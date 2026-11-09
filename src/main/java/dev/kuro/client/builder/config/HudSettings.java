package dev.kuro.client.builder.config;

/** Placement and content options for the movable on-screen HUD elements. */
public final class HudSettings {
    /** Per-element visibility; slots are also restored from the HUD editor. */
    public boolean arrayList = true;
    public boolean sessionInfo = true;
    public boolean worldInfo = true;
    public boolean autoBuilder = true;
    /** Which rows the session and world panels draw. */
    public boolean showPlaytime = true;
    public boolean showTotalPlaytime = false;
    public boolean showCoords = true;
    public boolean showFacing = true;
    public boolean showBiome = true;
    public boolean showDimension = true;
    public boolean showFps = true;
    /** Offsets from each element's default anchor, in GUI-scaled pixels. */
    public int listX, listY, infoX, infoY, worldX, worldY, builderX, builderY;
    /** Per-element size multipliers, set by dragging a corner in the HUD editor. */
    public double listScale = 1.0, infoScale = 1.0, worldScale = 1.0, builderScale = 1.0;

    public void sanitize() {
        listX = clampOffset(listX);
        listY = clampOffset(listY);
        infoX = clampOffset(infoX);
        infoY = clampOffset(infoY);
        worldX = clampOffset(worldX);
        worldY = clampOffset(worldY);
        builderX = clampOffset(builderX);
        builderY = clampOffset(builderY);
        listScale = clampScale(listScale);
        infoScale = clampScale(infoScale);
        worldScale = clampScale(worldScale);
        builderScale = clampScale(builderScale);
    }

    private static int clampOffset(int value) {
        return Math.clamp(value, -4096, 4096);
    }

    private static double clampScale(double value) {
        return Double.isFinite(value) ? Math.clamp(value, 0.5, 2.0) : 1.0;
    }
}
