package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds the definitions of records inside other regions that run on an execution ({@link RegionRecordRunnerUtils#executeDecoded}),
 * keyed by region and offset. A record's definition is a function of its 2400 bytes, so a run reuses the definition last
 * decoded at the same place and decodes again only the conditions, actions and owner selectors whose bytes changed since
 * (self-modifying triggers rewrite their own records). In a region that tracks its changes
 * ({@link ClassicRegion#getChangeCount()}), only the parts of a record that changed since its last check are read again.
 */
final class DecodedRecordCache {
    /**
     * The mask of the low BYTE.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * The number of decodes held; the next decode beyond it drops them all.
     */
    private static final int CAPACITY = 1 << 14;
    /**
     * The record span checked for changes and read again as a unit in a region that tracks its changes.
     */
    private static final int CHANGE_WINDOW = 64;

    /**
     * The last decode of one record: its bytes, definition and execution state. The definition is decoded again from the
     * bytes, for the conditions, actions and owner selectors that changed, when {@link #getDefinition} needs it.
     */
    static final class DecodedRecord {
        final byte @NotNull [] bytes;
        private @NotNull TriggerDefinition definition;

        /**
         * The conditions, actions and owner selectors changed in {@link #bytes} since {@link #definition} was decoded.
         */
        private int pendingConditions;
        private long pendingActions;
        private boolean pendingOwners;

        /**
         * The started bits of the record's actions ({@link RunningTrigger#collectStartedActions}).
         */
        long startedActions;
        /**
         * The execution flags DWORD and the action cursor of {@link #bytes}.
         */
        int executionFlags;
        int currentActionIndex;

        /**
         * The region change count at which {@link #bytes} last matched the region, {@code -1} if untracked.
         */
        private long checkedChangeCount;

        /**
         * Creates a {@link DecodedRecord} instance whose started bits come from the definition and whose execution state
         * comes from the bytes.
         *
         * @param bytes      the record bytes the decode holds
         * @param definition the definition decoded from the bytes
         */
        DecodedRecord(byte @NotNull [] bytes, @NotNull TriggerDefinition definition) {
            this.bytes      = bytes;
            this.definition = definition;

            this.pendingConditions = 0;
            this.pendingActions    = 0;
            this.pendingOwners     = false;

            this.startedActions = RunningTrigger.collectStartedActions(definition);
            this.readExecutionState();

            this.checkedChangeCount = -1;
        }

        /**
         * @return the definition of the current {@link #bytes}, decoded again for the changes since the last decode
         */
        @NotNull TriggerDefinition getDefinition() {
            if (this.pendingConditions == 0 && this.pendingActions == 0 && !this.pendingOwners) {
                return this.definition;
            }

            TriggerDefinition.Condition[] conditions = null;

            if (this.pendingConditions != 0) {
                conditions = this.definition.copyConditions();

                for (int changed = this.pendingConditions; changed != 0; changed &= changed - 1) {
                    int index = Integer.numberOfTrailingZeros(changed);

                    conditions[index] = TriggerRecordCodec.decodeCondition(this.bytes, index * TriggerRecordCodec.CONDITION_SIZE);
                }
            }

            TriggerDefinition.Action[] actions = null;

            if (this.pendingActions != 0) {
                actions = this.definition.copyActions();

                for (long changed = this.pendingActions; changed != 0; changed &= changed - 1) {
                    int index = Long.numberOfTrailingZeros(changed);

                    actions[index] = TriggerRecordCodec.decodeAction(this.bytes,
                                                                     TriggerRecordCodec.ACTIONS_OFFSET + index * TriggerRecordCodec.ACTION_SIZE);
                }
            }

            byte[] owners = this.pendingOwners
                            ? Arrays.copyOfRange(this.bytes, TriggerRecordCodec.OWNERS_OFFSET, TriggerRecordCodec.ACTION_INDEX_OFFSET)
                            : null;

            this.definition        = this.definition.createReplacement(conditions, actions, owners);
            this.pendingConditions = 0;
            this.pendingActions    = 0;
            this.pendingOwners     = false;

            return this.definition;
        }


        /**
         * Notes the conditions, actions and owner selectors that changed in {@link #bytes}, leaving their decode to
         * {@link #getDefinition}, and takes the started bits and execution state from the bytes.
         *
         * @param changedConditions bit {@code index} set for every condition whose bytes changed
         * @param changedActions    bit {@code index} set for every action whose bytes changed
         * @param changedOwners     {@code true} if an owner selector byte changed
         */
        void applyChanges(int changedConditions, long changedActions, boolean changedOwners) {
            for (long changed = changedActions; changed != 0; changed &= changed - 1) {
                int index = Long.numberOfTrailingZeros(changed);
                int flags = this.bytes[TriggerRecordCodec.ACTIONS_OFFSET + index * TriggerRecordCodec.ACTION_SIZE +
                                       TriggerRecordCodec.ACTION_FLAGS_FIELD];

                this.startedActions = RunningTrigger.updateStartedAction(this.startedActions, index, flags);
            }

            this.pendingConditions |= changedConditions;
            this.pendingActions |= changedActions;
            this.pendingOwners |= changedOwners;

            this.readExecutionState();
        }

        /**
         * Sets the definition of a run that wrote the record's {@link #bytes} and takes the execution state from them.
         *
         * @param definition     the definition
         * @param changedActions bit {@code index} set for every action that may have been decoded again; the others are unchanged
         */
        void update(@NotNull TriggerDefinition definition, long changedActions) {
            this.getDefinition();

            if (definition != this.definition) {
                long startedActions = this.startedActions;

                for (long changed = changedActions; changed != 0; changed &= changed - 1) {
                    int index = Long.numberOfTrailingZeros(changed);

                    startedActions = RunningTrigger.updateStartedAction(startedActions, index, definition.getAction(index).getFlags());
                }

                this.definition     = definition;
                this.startedActions = startedActions;
            }

            this.readExecutionState();
        }

        private void readExecutionState() {
            this.executionFlags     = LittleEndianUtils.readInt32(this.bytes, TriggerRecordCodec.FLAGS_OFFSET);
            this.currentActionIndex = this.bytes[TriggerRecordCodec.ACTION_INDEX_OFFSET] & BYTE_MASK;
        }
    }

    /**
     * Maps record offsets to their last decode with open addressing.
     */
    static final class OffsetTable {
        /**
         * The initial number of slots, a power of two as the slot mask requires.
         */
        private static final int INITIAL_CAPACITY = 64;
        /**
         * The Fibonacci hashing multiplier, {@code 2^32} divided by the golden ratio.
         */
        private static final int HASH_MULTIPLIER = 0x9E3779B9;
        /**
         * The shift that folds the high half of the hashed key into its low half.
         */
        private static final int HASH_FOLD_SHIFT = Integer.SIZE / 2;

        /**
         * Spreads a key over the slot bits.
         *
         * @param key the record offset
         * @return the hashed key
         */
        private static int mix(int key) {
            int hash = key * HASH_MULTIPLIER;

            return hash ^ (hash >>> HASH_FOLD_SHIFT);
        }


        private int @NotNull [] keys;
        private @Nullable DecodedRecord @NotNull [] values;
        private int count;

        OffsetTable() {
            this.keys   = new int[INITIAL_CAPACITY];
            this.values = new DecodedRecord[INITIAL_CAPACITY];
            this.count  = 0;
        }

        @Nullable DecodedRecord get(int key) {
            int mask = this.keys.length - 1;

            for (int slot = mix(key) & mask; ; slot = (slot + 1) & mask) {
                DecodedRecord value = this.values[slot];

                if (value == null || this.keys[slot] == key) {
                    return value;
                }
            }
        }

        void put(int key, @NotNull DecodedRecord value) {
            if ((this.count + 1) * 2 > this.keys.length) {
                this.grow();
            }

            int mask = this.keys.length - 1;
            int slot = mix(key) & mask;

            while (this.values[slot] != null && this.keys[slot] != key) {
                slot = (slot + 1) & mask;
            }

            if (this.values[slot] == null) {
                this.count++;
            }

            this.keys[slot]   = key;
            this.values[slot] = value;
        }

        private void grow() {
            int[] oldKeys = this.keys;
            DecodedRecord[] oldValues = this.values;

            this.keys   = new int[oldKeys.length * 2];
            this.values = new DecodedRecord[oldKeys.length * 2];
            this.count  = 0;

            for (int index = 0; index < oldKeys.length; index++) {
                if (oldValues[index] != null) {
                    this.put(oldKeys[index], oldValues[index]);
                }
            }
        }
    }

    private final @NotNull Map<ClassicRegion, OffsetTable> byRegion;
    private int size;
    /**
     * The region of the last lookup and its table, tried before {@link #byRegion}.
     */
    private @Nullable ClassicRegion lastRegion;
    private @Nullable OffsetTable lastRecords;

    /**
     * The bytes read from a region for a check against the last decode.
     */
    private final byte @NotNull [] readBytes;
    /**
     * The conditions, actions and owner selectors that {@link #markChanges} found changed.
     */
    private int changedConditions;
    private long changedActions;
    private boolean changedOwners;

    /**
     * Creates an empty {@link DecodedRecordCache} instance.
     */
    DecodedRecordCache() {
        // Regions compare by identity; the insertion order keeps a saved game's order of the tables.
        this.byRegion    = new LinkedHashMap<>();
        this.size        = 0;
        this.lastRegion  = null;
        this.lastRecords = null;

        this.readBytes         = new byte[TriggerRecordCodec.RECORD_SIZE];
        this.changedConditions = 0;
        this.changedActions    = 0;
        this.changedOwners     = false;
    }

    /**
     * Finds the last decode of the record at a region offset and brings it up to date with the record's current
     * bytes.
     *
     * @param region the region holding the record
     * @param offset the record offset, with the whole record inside the region
     * @return the decode
     */
    @NotNull DecodedRecord findCurrent(@NotNull ClassicRegion region, int offset) {
        long changeCount = region.getChangeCount();
        OffsetTable records = region == this.lastRegion ? this.lastRecords : this.byRegion.get(region);
        DecodedRecord cached = records == null ? null : records.get(offset);

        if (cached != null && changeCount >= 0 && cached.checkedChangeCount >= 0) {
            if (changeCount != cached.checkedChangeCount) {
                int change = region.findChangeSince(offset, TriggerRecordCodec.RECORD_SIZE, cached.checkedChangeCount);

                if (change >= 0) {
                    this.readChanges(region, offset, change, cached);
                }

                cached.checkedChangeCount = changeCount;
            }

            this.lastRegion  = region;
            this.lastRecords = records;
        } else {
            byte[] bytes = this.readBytes;

            region.read(offset, bytes, 0, bytes.length);

            cached                    = this.decode(region, offset, bytes);
            cached.checkedChangeCount = changeCount;
        }

        return cached;
    }

    /**
     * Brings the last decode of a record in a tracked region up to date, reading only the windows from the changes
     * found since its last check.
     *
     * @param region the region holding the record
     * @param offset the record offset
     * @param change the record offset of the first change
     * @param cached the last decode of the record
     */
    private void readChanges(@NotNull ClassicRegion region, int offset, int change, @NotNull DecodedRecord cached) {
        byte[] bytes = this.readBytes;

        this.changedConditions = 0;
        this.changedActions    = 0;
        this.changedOwners     = false;

        for (int start = change; start >= 0; ) {
            int end = Math.min(start + CHANGE_WINDOW, TriggerRecordCodec.RECORD_SIZE);

            region.read(offset + start, bytes, start, end - start);
            this.markChanges(cached.bytes, bytes, start, end);
            System.arraycopy(bytes, start, cached.bytes, start, end - start);

            if (end == TriggerRecordCodec.RECORD_SIZE) {
                break;
            }

            int next = region.findChangeSince(offset + end, TriggerRecordCodec.RECORD_SIZE - end, cached.checkedChangeCount);

            start = next < 0 ? -1 : end + next;
        }

        cached.applyChanges(this.changedConditions, this.changedActions, this.changedOwners);
    }

    /**
     * Finds the last decode of a record by its bytes just read from the region, creating it when the record has none and
     * noting the conditions, actions and owner selectors whose bytes changed otherwise. The next decode beyond
     * {@link #CAPACITY} drops every decode first.
     *
     * @param region the region holding the record
     * @param offset the record offset
     * @param bytes  the record bytes read from the region
     * @return the decode
     */
    private @NotNull DecodedRecord decode(@NotNull ClassicRegion region, int offset, byte @NotNull [] bytes) {
        OffsetTable records = this.byRegion.get(region);

        if (records == null) {
            records = new OffsetTable();

            this.byRegion.put(region, records);
        }

        DecodedRecord cached = records.get(offset);

        if (cached == null) {
            if (++this.size > CAPACITY) {
                this.byRegion.clear();

                this.size = 1;
                records   = new OffsetTable();

                this.byRegion.put(region, records);
            }

            cached = new DecodedRecord(bytes.clone(), TriggerRecordCodec.decodeDefinition(bytes, 0));

            records.put(offset, cached);
        } else if (!Arrays.equals(cached.bytes, bytes)) {
            this.changedConditions = 0;
            this.changedActions    = 0;
            this.changedOwners     = false;

            this.markChanges(cached.bytes, bytes, 0, TriggerRecordCodec.RECORD_SIZE);
            System.arraycopy(bytes, 0, cached.bytes, 0, bytes.length);
            cached.applyChanges(this.changedConditions, this.changedActions, this.changedOwners);
        }

        this.lastRegion  = region;
        this.lastRecords = records;

        return cached;
    }

    /**
     * Marks the conditions, actions and owner selectors with a byte in a range that differs between two record images;
     * the execution flags and the action cursor are not part of the definition.
     *
     * @param previousBytes the record image of the last decode
     * @param bytes         the current record image
     * @param from          the first record offset compared
     * @param to            the end of the compared range
     */
    private void markChanges(byte @NotNull [] previousBytes, byte @NotNull [] bytes, int from, int to) {
        int position = from;

        while (position < to) {
            int found = Arrays.mismatch(previousBytes, position, to, bytes, position, to);

            if (found < 0) {
                break;
            }

            int changedOffset = position + found;

            if (changedOffset < TriggerRecordCodec.ACTIONS_OFFSET) {
                int index = changedOffset / TriggerRecordCodec.CONDITION_SIZE;

                this.changedConditions |= 1 << index;
                position = (index + 1) * TriggerRecordCodec.CONDITION_SIZE;
            } else if (changedOffset < TriggerRecordCodec.FLAGS_OFFSET) {
                int index = (changedOffset - TriggerRecordCodec.ACTIONS_OFFSET) / TriggerRecordCodec.ACTION_SIZE;

                this.changedActions |= 1L << index;
                position = TriggerRecordCodec.ACTIONS_OFFSET + (index + 1) * TriggerRecordCodec.ACTION_SIZE;
            } else if (changedOffset < TriggerRecordCodec.OWNERS_OFFSET) {
                position = TriggerRecordCodec.OWNERS_OFFSET;
            } else if (changedOffset < TriggerRecordCodec.ACTION_INDEX_OFFSET) {
                this.changedOwners = true;
                position           = TriggerRecordCodec.ACTION_INDEX_OFFSET;
            } else {
                break;
            }
        }
    }
}
