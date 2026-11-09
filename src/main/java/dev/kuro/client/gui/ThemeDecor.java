package dev.kuro.client.gui;

import dev.kuro.client.util.ShaderUi;
import dev.kuro.client.util.ShaderUi.Mode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * The animated decoration each UI theme draws inside its glass panel: falling petals, snow, rising embers and
 * bubbles, twinkling stars, the scrolling grid, the Astolfo hearts, the Israel flag and the flower.
 *
 * <p>The same shapes the Glass menu uses, packaged so other themed windows (Kuro Builder) look like part of
 * the same menu instead of a flat dialog.
 */
final class ThemeDecor {
    private static final Identifier FLOWER = Identifier.fromNamespaceAndPath("kuro", "textures/gui/flower.png");

    private ThemeDecor() {}

    static void draw(GuiGraphicsExtractor g, UiThemes.UiTheme th, float x, float y, float w, float h, float time, float fade) {
        if (fade <= 0.01f) return;
        g.enableScissor(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h));
        try {
            int hot = 0xFF000000 | th.accent, soft = 0xFF000000 | th.accent2;
            switch (th.decor) {
                case HEARTS -> hearts(g, x, y, w, h, time, fade);
                case FLAG -> flag(g, x, y, w, h, fade);
                case FLOWER -> flower(g, th, x, y, w, h, fade);
                default -> ambient(g, th, x, y, w, h, time, fade, hot, soft);
            }
        } finally {
            g.disableScissor();
        }
    }

    // ------------------------------------------------------------------ decorations

    private static void ambient(GuiGraphicsExtractor g, UiThemes.UiTheme th, float x, float y, float w, float h, float time,
                                float fade, int hot, int soft) {
        switch (th.decor) {
            case SNOW -> {
                for (int i = 0; i < 30; i++) {
                    float yf = frac(rnd(i, 2) + time * (0.035f + 0.04f * rnd(i, 1)));
                    float px = x + w * frac(rnd(i, 3) + 0.03f * (float) Math.sin(time * 0.7f + i));
                    float edge = Math.min(1f, Math.min(yf, 1f - yf) * 6f);
                    ShaderUi.knob(g, px, y + h * yf, 1f + 1.8f * rnd(i, 4),
                            c(a(mix(0xFFFFFFFF, soft, 0.35f), Math.round((0x40 + 0x50 * rnd(i, 5)) * edge)), fade));
                }
            }
            case PETALS -> {
                for (int i = 0; i < 18; i++) {
                    float yf = frac(rnd(i, 2) + time * (0.03f + 0.03f * rnd(i, 1)));
                    float px = x + w * (frac(rnd(i, 3)) + 0.05f * (float) Math.sin(time * 0.9f + i * 1.7f + yf * 6f));
                    float py = y + h * yf, ang = time * 1.2f + i, len = 2.2f + 2f * rnd(i, 4);
                    float edge = Math.min(1f, Math.min(yf, 1f - yf) * 6f);
                    float dx = (float) Math.cos(ang) * len, dy = (float) Math.sin(ang) * len * 0.6f;
                    ShaderUi.line(g, px - dx, py - dy, px + dx, py + dy, 2.6f,
                            c(a(mix(soft, hot, rnd(i, 6)), Math.round(0x90 * edge)), fade));
                }
            }
            case EMBERS -> {
                for (int i = 0; i < 26; i++) {
                    float u = frac(rnd(i, 2) + time * (0.05f + 0.05f * rnd(i, 1)));
                    float px = x + w * (frac(rnd(i, 3)) + 0.025f * (float) Math.sin(time * 1.3f + i * 2.1f));
                    float py = y + h * (1f - u), r = 0.8f + 1.6f * rnd(i, 4);
                    float al = Math.min(1f, u * 8f) * (1f - u);
                    int col = mix(soft, hot, u);
                    ShaderUi.knob(g, px, py, r * 3f, c(a(col, Math.round(0x1C * al)), fade));
                    ShaderUi.knob(g, px, py, r, c(a(col, Math.round(0xC0 * al)), fade));
                }
            }
            case BUBBLES -> {
                for (int i = 0; i < 14; i++) {
                    float u = frac(rnd(i, 2) + time * (0.02f + 0.025f * rnd(i, 1)));
                    float px = x + w * (frac(rnd(i, 3)) + 0.02f * (float) Math.sin(time * 0.8f + i * 1.9f));
                    float py = y + h * (1f - u), r = 3f + 8f * rnd(i, 4);
                    float al = Math.min(1f, u * 8f) * Math.min(1f, (1f - u) * 5f);
                    ShaderUi.fancy(g, Mode.PILL_RING, px - r, py - r, 2f * r, 2f * r, ShaderUi.PILL_R,
                            c(a(soft, Math.round(0x58 * al)), fade));
                }
            }
            case STARS -> {
                for (int i = 0; i < 34; i++) {
                    float tw = 0.5f + 0.5f * (float) Math.sin(time * (0.8f + rnd(i, 1)) + i * 2.3f);
                    float px = x + w * rnd(i, 3), py = y + h * rnd(i, 4);
                    int al = Math.round(0x28 + 0x80 * tw);
                    if (i % 8 == 0) sparkle(g, px, py, 3.5f + 3f * rnd(i, 5) * (0.6f + 0.4f * tw), 1.1f, c(a(soft, al), fade));
                    else ShaderUi.knob(g, px, py, 0.7f + 1.1f * rnd(i, 5),
                            c(a(i % 3 == 0 ? hot : 0xFFFFFFFF, al), fade));
                }
            }
            case GRID -> {
                float hy = y + h * 0.60f, bh = h * 0.40f, cx = x + w / 2f;
                float scroll = frac(time * 0.22f);
                for (int k = 0; k < 9; k++) {
                    float f = (k + scroll) / 9f;
                    float gy = hy + bh * f * f;
                    ShaderUi.line(g, x, gy, x + w, gy, 1f, c(a(mix(hot, soft, f), Math.round(0x10 + 0x34 * f)), fade));
                }
                for (int j = -6; j <= 6; j++)
                    ShaderUi.line(g, cx + j * w * 0.018f, hy, cx + j * w * 0.17f, y + h, 1f,
                            c(a(mix(hot, soft, (j + 6) / 12f), 0x2C), fade));
                ShaderUi.line(g, x, hy, x + w, hy, 1.2f, c(a(hot, 0x44), fade));
            }
            default -> { }
        }
    }

    private static void flower(GuiGraphicsExtractor g, UiThemes.UiTheme th, float x, float y, float w, float h, float fade) {
        float fs = h * 0.82f, fcx = x + w * 0.25f, fcy = y + h * 0.75f;
        int tint = th.custom ? c(0x8CFFFFFF, fade)
                : th.light ? c(a(0xFF000000 | th.accent, 0x34), fade)
                : c(0x8C000000 | (mix(0xFFFFFFFF, 0xFF000000 | th.accent, 0.40f) & 0xFFFFFF), fade);
        var fm = g.pose();
        fm.pushMatrix();
        fm.translate(fcx - fs / 2f, fcy - fs / 2f);
        float fk = fs / 512f;
        fm.scale(fk, fk);
        g.blit(RenderPipelines.GUI_TEXTURED, FLOWER, 0, 0, 0f, 0f, 512, 512, 512, 512, 512, 512, tint);
        fm.popMatrix();
    }

    private static void hearts(GuiGraphicsExtractor g, float x, float y, float w, float h, float time, float fade) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 1.2f);
        for (int i = 0; i < 4; i++)
            heart(g, x + w * 0.25f, y + h * 0.80f, h * (0.46f - i * 0.07f), 2.2f, c(a(0xFFFF5FA8, 0x16 + i * 4), fade));
        heart(g, x + w * 0.86f, y + h * 0.86f, 26f, 1.6f, c(a(0xFFFF8CC6, 0x30), fade));
        heart(g, x + w * 0.62f, y + h * 0.94f, 12f, 1.2f, c(a(0xFFFF8CC6, 0x28), fade));
        sparkle(g, x + w * 0.90f, y + h * 0.12f, 9f + 2f * pulse, 1.4f, c(a(0xFFFFB3D9, 0x70), fade));
        sparkle(g, x + w * 0.72f, y + h * 0.06f, 5f, 1.1f, c(a(0xFFFFB3D9, 0x50), fade));
        sparkle(g, x + w * 0.30f, y + h * 0.93f, 6f + 1.5f * (1f - pulse), 1.1f, c(a(0xFFFFB3D9, 0x50), fade));
    }

    private static void flag(GuiGraphicsExtractor g, float x, float y, float w, float h, float fade) {
        int blue = c(0xFF0038B8, fade);
        float k = h / 220f, stripe = 25.5f * k, off = 25f * k;
        ShaderUi.fancy(g, Mode.PILL, x + 0.5f, y + off, w - 1f, stripe, 0f, blue);
        ShaderUi.fancy(g, Mode.PILL, x + 0.5f, y + h - off - stripe, w - 1f, stripe, 0f, blue);
        star(g, x + w / 2f, y + h / 2f, 50f * k - 2.75f * k, 5.5f * k, blue);
    }

    private static void heart(GuiGraphicsExtractor g, float cx, float cy, float size, float width, int col) {
        float px = 0, py = 0;
        for (int i = 0; i <= 36; i++) {
            double t = Math.PI * 2.0 * i / 36.0;
            float hx = (float) (16.0 * Math.pow(Math.sin(t), 3)) / 16f;
            float hy = -(float) (13.0 * Math.cos(t) - 5.0 * Math.cos(2 * t) - 2.0 * Math.cos(3 * t) - Math.cos(4 * t)) / 16f;
            float x = cx + hx * size, y = cy + hy * size;
            if (i > 0) ShaderUi.line(g, px, py, x, y, width, col);
            px = x; py = y;
        }
    }

    private static void sparkle(GuiGraphicsExtractor g, float cx, float cy, float r, float width, int col) {
        ShaderUi.line(g, cx - r, cy, cx + r, cy, width, col);
        ShaderUi.line(g, cx, cy - r, cx, cy + r, width, col);
        float d = r * 0.45f;
        ShaderUi.line(g, cx - d, cy - d, cx + d, cy + d, width * 0.8f, col);
        ShaderUi.line(g, cx - d, cy + d, cx + d, cy - d, width * 0.8f, col);
    }

    private static void star(GuiGraphicsExtractor g, float cx, float cy, float r, float width, int col) {
        for (int t = 0; t < 2; t++) {
            for (int i = 0; i < 3; i++) {
                double a1 = Math.toRadians((t == 0 ? -90 : 90) + i * 120);
                double a2 = Math.toRadians((t == 0 ? -90 : 90) + (i + 1) * 120);
                float x1 = cx + (float) Math.cos(a1) * r, y1 = cy + (float) Math.sin(a1) * r;
                float x2 = cx + (float) Math.cos(a2) * r, y2 = cy + (float) Math.sin(a2) * r;
                ShaderUi.line(g, x1, y1, x2, y2, width, col);
                ShaderUi.knob(g, x1, y1, width / 2f, col);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int c(int argb, float fade) {
        return (Mth.clamp(Math.round((argb >>> 24) * fade), 0, 255) << 24) | (argb & 0xFFFFFF);
    }

    private static int a(int argb, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (argb & 0xFFFFFF);
    }

    private static int mix(int c1, int c2, float t) {
        t = Mth.clamp(t, 0f, 1f);
        int a1 = c1 >>> 24, r1 = (c1 >> 16) & 255, g1 = (c1 >> 8) & 255, b1 = c1 & 255;
        int a2 = c2 >>> 24, r2 = (c2 >> 16) & 255, g2 = (c2 >> 8) & 255, b2 = c2 & 255;
        return (Math.round(a1 + (a2 - a1) * t) << 24) | (Math.round(r1 + (r2 - r1) * t) << 16)
                | (Math.round(g1 + (g2 - g1) * t) << 8) | Math.round(b1 + (b2 - b1) * t);
    }

    private static float rnd(int i, int k) {
        double v = Math.sin(i * 12.9898 + k * 78.233) * 43758.5453;
        return (float) (v - Math.floor(v));
    }

    private static float frac(float v) { return v - (float) Math.floor(v); }
}
