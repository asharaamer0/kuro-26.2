package dev.kuro.client.mixin;

import dev.kuro.client.echo.handlers.RotationHandler;
import dev.kuro.client.util.Rotations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Third person (F5) model rendering for silent rotations.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class SilentModelMixin {

    @Unique private float kuro$originalYaw;
    @Unique private float kuro$originalYawO;
    @Unique private float kuro$originalPitch;
    @Unique private float kuro$originalPitchO;
    @Unique private float kuro$originalHeadYaw;
    @Unique private float kuro$originalHeadYawO;
    @Unique private float kuro$originalBodyYaw;
    @Unique private float kuro$originalBodyYawO;
    @Unique private boolean kuro$swapped;

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("HEAD"), require = 0)
    private void kuro$applySilentRenderRotations(LivingEntity entity, LivingEntityRenderState state, float tickProgress, CallbackInfo ci) {
        if (entity != Minecraft.getInstance().player) return;
        if (!RotationHandler.isHasSilentRotation()) return;

        kuro$swapped = true;
        kuro$originalYaw = entity.getYRot();
        kuro$originalYawO = entity.yRotO;
        kuro$originalPitch = entity.getXRot();
        kuro$originalPitchO = entity.xRotO;
        kuro$originalHeadYaw = entity.yHeadRot;
        kuro$originalHeadYawO = entity.yHeadRotO;
        kuro$originalBodyYaw = entity.yBodyRot;
        kuro$originalBodyYawO = entity.yBodyRotO;

        entity.setYRot(RotationHandler.getRenderYaw());
        entity.yRotO = RotationHandler.getPrevRenderYaw();
        entity.setXRot(RotationHandler.getRenderPitch());
        entity.xRotO = RotationHandler.getPrevRenderPitch();
        entity.yHeadRot = RotationHandler.getRenderYaw();
        entity.yHeadRotO = RotationHandler.getPrevRenderYaw();
        entity.yBodyRot = RotationHandler.getRenderBodyYaw();
        entity.yBodyRotO = RotationHandler.getPrevRenderBodyYaw();
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("RETURN"), require = 0)
    private void kuro$restoreRenderRotations(LivingEntity entity, LivingEntityRenderState state, float tickProgress, CallbackInfo ci) {
        if (!kuro$swapped) return;
        kuro$swapped = false;

        entity.setYRot(kuro$originalYaw);
        entity.yRotO = kuro$originalYawO;
        entity.setXRot(kuro$originalPitch);
        entity.xRotO = kuro$originalPitchO;
        entity.yHeadRot = kuro$originalHeadYaw;
        entity.yHeadRotO = kuro$originalHeadYawO;
        entity.yBodyRot = kuro$originalBodyYaw;
        entity.yBodyRotO = kuro$originalBodyYawO;
    }
}
