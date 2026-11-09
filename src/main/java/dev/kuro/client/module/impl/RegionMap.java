package dev.kuro.client.module.impl;

import dev.kuro.client.gui.HudEditScreen;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.FineText;
import dev.kuro.client.util.SdfText;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Region Map: displays the DonutSMP 50k region grid, region IDs, region zones,
 * and your real-time player position indicator.
 */
public class RegionMap extends Module implements HudElement {
    private static final int MAP_SIZE = 9;
    private static final double CELL_WORLD_SIZE = 50000.0;
    private static final double MAP_OFFSET = 225000.0;

    private static final String[] REGION_NAMES = {
            "EU Central", "EU West", "NA East", "NA West", "Asia", "Oceania"
    };

    private static final int[] REGION_COLORS = {
            0xFF9FCE63, // EU Central: rgb(159, 206, 99)
            0xFF00A663, // EU West:    rgb(0, 166, 99)
            0xFF4FADEA, // NA East:    rgb(79, 173, 234)
            0xFF2F6EBA, // NA West:    rgb(47, 110, 186)
            0xFFF5C242, // Asia:       rgb(245, 194, 66)
            0xFFFC8803  // Oceania:    rgb(252, 136, 3)
    };

    private static final int[] REGION_IDS = {
            82, 100, 101, 102, 103, 104, 105, 106, 91,
            83,  44,  75,  42,  41,  40,  39,  38, 92,
            84,  45,  14,  13,  12,  11,  10,  37, 93,
            85,  46,  74,   3,   2,   1,  25,  36, 94,
            86,  47,  72,  71,   5,   4,  24,  35, 95,
            87,  51,  17,   9,   8,   7,  23,  34, 96,
            88,  54,  18,  61,  62,  21,  22,  33, 97,
            89,  26,  27,  28,  29,  30,  59,  32, 98,
            90, 107, 108, 109, 110, 111, 112, 113, 99
    };

    private static final int[] REGION_TYPES = {
            5, 3, 3, 3, 2, 2, 2, 2, 2,
            5, 3, 3, 3, 2, 2, 2, 2, 2,
            5, 3, 3, 3, 2, 2, 2, 2, 2,
            5, 5, 5, 3, 2, 2, 2, 2, 2,
            4, 4, 4, 4, 2, 2, 2, 2, 2,
            4, 1, 1, 0, 0, 0, 0, 0, 2,
            4, 1, 1, 0, 0, 0, 0, 0, 0,
            0, 1, 0, 0, 0, 0, 0, 0, 0,
            0, 1, 1, 1, 1, 1, 1, 1, 0
    };

    public record Spot(int col, int row, int type) {}

    private final Setting.Num mapX = add(new Setting.Num("Position X", 15, 0, 4000, 1));
    private final Setting.Num mapY = add(new Setting.Num("Position Y", 70, 0, 4000, 1));
    private final Setting.Num cellSize = add(new Setting.Num("Cell Size", 6, 1, 56, 1));
    private final Setting.Num transparency = add(new Setting.Num("Transparency", 0.78, 0.1, 1.0, 0.05));
    private final Setting.Bool purple = add(new Setting.Bool("Purple", false));
    private final Setting.Bool coordinates = add(new Setting.Bool("Coordinates", true));
    private final Setting.Bool legend = add(new Setting.Bool("Legend", true));
    private final Setting.Bool grid = add(new Setting.Bool("Grid", true));
    private final Setting.Bool playerIndicator = add(new Setting.Bool("Player Indicator", true));

    private final List<Spot> spots = initSpots();
    private int lastW = 60, lastH = 80;

    public RegionMap() {
        super("Region Map", Category.BASE);
    }

    @Override public Setting.Num hudX() { return mapX; }
    @Override public Setting.Num hudY() { return mapY; }
    @Override public int hudW() { return lastW; }
    @Override public int hudH() { return lastH; }
    @Override public String hudName() { return name; }

    @Override
    public void onHud(GuiGraphicsExtractor g) {
        if (mc.player == null && !HudEditScreen.editing()) return;

        int x = HudElement.clampX(mapX, lastW);
        int y = HudElement.clampY(mapY, lastH);
        int cell = cellSize.intValue();
        int mapSide = cell * MAP_SIZE;
        int alpha = (int) (transparency.value * 255.0);
        int bgAlpha = Math.max(30, alpha);

        // Background
        g.fill(x - 2, y - 2, x + mapSide + 2, y + mapSide + 2, (bgAlpha << 24) | 0x080A10);

        // Region cells
        boolean usePurple = purple.value;
        int inset = (grid.value && cell >= 4) ? 1 : 0;
        for (int row = 0; row < MAP_SIZE; ++row) {
            for (int col = 0; col < MAP_SIZE; ++col) {
                int idx = row * MAP_SIZE + col;
                int color;
                if (usePurple) {
                    color = (Math.max(45, alpha) << 24) | 0x12131C;
                } else {
                    int base = REGION_COLORS[REGION_TYPES[idx]];
                    color = (Math.max(0, Math.min(255, alpha)) << 24) | (base & 0x00FFFFFF);
                }
                g.fill(x + col * cell + inset, y + row * cell + inset, x + (col + 1) * cell, y + (row + 1) * cell, color);
            }
        }

        // Purple spot zones
        if (usePurple) {
            int spotInset = cell >= 10 ? 2 : (cell >= 4 ? 1 : 0);
            for (Spot spot : spots) {
                int spotColor = switch (spot.type()) {
                    case 3 -> (205 << 24) | 0xBA6EFF; // Glitched / no RTP spots
                    case 2 -> (215 << 24) | 0x408CFF; // Media / baltop
                    default -> (195 << 24) | 0xB0BCD0; // Other base spots
                };
                g.fill(x + spot.col() * cell + spotInset, y + spot.row() * cell + spotInset,
                        x + (spot.col() + 1) * cell - spotInset, y + (spot.row() + 1) * cell - spotInset, spotColor);
            }
        }

        // Grid lines
        if (grid.value && cell >= 3) {
            int gridColor = (230 << 24) | 0x191C26;
            for (int i = 0; i <= MAP_SIZE; ++i) {
                g.fill(x + i * cell, y, x + i * cell + 1, y + mapSide, gridColor);
                g.fill(x, y + i * cell, x + mapSide, y + i * cell + 1, gridColor);
            }
        }

        // Region ID numbers (shown when cells are large enough to fit readable text)
        if (cell >= 10) {
            float fontSize = Math.min(8.5f, cell * 0.7f);
            int textRgb = (235 << 24) | 0xEEF1FA;
            for (int row = 0; row < MAP_SIZE; ++row) {
                for (int col = 0; col < MAP_SIZE; ++col) {
                    String idStr = Integer.toString(REGION_IDS[row * MAP_SIZE + col]);
                    float strW = FineText.width(SdfText.Face.SANS, idStr, fontSize);
                    float tx = x + col * cell + (cell - strW) / 2f;
                    float ty = y + row * cell + (cell - fontSize) / 2f;
                    FineText.draw(g, SdfText.Face.SANS, idStr, tx, ty, fontSize, textRgb);
                }
            }
        }

        // Player indicator
        if (playerIndicator.value && mc.player != null) {
            double nx = (mc.player.getX() + MAP_OFFSET) / CELL_WORLD_SIZE;
            double nz = (mc.player.getZ() + MAP_OFFSET) / CELL_WORLD_SIZE;
            if (nx >= 0.0 && nx < MAP_SIZE && nz >= 0.0 && nz < MAP_SIZE) {
                int px = x + (int) Math.round(nx * (double) cell);
                int pz = y + (int) Math.round(nz * (double) cell);
                int pr = Math.max(1, Math.min(3, cell / 2));
                g.fill(px - pr, pz - pr, px + pr + 1, pz + pr + 1, 0xFFFF3746);
                if (pr >= 2) {
                    g.fill(px - 1, pz - pr - 2, px + 2, pz - pr, 0xFFFFD2D7);
                }
            }
        }

        // Bottom text: Coordinates & Legend
        int textY = y + mapSide + 7;
        int maxTextWidth = mapSide;

        if (coordinates.value && mc.player != null) {
            String coordStr = "Position: X: " + (int) mc.player.getX() + ", Z: " + (int) mc.player.getZ();
            FineText.draw(g, SdfText.Face.SANS, coordStr, x, textY, 8.5f, 0xFFFFFFFF);
            maxTextWidth = Math.max(maxTextWidth, (int) Math.ceil(FineText.width(SdfText.Face.SANS, coordStr, 8.5f)));
            textY += 13;
        }

        if (legend.value) {
            if (usePurple) {
                textY = drawLegendItem(g, x, textY, 0xFFBA6EFF, "Glitched / no RTP spots");
                textY = drawLegendItem(g, x, textY, 0xFF408CFF, "Media / baltop");
                textY = drawLegendItem(g, x, textY, 0xFFB0BCD0, "Other base spots");
                maxTextWidth = Math.max(maxTextWidth, (int) Math.ceil(FineText.width(SdfText.Face.SANS, "Glitched / no RTP spots", 8.5f)) + 15);
            } else {
                for (int i = 0; i < REGION_NAMES.length; ++i) {
                    textY = drawLegendItem(g, x, textY, REGION_COLORS[i], REGION_NAMES[i]);
                    maxTextWidth = Math.max(maxTextWidth, (int) Math.ceil(FineText.width(SdfText.Face.SANS, REGION_NAMES[i], 8.5f)) + 15);
                }
            }
        }

        lastW = Math.max(mapSide + 4, maxTextWidth + 4);
        lastH = Math.max(mapSide + 4, (textY - y) + 2);
    }

    private int drawLegendItem(GuiGraphicsExtractor g, int x, int y, int color, String label) {
        g.fill(x, y + 1, x + 8, y + 9, color);
        FineText.draw(g, SdfText.Face.SANS, label, x + 12, y, 8.5f, 0xFFFFFFFF);
        return y + 12;
    }

    private static List<Spot> initSpots() {
        List<Spot> list = new ArrayList<>();
        addSpot(list, 3, -200000.0, 200000.0);
        addSpot(list, 3, -200000.0, 150000.0);
        addSpot(list, 3, -100000.0, -200000.0);
        addSpot(list, 3, 0.0, -200000.0);
        addSpot(list, 3, 50000.0, -200000.0);
        addSpot(list, 3, 200000.0, 100000.0);
        addSpot(list, 2, 0.0, 0.0);
        addSpot(list, 2, 50000.0, 0.0);
        addSpot(list, 2, 0.0, 50000.0);
        addSpot(list, 2, 50000.0, 150000.0);

        int[][] defaultSpots = {
                {-200000, 0}, {-200000, 100000}, {0, -50000}, {50000, 50000},
                {150000, 50000}, {-50000, 150000}, {0, 150000}, {100000, 150000},
                {150000, 150000}, {200000, 150000}, {-50000, 200000}, {100000, 200000},
                {150000, 200000}, {200000, 200000}
        };
        for (int[] p : defaultSpots) {
            addSpot(list, 1, p[0], p[1]);
        }
        return list;
    }

    private static void addSpot(List<Spot> list, int type, double x, double z) {
        int col = (int) Math.floor((x + MAP_OFFSET) / CELL_WORLD_SIZE);
        int row = (int) Math.floor((z + MAP_OFFSET) / CELL_WORLD_SIZE);
        if (col < 0 || col >= MAP_SIZE || row < 0 || row >= MAP_SIZE) return;
        list.removeIf(s -> s.col() == col && s.row() == row && s.type() <= type);
        boolean existsBetter = list.stream().anyMatch(s -> s.col() == col && s.row() == row && s.type() > type);
        if (existsBetter) return;
        list.add(new Spot(col, row, type));
    }
}
