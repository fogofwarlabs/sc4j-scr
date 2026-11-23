package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.CorpusTest;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.replay.GameSourceFile;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplaySession;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerEvents;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.*;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Exercises installed EUD maps through LAN and single-player startup, session values and local interface input.
 * <p>
 * The EUD maps are read from the installed client's maps folder ({@link ExternalInput#MAPS}, {@code scr-client/build/install/sc4j/maps}).
 */
@CorpusTest({ExternalInput.INSTALLATION, ExternalInput.MAPS})
final class LanEudSessionMapsTest {
    private static final @NotNull InetSocketAddress HOST = new InetSocketAddress("192.168.0.88", GameProxySocket.GAME_PORT);
    /**
     * The options of a Use Map Settings LAN game.
     */
    private static final @NotNull String OPTIONS = "0a00010000000000000000020001030100010000000000000000000000000000";
    private static final int FRAMES = 3000;
    /**
     * The map's choice square unit type.
     */
    private static final int CHOICE_SQUARE = ScrUnitIds.SPECIAL_UPPER_LEVEL_DOOR;
    private static final @NotNull String SNAKE_MAP = "[SCA] 뱀서류 저글링 막기(싱글) v0.07.scx";
    /**
     * The frame at which the single-player snake map reads an EUD address without a native region, which ends its game.
     */
    private static final int SNAKE_FAILURE_FRAME = 2;
    private static final @NotNull String SNOW_MAP = "[EUD]스노우 브라더스 1.2.scx";
    private static final @NotNull String SCENARIO = "staredit\\scenario.chk";
    private static final int CLOCK_BASE = 10_000_000;
    private static final int FRAME_MILLISECONDS = 42;
    private static final int TURNS_IN_TRANSIT = 3;
    private static final int VIEWPORT_WIDTH = 640;
    private static final int VIEWPORT_HEIGHT = 480;
    private static final int INSTRUCTION_LIMIT = 100_000;
    private static final int ITERATION_LIMIT = 400_000;
    private static final int LOCAL_PLAYER = 0;
    private static final int SINGLE_PLAYER_SEED = 0x12345678;
    /**
     * The classic address of the session's turns in transit.
     */
    private static final int LATENCY_TURNS_ADDRESS = 0x57F090;
    /**
     * The classic address of the session's maximum turn bytes.
     */
    private static final int MAXIMUM_TURN_BYTES_ADDRESS = 0x57F0D8;
    private static final int HEADER_GAME_NAME_OFFSET = 0x18;
    private static final int HEADER_TURN_RATE_OFFSET = 0x40;
    private static final int HEADER_HOST_NAME_OFFSET = 0x48;
    private static final int GAME_NAME_PREFIX_LENGTH = 3;
    private static final int HOST_NAME_LENGTH = 4;
    /**
     * The mask of a byte's offset inside its dword.
     */
    private static final int DWORD_OFFSET_MASK = 3;
    private static final int SPEED_TIMEOUT_EXPECTED = 1000;
    private static final int KEY_SPACE = 0x20;
    private static final int KEY_DOWN = 1;
    private static final int KEY_UP = 0;
    private static final int LEFT_BUTTON_DOWN = 2;
    private static final int SELECTION_CAPACITY = 12;
    private static final int CHOICE_SQUARE_SPREAD = 208;
    private static final int CHOICE_SETTLE_FRAMES = 60;
    /**
     * The choices the snake map offers before the test ends: the difficulty and the first level-up.
     */
    private static final int CHOICES = 2;
    /**
     * The snake map's input frames: Space at the launcher, a click on the middle square and Space for the difficulty,
     * then D and W held; each range ends before its end frame.
     */
    private static final int LAUNCHER_SPACE_FRAME = 60;
    private static final int LAUNCHER_SPACE_END = 63;
    private static final int DIFFICULTY_CLICK_FRAME = 150;
    private static final int DIFFICULTY_CLICK_END = 153;
    private static final int DIFFICULTY_SPACE_FRAME = 160;
    private static final int DIFFICULTY_SPACE_END = 163;
    private static final int WALK_RIGHT_FRAME = 300;
    private static final int WALK_UP_FRAME = 600;
    private static final int WALK_END_FRAME = 800;
    private static final int START_SCREEN_X = 1728;
    private static final int SNOW_FRAMES = 2000;
    private static final int SNOW_TITLE_FRAME = 200;
    /**
     * The snow map's K press that starts the game; the range ends before its end frame.
     */
    private static final int SNOW_KEY_FRAME = 300;
    private static final int SNOW_KEY_END = 304;
    private static final int LOBBY_STEPS = 500;
    private static final int COUNTDOWN_LIMIT = 10_000;
    private static final int BRIEFING_LIMIT = 5000;
    private static final int BRIEFING_READY_STEP = 300;
    private static final int TIMEOUT_SECONDS = 900;

    private static final int DEFAULT_TURNS_IN_TRANSIT = 2;
    private static final int DEFAULT_TURNS_PER_SECOND = 24;
    private static final int MAXIMUM_TURN_BYTES = 0x1F0;
    private static final int STATE_GAME = 9;
    /**
     * The divisor that halves a view width or height.
     */
    private static final int HALF_DIVISOR = 2;

    /**
     * One client's network game: the live game, its runtime and its game.
     */
    private static final class LanGame {
        private final @NotNull LiveGame live;
        private final @NotNull ReplayRuntime runtime;
        private final @NotNull Game game;

        private LanGame(@NotNull LiveGame live, @NotNull ReplayRuntime runtime) {
            this.live    = live;
            this.runtime = runtime;
            this.game    = runtime.getGame();
        }
    }

    private static boolean isHeld(long frame, int firstFrame, int endFrame) {
        return frame >= firstFrame && frame < endFrame;
    }

    private static void collectNotices(@NotNull Game game, @NotNull List<String> notices) {
        TriggerContext context = game.getTriggerContext();

        if (context == null) {
            return;
        }

        TriggerEvents events = context.getEvents();

        for (int index = 0; index < events.getCount(); index++) {
            if (events.getKind(index) == TriggerEvents.EUD_FAILURE_NOTICE) {
                notices.add(String.format("frame %d: EUD ERROR 0x%08X", game.getCurrentFrame(), events.getSecond(index)));
            }
        }

        events.clear();
    }

    private static @NotNull String readText(@NotNull ClassicAddressSpace space, int address, int length) {
        byte[] bytes = new byte[length];

        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (space.readDword(address + index & ~DWORD_OFFSET_MASK) >>> (address + index & DWORD_OFFSET_MASK) * Byte.SIZE);
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static @NotNull ClassicAddressSpace createAddressSpace(@NotNull Game game) {
        TriggerContext context = game.getTriggerContext();

        Assertions.assertNotNull(context);

        return ClassicAddressSpace.create(context, game);
    }

    private static void assertSpeedTimeouts(@NotNull ClassicAddressSpace space) {
        for (int speed = 0; speed <= GameSpeed.FASTEST; speed++) {
            Assertions.assertEquals(SPEED_TIMEOUT_EXPECTED,
                                    space.readDword(ClassicHostTableRegion.SPEED_TIMEOUTS + Integer.BYTES * speed),
                                    "timeout of speed " + speed);
        }

        Assertions.assertEquals(SPEED_TIMEOUT_EXPECTED, space.readDword(ClassicHostValueRegion.TURN_BUDGET));
    }


    private int now;
    private int clock;

    LanEudSessionMapsTest() {
        this.now   = CLOCK_BASE;
        this.clock = 0;
    }

    @ParameterizedTest
    @ValueSource(strings = {"[EUD]지오메트리 대쉬 1.1.scx", SNOW_MAP, "[EUD]지렁이 키우기 1.2.scx", SNAKE_MAP})
    @Timeout(value = TIMEOUT_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void map_runs_as_a_one_human_lan_game_with_lan_session_values_and_no_eud_failure(@NotNull String name) throws Exception {
        Path mapPath = ExternalInput.MAPS.resolveFile(name);

        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            LanGame lanGame = this.startLanGame(data, mapPath);
            LiveGame live = lanGame.live;
            ReplayRuntime runtime = lanGame.runtime;
            Game game = lanGame.game;
            List<String> notices = new ArrayList<>();

            for (int iteration = 0; iteration < ITERATION_LIMIT && game.getCurrentFrame() < FRAMES; iteration++) {
                this.now++;

                long frame = game.getCurrentFrame();

                if (!live.exchangeTurn(frame)) {
                    continue;
                }

                this.clock = (int) frame * FRAME_MILLISECONDS;
                game.getInterfaceLink().getMessageLines().expire(this.clock);
                runtime.step(FRAME_MILLISECONDS, this.now);

                Assertions.assertNull(runtime.getFailure(), () -> "frame " + game.getCurrentFrame() + ": " + runtime.getFailure());

                collectNotices(game, notices);
            }

            Assertions.assertEquals(FRAMES, game.getCurrentFrame());
            Assertions.assertEquals(List.of(), notices, "no native EUD failure in the LAN game");
            Assertions.assertEquals(0, live.getSyncMismatches());

            // The session regions read the LAN values: 1000 for every speed at 24 turns per second, the budget 1000, two
            // turns in transit (UDPA caps), and the header's game name, turn rate and host name.
            ClassicAddressSpace space = createAddressSpace(game);
            int gameHeader = ClassicReplayFlagRegion.GAME_HEADER_ADDRESS;

            assertSpeedTimeouts(space);

            Assertions.assertEquals(DEFAULT_TURNS_IN_TRANSIT, space.readDword(LATENCY_TURNS_ADDRESS));
            Assertions.assertEquals(MAXIMUM_TURN_BYTES, space.readDword(MAXIMUM_TURN_BYTES_ADDRESS));
            Assertions.assertEquals(DEFAULT_TURNS_PER_SECOND, space.readDword(gameHeader + HEADER_TURN_RATE_OFFSET));
            Assertions.assertEquals("lan", readText(space, gameHeader + HEADER_GAME_NAME_OFFSET, GAME_NAME_PREFIX_LENGTH));
            Assertions.assertEquals("host", readText(space, gameHeader + HEADER_HOST_NAME_OFFSET, HOST_NAME_LENGTH));

            System.out.println("LAN " + name + ": " + FRAMES + " frames, no stop, no EUD failure");
        }
    }

    /**
     * Plays {@link #SNAKE_MAP} in a one-human LAN game, with the interface following the local view as the client does: the multiplayer Center View
     * moves the screen inside the trigger pass and the previous screen position holds the screen of the last camera move, which the map reads every
     * frame. The player picks the difficulty (left click on the middle square, Space), walks right and up (D, then W) and gets a level-up; the three
     * choice squares of both choices must lie on the centered screen, around its middle.
     */
    @Test
    @Timeout(value = TIMEOUT_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void snake_map_choice_squares_stay_on_the_centered_screen() throws Exception {
        Path mapPath = ExternalInput.MAPS.resolveFile(SNAKE_MAP);

        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            LanGame lanGame = this.startLanGame(data, mapPath);
            Game game = lanGame.game;
            LocalView view = game.getInterfaceLink().getLocalView();
            int width = VIEWPORT_WIDTH;
            int height = VIEWPORT_HEIGHT;
            int choices = 0;
            int squaresSince = -1;

            view.setScreenGeometry(width, height,
                                   game.getMap().getWidth() * TerrainTileset.TILE_SIZE - width,
                                   game.getMap().getHeight() * TerrainTileset.TILE_SIZE - height);

            for (int iteration = 0; iteration < ITERATION_LIMIT && game.getCurrentFrame() < FRAMES && choices < CHOICES; iteration++) {
                this.now++;

                long frame = game.getCurrentFrame();

                if (!lanGame.live.exchangeTurn(frame)) {
                    continue;
                }

                this.clock = (int) frame * FRAME_MILLISECONDS;
                game.getInterfaceLink().getMessageLines().expire(this.clock);

                // Space at the launcher, a click on the middle square and Space for the difficulty, then D and W held.
                boolean spaceHeld = isHeld(frame, LAUNCHER_SPACE_FRAME, LAUNCHER_SPACE_END) ||
                                    isHeld(frame, DIFFICULTY_SPACE_FRAME, DIFFICULTY_SPACE_END);
                boolean clickHeld = isHeld(frame, DIFFICULTY_CLICK_FRAME, DIFFICULTY_CLICK_END);

                game.getInterfaceLink().getLocalKeyStates().setState(KEY_SPACE, spaceHeld ? KEY_DOWN : KEY_UP);
                game.getInterfaceLink().getLocalKeyStates().setState('D', isHeld(frame, WALK_RIGHT_FRAME, WALK_UP_FRAME) ? KEY_DOWN : KEY_UP);
                game.getInterfaceLink().getLocalKeyStates().setState('W', isHeld(frame, WALK_UP_FRAME, WALK_END_FRAME) ? KEY_DOWN : KEY_UP);
                game.getInterfaceLink().getLocalInterface().setMouseButtons(clickHeld ? LEFT_BUTTON_DOWN : 0);

                // The client's camera follows the moved local view (Main.applyLocalViewWrite) and supplies it again.
                view.takeWritten();
                view.setView(view.getScreenX(), view.getScreenY(), width / HALF_DIVISOR, height / HALF_DIVISOR);
                lanGame.runtime.step(FRAME_MILLISECONDS, this.now);

                Assertions.assertNull(lanGame.runtime.getFailure(), () -> "frame " + game.getCurrentFrame() + ": " + lanGame.runtime.getFailure());

                List<Unit> squares = new ArrayList<>();
                UnitPool pool = game.getUnitSystem().getUnitPool();

                Assertions.assertNotNull(pool);

                for (int index = pool.getFirstCreatedIndex(true); index != 0; index = pool.getNextCreatedIndex(index)) {
                    if (pool.getUnit(index).getOwnerIndex() == LOCAL_PLAYER && pool.getUnit(index).getUnitId() == CHOICE_SQUARE) {
                        squares.add(pool.getUnit(index));
                    }
                }

                if (squares.isEmpty()) {
                    squaresSince = -1;

                    continue;
                }

                if (squaresSince < 0) {
                    squaresSince = (int) frame;
                }

                // The squares rise into place; 60 frames later they stand on the screen's middle row.
                if (frame - squaresSince != CHOICE_SETTLE_FRAMES) {
                    continue;
                }

                choices++;

                int middleX = view.getScreenX() + width / HALF_DIVISOR;
                int middleY = view.getScreenY() + height / HALF_DIVISOR;
                ClassicAddressSpace space = createAddressSpace(game);

                Assertions.assertEquals(view.getScreenX(), space.readDword(LocalView.PREVIOUS_SCREEN_X_ADDRESS),
                                        "moved screen X of choice " + choices);
                Assertions.assertEquals(view.getScreenY(), space.readDword(LocalView.PREVIOUS_SCREEN_Y_ADDRESS),
                                        "moved screen Y of choice " + choices);

                for (Unit square : squares) {
                    Assertions.assertTrue(Math.abs(square.getPositionX() - middleX) <= CHOICE_SQUARE_SPREAD && square.getPositionY() == middleY,
                                          "choice " + choices + " square at " + square.getPositionX() + "," + square.getPositionY() +
                                          " on the screen middle " + middleX + "," + middleY);
                }
            }

            Assertions.assertEquals(CHOICES, choices, "the difficulty and the first level-up choice");
            Assertions.assertNotEquals(START_SCREEN_X, view.getScreenX(), "the marine walked away from the start before the level-up");
        }
    }

    /**
     * Plays {@link #SNOW_MAP} in a one-human LAN game past its title screen, with the interface supplying only what the client supplies (the view
     * each frame, which also starts the alert screen position): the map copies the moved screen position into the alert position from frame {@code 1}
     * on; K starts the game and the camera moves to the stage.
     */
    @Test
    @Timeout(value = TIMEOUT_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void snow_map_starts_its_first_stage_as_a_one_human_lan_game() throws Exception {
        Path mapPath = ExternalInput.MAPS.resolveFile(SNOW_MAP);

        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            LanGame lanGame = this.startLanGame(data, mapPath);
            Game game = lanGame.game;
            LocalView view = game.getInterfaceLink().getLocalView();
            int width = VIEWPORT_WIDTH;
            int height = VIEWPORT_HEIGHT;
            int titleX = -1;
            int titleY = -1;
            List<String> notices = new ArrayList<>();

            view.setScreenGeometry(width, height,
                                   game.getMap().getWidth() * TerrainTileset.TILE_SIZE - width,
                                   game.getMap().getHeight() * TerrainTileset.TILE_SIZE - height);

            for (int iteration = 0; iteration < ITERATION_LIMIT && game.getCurrentFrame() < SNOW_FRAMES; iteration++) {
                this.now++;

                long frame = game.getCurrentFrame();

                if (!lanGame.live.exchangeTurn(frame)) {
                    continue;
                }

                this.clock = (int) frame * FRAME_MILLISECONDS;
                game.getInterfaceLink().getMessageLines().expire(this.clock);
                game.getInterfaceLink().getLocalKeyStates().setState('K', isHeld(frame, SNOW_KEY_FRAME, SNOW_KEY_END) ? KEY_DOWN : KEY_UP);
                view.takeWritten();
                view.setView(view.getScreenX(), view.getScreenY(), width / HALF_DIVISOR, height / HALF_DIVISOR);
                lanGame.runtime.step(FRAME_MILLISECONDS, this.now);

                Assertions.assertNull(lanGame.runtime.getFailure(), () -> "frame " + game.getCurrentFrame() + ": " + lanGame.runtime.getFailure());

                collectNotices(game, notices);

                if (frame == SNOW_TITLE_FRAME) {
                    titleX = view.getScreenX();
                    titleY = view.getScreenY();

                    Assertions.assertEquals(titleX, view.getAlertScreenX(), "the map copies the moved screen into the alert position");
                    Assertions.assertEquals(titleY, view.getAlertScreenY());
                }
            }

            Assertions.assertEquals(SNOW_FRAMES, game.getCurrentFrame());
            Assertions.assertEquals(List.of(), notices, "no native EUD failure");
            Assertions.assertTrue(view.getScreenX() != titleX || view.getScreenY() != titleY,
                                  "the first stage moved the camera off the title screen");
        }
    }

    /**
     * Exercises selected single-player maps and compares their collected EUD failure notices; a failure ends its game.
     *
     * @param name the map file name
     */
    @ParameterizedTest
    @ValueSource(strings = {"[EUD]지오메트리 대쉬 1.1.scx", "[EUD]지렁이 키우기 1.2.scx", SNAKE_MAP})
    @Timeout(value = TIMEOUT_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void map_runs_in_single_player_with_its_native_eud_failure_notices(@NotNull String name) throws Exception {
        Path mapPath = ExternalInput.MAPS.resolveFile(name);

        byte[] mapFile = Files.readAllBytes(mapPath);
        byte[] mapData = MpqArchive.open(mapPath).readFile(SCENARIO);
        ChkFile scenario = ChkFile.parseTolerant(mapData);
        LiveGame live = new LiveGame(MeleeGameSetup.createUseMapSettings(mapData, "player", ScrRaceIds.TERRAN, 0, GameSpeed.FASTEST),
                                     SINGLE_PLAYER_SEED);

        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = ReplayRuntime.createLiveGame(live.getReplay(), scenario,
                                                                 data,
                                                                 live.getLocalPlayer(), false,
                                                                 VIEWPORT_WIDTH, VIEWPORT_HEIGHT,
                                                                 INSTRUCTION_LIMIT);
            Game game = runtime.getGame();

            this.prepareLocalInterface(game);
            runtime.setSourceFile(GameSourceFile.create(mapPath, mapFile));
            live.startSession(game, runtime.getFrameTiming());
            runtime.start(0, 0);

            Assertions.assertNull(runtime.getFailure(), () -> "startup: " + runtime.getFailure());

            ReplaySession session = runtime.getSession();
            List<String> notices = new ArrayList<>();
            int tick = 0;

            Assertions.assertNotNull(session);

            while (game.getCurrentFrame() < FRAMES && !session.isEnded()) {
                long frame = game.getCurrentFrame();

                this.clock = (int) frame * FRAME_MILLISECONDS;
                game.getInterfaceLink().getMessageLines().expire(this.clock);
                live.exchangeTurn(frame);
                runtime.step(FRAME_MILLISECONDS, tick += FRAME_MILLISECONDS);

                Assertions.assertNull(runtime.getFailure(), () -> "frame " + game.getCurrentFrame() + ": " + runtime.getFailure());

                collectNotices(game, notices);
            }

            ClassicAddressSpace space = createAddressSpace(game);

            assertSpeedTimeouts(space);

            Assertions.assertEquals(1, space.readDword(LATENCY_TURNS_ADDRESS));

            // The native EUD failure ends the game at its frame.
            boolean failing = name.contains("뱀서류");
            List<String> expected = failing ? List.of("frame 2: EUD ERROR 0xFFFFFFFF") : List.of();

            Assertions.assertEquals(expected, notices, "the native EUD failures of single player");
            Assertions.assertEquals(failing, session.isEnded());
            Assertions.assertEquals(failing ? SNAKE_FAILURE_FRAME : FRAMES, game.getCurrentFrame());

            System.out.println("SP " + name + ": " + game.getCurrentFrame() + " frames, EUD failure notices " + notices);
        }
    }

    /**
     * Starts a one-human LAN game through the in-memory lobby, countdown, ready room and live runtime.
     *
     * @param data    the installed game resources
     * @param mapPath the map file
     * @return the started game
     * @throws Exception if the map cannot be read or the game cannot start
     */
    private @NotNull LanGame startLanGame(@NotNull GameData data, @NotNull Path mapPath) throws Exception {
        String name = mapPath.getFileName().toString();
        byte[] mapFile = Files.readAllBytes(mapPath);
        byte[] mapData = MpqArchive.open(mapPath).readFile(SCENARIO);
        ChkFile scenario = ChkFile.parseTolerant(mapData);
        byte[] gameData = HexFormat.of().parseHex(OPTIONS);
        LanGameOptions options = LanGameOptions.parse(gameData);
        ArrayDeque<byte[]> loopback = new ArrayDeque<>();
        ProviderSession session = new ProviderSession((address, packet) -> loopback.add(packet.getEncodedBytes()), () -> this.now);
        Runnable deliver = () -> {
            for (byte[] packet = loopback.poll(); packet != null; packet = loopback.poll()) {
                session.receive(HOST, packet);
            }

            session.update();
        };
        LanLobby.Setup setup = LanLobby.Setup.create(scenario, options, "host", name, mapFile.length, 0);

        session.createGame("lan game", "statistics", "", PlayerTable.PLAYABLE_PLAYER_SLOTS, 0, 0, 0, gameData, "host", "");

        LanLobby lobby = new LanLobby(session, () -> this.now, setup, "host", new byte[LanLobby.PLAYER_BLOCK_SIZE]);

        lobby.setTurnRate(ProviderSession.DEFAULT_TURNS_PER_SECOND);

        for (int step = 0; step < LOBBY_STEPS; step++) {
            this.now++;
            deliver.run();
            lobby.update();
        }

        Assertions.assertTrue(lobby.startGame());

        for (int step = 0; step < COUNTDOWN_LIMIT && lobby.getState() != STATE_GAME; step++) {
            this.now++;
            deliver.run();
            lobby.update();
        }

        Assertions.assertEquals(STATE_GAME, lobby.getState());

        lobby.beginBriefing();

        boolean ready = false;

        for (int step = 0; step < BRIEFING_LIMIT && !ready; step++) {
            this.now++;
            deliver.run();

            if (step == BRIEFING_READY_STEP) {
                lobby.readyBriefing();
            }

            ready = lobby.updateBriefing();
        }

        Assertions.assertTrue(ready, "the ready room ended");

        LiveGame live = new LiveGame(lobby.createGameSetup(mapData, GameSpeed.FASTEST, true), lobby.getSeed(), session.getLocalIdentifier());

        live.attachNetwork(new LanTurnTransport(session, deliver), TURNS_IN_TRANSIT, false);

        ReplayRuntime runtime = ReplayRuntime.createLiveGame(live.getReplay(), scenario,
                                                             data,
                                                             live.getLocalPlayer(), true,
                                                             VIEWPORT_WIDTH, VIEWPORT_HEIGHT,
                                                             INSTRUCTION_LIMIT);
        Game game = runtime.getGame();

        this.prepareLocalInterface(game);
        runtime.setSourceFile(GameSourceFile.create(mapPath, mapFile));
        live.startSession(game, runtime.getFrameTiming());
        live.setSyncSource(game, () -> 0);
        runtime.start(0, this.now);

        Assertions.assertNull(runtime.getFailure(), () -> "startup: " + runtime.getFailure());

        ReplaySession replaySession = runtime.getSession();

        Assertions.assertNotNull(replaySession);

        live.attachReplaySession(replaySession);

        return new LanGame(live, runtime);
    }

    /**
     * Supplies the local interface inputs, then installs a deterministic frame-clock message display.
     *
     * @param game the game
     * @throws IOException if an installed text file cannot be read
     */
    private void prepareLocalInterface(@NotNull Game game) throws IOException {
        game.getInterfaceLink().getLocalKeyStates().setAllReleased();

        // As the client: the first view also starts the alert screen position; nothing supplies it apart.
        game.getInterfaceLink().getLocalView().setView(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);

        LocalInterfaceState localInterface = game.getInterfaceLink().getLocalInterface();

        localInterface.startInterfaceTick(0);
        localInterface.setInterfaceSelection(new int[SELECTION_CAPACITY]);
        localInterface.setPortraitRefresh(1);
        localInterface.setStallStamp(0);
        localInterface.setChatLabel(new byte[LocalInterfaceState.CHAT_LABEL_SIZE]);
        localInterface.setStatusPanelCache(new int[LocalInterfaceState.STATUS_PANEL_CACHE_DWORDS]);
        localInterface.setMouseButtons(0);
        localInterface.setChatMode(0);
        localInterface.setGluePalette(0);
        localInterface.setDisplayedButtonSet(ButtonSetIds.EMPTY);

        MessageLines lines = game.getInterfaceLink().getMessageLines();
        Map<String, byte[]> keyed = new HashMap<>();
        List<byte[]> numbered = new ArrayList<>();

        lines.setLayout(text -> new int[]{text.length});
        lines.setHostClock(() -> this.clock);
        lines.setSubtitles(Boolean.TRUE);
        lines.setDepartureFormats("%s has left the game.", "%s was dropped from the game.");

        GameData networkGameData = game.getGameData();

        Assertions.assertNotNull(networkGameData);

        if (JsonReader.parse(networkGameData.readInstalledFile("locales/enUS/Assets/rez/network.json")) instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map && map.get("Key") instanceof String key && map.get("Value") instanceof String value) {
                    keyed.put(key, value.getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        GameData statusGameData = game.getGameData();

        Assertions.assertNotNull(statusGameData);

        if (JsonReader.parse(statusGameData.readInstalledFile("locales/enUS/Assets/rez/stat_txt.json")) instanceof List<?> entries) {
            for (Object entry : entries) {
                numbered.add(entry instanceof Map<?, ?> map && map.get("Value") instanceof String value
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
    }
}
