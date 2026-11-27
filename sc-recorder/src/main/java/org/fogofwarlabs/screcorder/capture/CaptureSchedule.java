package org.fogofwarlabs.screcorder.capture;

import org.fogofwarlabs.screcorder.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

/**
 * Selects the observed frames that a capture samples.
 * <p>
 * A frame is selected when it lies inside the frame window and either belongs to the dense prefix
 * ({@code frame < firstFrame + densePrefix}), is the first observed frame at or after a multiple of the stride (so a
 * missed multiple is replaced by the next observed frame), or is at or after the final expected frame.
 * Frames outside the selection are not sampled; the recording lists them as missing ranges.
 */
public final class CaptureSchedule {
    private static final long UNBOUNDED = Long.MAX_VALUE;

    private static final @NotNull CaptureSchedule EVERY_FRAME = new CaptureSchedule(0, UNBOUNDED, 0, 1);

    /**
     * @return the shared schedule that selects every frame
     */
    public static @NotNull CaptureSchedule getEveryFrame() {
        return EVERY_FRAME;
    }


    private final long firstFrame;
    private final long lastFrame;
    private final long densePrefix;
    private final long stride;

    /**
     * Creates a {@link CaptureSchedule} instance.
     *
     * @param firstFrame  the first frame of the window
     * @param lastFrame   the last frame of the window, inclusive
     * @param densePrefix the number of frames from the window start that are all selected
     * @param stride      the selection period after the dense prefix; {@code 1} selects every frame
     * @throws IllegalArgumentException if the window is negative or empty, the dense prefix is negative or the stride is not positive
     */
    public CaptureSchedule(long firstFrame, long lastFrame, long densePrefix, long stride) {
        if (firstFrame < 0 || lastFrame < firstFrame || densePrefix < 0 || stride < 1) {
            throw Exceptions.newIllegalArgumentException("Invalid capture schedule: frames {0}..{1}, dense {2}, stride {3}",
                                                         firstFrame, lastFrame, densePrefix, stride);
        }

        this.firstFrame  = firstFrame;
        this.lastFrame   = lastFrame;
        this.densePrefix = densePrefix;
        this.stride      = stride;
    }

    /**
     * @return the first frame of the window
     */
    public long getFirstFrame() {
        return this.firstFrame;
    }

    /**
     * @return the last frame of the window, inclusive
     */
    public long getLastFrame() {
        return this.lastFrame;
    }

    /**
     * @return {@code true} if the window has no upper bound, {@code false} otherwise
     */
    public boolean isUnbounded() {
        return this.lastFrame == UNBOUNDED;
    }


    /**
     * Checks whether an observed frame is sampled.
     *
     * @param frame        the observed frame
     * @param finalFrame   the final expected frame, or {@code -1} when unknown
     * @param writtenFrame the last recorded frame, or {@code -1} before the first
     * @return {@code true} if the frame is sampled, {@code false} otherwise
     */
    public boolean isSelected(long frame, long finalFrame, long writtenFrame) {
        if (frame < this.firstFrame || frame > this.lastFrame || frame <= writtenFrame) {
            return false;
        }

        return frame - this.firstFrame < this.densePrefix || writtenFrame < 0 ||
               Math.floorDiv(frame, this.stride) > Math.floorDiv(writtenFrame, this.stride) ||
               finalFrame >= 0 && frame >= finalFrame;
    }

    /**
     * Checks whether an observed frame is past the window.
     *
     * @param frame the observed frame
     * @return {@code true} if every later frame is outside the window, {@code false} otherwise
     */
    public boolean isPastWindow(long frame) {
        return frame > this.lastFrame;
    }

    /**
     * {@return a string representation of the object}
     * <p>
     * Satisfying this method's contract implies a non-{@code null}
     * result must be returned.
     *
     * @apiNote In general, the
     *         {@code toString} method returns a string that
     *         "textually represents" this object. The result should
     *         be a concise but informative representation that is easy for a
     *         person to read.
     *         It is recommended that all subclasses override this method.
     *         The string output is not necessarily stable over time or across
     *         JVM invocations.
     * @implSpec Returns the schedule as {@code frames=A..B dense=N stride=K}, with an empty {@code B} for an unbounded
     *         window.
     */
    @Override
    public @NotNull String toString() {
        return "frames=" + this.firstFrame + ".." + (this.isUnbounded() ? "" : Long.toString(this.lastFrame)) +
               " dense=" + this.densePrefix + " stride=" + this.stride;
    }
}
