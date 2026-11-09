package dev.kuro.client.builder.config;

import com.google.gson.annotations.SerializedName;

/** Presentation preferences and the chosen material source; building always requires an explicit Start action. */
public final class AutoBuilderSettings {
    public boolean favorite = false;
    public double previewOpacity = .28;
    public boolean bridging = true;
    /** Depth-tested synchronization diagnostics; off by default. */
    public boolean syncDebug = false;
    /** Opt-in: a matching full stack in the auction listing rows may be purchased with in-game currency. */
    public boolean buyMaterialsFromAuction = false;
    public boolean auctionStackOnly = false;
    public boolean towerWhenStuck = true;
    public boolean chatLogs = true;
    public transient boolean protectRedstoneFromWater = true;
    public transient boolean flowTempBlocks = true;
    public transient boolean clearTempBlocks = true;
    public boolean allowSubstitutes = true;
    public boolean silentRotations = false;
    public boolean autoEat = true;
    public int eatAt = 14;
    public boolean fastPlace = true;
    public boolean localSweep = true;
    public int placesPerTick = 2;
    public int placementDelay = 0;
    public boolean sprint = true;
    public boolean sprintJump = true;
    public boolean autoMove = true;
    public boolean smoothRoutes = true;
    public double reach = 4.5;
    /**
     * The storage container the player chose as the material source, as {@code dimension@x,y,z}, or empty.
     * The value was called {@code materialShulker} when only shulker boxes could be chosen, so the old key
     * is still read and a choice made back then survives. An unreadable value is cleared here rather than
     * sending the builder to a position nobody chose.
     */
    @SerializedName(value = "materialStorage", alternate = "materialShulker")
    public String materialStorage = "";

    public void sanitize() {
        previewOpacity = Double.isFinite(previewOpacity) ? Math.clamp(previewOpacity, .08, .8) : .28;
        reach = Double.isFinite(reach) ? Math.clamp(reach, 1.0, 6.0) : 4.5;
        placesPerTick = Math.clamp(placesPerTick, 1, 8);
        placementDelay = Math.clamp(placementDelay, 0, 1000);
        eatAt = Math.clamp(eatAt, 6, 19);
        materialStorage = sanitizeMaterialStorage(materialStorage);
    }

    private static String sanitizeMaterialStorage(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() > 128 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0 ? "" : trimmed;
    }
}
