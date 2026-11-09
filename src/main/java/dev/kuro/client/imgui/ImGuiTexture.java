package dev.kuro.client.imgui;

import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public final class ImGuiTexture {
    private static final Map<String, Integer> CACHE = new HashMap<>();

    private ImGuiTexture() {}

    public static int load(String resourcePath) {
        if (CACHE.containsKey(resourcePath)) {
            return CACHE.get(resourcePath);
        }

        try (InputStream stream = ImGuiTexture.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                CACHE.put(resourcePath, 0);
                return 0;
            }

            try (NativeImage img = NativeImage.read(stream)) {
                int texId = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, img.getWidth(), img.getHeight(), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, img.getPointer());
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

                CACHE.put(resourcePath, texId);
                return texId;
            }
        } catch (Exception e) {
            CACHE.put(resourcePath, 0);
            return 0;
        }
    }
}
