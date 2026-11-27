package org.fogofwarlabs.sc4j.scr.data.format.casc;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves locally available assets by quality prefix, HD skin and locale.
 * <p>
 * Candidates try the quality prefix with its HD skin, the prefix alone, then the unprefixed path.
 * Localized resolution tries the requested locale, English, then unlocalized candidates.
 */
public final class AssetResolver {
    private static final @NotNull String ENGLISH = "enUS";
    private static final int MAXIMUM_CANDIDATES = 3;

    /**
     * The installed asset qualities.
     */
    public enum AssetSet {
        /**
         * Classic assets below {@code SD/}: one texture pixel per game pixel.
         */
        SD("SD/", 1),
        /**
         * Half-resolution remastered assets below {@code HD2/}: two texture pixels per game pixel.
         */
        HD2("HD2/", 2),
        /**
         * Full-resolution remastered assets at the storage root: four texture pixels per game pixel.
         */
        HD("", 4);

        /**
         * Returns the set with the given path prefix.
         *
         * @param prefix {@code "SD/"}, {@code "HD2/"} or {@code ""}
         * @return the set with that path prefix
         * @throws IllegalArgumentException if the prefix names no set
         */
        public static @NotNull AssetSet ofPrefix(@NotNull String prefix) {
            for (AssetSet set : values()) {
                if (set.prefix.equals(prefix)) {
                    return set;
                }
            }

            throw Exceptions.newIllegalArgumentException("Unknown asset quality prefix: {0}", prefix);
        }


        private final @NotNull String prefix;
        private final int scale;

        AssetSet(@NotNull String prefix, int scale) {
            this.prefix = prefix;
            this.scale  = scale;
        }

        /**
         * @return the path prefix, empty for the storage root
         */
        public @NotNull String getPrefix() {
            return this.prefix;
        }

        /**
         * @return the texture pixels per game pixel
         */
        public int getScale() {
            return this.scale;
        }

        /**
         * @return {@code true} if this is a remastered set ({@link #HD2} or {@link #HD}), {@code false} otherwise
         */
        public boolean isHd() {
            return this != SD;
        }
    }


    private final @NotNull CascStorage storage;
    private final @NotNull AssetSet set;
    private final @NotNull String skinFolder;
    private final @NotNull String locale;

    /**
     * Creates an {@link AssetResolver} instance.
     *
     * @param storage    the installed storage
     * @param set        the asset quality
     * @param skinFolder the HD skin folder with a trailing slash, such as {@code "carbot/"}, or empty; ignored for {@code SD}
     * @param locale     the locale, such as {@code koKR}
     */
    public AssetResolver(@NotNull CascStorage storage, @NotNull AssetSet set, @NotNull String skinFolder, @NotNull String locale) {
        this.storage    = storage;
        this.set        = set;
        this.skinFolder = set.isHd() ? skinFolder : "";
        this.locale     = locale;
    }

    /**
     * @return the asset quality
     */
    public @NotNull AssetSet getSet() {
        return this.set;
    }


    /**
     * Resolves an asset path to its first locally available candidate.
     *
     * @param path the asset path below the quality prefix; both slash directions are accepted
     * @return the first locally available candidate, or {@code null}
     */
    public @Nullable String resolve(@NotNull String path) {
        List<String> candidates = this.createCandidates(path.replace('\\', '/'), "");

        for (String candidate : candidates) {
            if (this.storage.isLocallyAvailable(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Reads the content of {@link #resolve(String)}.
     *
     * @param path the asset path below the quality prefix
     * @return the content of {@link #resolve(String)}
     * @throws IOException if no candidate exists or it cannot be read
     */
    public byte @NotNull [] read(@NotNull String path) throws IOException {
        String resolved = this.resolve(path);

        if (resolved == null) {
            throw Exceptions.newIOException("Missing asset: {0}", path);
        }

        return this.storage.readFile(resolved);
    }

    /**
     * Resolves a localized asset path to its first locally available candidate.
     *
     * @param path the asset path below the quality prefix; both slash directions are accepted
     * @return the first locally available candidate of the locale, of {@code enUS}, then unlocalized, or {@code null}
     */
    public @Nullable String resolveLocalized(@NotNull String path) {
        String normalized = path.replace('\\', '/');
        List<String> candidates = new ArrayList<>(this.createCandidates(normalized, "locales/" + this.locale + "/assets/"));

        if (!this.locale.equalsIgnoreCase(ENGLISH)) {
            candidates.addAll(this.createCandidates(normalized, "locales/" + ENGLISH + "/assets/"));
        }

        candidates.addAll(this.createCandidates(normalized, ""));

        for (String candidate : candidates) {
            if (this.storage.isLocallyAvailable(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Reads the content of {@link #resolveLocalized(String)}.
     *
     * @param path the asset path below the quality prefix
     * @return the content of {@link #resolveLocalized(String)}
     * @throws IOException if no candidate exists or it cannot be read
     */
    public byte @NotNull [] readLocalized(@NotNull String path) throws IOException {
        String resolved = this.resolveLocalized(path);

        if (resolved == null) {
            throw Exceptions.newIOException("Missing localized asset: {0}", path);
        }

        return this.storage.readFile(resolved);
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
     * @implSpec Returns the asset set, skin folder and lowercase locale.
     */
    @Override
    public @NotNull String toString() {
        return "AssetResolver[" + this.set + ", skin " + this.skinFolder + ", " + this.locale.toLowerCase(Locale.ROOT) + "]";
    }


    private @NotNull List<String> createCandidates(@NotNull String path, @NotNull String root) {
        List<String> candidates = new ArrayList<>(MAXIMUM_CANDIDATES);

        if (!this.skinFolder.isEmpty()) {
            candidates.add(root + this.set.prefix + this.skinFolder + path);
        }

        if (!this.set.prefix.isEmpty()) {
            candidates.add(root + this.set.prefix + path);
        }

        candidates.add(root + path);

        return candidates;
    }
}
