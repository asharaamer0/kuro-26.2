package dev.kuro.client.builder.autobuild;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Maps blocks to interchangeable building material categories (stones, planks, glass, trapdoors, etc.)
 * so the AutoBuilder can build with available substitutes when exact materials are missing.
 */
public final class MaterialSubstitutes {
    private MaterialSubstitutes() { }

    public enum Category {
        NONE,
        STONE_FULL,
        DIRT_FULL,
        PLANKS,
        WOOD_STAIRS,
        STONE_STAIRS,
        WOOD_SLAB,
        STONE_SLAB,
        WOOD_TRAPDOOR,
        WOOD_FENCE,
        WOOD_FENCE_GATE,
        WOOD_BUTTON,
        WOOD_PRESSURE_PLATE,
        WOOD_DOOR,
        GLASS_BLOCK,
        GLASS_PANE,
        WOOL,
        CARPET,
        CONCRETE,
        TERRACOTTA
    }

    public static Category category(Block block) {
        if (block == null) return Category.NONE;
        String name = BuiltInRegistries.BLOCK.getKey(block).getPath();

        // Trapdoors
        if (block instanceof TrapDoorBlock && block != Blocks.IRON_TRAPDOOR) return Category.WOOD_TRAPDOOR;
        // Fences
        if (block instanceof FenceBlock && block != Blocks.NETHER_BRICK_FENCE) return Category.WOOD_FENCE;
        // Fence gates
        if (block instanceof FenceGateBlock) return Category.WOOD_FENCE_GATE;
        // Buttons
        if (block instanceof ButtonBlock && name.endsWith("_button") && !name.contains("stone") && !name.contains("polished")) return Category.WOOD_BUTTON;
        // Pressure plates
        if (block instanceof PressurePlateBlock && name.endsWith("_pressure_plate") && !name.contains("stone") && !name.contains("polished") && !name.contains("heavy") && !name.contains("light")) return Category.WOOD_PRESSURE_PLATE;
        // Doors
        if (block instanceof DoorBlock && block != Blocks.IRON_DOOR && block != Blocks.COPPER_DOOR.weathering().unaffected()
                && !name.contains("exposed") && !name.contains("weathered") && !name.contains("oxidized")) return Category.WOOD_DOOR;

        // Stairs
        if (block instanceof StairBlock) {
            if (isWoodName(name)) return Category.WOOD_STAIRS;
            return Category.STONE_STAIRS;
        }

        // Slabs
        if (block instanceof SlabBlock) {
            if (isWoodName(name)) return Category.WOOD_SLAB;
            return Category.STONE_SLAB;
        }

        // Planks
        if (name.endsWith("_planks") || name.equals("bamboo_mosaic")) return Category.PLANKS;

        // Glass
        if (block instanceof StainedGlassBlock || block == Blocks.GLASS || block == Blocks.TINTED_GLASS) return Category.GLASS_BLOCK;
        if (block instanceof StainedGlassPaneBlock || block == Blocks.GLASS_PANE) return Category.GLASS_PANE;

        // Wool, Carpet, Concrete, Terracotta
        if (block instanceof WoolCarpetBlock) return Category.CARPET;
        if (name.endsWith("_wool")) return Category.WOOL;
        if (name.endsWith("_concrete") && !name.contains("powder")) return Category.CONCRETE;
        if (name.endsWith("_terracotta")) return Category.TERRACOTTA;

        // Dirt
        if (block == Blocks.DIRT || block == Blocks.COARSE_DIRT || block == Blocks.ROOTED_DIRT || block == Blocks.MUD) return Category.DIRT_FULL;

        // Full solid stone / rock blocks
        if (isSolidStone(name, block)) return Category.STONE_FULL;

        return Category.NONE;
    }

    private static boolean isWoodName(String name) {
        return name.contains("oak") || name.contains("spruce") || name.contains("birch")
                || name.contains("jungle") || name.contains("acacia") || name.contains("dark_oak")
                || name.contains("mangrove") || name.contains("cherry") || name.contains("bamboo")
                || name.contains("crimson") || name.contains("warped");
    }

    private static boolean isSolidStone(String name, Block block) {
        return name.equals("stone") || name.equals("cobblestone") || name.equals("stone_bricks")
                || name.equals("mossy_cobblestone") || name.equals("mossy_stone_bricks") || name.equals("smooth_stone")
                || name.equals("andesite") || name.equals("polished_andesite")
                || name.equals("diorite") || name.equals("polished_diorite")
                || name.equals("granite") || name.equals("polished_granite")
                || name.equals("deepslate") || name.equals("cobbled_deepslate")
                || name.equals("polished_deepslate") || name.equals("deepslate_bricks") || name.equals("deepslate_tiles")
                || name.equals("tuff") || name.equals("polished_tuff") || name.equals("tuff_bricks")
                || name.equals("blackstone") || name.equals("polished_blackstone") || name.equals("polished_blackstone_bricks")
                || name.contains("sandstone") || name.equals("netherrack") || name.contains("nether_brick")
                || name.contains("basalt") || name.contains("end_stone") || name.equals("mud_bricks");
    }

    public static boolean areSubstitutable(Block expected, Block candidate) {
        if (expected == null || candidate == null) return false;
        if (expected == candidate) return true;
        Category cat = category(expected);
        if (cat == Category.NONE) return false;
        return cat == category(candidate);
    }
}
