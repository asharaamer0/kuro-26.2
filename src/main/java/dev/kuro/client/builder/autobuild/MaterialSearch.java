package dev.kuro.client.builder.autobuild;

/**
 * Bounded iteration over the positions a material search considers: a horizontal disc of
 * {@code radius} blocks around a centre, clamped vertically. Pure geometry keeps coverage and
 * termination independently testable, and the search never allocates a cursor for the caller.
 */
public final class MaterialSearch {
    private final int centreX, centreY, centreZ, radius;
    private final int minX, maxX, minY, maxY, minZ, maxZ;
    private int ix, iy, iz, x, y, z, visited;

    public MaterialSearch(int centreX, int centreY, int centreZ, int radius, int vertical) {
        if (radius < 0 || vertical < 0) throw new IllegalArgumentException("Search radius and vertical range must not be negative");
        this.centreX = centreX; this.centreY = centreY; this.centreZ = centreZ; this.radius = radius;
        minX = centreX - radius; maxX = centreX + radius;
        minY = centreY - vertical; maxY = centreY + vertical;
        minZ = centreZ - radius; maxZ = centreZ + radius;
        ix = minX; iy = minY; iz = minZ;
        x = minX; y = minY; z = minZ;
    }

    /** Advances to the next in-range position and returns false once the search is exhausted. */
    public boolean next() {
        while (ix <= maxX) {
            int candidateX = ix, candidateY = iy, candidateZ = iz;
            if (iz < maxZ) iz++;
            else if (iy < maxY) { iy++; iz = minZ; }
            else { ix++; iy = minY; iz = minZ; }
            visited++;
            if (contains(candidateX, candidateY, candidateZ)) {
                x = candidateX; y = candidateY; z = candidateZ;
                return true;
            }
        }
        return false;
    }

    /** True for positions on the horizontal disc at any allowed height. */
    public boolean contains(int posX, int posY, int posZ) {
        if (posY < minY || posY > maxY) return false;
        int dx = posX - centreX, dz = posZ - centreZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** Every position in the enclosing box, which bounds the work performed by {@link #next()}. */
    public int size() { return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1); }

    public int visited() { return visited; }
    public int centreX() { return centreX; }
    public int centreY() { return centreY; }
    public int centreZ() { return centreZ; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
}
