package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.scr.game.save.SaveFileReader;
import org.fogofwarlabs.sc4j.scr.game.save.SaveReferences;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Retains one town of the computer town pool: its owner, position, worker and building lists, resource area, gas
 * buildings and build request table.
 * <p>
 * The build request table holds {@link #REQUEST_COUNT} entries of a flags BYTE (the count, the kind and the owner-count
 * flag packed as {@code count << 3 | kind << 1 | flag}), a priority BYTE and a type WORD.
 */
final class ComputerTown {
    /**
     * The mask of the low BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The multiplier that combines the fields of {@link #computeStateHash(int)}.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * The entries of the build request table.
     */
    static final int REQUEST_COUNT = 100;
    /**
     * The request kind of a unit or building.
     */
    static final int REQUEST_UNIT = 0;
    /**
     * The request kind of an upgrade level.
     */
    static final int REQUEST_UPGRADE = 1;
    /**
     * The request kind of a technology.
     */
    static final int REQUEST_TECHNOLOGY = 2;
    /**
     * The requested counts are below this: the count field of the flags BYTE has five bits.
     */
    static final int REQUEST_COUNT_LIMIT = 31;
    /**
     * The position of the count in the flags BYTE.
     */
    static final int REQUEST_COUNT_SHIFT = 3;
    /**
     * The position of the kind in the flags BYTE.
     */
    static final int REQUEST_KIND_SHIFT = 1;
    /**
     * The mask of the kind after {@link #REQUEST_KIND_SHIFT}.
     */
    static final int REQUEST_KIND_MASK = 0x3;
    /**
     * The flag bit of a request that counts all units of the owner instead of the town's units.
     */
    static final int REQUEST_COUNTS_OWNER_UNITS = 0x1;
    /**
     * The smallest flags BYTE of a used entry: a count of one.
     */
    static final int REQUEST_FIRST_COUNT = 1 << REQUEST_COUNT_SHIFT;
    /**
     * The gas building slots of a town.
     */
    static final int GAS_BUILDING_COUNT = 3;
    /**
     * The size of a saved town.
     */
    static final int SAVED_SIZE = 0x1CC;

    private static final int SAVED_NEXT = 0x00;
    private static final int SAVED_PREVIOUS = 0x04;
    private static final int SAVED_FIRST_WORKER = 0x0C;
    private static final int SAVED_FIRST_BUILDING = 0x14;
    private static final int SAVED_OWNER = 0x18;
    private static final int SAVED_BUILDINGS_LINKED = 0x19;
    private static final int SAVED_WORKER_COUNT = 0x1A;
    private static final int SAVED_DESIRED_WORKERS = 0x1B;
    private static final int SAVED_RESOURCE_AREA = 0x1C;
    private static final int SAVED_RESOURCE_AREA_PENDING = 0x1D;
    private static final int SAVED_REQUEST_DELAY = 0x1E;
    private static final int SAVED_POSITION_X = 0x20;
    private static final int SAVED_POSITION_Y = 0x22;
    private static final int SAVED_RESOURCE_DEPOT = 0x24;
    private static final int SAVED_RESERVED_WORKER = 0x28;
    private static final int SAVED_MINERAL_TARGET = 0x2C;
    private static final int SAVED_GAS_BUILDINGS = 0x30;
    private static final int SAVED_REQUESTS = 0x3C;
    private static final int SAVED_REQUEST_SIZE = 4;
    private static final int SAVED_REQUEST_PRIORITY = 1;
    private static final int SAVED_REQUEST_TYPE = Short.BYTES;
    /**
     * Clearing a free town before saving stops one entry short, as native does, so the last request stays.
     */
    private static final int CLEARED_REQUEST_COUNT = REQUEST_COUNT - 1;

    /**
     * @param town a town, or {@code null}
     * @return its one-based pool index, or {@code 0}
     */
    static int encodeSaveLink(@Nullable ComputerTown town) {
        return town == null ? 0 : town.poolIndex + 1;
    }

    /**
     * Resolves a saved one-based pool index.
     *
     * @param towns the town pool
     * @param value a saved one-based pool index
     * @return the town, or {@code null} for {@code 0}
     * @throws java.io.UncheckedIOException if the value names no town
     */
    static @Nullable ComputerTown decodeSaveLink(@NotNull ComputerTown @NotNull [] towns, int value) {
        if (value == 0) {
            return null;
        }

        if (value < 0 || value > towns.length) {
            throw SaveFileReader.createFailure("A saved computer town reference {0} names no town of {1}.", value, towns.length);
        }

        return towns[value - 1];
    }


    private final int poolIndex;

    @Nullable ComputerTown previous;
    @Nullable ComputerTown next;
    @Nullable ComputerUnitRecord firstWorker;
    @Nullable ComputerUnitRecord firstBuilding;

    int owner;
    /**
     * Set with the gas slot reset when the first building is linked.
     */
    boolean buildingsLinked;
    int workerCount;
    /**
     * The desired worker count of the town's resource area.
     */
    int desiredWorkers;
    /**
     * The resource area index supplied from script flags, zero for none.
     */
    int resourceArea;
    /**
     * Set when a resource area is supplied or a refinery is linked to an area town.
     */
    boolean resourceAreaPending;
    /**
     * While nonzero the town's unit requests wait; decremented by the town update. Town initialization leaves it
     * unchanged, so a reused town keeps the previous value.
     */
    int requestDelay;

    int positionX;
    int positionY;
    @Nullable Unit resourceDepot;
    @Nullable Unit reservedWorker;
    @Nullable Unit mineralTarget;
    final @Nullable Unit @NotNull [] gasBuildings;

    final byte @NotNull [] requestFlags;
    final byte @NotNull [] requestPriorities;
    final short @NotNull [] requestTypes;

    /**
     * Creates a {@link ComputerTown} instance for one pool entry.
     *
     * @param poolIndex the zero-based index in the town pool
     */
    ComputerTown(int poolIndex) {
        this.poolIndex = poolIndex;

        this.previous            = null;
        this.next                = null;
        this.firstWorker         = null;
        this.firstBuilding       = null;
        this.owner               = 0;
        this.buildingsLinked     = false;
        this.workerCount         = 0;
        this.desiredWorkers      = 0;
        this.resourceArea        = 0;
        this.resourceAreaPending = false;
        this.requestDelay        = 0;
        this.positionX           = 0;
        this.positionY           = 0;
        this.resourceDepot       = null;
        this.reservedWorker      = null;
        this.mineralTarget       = null;
        this.gasBuildings        = new Unit[GAS_BUILDING_COUNT];

        this.requestFlags      = new byte[REQUEST_COUNT];
        this.requestPriorities = new byte[REQUEST_COUNT];
        this.requestTypes      = new short[REQUEST_COUNT];
    }

    /**
     * @return the zero-based index in the town pool
     */
    int getPoolIndex() {
        return this.poolIndex;
    }


    /**
     * Initializes a town taken from the free list after its list insertion: sets the owner and position and clears the
     * fields and the request table; the request delay is kept.
     *
     * @param owner     the owning player
     * @param positionX the horizontal WORD
     * @param positionY the vertical WORD
     */
    void initialize(int owner, int positionX, int positionY) {
        this.owner               = owner;
        this.positionX           = (short) positionX;
        this.positionY           = (short) positionY;
        this.buildingsLinked     = false;
        this.workerCount         = 0;
        this.mineralTarget       = null;
        this.reservedWorker      = null;
        this.resourceDepot       = null;
        this.resourceArea        = 0;
        this.resourceAreaPending = false;
        this.desiredWorkers      = 0;

        Arrays.fill(this.gasBuildings, null);

        Arrays.fill(this.requestFlags, (byte) 0);
        Arrays.fill(this.requestPriorities, (byte) 0);
        Arrays.fill(this.requestTypes, (short) 0);
    }

    /**
     * Stores a build request of a computer owner in the first empty entry; a full table drops it.
     *
     * @param kind     the request kind ({@link #REQUEST_UNIT}, {@link #REQUEST_UPGRADE} or {@link #REQUEST_TECHNOLOGY})
     * @param count    the requested count below {@link #REQUEST_COUNT_LIMIT}
     * @param unitType the requested type
     * @param priority the priority BYTE
     * @param flag     {@link #REQUEST_COUNTS_OWNER_UNITS} or {@code 0}
     */
    void addRequest(int kind, int count, int unitType, int priority, int flag) {
        int index = 0;

        while (Byte.toUnsignedInt(this.requestFlags[index]) >= REQUEST_FIRST_COUNT) {
            if (index == REQUEST_COUNT - 1) {
                return;
            }

            index++;
        }

        this.requestTypes[index]      = (short) unitType;
        this.requestPriorities[index] = (byte) priority;
        this.requestFlags[index]      = (byte) (count << REQUEST_COUNT_SHIFT | (kind & REQUEST_KIND_MASK) << REQUEST_KIND_SHIFT |
                                                flag & REQUEST_COUNTS_OWNER_UNITS);
    }

    /**
     * Clears a free town before saving: the fields and every request but the last; the worker and building lists are
     * kept.
     */
    void clearFree() {
        this.owner               = 0;
        this.buildingsLinked     = false;
        this.workerCount         = 0;
        this.desiredWorkers      = 0;
        this.resourceArea        = 0;
        this.resourceAreaPending = false;
        this.requestDelay        = 0;

        this.positionX      = 0;
        this.positionY      = 0;
        this.resourceDepot  = null;
        this.reservedWorker = null;
        this.mineralTarget  = null;

        Arrays.fill(this.gasBuildings, null);

        Arrays.fill(this.requestFlags, 0, CLEARED_REQUEST_COUNT, (byte) 0);
        Arrays.fill(this.requestPriorities, 0, CLEARED_REQUEST_COUNT, (byte) 0);
        Arrays.fill(this.requestTypes, 0, CLEARED_REQUEST_COUNT, (short) 0);
    }

    /**
     * Writes the town as a saved town: the one-based links of the town list, the worker and building list heads, the
     * BYTE fields, the position WORDs, the unit handles of the resource depot, the reserved worker, the mineral target
     * and the gas buildings, then the request table.
     *
     * @param data       the section, little-endian
     * @param offset     the town offset
     * @param references the unit references of the saved game
     */
    void writeSaveRecord(@NotNull ByteBuffer data, int offset, @NotNull SaveReferences references) {
        data.putInt(offset + SAVED_NEXT, encodeSaveLink(this.next));
        data.putInt(offset + SAVED_PREVIOUS, encodeSaveLink(this.previous));
        data.putInt(offset + SAVED_FIRST_WORKER, ComputerUnitRecord.encodeSaveLink(this.firstWorker));
        data.putInt(offset + SAVED_FIRST_BUILDING, ComputerUnitRecord.encodeSaveLink(this.firstBuilding));
        data.put(offset + SAVED_OWNER, (byte) this.owner);
        data.put(offset + SAVED_BUILDINGS_LINKED, (byte) (this.buildingsLinked ? 1 : 0));
        data.put(offset + SAVED_WORKER_COUNT, (byte) this.workerCount);
        data.put(offset + SAVED_DESIRED_WORKERS, (byte) this.desiredWorkers);
        data.put(offset + SAVED_RESOURCE_AREA, (byte) this.resourceArea);
        data.put(offset + SAVED_RESOURCE_AREA_PENDING, (byte) (this.resourceAreaPending ? 1 : 0));
        data.put(offset + SAVED_REQUEST_DELAY, (byte) this.requestDelay);

        data.putShort(offset + SAVED_POSITION_X, (short) this.positionX);
        data.putShort(offset + SAVED_POSITION_Y, (short) this.positionY);
        data.putInt(offset + SAVED_RESOURCE_DEPOT, references.encodeUnit(this.resourceDepot));
        data.putInt(offset + SAVED_RESERVED_WORKER, references.encodeUnit(this.reservedWorker));
        data.putInt(offset + SAVED_MINERAL_TARGET, references.encodeUnit(this.mineralTarget));

        for (int slot = 0; slot < this.gasBuildings.length; slot++) {
            data.putInt(offset + SAVED_GAS_BUILDINGS + slot * Integer.BYTES, references.encodeUnit(this.gasBuildings[slot]));
        }

        for (int index = 0; index < REQUEST_COUNT; index++) {
            int request = offset + SAVED_REQUESTS + index * SAVED_REQUEST_SIZE;

            data.put(request, this.requestFlags[index]);
            data.put(request + SAVED_REQUEST_PRIORITY, this.requestPriorities[index]);
            data.putShort(request + SAVED_REQUEST_TYPE, this.requestTypes[index]);
        }
    }

    /**
     * Reads a saved town that {@link #writeSaveRecord} wrote.
     *
     * @param data       the section, little-endian
     * @param offset     the town offset
     * @param towns      the town pool the links index
     * @param workers    the worker record pool
     * @param buildings  the building record pool
     * @param references the unit references of the restored game
     */
    void readSaveRecord(@NotNull ByteBuffer data, int offset,
                        @NotNull ComputerTown @NotNull [] towns,
                        @NotNull ComputerUnitRecord @NotNull [] workers,
                        @NotNull ComputerUnitRecord @NotNull [] buildings,
                        @NotNull SaveReferences references) {
        this.next                = decodeSaveLink(towns, data.getInt(offset + SAVED_NEXT));
        this.previous            = decodeSaveLink(towns, data.getInt(offset + SAVED_PREVIOUS));
        this.firstWorker         = ComputerUnitRecord.decodeSaveLink(workers, data.getInt(offset + SAVED_FIRST_WORKER));
        this.firstBuilding       = ComputerUnitRecord.decodeSaveLink(buildings, data.getInt(offset + SAVED_FIRST_BUILDING));
        this.owner               = data.get(offset + SAVED_OWNER) & BYTE_MASK;
        this.buildingsLinked     = data.get(offset + SAVED_BUILDINGS_LINKED) != 0;
        this.workerCount         = data.get(offset + SAVED_WORKER_COUNT) & BYTE_MASK;
        this.desiredWorkers      = data.get(offset + SAVED_DESIRED_WORKERS) & BYTE_MASK;
        this.resourceArea        = data.get(offset + SAVED_RESOURCE_AREA) & BYTE_MASK;
        this.resourceAreaPending = data.get(offset + SAVED_RESOURCE_AREA_PENDING) != 0;
        this.requestDelay        = data.get(offset + SAVED_REQUEST_DELAY) & BYTE_MASK;

        this.positionX      = data.getShort(offset + SAVED_POSITION_X);
        this.positionY      = data.getShort(offset + SAVED_POSITION_Y);
        this.resourceDepot  = references.decodeUnit(data.getInt(offset + SAVED_RESOURCE_DEPOT));
        this.reservedWorker = references.decodeUnit(data.getInt(offset + SAVED_RESERVED_WORKER));
        this.mineralTarget  = references.decodeUnit(data.getInt(offset + SAVED_MINERAL_TARGET));

        for (int slot = 0; slot < this.gasBuildings.length; slot++) {
            this.gasBuildings[slot] = references.decodeUnit(data.getInt(offset + SAVED_GAS_BUILDINGS + slot * Integer.BYTES));
        }

        for (int index = 0; index < REQUEST_COUNT; index++) {
            int request = offset + SAVED_REQUESTS + index * SAVED_REQUEST_SIZE;

            this.requestFlags[index]      = data.get(request);
            this.requestPriorities[index] = data.get(request + SAVED_REQUEST_PRIORITY);
            this.requestTypes[index]      = data.getShort(request + SAVED_REQUEST_TYPE);
        }
    }

    /**
     * Includes the town in the state hash.
     *
     * @param hash the preceding diagnostic hash
     * @return the hash including this town
     */
    int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + this.poolIndex;
        hash = HASH_MULTIPLIER * hash + (this.previous == null ? -1 : this.previous.poolIndex);
        hash = HASH_MULTIPLIER * hash + (this.next == null ? -1 : this.next.poolIndex);
        hash = HASH_MULTIPLIER * hash + (this.firstWorker == null ? -1 : this.firstWorker.getPoolIndex());
        hash = HASH_MULTIPLIER * hash + (this.firstBuilding == null ? -1 : this.firstBuilding.getPoolIndex());
        hash = HASH_MULTIPLIER * hash + this.owner;
        hash = HASH_MULTIPLIER * hash + (this.buildingsLinked ? 1 : 0);
        hash = HASH_MULTIPLIER * hash + this.workerCount;
        hash = HASH_MULTIPLIER * hash + this.desiredWorkers;
        hash = HASH_MULTIPLIER * hash + this.resourceArea;
        hash = HASH_MULTIPLIER * hash + (this.resourceAreaPending ? 1 : 0);
        hash = HASH_MULTIPLIER * hash + this.requestDelay;
        hash = HASH_MULTIPLIER * hash + this.positionX;
        hash = HASH_MULTIPLIER * hash + this.positionY;
        hash = HASH_MULTIPLIER * hash + (this.resourceDepot == null ? 0 : this.resourceDepot.getIndex());
        hash = HASH_MULTIPLIER * hash + (this.reservedWorker == null ? 0 : this.reservedWorker.getIndex());
        hash = HASH_MULTIPLIER * hash + (this.mineralTarget == null ? 0 : this.mineralTarget.getIndex());

        for (Unit gas : this.gasBuildings) {
            hash = HASH_MULTIPLIER * hash + (gas == null ? 0 : gas.getIndex());
        }

        for (int index = 0; index < REQUEST_COUNT; index++) {
            hash = HASH_MULTIPLIER * hash + Byte.toUnsignedInt(this.requestFlags[index]);
            hash = HASH_MULTIPLIER * hash + Byte.toUnsignedInt(this.requestPriorities[index]);
            hash = HASH_MULTIPLIER * hash + this.requestTypes[index];
        }

        return hash;
    }
}
