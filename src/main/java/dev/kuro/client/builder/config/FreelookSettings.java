package dev.kuro.client.builder.config;

public final class FreelookSettings {
    /** Freelook always starts off when joining a session. */
    public transient boolean enabled = false;
    public boolean favorite = false;
    public boolean hold = true;
    public double distance = 4.0;
    public boolean invertPitch = false;

    public void sanitize() {
        distance = Double.isFinite(distance) ? Math.clamp(distance, 1.0, 10.0) : 4.0;
    }
}
