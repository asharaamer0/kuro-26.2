package dev.kuro.client.builder.mixin;

import dev.kuro.client.command.CommandManager;
import io.netty.buffer.Unpooled;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server dialogs and chat buttons can carry a custom click action whose NBT payload is larger than the
 * 64 KiB the packet's own codec allows. Vanilla then throws while Netty encodes the packet and the whole
 * connection is lost (seen as "Failed to encode packet 'serverbound/minecraft:custom_click_action'").
 *
 * <p>Every outgoing custom click action is trial-encoded into a throw-away buffer first. If that fails,
 * the packet is dropped and the player is told, instead of being disconnected. The server could not have
 * accepted that payload anyway: its decoder has the same limit.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class CustomClickActionGuardMixin {
    private static long kuro$lastWarning;

    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void kuro$guardCustomClickAction(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ServerboundCustomClickActionPacket action)) return;
        FriendlyByteBuf probe = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ServerboundCustomClickActionPacket.STREAM_CODEC.encode(probe, action);
        } catch (RuntimeException e) {
            ci.cancel();
            long now = System.currentTimeMillis();
            if (now - kuro$lastWarning > 3000) {
                kuro$lastWarning = now;
                CommandManager.say("The server sent a click action too large to send back (" + action.id()
                        + "), so it was dropped instead of disconnecting you. Do that purchase by hand.");
            }
        } finally {
            probe.release();
        }
    }
}
