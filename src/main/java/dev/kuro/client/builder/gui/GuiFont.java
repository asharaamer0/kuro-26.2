package dev.kuro.client.builder.gui;

import net.minecraft.resources.Identifier;

/**
 * Clean font options for the client interface.
 */
public enum GuiFont {
    JAKARTA("Plus Jakarta", Identifier.fromNamespaceAndPath("cli", "jakarta")),
    OUTFIT("Outfit", Identifier.fromNamespaceAndPath("cli", "outfit")),
    POPPINS("Poppins", Identifier.fromNamespaceAndPath("cli", "poppins")),
    JETBRAINS("JetBrains Mono", Identifier.fromNamespaceAndPath("cli", "jetbrains")),
    LEXEND("Lexend", Identifier.fromNamespaceAndPath("cli", "lexend")),
    MONTSERRAT("Montserrat", Identifier.fromNamespaceAndPath("cli", "montserrat")),
    UNIFORM("Uniform", Identifier.fromNamespaceAndPath("minecraft", "uniform")),
    VANILLA("Minecraft", Identifier.fromNamespaceAndPath("minecraft", "default"));

    public final String label;
    public final Identifier id;

    GuiFont(String label, Identifier id) {
        this.label = label;
        this.id = id;
    }
}
