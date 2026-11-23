package org.fogofwarlabs.sc4j.scr.game.ability;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SoundIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.production.UnitMorphing;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.jetbrains.annotations.NotNull;

/**
 * Builds Nydus Canal exits and moves units through them.
 * <p>
 * Exit construction links both canals. Eligible units walk into a canal and reappear at a free position near its exit; destroying a canal also
 * destroys the linked end.
 */
public final class UnitNydus {
    /**
     * The {@link ScrOrderIds#ENTER_NYDUS_CANAL} order state that starts moving to the canal.
     */
    private static final int ENTER_STATE_START = 0;
    /**
     * The {@link ScrOrderIds#ENTER_NYDUS_CANAL} order state that walks into the canal.
     */
    private static final int ENTER_STATE_WALKING = 1;
    /**
     * The {@link ScrOrderIds#ENTER_NYDUS_CANAL} order state that moves the unit through the canal to a free position beside its exit.
     */
    private static final int ENTER_STATE_INSIDE = 2;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitNydus} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitNydus(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Tests same-owner, non-flying Zerg travel through completed paired canals.
     *
     * @param unit   the entering unit
     * @param target the candidate Nydus Canal
     * @return {@code true} if the unit may travel through the canal, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    public boolean canEnter(@NotNull Unit unit, @NotNull Unit target) {
        if (target.getUnitId() != ScrUnitIds.ZERG_NYDUS_CANAL || (target.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
            return false;
        }

        Unit exit = UnitQueries.getLinkedUnit(target);

        if (exit == null || (exit.getStatus() & UnitStatusFlags.COMPLETED) == 0 || unit.getOwnerIndex() != target.getOwnerIndex() ||
            (unit.getStatus() & UnitStatusFlags.FLYING) != 0) {
            return false;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        return (units.getGroupFlags(unit.getUnitId()) & UnitGroupFlags.ZERG) != 0;
    }


    /**
     * Creates the incomplete exit at the target and links both ends. A blocked exit site raises the blocked build location error; a failed creation
     * notifies the canal's owner before the order ends.
     * <p>
     * Rejected placement still requests order completion. The links are written only after creep registration returns.
     *
     * @param canal            the completed Nydus Canal
     * @param instructionLimit the creation, construction image and order budget
     * @throws UnsupportedOperationException if the creation context or creep storage is missing, or the unit accounting is not initialized
     * @throws IllegalStateException         if the exit has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    public void updateBuildExit(@NotNull Unit canal, int instructionLimit) {
        int positionX = (short) canal.getOrderTargetX();
        int positionY = (short) canal.getOrderTargetY();

        if (!this.game.getUnitPlacement().canPlaceBuilding(canal,
                                                           canal.getOwnerIndex(),
                                                           ScrUnitIds.ZERG_NYDUS_CANAL,
                                                           positionX, positionY,
                                                           true,
                                                           false)) {
            this.game.getInterfaceLink().getCommandErrorEvents().raiseBlockedBuildLocation(this.game, canal);
            this.game.getOrders().finishOrder(canal, instructionLimit);

            return;
        }

        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

        if (context == null) {
            throw Exceptions.newUnsupportedOperationException("Nydus exits require the unit creation context.");
        }

        Unit exit = this.game.getUnitStorageConstructor().create(context,
                                                                 this.game.getPlayerTable().requireUnitAccounting(),
                                                                 ScrUnitIds.ZERG_NYDUS_CANAL,
                                                                 positionX, positionY,
                                                                 canal.getOwnerIndex(),
                                                                 0,
                                                                 instructionLimit);

        if (exit != null) {
            this.game.getIscriptExecutor().initializeConstructionImage(exit, IscriptExecutor.CONSTRUCTION_IMAGE_MODE, instructionLimit);

            ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

            exit.setHitPoints(this.game.getUnitHealth().getMaximumHitPoints(exit) / UnitInitializer.CONSTRUCTION_START_DIVISOR);
            this.game.getOrders().issueUntargeted(exit, ScrOrderIds.INCOMPLETE_MORPHING, instructionLimit);
            UnitMorphing.writeButtonSet(units, exit, ButtonSetIds.EMPTY);

            CreepField creep = this.game.getCreepField();

            if (creep == null) {
                throw Exceptions.newUnsupportedOperationException("Nydus exits require creep storage.");
            }

            Sprite sprite = exit.requireSprite();

            creep.addProvider(this.game,
                              ScrUnitIds.ZERG_NYDUS_CANAL,
                              (short) sprite.getX(), (short) sprite.getY(),
                              (exit.getStatus() & UnitStatusFlags.COMPLETED) != 0);
            canal.setNydusExit(exit);
            exit.setNydusExit(canal);
        } else {
            this.game.getUnitStorageConstructor().notifyCreationFailure(canal.getOwnerIndex());
        }

        this.game.getOrders().finishOrder(canal, instructionLimit);
    }

    /**
     * Walks into the canal, then reappears beside its exit, or where it entered when no place beside the exit is free.
     * <p>
     * Rejected targets still request order completion.
     *
     * @param unit             the entering unit
     * @param instructionLimit the order budget
     * @throws UnsupportedOperationException if the creation context is missing
     * @throws IllegalStateException         if the unit, the exit or the target has no sprite
     */
    public void updateEnter(@NotNull Unit unit, int instructionLimit) {
        Unit target = unit.getOrderTargetUnit();

        if (target == null || !this.canEnter(unit, target)) {
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        Unit exit = UnitQueries.getLinkedUnit(target);

        if (exit == null) {
            exit = target;
        }

        if (unit.getPrimaryOrderState() == ENTER_STATE_START) {
            if (!this.game.getUnitTargetMovement().moveToTargetReset(unit, target)) {
                return;
            }

            unit.setPrimaryOrderState(ENTER_STATE_WALKING);
        } else if (unit.getPrimaryOrderState() == ENTER_STATE_WALKING && this.game.getEngineGame().getMovement().isAtDestination(unit)) {
            if ((unit.getStatus() & UnitStatusFlags.IMMOVABLE) != 0) {
                this.game.getOrders().finishOrder(unit, instructionLimit);
            } else {
                unit.setPrimaryOrderState(ENTER_STATE_INSIDE);
            }
        }

        if (unit.getPrimaryOrderState() != ENTER_STATE_INSIDE) {
            return;
        }

        this.game.getUnitLifecycle().hide(unit, false);

        Sprite sprite = unit.requireSprite();
        int previousX = sprite.getX();
        int previousY = sprite.getY();
        Sprite exitSprite = exit.requireSprite();

        this.game.getUnitPositioning().move(unit, exitSprite.getX(), exitSprite.getY());

        int[] position = new int[UnitPositioning.POSITION_COORDINATES];
        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

        if (context == null) {
            throw Exceptions.newUnsupportedOperationException("Nydus travel requires the unit creation context.");
        }

        if (this.game.getUnitPositioning().find(unit, exitSprite.getX(), exitSprite.getY(), position)) {
            this.game.getUnitSounds().play(SoundIds.NYDUS_CANAL_TRANSIT, target);
            this.game.getUnitPositioning().move(unit, position[UnitPositioning.X_INDEX], position[UnitPositioning.Y_INDEX]);
            this.game.getUnitVisibility().show(unit, context.isFoliageEnabled());
            this.game.getOrders().finishOrder(unit, instructionLimit);
            this.game.getUnitSounds().play(SoundIds.NYDUS_CANAL_TRANSIT, exit);
        } else {
            this.game.getUnitPositioning().move(unit, previousX, previousY);
            this.game.getUnitVisibility().show(unit, context.isFoliageEnabled());
        }
    }

    /**
     * Clears both canal links and destroys the other end when this canal dies.
     * <p>
     * Both links are cleared before requesting the linked end's death.
     *
     * @param canal            the dying Nydus Canal
     * @param instructionLimit the order budget
     */
    public void destroy(@NotNull Unit canal, int instructionLimit) {
        Unit exit = UnitQueries.getLinkedUnit(canal);

        if (canal.getUnitId() != ScrUnitIds.ZERG_NYDUS_CANAL || exit == null) {
            return;
        }

        canal.setNydusExit(null);
        exit.setNydusExit(null);
        this.game.getUnitLifecycle().kill(exit, instructionLimit);
    }
}
