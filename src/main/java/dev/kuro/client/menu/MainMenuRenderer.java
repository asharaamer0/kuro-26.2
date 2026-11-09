package dev.kuro.client.menu;

import dev.kuro.client.gui.UiThemes;
import dev.kuro.client.imgui.ImGuiManager;
import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.ImGuiIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class MainMenuRenderer {
   private final MenuContext context;
   private long openedAt = System.nanoTime();
   private boolean screenClicked = false;

   public MainMenuRenderer(Screen screen) {
      this.context = new MenuContext(screen);
   }

   public void onInit() {
      this.openedAt = System.nanoTime();
      this.screenClicked = false;
   }

   public void onMouseClicked() {
      this.screenClicked = true;
   }

   public boolean keyPressed(int key) {
      return false;
   }

   public void render() {
      dev.kuro.client.module.impl.Hud.applyTheme();
      long now = System.nanoTime();
      if (this.openedAt == 0) {
         this.openedAt = now;
      }

      ImGuiIO io = ImGui.getIO();
      float t = (float)(now - this.openedAt) / 1.0E9F;

      // Sync window cursor coordinates from Minecraft MouseHandler
      double realX = Minecraft.getInstance().mouseHandler.xpos();
      double realY = Minecraft.getInstance().mouseHandler.ypos();
      if (realX > 0 || realY > 0) {
         io.setMousePos((float) realX, (float) realY);
      }

      float mx = io.getMousePosX();
      float my = io.getMousePosY();
      boolean click = (ImGui.isMouseClicked(0) || this.screenClicked) && t > 0.1F;
      this.screenClicked = false;

      float width = io.getDisplaySizeX();
      float height = io.getDisplaySizeY();
      float scale = ImGuiManager.getInstance().uiScale();
      float dt = Math.max(0.001F, io.getDeltaTime());

      this.context.begin(
         ImGui.getBackgroundDrawList(),
         ImGui.getForegroundDrawList(),
         width,
         height,
         scale,
         t,
         dt,
         mx,
         my,
         click
      );

      // Render the glass background & buttons
      DuskMenu.render(this.context);

      // Tooltip pass
      if (this.context.pendingTooltip() != null) {
         drawTooltip(this.context);
      }
   }

   private static void drawTooltip(MenuContext c) {
      ImDrawList dl = c.fg();
      ImFont font = c.tagFont();
      String text = c.pendingTooltip();
      float size = c.textHeight(font);
      float w = c.textWidth(font, text) + c.px(18.0F);
      float h = size + c.px(9.0F);
      float x = c.tooltipX() - w * 0.5F;
      float y = c.tooltipY();

      dl.addRectFilled(x, y, x + w, y + h, 0xF2101010, c.px(6.0F));
      dl.addRect(x, y, x + w, y + h, 0x40FFFFFF, c.px(6.0F), 0, Math.max(1.0F, c.scale()));
      c.text(dl, font, x + c.px(9.0F), y + c.px(4.5F), 0xFFFFFFFF, 1.0F, text);
   }
}
