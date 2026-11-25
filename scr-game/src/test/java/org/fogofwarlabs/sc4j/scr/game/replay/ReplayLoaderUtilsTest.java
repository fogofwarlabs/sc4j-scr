package org.fogofwarlabs.sc4j.scr.game.replay;

import org.fogofwarlabs.sc4j.scr.core.test.CorpusTest;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UpgradesDat;
import org.fogofwarlabs.sc4j.scr.data.format.replay.*;
import org.fogofwarlabs.sc4j.scr.game.map.StartingUnitLoader;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerUpgrades;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.state.GameLoader;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.ReplayFileScenario;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitHandle;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tests the replay inspection and initialized replay worlds of {@link ReplayLoaderUtils} against replays and maps of a real installation.
 */
@RequiresInstallation
final class ReplayLoaderUtilsTest {
    private static final int INSPECTION_INSTRUCTION_LIMIT = 4096;

    /**
     * The header fields the fixture replay writes beside the ones {@link ReplayHeaderBuilder} names.
     */
    private static final int MAP_WIDTH_OFFSET = 0x34;
    private static final int MAP_HEIGHT_OFFSET = 0x36;
    private static final int VICTORY_CONDITION_OFFSET = 0x89;
    private static final int RESOURCE_TYPE_OFFSET = 0x8A;
    private static final int INITIAL_UNITS_OFFSET = 0x8D;
    private static final int TOURNAMENT_MODE_OFFSET = 0x93;
    private static final int STARTING_MINERALS_OFFSET = 0x98;
    private static final int STARTING_GAS_OFFSET = 0x9C;
    private static final int FIXTURE_STARTING_GAS = 0x12345678;

    /**
     * The fixture's human and computer slots, whose player records hold each other's index, and a slot that has a name but no player.
     */
    private static final int HUMAN_SLOT = 0;
    private static final int COMPUTER_SLOT = 7;
    private static final int NAMED_EMPTY_SLOT = 3;
    private static final int FIXTURE_RACE = 1;
    /**
     * The player types of a human, a computer and an empty slot.
     */
    private static final int HUMAN_TYPE = 2;
    private static final int COMPUTER_TYPE = 1;
    private static final int NO_PLAYER_TYPE = 0;

    /**
     * The width and height of the fixture map in tiles.
     */
    private static final int FIXTURE_MAP_SIZE = 64;
    /**
     * The DIM, ERA, MTXM and UNIT sections of the fixture map with its four start locations.
     */
    private static final int FIXTURE_MAP_BASE_SIZE = 8374;
    private static final int TILE_DATA_SIZE = FIXTURE_MAP_SIZE * FIXTURE_MAP_SIZE * Short.BYTES;

    /**
     * The layout of a UNIT section record.
     */
    private static final int UNIT_RECORD_SIZE = 36;
    private static final int UNIT_HORIZONTAL_OFFSET = 4;
    private static final int UNIT_VERTICAL_OFFSET = 6;
    private static final int UNIT_TYPE_OFFSET = 8;
    private static final int UNIT_OWNER_OFFSET = 16;
    private static final int START_LOCATION_VERTICAL_POSITION = 512;

    private static final int VERSION_TAG = 0x20524556;
    private static final int EXPANSION_UPGRADES_TAG = 0x78505550;

    /**
     * The size of the expansion upgrade section's data for one upgrade.
     */
    private static final int EXPANSION_UPGRADE_RECORD_SIZE = 38;
    private static final int EXPANSION_UPGRADES_SIZE = UpgradesDat.UPGRADE_COUNT * EXPANSION_UPGRADE_RECORD_SIZE;
    /**
     * The offset of the final version WORD in two consecutive version sections.
     */
    private static final int FINAL_VERSION_OFFSET = 18;
    private static final int INVALID_VERSION = 0xFFFF;

    /**
     * The starting minerals of the fixture players.
     */
    private static final int FIXTURE_STARTING_MINERALS = 50;
    /**
     * The random source drawn from a disabled random state.
     */
    private static final int DRAWN_RANDOM_SOURCE = 14;
    /**
     * The units the replay's map and melee startup allocate.
     */
    private static final int STARTING_UNIT_COUNT = 135;
    /**
     * The frame of the first Select command of {@link GameTestResources#REPLAY_ZVP_FIGHTING_SPIRIT}.
     */
    private static final int FIRST_SELECTION_FRAME = 6;

    /**
     * The most corpus replays the corpus tests read, and the replay file name extension.
     */
    private static final int CORPUS_REPLAY_LIMIT = 200;
    private static final @NotNull String REPLAY_EXTENSION = ".rep";

    private static @Nullable GameData gameData;

    /**
     * Loads the installed game resources.
     *
     * @throws IOException if the installed game resources cannot be read
     */
    @BeforeAll
    static void loadGameData() throws IOException {
        gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath());
    }

    /**
     * Closes the installed game resources when they were loaded.
     */
    @AfterAll
    static void closeGameData() {
        if (gameData != null) {
            gameData.close();
        }
    }

    /**
     * Creates a {@link Game} instance from the supplied header and map fixture inputs.
     *
     * @param initialUnits       the starting-unit option input
     * @param victoryCondition   the victory option input
     * @param engine             the expansion engine input
     * @param resourceType       the resource option input
     * @param startingMinerals   the initial mineral input
     * @param preplacedUnits     the raw preplaced-unit section bytes
     * @param additionalSections the ordered extra map sections
     * @return the created {@link Game} instance, loaded from the encoded header and map for inspection
     * @throws IOException if the encoded replay or map cannot be parsed
     */
    private static @NotNull Game createFixtureInspectionGame(int initialUnits,
                                                             int victoryCondition,
                                                             int engine,
                                                             int resourceType, int startingMinerals,
                                                             byte @NotNull [] preplacedUnits,
                                                             byte @NotNull [] additionalSections) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(ReplayHeaderBuilder.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        header.put(ReplayHeaderBuilder.ENGINE_OFFSET, (byte) engine);
        header.putShort(MAP_WIDTH_OFFSET, (short) FIXTURE_MAP_SIZE);
        header.putShort(MAP_HEIGHT_OFFSET, (short) FIXTURE_MAP_SIZE);
        header.put(VICTORY_CONDITION_OFFSET, (byte) victoryCondition);
        header.put(RESOURCE_TYPE_OFFSET, (byte) resourceType);
        header.put(INITIAL_UNITS_OFFSET, (byte) initialUnits);
        header.put(TOURNAMENT_MODE_OFFSET, (byte) 0);
        header.putInt(STARTING_MINERALS_OFFSET, startingMinerals);
        header.putInt(STARTING_GAS_OFFSET, FIXTURE_STARTING_GAS);
        header.put(ReplayHeaderBuilder.MELEE_UNIT_FLAGS_OFFSET, (byte) 0);

        for (int playerSlot = 0; playerSlot < ReplayHeaderBuilder.PLAYER_COUNT; playerSlot++) {
            int playerOffset = ReplayHeaderBuilder.PLAYERS_OFFSET + playerSlot * ReplayHeaderBuilder.PLAYER_RECORD_SIZE;

            header.putShort(playerOffset, (short) (playerSlot == HUMAN_SLOT ? COMPUTER_SLOT : playerSlot == COMPUTER_SLOT ? HUMAN_SLOT : playerSlot));

            if (playerSlot == HUMAN_SLOT || playerSlot == COMPUTER_SLOT) {
                int playerType = playerSlot == HUMAN_SLOT ? PlayerTypes.HUMAN : PlayerTypes.COMPUTER;

                header.put(playerOffset + ReplayHeaderBuilder.PLAYER_ID_OFFSET, (byte) playerSlot);
                header.put(playerOffset + ReplayHeaderBuilder.PLAYER_TYPE_OFFSET, (byte) playerType);
                header.put(playerOffset + ReplayHeaderBuilder.PLAYER_RACE_OFFSET, (byte) FIXTURE_RACE);
            }

            if (playerSlot == NAMED_EMPTY_SLOT || playerSlot == COMPUTER_SLOT) {
                header.put(playerOffset + ReplayHeaderBuilder.PLAYER_NAME_OFFSET, (byte) 'P');
            }
        }

        ByteBuffer mapData = ByteBuffer.allocate(FIXTURE_MAP_BASE_SIZE + preplacedUnits.length + additionalSections.length)
                                       .order(ByteOrder.LITTLE_ENDIAN);
        int[] startLocationOwners = {HUMAN_SLOT, HUMAN_SLOT, NAMED_EMPTY_SLOT, COMPUTER_SLOT};
        int[] startLocationHorizontalPositions = {128, 512, 1024, 1536};

        mapData.put("DIM ".getBytes(StandardCharsets.US_ASCII)).putInt(2 * Short.BYTES);
        mapData.putShort((short) FIXTURE_MAP_SIZE).putShort((short) FIXTURE_MAP_SIZE);
        mapData.put("ERA ".getBytes(StandardCharsets.US_ASCII)).putInt(Short.BYTES).putShort((short) 0);
        mapData.put("MTXM".getBytes(StandardCharsets.US_ASCII)).putInt(TILE_DATA_SIZE).put(new byte[TILE_DATA_SIZE]);
        mapData.put("UNIT".getBytes(StandardCharsets.US_ASCII)).putInt(startLocationOwners.length * UNIT_RECORD_SIZE + preplacedUnits.length);

        for (int locationIndex = 0; locationIndex < startLocationOwners.length; locationIndex++) {
            int playerSlot = startLocationOwners[locationIndex];
            int unitOffset = mapData.position();

            mapData.putInt(unitOffset, locationIndex + 1);
            mapData.putShort(unitOffset + UNIT_HORIZONTAL_OFFSET, (short) startLocationHorizontalPositions[locationIndex]);
            mapData.putShort(unitOffset + UNIT_VERTICAL_OFFSET, (short) START_LOCATION_VERTICAL_POSITION);
            mapData.putShort(unitOffset + UNIT_TYPE_OFFSET, (short) GameLoader.START_LOCATION_UNIT_ID);
            mapData.put(unitOffset + UNIT_OWNER_OFFSET, (byte) playerSlot);
            mapData.position(unitOffset + UNIT_RECORD_SIZE);
        }

        mapData.put(preplacedUnits);
        mapData.put(additionalSections);

        ReplayFile replay = ReplayFile.parse(ReplayFileScenario.encode(header.array(), new byte[0]));
        ChkFile map = ChkFile.parse(mapData.array());

        Assertions.assertNotNull(gameData);

        return ReplayLoaderUtils.createReplayInspectionState(replay, map, gameData);
    }

    /**
     * Finds replay files using the existing unsorted traversal and limit.
     *
     * @param limit the maximum file count
     * @return the replay paths collected in traversal order up to the limit
     * @throws IOException if the replay folder cannot be traversed
     */
    private static @NotNull List<Path> findReplayFiles(int limit) throws IOException {
        try (Stream<Path> walk = Files.walk(ExternalInput.REPLAYS.getPath())) {
            return walk.filter(Files::isRegularFile)
                       .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(REPLAY_EXTENSION))
                       .limit(limit)
                       .collect(Collectors.toList());
        }
    }

    @Test
    void create_replay_inspection_state_should_build_uninitialized_state_deterministically() throws IOException {
        Path replayFile = GameTestResources.getPath(GameTestResources.REPLAY_PVP_FORTRESS);

        ReplayFile firstReplay = ReplayFile.parse(Files.readAllBytes(replayFile));

        Assertions.assertNotNull(gameData);

        Game firstState = ReplayLoaderUtils.createReplayInspectionState(firstReplay, gameData);
        ReplayFile secondReplay = ReplayFile.parse(Files.readAllBytes(replayFile));
        Game secondState = ReplayLoaderUtils.createReplayInspectionState(secondReplay, gameData);
        ReplayFile replay = ReplayFile.parse(Files.readAllBytes(replayFile));

        Assertions.assertTrue(firstState.getPlayerTable().getOccupiedPlayers().size() >= 2, "Too few active players.");
        Assertions.assertEquals((int) replay.getHeader().getRandomSeed(), firstState.getRandom().getState());
        Assertions.assertNull(firstState.getUnitSystem().getUnitPool());
        Assertions.assertNull(firstState.getSpriteSystem().getSpritePool());
        Assertions.assertNull(firstState.getFrame());
        Assertions.assertTrue(firstState.getPlayerTable().getStartLocations().isEmpty());
        Assertions.assertFalse(firstState.hasInitializedWorldState());

        Assertions.assertEquals(firstState.computeStateHash(), secondState.computeStateHash(), "The initial state is not deterministic.");
    }

    @Test
    void create_replay_inspection_state_should_expand_terrain_flags_of_every_tile() throws IOException {
        Game state = createFixtureInspectionGame(1, 1, 1, 0, 0, new byte[0], new byte[0]);
        GameMap map = state.getMap();

        Assertions.assertTrue(map.hasTileFlags());
        Assertions.assertEquals(map.getWidth() * map.getHeight(), map.getTileFlagCount());
    }

    @Test
    void inspection_state_disables_random_generation_after_initialization() throws IOException {
        for (int initialUnits : new int[]{0, 1, 2}) {
            Game state = createFixtureInspectionGame(initialUnits, 1, 1, 0, 0, new byte[0], new byte[0]);
            long initialHash = state.computeStateHash();

            Assertions.assertEquals(0, state.getRandom().getEnabled());
            Assertions.assertEquals(0, state.getRandom().next(DRAWN_RANDOM_SOURCE));
            Assertions.assertEquals(initialHash, state.computeStateHash());
        }
    }

    @CorpusTest({ExternalInput.INSTALLATION, ExternalInput.REPLAYS})
    @Test
    void every_corpus_replay_embeds_map_data() throws IOException {
        List<Path> replayFiles = findReplayFiles(CORPUS_REPLAY_LIMIT);

        Assertions.assertFalse(replayFiles.isEmpty(), "No replay file found.");

        int mapDataCount = 0;

        for (Path replayFile : replayFiles) {
            ReplayFile replay = ReplayFile.parse(Files.readAllBytes(replayFile));

            if (replay.getMapData() != null && replay.getMapData().length > 0) {
                mapDataCount++;
            }
        }

        System.out.println("Embedded map data: " + mapDataCount + "/" + replayFiles.size());

        Assertions.assertEquals(replayFiles.size(), mapDataCount);
    }

    @CorpusTest({ExternalInput.INSTALLATION, ExternalInput.REPLAYS})
    @Test
    void create_replay_inspection_state_should_build_state_from_every_embedded_map() throws IOException {
        List<Path> replayFiles = findReplayFiles(CORPUS_REPLAY_LIMIT);

        Assertions.assertFalse(replayFiles.isEmpty(), "No replay file found.");

        for (Path replayFile : replayFiles) {
            ReplayFile replay = ReplayFile.parse(Files.readAllBytes(replayFile));

            Assertions.assertNotNull(gameData);

            Game state = ReplayLoaderUtils.createReplayInspectionState(replay, gameData);

            Assertions.assertNull(state.getUnitSystem().getUnitPool());
            Assertions.assertFalse(state.hasInitializedWorldState());
            Assertions.assertTrue(state.getMap().getWidth() > 0, "Invalid map width: " + replayFile);
            Assertions.assertTrue(state.getMap().getHeight() > 0, "Invalid map height: " + replayFile);
            Assertions.assertTrue(state.getMap().hasTileFlags(), "No expanded terrain flags: " + replayFile);
            Assertions.assertEquals(state.getMap().getWidth() * state.getMap().getHeight(), state.getMap().getTileFlagCount());
        }
    }

    @Test
    void inspection_state_uses_record_order_and_type_for_players() throws IOException {
        Game state = createFixtureInspectionGame(1, 1, 1, 1, FIXTURE_STARTING_MINERALS, new byte[0], new byte[0]);

        for (int playerSlot = 0; playerSlot < PlayerTable.PLAYER_SLOTS; playerSlot++) {
            Player player = state.getPlayerTable().getPlayer(playerSlot);

            Assertions.assertNotNull(player);
            Assertions.assertEquals(FIXTURE_STARTING_MINERALS, player.getMinerals());
            Assertions.assertEquals(0, player.getGas());
        }

        Player human = state.getPlayerTable().getPlayer(HUMAN_SLOT);

        Assertions.assertNotNull(human);
        Assertions.assertEquals(HUMAN_SLOT, human.getId());
        Assertions.assertEquals(COMPUTER_SLOT, human.getCommandPlayerIndex());
        Assertions.assertEquals(HUMAN_TYPE, human.getType());

        Player computer = state.getPlayerTable().getPlayer(COMPUTER_SLOT);

        Assertions.assertNotNull(computer);
        Assertions.assertEquals(COMPUTER_SLOT, computer.getId());
        Assertions.assertEquals(HUMAN_SLOT, computer.getCommandPlayerIndex());
        Assertions.assertEquals(COMPUTER_TYPE, computer.getType());

        Player namedEmpty = state.getPlayerTable().getPlayer(NAMED_EMPTY_SLOT);

        Assertions.assertNotNull(namedEmpty);
        Assertions.assertEquals(NO_PLAYER_TYPE, namedEmpty.getType());
    }

    @Test
    void inspection_state_uses_expansion_upgrade_defaults_and_ignores_normal_mode_sections() throws IOException {
        int[] versions = {ChkFile.VERSION_ORIGINAL, ChkFile.VERSION_HYBRID, ChkFile.VERSION_REMASTERED_ORIGINAL, ChkFile.VERSION_BROOD_WAR,
                          ChkFile.VERSION_REMASTERED_BROOD_WAR};

        Assertions.assertNotNull(gameData);

        for (int version : versions) {
            ByteBuffer sections = ByteBuffer.allocate(2 * Integer.BYTES + Short.BYTES + 2 * Integer.BYTES + EXPANSION_UPGRADES_SIZE)
                                            .order(ByteOrder.LITTLE_ENDIAN);

            sections.putInt(VERSION_TAG).putInt(Short.BYTES).putShort((short) version);
            sections.putInt(EXPANSION_UPGRADES_TAG).putInt(EXPANSION_UPGRADES_SIZE);

            byte[] payload = new byte[EXPANSION_UPGRADES_SIZE];

            Arrays.fill(payload, (byte) 0xFF);
            sections.put(payload);

            Game state = createFixtureInspectionGame(2, 1, 1, 0, 0, new byte[0], sections.array());
            PlayerUpgrades upgrades = state.getPlayerTable().getPlayerUpgrades();

            Assertions.assertNotNull(upgrades);
            Assertions.assertTrue(upgrades.isExpansionEnabled());

            for (int playerIndex = 0; playerIndex < PlayerTable.PLAYER_SLOTS; playerIndex++) {
                for (int upgradeIdentifier = 0; upgradeIdentifier < UpgradesDat.UPGRADE_COUNT; upgradeIdentifier++) {
                    Assertions.assertEquals(0, upgrades.getLevel(playerIndex, upgradeIdentifier));
                    Assertions.assertEquals(gameData.getUpgrades().get("Max Repeats", upgradeIdentifier),
                                            upgrades.getMaximumLevel(playerIndex, upgradeIdentifier));
                }
            }

            Assertions.assertFalse(state.hasInitializedWorldState());
        }
    }

    @Test
    void inspection_state_leaves_upgrades_unavailable_for_classic_and_map_settings_games() throws IOException {
        byte[] version = ByteBuffer.allocate(2 * Integer.BYTES + Short.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                                   .putInt(VERSION_TAG).putInt(Short.BYTES).putShort((short) ChkFile.VERSION_BROOD_WAR).array();
        Game classic = createFixtureInspectionGame(2, 1, 0, 0, 0, new byte[0], version);
        Game mapSettings = createFixtureInspectionGame(0, 0, 1, 0, 0, new byte[0], version);

        Assertions.assertNull(classic.getPlayerTable().getPlayerUpgrades());
        Assertions.assertNull(mapSettings.getPlayerTable().getPlayerUpgrades());
    }

    @Test
    void inspection_state_validates_every_version_section_and_uses_the_final_version_word() throws IOException {
        byte[] versions = ByteBuffer.allocate(2 * (2 * Integer.BYTES + Short.BYTES)).order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(VERSION_TAG).putInt(Short.BYTES).putShort((short) INVALID_VERSION)
                                    .putInt(VERSION_TAG).putInt(Short.BYTES).putShort((short) ChkFile.VERSION_BROOD_WAR).array();
        Game accepted = createFixtureInspectionGame(2, 1, 1, 0, 0, new byte[0], versions);

        Assertions.assertNotNull(accepted.getPlayerTable().getPlayerUpgrades());

        versions[FINAL_VERSION_OFFSET]     = 1;
        versions[FINAL_VERSION_OFFSET + 1] = 0;

        Game unsupported = createFixtureInspectionGame(2, 1, 1, 0, 0, new byte[0], versions);

        Assertions.assertNull(unsupported.getPlayerTable().getPlayerUpgrades());

        byte[] malformed = ByteBuffer.allocate(2 * Integer.BYTES + Byte.BYTES + 2 * Integer.BYTES + Short.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                                     .putInt(VERSION_TAG).putInt(Byte.BYTES).put((byte) ChkFile.VERSION_BROOD_WAR)
                                     .putInt(VERSION_TAG).putInt(Short.BYTES).putShort((short) ChkFile.VERSION_BROOD_WAR).array();
        Game rejected = createFixtureInspectionGame(2, 1, 1, 0, 0, new byte[0], malformed);

        Assertions.assertNull(rejected.getPlayerTable().getPlayerUpgrades());
    }

    @Test
    void create_initialized_replay_world_should_resolve_first_selection_handles() throws Exception {
        ReplayFile replay = ReplayFile.parse(Files.readAllBytes(GameTestResources.getPath(GameTestResources.REPLAY_ZVP_FIGHTING_SPIRIT)));

        try (GameData loadedData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayInitialization initialization = ReplayInitialization.createFreshReplay(replay.getHeader());
            byte[] mapData = replay.getMapData();

            Assertions.assertNotNull(mapData);

            Game state = ReplayLoaderUtils.createInitializedReplayWorld(initialization,
                                                                        replay, ChkFile.parseTolerant(mapData),
                                                                        loadedData,
                                                                        RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT,
                                                                        INSPECTION_INSTRUCTION_LIMIT);
            UnitPool unitPool = state.getUnitSystem().getUnitPool();

            Assertions.assertNotNull(unitPool);
            Assertions.assertEquals(STARTING_UNIT_COUNT, unitPool.getAllocatedCount());
            Assertions.assertFalse(state.hasInitializedWorldState());

            ReplayCommand firstSelection = replay.getCommands().stream()
                                                 .filter(command -> command.getTypeId() == ReplayCommandIds.SELECT)
                                                 .findFirst().orElseThrow();

            Assertions.assertEquals(FIRST_SELECTION_FRAME, firstSelection.getFrame());

            ReplaySelectionCommand selection = ReplaySelectionCommand.parse(firstSelection);
            StringBuilder result = new StringBuilder("Fresh replay construction; startup phases have not run.\n");

            for (int handleIndex = 0; handleIndex < selection.getHandleCount(); handleIndex++) {
                long handle = selection.getHandle(handleIndex);
                int poolIndex = UnitHandle.getPoolIndex((int) handle, unitPool.getCapacity());
                Unit unit = unitPool.getUnit(poolIndex);

                Assertions.assertTrue(unitPool.isAllocated(poolIndex));
                Assertions.assertEquals(UnitHandle.getGeneration((int) handle, unitPool.getCapacity()), unitPool.getGeneration(poolIndex));

                result.append("handle=").append(handle).append(" pool=").append(poolIndex).append(" type=").append(unit.getUnitId())
                      .append(" owner=").append(unit.getOwnerIndex()).append('\n');
            }

            for (StartingUnitLoader.Creation creation : state.getStartingUnitCreations()) {
                result.append(creation.getPoolIndex()).append('\n');
            }

            Path output = Path.of("build", "test-output", "native-replay-integration", "fresh-replay-handles.txt");

            Files.createDirectories(output.getParent());
            Files.writeString(output, result);
        }
    }
}
