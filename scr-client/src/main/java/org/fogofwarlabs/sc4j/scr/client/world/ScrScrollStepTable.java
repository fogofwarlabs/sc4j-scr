package org.fogofwarlabs.sc4j.scr.client.world;

import org.fogofwarlabs.sc4j.engine.client.world.ScrollStepTable;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalInterfaceState;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * Gives the SC:R camera scroll steps: {@value LocalInterfaceState#SCROLL_SPEED_COUNT} speed rows of
 * {@value LocalInterfaceState#SCROLL_STEP_COUNT} steps, read from the local interface of the running game at every tick, so that EUD writes to
 * the table change the scrolling at once.
 */
public final class ScrScrollStepTable implements ScrollStepTable {
    private final @NotNull Supplier<@NotNull LocalInterfaceState> localInterface;

    /**
     * Creates a {@link ScrScrollStepTable} instance.
     *
     * @param localInterface what gives the local interface of the running game
     */
    public ScrScrollStepTable(@NotNull Supplier<@NotNull LocalInterfaceState> localInterface) {
        this.localInterface = localInterface;
    }

    /**
     * @return the number of speed rows, at least {@code 1}
     */
    @Override
    public int getSpeedCount() {
        return LocalInterfaceState.SCROLL_SPEED_COUNT;
    }

    /**
     * @return the number of steps of every speed row, at least {@code 1}: a scroll raises its step index by one per moving update up to the last step
     */
    @Override
    public int getStepCount() {
        return LocalInterfaceState.SCROLL_STEP_COUNT;
    }


    /**
     * Reads one step.
     *
     * @param speed the speed row from {@code 0} through {@link #getSpeedCount()} {@code - 1}
     * @param index the step index from {@code 0} through {@link #getStepCount()} {@code - 1}
     * @return the step in pixels
     */
    @Override
    public int getStep(int speed, int index) {
        return this.localInterface.get().getScrollStep(speed, index);
    }
}
