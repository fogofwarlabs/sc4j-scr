package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.save.SaveFileReader;
import org.fogofwarlabs.sc4j.scr.game.save.SaveReferences;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Retains one entry of the computer unit-record pools that a unit references: a guard, worker, building or military
 * record.
 * <p>
 * Every kind has a pool of {@code 1000} records linked as intrusive lists; the free and the active list of a pool share the
 * record's links. The kind distinguishes the records behind the shared unit reference.
 */
public final class ComputerUnitRecord {
    /**
     * The kind of a guard record, which keeps a unit at its original position.
     */
    public static final int KIND_GUARD = 1;
    /**
     * The kind of a worker record, which assigns a worker to a town.
     */
    static final int KIND_WORKER = 2;
    /**
     * The kind of a building record, which tracks a producer's build queue requests.
     */
    public static final int KIND_BUILDING = 3;
    /**
     * The kind of a military record, which links a combat unit to a region record.
     */
    public static final int KIND_MILITARY = 4;

    /**
     * The saved record sizes of the military, guard, worker and building pools.
     */
    static final int SAVED_MILITARY_SIZE = 0x14;
    static final int SAVED_GUARD_SIZE = 0x20;
    static final int SAVED_WORKER_SIZE = 0x18;
    static final int SAVED_BUILDING_SIZE = 0x2C;

    /**
     * The mask of unsigned BYTE fields read from saved records.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of unsigned WORD fields read from saved records.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * The fields every saved record starts with: the links as one-based indices and the kind BYTE.
     */
    private static final int SAVED_NEXT = 0x00;
    private static final int SAVED_PREVIOUS = 0x04;
    private static final int SAVED_KIND = 0x08;
    /**
     * The fields of a saved military record.
     */
    private static final int SAVED_MILITARY_UNIT = 0x0C;
    private static final int SAVED_MILITARY_REGION = 0x10;
    /**
     * The fields of a saved guard record.
     */
    private static final int SAVED_GUARD_RETRIES = 0x09;
    private static final int SAVED_GUARD_UNIT = 0x0C;
    private static final int SAVED_GUARD_TYPE = 0x10;
    private static final int SAVED_GUARD_X = 0x12;
    private static final int SAVED_GUARD_Y = 0x14;
    private static final int SAVED_GUARD_HOME_X = 0x16;
    private static final int SAVED_GUARD_HOME_Y = 0x18;
    private static final int SAVED_GUARD_REQUEST = 0x1C;
    /**
     * The fields of a saved worker record.
     */
    private static final int SAVED_WORKER_ASSIGNMENT = 0x09;
    private static final int SAVED_WORKER_STATE = 0x0A;
    private static final int SAVED_WORKER_DELAY = 0x0B;
    private static final int SAVED_WORKER_CLOCK = 0x0C;
    private static final int SAVED_WORKER_UNIT = 0x10;
    private static final int SAVED_WORKER_TOWN = 0x14;
    /**
     * The fields of a saved building record.
     */
    private static final int SAVED_BUILDING_QUEUE_KINDS = 0x09;
    private static final int SAVED_BUILDING_UNIT = 0x10;
    private static final int SAVED_BUILDING_TOWN = 0x14;
    private static final int SAVED_BUILDING_QUEUE_TARGETS = 0x18;

    /**
     * The multiplier of each record state-hash step.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * The state hash adds this to the pool index of a guard record queue target.
     */
    private static final int HASH_RECORD_TARGET_BASE = 1000;
    /**
     * The state hash adds this to the region record index of a region queue target.
     */
    private static final int HASH_REGION_TARGET_BASE = 2000;

    /**
     * @param record a record, or {@code null}
     * @return its one-based index in its pool, or {@code 0}
     */
    static int encodeSaveLink(@Nullable ComputerUnitRecord record) {
        return record == null ? 0 : record.poolIndex + 1;
    }

    /**
     * @param pool  the pool the value indexes
     * @param value a saved one-based index
     * @return the record, or {@code null} for {@code 0}
     * @throws UncheckedIOException if the value names no record of the pool
     */
    static @Nullable ComputerUnitRecord decodeSaveLink(@NotNull ComputerUnitRecord @NotNull [] pool, int value) {
        if (value == 0) {
            return null;
        }

        if (value < 0 || value > pool.length) {
            throw SaveFileReader.createFailure("A saved computer record reference {0} names no record of {1}.", value, pool.length);
        }

        return pool[value - 1];
    }


    private final int kind;
    private final int poolIndex;

    @Nullable ComputerUnitRecord previous;
    @Nullable ComputerUnitRecord next;

    @Nullable Unit unit;
    @Nullable ComputerTown town;

    /**
     * The worker's resource assignment, set to the mineral line when the worker is bound.
     */
    int workerAssignment;
    /**
     * The worker's state, cleared when the worker is bound.
     */
    int workerState;
    /**
     * The delay a worker counts down before any reassignment.
     */
    int workerDelay;
    /**
     * The worker's elapsed trigger-clock timestamp, refreshed on binding, activity and idle retry.
     */
    int lastUpdateClock;

    /**
     * The request kind recorded per build-queue slot of a building record.
     */
    public final byte @NotNull [] queueKinds;
    /**
     * The target recorded per build-queue slot of a building record: a {@link ComputerTown} for a town request, a guard
     * {@link ComputerUnitRecord} for a guard request and a boxed region record index for a region request.
     */
    public final @Nullable Object @NotNull [] queueTargets;

    /**
     * The military record's region record index {@code owner * regionCount + region}.
     */
    int regionIndex;

    /**
     * The guard's refill count, incremented up to a limit when its unit leaves.
     */
    int guardRetries;
    /**
     * The guarded unit type.
     */
    int guardType;
    /**
     * The current horizontal guard position.
     */
    public int guardX;
    /**
     * The current vertical guard position.
     */
    public int guardY;
    /**
     * The original guard position.
     */
    int homeX;
    int homeY;
    /**
     * Set to {@code 1} when a refill unit is requested.
     */
    int guardRequest;

    /**
     * Creates a {@link ComputerUnitRecord} instance without unit, town or region record.
     *
     * @param kind      the record kind: {@link #KIND_GUARD}, {@link #KIND_WORKER}, {@link #KIND_BUILDING} or {@link #KIND_MILITARY}
     * @param poolIndex the zero-based index in the record pool of the kind
     */
    public ComputerUnitRecord(int kind, int poolIndex) {
        this.kind      = kind;
        this.poolIndex = poolIndex;

        this.previous = null;
        this.next     = null;

        this.unit = null;
        this.town = null;

        this.workerAssignment = 0;
        this.workerState      = 0;
        this.workerDelay      = 0;
        this.lastUpdateClock  = 0;

        this.queueKinds   = new byte[Unit.BUILD_QUEUE_SLOTS];
        this.queueTargets = new Object[Unit.BUILD_QUEUE_SLOTS];

        this.regionIndex = -1;

        this.guardRetries = 0;
        this.guardType    = 0;
        this.guardX       = 0;
        this.guardY       = 0;
        this.homeX        = 0;
        this.homeY        = 0;
        this.guardRequest = 0;
    }

    /**
     * @return the record kind: {@link #KIND_GUARD}, {@link #KIND_WORKER}, {@link #KIND_BUILDING} or {@link #KIND_MILITARY}
     */
    public int getKind() {
        return this.kind;
    }

    /**
     * @return the zero-based index in the record pool of the kind
     */
    public int getPoolIndex() {
        return this.poolIndex;
    }

    /**
     * Returns the unit of a linked record: every record of a town's or a region's list holds its unit.
     *
     * @return the unit of the record
     * @throws IllegalStateException if the record holds no unit
     */
    @NotNull Unit requireUnit() {
        Unit unit = this.unit;

        if (unit == null) {
            throw Exceptions.newIllegalStateException("Computer record {0} of kind {1} holds no unit.", this.poolIndex, this.kind);
        }

        return unit;
    }

    /**
     * Returns the town in the town slot of the record. Worker and building records hold their town there; in a military
     * record the same slot holds the region record and in a guard record the guarded type and positions, which name no town.
     * Every routine that takes the town of a unit's record reads this slot whatever the kind of the record.
     *
     * @return the town of the record
     * @throws IllegalStateException         if a worker or building record has no town
     * @throws UnsupportedOperationException if the record is a military or guard record
     */
    @NotNull ComputerTown requireTown() {
        if (this.kind == KIND_MILITARY || this.kind == KIND_GUARD) {
            throw Exceptions.newUnsupportedOperationException("Computer record {0} of kind {1} holds no town.", this.poolIndex, this.kind);
        }

        ComputerTown town = this.town;

        if (town == null) {
            throw Exceptions.newIllegalStateException("Computer record {0} of kind {1} has no town.", this.poolIndex, this.kind);
        }

        return town;
    }


    /**
     * Clears the unit and region reference of a free military record before saving.
     */
    void clearFreeMilitary() {
        this.unit        = null;
        this.regionIndex = -1;
    }

    /**
     * Writes the record as a saved military record: the links as one-based indices, the kind ({@code 0} for a free
     * record), the unit handle and the region record ({@link ComputerRegions#encodeSaveReference}).
     *
     * @param data       the section, little-endian
     * @param offset     the record offset
     * @param free       whether the record is in the free list
     * @param regions    the region records the record references
     * @param references the unit references of the saved game
     */
    void writeSaveMilitary(@NotNull ByteBuffer data, int offset, boolean free, @NotNull ComputerRegions regions, @NotNull SaveReferences references) {
        data.putInt(offset + SAVED_NEXT, encodeSaveLink(this.next));
        data.putInt(offset + SAVED_PREVIOUS, encodeSaveLink(this.previous));
        data.put(offset + SAVED_KIND, (byte) (free ? 0 : this.kind));
        data.putInt(offset + SAVED_MILITARY_UNIT, references.encodeUnit(this.unit));
        data.putInt(offset + SAVED_MILITARY_REGION, regions.encodeSaveReference(this.regionIndex));
    }

    /**
     * Reads a saved military record that {@link #writeSaveMilitary} wrote; the region record is restored only for a
     * record with a unit.
     *
     * @param data       the section, little-endian
     * @param offset     the record offset
     * @param pool       the military record pool the links index
     * @param regions    the region records the record references
     * @param references the unit references of the restored game
     */
    void readSaveMilitary(@NotNull ByteBuffer data, int offset,
                          @NotNull ComputerUnitRecord @NotNull [] pool,
                          @NotNull ComputerRegions regions,
                          @NotNull SaveReferences references) {
        this.next        = decodeSaveLink(pool, data.getInt(offset + SAVED_NEXT));
        this.previous    = decodeSaveLink(pool, data.getInt(offset + SAVED_PREVIOUS));
        this.unit        = references.decodeUnit(data.getInt(offset + SAVED_MILITARY_UNIT));
        this.regionIndex = this.unit == null ? -1 : regions.decodeSaveReference(data.getInt(offset + SAVED_MILITARY_REGION));
    }

    /**
     * Clears the fields of a free guard record before saving: the unit, the type, the positions and the request; the retry
     * count is kept.
     */
    void clearFreeGuard() {
        this.unit         = null;
        this.guardType    = 0;
        this.guardX       = 0;
        this.guardY       = 0;
        this.homeX        = 0;
        this.homeY        = 0;
        this.guardRequest = 0;
    }

    /**
     * Writes the record as a saved guard record: the links, the kind ({@code 0} for a free record), the retry count, a
     * unit handle, the type, the current and the original position and the request.
     *
     * @param data       the section, little-endian
     * @param offset     the record offset
     * @param free       whether the record is in the free list
     * @param references the unit references of the saved game
     */
    void writeSaveGuard(@NotNull ByteBuffer data, int offset, boolean free, @NotNull SaveReferences references) {
        data.putInt(offset + SAVED_NEXT, encodeSaveLink(this.next));
        data.putInt(offset + SAVED_PREVIOUS, encodeSaveLink(this.previous));
        data.put(offset + SAVED_KIND, (byte) (free ? 0 : this.kind));
        data.put(offset + SAVED_GUARD_RETRIES, (byte) this.guardRetries);
        data.putInt(offset + SAVED_GUARD_UNIT, references.encodeUnit(this.unit));
        data.putShort(offset + SAVED_GUARD_TYPE, (short) this.guardType);
        data.putShort(offset + SAVED_GUARD_X, (short) this.guardX);
        data.putShort(offset + SAVED_GUARD_Y, (short) this.guardY);
        data.putShort(offset + SAVED_GUARD_HOME_X, (short) this.homeX);
        data.putShort(offset + SAVED_GUARD_HOME_Y, (short) this.homeY);
        data.putInt(offset + SAVED_GUARD_REQUEST, this.guardRequest);
    }

    /**
     * Reads a saved guard record that {@link #writeSaveGuard} wrote.
     *
     * @param data       the section, little-endian
     * @param offset     the record offset
     * @param pool       the guard record pool the links index
     * @param references the unit references of the restored game
     */
    void readSaveGuard(@NotNull ByteBuffer data, int offset, @NotNull ComputerUnitRecord @NotNull [] pool, @NotNull SaveReferences references) {
        this.next         = decodeSaveLink(pool, data.getInt(offset + SAVED_NEXT));
        this.previous     = decodeSaveLink(pool, data.getInt(offset + SAVED_PREVIOUS));
        this.guardRetries = data.get(offset + SAVED_GUARD_RETRIES) & BYTE_MASK;
        this.unit         = references.decodeUnit(data.getInt(offset + SAVED_GUARD_UNIT));
        this.guardType    = data.getShort(offset + SAVED_GUARD_TYPE) & WORD_MASK;
        this.guardX       = data.getShort(offset + SAVED_GUARD_X);
        this.guardY       = data.getShort(offset + SAVED_GUARD_Y);
        this.homeX        = data.getShort(offset + SAVED_GUARD_HOME_X);
        this.homeY        = data.getShort(offset + SAVED_GUARD_HOME_Y);
        this.guardRequest = data.getInt(offset + SAVED_GUARD_REQUEST);
    }

    /**
     * Clears a free worker record's assignment, state, delay, elapsed trigger-clock timestamp, unit and town before saving.
     */
    void clearFreeWorker() {
        this.workerAssignment = 0;
        this.workerState      = 0;
        this.workerDelay      = 0;
        this.lastUpdateClock  = 0;
        this.unit             = null;
        this.town             = null;
    }

    /**
     * Writes the record as a saved worker record: the links, the kind ({@code 0} for a free record), the assignment,
     * the state and the delay, the elapsed trigger-clock timestamp, the unit handle and the town (one-based index).
     *
     * @param data       the section, little-endian
     * @param offset     the record offset
     * @param free       whether the record is in the free list
     * @param references the unit references of the saved game
     */
    void writeSaveWorker(@NotNull ByteBuffer data, int offset, boolean free, @NotNull SaveReferences references) {
        data.putInt(offset + SAVED_NEXT, encodeSaveLink(this.next));
        data.putInt(offset + SAVED_PREVIOUS, encodeSaveLink(this.previous));
        data.put(offset + SAVED_KIND, (byte) (free ? 0 : this.kind));
        data.put(offset + SAVED_WORKER_ASSIGNMENT, (byte) this.workerAssignment);
        data.put(offset + SAVED_WORKER_STATE, (byte) this.workerState);
        data.put(offset + SAVED_WORKER_DELAY, (byte) this.workerDelay);
        data.putInt(offset + SAVED_WORKER_CLOCK, this.lastUpdateClock);
        data.putInt(offset + SAVED_WORKER_UNIT, references.encodeUnit(this.unit));
        data.putInt(offset + SAVED_WORKER_TOWN, ComputerTown.encodeSaveLink(this.town));
    }

    /**
     * Reads a saved worker record that {@link #writeSaveWorker} wrote.
     *
     * @param data       the section, little-endian
     * @param offset     the record offset
     * @param pool       the worker record pool the links index
     * @param towns      the town pool
     * @param references the unit references of the restored game
     */
    void readSaveWorker(@NotNull ByteBuffer data, int offset,
                        @NotNull ComputerUnitRecord @NotNull [] pool,
                        @NotNull ComputerTown @NotNull [] towns,
                        @NotNull SaveReferences references) {
        this.next             = decodeSaveLink(pool, data.getInt(offset + SAVED_NEXT));
        this.previous         = decodeSaveLink(pool, data.getInt(offset + SAVED_PREVIOUS));
        this.workerAssignment = data.get(offset + SAVED_WORKER_ASSIGNMENT) & BYTE_MASK;
        this.workerState      = data.get(offset + SAVED_WORKER_STATE) & BYTE_MASK;
        this.workerDelay      = data.get(offset + SAVED_WORKER_DELAY) & BYTE_MASK;
        this.lastUpdateClock  = data.getInt(offset + SAVED_WORKER_CLOCK);
        this.unit             = references.decodeUnit(data.getInt(offset + SAVED_WORKER_UNIT));
        this.town             = ComputerTown.decodeSaveLink(towns, data.getInt(offset + SAVED_WORKER_TOWN));
    }

    /**
     * Clears the fields of a free building record before saving: the queue kinds, the unit, the town and the queue targets;
     * the field after the queue kinds is kept.
     */
    void clearFreeBuilding() {
        Arrays.fill(this.queueKinds, (byte) 0);
        Arrays.fill(this.queueTargets, null);

        this.unit = null;
        this.town = null;
    }

    /**
     * Writes the record as a saved building record: the links, the kind ({@code 0} for a free record), the queue kinds,
     * the unit handle, the town and one DWORD per queue slot: the region record
     * ({@link ComputerRegions#encodeSaveReference}) of a region request, the one-based guard record of a guard request,
     * the one-based town of a town request and {@code 0} for the other kinds.
     *
     * @param data       the section, little-endian
     * @param offset     the record offset
     * @param free       whether the record is in the free list
     * @param regions    the region records the queue targets reference
     * @param references the unit references of the saved game
     */
    void writeSaveBuilding(@NotNull ByteBuffer data, int offset, boolean free, @NotNull ComputerRegions regions, @NotNull SaveReferences references) {
        data.putInt(offset + SAVED_NEXT, encodeSaveLink(this.next));
        data.putInt(offset + SAVED_PREVIOUS, encodeSaveLink(this.previous));
        data.put(offset + SAVED_KIND, (byte) (free ? 0 : this.kind));
        data.put(offset + SAVED_BUILDING_QUEUE_KINDS, this.queueKinds);
        data.putInt(offset + SAVED_BUILDING_UNIT, references.encodeUnit(this.unit));
        data.putInt(offset + SAVED_BUILDING_TOWN, ComputerTown.encodeSaveLink(this.town));

        for (int slot = 0; slot < this.queueKinds.length; slot++) {
            Object target = this.queueTargets[slot];
            int value = switch (this.queueKinds[slot]) {
                case ComputerPlayer.REQUEST_KIND_REGION -> target == null ? 0 : regions.encodeSaveReference((Integer) target);
                case ComputerPlayer.REQUEST_KIND_GUARD -> encodeSaveLink((ComputerUnitRecord) target);
                case ComputerPlayer.REQUEST_KIND_TOWN -> ComputerTown.encodeSaveLink((ComputerTown) target);
                default -> 0;
            };

            data.putInt(offset + SAVED_BUILDING_QUEUE_TARGETS + slot * Integer.BYTES, value);
        }
    }

    /**
     * Reads a saved building record that {@link #writeSaveBuilding} wrote; the queue targets of kinds other than the
     * region, guard and town requests are cleared.
     *
     * @param data       the section, little-endian
     * @param offset     the record offset
     * @param pool       the building record pool the links index
     * @param guards     the guard record pool of guard request targets
     * @param towns      the town pool
     * @param regions    the region records of region request targets
     * @param references the unit references of the restored game
     */
    void readSaveBuilding(@NotNull ByteBuffer data, int offset,
                          @NotNull ComputerUnitRecord @NotNull [] pool,
                          @NotNull ComputerUnitRecord @NotNull [] guards,
                          @NotNull ComputerTown @NotNull [] towns,
                          @NotNull ComputerRegions regions,
                          @NotNull SaveReferences references) {
        this.next     = decodeSaveLink(pool, data.getInt(offset + SAVED_NEXT));
        this.previous = decodeSaveLink(pool, data.getInt(offset + SAVED_PREVIOUS));

        data.get(offset + SAVED_BUILDING_QUEUE_KINDS, this.queueKinds);

        this.unit = references.decodeUnit(data.getInt(offset + SAVED_BUILDING_UNIT));
        this.town = ComputerTown.decodeSaveLink(towns, data.getInt(offset + SAVED_BUILDING_TOWN));

        for (int slot = 0; slot < this.queueKinds.length; slot++) {
            int value = data.getInt(offset + SAVED_BUILDING_QUEUE_TARGETS + slot * Integer.BYTES);

            this.queueTargets[slot] = switch (this.queueKinds[slot]) {
                case ComputerPlayer.REQUEST_KIND_REGION -> {
                    int region = regions.decodeSaveReference(value);

                    yield region < 0 ? null : Integer.valueOf(region);
                }
                case ComputerPlayer.REQUEST_KIND_GUARD -> decodeSaveLink(guards, value);
                case ComputerPlayer.REQUEST_KIND_TOWN -> ComputerTown.decodeSaveLink(towns, value);
                default -> null;
            };
        }
    }

    /**
     * Mixes the record's state into a state hash.
     *
     * @param hash the hash so far
     * @return the hash with the record's state
     * @throws ClassCastException if a nonnull queue target is not a town, a computer unit record or an {@link Integer} region index
     */
    public long computeStateHash(long hash) {
        hash = HASH_MULTIPLIER * hash + this.kind;
        hash = HASH_MULTIPLIER * hash + this.poolIndex;
        hash = HASH_MULTIPLIER * hash + (this.unit == null ? 0 : this.unit.getIndex());
        hash = HASH_MULTIPLIER * hash + (this.town == null ? -1 : this.town.getPoolIndex());
        hash = HASH_MULTIPLIER * hash + (this.previous == null ? -1 : this.previous.poolIndex);
        hash = HASH_MULTIPLIER * hash + (this.next == null ? -1 : this.next.poolIndex);
        hash = HASH_MULTIPLIER * hash + this.workerAssignment;
        hash = HASH_MULTIPLIER * hash + this.workerState;
        hash = HASH_MULTIPLIER * hash + this.workerDelay;

        for (int slot = 0; slot < Unit.BUILD_QUEUE_SLOTS; slot++) {
            hash = HASH_MULTIPLIER * hash + this.queueKinds[slot];

            Object target = this.queueTargets[slot];

            hash = HASH_MULTIPLIER * hash + (target == null ? -1 : target instanceof ComputerTown targetTown
                                                                   ? targetTown.getPoolIndex()
                                                                   : target instanceof ComputerUnitRecord record
                                                                     ? HASH_RECORD_TARGET_BASE + record.poolIndex
                                                                     : HASH_REGION_TARGET_BASE + (Integer) target);
        }

        hash = HASH_MULTIPLIER * hash + this.regionIndex;
        hash = HASH_MULTIPLIER * hash + this.guardRetries;
        hash = HASH_MULTIPLIER * hash + this.guardType;
        hash = HASH_MULTIPLIER * hash + this.guardX;
        hash = HASH_MULTIPLIER * hash + this.guardY;
        hash = HASH_MULTIPLIER * hash + this.homeX;
        hash = HASH_MULTIPLIER * hash + this.homeY;
        hash = HASH_MULTIPLIER * hash + this.guardRequest;

        return HASH_MULTIPLIER * hash + this.lastUpdateClock;
    }
}
