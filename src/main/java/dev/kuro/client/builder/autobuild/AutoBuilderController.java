package dev.kuro.client.builder.autobuild;

import dev.kuro.client.builder.config.ClientConfig;
import dev.kuro.client.builder.freecam.FreecamController;
import dev.kuro.client.builder.mixin.BuilderClientLevelAccessor;
import dev.kuro.client.builder.mixin.BuilderLocalPlayerAccessor;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Vanilla interactions, bounded walking and server-confirmed construction. All access is on the client thread. */
public final class AutoBuilderController {
    public enum Status { IDLE, READY, RUNNING, PAUSED, COMPLETED, COLLECTING }
    private static final AutoBuilderController INSTANCE = new AutoBuilderController();
    /**
     * Ticks left for Minecraft to transmit the aim before an interaction. The rotation has to reach
     * the server before the click, and vanilla sends it once per tick, so one tick is the shortest
     * correct wait; one settling tick then keeps the prediction sequence ordered. Both used to be
     * two ticks, which cost roughly two ticks per placed block for no benefit.
     */
    private static final int ALIGN_TICKS = 1, SETTLE_TICKS = 1;
    /**
     * Standing positions tried for one target before it is set aside instead of wandered around.
     * Walking past a handful of neighbouring cells is still a genuine search, while the old
     * unbounded version spent fifteen seconds circling one block before blaming the reach.
     */
    private static final int UNPLACEABLE_ATTEMPTS = 8;
    /** Runaway guard for one target; ordinary walking counts as progress, not as failure. */
    private static final int REACH_TICKS = 600;
    /**
     * Consecutive interactions whose reply never arrived - neither an acknowledgement nor a block update -
     * that are retried before the build pauses. A single miss is usually a stale marker or a pour whose
     * water arrived by spreading from a neighbouring cell, not a broken connection, so stopping on the
     * first one left the build paused with a block that was plainly in the world.
     */
    private static final int CONFIRMATION_MISS_LIMIT = 3;
    /**
     * Automatic material runs allowed per build. Each one is already bounded by the collection budget,
     * and the cap settles storage that keeps handing over something other than what the build is short of.
     */
    private static final int AUTO_COLLECT_LIMIT = 4;
    /**
     * Automatic bucket refills allowed without a single confirmed block in between. Every pour that lands
     * resets this, so a large water body is not limited; a build that consumes buckets without building
     * anything stops asking for more water instead.
     */
    private static final int REFILL_LIMIT = 8;
    /**
     * Ticks the builder may spend trying to get its aim onto the wire for one target. Every other
     * re-align costs a tick, so this only fires when something keeps changing the view underneath,
     * such as held mouse movement - the loop it replaces never ended at all.
     */
    private static final int AIM_ATTEMPTS = 40;
    private final BuilderNavigator navigator = new BuilderNavigator();
    private final MaterialRestock restock = new MaterialRestock(navigator);
    private final BucketRefill refill = new BucketRefill(navigator);
    private ClientConfig config;
    private Runnable save;
    private MaterialStorage materialStorage;
    private boolean resumeAfterErrand;
    private boolean clearingLogged;
    private int autoCollections, refillsSinceProgress;
    private int inventoryTransferRetries;
    private Schematic schematic;
    private PlacementTransform transform = new PlacementTransform(BlockPos.ZERO, 0, false);
    private boolean previewVisible;
    private Status status = Status.IDLE;
    private String reason = "Choose a schematic.";
    private ClientLevel session;
    private List<Target> targets = List.of();
    private Target[] byIndex = new Target[0];
    private final NavigableSet<Target> work = new TreeSet<>(
            Comparator.comparingInt((Target t) -> t.pos.getY())
                    .thenComparingInt(t -> placementTier(t.state))
                    .thenComparingInt(Target::index)
    );
    private final Map<BlockPos, Integer> indices = new HashMap<>();
    private final BitSet correct = new BitSet();
    private final Map<BlockPos, Integer> failures = new HashMap<>();
    private final DeferredTargets deferred = new DeferredTargets();
    private final Set<BlockPos> supportBlocks = new HashSet<>();
    private final Map<BlockPos,Integer> unconfirmed = new HashMap<>();
    private ClientLevel predictionLevel;
    private Target current;
    private Prepared prepared;
    private Pending pending;
    private int tick, scanIndex, cooldown, noProgress, unplaceable, reachTicks, aimAttempts, revalidateIndex, finalCheckIndex, originalSlot = -1;
    /** Consecutive interactions that were never answered; reset whenever one is confirmed. */
    private int confirmationMisses;
    private BlockPos repeatedPos;
    private final Set<BlockState> repeatedStates = new HashSet<>();
    private boolean scanned;
    private boolean towerAttempted;
    private String lastFailure = "";
    /**
     * The level the file's own sources give each water cell, computed once per schematic. The model reads
     * the file, not the world, so a build never has to recompute it, and asking it per tick would be the
     * only cost of the water diagnostics.
     */
    private Map<BlockPos,Integer> flowLevels = Map.of();
    /**
     * False when the water model ran out of budget before it settled. Its answer is then only "not
     * reached yet", never "no source can reach it", so the pause reason must not claim the latter.
     */
    private boolean flowSettled = true;
    private BlockPos lastPlacedCoral;
    private boolean isEating;
    private int eatTicks;
    private int savedSlotBeforeEat = -1;
    private long lastPlacementTime;

    private final MiningConfirmation miningConfirmation = new MiningConfirmation();
    public record SyncMarker(BlockPos pos, int color, String reason, long expires) { }
    private final Map<BlockPos, SyncMarker> syncMarkers = new LinkedHashMap<>();
    private final Set<BlockPos> unknownMining = new HashSet<>();
    private final Deque<Long> placementsInMinute = new ArrayDeque<>();
    private final List<Pending> inFlight = new ArrayList<>();
    private long failedActions;
    public List<SyncMarker> syncMarkers() {
        long now = System.currentTimeMillis();
        syncMarkers.values().removeIf(marker -> marker.expires < now);
        return List.copyOf(syncMarkers.values());
    }
    private void syncMarker(BlockPos pos, int color, String why, boolean unresolved) {
        if (syncMarkers.size() >= 256 && !syncMarkers.containsKey(pos))
            syncMarkers.remove(syncMarkers.keySet().iterator().next());
        syncMarkers.put(pos.immutable(), new SyncMarker(pos.immutable(), color, why,
                unresolved ? Long.MAX_VALUE : System.currentTimeMillis() + 3000));
    }
    public record HudSnapshot(int total, int confirmed, int remaining, int layer, int layers,
                              int perMinute, int pending, long failed, int warnings) { }
    private HudSnapshot cachedHud;
    private long hudUpdatedAt;
    public HudSnapshot hudSnapshot() {
        if (cachedHud != null && System.currentTimeMillis()-hudUpdatedAt < 100) return cachedHud;
        hudUpdatedAt = System.currentTimeMillis();
        long now = System.currentTimeMillis();
        while (!placementsInMinute.isEmpty() && placementsInMinute.peekFirst() < now - 60000) placementsInMinute.removeFirst();
        int min = targets.isEmpty() ? 0 : targets.get(0).pos.getY();
        int max = min, layer = Integer.MAX_VALUE;
        if (!targets.isEmpty()) {
            max = targets.get(targets.size()-1).pos.getY();
            for (Target target : targets) {
                if (!correct.get(target.index)) { layer = target.pos.getY(); break; }
            }
        }
        int done = correct.cardinality();
        cachedHud = new HudSnapshot(targets.size(),done,targets.size()-done,
                targets.isEmpty() ? 0 : layer == Integer.MAX_VALUE ? max-min+1 : layer-min+1,
                targets.isEmpty() ? 0 : max-min+1,placementsInMinute.size(),
                (pending != null ? 1 : 0) + inFlight.size() + (navigator.hasPendingSupport() ? 1 : 0) + (miningPos != null ? 1 : 0),
                failedActions, unknownMining.size() + unconfirmed.size());
        return cachedHud;
    }

    private int miningTicks = 0;
    private BlockPos miningPos = null;

    /** A schematic cell, a temporary support, a water pour, or a temporary block clearing action. */
    record Target(BlockPos pos, BlockState state, int index, boolean support, boolean pour, boolean mine) {
        Target(BlockPos pos, BlockState state, int index, boolean support, boolean pour) { this(pos,state,index,support,pour,false); }
        Target(BlockPos pos, BlockState state, int index, boolean support) { this(pos,state,index,support,false,false); }
    }
    private record Prepared(Target target, PlacementPlanner.Plan plan, int readyTick) { }
    private static final class Pending {
        final Target target;
        final BlockState before, predicted;
        final int sentTick, sequence;
        final boolean sneak;
        boolean acknowledged;
        BlockState observed;
        Pending(Target target, BlockState before, BlockState predicted, int sentTick, int sequence, boolean sneak) {
            this.target=target; this.before=before; this.predicted=predicted; this.sentTick=sentTick; this.sequence=sequence;
            this.sneak=sneak;
        }
    }
    private AutoBuilderController() { }
    public static AutoBuilderController get() { return INSTANCE; }

    public static void log(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;
        AutoBuilderController c = get();
        if (c.config != null && !c.config.autoBuilder.chatLogs) return;
        client.player.sendSystemMessage(
                Component.literal("[Kuro Builder] ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(message).withStyle(ChatFormatting.WHITE)));
    }

    private BlockPos lastLoggedPos = null;
    private String lastLoggedStatus = null;

    public boolean isChatLogs() { return config == null || config.autoBuilder.chatLogs; }

    public void logTargetStatus(Target target, String status) {
        if (!isChatLogs()) return;
        if (target == null) {
            if (status != null && !status.equals(lastLoggedStatus)) {
                lastLoggedStatus = status;
                lastLoggedPos = null;
                log(status);
            }
            return;
        }
        if (!target.pos.equals(lastLoggedPos) || !status.equals(lastLoggedStatus)) {
            lastLoggedPos = target.pos;
            lastLoggedStatus = status;
            String name = target.state.getBlock().getName().getString();
            log(name + " at [" + coordinates(target.pos) + "] - " + status);
        }
    }
    public boolean isSubstitutesAllowed() { return config == null || config.autoBuilder.allowSubstitutes; }
    public boolean isAuctionStackOnly() { return config != null && config.autoBuilder.auctionStackOnly; }
    public boolean isSilentRotations() { return config != null && config.autoBuilder.silentRotations; }
    public boolean isAutoEat() { return config != null && config.autoBuilder.autoEat; }
    public int placesPerTick() { return config != null ? config.autoBuilder.placesPerTick : 1; }
    public int placementDelay() { return config != null ? config.autoBuilder.placementDelay : 0; }
    public boolean isFastPlace() { return config == null || config.autoBuilder.fastPlace; }
    public boolean isLocalSweep() { return config == null || config.autoBuilder.localSweep; }
    public boolean isSprint() { return config == null || config.autoBuilder.sprint; }
    public boolean isSprintJump() { return config == null || config.autoBuilder.sprintJump; }
    public boolean isAutoMove() { return config == null || config.autoBuilder.autoMove; }
    public boolean isSmoothRoutes() { return config == null || config.autoBuilder.smoothRoutes; }
    public double getReach() { return config != null ? config.autoBuilder.reach : 4.5; }
    public BuilderNavigator navigator() { return navigator; }

    public String setOriginLooking(Minecraft client) {
        if (client == null || client.level == null || client.player == null) return "Join a world first.";
        if (schematic == null) return "Choose a schematic first.";
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return "Look at a block to set origin.";
        }
        BlockPos pos = hit.getBlockPos();
        setTransform(new PlacementTransform(pos, transform.quarterTurns(), transform.mirrorX()));
        log("Origin set to target block: " + coordinates(pos));
        return "Origin set to target: " + coordinates(pos);
    }

    public String nudgeOrigin(int dx, int dy, int dz) {
        if (schematic == null) return "Choose a schematic first.";
        BlockPos pos = transform.origin().offset(dx, dy, dz);
        setTransform(new PlacementTransform(pos, transform.quarterTurns(), transform.mirrorX()));
        return "Origin nudged to " + coordinates(pos);
    }

    public void pasteSchematic(Minecraft client) {
        if (client == null || client.level == null || client.player == null) return;
        if (schematic == null) { log("Choose a schematic first."); return; }
        if (!client.hasSingleplayerServer() || client.getSingleplayerServer() == null) {
            log("'Paste Schematic' is singleplayer only (requires integrated server).");
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        ServerLevel serverLevel = server.getLevel(client.level.dimension());
        if (serverLevel == null) {
            log("Singleplayer server dimension not found.");
            return;
        }
        if (status == Status.RUNNING) cancel();

        List<Schematic.Block> blocks = schematic.blocks();
        log("Pasting " + blocks.size() + " blocks into singleplayer world at " + coordinates(transform.origin()) + "...");
        server.execute(() -> {
            int placed = 0;
            for (Schematic.Block b : blocks) {
                BlockPos worldPos = transform.apply(b.pos());
                BlockState worldState = transform.apply(b.state());
                serverLevel.setBlock(worldPos, worldState, 3);
                if (b.blockEntity() != null) {
                    BlockEntity be = serverLevel.getBlockEntity(worldPos);
                    if (be != null) {
                        try {
                            net.minecraft.world.level.storage.ValueInput input = net.minecraft.world.level.storage.TagValueInput.create(
                                    net.minecraft.util.ProblemReporter.DISCARDING,
                                    serverLevel.registryAccess(),
                                    b.blockEntity()
                            );
                            be.loadWithComponents(input);
                            be.setChanged();
                        } catch (Throwable ignored) { }
                    }
                }
                placed++;
            }
            final int totalPlaced = placed;
            client.execute(() -> {
                log("Pasted " + totalPlaced + " blocks successfully!");
            });
        });
    }

    public void printMaterials(Minecraft client) {
        if (client == null || client.player == null || schematic == null) {
            log("Choose a schematic first.");
            return;
        }
        MaterialTracker.Snapshot snapshot = MaterialTracker.snapshot(client, schematic, transform);
        client.player.sendSystemMessage(
                Component.literal("[Kuro Builder] ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("Materials for ").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(schematic.name()).withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(" (" + snapshot.totalBlocks() + " blocks total):").withStyle(ChatFormatting.WHITE)));
        for (MaterialTracker.Row row : snapshot.rows()) {
            if (row.item() == Items.AIR) continue;
            int carried = row.hotbar() + row.inventory();
            var line = Component.literal("  * ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(row.name()).withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(": ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(String.valueOf(row.remaining())).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" needed, ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(String.valueOf(carried)).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(" held").withStyle(ChatFormatting.WHITE));
            if (row.missing() > 0) {
                line.append(Component.literal(" (" + row.missing() + " missing)").withStyle(ChatFormatting.RED));
            } else {
                line.append(Component.literal(" (OK)").withStyle(ChatFormatting.DARK_GREEN));
            }
            client.player.sendSystemMessage(line);
        }
        client.player.sendSystemMessage(
                Component.literal("  Progress: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(snapshot.correctBlocks() + "/" + snapshot.totalBlocks() + " confirmed").withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(snapshot.conflicts() > 0 ? " (" + snapshot.conflicts() + " conflicts)" : "").withStyle(ChatFormatting.RED)));
    }

    /** Gives the controller the saved settings, so a chosen material storage is still there next launch. */
    public void attach(ClientConfig config, Runnable save) {
        this.config = config;
        this.save = save;
        materialStorage = MaterialStorage.parse(config.autoBuilder.materialStorage);
    }

    /** The container missing materials are taken from, or null when none is chosen. */
    public MaterialStorage materialStorage() { return materialStorage; }

    /** How the chosen container is shown in the manager. */
    public String materialStorageLabel() {
        if (materialStorage == null) return "Material storage: not set";
        BlockPos pos = materialStorage.pos();
        return "Material storage: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    /**
     * Chooses the container the player is looking at as the material source, which is the block they were
     * aiming at when this menu was opened. Returns the result for the manager to show.
     */
    public String chooseMaterialStorage(Minecraft client) {
        if (client.level == null || client.player == null) return "Join a world to choose material storage.";
        if (!(client.hitResult instanceof BlockHitResult hit)) return "Look at a chest, barrel or shulker box, then open this menu.";
        BlockPos pos = hit.getBlockPos();
        if (!MaterialStorage.isStorage(client.level.getBlockState(pos))) return "Look at a chest, barrel or shulker box, then open this menu.";
        if (client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > 36) return "That container is too far away to choose.";
        setMaterialStorage(new MaterialStorage(client.level.dimension().identifier().toString(), pos));
        return materialStorageLabel() + ". Missing materials are collected from it.";
    }

    /** Forgets the chosen material storage; collection falls back to nearby containers. */
    public void clearMaterialStorage() { setMaterialStorage(null); }

    /**
     * Chooses (or, with null, forgets) the container missing materials come from. The container is checked
     * against the world when collection starts, not here, so one that is only reachable later can be chosen
     * now. The choice is saved with the other settings.
     */
    public void setMaterialStorage(MaterialStorage value) {
        materialStorage = value;
        if (config != null) config.autoBuilder.materialStorage = value == null ? "" : value.encode();
        if (save != null) save.run();
    }
    public Schematic schematic() { return schematic; }
    public PlacementTransform transform() { return transform; }
    public boolean previewVisible() { return previewVisible; }
    public void setPreviewVisible(boolean value) { previewVisible = value && schematic != null; }
    public Status status() { return status; }
    public String reason() { return reason; }
    public boolean isRunning() { return status == Status.RUNNING; }
    public boolean isCollecting() { return errandRunning(); }
    public int confirmedBlocks() { return correct.cardinality(); }
    public double progress() { return restock.active() ? restock.progress() : refill.active() ? refill.progress()
            : targets.isEmpty() ? 0 : (double)correct.cardinality()/targets.size(); }
    public int supportsPlaced() { return supportBlocks.size(); }

    public void setSchematic(Schematic value) {
        cancel(); schematic = value; rebuildTargets(); previewVisible = value != null;
        status = value == null ? Status.IDLE : Status.READY;
        reason = value == null ? "Choose a schematic." : "Position the preview, then Start.";
    }
    public void setTransform(PlacementTransform value) {
        if (transform.equals(value)) return;
        cancel(); transform = Objects.requireNonNull(value); rebuildTargets();
        status = schematic == null ? Status.IDLE : Status.READY; reason = "Position updated.";
    }
    private void rebuildTargets() {
        indices.clear(); correct.clear(); failures.clear(); deferred.clear(); supportBlocks.clear(); scanIndex=0; scanned=false; towerAttempted=false;
        navigator.clearPlacedTowerBlocks();
        work.clear(); revalidateIndex=0; finalCheckIndex=0; flowLevels=Map.of(); flowSettled=true; lastPlacedCoral=null;
        if (schematic == null) { targets=List.of(); byIndex=new Target[0]; return; }
        List<Target> values = new ArrayList<>(schematic.blocks().size());
        for (Schematic.Block block : schematic.blocks()) {
            BlockPos pos = transform.apply(block.pos());
            int index=values.size(); values.add(new Target(pos,transform.apply(block.state()),index,false)); indices.put(pos,index);
        }
        // Low structural blocks become supports for later floors, attachments and redstone.
        values.sort(Comparator.comparingInt((Target t) -> t.pos.getY())
                .thenComparingInt(t -> placementTier(t.state)));
        targets=List.copyOf(values); byIndex=new Target[values.size()];
        for (Target target:values) byIndex[target.index]=target;
        work.addAll(values);
        WaterFlow.Spread flow=WaterFlow.spread(flowCells(), flowSources(), sourcesConvertToWater(), WaterFlow.SPREAD_BUDGET);
        flowLevels=flow.levels(); flowSettled=flow.settled();
    }

    /** The file's own water sources: the only places a recorded flowing level can be fed from. */
    private List<BlockPos> flowSources() {
        List<BlockPos> sources=new ArrayList<>();
        for (Target target:targets) if (MaterialTracker.isWaterSource(target.state) || MaterialTracker.needsOwnCellWater(target.state)) sources.add(target.pos);
        return sources;
    }

    private boolean hasPlannedWater(BlockPos pos) {
        if (schematic == null) return false;
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            Integer index = indices.get(neighbor);
            if (index != null) {
                BlockState state = transform.apply(schematic.blocks().get(index).state());
                if (MaterialTracker.isWater(state) || MaterialTracker.holdsWater(state) || MaterialTracker.needsOwnCellWater(state)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the world's water source conversion rule is on, which the pond rule needs. The client is not
     * told the rule, so it is read from the integrated server when the build runs in one and taken at its
     * default otherwise, which is on.
     */
    private boolean sourcesConvertToWater() {
        MinecraftServer server=Minecraft.getInstance().getSingleplayerServer();
        return server==null || server.overworld().getGameRules().get(GameRules.WATER_SOURCE_CONVERSION);
    }

    /**
     * The world the flow model reads: the file's blocks, described in the terms the game's own rules ask in,
     * so the model can run the game's tick over it. A cell the file does not describe is a wall to the water
     * but not a solid block the pond rule would stand on, because the file merely cuts there.
     */
    private WaterFlow.Cells flowCells() {
        return new WaterFlow.Cells() {
            @Override public WaterFlow.Fluid fluid(BlockPos pos) {
                BlockState state=fileState(pos);
                if (state==null) return WaterFlow.Fluid.NONE;
                FluidState fluid=state.getFluidState();
                if (!fluid.is(FluidTags.WATER)) return fluid.isEmpty() ? WaterFlow.Fluid.NONE : WaterFlow.Fluid.OTHER;
                // A cell the file records as flowing holds a source's water only until the game derives it
                // back: leaving it in would let the flow feed itself, so the model starts without it.
                return fluid.isSource() ? WaterFlow.Fluid.SOURCE : WaterFlow.Fluid.NONE;
            }
            @Override public boolean holdsAnyFluid(BlockPos pos) {
                BlockState state=fileState(pos); return state!=null && WaterFlow.holdsAnyFluid(state);
            }
            @Override public boolean holdsFluid(BlockPos pos, boolean water) {
                BlockState state=fileState(pos);
                if (state==null || !WaterFlow.holdsAnyFluid(state)) return false;
                if (!(state.getBlock() instanceof LiquidBlockContainer container)) return true;
                Minecraft client=Minecraft.getInstance();
                return client.level!=null
                        && container.canPlaceLiquid(null,client.level,pos,state,water ? Fluids.WATER : Fluids.EMPTY);
            }
            @Override public boolean waterloggable(BlockPos pos) {
                BlockState state=fileState(pos);
                return state!=null && state.getBlock() instanceof LiquidBlockContainer;
            }
            @Override public VoxelShape shape(BlockPos pos) {
                // The same call the game's own wall check makes, so water passes exactly where it would.
                BlockState state=fileState(pos);
                if (state==null) return Shapes.block();
                Minecraft client=Minecraft.getInstance();
                return client.level==null ? Shapes.empty() : state.getCollisionShape(client.level,pos);
            }
            @Override public boolean solid(BlockPos pos) {
                BlockState state=fileState(pos); return state!=null && state.isSolid();
            }
        };
    }

    /** The file's state for a world position, or null when the build does not describe that cell. */
    private BlockState fileState(BlockPos pos) {
        Integer index=indices.get(pos);
        return index==null || schematic==null ? null : transform.apply(schematic.blocks().get(index).state());
    }

    /**
     * Priorities for building within each Y level:
     * Solid structural blocks first, then directional redstone components, then surface attachments
     * (wire, repeaters, pickles), water sources after containment, falling blocks, and paired tops.
     */
    static int placementTier(BlockState state) {
        if (MaterialTracker.isSecondary(state)) return 5;
        if (state.getBlock() instanceof FallingBlock) return 4;
        if (MaterialTracker.isWaterSource(state)) return 3;
        if (state.is(Blocks.REDSTONE_WIRE) || state.getBlock() instanceof RepeaterBlock
                || state.getBlock() instanceof ComparatorBlock || state.is(Blocks.REDSTONE_TORCH)
                || state.is(Blocks.REDSTONE_WALL_TORCH)
                || state.getBlock() instanceof SeaPickleBlock || state.getBlock() instanceof TorchBlock
                || state.getBlock() instanceof ButtonBlock || state.getBlock() instanceof LeverBlock) {
            return 2;
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                || state.getBlock() instanceof DispenserBlock || state.getBlock() instanceof DropperBlock
                || state.getBlock() instanceof ObserverBlock || state.getBlock() instanceof HopperBlock) {
            return 1;
        }
        return 0;
    }

    public void start() {
        Minecraft client=Minecraft.getInstance();
        if (schematic == null || targets.isEmpty()) { reason="Load a schematic containing blocks."; return; }
        if (!canRun(client)) return;
        if (restock.active()) restock.cancel();
        if (refill.active()) refill.cancel();
        cancelErrands();
        if (predictionLevel!=client.level) { unconfirmed.clear(); unknownMining.clear(); syncMarkers.clear(); predictionLevel=client.level; }
        placementsInMinute.clear(); failedActions=0; cachedHud=null;
        correctionRecoveryTicks=0; stableCorrectionTicks=0;
        correct.clear(); failures.clear(); deferred.clear(); scanIndex=0; scanned=false;
        work.clear(); revalidateIndex=0; finalCheckIndex=0;
        session=client.level; tick=0; noProgress=0; confirmationMisses=0; lastFailure=""; current=null; pending=null; prepared=null;
        inFlight.clear();
        clearingLogged=false;
        inventoryTransferRetries=0;
        originalSlot=client.player.getInventory().getSelectedSlot(); status=Status.RUNNING; previewVisible=true;
        client.player.setShiftKeyDown(false);
        reason="Checking loaded blocks...";
        log("Started build: " + (schematic != null ? schematic.name() : "schematic") + " (" + targets.size() + " blocks).");
    }
    public void pause() { pauseWith("Paused by you."); }
    private void pauseWith(String message) {
        if (status != Status.RUNNING && status != Status.PAUSED && status != Status.COLLECTING) { reason=message; return; }
        stopMining(Minecraft.getInstance());
        inventoryTransfer.cancel();
        stopAutoEat(Minecraft.getInstance());
        if (isSilentRotations()) dev.kuro.client.util.RotationHandler.clear();
        boolean wasActive = status == Status.RUNNING || status == Status.COLLECTING;
        status=Status.PAUSED; reason=message; prepared=null; inFlight.clear(); navigator.release(); restoreSlot();
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.setShiftKeyDown(false);
        if (wasActive && !message.startsWith("Checking the material storage")
                && !message.startsWith("Filling an empty bucket")
                && !message.startsWith("Opening auction")) {
            log("Paused: " + message);
        }
    }
    public void resume() {
        if (status != Status.PAUSED) { start(); return; }
        Minecraft client=Minecraft.getInstance();
        if (!canRun(client)) return;
        if (session != client.level) { start(); return; }
        failures.clear(); deferred.clear(); lastFailure=""; noProgress=0; confirmationMisses=0; current=pending == null ? null : pending.target; prepared=null; navigator.reset();
        refillsSinceProgress=0;
        work.clear(); for (Target target:targets) if (!correct.get(target.index)) work.add(target);
        clearingLogged=false;
        inventoryTransferRetries=0;
        originalSlot=client.player.getInventory().getSelectedSlot(); status=Status.RUNNING; reason="Resuming...";
        if (client.player != null) client.player.setShiftKeyDown(false);
        log("Resumed building (" + (targets.size() - correct.cardinality()) + " blocks remaining).");
    }
    private boolean canRun(Minecraft client) {
        if (client.level == null || client.player == null || client.gameMode == null) { reason="Join a world before starting."; return false; }
        if (FreecamController.enabled()) { reason="Disable Freecam before resuming Kuro Builder."; return false; }
        if (client.gameMode.isSpectator()) { reason="Spectator mode cannot place blocks."; return false; }
        if (client.player.isPassenger()) { reason="Leave the vehicle before starting Kuro Builder."; return false; }
        if (client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> containerScreen) {
            if (client.player != null) client.player.closeContainer();
            containerScreen.onClose();
            if (client.gui.screen() == containerScreen) client.gui.setScreen(null);
        }
        if (client.gui.screen() != null) { reason="Close the open menu before resuming."; return false; }
        return true;
    }
    public void cancel() {
        stopMining(Minecraft.getInstance());
        cancelErrands();
        stopAutoEat(Minecraft.getInstance());
        if (isSilentRotations()) dev.kuro.client.util.RotationHandler.clear();
        navigator.reset(); navigator.clearPlacedTowerBlocks(); restoreSlot(); current=null; prepared=null; pending=null; inFlight.clear(); session=null; towerAttempted=false;
        failures.clear(); deferred.clear(); cooldown=0;
        clearingLogged=false;
        inventoryTransferRetries=0;
        status=schematic == null ? Status.IDLE : Status.READY; reason="Build cancelled.";
        log("Build cancelled.");
    }
    public void disconnect() {
        stopMining(Minecraft.getInstance());
        stopAutoEat(Minecraft.getInstance());
        if (isSilentRotations()) dev.kuro.client.util.RotationHandler.clear();
        if (errandRunning()) { cancelErrands(); status=schematic == null ? Status.IDLE : Status.READY; reason="Collecting stopped: disconnected."; }
        if (isRunning() || status==Status.PAUSED) { pauseWith("Disconnected. Rejoin, check the position, then Resume."); }
        navigator.reset(); navigator.clearPlacedTowerBlocks(); restoreSlot(); session=null; pending=null; prepared=null; inFlight.clear(); current=null; correct.clear(); scanIndex=0; scanned=false; towerAttempted=false;
        unknownMining.clear(); syncMarkers.clear(); placementsInMinute.clear(); cachedHud=null;
        unconfirmed.clear(); predictionLevel=null; deferred.clear(); unplaceable=0; reachTicks=0;
        previewVisible=false;
    }

    private boolean tickAutoEat(Minecraft client) {
        if (client == null || client.player == null || client.gameMode == null || config == null || !config.autoBuilder.autoEat) {
            stopAutoEat(client);
            return false;
        }
        if (client.player.getAbilities().instabuild) {
            stopAutoEat(client);
            return false;
        }
        int foodLevel = client.player.getFoodData().getFoodLevel();
        if (isEating) {
            eatTicks++;
            navigator.hold(client, false);
            if (foodLevel >= 20 || eatTicks > 45 || !client.player.isAlive()) {
                stopAutoEat(client);
                return false;
            }
            client.options.keyUse.setDown(true);
            reason = "Eating food (hunger " + foodLevel + "/20)...";
            return true;
        }
        if (foodLevel <= config.autoBuilder.eatAt) {
            int bestSlot = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack s = client.player.getInventory().getItem(i);
                if (isFoodItem(s)) {
                    bestSlot = i;
                    break;
                }
            }
            if (bestSlot < 0) {
                for (int i = 9; i < 36; i++) {
                    ItemStack s = client.player.getInventory().getItem(i);
                    if (isFoodItem(s)) {
                        int cur = client.player.getInventory().getSelectedSlot();
                        client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, i, cur, ContainerInput.SWAP, client.player);
                        bestSlot = cur;
                        break;
                    }
                }
            }
            if (bestSlot >= 0) {
                savedSlotBeforeEat = client.player.getInventory().getSelectedSlot();
                client.player.getInventory().setSelectedSlot(bestSlot);
                isEating = true;
                eatTicks = 0;
                client.options.keyUse.setDown(true);
                reason = "Eating food (hunger " + foodLevel + "/20)...";
                return true;
            } else if (foodLevel <= 3) {
                pauseWith("Out of food: hunger " + foodLevel + "/20 and no food in inventory. Add food, then Resume.");
                return true;
            }
        }
        return false;
    }

    private void stopAutoEat(Minecraft client) {
        if (!isEating && savedSlotBeforeEat < 0) return;
        isEating = false;
        eatTicks = 0;
        if (client != null && client.options != null && client.options.keyUse != null) {
            client.options.keyUse.setDown(false);
        }
        if (client != null && client.player != null && savedSlotBeforeEat >= 0) {
            client.player.getInventory().setSelectedSlot(savedSlotBeforeEat);
            savedSlotBeforeEat = -1;
        }
    }

    private static boolean isFoodItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.get(DataComponents.FOOD) == null) return false;
        Item item = stack.getItem();
        return item != Items.ROTTEN_FLESH && item != Items.SPIDER_EYE && item != Items.PUFFERFISH
                && item != Items.POISONOUS_POTATO && item != Items.CHORUS_FRUIT;
    }

    private void restoreSlot() {
        stopAutoEat(Minecraft.getInstance());
        var player=Minecraft.getInstance().player;
        if (player != null && originalSlot >= 0) player.getInventory().setSelectedSlot(originalSlot);
        originalSlot=-1;
    }

    public void tick(Minecraft client) {
        if (!isRunning() && !errandRunning()) return;
        tick++;
        for (var key:List.of(client.options.keyAttack,client.options.keyUse,client.options.keyDrop,client.options.keySwapOffhand)) {
            key.setDown(false); while (key.consumeClick()) { }
        }
        if (errandRunning()) { tickErrand(client); return; }
        if (!isRunning()) return;
        if (client.level == null || client.player == null || client.gameMode == null || client.level != session) { disconnect(); return; }
        if (tickAutoEat(client)) return;
        if (FreecamController.enabled()) { pauseWith("Freecam is active. Disable it, then Resume."); return; }
        if (client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen signScreen) {
            signScreen.onClose();
        }
        if (client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> containerScreen) {
            if (client.player != null) client.player.closeContainer();
            containerScreen.onClose();
            if (client.gui.screen() == containerScreen) client.gui.setScreen(null);
        }
        if (client.gui.screen() != null || client.isPaused()) { pauseWith("An open menu paused construction. Close it, then Resume."); return; }
        if (!client.player.isAlive() || client.player.isRemoved()) { pauseWith("Player unavailable. Resume after respawning."); return; }
        if (client.player.isPassenger()) { pauseWith("Leave the vehicle, then Resume."); return; }
        if (client.player.isUsingItem() && !isEating) { pauseWith("Finish using the held item, then Resume."); return; }
        navigator.pollSupport(client);
        if (navigator.hasPendingSupport()) { reason="Waiting for server confirmation of navigation support..."; return; }
        if (scanned) revalidate(client,64);
        pollInFlight(client);
        if (pending != null) {
            // Hold the pose the placement was sent with. Releasing it here made the crouch toggle on
            // every block, which moved the eye height under the next plan and had the builder re-aim.
            navigator.hold(client,pending.sneak);
            if (pending.acknowledged || pending.observed != null || tick-pending.sentTick > 60) finishPending(client);
            return;
        }
        int maxInFlight = Math.max(4, placesPerTick() * 2);
        if (inFlight.size() >= maxInFlight) {
            reason = "Waiting for placement pipeline (" + inFlight.size() + "/" + maxInFlight + ")...";
            return;
        }
        if (config != null && config.autoBuilder.placementDelay > 0) {
            long now = System.currentTimeMillis();
            if (now - lastPlacementTime < config.autoBuilder.placementDelay) {
                return;
            }
        }
        if (correctionRecoveryTicks > 0) {
            navigator.hold(client,false);
            boolean settled = client.player.onGround() || client.player.isInWater();
            stableCorrectionTicks = settled ? stableCorrectionTicks + 1 : 0;
            if (stableCorrectionTicks >= 3) {
                correctionRecoveryTicks = 0;
                navigator.replan();
                reason = "Position settled. Rechecking approach...";
            } else if (++correctionRecoveryTicks > 100) {
                correctionRecoveryTicks = 0;
                navigator.replan();
                reason = "Position reset after correction. Retrying approach...";
            }
            return;
        }
        if (prepared != null) {
            navigator.hold(client,shouldSneak(client,prepared.target,prepared.plan));
            if (tick>=prepared.readyTick || isSilentRotations()) sendPrepared(client);
            else aim(client,prepared.plan);
            return;
        }
        if (cooldown>0) { cooldown--; return; }
        if (!scanned) {
            int end=Math.min(targets.size(),scanIndex+512);
            for (;scanIndex<end;scanIndex++) {
                Target target=targets.get(scanIndex);
                if (client.level.isOutsideBuildHeight(target.pos)) { pauseWith("Outside world build height at " + coordinates(target.pos) + ". Reposition the schematic."); return; }
                if (!client.level.getWorldBorder().isWithinBounds(target.pos)) { pauseWith("Outside the world border at " + coordinates(target.pos) + ". Reposition the schematic."); return; }
                if (authoritativeSupport(target.pos) && MaterialTracker.isLoaded(client.level,target.pos)
                        && MaterialTracker.matches(target.state,client.level.getBlockState(target.pos))) {
                    markCorrect(target);
                } else {
                    work.add(target);
                }
            }
            if (scanIndex<targets.size()) return;
            scanned=true;
        }
        // Cleanup targets must execute before the completed-schematic branch selects them again.
        if (current != null && current.mine()) {
            tickMining(client,current);
            return;
        }
        if (correct.cardinality()==targets.size()) {
            if (config == null || config.autoBuilder.clearTempBlocks) {
                if (!supportBlocks.isEmpty()) {
                    BlockPos nextTemp = findNextTempBlockToClear(client);
                    if (nextTemp != null) {
                        if (!clearingLogged) { clearingLogged = true; log("Clearing temporary support blocks..."); }
                        current = new Target(nextTemp, client.level.getBlockState(nextTemp), -1, false, false, true);
                        reason = "Clearing temporary support block at " + coordinates(nextTemp);
                        return;
                    }
                }
                if (navigator.hasPlacedTowerBlocks()) {
                    BlockPos nextToClean = navigator.popHighestTowerBlock(client);
                    if (nextToClean != null) {
                        if (!clearingLogged) { clearingLogged = true; log("Clearing temporary tower blocks..."); }
                        current = new Target(nextToClean, client.level.getBlockState(nextToClean), -1, false, false, true);
                        reason = "Clearing temporary tower block at " + coordinates(nextToClean);
                        return;
                    }
                }
            }
            navigator.release(); reason="Verifying the completed structure...";
            int end=Math.min(targets.size(),finalCheckIndex+2048);
            for (;finalCheckIndex<end;finalCheckIndex++) {
                Target target=targets.get(finalCheckIndex);
                if (!authoritativeSupport(target.pos) || !MaterialTracker.isLoaded(client.level,target.pos)
                        || !MaterialTracker.matches(target.state,client.level.getBlockState(target.pos))) {
                    correct.clear(target.index); work.add(target); finalCheckIndex=0; return;
                }
            }
            if (finalCheckIndex==targets.size()) complete();
            return;
        }
        finalCheckIndex=0;
        int maxPlaces = isFastPlace() ? placesPerTick() : 1;
        int placedCount = 0;
        while (placedCount < maxPlaces && isRunning() && pending == null && prepared == null && cooldown == 0) {
            if (config != null && config.autoBuilder.placementDelay > 0 && placedCount > 0) {
                long now = System.currentTimeMillis();
                if (now - lastPlacementTime < config.autoBuilder.placementDelay) break;
            }
            if (inFlight.size() >= maxInFlight) break;

            boolean placed = tickPlacementStep(client);
            if (placed) {
                placedCount++;
            } else {
                break;
            }
        }
        if (isSilentRotations() && current == null) {
            dev.kuro.client.util.RotationHandler.clear();
        }
        if (prepared == null && pending == null && (current == null || (!navigator.isNavigating() && !navigator.isTowering()))) {
            navigator.release();
        }
    }

    private boolean tickPlacementStep(Minecraft client) {
        if (current == null) {
            current=chooseTarget(client);
            // A newly selected target starts with a fresh attempt, reach and aim budget.
            unplaceable=0; reachTicks=0; aimAttempts=0;
            towerAttempted=false;
            if (current != null) {
                logTargetStatus(current, "Target selected");
            }
        }
        reachTicks++;
        if (current == null) {
            // Keep trying: reset deferred so the builder constantly tries available/reachable targets
            retryDeferred(client);
            current = chooseTarget(client);
            if (current == null) {
                // If really nothing is currently selectable, wait a few ticks and retry instead of hard pausing
                cooldown = 20;
                reason = "Searching for workable blocks...";
                logTargetStatus(null, "Searching for workable blocks...");
                navigator.release();
                return false;
            }
        }
        if (current.mine()) {
            tickMining(client, current);
            return false;
        }
        if (client.level.isOutsideBuildHeight(current.pos)) { pauseWith("Outside world build height at " + coordinates(current.pos) + ". Reposition the schematic."); return false; }
        if (!client.level.getWorldBorder().isWithinBounds(current.pos)) { pauseWith("Outside the world border at " + coordinates(current.pos) + ". Reposition the schematic."); return false; }
        if (!MaterialTracker.isLoaded(client.level,current.pos)) { deferUnloaded("Area is unloaded at " + coordinates(current.pos) + ". Move closer, then Resume."); return false; }
        if (!authoritativeSupport(current.pos)) {
            defer("Still waiting for authoritative state at " + coordinates(current.pos) + ". Resume after the server updates it.");
            return false;
        }
        while (current != null && !current.mine() && MaterialTracker.matches(current.state, client.level.getBlockState(current.pos))) {
            markCorrect(current);
            current = chooseTarget(client);
            unplaceable = 0; reachTicks = 0; aimAttempts = 0;
        }
        if (current == null) return false;
        BlockState actual=client.level.getBlockState(current.pos);
        // Live coral fans/plants cannot survive dry even for a moment, so water must be placed first.
        // Other waterloggable blocks (leaves, fence gates, stairs, slabs, pickles) are placed first and waterlogged after.
        if (MaterialTracker.mustBePlacedInWater(current.state)) {
            Target pour=waterFirst(client,current);
            if (pour!=null) {
                if (!sealedForWater(client,pour.pos)) {
                    defer("The water at " + coordinates(pour.pos) + " would run into cells the build does not describe. "
                            + "Build the blocks around it first, then Resume.",DeferredTargets.Cause.AWAITING_NEIGHBOURS); return false;
                }
                boolean protect = config == null || config.autoBuilder.protectRedstoneFromWater;
                if (protect && waterWouldBreakRedstone(client, pour.pos)) {
                    defer("Water at " + coordinates(pour.pos) + " is not yet contained and would break redstone. "
                            + "Build the blocks around it first, then Resume.", DeferredTargets.Cause.AWAITING_NEIGHBOURS); return false;
                }
                if (findSlot(client,Items.WATER_BUCKET)<0) {
                    fetchMissingMaterial(client,Items.WATER_BUCKET," for the water held in the cell at " + coordinates(current.pos)); return false;
                }
                current=pour; return false;
            }
        }
        if (MaterialTracker.needsWater(current.state) && !waterContact(client,current.pos) && !hasPlannedWater(current.pos)) {
            defer("Alive coral at " + coordinates(current.pos) + " needs water beside it first. Fill or place the water around it, then Resume.",
                    DeferredTargets.Cause.AWAITING_NEIGHBOURS); return false;
        }
        if (MaterialTracker.isSecondary(current.state)) { defer("Paired block is missing at " + coordinates(current.pos) + ". Check its door, bed or plant base."); return false; }
        String manual=manualStateReason(current.state,actual);
        if (manual!=null) { defer(manual + " at " + coordinates(current.pos) + ". Complete it manually, then Resume."); return false; }
        if (!PlacementPlanner.canAdd(current.state,actual) && !PlacementPlanner.needsConfiguration(current.state,actual)) {
            // The requested block can already be there and still differ in a state the world writes: a
            // gate's wall state, a stair's or fence's connections, a redstone component's power. That is
            // not an occupied or incorrect block - the surrounding placements resolve it - so the position
            // stays queued for later progress instead of stopping the build for a manual fix.
            List<String> waiting=PlacementPlanner.awaitingNeighbours(current.state,actual);
            if (!waiting.isEmpty()) {
                if (current.state.getBlock() instanceof ChestBlock && current.state.hasProperty(BlockStateProperties.CHEST_TYPE)
                        && current.state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE
                        && actual.getBlock() instanceof ChestBlock && actual.hasProperty(BlockStateProperties.CHEST_TYPE)
                        && actual.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE) {
                    Direction dir = PlacementPlanner.getConnectedDirection(current.state);
                    if (dir != null) {
                        BlockPos partnerPos = current.pos.relative(dir);
                        BlockState partner = client.level.getBlockState(partnerPos);
                        if (partner.getBlock() == current.state.getBlock() && partner.hasProperty(BlockStateProperties.CHEST_TYPE)
                                && partner.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE) {
                            current = new Target(current.pos, actual, -1, false, false, true);
                            return false;
                        }
                    }
                }
                defer("The block at " + coordinates(current.pos) + " is placed, but its " + String.join("/",waiting)
                        + " state follows its neighbours. Build the blocks around it, then Resume.",
                        DeferredTargets.Cause.AWAITING_NEIGHBOURS);
                return false;
            }
            if (supportBlocks.contains(current.pos)) {
                current = new Target(current.pos, actual, -1, false, false, true);
                return false;
            }
            defer("Occupied or incorrect block at " + coordinates(current.pos) + ". Correct it manually, then Resume."); return false;
        }
        var requirement=MaterialTracker.requirement(current.state);
        // A water cell is poured from a bucket rather than placed, so its bucket is a supported item here.
        // Only a source is poured, though: a cell recorded as a flowing level is fed by the sources
        // beside or above it, and pouring one there would flatten the flow the file describes.
        boolean poured=MaterialTracker.poursBucket(current.state);
        if (MaterialTracker.isWaterFlow(current.state) && !poured) {
            // A flowing level is one the game derives from a source or a fall the file also describes, and
            // the flow model plays that derivation out. A cell it reaches is waiting on blocks being built;
            // a cell it does not reach has no arrangement of this build's own water that produces it, so the
            // source has to be added or the selection widened.
            int level=current.state.hasProperty(LiquidBlock.LEVEL) ? current.state.getValue(LiquidBlock.LEVEL) : 0;
            if (flowLevels.getOrDefault(current.pos,-1)>=0) {
                if (config == null || config.autoBuilder.flowTempBlocks) {
                    Target guide = WaterFlowGuide.findFlowGuideForUnreachedFlow(client, current.pos, flowLevels, this::fileState, this::findScaffoldMaterial);
                    if (guide != null) { current = guide; return false; }
                }
                defer("The water at " + coordinates(current.pos) + " is recorded as flowing water, which the sources around it feed. "
                        + "Build those, then Resume.",DeferredTargets.Cause.AWAITING_NEIGHBOURS); return false;
            }
            if (!flowSettled) {
                defer("The water at " + coordinates(current.pos) + " is recorded as flowing water, and whether this build's own "
                        + "water feeds it could not be worked out: the water model is bounded and this selection is large enough to "
                        + "run it out. Build the water around it, then Resume.",DeferredTargets.Cause.AWAITING_NEIGHBOURS); return false;
            }
            String needs=level>=WaterFlow.FALLING_LEVEL
                    ? "water records that level when it is falling, and water only falls into a cell with water above it, so this cell needs water above it, fed by a source within seven blocks"
                    : "water spreads one level per block from a source, so this cell needs a source within " + level + " blocks of it, or water falling onto it";
            defer("The water at " + coordinates(current.pos) + " is recorded as flowing water at level " + level
                    + ", but nothing in this build can feed it: " + needs
                    + ", and the file has neither inside the selection. Add the source, or select the water body that feeds it, then Resume.",
                    DeferredTargets.Cause.AWAITING_NEIGHBOURS); return false;
        }
        if (requirement.item()==Items.AIR || !(requirement.item() instanceof BlockItem || poured)) {
            defer("This block requires manual placement: " + current.state.getBlock().getName().getString() + " at " + coordinates(current.pos)); return false;
        }
        boolean configuring=PlacementPlanner.needsConfiguration(current.state,actual);
        boolean waterlogging=PlacementPlanner.needsWaterlogging(current.state,actual);
        if (poured) {
            if (config == null || config.autoBuilder.flowTempBlocks) {
                Target guide = WaterFlowGuide.findFlowGuide(client, current.pos, flowLevels, this::fileState, this::findScaffoldMaterial);
                if (guide != null) { current = guide; return false; }
            }
        }
        if (poured && !sealedForWater(client,current.pos)) {
            defer("The water at " + coordinates(current.pos) + " would run into cells the build does not describe. "
                    + "Build the blocks around it, then Resume.",DeferredTargets.Cause.AWAITING_NEIGHBOURS); return false;
        }
        boolean protect = config == null || config.autoBuilder.protectRedstoneFromWater;
        if (protect && (poured || current.pour || waterlogging)) {
            if (waterWouldBreakRedstone(client, current.pos)) {
                if (current.pour) {
                    Integer idx = indices.get(current.pos);
                    Target orig = idx != null ? byIndex[idx] : null;
                    if (orig != null) {
                        defer(orig, "Water at " + coordinates(current.pos) + " is not yet contained and would break redstone. Waiting for containment blocks.", DeferredTargets.Cause.AWAITING_NEIGHBOURS);
                        current = chooseTarget(client);
                        return false;
                    }
                }
                defer("Water at " + coordinates(current.pos) + " is not yet contained and would break redstone. "
                        + "Build containment blocks around it first, then Resume.", DeferredTargets.Cause.AWAITING_NEIGHBOURS);
                return false;
            }
        }
        // A fluid is poured against the block behind it, not supported from below.
        if (!poured && (!current.state.canSurvive(client.level,current.pos) || !hasRequiredPlacementSupport(client,current))) {
            Target fix = findSchematicSupport(client, current);
            if (fix != null && hasMaterial(client, fix)) {
                current = fix;
                return false;
            }
            Target temp = necessarySupport(client, current);
            if (temp == null) temp = sideSupport(client, current);
            if (temp == null) temp = lateralSupport(client, current);
            if (temp == null) temp = accessSupport(client, current);
            if (temp != null) {
                current = temp;
                return false;
            }
            if (config!=null && config.autoBuilder.buyMaterialsFromAuction && findScaffoldMaterial()==null) {
                fetchMissingMaterial(client,Items.DIRT," for required temporary supports");
                return false;
            }
            Target missingSupportTarget = current;
            current = null;
            defer(missingSupportTarget, "Missing support at " + coordinates(missingSupportTarget.pos) + ". Waiting for supporting blocks.",
                    DeferredTargets.Cause.AWAITING_NEIGHBOURS);
            current = chooseTarget(client);
            return false;
        }
        int slot = configuring ? client.player.getInventory().getSelectedSlot()
                : waterlogging ? findSlot(client, Items.WATER_BUCKET)
                : findSlot(client, requirement.item());

        // In Creative mode, auto-provide the missing item into selected slot
        if (slot < 0 && client.player.getAbilities().instabuild && client.gameMode != null && client.gameMode.getPlayerMode().isCreative()) {
            int selected = client.player.getInventory().getSelectedSlot();
            Item neededItem = waterlogging ? Items.WATER_BUCKET : requirement.item();
            ItemStack needed = new ItemStack(neededItem);
            client.player.getInventory().setItem(selected, needed);
            client.gameMode.handleCreativeModeItemAdd(needed, 36 + selected);
            slot = selected;
        }

        boolean allowAuction = config != null && config.autoBuilder.buyMaterialsFromAuction;

        // Check for substitute items in survival ONLY if auction buying is NOT enabled
        if (slot < 0 && !allowAuction && (config == null || config.autoBuilder.allowSubstitutes)) {
            slot = findSubstituteSlot(client, requirement.item(), current.state);
        }

        if (slot < 0) {
            if (allowAuction) {
                fetchMissingMaterial(client,waterlogging ? Items.WATER_BUCKET : requirement.item(),
                        " at " + coordinates(current.pos));
                return false;
            }
            Target missingTarget = current;
            current = null;
            defer("Missing " + requirement.item().getDefaultInstance().getHoverName().getString() + " at " + coordinates(missingTarget.pos)
                            + ". Auction buying is OFF; enable Buy from auction in the Kuro Builder window to purchase.",
                    DeferredTargets.Cause.AWAITING_NEIGHBOURS);
            current = chooseTarget(client);
            if (current != null) return false;
            current = missingTarget;
            fetchMissingMaterial(client, waterlogging ? Items.WATER_BUCKET : requirement.item(),
                    waterlogging ? " to waterlog the " + current.state.getBlock().getName().getString() + " at " + coordinates(current.pos) : "");
            return false;
        }
        if (slot >= 9) {
            swapMaterialToHotbar(client, slot);
            slot = client.player.getInventory().getSelectedSlot();
            log("Swapped " + requirement.item().getDefaultInstance().getHoverName().getString() + " into hotbar.");
        }
        client.player.getInventory().setSelectedSlot(slot);
        var plan=PlacementPlanner.plan(client,current.pos,current.state,client.player.getMainHandItem());
        if (plan != null) {
            boolean sneak=shouldSneak(client,current,plan);
            navigator.hold(client,sneak);
            boolean transmitted=transmitted(client,plan,sneak);
            aim(client,plan);
            int alignDelay = isSilentRotations() ? 0 : ALIGN_TICKS;
            prepared=new Prepared(current,plan,tick+alignDelay);
            reason=(current.support ? "Placing a temporary support" : waterlogging ? "Waterlogging block" : plan.configure() ? "Setting block state" : "Placing block") + " at " + coordinates(current.pos);
            logTargetStatus(current, plan.configure() ? "Configuring block" : "Placing block");
            noProgress=0;
            if (transmitted || isSilentRotations()) {
                sendPrepared(client);
                return current == null;
            }
            return false;
        }
        if (!isAutoMove()) {
            Target unreachable = current;
            current = null;
            defer(unreachable, "Out of reach and Auto Move is OFF. Move closer, then Resume.",
                    DeferredTargets.Cause.AWAITING_NEIGHBOURS);
            current = chooseTarget(client);
            return false;
        }
        if (reachTicks > REACH_TICKS) {
            Target unreachable = current;
            current = null;
            defer(unreachable, "Cannot reach a placement position at " + coordinates(unreachable.pos) + " within " + (REACH_TICKS / 20) + " seconds.",
                    DeferredTargets.Cause.AWAITING_NEIGHBOURS);
            current = chooseTarget(client);
            return false;
        }
        navigator.setBridgingAllowed(config == null || config.autoBuilder.bridging);
        navigator.setTowerAllowed(config == null || config.autoBuilder.towerWhenStuck);
        navigator.setSprint(isSprint());
        navigator.setSprintJump(isSprintJump());
        navigator.setSmoothRoutes(isSmoothRoutes());
        navigator.setInSchematicPredicate(indices::containsKey);
        navigator.setExternalSupportPredicate(pos -> !unconfirmed.containsKey(pos)
                && !unknownMining.contains(pos) && !miningConfirmation.active(pos));
        navigator.setBridgePlacedCallback(pos -> {
            if (!indices.containsKey(pos)) supportBlocks.add(pos);
        });
        if (isSilentRotations()) dev.kuro.client.util.RotationHandler.clear();
        BuilderNavigator.Result movement = navigator.moveTo(client, current.pos, current.state);
        if (movement == BuilderNavigator.Result.MOVING) {
            logTargetStatus(current, "Moving into range");
        }
        if (navigator.isTowering()) {
            reason = "Towering with dirt to unlock path to " + coordinates(current.pos) + " (" + navigator.getTowerPlacedCount() + " blocks)";
            noProgress = 0;
            return false;
        }
        if (movement == BuilderNavigator.Result.ARRIVED) {
            if (++unplaceable >= UNPLACEABLE_ATTEMPTS) {
                Target unplaceableTarget = current;
                current = null;
                defer(unplaceableTarget, "No usable placement face or orientation at " + coordinates(unplaceableTarget.pos) + " from " + unplaceable + " standing positions.",
                        DeferredTargets.Cause.AWAITING_NEIGHBOURS);
                current = chooseTarget(client);
                return false;
            }
            navigator.rejectCurrent(client);
            reason = "Looking for a placement angle at " + coordinates(current.pos);
            return false;
        }
        if (movement == BuilderNavigator.Result.NO_PATH) {
            if (config == null || config.autoBuilder.towerWhenStuck) {
                if (!towerAttempted && navigator.canTowerFrom(client)) {
                    towerAttempted = true;
                    navigator.startTowering(client);
                    reason = "Towering with dirt to unlock path to " + coordinates(current.pos);
                    log("Towering with dirt to reach " + coordinates(current.pos) + ".");
                    noProgress = 0;
                    return false;
                }
            }
            // Keep confirmed tower access while construction is unfinished. Mining it on a
            // navigation failure made the next attempt rebuild the same tower. Cleanup belongs
            // to the completed-structure branch, not path recovery.
            Target unreachableTarget = current;
            current = null;
            defer(unreachableTarget, "No walking route to " + coordinates(unreachableTarget.pos) + ". Waiting for reachable blocks or path.",
                    DeferredTargets.Cause.AWAITING_NEIGHBOURS);
            current = chooseTarget(client);
            towerAttempted = false;
            return false;
        }
        if (movement == BuilderNavigator.Result.STUCK) {
            if (config == null || config.autoBuilder.towerWhenStuck) {
                if (!towerAttempted && navigator.canTowerFrom(client)) {
                    towerAttempted = true;
                    navigator.startTowering(client);
                    reason = "Towering with dirt to unlock path to " + coordinates(current.pos);
                    log("Towering with dirt to reach " + coordinates(current.pos) + ".");
                    noProgress = 0;
                    return false;
                }
            }
            // Keep confirmed tower access while construction is unfinished. Mining it on a
            // navigation failure made the next attempt rebuild the same tower. Cleanup belongs
            // to the completed-structure branch, not path recovery.
            noProgress += 30;
            if (noProgress >= 60) {
                Target stuckTarget = current;
                current = null;
                defer(stuckTarget, "Stuck trying to reach " + coordinates(stuckTarget.pos) + ". Trying other targets.",
                        DeferredTargets.Cause.AWAITING_NEIGHBOURS);
                current = chooseTarget(client);
                towerAttempted = false;
                noProgress = 0;
                return false;
            }
        }
        reason = "Walking to " + coordinates(current.pos) + " / " + navigator.routeLength() + " steps";
        if (noProgress > 300) {
            Target stuck = current;
            current = null;
            defer(stuck, "Cannot reach a placement face at " + coordinates(stuck.pos) + ". Trying other targets.",
                    DeferredTargets.Cause.AWAITING_NEIGHBOURS);
            current = chooseTarget(client);
            noProgress = 0;
        }
        return false;
    }
    /** True while a material errand - collecting from a box or filling buckets from water - is running. */
    private final AuctionMaterials auction = new AuctionMaterials();
    private boolean errandRunning() { return auction.active() || restock.active() || refill.active(); }

    /** Stops whichever material errand is running and forgets how much of its budget was spent. */
    private void cancelErrands() {
        inventoryTransfer.cancel();
        auction.cancel();
        if (restock.active()) restock.cancel();
        if (refill.active()) refill.cancel();
        resumeAfterErrand=false;
        autoCollections=0;
        refillsSinceProgress=0;
    }

    /**
     * Advances the material errand that is running and settles the build when it ends. An errand that
     * brought nothing back leaves the build paused with its own reason, and a bucket refill that found no
     * water it may take falls through to the material shulker before that happens.
     */
    private void tickErrand(Minecraft client) {
        if (auction.active()) {
            if (config==null || !config.autoBuilder.buyMaterialsFromAuction || client.level!=session) {
                auction.cancel(); status=Status.PAUSED; reason="Auction restocking cancelled."; return;
            }
            auction.tick(client);
            reason=auction.reason();
            if (!auction.active()) {
                status=Status.PAUSED;
                if (auction.success() && client.gui.screen()==null) {
                    resume();
                    reason="Auction material received. Resumed.";
                } else {
                    // Do NOT skip or defer the block when out of material! Retry buying it from auction
                    cooldown = 20;
                    status = Status.RUNNING;
                    reason = "Retrying auction purchase...";
                    log("Auction purchase incomplete, retrying auction in 1s...");
                }
            }
            return;
        }
        boolean collecting=restock.active();
        if (collecting) restock.tick(client); else refill.tick(client);
        if (errandRunning()) return;
        int brought=collecting ? restock.moved() : refill.filled();
        String message=collecting ? restock.reason() : refill.reason();
        if (!resumeAfterErrand) {
            status=schematic == null ? Status.IDLE : Status.READY;
            reason=message;
            return;
        }
        resumeAfterErrand=false;
        if (brought>0) {
            // The build was paused by a missing item, so it carries on now that something arrived. resume()
            // needs a paused build, and the errand's own message says what it brought back.
            status=Status.PAUSED;
            resume();
            reason=message + " Resumed.";
            return;
        }
        // A refill that found no water anywhere falls back to the storage, chosen or merely nearby.
        if (!collecting && autoCollections<AUTO_COLLECT_LIMIT) {
            autoCollections++;
            if (startCollecting(client,Items.WATER_BUCKET.getDefaultInstance().getHoverName().getString())) return;
        }
        // If restock brought nothing, don't freeze the bot in PAUSED - defer and try other targets!
        if (current != null) {
            defer(current, message + ". Trying other targets.", DeferredTargets.Cause.AWAITING_NEIGHBOURS);
        }
        status=Status.PAUSED;
        resume();
        log("Restock finished (" + message + "). Trying other targets...");
    }

    /**
     * Pauses the build and checks the material storage for the missing materials. A run that cannot even
     * start - a chosen container that is gone, unreachable or in another dimension - leaves its own reason.
     */
    private boolean startCollecting(Minecraft client,String itemName) {
        pauseWith("Checking the material storage for " + itemName + "...");
        restock.start(client,schematic,transform,materialStorage);
        status=restock.active() ? Status.COLLECTING : Status.PAUSED;
        resumeAfterErrand=restock.active();
        reason=restock.reason();
        return restock.active();
    }

    /**
     * Pauses the build and walks to water to fill an empty bucket. A run that finds nothing it may take
     * reports that as its reason and leaves the build paused.
     */
    private boolean startRefill(Minecraft client,String itemName) {
        pauseWith("Filling an empty bucket from water nearby for " + itemName + "...");
        refill.start(client,schematic,transform,buildWater());
        status=refill.active() ? Status.COLLECTING : Status.PAUSED;
        resumeAfterErrand=refill.active();
        reason=refill.reason();
        return refill.active();
    }

    /**
     * True for a cell the file fills with water, whether it has been built yet or not. Its own source is
     * never taken, and neither is the water beside it, which is what keeps a recorded flowing level filled.
     */
    private Predicate<BlockPos> buildWater() {
        if (schematic==null) return at -> false;
        return at -> {
            Integer index=indices.get(at);
            if (index == null) return false;
            BlockState state = transform.apply(schematic.blocks().get(index).state());
            return MaterialTracker.isWater(state) || MaterialTracker.holdsWater(state) || MaterialTracker.needsOwnCellWater(state);
        };
    }

    private long targetSelectionNanos, targetSelections, candidateChecks;
    public record PlanningMetrics(long targetSelections, long candidateChecks, long targetSelectionNanos,
                                  BuilderNavigator.Metrics navigation) { }
    public boolean authoritativeSupport(BlockPos pos) {
        return !unconfirmed.containsKey(pos) && !unknownMining.contains(pos)
                && !miningConfirmation.active(pos) && navigator.isSupportUsable(pos);
    }
    public PlanningMetrics planningMetrics() {
        return new PlanningMetrics(targetSelections, candidateChecks, targetSelectionNanos, navigator.metrics());
    }
    private Target chooseTarget(Minecraft client) {
        long started = System.nanoTime();
        targetSelections++;
        try { return selectTarget(client); }
        finally { targetSelectionNanos += System.nanoTime() - started; }
    }
    private Target selectTarget(Minecraft client) {
        if (work.isEmpty()) retryDeferred(client);
        if (work.isEmpty()) return null;
        boolean protectWater = config == null || config.autoBuilder.protectRedstoneFromWater;
        Map<BlockPos, Boolean> waterSafetyCache = new HashMap<>();
        Predicate<BlockPos> safeWater = pos -> waterSafetyCache.computeIfAbsent(pos, p -> !waterWouldBreakRedstone(client, p));
        // All passes see the same pose, inventory and world within this client turn. Probe once,
        // not once per pass; never retain collision/support facts across a server update.
        Map<Target, Boolean> materialCache = new HashMap<>();
        Map<Target, Boolean> supportCache = new HashMap<>();
        Predicate<Target> available = target -> materialCache.computeIfAbsent(target, t -> hasMaterial(client, t));
        Predicate<Target> supportedHere = target -> supportCache.computeIfAbsent(target, t -> {
            candidateChecks++;
            return !unconfirmed.containsKey(t.pos) && navigator.isSupportUsable(t.pos)
                    && hasRequiredPlacementSupport(client, t) && t.state.canSurvive(client.level, t.pos);
        });
        if (lastPlacedCoral != null) {
            BlockPos coral = lastPlacedCoral;
            lastPlacedCoral = null;
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = coral.relative(dir);
                Integer index = indices.get(neighbor);
                if (index != null && !correct.get(index)) {
                    Target t = byIndex[index];
                    if (work.contains(t) && (MaterialTracker.isWater(t.state) || MaterialTracker.needsOwnCellWater(t.state))) {
                        if (protectWater && !safeWater.test(t.pos)) continue;
                        return t;
                    }
                }
            }
        }
        Target best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int minimumY = work.first().pos.getY();
        int minimumTier = placementTier(work.first().state);
        int examined = 0;

        // Spatial shortlist for blocks reachable right now. If a supported, available block can be placed
        // from our current spot without moving, prioritize it immediately.
        List<Target> nearby = new ArrayList<>();
        int reachLimit = (int) Math.ceil(getReach()) + 1;
        double reachSq = getReach() * getReach();
        BlockPos playerFeet = client.player.blockPosition();
        int sweepY = isLocalSweep() ? playerFeet.getY() + 3 : minimumY + 1;
        for (int dx = -reachLimit; dx <= reachLimit; dx++) {
            for (int dy = -reachLimit; dy <= reachLimit; dy++) {
                for (int dz = -reachLimit; dz <= reachLimit; dz++) {
                    Integer index = indices.get(playerFeet.offset(dx, dy, dz));
                    if (index == null) continue;
                    Target candidate = byIndex[index];
                    if (candidate.pos.getY() > sweepY || !work.contains(candidate)) continue;
                    if (client.player.position().distanceToSqr(Vec3.atCenterOf(candidate.pos)) > reachSq) continue;
                    nearby.add(candidate);
                }
            }
        }
        Item heldItem = client.player.getMainHandItem().getItem();
        nearby.sort(Comparator.comparingInt((Target t) -> t.pos.getY())
                .thenComparingInt((Target t) -> {
                    var req = MaterialTracker.requirement(t.state);
                    return req != null && req.item() == heldItem ? 0 : 1;
                })
                .thenComparingInt(t -> placementTier(t.state))
                .thenComparingDouble(t -> client.player.position().distanceToSqr(Vec3.atCenterOf(t.pos))));
        int localProbes = 0;
        int maxProbes = isLocalSweep() ? 24 : 6;
        for (Target candidate : nearby) {
            if (!available.test(candidate) || !supportedHere.test(candidate)) continue;
            if (isWaterTarget(candidate) && protectWater && !safeWater.test(candidate.pos)) continue;
            var requirement = MaterialTracker.requirement(candidate.state);
            if (requirement == null || requirement.item() == null) continue;
            if (++localProbes > maxProbes) break;
            if (PlacementPlanner.plan(client,candidate.pos,candidate.state,new ItemStack(requirement.item())) != null)
                return candidate;
        }

        // Pass 1: Lowest Y level, minimum tier, WITH available materials
        for (Target target : work) {
            if (++examined > 256 || target.pos.getY() > minimumY + 1) break;
            if (!available.test(target)) continue;
            if (isWaterTarget(target) && protectWater && !safeWater.test(target.pos)) continue;
            int tier = placementTier(target.state);
            if (target.pos.getY() == minimumY && tier > minimumTier) continue;
            boolean supported = supportedHere.test(target);
            if (!supported) continue;
            var req = MaterialTracker.requirement(target.state);
            boolean sameItem = heldItem != Items.AIR && req != null && req.item() == heldItem;
            double score = client.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target.pos))
                    + (target.pos.getY() - minimumY) * 64
                    + tier * 5000.0
                    - (sameItem ? 36.0 : 0.0);
            if (score < bestScore) { bestScore = score; best = target; }
        }
        if (best != null) return best;

        // Pass 2: Lowest Y level, any tier, WITH available materials
        examined = 0;
        for (Target target : work) {
            if (++examined > 256 || target.pos.getY() > minimumY + 1) break;
            if (!available.test(target)) continue;
            if (isWaterTarget(target) && protectWater && !safeWater.test(target.pos)) continue;
            boolean supported = supportedHere.test(target);
            if (!supported) continue;
            int tier = placementTier(target.state);
            var req = MaterialTracker.requirement(target.state);
            boolean sameItem = heldItem != Items.AIR && req != null && req.item() == heldItem;
            double score = client.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target.pos))
                    + (target.pos.getY() - minimumY) * 64
                    + tier * 5000.0
                    - (sameItem ? 36.0 : 0.0);
            if (score < bestScore) { bestScore = score; best = target; }
        }
        if (best != null) return best;

        // Pass 3: Any supported block in work WITH available materials
        examined = 0;
        for (Target target : work) {
            if (++examined > 256) break;
            if (!available.test(target)) continue;
            if (isWaterTarget(target) && protectWater && !safeWater.test(target.pos)) continue;
            if (!supportedHere.test(target)) continue;
            int tier = placementTier(target.state);
            var req = MaterialTracker.requirement(target.state);
            boolean sameItem = heldItem != Items.AIR && req != null && req.item() == heldItem;
            double score = client.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target.pos))
                    + (target.pos.getY() - minimumY) * 64
                    + tier * 5000.0
                    - (sameItem ? 36.0 : 0.0);
            if (score < bestScore) { bestScore = score; best = target; }
        }
        if (best != null) return best;

        // Pass 3b: If no supported block exists, try to fix missing support for pending blocks
        examined = 0;
        for (Target target : work) {
            if (++examined > 64) break;
            if (!available.test(target)) continue;
            Target fix = findSchematicSupport(client, target);
            if (fix != null && hasMaterial(client, fix) && hasRequiredPlacementSupport(client, fix) && fix.state.canSurvive(client.level, fix.pos)) {
                return fix;
            }
            Target temp = necessarySupport(client, target);
            if (temp == null) temp = sideSupport(client, target);
            if (temp == null) temp = lateralSupport(client, target);
            if (temp != null) return temp;
        }

        // Pass 4: Fallback to standard search if player has no materials for any supported block
        examined = 0;
        for (Target target : work) {
            if (++examined > 256 || target.pos.getY() > minimumY + 1) break;
            int tier = placementTier(target.state);
            if (target.pos.getY() == minimumY && tier > minimumTier) continue;
            boolean supported = hasRequiredPlacementSupport(client, target);
            double score = client.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target.pos))
                    + (target.pos.getY() - minimumY) * 64
                    + tier * 5000.0
                    + (supported ? 0 : 2048);
            if (score < bestScore) { bestScore = score; best = target; }
        }
        if (best != null) return best;

        examined = 0;
        for (Target target : work) {
            if (++examined > 256 || target.pos.getY() > minimumY + 1) break;
            int tier = placementTier(target.state);
            boolean supported = hasRequiredPlacementSupport(client, target);
            double score = client.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target.pos))
                    + (target.pos.getY() - minimumY) * 64
                    + tier * 5000.0
                    + (supported ? 0 : 2048);
            if (score < bestScore) { bestScore = score; best = target; }
        }
        return best;
    }
    /**
     * With nothing left in the queue, the nearest set-aside position gets one more attempt while it
     * still has attempts left. That keeps a temporary obstacle from being reported as a final failure,
     * and stops once the budget is spent so the build pauses with its real remaining reason.
     */
    private void retryDeferred(Minecraft client) {
        Target best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        boolean protectWater = config == null || config.autoBuilder.protectRedstoneFromWater;
        for (BlockPos pos : deferred.positions()) {
            if (!deferred.retryable(pos) || unconfirmed.containsKey(pos)) continue;
            Integer index = indices.get(pos);
            if (index == null || correct.get(index)) continue;
            Target candidate = byIndex[index];
            if (isWaterTarget(candidate) && protectWater && waterWouldBreakRedstone(client, candidate.pos)) continue;
            int tier = placementTier(candidate.state);
            double score = candidate.pos.getY() * 10000.0 + tier * 2000.0
                    + client.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos));
            if (score < bestScore) { bestScore = score; best = candidate; }
        }
        if (best != null) work.add(best);
    }
    private void revalidate(Minecraft client,int budget) {
        for (int checked=0;checked<Math.min(budget,targets.size());checked++) {
            if (revalidateIndex>=targets.size()) revalidateIndex=0;
            Target target=targets.get(revalidateIndex++);
            if (!authoritativeSupport(target.pos) || (pending!=null && pending.target.pos.equals(target.pos))) continue;
            boolean matches=MaterialTracker.isLoaded(client.level,target.pos) && MaterialTracker.matches(target.state,client.level.getBlockState(target.pos));
            if (matches) markCorrect(target);
            else if (correct.get(target.index)) { correct.clear(target.index); if (!deferred.isDeferred(target.pos)) work.add(target); }
        }
    }
    private Vec3 alignmentPosition;
    private BlockPos alignmentTarget;
    private int stationaryAlignmentTicks, lastAlignmentTick;

    private boolean nudgeStalledAlignment(Minecraft client, boolean sneak) {
        Vec3 position=client.player.position();
        if (current == null) return false;
        if (!current.pos.equals(alignmentTarget) || tick-lastAlignmentTick>1 || alignmentPosition==null
                || position.distanceToSqr(alignmentPosition)>.0025) {
            alignmentTarget=current.pos;
            alignmentPosition=position;
            stationaryAlignmentTicks=0;
        }
        if (lastAlignmentTick!=tick) stationaryAlignmentTicks++;
        lastAlignmentTick=tick;
        if (stationaryAlignmentTicks<20) return false;
        stationaryAlignmentTicks=0;
        if (!navigator.nudgeForward(client,sneak)) return false;
        prepared=null;
        reason="Small forward adjustment to unblock alignment...";
        return true;
    }

    public boolean swapMaterialToHotbar(Minecraft client, int slot) {
        if (client == null || client.player == null || client.gameMode == null) return false;
        if (slot < 9 || slot >= 36) return false;
        if (client.player.containerMenu != client.player.inventoryMenu) {
            client.player.closeContainer();
        }
        int hotbarSlot = client.player.getInventory().getSelectedSlot();
        for (int h = 0; h < 9; h++) {
            if (client.player.getInventory().getItem(h).isEmpty()) {
                hotbarSlot = h;
                break;
            }
        }
        client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, slot, hotbarSlot, ContainerInput.SWAP, client.player);
        client.player.getInventory().setSelectedSlot(hotbarSlot);
        if (client.player.connection != null) {
            client.player.connection.send(new ServerboundSetCarriedItemPacket(hotbarSlot));
        }
        return true;
    }

    private final InventoryMaterialTransfer inventoryTransfer = new InventoryMaterialTransfer();
    public void moveMaterialWithCursor(Minecraft client, int slot) {
        swapMaterialToHotbar(client, slot);
    }

    private final HeldItemStability heldItemStability = new HeldItemStability();

    private void sendPrepared(Minecraft client) {
        Prepared action=prepared; prepared=null;
        if (!action.target.equals(current)) return;
        // Ensure the player is holding the correct item for this placement
        if (current.support) {
            Item scaffold = findScaffoldMaterial();
            if (scaffold == null) {
                if (config!=null && config.autoBuilder.buyMaterialsFromAuction)
                    fetchMissingMaterial(client,Items.DIRT," for temporary support at " + coordinates(current.pos));
                else defer("Missing scaffold material for temporary support at " + coordinates(current.pos));
                return;
            }
            int s = findSlot(client, scaffold);
            if (s >= 9 && s < 36) {
                swapMaterialToHotbar(client, s);
                s = client.player.getInventory().getSelectedSlot();
            }
            if (s >= 0 && s < 9) client.player.getInventory().setSelectedSlot(s);
            else if (!client.player.getMainHandItem().is(scaffold)) {
                defer("Scaffold material not in hotbar for temporary support at " + coordinates(current.pos)); return;
            }
        } else if (action.plan.bucket()) {
            int s = findSlot(client, Items.WATER_BUCKET);
            if (s >= 9 && s < 36) {
                swapMaterialToHotbar(client, s);
                s = client.player.getInventory().getSelectedSlot();
            }
            if (s >= 0 && s < 9) client.player.getInventory().setSelectedSlot(s);
            else if (!client.player.getMainHandItem().is(Items.WATER_BUCKET)) {
                defer("Water bucket not in hotbar for placement at " + coordinates(current.pos)); return;
            }
        } else if (!action.plan.configure()) {
            var req = MaterialTracker.requirement(current.state);
            Item expectedItem = req.item();
            boolean allowAuction = config != null && config.autoBuilder.buyMaterialsFromAuction;
            boolean allowSubs = !allowAuction && (config == null || config.autoBuilder.allowSubstitutes);
            ItemStack inHand = client.player.getMainHandItem();
            boolean handMatches = inHand.is(expectedItem) || (allowSubs && inHand.getItem() instanceof BlockItem bi
                    && MaterialSubstitutes.areSubstitutable(expectedItem instanceof BlockItem ebi ? ebi.getBlock() : null, bi.getBlock()));
            if (!handMatches) {
                int s = findSlot(client, expectedItem);
                if (s < 0 && allowSubs) s = findSubstituteSlot(client, expectedItem, current.state);
                if (s >= 9 && s < 36) {
                    swapMaterialToHotbar(client, s);
                    s = client.player.getInventory().getSelectedSlot();
                }
                if (s >= 0 && s < 9) {
                    client.player.getInventory().setSelectedSlot(s);
                    if (isFastPlace() && client.player.connection != null) {
                        client.player.connection.send(new ServerboundSetCarriedItemPacket(s));
                    }
                } else {
                    defer("Required item " + expectedItem.getDefaultInstance().getHoverName().getString() + " not in hand at " + coordinates(current.pos));
                    return;
                }
            }
        }
        if (!isFastPlace() && !heldItemStability.ready(tick,client.player.getInventory().getSelectedSlot(),client.player.getMainHandItem())) {
            navigator.hold(client,shouldSneak(client,current,action.plan));
            prepared=new Prepared(current,action.plan,tick+1);
            reason="Waiting for held material to settle before placement...";
            return;
        }
        // Validate the retained face first. A full search is only needed when that interaction
        // is invalid; searching first discarded useful work and could change faces while turning.
        // Keep the plan the aim was already built around while it can still be clicked. Picking the face
        // again on every tick swung the view between opposite sides of one block while the player was
        // still sliding or the pose was settling, and because the transmitted aim kept changing with it
        // the interaction never went out - the builder just turned away and back.
        // A retained face is only reusable if it still predicts the current requested state.
        // Reachability alone misses changed neighbours, inventory substitutions and slab hit halves.
        var plan=PlacementPlanner.stillValid(client,action.plan)
                && !action.plan.bucket() && !action.plan.configure()
                && PlacementPlanner.matchesInteraction(client,current.pos,current.state,client.player.getMainHandItem(),
                    action.plan.hit(),action.plan.yaw(),action.plan.pitch(),shouldSneak(client,current,action.plan))
                ? action.plan : PlacementPlanner.plan(client,current.pos,current.state,client.player.getMainHandItem());
        if (plan==null) {
            prepared=null;
            navigator.rejectCurrent(client);
            return;
        }
        boolean sneak=shouldSneak(client,current,plan);
        navigator.hold(client,sneak);
        // Check the usable aim already on the wire before rotating farther towards the face center.
        // Turning first can invalidate the current-vs-sent check and postpone an otherwise valid click.
        // Let vanilla send rotation/input once per client tick before the interaction. An extra
        // movement packet here duplicates LocalPlayer.sendPosition and violates strict packet ordering.
        if (!transmitted(client,plan,sneak)) {
            if (nudgeStalledAlignment(client,sneak)) return;
            aim(client,plan);
            if (++aimAttempts>AIM_ATTEMPTS) {
                defer("Placement ray or pose did not converge at " + coordinates(current.pos) + " after " + aimAttempts
                        + " tries; support " + coordinates(plan.hit().getBlockPos()) + " face " + plan.hit().getDirection()
                        + ". Check access or reposition, then Resume.");
                return;
            }
            prepared=new Prepared(current,plan,tick+1);
            reason="Aligning for placement at " + coordinates(current.pos);
            return;
        }
        BlockState before=client.level.getBlockState(current.pos);
        // A bucket is used, not clicked: it has no block context, and vanilla pours where the player's own
        // view meets the first block. Only a pour therefore waits for the block update alone - the sequence
        // the engine tracks belongs to block interactions, so a stale number there must not confirm it.
        BlockHitResult hitToSend;
        if (plan.bucket() || isSilentRotations()) {
            hitToSend = plan.hit();
        } else {
            var sent = (BuilderLocalPlayerAccessor) client.player;
            Vec3 eye = client.player.getEyePosition();
            float sentYaw = sent.cli$lastSentYaw();
            float sentPitch = sent.cli$lastSentPitch();
            Vec3 look = calculateViewVector(sentPitch, sentYaw);
            double reach = client.player.blockInteractionRange();
            Vec3 end = eye.add(look.scale(reach));
            BlockHitResult ray = client.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
            if (ray.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                    || !ray.getBlockPos().equals(plan.hit().getBlockPos())
                    || ray.getDirection() != plan.hit().getDirection()) {
                if (++aimAttempts>AIM_ATTEMPTS) {
                    defer("Placement ray changed before interaction at " + coordinates(current.pos));
                    return;
                }
                aim(client,plan);
                prepared = new Prepared(current, plan, tick + 1);
                reason = "Aligning for placement at " + coordinates(current.pos);
                return;
            }
            hitToSend = ray;
        }
        if (!plan.bucket() && !plan.configure() && !isSilentRotations()) {
            var sent=(BuilderLocalPlayerAccessor)client.player;
            if (!PlacementPlanner.matchesInteraction(client,current.pos,current.state,client.player.getMainHandItem(),
                    hitToSend,sent.cli$lastSentYaw(),sent.cli$lastSentPitch(),sneak)) {
                if (nudgeStalledAlignment(client,sneak)) return;
                if (++aimAttempts>AIM_ATTEMPTS) {
                    defer("Actual placement angle cannot produce the requested state at " + coordinates(current.pos));
                    return;
                }
                aim(client,plan);
                prepared=new Prepared(current,plan,tick+1);
                reason="Aligning exact block state at " + coordinates(current.pos);
                return;
            }
        }
        aimAttempts=0;
        lastPlacementTime = System.currentTimeMillis();
        var result=plan.bucket()
                ? client.gameMode.useItem(client.player,InteractionHand.MAIN_HAND)
                : client.gameMode.useItemOn(client.player,InteractionHand.MAIN_HAND,hitToSend);
        int sequence=-1;
        if (!plan.bucket()) {
            sequence=((BuilderClientLevelAccessor)client.level).cli$predictionHandler().currentSequence();
            unconfirmed.put(current.pos,sequence);
            BlockPos secondary=secondaryPosition(current);
            if (secondary!=null) unconfirmed.put(secondary,sequence);
        }
        Pending p = new Pending(current,before,plan.prediction(),tick,sequence,sneak);
        syncMarker(current.pos,0xE8CE66,"Awaiting placement confirmation",true);
        if (result.consumesAction()) client.player.swing(InteractionHand.MAIN_HAND);
        if (isFastPlace() && !current.support && !current.pour && !action.plan.bucket() && !action.plan.configure()) {
            markCorrect(current);
            placementsInMinute.addLast(System.currentTimeMillis());
            inFlight.add(p);
            logTargetStatus(p.target, "Placed");
            current = null;
            pending = null;
            cooldown = 0;
            reason = "Pipelined placement at " + coordinates(p.target.pos);
        } else {
            pending = p;
            logTargetStatus(current, "Placed (waiting for server)");
            reason = "Waiting for the server at " + coordinates(current.pos);
        }
        if (isSilentRotations() && current == null) {
            dev.kuro.client.util.RotationHandler.clear();
        }
    }
    private void aim(Minecraft client,PlacementPlanner.Plan plan) {
        if (client.player == null || plan == null || plan.hit() == null) return;
        Vec3 eye = client.player.getEyePosition();
        Vec3 hitPos = plan.hit().getLocation();
        Vec3 delta = hitPos.subtract(eye);
        float pitch = plan.pitch();
        float yaw;
        if (plan.prediction() != null && hasFacingProperty(plan.prediction())) {
            // Directional block: preserve planned yaw so horizontal orientation is correct
            yaw = plan.yaw();
            float livePitch = (float)Math.toDegrees(Math.atan2(-delta.y,Math.hypot(delta.x,delta.z)));
            // Crouching changes the eye, not the requested facing. Track that change only if
            // the resulting interaction still predicts the desired state (including vertical facing).
            if (current != null && !plan.bucket() && !plan.configure() && PlacementPlanner.matchesInteraction(client,
                    current.pos, current.state,
                    client.player.getMainHandItem(), plan.hit(), yaw, livePitch, plan.sneak())) pitch=livePitch;
        } else {
            // Track both axes as crouching changes eye height; a frozen pitch can miss the
            // chosen face even after yaw has settled. Bucket plans keep their validated pour aim.
            yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
            if (!plan.bucket()) pitch = (float)Math.toDegrees(Math.atan2(-delta.y,Math.hypot(delta.x,delta.z)));
        }
        if (isSilentRotations()) {
            dev.kuro.client.util.RotationHandler.setSilent(yaw, pitch);
        } else {
            BuilderNavigator.rotateSmoothly(client.player, yaw, pitch, 65.0f);
        }
    }
    /**
     * True when Minecraft has already transmitted the rotation and sneak input this plan needs, so the
     * server would compute exactly the predicted state. Reads only vanilla's last sent values.
     */
    private static boolean transmitted(Minecraft client,PlacementPlanner.Plan plan,boolean sneak) {
        if (client.player == null) return false;
        if (get().isSilentRotations()) return true;
        var sent=(BuilderLocalPlayerAccessor)client.player;
        if (sent.cli$lastSentInput()==null || sent.cli$lastSentInput().shift()!=sneak) return false;
        float sentYaw = sent.cli$lastSentYaw();
        float sentPitch = sent.cli$lastSentPitch();

        if (plan.prediction() != null && hasFacingProperty(plan.prediction())) {
            Direction desiredDir = Direction.from2DDataValue(net.minecraft.util.Mth.floor((plan.yaw() * 4.0F / 360.0F) + 0.5D) & 3);
            Direction sentDir = Direction.from2DDataValue(net.minecraft.util.Mth.floor((sentYaw * 4.0F / 360.0F) + 0.5D) & 3);
            if (desiredDir != sentDir) return false;
        }

        if (plan.bucket()) {
            float yawDiff = Math.abs(net.minecraft.util.Mth.wrapDegrees(sentYaw - plan.yaw()));
            float pitchDiff = Math.abs(sentPitch - plan.pitch());
            return yawDiff <= 2.5f && pitchDiff <= 2.5f;
        }

        Vec3 eye = client.player.getEyePosition();
        Vec3 look = calculateViewVector(sentPitch, sentYaw);
        double reach = client.player.blockInteractionRange();
        Vec3 end = eye.add(look.scale(reach));
        BlockHitResult ray = client.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));

        if (ray.getType() != HitResult.Type.BLOCK
                || !ray.getBlockPos().equals(plan.hit().getBlockPos())
                || ray.getDirection() != plan.hit().getDirection()) {
            return false;
        }

        if (get().isSilentRotations()) return true;
        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();
        float clientYawDiff = Math.abs(net.minecraft.util.Mth.wrapDegrees(currentYaw - sentYaw));
        float clientPitchDiff = Math.abs(currentPitch - sentPitch);
        return clientYawDiff <= 8.0f && clientPitchDiff <= 6.0f;
    }
    private static Vec3 calculateViewVector(float pitch, float yaw) {
        float f = pitch * ((float)Math.PI / 180F);
        float g = -yaw * ((float)Math.PI / 180F);
        float h = net.minecraft.util.Mth.cos(g);
        float i = net.minecraft.util.Mth.sin(g);
        float j = net.minecraft.util.Mth.cos(f);
        float k = net.minecraft.util.Mth.sin(f);
        return new Vec3(i * j, -k, h * j);
    }
    private static boolean hasFacingProperty(BlockState state) {
        return state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                || state.hasProperty(BlockStateProperties.FACING);
    }
    public void serverBlockUpdate(BlockPos pos,BlockState state) {
        navigator.serverBlockUpdate(pos,state);
        miningConfirmation.observe(pos,state);
        if (unknownMining.remove(pos)) {
            // A delayed reply after pause/cancel still resolves cleanup ownership. Otherwise Resume
            // can try mining server-confirmed air and quarantine it again forever.
            if (state.isAir()) {
                supportBlocks.remove(pos);
                navigator.removeTowerBlock(pos);
            }
            navigator.replan();
        }
        if (syncMarkers.containsKey(pos)) syncMarker(pos,state.isAir() ? 0x72DF9A : 0xB897ED,
                state.isAir() ? "Server confirmed air" : "Server supplied block state",false);
        unconfirmed.remove(pos);
        if (session==null) return;
        Integer index=indices.get(pos);
        if (index!=null) {
            // Index refers to the original list, not its sorted order.
            BlockState desired=transform.apply(schematic.blocks().get(index).state());
            if (MaterialTracker.matches(desired,state)) { markCorrect(byIndex[index]); deferred.remove(pos); }
            else { correct.clear(index); if (!deferred.isDeferred(pos)) work.add(byIndex[index]); finalCheckIndex=0; }
        }
        if (pending!=null && pending.target.pos.equals(pos)) pending.observed=state;
        for (Pending p : inFlight) {
            if (p.target.pos.equals(pos)) p.observed = state;
        }
    }
    public void serverAcknowledged(int sequence) {
        unconfirmed.entrySet().removeIf(entry->entry.getValue()<=sequence);
        // A pour carries no sequence of its own, so only the block update may confirm it.
        if (pending!=null && pending.sequence>=0 && sequence>=pending.sequence) pending.acknowledged=true;
        for (Pending p : inFlight) {
            if (p.sequence>=0 && sequence>=p.sequence) p.acknowledged = true;
        }
    }
    private int correctionRecoveryTicks, stableCorrectionTicks;
    public void positionCorrected() {
        if (isRunning()) {
            navigator.hold(Minecraft.getInstance(), false);
            navigator.serverPositionCorrected(Minecraft.getInstance());
            prepared = null;
            correctionRecoveryTicks = 1;
            stableCorrectionTicks = 0;
            cooldown = 0;
            reason = "Server corrected position. Waiting for stable ground...";
        }
    }
    private void pollInFlight(Minecraft client) {
        if (inFlight.isEmpty()) return;
        Iterator<Pending> it = inFlight.iterator();
        while (it.hasNext()) {
            Pending action = it.next();
            boolean timeout = tick - action.sentTick > 60;
            if (action.acknowledged || action.observed != null || timeout) {
                it.remove();
                BlockState actual = action.observed == null && client.level != null
                        ? client.level.getBlockState(action.target.pos) : action.observed;
                boolean matches = actual != null && MaterialTracker.matches(action.target.state, actual);
                if (matches) {
                    syncMarker(action.target.pos, 0x72DF9A, "Server-confirmed schematic placement (pipelined)", false);
                    failures.remove(action.target.pos);
                    logTargetStatus(action.target, "Server confirmed");
                } else {
                    failedActions++;
                    correct.clear(action.target.index);
                    if (!deferred.isDeferred(action.target.pos)) {
                        work.add(action.target);
                    }
                    syncMarker(action.target.pos, 0xED6464, "Server rejected pipelined placement", false);
                    int attempts = failures.merge(action.target.pos, 1, Integer::sum);
                    if (attempts >= 3) {
                        defer(action.target, "Server rejected placement at " + coordinates(action.target.pos) + ". Trying other blocks.", DeferredTargets.Cause.BLOCKED);
                    }
                }
            }
        }
    }
    private void finishPending(Minecraft client) {
        Pending action=pending;
        if (!action.acknowledged && action.observed==null) {
            // Silence is unknown, not rejection or success. Keep the footprint quarantined until
            // a late block update or reconciled sequence arrives; never rewrite the client world
            // to manufacture a retryable state. Pours need the same quarantine despite no sequence.
            unconfirmed.putIfAbsent(action.target.pos, Integer.MAX_VALUE);
            failedActions++;
            syncMarker(action.target.pos,0xED6464,"No server reply; predicted state is unknown",true);
            pending=null;
            defer(action.target, "No server confirmation at " + coordinates(action.target.pos) + ". Trying other targets.", DeferredTargets.Cause.AWAITING_NEIGHBOURS);
            current=null;
            cooldown=4;
            return;
        }
        confirmationMisses=0;
        pending=null;
        if (client.player != null) client.player.setShiftKeyDown(false);
        BlockState actual=action.observed==null ? client.level.getBlockState(action.target.pos) : action.observed;
        boolean changed=action.acknowledged && !actual.equals(action.before);
        if (action.observed!=null && !action.observed.equals(action.before)) changed=true;
        if (MaterialTracker.matches(action.target.state,actual) && (changed || action.acknowledged)) {
            boolean newlyCompleted = !action.target.support && !action.target.pour
                    && action.target.index >= 0 && !correct.get(action.target.index);
            // The packet hook may already have marked this position correct. The pending action's
            // pre-interaction state still distinguishes a newly completed schematic cell.
            if (!action.target.support && !action.target.pour && action.target.index >= 0
                    && !MaterialTracker.matches(action.target.state,action.before)) newlyCompleted = true;
            markCorrect(action.target); failures.remove(action.target.pos);
            if (newlyCompleted) placementsInMinute.addLast(System.currentTimeMillis());
            syncMarker(action.target.pos,0x72DF9A,"Server-confirmed schematic placement",false);
            logTargetStatus(action.target, "Server confirmed");
            if (MaterialTracker.needsWater(action.target.state)) lastPlacedCoral = action.target.pos;
            requeueNear(action.target.pos);
            current=null; noProgress=0; cooldown=SETTLE_TICKS;
        } else if (changed && actual.getBlock()==action.target.state.getBlock()
                && (PlacementPlanner.canAdd(action.target.state,actual) || PlacementPlanner.needsConfiguration(action.target.state,actual))) {
            // Double slabs, stackable decorations and configurable redstone require further interactions,
            // and each one has to move the block towards the requested state. A state that comes back
            // means the interactions are cycling instead of converging, which is reported here.
            if (repeatsInteraction(action.target.pos,actual)) {
                defer(action.target, "The block at " + coordinates(action.target.pos) + " keeps returning to the same state instead of the requested one. Check permissions and state support, then Resume.");
                return;
            }
            failures.remove(action.target.pos); cooldown=SETTLE_TICKS;
        } else if (changed && actual.getBlock()==action.target.state.getBlock()
                && !PlacementPlanner.awaitingNeighbours(action.target.state,actual).isEmpty()) {
            failures.remove(action.target.pos);
            requeueNear(action.target.pos);
            noProgress=0; cooldown=SETTLE_TICKS;
            defer(action.target, "The block at " + coordinates(action.target.pos) + " is placed, but its "
                    + String.join("/",PlacementPlanner.awaitingNeighbours(action.target.state,actual))
                    + " state follows its neighbours.",
                    DeferredTargets.Cause.AWAITING_NEIGHBOURS);
        } else {
            failedActions++;
            syncMarker(action.target.pos,0xB897ED,"Server rejected or changed requested state",false);
            int attempts=failures.merge(action.target.pos,1,Integer::sum); navigator.rejectCurrent(client); cooldown=4;
            reason="Server rejected placement at " + coordinates(action.target.pos) + " (" + attempts + "/3). Replanning...";
            if (attempts>=3) {
                defer(action.target, "Server rejected placement at " + coordinates(action.target.pos) + ". Trying other blocks.", DeferredTargets.Cause.BLOCKED);
                current=null;
                cooldown=6;
            }
        }
    }
    private void markCorrect(Target target) {
        // A pour is a step, not a result: the block that needs the water is the target that has to match.
        if (target.pour) return;
        if (target.support) {
            // A temporary support is a block the server confirmed, so it is progress like any other.
            if (supportBlocks.add(target.pos)) refillsSinceProgress=0;
            return;
        }
        // Re-running over a position that was already counted is not progress, so it must not reset the
        // refill budget: a half-built structure is revalidated every tick and would otherwise reset it
        // endlessly. Only a newly confirmed block means the build is really moving.
        if (correct.get(target.index)) return;
        correct.set(target.index); work.remove(target); refillsSinceProgress=0;
        int confirmed = correct.cardinality();
        int total = targets.size();
        if (total > 0 && (confirmed % 50 == 0 || confirmed == total / 4 || confirmed == total / 2 || confirmed == (total * 3) / 4)) {
            log("Progress: " + confirmed + "/" + total + " blocks placed (" + (confirmed * 100 / total) + "%).");
        }
    }
    /**
     * Re-queues the set-aside positions a placement can have changed and leaves the rest where they
     * are. Repeating every one of them after every unrelated placement is what made the engine look
     * confused: it re-walked, re-failed and reported the same causes while progress stood still.
     */
    private void requeueNear(BlockPos placed) {
        for (BlockPos pos:deferred.retryAfter(placed)) {
            Integer index=indices.get(pos);
            if (index==null || correct.get(index) || unconfirmed.containsKey(pos)) continue;
            work.add(byIndex[index]);
        }
    }
    /**
     * True when an interaction produced a state this target already reached, so the sequence is
     * cycling rather than converging. The remembered states are bounded and reset per position.
     */
    private boolean repeatsInteraction(BlockPos pos,BlockState state) {
        if (!pos.equals(repeatedPos) || repeatedStates.size()>64) { repeatedPos=pos; repeatedStates.clear(); }
        return !repeatedStates.add(state);
    }
    /** Sets a target aside because something around it has to change before it can be placed. */
    private void defer(String message) { defer(current, message, DeferredTargets.Cause.BLOCKED); }
    private void defer(Target target, String message) { defer(target, message, DeferredTargets.Cause.BLOCKED); }
    /** Sets a target aside because its area is unloaded; any later progress re-queues it. */
    private void deferUnloaded(String message) { defer(current, message, DeferredTargets.Cause.AWAITING_CHUNK); }
    private void defer(String message, DeferredTargets.Cause cause) { defer(current, message, cause); }
    private void defer(Target target, String message, DeferredTargets.Cause cause) {
        lastFailure=message;
        if (target != null) {
            logTargetStatus(target, "Deferred: " + message);
        }
        if (target==current) current=null;
        if (target==null) {
            reason=message;
            noProgress=0;
            navigator.reset();
            return;
        }
        if (target.support || target.pour) {
            current = null;
            noProgress = 0;
            navigator.reset();
            return;
        }
        deferred.record(target.pos,cause);
        work.remove(target); noProgress=0; navigator.reset();
    }
    private void complete() {
        status=Status.COMPLETED; navigator.reset(); restoreSlot(); current=null; inFlight.clear();
        if (isSilentRotations()) dev.kuro.client.util.RotationHandler.clear();
        stopAutoEat(Minecraft.getInstance());
        reason="Complete: " + correct.cardinality() + " blocks confirmed." + (supportBlocks.isEmpty()?"":" " + supportBlocks.size() + " temporary supports remain.");
        if (schematic.blocks().stream().anyMatch(block->block.blockEntity()!=null)) reason += " Stored block-entity data needs server support.";
        log("Build completed! " + correct.cardinality() + " blocks placed.");
    }
    private static String coordinates(BlockPos pos) { return pos.getX()+", "+pos.getY()+", "+pos.getZ(); }
    private static int findSlot(Minecraft client,Item item) {
        for (int i=0;i<36;i++) if (client.player.getInventory().getItem(i).is(item)) return i;
        return -1;
    }

    private int findSubstituteSlot(Minecraft client, Item item, BlockState desired) {
        if (client.player == null || !(item instanceof BlockItem blockItem)) return -1;
        MaterialSubstitutes.Category cat = MaterialSubstitutes.category(blockItem.getBlock());
        if (cat == MaterialSubstitutes.Category.NONE) return -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem candidateItem)) continue;
            if (MaterialSubstitutes.category(candidateItem.getBlock()) == cat) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasMaterial(Minecraft client, Target target) {
        if (client.player == null) return false;
        if (client.player.getAbilities().instabuild) return true;
        if (target.mine()) return true;
        if (target.support) return findScaffoldMaterial() != null;
        if (client.level != null && PlacementPlanner.needsConfiguration(target.state, client.level.getBlockState(target.pos))) return true;
        if (client.level != null && PlacementPlanner.needsWaterlogging(target.state, client.level.getBlockState(target.pos))) {
            return findSlot(client, Items.WATER_BUCKET) >= 0;
        }
        var req = MaterialTracker.requirement(target.state);
        if (req.count() == 0) return true;
        if (MaterialTracker.poursBucket(target.state)) {
            return findSlot(client, Items.WATER_BUCKET) >= 0;
        }
        if (findSlot(client, req.item()) >= 0) return true;
        boolean allowAuction = config != null && config.autoBuilder.buyMaterialsFromAuction;
        if (allowAuction) return true;
        return (config == null || config.autoBuilder.allowSubstitutes) && findSubstituteSlot(client, req.item(), target.state) >= 0;
    }
    private static BlockPos secondaryPosition(Target target) {
        if ((target.state.getBlock() instanceof DoorBlock || target.state.getBlock() instanceof DoublePlantBlock)
                && target.state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.LOWER)
            return target.pos.above();
        if (target.state.getBlock() instanceof BedBlock && target.state.getValue(BedBlock.PART)==BedPart.FOOT)
            return target.pos.relative(target.state.getValue(BedBlock.FACING));
        return null;
    }
    private static boolean shouldSneak(Minecraft client,Target target,PlacementPlanner.Plan plan) {
        if (plan == null) return false;
        if (plan.bucket()) {
            if (client.level != null && target != null) {
                Block block = client.level.getBlockState(target.pos).getBlock();
                if (block instanceof FenceGateBlock || block instanceof TrapDoorBlock || block instanceof DoorBlock || block instanceof net.minecraft.world.level.block.BaseEntityBlock) {
                    return true;
                }
            }
            return false;
        }
        // Sneaking prevents opening a support container (e.g. chest, hopper, furnace), but normal blocks never sneak
        if (plan.hit() != null && client.level != null) {
            BlockState clicked = client.level.getBlockState(plan.hit().getBlockPos());
            if (PlacementPlanner.isInteractive(clicked)) {
                return true;
            }
        }
        return plan.sneak();
    }
    private static String manualStateReason(BlockState desired,BlockState actual) {
        for (var property:desired.getProperties()) {
            if ((property.getName().equals("age") || property.getName().equals("stage"))
                    && desired.getValue(property) instanceof Integer age && age>0 && !desired.is(Blocks.CACTUS))
                return "This grown block state requires growth or server support";
        }
        if (desired.getBlock() instanceof CandleBlock && desired.getValue(BlockStateProperties.LIT))
            return "Lit candles require ignition";
        return null;
    }
    /**
     * The pour this target needs before it can be placed, or null when its cell already holds that water.
     *
     * <p>A waterlogged state records the water in its own cell, so a submerged stair or a sea pickle colony
     * that is already there holds water without a water block in the cell. Pouring a bucket into one spends
     * it on water the cell already has - and the bucket cannot be placed at all, because the block occupying
     * the cell cannot be replaced - which is what made adding a pickle to an existing waterlogged colony ask
     * for water it could never use instead of adding the pickle.
     */
    private Target waterFirst(Minecraft client,Target target) {
        if (MaterialTracker.holdsWater(client.level.getBlockState(target.pos))) return null;
        return new Target(target.pos,Blocks.WATER.defaultBlockState(),-1,false,true);
    }
    /**
     * True when this cell or one of the six around it holds water, which is all a coral block needs to stay
     * alive. Water poured for the build counts as soon as it is there, so the coral waits for the pour.
     */
    private static boolean waterContact(Minecraft client,BlockPos pos) {
        if (client.level.getFluidState(pos).is(FluidTags.WATER)) return true;
        for (Direction direction:Direction.values())
            if (client.level.getFluidState(pos.relative(direction)).is(FluidTags.WATER)) return true;
        return false;
    }
    /** Whether a pour here stays inside the build; see {@link PlacementPlanner#sealedForWater}. */
    private boolean sealedForWater(Minecraft client,BlockPos pos) {
        return PlacementPlanner.sealedForWater(pos,new PlacementPlanner.Cells() {
            @Override public boolean isLoaded(BlockPos at) { return MaterialTracker.isLoaded(client.level,at); }
            @Override public boolean holdsWater(BlockPos at) { return client.level.getFluidState(at).is(FluidTags.WATER); }
            @Override public boolean isBuilt(BlockPos at) {
                BlockState state=client.level.getBlockState(at);
                return (!state.canBeReplaced() && !state.getCollisionShape(client.level,at).isEmpty())
                        || supportBlocks.contains(at);
            }
            @Override public boolean isInBuild(BlockPos at) { return indices.containsKey(at); }
        });
    }

    /**
     * Simulates water flow from startPos to verify if it would reach and break any redstone components,
     * either existing in the world or planned in the schematic. If uncontained and could break redstone, returns true.
     */
    private boolean waterWouldBreakRedstone(Minecraft client, BlockPos startPos) {
        if (client == null || client.level == null || startPos == null) return false;

        record FlowNode(BlockPos pos, int dist) {}
        Queue<FlowNode> queue = new ArrayDeque<>();
        Map<BlockPos, Integer> visited = new HashMap<>();

        queue.add(new FlowNode(startPos, 0));
        visited.put(startPos, 0);

        int maxVisits = 300;
        int visits = 0;
        int minY = startPos.getY() - 16;

        while (!queue.isEmpty() && ++visits < maxVisits) {
            FlowNode node = queue.poll();
            BlockPos curr = node.pos;
            int dist = node.dist;

            // 1. Check downwards flow
            BlockPos below = curr.below();
            if (below.getY() >= minY && MaterialTracker.isLoaded(client.level, below)) {
                if (isRedstoneAt(client, below)) return true;

                BlockState stateBelow = client.level.getBlockState(below);
                boolean blockedBelow = supportBlocks.contains(below)
                        || (!stateBelow.canBeReplaced() && !WaterFlow.holdsAnyFluid(stateBelow));

                if (!blockedBelow) {
                    if (!visited.containsKey(below) || visited.get(below) > 0) {
                        visited.put(below, 0);
                        queue.add(new FlowNode(below, 0));
                    }
                }
            }

            // 2. Check horizontal flow (up to 7 blocks from source/falling water)
            if (dist < 7) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbor = curr.relative(dir);
                    if (!MaterialTracker.isLoaded(client.level, neighbor)) continue;

                    if (isRedstoneAt(client, neighbor)) return true;

                    BlockState neighborState = client.level.getBlockState(neighbor);
                    if (supportBlocks.contains(neighbor)) continue;
                    if (!WaterFlow.holdsAnyFluid(neighborState)) continue;

                    VoxelShape currShape = client.level.getBlockState(curr).getCollisionShape(client.level, curr);
                    VoxelShape neighborShape = neighborState.getCollisionShape(client.level, neighbor);
                    if (!WaterFlow.canPassThroughWall(dir, currShape, neighborShape)) continue;

                    int nextDist = dist + 1;
                    if (!visited.containsKey(neighbor) || visited.get(neighbor) > nextDist) {
                        visited.put(neighbor, nextDist);
                        queue.add(new FlowNode(neighbor, nextDist));
                    }
                }
            }
        }

        return false;
    }

    private boolean isRedstoneAt(Minecraft client, BlockPos pos) {
        if (client == null || client.level == null) return false;
        BlockState worldState = client.level.getBlockState(pos);
        if (BuilderNavigator.isRedstoneOrComponent(worldState)) return true;
        BlockState file = fileState(pos);
        return file != null && BuilderNavigator.isRedstoneOrComponent(file);
    }

    private boolean isWaterTarget(Target target) {
        return target != null && (target.pour || MaterialTracker.isWater(target.state) || MaterialTracker.poursBucket(target.state));
    }
    private boolean hasRequiredPlacementSupport(Minecraft client, Target target) {
        if (target.support) return true;
        if (target.state.getBlock() instanceof HopperBlock) {
            Direction facing = target.state.getValue(HopperBlock.FACING);
            if (facing == Direction.DOWN) {
                return isSolidOrSupport(client, target.pos.below()) || isSolidOrSupport(client, target.pos.above());
            } else {
                return isSolidOrSupport(client, target.pos.relative(facing));
            }
        }
        if (target.state.getBlock() instanceof ChestBlock && target.state.hasProperty(BlockStateProperties.CHEST_TYPE)
                && target.state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE) {
            Direction partnerDir = PlacementPlanner.getConnectedDirection(target.state);
            BlockPos partnerPos = partnerDir != null ? target.pos.relative(partnerDir) : null;
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = target.pos.relative(direction);
                if (partnerPos != null && neighbor.equals(partnerPos)) continue;
                if (isSolidOrSupport(client, neighbor)) return true;
            }
            return false;
        }
        if (target.state.getBlock() instanceof RotatedPillarBlock && target.state.hasProperty(BlockStateProperties.AXIS)) {
            Direction.Axis axis = target.state.getValue(BlockStateProperties.AXIS);
            if (axis == Direction.Axis.Y) {
                return isSolidOrSupport(client, target.pos.below()) || isSolidOrSupport(client, target.pos.above());
            } else if (axis == Direction.Axis.X) {
                return isSolidOrSupport(client, target.pos.east()) || isSolidOrSupport(client, target.pos.west());
            } else if (axis == Direction.Axis.Z) {
                return isSolidOrSupport(client, target.pos.north()) || isSolidOrSupport(client, target.pos.south());
            }
        }
        return hasNeighborSupport(client, target.pos);
    }

    private boolean isSolidOrSupport(Minecraft client, BlockPos pos) {
        if (client.level == null || !MaterialTracker.isLoaded(client.level, pos)) return false;
        if (unconfirmed.containsKey(pos) || !navigator.isSupportUsable(pos)) return false;
        BlockState state = client.level.getBlockState(pos);
        return (!state.canBeReplaced() && !state.getCollisionShape(client.level, pos).isEmpty())
                || supportBlocks.contains(pos);
    }

    private boolean hasNeighborSupport(Minecraft client,BlockPos pos) {
        BlockState actual=client.level.getBlockState(pos);
        if (!actual.isAir()) return true;
        for (Direction direction:Direction.values()) {
            BlockPos neighbor=pos.relative(direction);
            if (!MaterialTracker.isLoaded(client.level,neighbor) || unconfirmed.containsKey(neighbor)
                    || !navigator.isSupportUsable(neighbor)) continue;
            var state=client.level.getBlockState(neighbor);
            if (!state.canBeReplaced() && !state.getCollisionShape(client.level,neighbor).isEmpty()) return true;
        }
        return false;
    }
    private Target necessarySupport(Minecraft client,Target target) {
        BlockPos below=target.pos.below();
        if (indices.containsKey(below) && fileState(below) != null && !fileState(below).isAir()) return null;
        if (BuilderNavigator.isRedstoneOrComponent(client.level.getBlockState(below))) return null;
        Item material=findScaffoldMaterial();
        if (!(material instanceof BlockItem item)) return null;
        // Build a short support column from its existing foundation, never overwrite a solid schematic cell.
        BlockPos base=below;
        for (int depth=0;depth<12;depth++,base=base.below()) {
            if (!MaterialTracker.isLoaded(client.level,base)) return null;
            if (indices.containsKey(base) && fileState(base) != null && !fileState(base).isAir()) return null;
            if (BuilderNavigator.isRedstoneOrComponent(client.level.getBlockState(base))) return null;
            if (!client.level.getBlockState(base).canBeReplaced()) return null;
            BlockPos foundation=base.below();
            if (!MaterialTracker.isLoaded(client.level,foundation)) return null;
            if (BuilderNavigator.isRedstoneOrComponent(client.level.getBlockState(foundation))) return null;
            if (client.level.getBlockState(foundation).isCollisionShapeFullBlock(client.level,foundation))
                return new Target(base,item.getBlock().defaultBlockState(),-1,true);
        }
        return null;
    }

    private Target findSchematicSupport(Minecraft client, Target target) {
        if (target.support) return null;
        BlockState state = target.state;
        Block block = state.getBlock();

        // Wall torch, ladder, wall sign, or horizontal facing attachment
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            Direction attachDir = (block instanceof WallTorchBlock || block instanceof LadderBlock
                    || block instanceof WallSignBlock || block instanceof WallBannerBlock)
                    ? facing.getOpposite() : null;
            if (attachDir != null) {
                BlockPos attachPos = target.pos.relative(attachDir);
                Target t = findUnbuiltSchematicTarget(attachPos);
                if (t != null) return t;
            }
        }

        // Hopper attachment
        if (block instanceof HopperBlock) {
            Direction facing = state.getValue(HopperBlock.FACING);
            BlockPos attachPos = target.pos.relative(facing);
            Target t = findUnbuiltSchematicTarget(attachPos);
            if (t != null) return t;
        }

        // Foundation / vertical column support directly below
        BlockPos below = target.pos.below();
        if (indices.containsKey(below)) {
            BlockPos cursor = below;
            Target lowestUnbuilt = null;
            while (indices.containsKey(cursor)) {
                int idx = indices.get(cursor);
                if (!correct.get(idx)) {
                    lowestUnbuilt = byIndex[idx];
                }
                cursor = cursor.below();
            }
            if (lowestUnbuilt != null) {
                if (hasRequiredPlacementSupport(client, lowestUnbuilt) && lowestUnbuilt.state.canSurvive(client.level, lowestUnbuilt.pos)) {
                    return lowestUnbuilt;
                }
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbor = lowestUnbuilt.pos.relative(dir);
                    Target t = findUnbuiltSchematicTarget(neighbor);
                    if (t != null && hasRequiredPlacementSupport(client, t) && t.state.canSurvive(client.level, t.pos)) {
                        return t;
                    }
                }
                return lowestUnbuilt;
            }
        }

        // Any adjacent neighbor in the schematic that could serve as placement support
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = target.pos.relative(dir);
            Target t = findUnbuiltSchematicTarget(neighbor);
            if (t != null && hasRequiredPlacementSupport(client, t) && t.state.canSurvive(client.level, t.pos)) {
                return t;
            }
        }

        return null;
    }

    private Target findUnbuiltSchematicTarget(BlockPos pos) {
        Integer idx = indices.get(pos);
        if (idx == null || correct.get(idx)) return null;
        return byIndex[idx];
    }
    private Target lateralSupport(Minecraft client,Target target) {
        boolean topSlab=target.state.getBlock() instanceof SlabBlock && target.state.getValue(SlabBlock.TYPE)==SlabType.TOP;
        boolean topStair=target.state.getBlock() instanceof StairBlock && target.state.getValue(StairBlock.HALF)==Half.TOP;
        if (target.support || (!topSlab && !topStair)) return null;
        List<Direction> directions=new ArrayList<>();
        if (topStair) {
            Direction facing=target.state.getValue(StairBlock.FACING);
            // A support behind the desired look direction exposes a face with the required stair yaw.
            // The opposite face would force the wrong direction, so it never blocks a suitable new support.
            directions.add(facing); directions.add(facing.getClockWise()); directions.add(facing.getCounterClockWise());
        } else {
            for (Direction direction:Direction.Plane.HORIZONTAL) directions.add(direction);
            // The face of a support beyond the target is visible from the player's current side.
            directions.sort(Comparator.comparingDouble((Direction direction)->
                    -client.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target.pos.relative(direction)))));
        }
        for (Direction direction:directions) {
            BlockPos neighbor=target.pos.relative(direction);
            if (!MaterialTracker.isLoaded(client.level,neighbor)) continue;
            var state=client.level.getBlockState(neighbor);
            var shape=state.getCollisionShape(client.level,neighbor);
            if (!state.canBeReplaced() && !shape.isEmpty() && shape.bounds().maxY>.65) return null;
        }
        Item material=findScaffoldMaterial();
        if (!(material instanceof BlockItem item)) return null;
        for (Direction direction:directions) {
            BlockPos neighbor=target.pos.relative(direction);
            if (indices.containsKey(neighbor) || !MaterialTracker.isLoaded(client.level,neighbor)
                    || !MaterialTracker.isLoaded(client.level,neighbor.below())
                    || BuilderNavigator.isRedstoneOrComponent(client.level.getBlockState(neighbor))
                    || BuilderNavigator.isRedstoneOrComponent(client.level.getBlockState(neighbor.below()))
                    || !client.level.getBlockState(neighbor).canBeReplaced()
                    || !client.level.getBlockState(neighbor.below()).isCollisionShapeFullBlock(client.level,neighbor.below())) continue;
            return new Target(neighbor,item.getBlock().defaultBlockState(),-1,true);
        }
        return null;
    }
    private Target accessSupport(Minecraft client,Target target) {
        boolean hopperDown = target.state.getBlock() instanceof HopperBlock
                && target.state.getValue(HopperBlock.FACING) == Direction.DOWN
                && client.player.getEyePosition().y <= target.pos.getY() + 0.15;
        boolean needsLower = (target.state.getBlock() instanceof DispenserBlock
                || target.state.getBlock() instanceof DropperBlock
                || target.state.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock)
                && target.state.hasProperty(BlockStateProperties.FACING)
                && target.state.getValue(BlockStateProperties.FACING) == Direction.DOWN
                && client.player.getEyePosition().y > target.pos.getY() + 0.3;
        if (target.support || (!hopperDown && !needsLower && target.pos.getY()-client.player.getY()<1.6)) return null;
        Item material=findScaffoldMaterial();
        if (!(material instanceof BlockItem item)) return null;
        Target nearest=null; double distance=Double.POSITIVE_INFINITY;
        int playerFloorY = (int)Math.floor(client.player.getY());
        int stepY = needsLower ? Math.max(playerFloorY - 1, target.pos.getY() - 2) : Math.min(target.pos.getY() - 1, playerFloorY + 1);
        if (!needsLower && stepY <= playerFloorY) stepY = playerFloorY + 1;
        // Check if an elevated access step already exists adjacent to the target
        for (Direction direction:Direction.Plane.HORIZONTAL) {
            BlockPos step=new BlockPos(target.pos.getX(), stepY, target.pos.getZ()).relative(direction);
            if (MaterialTracker.isLoaded(client.level,step)
                    && client.level.getBlockState(step).isCollisionShapeFullBlock(client.level,step)) return null;
        }
        for (Direction direction:Direction.Plane.HORIZONTAL) {
            BlockPos step=new BlockPos(target.pos.getX(), stepY, target.pos.getZ()).relative(direction);
            if (indices.containsKey(step) || !MaterialTracker.isLoaded(client.level,step)
                    || BuilderNavigator.isRedstoneOrComponent(client.level.getBlockState(step))
                    || BuilderNavigator.isRedstoneOrComponent(client.level.getBlockState(step.below()))
                    || !client.level.getBlockState(step).canBeReplaced()
                    || !client.level.getBlockState(step.above()).canBeReplaced()
                    || !client.level.getBlockState(step.above(2)).canBeReplaced()
                    || !MaterialTracker.isLoaded(client.level,step.below())
                    || !client.level.getBlockState(step.below()).isCollisionShapeFullBlock(client.level,step.below())) continue;
            double candidate=client.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(step));
            if (candidate<distance) { distance=candidate; nearest=new Target(step,item.getBlock().defaultBlockState(),-1,true); }
        }
        return nearest;
    }

    private Target sideSupport(Minecraft client, Target target) {
        if (target.support) return null;
        Item material = findScaffoldMaterial();
        if (!(material instanceof BlockItem item)) return null;

        List<Direction> preferred = new ArrayList<>();
        if (target.state.getBlock() instanceof HopperBlock) {
            Direction facing = target.state.getValue(HopperBlock.FACING);
            if (facing != Direction.DOWN && facing != Direction.UP) preferred.add(facing);
        } else if (target.state.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                || target.state.getBlock() instanceof DispenserBlock
                || target.state.getBlock() instanceof DropperBlock) {
            if (target.state.hasProperty(BlockStateProperties.FACING)) {
                Direction facing = target.state.getValue(BlockStateProperties.FACING);
                if (facing != Direction.DOWN && facing != Direction.UP) preferred.add(facing);
            }
        } else if (target.state.getBlock() instanceof ObserverBlock) {
            if (target.state.hasProperty(BlockStateProperties.FACING)) {
                Direction facing = target.state.getValue(BlockStateProperties.FACING);
                if (facing != Direction.DOWN && facing != Direction.UP) preferred.add(facing.getOpposite());
            }
        } else if (target.state.getBlock() instanceof RepeaterBlock || target.state.getBlock() instanceof ComparatorBlock) {
            if (target.state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                Direction facing = target.state.getValue(BlockStateProperties.HORIZONTAL_FACING);
                preferred.add(facing.getOpposite());
            }
        } else if (target.state.getBlock() instanceof RotatedPillarBlock) {
            if (target.state.hasProperty(BlockStateProperties.AXIS)) {
                Direction.Axis axis = target.state.getValue(BlockStateProperties.AXIS);
                if (axis == Direction.Axis.X) {
                    preferred.add(Direction.EAST); preferred.add(Direction.WEST);
                } else if (axis == Direction.Axis.Z) {
                    preferred.add(Direction.NORTH); preferred.add(Direction.SOUTH);
                } else {
                    preferred.add(Direction.UP); preferred.add(Direction.DOWN);
                }
            }
        } else if (target.state.getBlock() instanceof StairBlock) {
            if (target.state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                Direction facing = target.state.getValue(BlockStateProperties.HORIZONTAL_FACING);
                preferred.add(facing.getOpposite());
                preferred.add(facing);
            }
        } else if (target.state.getBlock() instanceof TrapDoorBlock) {
            if (target.state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                Direction facing = target.state.getValue(BlockStateProperties.HORIZONTAL_FACING);
                preferred.add(facing);
            }
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (!preferred.contains(dir)) preferred.add(dir);
        }
        preferred.add(Direction.DOWN);
        preferred.add(Direction.UP);

        // Only try neighbors not belonging to the schematic; never place scaffold in schematic cells
        for (Direction dir : preferred) {
            BlockPos neighbor = target.pos.relative(dir);
            if (indices.containsKey(neighbor)) continue;
            Target t = checkSupportNeighbor(client, target, neighbor, item);
            if (t != null) return t;
        }

        return null;
    }

    private Target checkSupportNeighbor(Minecraft client, Target target, BlockPos neighbor, BlockItem item) {
        if (indices.containsKey(neighbor)) return null;
        if (!MaterialTracker.isLoaded(client.level, neighbor)) return null;
        BlockState actual = client.level.getBlockState(neighbor);
        if (BuilderNavigator.isRedstoneOrComponent(actual)) return null;
        if (!actual.canBeReplaced()) return null;

        boolean canSupport = false;
        BlockPos ground = neighbor.below();
        if (MaterialTracker.isLoaded(client.level, ground)
                && !BuilderNavigator.isRedstoneOrComponent(client.level.getBlockState(ground))
                && client.level.getBlockState(ground).isCollisionShapeFullBlock(client.level, ground)) {
            canSupport = true;
        } else {
            for (Direction side : Direction.values()) {
                BlockPos sidePos = neighbor.relative(side);
                if (sidePos.equals(target.pos)) continue;
                if (MaterialTracker.isLoaded(client.level, sidePos)) {
                    BlockState sideState = client.level.getBlockState(sidePos);
                    if (!BuilderNavigator.isRedstoneOrComponent(sideState)
                            && !sideState.canBeReplaced() && !sideState.getCollisionShape(client.level, sidePos).isEmpty()) {
                        canSupport = true;
                        break;
                    }
                }
            }
        }
        if (!canSupport) return null;
        return new Target(neighbor, item.getBlock().defaultBlockState(), -1, true);
    }

    private List<Direction> sortDirectionsTowards(Vec3 delta) {
        List<Direction> dirs = new ArrayList<>(List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST));
        dirs.sort((d1, d2) -> {
            double dot1 = d1.getStepX() * delta.x + d1.getStepZ() * delta.z;
            double dot2 = d2.getStepX() * delta.x + d2.getStepZ() * delta.z;
            return Double.compare(dot2, dot1);
        });
        return dirs;
    }

    private boolean hasSupportingNeighbor(Minecraft client, BlockPos pos, BlockPos exclude) {
        if (client.level == null) return false;
        for (Direction side : Direction.values()) {
            BlockPos sidePos = pos.relative(side);
            if (sidePos.equals(exclude)) continue;
            if (MaterialTracker.isLoaded(client.level, sidePos)) {
                BlockState sideState = client.level.getBlockState(sidePos);
                if (!sideState.canBeReplaced() && !sideState.getCollisionShape(client.level, sidePos).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private Target findPathSupport(Minecraft client, Target target) {
        if (target.support || client.player == null || client.level == null) return null;
        Item material = findScaffoldMaterial();
        if (!(material instanceof BlockItem item)) return null;

        BlockPos playerPos = client.player.blockPosition();
        int playerY = playerPos.getY();
        int targetY = target.pos.getY();
        Vec3 toTarget = Vec3.atCenterOf(target.pos).subtract(client.player.position());
        List<Direction> candidates = sortDirectionsTowards(toTarget);

        // 1. If target is higher than player feet + 1, prioritize step/stair building towards target
        if (targetY > playerY + 1) {
            for (Direction dir : candidates) {
                BlockPos stepPos = playerPos.relative(dir);
                if (indices.containsKey(stepPos)) continue;
                if (!MaterialTracker.isLoaded(client.level, stepPos)) continue;
                BlockState state = client.level.getBlockState(stepPos);
                if (BuilderNavigator.isRedstoneOrComponent(state) || BuilderNavigator.isRedstoneOrComponent(client.level.getBlockState(stepPos.below()))) continue;
                if (!state.canBeReplaced()) continue;

                // Headroom check for stepping onto it
                if (!client.level.getBlockState(stepPos.above()).canBeReplaced()
                        || !client.level.getBlockState(stepPos.above(2)).canBeReplaced()) continue;

                if (hasSupportingNeighbor(client, stepPos, null)) {
                    return new Target(stepPos, item.getBlock().defaultBlockState(), -1, true);
                }
            }
        }

        // 2. Horizontal gap bridging: bridge towards target
        for (Direction dir : candidates) {
            for (int step = 1; step <= 3; step++) {
                BlockPos gap = playerPos.relative(dir, step);
                BlockPos floorBelow = gap.below();
                if (indices.containsKey(floorBelow)) continue;
                if (!MaterialTracker.isLoaded(client.level, floorBelow)) continue;
                BlockState actual = client.level.getBlockState(floorBelow);
                if (BuilderNavigator.isRedstoneOrComponent(actual) || BuilderNavigator.isRedstoneOrComponent(client.level.getBlockState(floorBelow.below()))) continue;
                if (actual.canBeReplaced()) {
                    if (!client.level.getBlockState(gap).canBeReplaced()
                            || !client.level.getBlockState(gap.above()).canBeReplaced()) continue;

                    if (hasSupportingNeighbor(client, floorBelow, null)) {
                        return new Target(floorBelow, item.getBlock().defaultBlockState(), -1, true);
                    }
                }
            }
        }

        // 3. If target is lower than player (descending ledge), build a stepping block along a cliff/wall
        if (targetY < playerY - 3) {
            for (Direction dir : candidates) {
                for (int dy = -1; dy >= -3; dy--) {
                    BlockPos ledgePos = playerPos.relative(dir).offset(0, dy, 0);
                    if (indices.containsKey(ledgePos)) continue;
                    if (!MaterialTracker.isLoaded(client.level, ledgePos)) continue;
                    BlockState actual = client.level.getBlockState(ledgePos);
                    if (BuilderNavigator.isRedstoneOrComponent(actual) || BuilderNavigator.isRedstoneOrComponent(client.level.getBlockState(ledgePos.below()))) continue;
                    if (actual.canBeReplaced()) {
                        if (!client.level.getBlockState(ledgePos.above()).canBeReplaced()
                                || !client.level.getBlockState(ledgePos.above(2)).canBeReplaced()) continue;
                        if (hasSupportingNeighbor(client, ledgePos, null)) {
                            return new Target(ledgePos, item.getBlock().defaultBlockState(), -1, true);
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Gathers missing materials from the chosen container or the storage nearby with vanilla interactions.
     * Creative keeps the instant inventory grant; everywhere else the containers are opened and their
     * stacks moved out.
     */
    public void collectMaterials(Minecraft client) {
        if (schematic == null || targets.isEmpty()) { reason="Load a schematic containing blocks."; return; }
        if (client.player != null && client.gameMode != null && client.player.getAbilities().instabuild
                && client.gameMode.getPlayerMode().isCreative()) { getMaterials(client); return; }
        if (!canRun(client)) return;
        cancel();
        restock.start(client, schematic, transform, materialStorage);
        status = restock.active() ? Status.COLLECTING : schematic == null ? Status.IDLE : Status.READY;
        reason = restock.reason();
    }

    /**
     * Nothing in the inventory can place this block, so the build's own sources are asked before it pauses.
     * Water comes first: an empty bucket can be filled from water the build does not need, which costs a
     * walk and leaves the material storage's buckets where they are. The storage - the chosen container,
     * or the ones around the player when none is chosen - is then searched; only storage that holds none
     * of the missing item still ends in the same clear pause.
     *
     * @param detail what the item is needed for, appended to the pause reason
     */
    private void fetchMissingMaterial(Minecraft client,Item item,String detail) {
        String missing="Missing " + item.getDefaultInstance().getHoverName().getString() + detail + ". Add materials, then Resume.";
        if (errandRunning()) return;
        // Keep the real precondition failure (open menu, Freecam, etc.), not a misleading missing-item message.
        if (!canRun(client)) { String blocked=reason; pauseWith(blocked); return; }
        String name=item.getDefaultInstance().getHoverName().getString();
        if (config!=null && config.autoBuilder.buyMaterialsFromAuction) {
            pauseWith("Opening auction for " + name + "...");
            auction.start(client,item);
            status=Status.COLLECTING;
            reason=auction.reason();
            return;
        }
        String refusal=null;
        if (item==Items.WATER_BUCKET && refillsSinceProgress<REFILL_LIMIT && findSlot(client,Items.BUCKET)>=0) {
            refillsSinceProgress++;
            if (startRefill(client,name)) return;
            // The walk found no water the build can spare, so the storage is asked instead of pausing here.
            refusal=refill.reason();
        }
        // The storage is asked before the build stops, whether one was chosen or only the containers
        // around the player are there to be searched. A run that could not start reports its own reason.
        if (autoCollections<AUTO_COLLECT_LIMIT) {
            autoCollections++;
            if (startCollecting(client,name)) return;
            if (refusal==null) refusal=restock.reason();
        }
        pauseWith(refusal==null ? missing : refusal);
    }

    /** Creative inventory packets preserve occupied slots and respect each item's stack limit. */
    public void getMaterials(Minecraft client) {
        if (schematic==null || client.player==null || client.level==null || client.gameMode==null) return;
        if (!client.player.getAbilities().instabuild || !client.gameMode.getPlayerMode().isCreative()) { reason="Get Materials requires Creative access."; return; }
        if (client.player.containerMenu!=client.player.inventoryMenu) { reason="Close the container before getting materials."; return; }
        MaterialTracker.Snapshot snapshot=MaterialTracker.snapshot(client,schematic,transform);
        int added=0, dropped=0;
        for (MaterialTracker.Row row:snapshot.rows()) {
            if (row.item()==Items.AIR) continue;
            int need=Math.max(0,row.remaining()-row.hotbar()-row.inventory());
            for (int slot=0;slot<36 && need>0;slot++) {
                ItemStack existing=client.player.getInventory().getItem(slot);
                if (!existing.isEmpty() && !existing.is(row.item())) continue;
                int room=row.item().getDefaultInstance().getMaxStackSize()-(existing.isEmpty()?0:existing.getCount());
                if (room<=0) continue;
                int count=Math.min(room,need); ItemStack stack=existing.isEmpty()?new ItemStack(row.item(),count):existing.copyWithCount(existing.getCount()+count);
                client.player.getInventory().setItem(slot,stack);
                client.gameMode.handleCreativeModeItemAdd(stack,slot<9?slot+36:slot);
                need-=count; added+=count;
            }
            if (need > 0) {
                int maxStack = row.item().getDefaultInstance().getMaxStackSize();
                while (need > 0) {
                    int count = Math.min(maxStack, need);
                    ItemStack dropStack = new ItemStack(row.item(), count);
                    client.player.drop(dropStack, false);
                    dropped += count;
                    need -= count;
                }
            }
        }
        reason="Creative Get: added " + added + " items" + (dropped > 0 ? " (" + dropped + " dropped on ground)" : "") + ".";
        log(reason);
    }

    private Item findScaffoldMaterial() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return null;
        if (client.player.getAbilities().instabuild) return Items.DIRT;

        List<Item> common = List.of(
                Items.DIRT, Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.MUD,
                Items.SCAFFOLDING, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE,
                Items.STONE, Items.NETHERRACK, Items.DEEPSLATE, Items.ANDESITE,
                Items.GRANITE, Items.DIORITE, Items.TUFF, Items.SANDSTONE,
                Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
                Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS,
                Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.BAMBOO_PLANKS
        );
        for (Item candidate : common) {
            if (findSlot(client, candidate) >= 0) return candidate;
        }

        // Fallback: check any solid block in inventory, excluding forbidden blocks
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;
            if (BuilderNavigator.isForbiddenBridgeOrScaffold(blockItem)) continue;
            Block block = blockItem.getBlock();
            if (client.level != null && block.defaultBlockState().isCollisionShapeFullBlock(client.level, BlockPos.ZERO)) {
                return stack.getItem();
            }
        }
        return null;
    }

    private BlockPos findNextTempBlockToClear(Minecraft client) {
        if (supportBlocks.isEmpty() || client.level == null || client.player == null) return null;
        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        Vec3 eye = client.player.getEyePosition();
        for (BlockPos pos : supportBlocks) {
            if (!MaterialTracker.isLoaded(client.level, pos)) continue;
            BlockState state = client.level.getBlockState(pos);
            if (state.isAir() || state.canBeReplaced()) continue;
            // Clean from top to bottom; never break if a block above depends on it to survive
            BlockPos above = pos.above();
            BlockState aboveState = client.level.getBlockState(above);
            if (!aboveState.isAir() && (supportBlocks.contains(above) || !aboveState.canSurvive(client.level, above))) {
                continue;
            }
            double dist = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (dist < bestDist) {
                bestDist = dist;
                best = pos;
            }
        }
        if (best == null && !supportBlocks.isEmpty()) {
            supportBlocks.removeIf(p -> MaterialTracker.isLoaded(client.level, p) && client.level.getBlockState(p).isAir());
        }
        return best;
    }

    private BlockHitResult findMiningHit(Minecraft client, BlockPos pos) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(client.player.blockInteractionRange()));
        BlockHitResult ray = client.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        if (ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(pos)) {
            return ray;
        }
        return PlacementPlanner.visibleMiningHit(client.level,pos,eye,client.player.blockInteractionRange()-.05,
                client.player.getYRot(),client.player.getXRot(),net.minecraft.world.phys.shapes.CollisionContext.of(client.player));
    }

    private void stopMining(Minecraft client) {
        if (miningPos != null && miningConfirmation.active(miningPos) && !miningConfirmation.confirmedAir()) {
            unknownMining.add(miningPos);
            syncMarker(miningPos,0xED6464,"Mining interrupted without confirmed destruction",true);
        }
        if (miningPos != null && client != null && client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }
        miningPos = null;
        miningTicks = 0;
        miningConfirmation.clear();
        cleanupMoveTarget = null;
        cleanupMoveTicks = 0;
    }

    private BlockPos cleanupMoveTarget;
    private int cleanupMoveTicks;

    private void tickMining(Minecraft client, Target target) {
        if (!MaterialTracker.isLoaded(client.level, target.pos)) {
            deferUnloaded("Area is unloaded at " + coordinates(target.pos) + ". Move closer, then Resume.");
            return;
        }
        BlockState actual = client.level.getBlockState(target.pos);
        if (miningConfirmation.active(target.pos) && miningConfirmation.confirmedAir()) {
            supportBlocks.remove(target.pos);
            navigator.removeTowerBlock(target.pos);
            stopMining(client);
            current = null;
            cooldown = 2;
            syncMarker(target.pos,0x72DF9A,"Server confirmed destruction",false);
            reason = "Cleared temporary block at " + coordinates(target.pos);
            return;
        }
        if (unknownMining.contains(target.pos)) {
            supportBlocks.remove(target.pos);
            navigator.removeTowerBlock(target.pos);
            current = null;
            return;
        }
        if (miningConfirmation.timedOut(tick)) {
            unknownMining.add(target.pos); failedActions++;
            syncMarker(target.pos,0xED6464,"Mining timed out without server-confirmed air",true);
            stopMining(client); navigator.replan();
            supportBlocks.remove(target.pos);
            navigator.removeTowerBlock(target.pos);
            current = null;
            return;
        }
        if (actual.isAir()) {
            // Vanilla can predict air before the server accepts STOP_DESTROY_BLOCK.
            navigator.hold(client,false);
            if (!miningConfirmation.active(target.pos)) {
                unknownMining.add(target.pos);
                supportBlocks.remove(target.pos);
                navigator.removeTowerBlock(target.pos);
                current = null;
            } else reason="Waiting for server-confirmed destruction at " + coordinates(target.pos);
            return;
        }
        navigator.hold(client,false);
        // Walk off the support before sending any destruction interaction. Never create another
        // temporary support to escape this one, and bound recovery if no safe position is reachable.
        if (client.player.getBoundingBox().move(0,-.1,0).intersects(new net.minecraft.world.phys.AABB(target.pos))
                || cleanupMoveTarget != null) {
            if (!target.pos.equals(cleanupMoveTarget)) {
                cleanupMoveTarget = target.pos;
                cleanupMoveTicks = 0;
                navigator.replan();
            }
            BuilderNavigator.Result movement = navigator.moveOffSupport(client, target.pos);
            if (movement == BuilderNavigator.Result.ARRIVED) {
                cleanupMoveTarget = null;
                cleanupMoveTicks = 0;
                navigator.replan();
            } else {
                if (movement == BuilderNavigator.Result.NO_PATH || movement == BuilderNavigator.Result.STUCK
                        || ++cleanupMoveTicks > 160) {
                    cleanupMoveTarget = null;
                    navigator.replan();
                    pauseWith("No safe route off temporary support at " + coordinates(target.pos)
                            + ". Provide nearby solid footing, then Resume cleanup.");
                } else reason = "Moving off temporary support before cleanup at " + coordinates(target.pos);
                return;
            }
        }

        double distanceSq = client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target.pos));
        double reach = client.player.blockInteractionRange() - 0.2;
        if (distanceSq > reach * reach) {
            BuilderNavigator.Result movement = navigator.moveTo(client, target.pos, actual);
            if (movement == BuilderNavigator.Result.NO_PATH || movement == BuilderNavigator.Result.STUCK) {
                defer("Cannot reach temporary block at " + coordinates(target.pos) + ". Provide access, then Resume.");
                return;
            }
            reason = "Walking to temporary block at " + coordinates(target.pos);
            return;
        }

        BlockHitResult hit = findMiningHit(client, target.pos);
        if (hit == null) {
            // Distance alone is not access: never keep aiming through the first obstructing block.
            if (miningPos != null) {
                stopMining(client);
                pauseWith("Mining access changed at " + coordinates(target.pos) + ". Await server state, then Resume.");
                return;
            }
            cleanupMoveTarget = target.pos;
            cleanupMoveTicks = 0;
            navigator.replan();
            reason = "Finding an unobstructed mining position at " + coordinates(target.pos);
            return;
        }
        Vec3 hitPos = hit.getLocation();
        Vec3 eye = client.player.getEyePosition();
        double dx = hitPos.x - eye.x;
        double dy = hitPos.y - eye.y;
        double dz = hitPos.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float)(net.minecraft.util.Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float)(-(net.minecraft.util.Mth.atan2(dy, dist) * (180.0 / Math.PI)));
        // Line-of-sight validation before sending mining packets to anticheat:
        // Ensure transmitted rotation already intersects the target block to satisfy server line-of-sight
        var sent = (BuilderLocalPlayerAccessor) client.player;
        float sentYaw = sent.cli$lastSentYaw();
        float sentPitch = sent.cli$lastSentPitch();
        Vec3 sentLook = calculateViewVector(sentPitch, sentYaw);
        Vec3 rayEnd = eye.add(sentLook.scale(client.player.blockInteractionRange()));
        BlockHitResult ray = client.level.clip(new ClipContext(eye, rayEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        boolean aimed = ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(target.pos);
        if (!aimed) {
            if (miningPos==null && nudgeStalledAlignment(client,false)) return;
            BuilderNavigator.rotateSmoothly(client.player, yaw, pitch, 45.0f);
            if (++aimAttempts > AIM_ATTEMPTS) {
                stopMining(client);
                pauseWith("Cannot align mining ray at " + coordinates(target.pos) + ". Check obstacles, then Resume.");
                return;
            }
            reason = "Aiming to clear temporary block at " + coordinates(target.pos);
            return;
        }
        aimAttempts = 0;
        Direction faceToSend = ray.getDirection();

        int bestSlot = dev.kuro.client.builder.autotool.AutoToolController.findBestSlot(client.player, actual, false);
        if (bestSlot >= 0 && bestSlot < 9 && client.player.getInventory().getSelectedSlot() != bestSlot) {
            client.player.getInventory().setSelectedSlot(bestSlot);
        }

        if (!target.pos.equals(miningPos)) {
            miningPos = target.pos;
            miningTicks = 0;
            miningConfirmation.begin(target.pos,tick);
            syncMarker(target.pos,0xF3A65C,"Mining: waiting for authoritative air",true);
            client.gameMode.startDestroyBlock(target.pos, faceToSend);
            client.player.swing(InteractionHand.MAIN_HAND);
        } else {
            miningTicks++;
            client.gameMode.continueDestroyBlock(target.pos, faceToSend);
            client.player.swing(InteractionHand.MAIN_HAND);

        }
        reason = "Clearing temporary block at " + coordinates(target.pos);
    }
}
