package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.Freecam;
import dev.kuro.client.module.impl.Zoom;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Zoom & Freecam: mouse wheel adjusts zoom or freecam flight speed. */
@Mixin(MouseHandler.class)
public abstract class ZoomMouseMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$onMouseScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (Zoom.onScroll(yOffset) || Freecam.onScroll(yOffset)) {
            ci.cancel();
        }
    }
}
