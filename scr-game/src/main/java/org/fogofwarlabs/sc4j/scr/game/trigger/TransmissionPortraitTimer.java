package org.fogofwarlabs.sc4j.scr.game.trigger;

/**
 * Tracks a Transmission portrait's one-shot host-clock timer.
 * <p>
 * Arming replaces the running timer. Expiration uses signed DWORD subtraction and clears the armed state before the
 * caller applies the portrait callback. Host-clock timing is presentation state; headless playback uses recorded wait
 * completion events instead.
 */
public final class TransmissionPortraitTimer {
    private boolean armed;
    private int start;
    private int interval;

    /**
     * Creates a {@link TransmissionPortraitTimer} instance that is not armed.
     */
    public TransmissionPortraitTimer() {
        this.armed    = false;
        this.start    = 0;
        this.interval = 0;
    }

    /**
     * @return {@code true} if the timer is armed, {@code false} otherwise
     */
    public boolean isArmed() {
        return this.armed;
    }


    /**
     * Arms the timer, replacing a running one.
     *
     * @param now      the host millisecond DWORD at the Transmission
     * @param duration the Transmission duration in milliseconds (the {@link TriggerEvents#TRANSMISSION} fourth value), a DWORD
     */
    public void arm(int now, int duration) {
        this.armed    = true;
        this.start    = now;
        this.interval = duration;
    }

    /**
     * Stops the timer without firing it.
     */
    public void cancel() {
        this.armed = false;
    }

    /**
     * Pumps the timer: an armed timer whose {@code now - interval - start} is positive fires once and is killed.
     *
     * @param now the host millisecond DWORD of this interface loop
     * @return {@code true} if the timer fired, so the caller applies the portrait callback, {@code false} otherwise
     */
    public boolean expire(int now) {
        if (!this.armed || now - this.interval - this.start <= 0) {
            return false;
        }

        this.armed = false;

        return true;
    }
}
