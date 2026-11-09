package dev.kuro.client.mixin;

import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.platform.IconSet;
import com.mojang.blaze3d.platform.Window;
import dev.kuro.client.imgui.ImGuiManager;
import net.minecraft.server.packs.PackResources;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

@Mixin(Window.class)
public abstract class KuroWindowMixin {

   @Shadow public abstract long handle();

   @Unique
   private static boolean kuro$iconApplied = false;

   @ModifyVariable(method = "setTitle", at = @At("HEAD"), argsOnly = true)
   private String kuro$overrideTitle(String title) {
      return "Kuro client";
   }

   @Inject(method = "setIcon", at = @At("HEAD"), cancellable = true)
   private void kuro$onSetIcon(PackResources packResources, IconSet iconSet, CallbackInfo ci) {
      kuro$applyCustomIcon();
      kuro$iconApplied = true;
      ci.cancel();
   }

   @Unique
   private void kuro$applyCustomIcon() {
      try {
         long winHandle = this.handle();
         if (winHandle == 0L) return;

         GLFW.glfwSetWindowTitle(winHandle, "Kuro client");

         try (InputStream in = KuroWindowMixin.class.getResourceAsStream("/assets/kuro/textures/gui/icons/window_icon.png")) {
            if (in == null) return;
            BufferedImage src = ImageIO.read(in);
            if (src == null) return;

            int[] sizes = {16, 32, 48, 64, 128, 256};
            List<ByteBuffer> allocatedBuffers = new ArrayList<>(sizes.length);

            try (MemoryStack stack = MemoryStack.stackPush()) {
               GLFWImage.Buffer iconBuffer = GLFWImage.malloc(sizes.length, stack);

               for (int i = 0; i < sizes.length; i++) {
                  int s = sizes[i];
                  BufferedImage scaled = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
                  Graphics2D g2d = scaled.createGraphics();
                  g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                  g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                  g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                  g2d.drawImage(src, 0, 0, s, s, null);
                  g2d.dispose();

                  ByteBuffer byteBuf = MemoryUtil.memAlloc(s * s * 4);
                  allocatedBuffers.add(byteBuf);

                  for (int y = 0; y < s; y++) {
                     for (int x = 0; x < s; x++) {
                        int argb = scaled.getRGB(x, y);
                        byteBuf.put((byte) ((argb >> 16) & 0xFF)); // R
                        byteBuf.put((byte) ((argb >> 8) & 0xFF));  // G
                        byteBuf.put((byte) (argb & 0xFF));         // B
                        byteBuf.put((byte) ((argb >> 24) & 0xFF)); // A
                     }
                  }
                  byteBuf.flip();

                  iconBuffer.position(i);
                  iconBuffer.width(s);
                  iconBuffer.height(s);
                  iconBuffer.pixels(byteBuf);
               }

               iconBuffer.position(0);
               GLFW.glfwSetWindowIcon(winHandle, iconBuffer);
            } finally {
               for (ByteBuffer b : allocatedBuffers) {
                  MemoryUtil.memFree(b);
               }
            }
         }
      } catch (Throwable t) {
         t.printStackTrace();
      }
   }
}
