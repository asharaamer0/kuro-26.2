package dev.kuro.client.mixin;

import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PauseScreen.class)
public abstract class KuroPauseScreenMixin extends Screen {
    protected KuroPauseScreenMixin(Component title) {
        super(title);
    }
}
