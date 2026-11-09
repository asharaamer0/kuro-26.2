package dev.kuro.client.builder.mixin;

import dev.kuro.client.builder.autobuild.AutoBuilderController;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only packets received from the server can acknowledge builder interactions. */
@Mixin(ClientPacketListener.class)
abstract class BuilderPacketMixin {
    @Inject(method = "handleBlockUpdate", at = @At("RETURN"))
    private void cli$block(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        AutoBuilderController.get().serverBlockUpdate(packet.getPos(), packet.getBlockState());
    }
    @Inject(method = "handleChunkBlocksUpdate", at = @At("RETURN"))
    private void cli$section(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        packet.runUpdates(AutoBuilderController.get()::serverBlockUpdate);
    }
    @Inject(method = "handleBlockChangedAck", at = @At("RETURN"))
    private void cli$ack(ClientboundBlockChangedAckPacket packet, CallbackInfo ci) {
        AutoBuilderController.get().serverAcknowledged(packet.sequence());
    }
    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void cli$correction(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        AutoBuilderController.get().positionCorrected();
    }
    @Inject(method = "handleOpenSignEditor", at = @At("HEAD"), cancellable = true)
    private void cli$openSignEditor(ClientboundOpenSignEditorPacket packet, CallbackInfo ci) {
        if (dev.kuro.client.builder.BuilderHost.get().config().autoCloseSigns) {
            ci.cancel();
            ((ClientPacketListener) (Object) this).send(new ServerboundSignUpdatePacket(packet.getPos(), packet.isFrontText(), "", "", "", ""));
        }
    }
}
