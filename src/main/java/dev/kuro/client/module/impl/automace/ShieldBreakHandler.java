package dev.kuro.client.module.impl.automace;

import dev.kuro.client.module.impl.AutoMace;
import dev.kuro.client.util.PacketUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class ShieldBreakHandler {

    private static final Minecraft mc = Minecraft.getInstance();

    private final AutoMace parent;
    private int slamState = 0;
    private long slamTimer = 0L;

    public ShieldBreakHandler(AutoMace parent) {
        this.parent = parent;
    }

    public int getSlamState() {
        return slamState;
    }

    public void setSlamState(int state) {
        this.slamState = state;
    }

    public void reset() {
        this.slamState = 0;
        this.slamTimer = 0L;
    }

    public boolean handleShieldBreak(Entity target, double fallDist) {
        if (mc.gameMode == null || mc.player == null) return false;

        boolean targetBlocking = target instanceof LivingEntity && ((LivingEntity) target).isBlocking();

        if ((targetBlocking || slamState > 0) && parent.getShieldMode() == AutoMace.ShieldMode.STUN_SLAM) {
            int axeSlot = findAxeSlot();
            if (axeSlot != -1 && parent.getAutoSwitch().getValue()) {
                if (slamState == 0) {
                    if (parent.getSavedSlot() == -1) parent.setSavedSlot(PacketUtils.getSelectedSlot());
                    PacketUtils.setSelectedSlot(axeSlot);
                    parent.executeAttack(target);

                    slamState = 1;
                    slamTimer = System.currentTimeMillis();

                    if (parent.getFastStunSlam().getValue()) {
                        switchToAppropriateMace(fallDist);
                        parent.executeAttack(target);
                        slamState = 2;
                        parent.registerMaceHit();
                    }
                    return true;
                } else if (slamState == 1) {
                    if (System.currentTimeMillis() - slamTimer >= parent.getStunSlamDelay().getValue().longValue()) {
                        switchToAppropriateMace(fallDist);
                        parent.executeAttack(target);
                        slamState = 2;
                        parent.registerMaceHit();
                    }
                    return true;
                } else if (slamState == 2) {
                    return true;
                }
            }
        }

        if (targetBlocking && parent.getShieldMode() == AutoMace.ShieldMode.DRAIN) {
            if (parent.getSavedSlot() == -1) parent.setSavedSlot(PacketUtils.getSelectedSlot());
            if (parent.getAutoSwitch().getValue()) switchToAppropriateMace(fallDist);

            int clicks = (int) (parent.getDrainClicks().getMinValue() + Math.random() * (parent.getDrainClicks().getMaxValue() - parent.getDrainClicks().getMinValue()));
            for (int i = 0; i < clicks; i++) {
                parent.executeAttack(target);
            }
            parent.registerMaceHit();
            return true;
        }

        return false;
    }

    public int findAxeSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof AxeItem) {
                return i;
            }
        }
        return -1;
    }

    public void switchToAppropriateMace(double fallDistance) {
        boolean useDensity = fallDistance >= parent.getDensityThreshold().getValue().floatValue();
        int primarySlot = useDensity ? findEnchantedMaceSlot("density") : findEnchantedMaceSlot("breach");
        int secondarySlot = useDensity ? findEnchantedMaceSlot("breach") : findEnchantedMaceSlot("density");

        int targetSlot = primarySlot != -1 ? primarySlot : (secondarySlot != -1 ? secondarySlot : findAnyMaceSlot());

        if (targetSlot != -1) {
            PacketUtils.setSelectedSlot(targetSlot);
        }
    }

    public int findEnchantedMaceSlot(String enchantKey) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.MACE) {
                var enchants = stack.getEnchantments();
                if (enchants != null) {
                    String str = enchants.toString().toLowerCase();
                    if (str.contains(enchantKey.toLowerCase())) {
                        return i;
                    }
                    for (var entry : enchants.entrySet()) {
                        String keyName = entry.getKey().getRegisteredName().toLowerCase();
                        if (keyName.contains(enchantKey.toLowerCase())) {
                            return i;
                        }
                    }
                }
            }
        }
        return -1;
    }

    public int findAnyMaceSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() == Items.MACE) {
                return i;
            }
        }
        return -1;
    }

    public boolean hasMace() {
        if (mc.player == null) return false;
        ItemStack held = mc.player.getInventory().getItem(PacketUtils.getSelectedSlot());
        return !held.isEmpty() && held.is(Items.MACE);
    }
}
