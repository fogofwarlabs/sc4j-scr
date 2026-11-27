package org.fogofwarlabs.sc4j.scr.game.save.section;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.save.*;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Compares the computer-player section that SC4J writes and reads with the native saves made at the same frame of the replayed game.
 */
@RequiresInstallation
final class ComputerSectionTest {
    private static final int RECORD_COUNT = 1000;
    private static final int TOWN_COUNT = 100;
    private static final int SCRIPT_COUNT = 100;
    private static final int AREA_COUNT = 250;

    private static final int MILITARY_SIZE = 0x14;
    private static final int REGION_SIZE = SaveGameLayoutCheck.COMPUTER_REGION_ENTRY_SIZE;
    private static final int GUARD_SIZE = 0x20;
    private static final int WORKER_SIZE = 0x18;
    private static final int BUILDING_SIZE = 0x2C;
    private static final int TOWN_SIZE = 0x1CC;
    private static final int PLAYER_SIZE = 0x4E8;
    private static final int PLAYER_REQUESTS = 0x18;
    private static final int PLAYER_REQUEST_COUNT = 0x210;
    private static final int SCRIPT_SIZE = 0x34;
    private static final int AREA_SIZE = 0x30;
    private static final int UNIT_GENERATION = 0xA5;

    /**
     * One reference save and its replay simulated to the save frame.
     */
    private static final class Case implements AutoCloseable {
        /**
         * Creates a {@link Case} instance by simulating the selected replay to its save frame; the case is skipped when not selected or its files are
         * missing.
         *
         * @param saveName the reference save
         * @return the created {@link Case} instance
         * @throws Exception if the files cannot be read or the replay cannot be simulated to the save frame
         */
        static @NotNull Case open(@NotNull String saveName) throws Exception {
            SaveGameRoundTripScenario.assumeSelected(saveName);

            Path save = GameTestResources.getPath(saveName);
            Path replayPath = GameTestResources.getPath(GameTestResources.getSaveReplay(saveName));

            SaveGameLayoutCheck layout = SaveGameLayoutCheck.parse(Files.readAllBytes(save));
            int frame = SaveGameLayoutCheck.readGameFrame(layout.requirePart("game"));
            ReplayFile replay = ReplayFile.parse(Files.readAllBytes(replayPath));
            GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath());

            try {
                ReplayRuntime runtime = SaveGameRoundTripScenario.startReplay(gameData, replay);

                SaveGameRoundTripScenario.advanceToFrame(runtime, frame);

                return new Case(gameData, layout, frame, new SaveGameContext(runtime, null));
            } catch (Exception | Error failure) {
                gameData.close();

                throw failure;
            }
        }


        private final @NotNull GameData gameData;
        private final @NotNull SaveGameLayoutCheck layout;
        private final int frame;
        private final @NotNull SaveGameContext context;

        /**
         * Creates a {@link Case} instance for the supplied simulated game.
         *
         * @param gameData the installed game data
         * @param layout   the independently decoded file parts
         * @param frame    the frame to reach
         * @param context  the serialization context
         */
        private Case(@NotNull GameData gameData, @NotNull SaveGameLayoutCheck layout, int frame, @NotNull SaveGameContext context) {
            this.gameData = gameData;
            this.layout   = layout;
            this.frame    = frame;
            this.context  = context;
        }

        /**
         * @return the independently decoded parts of the reference save
         */
        @NotNull SaveGameLayoutCheck getLayout() {
            return this.layout;
        }

        /**
         * @return the save frame that the simulated game reached
         */
        int getFrame() {
            return this.frame;
        }

        /**
         * @return the serialization context of the simulated game
         */
        @NotNull SaveGameContext getContext() {
            return this.context;
        }


        /**
         * Closes this resource, relinquishing any underlying resources. This method is invoked automatically on objects managed by the
         * {@code try}-with-resources statement.
         *
         * @apiNote While this interface method is declared to throw {@code Exception}, implementers are <em>strongly</em> encouraged to declare
         *         concrete implementations of the {@code close} method to throw more specific exceptions, or to throw no exception at all if the close
         *         operation cannot fail.
         *
         *         <p>
         *         Cases where the close operation may fail require careful attention by implementers. It is strongly advised to relinquish the underlying
         *         resources and to internally <em>mark</em> the resource as closed, prior to throwing the exception. The {@code close} method is unlikely to
         *         be invoked more than once and so this ensures that the resources are released in a timely manner. Furthermore it reduces problems that
         *         could arise when the resource wraps, or is wrapped, by another resource.
         *
         *         <p>
         *         <em>Implementers of this interface are also strongly advised to not have the {@code close} method throw {@link InterruptedException}.</em>
         *         <p>
         *         This exception interacts with a thread's interrupted status, and runtime misbehavior is likely to occur if an {@code InterruptedException}
         *         is {@linkplain Throwable#addSuppressed suppressed}.
         *         <p>
         *         More generally, if it would cause problems for an exception to be suppressed, the {@code AutoCloseable.close} method should not throw it.
         *
         *         <p>
         *         Note that unlike the {@link java.io.Closeable#close close} method of {@link java.io.Closeable}, this {@code close} method is <em>not</em>
         *         required to be idempotent. In other words, calling this {@code close} method more than once may have some visible side effect, unlike
         *         {@code Closeable.close} which is required to have no effect if called more than once.
         *         <p>
         *         However, implementers of this interface are strongly encouraged to make their {@code close} methods idempotent.
         * @implSpec Closes the installed data owned by this reference case.
         */
        @Override
        public void close() {
            this.gameData.close();
        }
    }

    /**
     * Returns the computer-section names in recorded file order.
     *
     * @param layout the independently decoded file parts
     * @return the computer-section names in file order
     */
    private static @NotNull List<String> getComputerPartNames(@NotNull SaveGameLayoutCheck layout) {
        List<String> names = new ArrayList<>();

        for (String name : layout.getParts().keySet()) {
            if (name.startsWith("ai")) {
                names.add(name);
            }
        }

        return names;
    }

    /**
     * Reads the computer sections using the recorded part lengths.
     *
     * @param input  the serialized input
     * @param layout the independently decoded file parts
     * @return the decoded computer sections in file order
     */
    private static @NotNull Map<String, byte[]> readComputerParts(@NotNull SaveFileReader input, @NotNull SaveGameLayoutCheck layout) {
        Map<String, byte[]> parts = new LinkedHashMap<>();

        for (String name : getComputerPartNames(layout)) {
            byte[] part = layout.requirePart(name);

            parts.put(name, input.readSection(part.length));
        }

        return parts;
    }

    /**
     * Reports computer-section differences by independent record layout and field.
     *
     * @param ours   the simulated serialized sections
     * @param theirs the reference serialized sections
     * @param report the diagnostic output
     */
    private static void compareAll(@NotNull Map<String, byte[]> ours, @NotNull Map<String, byte[]> theirs, @NotNull StringBuilder report) {
        SaveGameComparisonCheck.compareBytes("aiRegionCount", ours.get("aiRegionCount"), theirs.get("aiRegionCount"), report);
        compareRecords("aiRegionPool",
                       ours, theirs,
                       MILITARY_SIZE, RECORD_COUNT,
                       SaveGameComparisonCheck.createFieldLabels(0x02, "next",
                                                                 0x06, "previous",
                                                                 0x0A, "kind",
                                                                 0x0B, "byte11",
                                                                 0x0C, "word12",
                                                                 0x0E, "unit",
                                                                 0x12, "region"),
                       report);

        Map<Integer, String> regionFields = SaveGameComparisonCheck.createFieldLabels(0x02, "region",
                                                                                      0x04, "linkedRegion",
                                                                                      0x06, "owner",
                                                                                      0x07, "state",
                                                                                      0x08, "stateTimer",
                                                                                      0x09, "cooldown",
                                                                                      0x0A, "flags",
                                                                                      0x0B, "retries",
                                                                                      0x0C, "militaryCount",
                                                                                      0x0E, "requiredGround",
                                                                                      0x10, "requiredAir",
                                                                                      0x12, "regionGround",
                                                                                      0x14, "regionAir",
                                                                                      0x16, "assignedGround",
                                                                                      0x18, "assignedAir",
                                                                                      0x1A, "enemyAir",
                                                                                      0x1C, "enemyGround",
                                                                                      0x1E, "airTarget",
                                                                                      0x22, "groundTarget",
                                                                                      0x26, "leader",
                                                                                      0x2A, "patrolUnit",
                                                                                      0x2E, "zero",
                                                                                      0x32, "firstMilitary");

        for (int owner = SaveGameLayoutCheck.COMPUTER_PLAYER_COUNT - 1; owner >= 0; owner--) {
            String name = "aiRegions" + owner;

            compareRecords(name, ours, theirs, REGION_SIZE, theirs.get(name).length / REGION_SIZE, regionFields, report);
        }

        compareRecords("aiCaptains",
                       ours, theirs,
                       GUARD_SIZE, RECORD_COUNT,
                       SaveGameComparisonCheck.createFieldLabels(0x02, "next",
                                                                 0x06, "previous",
                                                                 0x0A, "kind",
                                                                 0x0B, "retries",
                                                                 0x0C, "word12",
                                                                 0x0E, "unit",
                                                                 0x12, "type",
                                                                 0x14, "x",
                                                                 0x16, "y",
                                                                 0x18, "homeX",
                                                                 0x1A, "homeY",
                                                                 0x1C, "word2a",
                                                                 0x1E, "request"),
                       report);
        SaveGameComparisonCheck.compareBytes("aiCaptainHeads", ours.get("aiCaptainHeads"), theirs.get("aiCaptainHeads"), report);
        compareRecords("aiTasks",
                       ours, theirs,
                       WORKER_SIZE, RECORD_COUNT,
                       SaveGameComparisonCheck.createFieldLabels(0x02, "next",
                                                                 0x06, "previous",
                                                                 0x0A, "kind",
                                                                 0x0B, "assignment",
                                                                 0x0C, "state",
                                                                 0x0D, "delay",
                                                                 0x0E, "frame",
                                                                 0x12, "unit",
                                                                 0x16, "town"),
                       report);
        compareRecords("aiTaskData",
                       ours, theirs,
                       BUILDING_SIZE, RECORD_COUNT,
                       SaveGameComparisonCheck.createFieldLabels(0x02, "next",
                                                                 0x06, "previous",
                                                                 0x0A, "kind",
                                                                 0x0B, "queueKinds",
                                                                 0x10, "word16",
                                                                 0x12, "unit",
                                                                 0x16, "town",
                                                                 0x1A, "queueTargets"),
                       report);
        compareRecords("aiTaskData2",
                       ours, theirs,
                       TOWN_SIZE, TOWN_COUNT,
                       SaveGameComparisonCheck.createFieldLabels(0x02, "next",
                                                                 0x06, "previous",
                                                                 0x0A, "workerBase",
                                                                 0x0E, "firstWorker",
                                                                 0x12, "buildingBase",
                                                                 0x16, "firstBuilding",
                                                                 0x1A, "owner",
                                                                 0x1B, "buildingsLinked",
                                                                 0x1C, "workerCount",
                                                                 0x1D, "desiredWorkers",
                                                                 0x1E, "resourceArea",
                                                                 0x1F, "areaPending",
                                                                 0x20, "countdown",
                                                                 0x21, "byte37",
                                                                 0x22, "x",
                                                                 0x24, "y",
                                                                 0x26, "resourceDepot",
                                                                 0x2A, "reservedWorker",
                                                                 0x2E, "mineralTarget",
                                                                 0x32, "gasBuildings",
                                                                 0x3E, "requests"),
                       report);
        SaveGameComparisonCheck.compareBytes("aiTaskHeads", ours.get("aiTaskHeads"), theirs.get("aiTaskHeads"), report);
        compareRecords("aiPlayers",
                       ours, theirs,
                       PLAYER_SIZE, SaveGameLayoutCheck.COMPUTER_PLAYER_COUNT,
                       SaveGameComparisonCheck.createFieldLabels(0x002, "reservedMinerals",
                                                                 0x006, "reservedGas",
                                                                 0x00A, "reservedSupply",
                                                                 0x00E, "mineralBudget",
                                                                 0x012, "gasBudget",
                                                                 0x016, "supplyBudget",
                                                                 0x01A, "requests",
                                                                 0x212, "requestCount",
                                                                 0x213, "placementCountdown",
                                                                 0x214, "nukeRate",
                                                                 0x215, "attackPreparation",
                                                                 0x216, "lastNukeClock",
                                                                 0x21A, "flags",
                                                                 0x21C, "panicAddress",
                                                                 0x21E, "strengthLimit",
                                                                 0x220, "attackRegion",
                                                                 0x222, "pendingUnitRequest",
                                                                 0x224, "strengthScale",
                                                                 0x225, "defenseCountdown",
                                                                 0x226, "buildingHitClock",
                                                                 0x22A, "attackFrame",
                                                                 0x22E, "strengthAttackCountdown",
                                                                 0x22F, "spellCountdown",
                                                                 0x230, "attackSearchCountdown",
                                                                 0x231, "difficulty",
                                                                 0x232, "attackTypes",
                                                                 0x2B2, "defenseLists",
                                                                 0x3F2, "maximumCounts",
                                                                 0x4D6, "medic",
                                                                 0x4DA, "generalCommandRectangle"),
                       report);
        compareRecords("aiScripts",
                       ours, theirs,
                       SCRIPT_SIZE, SCRIPT_COUNT,
                       SaveGameComparisonCheck.createFieldLabels(0x02, "next",
                                                                 0x06, "previous",
                                                                 0x0A, "address",
                                                                 0x0E, "wait",
                                                                 0x12, "owner",
                                                                 0x16, "left",
                                                                 0x1A, "top",
                                                                 0x1E, "right",
                                                                 0x22, "bottom",
                                                                 0x26, "centerX",
                                                                 0x2A, "centerY",
                                                                 0x2E, "town",
                                                                 0x32, "flags"),
                       report);
        SaveGameComparisonCheck.compareBytes("aiScriptHeads", ours.get("aiScriptHeads"), theirs.get("aiScriptHeads"), report);
        compareRecords("aiTail",
                       ours, theirs,
                       AREA_SIZE, AREA_COUNT,
                       SaveGameComparisonCheck.createFieldLabels(0x02, "x",
                                                                 0x04, "y",
                                                                 0x06, "mineralCount",
                                                                 0x07, "gasCount",
                                                                 0x08, "startLocation",
                                                                 0x09, "occupancy",
                                                                 0x0A, "mineralTotal",
                                                                 0x0E, "gasTotal",
                                                                 0x12, "unknown10"),
                       report);
    }

    /**
     * Compares the first {@code count} records of a section by index and field, and the bytes after them (free heads, area count and countdown) as a
     * whole.
     *
     * @param name       the section name
     * @param ours       the simulated serialized sections
     * @param theirs     the reference serialized sections
     * @param recordSize the recorded record width
     * @param count      the number of records compared by field
     * @param fields     the field labels by record offset
     * @param report     the diagnostic output
     */
    private static void compareRecords(@NotNull String name,
                                       @NotNull Map<String, byte[]> ours, @NotNull Map<String, byte[]> theirs,
                                       int recordSize, int count,
                                       @NotNull Map<Integer, String> fields,
                                       @NotNull StringBuilder report) {
        byte[] ourSection = ours.get(name);
        byte[] theirSection = theirs.get(name);
        int recordsEnd = recordSize * count;

        Assertions.assertNotNull(ourSection);
        Assertions.assertNotNull(theirSection);

        SaveGameComparisonCheck.compareRecords(name,
                                               SaveGameComparisonCheck.addRecordIndexes(Arrays.copyOf(ourSection, recordsEnd), recordSize),
                                               SaveGameComparisonCheck.addRecordIndexes(Arrays.copyOf(theirSection, recordsEnd), recordSize),
                                               recordSize + Short.BYTES, Short.BYTES,
                                               fields,
                                               report);

        if (theirSection.length > recordsEnd) {
            SaveGameComparisonCheck.compareBytes(name + "Tail",
                                                 Arrays.copyOfRange(ourSection, recordsEnd, ourSection.length),
                                                 Arrays.copyOfRange(theirSection, recordsEnd, theirSection.length),
                                                 report);
        }
    }


    @ParameterizedTest
    @ValueSource(strings = {GameTestResources.SAVE_SINGLE_PLAYER_WITH_COMPUTER, GameTestResources.SAVE_LEVEL_COMPUTER,
                            GameTestResources.SAVE_MULTIPLAYER_WITH_COMPUTER, GameTestResources.SAVE_TOM_AND_JERRY})
    void write_reports_computer_section_differences_from_native_save(@NotNull String saveName) throws Exception {
        try (Case run = Case.open(saveName)) {
            SaveFileReader ours = SaveGameComparisonCheck.createSectionReader(new ComputerSection(), run.getContext());
            StringBuilder report = new StringBuilder("NATIVE COMPUTER " + saveName + " @" + run.getFrame());

            compareAll(readComputerParts(ours, run.getLayout()), run.getLayout().getParts(), report);
            System.out.println(report);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {GameTestResources.SAVE_SINGLE_PLAYER_WITH_COMPUTER, GameTestResources.SAVE_LEVEL_COMPUTER,
                            GameTestResources.SAVE_MULTIPLAYER_WITH_COMPUTER, GameTestResources.SAVE_TOM_AND_JERRY})
    void read_should_round_trip_native_computer_section(@NotNull String saveName) throws Exception {
        try (Case run = Case.open(saveName)) {
            SaveFileWriter computer = new SaveFileWriter();
            StringBuilder report = new StringBuilder("NATIVE COMPUTER ROUND TRIP " + saveName + " @" + run.getFrame());
            List<String> differing = new ArrayList<>();
            byte[] recordedUnits = run.getLayout().requirePart("units");
            ByteBuffer units = ByteBuffer.wrap(recordedUnits).order(ByteOrder.LITTLE_ENDIAN);
            UnitPool pool = run.getContext().getGame().getUnitSystem().getUnitPool();

            Assertions.assertNotNull(pool);

            for (int entry = 0; entry + SaveGameLayoutCheck.UNIT_ENTRY_SIZE <= units.capacity(); entry += SaveGameLayoutCheck.UNIT_ENTRY_SIZE) {
                pool.setGeneration(units.getInt(entry) + 1, Byte.toUnsignedInt(units.get(entry + Integer.BYTES + UNIT_GENERATION)));
            }

            for (String name : getComputerPartNames(run.getLayout())) {
                computer.writeSection(run.getLayout().requirePart(name));
            }

            new ComputerSection().read(run.getContext(), new SaveFileReader(computer.toByteArray(), 0));

            SaveFileReader ourReader = SaveGameComparisonCheck.createSectionReader(new ComputerSection(), run.getContext());
            Map<String, byte[]> ours = readComputerParts(ourReader, run.getLayout());
            Map<String, byte[]> expected = readComputerParts(new SaveFileReader(computer.toByteArray(), 0), run.getLayout());

            for (int owner = 0; owner < SaveGameLayoutCheck.COMPUTER_PLAYER_COUNT; owner++) {
                Arrays.fill(expected.get("aiPlayers"),
                            owner * PLAYER_SIZE + PLAYER_REQUESTS,
                            owner * PLAYER_SIZE + PLAYER_REQUEST_COUNT + 1,
                            (byte) 0);
            }

            compareAll(ours, expected, report);
            System.out.println(report);

            for (String name : getComputerPartNames(run.getLayout())) {
                if (!Arrays.equals(ours.get(name), expected.get(name))) {
                    differing.add(name);
                }
            }

            Assertions.assertEquals(List.of(), differing, "Sections that do not round trip");
        }
    }
}
