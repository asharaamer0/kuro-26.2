package dev.kuro.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.kuro.client.echo.handlers.RotationHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {
    @Shadow
    private LivingEntity attachedToEntity;

    @ModifyExpressionValue(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getLookAngle()Lnet/minecraft/world/phys/Vec3;"),
        require = 0
    )
    private Vec3 kuro$modifyFireworkBoostVector(Vec3 original) {
        if (this.attachedToEntity != Minecraft.getInstance().player) {
            return original;
        }

        if (RotationHandler.isHasSilentRotation()) {
            return RotationHandler.getRotationVector(
                RotationHandler.getServerPitch(),
                RotationHandler.getServerYaw()
            );
        }

        return original;
    }
}
