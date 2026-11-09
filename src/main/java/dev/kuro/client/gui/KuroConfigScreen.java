package dev.kuro.client.gui;

import dev.kuro.client.config.ConfigManager;
import dev.kuro.client.util.SdfText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class KuroConfigScreen extends Screen {
    private final Screen parent;
    private EditBox nameField;
    private List<String> profiles = new ArrayList<>();
    private int scroll;
    private String statusMsg;
    private int statusTimer;
    private boolean statusSuccess;

    private static final int PANEL_W = 440;
    private static final int ROW_H = 26;

    public KuroConfigScreen(Screen parent) {
        super(Component.literal("Configurations"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        refreshProfiles();
        int cx = width / 2;
        int topY = 70;

        nameField = new EditBox(font, cx - PANEL_W / 2 + 6, topY, PANEL_W - 200, 20, Component.literal("Config Name"));
        nameField.setHint(Component.literal("Enter config name..."));
        nameField.setMaxLength(24);
        addRenderableWidget(nameField);
        setInitialFocus(nameField);
    }

    private void refreshProfiles() {
        this.profiles = ConfigManager.list();
    }

    private void showStatus(String msg, boolean success) {
        this.statusMsg = msg;
        this.statusSuccess = success;
        this.statusTimer = 180;
    }

    private void saveCurrent() {
        String name = nameField.getValue().trim();
        if (name.isEmpty()) {
            showStatus("Please enter a name first", false);
            return;
        }
        if (ConfigManager.save(name)) {
            showStatus("Saved profile '" + ConfigManager.clean(name) + "'", true);
            nameField.setValue("");
            refreshProfiles();
        } else {
            showStatus("Failed to save profile", false);
        }
    }

    private void importFromClipboard() {
        if (minecraft == null) return;
        String clip = minecraft.keyboardHandler.getClipboard();
        String err = ConfigManager.importString(clip);
        if (err == null) {
            ConfigManager.loadDefault();
            showStatus("Imported config successfully!", true);
            refreshProfiles();
        } else {
            showStatus(err, false);
        }
    }

    private void exportToClipboard() {
        if (minecraft == null) return;
        String str = ConfigManager.exportString(true);
        if (str != null && !str.isEmpty()) {
            minecraft.keyboardHandler.setClipboard(str);
            showStatus("Config string copied to clipboard!", true);
        } else {
            showStatus("Export failed", false);
        }
    }

    private void openProfilesFolder() {
        try {
            Path p = ConfigManager.getProfilesDir();
            File file = p.toFile();
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(file);
                return;
            }
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", file.getAbsolutePath()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", file.getAbsolutePath()).start();
            } else {
                new ProcessBuilder("xdg-open", file.getAbsolutePath()).start();
            }
        } catch (Throwable ignored) {
            showStatus("Couldn't open folder", false);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        // Pure solid black backdrop
        g.fill(0, 0, width, height, 0xFF000000);

        int cx = width / 2;
        int panelX = cx - PANEL_W / 2;

        // Title & subtitle
        SdfText.centered(g, SdfText.Face.BOLD, "CONFIGURATIONS", cx, 24, 18.0F, 0xFFFFFFFF);
        SdfText.centered(g, SdfText.Face.SANS, "Save and load named client profiles or import from clipboard", cx, 44, 9.5F, 0xFF7A8496);

        // Status banner
        if (statusTimer > 0) {
            statusTimer--;
            int bannerCol = statusSuccess ? 0xFF1B4D2E : 0xFF5A1E20;
            int textCol = statusSuccess ? 0xFF8AE8A2 : 0xFFFF9090;
            float sw = SdfText.w(statusMsg);
            int bw = Math.round(sw) + 24;
            KuroButtonRenderer.renderSleekRect(g, cx - bw / 2, 53, bw, 14, 3, bannerCol, 0.9F, textCol, 0.6F, 0.0F);
            SdfText.centered(g, SdfText.Face.SANS, statusMsg, cx, 55, 9.0F, textCol);
        }

        // Action bar buttons
        int topY = 70;
        int btnSaveX = panelX + PANEL_W - 190;
        int btnImportX = panelX + PANEL_W - 128;
        int btnExportX = panelX + PANEL_W - 66;

        boolean overSave = mx >= btnSaveX && mx < btnSaveX + 58 && my >= topY && my < topY + 20;
        KuroButtonRenderer.renderButton(g, "cfg_save", btnSaveX, topY, 58, 20, true, overSave, 1.0F);
        SdfText.ui(g, "Save", btnSaveX + 29 - SdfText.w("Save") / 2.0F, topY + 5, overSave ? 0xFF000000 : 0xFFFFFFFF, false);

        boolean overImport = mx >= btnImportX && mx < btnImportX + 58 && my >= topY && my < topY + 20;
        KuroButtonRenderer.renderButton(g, "cfg_import", btnImportX, topY, 58, 20, true, overImport, 1.0F);
        SdfText.ui(g, "Import", btnImportX + 29 - SdfText.w("Import") / 2.0F, topY + 5, overImport ? 0xFF000000 : 0xFFFFFFFF, false);

        boolean overExport = mx >= btnExportX && mx < btnExportX + 60 && my >= topY && my < topY + 20;
        KuroButtonRenderer.renderButton(g, "cfg_export", btnExportX, topY, 60, 20, true, overExport, 1.0F);
        SdfText.ui(g, "Export", btnExportX + 30 - SdfText.w("Export") / 2.0F, topY + 5, overExport ? 0xFF000000 : 0xFFFFFFFF, false);

        // Profiles list container
        int listY = 98;
        int listH = height - 146;
        KuroButtonRenderer.renderSleekRect(g, panelX, listY, PANEL_W, listH, 4, 0x101010, 0.85F, 0x303030, 0.7F, 0.0F);

        int maxVisible = listH / ROW_H;
        int total = profiles.size();
        int maxScroll = Math.max(0, total - maxVisible);
        scroll = Mth.clamp(scroll, 0, maxScroll);

        int visibleCount = Math.min(maxVisible, total - scroll);
        for (int i = 0; i < visibleCount; i++) {
            int pIdx = scroll + i;
            String name = profiles.get(pIdx);
            int ry = listY + 4 + i * ROW_H;

            boolean rowHover = mx >= panelX + 4 && mx < panelX + PANEL_W - 4 && my >= ry && my < ry + ROW_H - 2;
            int rowBg = rowHover ? 0x1A1A1A : 0x121212;
            KuroButtonRenderer.renderSleekRect(g, panelX + 4, ry, PANEL_W - 8, ROW_H - 2, 3, rowBg, 0.75F, 0x282828, 0.5F, 0.0F);

            // Profile name
            SdfText.ui(g, name, panelX + 14, ry + 5, 0xFFFFFFFF, false);

            // Load button
            int loadX = panelX + PANEL_W - 120;
            boolean overLoad = mx >= loadX && mx < loadX + 52 && my >= ry + 2 && my < ry + 20;
            KuroButtonRenderer.renderButton(g, "cfg_load_" + name, loadX, ry + 2, 52, 18, true, overLoad, 1.0F);
            SdfText.ui(g, "Load", loadX + 26 - SdfText.w("Load") / 2.0F, ry + 5, overLoad ? 0xFF000000 : 0xFFFFFFFF, false);

            // Delete button
            int delX = panelX + PANEL_W - 64;
            boolean overDel = mx >= delX && mx < delX + 54 && my >= ry + 2 && my < ry + 20;
            KuroButtonRenderer.renderButton(g, "cfg_del_" + name, delX, ry + 2, 54, 18, true, overDel, 1.0F);
            SdfText.ui(g, "Delete", delX + 27 - SdfText.w("Delete") / 2.0F, ry + 5, overDel ? 0xFF000000 : 0xFFFFFFFF, false);
        }

        if (profiles.isEmpty()) {
            SdfText.centered(g, SdfText.Face.SANS, "No custom configs found.", cx, listY + listH / 2 - 8, 10.0F, 0xFF606875);
            SdfText.centered(g, SdfText.Face.SANS, "Enter a name above and click Save to create one!", cx, listY + listH / 2 + 6, 9.0F, 0xFF4A5260);
        }

        // Scrollbar if needed
        if (total > maxVisible) {
            int trackH = listH - 8;
            int thumbH = Math.max(16, trackH * maxVisible / total);
            int thumbY = listY + 4 + (trackH - thumbH) * scroll / maxScroll;
            g.fill(panelX + PANEL_W - 5, listY + 4, panelX + PANEL_W - 3, listY + 4 + trackH, 0x30FFFFFF);
            g.fill(panelX + PANEL_W - 5, thumbY, panelX + PANEL_W - 3, thumbY + thumbH, 0x90FFFFFF);
        }

        // Bottom buttons: Open Folder & Back
        int bottomY = height - 38;
        int folderW = 100;
        int backW = 120;

        boolean overFolder = mx >= panelX && mx < panelX + folderW && my >= bottomY && my < bottomY + 22;
        KuroButtonRenderer.renderButton(g, "cfg_folder", panelX, bottomY, folderW, 22, true, overFolder, 1.0F);
        SdfText.ui(g, "Open Folder", panelX + folderW / 2.0F - SdfText.w("Open Folder") / 2.0F, bottomY + 6, overFolder ? 0xFF000000 : 0xFFFFFFFF, false);

        int backX = cx - backW / 2;
        boolean overBack = mx >= backX && mx < backX + backW && my >= bottomY && my < bottomY + 22;
        KuroButtonRenderer.renderButton(g, "cfg_back", backX, bottomY, backW, 22, true, overBack, 1.0F);
        SdfText.ui(g, "Back", backX + backW / 2.0F - SdfText.w("Back") / 2.0F, bottomY + 6, overBack ? 0xFF000000 : 0xFFFFFFFF, false);

        super.extractRenderState(g, mx, my, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == 0) {
            double mx = click.x(), my = click.y();
            int cx = width / 2;
            int panelX = cx - PANEL_W / 2;
            int topY = 70;

            // Action bar
            if (mx >= panelX + PANEL_W - 190 && mx < panelX + PANEL_W - 132 && my >= topY && my < topY + 20) {
                saveCurrent();
                return true;
            }
            if (mx >= panelX + PANEL_W - 128 && mx < panelX + PANEL_W - 70 && my >= topY && my < topY + 20) {
                importFromClipboard();
                return true;
            }
            if (mx >= panelX + PANEL_W - 66 && mx < panelX + PANEL_W - 6 && my >= topY && my < topY + 20) {
                exportToClipboard();
                return true;
            }

            // Profile list buttons
            int listY = 98;
            int listH = height - 146;
            int maxVisible = listH / ROW_H;
            int total = profiles.size();
            int visibleCount = Math.min(maxVisible, total - scroll);

            for (int i = 0; i < visibleCount; i++) {
                int pIdx = scroll + i;
                String name = profiles.get(pIdx);
                int ry = listY + 4 + i * ROW_H;

                int loadX = panelX + PANEL_W - 120;
                if (mx >= loadX && mx < loadX + 52 && my >= ry + 2 && my < ry + 20) {
                    if (ConfigManager.load(name)) {
                        dev.kuro.client.module.impl.Hud.applyTheme();
                        showStatus("Loaded profile '" + name + "'", true);
                    } else {
                        showStatus("Couldn't load profile", false);
                    }
                    return true;
                }

                int delX = panelX + PANEL_W - 64;
                if (mx >= delX && mx < delX + 54 && my >= ry + 2 && my < ry + 20) {
                    if (ConfigManager.delete(name)) {
                        showStatus("Deleted profile '" + name + "'", false);
                        refreshProfiles();
                    }
                    return true;
                }
            }

            // Bottom buttons
            int bottomY = height - 38;
            if (mx >= panelX && mx < panelX + 100 && my >= bottomY && my < bottomY + 22) {
                openProfilesFolder();
                return true;
            }
            int backX = cx - 60;
            if (mx >= backX && mx < backX + 120 && my >= bottomY && my < bottomY + 22) {
                onClose();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (vertical != 0) {
            int maxVisible = (height - 146) / ROW_H;
            int maxScroll = Math.max(0, profiles.size() - maxVisible);
            scroll = Mth.clamp(scroll - (int) Math.signum(vertical), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.input();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (nameField.isFocused() && !nameField.getValue().trim().isEmpty()) {
                saveCurrent();
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(this.parent);
        }
    }
}
