package dev.kuro.client.builder.autobuild;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Navigation supports are not usable until a server block packet confirms the requested block. */
final class SupportConfirmation {
    enum Outcome { WAITING, CONFIRMED, REJECTED, TIMED_OUT }
    private final Set<BlockPos> uncertain = new HashSet<>();
    private BlockPos position;
    private BlockState expected, observed;
    private long sentAt;

    void begin(BlockPos pos, BlockState expected, long tick) {
        if (position != null) throw new IllegalStateException("A support interaction is already pending");
        position = pos.immutable();
        this.expected = expected;
        observed = null;
        sentAt = tick;
        uncertain.add(position);
    }

    void observe(BlockPos pos, BlockState state) {
        // Even a late rejection resolves the uncertainty. The ordinary world checks still reject air.
        uncertain.remove(pos);
        if (pos.equals(position)) observed = state;
    }

    Outcome outcome(long tick) {
        if (position == null) return Outcome.WAITING;
        if (observed != null) return observed.getBlock() == expected.getBlock()
                ? Outcome.CONFIRMED : Outcome.REJECTED;
        return tick - sentAt >= 60 ? Outcome.TIMED_OUT : Outcome.WAITING;
    }

    boolean usable(BlockPos pos) { return !uncertain.contains(pos); }
    boolean pending() { return position != null; }
    BlockPos position() { return position; }
    void finish() { position = null; expected = null; observed = null; }
    void clear() { finish(); uncertain.clear(); }
}
