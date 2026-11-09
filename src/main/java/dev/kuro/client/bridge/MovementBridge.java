package dev.kuro.client.bridge;

import dev.kuro.client.util.RotationEngine;
import dev.kuro.client.util.RotationHandler;
import dev.kuro.client.util.Rotations;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;

public final class MovementBridge {

    public interface Hook {
        float getMovementYaw(float vanillaYaw);

        Input correctInput(Input original);

        boolean shouldKeepSprint();

        boolean rawHasForwardImpulse();

        boolean isSprintFixActive();

        boolean isChangeLook();

        boolean isHandlingCorrection();

        void prepareMovementCorrection(LocalPlayer player);

        void onInputCorrected(boolean changed);
    }

    private static Hook hook;

    private static int inputFreezeTicks = 0;

    private static boolean hasCorrection;
    private static float correctionCos = 1.0f;
    private static float correctionSin = 0.0f;

    private static boolean rotationSwapped;
    private static float savedYaw;
    private static float savedPitch;

    public static void register(Hook h) {
        hook = h;
    }

    public static void freezeInput(int ticks) {
        inputFreezeTicks = Math.max(inputFreezeTicks, ticks);
    }

    public static float getMovementYaw(float vanillaYaw) {
        return hook == null ? vanillaYaw : hook.getMovementYaw(vanillaYaw);
    }

    public static Input correctInput(Input original) {
        if (inputFreezeTicks > 0) {
            inputFreezeTicks--;
            return Input.EMPTY;
        }
        Input input = hook == null ? original : hook.correctInput(original);

        if (!hasCorrection) return input;
        hasCorrection = false;

        Input corrected = rotate(input, correctionCos, correctionSin);
        if (hook != null) hook.onInputCorrected(!corrected.equals(input));
        return corrected;
    }

    private static Input rotate(Input original, float cos, float sin) {
        if (original == null) return Input.EMPTY;

        float x = (original.left() ? 1.0f : 0.0f) - (original.right() ? 1.0f : 0.0f);
        float z = (original.forward() ? 1.0f : 0.0f) - (original.backward() ? 1.0f : 0.0f);

        int sideways = Math.round(x * cos - z * sin);
        int forward = Math.round(z * cos + x * sin);

        return new Input(
                forward > 0,
                forward < 0,
                sideways > 0,
                sideways < 0,
                original.jump(),
                original.shift(),
                original.sprint()
        );
    }

    public static boolean shouldKeepSprint() {
        return hook != null && hook.shouldKeepSprint();
    }

    public static boolean rawHasForwardImpulse() {
        return hook != null && hook.rawHasForwardImpulse();
    }

    public static boolean isSprintFixActive() {
        return hook != null && hook.isSprintFixActive();
    }

    public static boolean isChangeLook() {
        return hook != null && hook.isChangeLook();
    }

    public static boolean isHandlingCorrection() {
        return hook != null && hook.isHandlingCorrection();
    }

    public static void prepareMovementCorrection(LocalPlayer player) {
        hasCorrection = false;

        if (hook != null && hook.isHandlingCorrection()) {
            hook.prepareMovementCorrection(player);
        } else {
            publishDefaultCorrection(player);
        }

        applyTickRotation(player);
    }

    public static float[] getPacketRotation() {
        if (RotationHandler.isSilentActive()) {
            return new float[]{RotationHandler.getSilentYaw(), RotationHandler.getSilentPitch()};
        }
        if (Rotations.active()) {
            return new float[]{Rotations.yaw(), Rotations.pitch()};
        }
        if (RotationEngine.INSTANCE.isControllerUpdating()) {
            return new float[]{
                    RotationEngine.INSTANCE.getManagedYaw(),
                    Mth.clamp(RotationEngine.INSTANCE.getManagedPitch(), -90.0f, 90.0f)
            };
        }
        return null;
    }

    private static void publishDefaultCorrection(LocalPlayer player) {
        if (player == null) return;
        float[] aim = getPacketRotation();
        if (aim == null) return;

        float delta = Mth.wrapDegrees(aim[0] - player.getYRot());
        float pitchDelta = Mth.clamp(aim[1], -90.0f, 90.0f) - player.getXRot();
        if (Math.abs(delta) < 0.05f && Math.abs(pitchDelta) < 0.05f) return;
        setInputCorrection(delta);
    }

    public static void setInputCorrection(float yawDeltaDegrees) {
        float radians = (float) Math.toRadians(-yawDeltaDegrees);
        correctionCos = (float) Math.cos(radians);
        correctionSin = (float) Math.sin(radians);
        hasCorrection = true;
    }

    private static void applyTickRotation(LocalPlayer player) {
        if (player == null) {
            rotationSwapped = false;
            return;
        }
        float[] aim = getPacketRotation();
        if (aim == null) {
            restoreTickRotation(player);
            return;
        }

        float realYaw = player.getYRot();
        float realPitch = player.getXRot();
        float aimYaw = aim[0];
        float aimPitch = Mth.clamp(aim[1], -90.0f, 90.0f);

        if (Math.abs(Mth.wrapDegrees(aimYaw - realYaw)) < 0.05f
                && Math.abs(aimPitch - realPitch) < 0.05f) {
            restoreTickRotation(player);
            return;
        }

        if (!rotationSwapped) {
            savedYaw = realYaw;
            savedPitch = realPitch;
            rotationSwapped = true;
        }
        player.setYRot(aimYaw);
        player.setXRot(aimPitch);
    }

    public static void restoreTickRotation(LocalPlayer player) {
        if (!rotationSwapped) return;
        rotationSwapped = false;
        if (player == null) return;
        player.setYRot(savedYaw);
        player.setXRot(savedPitch);
    }

    public static boolean isTickRotationActive() {
        return rotationSwapped;
    }

    private MovementBridge() {
    }
}
