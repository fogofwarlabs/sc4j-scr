package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Retains one player's mutable execution flags and action cursor independently of its definition.
 * <p>
 * An action's started bit ({@link TriggerFlags#ACTION_STARTED}), which the Wait action sets when it starts a wait and
 * clears when it resumes, belongs to this execution. Bit {@code index} of {@link #startedActions} holds that bit of
 * action {@code index}, initialized from the record.
 * <p>
 * The 2400 record bytes are also the memory of the classic trigger node that the EUD emulation shows for this execution,
 * so {@link #readByte} and {@link #writeByte} serve them: the execution flags DWORD, the action cursor and the started
 * bits come from the execution state, every other byte from the record, and a write replaces the affected condition,
 * action or owner selector of this execution only.
 */
public final class RunningTrigger {
    /**
     * The mask of the low BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The multiplier that combines the fields of {@link #computeStateHash(int)}.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * Collects the started bits of a definition's actions.
     *
     * @param definition the record definition
     * @return bit {@code index} set for every action whose started bit is set
     */
    static long collectStartedActions(@NotNull TriggerDefinition definition) {
        long startedActions = 0;

        for (int index = 0; index < TriggerRecordCodec.ACTION_COUNT; index++) {
            if ((definition.getAction(index).getFlags() & TriggerFlags.ACTION_STARTED) != 0) {
                startedActions |= 1L << index;
            }
        }

        return startedActions;
    }

    /**
     * Takes the started bit of one action from its flags byte.
     *
     * @param startedActions the started bits
     * @param index          the action index
     * @param flags          the action flags byte
     * @return the started bits with bit {@code index} set if the flags hold {@link TriggerFlags#ACTION_STARTED}, cleared otherwise
     */
    static long updateStartedAction(long startedActions, int index, int flags) {
        return (flags & TriggerFlags.ACTION_STARTED) != 0 ? startedActions | 1L << index : startedActions & ~(1L << index);
    }

    /**
     * Tests whether a record offset is the flags byte of an action, which holds the action's started bit.
     *
     * @param offset the record offset
     * @return {@code true} if the offset is the flags byte of an action, {@code false} otherwise
     */
    private static boolean isActionFlagsByte(int offset) {
        return offset >= TriggerRecordCodec.ACTIONS_OFFSET && offset < TriggerRecordCodec.FLAGS_OFFSET &&
               (offset - TriggerRecordCodec.ACTIONS_OFFSET) % TriggerRecordCodec.ACTION_SIZE == TriggerRecordCodec.ACTION_FLAGS_FIELD;
    }

    @NotNull TriggerDefinition definition;
    int executionFlags;
    int currentActionIndex;
    long startedActions;

    /**
     * The record bytes, or {@code null} for an execution created without them (exported on the first write).
     */
    private byte @Nullable [] recordBytes;
    /**
     * Whether a write changed {@link #recordBytes}, which the caller may have supplied ({@link #setRecordBytes}).
     */
    private boolean recordBytesWritten;

    /**
     * Creates a {@link RunningTrigger} instance whose started bits come from the definition's action flags.
     *
     * @param definition         the record definition
     * @param executionFlags     the execution flags DWORD
     * @param currentActionIndex the action cursor
     */
    RunningTrigger(@NotNull TriggerDefinition definition, int executionFlags, int currentActionIndex) {
        this(definition, executionFlags, currentActionIndex, collectStartedActions(definition));
    }

    /**
     * Creates a {@link RunningTrigger} instance.
     *
     * @param definition         the record definition
     * @param executionFlags     the execution flags DWORD
     * @param currentActionIndex the action cursor
     * @param startedActions     the started bits, bit {@code index} for action {@code index}
     */
    RunningTrigger(@NotNull TriggerDefinition definition, int executionFlags, int currentActionIndex, long startedActions) {
        this.definition         = definition;
        this.executionFlags     = executionFlags;
        this.currentActionIndex = currentActionIndex;
        this.startedActions     = startedActions;

        this.recordBytes        = null;
        this.recordBytesWritten = false;
    }

    /**
     * Sets the record bytes this execution shows and changes in place.
     *
     * @param recordBytes the 2400 record bytes, matching {@link #definition}
     */
    void setRecordBytes(byte @NotNull [] recordBytes) {
        this.recordBytes = recordBytes;
    }

    /**
     * @return {@code true} if a write changed the record bytes, so {@link #definition} is decoded from the changed bytes, {@code false} otherwise
     */
    boolean isRecordBytesWritten() {
        return this.recordBytesWritten;
    }


    /**
     * Starts this execution over for another record, as a new execution with these values would be.
     *
     * @param definition         the record definition
     * @param executionFlags     the execution flags DWORD
     * @param currentActionIndex the action cursor
     * @param startedActions     the started bits, bit {@code index} for action {@code index}
     */
    void reset(@NotNull TriggerDefinition definition, int executionFlags, int currentActionIndex, long startedActions) {
        this.definition         = definition;
        this.executionFlags     = executionFlags;
        this.currentActionIndex = currentActionIndex;
        this.startedActions     = startedActions;

        this.recordBytes        = null;
        this.recordBytesWritten = false;
    }

    /**
     * Reads a byte of the node memory.
     *
     * @param offset the record offset, below {@link TriggerRecordCodec#RECORD_SIZE}
     * @return the unsigned byte the node memory shows
     */
    int readByte(int offset) {
        if (this.recordBytes == null || (offset >= TriggerRecordCodec.FLAGS_OFFSET && offset < TriggerRecordCodec.OWNERS_OFFSET) ||
            offset == TriggerRecordCodec.ACTION_INDEX_OFFSET || isActionFlagsByte(offset)) {
            return TriggerRecordCodec.readByte(this, offset);
        }

        return this.recordBytes[offset] & BYTE_MASK;
    }

    /**
     * Stores a byte of the node memory into this execution.
     *
     * @param offset the record offset, below {@link TriggerRecordCodec#RECORD_SIZE}
     * @param value  the byte in the low eight bits
     */
    void writeByte(int offset, int value) {
        byte[] bytes = this.getWritableRecordBytes();

        bytes[offset] = (byte) value;

        if (offset < TriggerRecordCodec.ACTIONS_OFFSET) {
            int index = offset / TriggerRecordCodec.CONDITION_SIZE;

            this.definition = this.definition.withCondition(index,
                                                            TriggerRecordCodec.decodeCondition(bytes, index * TriggerRecordCodec.CONDITION_SIZE));
        } else if (offset < TriggerRecordCodec.FLAGS_OFFSET) {
            int index = (offset - TriggerRecordCodec.ACTIONS_OFFSET) / TriggerRecordCodec.ACTION_SIZE;

            this.definition = this.definition.withAction(index,
                                                         TriggerRecordCodec.decodeAction(bytes,
                                                                                         TriggerRecordCodec.ACTIONS_OFFSET +
                                                                                         index * TriggerRecordCodec.ACTION_SIZE));

            if (isActionFlagsByte(offset)) {
                this.startedActions = updateStartedAction(this.startedActions, index, value);
            }
        } else if (offset < TriggerRecordCodec.OWNERS_OFFSET) {
            int shift = (offset - TriggerRecordCodec.FLAGS_OFFSET) * Byte.SIZE;

            this.executionFlags = LittleEndianUtils.replaceByte(this.executionFlags, shift, value);
        } else if (offset < TriggerRecordCodec.ACTION_INDEX_OFFSET) {
            this.definition = this.definition.withOwnerSelector(offset - TriggerRecordCodec.OWNERS_OFFSET, value);
        } else {
            this.currentActionIndex = value & BYTE_MASK;
        }
    }

    /**
     * Stores four consecutive bytes of the node memory with the effect of four {@link #writeByte} calls in address order:
     * the bytes are stored first and each condition and action they touch is decoded once from the final bytes.
     *
     * @param offset the record offset of the first byte, with four bytes inside the record
     * @param value  the bytes, little-endian
     */
    void writeDword(int offset, int value) {
        byte[] bytes = this.getWritableRecordBytes();
        int changedConditions = 0;
        long changedActions = 0;

        for (int index = 0; index < Integer.BYTES; index++) {
            int position = offset + index;
            int part = value >>> (index * Byte.SIZE);

            bytes[position] = (byte) part;

            if (position < TriggerRecordCodec.ACTIONS_OFFSET) {
                changedConditions |= 1 << (position / TriggerRecordCodec.CONDITION_SIZE);
            } else if (position < TriggerRecordCodec.FLAGS_OFFSET) {
                int action = (position - TriggerRecordCodec.ACTIONS_OFFSET) / TriggerRecordCodec.ACTION_SIZE;

                changedActions |= 1L << action;

                if (isActionFlagsByte(position)) {
                    this.startedActions = updateStartedAction(this.startedActions, action, part);
                }
            } else if (position < TriggerRecordCodec.OWNERS_OFFSET) {
                int shift = (position - TriggerRecordCodec.FLAGS_OFFSET) * Byte.SIZE;

                this.executionFlags = LittleEndianUtils.replaceByte(this.executionFlags, shift, part);
            } else if (position < TriggerRecordCodec.ACTION_INDEX_OFFSET) {
                this.definition = this.definition.withOwnerSelector(position - TriggerRecordCodec.OWNERS_OFFSET, part);
            } else {
                this.currentActionIndex = part & BYTE_MASK;
            }
        }

        for (int changed = changedConditions; changed != 0; changed &= changed - 1) {
            int index = Integer.numberOfTrailingZeros(changed);

            this.definition = this.definition.withCondition(index,
                                                            TriggerRecordCodec.decodeCondition(bytes, index * TriggerRecordCodec.CONDITION_SIZE));
        }

        for (long changed = changedActions; changed != 0; changed &= changed - 1) {
            int index = Long.numberOfTrailingZeros(changed);

            this.definition = this.definition.withAction(index,
                                                         TriggerRecordCodec.decodeAction(bytes,
                                                                                         TriggerRecordCodec.ACTIONS_OFFSET +
                                                                                         index * TriggerRecordCodec.ACTION_SIZE));
        }
    }

    /**
     * Adds the definition and execution state of this execution to a diagnostic hash.
     *
     * @param hash the preceding hash
     * @return the hash including this execution
     */
    int computeStateHash(int hash) {
        hash = this.definition.computeStateHash(hash);
        hash = HASH_MULTIPLIER * hash + this.executionFlags;
        hash = HASH_MULTIPLIER * hash + this.currentActionIndex;

        return HASH_MULTIPLIER * hash + Long.hashCode(this.startedActions);
    }

    /**
     * Returns the record bytes a write changes, exported on the first write of an execution created without them.
     *
     * @return the record bytes
     */
    private byte @NotNull [] getWritableRecordBytes() {
        byte[] bytes = this.recordBytes;

        if (bytes == null) {
            bytes            = TriggerRecordCodec.exportRecord(this);
            this.recordBytes = bytes;
        }

        this.recordBytesWritten = true;

        return bytes;
    }
}
