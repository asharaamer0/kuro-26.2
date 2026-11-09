package dev.kuro.client.builder;

import dev.kuro.client.builder.config.ClientConfig;
import dev.kuro.client.builder.config.ConfigStore;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The builder's tie to the Kuro client: where its own settings file and schematic folder live,
 * and how the rest of the client reaches them.
 */
public final class BuilderHost {
    private static BuilderHost instance;

    private final ConfigStore store;
    private final ClientConfig config;
    private final Path dir;
    private final Path schematics;

    private BuilderHost() {
        dir = FabricLoader.getInstance().getConfigDir().resolve("kuro-builder");
        store = new ConfigStore(dir.resolve("settings.json"));
        config = store.load();
        schematics = dir.resolve("schematics");
    }

    public static synchronized BuilderHost get() {
        if (instance == null) instance = new BuilderHost();
        return instance;
    }

    public ClientConfig config() { return config; }

    public void save() { store.save(config); }

    /** The folder the schematic list reads. Created on first use. */
    public Path schematicsDir() {
        try { Files.createDirectories(schematics); } catch (IOException ignored) { }
        return schematics;
    }

    /** Every .litematic in the folder, A-Z. */
    public List<Path> schematics() {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(schematicsDir())) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .forEach(out::add);
        } catch (IOException ignored) { }
        return out;
    }

    public void openSchematicsFolder() {
        try {
            java.awt.Desktop.getDesktop().open(schematicsDir().toFile());
        } catch (Exception e) {
            dev.kuro.client.command.CommandManager.say("Schematic folder: " + schematicsDir());
        }
    }
}
