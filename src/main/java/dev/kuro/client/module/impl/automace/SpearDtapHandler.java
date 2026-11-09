package dev.kuro.client.module.impl.automace;

import dev.kuro.client.event.events.KineticHitEvent;
import dev.kuro.client.module.impl.AutoMace;
import dev.kuro.client.util.PacketUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;

public class SpearDtapHandler {

    private static final Minecraft mc = Minecraft.getInstance();
    private static final long DTAP_SELF_FEEDBACK_GUARD_MS = 300L;

    private final AutoMace parent;
    private final ShieldBreakHandler shieldBreakHandler;
    private final AutoMaceTargeting targeting;

    private boolean dtapArmed = false;
    private int dtapOriginalSlot = -1;
    private int dtapSwapBackTick = 0;
    private long dtapNoArmUntil = 0L;

    public SpearDtapHandler(AutoMace parent, ShieldBreakHandler shieldBreakHandler, AutoMaceTargeting targeting) {
        this.parent = parent;
        this.shieldBreakHandler = shieldBreakHandler;
        this.targeting = targeting;
    }

    public void onMotionPre() {
        if (dtapSwapBackTick > 0) {
            dtapSwapBackTick--;
            if (dtapSwapBackTick == 0 && dtapOriginalSlot != -1) {
                PacketUtils.setSelectedSlot(dtapOriginalSlot);
                dtapOriginalSlot = -1;
            }
        }

        if (dtapArmed) {
            dtapArmed = false;
            executeDtapMace();
        }
    }

    public void onKineticHit(KineticHitEvent event) {
        if (!parent.getSpearDtap().getValue()) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (dtapArmed || dtapSwapBackTick > 0) return;

        if (!parent.getRocketDiveHandler().isIdle() || parent.isMaceHit() || shieldBreakHandler.getSlamState() != 0) return;

        if (System.currentTimeMillis() < dtapNoArmUntil) return;
        boolean holdsSpear = mc.player.getMainHandItem().is(ItemTags.SPEARS)
                || mc.player.getOffhandItem().is(ItemTags.SPEARS);
        if (!holdsSpear) return;

        dtapOriginalSlot = PacketUtils.getSelectedSlot();
        dtapArmed = true;
    }

    private void executeDtapMace() {
        if (mc.player == null || mc.level == null || mc.gameMode == null || dtapOriginalSlot == -1) {
            dtapOriginalSlot = -1;
            return;
        }
        int maceSlot = findDtapMaceSlot();
        if (maceSlot == -1) return;
        Entity target = findDtapTarget();
        if (target == null) return;

        PacketUtils.setSelectedSlot(maceSlot);
        parent.executeAttack(target);
        dtapNoArmUntil = System.currentTimeMillis() + DTAP_SELF_FEEDBACK_GUARD_MS;
        dtapSwapBackTick = 1;
    }

    private int findDtapMaceSlot() {
        boolean useDensity = mc.player.fallDistance >= parent.getDensityThreshold().getValue().floatValue();
        int primary = useDensity ? shieldBreakHandler.findEnchantedMaceSlot("density") : shieldBreakHandler.findEnchantedMaceSlot("breach");
        int secondary = useDensity ? shieldBreakHandler.findEnchantedMaceSlot("breach") : shieldBreakHandler.findEnchantedMaceSlot("density");
        return primary != -1 ? primary : (secondary != -1 ? secondary : shieldBreakHandler.findAnyMaceSlot());
    }

    private Entity findDtapTarget() {
        Entity cross = mc.crosshairPickEntity;
        if (targeting.isValidTarget(cross, parent.getRange().getValue())) return cross;
        return targeting.findOptimalTarget();
    }

    public void reset() {
        dtapArmed = false;
        dtapOriginalSlot = -1;
        dtapSwapBackTick = 0;
        dtapNoArmUntil = 0L;
    }

    public void onDisable() {
        if (dtapSwapBackTick > 0 && dtapOriginalSlot != -1 && mc.player != null) {
            PacketUtils.setSelectedSlot(dtapOriginalSlot);
        }
        reset();
    }
}
