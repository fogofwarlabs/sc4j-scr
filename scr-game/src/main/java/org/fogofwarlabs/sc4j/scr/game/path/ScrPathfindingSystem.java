package org.fogofwarlabs.sc4j.scr.game.path;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.math.MutablePoint;
import org.fogofwarlabs.sc4j.engine.path.PathfindingSystem;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Serves the SC:R path finding ({@link PathSystem}, the path records of the {@link PathPool}) as the engine {@link PathfindingSystem}: the path
 * update ({@link UnitPath#update} with the considered collision unit), ground reachability ({@link UnitPositioning#isGroundReachable}), the
 * current movement waypoint of a unit with a path record, and the release of that record. The view holds no state and is no state hash
 * contributor.
 * <p>
 * The SC:R movement states call {@link UnitPath#update} directly where they pass their own collision inputs.
 */
public final class ScrPathfindingSystem implements PathfindingSystem {
    private static final int WORD_MASK = 0xFFFF;

    private @Nullable Game game;

    /**
     * Creates a {@link ScrPathfindingSystem} instance; it serves the SC:R game it finds when it is attached.
     */
    public ScrPathfindingSystem() {
        this.game = null;
    }

    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Binds the SC:R game system of the game.
     *
     * @param game the game
     */
    @Override
    public void attach(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game) {
        this.game = game.getModSystem(Game.SYSTEM_KEY);
    }

    /**
     * Brings the path of a unit to a destination up to date: a unit that has not reached its current waypoint keeps it,
     * a unit at its waypoint advances to the next one, and a unit without a usable path gets a new one. The current
     * waypoint afterwards is read with {@link #getWaypoint}.
     * <p>
     * The SC:R update is {@link UnitPath#update} with the destination WORD pair, the considered collision unit and without other moving units.
     *
     * @param unit    the unit
     * @param targetX the x coordinate of the destination
     * @param targetY the y coordinate of the destination
     * @return {@code true} if the unit has a path afterwards, {@code false} if the destination cannot be reached
     * @throws IllegalArgumentException      if the unit is not an SC:R unit
     * @throws IllegalStateException         if the system is not attached or the unit has no sprite
     * @throws UnsupportedOperationException if the unit path workspace is not initialized
     * @throws NullPointerException          if the path pool or the local path workspace is not initialized, or the world was built without game data
     */
    @Override
    public boolean updatePath(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit, int targetX, int targetY) {
        Game attached = this.requireGame();

        return attached.getPathSystem().requireUnitPath().update(attached, Unit.of(unit), targetX & WORD_MASK | targetY << Short.SIZE);
    }

    /**
     * Checks whether a unit can reach a point over the terrain.
     * <p>
     * The SC:R test is {@link UnitPositioning#isGroundReachable}: a ground unit reaches a point of its terrain component.
     *
     * @param unit    the unit
     * @param targetX the x coordinate of the point
     * @param targetY the y coordinate of the point
     * @return {@code true} if the point is reachable for the unit, {@code false} otherwise
     * @throws IllegalArgumentException      if the unit is not an SC:R unit
     * @throws IllegalStateException         if the system is not attached
     * @throws UnsupportedOperationException if the terrain regions are unavailable
     */
    @Override
    public boolean isReachable(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit, int targetX, int targetY) {
        return this.requireGame().getUnitPositioning().isGroundReachable(Unit.of(unit), targetX, targetY);
    }

    /**
     * Reads the current waypoint of a unit's path.
     * <p>
     * The waypoint is the movement waypoint of an SC:R unit that holds a path record.
     *
     * @param unit     the unit
     * @param waypoint receives the waypoint
     * @return {@code true} if the unit has a path and the waypoint was written, {@code false} otherwise
     * @throws IllegalArgumentException if the unit is not an SC:R unit
     */
    @Override
    public boolean getWaypoint(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit, @NotNull MutablePoint waypoint) {
        Unit scrUnit = Unit.of(unit);

        if (scrUnit.getPathIndex() < 0) {
            return false;
        }

        waypoint.set(scrUnit.getMovementWaypointX(), scrUnit.getMovementWaypointY());

        return true;
    }

    /**
     * Releases the path of a unit, for example when it stops or dies.
     * <p>
     * A unit with a path record returns it to the path pool ({@link PathPool#release}) and holds none afterwards.
     *
     * @param unit the unit
     * @throws IllegalArgumentException      if the unit is not an SC:R unit
     * @throws IllegalStateException         if the system is not attached
     * @throws UnsupportedOperationException if the path pool is not initialized
     */
    @Override
    public void releasePath(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit) {
        Unit scrUnit = Unit.of(unit);

        if (scrUnit.getPathIndex() >= 0) {
            this.requireGame().getPathSystem().requirePathPool().release(scrUnit.getPathIndex());
            scrUnit.setPathIndex(-1);
        }
    }

    private @NotNull Game requireGame() {
        Game attached = this.game;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R path finding system is not attached.");
        }

        return attached;
    }
}
