package dev.kuro.client.menu;

import dev.kuro.client.gui.UiThemes;
import imgui.ImDrawList;

/**
 * Renders the in-game Glass UI backdrop on the custom menu:
 * Synchronizes dynamically with the active UI theme from in-game,
 * including tinted frosted background gradients, ambient accent glow,
 * the signature dotted grid matrix, and the theme's animated particle suite
 * (Snow, Petals, Embers, Bubbles, Stars, Cyber Grid, Hearts, and Ambient motes).
 */
public final class DuskScene {
   public static final float HORIZON = 0.72F;

   private DuskScene() {}

   public static float horizon(float height) {
      return height * 0.72F;
   }

   public static void draw(
      ImDrawList dl, float width, float height, float scale, float time, float horizon, float sunX, float sunY, float sunRadius, float lookX, float lookY
   ) {
      // Solid pure black background (Kuro)
      dl.addRectFilled(0.0F, 0.0F, width, height, 0xFF000000);
   }

   private static float rnd(int i, int k) {
      double v = Math.sin(i * 12.9898 + k * 78.233) * 43758.5453;
      return (float) (v - Math.floor(v));
   }

   private static float frac(float v) {
      return v - (float) Math.floor(v);
   }

   // ------------------------------------------------------------------ Dotted Grid
   private static void drawDottedGrid(ImDrawList dl, float width, float height, float scale, float cx, float cy, int accent) {
      float pitch = Math.max(24.0F * scale, 20.0F);
      float dotR = Math.max(1.0F, 1.1F * scale);
      float maxDistSq = (width * width + height * height) * 0.35F;

      int cols = (int) Math.ceil(width / pitch) + 1;
      int rows = (int) Math.ceil(height / pitch) + 1;
      int dotBase = DuskEmblem.mixColor(0x8CA0D0, accent, 0.25F);

      for (int c = 0; c <= cols; c++) {
         float x = c * pitch;
         float dx = x - cx;
         float dxSq = dx * dx;

         for (int r = 0; r <= rows; r++) {
            float y = r * pitch;
            float dy = y - cy;
            float distSq = dxSq + dy * dy;

            float falloff = Math.max(0.0F, 1.0F - (distSq / maxDistSq));
            if (falloff <= 0.02F) continue;

            int alpha = Math.round(falloff * 42.0F);
            int col = (alpha << 24) | (dotBase & 0x00FFFFFF);
            dl.addCircleFilled(x, y, dotR, col, 6);
         }
      }
   }

   // ------------------------------------------------------------------ SNOW (Frost theme)
   private static void drawSnow(ImDrawList dl, float width, float height, float scale, float time, UiThemes.UiTheme th) {
      int soft = 0xFF000000 | th.accent2;
      for (int i = 0; i < 45; i++) {
         float yf = frac(rnd(i, 2) + time * (0.025F + 0.035F * rnd(i, 1)));
         float x = (rnd(i, 3) * width + (float) Math.sin(time * 0.7F + i * 1.5F) * 40.0F * scale + width) % width;
         float y = yf * height;
         float edge = Math.min(1.0F, Math.min(yf, 1.0F - yf) * 6.0F);
         if (edge <= 0.05F) continue;

         float r = (1.2F + 2.2F * rnd(i, 4)) * scale;
         int al = Math.round((0x45 + 0x65 * rnd(i, 5)) * edge);
         int col = (al << 24) | (DuskEmblem.mixColor(0xFFFFFF, soft, 0.35F) & 0x00FFFFFF);
         dl.addCircleFilled(x, y, r, col, 10);
      }
   }

   // ------------------------------------------------------------------ PETALS (Sakura themes)
   private static void drawPetals(ImDrawList dl, float width, float height, float scale, float time, UiThemes.UiTheme th) {
      int hot = 0xFF000000 | th.accent, soft = 0xFF000000 | th.accent2;
      for (int i = 0; i < 30; i++) {
         float yf = frac(rnd(i, 2) + time * (0.024F + 0.028F * rnd(i, 1)));
         float x = (rnd(i, 3) * width + (float) Math.sin(time * 0.85F + i * 1.6F + yf * 5.0F) * 55.0F * scale + width) % width;
         float y = yf * height;
         float edge = Math.min(1.0F, Math.min(yf, 1.0F - yf) * 6.0F);
         if (edge <= 0.05F) continue;

         float ang = time * 1.25F + i * 1.7F;
         float len = (3.5F + 3.0F * rnd(i, 4)) * scale;
         float dx = (float) Math.cos(ang) * len;
         float dy = (float) Math.sin(ang) * len * 0.65F;

         int al = Math.round(155.0F * edge);
         int base = DuskEmblem.mixColor(soft, hot, rnd(i, 6));
         int col = (al << 24) | (base & 0x00FFFFFF);
         dl.addLine(x - dx, y - dy, x + dx, y + dy, col, 3.2F * scale);
      }
   }

   // ------------------------------------------------------------------ EMBERS (Ember theme)
   private static void drawEmbers(ImDrawList dl, float width, float height, float scale, float time, UiThemes.UiTheme th) {
      int hot = 0xFF000000 | th.accent, soft = 0xFF000000 | th.accent2;
      for (int i = 0; i < 38; i++) {
         float u = frac(rnd(i, 2) + time * (0.045F + 0.045F * rnd(i, 1)));
         float yf = 1.0F - u;
         float x = (rnd(i, 3) * width + (float) Math.sin(time * 1.3F + i * 2.1F) * 35.0F * scale + width) % width;
         float y = yf * height;
         float al = Math.min(1.0F, u * 8.0F) * (1.0F - u);
         if (al <= 0.02F) continue;

         float r = (1.2F + 2.0F * rnd(i, 4)) * scale;
         int base = DuskEmblem.mixColor(soft, hot, u);

         // Soft halo + bright core
         int haloCol = (Math.round(0x28 * al) << 24) | (base & 0x00FFFFFF);
         int coreCol = (Math.round(0xD0 * al) << 24) | (base & 0x00FFFFFF);
         dl.addCircleFilled(x, y, r * 2.8F, haloCol, 12);
         dl.addCircleFilled(x, y, r, coreCol, 8);
      }
   }

   // ------------------------------------------------------------------ BUBBLES (Ocean theme)
   private static void drawBubbles(ImDrawList dl, float width, float height, float scale, float time, UiThemes.UiTheme th) {
      int soft = 0xFF000000 | th.accent2;
      for (int i = 0; i < 22; i++) {
         float u = frac(rnd(i, 2) + time * (0.02F + 0.024F * rnd(i, 1)));
         float yf = 1.0F - u;
         float x = (rnd(i, 3) * width + (float) Math.sin(time * 0.8F + i * 1.9F) * 30.0F * scale + width) % width;
         float y = yf * height;
         float al = Math.min(1.0F, u * 8.0F) * Math.min(1.0F, (1.0F - u) * 5.0F);
         if (al <= 0.02F) continue;

         float r = (4.0F + 9.0F * rnd(i, 4)) * scale;
         int ringAlpha = Math.round(0x60 * al);
         int ringCol = (ringAlpha << 24) | (soft & 0x00FFFFFF);
         dl.addCircle(x, y, r, ringCol, 24, 1.4F * scale);

         // Specular highlight glint
         int glintAlpha = Math.round(0x80 * al);
         int glintCol = (glintAlpha << 24) | 0xFFFFFF;
         dl.addCircleFilled(x + r * 0.35F, y - r * 0.35F, r * 0.22F, glintCol, 8);
      }
   }

   // ------------------------------------------------------------------ STARS (Midnight, Amethyst)
   private static void drawStars(ImDrawList dl, float width, float height, float scale, float time, UiThemes.UiTheme th) {
      int hot = 0xFF000000 | th.accent, soft = 0xFF000000 | th.accent2;
      for (int i = 0; i < 50; i++) {
         float tw = 0.5F + 0.5F * (float) Math.sin(time * (0.9F + rnd(i, 1) * 1.4F) + i * 2.3F);
         float x = rnd(i, 3) * width;
         float y = rnd(i, 4) * height;
         int al = Math.round(0x28 + 0x85 * tw);

         if (i % 6 == 0) {
            float r = (3.5F + 3.0F * rnd(i, 5) * (0.6F + 0.4F * tw)) * scale;
            drawSparkle(dl, x, y, r, 1.2F * scale, (al << 24) | (soft & 0x00FFFFFF));
         } else {
            float r = (0.9F + 1.2F * rnd(i, 5)) * scale;
            int col = (i % 2 == 0) ? hot : 0xFFFFFF;
            dl.addCircleFilled(x, y, r, (al << 24) | (col & 0x00FFFFFF), 8);
         }
      }
   }

   private static void drawSparkle(ImDrawList dl, float cx, float cy, float r, float w, int col) {
      dl.addLine(cx - r, cy, cx + r, cy, col, w);
      dl.addLine(cx, cy - r, cx, cy + r, col, w);
      float d = r * 0.45F;
      dl.addLine(cx - d, cy - d, cx + d, cy + d, col, w * 0.8F);
      dl.addLine(cx - d, cy + d, cx + d, cy - d, col, w * 0.8F);
   }

   // ------------------------------------------------------------------ GRID (Cyber theme)
   private static void drawCyberGrid(ImDrawList dl, float width, float height, float scale, float time, UiThemes.UiTheme th) {
      int hot = 0xFF000000 | th.accent, soft = 0xFF000000 | th.accent2;
      float hy = height * 0.60F;
      float bh = height * 0.40F;
      float cx = width * 0.5F;
      float scroll = frac(time * 0.22F);

      // Transverse lines
      for (int k = 0; k < 10; k++) {
         float f = (k + scroll) / 10.0F;
         float y = hy + bh * f * f;
         int base = DuskEmblem.mixColor(hot, soft, f);
         int al = Math.round(0x18 + 0x50 * f);
         dl.addLine(0.0F, y, width, y, (al << 24) | (base & 0x00FFFFFF), 1.2F * scale);
      }

      // Perspective fan lines
      for (int j = -8; j <= 8; j++) {
         float xTop = cx + j * width * 0.016F;
         float xBot = cx + j * width * 0.16F;
         int base = DuskEmblem.mixColor(hot, soft, (j + 8) / 16.0F);
         dl.addLine(xTop, hy, xBot, height, (0x35 << 24) | (base & 0x00FFFFFF), 1.2F * scale);
      }

      // Horizon line
      dl.addLine(0.0F, hy, width, hy, (0x65 << 24) | (hot & 0x00FFFFFF), 1.6F * scale);
   }

   // ------------------------------------------------------------------ HEARTS (Astolfo theme)
   private static void drawHearts(ImDrawList dl, float width, float height, float scale, float time, UiThemes.UiTheme th) {
      int hot = 0xFF000000 | th.accent, soft = 0xFF000000 | th.accent2;

      // 1. Large background ambient heart pulsing softly in lower left
      float pulse = 0.5F + 0.5F * (float) Math.sin(time * 1.2F);
      float bigR = (110.0F + 15.0F * pulse) * scale;
      drawHeart(dl, width * 0.22F, height * 0.75F, bigR, 2.5F * scale, (0x22 << 24) | (hot & 0x00FFFFFF));
      drawHeart(dl, width * 0.22F, height * 0.75F, bigR * 0.82F, 1.8F * scale, (0x18 << 24) | (soft & 0x00FFFFFF));

      // 2. Floating smaller hearts rising upwards
      for (int i = 0; i < 14; i++) {
         float u = frac(rnd(i, 2) + time * (0.02F + 0.025F * rnd(i, 1)));
         float yf = 1.0F - u;
         float x = (rnd(i, 3) * width + (float) Math.sin(time * 0.9F + i * 2.0F) * 35.0F * scale + width) % width;
         float y = yf * height;
         float al = Math.min(1.0F, u * 8.0F) * Math.min(1.0F, (1.0F - u) * 4.0F);
         if (al <= 0.05F) continue;

         float r = (10.0F + 12.0F * rnd(i, 4)) * scale;
         int col = (Math.round(0x48 * al) << 24) | (soft & 0x00FFFFFF);
         drawHeart(dl, x, y, r, 1.4F * scale, col);
      }
   }

   private static void drawHeart(ImDrawList dl, float cx, float cy, float size, float w, int col) {
      float px0 = 0, py0 = 0;
      for (int i = 0; i <= 28; i++) {
         double t = Math.PI * 2.0 * i / 28.0;
         float hx = (float) (16.0 * Math.pow(Math.sin(t), 3)) / 16.0F;
         float hy = -(float) (13.0 * Math.cos(t) - 5.0 * Math.cos(2 * t) - 2.0 * Math.cos(3 * t) - Math.cos(4 * t)) / 16.0F;
         float x = cx + hx * size;
         float y = cy + hy * size;
         if (i > 0) dl.addLine(px0, py0, x, y, col, w);
         px0 = x;
         py0 = y;
      }
   }

   // ------------------------------------------------------------------ AMBIENT MOTES (Default / Flower themes)
   private static void drawAmbientMotes(ImDrawList dl, float width, float height, float scale, float time, int hot, int soft) {
      for (int i = 0; i < 28; i++) {
         float u = rnd(i, 1);
         float v = rnd(i, 2);
         float yf = (v + time * (0.015F + 0.020F * u)) % 1.0F;
         float x = (u * width + (float) Math.sin(time * 0.65F + i * 1.5F) * 40.0F * scale + width) % width;
         float y = (1.0F - yf) * height;

         float edgeFade = Math.min(1.0F, Math.min(yf, 1.0F - yf) * 5.0F);
         if (edgeFade <= 0.05F) continue;

         int col = (i % 2 == 0) ? hot : soft;
         int al = Math.round(edgeFade * 42.0F);
         int argb = (al << 24) | (col & 0x00FFFFFF);
         float radius = (1.2F + 1.8F * u) * scale;

         dl.addCircleFilled(x, y, radius, argb, 8);
      }
   }

   // ------------------------------------------------------------------ FLOWER (Classic, Sunset, Emerald, Gold, Daylight)
   private static void drawFlower(ImDrawList dl, float width, float height, float scale, UiThemes.UiTheme th, int accent) {
      int texId = dev.kuro.client.imgui.ImGuiTexture.load("/assets/kuro/textures/gui/flower.png");
      if (texId == 0) return;

      float size = Math.min(width, height) * 0.76F;
      float cx = width * 0.24F;
      float cy = height * 0.74F;
      float x0 = cx - size * 0.5F;
      float y0 = cy - size * 0.5F;
      float x1 = cx + size * 0.5F;
      float y1 = cy + size * 0.5F;

      int a = th.custom ? 0x48 : (th.light ? 0x22 : 0x3E);
      int rgb = th.custom ? 0xFFFFFF : DuskEmblem.mixColor(0xFFFFFF, accent, 0.40F);
      int r = (rgb >> 16) & 0xFF;
      int g = (rgb >> 8) & 0xFF;
      int b = rgb & 0xFF;
      int col = (a << 24) | (b << 16) | (g << 8) | r;

      dl.addImage(texId, x0, y0, x1, y1, 0.0F, 0.0F, 1.0F, 1.0F, col);
   }

   // ------------------------------------------------------------------ FLAG (Israel theme)
   private static void drawFlag(ImDrawList dl, float width, float height, float scale) {
      int blue = 0x800038B8;
      float k = height / 220.0F;
      float stripe = 25.5F * k;
      float off = 25.0F * k;
      dl.addRectFilled(0.0F, off, width, off + stripe, blue);
      dl.addRectFilled(0.0F, height - off - stripe, width, height - off, blue);
   }

   private static void vignette(ImDrawList dl, float width, float height) {
      float edgeX = Math.min(width * 0.25F, 240.0F);
      float edgeY = Math.min(height * 0.25F, 200.0F);
      int dark = 0xB003050B;
      int clear = 0x00000000;

      // Left & Right
      dl.addRectFilledMultiColor(0.0F, 0.0F, edgeX, height, dark, clear, clear, dark);
      dl.addRectFilledMultiColor(width - edgeX, 0.0F, width, height, clear, dark, dark, clear);

      // Top & Bottom
      dl.addRectFilledMultiColor(0.0F, 0.0F, width, edgeY, dark, dark, clear, clear);
      dl.addRectFilledMultiColor(0.0F, height - edgeY, width, height, clear, clear, dark, dark);
   }

   public static void glow(ImDrawList dl, float cx, float cy, float radius, int argb, float alpha) {
      int layers = 14;
      for (int i = 0; i < layers; i++) {
         float f = 1.0F - (float) i / layers;
         int a = Math.round((argb >>> 24 & 0xFF) * (alpha / layers * 2.0F));
         int col = (a << 24) | (argb & 0x00FFFFFF);
         dl.addCircleFilled(cx, cy, radius * f, col, 36);
      }
   }
}
