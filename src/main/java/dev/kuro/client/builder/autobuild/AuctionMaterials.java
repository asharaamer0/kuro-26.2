package dev.kuro.client.builder.autobuild;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** One opt-in search and one validated purchase click; never retries an uncertain transaction. */
final class AuctionMaterials {
    private Item item;
    private int ticks, before, menuId;
    private boolean clicked;
    private String listingTitle;
    private boolean nextPageRequested;
    private int pageWait;
    private int actionDelay;
    private int confirmDelay;
    private java.util.List<ItemStack> previousPage;
    private boolean submitted, confirmed;
    private net.minecraft.client.gui.screens.Screen confirmationScreen;
    private final DeliverySettlement delivery = new DeliverySettlement();

    /** A transient count increase or a still-open server menu cannot resume construction. */
    static final class DeliverySettlement {
        private int lastCount=-1, stableTicks;
        boolean ready(int count,int before,boolean menuClosed) {
            if (count<=before || !menuClosed) { lastCount=count; stableTicks=0; return false; }
            stableTicks=count==lastCount ? stableTicks+1 : 1;
            lastCount=count;
            return stableTicks>=10;
        }
        void reset() { lastCount=-1; stableTicks=0; }
    }
    private String reason = "";
    private boolean success;

    boolean active() { return item != null; }
    String reason() { return reason; }
    boolean success() { return success; }
    void cancel() {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            if (client.gui.screen() != null && (client.player.containerMenu != client.player.inventoryMenu || client.gui.screen() == confirmationScreen)) {
                client.player.closeContainer();
                client.gui.setScreen(null);
            }
        }
        item=null; success=false; actionDelay=0; confirmDelay=0;
    }

    static String command(Item item, boolean stackOnly) {
        String name = BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
        return "ah " + name + (stackOnly ? " stack" : "");
    }

    static boolean validListing(ItemStack stack, Item item, boolean stackOnly) {
        return stack.is(item) && (stackOnly ? stack.getCount() == stack.getMaxStackSize() : stack.getCount() > 0);
    }

    /** Five listing rows only; row six contains navigation/filter controls, not offers. */
    static int findListing(java.util.function.IntFunction<ItemStack> slots, Item item, boolean stackOnly) {
        int bestSlot = -1;
        int bestCount = 0;
        for (int slot = 0; slot < 45; slot++) {
            ItemStack stack = slots.apply(slot);
            if (validListing(stack, item, stackOnly)) {
                if (stack.getCount() == stack.getMaxStackSize()) return slot;
                if (stack.getCount() > bestCount) {
                    bestCount = stack.getCount();
                    bestSlot = slot;
                }
            }
        }
        return bestSlot;
    }

    static boolean nextPageArrow(ItemStack stack) {
        return stack.is(net.minecraft.world.item.Items.ARROW);
    }

    private static java.util.List<ItemStack> pageContents(ChestMenu menu) {
        java.util.List<ItemStack> contents=new java.util.ArrayList<>();
        for (int slot=0;slot<54;slot++) contents.add(menu.getSlot(slot).getItem().copy());
        return contents;
    }

    private static boolean samePage(java.util.List<ItemStack> before,java.util.List<ItemStack> after) {
        for (int i=0;i<before.size();i++) if (!ItemStack.matches(before.get(i),after.get(i))) return false;
        return true;
    }

    static boolean purchasePrompt(String title) {
        return title.trim().equalsIgnoreCase("Are you sure you want to buy this?");
    }

    private static net.minecraft.client.gui.components.AbstractButton yesButton(
            net.minecraft.client.gui.components.events.ContainerEventHandler container) {
        for (var child : container.children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractButton button
                    && button.active && button.visible && button.getMessage().getString().trim().equalsIgnoreCase("Yes")) return button;
            if (child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler nested) {
                var button=yesButton(nested);
                if (button!=null) return button;
            }
        }
        return null;
    }

    private int count(Minecraft client) {
        int total=0;
        for (int i=0;i<36;i++) {
            ItemStack stack=client.player.getInventory().getItem(i);
            if (stack.is(item)) total+=stack.getCount();
        }
        return total;
    }

    void start(Minecraft client, Item requested) {
        cancel();
        item=requested; ticks=0; clicked=false; menuId=-1; listingTitle=null;
        before=count(client);
        submitted=false; confirmed=false;
        nextPageRequested=false; pageWait=0; actionDelay=0; confirmDelay=0; previousPage=null;
        confirmationScreen=null; delivery.reset();

        // Close any existing screen first
        if (client.gui.screen() != null && client.player != null) {
            client.player.closeContainer();
            client.gui.setScreen(null);
        }

        reason="Searching auction for " + item.getDefaultInstance().getHoverName().getString() + "...";
        AutoBuilderController.log("Auction: Searching for " + item.getDefaultInstance().getHoverName().getString() + "...");
    }

    private void fail(String message) {
        cancel();
        reason=message; success=false;
        AutoBuilderController.log("Auction: " + message);
    }

    void tick(Minecraft client) {
        if (!active()) return;
        if (client.player==null || client.level==null || client.gameMode==null) { fail("Auction stopped: disconnected."); return; }
        if (++ticks>240) { fail("Auction timed out. Check the purchase manually; no automatic retry was sent."); return; }

        if (!submitted) {
            if (ticks < 3) return;
            submitted = true;
            boolean stackOnly = AutoBuilderController.get().isAuctionStackOnly();
            String cmd = command(item, stackOnly);
            if (client.getConnection() != null) {
                client.getConnection().sendCommand(cmd);
            }
            reason="Auction search submitted (" + cmd + "). Waiting for listings...";
            return;
        }

        if (clicked) {
            if (!confirmed && client.gui.screen()!=null && purchasePrompt(client.gui.screen().getTitle().getString())) {
                var button=yesButton(client.gui.screen());
                if (button!=null) {
                    if (++confirmDelay<6) {
                        reason="Confirming auction purchase...";
                        return;
                    }
                    // Mark before invoking the callback: a delayed screen update must never buy twice.
                    confirmed=true;
                    confirmationScreen=client.gui.screen();
                    button.onPress(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,0,0));
                    reason="Auction purchase confirmed. Waiting for material delivery...";
                    AutoBuilderController.log("Auction: Confirmed purchase for " + item.getDefaultInstance().getHoverName().getString() + ".");
                    return;
                }
            }
            int received=count(client);
            if (received>before) {
                // The server may return to listings after Yes. Close only this purchase's screens,
                // then keep the errand active while the inventory and menu finish settling.
                boolean returnedToAuction=listingTitle!=null && client.gui.screen()!=null
                        && client.player.containerMenu instanceof ChestMenu returned && returned.getRowCount()==6
                        && listingTitle.equals(client.gui.screen().getTitle().getString());
                if (client.gui.screen()!=null && (client.player.containerMenu.containerId==menuId
                        || client.gui.screen()==confirmationScreen || returnedToAuction)) {
                    // Use the screen's normal Escape/close handler too: purchase dialogs can be
                    // non-container screens, and returned auction menus may have a fresh menu ID.
                    var ownedScreen=client.gui.screen();
                    ownedScreen.onClose();
                    if (client.gui.screen()==ownedScreen) client.player.closeContainer();
                }
                reason="Material delivered. Waiting for inventory and auction menu to settle...";
            }
            if (delivery.ready(received,before,client.gui.screen()==null
                    && client.player.containerMenu==client.player.inventoryMenu)) {
                int brought=received-before;
                item=null; success=true; reason="Auction material received and inventory settled.";
                AutoBuilderController.log("Auction: Received " + brought + "x items.");
            }
            return;
        }

        if (client.player.containerMenu==client.player.inventoryMenu) return;
        if (!(client.player.containerMenu instanceof ChestMenu menu) || menu.getRowCount()!=6) {
            fail("Auction opened an unexpected menu. No purchase was clicked."); return;
        }

        var contents=pageContents(menu);
        if (nextPageRequested && previousPage!=null) {
            if (samePage(previousPage,contents)) {
                reason="Waiting for auction page two to arrive...";
                return;
            }
            previousPage=null;
            pageWait=0;
            actionDelay=0;
        }
        // Let split container updates settle before searching or clicking the page arrow.
        if (++pageWait<12) return;
        boolean stackOnly = AutoBuilderController.get().isAuctionStackOnly();
        int listingSlot=findListing(slot -> menu.getSlot(slot).getItem(),item,stackOnly);
        if (listingSlot<0) {
            if (!nextPageRequested && nextPageArrow(menu.getSlot(53).getItem())) {
                if (++actionDelay<5) {
                    reason="Opening auction page two...";
                    return;
                }
                previousPage=contents;
                nextPageRequested=true;
                pageWait=0;
                actionDelay=0;
                client.gameMode.handleContainerInput(menu.containerId,53,0,ContainerInput.PICKUP,client.player);
                reason="No matching stack on page one. Opening auction page two...";
                AutoBuilderController.log("Auction: Navigating to page two...");
                return;
            }
            reason="No matching full stack on auction " + (nextPageRequested ? "page two" : "page one")
                    + ". Waiting for listings until search timeout...";
            return;
        }
        if (++actionDelay<5) {
            reason="Selecting " + item.getDefaultInstance().getHoverName().getString() + " listing...";
            return;
        }
        menuId=menu.containerId;
        listingTitle=client.gui.screen()==null ? null : client.gui.screen().getTitle().getString();
        clicked=true; ticks=0; actionDelay=0; confirmDelay=0;
        client.gameMode.handleContainerInput(menuId,listingSlot,0,ContainerInput.PICKUP,client.player);
        reason="Auction slot " + listingSlot + " selected for " + item.getDefaultInstance().getHoverName().getString()
                + ". Waiting for purchase confirmation or material delivery...";
        AutoBuilderController.log("Auction: Selected listing in slot " + listingSlot + " for " + item.getDefaultInstance().getHoverName().getString() + ".");
    }
}
