package org.fogofwarlabs.sc4j.scr.game.unit.finder;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.collision.*;
import org.fogofwarlabs.sc4j.engine.hook.HookList;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitInitializer;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;

/**
 * Decides which SC:R units block each other: the movement blocking rule and the placement blocking rule of one game, with the hooks of their
 * native branches resolved once ({@link CollisionSystem#BLOCKING}, {@link CollisionSystem#PLACEMENT_EXEMPTION}). The unit finder, the movement
 * collision and the path obstacles run these rules; the engine {@link org.fogofwarlabs.sc4j.engine.collision.UnitCollisionSearch} takes them as
 * {@link BlockingTest} objects.
 * <p>
 * Determinism contract: the rules read the units only and draw no random value; the hooks of a point are asked in registration order until one
 * answers.
 */
public final class UnitCollisionRules {
    private final @NotNull HookList<BlockingHook> blockingHooks;
    private final @NotNull HookList<PlacementExemptionHook> placementExemptionHooks;

    private final @NotNull BlockingTest<Unit> movementTest;
    private final @NotNull BlockingTest<Unit> groundMovementTest;

    /**
     * Creates a {@link UnitCollisionRules} instance over the hooks of a game.
     *
     * @param blockingHooks           the hooks of {@link CollisionSystem#BLOCKING}
     * @param placementExemptionHooks the hooks of {@link CollisionSystem#PLACEMENT_EXEMPTION}
     */
    public UnitCollisionRules(@NotNull HookList<BlockingHook> blockingHooks, @NotNull HookList<PlacementExemptionHook> placementExemptionHooks) {
        this.blockingHooks           = blockingHooks;
        this.placementExemptionHooks = placementExemptionHooks;

        this.movementTest       = (unit, otherUnit) -> this.isBlockingMovement(otherUnit, unit);
        this.groundMovementTest = (unit, otherUnit) -> this.isBlockingMovement(otherUnit, unit) &&
                                                       (otherUnit.getStatus() & UnitStatusFlags.GROUND_UNIT) != 0;
    }

    /**
     * @return the movement blocking rule as a search test: the other unit blocks the moving unit ({@link #isBlockingMovement})
     */
    public @NotNull BlockingTest<Unit> getMovementTest() {
        return this.movementTest;
    }

    /**
     * @return the movement blocking rule of ground units as a search test: the other unit blocks the moving unit ({@link #isBlockingMovement}) and has the ground-unit status
     */
    public @NotNull BlockingTest<Unit> getGroundMovementTest() {
        return this.groundMovementTest;
    }


    /**
     * Checks whether a candidate unit blocks the movement of an incoming unit: only a candidate with the ground elevation path flag and without the
     * no-collide status blocks, and never the incoming unit itself; then the blocking hooks answer (the SC:R hook: a Larva is blocked only by
     * grounded buildings and every other unit passes Larvae; a gathering worker is blocked only by grounded buildings and, while it does not return
     * gas, by gathering workers that wait for gas); without an answer the candidate blocks.
     *
     * @param candidate the unit that may block
     * @param incoming  the moving unit
     * @return {@code true} if the candidate blocks the incoming unit, {@code false} otherwise
     * @throws UnsupportedOperationException if the candidate has the unrepresented status flag {@link UnitStatusFlags#FLAG_400000}
     */
    public boolean isBlockingMovement(@NotNull Unit candidate, @NotNull Unit incoming) {
        int candidateStatus = candidate.getStatus();

        if ((candidate.getPathCollisionFlags() & UnitInitializer.GROUND_ELEVATION_PATH_FLAG) == 0 ||
            (candidateStatus & UnitStatusFlags.NO_COLLIDE) != 0 ||
            candidate == incoming) {
            return false;
        }

        if ((candidateStatus & UnitStatusFlags.FLAG_400000) != 0) {
            throw Exceptions.newUnsupportedOperationException("Movement collision requires the retained alliance matrix.");
        }

        HookList<BlockingHook> hooks = this.blockingHooks;

        for (int index = 0; index < hooks.size(); index++) {
            CollisionAnswer answer = hooks.get(index).answerBlocking(incoming, candidate);

            if (answer != CollisionAnswer.NONE) {
                return answer == CollisionAnswer.BLOCKS;
            }
        }

        return true;
    }

    /**
     * Tests whether an indexed candidate blocks the placement of an incoming unit: a candidate without a sprite or destroyed, or either unit with
     * the no-collide status, never blocks; a power-up is blocked only by grounded buildings and power-ups; then the placement exemption hooks
     * answer (the SC:R hook: a Starport, Robotics Facility or Stargate blocks the air units it produces); without an answer units on the same
     * layer (both flying or both not) block each other.
     *
     * @param candidate the indexed candidate unit
     * @param incoming  the unit being placed
     * @param units     the unit type table
     * @return {@code true} if the candidate blocks the incoming unit, {@code false} otherwise
     */
    public boolean blocksPlacement(@NotNull Unit candidate, @NotNull Unit incoming, @NotNull ScrUnitTypeView units) {
        if (candidate.getSpriteOrNull() == null || candidate.isDestroyed()) {
            return false;
        }

        int candidateStatus = candidate.getStatus();
        int incomingStatus = incoming.getStatus();

        if ((candidateStatus & UnitStatusFlags.NO_COLLIDE) != 0 || (incomingStatus & UnitStatusFlags.NO_COLLIDE) != 0) {
            return false;
        }

        if ((units.getFlags(incoming.getUnitId()) & UnitTypeFlags.POWERUP) != 0) {
            return UnitQueries.isGroundedBuilding(candidate) || (units.getFlags(candidate.getUnitId()) & UnitTypeFlags.POWERUP) != 0;
        }

        HookList<PlacementExemptionHook> hooks = this.placementExemptionHooks;

        for (int index = 0; index < hooks.size(); index++) {
            CollisionAnswer answer = hooks.get(index).answerPlacementBlocking(incoming, candidate);

            if (answer != CollisionAnswer.NONE) {
                return answer == CollisionAnswer.BLOCKS;
            }
        }

        return (candidateStatus & UnitStatusFlags.FLYING) == (incomingStatus & UnitStatusFlags.FLYING);
    }
}
