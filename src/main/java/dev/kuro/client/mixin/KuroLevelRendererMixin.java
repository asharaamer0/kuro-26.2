package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.Freecam;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class KuroLevelRendererMixin {
    @Inject(method = "isSectionCompiledAndVisible", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$freecamEntitiesReady(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.active()) {
            cir.setReturnValue(true);
        }
    }
}
