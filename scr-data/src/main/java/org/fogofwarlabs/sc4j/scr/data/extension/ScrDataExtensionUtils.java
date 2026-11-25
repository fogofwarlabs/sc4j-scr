package org.fogofwarlabs.sc4j.scr.data.extension;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

/**
 * Reads the {@link ScrDataExtension} implementations that the jar of the running mod names. They are found once per class loader, with the
 * class loader of {@link ScrDataExtension}, so a mod jar in its own class loader finds its own implementations.
 */
public final class ScrDataExtensionUtils {
    private static final @NotNull List<@NotNull ScrDataExtension> EXTENSIONS = ServiceLoader.load(ScrDataExtension.class, ScrDataExtension.class.getClassLoader())
                                                                                            .stream()
                                                                                            .map(ServiceLoader.Provider::get)
                                                                                            .toList();
    /**
     * The form of a map file extension in lower case.
     */
    private static final @NotNull Pattern MAP_FILE_EXTENSION = Pattern.compile("\\.[a-z0-9]+");

    private static final int MAXIMUM_SCENARIO_SIZE = findMaximumScenarioSize(EXTENSIONS);
    private static final @NotNull List<@NotNull String> EXTRA_MAP_FILE_EXTENSIONS = collectExtraMapFileExtensions(EXTENSIONS);

    /**
     * Returns the largest {@code staredit\scenario.chk} size that the map reader accepts: the largest one an extension returns
     * ({@link ScrDataExtension#getMaximumScenarioSize}), or {@link MpqArchive#NATIVE_MAXIMUM_SCENARIO_SIZE} without an extension.
     *
     * @return the largest accepted scenario size in bytes
     */
    public static int getMaximumScenarioSize() {
        return MAXIMUM_SCENARIO_SIZE;
    }

    /**
     * Returns the map file extensions that the extensions add to the native {@code .scm} and {@code .scx}
     * ({@link ScrDataExtension#getExtraMapFileExtensions}).
     *
     * @return the extensions in lower case with the dot, each once, in the order of the extensions; empty without an extension
     */
    public static @NotNull List<@NotNull String> getExtraMapFileExtensions() {
        return EXTRA_MAP_FILE_EXTENSIONS;
    }

    /**
     * Finds the largest scenario size that extensions accept.
     *
     * @param extensions the extensions
     * @return the largest size an extension returns, at least {@link MpqArchive#NATIVE_MAXIMUM_SCENARIO_SIZE}
     */
    static int findMaximumScenarioSize(@NotNull List<@NotNull ScrDataExtension> extensions) {
        int maximumSize = MpqArchive.NATIVE_MAXIMUM_SCENARIO_SIZE;

        for (ScrDataExtension extension : extensions) {
            maximumSize = Math.max(maximumSize, extension.getMaximumScenarioSize());
        }

        return maximumSize;
    }

    /**
     * Collects the extra map file extensions of extensions.
     *
     * @param extensions the extensions
     * @return the map file extensions in lower case, each once, in the order of the extensions
     * @throws IllegalArgumentException if a map file extension is not a dot followed by letters or digits
     */
    static @NotNull List<@NotNull String> collectExtraMapFileExtensions(@NotNull List<@NotNull ScrDataExtension> extensions) {
        List<String> fileExtensions = new ArrayList<>();

        for (ScrDataExtension extension : extensions) {
            for (String fileExtension : extension.getExtraMapFileExtensions()) {
                String lowerCase = fileExtension.toLowerCase(Locale.ROOT);

                if (!MAP_FILE_EXTENSION.matcher(lowerCase).matches()) {
                    throw Exceptions.newIllegalArgumentException("The map file extension {0} is not a dot followed by letters or digits.",
                                                                 fileExtension);
                }

                if (!fileExtensions.contains(lowerCase)) {
                    fileExtensions.add(lowerCase);
                }
            }
        }

        return List.copyOf(fileExtensions);
    }

    private ScrDataExtensionUtils() {
    }
}
