package dev.kuro.client.builder.gui;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * Sound profiles for ClickGUI and AutoBuilder interactions.
 */
public enum GuiSoundTheme {
    CREAMY("Creamy"),
    CLASSIC("Classic"),
    BUBBLE("Bubble"),
    MECHANICAL("Mechanical"),
    CHIME("Chime"),
    PLING("Pling"),
    WOOD("Wood");

    public final String label;

    GuiSoundTheme(String label) {
        this.label = label;
    }

    public SoundEvent hoverSound() {
        return switch (this) {
            case CREAMY -> SoundEvents.CHISELED_BOOKSHELF_INSERT;
            case CLASSIC -> SoundEvents.UI_BUTTON_CLICK.value();
            case BUBBLE -> SoundEvents.BUBBLE_POP;
            case MECHANICAL -> SoundEvents.STONE_BUTTON_CLICK_ON;
            case CHIME -> SoundEvents.AMETHYST_BLOCK_CHIME;
            case PLING -> SoundEvents.NOTE_BLOCK_PLING.value();
            case WOOD -> SoundEvents.BAMBOO_WOOD_BUTTON_CLICK_ON;
        };
    }

    public float hoverPitch() {
        return switch (this) {
            case CREAMY -> 1.7f;
            case CLASSIC -> 1.6f;
            case BUBBLE -> 1.7f;
            case MECHANICAL -> 1.8f;
            case CHIME -> 1.8f;
            case PLING -> 2.0f;
            case WOOD -> 1.7f;
        };
    }

    public float hoverVolume() {
        return switch (this) {
            case CREAMY -> 0.12f;
            case CLASSIC -> 0.08f;
            case BUBBLE -> 0.12f;
            case MECHANICAL -> 0.09f;
            case CHIME -> 0.12f;
            case PLING -> 0.08f;
            case WOOD -> 0.10f;
        };
    }

    public SoundEvent leftClickSound() {
        return switch (this) {
            case CREAMY -> SoundEvents.DECORATED_POT_INSERT;
            case CLASSIC -> SoundEvents.UI_BUTTON_CLICK.value();
            case BUBBLE -> SoundEvents.BUBBLE_POP;
            case MECHANICAL -> SoundEvents.STONE_BUTTON_CLICK_ON;
            case CHIME -> SoundEvents.AMETHYST_BLOCK_HIT;
            case PLING -> SoundEvents.NOTE_BLOCK_PLING.value();
            case WOOD -> SoundEvents.BAMBOO_WOOD_BUTTON_CLICK_ON;
        };
    }

    public float leftClickPitch() {
        return switch (this) {
            case CREAMY -> 1.25f;
            case CLASSIC -> 1.12f;
            case BUBBLE -> 1.25f;
            case MECHANICAL -> 1.20f;
            case CHIME -> 1.25f;
            case PLING -> 1.40f;
            case WOOD -> 1.20f;
        };
    }

    public float leftClickVolume() {
        return switch (this) {
            case CREAMY -> 0.35f;
            case CLASSIC -> 0.22f;
            case BUBBLE -> 0.35f;
            case MECHANICAL -> 0.26f;
            case CHIME -> 0.30f;
            case PLING -> 0.25f;
            case WOOD -> 0.30f;
        };
    }

    public SoundEvent rightClickSound() {
        return switch (this) {
            case CREAMY -> SoundEvents.DECORATED_POT_INSERT;
            case CLASSIC -> SoundEvents.UI_BUTTON_CLICK.value();
            case BUBBLE -> SoundEvents.BUBBLE_POP;
            case MECHANICAL -> SoundEvents.STONE_BUTTON_CLICK_ON;
            case CHIME -> SoundEvents.AMETHYST_BLOCK_HIT;
            case PLING -> SoundEvents.NOTE_BLOCK_PLING.value();
            case WOOD -> SoundEvents.BAMBOO_WOOD_BUTTON_CLICK_ON;
        };
    }

    public float rightClickPitch() {
        return switch (this) {
            case CREAMY -> 1.05f;
            case CLASSIC -> 0.88f;
            case BUBBLE -> 0.95f;
            case MECHANICAL -> 0.92f;
            case CHIME -> 1.00f;
            case PLING -> 1.10f;
            case WOOD -> 0.95f;
        };
    }

    public float rightClickVolume() {
        return switch (this) {
            case CREAMY -> 0.35f;
            case CLASSIC -> 0.22f;
            case BUBBLE -> 0.35f;
            case MECHANICAL -> 0.26f;
            case CHIME -> 0.30f;
            case PLING -> 0.25f;
            case WOOD -> 0.30f;
        };
    }
}
