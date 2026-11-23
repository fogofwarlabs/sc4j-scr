package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegions;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Selects computer-player targets.
 * <p>
 * Each candidate scores its value from the table selected by the caller, adjusted by type, times {@code 128} divided by
 * its hit points and shields; the first highest score wins.
 */
public final class ComputerTargets {
    /**
     * The distance within which a guard and its candidate count as being at the guard post.
     */
    static final int GUARD_RADIUS = 0xC0;

    /**
     * The margin around the attacker's region bounds that the candidate query covers; a candidate outside the attacker's
     * region must lie within this distance of the attacker.
     */
    private static final int REGION_MARGIN = 0x120;
    /**
     * The score bonus of a Wraith against a movable active detector.
     */
    private static final int WRAITH_DETECTOR_BONUS = 1000;
    /**
     * The value of a Ghost that is placing a nuclear strike.
     */
    private static final int NUKING_GHOST_VALUE = 1000;
    /**
     * The value added per Spider Mine of a Vulture.
     */
    private static final int SPIDER_MINE_VALUE = 100;
    /**
     * The value added per occupied space of a transport.
     */
    private static final int LOADED_SPACE_VALUE = 100;
    /**
     * A sieged Siege Tank adds a quarter of its value.
     */
    private static final int SIEGE_MODE_BONUS_SHIFT = 2;
    /**
     * The value added to an Observer.
     */
    private static final int OBSERVER_BONUS = 0x32;
    /**
     * The value is multiplied by {@code 128} before the division by the life.
     */
    private static final int SCORE_SHIFT = 7;
    /**
     * The fraction bits of a fixed-point position.
     */
    private static final int FIXED_POINT_FRACTION_BITS = 8;

    /**
     * @param unit      the unit
     * @param distance  the distance in pixels
     * @param positionX the horizontal pixel position
     * @param positionY the vertical pixel position
     * @return {@code true} if the unit's fixed-point position is within the distance of the position, {@code false} otherwise
     */
    public static boolean isNear(@NotNull Unit unit, int distance, int positionX, int positionY) {
        int length = RegionPathSearch.calculateDistance(unit.getFixedPositionX(), positionX << FIXED_POINT_FRACTION_BITS,
                                                        unit.getFixedPositionY(), positionY << FIXED_POINT_FRACTION_BITS);

        return Integer.compareUnsigned(length >>> FIXED_POINT_FRACTION_BITS, distance) <= 0;
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link ComputerTargets} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public ComputerTargets(@NotNull Game game) {
        this.game = game;
    }

    /**
     * @param unit the unit
     * @return {@code true} if the unit is a completed detector that is neither disabled nor blinded, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    boolean isActiveDetector(@NotNull Unit unit) {
        int type = unit.getUnitId();
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if (!units.hasEntry(type) || (units.getFlags(type) & UnitTypeFlags.DETECTOR) == 0) {
            return false;
        }

        return (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0 && !UnitOrderEligibility.isDisabled(unit) && unit.getBlindnessState() == 0;
    }


    /**
     * Selects the target among the units of one region without an attacking unit.
     *
     * @param phase         the owning computer phase
     * @param owner         the evaluating owner
     * @param region        the region index
     * @param valueSelector {@code false} for the air value table, {@code true} for the ground value table
     * @return the selected target, or {@code null}
     */
    @Nullable Unit selectRegionTarget(@NotNull ComputerPlayerPhase phase, int owner, int region, boolean valueSelector) {
        ComputerTerrain terrain = this.game.getComputerTerrain();
        int count = terrain.collectRegionUnits(region);

        return this.select(phase, owner, null, region, terrain, count, valueSelector);
    }

    /**
     * Selects the target of the region holding a military unit, or for other units the best target near their region.
     *
     * @param phase         the owning computer phase
     * @param unit          the attacking unit with a computer record
     * @param valueSelector {@code false} for the air value table, {@code true} for the ground value table
     * @return the target, or {@code null}
     * @throws IllegalStateException if the unit has no computer record
     * @throws NullPointerException  if the world was built without game data
     */
    @Nullable Unit selectUnitTarget(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, boolean valueSelector) {
        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null) {
            throw Exceptions.newIllegalStateException("Computer unit targets require the unit's record.");
        }

        if (record.getKind() == ComputerUnitRecord.KIND_MILITARY) {
            if (record.unit != unit || UnitQueries.isBuilding(this.game.requireGameData().getUnitTypes(), unit.getUnitId())) {
                return null;
            }

            ComputerRegions regions = phase.getRegions();

            return valueSelector ? regions.getGroundTarget(record.regionIndex) : regions.getAirTarget(record.regionIndex);
        }

        TerrainRegions regions = this.game.getComputerTerrain().getRegions();
        int region = this.game.getComputerTerrain().getUnitRegion(unit);
        ComputerTerrain terrain = this.game.getComputerTerrain();
        int count = terrain.collectRectangleUnits((short) ((short) regions.getLeftBound(region) - REGION_MARGIN),
                                                  (short) ((short) regions.getUpperBound(region) - REGION_MARGIN),
                                                  (short) ((short) regions.getRightBound(region) + REGION_MARGIN),
                                                  (short) ((short) regions.getLowerBound(region) + REGION_MARGIN));

        return this.select(phase, unit.getOwnerIndex(), unit, region, terrain, count, valueSelector);
    }

    /**
     * Selects the best target among the units written by the preceding query.
     *
     * @throws IllegalStateException         if a candidate of a region query lies outside the queried region
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private @Nullable Unit select(@NotNull ComputerPlayerPhase phase,
                                  int owner,
                                  @Nullable Unit attacker,
                                  int referenceRegion,
                                  @NotNull ComputerTerrain terrain,
                                  int count,
                                  boolean valueSelector) {
        int attackerType = attacker == null ? ScrUnitIds.NONE : this.game.requireGameData().getBaseType(attacker);
        ComputerUnitRecord record = attacker == null ? null : attacker.getComputerRecord();
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        Unit best = null;
        int bestScore = -1;

        for (int index = 0; index < count; index++) {
            Unit candidate = this.game.getUnitSystem().requireUnitPool().getUnit(terrain.getRegionUnit(index));
            boolean critter = this.game.getComputerUnits().isCritter(candidate);

            if (!this.game.getComputerUnits().isEnemy(owner, candidate) && !critter) {
                continue;
            }

            int status = candidate.getStatus();

            if ((status & UnitStatusFlags.INVINCIBLE) != 0) {
                continue;
            }

            if ((status & (UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED)) != 0 && (candidate.getDetectionFlags() & 1 << owner) == 0) {
                continue;
            }

            boolean hallucination = (status & UnitStatusFlags.HALLUCINATION) != 0;

            if (hallucination && UnitQueries.getLife(units, candidate) < this.game.getUnitQueries().getMaximumLife(candidate)) {
                continue;
            }

            if (attacker != null && !this.game.getUnitTargeting().canAttackTarget(attacker, candidate)) {
                continue;
            }

            if (critter && phase.getPlayer(owner).isCampaign()) {
                continue;
            }

            if (attacker != null && this.game.getComputerCombat().isClaimedElsewhere(attacker, candidate)) {
                continue;
            }

            if (record == null || record.getKind() != ComputerUnitRecord.KIND_GUARD) {
                if (this.game.getComputerTerrain().getUnitRegion(candidate) != referenceRegion) {
                    if (attacker == null) {
                        throw Exceptions.newIllegalStateException("A region target candidate lies outside the queried region.");
                    }

                    if (Integer.compareUnsigned(this.game.getUnitTargeting().getDistance(this.game.getUnitTargeting().getMainUnit(attacker),
                                                                                         candidate),
                                                REGION_MARGIN) > 0) {
                        continue;
                    }
                }
            } else {
                int guardRegion = this.game.getComputerTerrain().getRegion((short) record.guardX, (short) record.guardY);

                if (this.game.getComputerTerrain().getUnitRegion(candidate) != guardRegion &&
                    (!isNear(candidate, GUARD_RADIUS, record.guardX, record.guardY) ||
                     !isNear(attacker, GUARD_RADIUS, record.guardX, record.guardY))) {
                    continue;
                }
            }

            int score = this.score(phase, candidate, critter, valueSelector);

            if (attackerType == ScrUnitIds.TERRAN_WRAITH && this.isActiveDetector(candidate) && (status & UnitStatusFlags.CAN_MOVE) != 0) {
                score += WRAITH_DETECTOR_BONUS;
            }

            if (bestScore < score) {
                bestScore = score;
                best      = candidate;
            }
        }

        return best;
    }

    private int score(@NotNull ComputerPlayerPhase phase, @NotNull Unit candidate, boolean critter, boolean valueSelector) {
        int type = candidate.getUnitId();

        if (this.game.requireGameData().getUnitTypes().isLarvaOrEgg(type)) {
            return 0;
        }

        if (critter) {
            return 1;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int direction = valueSelector ? UnitStrengthTable.GROUND : UnitStrengthTable.AIR;
        int value = phase.requireValues().getValue(this.game, candidate, direction, units);

        switch (ScrUnitIds.requireValid(this.game.requireGameData().getBaseType(type))) {
            case ScrUnitIds.TERRAN_GHOST -> {
                int order = candidate.getPrimaryOrder();

                if (order >= ScrOrderIds.NUKE_WAIT && order <= ScrOrderIds.NUKE_TRACK) {
                    value = NUKING_GHOST_VALUE;
                }
            }
            case ScrUnitIds.TERRAN_VULTURE -> value += candidate.getSpiderMineCount() * SPIDER_MINE_VALUE;
            case ScrUnitIds.TERRAN_DROPSHIP,
                 ScrUnitIds.ZERG_OVERLORD,
                 ScrUnitIds.HERO_YGGDRASILL,
                 ScrUnitIds.PROTOSS_SHUTTLE -> value += this.game.getUnitTransport().getOccupiedSpace(candidate) * LOADED_SPACE_VALUE;
            case ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE -> value += value >>> SIEGE_MODE_BONUS_SHIFT;
            case ScrUnitIds.TERRAN_MEDIC -> value += valueSelector ? phase.getGroundValue(ScrUnitIds.TERRAN_MARINE)
                                                                   : phase.getAirValue(ScrUnitIds.TERRAN_MARINE);
            case ScrUnitIds.PROTOSS_OBSERVER -> value += OBSERVER_BONUS;
            default -> {
            }
        }

        int life = UnitQueries.getLife(units, candidate);
        long quotient = Integer.toUnsignedLong(value << SCORE_SHIFT) / Integer.toUnsignedLong(life == 0 ? 1 : life);
        int score = (int) quotient;

        if (score == 0) {
            score = ((candidate.getStatus() & UnitStatusFlags.CAN_MOVE) != 0 ? 1 : 0) + 1;
        }

        return score;
    }
}
