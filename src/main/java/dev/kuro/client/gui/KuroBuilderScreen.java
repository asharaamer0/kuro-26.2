package dev.kuro.client.gui;

import dev.kuro.client.builder.BuilderHost;
import dev.kuro.client.builder.autobuild.AutoBuilderController;
import dev.kuro.client.builder.autobuild.BuilderNavigator;
import dev.kuro.client.builder.autobuild.LitematicLoader;
import dev.kuro.client.builder.autobuild.PlacementTransform;
import dev.kuro.client.builder.autobuild.Schematic;
import dev.kuro.client.builder.config.AutoBuilderSettings;
import dev.kuro.client.module.impl.GlassUi;
import dev.kuro.client.module.impl.Hud;
import dev.kuro.client.module.impl.KuroBuilder;
import dev.kuro.client.util.SdfText;
import dev.kuro.client.util.ShaderUi;
import dev.kuro.client.util.ShaderUi.Mode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kuro Builder's control panel: schematic list and placement on one tab, running the build and its
 * options on the other.
 *
 * <p>It is built from the same pieces as the Glass menu: shader glass panels and edges, the selected UI
 * theme's palette and animated backdrop (petals, stars, embers, bubbles, snow, hearts, flag, flower),
 * accent gradients, spring-eased switches and a short fade-in. Every preset therefore looks like it
 * belongs to that theme's menu.
 */
public class KuroBuilderScreen extends Screen {
    private static final int PW = 500, PH = 300, ROW = 17, MAX_ROWS = 8;
    private static final String[] TABS = {"Schematic", "Build", "Movement"};

    private final Screen parent;
    private final KuroBuilder module;
    private final AutoBuilderController controller = AutoBuilderController.get();
    private final Map<String, Float> switchAnim = new HashMap<>();

    private List<Path> files = new ArrayList<>();
    private Path selected;
    private String message = "Put .litematic files in the schematic folder (Open folder), then pick one.";
    private int tab, scroll;
    private boolean draggingOpacity;

    // frame animation
    private long lastNs;
    private float time, fade, dt = 1f / 60f;

    // Theme palette for this frame (UiThemes for every preset, Theme for Classic).
    private int panelFill, edge, headFill, railFill, cardFill, cardHi, cardSel;
    private int text, muted, faint, label, body, acc, acc2, onAcc, trackBg, offTrack, knob, knobOff, glow;

    public KuroBuilderScreen(Screen parent, KuroBuilder module) {
        super(Component.literal("Kuro Builder"));
        this.parent = parent;
        this.module = module;
        refresh();
        if (controller.schematic() != null) {
            String name = controller.schematic().name();
            for (Path p : files) {
                if (strip(p).equalsIgnoreCase(name)) { selected = p; break; }
            }
        }
    }

    private static String strip(Path p) {
        String n = p.getFileName().toString();
        return n.toLowerCase().endsWith(".litematic") ? n.substring(0, n.length() - 10) : n;
    }

    private void refresh() {
        files = BuilderHost.get().schematics();
        scroll = Mth.clamp(scroll, 0, Math.max(0, files.size() - MAX_ROWS));
    }

    private static int a(int argb, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (argb & 0xFFFFFF);
    }

    private static int mix(int c1, int c2, float t) {
        t = Mth.clamp(t, 0f, 1f);
        int a1 = c1 >>> 24, r1 = (c1 >> 16) & 255, g1 = (c1 >> 8) & 255, b1 = c1 & 255;
        int a2 = c2 >>> 24, r2 = (c2 >> 16) & 255, g2 = (c2 >> 8) & 255, b2 = c2 & 255;
        return (Math.round(a1 + (a2 - a1) * t) << 24) | (Math.round(r1 + (r2 - r1) * t) << 16)
                | (Math.round(g1 + (g2 - g1) * t) << 8) | Math.round(b1 + (b2 - b1) * t);
    }

    /** Colour with its alpha scaled by the opening fade. */
    private int c(int argb) {
        int al = Math.round((argb >>> 24) * fade);
        return (Mth.clamp(al, 0, 255) << 24) | (argb & 0xFFFFFF);
    }

    /** Reads the current theme exactly like GlassGuiScreen.palette(), so both windows share one look. */
    private void palette() {
        Hud.applyTheme();
        UiThemes.UiTheme t = UiThemes.current();
        float o = GlassUi.opacity();
        if (t.custom) {                                     // Classic: the HUD colour / light-dark switch drives Theme
            panelFill = Theme.PANEL; edge = Theme.PANEL_EDGE; headFill = Theme.TITLE;
            railFill = Theme.SETTING; cardFill = Theme.ROW; cardHi = Theme.ROW_HOVER; cardSel = Theme.ON;
            text = Theme.TEXT; muted = Theme.MUTED; faint = Theme.MUTED; label = Theme.TITLE_TEXT; body = Theme.TEXT;
            acc = Theme.ON; acc2 = Theme.SLIDER; onAcc = Theme.ON_TEXT; trackBg = Theme.SLIDER_BG; offTrack = Theme.SLIDER_BG;
            knob = Theme.SLIDER; knobOff = Theme.SLIDER; glow = Theme.GLOW;
            return;
        }
        panelFill = argbAlpha(t.panel, t.alpha[0] * o);
        railFill = argbAlpha(t.rail, t.alpha[1] * o);
        cardFill = argbAlpha(t.card, t.alpha[2] * o);
        cardHi = argbAlpha(t.cardHi, t.alpha[3] * o);
        cardSel = argbAlpha(t.cardSel, t.alpha[4] * o);
        headFill = argbAlpha(t.head, t.alpha[5] * o);
        edge = argbAlpha(t.edge, 0xA0);
        text = 0xFF000000 | t.text;
        muted = 0xFF000000 | t.muted;
        faint = 0xFF000000 | t.faint;
        label = 0xFF000000 | t.label;
        body = 0xFF000000 | t.body;
        acc = 0xFF000000 | t.accent;
        acc2 = 0xFF000000 | t.accent2;
        onAcc = 0xFF000000 | t.onAcc;
        trackBg = 0xFF000000 | t.trackBg;
        offTrack = 0xFF000000 | t.offTrack;
        knob = 0xFF000000 | t.knob;
        knobOff = 0xFF000000 | t.knobOff;
        glow = (0x60 << 24) | (t.accent & 0xFFFFFF);
    }

    private static int argbAlpha(int rgb, float alpha) {
        return (Mth.clamp(Math.round(alpha), 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (Hud.menuBlur()) super.extractBackground(g, mx, my, delta);
        else g.fill(0, 0, width, height, 0x90000000);
    }

    // ------------------------------------------------------------------ geometry

    private int x0() { return width / 2 - PW / 2; }
    private int y0() { return height / 2 - PH / 2; }
    private int contentTop() { return y0() + 58; }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        long now = System.nanoTime();
        dt = lastNs == 0 ? 1f / 60f : Mth.clamp((now - lastNs) / 1.0e9f, 0f, 0.1f);
        lastNs = now;
        time += dt;
        fade = Mth.clamp(fade + dt * 7f, 0f, 1f);            // quick fade-in, like the glass menu opening

        palette();
        int x0 = x0(), y0 = y0(), x1 = x0 + PW, y1 = y0 + PH;
        UiThemes.UiTheme theme = UiThemes.current();

        ShaderUi.shadow(g, x0, y0 + 10, PW, PH, 34, c(0xB0000000));
        ShaderUi.shadow(g, x0, y0, PW, PH, 24, c(a(acc, 0x16)));
        ShaderUi.fancy(g, Mode.GLASS, x0, y0, PW, PH, 14f, c(panelFill));
        ThemeDecor.draw(g, theme, x0, y0, PW, PH, time, fade);      // the animated theme background
        ShaderUi.fancy(g, Mode.GLASS_EDGE, x0, y0, PW, PH, 14f, c(a(edge, 0xB0)));

        SdfText.ui(g, "Kuro Builder", x0 + 12, y0 + 9, c(label), false);
        String state = statusLabel();
        SdfText.ui(g, state, x1 - 12 - SdfText.w(state), y0 + 9, c(label), false);
        ShaderUi.line(g, x0 + 12, y0 + 28, x1 - 12, y0 + 28, 1f, c(a(edge, 0x55)));

        for (int i = 0; i < TABS.length; i++) {
            int tx = x0 + 10 + i * 94;
            boolean on = tab == i;
            boolean hover = mx >= tx && mx < tx + 90 && my >= y0 + 33 && my < y0 + 49;
            ShaderUi.fancy(g, Mode.GLASS, tx, y0 + 33, 90, 16, 8f, c(on ? a(acc, 0xE8) : hover ? cardHi : cardFill));
            if (on) ShaderUi.fancy(g, Mode.GRAD, tx, y0 + 33, 90, 16, 8f, c(a(acc, 0xEF)));
            ShaderUi.fancy(g, Mode.GLASS_EDGE, tx, y0 + 33, 90, 16, 8f,
                    c(on ? a(acc2, 0xE0) : a(edge, hover ? 0x80 : 0x40)));
            SdfText.ui(g, TABS[i], tx + (90 - SdfText.w(TABS[i])) / 2, y0 + 37, c(on ? onAcc : text), false);
        }

        if (tab == 0) renderSchematic(g, mx, my);
        else if (tab == 1) renderBuild(g, mx, my);
        else renderMovement(g, mx, my);

        SdfText.ui(g, SdfText.trim(message, PW - 24), x0 + 12, y1 - 14, c(muted), false);
    }

    private String statusLabel() {
        return switch (controller.status()) {
            case RUNNING -> "Building";
            case PAUSED -> "Paused";
            case COLLECTING -> "Collecting";
            case COMPLETED -> "Done";
            case READY -> "Ready";
            default -> "Idle";
        };
    }

    // ------------------------------------------------------------------ schematic tab

    private void renderSchematic(GuiGraphicsExtractor g, int mx, int my) {
        int x0 = x0(), y1 = y0() + PH;
        int lx = x0 + 10, ly = contentTop(), lw = 230;
        int listBottom = y1 - 44;

        roundedGlass(g, lx, ly, lw, listBottom - 18 - ly, 10f, railFill, a(edge, 0x38));
        SdfText.ui(g, "Schematics", lx + 8, ly + 5, c(muted), false);
        String count = files.size() + " file" + (files.size() == 1 ? "" : "s");
        SdfText.ui(g, count, lx + lw - 8 - SdfText.w(count), ly + 5, c(muted), false);

        int rowsTop = ly + 18, visible = Math.min(MAX_ROWS, files.size() - scroll);
        for (int i = 0; i < visible; i++) {
            Path p = files.get(scroll + i);
            int ry = rowsTop + i * ROW;
            boolean isSel = p.equals(selected);
            boolean hover = mx >= lx + 3 && mx < lx + lw - 3 && my >= ry && my < ry + ROW - 1;
            roundedGlass(g, lx + 3, ry, lw - 6, ROW - 2, 6f, isSel ? a(acc, 0xE8) : hover ? cardHi : cardFill,
                    isSel ? a(acc2, 0xE0) : a(edge, hover ? 0x60 : 0x28));
            SdfText.ui(g, SdfText.trim(strip(p), lw - 18), lx + 9, ry + 4, c(isSel ? onAcc : text), false);
        }
        if (files.isEmpty()) SdfText.ui(g, "No .litematic files here yet.", lx + 9, rowsTop + 4, c(body), false);
        if (files.size() > MAX_ROWS) {
            int trackH = MAX_ROWS * ROW, thumbH = Math.max(10, trackH * MAX_ROWS / files.size());
            int thumbY = rowsTop + (trackH - thumbH) * scroll / (files.size() - MAX_ROWS);
            ShaderUi.fancy(g, Mode.PILL, lx + lw - 5, rowsTop, 2, trackH, ShaderUi.PILL_R, c(trackBg));
            ShaderUi.fancy(g, Mode.PILL, lx + lw - 5, thumbY, 2, thumbH, ShaderUi.PILL_R, c(a(acc, 0xDD)));
        }

        button(g, lx + 2, y1 - 38, 108, 16, "Open folder", mx, my, true);
        button(g, lx + 116, y1 - 38, 112, 16, "Refresh list", mx, my, true);

        int dx = x0 + 250, dw = PW - 260;
        roundedGlass(g, dx - 8, contentTop() - 8, dw + 12, y0() + PH - 26 - (contentTop() - 8), 10f,
                cardFill, a(edge, 0x40));

        Schematic s = controller.schematic();
        SdfText.ui(g, "SELECTED", dx, contentTop(), c(muted), false);
        if (s == null) {
            SdfText.ui(g, "Nothing loaded.", dx, contentTop() + 14, c(text), false);
        } else {
            SdfText.ui(g, SdfText.trim(s.name(), dw), dx, contentTop() + 14, c(text), false);
            String size = s.dimensions().getX() + " x " + s.dimensions().getY() + " x " + s.dimensions().getZ()
                    + "   " + s.blocks().size() + " blocks";
            SdfText.ui(g, SdfText.trim(size, dw), dx, contentTop() + 28, c(muted), false);
            String extra = s.regionCount() + " region" + (s.regionCount() == 1 ? "" : "s")
                    + (s.warnings().isEmpty() ? "" : "   " + s.warnings().size() + " warning" + (s.warnings().size() == 1 ? "" : "s"));
            SdfText.ui(g, SdfText.trim(extra, dw), dx, contentTop() + 42, c(muted), false);
        }

        int oy = contentTop() + 64;
        SdfText.ui(g, "ORIGIN", dx, oy, c(muted), false);
        PlacementTransform t = controller.transform();
        String origin = t.origin().getX() + ", " + t.origin().getY() + ", " + t.origin().getZ();
        SdfText.ui(g, origin, dx, oy + 14, c(text), false);

        button(g, dx, oy + 28, 48, 15, "Here", mx, my, minecraft.player != null);
        button(g, dx + 52, oy + 28, 48, 15, "Target", mx, my, minecraft.player != null);
        button(g, dx + 104, oy + 28, 38, 15, "Rot <", mx, my, s != null);
        button(g, dx + 146, oy + 28, 38, 15, "Rot >", mx, my, s != null);
        button(g, dx + 188, oy + 28, 48, 15, t.mirrorX() ? "Mirror: on" : "Mirror", mx, my, s != null);

        int ny = oy + 47;
        SdfText.ui(g, "NUDGE", dx, ny, c(muted), false);
        button(g, dx + 42, ny - 2, 28, 14, "X-", mx, my, s != null);
        button(g, dx + 73, ny - 2, 28, 14, "X+", mx, my, s != null);
        button(g, dx + 107, ny - 2, 28, 14, "Y-", mx, my, s != null);
        button(g, dx + 138, ny - 2, 28, 14, "Y+", mx, my, s != null);
        button(g, dx + 172, ny - 2, 28, 14, "Z-", mx, my, s != null);
        button(g, dx + 203, ny - 2, 28, 14, "Z+", mx, my, s != null);

        int py = ny + 18;
        toggle(g, dx, py, "Show preview", controller.previewVisible(), mx, my);
        SdfText.ui(g, "Preview opacity", dx, py + 18, c(muted), false);
        int sliderX = dx + 96, sliderW = dw - 96;
        int sy = py + 20;
        ShaderUi.fancy(g, Mode.PILL, sliderX, sy, sliderW, 8, ShaderUi.PILL_R, c(trackBg));
        int fill = (int) Math.round(previewOpacity() * sliderW);
        if (fill > 2) ShaderUi.fancy(g, Mode.GRAD, sliderX, sy, fill, 8, ShaderUi.PILL_R, c(a(acc, 0xEF)));
        ShaderUi.knob(g, sliderX + fill, sy + 4, 4.2f, c(knob));
        String pct = Math.round(previewOpacity() * 100) + "%";
        SdfText.ui(g, pct, sliderX + sliderW - SdfText.w(pct), py + 18, c(muted), false);
    }

    private double previewOpacity() {
        return Mth.clamp(module.config().autoBuilder.previewOpacity, 0.08, 0.8);
    }

    // ------------------------------------------------------------------ build tab

    private void renderBuild(GuiGraphicsExtractor g, int mx, int my) {
        int x0 = x0(), dx = x0 + 10, dw = PW - 20, top = contentTop();

        AutoBuilderController.HudSnapshot hud = controller.hudSnapshot();
        SdfText.ui(g, SdfText.trim(controller.reason(), dw), dx, top, c(text), false);

        int by = top + 28;
        ShaderUi.fancy(g, Mode.PILL, dx, by, dw, 10, ShaderUi.PILL_R, c(trackBg));
        int filled = (int) Math.round(Mth.clamp(controller.progress(), 0, 1) * dw);
        if (filled > 2) ShaderUi.fancy(g, Mode.GRAD, dx, by, filled, 10, ShaderUi.PILL_R, c(a(acc, 0xEF)));
        String progress = hud.confirmed() + " / " + hud.total() + " blocks   " + Math.round(controller.progress() * 100) + "%";
        SdfText.ui(g, progress, dx, by + 15, c(muted), false);

        String layer = hud.total() == 0 ? "" : "Layer " + hud.layer() + " / " + hud.layers();
        SdfText.ui(g, layer, dx + dw - SdfText.w(layer), by + 15, c(muted), false);

        int ry = by + 34;
        button(g, dx, ry, 64, 18, "Start", mx, my, controller.schematic() != null);
        button(g, dx + 70, ry, 64, 18, "Pause", mx, my, controller.status() == AutoBuilderController.Status.RUNNING);
        button(g, dx + 140, ry, 64, 18, "Resume", mx, my, controller.status() == AutoBuilderController.Status.PAUSED);
        button(g, dx + 210, ry, 64, 18, "Stop", mx, my, controller.status() != AutoBuilderController.Status.IDLE);
        SdfText.ui(g, hud.perMinute() + " blocks/min", dx + 288, ry + 5, c(muted), false);

        int my1 = ry + 28;
        SdfText.ui(g, controller.materialStorageLabel(), dx, my1, c(controller.materialStorage() == null ? muted : text), false);
        button(g, dx + dw - 130, my1 - 4, 62, 16, "Choose", mx, my, minecraft.player != null);
        button(g, dx + dw - 62, my1 - 4, 62, 16, "Clear", mx, my, controller.materialStorage() != null);

        int cy = my1 + 22;
        button(g, dx, cy, 60, 16, "Collect", mx, my, controller.schematic() != null && minecraft.player != null);
        button(g, dx + 64, cy, 76, 16, "Creative Get", mx, my, controller.schematic() != null && minecraft.player != null);
        button(g, dx + 144, cy, 66, 16, "Paste (SP)", mx, my, controller.schematic() != null && minecraft.hasSingleplayerServer());
        button(g, dx + 214, cy, 96, 16, "Materials in chat", mx, my, controller.schematic() != null && minecraft.player != null);

        int ty = cy + 24;
        int colW = dw / 2;
        AutoBuilderSettings s = module.config().autoBuilder;
        toggle(g, dx, ty, "Allow substitutes", s.allowSubstitutes, mx, my);
        toggle(g, dx + colW, ty, "Buy from auction", s.buyMaterialsFromAuction, mx, my);
        toggle(g, dx, ty + 18, "Silent rotations", s.silentRotations, mx, my);
        toggle(g, dx + colW, ty + 18, "Auction stack only", s.auctionStackOnly, mx, my);
        toggle(g, dx, ty + 36, "Fast place", s.fastPlace, mx, my);
        toggle(g, dx + colW, ty + 36, "Local sweep", s.localSweep, mx, my);
        toggle(g, dx, ty + 54, "Auto eat", s.autoEat, mx, my);
        toggle(g, dx + colW, ty + 54, "Close sign editors", module.config().autoCloseSigns, mx, my);
        toggle(g, dx, ty + 72, "Chat logs", s.chatLogs, mx, my);
        button(g, dx, ty + 92, colW - 6, 16, "Places/tick: " + s.placesPerTick, mx, my, true);
        button(g, dx + colW, ty + 92, colW - 6, 16, "Delay: " + s.placementDelay + "ms", mx, my, true);
    }

    private void renderMovement(GuiGraphicsExtractor g, int mx, int my) {
        int x0 = x0(), y0 = y0();
        int dx = x0 + 16, dw = PW - 32;
        int top = contentTop() - 8;
        roundedGlass(g, dx - 8, top, dw + 16, y0 + PH - 26 - top, 10f, cardFill, a(edge, 0x40));

        int colW = dw / 2;
        AutoBuilderSettings s = module.config().autoBuilder;

        int ty = top + 14;
        SdfText.ui(g, "Locomotion & Navigation", dx, ty, c(label), false);

        int rowY = ty + 18;
        toggle(g, dx, rowY, "Sprint while pathing", s.sprint, mx, my);
        toggle(g, dx + colW, rowY, "Sprint jumping", s.sprintJump, mx, my);

        rowY += 18;
        toggle(g, dx, rowY, "Auto move to targets", s.autoMove, mx, my);
        toggle(g, dx + colW, rowY, "Smooth routes (corner cut)", s.smoothRoutes, mx, my);

        rowY += 18;
        toggle(g, dx, rowY, "Bridge gaps", s.bridging, mx, my);
        toggle(g, dx + colW, rowY, "Tower when stuck", s.towerWhenStuck, mx, my);

        rowY += 18;
        button(g, dx, rowY - 2, colW - 6, 16, String.format("Reach: %.1f blocks", s.reach), mx, my, true);

        int infoY = rowY + 36;
        SdfText.ui(g, "Movement Diagnostics", dx, infoY, c(muted), false);
        BuilderNavigator.Metrics m = controller.navigator().metrics();
        String mInfo = "Path searches: " + m.pathSearches() + " (" + (m.pathNanos() / 1_000_000) + "ms total)";
        SdfText.ui(g, mInfo, dx, infoY + 16, c(body), false);
        String sInfo = "Supports confirmed: " + m.confirmedSupports() + " | Failures: " + m.supportFailures();
        SdfText.ui(g, sInfo, dx, infoY + 30, c(body), false);
    }

    // ------------------------------------------------------------------ widgets

    /** A glass card with a soft rim: the shape every control in the Glass menu uses. */
    private void roundedGlass(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int fill, int rim) {
        ShaderUi.fancy(g, Mode.GLASS, x, y, w, h, r, c(fill));
        ShaderUi.fancy(g, Mode.GLASS_EDGE, x, y, w, h, r, c(rim));
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, int mx, int my, boolean enabled) {
        boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        roundedGlass(g, x, y, w, h, 8f, enabled ? (hover ? cardHi : cardFill) : railFill,
                enabled ? a(edge, hover ? 0xB0 : 0x50) : a(edge, 0x30));
        int color = enabled ? text : faint;
        if (hover) color = mix(color, acc2, 0.35f);
        SdfText.ui(g, label, x + (w - SdfText.w(label)) / 2, y + (h - 8) / 2, c(color), false);
    }

    private void toggle(GuiGraphicsExtractor g, int x, int y, String label, boolean on, int mx, int my) {
        float t = switchValue(label, on);
        ShaderUi.fancy(g, Mode.PILL, x, y + 1, 22, 12, ShaderUi.PILL_R, c(offTrack));
        if (t > 0.02f) {
            ShaderUi.fancy(g, Mode.GRAD, x, y + 1, 22, 12, ShaderUi.PILL_R, c(a(acc, Math.round(0xE6 * t))));
            ShaderUi.spot(g, x + 11, y + 7, 20, 16, 10, c(a(acc, Math.round(0x30 * t))));
        }
        ShaderUi.knob(g, x + 6 + 10 * Mth.clamp(t, 0f, 1f), y + 7, 4.2f, c(mix(knobOff, onAcc, t)));
        SdfText.ui(g, label, x + 28, y + 2, c(text), false);
    }

    private float switchValue(String key, boolean on) {
        float target = on ? 1f : 0f;
        Float old = switchAnim.get(key);
        if (old == null) { switchAnim.put(key, target); return target; }
        float next = old + (target - old) * Mth.clamp(dt * 14f, 0f, 1f);
        if (Math.abs(target - next) < 0.002f) next = target;
        switchAnim.put(key, next);
        return next;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mx = click.x(), my = click.y();
        if (click.button() != 0) return super.mouseClicked(click, doubled);
        int x0 = x0(), y0 = y0(), y1 = y0 + PH;
        int lx = x0 + 10;

        for (int i = 0; i < TABS.length; i++) {
            int tx = x0 + 10 + i * 94;
            if (mx >= tx && mx < tx + 90 && my >= y0 + 33 && my < y0 + 49) { tab = i; return true; }
        }

        if (tab == 0) {
            int ly = contentTop(), listBottom = y1 - 44, rowsTop = ly + 18;
            if (mx >= lx + 3 && mx < lx + 233 && my >= rowsTop && my < listBottom - 18) {
                int idx = (int) ((my - rowsTop) / ROW);
                if (idx >= 0 && idx < MAX_ROWS && scroll + idx < files.size()) { select(files.get(scroll + idx)); return true; }
            }
            if (mx >= lx + 2 && mx < lx + 110 && my >= y1 - 38 && my < y1 - 22) { BuilderHost.get().openSchematicsFolder(); return true; }
            if (mx >= lx + 116 && mx < lx + 228 && my >= y1 - 38 && my < y1 - 22) { refresh(); message = files.size() + " schematic" + (files.size() == 1 ? "" : "s") + " found."; return true; }

            int dx = x0 + 250, dw = PW - 260, oy = contentTop() + 64;
            if (mx >= dx && mx < dx + 48 && my >= oy + 28 && my < oy + 44) { setOriginHere(); return true; }
            if (mx >= dx + 52 && mx < dx + 100 && my >= oy + 28 && my < oy + 44) { setOriginLooking(); return true; }
            if (mx >= dx + 104 && mx < dx + 142 && my >= oy + 28 && my < oy + 44) { rotate(-1); return true; }
            if (mx >= dx + 146 && mx < dx + 184 && my >= oy + 28 && my < oy + 44) { rotate(1); return true; }
            if (mx >= dx + 188 && mx < dx + 236 && my >= oy + 28 && my < oy + 44) { mirror(); return true; }

            int ny = oy + 47;
            if (mx >= dx + 42 && mx < dx + 70 && my >= ny - 2 && my < ny + 12) { nudge(-1, 0, 0); return true; }
            if (mx >= dx + 73 && mx < dx + 101 && my >= ny - 2 && my < ny + 12) { nudge(1, 0, 0); return true; }
            if (mx >= dx + 107 && mx < dx + 135 && my >= ny - 2 && my < ny + 12) { nudge(0, -1, 0); return true; }
            if (mx >= dx + 138 && mx < dx + 166 && my >= ny - 2 && my < ny + 12) { nudge(0, 1, 0); return true; }
            if (mx >= dx + 172 && mx < dx + 200 && my >= ny - 2 && my < ny + 12) { nudge(0, 0, -1); return true; }
            if (mx >= dx + 203 && mx < dx + 231 && my >= ny - 2 && my < ny + 12) { nudge(0, 0, 1); return true; }

            int py = ny + 18;
            if (mx >= dx && mx < dx + 22 + SdfText.w("Show preview") && my >= py && my < py + 14) {
                controller.setPreviewVisible(!controller.previewVisible());
                return true;
            }
            int sliderX = dx + 96;
            if (my >= py + 14 && my < py + 30 && mx >= sliderX && mx < dx + dw) {
                draggingOpacity = true;
                setOpacity((mx - sliderX) / Math.max(1, dw - 96));
                return true;
            }
            return super.mouseClicked(click, doubled);
        }

        if (tab == 1) {
            int dx = x0 + 10, dw = PW - 20, top = contentTop(), by = top + 28, ry = by + 34;
            if (my >= ry && my < ry + 18) {
                if (mx >= dx && mx < dx + 64) { start(); return true; }
                if (mx >= dx + 70 && mx < dx + 134) { module.pauseBuild(); return true; }
                if (mx >= dx + 140 && mx < dx + 204) { resume(); return true; }
                if (mx >= dx + 210 && mx < dx + 274) { module.stopBuild(); message = "Build stopped."; return true; }
            }
            int my1 = ry + 28;
            if (my >= my1 - 4 && my < my1 + 12) {
                if (mx >= dx + dw - 130 && mx < dx + dw - 68) { message = controller.chooseMaterialStorage(minecraft); return true; }
                if (mx >= dx + dw - 62 && mx < dx + dw) { controller.clearMaterialStorage(); message = "Material storage cleared."; return true; }
            }
            int cy = my1 + 22;
            if (my >= cy && my < cy + 16) {
                if (mx >= dx && mx < dx + 60) { controller.collectMaterials(minecraft); return true; }
                if (mx >= dx + 64 && mx < dx + 140) { controller.getMaterials(minecraft); message = controller.reason(); return true; }
                if (mx >= dx + 144 && mx < dx + 210) { controller.pasteSchematic(minecraft); message = "Pasted schematic."; return true; }
                if (mx >= dx + 214 && mx < dx + 310) { controller.printMaterials(minecraft); message = "Materials printed in chat."; return true; }
            }
            int ty = cy + 24;
            int colW = dw / 2;
            AutoBuilderSettings s = module.config().autoBuilder;
            if (clickRow(mx, my, dx, ty, colW)) { s.allowSubstitutes = !s.allowSubstitutes; module.save(); return true; }
            if (clickRow(mx, my, dx + colW, ty, colW)) { s.buyMaterialsFromAuction = !s.buyMaterialsFromAuction; module.save(); return true; }
            if (clickRow(mx, my, dx, ty + 18, colW)) { s.silentRotations = !s.silentRotations; module.save(); return true; }
            if (clickRow(mx, my, dx + colW, ty + 18, colW)) { s.auctionStackOnly = !s.auctionStackOnly; module.save(); return true; }
            if (clickRow(mx, my, dx, ty + 36, colW)) { s.fastPlace = !s.fastPlace; module.save(); return true; }
            if (clickRow(mx, my, dx + colW, ty + 36, colW)) { s.localSweep = !s.localSweep; module.save(); return true; }
            if (clickRow(mx, my, dx, ty + 54, colW)) { s.autoEat = !s.autoEat; module.save(); return true; }
            if (clickRow(mx, my, dx + colW, ty + 54, colW)) { module.config().autoCloseSigns = !module.config().autoCloseSigns; module.save(); return true; }
            if (clickRow(mx, my, dx, ty + 72, colW)) { s.chatLogs = !s.chatLogs; module.save(); return true; }
            if (mx >= dx && mx < dx + colW - 6 && my >= ty + 92 && my < ty + 108) {
                s.placesPerTick = s.placesPerTick == 1 ? 2 : s.placesPerTick == 2 ? 4 : s.placesPerTick == 4 ? 8 : 1;
                module.save();
                return true;
            }
            if (mx >= dx + colW && mx < dx + dw - 6 && my >= ty + 92 && my < ty + 108) {
                s.placementDelay = s.placementDelay == 0 ? 50 : s.placementDelay == 50 ? 100 : s.placementDelay == 100 ? 250 : 0;
                module.save();
                return true;
            }
            return super.mouseClicked(click, doubled);
        }

        if (tab == 2) {
            int dx = x0 + 16, dw = PW - 32;
            int colW = dw / 2;
            int top = contentTop() - 8;
            int ty = top + 14;
            int rowY = ty + 18;
            AutoBuilderSettings s = module.config().autoBuilder;
            if (clickRow(mx, my, dx, rowY, colW)) { s.sprint = !s.sprint; module.save(); return true; }
            if (clickRow(mx, my, dx + colW, rowY, colW)) { s.sprintJump = !s.sprintJump; module.save(); return true; }

            rowY += 18;
            if (clickRow(mx, my, dx, rowY, colW)) { s.autoMove = !s.autoMove; module.save(); return true; }
            if (clickRow(mx, my, dx + colW, rowY, colW)) { s.smoothRoutes = !s.smoothRoutes; module.save(); return true; }

            rowY += 18;
            if (clickRow(mx, my, dx, rowY, colW)) { s.bridging = !s.bridging; module.save(); return true; }
            if (clickRow(mx, my, dx + colW, rowY, colW)) { s.towerWhenStuck = !s.towerWhenStuck; module.save(); return true; }

            rowY += 18;
            if (mx >= dx && mx < dx + colW - 6 && my >= rowY - 2 && my < rowY + 14) {
                s.reach = s.reach >= 5.5 ? 3.0 : Math.round((s.reach + 0.5) * 10.0) / 10.0;
                module.save();
                return true;
            }
            return super.mouseClicked(click, doubled);
        }
        return super.mouseClicked(click, doubled);
    }

    private boolean clickRow(double mx, double my, int x, int y, int width) {
        return mx >= x && mx < x + width && my >= y && my < y + 14;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        if (draggingOpacity) {
            int dx = x0() + 250, dw = PW - 260, sliderX = dx + 96;
            setOpacity((click.x() - sliderX) / Math.max(1, dw - 96));
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (draggingOpacity) { draggingOpacity = false; module.save(); return true; }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (tab == 0 && files.size() > MAX_ROWS
                && mx >= x0() + 10 && mx < x0() + 240 && my >= contentTop() && my < y0() + PH - 44) {
            scroll = Mth.clamp(scroll - (int) Math.signum(vertical) * 2, 0, files.size() - MAX_ROWS);
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    // ------------------------------------------------------------------ actions

    private void select(Path path) {
        selected = path;
        try {
            Schematic s = LitematicLoader.load(path);
            controller.setSchematic(s);
            // Start from the player's feet so a plain "Start" already builds where they stand.
            if (minecraft.player != null) {
                PlacementTransform t = controller.transform();
                controller.setTransform(new PlacementTransform(minecraft.player.blockPosition(), t.quarterTurns(), t.mirrorX()));
            }
            message = "Loaded " + s.name() + " (" + s.blocks().size() + " blocks). Move the origin if needed, then Start.";
        } catch (IOException | RuntimeException e) {
            message = "Could not load " + path.getFileName() + ": " + e.getMessage();
        }
    }

    private void setOriginHere() {
        if (minecraft.player == null) { message = "Join a world first."; return; }
        if (controller.schematic() == null) { message = "Choose a schematic first."; return; }
        PlacementTransform t = controller.transform();
        controller.setTransform(new PlacementTransform(minecraft.player.blockPosition(), t.quarterTurns(), t.mirrorX()));
        message = "Origin moved to your feet.";
    }

    private void setOriginLooking() {
        if (minecraft.player == null) { message = "Join a world first."; return; }
        if (controller.schematic() == null) { message = "Choose a schematic first."; return; }
        message = controller.setOriginLooking(minecraft);
    }

    private void nudge(int dx, int dy, int dz) {
        if (controller.schematic() == null) { message = "Choose a schematic first."; return; }
        message = controller.nudgeOrigin(dx, dy, dz);
    }

    private void rotate(int quarters) {
        if (controller.schematic() == null) { message = "Choose a schematic first."; return; }
        PlacementTransform t = controller.transform();
        controller.setTransform(new PlacementTransform(t.origin(), t.quarterTurns() + quarters, t.mirrorX()));
        message = "Rotation: " + (controller.transform().quarterTurns() * 90) + " degrees.";
    }

    private void mirror() {
        if (controller.schematic() == null) { message = "Choose a schematic first."; return; }
        PlacementTransform t = controller.transform();
        controller.setTransform(new PlacementTransform(t.origin(), t.quarterTurns(), !t.mirrorX()));
        message = "Mirror " + (controller.transform().mirrorX() ? "on." : "off.");
    }

    private void start() {
        if (controller.schematic() == null) { message = "Choose a schematic first."; return; }
        if (minecraft.player == null) { message = "Join a world first."; return; }
        module.save();
        minecraft.gui.setScreen(null);                 // the builder refuses to start behind an open menu
        module.startBuild();
    }

    private void resume() {
        module.save();
        minecraft.gui.setScreen(null);
        module.resumeBuild();
    }

    private void setOpacity(double fraction) {
        module.config().autoBuilder.previewOpacity = Mth.clamp(0.08 + Mth.clamp(fraction, 0, 1) * 0.72, 0.08, 0.8);
    }

    @Override
    public void onClose() {
        module.save();
        minecraft.gui.setScreen(parent);
    }
}
