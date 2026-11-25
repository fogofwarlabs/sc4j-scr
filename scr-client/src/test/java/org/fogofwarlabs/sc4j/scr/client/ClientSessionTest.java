package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.scr.client.world.ClientCamera;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.casc.SoundPathResolver;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkUnit;
import org.fogofwarlabs.sc4j.scr.data.format.replay.*;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.fogofwarlabs.sc4j.scr.game.frame.GameExecutionScope;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.presentation.CommandErrorEvents;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.presentation.NotificationEvents;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayCommandIssuer;
import org.fogofwarlabs.sc4j.scr.game.save.SaveGameKind;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.state.GameEnd;
import org.fogofwarlabs.sc4j.scr.game.state.GameLoader;
import org.fogofwarlabs.sc4j.scr.game.test.*;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalInterfaceState;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.visibility.LocalVision;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Verifies that a client session inspects synthetic replay inputs without executing them, retains startup and frame failures, restarts installed
 * replays with their camera and vision, starts a replay at the first human start location and at the viewer's speed setting, plays alike whatever
 * the audio set and seeks, reports command errors and notifications of live games, writes the replay of a stopped live game, ends an EUD replay at
 * its EUD failure and resumes saved games.
 */
@RequiresInstallation
final class ClientSessionTest {
    private static final int FIRST_PLAYER = 0x01;
    /**
     * The vision mask of the eight playable players.
     */
    private static final int ALL_PLAYERS_MASK = 0xFF;

    /**
     * The frame of {@link GameTestResources#REPLAY_STALE_RESTART_GAME} whose stale Restart Game command ends native replay playback.
     */
    private static final int STALE_RESTART_GAME_FRAME = 14988;

    /**
     * The game type and the victory condition that the header of the one-on-one replay records.
     */
    private static final int ONE_ON_ONE_GAME_TYPE = 4;
    private static final int RECORDED_VICTORY_CONDITION = 7;
    /**
     * The unit types the one-on-one replay starts with: the mineral field types, the geyser, the Drone and the Hatchery.
     */
    private static final int FIRST_MINERAL_FIELD_TYPE = 176;
    private static final int LAST_MINERAL_FIELD_TYPE = 178;
    private static final int VESPENE_GEYSER_TYPE = 188;
    private static final int DRONE_TYPE = 41;
    private static final int HATCHERY_TYPE = 131;
    private static final int STARTING_WORKERS = 8;
    private static final int STARTING_BASES = 2;

    /**
     * A frame whose phase is left open in the running world, which stops its next step, and the state frame of that step.
     */
    private static final int OPEN_PHASE_FRAME = 100;
    private static final int INTERRUPTED_STATE_FRAME = 2;

    /**
     * The command types of the synthetic replays.
     */
    private static final int KEEP_ALIVE_COMMAND_TYPE = 0x05;
    private static final int SELECT_ADD_COMMAND_TYPE = 0x0A;
    private static final int PAUSE_COMMAND_TYPE = 0x10;
    private static final int RIGHT_CLICK_COMMAND_TYPE = 0x14;
    private static final int TARGETED_ORDER_COMMAND_TYPE = 0x15;
    private static final int RIGHT_CLICK_121_COMMAND_TYPE = 0x60;
    private static final int TARGETED_ORDER_121_COMMAND_TYPE = 0x61;
    private static final int SELECT_121_COMMAND_TYPE = 0x63;
    /**
     * The first command type whose unit handles are four bytes wide.
     */
    private static final int FIRST_WIDE_HANDLE_COMMAND_TYPE = 0x60;
    /**
     * A command type without a dispatcher, which the parser skips with the rest of its command block.
     */
    private static final int UNKNOWN_COMMAND_TYPE = 0xFF;
    /**
     * The frames the synthetic commands are recorded at.
     */
    private static final int COMMAND_FRAME = 2;
    private static final int SKIPPED_COMMAND_FRAME = 3;

    /**
     * The player record of the synthetic replays whose player index and value a test chooses, and the values it chooses.
     */
    private static final int SYNTHETIC_RECORD_INDEX = 3;
    private static final int HIGH_BIT_PLAYER_INDEX = 0x80000000;
    private static final int OTHER_PLAYER_INDEX = 7;
    private static final int EXTENDED_RECORD_VALUE = 0x10003;
    /**
     * A recorded issuer byte of a player record that keeps its own index as its values.
     */
    private static final int OTHER_ISSUER = 7;

    /**
     * The unit handle widths of the legacy and the extended commands.
     */
    private static final int NARROW_HANDLE_SIZE = 2;
    private static final int WIDE_HANDLE_SIZE = 4;
    /**
     * The extended selection payload: a count byte and two handles, all bits set and only the high bit set.
     */
    private static final int SELECTION_PAYLOAD_SIZE = 9;
    private static final int SELECTION_HANDLE_COUNT = 2;
    private static final int MAXIMUM_HANDLE = 0xFFFFFFFF;
    private static final int HIGH_BIT_HANDLE = 0x80000000;
    private static final long UNSIGNED_MAXIMUM_HANDLE = 0xFFFFFFFFL;
    private static final long UNSIGNED_HIGH_BIT_HANDLE = 0x80000000L;
    /**
     * The legacy selection payload: a count byte and forty-eight copies of one raw handle.
     */
    private static final int LEGACY_SELECTION_PAYLOAD_SIZE = 97;
    private static final int LEGACY_SELECTION_HANDLE_COUNT = 48;
    private static final int DUPLICATE_LEGACY_HANDLE = 0x8001;
    /**
     * The unit capacity of a replay without a capacity section.
     */
    private static final int DEFAULT_UNIT_CAPACITY = 1700;

    /**
     * The target payload fields: the target position, the largest legacy handle, the unit type, the order and the queue
     * flags, and the field bytes of a right click and of a targeted order besides the handle.
     */
    private static final int TARGET_X = 0xFFFF;
    private static final int TARGET_Y = 0x8000;
    private static final int MAXIMUM_NARROW_HANDLE = 0xFFFF;
    private static final long UNSIGNED_MAXIMUM_NARROW_HANDLE = 0xFFFFL;
    private static final int TARGET_UNIT_TYPE = 228;
    private static final int TARGET_ORDER = 74;
    private static final int QUEUE_FLAGS = 0xFE;
    private static final int RIGHT_CLICK_FIELDS_SIZE = 7;
    private static final int TARGETED_ORDER_FIELDS_SIZE = 8;

    /**
     * The inspection viewport around the synthetic map unit, its center, and the zoom that keeps the unit at the center.
     */
    private static final int INSPECTION_VIEWPORT_SIZE = 256;
    private static final int INSPECTION_VIEWPORT_CENTER = 128;
    private static final float INSPECTION_ZOOM = 2.0f;

    /**
     * The command block of a single command: the frame, the length, the issuer and the type bytes before the payload, and
     * the issuer and type bytes the length counts.
     */
    private static final int COMMAND_BLOCK_HEADER_SIZE = 7;
    private static final int COMMAND_PREFIX_SIZE = 2;

    /**
     * The synthetic replay header: its size, the engine and frame count fields, the map dimension fields and the player records.
     */
    private static final int REPLAY_HEADER_SIZE = 0x279;
    private static final int ENGINE_OFFSET = 0;
    private static final int BROOD_WAR_ENGINE = 1;
    private static final int FRAME_COUNT_OFFSET = 1;
    private static final int REPLAY_FRAMES = 10;
    private static final int MAP_WIDTH_OFFSET = 0x34;
    private static final int MAP_HEIGHT_OFFSET = 0x36;
    private static final int PLAYER_RECORD_COUNT = 12;
    private static final int PLAYER_INDEXES_OFFSET = 0xA1;
    private static final int PLAYER_VALUES_OFFSET = 0xA5;
    private static final int PLAYER_RECORD_SIZE = 36;

    /**
     * The synthetic scenario: a {@code 16 x 16} Badlands map with one neutral mineral field.
     */
    private static final int MAP_SIZE = 586;
    private static final int MAP_SIZE_TILES = 16;
    private static final @NotNull String DIMENSION_SECTION = "DIM ";
    private static final int DIMENSION_SECTION_SIZE = 4;
    private static final @NotNull String TILESET_SECTION = "ERA ";
    private static final int TILESET_SECTION_SIZE = 2;
    private static final int BADLANDS_TILESET = 0;
    private static final @NotNull String TILE_SECTION = "MTXM";
    private static final int TILE_SECTION_SIZE = 512;
    private static final @NotNull String UNIT_SECTION = "UNIT";
    private static final int UNIT_RECORD_SIZE = 36;
    private static final int UNIT_SERIAL = 1;
    private static final int UNIT_X_OFFSET = 4;
    private static final int UNIT_Y_OFFSET = 6;
    private static final int UNIT_TYPE_OFFSET = 8;
    private static final int UNIT_OWNER_OFFSET = 16;
    private static final int SYNTHETIC_UNIT_POSITION = 256;
    private static final int SYNTHETIC_UNIT_TYPE = 176;
    private static final int NEUTRAL_PLAYER = 11;
    /**
     * The scenario offsets of the unit section header and of its unit record.
     */
    private static final int UNIT_SECTION_HEADER_OFFSET = 542;
    private static final int UNIT_RECORD_OFFSET = 550;

    private static final @NotNull String REPLAY_FILE_NAME = "session.rep";

    /**
     * The view of the playback runs that switch the audio set and seek.
     */
    private static final int PLAYBACK_VIEWPORT_WIDTH = 980;
    private static final int PLAYBACK_VIEWPORT_HEIGHT = 668;
    /**
     * The sound whose classic and remastered files differ ({@code Misc\Buzz.wav}).
     */
    private static final int BUZZ_SOUND = 2;
    /**
     * The frames played, and the frame at which the audio set switches.
     */
    private static final int PLAYED_FRAMES = 2000;
    private static final int SWITCH_FRAME = 1000;
    /**
     * The seek targets as fractions of the replay's frames: two thirds, back to one third, forward to one half and back to one quarter.
     */
    private static final int TWO_THIRDS_MULTIPLIER = 2;
    private static final int THIRD_DIVISOR = 3;
    private static final int HALF_DIVISOR = 2;
    private static final int QUARTER_DIVISOR = 4;

    /**
     * The name the replay of the campaign mission whose start screen is checked is copied to.
     */
    private static final @NotNull String CAMPAIGN_REPLAY_FILE_NAME = "campaign-bw-zerg-2-transmissions.rep";
    /**
     * The player index of the mission's human, the replay local player of the viewer.
     */
    private static final int REPLAY_LOCAL_PLAYER = 3;
    /**
     * The game screen of a {@code 1280 x 800} window at {@code 480} rows, and a screen taller than the map allows.
     */
    private static final int SCREEN_WIDTH = 768;
    private static final int SCREEN_HEIGHT = 480;
    private static final int TALL_SCREEN_HEIGHT = 1000;
    /**
     * The frames after the viewer's Play until its Replay Speed command ran: the next turn step sends it and the one after runs it.
     */
    private static final int REPLAY_SPEED_COMMAND_FRAMES = 4;
    /**
     * The screen top-left corners centered on the start location in the screen width and the 383-row play view, and with the tall screen clamped
     * to the map.
     */
    private static final int START_SCREEN_X = 2976;
    private static final int START_SCREEN_Y = 3569;
    private static final int CLAMPED_SCREEN_Y = 3096;

    /**
     * The Fastest game speed of the live games.
     */
    private static final int FASTEST_SPEED = 6;
    /**
     * The campaign mission the command error tests play, its human's name and the locale.
     */
    private static final @NotNull String CAMPAIGN_MISSION = "bw-terran-1";
    private static final @NotNull String PLAYER_NAME = "Tester";
    private static final @NotNull String LOCALE = "enUS";

    private static final int MISSION_START_FRAMES = 4;
    private static final int ERROR_WAIT_MULTIPLIER = 4;
    private static final int ERROR_WAIT_FRAMES = MISSION_START_FRAMES * ERROR_WAIT_MULTIPLIER;
    private static final int TRAINING_FRAMES = 600;

    private static final int SELECT_OPCODE = ReplayCommandIds.SELECT_121;
    private static final int TRAIN_OPCODE = ReplayCommandIds.TRAIN;
    private static final int BUILD_OPCODE = ReplayCommandIds.BUILD;

    private static final int BUILDING_MINERALS = 1000;
    /**
     * Order {@code PlaceBuilding} ({@code 30}), the SCV's build order.
     */
    private static final int PLACE_BUILDING_ORDER = 30;
    private static final int TILE_SIZE = 32;

    private static final int MINERALS_TEXT = 850;
    /**
     * The text of placement code {@code 4} (blocked).
     */
    private static final int BLOCKED_TEXT = 856;

    /**
     * The installed maps and the lobby of the live melee games: the human's name and race.
     */
    private static final @NotNull String MELEE_PLAYER_NAME = "tester";
    private static final @NotNull String MELEE_PLAYER_RACE = "terran";
    /**
     * The file the replay of a live game is saved to.
     */
    private static final @NotNull String LAST_REPLAY_FILE_NAME = "LastReplay.rep";

    /**
     * The installed EUD test map whose first view read stops the frame, the frames the stopped game may run, and the Stop command type.
     */
    private static final @NotNull String EUD_MAP = "worm.scx";
    private static final int MAXIMUM_STOP_FRAMES = 1000;
    private static final int STOP_COMMAND_TYPE = 0x1A;

    /**
     * The installed civilization defense map and the frames its live game runs before its replay is viewed.
     */
    private static final @NotNull String CIVILIZATION_DEFENSE_MAP = "civ.scx";
    private static final int EUD_LIVE_FRAMES = 400;
    /**
     * The frame at which the replay branch of the civilization defense map reads an EUD address without a native region.
     */
    private static final int EUD_FAILURE_FRAME = 2;
    /**
     * The error-notice value shown when the replay branch reaches an unrepresented EUD address.
     */
    private static final int EUD_FAILURE_CODE = 0xD7279F57;
    /**
     * The game view the viewer inputs supply: half the viewport in each direction.
     */
    private static final int VIEWPORT_DIVISOR = 2;
    private static final int VIEW_WIDTH = RunningGameScenario.VIEWPORT_WIDTH / VIEWPORT_DIVISOR;
    private static final int VIEW_HEIGHT = RunningGameScenario.VIEWPORT_HEIGHT / VIEWPORT_DIVISOR;
    private static final @NotNull Charset SOURCE_PATH_CHARSET = Charset.forName("x-windows-949");

    private static final @NotNull String NETWORK_TEXTS = "locales/enUS/Assets/rez/network.json";
    private static final @NotNull String STATISTICS_TEXTS = "locales/enUS/Assets/rez/stat_txt.json";
    private static final @NotNull String TEXT_KEY = "Key";
    private static final @NotNull String TEXT_VALUE = "Value";

    private static final int SAVE_FRAME = 1500;
    private static final int CONTINUE_FRAMES = 800;
    private static final int SAVE_WAIT_FRAMES = 4;
    private static final @NotNull String SAVE_NAME = "round trip";
    /**
     * The installed melee map of the saved game and its lobby: the human's race and the computer's race.
     */
    private static final @NotNull String MELEE_MAP_FILE = "(2)Bottleneck.scm";
    private static final @NotNull String SAVED_GAME_PLAYER_RACE = "protoss";
    private static final @NotNull String COMPUTER_RACE = "zerg";

    /**
     * Plays the same frame sequence while switching the audio set at its midpoint.
     *
     * @param session the playback session
     * @return the completed frame and random-state counters
     */
    private static @NotNull String playFramesAndDescribeState(@NotNull ClientSession session) {
        session.getCamera().setViewportSize(PLAYBACK_VIEWPORT_WIDTH, PLAYBACK_VIEWPORT_HEIGHT);
        session.startReplay();

        for (int frame = 0; frame < PLAYED_FRAMES && session.stepFrame(true); frame++) {
            if (frame == SWITCH_FRAME) {
                session.setAudioSet(session.getAudioSet() == SoundPathResolver.AudioSet.CLASSIC
                                    ? SoundPathResolver.AudioSet.REMASTERED
                                    : SoundPathResolver.AudioSet.CLASSIC);
            }
        }

        return session.getLastCompletedFrame() + " " + Integer.toUnsignedString(session.getGame().getRandom().getState()) + " " +
               session.getGame().getRandom().getTotalRandomCounts();
    }

    /**
     * Steps playback until the requested frame or the session stops.
     *
     * @param session the playback session
     * @param frame   the target completed frame
     * @param audible whether to drain audio while stepping
     */
    private static void advanceToFrame(@NotNull ClientSession session, long frame, boolean audible) {
        while (session.getLastCompletedFrame() < frame && session.stepFrame(audible)) {
        }
    }

    /**
     * Describes the final state of a played session.
     *
     * @param session the playback session
     * @return the last completed frame, the RNG state and count, and the session status
     */
    private static @NotNull String describeFinalState(@NotNull ClientSession session) {
        return session.getLastCompletedFrame() + " " + session.getGame().getRandom().getState() + "/" +
               session.getGame().getRandom().getTotalRandomCounts() + " " + session.getStatus();
    }

    /**
     * Sizes the viewport, starts the mission and runs its first frames.
     *
     * @param session the started campaign session
     */
    private static void startMission(@NotNull ClientSession session) {
        session.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
        session.startReplay();

        for (int frame = 0; frame < MISSION_START_FRAMES; frame++) {
            session.stepFrame(false);
        }
    }

    /**
     * Queues a Select command of one unit of the local player.
     *
     * @param session the running session
     * @param player  the local player
     * @param handle  the unit handle
     */
    private static void selectUnit(@NotNull ClientSession session, int player, int handle) {
        session.submitLocalCommand(player,
                                   SELECT_OPCODE,
                                   new byte[]{1, (byte) handle, (byte) (handle >>> Byte.SIZE), (byte) (handle >>> Short.SIZE),
                                              (byte) (handle >>> (Integer.SIZE - Byte.SIZE))});
    }

    /**
     * Finds the first allocated unit of the required type and owner.
     *
     * @param game  the initialized world
     * @param owner the required owner
     * @param type  the required unit type
     * @return the matching unit
     * @throws AssertionError if the fixture contains no matching unit
     */
    private static @NotNull Unit findUnit(@NotNull Game game, int owner, int type) {
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        for (int index = 1; index <= pool.getCapacity(); index++) {
            if (pool.isAllocated(index) && pool.getUnit(index).getOwnerIndex() == owner && pool.getUnit(index).getUnitId() == type) {
                return pool.getUnit(index);
            }
        }

        throw Exceptions.newAssertionError("No unit of type {0}", type);
    }

    /**
     * Supplies the local viewer inputs and initializes message layouts on their first use.
     *
     * @param game the replay world
     * @throws IOException if the installed status texts cannot be read
     */
    private static void supplyViewerInputs(@NotNull Game game) throws IOException {
        MessageLines lines = game.getInterfaceLink().getMessageLines();

        if (!lines.hasLayout()) {
            lines.setLayout(text -> new int[]{text.length});
            lines.setHostClock(() -> game.getCurrentFrame() * GameSpeed.FASTEST_DURATION);
            lines.setSubtitles(Boolean.TRUE);

            Map<String, byte[]> keyed = new HashMap<>();
            List<byte[]> numbered = new ArrayList<>();

            GameData networkData = game.getGameData();

            Assertions.assertNotNull(networkData);

            if (JsonReader.parse(networkData.readInstalledFile(NETWORK_TEXTS)) instanceof List<?> entries) {
                for (Object entry : entries) {
                    if (entry instanceof Map<?, ?> record && record.get(TEXT_KEY) instanceof String key &&
                        record.get(TEXT_VALUE) instanceof String value) {
                        keyed.put(key, value.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }

            GameData statisticsData = game.getGameData();

            Assertions.assertNotNull(statisticsData);

            if (JsonReader.parse(statisticsData.readInstalledFile(STATISTICS_TEXTS)) instanceof List<?> entries) {
                for (Object entry : entries) {
                    numbered.add(entry instanceof Map<?, ?> record && record.get(TEXT_VALUE) instanceof String value
                                 ? value.getBytes(StandardCharsets.UTF_8)
                                 : null);
                }
            }

            lines.setStatusTexts(new MessageLines.StatusTexts() {
                /**
                 * Resolves a localized keyed text.
                 *
                 * @param key the string key
                 * @return the localized text bytes, or {@code null} when the key is unknown
                 */
                @Override
                public byte @Nullable [] resolveKeyedText(@NotNull String key) {
                    return keyed.get(key);
                }

                /**
                 * Resolves a localized numbered text.
                 *
                 * @param textIdentifier the 1-based {@code stat_txt} id
                 * @return the localized text bytes, or {@code null} when the id is unknown
                 */
                @Override
                public byte @Nullable [] resolveNumberedText(int textIdentifier) {
                    return textIdentifier >= 1 && textIdentifier <= numbered.size() ? numbered.get(textIdentifier - 1) : null;
                }
            });

            LocalInterfaceState local = game.getInterfaceLink().getLocalInterface();

            local.setMouseButtons(0);
            local.setChatMode(0);
            local.setGluePalette(0);
            local.setDisplayedButtonSet(ButtonSetIds.EMPTY);
            game.getInterfaceLink().getLocalKeyStates().setAllReleased();
            game.getInterfaceLink().getLocalView().setScreenGeometry(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT,
                                                                     game.getMap().getWidth() * TerrainTileset.TILE_SIZE -
                                                                     RunningGameScenario.VIEWPORT_WIDTH,
                                                                     game.getMap().getHeight() * TerrainTileset.TILE_SIZE -
                                                                     RunningGameScenario.VIEWPORT_HEIGHT);
        }

        game.getInterfaceLink().getLocalView().setView(game.getInterfaceLink().getLocalView().isKnown()
                                                       ? game.getInterfaceLink().getLocalView().getScreenX()
                                                       : 0,
                                                       game.getInterfaceLink().getLocalView().isKnown()
                                                       ? game.getInterfaceLink().getLocalView().getScreenY()
                                                       : 0,
                                                       VIEW_WIDTH, VIEW_HEIGHT);
        game.getInterfaceLink().getLocalView().takeWritten();
    }

    /**
     * Loads a saved game with the borrowed assets and captures each resumed observable-state digest.
     *
     * @param storage               the borrowed installed storage
     * @param gameData              the borrowed installed data
     * @param installationDirectory the installed data directory
     * @param file                  the saved game path
     * @param savedFrame            the expected saved frame
     * @param frames                the number of resumed frames
     * @return the ordered resumed observable-state digests
     * @throws IOException if the saved game cannot be loaded
     */
    private static @NotNull List<Long> loadAndContinue(@NotNull CascStorage storage,
                                                       @NotNull GameData gameData,
                                                       @NotNull Path installationDirectory,
                                                       @NotNull Path file,
                                                       int savedFrame,
                                                       int frames) throws IOException {
        List<Long> stateDigests = new ArrayList<>();

        try (ClientSession loaded = ClientSession.startSavedGame(installationDirectory, storage, gameData, file, null)) {
            loaded.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
            loaded.startReplay();

            Assertions.assertTrue(loaded.isSavedGame());
            Assertions.assertEquals(savedFrame, loaded.getGame().getCurrentFrame(), loaded.getStatus());

            for (int frame = 0; frame < frames; frame++) {
                Assertions.assertTrue(loaded.stepFrame(false), loaded.getStatus());
                stateDigests.add(StateDigestCheck.digestObservableState(loaded.getGame()));
            }
        }

        return stateDigests;
    }

    @TempDir
    @Nullable Path temporaryDirectory;

    @Test
    void one_on_one_replay_starts_with_map_resources_two_bases_and_eight_workers() throws IOException {
        Path replayPath = GameTestResources.getPath(GameTestResources.REPLAY_ZVZ_HEARTBREAK_RIDGE);

        try (ClientSession session = ClientSession.load(ExternalInput.INSTALLATION.getPath(), replayPath)) {
            Assertions.assertEquals(ONE_ON_ONE_GAME_TYPE, session.getReplay().getHeader().getType());
            Assertions.assertEquals(RECORDED_VICTORY_CONDITION, session.getReplay().getHeader().getSettings().getVictoryCondition());

            session.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
            session.startReplay();

            UnitPool pool = session.getGame().getUnitSystem().getUnitPool();

            Assertions.assertNotNull(pool, session.getStatus());

            int mineralCount = 0;
            int geyserCount = 0;
            int workerCount = 0;
            int baseCount = 0;

            for (int unitIndex = 1; unitIndex <= pool.getCapacity(); unitIndex++) {
                if (!pool.isAllocated(unitIndex)) {
                    continue;
                }

                int unitType = pool.getUnit(unitIndex).getUnitId();

                if (unitType >= FIRST_MINERAL_FIELD_TYPE && unitType <= LAST_MINERAL_FIELD_TYPE) {
                    mineralCount++;
                }

                if (unitType == VESPENE_GEYSER_TYPE) {
                    geyserCount++;
                }

                if (unitType == DRONE_TYPE) {
                    workerCount++;
                }

                if (unitType == HATCHERY_TYPE) {
                    baseCount++;
                }
            }

            int sourceMinerals = 0;
            int sourceGeysers = 0;

            for (ChkUnit placement : session.getPlacements()) {
                if (placement.getUnitId() >= FIRST_MINERAL_FIELD_TYPE && placement.getUnitId() <= LAST_MINERAL_FIELD_TYPE) {
                    sourceMinerals++;
                }

                if (placement.getUnitId() == VESPENE_GEYSER_TYPE) {
                    sourceGeysers++;
                }
            }

            Assertions.assertTrue(sourceMinerals > 0);
            Assertions.assertTrue(sourceGeysers > 0);
            Assertions.assertEquals(sourceMinerals, mineralCount);
            Assertions.assertEquals(sourceGeysers, geyserCount);
            Assertions.assertEquals(STARTING_WORKERS, workerCount);
            Assertions.assertEquals(STARTING_BASES, baseCount);
        }
    }

    @Test
    void replay_viewer_ends_at_a_stale_restart_game_command() throws IOException {
        try (ClientSession session = ClientReplayScenario.start(GameTestResources.REPLAY_STALE_RESTART_GAME, Long.MAX_VALUE)) {
            Assertions.assertEquals(GameEnd.RESTART, session.getEnd(), session.getStatus());
            Assertions.assertEquals(STALE_RESTART_GAME_FRAME, session.getLastCompletedFrame());
            Assertions.assertTrue(session.isEnded());
            Assertions.assertFalse(session.isFinished());
            Assertions.assertFalse(session.canStep());
            Assertions.assertFalse(session.stepFrame(false));
            Assertions.assertFalse(session.stepPausedReplay());
            Assertions.assertNull(session.getFailureBoundary(), session.getStatus());
            Assertions.assertEquals(STALE_RESTART_GAME_FRAME, session.getLastCompletedFrame());
        }
    }

    @Test
    void session_retains_partial_frame_and_pending_command_until_reset() throws IOException {
        Path replayPath = GameTestResources.getPath(GameTestResources.REPLAY_ZVP_FIGHTING_SPIRIT);

        try (ClientSession session = ClientSession.load(ExternalInput.INSTALLATION.getPath(), replayPath)) {
            session.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
            session.selectPlacementForInspection(0);
            session.startReplay();

            Assertions.assertTrue(session.canStep(), session.getStatus());
            Assertions.assertNull(session.getSelectedPlacement());
            Assertions.assertTrue(session.stepFrame(), session.getStatus());
            Assertions.assertEquals(1, session.getLastCompletedFrame());

            Game runningState = session.getGame();
            ReplayCommand pendingCommand = session.getPendingCommand();
            GameExecutionScope interruptedScope = runningState.getExecutionScope();

            Assertions.assertNotNull(interruptedScope);

            interruptedScope.beginFramePhase(OPEN_PHASE_FRAME);

            Assertions.assertFalse(session.stepFrame());
            Assertions.assertSame(runningState, session.getGame());
            Assertions.assertEquals(1, session.getLastCompletedFrame());
            Assertions.assertEquals(INTERRUPTED_STATE_FRAME, runningState.getCurrentFrame());
            Assertions.assertEquals(0, runningState.getRandom().getEnabled());
            Assertions.assertFalse(session.canStep());
            Assertions.assertSame(pendingCommand, session.getPendingCommand());
            Assertions.assertTrue(session.getStatus().contains("completed frame 1, state frame 2"));

            long interruptedHash = runningState.computeStateHash();
            String interruptionStatus = session.getStatus();

            Assertions.assertFalse(session.stepFrame());
            Assertions.assertEquals(interruptedHash, runningState.computeStateHash());
            Assertions.assertEquals(interruptionStatus, session.getStatus());

            ClientCamera camera = session.getCamera();

            session.reset();

            Assertions.assertSame(camera, session.getCamera());
            Assertions.assertNotSame(runningState, session.getGame());
            Assertions.assertTrue(session.canStep(), session.getStatus());
            Assertions.assertEquals(0, session.getLastCompletedFrame());
            Assertions.assertEquals(0, session.getGame().getCurrentFrame());
            Assertions.assertNull(session.getSelectedPlacement());
        }
    }

    @Test
    void reset_should_keep_the_replay_vision_and_the_reveal_of_the_vision_dialog() throws IOException {
        Path replayPath = GameTestResources.getPath(GameTestResources.REPLAY_ZVP_FIGHTING_SPIRIT);

        try (ClientSession session = ClientSession.load(ExternalInput.INSTALLATION.getPath(), replayPath)) {
            session.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
            session.startReplay();
            session.getGame().getLocalVision().acceptDialog(FIRST_PLAYER, false);
            session.reset();

            LocalVision restarted = session.getGame().getLocalVision();

            Assertions.assertEquals(FIRST_PLAYER, restarted.getReplayMask(), "The players the dialog unchecked stay hidden.");
            Assertions.assertEquals(FIRST_PLAYER, restarted.getComparisonMask());
            Assertions.assertFalse(restarted.isRevealed());

            restarted.acceptDialog(FIRST_PLAYER, true);
            session.reset();

            LocalVision revealed = session.getGame().getLocalVision();

            Assertions.assertTrue(revealed.isRevealed(), "A restart keeps the reveal.");
            Assertions.assertEquals(ALL_PLAYERS_MASK, revealed.getReplayMask());
            Assertions.assertEquals(ALL_PLAYERS_MASK, revealed.getComparisonMask());
        }
    }

    @Test
    void get_active_players_should_drop_a_computer_out_of_the_game() throws IOException {
        Path replayPath = GameTestResources.getPath(GameTestResources.REPLAY_ZVP_FIGHTING_SPIRIT);

        try (ClientSession session = ClientSession.load(ExternalInput.INSTALLATION.getPath(), replayPath)) {
            session.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
            session.startReplay();

            int slot = session.getActivePlayers()[0];
            Player player = session.getGame().getPlayerTable().getPlayer(slot);

            Assertions.assertNotNull(player);

            player.setType(PlayerTypes.COMPUTER);
            player.setVictoryState(Player.VICTORY_STATE_DEFEATED);

            Assertions.assertTrue(Arrays.stream(session.getActivePlayers()).noneMatch(active -> active == slot),
                                  "A defeated computer leaves the alliance and messaging dialog rows.");
        }
    }

    @Test
    void session_retains_startup_failure_without_executing_skipped_commands() throws IOException {
        try (ClientSession session = this.loadSession(UNKNOWN_COMMAND_TYPE, SKIPPED_COMMAND_FRAME)) {
            session.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
            session.startReplay();

            Assertions.assertTrue(session.isPlaybackRequested());
            Assertions.assertFalse(session.canStep());
            Assertions.assertFalse(session.stepFrame());
            Assertions.assertEquals(-1, session.getLastCompletedFrame());
            Assertions.assertTrue(session.getStatus().startsWith("Startup "));
            Assertions.assertThrows(IllegalStateException.class, session::startReplay);

            session.reset();

            Assertions.assertTrue(session.isPlaybackRequested());
            Assertions.assertFalse(session.canStep());
            Assertions.assertEquals(-1, session.getLastCompletedFrame());
        }
    }

    @Test
    void session_retains_inputs_without_executing_uninitialized_world() throws IOException {
        for (int commandType : new int[]{KEEP_ALIVE_COMMAND_TYPE, PAUSE_COMMAND_TYPE}) {
            try (ClientSession session = this.loadSession(commandType, COMMAND_FRAME)) {
                long initialHash = session.getGame().computeStateHash();
                GameLoader.InitializationRequest request = session.getInitializationRequests().getFirst();

                Assertions.assertEquals(GameLoader.InitializationRoute.UNIT_RECORD, request.getRoute());
                Assertions.assertEquals(UNIT_SECTION_HEADER_OFFSET, request.getSectionHeaderOffset());
                Assertions.assertEquals(UNIT_RECORD_OFFSET, request.getRecordOffset());
                Assertions.assertEquals(SYNTHETIC_UNIT_TYPE, request.getTypeIdentifier());
                Assertions.assertThrows(UnsupportedOperationException.class, () -> session.getInitializationRequests().clear());
                Assertions.assertThrows(UnsupportedOperationException.class, () -> session.getPlacements().clear());
                Assertions.assertNotNull(session.getPendingCommand());
                Assertions.assertFalse(session.canStep());
                Assertions.assertFalse(session.stepFrame());
                Assertions.assertEquals(0, session.getGame().getCurrentFrame());
                Assertions.assertEquals(0, session.getGame().getCommandDispatchMarkerCount());
                Assertions.assertEquals(initialHash, session.getGame().computeStateHash());
                Assertions.assertTrue(session.getStatus().contains("world initialization"));

                session.reset();

                Assertions.assertEquals(initialHash, session.getGame().computeStateHash());
                Assertions.assertSame(request, session.getInitializationRequests().getFirst());
            }
        }
    }

    @Test
    void session_blocks_every_step_when_parser_skipped_command_block() throws IOException {
        try (ClientSession session = this.loadSession(UNKNOWN_COMMAND_TYPE, SKIPPED_COMMAND_FRAME)) {
            long initialHash = session.getGame().computeStateHash();

            Assertions.assertEquals(1, session.getReplay().getUnknownTypeCount());
            Assertions.assertNull(session.getPendingCommand());
            Assertions.assertNull(session.getPendingSelection());
            Assertions.assertFalse(session.canStep());
            Assertions.assertFalse(session.stepFrame());
            Assertions.assertEquals(initialHash, session.getGame().computeStateHash());
            Assertions.assertTrue(session.getStatus().contains("inspection only"));
        }
    }

    @Test
    void selection_and_camera_do_not_change_simulation_state() throws IOException {
        try (ClientSession session = this.loadSession(PAUSE_COMMAND_TYPE, 0)) {
            long initialHash = session.getGame().computeStateHash();

            session.getCamera().setViewportSize(INSPECTION_VIEWPORT_SIZE, INSPECTION_VIEWPORT_SIZE);
            session.getCamera().centerOn(SYNTHETIC_UNIT_POSITION, SYNTHETIC_UNIT_POSITION);

            ChkUnit selectedUnit = session.selectAtScreen(INSPECTION_VIEWPORT_CENTER, INSPECTION_VIEWPORT_CENTER);

            Assertions.assertNotNull(selectedUnit);
            Assertions.assertEquals(SYNTHETIC_UNIT_TYPE, selectedUnit.getUnitId());
            Assertions.assertSame(selectedUnit, session.getSelectedPlacement());
            Assertions.assertNull(session.selectAtScreen(INSPECTION_VIEWPORT_SIZE, INSPECTION_VIEWPORT_CENTER));
            Assertions.assertNull(session.selectAtScreen(Float.NaN, INSPECTION_VIEWPORT_CENTER));

            session.getCamera().zoomAt(INSPECTION_ZOOM, INSPECTION_VIEWPORT_CENTER, INSPECTION_VIEWPORT_CENTER);

            Assertions.assertSame(selectedUnit, session.selectAtScreen(INSPECTION_VIEWPORT_CENTER, INSPECTION_VIEWPORT_CENTER));

            session.clearSelection();

            Assertions.assertNull(session.getSelectedPlacement());
            Assertions.assertEquals(initialHash, session.getGame().computeStateHash());
        }
    }

    @Test
    void closed_session_cannot_step_select_or_reset() throws IOException {
        ClientSession session = this.loadSession(PAUSE_COMMAND_TYPE, COMMAND_FRAME);

        session.close();
        session.close();

        Assertions.assertFalse(session.canStep());
        Assertions.assertFalse(session.stepFrame());
        Assertions.assertNull(session.selectAtScreen(0, 0));
        Assertions.assertThrows(IllegalStateException.class, () -> session.selectPlacementForInspection(0));
        Assertions.assertThrows(IllegalStateException.class, session::reset);
        Assertions.assertEquals("Session closed.", session.getStatus());
    }

    @Test
    void session_inspects_unsigned_selection_handles_without_applying_command() throws IOException {
        byte[] payload = ByteBuffer.allocate(SELECTION_PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                                   .put((byte) SELECTION_HANDLE_COUNT).putInt(MAXIMUM_HANDLE).putInt(HIGH_BIT_HANDLE).array();

        try (ClientSession session = this.loadSession(SELECT_121_COMMAND_TYPE, COMMAND_FRAME, OTHER_ISSUER, payload)) {
            long initialHash = session.getGame().computeStateHash();
            ReplayCommand command = session.getPendingCommand();
            ReplaySelectionCommand selection = session.getPendingSelection();

            Assertions.assertNotNull(command);
            Assertions.assertEquals(COMMAND_FRAME, command.getFrame());
            Assertions.assertEquals(OTHER_ISSUER, command.getPlayerId());
            Assertions.assertEquals(SELECT_121_COMMAND_TYPE, command.getTypeId());
            Assertions.assertNotNull(selection);
            Assertions.assertEquals(ReplaySelectionCommand.Operation.REPLACE, selection.getOperation());
            Assertions.assertEquals(SELECTION_HANDLE_COUNT, selection.getHandleCount());
            Assertions.assertEquals(WIDE_HANDLE_SIZE, selection.getHandleWidthBytes());
            Assertions.assertEquals(UNSIGNED_MAXIMUM_HANDLE, selection.getHandle(0));
            Assertions.assertEquals(UNSIGNED_HIGH_BIT_HANDLE, selection.getHandle(1));
            Assertions.assertEquals(DEFAULT_UNIT_CAPACITY, session.getReplay().getLimits().getUnitCapacity());
            Assertions.assertFalse(session.getReplay().getLimits().hasExplicitSection());
            Assertions.assertNull(session.getPendingCommandError());
            Assertions.assertNull(session.getSelectedPlacement());
            Assertions.assertEquals(initialHash, session.getGame().computeStateHash());
            Assertions.assertFalse(session.stepFrame());
            Assertions.assertFalse(session.stepFrame());

            long stoppedHash = session.getGame().computeStateHash();

            Assertions.assertFalse(session.stepFrame());
            Assertions.assertEquals(stoppedHash, session.getGame().computeStateHash());
            Assertions.assertSame(command, session.getPendingCommand());
            Assertions.assertSame(selection, session.getPendingSelection());
            Assertions.assertNull(session.getSelectedPlacement());

            session.reset();

            Assertions.assertSame(command, session.getPendingCommand());
            Assertions.assertEquals(initialHash, session.getGame().computeStateHash());
        }
    }

    @Test
    void session_keeps_large_legacy_selection_with_duplicate_raw_handles_for_inspection() throws IOException {
        ByteBuffer payload = ByteBuffer.allocate(LEGACY_SELECTION_PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        payload.put((byte) LEGACY_SELECTION_HANDLE_COUNT);

        for (int handleIndex = 0; handleIndex < LEGACY_SELECTION_HANDLE_COUNT; handleIndex++) {
            payload.putShort((short) DUPLICATE_LEGACY_HANDLE);
        }

        try (ClientSession session = this.loadSession(SELECT_ADD_COMMAND_TYPE, 0, SYNTHETIC_RECORD_INDEX, payload.array())) {
            long initialHash = session.getGame().computeStateHash();
            ReplaySelectionCommand selection = session.getPendingSelection();

            Assertions.assertNotNull(selection);
            Assertions.assertEquals(ReplaySelectionCommand.Operation.ADD, selection.getOperation());
            Assertions.assertEquals(LEGACY_SELECTION_HANDLE_COUNT, selection.getHandleCount());
            Assertions.assertEquals(NARROW_HANDLE_SIZE, selection.getHandleWidthBytes());
            Assertions.assertEquals(DUPLICATE_LEGACY_HANDLE, selection.getHandle(LEGACY_SELECTION_HANDLE_COUNT - 1));
            Assertions.assertFalse(session.canStep());
            Assertions.assertFalse(session.stepFrame());
            Assertions.assertNull(session.getSelectedPlacement());
            Assertions.assertEquals(initialHash, session.getGame().computeStateHash());
        }
    }

    @Test
    void session_inspects_command_issuer_mapping_without_passing_command_barrier() throws IOException {
        try (ClientSession session = this.loadSession(PAUSE_COMMAND_TYPE, 0,
                                                      SYNTHETIC_RECORD_INDEX,
                                                      new byte[0],
                                                      HIGH_BIT_PLAYER_INDEX, SYNTHETIC_RECORD_INDEX)) {
            long initialHash = session.getGame().computeStateHash();
            ReplayCommandIssuer issuer = session.getPendingCommandIssuer();

            Assertions.assertNotNull(issuer);
            Assertions.assertEquals(ReplayCommandIssuer.Status.RESOLVED, issuer.getStatus());
            Assertions.assertEquals(SYNTHETIC_RECORD_INDEX, issuer.getRecordIndex());
            Assertions.assertEquals(HIGH_BIT_PLAYER_INDEX, issuer.getCommandPlayerIndex());
            Assertions.assertFalse(session.stepFrame());
            Assertions.assertEquals(initialHash, session.getGame().computeStateHash());

            session.reset();

            Assertions.assertNotSame(issuer, session.getPendingCommandIssuer());

            int expectedPlayerIndex = issuer.getCommandPlayerIndex();
            ReplayCommandIssuer resetIssuer = session.getPendingCommandIssuer();

            Assertions.assertNotNull(resetIssuer);
            Assertions.assertEquals(expectedPlayerIndex, resetIssuer.getCommandPlayerIndex());
            Assertions.assertEquals(initialHash, session.getGame().computeStateHash());
        }

        try (ClientSession session = this.loadSession(PAUSE_COMMAND_TYPE, 0,
                                                      SYNTHETIC_RECORD_INDEX,
                                                      new byte[0],
                                                      OTHER_PLAYER_INDEX, EXTENDED_RECORD_VALUE)) {
            ReplayCommandIssuer.Status expectedStatus = ReplayCommandIssuer.Status.UNRESOLVED_EXTENDED_RECORDS;
            ReplayCommandIssuer pendingIssuer = session.getPendingCommandIssuer();

            Assertions.assertNotNull(pendingIssuer);
            Assertions.assertEquals(expectedStatus, pendingIssuer.getStatus());
            Assertions.assertFalse(session.stepFrame());
        }
    }

    @Test
    void select_placement_for_inspection_should_not_create_or_mutate_game_units() throws IOException {
        try (ClientSession session = this.loadSession(PAUSE_COMMAND_TYPE, 0)) {
            ChkUnit placement = session.getPlacements().getFirst();
            long initialHash = session.getGame().computeStateHash();

            Assertions.assertSame(placement, session.selectPlacementForInspection(0));
            Assertions.assertSame(placement, session.getSelectedPlacement());
            Assertions.assertThrows(IndexOutOfBoundsException.class, () -> session.selectPlacementForInspection(1));
            Assertions.assertSame(placement, session.getSelectedPlacement());
            Assertions.assertEquals(initialHash, session.getGame().computeStateHash());
            Assertions.assertFalse(session.stepFrame());
        }
    }

    @Test
    void session_inspects_target_fields_without_binding_units_or_executing_orders() throws IOException {
        for (int commandType : new int[]{RIGHT_CLICK_COMMAND_TYPE, TARGETED_ORDER_COMMAND_TYPE,
                                         RIGHT_CLICK_121_COMMAND_TYPE, TARGETED_ORDER_121_COMMAND_TYPE}) {
            boolean targetedOrder = commandType == TARGETED_ORDER_COMMAND_TYPE || commandType == TARGETED_ORDER_121_COMMAND_TYPE;
            int handleWidth = commandType < FIRST_WIDE_HANDLE_COMMAND_TYPE ? NARROW_HANDLE_SIZE : WIDE_HANDLE_SIZE;
            int fieldsSize = targetedOrder ? TARGETED_ORDER_FIELDS_SIZE : RIGHT_CLICK_FIELDS_SIZE;
            ByteBuffer payload = ByteBuffer.allocate(handleWidth + fieldsSize).order(ByteOrder.LITTLE_ENDIAN);

            payload.putShort((short) TARGET_X).putShort((short) TARGET_Y);

            if (handleWidth == NARROW_HANDLE_SIZE) {
                payload.putShort((short) MAXIMUM_NARROW_HANDLE);
            } else {
                payload.putInt(MAXIMUM_HANDLE);
            }

            payload.putShort((short) TARGET_UNIT_TYPE);

            if (targetedOrder) {
                payload.put((byte) TARGET_ORDER);
            }

            payload.put((byte) QUEUE_FLAGS);

            try (ClientSession session = this.loadSession(commandType, 0, SYNTHETIC_RECORD_INDEX, payload.array())) {
                long initialHash = session.getGame().computeStateHash();
                ReplayTargetCommand target = session.getPendingTarget();

                Assertions.assertNotNull(target);
                Assertions.assertNull(session.getPendingSelection());
                Assertions.assertNull(session.getPendingCommandError());
                Assertions.assertEquals(TARGET_X, target.getX());
                Assertions.assertEquals(TARGET_Y, target.getY());
                Assertions.assertEquals(handleWidth == NARROW_HANDLE_SIZE ? UNSIGNED_MAXIMUM_NARROW_HANDLE : UNSIGNED_MAXIMUM_HANDLE,
                                        target.getTargetHandle());
                Assertions.assertEquals(handleWidth, target.getHandleWidthBytes());
                Assertions.assertEquals(TARGET_UNIT_TYPE, target.getUnitTypeIdentifier());
                Assertions.assertEquals(targetedOrder ? TARGET_ORDER : -1, target.getOrderIdentifier());
                Assertions.assertEquals(QUEUE_FLAGS, target.getQueueFlags());
                Assertions.assertFalse(session.canStep());
                Assertions.assertFalse(session.stepFrame());
                Assertions.assertNull(session.getSelectedPlacement());
                Assertions.assertEquals(initialHash, session.getGame().computeStateHash());

                session.reset();

                Assertions.assertSame(target, session.getPendingTarget());
            }
        }
    }


    /**
     * The replay and its restart start on the screen of the first human start location with the set screen size, so camera-dependent trigger
     * waits follow the recorded timing.
     */
    @Test
    void replay_starts_on_first_human_start_location(@TempDir @NotNull Path folder) throws IOException {
        Path recording = GameTestResources.getPath(GameTestResources.REPLAY_CAMPAIGN_ZERG);
        Path replay = folder.resolve(CAMPAIGN_REPLAY_FILE_NAME);

        Files.copy(recording, replay);

        try (ClientSession session = ClientSession.load(ExternalInput.INSTALLATION.getPath(), replay)) {
            session.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
            session.setInteractiveReplay(true);
            session.setScreenSize(SCREEN_WIDTH, SCREEN_HEIGHT);
            session.startReplay();

            Assertions.assertNull(session.getFailureBoundary(), session.getStatus());
            // The human of the mission is player 4 (index 3), the native replay local player of the viewer.
            Assertions.assertEquals(REPLAY_LOCAL_PLAYER, session.getTriggerLocalPlayer());
            // A 768 x 480 screen (a 1280 x 800 window at the native 480 rows) with (3360,3760) in the middle of its width and play view: the local
            // view the triggers read starts on it, and the client's camera takes it.
            Assertions.assertEquals(START_SCREEN_Y, session.getGame().getInterfaceLink().getLocalView().getScreenY());
            Assertions.assertArrayEquals(new int[]{START_SCREEN_X, START_SCREEN_Y}, session.placeStartScreen(SCREEN_WIDTH, SCREEN_HEIGHT));

            // A restart places it with the screen size of its time: each axis is clamped to the 128 x 128 map less the screen, 3760 - 500 passes
            // 4096 - 1000.
            session.setScreenSize(SCREEN_WIDTH, TALL_SCREEN_HEIGHT);
            session.reset();

            Assertions.assertEquals(CLAMPED_SCREEN_Y, session.getGame().getInterfaceLink().getLocalView().getScreenY());
            Assertions.assertArrayEquals(new int[]{START_SCREEN_X, CLAMPED_SCREEN_Y}, session.placeStartScreen(SCREEN_WIDTH, TALL_SCREEN_HEIGHT));
        }
    }

    /**
     * The viewer starts a replay at its options' speed setting, and its Play keeps that setting: the speed its frames and its trigger Waits run at.
     */
    @Test
    void replay_viewer_plays_at_its_speed_setting_after_play() throws IOException {
        Path replay = GameTestResources.getPath(GameTestResources.REPLAY_UMS_SURVIVAL);

        try (ClientSession session = ClientSession.load(ExternalInput.INSTALLATION.getPath(), replay)) {
            session.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
            session.setInteractiveReplay(true);
            session.setReplaySpeedSetting(GameSpeed.FASTEST);
            session.startReplay();

            Assertions.assertNull(session.getFailureBoundary(), session.getStatus());
            Assertions.assertEquals(GameSpeed.FASTEST_DURATION, session.getFrameDuration());

            session.playOrPauseReplay();
            advanceToFrame(session, REPLAY_SPEED_COMMAND_FRAMES, false);

            Assertions.assertEquals(REPLAY_SPEED_COMMAND_FRAMES, session.getLastCompletedFrame(), session.getStatus());
            Assertions.assertEquals(GameSpeed.FASTEST_DURATION, session.getFrameDuration());
        }
    }

    /**
     * The audio set switches the sound files (classic {@code SD/sound/} versus remastered {@code sound/}) without touching the simulation.
     */
    @Test
    void switching_audio_set_changes_sound_files_but_not_simulation() throws Exception {
        Path replay = GameTestResources.getPath(GameTestResources.REPLAY_PVP_FORTRESS);
        String remasteredRun;
        byte[] remastered;

        try (ClientSession session = ClientSession.load(ExternalInput.INSTALLATION.getPath(), replay)) {
            Assertions.assertEquals(SoundPathResolver.AudioSet.REMASTERED, session.getAudioSet());

            remastered    = session.getGameData().readSound(BUZZ_SOUND);
            remasteredRun = playFramesAndDescribeState(session);
        }

        try (ClientSession session = ClientSession.load(ExternalInput.INSTALLATION.getPath(), replay)) {
            session.setAudioSet(SoundPathResolver.AudioSet.CLASSIC);

            byte[] classic = session.getGameData().readSound(BUZZ_SOUND);

            Assertions.assertFalse(Arrays.equals(remastered, classic), "Classic and remastered Misc\\Buzz.wav are different files.");
            Assertions.assertEquals(remasteredRun, playFramesAndDescribeState(session));

            session.setAudioSet(SoundPathResolver.AudioSet.REMASTERED);

            Assertions.assertArrayEquals(remastered, session.getGameData().readSound(BUZZ_SOUND));
        }
    }

    /**
     * Playback speed and seeking cannot change replay results: muted forward seeks, backward seeks through a session restart and ordinary
     * stepping reach the same final frame and RNG state.
     */
    @Test
    void seeking_and_muted_fast_forward_reach_same_final_state() throws Exception {
        Path replay = GameTestResources.getPath(GameTestResources.REPLAY_PVP_FORTRESS);
        String straight;

        try (ClientSession session = ClientSession.load(ExternalInput.INSTALLATION.getPath(), replay)) {
            session.getCamera().setViewportSize(PLAYBACK_VIEWPORT_WIDTH, PLAYBACK_VIEWPORT_HEIGHT);
            session.startReplay();

            while (session.stepFrame(true)) {
            }

            straight = describeFinalState(session);
        }

        String seeking;

        try (ClientSession session = ClientSession.load(ExternalInput.INSTALLATION.getPath(), replay)) {
            session.getCamera().setViewportSize(PLAYBACK_VIEWPORT_WIDTH, PLAYBACK_VIEWPORT_HEIGHT);
            session.startReplay();

            long total = session.getReplay().getHeader().getFrames();

            advanceToFrame(session, total * TWO_THIRDS_MULTIPLIER / THIRD_DIVISOR, false);
            session.reset();
            advanceToFrame(session, total / THIRD_DIVISOR, false);
            advanceToFrame(session, total / HALF_DIVISOR, true);
            session.reset();
            advanceToFrame(session, total / QUARTER_DIVISOR, false);

            while (session.stepFrame(true)) {
            }

            seeking = describeFinalState(session);
        }

        Assertions.assertEquals(straight, seeking);
    }

    @Test
    void rejected_train_raises_mineral_error() throws IOException {
        try (ClientSession session = ClientSession.startCampaign(ExternalInput.INSTALLATION.getPath(),
                                                                 CAMPAIGN_MISSION, PLAYER_NAME, FASTEST_SPEED, ClientSessionScenario.GAME_SEED,
                                                                 LOCALE)) {
            startMission(session);

            Game game = session.getGame();
            int player = session.getLocalPlayer();
            Unit commandCenter = findUnit(game, player, ScrUnitIds.TERRAN_COMMAND_CENTER);
            UnitPool pool = game.getUnitSystem().getUnitPool();

            Assertions.assertNotNull(pool);

            int handle = pool.getHandle(commandCenter.getIndex());

            selectUnit(session, player, handle);
            session.stepFrame(false);

            // Fewer minerals than an SCV costs: the queue rejects the Train command.
            Player localPlayer = game.getPlayerTable().getPlayer(player);

            Assertions.assertNotNull(localPlayer);

            localPlayer.setMinerals(0);
            session.submitLocalCommand(player, TRAIN_OPCODE, new byte[]{(byte) ScrUnitIds.TERRAN_SCV, 0});

            boolean found = false;

            for (int frame = 0; frame < ERROR_WAIT_FRAMES && !found; frame++) {
                session.stepFrame(false);

                CommandErrorEvents events = game.getInterfaceLink().getCommandErrorEvents();

                for (int index = 0; index < events.getCount(); index++) {
                    CommandErrorEvents.Event event = events.get(index);

                    found |= event.getPlayer() == player && event.getTextIdentifier() == MINERALS_TEXT;
                }
            }

            Assertions.assertTrue(found, "a rejected Train shows the mineral error after the step");
            Assertions.assertEquals(player, session.getLocalPlayer());
        }
    }

    @Test
    void trained_unit_raises_unit_ready_notification() throws IOException {
        try (ClientSession session = ClientSession.startCampaign(ExternalInput.INSTALLATION.getPath(),
                                                                 CAMPAIGN_MISSION, PLAYER_NAME, FASTEST_SPEED, ClientSessionScenario.GAME_SEED,
                                                                 LOCALE)) {
            startMission(session);

            Game game = session.getGame();
            int player = session.getLocalPlayer();
            Unit commandCenter = findUnit(game, player, ScrUnitIds.TERRAN_COMMAND_CENTER);
            UnitPool pool = game.getUnitSystem().getUnitPool();

            Assertions.assertNotNull(pool);

            int handle = pool.getHandle(commandCenter.getIndex());

            selectUnit(session, player, handle);
            session.stepFrame(false);
            session.submitLocalCommand(player, TRAIN_OPCODE, new byte[]{(byte) ScrUnitIds.TERRAN_SCV, 0});

            boolean ready = false;

            for (int frame = 0; frame < TRAINING_FRAMES && !ready; frame++) {
                session.stepFrame(false);

                NotificationEvents events = game.getInterfaceLink().getNotificationEvents();

                for (int index = 0; index < events.getCount(); index++) {
                    ready |= events.get(index).getKind() == NotificationEvents.Kind.UNIT_READY && events.get(index).getOwner() == player;
                }
            }

            Assertions.assertTrue(ready, "a trained SCV raises the unit-ready notification");
        }
    }

    @Test
    void blocked_build_site_shows_placement_text_without_sound() throws IOException {
        try (ClientSession session = ClientSession.startCampaign(ExternalInput.INSTALLATION.getPath(),
                                                                 CAMPAIGN_MISSION, PLAYER_NAME, FASTEST_SPEED, ClientSessionScenario.GAME_SEED,
                                                                 LOCALE)) {
            startMission(session);

            Game game = session.getGame();
            int player = session.getLocalPlayer();
            Unit commandCenter = findUnit(game, player, ScrUnitIds.TERRAN_COMMAND_CENTER);
            Unit worker = findUnit(game, player, ScrUnitIds.TERRAN_SCV);
            UnitPool pool = game.getUnitSystem().getUnitPool();

            Assertions.assertNotNull(pool);

            int handle = pool.getHandle(worker.getIndex());

            selectUnit(session, player, handle);
            session.stepFrame(false);

            Player localPlayer = game.getPlayerTable().getPlayer(player);

            Assertions.assertNotNull(localPlayer);

            localPlayer.setMinerals(BUILDING_MINERALS);

            // A Supply Depot (2x2) on the Command Center's footprint: the blocked site raises its placement text.
            int tileX = commandCenter.getPositionX() / TILE_SIZE;
            int tileY = commandCenter.getPositionY() / TILE_SIZE;

            session.submitLocalCommand(player,
                                       BUILD_OPCODE,
                                       new byte[]{(byte) PLACE_BUILDING_ORDER, (byte) tileX, (byte) (tileX >>> Byte.SIZE), (byte) tileY,
                                                  (byte) (tileY >>> Byte.SIZE), (byte) ScrUnitIds.TERRAN_SUPPLY_DEPOT,
                                                  (byte) (ScrUnitIds.TERRAN_SUPPLY_DEPOT >>> Byte.SIZE)});

            boolean found = false;

            for (int frame = 0; frame < ERROR_WAIT_FRAMES && !found; frame++) {
                session.stepFrame(false);

                CommandErrorEvents events = game.getInterfaceLink().getCommandErrorEvents();

                for (int index = 0; index < events.getCount(); index++) {
                    CommandErrorEvents.Event event = events.get(index);

                    found |= event.getPlayer() == player && event.getTextIdentifier() == BLOCKED_TEXT &&
                             event.getSound() == CommandErrorEvents.Sound.NONE;
                }
            }

            Assertions.assertTrue(found, "a blocked build site shows the placement text without a sound");
        }
    }

    /**
     * A live game whose simulation stops (here an EUD map whose local view nobody supplies, so its first view read stops the frame) still
     * writes its replay at the stop, with every command executed before the stop and ending at the last completed frame.
     */
    @Test
    void stopped_live_game_writes_replay_up_to_stop(@TempDir @NotNull Path folder) throws IOException {
        Path gameDirectory = ExternalInput.INSTALLATION.getPath();
        Path map = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, ExternalInput.TEST_MAPS_FOLDER, EUD_MAP);

        MeleeGameSetup setup = MeleeGameSetup.loadUseMapSettings(map, MELEE_PLAYER_NAME, MELEE_PLAYER_RACE, 0, FASTEST_SPEED);

        try (ClientSession session = ClientSession.startMelee(gameDirectory, map, setup, ClientSessionScenario.GAME_SEED, null)) {
            session.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
            session.startReplay();

            int player = session.getLocalPlayer();

            // Stop (0x1A, not queued) of the empty selection: recorded when it executes on the next frame.
            Assertions.assertTrue(session.submitLocalCommand(player, STOP_COMMAND_TYPE, new byte[]{0}));

            int completed = 0;

            while (completed < MAXIMUM_STOP_FRAMES && session.stepFrame(false)) {
                completed++;
            }

            Assertions.assertTrue(completed < MAXIMUM_STOP_FRAMES, "the simulation stopped");
            Assertions.assertNotNull(session.getFailureBoundary(), session.getStatus());

            long lastFrame = session.getLastCompletedFrame();

            Assertions.assertTrue(lastFrame >= 1, "the stop comes after the command's frame");

            Path saved = folder.resolve(LAST_REPLAY_FILE_NAME);

            session.saveReplay(saved);

            ReplayFile replay = ReplayFile.parse(Files.readAllBytes(saved));

            Assertions.assertEquals(lastFrame, replay.getHeader().getFrames(), "the replay ends at the last completed frame");

            boolean recorded = false;

            for (ReplayCommand command : replay.getCommands()) {
                recorded |= command.getTypeId() == STOP_COMMAND_TYPE && command.getFrame() <= lastFrame;
            }

            Assertions.assertTrue(recorded, "the command executed before the stop is in the replay");
        }
    }

    /**
     * The replay viewer of an EUD map keeps the replay file's path and ends the game with the EUD error notice at the frame whose replay
     * branch reads an EUD address without a native region, instead of stopping or playing to the end.
     */
    @Test
    void eud_map_replay_ends_at_its_eud_failure_in_viewer(@TempDir @NotNull Path folder) throws IOException {
        Path gameDirectory = ExternalInput.INSTALLATION.getPath();
        Path map = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, ExternalInput.TEST_MAPS_FOLDER, CIVILIZATION_DEFENSE_MAP);

        Path replay = folder.resolve(LAST_REPLAY_FILE_NAME);
        MeleeGameSetup setup = MeleeGameSetup.loadUseMapSettings(map, MELEE_PLAYER_NAME, MELEE_PLAYER_RACE, 0, GameSpeed.FASTEST);

        try (ClientSession live = ClientSession.startMelee(gameDirectory, map, setup, ClientSessionScenario.GAME_SEED, null)) {
            live.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
            live.startReplay();
            supplyViewerInputs(live.getGame());

            while (live.getLastCompletedFrame() < EUD_LIVE_FRAMES && live.stepFrame(false)) {
                supplyViewerInputs(live.getGame());
            }

            Assertions.assertEquals(EUD_LIVE_FRAMES, live.getLastCompletedFrame(), live.getStatus());

            live.saveReplay(replay);
        }

        try (ClientSession viewer = ClientSession.load(gameDirectory, replay)) {
            viewer.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
            viewer.startReplay();

            Game game = viewer.getGame();

            Assertions.assertArrayEquals(replay.toAbsolutePath().normalize().toString().getBytes(SOURCE_PATH_CHARSET),
                                         game.getGameContext().getMapArchivePath(),
                                         "the game context keeps the replay file's path");

            supplyViewerInputs(game);

            while (viewer.stepFrame(false)) {
                supplyViewerInputs(game);
            }

            TriggerContext triggers = game.getTriggerContext();

            Assertions.assertNotNull(triggers);
            Assertions.assertNull(viewer.getFailureBoundary(), viewer.getStatus());
            Assertions.assertFalse(viewer.isFinished(), viewer.getStatus());
            Assertions.assertFalse(viewer.canStep(), viewer.getStatus());
            Assertions.assertEquals(EUD_FAILURE_FRAME, viewer.getLastCompletedFrame());
            TriggerEventsAssert.assertEudFailureNotices(triggers.getEvents(), EUD_FAILURE_CODE);
        }
    }

    /**
     * A saved game resumes at its saved frame and two loads continue with matching observable-state digests; the frame at which the loaded
     * game leaves the live game is reported separately.
     */
    @Test
    void saved_game_loads_at_saved_frame_and_two_loads_continue_alike(@TempDir @NotNull Path folder) throws IOException {
        Path gameDirectory = ExternalInput.INSTALLATION.getPath();
        Path map = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, MELEE_MAP_FILE);

        MeleeGameSetup setup = MeleeGameSetup.load(map, MELEE_PLAYER_NAME, SAVED_GAME_PLAYER_RACE, COMPUTER_RACE, 0, FASTEST_SPEED);

        try (CascStorage storage = CascStorage.open(gameDirectory); GameData gameData = GameData.load(storage)) {
            List<Long> stateDigests = new ArrayList<>();
            int savedFrame;
            Path file = folder.resolve(SAVE_NAME + SaveGameKind.of(setup.isExpansion(), false).getExtension());

            try (ClientSession live = ClientSession.startMelee(gameDirectory, storage, gameData, map, setup, ClientSessionScenario.GAME_SEED, null)) {
                live.setSaveDirectory(folder);
                live.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
                live.startReplay();

                while (live.getGame().getCurrentFrame() < SAVE_FRAME) {
                    Assertions.assertTrue(live.stepFrame(false), live.getStatus());
                }

                Assertions.assertTrue(live.canOpenSaveDialog(0));

                live.requestSaveGame(SAVE_NAME.getBytes(StandardCharsets.UTF_8), 0);

                int waited = 0;
                int frameBeforeStep = live.getGame().getCurrentFrame();

                while (!Files.isRegularFile(file) && waited++ < SAVE_WAIT_FRAMES) {
                    frameBeforeStep = live.getGame().getCurrentFrame();

                    Assertions.assertTrue(live.stepFrame(false), live.getStatus());
                }

                Assertions.assertTrue(Files.isRegularFile(file), "the Save Game command wrote the file");
                Assertions.assertNull(live.takeSaveFailure());

                // The file holds the game as the command found it: before the game update of the frame whose turn ran it.
                savedFrame = frameBeforeStep;
                stateDigests.add(StateDigestCheck.digestObservableState(live.getGame()));

                for (int frame = 0; frame < CONTINUE_FRAMES; frame++) {
                    Assertions.assertTrue(live.stepFrame(false), live.getStatus());
                    stateDigests.add(StateDigestCheck.digestObservableState(live.getGame()));
                }
            }

            List<Long> loadedStateDigests = loadAndContinue(storage, gameData, gameDirectory, file, savedFrame, stateDigests.size());
            List<Long> reloadedStateDigests = loadAndContinue(storage, gameData, gameDirectory, file, savedFrame, stateDigests.size());

            Assertions.assertEquals(loadedStateDigests, reloadedStateDigests, "two loads of the file continue alike");

            int departure = -1;

            for (int frame = 0; frame < stateDigests.size() && departure < 0; frame++) {
                if (!stateDigests.get(frame).equals(loadedStateDigests.get(frame))) {
                    departure = savedFrame + frame + 1;
                }
            }

            System.out.println("SAVED GAME SESSION: the loaded game leaves the saved game's observable state at frame " + departure);
        }
    }

    private @NotNull ClientSession loadSession(int commandType, int commandFrame) throws IOException {
        return this.loadSession(commandType, commandFrame, 0, new byte[0]);
    }

    private @NotNull ClientSession loadSession(int commandType, int commandFrame, int recordedIssuer, byte @NotNull [] payload) throws IOException {
        return this.loadSession(commandType, commandFrame, recordedIssuer, payload, SYNTHETIC_RECORD_INDEX, SYNTHETIC_RECORD_INDEX);
    }

    /**
     * Encodes a command block with its issuer mapping and loads the synthetic replay.
     *
     * @param commandType         the recorded command opcode
     * @param commandFrame        the recorded command frame
     * @param recordedIssuer      the recorded issuer byte
     * @param payload             the command payload
     * @param commandPlayerIndex  the player index of the synthetic record
     * @param recordedPlayerValue the value in that player record
     * @return the loaded client session
     * @throws IOException if the replay cannot be written or loaded
     */
    private @NotNull ClientSession loadSession(int commandType, int commandFrame,
                                               int recordedIssuer,
                                               byte @NotNull [] payload,
                                               int commandPlayerIndex, int recordedPlayerValue) throws IOException {
        ByteBuffer commands = ByteBuffer.allocate(COMMAND_BLOCK_HEADER_SIZE + payload.length).order(ByteOrder.LITTLE_ENDIAN);

        commands.putInt(commandFrame).put((byte) (COMMAND_PREFIX_SIZE + payload.length)).put((byte) recordedIssuer).put((byte) commandType)
                .put(payload);

        return this.loadSession(commands.array(), commandPlayerIndex, recordedPlayerValue);
    }

    /**
     * Writes the synthetic header, command block and map into a checksummed replay.
     *
     * @param commands            the encoded command block
     * @param commandPlayerIndex  the player index of the synthetic record
     * @param recordedPlayerValue the value in that player record
     * @return the loaded client session
     * @throws IOException if the replay cannot be written or loaded
     */
    private @NotNull ClientSession loadSession(byte @NotNull [] commands, int commandPlayerIndex, int recordedPlayerValue) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(REPLAY_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        header.put(ENGINE_OFFSET, (byte) BROOD_WAR_ENGINE);
        header.putInt(FRAME_COUNT_OFFSET, REPLAY_FRAMES);
        header.putShort(MAP_WIDTH_OFFSET, (short) MAP_SIZE_TILES);
        header.putShort(MAP_HEIGHT_OFFSET, (short) MAP_SIZE_TILES);

        for (int recordIndex = 0; recordIndex < PLAYER_RECORD_COUNT; recordIndex++) {
            header.putInt(PLAYER_INDEXES_OFFSET + recordIndex * PLAYER_RECORD_SIZE,
                          recordIndex == SYNTHETIC_RECORD_INDEX ? commandPlayerIndex : recordIndex);
            header.putInt(PLAYER_VALUES_OFFSET + recordIndex * PLAYER_RECORD_SIZE,
                          recordIndex == SYNTHETIC_RECORD_INDEX ? recordedPlayerValue : recordIndex);
        }

        ByteBuffer map = ByteBuffer.allocate(MAP_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        map.put(DIMENSION_SECTION.getBytes(StandardCharsets.US_ASCII)).putInt(DIMENSION_SECTION_SIZE)
           .putShort((short) MAP_SIZE_TILES).putShort((short) MAP_SIZE_TILES);
        map.put(TILESET_SECTION.getBytes(StandardCharsets.US_ASCII)).putInt(TILESET_SECTION_SIZE).putShort((short) BADLANDS_TILESET);
        map.put(TILE_SECTION.getBytes(StandardCharsets.US_ASCII)).putInt(TILE_SECTION_SIZE);
        map.position(map.position() + TILE_SECTION_SIZE);
        map.put(UNIT_SECTION.getBytes(StandardCharsets.US_ASCII)).putInt(UNIT_RECORD_SIZE);

        int unitOffset = map.position();

        map.putInt(unitOffset, UNIT_SERIAL);
        map.putShort(unitOffset + UNIT_X_OFFSET, (short) SYNTHETIC_UNIT_POSITION);
        map.putShort(unitOffset + UNIT_Y_OFFSET, (short) SYNTHETIC_UNIT_POSITION);
        map.putShort(unitOffset + UNIT_TYPE_OFFSET, (short) SYNTHETIC_UNIT_TYPE);
        map.put(unitOffset + UNIT_OWNER_OFFSET, (byte) NEUTRAL_PLAYER);

        Path replayDirectory = this.temporaryDirectory;

        Assertions.assertNotNull(replayDirectory);

        Path replayPath = replayDirectory.resolve(REPLAY_FILE_NAME);

        Files.write(replayPath, ReplayFileScenario.encode(header.array(), commands, map.array()));

        return ClientSession.load(ExternalInput.INSTALLATION.getPath(), replayPath);
    }
}
