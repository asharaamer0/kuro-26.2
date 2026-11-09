package dev.kuro.client.module.impl.utility;

import dev.kuro.client.KuroClient;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;

/** Global rotation preferences for modules using Kuro's Echo rotation helpers. */
public final class SilentRotation extends Module {
    private static final int SILENT = 0;
    private static final int CLIENT = 1;

    private final Setting.Num mode = add(new Setting.Num("Mode", SILENT, SILENT, CLIENT, 1)
            .labelled("Silent", "Client"));
    private final Setting.Bool gcdFix = add(new Setting.Bool("GCD Fix", true));
    private final Setting.Bool returnToView = add(new Setting.Bool("Return To View", true));
    private final Setting.Num returnSpeed = add(new Setting.Num("Return Speed", 150, 10, 600, 10));
    private final Setting.Num speedMultiplier = add(new Setting.Num("Speed Multiplier", 1.0, 0.1, 3.0, 0.05));

    // Kuro's rotation APIs enforce vanilla pitch bounds, so pitch limiting remains enabled.
    public SilentRotation() {
        super("Silent Rotation", Category.MISC);
    }

    public boolean isSilent() {
        return mode.intValue() == SILENT;
    }

    public boolean isClient() {
        return mode.intValue() == CLIENT;
    }

    public boolean isGcdFix() {
        return gcdFix.value;
    }

    public boolean isReturnToView() {
        return returnToView.value;
    }

    public float getReturnSpeed() {
        return (float) returnSpeed.value;
    }

    public float getSpeedMultiplier() {
        return (float) speedMultiplier.value;
    }

    public boolean isStartFromCamera() {
        return true;
    }

    public boolean isLimitPitch() {
        return true;
    }

    /** Returns the enabled settings module, or null to preserve each caller's existing behavior. */
    public static SilentRotation get() {
        if (KuroClient.modules == null) return null;
        for (Module module : KuroClient.modules.modules) {
            if (module instanceof SilentRotation silentRotation && module.isEnabled()) {
                return silentRotation;
            }
        }
        return null;
    }

    public static boolean gcdFixEnabled() {
        SilentRotation module = get();
        return module == null || module.isGcdFix();
    }

    public static boolean returnToViewEnabled() {
        SilentRotation module = get();
        return module == null || module.isReturnToView();
    }

    public static float returnSpeedOr(float fallback) {
        SilentRotation module = get();
        return module == null ? fallback : module.getReturnSpeed();
    }

    public static float speedMultiplier() {
        SilentRotation module = get();
        return module == null ? 1.0F : module.getSpeedMultiplier();
    }

    public static boolean startFromCameraEnabled() {
        return true;
    }
}
