package org.fogofwarlabs.sc4j.scr.game.ability;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnKey;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnReset;
import org.fogofwarlabs.sc4j.engine.entity.column.IntColumn;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnits;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrFlingyTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SoundIds;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerUpgrades;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Builds, launches, recalls and destroys Carrier Interceptors and Reaver Scarabs.
 * <p>
 * Each parent keeps inside and outside fighter lists with front insertion.
 * <p>
 * The Vulture and hangar state of the native unit union is registered as unit pool columns ({@link #HANGAR_COLUMNS}): the
 * Spider Mine count, the inside and outside hangar counts, the outside byte of an Interceptor or Scarab and the stored
 * byte after the counts. The columns keep their values when a slot is reused, as the union fields do; unit creation and
 * the unit reset methods set them.
 */
public final class UnitHangar {
    /**
     * The Spider Mine count BYTE of a Vulture ({@link Unit#getSpiderMineCount()}).
     */
    public static final @NotNull ColumnKey<IntColumn> SPIDER_MINE_COUNT_COLUMN = ColumnKey.ofInt("spiderMineCount", 0, ColumnReset.KEEP);
    /**
     * The number of units inside the hangar BYTE ({@link Unit#getHangarCount(boolean)}).
     */
    public static final @NotNull ColumnKey<IntColumn> INSIDE_COUNT_COLUMN = ColumnKey.ofInt("insideHangarCount", 0, ColumnReset.KEEP);
    /**
     * The number of units outside the hangar BYTE ({@link Unit#getHangarCount(boolean)}).
     */
    public static final @NotNull ColumnKey<IntColumn> OUTSIDE_COUNT_COLUMN = ColumnKey.ofInt("outsideHangarCount", 0, ColumnReset.KEEP);
    /**
     * The BYTE that is nonzero while an Interceptor or Scarab is outside its hangar ({@link Unit#getHangarOutsideByte()}).
     */
    public static final @NotNull ColumnKey<IntColumn> OUTSIDE_COLUMN = ColumnKey.ofInt("hangarOutside", 0, ColumnReset.KEEP);
    /**
     * The Carrier or Reaver BYTE after the hangar counts, only stored: save, load and EUD access it
     * ({@link Unit#getHangarSpareByte()}).
     */
    public static final @NotNull ColumnKey<IntColumn> SPARE_BYTE_COLUMN = ColumnKey.ofInt("hangarSpareByte", 0, ColumnReset.KEEP);
    /**
     * The unit pool columns of the Vulture and hangar state, in registration order.
     */
    public static final @NotNull List<@NotNull ColumnKey<?>> HANGAR_COLUMNS = List.of(
            SPIDER_MINE_COUNT_COLUMN,
            INSIDE_COUNT_COLUMN,
            OUTSIDE_COUNT_COLUMN,
            OUTSIDE_COLUMN,
            SPARE_BYTE_COLUMN
    );

    private static final int FIXED_POINT_FRACTION_BITS = 8;
    private static final int WORD_MASK = 0xFFFF;

    private static final int PREDICTION_SLOPE_DIVISOR = 4;
    private static final int PREDICTION_AXIAL_DIVISOR = 32;
    private static final int PREDICTION_MINOR_MULTIPLIER = 3;
    private static final int PREDICTION_MAJOR_DIVISOR = 8;
    private static final int PREDICTION_MIDDLE_DIVISOR = 64;
    private static final int PREDICTION_AXIAL_CORRECTION_DIVISOR = 128;
    private static final int PREDICTION_MINOR_DIVISOR = 256;

    private static final int MAXIMUM_GROUND_DISTANCE_MULTIPLIER = 2;
    private static final int SCARAB_SPLASH_RADIUS_DIVISOR = 2;
    private static final int INTERCEPTOR_RELEASE_HEALTH_DIVISOR = 2;
    private static final int SCARAB_PREDICTION_SPEED_MULTIPLIER = 2;
    private static final int ACCELERATION_AVERAGE_DIVISOR = 2;

    /**
     * The removal timer of a launched Interceptor whose parent dies: a draw of this random source in this range.
     */
    private static final int RELEASE_TIMER_RANDOM_SOURCE = 39;
    private static final int RELEASE_TIMER_MINIMUM = 15;
    private static final int RELEASE_TIMER_MAXIMUM = 45;

    /**
     * The secondary order states of {@link ScrOrderIds#TRAIN_FIGHTER}: start, retry after a failed creation, building, repairing, finished.
     */
    private static final int TRAINING_STATE_START = 0;
    private static final int TRAINING_STATE_RETRYING = 1;
    private static final int TRAINING_STATE_BUILDING = 2;
    private static final int TRAINING_STATE_REPAIRING = 3;
    private static final int TRAINING_STATE_FINISHED = 4;
    /**
     * The hit points a Carrier restores to a damaged stored Interceptor per update: {@code 1/2} point ({@code 8} fraction bits).
     */
    private static final int INTERCEPTOR_REPAIR_HIT_POINTS = 128;

    /**
     * The main order timer of a Carrier and of a Reaver after it launched a fighter.
     */
    private static final int CARRIER_LAUNCH_DELAY = 7;
    private static final int REAVER_LAUNCH_DELAY = 60;
    /**
     * The distance in pixels in front of a Reaver at which its Scarab appears.
     */
    private static final int SCARAB_LAUNCH_DISTANCE = 25;
    /**
     * The largest unsigned difference between a Reaver's heading and the direction to its target at which it launches a Scarab.
     */
    private static final int REAVER_LAUNCH_FACING_TOLERANCE = 1;
    /**
     * The elevation levels below its parent that an Interceptor takes when it is launched or ordered to attack.
     */
    private static final int FIGHTER_LAUNCH_ELEVATION_DROP = 1;

    /**
     * The order states of {@link ScrOrderIds#INTERCEPTOR_ATTACK}: launched, climbing, attacking, passing the target, turning back.
     */
    private static final int INTERCEPTOR_STATE_START = 0;
    private static final int INTERCEPTOR_STATE_CLIMBING = 1;
    private static final int INTERCEPTOR_STATE_ATTACKING = 3;
    private static final int INTERCEPTOR_STATE_PASSING = 4;
    private static final int INTERCEPTOR_STATE_TURNING = 5;
    /**
     * An Interceptor returns once its shields fall below this fraction of its full shields.
     */
    private static final int INTERCEPTOR_RETURN_SHIELD_DIVISOR = 4;
    /**
     * The tiles beyond its parent's target acquisition range within which an Interceptor keeps attacking.
     */
    private static final int INTERCEPTOR_LEASH_TILES = 2;
    /**
     * The main order timer of a launched Interceptor before it climbs, and the elevation levels it climbs.
     */
    private static final int INTERCEPTOR_LAUNCH_DELAY = 15;
    private static final int INTERCEPTOR_CLIMB = 2;
    /**
     * The distance in pixels at which an Interceptor passes its target and at which it reaches a waypoint.
     */
    private static final int INTERCEPTOR_ATTACK_RUN_RANGE = 50;
    /**
     * The factors of the random waypoint offset at launch, when passing the target and when turning back.
     */
    private static final int LAUNCH_SPREAD = 3;
    private static final int PASS_SPREAD = 1;
    private static final int TURN_SPREAD = 2;
    /**
     * The random waypoint offset of an Interceptor: a draw of this random source in this range, on each axis.
     */
    private static final int SPREAD_RANDOM_SOURCE = 11;
    private static final int SPREAD_MINIMUM = -127;
    private static final int SPREAD_MAXIMUM = 128;

    /**
     * The order states of {@link ScrOrderIds#INTERCEPTOR_RETURN}: returning, docking.
     */
    private static final int RETURN_STATE_START = 0;
    private static final int RETURN_STATE_DOCKING = 1;
    /**
     * The distance in pixels at which a returning Interceptor drops below its parent, and the levels it drops.
     */
    private static final int DOCK_APPROACH_DISTANCE = 60;
    private static final int DOCK_ELEVATION_DROP = 2;
    /**
     * The distance in pixels at which a returning fighter enters its parent.
     */
    private static final int DOCK_DISTANCE = 10;

    /**
     * The order states of {@link ScrOrderIds#SCARAB_ATTACK}: launched, waiting to collide, homing, exploding.
     */
    private static final int SCARAB_STATE_START = 0;
    private static final int SCARAB_STATE_LAUNCHED = 2;
    private static final int SCARAB_STATE_HOMING = 3;
    private static final int SCARAB_STATE_EXPLODING = 6;
    /**
     * The removal timer of a Scarab and the main order timer before it collides again.
     */
    private static final int SCARAB_LIFETIME = 90;
    private static final int SCARAB_COLLISION_DELAY = 7;
    /**
     * The range within which a Scarab aims ahead of an accelerating target.
     */
    private static final int SCARAB_PREDICTION_RANGE = 256;

    /**
     * @param units the unit type table
     * @param unit  the parent unit
     * @return the Interceptor count of a Carrier, {@code 0} for other units
     */
    public static int getInterceptorCount(@NotNull ScrUnitTypeView units, @NotNull Unit unit) {
        return units.isCarrier(unit.getUnitId()) ? unit.getHangarCount(false) + unit.getHangarCount(true) : 0;
    }

    /**
     * @param units    the unit type table
     * @param unitType the parent type
     * @return the Interceptor type for the Carrier types, the Scarab type for the Reaver types, {@code 0} otherwise
     */
    public static int getSubordinateType(@NotNull ScrUnitTypeView units, int unitType) {
        if (units.isCarrier(unitType)) {
            return ScrUnitIds.PROTOSS_INTERCEPTOR;
        }

        return units.isReaver(unitType) ? ScrUnitIds.PROTOSS_SCARAB : 0;
    }

    /**
     * Inserts a fighter at the front of a hangar list of its parent and counts it.
     *
     * @param parent  the Carrier or Reaver
     * @param fighter the unit inserted in the hangar
     * @param outside {@code true} for the list of launched fighters, {@code false} for the stored ones
     */
    public static void pushFront(@NotNull Unit parent, @NotNull Unit fighter, boolean outside) {
        Unit first = parent.getFirstHangarUnit(outside);

        fighter.setPreviousHangarUnit(null);
        fighter.setNextHangarUnit(first);

        if (first != null) {
            first.setPreviousHangarUnit(fighter);
        }

        parent.setFirstHangarUnit(outside, fighter);
        parent.setHangarCount(outside, parent.getHangarCount(outside) + 1);
    }

    /**
     * Counts the Interceptors and Scarabs in the build queue, from its head to the first empty slot.
     *
     * @param producer the Carrier or Reaver
     * @return the number of queued fighters
     */
    private static int getQueuedFighterCount(@NotNull Game game, @NotNull Unit producer) {
        int head = producer.getBuildQueueIndex();
        int count = 0;

        for (int offset = 0; offset < Unit.BUILD_QUEUE_SLOTS; offset++) {
            int queuedType = producer.getBuildQueueUnitType((head + offset) % Unit.BUILD_QUEUE_SLOTS);

            if (queuedType == ScrUnitIds.NONE) {
                break;
            }

            int baseType = game.getBaseType(queuedType);

            if (baseType == ScrUnitIds.PROTOSS_INTERCEPTOR || baseType == ScrUnitIds.PROTOSS_SCARAB) {
                count++;
            }
        }

        return count;
    }

    /**
     * Computes the octagonal distance approximation used for target prediction.
     *
     * @param horizontal the horizontal offset
     * @param vertical   the vertical offset
     * @return the approximate distance
     */
    private static int getPredictionDistance(int horizontal, int vertical) {
        int largerOffset = Math.abs(horizontal);
        int smallerOffset = Math.abs(vertical);

        if (largerOffset < smallerOffset) {
            int previousLargerOffset = largerOffset;

            largerOffset  = smallerOffset;
            smallerOffset = previousLargerOffset;
        }

        if (largerOffset / PREDICTION_SLOPE_DIVISOR < smallerOffset) {
            return largerOffset + largerOffset / PREDICTION_AXIAL_DIVISOR +
                   smallerOffset * PREDICTION_MINOR_MULTIPLIER / PREDICTION_MAJOR_DIVISOR - largerOffset / PREDICTION_AXIAL_DIVISOR +
                   smallerOffset * PREDICTION_MINOR_MULTIPLIER / PREDICTION_MIDDLE_DIVISOR - largerOffset / PREDICTION_AXIAL_CORRECTION_DIVISOR +
                   smallerOffset * PREDICTION_MINOR_MULTIPLIER / PREDICTION_MINOR_DIVISOR;
        }

        return largerOffset + largerOffset / PREDICTION_AXIAL_DIVISOR + smallerOffset * PREDICTION_MINOR_MULTIPLIER / PREDICTION_MINOR_DIVISOR;
    }

    /**
     * Unlinks a fighter from a hangar list of its parent and uncounts it, whatever the parent type.
     * <p>
     * The fighter keeps its own parent and list links.
     *
     * @param parent           the parent that lists the fighter
     * @param fighter          the Interceptor or Scarab
     * @param outside          {@code true} for the list of launched fighters, {@code false} for the stored ones
     * @param replaceListFront {@code true} to replace the parent list front with the next fighter, {@code false} to keep it
     */
    private static void remove(@NotNull Unit parent, @NotNull Unit fighter, boolean outside, boolean replaceListFront) {
        Unit previous = fighter.getPreviousHangarUnit();
        Unit next = fighter.getNextHangarUnit();

        if (replaceListFront) {
            parent.setFirstHangarUnit(outside, next);
        }

        if (previous != null) {
            previous.setNextHangarUnit(next);
        }

        parent.setHangarCount(outside, parent.getHangarCount(outside) - 1);

        if (next != null) {
            next.setPreviousHangarUnit(previous);
        }
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitHangar} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitHangar(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Hides a unit, inserts it at the front of its parent's inside list and marks it stored, regardless of its type.
     * <p>
     * The caller assigns the parent before storage; the inside count keeps its byte wrapping.
     *
     * @param parent the parent whose inside list receives the unit
     * @param unit   the unit to store
     */
    public void storeInside(@NotNull Unit parent, @NotNull Unit unit) {
        this.game.getUnitLifecycle().hide(unit, true);
        pushFront(parent, unit, false);
        unit.setHangarOutside(false);
    }

    /**
     * Checks the producer type, queued fighters and hangar capacity ({@link UnitQueries#getHangarCapacity}).
     *
     * @param producer the Carrier or Reaver
     * @param unitType the Interceptor or Scarab type
     * @return {@code true} if the hangar has room for another fighter, {@code false} otherwise
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if player upgrade storage is missing
     */
    public boolean canBuildFighter(@NotNull Unit producer, int unitType) {
        int producerType = producer.getUnitId();
        PlayerUpgrades upgrades = this.game.getPlayerTable().getPlayerUpgrades();

        if (upgrades == null) {
            throw Exceptions.newUnsupportedOperationException("Hangar capacity requires player upgrade levels.");
        }

        if (this.game.getBaseType(unitType) == ScrUnitIds.PROTOSS_INTERCEPTOR) {
            if (!this.game.requireGameData().getUnitTypes().isCarrier(producerType)) {
                return false;
            }

            int maximum = this.game.getUnitQueries().getHangarCapacity(producer);

            return getInterceptorCount(this.game.requireGameData().getUnitTypes(), producer) + getQueuedFighterCount(this.game, producer) < maximum;
        }

        if (!this.game.requireGameData().getUnitTypes().isReaver(producerType)) {
            return false;
        }

        int maximum = this.game.getUnitQueries().getHangarCapacity(producer);

        return producer.getHangarCount(false) + getQueuedFighterCount(this.game, producer) < maximum;
    }


    /**
     * Queues an Interceptor or Scarab on every eligible selected Carrier or Reaver.
     *
     * @param owner the command player
     * @return {@code true} if any selected unit queued a fighter, {@code false} otherwise
     * @throws UnsupportedOperationException if selection storage is missing
     */
    public boolean trainFighter(int owner) {
        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Fighter training requires initialized selection storage.");
        }

        boolean accepted = false;

        for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
            Unit unit = selection.getSelectedUnit(owner, position);

            if (unit == null) {
                break;
            }

            if (unit.isDestroyed()) {
                continue;
            }

            if (unit.getOwnerIndex() != owner) {
                continue;
            }

            int buildType = this.game.requireGameData().getUnitTypes().isCarrier(unit.getUnitId())
                            ? ScrUnitIds.PROTOSS_INTERCEPTOR
                            : this.game.requireGameData().getUnitTypes().isReaver(unit.getUnitId()) ? ScrUnitIds.PROTOSS_SCARAB : ScrUnitIds.NONE;

            if (buildType == ScrUnitIds.NONE || !this.game.getUnitProduction().canBuild(unit, buildType)) {
                continue;
            }

            if (this.game.getEngineGame().getProduction().enqueueUnit(unit, ScrUnits.get(buildType)) < 0) {
                continue;
            }

            if (unit.getSecondaryOrderState() != TRAINING_STATE_BUILDING) {
                ScrOrderSystem.restartSecondaryOrder(unit, ScrOrderIds.TRAIN_FIGHTER);
            }

            accepted = true;
        }

        return accepted;
    }

    /**
     * Issues {@link ScrOrderIds#CARRIER_STOP} or {@link ScrOrderIds#REAVER_STOP} to every selected unit that can receive it.
     *
     * @param owner            the command player
     * @param orderIdentifier  {@link ScrOrderIds#CARRIER_STOP} for Carriers or {@link ScrOrderIds#REAVER_STOP} for Reavers
     * @param instructionLimit the order activation budget
     * @return {@code true} if any selected unit accepted the order, {@code false} otherwise
     * @throws UnsupportedOperationException if selection storage is missing
     */
    public boolean stop(int owner, int orderIdentifier, int instructionLimit) {
        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Hangar stop requires initialized selection storage.");
        }

        boolean accepted = false;

        for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
            Unit unit = selection.getSelectedUnit(owner, position);

            if (unit == null) {
                break;
            }

            if (unit.isDestroyed()) {
                continue;
            }

            if (!this.game.getUnitOrderEligibility().canReceiveOrder(unit, orderIdentifier, owner)) {
                continue;
            }

            this.game.getOrders().issueUntargeted(unit, orderIdentifier, instructionLimit);

            accepted = true;
        }

        return accepted;
    }

    /**
     * Builds the queued fighter and stores it hidden inside the parent, then lets a Carrier repair a damaged stored Interceptor per update.
     * <p>
     * Non-parent and empty-queue guards return before creation validation.
     *
     * @param unit             the Carrier or Reaver
     * @param instructionLimit the creation, completion and damage overlay script budget
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if training lacks accounting, the creation context, the unit pool or the owner
     * @throws IllegalStateException         if the unit has no sprite
     */
    public void updateTrainFighter(@NotNull Unit unit, int instructionLimit) {
        int unitType = unit.getUnitId();

        if (!this.game.requireGameData().getUnitTypes().hasHangar(unitType)) {
            return;
        }

        int trainingState = unit.getSecondaryOrderState();

        if (trainingState == TRAINING_STATE_START || trainingState == TRAINING_STATE_RETRYING) {
            int buildType = UnitQueries.getQueuedType(unit);

            if (buildType == ScrUnitIds.NONE) {
                unit.setSecondaryOrderState(TRAINING_STATE_REPAIRING);
                unit.setCurrentBuildUnit(null);

                return;
            }

            Unit built = null;
            UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();
            UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();
            UnitPool pool = this.game.getUnitSystem().getUnitPool();

            if (accounting == null || context == null || pool == null) {
                throw Exceptions.newUnsupportedOperationException("Fighter training requires accounting, creation context and the unit pool.");
            }

            // The supply test shows its error only on the first attempt of the queue head.
            if (this.game.getCostTests().testSupply(this.game, unit.getOwnerIndex(), buildType, trainingState == TRAINING_STATE_START)) {
                Sprite sprite = unit.requireSprite();

                built = this.game.getUnitStorageConstructor().create(context,
                                                                     accounting,
                                                                     buildType,
                                                                     sprite.getX(), sprite.getY(),
                                                                     unit.getOwnerIndex(),
                                                                     pool.getCreationTag(unit.getIndex()),
                                                                     instructionLimit);

                // A failed creation shows the pending creation message; the attempt then retries silently.
                if (built == null) {
                    this.game.getUnitStorageConstructor().notifyCreationFailure(unit.getOwnerIndex());
                }
            }

            unit.setCurrentBuildUnit(built);

            if (built != null) {
                built.setHangarParent(unit);
                unit.setSecondaryOrderState(TRAINING_STATE_BUILDING);
            } else {
                unit.setSecondaryOrderState(TRAINING_STATE_RETRYING);
            }
        } else if (trainingState == TRAINING_STATE_BUILDING) {
            Unit built = unit.getCurrentBuildUnit();

            if (built == null) {
                unit.setSecondaryOrderState(TRAINING_STATE_START);

                return;
            }

            this.game.getUnitHealth().setHitPoints(built,
                                                   built.getHitPoints() + this.game.getUnitBuildTime().getHitPointIncrement(built),
                                                   instructionLimit);

            int remaining = built.getRemainingBuildTime();

            if (remaining != 0) {
                this.game.getUnitBuildTime().advance(built);

                return;
            }

            UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();
            Player owner = this.game.getPlayerTable().getPlayer(built.getOwnerIndex());

            if (context == null || owner == null) {
                throw Exceptions.newUnsupportedOperationException("Fighter completion requires the creation context and owner.");
            }

            this.game.getUnitCompletion().initializeBeforePlacement(context, built, instructionLimit);
            this.game.getUnitCompletion().completeAfterPlacement(context, built, owner.getType(), instructionLimit);

            if ((built.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
                return;
            }

            this.storeInside(unit, built);

            UnitLifecycle.clearCurrentQueueSlot(unit);
            unit.setBuildQueueIndex(unit.getBuildQueueIndex() + 1);

            if (unit.getBuildQueueIndex() >= Unit.BUILD_QUEUE_SLOTS) {
                unit.setBuildQueueIndex(Unit.FIRST_QUEUE_SLOT);
            }

            unit.setCurrentBuildUnit(null);
            unit.setSecondaryOrderState(TRAINING_STATE_START);
        } else if (trainingState == TRAINING_STATE_REPAIRING) {
            if (!this.game.requireGameData().getUnitTypes().isCarrier(unitType)) {
                unit.setSecondaryOrderState(TRAINING_STATE_FINISHED);

                return;
            }

            for (Unit fighter = unit.getFirstHangarUnit(false); fighter != null; fighter = fighter.getNextHangarUnit()) {
                if (fighter.getHitPoints() >= this.game.getUnitHealth().getMaximumHitPoints(fighter)) {
                    continue;
                }

                this.game.getUnitHealth().setHitPoints(fighter, fighter.getHitPoints() + INTERCEPTOR_REPAIR_HIT_POINTS, instructionLimit);

                break;
            }
        }
    }

    /**
     * Acquires or follows a target and launches an Interceptor for {@link ScrOrderIds#CARRIER}, {@link ScrOrderIds#CARRIER_FIGHT} and
     * {@link ScrOrderIds#CARRIER_HOLD_POSITION}.
     *
     * @param unit             the Carrier
     * @param instructionLimit the order and script budget
     * @throws IllegalStateException if a fighter or the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void updateCarrier(@NotNull Unit unit, int instructionLimit) {
        if (!this.attack(unit, TerrainTileset.TILE_SIZE * this.game.getUnitOrders().getTargetAcquisitionRange(unit), 0, instructionLimit)) {
            if (unit.getComputerRecord() != null && unit.getOrderTimer() == 0) {
                this.game.getOrders().issueUntargeted(unit, ScrOrderIds.COMPUTER_AI, instructionLimit);
            }

            return;
        }

        Unit fighter = this.releaseFighter(unit);

        if (fighter == null) {
            return;
        }

        fighter.setShieldPoints(this.game.requireGameData().getUnitTypes().getShieldAmount(fighter.getUnitId()) << FIXED_POINT_FRACTION_BITS);
        fighter.requireSprite().setElevationLevel(unit.requireSprite().getElevationLevel() - FIGHTER_LAUNCH_ELEVATION_DROP);
        this.game.getOrders().issue(fighter,
                                    this.game.requireGameData().getUnitTypes().getAttackUnitOrder(fighter.getUnitId()),
                                    unit.getOrderTargetUnit(),
                                    0, 0,
                                    ScrUnitIds.NONE,
                                    false,
                                    instructionLimit);
        unit.setOrderTimer(CARRIER_LAUNCH_DELAY);
    }

    /**
     * Acquires or follows a target and launches a Scarab for {@link ScrOrderIds#REAVER}, {@link ScrOrderIds#REAVER_FIGHT} and
     * {@link ScrOrderIds#REAVER_HOLD_POSITION}.
     *
     * @param unit             the Reaver
     * @param instructionLimit the order and script budget
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void updateReaver(@NotNull Unit unit, int instructionLimit) {
        int range = TerrainTileset.TILE_SIZE * this.game.getUnitOrders().getTargetAcquisitionRange(unit);

        if (!this.attack(unit, range, range * MAXIMUM_GROUND_DISTANCE_MULTIPLIER, instructionLimit)) {
            if (unit.getComputerRecord() != null && unit.getOrderTimer() == 0) {
                this.game.getOrders().issueUntargeted(unit, ScrOrderIds.COMPUTER_AI, instructionLimit);
            }

            return;
        }

        unit.setDirectionTargetX(unit.getOrderTargetX());
        unit.setDirectionTargetY(unit.getOrderTargetY());

        Sprite sprite = unit.requireSprite();
        int direction = FlingyMotionUtils.calculateDirection((short) unit.getDirectionTargetX() - sprite.getX(),
                                                             (short) unit.getDirectionTargetY() - sprite.getY());

        if (Long.compareUnsigned((long) unit.getHeading() - direction, REAVER_LAUNCH_FACING_TOLERANCE) <= 0) {
            if ((unit.getStatus() & UnitStatusFlags.CANNOT_ATTACK) == 0 && unit.getHangarCount(true) == 0) {
                Unit fighter = this.releaseFighter(unit);

                if (fighter != null) {
                    this.game.getOrders().issue(fighter,
                                                this.game.requireGameData().getUnitTypes().getAttackUnitOrder(fighter.getUnitId()),
                                                unit.getOrderTargetUnit(),
                                                0, 0,
                                                ScrUnitIds.NONE,
                                                false,
                                                instructionLimit);
                    unit.setOrderTimer(REAVER_LAUNCH_DELAY);
                }
            }
        } else {
            unit.setPrimaryOrderCountdown(0);
        }
    }

    /**
     * Validates the attack target and transitions to the fight order of the attack order, preserving attack status bits:
     * {@link ScrOrderIds#CARRIER_ATTACK} sends a Carrier's launched Interceptors at the target and continues with
     * {@link ScrOrderIds#CARRIER_FIGHT}, {@link ScrOrderIds#REAVER_ATTACK} continues with {@link ScrOrderIds#REAVER_FIGHT}, whatever the unit type.
     *
     * @param unit             the unit of the attack order
     * @param instructionLimit the order and script budget
     * @throws IllegalStateException if the target has no sprite
     */
    public void updateParentAttack(@NotNull Unit unit, int instructionLimit) {
        Unit target = unit.getOrderTargetUnit();

        if (target == null) {
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        if (!this.game.getEngineGame().getCombat().canAttack(unit, target)) {
            this.game.getOrders().issue(unit,
                                        ScrOrderIds.MOVE,
                                        null,
                                        (short) unit.getOrderTargetX(), (short) unit.getOrderTargetY(),
                                        ScrUnitIds.NONE,
                                        false,
                                        instructionLimit);

            return;
        }

        int preserved = unit.getStatus() & (UnitStatusFlags.READY_TO_ATTACK | UnitStatusFlags.AUTOMATIC_ATTACK_STARTED);
        boolean carrierOrder = unit.getPrimaryOrder() == ScrOrderIds.CARRIER_ATTACK;

        if (carrierOrder) {
            this.attackWithInterceptors(unit, instructionLimit);
        }

        Sprite targetSprite = target.requireSprite();

        this.game.getOrders().queueFront(unit,
                                         carrierOrder ? ScrOrderIds.CARRIER_FIGHT : ScrOrderIds.REAVER_FIGHT,
                                         target,
                                         targetSprite.getX(), targetSprite.getY(),
                                         ScrUnitIds.NONE);
        this.game.getOrders().activateNext(unit, instructionLimit);
        unit.setStatus(unit.getStatus() & ~(UnitStatusFlags.READY_TO_ATTACK | UnitStatusFlags.AUTOMATIC_ATTACK_STARTED) | preserved);
    }

    /**
     * Stops the unit and continues with the idle order of the stop order, whatever the unit type: {@link ScrOrderIds#CARRIER_STOP} recalls a
     * Carrier's launched Interceptors and continues with {@link ScrOrderIds#CARRIER}, {@link ScrOrderIds#REAVER_STOP} makes the launched units
     * self-destruct and continues with {@link ScrOrderIds#REAVER}.
     *
     * @param unit             the unit of the stop order
     * @param instructionLimit the order and script budget
     */
    public void updateParentStop(@NotNull Unit unit, int instructionLimit) {
        this.game.getUnitPointMovement().stopAndFaceDestination(unit);

        boolean carrierOrder = unit.getPrimaryOrder() == ScrOrderIds.CARRIER_STOP;

        if (!carrierOrder || this.game.requireGameData().getUnitTypes().isCarrier(unit.getUnitId())) {
            for (Unit fighter = unit.getFirstHangarUnit(true); fighter != null; ) {
                Unit next = fighter.getNextHangarUnit();

                this.game.getOrders().issueUntargeted(fighter,
                                                      carrierOrder ? ScrOrderIds.INTERCEPTOR_RETURN : ScrOrderIds.SELF_DESTRUCTING,
                                                      instructionLimit);

                fighter = next;
            }
        }

        Unit target = unit.getOrderTargetUnit();
        int order = carrierOrder ? ScrOrderIds.CARRIER : ScrOrderIds.REAVER;

        if (target != null) {
            this.game.getOrders().issue(unit, order, target, 0, 0, ScrUnitIds.NONE, false, instructionLimit);
        } else {
            this.game.getOrders().issueUntargeted(unit, order, instructionLimit);
        }
    }

    /**
     * Advances an Interceptor through launch, climb, attack, pass and turn phases, returning when shields or range require it.
     *
     * @param unit             the Interceptor
     * @param instructionLimit the order, script and weapon budget
     * @throws IllegalStateException if the target or the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void updateInterceptorAttack(@NotNull Unit unit, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        Unit parent = unit.getHangarParent();

        if (parent != null &&
            unit.getShieldPoints() < (units.getShieldAmount(unit.getUnitId()) << FIXED_POINT_FRACTION_BITS) / INTERCEPTOR_RETURN_SHIELD_DIVISOR) {
            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.INTERCEPTOR_RETURN, instructionLimit);

            return;
        }

        Unit target = unit.getOrderTargetUnit();

        if (target == null || (target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0) {
            if (!this.game.getUnitAttack().attackAutomaticTarget(unit, instructionLimit)) {
                this.game.getOrders().issueUntargeted(unit, ScrOrderIds.INTERCEPTOR_RETURN, instructionLimit);

                return;
            }

            target = unit.getOrderTargetUnit();
        }

        Sprite targetSprite = target.requireSprite();

        unit.setOrderTargetX(targetSprite.getX());
        unit.setOrderTargetY(targetSprite.getY());

        if (parent != null) {
            int range = TerrainTileset.TILE_SIZE * (this.game.getUnitOrders().getTargetAcquisitionRange(parent) + INTERCEPTOR_LEASH_TILES);

            if (!this.game.getUnitTargeting().isInRange(parent,
                                                        target,
                                                        range + this.game.getUnitTargeting().getTargetMovementRange(parent, target))) {
                this.game.getOrders().issueUntargeted(unit, ScrOrderIds.INTERCEPTOR_RETURN, instructionLimit);

                return;
            }
        }

        if (unit.getPrimaryOrderState() == INTERCEPTOR_STATE_START) {
            if (parent == null) {
                this.game.getUnitLifecycle().kill(unit, instructionLimit);

                return;
            }

            this.game.getUnitSounds().play(SoundIds.INTERCEPTOR_LAUNCH, unit);
            this.setInterceptorMoveTarget(unit, LAUNCH_SPREAD);
            unit.setOrderTimer(INTERCEPTOR_LAUNCH_DELAY);
            unit.setPrimaryOrderState(INTERCEPTOR_STATE_CLIMBING);
        } else if (unit.getPrimaryOrderState() == INTERCEPTOR_STATE_CLIMBING) {
            if (unit.getOrderTimer() == 0) {
                Sprite sprite = unit.requireSprite();

                sprite.setElevationLevel(sprite.getElevationLevel() + INTERCEPTOR_CLIMB);
                unit.setPrimaryOrderState(INTERCEPTOR_STATE_ATTACKING);
            }
        }

        if (unit.getPrimaryOrderState() == INTERCEPTOR_STATE_ATTACKING) {
            if (this.game.getUnitTargeting().isInWeaponMovementRange(unit, target)) {
                this.game.getUnitAttack().fireWeapon(unit, instructionLimit);
            }

            if (this.game.getUnitTargeting().isInRange(unit,
                                                       target,
                                                       INTERCEPTOR_ATTACK_RUN_RANGE +
                                                       this.game.getUnitTargeting().getTargetMovementRange(unit, target))) {
                this.setInterceptorMoveTarget(unit, PASS_SPREAD);
                unit.setPrimaryOrderState(INTERCEPTOR_STATE_PASSING);
            } else {
                int positionX = (short) unit.getOrderTargetX();
                int positionY = (short) unit.getOrderTargetY();

                this.game.getEngineGame().getMovement().setDestination(unit, positionX, positionY);
                unit.setDirectionTargetX(positionX);
                unit.setDirectionTargetY(positionY);
            }
        } else if (unit.getPrimaryOrderState() == INTERCEPTOR_STATE_PASSING) {
            int destinationX = (short) unit.getMovementDestinationX();
            int destinationY = (short) unit.getMovementDestinationY();

            if (RegionPathSearch.calculateDistance(unit.getFixedPositionX(), destinationX << FIXED_POINT_FRACTION_BITS,
                                                   unit.getFixedPositionY(), destinationY << FIXED_POINT_FRACTION_BITS) >>
                FIXED_POINT_FRACTION_BITS <= INTERCEPTOR_ATTACK_RUN_RANGE) {
                this.setInterceptorMoveTarget(unit, TURN_SPREAD);
                unit.setPrimaryOrderState(INTERCEPTOR_STATE_TURNING);
            }
        } else if (unit.getPrimaryOrderState() == INTERCEPTOR_STATE_TURNING) {
            int destinationX = (short) unit.getMovementDestinationX();
            int destinationY = (short) unit.getMovementDestinationY();

            if (RegionPathSearch.calculateDistance(unit.getFixedPositionX(), destinationX << FIXED_POINT_FRACTION_BITS,
                                                   unit.getFixedPositionY(), destinationY << FIXED_POINT_FRACTION_BITS) >>
                FIXED_POINT_FRACTION_BITS <= INTERCEPTOR_ATTACK_RUN_RANGE) {
                int positionX = (short) unit.getOrderTargetX();
                int positionY = (short) unit.getOrderTargetY();

                this.game.getEngineGame().getMovement().setDestination(unit, positionX, positionY);
                unit.setDirectionTargetX(positionX);
                unit.setDirectionTargetY(positionY);
                unit.setPrimaryOrderState(INTERCEPTOR_STATE_ATTACKING);
            }
        }
    }

    /**
     * Sets the move target of an Interceptor to a random point around its order position, mirrored back into the map when it would leave it.
     *
     * @param unit       the Interceptor
     * @param multiplier the factor of the random offset
     * @throws NullPointerException if the world was built without game data
     */
    private void setInterceptorMoveTarget(@NotNull Unit unit, int multiplier) {
        int offsetX = this.game.getRandom().next(SPREAD_RANDOM_SOURCE, SPREAD_MINIMUM, SPREAD_MAXIMUM) * multiplier;
        int offsetY = this.game.getRandom().next(SPREAD_RANDOM_SOURCE, SPREAD_MINIMUM, SPREAD_MAXIMUM) * multiplier;
        int targetX = (short) unit.getOrderTargetX();
        int targetY = (short) unit.getOrderTargetY();
        int positionX = targetX + offsetX;
        int positionY = targetY + offsetY;
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();

        if (positionX < units.getDimensionLeft(unitType) ||
            positionX >= this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE - units.getDimensionRight(unitType)) {
            positionX = targetX - offsetX;
        }

        if (positionY < units.getDimensionUp(unitType) ||
            positionY >= this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE - units.getDimensionDown(unitType)) {
            positionY = targetY - offsetY;
        }

        positionX = UnitPositioning.clampCoordinate(positionX,
                                                    units.getDimensionLeft(unitType), units.getDimensionRight(unitType),
                                                    this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE,
                                                    0);
        positionY = UnitPositioning.clampCoordinate(positionY,
                                                    units.getDimensionUp(unitType), units.getDimensionDown(unitType),
                                                    this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE,
                                                    UnitPositioning.MAP_BOTTOM_MARGIN);

        this.game.getEngineGame().getMovement().setDestination(unit, positionX, positionY);
        unit.setDirectionTargetX(positionX);
        unit.setDirectionTargetY(positionY);
    }

    /**
     * Flies back and docks into the parent's inside list.
     *
     * @param unit             the returning Interceptor or Scarab
     * @param instructionLimit the order and script budget
     * @throws IllegalStateException if the parent or the unit has no sprite
     */
    public void updateInterceptorReturn(@NotNull Unit unit, int instructionLimit) {
        Unit parent = unit.getHangarParent();

        if (parent == null) {
            this.game.getUnitLifecycle().kill(unit, instructionLimit);

            return;
        }

        Sprite parentSprite = parent.requireSprite();

        if (unit.getPrimaryOrderState() == RETURN_STATE_START) {
            int parentX = parentSprite.getX();
            int parentY = parentSprite.getY();

            if (RegionPathSearch.calculateDistance(unit.getFixedPositionX(), parentX << FIXED_POINT_FRACTION_BITS,
                                                   unit.getFixedPositionY(), parentY << FIXED_POINT_FRACTION_BITS) >>
                FIXED_POINT_FRACTION_BITS <= DOCK_APPROACH_DISTANCE) {
                unit.setPrimaryOrderState(RETURN_STATE_DOCKING);

                if (this.game.getBaseType(unit) == ScrUnitIds.PROTOSS_INTERCEPTOR) {
                    unit.requireSprite().setElevationLevel(parentSprite.getElevationLevel() - DOCK_ELEVATION_DROP);
                }
            }
        }

        int parentX = parentSprite.getX();
        int parentY = parentSprite.getY();

        if (RegionPathSearch.calculateDistance(unit.getFixedPositionX(), parentX << FIXED_POINT_FRACTION_BITS,
                                               unit.getFixedPositionY(), parentY << FIXED_POINT_FRACTION_BITS) >>
            FIXED_POINT_FRACTION_BITS <= DOCK_DISTANCE) {
            remove(parent, unit, true, unit.getPreviousHangarUnit() == null);
            pushFront(parent, unit, false);
            unit.setHangarOutside(false);
            this.game.getUnitLifecycle().hide(unit, true);
            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.NOTHING, instructionLimit);
        } else {
            this.game.getEngineGame().getMovement().setDestination(unit, parentSprite.getX(), parentSprite.getY());
            unit.setDirectionTargetX(parentSprite.getX());
            unit.setDirectionTargetY(parentSprite.getY());
        }
    }

    /**
     * Chases the target until splash range, starts the explosion animation and dies when the action signal is set.
     * <p>
     * Startup ignores the core movement result before storing the launched state.
     *
     * @param unit             the Scarab
     * @param instructionLimit the order and script budget
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void updateScarabAttack(@NotNull Unit unit, int instructionLimit) {
        Unit target = unit.getOrderTargetUnit();
        int orderState = unit.getPrimaryOrderState();

        if (orderState == SCARAB_STATE_START) {
            if (unit.getRemainingLifetime() == 0 || SCARAB_LIFETIME < unit.getRemainingLifetime()) {
                unit.setRemainingLifetime(SCARAB_LIFETIME);
            }

            unit.setStatus(unit.getStatus() | UnitStatusFlags.NO_COLLIDE);
            unit.setOrderTimer(SCARAB_COLLISION_DELAY);

            if (target != null) {
                this.game.getEngineGame().getMovement().setTargetUnit(unit, target);
            } else {
                int positionX = (short) unit.getOrderTargetX();
                int positionY = (short) unit.getOrderTargetY();

                this.game.getEngineGame().getMovement().setDestination(unit, positionX, positionY);
                unit.setDirectionTargetX(positionX);
                unit.setDirectionTargetY(positionY);
            }

            unit.setPrimaryOrderState(SCARAB_STATE_LAUNCHED);
        } else if (orderState == SCARAB_STATE_LAUNCHED || orderState == SCARAB_STATE_HOMING) {
            if (orderState == SCARAB_STATE_LAUNCHED && unit.getOrderTimer() == 0) {
                unit.setStatus(unit.getStatus() & ~UnitStatusFlags.NO_COLLIDE);
                unit.setPrimaryOrderState(SCARAB_STATE_HOMING);
            }

            boolean atTarget;

            if (target != null) {
                if (this.game.getUnitTargeting().isInRange(unit, target, SCARAB_PREDICTION_RANGE) &&
                    (target.getMovementFlags() & MovementFlags.ACCELERATING) != 0) {
                    this.moveAheadOfTarget(unit, target);
                } else {
                    this.game.getEngineGame().getCombat().pursueTarget(unit, target, instructionLimit);
                }

                int weapon = this.game.requireGameData().getUnitTypes().getGroundWeapon(unit.getUnitId());

                atTarget = this.game.getUnitTargeting().isInRange(unit,
                                                                  target,
                                                                  this.game.requireGameData().getWeaponTypes().getInnerSplashRadius(weapon) /
                                                                  SCARAB_SPLASH_RADIUS_DIVISOR);
            } else {
                atTarget = this.game.getEngineGame().getMovement().isAtDestination(unit);
            }

            if (atTarget) {
                Sprite sprite = unit.requireSprite();

                unit.setOrderTargetX(sprite.getX());
                unit.setOrderTargetY(sprite.getY());
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_SPECIAL_STATE_1, 0, instructionLimit);
                unit.setPrimaryOrderState(SCARAB_STATE_EXPLODING);
            }
        } else if (orderState == SCARAB_STATE_EXPLODING && (unit.getOrderSignal() & Unit.ORDER_SIGNAL_ACTION_DONE) != 0) {
            unit.setStatus(unit.getStatus() | UnitStatusFlags.LIFETIME_EXPIRED);
            this.game.getUnitLifecycle().kill(unit, instructionLimit);
        }
    }

    /**
     * Cleans up a dying hangar unit: a dying Carrier or Reaver of any type releases its hangar, and a dying completed Interceptor or Scarab leaves
     * the hangar list of its parent, whatever the parent type, or clears its list links when it has no parent.
     *
     * @param unit             the dying unit
     * @param instructionLimit the order budget
     */
    public void destroy(@NotNull Unit unit, int instructionLimit) {
        int unitType = unit.getUnitId();

        if (this.game.requireGameData().getUnitTypes().hasHangar(unitType)) {
            this.releaseHangar(unit, instructionLimit);

            return;
        }

        int baseType = this.game.getBaseType(unitType);

        if ((baseType != ScrUnitIds.PROTOSS_INTERCEPTOR && baseType != ScrUnitIds.PROTOSS_SCARAB) ||
            (unit.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
            return;
        }

        Unit parent = unit.getHangarParent();

        if (parent == null) {
            unit.setNextHangarUnit(null);
            unit.setPreviousHangarUnit(null);
        } else {
            remove(parent, unit, unit.isHangarOutside(), unit.getPreviousHangarUnit() == null);
        }
    }

    /**
     * Moves a unit toward the point where a moving target is predicted to be when the unit arrives, or toward the target's destination while the
     * target brakes.
     *
     * @param unit   the chasing unit
     * @param target the chased unit
     * @throws IllegalStateException if the target or chasing unit has no sprite
     */
    public void moveAheadOfTarget(@NotNull Unit unit, @NotNull Unit target) {
        Sprite targetSprite = target.requireSprite();
        Sprite sprite = unit.requireSprite();
        int targetX = targetSprite.getX();
        int targetY = targetSprite.getY();
        int speed = (unit.getTopSpeed() * SCARAB_PREDICTION_SPEED_MULTIPLIER) >> FIXED_POINT_FRACTION_BITS;

        if ((target.getPositionX() != target.getMovementDestinationX() || target.getPositionY() != target.getMovementDestinationY()) && speed != 0) {
            if ((target.getMovementFlags() & MovementFlags.BRAKING) != 0) {
                targetX = (short) target.getMovementDestinationX();
                targetY = (short) target.getMovementDestinationY();
            } else {
                int time = getPredictionDistance(targetSprite.getX() - sprite.getX(), targetSprite.getY() - sprite.getY()) / speed;
                int predictedSpeed = unit.getMovementControl() == ScrFlingyTypeView.MOVEMENT_CONTROL_ISCRIPT
                                     ? target.getTopSpeed()
                                     : Math.min(target.getNextSpeed() + target.getAcceleration() * time / ACCELERATION_AVERAGE_DIVISOR,
                                                target.getTopSpeed());
                long vector = FlingyMotionUtils.calculateDirectionVector(target.getTargetHeading(), predictedSpeed * time);

                targetX = Math.max(0,
                                   Math.min(this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE - 1,
                                            targetSprite.getX() + ((int) vector >> FIXED_POINT_FRACTION_BITS)));
                targetY = Math.max(0,
                                   Math.min(this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE - 1,
                                            targetSprite.getY() +
                                            ((int) (vector >>> FlingyMotionUtils.DIRECTION_VECTOR_VERTICAL_SHIFT) >> FIXED_POINT_FRACTION_BITS)));
            }
        }

        if (unit.getMovementDestinationX() != (targetX & WORD_MASK) || unit.getMovementDestinationY() != (targetY & WORD_MASK)) {
            unit.setMovementDestinationX(targetX);
            unit.setMovementDestinationY(targetY);
            unit.setMovementTargetUnit(null);
            unit.setMovementWaypointX(targetX);
            unit.setMovementWaypointY(targetY);
            unit.setMovementFlags(unit.getMovementFlags() & ~MovementFlags.BRAKING | MovementFlags.ORDERED);
        }

        unit.setDirectionTargetX(targetX);
        unit.setDirectionTargetY(targetY);
    }

    /**
     * Creates a subordinate in a free unit pool slot at its parent's sprite position with the parent's creation tag. The subordinate is completed,
     * linked to its parent and hidden in the inside list. A failed construction adds nothing.
     *
     * @param parent           the Carrier or Reaver
     * @param subordinateType  the subordinate type ({@link #getSubordinateType(ScrUnitTypeView, int)})
     * @param instructionLimit the creation and completion budget
     * @return the stored subordinate, or {@code null} if construction failed
     * @throws UnsupportedOperationException if the accounting, the creation context or the owner is missing, or the unit pool is not initialized
     * @throws IllegalStateException         if the parent has no sprite
     */
    public @Nullable Unit createStoredSubordinate(@NotNull Unit parent, int subordinateType, int instructionLimit) {
        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();
        UnitCreationContext creation = this.game.getUnitSystem().getUnitCreationContext();
        Player owner = this.game.getPlayerTable().getPlayer(parent.getOwnerIndex());

        if (accounting == null || creation == null || owner == null) {
            throw Exceptions.newUnsupportedOperationException("Hangar subordinates require accounting and the creation context.");
        }

        Sprite sprite = parent.requireSprite();
        Unit fighter = this.game.getUnitStorageConstructor().create(creation,
                                                                    accounting,
                                                                    subordinateType,
                                                                    sprite.getX(), sprite.getY(),
                                                                    parent.getOwnerIndex(),
                                                                    this.game.getUnitSystem().requireUnitPool().getCreationTag(parent.getIndex()),
                                                                    instructionLimit);

        if (fighter == null) {
            return null;
        }

        this.game.getUnitCompletion().initializeBeforePlacement(creation, fighter, instructionLimit);
        this.game.getUnitCompletion().completeAfterPlacement(creation, fighter, owner.getType(), instructionLimit);
        fighter.setHangarParent(parent);
        this.storeInside(parent, fighter);

        return fighter;
    }

    /**
     * Empties the hangar of a Carrier or Reaver: every stored unit leaves the list front, loses its parent and dies; every launched unit loses its
     * parent and list links and, unless a Scarab, gets a random removal timer.
     * <p>
     * A dying stored unit keeps its list links until its own death clears them.
     *
     * @param unit             the Carrier or Reaver
     * @param instructionLimit the order budget
     * @throws UnsupportedOperationException if a hangar count is not {@code 0} while its list has no unit left
     */
    void releaseHangar(@NotNull Unit unit, int instructionLimit) {
        while (unit.getHangarCount(false) != 0) {
            Unit fighter = unit.getFirstHangarUnit(false);

            if (fighter == null) {
                throw Exceptions.newUnsupportedOperationException("The inside hangar count of unit type {0} exceeds its stored units.",
                                                                  unit.getUnitId());
            }

            unit.setFirstHangarUnit(false, fighter.getNextHangarUnit());
            fighter.setHangarParent(null);
            this.game.getUnitLifecycle().kill(fighter, instructionLimit);
            unit.setHangarCount(false, unit.getHangarCount(false) - 1);
        }

        Unit fighter = unit.getFirstHangarUnit(true);

        while (unit.getHangarCount(true) != 0) {
            if (fighter == null) {
                throw Exceptions.newUnsupportedOperationException("The outside hangar count of unit type {0} exceeds its launched units.",
                                                                  unit.getUnitId());
            }

            fighter.setHangarParent(null);

            if (this.game.getBaseType(fighter) != ScrUnitIds.PROTOSS_SCARAB) {
                UnitHallucination.setRemovalTimer(fighter,
                                                  this.game.getRandom().next(RELEASE_TIMER_RANDOM_SOURCE,
                                                                             RELEASE_TIMER_MINIMUM, RELEASE_TIMER_MAXIMUM));
            }

            Unit next = fighter.getNextHangarUnit();

            fighter.setNextHangarUnit(null);
            fighter.setPreviousHangarUnit(null);
            unit.setHangarCount(true, unit.getHangarCount(true) - 1);

            fighter = next;
        }

        unit.setFirstHangarUnit(true, null);
    }

    /**
     * Validates or acquires the order target and reports whether the parent may launch a fighter at it from where it stands.
     *
     * @param unit                  the Carrier or Reaver
     * @param acquireRange          the target acquisition range in pixels
     * @param maximumGroundDistance the largest ground distance to the target, or {@code 0} for no limit
     * @param instructionLimit      the order activation budget
     * @return {@code true} if the parent can attack its target now, {@code false} otherwise
     * @throws IllegalStateException if the target or the acquired target has no sprite
     */
    private boolean attack(@NotNull Unit unit, int acquireRange, int maximumGroundDistance, int instructionLimit) {
        int order = unit.getPrimaryOrder();
        boolean canMove = order != ScrOrderIds.CARRIER_HOLD_POSITION && order != ScrOrderIds.REAVER_HOLD_POSITION;

        if (!canMove && (unit.getMovementFlags() & MovementFlags.ACCELERATING) != 0) {
            this.game.getEngineGame().getMovement().stop(unit);
        }

        boolean reaver = this.game.requireGameData().getUnitTypes().isReaver(unit.getUnitId());
        Unit target = unit.getOrderTargetUnit();

        if (target != null) {
            if ((target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0 || !this.game.getEngineGame().getCombat().canAttack(unit, target)) {
                if (unit.getAutomaticTargetUnit() == target) {
                    unit.setAutomaticTargetUnit(null);
                }

                unit.setOrderTargetUnit(null);
            } else {
                Sprite targetSprite = target.requireSprite();

                unit.setOrderTargetX(targetSprite.getX());
                unit.setOrderTargetY(targetSprite.getY());

                if (this.canAttackFromHere(unit, target, acquireRange, maximumGroundDistance)) {
                    this.game.getEngineGame().getMovement().stop(unit);
                } else {
                    if (!canMove || (unit.getStatus() & UnitStatusFlags.READY_TO_ATTACK) != 0) {
                        if (unit.getAutomaticTargetUnit() == target) {
                            unit.setAutomaticTargetUnit(null);
                        }

                        unit.setOrderTargetUnit(null);

                        return false;
                    }

                    this.game.getEngineGame().getMovement().setDestination(unit, targetSprite.getX(), targetSprite.getY());
                    unit.setDirectionTargetX(targetSprite.getX());
                    unit.setDirectionTargetY(targetSprite.getY());
                }
            }
        }

        if (getInterceptorCount(this.game.requireGameData().getUnitTypes(), unit) == 0 &&
            (!reaver || unit.getHangarCount(false) + unit.getHangarCount(true) == 0)) {
            this.game.getEngineGame().getMovement().stop(unit);
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return false;
        }

        if (unit.getOrderTimer() != 0) {
            return false;
        }

        target = unit.getOrderTargetUnit();

        if (target == null && unit.getFirstOrder() != null) {
            this.game.getOrders().activateNext(unit, instructionLimit);

            return false;
        }

        if (target == null || (unit.getStatus() & UnitStatusFlags.READY_TO_ATTACK) != 0) {
            this.game.getUnitAttack().reacquireTarget(unit);

            if (this.game.requireGameData().getUnitTypes().isCarrier(unit.getUnitId()) && unit.getOrderTargetUnit() != target) {
                this.attackWithInterceptors(unit, instructionLimit);
            }

            target = unit.getOrderTargetUnit();
        }

        if (target != null) {
            if (canMove) {
                unit.setPrimaryOrder(reaver ? ScrOrderIds.REAVER_FIGHT : ScrOrderIds.CARRIER_FIGHT);
            }

            return this.canAttackFromHere(unit, target, acquireRange, maximumGroundDistance);
        }

        Unit acquired = this.game.getUnitTargeting().findAcquireRandomTarget(unit);

        if (acquired == null) {
            if (canMove && unit.getPrimaryOrder() != ScrOrderIds.CARRIER_IGNORE2) {
                unit.setPrimaryOrder(reaver ? ScrOrderIds.REAVER : ScrOrderIds.CARRIER);
            }

            return false;
        }

        if (canMove) {
            unit.setPrimaryOrder(reaver ? ScrOrderIds.REAVER_FIGHT : ScrOrderIds.CARRIER_FIGHT);
        }

        Sprite acquiredSprite = acquired.requireSprite();

        unit.setOrderTargetX(acquiredSprite.getX());
        unit.setOrderTargetY(acquiredSprite.getY());
        unit.markReadyToAttack();

        if (maximumGroundDistance != 0 &&
            this.game.getUnitPositioning().getGroundPathDistance(unit, acquiredSprite.getX(), acquiredSprite.getY()) >= maximumGroundDistance) {
            return false;
        }

        unit.setOrderTargetUnit(acquired);

        return true;
    }

    /**
     * @param unit                  the Carrier or Reaver
     * @param target                the order target
     * @param acquireRange          the target acquisition range in pixels
     * @param maximumGroundDistance the largest ground distance to the order position, or {@code 0} for no limit
     * @return {@code true} if the parent sees the target within range and, with a limit, within the ground distance, {@code false} otherwise
     */
    private boolean canAttackFromHere(@NotNull Unit unit, @NotNull Unit target, int acquireRange, int maximumGroundDistance) {
        if (!this.game.getUnitTargeting().canSeeTarget(unit, target) ||
            !this.game.getUnitTargeting().isInRange(unit, target, acquireRange + this.game.getUnitTargeting().getTargetMovementRange(unit, target))) {
            return false;
        }

        return maximumGroundDistance == 0 ||
               this.game.getUnitPositioning().getGroundPathDistance(unit, (short) unit.getOrderTargetX(), (short) unit.getOrderTargetY()) <
               maximumGroundDistance;
    }

    /**
     * Orders every launched Interceptor of a Carrier to attack the Carrier's order target.
     *
     * @param unit             the Carrier
     * @param instructionLimit the order activation budget
     * @throws IllegalStateException if the unit or a fighter has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    private void attackWithInterceptors(@NotNull Unit unit, int instructionLimit) {
        if (!this.game.requireGameData().getUnitTypes().isCarrier(unit.getUnitId())) {
            return;
        }

        int elevation = unit.requireSprite().getElevationLevel() - FIGHTER_LAUNCH_ELEVATION_DROP;

        for (Unit fighter = unit.getFirstHangarUnit(true); fighter != null; ) {
            Unit next = fighter.getNextHangarUnit();

            fighter.requireSprite().setElevationLevel(elevation);
            this.game.getOrders().issue(fighter,
                                        this.game.requireGameData().getUnitTypes().getAttackUnitOrder(fighter.getUnitId()),
                                        unit.getOrderTargetUnit(),
                                        0, 0,
                                        ScrUnitIds.NONE,
                                        false,
                                        instructionLimit);

            fighter = next;
        }
    }

    /**
     * Moves a completed stored fighter to the outside list and shows it at the parent (a Scarab in front of the Reaver).
     *
     * @param unit the Carrier or Reaver
     * @return the launched fighter, or {@code null} if none is ready
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the creation context is missing
     */
    private @Nullable Unit releaseFighter(@NotNull Unit unit) {
        Unit released = null;
        int bestHitPoints = 0;

        for (Unit fighter = unit.getFirstHangarUnit(false); fighter != null; fighter = fighter.getNextHangarUnit()) {
            if ((fighter.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
                continue;
            }

            // The first completed fighter sets the comparison threshold; the last fighter above that threshold is selected.
            if (released == null || fighter.getHitPoints() > bestHitPoints) {
                if (released == null) {
                    bestHitPoints = fighter.getHitPoints();
                }

                released = fighter;
            }
        }

        if (released == null) {
            return null;
        }

        if (this.game.getBaseType(released) == ScrUnitIds.PROTOSS_INTERCEPTOR &&
            released.getHitPoints() < this.game.getUnitHealth().getMaximumHitPoints(released) / INTERCEPTOR_RELEASE_HEALTH_DIVISOR) {
            return null;
        }

        int positionX = unit.getPositionX();
        int positionY = unit.getPositionY();

        if (this.game.getBaseType(released) == ScrUnitIds.PROTOSS_SCARAB) {
            long vector = FlingyMotionUtils.calculateDirectionVector(unit.getHeading(), SCARAB_LAUNCH_DISTANCE);

            positionX += (int) vector;
            positionY += (int) (vector >>> FlingyMotionUtils.DIRECTION_VECTOR_VERTICAL_SHIFT);

            released.resetDirection(this.game, unit.getHeading());
        }

        remove(unit, released, false, unit.getFirstHangarUnit(false) == released);
        pushFront(unit, released, true);
        this.game.getUnitPositioning().move(released, positionX, positionY);

        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

        if (context == null) {
            throw Exceptions.newUnsupportedOperationException("Fighter release requires the creation context.");
        }

        this.game.getUnitVisibility().show(released, context.isFoliageEnabled());
        released.setHangarOutside(true);

        return released;
    }
}
