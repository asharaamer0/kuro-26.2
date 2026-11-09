package dev.kuro.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.kuro.client.bridge.MovementBridge;
import dev.kuro.client.module.impl.Freecam;
import dev.kuro.client.module.impl.Freelook;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    // mouse movement steers the freecam instead of the real player
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void kuro$look(double dx, double dy, CallbackInfo ci) {
        if (Freecam.active() && (Object) this == Minecraft.getInstance().player) {
            Freecam.get().look(dx, dy);
            ci.cancel();
        } else if (Freelook.active() && (Object) this == Minecraft.getInstance().player) {
            Freelook.get().look(dx, dy);   // camera only; the player keeps facing the same way
            ci.cancel();
        }
    }

    @Inject(method = "isInvisibleTo", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$freecamOwnBody(net.minecraft.world.entity.player.Player viewer, CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.active() && Freecam.get().drawsSelf((Entity) (Object) this, viewer)) {
            cir.setReturnValue(false);
        }
    }

    // Zoom: the mouse turns the view less while zoomed in (same feel at every zoom level)
    @ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private double kuro$zoomYaw(double v) {
        return (Object) this == Minecraft.getInstance().player ? v * dev.kuro.client.module.impl.Zoom.sensScale() : v;
    }

    @ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private double kuro$zoomPitch(double v) {
        return (Object) this == Minecraft.getInstance().player ? v * dev.kuro.client.module.impl.Zoom.sensScale() : v;
    }

    // silent rotation
    @Inject(method = "getViewVector", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$silentView(float tickDelta, CallbackInfoReturnable<net.minecraft.world.phys.Vec3> cir) {
        if (dev.kuro.client.echo.handlers.RotationHandler.isHasSilentRotation() && (Object) this == Minecraft.getInstance().player) {
            cir.setReturnValue(dev.kuro.client.echo.handlers.RotationHandler.getRotationVector(
                dev.kuro.client.echo.handlers.RotationHandler.getServerPitch(),
                dev.kuro.client.echo.handlers.RotationHandler.getServerYaw()
            ));
            return;
        }
        if (dev.kuro.client.util.Rotations.active() && (Object) this == Minecraft.getInstance().player)
            cir.setReturnValue(dev.kuro.client.util.Rotations.viewVector());
    }

    @Inject(method = "getHeadLookAngle", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$silentHeadLookAngle(CallbackInfoReturnable<net.minecraft.world.phys.Vec3> cir) {
        if ((Object) this == Minecraft.getInstance().player && dev.kuro.client.echo.handlers.RotationHandler.isHasSilentRotation()) {
            cir.setReturnValue(dev.kuro.client.echo.handlers.RotationHandler.getRotationVector(
                dev.kuro.client.echo.handlers.RotationHandler.getServerPitch(),
                dev.kuro.client.echo.handlers.RotationHandler.getServerYaw()
            ));
        }
    }

    @Inject(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$freecamEyePos(float tickDelta, CallbackInfoReturnable<net.minecraft.world.phys.Vec3> cir) {
    }

    // Hitbox Expand: a bigger aiming box for other entities (crosshair / attack picking only)
    @Inject(method = "getPickRadius", at = @At("RETURN"), cancellable = true, require = 0)
    private void kuro$margin(CallbackInfoReturnable<Float> cir) {
        float m = dev.kuro.client.module.impl.HitboxExpand.margin((Entity) (Object) this);
        if (m > 0f) cir.setReturnValue(cir.getReturnValueF() + m);
    }

    @ModifyExpressionValue(
            method = "moveRelative",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getYRot()F")
    )
    private float kuro$movementBasisYaw(float original) {
        if ((Object) this != Minecraft.getInstance().player) return original;
        return MovementBridge.getMovementYaw(original);
    }
}
