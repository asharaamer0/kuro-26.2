package dev.kuro.client.echo.event;

public class EventMotion extends Event {
    private double x, y, z;
    private boolean onGround;
    private double yaw, pitch;

    public EventMotion(Event.Stage stage, double x, double y, double z, boolean onGround, double yaw, double pitch) {
        super(stage);
        this.x = x;
        this.y = y;
        this.z = z;
        this.onGround = onGround;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public double getZ() { return z; }
    public void setZ(double z) { this.z = z; }
    public boolean isOnGround() { return onGround; }
    public void setOnGround(boolean onGround) { this.onGround = onGround; }
    public double getYaw() { return yaw; }
    public void setYaw(double yaw) { this.yaw = yaw; }
    public double getPitch() { return pitch; }
    public void setPitch(double pitch) { this.pitch = pitch; }

    public static class Pre extends EventMotion {
        public Pre(double x, double y, double z, boolean onGround, double yaw, double pitch) {
            super(Event.Stage.PRE, x, y, z, onGround, yaw, pitch);
        }
    }

    public static class Post extends EventMotion {
        public Post(double x, double y, double z, boolean onGround, double yaw, double pitch) {
            super(Event.Stage.POST, x, y, z, onGround, yaw, pitch);
        }
    }
}
