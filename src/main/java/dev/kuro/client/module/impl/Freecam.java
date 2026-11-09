package dev.kuro.client.module.impl;

import dev.kuro.client.mixin.ClientInputAccessor;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Freecam module: Detached camera with chunk loading, scroll speed adjustment,
 * body persistence, and smooth flight controls.
 */
public final class Freecam extends Module {
    private static volatile Freecam instance;

    // Movement bits
    private static final int WALK_FWD = 1;
    private static final int WALK_BACK = 2;
    private static final int WALK_LEFT = 4;
    private static final int WALK_RIGHT = 8;
    private static final int WALK_JUMP = 16;
    private static final int WALK_SNEAK = 32;
    private static final int WALK_SPRINT = 64;

    // Settings
    public final Setting.Num speed = add(new Setting.Num("Speed", 1.0, 0.1, 10.0, 0.1));
    public final Setting.Num verticalSpeed = add(new Setting.Num("Vertical", 1.0, 0.2, 5.0, 0.1));
    public final Setting.Bool smoothing = add(new Setting.Bool("Smooth", true));
    public final Setting.Bool showOwnBody = add(new Setting.Bool("Body", true));
    public final Setting.Bool showHands = add(new Setting.Bool("Hands", true));
    public final Setting.Bool scrollSpeed = add(new Setting.Bool("Scroll Speed", true));
    public final Setting.Bool scrollResets = add(new Setting.Bool("Reset Scroll Speed", true));
    public final Setting.Num chunkDistance = add(new Setting.Num("Chunks", 12.0, 2.0, 32.0, 1.0));

    // Camera state
    private Vec3 pos = Vec3.ZERO;
    private Vec3 prev = Vec3.ZERO;
    public float yaw;
    public float pitch;

    // Player & render state tracking
    private boolean flyingBefore;
    private boolean cullBefore;
    private CameraType viewBefore;
    private boolean viewPushed;
    private ChunkPos focus;
    private int radius = -1;
    private boolean waiting;
    private boolean live;
    private int walk;
    private int learn;
    private float scrolled = 1.0F;

    public Freecam() {
        super("Freecam", Category.PLAYER);
        instance = this;
    }

    public static Freecam get() {
        return instance;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled() && instance.live;
    }

    @Override
    protected void onEnable() {
        this.waiting = true;
        this.live = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            setEnabled(false);
            return;
        }
        this.open(mc);
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        boolean wasLive = this.live;
        this.waiting = false;
        this.live = false;

        this.walk = 0;
        this.learn = 0;
        this.scrolled = 1.0F;

        if (this.viewPushed && mc.options != null) {
            mc.options.setCameraType(this.viewBefore);
            this.viewPushed = false;
        }

        if (wasLive) {
            mc.smartCull = this.cullBefore;
            if (mc.gameMode != null) {
                mc.gameMode.stopDestroyBlock();
            }

            this.releaseChunks(mc);
            if (mc.player != null) {
                mc.player.getAbilities().flying = this.flyingBefore;
            }
        }
    }

    private void open(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player != null && mc.level != null) {
            this.flyingBefore = player.getAbilities().flying;
            double eyeHeight = player.getEyeHeight(Pose.STANDING);
            this.pos = this.prev = new Vec3(player.getX(), player.getY() + eyeHeight, player.getZ());
            this.yaw = player.getYRot();
            this.pitch = player.getXRot();
            this.viewPushed = false;

            if (this.showOwnBody.value) {
                this.viewBefore = mc.options.getCameraType();
                if (this.viewBefore.isFirstPerson()) {
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    this.viewPushed = true;
                }
            }

            this.waiting = false;
            this.live = true;
            this.walk = 0;
            this.absorb(player, true);
            this.learn = 40;
            this.focus = null;
            this.radius = -1;
            this.cullBefore = mc.smartCull;
            mc.smartCull = false;
            this.followChunks(mc);
            drive(mc);
        }
    }

    @Override
    public void onPreTick() {
        Minecraft mc = Minecraft.getInstance();
        if (this.waiting) {
            this.open(mc);
        }

        LocalPlayer player = mc.player;
        if (this.live && player != null) {
            if (this.learn > 0) {
                this.learn--;
                this.absorb(player, false);
            }

            this.prev = this.pos;
            if (player.getAbilities().instabuild) {
                player.getAbilities().flying = false;
            }

            this.stepCamera(mc.options);
            this.followChunks(mc);
        }
    }

    private void stepCamera(Options options) {
        if (Minecraft.getInstance().gui.screen() != null) return;

        double ahead = (options.keyUp.isDown() ? 1.0 : 0.0) - (options.keyDown.isDown() ? 1.0 : 0.0);
        double side = (options.keyLeft.isDown() ? 1.0 : 0.0) - (options.keyRight.isDown() ? 1.0 : 0.0);
        double rise = (options.keyJump.isDown() ? 1.0 : 0.0) - (options.keyShift.isDown() ? 1.0 : 0.0);
        double step = this.flySpeed() * (this.smoothing.value ? 0.5 : 1.0);

        float yr = (float) Math.toRadians(this.yaw);
        Vec3 look = new Vec3(-Math.sin(yr), 0.0, Math.cos(yr));
        Vec3 left = new Vec3(look.z, 0.0, -look.x);

        this.pos = this.pos.add(
            (look.x * ahead + left.x * side) * step,
            rise * step * this.verticalSpeed.value,
            (look.z * ahead + left.z * side) * step
        );
    }

    private float flySpeed() {
        if (!this.scrollResets.value) {
            return (float) this.speed.value;
        }
        return (float) Mth.clamp(this.speed.value * this.scrolled, this.speed.min, this.speed.max);
    }

    private void absorb(LocalPlayer player, boolean opening) {
        this.walk = 0;
    }

    private void setWalk(int bit, boolean on) {
        if (on) {
            this.walk |= bit;
        }
    }

    public static void drive(Minecraft mc) {
        Freecam module = instance;
        if (module != null && module.isEnabled() && module.live && mc.player != null) {
            module.pushWalk(mc);
        }
    }

    private void pushWalk(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        player.input.keyPresses = new Input(false, false, false, false, false, player.isCrouching(), false);
        ((ClientInputAccessor) player.input).kuro$setMoveVector(Vec2.ZERO);
        Vec3 cur = player.getDeltaMovement();
        player.setDeltaMovement(0.0, Math.min(0.0, cur.y), 0.0);
    }

    private boolean on(int bit) {
        return (this.walk & bit) != 0;
    }

    private void followChunks(Minecraft mc) {
        if (this.live && mc.level != null && mc.player != null) {
            ClientChunkCache cache = mc.level.getChunkSource();
            ChunkPos camera = new ChunkPos(Mth.floor(this.pos.x) >> 4, Mth.floor(this.pos.z) >> 4);
            ChunkPos feet = mc.player.chunkPosition();
            int span = Math.max(Math.abs(camera.x() - feet.x()), Math.abs(camera.z() - feet.z()));
            int preference = Mth.clamp(this.chunkDistance.intValue(), 2, 32);
            ChunkPos next = span > preference
                ? new ChunkPos(feet.x() + (camera.x() - feet.x()) / 2, feet.z() + (camera.z() - feet.z()) / 2)
                : camera;

            int needed = Mth.clamp(Math.max(preference, span / 2 + 3), 2, 32);
            if (needed != this.radius) {
                cache.updateViewRadius(needed);
                this.radius = needed;
            }

            if (this.focus == null || next.x() != this.focus.x() || next.z() != this.focus.z()) {
                cache.updateViewCenter(next.x(), next.z());
                this.focus = next;
            }
        }
    }

    private void releaseChunks(Minecraft mc) {
        this.focus = null;
        this.radius = -1;
        if (mc.level != null) {
            ClientChunkCache cache = mc.level.getChunkSource();
            if (mc.player != null) {
                ChunkPos feet = mc.player.chunkPosition();
                cache.updateViewCenter(feet.x(), feet.z());
            }

            cache.updateViewRadius(mc.options.getEffectiveRenderDistance());
        }
    }

    public static boolean onScroll(double amount) {
        Freecam module = instance;
        if (module != null && module.isEnabled() && module.live && module.scrollSpeed.value && amount != 0.0) {
            float factor = amount > 0.0 ? 1.2F : 0.8333333F;
            if (module.scrollResets.value) {
                module.scrolled = (float) Mth.clamp(
                    module.scrolled * factor,
                    module.speed.min / module.speed.value,
                    module.speed.max / module.speed.value
                );
            } else {
                module.speed.value = Mth.clamp(
                    module.speed.value * factor,
                    module.speed.min,
                    module.speed.max
                );
            }
            return true;
        }
        return false;
    }

    public void look(double dx, double dy) {
        this.yaw += (float) (dx * 0.15);
        this.pitch = Mth.clamp(this.pitch + (float) (dy * 0.15), -90.0F, 90.0F);
    }

    public boolean drawsSelf(Entity entity, Player viewer) {
        Minecraft mc = Minecraft.getInstance();
        return this.showOwnBody.value && mc.player != null && entity == mc.player && viewer == mc.player;
    }

    public boolean keepHands() {
        return this.showHands.value;
    }

    public double lx(float tickDelta) {
        return Mth.lerp(tickDelta, this.prev.x, this.pos.x);
    }

    public double ly(float tickDelta) {
        return Mth.lerp(tickDelta, this.prev.y, this.pos.y);
    }

    public double lz(float tickDelta) {
        return Mth.lerp(tickDelta, this.prev.z, this.pos.z);
    }
}
