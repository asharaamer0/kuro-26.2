package dev.kuro.client.builder.autobuild;

import dev.kuro.client.builder.mixin.BlockItemAccessor;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Evaluates the same placement context as vanilla before sending an interaction. */
public final class PlacementPlanner {
    public record Plan(BlockHitResult hit, BlockState prediction, float yaw, float pitch, boolean configure, boolean bucket, boolean sneak) {
        public Plan(BlockHitResult hit, BlockState prediction, float yaw, float pitch, boolean configure, boolean bucket) {
            this(hit, prediction, yaw, pitch, configure, bucket, false);
        }
    }
    private static final Set<String> DERIVED = Set.of("powered", "power", "lit", "locked", "in_wall", "snowy",
            "distance", "north", "east", "south", "west", "up", "down", "instrument", "bottom", "enabled",
            "drag", "persistent", "age");
    /** One yaw per horizontal facing; vanilla derives facing-derived states from the player's yaw. */
    private static final float[] FACING_YAWS = {0f, 90f, 180f, 270f};
    private PlacementPlanner() { }

    /**
     * The yaw that points at the clicked face first, then one yaw for every horizontal facing.
     *
     * <p>Vanilla builds the placed state from {@code BlockPlaceContext.getHorizontalDirection()},
     * which reads the player's yaw at the moment of the interaction. A gate, door, bed, chest or
     * stair can therefore only be aligned by turning the player: aiming at the face alone leaves the
     * derived facing wherever the player happens to stand. Candidates are deduplicated by the facing
     * they produce, so this never repeats the aim's own facing.
     */
    static double turnCost(float yaw,float pitch,Vec3 delta) {
        double yawError = net.minecraft.util.Mth.wrapDegrees(aimYaw(delta)-yaw);
        double pitchError = aimPitch(delta)-pitch;
        return Math.hypot(yawError,pitchError);
    }

    public static float[] candidateYaws(float aimYaw) {
        float[] values = new float[FACING_YAWS.length + 1];
        values[0] = aimYaw;
        int count = 1;
        for (float candidate : FACING_YAWS) {
            Direction facing = Direction.fromYRot(candidate);
            boolean seen = false;
            for (int index = 0; index < count; index++) {
                if (Direction.fromYRot(values[index]) == facing) { seen = true; break; }
            }
            if (!seen) values[count++] = candidate;
        }
        return Arrays.copyOf(values, count);
    }

    public static boolean isDerived(Block block, Property<?> property) {
        String name = property.getName();
        if (DERIVED.contains(name)) return true;
        if (property == BlockStateProperties.CHEST_TYPE) return true;
        if (property == BlockStateProperties.STAIRS_SHAPE) return true;
        if (property == BlockStateProperties.RAIL_SHAPE || property == BlockStateProperties.RAIL_SHAPE_STRAIGHT) return true;
        if (property == BlockStateProperties.EXTENDED || property == BlockStateProperties.TRIGGERED || property == BlockStateProperties.CRAFTING) return true;
        if (property == BlockStateProperties.ROTATION_16) return true;
        if (property == BlockStateProperties.DOOR_HINGE) return true;
        return false;
    }

    public static boolean isFunctional(Block block) {
        return block instanceof HopperBlock || block instanceof ObserverBlock
                || block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                || block instanceof DispenserBlock || block instanceof DropperBlock
                || block instanceof RepeaterBlock || block instanceof ComparatorBlock;
    }

    public static boolean compatible(BlockState desired, BlockState predicted) {
        if (predicted == null) return false;
        if (desired.getBlock() != predicted.getBlock()) {
            var controller = AutoBuilderController.get();
            boolean allowSubs = controller == null || controller.isSubstitutesAllowed();
            if (!allowSubs || !MaterialSubstitutes.areSubstitutable(desired.getBlock(), predicted.getBlock())) return false;
        }
        for (Property<?> property : desired.getProperties()) {
            if (property == BlockStateProperties.CHEST_TYPE) {
                // If the schematic requests a SINGLE chest, the placement must not connect to an adjacent chest
                if (desired.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE
                        && predicted.hasProperty(BlockStateProperties.CHEST_TYPE)
                        && predicted.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE) {
                    return false;
                }
                continue;
            }
            if (isDerived(desired.getBlock(), property)) continue;
            if (isConfigurable(desired, property)) continue;
            if (property == SlabBlock.TYPE && desired.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) continue;
            if (isStackCount(property) && (Integer)predicted.getValue(property) <= (Integer)desired.getValue(property)) continue;
            if (property == BlockStateProperties.WATERLOGGED && desired.getValue(BlockStateProperties.WATERLOGGED) && !MaterialTracker.mustBePlacedInWater(desired)) continue;
            if (property == BedBlock.PART && desired.getValue(BedBlock.PART) == BedPart.HEAD) continue;
            if (property == BlockStateProperties.DOUBLE_BLOCK_HALF && desired.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) continue;
            if (!predicted.hasProperty(property)) return false;
            if (!desired.getValue(property).equals(predicted.getValue(property))) return false;
        }
        return true;
    }

    private static boolean isStackCount(Property<?> property) {
        return property == BlockStateProperties.LAYERS || property == BlockStateProperties.CANDLES
                || property == BlockStateProperties.PICKLES || property == BlockStateProperties.EGGS
                || property == BlockStateProperties.FLOWER_AMOUNT || property == BlockStateProperties.SEGMENT_AMOUNT;
    }
    /** True when a property is written by interacting with a block that is already in place. */
    public static boolean isConfigurable(Block block, Property<?> property) {
        return (block instanceof RepeaterBlock && property == BlockStateProperties.DELAY)
                || (block instanceof ComparatorBlock && property == BlockStateProperties.MODE_COMPARATOR)
                || (block instanceof NoteBlock && property == BlockStateProperties.NOTE)
                // Vanilla places a gate with the open state the signal at that position dictates, and every
                // other gate closed. An open gate in a file can therefore never come out of a placement, so
                // comparing the open state rejected placements that vanilla accepts; a plain use toggles it,
                // which reaches the requested state the same way repeater delay and note pitch are reached.
                || (block instanceof FenceGateBlock && property == BlockStateProperties.OPEN)
                || (block instanceof TrapDoorBlock && block != Blocks.IRON_TRAPDOOR && property == BlockStateProperties.OPEN);
    }
    public static boolean isConfigurable(BlockState state, Property<?> property) {
        return isConfigurable(state.getBlock(), property);
    }
    public static boolean needsConfiguration(BlockState expected, BlockState actual) {
        if (expected.getBlock() != actual.getBlock()
                && !MaterialSubstitutes.areSubstitutable(expected.getBlock(), actual.getBlock())) return false;
        for (Property<?> property : expected.getProperties()) {
            if (isConfigurable(expected, property) && !expected.getValue(property).equals(actual.getValue(property))) return true;
        }
        return false;
    }
    public static boolean canAdd(BlockState expected, BlockState actual) {
        if (actual.canBeReplaced()) return true;
        if (expected.getBlock() != actual.getBlock()
                && !MaterialSubstitutes.areSubstitutable(expected.getBlock(), actual.getBlock())) return false;
        if (expected.hasProperty(SlabBlock.TYPE)) return expected.getValue(SlabBlock.TYPE) == SlabType.DOUBLE
                && actual.getValue(SlabBlock.TYPE) != SlabType.DOUBLE;
        for (Property<?> property : expected.getProperties()) {
            if (isStackCount(property) && (Integer)expected.getValue(property) > (Integer)actual.getValue(property)) return true;
        }
        if (needsWaterlogging(expected, actual)) return true;
        return false;
    }

    public static boolean needsWaterlogging(BlockState expected, BlockState actual) {
        return expected.getBlock() == actual.getBlock()
                && expected.hasProperty(BlockStateProperties.WATERLOGGED)
                && expected.getValue(BlockStateProperties.WATERLOGGED)
                && actual.hasProperty(BlockStateProperties.WATERLOGGED)
                && !actual.getValue(BlockStateProperties.WATERLOGGED);
    }

    public static Plan plan(Minecraft client, BlockPos target, BlockState desired, ItemStack stack) {
        if (client.level == null || client.player == null
                || !AutoBuilderController.get().authoritativeSupport(target)) return null;
        BlockState actual = client.level.getBlockState(target);
        if (stack.is(Items.WATER_BUCKET) && (MaterialTracker.isWaterSource(desired) || needsWaterlogging(desired, actual)))
            return fluid(client, target, desired);
        boolean configure = needsConfiguration(desired, actual);
        if (!configure && !(stack.getItem() instanceof BlockItem)) return null;
        List<BlockHitResult> hits = new ArrayList<>();
        if (!actual.isAir() && actual.getBlock() == desired.getBlock()) {
            addFaces(hits, target, actual, client);
        } else {
            for (Direction face : new Direction[]{Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.DOWN}) {
                BlockPos support = target.relative(face.getOpposite());
                if (!MaterialTracker.isLoaded(client.level,support)
                        || !AutoBuilderController.get().authoritativeSupport(support)) continue;
                BlockState supportState = client.level.getBlockState(support);
                if (supportState.isAir() || supportState.canBeReplaced() || supportState.getShape(client.level,support).isEmpty()) continue;
                addFace(hits, support, face, supportState, client);
            }
        }
        Vec3 eye = client.player.getEyePosition();
        double reach = Math.min(AutoBuilderController.get().getReach(), client.player.blockInteractionRange()) - .05;
        float oldYaw = client.player.getYRot(), oldPitch = client.player.getXRot();
        // Prefer low-turn visible surfaces rather than the first face in a fixed direction order.
        hits.sort(Comparator.comparingDouble(hit -> turnCost(oldYaw,oldPitch,hit.getLocation().subtract(eye))));
        try {
            if (actual.getBlock() == desired.getBlock()) {
                client.player.setShiftKeyDown(false);
            }
            if (configure) return toggle(client,desired,actual,hits,eye,reach);
            // The view's own angle at the clicked face first, so a plan that already works keeps the
            // face and the pose the builder had chosen. Only when no face can be placed from that angle
            // is the same search repeated from the steep angles - see STEEP_PITCHES.
            Plan plan = place(client,target,desired,stack,hits,eye,reach,false);
            if (plan == null) plan = place(client,target,desired,stack,hits,eye,reach,true);
            if (plan == null) {
                Vec3 standingEye = new Vec3(client.player.getX(), client.player.getY() + client.player.getEyeHeight(Pose.STANDING), client.player.getZ());
                plan = place(client,target,desired,stack,hits,standingEye,reach,false);
                if (plan == null) plan = place(client,target,desired,stack,hits,standingEye,reach,true);
            }
            return plan;
        } finally {
            client.player.setYRot(oldYaw);
            client.player.setXRot(oldPitch);
            client.player.setShiftKeyDown(false);
        }
    }

    /**
     * The pitches one clicked face is tried with.
     *
     * <p>Vanilla reads an up or down facing out of the view's vertical direction: {@code
     * BlockPlaceContext.getNearestLookingDirection} is {@code Direction.orderedByNearest}, whose first
     * choice is the view's vertical direction only once its vertical component beats the horizontal ones
     * scaled by {@code cos(pitch)} - true past 45 degrees whatever the yaw is, and never true of the
     * shallow angle that points at a clicked face. An observer, dropper, dispenser, barrel or piston built
     * facing up or down therefore failed on every face, whichever way the player stood, because the one
     * thing those states need is an angle the aim cannot have. A player placing one by hand looks steeply
     * up or down at the same face, which is exactly what the second pass does.
     *
     * <p>Both signs are offered because vanilla's blocks disagree about whether they take the view's own
     * direction: an observer faces the way the player looks, a dispenser faces away from it. Which of the
     * two is right is settled by comparing the predicted state with the file's own state, so no block is
     * singled out here. The angle is 60 degrees rather than 90 so the vertical component wins with room
     * to spare - and still wins if a server rounds the transmitted angle to a coarser step.
     */
    static float[] candidatePitches(float aimPitch,boolean steep) {
        if (steep) return STEEP_PITCHES.clone();
        if (Math.abs(aimPitch) >= 35f) return new float[]{aimPitch, 0f};
        return new float[]{aimPitch};
    }
    /** Pitches whose nearest look direction is necessarily straight up or straight down. */
    private static final float[] STEEP_PITCHES = {60f,-60f};

    private static float aimYaw(Vec3 delta) {
        return (float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
    }
    private static float aimPitch(Vec3 delta) {
        return (float)Math.toDegrees(Math.atan2(-delta.y,Math.hypot(delta.x,delta.z)));
    }

    /**
     * The plan an interaction with the block already in place produces: a repeater's next delay, a
     * comparator's toggled mode, the next note, or an opened or closed gate. A plain use reads no
     * vertical angle, so the aim's own pitch is enough.
     */
    private static Plan toggle(Minecraft client,BlockState desired,BlockState actual,List<BlockHitResult> hits,Vec3 eye,double reach) {
        for (BlockHitResult hit : hits) {
            if (!reachable(client,hit,eye,reach)) continue;
            Vec3 delta = hit.getLocation().subtract(eye);
            float yaw = toggleYaw(desired,actual,aimYaw(delta));
            float pitch = aimPitch(delta);
            client.player.setYRot(yaw); client.player.setXRot(pitch);
            return new Plan(hit,toggled(actual),yaw,pitch,true,false);
        }
        return null;
    }

    public static Direction getConnectedDirection(BlockState state) {
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) || !state.hasProperty(BlockStateProperties.CHEST_TYPE)) {
            return null;
        }
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        ChestType type = state.getValue(BlockStateProperties.CHEST_TYPE);
        if (type == ChestType.SINGLE) return null;
        return type == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise();
    }

    public static boolean hasChestPartner(Minecraft client, BlockPos target, BlockState desired) {
        if (client == null || client.level == null || !(desired.getBlock() instanceof ChestBlock)) return false;
        Direction partnerDir = getConnectedDirection(desired);
        if (partnerDir == null) return false;
        BlockPos partnerPos = target.relative(partnerDir);
        if (!MaterialTracker.isLoaded(client.level, partnerPos)) return false;
        BlockState partner = client.level.getBlockState(partnerPos);
        return partner.getBlock() == desired.getBlock()
                && partner.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                && partner.getValue(BlockStateProperties.HORIZONTAL_FACING) == desired.getValue(BlockStateProperties.HORIZONTAL_FACING)
                && partner.hasProperty(BlockStateProperties.CHEST_TYPE)
                && partner.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE;
    }

    public static boolean isInteractive(BlockState state) {
        if (state == null) return false;
        Block block = state.getBlock();
        if (state.hasBlockEntity()) return true;
        return block instanceof CraftingTableBlock
                || block instanceof AnvilBlock
                || block instanceof LoomBlock
                || block instanceof CartographyTableBlock
                || block instanceof GrindstoneBlock
                || block instanceof StonecutterBlock
                || block instanceof SmithingTableBlock
                || block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock
                || block instanceof ButtonBlock
                || block instanceof LeverBlock
                || block instanceof DiodeBlock
                || block instanceof NoteBlock
                || block instanceof DaylightDetectorBlock
                || block instanceof RespawnAnchorBlock
                || block instanceof BedBlock
                || block instanceof CakeBlock
                || block instanceof CandleCakeBlock;
    }

    /** Revalidate the actual transmitted angle and ray, not just the planned face or yaw quadrant. */
    public static boolean matchesInteraction(Minecraft client,BlockPos target,BlockState desired,
                                             ItemStack stack,BlockHitResult hit,float yaw,float pitch,boolean sneak) {
        if (!(stack.getItem() instanceof BlockItem item)) return false;
        float oldYaw=client.player.getYRot(), oldPitch=client.player.getXRot();
        boolean oldSneak=client.player.isShiftKeyDown();
        try {
            client.player.setYRot(yaw); client.player.setXRot(pitch);
            client.player.setShiftKeyDown(sneak);
            BlockPlaceContext context=item.updatePlacementContext(new BlockPlaceContext(client.player,InteractionHand.MAIN_HAND,stack,hit));
            if (context==null || !context.getClickedPos().equals(target) || !context.canPlace()) return false;
            BlockState predicted=((BlockItemAccessor)item).cli$getPlacementState(context);
            return predicted!=null && compatible(desired,predicted);
        } finally {
            client.player.setYRot(oldYaw); client.player.setXRot(oldPitch);
            client.player.setShiftKeyDown(oldSneak);
        }
    }

    /** The placement for one candidate angle, or null when no clicked face can produce the state. */
    private static Plan place(Minecraft client,BlockPos target,BlockState desired,ItemStack stack,
                              List<BlockHitResult> hits,Vec3 eye,double reach,boolean steep) {
        BlockItem item = (BlockItem)stack.getItem();
        boolean needsChestConnection = hasChestPartner(client, target, desired);
        boolean isChest = desired.getBlock() instanceof ChestBlock;
        boolean desiredSingleChest = isChest && desired.hasProperty(BlockStateProperties.CHEST_TYPE)
                && desired.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE;

        for (BlockHitResult hit : hits) {
            if (!reachable(client,hit,eye,reach)) continue;
            BlockState clickedState = client.level != null ? client.level.getBlockState(hit.getBlockPos()) : null;
            boolean interactive = isInteractive(clickedState);

            boolean[] candidateSneaks;
            if (needsChestConnection) {
                // Clicking an interactive block (like the existing chest) without sneaking opens its GUI instead of placing.
                // To connect a double chest, placement must be against a non-interactive surface (e.g. floor/wall/support).
                if (interactive) continue;
                candidateSneaks = new boolean[]{false};
            } else if (desiredSingleChest) {
                // Must sneak when placing chest next to another chest if we desire single chests
                candidateSneaks = new boolean[]{true, false};
            } else if (interactive) {
                candidateSneaks = new boolean[]{true};
            } else if (desired.getBlock() instanceof SlabBlock) {
                candidateSneaks = new boolean[]{false, true};
            } else {
                candidateSneaks = new boolean[]{false, true};
            }

            Vec3 delta = hit.getLocation().subtract(eye);
            float aim = aimYaw(delta);
            for (float pitch : candidatePitches(aimPitch(delta),steep)) {
                client.player.setXRot(pitch);
                // Turning the player is the only way to control facing-derived states, so every
                // candidate yaw is evaluated against the same clicked face before the next face.
                for (float yaw : candidateYaws(aim)) {
                    client.player.setYRot(yaw);
                    // Vanilla line-of-sight check: only candidate angles that actually intersect the clicked block face
                    if (client.level != null && client.player != null) {
                        Vec3 look = client.player.calculateViewVector(pitch, yaw);
                        Vec3 end = eye.add(look.scale(reach));
                        BlockHitResult ray = client.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
                        if (ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(hit.getBlockPos()) || ray.getDirection() != hit.getDirection()) {
                            continue;
                        }
                        for (boolean sneak : candidateSneaks) {
                            client.player.setShiftKeyDown(sneak);
                            BlockPlaceContext context = item.updatePlacementContext(new BlockPlaceContext(client.player,InteractionHand.MAIN_HAND,stack,ray));
                            if (context == null || !context.getClickedPos().equals(target) || !context.canPlace()) continue;
                            BlockState predicted = ((BlockItemAccessor)item).cli$getPlacementState(context);
                            if (predicted == null) continue;
                            if (needsChestConnection) {
                                if (!predicted.hasProperty(BlockStateProperties.CHEST_TYPE)
                                        || predicted.getValue(BlockStateProperties.CHEST_TYPE) != desired.getValue(BlockStateProperties.CHEST_TYPE)) {
                                    continue;
                                }
                            }
                            if (compatible(desired,predicted)) return new Plan(ray,predicted,yaw,pitch,false,false,sneak);
                        }
                        continue;
                    }

                    for (boolean sneak : candidateSneaks) {
                        client.player.setShiftKeyDown(sneak);
                        BlockPlaceContext context = item.updatePlacementContext(new BlockPlaceContext(client.player,InteractionHand.MAIN_HAND,stack,hit));
                        if (context == null || !context.getClickedPos().equals(target) || !context.canPlace()) continue;
                        BlockState predicted = ((BlockItemAccessor)item).cli$getPlacementState(context);
                        if (predicted == null) continue;
                        if (needsChestConnection) {
                            if (!predicted.hasProperty(BlockStateProperties.CHEST_TYPE)
                                    || predicted.getValue(BlockStateProperties.CHEST_TYPE) != desired.getValue(BlockStateProperties.CHEST_TYPE)) {
                                continue;
                            }
                        }
                        if (compatible(desired,predicted)) return new Plan(hit,predicted,yaw,pitch,false,false,sneak);
                    }
                }
            }
        }
        return null;
    }
    /**
     * Aim and interaction for filling one cell with water.
     *
     * <p>A filled bucket has no block context to click: vanilla traces the player's own view
     * (`Item.getPlayerPOVHitResult`: the eye, the view vector, `blockInteractionRange`, `OUTLINE`, no
     * fluids) and pours into the cell in front of the first block that trace meets. The aim is therefore
     * the centre of the requested cell, and the same trace is repeated here so the plan is only offered
     * when the first hit really is the block behind that cell. Otherwise a cell further along the view
     * would be filled instead, which is exactly how a pour ends up somewhere the file never asked for.
     */
    public static Plan fluid(Minecraft client,BlockPos target,BlockState desired) {
        Vec3 eye = client.player.getEyePosition();
        double reachDist = Math.min(AutoBuilderController.get().getReach(), client.player.blockInteractionRange()) - .05;
        BlockState actual = client.level.getBlockState(target);

        // If target block already exists and can be waterlogged, try candidate points on its shape
        if (actual.getBlock() instanceof LiquidBlockContainer container
                && container.canPlaceLiquid(client.player, client.level, target, actual, net.minecraft.world.level.material.Fluids.WATER)) {
            VoxelShape shape = actual.getShape(client.level, target);
            if (!shape.isEmpty()) {
                for (Direction face : Direction.values()) {
                    for (Vec3 point : facePoints(face, shape, target)) {
                        Vec3 d = point.subtract(eye);
                        float y = aimYaw(d), p = aimPitch(d);
                        Vec3 lk = client.player.calculateViewVector(p, y);
                        Vec3 rch = eye.add(lk.scale(reachDist));
                        BlockHitResult h = client.level.clip(new ClipContext(eye, rch, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
                        if (h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(target)) {
                            return new Plan(h, desired, y, p, true, true);
                        }
                    }
                }
            }
        }

        Vec3 delta = Vec3.atCenterOf(target).subtract(eye);
        float yaw = (float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
        float pitch = (float)Math.toDegrees(Math.atan2(-delta.y,Math.hypot(delta.x,delta.z)));
        Vec3 look = client.player.calculateViewVector(pitch,yaw);
        Vec3 reach = eye.add(look.scale(reachDist));
        BlockHitResult hit = client.level.clip(new ClipContext(eye,reach,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,client.player));
        if (hit.getType() == HitResult.Type.BLOCK) {
            boolean intoAir = hit.getBlockPos().relative(hit.getDirection()).equals(target);
            boolean waterlog = hit.getBlockPos().equals(target)
                    && client.level.getBlockState(target).getBlock() instanceof LiquidBlockContainer container
                    && container.canPlaceLiquid(client.player, client.level, target, client.level.getBlockState(target), net.minecraft.world.level.material.Fluids.WATER);
            if (intoAir || waterlog) return new Plan(hit,desired,yaw,pitch,true,true);
        }

        // If direct center missed, check faces of neighbor blocks that pour into target
        for (Direction face : Direction.values()) {
            BlockPos support = target.relative(face.getOpposite());
            if (!MaterialTracker.isLoaded(client.level, support)
                    || !AutoBuilderController.get().authoritativeSupport(support)) continue;
            BlockState supportState = client.level.getBlockState(support);
            if (supportState.isAir() || supportState.canBeReplaced()) continue;
            VoxelShape shape = supportState.getShape(client.level, support);
            if (shape.isEmpty()) continue;
            for (Vec3 point : facePoints(face, shape, support)) {
                Vec3 d = point.subtract(eye);
                float y = aimYaw(d), p = aimPitch(d);
                Vec3 lk = client.player.calculateViewVector(p, y);
                Vec3 rch = eye.add(lk.scale(reachDist));
                BlockHitResult h = client.level.clip(new ClipContext(eye, rch, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
                if (h.getType() == HitResult.Type.BLOCK && h.getBlockPos().relative(h.getDirection()).equals(target)) {
                    return new Plan(h, desired, y, p, true, true);
                }
            }
        }
        return null;
    }

    /** What a pour needs to know about the cells water could reach from one position. */
    public interface Cells {
        boolean isLoaded(BlockPos pos);
        boolean holdsWater(BlockPos pos);
        boolean isBuilt(BlockPos pos);
        boolean isInBuild(BlockPos pos);
    }

    /**
     * True when water poured into this cell cannot reach a cell the build does not describe.
     *
     * <p>Water flows sideways and down, so those five cells decide it: water that is already there, a
     * block that is already in place and cannot be replaced, or a cell of the schematic itself all keep
     * the pour inside the build. Anything else is a hole that flowing water would stay in, which is why a
     * pour waits for the surrounding blocks instead of flooding what is beside them.
     */
    public static boolean sealedForWater(BlockPos pos,Cells cells) {
        for (Direction face : new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST,Direction.DOWN}) {
            BlockPos neighbour = pos.relative(face);
            if (!cells.isLoaded(neighbour)) return false;
            if (cells.holdsWater(neighbour) || cells.isBuilt(neighbour) || cells.isInBuild(neighbour)) continue;
            return false;
        }
        return true;
    }

    /**
     * The yaw a toggle interaction is sent with.
     *
     * <p>A gate is the one configurable block whose use reads the player's view: opening one flips it to
     * face the player when it was facing the other way. Facing the side the file asks for keeps that
     * axis, and a use does not need the clicked face's own angle, so the aim may be chosen for this.
     * Every other toggle keeps the aim.
     */
    static float toggleYaw(BlockState desired, BlockState actual, float aimYaw) {
        if (actual.getBlock() instanceof FenceGateBlock && desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            return desired.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
        return aimYaw;
    }

    /**
     * The state a plain use produces on a block the builder already placed: a repeater's next delay, a
     * comparator's toggled mode, the next note, or an opened or closed gate.
     */
    private static BlockState toggled(BlockState actual) {
        if (actual.getBlock() instanceof RepeaterBlock) return actual.cycle(BlockStateProperties.DELAY);
        if (actual.getBlock() instanceof ComparatorBlock) return actual.cycle(BlockStateProperties.MODE_COMPARATOR);
        if (actual.getBlock() instanceof NoteBlock) return actual.cycle(BlockStateProperties.NOTE);
        if (actual.getBlock() instanceof FenceGateBlock) return actual.cycle(BlockStateProperties.OPEN);
        if (actual.getBlock() instanceof TrapDoorBlock) return actual.cycle(BlockStateProperties.OPEN);
        return actual;
    }

    /**
     * The requested properties the placed block has not taken yet and that only the blocks around it can
     * write, or an empty list when the difference is the builder's to fix.
     *
     * <p>Vanilla writes powered, redstone power, lit, the wall state and the neighbour connections from
     * the surrounding blocks whenever they change. A gate between two cobblestone walls, a fence meeting
     * a fence or a stair with a block beside it therefore reaches the requested state as the rest of the
     * structure is built, so the builder keeps the position queued instead of calling it an occupied
     * block and stopping for a manual fix that the structure itself provides.
     */
    public static List<String> awaitingNeighbours(BlockState desired, BlockState actual) {
        if (desired.getBlock() != actual.getBlock() || desired.equals(actual)) return List.of();
        List<String> waiting = new ArrayList<>();
        for (Property<?> property : desired.getProperties()) {
            if (desired.getValue(property).equals(actual.getValue(property))) continue;
            if (!isDerived(desired.getBlock(), property)) return List.of();
            waiting.add(property.getName());
        }
        return waiting;
    }

    /**
     * True when the eye can still reach the clicked point and still sees it as the first block hit.
     *
     * <p>Used both to choose a hit and to keep the plan the aim was already built around. Re-picking the
     * face on every tick instead swung the view between opposite sides of one block whenever the player
     * kept sliding or the pose changed underneath, which looked like the builder turning away and back
     * without ever placing anything.
     */
    private static boolean reachable(Minecraft client,BlockHitResult hit,Vec3 eye,double reach) {
        Vec3 point = hit.getLocation();
        if (eye.distanceToSqr(point) > reach * reach) return false;
        Vec3 inside = point.subtract(Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i()).scale(.002));
        BlockHitResult visible = client.level.clip(new ClipContext(eye,inside,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,client.player));
        if (visible.getType() != HitResult.Type.BLOCK || !visible.getBlockPos().equals(hit.getBlockPos())) return false;
        return visible.getDirection() == hit.getDirection() || visible.getLocation().distanceToSqr(point) < 0.04;
    }

    /** True while an already chosen plan can still be clicked and the block under it is still solid. */
    public static boolean stillValid(Minecraft client,Plan plan) {
        if (client.level == null || client.player == null
                || !AutoBuilderController.get().authoritativeSupport(plan.hit().getBlockPos())) return false;
        BlockState clicked = client.level.getBlockState(plan.hit().getBlockPos());
        if (clicked.isAir() || clicked.canBeReplaced()) return false;
        return reachable(client,plan.hit(),client.player.getEyePosition(),Math.min(AutoBuilderController.get().getReach(), client.player.blockInteractionRange()) - .05);
    }

    /** Mining may only aim at a real surface for which the target is the first ray hit. */
    static BlockHitResult visibleMiningHit(net.minecraft.world.level.BlockGetter level, BlockPos target,
                                           Vec3 eye, double reach, float yaw, float pitch,
                                           net.minecraft.world.phys.shapes.CollisionContext collision) {
        VoxelShape shape = level.getBlockState(target).getShape(level,target);
        if (shape.isEmpty()) return null;
        List<BlockHitResult> candidates = new ArrayList<>();
        for (Direction face : Direction.values()) {
            for (Vec3 point : facePoints(face,shape,target)) {
                if (eye.distanceToSqr(point)>reach*reach) continue;
                Vec3 inside=point.subtract(new Vec3(face.getStepX(),face.getStepY(),face.getStepZ()).scale(.002));
                BlockHitResult ray=level.clip(new ClipContext(eye,inside,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,collision));
                if (ray.getType()==HitResult.Type.BLOCK && ray.getBlockPos().equals(target)) candidates.add(ray);
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(hit -> turnCost(yaw,pitch,hit.getLocation().subtract(eye))))
                .orElse(null);
    }

    private static void addFaces(List<BlockHitResult> hits,BlockPos pos,BlockState state,Minecraft client) {
        for (Direction face : Direction.values()) addFace(hits,pos,face,state,client);
    }
    /** Fractions of a face used for candidate click points, tested from the middle outwards. */
    private static final double[] FACE_LEVELS = {.5,.25,.75};
    private static final double[] RIM_LEVELS = {.0625,.9375};

    private static void addFace(List<BlockHitResult> hits,BlockPos pos,Direction face,BlockState state,Minecraft client) {
        VoxelShape shape = state.getShape(client.level,pos);
        if (shape.isEmpty()) return;
        for (Vec3 point : facePoints(face,shape,pos)) hits.add(new BlockHitResult(point,face,pos,false));
    }

    /**
     * Candidate click points on one face of a block, nearest the face centre first.
     *
     * <p>A single centre point per face is not enough. Building against terrain, in a corridor or
     * beside an existing wall very often leaves part of a face open while the middle of it is covered
     * by a neighbouring block, and then the builder reports a target it could plainly place - the view
     * is simply obstructed at the angle it picked. Each face is therefore tested as a small grid, and
     * only points that actually touch this block's shape are kept, so a stair, slab or fence is clicked
     * where it has geometry instead of in the empty part of its bounding box.
     */
    static List<Vec3> facePoints(Direction face,VoxelShape shape,BlockPos pos) {
        List<AABB> boxes = shape.toAabbs();
        if (boxes.isEmpty()) return List.of();
        AABB bounds = shape.bounds();
        Direction.Axis axis = face.getAxis(), first = perpendicular(axis,false), second = perpendicular(axis,true);
        double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? bounds.max(axis) : bounds.min(axis);
        double firstMin = bounds.min(first), firstSize = bounds.max(first) - firstMin;
        double secondMin = bounds.min(second), secondSize = bounds.max(second) - secondMin;
        List<double[]> offsets = new ArrayList<>();
        for (double across : FACE_LEVELS) for (double along : FACE_LEVELS) offsets.add(new double[]{across,along});
        offsets.sort(Comparator.comparingDouble(offset -> Math.hypot(offset[0]-.5,offset[1]-.5)));
        List<Vec3> points = new ArrayList<>(offsets.size());
        for (double[] offset : offsets) {
            double across = firstMin + firstSize * offset[0], along = secondMin + secondSize * offset[1];
            Vec3 local = new Vec3(component(Direction.Axis.X,axis,plane,first,across,second,along),
                    component(Direction.Axis.Y,axis,plane,first,across,second,along),
                    component(Direction.Axis.Z,axis,plane,first,across,second,along));
            if (points.contains(local) || !touches(boxes,local)) continue;
            points.add(local);
        }
        // If candidate points missed parts of the face (e.g. hollow blocks like hoppers, composters, cauldrons):
        for (AABB box : boxes) {
            double boxPlane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(axis) : box.min(axis);
            if (Math.abs(boxPlane - plane) < 1.0E-5) {
                boolean covered = false;
                for (Vec3 pt : points) {
                    if (box.inflate(1.0E-7).contains(pt.x, pt.y, pt.z)) { covered = true; break; }
                }
                if (!covered) {
                    double firstMid = (box.min(first) + box.max(first)) * 0.5;
                    double secondMid = (box.min(second) + box.max(second)) * 0.5;
                    Vec3 local = new Vec3(component(Direction.Axis.X, axis, plane, first, firstMid, second, secondMid),
                            component(Direction.Axis.Y, axis, plane, first, firstMid, second, secondMid),
                            component(Direction.Axis.Z, axis, plane, first, firstMid, second, secondMid));
                    if (!points.contains(local) && touches(boxes, local)) points.add(local);
                }
            }
        }
        if (points.isEmpty()) {
            List<double[]> rimOffsets = new ArrayList<>();
            for (double across : RIM_LEVELS) for (double along : List.of(.5, .25, .75, .0625, .9375)) {
                rimOffsets.add(new double[]{across, along});
                rimOffsets.add(new double[]{along, across});
            }
            for (double[] offset : rimOffsets) {
                double across = firstMin + firstSize * offset[0], along = secondMin + secondSize * offset[1];
                Vec3 local = new Vec3(component(Direction.Axis.X,axis,plane,first,across,second,along),
                        component(Direction.Axis.Y,axis,plane,first,across,second,along),
                        component(Direction.Axis.Z,axis,plane,first,across,second,along));
                if (points.contains(local) || !touches(boxes,local)) continue;
                points.add(local);
            }
        }
        List<Vec3> world = new ArrayList<>(points.size());
        for (Vec3 point : points) world.add(point.add(pos.getX(),pos.getY(),pos.getZ()));
        return world;
    }

    /** The other two axes of a face, in a stable order. */
    private static Direction.Axis perpendicular(Direction.Axis axis,boolean second) {
        return switch (axis) {
            case X -> second ? Direction.Axis.Z : Direction.Axis.Y;
            case Y -> second ? Direction.Axis.Z : Direction.Axis.X;
            case Z -> second ? Direction.Axis.Y : Direction.Axis.X;
        };
    }

    private static double component(Direction.Axis wanted,Direction.Axis planeAxis,double plane,
                                    Direction.Axis firstAxis,double first,Direction.Axis secondAxis,double second) {
        if (wanted == planeAxis) return plane;
        return wanted == firstAxis ? first : second;
    }

    /** True when the point lies on the block's own geometry rather than in a gap inside its bounds. */
    private static boolean touches(List<AABB> boxes,Vec3 point) {
        for (AABB box : boxes) if (box.inflate(1.0E-7).contains(point.x,point.y,point.z)) return true;
        return false;
    }
}
