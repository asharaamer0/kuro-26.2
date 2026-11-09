package dev.kuro.client.builder.gui;

/** Palette ported from the uploaded CLI click-GUI mockup. Colours are 0xRRGGBB. */
public final class CliTheme {
    public static final int TEXT = 0xD6DAD4, BRIGHT = 0xEEF1EC, DIM = 0x8D948B, FAINT = 0x5E655D,
            WARN = 0xC9A86A, ERROR = 0xD9776F, CLOSE = 0xB2453F, WHITE = 0xFFFFFF, BLACK = 0x000000;

    public static final int DEFAULT_ACCENT = 0x8CB876;

    public static final int[] PALETTE = {0x8CB876, 0xA3BE8C, 0x6FA8DC, 0x88C0D0, 0xB48EAD, 0xD9776F,
            0xD08770, 0xC9A86A, 0xEBCB8B, 0xECEFF4, 0x5E81AC, 0x7A8279};

    private CliTheme() { }

    public static int mix(int a, int b, float t) {
        int out = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            float x = (a >> shift) & 0xFF, y = (b >> shift) & 0xFF;
            out |= Math.round(x + (y - x) * t) << shift;
        }
        return out;
    }

    /** Black background with a subtle hue derived from the GUI color / accent. */
    static int bg(int accent) { return mix(0x020202, accent, 0.05f); }
    static int panel(int accent) { return mix(0x080808, accent, 0.07f); }
    static int panelHi(int accent) { return mix(0x141414, accent, 0.11f); }
    static int rowHot(int accent) { return mix(0x101010, accent, 0.09f); }
    static int line(int accent) { return mix(0x1A1A1A, accent, 0.11f); }
    static int border(int accent) { return mix(0x222222, accent, 0.13f); }
    static int borderHot(int accent) { return mix(0x363636, accent, 0.17f); }
    static int boxBorder(int accent) { return mix(0x282828, accent, 0.13f); }
    static int boxHot(int accent) { return mix(0x404040, accent, 0.18f); }
    static int track(int accent) { return mix(0x161616, accent, 0.09f); }
    static int scroll(int accent) { return mix(0x222222, accent, 0.12f); }
    static int popup(int accent) { return mix(0x0A0A0A, accent, 0.08f); }
    static int windowEdge(int accent) { return mix(0x1C1C1C, accent, 0.13f); }

    static final int BG = bg(DEFAULT_ACCENT);
    static final int PANEL = panel(DEFAULT_ACCENT);
    static final int PANEL_HI = panelHi(DEFAULT_ACCENT);
    static final int ROW_HOT = rowHot(DEFAULT_ACCENT);
    static final int LINE = line(DEFAULT_ACCENT);
    static final int BORDER = border(DEFAULT_ACCENT);
    static final int BORDER_HOT = borderHot(DEFAULT_ACCENT);
    static final int BOX_BORDER = boxBorder(DEFAULT_ACCENT);
    static final int BOX_HOT = boxHot(DEFAULT_ACCENT);
    static final int TRACK = track(DEFAULT_ACCENT);
    static final int SCROLL = scroll(DEFAULT_ACCENT);
    static final int POPUP = popup(DEFAULT_ACCENT);
    static final int WINDOW_EDGE = windowEdge(DEFAULT_ACCENT);
}
