package org.fogofwarlabs.sc4j.scr.game.unit.finder;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.spatial.SortedEdgeIndex;
import org.fogofwarlabs.sc4j.engine.spatial.SpatialIndex;
import org.fogofwarlabs.sc4j.engine.spatial.UnitFilter;
import org.fogofwarlabs.sc4j.engine.spatial.UnitList;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Serves the SC:R unit finder ({@link UnitSpatialIndex}, a view over the engine {@link SortedEdgeIndex} of the unit pool) as the engine
 * {@link SpatialIndex}. The SC:R finder is created with the unit pool of a map, after the systems attach, so this view resolves it at every call
 * instead of registering the engine {@link org.fogofwarlabs.sc4j.engine.spatial.StandardSpatialIndex}. The view holds no simulation state and is
 * no state hash contributor: the SC:R game mixes the finder.
 * <p>
 * A rectangle query returns the expanded candidates of {@link UnitSpatialIndex#findAllInRectangle} in its query order
 * ({@link SortedEdgeIndex#findInRectangle}); a nearest query is the finder's radius search around a point, whose candidate test runs before the
 * distance comparison ({@link SortedEdgeIndex#findNearestAccepted}). The finder moves a unit by the displacement of its
 * sprite ({@link UnitSpatialIndex#relocate}). The queries allocate nothing.
 */
public final class ScrSpatialIndex implements SpatialIndex {
    private @Nullable Game game;

    /**
     * Creates a {@link ScrSpatialIndex} instance; it serves the SC:R game it finds when it is attached.
     */
    public ScrSpatialIndex() {
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
     * Adds a unit with its current bounds.
     *
     * @param unit the unit
     * @throws IllegalArgumentException      if the unit is not an SC:R unit
     * @throws IllegalStateException         if the system is not attached or the finder does not exist yet
     * @throws UnsupportedOperationException if the finder cannot index the unit ({@link UnitSpatialIndex#insert})
     */
    @Override
    public void insert(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit) {
        Game attached = this.requireGame();

        attached.getUnitSystem().requireUnitSpatialIndex().insert(Unit.of(unit), attached.requireGameData().getUnitTypes());
    }

    /**
     * Removes a unit.
     *
     * @param unit the unit
     * @throws IllegalArgumentException if the unit is not an SC:R unit of the game's pool
     * @throws IllegalStateException    if the system is not attached, the finder does not exist yet or the unit's edges are inconsistent
     */
    @Override
    public void remove(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit) {
        this.requireGame().getUnitSystem().requireUnitSpatialIndex().remove(Unit.of(unit));
    }

    /**
     * Moves a unit in the index by the displacement of its position; the unit has already moved. Its size is unchanged.
     * <p>
     * The SC:R move is {@link UnitSpatialIndex#relocate}: the unit's edges move by the signed DWORD displacement and are swapped past their
     * neighbors until the edge arrays are sorted again.
     *
     * @param unit             the unit
     * @param horizontalChange the horizontal displacement
     * @param verticalChange   the vertical displacement
     * @throws IllegalArgumentException if the unit is not an SC:R unit
     * @throws IllegalStateException    if the system is not attached or the finder does not exist yet
     */
    @Override
    public void relocate(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit, int horizontalChange, int verticalChange) {
        this.requireGame().getUnitSystem().requireUnitSpatialIndex().relocate(Unit.of(unit), horizontalChange, verticalChange);
    }

    /**
     * Finds the units whose bounds intersect a rectangle. The result is cleared first and collects the accepted units in
     * index order up to its capacity.
     * <p>
     * The candidates are those of {@link UnitSpatialIndex#findAllInRectangle} (the rectangle as signed WORDs, expanded by the largest unit
     * extents), in its query order.
     *
     * @param left   the left edge
     * @param top    the top edge
     * @param right  the right edge
     * @param bottom the bottom edge
     * @param filter selects the returned units
     * @param result receives the units
     * @throws IllegalStateException         if the system is not attached or the finder does not exist yet
     * @throws UnsupportedOperationException if the query bounds of the finder are not initialized
     */
    @Override
    public void findInRectangle(int left, int top, int right, int bottom, @NotNull UnitFilter filter, @NotNull UnitList result) {
        Game attached = this.requireGame();

        attached.getUnitSystem().requireUnitSpatialIndex().findInRectangle(attached.requireGameData().getUnitTypes(),
                                                                           left, top, right, bottom,
                                                                           filter, result);
    }

    /**
     * Finds the unit nearest to a point, measured between the point and the unit bounds.
     * <p>
     * The search is {@link UnitSpatialIndex#findNearest(int, int, int, java.util.function.Predicate)}: the point and the distance as WORDs, the
     * filter as its candidate test.
     *
     * @param x               the x coordinate of the point
     * @param y               the y coordinate of the point
     * @param maximumDistance the largest distance searched
     * @param filter          selects the candidate units
     * @return the nearest accepted unit, the first in index order among equally near ones, or {@code null} if none is within the distance
     * @throws IllegalStateException if the system is not attached or the finder does not exist yet
     */
    @Override
    public org.fogofwarlabs.sc4j.engine.entity.@Nullable Unit findNearest(int x, int y, int maximumDistance, @NotNull UnitFilter filter) {
        return this.requireGame().getUnitSystem().requireUnitSpatialIndex().getEdges().findNearestAccepted(x, y, maximumDistance, filter);
    }

    private @NotNull Game requireGame() {
        Game attached = this.game;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R spatial index is not attached.");
        }

        return attached;
    }
}
