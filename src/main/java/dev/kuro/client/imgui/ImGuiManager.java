package dev.kuro.client.imgui;

import com.mojang.blaze3d.platform.Window;
import imgui.ImFont;
import imgui.ImFontConfig;
import imgui.ImFontGlyphRangesBuilder;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.gl3.KuroGl3;
import imgui.glfw.ImGuiImplGlfw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class ImGuiManager {
   private static final ImGuiManager INSTANCE = new ImGuiManager();

   private final ImGuiImplGlfw glfw = new ImGuiImplGlfw();
   private final KuroGl3 gl3 = new KuroGl3();

   private boolean initialized = false;
   private Runnable activeRenderer = null;
   private boolean isRendering = false;
   private final List<Runnable> postRenderTasks = new ArrayList<>();

   // Fonts
   private ImFont titleFont;
   private ImFont itemFont;
   private ImFont tagFont;

   public static ImGuiManager getInstance() {
      return INSTANCE;
   }

   public void init(long windowHandle) {
      if (this.initialized) return;

      ImGui.createContext();
      ImGuiIO io = ImGui.getIO();
      io.setIniFilename(null); // Disable automatic imgui.ini saving

      this.glfw.init(windowHandle, true);
      this.gl3.init("#version 150");

      this.setupFonts(io);
      this.initialized = true;
   }

   private void setupFonts(ImGuiIO io) {
      ImFontGlyphRangesBuilder builder = new ImFontGlyphRangesBuilder();
      builder.addRanges(io.getFonts().getGlyphRangesDefault());
      short[] glyphRanges = builder.buildRanges();

      ImFontConfig config = new ImFontConfig();
      config.setGlyphRanges(glyphRanges);

      byte[] boldData = loadFontBytes("/assets/kuro/font/instrument_sans_bold.ttf");
      byte[] regularData = loadFontBytes("/assets/kuro/font/instrument_sans.ttf");

      if (boldData != null && regularData != null) {
         this.titleFont = io.getFonts().addFontFromMemoryTTF(boldData, 38.0F, config);
         this.itemFont = io.getFonts().addFontFromMemoryTTF(boldData, 19.0F, config);
         this.tagFont = io.getFonts().addFontFromMemoryTTF(regularData, 14.0F, config);
      } else if (boldData != null) {
         this.titleFont = io.getFonts().addFontFromMemoryTTF(boldData, 38.0F, config);
         this.itemFont = io.getFonts().addFontFromMemoryTTF(boldData, 19.0F, config);
         this.tagFont = io.getFonts().addFontFromMemoryTTF(boldData, 14.0F, config);
      } else if (regularData != null) {
         this.titleFont = io.getFonts().addFontFromMemoryTTF(regularData, 38.0F, config);
         this.itemFont = io.getFonts().addFontFromMemoryTTF(regularData, 19.0F, config);
         this.tagFont = io.getFonts().addFontFromMemoryTTF(regularData, 14.0F, config);
      } else {
         this.titleFont = io.getFonts().addFontDefault();
         this.itemFont = io.getFonts().addFontDefault();
         this.tagFont = io.getFonts().addFontDefault();
      }

      this.gl3.createFontsTexture();
      config.destroy();
   }

   private byte[] loadFontBytes(String path) {
      try (InputStream stream = getClass().getResourceAsStream(path)) {
         if (stream != null) {
            return stream.readAllBytes();
         }
      } catch (Exception ignored) {}
      return null;
   }

   public void activate(Runnable renderer) {
      long handle = Minecraft.getInstance().getWindow().handle();
      if (!this.initialized) {
         this.init(handle);
      }
      this.activeRenderer = renderer;
   }

   public void deactivate() {
      this.activeRenderer = null;
      this.postRenderTasks.clear();
   }

   public boolean isActive() {
      return this.activeRenderer != null;
   }

   public void queueTask(Runnable task) {
      if (task == null) return;
      if (this.isRendering) {
         this.postRenderTasks.add(task);
      } else {
         Minecraft.getInstance().execute(task);
      }
   }

   public void requestCursor(GuiGraphicsExtractor graphics) {
      // Cursor hook
   }

   public void renderIfActive() {
      if (!this.initialized || this.activeRenderer == null || this.isRendering) return;

      if (Minecraft.getInstance().gui.screen() == null) {
         this.activeRenderer = null;
         return;
      }

      Window window = Minecraft.getInstance().getWindow();
      int fbWidth = window.getWidth();
      int fbHeight = window.getHeight();
      if (fbWidth <= 0 || fbHeight <= 0) return;

      this.isRendering = true;

      ImGuiIO io = ImGui.getIO();
      io.setDisplaySize((float) fbWidth, (float) fbHeight);
      io.setDisplayFramebufferScale(1.0F, 1.0F);

      // Save GL state
      int oldFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
      int[] oldViewport = new int[4];
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, oldViewport);

      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
      GL11.glViewport(0, 0, fbWidth, fbHeight);

      this.glfw.newFrame();
      this.gl3.newFrame();
      ImGui.newFrame();

      try {
         this.activeRenderer.run();
      } catch (Throwable t) {
         t.printStackTrace();
      } finally {
         try {
            ImGui.render();
            this.gl3.renderDrawData(ImGui.getDrawData());
         } catch (Throwable t) {
            t.printStackTrace();
         } finally {
            // Restore GL state
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, oldFbo);
            GL11.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);
            this.isRendering = false;
         }
      }

      if (!this.postRenderTasks.isEmpty()) {
         List<Runnable> tasks = new ArrayList<>(this.postRenderTasks);
         this.postRenderTasks.clear();
         for (Runnable task : tasks) {
            try {
               task.run();
            } catch (Throwable t) {
               t.printStackTrace();
            }
         }
      }
   }

   public float uiScale() {
      Window window = Minecraft.getInstance().getWindow();
      return Math.max(1.0F, (float) window.getHeight() / 1080.0F);
   }

   public ImFont titleFont() { return this.titleFont; }
   public ImFont itemFont()  { return this.itemFont; }
   public ImFont tagFont()   { return this.tagFont; }
}
