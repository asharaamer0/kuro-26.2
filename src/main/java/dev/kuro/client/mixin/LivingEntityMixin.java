package dev.kuro.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.kuro.client.bridge.MovementBridge;
import dev.kuro.client.echo.handlers.RotationHandler;
import dev.kuro.client.event.EventBus;
import dev.kuro.client.event.events.KineticHitEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @ModifyExpressionValue(
            method = "jumpFromGround",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getYRot()F")
    )
    private float kuro$jumpBoostYaw(float original) {
        if ((Object) this != Minecraft.getInstance().player) return original;
        return MovementBridge.getMovementYaw(original);
    }

    @Inject(method = "handleEntityEvent",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;onKineticHit()V"))
    private void kuro$onKineticHit(CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().player) return;
        EventBus.INSTANCE.post(new KineticHitEvent());
    }

    /**
     * Gliding pitch and look vector using silent rotation.
     */
    @ModifyExpressionValue(method = "updateFallFlyingMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getXRot()F"), require = 0)
    private float kuro$hookFallFlyingPitch(float original) {
        if ((Object) this != Minecraft.getInstance().player) return original;
        if (!RotationHandler.isHasSilentRotation()) return original;
        return RotationHandler.getServerPitch();
    }

    @ModifyExpressionValue(method = "updateFallFlyingMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getLookAngle()Lnet/minecraft/world/phys/Vec3;"), require = 0)
    private Vec3 kuro$hookFallFlyingLookAngle(Vec3 original) {
        if ((Object) this != Minecraft.getInstance().player) return original;
        if (!RotationHandler.isHasSilentRotation()) return original;
        return RotationHandler.getRotationVector(
            RotationHandler.getServerPitch(),
            RotationHandler.getServerYaw()
        );
    }
}
