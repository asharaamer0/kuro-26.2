package dev.kuro.client.mixin;

import dev.kuro.client.menu.DuskBackdrop;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class KuroBackdropMixin {
   @Shadow public int width;
   @Shadow public int height;

   @Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
   private void kuro$duskBackdrop(GuiGraphicsExtractor graphics, float partialTick, CallbackInfo ci) {
      ci.cancel();
      DuskBackdrop.draw(graphics, this.width, this.height);
   }
}
