package dev.kuro.client.mixin;

import dev.kuro.client.imgui.ImGuiManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draw the OpenGL menu after the surface blit and before presentation. */
@Mixin(Minecraft.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V"))
    private void kuro$drawMenu(CallbackInfo ci) {
        ImGuiManager.getInstance().renderIfActive();
    }
}

