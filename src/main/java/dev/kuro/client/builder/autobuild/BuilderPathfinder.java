package dev.kuro.client.builder.autobuild;

import java.util.*;
import java.util.function.Predicate;

/** Bounded A* over walkable feet cells. Pure geometry keeps route behavior independently testable. */
public final class BuilderPathfinder {
    public record Cell(int x, int y, int z) {
        public Cell offset(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); }
        public double distance(Cell other) { return Math.sqrt((double)(x-other.x)*(x-other.x) + (double)(y-other.y)*(y-other.y) + (double)(z-other.z)*(z-other.z)); }
    }
    public interface Terrain {
        boolean standable(Cell cell);
        /** Jumping up requires clearance over the departure block too. */
        boolean headroom(Cell cell);
        default boolean water(Cell cell) { return false; }
        default boolean bridgeable(Cell cell, Cell from) { return false; }
    }
    private record Entry(Cell cell, double score) { }
    private record BridgeStep(Cell cell, Cell from) { }
    private BuilderPathfinder() { }

    private static boolean isOutOfBounds(Cell next, Cell start, int maxDistance) {
        return Math.abs(next.x - start.x) > maxDistance
                || Math.abs(next.z - start.z) > maxDistance
                || Math.abs(next.y - start.y) > 48;
    }

    private static void addCandidate(PriorityQueue<Entry> open, Map<Cell, Double> costs,
                                     Map<Cell, Cell> previous, Cell target, Cell current, Cell next,
                                     double cost, Map<Cell, Double> penalties) {
        double penalty = penalties.getOrDefault(next, 0.0);
        double totalCost = cost + penalty;
        if (totalCost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) return;
        costs.put(next, totalCost);
        previous.put(next, current);
        open.add(new Entry(next, totalCost + Math.max(0, next.distance(target) - 4)));
    }

    public static List<Cell> find(Cell start, Cell target, Terrain terrain, Predicate<Cell> goal,
                                  int maxVisited, int maxDistance) {
        return find(start, target, terrain, goal, maxVisited, maxDistance, Map.of());
    }

    public static List<Cell> find(Cell start, Cell target, Terrain terrain, Predicate<Cell> goal,
                                  int maxVisited, int maxDistance, Map<Cell, Double> penalties) {
        // A search probes most cells from several neighbours, and each probe reads the world. Caching
        // one answer per cell keeps the whole search inside a client tick instead of stuttering while
        // the builder replans, without changing which route is found.
        Terrain probed = new Terrain() {
            private final Map<Cell, Boolean> standable = new HashMap<>(), headroom = new HashMap<>(), water = new HashMap<>();
            private final Map<BridgeStep, Boolean> bridgeable = new HashMap<>();
            @Override public boolean standable(Cell cell) { return standable.computeIfAbsent(cell, terrain::standable); }
            @Override public boolean headroom(Cell cell) { return headroom.computeIfAbsent(cell, terrain::headroom); }
            @Override public boolean water(Cell cell) { return water.computeIfAbsent(cell, terrain::water); }
            @Override public boolean bridgeable(Cell cell, Cell from) {
                return bridgeable.computeIfAbsent(new BridgeStep(cell, from),
                        step -> terrain.bridgeable(step.cell(), step.from()));
            }
        };
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::score));
        Map<Cell, Double> costs = new HashMap<>();
        Map<Cell, Cell> previous = new HashMap<>();
        Set<Cell> closed = new HashSet<>();
        costs.put(start, 0d);
        open.add(new Entry(start, start.distance(target)));
        int[][] horizontalAxes = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!open.isEmpty() && closed.size() < maxVisited) {
            Cell current = open.remove().cell;
            if (!closed.add(current)) continue;
            if (goal.test(current)) {
                LinkedList<Cell> route = new LinkedList<>();
                for (Cell cursor = current; !cursor.equals(start); cursor = previous.get(cursor)) route.addFirst(cursor);
                return List.copyOf(route);
            }
            boolean inWater = probed.water(current);
            int[][] currentAxes = inWater ? new int[][]{{1,0},{-1,0},{0,1},{0,-1},{0,0}} : horizontalAxes;
            for (int[] axis : currentAxes) {
                if (axis[0] == 0 && axis[1] == 0) {
                    // Vertical swimming inside water: swim straight up or down 1 cell
                    for (int dy : new int[]{1, -1}) {
                        Cell next = current.offset(0, dy, 0);
                        if (isOutOfBounds(next, start, maxDistance) || closed.contains(next) || !probed.standable(next)) continue;
                        double cost = costs.get(current) + (dy > 0 ? 1.2 : 1.0);
                        addCandidate(open, costs, previous, target, current, next, cost, penalties);
                    }
                    continue;
                }

                int dx = axis[0], dz = axis[1];
                if (inWater) {
                    // In water: swim horizontally, diagonally up (or vault ashore), or diagonally down
                    for (int dy : new int[]{0, 1, -1}) {
                        Cell next = current.offset(dx, dy, dz);
                        if (isOutOfBounds(next, start, maxDistance) || closed.contains(next) || !probed.standable(next)) continue;
                        if (dy > 0 && !probed.headroom(current.offset(0, 1, 0))) continue;
                        if (dy < 0 && !probed.headroom(current.offset(dx, 0, dz))) continue;
                        boolean nextInWater = probed.water(next);
                        double stepCost = dy == 0 ? 1.0 : dy > 0 ? (nextInWater ? 1.45 : 3.5) : 1.1;
                        double cost = costs.get(current) + stepCost;
                        addCandidate(open, costs, previous, target, current, next, cost, penalties);
                    }
                } else {
                    // On land / in air:
                    // 1. Flat step (dy = 0)
                    Cell flat = current.offset(dx, 0, dz);
                    boolean hasFlat = false;
                    if (!isOutOfBounds(flat, start, maxDistance) && !closed.contains(flat)) {
                        if (probed.standable(flat)) {
                            hasFlat = true;
                            addCandidate(open, costs, previous, target, current, flat, costs.get(current) + 1.0, penalties);
                        } else if (probed.bridgeable(flat, current)) {
                            hasFlat = true;
                            // Bridging step across air gap: requires crouching & placing bridge block.
                            // Higher cost than walking on existing floor so pathfinder prioritizes existing walkways.
                            addCandidate(open, costs, previous, target, current, flat, costs.get(current) + 3.5, penalties);
                        }
                    }
                    // 2. Step up 1 block (dy = 1)
                    Cell up = current.offset(dx, 1, dz);
                    if (!isOutOfBounds(up, start, maxDistance) && !closed.contains(up)
                            && probed.headroom(current.offset(0, 1, 0)) && probed.standable(up)) {
                        addCandidate(open, costs, previous, target, current, up, costs.get(current) + 1.45, penalties);
                    }
                    // 3. Drop down a ledge if flat ground is absent (dy < 0)
                    if (!hasFlat) {
                        for (int d = 1; d <= 16; d++) {
                            Cell fallSpace = current.offset(dx, -(d - 1), dz);
                            if (!probed.headroom(fallSpace)) break;
                            Cell landing = current.offset(dx, -d, dz);
                            if (isOutOfBounds(landing, start, maxDistance)) break;
                            if (probed.standable(landing)) {
                                if (closed.contains(landing)) break;
                                boolean landingInWater = probed.water(landing);
                                if (landingInWater || d <= 6) {
                                    double cost = costs.get(current) + (landingInWater
                                            ? 1.0 + d * 0.1
                                            : d <= 3 ? 1.0 + d * 0.25 : 2.0 + d * 0.5);
                                    addCandidate(open, costs, previous, target, current, landing, cost, penalties);
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }
        return List.of();
    }
}
