package dev.kuro.client.builder.autobuild;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * How water reaches a cell, as a replay of the game's own fluid tick.
 *
 * <p>A bucket makes a source, and every other water cell is what the game derives from the water around and
 * above it. This runs that derivation over one schematic, cell by cell and tick by tick, and reports the
 * level each cell settles at - which is the number a water block records: 0 for a source, 1 to 7 for a flow
 * that many blocks from the source feeding it, and 8 for water falling. That is why this builder never
 * spends a bucket on a flowing cell, and why a recorded flow appears by itself once the source that feeds
 * it is in place.
 *
 * <p>It is the game's algorithm rather than a summary of it, read out of {@code FlowingFluid}'s and
 * {@code LiquidBlock}'s bytecode in the client jar the mod is built against:
 * <ul>
 *   <li><b>Every water cell is ticked, and a tick recomputes the cell from its surroundings.</b> A cell
 *       that is not a source runs {@code getNewLiquid} again and takes whatever answer it gives - so water
 *       that has lost its supply drains away instead of staying where it was once put.</li>
 *   <li><b>A tick ends by spreading.</b> Water pours into the cell below when that cell can take water,
 *       and otherwise runs sideways - with three or more sources beside it, it does both - toward the
 *       nearest drop it can see within four blocks, and not the ways leading away from it.</li>
 *   <li><b>The cell below gets {@code getNewLiquid} of its own,</b> not the pourer's level, which is how a
 *       waterfall lands at level 8 and starts a fresh run of seven from where it stopped.</li>
 *   <li><b>Writing water into a cell schedules that cell's next tick,</b> five ticks later - water's own
 *       tick delay - and a cell whose own liquid changes reschedules itself, so the flow walks outward one
 *       cell per wave.</li>
 *   <li><b>A change wakes the water around it,</b> and the cells that are sources, that five ticks later
 *       ({@code LiquidBlock.updateShape}) - a tick the game skips when the cell no longer holds that
 *       fluid, so a cell that holds nothing is not woken into water. Water appears where a spread put it,
 *       and a source that appears beside water beside it re-derives that water.</li>
 *   <li><b>Two sources looking across a cell onto a solid block make that cell a source too,</b> the pond
 *       rule, and it needs the game's water source conversion to be on.</li>
 * </ul>
 *
 * <p>The model reads the schematic rather than the world: the file's own sources are the water it starts
 * from, the file's flowing cells are left out of that world because they are what it is predicting - the
 * game derives them back from the sources - and a cell the file does not describe blocks the water, because
 * the file's own walls are what the water is expected to stay inside. It is the tick's arithmetic over that
 * world, not a byte-for-byte replay of a server: the world's own water outside the selection is not read,
 * and cells scheduled for the same tick are ticked in the order they were scheduled.
 *
 * <p>A tick budget is a net against a caller's unbounded {@link Cells} rather than part of the rule; a run
 * that hits it says so through {@link Spread#settled()} instead of being mistaken for water that reaches
 * nothing.
 */
public final class WaterFlow {
    /** The highest level a flowing cell records, and the most blocks a run reaches from its source. */
    public static final int MAX_LEVEL = 7;
    /** The level a falling cell records, which is how the game marks water that is dropping. */
    public static final int FALLING_LEVEL = 8;
    /** Room the caller leaves for the model: one tick per cell the water touches, a few times over. */
    public static final int SPREAD_BUDGET = 200000;
    /** Water's own tick delay ({@code WaterFluid.getTickDelay}): how far ahead a written cell is ticked. */
    public static final int TICK_DELAY = 5;
    /** Water's slope find distance: how far a cell looks for a drop to run toward. */
    private static final int SLOPE_FIND_DISTANCE = 4;
    /** The "no drop within reach" answer of the game's slope search. */
    private static final int NO_DROP = 1000;
    /**
     * The source neighbours that let a cell run sideways while it pours, which is water's {@code spread}'s
     * own threshold. It counts neighbours rather than asking whether the cell is a source, so a flow that
     * three sources stand beside pours and spreads at once.
     */
    private static final int POURING_SPREAD_SOURCES = 3;

    /**
     * The fluid in one cell, in the game's own terms: {@code amount} is {@code FluidState.getAmount()} - 8
     * for a source and for falling water, 7 down to 1 for a flow - and the flags are what the game reads to
     * decide what the cell does next.
     */
    public record Fluid(boolean present, boolean water, boolean source, int amount, boolean falling) {
        /** No fluid at all: air, or a cell the model has emptied. */
        public static final Fluid NONE = new Fluid(false, false, false, 0, false);
        /** A fluid that is not water, which water may still pour into but nothing else here reads. */
        public static final Fluid OTHER = new Fluid(true, false, false, 0, false);
        /** A water source: the state a bucket makes, and the state a waterlogged block holds. */
        public static final Fluid SOURCE = new Fluid(true, true, true, 8, false);
        /** Water that is dropping, which the game records at level {@link #FALLING_LEVEL}. */
        public static final Fluid FALLING = new Fluid(true, true, false, 8, true);

        /** A flow of the given amount, 1 to 7. */
        public static Fluid flow(int amount) { return new Fluid(true, true, false, amount, false); }

        /** The level the block records: 0 for a source, 1 to 7 for a flow, {@link #FALLING_LEVEL} falling. */
        public int level() { return source ? 0 : falling ? FALLING_LEVEL : 8 - amount; }

        /** True for the same fluid state, which is when the game leaves a cell as it is. */
        public boolean sameAs(Fluid other) {
            return present == other.present && water == other.water && source == other.source
                    && amount == other.amount && falling == other.falling;
        }
    }

    /**
     * What the model reads: the file's own world, cell by cell, in the terms the game's rules ask in. A
     * caller backs this with the schematic, so every answer describes the build rather than the world.
     */
    public interface Cells {
        /**
         * The fluid in a cell. The caller reports the file's own water, with the flowing cells left out -
         * they are what the model is predicting - and a cell the file does not describe as {@link
         * Fluid#NONE}.
         */
        Fluid fluid(BlockPos pos);
        /** True when fluid can stand here at all, the game's {@code canHoldAnyFluid}. */
        boolean holdsAnyFluid(BlockPos pos);
        /**
         * True when fluid can be put into this cell, the game's {@code canHoldSpecificFluid}: a block that
         * takes water itself refuses when it already holds it.
         */
        boolean holdsFluid(BlockPos pos, boolean water);
        /** True when the block here takes water into itself rather than being replaced, a waterlogged cell. */
        boolean waterloggable(BlockPos pos);
        /** The block's collision shape, which is what decides whether water passes between two cells. */
        VoxelShape shape(BlockPos pos);
        /** True when the file describes this cell as a solid block, which the pond rule needs below it. */
        boolean solid(BlockPos pos);
    }

    /** The levels a run settled at, and whether it settled at all within the caller's budget. */
    public record Spread(Map<BlockPos, Integer> levels, boolean settled) {
        /** The level a cell settled at: 0 a source, 1 to 7 a flow, {@link #FALLING_LEVEL} falling. */
        public int level(BlockPos pos) {
            Integer value = levels.get(pos);
            return value == null ? -1 : value;
        }
    }

    private WaterFlow() { }

    /**
     * Plays the game's fluid tick out over the given world from the given sources, and reports the level
     * every cell of water settles at.
     */
    public static Spread spread(Cells cells, Collection<BlockPos> sources, int budget) {
        return spread(cells, sources, true, budget);
    }

    /** The same run, with the game's water source conversion read from the world it is built in. */
    public static Spread spread(Cells cells, Collection<BlockPos> sources, boolean sourceConversion, int budget) {
        return new Simulation(cells, sourceConversion).settle(sources, budget);
    }

    /** The level the given sources give this cell, or -1 when none of their water reaches it. */
    public static int reachable(Cells cells, Collection<BlockPos> sources, BlockPos target) {
        return spread(cells, sources, SPREAD_BUDGET).level(target);
    }

    /**
     * True when water here has somewhere to go: the cell below can take water and holds none of it.
     *
     * <p>This is the file's answer rather than the model's, which decides the same question from the water
     * it has already placed. A call to it says whether a block under this cell would change what the water
     * does.
     */
    public static boolean falls(Cells cells, BlockPos pos) {
        BlockPos below = pos.below();
        return !cells.fluid(below).water() && cells.holdsAnyFluid(below) && cells.holdsFluid(below, true);
    }

    /** The four horizontal neighbours, which is every direction water spreads across. */
    public static List<BlockPos> neighbours(BlockPos pos) {
        List<BlockPos> values = new ArrayList<>(4);
        values.add(pos.north()); values.add(pos.south()); values.add(pos.east()); values.add(pos.west());
        return values;
    }

    /**
     * The game's {@code FlowingFluid.canHoldAnyFluid}: whether fluid can stand in a cell holding this
     * block. A block that takes fluid into itself can hold it, a block that stops motion cannot, and
     * neither can the blocks the game lists by hand - doors, signs, ladders, sugar cane, bubble columns,
     * the three portals and the structure void - which stop motion without giving water anywhere to sit.
     */
    public static boolean holdsAnyFluid(BlockState state) {
        if (state.getBlock() instanceof LiquidBlockContainer) return true;
        if (state.blocksMotion()) return false;
        return !(state.getBlock() instanceof DoorBlock) && !state.is(BlockTags.SIGNS)
                && !state.is(Blocks.LADDER) && !state.is(Blocks.SUGAR_CANE) && !state.is(Blocks.BUBBLE_COLUMN)
                && !state.is(Blocks.NETHER_PORTAL) && !state.is(Blocks.END_PORTAL)
                && !state.is(Blocks.END_GATEWAY) && !state.is(Blocks.STRUCTURE_VOID);
    }

    /**
     * The game's {@code canPassThroughWall}: whether water can pass between two cells whose blocks have
     * these collision shapes, in this direction. A full block on either side stops it, two empty shapes
     * always let it through, and otherwise the two shapes together must leave the face open.
     */
    public static boolean canPassThroughWall(Direction direction, VoxelShape from, VoxelShape to) {
        if (to == Shapes.block() || from == Shapes.block()) return false;
        if (from.isEmpty() && to.isEmpty()) return true;
        return !Shapes.mergedFaceOccludes(from, to, direction);
    }

    /**
     * One run of the game's tick loop over one world: the water it has placed, the ticks still due, and
     * the rules each tick applies.
     */
    private static final class Simulation {
        private final Cells cells;
        private final boolean sourceConversion;
        /** The water this run placed, and the cells it emptied, over the caller's own world. */
        private final Map<BlockPos, Fluid> placed = new HashMap<>();
        /** The tick each cell is waiting for, so a cell is never ticked twice for the same write. */
        private final Map<BlockPos, Long> due = new HashMap<>();
        private final PriorityQueue<Tick> queue = new PriorityQueue<>();
        /**
         * The drop search's answers, which the game caches the same way inside one {@code SpreadContext}.
         * One tick asks the same cell the same question again and again, and none of the answers change
         * until the water does, so the notes are dropped whenever a cell is written.
         */
        private final Map<BlockPos, Boolean> holeCache = new HashMap<>();
        private final Map<Search, Integer> slopeCache = new HashMap<>();

        /** One cell of the slope search, from the direction the search came from. */
        private record Search(BlockPos pos, Direction from) { }
        private long sequence;
        /** The tick being run, which every cell written during it is due this many ticks after. */
        private long now;

        Simulation(Cells cells, boolean sourceConversion) {
            this.cells = cells;
            this.sourceConversion = sourceConversion;
        }

        /** One scheduled tick. The game reads ticks in time order, and same-time ticks in the order given. */
        private record Tick(long time, long order, BlockPos pos) implements Comparable<Tick> {
            @Override public int compareTo(Tick other) {
                int byTime = Long.compare(time, other.time);
                return byTime != 0 ? byTime : Long.compare(order, other.order);
            }
        }

        Spread settle(Collection<BlockPos> sources, int budget) {
            // Each source is a block the file places: that schedules its own tick and wakes the cells
            // around it, exactly as LiquidBlock.onPlace and updateShape do. A woken cell that holds
            // nothing is left alone by its own tick, so the source's own spread is what waters its sides.
            for (BlockPos source : sources) {
                BlockPos key = source.immutable();
                placed.put(key, Fluid.SOURCE);
                schedule(key);
                wakeAround(key, Fluid.SOURCE);
            }
            int ticks = 0;
            boolean settled = true;
            while (!queue.isEmpty()) {
                if (ticks++ >= budget) { settled = false; break; }
                Tick tick = queue.poll();
                due.remove(tick.pos());
                now = tick.time();
                run(tick.pos());
            }
            Map<BlockPos, Integer> levels = new HashMap<>();
            for (Map.Entry<BlockPos, Fluid> entry : placed.entrySet())
                if (entry.getValue().present && entry.getValue().water())
                    levels.put(entry.getKey(), entry.getValue().level());
            return new Spread(levels, settled);
        }

        /** The fluid in a cell: what this run put there, and otherwise what the caller's world holds. */
        private Fluid fluid(BlockPos pos) {
            Fluid placedHere = placed.get(pos);
            return placedHere != null ? placedHere : cells.fluid(pos);
        }

        /**
         * One cell's tick, which is {@code FlowingFluid.tick} and then {@code spread}. The game reaches a
         * cell's fluid only when the cell still holds that fluid ({@code ServerLevel.tickFluid}), so a
         * scheduled cell that has come to hold nothing is left alone - which is why a cell the file does
         * not describe is never woken into water.
         */
        private void run(BlockPos pos) {
            holeCache.clear();
            slopeCache.clear();
            Fluid fluid = fluid(pos);
            if (!fluid.water()) return;
            if (!fluid.source()) {
                // A cell that is not a source works out its own liquid again every tick, so water that has
                // lost its supply drains away and water that has gained one strengthens.
                Fluid liquid = newLiquid(pos);
                if (!liquid.sameAs(fluid)) {
                    put(pos, liquid);
                    fluid = liquid;
                }
            }
            if (fluid.present) spread(pos, fluid);
        }

        /**
         * {@code FlowingFluid.spread}: pour into the cell below when it can take the water, and otherwise
         * run sideways - and a cell that pours runs sideways too when three or more sources stand beside it.
         */
        private void spread(BlockPos pos, Fluid fluid) {
            BlockPos below = pos.below();
            if (canMaybePassThrough(pos, below)) {
                Fluid liquid = newLiquid(below);
                // Water never pours down into water: the cell that already holds it takes its level from its
                // neighbours instead. Anything else below, air included, is replaced.
                if (!fluid(below).water() && cells.holdsFluid(below, liquid.water())) {
                    spreadTo(below, liquid);
                    if (sourceNeighbours(pos) >= POURING_SPREAD_SOURCES) spreadToSides(pos, fluid);
                    return;
                }
            }
            // A cell that cannot pour onto the water below it only feeds the fall; a source spreads anyway,
            // and so does a cell whose below is not a hole at all.
            if (fluid.source() || !waterHole(pos)) spreadToSides(pos, fluid);
        }

        /**
         * {@code FlowingFluid.getNewLiquid}: a source when two sources look across the cell onto something
         * solid, falling water when water stands directly above it, and otherwise one level weaker than the
         * strongest water beside it - nothing when that reaches zero.
         */
        private Fluid newLiquid(BlockPos pos) {
            int sources = 0;
            int strongest = 0;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbour = pos.relative(direction);
                Fluid there = fluid(neighbour);
                if (!there.water() || !canPassThroughWall(direction, pos, neighbour)) continue;
                if (there.source()) sources++;
                strongest = Math.max(strongest, there.amount());
            }
            BlockPos below = pos.below();
            Fluid belowFluid = fluid(below);
            if (sources >= 2 && sourceConversion && (cells.solid(below) || belowFluid.source())) return Fluid.SOURCE;
            BlockPos above = pos.above();
            if (fluid(above).water() && canPassThroughWall(Direction.UP, pos, above)) return Fluid.FALLING;
            int amount = strongest - 1;
            return amount <= 0 ? Fluid.NONE : Fluid.flow(amount);
        }

        /** {@code FlowingFluid.spreadToSides}: one level weaker than the water here, where it runs. */
        private void spreadToSides(BlockPos pos, Fluid fluid) {
            int amount = fluid.falling() ? MAX_LEVEL : fluid.amount() - 1;
            if (amount <= 0) return;
            for (Map.Entry<BlockPos, Fluid> entry : spreadDirections(pos).entrySet())
                spreadTo(entry.getKey(), entry.getValue());
        }

        /**
         * {@code FlowingFluid.getSpread}: the directions a cell runs in, which are the ones whose drop it
         * can reach soonest - water runs toward the nearest drop within four blocks and not at all in the
         * directions leading away from it, with the closest drops of equal distance all fed.
         */
        private Map<BlockPos, Fluid> spreadDirections(BlockPos pos) {
            Map<BlockPos, Fluid> chosen = new LinkedHashMap<>();
            int nearest = NO_DROP;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbour = pos.relative(direction);
                if (!canMaybePassThrough(pos, neighbour)) continue;
                Fluid target = newLiquid(neighbour);
                if (!cells.holdsFluid(neighbour, target.water())) continue;
                int distance = waterHole(neighbour) ? 0 : slopeDistance(neighbour, 1, direction.getOpposite());
                if (distance < nearest) chosen.clear();
                if (distance <= nearest) {
                    // A direction can set the nearest drop for the others without being filled itself, which
                    // is what water already standing in that cell does.
                    if (!fluid(neighbour).water()) chosen.put(neighbour.immutable(), target);
                    nearest = distance;
                }
            }
            return chosen;
        }

        /**
         * {@code FlowingFluid.getSlopeDistance}: how many blocks a cell is from the nearest drop it can
         * reach. The search walks away from the cell it came from, stops at the first drop it meets, and
         * gives up past the slope find distance.
         */
        private int slopeDistance(BlockPos pos, int distance, Direction from) {
            Search search = new Search(pos, from);
            Integer known = slopeCache.get(search);
            if (known != null) return known;
            int nearest = NO_DROP;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (direction == from) continue;
                BlockPos neighbour = pos.relative(direction);
                if (!canPassThrough(pos, neighbour)) continue;
                if (waterHole(neighbour)) {
                    slopeCache.put(search, distance);
                    return distance;
                }
                if (distance < SLOPE_FIND_DISTANCE)
                    nearest = Math.min(nearest, slopeDistance(neighbour, distance + 1, direction.getOpposite()));
            }
            slopeCache.put(search, nearest);
            return nearest;
        }

        /** {@code FlowingFluid.isWaterHole}: the cell below could take this water, or already holds it. */
        private boolean waterHole(BlockPos pos) {
            BlockPos key = pos.immutable();
            Boolean known = holeCache.get(key);
            if (known != null) return known;
            BlockPos below = key.below();
            boolean hole = canPassThroughWall(Direction.DOWN, key, below)
                    && (fluid(below).water() || cells.holdsAnyFluid(below) && cells.holdsFluid(below, true));
            holeCache.put(key, hole);
            return hole;
        }

        /**
         * {@code FlowingFluid.canMaybePassThrough}: water can enter this cell at all - it is not already a
         * source of the same water, the block there can hold fluid, and the wall between them lets it past.
         */
        private boolean canMaybePassThrough(BlockPos from, BlockPos to) {
            if (fluid(to).source()) return false;
            return cells.holdsAnyFluid(to) && canPassThroughWall(direction(from, to), from, to);
        }

        /** The same, for the slope search, which also needs the cell to be able to hold flowing water. */
        private boolean canPassThrough(BlockPos from, BlockPos to) {
            return canMaybePassThrough(from, to) && cells.holdsFluid(to, true);
        }

        /** {@code FlowingFluid.sourceNeighborCount}, which the rule counts rather than the cell's own state. */
        private int sourceNeighbours(BlockPos pos) {
            int count = 0;
            for (BlockPos neighbour : neighbours(pos)) if (fluid(neighbour).source()) count++;
            return count;
        }

        /** {@code FlowingFluid.spreadTo}: the water goes into the cell, or the block itself takes it. */
        private void spreadTo(BlockPos pos, Fluid fluid) {
            if (fluid.water() && cells.waterloggable(pos)) put(pos, Fluid.SOURCE);
            else put(pos, fluid);
        }

        /**
         * Writes a fluid into a cell, the way {@code Level.setBlock} does: nothing happens when the cell
         * already holds it, a placed block is scheduled for its own next tick - which is what makes the
         * flow walk outward - and the cells around it that are sources are woken, as {@code updateShape}
         * does. A cell the model empties is written but not scheduled: the game puts air there, and air
         * schedules nothing.
         */
        private void put(BlockPos pos, Fluid fluid) {
            BlockPos key = pos.immutable();
            if (fluid.sameAs(fluid(key))) return;
            // Water only ever stands in a cell that can hold fluid, so a cell the file does not describe
            // stays dry even when it is worked out from the water beside it.
            if (fluid.present && !cells.holdsAnyFluid(key)) return;
            placed.put(key, fluid);
            // The water has moved, so every cached answer about a drop is stale.
            holeCache.clear();
            slopeCache.clear();
            if (fluid.present) schedule(key);
            wakeAround(key, fluid);
        }

        /** The cells around a change that the game wakes: the ones that are sources themselves. */
        private void wakeAround(BlockPos pos, Fluid changed) {
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = pos.relative(direction);
                if (fluid(neighbour).source() || changed.source()) schedule(neighbour);
            }
        }

        /** Schedules a cell's next tick, the way the game schedules one: once, at the earliest time due. */
        private void schedule(BlockPos pos) {
            BlockPos key = pos.immutable();
            if (due.containsKey(key)) return;
            long time = now + TICK_DELAY;
            due.put(key, time);
            queue.add(new Tick(time, sequence++, key));
        }

        private boolean canPassThroughWall(Direction direction, BlockPos from, BlockPos to) {
            return WaterFlow.canPassThroughWall(direction, cells.shape(from), cells.shape(to));
        }

        private static Direction direction(BlockPos from, BlockPos to) {
            int dx = to.getX() - from.getX();
            int dy = to.getY() - from.getY();
            int dz = to.getZ() - from.getZ();
            if (dx != 0) return dx > 0 ? Direction.EAST : Direction.WEST;
            if (dy != 0) return dy > 0 ? Direction.UP : Direction.DOWN;
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }
}
