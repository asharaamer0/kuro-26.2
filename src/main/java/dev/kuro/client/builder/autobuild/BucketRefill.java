package dev.kuro.client.builder.autobuild;

import dev.kuro.client.builder.freecam.FreecamController;
import dev.kuro.client.builder.mixin.BuilderLocalPlayerAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Fills the builder's empty buckets from water that is already in the world.
 *
 * <p>A pour in Survival leaves an empty bucket behind, so a build with more water cells than buckets would
 * stop halfway through. This routine walks to water the build does not need, aims at it and fills one
 * bucket per ordinary use, exactly as a player fills a bucket by hand: nothing is mined, no block is
 * placed, and only a source can be taken - vanilla's {@code LiquidBlock.pickupBlock} returns a bucket at
 * level zero alone, so flowing water cannot be bucketed at all and is never a candidate.
 *
 * <p>A source is never taken from a cell the file fills with water, nor from one beside such a cell: the
 * file's flowing levels are fed by the water around them. Among the cells that are left, ones with two
 * neighbouring sources are preferred, because vanilla turns that cell back into a source by itself, so the
 * hole a bucket leaves closes again instead of turning into a permanent dent in a pond.
 */
public final class BucketRefill {
    /** Search box around the player when a run starts, and how much of it is looked at per tick. */
    private static final int SEARCH_RADIUS = 16, SEARCH_VERTICAL = 8, SCAN_BUDGET = 8192, MAX_SOURCES = 32;
    /** Buckets filled per visit, walk, aim and confirmation limits, and the pause between interactions. */
    private static final int FILL_CAP = 16, WALK_LIMIT = 600, AIM_ATTEMPTS = 3, AIM_LIMIT = 5, FILL_WAIT = 20;
    private static final int HAND_TRIES = 6, SETTLE_TICKS = 2;

    private enum Stage { SCAN, APPROACH, AIM, FILL, CONFIRM, FINISHED }

    private final BuilderNavigator navigator;
    private final List<BlockPos> sources = new ArrayList<>();
    private Stage stage = Stage.FINISHED;
    private ClientLevel session;
    private MaterialSearch search;
    /** True for a cell the file fills with water, built or not; those cells and their neighbours are kept. */
    private Predicate<BlockPos> buildWater = pos -> false;
    private BlockPos target;
    private int wanted, filled, walkTicks, stageTicks, tries, handTries, waitTicks, heldBefore, cooldown;
    private String reason = "";

    public BucketRefill(BuilderNavigator navigator) { this.navigator = navigator; }

    public boolean active() { return stage != Stage.FINISHED; }
    public String reason() { return reason; }
    /** Buckets really filled during this run, each one confirmed by the inventory. */
    public int filled() { return filled; }
    public double progress() { return wanted == 0 ? 1 : Math.clamp((double) filled / wanted, 0, 1); }

    /**
     * Starts a run for as many buckets as the build still needs water for, limited to the empty buckets
     * that are actually in the inventory.
     */
    public void start(Minecraft client, Schematic schematic, PlacementTransform transform, Predicate<BlockPos> buildWater) {
        cancel();
        if (client.level == null || client.player == null || client.gameMode == null) {
            finish("Join a world before collecting water.");
            return;
        }
        int needed = 0;
        for (MaterialTracker.Row row : MaterialTracker.snapshot(client, schematic, transform).rows()) {
            if (row.item() == Items.WATER_BUCKET) needed = Math.max(needed, row.missing());
        }
        int empty = client.player.getInventory().countItem(Items.BUCKET);
        wanted = Math.min(FILL_CAP, Math.min(needed, empty));
        if (wanted <= 0) {
            finish(empty == 0 ? "No empty bucket is left to fill." : "This build needs no more water.");
            return;
        }
        session = client.level;
        this.buildWater = buildWater;
        stage = Stage.SCAN;
        reason = "Looking for water to fill a bucket from...";
        search = new MaterialSearch(client.player.blockPosition().getX(), client.player.blockPosition().getY(),
                client.player.blockPosition().getZ(), SEARCH_RADIUS, SEARCH_VERTICAL);
        AutoBuilderController.log("Refill: Finding water to fill empty buckets (" + wanted + " needed)...");
    }

    public void cancel() {
        stage = Stage.FINISHED;
        navigator.reset();
        sources.clear();
        search = null; session = null; target = null;
        wanted = 0; filled = 0; walkTicks = 0; stageTicks = 0; tries = 0; handTries = 0; waitTicks = 0; cooldown = 0;
        buildWater = pos -> false;
    }

    public void tick(Minecraft client) {
        if (!active()) return;
        LocalPlayer player = client.player;
        if (client.level == null || player == null || client.gameMode == null || client.level != session) {
            finish("Filling buckets stopped: the world changed.");
            return;
        }
        // Never fight the player for an open menu, and never walk while the game is paused.
        if (client.gui.screen() != null) { hold("Filling buckets is waiting for the open menu to close."); return; }
        if (client.isPaused()) { hold("Filling buckets is waiting for the game menu to close."); return; }
        if (!player.isAlive() || player.isRemoved()) { hold("Filling buckets is waiting for the player."); return; }
        if (player.isUsingItem()) { hold("Filling buckets is waiting for the held item to finish."); return; }
        if (FreecamController.enabled()) { hold("Filling buckets is waiting for Freecam to be disabled."); return; }
        if (cooldown > 0) { cooldown--; return; }
        switch (stage) {
            case SCAN -> scan(client);
            case APPROACH -> approach(client);
            case AIM -> aim(client);
            case FILL -> fill(client);
            case CONFIRM -> confirm(client);
            default -> { }
        }
    }

    /** One budgeted pass over the loaded search disc; every usable source is remembered. */
    private void scan(Minecraft client) {
        int budget = SCAN_BUDGET;
        while (budget-- > 0 && search.next()) {
            if (sources.size() >= MAX_SOURCES) continue;
            BlockPos pos = new BlockPos(search.x(), search.y(), search.z());
            if (fillable(client, pos)) sources.add(pos);
        }
        if (search.visited() < search.size()) { reason = "Looking for water to fill a bucket from..."; return; }
        chooseTarget(client);
    }

    /** Takes the best remaining source, or reports that the search found none the build can spare. */
    private void chooseTarget(Minecraft client) {
        if (sources.isEmpty()) {
            finish("No water source within " + SEARCH_RADIUS + " blocks that the build does not need. Place water "
                    + "nearby or bring full buckets, then Resume.");
            return;
        }
        target = best(client.player.blockPosition(), sources, at -> selfHealing(client, at));
        sources.remove(target);
        tries = 0; walkTicks = 0;
        navigator.reset();
        stage = Stage.APPROACH;
        reason = "Walking to the water at " + coordinates(target) + ".";
    }

    /** Gives up on one source without ending the run, so the next candidate can still be tried. */
    private void giveUp(Minecraft client, String message) {
        target = null;
        navigator.reset();
        if (sources.isEmpty()) { finish(message); return; }
        chooseTarget(client);
    }

    private void approach(Minecraft client) {
        BuilderNavigator.Result result = navigator.moveTo(client, target);
        if (result == BuilderNavigator.Result.ARRIVED) {
            navigator.hold(client, false);
            stage = Stage.AIM; stageTicks = 0;
            return;
        }
        if (result == BuilderNavigator.Result.NO_PATH || result == BuilderNavigator.Result.STUCK) {
            giveUp(client, "No walking route to the water at " + coordinates(target) + ".");
            return;
        }
        if (++walkTicks > WALK_LIMIT) giveUp(client, "The water at " + coordinates(target) + " is out of reach.");
        else reason = "Walking to the water at " + coordinates(target) + " / " + navigator.routeLength() + " steps";
    }

    /**
     * Turns towards the water and proves the fill would take from that cell. Vanilla fills through the
     * player's own view with {@code ClipContext.Fluid.SOURCE_ONLY}, so the water block itself is the hit; the
     * same trace is repeated here, and a cell that cannot be hit is left to another standing position.
     */
    private void aim(Minecraft client) {
        LocalPlayer player = client.player;
        if (!player.getMainHandItem().is(Items.BUCKET)) {
            if (handTries++ > HAND_TRIES) { finish("An empty bucket could not be held to fill."); return; }
            if (!selectEmptyBucket(client)) { finish("No empty bucket is left to fill."); return; }
            cooldown = SETTLE_TICKS;
            reason = "Taking an empty bucket in hand.";
            return;
        }
        handTries = 0;
        Vec3 eye = player.getEyePosition();
        Vec3 delta = Vec3.atCenterOf(target).subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-delta.y, Math.hypot(delta.x, delta.z)));
        BuilderNavigator.rotateSmoothly(player, yaw, pitch, 40.0f);
        Vec3 look = player.calculateViewVector(pitch, yaw);
        BlockHitResult hit = client.level.clip(new ClipContext(eye, eye.add(look.scale(player.blockInteractionRange())),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) {
            if (++tries > AIM_ATTEMPTS) { giveUp(client, "The water at " + coordinates(target) + " cannot be reached from here."); return; }
            navigator.rejectCurrent(client);
            stage = Stage.APPROACH; walkTicks = 0;
            reason = "Looking for a way to reach the water at " + coordinates(target) + ".";
            return;
        }
        var sent = (BuilderLocalPlayerAccessor) player;
        boolean transmitted = Math.abs(Mth.wrapDegrees(sent.cli$lastSentYaw() - yaw)) <= .001f
                && Math.abs(sent.cli$lastSentPitch() - pitch) <= .001f;
        if (transmitted || ++stageTicks > AIM_LIMIT) { stage = Stage.FILL; stageTicks = 0; }
        else reason = "Aiming at the water at " + coordinates(target) + ".";
    }

    /** One bucket per ordinary item use; the water block disappears and the server refills the slot. */
    private void fill(Minecraft client) {
        if (!client.player.getMainHandItem().is(Items.BUCKET)) { stage = Stage.AIM; return; }
        heldBefore = client.player.getInventory().countItem(Items.WATER_BUCKET);
        InteractionResult result = client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        if (result.consumesAction()) client.player.swing(InteractionHand.MAIN_HAND);
        waitTicks = 0;
        stage = Stage.CONFIRM;
        reason = "Filling a bucket from the water at " + coordinates(target) + ".";
    }

    /** The inventory is the only proof of a fill, so a use that changed nothing retires the run. */
    private void confirm(Minecraft client) {
        if (client.player.getInventory().countItem(Items.WATER_BUCKET) > heldBefore) {
            filled++;
            if (filled >= wanted) {
                finish("Filled " + filled + " bucket" + (filled == 1 ? "" : "s") + " from the water at "
                        + coordinates(target) + ".");
                return;
            }
            rescan(client);
            return;
        }
        if (++waitTicks > FILL_WAIT) {
            finish("The water at " + coordinates(target) + " could not be bucketed. Add full buckets, then Resume.");
        }
    }

    /** Looks again after every fill: the cell that was emptied may close by itself, and another may not. */
    private void rescan(Minecraft client) {
        sources.clear();
        BlockPos feet = client.player.blockPosition();
        search = new MaterialSearch(feet.getX(), feet.getY(), feet.getZ(), SEARCH_RADIUS, SEARCH_VERTICAL);
        cooldown = SETTLE_TICKS;
        stage = Stage.SCAN;
        reason = "Looking for water to fill the next bucket from...";
    }

    /** True for a water source the build can spare. Flowing water cannot be bucketed at all. */
    private boolean fillable(Minecraft client, BlockPos pos) {
        if (!MaterialTracker.isLoaded(client.level, pos)) return false;
        FluidState fluid = client.level.getFluidState(pos);
        if (!fluid.isSource() || !fluid.is(FluidTags.WATER)) return false;
        return mayTake(pos, buildWater);
    }

    /**
     * True when this cell may be emptied. A cell of the build is kept, and so is every cell beside one,
     * because the water there is what feeds the file's own water.
     */
    static boolean mayTake(BlockPos pos, Predicate<BlockPos> buildWater) {
        if (buildWater.test(pos)) return false;
        for (Direction face : Direction.values()) if (buildWater.test(pos.relative(face))) return false;
        return true;
    }

    /**
     * True when the water around this cell closes the hole again: vanilla turns a cell back into a source
     * once it has two neighbouring sources, which is what keeps an ordinary pond at its level.
     */
    private static boolean selfHealing(Minecraft client, BlockPos pos) {
        int found = 0;
        for (Direction face : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = pos.relative(face);
            if (!MaterialTracker.isLoaded(client.level, neighbour)) continue;
            FluidState fluid = client.level.getFluidState(neighbour);
            if (fluid.isSource() && fluid.is(FluidTags.WATER)) found++;
        }
        return found >= 2;
    }

    /** The nearest source the water around it will replace, or the nearest one at all. */
    static BlockPos best(BlockPos origin, Collection<BlockPos> sources, Predicate<BlockPos> selfHealing) {
        List<BlockPos> healing = sources.stream().filter(selfHealing).toList();
        Collection<BlockPos> pool = healing.isEmpty() ? sources : healing;
        return pool.stream().min(Comparator.comparingDouble(origin::distSqr)).orElse(null);
    }

    /** Puts an empty bucket in hand, moving one into the hotbar when every one of them sits further back. */
    private static boolean selectEmptyBucket(Minecraft client) {
        int slot = -1;
        for (int index = 0; index < 36; index++) {
            if (client.player.getInventory().getItem(index).is(Items.BUCKET)) { slot = index; break; }
        }
        if (slot < 0) return false;
        if (slot >= 9) {
            int selected = client.player.getInventory().getSelectedSlot();
            client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, slot, selected,
                    ContainerInput.SWAP, client.player);
            slot = selected;
        }
        client.player.getInventory().setSelectedSlot(slot);
        return true;
    }

    private void hold(String message) {
        navigator.release();
        reason = message;
    }

    private void finish(String message) {
        stage = Stage.FINISHED;
        navigator.reset();
        sources.clear();
        search = null; session = null; target = null;
        cooldown = 0;
        reason = message;
        AutoBuilderController.log("Refill: " + message);
    }

    private static String coordinates(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
