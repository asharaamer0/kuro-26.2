package dev.kuro.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Renders sleek dark rectangular buttons matching the in-game Glass UI theme,
 * with subtle rounded corners, translucent smoked glass fills, and smooth hover glow.
 */
public final class KuroButtonRenderer {
    private static final Map<Object, Float> HOVER_MAP = new WeakHashMap<>();
    private static long lastFrameNano = System.nanoTime();
    private static float currentDt = 0.016F;

    private KuroButtonRenderer() {}

    private static void tickTime() {
        long now = System.nanoTime();
        long diff = now - lastFrameNano;
        if (diff > 500_000L) { // update if at least 0.5ms elapsed
            currentDt = Math.min(0.08F, Math.max(0.001F, diff / 1.0E9F));
            lastFrameNano = now;
        }
    }

    public static float getSmoothHover(Object key, boolean target) {
        tickTime();
        Float current = HOVER_MAP.get(key);
        float cur = current != null ? current : (target ? 1.0F : 0.0F);
        float dest = target ? 1.0F : 0.0F;
        float next = cur + (dest - cur) * Math.min(1.0F, currentDt * 14.0F);
        HOVER_MAP.put(key, next);
        return next;
    }

    public static void renderButton(GuiGraphicsExtractor g, Object key, int x, int y, int w, int h, boolean active, boolean hovered, float alpha) {
        if (w <= 0 || h <= 0) return;

        float k = getSmoothHover(key, active && hovered);

        // Pure sleek black button with white hover illumination
        int bodyRgb = active ? mixRgb(0x101010, 0x222222, k) : 0x080808;
        float bodyAlpha = alpha * (active ? (0.85F + 0.10F * k) : 0.40F);

        int borderRgb = active ? mixRgb(0x303030, 0xFFFFFF, k) : 0x1A1A1A;
        float borderAlpha = alpha * (active ? (0.65F + 0.35F * k) : 0.25F);

        float glowStrength = active ? (k * 0.22F * alpha) : 0.0F;

        int radius = Math.min(4, Math.min(w / 4, h / 4));
        renderSleekRect(g, x, y, w, h, radius, bodyRgb, bodyAlpha, borderRgb, borderAlpha, glowStrength);
    }

    public static void renderSliderTrack(GuiGraphicsExtractor g, Object key, int x, int y, int w, int h, boolean active, boolean hovered, float alpha) {
        if (w <= 0 || h <= 0) return;

        float k = getSmoothHover(key, active && hovered);
        int bodyRgb = 0x080808;
        float bodyAlpha = alpha * 0.85F;

        int borderRgb = mixRgb(0x242424, 0x444444, k);
        float borderAlpha = alpha * (0.50F + 0.25F * k);

        int radius = Math.min(3, Math.min(w / 4, h / 4));
        renderSleekRect(g, x, y, w, h, radius, bodyRgb, bodyAlpha, borderRgb, borderAlpha, 0.0F);
    }

    public static void renderSliderHandle(GuiGraphicsExtractor g, Object key, int x, int y, int w, int h, boolean active, boolean hovered, float alpha) {
        if (w <= 0 || h <= 0) return;

        float k = getSmoothHover(key, active && hovered);
        int bodyRgb = mixRgb(0x222222, 0x333333, k);
        float bodyAlpha = alpha * 0.95F;

        int borderRgb = mixRgb(0x444444, 0xFFFFFF, k);
        float borderAlpha = alpha * (0.75F + 0.25F * k);
        float glowStrength = active ? (k * 0.20F * alpha) : 0.0F;

        int radius = Math.min(2, Math.min(w / 2, h / 4));
        renderSleekRect(g, x, y, w, h, radius, bodyRgb, bodyAlpha, borderRgb, borderAlpha, glowStrength);
    }

    /**
     * Renders a sleek rectangular button with subtle anti-aliased rounded corners.
     */
    public static void renderSleekRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r,
                                       int bodyRgb, float bodyAlpha,
                                       int borderRgb, float borderAlpha,
                                       float glowStrength) {
        if (r < 1) {
            int bCol = (Math.round(bodyAlpha * 255.0F) << 24) | bodyRgb;
            int brdCol = (Math.round(borderAlpha * 255.0F) << 24) | borderRgb;
            g.fill(x, y, x + w, y + h, bCol);
            g.outline(x, y, w, h, brdCol);
            return;
        }

        int centerBodyCol = (Math.round(bodyAlpha * 255.0F) << 24) | bodyRgb;
        int centerBorderCol = (Math.round(borderAlpha * 255.0F) << 24) | borderRgb;

        // 1. Center & side body fills
        g.fill(x + r, y, x + w - r, y + h, centerBodyCol);
        g.fill(x, y + r, x + r, y + h - r, centerBodyCol);
        g.fill(x + w - r, y + r, x + w, y + h - r, centerBodyCol);

        // 2. Straight edge borders
        g.fill(x + r, y, x + w - r, y + 1, centerBorderCol);             // Top
        g.fill(x + r, y + h - 1, x + w - r, y + h, centerBorderCol);     // Bottom
        g.fill(x, y + r, x + 1, y + h - r, centerBorderCol);             // Left
        g.fill(x + w - 1, y + r, x + w, y + h - r, centerBorderCol);     // Right

        // 3. Straight edge glow
        if (glowStrength > 0.01F) {
            int glowCol = (Math.round(glowStrength * 255.0F) << 24) | 0xFFFFFF;
            g.fill(x + r, y - 1, x + w - r, y, glowCol);
            g.fill(x + r, y + h, x + w - r, y + h + 1, glowCol);
            g.fill(x - 1, y + r, x, y + h - r, glowCol);
            g.fill(x + w, y + r, x + w + 1, y + h - r, glowCol);
        }

        // 4. Four anti-aliased rounded corners
        float rad = (float) r - 0.5F;

        // Top-Left corner
        renderCorner(g, x, x + r, y, y + r, x + r, y + r, rad, bodyRgb, bodyAlpha, borderRgb, borderAlpha, glowStrength);

        // Top-Right corner
        renderCorner(g, x + w - r, x + w, y, y + r, x + w - r, y + r, rad, bodyRgb, bodyAlpha, borderRgb, borderAlpha, glowStrength);

        // Bottom-Left corner
        renderCorner(g, x, x + r, y + h - r, y + h, x + r, y + h - r, rad, bodyRgb, bodyAlpha, borderRgb, borderAlpha, glowStrength);

        // Bottom-Right corner
        renderCorner(g, x + w - r, x + w, y + h - r, y + h, x + w - r, y + h - r, rad, bodyRgb, bodyAlpha, borderRgb, borderAlpha, glowStrength);
    }

    private static void renderCorner(GuiGraphicsExtractor g, int x0, int x1, int y0, int y1,
                                     float cx, float cy, float rad,
                                     int bodyRgb, float bodyAlpha,
                                     int borderRgb, float borderAlpha,
                                     float glowStrength) {
        for (int py = y0; py < y1; py++) {
            float dy = (py + 0.5F) - cy;
            float dy2 = dy * dy;

            for (int px = x0; px < x1; px++) {
                float dx = (px + 0.5F) - cx;
                float dist = (float) Math.sqrt(dx * dx + dy2);

                // Glow aura outside corner
                if (glowStrength > 0.01F && dist >= rad && dist < rad + 2.0F) {
                    float glowCov = (1.0F - (dist - rad) / 2.0F) * glowStrength;
                    if (glowCov > 0.005F) {
                        int gCol = (Math.round(glowCov * 255.0F) << 24) | 0xFFFFFF;
                        g.fill(px, py, px + 1, py + 1, gCol);
                    }
                }

                // Body fill with subpixel antialiasing
                if (dist < rad + 0.5F) {
                    float bodyCov = Math.min(1.0F, Math.max(0.0F, rad + 0.5F - dist));
                    if (bodyCov > 0.01F) {
                        int bCol = (Math.round(bodyCov * bodyAlpha * 255.0F) << 24) | bodyRgb;
                        g.fill(px, py, px + 1, py + 1, bCol);
                    }
                }

                // Border edge with subpixel antialiasing
                float edgeDist = Math.abs(dist - rad);
                if (edgeDist < 0.9F) {
                    float edgeCov = 1.0F - (edgeDist / 0.9F);
                    if (edgeCov > 0.01F) {
                        int brdCol = (Math.round(edgeCov * borderAlpha * 255.0F) << 24) | borderRgb;
                        g.fill(px, py, px + 1, py + 1, brdCol);
                    }
                }
            }
        }
    }

    private static int mixRgb(int rgb1, int rgb2, float t) {
        int r1 = (rgb1 >>> 16) & 0xFF, g1 = (rgb1 >>> 8) & 0xFF, b1 = rgb1 & 0xFF;
        int r2 = (rgb2 >>> 16) & 0xFF, g2 = (rgb2 >>> 8) & 0xFF, b2 = rgb2 & 0xFF;
        int r = Math.round(r1 + (r2 - r1) * t);
        int g = Math.round(g1 + (g2 - g1) * t);
        int b = Math.round(b1 + (b2 - b1) * t);
        return (r << 16) | (g << 8) | b;
    }
}
