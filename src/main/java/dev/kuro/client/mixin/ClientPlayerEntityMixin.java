package dev.kuro.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.kuro.client.bridge.MovementBridge;
import dev.kuro.client.echo.event.EventMotion;
import dev.kuro.client.echo.event.EventMove;
import dev.kuro.client.echo.handlers.RotationHandler;
import dev.kuro.client.util.SilentRaycastHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Movement packets always use the real camera yaw/pitch so WASD and Grim's
 * movement simulation stay vanilla. Silent aim angles are sent separately as
 * pure Look packets via {@link dev.kuro.client.util.Rotations#sendLookPacket()}.
 */
@Mixin(LocalPlayer.class)
public abstract class ClientPlayerEntityMixin {

    // After a forced silent look packet the server still holds the silent angles. Vanilla only puts the rotation into a
    // movement packet when it changed, so nudge the real yaw by a hair (0.03 deg, invisible) to make this tick's
    // movement packet carry the real camera angles again.
    /** Pre-motion: runs before anything of this tick's movement packet is built (after vanilla input handling). */
    @Inject(method = "sendPosition", at = @At("HEAD"), require = 0)
    private void kuro$restoreLook(CallbackInfo ci) {
        dev.kuro.client.module.impl.Freecam.drive(net.minecraft.client.Minecraft.getInstance());
        // pre-motion modules act first (their packets go out ahead of the movement packet), then the look restore
        if (dev.kuro.client.KuroClient.modules != null) dev.kuro.client.KuroClient.modules.onPreMotion();
        if (dev.kuro.client.util.Rotations.consumeRestore()) {
            net.minecraft.client.player.LocalPlayer self = (net.minecraft.client.player.LocalPlayer) (Object) this;
            self.setYRot(self.getYRot() + 0.03f);
        }
    }

    @Inject(method = "sendPosition", at = @At("RETURN"), require = 0)
    private void kuro$markMoved(CallbackInfo ci) {
        dev.kuro.client.util.TickPhase.markMoved();
        // Post-motion: directly behind the movement packet
        if (dev.kuro.client.KuroClient.modules != null) dev.kuro.client.KuroClient.modules.onPostMotion();
        if (dev.kuro.client.gui.KuroTitleScreen.CACHED_PLAYER_SKIN == null) {
            dev.kuro.client.gui.KuroTitleScreen.CACHED_PLAYER_SKIN = ((LocalPlayer) (Object) this).getSkin();
        }
    }

    // ---- echo client hooks (Anchor Aura): the movement packet carries the yaw / pitch an EventMove.Pre listener sets ----
    @Unique
    private EventMove kuro$echoMove;

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void kuro$echoMovePre(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        float pitch = RotationHandler.getEnforcedPitch(self.getXRot());
        // keep the server rotation in sync every tick (continuity, prevents AimModulo360)
        if (!RotationHandler.isHasSilentRotation()) RotationHandler.updateServerRotations(self.getYRot(), pitch);
        EventMove event = new EventMove.Pre(self.getX(), self.getY(), self.getZ(), self.onGround(), self.getYRot(), pitch);
        event.call();
        this.kuro$echoMove = event;
    }

    @Unique
    private boolean kuro$renderAdvanced;

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void kuro$echoMovePost(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        new EventMove.Post(self.getX(), self.getY(), self.getZ(), self.onGround(), self.getYRot(), self.getXRot()).call();
        this.kuro$echoMove = null;

        if (dev.kuro.client.util.RotationHandler.isSilentActive()) {
            dev.kuro.client.util.RotationHandler.updateRenderBodyYaw(self, dev.kuro.client.util.RotationHandler.getSilentYaw());
            self.yHeadRot = dev.kuro.client.util.RotationHandler.getRenderYaw();
            self.yBodyRot = dev.kuro.client.util.RotationHandler.getRenderBodyYaw();
        }
        dev.kuro.client.util.RotationHandler.endTick();

        kuro$syncSilentRenderRotations(self, true);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void kuro$onTickReturn(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (dev.kuro.client.util.RotationHandler.isSilentHeld()) {
            self.yHeadRot = dev.kuro.client.util.RotationHandler.getRenderYaw();
            self.yBodyRot = dev.kuro.client.util.RotationHandler.getRenderBodyYaw();
        } else {
            dev.kuro.client.util.RotationHandler.clearRenderBodyYaw();
        }

        kuro$syncSilentRenderRotations(self, false);
        this.kuro$renderAdvanced = false;
    }

    @Unique
    private void kuro$syncSilentRenderRotations(LocalPlayer player, boolean advance) {
        if (!RotationHandler.isHasSilentRotation()) {
            RotationHandler.clearRenderBodyYaw();
            return;
        }
        if (advance || !kuro$renderAdvanced) {
            RotationHandler.updateRenderRotations(player);
            kuro$renderAdvanced = true;
        }
        player.yHeadRot = RotationHandler.getServerYaw();
        player.yBodyRot = RotationHandler.getRenderBodyYaw();
    }

    @ModifyExpressionValue(method = {"sendPosition", "tick"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F"), require = 0)
    private float kuro$echoYaw(float original) {
        if (dev.kuro.client.util.RotationHandler.isSilentActive()) {
            return dev.kuro.client.util.RotationHandler.getSilentYaw();
        }
        if (RotationHandler.isHasSilentRotation()) {
            return RotationHandler.getServerYaw();
        }
        return this.kuro$echoMove != null ? (float) this.kuro$echoMove.getYaw() : original;
    }

    @ModifyExpressionValue(method = {"sendPosition", "tick"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getXRot()F"), require = 0)
    private float kuro$echoPitch(float original) {
        if (dev.kuro.client.util.RotationHandler.isSilentActive()) {
            return dev.kuro.client.util.RotationHandler.getSilentPitch();
        }
        if (RotationHandler.isHasSilentRotation()) {
            return RotationHandler.getServerPitch();
        }
        float pitch = this.kuro$echoMove != null ? (float) this.kuro$echoMove.getPitch() : original;
        return RotationHandler.getEnforcedPitch(pitch);
    }

    @Inject(method = "raycastHitResult", at = @At("HEAD"), cancellable = true)
    private void kuro$silentMouseOver(float partialTicks, Entity camera, CallbackInfoReturnable<HitResult> cir) {
        if (SilentRaycastHelper.isInSilentRaycast()) return;
        if (camera != (Object) this) return;
        float[] rotation = MovementBridge.getPacketRotation();
        if (rotation == null) return;

        LocalPlayer player = (LocalPlayer) (Object) this;
        float savedYaw = player.getYRot();
        float savedPitch = player.getXRot();
        player.setYRot(rotation[0]);
        player.yHeadRot = rotation[0];
        player.setXRot(rotation[1]);
        SilentRaycastHelper.setInSilentRaycast(true);
        try {
            cir.setReturnValue(((LocalPlayer) (Object) this).raycastHitResult(partialTicks, camera));
        } finally {
            SilentRaycastHelper.setInSilentRaycast(false);
            player.setYRot(savedYaw);
            player.yHeadRot = savedYaw;
            player.setXRot(savedPitch);
        }
    }

    // ---- StrafeFix & Silent Aim Hooks ----
    @Inject(method = "aiStep", at = @At("HEAD"))
    public void kuro$onAiStepPre(CallbackInfo ci) {
        if (dev.kuro.client.module.impl.Freecam.active()) {
            LocalPlayer self = (LocalPlayer) (Object) this;
            self.input.keyPresses = new net.minecraft.world.entity.player.Input(false, false, false, false, false, false, false);
            ((dev.kuro.client.mixin.ClientInputAccessor) self.input).kuro$setMoveVector(net.minecraft.world.phys.Vec2.ZERO);
            net.minecraft.world.phys.Vec3 cur = self.getDeltaMovement();
            self.setDeltaMovement(0.0, Math.min(0.0, cur.y), 0.0);
        }
        LocalPlayer self = (LocalPlayer) (Object) this;

        dev.kuro.client.util.RotationHandler.startTick();
        dev.kuro.client.util.RotationHandler.setOriginal(self.getYRot(), self.getXRot());

        float enforced = RotationHandler.getEnforcedPitch(self.getXRot());
        dev.kuro.client.event.EventBus.INSTANCE.post(new dev.kuro.client.event.events.MotionEvent(dev.kuro.client.event.events.MotionEvent.State.PRE));
        EventMotion event = new EventMotion.Pre(self.getX(), self.getY(), self.getZ(), self.onGround(), self.getYRot(), enforced);
        event.call();

        if (dev.kuro.client.util.RotationHandler.isSilentActive() && self.input != null) {
            float forward = (self.input.keyPresses.forward() ? 1.0f : 0.0f) - (self.input.keyPresses.backward() ? 1.0f : 0.0f);
            float strafe = (self.input.keyPresses.left() ? 1.0f : 0.0f) - (self.input.keyPresses.right() ? 1.0f : 0.0f);

            if (forward != 0.0f || strafe != 0.0f) {
                double moveAngleRad = Math.atan2(-strafe, forward);
                float moveAngleDeg = (float) Math.toDegrees(moveAngleRad);

                if (Math.abs(moveAngleDeg) > 35.0f && self.isSprinting()
                        && !MovementBridge.isSprintFixActive()) {
                    self.setSprinting(false);
                }
            }
        }

        MovementBridge.prepareMovementCorrection(self);
    }

    @Inject(method = "aiStep", at = @At("RETURN"))
    public void kuro$onAiStepPost(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        MovementBridge.restoreTickRotation(self);
        dev.kuro.client.event.EventBus.INSTANCE.post(new dev.kuro.client.event.events.MotionEvent(dev.kuro.client.event.events.MotionEvent.State.POST));
        EventMotion event = new EventMotion.Post(self.getX(), self.getY(), self.getZ(), self.onGround(), self.getYRot(), self.getXRot());
        event.call();
    }
}
