package dev.kuro.client.module.impl;

import dev.kuro.client.util.SwapLimit;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Auto Tool (Player): while you hold the attack button on a block, the fastest tool for that block in your hotbar is
 * selected for you (pickaxe on stone, axe on logs, shovel on dirt, sword on cobwebs...). When you stop mining it can put
 * your previous slot back.
 */
public class AutoTool extends Module {
    private final Setting.Bool switchBack = add(new Setting.Bool("Switch back", true));
    private final Setting.Num backDelay = add(new Setting.Num("Switch back delay (ticks)", 4, 0, 20, 1));
    private final Setting.Bool saveDurability = add(new Setting.Bool("Save durability", true));

    private int previous = -1, restoreIn;

    public AutoTool() { super("Auto Tool", Category.PLAYER); }

    @Override
    protected void onDisable() {
        if (previous >= 0 && switchBack.value && mc.player != null) {
            SwapLimit.selectNow(previous);
        }
        previous = -1;
    }

    @Override
    public void onPreTick() {
        Player p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            previous = -1;
            return;
        }

        boolean blocked = mc.gui.screen() != null || p.isCreative() || p.isSpectator() || AutoEat.eating();
        BlockHitResult hit = null;

        if (!blocked && mc.options.keyAttack.isDown()) {
            if (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
                hit = b;
            }
        }

        if (hit != null) {
            select(p, mc.level.getBlockState(hit.getBlockPos()), hit);
            restoreIn = backDelay.intValue();
        } else if (previous >= 0 && --restoreIn <= 0) {
            restore();
        }
    }

    private void select(Player p, BlockState st, BlockHitResult hit) {
        if (st.isAir() || st.getDestroySpeed(mc.level, hit.getBlockPos()) < 0) return; // unbreakable / air
        Inventory inv = p.getInventory();
        int cur = inv.getSelectedSlot(), best = cur;
        float bestSpeed = speed(inv.getItem(cur), st);

        for (int i = 0; i < 9; i++) {
            if (i == cur) continue;
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (saveDurability.value && s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 4)
                continue;

            float sp = speed(s, st);
            if (sp > bestSpeed) {
                best = i;
                bestSpeed = sp;
            }
        }

        if (best != cur) {
            if (previous < 0) previous = cur;
            SwapLimit.select(best);
        }
    }

    private void restore() {
        if (previous >= 0 && switchBack.value && mc.player != null) {
            SwapLimit.select(previous);
        }
        previous = -1;
    }

    private float speed(ItemStack stack, BlockState state) {
        if (stack.isEmpty()) return 1.0f;
        return stack.getDestroySpeed(state);
    }
}
