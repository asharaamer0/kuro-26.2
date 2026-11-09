package dev.kuro.client.module.impl;

import dev.kuro.client.KuroClient;
import dev.kuro.client.echo.event.EventMotion;
import dev.kuro.client.echo.event.EventMove;
import dev.kuro.client.event.EventHandler;
import dev.kuro.client.event.events.KineticHitEvent;
import dev.kuro.client.event.events.MotionEvent;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.module.impl.SafeAnchor;
import dev.kuro.client.module.impl.automace.AutoMaceTargeting;
import dev.kuro.client.module.impl.automace.RocketDiveHandler;
import dev.kuro.client.module.impl.automace.ShieldBreakHandler;
import dev.kuro.client.module.impl.automace.SpearDtapHandler;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.CombatTargetUtil;
import dev.kuro.client.util.MLRotationEngine;
import dev.kuro.client.util.PacketUtils;
import dev.kuro.client.util.RotationHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

public class AutoMace extends Module {

    public enum Mode { SILENT, LEGIT }
    public enum ShieldMode { NONE, STUN_SLAM, DRAIN }
    public enum AimMode { NORMAL, ML_BASED }
    public enum TargetPointMode { CLOSEST, NEAREST_VECTOR, CENTER }
    public enum PriorityMode { DISTANCE, LOWEST_HP, HIGHEST_HP }

    private final Setting.Num mode = add(new Setting.Num("Mode", 0, 0, 1, 1).labelled("Silent", "Legit"));
    private final Setting.Num targetPriority = add(new Setting.Num("Priority", 0, 0, 2, 1).labelled("Distance", "Lowest HP", "Highest HP"));
    private final Setting.Num range = add(new Setting.Num("Range", 5.0, 1.0, 10.0, 0.1));
    private final Setting.Num aimRange = add(new Setting.Num("Aim Range", 5.5, 1.0, 10.0, 0.1));
    private final Setting.Num fov = add(new Setting.Num("FOV", 360.0, 5.0, 360.0, 5.0));
    private final Setting.Bool sameTarget = add(new Setting.Bool("Same Target", false));
    private final Setting.Num aimMode = add(new Setting.Num("Aim Mode", 0, 0, 1, 1).labelled("Normal", "ML Based"));
    private final Setting.Num targetPointMode = add(new Setting.Num("Aim Point", 1, 0, 2, 1).labelled("Closest", "Nearest Vector", "Center"));
    private final Setting.Num aimSpeed = add(new Setting.Num("Aim Speed", 1.0, 0.1, 20.0, 0.1));

    private final Setting.Num shieldMode = add(new Setting.Num("Shield Break", 0, 0, 2, 1).labelled("None", "Stun Slam", "Drain"));
    private final Setting.Bool fastStunSlam = add(new Setting.Bool("Fast Stun Slam", true));
    private final Setting.Num stunSlamDelay = add(new Setting.Num("Stun Slam Delay", 0.0, 0.0, 300.0, 5.0));
    private final Setting.Bool directPackets = add(new Setting.Bool("Direct Packets", true));
    private final Setting.Num minFallDistance = add(new Setting.Num("Min Fall Distance", 3.0, 1.0, 10.0, 0.5));
    private final Setting.Bool checkFalling = add(new Setting.Bool("Check Falling", true));
    private final Setting.Num densityThreshold = add(new Setting.Num("Density Threshold", 5.0, 1.0, 20.0, 0.5));
    private final Setting.Num reactionDelayMin = add(new Setting.Num("Reaction Delay Min", 50.0, 0.0, 500.0, 10.0));
    private final Setting.Num reactionDelayMax = add(new Setting.Num("Reaction Delay Max", 150.0, 0.0, 500.0, 10.0));
    private final Setting.Num drainClicksMin = add(new Setting.Num("Drain Clicks Min", 5.0, 1.0, 20.0, 1.0));
    private final Setting.Num drainClicksMax = add(new Setting.Num("Drain Clicks Max", 10.0, 1.0, 20.0, 1.0));

    private final Setting.Bool autoRocket = add(new Setting.Bool("Auto Rocket", false));
    private final Setting.Bool rocketLoop = add(new Setting.Bool("Rocket Loop", false));
    private final Setting.Num rocketTargetRange = add(new Setting.Num("Rocket Target Range", 48.0, 8.0, 128.0, 1.0));
    private final Setting.Num rocketAttackRange = add(new Setting.Num("Rocket Attack Range", 4.0, 2.5, 6.0, 0.1));
    private final Setting.Num rocketSnapFov = add(new Setting.Num("Rocket Snap FOV", 360.0, 10.0, 360.0, 1.0));
    private final Setting.Num rocketHeightAboveTarget = add(new Setting.Num("Rocket Height Above Target", 8.0, 0.0, 30.0, 0.5));
    private final Setting.Num rocketFireworkDelay = add(new Setting.Num("Rocket Firework Delay", 650.0, 100.0, 2500.0, 25.0));
    private final Setting.Num rocketDropDelay = add(new Setting.Num("Rocket Drop Delay", 120.0, 0.0, 1000.0, 10.0));
    private final Setting.Num rocketAttackDelay = add(new Setting.Num("Rocket Attack Delay", 100.0, 0.0, 500.0, 10.0));
    private final Setting.Num rocketReequipDelay = add(new Setting.Num("Rocket Reequip Delay", 150.0, 0.0, 1000.0, 10.0));
    private final Setting.Num rocketGlideDelay = add(new Setting.Num("Rocket Glide Delay", 100.0, 0.0, 1000.0, 10.0));
    private final Setting.Num rocketAimOffset = add(new Setting.Num("Rocket Aim Offset", 1.5, 0.0, 4.0, 0.1));
    private final Setting.Bool rocketJuice = add(new Setting.Bool("Rocket Juice", true));
    private final Setting.Bool rocketPathfind = add(new Setting.Bool("Rocket Pathfind", true));
    private final Setting.Bool rocketRecover = add(new Setting.Bool("Rocket Recover", true));

    private final Setting.Bool targetPlayers = add(new Setting.Bool("Target Players", true));
    private final Setting.Bool targetMobs = add(new Setting.Bool("Target Mobs", false));
    private final Setting.Bool webCheck = add(new Setting.Bool("Web Check", true));
    private final Setting.Num obstruction = add(new Setting.Num("Obstruction", 1, 0, 2, 1).labelled("Off", "Crystals", "All"));
    private final Setting.Bool autoSwitch = add(new Setting.Bool("Auto Switch", true));
    private final Setting.Bool stayOnMace = add(new Setting.Bool("Stay on Mace", false));
    private final Setting.Bool swapBackOnHit = add(new Setting.Bool("Swap Back on Hit", true));
    private final Setting.Bool retrySlam = add(new Setting.Bool("Retry Slam", true));
    private final Setting.Num retryDelay = add(new Setting.Num("Retry Delay", 250.0, 0.0, 1000.0, 25.0));

    private final Setting.Bool spearDtap = add(new Setting.Bool("Spear Mace Dtap", false));

    private double fallStartY = -1;
    private boolean isFalling = false;
    private boolean maceHit = false;
    private int savedSlot = -1;
    private long lastMaceHitTime = 0L;
    private long swapBackAt = 0L;

    private static boolean autoAttacking = false;

    private long targetTime = 0L;
    private boolean wasTargetingLastFrame = false;
    private long currentReactionDelay = 0L;
    private long lastAttackTime = 0L;
    private int lastPreMotionTick = -1;

    private final MLRotationEngine mlEngine = new MLRotationEngine();
    private final RocketDiveHandler rocketDiveHandler = new RocketDiveHandler(this);
    private final ShieldBreakHandler shieldBreakHandler = new ShieldBreakHandler(this);
    private final AutoMaceTargeting targeting = new AutoMaceTargeting(this);
    private final SpearDtapHandler spearDtapHandler = new SpearDtapHandler(this, shieldBreakHandler, targeting);

    public AutoMace() {
        super("Auto Mace", Category.COMBAT);
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        resetFall();
        rocketDiveHandler.resetDive(false);
        savedSlot = -1;
        wasTargetingLastFrame = false;
        lastAttackTime = 0L;
        lastMaceHitTime = 0L;
        swapBackAt = 0L;
        lastPreMotionTick = -1;
        mlEngine.reset();
        spearDtapHandler.reset();
        shieldBreakHandler.reset();
    }

    @Override
    protected void onDisable() {
        super.onDisable();
        if (!stayOnMace.value && savedSlot != -1 && mc.player != null) {
            PacketUtils.setSelectedSlot(savedSlot);
        }
        spearDtapHandler.onDisable();
        rocketDiveHandler.resetDive(true);
        resetFall();
        shieldBreakHandler.reset();
        lastMaceHitTime = 0L;
        swapBackAt = 0L;
        RotationHandler.clearSilent();
    }

    @EventHandler
    public void onMotion(MotionEvent event) {
        if (event.getState() != MotionEvent.State.PRE) return;
        doMotionPre();
    }

    @EventHandler
    public void onEventMotionPre(EventMotion.Pre event) {
        if (mc.player != null && lastPreMotionTick == mc.player.tickCount) return;
        doMotionPre();
    }

    @EventHandler
    public void onMovePre(EventMove.Pre event) {
        if (!isEnabled() || mc.player == null) return;
        if (RotationHandler.isSilentActive()) {
            event.setYaw(RotationHandler.getSilentYaw());
            event.setPitch(RotationHandler.getSilentPitch());
        }
    }

    private void doMotionPre() {
        if (mc.player == null || mc.level == null) return;
        lastPreMotionTick = mc.player.tickCount;

        SafeAnchor safeAnchor = KuroClient.modules != null ? KuroClient.modules.getModule(SafeAnchor.class) : null;
        if (safeAnchor != null && safeAnchor.isEnabled() && safeAnchor.isMacroActive()) {
            return;
        }

        updateFall();

        if (swapBackAt != 0L && !stayOnMace.value && savedSlot != -1
                && System.currentTimeMillis() >= swapBackAt) {
            PacketUtils.setSelectedSlot(savedSlot);
            savedSlot = -1;
            swapBackAt = 0L;
        }

        spearDtapHandler.onMotionPre();

        if (autoRocket.value) {
            rocketDiveHandler.handleDive();
        } else if (!rocketDiveHandler.isIdle()) {
            rocketDiveHandler.resetDive(true);
        }

        if (autoRocket.value && !rocketDiveHandler.isIdle()) {
            return;
        }

        if (mc.player.isFallFlying()) {
            wasTargetingLastFrame = false;
            return;
        }

        if (shieldBreakHandler.findAnyMaceSlot() == -1) {
            wasTargetingLastFrame = false;
            return;
        }

        double fallDist = fallStartY == -1 ? 0 : Math.max(0, fallStartY - mc.player.getY());

        if (!checkFalling.value && maceHit && mc.player.getAttackStrengthScale(0.0F) >= 1.0F) {
            maceHit = false;
            shieldBreakHandler.setSlamState(0);
        }

        if (checkFalling.value) {
            if (!isFalling || mc.player.getDeltaMovement().y >= -0.1) {
                wasTargetingLastFrame = false;
                return;
            }

            if (fallDist < minFallDistance.value) {
                wasTargetingLastFrame = false;
                return;
            }
        }

        Entity target;
        if (mode.intValue() == Mode.LEGIT.ordinal()) {
            target = mc.crosshairPickEntity;
            if (!targeting.isAttackable(target)) {
                wasTargetingLastFrame = false;
                return;
            }
        } else {
            target = targeting.findOptimalTarget();
            if (target != null) {
                float[] rawRotations = targeting.calculateAdvancedRotations(mc.player, target);
                float[] finalRotations;
                float silentSpeed;

                if (aimMode.intValue() == AimMode.ML_BASED.ordinal()) {
                    float[] mlResult = mlEngine.computeTick(rawRotations[0], rawRotations[1], mc.player.getYRot(), mc.player.getXRot());
                    finalRotations = targeting.applyGCDMouseFix(mc.player.getYRot(), mc.player.getXRot(), mlResult[0], mlResult[1]);
                    silentSpeed = mlResult[2] * (float) aimSpeed.value;
                } else {
                    finalRotations = targeting.applyGCDMouseFix(mc.player.getYRot(), mc.player.getXRot(), rawRotations[0], rawRotations[1]);
                    silentSpeed = (float) aimSpeed.value;
                }

                if (mode.intValue() == Mode.SILENT.ordinal()) {
                    silentSpeed = 10.0f;
                }
                RotationHandler.setSilent(finalRotations[0], finalRotations[1], silentSpeed);

                float silentYaw = RotationHandler.getSilentYaw();
                float silentPitch = RotationHandler.getSilentPitch();
                float yawDeviation = Math.abs(RotationHandler.getAngleDifference(silentYaw, rawRotations[0]));
                float pitchDeviation = Math.abs(silentPitch - rawRotations[1]);

                boolean aimed = (yawDeviation <= 55.0f && pitchDeviation <= 55.0f) || silentSpeed >= 9.9f || mode.intValue() == Mode.SILENT.ordinal();
                if (!aimed) {
                    return;
                }

                if (mc.player.distanceTo(target) > range.value) {
                    wasTargetingLastFrame = false;
                    return;
                }
            } else {
                wasTargetingLastFrame = false;
                return;
            }
        }

        if (target != null) {
            if (!wasTargetingLastFrame) {
                targetTime = System.currentTimeMillis();
                currentReactionDelay = (long) (reactionDelayMin.value + Math.random() * (reactionDelayMax.value - reactionDelayMin.value));
                wasTargetingLastFrame = true;
            }

            if (maceHit && retrySlam.value
                    && isFalling && mc.player.getDeltaMovement().y < -0.1
                    && fallDist >= minFallDistance.value
                    && mc.player.getAttackStrengthScale(0.0F) >= 0.9F
                    && System.currentTimeMillis() - lastMaceHitTime >= (long) retryDelay.value) {
                maceHit = false;
                shieldBreakHandler.setSlamState(0);
            }

            if (System.currentTimeMillis() - targetTime >= currentReactionDelay) {
                handleMaceAttack(target, fallDist);
            }
        }
    }

    @EventHandler
    public void onKineticHit(KineticHitEvent event) {
        spearDtapHandler.onKineticHit(event);
    }

    private void updateFall() {
        boolean onGround = mc.player.onGround();
        double currentY = mc.player.getY();
        double yVel = mc.player.getDeltaMovement().y;

        if (onGround) {
            if (isFalling) {
                resetFall();
            }

            if (!stayOnMace.value && savedSlot != -1) {
                PacketUtils.setSelectedSlot(savedSlot);
                savedSlot = -1;
                swapBackAt = 0L;
            }
            return;
        }

        if (fallStartY == -1 || currentY > fallStartY) {
            fallStartY = currentY;
        }

        if (yVel > 0.05) {
            if (maceHit) {
                maceHit = false;
                shieldBreakHandler.setSlamState(0);
                wasTargetingLastFrame = false;
            }
        } else if (yVel < -0.05) {
            if (!isFalling) {
                isFalling = true;
                maceHit = false;
                shieldBreakHandler.setSlamState(0);
            }
        }
    }

    private void resetFall() {
        isFalling = false;
        fallStartY = -1;
        maceHit = false;
        shieldBreakHandler.setSlamState(0);
    }

    public void executeAttack(Entity target) {
        autoAttacking = true;
        try {
            if (mc.gameMode != null && mc.player != null) {
                if (mc.player.isUsingItem()) {
                    mc.gameMode.releaseUsingItem(mc.player);
                }
                if (directPackets.value && mc.getConnection() != null) {
                    mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundAttackPacket(target.getId()));
                } else {
                    mc.gameMode.attack(mc.player, target);
                }
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        } finally {
            autoAttacking = false;
        }
    }

    public static boolean isAutoAttacking() {
        return autoAttacking;
    }

    public boolean isFallSlamActive() {
        if (mc.player == null) return false;
        if (mc.player.onGround() || mc.player.isFallFlying() || mc.player.getDeltaMovement().y >= -0.1) return false;
        double fallDist = fallStartY == -1 ? 0 : Math.max(0, fallStartY - mc.player.getY());
        return fallDist >= (float) minFallDistance.value;
    }

    private void handleMaceAttack(Entity target, double fallDist) {
        if (mc.gameMode == null) return;

        if (shieldBreakHandler.handleShieldBreak(target, fallDist)) {
            return;
        }

        if (!shieldBreakHandler.hasMace() && !maceHit) {
            if (savedSlot == -1) savedSlot = PacketUtils.getSelectedSlot();
            if (autoSwitch.value) {
                shieldBreakHandler.switchToAppropriateMace(fallDist);
            }
        }

        if (shieldBreakHandler.hasMace() && !maceHit) {
            executeAttack(target);
            registerMaceHit();
        }
    }

    public void registerMaceHit() {
        maceHit = true;
        long now = System.currentTimeMillis();
        lastMaceHitTime = now;
        if (!stayOnMace.value && swapBackOnHit.value) {
            swapBackAt = now + 50L;
        }
    }

    public static class NumVal {
        private final Setting.Num setting;
        public NumVal(Setting.Num setting) { this.setting = setting; }
        public Double getValue() { return setting.value; }
        public double doubleValue() { return setting.value; }
        public float floatValue() { return (float) setting.value; }
        public long longValue() { return Math.round(setting.value); }
        public int intValue() { return setting.intValue(); }
    }

    public static class BoolVal {
        private final Setting.Bool setting;
        public BoolVal(Setting.Bool setting) { this.setting = setting; }
        public Boolean getValue() { return setting.value; }
        public boolean get() { return setting.value; }
    }

    public static class RangeVal {
        private final Setting.Num min;
        private final Setting.Num max;
        public RangeVal(Setting.Num min, Setting.Num max) { this.min = min; this.max = max; }
        public double getMinValue() { return min.value; }
        public double getMaxValue() { return max.value; }
        public double getRandomValue() {
            if (min.value >= max.value) return min.value;
            return min.value + Math.random() * (max.value - min.value);
        }
    }

    public static class EnumVal<E extends Enum<E>> {
        private final Setting.Num setting;
        private final E[] values;
        public EnumVal(Setting.Num setting, E[] values) { this.setting = setting; this.values = values; }
        public E getValue() {
            int idx = Math.min(values.length - 1, Math.max(0, setting.intValue()));
            return values[idx];
        }
    }

    public ShieldMode getShieldMode() {
        return ShieldMode.values()[Math.min(ShieldMode.values().length - 1, Math.max(0, shieldMode.intValue()))];
    }

    public EnumVal<ShieldMode> getShieldModeSetting() { return new EnumVal<>(shieldMode, ShieldMode.values()); }
    public EnumVal<Mode> getMode() { return new EnumVal<>(mode, Mode.values()); }
    public NumVal getRange() { return new NumVal(range); }
    public NumVal getAimRange() { return new NumVal(aimRange); }
    public NumVal getFov() { return new NumVal(fov); }
    public BoolVal getSameTarget() { return new BoolVal(sameTarget); }
    public EnumVal<AimMode> getAimMode() { return new EnumVal<>(aimMode, AimMode.values()); }
    public EnumVal<TargetPointMode> getTargetPointMode() { return new EnumVal<>(targetPointMode, TargetPointMode.values()); }
    public NumVal getAimSpeed() { return new NumVal(aimSpeed); }
    public BoolVal getFastStunSlam() { return new BoolVal(fastStunSlam); }
    public NumVal getStunSlamDelay() { return new NumVal(stunSlamDelay); }
    public BoolVal getDirectPackets() { return new BoolVal(directPackets); }
    public NumVal getMinFallDistance() { return new NumVal(minFallDistance); }
    public BoolVal getCheckFalling() { return new BoolVal(checkFalling); }
    public NumVal getDensityThreshold() { return new NumVal(densityThreshold); }
    public RangeVal getReactionDelay() { return new RangeVal(reactionDelayMin, reactionDelayMax); }
    public RangeVal getDrainClicks() { return new RangeVal(drainClicksMin, drainClicksMax); }
    public BoolVal getAutoRocket() { return new BoolVal(autoRocket); }
    public BoolVal getRocketLoop() { return new BoolVal(rocketLoop); }
    public NumVal getRocketTargetRange() { return new NumVal(rocketTargetRange); }
    public NumVal getRocketAttackRange() { return new NumVal(rocketAttackRange); }
    public NumVal getRocketSnapFov() { return new NumVal(rocketSnapFov); }
    public NumVal getRocketHeightAboveTarget() { return new NumVal(rocketHeightAboveTarget); }
    public NumVal getRocketFireworkDelay() { return new NumVal(rocketFireworkDelay); }
    public NumVal getRocketDropDelay() { return new NumVal(rocketDropDelay); }
    public NumVal getRocketAttackDelay() { return new NumVal(rocketAttackDelay); }
    public NumVal getRocketReequipDelay() { return new NumVal(rocketReequipDelay); }
    public NumVal getRocketGlideDelay() { return new NumVal(rocketGlideDelay); }
    public NumVal getRocketAimOffset() { return new NumVal(rocketAimOffset); }
    public BoolVal getRocketJuice() { return new BoolVal(rocketJuice); }
    public BoolVal getRocketPathfind() { return new BoolVal(rocketPathfind); }
    public BoolVal getRocketRecover() { return new BoolVal(rocketRecover); }
    public BoolVal getTargetPlayers() { return new BoolVal(targetPlayers); }
    public BoolVal getTargetMobs() { return new BoolVal(targetMobs); }
    public BoolVal getWebCheck() { return new BoolVal(webCheck); }
    public EnumVal<CombatTargetUtil.Obstruction> getObstruction() { return new EnumVal<>(obstruction, CombatTargetUtil.Obstruction.values()); }
    public BoolVal getAutoSwitch() { return new BoolVal(autoSwitch); }
    public BoolVal getStayOnMace() { return new BoolVal(stayOnMace); }
    public BoolVal getSwapBackOnHit() { return new BoolVal(swapBackOnHit); }
    public BoolVal getRetrySlam() { return new BoolVal(retrySlam); }
    public NumVal getRetryDelay() { return new NumVal(retryDelay); }
    public BoolVal getSpearDtap() { return new BoolVal(spearDtap); }
    public EnumVal<PriorityMode> getTargetPriority() { return new EnumVal<>(targetPriority, PriorityMode.values()); }

    public RocketDiveHandler getRocketDiveHandler() { return rocketDiveHandler; }
    public ShieldBreakHandler getShieldBreakHandler() { return shieldBreakHandler; }
    public AutoMaceTargeting getTargeting() { return targeting; }

    public int getSavedSlot() { return savedSlot; }
    public void setSavedSlot(int slot) { this.savedSlot = slot; }
    public boolean isMaceHit() { return maceHit; }
}
