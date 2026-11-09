package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

public class PacketUtils {
    public static int getSelectedSlot() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            return mc.player.getInventory().getSelectedSlot();
        }
        return BlockPlacer.selected();
    }

    public static void setSelectedSlot(int slot) {
        if (slot < 0 || slot > 8) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.player.getInventory().getSelectedSlot() != slot) {
            mc.player.getInventory().setSelectedSlot(slot);
            if (mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            }
        }
        BlockPlacer.selectForce(slot);
    }
}
