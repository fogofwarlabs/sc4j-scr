package org.fogofwarlabs.sc4j.scr.game.ability;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerPlayerPhase;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.production.UnitMorphing;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitCreationContext;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;

/**
 * Executes door, doodad, trap and Capture the Flag beacon orders.
 */
public final class DoodadOrders {
    /**
     * The doors and the Floor Gun Trap follow the Upper Level Door up to this offset.
     */
    private static final int LAST_DOOR_OR_GUN_TRAP_OFFSET = 4;
    /**
     * The traps follow the Floor Gun Trap up to this offset (Right Wall Flame Trap).
     */
    private static final int LAST_TRAP_OFFSET = 4;
    /**
     * The sprite elevation of a disabled doodad or open door.
     */
    private static final int LOWERED_ELEVATION = 1;
    /**
     * The sprite elevation of a hidden floor trap.
     */
    private static final int HIDDEN_FLOOR_TRAP_ELEVATION = 0;

    /**
     * The {@link ScrOrderIds#HIDDEN_GUN} order state that hides the trap.
     */
    private static final int HIDDEN_GUN_STATE_HIDING = 0;
    /**
     * The {@link ScrOrderIds#HIDDEN_GUN} order state that waits for a target.
     */
    private static final int HIDDEN_GUN_STATE_WAITING = 1;
    /**
     * The main order timer of a hidden trap between two target searches.
     */
    private static final int TRAP_SEARCH_DELAY = 15;
    /**
     * The started order state of {@link ScrOrderIds#HIDE_TRAP} and {@link ScrOrderIds#REVEAL_TRAP}.
     */
    private static final int TRAP_STATE_START = 0;
    /**
     * The order state of {@link ScrOrderIds#HIDE_TRAP} and {@link ScrOrderIds#REVEAL_TRAP} that waits for the end of the animation.
     */
    private static final int TRAP_STATE_ANIMATING = 1;

    /**
     * The elapsed game seconds after which a flag beacon places its Flag.
     */
    private static final int FLAG_BEACON_START_SECONDS = 600;
    /**
     * The frames between two scans of a flag beacon.
     */
    private static final int FLAG_BEACON_SCAN_DELAY = 15;

    /**
     * The mask of an unsigned WORD coordinate.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The divisor of a placement box dimension that gives its offset from the placement center.
     */
    private static final int PLACEMENT_HALF_DIVISOR = 2;

    private final @NotNull Game game;

    /**
     * Creates a {@link DoodadOrders} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public DoodadOrders(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Applies {@link ScrOrderIds#ENABLE_DOODAD}: the doodad runs its {@link IscriptExecutor#ANIMATION_ENABLE} animation and restores its elevation; a
     * door plays its closing sound ({@code units.dat} What Sound End) and queues {@link ScrOrderIds#CLOSE_DOOR} at the front.
     * <p>
     * Disabled or frozen units follow the idle-order path; a nonbreaking script returns before the enable work.
     *
     * @param unit             the doodad
     * @param instructionLimit the order and script budget
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void enable(@NotNull Unit unit, int instructionLimit) {
        if (UnitOrderEligibility.isDisabled(unit)) {
            this.finishIdle(unit, instructionLimit);

            return;
        }

        if ((unit.getStatus() & UnitStatusFlags.ISCRIPT_NO_BREAK) != 0) {
            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = unit.getUnitId();
        Sprite sprite = unit.requireSprite();

        this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_ENABLE, 1, instructionLimit);

        if (type == ScrUnitIds.SPECIAL_FLOOR_MISSILE_TRAP || type >= ScrUnitIds.SPECIAL_UPPER_LEVEL_DOOR &&
                                                             type <= ScrUnitIds.SPECIAL_FLOOR_GUN_TRAP) {
            sprite.setElevationLevel(units.getElevationLevel(type));
        }

        if (type >= ScrUnitIds.SPECIAL_UPPER_LEVEL_DOOR && type <= ScrUnitIds.SPECIAL_RIGHT_PIT_DOOR) {
            this.game.getUnitSounds().play(units.getWhatSoundEnd(type), unit);
            this.game.getOrders().queueFront(unit, ScrOrderIds.CLOSE_DOOR, null, 0, 0, ScrUnitIds.NONE);
            this.game.getOrders().completeOrder(unit, instructionLimit);

            return;
        }

        this.finishIdle(unit, instructionLimit);
    }

    /**
     * Disables a created doodad or trap that the map places disabled: unless the unit is already disabled or frozen, it becomes disabled, runs its
     * {@link IscriptExecutor#ANIMATION_ALMOST_BUILT} animation and stops colliding; the Floor Missile Trap and the doors drop to the lowered
     * elevation; the Floor Missile Trap and the traps become cloaked, requiring detection, and undetected.
     * <p>
     * Disabled or frozen units return before any change.
     *
     * @param unit             the created unit
     * @param instructionLimit the script budget
     * @throws IllegalStateException if the unit has no sprite
     */
    public void disableAtCreation(@NotNull Unit unit, int instructionLimit) {
        if (UnitOrderEligibility.isDisabled(unit)) {
            return;
        }

        unit.setStatus(unit.getStatus() | UnitStatusFlags.DISABLED);
        this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(), unit, IscriptExecutor.ANIMATION_ALMOST_BUILT, 1, instructionLimit);
        unit.setStatus(unit.getStatus() | UnitStatusFlags.NO_COLLIDE);

        int type = unit.getUnitId();

        if (type == ScrUnitIds.SPECIAL_FLOOR_MISSILE_TRAP || type - ScrUnitIds.SPECIAL_UPPER_LEVEL_DOOR >= 0 &&
                                                             type - ScrUnitIds.SPECIAL_UPPER_LEVEL_DOOR <= LAST_DOOR_OR_GUN_TRAP_OFFSET) {
            unit.requireSprite().setElevationLevel(LOWERED_ELEVATION);
        }

        if (type == ScrUnitIds.SPECIAL_FLOOR_MISSILE_TRAP || type - ScrUnitIds.SPECIAL_FLOOR_GUN_TRAP >= 0 &&
                                                             type - ScrUnitIds.SPECIAL_FLOOR_GUN_TRAP <= LAST_TRAP_OFFSET) {
            unit.setStatus(unit.getStatus() | UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED);
            unit.setDetectionFlags(Unit.DETECTION_FLAGS_UNDETECTED);
            unit.setVisibilityUpdateCountdown(0);
        }
    }

    /**
     * Applies {@link ScrOrderIds#HIDDEN_GUN}, the idle order of the traps. The hiding state makes the trap stop colliding, lowers the Floor Missile
     * Trap and the Floor Gun Trap and cloaks it, requiring detection and undetected. The waiting state waits, unless the trap is disabled or frozen,
     * for the main order timer; it then reloads and looks for a random target. With one the trap collides again, the floor traps return to their
     * {@code units.dat} elevation, the cloak is removed, {@link ScrOrderIds#REVEAL_TRAP} is ordered and the {@code units.dat} Attack Unit order at the
     * target and {@link ScrOrderIds#HIDE_TRAP} are queued.
     * <p>
     * The hiding branch returns after its status and optional elevation changes. Other states, disabled or frozen traps and an active countdown
     * return before target search. The waiting countdown is reloaded before the search, and target selection can consume random values.
     *
     * @param unit             the trap
     * @param instructionLimit the order budget
     * @throws IllegalStateException if the unit or the target has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void updateHiddenGun(@NotNull Unit unit, int instructionLimit) {
        int type = unit.getUnitId();
        boolean floorTrap = type == ScrUnitIds.SPECIAL_FLOOR_MISSILE_TRAP || type == ScrUnitIds.SPECIAL_FLOOR_GUN_TRAP;

        if (unit.getPrimaryOrderState() == HIDDEN_GUN_STATE_HIDING) {
            unit.setStatus(unit.getStatus() | UnitStatusFlags.NO_COLLIDE);

            if (floorTrap) {
                unit.requireSprite().setElevationLevel(HIDDEN_FLOOR_TRAP_ELEVATION);
            }

            unit.setStatus(unit.getStatus() & ~(UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED) |
                           UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED);
            unit.setDetectionFlags(Unit.DETECTION_FLAGS_UNDETECTED);
            unit.setVisibilityUpdateCountdown(0);
            unit.setPrimaryOrderState(HIDDEN_GUN_STATE_WAITING);

            return;
        }

        if (unit.getPrimaryOrderState() != HIDDEN_GUN_STATE_WAITING || UnitOrderEligibility.isDisabled(unit) || unit.getOrderTimer() != 0) {
            return;
        }

        unit.setOrderTimer(TRAP_SEARCH_DELAY);

        Unit target = this.game.getUnitTargeting().findAcquireRandomTarget(unit);

        if (target == null) {
            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        unit.setStatus(unit.getStatus() & ~UnitStatusFlags.NO_COLLIDE);

        if (floorTrap) {
            unit.requireSprite().setElevationLevel(units.getElevationLevel(type));
        }

        unit.setStatus(unit.getStatus() & ~(UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED));
        unit.setVisibilityUpdateCountdown(0);
        this.game.getOrders().issueUntargeted(unit, ScrOrderIds.REVEAL_TRAP, instructionLimit);

        Sprite targetSprite = target.requireSprite();

        this.game.getOrders().queueReplacingSameOrders(unit,
                                                       units.getAttackUnitOrder(type),
                                                       target,
                                                       targetSprite.getX(), targetSprite.getY(),
                                                       ScrUnitIds.NONE);
        this.game.getOrders().queueReplacingSameOrders(unit, ScrOrderIds.HIDE_TRAP, null, 0, 0, ScrUnitIds.NONE);
    }

    /**
     * Applies {@link ScrOrderIds#HIDE_TRAP}. In the started state a trap that is not disabled or frozen and still finds a random target attacks it
     * again with its {@code units.dat} Attack Unit order, queues {@link ScrOrderIds#HIDE_TRAP} and finishes; otherwise its hide animation
     * ({@link IscriptExecutor#ANIMATION_SPECIAL_STATE_2}) runs, the action-done order signal is cleared and the trap waits for that signal
     * ({@link #finishTrapAnimation}).
     * <p>
     * The animating branch waits for its signal; other nonstarting states return. Target search can consume random values.
     *
     * @param unit             the trap
     * @param instructionLimit the order and script budget
     * @throws IllegalStateException if the target or the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void hideTrap(@NotNull Unit unit, int instructionLimit) {
        if (unit.getPrimaryOrderState() == TRAP_STATE_ANIMATING) {
            this.finishTrapAnimation(unit, instructionLimit);

            return;
        }

        if (unit.getPrimaryOrderState() != TRAP_STATE_START) {
            return;
        }

        if (!UnitOrderEligibility.isDisabled(unit)) {
            Unit target = this.game.getUnitTargeting().findAcquireRandomTarget(unit);

            if (target != null) {
                Sprite targetSprite = target.requireSprite();

                this.game.getOrders().issue(unit,
                                            this.game.requireGameData().getUnitTypes().getAttackUnitOrder(unit.getUnitId()),
                                            target,
                                            targetSprite.getX(), targetSprite.getY(),
                                            ScrUnitIds.NONE,
                                            false,
                                            instructionLimit);
                this.game.getOrders().queueReplacingSameOrders(unit, ScrOrderIds.HIDE_TRAP, null, 0, 0, ScrUnitIds.NONE);
                this.game.getOrders().completeOrder(unit, instructionLimit);

                return;
            }
        }

        this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(), unit, IscriptExecutor.ANIMATION_SPECIAL_STATE_2, 1, instructionLimit);
        unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ACTION_DONE);
        unit.setPrimaryOrderState(TRAP_STATE_ANIMATING);
    }

    /**
     * Applies {@link ScrOrderIds#REVEAL_TRAP}: the started state runs the reveal animation ({@link IscriptExecutor#ANIMATION_SPECIAL_STATE_1}), clears
     * the action-done order signal and waits for that signal ({@link #finishTrapAnimation}).
     * <p>
     * Only the starting and animating states perform work.
     *
     * @param unit             the trap
     * @param instructionLimit the order and script budget
     * @throws IllegalStateException if the unit has no sprite
     */
    public void revealTrap(@NotNull Unit unit, int instructionLimit) {
        if (unit.getPrimaryOrderState() == TRAP_STATE_START) {
            this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(),
                                                              unit,
                                                              IscriptExecutor.ANIMATION_SPECIAL_STATE_1,
                                                              1,
                                                              instructionLimit);
            unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ACTION_DONE);
            unit.setPrimaryOrderState(TRAP_STATE_ANIMATING);
        } else if (unit.getPrimaryOrderState() == TRAP_STATE_ANIMATING) {
            this.finishTrapAnimation(unit, instructionLimit);
        }
    }

    /**
     * Applies the shared animating state of {@link ScrOrderIds#HIDE_TRAP} and {@link ScrOrderIds#REVEAL_TRAP}: once the action-done order signal is set
     * it is cleared; with an empty order queue the {@code units.dat} Return to Idle order is issued; then the order finishes.
     *
     * @param unit             the trap
     * @param instructionLimit the order budget
     */
    private void finishTrapAnimation(@NotNull Unit unit, int instructionLimit) {
        if ((unit.getOrderSignal() & Unit.ORDER_SIGNAL_ACTION_DONE) == 0) {
            return;
        }

        unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ACTION_DONE);

        this.finishIdle(unit, instructionLimit);
    }

    /**
     * Applies {@link ScrOrderIds#DISABLE_DOODAD}: the doodad runs its {@link IscriptExecutor#ANIMATION_DISABLE} animation and drops to the lowered
     * elevation; a door plays its opening sound ({@code units.dat} What Sound Start) and queues {@link ScrOrderIds#OPEN_DOOR} at the front; another
     * doodad becomes cloaked, requiring detection, undetected and disabled.
     * <p>
     * Disabled or frozen units follow the idle-order path; a nonbreaking script returns before the disable work.
     *
     * @param unit             the doodad
     * @param instructionLimit the order and script budget
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void disable(@NotNull Unit unit, int instructionLimit) {
        if (UnitOrderEligibility.isDisabled(unit)) {
            this.finishIdle(unit, instructionLimit);

            return;
        }

        if ((unit.getStatus() & UnitStatusFlags.ISCRIPT_NO_BREAK) != 0) {
            return;
        }

        int type = unit.getUnitId();
        Sprite sprite = unit.requireSprite();

        this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_DISABLE, 1, instructionLimit);

        if (type == ScrUnitIds.SPECIAL_FLOOR_MISSILE_TRAP || type >= ScrUnitIds.SPECIAL_UPPER_LEVEL_DOOR &&
                                                             type <= ScrUnitIds.SPECIAL_FLOOR_GUN_TRAP) {
            sprite.setElevationLevel(LOWERED_ELEVATION);
        }

        if (type >= ScrUnitIds.SPECIAL_UPPER_LEVEL_DOOR && type <= ScrUnitIds.SPECIAL_RIGHT_PIT_DOOR) {
            this.game.getUnitSounds().play(this.game.requireGameData().getUnitTypes().getWhatSoundStart(type), unit);
            this.game.getOrders().queueFront(unit, ScrOrderIds.OPEN_DOOR, null, 0, 0, ScrUnitIds.NONE);
            this.game.getOrders().completeOrder(unit, instructionLimit);

            return;
        }

        unit.setStatus(unit.getStatus() | (UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED) | UnitStatusFlags.DISABLED);
        unit.setDetectionFlags(Unit.DETECTION_FLAGS_UNDETECTED);
        unit.setVisibilityUpdateCountdown(0);
        this.finishIdle(unit, instructionLimit);
    }

    /**
     * Applies {@link ScrOrderIds#OPEN_DOOR}: after the action-done order signal the door stops colliding, drops to the lowered elevation, becomes
     * disabled and requests the computer unit-status refresh.
     * <p>
     * An absent action-done signal returns before any change. The computer refresh runs only after order completion.
     *
     * @param unit             the door
     * @param instructionLimit the order budget
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void openDoor(@NotNull Unit unit, int instructionLimit) {
        if ((unit.getOrderSignal() & Unit.ORDER_SIGNAL_ACTION_DONE) == 0) {
            return;
        }

        unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ACTION_DONE);
        unit.setStatus(unit.getStatus() | UnitStatusFlags.NO_COLLIDE);
        unit.requireSprite().setElevationLevel(LOWERED_ELEVATION);
        this.game.getOrders().issueUntargeted(unit,
                                              this.game.requireGameData().getUnitTypes().getReturnToIdleOrder(unit.getUnitId()),
                                              instructionLimit);
        unit.setStatus(unit.getStatus() | UnitStatusFlags.DISABLED);
        this.game.getOrders().completeOrder(unit, instructionLimit);

        ComputerPlayerPhase computer = this.game.getComputerPlayerPhase();

        if (computer != null) {
            computer.requestUnitStatusRefresh();
        }
    }

    /**
     * Applies {@link ScrOrderIds#CLOSE_DOOR}: after the action-done order signal the door collides again, restores its elevation, goes idle and marks
     * the units it overlaps as colliding.
     * <p>
     * An absent action-done signal returns before any change.
     *
     * @param unit             the door
     * @param instructionLimit the order budget
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void closeDoor(@NotNull Unit unit, int instructionLimit) {
        if ((unit.getOrderSignal() & Unit.ORDER_SIGNAL_ACTION_DONE) == 0) {
            return;
        }

        unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ACTION_DONE);
        unit.setStatus(unit.getStatus() & ~UnitStatusFlags.NO_COLLIDE);

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        unit.requireSprite().setElevationLevel(units.getElevationLevel(unit.getUnitId()));
        this.game.getOrders().issueUntargeted(unit, units.getReturnToIdleOrder(unit.getUnitId()), instructionLimit);
        this.game.getOrders().completeOrder(unit, instructionLimit);
        this.markOverlappingUnits(units, unit);
    }

    /**
     * Visits the units within the door's unit dimensions: a grounded building gives the door the collision status; air units and, unless the door is
     * a grounded building, gathering units are skipped; other units whose unit-finder box overlaps the door's receive the collision status.
     *
     * @param units the unit type table
     * @param door  the closed door
     * @throws UnsupportedOperationException if the unit finder is missing or the unit pool is not initialized
     */
    private void markOverlappingUnits(@NotNull ScrUnitTypeView units, @NotNull Unit door) {
        UnitSpatialIndex spatial = this.game.getUnitSystem().getUnitSpatialIndex();

        if (spatial == null) {
            throw Exceptions.newUnsupportedOperationException("CloseDoor collisions require the rectangle query.");
        }

        int type = door.getUnitId();
        int positionX = door.getPositionX();
        int positionY = door.getPositionY();
        int left = (positionX - units.getDimensionLeft(type)) & WORD_MASK;
        int top = (positionY - units.getDimensionUp(type)) & WORD_MASK;
        int right = (positionX + units.getDimensionRight(type)) & WORD_MASK;
        int bottom = (positionY + units.getDimensionDown(type)) & WORD_MASK;

        for (int poolIndex : spatial.findAllInRectangle(units, left, top, right, bottom)) {
            Unit found = this.game.getUnitSystem().requireUnitPool().getUnit(poolIndex);

            if (UnitQueries.isGroundedBuilding(found)) {
                door.setStatus(door.getStatus() | UnitStatusFlags.COLLISION);

                continue;
            }

            if ((found.getStatus() & UnitStatusFlags.FLYING) != 0) {
                continue;
            }

            if ((found.getStatus() & UnitStatusFlags.GATHERING) != 0 && !UnitQueries.isGroundedBuilding(door)) {
                continue;
            }

            if (door.getMinimumHorizontalEdgeIndex() < 0) {
                continue;
            }

            if (spatial.getCoordinate(true, found.getMaximumHorizontalEdgeIndex()) >=
                spatial.getCoordinate(true, door.getMinimumHorizontalEdgeIndex()) &&
                spatial.getCoordinate(true, found.getMinimumHorizontalEdgeIndex()) <=
                spatial.getCoordinate(true, door.getMaximumHorizontalEdgeIndex()) &&
                spatial.getCoordinate(false, found.getMaximumVerticalEdgeIndex()) >=
                spatial.getCoordinate(false, door.getMinimumVerticalEdgeIndex()) &&
                spatial.getCoordinate(false, found.getMinimumVerticalEdgeIndex()) <=
                spatial.getCoordinate(false, door.getMaximumVerticalEdgeIndex())) {
                found.setStatus(found.getStatus() | UnitStatusFlags.COLLISION);
            }
        }
    }

    /**
     * Applies {@link ScrOrderIds#CTFCOP_INIT}: before {@link #FLAG_BEACON_START_SECONDS} elapsed game seconds ({@code 0} before the trigger clock
     * exists) nothing happens. Afterwards the beacon's button set becomes empty, a Flag of the beacon's owner is constructed at the beacon with its
     * creation tag, shown and completed, and the beacon switches to {@link ScrOrderIds#CTFCOP_STARTED}.
     * <p>
     * The clock gate returns before the data lookup. Construction and completion can consume random values. A {@code null} result skips reveal and
     * completion but still reaches the final order.
     *
     * @param unit             the flag beacon
     * @param instructionLimit the creation, completion and order budget
     * @throws UnsupportedOperationException if accounting, the creation context or the owner's player is unavailable, or the unit pool is not initialized
     * @throws IllegalStateException         if the unit has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    public void initializeFlagBeacon(@NotNull Unit unit, int instructionLimit) {
        TriggerContext triggers = this.game.getTriggerContext();
        int seconds = triggers == null ? 0 : triggers.getElapsedClockTicks();

        if (Integer.compareUnsigned(seconds, FLAG_BEACON_START_SECONDS) < 0) {
            return;
        }

        UnitMorphing.writeButtonSet(this.game.requireGameData().getUnitTypes(), unit, ButtonSetIds.EMPTY);

        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();
        UnitCreationContext creation = this.game.getUnitSystem().getUnitCreationContext();
        Player owner = this.game.getPlayerTable().getPlayer(unit.getOwnerIndex());

        if (accounting == null || creation == null || owner == null) {
            throw Exceptions.newUnsupportedOperationException("Order CTFCOP_INIT requires the unit accounting, the creation context and the owner record.");
        }

        Sprite sprite = unit.requireSprite();
        Unit flag = this.game.getUnitStorageConstructor().create(creation,
                                                                 accounting,
                                                                 ScrUnitIds.POWERUP_FLAG,
                                                                 sprite.getX(), sprite.getY(),
                                                                 unit.getOwnerIndex(),
                                                                 this.game.getUnitSystem().requireUnitPool().getCreationTag(unit.getIndex()),
                                                                 instructionLimit);

        if (flag != null) {
            this.game.getUnitVisibility().show(flag, creation.isFoliageEnabled());
            this.game.getUnitCompletion().initializeBeforePlacement(creation, flag, instructionLimit);
            this.game.getUnitCompletion().completeAfterPlacement(creation, flag, owner.getType(), instructionLimit);
        }

        this.game.getOrders().issueUntargeted(unit, ScrOrderIds.CTFCOP_STARTED, instructionLimit);
    }

    /**
     * Applies {@link ScrOrderIds#CTFCOP_STARTED} to units within the beacon's dimensions. An own unit carrying a Flag releases it; a Flag of another
     * player is destroyed without a visible death. In Capture the Flag, the localized score message is marked as an unrepresented write. The order
     * then waits {@link #FLAG_BEACON_SCAN_DELAY} frames.
     * <p>
     * The beacon countdown is written only after all visited units finish.
     *
     * @param unit             the flag beacon
     * @param instructionLimit the order budget
     * @throws UnsupportedOperationException if the unit finder is missing or the unit pool is not initialized
     * @throws NullPointerException          if the world was built without game data
     */
    public void updateFlagBeacon(@NotNull Unit unit, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        UnitSpatialIndex spatial = this.game.getUnitSystem().getUnitSpatialIndex();

        if (spatial == null) {
            throw Exceptions.newUnsupportedOperationException("Order CTFCOP_STARTED requires the unit spatial index.");
        }

        int type = unit.getUnitId();
        int positionX = unit.getPositionX();
        int positionY = unit.getPositionY();
        int left = (positionX - units.getDimensionLeft(type)) & WORD_MASK;
        int top = (positionY - units.getDimensionUp(type)) & WORD_MASK;
        int right = (positionX + units.getDimensionRight(type)) & WORD_MASK;
        int bottom = (positionY + units.getDimensionDown(type)) & WORD_MASK;

        for (int poolIndex : spatial.findAllInRectangleUnexpanded(left, top, right, bottom)) {
            Unit found = this.game.getUnitSystem().requireUnitPool().getUnit(poolIndex);

            if (found.getOwnerIndex() == unit.getOwnerIndex()) {
                Unit carriedFlag = this.game.getUnitQueries().getCarriedFlag(found);

                if (carriedFlag != null) {
                    this.game.getCaptureTheFlag().markFlagScoredUnrepresented(carriedFlag.getOwnerIndex());
                    this.game.getUnitPowerups().release(found, instructionLimit);
                }
            } else if (found.getUnitId() == ScrUnitIds.POWERUP_FLAG) {
                this.game.getCaptureTheFlag().markFlagScoredUnrepresented(found.getOwnerIndex());
                found.setUserActionFlags(found.getUserActionFlags() | Unit.USER_ACTION_HIDE_ON_DEATH);
                this.game.getUnitLifecycle().kill(found, instructionLimit);
            }
        }

        unit.setPrimaryOrderCountdown(FLAG_BEACON_SCAN_DELAY);
    }

    /**
     * Applies {@link ScrOrderIds#CTFCOP2}, issued to a flag beacon by the build command during the first {@link #FLAG_BEACON_START_SECONDS} seconds.
     * When the beacon's own type may be placed at the order target, the beacon leaves the unit finder, moves there, is registered again and takes the
     * empty button set; otherwise only the placement error message is shown (presentation). Either way {@link ScrOrderIds#CTFCOP_INIT} is issued.
     * <p>
     * A failed placement emits its error event; both paths reach order issuance.
     *
     * @param unit             the flag beacon
     * @param instructionLimit the order budget
     * @throws UnsupportedOperationException if the creation context or the unit finder is missing
     * @throws IllegalStateException         if the unit has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    public void placeFlagBeacon(@NotNull Unit unit, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = unit.getUnitId();
        int positionX = (short) unit.getOrderTargetX();
        int positionY = (short) unit.getOrderTargetY();

        if (this.game.getUnitPlacement().canPlaceBuilding(unit, unit.getOwnerIndex(), type, positionX, positionY, true, false)) {
            UnitCreationContext creation = this.game.getUnitSystem().getUnitCreationContext();
            UnitSpatialIndex spatial = this.game.getUnitSystem().getUnitSpatialIndex();

            if (creation == null || spatial == null) {
                throw Exceptions.newUnsupportedOperationException("Flag beacon placement requires the creation context and the unit finder.");
            }

            Sprite sprite = unit.requireSprite();

            spatial.remove(unit);

            if (UnitQueries.isGroundedBuilding(unit)) {
                this.game.getUnitPositioning().setFootprintOccupied(type, (short) sprite.getX(), (short) sprite.getY(), false);
            }

            if ((unit.getStatus() & UnitStatusFlags.FLYING) != 0) {
                spatial.unregisterFlyingUnit(unit, units);
            }

            this.game.getUnitPositioning().move(unit, positionX, positionY);
            this.game.getUnitStorageConstructor().registerSpatially(unit, creation.isFoliageEnabled());
            UnitMorphing.writeButtonSet(units, unit, ButtonSetIds.EMPTY);
        } else {
            // The placement code's text with the invalid-location sound.
            int tileX = (positionX - units.getPlacementWidth(type) / PLACEMENT_HALF_DIVISOR) / TerrainTileset.TILE_SIZE;
            int tileY = (positionY - units.getPlacementHeight(type) / PLACEMENT_HALF_DIVISOR) / TerrainTileset.TILE_SIZE;
            int code = this.game.getUnitPlacement().evaluateBuilding(unit, unit.getOwnerIndex(), type, tileX, tileY, false, true, false, null).getCode();

            this.game.getInterfaceLink().getCommandErrorEvents().raiseInvalidBuildLocation(this.game, unit, code);
        }

        this.game.getOrders().issueUntargeted(unit, ScrOrderIds.CTFCOP_INIT, instructionLimit);
    }

    /**
     * Issues the {@code units.dat} Return to Idle order when the order queue is empty, then completes the order; it ends the disabled and non-door
     * paths of {@link ScrOrderIds#ENABLE_DOODAD} and {@link ScrOrderIds#DISABLE_DOODAD} and the trap animation of {@link ScrOrderIds#HIDE_TRAP} and
     * {@link ScrOrderIds#REVEAL_TRAP} ({@link #finishTrapAnimation}).
     *
     * @param unit             the doodad or trap
     * @param instructionLimit the order budget
     * @throws NullPointerException if the world was built without game data
     */
    private void finishIdle(@NotNull Unit unit, int instructionLimit) {
        if (unit.getFirstOrder() == null) {
            this.game.getOrders().issueUntargeted(unit,
                                                  this.game.requireGameData().getUnitTypes().getReturnToIdleOrder(unit.getUnitId()),
                                                  instructionLimit);
        }

        this.game.getOrders().completeOrder(unit, instructionLimit);
    }
}
