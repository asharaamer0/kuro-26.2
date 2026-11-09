package dev.kuro.client.module.impl.automace;

import dev.kuro.client.module.impl.AutoMace;
import dev.kuro.client.util.PacketUtils;
import dev.kuro.client.util.RotationHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class RocketDiveHandler {

    private static final Minecraft mc = Minecraft.getInstance();

    private static final int DIVE_IDLE = 0;
    private static final int DIVE_BOOST = 1;
    private static final int DIVE_CHASE = 2;
    private static final int DIVE_DROP_ELYTRA = 3;
    private static final int DIVE_ATTACK = 4;
    private static final int DIVE_REEQUIP = 5;
    private static final int DIVE_GLIDE = 6;
    private static final int DIVE_RECOVERY_BOOST = 7;
    private static final int DIVE_CLIMB = 8;
    private static final int DIVE_RECOVER = 9;

    private static final double DIVE_AIM_RESPONSE = 120.0;
    private static final float DIVE_RECOVERY_PITCH = -80.0F;
    private static final long DIVE_GLIDE_START_TIMEOUT = 800L;

    private final AutoMace parent;

    private int diveState = DIVE_IDLE;
    private int diveOriginalSlot = -1;
    private int diveChestSlot = -1;
    private int diveElytraSlot = -1;
    private Entity diveTarget;
    private long diveNextActionTime;
    private long diveLastFireworkTime;
    private long diveLastAimTime;
    private long diveLastAttackTime;
    private long diveGlideStartDeadline;
    private int diveGlideJumpTicks;
    private boolean diveForcingGlideJump;
    private double diveFallStartY = Double.NaN;
    private long rocketJuiceUntil;
    private long recoverStartMs;

    public RocketDiveHandler(AutoMace parent) {
        this.parent = parent;
    }

    public boolean isIdle() {
        return diveState == DIVE_IDLE;
    }

    public void handleDive() {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            resetDive(false);
            return;
        }

        tickDiveGlideJump();

        if (diveState == DIVE_IDLE) {
            if (readyToDive()) {
                startDive();
            }
            return;
        }

        if (mc.gui.screen() != null || mc.player.isUsingItem()) {
            return;
        }

        tickDiveFallStart();

        if (mc.player.onGround() || (diveTarget != null && distanceTo(diveTarget) < 1.2)) {
            rocketJuiceUntil = 0L;
        }

        tickDiveState();

        if (shouldAimAtTarget() && diveTarget != null && validRocketTarget(diveTarget)) {
            snapTo(diveTarget);
        }
    }

    private boolean readyToDive() {
        return mc.player != null
                && mc.level != null
                && mc.gameMode != null
                && mc.gui.screen() == null
                && !mc.player.isUsingItem()
                && !mc.player.onGround()
                && mc.player.isFallFlying()
                && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private void startDive() {
        if (!readyToDive()) {
            resetDive(false);
            return;
        }

        diveTarget = findRocketTarget();
        if (diveTarget == null) {
            resetDive(false);
            return;
        }

        diveOriginalSlot = PacketUtils.getSelectedSlot();
        diveChestSlot = findChestArmorSlot();
        if (diveChestSlot == -1 || findRocketSlot() == -1 || findDiveMaceSlot() == -1) {
            resetDive(false);
            return;
        }

        diveFallStartY = mc.player.getY();
        diveState = DIVE_BOOST;
        diveNextActionTime = 0L;
        tickDiveState();
    }

    private void tickDiveState() {
        long now = System.currentTimeMillis();
        if (now < diveNextActionTime) {
            return;
        }

        if (!validRocketTarget(diveTarget)) {
            if (parent.getRocketLoop().getValue()) {
                diveState = DIVE_IDLE;
                startDive();
            } else {
                resetDive(true);
            }
            return;
        }

        if (diveState == DIVE_BOOST) {
            if (!boostUp(now)) {
                resetDive(true);
                return;
            }
            diveState = DIVE_CLIMB;
            diveNextActionTime = now + parent.getRocketDropDelay().getValue().longValue();
            return;
        }

        if (diveState == DIVE_CLIMB) {
            if (!mc.player.isFallFlying()) {
                resetDive(true);
                return;
            }
            if (parent.getMode().getValue() == AutoMace.Mode.SILENT) {
                RotationHandler.setSilent(mc.player.getYRot(), DIVE_RECOVERY_PITCH, 10.0f);
            } else {
                mc.player.setXRot(DIVE_RECOVERY_PITCH);
            }
            if (mc.player.getY() >= climbHeight() || now - diveLastFireworkTime < parent.getRocketFireworkDelay().getValue().longValue()) {
                if (mc.player.getY() >= climbHeight()) {
                    diveFallStartY = mc.player.getY();
                    diveState = DIVE_CHASE;
                }
                return;
            }
            if (!boostUp(now)) {
                resetDive(true);
            }
            return;
        }

        if (diveState == DIVE_CHASE) {
            if (!mc.player.isFallFlying()) {
                if (tryRecoverGround(now)) {
                    return;
                }
                resetDive(true);
                return;
            }
            if (distanceTo(diveTarget) <= parent.getRocketAttackRange().getValue() && diveFallDistance() >= parent.getMinFallDistance().getValue()) {
                diveState = DIVE_DROP_ELYTRA;
            }
            return;
        }

        if (diveState == DIVE_DROP_ELYTRA) {
            if (!dropElytra()) {
                resetDive(true);
                return;
            }
            diveState = DIVE_ATTACK;
            diveNextActionTime = now + parent.getRocketAttackDelay().getValue().longValue();
            return;
        }

        if (diveState == DIVE_ATTACK) {
            if (distanceTo(diveTarget) > parent.getRocketAttackRange().getValue() + 1.0 || diveFallDistance() < parent.getMinFallDistance().getValue()) {
                resetDive(true);
                return;
            }
            if (!switchDiveMace() || !diveAttack(diveTarget)) {
                resetDive(true);
                return;
            }

            if (parent.getRocketJuice().getValue()
                    && now < rocketJuiceUntil
                    && !mc.player.onGround()
                    && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
                diveState = DIVE_CHASE;
                return;
            }
            diveState = DIVE_REEQUIP;
            diveNextActionTime = now + parent.getRocketReequipDelay().getValue().longValue();
            return;
        }

        if (diveState == DIVE_REEQUIP) {
            if (!reequipElytra()) {
                resetDive(true);
                return;
            }
            diveState = DIVE_GLIDE;
            diveNextActionTime = now + parent.getRocketGlideDelay().getValue().longValue();
            return;
        }

        if (diveState == DIVE_GLIDE) {
            if (!startGlide(now)) {
                if (now > diveGlideStartDeadline) {
                    resetDive(true);
                }
                return;
            }
            if (parent.getRocketLoop().getValue()) {
                diveState = DIVE_RECOVERY_BOOST;
                diveNextActionTime = now + parent.getRocketGlideDelay().getValue().longValue();
            } else {
                resetDive(true);
            }
            return;
        }

        if (diveState == DIVE_RECOVERY_BOOST) {
            if (!startGlide(now)) {
                if (tryRecoverGround(now)) {
                    return;
                }
                if (now > diveGlideStartDeadline) {
                    resetDive(true);
                }
                return;
            }
            if (!boostUp(now)) {
                resetDive(true);
                return;
            }
            diveState = DIVE_CLIMB;
            diveNextActionTime = now + parent.getRocketDropDelay().getValue().longValue();
            return;
        }

        if (diveState == DIVE_RECOVER) {
            if (now - recoverStartMs > 1500L) {
                resetDive(true);
                return;
            }
            if (mc.player.onGround()) {
                mc.player.jumpFromGround();
                diveGlideJumpTicks = Math.max(diveGlideJumpTicks, 2);
                diveForcingGlideJump = true;
                return;
            }
            if (mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) && startGlide(now)) {
                if (parent.getRocketJuice().getValue() && now < rocketJuiceUntil) {
                    diveState = DIVE_CLIMB;
                } else {
                    diveState = DIVE_RECOVERY_BOOST;
                }
                diveNextActionTime = now + parent.getRocketGlideDelay().getValue().longValue();
            } else if (now > diveGlideStartDeadline && diveGlideStartDeadline != 0L) {
                resetDive(true);
            }
        }
    }

    private boolean tryRecoverGround(long now) {
        if (!parent.getRocketRecover().getValue()) {
            return false;
        }
        if (!mc.player.onGround()) {
            return false;
        }
        if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return false;
        }
        diveState = DIVE_RECOVER;
        recoverStartMs = now;
        diveGlideStartDeadline = now + DIVE_GLIDE_START_TIMEOUT;
        return true;
    }

    private boolean boostUp(long now) {
        if (parent.getMode().getValue() == AutoMace.Mode.SILENT) {
            RotationHandler.setSilent(mc.player.getYRot(), DIVE_RECOVERY_PITCH, 10.0f);
        } else {
            mc.player.setXRot(DIVE_RECOVERY_PITCH);
        }

        if (parent.getRocketJuice().getValue() && now < rocketJuiceUntil) {
            return true;
        }
        return useFirework(now);
    }

    private double climbHeight() {
        return diveTarget == null ? mc.player.getY() : diveTarget.getY() + parent.getRocketHeightAboveTarget().getValue();
    }

    private boolean useFirework(long now) {
        int fireworkSlot = findRocketSlot();
        if (fireworkSlot == -1) {
            return false;
        }
        ItemStack stack = mc.player.getInventory().getItem(fireworkSlot);
        selectDiveSlot(fireworkSlot);
        boolean accepted = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND).consumesAction();
        if (accepted) {
            mc.player.swing(InteractionHand.MAIN_HAND);
            diveLastFireworkTime = now;
            rocketJuiceUntil = now + rocketJuiceMs(stack);
        }
        return accepted;
    }

    private long rocketJuiceMs(ItemStack rocket) {
        int flightDuration = 1;
        try {
            Fireworks fireworks = rocket.get(DataComponents.FIREWORKS);
            if (fireworks != null) {
                flightDuration = Math.max(1, fireworks.flightDuration());
            }
        } catch (Throwable ignored) {}
        return Math.max(250L, ((flightDuration + 1L) * 10L - 5L) * 50L);
    }

    private boolean dropElytra() {
        if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return true;
        }
        if (diveChestSlot == -1 || !isChestArmor(mc.player.getInventory().getItem(diveChestSlot))) {
            diveChestSlot = findChestArmorSlot();
        }
        if (diveChestSlot == -1) {
            return false;
        }
        selectDiveSlot(diveChestSlot);
        boolean accepted = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND).consumesAction();
        if (accepted) {
            mc.player.swing(InteractionHand.MAIN_HAND);
            diveElytraSlot = diveChestSlot;
        }
        return accepted;
    }

    private boolean switchDiveMace() {
        int slot = findDiveMaceSlot();
        if (slot == -1) {
            return false;
        }
        selectDiveSlot(slot);
        return mc.player.getMainHandItem().is(Items.MACE);
    }

    private boolean diveAttack(Entity entity) {
        long now = System.currentTimeMillis();
        if (now - diveLastAttackTime < parent.getRocketAttackDelay().getValue().longValue()) {
            return false;
        }
        parent.executeAttack(entity);
        diveLastAttackTime = now;
        return true;
    }

    private boolean reequipElytra() {
        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return true;
        }
        if (diveElytraSlot == -1 || !mc.player.getInventory().getItem(diveElytraSlot).is(Items.ELYTRA)) {
            diveElytraSlot = findDiveElytraSlot();
        }
        if (diveElytraSlot == -1) {
            return false;
        }
        selectDiveSlot(diveElytraSlot);
        boolean accepted = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND).consumesAction();
        if (accepted) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        return accepted;
    }

    private boolean startGlide(long now) {
        if (mc.player.onGround() || !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return false;
        }
        if (mc.player.isFallFlying()) {
            diveGlideStartDeadline = 0L;
            return true;
        }
        if (diveGlideStartDeadline == 0L) {
            diveGlideStartDeadline = now + DIVE_GLIDE_START_TIMEOUT;
        }
        if (now > diveGlideStartDeadline) {
            return false;
        }
        diveGlideJumpTicks = Math.max(diveGlideJumpTicks, 2);
        diveForcingGlideJump = true;
        mc.options.keyJump.setDown(true);
        return false;
    }

    private void tickDiveGlideJump() {
        if (diveGlideJumpTicks > 0) {
            mc.options.keyJump.setDown(true);
            diveGlideJumpTicks--;
        } else if (diveForcingGlideJump) {
            mc.options.keyJump.setDown(false);
            diveForcingGlideJump = false;
        }
    }

    private boolean shouldAimAtTarget() {
        return diveState != DIVE_IDLE
                && diveState != DIVE_GLIDE
                && diveState != DIVE_RECOVERY_BOOST
                && diveState != DIVE_CLIMB;
    }

    private Entity findRocketTarget() {
        Entity best = null;
        double bestScore = Double.MAX_VALUE;

        double rocketRange = parent.getRocketTargetRange().getValue();
        for (Entity entity : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(rocketRange))) {
            if (!validRocketTarget(entity) || !mc.player.hasLineOfSight(entity)) {
                continue;
            }
            double distance = distanceTo(entity);
            if (distance > rocketRange) {
                continue;
            }
            float[] rotation = rotationTo(aimPoint(entity));
            double fovDistance = Math.hypot(yawDistance(rotation[0], mc.player.getYRot()),
                    rotation[1] - mc.player.getXRot());
            if (parent.getRocketSnapFov().getValue() < 360.0 && fovDistance > parent.getRocketSnapFov().getValue() / 2.0) {
                continue;
            }
            double score = distance + fovDistance * 0.75;
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    private boolean validRocketTarget(Entity entity) {
        if (entity == null || entity == mc.player || entity == mc.getCameraEntity()) return false;
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) return false;
        if (mc.player.distanceTo(entity) > parent.getRocketTargetRange().getValue()) return false;
        if (!mc.player.hasLineOfSight(entity)) return false;

        if (entity instanceof Player) {
            return parent.getTargetPlayers().getValue();
        } else {
            return parent.getTargetMobs().getValue();
        }
    }

    private Vec3 aimPoint(Entity entity) {
        AABB box = entity.getBoundingBox();
        double aimY = Mth.clamp(entity.getEyeY() - 0.2, box.minY + 0.1, box.maxY - 0.1);
        return new Vec3((box.minX + box.maxX) * 0.5, aimY, (box.minZ + box.maxZ) * 0.5);
    }

    private float[] rotationTo(Vec3 position) {
        Vec3 difference = position.subtract(mc.player.getEyePosition());
        double horizontal = Math.sqrt(difference.x * difference.x + difference.z * difference.z);
        float yaw = (float) Math.toDegrees(Math.atan2(difference.z, difference.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(difference.y, horizontal));
        return new float[]{yaw, Mth.clamp(pitch, -90.0F, 90.0F)};
    }

    private void snapTo(Entity entity) {
        float[] rotation = rotationTo(diveAimPoint(entity));
        if (parent.getRocketPathfind().getValue()) {
            rotation = steerAroundBlocks(rotation);
        }
        long now = System.nanoTime();
        double delta = diveLastAimTime == 0L ? 0.016 : Math.min(0.1, (now - diveLastAimTime) / 1.0E9);
        float yawDiff = yawDistance(rotation[0], mc.player.getYRot());
        float pitchDiff = rotation[1] - mc.player.getXRot();
        float blend = Mth.clamp((float) (1.0 - Math.exp(-DIVE_AIM_RESPONSE * delta)), 0.0F, 1.0F);
        float newYaw = mc.player.getYRot() + yawDiff * blend;
        float newPitch = Mth.clamp(mc.player.getXRot() + pitchDiff * blend, -90.0F, 90.0F);
        if (parent.getMode().getValue() == AutoMace.Mode.SILENT) {
            RotationHandler.setSilent(newYaw, newPitch, 10.0f);
        } else {
            mc.player.setYRot(newYaw);
            mc.player.setXRot(newPitch);
            mc.player.yRotO = newYaw;
            mc.player.xRotO = newPitch;
        }
        diveLastAimTime = now;
    }

    private Vec3 diveAimPoint(Entity entity) {
        Vec3 center = aimPoint(entity);
        double offset = parent.getRocketAimOffset().getValue();
        if (offset <= 0.0) {
            return center;
        }
        Vec3 eye = mc.player.getEyePosition();
        double dx = center.x - eye.x;
        double dz = center.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4) {
            return center;
        }

        double perpX = -dz / horizontal;
        double perpZ = dx / horizontal;
        return center.add(perpX * offset, 0.0, perpZ * offset);
    }

    private float[] steerAroundBlocks(float[] rotation) {
        if (mc.player == null || mc.level == null) {
            return rotation;
        }
        Vec3 eye = mc.player.getEyePosition();
        double probe = Math.min(6.0, Math.max(2.0, mc.player.getDeltaMovement().length() * 4.0));

        if (!pathBlocked(eye, rotation[0], rotation[1], probe)) {
            return rotation;
        }

        for (float up = 15.0F; up <= 60.0F; up += 15.0F) {
            float pitch = Mth.clamp(rotation[1] - up, -90.0F, 90.0F);
            if (!pathBlocked(eye, rotation[0], pitch, probe)) {
                return new float[]{rotation[0], pitch};
            }
        }

        for (float side = 20.0F; side <= 60.0F; side += 20.0F) {
            if (!pathBlocked(eye, rotation[0] + side, rotation[1], probe)) {
                return new float[]{rotation[0] + side, rotation[1]};
            }
            if (!pathBlocked(eye, rotation[0] - side, rotation[1], probe)) {
                return new float[]{rotation[0] - side, rotation[1]};
            }
        }

        return rotation;
    }

    private boolean pathBlocked(Vec3 from, float yaw, float pitch, double distance) {
        float yawRad = (float) Math.toRadians(-yaw) - (float) Math.PI;
        float pitchRad = (float) Math.toRadians(-pitch);
        double dx = Math.cos(yawRad) * Math.cos(pitchRad);
        double dy = Math.sin(pitchRad);
        double dz = Math.sin(yawRad) * Math.cos(pitchRad);
        for (double d = 0.5; d <= distance; d += 0.5) {
            BlockPos pos = BlockPos.containing(from.x + dx * d, from.y + dy * d, from.z + dz * d);
            if (!mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private float yawDistance(float targetYaw, float currentYaw) {
        double radians = Math.toRadians(targetYaw - currentYaw);
        return Mth.clamp((float) Math.toDegrees(Math.atan2(Math.sin(radians), Math.cos(radians))), -180.0F, 180.0F);
    }

    private double distanceTo(Entity entity) {
        Vec3 eye = mc.player.getEyePosition();
        AABB box = entity.getBoundingBox();
        double x = Mth.clamp(eye.x, box.minX, box.maxX);
        double y = Mth.clamp(eye.y, box.minY, box.maxY);
        double z = Mth.clamp(eye.z, box.minZ, box.maxZ);
        return eye.distanceTo(new Vec3(x, y, z));
    }

    private void tickDiveFallStart() {
        if (mc.player.onGround()) {
            diveFallStartY = Double.NaN;
            return;
        }
        double currentY = mc.player.getY();
        if (Double.isNaN(diveFallStartY) || currentY > diveFallStartY) {
            diveFallStartY = currentY;
        }
    }

    private double diveFallDistance() {
        return Double.isNaN(diveFallStartY) ? 0.0 : Math.max(0.0, diveFallStartY - mc.player.getY());
    }

    private int findChestArmorSlot() {
        for (int slot = 0; slot < 9; slot++) {
            if (isChestArmor(mc.player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private int findRocketSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.FIREWORK_ROCKET) {
                return i;
            }
        }
        return -1;
    }

    private int findDiveElytraSlot() {
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getItem(slot).is(Items.ELYTRA)) {
                return slot;
            }
        }
        return -1;
    }

    private int findDiveMaceSlot() {
        int fallback = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.is(Items.MACE)) {
                continue;
            }
            if (fallback == -1) {
                fallback = slot;
            }
            var enchants = stack.getEnchantments();
            if (enchants != null) {
                String str = enchants.toString().toLowerCase();
                if (str.contains("density")) {
                    return slot;
                }
                for (var entry : enchants.entrySet()) {
                    String keyName = entry.getKey().getRegisteredName().toLowerCase();
                    if (keyName.contains("density")) {
                        return slot;
                    }
                }
            }
        }
        return fallback;
    }

    private boolean isChestArmor(ItemStack stack) {
        if (stack.isEmpty()) return false;
        try {
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            if (equippable != null && equippable.slot() == EquipmentSlot.CHEST) {
                return true;
            }
        } catch (Throwable ignored) {}
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return id.endsWith("_chestplate");
    }

    private boolean selectDiveSlot(int slot) {
        if (slot < 0 || slot >= 9) {
            return false;
        }
        PacketUtils.setSelectedSlot(slot);
        return true;
    }

    public void resetDive(boolean restoreSlot) {
        if (restoreSlot && diveOriginalSlot >= 0 && diveOriginalSlot < 9) {
            PacketUtils.setSelectedSlot(diveOriginalSlot);
        }
        if (diveForcingGlideJump && mc.options != null) {
            mc.options.keyJump.setDown(false);
        }
        diveState = DIVE_IDLE;
        diveOriginalSlot = -1;
        diveChestSlot = -1;
        diveElytraSlot = -1;
        diveTarget = null;
        diveNextActionTime = 0L;
        diveLastAimTime = 0L;
        diveLastAttackTime = 0L;
        diveGlideStartDeadline = 0L;
        diveGlideJumpTicks = 0;
        diveForcingGlideJump = false;
        diveFallStartY = Double.NaN;
        rocketJuiceUntil = 0L;
        recoverStartMs = 0L;
    }
}
