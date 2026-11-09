package dev.kuro.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.kuro.client.bridge.MovementBridge;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin extends ClientInput {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    public void onTickHead(CallbackInfo ci) {
        if (dev.kuro.client.module.impl.Freecam.active()) {
            this.keyPresses = new Input(false, false, false, false, false, false, false);
            ((ClientInputAccessor) this).kuro$setMoveVector(net.minecraft.world.phys.Vec2.ZERO);
            ci.cancel();
        }
    }

    @ModifyExpressionValue(
            method = "tick",
            at = @At(value = "NEW", target = "(ZZZZZZZ)Lnet/minecraft/world/entity/player/Input;")
    )
    private Input kuro$modifyMovementInput(Input original) {
        return MovementBridge.correctInput(original);
    }
}
