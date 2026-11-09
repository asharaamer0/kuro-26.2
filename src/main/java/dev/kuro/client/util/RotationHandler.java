package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.concurrent.atomic.AtomicBoolean;

public class RotationHandler {
    public static final int PRIORITY_LOW = 1;
    public static final int PRIORITY_NORMAL = 5;
    public static final int PRIORITY_HIGH = 10;
    public static final int PRIORITY_HIGHEST = 20;

    private static final AtomicBoolean silentActive = new AtomicBoolean(false);

    private static boolean silentHeld = false;
    private static boolean rotationSetThisTick = false;
    private static int activePriority = 0;

    private static float silentYaw = 0;
    private static float silentPitch = 0;
    private static float originalYaw = 0;
    private static float originalPitch = 0;

    private static float lastYaw = 0;
    private static float lastPitch = 0;
    private static boolean hasLast = false;

    private static float correctionDeltaYaw = 0f;
    private static boolean hasCorrectionDelta = false;
    private static boolean setThisGameTick = false;

    private static float yawVelocity = 0.0F;
    private static float pitchVelocity = 0.0F;
    private static double noiseSeedX = Math.random() * 1000.0;
    private static double noiseSeedY = Math.random() * 1000.0;
    private static double noiseSeedZ = Math.random() * 1000.0;

    private static float renderYaw = 0;
    private static float prevRenderYaw = 0;
    private static float renderPitch = 0;
    private static float prevRenderPitch = 0;
    private static boolean renderInitialized = false;

    private static float renderBodyYaw = 0;
    private static float prevRenderBodyYaw = 0;
    private static boolean renderBodyYawActive = false;

    public static boolean isSilentActive() {
        return silentActive.get();
    }

    public static boolean isSilentHeld() {
        synchronized (silentActive) {
            return silentHeld;
        }
    }

    public static float getSilentYaw() {
        synchronized (silentActive) {
            return silentYaw;
        }
    }

    public static float getSilentPitch() {
        synchronized (silentActive) {
            return silentPitch;
        }
    }

    public static float getOriginalYaw() {
        synchronized (silentActive) {
            return originalYaw;
        }
    }

    public static float getOriginalPitch() {
        synchronized (silentActive) {
            return originalPitch;
        }
    }

    public static float getRenderYaw() {
        synchronized (silentActive) {
            return renderInitialized ? renderYaw : silentYaw;
        }
    }

    public static float getPrevRenderYaw() {
        synchronized (silentActive) {
            return renderInitialized ? prevRenderYaw : silentYaw;
        }
    }

    public static float getRenderPitch() {
        synchronized (silentActive) {
            return renderInitialized ? renderPitch : silentPitch;
        }
    }

    public static float getPrevRenderPitch() {
        synchronized (silentActive) {
            return renderInitialized ? prevRenderPitch : silentPitch;
        }
    }

    public static float getRenderBodyYaw() {
        synchronized (silentActive) {
            return renderBodyYawActive ? renderBodyYaw : getRenderYaw();
        }
    }

    public static float getPrevRenderBodyYaw() {
        synchronized (silentActive) {
            return renderBodyYawActive ? prevRenderBodyYaw : getPrevRenderYaw();
        }
    }

    public static void updateRenderBodyYaw(LocalPlayer player, float aimYaw) {
        synchronized (silentActive) {
            if (!renderBodyYawActive) {
                renderBodyYaw = player.yBodyRot;
                prevRenderBodyYaw = player.yBodyRotO;
                renderBodyYawActive = true;
            } else {
                prevRenderBodyYaw = renderBodyYaw;
            }

            double deltaX = player.getX() - player.xo;
            double deltaZ = player.getZ() - player.zo;
            float targetBodyYaw = renderBodyYaw;
            if ((float) (deltaX * deltaX + deltaZ * deltaZ) > 0.0025f) {
                float moveYaw = (float) (Math.atan2(deltaZ, deltaX) * 180.0 / Math.PI) - 90.0f;
                float lookToMove = Math.abs(Mth.wrapDegrees(aimYaw - moveYaw));
                targetBodyYaw = (lookToMove > 95.0f && lookToMove < 265.0f) ? moveYaw - 180.0f : moveYaw;
            }
            if (player.swinging) {
                targetBodyYaw = aimYaw;
            }

            renderBodyYaw += getAngleDifference(targetBodyYaw, renderBodyYaw) * 0.3f;

            float yawOffset = Mth.wrapDegrees(aimYaw - renderBodyYaw);
            if (yawOffset < -75.0F) {
                renderBodyYaw = aimYaw + 75.0F;
            } else if (yawOffset > 75.0F) {
                renderBodyYaw = aimYaw - 75.0F;
            }
        }
    }

    public static void clearRenderBodyYaw() {
        synchronized (silentActive) {
            renderBodyYawActive = false;
        }
    }

    public static boolean hasCorrectionDelta() {
        return hasCorrectionDelta;
    }

    public static float getCorrectionDeltaYaw() {
        synchronized (silentActive) {
            return correctionDeltaYaw;
        }
    }

    public static void setOriginal(float yaw, float pitch) {
        synchronized (silentActive) {
            originalYaw = yaw;
            originalPitch = pitch;
        }
    }

    public static void startTick() {
        synchronized (silentActive) {
            if (silentActive.get() && !setThisGameTick) {
                silentActive.set(false);
                silentHeld = false;
                hasLast = false;
                hasCorrectionDelta = false;
                setThisGameTick = false;
                correctionDeltaYaw = 0f;
                rotationSetThisTick = false;
                yawVelocity = 0.0F;
                pitchVelocity = 0.0F;
            }
            setThisGameTick = false;
            rotationSetThisTick = false;
            activePriority = 0;
        }
    }

    public static void endTick() {
        synchronized (silentActive) {
            if (!rotationSetThisTick) {
                silentActive.set(false);
                silentHeld = false;
                hasLast = false;
                hasCorrectionDelta = false;
                correctionDeltaYaw = 0f;
                rotationSetThisTick = false;
                activePriority = 0;
                yawVelocity = 0.0F;
                pitchVelocity = 0.0F;
                renderInitialized = false;
            } else {
                silentActive.set(false);
                hasCorrectionDelta = false;
                correctionDeltaYaw = 0f;
            }
        }
    }

    public static void setSilent(float targetYaw, float targetPitch) {
        setSilent(targetYaw, targetPitch, 10.0F, PRIORITY_NORMAL);
    }

    public static void setSilent(float targetYaw, float targetPitch, float speed) {
        setSilent(targetYaw, targetPitch, speed, PRIORITY_NORMAL);
    }

    public static void setSilent(float targetYaw, float targetPitch, float speed, int priority) {
        synchronized (silentActive) {
            if (rotationSetThisTick && priority < activePriority) {
                return;
            }
            activePriority = priority;
            setThisGameTick = true;
            silentHeld = true;
            if (!hasLast) {
                lastYaw = originalYaw;
                lastPitch = originalPitch;
                yawVelocity = 0.0F;
                pitchVelocity = 0.0F;
                hasLast = true;
            }
            float previousYaw = lastYaw;
            float previousPitch = lastPitch;

            correctionDeltaYaw = getAngleDifference(targetYaw, originalYaw);
            hasCorrectionDelta = true;

            Minecraft mc = Minecraft.getInstance();
            double time = System.currentTimeMillis() * 0.0015;

            float noiseYawFreq = 1.8F;
            float noisePitchFreq = 2.2F;
            float noiseAmp = 0.08F;

            float yawDiff = getAngleDifference(targetYaw, lastYaw);
            float pitchDiff = targetPitch - lastPitch;
            double targetDist = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);

            float targetLockScale = targetDist < 3.0 ? 1.2F : 0.6F;
            float currentNoiseYaw = (float) (ImprovedNoise.noise(time * noiseYawFreq + noiseSeedX, noiseSeedY, noiseSeedZ) * noiseAmp * targetLockScale);
            float currentNoisePitch = (float) (ImprovedNoise.noise(noiseSeedX, time * noisePitchFreq + noiseSeedY, noiseSeedZ) * noiseAmp * targetLockScale);

            float finalYaw, finalPitch;
            if (speed >= 10.0F) {
                float continuousTargetYaw = lastYaw + getAngleDifference(targetYaw, lastYaw);
                lastYaw = continuousTargetYaw;
                lastPitch = targetPitch;
                yawVelocity = 0.0F;
                pitchVelocity = 0.0F;
                finalYaw = continuousTargetYaw;
                finalPitch = targetPitch;
            } else {
                float clampedSpeed = Math.max(0.1F, Math.min(9.9F, speed));
                float baseSmoothTime = 0.001F + 0.25F * (1.0F - (clampedSpeed / 10.0F));
                float smoothTime = baseSmoothTime;

                if (targetDist > 80.0) {
                    double angleFactor = targetDist / 180.0;
                    float randomOffset = (float) (Math.random() * 0.03 - 0.015);
                    smoothTime = baseSmoothTime + (float) (angleFactor * 0.08) + randomOffset;
                }

                smoothTime = Math.max(0.005F, smoothTime);
                float maxSpeed = 100000.0F;

                float[] dampResultYaw = smoothDampAngle(lastYaw, targetYaw, yawVelocity, smoothTime, maxSpeed, 0.05F);
                lastYaw = dampResultYaw[0];
                yawVelocity = dampResultYaw[1];

                float[] dampResultPitch = smoothDamp(lastPitch, targetPitch, pitchVelocity, smoothTime, maxSpeed, 0.05F);
                lastPitch = dampResultPitch[0];
                pitchVelocity = dampResultPitch[1];

                finalYaw = lastYaw + currentNoiseYaw;
                finalPitch = lastPitch + currentNoisePitch;
            }

            finalPitch = Mth.clamp(finalPitch, -90.0F, 90.0F);

            if (mc.options != null && mc.options.sensitivity() != null) {
                float sens = mc.options.sensitivity().get().floatValue() * 0.6F + 0.2F;
                float gcd = sens * sens * sens * 1.2F;

                float yawGridDiff = Math.round(getAngleDifference(finalYaw, previousYaw) / gcd) * gcd;
                silentYaw = previousYaw + yawGridDiff;

                float pitchGridDiff = Math.round((finalPitch - previousPitch) / gcd) * gcd;
                silentPitch = previousPitch + pitchGridDiff;
            } else {
                silentYaw = previousYaw + getAngleDifference(finalYaw, previousYaw);
                silentPitch = finalPitch;
            }

            silentPitch = Mth.clamp(silentPitch, -90.0F, 90.0F);
            lastYaw = silentYaw;
            if (!renderInitialized) {
                if (mc.player != null) {
                    renderYaw = mc.player.getYRot();
                    prevRenderYaw = mc.player.yRotO;
                    renderPitch = mc.player.getXRot();
                    prevRenderPitch = mc.player.xRotO;
                } else {
                    renderYaw = silentYaw;
                    prevRenderYaw = silentYaw;
                    renderPitch = silentPitch;
                    prevRenderPitch = silentPitch;
                }
                renderInitialized = true;
            } else {
                prevRenderYaw = renderYaw;
                prevRenderPitch = renderPitch;
                float renderYawDiff = getAngleDifference(silentYaw, renderYaw);
                float renderPitchDiff = silentPitch - renderPitch;
                renderYaw += renderYawDiff * 0.4f;
                renderPitch += renderPitchDiff * 0.4f;
            }

            silentActive.set(true);
            rotationSetThisTick = true;
        }
    }

    public static void clearSilent() {
        clear();
    }

    public static void clear() {
        synchronized (silentActive) {
            silentActive.set(false);
            silentHeld = false;
            hasLast = false;
            hasCorrectionDelta = false;
            setThisGameTick = false;
            correctionDeltaYaw = 0f;
            rotationSetThisTick = false;
            yawVelocity = 0.0F;
            pitchVelocity = 0.0F;
            renderInitialized = false;
            renderBodyYawActive = false;
            renderBodyYaw = 0.0f;
            prevRenderBodyYaw = 0.0f;
        }
    }

    public static float getAngleDifference(float a, float b) {
        return ((((a - b) % 360F) + 540F) % 360F) - 180F;
    }

    private static float[] smoothDampAngle(float current, float target, float currentVelocity, float smoothTime, float maxSpeed, float deltaTime) {
        float diff = getAngleDifference(target, current);
        return smoothDamp(current, current + diff, currentVelocity, smoothTime, maxSpeed, deltaTime);
    }

    private static float[] smoothDamp(float current, float target, float currentVelocity, float smoothTime, float maxSpeed, float deltaTime) {
        smoothTime = Math.max(0.0001F, smoothTime);
        float num = 2.0F / smoothTime;
        float num2 = num * deltaTime;
        float num3 = 1.0F / (1.0F + num2 + 0.48F * num2 * num2 + 0.235F * num2 * num2 * num2);
        float num4 = current - target;
        float num5 = target;
        float num6 = maxSpeed * smoothTime;
        num4 = Math.max(-num6, Math.min(num6, num4));
        float target2 = current - num4;
        float num7 = (currentVelocity + num * num4) * deltaTime;
        currentVelocity = (currentVelocity - num * num7) * num3;
        float num8 = target2 + (num4 + num7) * num3;
        if (num5 - current > 0.0F == num8 > num5) {
            num8 = num5;
            currentVelocity = (num8 - num5) / deltaTime;
        }
        return new float[]{num8, currentVelocity};
    }

    private static class ImprovedNoise {
        private static final int[] p = new int[512];
        static {
            int[] permutation = {
                    151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,140,36,103,30,69,142,8,99,37,240,21,10,23,
                    190,6,148,247,120,234,75,0,26,56,62,94,252,219,203,117,35,11,32,57,177,33,88,237,149,56,87,174,20,
                    125,136,171,168,68,175,74,165,71,134,139,48,27,166,77,146,158,231,83,111,229,122,60,211,133,230,220,
                    105,92,41,55,46,245,40,244,102,143,54,65,25,63,161,1,216,80,73,209,76,132,187,208,89,18,169,200,196,
                    135,130,116,188,159,86,164,100,109,198,173,186,3,64,52,217,226,250,124,123,5,202,38,147,118,126,255,
                    82,85,212,207,206,59,227,47,16,58,17,182,189,28,42,223,183,170,213,119,248,152,2,44,154,163,70,221,
                    153,101,155,167,43,172,9,129,22,39,253,19,98,108,110,79,113,224,232,178,185,112,104,218,246,97,228,
                    251,34,242,193,238,210,144,12,191,179,162,241,81,51,145,235,249,14,239,107,49,192,214,31,181,199,106,
                    157,184,84,204,176,115,121,50,45,127,4,150,254,138,236,205,93,222,114,67,29,24,72,243,141,128,195,78,
                    66,215,61,156,180
            };
            for (int i = 0; i < 256; i++) {
                p[256 + i] = p[i] = permutation[i];
            }
        }

        public static double noise(double x, double y, double z) {
            int X = (int) Math.floor(x) & 255;
            int Y = (int) Math.floor(y) & 255;
            int Z = (int) Math.floor(z) & 255;
            x -= Math.floor(x);
            y -= Math.floor(y);
            z -= Math.floor(z);
            double u = fade(x);
            double v = fade(y);
            double w = fade(z);
            int A = p[X] + Y;
            int AA = p[A] + Z;
            int AB = p[A + 1] + Z;
            int B = p[X + 1] + Y;
            int BA = p[B] + Z;
            int BB = p[B + 1] + Z;

            return lerp(w, lerp(v, lerp(u, grad(p[AA], x, y, z),
                                    grad(p[BA], x - 1, y, z)),
                            lerp(u, grad(p[AB], x, y - 1, z),
                                    grad(p[BB], x - 1, y - 1, z))),
                    lerp(v, lerp(u, grad(p[AA + 1], x, y, z - 1),
                                    grad(p[BA + 1], x - 1, y, z - 1)),
                            lerp(u, grad(p[AB + 1], x, y - 1, z - 1),
                                    grad(p[BB + 1], x - 1, y - 1, z - 1))));
        }

        private static double fade(double t) {
            return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
        }

        private static double lerp(double t, double a, double b) {
            return a + t * (b - a);
        }

        private static double grad(int hash, double x, double y, double z) {
            int h = hash & 15;
            double u = h < 8 ? x : y;
            double v = h < 4 ? y : h == 12 || h == 14 ? x : z;
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }
    }
}
