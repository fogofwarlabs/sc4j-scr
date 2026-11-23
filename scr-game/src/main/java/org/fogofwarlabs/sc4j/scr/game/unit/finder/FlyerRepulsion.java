package org.fogofwarlabs.sc4j.scr.game.unit.finder;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.movement.MotionStep;
import org.fogofwarlabs.sc4j.engine.random.DeterministicRandom;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Keeps crowded flyers apart: the flyer repulsion. A square grid of cells counts the repulsion flyers in each cell (up to {@code 255}); a flyer
 * in a cell with another flyer turns its repulsion direction now and then, toward its destination and away from the map edges, and its
 * prospective movement step is displaced along that direction. The rules decide which units take part and supply their shown positions and
 * edges ({@link FlyerRepulsionRules}).
 * <p>
 * The per-unit state is the repulsion state of the {@link Unit}: the flags (a three-bit countdown to the next turn, bit {@code 3} and a
 * four-bit adjustment index), the eight-bit direction and the cell. A flyer without an adjustment index draws one random value from the game's
 * random source when it registers: its low byte is the direction, its second byte's high nibble the adjustment index ({@code 15} when it is
 * {@code 0}). Shown positions are read as signed sixteen-bit values for the cells; displacements are fixed-point with eight fraction bits.
 * <p>
 * The grid storage exists once it is allocated ({@link #allocate()}), when the map starts; before, every operation that reaches the grid fails.
 * <p>
 * Determinism contract: the operations run at their caller's point, draw random values only at a registration without adjustment index and
 * allocate nothing. The cell counts are the storage the unit finder reads and writes ({@link #getCellCounts()}).
 */
final class FlyerRepulsion {
    /**
     * The largest count of a cell.
     */
    private static final int CELL_COUNT_LIMIT = 255;
    /**
     * The mask of an unsigned eight-bit value: a cell coordinate or a direction.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The fraction bits of the fixed-point positions.
     */
    private static final int FIXED_POINT_SHIFT = 8;
    /**
     * The countdown bits of the repulsion flags: bits {@code 0-2} count down to the next direction change.
     */
    private static final int COUNTDOWN_MASK = 7;
    /**
     * The countdown bits and bit {@code 3} of the repulsion flags, without the adjustment index.
     */
    private static final int STATE_MASK = 0x0F;
    /**
     * The adjustment index bits and bit {@code 3} of the repulsion flags, without the countdown.
     */
    private static final int REGISTRATION_MASK = 0xF8;
    /**
     * The adjustment index bits of the repulsion flags.
     */
    private static final int ADJUSTMENT_MASK = 0xF0;
    /**
     * The shift of the adjustment index in the repulsion flags.
     */
    private static final int ADJUSTMENT_SHIFT = 4;
    /**
     * The shift of the registration random value whose high bits give a new adjustment index (its second byte).
     */
    private static final int RANDOM_ADJUSTMENT_SHIFT = Byte.SIZE;
    /**
     * The distance in pixels from the map edge within which repulsion turns a flyer away from the edge.
     */
    private static final int EDGE_MARGIN = 32;
    /**
     * The length of the repulsion direction vector before it is halved.
     */
    private static final int VECTOR_LENGTH = 256;
    /**
     * The divisor that halves the repulsion direction vector.
     */
    private static final int VECTOR_DIVISOR = 2;
    /**
     * The divisor of the cell count in the displacement of a flyer at its target.
     */
    private static final int STRENGTH_DIVISOR = 4;
    /**
     * The divisor of the displacement of a flyer at its target.
     */
    private static final int TARGET_DIVISOR = 2;
    /**
     * The factor of the adjustment beyond which a displacement component is reduced by it instead of enlarged.
     */
    private static final int ADJUSTMENT_FACTOR = 4;
    /**
     * The distance in pixels within which a displaced flyer takes its displaced position as its destination.
     */
    private static final int DESTINATION_SNAP_DISTANCE = 24;
    /**
     * The adjustment pairs (horizontal, vertical) of the displacement, indexed by the adjustment index.
     */
    private static final int @NotNull [] ADJUSTMENTS = {
            -5, -5, -5, 5, 5, -5, 5, 5, 5, -5, 5, 5, -5, -5, -5, 5,
            -5, 5, 5, -5, -5, -5, 5, 5, -5, 5, 5, -5, 5, 5, -5, -5
    };
    /**
     * The number of entries of an adjustment pair in {@link #ADJUSTMENTS}.
     */
    private static final int ADJUSTMENT_PAIR_SIZE = 2;
    /**
     * The offset of the vertical entry in an adjustment pair.
     */
    private static final int VERTICAL_ADJUSTMENT_OFFSET = 1;

    private final @NotNull FlyerRepulsionRules rules;
    private final int gridDimension;
    private final int cellSize;
    private final int randomSource;

    private byte @Nullable [] cellCounts;

    /**
     * Creates a {@link FlyerRepulsion} instance whose grid is not allocated yet.
     *
     * @param rules         the facts about the units
     * @param gridDimension the number of cells of the grid on each axis
     * @param cellSize      the size of a cell in pixels
     * @param randomSource  the random source of the registration draw
     * @throws IllegalArgumentException if the grid dimension or the cell size is not positive
     */
    FlyerRepulsion(@NotNull FlyerRepulsionRules rules, int gridDimension, int cellSize, int randomSource) {
        if (gridDimension <= 0 || cellSize <= 0) {
            throw Exceptions.newIllegalArgumentException("A flyer repulsion grid requires positive sizes: {0} cells of {1} pixels",
                                                         gridDimension, cellSize);
        }

        this.rules         = rules;
        this.gridDimension = gridDimension;
        this.cellSize      = cellSize;
        this.randomSource  = randomSource;

        this.cellCounts = null;
    }

    /**
     * @return the live cell counts, row by row, one unsigned byte per cell, or {@code null} before the grid is allocated; a mod's adapter may read and write them
     */
    byte @Nullable [] getCellCounts() {
        return this.cellCounts;
    }


    /**
     * Allocates the grid with every cell empty.
     */
    void allocate() {
        this.cellCounts = new byte[this.gridDimension * this.gridDimension];
    }

    /**
     * Empties every cell of an allocated grid; the storage is kept.
     */
    void clear() {
        byte[] counts = this.cellCounts;

        if (counts != null) {
            Arrays.fill(counts, (byte) 0);
        }
    }

    /**
     * Registers a repulsion flyer in the cell it is shown in: a flyer without an adjustment index first draws its direction and adjustment
     * index, then its cell is stored and the cell count rises up to its limit. Other units are ignored.
     *
     * @param unit   the unit
     * @param random the game random generator
     * @throws IllegalStateException if the grid is not allocated or the unit's cell lies outside it
     */
    void register(@NotNull Unit unit, @NotNull DeterministicRandom random) {
        if (!this.rules.isRepulsionFlyer(unit)) {
            return;
        }

        int registrationFlags = unit.getRepulsionFlags();

        if ((registrationFlags & ADJUSTMENT_MASK) == 0) {
            int randomValue = random.next(this.randomSource);

            unit.setRepulsionDirection(randomValue);

            int randomFlags = (randomValue >>> RANDOM_ADJUSTMENT_SHIFT) & ADJUSTMENT_MASK;

            registrationFlags |= randomFlags != 0 ? randomFlags : ADJUSTMENT_MASK;
        }

        unit.setRepulsionFlags(registrationFlags & REGISTRATION_MASK);

        int horizontalCell = ((short) this.rules.getShownX(unit) / this.cellSize) & BYTE_MASK;

        unit.setRepulsionCellX(horizontalCell);

        int verticalCell = ((short) this.rules.getShownY(unit) / this.cellSize) & BYTE_MASK;

        unit.setRepulsionCellY(verticalCell);

        int cellIndex = verticalCell * this.gridDimension + horizontalCell;
        byte[] counts = this.cellCounts;

        if (counts == null || cellIndex >= counts.length) {
            throw Exceptions.newIllegalStateException("The flyer repulsion grid does not contain the cell of the unit: {0}", cellIndex);
        }

        int cellCount = Byte.toUnsignedInt(counts[cellIndex]);

        if (cellCount < CELL_COUNT_LIMIT) {
            counts[cellIndex] = (byte) (cellCount + 1);
        }
    }

    /**
     * Removes a repulsion flyer's count from its cell. Other units are ignored.
     *
     * @param unit the unit
     * @throws IllegalStateException if the grid is not allocated or the unit's cell lies outside it
     */
    void unregister(@NotNull Unit unit) {
        if (!this.rules.isRepulsionFlyer(unit)) {
            return;
        }

        int cellIndex = unit.getRepulsionCellY() * this.gridDimension + unit.getRepulsionCellX();
        byte[] counts = this.cellCounts;

        if (counts == null || cellIndex < 0 || cellIndex >= counts.length) {
            throw Exceptions.newIllegalStateException("The flyer repulsion grid does not contain the cell of the unit: {0}", cellIndex);
        }

        int cellCount = Byte.toUnsignedInt(counts[cellIndex]);

        if (cellCount > 0) {
            counts[cellIndex] = (byte) (cellCount - 1);
        }
    }

    /**
     * Displaces the prospective movement step of a crowded flyer. A flyer alone in its cell resets its countdown and is not displaced. Otherwise
     * the countdown runs; when it has run out, it restarts and the flyer turns: a flyer away from its target turns to an eighth turn beside the
     * direction to its destination, and a flyer near a map edge turns away from it. The step moves by half the direction vector of length
     * {@code 256} (scaled by the crowding for a flyer at its target), enlarged or reduced by the adjustment pair of the flyer's adjustment index.
     *
     * @param step      the prospective movement step, updated in place
     * @param unit      the unit
     * @param mapWidth  the map width in pixels
     * @param mapHeight the map height in pixels
     * @return {@code true} if crowding displaced the step, {@code false} otherwise
     * @throws IllegalStateException if the grid is not allocated or the unit's cell lies outside it
     */
    boolean apply(@NotNull MotionStep step, @NotNull Unit unit, int mapWidth, int mapHeight) {
        if (!this.rules.isRepulsionFlyer(unit) || this.rules.isRepulsionHeld(unit)) {
            return false;
        }

        int cellIndex = unit.getRepulsionCellY() * this.gridDimension + unit.getRepulsionCellX();
        byte[] counts = this.cellCounts;

        if (counts == null || cellIndex >= counts.length) {
            throw Exceptions.newIllegalStateException("The flyer repulsion grid does not contain the cell of the unit: {0}", cellIndex);
        }

        int strength = Byte.toUnsignedInt(counts[cellIndex]);

        if (strength <= 1) {
            unit.setRepulsionFlags(unit.getRepulsionFlags() & ~COUNTDOWN_MASK);

            return false;
        }

        boolean atTarget = this.rules.isAtMovementTarget(unit);

        if ((unit.getRepulsionFlags() & COUNTDOWN_MASK) != 0) {
            unit.setRepulsionFlags(unit.getRepulsionFlags() - 1);
        } else {
            unit.setRepulsionFlags(unit.getRepulsionFlags() | COUNTDOWN_MASK);
            unit.setRepulsionDirection(this.turn(unit, atTarget, mapWidth, mapHeight));
        }

        long vector = FlingyMotionUtils.calculateDirectionVector(unit.getRepulsionDirection(), VECTOR_LENGTH);
        int horizontal = (int) vector / VECTOR_DIVISOR;
        int vertical = (int) (vector >>> FlingyMotionUtils.DIRECTION_VECTOR_VERTICAL_SHIFT) / VECTOR_DIVISOR;

        if (atTarget) {
            horizontal = horizontal * (strength / STRENGTH_DIVISOR + 1) / TARGET_DIVISOR;
            vertical   = vertical * (strength / STRENGTH_DIVISOR + 1) / TARGET_DIVISOR;
        }

        int adjustmentIndex = unit.getRepulsionFlags() >>> ADJUSTMENT_SHIFT;

        horizontal = adjust(horizontal, ADJUSTMENTS[adjustmentIndex * ADJUSTMENT_PAIR_SIZE]);
        vertical   = adjust(vertical, ADJUSTMENTS[adjustmentIndex * ADJUSTMENT_PAIR_SIZE + VERTICAL_ADJUSTMENT_OFFSET]);

        step.setFixedPositionX(step.getFixedPositionX() + horizontal);
        step.setFixedPositionY(step.getFixedPositionY() + vertical);
        step.setPositionX(step.getFixedPositionX() >> FIXED_POINT_SHIFT);
        step.setPositionY(step.getFixedPositionY() >> FIXED_POINT_SHIFT);

        return true;
    }

    /**
     * Moves a repulsion flyer to the cell it is shown in when it left its cell, and makes a displaced flyer close to its destination take the
     * given position as its destination and waypoint.
     *
     * @param unit      the unit
     * @param random    the game random generator, for the registration in the new cell
     * @param displaced the result of the preceding {@link #apply}
     * @param positionX the next horizontal sixteen-bit coordinate
     * @param positionY the next vertical sixteen-bit coordinate
     * @throws IllegalStateException if the grid is not allocated or the unit's previous or new cell lies outside it
     */
    void updateCell(@NotNull Unit unit, @NotNull DeterministicRandom random, boolean displaced, int positionX, int positionY) {
        if (!this.rules.isRepulsionFlyer(unit)) {
            return;
        }

        if (unit.getRepulsionCellX() != (short) this.rules.getShownX(unit) / this.cellSize ||
            unit.getRepulsionCellY() != (short) this.rules.getShownY(unit) / this.cellSize) {
            int cellIndex = unit.getRepulsionCellY() * this.gridDimension + unit.getRepulsionCellX();
            byte[] counts = this.cellCounts;

            if (counts == null || cellIndex >= counts.length) {
                throw Exceptions.newIllegalStateException("The flyer repulsion grid does not contain the previous cell of the unit: {0}", cellIndex);
            }

            int cellCount = Byte.toUnsignedInt(counts[cellIndex]);

            if (cellCount != 0) {
                counts[cellIndex] = (byte) (cellCount - 1);
            }

            this.register(unit, random);
        }

        if (displaced && Math.abs((short) unit.getMovementDestinationX() - (short) positionX) < DESTINATION_SNAP_DISTANCE &&
            Math.abs((short) unit.getMovementDestinationY() - (short) positionY) < DESTINATION_SNAP_DISTANCE) {
            unit.setMovementDestinationX(positionX);
            unit.setMovementWaypointX(positionX);
            unit.setMovementDestinationY(positionY);
            unit.setMovementWaypointY(positionY);
        }
    }

    /**
     * Turns the repulsion direction of a flyer whose countdown ran out: beside the direction to its destination when it is away from its target,
     * then away from a map edge it is near; a turn away from an edge also clears the flyer's adjustment index.
     */
    private int turn(@NotNull Unit unit, boolean atTarget, int mapWidth, int mapHeight) {
        int direction = unit.getRepulsionDirection();

        if (!atTarget) {
            int targetDirection = FlingyMotionUtils.calculateDirection((short) unit.getMovementDestinationX() - unit.getPositionX(),
                                                                       (short) unit.getMovementDestinationY() - unit.getPositionY());
            int difference = (direction - targetDirection) & BYTE_MASK;

            direction = targetDirection + difference % FlingyMotionUtils.DIRECTION_EIGHTH_TURN +
                        (difference < FlingyMotionUtils.DIRECTION_HALF_TURN ? FlingyMotionUtils.DIRECTION_EIGHTH_TURN
                                                                            : -FlingyMotionUtils.DIRECTION_QUARTER_TURN);
        }

        if (this.rules.getLeftEdge(unit) < EDGE_MARGIN) {
            if ((byte) direction < 0) {
                direction = -direction;
            }

            unit.setRepulsionFlags(unit.getRepulsionFlags() & STATE_MASK);
        } else if (this.rules.getRightEdge(unit) > mapWidth - (EDGE_MARGIN + 1)) {
            if ((byte) direction >= 0) {
                direction = -direction;
            }

            unit.setRepulsionFlags(unit.getRepulsionFlags() & STATE_MASK);
        }

        if (this.rules.getTopEdge(unit) < EDGE_MARGIN) {
            if ((byte) (direction + FlingyMotionUtils.DIRECTION_QUARTER_TURN) >= 0) {
                direction = -direction - FlingyMotionUtils.DIRECTION_HALF_TURN;
            }

            unit.setRepulsionFlags(unit.getRepulsionFlags() & STATE_MASK);
        } else if (this.rules.getBottomEdge(unit) > mapHeight - (EDGE_MARGIN + 1)) {
            if ((byte) (direction + FlingyMotionUtils.DIRECTION_QUARTER_TURN) < 0) {
                direction = -direction - FlingyMotionUtils.DIRECTION_HALF_TURN;
            }

            unit.setRepulsionFlags(unit.getRepulsionFlags() & STATE_MASK);
        }

        return direction;
    }

    /**
     * Enlarges a displacement component by the adjustment when it is small, and reduces it by the adjustment when it is beyond four times it.
     */
    private static int adjust(int component, int adjustment) {
        if (component > adjustment * ADJUSTMENT_FACTOR) {
            return component - adjustment;
        }

        if (component > 0) {
            return component + adjustment;
        }

        if (component < -adjustment * ADJUSTMENT_FACTOR) {
            return component + adjustment;
        }

        if (component < 0) {
            return component - adjustment;
        }

        return component;
    }
}
