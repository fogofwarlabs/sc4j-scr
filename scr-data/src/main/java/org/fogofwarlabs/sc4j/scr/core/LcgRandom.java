package org.fogofwarlabs.sc4j.scr.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.random.DeterministicRandom;
import org.fogofwarlabs.sc4j.engine.random.RandomFactory;
import org.fogofwarlabs.sc4j.engine.random.RandomState;
import org.fogofwarlabs.sc4j.engine.state.StateHash;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Represents the shared 32-bit random state and call counters.
 * <p>
 * Generation advances the state as {@code state = state * 22695477 + 1} and returns bits 16-30. An enabled
 * call also increments the total counter and its source counter. A disabled call returns zero without advancing them.
 * Arithmetic uses 32-bit overflow.
 * <p>
 * Instances start enabled for simulation use. Callers must reproduce the original enable/disable scopes. Diagnostic
 * side effects and accesses outside the {@value #SOURCE_COUNT} source counters are not modeled.
 * <p>
 * As the {@link DeterministicRandom} of the SC:R mod, the active flag is the enable flag: {@link #setActive} writes
 * {@code 1} or {@code 0}, while {@link #setEnabled} keeps any value an EUD write gives it. The saved state holds the
 * state, the enable flag, the total call count and the source counts, in this order.
 */
public final class LcgRandom implements DeterministicRandom {
    /**
     * The number of random-source call counters.
     */
    public static final int SOURCE_COUNT = 256;

    /**
     * Creates the generators of SC:R games: the low {@code 32} bits of the seed are the initial state.
     */
    public static final @NotNull RandomFactory FACTORY = seed -> new LcgRandom((int) seed);

    private static final int MULTIPLIER = 22695477;
    private static final int INCREMENT = 1;
    private static final int OUTPUT_SHIFT = 16;
    private static final int OUTPUT_MASK = 0x7FFF;
    private static final int OUTPUT_BITS = 15;

    /**
     * The enable flag that {@link #setActive} writes for an active generator.
     */
    private static final int ENABLED = 1;
    /**
     * The enable flag of an inactive generator.
     */
    private static final int DISABLED = 0;

    /**
     * The position of the state in the saved words.
     */
    private static final int STATE_WORD = 0;
    /**
     * The position of the enable flag in the saved words.
     */
    private static final int ENABLED_WORD = 1;
    /**
     * The position of the total call count in the saved words.
     */
    private static final int TOTAL_WORD = 2;
    /**
     * The position of the first source count in the saved words.
     */
    private static final int FIRST_COUNT_WORD = 3;
    /**
     * The number of saved words.
     */
    private static final int STATE_WORD_COUNT = FIRST_COUNT_WORD + SOURCE_COUNT;

    private final int @NotNull [] randomCounts;

    private int state;
    private int totalRandomCounts;
    private int enabled;

    /**
     * Creates a {@link LcgRandom} instance with the given seed.
     *
     * @param seed the initial state
     */
    public LcgRandom(int seed) {
        this.randomCounts = new int[SOURCE_COUNT];

        this.state             = seed;
        this.totalRandomCounts = 0;
        this.enabled           = ENABLED;
    }

    /**
     * @return the number of bits of a value of {@link #next}: its values are {@code 0} up to {@code (1 << getOutputBits()) - 1}
     */
    @Override
    public int getOutputBits() {
        return OUTPUT_BITS;
    }

    /**
     * @return the number of call sources this generator counts; a source is {@code 0} up to this number minus one
     */
    @Override
    public int getSourceCount() {
        return SOURCE_COUNT;
    }

    /**
     * @return the current 32-bit state
     */
    public int getState() {
        return this.state;
    }

    /**
     * Sets the state and resets the total and per-source call counters.
     *
     * @param state the new 32-bit state
     */
    public void setState(int state) {
        this.state             = state;
        this.totalRandomCounts = 0;

        Arrays.fill(this.randomCounts, 0);
    }

    /**
     * @return {@code true} if the generator draws, {@code false} if it returns fixed values without advancing
     */
    @Override
    public boolean isActive() {
        return this.enabled != DISABLED;
    }

    /**
     * Sets whether the generator draws: writes the enable flag {@code 1} or {@code 0}.
     *
     * @param active whether the generator draws
     * @return the previous value
     */
    @Override
    public boolean setActive(boolean active) {
        int previousEnabled = this.enabled;

        this.enabled = active ? ENABLED : DISABLED;

        return previousEnabled != DISABLED;
    }

    /**
     * @return the enable flag; zero disables generation, any other value enables it
     */
    public int getEnabled() {
        return this.enabled;
    }

    /**
     * Replaces the enable flag without changing the state or counters.
     *
     * @param enabled the new enable flag
     * @return the previous enable flag
     */
    public int setEnabled(int enabled) {
        int previousEnabled = this.enabled;

        this.enabled = enabled;

        return previousEnabled;
    }

    /**
     * Returns the number of active calls of one source since the generator was created or its state restored.
     *
     * @param source the source
     * @return the number of active calls of the source
     */
    @Override
    public int getCallCount(int source) {
        return this.randomCounts[source];
    }

    /**
     * @return the total call count over all sources
     */
    public int getTotalRandomCounts() {
        return this.totalRandomCounts;
    }


    /**
     * Replaces the state alone, as an EUD write to the state DWORD does: the call counters stay.
     *
     * @param state the new 32-bit state
     */
    public void writeState(int state) {
        this.state = state;
    }

    /**
     * Draws the next value.
     *
     * @param source the call source
     * @return the drawn value, {@code 0} up to {@code 32767}, or {@code 0} while inactive
     */
    @Override
    public int next(int source) {
        if (this.enabled == DISABLED) {
            return 0;
        }

        this.randomCounts[source]++;
        this.totalRandomCounts++;
        this.state = this.state * MULTIPLIER + INCREMENT;

        return (this.state >>> OUTPUT_SHIFT) & OUTPUT_MASK;
    }

    /**
     * Returns the next random value mapped into the given inclusive range.
     *
     * @param source the call site index; only used for call counting
     * @param from   the lower bound, inclusive
     * @param to     the upper bound, inclusive
     * @return the next value in the range {@code from..to}
     */
    public int next(int source, int from, int to) {
        return from + ((this.next(source) * (to - from + 1)) >>> OUTPUT_BITS);
    }

    /**
     * Draws the next value of an inclusive range. One call draws exactly one value of {@link #next}: the range mapping
     * of {@link #next(int, int, int)}, which does not check the bounds.
     *
     * @param source  the call source
     * @param minimum the lowest value
     * @param maximum the highest value, at least {@code minimum}
     * @return the drawn value, {@code minimum} up to {@code maximum}, or {@code minimum} while inactive
     */
    @Override
    public int nextRange(int source, int minimum, int maximum) {
        return this.next(source, minimum, maximum);
    }

    /**
     * Saves the complete state: the generator state, the counters and the active flag.
     *
     * @return the saved state
     */
    @Override
    public @NotNull RandomState saveState() {
        int[] words = new int[STATE_WORD_COUNT];

        words[STATE_WORD]   = this.state;
        words[ENABLED_WORD] = this.enabled;
        words[TOTAL_WORD]   = this.totalRandomCounts;

        System.arraycopy(this.randomCounts, 0, words, FIRST_COUNT_WORD, SOURCE_COUNT);

        return RandomState.of(words);
    }

    /**
     * Restores a state saved by a generator of the same implementation.
     *
     * @param state the saved state
     * @throws IllegalArgumentException if the state was not saved by a generator of this implementation and source count
     */
    @Override
    public void restoreState(@NotNull RandomState state) {
        if (state.getWordCount() != STATE_WORD_COUNT) {
            throw Exceptions.newIllegalArgumentException("An SC:R random state has {0} words: {1}", STATE_WORD_COUNT, state.getWordCount());
        }

        this.state             = state.getWord(STATE_WORD);
        this.enabled           = state.getWord(ENABLED_WORD);
        this.totalRandomCounts = state.getWord(TOTAL_WORD);

        for (int source = 0; source < SOURCE_COUNT; source++) {
            this.randomCounts[source] = state.getWord(FIRST_COUNT_WORD + source);
        }
    }

    /**
     * Mixes this part of the simulation state into the hash: the state, the enable flag, the total call count and the
     * source counts in source order.
     *
     * @param hash the hash being computed
     */
    @Override
    public void contributeStateHash(@NotNull StateHash hash) {
        hash.mix(this.state);
        hash.mix(this.enabled);
        hash.mix(this.totalRandomCounts);

        for (int source = 0; source < SOURCE_COUNT; source++) {
            hash.mix(this.randomCounts[source]);
        }
    }
}
