package dev.kuro.client.builder.autobuild;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Client air and sequence acknowledgements alone never prove destruction. */
final class MiningConfirmation {
    private BlockPos position;
    private BlockState observed;
    private int started;
    void begin(BlockPos pos, int tick) { position = pos.immutable(); observed = null; started = tick; }
    void observe(BlockPos pos, BlockState state) { if (pos.equals(position)) observed = state; }
    boolean active(BlockPos pos) { return pos.equals(position); }
    boolean confirmedAir() { return observed != null && observed.isAir(); }
    boolean timedOut(int tick) { return position != null && tick - started >= 200; }
    void clear() { position = null; observed = null; }
}
