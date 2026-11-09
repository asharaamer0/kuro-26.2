package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Sharp text for small UI: the string is laid out on a grid M times finer than a GUI pixel (drawn M times bigger, then
 * scaled back down), so every glyph lands on its own screen pixel instead of on a whole GUI pixel. Coordinates and sizes
 * are in ordinary GUI pixels, so it is a drop-in replacement for SdfText.draw / SdfText.width.
 */
public final class FineText {
    private FineText() {}

    /** Fine-grid factor: 4 sub-steps per GUI pixel is finer than a screen pixel at any normal GUI scale. */
    public static final int M = 4;

    public static void draw(GuiGraphicsExtractor g, SdfText.Face face, String s, float x, float y, float size, int argb) {
        if (s == null || s.isEmpty()) return;
        double gs = scale();
        var mat = g.pose();
        // start the string on a whole screen pixel, even when the menu itself is drawn scaled
        float a = mat.m00(), d = mat.m11(), tx = mat.m20(), ty = mat.m21();
        float sx = a == 0f ? x : (float) ((Math.round((a * x + tx) * gs) / gs - tx) / a);
        float sy = d == 0f ? y : (float) ((Math.round((d * y + ty) * gs) / gs - ty) / d);
        mat.pushMatrix();
        try {
            mat.translate(sx, sy);
            mat.scale(1f / M, 1f / M);
            SdfText.draw(g, face, s, 0f, 0f, size * M, argb);
        } finally {
            mat.popMatrix();
        }
    }

    public static float width(SdfText.Face face, String s, float size) {
        return SdfText.width(face, s, size * M) / M;
    }

    /** Runs {@code body} in fine units: coordinates passed to g.fill / Ui.* inside are 1/M GUI pixel each. */
    public static void fine(GuiGraphicsExtractor g, Runnable body) {
        var mat = g.pose();
        mat.pushMatrix();
        try {
            mat.scale(1f / M, 1f / M);
            body.run();
        } finally {
            mat.popMatrix();
        }
    }

    private static double scale() {
        var w = Minecraft.getInstance().getWindow();
        return w == null ? 1.0 : Math.max(1.0, w.getGuiScale());
    }
}
