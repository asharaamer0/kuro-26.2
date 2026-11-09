package dev.kuro.client.util;

import net.minecraft.world.entity.Entity;

public final class CombatTargetUtil {
    public enum Obstruction {
        OFF,
        CRYSTALS,
        ALL
    }

    private CombatTargetUtil() {}

    public static boolean inWeb(Entity entity) {
        return TargetUtil.inWeb(entity);
    }

    public static boolean isObstructed(Entity self, Entity target, Obstruction mode, double reach) {
        TargetUtil.Obstruction targetMode = mode == null ? TargetUtil.Obstruction.OFF : switch (mode) {
            case CRYSTALS -> TargetUtil.Obstruction.CRYSTALS;
            case ALL -> TargetUtil.Obstruction.ALL;
            default -> TargetUtil.Obstruction.OFF;
        };
        return TargetUtil.isObstructed(self, target, targetMode, reach);
    }
}
