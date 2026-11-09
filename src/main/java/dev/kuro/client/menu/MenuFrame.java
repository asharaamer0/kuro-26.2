package dev.kuro.client.menu;

import dev.kuro.client.imgui.ImGuiManager;
import imgui.ImDrawList;
import imgui.ImFont;
import net.minecraft.client.Minecraft;

public class MenuFrame {
   private ImDrawList bg;
   private ImDrawList fg;
   private float width;
   private float height;
   private float scale;
   private float time;
   private float dt;
   private float mouseX;
   private float mouseY;
   private boolean clicked;
   private String tooltip;
   private float tooltipX;
   private float tooltipY;

   public void begin(ImDrawList bg, ImDrawList fg, float width, float height, float scale, float time, float dt, float mouseX, float mouseY, boolean clicked) {
      this.bg = bg;
      this.fg = fg;
      this.width = width;
      this.height = height;
      this.scale = scale;
      this.time = time;
      this.dt = dt;
      this.mouseX = mouseX;
      this.mouseY = mouseY;
      this.clicked = clicked;
      this.tooltip = null;
   }

   public ImDrawList bg() { return this.bg; }
   public ImDrawList fg() { return this.fg; }
   public float width()  { return this.width; }
   public float height() { return this.height; }
   public float scale()  { return this.scale; }
   public float time()   { return this.time; }
   public float dt()     { return this.dt; }
   public float mouseX() { return this.mouseX; }
   public float mouseY() { return this.mouseY; }
   public boolean clicked() { return this.clicked; }

   public float px(float px1080) {
      return px1080 * this.scale;
   }

   public boolean inside(float x, float y, float w, float h) {
      return this.mouseX >= x && this.mouseX <= x + w && this.mouseY >= y && this.mouseY <= y + h;
   }

   public void tooltip(String text, float centerX, float y) {
      this.tooltip = text;
      this.tooltipX = centerX;
      this.tooltipY = y;
   }

   public String pendingTooltip() { return this.tooltip; }
   public float tooltipX() { return this.tooltipX; }
   public float tooltipY() { return this.tooltipY; }

   public float intro(float delay) {
      float t = Math.max(0.0F, this.time - delay);
      return Math.min(1.0F, t * 2.5F);
   }

   public ImFont titleFont() { return ImGuiManager.getInstance().titleFont(); }
   public ImFont nameFont()  { return ImGuiManager.getInstance().titleFont(); }
   public ImFont itemFont()  { return ImGuiManager.getInstance().itemFont(); }
   public ImFont tagFont()   { return ImGuiManager.getInstance().tagFont(); }

   public void text(ImDrawList dl, ImFont font, float x, float y, int color, float alpha, String text) {
      int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, alpha)));
      int c = (a << 24) | (color & 0x00FFFFFF);
      if (font != null) {
         dl.addText(font, (int) font.getFontSize(), x, y, c, text);
      } else {
         dl.addText(x, y, c, text);
      }
   }

   public float textWidth(ImFont font, String text) {
      if (text == null || text.isEmpty()) return 0.0F;
      if (font != null) {
         try {
            imgui.ImVec2 sz = new imgui.ImVec2();
            imgui.ImGui.pushFont(font);
            imgui.ImGui.calcTextSize(sz, text);
            imgui.ImGui.popFont();
            return sz.x;
         } catch (Throwable ignored) {}
      }
      return font != null ? font.getFontSize() * text.length() * 0.55F : 0.0F;
   }

   public float textHeight(ImFont font) {
      if (font != null) {
         try {
            imgui.ImVec2 sz = new imgui.ImVec2();
            imgui.ImGui.pushFont(font);
            imgui.ImGui.calcTextSize(sz, "Ag");
            imgui.ImGui.popFont();
            return sz.y;
         } catch (Throwable ignored) {}
      }
      return font != null ? font.getFontSize() : this.px(16.0F);
   }

   public float spacedWidth(ImFont font, String text, float spacing) {
      if (text == null || text.isEmpty()) return 0.0F;
      float total = 0.0F;
      for (int i = 0; i < text.length(); i++) {
         total += this.textWidth(font, String.valueOf(text.charAt(i)));
      }
      return total + (text.length() - 1) * spacing;
   }

   public void spacedText(ImDrawList dl, ImFont font, float x, float y, float spacing, int color, float alpha, String text) {
      if (text == null || text.isEmpty()) return;
      float curX = x;
      for (int i = 0; i < text.length(); i++) {
         String glyph = String.valueOf(text.charAt(i));
         this.text(dl, font, curX, y, color, alpha, glyph);
         curX += this.textWidth(font, glyph) + spacing;
      }
   }

   public String displayName() {
      try {
         return Minecraft.getInstance().getUser().getName();
      } catch (Exception e) {
         return "Player";
      }
   }
}
