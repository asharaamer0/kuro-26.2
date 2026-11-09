package dev.kuro.client.builder.autobuild;

import net.minecraft.core.BlockPos;

/**
 * The walking rules, as pure decisions over the facts the walker reads from the level.
 *
 * <p>These rules used to live inside the navigator as level reads, where nothing could review them. A cell
 * whose fluid was anything but empty was refused outright, so the builder would not enter water at all and
 * could never reach a build that stands in it. Keeping the decision here means the walk, the swim, and the
 * hazards each of them refuses can be checked without a running world.
 */
public final class BuilderTerrain {
    /** What the rules need to know about one cell; the navigator answers these from the client level. */
    public interface World {
        /** True when the chunk around this cell is loaded, so its content can be read at all. */
        boolean loaded(BlockPos pos);
        /** False for pending/unknown placement or destruction, even if client collision says air. */
        default boolean known(BlockPos pos) { return true; }
        /** True when this cell holds nothing but air or water, so a body could occupy it. */
        boolean open(BlockPos pos);
        /** True when this cell is a water block the player can swim in. */
        boolean water(BlockPos pos);
        /** True when a solid block the player can stand on fills this cell and it is not a hazard. */
        boolean floor(BlockPos pos);
        /** True when the player's own collision box fits into this cell. */
        boolean fits(BlockPos pos);
    }

    private BuilderTerrain() { }

    /**
     * True when the walker may use this feet cell.
     *
     * <p>An ordinary step needs solid, non-hazardous ground under it. A water cell needs no floor at all,
     * because the walker swims through it instead of standing on something - which is what turns a water
     * body, and the column of water above it, from a wall into route.
     */
    public static boolean standable(World world, BuilderPathfinder.Cell feet) {
        BlockPos pos = pos(feet);
        if (!world.loaded(pos) || !world.loaded(pos.above(2))) return false;
        if (!headroom(world, feet)) return false;
        BlockPos below = pos.below();
        if (!world.loaded(below) || !world.known(below)) return false;
        if (world.water(pos)) return world.water(below) || world.floor(below);
        return world.floor(below);
    }

    /**
     * True when the player's body has room in this cell. The feet cell and the head cell above it must both
     * hold nothing but air or water, the blocks that hurt to occupy are refused here, and the player's own
     * collision box has to fit - which is what keeps the walker out of walls, fences and closed doors.
     */
    public static boolean headroom(World world, BuilderPathfinder.Cell feet) {
        BlockPos pos = pos(feet);
        if (!world.loaded(pos) || !world.loaded(pos.above())) return false;
        if (!world.known(pos) || !world.known(pos.above())) return false;
        if (!world.open(pos) || !world.open(pos.above())) return false;
        return world.fits(pos);
    }

    /**
     * True when a gap can be bridged: 'from' has a solid floor under it, 'at' has headroom, and the
     * space directly under 'at' is open and adjacent to the solid supporting floor of 'from'.
     */
    public static boolean bridgeable(World world, BuilderPathfinder.Cell at, BuilderPathfinder.Cell from) {
        if (at.y() != from.y()) return false;
        int dx = Math.abs(at.x() - from.x());
        int dz = Math.abs(at.z() - from.z());
        if (dx + dz != 1) return false;
        BlockPos atPos = pos(at);
        BlockPos fromPos = pos(from);
        if (!world.loaded(atPos) || !world.loaded(fromPos)) return false;
        if (!headroom(world, at)) return false;
        BlockPos fromFloor = fromPos.below();
        if (!world.loaded(fromFloor) || !world.floor(fromFloor)) return false;
        BlockPos atFloor = atPos.below();
        if (!world.loaded(atFloor) || !world.known(atFloor) || !world.known(fromFloor)) return false;
        if (world.floor(atFloor) || world.water(atFloor)) return false;
        return world.open(atFloor);
    }

    private static BlockPos pos(BuilderPathfinder.Cell cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }
}
