package org.fogofwarlabs.sc4j.scr.game.golden;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.core.test.TestResourceUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Checks the class of folder-driven cases against a known problem list: a case that is not listed must be {@code ok}, a listed case must keep its
 * listed class.
 * <p>
 * The list is a UTF-8 resource with one {@code <key> TAB <class> TAB <signals> TAB <note>} line per case that is not {@code ok}; lines starting with
 * {@code #} and blank lines are ignored. A line without a class or with a repeated key fails the read. The failure messages name the list.
 */
final class KnownProblemsAssert {
    private static final @NotNull String OK = "ok";
    private static final @NotNull String NO_SIGNALS = "none";
    private static final @NotNull String COMMENT_PREFIX = "#";
    private static final @NotNull String COLUMN_SEPARATOR = "\t";
    private static final int KEY_COLUMN = 0;
    private static final int CLASS_COLUMN = 1;
    private static final int SIGNALS_COLUMN = 2;

    /**
     * Creates a {@link KnownProblemsAssert} instance that reads a known problem list of the test resources.
     *
     * @param resource the list's resource path, such as {@code replays/detection/known-problems.tsv}
     * @return the created {@link KnownProblemsAssert} instance
     * @throws IOException if the list cannot be read or holds an invalid or repeated line
     */
    static @NotNull KnownProblemsAssert read(@NotNull String resource) throws IOException {
        Map<String, String[]> lines = new LinkedHashMap<>();

        for (String line : Files.readAllLines(TestResourceUtils.getPath(resource), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith(COMMENT_PREFIX)) {
                continue;
            }

            String[] columns = line.split(COLUMN_SEPARATOR, -1);

            if (columns.length <= CLASS_COLUMN || lines.put(columns[KEY_COLUMN], columns) != null) {
                throw Exceptions.newIOException("Invalid or repeated known problem line in {0}: {1}", resource, line);
            }
        }

        return new KnownProblemsAssert(resource, lines);
    }


    private final @NotNull String resource;
    private final @NotNull Map<String, String[]> lines;

    private KnownProblemsAssert(@NotNull String resource, @NotNull Map<String, String[]> lines) {
        this.resource = resource;
        this.lines    = lines;
    }

    /**
     * @param key the case key in the list
     * @return the listed signals, {@code none} if the case is not listed or its line has no signals
     */
    @NotNull String getListedSignals(@NotNull String key) {
        String[] listed = this.lines.get(key);

        return listed == null || listed.length <= SIGNALS_COLUMN ? NO_SIGNALS : listed[SIGNALS_COLUMN];
    }


    /**
     * Checks that every listed key names a case.
     *
     * @param keys the keys of the cases
     * @throws IOException if the list names a key without a case
     */
    void assertListedKeys(@NotNull Collection<String> keys) throws IOException {
        List<String> missing = new ArrayList<>(this.lines.keySet());

        missing.removeAll(keys);

        if (!missing.isEmpty()) {
            throw Exceptions.newIOException("The known problem list {0} names {1}, which have no case.", this.resource, missing);
        }
    }

    /**
     * Checks the class of a case.
     *
     * @param key         the case key in the list
     * @param name        the case name for messages, such as {@code detection/CWAL_00000001}
     * @param actualClass the class the case shows: {@code ok}, {@code suspicious} or {@code error}
     * @param description the description of the outcome for messages
     */
    void assertClass(@NotNull String key, @NotNull String name, @NotNull String actualClass, @NotNull String description) {
        String[] listed = this.lines.get(key);

        if (listed == null) {
            Assertions.assertEquals(OK, actualClass, name + " is " + description + " but not on the known problem list " + this.resource + ".");

            return;
        }

        Assertions.assertEquals(listed[CLASS_COLUMN], actualClass, name + " is listed as " + listed[CLASS_COLUMN] + " in " + this.resource +
                                                                   " but is " + description + "; update or remove its entry.");
    }

    /**
     * Checks the class and the signals of a case whose listed outcome is native-confirmed, such as a map's EUD end.
     *
     * @param key           the case key in the list
     * @param name          the case name for messages, such as {@code maps/user/zergling-defense}
     * @param actualClass   the class the case shows: {@code ok}, {@code suspicious} or {@code error}
     * @param actualSignals the signals the case shows, {@code none} without a signal
     * @param description   the description of the outcome for messages
     */
    void assertClassAndSignals(@NotNull String key, @NotNull String name,
                               @NotNull String actualClass, @NotNull String actualSignals,
                               @NotNull String description) {
        String listedSignals = this.getListedSignals(key);

        this.assertClass(key, name, actualClass, description);
        Assertions.assertEquals(listedSignals, actualSignals, name + " shows the signals " + actualSignals + " but " + this.resource + " lists " +
                                                              listedSignals + "; fix SC4J or update the entry with native evidence.");
    }
}
