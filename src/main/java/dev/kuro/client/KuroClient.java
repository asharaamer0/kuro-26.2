package dev.kuro.client;

import dev.kuro.client.builder.BuilderHost;
import dev.kuro.client.builder.autobuild.AutoBuilderController;
import dev.kuro.client.builder.finder.FinderScanner;
import dev.kuro.client.builder.render.HighlightRenderer;
import dev.kuro.client.command.CommandManager;
import dev.kuro.client.config.ConfigManager;
import dev.kuro.client.event.EventBus;
import dev.kuro.client.event.events.MotionEvent;
import dev.kuro.client.event.events.RenderEvent;
import dev.kuro.client.event.events.TickEvent;
import dev.kuro.client.module.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public class KuroClient implements ClientModInitializer {
    public static ModuleManager modules;
    private static HighlightRenderer builderRenderer;

    @Override
    public void onInitializeClient() {
        dev.kuro.client.util.ShaderUi.init();      // registers the UI shader pipelines before the first frame
        modules = new ModuleManager();
        ConfigManager.init();
        CommandManager.init();
        dev.kuro.client.util.WorldGfx.init();
        dev.kuro.client.util.TargetCache.init();

        // Kuro Builder: its settings file, plus the in-world schematic preview renderer (registered once).
        var builderHost = BuilderHost.get();
        builderRenderer = new HighlightRenderer(builderHost.config(), new FinderScanner(builderHost.config()));
        builderRenderer.register();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AutoBuilderController.get().disconnect();
            ConfigManager.saveImmediately();
            dev.kuro.client.module.impl.Hud.applyTheme();
        });
        
        // Inject RenderEvent before the world renders
        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(ctx -> {
            float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
            EventBus.INSTANCE.post(new RenderEvent(partialTick));
            modules.onWorld(ctx);
        });
        
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            dev.kuro.client.util.SwapLimit.nextTick();
            dev.kuro.client.util.ServerSync.nextTick();
            dev.kuro.client.util.TickPhase.startTick();
            dev.kuro.client.module.impl.AttackPackets.flush();     // hits held back from the end of last tick go out first, before any movement packet
            
            // --- EVENT BUS INTEGRATION ---
            EventBus.INSTANCE.post(new TickEvent.Pre());
            
            modules.onPreTick();
        });
        
        ClientTickEvents.END_CLIENT_TICK.register(client -> { 
            modules.onTick(); 
            dev.kuro.client.util.MenuKey.tick(client);
            dev.kuro.client.module.impl.FakeLoading.tick(client);   // first start: asks which key opens the menu
            ConfigManager.tick(); 
            
            // --- EVENT BUS INTEGRATION ---
            EventBus.INSTANCE.post(new TickEvent.Post());
        });
        
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            dev.kuro.client.util.ViewLock.release(client);
            if (builderRenderer != null) builderRenderer.close();
        });
        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("kuro", "modules"),
                (context, tickCounter) -> modules.onHud(context));
    }
}