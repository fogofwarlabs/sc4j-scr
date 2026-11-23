package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.combat.UnitTargeting;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Applies the computer reaction to a hit: the damage notification runs it for the unit that was hit, and the helper
 * search runs it again for every own unit called to help.
 * <p>
 * The argument {@code direct} marks the unit that was hit itself; {@code nested} marks a helper call, which neither calls
 * for further help nor records the attacker region's strength.
 */
public final class ComputerDefense {
    /**
     * An incomplete building hit below this fraction (one divided by it) of its life is canceled.
     */
    private static final int CANCEL_LIFE_DIVISOR = 4;
    /**
     * The elapsed clock ticks after which a hit marks the attacker's idle region as a town region.
     */
    private static final int ATTACKER_REGION_START_CLOCK_TICKS = 0x3C;

    /**
     * Orders {@code FIRE_YAMATO_GUN + bit} that a unit called to help keeps: Yamato Gun, Lockdown, Dark Swarm, Spawn
     * Broodlings, EMP Shockwave, Psionic Storm, Irradiate, Plague, Ensnare and Stasis Field.
     */
    private static final long KEPT_SPELL_ORDERS = 0x6E0000345L;
    /**
     * The highest bit of {@link #KEPT_SPELL_ORDERS} the test reads.
     */
    private static final int KEPT_SPELL_ORDERS_LAST_OFFSET = 0x22;
    /**
     * A hit Carrier with at most this many Interceptors returns to a town region.
     */
    private static final int CARRIER_RETURN_INTERCEPTORS = 2;
    /**
     * A hit Carrier with at most this many Interceptors retreats to a town region.
     */
    private static final int CARRIER_RETREAT_INTERCEPTORS = 4;
    /**
     * A Wraith, Mutalisk or Arbiter hit by an immobile attacker returns at three quarters of its life.
     */
    private static final int LIGHT_RETURN_LIFE_NUMERATOR = 3;
    private static final int LIGHT_RETURN_LIFE_DENOMINATOR = 4;
    /**
     * A Battlecruiser, Scout or Carrier hit by an immobile attacker returns at two thirds of its life.
     */
    private static final int HEAVY_RETURN_LIFE_NUMERATOR = 2;
    private static final int HEAVY_RETURN_LIFE_DENOMINATOR = 3;

    /**
     * The radius of the first exposure pass.
     */
    private static final int ALERT_RADIUS = 0x280;
    /**
     * The radius of the second exposure pass.
     */
    private static final int WIDE_ALERT_RADIUS = 0x500;
    /**
     * A unit carrying at least this many acid spores exposes itself.
     */
    private static final int ALERT_ACID_SPORES = 4;

    /**
     * The half size of the box searched for helpers around a unit.
     */
    private static final int HELP_RADIUS = 0x120;
    /**
     * The half size of the box searched for helpers around a building.
     */
    private static final int BUILDING_HELP_RADIUS = 0x240;
    /**
     * The half size of the box searched for helpers around a unit of a campaign owner.
     */
    private static final int CAMPAIGN_HELP_RADIUS = 0x60;
    /**
     * The half size of the box searched for helpers around a building of a campaign owner.
     */
    private static final int CAMPAIGN_BUILDING_HELP_RADIUS = 0x120;
    /**
     * A guard helps only when the attacker is within its weapon range plus this distance of its guard position.
     */
    private static final int GUARD_HELP_RANGE_MARGIN = 0xC0;

    /**
     * Orders {@code CAST_EMP_SHOCKWAVE + bit} in which a detector may be called over: EMP Shockwave, Computer AI, AI
     * Patrol, Guard Post and Computer Return.
     */
    private static final long DETECTOR_CALL_ORDERS = 0x26400000001L;
    /**
     * The highest bit of {@link #DETECTOR_CALL_ORDERS} the test reads.
     */
    private static final int DETECTOR_CALL_ORDERS_LAST_OFFSET = 0x29;

    /**
     * The fraction bits of the energy.
     */
    private static final int FIXED_POINT_FRACTION_BITS = 8;


    private final @NotNull Game game;

    /**
     * Creates a {@link ComputerDefense} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public ComputerDefense(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Returns the summed strength of the owner's enemy air or ground units in a region: each unit counts the larger of
     * its air and ground value scaled by its life fraction; eggs, Larvae and damaged hallucinations count zero.
     *
     * @param phase  the computer-player phase
     * @param owner  the evaluating owner
     * @param region the region
     * @param air    {@code true} to sum flying units, {@code false} to sum ground units
     * @return the summed strength
     * @throws IllegalStateException         if the value tables are read before the scripts are initialized
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    int getRegionStrength(@NotNull ComputerPlayerPhase phase, int owner, int region, boolean air) {
        ComputerTerrain query = this.game.getComputerTerrain();
        int found = query.collectRegionUnits(region);
        int total = 0;

        for (int index = 0; index < found; index++) {
            Unit unit = this.game.getUnitSystem().requireUnitPool().getUnit(query.getRegionUnit(index));

            if (((unit.getStatus() & UnitStatusFlags.FLYING) != 0) != air || !this.game.getComputerUnits().isEnemy(owner, unit)) {
                continue;
            }

            UnitStrengthTable values = phase.requireValues();
            ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
            int airStrength = values.calculate(this.game, unit, UnitStrengthTable.AIR, units);
            int groundStrength = values.calculate(this.game, unit, UnitStrengthTable.GROUND, units);

            total += Math.max(airStrength, groundStrength);
        }

        return total;
    }


    /**
     * Applies the computer reaction to a hit of a computer-owned unit.
     * <p>
     * A hit building with a record stores the owner's building hit clock and marks its town; an incomplete building below
     * a quarter of its life hit directly by a non-worker is canceled, which ends the reaction. An Interceptor attacker is
     * resolved to its Carrier, a loaded attacker to its Bunker, and an attacker cloaked by an Arbiter to the nearest enemy
     * Arbiter. A unit hit directly cloaks when it can, a Lurker burrows, and a transport unloads or returns. Then the unit
     * calls for help, the regions escalate, the attacker region's strength is recorded and the unit responds.
     *
     * @param phase  the computer-player phase
     * @param unit   the unit that was hit or is called to help
     * @param source the attacking unit, or {@code null}
     * @param direct {@code true} for the unit that was hit itself, {@code false} for a unit called to help
     * @param nested {@code true} for a helper call, which neither calls for further help nor records the attacker region's strength, {@code false} otherwise
     * @throws UnsupportedOperationException if a hit building has a non-building record or no town
     * @throws NullPointerException          if the world was built without game data
     */
    public void onHit(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, @Nullable Unit source, boolean direct, boolean nested) {
        int owner = unit.getOwnerIndex();

        if (owner >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return;
        }

        Player player = this.game.getPlayerTable().getPlayer(owner);

        if (player == null || player.getType() != PlayerTypes.COMPUTER) {
            return;
        }

        int limit = phase.getInstructionLimit();
        ComputerPlayer computer = phase.getPlayer(owner);
        ComputerUnitRecord record = unit.getComputerRecord();

        if (UnitQueries.isBuilding(this.game.requireGameData().getUnitTypes(), unit.getUnitId()) && record != null) {
            computer.setLastBuildingHitClock(phase.getElapsedClockTicks());

            if (record.getKind() != ComputerUnitRecord.KIND_BUILDING || record.town == null) {
                throw Exceptions.newUnsupportedOperationException("A hit building lacks a building record with a town: kind {0}.", record.getKind());
            }

            record.town.requestDelay = 1;

            ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

            if (direct && source != null && !this.game.getComputerUnits().isWorker(source) && !ComputerUnits.isDeadOrHidden(unit) &&
                (unit.getStatus() & UnitStatusFlags.COMPLETED) == 0 &&
                UnitQueries.getLife(units, unit) < this.game.getUnitQueries().getMaximumLife(unit) / CANCEL_LIFE_DIVISOR) {
                this.game.getUnitConstruction().cancelBuildingUnit(unit, limit);

                return;
            }
        }

        Unit attacker = this.resolveAttacker(unit, source);

        if (attacker == null) {
            return;
        }

        if ((attacker.getStatus() & UnitStatusFlags.IN_BUNKER) != 0) {
            attacker = attacker.getParentUnit();

            if (attacker == null) {
                return;
            }
        }

        if ((attacker.getStatus() & UnitStatusFlags.PASSIVELY_CLOAKED) != 0 && (attacker.getStatus() & UnitStatusFlags.BURROWED) == 0) {
            attacker = this.game.getComputerResources().findNearestEnemyOfType(unit, ScrUnitIds.PROTOSS_ARBITER);

            if (attacker == null) {
                return;
            }
        }

        if (direct) {
            if ((unit.getStatus() & UnitStatusFlags.REQUIRES_DETECTOR) == 0) {
                int technology = UnitQueries.getCloakTechnology(this.game.requireGameData().getUnitTypes(), unit);

                if (technology != ScrTechIds.NONE && this.game.getUnitTechnology().canUseTechnology(unit, technology)) {
                    this.game.getUnitTechnology().cloakUnit(unit);
                }
            }

            if (this.game.requireGameData().getBaseType(unit) == ScrUnitIds.ZERG_LURKER && (unit.getStatus() & UnitStatusFlags.BURROWED) == 0 &&
                unit.getPrimaryOrder() != ScrOrderIds.BURROWING) {
                phase.issueOrder(unit, ScrOrderIds.BURROWING);
            }
        }

        ComputerRegions regions = phase.getRegions();
        boolean attacking = false;
        boolean skipRegions = false;

        if (record != null && record.getKind() == ComputerUnitRecord.KIND_MILITARY) {
            int regionState = regions.getState(record.regionIndex);

            if (regionState == ComputerRegionStates.ATTACK && record.unit == unit &&
                !UnitQueries.isBuilding(this.game.requireGameData().getUnitTypes(), unit.getUnitId())) {
                regions.setState(phase, record.regionIndex, ComputerRegionStates.ATTACK_ENGAGED);
            }

            ComputerUnitRecord current = unit.getComputerRecord();

            if (current != null && current.getKind() == ComputerUnitRecord.KIND_MILITARY) {
                attacking = ComputerRegionStates.isAttackState(regions.getState(current.regionIndex));
            }
        }

        if (direct && this.game.getUnitQueries().isTransport(unit) && (unit.getStatus() & UnitStatusFlags.CAN_MOVE) != 0) {
            if (this.game.getUnitTransport().hasLoadedUnits(unit)) {
                if (unit.getPrimaryOrder() != ScrOrderIds.UNLOAD) {
                    phase.issueOrder(unit, ScrOrderIds.UNLOAD);
                }

                return;
            }

            if (!this.game.getComputerCombat().returnToGuardPost(unit, limit) && this.game.getComputerOrders().moveToTownRegion(phase, unit, limit)) {
                return;
            }

            if (computer.isCampaign()) {
                skipRegions = true;
            }
        }

        int unitIndex = regions.getIndex(owner, this.game.getComputerTerrain().getUnitRegion(unit));

        if (!skipRegions) {
            if (!nested) {
                this.callHelp(phase, unit, attacker, attacking);
            }

            if (!attacking) {
                ComputerUnitRecord current = unit.getComputerRecord();
                boolean escalate = false;

                if (current != null) {
                    int kind = current.getKind();
                    boolean militaryAttacking = false;

                    if (kind == ComputerUnitRecord.KIND_MILITARY) {
                        militaryAttacking = ComputerRegionStates.isAttackState(regions.getState(current.regionIndex));
                    }

                    if (!militaryAttacking) {
                        escalate = !(kind == ComputerUnitRecord.KIND_GUARD && computer.isCampaign());
                    }
                }

                int attackerIndex = regions.getIndex(owner, this.game.getComputerTerrain().getUnitRegion(attacker));

                if (escalate) {
                    int unitState = regions.getState(unitIndex);

                    if (unitState == ComputerRegionStates.TOWN || unitState == ComputerRegionStates.ALLY_TOWN) {
                        regions.setState(phase, unitIndex, ComputerRegionStates.UNDER_ATTACK);
                    }

                    if (regions.getState(unitIndex) != ComputerRegionStates.IDLE && regions.getState(attackerIndex) == ComputerRegionStates.IDLE &&
                        Integer.compareUnsigned(phase.getElapsedClockTicks(), ATTACKER_REGION_START_CLOCK_TICKS) > 0) {
                        regions.setState(phase, attackerIndex, ComputerRegionStates.TOWN);
                    }
                }

                if (!nested) {
                    int attackerRegion = regions.getRegion(attackerIndex);

                    regions.setEnemyAirStrength(attackerIndex, this.getRegionStrength(phase, owner, attackerRegion, true));
                    regions.setEnemyGroundStrength(attackerIndex, this.getRegionStrength(phase, owner, attackerRegion, false));

                    if (regions.getRequiredAirStrength(attackerIndex) > regions.getRequiredAirStrength(unitIndex)) {
                        regions.setRequiredAirStrength(unitIndex, regions.getRequiredAirStrength(attackerIndex));
                    }

                    if (regions.getRequiredGroundStrength(attackerIndex) > regions.getRequiredGroundStrength(unitIndex)) {
                        regions.setRequiredGroundStrength(unitIndex, regions.getRequiredGroundStrength(attackerIndex));
                    }
                }
            }
        }

        regions.setFlags(unitIndex, regions.getFlags(unitIndex) | ComputerRegionStates.FLAG_HIT);
        this.respond(phase, unit, attacker, direct, limit);
    }

    /**
     * Applies the completed unit's own reaction, the tail of the hit reaction: a spell, a return to a town region, a
     * retreat, a detection request or an attack on the attacker.
     *
     * @throws IllegalStateException if the attacker or the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    private void respond(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, @NotNull Unit attacker, boolean direct, int limit) {
        if ((unit.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
            return;
        }

        int order = unit.getPrimaryOrder();

        if (order == ScrOrderIds.COMPLETING_ARCHON_SUMMON || order == ScrOrderIds.RESET_COLLISION ||
            order == ScrOrderIds.CONSTRUCTING_BUILDING) {
            return;
        }

        if (!direct) {
            int offset = order - ScrOrderIds.FIRE_YAMATO_GUN;

            if (offset >= 0 && offset <= KEPT_SPELL_ORDERS_LAST_OFFSET && (KEPT_SPELL_ORDERS >>> offset & 1) != 0) {
                return;
            }
        }

        int type = this.game.requireGameData().getBaseType(unit);

        if (type == ScrUnitIds.TERRAN_MARINE && order == ScrOrderIds.ENTER_TRANSPORT) {
            return;
        }

        // A unit whose order may not be interrupted skips the spell attempt and the attack below.
        boolean busy = (unit.getStatus() & (UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE | UnitStatusFlags.ISCRIPT_NO_BREAK)) != 0;

        if (!busy && ComputerSpells.cast(phase, this.game, unit, direct)) {
            return;
        }

        if (order == ScrOrderIds.RECHARGE_SHIELDS_UNIT || order == ScrOrderIds.MOVE) {
            return;
        }

        ComputerPlayer computer = phase.getPlayer(unit.getOwnerIndex());

        if (direct) {
            if (type == ScrUnitIds.PROTOSS_CARRIER && unit.getHangarCount(false) + unit.getHangarCount(true) <= CARRIER_RETURN_INTERCEPTORS &&
                this.game.getComputerOrders().moveToTownRegion(phase, unit, limit)) {
                return;
            }

            if ((attacker.getStatus() & UnitStatusFlags.CAN_MOVE) == 0 && !computer.isCampaign()) {
                ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
                int life = UnitQueries.getLife(units, unit);
                int maximum = this.game.getUnitQueries().getMaximumLife(unit);

                if (life <= maximum * LIGHT_RETURN_LIFE_NUMERATOR / LIGHT_RETURN_LIFE_DENOMINATOR &&
                    (type == ScrUnitIds.TERRAN_WRAITH || type == ScrUnitIds.ZERG_MUTALISK || type == ScrUnitIds.PROTOSS_ARBITER) &&
                    this.game.getComputerOrders().moveToTownRegion(phase, unit, limit)) {
                    return;
                }

                if (life <= maximum * HEAVY_RETURN_LIFE_NUMERATOR / HEAVY_RETURN_LIFE_DENOMINATOR &&
                    (type == ScrUnitIds.TERRAN_BATTLECRUISER || type == ScrUnitIds.PROTOSS_SCOUT ||
                     type == ScrUnitIds.PROTOSS_CARRIER) &&
                    this.game.getComputerOrders().moveToTownRegion(phase, unit, limit)) {
                    return;
                }
            }

            if (type == ScrUnitIds.ZERG_LURKER && (unit.getStatus() & UnitStatusFlags.BURROWED) == 0 && order != ScrOrderIds.BURROWING &&
                order != ScrOrderIds.UNBURROWING && this.retreat(phase, unit, attacker, limit)) {
                return;
            }

            if (!busy && (unit.getStatus() & UnitStatusFlags.CAN_MOVE) != 0) {
                Sprite sprite = attacker.requireSprite();

                if (!this.game.getUnitPositioning().isGroundReachable(unit, (short) sprite.getX(), (short) sprite.getY()) &&
                    this.retreat(phase, unit, attacker, limit)) {
                    return;
                }
            }
        }

        if (unit.getOrderTargetUnit() == attacker) {
            return;
        }

        if (this.game.getComputerUnits().isWorker(unit) && !this.game.getComputerUnits().isWorker(attacker) &&
            this.game.requireGameData().getBaseType(attacker) != ScrUnitIds.ZERG_ZERGLING) {
            // A directly hit worker retreats from a non-worker other than a Zergling.
            if (direct) {
                this.retreat(phase, unit, attacker, limit);
            }

            return;
        }

        if (direct && unit.getComputerRecord() != null && UnitTargeting.isUndetected(unit, attacker)) {
            this.requestDetection(phase, unit, attacker, limit);
        }

        if (busy) {
            return;
        }

        if (this.game.getUnitTargeting().canAttackTarget(unit, attacker)) {
            unit.setAutomaticTargetUnit(attacker);

            Unit turret = unit.getSubunit();

            if (turret != null && (this.game.requireGameData().getUnitTypes().getFlags(turret.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
                turret.setAutomaticTargetUnit(attacker);
            }

            this.game.getComputerMilitary().acquireTarget(unit);

            return;
        }

        if (unit.getComputerRecord() == null) {
            return;
        }

        if (order == ScrOrderIds.AI_PATROL) {
            int[] position = this.game.getUnitDamage().computeFleePosition(unit, attacker);
            Sprite sprite = unit.requireSprite();

            if (position[0] != (short) sprite.getX() || position[1] != (short) sprite.getY()) {
                this.game.getOrders().issue(unit, ScrOrderIds.MOVE, null, position[0], position[1], ScrUnitIds.NONE, false, limit);

                return;
            }
        }

        if (!direct || type == ScrUnitIds.TERRAN_MEDIC) {
            return;
        }

        // Keep the current order only when its target can pass an attack test against itself.
        Unit target = unit.getOrderTargetUnit();

        if (target == null || !this.game.getUnitTargeting().canAttackTarget(target, target)) {
            this.retreat(phase, unit, attacker, limit);
        }
    }

    /**
     * Exposes a disabled unit, or an immobile unit that cannot attack, to the computer units around it. The order
     * dispatcher runs it for such a unit (a disabled unit then does nothing else); each time the main order timer
     * expires (every {@code 15} frames) a locked-down, maelstrommed or unarmed unit runs the first pass of {@link #alertAround}.
     *
     * @param unit the exposed unit
     */
    public void alertDisabled(@NotNull Unit unit) {
        if (!ComputerOrders.consumeTimer(unit)) {
            return;
        }

        if (unit.getLockdownTimer() != 0 || unit.getMaelstromTimer() != 0 || (unit.getStatus() & UnitStatusFlags.CANNOT_ATTACK) != 0) {
            this.alertPass(unit, ALERT_RADIUS, false);
        }
    }

    /**
     * Exposes a unit when its order timer restarts: a unit carrying four or more acid spores exposes itself to the
     * computer units around it; otherwise the first Interceptor processed in the frame (the flag is cleared at the start
     * of the unit phase) exposes its Carrier.
     * <p>
     * The Interceptor alert flag is set only after both Carrier passes return.
     *
     * @param unit the unit whose order timer restarted
     */
    public void alertOnOrderTimer(@NotNull Unit unit) {
        if (unit.getAcidSporeCount() >= ALERT_ACID_SPORES) {
            this.alertAround(unit);

            return;
        }

        ComputerPlayerPhase phase = this.game.getComputerPlayerPhase();

        if (phase == null || phase.isInterceptorAlerted() || this.game.requireGameData().getBaseType(unit) != ScrUnitIds.PROTOSS_INTERCEPTOR) {
            return;
        }

        Unit carrier = unit.getHangarParent();

        if (carrier == null) {
            return;
        }

        this.alertAround(carrier);
        phase.setInterceptorAlerted();
    }

    /**
     * Runs both exposure passes around the unit, first within {@code 640} pixels and then within {@code 1280} pixels.
     */
    private void alertAround(@NotNull Unit unit) {
        this.alertPass(unit, ALERT_RADIUS, false);
        this.alertPass(unit, WIDE_ALERT_RADIUS, true);
    }

    /**
     * Runs one exposure pass: a computer unit (with a record) in the box around the exposed unit that is its enemy, has
     * no automatic target, can attack it and can move or already has it in range reacts as a helper. A ground candidate
     * only reacts in the first pass and not to an unarmed unit.
     *
     * @throws IllegalStateException if the exposed unit has no sprite
     */
    private void alertPass(@NotNull Unit unit, int radius, boolean secondPass) {
        ComputerPlayerPhase phase = this.game.getComputerPlayerPhase();

        if (phase == null) {
            return;
        }

        Sprite sprite = unit.requireSprite();
        int centerX = (short) sprite.getX();
        int centerY = (short) sprite.getY();
        ComputerTerrain query = this.game.getComputerTerrain();
        Unit[] candidates = query.collectRectangle(centerX - radius, centerY - radius, centerX + radius, centerY + radius);

        for (Unit candidate : candidates) {
            if (candidate.getComputerRecord() == null) {
                continue;
            }

            if ((candidate.getStatus() & UnitStatusFlags.FLYING) == 0 && (secondPass || (unit.getStatus() & UnitStatusFlags.CANNOT_ATTACK) != 0)) {
                continue;
            }

            if (!this.game.getComputerUnits().isEnemy(candidate.getOwnerIndex(), unit) || candidate.getAutomaticTargetUnit() != null ||
                !this.game.getUnitTargeting().canAttackTarget(candidate, unit)) {
                continue;
            }

            if ((candidate.getStatus() & UnitStatusFlags.CAN_MOVE) == 0 && !this.game.getUnitTargeting().isInWeaponMovementRange(candidate, unit)) {
                continue;
            }

            this.onHit(phase, candidate, unit, false, true);
        }
    }

    /**
     * Calls every other own unit in the box of {@code 288} pixels around the unit ({@code 576} around a building;
     * {@code 96} and {@code 288} for campaign owners) that can fight the attacker, or be attacked by it while not burrowed,
     * to help. A worker helps only against a worker; an Arbiter never helps. With {@code attacking} only military units of
     * attacking regions help; otherwise a guard helps only when the attacker is within its weapon range plus {@code 192}
     * pixels of its guard position.
     *
     * @throws IllegalStateException if the unit or the attacker has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    private void callHelp(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, @NotNull Unit attacker, boolean attacking) {
        boolean campaign = phase.getPlayer(unit.getOwnerIndex()).isCampaign();
        int radius = UnitQueries.isBuilding(this.game.requireGameData().getUnitTypes(), unit.getUnitId())
                     ? campaign ? CAMPAIGN_BUILDING_HELP_RADIUS : BUILDING_HELP_RADIUS
                     : campaign ? CAMPAIGN_HELP_RADIUS : HELP_RADIUS;
        Sprite sprite = unit.requireSprite();
        int centerX = (short) sprite.getX();
        int centerY = (short) sprite.getY();
        ComputerTerrain query = this.game.getComputerTerrain();
        Unit[] helpers = query.collectRectangle(centerX - radius, centerY - radius, centerX + radius, centerY + radius);

        ComputerRegions regions = phase.getRegions();

        for (Unit helper : helpers) {
            if (helper == unit || helper.getOwnerIndex() != unit.getOwnerIndex()) {
                continue;
            }

            if (this.game.getComputerUnits().isWorker(helper) && !this.game.getComputerUnits().isWorker(attacker)) {
                continue;
            }

            if (this.game.requireGameData().getBaseType(helper) == ScrUnitIds.PROTOSS_ARBITER) {
                continue;
            }

            if (!this.game.getUnitTargeting().canAttackTarget(helper, attacker) &&
                (!this.game.getUnitTargeting().canAttackTarget(attacker, helper) || (helper.getStatus() & UnitStatusFlags.BURROWED) != 0)) {
                continue;
            }

            ComputerUnitRecord record = helper.getComputerRecord();

            if (attacking) {
                if (record == null || record.getKind() != ComputerUnitRecord.KIND_MILITARY) {
                    continue;
                }

                if (!ComputerRegionStates.isAttackState(regions.getState(record.regionIndex))) {
                    continue;
                }

                // An attacking helper reacts here and again at the common call below.
                this.onHit(phase, helper, attacker, false, true);

                record = helper.getComputerRecord();
            }

            if (record != null && record.getKind() == ComputerUnitRecord.KIND_GUARD) {
                Sprite attackerSprite = attacker.requireSprite();
                int distance = RegionPathSearch.calculateDistance((short) attackerSprite.getX(), record.guardX,
                                                                  (short) attackerSprite.getY(), record.guardY);
                Unit shooter = helper.getSubunit();

                if (shooter == null || (this.game.requireGameData().getUnitTypes().getFlags(shooter.getUnitId()) & UnitTypeFlags.SUBUNIT) == 0) {
                    shooter = helper;
                }

                ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
                int weapon = (helper.getStatus() & UnitStatusFlags.FLYING) != 0
                             ? units.getAirWeapon(shooter.getUnitId())
                             : this.game.getUnitTargeting().getGroundWeapon(shooter);

                if (weapon == ScrWeaponIds.NONE) {
                    continue;
                }

                if (Integer.compareUnsigned(distance,
                                            this.game.getUnitTargeting().getWeaponMaximumRange(shooter, weapon) + GUARD_HELP_RANGE_MARGIN) > 0) {
                    continue;
                }
            }

            this.onHit(phase, helper, attacker, false, true);
        }
    }

    /**
     * Retreats a movable unit (not unburrowing, not a Larva, not an irradiated Lurker): spellcasters and Overlords (and
     * Carriers with at most four Interceptors) move to a town region, others move to their flee position.
     *
     * @return {@code true} if an order was issued, {@code false} otherwise
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    private boolean retreat(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, @NotNull Unit attacker, int limit) {
        if ((unit.getStatus() & UnitStatusFlags.CAN_MOVE) == 0 || unit.getPrimaryOrder() == ScrOrderIds.UNBURROWING ||
            this.game.requireGameData().getBaseType(unit) == ScrUnitIds.ZERG_LARVA) {
            return false;
        }

        if (this.game.requireGameData().getBaseType(unit) == ScrUnitIds.ZERG_LURKER && unit.getIrradiateTimer() != 0) {
            return false;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        boolean spellcaster = UnitTypeTraitsUtils.isSpellcaster(units, unit);

        if ((spellcaster || this.game.requireGameData().getBaseType(unit) == ScrUnitIds.ZERG_OVERLORD) &&
            this.game.getComputerOrders().moveToTownRegion(phase, unit, limit)) {
            return true;
        }

        if (this.game.requireGameData().getBaseType(unit) == ScrUnitIds.PROTOSS_CARRIER &&
            unit.getHangarCount(false) + unit.getHangarCount(true) <= CARRIER_RETREAT_INTERCEPTORS &&
            this.game.getComputerOrders().moveToTownRegion(phase, unit, limit)) {
            return true;
        }

        int[] position = this.game.getUnitDamage().computeFleePosition(unit, attacker);
        Sprite sprite = unit.requireSprite();

        if (position[0] == (short) sprite.getX() && position[1] == (short) sprite.getY()) {
            return false;
        }

        this.game.getOrders().issue(unit, ScrOrderIds.MOVE, null, position[0], position[1], ScrUnitIds.NONE, false, limit);

        return true;
    }

    /**
     * Returns the attacker to react to: an Interceptor resolves to its Carrier when the Carrier is in reach or the unit
     * can move.
     */
    private @Nullable Unit resolveAttacker(@NotNull Unit unit, @Nullable Unit source) {
        if (source == null) {
            return null;
        }

        if (this.game.requireGameData().getBaseType(source) == ScrUnitIds.PROTOSS_INTERCEPTOR && source.getHangarParent() != null) {
            Unit carrier = source.getHangarParent();

            if (this.game.getUnitTargeting().isInWeaponMovementRange(unit, carrier) || (unit.getStatus() & UnitStatusFlags.CAN_MOVE) != 0) {
                return carrier;
            }
        }

        return source;
    }

    /**
     * Requests detection for a unit with a computer record that cannot see its attacker. The owner is flagged for a
     * patrol assignment. The patrol unit of the unit's region record (its military region, or the region of a guard's
     * home position) comes over in the AI Patrol order. Otherwise the owner's units are searched: a ready Comsat Station
     * scans the attacker at once; the nearest idle Science Vessel, Observer or empty Overlord comes over in the Computer
     * Return order; and the nearest Queen that can cast Ensnare ensnares the attacker's position.
     *
     * @param phase    the computer-player phase
     * @param unit     the attacked unit
     * @param attacker the undetected attacker
     * @param limit    the iscript budget for issued orders
     * @throws IllegalStateException         if the unit, the attacker or a candidate detector or Queen has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    void requestDetection(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, @NotNull Unit attacker, int limit) {
        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null) {
            return;
        }

        int owner = unit.getOwnerIndex();
        ComputerPlayer player = phase.getPlayer(owner);

        player.setFlags(player.getFlags() | ComputerPlayer.FLAG_PATROL_ASSIGNMENT);

        ComputerRegions regions = phase.getRegions();
        int regionIndex = -1;

        if (record.getKind() == ComputerUnitRecord.KIND_MILITARY) {
            regionIndex = record.regionIndex;
        } else if (record.getKind() == ComputerUnitRecord.KIND_GUARD) {
            regionIndex = regions.getIndex(owner, this.game.getComputerTerrain().getRegion((short) record.homeX, (short) record.homeY));
        }

        if (regionIndex >= 0) {
            Unit patrol = regions.getPatrolUnit(regionIndex);

            if (patrol != null) {
                Sprite sprite = unit.requireSprite();

                this.game.getOrders().issue(patrol, ScrOrderIds.AI_PATROL, unit, sprite.getX(), sprite.getY(), ScrUnitIds.NONE, false, limit);

                return;
            }
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        Sprite unitSprite = unit.requireSprite();
        Sprite attackerSprite = attacker.requireSprite();
        Unit detector = null;
        int detectorDistance = ComputerPlayerPhase.NO_CANDIDATE_DISTANCE;
        Unit queen = null;
        int queenDistance = ComputerPlayerPhase.NO_CANDIDATE_DISTANCE;
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int index = pool.getFirstOwnerIndex(owner); index != 0; index = pool.getNextOwnerIndex(index)) {
            Unit candidate = pool.getUnit(index);

            if (ComputerUnits.isDeadOrHidden(candidate)) {
                continue;
            }

            int type = this.game.requireGameData().getBaseType(candidate);
            boolean detectorCandidate;

            if (type == ScrUnitIds.TERRAN_SCIENCE_VESSEL || type == ScrUnitIds.PROTOSS_OBSERVER) {
                detectorCandidate = true;
            } else if (type == ScrUnitIds.ZERG_OVERLORD) {
                detectorCandidate = !this.game.getUnitTransport().hasLoadedUnits(candidate);
            } else if (type == ScrUnitIds.ZERG_QUEEN) {
                if (!this.game.getUnitTargeting().canAttackTarget(unit, attacker) ||
                    this.game.requireGameData().getBaseType(attacker) == ScrUnitIds.ZERG_LURKER) {
                    continue;
                }

                if (!this.game.getUnitEnergy().hasEnergy(candidate, ComputerSpells.getOrderEnergyCost(this.game, ScrOrderIds.CAST_ENSNARE))) {
                    continue;
                }

                if (!phase.canUseTechnology(candidate, ScrTechIds.ENSNARE)) {
                    continue;
                }

                Sprite sprite = candidate.requireSprite();
                int distance = RegionPathSearch.calculateDistance(unitSprite.getX(), sprite.getX(), unitSprite.getY(), sprite.getY());

                if (Integer.compareUnsigned(distance, queenDistance) < 0) {
                    queen         = candidate;
                    queenDistance = distance;
                }

                continue;
            } else if (type == ScrUnitIds.TERRAN_COMSAT_STATION) {
                if ((candidate.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
                    continue;
                }

                boolean occupiedBunker = this.game.requireGameData().getBaseType(unit) == ScrUnitIds.TERRAN_BUNKER &&
                                         this.game.getUnitTransport().hasLoadedUnits(unit);

                if (!occupiedBunker && (!this.game.getUnitQueries().canAttack(unit) ||
                                        units.hasEntry(unit.getUnitId()) && (units.getFlags(unit.getUnitId()) & UnitTypeFlags.WORKER) != 0)) {
                    continue;
                }

                int cost = this.game.requireGameData().getTechTypes().getEnergyCost(ScrTechIds.SCANNER_SWEEP) << FIXED_POINT_FRACTION_BITS;

                if (!this.game.getUnitEnergy().hasEnergy(candidate, cost) || candidate.getPrimaryOrder() == ScrOrderIds.CAST_SCANNER_SWEEP) {
                    continue;
                }

                this.game.getOrders().issue(candidate,
                                            ScrOrderIds.CAST_SCANNER_SWEEP,
                                            attacker,
                                            attackerSprite.getX(), attackerSprite.getY(),
                                            ScrUnitIds.NONE,
                                            false,
                                            limit);

                return;
            } else {
                continue;
            }

            if (!detectorCandidate || candidate.getBlindnessState() != 0) {
                continue;
            }

            int order = candidate.getPrimaryOrder() - ScrOrderIds.CAST_EMP_SHOCKWAVE;

            if (order < 0 || order > DETECTOR_CALL_ORDERS_LAST_OFFSET || (DETECTOR_CALL_ORDERS >>> order & 1) == 0) {
                continue;
            }

            Sprite sprite = candidate.requireSprite();
            int distance = RegionPathSearch.calculateDistance(unitSprite.getX(), sprite.getX(), unitSprite.getY(), sprite.getY());

            if (Integer.compareUnsigned(distance, detectorDistance) < 0) {
                detector         = candidate;
                detectorDistance = distance;
            }
        }

        if (detector != null) {
            this.game.getOrders().issue(detector,
                                        ScrOrderIds.COMPUTER_RETURN,
                                        null,
                                        unitSprite.getX(), unitSprite.getY(),
                                        ScrUnitIds.NONE,
                                        false,
                                        limit);
        }

        if (queen != null) {
            this.game.getOrders().issue(queen,
                                        ScrOrderIds.CAST_ENSNARE,
                                        null,
                                        attackerSprite.getX(), attackerSprite.getY(),
                                        ScrUnitIds.NONE,
                                        false,
                                        limit);
        }
    }
}
