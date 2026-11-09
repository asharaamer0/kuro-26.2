package dev.kuro.client.builder.autobuild;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The container chosen as the build's material source: the dimension it stands in and its position.
 *
 * <p>The choice is kept as a single configuration string so it is set once and still there next time.
 * Anything unreadable parses to {@code null}, which simply means no storage is chosen, so a hand-edited
 * or stale value can never send the builder to a position nobody picked.
 */
public record MaterialStorage(String dimension, BlockPos pos) {
    public static MaterialStorage parse(String value) {
        if (value == null) return null;
        int separator = value.indexOf('@');
        if (separator <= 0 || separator == value.length() - 1) return null;
        String[] parts = value.substring(separator + 1).split(",");
        if (parts.length != 3) return null;
        try {
            BlockPos pos = new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
            return new MaterialStorage(value.substring(0, separator), pos);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    public String encode() {
        return dimension + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** True when this storage stands in the level the builder is working in. */
    public boolean inDimension(String level) { return dimension.equals(level); }

    /**
     * True for a block the builder may open and take materials out of. A chest, a barrel and a shulker box
     * all open the same ordinary container menu, so any of them can serve as the material source; an ender
     * chest does not, because it holds nothing the world can be searched for.
     */
    public static boolean isStorage(BlockState state) {
        return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock
                || state.getBlock() instanceof ShulkerBoxBlock;
    }
}
