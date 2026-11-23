package org.fogofwarlabs.sc4j.scr.game.path;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Retains the path records of a game and their free list.
 * <p>
 * Game operations use the records {@code 0..1023}; each record retains {@code 48} route WORDs shared by its waypoints (two WORDs each) and the region
 * route after them. A free record keeps its one-based free-list link in the source position and its one-based identifier in the waypoint position;
 * allocation and release keep the other fields, so a reused record starts with the stale values of its previous path.
 */
public final class PathPool {
    /**
     * The path records of a game.
     */
    public static final int RECORD_COUNT = 1024;
    /**
     * The route WORDs of a record, shared by its waypoints and its region route.
     */
    private static final int TAIL_WORD_COUNT = 48;
    /**
     * The route WORDs of one waypoint: its X and Y coordinates.
     */
    private static final int WAYPOINT_WORDS = 2;
    /**
     * The route WORD of the Y coordinate in a waypoint, after its X WORD.
     */
    private static final int WAYPOINT_Y_WORD = 1;

    /**
     * The size of a saved path record.
     */
    public static final int SAVED_RECORD_SIZE = 0x80;
    /**
     * The size of the saved path records.
     */
    public static final int SAVED_SIZE = RECORD_COUNT * SAVED_RECORD_SIZE;
    /**
     * The path flag ({@link #getFlags(int)}) of a path whose unit received a new move target while it follows the path; the movement then enters its
     * new-move-target state.
     */
    public static final int FLAG_NEW_MOVE_TARGET = 0x1;

    /**
     * The length of the count array of {@link #allocateForRoute(short[])}: the region count, then the waypoint count.
     */
    static final int ROUTE_COUNTS_LENGTH = 2;
    /**
     * The index of the region count in the count array of {@link #allocateForRoute(short[])}.
     */
    static final int ROUTE_REGION_COUNT_INDEX = 0;
    /**
     * The index of the waypoint count in the count array of {@link #allocateForRoute(short[])}.
     */
    static final int ROUTE_WAYPOINT_COUNT_INDEX = 1;

    /**
     * The field offsets of a saved path record.
     */
    private static final int SAVED_SOURCE = 0x00;
    private static final int SAVED_WAYPOINT = 0x04;
    private static final int SAVED_DESTINATION = 0x08;
    private static final int SAVED_CREATION_FRAME = 0x0C;
    private static final int SAVED_LAST_COLLISION = 0x10;
    private static final int SAVED_SPEED = 0x14;
    private static final int SAVED_FLAGS = 0x18;
    private static final int SAVED_TIMER = 0x19;
    private static final int SAVED_SLIDE_DIRECTION = 0x1A;
    private static final int SAVED_FULL_REGION_COUNT = 0x1B;
    private static final int SAVED_REGION_COUNT = 0x1C;
    private static final int SAVED_REGION_INDEX = 0x1D;
    private static final int SAVED_WAYPOINT_COUNT = 0x1E;
    private static final int SAVED_WAYPOINT_INDEX = 0x1F;
    private static final int SAVED_ROUTE_CELLS = 0x20;

    /**
     * The shift of the Y WORD of a packed position.
     */
    private static final int WORD_BITS = 16;
    /**
     * The mask of one WORD of a packed position.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The multiplier of the diagnostic state hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    private final int @NotNull [] sourcePositionOrFreeLink;
    private final int @NotNull [] waypointPositionOrIdentifier;
    private final int @NotNull [] destinations;
    private final int @NotNull [] creationFrames;

    private final int @NotNull [] savedSpeeds;
    private final int @NotNull [] lastCollisionHandles;
    private final byte @NotNull [] slideDirections;
    private final byte @NotNull [] flags;
    private final byte @NotNull [] timers;

    private final byte @NotNull [] fullRegionCounts;
    private final byte @NotNull [] regionCounts;
    private final byte @NotNull [] regionIndices;
    private final byte @NotNull [] waypointCounts;
    private final byte @NotNull [] waypointIndices;
    private final short @NotNull [] routeCells;

    private int firstFreeIndex;

    /**
     * Creates a {@link PathPool} instance with every record free; the free list starts at the second record, so the first record is never allocated
     * before a release.
     */
    public PathPool() {
        this.sourcePositionOrFreeLink     = new int[RECORD_COUNT];
        this.waypointPositionOrIdentifier = new int[RECORD_COUNT];
        this.destinations                 = new int[RECORD_COUNT];
        this.creationFrames               = new int[RECORD_COUNT];

        this.savedSpeeds          = new int[RECORD_COUNT];
        this.lastCollisionHandles = new int[RECORD_COUNT];
        this.slideDirections      = new byte[RECORD_COUNT];
        this.flags                = new byte[RECORD_COUNT];
        this.timers               = new byte[RECORD_COUNT];

        this.fullRegionCounts = new byte[RECORD_COUNT];
        this.regionCounts     = new byte[RECORD_COUNT];
        this.regionIndices    = new byte[RECORD_COUNT];
        this.waypointCounts   = new byte[RECORD_COUNT];
        this.waypointIndices  = new byte[RECORD_COUNT];
        this.routeCells       = new short[RECORD_COUNT * TAIL_WORD_COUNT];

        this.firstFreeIndex = 1;

        for (int recordIndex = 0; recordIndex < RECORD_COUNT; recordIndex++) {
            int nextRecordIndex = recordIndex + 1;

            // Links and identifiers are one-based; the last record ends the free list with 0.
            this.sourcePositionOrFreeLink[recordIndex]     = recordIndex == RECORD_COUNT - 1 ? 0 : nextRecordIndex + 1;
            this.waypointPositionOrIdentifier[recordIndex] = recordIndex + 1;
        }
    }

    /**
     * @return the zero-based free head, or {@code -1}
     */
    public int getFirstFreeIndex() {
        return this.firstFreeIndex;
    }

    /**
     * Sets the X WORD of the source position of a path record.
     *
     * @param recordIndex the record index
     * @param value       the X coordinate, narrowed to a WORD
     */
    public void setSourceX(int recordIndex, int value) {
        this.sourcePositionOrFreeLink[recordIndex] = this.sourcePositionOrFreeLink[recordIndex] & WORD_MASK << WORD_BITS | value & WORD_MASK;
    }

    /**
     * Sets the Y WORD of the source position of a path record.
     *
     * @param recordIndex the record index
     * @param value       the Y coordinate, narrowed to a WORD
     */
    public void setSourceY(int recordIndex, int value) {
        this.sourcePositionOrFreeLink[recordIndex] = this.sourcePositionOrFreeLink[recordIndex] & WORD_MASK | value << WORD_BITS;
    }

    /**
     * @param recordIndex the record index
     * @return the packed current waypoint position of a path record
     */
    public int getWaypointPosition(int recordIndex) {
        return this.waypointPositionOrIdentifier[recordIndex];
    }

    /**
     * Sets the packed current waypoint position of a path record.
     *
     * @param recordIndex the record index
     * @param value       the packed waypoint position
     */
    public void setWaypointPosition(int recordIndex, int value) {
        this.waypointPositionOrIdentifier[recordIndex] = value;
    }

    /**
     * @param recordIndex the record index
     * @return the packed destination of a path record
     */
    public int getDestination(int recordIndex) {
        return this.destinations[recordIndex];
    }

    /**
     * Sets the packed destination of a path record.
     *
     * @param recordIndex the record index
     * @param value       the packed destination
     */
    public void setDestination(int recordIndex, int value) {
        this.destinations[recordIndex] = value;
    }

    /**
     * @param recordIndex the record index
     * @return the frame in which the path record was created
     */
    public int getCreationFrame(int recordIndex) {
        return this.creationFrames[recordIndex];
    }

    /**
     * Sets the frame in which a path record was created.
     *
     * @param recordIndex the record index
     * @param value       the creation frame
     */
    public void setCreationFrame(int recordIndex, int value) {
        this.creationFrames[recordIndex] = value;
    }

    /**
     * @param recordIndex the record index
     * @return the speed saved by a path record while its unit waits or slides
     */
    public int getSavedSpeed(int recordIndex) {
        return this.savedSpeeds[recordIndex];
    }

    /**
     * Sets the speed a path record saves while its unit waits or slides.
     *
     * @param recordIndex the record index
     * @param value       the saved speed
     */
    public void setSavedSpeed(int recordIndex, int value) {
        this.savedSpeeds[recordIndex] = value;
    }

    /**
     * @param recordIndex the record index
     * @return the handle of the unit the path's unit last collided with
     */
    public int getLastCollisionHandle(int recordIndex) {
        return this.lastCollisionHandles[recordIndex];
    }

    /**
     * Sets the handle of the unit the path's unit last collided with.
     *
     * @param recordIndex the record index
     * @param value       the unit handle
     */
    public void setLastCollisionHandle(int recordIndex, int value) {
        this.lastCollisionHandles[recordIndex] = value;
    }

    /**
     * @param recordIndex the record index
     * @return the unsigned slide direction BYTE of a path record
     */
    public int getSlideDirection(int recordIndex) {
        return Byte.toUnsignedInt(this.slideDirections[recordIndex]);
    }

    /**
     * Sets the slide direction BYTE of a path record.
     *
     * @param recordIndex the record index
     * @param value       the slide direction, narrowed to a BYTE
     */
    public void setSlideDirection(int recordIndex, int value) {
        this.slideDirections[recordIndex] = (byte) value;
    }

    /**
     * @param recordIndex the record index
     * @return the unsigned flag BYTE of a path record, such as {@link #FLAG_NEW_MOVE_TARGET}
     */
    public int getFlags(int recordIndex) {
        return Byte.toUnsignedInt(this.flags[recordIndex]);
    }

    /**
     * Sets the flag BYTE of a path record.
     *
     * @param recordIndex the record index
     * @param value       the flags, narrowed to a BYTE
     */
    public void setFlags(int recordIndex, int value) {
        this.flags[recordIndex] = (byte) value;
    }

    /**
     * @param recordIndex the record index
     * @return the unsigned timer BYTE of a path record
     */
    public int getTimer(int recordIndex) {
        return Byte.toUnsignedInt(this.timers[recordIndex]);
    }

    /**
     * Sets the timer BYTE of a path record.
     *
     * @param recordIndex the record index
     * @param value       the timer, narrowed to a BYTE
     */
    public void setTimer(int recordIndex, int value) {
        this.timers[recordIndex] = (byte) value;
    }

    /**
     * @param recordIndex the record index
     * @return the unsigned full region count BYTE of a path record: the region count of the complete route
     */
    int getFullRegionCount(int recordIndex) {
        return Byte.toUnsignedInt(this.fullRegionCounts[recordIndex]);
    }

    /**
     * Sets the full region count BYTE of a path record: the region count of the complete route.
     *
     * @param recordIndex the record index
     * @param value       the full region count, narrowed to a BYTE
     */
    public void setFullRegionCount(int recordIndex, int value) {
        this.fullRegionCounts[recordIndex] = (byte) value;
    }

    /**
     * @param recordIndex the record index
     * @return the unsigned count BYTE of the regions stored in a path record
     */
    int getRegionCount(int recordIndex) {
        return Byte.toUnsignedInt(this.regionCounts[recordIndex]);
    }

    /**
     * Sets the count BYTE of the regions stored in a path record.
     *
     * @param recordIndex the record index
     * @param value       the region count, narrowed to a BYTE
     */
    public void setRegionCount(int recordIndex, int value) {
        this.regionCounts[recordIndex] = (byte) value;
    }

    /**
     * @param recordIndex the record index
     * @return the signed index BYTE of the current route region of a path record
     */
    int getRegionIndex(int recordIndex) {
        return this.regionIndices[recordIndex];
    }

    /**
     * Sets the index BYTE of the current route region of a path record.
     *
     * @param recordIndex the record index
     * @param value       the region index, narrowed to a BYTE
     */
    public void setRegionIndex(int recordIndex, int value) {
        this.regionIndices[recordIndex] = (byte) value;
    }

    /**
     * @param recordIndex the record index
     * @return the number of waypoints of a path record
     */
    public int getWaypointCount(int recordIndex) {
        return this.waypointCounts[recordIndex];
    }

    /**
     * Sets the number of waypoints of a path record.
     *
     * @param recordIndex the record index
     * @param value       the waypoint count, narrowed to a BYTE
     */
    public void setWaypointCount(int recordIndex, int value) {
        this.waypointCounts[recordIndex] = (byte) value;
    }

    /**
     * @param recordIndex the record index
     * @return the index of the current waypoint of a path record
     */
    public int getWaypointIndex(int recordIndex) {
        return this.waypointIndices[recordIndex];
    }

    /**
     * Sets the index of the current waypoint of a path record.
     *
     * @param recordIndex the record index
     * @param value       the waypoint index, narrowed to a BYTE
     */
    public void setWaypointIndex(int recordIndex, int value) {
        this.waypointIndices[recordIndex] = (byte) value;
    }

    /**
     * @param recordIndex   the record index
     * @param waypointIndex the waypoint index
     * @return a packed waypoint of a path record
     * @throws IndexOutOfBoundsException if an index is outside the pool or the record's route WORDs
     */
    public int getWaypoint(int recordIndex, int waypointIndex) {
        Objects.checkIndex(recordIndex, RECORD_COUNT);
        Objects.checkIndex(waypointIndex, TAIL_WORD_COUNT / WAYPOINT_WORDS);

        int index = recordIndex * TAIL_WORD_COUNT + waypointIndex * WAYPOINT_WORDS;

        return Short.toUnsignedInt(this.routeCells[index]) | this.routeCells[index + WAYPOINT_Y_WORD] << WORD_BITS;
    }

    /**
     * Sets a packed waypoint of a path record.
     *
     * @param recordIndex   the record index
     * @param waypointIndex the waypoint index
     * @param value         the packed waypoint
     * @throws IndexOutOfBoundsException if an index is outside the pool or the record's route WORDs
     */
    public void setWaypoint(int recordIndex, int waypointIndex, int value) {
        Objects.checkIndex(recordIndex, RECORD_COUNT);
        Objects.checkIndex(waypointIndex, TAIL_WORD_COUNT / WAYPOINT_WORDS);

        int index = recordIndex * TAIL_WORD_COUNT + waypointIndex * WAYPOINT_WORDS;

        this.routeCells[index]                   = (short) value;
        this.routeCells[index + WAYPOINT_Y_WORD] = (short) (value >>> WORD_BITS);
    }

    /**
     * Reads a region WORD of the region route that follows the waypoints of a path record.
     * <p>
     * The first region may retain the previous route's second waypoint coordinate when a one-step path reuses the record.
     *
     * @param recordIndex   the record index
     * @param waypointCount the waypoint count of the record
     * @param regionIndex   the index in the region route
     * @return the signed region WORD
     * @throws IndexOutOfBoundsException     if an index is outside the pool or the record's route WORDs
     * @throws UnsupportedOperationException if the read passes the last record of the pool
     */
    public int getRouteRegion(int recordIndex, int waypointCount, int regionIndex) {
        Objects.checkIndex(recordIndex, RECORD_COUNT);
        Objects.checkIndex(waypointCount, TAIL_WORD_COUNT / WAYPOINT_WORDS + 1);

        int cell = waypointCount * WAYPOINT_WORDS + regionIndex;

        if (cell == TAIL_WORD_COUNT) {
            // A read one WORD past the route cells takes the first WORD of the next record: its source position or free link.
            if (recordIndex + 1 == RECORD_COUNT) {
                throw Exceptions.newUnsupportedOperationException("A route region read after the last path record reaches memory beyond the pool.");
            }

            return (short) this.sourcePositionOrFreeLink[recordIndex + 1];
        }

        Objects.checkIndex(regionIndex, TAIL_WORD_COUNT - waypointCount * WAYPOINT_WORDS);

        return this.routeCells[recordIndex * TAIL_WORD_COUNT + cell];
    }


    /**
     * Removes the free head from the free list, without clearing its contents.
     *
     * @return the zero-based record index, or {@code -1} if no record is free
     * @throws IndexOutOfBoundsException if the free head is outside the pool
     */
    public int allocate() {
        if (this.firstFreeIndex < 0) {
            return -1;
        }

        int recordIndex = Objects.checkIndex(this.firstFreeIndex, RECORD_COUNT);

        this.firstFreeIndex = this.sourcePositionOrFreeLink[recordIndex] - 1;

        return recordIndex;
    }

    /**
     * Clamps the route counts to the route WORDs of a record and allocates a record.
     * <p>
     * When the regions and two WORDs per waypoint exceed the route WORDs, a route with half the route WORDs or more waypoints keeps one waypoint less
     * than half and one region; otherwise the region count shrinks to the remaining WORDs.
     *
     * @param counts the mutable region and waypoint count WORDs, in that order
     * @return the zero-based record index, or {@code -1} on allocation failure
     * @throws IllegalArgumentException if the array does not hold two counts, the region count is negative or a count exceeds the request capacity
     */
    public int allocateForRoute(short @NotNull [] counts) {
        if (counts.length != ROUTE_COUNTS_LENGTH) {
            throw Exceptions.newIllegalArgumentException("Native path allocation requires region and waypoint count WORDs.");
        }

        int regionCount = counts[ROUTE_REGION_COUNT_INDEX];
        int waypointCount = Short.toUnsignedInt(counts[ROUTE_WAYPOINT_COUNT_INDEX]);

        if (regionCount < 0 || regionCount > PathRequest.REGION_CAPACITY || waypointCount > PathRequest.WAYPOINT_CAPACITY) {
            throw Exceptions.newIllegalArgumentException("Route counts must fit the search request.");
        }

        if (regionCount + waypointCount * WAYPOINT_WORDS > TAIL_WORD_COUNT) {
            if (waypointCount >= TAIL_WORD_COUNT / WAYPOINT_WORDS) {
                counts[ROUTE_WAYPOINT_COUNT_INDEX] = TAIL_WORD_COUNT / WAYPOINT_WORDS - 1;
                counts[ROUTE_REGION_COUNT_INDEX]   = 1;
            } else {
                counts[ROUTE_REGION_COUNT_INDEX] = (short) (TAIL_WORD_COUNT - waypointCount * WAYPOINT_WORDS);
            }
        }

        return this.allocate();
    }

    /**
     * Prepends a record to the free list, keeping the bytes after its links.
     *
     * @param recordIndex the zero-based record index in {@code 0..1023}
     * @throws IndexOutOfBoundsException if the index is outside this pool
     */
    public void release(int recordIndex) {
        Objects.checkIndex(recordIndex, RECORD_COUNT);

        this.waypointPositionOrIdentifier[recordIndex] = recordIndex + 1;
        this.sourcePositionOrFreeLink[recordIndex]     = this.firstFreeIndex + 1;
        this.firstFreeIndex                            = recordIndex;
    }

    /**
     * Copies the bounded waypoint prefix of a request without changing the remaining shared route cells.
     * <p>
     * The record and the count are validated before any route cell changes.
     *
     * @param source      the request with the waypoints
     * @param recordIndex the record index
     * @param count       the waypoint count
     * @throws NullPointerException      if the source request is {@code null}
     * @throws IndexOutOfBoundsException if the record or count is outside the pool or the record's route WORDs
     */
    void copyWaypointsFrom(@NotNull PathRequest source, int recordIndex, int count) {
        if (source == null) {
            throw Exceptions.newNullPointerException();
        }

        Objects.checkIndex(recordIndex, RECORD_COUNT);
        Objects.checkFromIndexSize(0, count, TAIL_WORD_COUNT / WAYPOINT_WORDS);

        int firstCell = recordIndex * TAIL_WORD_COUNT;

        for (int index = 0; index < count; index++) {
            int waypoint = source.waypoints[index];

            this.routeCells[firstCell + index * WAYPOINT_WORDS]                   = (short) waypoint;
            this.routeCells[firstCell + index * WAYPOINT_WORDS + WAYPOINT_Y_WORD] = (short) (waypoint >>> WORD_BITS);
        }
    }

    /**
     * Copies the regions of a request after a bounded waypoint prefix into the shared route cells of a record.
     * <p>
     * The request owns {@code 50} region slots, while a stored route retains at most {@code 48} WORDs.
     *
     * @param source        the request with the regions
     * @param recordIndex   the record index
     * @param waypointCount the waypoint count before the regions
     * @param regionCount   the region count
     * @throws NullPointerException      if the source request is {@code null}
     * @throws IndexOutOfBoundsException if an index or count is outside the pool or the record's route WORDs
     */
    void copyRegionsFrom(@NotNull PathRequest source, int recordIndex, int waypointCount, int regionCount) {
        if (source == null) {
            throw Exceptions.newNullPointerException();
        }

        Objects.checkIndex(recordIndex, RECORD_COUNT);
        Objects.checkIndex(waypointCount, TAIL_WORD_COUNT / WAYPOINT_WORDS + 1);

        int firstCell = waypointCount * WAYPOINT_WORDS;

        Objects.checkFromIndexSize(firstCell, regionCount, TAIL_WORD_COUNT);

        System.arraycopy(source.regions, 0, this.routeCells, recordIndex * TAIL_WORD_COUNT + firstCell, regionCount);
    }

    /**
     * Writes every {@link #SAVED_RECORD_SIZE}-byte path record, allocated or free, with its stale fields and route cells, in the saved record layout:
     * the source position or free link, the waypoint position or identifier, the destination, the creation frame, the last collision unit handle and
     * the saved speed DWORDs, the flags, timer, slide direction, full region count, region count, region index, waypoint count and waypoint index
     * BYTEs and the {@code 48} route WORDs.
     *
     * @param data the path records, little-endian, at offset {@code 0}
     */
    public void writeSaveRecords(@NotNull ByteBuffer data) {
        for (int recordIndex = 0; recordIndex < RECORD_COUNT; recordIndex++) {
            int record = recordIndex * SAVED_RECORD_SIZE;

            data.putInt(record + SAVED_SOURCE, this.sourcePositionOrFreeLink[recordIndex]);
            data.putInt(record + SAVED_WAYPOINT, this.waypointPositionOrIdentifier[recordIndex]);
            data.putInt(record + SAVED_DESTINATION, this.destinations[recordIndex]);
            data.putInt(record + SAVED_CREATION_FRAME, this.creationFrames[recordIndex]);
            data.putInt(record + SAVED_LAST_COLLISION, this.lastCollisionHandles[recordIndex]);
            data.putInt(record + SAVED_SPEED, this.savedSpeeds[recordIndex]);

            data.put(record + SAVED_FLAGS, this.flags[recordIndex]);
            data.put(record + SAVED_TIMER, this.timers[recordIndex]);
            data.put(record + SAVED_SLIDE_DIRECTION, this.slideDirections[recordIndex]);
            data.put(record + SAVED_FULL_REGION_COUNT, this.fullRegionCounts[recordIndex]);
            data.put(record + SAVED_REGION_COUNT, this.regionCounts[recordIndex]);
            data.put(record + SAVED_REGION_INDEX, this.regionIndices[recordIndex]);
            data.put(record + SAVED_WAYPOINT_COUNT, this.waypointCounts[recordIndex]);
            data.put(record + SAVED_WAYPOINT_INDEX, this.waypointIndices[recordIndex]);

            for (int cell = 0; cell < TAIL_WORD_COUNT; cell++) {
                data.putShort(record + SAVED_ROUTE_CELLS + cell * Short.BYTES, this.routeCells[recordIndex * TAIL_WORD_COUNT + cell]);
            }
        }
    }

    /**
     * Reads the path records that {@link #writeSaveRecords} wrote.
     *
     * @param data           the path records, little-endian, at offset {@code 0}
     * @param firstFreeIndex the zero-based free head, or {@code -1}
     */
    public void readSaveRecords(@NotNull ByteBuffer data, int firstFreeIndex) {
        for (int recordIndex = 0; recordIndex < RECORD_COUNT; recordIndex++) {
            int record = recordIndex * SAVED_RECORD_SIZE;

            this.sourcePositionOrFreeLink[recordIndex]     = data.getInt(record + SAVED_SOURCE);
            this.waypointPositionOrIdentifier[recordIndex] = data.getInt(record + SAVED_WAYPOINT);
            this.destinations[recordIndex]                 = data.getInt(record + SAVED_DESTINATION);
            this.creationFrames[recordIndex]               = data.getInt(record + SAVED_CREATION_FRAME);
            this.lastCollisionHandles[recordIndex]         = data.getInt(record + SAVED_LAST_COLLISION);
            this.savedSpeeds[recordIndex]                  = data.getInt(record + SAVED_SPEED);

            this.flags[recordIndex]            = data.get(record + SAVED_FLAGS);
            this.timers[recordIndex]           = data.get(record + SAVED_TIMER);
            this.slideDirections[recordIndex]  = data.get(record + SAVED_SLIDE_DIRECTION);
            this.fullRegionCounts[recordIndex] = data.get(record + SAVED_FULL_REGION_COUNT);
            this.regionCounts[recordIndex]     = data.get(record + SAVED_REGION_COUNT);
            this.regionIndices[recordIndex]    = data.get(record + SAVED_REGION_INDEX);
            this.waypointCounts[recordIndex]   = data.get(record + SAVED_WAYPOINT_COUNT);
            this.waypointIndices[recordIndex]  = data.get(record + SAVED_WAYPOINT_INDEX);

            for (int cell = 0; cell < TAIL_WORD_COUNT; cell++) {
                this.routeCells[recordIndex * TAIL_WORD_COUNT + cell] = data.getShort(record + SAVED_ROUTE_CELLS + cell * Short.BYTES);
            }
        }

        this.firstFreeIndex = firstFreeIndex;
    }

    /**
     * Adds the free-list state, the retained header fields and the shared route cells to a diagnostic hash, in their declared domain order.
     *
     * @param hash the previous hash
     * @return the updated hash
     */
    public int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + this.firstFreeIndex;

        for (int recordIndex = 0; recordIndex < RECORD_COUNT; recordIndex++) {
            hash = HASH_MULTIPLIER * hash + this.sourcePositionOrFreeLink[recordIndex];
            hash = HASH_MULTIPLIER * hash + this.waypointPositionOrIdentifier[recordIndex];
            hash = HASH_MULTIPLIER * hash + this.destinations[recordIndex];
            hash = HASH_MULTIPLIER * hash + this.creationFrames[recordIndex];
            hash = HASH_MULTIPLIER * hash + this.savedSpeeds[recordIndex];
            hash = HASH_MULTIPLIER * hash + this.lastCollisionHandles[recordIndex];
            hash = HASH_MULTIPLIER * hash + this.slideDirections[recordIndex];
            hash = HASH_MULTIPLIER * hash + this.flags[recordIndex];
            hash = HASH_MULTIPLIER * hash + this.timers[recordIndex];
            hash = HASH_MULTIPLIER * hash + this.fullRegionCounts[recordIndex];
            hash = HASH_MULTIPLIER * hash + this.regionCounts[recordIndex];
            hash = HASH_MULTIPLIER * hash + this.regionIndices[recordIndex];
            hash = HASH_MULTIPLIER * hash + this.waypointCounts[recordIndex];
            hash = HASH_MULTIPLIER * hash + this.waypointIndices[recordIndex];
        }

        for (short cell : this.routeCells) {
            hash = HASH_MULTIPLIER * hash + cell;
        }

        return hash;
    }
}
