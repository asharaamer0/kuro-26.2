package dev.kuro.client.builder.config;

public final class ClientConfig {
    public int schemaVersion = 1;
    public FinderSettings storage = new FinderSettings();
    public FinderSettings spawner = spawnerDefaults();
    public FreecamSettings freecam = new FreecamSettings();
    public AutoBuilderSettings autoBuilder = new AutoBuilderSettings();
    public ChunkFinderSettings chunkFinder = new ChunkFinderSettings();
    public FullbrightSettings fullbright = new FullbrightSettings();
    public RenderMethodSettings renderMethod = new RenderMethodSettings();
    public FreelookSettings freelook = new FreelookSettings();
    public PlayerEspSettings playerEsp = new PlayerEspSettings();
    public AutoToolSettings autoTool = new AutoToolSettings();
    public int accentColor = 0x8CB876;
    public HudSettings hud = new HudSettings();
    public boolean showHud = true;
    public boolean snowfall = true;
    public boolean guiSounds = true;
    public boolean autoCloseSigns = true;
    public dev.kuro.client.builder.gui.GuiSoundTheme soundTheme = dev.kuro.client.builder.gui.GuiSoundTheme.CREAMY;
    public dev.kuro.client.builder.gui.GuiFont font = dev.kuro.client.builder.gui.GuiFont.JAKARTA;
    /** When true the click GUI fills the window; otherwise it uses {@link #clickGuiScale}. */
    public boolean clickGuiAutoScale = true;
    public double clickGuiScale = 1.0;
    /** Where the click GUI sits, as an offset from its centred position. */
    public int clickGuiX = 0;
    public int clickGuiY = 0;
    /** Lifetime playtime recorded by the session tracker, in milliseconds. */
    public long totalPlaytimeMillis = 0;

    private static FinderSettings spawnerDefaults() {
        FinderSettings settings = new FinderSettings();
        settings.color = 0xFF779C;
        settings.perTypeColors = false;
        return settings;
    }

    public void sanitize() {
        if (storage == null) storage = new FinderSettings();
        if (spawner == null) spawner = spawnerDefaults();
        if (freecam == null) freecam = new FreecamSettings();
        if (autoBuilder == null) autoBuilder = new AutoBuilderSettings();
        if (chunkFinder == null) chunkFinder = new ChunkFinderSettings();
        if (fullbright == null) fullbright = new FullbrightSettings();
        if (renderMethod == null) renderMethod = new RenderMethodSettings();
        if (freelook == null) freelook = new FreelookSettings();
        if (playerEsp == null) playerEsp = new PlayerEspSettings();
        if (autoTool == null) autoTool = new AutoToolSettings();
        if (soundTheme == null) soundTheme = dev.kuro.client.builder.gui.GuiSoundTheme.CREAMY;
        if (font == null) font = dev.kuro.client.builder.gui.GuiFont.JAKARTA;
        if (hud == null) hud = new HudSettings();
        hud.sanitize();
        storage.sanitize();
        spawner.sanitize();
        freecam.sanitize();
        freelook.sanitize();
        playerEsp.sanitize();
        autoTool.sanitize();
        autoBuilder.sanitize();
        chunkFinder.sanitize();
        clickGuiScale = Double.isFinite(clickGuiScale) ? Math.clamp(clickGuiScale, 0.25, 2.0) : 1.0;
        clickGuiX = Math.clamp(clickGuiX, -4096, 4096);
        clickGuiY = Math.clamp(clickGuiY, -4096, 4096);
        totalPlaytimeMillis = Math.max(0, totalPlaytimeMillis);
        accentColor &= 0xFFFFFF;
    }
}
