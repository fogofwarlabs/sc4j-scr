package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.combat.UnitTargeting;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.order.OrderPool;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerRelations;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.production.CostTests;
import org.fogofwarlabs.sc4j.scr.game.production.ScrRequirementSystem;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegions;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiPredicate;

/**
 * Executes the computer-controlled primary orders ComputerAI and AIPatrol and the computer behavior of the Neutral and
 * JunkYardDog orders.
 * <p>
 * The handlers run from the delayed order branch and use the main order timer BYTE ({@link Unit#getOrderTimer()}), the
 * low byte of the cooldown timers.
 */
public final class ComputerOrders {
    /**
     * The arrival result of a unit away from its movement destination.
     */
    public static final int ARRIVAL_AWAY = 0;
    /**
     * The arrival result of a unit at its movement destination.
     */
    public static final int ARRIVAL_REACHED = 1;
    /**
     * The arrival result of a unit at its movement destination that cannot move on.
     */
    public static final int ARRIVAL_BLOCKED = 2;

    /**
     * The gas building class of an unused Vespene Geyser.
     */
    static final int GAS_GEYSER = 1;
    /**
     * The gas building class of a depleted refinery.
     */
    static final int GAS_DEPLETED = 2;
    /**
     * The gas building class of a refinery with gas left.
     */
    static final int GAS_ACTIVE = 3;

    /**
     * The gas building class of anything else.
     */
    private static final int GAS_NONE = 0;
    /**
     * The ordinals of the second and the third gas building of a class.
     */
    private static final int SECOND_GAS_BUILDING = 2;
    private static final int THIRD_GAS_BUILDING = 3;
    /**
     * The gas buildings a gas worker falls back to, as class and ordinal, after the active building of its ordinal.
     */
    private static final int @NotNull [] @NotNull [] GAS_FALLBACKS = {{GAS_ACTIVE, 1}, {GAS_ACTIVE, SECOND_GAS_BUILDING},
                                                                      {GAS_ACTIVE, THIRD_GAS_BUILDING}, {GAS_DEPLETED, 1},
                                                                      {GAS_DEPLETED, SECOND_GAS_BUILDING},
                                                                      {GAS_DEPLETED, THIRD_GAS_BUILDING}};
    /**
     * A town with at most this many workers searches its minerals the short way.
     */
    private static final int FEW_TOWN_WORKERS = 12;
    /**
     * A worker that was reassigned this many times after idling does not construct.
     */
    private static final int CONSTRUCTION_RETRY_LIMIT = 3;

    /**
     * The order timer reloaded by an expired timer.
     */
    private static final int ORDER_TIMER_RELOAD = 15;
    /**
     * The order timer of a building whose request is done or waits.
     */
    private static final int BUILDING_LONG_TIMER = 45;
    /**
     * The order timer of a building whose request may continue, and of a military unit.
     */
    private static final int SHORT_TIMER = 3;
    /**
     * The AIPatrol order states: looking for a patrol, patrolling, scouting.
     */
    private static final int PATROL_STATE_START = 0;
    private static final int PATROL_STATE_PATROLLING = 1;
    private static final int PATROL_STATE_SCOUTING = 2;

    /**
     * The random source of the JunkYardDog wander point.
     */
    private static final int JUNK_YARD_DOG_RANDOM_SOURCE = 0x30;
    /**
     * The largest wander direction.
     */
    private static final int JUNK_YARD_DOG_LAST_DIRECTION = 0xFF;
    /**
     * The wander distance range.
     */
    private static final int JUNK_YARD_DOG_MINIMUM_DISTANCE = 0x400;
    private static final int JUNK_YARD_DOG_MAXIMUM_DISTANCE = 0x800;
    /**
     * The frames the spell cooldown BYTE keeps a JunkYardDog on one wander point.
     */
    private static final int JUNK_YARD_DOG_WANDER_TIME = 0xFF;

    /**
     * The radius within which an Arbiter looks for an own unit with an automatic target.
     */
    private static final int ARBITER_FOLLOW_RADIUS = 0x280;
    /**
     * A Carrier or Reaver whose secondary order is in this state keeps it.
     */
    private static final int KEPT_SECONDARY_ORDER_STATE = 2;
    /**
     * The energy a Shield Battery needs before it calls a unit.
     */
    private static final int SHIELD_BATTERY_MINIMUM_ENERGY = 0x32;
    /**
     * A Shield Battery calls only units with at most these shields.
     */
    private static final int RECHARGE_MAXIMUM_SHIELDS = 0x28;
    /**
     * The radius of the enemy search that keeps a unit from decloaking.
     */
    private static final int THREAT_RADIUS = 0x100;

    /**
     * The random sources of the Spider Mine site: the region, then the horizontal and the vertical spread.
     */
    private static final int MINE_SITE_REGION_RANDOM_SOURCE = 0x2C;
    private static final int MINE_SITE_X_RANDOM_SOURCE = 0x2D;
    private static final int MINE_SITE_Y_RANDOM_SOURCE = 0x2E;
    /**
     * The spread of the Spider Mine site around the region center: a random value below twice the spread less it.
     */
    private static final int MINE_SITE_SPREAD_MASK = 0x1FF;
    private static final int MINE_SITE_SPREAD = 0x100;
    /**
     * No own Spider Mine may lie within this distance of the site.
     */
    private static final int MINE_SPACING = 0x60;
    /**
     * The region flags of a town region where Spider Mines are laid.
     */
    private static final int MINE_REGION_FLAGS = ComputerRegionStates.FLAG_TOWN | ComputerRegionStates.FLAG_KEEP_REQUIRED_STRENGTH;

    /**
     * The resources above which a building may request a combat unit while idle.
     */
    private static final int SURPLUS_RESOURCES = 1500;
    /**
     * The request priority of the surplus combat unit.
     */
    private static final int SURPLUS_PRIORITY = 0xA0;
    /**
     * The request priority of an idle building's combat unit without an active request.
     */
    private static final int IDLE_PRODUCTION_PRIORITY = 0x32;
    /**
     * The minerals and gas the default build needs.
     */
    private static final int DEFAULT_BUILD_MINERALS = 600;
    private static final int DEFAULT_BUILD_GAS = 300;
    /**
     * The building search radius in tiles of a construction or add-on site.
     */
    private static final int PLACEMENT_RADIUS = 0x40;
    /**
     * The choke limit recorded for a Bunker site and for a Creep Colony or Photon Cannon site.
     */
    private static final int BUNKER_CHOKE_LIMIT = 1;
    private static final int DEFENSE_CHOKE_LIMIT = 4;

    /**
     * Unit types {@code ZERG_GUARDIAN + bit} whose request leaves the reservations and budgets unchanged: Guardian,
     * Devourer, Dark Archon, Archon and Lurker, which morph from units.
     */
    private static final long RESERVATION_EXEMPT_TYPES = 0x8000000010C0001L;
    /**
     * The highest bit of {@link #RESERVATION_EXEMPT_TYPES} the test reads.
     */
    private static final int RESERVATION_EXEMPT_LAST_OFFSET = 0x3B;
    /**
     * The production failure codes, one bit per code, that drop the active request.
     */
    private static final int DROPPED_REQUEST_ERRORS = 0xA02184;
    /**
     * The highest code of {@link #DROPPED_REQUEST_ERRORS}.
     */
    private static final int LAST_DROPPED_REQUEST_ERROR = 0x17;
    /**
     * The production failure code of a producer of another race.
     */
    private static final int RACE_MISMATCH_ERROR = 0x19;

    /**
     * The build queue kind of an Observer.
     */
    private static final int QUEUE_KIND_OBSERVER = 5;
    /**
     * The build queue kind of a Nuclear Missile.
     */
    private static final int QUEUE_KIND_NUCLEAR_MISSILE = 8;

    /**
     * The energy of a Templar search without a candidate.
     */
    private static final int NO_ENERGY = 9999;
    /**
     * A High Templar with more energy than this in a merge source state is taken first.
     */
    private static final int MERGE_ENERGY_THRESHOLD = 0x4A;
    /**
     * The region states, one bit per state, whose High Templar are taken first.
     */
    private static final int MERGE_SOURCE_STATES = 1 << ComputerRegionStates.IDLE | 1 << ComputerRegionStates.ALLY_TOWN |
                                                   1 << ComputerRegionStates.TOWN | 1 << ComputerRegionStates.ATTACK_GATHERING |
                                                   1 << ComputerRegionStates.ATTACK_LAUNCHED;

    /**
     * The region states below {@link ComputerRegionStates#ATTACK_LAUNCHED}, one bit per state, whose Dark Templar are taken first.
     */
    private static final int DARK_MERGE_SOURCE_STATES = 1 << ComputerRegionStates.IDLE | 1 << ComputerRegionStates.ALLY_TOWN |
                                                        1 << ComputerRegionStates.TOWN | 1 << ComputerRegionStates.ATTACK_GATHERING;

    /**
     * Truncates a value to a BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The low WORD of a packed position: its horizontal coordinate.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The fraction bits of energy and shield points.
     */
    private static final int FIXED_POINT_FRACTION_BITS = 8;

    /**
     * Consumes the expired main order timer and reloads it.
     *
     * @param unit the unit
     * @return {@code true} if the timer had expired, {@code false} otherwise
     */
    static boolean consumeTimer(@NotNull Unit unit) {
        if (unit.getOrderTimer() != 0) {
            return false;
        }

        unit.setOrderTimer(ORDER_TIMER_RELOAD);

        return true;
    }

    /**
     * @param code a production failure code
     * @return {@code true} if the code drops the active request ({@link #DROPPED_REQUEST_ERRORS}), {@code false} otherwise
     */
    private static boolean isDroppedRequestError(int code) {
        return Integer.compareUnsigned(code, LAST_DROPPED_REQUEST_ERROR) <= 0 && (DROPPED_REQUEST_ERRORS >>> code & 1) != 0;
    }

    /**
     * @param unit the unit
     * @return {@link #ARRIVAL_AWAY} away from the movement destination, otherwise {@link #ARRIVAL_REACHED}, or {@link #ARRIVAL_BLOCKED} when immovable
     * @throws UnsupportedOperationException if the unit has no sprite
     */
    public static int getArrival(@NotNull Unit unit) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("The arrival test requires the unit sprite.");
        }

        if ((short) unit.getMovementDestinationX() != (short) sprite.getX() || (short) unit.getMovementDestinationY() != (short) sprite.getY()) {
            return ARRIVAL_AWAY;
        }

        return (unit.getStatus() & UnitStatusFlags.IMMOVABLE) != 0 ? ARRIVAL_BLOCKED : ARRIVAL_REACHED;
    }

    /**
     * Returns whether the update budgets cover the costs the last cost test stored for the computer's owner (unsigned
     * comparisons).
     */
    private static boolean coversTestedCosts(@NotNull CostTests costs, @NotNull ComputerPlayer computer) {
        int owner = computer.getOwner();

        return Integer.compareUnsigned(costs.getMineralCosts()[owner], computer.getMineralBudget()) <= 0 &&
               Integer.compareUnsigned(costs.getGasCosts()[owner], computer.getGasBudget()) <= 0 &&
               Integer.compareUnsigned(costs.getSupplyCosts()[owner], computer.getSupplyBudget()) <= 0;
    }


    private final @NotNull Game game;
    private final @NotNull BiPredicate<Unit, Unit> threatPredicate;

    /**
     * Creates a {@link ComputerOrders} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public ComputerOrders(@NotNull Game game) {
        this.game            = game;
        this.threatPredicate = this::isNearbyThreat;
    }

    /**
     * Issues the {@code units.dat} Attack Unit order of the unit at the target.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private void issueAttack(@NotNull Unit unit, @NotNull Unit target, int instructionLimit) {
        this.game.getOrders().issue(unit,
                                    this.game.requireGameData().getUnitTypes().getAttackUnitOrder(unit.getUnitId()),
                                    target,
                                    0, 0,
                                    ScrUnitIds.NONE,
                                    false,
                                    instructionLimit);
    }

    /**
     * Returns whether a unit may be recharged by the Shield Battery; a computer unit already recharging (or waiting for
     * a transport, or with a queued recharge) does not qualify.
     */
    private boolean canRecharge(@NotNull Unit unit, @NotNull Unit battery) {
        if (!this.game.getUnitShieldBattery().canUse(unit, battery)) {
            return false;
        }

        if (unit.getComputerRecord() == null) {
            return true;
        }

        int order = unit.getPrimaryOrder();

        if (order == ScrOrderIds.RECHARGE_SHIELDS_UNIT || order == ScrOrderIds.PICKUP4) {
            return false;
        }

        OrderPool.Order head = unit.getFirstOrder();

        return head == null || head.getOrderId() != ScrOrderIds.RECHARGE_SHIELDS_UNIT;
    }

    private boolean hasNearbyThreat(@NotNull Unit unit) {
        UnitSpatialIndex index = this.game.getUnitSystem().getUnitSpatialIndex();

        if (index == null) {
            throw Exceptions.newUnsupportedOperationException("Computer threat search requires the unit spatial index.");
        }

        return index.findNearest(unit, THREAT_RADIUS, this.threatPredicate) != null;
    }

    private boolean isNearbyThreat(@NotNull Unit candidate, @NotNull Unit searcher) {
        return this.game.getComputerUnits().isEnemy(searcher.getOwnerIndex(), candidate) && this.game.getUnitQueries().canAttack(candidate);
    }

    /**
     * Returns whether the producer's StarEdit race matches the requested type, recording the race mismatch error
     * otherwise.
     *
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the race does not match and the game has no computer-player phase
     */
    private boolean isSameRace(@NotNull Unit unit, int type) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int requested = units.getGroupFlags(type);
        int producer = units.getGroupFlags(unit.getUnitId());
        boolean matches;

        if ((requested & UnitGroupFlags.TERRAN) != 0) {
            matches = (producer & UnitGroupFlags.TERRAN) != 0;
        } else if ((requested & UnitGroupFlags.ZERG) != 0) {
            matches = (producer & UnitGroupFlags.ZERG) != 0;
        } else {
            matches = (producer & UnitGroupFlags.PROTOSS) != 0;
        }

        if (!matches) {
            this.game.requireComputerPlayerPhase().setLastError(RACE_MISMATCH_ERROR);
        }

        return matches;
    }

    /**
     * Returns whether the producer can make the type through the common production requirement test.
     */
    private boolean canBuild(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, int type) {
        if (!this.game.getUnitProduction().canBuild(unit, type)) {
            // Record the production failure after the common requirement test rejects the type.
            phase.setProductionFailure(type, unit);

            return false;
        }

        phase.setLastError(0);

        return true;
    }

    /**
     * Tests the unit cost (supply tested, error display on) followed by the update budgets ({@link #coversTestedCosts}).
     *
     * @param computer the computer player
     * @param type     the unit type
     * @return {@code true} if the owner can afford the type within the budgets, {@code false} otherwise
     */
    boolean canAfford(@NotNull ComputerPlayer computer, int type) {
        CostTests costs = this.game.getCostTests();

        return costs.testUnit(this.game, computer.getOwner(), type, true, true) && coversTestedCosts(costs, computer);
    }

    /**
     * Returns whether the {@code define_max} count of the type is reached; the owner's Siege Tanks count in both modes.
     *
     * @param computer the computer player
     * @param type     the unit type
     * @return {@code true} if the type is refused or its count reached the maximum, {@code false} otherwise
     * @throws UnsupportedOperationException if the unit accounting is not initialized
     */
    boolean isAtMaximum(@NotNull ComputerPlayer computer, int type) {
        if (type >= ScrUnitIds.NONE) {
            return false;
        }

        int maximum = computer.getMaximumCount(type);

        if (maximum == 0) {
            return false;
        }

        if (maximum == ComputerTowns.MAXIMUM_REFUSED) {
            return true;
        }

        ComputerUnits computerUnits = this.game.getComputerUnits();
        int count = computerUnits.getTypeCount(type, computer.getOwner());

        if (type == ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE) {
            count += computerUnits.getTypeCount(ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE, computer.getOwner());
        }

        return Integer.compareUnsigned(count, maximum) >= 0;
    }

    private void issueTargetOrder(@NotNull Unit unit, int order, @NotNull Unit target, int instructionLimit) {
        Sprite sprite = target.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Computer merge orders require the partner sprite.");
        }

        this.game.getOrders().issue(unit, order, target, sprite.getX(), sprite.getY(), ScrUnitIds.NONE, false, instructionLimit);
    }

    /**
     * Returns whether the producer cannot make the type or its {@code define_max} is reached.
     */
    private boolean isUnavailable(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, int type) {
        return !this.canBuild(phase, unit, type) || this.isAtMaximum(phase.getPlayer(unit.getOwnerIndex()), type);
    }


    /**
     * Sets the movement destination and the direction target.
     *
     * @param unit      the moving unit
     * @param positionX the destination horizontal coordinate
     * @param positionY the destination vertical coordinate
     */
    void moveTo(@NotNull Unit unit, int positionX, int positionY) {
        this.game.getUnitPositioning().setMovementDestination(unit, positionX, positionY);

        if ((short) unit.getDirectionTargetX() != positionX || (short) unit.getDirectionTargetY() != positionY) {
            unit.setDirectionTargetX(positionX);
            unit.setDirectionTargetY(positionY);
        }
    }

    /**
     * Unburrows a burrowed unit to attack: a burrowed Lurker with an order target stays; otherwise the unit unburrows
     * and, with an empty queue, queues the Computer AI order.
     *
     * @param unit             the unit
     * @param instructionLimit the iscript budget for issued orders
     */
    void unburrowToAttack(@NotNull Unit unit, int instructionLimit) {
        if (this.game.requireGameData().getBaseType(unit) == ScrUnitIds.ZERG_LURKER && unit.getOrderTargetUnit() != null) {
            return;
        }

        int order = unit.getPrimaryOrder();

        if (order == ScrOrderIds.BURROWING || order == ScrOrderIds.BURROWED ||
            this.game.requireGameData().getBaseType(unit) == ScrUnitIds.ZERG_LURKER) {
            if (this.game.requireGameData().getBaseType(unit) == ScrUnitIds.ZERG_LURKER) {
                unit.setStatus(unit.getStatus() | UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE);
            }

            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.UNBURROWING, instructionLimit);
            // The unit may act again: a pending queue activates, otherwise it returns to Computer AI, or to its units.dat
            // Return to Idle order without a computer record.
            this.game.getOrders().completeOrder(unit, instructionLimit);
        }

        if (unit.getFirstOrder() == null) {
            this.game.getOrders().issue(unit, ScrOrderIds.COMPUTER_AI, null, 0, 0, ScrUnitIds.NONE, true, instructionLimit);
        }
    }

    /**
     * Runs the AIPatrol order.
     * <p>
     * An expired timer is reloaded before the phase and patrol-target checks.
     *
     * @param unit the unit
     * @throws UnsupportedOperationException if the game has no computer-player phase or the patrol target has no sprite
     */
    public void updatePatrol(@NotNull Unit unit) {
        if (!consumeTimer(unit)) {
            return;
        }

        ComputerPlayerPhase phase = this.game.requireComputerPlayerPhase();
        ComputerRegions regions = phase.getRegions();

        switch (unit.getPrimaryOrderState()) {
            case PATROL_STATE_START -> {
                Unit target = unit.getOrderTargetUnit();

                if (target != null) {
                    Sprite sprite = target.getSpriteOrNull();

                    if (sprite == null) {
                        throw Exceptions.newUnsupportedOperationException("The patrol target requires its sprite.");
                    }

                    unit.setOrderTargetX(sprite.getX());
                    unit.setOrderTargetY(sprite.getY());
                    this.moveTo(unit, (short) sprite.getX(), (short) sprite.getY());

                    return;
                }

                if (regions.continuePatrol(unit) || regions.assignPatrol(phase, unit)) {
                    unit.setPrimaryOrderState(PATROL_STATE_PATROLLING);
                } else {
                    unit.setPrimaryOrderState(PATROL_STATE_SCOUTING);
                }
            }
            case PATROL_STATE_PATROLLING -> {
                if (!regions.continuePatrol(unit)) {
                    unit.setPrimaryOrderState(PATROL_STATE_START);
                }
            }
            case PATROL_STATE_SCOUTING -> {
                if (regions.assignPatrol(phase, unit)) {
                    unit.setPrimaryOrderState(PATROL_STATE_PATROLLING);

                    return;
                }

                if (getArrival(unit) != ARRIVAL_AWAY) {
                    int region = phase.getRegions().selectScoutRegion(unit);
                    TerrainRegions terrain = this.game.getComputerTerrain().getRegions();

                    this.moveTo(unit, terrain.getCenterX(region), terrain.getCenterY(region));
                }
            }
            default -> {
            }
        }
    }

    /**
     * Runs the Neutral order.
     * <p>
     * A neutral unit with an automatic target (an attacker) that is visible, detected and owned by another player
     * switches its owner to computer behavior: the owner's player type becomes computer, both alliance BYTEs between the
     * two owners become enemy, and the unit attacks the target (with its {@code units.dat} Attack Unit order, after clearing its
     * order targets; also for its turret) when it can, otherwise it returns to its {@code units.dat} Return to Idle order. The
     * computer AI adopts it; every other live, non-subunit unit of the owner returns to idle (a unit in a Bunker guards
     * it, another loaded unit keeps its order), its turret attacks the same target, and it is adopted too. The automatic
     * target is always cleared.
     *
     * @param unit             the neutral unit
     * @param instructionLimit the iscript budget for issued orders
     * @throws UnsupportedOperationException if the switch to computer behavior has no computer-player phase or no player relations, or the unit pool is not initialized
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the automatic target has no sprite
     */
    public void updateNeutral(@NotNull Unit unit, int instructionLimit) {
        Unit target = unit.getAutomaticTargetUnit();

        if (target == null) {
            return;
        }

        int owner = unit.getOwnerIndex();
        int enemy = target.getOwnerIndex();

        if ((target.requireSprite().getFlags() & Sprite.FLAG_HIDDEN) == 0 && !UnitTargeting.isUndetected(unit, target) && owner != enemy) {
            ComputerPlayerPhase phase = this.game.requireComputerPlayerPhase();
            PlayerRelations relations = this.game.getPlayerTable().getPlayerRelations();

            if (relations == null) {
                throw Exceptions.newUnsupportedOperationException("Neutral units switching to computer behavior require player relations.");
            }

            this.game.getPlayerTable().requirePlayer(owner).setType(PlayerTypes.COMPUTER);
            relations.setAlliance(owner, enemy, PlayerRelations.ENEMY);
            relations.setAlliance(enemy, owner, PlayerRelations.ENEMY);

            ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

            if (this.game.getUnitTargeting().canAttackTarget(unit, target)) {
                this.game.getComputerCombat().clearOrderTargets(unit);
                this.issueAttack(unit, target, instructionLimit);

                Unit turret = unit.getSubunit();

                if (turret != null && this.game.getComputerUnits().isSubunit(turret)) {
                    this.issueAttack(turret, target, instructionLimit);
                }
            } else {
                this.game.getOrders().issueUntargeted(unit, units.getReturnToIdleOrder(unit.getUnitId()), instructionLimit);
            }

            phase.adoptUnit(unit, instructionLimit);

            UnitPool pool = this.game.getUnitSystem().requireUnitPool();

            for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
                Unit other = pool.getUnit(poolIndex);

                if (ComputerUnits.isDeadOrHidden(other) || this.game.getComputerUnits().isSubunit(other) || other == unit) {
                    continue;
                }

                if ((other.getStatus() & UnitStatusFlags.LOADED) == 0) {
                    this.game.getOrders().issueUntargeted(other, units.getReturnToIdleOrder(other.getUnitId()), instructionLimit);
                } else if ((other.getStatus() & UnitStatusFlags.IN_BUNKER) != 0) {
                    this.game.getOrders().issueUntargeted(other, ScrOrderIds.BUNKER_GUARD, instructionLimit);
                }

                Unit turret = other.getSubunit();

                if (turret != null && this.game.getComputerUnits().isSubunit(turret)) {
                    this.issueAttack(turret, target, instructionLimit);
                }

                phase.adoptUnit(other, instructionLimit);
            }
        }

        unit.setAutomaticTargetUnit(null);
    }

    /**
     * Runs JunkYardDog, the wandering order of aiscript {@code junkyard_dog}.
     * <p>
     * State {@code 0} draws a direction and a distance, moves the order target position that far from the unit along the
     * direction, keeps it inside the map for the unit's dimensions, sets the movement destination there, enters state {@code 1}
     * and loads the spell cooldown BYTE with the wander time. Every call then attacks the automatic target when it is an
     * enemy of the unit's owner (a neutral target counts as its sprite owner), not hidden, not invincible and detected;
     * otherwise, with the order timer expired (and reloaded), an arrived unit or a grounded unit whose wander time ran out
     * goes back to state {@code 0}, and any other unit attacks a target of the acquisition search. An attack issues the {@code units.dat}
     * Attack Unit order, queues JunkYardDog again and marks the unit and its subunit ready to attack.
     *
     * @param unit             the unit
     * @param instructionLimit the iscript budget for issued orders
     * @throws UnsupportedOperationException if player relations are unavailable
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the unit or the automatic target has no sprite
     */
    public void updateJunkYardDog(@NotNull Unit unit, int instructionLimit) {
        if (unit.getPrimaryOrderState() == 0) {
            int direction = this.game.getRandom().next(JUNK_YARD_DOG_RANDOM_SOURCE, 0, JUNK_YARD_DOG_LAST_DIRECTION);
            int distance = this.game.getRandom().next(JUNK_YARD_DOG_RANDOM_SOURCE,
                                                      JUNK_YARD_DOG_MINIMUM_DISTANCE, JUNK_YARD_DOG_MAXIMUM_DISTANCE);
            long vector = FlingyMotionUtils.calculateDirectionVector(direction, distance);
            Sprite sprite = unit.requireSprite();
            ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
            int type = unit.getUnitId();
            int positionX = UnitPositioning.clampCoordinate((short) (sprite.getX() + (int) vector),
                                                            units.getDimensionLeft(type), units.getDimensionRight(type),
                                                            this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE,
                                                            0);
            int positionY = UnitPositioning.clampCoordinate((short) (sprite.getY() + (int) (vector >>> Integer.SIZE)),
                                                            units.getDimensionUp(type), units.getDimensionDown(type),
                                                            this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE,
                                                            TerrainTileset.TILE_SIZE);

            unit.setOrderTargetX(positionX);
            unit.setOrderTargetY(positionY);
            this.game.getUnitPositioning().setMovementDestination(unit, (short) positionX, (short) positionY);
            unit.setPrimaryOrderState(1);
            unit.setCooldownTimers(unit.getCooldownTimers() & ~Unit.SPELL_COOLDOWN_MASK | JUNK_YARD_DOG_WANDER_TIME << Unit.SPELL_COOLDOWN_SHIFT);
        }

        Unit automatic = unit.getAutomaticTargetUnit();

        if (automatic != null) {
            int targetOwner = automatic.getOwnerIndex() == PlayerTable.NEUTRAL_PLAYER_ID ? automatic.requireSprite().getOwner()
                                                                                         : automatic.getOwnerIndex();
            PlayerRelations relations = this.game.getPlayerTable().getPlayerRelations();

            if (relations == null) {
                throw Exceptions.newUnsupportedOperationException("JunkYardDog requires player relations.");
            }

            if (relations.getAlliance(unit.getOwnerIndex(), targetOwner) == PlayerRelations.ENEMY &&
                (automatic.requireSprite().getFlags() & Sprite.FLAG_HIDDEN) == 0 &&
                (automatic.getStatus() & UnitStatusFlags.INVINCIBLE) == 0 && !UnitTargeting.isUndetected(unit, automatic)) {
                this.attackAndWander(unit, automatic, instructionLimit);
                unit.setAutomaticTargetUnit(null);

                return;
            }
        }

        if (unit.getOrderTimer() != 0) {
            return;
        }

        unit.setOrderTimer(ORDER_TIMER_RELOAD);

        if (getArrival(unit) != ARRIVAL_AWAY ||
            (unit.getCooldownTimers() >>> Unit.SPELL_COOLDOWN_SHIFT) == 0 && (unit.getStatus() & UnitStatusFlags.FLYING) == 0) {
            unit.setPrimaryOrderState(0);

            return;
        }

        Unit target = this.game.getUnitTargeting().findAcquireTarget(unit);

        if (target != null) {
            this.attackAndWander(unit, target, instructionLimit);
        }
    }

    /**
     * Attacks for {@link #updateJunkYardDog}: Attack Unit at the target, JunkYardDog queued behind it, and the unit and
     * its subunit marked ready to attack.
     */
    private void attackAndWander(@NotNull Unit unit, @NotNull Unit target, int instructionLimit) {
        this.issueAttack(unit, target, instructionLimit);
        this.game.getOrders().queueBack(unit, ScrOrderIds.JUNK_YARD_DOG, null, 0, 0, ScrUnitIds.NONE);
        unit.markReadyToAttack();
    }

    /**
     * Runs the ComputerAI order.
     *
     * @param unit             the unit
     * @param instructionLimit the iscript budget
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the game has no computer-player phase
     * @throws IllegalStateException         if the repairer has no sprite
     */
    public void updateComputer(@NotNull Unit unit, int instructionLimit) {
        if (!consumeTimer(unit)) {
            return;
        }

        ComputerPlayerPhase phase = this.game.requireComputerPlayerPhase();
        int type = this.game.requireGameData().getBaseType(unit);

        if (type == ScrUnitIds.ZERG_LARVA) {
            this.game.getLarvaSpawner().updatePrimary(unit, instructionLimit);

            if (ComputerUnits.isDeadOrHidden(unit)) {
                return;
            }
        } else if (type == ScrUnitIds.TERRAN_MEDIC) {
            // A Medic of an idle region joins the nearest town region; otherwise it heals.
            ComputerUnitRecord record = unit.getComputerRecord();

            if (record != null && record.getKind() == ComputerUnitRecord.KIND_MILITARY &&
                phase.getRegions().getState(record.regionIndex) == ComputerRegionStates.IDLE) {
                this.moveToTownRegion(phase, unit, instructionLimit);
            } else {
                phase.issueOrder(unit, ScrOrderIds.MEDIC_IDLE);
            }

            return;
        }

        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null) {
            this.game.getOrders().issueUntargeted(unit,
                                                  this.game.requireGameData().getUnitTypes().getComputerIdleOrder(unit.getUnitId()),
                                                  instructionLimit);

            return;
        }

        if (this.updateSpecialAbility(unit)) {
            return;
        }

        switch (record.getKind()) {
            case ComputerUnitRecord.KIND_WORKER -> this.updateWorker(phase, unit, record, instructionLimit);
            case ComputerUnitRecord.KIND_BUILDING -> unit.setOrderTimer(this.updateBuilding(phase, unit, instructionLimit) ? BUILDING_LONG_TIMER : SHORT_TIMER);
            case ComputerUnitRecord.KIND_MILITARY -> {
                phase.getMilitary().update(phase, unit, instructionLimit);
                unit.setOrderTimer(SHORT_TIMER);
            }
            default -> this.game.getOrders().issueUntargeted(unit,
                                                             this.game.requireGameData().getUnitTypes().getComputerIdleOrder(unit.getUnitId()),
                                                             instructionLimit);
        }

        if (UnitRacesUtils.getDisplayRace(this.game.requireGameData().getUnitTypes(), unit.getUnitId()) == ScrRaceIds.TERRAN &&
            this.needsRepair(phase, unit)) {
            // The damaged unit follows the town's reserved SCV.
            Unit repairer = this.game.getComputerResources().findRepairer(unit);

            if (repairer != null) {
                Sprite sprite = repairer.requireSprite();

                this.game.getOrders().issue(unit,
                                            ScrOrderIds.FOLLOW,
                                            repairer,
                                            sprite.getX(), sprite.getY(),
                                            ScrUnitIds.NONE,
                                            false,
                                            instructionLimit);
            }

            return;
        }

        if (type == ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE) {
            this.updateSiegeTank(phase, unit, instructionLimit);

            return;
        }

        if ((this.game.requireGameData().getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.BURROWABLE) != 0) {
            if ((unit.getStatus() & UnitStatusFlags.BURROWED) != 0 || this.game.requireGameData().getBaseType(unit) == ScrUnitIds.ZERG_DRONE) {
                return;
            }

            int order = unit.getPrimaryOrder();

            if (order != ScrOrderIds.COMPUTER_AI && order != ScrOrderIds.GUARD) {
                return;
            }

            if (getArrival(unit) == ARRIVAL_AWAY) {
                return;
            }

            ComputerPlayerPhase burrowPhase = this.game.requireComputerPlayerPhase();

            if (burrowPhase.canUseTechnology(unit, ScrTechIds.BURROWING)) {
                burrowPhase.issueOrder(unit, ScrOrderIds.BURROWING);
            }
        }
    }

    /**
     * Sieges a Tank-mode Siege Tank of a non-campaign owner in ComputerAI or Guard that has arrived at its destination
     * when its owner can use Tank Siege Mode.
     */
    private void updateSiegeTank(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, int instructionLimit) {
        if (phase.getPlayer(unit.getOwnerIndex()).isCampaign()) {
            return;
        }

        int order = unit.getPrimaryOrder();

        if (order != ScrOrderIds.COMPUTER_AI && order != ScrOrderIds.GUARD) {
            return;
        }

        if (getArrival(unit) == ARRIVAL_AWAY) {
            return;
        }

        if (!this.game.getUnitTechnology().canUseTechnology(unit, ScrTechIds.TANK_SIEGE_MODE)) {
            return;
        }

        this.game.getOrders().issueUntargeted(unit, ScrOrderIds.SIEGING, instructionLimit);
    }

    /**
     * Returns whether a unit needs repair: a damaged mechanical ground unit that is not a worker, not attacking and has
     * no Irradiate or Plague timer.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private boolean needsRepair(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit) {
        if ((this.game.requireGameData().getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.MECHANICAL) == 0) {
            return false;
        }

        ComputerUnitRecord record = unit.getComputerRecord();

        if (record != null && record.getKind() == ComputerUnitRecord.KIND_MILITARY &&
            ComputerRegionStates.isAttackState(phase.getRegions().getState(record.regionIndex))) {
            return false;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if (UnitQueries.getLife(units, unit) == this.game.getUnitQueries().getMaximumLife(unit)) {
            return false;
        }

        if ((unit.getStatus() & UnitStatusFlags.CAN_MOVE) == 0) {
            return false;
        }

        if (unit.getPlagueTimer() != 0 || unit.getIrradiateTimer() != 0) {
            return false;
        }

        return !this.game.getComputerUnits().isWorker(unit);
    }

    /**
     * Moves a transport that cannot unload to a neighbor region: the last neighbor whose record is a town or an attack
     * staging region, else the last idle one, else the last other one, else its own region. An inaccessible choice sends
     * it to a town region ({@link #moveToTownRegion}); otherwise it move-unloads at the region's center.
     *
     * @param phase            the computer-player phase
     * @param unit             the transport
     * @param instructionLimit the iscript budget for issued orders
     */
    public void moveTransportToNeighbor(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, int instructionLimit) {
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        ComputerRegions regions = phase.getRegions();
        int region = this.game.getComputerTerrain().getUnitRegion(unit);
        int preferred = ComputerPlayerPhase.NO_REGION;
        int empty = ComputerPlayerPhase.NO_REGION;
        int other = ComputerPlayerPhase.NO_REGION;
        int count = terrain.getWalkableNeighborCount(region);

        for (int index = 0; index < count; index++) {
            int neighbor = terrain.getNeighborRegion(region, index);
            int regionState = regions.getState(regions.getIndex(unit.getOwnerIndex(), neighbor));

            if (regionState == ComputerRegionStates.IDLE) {
                empty = neighbor;
            } else if (regionState == ComputerRegionStates.ALLY_TOWN || regionState == ComputerRegionStates.TOWN ||
                       regionState == ComputerRegionStates.ATTACK_GATHERING || regionState == ComputerRegionStates.ATTACK_LAUNCHED) {
                preferred = neighbor;
            } else {
                other = neighbor;
            }
        }

        int chosen = preferred != ComputerPlayerPhase.NO_REGION
                     ? preferred
                     : empty != ComputerPlayerPhase.NO_REGION ? empty : other != ComputerPlayerPhase.NO_REGION ? other : region;

        if (this.game.getComputerTerrain().isInaccessible(chosen)) {
            this.moveToTownRegion(phase, unit, instructionLimit);

            return;
        }

        this.game.getOrders().issue(unit,
                                    ScrOrderIds.MOVE_UNLOAD,
                                    null,
                                    terrain.getCenterX(chosen), terrain.getCenterY(chosen),
                                    ScrUnitIds.NONE,
                                    false,
                                    instructionLimit);
    }

    /**
     * Moves a unit outside a town region (a town or ally town region with a worker or building) to the center of the
     * nearest such region, joining its military list when military.
     *
     * @param phase            the computer-player phase
     * @param unit             the unit
     * @param instructionLimit the iscript budget for issued orders
     * @return {@code true} if the unit was sent to a town region, {@code false} otherwise
     * @throws IllegalStateException if the unit has no sprite
     */
    public boolean moveToTownRegion(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, int instructionLimit) {
        if (unit.getComputerRecord() == null) {
            return false;
        }

        int owner = unit.getOwnerIndex();
        ComputerRegions regions = phase.getRegions();
        int unitRegion = this.game.getComputerTerrain().getUnitRegion(unit);
        int unitState = regions.getState(regions.getIndex(owner, unitRegion));

        if ((unitState == ComputerRegionStates.ALLY_TOWN || unitState == ComputerRegionStates.TOWN) &&
            this.game.getComputerTerrain().hasWorkerOrBuilding(owner, unitRegion)) {
            return false;
        }

        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int bestX = -1;
        int bestY = -1;
        int best = ComputerPlayerPhase.NO_CANDIDATE_DISTANCE;

        for (int region = 1; region < terrain.getRegionCount(); region++) {
            int regionState = regions.getState(regions.getIndex(owner, region));

            if (regionState != ComputerRegionStates.ALLY_TOWN && regionState != ComputerRegionStates.TOWN ||
                !this.game.getComputerTerrain().hasWorkerOrBuilding(owner, region)) {
                continue;
            }

            int centerX = terrain.getCenterX(region);
            int centerY = terrain.getCenterY(region);
            Sprite sprite = unit.requireSprite();
            int distance = RegionPathSearch.calculateDistance(centerX, (short) sprite.getX(), centerY, (short) sprite.getY());

            if (Integer.compareUnsigned(distance, best) < 0) {
                best  = distance;
                bestX = centerX;
                bestY = centerY;
            }
        }

        if (bestX == -1) {
            return false;
        }

        if (unit.getComputerRecord().getKind() == ComputerUnitRecord.KIND_MILITARY) {
            phase.removeUnit(unit, false);
            phase.getMilitary().addToRegion(phase,
                                            regions.getIndex(owner, this.game.getComputerTerrain().getRegion((short) bestX, (short) bestY)),
                                            unit,
                                            true,
                                            instructionLimit);
        }

        int order = this.game.requireGameData().getBaseType(unit) == ScrUnitIds.TERRAN_MEDIC ? ScrOrderIds.HEAL_MOVE : ScrOrderIds.MOVE;
        int primary = unit.getPrimaryOrder();

        if (primary == ScrOrderIds.UNLOAD || primary == ScrOrderIds.MOVE_UNLOAD) {
            order = ScrOrderIds.MOVE_UNLOAD;
        }

        this.game.getOrders().issue(unit, order, null, bestX, bestY, ScrUnitIds.NONE, false, instructionLimit);

        return true;
    }

    /**
     * Runs the represented cloak and unit-type cases of the special abilities.
     *
     * @return {@code true} if a special behavior handled the unit, {@code false} otherwise
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if a unit-type case needs the computer-player phase and the game has none or the unit pool is not initialized
     * @throws IllegalStateException         if the repairer, the unit or the candidate has no sprite
     */
    private boolean updateSpecialAbility(@NotNull Unit unit) {
        int type = this.game.requireGameData().getBaseType(unit);

        if (unit.getSecondaryOrder() == ScrOrderIds.CLOAK &&
            (this.game.requireGameData().getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.CLOAKABLE) != 0 &&
            !this.game.getComputerCombat().isEngaged(unit, null) && unit.getAutomaticTargetUnit() == null && !this.hasNearbyThreat(unit)) {
            int order = unit.getPrimaryOrder();

            if (order < ScrOrderIds.NUKE_WAIT || order > ScrOrderIds.NUKE_TRACK) {
                ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.DECLOAK);
            }
        }

        switch (ScrUnitIds.requireValid(type)) {
            case ScrUnitIds.TERRAN_MISSILE_TURRET, ScrUnitIds.ZERG_SPORE_COLONY, ScrUnitIds.ZERG_SUNKEN_COLONY, ScrUnitIds.PROTOSS_PHOTON_CANNON -> {
                // Static defenses switch to TowerGuard.
                this.game.requireComputerPlayerPhase().issueOrder(unit, ScrOrderIds.TOWER_GUARD);

                return true;
            }
            case ScrUnitIds.TERRAN_VULTURE -> {
                // A Vulture with Spider Mines lays one at a random quiet town site.
                if (!this.game.getUnitTechnology().canUseTechnology(unit, ScrTechIds.SPIDER_MINES)) {
                    return false;
                }

                int site = this.findMineSite(unit);

                if (site == -1) {
                    return false;
                }

                this.game.getOrders().issue(unit,
                                            ScrOrderIds.PLACE_MINE,
                                            null,
                                            (short) site, site >> Short.SIZE,
                                            ScrUnitIds.NONE,
                                            false,
                                            this.game.requireComputerPlayerPhase().getInstructionLimit());

                return true;
            }
            case ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE -> {
                // A guarding Tank at its post sieges when idle.
                ComputerUnitRecord record = unit.requireComputerRecord();

                if (record.getKind() != ComputerUnitRecord.KIND_GUARD ||
                    this.game.getComputerCombat().returnToGuardPost(unit, this.game.requireComputerPlayerPhase().getInstructionLimit())) {
                    return false;
                }

                if (!this.game.getUnitTechnology().canUseTechnology(unit, ScrTechIds.TANK_SIEGE_MODE) || unit.getFirstOrder() != null) {
                    return false;
                }

                this.game.requireComputerPlayerPhase().issueOrder(unit, ScrOrderIds.SIEGING);

                return true;
            }
            case ScrUnitIds.TERRAN_DROPSHIP, ScrUnitIds.PROTOSS_SHUTTLE, ScrUnitIds.ZERG_OVERLORD -> {
                ComputerPlayerPhase phase = this.game.requireComputerPlayerPhase();
                ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

                if (type == ScrUnitIds.TERRAN_DROPSHIP && UnitQueries.getLife(units, unit) != this.game.getUnitQueries().getMaximumLife(unit)) {
                    // A damaged Dropship follows the reserved SCV unless it carries units.
                    Unit repairer = this.game.getComputerResources().findRepairer(unit);

                    if (repairer != null) {
                        if (this.game.getUnitTransport().hasLoadedUnits(unit)) {
                            phase.issueOrder(unit, ScrOrderIds.UNLOAD);
                        } else {
                            Sprite sprite = repairer.requireSprite();

                            this.game.getOrders().issue(unit,
                                                        ScrOrderIds.FOLLOW,
                                                        repairer,
                                                        sprite.getX(), sprite.getY(),
                                                        ScrUnitIds.NONE,
                                                        false,
                                                        phase.getInstructionLimit());
                        }

                        return true;
                    }
                }

                // A transport outside town regions returns to one; a loaded one unloads.
                if (type != ScrUnitIds.ZERG_OVERLORD && this.moveToTownRegion(phase, unit, phase.getInstructionLimit())) {
                    return true;
                }

                if (!this.game.getUnitTransport().hasLoadedUnits(unit)) {
                    return false;
                }

                phase.issueOrder(unit, ScrOrderIds.UNLOAD);

                return true;
            }
            case ScrUnitIds.PROTOSS_ARBITER -> {
                // An Arbiter follows the first own non-building unit with an automatic target around it.
                Sprite sprite = unit.requireSprite();
                ComputerPlayerPhase phase = this.game.requireComputerPlayerPhase();
                ComputerTerrain query = this.game.getComputerTerrain();
                int found = query.collectRectangleUnits(
                        (short) (sprite.getX() - ARBITER_FOLLOW_RADIUS), (short) (sprite.getY() - ARBITER_FOLLOW_RADIUS),
                        (short) (sprite.getX() + ARBITER_FOLLOW_RADIUS), (short) (sprite.getY() + ARBITER_FOLLOW_RADIUS)
                );
                ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

                for (int index = 0; index < found; index++) {
                    Unit candidate = this.game.getUnitSystem().requireUnitPool().getUnit(query.getRegionUnit(index));

                    if (candidate.getOwnerIndex() != unit.getOwnerIndex() || candidate.getAutomaticTargetUnit() == null ||
                        UnitQueries.isBuilding(units, candidate.getUnitId())) {
                        continue;
                    }

                    Sprite target = candidate.requireSprite();

                    this.game.getOrders().issue(unit,
                                                ScrOrderIds.ATTACK_MOVE,
                                                null,
                                                target.getX(), target.getY(),
                                                ScrUnitIds.NONE,
                                                false,
                                                phase.getInstructionLimit());

                    return true;
                }

                return false;
            }
            case ScrUnitIds.PROTOSS_CARRIER, ScrUnitIds.PROTOSS_REAVER -> {
                // A Carrier or Reaver queues its next Interceptor or Scarab and trains it.
                ComputerPlayerPhase phase = this.game.requireComputerPlayerPhase();
                int child = type == ScrUnitIds.PROTOSS_CARRIER ? ScrUnitIds.PROTOSS_INTERCEPTOR : ScrUnitIds.PROTOSS_SCARAB;

                if (!this.canBuild(phase, unit, child) || this.game.getUnitProduction().pushBuildQueue(unit, child) < 0) {
                    return false;
                }

                if (unit.getSecondaryOrderState() == KEPT_SECONDARY_ORDER_STATE) {
                    return false;
                }

                ScrOrderSystem.restartSecondaryOrder(unit, ScrOrderIds.TRAIN_FIGHTER);

                return false;
            }
            case ScrUnitIds.PROTOSS_SHIELD_BATTERY -> {
                // A Shield Battery with enough energy calls the first own unit it may recharge whose shields are low, has
                // no automatic target and is not attacking.
                if ((unit.getEnergy() >>> FIXED_POINT_FRACTION_BITS & BYTE_MASK) < SHIELD_BATTERY_MINIMUM_ENERGY) {
                    return false;
                }

                ComputerPlayerPhase phase = this.game.requireComputerPlayerPhase();
                UnitPool pool = this.game.getUnitSystem().requireUnitPool();

                for (int poolIndex = pool.getFirstOwnerIndex(unit.getOwnerIndex()); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
                    Unit candidate = pool.getUnit(poolIndex);

                    if (!this.canRecharge(candidate, unit) || candidate.getAutomaticTargetUnit() != null) {
                        continue;
                    }

                    ComputerUnitRecord record = candidate.getComputerRecord();

                    if (record != null && record.getKind() == ComputerUnitRecord.KIND_MILITARY &&
                        ComputerRegionStates.isAttackState(phase.getRegions().getState(record.regionIndex))) {
                        continue;
                    }

                    if (candidate.getShieldPoints() >> FIXED_POINT_FRACTION_BITS > RECHARGE_MAXIMUM_SHIELDS) {
                        continue;
                    }

                    Sprite sprite = candidate.requireSprite();

                    this.game.getOrders().issue(unit,
                                                ScrOrderIds.RECHARGE_SHIELDS_BATTERY,
                                                null,
                                                sprite.getX(), sprite.getY(),
                                                ScrUnitIds.NONE,
                                                false,
                                                phase.getInstructionLimit());

                    return false;
                }

                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Finds a Spider Mine site: a random town region center moved by a random spread, clamped to the map, in the
     * Vulture's ground component, not inaccessible and without an own Spider Mine nearby.
     *
     * @return the packed site {@code x | y << Short.SIZE}, or {@code -1}
     * @throws UnsupportedOperationException if the game has no computer-player phase or the unit pool is not initialized
     */
    private int findMineSite(@NotNull Unit unit) {
        ComputerPlayerPhase phase = this.game.requireComputerPlayerPhase();
        ComputerRegions regions = phase.getRegions();
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int owner = unit.getOwnerIndex();
        int count = terrain.getRegionCount();
        int[] candidates = new int[count];
        int found = 0;

        for (int region = 1; region < count; region++) {
            int index = regions.getIndex(owner, region);
            int regionState = regions.getState(index);

            if ((regionState == ComputerRegionStates.ALLY_TOWN || regionState == ComputerRegionStates.TOWN) &&
                (regions.getFlags(index) & MINE_REGION_FLAGS) != 0) {
                candidates[found++] = region;
            }
        }

        if (found == 0) {
            return -1;
        }

        LcgRandom random = this.game.getRandom();
        int region = candidates[Integer.remainderUnsigned(random.next(MINE_SITE_REGION_RANDOM_SOURCE), found)];
        int positionX = terrain.getCenterX(region) + ((random.next(MINE_SITE_X_RANDOM_SOURCE) & MINE_SITE_SPREAD_MASK) - MINE_SITE_SPREAD);
        int positionY = terrain.getCenterY(region) + ((random.next(MINE_SITE_Y_RANDOM_SOURCE) & MINE_SITE_SPREAD_MASK) - MINE_SITE_SPREAD);
        int width = this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE;
        int height = this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE;

        if (positionX < 0) {
            positionX = 0;
        } else if (positionX >= width) {
            positionX = width - 1;
        }

        if (positionY < 0) {
            positionY = 0;
        } else if (positionY >= height) {
            positionY = height - 1;
        }

        if (!unit.sharesTerrainComponent(terrain, (short) positionX, (short) positionY)) {
            return -1;
        }

        if (this.game.getComputerTerrain().isInaccessible(this.game.getComputerTerrain().getRegion((short) positionX, (short) positionY))) {
            return -1;
        }

        ComputerTerrain query = this.game.getComputerTerrain();
        int nearby = query.collectRectangleUnits((short) (positionX - MINE_SPACING), (short) (positionY - MINE_SPACING),
                                                 (short) (positionX + MINE_SPACING), (short) (positionY + MINE_SPACING));

        for (int index = 0; index < nearby; index++) {
            UnitPool pool = this.game.getUnitSystem().requireUnitPool();
            Unit candidate = pool.getUnit(query.getRegionUnit(index));

            if (this.game.requireGameData().getBaseType(candidate) == ScrUnitIds.TERRAN_VULTURE_SPIDER_MINE && candidate.getOwnerIndex() == owner) {
                return -1;
            }
        }

        return positionX & WORD_MASK | positionY << Short.SIZE;
    }

    /**
     * Runs the worker branch: repair for an SCV, then construction, then mining or gas collection by the worker's
     * assignment.
     *
     * @throws IllegalStateException         if the worker record has no town
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the mineral search runs for a worker without a sprite
     */
    private void updateWorker(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, @NotNull ComputerUnitRecord record, int instructionLimit) {
        if (record.workerDelay != 0) {
            record.workerDelay--;

            return;
        }

        ComputerTown town = record.town;

        if (town == null) {
            throw Exceptions.newIllegalStateException("Computer worker record has no town.");
        }

        record.lastUpdateClock = phase.getElapsedClockTicks();

        if (UnitRacesUtils.getDisplayRace(this.game.requireGameData().getUnitTypes(), unit.getUnitId()) == ScrRaceIds.TERRAN &&
            this.assignRepair(phase, town, unit, instructionLimit)) {
            return;
        }

        if (this.startConstruction(phase, town, unit, record, instructionLimit)) {
            return;
        }

        Unit depot = town.resourceDepot;

        if (depot == null || !this.game.getUnitResourceSearch().isActiveDepot(depot)) {
            return;
        }

        Unit current = unit.getTargetResourceUnit();
        ComputerResources resources = phase.getResources();

        if (record.workerAssignment == ComputerTowns.ASSIGNMENT_MINERALS) {
            if (current != null && this.game.requireGameData().getUnitTypes().isMineralField(current.getUnitId())) {
                resources.assignWorker(unit, current, null, instructionLimit);

                return;
            }

            Unit mineral = town.mineralTarget;

            if (mineral == null || mineral.isResourceClaimedByComputer()) {
                Sprite sprite = unit.getSpriteOrNull();

                if (sprite == null) {
                    throw Exceptions.newUnsupportedOperationException("Computer mineral search dereferences a missing worker sprite.");
                }

                mineral = resources.findMineral(unit, (short) sprite.getX(), (short) sprite.getY(), town.workerCount <= FEW_TOWN_WORKERS);

                if (mineral == null) {
                    mineral = town.mineralTarget;
                }
            }

            Unit gas = this.findGasBuilding(town, GAS_ACTIVE, 1);

            if (gas == null) {
                gas = this.findGasBuilding(town, GAS_DEPLETED, 1);
            }

            resources.assignWorker(unit, mineral, gas, instructionLimit);

            return;
        }

        int ordinal = record.workerAssignment == ComputerTowns.ASSIGNMENT_FIRST_GAS
                      ? 1
                      : record.workerAssignment == ComputerTowns.ASSIGNMENT_FIRST_GAS + 1 ? SECOND_GAS_BUILDING : THIRD_GAS_BUILDING;
        Unit gas = this.findGasBuilding(town, GAS_ACTIVE, ordinal);
        int[][] fallbacks = GAS_FALLBACKS;

        for (int index = 0; gas == null && index < fallbacks.length; index++) {
            gas = this.findGasBuilding(town, fallbacks[index][0], fallbacks[index][1]);
        }

        resources.assignWorker(unit, gas, town.mineralTarget, instructionLimit);
    }

    /**
     * Assigns an SCV's repair: the SCV repairs the town building (without a builder link, in a quiet region) that is
     * incomplete, else the one missing the most hit points (shields left out); with none, the town's reserved SCV
     * repairs the nearest unit following it. The target records the SCV as its builder link.
     *
     * @return {@code true} if a repair order was issued, {@code false} otherwise
     * @throws NullPointerException  if the world was built without game data
     * @throws IllegalStateException if the repair target has no sprite
     */
    private boolean assignRepair(@NotNull ComputerPlayerPhase phase, @NotNull ComputerTown town, @NotNull Unit unit, int instructionLimit) {
        Unit best = null;
        int bestMissing = 0;
        ComputerRegions regions = phase.getRegions();

        for (ComputerUnitRecord record = town.firstBuilding; record != null; record = record.next) {
            Unit building = record.requireUnit();

            if (building.getParentUnit() != null || ComputerUnits.isDeadOrHidden(building) ||
                !regions.isQuiet(building.getOwnerIndex(), this.game.getComputerTerrain().getUnitRegion(building))) {
                continue;
            }

            if ((building.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
                best = building;

                break;
            }

            // The missing hit points leave the shields out: a building whose shields alone are down is not repaired.
            int missing = this.game.getUnitQueries().getMaximumHitPoints(building) - UnitQueries.getHitPoints(building);

            if (Integer.compareUnsigned(bestMissing, missing) < 0) {
                best        = building;
                bestMissing = missing;
            }
        }

        if (best == null) {
            if (town.reservedWorker == null) {
                town.reservedWorker = unit;
            }

            if (town.reservedWorker != unit) {
                return false;
            }

            best = this.game.getComputerResources().findRepairTarget(unit);

            if (best == null) {
                return false;
            }
        }

        Sprite sprite = best.requireSprite();

        this.game.getOrders().issue(unit, ScrOrderIds.REPAIR, best, sprite.getX(), sprite.getY(), ScrUnitIds.NONE, false, instructionLimit);
        best.setParentUnit(unit);

        return true;
    }

    /**
     * Finds the town's gas building of a class by its ordinal among the buildings of that class.
     *
     * @param town    the town
     * @param kind    the gas building class ({@link #GAS_GEYSER}, {@link #GAS_DEPLETED} or {@link #GAS_ACTIVE})
     * @param ordinal the one-based ordinal among the town's buildings of the class
     * @return the gas building, or {@code null}
     */
    @Nullable Unit findGasBuilding(@NotNull ComputerTown town, int kind, int ordinal) {
        int found = 0;

        for (int slot = 0; slot < ComputerTown.GAS_BUILDING_COUNT; slot++) {
            Unit gas = town.gasBuildings[slot];

            if (gas != null && this.classifyGas(town.owner, gas) == kind && ++found == ordinal) {
                return gas;
            }
        }

        return null;
    }

    /**
     * Classifies a gas building of the owner.
     *
     * @param owner the town owner
     * @param unit  the gas building
     * @return {@link #GAS_GEYSER} for a geyser, {@link #GAS_ACTIVE} for an active refinery, {@link #GAS_DEPLETED} when depleted, or {@code 0}
     */
    int classifyGas(int owner, @NotNull Unit unit) {
        if (ComputerUnits.isDeadOrHidden(unit)) {
            return GAS_NONE;
        }

        if (this.game.requireGameData().getBaseType(unit) == ScrUnitIds.RESOURCE_VESPENE_GEYSER) {
            return GAS_GEYSER;
        }

        Player player = this.game.getPlayerTable().getPlayer(owner);

        if (player != null && this.game.requireGameData().getBaseType(unit) == ComputerUnits.getRefineryType(player.getRace())) {
            return unit.getResources() == 0 ? GAS_DEPLETED : GAS_ACTIVE;
        }

        return GAS_NONE;
    }

    /**
     * Runs the building branch: the type-specific actions, then the active request of the owner (the kinds up to the
     * Observer request; other kinds do nothing).
     *
     * @param phase            the computer-player phase
     * @param unit             the computer building
     * @param instructionLimit the iscript budget for issued orders
     * @return {@code true} if the long timer is selected, {@code false} otherwise
     * @throws UnsupportedOperationException if the owner has no player record
     * @throws IllegalStateException         if the unit has no sprite
     */
    boolean updateBuilding(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, int instructionLimit) {
        int type = this.game.requireGameData().getBaseType(unit);

        if (type == ScrUnitIds.ZERG_OVERLORD) {
            if (getArrival(unit) != ARRIVAL_AWAY) {
                int region = phase.getRegions().selectScoutRegion(unit);
                TerrainRegions terrain = this.game.getComputerTerrain().getRegions();

                this.moveTo(unit, terrain.getCenterX(region), terrain.getCenterY(region));
            }
        } else if (type == ScrUnitIds.TERRAN_COMSAT_STATION) {
            // The spell routine has no Comsat case, so it only reads the countdown.
            ComputerSpells.cast(phase, this.game, unit, false);
        } else if (type == ScrUnitIds.TERRAN_NUCLEAR_SILO) {
            // A Nuclear Silo below its define_max arms a Nuclear Missile.
            ComputerPlayer silo = phase.getPlayer(unit.getOwnerIndex());

            if (!this.isAtMaximum(silo, ScrUnitIds.TERRAN_NUCLEAR_MISSILE) &&
                this.isSameRace(unit, ScrUnitIds.TERRAN_NUCLEAR_MISSILE) &&
                this.canBuild(phase, unit, ScrUnitIds.TERRAN_NUCLEAR_MISSILE) &&
                this.canAfford(silo, ScrUnitIds.TERRAN_NUCLEAR_MISSILE)) {
                int slot = this.game.getUnitProduction().pushBuildQueue(unit, ScrUnitIds.TERRAN_NUCLEAR_MISSILE);

                if (slot >= 0) {
                    ComputerUnitRecord siloRecord = unit.getComputerRecord();

                    if (siloRecord != null) {
                        siloRecord.queueKinds[slot]   = QUEUE_KIND_NUCLEAR_MISSILE;
                        siloRecord.queueTargets[slot] = null;
                    }

                    phase.issueOrder(unit, ScrOrderIds.NUKE_TRAIN);
                    ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.TRAIN);
                }
            }
        }

        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null || record.getKind() != ComputerUnitRecord.KIND_BUILDING) {
            return true;
        }

        int owner = unit.getOwnerIndex();
        Player player = this.game.getPlayerTable().getPlayer(owner);

        if (player == null) {
            throw Exceptions.newUnsupportedOperationException("Computer building behavior requires the owner's player record.");
        }

        boolean surplus = false;

        if (player.getMinerals() > SURPLUS_RESOURCES && player.getGas() > SURPLUS_RESOURCES && !UnitQueries.isProducing(unit)) {
            surplus = this.selectIdleProduction(phase, unit, SURPLUS_PRIORITY);
        }

        ComputerPlayer computer = phase.getPlayer(owner);
        int requestKind = computer.getActiveRequestKind();

        switch (requestKind) {
            case ComputerPlayer.REQUEST_KIND_NONE -> {
                if (!this.selectIdleProduction(phase, unit, IDLE_PRODUCTION_PRIORITY)) {
                    return true;
                }

                return this.produceMilitary(phase, computer, unit, true, instructionLimit);
            }
            case ComputerPlayer.REQUEST_KIND_REGION, ComputerPlayer.REQUEST_KIND_GUARD -> {
                return this.produceMilitary(phase, computer, unit, surplus, instructionLimit);
            }
            case ComputerPlayer.REQUEST_KIND_TOWN -> {
                int requestType = computer.getActiveRequestType();

                if (!this.canAfford(computer, requestType)) {
                    return false;
                }

                if (requestType == ScrUnitIds.ZERG_OVERLORD) {
                    if (this.game.requireGameData().getBaseType(unit) != ScrUnitIds.ZERG_LARVA) {
                        return false;
                    }

                    // The Overlord is queued with the town as its request target.
                    int slot = this.game.getUnitProduction().pushBuildQueue(unit, ScrUnitIds.ZERG_OVERLORD);

                    if (slot < 0) {
                        return false;
                    }

                    record.queueKinds[slot]   = ComputerPlayer.REQUEST_KIND_TOWN;
                    record.queueTargets[slot] = record.town;

                    phase.issueOrder(unit, ScrOrderIds.ZERG_UNIT_MORPH);
                    this.popRequest(computer, true);

                    return false;
                }

                if (!this.game.requireGameData().getUnitTypes().isBuildingMorphType(requestType)) {
                    return false;
                }

                if (computer.getActiveRequestTown() != record.town) {
                    return false;
                }

                if (this.isSameRace(unit, requestType) && this.canBuild(phase, unit, requestType)) {
                    if (this.game.getUnitProduction().pushBuildQueue(unit, requestType) < 0) {
                        return false;
                    }

                    phase.issueOrder(unit, ScrOrderIds.ZERG_BUILDING_MORPH);
                    this.popRequest(computer, true);

                    return true;
                }

                this.failRequest(phase, computer, unit, false, instructionLimit);

                return false;
            }
            case ComputerPlayer.REQUEST_KIND_WORKER -> {
                if (computer.getActiveRequestTown() != record.town) {
                    return true;
                }

                int requestType = computer.getActiveRequestType();

                if (!this.isSameRace(unit, requestType)) {
                    return true;
                }

                if (!this.canBuild(phase, unit, requestType)) {
                    return true;
                }

                if (!this.canAfford(computer, requestType)) {
                    return true;
                }

                if (this.isAtMaximum(computer, requestType)) {
                    this.popRequest(computer, false);

                    return true;
                }

                if (this.game.getUnitProduction().pushBuildQueue(unit, requestType) < 0) {
                    return true;
                }

                if (this.game.requireGameData().getBaseType(unit) == ScrUnitIds.ZERG_LARVA) {
                    phase.issueOrder(unit, ScrOrderIds.ZERG_UNIT_MORPH);
                } else {
                    ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.TRAIN);
                }

                this.popRequest(computer, true);

                return true;
            }
            case ComputerPlayer.REQUEST_KIND_UPGRADE, ComputerPlayer.REQUEST_KIND_RESEARCH -> {
                boolean upgrade = requestKind == ComputerPlayer.REQUEST_KIND_UPGRADE;
                int requestType = computer.getActiveRequestType();
                int code = upgrade
                           ? ScrRequirementSystem.getFailureCode(this.game.getRequirements().checkUpgrade(unit, requestType, owner))
                           : ScrRequirementSystem.getFailureCode(this.game.getRequirements().checkResearch(unit, requestType, owner));

                phase.setLastError(code);

                if (code != 0) {
                    // A dropping failure code drops the request and runs the building branch again.
                    if (isDroppedRequestError(code)) {
                        this.popRequest(computer, false);
                        this.updateBuilding(phase, unit, instructionLimit);
                    }

                    return true;
                }

                // The upgrade or technology cost test without error display, then the budgets.
                CostTests costs = this.game.getCostTests();
                boolean affordable = upgrade
                                     ? costs.testUpgrade(this.game, owner, requestType, false)
                                     : costs.testTechnology(this.game, owner, requestType, false);

                if (!affordable || !coversTestedCosts(costs, computer)) {
                    return true;
                }

                boolean started = upgrade
                                  ? this.game.getUnitResearch().startUpgrade(unit, requestType, instructionLimit)
                                  : this.game.getUnitResearch().startResearch(unit, requestType, instructionLimit);

                if (!started) {
                    return true;
                }

                phase.issueOrder(unit, upgrade ? ScrOrderIds.UPGRADE : ScrOrderIds.RESEARCH_TECH);
                this.popRequest(computer, true);

                return true;
            }
            case ComputerPlayer.REQUEST_KIND_ADDON -> {
                // An add-on request of the building's town.
                int requestType = computer.getActiveRequestType();

                if (computer.getActiveRequestTown() != record.town) {
                    return true;
                }

                if (!this.isSameRace(unit, requestType) || !this.canBuild(phase, unit, requestType)) {
                    this.failRequest(phase, computer, unit, false, instructionLimit);

                    return true;
                }

                if (!this.canAfford(computer, requestType)) {
                    return true;
                }

                ComputerPlacement placement = phase.getPlacement();
                int position = placement.findAtUnit(unit, requestType);

                if (position == -1 && computer.isCampaign()) {
                    Sprite sprite = unit.requireSprite();

                    position = placement.find(computer, unit, requestType, (short) sprite.getX(), (short) sprite.getY(), PLACEMENT_RADIUS);
                }

                if (position == -1) {
                    this.failRequest(phase, computer, unit, true, instructionLimit);

                    return true;
                }

                this.popRequest(computer, true);
                this.game.getOrders().issue(unit,
                                            ScrOrderIds.PLACE_ADDON,
                                            null,
                                            (short) position, position >> Short.SIZE,
                                            ScrUnitIds.NONE,
                                            false,
                                            instructionLimit);
                this.game.getUnitLifecycle().resetBuildQueue(unit, instructionLimit);
                unit.setAddonBuildType(requestType);

                return true;
            }
            case ComputerPlayer.REQUEST_KIND_OBSERVER -> {
                // An Observer request, queued with its own build queue kind.
                if (computer.getActiveRequestType() != ScrUnitIds.PROTOSS_OBSERVER) {
                    return false;
                }

                if (!this.isSameRace(unit, ScrUnitIds.PROTOSS_OBSERVER) || !this.canBuild(phase, unit, ScrUnitIds.PROTOSS_OBSERVER)) {
                    this.failRequest(phase, computer, unit, false, instructionLimit);

                    return false;
                }

                if (!this.canAfford(computer, ScrUnitIds.PROTOSS_OBSERVER)) {
                    return false;
                }

                if (this.isAtMaximum(computer, ScrUnitIds.PROTOSS_OBSERVER)) {
                    this.popRequest(computer, false);

                    return false;
                }

                int slot = this.game.getUnitProduction().pushBuildQueue(unit, ScrUnitIds.PROTOSS_OBSERVER);

                if (slot < 0) {
                    return false;
                }

                record.queueKinds[slot]   = QUEUE_KIND_OBSERVER;
                record.queueTargets[slot] = null;

                ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.TRAIN);
                this.popRequest(computer, true);

                return true;
            }
            default -> {
                // Other request kinds keep the long timer.
                return true;
            }
        }
    }

    /**
     * Sends the worker to construct the active building request of its town.
     *
     * @return {@code true} if the worker received the construction order, {@code false} otherwise
     * @throws UnsupportedOperationException if the town owner has no player record
     * @throws IllegalStateException         if the geyser has no sprite
     */
    private boolean startConstruction(@NotNull ComputerPlayerPhase phase,
                                      @NotNull ComputerTown town,
                                      @NotNull Unit worker,
                                      @NotNull ComputerUnitRecord record,
                                      int instructionLimit) {
        int owner = worker.getOwnerIndex();
        ComputerPlayer computer = phase.getPlayer(owner);

        if (computer.getActiveRequestKind() != ComputerPlayer.REQUEST_KIND_TOWN) {
            return false;
        }

        int type = computer.getActiveRequestType();

        if (computer.getActiveRequestTown() != record.town && type != ScrUnitIds.TERRAN_SUPPLY_DEPOT ||
            record.workerState >= CONSTRUCTION_RETRY_LIMIT) {
            return false;
        }

        if (!this.isSameRace(worker, type) || !this.canBuild(phase, worker, type) || !this.canAfford(computer, type)) {
            return false;
        }

        Player townOwner = this.game.getPlayerTable().getPlayer(town.owner);

        if (townOwner == null) {
            throw Exceptions.newUnsupportedOperationException("Computer construction requires the town owner's player record.");
        }

        int positionX;
        int positionY;

        if (type == ComputerUnits.getRefineryType(townOwner.getRace())) {
            Unit geyser = this.findGasBuilding(town, GAS_GEYSER, 1);

            if (geyser == null) {
                return false;
            }

            positionX = geyser.requireSprite().getX();
            positionY = geyser.requireSprite().getY();
        } else {
            int origin;

            if (type == ScrUnitIds.TERRAN_BUNKER || type == ScrUnitIds.TERRAN_MISSILE_TURRET) {
                // A Bunker or Missile Turret takes the first region that wants one, otherwise the town position.
                int flag = type == ScrUnitIds.TERRAN_BUNKER ? ComputerRegionStates.FLAG_BUNKER_WANTED : ComputerRegionStates.FLAG_TURRET_WANTED;

                origin = phase.getRegions().takeBuildSite(town.owner, flag);

                if (origin == -1) {
                    origin = town.positionX & WORD_MASK | town.positionY << Short.SIZE;
                }

                if (type == ScrUnitIds.TERRAN_BUNKER) {
                    this.game.getComputerTerrain().recordChokes(town.owner,
                                                                this.game.getComputerTerrain().getRegion((short) origin, origin >> Short.SIZE),
                                                                BUNKER_CHOKE_LIMIT);
                }
            } else if (type == ScrUnitIds.ZERG_HATCHERY && town.resourceDepot != null) {
                origin = this.game.getComputerTowns().getDepotSite(town.resourceArea);
            } else {
                origin = town.positionX & WORD_MASK | town.positionY << Short.SIZE;

                if (type == ScrUnitIds.ZERG_CREEP_COLONY || type == ScrUnitIds.PROTOSS_PHOTON_CANNON) {
                    this.game.getComputerTerrain().recordChokes(
                            town.owner,
                            this.game.getComputerTerrain().getRegion((short) town.positionX, (short) town.positionY),
                            DEFENSE_CHOKE_LIMIT
                    );
                }
            }

            int position = phase.getPlacement().find(computer, worker, type, (short) origin, origin >> Short.SIZE, PLACEMENT_RADIUS);

            if (position == -1) {
                // The placement error code is read without effect: the request fails and the worker branch returns.
                this.popRequest(computer, false);

                return false;
            }

            positionX = (short) position;
            positionY = position >> Short.SIZE;

            if (type == ScrUnitIds.TERRAN_BUNKER) {
                phase.getRegions().activate(phase, town.owner, this.game.getComputerTerrain().getRegion((short) positionX, (short) positionY), true);
            }
        }

        this.popRequest(computer, true);

        int order = switch (ScrUnitIds.requireValid(this.game.requireGameData().getBaseType(worker.getUnitId()))) {
            case ScrUnitIds.TERRAN_SCV -> ScrOrderIds.PLACE_BUILDING;
            case ScrUnitIds.ZERG_DRONE -> ScrOrderIds.DRONE_START_BUILD;
            case ScrUnitIds.PROTOSS_PROBE -> ScrOrderIds.PLACE_PROTOSS_BUILDING;
            default -> -1;
        };

        if (order == -1) {
            return false;
        }

        this.game.getOrders().issue(worker, order, null, positionX, positionY, ScrUnitIds.NONE, false, instructionLimit);
        this.game.getUnitLifecycle().resetBuildQueue(worker, instructionLimit);
        this.game.getUnitProduction().pushBuildQueue(worker, type);

        return true;
    }

    /**
     * Drops the active request for a dropping error code or forced failure, then retries its building branch.
     */
    private void failRequest(@NotNull ComputerPlayerPhase phase,
                             @NotNull ComputerPlayer computer,
                             @NotNull Unit unit,
                             boolean forced,
                             int instructionLimit) {
        int error = phase.getLastError();

        if (forced || isDroppedRequestError(error)) {
            this.popRequest(computer, false);

            this.updateBuilding(phase, unit, instructionLimit);
        }
    }

    /**
     * Pops the active request after success or failure, releasing its reservations unless the type morphs from units.
     *
     * @param computer the computer player
     * @param success  whether the request succeeded, which also charges the update budgets
     * @throws NullPointerException if the world was built without game data
     */
    void popRequest(@NotNull ComputerPlayer computer, boolean success) {
        int kind = computer.getActiveRequestKind();
        int type = computer.getActiveRequestType();
        int offset = type - ScrUnitIds.ZERG_GUARDIAN;
        boolean exempt = offset >= 0 && offset <= RESERVATION_EXEMPT_LAST_OFFSET && (RESERVATION_EXEMPT_TYPES >>> offset & 1) != 0;

        if (kind == ComputerPlayer.REQUEST_KIND_UPGRADE || kind == ComputerPlayer.REQUEST_KIND_RESEARCH) {
            int[] cost = kind == ComputerPlayer.REQUEST_KIND_UPGRADE
                         ? this.game.getComputerTechnology().getUpgradeCost(computer.getOwner(), type)
                         : this.game.getComputerTechnology().getResearchCost(type);

            computer.popRequest(success, cost[0], cost[1], 0, exempt);

            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        computer.popRequest(success, units.getMineralCost(type), units.getGasCost(type), ComputerTowns.getSupplyCost(units, type), exempt);
    }

    /**
     * Requests the pending unit of the script, or with surplus resources a producer-specific combat unit.
     *
     * @return {@code true} if a request is active afterwards, {@code false} otherwise
     */
    private boolean selectIdleProduction(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, int priority) {
        if (ComputerUnits.isUnavailableForTown(unit) || unit.getSecondaryOrder() != ScrOrderIds.NOTHING) {
            return false;
        }

        int owner = unit.getOwnerIndex();
        ComputerPlayer computer = phase.getPlayer(owner);
        int type = computer.takePendingUnitRequest();

        if (type == -1) {
            Player player = this.game.getPlayerTable().requirePlayer(owner);

            if (player.getMinerals() < DEFAULT_BUILD_MINERALS || player.getGas() < DEFAULT_BUILD_GAS) {
                return false;
            }

            if ((computer.getFlags() & ComputerPlayer.FLAG_DEFAULT_BUILD_OFF) != 0) {
                return false;
            }
        } else if (phase.getMilitary().hasEmptyGuard(owner, type)) {
            phase.getMilitary().markGuardRequested(owner, type);

            type = -1;
        }

        boolean forced = false;

        if (type != -1) {
            forced = true;

            if (type == ScrUnitIds.NONE) {
                return false;
            }
        } else {
            type = switch (ScrUnitIds.requireValid(this.game.requireGameData().getBaseType(unit.getUnitId()))) {
                case ScrUnitIds.ZERG_LARVA -> this.isUnavailable(phase, unit, ScrUnitIds.ZERG_HYDRALISK) ? ScrUnitIds.ZERG_MUTALISK
                                                                                                         : ScrUnitIds.ZERG_HYDRALISK;
                case ScrUnitIds.TERRAN_BARRACKS -> this.isAtMaximum(computer, ScrUnitIds.TERRAN_MARINE) ? ScrUnitIds.TERRAN_GHOST
                                                                                                        : ScrUnitIds.TERRAN_MARINE;
                case ScrUnitIds.TERRAN_FACTORY -> this.isUnavailable(phase, unit, ScrUnitIds.TERRAN_GOLIATH) ? ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE
                                                                                                             : ScrUnitIds.TERRAN_GOLIATH;
                case ScrUnitIds.TERRAN_STARPORT -> this.isAtMaximum(computer, ScrUnitIds.TERRAN_WRAITH) ? ScrUnitIds.TERRAN_BATTLECRUISER
                                                                                                        : ScrUnitIds.TERRAN_WRAITH;
                case ScrUnitIds.PROTOSS_ROBOTICS_FACILITY -> ScrUnitIds.PROTOSS_REAVER;
                case ScrUnitIds.PROTOSS_GATEWAY -> this.isUnavailable(phase, unit, ScrUnitIds.PROTOSS_DRAGOON) ? ScrUnitIds.PROTOSS_ZEALOT
                                                                                                               : ScrUnitIds.PROTOSS_DRAGOON;
                case ScrUnitIds.PROTOSS_STARGATE -> this.isAtMaximum(computer, ScrUnitIds.PROTOSS_SCOUT) ? ScrUnitIds.PROTOSS_CARRIER
                                                                                                         : ScrUnitIds.PROTOSS_SCOUT;
                default -> -1;
            };

            if (type == -1) {
                return false;
            }

            boolean checked = switch (ScrUnitIds.requireValid(this.game.requireGameData().getBaseType(unit.getUnitId()))) {
                case ScrUnitIds.ZERG_LARVA -> type == ScrUnitIds.ZERG_MUTALISK;
                case ScrUnitIds.TERRAN_BARRACKS -> type == ScrUnitIds.TERRAN_GHOST;
                case ScrUnitIds.TERRAN_FACTORY -> type == ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE;
                case ScrUnitIds.TERRAN_STARPORT -> type == ScrUnitIds.TERRAN_BATTLECRUISER;
                case ScrUnitIds.PROTOSS_ROBOTICS_FACILITY -> true;
                case ScrUnitIds.PROTOSS_GATEWAY -> type == ScrUnitIds.PROTOSS_ZEALOT;
                case ScrUnitIds.PROTOSS_STARGATE -> type == ScrUnitIds.PROTOSS_CARRIER;
                default -> false;
            };

            if (checked) {
                boolean unavailable = this.game.requireGameData().getBaseType(unit) == ScrUnitIds.PROTOSS_GATEWAY
                                      ? this.isAtMaximum(computer, ScrUnitIds.PROTOSS_ZEALOT)
                                      : this.isUnavailable(phase, unit, type);

                if (unavailable) {
                    return false;
                }
            }
        }

        int region = phase.getRegions().getIndex(owner, this.game.getComputerTerrain().getUnitRegion(unit));

        this.game.getComputerTowns().requestUnit(computer, region, type, priority, true, forced);

        return computer.getActiveRequestKind() != ComputerPlayer.REQUEST_KIND_NONE;
    }

    /**
     * Finds the unit a Mutalisk or Hydralisk morph starts from: the first idle source unit without an automatic or order
     * target that is a guard or a military unit in an idle or town region; otherwise the last military source unit seen
     * in any other state.
     *
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private @Nullable Unit findMorphSource(@NotNull ComputerPlayerPhase phase, int owner, int sourceType) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        ComputerRegions regions = phase.getRegions();
        Unit fallback = null;

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || this.game.requireGameData().getBaseType(unit) != sourceType ||
                unit.getAutomaticTargetUnit() != null || unit.getOrderTargetUnit() != null) {
                continue;
            }

            ComputerUnitRecord record = unit.getComputerRecord();

            if (record == null) {
                continue;
            }

            if (record.getKind() == ComputerUnitRecord.KIND_GUARD) {
                return unit;
            }

            if (record.getKind() != ComputerUnitRecord.KIND_MILITARY) {
                continue;
            }

            fallback = unit;

            int regionState = regions.getState(record.regionIndex);

            if ((regionState & ComputerRegionStates.ACTIVE_STATE_BITS) == 0 && regionState != ComputerRegionStates.ATTACK) {
                return unit;
            }
        }

        return fallback;
    }

    /**
     * Finds two completed High Templar not already merging; a guard or a military Templar with enough energy in a merge
     * source state is taken first. The fallback scan replaces its lowest slot when a lower energy is seen,
     * otherwise its second slot when lower than that slot; replacing the lowest slot does not shift its former unit.
     *
     * @return the two partners, or {@code null}
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private @NotNull Unit @Nullable [] findHighTemplars(@NotNull ComputerPlayerPhase phase, int owner) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        ComputerRegions regions = phase.getRegions();
        Unit first = null;
        Unit lowest = null;
        Unit second = null;
        int lowestEnergy = NO_ENERGY;
        int secondEnergy = NO_ENERGY;
        boolean picking = true;

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || this.game.requireGameData().getBaseType(unit) != ScrUnitIds.PROTOSS_HIGH_TEMPLAR ||
                (unit.getStatus() & UnitStatusFlags.COMPLETED) == 0 ||
                (unit.getStatus() & UnitStatusFlags.LOADED) != 0 || unit.getPrimaryOrder() == ScrOrderIds.ARCHON_WARP) {
                continue;
            }

            ComputerUnitRecord record = unit.getComputerRecord();

            if (record == null) {
                continue;
            }

            if (record.getKind() == ComputerUnitRecord.KIND_GUARD) {
                if (!picking) {
                    return new Unit[]{first, unit};
                }

                first   = unit;
                picking = false;

                continue;
            }

            if (record.getKind() != ComputerUnitRecord.KIND_MILITARY) {
                continue;
            }

            int energy = unit.getEnergy() >>> FIXED_POINT_FRACTION_BITS & BYTE_MASK;

            if (lowest == null || energy < lowestEnergy) {
                lowestEnergy = energy;
                lowest       = unit;
            } else if (second == null || energy < secondEnergy) {
                secondEnergy = energy;
                second       = unit;
            }

            int regionState = regions.getState(record.regionIndex);

            if (energy > MERGE_ENERGY_THRESHOLD && regionState <= ComputerRegionStates.ATTACK_LAUNCHED &&
                (MERGE_SOURCE_STATES >>> regionState & 1) != 0) {
                if (!picking) {
                    return new Unit[]{first, unit};
                }

                first   = unit;
                picking = false;
            }
        }

        if (lowest != null && second != null) {
            return new Unit[]{lowest, second};
        }

        return null;
    }

    /**
     * Finds two completed Dark Templar not already merging; a guard or a military Templar in a merge source state is
     * taken first, otherwise the first and the last military Templar.
     *
     * @return the two partners, or {@code null}
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private @NotNull Unit @Nullable [] findDarkTemplars(@NotNull ComputerPlayerPhase phase, int owner) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        ComputerRegions regions = phase.getRegions();
        Unit first = null;
        Unit earliest = null;
        Unit latest = null;
        boolean picking = true;

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || this.game.requireGameData().getBaseType(unit) != ScrUnitIds.PROTOSS_DARK_TEMPLAR ||
                (unit.getStatus() & UnitStatusFlags.COMPLETED) == 0 ||
                (unit.getStatus() & UnitStatusFlags.LOADED) != 0 || unit.getPrimaryOrder() == ScrOrderIds.DARK_ARCHON_MELD) {
                continue;
            }

            ComputerUnitRecord record = unit.getComputerRecord();

            if (record == null) {
                continue;
            }

            if (record.getKind() == ComputerUnitRecord.KIND_GUARD) {
                if (!picking) {
                    return new Unit[]{first, unit};
                }

                first   = unit;
                picking = false;

                continue;
            }

            if (record.getKind() != ComputerUnitRecord.KIND_MILITARY) {
                continue;
            }

            if (earliest == null) {
                earliest = unit;
            } else {
                latest = unit;
            }

            int regionState = regions.getState(record.regionIndex);

            if (regionState < ComputerRegionStates.ATTACK_LAUNCHED && (DARK_MERGE_SOURCE_STATES >>> regionState & 1) != 0 ||
                regionState == ComputerRegionStates.ATTACK_LAUNCHED) {
                if (!picking) {
                    return new Unit[]{first, unit};
                }

                first   = unit;
                picking = false;
            }
        }

        if (earliest != null && latest != null) {
            return new Unit[]{earliest, latest};
        }

        return null;
    }

    /**
     * Produces the active military request with the building, or with the unit that morphs into the requested type.
     *
     * @param noRetry whether the building branch is not run again after a failed request
     * @return {@code true} if the long timer is selected, {@code false} otherwise
     */
    private boolean produceMilitary(@NotNull ComputerPlayerPhase phase,
                                    @NotNull ComputerPlayer computer,
                                    @NotNull Unit unit,
                                    boolean noRetry,
                                    int instructionLimit) {
        int type = computer.getActiveRequestType();
        int owner = computer.getOwner();
        Unit producer = unit;

        switch (ScrUnitIds.requireValid(this.game.requireGameData().getBaseType(type))) {
            case ScrUnitIds.ZERG_GUARDIAN, ScrUnitIds.ZERG_DEVOURER -> producer = this.findMorphSource(phase, owner, ScrUnitIds.ZERG_MUTALISK);
            case ScrUnitIds.ZERG_LURKER -> producer = this.findMorphSource(phase, owner, ScrUnitIds.ZERG_HYDRALISK);
            case ScrUnitIds.PROTOSS_ARCHON, ScrUnitIds.PROTOSS_DARK_ARCHON -> {
                Unit[] pair = type == ScrUnitIds.PROTOSS_ARCHON ? this.findHighTemplars(phase, owner) : this.findDarkTemplars(phase, owner);

                if (pair == null) {
                    this.popRequest(computer, false);

                    return false;
                }

                // Each partner is ordered to merge with the other.
                int order = type == ScrUnitIds.PROTOSS_ARCHON ? ScrOrderIds.ARCHON_WARP : ScrOrderIds.DARK_ARCHON_MELD;

                this.issueTargetOrder(pair[0], order, pair[1], instructionLimit);
                this.issueTargetOrder(pair[1], order, pair[0], instructionLimit);
                this.popRequest(computer, true);

                return false;
            }
            default -> {
            }
        }

        if (producer == null) {
            this.popRequest(computer, false);

            return false;
        }

        if (!this.isSameRace(producer, type) || !this.canBuild(phase, producer, type)) {
            int error = phase.getLastError();

            if (!isDroppedRequestError(error)) {
                return false;
            }
        } else if (this.canAfford(computer, type)) {
            if (this.isAtMaximum(computer, type)) {
                this.popRequest(computer, false);

                return false;
            }

            int kind = computer.getActiveRequestKind();
            int slot = this.game.getUnitProduction().pushBuildQueue(producer, type);

            if (slot < 0) {
                return false;
            }

            if (producer != unit) {
                // The morph source leaves its records and joins the requesting guard or region.
                phase.removeUnit(producer, false);

                if (kind == ComputerPlayer.REQUEST_KIND_GUARD) {
                    ComputerMilitary.refillGuard(computer.requireActiveRequestGuard(), producer);
                } else {
                    phase.getMilitary().addToRegion(phase, computer.getActiveRequestRegion(), producer, true, instructionLimit);
                }

                this.popRequest(computer, true);
                phase.issueOrder(producer, ScrOrderIds.ZERG_UNIT_MORPH);

                return true;
            }

            ComputerUnitRecord record = producer.requireComputerRecord();

            record.queueKinds[slot] = (byte) kind;

            if (kind == ComputerPlayer.REQUEST_KIND_GUARD) {
                record.queueTargets[slot] = computer.getActiveRequestGuard();
            } else {
                record.queueTargets[slot] = computer.getActiveRequestRegion();
            }

            this.popRequest(computer, true);

            int producerType = this.game.requireGameData().getBaseType(producer);

            if (producerType != ScrUnitIds.ZERG_LARVA && producerType != ScrUnitIds.ZERG_MUTALISK &&
                producerType != ScrUnitIds.ZERG_HYDRALISK) {
                ScrOrderSystem.setSecondaryOrder(producer, ScrOrderIds.TRAIN);

                return true;
            }

            phase.issueOrder(producer, ScrOrderIds.ZERG_UNIT_MORPH);

            return true;
        } else if (computer.isCampaign()) {
            return false;
        }

        this.popRequest(computer, false);

        if (!noRetry) {
            this.updateBuilding(phase, unit, instructionLimit);
        }

        return false;
    }
}
