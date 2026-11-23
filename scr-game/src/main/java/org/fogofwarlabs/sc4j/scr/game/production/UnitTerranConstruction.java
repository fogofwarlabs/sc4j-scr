package org.fogofwarlabs.sc4j.scr.game.production;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnKey;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnReset;
import org.fogofwarlabs.sc4j.engine.entity.column.IntColumn;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.movement.MovementCollision;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerRelations;
import org.fogofwarlabs.sc4j.scr.game.presentation.NotificationEvents;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Runs SCV building placement, construction, incomplete-building animation stages and repair.
 * <p>
 * The SCV reserves the building type in its production queue when the build command is accepted, walks to the site, pays the cost and creates the
 * incomplete building, then moves around the footprint while its construction animation advances the building each frame. A building left without its
 * builder stays incomplete until another SCV resumes it.
 * <p>
 * The repair timer of the native unit union is registered as a unit pool column ({@link #REPAIR_COLUMNS}). The column
 * keeps its value when a slot is reused, as the union field does; unit creation and the unit reset methods set it.
 */
public final class UnitTerranConstruction {
    /**
     * The repair timer WORD ({@link Unit#getRepairTimer()}).
     */
    public static final @NotNull ColumnKey<IntColumn> REPAIR_TIMER_COLUMN = ColumnKey.ofInt("repairTimer", 0, ColumnReset.KEEP);
    /**
     * The unit pool columns of the repair state, in registration order.
     */
    public static final @NotNull List<@NotNull ColumnKey<?>> REPAIR_COLUMNS = List.of(
            REPAIR_TIMER_COLUMN
    );

    /**
     * The fraction bits of a fixed-point position.
     */
    private static final int FIXED_POINT_SHIFT = 8;
    /**
     * The mask of a WORD: the horizontal coordinate of a packed position, repair prices and repair intervals.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of a WORD of the packed unit dimensions.
     */
    private static final long LONG_WORD_MASK = 0xFFFFL;
    /**
     * The bit offsets of the second, third and fourth WORD of a packed position, unit dimensions or terrain rectangle.
     */
    private static final int SECOND_WORD_SHIFT = 16;
    private static final int THIRD_WORD_SHIFT = 32;
    private static final int FOURTH_WORD_SHIFT = 48;
    /**
     * The divisor from a sprite or placement box size to the offset of its center.
     */
    private static final int CENTER_DIVISOR = 2;
    /**
     * The default construction order result for a transport the SCV may enter.
     */
    private static final int ENTER_TRANSPORT_RESULT = 0;

    /**
     * The pixel distance within which an SCV at its destination places the building.
     */
    private static final int PLACEMENT_REACH = 0x80;

    /**
     * The order states of {@link ScrOrderIds#PLACE_BUILDING}: started, walking to the site.
     */
    private static final int PLACE_STATE_START = 0;
    private static final int PLACE_STATE_MOVING = 1;

    /**
     * The order states of {@link ScrOrderIds#CONSTRUCTING_BUILDING}: started, walking to the building, moving onto it.
     */
    private static final int CONSTRUCT_STATE_START = 0;
    private static final int CONSTRUCT_STATE_APPROACHING = 2;
    private static final int CONSTRUCT_STATE_ENTERING = 3;
    /**
     * The order states of {@link ScrOrderIds#CONSTRUCTING_BUILDING}: picking a point, moving to it, arriving, facing, building.
     */
    private static final int CONSTRUCT_STATE_PICKING = 4;
    private static final int CONSTRUCT_STATE_MOVING = 5;
    private static final int CONSTRUCT_STATE_ARRIVING = 6;
    private static final int CONSTRUCT_STATE_FACING = 7;
    private static final int CONSTRUCT_STATE_BUILDING = 8;

    /**
     * The order states of {@link ScrOrderIds#INCOMPLETE_BUILDING}: the stages and the animations that start them.
     */
    private static final int INCOMPLETE_STATE_FIRST_STAGE = 0;
    private static final int INCOMPLETE_STATE_FIRST_ANIMATION = 1;
    private static final int INCOMPLETE_STATE_SECOND_STAGE = 2;
    private static final int INCOMPLETE_STATE_SECOND_ANIMATION = 3;
    private static final int INCOMPLETE_STATE_THIRD_STAGE = 4;
    private static final int INCOMPLETE_STATE_THIRD_ANIMATION = 5;
    private static final int INCOMPLETE_STATE_FOURTH_STAGE = 6;
    /**
     * The divisor into fifths of the maximum hit points, and the fifths above which the incomplete building leaves its second, third and fourth
     * stage; it leaves its first stage above {@code 1/5}.
     */
    private static final int STAGE_DIVISOR = 5;
    private static final int SECOND_STAGE_FIFTHS = 2;
    private static final int THIRD_STAGE_FIFTHS = 3;
    private static final int FOURTH_STAGE_FIFTHS = 4;

    /**
     * The order states of {@link ScrOrderIds#REPAIR}: started, deciding, following, facing, repairing.
     */
    private static final int REPAIR_STATE_START = 0;
    private static final int REPAIR_STATE_DECIDING = 1;
    private static final int REPAIR_STATE_FOLLOWING = 6;
    private static final int REPAIR_STATE_FACING = 7;
    private static final int REPAIR_STATE_REPAIRING = 8;
    /**
     * The pixel range within which an SCV repairs its target.
     */
    private static final int REPAIR_RANGE = 5;
    /**
     * The factor of the maximum hit points in the repair time and cost rate.
     */
    private static final int REPAIR_HIT_POINT_FACTOR = 3;
    /**
     * The repair time of a unit without a cost.
     */
    private static final int FREE_REPAIR_TIME = 0xFFFF;

    /**
     * The random source of the construction point.
     */
    private static final int CONSTRUCTION_POINT_RANDOM_SOURCE = 9;
    /**
     * The random source, base and random mask of the construction animation timer.
     */
    private static final int CONSTRUCTION_TIMER_RANDOM_SOURCE = 10;
    private static final int CONSTRUCTION_TIMER_BASE = 30;
    private static final int CONSTRUCTION_TIMER_RANDOM_MASK = 63;
    /**
     * The pixel distance within which the SCV stops at its construction point, and the length of its facing vector.
     */
    private static final int CONSTRUCTION_POINT_REACH = 20;
    private static final int FACING_VECTOR_LENGTH = 20;

    /**
     * The extent around the unit of the cached collision-free terrain rectangle.
     */
    private static final int COLLISION_RECTANGLE_EXTENT = 128;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitTerranConstruction} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitTerranConstruction(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Selects construction resumption, repair or transport entry for an SCV right-click target: {@link ScrOrderIds#CONSTRUCTING_BUILDING} for an
     * own incomplete Terran building without an active builder, {@link ScrOrderIds#REPAIR} for a damaged completed allied Terran mechanical unit,
     * {@code 0} for a transport the SCV may enter, or {@code -1} for the ordinary movement action.
     *
     * @param worker the selected SCV
     * @param target the clicked unit
     * @return the order, {@code 0} to enter a transport or {@code -1} for the ordinary movement action
     * @throws UnsupportedOperationException if player alliances are missing
     * @throws NullPointerException          if the world was built without game data
     */
    public int getDefaultConstructionOrder(@NotNull Unit worker, @NotNull Unit target) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int targetType = target.getUnitId();
        int targetStatus = target.getStatus();
        boolean terran = (units.getGroupFlags(targetType) & UnitGroupFlags.TERRAN) != 0;

        if (UnitQueries.isGroundedBuilding(target) && (targetStatus & UnitStatusFlags.COMPLETED) == 0) {
            if (target.getOwnerIndex() == worker.getOwnerIndex() && terran && (units.getFlags(targetType) & UnitTypeFlags.ADDON) == 0) {
                Unit connected = target.getParentUnit();

                if (connected == null || connected.getOrderTargetUnit() != target) {
                    return ScrOrderIds.CONSTRUCTING_BUILDING;
                }
            }

            return -1;
        }

        PlayerRelations relations = this.game.getPlayerTable().getPlayerRelations();

        if (relations == null) {
            throw Exceptions.newUnsupportedOperationException("Default repair eligibility requires player alliances.");
        }

        if (relations.getAlliance(worker.getOwnerIndex(), target.getOwnerIndex()) == PlayerRelations.ENEMY) {
            return -1;
        }

        boolean canEnter = this.game.getUnitCommands().canTransport(target, worker);

        if (((units.getFlags(targetType) & UnitTypeFlags.BUILDING) != 0 || !canEnter) && terran &&
            (units.getFlags(targetType) & UnitTypeFlags.MECHANICAL) != 0 && (targetStatus & UnitStatusFlags.COMPLETED) != 0 &&
            target.getHitPoints() < units.getHitPoints(targetType)) {
            return ScrOrderIds.REPAIR;
        }

        return canEnter ? ENTER_TRANSPORT_RESULT : -1;
    }

    /**
     * Moves the SCV to the site, charges construction costs and creates the incomplete building.
     * <p>
     * The cost is charged only when the SCV can move, is within {@code 128} pixels of the site, and supply, resources and placement permit the
     * building. The SCV clears its ground-unit flag, raises sprite elevation and continues with {@link ScrOrderIds#CONSTRUCTING_BUILDING}.
     *
     * @param builder          the SCV executing {@link ScrOrderIds#PLACE_BUILDING}
     * @param instructionLimit the order, creation, ownership, morph and image budget
     * @throws IllegalStateException         if the creation context is missing or the builder or the target has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit accounting or the unit pool is not initialized
     */
    public void placeBuilding(@NotNull Unit builder, int instructionLimit) {
        // A builder with a computer record ignores unit collisions.
        if (builder.getComputerRecord() != null) {
            builder.setStatus(builder.getStatus() | UnitStatusFlags.GATHERING);
        }

        int phase = builder.getPrimaryOrderState();

        if (phase == PLACE_STATE_START) {
            Unit target = builder.getOrderTargetUnit();

            if (target != null) {
                Sprite targetSprite = target.requireSprite();

                builder.setOrderTargetX(targetSprite.getX());
                builder.setOrderTargetY(targetSprite.getY());
            }

            int positionX = (short) builder.getOrderTargetX();
            int positionY = (short) builder.getOrderTargetY();

            this.game.getEngineGame().getMovement().setDestination(builder, positionX, positionY);
            builder.setDirectionTargetX(positionX);
            builder.setDirectionTargetY(positionY);
            builder.setPrimaryOrderState(PLACE_STATE_MOVING);

            return;
        }

        if (phase != PLACE_STATE_MOVING || !this.game.getEngineGame().getMovement().isAtDestination(builder)) {
            return;
        }

        int positionX = (short) builder.getOrderTargetX();
        int positionY = (short) builder.getOrderTargetY();
        int distance = RegionPathSearch.calculateDistance(0, (positionX << FIXED_POINT_SHIFT) - builder.getFixedPositionX(),
                                                          0, (positionY << FIXED_POINT_SHIFT) - builder.getFixedPositionY()) >> FIXED_POINT_SHIFT;

        if ((builder.getStatus() & UnitStatusFlags.IMMOVABLE) != 0 || distance > PLACEMENT_REACH) {
            this.game.getOrders().finishOrder(builder, instructionLimit);

            return;
        }

        // Canceling training while the SCV walks can leave an empty queue slot (228); it is not checked, and the site builds a unit of that type.
        int buildType = UnitQueries.getQueuedType(builder);

        // The cost and supply test shows its error; a blocked site shows "You can't build there.".
        if (!this.game.getCostTests().testUnit(this.game, builder.getOwnerIndex(), buildType, true, true)) {
            this.game.getOrders().finishOrder(builder, instructionLimit);

            return;
        }

        if (!this.game.getUnitPlacement().canPlaceBuilding(builder, builder.getOwnerIndex(), buildType, positionX, positionY, true, false)) {
            this.game.getInterfaceLink().getCommandErrorEvents().raiseBlockedBuildLocation(this.game, builder);
            this.game.getOrders().finishOrder(builder, instructionLimit);

            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        this.game.getCostTests().spend(this.game, builder.getOwnerIndex());
        builder.setStatus(builder.getStatus() & ~UnitStatusFlags.GROUND_UNIT);
        builder.requireSprite().setElevationLevel(units.getElevationLevel(builder.getUnitId()) + 1);

        if (builder.getFirstOrder() == null) {
            this.game.getOrders().queueBack(builder, ScrOrderIds.RESET_COLLISION, null, 0, 0, ScrUnitIds.NONE);
            this.game.getOrders().queueBack(builder, units.getReturnToIdleOrder(builder.getUnitId()), null, 0, 0, ScrUnitIds.NONE);
        } else {
            this.game.getOrders().queueFront(builder, ScrOrderIds.RESET_COLLISION, null, 0, 0, ScrUnitIds.NONE);
        }

        // The site type is read again: past the 5 queue slots the WORD can be builder state that the steps above changed.
        buildType = UnitQueries.getQueuedType(builder);

        Unit building;

        if (buildType == ScrUnitIds.TERRAN_REFINERY) {
            building = this.game.getUnitConstruction().buildRefinery(builder, buildType, instructionLimit);
        } else {
            UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

            if (context == null) {
                throw Exceptions.newIllegalStateException("Construction requires initialized creation context.");
            }

            building = this.game.getUnitStorageConstructor().create(context,
                                                                    this.game.getPlayerTable().requireUnitAccounting(),
                                                                    buildType,
                                                                    positionX, positionY,
                                                                    builder.getOwnerIndex(),
                                                                    this.game.getUnitSystem().requireUnitPool().getCreationTag(builder.getIndex()),
                                                                    instructionLimit);
        }

        UnitLifecycle.clearCurrentQueueSlot(builder);

        if (building == null) {
            // The builder's owner sees the pending creation message, then the SCV returns to idle.
            this.game.getUnitStorageConstructor().notifyCreationFailure(builder.getOwnerIndex());
            this.game.getOrders().issueUntargeted(builder, units.getReturnToIdleOrder(builder.getUnitId()), instructionLimit);

            return;
        }

        building.setParentUnit(builder);
        builder.setPrimaryOrder(ScrOrderIds.CONSTRUCTING_BUILDING);
        builder.setPrimaryOrderState(CONSTRUCT_STATE_ENTERING);
        builder.setOrderTargetUnit(building);
        this.game.getOrders().issueUntargeted(building, ScrOrderIds.INCOMPLETE_BUILDING, instructionLimit);
        this.game.getComputerUnits().notifyCreated(builder, building, instructionLimit);
    }

    /**
     * Advances an SCV constructing its target building.
     * <p>
     * A resuming SCV first walks to the building and claims it unless another SCV is constructing it. The SCV then repeatedly picks a random point on
     * the building sprite (source {@code 9}), moves there, faces the building and plays its construction animation for {@code 30 + (random(10) & 63)}
     * frames. From state {@code 4} on, every frame resumes the building. A completed or lost target ends the order; a completed Refinery sends the
     * SCV to harvest gas.
     * <p>
     * Pickup changes are kept before an early return.
     *
     * @param builder          the SCV executing {@link ScrOrderIds#CONSTRUCTING_BUILDING}
     * @param instructionLimit the order, animation and construction budget
     * @throws IllegalStateException if the builder or the target has no sprite, or the state is outside the state machine
     * @throws NullPointerException  if the world was built without game data
     */
    public void updateConstructing(@NotNull Unit builder, int instructionLimit) {
        Unit target = builder.getOrderTargetUnit();

        if (target == null || (target.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
            this.finishConstructing(builder, target, instructionLimit);

            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        Sprite sprite = builder.requireSprite();
        Sprite targetSprite = target.requireSprite();
        int phase = builder.getPrimaryOrderState();
        boolean build = phase >= CONSTRUCT_STATE_PICKING;

        if (phase == CONSTRUCT_STATE_START || phase == CONSTRUCT_STATE_APPROACHING) {
            if (phase == CONSTRUCT_STATE_START) {
                if (!this.game.getUnitTargetMovement().moveToTargetReset(builder, target)) {
                    return;
                }

                builder.setPrimaryOrderState(CONSTRUCT_STATE_APPROACHING);
            }

            if (this.game.getEngineGame().getMovement().isAtDestination(builder)) {
                Unit connected = target.getParentUnit();

                if ((builder.getStatus() & UnitStatusFlags.IMMOVABLE) != 0 ||
                    connected != null && connected != builder && connected.getOrderTargetUnit() == target) {
                    this.game.getOrders().finishOrder(builder, instructionLimit);
                } else {
                    target.setParentUnit(builder);
                    builder.setStatus(builder.getStatus() & ~UnitStatusFlags.GROUND_UNIT);
                    builder.requireSprite().setElevationLevel(units.getElevationLevel(builder.getUnitId()) + 1);
                    this.game.getOrders().queueReplacingSameOrders(builder, ScrOrderIds.RESET_COLLISION, null, 0, 0, ScrUnitIds.NONE);
                    this.game.getOrders().queueReplacingSameOrders(builder, units.getReturnToIdleOrder(builder.getUnitId()), null, 0, 0, ScrUnitIds.NONE);
                    this.game.getEngineGame().getMovement().setDestination(builder, targetSprite.getX(), targetSprite.getY());
                    builder.setDirectionTargetX(targetSprite.getX());
                    builder.setDirectionTargetY(targetSprite.getY());
                    builder.setPrimaryOrderState(CONSTRUCT_STATE_ENTERING);
                }
            }
        } else if (phase == CONSTRUCT_STATE_ENTERING) {
            if (this.game.getEngineGame().getMovement().isAtDestination(builder)) {
                builder.setPrimaryOrderState(CONSTRUCT_STATE_PICKING);
            }
        } else if (phase == CONSTRUCT_STATE_PICKING) {
            int targetType = target.getUnitId();
            int builderType = builder.getUnitId();
            int spriteLeft = targetSprite.getX() - targetSprite.getWidth() / CENTER_DIVISOR;
            int spriteUpper = targetSprite.getY() - targetSprite.getHeight() / CENTER_DIVISOR;
            int spriteRight = spriteLeft + targetSprite.getWidth() - 1;
            int spriteLower = spriteUpper + targetSprite.getHeight() - 1;
            int placementWidth = units.getPlacementWidth(targetType);
            int placementHeight = units.getPlacementHeight(targetType);
            int placementLeft = targetSprite.getX() - placementWidth / CENTER_DIVISOR;
            int placementUpper = targetSprite.getY() - placementHeight / CENTER_DIVISOR;
            int placementRight = placementLeft + placementWidth - 1;
            int placementLower = placementUpper + placementHeight - 1;
            int insideLeft = Math.max(placementLeft + units.getDimensionLeft(builderType) + 1, spriteLeft);
            int insideUpper = Math.max(placementUpper + units.getDimensionUp(builderType) + 1, spriteUpper);
            int insideRight = Math.min(placementRight - units.getDimensionRight(builderType) - 1, spriteRight);
            int insideLower = Math.min(placementLower - units.getDimensionDown(builderType) - 1, spriteLower);
            int positionX = this.game.getRandom().next(CONSTRUCTION_POINT_RANDOM_SOURCE, spriteLeft, spriteRight);
            int positionY = this.game.getRandom().next(CONSTRUCTION_POINT_RANDOM_SOURCE, spriteUpper, spriteLower);
            int position = this.restrictMoveTargetToValidBounds(builderType, positionX, positionY);

            positionX = (short) position;
            positionY = position >> SECOND_WORD_SHIFT;

            if (this.isMoveBlockedByTerrainContours(builder, positionX, positionY)) {
                positionX = this.game.getRandom().next(CONSTRUCTION_POINT_RANDOM_SOURCE, insideLeft, insideRight);
                positionY = this.game.getRandom().next(CONSTRUCTION_POINT_RANDOM_SOURCE, insideUpper, insideLower);
                position  = this.restrictMoveTargetToValidBounds(builderType, positionX, positionY);
                positionX = (short) position;
                positionY = position >> SECOND_WORD_SHIFT;
            }

            this.game.getEngineGame().getMovement().setDestination(builder, positionX, positionY);
            builder.setDirectionTargetX(positionX);
            builder.setDirectionTargetY(positionY);
            builder.setOrderTargetX(positionX);
            builder.setOrderTargetY(positionY);
            builder.setPrimaryOrderState(CONSTRUCT_STATE_MOVING);
        } else if (phase == CONSTRUCT_STATE_MOVING) {
            builder.setPrimaryOrderState(CONSTRUCT_STATE_ARRIVING);
        } else if (phase == CONSTRUCT_STATE_ARRIVING) {
            int targetX = (short) builder.getOrderTargetX();
            int targetY = (short) builder.getOrderTargetY();
            int distance = RegionPathSearch.calculateDistance(0, (targetX << FIXED_POINT_SHIFT) - builder.getFixedPositionX(),
                                                              0, (targetY << FIXED_POINT_SHIFT) - builder.getFixedPositionY()) >> FIXED_POINT_SHIFT;

            if (distance <= CONSTRUCTION_POINT_REACH) {
                this.game.getEngineGame().getMovement().stop(builder);
                builder.setDirectionTargetX(targetSprite.getX());
                builder.setDirectionTargetY(targetSprite.getY());

                int direction = FlingyMotionUtils.calculateDirection(targetSprite.getX() - sprite.getX(), targetSprite.getY() - sprite.getY());
                long offset = FlingyMotionUtils.calculateDirectionVector(direction, FACING_VECTOR_LENGTH);

                builder.setOrderTargetX(sprite.getX() + (int) offset);
                builder.setOrderTargetY(sprite.getY() + (int) (offset >> Integer.SIZE));
                builder.setPrimaryOrderState(CONSTRUCT_STATE_FACING);
            }
        } else if (phase == CONSTRUCT_STATE_FACING) {
            if (this.isAtDirectionTargetOrWithinAttackAngle(builder)) {
                int timer = CONSTRUCTION_TIMER_BASE + (this.game.getRandom().next(CONSTRUCTION_TIMER_RANDOM_SOURCE) & CONSTRUCTION_TIMER_RANDOM_MASK);

                builder.setOrderTimer(timer);
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, builder, IscriptExecutor.ANIMATION_ALMOST_BUILT, 1, instructionLimit);
                builder.setPrimaryOrderState(CONSTRUCT_STATE_BUILDING);
            }
        } else if (phase == CONSTRUCT_STATE_BUILDING) {
            if (builder.getOrderTimer() == 0) {
                this.game.getIscriptExecutor().runSpriteAnimation(sprite,
                                                                  builder,
                                                                  IscriptExecutor.ANIMATION_GROUND_ATTACK_TO_IDLE,
                                                                  1,
                                                                  instructionLimit);
                builder.setPrimaryOrderState(CONSTRUCT_STATE_PICKING);
            }
        } else {
            throw Exceptions.newIllegalStateException("ConstructingBuilding state is outside its state machine: {0}", phase);
        }

        if (build) {
            this.resumeBuilding(target, instructionLimit);

            if ((target.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
                // The SCV's work-done notification.
                this.game.getInterfaceLink().getNotificationEvents().raise(NotificationEvents.Kind.WORK_DONE, builder);
                this.finishConstructing(builder, target, instructionLimit);
            }
        }
    }

    private void finishConstructing(@NotNull Unit builder, @Nullable Unit target, int instructionLimit) {
        this.game.getIscriptExecutor().runSpriteAnimation(builder.requireSprite(),
                                                          builder,
                                                          IscriptExecutor.ANIMATION_WALKING_TO_IDLE,
                                                          1,
                                                          instructionLimit);

        if (target != null) {
            target.setParentUnit(null);

            if (this.game.getUnitHarvesting().canGatherGas(builder, target)) {
                this.game.getOrders().issue(builder, ScrOrderIds.HARVEST1, target, 0, 0, ScrUnitIds.NONE, false, instructionLimit);

                return;
            }
        }

        this.game.getOrders().finishOrder(builder, instructionLimit);
    }

    /**
     * Applies the facing test of the {@link ScrOrderIds#CONSTRUCTING_BUILDING} and {@link ScrOrderIds#REPAIR} facing states: the unit stands on its
     * direction target, or its heading is within the ground weapon's attack angle of it.
     * <p>
     * A unit without a ground weapon ({@code 130}) reads the {@code weapons.dat} memory past the attack angle column, a {@code 0} alignment gap, and
     * must face the target exactly unless it already stands at its direction target.
     *
     * @param unit the facing unit
     * @return {@code true} if the unit faces its direction target, {@code false} otherwise
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    private boolean isAtDirectionTargetOrWithinAttackAngle(@NotNull Unit unit) {
        if (unit.getPositionX() == (short) unit.getDirectionTargetX() && unit.getPositionY() == (short) unit.getDirectionTargetY()) {
            return true;
        }

        int weapon = this.game.getUnitTargeting().getGroundWeapon(unit);
        int attackAngle = this.game.requireGameData().getWeaponTypes().getAttackAngle(weapon);
        Sprite sprite = unit.requireSprite();
        int direction = FlingyMotionUtils.calculateDirection((short) unit.getDirectionTargetX() - sprite.getX(),
                                                             (short) unit.getDirectionTargetY() - sprite.getY());

        return Math.abs((byte) (unit.getHeading() - direction)) <= attackAngle;
    }

    /**
     * Advances incomplete Terran building animations by hit points: {@link IscriptExecutor#ANIMATION_SPECIAL_STATE_1} above {@code 1/5},
     * {@link IscriptExecutor#ANIMATION_SPECIAL_STATE_2} above {@code 2/5}, the final building graphic with
     * {@link IscriptExecutor#ANIMATION_ALMOST_BUILT} above {@code 3/5}, and the {@link ScrOrderIds#NOTHING} order above {@code 4/5}.
     *
     * @param building         the incomplete building
     * @param instructionLimit the animation, construction image and order budget
     * @throws IllegalStateException if the building has no sprite or the state is outside the state machine
     * @throws NullPointerException  if the world was built without game data
     */
    public void updateIncompleteBuilding(@NotNull Unit building, int instructionLimit) {
        int maximumHitPoints = this.game.requireGameData().getUnitTypes().getHitPoints(building.getUnitId());
        int hitPoints = building.getHitPoints();
        Sprite sprite = building.requireSprite();
        int phase = building.getPrimaryOrderState();

        switch (phase) {
            case INCOMPLETE_STATE_FIRST_STAGE -> {
                if (hitPoints > maximumHitPoints / STAGE_DIVISOR) {
                    building.setPrimaryOrderState(INCOMPLETE_STATE_FIRST_ANIMATION);
                }
            }
            case INCOMPLETE_STATE_FIRST_ANIMATION -> {
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, building, IscriptExecutor.ANIMATION_SPECIAL_STATE_1, 1, instructionLimit);
                building.setPrimaryOrderState(INCOMPLETE_STATE_SECOND_STAGE);
            }
            case INCOMPLETE_STATE_SECOND_STAGE -> {
                if (hitPoints > maximumHitPoints * SECOND_STAGE_FIFTHS / STAGE_DIVISOR) {
                    building.setPrimaryOrderState(INCOMPLETE_STATE_SECOND_ANIMATION);
                }
            }
            case INCOMPLETE_STATE_SECOND_ANIMATION -> {
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, building, IscriptExecutor.ANIMATION_SPECIAL_STATE_2, 1, instructionLimit);
                building.setPrimaryOrderState(INCOMPLETE_STATE_THIRD_STAGE);
            }
            case INCOMPLETE_STATE_THIRD_STAGE -> {
                if (hitPoints > maximumHitPoints * THIRD_STAGE_FIFTHS / STAGE_DIVISOR) {
                    building.setPrimaryOrderState(INCOMPLETE_STATE_THIRD_ANIMATION);
                }
            }
            case INCOMPLETE_STATE_THIRD_ANIMATION -> {
                this.game.getIscriptExecutor().initializeConstructionImage(building, IscriptExecutor.COMPLETED_IMAGE_MODE, instructionLimit);
                this.game.getIscriptExecutor().runSpriteAnimation(building.requireSprite(),
                                                                  building,
                                                                  IscriptExecutor.ANIMATION_ALMOST_BUILT,
                                                                  1,
                                                                  instructionLimit);
                building.setPrimaryOrderState(INCOMPLETE_STATE_FOURTH_STAGE);
            }
            case INCOMPLETE_STATE_FOURTH_STAGE -> {
                if (hitPoints > maximumHitPoints * FOURTH_STAGE_FIFTHS / STAGE_DIVISOR) {
                    this.game.getOrders().issueUntargeted(building, ScrOrderIds.NOTHING, instructionLimit);
                }
            }
            default -> throw Exceptions.newIllegalStateException("IncompleteBuilding state is outside its state machine: {0}", phase);
        }
    }

    /**
     * Advances construction hit points and build time, completing the building when no build time remains.
     * <p>
     * A completed grounded Terran building connects a neutral add-on at its add-on position.
     *
     * @param building         the incomplete building
     * @param instructionLimit the hit point image, completion and add-on connection budget
     * @throws IllegalStateException if the unit creation context or the owner is missing
     * @throws NullPointerException  if the world was built without game data
     */
    void resumeBuilding(@NotNull Unit building, int instructionLimit) {
        this.game.getUnitHealth().setHitPoints(building,
                                               building.getHitPoints() + this.game.getUnitBuildTime().getHitPointIncrement(building),
                                               instructionLimit);

        int remaining = building.getRemainingBuildTime();

        if (remaining != 0) {
            this.game.getUnitBuildTime().advance(building);

            return;
        }

        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();
        Player player = this.game.getPlayerTable().getPlayer(building.getOwnerIndex());

        if (context == null || player == null) {
            throw Exceptions.newIllegalStateException("Construction completion requires its creation context and owner.");
        }

        this.game.getUnitCompletion().initializeBeforePlacement(context, building, instructionLimit);
        this.game.getUnitCompletion().completeAfterPlacement(context, building, player.getType(), instructionLimit);

        if (UnitQueries.isGroundedBuilding(building) &&
            (this.game.requireGameData().getUnitTypes().getGroupFlags(building.getUnitId()) & UnitGroupFlags.TERRAN) != 0) {
            this.game.getUnitAddons().findAndConnect(building, instructionLimit);
        }
    }

    /**
     * Repairs a damaged completed Terran mechanical unit. Incomplete targets end the repair order.
     * <p>
     * Startup checks resources without charging and ignores the availability result before storing its deciding state and a timer of {@code 0}.
     * Repair decrements the unsigned WORD timer before testing its old value. An old value of {@code 0} that cannot afford the charge remains
     * {@code 65535} before the order ends. The selected nonzero price determines the cost rate. The SCV follows the target, faces it and plays its
     * construction animation within {@code 5} pixels.
     * <p>
     * Pickup requests keep their order/queue changes and prevent the ordinary following-state stores.
     *
     * @param worker           the repairing SCV
     * @param instructionLimit the order, animation and hit point image budget
     * @throws IllegalStateException if the unit is not a worker, or the worker or the target has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void updateRepair(@NotNull Unit worker, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if ((units.getFlags(worker.getUnitId()) & UnitTypeFlags.WORKER) == 0) {
            throw Exceptions.newIllegalStateException("Repair requires a worker.");
        }

        Unit target = worker.getOrderTargetUnit();
        Sprite sprite = worker.requireSprite();

        if (target == null || target.getHitPoints() >= units.getHitPoints(target.getUnitId()) || target.getStasisTimer() != 0 ||
            (target.getStatus() & UnitStatusFlags.LOADED) != 0 ||
            (units.getFlags(target.getUnitId()) & UnitTypeFlags.MECHANICAL) == 0 ||
            (target.getStatus() & UnitStatusFlags.COMPLETED) == 0 ||
            // An SCV with a computer record stops repairing a mobile unit that is not following it.
            worker.getComputerRecord() != null && (target.getStatus() & UnitStatusFlags.CAN_MOVE) != 0 && target.getOrderTargetUnit() != worker) {
            this.game.getIscriptExecutor().runSpriteAnimation(sprite, worker, IscriptExecutor.ANIMATION_WALKING_TO_IDLE, 1, instructionLimit);
            this.game.getOrders().finishOrder(worker, instructionLimit);

            if (target != null && (target.getStatus() & UnitStatusFlags.LOADED) == 0) {
                target.setParentUnit(null);
            }

            return;
        }

        if ((units.getGroupFlags(target.getUnitId()) & UnitGroupFlags.TERRAN) == 0) {
            this.game.getIscriptExecutor().runSpriteAnimation(sprite, worker, IscriptExecutor.ANIMATION_WALKING_TO_IDLE, 1, instructionLimit);
            this.game.getOrders().finishOrder(worker, instructionLimit);

            return;
        }

        Sprite targetSprite = target.requireSprite();
        int phase = worker.getPrimaryOrderState();

        if (phase == REPAIR_STATE_START || phase == REPAIR_STATE_DECIDING) {
            if (phase == REPAIR_STATE_START) {
                this.checkRepairCost(worker, target, units, false);
                worker.setPrimaryOrderState(REPAIR_STATE_DECIDING);
                worker.setRepairTimer(0);
            }

            if ((target.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
                if (!this.game.getUnitTargetMovement().moveToTargetReset(worker, target)) {
                    return;
                }

                worker.setOrderTargetX(targetSprite.getX());
                worker.setOrderTargetY(targetSprite.getY());
                worker.setPrimaryOrderState(REPAIR_STATE_FOLLOWING);
            } else {
                this.game.getOrders().issue(worker, ScrOrderIds.CONSTRUCTING_BUILDING, target, 0, 0, ScrUnitIds.NONE, false, instructionLimit);
            }
        } else if (phase == REPAIR_STATE_FOLLOWING) {
            if (!this.game.getEngineGame().getMovement().isAtDestination(worker)) {
                this.game.getEngineGame().getCombat().pursueTarget(worker, target, instructionLimit);
            } else if (this.game.getUnitTargetMovement().isTargetInRange(worker, target, REPAIR_RANGE)) {
                worker.setDirectionTargetX(targetSprite.getX());
                worker.setDirectionTargetY(targetSprite.getY());
                worker.setOrderTargetX(targetSprite.getX());
                worker.setOrderTargetY(targetSprite.getY());
                worker.setPrimaryOrderState(REPAIR_STATE_FACING);
                this.repair(worker, target, instructionLimit);
            } else {
                worker.setPrimaryOrderState(REPAIR_STATE_DECIDING);
            }
        } else if (phase == REPAIR_STATE_FACING) {
            if (this.game.getUnitTargetMovement().isTargetInRange(worker, target, REPAIR_RANGE)) {
                worker.setDirectionTargetX(targetSprite.getX());
                worker.setDirectionTargetY(targetSprite.getY());
                worker.setOrderTargetX(targetSprite.getX());
                worker.setOrderTargetY(targetSprite.getY());

                if (this.isAtDirectionTargetOrWithinAttackAngle(worker)) {
                    this.game.getIscriptExecutor().runSpriteAnimation(sprite, worker, IscriptExecutor.ANIMATION_ALMOST_BUILT, 1, instructionLimit);
                    worker.setPrimaryOrderState(REPAIR_STATE_REPAIRING);
                }

                this.repair(worker, target, instructionLimit);
            } else {
                worker.setPrimaryOrderState(REPAIR_STATE_DECIDING);
            }
        } else if (phase == REPAIR_STATE_REPAIRING) {
            worker.setDirectionTargetX(targetSprite.getX());
            worker.setDirectionTargetY(targetSprite.getY());
            worker.setOrderTargetX(targetSprite.getX());
            worker.setOrderTargetY(targetSprite.getY());

            if (!this.game.getUnitTargetMovement().isTargetInRange(worker, target, REPAIR_RANGE) ||
                target.getPrimaryOrder() == ScrOrderIds.LIFTING_OFF) {
                this.game.getIscriptExecutor().runSpriteAnimation(sprite,
                                                                  worker,
                                                                  IscriptExecutor.ANIMATION_GROUND_ATTACK_TO_IDLE,
                                                                  1,
                                                                  instructionLimit);
                worker.setPrimaryOrderState(REPAIR_STATE_DECIDING);
            }

            this.repair(worker, target, instructionLimit);
        } else {
            this.repair(worker, target, instructionLimit);
        }
    }

    private void repair(@NotNull Unit worker, @NotNull Unit target, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int targetType = target.getUnitId();

        int timer = worker.getRepairTimer();

        worker.setRepairTimer(timer - 1);

        if (timer == 0 && !this.checkRepairCost(worker, target, units, true)) {
            this.game.getIscriptExecutor().runSpriteAnimation(worker.requireSprite(),
                                                              worker,
                                                              IscriptExecutor.ANIMATION_WALKING_TO_IDLE,
                                                              1,
                                                              instructionLimit);
            this.game.getOrders().finishOrder(worker, instructionLimit);

            return;
        }

        int rate = this.game.getUnitBuildTime().getHitPointIncrement(target);

        this.game.getUnitHealth().setHitPoints(target, target.getHitPoints() + rate, instructionLimit);

        if (target.getHitPoints() >= units.getHitPoints(targetType)) {
            this.game.getIscriptExecutor().runSpriteAnimation(worker.requireSprite(),
                                                              worker,
                                                              IscriptExecutor.ANIMATION_WALKING_TO_IDLE,
                                                              1,
                                                              instructionLimit);
            this.game.getOrders().finishOrder(worker, instructionLimit);

            if ((target.getStatus() & UnitStatusFlags.LOADED) == 0) {
                target.setParentUnit(null);
            }
        }
    }

    /**
     * Calculates and checks a repair charge; startup checks leave the timer and resources unchanged.
     */
    private boolean checkRepairCost(@NotNull Unit worker, @NotNull Unit target, @NotNull ScrUnitTypeView units, boolean charge) {
        int mineralCost = units.getMineralCost(target.getUnitId()) & WORD_MASK;
        int gasCost = units.getGasCost(target.getUnitId()) & WORD_MASK;
        int lowestCost = mineralCost != 0 && gasCost != 0 ? Math.min(mineralCost, gasCost) : Math.max(mineralCost, gasCost);
        int repairMinerals;
        int repairGas;
        int repairTime;

        if (lowestCost == 0) {
            repairMinerals = 0;
            repairGas      = 0;
            repairTime     = FREE_REPAIR_TIME;
        } else {
            int rate = this.game.getUnitBuildTime().getHitPointIncrement(target);
            int denominator = rate * lowestCost;
            int maximumHitPoints = units.getHitPoints(target.getUnitId());

            repairTime = maximumHitPoints * REPAIR_HIT_POINT_FACTOR / denominator;

            if ((repairTime & WORD_MASK) != 0) {
                if (lowestCost == mineralCost) {
                    repairMinerals = 1;
                    repairGas      = gasCost / mineralCost;
                } else {
                    repairMinerals = mineralCost / gasCost;
                    repairGas      = 1;
                }
            } else {
                repairTime     = 1;
                repairMinerals = 0;

                if (mineralCost != 0) {
                    int numerator = rate * mineralCost;

                    repairMinerals = Math.max(1, numerator / (maximumHitPoints * REPAIR_HIT_POINT_FACTOR));
                }

                repairGas = 0;

                if (gasCost != 0) {
                    int numerator = rate * gasCost;

                    repairGas = Math.max(1, numerator / (maximumHitPoints * REPAIR_HIT_POINT_FACTOR));
                }
            }
        }

        Player player = this.game.getPlayerTable().requirePlayer(worker.getOwnerIndex());

        if (player.getMinerals() < repairMinerals) {
            this.game.getInterfaceLink().getCommandErrorEvents().raiseResources(worker.getOwnerIndex(), false);

            return false;
        }

        if (player.getGas() < repairGas) {
            this.game.getInterfaceLink().getCommandErrorEvents().raiseResources(worker.getOwnerIndex(), true);

            return false;
        }

        if (charge) {
            worker.setRepairTimer(repairTime);
            player.setMinerals(player.getMinerals() - repairMinerals);
            player.setGas(player.getGas() - repairGas);
        }

        return true;
    }

    /**
     * Clamps a movement target using a box whose lower-right corner lies {@code 1} pixel beyond the unit dimensions. The box stays inside the map,
     * excluding its bottom {@code 32} pixels.
     *
     * @param unitType  the unit type
     * @param positionX the requested horizontal coordinate
     * @param positionY the requested vertical coordinate
     * @return the horizontal coordinate in the low half and the vertical coordinate in the high half
     * @throws NullPointerException if the world was built without game data
     */
    private int restrictMoveTargetToValidBounds(int unitType, int positionX, int positionY) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int left = positionX - units.getDimensionLeft(unitType);
        int right = positionX + units.getDimensionRight(unitType) + 1;
        int upper = positionY - units.getDimensionUp(unitType);
        int lower = positionY + units.getDimensionDown(unitType) + 1;
        int width = this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE;
        int height = this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE - UnitPositioning.MAP_BOTTOM_MARGIN;

        if (left < 0) {
            positionX -= left;
        } else if (right >= width) {
            positionX -= right - width;
        }

        if (upper < 0) {
            positionY -= upper;
        } else if (lower >= height) {
            positionY -= lower - height;
        }

        return positionX & WORD_MASK | positionY << SECOND_WORD_SHIFT;
    }

    /**
     * Tests terrain contours regardless of the unit's ground flag, sharing its cached collision-free rectangle with ordinary movement.
     *
     * @param unit      the moving unit
     * @param positionX the destination horizontal coordinate
     * @param positionY the destination vertical coordinate
     * @return {@code true} if the straight move hits a terrain contour, {@code false} otherwise
     * @throws IllegalStateException if the terrain contours are missing or the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    private boolean isMoveBlockedByTerrainContours(@NotNull Unit unit, int positionX, int positionY) {
        positionX = (short) positionX;
        positionY = (short) positionY;

        if (positionX >= (short) unit.getTerrainCollisionLeft() && positionX <= (short) unit.getTerrainCollisionRight() &&
            positionY >= (short) unit.getTerrainCollisionTop() && positionY <= (short) unit.getTerrainCollisionBottom()) {
            return false;
        }

        Sprite sprite = unit.requireSprite();
        int currentX = (short) sprite.getX();
        int currentY = (short) sprite.getY();

        unit.setTerrainCollisionTop(currentY - COLLISION_RECTANGLE_EXTENT);
        unit.setTerrainCollisionLeft(currentX - COLLISION_RECTANGLE_EXTENT);
        unit.setTerrainCollisionBottom(currentY + COLLISION_RECTANGLE_EXTENT);
        unit.setTerrainCollisionRight(currentX + COLLISION_RECTANGLE_EXTENT);

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = unit.getUnitId();
        long dimensions = (units.getDimensionUp(type) & LONG_WORD_MASK) |
                          (-units.getDimensionRight(type) & LONG_WORD_MASK) << SECOND_WORD_SHIFT |
                          (-units.getDimensionDown(type) & LONG_WORD_MASK) << THIRD_WORD_SHIFT |
                          (units.getDimensionLeft(type) & LONG_WORD_MASK) << FOURTH_WORD_SHIFT;
        TerrainContours terrain = this.game.getPathSystem().getTerrainContours();

        if (terrain == null) {
            throw Exceptions.newIllegalStateException("Construction movement requires terrain contours.");
        }

        terrain.initializeCollisionFootprint(type, units);

        long rectangle = unit.getTerrainCollisionLeft() | (long) unit.getTerrainCollisionTop() << SECOND_WORD_SHIFT |
                         (long) unit.getTerrainCollisionRight() << THIRD_WORD_SHIFT | (long) unit.getTerrainCollisionBottom() << FOURTH_WORD_SHIFT;

        rectangle = MovementCollision.refreshTerrainRectangle(terrain, dimensions, currentX, currentY, rectangle);

        unit.setTerrainCollisionLeft((int) rectangle);
        unit.setTerrainCollisionTop((int) (rectangle >>> SECOND_WORD_SHIFT));
        unit.setTerrainCollisionRight((int) (rectangle >>> THIRD_WORD_SHIFT));
        unit.setTerrainCollisionBottom((int) (rectangle >>> FOURTH_WORD_SHIFT));

        if (positionX >= (short) rectangle && positionX <= (short) (rectangle >>> THIRD_WORD_SHIFT) &&
            positionY >= (short) (rectangle >>> SECOND_WORD_SHIFT) && positionY <= (short) (rectangle >>> FOURTH_WORD_SHIFT)) {
            return false;
        }

        return !MovementCollision.isTerrainSegmentClear(terrain, dimensions, currentX, currentY, positionX, positionY);
    }
}
