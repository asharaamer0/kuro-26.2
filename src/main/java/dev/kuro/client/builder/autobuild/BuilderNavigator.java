package dev.kuro.client.builder.autobuild;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;

/** Walks and swims using vanilla player input. Does not teleport, fly, mine, or modify the world. */
public final class BuilderNavigator {
    public enum Result { MOVING, ARRIVED, NO_PATH, STUCK }
    private LocalPlayer owner;
    private ClientInput saved;
    private final SteeringInput steering = new SteeringInput();
    private final HeldItemStability heldItemStability = new HeldItemStability();
    private List<BuilderPathfinder.Cell> route = List.of();
    private int index, stalled;
    private int waypointTicks;
    private int lastWaypointIndex = -1;
    private int replansForTarget;
    private Vec3 lastPosition;
    private BlockPos target;
    private BlockPos cleanupSupport;
    private final Set<BuilderPathfinder.Cell> rejectedGoals = new HashSet<>();
    private final Map<BuilderPathfinder.Cell, Long> blockedCells = new HashMap<>();
    private boolean bridgingAllowed = true;
    private boolean towerAllowed = true;
    private boolean sprintAllowed = true;
    private boolean sprintJumpAllowed = true;
    private boolean smoothRoutesAllowed = true;

    public void setSprint(boolean allowed) { this.sprintAllowed = allowed; }
    public void setSprintJump(boolean allowed) { this.sprintJumpAllowed = allowed; }
    public void setSmoothRoutes(boolean allowed) { this.smoothRoutesAllowed = allowed; }
    private boolean towering = false;
    private int towerTicks = 0;
    private int towerPlacedCount = 0;
    private BlockPos currentTowerPos;
    private static final int MAX_TOWER_BLOCKS = 10;
    private final List<BlockPos> placedTowerBlocks = new ArrayList<>();
    private int savedTowerSlot = -1;
    private int bridgeHoldSneakTicks = 0;
    private boolean towerPlacedThisJump = false;
    private int towerJumpAttempts = 0;
    private final SupportConfirmation supportConfirmation = new SupportConfirmation();
    private net.minecraft.client.multiplayer.ClientLevel supportLevel;
    private boolean pendingTowerSupport, supportFailed;
    private long navigationTicks, towerRetryAfter;
    private int towerAttempts, lastTowerRouteY = Integer.MIN_VALUE;
    private long pathSearches, pathNanos, supportFailures, confirmedSupports, confirmationWaitTicks;

    public record Metrics(long pathSearches, long pathNanos, long supportFailures,
                          long confirmedSupports, long confirmationWaitTicks) { }
    public Metrics metrics() {
        return new Metrics(pathSearches, pathNanos, supportFailures, confirmedSupports, confirmationWaitTicks);
    }
    public boolean hasPendingSupport() { return supportConfirmation.pending(); }
    private Predicate<BlockPos> externalSupport = pos -> true;
    public void setExternalSupportPredicate(Predicate<BlockPos> predicate) { externalSupport = predicate; }
    public boolean isSupportUsable(BlockPos pos) { return supportConfirmation.usable(pos) && externalSupport.test(pos); }
    public void serverBlockUpdate(BlockPos pos, BlockState state) { supportConfirmation.observe(pos, state); }

    /** Called before controller planning too: predicted supports must not unlock a schematic placement. */
    public void pollSupport(Minecraft client) {
        if (client.level != null) navigationTicks = client.level.getGameTime();
        if (supportLevel != client.level) {
            supportConfirmation.clear(); supportLevel = client.level; supportFailed = false;
            towerAttempts = 0; towerRetryAfter = 0;
        }
        if (!supportConfirmation.pending()) return;
        hold(client, false);
        confirmationWaitTicks++;
        SupportConfirmation.Outcome outcome = supportConfirmation.outcome(navigationTicks);
        if (outcome == SupportConfirmation.Outcome.WAITING) return;
        BlockPos pos = supportConfirmation.position();
        supportConfirmation.finish();
        if (outcome == SupportConfirmation.Outcome.CONFIRMED
                && isSolidBridgeSupport(client, pos)) {
            confirmedSupports++;
            if (pendingTowerSupport) {
                towerPlacedCount++;
                towerPlacedThisJump = true;
                if (!placedTowerBlocks.contains(pos)) placedTowerBlocks.add(pos);
            }
            if (bridgePlacedCallback != null) bridgePlacedCallback.accept(pos);
        } else {
            supportFailures++;
            supportFailed = true;
            towerRetryAfter = navigationTicks + 100;
            replan();
        }
    }

    private void awaitSupport(Minecraft client, BlockPos pos, boolean tower) {
        if (supportLevel != client.level) { supportConfirmation.clear(); supportLevel = client.level; }
        BlockState expected = client.player.getMainHandItem().getItem() instanceof BlockItem item
                ? item.getBlock().defaultBlockState() : client.level.getBlockState(pos);
        supportConfirmation.begin(pos, expected, navigationTicks);
        pendingTowerSupport = tower;
        steering.forward = false; steering.jump = false; steering.sprint = false;
    }

    private Consumer<BlockPos> bridgePlacedCallback;
    private Predicate<BlockPos> inSchematicPredicate;

    private final class SteeringInput extends ClientInput {
        boolean forward, jump, sneak, sprint;
        @Override public void tick() {
            if (isNavigating() || isTowering()) {
                keyPresses = new Input(forward, false, false, false, jump, sneak, sprint && forward && !sneak);
                float f = forward ? 1.0f : 0.0f;
                if (sneak) f *= 0.3f;
                moveVector = new Vec2(0, f);
            } else if (saved != null) {
                saved.tick();
                boolean effSneak = saved.keyPresses.shift() || sneak;
                keyPresses = new Input(
                        saved.keyPresses.forward(),
                        saved.keyPresses.backward(),
                        saved.keyPresses.left(),
                        saved.keyPresses.right(),
                        saved.keyPresses.jump(),
                        effSneak,
                        saved.keyPresses.sprint()
                );
                moveVector = saved.getMoveVector();
            } else {
                keyPresses = new Input(false, false, false, false, false, sneak, false);
                moveVector = new Vec2(0, 0);
            }
        }
        @Override public void makeJump() {
            if (isNavigating() || isTowering()) {
                if (jump) super.makeJump();
            } else if (saved != null) {
                saved.makeJump();
            }
        }
    }

    public void hold(Minecraft client, boolean sneak) {
        if (client.player == null) return;
        if (owner != client.player) {
            if (owner != null && owner.input == steering && saved != null) {
                owner.input = saved;
            }
            owner = client.player;
            if (owner.input != steering) {
                saved = owner.input;
                owner.input = steering;
            }
        }
        steering.forward = false; steering.jump = false; steering.sprint = false; steering.sneak = sneak; steering.tick();
        client.player.setShiftKeyDown(sneak);
    }

    /** One vanilla-input tick forward, only across known, level, collision-free footing. */
    public boolean nudgeForward(Minecraft client, boolean sneak) {
        if (client.player == null || client.level == null || !client.player.onGround()
                || supportConfirmation.pending()) return false;
        double yaw = Math.toRadians(client.player.getYRot());
        Vec3 step = new Vec3(-Math.sin(yaw)*.35,0,Math.cos(yaw)*.35);
        AABB swept = client.player.getBoundingBox().expandTowards(step);
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(swept.minX),Mth.floor(swept.minY-.1),Mth.floor(swept.minZ),
                Mth.floor(swept.maxX),Mth.floor(swept.maxY),Mth.floor(swept.maxZ))) {
            if (!MaterialTracker.isLoaded(client.level,pos) || !isSupportUsable(pos)) return false;
        }
        if (!client.level.noCollision(client.player,swept)) return false;
        AABB ahead = client.player.getBoundingBox().move(step);
        int floorY = Mth.floor(ahead.minY-.1);
        for (int x=Mth.floor(ahead.minX);x<=Mth.floor(ahead.maxX);x++) {
            for (int z=Mth.floor(ahead.minZ);z<=Mth.floor(ahead.maxZ);z++) {
                if (!world(client).floor(new BlockPos(x,floorY,z))) return false;
            }
        }
        hold(client,sneak);
        steering.forward=true;
        steering.tick();
        return true;
    }

    public void release() {
        if (owner != null && owner.input == steering && saved != null) owner.input = saved;
        if (owner != null) owner.setShiftKeyDown(false);
        owner = null; saved = null; steering.forward = false; steering.jump = false; steering.sneak = false; steering.sprint = false;
        stalled = 0; lastPosition = null;
        if (!towering) stopTowering();
    }
    public void replan() { route = List.of(); index = 0; stalled = 0; lastPosition = null; waypointTicks = 0; lastWaypointIndex = -1; bridgeHoldSneakTicks = 0; stopTowering(); }
    public void reset() { release(); replan(); towerAttempts = 0; target = null; rejectedGoals.clear(); blockedCells.clear(); replansForTarget = 0; waypointTicks = 0; lastWaypointIndex = -1; bridgeHoldSneakTicks = 0; stopTowering(); }
    public void rejectCurrent(Minecraft client) {
        if (!route.isEmpty() && index < route.size()) {
            BuilderPathfinder.Cell blocked = route.get(index);
            blockedCells.put(blocked, System.currentTimeMillis() + 45000L);
            if (client.player != null && (client.player.isInWater() || client.level.getFluidState(client.player.blockPosition()).is(net.minecraft.tags.FluidTags.WATER))) {
                BuilderPathfinder.Cell feet = cell(client.player.blockPosition());
                blockedCells.put(feet, System.currentTimeMillis() + 45000L);
            }
            if (++replansForTarget >= 2 || index >= route.size() - 2) {
                rejectedGoals.add(route.get(route.size() - 1));
            }
        }
        replan();
    }
    public void serverPositionCorrected(Minecraft client) {
        replan();
        long now=client.level == null ? navigationTicks : client.level.getGameTime();
        towerRetryAfter=Math.max(towerRetryAfter,now+100);
        hold(client,false);
    }

    public int routeLength() { return Math.max(0, route.size() - index); }

    public void setBridgingAllowed(boolean allowed) { this.bridgingAllowed = allowed; }
    public boolean isBridgingAllowed() { return bridgingAllowed; }
    public void setTowerAllowed(boolean allowed) { this.towerAllowed = allowed; }
    public boolean isTowerAllowed() { return towerAllowed; }
    public boolean isTowering() { return towering; }
    public boolean isNavigating() { return (!route.isEmpty() && index < route.size()) || towering; }
    public int getTowerPlacedCount() { return towerPlacedCount; }

    public void startTowering(Minecraft client) {
        if (!towerAllowed || client == null || client.player == null || !canTowerFrom(client)) return;
        if (navigationTicks < towerRetryAfter || towerAttempts >= 2) return;
        towerAttempts++;
        towering = true;
        lastTowerRouteY = Integer.MIN_VALUE;
        towerTicks = 0;
        towerPlacedCount = 0;
        currentTowerPos = null;
        towerPlacedThisJump = false;
        towerJumpAttempts = 0;
        rejectedGoals.clear();
        blockedCells.clear();
        route = List.of();
        index = 0;
        stalled = 0;
        savedTowerSlot = client.player.getInventory().getSelectedSlot();
        int slot = findTowerMaterialSlot(client);
        if (slot >= 0 && slot < 9) {
            client.player.getInventory().setSelectedSlot(slot);
        }
        hold(client, false);
        BlockPos feet = client.player.blockPosition();
        client.player.setPos(feet.getX() + 0.5, client.player.getY(), feet.getZ() + 0.5);
        if (AutoBuilderController.get().isSilentRotations()) {
            dev.kuro.client.util.RotationHandler.setSilent(client.player.getYRot(), 90.0f);
        } else {
            client.player.setXRot(90.0f);
        }
    }

    public void stopTowering() {
        towering = false;
        towerTicks = 0;
        towerPlacedCount = 0;
        currentTowerPos = null;
        towerPlacedThisJump = false;
        towerJumpAttempts = 0;
        steering.jump = false;
        steering.sneak = false;
        Minecraft client = Minecraft.getInstance();
        if (savedTowerSlot >= 0 && client != null && client.player != null) {
            client.player.getInventory().setSelectedSlot(savedTowerSlot);
            savedTowerSlot = -1;
        }
    }

    public boolean hasPlacedTowerBlocks() {
        return !placedTowerBlocks.isEmpty();
    }

    public List<BlockPos> getPlacedTowerBlocks() {
        return Collections.unmodifiableList(placedTowerBlocks);
    }

    public BlockPos popHighestTowerBlock(Minecraft client) {
        if (client == null || client.level == null) return null;
        placedTowerBlocks.removeIf(p -> MaterialTracker.isLoaded(client.level, p) && client.level.getBlockState(p).isAir());
        if (placedTowerBlocks.isEmpty()) return null;
        BlockPos highest = null;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos p : placedTowerBlocks) {
            if (p.getY() > maxY && !client.level.getBlockState(p).isAir()) {
                maxY = p.getY();
                highest = p;
            }
        }
        return highest;
    }

    public void removeTowerBlock(BlockPos pos) {
        placedTowerBlocks.remove(pos);
    }

    public void clearPlacedTowerBlocks() {
        placedTowerBlocks.clear();
    }

    public void setBridgePlacedCallback(Consumer<BlockPos> callback) { this.bridgePlacedCallback = callback; }
    public void setInSchematicPredicate(Predicate<BlockPos> predicate) { this.inSchematicPredicate = predicate; }

    private Map<BuilderPathfinder.Cell, Double> activePenalties() {
        long now = System.currentTimeMillis();
        blockedCells.entrySet().removeIf(entry -> now > entry.getValue());
        if (blockedCells.isEmpty()) return Map.of();
        Map<BuilderPathfinder.Cell, Double> penalties = new HashMap<>();
        for (BuilderPathfinder.Cell cell : blockedCells.keySet()) {
            penalties.put(cell, 2000.0);
        }
        return penalties;
    }

    /** Find a real standing position within mining reach without building more temporary supports. */
    public Result moveOffSupport(Minecraft client, BlockPos support) {
        cleanupSupport = support;
        try {
            Result result = moveTo(client, support, null);
            if (result == Result.ARRIVED && !client.player.onGround()) return Result.MOVING;
            return result;
        } finally {
            cleanupSupport = null;
        }
    }

    public Result moveTo(Minecraft client, BlockPos destination) {
        return moveTo(client, destination, null);
    }

    public Result moveTo(Minecraft client, BlockPos destination, BlockState desired) {
        if (client.level == null || client.player == null) return Result.NO_PATH;
        if (supportConfirmation.pending()) {
            pollSupport(client);
            if (supportConfirmation.pending()) return Result.MOVING;
        }
        if (supportFailed) { supportFailed = false; release(); return Result.STUCK; }
        if (!destination.equals(target)) { target = destination.immutable(); rejectedGoals.clear(); replansForTarget = 0; towerAttempts = 0; replan(); stopTowering(); }
        if (towering) {
            return tickTower(client, destination, desired);
        }
        BuilderPathfinder.Cell feet = cell(client.player.blockPosition());
        if (!isSupportUsable(client.player.blockPosition().below())) { release(); return Result.STUCK; }
        if (goal(client, feet, destination, desired)) { release(); return Result.ARRIVED; }
        if (route.isEmpty() || index >= route.size()) {
            BuilderTerrain.World world = world(client);
            boolean canBridge = cleanupSupport == null && bridgingAllowed && hasBridgeMaterial(client);
            BuilderPathfinder.Terrain terrain = createTerrain(world, canBridge, client);
            long searchStart = System.nanoTime();
            pathSearches++;
            route = BuilderPathfinder.find(feet, cell(destination), terrain, at -> goal(client, at, destination, desired), 1500, 96, activePenalties());
            pathNanos += System.nanoTime() - searchStart;
            index = 0;
            if (route.isEmpty()) {
                if (cleanupSupport == null && towerAllowed && canTowerFrom(client)) {
                    startTowering(client);
                    return tickTower(client, destination, desired);
                }
                release();
                return Result.NO_PATH;
            }
        }
        if (route.isEmpty() || index >= route.size()) {
            release();
            return Result.NO_PATH;
        }
        hold(client, false);
        if (route.isEmpty() || index >= route.size()) {
            release();
            return Result.NO_PATH;
        }
        BuilderPathfinder.Cell next = route.get(index);
        Vec3 aim = new Vec3(next.x()+.5, next.y()+.5, next.z()+.5);
        BlockPos nextPos = new BlockPos(next.x(), next.y(), next.z());
        BlockPos floorPos = nextPos.below();
        boolean inWater = client.player.isInWater() || client.level.getFluidState(client.player.blockPosition()).is(net.minecraft.tags.FluidTags.WATER);
        boolean nextIsWater = client.level.getFluidState(nextPos).is(net.minecraft.tags.FluidTags.WATER) || client.level.getBlockState(nextPos).is(Blocks.WATER);

        double hDistSq = (aim.x - client.player.getX()) * (aim.x - client.player.getX())
                + (aim.z - client.player.getZ()) * (aim.z - client.player.getZ());

        boolean arrivedAtWaypoint;
        if (inWater) {
            if (!nextIsWater) {
                // Transitioning out of water onto land: reached once close horizontally and either grounded or mounted ledge
                arrivedAtWaypoint = (hDistSq < 0.45 && client.player.getY() >= next.y() - 0.3)
                        || (hDistSq < 0.25 && client.player.onGround());
            } else {
                double yDist = Math.abs((next.y() + 0.4) - client.player.getEyePosition().y);
                arrivedAtWaypoint = (hDistSq < 0.35 && yDist < 0.9);
            }
        } else {
            boolean inSameColumn = client.player.getBlockX() == next.x() && client.player.getBlockZ() == next.z();
            double threshold = smoothRoutesAllowed ? 0.64 : 0.45;
            arrivedAtWaypoint = (inSameColumn && client.player.getY() >= next.y() - 0.35 && client.player.getY() <= next.y() + 1.25)
                    || client.player.position().distanceToSqr(aim) < threshold
                    || (hDistSq < threshold && client.player.getY() >= next.y() - 0.35);

            // Look-ahead corner cutting: if waypoint + 1 is directly walkable, advance early to maintain momentum
            if (!arrivedAtWaypoint && index + 1 < route.size()) {
                BuilderPathfinder.Cell secondNext = route.get(index + 1);
                if (Math.abs(secondNext.y() - next.y()) <= 1 && client.player.getY() >= next.y() - 0.35) {
                    if (hDistSq < 1.2 && isLineWalkable(client, client.player.blockPosition(), new BlockPos(secondNext.x(), secondNext.y(), secondNext.z()))) {
                        arrivedAtWaypoint = true;
                    }
                }
            }
        }

        if (arrivedAtWaypoint) {
            index++;
            if (index >= route.size()) { replan(); release(); return Result.ARRIVED; }
            next = route.get(index); aim = new Vec3(next.x()+.5, next.y()+.5, next.z()+.5);
            nextPos = new BlockPos(next.x(), next.y(), next.z());
            floorPos = nextPos.below();
            nextIsWater = client.level.getFluidState(nextPos).is(net.minecraft.tags.FluidTags.WATER) || client.level.getBlockState(nextPos).is(Blocks.WATER);
        }

        Vec3 targetAim = aim;
        if (hDistSq < 1.0 && index + 1 < route.size()) {
            BuilderPathfinder.Cell future = route.get(index + 1);
            targetAim = new Vec3(future.x() + .5, future.y() + .5, future.z() + .5);
        }
        Vec3 delta = targetAim.subtract(client.player.position());
        float walkYaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));

        if (inWater) {
            boolean swimming = client.player.isSwimming();
            double hDist = Math.hypot(delta.x, delta.z);
            double eyeY = client.player.getEyePosition().y;
            double targetY = next.y() + 0.4;
            double vDiff = targetY - eyeY;

            // Compensate for water flow currents
            Vec3 flow = client.level.getFluidState(client.player.blockPosition()).getFlow(client.level, client.player.blockPosition());
            float swimYaw = walkYaw;
            if (flow.lengthSqr() > 0.002) {
                Vec3 forwardDir = new Vec3(delta.x, 0, delta.z).normalize();
                Vec3 compensated = forwardDir.subtract(flow.scale(0.75));
                if (compensated.lengthSqr() > 0.01) {
                    swimYaw = (float) Math.toDegrees(Math.atan2(-compensated.x, compensated.z));
                }
            }

            float targetPitch;
            if (!nextIsWater && next.y() >= client.player.getY() - 0.5) {
                // Exiting water onto land / ledge: vault out by holding jump, and pitch up
                steering.jump = true;
                steering.sneak = false;
                steering.forward = true;
                steering.sprint = swimming;
                targetPitch = -20f;
            } else if (vDiff > 0.3) {
                // Swimming up: space key (jump) + pitch up towards aim
                steering.jump = true;
                steering.sneak = false;
                steering.forward = true;
                steering.sprint = swimming;
                targetPitch = (float) Math.toDegrees(-Math.atan2(vDiff, Math.max(0.1, hDist)));
                targetPitch = Math.max(-65f, Math.min(65f, targetPitch));
            } else if (vDiff < -0.3) {
                // Swimming down: pitch down + sprint forward only if swimming
                steering.jump = false;
                steering.sneak = false;
                steering.forward = true;
                steering.sprint = swimming;
                targetPitch = (float) Math.toDegrees(-Math.atan2(vDiff, Math.max(0.1, hDist)));
                targetPitch = Math.max(-65f, Math.min(65f, targetPitch));
            } else {
                // Swimming level
                steering.jump = false;
                steering.sneak = false;
                steering.forward = true;
                steering.sprint = swimming;
                targetPitch = (float) Math.toDegrees(-Math.atan2(vDiff, Math.max(0.1, hDist)));
                targetPitch = Math.max(-20f, Math.min(20f, targetPitch));
            }
            rotateSmoothly(client.player, swimYaw, targetPitch, 30.0f);
        } else {
            boolean jumpStep = next.y() > client.player.getY() + .25 && client.player.onGround()
                    && (hDistSq < 1.6 || client.player.horizontalCollision);
            boolean canSprint = sprintAllowed && client.player.getFoodData().getFoodLevel() > 6;

            BlockPos headAbove = client.player.blockPosition().above(2);
            boolean lowCeiling = !client.level.getBlockState(headAbove).getCollisionShape(client.level, headAbove).isEmpty();

            float yawDiff = Math.abs(Mth.wrapDegrees(walkYaw - client.player.getYRot()));
            boolean facingRunway = yawDiff < 45.0f;

            steering.sneak = false;
            steering.forward = true;

            if (jumpStep) {
                steering.jump = true;
                steering.sprint = canSprint && facingRunway;
            } else if (sprintJumpAllowed && canSprint && facingRunway && !lowCeiling && client.player.onGround()
                    && !client.player.horizontalCollision && isStraightRunway(route, index, 2)) {
                steering.jump = true;
                steering.sprint = true;
            } else {
                steering.jump = false;
                steering.sprint = canSprint && facingRunway;
            }

            if (steering.sprint && facingRunway) {
                client.player.setSprinting(true);
            }
            rotateSmoothly(client.player, walkYaw, 0.0f, 65.0f);
        }

        if (index == lastWaypointIndex) {
            waypointTicks++;
        } else {
            lastWaypointIndex = index;
            waypointTicks = 0;
            stalled = 0;
        }

        double hDistMovedSq = 0;
        if (lastPosition != null) {
            double dx = client.player.getX() - lastPosition.x;
            double dz = client.player.getZ() - lastPosition.z;
            hDistMovedSq = dx * dx + dz * dz;
        }
        lastPosition = client.player.position();

        if (hDistMovedSq < 0.0009) {
            stalled++;
        } else if (stalled > 0) {
            stalled--;
        }

        // Anti-stall unsticking: if truly stalled (no horizontal progress for >15 ticks), try jumping
        if (stalled > 15) {
            if (inWater || client.player.onGround()) {
                steering.jump = true;
            }
        }

        steering.tick();

        int maxStall = inWater ? 50 : 35;
        if (stalled > maxStall || waypointTicks > (inWater ? 120 : 80)) {
            if (cleanupSupport == null && towerAllowed && !inWater && canTowerFrom(client)) {
                startTowering(client);
                return tickTower(client, destination, desired);
            }
            rejectCurrent(client);
            return Result.STUCK;
        }
        return Result.MOVING;
    }

    public static boolean isRedstoneOrComponent(BlockState state) {
        if (state == null || state.isAir()) return false;
        Block b = state.getBlock();
        return b instanceof RedStoneWireBlock || b instanceof DiodeBlock || b instanceof RedstoneTorchBlock
                || b instanceof BaseRailBlock || b instanceof LeverBlock || b instanceof ButtonBlock
                || b instanceof PressurePlateBlock || b instanceof TripWireBlock || b instanceof TripWireHookBlock
                || b instanceof TorchBlock || b instanceof CarpetBlock;
    }

    public static boolean isSolidBridgeSupport(Minecraft client, BlockPos pos) {
        if (client.level == null || !MaterialTracker.isLoaded(client.level, pos)) return false;
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced() || isRedstoneOrComponent(state)) return false;
        return !state.getCollisionShape(client.level, pos).isEmpty();
    }

    public static void rotateSmoothly(LocalPlayer player, float targetYaw, float targetPitch, float maxTurnSpeed) {
        if (player == null) return;
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        float yawDiff = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;

        // Cap turn speed to responsive but smooth speeds (up to 80 deg/tick yaw, 65 deg/tick pitch) for agile navigation
        float effectiveMaxYaw = Math.min(maxTurnSpeed, 80.0f);
        float effectiveMaxPitch = Math.min(maxTurnSpeed, 65.0f);

        // Compute mouse sensitivity GCD step to match vanilla mouse movement patterns exactly
        Minecraft client = Minecraft.getInstance();
        double gcd = 0.15;
        if (client != null && client.options != null) {
            double sens = client.options.sensitivity().get();
            double f = sens * 0.6 + 0.2;
            gcd = f * f * f * 8.0 * 0.15;
            if (gcd < 0.0001) gcd = 0.15;
        }

        // Quantization must not overshoot the target or leave a permanent sub-step error.
        // A stable final correction also prevents alternating yaw/pitch on adjacent ticks.
        float stepYaw = rotationStep(yawDiff,effectiveMaxYaw,gcd);
        float stepPitch = rotationStep(pitchDiff,effectiveMaxPitch,gcd);
        player.setYRot(currentYaw + stepYaw);
        player.setXRot(Mth.clamp(currentPitch + stepPitch,-90f,90f));
    }

    static float rotationStep(float error,float limit,double quantum) {
        float distance = Math.abs(error);
        if (distance <= quantum) return error;
        // Continuous easing avoids the old jumps in speed at 1.5 and 6 degrees.
        float amount = Math.min(limit,distance*.65f);
        float quantized = (float)(Math.floor(amount/quantum)*quantum);
        return Math.copySign(Math.min(distance,Math.max((float)quantum,quantized)),error);
    }

    private static boolean isFloorMissing(Minecraft client, BlockPos pos) {
        if (!MaterialTracker.isLoaded(client.level, pos)) return false;
        BlockState state = client.level.getBlockState(pos);
        return state.canBeReplaced() || state.getCollisionShape(client.level, pos).isEmpty();
    }

    public static boolean isDirtItem(Item item) {
        return item == Items.DIRT || item == Items.COARSE_DIRT || item == Items.ROOTED_DIRT
                || item == Items.MUD || item == Items.DIRT_PATH || item == Items.PODZOL || item == Items.GRASS_BLOCK;
    }

    public static boolean isForbiddenBridgeOrScaffold(Item item) {
        if (item == null || !(item instanceof BlockItem bi)) return true;
        Block b = bi.getBlock();

        // Containers, inventories, and workstations (never bridge/tower/scaffold with hoppers or containers!)
        if (b instanceof HopperBlock
                || b instanceof ChestBlock
                || b instanceof EnderChestBlock
                || b instanceof ShulkerBoxBlock
                || b instanceof BarrelBlock
                || b instanceof DispenserBlock
                || b instanceof DropperBlock
                || b instanceof CrafterBlock
                || b instanceof AbstractFurnaceBlock
                || b instanceof BrewingStandBlock
                || b instanceof LecternBlock
                || b instanceof ChiseledBookShelfBlock
                || b instanceof JukeboxBlock
                || b instanceof CraftingTableBlock
                || b instanceof LoomBlock
                || b instanceof CartographyTableBlock
                || b instanceof GrindstoneBlock
                || b instanceof StonecutterBlock
                || b instanceof SmithingTableBlock) {
            return true;
        }

        // Redstone components, rails, triggers, and mechanics
        if (b instanceof DiodeBlock
                || b instanceof RedStoneWireBlock
                || b instanceof ObserverBlock
                || b instanceof RedstoneLampBlock
                || b instanceof TargetBlock
                || b instanceof DaylightDetectorBlock
                || b instanceof NoteBlock
                || b instanceof RedstoneTorchBlock
                || b instanceof BaseRailBlock
                || b instanceof LeverBlock
                || b instanceof ButtonBlock
                || b instanceof PressurePlateBlock
                || b instanceof TripWireBlock
                || b instanceof TripWireHookBlock
                || b instanceof SculkSensorBlock
                || b instanceof SculkCatalystBlock
                || b instanceof SculkShriekerBlock) {
            return true;
        }

        // Interactive, decorative, non-solid, fragile, or utility blocks
        if (b instanceof AnvilBlock
                || b instanceof BeaconBlock
                || b instanceof ConduitBlock
                || b instanceof EnchantingTableBlock
                || b instanceof RespawnAnchorBlock
                || b instanceof BedBlock
                || b instanceof DoorBlock
                || b instanceof TrapDoorBlock
                || b instanceof FenceGateBlock
                || b instanceof TorchBlock
                || b instanceof LanternBlock
                || b instanceof CampfireBlock
                || b instanceof SignBlock
                || b instanceof BannerBlock
                || b instanceof CarpetBlock
                || b instanceof SkullBlock
                || b instanceof CakeBlock
                || b instanceof FlowerPotBlock
                || b instanceof BellBlock) {
            return true;
        }

        // Gravity blocks that fall when placed
        if (b instanceof FallingBlock) {
            return true;
        }

        // Valuable, dangerous, technical, or special blocks
        if (item == Items.PISTON || item == Items.STICKY_PISTON
                || item == Items.REDSTONE_BLOCK || item == Items.TNT
                || item == Items.SLIME_BLOCK || item == Items.HONEY_BLOCK
                || item == Items.COMMAND_BLOCK || item == Items.CHAIN_COMMAND_BLOCK || item == Items.REPEATING_COMMAND_BLOCK
                || item == Items.STRUCTURE_BLOCK || item == Items.STRUCTURE_VOID || item == Items.JIGSAW
                || item == Items.BARRIER || item == Items.LIGHT || item == Items.SPAWNER
                || item == Items.NETHERITE_BLOCK || item == Items.DIAMOND_BLOCK || item == Items.EMERALD_BLOCK
                || item == Items.GOLD_BLOCK || item == Items.IRON_BLOCK || item == Items.LAPIS_BLOCK
                || item == Items.COPPER_BLOCK.weathering().unaffected() || item == Items.RAW_IRON_BLOCK || item == Items.RAW_GOLD_BLOCK
                || item == Items.RAW_COPPER_BLOCK || item == Items.OBSIDIAN || item == Items.CRYING_OBSIDIAN
                || item == Items.ANCIENT_DEBRIS) {
            return true;
        }

        return false;
    }

    public static int findBridgeMaterialSlot(Minecraft client) {
        if (client.player == null) return -1;
        if (client.player.getAbilities().instabuild && client.gameMode != null && client.gameMode.getPlayerMode().isCreative()) {
            int selected = client.player.getInventory().getSelectedSlot();
            ItemStack current = client.player.getInventory().getItem(selected);
            if (isDirtItem(current.getItem()) || current.is(Items.COBBLESTONE)) return selected;
            if (!current.isEmpty() && !isForbiddenBridgeOrScaffold(current.getItem())) return selected;

            for (int i = 0; i < 9; i++) {
                ItemStack s = client.player.getInventory().getItem(i);
                if (isDirtItem(s.getItem()) || s.is(Items.COBBLESTONE)) return i;
            }
            for (int i = 0; i < 9; i++) {
                ItemStack s = client.player.getInventory().getItem(i);
                if (!s.isEmpty() && !isForbiddenBridgeOrScaffold(s.getItem())) return i;
            }
            ItemStack stack = new ItemStack(Items.DIRT);
            client.player.getInventory().setItem(selected, stack);
            client.gameMode.handleCreativeModeItemAdd(stack, 36 + selected);
            return selected;
        }

        // 1. Dirt prioritized across all 36 slots
        for (int i = 0; i < 9; i++) {
            if (isDirtItem(client.player.getInventory().getItem(i).getItem())) return i;
        }
        for (int i = 9; i < 36; i++) {
            if (isDirtItem(client.player.getInventory().getItem(i).getItem())) {
                AutoBuilderController.get().swapMaterialToHotbar(client, i);
                return client.player.getInventory().getSelectedSlot();
            }
        }

        // 2. Common cheap solid building blocks across all 36 slots
        for (Item pref : List.of(Items.SCAFFOLDING, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.NETHERRACK, Items.OAK_PLANKS)) {
            for (int i = 0; i < 9; i++) {
                if (client.player.getInventory().getItem(i).is(pref)) return i;
            }
        }
        for (Item pref : List.of(Items.SCAFFOLDING, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.NETHERRACK, Items.OAK_PLANKS)) {
            for (int i = 9; i < 36; i++) {
                if (client.player.getInventory().getItem(i).is(pref)) {
                    AutoBuilderController.get().swapMaterialToHotbar(client, i);
                    return client.player.getInventory().getSelectedSlot();
                }
            }
        }

        // 3. Fallback: any non-forbidden solid block across all 36 slots
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && !isForbiddenBridgeOrScaffold(stack.getItem())) return i;
        }
        for (int i = 9; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && !isForbiddenBridgeOrScaffold(stack.getItem())) {
                AutoBuilderController.get().swapMaterialToHotbar(client, i);
                return client.player.getInventory().getSelectedSlot();
            }
        }
        return -1;
    }

    public static boolean hasBridgeMaterial(Minecraft client) {
        if (client.player == null) return false;
        if (client.player.getAbilities().instabuild && client.gameMode != null && client.gameMode.getPlayerMode().isCreative()) return true;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && !isForbiddenBridgeOrScaffold(stack.getItem())) return true;
        }
        return false;
    }

    private BuilderPathfinder.Terrain createTerrain(BuilderTerrain.World world, boolean canBridge, Minecraft client) {
        return new BuilderPathfinder.Terrain() {
            @Override public boolean standable(BuilderPathfinder.Cell at) { return BuilderTerrain.standable(world, at); }
            @Override public boolean headroom(BuilderPathfinder.Cell at) { return BuilderTerrain.headroom(world, at); }
            @Override public boolean water(BuilderPathfinder.Cell at) { return world.water(new BlockPos(at.x(), at.y(), at.z())); }
            @Override public boolean bridgeable(BuilderPathfinder.Cell at, BuilderPathfinder.Cell from) {
                BlockPos atFloor = new BlockPos(at.x(), at.y() - 1, at.z());
                if (isRedstoneOrComponent(client.level.getBlockState(atFloor))) return false;
                if (isRedstoneOrComponent(client.level.getBlockState(atFloor.below()))) return false;
                BlockPos fromFloor = new BlockPos(from.x(), from.y() - 1, from.z());
                if (isRedstoneOrComponent(client.level.getBlockState(fromFloor))) return false;
                return canBridge && isSupportUsable(atFloor) && isSupportUsable(fromFloor)
                        && BuilderTerrain.bridgeable(world, at, from);
            }
        };
    }

    public boolean canTowerFrom(Minecraft client) {
        if (client.player == null || client.level == null) return false;
        if (client.player.isInWater() || client.player.isInLava()) return false;
        if (navigationTicks < towerRetryAfter || towerAttempts >= 2) return false;
        if (!hasTowerMaterial(client)) return false;
        BlockPos feet = client.player.blockPosition();
        if (client.level.isOutsideBuildHeight(feet.above(2))) return false;
        BlockPos ceiling = feet.above(2);
        if (MaterialTracker.isLoaded(client.level, ceiling)) {
            BlockState ceilState = client.level.getBlockState(ceiling);
            if (!ceilState.canBeReplaced() && !ceilState.getCollisionShape(client.level, ceiling).isEmpty()) {
                return false;
            }
        }
        BlockPos below = feet.below();
        if (isRedstoneOrComponent(client.level.getBlockState(below))) return false;
        if (inSchematicPredicate != null && inSchematicPredicate.test(feet)) return false;
        return client.player.onGround() && isSupportUsable(below) && isSolidBridgeSupport(client, below);
    }

    private Result tickTower(Minecraft client, BlockPos destination, BlockState desired) {
        if (!towerAllowed || client.player == null || client.level == null) {
            stopTowering();
            return Result.STUCK;
        }

        towerTicks++;
        BlockPos feet = client.player.blockPosition();
        BuilderPathfinder.Cell feetCell = cell(feet);

        // Check if arrived at goal / can reach destination from this height
        if (client.player.onGround() && goal(client, feetCell, destination, desired)) {
            stopTowering();
            return Result.ARRIVED;
        }

        // Limit height relative to destination: do not tower higher than destination + 2
        int maxTargetY = destination.getY() + 2;
        if (feet.getY() >= maxTargetY || client.level.isOutsideBuildHeight(feet.above(2))) {
            stopTowering();
            rejectCurrent(client);
            return Result.STUCK;
        }

        // Test if a viable route is unlocked from current height
        if (towerPlacedCount > 0 && client.player.onGround() && feet.getY() != lastTowerRouteY) {
            lastTowerRouteY = feet.getY();
            List<BuilderPathfinder.Cell> unlockedRoute = findRouteFromTower(client, feet, destination, desired);
            if (!unlockedRoute.isEmpty()) {
                route = unlockedRoute;
                index = 0;
                stalled = 0;
                waypointTicks = 0;
                stopTowering();
                return Result.MOVING;
            }
        }

        // Limit maximum tower height or timeout
        if (towerPlacedCount >= MAX_TOWER_BLOCKS || towerTicks > 200) {
            stopTowering();
            rejectCurrent(client);
            return Result.STUCK;
        }

        // Check headroom
        BlockPos ceiling = feet.above(2);
        if (MaterialTracker.isLoaded(client.level, ceiling)) {
            BlockState ceilState = client.level.getBlockState(ceiling);
            if (!ceilState.canBeReplaced() && !ceilState.getCollisionShape(client.level, ceiling).isEmpty()) {
                stopTowering();
                rejectCurrent(client);
                return Result.STUCK;
            }
        }

        int slot = findTowerMaterialSlot(client);
        if (slot < 0) {
            stopTowering();
            rejectCurrent(client);
            return Result.STUCK;
        }

        // Ensure controls are steered without wiping tower state
        if (owner != client.player) {
            if (owner != null && owner.input == steering && saved != null) {
                owner.input = saved;
            }
            owner = client.player;
            if (owner.input != steering) {
                saved = owner.input;
                owner.input = steering;
            }
        }

        // Select tower material
        if (slot >= 0 && slot < 9) {
            client.player.getInventory().setSelectedSlot(slot);
        }

        // Look straight down at feet
        if (AutoBuilderController.get().isSilentRotations()) {
            dev.kuro.client.util.RotationHandler.setSilent(client.player.getYRot(), 90.0f);
        } else {
            client.player.setXRot(90.0f);
        }

        // Keep horizontal movement zeroed so player stays centered on tower
        steering.forward = false;
        steering.sprint = false;
        Vec3 vel = client.player.getDeltaMovement();
        client.player.setDeltaMovement(0, vel.y, 0);

        if (client.player.onGround()) {
            if (towerPlacedThisJump) {
                // Landed on newly placed tower block
                towerPlacedThisJump = false;
                currentTowerPos = null;
                towerJumpAttempts = 0;
            } else if (currentTowerPos != null) {
                // Landed without placing
                towerJumpAttempts++;
                if (towerJumpAttempts >= 3) {
                    stopTowering();
                    rejectCurrent(client);
                    return Result.STUCK;
                }
                currentTowerPos = null;
            }

            // Start jump from ground
            currentTowerPos = feet;
            steering.jump = true;
            steering.sneak = false;
        } else {
            // In air
            steering.jump = false;
            if (currentTowerPos != null && !towerPlacedThisJump) {
                double playerY = client.player.getY();
                if (playerY >= currentTowerPos.getY() + 1.0) {
                    BlockState stateAtFeet = client.level.getBlockState(currentTowerPos);
                    if (stateAtFeet.isAir() || stateAtFeet.canBeReplaced()) {
                        BlockPos ground = currentTowerPos.below();
                        Vec3 hitVec = new Vec3(currentTowerPos.getX() + 0.5, currentTowerPos.getY(), currentTowerPos.getZ() + 0.5);
                        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, ground, false);
                        var placeResult = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
                        if (placeResult.consumesAction()) {
                            client.player.swing(InteractionHand.MAIN_HAND);
                            towerPlacedCount++;
                            towerPlacedThisJump = true;
                            if (!placedTowerBlocks.contains(currentTowerPos)) {
                                placedTowerBlocks.add(currentTowerPos);
                            }
                        }
                    } else {
                        // Already placed
                        towerPlacedThisJump = true;
                        if (!placedTowerBlocks.contains(currentTowerPos)) {
                            placedTowerBlocks.add(currentTowerPos);
                        }
                    }
                }
            }
        }

        steering.tick();
        return Result.MOVING;
    }

    public static int findTowerMaterialSlot(Minecraft client) {
        if (client.player == null) return -1;
        if (client.player.getAbilities().instabuild && client.gameMode != null && client.gameMode.getPlayerMode().isCreative()) {
            int selected = client.player.getInventory().getSelectedSlot();
            ItemStack current = client.player.getInventory().getItem(selected);
            if (isDirtItem(current.getItem())) return selected;

            for (int i = 0; i < 9; i++) {
                if (isDirtItem(client.player.getInventory().getItem(i).getItem())) return i;
            }
            ItemStack stack = new ItemStack(Items.DIRT);
            client.player.getInventory().setItem(selected, stack);
            client.gameMode.handleCreativeModeItemAdd(stack, 36 + selected);
            return selected;
        }

        // 1. Dirt prioritized across all 36 slots
        for (int i = 0; i < 9; i++) {
            if (isDirtItem(client.player.getInventory().getItem(i).getItem())) return i;
        }
        for (int i = 9; i < 36; i++) {
            if (isDirtItem(client.player.getInventory().getItem(i).getItem())) {
                AutoBuilderController.get().swapMaterialToHotbar(client, i);
                return client.player.getInventory().getSelectedSlot();
            }
        }

        // 2. Common cheap solid blocks across all 36 slots
        for (Item pref : List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.NETHERRACK, Items.OAK_PLANKS, Items.SCAFFOLDING)) {
            for (int i = 0; i < 9; i++) {
                if (client.player.getInventory().getItem(i).is(pref)) return i;
            }
        }
        for (Item pref : List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.NETHERRACK, Items.OAK_PLANKS, Items.SCAFFOLDING)) {
            for (int i = 9; i < 36; i++) {
                if (client.player.getInventory().getItem(i).is(pref)) {
                    AutoBuilderController.get().swapMaterialToHotbar(client, i);
                    return client.player.getInventory().getSelectedSlot();
                }
            }
        }

        // 3. Fallback: any non-forbidden solid block across all 36 slots
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && !isForbiddenBridgeOrScaffold(stack.getItem())) return i;
        }
        for (int i = 9; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && !isForbiddenBridgeOrScaffold(stack.getItem())) {
                AutoBuilderController.get().swapMaterialToHotbar(client, i);
                return client.player.getInventory().getSelectedSlot();
            }
        }
        return -1;
    }

    public static boolean hasTowerMaterial(Minecraft client) {
        if (client.player == null) return false;
        if (client.player.getAbilities().instabuild && client.gameMode != null && client.gameMode.getPlayerMode().isCreative()) return true;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && !isForbiddenBridgeOrScaffold(stack.getItem())) return true;
        }
        return false;
    }

    /**
     * Cells of straight, level run-up that have to remain before sprinting is worth its cost.
     *
     * <p>Sprinting is 30% faster in vanilla, but the player keeps momentum while turning, so a corner
     * or a drop taken at that speed can step off a one-block walkway. Sprints are therefore limited to
     * a straight, level stretch, and the look-ahead stops the sprint several cells before a turn or a
     * step instead of at the last moment. Vanilla still applies its own rules: a collision, water,
     * blindness or an empty hunger bar stop the sprint on their own.
     */
    boolean sprintable(List<BuilderPathfinder.Cell> route, int index) {
        if (!sprintAllowed) return false;
        if (route == null || route.isEmpty() || index < 0) return false;
        return route.size() - index >= 1;
    }

    private static boolean isStraightRunway(List<BuilderPathfinder.Cell> route, int index, int length) {
        if (route == null || index < 0 || route.size() - index <= length) return false;
        BuilderPathfinder.Cell cur = route.get(index);
        BuilderPathfinder.Cell next = route.get(index + 1);
        int dx = next.x() - cur.x();
        int dz = next.z() - cur.z();
        if (dx == 0 && dz == 0) return false;
        for (int i = index + 1; i <= index + length && i < route.size(); i++) {
            BuilderPathfinder.Cell from = route.get(i - 1), to = route.get(i);
            if (to.y() != from.y() || to.x() - from.x() != dx || to.z() - from.z() != dz) return false;
        }
        return true;
    }

    private boolean isLineWalkable(Minecraft client, BlockPos from, BlockPos to) {
        if (client.level == null) return false;
        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());
        int y = to.getY();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos floor = new BlockPos(x, y - 1, z);
                BlockPos body = new BlockPos(x, y, z);
                BlockPos head = new BlockPos(x, y + 1, z);
                if (!isSolidBridgeSupport(client, floor)) return false;
                BlockState bodyState = client.level.getBlockState(body);
                BlockState headState = client.level.getBlockState(head);
                if (!bodyState.getCollisionShape(client.level, body).isEmpty()) return false;
                if (!headState.getCollisionShape(client.level, head).isEmpty()) return false;
            }
        }
        return true;
    }

    static boolean isHopperGoalValid(Vec3 eye, BlockPos destination, net.minecraft.core.Direction facing) {
        if (facing == net.minecraft.core.Direction.DOWN) {
            // To face DOWN, a hopper MUST be clicked on the UP face of destination.below() (or DOWN face of destination.above()).
            // Side faces never place a down-facing hopper. The top face must be viewed from above.
            return eye.y > destination.getY() + 0.15;
        } else {
            // For horizontal facing, player clicks the opposite face of destination.relative(facing).
            // Player eye cannot be behind the attached block.
            double dot = (eye.x - (destination.getX() + 0.5)) * facing.getStepX() + (eye.z - (destination.getZ() + 0.5)) * facing.getStepZ();
            return dot <= 0.3;
        }
    }

    static boolean isDirectionalGoalValid(Vec3 eye, BlockPos destination, BlockState desired) {
        if (desired == null) return true;
        Block block = desired.getBlock();
        if (block instanceof HopperBlock) {
            net.minecraft.core.Direction facing = desired.getValue(HopperBlock.FACING);
            return isHopperGoalValid(eye, destination, facing);
        }
        if (block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                || block instanceof DispenserBlock
                || block instanceof DropperBlock) {
            if (desired.hasProperty(BlockStateProperties.FACING)) {
                net.minecraft.core.Direction facing = desired.getValue(BlockStateProperties.FACING);
                if (facing == net.minecraft.core.Direction.DOWN) {
                    return eye.y <= destination.getY() + 1.2;
                } else if (facing == net.minecraft.core.Direction.UP) {
                    return eye.y >= destination.getY() - 0.2;
                } else {
                    double dot = (eye.x - (destination.getX() + 0.5)) * facing.getStepX() + (eye.z - (destination.getZ() + 0.5)) * facing.getStepZ();
                    return dot >= -0.3;
                }
            }
        }
        if (block instanceof ObserverBlock) {
            if (desired.hasProperty(BlockStateProperties.FACING)) {
                net.minecraft.core.Direction facing = desired.getValue(BlockStateProperties.FACING);
                if (facing == net.minecraft.core.Direction.DOWN) {
                    return eye.y >= destination.getY() - 0.2;
                } else if (facing == net.minecraft.core.Direction.UP) {
                    return eye.y <= destination.getY() + 1.2;
                } else {
                    double dot = (eye.x - (destination.getX() + 0.5)) * facing.getStepX() + (eye.z - (destination.getZ() + 0.5)) * facing.getStepZ();
                    return dot <= 0.3;
                }
            }
        }
        if (block instanceof RepeaterBlock || block instanceof ComparatorBlock) {
            if (desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                net.minecraft.core.Direction facing = desired.getValue(BlockStateProperties.HORIZONTAL_FACING);
                double dot = (eye.x - (destination.getX() + 0.5)) * facing.getStepX() + (eye.z - (destination.getZ() + 0.5)) * facing.getStepZ();
                return dot >= -0.3;
            }
        }
        if (block instanceof StairBlock) {
            if (desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                net.minecraft.core.Direction facing = desired.getValue(BlockStateProperties.HORIZONTAL_FACING);
                double dot = (eye.x - (destination.getX() + 0.5)) * facing.getStepX() + (eye.z - (destination.getZ() + 0.5)) * facing.getStepZ();
                return dot <= 0.4;
            }
        }
        if (block instanceof ChestBlock) {
            if (desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                net.minecraft.core.Direction facing = desired.getValue(BlockStateProperties.HORIZONTAL_FACING);
                double dot = (eye.x - (destination.getX() + 0.5)) * facing.getStepX() + (eye.z - (destination.getZ() + 0.5)) * facing.getStepZ();
                return dot >= -0.4;
            }
        }
        return true;
    }

    private List<BuilderPathfinder.Cell> findRouteFromTower(Minecraft client, BlockPos fromPos, BlockPos destination, BlockState desired) {
        if (client == null || client.level == null || client.player == null) return List.of();
        BuilderTerrain.World world = world(client);
        boolean canBridge = cleanupSupport == null && bridgingAllowed && hasBridgeMaterial(client);
        BuilderPathfinder.Terrain terrain = createTerrain(world, canBridge, client);
        long searchStart = System.nanoTime();
        pathSearches++;
        var found = BuilderPathfinder.find(
                cell(fromPos), cell(destination), terrain,
                at -> goal(client, at, destination, desired), 800, 48, activePenalties());
        pathNanos += System.nanoTime() - searchStart;
        return found;
    }

    private boolean goal(Minecraft client, BuilderPathfinder.Cell at, BlockPos destination, BlockState desired) {
        if (rejectedGoals.contains(at)) return false;
        BlockPos feet = new BlockPos(at.x(), at.y(), at.z());
        double offset = lowComponentHeight(client.level.getBlockState(feet), client.level, feet);
        Vec3 eye = new Vec3(at.x()+.5, at.y()+offset+client.player.getEyeHeight(), at.z()+.5);
        if (cleanupSupport != null) {
            if (feet.equals(client.player.blockPosition())
                    && client.player.getBoundingBox().move(0,-.1,0).intersects(new AABB(cleanupSupport))) return false;
            if (!BuilderTerrain.standable(world(client), at) || feet.below().equals(cleanupSupport)) return false;
            AABB body = new AABB(at.x()+.2, at.y()+offset-.1, at.z()+.2,
                    at.x()+.8, at.y()+offset+1.8, at.z()+.8);
            if (body.intersects(new AABB(cleanupSupport))) return false;
            double reach = Math.min(4.25, client.player.blockInteractionRange()-.25);
            if (eye.distanceToSqr(Vec3.atCenterOf(cleanupSupport)) > reach*reach) return false;
            return PlacementPlanner.visibleMiningHit(client.level,cleanupSupport,eye,reach,
                    client.player.getYRot(),client.player.getXRot(),net.minecraft.world.phys.shapes.CollisionContext.of(client.player)) != null;
        }
        // A block cannot be placed into the walking player's body.
        if (at.x()==destination.getX() && at.z()==destination.getZ()
                && at.y()<=destination.getY() && at.y()+1.8>destination.getY()) return false;
        if (client.player != null && at.x() == client.player.getBlockX() && at.y() == client.player.getBlockY() && at.z() == client.player.getBlockZ()) {
            if (desired != null && client.level != null && !desired.getCollisionShape(client.level, destination).isEmpty()) {
                if (client.player.getBoundingBox().intersects(new AABB(destination))) return false;
            }
        }
        // Direction-specific blocks (Hoppers, Pistons, Dispensers, Observers, Repeaters) have strict face/position requirements.
        if (!isDirectionalGoalValid(eye, destination, desired)) return false;
        if (client.level.getBlockState(destination).canBeReplaced() && eye.y<=destination.getY()+.01) {
            boolean alternativeFace=false;
            for (var direction:net.minecraft.core.Direction.values()) {
                if (direction==net.minecraft.core.Direction.DOWN) continue;
                BlockPos neighbor=destination.relative(direction);
                if (!MaterialTracker.isLoaded(client.level,neighbor)) continue;
                var state=client.level.getBlockState(neighbor);
                if (!state.canBeReplaced() && !state.getCollisionShape(client.level,neighbor).isEmpty()) { alternativeFace=true; break; }
            }
            if (!alternativeFace) return false;
        }
        double reach = Math.min(AutoBuilderController.get().getReach(), client.player.blockInteractionRange()) - .25;
        if (eye.distanceToSqr(Vec3.atCenterOf(destination)) > reach * reach) return false;
        BlockHitResult hit = client.level.clip(new ClipContext(eye, Vec3.atCenterOf(destination),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(destination);
    }
    /**
     * The terrain facts {@link BuilderTerrain} decides on, read from the level. Water is a block the walker
     * may swim in; lava, powder snow, fire, a berry bush and a bubble column are not, and a floor has to be
     * a solid block that does not hurt to stand on.
     */
    private BuilderTerrain.World world(Minecraft client) {
        return new BuilderTerrain.World() {
            @Override public boolean loaded(BlockPos pos) { return MaterialTracker.isLoaded(client.level,pos); }
            @Override public boolean known(BlockPos pos) { return isSupportUsable(pos); }
            @Override public boolean open(BlockPos pos) {
                if (!isSupportUsable(pos)) return false;
                var state = client.level.getBlockState(pos);
                if (state.is(Blocks.WATER)) return true;
                if (!client.level.getFluidState(pos).isEmpty()) {
                    if (client.level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) {
                        return state.getCollisionShape(client.level, pos).isEmpty();
                    }
                    return false;
                }
                return !(state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.SWEET_BERRY_BUSH)
                        || state.is(Blocks.POWDER_SNOW));
            }
            @Override public boolean water(BlockPos pos) {
                return client.level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)
                        || client.level.getBlockState(pos).is(Blocks.WATER);
            }
            @Override public boolean floor(BlockPos pos) {
                if (!isSupportUsable(pos)) return false;
                var ground = client.level.getBlockState(pos);
                if (ground.is(Blocks.MAGMA_BLOCK) || ground.is(Blocks.CAMPFIRE) || ground.is(Blocks.SOUL_CAMPFIRE)
                        || ground.is(Blocks.CACTUS) || ground.is(Blocks.POWDER_SNOW)) return false;
                return ground.isCollisionShapeFullBlock(client.level, pos);
            }
            @Override public boolean fits(BlockPos pos) {
                double offset = lowComponentHeight(client.level.getBlockState(pos), client.level, pos);
                return client.level.noCollision(client.player, new AABB(pos.getX()+.2, pos.getY()+offset+.01, pos.getZ()+.2,
                        pos.getX()+.8, pos.getY()+offset+1.8, pos.getZ()+.8));
            }
        };
    }
    /** Low components occupy only their real collision height, never an invented full cube. */
    static double lowComponentHeight(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        if (!isRedstoneOrComponent(state)) return 0;
        var shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) return 0;
        double height = shape.max(Direction.Axis.Y);
        return height <= .5 ? height : 0;
    }

    private static BuilderPathfinder.Cell cell(BlockPos p) { return new BuilderPathfinder.Cell(p.getX(),p.getY(),p.getZ()); }
}
