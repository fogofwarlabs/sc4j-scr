package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.fogofwarlabs.sc4j.scr.content.*;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.combat.UnitTargeting;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.ScrHooks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Provides the unit queries shared by the simulation, the computer players and the interface.
 * <p>
 * The interface uses the same predicates as the simulation. Status bits come from {@link Unit#getStatus()}; {@code units.dat} fields come from the
 * game data.
 */
public final class UnitQueries {
    /**
     * The hangar capacity of a hero Carrier or of a Carrier with Carrier Capacity.
     */
    public static final int UPGRADED_CARRIER_CAPACITY = 8;
    /**
     * The hangar capacity of a hero Reaver or of a Reaver with Reaver Capacity.
     */
    public static final int UPGRADED_REAVER_CAPACITY = 10;
    /**
     * The offset of the first build queue slot in the {@code 488}-byte unit layout ({@link NativeUnitFields}).
     */
    static final int NATIVE_BUILD_QUEUE = 0xDC;

    /**
     * The mask of an unsigned byte.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The hangar use BYTE of a unit without a hangar.
     */
    private static final int NO_HANGAR = 0xFF;
    /**
     * The number of fraction bits of the hit points and the shield points.
     */
    private static final int POINT_FRACTION_BITS = 8;
    /**
     * The scale of a whole percentage.
     */
    private static final int PERCENT = 100;
    /**
     * The largest hit point percentage of a heavily damaged unit.
     */
    private static final int HEAVY_DAMAGE_PERCENT = 33;

    /**
     * Returns the rank of a unit: the {@code units.dat} Sublabel plus the unit's rank increase ({@link Unit#getRankIncrease()}),
     * summed as a byte. The selection's active unit, the speaker of a new selection and the Terran rank text read it.
     *
     * @param units the unit type table
     * @param unit  the unit
     * @return the unsigned rank byte
     */
    public static int getRank(@NotNull ScrUnitTypeView units, @NotNull Unit unit) {
        return units.getSublabel(unit.getUnitId()) + unit.getRankIncrease() & BYTE_MASK;
    }

    /**
     * Tests whether a unit type is a building: a {@code units.dat} type, below {@link ScrUnitIds#NONE}, with the building flag. A type beyond
     * {@code units.dat} is never a building, whatever its flag DWORD holds. Every bounded building test goes through this method.
     *
     * @param units the unit type table
     * @param type  the unit type
     * @return {@code true} if the type is a {@code units.dat} type with {@link UnitTypeFlags#BUILDING}, {@code false} otherwise
     */
    public static boolean isBuilding(@NotNull ScrUnitTypeView units, int type) {
        return units.hasEntry(type) && (units.getFlags(type) & UnitTypeFlags.BUILDING) != 0;
    }

    /**
     * Tests whether the unit's current status marks a grounded building.
     *
     * @param unit the unit
     * @return {@code true} if the current status has {@link UnitStatusFlags#GROUNDED_BUILDING}, {@code false} otherwise
     */
    public static boolean isGroundedBuilding(@NotNull Unit unit) {
        return (unit.getStatus() & UnitStatusFlags.GROUNDED_BUILDING) != 0;
    }

    /**
     * Returns the hit points of a unit in whole points, rounded up (toward positive infinity, also for a negative value).
     *
     * @param unit the unit
     * @return the whole hit points
     */
    public static int getHitPoints(@NotNull Unit unit) {
        return (unit.getHitPoints() + Unit.HIT_POINT_ROUNDING) >> POINT_FRACTION_BITS;
    }

    /**
     * Returns the maximum hit points of a unit in whole points from its maximum with eight fractional bits, or for zero its current hit points
     * ({@link #getHitPoints}), or for zero again {@code 1}. A negative maximum stays negative.
     *
     * @param maximumHitPoints the unit's maximum hit points with eight fractional bits ({@link UnitHealth#getMaximumHitPoints})
     * @param unit             the unit
     * @return the maximum hit points
     */
    public static int getMaximumHitPoints(int maximumHitPoints, @NotNull Unit unit) {
        int maximum = maximumHitPoints >> POINT_FRACTION_BITS;

        if (maximum != 0) {
            return maximum;
        }

        int current = getHitPoints(unit);

        return current != 0 ? current : 1;
    }

    /**
     * Returns the hit points of a unit ({@link #getHitPoints}) as a whole percentage of its maximum hit points
     * ({@link #getMaximumHitPoints(int, Unit)}), rounded toward zero.
     *
     * @param maximumHitPoints the unit's maximum hit points with eight fractional bits ({@link UnitHealth#getMaximumHitPoints})
     * @param unit             the unit
     * @return the hit point percentage
     */
    public static int getHitPointPercent(int maximumHitPoints, @NotNull Unit unit) {
        return getHitPoints(unit) * PERCENT / getMaximumHitPoints(maximumHitPoints, unit);
    }

    /**
     * Tests whether a unit is heavily damaged: its hit point percentage ({@link #getHitPointPercent(int, Unit)}), compared unsigned, is at most
     * {@code 33}. A heavily damaged Terran building burns, and the status panel and the building responses show the damage.
     *
     * @param maximumHitPoints the unit's maximum hit points with eight fractional bits ({@link UnitHealth#getMaximumHitPoints})
     * @param unit             the unit
     * @return {@code true} if the unit is heavily damaged, {@code false} otherwise
     */
    public static boolean isHeavilyDamaged(int maximumHitPoints, @NotNull Unit unit) {
        return Integer.compareUnsigned(getHitPointPercent(maximumHitPoints, unit), HEAVY_DAMAGE_PERCENT) <= 0;
    }

    /**
     * Returns the life of a unit: its hit points ({@link #getHitPoints}) plus its whole shield points when its unit type has shields.
     *
     * @param units the unit type table
     * @param unit  the unit
     * @return the life in whole points
     */
    public static int getLife(@NotNull ScrUnitTypeView units, @NotNull Unit unit) {
        int shields = units.getShieldEnabled(unit.getUnitId()) != 0 ? unit.getShieldPoints() >> POINT_FRACTION_BITS : 0;

        return getHitPoints(unit) + shields;
    }

    /**
     * Returns the maximum life of a unit: its maximum hit points ({@link #getMaximumHitPoints(int, Unit)}) plus the {@code units.dat} shield
     * amount when its unit type has shields.
     *
     * @param units            the unit type table
     * @param maximumHitPoints the unit's maximum hit points with eight fractional bits ({@link UnitHealth#getMaximumHitPoints})
     * @param unit             the unit
     * @return the maximum life in whole points
     */
    public static int getMaximumLife(@NotNull ScrUnitTypeView units, int maximumHitPoints, @NotNull Unit unit) {
        int type = unit.getUnitId();
        int shields = units.getShieldEnabled(type) != 0 ? units.getShieldAmount(type) : 0;

        return getMaximumHitPoints(maximumHitPoints, unit) + shields;
    }

    /**
     * Returns the unit type of the current production entry: the build queue WORD at the current queue index. The index has no bound: an index
     * from {@link Unit#BUILD_QUEUE_SLOTS} on reads the WORD of the {@code 488}-byte unit layout that follows the queue ({@link NativeUnitFields}),
     * so index {@code 5} reads the energy and index {@code 6} the queue index with the slot's generation in the high byte. A WORD outside the
     * represented fields of the layout reads as an empty slot ({@code 228}).
     *
     * @param unit the unit
     * @return the queued unit type
     */
    public static int getQueuedType(@NotNull Unit unit) {
        int index = unit.getBuildQueueIndex();

        if (index < Unit.BUILD_QUEUE_SLOTS) {
            return unit.getBuildQueueUnitType(index);
        }

        int offset = NATIVE_BUILD_QUEUE + index * Short.BYTES;

        if (NativeUnitFields.getFieldStart(offset) < 0 || NativeUnitFields.getFieldStart(offset + 1) < 0) {
            return ScrUnitIds.NONE;
        }

        return NativeUnitFields.readByte(unit, offset) | NativeUnitFields.readByte(unit, offset + 1) << Byte.SIZE;
    }

    /**
     * Returns the unit type in the queue slot {@code offset} slots after the current one. The slot index wraps around the five queue slots.
     *
     * @param unit   the unit
     * @param offset the slot offset from the current slot, from {@code 0}
     * @return the queued unit type, or {@code 228} for an empty slot
     */
    public static int getQueueSlotType(@NotNull Unit unit, int offset) {
        return unit.getBuildQueueUnitType((unit.getBuildQueueIndex() + offset) % Unit.BUILD_QUEUE_SLOTS);
    }

    /**
     * Tests whether a unit is an incomplete building that morphs into a Lair, Hive, Greater Spire, Spore Colony or Sunken Colony: its current
     * production entry ({@link #getQueuedType}) is one of those types ({@link ScrUnitTypeView#isBuildingMorphType}).
     *
     * @param units the unit type table
     * @param unit  the unit
     * @return {@code true} if the unit is an incomplete building morph, {@code false} otherwise
     */
    public static boolean isBuildingMorph(@NotNull ScrUnitTypeView units, @NotNull Unit unit) {
        return (unit.getStatus() & UnitStatusFlags.COMPLETED) == 0 && units.isBuildingMorphType(getQueuedType(unit));
    }

    /**
     * Returns the displayed type of a unit: the queued type of an incomplete building morph ({@link #isBuildingMorph}), otherwise the unit type.
     * The status panel, its wireframe and the replay production list show this type.
     *
     * @param units the unit type table
     * @param unit  the unit
     * @return the displayed unit type
     */
    public static int getDisplayedType(@NotNull ScrUnitTypeView units, @NotNull Unit unit) {
        return isBuildingMorph(units, unit) ? getQueuedType(unit) : unit.getUnitId();
    }

    /**
     * Returns the build time of what a unit builds: the queued type ({@link #getQueuedType}) of an Egg, Cocoon, Lurker Egg or incomplete
     * building morph ({@link #isBuildingMorph}), otherwise the unit type, read through {@link ScrUnitTypeView#getBuildTime(int)}.
     *
     * @param units the unit type table
     * @param unit  the unit
     * @return the build time in frames
     */
    public static int getBuildTime(@NotNull ScrUnitTypeView units, @NotNull Unit unit) {
        int type = unit.getUnitId();

        if (units.isEgg(type) || isBuildingMorph(units, unit)) {
            type = getQueuedType(unit);
        }

        return units.getBuildTime(type);
    }

    /**
     * Tests whether a unit is producing: the current queue slot ({@link #getQueueSlotType}) holds a type, or the building constructs an add-on
     * ({@link #isBuildingAddon}) or places a Protoss building ({@link #isPlacingProtossBuilding}).
     *
     * @param unit the unit
     * @return {@code true} if the unit is producing, {@code false} otherwise
     */
    public static boolean isProducing(@NotNull Unit unit) {
        return getQueueSlotType(unit, 0) != ScrUnitIds.NONE || isBuildingAddon(unit) || isPlacingProtossBuilding(unit);
    }

    /**
     * Tests a grounded building constructing an incomplete add-on (secondary order Build Addon).
     *
     * @param unit the unit
     * @return {@code true} if the unit builds an add-on, {@code false} otherwise
     */
    public static boolean isBuildingAddon(@NotNull Unit unit) {
        Unit addon = unit.getCurrentBuildUnit();

        return unit.getSecondaryOrder() == ScrOrderIds.BUILD_ADDON && isGroundedBuilding(unit) &&
               addon != null && (addon.getStatus() & UnitStatusFlags.COMPLETED) == 0;
    }

    /**
     * Tests a grounded building placing a Protoss building (primary order Place Protoss Building) whose target is incomplete.
     *
     * @param unit the unit
     * @return {@code true} if the unit places a building, {@code false} otherwise
     */
    public static boolean isPlacingProtossBuilding(@NotNull Unit unit) {
        Unit target = unit.getOrderTargetUnit();

        return unit.getPrimaryOrder() == ScrOrderIds.PLACE_PROTOSS_BUILDING && isGroundedBuilding(unit) &&
               target != null && (target.getStatus() & UnitStatusFlags.COMPLETED) == 0;
    }

    /**
     * Tests whether a unit researches a technology.
     *
     * @param unit the unit
     * @return {@code true} if a technology is being researched, {@code false} otherwise
     */
    public static boolean isResearching(@NotNull Unit unit) {
        return unit.getResearchType() != ScrTechIds.NONE;
    }

    /**
     * Tests whether a unit researches an upgrade.
     *
     * @param unit the unit
     * @return {@code true} if an upgrade is being researched, {@code false} otherwise
     */
    public static boolean isUpgrading(@NotNull Unit unit) {
        return unit.getUpgradeType() != ScrUpgradeIds.NONE;
    }

    /**
     * Tests whether the queue slot {@code offset} after the current one ({@link #getQueueSlotType}) holds a queue unit type
     * ({@link UnitTypeTraitsUtils#isQueueUnitType}); offset {@code 4} is the production test's full queue, offset {@code 0} a unit in training.
     *
     * @param units  the unit type table
     * @param unit   the unit
     * @param offset the slot offset from the current slot
     * @return {@code true} if the slot holds a unit type, {@code false} otherwise
     */
    public static boolean hasQueuedUnit(@NotNull ScrUnitTypeView units, @NotNull Unit unit, int offset) {
        return UnitTypeTraitsUtils.isQueueUnitType(units, getQueueSlotType(unit, offset));
    }

    /**
     * Returns the unit held in the shared slot of a Nuclear Silo's missile and a Nydus Canal's exit.
     *
     * @param unit the unit
     * @return the linked unit, or {@code null}
     */
    public static @Nullable Unit getLinkedUnit(@NotNull Unit unit) {
        Unit missile = unit.getSiloNuke();

        return missile != null ? missile : unit.getNydusExit();
    }

    /**
     * Returns the cloaking technology of a unit: Personnel Cloaking for the Ghost types ({@link ScrUnitTypeView#isGhost})
     * and Infested Kerrigan, Cloaking Field for the Wraith and Tom Kazansky, otherwise none.
     *
     * @param units the unit type table
     * @param unit  the unit
     * @return the cloaking technology
     */
    public static int getCloakTechnology(@NotNull ScrUnitTypeView units, @NotNull Unit unit) {
        int type = unit.getUnitId();
        int baseType = units.getBaseType(type);

        if (units.isGhost(type) || baseType == ScrUnitIds.HERO_INFESTED_KERRIGAN) {
            return ScrTechIds.PERSONNEL_CLOAKING;
        }

        return baseType == ScrUnitIds.TERRAN_WRAITH || baseType == ScrUnitIds.HERO_TOM_KAZANSKY ? ScrTechIds.CLOAKING_FIELD : ScrTechIds.NONE;
    }

    /**
     * Returns the hangar use of a unit: a Carrier's Interceptors inside and outside the hangar or a Reaver's Scarabs
     * inside it, each plus the Interceptors and Scarabs among the five queue slots; {@code 0xFF} for other units.
     *
     * @param units the unit type table
     * @param unit  the unit
     * @return the hangar use BYTE
     */
    public static int getHangarUse(@NotNull ScrUnitTypeView units, @NotNull Unit unit) {
        int type = unit.getUnitId();
        int stored;

        if (units.isCarrier(type)) {
            stored = unit.getHangarCount(false) + unit.getHangarCount(true);
        } else if (units.isReaver(type)) {
            stored = unit.getHangarCount(false);
        } else {
            return NO_HANGAR;
        }

        int queued = 0;

        for (int slot = 0; slot < Unit.BUILD_QUEUE_SLOTS; slot++) {
            int queuedType = unit.getBuildQueueUnitType(slot);

            int queuedBaseType = units.getBaseType(queuedType);

            if (queuedBaseType == ScrUnitIds.PROTOSS_INTERCEPTOR || queuedBaseType == ScrUnitIds.PROTOSS_SCARAB) {
                queued++;
            }
        }

        return stored + queued & BYTE_MASK;
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitQueries} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitQueries(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Returns the maximum hit points of a unit in whole points ({@link #getMaximumHitPoints(int, Unit)} of
     * {@link UnitHealth#getMaximumHitPoints}).
     *
     * @param unit the unit
     * @return the maximum hit points
     * @throws NullPointerException if the world was built without game data
     */
    public int getMaximumHitPoints(@NotNull Unit unit) {
        return getMaximumHitPoints(this.game.getUnitHealth().getMaximumHitPoints(unit), unit);
    }

    /**
     * Returns the hit point percentage of a unit ({@link #getHitPointPercent(int, Unit)} of {@link UnitHealth#getMaximumHitPoints}).
     *
     * @param unit the unit
     * @return the hit point percentage
     * @throws NullPointerException if the world was built without game data
     */
    public int getHitPointPercent(@NotNull Unit unit) {
        return getHitPointPercent(this.game.getUnitHealth().getMaximumHitPoints(unit), unit);
    }

    /**
     * Tests whether a unit is heavily damaged ({@link #isHeavilyDamaged(int, Unit)} of {@link UnitHealth#getMaximumHitPoints}).
     *
     * @param unit the unit
     * @return {@code true} if the unit is heavily damaged, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    public boolean isHeavilyDamaged(@NotNull Unit unit) {
        return isHeavilyDamaged(this.game.getUnitHealth().getMaximumHitPoints(unit), unit);
    }

    /**
     * Returns the maximum life of a unit ({@link #getMaximumLife(ScrUnitTypeView, int, Unit)} of {@link UnitHealth#getMaximumHitPoints}).
     *
     * @param unit the unit
     * @return the maximum life in whole points
     * @throws NullPointerException if the world was built without game data
     */
    public int getMaximumLife(@NotNull Unit unit) {
        return getMaximumLife(this.game.requireGameData().getUnitTypes(), this.game.getUnitHealth().getMaximumHitPoints(unit), unit);
    }

    /**
     * Tests whether the unit type can burrow ({@code units.dat} flag {@link UnitTypeFlags#BURROWABLE}).
     *
     * @param unit the unit
     * @return {@code true} if the type can burrow, {@code false} otherwise
     */
    public boolean isBurrowable(@NotNull Unit unit) {
        return this.hasTypeFlag(unit, UnitTypeFlags.BURROWABLE);
    }

    /**
     * Returns the {@code units.dat} special ability flags of a unit type.
     *
     * @param type the {@code units.dat} type
     * @return the {@code units.dat} special ability flags
     * @throws NullPointerException if the world was built without game data
     */
    public int getSpecialAbilityFlags(int type) {
        return this.game.requireGameData().getUnitTypes().getFlags(type);
    }

    /**
     * Returns the right-click class of a unit: {@code 3} for a burrowed Lurker, otherwise the {@code units.dat} Right-click Action,
     * where a grounded producer keeping a rally point ({@link ScrUnitTypeView#isRallyProducer}) without one answers {@code 2}.
     *
     * @param unit the unit
     * @return the right-click class
     * @throws NullPointerException if the unit is not a burrowed Lurker and the world was built without game data
     */
    public int getRightClickClass(@NotNull Unit unit) {
        int type = unit.getUnitId();

        if (this.game.getBaseType(type) == ScrUnitIds.ZERG_LURKER && (unit.getStatus() & UnitStatusFlags.BURROWED) != 0) {
            return ScrUnitTypeView.RIGHT_CLICK_ATTACK_NO_MOVE;
        }

        int action = this.game.requireGameData().getUnitTypes().getRightClickAction(type);

        if (action == ScrUnitTypeView.RIGHT_CLICK_NONE && isGroundedBuilding(unit) &&
            this.game.requireGameData().getUnitTypes().isRallyProducer(type)) {
            return ScrUnitTypeView.RIGHT_CLICK_MOVE_NO_ATTACK;
        }

        return action;
    }

    /**
     * Tests whether a unit accepts movement orders: not a grounded building, and a right-click class
     * ({@link #getRightClickClass}) that is neither none nor {@code 3}.
     *
     * @param unit the unit
     * @return {@code true} if the unit accepts movement orders, {@code false} otherwise
     */
    public boolean canMove(@NotNull Unit unit) {
        if (isGroundedBuilding(unit)) {
            return false;
        }

        int rightClick = this.getRightClickClass(unit);

        return rightClick != ScrUnitTypeView.RIGHT_CLICK_NONE && rightClick != ScrUnitTypeView.RIGHT_CLICK_ATTACK_NO_MOVE;
    }

    /**
     * Tests whether a unit can attack at all: a ground weapon ({@link UnitTargeting#getGroundWeapon}, none for an unburrowed Lurker) or an air
     * weapon; without an air weapon, Interceptors of a Carrier type, Scarabs inside a Reaver type, or a subunit with a ground or air weapon.
     *
     * @param unit the unit
     * @return {@code true} if the unit can attack at all, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    public boolean canAttack(@NotNull Unit unit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = unit.getUnitId();

        if (this.game.getUnitTargeting().getGroundWeapon(unit) != ScrWeaponIds.NONE || units.getAirWeapon(type) != ScrWeaponIds.NONE) {
            return true;
        }

        if (units.isCarrier(type) && unit.getHangarCount(false) + unit.getHangarCount(true) != 0) {
            return true;
        }

        if (units.isReaver(type) && unit.getHangarCount(false) != 0) {
            return true;
        }

        Unit subunit = unit.getSubunit();

        if (subunit == null) {
            return false;
        }

        return this.game.getUnitTargeting().getGroundWeapon(subunit) != ScrWeaponIds.NONE ||
               units.getAirWeapon(subunit.getUnitId()) != ScrWeaponIds.NONE;
    }

    /**
     * Tests whether a unit is a transport: a real unit whose transport capacity is not {@code 0}. The capacity is the space the type provides
     * after the transport capacity hooks ({@link ScrHooks#getTransportCapacity}); the SC:R hook keeps an Overlord without Ventral Sacs at
     * {@code 0}.
     *
     * @param unit the unit
     * @return {@code true} if the unit is a transport, {@code false} otherwise
     * @throws UnsupportedOperationException if the type needs a transport upgrade and the player upgrade levels are not loaded
     * @throws NullPointerException          if the unit is no hallucination and the world was built without game data
     */
    public boolean isTransport(@NotNull Unit unit) {
        if ((unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
            return false;
        }

        int capacity = this.game.requireGameData().getUnitTypes().getSpaceProvided(unit.getUnitId());

        return this.game.getHooks().getTransportCapacity(unit, capacity) != 0;
    }

    /**
     * Tests whether the space required by the loaded units is below the space provided.
     *
     * @param transport the transport
     * @return {@code true} if the transport has free space, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    public boolean hasFreeSpace(@NotNull Unit transport) {
        int used = this.game.getUnitTransport().getOccupiedSpace(transport);

        return used < this.game.requireGameData().getUnitTypes().getSpaceProvided(transport.getUnitId());
    }

    /**
     * Returns the hangar capacity of a unit: the base capacity of its type ({@link ScrUnitTypeView#getHangarCapacity}), {@code 4} for a Carrier
     * and {@code 5} for a Reaver, after the hangar capacity modifiers, which double it for a hero or with Carrier or Reaver Capacity; {@code 0}
     * for other units.
     *
     * @param unit the unit
     * @return the capacity
     * @throws UnsupportedOperationException if the unit is a Carrier or Reaver that is not a hero and the player upgrade levels are not loaded
     */
    public int getHangarCapacity(@NotNull Unit unit) {
        int type = unit.getUnitId();
        int capacity = this.game.requireGameData().getUnitTypes().getHangarCapacity(type);

        // The hero and upgrade capacities are the hangar capacity modifier of the game.
        return capacity == 0 ? 0 : this.game.applyModifiers(ScrStats.HANGAR_CAPACITY, unit.getOwnerIndex(), type, ContentType.NO_INDEX, capacity);
    }

    /**
     * Returns the powerup held by a worker's carried-powerup relationship.
     *
     * @param unit the unit
     * @return the carried powerup, or {@code null} if the type is not a worker or the relationship is empty
     * @throws NullPointerException if the unit has a {@code units.dat} type and the game was built without game data
     */
    public @Nullable Unit getCarriedPowerup(@NotNull Unit unit) {
        if (!this.hasTypeFlag(unit, UnitTypeFlags.WORKER)) {
            return null;
        }

        return unit.getCarriedPowerup();
    }

    /**
     * Returns the Flag held by a worker's carried-powerup relationship.
     *
     * @param unit the unit
     * @return the carried Flag, or {@code null} if the type is not a worker or the relationship holds no Flag
     */
    public @Nullable Unit getCarriedFlag(@NotNull Unit unit) {
        Unit carriedPowerup = this.getCarriedPowerup(unit);

        return carriedPowerup != null && carriedPowerup.getUnitId() == ScrUnitIds.POWERUP_FLAG ? carriedPowerup : null;
    }

    /**
     * Tests whether a worker's carried-powerup relationship holds a Flag.
     *
     * @param unit the unit
     * @return {@code true} if the worker's carried-powerup relationship holds a Flag, {@code false} otherwise
     */
    public boolean isCarryingFlag(@NotNull Unit unit) {
        return this.getCarriedFlag(unit) != null;
    }

    private boolean hasTypeFlag(@NotNull Unit unit, int flag) {
        int type = unit.getUnitId();
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        return units.hasEntry(type) && (units.getFlags(type) & flag) != 0;
    }
}
