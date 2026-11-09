package imgui.gl3;

import imgui.ImFontAtlas;
import imgui.ImGui;
import imgui.type.ImInt;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

@Environment(EnvType.CLIENT)
public class KuroGl3 extends ImGuiImplGl3 {

   @Override
   public boolean createFontsTexture() {
      ImFontAtlas atlas = ImGui.getIO().getFonts();
      ImInt width = new ImInt();
      ImInt height = new ImInt();
      ByteBuffer pixels = atlas.getTexDataAsRGBA32(width, height);
      int maximum = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
      long byteCount = (long) width.get() * height.get() * 4L;

      if (width.get() > 0
         && height.get() > 0
         && width.get() <= maximum
         && height.get() <= maximum
         && pixels != null
         && byteCount <= pixels.capacity()
         && byteCount <= Integer.MAX_VALUE) {

         ByteBuffer upload = pixels.duplicate();
         upload.position(0);
         upload.limit((int) byteCount);

         int oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);

         int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
         int oldUnpackBuffer = GL11.glGetInteger(35055); // GL_PIXEL_UNPACK_BUFFER_BINDING
         int oldAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
         int oldRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
         int oldSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
         int oldSkipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);

         int oldFont = this.data.fontTexture;
         int texture = 0;
         boolean success = false;

         try {
            reportExistingErrors();
            GL15.glBindBuffer(35052, 0); // Unbind GL_PIXEL_UNPACK_BUFFER so glTexImage2D reads client buffer
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);

            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 33071); // GL_CLAMP_TO_EDGE
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 33071);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, 33084, 0); // GL_TEXTURE_BASE_LEVEL
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, 33085, 0); // GL_TEXTURE_MAX_LEVEL

            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width.get(), height.get(), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, upload);
            int error = GL11.glGetError();
            if (texture == 0 || error != 0) {
               throw new IllegalStateException(
                  "[Kuro ImGui] Font upload failed: "
                     + width.get()
                     + "x"
                     + height.get()
                     + ", GL error=0x"
                     + Integer.toHexString(error)
               );
            }

            this.data.fontTexture = texture;
            atlas.setTexID(texture);
            success = true;
         } finally {
            if (!success && texture != 0) {
               GL11.glDeleteTextures(texture);
            }

            int restoredTexture = success && oldFont != 0 && oldTexture == oldFont ? texture : oldTexture;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, restoredTexture);
            GL15.glBindBuffer(35052, oldUnpackBuffer);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, oldAlignment);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, oldRowLength);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, oldSkipRows);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, oldSkipPixels);
            GL13.glActiveTexture(oldActive);

            if (success && oldFont != 0 && oldFont != texture) {
               GL11.glDeleteTextures(oldFont);
            }
         }

         return true;
      } else {
         throw new IllegalStateException("[Kuro ImGui] Invalid font atlas " + width.get() + "x" + height.get() + "; GL_MAX_TEXTURE_SIZE=" + maximum);
      }
   }

   private static void reportExistingErrors() {
      for (int i = 0; i < 16; i++) {
         int error = GL11.glGetError();
         if (error == 0) return;
      }
   }
}
