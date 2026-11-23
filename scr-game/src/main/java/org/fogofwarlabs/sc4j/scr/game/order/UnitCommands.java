package org.fogofwarlabs.sc4j.scr.game.order;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrOrderTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitTransport;
import org.fogofwarlabs.sc4j.scr.game.movement.UnitGroupMovement;
import org.fogofwarlabs.sc4j.scr.game.production.PlacementResult;
import org.fogofwarlabs.sc4j.scr.game.resource.CarryingFlags;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Applies the player commands that order the selected units: building placement, Stop, Hold Position, Nothing, Return Cargo, rally points, explicit
 * orders and the context-dependent right click with its default order.
 */
public final class UnitCommands {
    /**
     * The mask of an unsigned WORD: the horizontal component of a packed group destination.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The shift of the vertical component of a packed group destination.
     */
    private static final int POSITION_VERTICAL_SHIFT = Short.SIZE;
    /**
     * The divisor of a placement width or height that gives the offset of the footprint center.
     */
    private static final int FOOTPRINT_CENTER_DIVISOR = 2;

    /**
     * The elapsed game seconds until which a flag beacon may still be placed.
     */
    private static final int FLAG_BEACON_PLACEMENT_SECONDS = 600;
    /**
     * The RightClickAction order state of a unit that has not started moving to the fog target position.
     */
    private static final int RIGHT_CLICK_STATE_START = 0;
    /**
     * The RightClickAction order state of a unit moving to the fog target position.
     */
    private static final int RIGHT_CLICK_STATE_MOVING = 1;
    /**
     * The order animation budget of a rally command.
     */
    private static final int RALLY_INSTRUCTION_LIMIT = 1;

    /**
     * Converts a tile coordinate of a building command to the footprint center of a building along one axis.
     *
     * @param tileCoordinate the tile coordinate of the footprint's top or left edge
     * @param placementSize  the placement width or height of the building in pixels
     * @return the pixel coordinate of the footprint center
     */
    private static int calculateFootprintCenter(int tileCoordinate, int placementSize) {
        return tileCoordinate * TerrainTileset.TILE_SIZE + placementSize / FOOTPRINT_CENTER_DIVISOR;
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitCommands} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitCommands(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Checks whether a unit is a transport ({@link UnitQueries#isTransport}) that can load a target unit ({@link UnitTransport#canLoad}): a
     * completed, enabled, non-hallucinated transport of the target's owner with room for it (an Overlord only with Ventral Sacs, a building only for
     * a small Terran infantry unit), and a target that is not burrowed.
     *
     * @param transport the transport
     * @param target    the unit to load
     * @return {@code true} if the transport can load the target, {@code false} otherwise
     */
    public boolean canTransport(@NotNull Unit transport, @NotNull Unit target) {
        return this.game.getUnitQueries().isTransport(transport) && this.game.getUnitTransport().canLoad(transport, target);
    }

    /**
     * Validates a building command of the single selected builder and converts its tile coordinates to a footprint center: landing, Nydus exit and
     * flag beacon orders are issued directly, the other construction orders place the building (for an add-on, at the parent site derived from the
     * add-on position). A failing site shows its placement text.
     *
     * @param owner            the command player
     * @param orderId          the construction order
     * @param unitType         the requested building type
     * @param tileX            the unsigned horizontal tile coordinate
     * @param tileY            the unsigned vertical tile coordinate
     * @param instructionLimit the order animation budget
     * @return {@code true} if the selected builder accepts the command, {@code false} otherwise
     * @throws UnsupportedOperationException if the selection storage is missing
     * @throws NullPointerException          if the world was built without game data
     */
    public boolean build(int owner, int orderId, int unitType, int tileX, int tileY, int instructionLimit) {
        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Building commands require initialized selection storage.");
        }

        Unit builder = selection.getSelectedUnit(owner, 0);

        if (builder == null || selection.getSelectedUnit(owner, 1) != null || builder.getOwnerIndex() != owner) {
            return false;
        }

        // The tile must lie inside the map before the order is checked.
        if (Integer.compareUnsigned(tileX, this.game.getMap().getWidth()) >= 0 ||
            Integer.compareUnsigned(tileY, this.game.getMap().getHeight()) >= 0) {
            return false;
        }

        if (builder.isDestroyed()) {
            return false;
        }

        if (orderId == ScrOrderIds.BUILDING_LAND) {
            if (builder.getUnitId() != unitType ||
                !this.game.getUnitOrderEligibility().canReceiveOrder(builder, ScrOrderIds.BUILDING_LAND, owner)) {
                return false;
            }

            this.issueAtCommandSite(builder, owner, ScrOrderIds.BUILDING_LAND, unitType, tileX, tileY, instructionLimit);

            return true;
        }

        if (orderId == ScrOrderIds.BUILD_NYDUS_EXIT) {
            if (builder.getUnitId() != unitType ||
                !this.game.getUnitOrderEligibility().canReceiveOrder(builder, ScrOrderIds.BUILD_NYDUS_EXIT, owner)) {
                return false;
            }

            this.issueAtCommandSite(builder, owner, ScrOrderIds.BUILD_NYDUS_EXIT, unitType, tileX, tileY, instructionLimit);

            return true;
        }

        if (orderId == ScrOrderIds.CTFCOP2) {
            // A flag beacon may be placed until the beacon time has elapsed, then it is checked as BuildingLand; the site
            // is checked and the order issued at it.
            TriggerContext triggers = this.game.getTriggerContext();
            int seconds = triggers == null ? 0 : triggers.getElapsedClockTicks();

            if (Integer.compareUnsigned(seconds, FLAG_BEACON_PLACEMENT_SECONDS) > 0 || builder.getUnitId() != unitType ||
                !this.game.getUnitOrderEligibility().canReceiveOrder(builder, ScrOrderIds.CTFCOP2, owner)) {
                return false;
            }

            this.issueAtCommandSite(builder, owner, ScrOrderIds.CTFCOP2, unitType, tileX, tileY, instructionLimit);

            return true;
        }

        if (orderId != ScrOrderIds.DRONE_START_BUILD && orderId != ScrOrderIds.PLACE_BUILDING &&
            orderId != ScrOrderIds.PLACE_PROTOSS_BUILDING && orderId != ScrOrderIds.CREATE_PROTOSS_BUILDING &&
            orderId != ScrOrderIds.PLACE_ADDON) {
            // An order outside the construction orders fails the check and only reports the command error.
            return false;
        }

        if (!this.game.getUnitProduction().canBuild(builder, unitType)) {
            return false;
        }

        // The orders.dat requirement script of the construction order names the builder types; an EUD map may rewrite it.
        if (!this.game.getUnitOrderEligibility().canReceiveOrder(builder, orderId, owner)) {
            return false;
        }

        int builderType = builder.getUnitId();
        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();

        if ((unitTypes.getFlags(unitType) & UnitTypeFlags.ADDON) != 0) {
            // Both the add-on site and the parent site derived from the add-on position must permit placement; the parent
            // site becomes the PlaceAddon target.
            int addonX = calculateFootprintCenter(tileX, unitTypes.getPlacementWidth(unitType));
            int addonY = calculateFootprintCenter(tileY, unitTypes.getPlacementHeight(unitType));

            if (this.checkCommandSite(builder, owner, unitType, addonX, addonY)) {
                int builderX = calculateFootprintCenter(tileX, unitTypes.getPlacementWidth(builderType)) -
                               unitTypes.getAddonHorizontalOffset(unitType) / TerrainTileset.TILE_SIZE * TerrainTileset.TILE_SIZE;
                int builderY = calculateFootprintCenter(tileY, unitTypes.getPlacementHeight(builderType)) -
                               unitTypes.getAddonVerticalOffset(unitType) / TerrainTileset.TILE_SIZE * TerrainTileset.TILE_SIZE;

                if (this.checkCommandSite(builder, owner, builderType, builderX, builderY)) {
                    this.game.getUnitConstruction().place(builder, orderId, unitType, builderX, builderY, instructionLimit);
                }
            }

            return true;
        }

        int positionX = calculateFootprintCenter(tileX, unitTypes.getPlacementWidth(unitType));
        int positionY = calculateFootprintCenter(tileY, unitTypes.getPlacementHeight(unitType));

        if (this.checkCommandSite(builder, owner, unitType, positionX, positionY)) {
            this.game.getUnitConstruction().place(builder, orderId, unitType, positionX, positionY, instructionLimit);
        }

        return true;
    }

    /**
     * Issues Stop through the common queued-order activation and cancellation path.
     *
     * @param owner            the command player
     * @param queue            whether to append the command
     * @param instructionLimit the order cleanup animation budget
     * @return {@code true} if any selected unit accepted the command, {@code false} otherwise
     * @throws UnsupportedOperationException if the selection storage is missing
     */
    public boolean stop(int owner, boolean queue, int instructionLimit) {
        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Stop requires initialized selection storage.");
        }

        boolean accepted = false;

        for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
            Unit unit = selection.getSelectedUnit(owner, position);

            if (unit == null) {
                break;
            }

            if (unit.getOwnerIndex() != owner || unit.isDestroyed()) {
                continue;
            }

            int status = unit.getStatus();
            int unitType = unit.getUnitId();
            int baseType = this.game.getBaseType(unitType);

            if ((status & UnitStatusFlags.COMPLETED) == 0 || UnitOrderEligibility.isDisabled(unit) ||
                (baseType != ScrUnitIds.ZERG_LURKER && (status & UnitStatusFlags.BURROWED) != 0)) {
                continue;
            }

            if (this.game.requireGameData().getUnitTypes().isGhost(unitType) && unit.getPrimaryOrder() == ScrOrderIds.NUKE_TRACK ||
                (baseType == ScrUnitIds.PROTOSS_ARCHON || baseType == ScrUnitIds.PROTOSS_DARK_ARCHON) &&
                unit.getPrimaryOrder() == ScrOrderIds.COMPLETING_ARCHON_SUMMON) {
                continue;
            }

            this.game.getOrders().issue(unit, ScrOrderIds.STOP, null, 0, 0, ScrUnitIds.NONE, queue, instructionLimit);

            accepted = true;
        }

        return accepted;
    }

    /**
     * Issues the unit type's hold-position order to every selected unit that can receive it.
     * <p>
     * Carriers, Reavers, Queens, suicide units and Medics use their specialized hold orders.
     *
     * @param owner            the command player
     * @param queue            whether to append a queueable order
     * @param instructionLimit the order activation script budget
     * @return {@code true} if any selected unit accepted the command, {@code false} otherwise
     * @throws UnsupportedOperationException if the selection storage is missing
     */
    public boolean holdPosition(int owner, boolean queue, int instructionLimit) {
        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Hold position requires initialized selection storage.");
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

            int orderId = switch (ScrUnitIds.requireValidOrAdded(this.game.getBaseType(unit))) {
                case ScrUnitIds.PROTOSS_CARRIER, ScrUnitIds.HERO_GANTRITHOR -> ScrOrderIds.CARRIER_HOLD_POSITION;
                case ScrUnitIds.PROTOSS_REAVER, ScrUnitIds.HERO_WARBRINGER -> ScrOrderIds.REAVER_HOLD_POSITION;
                case ScrUnitIds.ZERG_QUEEN, ScrUnitIds.HERO_MATRIARCH -> ScrOrderIds.QUEEN_HOLD_POSITION;
                case ScrUnitIds.ZERG_SCOURGE, ScrUnitIds.ZERG_INFESTED_TERRAN -> ScrOrderIds.SUICIDE_HOLD_POSITION;
                case ScrUnitIds.TERRAN_MEDIC -> ScrOrderIds.MEDIC_HOLD_POSITION;
                default -> ScrOrderIds.HOLD_POSITION;
            };

            if (!this.game.getUnitOrderEligibility().canReceiveOrder(unit, orderId, owner)) {
                continue;
            }

            this.game.getOrders().issue(unit, orderId, null, 0, 0, ScrUnitIds.NONE, queue, instructionLimit);

            accepted = true;
        }

        return accepted;
    }

    /**
     * Issues order {@code Nothing} to every selected unit that can receive it: for each unit of the command player's selection, the order test
     * decides and the empty-queue path issues the order; a unit that fails only raises the local command error.
     *
     * @param owner            the command player
     * @param instructionLimit the order activation script budget
     * @return {@code true} if any selected unit received the order, {@code false} otherwise
     * @throws UnsupportedOperationException if the selection storage is missing
     */
    public boolean orderNothing(int owner, int instructionLimit) {
        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Order Nothing requires initialized selection storage.");
        }

        boolean accepted = false;

        for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
            Unit unit = selection.getSelectedUnit(owner, position);

            if (unit == null) {
                break;
            }

            if (!this.game.getUnitOrderEligibility().canReceiveOrder(unit, ScrOrderIds.NOTHING, owner)) {
                continue;
            }

            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.NOTHING, instructionLimit);

            accepted = true;
        }

        return accepted;
    }

    /**
     * Issues ReturnMinerals or ReturnGas to selected workers carrying resources.
     * <p>
     * Minerals take precedence over gas.
     *
     * @param owner            the command player
     * @param queue            whether to append a queueable order
     * @param instructionLimit the order activation script budget
     * @return {@code true} if any selected worker accepted the command, {@code false} otherwise
     * @throws UnsupportedOperationException if the selection storage is missing
     */
    public boolean returnCargo(int owner, boolean queue, int instructionLimit) {
        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Return cargo requires initialized selection storage.");
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

            int orderId;

            if ((unit.getCarryingFlags() & CarryingFlags.MINERALS) != 0) {
                orderId = ScrOrderIds.RETURN_MINERALS;
            } else if ((unit.getCarryingFlags() & CarryingFlags.GAS) != 0) {
                orderId = ScrOrderIds.RETURN_GAS;
            } else {
                continue;
            }

            if (!this.game.getUnitOrderEligibility().canReceiveOrder(unit, orderId, owner)) {
                continue;
            }

            this.game.getOrders().issue(unit, orderId, null, 0, 0, ScrUnitIds.NONE, queue, instructionLimit);

            accepted = true;
        }

        return accepted;
    }

    /**
     * Updates building rally targets using the explicit order command.
     *
     * @param owner     the command player
     * @param orderId   the explicit rally order, {@link ScrOrderIds#RALLY_POINT_UNIT} or {@link ScrOrderIds#RALLY_POINT_TILE}
     * @param positionX the unsigned horizontal coordinate
     * @param positionY the unsigned vertical coordinate
     * @param target    the resolved target, or {@code null}
     * @return {@code true} if a factory's rally target changed, {@code false} otherwise
     * @throws IllegalArgumentException if the order is not a rally order
     */
    public boolean rally(int owner, int orderId, int positionX, int positionY, @Nullable Unit target) {
        if (orderId != ScrOrderIds.RALLY_POINT_UNIT && orderId != ScrOrderIds.RALLY_POINT_TILE) {
            throw Exceptions.newIllegalArgumentException("Rally requires order 39 or 40.");
        }

        return this.order(owner, orderId, positionX, positionY, target, ScrUnitIds.NONE, false, RALLY_INSTRUCTION_LIMIT);
    }

    /**
     * Issues an explicit order to every selected unit that can receive it.
     * <p>
     * Excluded orders are rejected, eligibility and self-target checks precede the medic heal-move substitution, and {@code AttackDefault} resolves
     * to the unit and subunit attack-unit or attack-move order. Point orders without a target type use the shared group destination; every issued
     * order requests the command position.
     *
     * @param owner            the command player
     * @param orderId          the requested order
     * @param positionX        the unsigned horizontal coordinate
     * @param positionY        the unsigned vertical coordinate
     * @param target           the resolved target, or {@code null}
     * @param targetUnitType   the requested target unit type, or {@link ScrUnitIds#NONE}
     * @param queue            whether to append a queueable order
     * @param instructionLimit the order activation script budget
     * @return {@code true} if any selected unit accepted the order or changed its rally target, {@code false} otherwise
     * @throws UnsupportedOperationException if the selection storage is missing
     * @throws IllegalStateException         if a unit target of a rally point has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    public boolean order(int owner,
                         int orderId,
                         int positionX, int positionY,
                         @Nullable Unit target,
                         int targetUnitType,
                         boolean queue,
                         int instructionLimit) {
        if (this.isOutsideMap(positionX, positionY)) {
            return false;
        }

        switch (ScrOrderIds.orNone(orderId)) {
            case ScrOrderIds.DIE, ScrOrderIds.INFESTED_COMMAND_CENTER, ScrOrderIds.SPIDER_MINE, ScrOrderIds.DRONE_START_BUILD,
                 ScrOrderIds.INFESTING_COMMAND_CENTER, ScrOrderIds.PLACE_BUILDING, ScrOrderIds.PLACE_PROTOSS_BUILDING,
                 ScrOrderIds.CREATE_PROTOSS_BUILDING, ScrOrderIds.CONSTRUCTING_BUILDING, ScrOrderIds.PLACE_ADDON, ScrOrderIds.BUILD_NYDUS_EXIT,
                 ScrOrderIds.BUILDING_LAND, ScrOrderIds.BUILDING_LIFTOFF, ScrOrderIds.DRONE_LIFT_OFF, ScrOrderIds.HARVEST2, ScrOrderIds.MOVE_TO_GAS,
                 ScrOrderIds.WAIT_FOR_GAS, ScrOrderIds.HARVEST_GAS, ScrOrderIds.MOVE_TO_MINERALS, ScrOrderIds.WAIT_FOR_MINERALS,
                 ScrOrderIds.MINING_MINERALS, ScrOrderIds.GATHERING_INTERRUPTED, ScrOrderIds.GATHER_WAIT_INTERRUPTED, ScrOrderIds.CTFCOP2 -> {
                return false;
            }
            default -> {
            }
        }

        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Explicit orders require initialized selection storage.");
        }

        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        ScrOrderTypeView orderTypes = this.game.requireGameData().getOrderTypes();
        boolean canBeObstructed = orderTypes.getCanBeObstructed(orderId) != 0;
        boolean accepted = false;
        UnitGroupMovement groupMovement = null;

        for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
            Unit unit = selection.getSelectedUnit(owner, position);

            if (unit == null) {
                break;
            }

            if (unit.isDestroyed()) {
                continue;
            }

            int unitOrder = orderId;
            int technology = orderTypes.getTechnology(unitOrder);

            if (technology == ScrTechIds.NONE) {
                if (!this.game.getUnitOrderEligibility().canReceiveOrder(unit, unitOrder, owner)) {
                    continue;
                }
            } else if (unit.getOwnerIndex() != owner || !this.game.getUnitTechnology().canUseTechnology(unit, technology)) {
                continue;
            }

            if (unit == target && !this.game.getUnitOrderEligibility().canTargetSelf(unit, unitOrder)) {
                continue;
            }

            if (unit.getPrimaryOrder() == ScrOrderIds.NUKE_LAUNCH) {
                continue;
            }

            if (this.getDefaultAction(unit) == ScrUnitTypeView.RIGHT_CLICK_NONE && unitOrder != ScrOrderIds.RALLY_POINT_UNIT &&
                unitOrder != ScrOrderIds.RALLY_POINT_TILE && unitOrder != ScrOrderIds.RECHARGE_SHIELDS_BATTERY &&
                unitOrder != ScrOrderIds.PICKUP_BUNKER) {
                continue;
            }

            int unitType = unit.getUnitId();

            if (this.game.getBaseType(unitType) == ScrUnitIds.TERRAN_MEDIC &&
                (unitOrder == ScrOrderIds.ATTACK_MOVE || unitOrder == ScrOrderIds.ATTACK_DEFAULT)) {
                unitOrder = ScrOrderIds.HEAL_MOVE;
            }

            Unit subunit = unit.getSubunit();
            int subunitOrder = ScrOrderIds.NOTHING;

            if (unitOrder == ScrOrderIds.ATTACK_DEFAULT) {
                boolean attackUnit = target != null;

                unitOrder = attackUnit ? unitTypes.getAttackUnitOrder(unitType) : unitTypes.getAttackMoveOrder(unitType);

                if (unitOrder == ScrOrderIds.NOTHING) {
                    continue;
                }

                if (subunit != null) {
                    subunitOrder = attackUnit ? unitTypes.getAttackUnitOrder(subunit.getUnitId()) : unitTypes.getAttackMoveOrder(subunit.getUnitId());
                }
            }

            if (unitOrder == ScrOrderIds.RALLY_POINT_UNIT) {
                if (unitTypes.isRallyProducer(unitType)) {
                    accepted = true;

                    if (target == null) {
                        target = unit;
                    }

                    Sprite sprite = target.getSpriteOrNull();

                    if (sprite == null) {
                        throw Exceptions.newIllegalStateException("Rally target requires a retained sprite.");
                    }

                    unit.setRallyTargetUnit(target);
                    unit.setRallyPositionX(sprite.getX());
                    unit.setRallyPositionY(sprite.getY());
                }
            } else if (unitOrder == ScrOrderIds.RALLY_POINT_TILE) {
                if (unitTypes.isRallyProducer(unitType)) {
                    accepted = true;

                    unit.setRallyTargetUnit(null);
                    unit.setRallyPositionX(positionX);
                    unit.setRallyPositionY(positionY);
                }
            } else {
                accepted = true;

                int orderX = positionX;
                int orderY = positionY;

                if (target == null && targetUnitType == ScrUnitIds.NONE) {
                    if (groupMovement == null) {
                        groupMovement = new UnitGroupMovement(this.game, owner, positionX, positionY, canBeObstructed);
                    }

                    int destination = groupMovement.calculatePosition(this.game, unit, positionX, positionY);

                    orderX = destination & WORD_MASK;
                    orderY = destination >>> POSITION_VERTICAL_SHIFT;
                }

                int orderUnitType = target != null ? ScrUnitIds.NONE : targetUnitType;

                this.game.getOrders().issue(unit, unitOrder, target, orderX, orderY, positionX, positionY, orderUnitType, queue, instructionLimit);

                if (subunitOrder != ScrOrderIds.NOTHING) {
                    this.game.getOrders().issue(subunit,
                                                subunitOrder,
                                                target,
                                                orderX, orderY,
                                                positionX, positionY,
                                                orderUnitType,
                                                queue,
                                                instructionLimit);
                }
            }

            unit.setUserActionFlags(unit.getUserActionFlags() | Unit.USER_ACTION_COMMANDED);
        }

        return accepted;
    }

    /**
     * Issues movement, gathering and attack orders using the selected units and actual target identity.
     * <p>
     * Fog target lookup and unrepresented default actions remain explicit boundaries. Rally points and subunit attack orders are applied before the
     * owner is tested through the order check, so a selected enemy turret's subunit link receives the attack order. A position order goes to the
     * group destination and requests the command position.
     *
     * @param owner            the command player
     * @param positionX        the unsigned input horizontal coordinate
     * @param positionY        the unsigned input vertical coordinate
     * @param target           the resolved target, or {@code null}
     * @param targetUnitType   the fog target type, or {@link ScrUnitIds#NONE}
     * @param queue            whether to append rather than replace an eligible order
     * @param instructionLimit the order activation script budget
     * @return {@code true} if any selected unit accepts the command, {@code false} otherwise
     * @throws UnsupportedOperationException if the selection storage is missing
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the target has no sprite
     */
    public boolean rightClick(int owner,
                              int positionX, int positionY,
                              @Nullable Unit target,
                              int targetUnitType,
                              boolean queue,
                              int instructionLimit) {
        if (this.isOutsideMap(positionX, positionY)) {
            return false;
        }

        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Unit commands require initialized selection storage.");
        }

        if (target != null) {
            targetUnitType = ScrUnitIds.NONE;
        } else if (targetUnitType != ScrUnitIds.NONE && this.game.getUnitTargeting().isPositionVisible(owner, positionX, positionY)) {
            target         = this.game.getUnitTargeting().findUnitOfTypeAt(targetUnitType, positionX, positionY);
            targetUnitType = ScrUnitIds.NONE;
        }

        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        boolean accepted = false;
        UnitGroupMovement groupMovement = null;

        for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
            Unit unit = selection.getSelectedUnit(owner, position);

            if (unit == null) {
                break;
            }

            if (unit.isDestroyed()) {
                continue;
            }

            int unitType = unit.getUnitId();
            int action = this.getDefaultAction(unit);

            if (action == ScrUnitTypeView.RIGHT_CLICK_MOVE_NO_ATTACK && UnitQueries.isGroundedBuilding(unit)) {
                if (unitTypes.isRallyProducer(unitType)) {
                    unit.setRallyTargetUnit(target);
                    unit.setRallyPositionX(target == null ? positionX : target.requireSprite().getX());
                    unit.setRallyPositionY(target == null ? positionY : target.requireSprite().getY());

                    accepted = true;
                }

                continue;
            }

            if (action == ScrUnitTypeView.RIGHT_CLICK_NONE || action == ScrUnitTypeView.RIGHT_CLICK_NOTHING) {
                continue;
            }

            int orderId;

            if (target == null) {
                orderId = this.getDefaultPointOrder(unit, action, targetUnitType);
            } else {
                if (target == unit) {
                    continue;
                }

                orderId = this.getDefaultTargetOrder(unit, target, action);
            }

            if (orderId < 0) {
                continue;
            }

            if (orderId == ScrOrderIds.ATTACK_DEFAULT) {
                Unit subunit = unit.getSubunit();

                if (subunit != null) {
                    this.game.getOrders().issue(subunit,
                                                unitTypes.getAttackUnitOrder(subunit.getUnitId()),
                                                target,
                                                positionX, positionY,
                                                ScrUnitIds.NONE,
                                                queue,
                                                instructionLimit);
                }

                orderId = unitTypes.getAttackUnitOrder(unitType);
            }

            if (!this.game.getUnitOrderEligibility().canReceiveOrder(unit, orderId, owner)) {
                continue;
            }

            if (target == null && targetUnitType != ScrUnitIds.NONE) {
                this.game.getOrders().issue(unit, orderId, null, positionX, positionY, targetUnitType, queue, instructionLimit);
            } else if (target == null) {
                if (groupMovement == null) {
                    groupMovement = new UnitGroupMovement(this.game, owner, positionX, positionY, true);
                }

                int destination = groupMovement.calculatePosition(this.game, unit, positionX, positionY);

                this.game.getOrders().issue(unit,
                                            orderId,
                                            null,
                                            destination & WORD_MASK, destination >>> POSITION_VERTICAL_SHIFT,
                                            positionX, positionY,
                                            ScrUnitIds.NONE,
                                            queue,
                                            instructionLimit);
            } else {
                this.game.getOrders().issue(unit, orderId, target, positionX, positionY, ScrUnitIds.NONE, queue, instructionLimit);
            }

            unit.setUserActionFlags(unit.getUserActionFlags() | Unit.USER_ACTION_COMMANDED);

            accepted = true;
        }

        return accepted;
    }

    /**
     * Moves to the fog target position, then resolves the requested unit type there once the position is visible and queues the default order for it.
     *
     * @param unit             the unit executing the order
     * @param instructionLimit the order activation script budget
     * @throws NullPointerException  if the world was built without game data
     * @throws IllegalStateException if the target has no sprite
     */
    public void updateRightClickAction(@NotNull Unit unit, int instructionLimit) {
        int positionX = (short) unit.getOrderTargetX();
        int positionY = (short) unit.getOrderTargetY();

        if (unit.getPrimaryOrderState() == RIGHT_CLICK_STATE_START) {
            this.game.getUnitPositioning().setMovementDestination(unit, positionX, positionY);
            unit.setDirectionTargetX(positionX);
            unit.setDirectionTargetY(positionY);
            unit.setPrimaryOrderState(RIGHT_CLICK_STATE_MOVING);

            return;
        }

        if (unit.getPrimaryOrderState() != RIGHT_CLICK_STATE_MOVING ||
            !this.game.getUnitTargeting().isPositionVisible(unit.getOwnerIndex(), positionX, positionY)) {
            return;
        }

        Unit target = this.game.getUnitTargeting().findUnitOfTypeAt(unit.getOrderTargetUnitType(), positionX, positionY);
        int action = this.getDefaultAction(unit);
        int orderId;

        if (action == ScrUnitTypeView.RIGHT_CLICK_MOVE_NO_ATTACK && UnitQueries.isGroundedBuilding(unit)) {
            orderId = this.game.requireGameData().getUnitTypes().isRallyProducer(unit.getUnitId())
                      ? target != null ? ScrOrderIds.RALLY_POINT_UNIT : ScrOrderIds.RALLY_POINT_TILE
                      : -1;
        } else if (action == ScrUnitTypeView.RIGHT_CLICK_NONE || action == ScrUnitTypeView.RIGHT_CLICK_NOTHING) {
            orderId = -1;
        } else if (target == null) {
            orderId = this.getDefaultPointOrder(unit, action, ScrUnitIds.NONE);
        } else {
            orderId = this.getDefaultTargetOrder(unit, target, action);
        }

        if (orderId < 0) {
            this.game.getUnitPointMovement().stopAndFaceDestination(unit);
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        if (orderId == ScrOrderIds.ATTACK_DEFAULT) {
            orderId = this.game.requireGameData().getUnitTypes().getAttackUnitOrder(unit.getUnitId());
        }

        if (!this.game.getUnitOrderEligibility().canReceiveOrder(unit, orderId, unit.getOwnerIndex())) {
            return;
        }

        if (orderId == ScrOrderIds.RALLY_POINT_UNIT || orderId == ScrOrderIds.RALLY_POINT_TILE) {
            if (this.game.requireGameData().getUnitTypes().isRallyProducer(unit.getUnitId())) {
                if (orderId == ScrOrderIds.RALLY_POINT_UNIT && target == null) {
                    target = unit;
                }

                unit.setRallyTargetUnit(orderId == ScrOrderIds.RALLY_POINT_UNIT ? target : null);
                unit.setRallyPositionX(orderId == ScrOrderIds.RALLY_POINT_UNIT ? target.requireSprite().getX() : positionX);
                unit.setRallyPositionY(orderId == ScrOrderIds.RALLY_POINT_UNIT ? target.requireSprite().getY() : positionY);
            }

            return;
        }

        if (target != null) {
            this.game.getOrders().queueFront(unit, orderId, target, target.requireSprite().getX(), target.requireSprite().getY(), ScrUnitIds.NONE);
        } else {
            this.game.getOrders().queueFront(unit, orderId, null, positionX, positionY, ScrUnitIds.NONE);
        }

        this.game.getOrders().finishOrder(unit, instructionLimit);
    }


    /**
     * Returns the right-click class of a unit ({@link UnitQueries#getRightClickClass}) as a command applies it: a hallucination that would harvest
     * moves and attacks instead.
     *
     * @param unit the selected unit
     * @return one of the {@code ScrUnitTypeView.RIGHT_CLICK_*} values
     */
    int getDefaultAction(@NotNull Unit unit) {
        int action = this.game.getUnitQueries().getRightClickClass(unit);

        if ((unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0 &&
            (action == ScrUnitTypeView.RIGHT_CLICK_HARVEST || action == ScrUnitTypeView.RIGHT_CLICK_HARVEST_AND_REPAIR)) {
            action = ScrUnitTypeView.RIGHT_CLICK_MOVE_AND_ATTACK;
        }

        return action;
    }

    /**
     * Returns the default order of a right click on a position.
     *
     * @param unit           the selected unit
     * @param action         the right-click class of the unit ({@link #getDefaultAction})
     * @param targetUnitType the fog target type, or {@link ScrUnitIds#NONE}
     * @return the default order, or {@code -1} for none
     * @throws NullPointerException if the world was built without game data
     */
    private int getDefaultPointOrder(@NotNull Unit unit, int action, int targetUnitType) {
        if (action == ScrUnitTypeView.RIGHT_CLICK_ATTACK_NO_MOVE) {
            return this.game.requireGameData().getUnitTypes().getReturnToIdleOrder(unit.getUnitId());
        }

        if (action == ScrUnitTypeView.RIGHT_CLICK_MOVE_NO_ATTACK) {
            int baseType = this.game.getBaseType(unit);

            return (baseType == ScrUnitIds.ZERG_QUEEN || baseType == ScrUnitIds.HERO_MATRIARCH) &&
                   targetUnitType != ScrUnitIds.NONE
                   ? ScrOrderIds.RIGHT_CLICK_ACTION
                   : ScrOrderIds.MOVE;
        }

        if (UnitQueries.isGroundedBuilding(unit)) {
            return -1;
        }

        return targetUnitType != ScrUnitIds.NONE ? ScrOrderIds.RIGHT_CLICK_ACTION : ScrOrderIds.MOVE;
    }

    /**
     * Returns the default order of a right click on a target unit; harvest actions that find no harvest order fall back to the move-and-attack
     * action.
     *
     * @param unit   the selected unit
     * @param target the target unit
     * @param action the right-click class of the unit ({@link #getDefaultAction})
     * @return the default order, or {@code -1} for none
     * @throws UnsupportedOperationException if the action or a building target command is unrepresented
     * @throws NullPointerException          if the world was built without game data
     */
    private int getDefaultTargetOrder(@NotNull Unit unit, @NotNull Unit target, int action) {
        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        int targetType = target.getUnitId();

        if (action == ScrUnitTypeView.RIGHT_CLICK_ATTACK_NO_MOVE) {
            if (unitTypes.isBeacon(targetType) || (unitTypes.getFlags(targetType) & UnitTypeFlags.POWERUP) != 0 ||
                !this.game.getUnitTargeting().isEnemy(unit, target)) {
                return unitTypes.getReturnToIdleOrder(unit.getUnitId());
            }

            return ScrOrderIds.ATTACK_DEFAULT;
        }

        if (action == ScrUnitTypeView.RIGHT_CLICK_HARVEST || action == ScrUnitTypeView.RIGHT_CLICK_HARVEST_AND_REPAIR) {
            if ((unitTypes.getFlags(targetType) & UnitTypeFlags.POWERUP) != 0) {
                return ScrOrderIds.MOVE;
            }

            if (unitTypes.isMineralField(targetType)) {
                return (unit.getCarryingFlags() & ~CarryingFlags.RESOURCES) == 0 ? ScrOrderIds.MOVE_TO_MINERALS : ScrOrderIds.MOVE;
            }

            if (targetType == ScrUnitIds.RESOURCE_VESPENE_GEYSER ||
                (unitTypes.isGasStructure(targetType) && (target.getStatus() & UnitStatusFlags.COMPLETED) != 0 &&
                 target.getOwnerIndex() == unit.getOwnerIndex())) {
                return (unit.getCarryingFlags() & ~CarryingFlags.RESOURCES) == 0 ? ScrOrderIds.MOVE_TO_GAS : ScrOrderIds.MOVE;
            }

            if (this.game.getUnitResourceSearch().isActiveDepot(target) && target.getOwnerIndex() == unit.getOwnerIndex()) {
                if ((unit.getCarryingFlags() & CarryingFlags.MINERALS) != 0) {
                    return ScrOrderIds.RETURN_MINERALS;
                }

                if ((unit.getCarryingFlags() & CarryingFlags.GAS) != 0) {
                    return ScrOrderIds.RETURN_GAS;
                }
            }

            if (action == ScrUnitTypeView.RIGHT_CLICK_HARVEST_AND_REPAIR) {
                int constructionOrder = this.game.getUnitTerranConstruction().getDefaultConstructionOrder(unit, target);

                if (constructionOrder > 0) {
                    return constructionOrder;
                }

                if (constructionOrder == 0) {
                    return ScrOrderIds.ENTER_TRANSPORT;
                }
            }

            action = ScrUnitTypeView.RIGHT_CLICK_MOVE_AND_ATTACK;
        }

        if (action == ScrUnitTypeView.RIGHT_CLICK_NONE || action == ScrUnitTypeView.RIGHT_CLICK_NOTHING) {
            return -1;
        }

        if (action != ScrUnitTypeView.RIGHT_CLICK_MOVE_AND_ATTACK && action != ScrUnitTypeView.RIGHT_CLICK_MOVE_NO_ATTACK) {
            throw Exceptions.newUnsupportedOperationException("Default target action requires stationary attack eligibility: {0}", action);
        }

        if (UnitQueries.isGroundedBuilding(unit)) {
            if (action == ScrUnitTypeView.RIGHT_CLICK_MOVE_AND_ATTACK) {
                return -1;
            }

            throw Exceptions.newUnsupportedOperationException("Default building target commands require the factory rally branch.");
        }

        if (action == ScrUnitTypeView.RIGHT_CLICK_MOVE_AND_ATTACK) {
            if (unitTypes.isBeacon(targetType) || (unitTypes.getFlags(targetType) & UnitTypeFlags.POWERUP) != 0) {
                return ScrOrderIds.MOVE;
            }

            if (this.game.getUnitTargeting().isEnemy(unit, target)) {
                return ScrOrderIds.ATTACK_DEFAULT;
            }
        } else if (this.canTransport(unit, target)) {
            return ScrOrderIds.PICKUP_TRANSPORT;
        }

        if (this.canTransport(target, unit)) {
            return ScrOrderIds.ENTER_TRANSPORT;
        }

        if ((target.getStatus() & UnitStatusFlags.BURROWED) != 0) {
            return ScrOrderIds.MOVE;
        }

        // A Queen right-clicking a damaged Command Center infests it.
        if (action == ScrUnitTypeView.RIGHT_CLICK_MOVE_NO_ATTACK &&
            (this.game.getBaseType(unit) == ScrUnitIds.ZERG_QUEEN || this.game.getBaseType(unit) == ScrUnitIds.HERO_MATRIARCH) &&
            (target.getStatus() & UnitStatusFlags.INVINCIBLE) == 0 && this.game.getUnitInfestation().canBeInfested(target)) {
            return ScrOrderIds.CAST_INFESTATION;
        }

        // A Medic heal-moves to a friendly target.
        if (action == ScrUnitTypeView.RIGHT_CLICK_MOVE_NO_ATTACK && this.game.getBaseType(unit) == ScrUnitIds.TERRAN_MEDIC) {
            return ScrOrderIds.HEAL_MOVE;
        }

        return ScrOrderIds.FOLLOW;
    }

    /**
     * Checks whether a command position lies outside the map.
     *
     * @param positionX the horizontal pixel coordinate
     * @param positionY the vertical pixel coordinate
     * @return {@code true} if the position lies outside the map, {@code false} otherwise
     */
    private boolean isOutsideMap(int positionX, int positionY) {
        return positionX < 0 ||
               positionY < 0 ||
               positionX >= this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE ||
               positionY >= this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE;
    }

    /**
     * Issues a building order that places no building (landing, Nydus exit or flag beacon) at the footprint center of its tile coordinates when the
     * command site permits it.
     *
     * @param builder          the single selected builder
     * @param owner            the command player
     * @param orderId          the building order
     * @param unitType         the requested building type
     * @param tileX            the unsigned horizontal tile coordinate
     * @param tileY            the unsigned vertical tile coordinate
     * @param instructionLimit the order animation budget
     * @throws NullPointerException if the world was built without game data
     */
    private void issueAtCommandSite(@NotNull Unit builder, int owner, int orderId, int unitType, int tileX, int tileY, int instructionLimit) {
        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        int positionX = calculateFootprintCenter(tileX, unitTypes.getPlacementWidth(unitType));
        int positionY = calculateFootprintCenter(tileY, unitTypes.getPlacementHeight(unitType));

        if (this.checkCommandSite(builder, owner, unitType, positionX, positionY)) {
            this.game.getOrders().issue(builder, orderId, null, positionX, positionY, ScrUnitIds.NONE, false, instructionLimit);
        }
    }

    /**
     * Checks a building command site after the order check: a failing placement code shows its text on the command player's status line, without a
     * sound.
     *
     * @param builder   the single selected builder
     * @param owner     the command player
     * @param unitType  the building type placed at the site
     * @param positionX the horizontal pixel coordinate of the footprint center
     * @param positionY the vertical pixel coordinate of the footprint center
     * @return {@code true} if the site permits the building, {@code false} otherwise
     */
    private boolean checkCommandSite(@NotNull Unit builder, int owner, int unitType, int positionX, int positionY) {
        int code = this.game.getUnitPlacement().getPlacementCode(builder, builder, owner, unitType, positionX, positionY, false, false);

        if (code == PlacementResult.VALID) {
            return true;
        }

        this.game.getInterfaceLink().getCommandErrorEvents().raiseStatusText(owner, PlacementResult.getMessageId(code));

        return false;
    }
}
