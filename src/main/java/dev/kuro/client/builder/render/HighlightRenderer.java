package dev.kuro.client.builder.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.kuro.client.builder.config.ClientConfig;
import dev.kuro.client.builder.chunkfinder.ChunkFinderScanner;
import dev.kuro.client.builder.autobuild.MaterialTracker;
import dev.kuro.client.builder.autobuild.AutoBuilderController;
import dev.kuro.client.builder.config.FinderSettings;
import dev.kuro.client.builder.config.RenderMode;
import dev.kuro.client.builder.gui.CliTheme;
import dev.kuro.client.builder.finder.FinderScanner;
import dev.kuro.client.builder.finder.FoundBlock;
import dev.kuro.client.builder.finder.TargetKind;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/** Batches finder overlays using the 26.2 render pipeline and render-state APIs. */
public final class HighlightRenderer implements AutoCloseable {
    private final RenderStateDataKey<RenderPayload> frame =
            RenderStateDataKey.create(() -> "Cli finder highlights");
    private static final RenderPipeline FILL_VISIBLE = pipeline("fill_visible", false, false);
    private static final RenderPipeline FILL_THROUGH = pipeline("fill_through", false, true);
    private static final RenderPipeline LINE_VISIBLE = pipeline("line_visible", true, false);
    private static final RenderPipeline LINE_THROUGH = pipeline("line_through", true, true);
    private static final Vector4f WHITE = new Vector4f(1, 1, 1, 1);
    private static final Vector3f ZERO = new Vector3f();
    private static final Matrix4f IDENTITY = new Matrix4f();

    // Corner bits are X=1, Y=2, Z=4. Winding is outward for all six faces.
    private static final int[][] FACES = {
            {0, 4, 6, 2}, {5, 1, 3, 7}, {4, 0, 1, 5},
            {2, 6, 7, 3}, {1, 0, 2, 3}, {4, 5, 7, 6}
    };

    private final ClientConfig config;
    private final FinderScanner scanner;
    private final ChunkFinderScanner chunkScanner;
    private final SchematicGhosts ghosts = new SchematicGhosts();
    private final Batch fillVisible = new Batch(FILL_VISIBLE);
    private final Batch fillThrough = new Batch(FILL_THROUGH);
    private final Batch lineVisible = new Batch(LINE_VISIBLE);
    private final Batch lineThrough = new Batch(LINE_THROUGH);
    private boolean registered;
    private boolean closed;

    public HighlightRenderer(ClientConfig config, FinderScanner scanner) {
        this(config, scanner, null);
    }

    public HighlightRenderer(ClientConfig config, FinderScanner scanner, ChunkFinderScanner chunkScanner) {
        this.config = config;
        this.scanner = scanner;
        this.chunkScanner = chunkScanner;
    }

    public void register() {
        if (registered) return;
        registered = true;
        LevelRenderEvents.END_EXTRACTION.register(this::extract);
        LevelRenderEvents.END_MAIN.register(this::draw);
        LevelRenderEvents.COLLECT_SUBMITS.register(this::submitNametags);
    }

    private static RenderPipeline pipeline(String name, boolean lines, boolean throughWalls) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("cli", "pipeline/" + name))
                .withVertexShader(lines ? "core/rendertype_lines" : "core/position_color")
                .withFragmentShader("core/position_color")
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.MATRICES_PROJECTION)
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.GLOBALS)
                .withVertexBinding(0, lines ? DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH
                        : DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(lines ? com.mojang.blaze3d.PrimitiveTopology.LINES : com.mojang.blaze3d.PrimitiveTopology.QUADS)
                .withColorTargetState(new com.mojang.blaze3d.pipeline.ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(!lines)
                .withDepthStencilState(throughWalls ? java.util.Optional.empty() : java.util.Optional.of(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)));
        if (lines) {
            // The vanilla line vertex shader expands each segment in screen space.
            // The position-color fragment shader keeps these overlays independent of fog.
            builder.withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.FOG);
        }
        return RenderPipelines.register(builder.build());
    }

    private void extract(LevelExtractionContext context) {
        FabricRenderState state = (FabricRenderState) context.levelState();
        if (closed) {
            state.setData(frame, RenderPayload.EMPTY);
            return;
        }

        Vec3 camera = context.levelState().cameraRenderState.pos;
        List<DrawBox> boxes = new ArrayList<>();
        List<DrawQuad> quads = new ArrayList<>();
        List<DrawLine> lines = new ArrayList<>();
        List<DrawNametag> nametags = new ArrayList<>();
        for (FoundBlock block : scanner.snapshot()) {
            FinderSettings settings = block.kind().isSpawner() ? config.spawner : config.storage;
            if (!settings.enabled || !block.kind().included(settings)) continue;
            double distance = Vec3.atCenterOf(block.pos()).distanceTo(camera);
            if (distance > settings.range) continue;

            if (!context.level().hasChunkAt(block.pos())) continue;
            var currentState = context.level().getBlockState(block.pos());
            if (TargetKind.from(currentState) != block.kind()) continue;
            VoxelShape shape = currentState.getShape(context.level(), block.pos());
            if (shape.isEmpty()) continue;
            AABB bounds = shape.bounds().move(block.pos()).inflate(0.003);
            if (!context.levelState().cameraRenderState.cullFrustum.isVisible(bounds)) continue;

            double fade = settings.distanceFade ? fade(distance, settings.range) : 1.0;
            int color = color(settings, block.kind());
            int fill = settings.mode != RenderMode.OUTLINE ? argb(color, settings.fillOpacity * fade) : 0;
            int outline = settings.mode != RenderMode.FILL ? argb(color, settings.outlineOpacity * fade) : 0;
            if ((fill >>> 24) == 0 && (outline >>> 24) == 0) continue;
            // Subtract in double precision before making vertices, including near the world border.
            boxes.add(new DrawBox(bounds.move(-camera.x, -camera.y, -camera.z), fill, outline,
                    (float) Math.clamp(settings.lineWidth, 0.5, 8.0), settings.throughWalls));
        }
        if (chunkScanner != null && config.chunkFinder.enabled && config.chunkFinder.alpha > 0) {
            var settings = config.chunkFinder;
            for (var chunk : chunkScanner.snapshot()) {
                if (!context.level().hasChunk(chunk.chunkX(), chunk.chunkZ())) continue;
                double x = chunk.chunkX() * 16.0, z = chunk.chunkZ() * 16.0;
                double opacity = settings.alpha / 255.0;
                for (var layer : chunk.layers()) {
                    AABB bounds = new AABB(x, layer.minY() - .05, z,
                            x + 16, layer.maxY() + 1.05, z + 16);
                    if (!context.levelState().cameraRenderState.cullFrustum.isVisible(bounds)) continue;
                    boxes.add(new DrawBox(bounds.move(-camera.x, -camera.y, -camera.z),
                            argb(settings.color, opacity * .06), argb(settings.color, opacity), 1.0f, true));
                }
            }
        }
        if (config.autoBuilder.syncDebug) {
            for (var marker : AutoBuilderController.get().syncMarkers()) {
                if (Vec3.atCenterOf(marker.pos()).distanceToSqr(camera) > 48 * 48
                        || !MaterialTracker.isLoaded(context.level(),marker.pos())) continue;
                AABB bounds = new AABB(marker.pos()).inflate(.004);
                if (!context.levelState().cameraRenderState.cullFrustum.isVisible(bounds)) continue;
                boxes.add(new DrawBox(bounds.move(-camera.x,-camera.y,-camera.z),
                        argb(marker.color(),marker.color() == 0xED6464 ? .18 : .04),
                        argb(marker.color(),.9),1.4f,false));
            }
        }
        for (SchematicGhosts.Ghost ghost : ghosts.nearby(camera)) {
            boolean loaded = MaterialTracker.isLoaded(context.level(), ghost.pos());
            var actual = loaded ? context.level().getBlockState(ghost.pos()) : null;
            if (actual != null && actual.equals(ghost.state())) continue;
            boolean conflict = actual != null && !actual.canBeReplaced() && !actual.is(ghost.state().getBlock());
            int color = !loaded ? 0xB788FF : conflict ? 0xFF657B : ghost.state().getMapColor(context.level(), ghost.pos()).col;
            if (color == 0) color = config.accentColor;
            AABB bounds;
            if (ghost.state().isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                bounds = new AABB(ghost.pos()).inflate(.002);
            } else {
                VoxelShape shape = ghost.state().getShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                bounds = (shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds())
                        .move(ghost.pos()).inflate(.002);
            }
            if (!context.levelState().cameraRenderState.cullFrustum.isVisible(bounds)) continue;
            boxes.add(new DrawBox(bounds.move(-camera.x, -camera.y, -camera.z),
                    argb(color, config.autoBuilder.previewOpacity), argb(color, .8), 1.3f, false));
        }
        if (config.playerEsp != null && config.playerEsp.enabled && context.level() != null) {
            var settings = config.playerEsp;
            Minecraft client = Minecraft.getInstance();
            float partialTick = context.camera() != null ? context.deltaTracker().getGameTimeDeltaPartialTick(false) : 1.0f;
            for (var player : context.level().players()) {
                if (player == null || !player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
                if (player == client.player && !settings.self) continue;

                double distance = player.position().distanceTo(camera);
                if (distance > settings.range) continue;

                double px = net.minecraft.util.Mth.lerp(partialTick, player.xo, player.getX());
                double py = net.minecraft.util.Mth.lerp(partialTick, player.yo, player.getY());
                double pz = net.minecraft.util.Mth.lerp(partialTick, player.zo, player.getZ());
                double relX = px - camera.x;
                double relY = py - camera.y;
                double relZ = pz - camera.z;
                AABB raw = player.getBoundingBox();
                AABB bounds = new AABB(
                        raw.minX - player.getX() + px, raw.minY - player.getY() + py, raw.minZ - player.getZ() + pz,
                        raw.maxX - player.getX() + px, raw.maxY - player.getY() + py, raw.maxZ - player.getZ() + pz
                ).inflate(0.01);

                if (!context.levelState().cameraRenderState.cullFrustum.isVisible(bounds)) continue;

                double fade = settings.distanceFade ? fade(distance, settings.range) : 1.0;
                int baseColor = settings.boxGuiColor ? (config.accentColor & 0xFFFFFF) : (settings.color & 0xFFFFFF);
                int fill = settings.mode != RenderMode.OUTLINE ? argb(baseColor, settings.fillOpacity * fade) : 0;
                int outline = settings.mode != RenderMode.FILL ? argb(baseColor, settings.outlineOpacity * fade) : 0;
                float lineWidth = (float) Math.clamp(settings.lineWidth, 0.5, 6.0);
                boolean through = settings.throughWalls;

                if (settings.box3d && ((fill >>> 24) != 0 || (outline >>> 24) != 0)) {
                    boxes.add(new DrawBox(bounds.move(-camera.x, -camera.y, -camera.z), fill, outline,
                            lineWidth, through));
                }

                if (settings.box2d) {
                    float height = player.getBbHeight();
                    float width = player.getBbWidth();
                    float halfW = width * 0.65f;
                    float minY = (float) relY - 0.04f;
                    float maxY = (float) relY + height + 0.08f;

                    double horizDist = Math.sqrt(relX * relX + relZ * relZ);
                    float rx = horizDist > 0.001 ? (float) (-relZ / horizDist) : 1.0f;
                    float rz = horizDist > 0.001 ? (float) (relX / horizDist) : 0.0f;

                    Vector3f bLeft = new Vector3f((float) (relX - rx * halfW), minY, (float) (relZ - rz * halfW));
                    Vector3f bRight = new Vector3f((float) (relX + rx * halfW), minY, (float) (relZ + rz * halfW));
                    Vector3f tRight = new Vector3f((float) (relX + rx * halfW), maxY, (float) (relZ + rz * halfW));
                    Vector3f tLeft = new Vector3f((float) (relX - rx * halfW), maxY, (float) (relZ - rz * halfW));

                    // 2D Box Fill
                    if (settings.mode != RenderMode.OUTLINE && (fill >>> 24) != 0) {
                        quads.add(new DrawQuad(bLeft, bRight, tRight, tLeft, fill, fill, fill, fill, through));
                    }

                    // 2D Box Outline
                    if (settings.mode != RenderMode.FILL && (outline >>> 24) != 0) {
                        int shadow = argb(0x000000, 0.70 * fade);
                        float shadowW = lineWidth + 1.2f;
                        // Drop shadow outline
                        lines.add(new DrawLine(bLeft, bRight, shadow, shadowW, through));
                        lines.add(new DrawLine(bRight, tRight, shadow, shadowW, through));
                        lines.add(new DrawLine(tRight, tLeft, shadow, shadowW, through));
                        lines.add(new DrawLine(tLeft, bLeft, shadow, shadowW, through));
                        // Foreground colored outline
                        lines.add(new DrawLine(bLeft, bRight, outline, lineWidth, through));
                        lines.add(new DrawLine(bRight, tRight, outline, lineWidth, through));
                        lines.add(new DrawLine(tRight, tLeft, outline, lineWidth, through));
                        lines.add(new DrawLine(tLeft, bLeft, outline, lineWidth, through));
                    }

                    // Side Health Bar
                    if (settings.healthBar) {
                        float gap = 0.08f;
                        float barW = 0.05f;
                        Vector3f hBottomRight = new Vector3f(bLeft.x() - rx * gap, minY, bLeft.z() - rz * gap);
                        Vector3f hBottomLeft = new Vector3f(bLeft.x() - rx * (gap + barW), minY, bLeft.z() - rz * (gap + barW));
                        Vector3f hTopRight = new Vector3f(tLeft.x() - rx * gap, maxY, tLeft.z() - rz * gap);
                        Vector3f hTopLeft = new Vector3f(tLeft.x() - rx * (gap + barW), maxY, tLeft.z() - rz * (gap + barW));

                        // Health bar dark backdrop & border
                        int bgCol = argb(0x0A0A10, 0.65 * fade);
                        int borderCol = argb(0x000000, 0.80 * fade);
                        quads.add(new DrawQuad(hBottomLeft, hBottomRight, hTopRight, hTopLeft, bgCol, bgCol, bgCol, bgCol, through));
                        lines.add(new DrawLine(hBottomLeft, hBottomRight, borderCol, 1.0f, through));
                        lines.add(new DrawLine(hBottomRight, hTopRight, borderCol, 1.0f, through));
                        lines.add(new DrawLine(hTopRight, hTopLeft, borderCol, 1.0f, through));
                        lines.add(new DrawLine(hTopLeft, hBottomLeft, borderCol, 1.0f, through));

                        // Filled health portion
                        float maxHp = Math.max(1.0f, player.getMaxHealth());
                        float curHp = Math.clamp(player.getHealth() + player.getAbsorptionAmount(), 0.0f, maxHp);
                        float hpRatio = curHp / maxHp;
                        if (hpRatio > 0.01f) {
                            float curTopY = minY + (maxY - minY) * hpRatio;
                            Vector3f curTopRight = new Vector3f(hBottomRight.x(), curTopY, hBottomRight.z());
                            Vector3f curTopLeft = new Vector3f(hBottomLeft.x(), curTopY, hBottomLeft.z());

                            int topRgb, bottomRgb;
                            if (settings.gradientHealth) {
                                topRgb = healthTopColor(hpRatio, settings.boxGuiColor ? (config.accentColor & 0xFFFFFF) : -1);
                                bottomRgb = healthBottomColor(settings.boxGuiColor ? (config.accentColor & 0xFFFFFF) : -1);
                            } else {
                                topRgb = healthTopColor(hpRatio, -1);
                                bottomRgb = topRgb;
                            }
                            int topCol = argb(topRgb, 0.95 * fade);
                            int bottomCol = argb(bottomRgb, 0.95 * fade);
                            quads.add(new DrawQuad(hBottomLeft, hBottomRight, curTopRight, curTopLeft,
                                    bottomCol, bottomCol, topCol, topCol, through));
                        }
                    }
                }

                // Custom Nametag (Billboarded, through walls, styled with selected font & GUI accent)
                if (settings.nametags) {
                    float height = player.getBbHeight();
                    float nametagY = (float) (relY + height + (settings.box2d ? 0.32f : 0.28f));
                    Vector3f tagPos = new Vector3f((float) relX, nametagY, (float) relZ);

                    int nameColor = settings.nametagGuiColor
                            ? (config.accentColor & 0xFFFFFF)
                            : (settings.color & 0xFFFFFF);

                    Identifier fontId = config.font != null ? config.font.id : null;
                    Style fontStyle = Style.EMPTY;
                    if (fontId != null) {
                        fontStyle = fontStyle.withFont(new FontDescription.Resource(fontId));
                    }

                    MutableComponent tag = Component.empty();

                    String name = player.getScoreboardName();
                    if (name == null || name.isEmpty()) name = player.getName().getString();
                    tag.append(Component.literal(name).withStyle(fontStyle.withColor(TextColor.fromRgb(nameColor))));

                    if (settings.nametagHealth) {
                        float health = player.getHealth() + player.getAbsorptionAmount();
                        int hp = (int) Math.ceil(health);
                        int hpColor = hp > 15 ? 0x55FF55 : hp > 8 ? 0xFFFF55 : 0xFF5555;
                        tag.append(Component.literal(" " + hp + "❤").withStyle(fontStyle.withColor(TextColor.fromRgb(hpColor))));
                    }

                    if (settings.nametagDistance) {
                        int dist = (int) Math.round(distance);
                        tag.append(Component.literal(" [" + dist + "m]").withStyle(fontStyle.withColor(TextColor.fromRgb(0xAAAAAA))));
                    }

                    float textWidth = client.font.width(tag);
                    nametags.add(new DrawNametag(tagPos, tag, textWidth, distance));
                }
            }
        }
        state.setData(frame, new RenderPayload(List.copyOf(boxes), List.copyOf(quads), List.copyOf(lines), List.copyOf(nametags)));
    }

    private static int healthTopColor(float ratio, int accent) {
        if (accent != -1) {
            return CliTheme.mix(0xFF3333, accent, Math.clamp(ratio, 0.0f, 1.0f));
        }
        if (ratio > 0.5f) {
            float p = (ratio - 0.5f) * 2.0f;
            return CliTheme.mix(0xFFFFDD33, 0xFF33FF55, p);
        } else {
            float p = ratio * 2.0f;
            return CliTheme.mix(0xFFFF2A2A, 0xFFFFDD33, p);
        }
    }

    private static int healthBottomColor(int accent) {
        if (accent != -1) {
            return CliTheme.mix(0xAA1111, accent, 0.25f);
        }
        return 0xFFFF2222;
    }

    private static int color(FinderSettings settings, TargetKind kind) {
        if (!settings.perTypeColors) return settings.color;
        return switch (kind) {
            case CHEST, TRAPPED_CHEST, ENDER_CHEST -> settings.chestColor;
            case SHULKER -> settings.shulkerColor;
            case DROPPER -> settings.dropperColor;
            case SPAWNER, TRIAL_SPAWNER -> settings.color;
        };
    }

    private static double fade(double distance, double range) {
        double progress = Math.clamp((distance / Math.max(1.0, range) - 0.5) * 2.0, 0.0, 1.0);
        return 1.0 - progress * progress * (3.0 - 2.0 * progress);
    }

    private static int argb(int rgb, double opacity) {
        return ((int) Math.round(Math.clamp(opacity, 0.0, 1.0) * 255.0) << 24) | (rgb & 0xFFFFFF);
    }

    private void draw(LevelRenderContext context) {
        if (closed) return;
        RenderPayload payload = ((FabricRenderState) context.levelState()).getData(frame);
        if (payload == null) return;
        PoseStack.Pose pose = context.poseStack().last();
        if (payload.boxes() != null) {
            for (DrawBox box : payload.boxes()) {
                if ((box.fill >>> 24) != 0) {
                    filled((box.throughWalls ? fillThrough : fillVisible).begin(), pose.pose(), box.bounds, box.fill);
                }
                if ((box.outline >>> 24) != 0) {
                    outlined((box.throughWalls ? lineThrough : lineVisible).begin(), pose, box.bounds, box.outline, box.width);
                }
            }
        }
        if (payload.quads() != null) {
            for (DrawQuad quad : payload.quads()) {
                BufferBuilder buffer = (quad.throughWalls ? fillThrough : fillVisible).begin();
                buffer.addVertex(pose.pose(), quad.v0.x(), quad.v0.y(), quad.v0.z()).setColor(quad.c0);
                buffer.addVertex(pose.pose(), quad.v1.x(), quad.v1.y(), quad.v1.z()).setColor(quad.c1);
                buffer.addVertex(pose.pose(), quad.v2.x(), quad.v2.y(), quad.v2.z()).setColor(quad.c2);
                buffer.addVertex(pose.pose(), quad.v3.x(), quad.v3.y(), quad.v3.z()).setColor(quad.c3);
            }
        }
        if (payload.lines() != null) {
            for (DrawLine line : payload.lines()) {
                BufferBuilder buffer = (line.throughWalls ? lineThrough : lineVisible).begin();
                float dx = line.to.x() - line.from.x();
                float dy = line.to.y() - line.from.y();
                float dz = line.to.z() - line.from.z();
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len > 0.0001f) { dx /= len; dy /= len; dz /= len; }
                buffer.addVertex(pose, line.from.x(), line.from.y(), line.from.z())
                        .setColor(line.color).setNormal(pose, dx, dy, dz).setLineWidth(line.width);
                buffer.addVertex(pose, line.to.x(), line.to.y(), line.to.z())
                        .setColor(line.color).setNormal(pose, dx, dy, dz).setLineWidth(line.width);
            }
        }
        // Drawing all fills before lines keeps translucent surfaces from dimming their own outlines.
        fillVisible.flush();
        fillThrough.flush();
        lineVisible.flush();
        lineThrough.flush();

    }

    private void submitNametags(LevelRenderContext context) {
        if (closed) return;
        RenderPayload payload = ((FabricRenderState) context.levelState()).getData(frame);
        if (payload == null) return;
        if (payload.nametags() != null && !payload.nametags().isEmpty()) {
            PoseStack matrices = context.poseStack();
            CameraRenderState camera = context.levelState().cameraRenderState;
            for (DrawNametag tag : payload.nametags()) {
                matrices.pushPose();
                try {
                    matrices.translate(tag.pos().x(), tag.pos().y(), tag.pos().z());
                    matrices.mulPose(camera.orientation);
                    float distanceScale = (float) Math.clamp(tag.distance() * 0.0035f, 0.022f, 0.09f);
                    matrices.scale(distanceScale, -distanceScale, distanceScale);
                    context.submitNodeCollector().submitText(matrices, -tag.textWidth() / 2.0f, 0,
                            tag.text().getVisualOrderText(), false, Font.DisplayMode.SEE_THROUGH,
                            0xF000F0, 0xFFFFFFFF, 0xB00C0C14, 0);
                } finally {
                    matrices.popPose();
                }
            }
        }
    }

    private static void filled(BufferBuilder buffer, Matrix4fc pose, AABB bounds, int color) {
        for (int[] face : FACES) {
            for (int corner : face) {
                buffer.addVertex(pose, x(bounds, corner), y(bounds, corner), z(bounds, corner)).setColor(color);
            }
        }
    }

    private static void outlined(BufferBuilder buffer, PoseStack.Pose pose, AABB bounds, int color, float width) {
        for (int corner = 0; corner < 8; corner++) {
            for (int axis = 1; axis <= 4; axis *= 2) {
                if ((corner & axis) != 0) continue;
                int other = corner | axis;
                float normalX = axis == 1 ? 1 : 0;
                float normalY = axis == 2 ? 1 : 0;
                float normalZ = axis == 4 ? 1 : 0;
                buffer.addVertex(pose, x(bounds, corner), y(bounds, corner), z(bounds, corner))
                        .setColor(color).setNormal(pose, normalX, normalY, normalZ).setLineWidth(width);
                buffer.addVertex(pose, x(bounds, other), y(bounds, other), z(bounds, other))
                        .setColor(color).setNormal(pose, normalX, normalY, normalZ).setLineWidth(width);
            }
        }
    }

    private static float x(AABB box, int corner) { return (float) ((corner & 1) == 0 ? box.minX : box.maxX); }
    private static float y(AABB box, int corner) { return (float) ((corner & 2) == 0 ? box.minY : box.maxY); }
    private static float z(AABB box, int corner) { return (float) ((corner & 4) == 0 ? box.minZ : box.maxZ); }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        fillVisible.close();
        fillThrough.close();
        lineVisible.close();
        lineThrough.close();
    }

    public record RenderPayload(List<DrawBox> boxes, List<DrawQuad> quads, List<DrawLine> lines, List<DrawNametag> nametags) {
        public static final RenderPayload EMPTY = new RenderPayload(List.of(), List.of(), List.of(), List.of());
    }
    public record DrawBox(AABB bounds, int fill, int outline, float width, boolean throughWalls) { }
    public record DrawQuad(Vector3f v0, Vector3f v1, Vector3f v2, Vector3f v3,
                           int c0, int c1, int c2, int c3, boolean throughWalls) { }
    public record DrawLine(Vector3f from, Vector3f to, int color, float width, boolean throughWalls) { }
    public record DrawNametag(Vector3f pos, Component text, float textWidth, double distance) { }

    /** A persistent CPU allocator and rotating GPU vertex buffer for one pipeline. */
    private static final class Batch implements AutoCloseable {
        private final RenderPipeline pipeline;
        private final ByteBufferBuilder allocator = new ByteBufferBuilder(64 * 1024);
        private BufferBuilder builder;
        private MappableRingBuffer vertices;

        private Batch(RenderPipeline pipeline) {
            this.pipeline = pipeline;
        }

        private BufferBuilder begin() {
            if (builder == null) builder = new BufferBuilder(allocator, pipeline.getPrimitiveTopology(), pipeline.getVertexFormatBinding(0));
            return builder;
        }

        private void flush() {
            if (builder == null) return;
            MeshData mesh = builder.buildOrThrow();
            builder = null;
            try (mesh) {
                int bytes = mesh.vertexBuffer().remaining();
                if (vertices == null || vertices.size() < bytes) {
                    if (vertices != null) vertices.close();
                    vertices = new MappableRingBuffer(() -> "Cli highlights", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, bytes);
                }
                try (GpuBufferSlice.MappedView mapped = vertices.currentBuffer().slice(0, bytes).map(false, true)) {
                    MemoryUtil.memCopy(mesh.vertexBuffer(), mapped.data());
                }

                GpuBuffer indices;
                com.mojang.blaze3d.IndexType indexType;
                if (pipeline.getPrimitiveTopology() == com.mojang.blaze3d.PrimitiveTopology.QUADS) {
                    mesh.sortQuads(allocator, RenderSystem.getProjectionType().vertexSorting());
                    indices = RenderSystem.getDevice().createBuffer(() -> "Kuro sorted indices", GpuBuffer.USAGE_INDEX, mesh.indexBuffer());
                    indexType = mesh.drawState().indexType();
                } else {
                    RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(pipeline.getPrimitiveTopology());
                    indices = sequential.getBuffer(mesh.drawState().indexCount());
                    indexType = sequential.type();
                }

                GpuBufferSlice transform = RenderSystem.getDynamicUniforms()
                        .writeTransform(RenderSystem.getModelViewMatrixCopy(), WHITE, ZERO, IDENTITY);
                var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
                var encoder = RenderSystem.getDevice().createCommandEncoder();
                try (RenderPass pass = encoder.createRenderPass(
                        () -> "Cli finder overlay", target.getColorTextureView(), java.util.Optional.empty(),
                        target.getDepthTextureView(), OptionalDouble.empty())) {
                    pass.setPipeline(pipeline);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", transform);
                    pass.setVertexBuffer(0, vertices.currentBuffer().slice());
                    pass.setIndexBuffer(indices, indexType);
                    pass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1, 0);
                }
                encoder.submit();
                if (pipeline.getPrimitiveTopology() == com.mojang.blaze3d.PrimitiveTopology.QUADS) indices.close();
                vertices.rotate();
            }
        }

        @Override
        public void close() {
            builder = null;
            allocator.close();
            if (vertices != null) {
                vertices.close();
                vertices = null;
            }
        }
    }
}
