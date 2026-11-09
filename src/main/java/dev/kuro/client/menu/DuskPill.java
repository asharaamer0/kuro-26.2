package dev.kuro.client.menu;

import dev.kuro.client.gui.UiThemes;
import imgui.ImDrawList;
import imgui.ImFont;

/**
 * Renders frosted glass buttons matching the in-game Glass UI theme,
 * with specular glass sheen, lit glass edges, and accent hover glow.
 */
public final class DuskPill {
   private DuskPill() {}

   public static void frame(MenuFrame c, float x, float y, float w, float h, float hover, float pass, float a) {
      ImDrawList dl = c.bg();
      float s = c.scale();
      float r = Math.max(4.0F, Math.min(6.0F * s, h * 0.5F));

      // 1. Soft white hover glow aura behind the glass
      if (hover > 0.01F) {
         int glowAlpha1 = Math.round(0.18F * hover * a * 255);
         int glowAlpha2 = Math.round(0.35F * hover * a * 255);
         int glowCol1 = (glowAlpha1 << 24) | 0xFFFFFF;
         int glowCol2 = (glowAlpha2 << 24) | 0xFFFFFF;

         dl.addRect(x - 3.0F * s, y - 3.0F * s, x + w + 3.0F * s, y + h + 3.0F * s, glowCol1, r + 2.0F * s, 0, 4.0F * s);
         dl.addRect(x - 1.5F * s, y - 1.5F * s, x + w + 1.5F * s, y + h + 1.5F * s, glowCol2, r + 1.0F * s, 0, 2.0F * s);
      }

      // 2. Button body: neutral dark glass when idle -> pure crisp white when hovered
      int idleBody = (Math.round(0.72F * a * 255) << 24) | 0x121212;
      int hotBody = (Math.round(1.0F * a * 255) << 24) | 0xFFFFFF;
      int bodyCol = DuskEmblem.mixColor(idleBody, hotBody, hover);
      dl.addRectFilled(x, y, x + w, y + h, bodyCol, r);

      // 3. Top glass specular sheen (fades out as button turns white)
      if (hover < 0.99F) {
         int sheenAlpha = Math.round(0.14F * (1.0F - hover) * a * 255);
         int topSheen = (sheenAlpha << 24) | 0xFFFFFF;
         int clearSheen = 0x00FFFFFF;
         dl.addRectFilledMultiColor(x + r * 0.5F, y, x + w - r * 0.5F, y + h * 0.42F, topSheen, topSheen, clearSheen, clearSheen);
      }

      // 4. Lit glass edge (subtle idle glass outline -> pure white on hover)
      int idleEdge = 0x38FFFFFF;
      int hotEdge = (Math.round(1.0F * a * 255) << 24) | 0xFFFFFF;
      int borderCol = DuskEmblem.mixColor(idleEdge, hotEdge, hover);
      dl.addRect(x, y, x + w, y + h, borderCol, r, 0, Math.max(1.0F, 1.2F * s));
   }

   public static void halo(MenuFrame c, ImFont font, float x, float y, float spacing, float intensity, String text) {
      if (intensity <= 0.01F || text == null) return;
      ImDrawList dl = c.bg();
      float s = c.scale();
      int accent = UiThemes.accent();
      int col = (Math.round(intensity * 0.20F * 255) << 24) | (accent & 0x00FFFFFF);
      for (float ox = -s; ox <= s; ox += s) {
         for (float oy = -s; oy <= s; oy += s) {
            if (ox != 0 || oy != 0) {
               c.spacedText(dl, font, x + ox, y + oy, spacing, col, 1.0F, text);
            }
         }
      }
   }
}
