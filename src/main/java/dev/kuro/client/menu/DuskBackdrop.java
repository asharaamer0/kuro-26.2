package dev.kuro.client.menu;

import dev.kuro.client.gui.UiThemes;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class DuskBackdrop {
   private DuskBackdrop() {}

   public static void draw(GuiGraphicsExtractor graphics, int width, int height) {
      if (width > 0 && height > 0) {
         UiThemes.UiTheme t = UiThemes.current();
         int panel = t != null ? (t.panel & 0xFFFFFF) : 0x070B14;
         graphics.fillGradient(0, 0, width, height, 0xFF070B14, 0xFF000000 | panel);
      }
   }
}
