package dev.kuro.client.builder.autobuild;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Human-paced, auto-retrying, fail-safe cursor transfer.
 * Never leaves the inventory screen stuck open and automatically retries on latency/desync.
 */
final class InventoryMaterialTransfer {
    private InventoryScreen screen;
    private int source, destination, phase;
    private Item requestedItem;
    private String failure;
    private int delay;
    private int waitTicks;
    private int phaseRetries;

    boolean active() { return screen != null; }
    String failure() { return failure; }

    void begin(Minecraft client, int slot) {
        failure = null;
        if (active()) return;
        if (client.player == null || client.gameMode == null) return;

        // If another non-inventory screen is open, report it
        if (client.gui.screen() != null && !(client.gui.screen() instanceof InventoryScreen)) {
            failure = "Close the menu and clear the inventory cursor before moving materials.";
            return;
        }

        source = slot;
        destination = 36 + client.player.getInventory().getSelectedSlot();
        phase = 0;
        waitTicks = 0;
        phaseRetries = 0;

        // If the cursor is carrying something before starting, safely put it away
        if (!client.player.inventoryMenu.getCarried().isEmpty()) {
            client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, source, 0, ContainerInput.PICKUP, client.player);
        }

        ItemStack stack = client.player.inventoryMenu.getSlot(source).getItem();
        if (stack.isEmpty()) {
            // Find the item if it shifted
            int found = -1;
            for (int i = 9; i < 36; i++) {
                if (!client.player.inventoryMenu.getSlot(i).getItem().isEmpty()) {
                    found = i;
                    break;
                }
            }
            if (found >= 0) {
                source = found;
                stack = client.player.inventoryMenu.getSlot(source).getItem();
            } else {
                failure = "Target inventory slot is empty.";
                return;
            }
        }
        requestedItem = stack.getItem();

        if (client.gui.screen() instanceof InventoryScreen inv) {
            screen = inv;
        } else {
            screen = new InventoryScreen(client.player);
            client.gui.setScreen(screen);
        }
        delay = 4;
        AutoBuilderController.log("Moving " + requestedItem.getDefaultInstance().getHoverName().getString() + " to hotbar.");
    }

    void cancel() {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            closeScreen(client);
        }
        screen = null;
        delay = 0;
        waitTicks = 0;
        phaseRetries = 0;
    }

    private void closeScreen(Minecraft client) {
        if (client.player != null) {
            // If carried stack exists, return it to source
            if (!client.player.inventoryMenu.getCarried().isEmpty() && source >= 0) {
                client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, source, 0, ContainerInput.PICKUP, client.player);
            }
            if (client.gui.screen() == screen || client.gui.screen() instanceof InventoryScreen) {
                if (client.player.containerMenu != client.player.inventoryMenu) {
                    client.player.closeContainer();
                }
                client.gui.setScreen(null);
            }
        }
    }

    void tick(Minecraft client) {
        if (!active()) return;
        if (client.player == null || client.gameMode == null) {
            failure = "Inventory transfer interrupted: disconnected.";
            cancel();
            return;
        }
        if (client.gui.screen() != screen && !(client.gui.screen() instanceof InventoryScreen)) {
            failure = "Inventory transfer interrupted: menu changed.";
            cancel();
            return;
        }
        if (client.player.containerMenu != client.player.inventoryMenu) {
            failure = "Inventory transfer interrupted: container open.";
            cancel();
            return;
        }

        if (delay > 0) { delay--; return; }

        var menu = client.player.inventoryMenu;

        if (phase == 0) {
            // Step 1: Pick up requested item from source
            ItemStack currentStack = menu.getSlot(source).getItem();
            if (!currentStack.is(requestedItem)) {
                // If the item moved from this slot, search main inventory (slots 9-35)
                int newSlot = -1;
                for (int i = 9; i < 36; i++) {
                    if (menu.getSlot(i).getItem().is(requestedItem)) {
                        newSlot = i;
                        break;
                    }
                }
                if (newSlot >= 0) {
                    source = newSlot;
                    currentStack = menu.getSlot(source).getItem();
                } else {
                    failure = "Material not found in inventory.";
                    cancel();
                    return;
                }
            }

            if (!menu.getCarried().isEmpty()) {
                if (menu.getCarried().is(requestedItem)) {
                    phase = 1;
                    delay = 3;
                    waitTicks = 0;
                    return;
                } else {
                    // Put down whatever is carried back into an empty slot
                    client.gameMode.handleContainerInput(menu.containerId, source, 0, ContainerInput.PICKUP, client.player);
                    delay = 3;
                    return;
                }
            }

            // Click source to pick up
            client.gameMode.handleContainerInput(menu.containerId, source, 0, ContainerInput.PICKUP, client.player);
            phase = 1;
            delay = 3;
            waitTicks = 0;
            return;

        } else if (phase == 1) {
            // Step 2: Waiting for item on cursor, then clicking destination hotbar slot
            if (!menu.getCarried().is(requestedItem)) {
                // Waiting for cursor to update from server packet
                if (++waitTicks < 8) {
                    delay = 1;
                    return;
                }
                // Timed out waiting for cursor; retry pickup if retries remain
                if (phaseRetries++ < 3) {
                    phase = 0;
                    waitTicks = 0;
                    delay = 3;
                    return;
                }
                failure = "Timed out picking up material.";
                cancel();
                return;
            }

            // Item is on cursor; click destination slot
            client.gameMode.handleContainerInput(menu.containerId, destination, 0, ContainerInput.PICKUP, client.player);
            phase = 2;
            delay = 3;
            waitTicks = 0;
            return;

        } else if (phase == 2) {
            // Step 3: Confirming deposit into destination hotbar slot
            if (!menu.getCarried().isEmpty()) {
                if (++waitTicks < 8) {
                    delay = 1;
                    return;
                }
                // If destination slot already received the item, return what was swapped back to source
                if (menu.getSlot(destination).getItem().is(requestedItem)) {
                    client.gameMode.handleContainerInput(menu.containerId, source, 0, ContainerInput.PICKUP, client.player);
                    phase = 3;
                    delay = 3;
                    waitTicks = 0;
                    return;
                }
                if (phaseRetries++ < 3) {
                    client.gameMode.handleContainerInput(menu.containerId, destination, 0, ContainerInput.PICKUP, client.player);
                    delay = 3;
                    waitTicks = 0;
                    return;
                }
                failure = "Timed out placing material in hotbar.";
                cancel();
                return;
            }

            // Cursor is empty! Verify hotbar slot has the item
            if (menu.getSlot(destination).getItem().is(requestedItem)) {
                // Success! Close screen cleanly
                closeScreen(client);
                screen = null;
                delay = 0;
                return;
            } else {
                if (++waitTicks < 8) {
                    delay = 1;
                    return;
                }
                if (phaseRetries++ < 3) {
                    phase = 0;
                    delay = 3;
                    waitTicks = 0;
                    return;
                }
                failure = "Hotbar slot did not receive material.";
                cancel();
                return;
            }

        } else {
            // Step 4: Finished returning swapped item, clean up and close screen
            if (!menu.getCarried().isEmpty()) {
                if (++waitTicks < 8) {
                    delay = 1;
                    return;
                }
                for (int i = 9; i < 36; i++) {
                    if (menu.getSlot(i).getItem().isEmpty()) {
                        client.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.PICKUP, client.player);
                        break;
                    }
                }
            }
            closeScreen(client);
            screen = null;
            delay = 0;
        }
    }
}
