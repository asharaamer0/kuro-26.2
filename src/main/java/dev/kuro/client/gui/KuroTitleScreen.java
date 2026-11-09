package dev.kuro.client.gui;

import dev.kuro.client.imgui.ImGuiManager;
import dev.kuro.client.menu.MainMenuRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.entity.player.PlayerSkin;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public final class KuroTitleScreen extends TitleScreen {
    public static PlayerSkin CACHED_PLAYER_SKIN;
    private final MainMenuRenderer menu;

    public KuroTitleScreen() {
        super(false);
        this.menu = new MainMenuRenderer(this);
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();
        this.menu.onInit();
        ImGuiManager.getInstance().activate(this.menu::render);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            return true; // Main menu never closes on Escape
        }
        return this.menu.keyPressed(input.key()) || super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == 0) {
            this.menu.onMouseClicked();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public void removed() {
        super.removed();
        ImGuiManager.getInstance().deactivate();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        context.fill(0, 0, width, height, 0xFF000000);
        ImGuiManager.getInstance().requestCursor(context);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        // Prevent closing the title screen to null which closes the game
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
