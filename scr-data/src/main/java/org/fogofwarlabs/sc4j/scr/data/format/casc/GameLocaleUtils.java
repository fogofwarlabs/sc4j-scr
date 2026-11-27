package org.fogofwarlabs.sc4j.scr.data.format.casc;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selects an installed locale for interface text and speech.
 * <p>
 * Selection tries the requested installed locale, the installed non-English text locale, then English. A run's text locale
 * ({@link #selectInstalled}) is the requested one when the installation lists it, English otherwise.
 */
public final class GameLocaleUtils {
    /**
     * The fallback locale.
     */
    public static final @NotNull String FALLBACK = "enUS";

    /**
     * The length of a game locale's language part ({@code ko} of {@code koKR}).
     */
    private static final int LANGUAGE_LENGTH = 2;

    private static final @NotNull Logger LOGGER = Logger.of(GameLocaleUtils.class);
    private static final @NotNull Pattern TEXT_TAG = Pattern.compile("([a-zA-Z]{4}) text\\?");
    private static final @NotNull Pattern LOCALE_FILE = Pattern.compile("(?i)^locales/([a-z]{4})/assets/rez/stat_txt\\.json$");

    /**
     * Returns the language and country of a game locale, for example Korean in Korea for {@code koKR}.
     *
     * @param locale the game locale, such as {@code koKR}
     * @return the locale of the language and country, or the root locale for an empty game locale
     */
    public static @NotNull Locale toLocale(@NotNull String locale) {
        if (locale.length() <= LANGUAGE_LENGTH) {
            return locale.isEmpty() ? Locale.ROOT : Locale.of(locale);
        }

        return Locale.of(locale.substring(0, LANGUAGE_LENGTH), locale.substring(LANGUAGE_LENGTH));
    }

    /**
     * @param storage the installed storage
     * @return the locales with installed interface text, in their storage spelling (for example {@code koKR})
     */
    public static @NotNull Set<String> getAvailableLocales(@NotNull CascStorage storage) {
        Set<String> locales = new TreeSet<>();

        for (String name : storage.getFileNames()) {
            Matcher matcher = LOCALE_FILE.matcher(name.replace('\\', '/'));

            if (matcher.matches() && storage.isLocallyAvailable(name)) {
                locales.add(matcher.group(1));
            }
        }

        return locales;
    }


    /**
     * Reads the text locales the installation lists in its {@code .build.info} tags.
     *
     * @param gameDirectory the installation directory holding {@code .build.info}
     * @return the text locales in their tag spelling (for example {@code koKR}), empty when the file cannot be read
     */
    public static @NotNull Set<String> readTextLocales(@NotNull Path gameDirectory) {
        Set<String> locales = new TreeSet<>();

        try {
            Matcher matcher = TEXT_TAG.matcher(Files.readString(gameDirectory.resolve(BuildInfo.FILE_NAME), StandardCharsets.UTF_8));

            while (matcher.find()) {
                locales.add(matcher.group(1));
            }
        } catch (IOException exception) {
            return Set.of();
        }

        return locales;
    }

    /**
     * Selects the text locale of a run: the requested locale when the installation lists it as a text locale, otherwise
     * {@value #FALLBACK}.
     *
     * @param requested     the requested locale such as {@code koKR} (any letter case)
     * @param gameDirectory the installation directory holding {@code .build.info}
     * @return the requested locale in the installation's spelling, or {@value #FALLBACK}
     */
    public static @NotNull String selectInstalled(@NotNull String requested, @NotNull Path gameDirectory) {
        for (String installed : readTextLocales(gameDirectory)) {
            if (installed.equalsIgnoreCase(requested)) {
                return installed;
            }
        }

        return FALLBACK;
    }

    /**
     * Reads the installed text locale from the {@code .build.info} tags.
     *
     * @param gameDirectory the installation directory holding {@code .build.info}
     * @return the installed text locale other than {@code enUS}, or {@code enUS}
     */
    public static @NotNull String readInstalledLocale(@NotNull Path gameDirectory) {
        try {
            Matcher matcher = TEXT_TAG.matcher(Files.readString(gameDirectory.resolve(BuildInfo.FILE_NAME), StandardCharsets.UTF_8));

            while (matcher.find()) {
                if (!matcher.group(1).equalsIgnoreCase(FALLBACK)) {
                    return matcher.group(1);
                }
            }
        } catch (IOException exception) {
            return FALLBACK;
        }

        return FALLBACK;
    }

    /**
     * Resolves the locale to use.
     *
     * @param requested     the requested locale such as {@code koKR} (any letter case), or {@code null} for the default
     * @param gameDirectory the installation directory holding {@code .build.info}
     * @param storage       the installed storage
     * @return an installed locale in its storage spelling, or {@code enUS}
     */
    public static @NotNull String select(@Nullable String requested, @NotNull Path gameDirectory, @NotNull CascStorage storage) {
        Set<String> available = getAvailableLocales(storage);

        if (requested != null) {
            for (String candidate : available) {
                if (candidate.toLowerCase(Locale.ROOT).equals(requested.toLowerCase(Locale.ROOT))) {
                    return candidate;
                }
            }

            LOGGER.warning("LOCALE: {0} is not installed (available {1}); using the default.", requested, available);
        }

        String installed = readInstalledLocale(gameDirectory);

        for (String candidate : available) {
            if (candidate.toLowerCase(Locale.ROOT).equals(installed.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }

        return FALLBACK;
    }


    private GameLocaleUtils() {
    }
}
