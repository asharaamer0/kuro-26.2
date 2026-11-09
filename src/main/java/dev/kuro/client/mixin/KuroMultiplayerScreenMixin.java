package dev.kuro.client.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(JoinMultiplayerScreen.class)
public abstract class KuroMultiplayerScreenMixin extends Screen {
   protected KuroMultiplayerScreenMixin(Component title) {
      super(title);
   }
}
