package dev.kuro.client.echo.handlers;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side rotation state (what the movement packet carries) and the silent-rotation flag.
 * Port of hack.raze.client.handlers.RotationHandler.
 *
 * Fed by dev.kuro.client.mixin.ClientPlayerEntityMixin (sendPosition): EventMove.Pre carries these values.
 */
public class RotationHandler {
    private static float serverYaw = 0f;
    private static float serverPitch = 0f;
    private static float prevServerYaw = 0f;
    private static float prevServerPitch = 0f;
    private static float serverDeltaYaw = 0f;
    private static boolean hasSilentRotation = false;


    // Third-person preview rotations, advanced once per tick like vanilla.
    // The body uses the vanilla tickHeadTurn simulation and is interpolated at
    // render time with Mth.rotLerp, instead of being eased per frame.
    private static float renderYaw;
    private static float prevRenderYaw;
    private static float renderPitch;
    private static float prevRenderPitch;
    private static float renderBodyYaw;
    private static float prevRenderBodyYaw;
    private static boolean renderInitialized;
    private static boolean renderBodyYawActive;

    // Per-frame third-person head smoothing. Advanced while rendering (so it runs at
    // frame rate, not tick rate) using a frame-rate independent exponential factor.
    private static final float FRAME_SMOOTHING = 30.0F;
    private static final long FRAME_STALE_NANOS = 250_000_000L;
    private static float frameYaw;
    private static float framePitch;
    private static long frameNanos;
    private static boolean frameInit;


    public static float getServerYaw() { return serverYaw; }
    public static void setServerYaw(float v) { serverYaw = v; }
    public static float getServerPitch() { return serverPitch; }
    public static void setServerPitch(float v) { serverPitch = v; }
    public static float getPrevServerYaw() { return prevServerYaw; }
    public static float getPrevServerPitch() { return prevServerPitch; }
    public static float getServerDeltaYaw() { return serverDeltaYaw; }
    public static void setServerDeltaYaw(float v) { serverDeltaYaw = v; }
    public static boolean isHasSilentRotation() { return hasSilentRotation; }
    public static void setHasSilentRotation(boolean v) { hasSilentRotation = v; }

    public static float getRenderYaw() { return renderYaw; }
    public static float getPrevRenderYaw() { return prevRenderYaw; }
    public static float getRenderPitch() { return renderPitch; }
    public static float getPrevRenderPitch() { return prevRenderPitch; }
    public static float getRenderBodyYaw() { return renderBodyYaw; }
    public static float getPrevRenderBodyYaw() { return prevRenderBodyYaw; }

    public static float getFrameRenderYaw() { return frameYaw; }
    public static float getFrameRenderPitch() { return framePitch; }

    public static float getEnforcedPitch(float requestedPitch) {
        return Mth.clamp(requestedPitch, -90f, 90f);
    }

    /** Update server rotations (called every tick to keep them in sync); keeps yaw continuous (AimModulo360). */
    public static void updateServerRotations(float yaw, float pitch) {
        prevServerYaw = serverYaw;
        prevServerPitch = serverPitch;
        serverYaw = serverYaw + Mth.wrapDegrees(yaw - serverYaw);
        serverPitch = Mth.clamp(getEnforcedPitch(pitch), -90f, 90f);
        serverDeltaYaw = 0f;
    }

    /** Set server rotations with delta tracking for silent aim. */
    public static void setServerRotationsWithDelta(float yaw, float pitch) {
        prevServerYaw = serverYaw;
        prevServerPitch = serverPitch;
        serverDeltaYaw = Mth.wrapDegrees(yaw - serverYaw);
        serverYaw = yaw;
        serverPitch = Mth.clamp(getEnforcedPitch(pitch), -90f, 90f);
    }

    /**
     * Advance the third-person preview once per tick, from the local player's tick.
     */
    public static void updateRenderRotations(LocalPlayer player) {
        if (!renderInitialized) {
            renderYaw = player.getYRot();
            prevRenderYaw = player.yRotO;
            renderPitch = player.getXRot();
            prevRenderPitch = player.xRotO;
            renderInitialized = true;
        } else {
            prevRenderYaw = renderYaw;
            prevRenderPitch = renderPitch;
            renderYaw += Mth.wrapDegrees(serverYaw - renderYaw) * 0.4F;
            renderPitch += (serverPitch - renderPitch) * 0.4F;
        }

        updateRenderBodyYaw(player, serverYaw);
    }

    public static void updateRenderBodyYaw(LocalPlayer player, float aimYaw) {
        if (!renderBodyYawActive) {
            renderBodyYaw = player.yBodyRot;
            prevRenderBodyYaw = player.yBodyRotO;
            renderBodyYawActive = true;
        } else {
            prevRenderBodyYaw = renderBodyYaw;
        }

        double dx = player.getX() - player.xo;
        double dz = player.getZ() - player.zo;
        float targetBodyYaw = renderBodyYaw;

        if (dx * dx + dz * dz > 0.0025D) {
            float moveYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
            float viewDiff = Math.abs(Mth.wrapDegrees(aimYaw - moveYaw));
            targetBodyYaw = (viewDiff > 95.0F && viewDiff < 265.0F) ? moveYaw - 180.0F : moveYaw;
        }
        if (player.attackAnim > 0.0F) {
            targetBodyYaw = aimYaw;
        }

        renderBodyYaw += Mth.wrapDegrees(targetBodyYaw - renderBodyYaw) * 0.3F;

        float headDelta = Mth.wrapDegrees(aimYaw - renderBodyYaw);
        headDelta = Mth.clamp(headDelta, -75.0F, 75.0F);
        renderBodyYaw = aimYaw - headDelta;
        if (headDelta * headDelta > 2500.0F) {
            renderBodyYaw += headDelta * 0.2F;
        }

        while (renderBodyYaw - prevRenderBodyYaw < -180.0F) prevRenderBodyYaw -= 360.0F;
        while (renderBodyYaw - prevRenderBodyYaw >= 180.0F) prevRenderBodyYaw += 360.0F;
    }

    public static void updateFrameRenderRotations() {
        long now = System.nanoTime();
        if (!frameInit || now - frameNanos > FRAME_STALE_NANOS) {
            frameYaw = serverYaw;
            framePitch = serverPitch;
            frameNanos = now;
            frameInit = true;
            return;
        }
        float dt = (now - frameNanos) / 1.0E9F;
        frameNanos = now;
        if (dt <= 0.0F) return;
        if (dt > 0.1F) dt = 0.1F;
        float alpha = 1.0F - (float) Math.exp(-FRAME_SMOOTHING * dt);
        frameYaw += Mth.wrapDegrees(serverYaw - frameYaw) * alpha;
        framePitch += (serverPitch - framePitch) * alpha;
    }

    public static void clearRenderBodyYaw() {
        renderBodyYawActive = false;
        renderBodyYaw = 0.0F;
        prevRenderBodyYaw = 0.0F;
        renderInitialized = false;
    }

    public static void reset() {
        serverDeltaYaw = 0f;
        hasSilentRotation = false;
        renderInitialized = false;
        frameInit = false;
        clearRenderBodyYaw();
    }

    public static void syncToPlayer(float playerYaw, float playerPitch) {
        prevServerYaw = serverYaw;
        prevServerPitch = serverPitch;
        serverYaw = serverYaw + Mth.wrapDegrees(playerYaw - serverYaw);
        serverPitch = Mth.clamp(getEnforcedPitch(playerPitch), -90f, 90f);
        serverDeltaYaw = 0f;
        hasSilentRotation = false;
        renderInitialized = false;
        frameInit = false;
        clearRenderBodyYaw();
    }

    public static Vec3 getRotationVector(float pitch, float yaw) {
        float pitchRad = pitch * Mth.DEG_TO_RAD;
        float yawRad = -yaw * Mth.DEG_TO_RAD;
        float cosPitch = Mth.cos(pitchRad);
        float sinPitch = Mth.sin(pitchRad);
        float cosYaw = Mth.cos(yawRad);
        float sinYaw = Mth.sin(yawRad);
        return new Vec3(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
    }
}
