package org.fogofwarlabs.sc4j.scr.game.path;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrFlingyTypeView;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.movement.MovementStates;
import org.fogofwarlabs.sc4j.scr.game.movement.ScrFlingyProperties;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegionLookupUtils;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Drives the paths of units: searches a route into the retained {@link PathRequest} with {@link LocalPathSearch}, stores it in a {@link PathPool}
 * record and projects the current waypoint into the unit's movement targets.
 */
public final class UnitPath {
    /**
     * The multiple of the stopping distance by which the position of an accelerating target is predicted.
     */
    private static final int PREDICTION_DISTANCE_FACTOR = 3;
    /**
     * A stored partial route is continued only while its region index plus this margin is below its region count.
     */
    private static final int CONTINUATION_REGION_MARGIN = 4;
    /**
     * The bit position of {@link UnitStatusFlags#IMMOVABLE}, set when the search adjusted the destination.
     */
    private static final int IMMOVABLE_STATUS_SHIFT = 19;
    /**
     * The bit of the request's destination-adjusted BYTE that becomes the immovable status.
     */
    private static final int DESTINATION_ADJUSTED_BIT = 1;
    /**
     * The distance below which a waypoint near the path destination makes the unit brake on the path step.
     */
    private static final int BRAKE_DISTANCE = 16;
    /**
     * The distance up to which a unit may leave its current waypoint for the next one.
     */
    private static final int WAYPOINT_ARRIVAL_DISTANCE = 16;
    /**
     * A unit near its waypoint keeps it while this many times the distance is at least the turn to its heading.
     */
    private static final int TURN_DISTANCE_FACTOR = 2;
    /**
     * The fraction bits of a fixed-point displacement.
     */
    private static final int FIXED_POINT_SHIFT = 8;

    /**
     * The mask of an unsigned BYTE movement state or direction difference.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of an unsigned WORD coordinate.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The shift of the vertical WORD of a packed position.
     */
    private static final int WORD_BITS = 16;

    /**
     * Applies the movement-state guard of the path states.
     * <p>
     * A unit at its movement destination or braking enters {@link MovementStates#AT_MOVE_TARGET}; a ground unit with the collision status releases
     * its path and enters {@link MovementStates#CHECK_ILLEGAL}; a missing path (when one is required) enters {@link MovementStates#ANOTHER_PATH} and
     * a path flagged with a new move target enters {@link MovementStates#NEW_MOVE_TARGET}, both as {@link MovementStates#TURN_AND_START} while the
     * unit is in a collision or slide state.
     *
     * @param game        the game retaining the path pool
     * @param unit        the moving unit
     * @param requirePath whether a missing path changes the movement state
     * @return {@code true} if the guard selected another movement state, {@code false} otherwise
     * @throws IllegalStateException         if the unit has no sprite
     * @throws UnsupportedOperationException if the path pool is not initialized
     */
    public static boolean updateMovementGuard(@NotNull Game game, @NotNull Unit unit, boolean requirePath) {
        int nextState;

        if ((short) unit.requireSprite().getX() == (short) getPackedMovementDestination(unit) &&
            (short) unit.requireSprite().getY() == (short) (getPackedMovementDestination(unit) >>> WORD_BITS) ||
            (unit.getMovementFlags() & MovementFlags.BRAKING) != 0) {
            nextState = MovementStates.AT_MOVE_TARGET;
        } else if ((unit.getStatus() & (UnitStatusFlags.COLLISION | UnitStatusFlags.GROUND_UNIT)) ==
                   (UnitStatusFlags.COLLISION | UnitStatusFlags.GROUND_UNIT)) {
            if (unit.getPathIndex() >= 0) {
                game.getPathSystem().requirePathPool().release(unit.getPathIndex());
                unit.setPathIndex(-1);
            }

            nextState = MovementStates.CHECK_ILLEGAL;
        } else {
            int pathIndex = unit.getPathIndex();

            if (requirePath && pathIndex < 0) {
                nextState = MovementStates.ANOTHER_PATH;
            } else if (pathIndex >= 0 && (game.getPathSystem().requirePathPool().getFlags(pathIndex) & PathPool.FLAG_NEW_MOVE_TARGET) != 0) {
                nextState = MovementStates.NEW_MOVE_TARGET;
            } else {
                return false;
            }

            if (((unit.getMovementState() - MovementStates.FIX_COLLISION) & BYTE_MASK) <=
                MovementStates.TERRAIN_SLIDE - MovementStates.FIX_COLLISION) {
                nextState = MovementStates.TURN_AND_START;
            }
        }

        unit.setMovementState(nextState);

        return true;
    }

    /**
     * Reads the BYTE timer of the unit's path and decrements it when it is not {@code 0}.
     *
     * @param game the game retaining the path pool
     * @param unit the unit owning the path
     * @return {@code true} if the path exists and its timer was already {@code 0}, {@code false} otherwise
     * @throws UnsupportedOperationException if the path pool is not initialized
     */
    public static boolean updateTimer(@NotNull Game game, @NotNull Unit unit) {
        int pathIndex = unit.getPathIndex();

        if (pathIndex < 0) {
            return false;
        }

        PathPool pool = game.getPathSystem().requirePathPool();
        int timer = pool.getTimer(pathIndex);

        if (timer == 0) {
            return true;
        }

        pool.setTimer(pathIndex, timer - 1);

        return false;
    }

    /**
     * Sets the BYTE timer of the unit's path, if it has one.
     *
     * @param game  the game retaining the path pool
     * @param unit  the unit owning the path
     * @param timer the value narrowed to a BYTE
     * @throws UnsupportedOperationException if the path pool is not initialized
     */
    public static void setTimer(@NotNull Game game, @NotNull Unit unit, int timer) {
        if (unit.getPathIndex() >= 0) {
            game.getPathSystem().requirePathPool().setTimer(unit.getPathIndex(), timer);
        }
    }

    /**
     * Moves the unit's movement destination to an adjusted path destination, or to the predicted position of an accelerating movement target when
     * that position lies inside the map, and marks the movement as ordered when the destination changes.
     *
     * @param unit        the moving unit
     * @param flingyTypes the flingy type values of the units
     * @param destination the packed adjusted destination WORDs
     * @param width       the map width in pixels
     * @param height      the map height in pixels
     * @throws IllegalStateException if the target has no sprite
     */
    static void applyAdjustedDestination(@NotNull Unit unit, @NotNull ScrFlingyProperties flingyTypes, int destination, int width, int height) {
        Unit target = unit.getMovementTargetUnit();
        int unitDestination = destination;

        if (target != null && (target.getMovementFlags() & MovementFlags.ACCELERATING) != 0) {
            long prediction = calculateTargetPrediction(unit, target.getTargetHeading(), flingyTypes);
            int horizontal = (short) target.requireSprite().getX() + ((int) prediction >> FIXED_POINT_SHIFT);
            int vertical = (short) target.requireSprite().getY() +
                           ((int) (prediction >>> FlingyMotionUtils.DIRECTION_VECTOR_VERTICAL_SHIFT) >> FIXED_POINT_SHIFT);

            if (Integer.compareUnsigned(horizontal, width) < 0 && Integer.compareUnsigned(vertical, height) < 0) {
                unitDestination = horizontal & WORD_MASK | vertical << WORD_BITS;
            }
        }

        if (getPackedMovementDestination(unit) != unitDestination) {
            unit.setMovementFlags(unit.getMovementFlags() & ~MovementFlags.BRAKING | MovementFlags.ORDERED);
            unit.setMovementDestinationX(unitDestination);
            unit.setMovementDestinationY(unitDestination >>> WORD_BITS);
            unit.setMovementWaypointX(unitDestination);
            unit.setMovementWaypointY(unitDestination >>> WORD_BITS);
        }
    }

    /**
     * Calculates the predicted displacement of a moving target: three times the stopping distance of the unit in a direction. The stopping distance
     * is the flingy halt distance at top speed and flingy acceleration, else derived from the speed and acceleration, and {@code 0} for a unit at
     * rest or with a movement control other than {@link ScrFlingyTypeView#MOVEMENT_CONTROL_FLINGY}.
     *
     * @param unit        the unit whose speed and acceleration give the stopping distance
     * @param direction   the direction of the displacement
     * @param flingyTypes the flingy type values of the units
     * @return the packed fixed-point displacement vector (X in the low DWORD, Y in the high DWORD)
     * @throws ArithmeticException if the stopping distance is derived from the speed with an acceleration of {@code 0}
     */
    static long calculateTargetPrediction(@NotNull Unit unit, int direction, @NotNull ScrFlingyProperties flingyTypes) {
        int distance = FlingyMotionUtils.calculateStoppingDistance(flingyTypes, unit);

        return FlingyMotionUtils.calculateDirectionVector(direction, distance * PREDICTION_DISTANCE_FACTOR);
    }

    /**
     * @param unit the unit
     * @return the packed movement destination: the X coordinate in the low WORD, the Y coordinate shifted into the high WORD
     */
    private static int getPackedMovementDestination(@NotNull Unit unit) {
        return unit.getMovementDestinationX() | unit.getMovementDestinationY() << WORD_BITS;
    }


    private final @NotNull PathRequest request;
    private final short @NotNull [] allocationCounts;

    private @Nullable Unit consideredCollisionUnit;

    /**
     * Creates a {@link UnitPath} instance with reusable route request storage.
     */
    public UnitPath() {
        this.request          = new PathRequest();
        this.allocationCounts = new short[PathPool.ROUTE_COUNTS_LENGTH];

        this.consideredCollisionUnit = null;
    }

    /**
     * @return the moving unit that still blocks the routes of {@link #update(Game, Unit, int)}, or {@code null}
     */
    public @Nullable Unit getConsideredCollisionUnit() {
        return this.consideredCollisionUnit;
    }

    /**
     * Sets the moving unit that still blocks the routes of {@link #update(Game, Unit, int)}.
     *
     * @param unit the moving unit that blocks the routes, or {@code null}
     */
    public void setConsideredCollisionUnit(@Nullable Unit unit) {
        this.consideredCollisionUnit = unit;
    }


    /**
     * Requests a path to a destination with the considered collision unit and without other moving units, and projects its current waypoint.
     *
     * @param game        the game with its path workspaces
     * @param unit        the unit requesting the route
     * @param destination the packed destination WORD pair
     * @return {@code true} if the unit has a path afterwards, {@code false} otherwise
     * @throws NullPointerException  if the path pool or the local path workspace is not initialized, or the world was built without game data
     * @throws IllegalStateException if the unit has no sprite
     */
    public boolean update(@NotNull Game game, @NotNull Unit unit, int destination) {
        return this.update(game, unit, destination, this.consideredCollisionUnit, false);
    }

    /**
     * Requests a path to a destination and projects its current waypoint.
     * <p>
     * A unit still away from its current waypoint keeps it. A unit without the ground-unit status gets a one-waypoint path straight to the
     * destination. A ground unit first advances its stored path; past its last waypoint the stored region route is continued while enough regions
     * remain, else a new route is searched. An adjusted destination replaces the unit's movement destination and sets its immovable status.
     *
     * @param game          the game with its path workspaces
     * @param unit          the unit requesting the route
     * @param destination   the packed destination WORD pair
     * @param collisionUnit the moving unit that still blocks the route, or {@code null}
     * @param includeMoving whether every moving unit blocks the route
     * @return {@code true} if the unit has a path afterwards, {@code false} otherwise
     * @throws NullPointerException  if the path pool or the local path workspace is not initialized, or the world was built without game data
     * @throws IllegalStateException if the unit has no sprite
     */
    public boolean update(@NotNull Game game, @NotNull Unit unit, int destination, @Nullable Unit collisionUnit, boolean includeMoving) {
        PathPool pool = game.getPathSystem().getPathPool();

        if (pool == null) {
            throw Exceptions.newNullPointerException("Native path pool is not initialized.");
        }

        if (this.isKeepingCurrentWaypoint(game, unit, pool)) {
            return true;
        }

        int destinationX = (short) destination;
        int destinationY = (short) (destination >>> WORD_BITS);

        if ((unit.getStatus() & UnitStatusFlags.GROUND_UNIT) == 0) {
            game.getUnitPositioning().setMovementDestination(unit, destinationX, destinationY);

            if (unit.getPathIndex() >= 0) {
                pool.release(unit.getPathIndex());
            }

            int pathIndex = pool.allocate();

            unit.setPathIndex(pathIndex);

            if (pathIndex >= 0) {
                pool.setRegionCount(pathIndex, 1);
                pool.setRegionIndex(pathIndex, 0);
                pool.setWaypointCount(pathIndex, 1);
                pool.setWaypointIndex(pathIndex, 0);
                pool.setFullRegionCount(pathIndex, 1);
                pool.setSourceX(pathIndex, unit.requireSprite().getX());
                pool.setSourceY(pathIndex, unit.requireSprite().getY());
                pool.setWaypointPosition(pathIndex, destination);
                pool.setDestination(pathIndex, destination);
                pool.setFlags(pathIndex, 0);
                pool.setTimer(pathIndex, 0);
                pool.setWaypoint(pathIndex, 0, destination);
            }

            return true;
        }

        unit.setMovementFlags(unit.getMovementFlags() & ~MovementFlags.BRAKE_ON_PATH_STEP | MovementFlags.MOVING);

        LocalPathSearch search = game.getPathSystem().getLocalPathSearch();
        boolean continued = false;

        if (unit.getPathIndex() >= 0) {
            int pathIndex = unit.getPathIndex();
            byte waypointIndex = (byte) (pool.getWaypointIndex(pathIndex) + 1);

            pool.setWaypointIndex(pathIndex, waypointIndex);

            if (waypointIndex < pool.getWaypointCount(pathIndex)) {
                this.projectWaypoint(game, unit, pool);

                return true;
            }

            int waypointCount = pool.getWaypointCount(pathIndex);
            int regionCount = pool.getRegionCount(pathIndex);

            this.request.sourcePosition      = unit.requireSprite().getX() | unit.requireSprite().getY() << WORD_BITS;
            this.request.destinationPosition = pool.getDestination(pathIndex);
            this.request.regionCount         = (short) regionCount;
            this.request.fullRegionCount     = (byte) pool.getFullRegionCount(pathIndex);
            this.request.regionIndex         = (short) pool.getRegionIndex(pathIndex);

            for (int index = 0; index < regionCount; index++) {
                this.request.setRegion(index, (short) pool.getRouteRegion(pathIndex, waypointCount, index));
            }

            pool.release(pathIndex);
            unit.setPathIndex(-1);

            if (this.request.regionIndex + 1 < regionCount &&
                (Byte.toUnsignedInt(this.request.fullRegionCount) == regionCount ||
                 this.request.regionIndex + CONTINUATION_REGION_MARGIN < regionCount) &&
                this.request.sourcePosition != this.request.destinationPosition) {
                if (search == null) {
                    throw Exceptions.newNullPointerException("Native local path workspace is not initialized.");
                }

                search.initializeMovementContext(includeMoving, collisionUnit == null ? 0 : collisionUnit.getIndex());

                continued = search.search(game, unit, this.request, true) && this.request.regionCount != 0 && this.request.waypointCount != 0;
            }
        }

        if (!continued) {
            if (search == null) {
                throw Exceptions.newNullPointerException("Native local path workspace is not initialized.");
            }

            search.initializeMovementContext(includeMoving, collisionUnit == null ? 0 : collisionUnit.getIndex());

            this.request.sourcePosition      = this.request.sourcePosition & WORD_MASK << WORD_BITS | unit.requireSprite().getX() & WORD_MASK;
            this.request.sourcePosition      = this.request.sourcePosition & WORD_MASK | unit.requireSprite().getY() << WORD_BITS;
            this.request.destinationPosition = destination;

            if (!search.search(game, unit, this.request, false) || this.request.regionCount == 0 || this.request.waypointCount == 0) {
                return false;
            }
        }

        if (this.request.destinationReached != 0 && this.request.adjustedDestination != destination) {
            destination = this.request.adjustedDestination;

            applyAdjustedDestination(unit,
                                     game.requireGameData().getFlingyProperties(),
                                     destination,
                                     game.getMap().getWidth() * TerrainTileset.TILE_SIZE, game.getMap().getHeight() * TerrainTileset.TILE_SIZE);
            unit.setStatus(unit.getStatus() & ~UnitStatusFlags.IMMOVABLE |
                           (this.request.destinationAdjusted & DESTINATION_ADJUSTED_BIT) << IMMOVABLE_STATUS_SHIFT);
        }

        this.allocationCounts[PathPool.ROUTE_REGION_COUNT_INDEX]   = this.request.regionCount;
        this.allocationCounts[PathPool.ROUTE_WAYPOINT_COUNT_INDEX] = this.request.waypointCount;

        int pathIndex = pool.allocateForRoute(this.allocationCounts);

        if (pathIndex < 0) {
            unit.setPathIndex(-1);

            return false;
        }

        pool.setTimer(pathIndex, 0);
        pool.setCreationFrame(pathIndex, game.getCurrentFrame());
        pool.setFlags(pathIndex, 0);
        pool.setWaypointCount(pathIndex, this.allocationCounts[PathPool.ROUTE_WAYPOINT_COUNT_INDEX]);
        pool.setWaypointIndex(pathIndex, 0);
        pool.setRegionCount(pathIndex, this.allocationCounts[PathPool.ROUTE_REGION_COUNT_INDEX]);
        pool.setFullRegionCount(pathIndex, this.request.fullRegionCount);

        byte regionIndex = (byte) this.request.regionIndex;
        byte regionCount = (byte) this.allocationCounts[PathPool.ROUTE_REGION_COUNT_INDEX];

        pool.setRegionIndex(pathIndex, regionIndex > regionCount ? regionCount : regionIndex);

        int waypointCount = (byte) this.allocationCounts[PathPool.ROUTE_WAYPOINT_COUNT_INDEX];

        pool.copyWaypointsFrom(this.request, pathIndex, waypointCount);
        pool.copyRegionsFrom(this.request, pathIndex, waypointCount, regionCount);
        unit.setPathIndex(pathIndex);
        pool.setSourceX(pathIndex, unit.requireSprite().getX());
        pool.setSourceY(pathIndex, unit.requireSprite().getY());
        pool.setDestination(pathIndex, destination);
        this.projectWaypoint(game, unit, pool);

        return true;
    }

    /**
     * Checks whether the unit keeps moving to its current waypoint instead of advancing to the next one.
     * <p>
     * A unit on its waypoint does not keep it. A unit keeps it when the waypoint is its movement destination, when its movement control is not
     * {@link ScrFlingyTypeView#MOVEMENT_CONTROL_FLINGY}, when the waypoint is beyond the arrival distance ({@code 16} pixels) or when the turn to its
     * heading is large for the distance; otherwise it keeps only its last waypoint, while it is outside the current route region.
     *
     * @param game the game with the terrain regions
     * @param unit the moving unit
     * @param pool the path pool
     * @return {@code true} if the unit keeps moving to its current waypoint, {@code false} otherwise
     * @throws IllegalStateException         if the unit has no sprite
     * @throws UnsupportedOperationException if the terrain contours are not initialized
     */
    public boolean isKeepingCurrentWaypoint(@NotNull Game game, @NotNull Unit unit, @NotNull PathPool pool) {
        int pathIndex = unit.getPathIndex();

        if (pathIndex < 0) {
            return false;
        }

        int horizontal = (short) unit.requireSprite().getX();
        int vertical = (short) unit.requireSprite().getY();
        int waypointX = (short) pool.getWaypointPosition(pathIndex);
        int waypointY = (short) (pool.getWaypointPosition(pathIndex) >>> WORD_BITS);

        if (horizontal == waypointX && vertical == waypointY) {
            return false;
        }

        if (pool.getWaypointPosition(pathIndex) == getPackedMovementDestination(unit) ||
            unit.getMovementControl() != ScrFlingyTypeView.MOVEMENT_CONTROL_FLINGY) {
            return true;
        }

        int distance = RegionPathSearch.calculateDistance(horizontal, waypointX, vertical, waypointY);

        if (distance > WAYPOINT_ARRIVAL_DISTANCE) {
            return true;
        }

        int directionDifference = (unit.getDesiredMovementDirection() - unit.getTargetHeading()) & BYTE_MASK;

        if (distance * TURN_DISTANCE_FACTOR >= Math.min(directionDifference, FlingyMotionUtils.DIRECTION_FULL_TURN - directionDifference)) {
            return true;
        }

        int waypointCount = pool.getWaypointCount(pathIndex);

        if (pool.getWaypointIndex(pathIndex) < waypointCount - 1) {
            return false;
        }

        int region = TerrainRegionLookupUtils.lookup(game.getPathSystem().requireTerrainContours().getRegions(), horizontal, vertical);

        // A stored region index equal to the truncated region count reads the first WORD of the next record.
        return (short) region != (short) pool.getRouteRegion(pathIndex, waypointCount, pool.getRegionIndex(pathIndex));
    }

    /**
     * Moves the waypoint position of the unit's path to its current waypoint and makes that waypoint the unit's direction target and movement
     * waypoint; the unit stops moving at its movement destination and brakes on the path step near the path destination.
     *
     * @param game the game with the terrain regions
     * @param unit the unit owning the path
     * @param pool the path pool
     */
    private void projectWaypoint(@NotNull Game game, @NotNull Unit unit, @NotNull PathPool pool) {
        int pathIndex = unit.getPathIndex();
        int destination = pool.getDestination(pathIndex);
        int waypoint = pool.getWaypoint(pathIndex, pool.getWaypointIndex(pathIndex));

        pool.setWaypointPosition(pathIndex, waypoint);

        if (waypoint == getPackedMovementDestination(unit)) {
            unit.setMovementFlags(unit.getMovementFlags() & ~MovementFlags.MOVING);
        } else if (RegionPathSearch.calculateDistance((short) waypoint, (short) destination,
                                                      (short) (waypoint >>> WORD_BITS), (short) (destination >>> WORD_BITS)) < BRAKE_DISTANCE) {
            unit.setMovementFlags(unit.getMovementFlags() | MovementFlags.BRAKE_ON_PATH_STEP);
        }

        unit.setDirectionTargetX(waypoint);
        unit.setDirectionTargetY(waypoint >>> WORD_BITS);

        if ((unit.getMovementWaypointX() | unit.getMovementWaypointY() << WORD_BITS) != waypoint) {
            unit.setMovementFlags(unit.getMovementFlags() | MovementFlags.ORDERED);
            unit.setMovementWaypointX(waypoint);
            unit.setMovementWaypointY(waypoint >>> WORD_BITS);
        }

        // The keep-waypoint test only reads state; its result is unused.
        this.isKeepingCurrentWaypoint(game, unit, pool);
    }
}
