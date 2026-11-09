package dev.kuro.client.mixin;

import dev.kuro.client.bridge.MovementBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientInput.class)
public abstract class ClientInputMixin {

    @Inject(method = "hasForwardImpulse", at = @At("HEAD"), cancellable = true)
    private void kuro$keepSprintWhileCorrecting(CallbackInfoReturnable<Boolean> cir) {
        if (!MovementBridge.shouldKeepSprint() || !MovementBridge.rawHasForwardImpulse()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || (Object) this != mc.player.input) return;

        cir.setReturnValue(true);
    }
}
