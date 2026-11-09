package dev.kuro.client.builder.autobuild;

import net.minecraft.world.item.ItemStack;

/** Require a selected stack to remain unchanged across client ticks before interacting. */
final class HeldItemStability {
    private ItemStack previous = ItemStack.EMPTY;
    private int slot = -1;
    private int changedTick = Integer.MIN_VALUE;

    boolean ready(int tick, int selected, ItemStack stack) {
        if (slot != selected || !ItemStack.matches(previous, stack)) {
            slot = selected;
            previous = stack.copy();
            changedTick = tick;
        }
        return tick > changedTick;
    }
}
