package org.fogofwarlabs.sc4j.scr.data.resource;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.fogofwarlabs.sc4j.scr.data.type.PortraitTypeTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Names the portrait that a mod ships in its jar ({@link ScrModFiles}) for an added unit type: the clips the console's portrait player plays, in
 * the format of the installed clips ({@code .webm}). As an installed portrait has two folders in {@code portdata.tbl}, the portrait has two clip
 * prefixes per quality, the idle clips and the talking clips; clip {@code n} is the prefix, {@code n} and {@code .webm}, and a missing clip number
 * plays the next lower one, as for installed clips. The idle clips are chosen by their chances in percent, as {@code portdata.dat} gives them.
 * The SD prefixes are required; a quality the mod does not give plays the SD clips.
 */
public final class ScrModPortrait {
    /**
     * The extension of a clip file.
     */
    public static final @NotNull String CLIP_EXTENSION = ".webm";

    private static final int PERCENT = 100;

    /**
     * Builds {@link ScrModPortrait} instances.
     */
    public static final class Builder {
        private final @NotNull ScrModFiles files;
        private final @NotNull String sdIdlePrefix;
        private final @NotNull String sdTalkingPrefix;

        private @Nullable String hd2IdlePrefix;
        private @Nullable String hd2TalkingPrefix;
        private @Nullable String hdIdlePrefix;
        private @Nullable String hdTalkingPrefix;
        private final int @NotNull [] idleChances;

        /**
         * Creates a {@link Builder} instance with the SD clips, no HD clips and every idle choice on the first idle clip.
         *
         * @param files           the files of the mod
         * @param sdIdlePrefix    the path prefix of the SD idle clips
         * @param sdTalkingPrefix the path prefix of the SD talking clips
         */
        public Builder(@NotNull ScrModFiles files, @NotNull String sdIdlePrefix, @NotNull String sdTalkingPrefix) {
            this.files           = files;
            this.sdIdlePrefix    = sdIdlePrefix;
            this.sdTalkingPrefix = sdTalkingPrefix;

            this.hd2IdlePrefix    = null;
            this.hd2TalkingPrefix = null;
            this.hdIdlePrefix     = null;
            this.hdTalkingPrefix  = null;
            this.idleChances      = new int[PortraitTypeTable.IDLE_CLIP_COUNT];

            this.idleChances[0] = PERCENT;
        }

        /**
         * Sets the HD2 clips.
         *
         * @param idlePrefix    the path prefix of the idle clips
         * @param talkingPrefix the path prefix of the talking clips
         * @return this instance for chaining
         */
        public @NotNull Builder setHd2(@NotNull String idlePrefix, @NotNull String talkingPrefix) {
            this.hd2IdlePrefix    = idlePrefix;
            this.hd2TalkingPrefix = talkingPrefix;

            return this;
        }

        /**
         * Sets the HD clips.
         *
         * @param idlePrefix    the path prefix of the idle clips
         * @param talkingPrefix the path prefix of the talking clips
         * @return this instance for chaining
         */
        public @NotNull Builder setHd(@NotNull String idlePrefix, @NotNull String talkingPrefix) {
            this.hdIdlePrefix    = idlePrefix;
            this.hdTalkingPrefix = talkingPrefix;

            return this;
        }

        /**
         * Sets the chances of the idle clips, in percent.
         *
         * @param chances the chance of each of the {@link PortraitTypeTable#IDLE_CLIP_COUNT} idle clips
         * @return this instance for chaining
         * @throws IllegalArgumentException if the number of chances is not {@link PortraitTypeTable#IDLE_CLIP_COUNT}, a chance is negative or their sum exceeds {@code 100}
         */
        public @NotNull Builder setIdleChances(int @NotNull ... chances) {
            int sum = 0;

            if (chances.length != this.idleChances.length) {
                throw Exceptions.newIllegalArgumentException("A portrait has {0} idle clip chances, not {1}.",
                                                             this.idleChances.length, chances.length);
            }

            for (int chance : chances) {
                if (chance < 0) {
                    throw Exceptions.newIllegalArgumentException("A portrait idle clip chance is negative: {0}", chance);
                }

                sum += chance;
            }

            if (sum > PERCENT) {
                throw Exceptions.newIllegalArgumentException("The portrait idle clip chances sum to {0}, above {1}.", sum, PERCENT);
            }

            System.arraycopy(chances, 0, this.idleChances, 0, chances.length);

            return this;
        }

        /**
         * Builds a {@link ScrModPortrait} instance with the current clips and chances.
         *
         * @return the built {@link ScrModPortrait} instance
         */
        public @NotNull ScrModPortrait build() {
            return new ScrModPortrait(this);
        }
    }

    private final @NotNull ScrModFiles files;
    private final @NotNull String sdIdlePrefix;
    private final @NotNull String sdTalkingPrefix;

    private final @Nullable String hd2IdlePrefix;
    private final @Nullable String hd2TalkingPrefix;
    private final @Nullable String hdIdlePrefix;
    private final @Nullable String hdTalkingPrefix;
    private final int @NotNull [] idleChances;

    private ScrModPortrait(@NotNull Builder builder) {
        this.files           = builder.files;
        this.sdIdlePrefix    = builder.sdIdlePrefix;
        this.sdTalkingPrefix = builder.sdTalkingPrefix;

        this.hd2IdlePrefix    = builder.hd2IdlePrefix;
        this.hd2TalkingPrefix = builder.hd2TalkingPrefix;
        this.hdIdlePrefix     = builder.hdIdlePrefix;
        this.hdTalkingPrefix  = builder.hdTalkingPrefix;
        this.idleChances      = builder.idleChances.clone();
    }

    /**
     * Returns the path prefix of the idle clips of a quality.
     *
     * @param quality the asset quality
     * @return the prefix of the quality, or the SD prefix if the mod gives no clips of the quality
     */
    public @NotNull String getIdlePrefix(AssetResolver.@NotNull AssetSet quality) {
        String prefix = switch (quality) {
            case SD -> this.sdIdlePrefix;
            case HD2 -> this.hd2IdlePrefix;
            case HD -> this.hdIdlePrefix;
        };

        return prefix == null ? this.sdIdlePrefix : prefix;
    }

    /**
     * Returns the path prefix of the talking clips of a quality.
     *
     * @param quality the asset quality
     * @return the prefix of the quality, or the SD prefix if the mod gives no clips of the quality
     */
    public @NotNull String getTalkingPrefix(AssetResolver.@NotNull AssetSet quality) {
        String prefix = switch (quality) {
            case SD -> this.sdTalkingPrefix;
            case HD2 -> this.hd2TalkingPrefix;
            case HD -> this.hdTalkingPrefix;
        };

        return prefix == null ? this.sdTalkingPrefix : prefix;
    }

    /**
     * Returns the chance of an idle clip.
     *
     * @param clip the idle clip, below {@link PortraitTypeTable#IDLE_CLIP_COUNT}
     * @return the chance in percent
     */
    public int getIdleChance(int clip) {
        return this.idleChances[clip];
    }


    /**
     * Reads a clip file.
     *
     * @param path the path of the clip: a prefix, the clip number and {@link #CLIP_EXTENSION}
     * @return the file contents
     * @throws IOException if the file cannot be read
     */
    public byte @NotNull [] readClip(@NotNull String path) throws IOException {
        return this.files.readFile(path);
    }
}
