package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedUnitTypes;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrWeaponTypeView;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitTypeTraitsUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Retains the strength of every unit type against air and ground targets, computed from the units.dat and weapons.dat
 * columns before the map unit settings are read.
 * <p>
 * The same tables are the computer players' unit values.
 */
public final class UnitStrengthTable {
    /**
     * The strength direction against air targets.
     */
    public static final int AIR = 0;
    /**
     * The strength direction against ground targets.
     */
    public static final int GROUND = 1;

    /**
     * The number of strength directions (air and ground).
     */
    public static final int DIRECTION_COUNT = 2;

    /**
     * The strength of a type without a weapon for the direction.
     */
    private static final int NO_WEAPON_STRENGTH = 1;
    /**
     * The fraction bits of hit points, shields and energy.
     */
    private static final int FIXED_POINT_SHIFT = 8;
    /**
     * The fixed-point shift of the damage component.
     */
    private static final int DAMAGE_SHIFT = 11;
    /**
     * The shift that scales the damage component down after its multiplication by the hit points.
     */
    private static final int DAMAGE_COMPONENT_SHIFT = 8;
    /**
     * The square root of the combined components is scaled by {@code 758 / 100}: the numerator.
     */
    private static final double STRENGTH_SCALE = 758.0;
    /**
     * The denominator of the {@code 758 / 100} strength scale.
     */
    private static final double STRENGTH_DIVISOR = 100.0;
    /**
     * Workers keep a quarter of their strength.
     */
    private static final int WORKER_STRENGTH_SHIFT = 2;
    /**
     * Firebats, Mutalisks and Zealots have their strength doubled.
     */
    private static final int DOUBLED_STRENGTH_FACTOR = 2;
    /**
     * Scourge and Infested Terran keep a sixteenth of their strength.
     */
    private static final int SUICIDE_STRENGTH_SHIFT = 4;
    /**
     * Reavers keep a tenth of their strength.
     */
    private static final int REAVER_STRENGTH_DIVISOR = 10;
    /**
     * The shift that adds half the energy of a spellcaster to its strength.
     */
    private static final int ENERGY_STRENGTH_SHIFT = 1;
    /**
     * The strength {@link #getStrength(int, int)} returns just past the ground table: the ground strength of the empty
     * type {@link ScrUnitIds#NONE}.
     */
    private static final int PAST_GROUND_TABLE_STRENGTH = 0;

    /**
     * Computes the strength of every native unit type against air and ground targets.
     *
     * @param units   the initialized unit type table
     * @param weapons the initialized weapon type table
     * @return the strengths, air then ground
     */
    private static int @NotNull [] computeNativeStrengths(@NotNull ScrUnitTypeView units, @NotNull ScrWeaponTypeView weapons) {
        int[] strengths = new int[DIRECTION_COUNT * UnitsDat.UNIT_COUNT];

        for (int unitType = 0; unitType < ScrUnitIds.NONE; unitType++) {
            int weaponUnit = getWeaponUnit(units, unitType);

            for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
                if (units.isLarvaOrEgg(unitType)) {
                    continue;
                }

                strengths[direction * UnitsDat.UNIT_COUNT + unitType] = computeStrength(units, weapons, unitType, unitType, weaponUnit, direction);
            }

            clearMissingDirection(strengths, AIR * UnitsDat.UNIT_COUNT + unitType, GROUND * UnitsDat.UNIT_COUNT + unitType);
        }

        return strengths;
    }

    /**
     * Returns the unit whose weapons the strength of a native unit type reads: the Interceptor for a Carrier, the Scarab for a Reaver, the
     * first subunit of a type with one, the type itself otherwise.
     *
     * @param units    the initialized unit type table
     * @param unitType the native unit type
     * @return the unit type whose weapons count
     */
    private static int getWeaponUnit(@NotNull ScrUnitTypeView units, int unitType) {
        return switch (ScrUnitIds.requireValid(unitType)) {
            case ScrUnitIds.PROTOSS_CARRIER, ScrUnitIds.HERO_GANTRITHOR -> ScrUnitIds.PROTOSS_INTERCEPTOR;
            case ScrUnitIds.HERO_WARBRINGER, ScrUnitIds.PROTOSS_REAVER -> ScrUnitIds.PROTOSS_SCARAB;
            default -> units.getFirstSubunit(unitType) == ScrUnitIds.NONE ? unitType : units.getFirstSubunit(unitType);
        };
    }

    /**
     * Computes the strength of a unit type in a direction: {@link #NO_WEAPON_STRENGTH} without a weapon, otherwise the weapon strength
     * ({@link #computeWeaponStrength}) with the per-type rule of the rule type: workers keep a quarter, Spider Mines, Interceptors and Scarabs
     * none, Firebats, Mutalisks and Zealots double it, Scourge and Infested Terrans keep a sixteenth, Reavers a tenth.
     *
     * @param units      the initialized unit type table
     * @param weapons    the initialized weapon type table
     * @param unitType   the unit type, whose hit points and shields count
     * @param ruleType   the native unit type whose per-type rule applies
     * @param weaponUnit the unit type whose weapons count
     * @param direction  {@link #AIR} or {@link #GROUND}
     * @return the strength
     */
    private static int computeStrength(@NotNull ScrUnitTypeView units,
                                       @NotNull ScrWeaponTypeView weapons,
                                       int unitType, int ruleType, int weaponUnit,
                                       int direction) {
        int weapon = direction == AIR ? units.getAirWeapon(weaponUnit) : units.getGroundWeapon(weaponUnit);

        if (weapon == ScrWeaponIds.NONE) {
            return NO_WEAPON_STRENGTH;
        }

        int strength = computeWeaponStrength(units, weapons, unitType, weapon);

        return switch (ScrUnitIds.requireValid(ruleType)) {
            case ScrUnitIds.TERRAN_SCV, ScrUnitIds.ZERG_DRONE, ScrUnitIds.PROTOSS_PROBE -> strength >>> WORKER_STRENGTH_SHIFT;
            case ScrUnitIds.TERRAN_VULTURE_SPIDER_MINE, ScrUnitIds.PROTOSS_INTERCEPTOR, ScrUnitIds.PROTOSS_SCARAB -> 0;
            case ScrUnitIds.TERRAN_FIREBAT, ScrUnitIds.ZERG_MUTALISK, ScrUnitIds.PROTOSS_ZEALOT -> strength * DOUBLED_STRENGTH_FACTOR;
            case ScrUnitIds.ZERG_SCOURGE, ScrUnitIds.ZERG_INFESTED_TERRAN -> strength >>> SUICIDE_STRENGTH_SHIFT;
            case ScrUnitIds.PROTOSS_REAVER -> Integer.divideUnsigned(strength, REAVER_STRENGTH_DIVISOR);
            default -> strength;
        };
    }

    /**
     * Computes the strength of a unit type with a weapon, before the per-type rules: from the weapon's range, cooldown and damage and the
     * type's hit points and shields.
     *
     * @param units    the initialized unit type table
     * @param weapons  the initialized weapon type table
     * @param unitType the unit type, whose hit points and shields count
     * @param weapon   the weapon, not {@link ScrWeaponIds#NONE}
     * @return the strength
     */
    private static int computeWeaponStrength(@NotNull ScrUnitTypeView units, @NotNull ScrWeaponTypeView weapons, int unitType, int weapon) {
        int hitPoints = units.getHitPoints(unitType) >> FIXED_POINT_SHIFT;

        if (units.getShieldEnabled(unitType) != 0) {
            hitPoints += units.getShieldAmount(unitType);
        }

        if (hitPoints == 0) {
            return 0;
        }

        int cooldown = weapons.getCooldown(weapon);
        int damage = weapons.getDamageAmount(weapon);
        int factor = weapons.getDamageFactor(weapon);
        int rangeComponent = Integer.divideUnsigned(weapons.getMaximumRange(weapon), cooldown) * factor * damage;
        int damageComponent = ((factor * damage << DAMAGE_SHIFT) / cooldown * hitPoints) >>> DAMAGE_COMPONENT_SHIFT;

        return (int) (long) (Math.sqrt(Integer.toUnsignedLong(rangeComponent + damageComponent)) * STRENGTH_SCALE / STRENGTH_DIVISOR);
    }

    /**
     * Clears the no-weapon strength of one direction when the other direction has a weapon strength.
     *
     * @param strengths   the strength table
     * @param airIndex    the index of the air strength
     * @param groundIndex the index of the ground strength
     */
    private static void clearMissingDirection(int @NotNull [] strengths, int airIndex, int groundIndex) {
        int airStrength = strengths[airIndex];
        int groundStrength = strengths[groundIndex];

        if (airStrength == NO_WEAPON_STRENGTH && Integer.compareUnsigned(groundStrength, NO_WEAPON_STRENGTH) > 0) {
            strengths[airIndex] = 0;
        }

        if (groundStrength == NO_WEAPON_STRENGTH && Integer.compareUnsigned(airStrength, NO_WEAPON_STRENGTH) > 0) {
            strengths[groundIndex] = 0;
        }
    }


    private final int @NotNull [] strengths;
    /**
     * The strengths of the added unit types, {@code direction * addedCount + added}.
     */
    private final int @NotNull [] addedStrengths;
    private final int addedCount;

    /**
     * Creates a {@link UnitStrengthTable} instance and computes the strength of every native unit type; the table holds no added unit type.
     *
     * @param units   the initialized unit type table
     * @param weapons the initialized weapon type table
     */
    public UnitStrengthTable(@NotNull ScrUnitTypeView units, @NotNull ScrWeaponTypeView weapons) {
        this.strengths      = computeNativeStrengths(units, weapons);
        this.addedStrengths = new int[0];
        this.addedCount     = 0;
    }

    /**
     * Creates a {@link UnitStrengthTable} instance and computes the strength of every native unit type from one table and of every added unit
     * type of a game's data: a game computes the native strengths from the installed tables and the added ones from its own, the only tables
     * that hold them and the weapons added for them. An added type's strength comes from its own values with the per-type rules of its base type
     * ({@link GameData#getBaseType(int)}).
     *
     * @param units    the initialized unit type table of the native types
     * @param weapons  the initialized weapon type table of the native weapons
     * @param gameData the game data whose unit and weapon type tables hold the added unit types and weapons
     */
    public UnitStrengthTable(@NotNull ScrUnitTypeView units, @NotNull ScrWeaponTypeView weapons, @NotNull GameData gameData) {
        ScrUnitTypeView addedUnits = gameData.getUnitTypes();
        ScrWeaponTypeView addedWeapons = gameData.getWeaponTypes();

        this.strengths      = computeNativeStrengths(units, weapons);
        this.addedCount     = addedUnits.getAddedCount();
        this.addedStrengths = new int[DIRECTION_COUNT * this.addedCount];

        for (int added = 0; added < this.addedCount; added++) {
            int unitType = ScrAddedUnitTypes.FIRST_ID + added;
            int baseType = gameData.getBaseType(unitType);
            int weaponUnit = getWeaponUnit(addedUnits, baseType);

            // An added type has no subunit: it fires its own weapons unless its base type fires those of another unit.
            if (weaponUnit == baseType) {
                weaponUnit = unitType;
            }

            for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
                if (addedUnits.isLarvaOrEgg(unitType)) {
                    continue;
                }

                int strength = computeStrength(addedUnits, addedWeapons, unitType, baseType, weaponUnit, direction);

                this.addedStrengths[direction * this.addedCount + added] = strength;
            }

            clearMissingDirection(this.addedStrengths, AIR * this.addedCount + added, GROUND * this.addedCount + added);
        }
    }

    /**
     * @return the number of added unit types the table holds strengths for
     */
    public int getAddedCount() {
        return this.addedCount;
    }

    /**
     * Reads the strength of a unit type in a direction. The air table is followed by the ground table, so the air
     * strength of the empty type {@link ScrUnitIds#NONE} is the ground strength of type {@code 0}; its ground strength is
     * {@link #PAST_GROUND_TABLE_STRENGTH}. An added unit type reads its own strengths.
     *
     * @param unitType  the unit type
     * @param direction {@link #AIR} or {@link #GROUND}
     * @return the retained unsigned DWORD strength
     */
    public int getStrength(int unitType, int direction) {
        int added = unitType - ScrAddedUnitTypes.FIRST_ID;

        if (added >= 0 && added < this.addedCount) {
            return this.addedStrengths[direction * this.addedCount + added];
        }

        int index = direction * UnitsDat.UNIT_COUNT + unitType;

        if (index == this.strengths.length) {
            return PAST_GROUND_TABLE_STRENGTH;
        }

        return this.strengths[index];
    }

    /**
     * @return the live strengths, air then ground, which EUD writes to the classic strength table change
     */
    public int @NotNull [] getLiveStrengths() {
        return this.strengths;
    }


    /**
     * Returns the value of a unit in a direction: the strength of its type, where a Bunker takes the Marine strength
     * multiplied by the space occupied by its loaded units, plus half the energy of a spellcaster that is not a
     * hallucination.
     *
     * @param game      the game whose unit pool resolves loaded-unit handles, or {@code null}
     * @param unit      the unit
     * @param direction {@link #AIR} or {@link #GROUND}
     * @param units     the current unit type table
     * @return the unsigned DWORD value
     * @throws UnsupportedOperationException if the unit is a Bunker and no game is supplied
     */
    public int getValue(@Nullable Game game, @NotNull Unit unit, int direction, @NotNull ScrUnitTypeView units) {
        int unitType = unit.getUnitId();
        int value = this.getStrength(unitType, direction);

        if (unitType == ScrUnitIds.TERRAN_BUNKER) {
            if (game == null) {
                throw Exceptions.newUnsupportedOperationException("Bunker strength requires a game to resolve its loaded units.");
            }

            value = this.getStrength(ScrUnitIds.TERRAN_MARINE, direction) * game.getUnitTransport().getOccupiedSpace(unit);
        }

        if (UnitTypeTraitsUtils.isSpellcaster(units, unit)) {
            value += (unit.getEnergy() >>> FIXED_POINT_SHIFT) >>> ENERGY_STRENGTH_SHIFT;
        }

        return value;
    }

    /**
     * Applies the integer health scaling of the strength without pool access.
     *
     * @param unit      the unit
     * @param direction {@link #AIR} or {@link #GROUND}
     * @param units     the current unit type table
     * @return the scaled unsigned DWORD strength
     * @throws UnsupportedOperationException if the unit is a Bunker, whose strength needs its loaded units
     */
    public int calculate(@NotNull Unit unit, int direction, @NotNull ScrUnitTypeView units) {
        return this.calculate(null, unit, direction, units);
    }

    /**
     * Applies the integer health scaling of the strength: the value ({@link #getValue}) times the current health divided
     * by the maximum health.
     * <p>
     * Larvae, eggs, cocoons, Lurker eggs and damaged hallucinations have none.
     *
     * @param game      the game whose unit pool resolves loaded-unit handles, or {@code null}
     * @param unit      the unit
     * @param direction {@link #AIR} or {@link #GROUND}
     * @param units     the current unit type table
     * @return the scaled unsigned DWORD strength
     * @throws UnsupportedOperationException if the unit is a Bunker and no game is supplied
     */
    public int calculate(@Nullable Game game, @NotNull Unit unit, int direction, @NotNull ScrUnitTypeView units) {
        int unitType = unit.getUnitId();

        if (units.isLarvaOrEgg(unitType)) {
            return 0;
        }

        int currentHealth = UnitQueries.getLife(units, unit);
        int maximumHitPoints = game != null ? game.getUnitHealth().getMaximumHitPoints(unit) : units.getHitPoints(unitType);
        int maximumHealth = UnitQueries.getMaximumLife(units, maximumHitPoints, unit);

        if ((unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0 && currentHealth < maximumHealth) {
            return 0;
        }

        int strength = this.getValue(game, unit, direction, units);

        return Integer.divideUnsigned(currentHealth * strength, maximumHealth == 0 ? 1 : maximumHealth);
    }

    /**
     * Updates the air and ground strength WORDs of the unit.
     *
     * @param game  the game whose unit pool resolves loaded-unit handles, or {@code null}
     * @param unit  the unit receiving its strengths
     * @param units the current unit type table
     * @throws UnsupportedOperationException if the unit is a Bunker and no game is supplied
     */
    public void update(@Nullable Game game, @NotNull Unit unit, @NotNull ScrUnitTypeView units) {
        unit.setAirStrength(this.calculate(game, unit, AIR, units));
        unit.setGroundStrength(this.calculate(game, unit, GROUND, units));
    }
}
