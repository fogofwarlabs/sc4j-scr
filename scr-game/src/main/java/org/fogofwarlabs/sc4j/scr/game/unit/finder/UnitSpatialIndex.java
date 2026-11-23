package org.fogofwarlabs.sc4j.scr.game.unit.finder;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.collision.BlockingTest;
import org.fogofwarlabs.sc4j.engine.collision.UnitCollisionSearch;
import org.fogofwarlabs.sc4j.engine.spatial.SearchCounter;
import org.fogofwarlabs.sc4j.engine.spatial.SortedEdgeIndex;
import org.fogofwarlabs.sc4j.engine.spatial.UnitFilter;
import org.fogofwarlabs.sc4j.engine.spatial.UnitList;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedUnitTypes;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.movement.UnitMovement;
import org.fogofwarlabs.sc4j.scr.game.path.PathObstacleCollector;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Serves the SC:R unit finder over the engine sorted-edge index ({@link SortedEdgeIndex}): primary units are indexed by two sorted edges on each
 * axis of their sprite position and {@code units.dat} dimensions ({@link ScrUnitBounds}), with their four reciprocal edge indices on the unit.
 * The engine index holds the edge arrays in the classic layout that EUD reads ({@link #readHorizontalFinderByte}) and the state hash mixes, the
 * query markers and the minimum query dimensions; its unexpanded queries advance the search counter of the host process.
 * <p>
 * The finder adds what is SC:R: the checks of the classic insertion (pool, type range, subunits), the minimum query dimensions from the
 * {@code units.dat} extents, the collision, placement, movement, Larva and path target queries over the engine queries, and the flying-unit count
 * grid of the flyer repulsion. Terrain occupancy and region notification are separate.
 */
public final class UnitSpatialIndex {
    /**
     * The number of cells of the flying-unit count grid on each axis.
     */
    public static final int FLYING_GRID_DIMENSION = 171;
    /**
     * The number of bytes of the flying-unit count grid (one count per cell).
     */
    public static final int FLYING_GRID_SIZE = FLYING_GRID_DIMENSION * FLYING_GRID_DIMENSION;

    /**
     * The size in pixels of a flying-unit grid cell.
     */
    private static final int FLYING_CELL_SIZE = 48;
    /**
     * The random source of the flying-unit registration draw.
     */
    private static final int FLYING_REGISTRATION_RANDOM_SOURCE = 37;

    /**
     * The mask of an unsigned BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of an unsigned WORD.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * The number of edges of an indexed unit on each axis: the minimum and the maximum edge.
     */
    private static final int EDGES_PER_UNIT = 2;
    /**
     * The number of axes, horizontal then vertical.
     */
    private static final int AXIS_COUNT = 2;
    /**
     * The edge scans of the nearby query on each axis: forward and backward.
     */
    private static final int SCAN_DIRECTIONS_PER_AXIS = 2;
    /**
     * The number of edge scans of the nearby query.
     */
    private static final int SCAN_DIRECTION_COUNT = AXIS_COUNT * SCAN_DIRECTIONS_PER_AXIS;
    /**
     * The horizontal entry a missing vertical edge falls back to: the last entry of an array of the classic capacity.
     */
    private static final int VERTICAL_FALLBACK_EDGE = EDGES_PER_UNIT * UnitPool.CLASSIC_UNIT_CAPACITY - 1;

    /**
     * The size in bytes of a horizontal finder entry: the unit index DWORD, then the coordinate DWORD.
     */
    private static final int FINDER_ENTRY_SIZE = 2 * Integer.BYTES;
    /**
     * The shift from a byte offset of the horizontal finder array to its entry.
     */
    private static final int FINDER_ENTRY_SHIFT = 3;
    /**
     * The byte offset of the coordinate within a horizontal finder entry.
     */
    private static final int FINDER_COORDINATE_OFFSET = Integer.BYTES;
    /**
     * The mask of a byte offset within its DWORD.
     */
    private static final int DWORD_BYTE_MASK = Integer.BYTES - 1;

    /**
     * The last unit type of the extent scan.
     */
    private static final int LAST_UNIT_TYPE = UnitsDat.UNIT_COUNT - 1;
    /**
     * The radius in pixels of the Larva query around a parent.
     */
    private static final int LARVA_QUERY_RADIUS = 256;
    /**
     * The Larva count at which the count stops.
     */
    private static final int LARVA_COUNT_LIMIT = 3;

    /**
     * The shift that halves a minimum query dimension.
     */
    private static final int QUERY_HALF_EXTENT_SHIFT = 1;

    /**
     * Runs the placement blocking rule of one search as an engine search test: the rules and the type table of the running
     * {@link #findPlacementBlocker} query.
     */
    private static final class PlacementTest implements BlockingTest<Unit> {
        private @Nullable UnitCollisionRules rules;
        private @Nullable ScrUnitTypeView units;

        private PlacementTest() {
            this.rules = null;
            this.units = null;
        }

        private void prepare(@NotNull UnitCollisionRules rules, @NotNull ScrUnitTypeView units) {
            this.rules = rules;
            this.units = units;
        }

        /**
         * Checks whether another unit blocks a unit.
         *
         * @param unit      the moving or placed unit
         * @param otherUnit the candidate unit
         * @return {@code true} if the other unit blocks the unit, {@code false} otherwise
         * @throws IllegalStateException if no search prepared the test
         */
        @Override
        public boolean blocks(@NotNull Unit unit, @NotNull Unit otherUnit) {
            UnitCollisionRules preparedRules = this.rules;
            ScrUnitTypeView preparedUnits = this.units;

            if (preparedRules == null || preparedUnits == null) {
                throw Exceptions.newIllegalStateException("The placement test runs inside a placement query only.");
            }

            return preparedRules.blocksPlacement(otherUnit, unit, preparedUnits);
        }
    }


    private final @NotNull UnitPool pool;
    private final @NotNull ScrUnitBounds bounds;
    private final @NotNull SortedEdgeIndex<Unit> edges;
    private final @NotNull UnitCollisionSearch<Unit> collision;
    private final @NotNull PlacementTest placementTest;

    /**
     * The results of the queries that return a copy ({@link #findAllInRectangle} and the like), reused between calls.
     */
    private final int @NotNull [] copiedResults;

    private final @NotNull ScrFlyerRepulsionRules repulsionRules;
    private final @NotNull FlyerRepulsion repulsion;

    /**
     * Creates a {@link UnitSpatialIndex} instance, empty, with two edges per unit and a fresh search counter.
     *
     * @param pool the unit pool
     */
    public UnitSpatialIndex(@NotNull UnitPool pool) {
        this(pool, new SearchCounter());
    }

    /**
     * Creates a {@link UnitSpatialIndex} instance, empty, with two edges per unit and the search counter of the host process.
     *
     * @param pool          the unit pool
     * @param searchCounter the process's unit finder search counter
     */
    public UnitSpatialIndex(@NotNull UnitPool pool, @NotNull SearchCounter searchCounter) {
        this.pool   = pool;
        this.bounds = new ScrUnitBounds();
        this.edges  = new SortedEdgeIndex<>(pool.getCapacity(), pool::getUnit, this.bounds, searchCounter);

        this.collision     = new UnitCollisionSearch<>(this.edges, this.bounds);
        this.placementTest = new PlacementTest();

        this.copiedResults = new int[this.edges.getQueryCapacity()];

        this.repulsionRules = new ScrFlyerRepulsionRules();
        this.repulsion      = new FlyerRepulsion(this.repulsionRules, FLYING_GRID_DIMENSION, FLYING_CELL_SIZE, FLYING_REGISTRATION_RANDOM_SOURCE);
    }

    /**
     * Sets the unit finder search counter of the host process.
     * <p>
     * The owning game sets it once, at its creation, when it uses another counter than the one of its data
     * ({@link UnitSystem#setUnitFinderSearchCounter}).
     *
     * @param searchCounter the unit finder search counter of the host process
     */
    public void setSearchCounter(@NotNull SearchCounter searchCounter) {
        this.edges.setSearchCounter(searchCounter);
    }

    /**
     * @return the engine sorted-edge index this finder serves, for the engine {@link org.fogofwarlabs.sc4j.engine.spatial.SpatialIndex} view
     */
    public @NotNull SortedEdgeIndex<Unit> getEdges() {
        return this.edges;
    }

    /**
     * @return the engine blocker searches over the edges of this finder
     */
    public @NotNull UnitCollisionSearch<Unit> getCollisionSearch() {
        return this.collision;
    }

    /**
     * @return the number of edges in each axis
     */
    public int getCount() {
        return this.edges.getCount();
    }

    /**
     * @param horizontal whether to read the horizontal axis
     * @param index      the edge index
     * @return the stored one-based pool index
     * @throws IndexOutOfBoundsException if the index is negative or not below the edge count
     */
    public int getUnitIndex(boolean horizontal, int index) {
        return this.edges.getUnitIndex(horizontal, index);
    }

    /**
     * @param horizontal whether to read the horizontal axis
     * @param index      the edge index
     * @return the signed DWORD edge coordinate
     * @throws IndexOutOfBoundsException if the index is negative or not below the edge count
     */
    public int getCoordinate(boolean horizontal, int index) {
        return this.edges.getCoordinate(horizontal, index);
    }

    /**
     * @return the query markers, live; one DWORD per pool index from zero
     */
    public int @NotNull [] getQueryMarkers() {
        return this.edges.getQueryMarkers();
    }

    /**
     * @return the number of entries of a query result list, one more than the unit capacity
     */
    public int getQueryCapacity() {
        return this.edges.getQueryCapacity();
    }

    /**
     * @return {@code true} if the extent scan has initialized the query bounds, {@code false} otherwise
     */
    public boolean hasQueryBounds() {
        return this.edges.hasQueryBounds();
    }

    /**
     * @param horizontal whether to read the horizontal dimension
     * @return the unsigned WORD minimum query dimension
     */
    public int getMinimumQueryDimension(boolean horizontal) {
        return this.edges.getMinimumQueryDimension(horizontal);
    }

    /**
     * @return the owned count grid ({@link #FLYING_GRID_SIZE} bytes), borrowed by state hashing and by the EUD region of the grid, or {@code null} before allocation
     */
    public byte @Nullable [] getFlyingUnitCounts() {
        return this.repulsion.getCellCounts();
    }

    /**
     * @param poolIndex the one-based pool index
     * @return the unit of the given pool slot
     */
    public @NotNull Unit getPoolUnit(int poolIndex) {
        return this.pool.getUnit(poolIndex);
    }

    /**
     * @return the size in bytes of the horizontal unit finder array
     */
    public int getHorizontalFinderSize() {
        return this.edges.getEdgeCapacity() * FINDER_ENTRY_SIZE;
    }

    /**
     * @param index the result index, below the count {@link #collectPlacementCandidates} returned
     * @return the unit of the given result of the last {@link #collectPlacementCandidates} query
     * @throws ArrayIndexOutOfBoundsException if the index is outside the owned query-result storage
     */
    public @NotNull Unit getPlacementCandidate(int index) {
        return this.pool.getUnit(this.edges.getQueryResult(index));
    }

    private int getStoredCoordinate(@NotNull Unit unit, boolean horizontal, boolean maximum) {
        int index = maximum ? unit.getMaximumEdgeIndex(horizontal) : unit.getMinimumEdgeIndex(horizontal);
        boolean storedHorizontal = horizontal;

        if (index == -1) {
            if (horizontal) {
                return this.edges.getCount();
            }

            // Missing vertical edges fall back to the final classic horizontal entry.
            storedHorizontal = true;
            index            = VERTICAL_FALLBACK_EDGE;
        }

        if (index < 0 || index >= this.edges.getEdgeCapacity()) {
            throw Exceptions.newUnsupportedOperationException("The overlap test reads edge index {0} outside the edge storage.", index);
        }

        return this.edges.getStoredCoordinate(storedHorizontal, index);
    }


    /**
     * Allocates the zeroed flying-unit count grid of {@code 171} by {@code 171} BYTEs.
     */
    public void initializeFlyingUnits() {
        this.repulsion.allocate();
    }

    /**
     * Empties edge storage, query markers and the flying-unit grid before saved units are registered again. The grid
     * retains its storage for EUD access.
     */
    public void clear() {
        this.repulsion.clear();
        this.edges.clear();
    }

    /**
     * Registers a moving non-building flyer in its flying-unit grid cell ({@link FlyerRepulsion#register}): a flyer without an adjustment index
     * first draws its repulsion direction and adjustment index (random source {@code 37}), then its cell is stored and the cell count rises up
     * to the byte limit.
     *
     * @param unit   the flyer to register
     * @param units  the unit type table
     * @param random the game random generator
     * @throws IllegalStateException         if the unit's cell lies outside the flying-unit grid
     * @throws UnsupportedOperationException if the unit has no sprite
     */
    public void registerFlyingUnit(@NotNull Unit unit, @NotNull ScrUnitTypeView units, @NotNull LcgRandom random) {
        this.repulsionRules.setUnitTypes(units);
        this.repulsion.register(unit, random);
    }

    /**
     * Removes a moving non-building flyer's contribution to its repulsion cell.
     *
     * @param unit  the unit leaving the visible world
     * @param units the unit type table
     * @throws IllegalStateException if the unit's repulsion cell is outside the grid
     */
    public void unregisterFlyingUnit(@NotNull Unit unit, @NotNull ScrUnitTypeView units) {
        this.repulsionRules.setUnitTypes(units);
        this.repulsion.unregister(unit);
    }

    /**
     * Applies the flyer repulsion ({@link FlyerRepulsion#apply}): a flyer in a crowded cell turns its repulsion direction now and then (toward its
     * destination, away from the map edges) and is displaced along it, with the signed fixed-point displacement and the adjustment pair of its
     * adjustment index.
     *
     * @param context the prospective movement coordinates
     * @param game    the game (map dimensions and unit type table)
     * @param unit    the flying unit
     * @return {@code true} if crowding displaced the prospective position, {@code false} otherwise
     * @throws IllegalStateException if the unit's repulsion cell is outside the grid or the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public boolean applyFlyingSeparation(@NotNull UnitMovement.Context context, @NotNull Game game, @NotNull Unit unit) {
        this.repulsionRules.setUnitTypes(game.requireGameData().getUnitTypes());

        return this.repulsion.apply(context, unit,
                                    game.getMap().getWidth() * TerrainTileset.TILE_SIZE, game.getMap().getHeight() * TerrainTileset.TILE_SIZE);
    }

    /**
     * Moves a flyer to the grid cell of its sprite when it left its cell, and makes a displaced flyer close to its
     * destination take the given position as its destination ({@link FlyerRepulsion#updateCell}).
     *
     * @param unit      the flyer to move between cells
     * @param units     the unit type table
     * @param random    the game random generator
     * @param displaced the result from the preceding separation calculation
     * @param positionX the next horizontal WORD coordinate
     * @param positionY the next vertical WORD coordinate
     * @throws IllegalStateException         if the grid is missing or the flyer's previous or new cell lies outside it
     * @throws UnsupportedOperationException if the sprite is missing
     */
    public void updateFlyingUnitCell(@NotNull Unit unit,
                                     @NotNull ScrUnitTypeView units,
                                     @NotNull LcgRandom random,
                                     boolean displaced,
                                     int positionX, int positionY) {
        this.repulsionRules.setUnitTypes(units);
        this.repulsion.updateCell(unit, random, displaced, positionX, positionY);
    }

    /**
     * Initializes the minimum query dimensions with the largest unit width and height, scanning the native unit types from the last to the first,
     * then the added unit types, which raise the dimensions only when they are larger than every native type.
     *
     * @param units the unit type table containing all {@code 228} native types and the added types
     */
    public void initializeQueryBounds(@NotNull ScrUnitTypeView units) {
        int width = 0;
        int height = 0;
        int lastAddedType = ScrAddedUnitTypes.FIRST_ID + units.getAddedCount() - 1;

        for (int unitType = lastAddedType; unitType >= 0; unitType--) {
            if (unitType > LAST_UNIT_TYPE && !units.isAddedType(unitType)) {
                continue;
            }

            int unitWidth = (short) units.getDimensionLeft(unitType) + 1 + (short) units.getDimensionRight(unitType);
            int unitHeight = (short) units.getDimensionUp(unitType) + 1 + (short) units.getDimensionDown(unitType);

            if (unitWidth > width) {
                width = unitWidth & WORD_MASK;
            }

            if (unitHeight > height) {
                height = unitHeight & WORD_MASK;
            }
        }

        this.edges.setMinimumQueryDimensions(width, height);
    }

    /**
     * Inserts sprite coordinates and signed WORD unit extents, minimum edges before maximum edges ({@link SortedEdgeIndex#insert}).
     * <p>
     * Subunits do not enter the index. A primary unit may still be the uncommitted free head because building registration precedes pool commitment.
     *
     * @param unit  the unit to insert
     * @param units the unit type table
     * @throws UnsupportedOperationException if the unit is not from the owning pool, its type lies past {@link ScrUnitIds#NONE}, it has no sprite or the edge storage is full
     */
    public void insert(@NotNull Unit unit, @NotNull ScrUnitTypeView units) {
        int unitIndex = unit.getIndex();

        if (unitIndex <= 0 || unitIndex > this.pool.getCapacity() || this.pool.getUnit(unitIndex) != unit) {
            throw Exceptions.newUnsupportedOperationException("Spatial insertion requires a unit from the owning pool.");
        }

        int unitType = unit.getUnitId();

        if (unitType > ScrUnitIds.NONE && !units.isAddedType(unitType)) {
            throw Exceptions.newUnsupportedOperationException("Spatial insertion of unit type {0} past {1} is not modeled.",
                                                              unitType, ScrUnitIds.NONE);
        }

        if ((units.getFlags(unitType) & UnitTypeFlags.SUBUNIT) != 0) {
            return;
        }

        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Spatial insertion requires the unit sprite.");
        }

        if (this.edges.getCount() > this.edges.getEdgeCapacity() - EDGES_PER_UNIT) {
            throw Exceptions.newUnsupportedOperationException("Spatial insertion exceeds the edge storage capacity.");
        }

        this.bounds.setUnitTypes(units);
        this.edges.insert(unit);
    }

    /**
     * Removes both edges on each axis without changing the order of surviving entries: the count drops by one, the
     * maximum edges go, the count drops again and the minimum edges go. Each removal renumbers the following entries'
     * units and moves them down one slot, leaving the old last entry in place past the count, so the bytes past the
     * count retain the removed traversal's trailing entries.
     * <p>
     * All reciprocal indices of the removed unit become {@code -1}. Pool allocation, terrain occupancy and flying-unit registration remain the
     * caller's responsibility.
     *
     * @param unit the indexed unit, including an uncommitted constructor slot
     * @throws IllegalArgumentException if the unit belongs to another pool
     * @throws IllegalStateException    if its reciprocal edge indices do not identify its entries
     */
    public void remove(@NotNull Unit unit) {
        int unitIndex = unit.getIndex();

        if (unitIndex <= 0 || unitIndex > this.pool.getCapacity() || this.pool.getUnit(unitIndex) != unit) {
            throw Exceptions.newIllegalArgumentException("Spatial removal requires a unit from the owning pool.");
        }

        this.edges.remove(unit);
    }

    /**
     * Reads the horizontal unit finder array as the classic EUD address serves it: entries of eight bytes, the one-based unit index then the
     * coordinate, over the whole capacity (past the count: what the removals left, {@code -1} where nothing was ever stored).
     *
     * @param offset the byte offset
     * @return the byte
     */
    public int readHorizontalFinderByte(int offset) {
        int entry = offset >>> FINDER_ENTRY_SHIFT;
        int value = (offset & FINDER_COORDINATE_OFFSET) == 0 ? this.edges.getStoredUnitIndex(true, entry)
                                                             : this.edges.getStoredCoordinate(true, entry);

        return value >>> (offset & DWORD_BYTE_MASK) * Byte.SIZE & BYTE_MASK;
    }

    /**
     * Moves a unit's edges by the displacement of its sprite and swaps them past their neighbors until the arrays are
     * sorted again (the leading edge first).
     *
     * @param unit             the unit whose sprite has already moved
     * @param horizontalChange the signed DWORD horizontal displacement
     * @param verticalChange   the signed DWORD vertical displacement
     */
    public void relocate(@NotNull Unit unit, int horizontalChange, int verticalChange) {
        this.edges.relocate(unit, horizontalChange, verticalChange);
    }

    /**
     * Notifies the collisions of a unit with the indexed candidates of its extent: a grounded building candidate marks
     * the unit as colliding, and an overlapping ground candidate is marked as colliding.
     * <p>
     * Candidates are traversed in query order with inclusive overlap comparisons, using shared result storage.
     *
     * @param unit  the incoming unit
     * @param units the unit type table
     * @throws UnsupportedOperationException if the query bounds are not initialized or the unit has no sprite
     */
    public void notifyCollision(@NotNull Unit unit, @NotNull ScrUnitTypeView units) {
        if (!this.edges.hasQueryBounds()) {
            throw Exceptions.newUnsupportedOperationException("Collision notification requires initialized query bounds.");
        }

        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Collision notification requires the unit sprite.");
        }

        int unitType = unit.getUnitId();
        int left = (short) (sprite.getX() - units.getDimensionLeft(unitType));
        int upper = (short) (sprite.getY() - units.getDimensionUp(unitType));
        int right = (short) (sprite.getX() + units.getDimensionRight(unitType));
        int lower = (short) (sprite.getY() + units.getDimensionDown(unitType));
        int resultCount = this.collectCandidates(units, left, upper, right, lower);

        for (int resultIndex = 0; resultIndex < resultCount; resultIndex++) {
            Unit candidate = this.pool.getUnit(this.edges.getQueryResult(resultIndex));
            int candidateStatus = candidate.getStatus();

            if (UnitQueries.isGroundedBuilding(candidate)) {
                unit.setStatus(unit.getStatus() | UnitStatusFlags.COLLISION);
            } else if ((candidateStatus & UnitStatusFlags.FLYING) == 0 &&
                       ((candidateStatus & UnitStatusFlags.GATHERING) == 0 || UnitQueries.isGroundedBuilding(unit)) &&
                       unit.getMinimumHorizontalEdgeIndex() >= 0 && this.isOverlapping(candidate, unit)) {
                candidate.setStatus(candidateStatus | UnitStatusFlags.COLLISION);
            }
        }
    }

    /**
     * Returns the first candidate of the expanded query at the placement position that blocks the placement
     * ({@link UnitCollisionRules#blocksPlacement}, the engine {@link UnitCollisionSearch#findFirstBlocker}).
     *
     * @param rules     the collision rules of the game
     * @param incoming  the unit being placed
     * @param units     the unit type table
     * @param positionX the horizontal WORD placement coordinate
     * @param positionY the vertical WORD placement coordinate
     * @return the blocking unit, or {@code null} when no candidate blocks placement
     * @throws UnsupportedOperationException if the query bounds are not initialized
     */
    public @Nullable Unit findPlacementBlocker(@NotNull UnitCollisionRules rules,
                                               @NotNull Unit incoming, @NotNull ScrUnitTypeView units,
                                               int positionX, int positionY) {
        if (!this.edges.hasQueryBounds()) {
            throw Exceptions.newUnsupportedOperationException("Placement query requires initialized query bounds.");
        }

        int unitType = incoming.getUnitId();

        this.bounds.setUnitTypes(units);
        this.placementTest.prepare(rules, units);

        return this.collision.findFirstBlocker(incoming,
                                               (short) (positionX - units.getDimensionLeft(unitType)),
                                               (short) (positionY - units.getDimensionUp(unitType)),
                                               (short) (positionX + units.getDimensionRight(unitType)),
                                               (short) (positionY + units.getDimensionDown(unitType)),
                                               this.placementTest);
    }

    /**
     * Copies expanded rectangle query candidates in query order, using shared query markers for simulation and interface queries.
     *
     * @param units the unit type table
     * @param left  the left WORD boundary
     * @param upper the upper WORD boundary
     * @param right the right WORD boundary
     * @param lower the lower WORD boundary
     * @return the one-based pool indices of the candidates
     * @throws UnsupportedOperationException if the query bounds are not initialized
     */
    public int @NotNull [] findAllInRectangle(@NotNull ScrUnitTypeView units, int left, int upper, int right, int lower) {
        return Arrays.copyOf(this.copiedResults, this.findAllInRectangle(units, left, upper, right, lower, this.copiedResults));
    }

    /**
     * Writes expanded rectangle query candidates in query order into the caller's storage, using shared query markers for simulation and interface
     * queries.
     *
     * @param units   the unit type table
     * @param left    the left WORD boundary
     * @param upper   the upper WORD boundary
     * @param right   the right WORD boundary
     * @param lower   the lower WORD boundary
     * @param results receives the one-based pool indices of the candidates; at least {@link #getQueryCapacity()} entries
     * @return the number of candidates written
     * @throws UnsupportedOperationException if the query bounds are not initialized
     */
    public int findAllInRectangle(@NotNull ScrUnitTypeView units, int left, int upper, int right, int lower, int @NotNull [] results) {
        if (!this.edges.hasQueryBounds()) {
            throw Exceptions.newUnsupportedOperationException("Rectangle query requires initialized query bounds.");
        }

        this.bounds.setUnitTypes(units);

        return this.edges.findCandidates(left, upper, right, lower, results);
    }

    /**
     * Finds the units whose bounds intersect a rectangle for the engine {@link org.fogofwarlabs.sc4j.engine.spatial.SpatialIndex} view: the
     * candidates of {@link #findAllInRectangle} in its query order, accepted by the filter ({@link SortedEdgeIndex#findInRectangle}).
     *
     * @param units  the unit type table
     * @param left   the left WORD boundary
     * @param upper  the upper WORD boundary
     * @param right  the right WORD boundary
     * @param lower  the lower WORD boundary
     * @param filter selects the returned units
     * @param result receives the units; cleared first
     * @throws UnsupportedOperationException if the query bounds are not initialized
     */
    public void findInRectangle(@NotNull ScrUnitTypeView units, int left, int upper, int right, int lower,
                                @NotNull UnitFilter filter, @NotNull UnitList result) {
        if (!this.edges.hasQueryBounds()) {
            throw Exceptions.newUnsupportedOperationException("Rectangle query requires initialized query bounds.");
        }

        this.bounds.setUnitTypes(units);
        this.edges.findInRectangle(left, upper, right, lower, filter, result);
    }

    /**
     * Copies unexpanded rectangle query results in horizontal-edge order, advancing the shared search counter.
     *
     * @param left  the left WORD boundary
     * @param upper the upper WORD boundary
     * @param right the right WORD boundary
     * @param lower the lower WORD boundary
     * @return the one-based pool indices in horizontal-edge order
     */
    public int @NotNull [] findAllInRectangleUnexpanded(int left, int upper, int right, int lower) {
        return Arrays.copyOf(this.copiedResults, this.edges.findUnexpanded(left, upper, right, lower, this.copiedResults));
    }

    /**
     * Applies the point query ({@link SortedEdgeIndex#findAtPoint}): the unexpanded query over {@code [x, y, x + minimum width, y + minimum
     * height]}, keeping the units whose stored edges contain the point (left edge at most {@code x} and right edge above it, the same vertically).
     *
     * @param positionX the horizontal WORD
     * @param positionY the vertical WORD
     * @return the one-based pool indices in query order
     */
    public int @NotNull [] findAllAtPoint(int positionX, int positionY) {
        int[] results = new int[this.edges.getCount()];

        return Arrays.copyOf(results, this.edges.findAtPoint(positionX, positionY, results));
    }

    /**
     * Counts the visible Larvae of a parent among the units of the unexpanded query {@code 256} pixels around it, in query order, stopping at three.
     *
     * @param parent the persistent parent building
     * @return the number of visible Larva candidates with this parent, capped at three
     * @throws UnsupportedOperationException if the query bounds or a sprite are missing
     */
    public int countLarvaeNearParent(@NotNull Unit parent) {
        Sprite parentSprite = parent.getSpriteOrNull();

        if (!this.edges.hasQueryBounds() || parentSprite == null) {
            throw Exceptions.newUnsupportedOperationException("Larva counting requires initialized query bounds and the parent sprite.");
        }

        int horizontal = (short) parentSprite.getX();
        int vertical = (short) parentSprite.getY();
        int resultCount = this.edges.findUnexpanded(horizontal - LARVA_QUERY_RADIUS, vertical - LARVA_QUERY_RADIUS,
                                                    horizontal + LARVA_QUERY_RADIUS, vertical + LARVA_QUERY_RADIUS);
        int larvaCount = 0;

        for (int resultIndex = 0; resultIndex < resultCount; resultIndex++) {
            Unit candidate = this.pool.getUnit(this.edges.getQueryResult(resultIndex));

            if (candidate.getUnitId() != ScrUnitIds.ZERG_LARVA) {
                continue;
            }

            Sprite sprite = candidate.getSpriteOrNull();

            if (sprite == null) {
                throw Exceptions.newUnsupportedOperationException("Larva counting requires the sprite of every Larva candidate.");
            }

            if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) == 0 && candidate.getParentUnit() == parent && ++larvaCount == LARVA_COUNT_LIMIT) {
                break;
            }
        }

        return larvaCount;
    }

    /**
     * Selects the overlapping movement candidate with the greatest signed footprint area ({@link UnitCollisionRules#getMovementTest}, the engine
     * {@link UnitCollisionSearch#findLargestBlocker}).
     *
     * @param rules     the collision rules of the game
     * @param incoming  the moving unit
     * @param units     the unit type table
     * @param positionX the requested horizontal WORD coordinate
     * @param positionY the requested vertical WORD coordinate
     * @return the first greatest-area candidate, or {@code null} before the terrain fallback
     * @throws UnsupportedOperationException if the query bounds are not initialized
     */
    public @Nullable Unit findMovementBlocker(@NotNull UnitCollisionRules rules,
                                              @NotNull Unit incoming, @NotNull ScrUnitTypeView units,
                                              int positionX, int positionY) {
        if (!this.edges.hasQueryBounds()) {
            throw Exceptions.newUnsupportedOperationException("Movement collision requires initialized query bounds.");
        }

        int unitType = incoming.getUnitId();
        int left = (short) (positionX - units.getDimensionLeft(unitType));
        int upper = (short) (positionY - units.getDimensionUp(unitType));
        int right = (short) (positionX + units.getDimensionRight(unitType));
        int lower = (short) (positionY + units.getDimensionDown(unitType));

        this.bounds.setUnitTypes(units);

        return this.collision.findLargestBlocker(incoming, left, upper, right, lower, rules.getMovementTest());
    }

    /**
     * Collects moving ground candidates near an indexed unit in four ordered edge scans (horizontal forward and
     * backward, then vertical forward and backward), up to a limit.
     *
     * @param rules    the collision rules of the game
     * @param incoming the indexed incoming unit
     * @param range    the original unsigned WORD expansion on both axes
     * @param limit    the positive accepted-entry limit
     * @param results  caller-owned storage for one-based pool indices
     * @return the number of accepted entries, including original duplicates
     * @throws IllegalArgumentException if the limit is not positive or exceeds the result storage
     * @throws IllegalStateException    if the incoming unit or a scanned candidate has no sprite
     */
    public int collectNearbyMovingUnits(@NotNull UnitCollisionRules rules, @NotNull Unit incoming, int range, int limit, int @NotNull [] results) {
        if (limit <= 0 || limit > results.length) {
            throw Exceptions.newIllegalArgumentException("Nearby query requires a positive limit within the result storage.");
        }

        if ((incoming.requireSprite().getFlags() & Sprite.FLAG_HIDDEN) != 0) {
            return 0;
        }

        SortedEdgeIndex<Unit> edges = this.edges;
        int count = edges.getCount();
        int resultCount = 0;

        for (int direction = 0; direction < SCAN_DIRECTION_COUNT; direction++) {
            boolean horizontal = direction < SCAN_DIRECTIONS_PER_AXIS;
            boolean forward = (direction & 1) == 0;
            int edgeIndex = (horizontal
                             ? (forward ? incoming.getMaximumHorizontalEdgeIndex() : incoming.getMinimumHorizontalEdgeIndex())
                             : (forward ? incoming.getMaximumVerticalEdgeIndex() : incoming.getMinimumVerticalEdgeIndex()));
            int limitCoordinate = (short) (edges.getStoredCoordinate(horizontal, edgeIndex) + (forward ? range : -range));
            int first = (short) (edges.getStoredCoordinate(!horizontal, incoming.getMinimumEdgeIndex(!horizontal)) - (horizontal ? range : 0));
            int last = (short) (edges.getStoredCoordinate(!horizontal, incoming.getMaximumEdgeIndex(!horizontal)) + (horizontal ? range : 0));

            for (edgeIndex += forward ? 1 : -1; edgeIndex >= 0 && edgeIndex < count; edgeIndex += forward ? 1 : -1) {
                int coordinate = edges.getStoredCoordinate(horizontal, edgeIndex);

                if (forward ? coordinate >= limitCoordinate : coordinate < limitCoordinate) {
                    break;
                }

                Unit candidate = this.pool.getUnit(edges.getStoredUnitIndex(horizontal, edgeIndex));

                if (candidate == incoming || edges.getStoredCoordinate(!horizontal, candidate.getMaximumEdgeIndex(!horizontal)) < first ||
                    edges.getStoredCoordinate(!horizontal, candidate.getMinimumEdgeIndex(!horizontal)) >= last) {
                    continue;
                }

                Sprite candidateSprite = candidate.requireSprite();

                if (candidate.getMovementDestinationX() == (candidateSprite.getX() & WORD_MASK) &&
                    candidate.getMovementDestinationY() == (candidateSprite.getY() & WORD_MASK)) {
                    continue;
                }

                if ((candidate.getStatus() & UnitStatusFlags.GROUND_UNIT) == 0 || !rules.isBlockingMovement(candidate, incoming)) {
                    continue;
                }

                results[resultCount++] = candidate.getIndex();

                if (resultCount == limit) {
                    return resultCount;
                }
            }
        }

        return resultCount;
    }

    /**
     * Returns the first unit of the ordered destination query whose extent, grown by the moving unit's extent, contains
     * the destination and that blocks the path of the moving unit.
     *
     * @param rules             the collision rules of the game
     * @param incoming          the moving unit
     * @param units             the unit type table
     * @param positionX         the destination horizontal WORD
     * @param positionY         the destination vertical WORD
     * @param includeMoving     the explicit moving-unit inclusion DWORD
     * @param selectedUnitIndex the explicit selected unit identity
     * @return the first eligible overlapping unit, or {@code null}
     * @throws UnsupportedOperationException if the query bounds or a candidate's sprite are missing
     */
    public @Nullable Unit findPathTarget(@NotNull UnitCollisionRules rules,
                                         @NotNull Unit incoming,
                                         @NotNull ScrUnitTypeView units,
                                         int positionX, int positionY,
                                         int includeMoving,
                                         int selectedUnitIndex) {
        if (!this.edges.hasQueryBounds()) {
            throw Exceptions.newUnsupportedOperationException("Path target selection requires initialized query bounds.");
        }

        int horizontal = (short) positionX;
        int vertical = (short) positionY;
        int minimumWidth = this.edges.getMinimumQueryDimension(true);
        int minimumHeight = this.edges.getMinimumQueryDimension(false);
        int left = horizontal - (minimumWidth >>> QUERY_HALF_EXTENT_SHIFT) - 1;
        int right = horizontal + (minimumWidth >>> QUERY_HALF_EXTENT_SHIFT) + 1;
        int upper = vertical - (minimumHeight >>> QUERY_HALF_EXTENT_SHIFT) - 1;
        int lower = vertical + (minimumHeight >>> QUERY_HALF_EXTENT_SHIFT) + 1;
        int resultCount = this.edges.findUnexpanded(left, upper, right, lower);
        int unitType = incoming.getUnitId();

        for (int index = 0; index < resultCount; index++) {
            Unit candidate = this.pool.getUnit(this.edges.getQueryResult(index));
            Sprite sprite = candidate.getSpriteOrNull();

            if (sprite == null) {
                throw Exceptions.newUnsupportedOperationException("Path target selection requires the sprite of every candidate.");
            }

            int candidateType = candidate.getUnitId();
            int candidateLeft = (short) (sprite.getX() - units.getDimensionLeft(candidateType) - 1 - units.getDimensionRight(unitType));
            int candidateRight = (short) (sprite.getX() + units.getDimensionRight(candidateType) + 1 + units.getDimensionLeft(unitType));
            int candidateUpper = (short) (sprite.getY() - units.getDimensionUp(candidateType) - 1 - units.getDimensionDown(unitType));
            int candidateLower = (short) (sprite.getY() + units.getDimensionDown(candidateType) + 1 + units.getDimensionUp(unitType));

            if (candidateLeft <= horizontal && horizontal <= candidateRight && candidateUpper <= vertical && vertical <= candidateLower &&
                PathObstacleCollector.isBlockingPath(rules, units, candidate, incoming, includeMoving, selectedUnitIndex)) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Collects the units of the unexpanded rectangle query into the shared result storage, read back with
     * {@link #getPlacementCandidate}; the next query of the index replaces them.
     *
     * @param left  the left WORD boundary
     * @param upper the upper WORD boundary
     * @param right the right WORD boundary
     * @param lower the lower WORD boundary
     * @return the number of results
     */
    public int collectPlacementCandidates(int left, int upper, int right, int lower) {
        return this.edges.findUnexpanded(left, upper, right, lower);
    }

    /**
     * Applies the unexpanded rectangle query ({@link SortedEdgeIndex#findUnexpanded}) into caller storage.
     *
     * @param left    the left WORD boundary
     * @param upper   the upper WORD boundary
     * @param right   the right WORD boundary
     * @param lower   the lower WORD boundary
     * @param results receives the one-based pool indices in horizontal-edge order
     * @return the number of results
     */
    public int collectUnstackingCandidates(int left, int upper, int right, int lower, int @NotNull [] results) {
        return this.edges.findUnexpanded(left, upper, right, lower, results);
    }

    /**
     * Finds the nearest accepted unit in the signed WORD radius box around a unit, excluding that unit.
     *
     * @param unit      the searching unit
     * @param radius    the unsigned WORD radius
     * @param predicate the candidate test, run before distance comparison
     * @return the nearest accepted unit, or {@code null}
     */
    public @Nullable Unit findNearest(@NotNull Unit unit, int radius, @NotNull Predicate<Unit> predicate) {
        return this.edges.findNearest(unit, radius, predicate);
    }

    /**
     * Finds the nearest accepted unit around a unit, passing that unit as the candidate test's borrowed context.
     *
     * @param unit      the searching unit and candidate-test context
     * @param radius    the unsigned WORD radius
     * @param predicate the candidate and context test, run before distance comparison
     * @return the nearest accepted unit, or {@code null}
     */
    public @Nullable Unit findNearest(@NotNull Unit unit, int radius, @NotNull BiPredicate<Unit, Unit> predicate) {
        return this.edges.findNearest(unit, radius, predicate);
    }

    /**
     * Finds the nearest accepted unit in a signed WORD rectangle, starting at the searching unit's edges or hidden position.
     * <p>
     * Bounds are half-open. Each round visits left, right, up and down before shrinking the box. Candidate callbacks precede distance comparison. The
     * shared rectangle-query markers, results and search counter are unchanged.
     *
     * @param unit      the searching unit, excluded before the candidate test
     * @param left      the inclusive left WORD bound
     * @param upper     the inclusive upper WORD bound
     * @param right     the exclusive right WORD bound
     * @param lower     the exclusive lower WORD bound
     * @param predicate the candidate test
     * @return the nearest accepted unit, or {@code null}
     */
    public @Nullable Unit findNearest(@NotNull Unit unit, int left, int upper, int right, int lower, @NotNull Predicate<Unit> predicate) {
        return this.edges.findNearest(unit, left, upper, right, lower, predicate);
    }

    /**
     * Finds the nearest accepted unit in a signed WORD rectangle, passing the searching unit as borrowed candidate-test context.
     * <p>
     * Bounds are half-open. Each round visits left, right, up and down before shrinking the box. Candidate callbacks precede distance comparison. The
     * shared rectangle-query markers, results and search counter are unchanged.
     *
     * @param unit      the searching unit and candidate-test context, excluded before the candidate test
     * @param left      the inclusive left WORD bound
     * @param upper     the inclusive upper WORD bound
     * @param right     the exclusive right WORD bound
     * @param lower     the exclusive lower WORD bound
     * @param predicate the candidate and context test
     * @return the nearest accepted unit, or {@code null}
     */
    public @Nullable Unit findNearest(@NotNull Unit unit, int left, int upper, int right, int lower, @NotNull BiPredicate<Unit, Unit> predicate) {
        return this.edges.findNearest(unit, left, upper, right, lower, predicate);
    }

    /**
     * Finds the nearest accepted unit in the signed WORD radius box around a position.
     *
     * @param positionX the horizontal WORD position
     * @param positionY the vertical WORD position
     * @param radius    the unsigned WORD radius
     * @param predicate the candidate test, run before distance comparison
     * @return the nearest accepted unit, or {@code null}
     */
    public @Nullable Unit findNearest(int positionX, int positionY, int radius, @NotNull Predicate<Unit> predicate) {
        return this.edges.findNearest(positionX, positionY, radius, predicate);
    }

    /**
     * Finds the nearest accepted unit in a signed WORD rectangle around a position.
     * <p>
     * Bounds are half-open. Each round visits left, right, up and down before shrinking the box. Candidate callbacks precede distance comparison. The
     * shared rectangle-query markers, results and search counter are unchanged.
     *
     * @param positionX the horizontal WORD position
     * @param positionY the vertical WORD position
     * @param left      the inclusive left WORD bound
     * @param upper     the inclusive upper WORD bound
     * @param right     the exclusive right WORD bound
     * @param lower     the exclusive lower WORD bound
     * @param predicate the candidate test
     * @return the nearest accepted unit, or {@code null}
     */
    public @Nullable Unit findNearest(int positionX, int positionY, int left, int upper, int right, int lower, @NotNull Predicate<Unit> predicate) {
        return this.edges.findNearest(positionX, positionY, left, upper, right, lower, predicate);
    }

    private int collectCandidates(@NotNull ScrUnitTypeView units, int left, int upper, int right, int lower) {
        this.bounds.setUnitTypes(units);

        return this.edges.findCandidates(left, upper, right, lower);
    }

    private boolean isOverlapping(@NotNull Unit first, @NotNull Unit second) {
        return this.getStoredCoordinate(first, true, true) >= this.getStoredCoordinate(second, true, false) &&
               this.getStoredCoordinate(first, true, false) <= this.getStoredCoordinate(second, true, true) &&
               this.getStoredCoordinate(first, false, true) >= this.getStoredCoordinate(second, false, false) &&
               this.getStoredCoordinate(first, false, false) <= this.getStoredCoordinate(second, false, true);
    }
}
