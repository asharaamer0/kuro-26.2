package dev.kuro.client.module.impl.automace;

import dev.kuro.client.module.impl.AntiBot;
import dev.kuro.client.module.impl.AutoMace;
import dev.kuro.client.util.CombatTargetUtil;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.RotationHandler;
import dev.kuro.client.util.TargetCache;
import dev.kuro.client.util.TargetSync;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AutoMaceTargeting {

    private static final Minecraft mc = Minecraft.getInstance();

    private final AutoMace parent;

    public AutoMaceTargeting(AutoMace parent) {
        this.parent = parent;
    }

    public float[] applyGCDMouseFix(float currentYaw, float currentPitch, float targetYaw, float targetPitch) {
        float sens = mc.options.sensitivity().get().floatValue() * 0.6F + 0.2F;
        float gcd = sens * sens * sens * 1.2F;
        float finalYaw = currentYaw + Math.round((targetYaw - currentYaw) / gcd) * gcd;
        float finalPitch = currentPitch + Math.round((targetPitch - currentPitch) / gcd) * gcd;
        finalPitch = Math.max(-90.0F, Math.min(90.0F, finalPitch));
        return new float[]{finalYaw, finalPitch};
    }

    public float[] calculateAdvancedRotations(Player player, Entity entity) {
        AABB boundingBox = entity.getBoundingBox();
        Vec3 eyes = player.getEyePosition();
        Vec3 targetPoint = null;

        switch (parent.getTargetPointMode().getValue()) {
            case CENTER:
                targetPoint = new Vec3(entity.getX(), entity.getY() + (entity.getBbHeight() / 2.0D), entity.getZ());
                break;
            case NEAREST_VECTOR:
                float currentYaw = RotationHandler.isSilentActive() ? RotationHandler.getSilentYaw() : player.getYRot();
                float currentPitch = RotationHandler.isSilentActive() ? RotationHandler.getSilentPitch() : player.getXRot();

                float f = Mth.cos(-currentYaw * 0.017453292F - (float) Math.PI);
                float f1 = Mth.sin(-currentYaw * 0.017453292F - (float) Math.PI);
                float f2 = -Mth.cos(-currentPitch * 0.017453292F);
                float f3 = Mth.sin(-currentPitch * 0.017453292F);

                Vec3 lookVec = new Vec3(f1 * f2, f3, f * f2);

                double maxT = parent.getAimRange().getValue();
                double step = maxT / 15.0;
                double bestDistSq = Double.MAX_VALUE;

                for (int i = 0; i <= 15; i++) {
                    double t = i * step;
                    Vec3 rayPoint = eyes.add(lookVec.scale(t));

                    double clampedX = Math.max(boundingBox.minX, Math.min(rayPoint.x, boundingBox.maxX));
                    double clampedY = Math.max(boundingBox.minY, Math.min(rayPoint.y, boundingBox.maxY));
                    double clampedZ = Math.max(boundingBox.minZ, Math.min(rayPoint.z, boundingBox.maxZ));

                    Vec3 boxPoint = new Vec3(clampedX, clampedY, clampedZ);

                    double dSq = rayPoint.distanceToSqr(boxPoint);
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        targetPoint = boxPoint;
                    }
                }
                break;
            case CLOSEST:
            default:
                double minX = Math.max(boundingBox.minX, Math.min(eyes.x, boundingBox.maxX));
                double minY = Math.max(boundingBox.minY, Math.min(eyes.y, boundingBox.maxY));
                double minZ = Math.max(boundingBox.minZ, Math.min(eyes.z, boundingBox.maxZ));
                targetPoint = new Vec3(minX, minY, minZ);
                break;
        }

        if (targetPoint == null || !player.hasLineOfSight(entity)) {
            targetPoint = new Vec3(entity.getX(), entity.getY() + (entity.getBbHeight() * 0.75D), entity.getZ());
        }

        double diffX = targetPoint.x - eyes.x;
        double diffY = targetPoint.y - eyes.y;
        double diffZ = targetPoint.z - eyes.z;
        double hypotenuse = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float yaw = (float) (Math.atan2(diffZ, diffX) * 180.0D / Math.PI) - 90.0F;
        float pitch = (float) -(Math.atan2(diffY, hypotenuse) * 180.0D / Math.PI);

        return new float[]{yaw, pitch};
    }

    public boolean isValidTarget(Entity entity) {
        return isValidTarget(entity, parent.getRange().getValue());
    }

    public boolean isAttackable(Entity entity) {
        return isAttackable(entity, parent.getRange().getValue());
    }

    public boolean isAttackable(Entity entity, double maxRange) {
        if (!isValidTarget(entity, maxRange)) return false;
        if (!(entity instanceof LivingEntity living)) return false;
        return !CombatTargetUtil.isObstructed(mc.player, living, parent.getObstruction().getValue(), maxRange);
    }

    public boolean isValidTarget(Entity entity, double maxRange) {
        if (entity == null || entity == mc.player || entity == mc.getCameraEntity()) return false;
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) return false;
        if (mc.player.distanceTo(entity) > maxRange) return false;
        if (parent.getWebCheck().getValue() && CombatTargetUtil.inWeb(living)) return false;

        if (entity instanceof Player p) {
            if (!parent.getTargetPlayers().getValue()) return false;
            if (p.isCreative() || p.isSpectator()) return false;
            if (Friends.shield(p) || Friends.is(p)) return false;
            if (AntiBot.isBot(p)) return false;
            return true;
        } else {
            if (!parent.getTargetMobs().getValue()) return false;
            return true;
        }
    }

    public Entity findOptimalTarget() {
        if (parent.getSameTarget().getValue()) {
            Entity synced = TargetSync.get();
            if (isAttackable(synced, parent.getAimRange().getValue())) {
                TargetSync.set(synced);
                return synced;
            }
        }

        double bestMetric = Double.MAX_VALUE;
        Entity best = null;

        for (LivingEntity living : TargetCache.living()) {
            if (!isValidTarget(living, parent.getAimRange().getValue())) continue;
            if (!mc.player.hasLineOfSight(living)) continue;

            if (parent.getFov().getValue() < 360.0) {
                float[] rot = calculateAdvancedRotations(mc.player, living);
                float yawDiff = Math.abs(RotationHandler.getAngleDifference(rot[0], mc.player.getYRot()));
                float pitchDiff = Math.abs(rot[1] - mc.player.getXRot());
                if (Math.hypot(yawDiff, pitchDiff) > parent.getFov().getValue() / 2.0) continue;
            }

            double metricValue;
            switch (parent.getTargetPriority().getValue()) {
                case LOWEST_HP:
                    metricValue = living.getHealth();
                    break;
                case HIGHEST_HP:
                    metricValue = -living.getHealth();
                    break;
                case DISTANCE:
                default:
                    metricValue = mc.player.distanceToSqr(living);
                    break;
            }

            if (metricValue < bestMetric) {
                if (CombatTargetUtil.isObstructed(mc.player, living, parent.getObstruction().getValue(), parent.getAimRange().getValue())) {
                    continue;
                }
                bestMetric = metricValue;
                best = living;
            }
        }
        if (best != null) {
            TargetSync.set(best);
        }
        return best;
    }
}
