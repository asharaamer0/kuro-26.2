package dev.kuro.client.builder.finder;

import net.minecraft.core.BlockPos;

public record FoundBlock(BlockPos pos, TargetKind kind) { }
