package org.fogofwarlabs.sc4j.scr.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks that the host timing of the current host keeps the unit phase outside its host-timing diagnostic range.
 */
final class HostTimingTest {
    /**
     * The largest elapsed tick difference of the diagnostic's short branch.
     */
    private static final int DIAGNOSTIC_ELAPSED_LIMIT = 7500;
    /**
     * The smallest elapsed tick difference of the diagnostic's wrapped branch.
     */
    private static final int DIAGNOSTIC_WRAPPED_MINIMUM = 0x0FFFFFFF;

    @Test
    void create_should_return_a_timing_whose_elapsed_ticks_stay_outside_the_diagnostic_range() {
        int elapsedTicks = HostTiming.create().readElapsedTicks();

        Assertions.assertTrue(Integer.compareUnsigned(elapsedTicks, DIAGNOSTIC_ELAPSED_LIMIT) <= 0 ||
                              Integer.compareUnsigned(elapsedTicks, DIAGNOSTIC_WRAPPED_MINIMUM) >= 0,
                              "The elapsed tick difference " + Integer.toUnsignedString(elapsedTicks) + " lies in the diagnostic range.");
    }
}
