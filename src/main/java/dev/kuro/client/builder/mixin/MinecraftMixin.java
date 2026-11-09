package dev.kuro.client.builder.mixin;

import dev.kuro.client.builder.freecam.FreecamController;
import dev.kuro.client.builder.autobuild.AutoBuilderController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void cli$clearActions(CallbackInfo ci) {
        if (FreecamController.enabled()) FreecamController.getInstance().suppressActions((Minecraft) (Object) this);
    }

    @Inject(method = {"startUseItem", "pickBlockOrEntity"}, at = @At("HEAD"), cancellable = true)
    private void cli$blockInteractions(CallbackInfo ci) {
        if (AutoBuilderController.get().isRunning() || FreecamController.enabled()) ci.cancel();
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void cli$continueAttack(CallbackInfo ci) {
        if (AutoBuilderController.get().isRunning()) {
            ci.cancel();
        } else if (FreecamController.enabled() && !FreecamController.getInstance().settings().allowMining) {
            ci.cancel();
        } else {
            if (dev.kuro.client.builder.autotool.AutoToolController.get() != null) {
                dev.kuro.client.builder.autotool.AutoToolController.get().onAttackBlock((Minecraft) (Object) this);
            }
        }
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void cli$blockAttack(CallbackInfoReturnable<Boolean> cir) {
        if (AutoBuilderController.get().isRunning()) {
            cir.setReturnValue(false);
        } else if (FreecamController.enabled() && !FreecamController.getInstance().settings().allowMining) {
            cir.setReturnValue(false);
        } else {
            if (dev.kuro.client.builder.autotool.AutoToolController.get() != null) {
                dev.kuro.client.builder.autotool.AutoToolController.get().onAttackBlock((Minecraft) (Object) this);
            }
        }
    }
}
