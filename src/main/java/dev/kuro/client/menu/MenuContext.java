package dev.kuro.client.menu;

import dev.kuro.client.imgui.ImGuiManager;
import imgui.ImDrawList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;

import java.util.function.Supplier;

public final class MenuContext extends MenuFrame {
   private final Screen screen;
   private final float[] hover = new float[MenuAction.values().length];
   private float lookX;
   private float lookY;

   public MenuContext(Screen screen) {
      this.screen = screen;
   }

   @Override
   public void begin(ImDrawList bg, ImDrawList fg, float width, float height, float scale, float time, float dt, float mouseX, float mouseY, boolean clicked) {
      super.begin(bg, fg, width, height, scale, time, dt, mouseX, mouseY, clicked);
      boolean onScreen = mouseX >= 0.0F && mouseY >= 0.0F && mouseX <= width && mouseY <= height;
      float targetX = onScreen ? (mouseX / width) * 2.0F - 1.0F : 0.0F;
      float targetY = onScreen ? (mouseY / height) * 2.0F - 1.0F : 0.0F;
      float k = Math.min(1.0F, dt * 2.5F);
      this.lookX += (targetX - this.lookX) * k;
      this.lookY += (targetY - this.lookY) * k;
   }

   public float lookX() { return this.lookX; }
   public float lookY() { return this.lookY; }

   public boolean enabled(MenuAction action) {
      return true;
   }

   public float control(MenuAction action, float x, float y, float w, float h) {
      boolean over = this.inside(x, y, w, h);
      if (over && this.clicked()) {
         this.activate(action);
      }
      return this.hover(action, over);
   }

   public float hover(MenuAction action, boolean target) {
      float k = this.hover[action.ordinal()];
      k += ((target ? 1.0F : 0.0F) - k) * Math.min(1.0F, this.dt() * 12.0F);
      this.hover[action.ordinal()] = k;
      return k;
   }

   public void activateCredits() {
      ImGuiManager.getInstance().queueTask(() -> openUrl("https://github.com/mrdino201/Kuro"));
   }

   public void activate(MenuAction action) {
      Minecraft mc = Minecraft.getInstance();

      Supplier<Screen> next = switch (action) {
         case SINGLEPLAYER -> () -> new SelectWorldScreen(this.screen);
         case MULTIPLAYER  -> () -> new JoinMultiplayerScreen(this.screen);
         case OPTIONS      -> () -> new OptionsScreen(this.screen, mc.options, false);
         case CONFIGS      -> () -> new dev.kuro.client.gui.KuroConfigScreen(this.screen);
         case LANGUAGE     -> () -> new LanguageSelectScreen(this.screen, mc.options, mc.getLanguageManager());
         case ACCESSIBILITY-> () -> new AccessibilityOptionsScreen(this.screen, mc.options);
         case QUIT         -> null;
         default           -> null;
      };

      if (action == MenuAction.QUIT) {
         ImGuiManager.getInstance().queueTask(mc::stop);
      } else if (next != null) {
         ImGuiManager.getInstance().queueTask(() -> mc.gui.setScreen(next.get()));
      }
   }

   private static void openUrl(String url) {
      try {
         if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
            return;
         }
      } catch (Throwable ignored) {}
      try {
         String os = System.getProperty("os.name", "").toLowerCase();
         if (os.contains("win")) {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
         } else if (os.contains("mac")) {
            new ProcessBuilder("open", url).start();
         } else {
            new ProcessBuilder("xdg-open", url).start();
         }
      } catch (Throwable ignored) {}
   }
}
