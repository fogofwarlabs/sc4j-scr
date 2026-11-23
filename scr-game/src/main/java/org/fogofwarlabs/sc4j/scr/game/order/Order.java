package org.fogofwarlabs.sc4j.scr.game.order;

import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;

/**
 * The step of one order type at one stage of the per-unit order dispatch ({@link OrderStage}): a part of the {@link ScrOrderBehavior} of the type.
 * <p>
 * It runs inside the frame, so it must not allocate per call. One instance may serve several order types and stages that share their behavior.
 */
@FunctionalInterface
public interface Order {
    /**
     * A step without behavior; setting it marks the type as handled at that stage.
     */
    @NotNull Order EMPTY = (context, unit) -> {
    };

    /**
     * Runs the step for one unit.
     *
     * @param context the dispatch context: the player type of the unit's owner for this frame and the instruction budget of each animation script invocation
     * @param unit    the unit whose order runs
     */
    void update(@NotNull ScrOrderContext context, @NotNull Unit unit);
}
