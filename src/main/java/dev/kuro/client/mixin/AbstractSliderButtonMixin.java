package dev.kuro.client.mixin;

import dev.kuro.client.gui.KuroButtonRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSliderButton.class)
public abstract class AbstractSliderButtonMixin extends AbstractWidget {
    @Shadow protected double value;

    public AbstractSliderButtonMixin(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"), cancellable = true)
    private void kuro$onRenderSliderWidget(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ci.cancel();

        // 1. Render slider track
        KuroButtonRenderer.renderSliderTrack(
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

        // 2. Render slider handle
        int handleWidth = 8;
        int handleX = this.getX() + (int)(this.value * (double)(this.getWidth() - handleWidth));
        int handleY = this.getY();
        KuroButtonRenderer.renderSliderHandle(
            graphics,
            this,
            handleX,
            handleY,
            handleWidth,
            this.getHeight(),
            this.active,
            this.isHoveredOrFocused(),
            this.alpha
        );

        // 3. Render label
        this.extractScrollingStringOverContents(
            graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE),
            this.getMessage(),
            2
        );
    }
}
