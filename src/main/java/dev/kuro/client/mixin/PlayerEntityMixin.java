package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.Reach;
import dev.kuro.client.module.impl.SafeWalk;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerEntityMixin {
    @Inject(method = "blockInteractionRange", at = @At("RETURN"), cancellable = true, require = 0)
    private void kuro$blockReach(CallbackInfoReturnable<Double> cir) {
        double r = Reach.block();
        if (r > 0 && (Object) this == Minecraft.getInstance().player) cir.setReturnValue(Math.max(r, cir.getReturnValue()));
    }

    @Inject(method = "entityInteractionRange", at = @At("RETURN"), cancellable = true, require = 0)
    private void kuro$entityReach(CallbackInfoReturnable<Double> cir) {
        double r = Reach.entity();
        if (r > 0 && (Object) this == Minecraft.getInstance().player) cir.setReturnValue(Math.max(r, cir.getReturnValue()));
    }

    // edge-clip (like sneaking) while Safe Walk sees a harmful drop ahead
    @Inject(method = "isStayingOnGroundSurface", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$safeWalk(CallbackInfoReturnable<Boolean> cir) {
        if (dev.kuro.client.builder.autobuild.AutoBuilderController.get().isRunning()) return;
        if (SafeWalk.clip() && (Object) this == Minecraft.getInstance().player) cir.setReturnValue(true);
    }

    @org.spongepowered.asm.mixin.injection.Redirect(
        method = {"causeExtraKnockback", "doSweepAttack"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getYRot()F"
        ),
        require = 0
    )
    private float kuro$redirectAttackYaw(Player entity) {
        if (entity == Minecraft.getInstance().player && dev.kuro.client.echo.handlers.RotationHandler.isHasSilentRotation()) {
            return dev.kuro.client.echo.handlers.RotationHandler.getServerYaw();
        }
        return entity.getYRot();
    }
}
