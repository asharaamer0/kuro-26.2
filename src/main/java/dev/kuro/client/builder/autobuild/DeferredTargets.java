package dev.kuro.client.builder.autobuild;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Remembers the targets the builder set aside, so the ones that may have become possible are tried
 * again without walking into the same dead end over and over.
 *
 * <p>A blocked position is only reconsidered while it still has attempts left and only when a
 * placement lands close enough to change its surroundings. Repeating every deferred position after
 * every unrelated placement is what made the engine look confused: it re-approached the same spots,
 * failed the same way and reported the same cause while the rest of the build stood still. Once a
 * position has used its attempts it stays aside until the build is resumed by hand, which also
 * reports it as the remaining reason instead of hiding it behind unrelated work.
 *
 * <p>That budget is deliberately generous, because a position parked for a few attempts is the one the
 * build most needs back: too small a budget shelved a block that a later placement would have freed, and
 * the walk then had to be finished by hand. Six attempts within three blocks is the balance - still bounded
 * and still neighbourhood-gated, so a genuinely impossible position is not approached forever.
 *
 * <p>An unloaded area is different: nothing about it is wrong, so any later progress re-queues it
 * while the player keeps walking and loading chunks around it. A placed block whose state still follows
 * its neighbours is the same kind of position: the neighbouring placements are exactly what resolves it,
 * so it is retried on later progress instead of being reported for a manual fix.
 */
final class DeferredTargets {
    /** Attempts a blocked position gets before it is left for a manual Resume. */
    static final int MAX_ATTEMPTS = 6;
    /** How close a placement has to be to make a blocked position worth retrying. */
    static final int RETRY_RADIUS = 3;

    /** Why a position was set aside; an unloaded area and a neighbour-derived state resolve by progress. */
    enum Cause { BLOCKED, AWAITING_CHUNK, AWAITING_NEIGHBOURS }

    private record Entry(Cause cause, int attempts) { }

    private final Map<BlockPos, Entry> entries = new HashMap<>();

    boolean isDeferred(BlockPos pos) { return entries.containsKey(pos); }

    /** Records one failure of a position and returns its accumulated attempt count. */
    int record(BlockPos pos, Cause cause) {
        Entry previous = entries.get(pos);
        int attempts = previous == null ? 1 : previous.attempts() + 1;
        entries.put(pos.immutable(), new Entry(cause, attempts));
        return attempts;
    }

    /** True while a position still has attempts left, so an empty queue may try it once more. */
    boolean retryable(BlockPos pos) {
        Entry entry = entries.get(pos);
        return entry == null || entry.attempts() < MAX_ATTEMPTS;
    }

    /** The deferred positions, in no particular order. */
    Set<BlockPos> positions() { return Set.copyOf(entries.keySet()); }

    /**
     * Returns the positions a placement can reasonably have changed and forgets them, so a retry
     * starts with a fresh attempt budget.
     */
    List<BlockPos> retryAfter(BlockPos placed) {
        List<BlockPos> retry = new ArrayList<>();
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Entry> entry = iterator.next();
            // Something the structure itself has to supply - a loaded chunk or the neighbouring blocks
            // that write a derived state - is re-queued by progress; a genuinely blocked position is not.
            boolean progressResolved = entry.getValue().cause() != Cause.BLOCKED;
            // A blocked position that used up its attempts is not worth another approach, no matter
            // how much building happens next to it.
            if (!progressResolved && (entry.getValue().attempts() >= MAX_ATTEMPTS
                    || distance(entry.getKey(), placed) > RETRY_RADIUS)) continue;
            retry.add(entry.getKey());
            iterator.remove();
        }
        return retry;
    }

    /** Forgets a position that no longer needs another approach. */
    void remove(BlockPos pos) { entries.remove(pos); }

    void clear() { entries.clear(); }

    private static int distance(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }
}
