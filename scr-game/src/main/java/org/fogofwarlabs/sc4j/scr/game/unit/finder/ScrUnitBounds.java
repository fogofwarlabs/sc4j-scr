package org.fogofwarlabs.sc4j.scr.game.unit.finder;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.spatial.UnitBounds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the bounds of an SC:R unit for the unit finder's sorted-edge index: the sprite position as signed WORDs and the {@code units.dat}
 * dimensions of the unit type, read as signed WORDs, from the type table the finder last received. A unit with a hidden sprite is not placed.
 * Every read requires the unit sprite.
 */
final class ScrUnitBounds implements UnitBounds<Unit> {
    private @Nullable ScrUnitTypeView units;

    /**
     * Creates a {@link ScrUnitBounds} instance without a type table.
     */
    ScrUnitBounds() {
        this.units = null;
    }

    /**
     * Sets the type table the dimensions are read from.
     *
     * @param units the unit type table
     */
    void setUnitTypes(@NotNull ScrUnitTypeView units) {
        this.units = units;
    }

    /**
     * @param unit the unit
     * @return the left edge of the unit
     * @throws IllegalStateException if the unit has no sprite or no type table is set
     */
    @Override
    public int getLeft(@NotNull Unit unit) {
        return (short) unit.requireSprite().getX() - (short) this.requireUnitTypes().getDimensionLeft(unit.getUnitId());
    }

    /**
     * @param unit the unit
     * @return the top edge of the unit
     * @throws IllegalStateException if the unit has no sprite or no type table is set
     */
    @Override
    public int getTop(@NotNull Unit unit) {
        return (short) unit.requireSprite().getY() - (short) this.requireUnitTypes().getDimensionUp(unit.getUnitId());
    }

    /**
     * @param unit the unit
     * @return the right edge of the unit, inclusive
     * @throws IllegalStateException if the unit has no sprite or no type table is set
     */
    @Override
    public int getRight(@NotNull Unit unit) {
        return (short) unit.requireSprite().getX() + (short) this.requireUnitTypes().getDimensionRight(unit.getUnitId());
    }

    /**
     * @param unit the unit
     * @return the bottom edge of the unit, inclusive
     * @throws IllegalStateException if the unit has no sprite or no type table is set
     */
    @Override
    public int getBottom(@NotNull Unit unit) {
        return (short) unit.requireSprite().getY() + (short) this.requireUnitTypes().getDimensionDown(unit.getUnitId());
    }

    /**
     * @param unit the unit
     * @return the width of the unit's bounds in pixels: the left and right dimensions plus one, as a signed WORD
     * @throws IllegalStateException if no type table is set
     */
    @Override
    public int getWidth(@NotNull Unit unit) {
        ScrUnitTypeView unitTypes = this.requireUnitTypes();
        int unitType = unit.getUnitId();

        return (short) (unitTypes.getDimensionLeft(unitType) + 1 + unitTypes.getDimensionRight(unitType));
    }

    /**
     * @param unit the unit
     * @return the height of the unit's bounds in pixels: the up and down dimensions plus one, as a signed WORD
     * @throws IllegalStateException if no type table is set
     */
    @Override
    public int getHeight(@NotNull Unit unit) {
        ScrUnitTypeView unitTypes = this.requireUnitTypes();
        int unitType = unit.getUnitId();

        return (short) (unitTypes.getDimensionUp(unitType) + 1 + unitTypes.getDimensionDown(unitType));
    }

    /**
     * @param unit the unit
     * @return the x coordinate of the point a nearest search measures the unit from: the sprite position as a signed WORD
     * @throws IllegalStateException if the unit has no sprite
     */
    @Override
    public int getCenterX(@NotNull Unit unit) {
        return (short) unit.requireSprite().getX();
    }

    /**
     * @param unit the unit
     * @return the y coordinate of the point a nearest search measures the unit from: the sprite position as a signed WORD
     * @throws IllegalStateException if the unit has no sprite
     */
    @Override
    public int getCenterY(@NotNull Unit unit) {
        return (short) unit.requireSprite().getY();
    }

    /**
     * @param unit the unit
     * @return {@code true} if the unit's sprite is not hidden, so a search around it starts at its own edges, {@code false} if it is hidden and a search around it starts at its sprite position
     * @throws IllegalStateException if the unit has no sprite
     */
    @Override
    public boolean isPlaced(@NotNull Unit unit) {
        return (unit.requireSprite().getFlags() & Sprite.FLAG_HIDDEN) == 0;
    }

    private @NotNull ScrUnitTypeView requireUnitTypes() {
        ScrUnitTypeView unitTypes = this.units;

        if (unitTypes == null) {
            throw Exceptions.newIllegalStateException("The unit finder bounds require the unit type table.");
        }

        return unitTypes;
    }
}
