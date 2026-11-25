package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.engine.client.host.HostTickClock;
import org.jetbrains.annotations.NotNull;

/**
 * Reads the host clocks of a game's frames: the host uptime clock of display scheduling and the elapsed tick difference that the unit phase
 * compares with its host-timing diagnostic range.
 * <p>
 * The elapsed tick difference is the uptime clock minus an earlier reading of the same clock, so it is {@code 0} or a few ticks on every host
 * and the unit phase stays outside the diagnostic range.
 */
public interface HostTiming {
    /**
     * Creates a {@link HostTiming} instance of the host the client runs on.
     *
     * @return the created {@link HostTiming} instance: a {@link WindowsHostTiming} on Windows x64, otherwise a {@link PortableHostTiming} over the engine host clock
     */
    static @NotNull HostTiming create() {
        return WindowsHostTiming.isSupported() ? new WindowsHostTiming() : new PortableHostTiming(HostTickClock.getDefault());
    }

    /**
     * Reads the elapsed tick difference that the unit phase compares with its host-timing diagnostic range.
     *
     * @return the wrapped 32-bit difference of the host uptime clock and an earlier reading of the same clock
     * @throws IllegalStateException if the platform call fails
     */
    int readElapsedTicks();

    /**
     * Reads the host uptime clock used by host display scheduling.
     *
     * @return the milliseconds counter, wrapping at {@code 2^32}
     * @throws IllegalStateException if the platform call fails
     */
    int readHostTicks();
}
