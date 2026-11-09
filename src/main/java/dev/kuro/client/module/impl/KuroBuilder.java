package dev.kuro.client.module.impl;

import dev.kuro.client.builder.BuilderHost;
import dev.kuro.client.builder.autobuild.AutoBuilderController;
import dev.kuro.client.builder.config.ClientConfig;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;

/**
 * Kuro Builder: the module side of the ported builder engine.
 *
 * The module owns the engine's lifetime. While it is on, the controller ticks and the schematic preview
 * is drawn; switching it off cancels the build and hides the preview. Start / position / options all live
 * in the themed {@link dev.kuro.client.gui.KuroBuilderScreen}.
 */
public class KuroBuilder extends Module {
    private static KuroBuilder instance;

    public static KuroBuilder get() { return instance; }

    public KuroBuilder() {
        super("Kuro Builder", Category.PLAYER);
        instance = this;

        // The engine keeps its own settings file; attach once so a chosen material storage survives restarts.
        AutoBuilderController.get().attach(BuilderHost.get().config(), BuilderHost.get()::save);

        add(new Setting.Button("Open Kuro Builder...", this::openGui));
        add(new Setting.Button("Open schematic folder", BuilderHost.get()::openSchematicsFolder));
    }

    public AutoBuilderController controller() { return AutoBuilderController.get(); }

    public ClientConfig config() { return BuilderHost.get().config(); }

    public void openGui() {
        mc.gui.setScreen(new dev.kuro.client.gui.KuroBuilderScreen(mc.gui.screen(), this));
    }

    /** Starts the build, switching the module on first so the controller ticks. */
    public void startBuild() {
        if (!isEnabled()) setEnabled(true);
        AutoBuilderController.get().start();
    }

    public void pauseBuild() { AutoBuilderController.get().pause(); }

    public void resumeBuild() {
        if (!isEnabled()) setEnabled(true);
        AutoBuilderController.get().resume();
    }

    public void stopBuild() { AutoBuilderController.get().cancel(); }

    public void save() { BuilderHost.get().save(); }

    @Override
    protected void onEnable() {
        AutoBuilderController controller = AutoBuilderController.get();
        if (controller.schematic() != null && controller.status() != AutoBuilderController.Status.RUNNING) {
            // Ready for a Start; show where it will go.
            controller.setPreviewVisible(true);
        }
    }

    @Override
    protected void onDisable() {
        AutoBuilderController controller = AutoBuilderController.get();
        controller.cancel();
        controller.setPreviewVisible(false);
    }

    @Override
    public void onPreTick() {
        AutoBuilderController.get().tick(mc);
    }
}
