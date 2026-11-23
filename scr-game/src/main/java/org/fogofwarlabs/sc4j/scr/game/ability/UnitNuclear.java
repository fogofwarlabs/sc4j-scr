package org.fogofwarlabs.sc4j.scr.game.ability;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnKey;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnReset;
import org.fogofwarlabs.sc4j.engine.entity.column.IntColumn;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SoundIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SpriteIds;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerPlayerPhase;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.production.UnitMorphing;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffect;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameRulesVersion;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.visibility.SpriteVisibility;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Runs Nuclear Silo storage, the Ghost nuclear strike orders and the Nuclear Missile launch.
 * <p>
 * Missiles, Silos and tracking Ghosts retain their connected unit through the parent-unit reference.
 * <p>
 * The ready state of the Nuclear Silo in the native unit union is registered as a unit pool column ({@link #SILO_COLUMNS}).
 * The column keeps its value when a slot is reused, as the union field does; unit creation and the unit reset methods set
 * it.
 */
public final class UnitNuclear {
    /**
     * The DWORD that is nonzero when the Nuclear Silo's missile is ready ({@link Unit#getSiloReadyDword()}).
     */
    public static final @NotNull ColumnKey<IntColumn> SILO_READY_COLUMN = ColumnKey.ofInt("siloReady", 0, ColumnReset.KEEP);
    /**
     * The unit pool columns of the Nuclear Silo state, in registration order.
     */
    public static final @NotNull List<@NotNull ColumnKey<?>> SILO_COLUMNS = List.of(SILO_READY_COLUMN);

    /**
     * The {@link ScrOrderIds#NUKE_TRAIN} order state that waits for the Silo to train a Missile.
     */
    private static final int TRAIN_STATE_WAITING = 0;
    /**
     * The {@link ScrOrderIds#NUKE_TRAIN} order state that waits for the adopted Missile to complete.
     */
    private static final int TRAIN_STATE_ADOPTED = 1;

    /**
     * The fractional bits of unit coordinates and distances.
     */
    private static final int FIXED_POINT_SHIFT = 8;
    /**
     * A computer Ghost cloaks within this many sight ranges of its target.
     */
    private static final int COMPUTER_CLOAK_SIGHT_RANGES = 3;
    /**
     * The direction difference within which the Ghost faces its target.
     */
    private static final int STRIKE_FACING_TOLERANCE = 1;

    /**
     * The {@link ScrOrderIds#NUKE_LAUNCH} order state of a launched Missile, which starts to rise.
     */
    private static final int LAUNCH_STATE_LAUNCHED = 0;
    /**
     * The {@link ScrOrderIds#NUKE_LAUNCH} order state of a rising Missile, which waits to raise the launch alert.
     */
    private static final int LAUNCH_STATE_RISING = 1;
    /**
     * The {@link ScrOrderIds#NUKE_LAUNCH} order state after the launch alert, which waits to hide the Missile.
     */
    private static final int LAUNCH_STATE_ALERTED = 2;
    /**
     * The {@link ScrOrderIds#NUKE_LAUNCH} order state of a hidden Missile, which waits to start its warp-in animation.
     */
    private static final int LAUNCH_STATE_HIDDEN = 3;
    /**
     * The {@link ScrOrderIds#NUKE_LAUNCH} order state of a warping-in Missile, which waits to reappear above the target.
     */
    private static final int LAUNCH_STATE_WARPING_IN = 4;
    /**
     * The {@link ScrOrderIds#NUKE_LAUNCH} order state of a descending Missile, which waits to reach the detonation distance.
     */
    private static final int LAUNCH_STATE_DESCENDING = 5;
    /**
     * The {@link ScrOrderIds#NUKE_LAUNCH} order state of a detonating Missile, which waits for the action-done order signal.
     */
    private static final int LAUNCH_STATE_DETONATING = 6;

    /**
     * The main order timer of a rising Missile.
     */
    private static final int LAUNCH_RISE_TIMER = 90;
    /**
     * The main order timer value of a rising Missile at which the launch alert is raised.
     */
    private static final int LAUNCH_ALERT_TIMER = 45;
    /**
     * The height above the target at which the Missile reappears.
     */
    private static final int DESCENT_HEIGHT = 320;
    /**
     * The direction of the descending Missile (facing down).
     */
    private static final int DESCENT_DIRECTION = FlingyMotionUtils.DIRECTION_HALF_TURN;
    /**
     * The distance to the target at which the Missile detonates.
     */
    private static final int DETONATION_DISTANCE = 10;

    /**
     * The {@link ScrOrderIds#NUKE_TRACK} order state that starts tracking and paints the target dot.
     */
    private static final int TRACK_STATE_START = 0;
    /**
     * The {@link ScrOrderIds#NUKE_TRACK} order state that holds the Ghost until the Missile descends.
     */
    private static final int TRACK_STATE_TRACKING = 6;

    /**
     * Cancels a Nuclear Missile: the Ghost loses its dot reference and the connected unit restarts its order.
     *
     * @param units   the unit type table
     * @param missile the canceled Missile
     */
    public static void cancel(@NotNull ScrUnitTypeView units, @NotNull Unit missile) {
        Unit connected = missile.getParentUnit();

        if (connected == null) {
            return;
        }

        if (units.isGhost(connected.getUnitId())) {
            connected.setNukeDot(null);
        }

        connected.setPrimaryOrderState(0);
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitNuclear} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitNuclear(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Adopts the Silo's trained Missile and marks the Silo ready when it completes. Continues with {@link ScrOrderIds#COMPUTER_AI} when the Silo has a
     * computer record.
     *
     * @param silo             the Nuclear Silo
     * @param instructionLimit the order budget
     * @throws IllegalStateException if the unit is not a Nuclear Silo
     */
    public void updateTrain(@NotNull Unit silo, int instructionLimit) {
        if (silo.getUnitId() != ScrUnitIds.TERRAN_NUCLEAR_SILO) {
            throw Exceptions.newIllegalStateException("NukeTrain runs on a unit that is not a Nuclear Silo.");
        }

        if (silo.getPrimaryOrderState() == TRAIN_STATE_WAITING) {
            Unit missile = silo.getCurrentBuildUnit();

            if (missile != null) {
                silo.setSiloNuke(missile);
                missile.setParentUnit(silo);
                silo.setPrimaryOrderState(TRAIN_STATE_ADOPTED);
            }
        } else if (silo.getSiloNuke() != null) {
            if ((silo.getSiloNuke().getStatus() & UnitStatusFlags.COMPLETED) != 0) {
                silo.setSiloReady(true);
                silo.setPrimaryOrderState(TRAIN_STATE_WAITING);

                if (silo.getComputerRecord() != null) {
                    this.game.getOrders().issueUntargeted(silo, ScrOrderIds.COMPUTER_AI, instructionLimit);
                }
            }
        } else {
            silo.setPrimaryOrderState(TRAIN_STATE_WAITING);
        }
    }

    /**
     * Issues {@link ScrOrderIds#NUKE_UNIT} for a painted unit target or {@link ScrOrderIds#CAST_NUCLEAR_STRIKE} for a painted position.
     *
     * @param ghost            the Ghost
     * @param instructionLimit the order budget
     */
    public void updatePaint(@NotNull Unit ghost, int instructionLimit) {
        if (ghost.getOrderTargetUnit() != null) {
            this.game.getOrders().issue(ghost, ScrOrderIds.NUKE_UNIT, ghost.getOrderTargetUnit(), 0, 0, ScrUnitIds.NONE, false, instructionLimit);
        } else {
            this.game.getOrders().issue(ghost,
                                        ScrOrderIds.CAST_NUCLEAR_STRIKE,
                                        null,
                                        ghost.getOrderTargetX(), ghost.getOrderTargetY(),
                                        ScrUnitIds.NONE,
                                        false,
                                        instructionLimit);
        }
    }

    /**
     * Updates the target position for a unit-targeted nuclear strike.
     * <p>
     * A missing target stops movement before completing the order.
     *
     * @param ghost            the Ghost
     * @param instructionLimit the order budget
     * @throws IllegalStateException if the target has no sprite
     */
    public void updateUnit(@NotNull Unit ghost, int instructionLimit) {
        Unit target = ghost.getOrderTargetUnit();

        if (target == null) {
            this.game.getEngineGame().getMovement().stop(ghost);
            this.game.getOrders().finishOrder(ghost, instructionLimit);

            return;
        }

        Sprite targetSprite = target.requireSprite();

        ghost.setOrderTargetX(targetSprite.getX());
        ghost.setOrderTargetY(targetSprite.getY());

        this.updateCastNuclearStrike(ghost, instructionLimit);
    }

    /**
     * Approaches within sight range, faces the target, then launches the first ready Missile of the owner's Silos and tracks it. A Ghost with a
     * computer record within {@link #COMPUTER_CLOAK_SIGHT_RANGES} sight ranges cloaks first when it is not cloaked and the cloak technology is
     * usable; the tracking Ghost shows the {@link ButtonSetIds#CANCEL_NUCLEAR_STRIKE} button set.
     *
     * @param ghost            the Ghost
     * @param instructionLimit the order budget
     * @throws UnsupportedOperationException if the unit creation context is unavailable or the unit pool is not initialized
     * @throws IllegalStateException         if the Ghost or the Missile has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    public void updateCastNuclearStrike(@NotNull Unit ghost, int instructionLimit) {
        int targetX = (short) ghost.getOrderTargetX();
        int targetY = (short) ghost.getOrderTargetY();

        ghost.setDirectionTargetX(targetX);
        ghost.setDirectionTargetY(targetY);

        int horizontal = ghost.getFixedPositionX() - (targetX << FIXED_POINT_SHIFT);
        int vertical = ghost.getFixedPositionY() - (targetY << FIXED_POINT_SHIFT);
        int sight = TerrainTileset.TILE_SIZE * this.game.getUnitVisibility().calculateSightRange(ghost, false);
        int distance = RegionPathSearch.calculateDistance(0, horizontal, 0, vertical) >> FIXED_POINT_SHIFT;

        if (ghost.getComputerRecord() != null && distance <= COMPUTER_CLOAK_SIGHT_RANGES * sight &&
            (ghost.getStatus() & UnitStatusFlags.REQUIRES_DETECTOR) == 0) {
            int technology = UnitQueries.getCloakTechnology(this.game.requireGameData().getUnitTypes(), ghost);

            if (technology != ScrTechIds.NONE && this.game.getUnitTechnology().canUseTechnology(ghost, technology)) {
                this.game.getUnitTechnology().cloakUnit(ghost);
            }
        }

        if (distance > sight) {
            if (ghost.getMovementDestinationCountdown() == 0) {
                this.game.getEngineGame().getMovement().setDestination(ghost, targetX, targetY);
            }

            return;
        }

        this.game.getEngineGame().getMovement().stop(ghost);

        boolean facingTarget = ghost.getPositionX() == ghost.getDirectionTargetX() && ghost.getPositionY() == ghost.getDirectionTargetY();

        if (!facingTarget) {
            Sprite sprite = ghost.requireSprite();
            int direction = FlingyMotionUtils.calculateDirection(ghost.getDirectionTargetX() - sprite.getX(),
                                                                 ghost.getDirectionTargetY() - sprite.getY());

            facingTarget = Math.abs((byte) (ghost.getHeading() - direction)) <= STRIKE_FACING_TOLERANCE;
        }

        if (!facingTarget) {
            return;
        }

        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        Unit silo = null;

        for (int index = pool.getFirstOwnerIndex(ghost.getOwnerIndex()); index != 0; index = pool.getNextOwnerIndex(index)) {
            Unit candidate = pool.getUnit(index);

            if (candidate.getUnitId() == ScrUnitIds.TERRAN_NUCLEAR_SILO && candidate.getSiloNuke() != null && candidate.isSiloReady()) {
                silo = candidate;

                break;
            }
        }

        if (silo == null) {
            this.game.getOrders().finishOrder(ghost, instructionLimit);

            return;
        }

        Sprite ghostSprite = ghost.requireSprite();

        this.game.getUnitSounds().play(SoundIds.GHOST_LASER, ghost);

        Unit missile = silo.getSiloNuke();

        silo.setSiloNuke(null);
        silo.setSiloReady(false);

        this.game.getOrders().issue(missile, ScrOrderIds.NUKE_LAUNCH, null, targetX, targetY, ScrUnitIds.NONE, false, instructionLimit);

        missile.setParentUnit(ghost);

        IscriptExecutor.applySpriteDirection(this.game, missile.requireSprite(), FlingyMotionUtils.DIRECTION_UP);

        UnitCreationContext context = this.game.getUnitSystem().requireUnitCreationContext();

        this.game.getUnitVisibility().show(missile, context.isFoliageEnabled());

        ghost.setParentUnit(missile);

        this.game.getOrders().issue(ghost,
                                    ScrOrderIds.NUKE_TRACK,
                                    null,
                                    ghostSprite.getX(), ghostSprite.getY(),
                                    ScrUnitIds.NONE,
                                    false,
                                    instructionLimit);
        UnitMorphing.writeButtonSet(this.game.requireGameData().getUnitTypes(), ghost, ButtonSetIds.CANCEL_NUCLEAR_STRIKE);
    }

    /**
     * Rises off the map, waits, reappears above the target and detonates. The arrival frame already tests the detonation signal unless the game is a
     * legacy replay ({@link GameRulesVersion#detonatesOnArrival}).
     *
     * @param missile          the Nuclear Missile
     * @param instructionLimit the order and script budget
     * @throws UnsupportedOperationException if the unit creation context is unavailable
     * @throws IllegalStateException         if the Missile has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    public void updateLaunch(@NotNull Unit missile, int instructionLimit) {
        if (missile.getParentUnit() == null && missile.getPrimaryOrderState() < LAUNCH_STATE_DESCENDING) {
            this.game.getUnitLifecycle().kill(missile, instructionLimit);

            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        Sprite sprite = missile.requireSprite();
        int type = missile.getUnitId();

        switch (missile.getPrimaryOrderState()) {
            case LAUNCH_STATE_LAUNCHED -> {
                this.game.getUnitSounds().play(SoundIds.NUCLEAR_MISSILE_LAUNCH, missile);

                int topY = units.getDimensionUp(type);

                this.game.getEngineGame().getMovement().setDestination(missile, sprite.getX(), topY);
                missile.setDirectionTargetX(sprite.getX());
                missile.setDirectionTargetY(topY);
                missile.setOrderTimer(LAUNCH_RISE_TIMER);
                missile.setPrimaryOrderState(LAUNCH_STATE_RISING);
            }
            case LAUNCH_STATE_RISING -> {
                if (missile.getOrderTimer() <= LAUNCH_ALERT_TIMER || this.game.getEngineGame().getMovement().isAtDestination(missile)) {
                    this.game.getInterfaceLink().getSoundEvents().add(SoundIds.ZERG_ADVISOR_UPDATE_4, 0, 0, ScrUnitIds.NONE);
                    this.game.getInterfaceLink().getCommandErrorEvents().raiseNuclearLaunchDetected();
                    missile.setPrimaryOrderState(LAUNCH_STATE_ALERTED);
                }
            }
            case LAUNCH_STATE_ALERTED -> {
                if (missile.getOrderTimer() == 0 || this.game.getEngineGame().getMovement().isAtDestination(missile)) {
                    this.game.getUnitLifecycle().hide(missile, true);
                    missile.getParentUnit().setParentUnit(missile);
                    this.game.getEngineGame().getMovement().stop(missile);
                    missile.setPrimaryOrderState(LAUNCH_STATE_HIDDEN);
                }
            }
            case LAUNCH_STATE_HIDDEN -> {
                if ((missile.getMovementFlags() & MovementFlags.ACCELERATING) == 0) {
                    this.game.getIscriptExecutor().runSpriteAnimation(sprite, missile, IscriptExecutor.ANIMATION_WARP_IN, 0, instructionLimit);
                    missile.setPrimaryOrderState(LAUNCH_STATE_WARPING_IN);
                }
            }
            case LAUNCH_STATE_WARPING_IN -> {
                if ((missile.getOrderSignal() & Unit.ORDER_SIGNAL_CAST_DONE) != 0) {
                    missile.setOrderSignal(missile.getOrderSignal() & ~Unit.ORDER_SIGNAL_CAST_DONE);

                    int targetX = (short) missile.getOrderTargetX();
                    int targetY = (short) missile.getOrderTargetY();
                    int appearX = Math.max(units.getDimensionLeft(type), targetX);
                    int appearY = Math.max(units.getDimensionUp(type), targetY - DESCENT_HEIGHT);

                    this.game.getUnitPositioning().move(missile, appearX, appearY);
                    missile.resetDirection(this.game, DESCENT_DIRECTION);
                    this.game.getEngineGame().getMovement().setDestination(missile, targetX, targetY);
                    missile.setDirectionTargetX(targetX);
                    missile.setDirectionTargetY(targetY);
                    this.game.getUnitVisibility().show(missile, this.game.getUnitSystem().requireUnitCreationContext().isFoliageEnabled());
                    missile.setPrimaryOrderState(LAUNCH_STATE_DESCENDING);
                }
            }
            case LAUNCH_STATE_DESCENDING -> {
                int horizontal = missile.getFixedPositionX() - (missile.getMovementDestinationX() << FIXED_POINT_SHIFT);
                int vertical = missile.getFixedPositionY() - (missile.getMovementDestinationY() << FIXED_POINT_SHIFT);

                if (RegionPathSearch.calculateDistance(0, horizontal, 0, vertical) >> FIXED_POINT_SHIFT <= DETONATION_DISTANCE) {
                    missile.setOrderTargetUnit(missile);
                    this.game.getIscriptExecutor().runSpriteAnimation(sprite,
                                                                      missile,
                                                                      IscriptExecutor.ANIMATION_SPECIAL_STATE_1,
                                                                      0,
                                                                      instructionLimit);
                    missile.setPrimaryOrderState(LAUNCH_STATE_DETONATING);

                    // Test the detonation signal on the arrival frame when the rules version permits it.
                    if (this.game.getRulesVersion().detonatesOnArrival()) {
                        this.detonateOnSignal(missile, instructionLimit);
                    }
                }
            }
            case LAUNCH_STATE_DETONATING -> this.detonateOnSignal(missile, instructionLimit);
            default -> {
            }
        }
    }

    /**
     * Paints the target dot and holds the Ghost until the Missile descends. Restores the Ghost's button set and returns a Ghost with a computer
     * record to a town region.
     * <p>
     * The dot reference is cleared only after its {@link IscriptExecutor#ANIMATION_DEATH} animation returns.
     *
     * @param ghost            the Ghost
     * @param instructionLimit the order, script and sprite budget
     * @throws IllegalStateException if the unit is not a Ghost or the Ghost has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void updateTrack(@NotNull Unit ghost, int instructionLimit) {
        if (!this.game.requireGameData().getUnitTypes().isGhost(ghost.getUnitId())) {
            throw Exceptions.newIllegalStateException("NukeTrack runs on a unit that is not a Ghost.");
        }

        Sprite sprite = ghost.requireSprite();
        Unit missile = ghost.getParentUnit();

        if (ghost.getPrimaryOrderState() == TRACK_STATE_START) {
            this.game.getIscriptExecutor().runSpriteAnimation(sprite, ghost, IscriptExecutor.ANIMATION_SPECIAL_STATE_1, 0, instructionLimit);

            int positionX = missile != null ? (short) missile.getOrderTargetX() : 0;
            int positionY = missile != null ? (short) missile.getOrderTargetY() : 0;
            SpriteEffect dot = this.game.getIscriptExecutor().createSpriteEffect(SpriteIds.NUKE_TARGET_DOT,
                                                                                 positionX, positionY,
                                                                                 ghost.getOwnerIndex(),
                                                                                 instructionLimit);

            ghost.setNukeDot(dot);

            if (dot != null) {
                Sprite dotSprite = dot.getSprite();

                dotSprite.setElevationLevel(sprite.getElevationLevel() + 1);

                SpriteVisibility.updatePositionTile(dotSprite, this.game.getMap(), this.game.getLocalVision().getComparisonMask());
            }

            ghost.setPrimaryOrderState(TRACK_STATE_TRACKING);
        } else if (ghost.getPrimaryOrderState() == TRACK_STATE_TRACKING) {
            if (missile == null || missile.getPrimaryOrderState() == LAUNCH_STATE_DESCENDING) {
                this.game.getOrders().completeOrderToIdle(ghost, instructionLimit);
                UnitMorphing.writeButtonSet(this.game.requireGameData().getUnitTypes(),
                                            ghost,
                                            this.game.requireGameData().getButtonSet(ghost.getUnitId()));
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, ghost, IscriptExecutor.ANIMATION_WALKING_TO_IDLE, 0, instructionLimit);
                this.removeDot(ghost, instructionLimit);

                ComputerPlayerPhase phase = this.game.getComputerPlayerPhase();

                if (phase != null && ghost.getComputerRecord() != null) {
                    this.game.getComputerOrders().moveToTownRegion(phase, ghost, instructionLimit);
                }
            }
        }
    }

    /**
     * Runs the {@link IscriptExecutor#ANIMATION_DEATH} animation of a Ghost's nuclear target dot and forgets it.
     *
     * @param ghost            the Ghost
     * @param instructionLimit the script budget
     */
    void removeDot(@NotNull Unit ghost, int instructionLimit) {
        SpriteEffect dot = ghost.getNukeDot();

        if (dot == null) {
            return;
        }

        Sprite dotSprite = dot.getSpriteOrNull();

        if (dotSprite != null) {
            this.game.getIscriptExecutor().runSpriteAnimation(dotSprite, null, IscriptExecutor.ANIMATION_DEATH, 0, instructionLimit);
        }

        ghost.setNukeDot(null);
    }

    /**
     * Removes a Ghost's target dot or unlinks the Nuclear Silo and its Missile during destruction.
     * <p>
     * A Silo's missile reference is cleared only after the missile's death request returns. A Ghost's dot reference is cleared only after its
     * {@link IscriptExecutor#ANIMATION_DEATH} animation returns.
     *
     * @param unit             the dying unit
     * @param instructionLimit the order and script budget
     */
    public void destroy(@NotNull Unit unit, int instructionLimit) {
        int type = unit.getUnitId();

        if (this.game.requireGameData().getUnitTypes().isGhost(type)) {
            this.removeDot(unit, instructionLimit);
        } else if (type == ScrUnitIds.TERRAN_NUCLEAR_SILO) {
            Unit missile = unit.getSiloNuke();

            if (missile != null) {
                this.game.getUnitLifecycle().kill(missile, instructionLimit);
                unit.setSiloNuke(null);
            }
        } else if (this.game.getBaseType(type) == ScrUnitIds.TERRAN_NUCLEAR_MISSILE) {
            Unit connected = unit.getParentUnit();

            if (connected != null && connected.getUnitId() == ScrUnitIds.TERRAN_NUCLEAR_SILO) {
                connected.setSiloNuke(null);
                connected.setSiloReady(false);
                unit.setParentUnit(null);
            }
        }
    }

    /**
     * Applies the detonating state of {@link ScrOrderIds#NUKE_LAUNCH}: the Missile dies (hidden on death) once its script signals the action-done order
     * bit.
     *
     * @param missile          the Nuclear Missile
     * @param instructionLimit the order budget
     */
    private void detonateOnSignal(@NotNull Unit missile, int instructionLimit) {
        if ((missile.getOrderSignal() & Unit.ORDER_SIGNAL_ACTION_DONE) != 0) {
            missile.setOrderSignal(missile.getOrderSignal() & ~Unit.ORDER_SIGNAL_ACTION_DONE);
            missile.setUserActionFlags(missile.getUserActionFlags() | Unit.USER_ACTION_HIDE_ON_DEATH);

            this.game.getUnitLifecycle().kill(missile, instructionLimit);
        }
    }
}
