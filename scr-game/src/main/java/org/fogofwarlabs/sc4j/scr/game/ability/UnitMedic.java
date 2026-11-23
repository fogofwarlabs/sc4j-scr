package org.fogofwarlabs.sc4j.scr.game.ability;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerPlayerPhase;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerSpells;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerUnitRecord;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerUnits;
import org.fogofwarlabs.sc4j.scr.game.order.OrderPool;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the Medic healing orders.
 * <p>
 * The order dispatcher skips {@link ScrOrderIds#MEDIC_IDLE} and {@link ScrOrderIds#MEDIC_HEAL} for hidden units.
 */
public final class UnitMedic {
    /**
     * The heal attempt result when the Medic cannot use Healing or has no healable target.
     */
    private static final int HEAL_FAILED = 0;
    /**
     * The heal attempt result when the target was healed.
     */
    private static final int HEALED = 1;
    /**
     * The heal attempt result when the Medic lacks the energy for the heal.
     */
    private static final int NO_ENERGY = 2;
    /**
     * The heal attempt result when the target is out of heal range.
     */
    private static final int OUT_OF_RANGE = 3;

    /**
     * The order state in which {@link ScrOrderIds#MEDIC_HEAL} approaches its target and {@link ScrOrderIds#MEDIC_HOLD_POSITION} starts.
     */
    private static final int HEAL_STATE_APPROACHING = 0;
    /**
     * The order state in which {@link ScrOrderIds#MEDIC_HEAL} and {@link ScrOrderIds#MEDIC_HOLD_POSITION} heal.
     */
    private static final int HEAL_STATE_HEALING = 1;

    /**
     * The range within which a Medic heals its target.
     */
    private static final int HEAL_RANGE = 30;
    /**
     * The half size of the box around a Medic searched for a heal target.
     */
    private static final int HEAL_SEARCH_RADIUS = 160;
    /**
     * The most hit points healed per update.
     */
    private static final int MAXIMUM_HEAL_AMOUNT = 200;
    /**
     * The healed hit points per spent energy point, including fraction bits.
     */
    private static final int HIT_POINTS_PER_ENERGY = 2;
    /**
     * The main order timer between two heal target searches of {@link ScrOrderIds#HEAL_MOVE} and {@link ScrOrderIds#MEDIC_HOLD_POSITION}.
     */
    private static final int HEAL_SEARCH_DELAY = 15;
    /**
     * The healing state of a unit healed this frame.
     */
    private static final int HEALING_STATE_HEALED = 1;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitMedic} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitMedic(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Tests whether an allied, damaged organic ground unit can be healed.
     *
     * @param unit   the Medic
     * @param target the candidate
     * @return {@code true} if the Medic may heal the target, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    @Contract("_, null -> false")
    public boolean canHeal(@NotNull Unit unit, @Nullable Unit target) {
        if (target == null || target == unit) {
            return false;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int abilities = units.getFlags(target.getUnitId());

        if ((abilities & UnitTypeFlags.ORGANIC) == 0 || (abilities & UnitTypeFlags.BUILDING) != 0) {
            return false;
        }

        if ((target.getStatus() & UnitStatusFlags.FLYING) != 0 || (target.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
            return false;
        }

        if (target.getHitPoints() >= this.game.getUnitHealth().getMaximumHitPoints(target)) {
            return false;
        }

        if (this.game.getEngineGame().getCombat().isEnemy(unit, target)) {
            return false;
        }

        if (unit.getOwnerIndex() >= PlayerTable.PLAYABLE_PLAYER_SLOTS || target.getOwnerIndex() >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return false;
        }

        return !UnitOrderEligibility.isDisabled(target);
    }


    /**
     * Starts healing the nearest unit that needs it. A Medic with a computer record first tries its spells; without a heal target a guard Medic
     * returns to its post, other computer Medics continue with {@link ScrOrderIds#COMPUTER_AI}.
     *
     * @param unit             the Medic
     * @param instructionLimit the order budget
     * @throws UnsupportedOperationException if the computer player phase is missing
     * @throws IllegalStateException         if the unit has no sprite
     */
    public void updateIdle(@NotNull Unit unit, int instructionLimit) {
        ComputerUnitRecord record = unit.getComputerRecord();

        if (record != null) {
            ComputerPlayerPhase phase = this.game.getComputerPlayerPhase();

            if (phase == null) {
                throw Exceptions.newUnsupportedOperationException("Computer Medics require the computer-player phase.");
            }

            if (ComputerSpells.cast(phase, this.game, unit, false)) {
                return;
            }
        }

        Unit target = this.findTarget(unit);

        if (target != null) {
            this.game.getOrders().issue(unit, ScrOrderIds.MEDIC_HEAL, target, 0, 0, ScrUnitIds.NONE, false, instructionLimit);
            this.game.getEngineGame().getMovement().setTargetUnit(unit, target);

            return;
        }

        if (record == null) {
            return;
        }

        if (record.getKind() != ComputerUnitRecord.KIND_GUARD) {
            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.COMPUTER_AI, instructionLimit);

            return;
        }

        Sprite sprite = unit.requireSprite();

        if (sprite.getX() != (short) record.guardX || sprite.getY() != (short) record.guardY) {
            this.game.getOrders().issue(unit,
                                        ScrOrderIds.COMPUTER_RETURN,
                                        null,
                                        (short) record.guardX, (short) record.guardY,
                                        ScrUnitIds.NONE,
                                        false,
                                        instructionLimit);
        }
    }

    /**
     * Approaches the target and heals it while energy lasts.
     *
     * @param unit             the Medic
     * @param instructionLimit the order, script and hit point budget
     * @throws IllegalStateException if the target has no sprite
     */
    public void updateHeal(@NotNull Unit unit, int instructionLimit) {
        int result = this.tryHeal(unit, instructionLimit);

        if (result == HEAL_FAILED) {
            this.game.getEngineGame().getMovement().stop(unit);
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        Unit target = unit.getOrderTargetUnit();

        if (unit.getPrimaryOrderState() == HEAL_STATE_APPROACHING) {
            if (target != null && this.game.getEngineGame().getCombat().pursueTarget(unit, target, instructionLimit) &&
                (!this.game.getEngineGame().getMovement().isAtDestination(unit) || (unit.getStatus() & UnitStatusFlags.IMMOVABLE) == 0)) {
                if (this.game.getUnitTargeting().isInRange(unit, target, HEAL_RANGE)) {
                    this.game.getEngineGame().getMovement().stop(unit);
                    unit.setPrimaryOrderState(HEAL_STATE_HEALING);
                }
            } else {
                this.game.getOrders().finishOrder(unit, instructionLimit);
            }
        } else if (result == HEALED) {
            this.startHealAnimation(unit, instructionLimit);

            Sprite targetSprite = unit.requireOrderTargetUnit().requireSprite();

            unit.setDirectionTargetX(targetSprite.getX());
            unit.setDirectionTargetY(targetSprite.getY());
        } else {
            unit.setPrimaryOrderState(HEAL_STATE_APPROACHING);
        }
    }

    /**
     * Moves to the target position, diverting to heal units found on the way.
     *
     * @param unit             the Medic
     * @param instructionLimit the order budget
     * @throws IllegalStateException if the target has no sprite
     */
    public void updateHealMove(@NotNull Unit unit, int instructionLimit) {
        Unit target = unit.getOrderTargetUnit();

        if (target != null) {
            if (this.canHeal(unit, target)) {
                this.game.getOrders().issue(unit, ScrOrderIds.MEDIC_HEAL, target, 0, 0, ScrUnitIds.NONE, false, instructionLimit);
                this.game.getEngineGame().getMovement().setTargetUnit(unit, target);

                return;
            }

            Sprite targetSprite = target.requireSprite();

            unit.setOrderTargetX(targetSprite.getX());
            unit.setOrderTargetY(targetSprite.getY());
        }

        if (unit.getMovementDestinationCountdown() == 0) {
            this.game.getEngineGame().getMovement().setDestination(unit, unit.getOrderTargetX(), unit.getOrderTargetY());
        }

        if (this.game.getEngineGame().getMovement().isAtDestination(unit)) {
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        if (unit.getOrderTimer() != 0) {
            return;
        }

        unit.setOrderTimer(HEAL_SEARCH_DELAY);

        Unit healTarget = this.findTarget(unit);

        if (healTarget == null) {
            return;
        }

        int previousX = unit.getOrderTargetX();
        int previousY = unit.getOrderTargetY();

        this.game.getOrders().issue(unit, ScrOrderIds.MEDIC_HEAL, healTarget, 0, 0, ScrUnitIds.NONE, false, instructionLimit);
        this.game.getEngineGame().getMovement().setTargetUnit(unit, healTarget);

        if (target != null) {
            Sprite targetSprite = target.requireSprite();

            this.game.getOrders().queueBack(unit, ScrOrderIds.HEAL_MOVE, target, targetSprite.getX(), targetSprite.getY(), ScrUnitIds.NONE);
        } else {
            this.game.getOrders().queueBack(unit, ScrOrderIds.HEAL_MOVE, null, previousX, previousY, ScrUnitIds.NONE);
        }
    }

    /**
     * Stays in place and heals units within reach.
     *
     * @param unit             the Medic
     * @param instructionLimit the script and hit point budget
     * @throws IllegalStateException if the unit, the order target or the found target has no sprite
     */
    public void updateHoldPosition(@NotNull Unit unit, int instructionLimit) {
        Sprite sprite = unit.requireSprite();

        if (unit.getPrimaryOrderState() == HEAL_STATE_APPROACHING) {
            this.game.getUnitPointMovement().stopAndFaceDestination(unit);
            unit.markReadyToAttack();
            unit.setPrimaryOrderState(HEAL_STATE_HEALING);
            this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_WALKING_TO_IDLE, 0, instructionLimit);
        }

        int result = this.tryHeal(unit, instructionLimit);

        if (result == HEALED) {
            this.startHealAnimation(unit, instructionLimit);

            Sprite targetSprite = unit.requireOrderTargetUnit().requireSprite();

            unit.setDirectionTargetX(targetSprite.getX());
            unit.setDirectionTargetY(targetSprite.getY());

            return;
        }

        if (result == OUT_OF_RANGE) {
            unit.setOrderTargetUnit(null);
        }

        this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(), unit, IscriptExecutor.ANIMATION_WALKING_TO_IDLE, 0, instructionLimit);

        if (unit.getOrderTimer() != 0) {
            return;
        }

        unit.setOrderTimer(HEAL_SEARCH_DELAY);

        Unit target = this.findTarget(unit);

        unit.setOrderTargetUnit(target);

        if (target != null) {
            Sprite targetSprite = target.requireSprite();

            unit.setOrderTargetX(targetSprite.getX());
            unit.setOrderTargetY(targetSprite.getY());
        }
    }

    /**
     * Returns the Medic from healing to its idle animation ({@link IscriptExecutor#ANIMATION_WALKING_TO_IDLE}) and finishes the order.
     *
     * @param unit             the Medic
     * @param instructionLimit the order and script budget
     * @throws IllegalStateException if the unit has no sprite
     */
    public void updateHealToIdle(@NotNull Unit unit, int instructionLimit) {
        if ((unit.getMovementFlags() & MovementFlags.ACCELERATING) == 0) {
            this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(),
                                                              unit,
                                                              IscriptExecutor.ANIMATION_WALKING_TO_IDLE,
                                                              0,
                                                              instructionLimit);
        }

        this.game.getOrders().completeOrder(unit, instructionLimit);
    }

    /**
     * Finds the nearest healable unit not already healed this frame within {@link #HEAL_SEARCH_RADIUS} pixels; a Medic with a computer record uses
     * the computer search.
     *
     * @param unit the Medic
     * @return the heal target, or {@code null}
     * @throws IllegalStateException if the unit has no sprite
     */
    public @Nullable Unit findTarget(@NotNull Unit unit) {
        if (!this.game.getUnitTechnology().canUseTechnology(unit, ScrTechIds.HEALING)) {
            return null;
        }

        if (unit.getComputerRecord() != null) {
            return this.findComputerTarget(unit);
        }

        Sprite sprite = unit.requireSprite();

        return this.game.getUnitTargeting().findNearestUnit(unit,
                                                            sprite.getX() - HEAL_SEARCH_RADIUS, sprite.getY() - HEAL_SEARCH_RADIUS,
                                                            sprite.getX() + HEAL_SEARCH_RADIUS, sprite.getY() + HEAL_SEARCH_RADIUS,
                                                            candidate -> candidate.getHealingState() == 0 && this.canHeal(unit, candidate));
    }

    /**
     * Searches heal targets for a Medic with a computer record: every living, visible, completed, non-hallucinated organic unit of the owner that is
     * not being healed and that the Medic can heal is a candidate, without a range limit. Starting at the first such candidate that is a Medic, every
     * later unit of the owner whose primary order, or else whose first queued order, is {@link ScrOrderIds#MEDIC_HEAL} removes its target from the
     * candidates (only the first matching candidate). The nearest remaining candidate wins; the first one wins ties.
     *
     * @param unit the Medic
     * @return the heal target, or {@code null}
     * @throws IllegalStateException         if a candidate or the unit has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private @Nullable Unit findComputerTarget(@NotNull Unit unit) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        List<Unit> candidates = new ArrayList<>();
        Unit firstMedic = null;

        for (int index = pool.getFirstOwnerIndex(unit.getOwnerIndex()); index != 0; index = pool.getNextOwnerIndex(index)) {
            Unit candidate = pool.getUnit(index);

            if (ComputerUnits.isDeadOrHidden(candidate) || (candidate.requireSprite().getFlags() & Sprite.FLAG_HIDDEN) != 0 ||
                (candidate.getStatus() & UnitStatusFlags.COMPLETED) == 0 || candidate.getHealingState() != 0 || !this.canHeal(unit, candidate) ||
                (candidate.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
                continue;
            }

            if (firstMedic == null && this.game.getBaseType(candidate) == ScrUnitIds.TERRAN_MEDIC) {
                firstMedic = candidate;
            }

            if ((this.game.requireGameData().getUnitTypes().getFlags(candidate.getUnitId()) & UnitTypeFlags.ORGANIC) != 0) {
                candidates.add(candidate);
            }
        }

        if (firstMedic != null) {
            for (int index = firstMedic.getIndex(); index != 0; index = pool.getNextOwnerIndex(index)) {
                Unit healer = pool.getUnit(index);
                Unit healed;

                if (healer.getPrimaryOrder() == ScrOrderIds.MEDIC_HEAL) {
                    healed = healer.getOrderTargetUnit();
                } else {
                    OrderPool.Order queued = healer.getFirstOrder();

                    if (queued == null || queued.getOrderId() != ScrOrderIds.MEDIC_HEAL) {
                        continue;
                    }

                    healed = queued.getTargetUnit();
                }

                int position = candidates.indexOf(healed);

                if (position >= 0) {
                    candidates.set(position, null);
                }
            }
        }

        Sprite sprite = unit.requireSprite();
        Unit best = null;
        int bestDistance = ComputerPlayerPhase.NO_DISTANCE;

        for (Unit candidate : candidates) {
            if (candidate == null) {
                continue;
            }

            Sprite candidateSprite = candidate.requireSprite();
            int distance = RegionPathSearch.calculateDistance(sprite.getY(), candidateSprite.getY(), sprite.getX(), candidateSprite.getX());

            if (Integer.compareUnsigned(distance, bestDistance) < 0) {
                bestDistance = distance;
                best         = candidate;
            }
        }

        return best;
    }

    /**
     * Replaces a target already healed this frame with a new search; in range, the Medic spends the healed hit points divided by
     * {@link #HIT_POINTS_PER_ENERGY} as energy and heals up to {@link #MAXIMUM_HEAL_AMOUNT}.
     *
     * @param unit             the Medic
     * @param instructionLimit the hit point budget
     * @return the heal result
     * @throws IllegalStateException if the target has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    private int tryHeal(@NotNull Unit unit, int instructionLimit) {
        if (!this.game.getUnitTechnology().canUseTechnology(unit, ScrTechIds.HEALING)) {
            return HEAL_FAILED;
        }

        Unit target = unit.getOrderTargetUnit();

        if (!this.canHeal(unit, target)) {
            return HEAL_FAILED;
        }

        if (target.getHealingState() != 0) {
            target = this.findTarget(unit);

            if (target == null) {
                return HEAL_FAILED;
            }

            Sprite targetSprite = target.requireSprite();

            unit.setOrderTargetUnit(target);
            unit.setOrderTargetX(targetSprite.getX());
            unit.setOrderTargetY(targetSprite.getY());
            this.game.getEngineGame().getMovement().setTargetUnit(unit, target);
        }

        if (!this.game.getUnitTargeting().isInRange(unit, target, HEAL_RANGE)) {
            return OUT_OF_RANGE;
        }

        int healAmount = Math.min(this.game.requireGameData().getUnitTypes().getHitPoints(target.getUnitId()) - target.getHitPoints(),
                                  MAXIMUM_HEAL_AMOUNT);
        int energyRequired = healAmount / HIT_POINTS_PER_ENERGY;

        if (!this.game.getUnitEnergy().hasEnergy(unit, energyRequired)) {
            return NO_ENERGY;
        }

        this.game.getUnitEnergy().spend(unit, energyRequired);
        this.game.getUnitHealth().setHitPoints(target, target.getHitPoints() + healAmount, instructionLimit);
        target.setHealingState(HEALING_STATE_HEALED);

        return HEALED;
    }

    /**
     * Runs the heal animation ({@link IscriptExecutor#ANIMATION_SPECIAL_STATE_1}) and queues {@link ScrOrderIds#MEDIC_HEAL_TO_IDLE} at the front unless
     * it is already queued first; a set action-done order signal skips both.
     *
     * @param unit             the Medic
     * @param instructionLimit the script budget
     * @throws IllegalStateException if the unit has no sprite
     */
    private void startHealAnimation(@NotNull Unit unit, int instructionLimit) {
        if ((unit.getOrderSignal() & Unit.ORDER_SIGNAL_ACTION_DONE) != 0) {
            return;
        }

        this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(), unit, IscriptExecutor.ANIMATION_SPECIAL_STATE_1, 0, instructionLimit);

        OrderPool.Order next = unit.getFirstOrder();

        if (next == null || next.getOrderId() != ScrOrderIds.MEDIC_HEAL_TO_IDLE) {
            this.game.getOrders().queueFront(unit, ScrOrderIds.MEDIC_HEAL_TO_IDLE, null, 0, 0, ScrUnitIds.NONE);
        }
    }
}
