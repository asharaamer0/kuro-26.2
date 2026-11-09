package dev.kuro.client.mixin;

import dev.kuro.client.echo.handlers.RotationHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Item.class)
public class ItemMixin {

    @Redirect(
        method = "getPlayerPOVHitResult",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getYRot()F"
        ),
        require = 0
    )
    private static float redirectYaw(Player instance) {
        if (instance == Minecraft.getInstance().player && RotationHandler.isHasSilentRotation()) {
            return RotationHandler.getServerYaw();
        }
        return instance.getYRot();
    }

    @Redirect(
        method = "getPlayerPOVHitResult",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getXRot()F"
        ),
        require = 0
    )
    private static float redirectPitch(Player instance) {
        if (instance == Minecraft.getInstance().player && RotationHandler.isHasSilentRotation()) {
            return RotationHandler.getServerPitch();
        }
        return instance.getXRot();
    }
}
