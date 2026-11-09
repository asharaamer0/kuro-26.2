package dev.kuro.client.builder.config;

public final class PlayerEspSettings {
    public boolean enabled = false;
    public boolean favorite = false;
    public boolean throughWalls = true;
    public boolean distanceFade = true;
    public RenderMode mode = RenderMode.BOTH;
    public double range = 128;
    public double outlineOpacity = 0.9;
    public double fillOpacity = 0.20;
    public double lineWidth = 1.5;
    public int color = 0xFF4455;
    public boolean self = false;
    public boolean box2d = true;
    public boolean box3d = false;
    public boolean healthBar = true;
    public boolean gradientHealth = true;
    public boolean boxGuiColor = true;
    public boolean nametags = true;
    public boolean nametagGuiColor = true;
    public boolean nametagHealth = true;
    public boolean nametagDistance = true;

    public void sanitize() {
        if (mode == null) mode = RenderMode.BOTH;
        range = clamp(range, 16, 256, 128);
        outlineOpacity = clamp(outlineOpacity, 0, 1, 0.9);
        fillOpacity = clamp(fillOpacity, 0, 1, 0.20);
        lineWidth = clamp(lineWidth, 0.5, 5, 1.5);
        color &= 0xFFFFFF;
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
