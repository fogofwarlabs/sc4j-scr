package org.fogofwarlabs.sc4j.scr.game.golden;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.core.test.TestResourceUtils;
import org.fogofwarlabs.sc4j.scr.core.test.TestResultUtils;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkSection;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

/**
 * Loads every map below the folder {@code maps} ({@code <source>/<name>.scm|.scx|.chk}) and the Blizzard maps that tests read from the installation
 * ({@code Maps} of {@link ExternalInput#INSTALLATION}), and checks what is true of each: the scenario facts that the file itself states, and a game
 * started on it that runs {@value MapDetectionCheck#FRAMES} frames without an unimplemented operation or an impossible end
 * ({@link MapDetectionCheck}).
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class MapDetectionTest {
    private static final @NotNull String FOLDER = "maps";
    private static final @NotNull String KNOWN_PROBLEMS = FOLDER + "/known-problems.tsv";
    private static final @NotNull String MELEE_ARCHIVE_EXTENSION = ".scm";
    private static final @NotNull String EXPANSION_ARCHIVE_EXTENSION = ".scx";
    private static final @NotNull String RAW_SCENARIO_EXTENSION = ".chk";
    private static final @NotNull String INSTALLATION_SOURCE = "blizzard";
    private static final @NotNull String LADDER_SOURCE = "ladder";
    private static final @NotNull List<String> INSTALLATION_MAP_FILES = List.of("(2)Bottleneck.scm", "(4)Cyclone.scm", "(8)The Hunters.scm");
    private static final @NotNull String SCENARIO_ENTRY = "staredit\\scenario.chk";
    private static final @NotNull String DIMENSIONS_SECTION = "DIM ";
    private static final @NotNull String TILESET_SECTION = "ERA ";
    private static final @NotNull String RESULTS_FILE = "map-detection.tsv";
    private static final @NotNull String KEY_COLUMN = "map";
    private static final @NotNull List<String> SCENARIO_COLUMNS = List.of("chk.size", "chk.sections", "chk.width", "chk.height", "chk.tileset",
                                                                          "chk.strict");
    private static final char PATH_SEPARATOR = '/';
    private static final char EXTENSION_SEPARATOR = '.';
    private static final @NotNull Duration CASE_TIMEOUT = Duration.ofMinutes(5);
    private static final int SECTION_NAME_SIZE = 4;
    private static final int SECTION_HEADER_SIZE = 8;
    private static final int HEIGHT_OFFSET = 2;
    private static final int TILESET_MASK = 0x7;
    private static final int FIRST_PRINTABLE_NAME_CHARACTER = 0x20;
    private static final int LAST_PRINTABLE_NAME_CHARACTER = 0x7E;

    /**
     * The section headers of a scenario, walked as the file states them.
     */
    private static final class SectionWalk {
        private final @NotNull List<String> sections;
        private final int dimensionsOffset;
        private final int tilesetOffset;
        private final boolean strictlyValid;

        private SectionWalk(@NotNull List<String> sections, int dimensionsOffset, int tilesetOffset, boolean strictlyValid) {
            this.sections         = sections;
            this.dimensionsOffset = dimensionsOffset;
            this.tilesetOffset    = tilesetOffset;
            this.strictlyValid    = strictlyValid;
        }

        /**
         * @return every walked section as {@code <name>@<offset>+<size>}, marked when its payload is incomplete
         */
        private @NotNull List<String> getSections() {
            return this.sections;
        }

        /**
         * @return the payload offset of the last complete {@code DIM } section, or {@code -1} if there is none
         */
        private int getDimensionsOffset() {
            return this.dimensionsOffset;
        }

        /**
         * @return the payload offset of the last complete {@code ERA } section, or {@code -1} if there is none
         */
        private int getTilesetOffset() {
            return this.tilesetOffset;
        }

        /**
         * @return {@code true} if every section has a printable name and a complete payload and the last one ends at the end of the scenario, {@code false} otherwise
         */
        private boolean isStrictlyValid() {
            return this.strictlyValid;
        }
    }

    private static @NotNull String describeSection(@NotNull String name, int offset, int size, boolean complete) {
        return name + "@" + offset + "+" + size + (complete ? "" : " incomplete");
    }

    /**
     * Walks the section headers from offset {@code 0}: each header names a section and its size, and the next header follows the payload. The walk
     * stops at a header that does not fit into the scenario, a revisited header, or a payload that ends before the scenario or leaves no room for
     * another header.
     *
     * @param scenario the scenario
     * @return the walked sections and the facts they state
     */
    private static @NotNull SectionWalk walkSections(byte @NotNull [] scenario) {
        ByteBuffer buffer = ByteBuffer.wrap(scenario).order(ByteOrder.LITTLE_ENDIAN);
        List<String> sections = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        int dimensionsOffset = -1;
        int tilesetOffset = -1;
        boolean strictlyValid = true;
        long lastEnd = 0L;
        int offset = 0;

        while (offset <= scenario.length - SECTION_HEADER_SIZE && visited.add(offset)) {
            String name = new String(scenario, offset, SECTION_NAME_SIZE, StandardCharsets.ISO_8859_1);
            int size = buffer.getInt(offset + SECTION_NAME_SIZE);
            long end = offset + SECTION_HEADER_SIZE + (long) size;
            boolean complete = size >= 0 && end <= scenario.length;

            sections.add(describeSection(name, offset, size, complete));
            strictlyValid &= complete && name.chars().allMatch(character -> character >= FIRST_PRINTABLE_NAME_CHARACTER &&
                                                                            character <= LAST_PRINTABLE_NAME_CHARACTER);
            lastEnd = end;

            if (complete && DIMENSIONS_SECTION.equals(name)) {
                dimensionsOffset = offset + SECTION_HEADER_SIZE;
            }

            if (complete && TILESET_SECTION.equals(name)) {
                tilesetOffset = offset + SECTION_HEADER_SIZE;
            }

            if (end < 0 || end + SECTION_HEADER_SIZE > scenario.length) {
                break;
            }

            offset = (int) end;
        }

        return new SectionWalk(sections, dimensionsOffset, tilesetOffset, strictlyValid && lastEnd == scenario.length);
    }

    private static boolean isStrictlyAccepted(byte @NotNull [] scenario) {
        try {
            ChkFile.parse(scenario);

            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void detect(@NotNull InstallationTestResources resources,
                               @NotNull String key,
                               @NotNull String name,
                               @NotNull Path map,
                               boolean useMapSettings,
                               @NotNull KnownProblemsAssert known,
                               @NotNull Path results) throws Exception {
        boolean rawScenario = map.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(RAW_SCENARIO_EXTENSION);
        byte[] scenario = rawScenario ? Files.readAllBytes(map) : MpqArchive.open(map).readFile(SCENARIO_ENTRY);
        ByteBuffer buffer = ByteBuffer.wrap(scenario).order(ByteOrder.LITTLE_ENDIAN);
        ChkFile chk = ChkFile.parseTolerant(scenario);
        SectionWalk walk = walkSections(scenario);
        List<String> parsedSections = new ArrayList<>();
        boolean strictlyAccepted = isStrictlyAccepted(scenario);

        for (ChkSection section : chk.getSections()) {
            parsedSections.add(describeSection(section.getName(), section.getHeaderOffset(), section.getDeclaredSize(),
                                               section.hasCompletePayload()));
        }

        MapDetectionCheck check = Assertions.assertTimeoutPreemptively(
                CASE_TIMEOUT,
                () -> MapDetectionCheck.run(resources, map, rawScenario, scenario, chk, useMapSettings),
                name + " did not run " + MapDetectionCheck.FRAMES + " frames within " + CASE_TIMEOUT.toSeconds() + " seconds."
        );
        List<String> report = new ArrayList<>(List.of(Integer.toString(scenario.length), Integer.toString(walk.getSections().size()),
                                                      Integer.toString(chk.getWidth()), Integer.toString(chk.getHeight()),
                                                      Integer.toString(chk.getTileset()), Boolean.toString(strictlyAccepted)));

        report.addAll(check.describeReport());
        TestResultUtils.appendResultLine(results, key, report);

        Assertions.assertAll(
                () -> Assertions.assertEquals(walk.getSections(), parsedSections, name + ": the parsed sections differ from the section headers."),
                () -> Assertions.assertTrue(walk.getDimensionsOffset() >= 0 && walk.getTilesetOffset() >= 0,
                                            name + " has no complete DIM or ERA section."),
                () -> Assertions.assertEquals(Short.toUnsignedInt(buffer.getShort(walk.getDimensionsOffset())), chk.getWidth(), name + ": width"),
                () -> Assertions.assertEquals(Short.toUnsignedInt(buffer.getShort(walk.getDimensionsOffset() + HEIGHT_OFFSET)), chk.getHeight(),
                                              name + ": height"),
                () -> Assertions.assertEquals(buffer.getShort(walk.getTilesetOffset()) & TILESET_MASK, chk.getTileset(), name + ": tileset"),
                () -> Assertions.assertEquals(walk.isStrictlyValid(), strictlyAccepted, name + ": strict parse result"),
                () -> known.assertClassAndSignals(key, name, check.getClassification().getName(), check.describeSignals(), check.describe())
        );
    }


    /**
     * Creates one test per map.
     * <p>
     * The scenario facts come from a plain walk of the scenario's section headers, each followed by its declared size: the name, header offset,
     * declared size and completeness of every section in walk order; the dimensions of the last {@code DIM } section and the tileset of the last
     * {@code ERA } section; and the strict-parse result, accepted exactly when every section has a printable name and a complete payload and the last
     * one ends at the end of the scenario. The parsed scenario ({@link ChkFile}) must state the same facts.
     * <p>
     * The Blizzard maps of the installation and the ladder maps ({@code ladder}) start as melee games, the other repository maps ({@code user},
     * {@code internal}, {@code archive}) as Use Map Settings games. A map whose run is not {@code ok} must be on the known problem list
     * {@value #KNOWN_PROBLEMS} with its class and signals, which are native-confirmed outcomes such as an EUD end. The test of a map is named
     * {@code maps/<source>/<name>}, an installation map {@code maps/blizzard/<name>}; the list key is the name. Every map appends one line to
     * {@code <results>/map-detection.tsv}.
     *
     * @param resources the installed game resources
     * @return one test per map
     * @throws IOException if an installation map has the name of a repository map
     */
    @TestFactory
    @NotNull Stream<DynamicTest> map_detection(@NotNull InstallationTestResources resources) throws IOException {
        Path folder = TestResourceUtils.getPath(FOLDER);
        Path installationMaps = resources.getGamePath().resolve(ExternalInput.MAPS_FOLDER);
        KnownProblemsAssert known = KnownProblemsAssert.read(KNOWN_PROBLEMS);
        Map<String, Path> maps = TestResourceUtils.listFilesByName(folder,
                                                                   MELEE_ARCHIVE_EXTENSION, EXPANSION_ARCHIVE_EXTENSION, RAW_SCENARIO_EXTENSION);
        Set<String> keys = new HashSet<>(maps.keySet());
        List<String> columns = new ArrayList<>(SCENARIO_COLUMNS);
        List<DynamicTest> tests = new ArrayList<>();

        columns.addAll(MapDetectionCheck.REPORT_COLUMNS);

        Path results = TestResultUtils.createResultTable(RESULTS_FILE, KEY_COLUMN, columns);

        for (String fileName : INSTALLATION_MAP_FILES) {
            Path map = installationMaps.resolve(fileName);
            String key = fileName.substring(0, fileName.lastIndexOf(EXTENSION_SEPARATOR));
            String name = FOLDER + PATH_SEPARATOR + INSTALLATION_SOURCE + PATH_SEPARATOR + key;

            if (!keys.add(key)) {
                throw Exceptions.newIOException("The name {0} is used twice in {1}.", key, FOLDER);
            }

            tests.add(DynamicTest.dynamicTest(name, map.toUri(), () -> detect(resources, key, name, map, false, known, results)));
        }

        known.assertListedKeys(keys);

        for (Map.Entry<String, Path> entry : maps.entrySet()) {
            String relative = folder.relativize(entry.getValue()).toString().replace(File.separatorChar, PATH_SEPARATOR);
            String name = FOLDER + PATH_SEPARATOR + relative.substring(0, relative.lastIndexOf(EXTENSION_SEPARATOR));
            boolean useMapSettings = !LADDER_SOURCE.equals(relative.substring(0, relative.indexOf(PATH_SEPARATOR)));

            tests.add(DynamicTest.dynamicTest(name, entry.getValue().toUri(),
                                              () -> detect(resources, entry.getKey(), name, entry.getValue(), useMapSettings, known, results)));
        }

        return tests.stream();
    }
}
