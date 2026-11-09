package dev.kuro.client.module.impl;

import dev.kuro.client.mixin.InteractionManagerInvoker;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.SwapLimit;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Raze-style Auto Totem adapted to Kuro's settings, inventory and module APIs. */
public class AutoTotem extends Module implements HudElement {
    private static final int OFFHAND_SWAP_BUTTON = 40;

    private final Setting.Bool inventoryOnly = add(new Setting.Bool("Inventory only", false));
    private final Setting.Bool randomSlot = add(new Setting.Bool("Random slot", true));
    private final Setting.Bool hotbar = add(new Setting.Bool("Hotbar", true));
    private final Setting.Num hotbarSlot = add(new Setting.Num("Hotbar slot", 1, 1, 9, 1));
    private final Setting.Bool autoSwitch = add(new Setting.Bool("Auto switch", true));
    private final Setting.Num delayMin = add(new Setting.Num("Delay min (ms)", 50, 0, 1000, 1));
    private final Setting.Num delayMax = add(new Setting.Num("Delay max (ms)", 200, 0, 1000, 1));
    private final Setting.Bool extraRandomization = add(new Setting.Bool("Extra randomization", true));
    private final Setting.Bool showTotemCount = add(new Setting.Bool("Show totem count", false));
    private final Setting.Bool openInventory = add(new Setting.Bool("Open inventory", true));
    private final Setting.Bool silentOpen = add(new Setting.Bool("Silent open", false));
    private final Setting.Bool autoClose = add(new Setting.Bool("Auto close", false));
    private final Setting.Bool replaceOffhand = add(new Setting.Bool("Replace offhand item", true));
    private final Setting.Num hudX = addSaved(new Setting.Num("HUD X", 8, 0, 4000, 1));
    private final Setting.Num hudY = addSaved(new Setting.Num("HUD Y", 8, 0, 4000, 1));

    private long nextSwapAt;
    private boolean forcedOpen;
    private boolean wasInventoryOpen;
    private int pendingSwitchSlot = -1;

    public AutoTotem() { super("Auto Totem", Category.PLAYER); }

    @Override protected void onEnable() { reset(); }
    @Override protected void onDisable() { reset(); }

    private void reset() {
        nextSwapAt = 0L;
        forcedOpen = false;
        wasInventoryOpen = false;
        pendingSwitchSlot = -1;
    }

    @Override
    public void onTick() {
        Player player = mc.player;
        if (player == null || mc.gameMode == null || player.isSpectator()) return;
        boolean inventoryScreenOpen = mc.gui.screen() instanceof InventoryScreen;
        if (mc.gui.screen() != null && !inventoryScreenOpen) return; // never interact with another container or screen

        if (!inventoryScreenOpen) {
            applyPendingSwitch();
            forcedOpen = false;
        }
        wasInventoryOpen = inventoryScreenOpen;

        int targetHotbar = hotbarSlot.intValue() - 1;
        handleTotem(player, targetHotbar, inventoryScreenOpen);
        if (inventoryScreenOpen) maybeAutoClose(player, targetHotbar);
    }

    private void handleTotem(Player player, int targetHotbar, boolean inventoryScreenOpen) {
        if (System.currentTimeMillis() < nextSwapAt) return;
        boolean needOffhand = !player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                && (replaceOffhand.value || player.getOffhandItem().isEmpty());
        boolean needHotbar = hotbar.value
                && !player.getInventory().getItem(targetHotbar).is(Items.TOTEM_OF_UNDYING);
        if (!needOffhand && !needHotbar) return;
        if (inventoryOnly.value && !inventoryScreenOpen) return;

        if (!inventoryScreenOpen && openInventory.value && !silentOpen.value) {
            if (mc.gui.screen() != null) return;
            forcedOpen = true;
            mc.gui.setScreen(new InventoryScreen(player));
            return;
        }
        if (!inventoryScreenOpen && !silentOpen.value) return;
        if (player.containerMenu != player.inventoryMenu) return;

        if (autoSwitch.value && hotbar.value && needHotbar) pendingSwitchSlot = targetHotbar;
        int source = findTotemSlot(player.getInventory(), needOffhand ? -1 : targetHotbar);
        if (source < 0 || !SwapLimit.take()) return;

        int menuSlot = source < 9 ? source + 36 : source;
        int button = needOffhand ? OFFHAND_SWAP_BUTTON : targetHotbar;
        mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, button, ContainerInput.SWAP, player);
        long low = (long) Math.min(delayMin.value, delayMax.value);
        long high = (long) Math.max(delayMin.value, delayMax.value);
        long wait = low + (high > low ? (long) (Math.random() * (high - low + 1)) : 0L);
        if (extraRandomization.value) wait += (long) (Math.random() * 180.0);
        nextSwapAt = System.currentTimeMillis() + wait;
    }

    private void maybeAutoClose(Player player, int targetHotbar) {
        if (!autoClose.value || inventoryOnly.value || !forcedOpen) return;
        boolean needOffhand = !player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                && (replaceOffhand.value || player.getOffhandItem().isEmpty());
        boolean needHotbar = hotbar.value && !player.getInventory().getItem(targetHotbar).is(Items.TOTEM_OF_UNDYING);
        if (needOffhand || needHotbar) {
            if (findTotemSlot(player.getInventory(), needOffhand ? -1 : targetHotbar) >= 0) return;
        }
        forcedOpen = false;
        mc.gui.setScreen(null);
    }

    private void applyPendingSwitch() {
        if (pendingSwitchSlot < 0 || mc.player == null) return;
        int slot = pendingSwitchSlot;
        pendingSwitchSlot = -1;
        if (SwapLimit.select(slot) && mc.gameMode != null) {
            ((InteractionManagerInvoker) mc.gameMode).kuro$syncSelectedSlot();
        } else {
            pendingSwitchSlot = slot; // retry if another module used this tick's swap allowance
        }
    }

    private int findTotemSlot(Inventory inventory, int exclude) {
        int count = 0;
        int[] candidates = new int[36];
        for (int slot = 9; slot < 36; slot++) {
            if (slot != exclude && inventory.getItem(slot).is(Items.TOTEM_OF_UNDYING)) candidates[count++] = slot;
        }
        if (count == 0) {
            for (int slot = 0; slot < 9; slot++) {
                if (slot != exclude && inventory.getItem(slot).is(Items.TOTEM_OF_UNDYING)) candidates[count++] = slot;
            }
        }
        if (count == 0) return -1;
        return candidates[randomSlot.value ? (int) (Math.random() * count) : 0];
    }

    @Override public Setting.Num hudX() { return hudX; }
    @Override public Setting.Num hudY() { return hudY; }
    @Override public int hudW() { return 90; }
    @Override public int hudH() { return 14; }
    @Override public String hudName() { return "Totem Count"; }

    @Override
    public void onHud(GuiGraphicsExtractor graphics) {
        if (!showTotemCount.value || mc.player == null) return;
        int total = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) ? mc.player.getOffhandItem().getCount() : 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) total += stack.getCount();
        }
        int x = HudElement.clampX(hudX, 90), y = HudElement.clampY(hudY, 14);
        graphics.text(mc.font, "Totems: " + total, x, y, 0xFFFFFFFF, true);
    }
}
