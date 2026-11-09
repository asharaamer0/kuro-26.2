package dev.kuro.client.menu;

import dev.kuro.client.gui.UiThemes;
import imgui.ImDrawList;
import imgui.ImFont;

public final class DuskEmblem {
   private static final String NAME = "KURO";
   private static final float NAME_TRACK = 4.0F;

   private DuskEmblem() {}

   public static void draw(MenuContext c, float cx, float cy, float size, float nameY) {
      float t = c.time();
      float cycle = t < 1.1F ? -1.0F : (t - 1.1F) % 8.0F;

      name(c, cx, nameY, cycle);
   }

   public static float buttonPass(MenuContext c, int index) {
      float t = c.time();
      float delay = 0.5F + index * 0.08F;
      return Math.max(0.0F, Math.min(1.0F, (t - delay) * 2.0F));
   }

   private static void name(MenuContext c, float cx, float y, float cycle) {
      ImDrawList dl = c.bg();
      ImFont font = c.titleFont();
      float s = c.scale();
      float track = NAME_TRACK * s;
      float width = c.spacedWidth(font, NAME, track);
      float left = cx - width * 0.5F;
      float light = cycle < 0.0F ? -1.0F : (cycle - 0.55F) / 0.8F;
      float x = left;

      for (int i = 0; i < NAME.length(); i++) {
         String glyph = String.valueOf(NAME.charAt(i));
         float w = c.textWidth(font, glyph);
         float a = Math.max(0.5F, c.intro(0.1F + i * 0.04F));
         float ly = y;
         float at = (x + w * 0.5F - left) / Math.max(1.0F, width);
         float k = !(light < 0.0F) && !(light > 1.0F) ? smooth(Math.max(0.0F, 1.0F - Math.abs(light * 1.4F - 0.2F - at) / 0.16F)) : 0.0F;

         // Deep shadow
         c.text(dl, font, x + 1.5F * s, ly + 2.0F * s, 0xFF020408, 0.85F * a, glyph);
         // Glyph with silver-white light sweep and subtle accent reflection
         int color = mixColor(0xFFD4E2F5, 0xFFFFFFFF, k);
         c.text(dl, font, x, ly, color, a, glyph);
         x += w + track;
      }
   }

   public static float smooth(float x) {
      x = Math.max(0.0F, Math.min(1.0F, x));
      return x * x * (3.0F - 2.0F * x);
   }

   public static int mixColor(int from, int to, float k) {
      k = Math.max(0.0F, Math.min(1.0F, k));
      int a = Math.round((from >>> 24 & 0xFF) + ((to >>> 24 & 0xFF) - (from >>> 24 & 0xFF)) * k);
      int r = Math.round((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * k);
      int g = Math.round((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * k);
      int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
      return a << 24 | r << 16 | g << 8 | b;
   }
}
