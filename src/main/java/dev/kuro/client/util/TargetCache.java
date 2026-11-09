package dev.kuro.client.util;

import dev.kuro.client.event.EventBus;
import dev.kuro.client.event.EventHandler;
import dev.kuro.client.event.events.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public final class TargetCache {

    public static final TargetCache INSTANCE = new TargetCache();

    public static final double RANGE = 24.0;
    public static final double RANGE_SQ = RANGE * RANGE;

    private static final int SCAN_LIMIT = 256;

    private final List<LivingEntity> living = new ArrayList<>(64);
    private final List<Player> players = new ArrayList<>(16);
    private final List<LivingEntity> buffer = new ArrayList<>(SCAN_LIMIT);

    private int tick;
    private int refreshedTick = -1;

    private TargetCache() {
    }

    public static void init() {
        EventBus.INSTANCE.register(INSTANCE);
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        tick++;
    }

    public static List<LivingEntity> living() {
        INSTANCE.ensureFresh();
        return INSTANCE.living;
    }

    public static List<Player> players() {
        INSTANCE.ensureFresh();
        return INSTANCE.players;
    }

    public static double horizontalDistanceSq(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        if (self == null || entity == null) return Double.MAX_VALUE;
        double dx = entity.getX() - self.getX();
        double dz = entity.getZ() - self.getZ();
        return dx * dx + dz * dz;
    }

    public static boolean inRange(Entity entity) {
        return horizontalDistanceSq(entity) <= RANGE_SQ;
    }

    private void ensureFresh() {
        Minecraft mc = Minecraft.getInstance();
        int curTick = mc.player != null ? mc.player.tickCount : tick;
        if (refreshedTick == curTick) return;
        refreshedTick = curTick;
        refresh();
    }

    private void refresh() {
        living.clear();
        players.clear();
        buffer.clear();

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        if (self == null || mc.level == null) return;

        double px = self.getX();
        double pz = self.getZ();
        AABB area = new AABB(px - RANGE, mc.level.getMinY(), pz - RANGE, px + RANGE, mc.level.getMaxY(), pz + RANGE);

        mc.level.getEntities(EntityTypeTest.forClass(LivingEntity.class), area,
                e -> e != self && e.isAlive() && !e.isRemoved(), buffer, SCAN_LIMIT);

        for (int i = 0; i < buffer.size(); i++) {
            LivingEntity entity = buffer.get(i);
            double dx = entity.getX() - px;
            double dz = entity.getZ() - pz;
            if (dx * dx + dz * dz > RANGE_SQ) continue;
            living.add(entity);
            if (entity instanceof Player player) {
                players.add(player);
            }
        }
    }
}
