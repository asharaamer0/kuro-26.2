package dev.kuro.client.mixin;

import dev.kuro.client.gui.KuroTitleScreen;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(net.minecraft.client.gui.Gui.class)
public abstract class KuroTitleScreenMixin {
   @Shadow @org.spongepowered.asm.mixin.Final private Minecraft minecraft;
   @Shadow public abstract void setScreen(Screen screen);

   @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
   private void kuro$replaceTitleScreen(Screen screen, CallbackInfo ci) {
      if (!this.minecraft.getWindow().backend().getName().equalsIgnoreCase("OpenGL")) return;
      if (!(screen instanceof KuroTitleScreen)) {
         if (screen instanceof TitleScreen || (screen == null && this.minecraft.level == null)) {
            ci.cancel();
            this.setScreen(new KuroTitleScreen());
         }
      }
   }

}
