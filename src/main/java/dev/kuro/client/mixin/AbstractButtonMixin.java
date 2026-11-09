package dev.kuro.client.mixin;

import dev.kuro.client.gui.KuroButtonRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractButton.class)
public abstract class AbstractButtonMixin extends AbstractWidget {
    public AbstractButtonMixin(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    @Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true)
    private void kuro$onRenderDefaultSprite(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        ci.cancel();
        KuroButtonRenderer.renderButton(
            graphics,
            this,
            this.getX(),
            this.getY(),
            this.getWidth(),
            this.getHeight(),
            this.active,
            this.isHoveredOrFocused(),
            this.alpha
        );
    }
}
