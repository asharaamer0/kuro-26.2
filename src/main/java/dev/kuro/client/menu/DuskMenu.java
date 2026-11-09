package dev.kuro.client.menu;

import dev.kuro.client.gui.UiThemes;
import imgui.ImDrawList;
import imgui.ImFont;

import java.util.List;

public final class DuskMenu {
   private static final List<MenuAction> BOTTOM_ICONS = List.of(
      MenuAction.LANGUAGE, MenuAction.ACCESSIBILITY, MenuAction.OPTIONS
   );

   private DuskMenu() {}

   public static Layout layout(float width, float height, float s) {
      float titleH = 40.0F * s;
      float buttonH = 44.0F * s;
      float buttonGap = 10.0F * s;
      float extraQuitGap = 12.0F * s;
      float totalButtonsH = MenuAction.MAIN.size() * (buttonH + buttonGap) + extraQuitGap;
      float gapTitleToButtons = 32.0F * s;

      float totalH = titleH + gapTitleToButtons + totalButtonsH;
      float topY = Math.max(36.0F * s, (height - totalH) * 0.42F);
      float titleY = topY;
      float itemsY = titleY + titleH + gapTitleToButtons;

      return new Layout(width * 0.5F, topY, 0.0F, titleY, itemsY);
   }

   public static void render(MenuContext c) {
      ImDrawList dl = c.bg();
      float sw = c.width();
      float sh = c.height();
      float s = c.scale();
      Layout l = layout(sw, sh, s);

      // 1. Glass UI Backdrop
      DuskScene.draw(dl, sw, sh, s, c.time(), DuskScene.horizon(sh), l.cx(), l.sunY(), l.sunR(), c.lookX(), c.lookY());

      // 2. Animated "KURO" title
      DuskEmblem.draw(c, l.cx(), l.sunY(), 0.0F, l.nameY());

      // 3. Frosted Glass Buttons
      float y = l.itemsY();
      for (int i = 0; i < MenuAction.MAIN.size(); i++) {
         MenuAction action = MenuAction.MAIN.get(i);
         if (action == MenuAction.QUIT) {
            y += 12.0F * s;
         }
         float a = Math.max(0.5F, c.intro(0.2F + i * 0.06F));
         item(c, action, i, l.cx(), y, a);
         y += 54.0F * s;
      }

      // 4. Bottom Glass Utility Icons
      float a = Math.max(0.5F, c.intro(0.5F));
      float iconY = sh - 28.0F * s;
      float iconX = sw - 28.0F * s - (BOTTOM_ICONS.size() - 1) * 38.0F * s;

      for (int i = 0; i < BOTTOM_ICONS.size(); i++) {
         icon(c, BOTTOM_ICONS.get(i), iconX + i * 38.0F * s, iconY, a);
      }

      // 5. Bottom-Left Plain Text Credits Link
      creditsLink(c, 28.0F * s, iconY);
   }

   private static void creditsLink(MenuContext c, float x, float cy) {
      ImDrawList dl = c.bg();
      ImFont font = c.tagFont();
      float s = c.scale();
      String text = "Credits";
      float tw = c.textWidth(font, text);
      float th = c.textHeight(font);
      float ty = cy - th * 0.5F;

      float pad = 6.0F * s;
      boolean over = c.inside(x - pad, ty - pad, tw + pad * 2.0F, th + pad * 2.0F);
      float k = c.hover(MenuAction.KURO, over);

      if (over && c.clicked()) {
         c.activateCredits();
      }

      int col = DuskEmblem.mixColor(0xFF7A8496, 0xFFFFFFFF, k);
      c.text(dl, font, x, ty, col, 1.0F, text);

      if (k > 0.05F) {
         int lineCol = (Math.round(k * 220) << 24) | 0xFFFFFF;
         dl.addLine(x, ty + th + 1.0F * s, x + tw, ty + th + 1.0F * s, lineCol, Math.max(1.0F, 1.2F * s));
      }

      if (over) {
         c.tooltip("https://github.com/mrdino201/Kuro", x + tw * 0.5F, ty - 18.0F * s);
      }
   }

   private static void item(MenuContext c, MenuAction action, int index, float cx, float y, float a) {
      ImDrawList dl = c.bg();
      ImFont font = c.itemFont();
      float s = c.scale();
      float w = 280.0F * s;
      float h = 44.0F * s;
      float x = cx - w * 0.5F;
      boolean enabled = c.enabled(action);
      float k = c.control(action, x, y, w, h);

      DuskPill.frame(c, x, y, w, h, k, DuskEmblem.buttonPass(c, index), a);

      String label = action.label();
      float ty = y + (h - c.textHeight(font)) * 0.5F + 1.2F * s;
      float tx = cx - c.textWidth(font, label) * 0.5F;

      // Text color: crisp silver-white when idle -> deep sleek black when hovered over white button
      int idleColor = 0xFFE8EEF5;
      int hoverColor = 0xFF0D0F14;
      int color = enabled ? DuskEmblem.mixColor(idleColor, hoverColor, (float) Math.pow(k, 1.4)) : 0xFF718096;
      float alpha = (action == MenuAction.QUIT ? 0.88F + 0.12F * k : 0.92F + 0.08F * k) * a;

      // Drop shadow for idle legibility (fades out smoothly on hover so white button has razor sharp black text)
      float shadowAlpha = (1.0F - k) * 0.65F * a;
      if (shadowAlpha > 0.02F) {
         c.text(dl, font, tx + s, ty + 1.2F * s, 0x9003050A, shadowAlpha, label);
      }
      c.text(dl, font, tx, ty, color, alpha, label);
   }

   private static void icon(MenuContext c, MenuAction action, float cx, float cy, float a) {
      ImDrawList dl = c.bg();
      float s = c.scale();
      float r = 10.0F * s;
      float reach = 36.0F * s * 0.5F;
      float k = c.control(action, cx - reach, cy - reach, reach * 2.0F, reach * 2.0F);

      // Raw vector icon without circle disc/border: muted silver idle -> pure white hover
      int idleIconCol = 0xFF7A8496;
      int hotIconCol = 0xFFFFFFFF;
      int baseCol = DuskEmblem.mixColor(idleIconCol, hotIconCol, k);
      int col = (Math.round(((baseCol >>> 24) & 0xFF) * a) << 24) | (baseCol & 0x00FFFFFF);
      float thickness = Math.max(1.2F, (1.4F + 0.3F * k) * s);

      drawVectorIcon(dl, action, cx, cy, r, col, thickness);

      if (k > 0.5F) {
         String name = action.label();
         c.tooltip(name, Math.min(cx, c.width() - c.textWidth(c.tagFont(), name) * 0.5F - 18.0F * s), cy - reach - c.textHeight(c.tagFont()) - 14.0F * s);
      }
   }

   private static void drawVectorIcon(ImDrawList dl, MenuAction action, float cx, float cy, float r, int col, float th) {
      switch (action) {
         case LANGUAGE -> {
            dl.addCircle(cx, cy, r, col, 20, th);
            dl.addLine(cx - r, cy, cx + r, cy, col, th);
            dl.addLine(cx, cy - r, cx, cy + r, col, th);
         }
         case ACCESSIBILITY -> {
            dl.addCircle(cx, cy - r * 0.5F, r * 0.3F, col, 12, th);
            dl.addLine(cx - r * 0.7F, cy - r * 0.1F, cx + r * 0.7F, cy - r * 0.1F, col, th);
            dl.addLine(cx, cy - r * 0.1F, cx, cy + r * 0.5F, col, th);
            dl.addLine(cx, cy + r * 0.5F, cx - r * 0.5F, cy + r, col, th);
            dl.addLine(cx, cy + r * 0.5F, cx + r * 0.5F, cy + r, col, th);
         }
         case OPTIONS -> {
            dl.addCircle(cx, cy, r * 0.5F, col, 16, th);
            for (int i = 0; i < 8; i++) {
               double angle = i * Math.PI / 4.0;
               float x1 = cx + (float) Math.cos(angle) * r * 0.6F;
               float y1 = cy + (float) Math.sin(angle) * r * 0.6F;
               float x2 = cx + (float) Math.cos(angle) * r;
               float y2 = cy + (float) Math.sin(angle) * r;
               dl.addLine(x1, y1, x2, y2, col, th);
            }
         }
         default -> {}
      }
   }

   public record Layout(float cx, float topY, float sunY, float nameY, float itemsY) {
      public float sunR() {
         return 0.0F;
      }
   }
}
