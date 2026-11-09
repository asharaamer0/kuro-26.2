package dev.kuro.client.gui;

import dev.kuro.client.KuroClient;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Descriptions;
import dev.kuro.client.module.Module;
import dev.kuro.client.module.impl.ConfigModule;
import dev.kuro.client.module.impl.FrameUi;
import dev.kuro.client.module.impl.FriendsModule;
import dev.kuro.client.module.impl.Hud;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.FineText;
import dev.kuro.client.util.SdfText;
import dev.kuro.client.util.ShaderUi;
import dev.kuro.client.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Frame-style module menu: a navigation panel on the left (search box, one button per category, a few extra pages) and
 * one draggable frame per category. Module rows toggle on left click, expand to their settings on right click (or the
 * dots), and bind a key on middle click (or a click on the bind box).
 *
 * Look: dark layered panels with soft drop shadows, enabled rows filled with an accent gradient, animated setting
 * drawers and frame collapse, fine-grid (quarter GUI pixel) switches / sliders / knobs, and a short open animation.
 */
public class FrameGuiScreen extends Screen implements ModuleGui {

    // ---- metrics (GUI px) ----
    private static final int FW = 124, HEAD = 28, ROW = 23, SET = 20, NUM = 30, WHEEL = 104, WS = 64, FOOT = 6, R = 5, PAD = 10;
    private static final int NAV_TITLE = 28, NAV_SEARCH = 30, NAV_BTN = 23, NAV_LABEL = 16, MAX_BODY = 300;
    private static final float FS = 9.5f, FS_SET = 8.8f, FS_SMALL = 7.6f, FS_TITLE = 10.5f;

    // ---- palette ----
    private static final int C_HEAD = 0xFF111111, C_ROW = 0xFF191919, C_ROW_HOT = 0xFF212121, C_ROW_SEL = 0xFF1E1E1E;
    private static final int C_SETBG = 0xFF141414, C_LINE = 0xFF121212, C_TRACK = 0xFF353535, C_TEXT = 0xFFEFEFEF;
    private static final int C_DIM = 0xFFA6A6A6, C_MUTED = 0xFF7C7C7C, C_FAINT = 0xFF5A5A5A, C_INSET = 0xFF0D0D0D;

    private static int accent() { return FrameUi.accent(); }

    /** Readable text colour on top of the accent. */
    private static int onAccent(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 150 ? 0xFF1F1F1F : 0xFFFFFFFF;
    }

    // ---- text ----
    private static int tw(String s, float size) { return (int) Math.ceil(FineText.width(SdfText.Face.SANS, s, size)); }
    private static int twb(String s, float size) { return (int) Math.ceil(FineText.width(SdfText.Face.BOLD, s, size)); }
    private static int ty(int top, int h, float size) { return top + h / 2 - Math.round(0.35f * size); }
    private static void tx(GuiGraphicsExtractor g, String s, int x, int y, float size, int c) { FineText.draw(g, SdfText.Face.SANS, s, x, y, size, c); }
    private static void txb(GuiGraphicsExtractor g, String s, int x, int y, float size, int c) { FineText.draw(g, SdfText.Face.BOLD, s, x, y, size, c); }

    private static String trim(String s, int max, float size) {
        if (tw(s, size) <= max) return s;
        String dots = "...";
        String t = s;
        while (!t.isEmpty() && tw(t + dots, size) > max) t = t.substring(0, t.length() - 1);
        return t + dots;
    }

    private static void rr(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int r, int c) {
        Ui.corners(g, x1, y1, x2, y2, r, c, true, true, true, true);
    }

    /** Rounded rect on the quarter-pixel grid (coordinates and radius in GUI px, fractions allowed): crisp thin tracks and knobs. */
    private static void frr(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, float r, int c) {
        final int M = FineText.M;
        final int a = Math.round(x1 * M), b = Math.round(y1 * M), cc = Math.round(x2 * M), d = Math.round(y2 * M), rad = Math.round(r * M);
        FineText.fine(g, () -> Ui.corners(g, a, b, cc, d, rad, c, true, true, true, true));
    }

    private static int alpha(int argb, float a) {
        return (Mth.clamp(Math.round((argb >>> 24) * a), 0, 255) << 24) | (argb & 0xFFFFFF);
    }

    /** Soft drop shadow under a whole panel (drawn first, the panel covers the middle). */
    private static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        ShaderUi.shadow(g, x, y + 2, w, h, 12, 0x73000000);
    }

    // ---- model ----
    private static class Pane {
        int x, y, scroll, maxScroll;
        float pos, target;
        final List<Hit> hits = new ArrayList<>();
    }

    private static final class Frame extends Pane {
        final Category cat;      // category frames
        final Module page;       // single-module page frames (Settings, Config, Friends, Menu)
        final String title;
        boolean visible, open = true, placed;
        float oa = 1f;           // open / collapse animation, 0..1
        Frame(Category cat, Module page, String title) { this.cat = cat; this.page = page; this.title = title; }
    }

    /** One line of a frame: a module row (s == null) or one of that module's settings (exp = animates with the module's drawer). */
    private record Row(Module m, Setting s, boolean exp) {}

    private static final int K_HEAD = 0, K_MODULE = 1, K_SETTING = 2, K_BIND = 3, K_DOTS = 4, K_NAVTITLE = 5, K_CAT = 6, K_PAGE = 7, K_SEARCH = 8;

    private record Hit(int x, int y, int w, int h, int kind, Module module, Setting setting, int idx) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    // static so positions and open frames survive closing and reopening the menu
    private static final Pane NAV = new Pane();
    private static final List<Frame> FRAMES = new ArrayList<>();
    private static final List<Module> PAGES = new ArrayList<>();
    private static boolean init;

    private Pane dragPane;
    private int dragDx, dragDy;
    private Setting activeSlider;
    private int sliderX, sliderW;
    private int dragPart;                       // colour wheel: 0 = hue strip, 1 = wheel, 2 = brightness bar
    private int wheelCx, wheelCy, barX, barW;
    private final Map<Setting, int[]> wheelGeom = new HashMap<>();
    private Module listening;
    private Setting.Text typing;
    private String query = "";
    private int lastMx, lastMy;
    private boolean scaleDrag;              // the Scale slider is being dragged: keep the menu size fixed until release
    private float sc = 1f;                 // menu scale (FrameUi "Scale")
    private float ease = 1f, fade = 1f;    // scroll easing / colour-fade easing for this frame
    private final Map<Object, float[]> anim = new HashMap<>();
    private final Map<Module, float[]> expand = new HashMap<>();     // [0] = settings drawer openness 0..1
    private final long openNs = System.nanoTime();
    private int vw() { return Math.round(width / sc); }
    private int vh() { return Math.round(height / sc); }
    private long lastNs;
    private String tip;

    public FrameGuiScreen() {
        super(Component.literal(Hud.NAME));
        if (!init) {
            init = true;
            NAV.x = 10; NAV.y = 14;
            for (Category c : Category.values()) FRAMES.add(new Frame(c, null, c.label));
            Module[] pages = {find(Hud.class), find(ConfigModule.class), find(FriendsModule.class), find(FrameUi.class)};
            String[] names = {"Settings", "Config", "Friends", "Menu"};
            for (int i = 0; i < pages.length; i++) {
                if (pages[i] == null) continue;
                PAGES.add(pages[i]);
                FRAMES.add(new Frame(null, pages[i], names[i]));
            }
        }
    }

    private static Module find(Class<? extends Module> type) {
        for (Module m : KuroClient.modules.modules) if (type.isInstance(m)) return m;
        return null;
    }

    @Override public boolean isBinding() { return listening != null || typing != null; }
    @Override public boolean isPauseScreen() { return false; }

    /** 0..1 open animation, eased out. */
    private float openProgress() {
        float t = Mth.clamp((System.nanoTime() - openNs) / 200e6f, 0f, 1f);
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (Hud.menuBlur()) super.extractBackground(g, mx, my, delta);
        g.fill(0, 0, width, height, ((int) (0x4A * openProgress())) << 24);     // soft dim behind the panels
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        // the scale only changes when it is not being dragged (resizing the menu under the cursor makes the slider chase
        // itself) and always snaps to a whole number of screen pixels per GUI pixel so edges and text stay crisp
        if (!scaleDrag || sc <= 0f) sc = snapScale(Mth.clamp(FrameUi.scale(), 0.5f, 2.5f));
        float op = openProgress();
        var mat = g.pose();
        mat.pushMatrix();
        mat.scale(sc, sc);
        if (op < 1f) {                                   // short pop-in: panels grow from 96.5% about the screen centre
            float k = 0.965f + 0.035f * op;
            mat.translate(vw() / 2f, vh() / 2f);
            mat.scale(k, k);
            mat.translate(-vw() / 2f, -vh() / 2f);
        }
        try { renderScaled(g, Math.round(mx / sc), Math.round(my / sc)); }
        finally { mat.popMatrix(); }
    }

    /** Nearest scale at which one menu unit is a whole number of screen pixels. */
    private float snapScale(float want) {
        double gs = minecraft != null && minecraft.getWindow() != null ? Math.max(1.0, minecraft.getWindow().getGuiScale()) : 1.0;
        return (float) (Math.max(1L, Math.round(want * gs)) / gs);
    }

    private void renderScaled(GuiGraphicsExtractor g, int mx, int my) {
        Hud.applyTheme();
        lastMx = mx; lastMy = my; tip = null;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        ease = 1f - (float) Math.exp(-dt * 28f);       // scrolling
        fade = 1f - (float) Math.exp(-dt * 24f);       // hover / on-off colour fades

        // settings drawers: ease each module's openness toward its target once per frame
        for (Module m : KuroClient.modules.modules) {
            float[] e = expand.computeIfAbsent(m, k -> new float[]{m.expanded ? 1f : 0f});
            float tgt = m.expanded ? 1f : 0f;
            e[0] += (tgt - e[0]) * (1f - (float) Math.exp(-dt * 20f));
            if (Math.abs(tgt - e[0]) < 0.01f) e[0] = tgt;
        }
        for (Frame f : FRAMES) {
            float tgt = f.open ? 1f : 0f;
            f.oa += (tgt - f.oa) * (1f - (float) Math.exp(-dt * 20f));
            if (Math.abs(tgt - f.oa) < 0.01f) f.oa = tgt;
        }

        NAV.x = Mth.clamp(NAV.x, 0, Math.max(0, vw() - FW));
        NAV.y = Mth.clamp(NAV.y, 0, Math.max(0, vh() - HEAD));
        for (Frame f : FRAMES) {
            f.x = Mth.clamp(f.x, 0, Math.max(0, vw() - FW));
            f.y = Mth.clamp(f.y, 0, Math.max(0, vh() - HEAD));
        }

        drawNav(g, mx, my);
        for (Frame f : FRAMES) if (f.visible) drawFrame(g, f, mx, my);

        if (tip != null && FrameUi.tooltips()) {
            int w = tw(tip, FS_SMALL) + 12, h = 16;
            int x = Math.min(mx + 10, vw() - w - 2), y = Math.min(my + 12, vh() - h - 2);
            shadow(g, x, y, w, h);
            rr(g, x, y, x + w, y + h, 3, 0xF2101010);
            Ui.ring(g, x, y, x + w, y + h, 3, 0xFF2E2E2E);
            tx(g, tip, x + 6, ty(y, h, FS_SMALL), FS_SMALL, C_TEXT);
        }
    }

    /** Openness of a module's settings drawer, eased (smoothstep). */
    private float drawer(Module m) {
        float[] e = expand.get(m);
        float v = e == null ? (m.expanded ? 1f : 0f) : e[0];
        return v * v * (3f - 2f * v);
    }

    private boolean drawerOpen(Module m) { return m.expanded || drawer(m) > 0.004f; }

    private List<Row> frameRows(Frame f) {
        List<Row> rows = new ArrayList<>();
        if (f.page != null) {
            for (Setting s : f.page.settings) rows.add(new Row(f.page, s, false));
        } else {
            for (Module m : KuroClient.modules.inCategory(f.cat)) {
                rows.add(new Row(m, null, false));
                if (drawerOpen(m)) for (Setting s : m.settings) rows.add(new Row(m, s, true));
            }
        }
        return rows;
    }

    private List<Row> searchRows() {
        List<Row> rows = new ArrayList<>();
        String q = query.toLowerCase(Locale.ROOT);
        for (Module m : KuroClient.modules.sorted()) {
            if (!m.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            rows.add(new Row(m, null, false));
            if (drawerOpen(m)) for (Setting s : m.settings) rows.add(new Row(m, s, true));
        }
        return rows;
    }

    private static int fullH(Row r) {
        if (r.s == null) return ROW;
        if (r.s instanceof Setting.Num) return NUM;
        if (r.s instanceof Setting.Hue h && h.wheel) return WHEEL;
        return SET;
    }

    private int rowH(Row r) {
        int h = fullH(r);
        return r.s != null && r.exp ? Math.round(h * drawer(r.m)) : h;
    }

    private int bodyHeight(List<Row> rows, int maxH) {
        int contentH = 0;
        for (Row r : rows) contentH += rowH(r);
        return Math.min(contentH, maxH);
    }

    // ---- navigation panel ----

    private void drawNav(GuiGraphicsExtractor g, int mx, int my) {
        NAV.hits.clear();
        int x = NAV.x, y = NAV.y;
        int acc = accent();
        int head = NAV_TITLE + NAV_SEARCH;
        boolean searching = !query.isEmpty();
        int top = y + head;

        List<Row> srows = searching ? searchRows() : null;
        int bodyH;
        if (searching) bodyH = srows.isEmpty() ? ROW : bodyHeight(srows, Math.min(MAX_BODY, Math.max(ROW, vh() - top - 14)));
        else bodyH = Category.values().length * NAV_BTN + (PAGES.isEmpty() ? 0 : NAV_LABEL + PAGES.size() * NAV_BTN);
        shadow(g, x, y, FW, head + bodyH + FOOT);

        // title + search box
        Ui.corners(g, x, y, x + FW, y + head, R, C_HEAD, true, true, false, false);
        sakura(g, x + 18, y + NAV_TITLE / 2, 22);
        txb(g, Hud.NAME, x + 33, ty(y, NAV_TITLE, FS_TITLE + 1f), FS_TITLE + 1f, 0xFFFFFFFF);
        NAV.hits.add(new Hit(x, y, FW, NAV_TITLE, K_NAVTITLE, null, null, 0));
        int sx = x + 6, sy = y + NAV_TITLE, sw = FW - 12, sh = NAV_SEARCH - 7;
        rr(g, sx, sy, sx + sw, sy + sh, 4, C_INSET);
        Ui.ring(g, sx, sy, sx + sw, sy + sh, 4, searching ? acc : 0xFF232323);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        if (!searching) tx(g, "Search", sx + 8, ty(sy, sh, FS_SET), FS_SET, C_FAINT);
        else {
            int max = sw - 28;
            String shown = query;
            while (!shown.isEmpty() && tw(shown, FS_SET) > max) shown = shown.substring(1);
            tx(g, shown + (blink ? "|" : ""), sx + 8, ty(sy, sh, FS_SET), FS_SET, C_TEXT);
            tx(g, "x", sx + sw - 12, ty(sy, sh, FS_SET), FS_SET, C_MUTED);
        }
        NAV.hits.add(new Hit(sx, sy, sw, sh, K_SEARCH, null, null, 0));
        g.fill(x, top - 1, x + FW, top, 0xFF0C0C0C);

        if (searching) {
            if (srows.isEmpty()) {
                g.fill(x, top, x + FW, top + ROW, C_ROW);
                tx(g, "No modules found", x + PAD, ty(top, ROW, FS), FS, C_MUTED);
            } else {
                drawBody(g, NAV, srows, x, top, FW, Math.min(MAX_BODY, Math.max(ROW, vh() - top - 14)), mx, my);
            }
            footer(g, x, top + bodyH, FW);
            return;
        }

        Category[] cats = Category.values();
        int cy = top;
        for (int i = 0; i < cats.length; i++) {
            Frame f = frameOf(cats[i]);
            cy = navButton(g, x, cy, cats[i].label, f != null && f.visible, countOn(cats[i]), K_CAT, i, mx, my);
        }
        if (!PAGES.isEmpty()) {
            g.fill(x, cy, x + FW, cy + NAV_LABEL, C_ROW);
            txb(g, "MISC", x + PAD, ty(cy, NAV_LABEL, FS_SMALL) + 1, FS_SMALL, C_FAINT);
            cy += NAV_LABEL;
            for (int i = 0; i < PAGES.size(); i++) {
                Frame f = frameOfPage(PAGES.get(i));
                cy = navButton(g, x, cy, f != null ? f.title : PAGES.get(i).name, f != null && f.visible, 0, K_PAGE, i, mx, my);
            }
        }
        footer(g, x, cy, FW);
    }

    private static final Identifier SAKURA = Identifier.fromNamespaceAndPath("kuro", "textures/gui/icons/sakura.png");

    /** White outline-only sakura (smooth, anti-aliased texture), centred on (cx, cy), size GUI px wide. */
    private static void sakura(GuiGraphicsExtractor g, int cx, int cy, int size) {
        g.blit(RenderPipelines.GUI_TEXTURED, SAKURA, cx - size / 2, cy - size / 2, 0f, 0f, size, size, 128, 128, 128, 128, 0xFFFFFFFF);
    }

    private int navButton(GuiGraphicsExtractor g, int x, int y, String label, boolean open, int count, int kind, int idx, int mx, int my) {
        boolean hover = mx >= x && mx < x + FW && my >= y && my < y + NAV_BTN;
        float[] an = anim.computeIfAbsent(label + kind, k -> new float[]{0f, open ? 1f : 0f});
        an[0] += ((hover ? 1f : 0f) - an[0]) * fade;
        an[1] += ((open ? 1f : 0f) - an[1]) * fade;
        int bg = Ui.mix(Ui.mix(C_ROW, C_ROW_HOT, an[0]), C_ROW_SEL, an[1]);
        g.fill(x, y, x + FW, y + NAV_BTN, bg);
        if (an[1] > 0.02f) frr(g, x + 1f, y + 5, x + 3.25f, y + NAV_BTN - 5, 1.1f, alpha(accent(), an[1]));
        tx(g, label, x + PAD + 2, ty(y, NAV_BTN, FS), FS, Ui.mix(C_DIM, C_TEXT, Math.max(an[0], an[1])));
        if (count > 0) {
            String c = String.valueOf(count);
            int cw = tw(c, FS_SMALL) + 8;
            int px = x + FW - PAD - cw, py = y + (NAV_BTN - 12) / 2;
            frr(g, px, py, px + cw, py + 12, 6, alpha(accent(), 0.20f));
            tx(g, c, px + 4, ty(py, 12, FS_SMALL), FS_SMALL, C_TEXT);
        }
        NAV.hits.add(new Hit(x, y, FW, NAV_BTN, kind, null, null, idx));
        return y + NAV_BTN;
    }

    private static int countOn(Category c) {
        int n = 0;
        for (Module m : KuroClient.modules.inCategory(c)) if (m.isEnabled()) n++;
        return n;
    }

    private static Frame frameOf(Category c) {
        for (Frame f : FRAMES) if (f.cat == c) return f;
        return null;
    }

    private static Frame frameOfPage(Module m) {
        for (Frame f : FRAMES) if (f.page == m) return f;
        return null;
    }

    private static void footer(GuiGraphicsExtractor g, int x, int y, int w) {
        Ui.corners(g, x, y, x + w, y + FOOT, R, C_HEAD, false, false, true, true);
    }

    // ---- frames ----

    private void drawFrame(GuiGraphicsExtractor g, Frame f, int mx, int my) {
        f.hits.clear();
        int x = f.x, y = f.y;
        boolean showBody = f.open || f.oa > 0.004f;
        List<Row> rows = showBody ? frameRows(f) : null;
        int maxH = Math.min(MAX_BODY, Math.max(ROW, vh() - y - HEAD - 14));
        int bodyFull = showBody ? bodyHeight(rows, maxH) : 0;
        int bodyH = Math.round(bodyFull * f.oa * f.oa * (3f - 2f * f.oa));
        shadow(g, x, y, FW, HEAD + bodyH + FOOT);

        Ui.corners(g, x, y, x + FW, y + HEAD, R, C_HEAD, true, true, false, false);
        txb(g, f.title, x + PAD, ty(y, HEAD, FS_TITLE), FS_TITLE, C_TEXT);
        int on = f.cat != null ? countOn(f.cat) : 0;
        if (on > 0) {
            String c = on + " on";
            tx(g, c, x + FW - 24 - tw(c, FS_SMALL), ty(y, HEAD, FS_SMALL), FS_SMALL, alpha(accent(), 1f));
        }
        chevron(g, x + FW - 13, y + HEAD / 2, f.oa, C_MUTED);
        f.hits.add(new Hit(x, y, FW, HEAD, K_HEAD, null, null, 0));

        if (showBody && bodyH > 0) {
            g.fill(x, y + HEAD - 1, x + FW, y + HEAD, 0xFF0C0C0C);
            g.enableScissor(x, y + HEAD, x + FW, y + HEAD + bodyH);
            drawBody(g, f, rows, x, y + HEAD, FW, maxH, mx, my);
            g.disableScissor();
        }
        footer(g, x, y + HEAD + bodyH, FW);
    }

    /** Thin chevron drawn on the fine grid; t = 1 points up (open), 0 points down, in between it flattens through the middle. */
    private static void chevron(GuiGraphicsExtractor g, int cx, int cy, float t, int c) {
        final int M = FineText.M, arm = 3 * M;
        final float tilt = (t - 0.5f) * 2f;                    // -1 (down) .. 1 (up)
        FineText.fine(g, () -> {
            int fx = cx * M, fy = cy * M;
            for (int i = 0; i <= arm; i++) {
                int yy = Math.round(fy + (arm / 2f - i) * tilt);
                g.fill(fx - arm + i, yy, fx - arm + i + 2, yy + 2, c);
                g.fill(fx + arm - i - 2, yy, fx + arm - i, yy + 2, c);
            }
        });
    }

    /** Draws a scrolling list of rows under (x, top); returns the visible height. */
    private int drawBody(GuiGraphicsExtractor g, Pane p, List<Row> rows, int x, int top, int w, int maxH, int mx, int my) {
        int contentH = 0;
        for (Row r : rows) contentH += rowH(r);
        int bodyH = Math.min(contentH, maxH);
        p.maxScroll = Math.max(0, contentH - bodyH);
        p.target = Mth.clamp(p.target, 0, p.maxScroll);
        p.pos += (p.target - p.pos) * ease;
        if (Math.abs(p.target - p.pos) < 0.5f) p.pos = p.target;
        p.pos = Mth.clamp(p.pos, 0, p.maxScroll);
        p.scroll = Math.round(p.pos);
        if (bodyH <= 0) return 0;

        g.enableScissor(x, top, x + w, top + bodyH);
        int cy = top - p.scroll;
        for (Row r : rows) {
            int h = rowH(r);
            if (h > 0 && cy + h > top && cy < top + bodyH) {
                int vy = Math.max(cy, top), vh = Math.min(cy + h, top + bodyH) - vy;
                boolean hover = mx >= x && mx < x + w && my >= vy && my < vy + vh;
                if (r.s == null) drawModule(g, p, r.m, x, cy, w, vy, vh, hover);
                else {
                    g.enableScissor(x, cy, x + w, cy + h);          // a drawer that is still opening clips its rows
                    drawSetting(g, p, r.m, r.s, x, cy, w, fullH(r), vy, vh, hover);
                    g.disableScissor();
                }
            }
            cy += h;
        }
        g.disableScissor();

        return bodyH;
    }

    private void drawModule(GuiGraphicsExtractor g, Pane p, Module m, int x, int y, int w, int vy, int vh, boolean hover) {
        int acc = accent();
        boolean on = m.isEnabled(), listen = listening == m;
        // colour fades: [0] = hover, [1] = enabled, [2] = expanded / listening
        float[] an = anim.computeIfAbsent(m, k -> new float[]{0f, on ? 1f : 0f, 0f});
        an[0] += ((hover ? 1f : 0f) - an[0]) * fade;
        an[1] += ((on ? 1f : 0f) - an[1]) * fade;
        an[2] += (((m.expanded || listen) ? 1f : 0f) - an[2]) * fade;
        int bg = Ui.mix(Ui.mix(C_ROW, C_ROW_HOT, an[0]), C_ROW_SEL, an[2]);
        int fg = Ui.mix(Ui.mix(C_DIM, C_TEXT, Math.max(an[0], an[2])), onAccent(acc), an[1]);
        g.fill(x, y, x + w, y + ROW, bg);
        if (an[1] > 0.01f) {
            // enabled: accent fill that deepens towards the right edge, plus a faint top highlight
            g.fill(x, y, x + w, y + ROW, alpha(acc, an[1]));
            ShaderUi.shape(g, ShaderUi.Mode.RAMP_UP, x, y, x + w, y + ROW, 0, alpha(0xFF000000, 0.34f * an[1]));
            g.fill(x, y, x + w, y + 1, alpha(0xFFFFFFFF, 0.10f * an[1]));
        } else {
            g.fill(x, y + ROW - 1, x + w, y + ROW, C_LINE);
        }

        int right = x + w - PAD;
        if (!m.settings.isEmpty()) {
            dots(g, x + w - 10, y + ROW / 2, an[1] > 0.5f ? alpha(fg, 0.85f) : C_MUTED);
            right = x + w - 18;
        }
        boolean showBind = m.key > 0 || m.expanded || listen;
        if (showBind) {
            String label = listen ? "..." : m.key > 0 ? keyName(m.key) : "-";
            int bw = Math.max(16, tw(label, FS_SMALL) + 9), bh = 13;
            int bx = right - bw, by = y + (ROW - bh) / 2;
            frr(g, bx, by, bx + bw, by + bh, 3.5f, on ? 0x38000000 : C_INSET);
            tx(g, label, bx + (bw - tw(label, FS_SMALL)) / 2, ty(by, bh, FS_SMALL), FS_SMALL, on ? alpha(fg, 0.9f) : C_MUTED);
            int hy = Math.max(by, vy), hb = Math.min(by + bh, vy + vh);
            if (hb > hy) p.hits.add(new Hit(bx, hy, bw, hb - hy, K_BIND, m, null, 0));
            right = bx - 5;
        }
        if (listen) tx(g, "PRESS A KEY", x + PAD, ty(y, ROW, FS_SMALL), FS_SMALL, fg);
        else tx(g, trim(m.name, right - x - PAD, FS), x + PAD, ty(y, ROW, FS), FS, fg);

        if (hover) { String d = Descriptions.of(m); if (d != null && !d.isEmpty()) tip = d; }
        // dots first so a click on them beats the plain row hit
        if (!m.settings.isEmpty()) p.hits.add(new Hit(x + w - 20, vy, 20, vh, K_DOTS, m, null, 0));
        p.hits.add(new Hit(x, vy, w, vh, K_MODULE, m, null, 0));
    }

    private static void dots(GuiGraphicsExtractor g, int cx, int cy, int c) {
        final int M = FineText.M;
        FineText.fine(g, () -> {
            for (int i = -1; i <= 1; i++) {
                int fx = cx * M, fy = (cy + i * 4) * M, r = M * 9 / 8;      // round dots, ~2.25 GUI px across
                Ui.corners(g, fx - r, fy - r, fx + r, fy + r, r, c, true, true, true, true);
            }
        });
    }

    private void drawSetting(GuiGraphicsExtractor g, Pane p, Module m, Setting s, int x, int y, int w, int h, int vy, int vh, boolean hover) {
        int acc = accent();
        g.fill(x, y, x + w, y + h, C_SETBG);
        int ty = ty(y, SET, FS_SET);
        int lab = hover ? C_TEXT : C_DIM;
        if (s instanceof Setting.Bool b) {
            tx(g, trim(b.name, w - 52, FS_SET), x + PAD, ty, FS_SET, lab);
            float[] sa = anim.computeIfAbsent(b, k -> new float[]{b.value ? 1f : 0f});
            sa[0] += ((b.value ? 1f : 0f) - sa[0]) * fade;
            int sw = 20, sh = 10, bx = x + w - PAD - sw, by = y + (h - sh) / 2;
            frr(g, bx, by, bx + sw, by + sh, sh / 2f, Ui.mix(C_TRACK, acc, sa[0]));
            float kd = sh - 3f, kx = bx + 1.5f + (sw - kd - 3f) * sa[0];
            frr(g, kx - 0.4f, by + 1.9f, kx + kd + 0.4f, by + 1.9f + kd + 0.8f, (kd + 0.8f) / 2f, 0x40000000);   // knob shadow
            frr(g, kx, by + 1.5f, kx + kd, by + 1.5f + kd, kd / 2f, Ui.mix(0xFFB4B4B4, 0xFFFFFFFF, sa[0]));
        } else if (s instanceof Setting.Num n) {
            String v = n.display();
            tx(g, trim(n.name, w - 28 - tw(v, FS_SET), FS_SET), x + PAD, ty, FS_SET, lab);
            tx(g, v, x + w - PAD - tw(v, FS_SET), ty, FS_SET, C_DIM);
            int sx = x + PAD, sw = w - 2 * PAD, ly = y + h - 8;
            frr(g, sx, ly - 1.5f, sx + sw, ly + 1.5f, 1.5f, C_TRACK);
            float kx = sx + (float) (n.fraction() * sw);
            if (kx > sx) frr(g, sx, ly - 1.5f, kx, ly + 1.5f, 1.5f, acc);
            frr(g, kx - 5f, ly - 4.6f, kx + 5f, ly + 5.4f, 5f, 0x55000000);        // knob shadow
            frr(g, kx - 4f, ly - 4f, kx + 4f, ly + 4f, 4f, 0xFFFFFFFF);
        } else if (s instanceof Setting.Hue hu && hu.wheel) {
            tx(g, hueLabel(hu), x + PAD, ty, FS_SET, lab);
            frr(g, x + w - PAD - 12, y + SET / 2f - 4, x + w - PAD, y + SET / 2f + 4, 3f, hu.argb(255));
            int top = y + SET + 2, cx = x + w / 2, cy = top + WS / 2;
            wheelGeom.put(s, new int[]{x, y, w});
            Ui.wheel(g, cx - WS / 2, top, cx + WS / 2, top + WS, hu.val);
            double ang = (hu.hue - 0.5) * Math.PI * 2.0;
            float kx = cx + (float) (Math.cos(ang) * hu.sat * (WS / 2 - 2));
            float ky = cy + (float) (Math.sin(ang) * hu.sat * (WS / 2 - 2));
            frr(g, kx - 5f, ky - 5f, kx + 5f, ky + 5f, 5f, 0x66000000);
            frr(g, kx - 4f, ky - 4f, kx + 4f, ky + 4f, 4f, 0xFFFFFFFF);
            frr(g, kx - 2.8f, ky - 2.8f, kx + 2.8f, ky + 2.8f, 2.8f, hu.argb(255));
            int bx = x + 12, bw = w - 24, by = y + h - 11;
            int full = 0xFF000000 | (Mth.hsvToRgb(hu.hue, hu.sat, 1f) & 0xFFFFFF);
            Ui.gradient(g, bx, by, bx + bw, by + 5, 2, 0xFF000000, full);
            float bk = bx + hu.val * bw;
            frr(g, bk - 3f, by - 1.6f, bk + 3f, by + 6.6f, 3f, 0x55000000);
            frr(g, bk - 2.4f, by - 1f, bk + 2.4f, by + 6f, 2.4f, 0xFFFFFFFF);
        } else if (s instanceof Setting.Hue hu) {
            tx(g, hueLabel(hu), x + PAD, ty, FS_SET, lab);
            int off = hueOff(hu, w), sx = x + off, sw = w - off - 22;
            Ui.hueStrip(g, sx, y + 7, sx + sw, y + h - 7, 3);
            float mxp = sx + hu.hue * sw;
            frr(g, mxp - 2.2f, y + 4.5f, mxp + 2.2f, y + h - 4.5f, 2.2f, 0xFFFFFFFF);
            frr(g, x + w - PAD - 10, y + h / 2f - 4, x + w - PAD, y + h / 2f + 4, 3f, hu.argb(255));
        } else if (s instanceof Setting.Text t) {
            boolean focus = typing == t;
            rr(g, x + 6, y + 2, x + w - 6, y + h - 2, 4, C_INSET);
            Ui.ring(g, x + 6, y + 2, x + w - 6, y + h - 2, 4, focus ? acc : 0xFF232323);
            String caret = focus && (System.currentTimeMillis() / 500) % 2 == 0 ? "|" : "";
            if (t.value.isEmpty()) {
                tx(g, trim(t.name, w - 28, FS_SET), x + PAD + 2, ty, FS_SET, C_FAINT);
                if (!caret.isEmpty()) tx(g, caret, x + PAD + 2, ty, FS_SET, C_TEXT);
            } else {
                String shown = t.value;
                while (!shown.isEmpty() && tw(shown, FS_SET) > w - 32) shown = shown.substring(1);
                tx(g, shown + caret, x + PAD + 2, ty, FS_SET, C_TEXT);
            }
        } else if (s instanceof Setting.Button btn) {
            rr(g, x + 6, y + 2, x + w - 6, y + h - 2, 4, hover ? 0xFF2C2C2C : 0xFF242424);
            Ui.ring(g, x + 6, y + 2, x + w - 6, y + h - 2, 4, 0xFF2E2E2E);
            String shown = trim(btn.name, w - 28, FS_SET);
            tx(g, shown, x + (w - tw(shown, FS_SET)) / 2, ty, FS_SET, C_TEXT);
        }
        p.hits.add(new Hit(x, vy, w, vh, K_SETTING, m, s, y));
    }

    private static String hueLabel(Setting.Hue h) {
        String t = h.name.replaceAll("(?i)\\s+colou?r$", "");
        return t.isEmpty() ? h.name : t;
    }

    private static int hueOff(Setting.Hue h, int w) {
        return Math.min(w / 2 + 4, Math.max(40, PAD + tw(hueLabel(h), FS_SET) + 6));
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mx = click.x() / sc, my = click.y() / sc;
        int button = click.button();
        typing = null;
        for (int i = FRAMES.size() - 1; i >= 0; i--) {
            Frame f = FRAMES.get(i);
            if (!f.visible) continue;
            for (Hit h : f.hits) {
                if (h.contains(mx, my)) {
                    FRAMES.remove(i);
                    FRAMES.add(f);          // bring to the front
                    handle(f, h, button, mx, my);
                    return true;
                }
            }
        }
        for (Hit h : NAV.hits) {
            if (h.contains(mx, my)) { handle(NAV, h, button, mx, my); return true; }
        }
        return super.mouseClicked(click, doubled);
    }

    private void handle(Pane p, Hit h, int button, double mx, double my) {
        switch (h.kind()) {
            case K_HEAD -> {
                Frame f = (Frame) p;
                if (button == 0) { dragPane = p; dragDx = (int) mx - p.x; dragDy = (int) my - p.y; }
                else if (button == 1) f.open = !f.open;
            }
            case K_NAVTITLE -> { if (button == 0) { dragPane = p; dragDx = (int) mx - p.x; dragDy = (int) my - p.y; } }
            case K_SEARCH -> { if (button == 0 && !query.isEmpty() && mx >= h.x() + h.w() - 16) query = ""; }
            case K_CAT -> { if (button == 0) toggleFrame(frameOf(Category.values()[h.idx()])); }
            case K_PAGE -> { if (button == 0) toggleFrame(frameOfPage(PAGES.get(h.idx()))); }
            case K_MODULE -> {
                Module m = h.module();
                if (button == 0) m.toggle();
                else if (button == 1 && !m.settings.isEmpty()) m.expanded = !m.expanded;
                else if (button == 2) listening = m;
            }
            case K_DOTS -> { if (button == 0 || button == 1) h.module().expanded = !h.module().expanded; }
            case K_BIND -> { if (button == 0 || button == 2) listening = h.module(); else if (button == 1) h.module().key = -1; }
            case K_SETTING -> {
                if (button != 0) return;
                Setting s = h.setting();
                if (s instanceof Setting.Bool b) b.value = !b.value;
                else if (s instanceof Setting.Button btn) btn.action.run();
                else if (s instanceof Setting.Text t) typing = t;
                else if (s instanceof Setting.Num) {
                    dragPart = 0;
                    scaleDrag = h.module() instanceof FrameUi && "Scale".equals(s.name);
                    startSlider(s, h.x() + PAD, h.w() - 2 * PAD, mx);
                }
                else if (s instanceof Setting.Hue hue && hue.wheel) startWheel(hue, mx, my);
                else if (s instanceof Setting.Hue hue) { int off = hueOff(hue, h.w()); dragPart = 0; startSlider(s, h.x() + off, h.w() - off - 22, mx); }
            }
            default -> {}
        }
    }

    private void toggleFrame(Frame f) {
        if (f == null) return;
        f.visible = !f.visible;
        if (!f.visible) return;
        if (!f.placed) {
            f.placed = true;
            int n = 0;
            for (Frame o : FRAMES) if (o != f && o.visible) n++;
            f.x = NAV.x + FW + 8 + n * (FW + 6);
            f.y = NAV.y;
            if (f.x + FW > vw()) { f.x = Math.max(0, vw() - FW - 4); f.y = NAV.y + 14 * (n % 6); }
        }
        FRAMES.remove(f);
        FRAMES.add(f);
    }

    private void startSlider(Setting s, int x, int w, double mx) {
        activeSlider = s; sliderX = x; sliderW = w;
        applySlider(mx, lastMy);
    }

    private void startWheel(Setting.Hue hu, double mx, double my) {
        int[] gm = wheelGeom.get(hu);
        if (gm == null || my < gm[1] + SET) return;      // the label row does nothing
        activeSlider = hu;
        wheelCx = gm[0] + gm[2] / 2;
        wheelCy = gm[1] + SET + 2 + WS / 2;
        barX = gm[0] + 12; barW = gm[2] - 24;
        dragPart = my >= gm[1] + WHEEL - 17 ? 2 : 1;      // bottom strip = brightness, above it = wheel
        applySlider(mx, my);
    }

    private void applySlider(double mx, double my) {
        if (activeSlider instanceof Setting.Num n) n.setFraction((mx - sliderX) / sliderW);
        else if (activeSlider instanceof Setting.Hue h) {
            if (!h.wheel || dragPart == 0) h.hue = (float) Mth.clamp((mx - sliderX) / sliderW, 0.0, 0.999);
            else if (dragPart == 2) h.val = (float) Mth.clamp((mx - barX) / barW, 0.03, 1.0);
            else {
                double dx = mx - wheelCx, dy = my - wheelCy, r = WS / 2 - 2;
                h.hue = (float) Mth.clamp(Math.atan2(dy, dx) / (Math.PI * 2.0) + 0.5, 0.0, 0.999);
                h.sat = (float) Mth.clamp(Math.hypot(dx, dy) / r, 0.0, 1.0);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        mx /= sc; my /= sc;
        for (int i = FRAMES.size() - 1; i >= 0; i--) {
            Frame f = FRAMES.get(i);
            if (!f.visible || !f.open || f.maxScroll <= 0) continue;
            if (mx >= f.x && mx < f.x + FW && my >= f.y && my < f.y + HEAD + Math.min(MAX_BODY, vh())) {
                f.target = Mth.clamp(f.target - (float) vAmount * 40f, 0, f.maxScroll);
                return true;
            }
        }
        if (!query.isEmpty() && NAV.maxScroll > 0 && mx >= NAV.x && mx < NAV.x + FW) {
            NAV.target = Mth.clamp(NAV.target - (float) vAmount * 40f, 0, NAV.maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, hAmount, vAmount);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        if (dragPane != null) {
            dragPane.x = Mth.clamp((int) (click.x() / sc) - dragDx, 0, Math.max(0, vw() - FW));
            dragPane.y = Mth.clamp((int) (click.y() / sc) - dragDy, 0, Math.max(0, vh() - HEAD));
            return true;
        }
        if (activeSlider != null) { applySlider(click.x() / sc, click.y() / sc); return true; }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        dragPane = null;
        activeSlider = null;
        scaleDrag = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        int cp = input.codepoint();
        if (typing != null) {
            if (typing.value.length() < typing.maxLen && !Character.isISOControl(cp) && !Character.isWhitespace(cp))
                typing.value += new String(Character.toChars(cp));
            return true;
        }
        if (listening != null) return false;
        if (Character.isISOControl(cp)) return false;
        String t = new String(Character.toChars(cp));
        if (t.isBlank() && query.isEmpty()) return true;
        if (query.length() < 32) {
            query += t;
            NAV.scroll = 0; NAV.pos = 0; NAV.target = 0;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.input();
        if (typing != null) {   // a text box has focus: it gets every key
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) typing.submit();
            else if (key == GLFW.GLFW_KEY_ESCAPE) typing = null;
            else if (key == GLFW.GLFW_KEY_BACKSPACE) { if (!typing.value.isEmpty()) typing.value = typing.value.substring(0, typing.value.length() - 1); }
            else if (key == GLFW.GLFW_KEY_V && minecraft != null && minecraft.getWindow() != null
                    && (GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS)) {
                String clip = GLFW.glfwGetClipboardString(minecraft.getWindow().handle());
                if (clip != null) for (char ch : clip.toCharArray())
                    if (!Character.isISOControl(ch) && !Character.isWhitespace(ch) && typing.value.length() < typing.maxLen) typing.value += ch;
            }
            return true;
        }
        if (listening != null) {
            boolean clear = key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE;
            listening.key = clear ? -1 : key;
            listening = null;
            return true;
        }
        if (!query.isEmpty()) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) { query = query.substring(0, query.length() - 1); return true; }
            if (key == GLFW.GLFW_KEY_ESCAPE) { query = ""; return true; }
        }
        if (key == dev.kuro.client.util.MenuKey.key()) { KuroClient.modules.consumeGuiKey(); onClose(); return true; }
        return super.keyPressed(input);
    }

    private static String keyName(int key) {
        String n = GLFW.glfwGetKeyName(key, 0);
        return n != null ? n.toUpperCase(Locale.ROOT) : "K" + key;
    }
}
