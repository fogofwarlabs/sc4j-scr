package org.fogofwarlabs.sc4j.scr.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;

/**
 * Checks that the portable host timing reads both host clocks from one tick clock.
 */
final class PortableHostTimingTest {
    /**
     * Two consecutive clock readings across the 32-bit wrap and their wrapped difference.
     */
    private static final int FIRST_READING = 0xFFFFFFF0;
    private static final int SECOND_READING = 0x00000010;
    private static final int WRAPPED_DIFFERENCE = 0x20;

    @Test
    void read_elapsed_ticks_should_return_the_wrapped_difference_of_two_clock_readings() {
        Iterator<Integer> readings = List.of(FIRST_READING, SECOND_READING).iterator();
        PortableHostTiming timing = new PortableHostTiming(readings::next);

        Assertions.assertEquals(WRAPPED_DIFFERENCE, timing.readElapsedTicks());
        Assertions.assertFalse(readings.hasNext());
    }

    @Test
    void read_host_ticks_should_return_the_clock_reading() {
        PortableHostTiming timing = new PortableHostTiming(() -> FIRST_READING);

        Assertions.assertEquals(FIRST_READING, timing.readHostTicks());
    }
}
