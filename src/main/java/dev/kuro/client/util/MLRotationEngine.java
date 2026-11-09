package dev.kuro.client.util;

import java.util.Random;

public class MLRotationEngine {

    private float coarseSpeedMult;
    private float fineSpeedMult;
    private float microJitterAmpYaw;
    private float microJitterAmpPitch;
    private float driftChance;
    private float driftStrength;
    private float driftDecayRate;
    private int reactionDelay;

    private static final float MAX_NOISE_OFFSET_DEG = 2.5f;

    private int ticksActive = 0;
    private float driftYaw = 0f;
    private float driftPitch = 0f;
    private float decayMult = 1f;
    private float lastSpeedOut = 1.0f;

    private double nx1, ny1, nz1;
    private double nx2, ny2, nz2;

    private Random rng;

    private static final float FINE_THRESHOLD_DEG = 12.0f;

    public MLRotationEngine() {
        reset();
    }

    public void reset() {
        long seed = System.nanoTime() ^ Thread.currentThread().getId();
        rng = new Random(seed);

        coarseSpeedMult = 0.8f + rng.nextFloat() * 1.2f;
        fineSpeedMult = 0.10f + rng.nextFloat() * 0.30f;
        microJitterAmpYaw = 0.01f + rng.nextFloat() * 0.05f;
        microJitterAmpPitch = 0.005f + rng.nextFloat() * 0.025f;
        driftChance = 0.01f + rng.nextFloat() * 0.04f;
        driftStrength = 0.10f + rng.nextFloat() * 0.50f;
        driftDecayRate = 0.75f + rng.nextFloat() * 0.20f;
        reactionDelay = rng.nextInt(3);

        nx1 = rng.nextDouble() * 100000;
        ny1 = rng.nextDouble() * 100000;
        nz1 = rng.nextDouble() * 100000;
        nx2 = rng.nextDouble() * 100000;
        ny2 = rng.nextDouble() * 100000;
        nz2 = rng.nextDouble() * 100000;

        ticksActive = 0;
        driftYaw = 0f;
        driftPitch = 0f;
        decayMult = 1f;
    }

    public float[] computeTick(float rawYaw, float rawPitch, float currentYaw, float currentPitch) {
        ticksActive++;

        if (ticksActive <= reactionDelay) {
            lastSpeedOut = 0.05f;
            return new float[]{rawYaw, rawPitch, lastSpeedOut};
        }

        double t = System.currentTimeMillis() * 0.001;

        float yawDiff = angleDiff(rawYaw, currentYaw);
        float pitchDiff = rawPitch - currentPitch;
        float angularDist = (float) Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
        boolean isCoarse = angularDist > FINE_THRESHOLD_DEG;

        float jitterScale = isCoarse ? 0.15f : 1.0f;

        float noiseYaw = (float) (
                PerlinNoise.noise(nx1 + t * 2.3, ny1, nz1) * microJitterAmpYaw * jitterScale
                        + PerlinNoise.noise(nx2 + t * 5.1, ny2, nz2) * microJitterAmpYaw * 0.3f * jitterScale
        );
        float noisePitch = (float) (
                PerlinNoise.noise(nx1, ny1 + t * 1.9, nz1) * microJitterAmpPitch * jitterScale
                        + PerlinNoise.noise(nx2, ny2 + t * 4.3, nz2) * microJitterAmpPitch * 0.2f * jitterScale
        );

        if (!isCoarse && rng.nextFloat() < driftChance) {
            driftYaw = (rng.nextFloat() - 0.5f) * 2f * driftStrength;
            driftPitch = (rng.nextFloat() - 0.5f) * 2f * driftStrength * 0.4f;
            decayMult = driftDecayRate + (rng.nextFloat() - 0.5f) * 0.05f;
        }
        driftYaw *= decayMult;
        driftPitch *= decayMult;

        float totalOffsetYaw = noiseYaw + driftYaw;
        float totalOffsetPitch = noisePitch + driftPitch;
        float offsetMag = (float) Math.sqrt(totalOffsetYaw * totalOffsetYaw + totalOffsetPitch * totalOffsetPitch);
        if (offsetMag > MAX_NOISE_OFFSET_DEG) {
            float scale = MAX_NOISE_OFFSET_DEG / offsetMag;
            totalOffsetYaw *= scale;
            totalOffsetPitch *= scale;
        }

        float baseSpeed = isCoarse ? coarseSpeedMult : fineSpeedMult;
        float speedJitter = (rng.nextFloat() - 0.5f) * 0.05f * baseSpeed;
        lastSpeedOut = Math.max(0.05f, baseSpeed + speedJitter);

        float targetYaw = rawYaw + totalOffsetYaw;
        float targetPitch = rawPitch + totalOffsetPitch;

        return new float[]{targetYaw, targetPitch, lastSpeedOut};
    }

    public float[] compute(float rawYaw, float rawPitch, float currentYaw, float currentPitch) {
        return computeTick(rawYaw, rawPitch, currentYaw, currentPitch);
    }

    public float getLastSpeed() {
        return lastSpeedOut;
    }

    public float lastSpeed() {
        return lastSpeedOut;
    }

    private static float angleDiff(float a, float b) {
        return ((((a - b) % 360f) + 540f) % 360f) - 180f;
    }

    private static class PerlinNoise {
        private static final int[] P;
        static {
            int[] perm = {
                    151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,140,36,103,30,69,142,
                    8,99,37,240,21,10,23,190,6,148,247,120,234,75,0,26,56,62,94,252,219,203,117,
                    35,11,32,57,177,33,88,237,149,56,87,174,20,125,136,171,168,68,175,74,165,71,
                    134,139,48,27,166,77,146,158,231,83,111,229,122,60,211,133,230,220,105,92,41,
                    55,46,245,40,244,102,143,54,65,25,63,161,1,216,80,73,209,76,132,187,208,89,
                    18,169,200,196,135,130,116,188,159,86,164,100,109,198,173,186,3,64,52,217,226,
                    250,124,123,5,202,38,147,118,126,255,82,85,212,207,206,59,227,47,16,58,17,182,
                    189,28,42,223,183,170,213,119,248,152,2,44,154,163,70,221,153,101,155,167,43,
                    172,9,129,22,39,253,19,98,108,110,79,113,224,232,178,185,112,104,218,246,97,
                    228,251,34,242,193,238,210,144,12,191,179,162,241,81,51,145,235,249,14,239,
                    107,49,192,214,31,181,199,106,157,184,84,204,176,115,121,50,45,127,4,150,254,
                    138,236,205,93,222,114,67,29,24,72,243,141,128,195,78,66,215,61,156,180
            };
            P = new int[512];
            for (int i = 0; i < 256; i++) P[256 + i] = P[i] = perm[i];
        }

        static double noise(double x, double y, double z) {
            int X = (int)Math.floor(x) & 255, Y = (int)Math.floor(y) & 255, Z = (int)Math.floor(z) & 255;
            x -= Math.floor(x); y -= Math.floor(y); z -= Math.floor(z);
            double u = f(x), v = f(y), w = f(z);
            int A = P[X]+Y, AA = P[A]+Z, AB = P[A+1]+Z, B = P[X+1]+Y, BA = P[B]+Z, BB = P[B+1]+Z;
            return lerp(w, lerp(v, lerp(u, g(P[AA],x,y,z),   g(P[BA],x-1,y,z)),
                            lerp(u, g(P[AB],x,y-1,z), g(P[BB],x-1,y-1,z))),
                    lerp(v, lerp(u, g(P[AA+1],x,y,z-1),   g(P[BA+1],x-1,y,z-1)),
                            lerp(u, g(P[AB+1],x,y-1,z-1), g(P[BB+1],x-1,y-1,z-1))));
        }
        private static double f(double t){ return t*t*t*(t*(t*6-15)+10); }
        private static double lerp(double t,double a,double b){ return a+t*(b-a); }
        private static double g(int h,double x,double y,double z){
            int hh=h&15; double u=hh<8?x:y, v=hh<4?y:hh==12||hh==14?x:z;
            return((hh&1)==0?u:-u)+((hh&2)==0?v:-v);
        }
    }
}
