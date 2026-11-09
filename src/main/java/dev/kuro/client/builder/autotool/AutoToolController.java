package dev.kuro.client.builder.autotool;

import dev.kuro.client.builder.autobuild.AutoBuilderController;
import dev.kuro.client.builder.config.AutoToolSettings;
import dev.kuro.client.builder.config.ClientConfig;
import dev.kuro.client.builder.freecam.FreecamController;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class AutoToolController {
    private static AutoToolController instance;
    private final ClientConfig config;
    private int previousSlot = -1;
    private int lastAutoToolSlot = -1;
    private boolean mining = false;

    public AutoToolController(ClientConfig config) {
        this.config = config;
        instance = this;
    }

    public static AutoToolController get() {
        return instance;
    }

    public AutoToolSettings settings() {
        return config.autoTool;
    }

    public static boolean isMineable(BlockState state) {
        if (state == null || state.isAir()) return false;
        return state.getBlock().defaultDestroyTime() >= 0.0f;
    }

    public void onAttackBlock(Minecraft client) {
        if (config == null || settings() == null || !settings().enabled) return;
        if (client.player == null || client.level == null) return;
        if (AutoBuilderController.get().isRunning()) return;

        // Only activate in Survival/Adventure mode
        if (client.player.isCreative() || client.player.isSpectator() || client.player.getAbilities().instabuild) {
            return;
        }

        // Do not activate while using an item (eating, shooting bow, blocking with shield)
        if (client.player.isUsingItem()) return;

        // Do not activate when aiming at or attacking entities
        if (client.crosshairPickEntity != null) return;

        // Only activate when holding attack key on a block
        boolean attackDown = client.options.keyAttack.isDown()
                || (FreecamController.enabled() && FreecamController.getInstance().settings().autoDig);
        if (!attackDown) return;

        if (client.hitResult == null || client.hitResult.getType() != HitResult.Type.BLOCK) return;

        BlockHitResult hit = (BlockHitResult) client.hitResult;
        BlockPos pos = hit.getBlockPos();
        BlockState state = client.level.getBlockState(pos);
        if (!isMineable(state)) return;

        if (client.gameMode != null && client.player.blockActionRestricted(client.level, pos, client.gameMode.getPlayerMode())) {
            return;
        }

        int currentSlot = client.player.getInventory().getSelectedSlot();
        int bestSlot = findBestSlot(client.player, state, settings().saveDurability);
        if (bestSlot < 0) return;

        if (bestSlot != currentSlot) {
            if (previousSlot < 0 && settings().switchBack) {
                previousSlot = currentSlot;
            }
            client.player.getInventory().setSelectedSlot(bestSlot);
            lastAutoToolSlot = bestSlot;
            mining = true;
        } else {
            lastAutoToolSlot = currentSlot;
            mining = true;
        }
    }

    public void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            previousSlot = -1;
            lastAutoToolSlot = -1;
            mining = false;
            return;
        }

        if (AutoBuilderController.get().isRunning()) {
            previousSlot = -1;
            lastAutoToolSlot = -1;
            mining = false;
            return;
        }

        int currentSlot = client.player.getInventory().getSelectedSlot();
        if (lastAutoToolSlot != -1 && currentSlot != lastAutoToolSlot) {
            // Player manually changed slot; do not override user input
            previousSlot = -1;
            lastAutoToolSlot = currentSlot;
        }

        if (mining) {
            boolean attackDown = client.options.keyAttack.isDown()
                    || (FreecamController.enabled() && FreecamController.getInstance().settings().autoDig);
            if (!attackDown) {
                mining = false;
                if (previousSlot >= 0 && settings().switchBack) {
                    client.player.getInventory().setSelectedSlot(previousSlot);
                }
                previousSlot = -1;
                lastAutoToolSlot = -1;
            }
        }
    }

    public static int findBestSlot(Player player, BlockState state, boolean saveDurability) {
        java.util.List<ItemStack> hotbar = new java.util.ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            hotbar.add(player.getInventory().getItem(i));
        }
        return findBestSlot(player.getInventory().getSelectedSlot(), hotbar, state, saveDurability);
    }

    public static int findBestSlot(int currentSlot, java.util.List<ItemStack> hotbar, BlockState state, boolean saveDurability) {
        int bestSlot = -1;
        float bestSpeed = 1.0f;
        boolean bestCorrectDrops = false;

        boolean requiresCorrect = state.requiresCorrectToolForDrops();

        for (int slot = 0; slot < Math.min(9, hotbar.size()); slot++) {
            ItemStack stack = hotbar.get(slot);
            if (stack == null || stack.isEmpty()) continue;
            if (saveDurability && isNearBreaking(stack)) continue;

            float speed = stack.getDestroySpeed(state);
            boolean drops = stack.isCorrectToolForDrops(state);

            if (bestSlot == -1) {
                bestSlot = slot;
                bestSpeed = speed;
                bestCorrectDrops = drops;
                continue;
            }

            if (requiresCorrect) {
                if (drops && !bestCorrectDrops) {
                    bestSlot = slot;
                    bestSpeed = speed;
                    bestCorrectDrops = drops;
                } else if (drops == bestCorrectDrops) {
                    if (speed > bestSpeed) {
                        bestSlot = slot;
                        bestSpeed = speed;
                    } else if (speed == bestSpeed && slot == currentSlot) {
                        bestSlot = slot;
                    }
                }
            } else {
                if (speed > bestSpeed) {
                    bestSlot = slot;
                    bestSpeed = speed;
                    bestCorrectDrops = drops;
                } else if (speed == bestSpeed) {
                    if (drops && !bestCorrectDrops) {
                        bestSlot = slot;
                        bestSpeed = speed;
                        bestCorrectDrops = drops;
                    } else if (drops == bestCorrectDrops && slot == currentSlot) {
                        bestSlot = slot;
                    }
                }
            }
        }

        if (bestSlot != -1) {
            ItemStack currentStack = currentSlot >= 0 && currentSlot < hotbar.size() ? hotbar.get(currentSlot) : ItemStack.EMPTY;
            float currentSpeed = currentStack == null || currentStack.isEmpty() ? 1.0f : currentStack.getDestroySpeed(state);
            boolean currentDrops = currentStack != null && !currentStack.isEmpty() && currentStack.isCorrectToolForDrops(state);

            if (currentDrops == bestCorrectDrops && currentSpeed >= bestSpeed) {
                return currentSlot;
            }
            if (!bestCorrectDrops && bestSpeed <= 1.0f) {
                // No tool in hotbar is effective; stay on current slot to avoid damaging inappropriate tools
                return currentSlot;
            }
        }

        return bestSlot != -1 ? bestSlot : currentSlot;
    }

    public static boolean isNearBreaking(ItemStack stack) {
        if (!stack.isDamageableItem()) return false;
        return stack.getMaxDamage() - stack.getDamageValue() <= 1;
    }
}
