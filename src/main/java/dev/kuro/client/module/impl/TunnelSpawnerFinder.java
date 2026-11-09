package dev.kuro.client.module.impl;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kuro.client.builder.autobuild.BuilderNavigator;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import org.lwjgl.glfw.GLFW;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.ForcedKeys;
import dev.kuro.client.util.RotationHandler;
import dev.kuro.client.util.SwapLimit;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Tunnel Spawner Finder:
 * Tunnels forward by mining blocks in 2x1 or 3x3 profiles using smooth GCD-matched
 * rotations. In 2x1 mode, deliberately looks down and breaks the lower feet block
 * before breaking the upper head block. Employs fast, corridor-focused A* pathfinding
 * to seamlessly route around lava/liquid hazards and return back to the original straight
 * tunneling line. Eats food when health is low or hunger drops. Continuously scans all
 * chunks within simulation distance for monster spawners, kicking out immediately with
 * "spawner found" upon detection.
 */
public class TunnelSpawnerFinder extends Module {
    private static TunnelSpawnerFinder instance;

    private final Setting.Num tunnelSize = add(new Setting.Num("Size", 0, 0, 1, 1).labelled("2x1", "3x3"));
    private final Setting.Num reach = add(new Setting.Num("Reach", 4.0, 1.0, 5.0, 0.5));
    private final Setting.Bool headOnly = add(new Setting.Bool("Head only", false));
    private final Setting.Bool walk = add(new Setting.Bool("Walk forward", true));
    private final Setting.Bool autoJump = add(new Setting.Bool("Auto jump", false));
    private final Setting.Bool autoTool = add(new Setting.Bool("Auto tool", true));
    private final Setting.Bool autoEat = add(new Setting.Bool("Auto eat", false));
    private final Setting.Num minHealth = add(new Setting.Num("Eat health", 14.0, 2.0, 19.0, 1.0));
    private final Setting.Bool detourLava = add(new Setting.Bool("Detour lava", true));
    private final Setting.Bool silentRotations = add(new Setting.Bool("Silent rotations", false));
    private final Setting.Num turnSpeed = add(new Setting.Num("Turn speed", 25.0, 5.0, 60.0, 5.0));
    private final Setting.Num scanChunks = add(new Setting.Num("Scan chunks", 32, 2, 32, 1));
    private final Setting.Bool trialSpawners = add(new Setting.Bool("Include trial spawners", false));

    private BlockPos target;
    private boolean pressedByUs;
    private boolean jumpPressedByUs;
    private int jumpCooldown = 0;
    private int prevSlot = -1;
    private boolean prevVanillaAutoJump = false;
    private boolean loggedHazard;

    // 200-tick aim variation with smooth interpolation
    private int angleTimer = 0;
    private double currentOffsetX = 0.5;
    private double currentOffsetY = 0.5;
    private double currentOffsetZ = 0.5;
    private double targetOffsetX = 0.5;
    private double targetOffsetY = 0.5;
    private double targetOffsetZ = 0.5;

    // Straight-line navigation & A* Detour tracking
    private Direction mainDir = Direction.NORTH;
    private int targetLineCoord = 0;
    private List<BlockPos> detourPath = null;
    private int detourPathIndex = 0;
    private int replanCooldown = 0;
    private int detourStalledTicks = 0;
    private Vec3 lastDetourPos = null;

    // Auto-eat state
    private boolean isEating;
    private int eatTicks;
    private int prevSlotBeforeEat = -1;
    private InteractionHand eatingHand = InteractionHand.MAIN_HAND;

    public TunnelSpawnerFinder() {
        super("Tunnel Spawner Finder", Category.BASE);
        instance = this;
    }

    public static boolean breaking() {
        return instance != null && instance.isEnabled() && instance.target != null;
    }

    @Override
    protected void onEnable() {
        loggedHazard = false;
        target = null;
        angleTimer = 0;
        jumpCooldown = 0;
        replanCooldown = 0;
        detourStalledTicks = 0;
        lastDetourPos = null;

        currentOffsetX = targetOffsetX = 0.5;
        currentOffsetY = targetOffsetY = 0.5;
        currentOffsetZ = targetOffsetZ = 0.5;
        pickNewTargetOffset();

        if (mc.player != null) {
            mainDir = mc.player.getDirection();
            targetLineCoord = lineOf(mc.player.blockPosition());
        }

        try {
            if (mc.options != null && mc.options.autoJump() != null) {
                prevVanillaAutoJump = mc.options.autoJump().get();
                mc.options.autoJump().set(false);
            }
        } catch (Exception ignored) { }

        detourPath = null;
        detourPathIndex = 0;
        isEating = false;
        eatTicks = 0;
        prevSlotBeforeEat = -1;
    }

    private void pickNewTargetOffset() {
        targetOffsetX = 0.35 + Math.random() * 0.30;
        targetOffsetY = 0.35 + Math.random() * 0.30;
        targetOffsetZ = 0.35 + Math.random() * 0.30;
    }

    @Override
    protected void onDisable() {
        stopEating();
        stopAll();
        if (prevSlot >= 0 && mc.player != null) {
            SwapLimit.selectNow(prevSlot);
        }
        prevSlot = -1;
        try {
            if (mc.options != null && mc.options.autoJump() != null) {
                mc.options.autoJump().set(prevVanillaAutoJump);
            }
        } catch (Exception ignored) { }
        if (silentRotations.value) {
            RotationHandler.clear();
        }
    }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null || mc.gameMode == null
                || mc.gui.screen() != null || mc.player.isSpectator() || Freecam.active()) {
            stopAll();
            stopEating();
            return;
        }

        // 1. Auto eat when health or hunger is low
        if (tickAutoEat()) {
            return;
        }

        // 2. Scan for monster spawners within simulation distance
        BlockPos spawner = scanForSpawners();
        if (spawner != null) {
            disconnectSpawner(spawner);
            return;
        }

        // 3. Smooth angle drift: shift target angle every 200 ticks and smoothly lerp every tick
        if (++angleTimer >= 200) {
            angleTimer = 0;
            pickNewTargetOffset();
        }
        currentOffsetX += (targetOffsetX - currentOffsetX) * 0.05;
        currentOffsetY += (targetOffsetY - currentOffsetY) * 0.05;
        currentOffsetZ += (targetOffsetZ - currentOffsetZ) * 0.05;

        if (replanCooldown > 0) replanCooldown--;
        if (jumpCooldown > 0) jumpCooldown--;

        BlockPos base = mc.player.blockPosition();

        // 4. Detour following mode (active A* detour path around lava)
        if (detourPath != null) {
            handleDetourPath(base);
            return;
        }

        // 5. Normal straight-line tunneling mode
        handleNormalTunnel(base);
    }

    private void handleNormalTunnel(BlockPos base) {
        int ahead = (int) Math.floor(reach.value);
        BlockPos next = null;
        boolean hazard = false;

        boolean is3x3 = tunnelSize.intValue() == 1;
        Direction rightDir = mainDir.getClockWise();

        outer:
        for (int i = 1; i <= ahead; i++) {
            BlockPos centerForward = base.relative(mainDir, i);

            if (is3x3) {
                // In 3x3 mode: check center first (feet, head, ceiling), then sides
                int[] lateralOffsets = {0, -1, 1};
                int[] verticalOffsets = {0, 1, 2}; // feet, head, ceiling

                for (int lat : lateralOffsets) {
                    for (int dy : verticalOffsets) {
                        BlockPos checkPos = centerForward.relative(rightDir, lat).above(dy);
                        BlockState state = mc.level.getBlockState(checkPos);

                        if (isHazard(checkPos, state)) {
                            hazard = true;
                            break outer;
                        }

                        if (!isOpen(checkPos, state)) {
                            if (state.getDestroySpeed(mc.level, checkPos) >= 0) {
                                next = checkPos;
                                break outer;
                            } else {
                                hazard = true;
                                break outer;
                            }
                        }
                    }
                }
            } else {
                // In 2x1 tunnel: check feet level (lower block) first!
                if (!headOnly.value) {
                    BlockPos feetPos = centerForward;
                    BlockState feetState = mc.level.getBlockState(feetPos);

                    if (isHazard(feetPos, feetState)) {
                        hazard = true;
                        break;
                    }

                    if (!isOpen(feetPos, feetState)) {
                        if (feetState.getDestroySpeed(mc.level, feetPos) >= 0) {
                            next = feetPos;
                            break;
                        } else {
                            hazard = true;
                            break;
                        }
                    }
                }

                // If feet is open (or head-only mode), check head level (upper block)
                BlockPos headPos = centerForward.above();
                BlockState headState = mc.level.getBlockState(headPos);

                if (isHazard(headPos, headState)) {
                    hazard = true;
                    break;
                }

                if (!isOpen(headPos, headState)) {
                    if (headState.getDestroySpeed(mc.level, headPos) >= 0) {
                        next = headPos;
                        break;
                    } else {
                        hazard = true;
                        break;
                    }
                }
            }
        }

        // Check if floor immediately ahead is dangerous (lava or empty drop)
        BlockPos stepPos = base.relative(mainDir, 1);
        BlockPos floorPos = stepPos.below();
        BlockState floorState = mc.level.getBlockState(floorPos);
        if (isFluidOrLava(floorState) || isFluidOrLava(mc.level.getBlockState(stepPos))) {
            hazard = true;
        }

        // Handle hazard using smart A* pathfinding
        if (hazard) {
            if (detourLava.value && replanCooldown == 0) {
                detourPath = findDetourPath(base, is3x3);
                detourPathIndex = 0;
                detourStalledTicks = 0;
                lastDetourPos = null;
                if (detourPath != null && !detourPath.isEmpty()) {
                    log("Lava ahead! Pathfinding " + detourPath.size() + " blocks around hazard...");
                    handleDetourPath(base);
                    return;
                } else {
                    replanCooldown = 25;
                }
            }

            if (!loggedHazard) {
                loggedHazard = true;
                log("Hazard (lava/liquid) ahead! Paused.");
            }
            stopAll();
            aimSmoothly(mainDir.toYRot(), 0.0f);
            return;
        } else {
            loggedHazard = false;
        }

        // Check whether path directly ahead is fully clear to walk forward
        boolean clearAhead;
        if (is3x3) {
            boolean feetOpen = isOpen(base.relative(mainDir, 1));
            boolean headOpen = isOpen(base.relative(mainDir, 1).above());
            clearAhead = feetOpen && headOpen;
        } else {
            boolean clearFeet = headOnly.value || isOpen(base.relative(mainDir, 1));
            boolean clearHead = isOpen(base.relative(mainDir, 1).above());
            clearAhead = clearFeet && clearHead;
        }

        // Never jump while mining or when path is blocked by unmined blocks
        handleObstructionJump(base, mainDir, clearAhead, next != null);

        // Walk forward ONLY if the pathway ahead is actually clear
        if (walk.value && clearAhead) {
            ForcedKeys.force(mc.options.keyUp);
            pressedByUs = true;
        } else {
            releaseKey();
        }

        // If no block to dig ahead, smoothly align camera with walking direction
        if (next == null || mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(next)) > reach.value * reach.value) {
            cancelDig();
            if (walk.value && clearAhead) {
                aimSmoothly(mainDir.toYRot(), 0.0f);
            }
            return;
        }

        // Aim at block with smooth, non-flicking GCD-matched rotations
        aimAtBlock(next);

        if (autoTool.value) {
            selectTool(mc.level.getBlockState(next));
        }

        target = next;
        mc.gameMode.continueDestroyBlock(next, mainDir.getOpposite());
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void handleDetourPath(BlockPos base) {
        if (detourPath == null || detourPathIndex >= detourPath.size()) {
            detourPath = null;
            detourPathIndex = 0;
            detourStalledTicks = 0;
            lastDetourPos = null;
            log("Lava hazard bypassed! Returning to straight line tunnel.");
            return;
        }

        BlockPos waypoint = detourPath.get(detourPathIndex);
        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();

        double wx = waypoint.getX() + 0.5;
        double wy = waypoint.getY();
        double wz = waypoint.getZ() + 0.5;

        double dx = wx - px;
        double dz = wz - pz;
        double horizontalDistSq = dx * dx + dz * dz;

        // Advance to next waypoint if arrived inside waypoint block
        boolean insideWaypoint = Math.abs(px - wx) < 0.55 && Math.abs(pz - wz) < 0.55 && Math.abs(py - wy) < 0.85;
        if (insideWaypoint || (horizontalDistSq < 0.25 && Math.abs(py - wy) < 0.85)) {
            detourPathIndex++;
            detourStalledTicks = 0;
            if (detourPathIndex >= detourPath.size()) {
                detourPath = null;
                detourPathIndex = 0;
                detourStalledTicks = 0;
                lastDetourPos = null;
                log("Lava hazard bypassed! Resuming straight line tunnel.");
                return;
            }
            waypoint = detourPath.get(detourPathIndex);
            wx = waypoint.getX() + 0.5;
            wy = waypoint.getY();
            wz = waypoint.getZ() + 0.5;
            dx = wx - px;
            dz = wz - pz;
        }

        // Watchdog for stalled detour progress
        Vec3 curPos = mc.player.position();
        if (lastDetourPos != null && curPos.distanceToSqr(lastDetourPos) < 0.0004) {
            if (++detourStalledTicks > 45) {
                log("Detour progress stalled. Replanning safe route...");
                detourPath = findDetourPath(base, tunnelSize.intValue() == 1);
                detourPathIndex = 0;
                detourStalledTicks = 0;
                if (detourPath == null) {
                    stopAll();
                    log("Unable to find clear detour path. Pausing.");
                }
                return;
            }
        } else {
            detourStalledTicks = 0;
        }
        lastDetourPos = curPos;

        // Step direction towards waypoint
        Direction stepDir = getStepDirection(dx, dz);
        boolean is3x3 = tunnelSize.intValue() == 1;

        // Verify next waypoint hasn't become hazardous
        if (isHazard(waypoint, mc.level.getBlockState(waypoint)) || isHazard(waypoint.above(), mc.level.getBlockState(waypoint.above()))) {
            log("Hazard entered detour route! Replanning path...");
            detourPath = findDetourPath(base, is3x3);
            detourPathIndex = 0;
            detourStalledTicks = 0;
            if (detourPath == null) {
                stopAll();
                log("Unable to detour around hazard. Pausing.");
            }
            return;
        }

        // Determine which block at the waypoint needs to be mined (feet first in 2x1)
        BlockPos nextToMine = null;
        if (is3x3) {
            Direction rightDir = stepDir.getClockWise();
            int[] lateralOffsets = {0, -1, 1};
            int[] verticalOffsets = {0, 1, 2}; // feet, head, ceiling
            outerMine:
            for (int lat : lateralOffsets) {
                for (int dy : verticalOffsets) {
                    BlockPos checkPos = waypoint.relative(rightDir, lat).above(dy);
                    BlockState st = mc.level.getBlockState(checkPos);
                    if (!isOpen(checkPos, st) && st.getDestroySpeed(mc.level, checkPos) >= 0) {
                        nextToMine = checkPos;
                        break outerMine;
                    }
                }
            }
        } else {
            // Check feet level first
            if (!headOnly.value) {
                BlockPos feetPos = waypoint;
                BlockState feetSt = mc.level.getBlockState(feetPos);
                if (!isOpen(feetPos, feetSt) && feetSt.getDestroySpeed(mc.level, feetPos) >= 0) {
                    nextToMine = feetPos;
                }
            }
            // If feet is open, check head level
            if (nextToMine == null) {
                BlockPos headPos = waypoint.above();
                BlockState headSt = mc.level.getBlockState(headPos);
                if (!isOpen(headPos, headSt) && headSt.getDestroySpeed(mc.level, headPos) >= 0) {
                    nextToMine = headPos;
                }
            }
        }

        boolean detourClearAhead = nextToMine == null;
        handleObstructionJump(base, stepDir, detourClearAhead, nextToMine != null);

        if (nextToMine != null) {
            releaseKey();
            aimAtBlock(nextToMine);
            if (autoTool.value) {
                selectTool(mc.level.getBlockState(nextToMine));
            }
            target = nextToMine;
            mc.gameMode.continueDestroyBlock(nextToMine, stepDir.getOpposite());
            mc.player.swing(InteractionHand.MAIN_HAND);
        } else {
            // Path to waypoint is open: walk towards waypoint
            cancelDig();
            float walkYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            aimSmoothly(walkYaw, 0.0f);

            if (walk.value) {
                ForcedKeys.force(mc.options.keyUp);
                pressedByUs = true;
            } else {
                releaseKey();
            }
        }
    }

    private void handleObstructionJump(BlockPos base, Direction dir, boolean clearAhead, boolean mining) {
        // Never jump while mining, or if pathway ahead is not completely cleared
        if (!autoJump.value || !walk.value || !mc.player.onGround() || mining || !clearAhead) {
            releaseJump();
            return;
        }

        // Only jump if player is genuinely stuck against an actual floor obstruction (like a slab or step)
        if (mc.player.horizontalCollision && jumpCooldown == 0) {
            BlockPos stepAhead = base.relative(dir, 1);
            BlockPos headAhead = stepAhead.above();
            BlockPos ceilingAhead = stepAhead.above(2);

            // Require clear headroom so the player never hits their head on the tunnel ceiling
            if (isOpen(headAhead) && isOpen(ceilingAhead) && isOpen(base.above(2))) {
                ForcedKeys.force(mc.options.keyJump);
                jumpPressedByUs = true;
                jumpCooldown = 20;
                return;
            }
        }
        releaseJump();
    }

    // --- Fast, Corridor-Focused A* Pathfinding Around Lava Hazards ---

    private static class PathNode {
        final BlockPos pos;
        final PathNode parent;
        final double g;
        final double h;
        final double f;

        PathNode(BlockPos pos, PathNode parent, double g, double h) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.h = h;
            this.f = g + h;
        }
    }

    private List<BlockPos> findDetourPath(BlockPos start, boolean is3x3) {
        if (mc.level == null) return null;

        // 1. Scan ahead strictly along the tunnel line to find the exact extent of the hazard blocking our tunnel
        int hazardEndF = 1;
        boolean foundHazardOnLine = false;
        for (int f = 1; f <= 20; f++) {
            BlockPos linePos = start.relative(mainDir, f);
            if (isTunnelSliceHazard(linePos, is3x3)) {
                foundHazardOnLine = true;
                hazardEndF = f;
            } else if (foundHazardOnLine && f > hazardEndF + 1) {
                // Found clear buffer after hazard on the line
                break;
            }
        }

        int safeBuffer = 2;
        int targetF = (foundHazardOnLine ? hazardEndF : 2) + safeBuffer;
        int goalForward = forwardOf(start.relative(mainDir, targetF));
        int targetY = start.getY();

        // 2. Priority queue for A*
        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<BlockPos, Double> bestG = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        double startH = detourHeuristic(start, goalForward, targetY);
        open.add(new PathNode(start, null, 0.0, startH));
        bestG.put(start, 0.0);

        Direction right = mainDir.getClockWise();
        Direction left = mainDir.getCounterClockWise();
        Direction back = mainDir.getOpposite();

        // Check if one side is blocked near start to prioritize the clear side immediately
        boolean rightBlocked = isSideBlockedNearStart(start, right);
        boolean leftBlocked = isSideBlockedNearStart(start, left);

        Direction[] moveDirs;
        if (rightBlocked && !leftBlocked) {
            moveDirs = new Direction[]{left, mainDir, right, back};
        } else if (leftBlocked && !rightBlocked) {
            moveDirs = new Direction[]{right, mainDir, left, back};
        } else {
            moveDirs = new Direction[]{mainDir, right, left, back};
        }

        while (!open.isEmpty() && closed.size() < 2500) {
            PathNode current = open.poll();
            if (!closed.add(current.pos)) continue;

            // Goal condition: returned to original tunnel line and elevation, safely past the hazard
            if (lineOf(current.pos) == targetLineCoord && current.pos.getY() == targetY
                    && forwardOf(current.pos) >= goalForward && !current.pos.equals(start)) {
                if (isNodeSafeToTunnel(current.pos, is3x3)) {
                    LinkedList<BlockPos> path = new LinkedList<>();
                    for (PathNode curr = current; curr != null && !curr.pos.equals(start); curr = curr.parent) {
                        path.addFirst(curr.pos);
                    }
                    return path;
                }
            }

            // Expand horizontal neighbors (and small vertical steps if necessary)
            for (Direction d : moveDirs) {
                for (int dy : new int[]{0, 1, -1}) {
                    BlockPos nextPos = current.pos.relative(d).above(dy);
                    if (closed.contains(nextPos)) continue;

                    // Search bounds: max 15 blocks lateral, max 3 blocks backwards, max goalForward + 8
                    if (Math.abs(lineOf(nextPos) - targetLineCoord) > 15) continue;
                    if (forwardOf(nextPos) < forwardOf(start) - 3) continue;
                    if (forwardOf(nextPos) > goalForward + 8) continue;
                    if (Math.abs(nextPos.getY() - targetY) > 2) continue;

                    if (!isNodeSafeToTunnel(nextPos, is3x3)) continue;

                    double stepCost = 1.0;
                    if (dy != 0) stepCost += 1.5; // strongly prefer staying on same Y level
                    if (d == back) stepCost += 1.2; // penalty for backtracking
                    stepCost += 0.08 * Math.abs(lineOf(nextPos) - targetLineCoord); // slight penalty for straying too far

                    double newG = current.g + stepCost;
                    if (newG < bestG.getOrDefault(nextPos, Double.POSITIVE_INFINITY)) {
                        bestG.put(nextPos, newG);
                        double h = detourHeuristic(nextPos, goalForward, targetY);
                        open.add(new PathNode(nextPos, current, newG, h));
                    }
                }
            }
        }

        return null;
    }

    private boolean isTunnelSliceHazard(BlockPos pos, boolean is3x3) {
        if (is3x3) {
            Direction right = mainDir.getClockWise();
            for (int lat = -1; lat <= 1; lat++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos p = pos.relative(right, lat).above(dy);
                    if (isHazard(p, mc.level.getBlockState(p))) return true;
                }
            }
        } else {
            if (isHazard(pos, mc.level.getBlockState(pos))) return true;
            if (isHazard(pos.above(), mc.level.getBlockState(pos.above()))) return true;
            if (isHazard(pos.below(), mc.level.getBlockState(pos.below()))) return true;
        }
        return false;
    }

    private boolean isSideBlockedNearStart(BlockPos start, Direction side) {
        for (int i = 1; i <= 3; i++) {
            BlockPos p = start.relative(side, i);
            if (isHazard(p, mc.level.getBlockState(p)) || isHazard(p.above(), mc.level.getBlockState(p.above()))) {
                return true;
            }
        }
        return false;
    }

    private double detourHeuristic(BlockPos pos, int goalForward, int targetY) {
        int lineDiff = Math.abs(lineOf(pos) - targetLineCoord);
        int forwardRemaining = Math.max(0, goalForward - forwardOf(pos));
        int yDiff = Math.abs(pos.getY() - targetY);
        return lineDiff * 1.2 + forwardRemaining * 1.0 + yDiff * 2.0;
    }

    private boolean isNodeSafeToTunnel(BlockPos pos, boolean is3x3) {
        if (mc.level == null) return false;

        // Check floor support
        BlockPos floorPos = pos.below();
        BlockState floorState = mc.level.getBlockState(floorPos);
        if (isFluidOrLava(floorState)) return false;
        if (floorState.isAir() || floorState.getCollisionShape(mc.level, floorPos).isEmpty()) return false;

        if (is3x3) {
            // Check 3x3 tunnel cross-section profile
            Direction right = mainDir.getClockWise();
            for (int lat = -1; lat <= 1; lat++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos p = pos.relative(right, lat).above(dy);
                    BlockState st = mc.level.getBlockState(p);
                    if (isHazard(p, st)) return false;
                    if (!isOpen(p, st) && st.getDestroySpeed(mc.level, p) < 0) return false; // Bedrock
                }
            }
        } else {
            // 2x1 clearance
            BlockPos feet = pos;
            BlockState feetState = mc.level.getBlockState(feet);
            if (isHazard(feet, feetState)) return false;
            if (!isOpen(feet, feetState) && feetState.getDestroySpeed(mc.level, feet) < 0) return false;

            BlockPos head = pos.above();
            BlockState headState = mc.level.getBlockState(head);
            if (isHazard(head, headState)) return false;
            if (!isOpen(head, headState) && headState.getDestroySpeed(mc.level, head) < 0) return false;
        }

        return true;
    }

    private int lineOf(BlockPos pos) {
        return mainDir.getAxis() == Direction.Axis.X ? pos.getZ() : pos.getX();
    }

    private int forwardOf(BlockPos pos) {
        return mainDir.getStepX() * pos.getX() + mainDir.getStepZ() * pos.getZ();
    }

    private Direction getStepDirection(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    // --- Smooth Non-Flicking Rotation Engine ---

    private void aimAtBlock(BlockPos pos) {
        Vec3 targetPoint = new Vec3(pos.getX() + currentOffsetX, pos.getY() + currentOffsetY, pos.getZ() + currentOffsetZ);
        aimAtPoint(targetPoint);
    }

    private void aimAtPoint(Vec3 targetPoint) {
        if (mc.player == null) return;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 delta = targetPoint.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-delta.y, Math.hypot(delta.x, delta.z)));
        aimSmoothly(yaw, pitch);
    }

    private void aimSmoothly(float targetYaw, float targetPitch) {
        if (mc.player == null) return;
        if (silentRotations.value) {
            RotationHandler.setSilent(targetYaw, targetPitch);
            return;
        }

        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();

        float yawDiff = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;

        // Calculate mouse sensitivity GCD quantum step
        Minecraft client = Minecraft.getInstance();
        double gcd = 0.15;
        if (client != null && client.options != null) {
            double sens = client.options.sensitivity().get();
            double f = sens * 0.6 + 0.2;
            gcd = f * f * f * 8.0 * 0.15;
            if (gcd < 0.0001) gcd = 0.15;
        }

        float maxSpeed = (float) turnSpeed.value;
        float stepYaw = calculateSmoothStep(yawDiff, maxSpeed, gcd);
        float stepPitch = calculateSmoothStep(pitchDiff, maxSpeed, gcd);

        mc.player.setYRot(currentYaw + stepYaw);
        mc.player.setXRot(Mth.clamp(currentPitch + stepPitch, -90f, 90f));
    }

    private static float calculateSmoothStep(float error, float maxSpeed, double quantum) {
        float distance = Math.abs(error);
        if (distance <= quantum) return error;

        // Smooth ease-out curve: responsive tracking that smoothly decelerates into the target without snap
        float amount = Math.min(maxSpeed, Math.max((float) quantum, distance * 0.40f));
        float quantized = (float) (Math.round(amount / quantum) * quantum);
        if (quantized < (float) quantum) quantized = (float) quantum;
        return Math.copySign(Math.min(distance, quantized), error);
    }

    // --- Auto Eat Implementation ---

    private boolean tickAutoEat() {
        if (!autoEat.value || mc.player == null) {
            if (isEating || prevSlotBeforeEat >= 0) {
                stopEating();
            }
            return false;
        }

        float hp = mc.player.getHealth();
        int hunger = mc.player.getFoodData().getFoodLevel();
        boolean lowHp = hp <= (float) minHealth.value;
        boolean lowHunger = hunger <= 14;

        if (isEating) {
            eatTicks++;
            releaseKey();
            releaseJump();
            cancelDig();

            if (mc.player.isUsingItem()) return true;
            if (eatTicks > 4) {
                stopEating();
                return false;
            }
            mc.gameMode.useItem(mc.player, eatingHand);
            return true;
        }

        if (lowHp || lowHunger) {
            if (isFood(mc.player.getOffhandItem())) {
                eatingHand = InteractionHand.OFF_HAND;
                startEating();
                return true;
            } else {
                int slot = findFoodSlot();
                if (slot >= 0) {
                    eatingHand = InteractionHand.MAIN_HAND;
                    if (slot != mc.player.getInventory().getSelectedSlot()) {
                        int cur = mc.player.getInventory().getSelectedSlot();
                        if (!SwapLimit.select(slot)) return false;
                        if (prevSlotBeforeEat < 0) prevSlotBeforeEat = cur;
                    }
                    startEating();
                    return true;
                }
            }
        }
        return false;
    }

    private void startEating() {
        isEating = true;
        eatTicks = 0;
        releaseKey();
        releaseJump();
        cancelDig();
        ForcedKeys.force(mc.options.keyUse);
        mc.options.keyUse.setDown(true);
        mc.gameMode.useItem(mc.player, eatingHand);
    }

    private void stopEating() {
        boolean wasEating = isEating;
        isEating = false;
        eatTicks = 0;
        if (mc.options != null && mc.options.keyUse != null) {
            ForcedKeys.release(mc.options.keyUse);
            if (wasEating) {
                syncToPhysical(mc.options.keyUse);
            }
        }
        if (mc.player != null) {
            mc.player.stopUsingItem();
            if (mc.gameMode != null) {
                mc.gameMode.releaseUsingItem(mc.player);
            }
        }
        if (prevSlotBeforeEat >= 0 && mc.player != null) {
            SwapLimit.selectNow(prevSlotBeforeEat);
            prevSlotBeforeEat = -1;
        }
    }

    private void syncToPhysical(KeyMapping kb) {
        if (mc.options == null) return;
        boolean down = false;
        if (mc.getWindow() != null) {
            long win = mc.getWindow().handle();
            InputConstants.Key k = KeyMappingHelper.getBoundKeyOf(kb);
            if (k.getType() == InputConstants.Type.MOUSE) {
                down = GLFW.glfwGetMouseButton(win, k.getValue()) == GLFW.GLFW_PRESS;
            } else if (k.getType() == InputConstants.Type.KEYSYM) {
                down = GLFW.glfwGetKey(win, k.getValue()) == GLFW.GLFW_PRESS;
            }
        }
        kb.setDown(down);
    }

    private static boolean isFood(ItemStack s) {
        if (s == null || s.isEmpty() || s.get(DataComponents.FOOD) == null) return false;
        Item i = s.getItem();
        return i != Items.ROTTEN_FLESH && i != Items.SPIDER_EYE && i != Items.PUFFERFISH && i != Items.POISONOUS_POTATO && i != Items.CHORUS_FRUIT;
    }

    private int findFoodSlot() {
        if (mc.player == null) return -1;
        if (mc.player.getHealth() <= 10.0f) {
            for (int i = 0; i < 9; i++) {
                ItemStack s = mc.player.getInventory().getItem(i);
                if (s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.GOLDEN_APPLE)) return i;
            }
        }
        int best = -1;
        int bestNutrition = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (isFood(s)) {
                FoodProperties fp = s.get(DataComponents.FOOD);
                int nut = fp != null ? fp.nutrition() : 0;
                if (nut > bestNutrition) {
                    best = i;
                    bestNutrition = nut;
                }
            }
        }
        return best;
    }

    // --- Block and Hazard Detection ---

    private boolean isHazard(BlockPos pos, BlockState state) {
        if (state == null) return false;
        if (isFluidOrLava(state)) return true;

        // Check surrounding blocks so breaking does not release lava/liquids into the tunnel
        for (Direction d : Direction.values()) {
            BlockPos neighbor = pos.relative(d);
            BlockState nState = mc.level.getBlockState(neighbor);
            if (isFluidOrLava(nState)) return true;
        }
        return false;
    }

    private static boolean isFluidOrLava(BlockState state) {
        if (state == null) return false;
        if (!state.getFluidState().isEmpty()) return true;
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
    }

    private boolean isOpen(BlockPos pos) {
        return isOpen(pos, mc.level.getBlockState(pos));
    }

    private boolean isOpen(BlockPos pos, BlockState state) {
        return state.isAir() || state.getCollisionShape(mc.level, pos).isEmpty();
    }

    // --- Monster Spawner Detection ---

    private BlockPos scanForSpawners() {
        if (mc.level == null || mc.player == null) return null;
        ChunkPos cp = mc.player.chunkPosition();
        int simDistance = scanChunks.intValue();

        for (int dx = -simDistance; dx <= simDistance; dx++) {
            for (int dz = -simDistance; dz <= simDistance; dz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(cp.x() + dx, cp.z() + dz, false);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof SpawnerBlockEntity) {
                        return be.getBlockPos();
                    }
                    if (trialSpawners.value && be instanceof TrialSpawnerBlockEntity) {
                        return be.getBlockPos();
                    }
                }
            }
        }
        return null;
    }

    private void disconnectSpawner(BlockPos spawnerPos) {
        stopAll();
        stopEating();
        setEnabled(false);

        String posStr = "[" + spawnerPos.getX() + ", " + spawnerPos.getY() + ", " + spawnerPos.getZ() + "]";
        Component kickMsg = Component.literal("spawner found at " + posStr);

        if (mc.getConnection() != null && mc.getConnection().getConnection() != null) {
            mc.getConnection().getConnection().disconnect(kickMsg);
        } else if (mc.level != null) {
            mc.level.disconnect(kickMsg);
        }
    }

    private void selectTool(BlockState st) {
        int best = mc.player.getInventory().getSelectedSlot();
        float bestSpeed = mc.player.getInventory().getItem(best).getDestroySpeed(st);
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            float sp = s.getDestroySpeed(st);
            if (sp > bestSpeed) {
                best = i;
                bestSpeed = sp;
            }
        }
        if (best != mc.player.getInventory().getSelectedSlot()) {
            int cur = mc.player.getInventory().getSelectedSlot();
            if (!SwapLimit.select(best)) return;
            if (prevSlot < 0) prevSlot = cur;
        }
    }

    private void cancelDig() {
        if (target == null) return;
        target = null;
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    private void releaseKey() {
        if (pressedByUs) {
            pressedByUs = false;
            ForcedKeys.release(mc.options.keyUp);
            syncToPhysical(mc.options.keyUp);
            KeyMapping.setAll();
        }
    }

    private void releaseJump() {
        if (jumpPressedByUs) {
            jumpPressedByUs = false;
            ForcedKeys.release(mc.options.keyJump);
            syncToPhysical(mc.options.keyJump);
            KeyMapping.setAll();
        }
    }

    private void stopAll() {
        releaseKey();
        releaseJump();
        cancelDig();
        if (prevSlot >= 0 && mc.player != null) {
            SwapLimit.selectNow(prevSlot);
        }
        prevSlot = -1;
    }

    private void log(String msg) {
        if (mc.player != null) {
            mc.player.sendSystemMessage(
                    Component.literal("[Tunnel Spawner Finder] ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal(msg).withStyle(ChatFormatting.WHITE)));
        }
    }
}
