package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.engine.client.host.HostTickClock;
import org.jetbrains.annotations.NotNull;

/**
 * Reads both host clocks from one {@link HostTickClock}: the elapsed tick difference is the difference of two consecutive readings.
 */
public final class PortableHostTiming implements HostTiming {
    private final @NotNull HostTickClock clock;

    /**
     * Creates a {@link PortableHostTiming} instance.
     *
     * @param clock the host uptime clock
     */
    public PortableHostTiming(@NotNull HostTickClock clock) {
        this.clock = clock;
    }

    /**
     * Reads the elapsed tick difference that the unit phase compares with its host-timing diagnostic range: the host uptime clock minus the
     * reading taken just before it.
     *
     * @return the wrapped 32-bit difference of the host uptime clock and an earlier reading of the same clock
     * @throws IllegalStateException if the platform call fails
     */
    @Override
    public int readElapsedTicks() {
        int previousTicks = this.clock.readTicks();

        return this.readHostTicks() - previousTicks;
    }

    /**
     * Reads the host uptime clock used by host display scheduling.
     *
     * @return the milliseconds counter, wrapping at {@code 2^32}
     * @throws IllegalStateException if the platform call fails
     */
    @Override
    public int readHostTicks() {
        return this.clock.readTicks();
    }
}
