package dev.kuro.client.mixin;

import dev.kuro.client.echo.handlers.RotationHandler;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"), require = 0)
    private void kuro$applyServerHeadRotation(Avatar player, AvatarRenderState state, float tickProgress, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || player == null) return;
        if (player != mc.player) return;
        CameraType perspective = mc.options.getCameraType();
        if (perspective.isFirstPerson()) return;
        if (!RotationHandler.isHasSilentRotation()) return;

        RotationHandler.updateFrameRenderRotations();

        float bodyYaw = Mth.rotLerp(tickProgress, player.yBodyRotO, player.yBodyRot);
        state.bodyRot = bodyYaw;
        state.yRot = Mth.wrapDegrees(RotationHandler.getFrameRenderYaw() - bodyYaw);
        state.xRot = Mth.clamp(RotationHandler.getFrameRenderPitch(), -90.0F, 90.0F);
    }
}
