package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.text.TerminatedStringUtils;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Owns the four visible replay labels and their localized formats.
 */
public final class ReplayStatusText {
    /**
     * The number of visible replay labels.
     */
    private static final int LABEL_COUNT = 4;
    /**
     * The number of localized texts.
     */
    private static final int LOCALIZED_TEXT_COUNT = 12;

    /**
     * The label index of the replay title.
     */
    private static final int REPLAY_LABEL = 0;
    /**
     * The label index of the elapsed time.
     */
    private static final int TIME_LABEL = 1;
    /**
     * The label index of the game speed or the target time.
     */
    private static final int SPEED_LABEL = 2;
    /**
     * The label index of the pause message.
     */
    private static final int PAUSE_LABEL = 3;

    /**
     * The localized text index of the replay title.
     */
    private static final int REPLAY_TEXT = 0;
    /**
     * The localized text index of the elapsed time format.
     */
    private static final int TIME_TEXT = 1;
    /**
     * The localized text index of the target time format.
     */
    private static final int TARGET_TIME_TEXT = 2;
    /**
     * The localized text index of the game speed caption.
     */
    private static final int SPEED_TEXT = 3;
    /**
     * The localized text index of the slowest game speed name; the faster speeds follow.
     */
    private static final int FIRST_SPEED_TEXT = 4;
    /**
     * The localized text index of the pause message.
     */
    private static final int PAUSE_TEXT = 11;

    /**
     * The replay speed multiplier that shows no multiplier text.
     */
    private static final int NORMAL_REPLAY_MULTIPLIER = 1;
    /**
     * The first speed setting whose multiplier text multiplies ({@code x}); slower settings divide ({@code /}).
     */
    private static final int FIRST_MULTIPLIED_SPEED = 3;
    /**
     * The most characters of the multiplier text.
     */
    private static final int MULTIPLIER_LENGTH = 7;
    /**
     * The most bytes of a label.
     */
    private static final int MAXIMUM_LABEL_LENGTH = 99;
    /**
     * The mask of an unsigned byte.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The multiplier that combines each retained value into the state hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    private final byte @NotNull [] @NotNull [] labels;
    private final @NotNull String @NotNull [] localizedText;

    /**
     * Creates a {@link ReplayStatusText} instance with established localization results.
     *
     * @param labels        the four visible byte strings in label order ({@code -45}, {@code -46}, {@code -47}, {@code -48})
     * @param localizedText the byte strings of {@code 609}, {@code scoreTIME_s}, {@code 60C}, {@code gameSPEED}, {@code gameSLOWEST} through {@code gameFASTEST} and {@code 60A}
     * @throws IllegalArgumentException if {@code labels} does not hold {@code 4} strings or {@code localizedText} does not hold {@code 12}
     */
    public ReplayStatusText(byte @NotNull [] @NotNull [] labels, byte @NotNull [] @NotNull [] localizedText) {
        if (labels.length != LABEL_COUNT || localizedText.length != LOCALIZED_TEXT_COUNT) {
            throw Exceptions.newIllegalArgumentException("Replay labels require four strings and twelve localization results.");
        }

        this.labels = new byte[LABEL_COUNT][];

        for (int index = 0; index < LABEL_COUNT; index++) {
            this.labels[index] = labels[index].clone();
        }

        this.localizedText = new String[LOCALIZED_TEXT_COUNT];

        for (int index = 0; index < LOCALIZED_TEXT_COUNT; index++) {
            int length = TerminatedStringUtils.getTextLength(localizedText[index]);

            this.localizedText[index] = new String(localizedText[index], 0, length, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * @param index the label index from {@code 0} through {@code 3}
     * @return the copied string bytes without the terminating zero
     */
    public byte @NotNull [] getText(int index) {
        return this.labels[index].clone();
    }


    /**
     * Applies replay text writes with the original unsigned, wrapping frame arithmetic.
     *
     * @param frame        the current replay frame
     * @param targetFrame  the target replay frame
     * @param framePeriod  the selected frame duration
     * @param paused       the pause state
     * @param pauseMessage the pause message state
     * @param speedSetting the signed speed setting
     * @param replaySpeed  the replay speed multiplier
     * @throws UnsupportedOperationException if {@code targetFrame} is not after {@code frame} (unsigned) and {@code speedSetting} lies outside {@code 0} through {@link GameSpeed#FASTEST}
     */
    public void update(int frame, int targetFrame, int framePeriod, int paused, int pauseMessage, int speedSetting, int replaySpeed) {
        this.writeLabelText(REPLAY_LABEL, "\u0003" + this.localizedText[REPLAY_TEXT]);
        this.writeLabelText(TIME_LABEL, this.formatTimeLabel(this.localizedText[TIME_TEXT], frame, framePeriod));

        if (Integer.compareUnsigned(targetFrame, frame) > 0) {
            this.writeLabelText(SPEED_LABEL, this.formatTimeLabel(this.localizedText[TARGET_TIME_TEXT], targetFrame, framePeriod));
        } else {
            if (speedSetting < 0 || speedSetting > GameSpeed.FASTEST) {
                throw Exceptions.newUnsupportedOperationException("Replay speed localization requires an established table index.");
            }

            String multiplier = replaySpeed > NORMAL_REPLAY_MULTIPLIER ? " " + (speedSetting < FIRST_MULTIPLIED_SPEED ? "/" : "x") + replaySpeed : "";

            if (multiplier.length() > MULTIPLIER_LENGTH) {
                multiplier = multiplier.substring(0, MULTIPLIER_LENGTH);
            }

            this.writeLabelText(SPEED_LABEL, this.localizedText[SPEED_TEXT] + " " + this.localizedText[FIRST_SPEED_TEXT + speedSetting] + multiplier);
        }

        if (paused != 0) {
            this.writeLabelText(PAUSE_LABEL, pauseMessage != 0 ? this.localizedText[PAUSE_TEXT] : "");
        }
    }

    /**
     * Computes a hash of the visible labels and localization inputs.
     *
     * @param hash the preceding deterministic state hash
     * @return the hash including visible labels and localization inputs
     */
    public int computeStateHash(int hash) {
        for (byte[] label : this.labels) {
            hash = HASH_MULTIPLIER * hash + label.length;

            for (byte value : label) {
                hash = HASH_MULTIPLIER * hash + (value & BYTE_MASK);
            }
        }

        for (String value : this.localizedText) {
            hash = HASH_MULTIPLIER * hash + value.hashCode();
        }

        return hash;
    }

    /**
     * Formats a localized time label: {@code %%} becomes {@code %} and the first {@code %s} the elapsed time of the frame.
     *
     * @param format      the localized format
     * @param frame       the frame
     * @param framePeriod the frame duration
     * @return the formatted label
     * @throws UnsupportedOperationException if the format ends inside a conversion or has another conversion
     */
    private @NotNull String formatTimeLabel(@NotNull String format, int frame, int framePeriod) {
        String time = TimerTextUtils.formatElapsedTime(TimerTextUtils.calculateElapsedSeconds(frame, framePeriod));
        StringBuilder result = new StringBuilder();
        boolean substituted = false;

        for (int index = 0; index < format.length(); index++) {
            char character = format.charAt(index);

            if (character != '%') {
                result.append(character);

                continue;
            }

            if (++index == format.length()) {
                throw Exceptions.newUnsupportedOperationException("Native replay localization contains an incomplete format.");
            }

            character = format.charAt(index);

            if (character == '%') {
                result.append('%');
            } else if (character == 's' && !substituted) {
                result.append(time);
                substituted = true;
            } else {
                throw Exceptions.newUnsupportedOperationException("Native replay localization requires an unrepresented format conversion.");
            }
        }

        return result.toString();
    }

    private void writeLabelText(int index, @NotNull String value) {
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        int length = Math.min(MAXIMUM_LABEL_LENGTH, bytes.length);

        this.labels[index] = length == bytes.length ? bytes : Arrays.copyOf(bytes, length);
    }
}
