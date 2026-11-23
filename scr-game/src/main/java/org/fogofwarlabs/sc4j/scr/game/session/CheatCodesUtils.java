package org.fogofwarlabs.sc4j.scr.game.session;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.scr.game.state.GameCheats;
import org.fogofwarlabs.sc4j.scr.game.state.RuntimeRandom;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

/**
 * Matches typed single-player text against the stored cheat hashes with zero-padded IDEA CBC blocks.
 * <p>
 * A matching hash toggles its cheat flag; the music-only cheat leaves the flags unchanged. The table includes an
 * unconfirmed cheat text that still matches through its hash.
 * <p>
 * The cipher key comes from four draws of a C runtime generator seeded with a fixed seed. A check in a game draws them from
 * the game's shared generator ({@link org.fogofwarlabs.sc4j.scr.game.state.Game#getRuntimeRandom}) and then reseeds it with
 * the current time, so every checked line changes what the generator's other users draw next.
 */
public final class CheatCodesUtils {
    /**
     * The result for text that is not a cheat.
     */
    public static final long NOT_A_CHEAT = -1;

    private static final int KEY_WORD_COUNT = 8;
    private static final int KEY_RANDOM_DRAWS = 4;
    private static final int KEY_RANDOM_SEED = 0x75;
    private static final int KEY_WORDS_PER_DRAW = 2;
    private static final int KEY_SCHEDULE_WORD_COUNT = 60;
    private static final int SUBKEY_COUNT = 52;
    private static final int KEY_WORD_INDEX_MASK = 7;
    private static final int LAST_KEY_WORD_INDEX = 7;
    private static final int KEY_ROTATION_LEFT_SHIFT = 9;
    private static final int KEY_ROTATION_RIGHT_SHIFT = 7;
    private static final int MAXIMUM_TEXT_BYTES = 255;
    private static final int BLOCK_BYTE_COUNT = 8;
    private static final int BLOCK_BYTE_SHIFT = 3;
    private static final int ROUND_COUNT = 8;
    private static final int ROUND_SUBKEY_COUNT = 6;
    private static final int SECOND_ADDITIVE_SUBKEY = 2;
    private static final int SECOND_MULTIPLICATIVE_SUBKEY = 3;
    private static final int FIRST_MIXING_SUBKEY = 4;
    private static final int SECOND_MIXING_SUBKEY = 5;
    private static final int WORD_MASK = 0xFFFF;
    private static final int SECOND_WORD_OFFSET = Short.BYTES;
    private static final int THIRD_WORD_OFFSET = 2 * Short.BYTES;
    private static final int FOURTH_WORD_OFFSET = 3 * Short.BYTES;
    private static final int SECOND_WORD_SHIFT = Short.SIZE;
    private static final int THIRD_WORD_SHIFT = 2 * Short.SIZE;
    private static final int FOURTH_WORD_SHIFT = 3 * Short.SIZE;

    private static final long @NotNull [] HASHES = {
            0x285a16281b5b3daeL, 0x6e26e2b87fe014ccL, 0x902273ac960c7c82L, 0xb0523962e9664828L, 0x7d9e46773a932764L,
            0x32f565353a439a3eL, 0x8d389b4178ef3975L, 0x8f06c2373a479567L, 0x425e8432a9783171L, 0xc8aadfa5b2460378L,
            0xc0a2e0abb8b696e6L, 0xce87028b182e9e9fL, 0xad0842e6eace9558L, 0x9ebdb515fe3beabfL, 0x1648847555e238e6L,
            0xeb5345e4782e7767L, 0x57ac3752d7699bcdL
    };
    private static final int @NotNull [] FLAGS = {
            GameCheats.OPHELIA, GameCheats.SHOW_ME_THE_MONEY, GameCheats.WHATS_MINE_IS_MINE, GameCheats.BREATHE_DEEP,
            GameCheats.SOMETHING_FOR_NOTHING, GameCheats.MEDIEVAL_MAN, GameCheats.MODIFY_THE_PHASE_VARIANCE,
            GameCheats.FOOD_FOR_THOUGHT, GameCheats.THE_GATHERING, GameCheats.BLACK_SHEEP_WALL, GameCheats.OPERATION_CWAL,
            GameCheats.POWER_OVERWHELMING, GameCheats.WAR_AINT_WHAT_IT_USED_TO_BE, GameCheats.THERE_IS_NO_COW_LEVEL,
            GameCheats.GAME_OVER_MAN, GameCheats.STAYING_ALIVE, GameCheats.UNIDENTIFIED_CHEAT
    };
    private static final long NOGLUES_HASH = 0x1ad0844966459a95L;
    private static final long RADIO_FREE_ZERG_HASH = 0x8fbb39123ce4870cL;

    /**
     * Computes the cheat hash of typed text with a key drawn from a generator of its own.
     *
     * @param text the typed text
     * @return the last CBC cipher block as a little-endian QWORD, {@code 0} for empty text
     */
    public static long hash(@NotNull String text) {
        return hash(text, createSubkeys(new RuntimeRandom(KEY_RANDOM_SEED)));
    }

    private static long hash(@NotNull String text, int @NotNull [] subkeys) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(bytes.length, MAXIMUM_TEXT_BYTES);
        int blocks = length == 0 ? 0 : ((length - 1) >>> BLOCK_BYTE_SHIFT) + 1;
        byte[] padded = new byte[blocks * BLOCK_BYTE_COUNT];

        for (int index = 0; index < length; index++) {
            int value = bytes[index];

            padded[index] = (byte) (value >= 'A' && value <= 'Z' ? value + ('a' - 'A') : value);
        }

        int firstCipherWord = 0;
        int secondCipherWord = 0;
        int thirdCipherWord = 0;
        int fourthCipherWord = 0;

        for (int block = 0; block < blocks; block++) {
            int offset = block * BLOCK_BYTE_COUNT;
            int firstWord = LittleEndianUtils.readUnsigned16(padded, offset) ^ firstCipherWord;
            int secondWord = LittleEndianUtils.readUnsigned16(padded, offset + SECOND_WORD_OFFSET) ^ secondCipherWord;
            int thirdWord = LittleEndianUtils.readUnsigned16(padded, offset + THIRD_WORD_OFFSET) ^ thirdCipherWord;
            int fourthWord = LittleEndianUtils.readUnsigned16(padded, offset + FOURTH_WORD_OFFSET) ^ fourthCipherWord;
            int subkeyIndex = 0;

            for (int round = 0; round < ROUND_COUNT; round++) {
                firstWord  = multiply(firstWord, subkeys[subkeyIndex]);
                secondWord = (secondWord + subkeys[subkeyIndex + 1]) & WORD_MASK;
                thirdWord  = (thirdWord + subkeys[subkeyIndex + SECOND_ADDITIVE_SUBKEY]) & WORD_MASK;
                fourthWord = multiply(fourthWord, subkeys[subkeyIndex + SECOND_MULTIPLICATIVE_SUBKEY]);

                int savedThirdWord = thirdWord;

                thirdWord = multiply(thirdWord ^ firstWord, subkeys[subkeyIndex + FIRST_MIXING_SUBKEY]);

                int savedSecondWord = secondWord;

                secondWord = multiply(((secondWord ^ fourthWord) + thirdWord) & WORD_MASK, subkeys[subkeyIndex + SECOND_MIXING_SUBKEY]);
                thirdWord  = (thirdWord + secondWord) & WORD_MASK;
                firstWord ^= secondWord;
                fourthWord ^= thirdWord;
                secondWord ^= savedThirdWord;
                thirdWord ^= savedSecondWord;
                subkeyIndex += ROUND_SUBKEY_COUNT;
            }

            firstCipherWord  = multiply(firstWord, subkeys[subkeyIndex]);
            secondCipherWord = (thirdWord + subkeys[subkeyIndex + 1]) & WORD_MASK;
            thirdCipherWord  = (secondWord + subkeys[subkeyIndex + SECOND_ADDITIVE_SUBKEY]) & WORD_MASK;
            fourthCipherWord = multiply(fourthWord, subkeys[subkeyIndex + SECOND_MULTIPLICATIVE_SUBKEY]);
        }

        return firstCipherWord | (long) secondCipherWord << SECOND_WORD_SHIFT | (long) thirdCipherWord << THIRD_WORD_SHIFT |
               (long) fourthCipherWord << FOURTH_WORD_SHIFT;
    }

    /**
     * Applies the cheat hash table and the special no-glues and music-only checks with a key drawn from a generator of its
     * own, leaving the game's C runtime generator as it is.
     *
     * @param text         the typed text
     * @param currentFlags the current cheat flags
     * @return the new cheat flags when the text is a cheat (equal to {@code currentFlags} for {@code radio free zerg}), else {@link #NOT_A_CHEAT}
     */
    public static long apply(@NotNull String text, int currentFlags) {
        return match(hash(text), currentFlags);
    }

    /**
     * Applies the cheat hash table and the special no-glues and music-only checks for an enabled single-player game.
     * <p>
     * The key draws reseed and advance the game's C runtime generator, which is then reseeded with the time.
     *
     * @param text          the typed text
     * @param currentFlags  the current cheat flags
     * @param runtimeRandom the game's C runtime generator
     * @param unixTime      the current Unix time in seconds, truncated to an {@code int}
     * @return the new cheat flags when the text is a cheat (equal to {@code currentFlags} for {@code radio free zerg}), else {@link #NOT_A_CHEAT}
     */
    public static long apply(@NotNull String text, int currentFlags, @NotNull RuntimeRandom runtimeRandom, int unixTime) {
        int[] subkeys = createSubkeys(runtimeRandom);

        runtimeRandom.seed(unixTime);

        return match(hash(text, subkeys), currentFlags);
    }

    private static long match(long value, int currentFlags) {
        for (int index = 0; index < HASHES.length; index++) {
            if (HASHES[index] == value) {
                return Integer.toUnsignedLong(currentFlags ^ FLAGS[index]);
            }
        }

        if (value == NOGLUES_HASH) {
            return Integer.toUnsignedLong(currentFlags ^ GameCheats.NOGLUES);
        }

        if (value == RADIO_FREE_ZERG_HASH) {
            return Integer.toUnsignedLong(currentFlags);
        }

        return NOT_A_CHEAT;
    }

    /**
     * Creates the cipher subkeys: the generator is reseeded with the key seed and draws the four key values.
     *
     * @param runtimeRandom the generator the key values are drawn from
     * @return the subkeys
     */
    private static int @NotNull [] createSubkeys(@NotNull RuntimeRandom runtimeRandom) {
        int[] keyWords = new int[KEY_WORD_COUNT];

        runtimeRandom.seed(KEY_RANDOM_SEED);

        for (int index = 0; index < KEY_RANDOM_DRAWS; index++) {
            int value = runtimeRandom.draw();

            keyWords[index * KEY_WORDS_PER_DRAW]     = value & WORD_MASK;
            keyWords[index * KEY_WORDS_PER_DRAW + 1] = value >>> Short.SIZE;
        }

        int[] words = new int[KEY_SCHEDULE_WORD_COUNT];

        System.arraycopy(keyWords, 0, words, 0, KEY_WORD_COUNT);

        int base = 0;
        int step = 0;

        for (int count = KEY_WORD_COUNT; count < SUBKEY_COUNT; count++) {
            step++;

            words[base + step + LAST_KEY_WORD_INDEX] = ((words[base + (step & KEY_WORD_INDEX_MASK)] << KEY_ROTATION_LEFT_SHIFT) |
                                                        (words[base + ((step + 1) & KEY_WORD_INDEX_MASK)] >>> KEY_ROTATION_RIGHT_SHIFT)) & WORD_MASK;
            base += step & KEY_WORD_COUNT;
            step &= KEY_WORD_INDEX_MASK;
        }

        int[] subkeys = new int[SUBKEY_COUNT];

        System.arraycopy(words, 0, subkeys, 0, SUBKEY_COUNT);

        return subkeys;
    }

    private static int multiply(int left, int right) {
        if (left == 0) {
            return (1 - right) & WORD_MASK;
        }

        if (right == 0) {
            return (1 - left) & WORD_MASK;
        }

        int product = left * right;
        int low = product & WORD_MASK;
        int high = product >>> Short.SIZE;

        return (low - high + (low < high ? 1 : 0)) & WORD_MASK;
    }


    private CheatCodesUtils() {
    }
}
