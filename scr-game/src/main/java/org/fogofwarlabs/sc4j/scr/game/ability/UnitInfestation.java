package org.fogofwarlabs.sc4j.scr.game.ability;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.production.UnitMorphing;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitCreationContext;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.jetbrains.annotations.NotNull;

/**
 * Runs Queen infestation of a damaged Command Center.
 * <p>
 * The Queen approaches and hides inside an eligible Command Center while its delayed conversion changes the existing unit's type and owner. The Queen
 * later reappears at a free position.
 */
public final class UnitInfestation {
    /**
     * The {@code stat_txt} id of the text of a target that cannot be infested.
     */
    static final int TEXT_CANNOT_INFEST = 0x366;

    /**
     * A Command Center can be infested below this percentage of its hit points.
     */
    private static final int INFESTABLE_HIT_POINT_PERCENT = 50;

    /**
     * The {@link ScrOrderIds#CAST_INFESTATION} order state that checks the target and starts the approach.
     */
    private static final int CAST_STATE_START = 0;
    /**
     * The {@link ScrOrderIds#CAST_INFESTATION} order state that approaches the Command Center.
     */
    private static final int CAST_STATE_APPROACHING = 1;

    /**
     * The {@link ScrOrderIds#INFESTING_COMMAND_CENTER} order state that hides the Queen inside an eligible Command Center.
     */
    private static final int INFESTING_STATE_START = 0;
    /**
     * The {@link ScrOrderIds#INFESTING_COMMAND_CENTER} order state of the Queen hidden inside the Command Center.
     */
    private static final int INFESTING_STATE_INSIDE = 3;

    /**
     * The {@link ScrOrderIds#INFESTED_COMMAND_CENTER} order state that starts the conversion delay.
     */
    private static final int INFESTED_STATE_START = 0;
    /**
     * The {@link ScrOrderIds#INFESTED_COMMAND_CENTER} order state that waits for the conversion.
     */
    private static final int INFESTED_STATE_WAITING = 2;

    /**
     * The updates the Queen stays inside the Command Center.
     */
    private static final int INFESTING_DURATION = 60;
    /**
     * The updates before the Command Center converts.
     */
    private static final int INFESTED_DELAY = 3;
    /**
     * The remaining build time an infested Command Center keeps after the conversion.
     */
    private static final int INFESTED_BUILD_TIME = 0xFFFF;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitInfestation} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitInfestation(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Tests for a completed Command Center below {@link #INFESTABLE_HIT_POINT_PERCENT} percent of its hit points.
     *
     * @param unit the candidate
     * @return {@code true} if a Queen may infest the unit, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    public boolean canBeInfested(@NotNull Unit unit) {
        return unit.getUnitId() == ScrUnitIds.TERRAN_COMMAND_CENTER && (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0 &&
               this.game.getUnitQueries().getHitPointPercent(unit) < INFESTABLE_HIT_POINT_PERCENT;
    }


    /**
     * Approaches the Command Center, then starts infesting it. A target that cannot be infested raises the {@link #TEXT_CANNOT_INFEST} unit error.
     *
     * @param queen            the Queen
     * @param instructionLimit the order budget
     */
    public void updateCast(@NotNull Unit queen, int instructionLimit) {
        Unit target = queen.getOrderTargetUnit();

        if (queen.getPrimaryOrderState() == CAST_STATE_START) {
            if (target == null || !this.canBeInfested(target)) {
                if (target != null) {
                    this.game.getInterfaceLink().getCommandErrorEvents().raiseUnitError(this.game, queen, TEXT_CANNOT_INFEST, queen.getOwnerIndex());
                }

                this.game.getOrders().finishOrder(queen, instructionLimit);

                return;
            }

            if (!this.game.getUnitTargetMovement().moveToTargetReset(queen, target)) {
                return;
            }

            queen.setPrimaryOrderState(CAST_STATE_APPROACHING);
        } else if (queen.getPrimaryOrderState() == CAST_STATE_APPROACHING) {
            if (this.game.getEngineGame().getMovement().isAtDestination(queen)) {
                if (target != null && this.canBeInfested(target) && (queen.getStatus() & UnitStatusFlags.IMMOVABLE) == 0) {
                    this.game.getOrders().issue(queen, ScrOrderIds.INFESTING_COMMAND_CENTER, target, 0, 0, ScrUnitIds.NONE, false, instructionLimit);
                } else {
                    this.game.getOrders().finishOrder(queen, instructionLimit);
                }
            } else if (target != null) {
                this.game.getEngineGame().getCombat().pursueTarget(queen, target, instructionLimit);
            }
        }
    }

    /**
     * Hides the Queen inside the Command Center for {@link #INFESTING_DURATION} updates, then reappears beside it.
     *
     * @param queen            the Queen
     * @param instructionLimit the order budget
     * @throws UnsupportedOperationException if the creation context is unavailable
     * @throws IllegalStateException         if the Queen has no sprite
     */
    public void updateInfesting(@NotNull Unit queen, int instructionLimit) {
        Unit target = queen.getOrderTargetUnit();

        if (queen.getPrimaryOrderState() == INFESTING_STATE_START) {
            if (target != null && this.canBeInfested(target) && target.getPrimaryOrder() != ScrOrderIds.INFESTED_COMMAND_CENTER) {
                this.game.getUnitLifecycle().hide(queen, true);
                this.game.getOrders().issue(target, ScrOrderIds.INFESTED_COMMAND_CENTER, queen, 0, 0, ScrUnitIds.NONE, false, instructionLimit);
                queen.setRemainingBuildTime(INFESTING_DURATION);
                queen.setPrimaryOrderState(INFESTING_STATE_INSIDE);
            } else {
                this.game.getUnitPointMovement().stopAndFaceDestination(queen);
                this.game.getOrders().completeOrderToIdle(queen, instructionLimit);
            }
        } else if (queen.getPrimaryOrderState() == INFESTING_STATE_INSIDE) {
            if (queen.getRemainingBuildTime() != 0) {
                queen.setRemainingBuildTime(queen.getRemainingBuildTime() - 1);

                return;
            }

            Sprite sprite = queen.requireSprite();
            int[] position = new int[UnitPositioning.POSITION_COORDINATES];

            if (this.game.getUnitPositioning().find(queen, sprite.getX(), sprite.getY(), position)) {
                this.game.getUnitPositioning().move(queen, position[UnitPositioning.X_INDEX], position[UnitPositioning.Y_INDEX]);
                this.game.getUnitPointMovement().stopAndFaceDestination(queen);

                UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

                if (context == null) {
                    throw Exceptions.newUnsupportedOperationException("Infestation requires the unit creation context.");
                }

                this.game.getUnitVisibility().show(queen, context.isFoliageEnabled());
                this.game.getOrders().completeOrderToIdle(queen, instructionLimit);
            }
        }
    }

    /**
     * Converts the Command Center, after a short delay, into an Infested Command Center of the Queen's owner at full hit points. The Command Center
     * is credited to the Queen's owner as a kill before its type changes, and it takes its new type's button set.
     *
     * @param center           the Command Center
     * @param instructionLimit the order, ownership, script, image, hit point and build queue reset budget
     * @throws UnsupportedOperationException if accounting or the creation context is unavailable, or the order pool is not initialized
     * @throws IllegalStateException         if the Command Center has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    public void updateInfested(@NotNull Unit center, int instructionLimit) {
        Unit queen = center.getOrderTargetUnit();

        if (queen == null || !this.canBeInfested(center)) {
            this.game.getOrders().completeOrder(center, instructionLimit);

            return;
        }

        if (center.getPrimaryOrderState() == INFESTED_STATE_START) {
            center.setRemainingBuildTime(INFESTED_DELAY);
            center.setPrimaryOrderState(INFESTED_STATE_WAITING);

            return;
        }

        if (center.getRemainingBuildTime() != 0) {
            center.setRemainingBuildTime(center.getRemainingBuildTime() - 1);

            return;
        }

        center.setRemainingBuildTime(INFESTED_BUILD_TIME);

        if (center.getAddon() != null) {
            this.game.getUnitAddons().abandon(center, instructionLimit);
        }

        center.setRallyTargetUnit(center);

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int infestedType = units.getInfestedType(center.getUnitId());
        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();
        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

        if (accounting == null || context == null) {
            throw Exceptions.newUnsupportedOperationException("Infestation requires accounting and the creation context.");
        }

        this.game.getUnitLifecycle().recordDeath(center, queen.getOwnerIndex());

        boolean completed = (center.getStatus() & UnitStatusFlags.COMPLETED) != 0;

        accounting.update(center, units, -1, context.preservesNegativeTypeCounts());

        if (completed) {
            accounting.updateCompleted(center, units, -1, false, this.game.getPlayerTable().getProductionStatistics());
        }

        center.setUnitId(infestedType);
        accounting.update(center, units, 1, context.preservesNegativeTypeCounts());

        if (completed) {
            accounting.updateCompleted(center, units, 1, false, this.game.getPlayerTable().getProductionStatistics());
        }

        this.game.getUnitOwnership().changeOwner(center, queen.getOwnerIndex(), true, instructionLimit);

        ScrOrderSystem.restartSecondaryOrder(center, ScrOrderIds.NOTHING);
        this.game.getUnitLifecycle().resetBuildQueue(center, instructionLimit);

        while (center.getFirstOrder() != null) {
            this.game.requireOrderPool().remove(center, center.getFirstOrder(), this.game.requireGameData().getOrderTypes());
        }

        Sprite sprite = center.requireSprite();

        if (UnitQueries.isGroundedBuilding(center)) {
            this.game.getIscriptExecutor().runSpriteAnimation(sprite, center, IscriptExecutor.ANIMATION_WORKING_TO_IDLE, 0, instructionLimit);
        }

        int overlay = units.getConstructionImage(infestedType);

        if (overlay != 0) {
            this.game.getIscriptExecutor().createImage(sprite, sprite.getMainImage(), overlay, 0, 0, 0, center, instructionLimit);
        }

        this.game.getUnitHealth().setHitPoints(center, this.game.getUnitHealth().getMaximumHitPoints(center), instructionLimit);
        UnitMorphing.writeButtonSet(units, center, infestedType);
        this.game.getOrders().completeOrder(center, instructionLimit);
    }
}
