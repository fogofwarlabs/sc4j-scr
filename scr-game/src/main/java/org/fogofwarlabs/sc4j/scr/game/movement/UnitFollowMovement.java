package org.fogofwarlabs.sc4j.scr.game.movement;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.order.OrderSystem;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerRelations;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;

/**
 * Applies the Follow order through the target lifecycle: the unit moves after its target, turns the order into an enter, recharge or guard order
 * where the target allows it, and finishes when the target is gone.
 */
public final class UnitFollowMovement {
    /**
     * The Follow order state that starts moving after the target or turns the order into an enter or recharge order.
     */
    private static final int FOLLOW_STATE_START = 0;
    /**
     * The Follow order state that follows the target.
     */
    private static final int FOLLOW_STATE_FOLLOWING = 1;
    /**
     * The frames between two Follow updates (the order timer reload).
     */
    private static final int FOLLOW_TIMER = 7;
    /**
     * The range in pixels within which the unit stops moving after its target.
     */
    private static final int FOLLOW_RANGE = 96;
    /**
     * The range in pixels within which a unit following a rescuable target stops moving toward it.
     */
    private static final int RESCUE_RANGE = 1;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitFollowMovement} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitFollowMovement(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Updates the Follow order of a unit: a unit with a computer record stops following once it is at full hit points; a target it can use (the
     * follow target hooks, {@link OrderSystem#FOLLOW_TARGET}: EnterNydusCanal, RechargeShieldsUnit) or an enemy it can attack turns the order
     * into the order that uses the target or Guard; otherwise the unit moves after its target until it is in range.
     * <p>
     * A pickup keeps its order and queue changes before returning.
     *
     * @param unit             the following unit
     * @param instructionLimit the script budget of order activation
     * @throws IllegalStateException if player relations or the target controller are missing
     * @throws NullPointerException  if the world was built without game data
     */
    public void update(@NotNull Unit unit, int instructionLimit) {
        Unit target = unit.getOrderTargetUnit();
        boolean stopFollowing = target == null;

        if (!stopFollowing && unit.getComputerRecord() != null) {
            stopFollowing = UnitQueries.getHitPoints(unit) == this.game.getUnitQueries().getMaximumHitPoints(unit);
        }

        if (stopFollowing) {
            // A unit with a computer record stops following (its repairer) once at full hit points.
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        if (unit.getPrimaryOrderState() == FOLLOW_STATE_START) {
            if (!this.game.getUnitTargetMovement().moveToTargetReset(unit, target)) {
                return;
            }

            // A target the unit can use (a Nydus Canal it can enter, a Shield Battery it can recharge at) turns Follow into the order that uses it.
            if (this.game.getHooks().useFollowTarget(unit, target, instructionLimit)) {
                return;
            }

            unit.setPrimaryOrderState(FOLLOW_STATE_FOLLOWING);
        }

        if (unit.getOrderTimer() != 0) {
            return;
        }

        unit.setOrderTimer(FOLLOW_TIMER);

        PlayerRelations relations = this.game.getPlayerTable().getPlayerRelations();

        if (relations == null) {
            throw Exceptions.newIllegalStateException("Follow requires player relations.");
        }

        if (this.game.getUnitTargeting().isEnemy(unit, target) && this.game.getUnitTargeting().canAttackTarget(unit, target)) {
            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.GUARD, instructionLimit);

            return;
        }

        if ((unit.getStatus() & UnitStatusFlags.FLYING) == 0 && !this.game.getUnitTargeting().isReachable(unit, target)) {
            this.game.getUnitPointMovement().stopAndFaceDestination(unit);

            return;
        }

        Player player = this.game.getPlayerTable().getPlayer(target.getOwnerIndex());

        if (player == null) {
            throw Exceptions.newIllegalStateException("Follow requires the target controller.");
        }

        if ((player.getType() == PlayerTypes.RESCUE_PASSIVE || player.getType() == PlayerTypes.RESCUE_ACTIVE) &&
            !this.game.getUnitTargetMovement().isTargetInRange(unit, target, RESCUE_RANGE)) {
            this.game.getUnitTargetMovement().moveToTargetReset(unit, target);

            return;
        }

        if (!this.game.getUnitTargetMovement().isTargetInRange(unit, target, FOLLOW_RANGE)) {
            this.game.getUnitAttack().tryFollowTarget(unit, target, instructionLimit);

            return;
        }

        if (!UnitQueries.isGroundedBuilding(target) && unit.getFirstOrder() == null) {
            this.game.getUnitPointMovement().stopAndFaceDestination(unit);

            return;
        }

        this.game.getOrders().finishOrder(unit, instructionLimit);
    }
}
