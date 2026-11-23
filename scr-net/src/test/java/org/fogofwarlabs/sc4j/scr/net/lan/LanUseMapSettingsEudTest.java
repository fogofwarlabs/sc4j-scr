package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.replay.GameSourceFile;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.setup.LobbyResolution;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalInterfaceState;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Exercises a one-human EUD LAN game through lobby setup, local interface initialization and civilian choices.
 */
@RequiresInstallation
final class LanUseMapSettingsEudTest {
    /**
     * The options of a Use Map Settings LAN game.
     */
    private static final @NotNull String OPTIONS = "0a00010000000000000000020001030100010000000000000000000000000000";
    private static final int FRAMES = 5000;
    private static final int CIVILIAN = ScrUnitIds.TERRAN_CIVILIAN;
    private static final int SEED = 0x12345678;
    private static final int HUMAN_PLAYER = 0;
    private static final int FRAME_MILLISECONDS = 42;
    private static final int VIEWPORT_WIDTH = 640;
    private static final int VIEWPORT_HEIGHT = 480;
    private static final int INSTRUCTION_LIMIT = 100_000;
    /**
     * The interface selection's capacity.
     */
    private static final int SELECTION_CAPACITY = 12;
    /**
     * The distance in pixels within which the recreated civilian is searched.
     */
    private static final int CIVILIAN_SEARCH_DISTANCE = 600;
    /**
     * The select command of one unit: the count and the unit's handle.
     */
    private static final int SINGLE_SELECT_SIZE = 1 + Integer.BYTES;
    /**
     * The right-click command: x, y, target handle, target unit type, queued flag.
     */
    private static final int RIGHT_CLICK_SIZE = 11;
    private static final int SPEED_CHOICE_FRAME = 30;
    private static final int DIFFICULTY_CHOICE_FRAME = 70;
    private static final int CIVILIZATION_CHOICE_FRAME = 110;
    /**
     * The civilian's start position, where it waits for the speed choice.
     */
    private static final int CIVILIAN_START_X = 2048;
    private static final int CIVILIAN_START_Y = 848;
    /**
     * The speed choice's location (location {@code 105}), where the civilian waits for the difficulty choice.
     */
    private static final int SPEED_CHOICE_X = 2144;
    private static final int SPEED_CHOICE_Y = 336;
    /**
     * The difficulty choice's location (location {@code 113}), where the civilian waits for the civilization choice.
     */
    private static final int DIFFICULTY_CHOICE_X = 2144;
    private static final int DIFFICULTY_CHOICE_Y = 592;
    /**
     * The civilization choice the civilian walks to.
     */
    private static final int CIVILIZATION_CHOICE_X = 2032;
    private static final int CIVILIZATION_CHOICE_Y = 320;
    private static final int TIMEOUT_SECONDS = 600;

    /**
     * Selects the human's civilian near a point (the map recreates it after each choice) and right-clicks a destination.
     *
     * @param live         the live game
     * @param game         the game
     * @param sourceX      the x near which the civilian stands
     * @param sourceY      the y near which the civilian stands
     * @param destinationX the destination x
     * @param destinationY the destination y
     */
    private static void moveCivilian(@NotNull LiveGame live, @NotNull Game game, int sourceX, int sourceY, int destinationX, int destinationY) {
        UnitPool pool = game.getUnitSystem().getUnitPool();
        int handle = 0;

        Assertions.assertNotNull(pool);

        for (int index = pool.getFirstCreatedIndex(true); index != 0 && handle == 0; index = pool.getNextCreatedIndex(index)) {
            Unit unit = pool.getUnit(index);

            if (unit.getOwnerIndex() == HUMAN_PLAYER && unit.getUnitId() == CIVILIAN &&
                Math.abs(unit.getPositionX() - sourceX) < CIVILIAN_SEARCH_DISTANCE &&
                Math.abs(unit.getPositionY() - sourceY) < CIVILIAN_SEARCH_DISTANCE) {
                handle = pool.getHandle(index);
            }
        }

        Assertions.assertNotEquals(0, handle, "the human's civilian waits for the next choice");

        ByteBuffer select = ByteBuffer.allocate(SINGLE_SELECT_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        select.put((byte) 1).putInt(handle);

        Assertions.assertTrue(live.submit(ReplayCommandIds.SELECT_121, select.array()));

        ByteBuffer order = ByteBuffer.allocate(RIGHT_CLICK_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        order.putShort((short) destinationX).putShort((short) destinationY).putInt(0).putShort((short) ScrUnitIds.NONE).put((byte) 0);

        Assertions.assertTrue(live.submit(ReplayCommandIds.RIGHT_CLICK_121, order.array()));
    }

    private int clock;

    LanUseMapSettingsEudTest() {
        this.clock = 0;
    }


    @Test
    @Timeout(value = TIMEOUT_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void eud_map_runs_as_a_one_human_lan_game() throws Exception {
        Path mapPath = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, ExternalInput.TEST_MAPS_FOLDER, "civ.scx");

        byte[] mapFile = Files.readAllBytes(mapPath);
        byte[] mapData = MpqArchive.open(mapPath).readFile("staredit\\scenario.chk");
        ChkFile scenario = ChkFile.parseTolerant(mapData);
        LanGameOptions options = LanGameOptions.parse(HexFormat.of().parseHex(OPTIONS));
        LanLobby.Setup setup = LanLobby.Setup.create(scenario, options, "host", "civ.scx", mapFile.length, 0);
        MeleeGameSetup.PlayerRecord[] records = new MeleeGameSetup.PlayerRecord[LanLobby.RECORDS];
        byte[] lobbyData = new byte[LanLobby.LOBBY_DATA_SIZE];

        for (int index = 0; index < LanLobby.RECORDS; index++) {
            records[index] = setup.getRecord(index);
        }

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            lobbyData[LanLobby.LOBBY_DATA_COLORS + slot] = (byte) LanLobby.COLOR_DEFAULT;
        }

        LobbyResolution.Table table = LanLobby.createResolutionTable(LanLobby.createGameSettings(setup), records, lobbyData);
        MeleeGameSetup gameSetup = MeleeGameSetup.createNetwork(mapData, "host",
                                                                table, LanLobby.createResolutionOptions(options),
                                                                options.createTemplate(),
                                                                GameSpeed.FASTEST, true);
        LiveGame live = new LiveGame(gameSetup, SEED, HUMAN_PLAYER);

        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = ReplayRuntime.createLiveGame(live.getReplay(), scenario,
                                                                 gameData,
                                                                 live.getLocalPlayer(), true,
                                                                 VIEWPORT_WIDTH, VIEWPORT_HEIGHT,
                                                                 INSTRUCTION_LIMIT);
            Game game = runtime.getGame();

            // As the client: the game loads from the local map copy, whose path builds the Storm archive list.
            runtime.setSourceFile(GameSourceFile.create(mapPath, mapFile));
            game.getInterfaceLink().getLocalKeyStates().setAllReleased();
            this.installReferenceDisplay(game);

            // The local interface inputs the client supplies at the start (ClientSession.supplyInterfaceStart, Main): no
            // selection, no chat, no mouse button, the empty command card (228), a reference clock at zero.
            LocalInterfaceState local = game.getInterfaceLink().getLocalInterface();

            local.startInterfaceTick(0);
            local.setInterfaceSelection(new int[SELECTION_CAPACITY]);
            local.setPortraitRefresh(1);
            local.setStallStamp(0);
            local.setChatLabel(new byte[LocalInterfaceState.CHAT_LABEL_SIZE]);
            local.setStatusPanelCache(new int[LocalInterfaceState.STATUS_PANEL_CACHE_DWORDS]);
            local.setMouseButtons(0);
            local.setChatMode(0);
            local.setGluePalette(0);
            local.setDisplayedButtonSet(ButtonSetIds.EMPTY);
            game.getInterfaceLink().setOutgoingTurn(live.getOutgoingTurn());
            game.getInterfaceLink().setSessionTurnLimits(LiveGame.LATENCY_TURNS, LiveGame.MAXIMUM_TURN_BYTES);
            runtime.start(0, 0);

            Assertions.assertNull(runtime.getFailure(), () -> "startup: " + runtime.getFailure());

            int tick = 0;

            while (game.getCurrentFrame() < FRAMES) {
                long frame = game.getCurrentFrame();

                // The player's choices with the civilian: speed (location 105), difficulty (113), then a civilization. The
                // civilization choice removes the choice row, Assimilators among them, twice (removed refineries).
                if (frame == SPEED_CHOICE_FRAME) {
                    moveCivilian(live, game, CIVILIAN_START_X, CIVILIAN_START_Y, SPEED_CHOICE_X, SPEED_CHOICE_Y);
                }

                if (frame == DIFFICULTY_CHOICE_FRAME) {
                    moveCivilian(live, game, SPEED_CHOICE_X, SPEED_CHOICE_Y, DIFFICULTY_CHOICE_X, DIFFICULTY_CHOICE_Y);
                }

                if (frame == CIVILIZATION_CHOICE_FRAME) {
                    moveCivilian(live, game, DIFFICULTY_CHOICE_X, DIFFICULTY_CHOICE_Y, CIVILIZATION_CHOICE_X, CIVILIZATION_CHOICE_Y);
                }

                this.clock = (int) frame * FRAME_MILLISECONDS;

                game.getInterfaceLink().getMessageLines().expire(this.clock);
                live.exchangeTurn(frame);
                runtime.step(FRAME_MILLISECONDS, tick += FRAME_MILLISECONDS);

                Throwable failure = runtime.getFailure();

                Assertions.assertNull(failure, () -> "frame " + game.getCurrentFrame() + ": " + failure);
            }

            // The map defeats a player outside a multiplayer session in its first trigger pass.
            Player human = game.getPlayerTable().getPlayer(HUMAN_PLAYER);

            Assertions.assertNotNull(human);
            Assertions.assertEquals(0, human.getVictoryState(), "the human still plays");
        }
    }

    /**
     * Installs a deterministic message display with one line per text, a frame-based clock and enUS status texts.
     *
     * @param game the game
     * @throws IOException if an installed text file cannot be read
     */
    private void installReferenceDisplay(@NotNull Game game) throws IOException {
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
