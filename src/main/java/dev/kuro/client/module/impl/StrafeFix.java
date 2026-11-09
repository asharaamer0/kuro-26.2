package dev.kuro.client.module.impl;

import dev.kuro.client.KuroClient;
import dev.kuro.client.bridge.MovementBridge;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.RotationHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;

public class StrafeFix extends Module implements MovementBridge.Hook {

    public enum Mode {
        NONE,
        SILENT,
        STRICT,
        CHANGE_LOOK
    }

    private static final float DEG_TO_RAD = 0.017453292f;

    private static StrafeFix instance;

    private static Input rawInput = Input.EMPTY;
    private static boolean inputRewritten = false;

    private static Mode forcedMode = null;

    private final Setting.Num moveCorrection = add(new Setting.Num("Move Correction", 1, 0, 3, 1).labelled("None", "Silent", "Strict", "Change Look"));
    private final Setting.Bool sprintFix = add(new Setting.Bool("Sprint Fix", true));

    public StrafeFix() {
        super("StrafeFix", Category.MOVEMENT);
        instance = this;
        MovementBridge.register(this);
        setEnabled(true);
    }

    private static StrafeFix get() {
        if (instance == null && KuroClient.modules != null) {
            for (Module m : KuroClient.modules.modules) {
                if (m instanceof StrafeFix sf) {
                    instance = sf;
                    break;
                }
            }
        }
        return instance;
    }

    public static Mode getMode() {
        if (forcedMode != null) return forcedMode;
        StrafeFix fix = get();
        if (fix == null || !fix.isEnabled()) return Mode.NONE;
        return switch (fix.moveCorrection.intValue()) {
            case 0 -> Mode.NONE;
            case 1 -> Mode.SILENT;
            case 2 -> Mode.STRICT;
            case 3 -> Mode.CHANGE_LOOK;
            default -> Mode.SILENT;
        };
    }

    public static void forceMode(Mode mode) {
        forcedMode = mode;
    }

    public static void clearForcedMode() {
        forcedMode = null;
    }

    private static boolean hasActiveSilent() {
        float[] packet = MovementBridge.getPacketRotation();
        if (packet == null) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        return Math.abs(RotationHandler.getAngleDifference(packet[0], mc.player.getYRot())) > 0.05f;
    }

    private static float getActiveSilentYaw() {
        float[] packet = MovementBridge.getPacketRotation();
        if (packet != null) return packet[0];
        return RotationHandler.getSilentYaw();
    }

    private static float getActiveSilentPitch() {
        float[] packet = MovementBridge.getPacketRotation();
        if (packet != null) return packet[1];
        return RotationHandler.getSilentPitch();
    }

    public static boolean isCorrecting() {
        Mode mode = getMode();
        return (mode == Mode.SILENT || mode == Mode.STRICT) && hasActiveSilent();
    }

    public static boolean isChangeLookActive() {
        return getMode() == Mode.CHANGE_LOOK && hasActiveSilent();
    }

    public static float getYawDelta() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !hasActiveSilent()) return 0.0f;
        return RotationHandler.getAngleDifference(getActiveSilentYaw(), mc.player.getYRot());
    }

    @Override
    public float getMovementYaw(float vanillaYaw) {
        return (isCorrecting() || isChangeLook()) ? getActiveSilentYaw() : vanillaYaw;
    }

    @Override
    public Input correctInput(Input original) {
        rawInput = original;
        return original;
    }

    @Override
    public boolean shouldKeepSprint() {
        return isEnabled() && sprintFix.value && inputRewritten;
    }

    @Override
    public boolean rawHasForwardImpulse() {
        return rawInput.forward() && !rawInput.backward();
    }

    @Override
    public boolean isSprintFixActive() {
        return isEnabled() && sprintFix.value;
    }

    @Override
    public boolean isChangeLook() {
        return isChangeLookActive();
    }

    @Override
    public boolean isHandlingCorrection() {
        return isEnabled();
    }

    @Override
    public void prepareMovementCorrection(LocalPlayer player) {
        if (player == null) return;

        if (isChangeLook()) {
            float yaw = getActiveSilentYaw();
            float pitch = Mth.clamp(getActiveSilentPitch(), -90.0f, 90.0f);
            player.setYRot(yaw);
            player.yHeadRot = yaw;
            player.setXRot(pitch);

            if (Freelook.active()) {
                Freelook fl = Freelook.get();
                if (fl != null) {
                    fl.yaw = yaw;
                    fl.pitch = pitch;
                }
            }
            return;
        }

        if (getMode() != Mode.SILENT || !hasActiveSilent()) {
            inputRewritten = false;
            return;
        }

        MovementBridge.setInputCorrection(getYawDelta());
    }

    @Override
    public void onInputCorrected(boolean changed) {
        inputRewritten = changed;
    }

    @Override
    public void onDisable() {
        super.onDisable();
        rawInput = Input.EMPTY;
        inputRewritten = false;
    }
}
