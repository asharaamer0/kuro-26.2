package dev.kuro.client.echo.utils.inventory;

import dev.kuro.client.echo.handlers.InputHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.function.Predicate;

import static dev.kuro.client.echo.Imports.mc;

/** The hotbar helpers of hack.echo.client.utils.inventory.InventoryUtils. */
public final class InventoryUtils {
    private InventoryUtils() {}

    public static void setInvSlot(int slot) {
        if (mc.player == null) return;
        if (mc.player.getInventory().getSelectedSlot() == slot) return;
        mc.player.getInventory().setSelectedSlot(slot);
    }

    public static void setInvSlot(int slot, boolean inputSimulation) {
        if (slot < 0 || slot > 8 || mc.player == null) return;
        if (mc.player.getInventory().getSelectedSlot() == slot) return;

        if (inputSimulation) {
            if (mc.options != null && mc.options.keyHotbarSlots != null) {
                KeyMapping hotbarKey = mc.options.keyHotbarSlots[slot];
                if (hotbarKey != null) InputHandler.incrementClickCountForKeyMapping(hotbarKey);
            }
        } else {
            setInvSlot(slot);
        }
    }

    public static int findItemWithPredicateInHotbar(Predicate<ItemStack> predicate) {
        if (mc.player == null) return -1;
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i <= 8; i++) {
            if (predicate.test(inventory.getItem(i))) return i;
        }
        return -1;
    }

    public static int findItemWithEnchantmentInHotbar(Predicate<ItemStack> predicate, ResourceKey<Enchantment> enchantKey, boolean allowNoEnch) {
        if (mc.player == null) return -1;
        Inventory inventory = mc.player.getInventory();

        if (enchantKey != null) {
            for (int i = 0; i <= 8; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack != null && !stack.isEmpty() && predicate.test(stack)) {
                    for (Holder<Enchantment> e : stack.getEnchantments().keySet()) {
                        if (e.is(enchantKey)) return i;
                    }
                }
            }
        }

        if (allowNoEnch) {
            for (int i = 0; i <= 8; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack != null && !stack.isEmpty() && predicate.test(stack)) {
                    return i;
                }
            }
        }

        return -1;
    }

    public static int findMaceWithEnchantmentInHotbar(ResourceKey<Enchantment> enchantKey, boolean allowNoEnch) {
        return findItemWithEnchantmentInHotbar(stack -> stack.getItem() instanceof MaceItem || stack.getItem() == Items.MACE, enchantKey, allowNoEnch);
    }

    public static int findChestplateSlot() {
        if (mc.player == null) return -1;
        final Inventory inv = mc.player.getInventory();
        for (int i = 0; i <= 8; i++) {
            Item item = inv.getItem(i).getItem();
            if (item == Items.NETHERITE_CHESTPLATE ||
                item == Items.CHAINMAIL_CHESTPLATE ||
                item == Items.IRON_CHESTPLATE ||
                item == Items.GOLDEN_CHESTPLATE ||
                item == Items.DIAMOND_CHESTPLATE ||
                item == Items.LEATHER_CHESTPLATE) {
                return i;
            }
        }
        return -1;
    }
}
