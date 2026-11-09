package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class TargetUtil {

    public enum Obstruction {
        OFF,
        CRYSTALS,
        ALL
    }

    private static final BlockPos.MutableBlockPos MUTABLE_POS = new BlockPos.MutableBlockPos();

    private TargetUtil() {}

    public static boolean inWeb(Entity entity) {
        if (entity == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;

        AABB box = entity.getBoundingBox().inflate(0.05);
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (mc.level.getBlockState(MUTABLE_POS.set(x, y, z)).is(Blocks.COBWEB)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isObstructed(Entity self, Entity target, Obstruction mode, double reach) {
        return isObstructed(self, target, mode, reach, e -> true);
    }

    public static boolean isObstructed(Entity self, Entity target, Obstruction mode, double reach,
                                       Predicate<Entity> filter) {
        if (mode == Obstruction.OFF || self == null || target == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;

        AABB targetBox = target.getBoundingBox();
        AABB search = targetBox.inflate(mode == Obstruction.ALL ? Math.max(1.0, reach) : 2.0);
        List<Entity> nearby = mc.level.getEntities(self, search,
                e -> e != target && e != self && e.isPickable() && filter.test(e));

        Vec3 eyes = self.getEyePosition();
        Vec3 center = targetBox.getCenter();
        double centerDistSq = eyes.distanceToSqr(center);

        for (int i = 0; i < nearby.size(); i++) {
            Entity obstacle = nearby.get(i);
            if (mode == Obstruction.CRYSTALS && !(obstacle instanceof EndCrystal)) continue;

            if (targetBox.intersects(obstacle.getBoundingBox())) return true;

            Optional<Vec3> hit = obstacle.getBoundingBox()
                    .inflate(obstacle.getPickRadius())
                    .clip(eyes, center);
            if (hit.isPresent() && eyes.distanceToSqr(hit.get()) < centerDistSq - 1.0E-7) {
                return true;
            }
        }
        return false;
    }
}
