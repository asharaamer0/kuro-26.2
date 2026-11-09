package dev.kuro.client.builder.autobuild;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Directs and shapes water streams for farm structures (such as sea pickle farms)
 * using temporary blocks to change flow direction or prevent side spills.
 */
public final class WaterFlowGuide {
    private WaterFlowGuide() { }

    /**
     * Checks if placing water at {@code waterPos} requires a temporary block to steer flow
     * into the intended direction or change direction at a turn.
     * Returns a single Target, or null if no temporary block is needed.
     */
    public static AutoBuilderController.Target findFlowGuide(
            Minecraft client,
            BlockPos waterPos,
            Map<BlockPos, Integer> flowLevels,
            Function<BlockPos, BlockState> fileState,
            Supplier<Item> scaffoldSupplier) {

        if (client.level == null || client.player == null) return null;

        // Check which horizontal directions are intended to have water from this source
        List<Direction> intendedDirs = new ArrayList<>(4);
        List<Direction> unintendedDirs = new ArrayList<>(4);

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = waterPos.relative(dir);
            if (flowLevels.containsKey(neighbor) && flowLevels.get(neighbor) > 0) {
                intendedDirs.add(dir);
            } else if (!flowLevels.containsKey(neighbor)) {
                unintendedDirs.add(dir);
            }
        }

        // If this water source does not have a directional flow (e.g. enclosed or static pool), no temp block needed
        if (intendedDirs.isEmpty()) return null;

        // If the water is already flowing into all intended directions in the world, no temp block needed
        boolean allIntendedFlowing = true;
        for (Direction dir : intendedDirs) {
            BlockPos neighbor = waterPos.relative(dir);
            if (!MaterialTracker.isLoaded(client.level, neighbor)
                    || !client.level.getFluidState(neighbor).is(FluidTags.WATER)) {
                allIntendedFlowing = false;
                break;
            }
        }
        if (allIntendedFlowing) return null;

        Item scaffold = scaffoldSupplier.get();
        if (!(scaffold instanceof BlockItem blockItem)) return null;
        BlockState scaffoldState = blockItem.getBlock().defaultBlockState();

        // 1. Immediate direction guidance: block any immediate unintended neighbor of waterPos
        // that is currently open air in the world and would divert the flow away from intendedDirs
        for (Direction dir : unintendedDirs) {
            BlockPos neighbor = waterPos.relative(dir);
            if (fileState.apply(neighbor) != null) continue;
            if (!MaterialTracker.isLoaded(client.level, neighbor)) continue;
            BlockState actual = client.level.getBlockState(neighbor);
            if (actual.isAir()) {
                if (hasPlacementSupport(client, neighbor)) {
                    return new AutoBuilderController.Target(neighbor, scaffoldState, -1, true);
                }
            }
        }

        // 2. Corner / Direction change along the immediate downstream turn:
        // For each intended direction, check if the stream turns at the very next block
        for (Direction dir : intendedDirs) {
            BlockPos next = waterPos.relative(dir);
            if (!flowLevels.containsKey(next)) continue;

            // Water moves along 'dir' into 'next'.
            // If water does NOT continue straight in 'dir' in the schematic, it must turn!
            BlockPos straight = next.relative(dir);
            if (fileState.apply(straight) != null) continue;
            if (!flowLevels.containsKey(straight)) {
                if (MaterialTracker.isLoaded(client.level, straight)) {
                    BlockState straightActual = client.level.getBlockState(straight);
                    if (straightActual.isAir() && hasPlacementSupport(client, straight)) {
                        return new AutoBuilderController.Target(straight, scaffoldState, -1, true);
                    }
                }
            }
        }

        return null;
    }

    /**
     * For a flowing cell that should be fed by an already-placed water source but hasn't received water,
     * checks if a corner turn block is needed to redirect water to it.
     */
    public static AutoBuilderController.Target findFlowGuideForUnreachedFlow(
            Minecraft client,
            BlockPos flowPos,
            Map<BlockPos, Integer> flowLevels,
            Function<BlockPos, BlockState> fileState,
            Supplier<Item> scaffoldSupplier) {

        if (client.level == null) return null;
        BlockPos source = findFeedingSource(flowPos, flowLevels);
        if (source == null) return null;

        // If the source is not yet placed in the world, the flow simply hasn't been poured yet
        if (!MaterialTracker.isLoaded(client.level, source)
                || !client.level.getFluidState(source).is(FluidTags.WATER)) {
            return null;
        }

        // The source is already placed in the world, but water hasn't reached flowPos!
        // Check if an immediate corner turn or containment block is needed
        return findFlowGuide(client, source, flowLevels, fileState, scaffoldSupplier);
    }

    /**
     * Walks backward through descending fluid levels to find the water source that feeds {@code flowPos}.
     */
    public static BlockPos findFeedingSource(BlockPos flowPos, Map<BlockPos, Integer> flowLevels) {
        BlockPos current = flowPos;
        Set<BlockPos> visited = new HashSet<>();
        visited.add(current);

        for (int step = 0; step < 16; step++) {
            int level = flowLevels.getOrDefault(current, -1);
            if (level <= 0) return current;

            if (level == WaterFlow.FALLING_LEVEL) {
                BlockPos above = current.above();
                if (flowLevels.containsKey(above) && visited.add(above)) {
                    current = above;
                    continue;
                }
            }

            BlockPos upstream = null;
            int lowestLevel = level;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = current.relative(dir);
                if (flowLevels.containsKey(neighbor)) {
                    int neighborLevel = flowLevels.get(neighbor);
                    if (neighborLevel == WaterFlow.FALLING_LEVEL) {
                        upstream = neighbor;
                        break;
                    }
                    if (neighborLevel < lowestLevel) {
                        lowestLevel = neighborLevel;
                        upstream = neighbor;
                    }
                }
            }

            if (upstream != null && visited.add(upstream)) {
                current = upstream;
            } else {
                break;
            }
        }

        return flowLevels.getOrDefault(current, -1) == 0 ? current : null;
    }

    /**
     * Checks if a world block state can allow water to enter it.
     */
    public static boolean isOpenToWater(BlockState state) {
        if (state == null) return true;
        if (state.isAir() || state.canBeReplaced()) return true;
        if (state.blocksMotion()) return false;
        return WaterFlow.holdsAnyFluid(state);
    }

    /**
     * Checks if a position can be clicked/placed against an adjacent solid block face.
     */
    public static boolean hasPlacementSupport(Minecraft client, BlockPos pos) {
        if (!MaterialTracker.isLoaded(client.level, pos)) return false;
        if (!client.level.getBlockState(pos).canBeReplaced()) return false;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            if (!MaterialTracker.isLoaded(client.level, neighbor)) continue;
            BlockState state = client.level.getBlockState(neighbor);
            if (!state.canBeReplaced() && !state.getCollisionShape(client.level, neighbor).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
