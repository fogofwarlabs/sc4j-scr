package org.fogofwarlabs.sc4j.scr.game.movement;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitCollisionRules;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Applies the unit and terrain collision checks of the prospective movement step.
 * <p>
 * The terrain checks read the terrain boundaries of {@link TerrainContours} in four directions (up, right, down, left) and keep a per-unit terrain
 * collision rectangle, four packed signed WORDs (left, top, right, bottom) in which the unit moves without a terrain test.
 */
public final class MovementCollision {
    /**
     * The half size in pixels of the terrain collision rectangle a unit scans around its position.
     */
    private static final int TERRAIN_SCAN_RANGE = 128;
    /**
     * The number of axes of a movement step: horizontal and vertical.
     */
    private static final int AXIS_COUNT = 2;
    /**
     * The bits of one packed WORD field.
     */
    private static final int WORD_BITS = 16;
    /**
     * The mask of one packed WORD field.
     */
    private static final long WORD_MASK = 0xFFFFL;

    /**
     * The terrain boundary direction up (decreasing Y).
     */
    private static final int BOUNDARY_UP = 0;
    /**
     * The terrain boundary direction right (increasing X).
     */
    private static final int BOUNDARY_RIGHT = 1;
    /**
     * The terrain boundary direction down (increasing Y).
     */
    private static final int BOUNDARY_DOWN = 2;
    /**
     * The terrain boundary direction left (decreasing X).
     */
    private static final int BOUNDARY_LEFT = 3;
    /**
     * The number of terrain boundary directions.
     */
    private static final int BOUNDARY_DIRECTION_COUNT = 4;
    /**
     * The mask of a boundary direction or rectangle side index.
     */
    private static final int SIDE_MASK = 3;
    /**
     * The bit of a boundary direction that is set for the horizontal directions (right and left).
     */
    private static final int HORIZONTAL_DIRECTION_BIT = 1;
    /**
     * The terrain start search compares coordinates times this, so that its direction bias of one orders a coordinate equal to a boundary before or
     * after that boundary.
     */
    private static final int BOUNDARY_COMPARE_SCALE = 2;
    /**
     * The terrain start search shifts the sum of its lower and upper bounds right by this to get the middle boundary (half the sum).
     */
    private static final int MIDPOINT_SHIFT = 1;

    /**
     * The packed rectangle side of the left edge.
     */
    private static final int RECTANGLE_LEFT = 0;
    /**
     * The packed rectangle side of the top edge.
     */
    private static final int RECTANGLE_TOP = 1;
    /**
     * The packed rectangle side of the right edge.
     */
    private static final int RECTANGLE_RIGHT = 2;
    /**
     * The packed rectangle side of the bottom edge.
     */
    private static final int RECTANGLE_BOTTOM = 3;

    /**
     * The boundary component of its coordinate.
     */
    private static final int COMPONENT_COORDINATE = 0;
    /**
     * The boundary component of its first cross coordinate.
     */
    private static final int COMPONENT_FIRST = 1;
    /**
     * The boundary component of its last cross coordinate.
     */
    private static final int COMPONENT_LAST = 2;
    /**
     * The boundary flag of a first end that a segment may pass.
     */
    private static final int BOUNDARY_FIRST_OPEN = 0x10;
    /**
     * The boundary flag of a last end that a segment may pass.
     */
    private static final int BOUNDARY_LAST_OPEN = 0x20;
    /**
     * The mask of a dimension index in the boundary flags.
     */
    private static final int DIMENSION_INDEX_MASK = 3;
    /**
     * The shift of the last cross coordinate's dimension index in the boundary flags.
     */
    private static final int LAST_DIMENSION_SHIFT = 2;
    /**
     * The shift of the flags BYTE in a terrain boundary.
     */
    private static final int BOUNDARY_FLAGS_SHIFT = 56;
    /**
     * The shift of the coordinate's dimension index BYTE in a terrain boundary.
     */
    private static final int COORDINATE_DIMENSION_SHIFT = 48;
    /**
     * The mask of the coordinate's dimension index BYTE.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * Shrinks a terrain collision rectangle to the terrain boundaries around a position, each boundary expanded by the unit dimensions, so that a
     * unit inside the rectangle crosses no boundary.
     *
     * @param terrain    the terrain contours whose collision footprint is initialized for the unit type
     * @param dimensions the unit dimensions as four packed WORDs (up, negated right, negated down, left)
     * @param positionX  the horizontal position the rectangle surrounds
     * @param positionY  the vertical position the rectangle surrounds
     * @param rectangle  the starting rectangle as four packed signed WORDs (left, top, right, bottom)
     * @return the shrunk rectangle in the same packing
     */
    public static long refreshTerrainRectangle(@NotNull TerrainContours terrain, long dimensions, int positionX, int positionY, long rectangle) {
        long cursors = 0;

        for (int direction = 0; direction < BOUNDARY_DIRECTION_COUNT; direction++) {
            boolean backward = direction == BOUNDARY_UP || direction == BOUNDARY_LEFT;
            int coordinate = (direction & HORIZONTAL_DIRECTION_BIT) == 0 ? positionY : positionX;
            int cursor = findTerrainStart(terrain, direction, coordinate, dimensions) - (backward ? 1 : 0);

            cursors |= (cursor & WORD_MASK) << (direction * WORD_BITS);
        }

        boolean visited;

        do {
            visited = false;

            for (int direction = 0; direction < BOUNDARY_DIRECTION_COUNT; direction++) {
                boolean backward = direction == BOUNDARY_UP || direction == BOUNDARY_LEFT;
                int cursor = (short) (cursors >>> (direction * WORD_BITS));

                if (cursor < 0 || cursor >= terrain.getBoundaryCount(direction)) {
                    continue;
                }

                visited = true;

                long boundary = terrain.getBoundary(direction, cursor);
                int coordinate = expandTerrain(boundary, dimensions, COMPONENT_COORDINATE);
                int first = expandTerrain(boundary, dimensions, COMPONENT_FIRST);
                int last = expandTerrain(boundary, dimensions, COMPONENT_LAST);
                int side = (direction + 1) & SIDE_MASK;
                int limit = (short) (rectangle >>> (side * WORD_BITS));

                if (backward ? coordinate < limit : coordinate > limit) {
                    cursor = backward ? -1 : terrain.getBoundaryCount(direction);
                } else {
                    cursor += backward ? -1 : 1;

                    int firstSide = (direction & HORIZONTAL_DIRECTION_BIT) == 0 ? RECTANGLE_LEFT : RECTANGLE_TOP;
                    int lastSide = firstSide + (RECTANGLE_RIGHT - RECTANGLE_LEFT);

                    if (last >= (short) (rectangle >>> (firstSide * WORD_BITS)) && first <= (short) (rectangle >>> (lastSide * WORD_BITS))) {
                        int current = (direction & HORIZONTAL_DIRECTION_BIT) == 0 ? positionY : positionX;
                        int cross = (direction & HORIZONTAL_DIRECTION_BIT) == 0 ? positionX : positionY;
                        int distance = backward ? current - coordinate : coordinate - current;
                        int newSide;
                        int value;

                        if (cross < first && distance < first - cross) {
                            newSide = lastSide;
                            value   = first - 1;
                        } else if (cross > last && distance < cross - last) {
                            newSide = firstSide;
                            value   = last + 1;
                        } else {
                            newSide = side;
                            value   = coordinate + (backward ? 1 : -1);
                        }

                        rectangle = (rectangle & ~(WORD_MASK << (newSide * WORD_BITS))) | (value & WORD_MASK) << (newSide * WORD_BITS);
                    }
                }

                cursors = (cursors & ~(WORD_MASK << (direction * WORD_BITS))) | (cursor & WORD_MASK) << (direction * WORD_BITS);
            }
        } while (visited);

        return rectangle;
    }

    /**
     * Checks the terrain boundaries between a current and a target position, the vertical move first.
     *
     * @param terrain    the terrain contours whose collision footprint is initialized for the unit type
     * @param dimensions the unit dimensions as four packed WORDs (up, negated right, negated down, left)
     * @param currentX   the current horizontal position
     * @param currentY   the current vertical position
     * @param positionX  the target horizontal position
     * @param positionY  the target vertical position
     * @return {@code true} if the unit moves from the current to the target position along both axes without crossing a terrain boundary, {@code false} otherwise
     */
    public static boolean isTerrainSegmentClear(@NotNull TerrainContours terrain,
                                                long dimensions,
                                                int currentX, int currentY,
                                                int positionX, int positionY) {
        currentX  = (short) currentX;
        currentY  = (short) currentY;
        positionX = (short) positionX;
        positionY = (short) positionY;

        for (int axis = 0; axis < AXIS_COUNT; axis++) {
            int current = axis == 0 ? currentY : currentX;
            int destination = axis == 0 ? positionY : positionX;

            if (current == destination) {
                continue;
            }

            boolean backward = destination < current;
            int direction = axis == 0 ? (backward ? BOUNDARY_UP : BOUNDARY_DOWN) : (backward ? BOUNDARY_LEFT : BOUNDARY_RIGHT);
            int first = Math.min(axis == 0 ? currentX : currentY, axis == 0 ? positionX : positionY);
            int last = Math.max(axis == 0 ? currentX : currentY, axis == 0 ? positionX : positionY);
            int cursor = findTerrainStart(terrain, direction, current, dimensions) - (backward ? 1 : 0);

            for (; cursor >= 0 && cursor < terrain.getBoundaryCount(direction); cursor += backward ? -1 : 1) {
                long boundary = terrain.getBoundary(direction, cursor);
                int coordinate = expandTerrain(boundary, dimensions, COMPONENT_COORDINATE);

                if (backward ? coordinate < destination : coordinate > destination) {
                    break;
                }

                int boundaryFirst = expandTerrain(boundary, dimensions, COMPONENT_FIRST);
                int boundaryLast = expandTerrain(boundary, dimensions, COMPONENT_LAST);

                if (boundaryFirst > last || boundaryLast < first) {
                    continue;
                }

                int flags = (int) (boundary >>> BOUNDARY_FLAGS_SHIFT);

                if ((axis == 0 ? boundaryFirst < first && boundaryLast > last : boundaryFirst < last && boundaryLast > first) ||
                    (flags & BOUNDARY_FIRST_OPEN) == 0 || boundaryFirst <= first || (flags & BOUNDARY_LAST_OPEN) == 0 || boundaryLast >= last) {
                    return false;
                }
            }
        }

        return true;
    }

    private static int findTerrainStart(@NotNull TerrainContours terrain, int direction, int coordinate, long dimensions) {
        int threshold = (short) ((coordinate - (short) (dimensions >>> (direction * WORD_BITS))) * BOUNDARY_COMPARE_SCALE +
                                 (direction == BOUNDARY_UP || direction == BOUNDARY_LEFT ? 1 : -1));
        int lower = 0;
        int upper = terrain.getBoundaryCount(direction);

        while (lower < upper) {
            int middle = (lower + upper) >>> MIDPOINT_SHIFT;

            if (threshold >= (short) ((short) terrain.getBoundary(direction, middle) * BOUNDARY_COMPARE_SCALE)) {
                lower = middle + 1;
            } else {
                upper = middle;
            }
        }

        return lower;
    }

    private static int expandTerrain(long boundary, long dimensions, int component) {
        int flags = (int) (boundary >>> BOUNDARY_FLAGS_SHIFT);
        int dimension = component == COMPONENT_COORDINATE
                        ? (int) (boundary >>> COORDINATE_DIMENSION_SHIFT) & BYTE_MASK
                        : component == COMPONENT_FIRST ? flags & DIMENSION_INDEX_MASK : (flags >>> LAST_DIMENSION_SHIFT) & DIMENSION_INDEX_MASK;

        return (short) ((short) (boundary >>> (component * WORD_BITS)) + (short) (dimensions >>> (dimension * WORD_BITS)));
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link MovementCollision} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public MovementCollision(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Returns the ground unit the step to a position collides with: only a mover with the ground-unit status collides. Used by AtRest, LumpWannabe
     * and the first FollowPath check.
     *
     * @param unit      the moving unit
     * @param positionX the prospective horizontal position
     * @param positionY the prospective vertical position
     * @return the colliding unit, or {@code null} without a collision
     */
    @Nullable Unit findUnitCollision(@NotNull Unit unit, int positionX, int positionY) {
        if ((unit.getStatus() & UnitStatusFlags.GROUND_UNIT) == 0) {
            return null;
        }

        return this.findAnyUnitCollision(unit, positionX, positionY);
    }

    /**
     * Returns the ground unit the step to a position collides with. The mover's ground-unit status is not tested, so a unit that cleared it (an
     * Archon warp) still collides with ground units while sliding, waiting, getting free or following its path. The search is the engine step
     * search ({@link org.fogofwarlabs.sc4j.engine.collision.UnitCollisionSearch#findStepBlocker}) with the ground movement blocking rule
     * ({@link UnitCollisionRules#getGroundMovementTest}); a hidden mover steps nowhere.
     *
     * @param unit      the moving unit
     * @param positionX the prospective horizontal position
     * @param positionY the prospective vertical position
     * @return the colliding unit, or {@code null} without a collision
     * @throws NullPointerException          if the unit spatial index is missing
     * @throws IllegalStateException         if the unit has no sprite
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    @Nullable Unit findAnyUnitCollision(@NotNull Unit unit, int positionX, int positionY) {
        int horizontalDelta = (short) (positionX - (short) unit.requireSprite().getX());
        int verticalDelta = (short) (positionY - (short) unit.requireSprite().getY());

        if ((unit.requireSprite().getFlags() & Sprite.FLAG_HIDDEN) != 0) {
            horizontalDelta = 0;
            verticalDelta   = 0;
        }

        UnitSpatialIndex spatialIndex = this.game.getUnitSystem().getUnitSpatialIndex();

        if (spatialIndex == null) {
            throw Exceptions.newNullPointerException();
        }

        UnitCollisionRules rules = this.game.getHooks().getCollisionRules();

        return spatialIndex.getCollisionSearch().findStepBlocker(unit, horizontalDelta, verticalDelta, rules.getGroundMovementTest());
    }

    /**
     * Checks whether the step to a position hits the terrain: an air unit leaves the map, a ground unit outside its terrain collision rectangle
     * crosses a terrain boundary on the way. A ground unit refreshes its rectangle around its current position first.
     *
     * @param unit      the moving unit
     * @param positionX the prospective horizontal position
     * @param positionY the prospective vertical position
     * @return {@code true} if the step hits the terrain, {@code false} otherwise
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the unit has no sprite
     * @throws UnsupportedOperationException if the terrain contours are not initialized
     */
    boolean isBlockedByTerrain(@NotNull Unit unit, int positionX, int positionY) {
        positionX = (short) positionX;
        positionY = (short) positionY;

        if ((unit.getStatus() & UnitStatusFlags.GROUND_UNIT) == 0) {
            ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
            int unitType = unit.getUnitId();

            return positionX < (short) unitTypes.getDimensionLeft(unitType) ||
                   positionY < (short) unitTypes.getDimensionUp(unitType) ||
                   positionX + (short) unitTypes.getDimensionRight(unitType) >= this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE ||
                   positionY + (short) unitTypes.getDimensionDown(unitType) >= this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE;
        }

        if (positionX >= (short) unit.getTerrainCollisionLeft() && positionX <= (short) unit.getTerrainCollisionRight() &&
            positionY >= (short) unit.getTerrainCollisionTop() && positionY <= (short) unit.getTerrainCollisionBottom()) {
            return false;
        }

        int currentX = (short) unit.requireSprite().getX();
        int currentY = (short) unit.requireSprite().getY();

        unit.setTerrainCollisionTop(currentY - TERRAIN_SCAN_RANGE);
        unit.setTerrainCollisionLeft(currentX - TERRAIN_SCAN_RANGE);
        unit.setTerrainCollisionBottom(currentY + TERRAIN_SCAN_RANGE);
        unit.setTerrainCollisionRight(currentX + TERRAIN_SCAN_RANGE);

        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();
        long dimensions = (unitTypes.getDimensionUp(unitType) & WORD_MASK) |
                          (-unitTypes.getDimensionRight(unitType) & WORD_MASK) << (BOUNDARY_RIGHT * WORD_BITS) |
                          (-unitTypes.getDimensionDown(unitType) & WORD_MASK) << (BOUNDARY_DOWN * WORD_BITS) |
                          (unitTypes.getDimensionLeft(unitType) & WORD_MASK) << (BOUNDARY_LEFT * WORD_BITS);
        TerrainContours terrain = this.game.getPathSystem().requireTerrainContours();

        terrain.initializeCollisionFootprint(unitType, unitTypes);

        long rectangle = unit.getTerrainCollisionLeft() | (long) unit.getTerrainCollisionTop() << (RECTANGLE_TOP * WORD_BITS) |
                         (long) unit.getTerrainCollisionRight() << (RECTANGLE_RIGHT * WORD_BITS) |
                         (long) unit.getTerrainCollisionBottom() << (RECTANGLE_BOTTOM * WORD_BITS);

        rectangle = refreshTerrainRectangle(terrain, dimensions, currentX, currentY, rectangle);

        unit.setTerrainCollisionLeft((int) rectangle);
        unit.setTerrainCollisionTop((int) (rectangle >>> (RECTANGLE_TOP * WORD_BITS)));
        unit.setTerrainCollisionRight((int) (rectangle >>> (RECTANGLE_RIGHT * WORD_BITS)));
        unit.setTerrainCollisionBottom((int) (rectangle >>> (RECTANGLE_BOTTOM * WORD_BITS)));

        if (positionX >= (short) rectangle && positionX <= (short) (rectangle >>> (RECTANGLE_RIGHT * WORD_BITS)) &&
            positionY >= (short) (rectangle >>> (RECTANGLE_TOP * WORD_BITS)) && positionY <= (short) (rectangle >>> (RECTANGLE_BOTTOM * WORD_BITS))) {
            return false;
        }

        return !isTerrainSegmentClear(terrain, dimensions, currentX, currentY, positionX, positionY);
    }
}
