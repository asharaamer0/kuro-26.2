package dev.kuro.client.module.impl;

import dev.kuro.client.echo.event.EventStartUseItem;
import dev.kuro.client.event.EventHandler;
import dev.kuro.client.echo.handlers.InputHandler;
import dev.kuro.client.mixin.EchoKeyMappingAccessor;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.BlockPlacer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Raze-style, crosshair-driven Auto Crystal adapted to Kuro's Module, Setting,
 * input and inventory APIs. Hold the activation bind while looking at a crystal
 * base to place, or at an End Crystal to break it.
 */
public class AutoCrystal extends Module {
    // Kuro bind encoding: keyboard = GLFW key code, mouse = 1000 + GLFW mouse button; -1 disables the bind.
    private final Setting.Num activateKey = add(new Setting.Num("Activate key (code)", 1001, -1, 1400, 1));
    private final Setting.Num placeDelay = add(new Setting.Num("Place delay (ticks)", 1, 0, 20, 1));
    private final Setting.Num breakDelay = add(new Setting.Num("Break delay (ticks)", 1, 0, 20, 1));
    private final Setting.Num failChance = add(new Setting.Num("Fail chance (%)", 0, 0, 100, 1));
    private final Setting.Bool inputSimulation = add(new Setting.Bool("Input simulation", false));
    private final Setting.Bool autoSwitch = add(new Setting.Bool("Auto switch", true));
    private final Setting.Bool useOffhand = add(new Setting.Bool("Use offhand", true));
    private final Setting.Bool stopOnKill = add(new Setting.Bool("Stop on kill", false));

    private int placeCooldown;
    private int breakCooldown;

    public AutoCrystal() { super("Auto Crystal", Category.COMBAT); }

    @Override
    public void onDisable() {
        placeCooldown = 0;
        breakCooldown = 0;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null
                || mc.player.isSpectator() || mc.player.isUsingItem()) return;
        if (!isActivateDown()) return;
        if (stopOnKill.value && deadPlayerNearby()) return;

        if (placeCooldown > 0) placeCooldown--;
        if (breakCooldown > 0) breakCooldown--;

        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof EndCrystal crystal) {
            if (breakCooldown == 0 && crystal.isAlive() && mc.player.distanceTo(crystal) <= mc.player.entityInteractionRange()) {
                if (passesFailCheck()) {
                    if (inputSimulation.value) clickMapping(mc.options.keyAttack);
                    else AttackPackets.hit(crystal);
                }
                breakCooldown = breakDelay.intValue();
            }
            return;
        }

        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return;
        if (placeCooldown > 0) return;
        var base = mc.level.getBlockState(blockHit.getBlockPos());
        if (!base.is(Blocks.OBSIDIAN) && !base.is(Blocks.BEDROCK)) return;

        InteractionHand hand = mc.player.getOffhandItem().is(Items.END_CRYSTAL) && useOffhand.value
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (!mc.player.getItemInHand(hand).is(Items.END_CRYSTAL)) {
            if (!autoSwitch.value) return;
            int slot = BlockPlacer.findSlot(stack -> stack.is(Items.END_CRYSTAL));
            if (slot < 0 || !BlockPlacer.select(slot)) return;
            return; // let the server receive the slot change before the use packet
        }

        if (inputSimulation.value) {
            if (passesFailCheck()) clickMapping(mc.options.keyUse);
            placeCooldown = placeDelay.intValue();
            return;
        }

        InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, blockHit);
        if (result.consumesAction()) {
            mc.player.swing(hand);
            placeCooldown = placeDelay.intValue();
        }
    }

    /** Stop vanilla's duplicate right-click use; the module emits its own validated use from onTick. */
    @EventHandler
    private void onStartUseItem(EventStartUseItem.Pre event) {
        if (inputSimulation.value || (stopOnKill.value && mc.level != null && deadPlayerNearby())
                || event.getPlayer() != mc.player || !isActivateDown() || mc.level == null) return;
        if (!(event.getHitResult() instanceof BlockHitResult hit)) return;
        var state = mc.level.getBlockState(hit.getBlockPos());
        if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) return;
        boolean hasCrystal = mc.player.getMainHandItem().is(Items.END_CRYSTAL)
                || (useOffhand.value && mc.player.getOffhandItem().is(Items.END_CRYSTAL))
                || (autoSwitch.value && BlockPlacer.findSlot(stack -> stack.is(Items.END_CRYSTAL)) >= 0);
        if (hasCrystal) event.cancel();
    }

    private boolean passesFailCheck() {
        return Math.random() * 100.0 >= failChance.value;
    }

    private static void clickMapping(net.minecraft.client.KeyMapping mapping) {
        if (mapping == null) return;
        boolean wasDown = mapping.isDown();
        EchoKeyMappingAccessor accessor = (EchoKeyMappingAccessor) mapping;
        accessor.kuro$setClickCount(accessor.kuro$getClickCount() + 1);
        mapping.setDown(true);
        mapping.setDown(false);
        if (wasDown) mapping.setDown(true);
    }

    private boolean deadPlayerNearby() {
        for (var player : mc.level.players()) {
            if (player != mc.player && mc.player.distanceToSqr(player) < 36.0 && player.isDeadOrDying()) return true;
        }
        return false;
    }

    private boolean isActivateDown() {
        int code = activateKey.intValue();
        if (code < 0) return false;
        int bind = code >= 1000 ? 0x80000000 | (code - 1000) : code;
        return InputHandler.isBindDown(bind);
    }
}
