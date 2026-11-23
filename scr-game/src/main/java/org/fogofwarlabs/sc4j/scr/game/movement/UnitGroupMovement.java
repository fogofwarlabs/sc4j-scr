package org.fogofwarlabs.sc4j.scr.game.movement;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegionLookupUtils;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegions;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitInitializer;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.jetbrains.annotations.NotNull;

/**
 * Computes the selected formation and terrain destinations for one point command.
 */
public final class UnitGroupMovement {
    /**
     * The largest spread in pixels of a selection with colliding units that keeps its formation.
     */
    private static final int COLLIDING_FORMATION_SPREAD = 192;
    /**
     * The largest spread in pixels of a selection without colliding units that keeps its formation.
     */
    private static final int FORMATION_SPREAD = 256;
    /**
     * The pixels a unit of a selection around the target stays within the target on each axis.
     */
    private static final int TARGET_INSIDE_SPREAD = 32;
    /**
     * The walk from the position toward the destination takes steps of a quarter of the distance at most.
     */
    private static final int STEP_DIVISOR = 4;
    /**
     * The largest step in pixels of the walk toward the destination.
     */
    private static final int MAXIMUM_STEP = 16;
    /**
     * A walk step below this takes the whole distance in one step.
     */
    private static final int MINIMUM_STEP = 2;
    /**
     * The smallest distance to the target that divides the walk toward the destination.
     */
    private static final int MINIMUM_DISTANCE = 1;
    /**
     * A selection keeps a formation only with at least this many units.
     */
    private static final int MINIMUM_FORMATION_COUNT = 2;
    /**
     * A selection of this many units moves its target off unwalkable terrain by the dimensions of that unit.
     */
    private static final int SINGLE_UNIT_COUNT = 1;
    /**
     * The formation square of a selection with colliding units has the area of this many times the total unit area.
     */
    private static final int FORMATION_AREA_FACTOR = 2;
    /**
     * The formation square extends its side divided by this to the left of and above the target.
     */
    private static final int FORMATION_CENTER_DIVISOR = 2;
    /**
     * The mask of the unsigned horizontal WORD of a packed position.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The shift of the vertical WORD of a packed position.
     */
    private static final int POSITION_VERTICAL_SHIFT = Short.SIZE;

    /**
     * Moves a destination off terrain that a ground unit of a type cannot stand on, as a player command for one unit does: a destination where the
     * terrain accepts the type's footprint ({@link UnitPositioning#isTerrainPlacementWalkable}) stays, any other destination moves as
     * {@link #adjustDestination} moves it with the type's extents. The destination is not clamped to the map.
     *
     * @param game        the initialized world
     * @param unitType    the unit type whose footprint stands at the destination
     * @param sourceX     the horizontal coordinate the unit comes from
     * @param sourceY     the vertical coordinate the unit comes from
     * @param destination the mutable horizontal and vertical pixel coordinates ({@link UnitPositioning#X_INDEX}, {@link UnitPositioning#Y_INDEX})
     * @throws UnsupportedOperationException if the terrain contours are not initialized
     * @throws NullPointerException          if the world was built without game data
     */
    public static void adjustUnitDestination(@NotNull Game game, int unitType, int sourceX, int sourceY, int @NotNull [] destination) {
        int destinationX = destination[UnitPositioning.X_INDEX];
        int destinationY = destination[UnitPositioning.Y_INDEX];

        if (game.getUnitPositioning().isTerrainPlacementWalkable(unitType, destinationX, destinationY)) {
            return;
        }

        ScrUnitTypeView unitTypes = game.requireGameData().getUnitTypes();

        adjustDestination(game,
                          sourceX, sourceY,
                          -unitTypes.getDimensionLeft(unitType), -unitTypes.getDimensionUp(unitType),
                          unitTypes.getDimensionRight(unitType), unitTypes.getDimensionDown(unitType),
                          destination);
    }

    /**
     * Moves a destination to terrain near it that a footprint of the given extents can stand on, on the side of the source position: when the
     * destination point is unwalkable or lies in another ground component than the source, the region that the region-level path from the source
     * ends in replaces the destination's region. The destination then moves into that region
     * ({@link TerrainRegionLookupUtils#adjustDestinationToRegion}) and to the first spot nearby whose footprint covers no blocked tile
     * ({@link TerrainRegionLookupUtils#adjustDestination}). The destination is not clamped to the map.
     *
     * @param game        the initialized world
     * @param sourceX     the horizontal coordinate the movement comes from
     * @param sourceY     the vertical coordinate the movement comes from
     * @param left        the signed left extent of the footprint
     * @param top         the signed top extent of the footprint
     * @param right       the signed right extent of the footprint
     * @param bottom      the signed bottom extent of the footprint
     * @param destination the mutable horizontal and vertical pixel coordinates ({@link UnitPositioning#X_INDEX}, {@link UnitPositioning#Y_INDEX})
     * @throws UnsupportedOperationException if the terrain contours are not initialized
     */
    public static void adjustDestination(@NotNull Game game,
                                         int sourceX, int sourceY,
                                         int left, int top, int right, int bottom,
                                         int @NotNull [] destination) {
        TerrainContours terrain = game.getPathSystem().getTerrainContours();

        if (terrain == null) {
            throw Exceptions.newUnsupportedOperationException("Group destination adjustment requires initialized terrain regions.");
        }

        TerrainRegions regions = terrain.getRegions();
        int targetX = destination[UnitPositioning.X_INDEX];
        int targetY = destination[UnitPositioning.Y_INDEX];
        int sourceRegion = TerrainRegionLookupUtils.lookupPreferWalkable(regions, sourceX, sourceY);
        int targetRegion = TerrainRegionLookupUtils.lookupPreferWalkable(regions, targetX, targetY);

        if (!game.getUnitPositioning().isTerrainPointWalkable(targetX, targetY) ||
            regions.getComponent(sourceRegion) != regions.getComponent(targetRegion)) {
            // Replace the target region with the end of the region-level long path.
            int longPathSource = TerrainRegionLookupUtils.lookup(regions, sourceX, sourceY);

            if (longPathSource == TerrainRegionLookupUtils.lookup(regions, targetX, targetY)) {
                targetRegion = longPathSource;
            } else {
                RegionPathSearch search = game.getPathSystem().getResourcePathSearch();

                if (search.search(sourceX, sourceY, targetX, targetY)) {
                    targetRegion = search.getRouteRegion(search.getRouteCount() - 1);
                }
            }
        }

        TerrainRegionLookupUtils.adjustDestinationToRegion(regions, game.getMap().getWidth(), game.getMap().getHeight(), targetRegion, destination);
        TerrainRegionLookupUtils.adjustDestination(regions,
                                                   game.getMap().getWidth() * TerrainTileset.TILE_SIZE,
                                                   game.getMap().getHeight() * TerrainTileset.TILE_SIZE,
                                                   left, top, right, bottom,
                                                   destination);
    }

    /**
     * Calculates the destination that a player command to a position gives one selected ground unit of a type, which the order then targets: the
     * target moves off terrain the unit cannot stand on ({@link #adjustUnitDestination}) and is clamped to the map, and the unit goes to the first
     * point on the way from the target to that destination that the terrain accepts for the unit ({@link #calculatePosition}).
     *
     * @param game     the initialized world
     * @param unitType the unit type
     * @param sourceX  the horizontal coordinate of the unit
     * @param sourceY  the vertical coordinate of the unit
     * @param targetX  the horizontal coordinate of the command
     * @param targetY  the vertical coordinate of the command
     * @return horizontal and vertical unsigned WORD coordinates packed into a DWORD
     * @throws UnsupportedOperationException if the terrain contours are not initialized
     * @throws NullPointerException          if the world was built without game data
     */
    public static int calculateUnitPosition(@NotNull Game game, int unitType, int sourceX, int sourceY, int targetX, int targetY) {
        int[] destination = {targetX, targetY};

        adjustUnitDestination(game, unitType, sourceX, sourceY, destination);

        int destinationX = Math.clamp(destination[UnitPositioning.X_INDEX], 0, game.getMap().getWidth() * TerrainTileset.TILE_SIZE - 1);
        int destinationY = Math.clamp(destination[UnitPositioning.Y_INDEX], 0, game.getMap().getHeight() * TerrainTileset.TILE_SIZE - 1);

        return calculateTerrainPosition(game, unitType, true, targetX, targetY, destinationX, destinationY);
    }

    /**
     * Calculates the terrain destination of one commanded unit: the unit's position for the command clamped to the map by the type's dimensions;
     * for a ground unit, the first point on the way from there to the group destination that the terrain accepts in the destination's region or a
     * walkable neighbor of it, else the group destination.
     *
     * @return horizontal and vertical unsigned WORD coordinates packed into a DWORD
     */
    private static int calculateTerrainPosition(@NotNull Game game,
                                                int unitType,
                                                boolean collisionEnabled,
                                                int positionX, int positionY,
                                                int destinationX, int destinationY) {
        ScrUnitTypeView unitTypes = game.requireGameData().getUnitTypes();

        positionX = UnitPositioning.clampCoordinate(positionX,
                                                    unitTypes.getDimensionLeft(unitType), unitTypes.getDimensionRight(unitType),
                                                    game.getMap().getWidth() * TerrainTileset.TILE_SIZE,
                                                    0);
        positionY = UnitPositioning.clampCoordinate(positionY,
                                                    unitTypes.getDimensionUp(unitType), unitTypes.getDimensionDown(unitType),
                                                    game.getMap().getHeight() * TerrainTileset.TILE_SIZE,
                                                    UnitPositioning.MAP_BOTTOM_MARGIN);

        if (!collisionEnabled) {
            return packPosition(positionX, positionY);
        }

        TerrainContours terrain = game.getPathSystem().getTerrainContours();

        if (terrain == null) {
            throw Exceptions.newUnsupportedOperationException("Group movement requires initialized terrain regions.");
        }

        TerrainRegions regions = terrain.getRegions();
        int sourceRegion = TerrainRegionLookupUtils.lookup(regions, destinationX, destinationY);

        if (isAcceptedPosition(game, unitType, regions, sourceRegion, positionX, positionY)) {
            return packPosition(positionX, positionY);
        }

        int distanceToTarget = Math.max(MINIMUM_DISTANCE, RegionPathSearch.calculateDistance(positionX, destinationX, positionY, destinationY));
        int step = Math.min(distanceToTarget / STEP_DIVISOR, MAXIMUM_STEP);

        if (step < MINIMUM_STEP) {
            step = distanceToTarget;
        }

        for (int distance = step; distance <= distanceToTarget; distance += step) {
            int horizontal = positionX + (destinationX - positionX) * distance / distanceToTarget;
            int vertical = positionY + (destinationY - positionY) * distance / distanceToTarget;

            if (isAcceptedPosition(game, unitType, regions, sourceRegion, horizontal, vertical)) {
                return packPosition(horizontal, vertical);
            }
        }

        return packPosition(destinationX, destinationY);
    }

    private static boolean isAcceptedPosition(@NotNull Game game,
                                              int unitType,
                                              @NotNull TerrainRegions regions, int sourceRegion,
                                              int positionX, int positionY) {
        if (!game.getUnitPositioning().isTerrainPlacementWalkable(unitType, positionX, positionY)) {
            return false;
        }

        int region = TerrainRegionLookupUtils.lookup(regions, positionX, positionY);

        if (region == sourceRegion) {
            return true;
        }

        for (int index = 0; index < regions.getWalkableNeighborCount(sourceRegion); index++) {
            if (regions.getNeighborRegion(sourceRegion, index) == region) {
                return true;
            }
        }

        return false;
    }

    private static int packPosition(int positionX, int positionY) {
        return positionX & WORD_MASK | positionY << POSITION_VERTICAL_SHIFT;
    }


    private final int originalTargetX;
    private final int originalTargetY;
    private int targetX;
    private int targetY;

    private int offsetX;
    private int offsetY;
    private boolean hasOffset;
    private boolean targetInsideSelection;

    /**
     * Creates a {@link UnitGroupMovement} instance for the retained selection.
     *
     * @param game            the initialized world
     * @param owner           the command player
     * @param targetX         the requested horizontal coordinate
     * @param targetY         the requested vertical coordinate
     * @param canBeObstructed whether the command adjusts destinations for terrain
     * @throws UnsupportedOperationException if the selection storage, a selected unit sprite or the terrain contours are absent
     * @throws NullPointerException          if the world was built without game data
     */
    public UnitGroupMovement(@NotNull Game game, int owner, int targetX, int targetY, boolean canBeObstructed) {
        this.originalTargetX = targetX;
        this.originalTargetY = targetY;
        this.targetX         = targetX;
        this.targetY         = targetY;

        this.offsetX               = 0;
        this.offsetY               = 0;
        this.hasOffset             = false;
        this.targetInsideSelection = false;

        UnitSelection selection = game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Group movement requires retained selection storage.");
        }

        ScrUnitTypeView unitTypes = game.requireGameData().getUnitTypes();
        int minimumX = Integer.MAX_VALUE;
        int minimumY = Integer.MAX_VALUE;
        int maximumX = 0;
        int maximumY = 0;
        int totalX = 0;
        int totalY = 0;
        int totalArea = 0;
        int count = 0;
        boolean collisionEnabled = false;
        Unit firstUnit = null;

        for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
            Unit unit = selection.getSelectedUnit(owner, position);

            if (unit == null || unit.isDestroyed()) {
                continue;
            }

            Sprite sprite = unit.getSpriteOrNull();

            if (sprite == null) {
                throw Exceptions.newUnsupportedOperationException("Group movement requires the selected unit sprite.");
            }

            if (firstUnit == null) {
                firstUnit = unit;
            }

            count++;
            collisionEnabled |= (unit.getPathCollisionFlags() & UnitInitializer.GROUND_ELEVATION_PATH_FLAG) != 0;

            int horizontal = sprite.getX();
            int vertical = sprite.getY();

            minimumX = Math.min(minimumX, horizontal);
            minimumY = Math.min(minimumY, vertical);
            maximumX = Math.max(maximumX, horizontal);
            maximumY = Math.max(maximumY, vertical);
            totalX += horizontal;
            totalY += vertical;

            int unitType = unit.getUnitId();

            totalArea += (unitTypes.getDimensionLeft(unitType) + unitTypes.getDimensionRight(unitType) + 1) *
                         (unitTypes.getDimensionUp(unitType) + unitTypes.getDimensionDown(unitType) + 1);
        }

        if (count == 0) {
            return;
        }

        if (collisionEnabled && canBeObstructed) {
            int[] destination = {targetX, targetY};

            if (count == SINGLE_UNIT_COUNT) {
                adjustUnitDestination(game, firstUnit.getUnitId(), totalX, totalY, destination);
            } else {
                int width = (int) Math.sqrt(Integer.toUnsignedLong(totalArea * FORMATION_AREA_FACTOR));
                TerrainContours terrain = game.getPathSystem().getTerrainContours();

                if (terrain == null) {
                    throw Exceptions.newUnsupportedOperationException("Group movement requires initialized terrain contours.");
                }

                int left = targetX - width / FORMATION_CENTER_DIVISOR;
                int top = targetY - width / FORMATION_CENTER_DIVISOR;

                if (left < 0 || top < 0 ||
                    left + width >= game.getMap().getWidth() * TerrainTileset.TILE_SIZE ||
                    top + width >= game.getMap().getHeight() * TerrainTileset.TILE_SIZE ||
                    !game.getUnitPositioning().isTerrainPointWalkable(targetX, targetY) ||
                    !terrain.isRectanglePlacementClear(targetX, targetY, width, width)) {
                    adjustDestination(game,
                                      totalX / count, totalY / count,
                                      -width / FORMATION_CENTER_DIVISOR, -width / FORMATION_CENTER_DIVISOR,
                                      -width / FORMATION_CENTER_DIVISOR + width, -width / FORMATION_CENTER_DIVISOR + width,
                                      destination);
                }
            }

            this.targetX = Math.clamp(destination[UnitPositioning.X_INDEX], 0, game.getMap().getWidth() * TerrainTileset.TILE_SIZE - 1);
            this.targetY = Math.clamp(destination[UnitPositioning.Y_INDEX], 0, game.getMap().getHeight() * TerrainTileset.TILE_SIZE - 1);
        }

        if (count >= MINIMUM_FORMATION_COUNT) {
            if (targetX >= minimumX && targetX <= maximumX && targetY >= minimumY && targetY <= maximumY) {
                this.targetInsideSelection = true;
            } else if (maximumX - minimumX <= (collisionEnabled ? COLLIDING_FORMATION_SPREAD : FORMATION_SPREAD) &&
                       maximumY - minimumY <= (collisionEnabled ? COLLIDING_FORMATION_SPREAD : FORMATION_SPREAD)) {
                this.offsetX   = targetX - totalX / count;
                this.offsetY   = targetY - totalY / count;
                this.hasOffset = true;
            }
        }
    }

    /**
     * Calculates the group destination of a unit: applies the formation offset, map bounds, and neighboring-region acceptance.
     *
     * @param game      the initialized world
     * @param unit      the commanded unit
     * @param positionX the command's initial horizontal destination
     * @param positionY the command's initial vertical destination
     * @return horizontal and vertical unsigned WORD coordinates packed into a DWORD
     * @throws UnsupportedOperationException if the unit has no sprite or the terrain regions are absent
     * @throws NullPointerException          if the world was built without game data
     */
    public int calculatePosition(@NotNull Game game, @NotNull Unit unit, int positionX, int positionY) {
        boolean collisionEnabled = (unit.getPathCollisionFlags() & UnitInitializer.GROUND_ELEVATION_PATH_FLAG) != 0;
        int destinationX = collisionEnabled ? this.targetX : this.originalTargetX;
        int destinationY = collisionEnabled ? this.targetY : this.originalTargetY;
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Group movement requires the commanded unit sprite.");
        }

        if (this.targetInsideSelection) {
            positionX = Math.clamp(sprite.getX(), destinationX - TARGET_INSIDE_SPREAD, destinationX + TARGET_INSIDE_SPREAD);
            positionY = Math.clamp(sprite.getY(), destinationY - TARGET_INSIDE_SPREAD, destinationY + TARGET_INSIDE_SPREAD);
        } else if (this.hasOffset) {
            positionX = sprite.getX() + this.offsetX;
            positionY = sprite.getY() + this.offsetY;
        }

        return calculateTerrainPosition(game, unit.getUnitId(), collisionEnabled, positionX, positionY, destinationX, destinationY);
    }
}
