package org.fogofwarlabs.sc4j.scr.game.production;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameCheats;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitRacesUtils;
import org.jetbrains.annotations.NotNull;

/**
 * Tests supply without displaying errors, shared by the command errors of the game and the production gate of the interface.
 */
public final class SupplyCheck {
    /**
     * The type fits.
     */
    public static final int PASSED = 0;
    /**
     * The used supply would exceed the race's maximum: the supply limit message plus the race.
     */
    public static final int LIMIT_EXCEEDED = 1;
    /**
     * The available supply is too small: the insufficient supply message plus the race.
     */
    public static final int INSUFFICIENT = 2;

    private final @NotNull Game game;

    /**
     * Creates a {@link SupplyCheck} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public SupplyCheck(@NotNull Game game) {
        this.game = game;
    }

    /**
     * @param unitType the requested type
     * @return the half-supply the test requires: {@code 2} times the supply for {@link UnitTypeFlags#TWO_UNITS_IN_ONE_EGG} types, a Lurker's less the Hydralisk's
     * @throws NullPointerException if the world was built without game data
     */
    public int getRequiredSupply(int unitType) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int supply = units.getSupplyRequired(unitType);

        if ((units.getFlags(unitType) & UnitTypeFlags.TWO_UNITS_IN_ONE_EGG) != 0) {
            supply *= UnitMorphing.OFFSPRING_COUNT;
        }

        if (this.game.getBaseType(unitType) == ScrUnitIds.ZERG_LURKER) {
            supply -= units.getSupplyRequired(ScrUnitIds.ZERG_HYDRALISK);
        }

        return supply;
    }

    /**
     * @param unitType the unit type selecting the race
     * @param owner    the player
     * @return the available half-supply of the type's race: the minimum of provided supply and the cap (the cap alone under Food for Thought), minus used supply; {@code 0} for a type without a race
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit accounting is not initialized
     */
    public int getAvailableSupply(int unitType, int owner) {
        int race = UnitRacesUtils.getSupplyRace(this.game.requireGameData().getUnitTypes(), unitType);

        if (race == UnitRacesUtils.NEUTRAL) {
            return 0;
        }

        int base = race * PlayerTable.PLAYER_SLOTS;
        UnitAccounting accounting = this.game.getPlayerTable().requireUnitAccounting();
        int cap = accounting.getSupplyCap(base + owner);
        int used = accounting.getSupplyUsed(base + owner);

        if ((this.game.getCheatFlags() & GameCheats.FOOD_FOR_THOUGHT) != 0) {
            return cap - used;
        }

        return accounting.getSupplyAvailable(base + owner) - used;
    }

    /**
     * @param unitType the unit type selecting the race
     * @param owner    the player
     * @return the used half-supply of the type's race; {@code 0} for a type without a race
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit accounting is not initialized
     */
    public int getSupplyUsed(int unitType, int owner) {
        int race = UnitRacesUtils.getSupplyRace(this.game.requireGameData().getUnitTypes(), unitType);
        int base = race == UnitRacesUtils.NEUTRAL ? -1 : race * PlayerTable.PLAYER_SLOTS;

        return base < 0 ? 0 : this.game.getPlayerTable().requireUnitAccounting().getSupplyUsed(base + owner);
    }


    /**
     * Tests the supply of a unit type: a type without supply or with the supply-check exemption passes. {@link UnitTypeFlags#TWO_UNITS_IN_ONE_EGG}
     * types need {@code 2} times their supply, and a Lurker needs its own less the Hydralisk's. The used supply of the type's race
     * ({@link UnitRacesUtils#getSupplyRace}) plus the need must not exceed the maximum, and the available supply less the need must not be negative.
     *
     * @param unitType the requested type
     * @param owner    the player
     * @return {@link #PASSED}, {@link #LIMIT_EXCEEDED} or {@link #INSUFFICIENT}
     * @throws NullPointerException if the world was built without game data
     */
    public int check(int unitType, int owner) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if (units.getSupplyRequired(unitType) == 0 || (units.getFlags(unitType) & UnitTypeFlags.IGNORE_SUPPLY_CHECK) != 0) {
            return PASSED;
        }

        return this.check(unitType, owner, this.getRequiredSupply(unitType));
    }

    /**
     * Compares a tested type's required supply with the supply of its race.
     *
     * @param unitType the requested type, which selects the race
     * @param owner    the player
     * @param supply   the required half-supply
     * @return {@link #PASSED}, {@link #LIMIT_EXCEEDED} or {@link #INSUFFICIENT}
     * @throws NullPointerException if the world was built without game data
     */
    int check(int unitType, int owner, int supply) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int race = UnitRacesUtils.getSupplyRace(units, unitType);
        int used = this.getSupplyUsed(unitType, owner) + supply;
        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();
        int cap = race == UnitRacesUtils.NEUTRAL || accounting == null ? 0 : accounting.getSupplyCap(race * PlayerTable.PLAYER_SLOTS + owner);

        if (used > cap) {
            return LIMIT_EXCEEDED;
        }

        if (this.getAvailableSupply(unitType, owner) - supply < 0) {
            return INSUFFICIENT;
        }

        return PASSED;
    }
}
