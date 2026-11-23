package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Walks the classic trigger lists of the EUD emulation: the list heads and nodes of {@link ClassicTriggerMirror} and the
 * records that links name inside other regions.
 */
final class ClassicTriggerWalkUtils {
    /**
     * The mask that reads a DWORD as an unsigned value.
     */
    private static final long DWORD_MASK = 0xFFFFFFFFL;

    /**
     * The size of a link and the next-link DWORD after it.
     */
    private static final int LINK_PAIR_SIZE = 2 * Integer.BYTES;

    /**
     * Returns the classic trigger lists of the context's EUD address space.
     *
     * @param context the trigger context
     * @return the classic trigger lists, or {@code null} when the extended Deaths operations are not that address space (a supplied memory model walks the running-trigger lists instead)
     */
    static @Nullable ClassicTriggerMirror getMirror(@NotNull TriggerContext context) {
        return ExtendedDeathsUtils.getExtendedDeathsOperations(context) instanceof ClassicAddressSpace ? context.triggerMirror : null;
    }

    /**
     * Walks a player's EUD trigger list from its first link. Positive even links select a mirrored node, a record inside
     * another region or a zeroed fallback record. The following link is read after execution; an unavailable read ends
     * this player's walk without stopping trigger evaluation.
     *
     * @param context the trigger context
     * @param mirror  the classic trigger lists of the context ({@link #getMirror})
     * @param player  the list owner
     */
    static void evaluateMirroredPlayer(@NotNull TriggerContext context, @NotNull ClassicTriggerMirror mirror, int player) {
        ClassicAddressSpace space = (ClassicAddressSpace) ExtendedDeathsUtils.getExtendedDeathsOperations(context);
        int link = mirror.getFirstLink(player);
        int recordIndex = 0;
        int[] next = context.linkScratch;
        WalkCache cache = context.walkCaches[player];

        cache.validate(space.getLayoutVersion());

        while (!context.isEvaluationStopped()) {
            if (link <= 0 || (link & 1) != 0) {
                return;
            }

            if (cache.holds(recordIndex, link)) {
                // The record that ran at this position from the same link, found without the lookup; the link DWORD after
                // it lies in its region.
                ClassicRegion region = cache.getRegion(recordIndex);
                int linkOffset = cache.getLinkOffset(recordIndex);
                RunningTrigger node = cache.getNode(recordIndex);

                if (node == null) {
                    RegionRecordRunnerUtils.runRegionRecord(context, region, linkOffset + ClassicTriggerMirror.RECORD_OFFSET, player, recordIndex);
                } else {
                    ScrTriggerSystem.runRecord(context, node, player, recordIndex, cache.getNodeConditions(recordIndex));
                }

                recordIndex++;
                link = region.readDword(linkOffset + Integer.BYTES);

                continue;
            }

            ClassicRegion region = space.findRegion(link);
            RunningTrigger node = region == null ? null : ClassicTriggerMirror.getNodeTrigger(region);
            boolean cacheable = region != null && (link & ClassicRegion.DWORD_OFFSET_MASK) == 0 &&
                                (link & DWORD_MASK) + LINK_PAIR_SIZE <= (region.getBase() & DWORD_MASK) + region.getSize();

            if (node != null) {
                TriggerConditions.ConditionRows conditions = cacheable && cache.storeNode(recordIndex, link, region, node)
                                                             ? cache.getNodeConditions(recordIndex)
                                                             : null;

                ScrTriggerSystem.runRecord(context, node, player, recordIndex, conditions);
            } else {
                long recordOffset = region == null ? -1 : (link & DWORD_MASK) - (region.getBase() & DWORD_MASK) + ClassicTriggerMirror.RECORD_OFFSET;

                if (region == null || recordOffset + TriggerRecordCodec.RECORD_SIZE > region.getSize()) {
                    ScrTriggerSystem.runRecord(context, context.requireFallbackTrigger(), player, recordIndex, null);
                } else {
                    if (cacheable) {
                        cache.storeRecord(recordIndex, link, region);
                    }

                    RegionRecordRunnerUtils.runRegionRecord(context, region, (int) recordOffset, player, recordIndex);
                }
            }

            recordIndex++;

            // The link DWORD after the record's own lies in the same region when it fits there (regions do not overlap and
            // no record runs now), so it is read from that region directly.
            long nextOffset = region == null ? -1 : (link & DWORD_MASK) + Integer.BYTES - (region.getBase() & DWORD_MASK);

            if ((link & ClassicRegion.DWORD_OFFSET_MASK) == 0 && nextOffset >= 0 && nextOffset + Integer.BYTES <= region.getSize()) {
                link = region.readDword((int) nextOffset);
            } else {
                if (!space.readQuietly(link + Integer.BYTES, next)) {
                    return;
                }

                link = next[0];
            }
        }
    }

    /**
     * Creates the walk caches of every player slot.
     *
     * @return one empty walk cache per player slot
     */
    static @NotNull WalkCache @NotNull [] createWalkCaches() {
        WalkCache[] caches = new WalkCache[PlayerTable.PLAYER_SLOTS];

        for (int player = 0; player < caches.length; player++) {
            caches[player] = new WalkCache();
        }

        return caches;
    }


    private ClassicTriggerWalkUtils() {
    }
}
