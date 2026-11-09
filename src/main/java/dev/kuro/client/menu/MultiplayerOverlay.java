package dev.kuro.client.menu;

import dev.kuro.client.gui.UiThemes;
import dev.kuro.client.imgui.ImGuiManager;
import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.ImGuiIO;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;

public final class MultiplayerOverlay {
   private final JoinMultiplayerScreen screen;
   private final MenuFrame frame = new MenuFrame();
   private long shownAt;

   public MultiplayerOverlay(JoinMultiplayerScreen screen) {
      this.screen = screen;
   }

   public void show() {
      this.shownAt = System.nanoTime();
      ImGuiManager.getInstance().activate(this::render);
   }

   public void hide() {
      ImGuiManager.getInstance().deactivate();
   }

   private void render() {
      ImGuiIO io = ImGui.getIO();
      float width = io.getDisplaySizeX();
      float height = io.getDisplaySizeY();
      float scale = ImGuiManager.getInstance().uiScale();
      float dt = Math.max(0.001F, io.getDeltaTime());

      this.frame.begin(
         ImGui.getBackgroundDrawList(),
         ImGui.getForegroundDrawList(),
         width,
         height,
         scale,
         (float)(System.nanoTime() - this.shownAt) / 1.0E9F,
         dt,
         io.getMousePosX(),
         io.getMousePosY(),
         ImGui.isMouseClicked(0)
      );

      this.drawHeader();
   }

   private void drawHeader() {
      ImDrawList dl = this.frame.bg();
      float s = this.frame.scale();
      float a = this.frame.intro(0.0F);

      float h = 54.0F * s;
      float margin = 16.0F * s;
      float y = 10.0F * s;
      float w = 180.0F * s;

      // Draw frosted glass account card in top-left
      boolean hovered = this.frame.inside(margin, y, margin + w, y + h);
      int accent = UiThemes.accent();

      int bgCol = hovered ? 0xB816243A : 0x880E1626;
      int borderCol = hovered ? ((0xF0000000) | (accent & 0x00FFFFFF)) : 0x38FFFFFF;

      // Hover glow
      if (hovered) {
         int glowCol = (0x30000000) | (accent & 0x00FFFFFF);
         dl.addRect(margin - 2.0F * s, y - 2.0F * s, margin + w + 2.0F * s, y + h + 2.0F * s, glowCol, 8.0F * s, 0, 3.0F * s);
      }

      dl.addRectFilled(margin, y, margin + w, y + h, bgCol, 8.0F * s);

      // Top glass sheen
      int topSheen = (hovered ? 0x25 : 0x14) << 24 | 0xFFFFFF;
      dl.addRectFilledMultiColor(margin + 4.0F * s, y, margin + w - 4.0F * s, y + h * 0.45F, topSheen, topSheen, 0x00FFFFFF, 0x00FFFFFF);

      dl.addRect(margin, y, margin + w, y + h, borderCol, 8.0F * s, 0, Math.max(1.0F, 1.2F * s));

      ImFont font = this.frame.itemFont();
      String name = this.frame.displayName();
      int textCol = hovered ? 0xFFFFFFFF : 0xFFD4E2F2;
      this.frame.text(dl, font, margin + 14.0F * s, y + (h - this.frame.textHeight(font)) * 0.5F, textCol, a, name);
   }
}
