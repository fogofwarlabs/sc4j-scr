package org.fogofwarlabs.sc4j.scr.game.map;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkUnit;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitAvailabilityFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitHallucination;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitHangar;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Applies established preplaced-unit construction and CHK property writes in native order.
 */
public final class PreplacedUnitLoaderUtils {
    /**
     * The random source the unit constructor draws for an invalid type.
     */
    private static final int INVALID_TYPE_RANDOM_SOURCE = 14;
    /**
     * The distance in pixels beyond the map edge from which any signed 16-bit dimension fails the coordinate checks.
     */
    private static final int ALWAYS_OUTSIDE_DISTANCE = 0x8000;
    /**
     * The divisor of the CHK percentages.
     */
    private static final int PERCENT = 100;
    /**
     * The fraction bits of the fixed-point shield and energy values.
     */
    private static final int FIXED_POINT_FRACTION_BITS = 8;
    /**
     * The hit points a hit point percentage that truncates to {@code 0} leaves.
     */
    private static final int MINIMUM_HIT_POINTS = 1;
    /**
     * The mask of one unsigned byte.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of one unsigned WORD.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * The mineral field resources from which the depletion animations of the higher stages run.
     */
    private static final int MINERAL_FULL_RESOURCES = 750;
    private static final int MINERAL_HIGH_RESOURCES = 500;
    private static final int MINERAL_LOW_RESOURCES = 250;
    /**
     * The iscript animations of the mineral field depletion stages, from full to almost depleted.
     */
    private static final int MINERAL_FULL_ANIMATION = IscriptExecutor.ANIMATION_WORKING_TO_IDLE;
    private static final int MINERAL_HIGH_ANIMATION = IscriptExecutor.ANIMATION_ALMOST_BUILT;
    private static final int MINERAL_LOW_ANIMATION = IscriptExecutor.ANIMATION_SPECIAL_STATE_2;
    private static final int MINERAL_DEPLETED_ANIMATION = IscriptExecutor.ANIMATION_SPECIAL_STATE_1;

    /**
     * Creates a {@link Unit} instance through the native preplaced construction path: the expansion gate, construction, creep for creep buildings
     * and completion; a unit without a placement is killed.
     *
     * @param creation         the unit creation inputs
     * @param game             the explicitly initialized native creation context
     * @param unitType         the original type WORD
     * @param positionX        the source horizontal coordinate
     * @param positionY        the source vertical coordinate
     * @param owner            the original owner DWORD
     * @param ownerPlayerType  the decoded player type used by completion and computer registration
     * @param expansionEnabled the game-context expansion BYTE
     * @param instructionLimit the iscript execution bound
     * @return the created and completed {@link Unit} instance, or {@code null} if the expansion gate rejects its type, construction fails or the unit finds no placement
     * @throws UnsupportedOperationException if the type lies past the unit table and the native result is not represented, or the accounting, creep or sprite context is missing
     * @throws NullPointerException          if the world was built without game data
     */
    public static @Nullable Unit create(@NotNull UnitCreationContext creation, @NotNull Game game,
                                        int unitType,
                                        int positionX, int positionY,
                                        int owner, int ownerPlayerType,
                                        boolean expansionEnabled,
                                        int instructionLimit) {
        unitType &= WORD_MASK;

        ScrUnitTypeView unitTypes = game.requireGameData().getUnitTypes();
        int ownerByte = owner & BYTE_MASK;

        if (unitType >= ScrUnitIds.NONE) {
            // An expansion-enabled out-of-table type consumes one random draw before creation fails at out-of-map coordinates.
            UnitPool pool = game.getUnitSystem().getUnitPool();

            if (!expansionEnabled || pool == null) {
                throw Exceptions.newUnsupportedOperationException("Native preplaced creation references unrepresented DAT memory.");
            }

            int mapWidthPixels = (game.getMap().getWidth() << TerrainTileset.TILE_SHIFT) & WORD_MASK;
            int mapHeightPixels = (game.getMap().getHeight() << TerrainTileset.TILE_SHIFT) & WORD_MASK;
            boolean alwaysOutside = positionX - ALWAYS_OUTSIDE_DISTANCE >= mapWidthPixels || positionY - ALWAYS_OUTSIDE_DISTANCE >= mapHeightPixels ||
                                    positionX + (ALWAYS_OUTSIDE_DISTANCE - 1) < 0 || positionY + (ALWAYS_OUTSIDE_DISTANCE - 1) < 0;

            if (!alwaysOutside && pool.getAllocatedCount() != pool.getCapacity()) {
                throw Exceptions.newUnsupportedOperationException("Native preplaced creation references unrepresented DAT memory.");
            }

            game.getRandom().next(INVALID_TYPE_RANDOM_SOURCE);
            game.getUnitStorageConstructor().recordEarlyCreationFailure(pool.getAllocatedCount() == pool.getCapacity());
            game.getUnitStorageConstructor().notifyCreationFailure(ownerByte);

            return null;
        }

        if ((unitTypes.getAvailabilityFlags(unitType) & UnitAvailabilityFlags.EXPANSION_ONLY) != 0 && !expansionEnabled) {
            return null;
        }

        UnitAccounting accounting = game.getPlayerTable().getUnitAccounting();

        if (accounting == null) {
            throw Exceptions.newUnsupportedOperationException("Native preplaced creation requires initialized accounting.");
        }

        Unit unit = game.getUnitStorageConstructor().create(creation,
                                                            accounting,
                                                            unitType,
                                                            positionX, positionY,
                                                            owner,
                                                            ownerByte | ownerByte << Byte.SIZE,
                                                            instructionLimit);

        // The failure notification only displays a message for the local player.
        if (unit == null) {
            game.getUnitStorageConstructor().notifyCreationFailure(ownerByte);

            return null;
        }

        int createdType = unit.getUnitId();

        if (createdType == ScrUnitIds.ZERG_HATCHERY || createdType == ScrUnitIds.ZERG_LAIR ||
            createdType == ScrUnitIds.ZERG_HIVE || createdType == ScrUnitIds.ZERG_CREEP_COLONY ||
            createdType == ScrUnitIds.ZERG_SPORE_COLONY || createdType == ScrUnitIds.ZERG_SUNKEN_COLONY ||
            (unitTypes.getFlags(createdType) & UnitTypeFlags.REQUIRES_CREEP) != 0) {
            CreepField creep = game.getCreepField();
            Sprite sprite = unit.getSpriteOrNull();

            if (creep == null || sprite == null) {
                throw Exceptions.newUnsupportedOperationException("Native preplaced creation requires the original creep and sprite contexts.");
            }

            creep.fill(game, createdType, (short) sprite.getX(), (short) sprite.getY());
        }

        // Without a placement the unit is killed hidden and the record creates nothing; the notifications are displays.
        if (!game.getUnitStorageConstructor().tryComplete(creation, unit, ownerPlayerType, instructionLimit)) {
            game.getUnitStorageConstructor().notifyUnplaceable(unit.getUnitId(), unit);
            unit.setUserActionFlags(unit.getUserActionFlags() | Unit.USER_ACTION_HIDE_ON_DEATH);
            game.getUnitLifecycle().kill(unit, instructionLimit);

            return null;
        }

        // Computer-player units are registered as guards later, by ReplayStartup once the computer phase exists.
        return unit;
    }

    /**
     * Applies the properties of a CHK {@code UNIT} record after successful construction.
     * <p>
     * The element bits select hit points, shields, energy, resources and hangar count; a hangar count only matters for Carriers and Reavers. A state
     * flag applies when both its valid properties bit and its state bit are set, in native order: cloak, burrow and in transit (each gated by a
     * technology or order eligibility check), hallucination ({@link UnitHallucination}) and invincibility. Broodlings finally receive their default
     * removal timer.
     *
     * @param game             the initialized native state
     * @param unit             the completed native unit
     * @param properties       the source CHK record, without interpreting its serial as a handle
     * @param instructionLimit the iscript execution bound
     * @throws IllegalStateException         if a burrow property applies and the unit has no sprite
     * @throws UnsupportedOperationException if a hallucination property finds no creation context
     * @throws NullPointerException          if the world was built without game data
     */
    public static void applyProperties(@NotNull Game game, @NotNull Unit unit, @NotNull ChkUnit properties, int instructionLimit) {
        ScrUnitTypeView unitTypes = game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();
        int validElements = properties.getValidElementsFlags();

        if ((validElements & ChkUnit.ELEMENT_HIT_POINTS) != 0) {
            int maximumHitPoints = unitTypes.getHitPoints(unitType);
            int hitPoints = Integer.divideUnsigned(maximumHitPoints * (properties.getHitPointsPercent() & BYTE_MASK), PERCENT);

            if (hitPoints == 0) {
                hitPoints = MINIMUM_HIT_POINTS;
            }

            game.getUnitHealth().setHitPoints(unit, hitPoints, instructionLimit);
        }

        int maximumShields = unitTypes.getShieldAmount(unitType);
        int shields = Integer.divideUnsigned(maximumShields * (properties.getShieldPointsPercent() & BYTE_MASK), PERCENT);

        if ((validElements & ChkUnit.ELEMENT_SHIELD_POINTS) != 0) {
            unit.setShieldPoints(Math.min(shields, maximumShields) << FIXED_POINT_FRACTION_BITS);
        }

        int maximumEnergy = game.getUnitInitializer().getMaximumEnergy(unit, unitTypes) >> FIXED_POINT_FRACTION_BITS;
        int energy = Integer.divideUnsigned(maximumEnergy * (properties.getEnergyPercent() & BYTE_MASK), PERCENT);

        if ((validElements & ChkUnit.ELEMENT_ENERGY) != 0) {
            unit.setEnergy(Math.min(energy & BYTE_MASK, maximumEnergy) << FIXED_POINT_FRACTION_BITS);
        }

        if ((validElements & ChkUnit.ELEMENT_RESOURCE_AMOUNT) != 0 && (unitTypes.getFlags(unitType) & UnitTypeFlags.RESOURCE_CONTAINER) != 0) {
            setResources(game, unit, (int) properties.getResourceAmount() & WORD_MASK, instructionLimit);
        }

        // The subordinate type is added min(record count, hangar capacity) times.
        int subordinateType = UnitHangar.getSubordinateType(unitTypes, unitType);

        if ((validElements & ChkUnit.ELEMENT_HANGAR_COUNT) != 0 && subordinateType != 0) {
            int count = Math.min(properties.getHangarCount() & BYTE_MASK, game.getUnitQueries().getHangarCapacity(unit));

            for (int index = 0; index < count; index++) {
                game.getUnitHangar().createStoredSubordinate(unit, subordinateType, instructionLimit);
            }
        }

        int validProperties = properties.getValidPropertiesFlags();
        int stateFlags = properties.getStateFlags();

        // Cloak: the cloak technology of the type must be usable; burrow passes the same check.
        int cloakTechnology = UnitQueries.getCloakTechnology(unitTypes, unit);

        if ((validProperties & ChkUnit.STATE_CLOAKED) != 0 && (stateFlags & ChkUnit.STATE_CLOAKED) != 0 && cloakTechnology != ScrTechIds.NONE &&
            game.getUnitTechnology().canUseTechnology(unit, cloakTechnology)) {
            game.getUnitCloaking().cloakPreplaced(unit);
        }

        // Burrow: Burrowing must be usable; the Burrow animation runs, then the unit is burrowed, cloaked
        // (UnitCloaking.cloakPreplaced), made no-collide and given the Burrowed order.
        if ((validProperties & ChkUnit.STATE_BURROWED) != 0 && (stateFlags & ChkUnit.STATE_BURROWED) != 0 &&
            (unitType == ScrUnitIds.ZERG_LURKER || unitType == ScrUnitIds.ZERG_DRONE ||
             unitType == ScrUnitIds.ZERG_INFESTED_TERRAN ||
             unitType == ScrUnitIds.ZERG_DEFILER || unitType == ScrUnitIds.ZERG_ZERGLING || unitType == ScrUnitIds.ZERG_HYDRALISK ||
             unitType == ScrUnitIds.HERO_UNCLEAN_ONE || unitType == ScrUnitIds.HERO_HUNTER_KILLER ||
             unitType == ScrUnitIds.HERO_DEVOURING_ONE) && game.getUnitTechnology().canUseTechnology(unit, ScrTechIds.BURROWING)) {
            game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(),
                                                         unit,
                                                         IscriptExecutor.ANIMATION_SPECIAL_STATE_2,
                                                         IscriptExecutor.FORCE_ANIMATION,
                                                         instructionLimit);
            game.getUnitBurrowing().setBurrowed(unit);
            game.getUnitCloaking().cloakPreplaced(unit);
            unit.setStatus(unit.getStatus() | UnitStatusFlags.NO_COLLIDE);
            game.getOrders().issueUntargeted(unit, ScrOrderIds.BURROWED, instructionLimit);
        }

        // In transit: the unit must be able to receive the BuildingLiftoff order.
        if ((validProperties & ChkUnit.STATE_IN_TRANSIT) != 0 && (stateFlags & ChkUnit.STATE_IN_TRANSIT) != 0 &&
            game.getUnitOrderEligibility().canReceiveOrder(unit, ScrOrderIds.BUILDING_LIFTOFF, unit.getOwnerIndex())) {
            game.getUnitBuildingFlight().enterTransit(unit, instructionLimit);
        }

        if ((validProperties & ChkUnit.STATE_HALLUCINATED) != 0 && (stateFlags & ChkUnit.STATE_HALLUCINATED) != 0) {
            UnitCreationContext context = game.getUnitSystem().getUnitCreationContext();

            if (context == null) {
                throw Exceptions.newUnsupportedOperationException("Native CHK hallucination properties require the established creation context.");
            }

            game.getUnitHallucination().convertToHallucination(unit,
                                                               context.preservesNegativeTypeCounts(),
                                                               context.isCompletionRefreshSuppressed(),
                                                               context.getLocalPlayer(),
                                                               instructionLimit);
        }

        if ((validProperties & ChkUnit.STATE_INVINCIBLE) != 0 && (stateFlags & ChkUnit.STATE_INVINCIBLE) != 0) {
            unit.setStatus(unit.getStatus() | UnitStatusFlags.INVINCIBLE);
        }

        if (unit.getUnitId() == ScrUnitIds.ZERG_BROODLING) {
            UnitHallucination.setRemovalTimer(unit, (unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0
                                                    ? UnitHallucination.HALLUCINATION_REMOVAL_TIMER
                                                    : UnitHallucination.BROODLING_REMOVAL_TIMER);
        }
    }

    /**
     * Sets a resource WORD; mineral fields then select their depletion animation: the full stage from {@code 750}, the next stages from {@code 500}
     * and {@code 250}, otherwise the almost depleted stage.
     *
     * @param game             the initialized native state
     * @param unit             the resource container
     * @param resources        the unsigned resource WORD
     * @param instructionLimit the iscript execution bound
     * @throws UnsupportedOperationException if a mineral field whose animation changes has no sprite
     */
    public static void setResources(@NotNull Game game, @NotNull Unit unit, int resources, int instructionLimit) {
        unit.setResources(resources);

        int unitType = unit.getUnitId();

        if (game.requireGameData().getUnitTypes().isMineralField(unitType)) {
            int animation = resources >= MINERAL_FULL_RESOURCES ? MINERAL_FULL_ANIMATION
                                                                : resources >= MINERAL_HIGH_RESOURCES ? MINERAL_HIGH_ANIMATION
                                                                                                      : resources >= MINERAL_LOW_RESOURCES ? MINERAL_LOW_ANIMATION
                                                                                                                                           : MINERAL_DEPLETED_ANIMATION;

            if (unit.getResourceAnimation() != animation) {
                unit.setResourceAnimation(animation);

                Sprite sprite = unit.getSpriteOrNull();

                if (sprite == null) {
                    throw Exceptions.newUnsupportedOperationException("Native mineral resource writes require its sprite.");
                }

                game.getIscriptExecutor().runSpriteAnimation(sprite, unit, animation, IscriptExecutor.FORCE_ANIMATION, instructionLimit);
            }
        }
    }

    private PreplacedUnitLoaderUtils() {
    }
}
