package org.fogofwarlabs.sc4j.scr.game.path;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegionLookupUtils;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegions;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Searches the local routes of units: a bounded best-first search over the candidate positions of {@link PathObstacleCollector} along the region
 * route of a {@link RegionPathSearch}, with a coverage of the visited rectangles that filters the directions of new nodes.
 * <p>
 * The coverage is a record pool behind a band map: the band count, the band boundaries ({@code 129} DWORDs), the record index of each band
 * ({@code 129} DWORDs) and the records ({@code 128} records of {@code 21} DWORDs: the interval count, then ten start and end pairs). A split band
 * takes the next free record, a copy of the count and the used intervals of its record; records and map entries past the bands keep what earlier
 * searches left there.
 */
public final class LocalPathSearch {
    /**
     * The visited-rectangle coverage: up to {@code 128} vertical bands of up to {@code 10} intervals each.
     */
    private static final int BAND_CAPACITY = 128;
    private static final int INTERVALS_PER_BAND = 10;
    /**
     * The DWORDs of one interval of a band record: its start and its end.
     */
    private static final int INTERVAL_SIZE = 2;
    /**
     * The offset of the end in an interval of a band record.
     */
    private static final int INTERVAL_END = 1;
    /**
     * The offset of the end of the following interval from the start of an interval.
     */
    private static final int NEXT_INTERVAL_END = INTERVAL_SIZE + INTERVAL_END;
    /**
     * The offset of the interval after the following one from the start of an interval.
     */
    private static final int SECOND_NEXT_INTERVAL = INTERVAL_SIZE + INTERVAL_SIZE;
    /**
     * The offset of the first interval in a band record, after the interval count.
     */
    private static final int FIRST_INTERVAL = 1;
    /**
     * The offset of the end of the first interval in a band record.
     */
    private static final int FIRST_INTERVAL_END = FIRST_INTERVAL + INTERVAL_END;
    /**
     * The DWORDs of one band record: the interval count, then the start and end of each interval.
     */
    private static final int RECORD_SIZE = FIRST_INTERVAL + INTERVAL_SIZE * INTERVALS_PER_BAND;

    /**
     * The search nodes and the open-node heap (slot {@code 0} of the heap holds the sentinel node index).
     */
    private static final int NODE_CAPACITY = 250;
    private static final int HEAP_CAPACITY = 150;
    /**
     * The slot of the first open node in the heap.
     */
    private static final int HEAP_ROOT = 1;
    /**
     * The children of a heap slot: the parent of a slot is the slot divided by this, its first child is the slot times this.
     */
    private static final int HEAP_ARITY = 2;
    /**
     * The backtracked route positions of the selected node.
     */
    private static final int BACKTRACK_CAPACITY = 128;
    /**
     * The backtracked positions of a direct route: the source and the destination.
     */
    private static final int DIRECT_ROUTE_POSITIONS = 2;
    /**
     * The backtrack index of the first waypoint, counted back from the backtrack count: the last backtracked position is the source.
     */
    private static final int FIRST_WAYPOINT_OFFSET = 2;
    /**
     * An estimate above every reachable one: the start of minimum searches and of the source node.
     */
    private static final int NO_ESTIMATE = 0x400000;
    /**
     * The byte size used to compare the used search nodes against the node limits.
     */
    private static final int NODE_RECORD_SIZE = 40;
    /**
     * The node bytes from which a search with an open node in the following region (or a moved goal) is limited.
     */
    private static final int FOLLOWING_REGION_NODE_LIMIT = 6000;
    /**
     * The node bytes from which a search with an open node in a neighbor region of the goal is limited.
     */
    private static final int NEIGHBOR_REGION_NODE_LIMIT = 8000;

    /**
     * The route regions, from the current one, up to which the local search heads for the final destination.
     */
    private static final int FINAL_ROUTE_REGIONS = 3;
    /**
     * The route region, counted from the current one, whose center is the goal of an intermediate local search.
     */
    private static final int GOAL_REGION_OFFSET = 2;
    /**
     * The following region of a local search toward the final destination: none.
     */
    private static final int NO_FOLLOWING_REGION = 0x1FFE;
    /**
     * The exclusion rectangle stored without a target unit: four WORDs of {@code -32000}.
     */
    private static final long NO_EXCLUSION_RECTANGLE = 0x8300830083008300L;

    /**
     * The cost factor of a step into a region other than the source and following regions, while the following region is walkable.
     */
    private static final int OTHER_REGION_COST_FACTOR = 2;
    /**
     * The estimate factors of the fallback node choice for a node in the source region and in another unmarked region.
     */
    private static final int SOURCE_REGION_ESTIMATE_FACTOR = 2;
    private static final int OTHER_REGION_ESTIMATE_FACTOR = 4;
    /**
     * The estimate of a node in a neighbor region of the goal is multiplied by the numerator and divided by the denominator.
     */
    private static final int NEIGHBOR_REGION_ESTIMATE_NUMERATOR = 3;
    private static final int NEIGHBOR_REGION_ESTIMATE_DENOMINATOR = 2;
    /**
     * The estimate shift of a closed node in the fallback node choice.
     */
    private static final int CLOSED_ESTIMATE_SHIFT = 5;
    /**
     * The fallback node choice adds the cost of a node in the goal or following region divided by this to its estimate.
     */
    private static final int DESTINATION_COST_DIVISOR = 2;
    /**
     * The fallback node choice discounts the open node count by the mark of each goal neighbor region divided by this.
     */
    private static final int REGION_MARK_DIVISOR = 2;
    /**
     * The mark of a neighbor region of the goal before the node count.
     */
    private static final int NEIGHBOR_MARK = 1;
    /**
     * The lowest mark of a neighbor region of the goal that holds a node: {@link #NEIGHBOR_MARK} plus one.
     */
    private static final int MARK_WITH_NODE = NEIGHBOR_MARK + 1;

    /**
     * The fraction bits of the fixed-point node distances.
     */
    private static final int FIXED_POINT_SHIFT = 8;

    /**
     * The mask of the unsigned BYTE node depth.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of an unsigned WORD region index, coordinate or map width.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of one WORD of the packed exclusion rectangle.
     */
    private static final long LONG_WORD_MASK = 0xFFFFL;
    /**
     * The shift of the vertical WORD of a packed position.
     */
    private static final int WORD_BITS = 16;
    /**
     * The multiplier of the diagnostic state hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * Calculates the fixed-point distance between two packed pixel positions.
     *
     * @param firstPosition  the first packed position WORDs
     * @param secondPosition the second packed position WORDs
     * @return the distance in fixed point
     */
    private static int calculateFixedPointDistance(int firstPosition, int secondPosition) {
        int firstHorizontal = (short) firstPosition << FIXED_POINT_SHIFT;
        int secondHorizontal = (short) secondPosition << FIXED_POINT_SHIFT;
        int firstVertical = (short) (firstPosition >>> WORD_BITS) << FIXED_POINT_SHIFT;
        int secondVertical = (short) (secondPosition >>> WORD_BITS) << FIXED_POINT_SHIFT;

        return RegionPathSearch.calculateDistance(firstHorizontal, secondHorizontal, firstVertical, secondVertical);
    }


    private final int @NotNull [] horizontalBoundaries;
    private final int @NotNull [] bandRecords;
    private final int @NotNull [] records;
    private int bandCount;

    private final int @NotNull [] positions;
    private final int @NotNull [] regions;
    private final int @NotNull [] directions;
    private final int @NotNull [] depths;
    private final int @NotNull [] parents;
    private final int @NotNull [] heuristics;
    private final int @NotNull [] costs;
    private final int @NotNull [] totals;
    private final boolean @NotNull [] neighboring;
    private final boolean @NotNull [] nextRegion;
    private final boolean @NotNull [] terminal;
    private final boolean @NotNull [] closed;

    private final int @NotNull [] heap;
    private final int @NotNull [] backtrack;
    private final int @NotNull [] regionMarks;
    private final int @NotNull [] adjustedDestination;

    private final @NotNull PathObstacleCollector obstacleCollector;
    private @Nullable RegionPathSearch regionSearch;
    private @Nullable TerrainRegions terrainRegions;

    private int nodeCount;
    private int heapCount;

    private boolean movementContextKnown;
    private boolean includeMoving;
    private int selectedUnitIndex;

    /**
     * Creates a {@link LocalPathSearch} instance with empty search storage and unknown movement collision controls.
     */
    public LocalPathSearch() {
        this.horizontalBoundaries = new int[BAND_CAPACITY + 1];
        this.bandRecords          = new int[BAND_CAPACITY + 1];
        this.records              = new int[BAND_CAPACITY * RECORD_SIZE];
        this.bandCount            = 0;

        this.positions   = new int[NODE_CAPACITY];
        this.regions     = new int[NODE_CAPACITY];
        this.directions  = new int[NODE_CAPACITY];
        this.depths      = new int[NODE_CAPACITY];
        this.parents     = new int[NODE_CAPACITY];
        this.heuristics  = new int[NODE_CAPACITY];
        this.costs       = new int[NODE_CAPACITY];
        this.totals      = new int[NODE_CAPACITY + 1];
        this.neighboring = new boolean[NODE_CAPACITY];
        this.nextRegion  = new boolean[NODE_CAPACITY];
        this.terminal    = new boolean[NODE_CAPACITY];
        this.closed      = new boolean[NODE_CAPACITY];

        this.heap                = new int[HEAP_CAPACITY + 1];
        this.backtrack           = new int[BACKTRACK_CAPACITY];
        this.regionMarks         = new int[TerrainRegions.REGION_COUNT];
        this.adjustedDestination = new int[UnitPositioning.POSITION_COORDINATES];

        this.obstacleCollector = new PathObstacleCollector();
        this.regionSearch      = null;
        this.terrainRegions    = null;

        this.nodeCount = 0;
        this.heapCount = 0;

        this.movementContextKnown = false;
        this.includeMoving        = false;
        this.selectedUnitIndex    = 0;
    }

    /**
     * @return the band count of the visited-rectangle coverage
     */
    public int getVisitedBandCount() {
        return this.bandCount;
    }

    /**
     * Sets the band count of the visited-rectangle coverage, as an EUD write stores it.
     *
     * @param bandCount the band count
     */
    public void setVisitedBandCount(int bandCount) {
        this.bandCount = bandCount;
    }

    /**
     * @return the band boundaries of the visited-rectangle coverage, live
     */
    public int @NotNull [] getVisitedBoundaries() {
        return this.horizontalBoundaries;
    }

    /**
     * @return the record index of each band of the visited-rectangle coverage, live
     */
    public int @NotNull [] getVisitedBandRecords() {
        return this.bandRecords;
    }

    /**
     * @return the band records of the visited-rectangle coverage, live
     */
    public int @NotNull [] getVisitedRecords() {
        return this.records;
    }


    /**
     * Sets the movement collision controls of the following searches, also for their {@link PathObstacleCollector}.
     *
     * @param includeMoving     whether every moving unit blocks routes
     * @param selectedUnitIndex the pool index of the moving unit that still blocks routes, or {@code 0}
     */
    public void initializeMovementContext(boolean includeMoving, int selectedUnitIndex) {
        this.movementContextKnown = true;
        this.includeMoving        = includeMoving;
        this.selectedUnitIndex    = selectedUnitIndex;

        this.obstacleCollector.initializeMovementContext(includeMoving, selectedUnitIndex);
    }

    /**
     * Adds the represented retained local search scratch to a diagnostic hash.
     *
     * @param hash the previous hash
     * @return the updated hash
     */
    public int computeStateHash(int hash) {
        hash = hash * HASH_MULTIPLIER + (this.movementContextKnown ? 1 : 0);
        hash = hash * HASH_MULTIPLIER + (this.includeMoving ? 1 : 0);
        hash = hash * HASH_MULTIPLIER + this.selectedUnitIndex;
        hash = hash * HASH_MULTIPLIER + this.bandCount;

        for (int index = 0; index <= this.bandCount; index++) {
            hash = hash * HASH_MULTIPLIER + this.horizontalBoundaries[index];
        }

        for (int band = 0; band < this.bandCount; band++) {
            int record = this.bandRecords[band] * RECORD_SIZE;

            hash = hash * HASH_MULTIPLIER + this.records[record];

            for (int interval = 0; interval < this.records[record]; interval++) {
                hash = hash * HASH_MULTIPLIER + this.records[record + FIRST_INTERVAL + INTERVAL_SIZE * interval];
                hash = hash * HASH_MULTIPLIER + this.records[record + FIRST_INTERVAL_END + INTERVAL_SIZE * interval];
            }
        }

        hash = hash * HASH_MULTIPLIER + this.nodeCount;

        for (int index = 0; index < this.nodeCount; index++) {
            hash = hash * HASH_MULTIPLIER + this.positions[index];
            hash = hash * HASH_MULTIPLIER + this.regions[index];
            hash = hash * HASH_MULTIPLIER + this.directions[index];
            hash = hash * HASH_MULTIPLIER + this.depths[index];
            hash = hash * HASH_MULTIPLIER + this.parents[index];
            hash = hash * HASH_MULTIPLIER + this.heuristics[index];
            hash = hash * HASH_MULTIPLIER + this.costs[index];
            hash = hash * HASH_MULTIPLIER + this.totals[index];
            hash = hash * HASH_MULTIPLIER + (this.neighboring[index] ? 1 : 0);
            hash = hash * HASH_MULTIPLIER + (this.nextRegion[index] ? 1 : 0);
            hash = hash * HASH_MULTIPLIER + (this.terminal[index] ? 1 : 0);
            hash = hash * HASH_MULTIPLIER + (this.closed[index] ? 1 : 0);
        }

        hash = hash * HASH_MULTIPLIER + this.heapCount;

        for (int index = HEAP_ROOT; index <= this.heapCount; index++) {
            hash = hash * HASH_MULTIPLIER + this.heap[index];
        }

        return this.obstacleCollector.computeStateHash(hash);
    }

    /**
     * Searches the next local route of a request: finds the region route (unless a stored route is continued), heads for the center of the region two
     * steps ahead or, near the end of the route, for the adjusted destination or the edge of the target unit, and writes the waypoints of the best
     * node into the request.
     *
     * @param game          the game with the terrain and the units
     * @param unit          the moving unit
     * @param request       the request with the source and destination positions
     * @param continueRoute whether the request continues its stored region route
     * @return {@code true} if the search produced a route, {@code false} otherwise
     * @throws UnsupportedOperationException if the terrain contours, the unit spatial index, the movement collision controls or the target's sprite are missing
     * @throws NullPointerException          if the world was built without game data
     */
    boolean search(@NotNull Game game, @NotNull Unit unit, @NotNull PathRequest request, boolean continueRoute) {
        TerrainContours terrain = game.getPathSystem().getTerrainContours();

        if (terrain == null) {
            throw Exceptions.newUnsupportedOperationException("Native local paths require terrain and complete request storage.");
        }

        if (this.terrainRegions != terrain.getRegions()) {
            this.terrainRegions = terrain.getRegions();
            this.regionSearch   = new RegionPathSearch(this.terrainRegions);
        }

        int source = TerrainRegionLookupUtils.lookup(this.terrainRegions,
                                                     (short) request.sourcePosition, (short) (request.sourcePosition >>> WORD_BITS));
        int destination = TerrainRegionLookupUtils.lookup(this.terrainRegions,
                                                          (short) request.destinationPosition, (short) (request.destinationPosition >>> WORD_BITS));

        request.sourceRegion          = (short) source;
        request.destinationRegion     = (short) destination;
        request.adjustedDestination   = request.destinationPosition;
        request.incompleteRegionRoute = 0;
        request.limitedLocalSearch    = 0;
        request.destinationReached    = 0;
        request.destinationAdjusted   = 0;

        ScrUnitTypeView units = game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();

        request.collisionLeft          = (short) -units.getDimensionLeft(unitType);
        request.collisionTop           = (short) -units.getDimensionUp(unitType);
        request.collisionRight         = (short) units.getDimensionRight(unitType);
        request.collisionBottom        = (short) units.getDimensionDown(unitType);
        request.regionNodeCount        = 0;
        request.localNodeCount         = 0;
        request.regionMaximumHeapCount = 0;
        request.localMaximumHeapCount  = 0;

        if (!continueRoute && source == destination) {
            request.setRegion(0, (short) source);

            request.regionIndex     = -1;
            request.regionCount     = 1;
            request.fullRegionCount = 1;
        } else if (!continueRoute) {
            if (!this.regionSearch.search((short) request.sourcePosition, (short) (request.sourcePosition >>> WORD_BITS),
                                          (short) request.destinationPosition, (short) (request.destinationPosition >>> WORD_BITS))) {
                return false;
            }

            request.regionIndex = -1;
            request.regionCount = (short) this.regionSearch.getRouteCount();

            for (int index = 0; index < this.regionSearch.getRouteCount(); index++) {
                request.setRegion(index, (short) this.regionSearch.getRouteRegion(index));
            }

            request.fullRegionCount        = (byte) this.regionSearch.getFullRouteCount();
            request.incompleteRegionRoute  = (byte) (this.regionSearch.isIncomplete() ? 1 : 0);
            request.regionNodeCount        = (short) this.regionSearch.getNodeCount();
            request.regionMaximumHeapCount = (short) this.regionSearch.getMaximumHeapCount();
        }

        int cursor = (short) (request.regionIndex + 1);
        int routeCount = request.regionCount;

        while (cursor < routeCount && (request.getRegion(cursor) & WORD_MASK) != source) {
            cursor++;
        }

        request.regionIndex = (short) cursor;

        if (cursor >= routeCount) {
            return false;
        }

        boolean finalDestination = routeCount - cursor <= FINAL_ROUTE_REGIONS;
        int position = request.adjustedDestination;

        if (!finalDestination) {
            int region = request.getRegion(cursor + GOAL_REGION_OFFSET) & WORD_MASK;

            position = this.terrainRegions.getCenterX(region) & WORD_MASK | this.terrainRegions.getCenterY(region) << WORD_BITS;
        }

        int followingRegion = NO_FOLLOWING_REGION;

        if (cursor == routeCount - 1) {
            if (source != destination) {
                this.adjustedDestination[UnitPositioning.X_INDEX] = (short) request.adjustedDestination;
                this.adjustedDestination[UnitPositioning.Y_INDEX] = (short) (request.adjustedDestination >>> WORD_BITS);

                TerrainRegionLookupUtils.adjustDestinationToRegion(this.terrainRegions,
                                                                   game.getMap().getWidth(),
                                                                   game.getMap().getHeight(),
                                                                   source,
                                                                   this.adjustedDestination);

                request.adjustedDestination = this.adjustedDestination[UnitPositioning.X_INDEX] & WORD_MASK |
                                              this.adjustedDestination[UnitPositioning.Y_INDEX] << WORD_BITS;
                request.destinationAdjusted = 1;
                finalDestination            = true;
            }
        } else {
            followingRegion = request.getRegion(cursor + 1) & WORD_MASK;
        }

        request.targetUnitIndex = 0L;

        if (finalDestination) {
            Unit target = unit.getMovementTargetUnit();

            if (target == null) {
                UnitSpatialIndex spatial = game.getUnitSystem().getUnitSpatialIndex();

                if (spatial == null || !this.movementContextKnown) {
                    throw Exceptions.newUnsupportedOperationException("Native path target query requires explicit movement collision controls.");
                }

                target = spatial.findPathTarget(game.getHooks().getCollisionRules(),
                                                unit,
                                                units,
                                                (short) request.adjustedDestination, (short) (request.adjustedDestination >>> WORD_BITS),
                                                this.includeMoving ? 1 : 0,
                                                this.selectedUnitIndex);
            }

            if (target != null && units.isBeacon(target.getUnitId())) {
                target = null;
            }

            request.targetUnitIndex = target == null ? 0L : (long) target.getIndex();

            if (target != null) {
                Sprite sprite = target.getSpriteOrNull();

                if (sprite == null) {
                    throw Exceptions.newUnsupportedOperationException("Native local path target has no retained sprite.");
                }

                int targetType = target.getUnitId();
                int left = (short) (sprite.getX() - units.getDimensionLeft(targetType) - 1 - units.getDimensionRight(unitType));
                int right = (short) (sprite.getX() + units.getDimensionRight(targetType) + 1 + units.getDimensionLeft(unitType));
                int top = (short) (sprite.getY() - units.getDimensionUp(targetType) - 1 - units.getDimensionDown(unitType));
                int bottom = (short) (sprite.getY() + units.getDimensionDown(targetType) + 1 + units.getDimensionUp(unitType));

                request.exclusionRectangle = ((short) left & LONG_WORD_MASK) |
                                             ((short) top & LONG_WORD_MASK) << PathObstacleCollector.RECTANGLE_TOP_SHIFT |
                                             ((short) right & LONG_WORD_MASK) << PathObstacleCollector.RECTANGLE_RIGHT_SHIFT |
                                             ((short) bottom & LONG_WORD_MASK) << PathObstacleCollector.RECTANGLE_BOTTOM_SHIFT;

                int sourceHorizontal = (short) request.sourcePosition;
                int sourceVertical = (short) (request.sourcePosition >>> WORD_BITS);

                if (((sourceHorizontal == left || sourceHorizontal == right) && top <= sourceVertical && sourceVertical <= bottom) ||
                    ((sourceVertical == top || sourceVertical == bottom) && left <= sourceHorizontal && sourceHorizontal <= right)) {
                    int sourcePosition = request.sourcePosition;

                    request.setWaypoint(0, sourcePosition);

                    request.adjustedDestination = sourcePosition;
                    request.waypointIndex       = 0;
                    request.waypointCount       = 1;
                    request.destinationReached  = 1;
                    request.destinationAdjusted = 0;

                    return true;
                }
            } else {
                request.exclusionRectangle                        = NO_EXCLUSION_RECTANGLE;
                this.adjustedDestination[UnitPositioning.X_INDEX] = (short) position;
                this.adjustedDestination[UnitPositioning.Y_INDEX] = (short) (position >>> WORD_BITS);

                if (TerrainRegionLookupUtils.adjustDestination(this.terrainRegions,
                                                               game.getMap().getWidth() * TerrainTileset.TILE_SIZE,
                                                               game.getMap().getHeight() * TerrainTileset.TILE_SIZE,
                                                               request.collisionLeft,
                                                               request.collisionTop,
                                                               request.collisionRight,
                                                               request.collisionBottom,
                                                               this.adjustedDestination)) {
                    int adjusted = this.adjustedDestination[UnitPositioning.X_INDEX] & WORD_MASK |
                                   this.adjustedDestination[UnitPositioning.Y_INDEX] << WORD_BITS;

                    if (adjusted != position) {
                        position                    = adjusted;
                        request.adjustedDestination = position;
                    }
                }
            }
        }

        boolean result = this.searchLocal(game, unit, request, position, followingRegion);

        if (result) {
            int lastPosition = request.getWaypoint(request.waypointCount - 1);

            if (request.destinationReached != 0) {
                if (!finalDestination && lastPosition == position) {
                    request.destinationReached = 0;
                } else {
                    request.adjustedDestination = lastPosition;
                }
            } else if (followingRegion == NO_FOLLOWING_REGION) {
                request.destinationReached  = 1;
                request.destinationAdjusted = 1;
                request.adjustedDestination = lastPosition;
            }
        }

        return result;
    }

    /**
     * Runs the bounded best-first search from the source position of a request toward a goal position and writes the waypoints of the terminal,
     * limited or fallback node into the request.
     *
     * @param game                the game with the terrain and the units
     * @param unit                the moving unit
     * @param request             the request with the source position and the adjusted destination
     * @param destinationPosition the packed goal position WORDs
     * @param followingRegion     the route region after the source region, or {@link #NO_FOLLOWING_REGION}
     * @return {@code true} if the route has a waypoint, {@code false} otherwise
     */
    private boolean searchLocal(@NotNull Game game, @NotNull Unit unit, @NotNull PathRequest request, int destinationPosition, int followingRegion) {
        int sourceRegion = Short.toUnsignedInt(request.sourceRegion);
        int destinationRegion = TerrainRegionLookupUtils.lookup(this.terrainRegions,
                                                                (short) request.adjustedDestination,
                                                                (short) (request.adjustedDestination >>> WORD_BITS));
        int neighborRegion = followingRegion < TerrainRegions.REGION_LIMIT ? followingRegion : destinationRegion;
        int neighborCount = this.terrainRegions.getWalkableNeighborCount(neighborRegion);

        Arrays.fill(this.regionMarks, 0);

        for (int index = 0; index < neighborCount; index++) {
            int region = this.terrainRegions.getNeighborRegion(neighborRegion, index);

            if (region != sourceRegion) {
                this.regionMarks[region] = NEIGHBOR_MARK;
            }
        }

        boolean penalizeOtherRegions = followingRegion < TerrainRegions.REGION_LIMIT &&
                                       this.terrainRegions.getTerrainType(followingRegion) != TerrainRegions.BLOCKED_TERRAIN;

        request.localNodeCount        = 0;
        request.localMaximumHeapCount = 0;

        this.obstacleCollector.initialize(game,
                                          unit,
                                          (int) request.targetUnitIndex,
                                          (short) destinationPosition, (short) (destinationPosition >>> WORD_BITS),
                                          request.exclusionRectangle);
        this.resetVisited(game.getMap().getWidth() * TerrainTileset.TILE_SIZE);

        this.nodeCount             = 1;
        this.heapCount             = 1;
        this.heap[0]               = NODE_CAPACITY;
        this.heap[HEAP_ROOT]       = 0;
        this.totals[NODE_CAPACITY] = -1;
        this.positions[0]          = request.sourcePosition;
        this.regions[0]            = sourceRegion;
        this.directions[0]         = PathDirections.ALL;
        this.depths[0]             = 0;
        this.parents[0]            = -1;
        this.heuristics[0]         = NO_ESTIMATE;
        this.costs[0]              = 0;
        this.totals[0]             = NO_ESTIMATE;
        this.neighboring[0]        = false;
        this.nextRegion[0]         = false;
        this.terminal[0]           = false;
        this.closed[0]             = false;

        int openFollowing = 0;
        int openNeighbors = 0;
        boolean foundTerminal = false;
        boolean limited = false;
        int selected = -1;

        while (this.heapCount != 0) {
            int current = this.removeFirst();

            if (this.terminal[current]) {
                request.destinationReached = 1;
                selected                   = current;

                break;
            }

            if (this.directions[current] != 0) {
                this.directions[current] = this.filterDirections(this.positions[current], this.directions[current]);
            }

            if (openFollowing > 0 && !foundTerminal &&
                (destinationPosition != request.adjustedDestination || this.nodeCount * NODE_RECORD_SIZE >= FOLLOWING_REGION_NODE_LIMIT)) {
                limited = true;
            }

            if (openNeighbors != 0 && !foundTerminal && this.nodeCount * NODE_RECORD_SIZE >= NEIGHBOR_REGION_NODE_LIMIT) {
                limited = true;
            }

            if (this.nextRegion[current] && limited) {
                selected = current;

                break;
            }

            this.closed[current] = true;

            if (this.nextRegion[current]) {
                openFollowing--;
            }

            if (this.neighboring[current]) {
                openNeighbors--;

                if (limited) {
                    break;
                }
            }

            if (foundTerminal || limited || this.directions[current] == 0) {
                continue;
            }

            this.obstacleCollector.collect((short) this.positions[current], (short) (this.positions[current] >>> WORD_BITS),
                                           this.directions[current]);

            for (int index = 0; index < this.obstacleCollector.getSegmentCount(); index++) {
                this.addVisitedRectangle(this.obstacleCollector.getSegment(index));
            }

            for (int index = 0; index < this.obstacleCollector.getCandidateCount(); index++) {
                int position = this.obstacleCollector.getCandidatePosition(index);
                int region = TerrainRegionLookupUtils.lookup(this.terrainRegions, (short) position, (short) (position >>> WORD_BITS));
                int mask = this.obstacleCollector.getCandidateDirections(index);
                boolean candidateTerminal = this.obstacleCollector.getCandidateTerminal(index) != 0;
                boolean candidateFollowing = region == followingRegion;
                boolean candidateNeighbor = this.regionMarks[region] != 0;

                if (candidateTerminal) {
                    foundTerminal = true;
                } else if (mask != 0) {
                    mask = this.filterDirections(position, mask);

                    if (mask == 0) {
                        continue;
                    }
                }

                int node = 1;

                while (node < this.nodeCount && this.positions[node] != position) {
                    node++;
                }

                if (node == this.nodeCount) {
                    this.nodeCount++;
                    this.positions[node]  = position;
                    this.regions[node]    = region;
                    this.parents[node]    = current;
                    this.directions[node] = mask;
                    this.depths[node]     = (this.depths[current] + 1) & BYTE_MASK;

                    int distance = calculateFixedPointDistance(position, this.positions[current]);

                    if (penalizeOtherRegions && region != followingRegion && region != sourceRegion) {
                        distance *= OTHER_REGION_COST_FACTOR;
                    }

                    this.heuristics[node]  = calculateFixedPointDistance(position, destinationPosition);
                    this.costs[node]       = this.costs[current] + distance;
                    this.totals[node]      = this.costs[node] + this.heuristics[node];
                    this.nextRegion[node]  = candidateFollowing;
                    this.neighboring[node] = candidateNeighbor;
                    this.closed[node]      = mask == 0;
                    this.terminal[node]    = candidateTerminal;

                    if (mask != 0 || candidateTerminal) {
                        this.closed[node] = false;

                        this.raise(++this.heapCount, node);

                        if (candidateFollowing) {
                            openFollowing++;
                        }

                        if (candidateNeighbor) {
                            openNeighbors++;
                        }
                    }

                    if (this.heapCount == HEAP_CAPACITY || this.nodeCount == NODE_CAPACITY) {
                        break;
                    }
                } else if (this.parents[current] != node) {
                    int distance = calculateFixedPointDistance(position, this.positions[current]);

                    if (penalizeOtherRegions && region != followingRegion && region != sourceRegion) {
                        distance *= OTHER_REGION_COST_FACTOR;
                    }

                    int cost = this.costs[current] + distance;

                    if (cost < this.costs[node]) {
                        this.parents[node] = current;
                        this.depths[node]  = (this.depths[current] + 1) & BYTE_MASK;
                        this.costs[node]   = cost;

                        int total = cost + this.heuristics[node];

                        if (!this.closed[node]) {
                            int previousTotal = this.totals[node];

                            this.totals[node] = total;

                            if (previousTotal != total) {
                                int heapIndex = this.heapCount;

                                while (heapIndex > 0 && this.heap[heapIndex] != node) {
                                    heapIndex--;
                                }

                                if (heapIndex > 0) {
                                    if (total < previousTotal) {
                                        this.raise(heapIndex, node);
                                    } else {
                                        this.lower(heapIndex, node);
                                    }
                                }
                            }
                        } else {
                            this.totals[node]     = total;
                            this.directions[node] = mask;
                            this.closed[node]     = false;
                            this.terminal[node]   = candidateTerminal;

                            this.raise(++this.heapCount, node);

                            if (candidateFollowing) {
                                openFollowing++;
                            }

                            if (candidateNeighbor) {
                                openNeighbors++;
                            }
                        }

                        if (this.heapCount == HEAP_CAPACITY) {
                            break;
                        }
                    } else if (!this.closed[node]) {
                        this.directions[node] &= mask;
                    }
                }
            }

            request.localMaximumHeapCount = (short) Math.max(request.localMaximumHeapCount, this.heapCount);

            if ((this.heapCount == HEAP_CAPACITY || this.nodeCount == NODE_CAPACITY) && !foundTerminal && openFollowing == 0) {
                break;
            }
        }

        if (selected < 0) {
            request.limitedLocalSearch = 1;

            int open = 0;
            int openInDestination = 0;

            for (int node = 1; node < this.nodeCount; node++) {
                int region = this.regions[node];

                if (this.regionMarks[region] != 0) {
                    this.regionMarks[region]++;
                }

                if (this.closed[node]) {
                    continue;
                }

                if (this.directions[node] != 0) {
                    this.directions[node] = this.filterDirections(this.positions[node], this.directions[node]);
                }

                if (this.directions[node] == 0) {
                    this.closed[node] = true;

                    if (this.nextRegion[node]) {
                        openFollowing--;
                    }
                } else {
                    open++;

                    if (region == destinationRegion) {
                        openInDestination++;
                    }
                }
            }

            if (destinationPosition == request.adjustedDestination) {
                open = openInDestination;

                for (int index = 0; index < neighborCount; index++) {
                    int region = this.terrainRegions.getNeighborRegion(neighborRegion, index);

                    if (region == sourceRegion) {
                        continue;
                    }

                    if (this.regionMarks[region] < MARK_WITH_NODE) {
                        open++;

                        break;
                    }

                    open = Math.max(0, open - this.regionMarks[region] / REGION_MARK_DIVISOR);
                }
            }

            boolean exhausted = open == 0 && openFollowing == 0;

            this.heuristics[0] = calculateFixedPointDistance(this.positions[0], destinationPosition);
            selected           = 0;

            if (!exhausted) {
                int minimum = NO_ESTIMATE;

                for (int node = 1; node < this.nodeCount; node++) {
                    int region = this.regions[node];
                    int estimate = this.heuristics[node];

                    if (region == destinationRegion || region == followingRegion) {
                        estimate += this.costs[node] / DESTINATION_COST_DIVISOR;
                    } else if (this.regionMarks[region] == 0) {
                        estimate *= region == sourceRegion ? SOURCE_REGION_ESTIMATE_FACTOR : OTHER_REGION_ESTIMATE_FACTOR;
                    } else {
                        estimate = estimate * NEIGHBOR_REGION_ESTIMATE_NUMERATOR / NEIGHBOR_REGION_ESTIMATE_DENOMINATOR;
                    }

                    if (this.closed[node]) {
                        estimate <<= CLOSED_ESTIMATE_SHIFT;
                    }

                    if (estimate < minimum) {
                        minimum  = estimate;
                        selected = node;
                    }
                }
            } else {
                int minimum = this.heuristics[0];

                for (int node = 1; node < this.nodeCount; node++) {
                    if (this.heuristics[node] >= minimum) {
                        continue;
                    }

                    if (destinationPosition != request.adjustedDestination && this.regions[node] != sourceRegion) {
                        continue;
                    }

                    selected = node;
                    minimum  = this.heuristics[node];
                }

                request.destinationReached  = 1;
                request.destinationAdjusted = 1;
            }
        }

        request.localNodeCount = (short) this.nodeCount;

        int count = 0;

        while (selected >= 0 && count < BACKTRACK_CAPACITY) {
            this.backtrack[count++] = this.positions[selected];
            selected                = this.parents[selected];
        }

        int waypoints;

        if (count <= DIRECT_ROUTE_POSITIONS) {
            if (count == DIRECT_ROUTE_POSITIONS) {
                request.setWaypoint(0, this.backtrack[0]);
            }

            waypoints = count - 1;
        } else {
            waypoints = 0;

            for (int index = count - FIRST_WAYPOINT_OFFSET; index >= 1 && waypoints < PathRequest.WAYPOINT_CAPACITY - 1; index--) {
                request.setWaypoint(waypoints++, this.backtrack[index]);
            }

            request.setWaypoint(waypoints++, this.backtrack[0]);
        }

        request.waypointCount = (short) waypoints;
        request.waypointIndex = 0;

        for (int index = 0; index < neighborCount; index++) {
            int region = this.terrainRegions.getNeighborRegion(neighborRegion, index);

            if (region != sourceRegion) {
                this.regionMarks[region] = 0;
            }
        }

        return waypoints > 0;
    }

    private int removeFirst() {
        int first = this.heap[HEAP_ROOT];
        int node = this.heap[this.heapCount--];

        this.lower(HEAP_ROOT, node);

        return first;
    }

    private void raise(int heapIndex, int node) {
        while (this.totals[node] <= this.totals[this.heap[heapIndex / HEAP_ARITY]]) {
            this.heap[heapIndex] = this.heap[heapIndex / HEAP_ARITY];
            heapIndex /= HEAP_ARITY;
        }

        this.heap[heapIndex] = node;
    }

    private void lower(int heapIndex, int node) {
        while (heapIndex <= this.heapCount / HEAP_ARITY) {
            int child = heapIndex * HEAP_ARITY;

            if (child < this.heapCount && this.totals[this.heap[child + 1]] < this.totals[this.heap[child]]) {
                child++;
            }

            if (this.totals[node] <= this.totals[this.heap[child]]) {
                break;
            }

            this.heap[heapIndex] = this.heap[child];
            heapIndex            = child;
        }

        this.heap[heapIndex] = node;
    }

    /**
     * Resets the visited-rectangle coverage to one band over the map width with the empty record {@code 0}; the map entry after it is {@code -1}.
     *
     * @param widthPixels the map width in pixels, a WORD
     */
    void resetVisited(int widthPixels) {
        this.horizontalBoundaries[0] = 0;
        this.bandRecords[0]          = 0;
        this.horizontalBoundaries[1] = widthPixels & WORD_MASK;
        this.records[0]              = 0;
        this.bandCount               = 1;
        this.bandRecords[1]          = -1;
    }

    /**
     * Adds a visited rectangle to the coverage: splits the bands at the rectangle's {@code left} and {@code right + 1} and adds its vertical interval
     * to every band it covers, merging touching intervals.
     * <p>
     * Full bands or full intervals of a band stop the addition and keep the changes made so far.
     *
     * @param rectangle the left, top, right and bottom WORDs
     * @return {@code true} if the bands and the intervals of every covered band had room, {@code false} otherwise
     */
    boolean addVisitedRectangle(long rectangle) {
        int left = (short) rectangle;
        int top = (short) (rectangle >>> PathObstacleCollector.RECTANGLE_TOP_SHIFT);
        int right = (short) (rectangle >>> PathObstacleCollector.RECTANGLE_RIGHT_SHIFT);
        int bottom = (short) (rectangle >>> PathObstacleCollector.RECTANGLE_BOTTOM_SHIFT);
        int band = 0;

        while (left >= this.horizontalBoundaries[band + 1]) {
            band++;
        }

        if (this.horizontalBoundaries[band] < left) {
            // The part before left takes a copy of the record.
            if (!this.splitBand(band, left, true)) {
                return false;
            }

            band++;
        }

        // Compare the left coordinate before the first band and the band boundaries after it.
        if (left > right) {
            return true;
        }

        do {
            if (right < this.horizontalBoundaries[band + 1] - 1 && !this.splitBand(band, right + 1, false)) {
                return false;
            }

            int record = this.bandRecords[band] * RECORD_SIZE;
            int count = this.records[record];
            int interval = 0;

            while (interval < count && top > this.records[record + FIRST_INTERVAL_END + INTERVAL_SIZE * interval] + 1) {
                interval++;
            }

            int first = record + FIRST_INTERVAL + INTERVAL_SIZE * interval;

            if (interval == count) {
                if (count == INTERVALS_PER_BAND) {
                    return false;
                }

                this.records[first]                = top;
                this.records[first + INTERVAL_END] = bottom;
                this.records[record]++;
            } else if (bottom >= this.records[first] - 1) {
                if (this.records[first] > top) {
                    this.records[first] = top;
                }

                if (this.records[first + INTERVAL_END] < bottom) {
                    this.records[first + INTERVAL_END] = bottom;
                }

                // One pass over the pairs from the first merges every interval touching its predecessor.
                int remaining = this.records[record] - 1;
                int previous = record + FIRST_INTERVAL;

                while (remaining > 0) {
                    if (this.records[previous + INTERVAL_SIZE] - 1 <= this.records[previous + INTERVAL_END]) {
                        if (this.records[previous + INTERVAL_END] < this.records[previous + NEXT_INTERVAL_END]) {
                            this.records[previous + INTERVAL_END] = this.records[previous + NEXT_INTERVAL_END];
                        }

                        this.records[record]--;

                        if (remaining > 1) {
                            System.arraycopy(this.records, previous + SECOND_NEXT_INTERVAL,
                                             this.records, previous + INTERVAL_SIZE,
                                             INTERVAL_SIZE * (remaining - 1));
                        }
                    } else {
                        previous += INTERVAL_SIZE;
                    }

                    remaining--;
                }
            } else {
                if (count == INTERVALS_PER_BAND) {
                    return false;
                }

                System.arraycopy(this.records, first, this.records, first + INTERVAL_SIZE, INTERVAL_SIZE * (count - interval));

                this.records[first]                = top;
                this.records[first + INTERVAL_END] = bottom;
                this.records[record]++;
            }

            band++;
        } while (this.horizontalBoundaries[band] <= right);

        return true;
    }

    /**
     * Splits a band at a boundary: the boundaries and the map entries from the band move up by one (the entry past the last band too), the next free
     * record becomes a copy of the count and used intervals of the band's record, and the lower part (a split at the left) or the upper part (a split
     * at {@code right + 1}) takes it.
     *
     * @param band           the band to split
     * @param horizontal     the boundary of the split
     * @param lowerTakesCopy whether the lower part takes the copied record
     * @return {@code true} if the bands had room for the split, {@code false} otherwise
     */
    private boolean splitBand(int band, int horizontal, boolean lowerTakesCopy) {
        int count = this.bandCount;

        if (count == BAND_CAPACITY) {
            return false;
        }

        int source = this.bandRecords[band] * RECORD_SIZE;

        System.arraycopy(this.horizontalBoundaries, band, this.horizontalBoundaries, band + 1, count - band + 1);
        System.arraycopy(this.bandRecords, band, this.bandRecords, band + 1, count - band + 1);

        this.bandCount                                     = count + 1;
        this.bandRecords[lowerTakesCopy ? band : band + 1] = count;

        System.arraycopy(this.records, source, this.records, count * RECORD_SIZE, FIRST_INTERVAL + INTERVAL_SIZE * this.records[source]);

        this.horizontalBoundaries[band + 1] = horizontal;

        return true;
    }

    /**
     * Removes the directions that lead into visited rectangles of the coverage from a direction BYTE at a position.
     *
     * @param position   the packed position WORDs
     * @param directions the direction BYTE of {@link PathDirections} bits
     * @return the remaining directions
     */
    int filterDirections(int position, int directions) {
        int horizontal = (short) position;
        int vertical = (short) (position >>> WORD_BITS);
        int band = 0;

        while (horizontal >= this.horizontalBoundaries[band + 1]) {
            band++;
        }

        int record = this.bandRecords[band] * RECORD_SIZE;
        int interval = 0;

        while (interval < this.records[record] && this.records[record + FIRST_INTERVAL_END + INTERVAL_SIZE * interval] < vertical) {
            interval++;
        }

        if (interval == this.records[record]) {
            return directions;
        }

        int first = record + FIRST_INTERVAL + INTERVAL_SIZE * interval;
        int mask = this.records[first] < vertical ? 0 : PathDirections.NORTHEAST | PathDirections.NORTHWEST | PathDirections.CARDINAL_UP;

        if (vertical >= this.records[first + INTERVAL_END]) {
            mask |= PathDirections.SOUTHEAST | PathDirections.SOUTHWEST | PathDirections.CARDINAL_DOWN;
        }

        int originalMask = mask;

        if (this.horizontalBoundaries[band] == horizontal) {
            mask |= PathDirections.SOUTHWEST | PathDirections.NORTHWEST | PathDirections.CARDINAL_LEFT;

            if (band > 0) {
                int previous = this.bandRecords[band - 1] * RECORD_SIZE;

                for (int index = 0; index < this.records[previous]; index++) {
                    int start = this.records[previous + FIRST_INTERVAL + INTERVAL_SIZE * index];
                    int end = this.records[previous + FIRST_INTERVAL_END + INTERVAL_SIZE * index];

                    if (vertical > end) {
                        continue;
                    }

                    if (start <= vertical) {
                        mask &= ~(PathDirections.NORTHWEST | PathDirections.CARDINAL_LEFT) & PathDirections.ALL;

                        if (vertical <= start) {
                            mask = originalMask | (PathDirections.SOUTHWEST | PathDirections.NORTHWEST);
                        }

                        if (vertical < end) {
                            mask &= ~PathDirections.SOUTHWEST & PathDirections.ALL;
                        }
                    }

                    break;
                }
            }
        }

        originalMask = mask;

        if (this.horizontalBoundaries[band + 1] - 1 == horizontal) {
            mask |= PathDirections.NORTHEAST | PathDirections.SOUTHEAST | PathDirections.CARDINAL_RIGHT;

            if (band + 1 < this.bandCount) {
                int following = this.bandRecords[band + 1] * RECORD_SIZE;

                for (int index = 0; index < this.records[following]; index++) {
                    int start = this.records[following + FIRST_INTERVAL + INTERVAL_SIZE * index];
                    int end = this.records[following + FIRST_INTERVAL_END + INTERVAL_SIZE * index];

                    if (vertical > end) {
                        continue;
                    }

                    if (start <= vertical) {
                        mask &= ~(PathDirections.NORTHEAST | PathDirections.CARDINAL_RIGHT) & PathDirections.ALL;

                        if (vertical <= start) {
                            mask = originalMask | (PathDirections.NORTHEAST | PathDirections.SOUTHEAST);
                        }

                        if (vertical < end) {
                            mask &= ~PathDirections.SOUTHEAST & PathDirections.ALL;
                        }
                    }

                    break;
                }
            }
        }

        return directions & mask;
    }
}
