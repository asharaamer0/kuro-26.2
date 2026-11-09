package dev.kuro.client.builder.autobuild;

import dev.kuro.client.builder.freecam.FreecamController;
import dev.kuro.client.builder.mixin.BuilderLocalPlayerAccessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Gathers the schematic materials the player still lacks out of nearby storage containers.
 *
 * <p>A chosen container is checked first, and when none is chosen the containers around the player are
 * searched instead, so a build that runs out of an item asks the storage rather than stopping. Chests,
 * barrels and shulker boxes all open the same container menu and are handled the same way.
 *
 * <p>Every step is an ordinary vanilla interaction: walk into reach, aim, open the container, quick-move
 * the needed stacks, close it and continue with the next one. The routine never modifies the world, never
 * sends an interaction a player could not send by hand, and is bounded in tiles, ticks, containers and
 * clicks so that it always settles.
 *
 * <p>Client-side prediction is deliberately not trusted: a quick-move only counts once the player's own
 * inventory really gained the items, so a rejected or ignored click retires its slot instead of counting
 * progress that never arrived.
 */
public final class MaterialRestock {
    /** Search box around the player at the moment collection starts. */
    private static final int SEARCH_RADIUS = 20, SEARCH_VERTICAL = 10;
    /**
     * How far the chosen material container may stand away. The walking search is bounded to about this
     * range, so a container beyond it is reported instead of being walked at until the walk gives up.
     */
    static final int MATERIAL_RANGE = 96;
    private static final int SCAN_BUDGET = 8192, MAX_CONTAINERS = 32;
    private static final int WALK_LIMIT = 400, AIM_LIMIT = 6, OPEN_LIMIT = 40, MOVE_LIMIT = 20, MENU_SETTLE = 6;
    // Pacing between quick-moves: spaced out to avoid dropping packets or triggering anti-cheat limits.
    private static final int CLICK_LIMIT = 400, CLICK_COOLDOWN = 4, SLOT_LIMIT = 64;

    private enum Stage { SCAN, APPROACH, AIM, OPEN, MOVE, CLOSE, FINISHED }

    private final BuilderNavigator navigator;
    private final Map<Item, Integer> outstanding = new LinkedHashMap<>();
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private final Set<Integer> exhausted = new HashSet<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private Stage stage = Stage.FINISHED;
    private ClientLevel session;
    private MaterialSearch search;
    /** The container the player chose as the material source, checked before the nearby scan. */
    private MaterialStorage designated;
    private BlockPos origin, current;
    private Item pending;
    private int pendingHeld, pendingWaited, pendingSlot = -1, cooldown, clicks, moved, containersUsed;
    private int total, stageTicks, walkTicks, openWaits, menuTicks;
    private String reason = "";

    public MaterialRestock(BuilderNavigator navigator) { this.navigator = navigator; }

    public boolean active() { return stage != Stage.FINISHED; }
    public String reason() { return reason; }
    /** Items really moved into the inventory during this run, each one confirmed against the inventory. */
    public int moved() { return moved; }
    public double progress() { return total == 0 ? 1 : Math.clamp(1 - (double) needed() / total, 0, 1); }

    /** Starts a run for the items the schematic still lacks after counting the current inventory. */
    public void start(Minecraft client, Schematic schematic, PlacementTransform transform) {
        start(client, schematic, transform, null);
    }

    /**
     * Starts a run for the items the schematic still lacks. A chosen material container is checked first and
     * the containers around the player stay the fallback for whatever it does not hold, so a build with no
     * choice made still checks the storage it is standing among before giving up.
     */
    public void start(Minecraft client, Schematic schematic, PlacementTransform transform, MaterialStorage materialStorage) {
        cancel();
        for (MaterialTracker.Row row : MaterialTracker.snapshot(client, schematic, transform).rows()) {
            if (row.item() != Items.AIR && row.missing() > 0) outstanding.merge(row.item(), row.missing(), Integer::sum);
        }
        total = needed();
        // These belong to one run: a collection that never reset them stopped moving stacks once earlier
        // runs had used the budget up, and its report counted containers from every run before it.
        moved = 0; clicks = 0; containersUsed = 0;
        if (client.level == null || client.player == null || client.gameMode == null) {
            finish("Join a world before collecting materials.");
            return;
        }
        if (outstanding.isEmpty()) { finish("Materials are already in stock."); return; }
        if (!materialStorageReachable(client, materialStorage)) return;
        session = client.level;
        BlockPos feet = client.player.blockPosition();
        origin = feet.immutable();
        if (designated != null) queue.add(designated.pos().immutable());
        search = new MaterialSearch(feet.getX(), feet.getY(), feet.getZ(), SEARCH_RADIUS, SEARCH_VERTICAL);
        stage = Stage.SCAN;
        reason = designated == null ? "Searching for nearby material storage..."
                : "Walking to the material storage at " + coordinates(designated.pos()) + ".";
        AutoBuilderController.log("Restocking: Gathering missing materials (" + total + " items needed)...");
    }

    /**
     * Judges the chosen material container before anything is walked to, so one that is gone, unloaded or
     * plainly out of range is reported instead of turning into a wander. It becomes the first candidate.
     */
    private boolean materialStorageReachable(Minecraft client, MaterialStorage materialStorage) {
        designated = null;
        if (materialStorage == null) return true;
        BlockPos pos = materialStorage.pos();
        if (!materialStorage.inDimension(client.level.dimension().identifier().toString())) {
            finish("The material storage at " + coordinates(pos) + " is in another dimension.");
            return false;
        }
        if (!MaterialTracker.isLoaded(client.level, pos)) {
            finish("The material storage at " + coordinates(pos) + " is not loaded. Move closer, then collect.");
            return false;
        }
        if (!MaterialStorage.isStorage(client.level.getBlockState(pos))) {
            finish("The material storage at " + coordinates(pos) + " is no longer an openable container.");
            return false;
        }
        if (client.player.blockPosition().distSqr(pos) > (double) MATERIAL_RANGE * MATERIAL_RANGE) {
            finish("The material storage at " + coordinates(pos) + " is more than " + MATERIAL_RANGE
                    + " blocks away. Move closer, then collect.");
            return false;
        }
        designated = materialStorage;
        return true;
    }

    public void cancel() {
        // Ends an open container interaction so the next run starts from a clean inventory menu.
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && ownsScreen(client)) {
            if (client.player.containerMenu != client.player.inventoryMenu) client.player.closeContainer();
            client.gui.setScreen(null);
        }
        stage = Stage.FINISHED;
        queue.clear();
        outstanding.clear();
        navigator.reset();
        pending = null; pendingSlot = -1; current = null; session = null;
        search = null; origin = null; designated = null;
    }

    public void tick(Minecraft client) {
        if (!active()) return;
        LocalPlayer player = client.player;
        if (client.level == null || player == null || client.gameMode == null || client.level != session) {
            finish("Collecting stopped: the world changed.");
            return;
        }
        // Never fight the player for an open menu, and never walk while the game is paused.
        if (client.gui.screen() != null && !ownsScreen(client)) {
            hold("Collecting is waiting for the open menu to close.");
            return;
        }
        if (client.isPaused()) { hold("Collecting is waiting for the game menu to close."); return; }
        if (!player.isAlive() || player.isRemoved()) { hold("Collecting is waiting for the player."); return; }
        if (FreecamController.enabled()) { hold("Collecting is waiting for Freecam to be disabled."); return; }
        switch (stage) {
            case SCAN -> scan(client);
            case APPROACH -> approach(client);
            case AIM -> aim(client);
            case OPEN -> open(client);
            case MOVE -> move(client);
            case CLOSE -> close(client);
            default -> { }
        }
    }

    /** One budgeted pass over the loaded search disc; every hit is remembered. */
    private void scan(Minecraft client) {
        int budget = SCAN_BUDGET;
        while (budget-- > 0 && search.next()) {
            cursor.set(search.x(), search.y(), search.z());
            if (!MaterialTracker.isLoaded(session, cursor)) continue;
            if (MaterialStorage.isStorage(session.getBlockState(cursor)) && queue.size() < MAX_CONTAINERS)
                queue.add(cursor.immutable());
        }
        if (search.visited() >= search.size()) { sortCandidates(); return; }
        reason = "Searching for nearby material storage...";
    }

    private void sortCandidates() {
        // The chosen container is already a candidate, so an empty queue here really means the scan found none.
        if (queue.isEmpty()) {
            finish(designated == null ? "No material storage within " + SEARCH_RADIUS + " blocks."
                    : "The material storage at " + coordinates(designated.pos()) + " holds no more of the missing materials.");
            return;
        }
        List<BlockPos> ordered = order(designated == null ? null : designated.pos(), origin, queue);
        queue.clear();
        queue.addAll(ordered);
        nextStorage();
    }

    /**
     * The chosen container first, then the rest nearest the player: the container that was set is the one
     * expected to be checked, and the scan is only the fallback for what it does not hold.
     */
    static List<BlockPos> order(BlockPos designated, BlockPos origin, Collection<BlockPos> candidates) {
        List<BlockPos> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingDouble(origin::distSqr));
        if (designated != null) {
            ordered.remove(designated);
            ordered.add(0, designated.immutable());
        }
        return ordered;
    }

    /** Pops the next candidate, which may have been removed or replaced since the scan. */
    private void nextStorage() {
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            if (session == null || !MaterialStorage.isStorage(session.getBlockState(pos))) continue;
            current = pos.immutable();
            exhausted.clear();
            pending = null; pendingSlot = -1; pendingHeld = 0; pendingWaited = 0;
            walkTicks = 0; stageTicks = 0; openWaits = 0; menuTicks = 0;
            stage = Stage.APPROACH;
            reason = "Walking to the material storage at " + coordinates(current) + ".";
            AutoBuilderController.log("Restocking: Moving to container at " + coordinates(current) + ".");
            return;
        }
        finish(designated == null ? "No nearby material storage holds the missing materials."
                : "The material storage at " + coordinates(designated.pos()) + " holds no more of the missing materials.");
    }

    private void approach(Minecraft client) {
        BuilderNavigator.Result result = navigator.moveTo(client, current);
        if (result == BuilderNavigator.Result.ARRIVED) {
            navigator.hold(client, false);
            stage = Stage.AIM; stageTicks = 0;
            reason = "Opening the material storage at " + coordinates(current) + ".";
            return;
        }
        if (result == BuilderNavigator.Result.NO_PATH || result == BuilderNavigator.Result.STUCK) {
            skip(client, "No walking route to the material storage at " + coordinates(current) + ".");
            return;
        }
        if (++walkTicks > WALK_LIMIT) skip(client, "The material storage at " + coordinates(current) + " is out of reach.");
    }

    /**
     * Turns towards the container before interacting. The interaction carries its own hit result, so this
     * only keeps the transmitted rotation consistent with what is clicked.
     */
    private void aim(Minecraft client) {
        Vec3 delta = Vec3.atCenterOf(current).subtract(client.player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-delta.y, Math.hypot(delta.x, delta.z)));
        BuilderNavigator.rotateSmoothly(client.player, yaw, pitch, 40.0f);
        var sent = (BuilderLocalPlayerAccessor) client.player;
        boolean transmitted = Math.abs(Mth.wrapDegrees(sent.cli$lastSentYaw() - yaw)) <= .001f
                && Math.abs(sent.cli$lastSentPitch() - pitch) <= .001f;
        if (transmitted || ++stageTicks > AIM_LIMIT) { stage = Stage.OPEN; stageTicks = 0; }
    }

    private void open(Minecraft client) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(current), facing(client.player, current), current, false);
        InteractionResult result = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) client.player.swing(InteractionHand.MAIN_HAND);
        stage = Stage.MOVE; stageTicks = 0; cooldown = 5;
        reason = "Waiting for the material storage at " + coordinates(current) + " to open.";
        AutoBuilderController.log("Restocking: Opening container at " + coordinates(current) + ".");
    }

    private void move(Minecraft client) {
        LocalPlayer player = client.player;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu) {
            if (++openWaits > OPEN_LIMIT) skip(client, "The material storage at " + coordinates(current) + " could not be opened.");
            return;
        }
        menuTicks++;
        if (pending != null) {
            if (!settle(player)) return;
            reason = "Moving materials out of the material storage at " + coordinates(current) + ".";
        }
        if (outstanding.isEmpty()) { stage = Stage.CLOSE; cooldown = 3; return; }
        if (cooldown > 0) { cooldown--; return; }
        if (++clicks > CLICK_LIMIT) { skip(client, "Stopped moving materials after " + CLICK_LIMIT + " stacks."); return; }
        int slot = nextSlot(player, menu);
        if (slot < 0) {
            // A screen can appear a tick before its content packet, so an empty menu is re-read.
            if (menuTicks <= MENU_SETTLE && !holdsItems(player, menu)) return;
            stage = Stage.CLOSE;
            cooldown = 3;
            reason = "The material storage at " + coordinates(current) + " holds no other needed materials.";
            return;
        }
        ItemStack stack = menu.slots.get(slot).getItem();
        pending = stack.getItem();
        pendingSlot = slot;
        pendingHeld = player.getInventory().countItem(pending);
        pendingWaited = 0;
        client.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);
        cooldown = CLICK_COOLDOWN;
        reason = "Moving " + stack.getHoverName().getString() + " out of the material storage at " + coordinates(current) + ".";
    }

    /**
     * Confirms the last quick-move against the real inventory. A rejected or ignored click leaves the
     * count unchanged, which retires that slot instead of counting progress that never arrived.
     *
     * @return true once the pending move is either confirmed or abandoned
     */
    private boolean settle(LocalPlayer player) {
        int held = player.getInventory().countItem(pending);
        if (held > pendingHeld) {
            int gained = held - pendingHeld;
            int left = outstanding.getOrDefault(pending, 0) - gained;
            if (left > 0) outstanding.put(pending, left); else outstanding.remove(pending);
            moved += gained;
            AutoBuilderController.log("Restocking: Collected " + gained + "x " + pending.getDefaultInstance().getHoverName().getString() + ".");
            pending = null; pendingSlot = -1;
            return true;
        }
        if (++pendingWaited > MOVE_LIMIT) {
            if (pendingSlot >= 0 && exhausted.size() < SLOT_LIMIT) exhausted.add(pendingSlot);
            pending = null; pendingSlot = -1;
            return true;
        }
        return false;
    }

    /** True when the container itself holds anything; the player's own slots never count. */
    private static boolean holdsItems(LocalPlayer player, AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.container != player.getInventory() && !slot.getItem().isEmpty()) return true;
        }
        return false;
    }

    /** The container slot holding a still-needed item, preferring the largest outstanding need. */
    private int nextSlot(LocalPlayer player, AbstractContainerMenu menu) {
        int best = -1, bestNeed = 0;
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            if (slot.container == player.getInventory() || exhausted.contains(index)) continue;
            int need = outstanding.getOrDefault(slot.getItem().getItem(), 0);
            if (need > bestNeed) { best = index; bestNeed = need; }
        }
        return best;
    }

    private void close(Minecraft client) {
        if (cooldown > 0) { cooldown--; return; }
        if (client.player.containerMenu != client.player.inventoryMenu) client.player.closeContainer();
        if (client.gui.screen() instanceof AbstractContainerScreen<?>) client.gui.setScreen(null);
        containersUsed++;
        navigator.reset();
        if (outstanding.isEmpty()) {
            finish("Collected every missing material from " + containersUsed + " container"
                    + (containersUsed == 1 ? "" : "s") + ".");
            return;
        }
        nextStorage();
    }

    /** Abandons the current container, closing it first when an interaction already opened it. */
    private void skip(Minecraft client, String message) {
        AutoBuilderController.log("Restocking: Skipping container at " + coordinates(current) + " (" + message + ").");
        pending = null; pendingSlot = -1;
        if (client.player != null && client.player.containerMenu != client.player.inventoryMenu) {
            stage = Stage.CLOSE;
            reason = message;
            return;
        }
        if (client.gui.screen() != null && client.player != null && !ownsScreen(client)) {
            hold(message);
            return;
        }
        reason = message;
        nextStorage();
    }

    private void hold(String message) {
        navigator.release();
        reason = message;
    }

    private void finish(String message) {
        int missing = needed();
        stage = Stage.FINISHED;
        session = null;
        navigator.reset();
        pending = null; pendingSlot = -1; current = null;
        queue.clear();
        search = null; origin = null; designated = null;
        outstanding.clear();
        reason = missing > 0 && moved > 0
                ? message + " Still missing " + missing + " item" + (missing == 1 ? "" : "s") + "."
                : message;
        AutoBuilderController.log("Restocking: " + reason);
    }

    private int needed() {
        int sum = 0;
        for (int value : outstanding.values()) sum += Math.max(0, value);
        return sum;
    }

    /** True while the open screen is the container screen this routine opened. */
    private static boolean ownsScreen(Minecraft client) {
        return client.gui.screen() instanceof AbstractContainerScreen<?> screen && client.player != null
                && screen.getMenu() == client.player.containerMenu
                && client.player.containerMenu != client.player.inventoryMenu;
    }

    private static Direction facing(LocalPlayer player, BlockPos pos) {
        Vec3 delta = player.getEyePosition().subtract(Vec3.atCenterOf(pos));
        double x = Math.abs(delta.x), y = Math.abs(delta.y), z = Math.abs(delta.z);
        if (x >= y && x >= z) return delta.x >= 0 ? Direction.EAST : Direction.WEST;
        if (y >= z) return delta.y >= 0 ? Direction.UP : Direction.DOWN;
        return delta.z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static String coordinates(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
