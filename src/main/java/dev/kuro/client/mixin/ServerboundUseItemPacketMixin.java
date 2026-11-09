package dev.kuro.client.mixin;

import dev.kuro.client.echo.handlers.RotationHandler;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerboundUseItemPacket.class)
public abstract class ServerboundUseItemPacketMixin {

    @Mutable
    @Shadow
    @Final
    private float yRot;

    @Mutable
    @Shadow
    @Final
    private float xRot;

    @Inject(method = "<init>(Lnet/minecraft/world/InteractionHand;IFF)V", at = @At("RETURN"), require = 0)
    private void kuro$modifyUseItemRotation(InteractionHand hand, int sequence, float yRot, float xRot, CallbackInfo ci) {
        if (RotationHandler.isHasSilentRotation()) {
            this.yRot = RotationHandler.getServerYaw();
            this.xRot = RotationHandler.getServerPitch();
        }
    }
}
