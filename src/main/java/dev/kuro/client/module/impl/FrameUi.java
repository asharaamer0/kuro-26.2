package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;

/**
 * The frame menu (navigation panel with a search box on the left, one draggable frame per category).
 * Switching this module on forces it over the other styles. Its colour and size are set here
 * (open the "Menu" page in the menu's own nav panel).
 */
public class FrameUi extends Module {
    private static FrameUi instance;
    private final Setting.Bool tooltips = add(new Setting.Bool("Tooltips", true));
    private final Setting.Num scale = add(new Setting.Num("Scale", 1.0, 0.7, 1.6, 0.05));
    private final Setting.Hue color = add(new Setting.Hue("Menu color", 0.46f).wheel(0.96f, 0.53f));   // default: teal

    public FrameUi() {
        super("Frame UI", Category.RENDER);
        instance = this;
        setEnabled(true);
    }

    public static boolean active() { return instance != null && instance.isEnabled(); }
    public static boolean tooltips() { return instance == null || instance.tooltips.value; }
    /** Menu size multiplier (1 = normal). */
    public static float scale() { return instance == null ? 1f : (float) instance.scale.value; }
    /** Accent colour for enabled rows, sliders, switches and toasts (0xFFRRGGBB). */
    public static int accent() { return instance == null ? 0xFF058669 : 0xFF000000 | instance.color.rgb(); }
}
