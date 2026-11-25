package org.fogofwarlabs.sc4j.scr.data.extension;

import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Extends the data reading of the SC:R mod. A mod built from the SC:R mod's sources adds an implementation with a public no-argument
 * constructor and names it in the resource {@code META-INF/services/org.fogofwarlabs.sc4j.scr.data.extension.ScrDataExtension} of its jar;
 * {@link ScrDataExtensionUtils} reads the named implementations. The SC:R mod itself names none, so its map reader keeps the native limits
 * and its map files the native extensions {@code .scm} and {@code .scx}.
 */
public interface ScrDataExtension {
    /**
     * Returns the largest {@code staredit\scenario.chk} size, in bytes, that the map reader accepts.
     *
     * @return the largest accepted scenario size, at least {@link MpqArchive#NATIVE_MAXIMUM_SCENARIO_SIZE}
     */
    default int getMaximumScenarioSize() {
        return MpqArchive.NATIVE_MAXIMUM_SCENARIO_SIZE;
    }

    /**
     * Returns the file name extensions of map archives besides the native {@code .scm} and {@code .scx}: a file with one of them is listed,
     * opened, hosted and transferred as a map, in the same MPQ format.
     *
     * @return the extensions, each a dot followed by letters or digits, empty by default
     */
    default @NotNull List<@NotNull String> getExtraMapFileExtensions() {
        return List.of();
    }
}
