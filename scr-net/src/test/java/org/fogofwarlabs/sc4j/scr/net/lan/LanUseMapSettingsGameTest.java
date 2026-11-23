package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommand;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.replay.GameSourceFile;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplaySession;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.SyncChecksumUtils;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderPacket;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Tests a Use Map Settings LAN game between two sc4j clients in memory, from the lobby to the played game, in which every
 * Sync command matches the receiver's own record and both simulations hash alike every frame.
 */
@RequiresInstallation
final class LanUseMapSettingsGameTest {
    private static final @NotNull InetSocketAddress HOST = new InetSocketAddress("192.168.0.88", GameProxySocket.GAME_PORT);
    private static final @NotNull InetSocketAddress JOINER = new InetSocketAddress("192.168.0.2", GameProxySocket.GAME_PORT);
    /**
     * The options of a Use Map Settings LAN game.
     */
    private static final @NotNull String OPTIONS = "0a00010000000000000000020001030100010000000000000000000000000000";
    private static final int FRAMES = 600;
    private static final int ZERGLING = ScrUnitIds.ZERG_ZERGLING;
    private static final int CLOCK_BASE = 10_000_000;
    private static final int HOST_PLAYER = 0;
    private static final int JOINER_PLAYER = 1;
    private static final int JOIN_STEPS = 3000;
    private static final int COUNTDOWN_LIMIT = 10_000;
    private static final int BRIEFING_LIMIT = 5000;
    private static final int HOST_READY_STEP = 300;
    private static final int JOINER_READY_STEP = 900;
    /**
     * The turns between the room's end and the turn frame {@code 0} executes: the game start step's and frame {@code 0}'s.
     */
    private static final int ROOM_END_TO_FIRST_FRAME = 2;
    private static final int ITERATION_LIMIT = 200_000;
    private static final int TURNS_IN_TRANSIT = 3;
    private static final int FRAME_MILLISECONDS = 42;
    private static final int VIEWPORT_WIDTH = 640;
    private static final int VIEWPORT_HEIGHT = 480;
    private static final int INSTRUCTION_LIMIT = 100_000;
    /**
     * The most units of one selection.
     */
    private static final int SELECTION_LIMIT = 12;
    /**
     * The right-click command: x, y, target handle, target unit type, queued flag.
     */
    private static final int RIGHT_CLICK_SIZE = 11;
    /**
     * The select command's unit count byte, ahead of the units' handles.
     */
    private static final int SELECT_COUNT_SIZE = 1;
    private static final int HOST_MOVE_FRAME = 40;
    private static final int JOINER_MOVE_FRAME = 41;
    private static final int HOST_SECOND_MOVE_FRAME = 200;
    /**
     * The targets in pixels of the host's move, the joiner's move and the host's second move.
     */
    private static final int HOST_MOVE_X = 1200;
    private static final int HOST_MOVE_Y = 900;
    private static final int JOINER_MOVE_X = 900;
    private static final int JOINER_MOVE_Y = 1200;
    private static final int HOST_SECOND_MOVE_X = 700;
    private static final int HOST_SECOND_MOVE_Y = 700;
    /**
     * The select and order commands of the three moves, and those of the host's two moves.
     */
    private static final int COMMANDS = 6;
    private static final int HOST_COMMANDS = 4;
    private static final int JOINER_COMMANDS = 2;

    private static final int STATE_GAME = 9;

    private static final class Datagram {
        private final @NotNull InetSocketAddress source;
        private final byte @NotNull [] packet;

        private Datagram(@NotNull InetSocketAddress source, byte @NotNull [] packet) {
            this.source = source;
            this.packet = packet;
        }
    }

    /**
     * Both sessions over in-memory datagrams, on one millisecond clock.
     */
    private static final class Network {
        private int now;

        private final @NotNull ArrayDeque<Datagram> toHost;
        private final @NotNull ArrayDeque<Datagram> toJoiner;

        private final @NotNull ProviderSession hostSession;
        private final @NotNull ProviderSession joinerSession;

        private Network() {
            this.now = CLOCK_BASE;

            this.toHost   = new ArrayDeque<>();
            this.toJoiner = new ArrayDeque<>();

            this.hostSession   = new ProviderSession((address, packet) -> this.toJoiner.add(new Datagram(HOST, packet.getEncodedBytes())),
                                                     () -> this.now);
            this.joinerSession = new ProviderSession((address, packet) -> this.toHost.add(new Datagram(JOINER, packet.getEncodedBytes())),
                                                     () -> this.now);
        }

        private void deliver() {
            while (!this.toHost.isEmpty() || !this.toJoiner.isEmpty()) {
                for (Datagram datagram = this.toHost.poll(); datagram != null; datagram = this.toHost.poll()) {
                    this.hostSession.receive(datagram.source, datagram.packet);
                }

                for (Datagram datagram = this.toJoiner.poll(); datagram != null; datagram = this.toJoiner.poll()) {
                    this.joinerSession.receive(datagram.source, datagram.packet);
                }
            }

            this.hostSession.update();
            this.joinerSession.update();
        }
    }

    /**
     * One client's game: the live game, its simulation and its own zerglings.
     */
    private static final class Client {
        private final @NotNull LiveGame live;
        private final @NotNull ReplayRuntime runtime;
        private final @NotNull Game game;

        private Client(@NotNull LiveGame live, @NotNull ReplayRuntime runtime) {
            this.live    = live;
            this.runtime = runtime;
            this.game    = runtime.getGame();
        }

        /**
         * Selects the local player's zerglings ({@code 0x63}) and orders them to a point (right click {@code 0x60}).
         *
         * @param targetX the target x in pixels
         * @param targetY the target y in pixels
         */
        private void moveZerglings(int targetX, int targetY) {
            UnitPool pool = this.game.getUnitSystem().getUnitPool();
            List<Integer> handles = new ArrayList<>();

            Assertions.assertNotNull(pool);

            for (int index = pool.getFirstCreatedIndex(true);
                 index != 0 && handles.size() < SELECTION_LIMIT;
                 index = pool.getNextCreatedIndex(index)) {
                Unit unit = pool.getUnit(index);

                if (unit.getOwnerIndex() == this.live.getLocalPlayer() && unit.getUnitId() == ZERGLING) {
                    handles.add(pool.getHandle(index));
                }
            }

            Assertions.assertFalse(handles.isEmpty(), "the local player has zerglings");

            ByteBuffer select = ByteBuffer.allocate(SELECT_COUNT_SIZE + Integer.BYTES * handles.size()).order(ByteOrder.LITTLE_ENDIAN);

            select.put((byte) handles.size());

            for (int handle : handles) {
                select.putInt(handle);
            }

            Assertions.assertTrue(this.live.submit(ReplayCommandIds.SELECT_121, select.array()));

            ByteBuffer order = ByteBuffer.allocate(RIGHT_CLICK_SIZE).order(ByteOrder.LITTLE_ENDIAN);

            order.putShort((short) targetX).putShort((short) targetY).putInt(0).putShort((short) ScrUnitIds.NONE).put((byte) 0);

            Assertions.assertTrue(this.live.submit(ReplayCommandIds.RIGHT_CLICK_121, order.array()));
        }
    }

    private static @NotNull Client startGame(@NotNull GameData data,
                                             @NotNull Network network,
                                             @NotNull LanLobby lobby, @NotNull ProviderSession session,
                                             @NotNull ChkFile scenario, byte @NotNull [] mapData,
                                             @NotNull Path mapPath, byte @NotNull [] mapFile) throws Exception {
        MeleeGameSetup setup = lobby.createGameSetup(mapData, GameSpeed.FASTEST, true);
        LiveGame live = new LiveGame(setup, lobby.getSeed(), session.getLocalIdentifier());

        live.attachNetwork(new LanTurnTransport(session, network::deliver), TURNS_IN_TRANSIT, false);

        ReplayRuntime runtime = ReplayRuntime.createLiveGame(live.getReplay(), scenario,
                                                             data,
                                                             live.getLocalPlayer(), true,
                                                             VIEWPORT_WIDTH, VIEWPORT_HEIGHT,
                                                             INSTRUCTION_LIMIT);
        Game game = runtime.getGame();

        runtime.setSourceFile(GameSourceFile.create(mapPath, mapFile));
        live.setSyncSource(game, () -> 0);
        runtime.start(0, network.now);

        Assertions.assertNull(runtime.getFailure(), () -> "startup: " + runtime.getFailure());

        ReplaySession replaySession = runtime.getSession();

        Assertions.assertNotNull(replaySession);

        live.attachReplaySession(replaySession);

        return new Client(live, runtime);
    }


    /**
     * Checks that the host creates the lobby of {@code Maps/test/zergling.scm} (a map whose eight slots are human, one force, trigger-made
     * zerglings), the joiner takes its slot, the countdown runs, both pass the ready room ({@link LanLobby#updateBriefing()}) and both games take the
     * turn stream over ({@link LanTurnTransport}). Each player then moves its zerglings; every Sync command must match the receiver's own record and
     * both simulations must hash alike every frame.
     */
    @Test
    void both_players_move_their_zerglings_and_every_sync_matches() throws Exception {
        Path mapPath = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, ExternalInput.TEST_MAPS_FOLDER, "zergling.scm");

        byte[] mapFile = Files.readAllBytes(mapPath);
        byte[] mapData = MpqArchive.open(mapPath).readFile("staredit\\scenario.chk");
        ChkFile scenario = ChkFile.parseTolerant(mapData);
        byte[] gameData = HexFormat.of().parseHex(OPTIONS);
        LanGameOptions options = LanGameOptions.parse(gameData);
        Network network = new Network();
        LanLobby.Setup setup = LanLobby.Setup.create(scenario, options, "host", "zergling.scm", mapFile.length, 0);

        network.hostSession.createGame("game", "statistics", "", PlayerTable.PLAYABLE_PLAYER_SLOTS, 0, 0, 0, gameData, "host", "");

        LanLobby host = new LanLobby(network.hostSession, () -> network.now, setup, "host", new byte[LanLobby.PLAYER_BLOCK_SIZE]);
        LanLobby joiner = null;

        network.joinerSession.beginJoin(HOST, "joiner", "", "");

        for (int step = 0; step < JOIN_STEPS; step++) {
            network.now++;
            network.deliver();

            if (joiner == null && network.joinerSession.pollJoin() == ProviderSession.JoinState.JOINED) {
                joiner = new LanLobby(network.joinerSession, () -> network.now, null, "joiner", new byte[LanLobby.PLAYER_BLOCK_SIZE]);
            }

            host.update();

            if (joiner != null) {
                joiner.update();
            }
        }

        Assertions.assertNotNull(joiner);
        Assertions.assertEquals(JOINER_PLAYER, joiner.getRecord(JOINER_PLAYER).getPlayerId(), "the joiner takes slot 1 of force 1");
        Assertions.assertTrue(host.startGame());

        for (int step = 0; step < COUNTDOWN_LIMIT && (host.getState() != STATE_GAME || joiner.getState() != STATE_GAME); step++) {
            network.now++;
            network.deliver();
            host.update();
            joiner.update();
        }

        Assertions.assertEquals(STATE_GAME, host.getState());
        Assertions.assertEquals(STATE_GAME, joiner.getState());

        // The ready room: both press Start at different times; each room ends after the same turn.
        boolean hostReady = false;
        boolean joinerReady = false;

        host.beginBriefing();
        joiner.beginBriefing();

        for (int step = 0; step < BRIEFING_LIMIT && !(hostReady && joinerReady); step++) {
            network.now++;
            network.deliver();

            if (step == HOST_READY_STEP) {
                host.readyBriefing();
            }

            if (step == JOINER_READY_STEP) {
                joiner.readyBriefing();
            }

            hostReady |= !hostReady && host.updateBriefing();
            joinerReady |= !joinerReady && joiner.updateBriefing();
        }

        Assertions.assertTrue(hostReady && joinerReady, "both ready rooms ended");

        // The rooms end on the same turn with two own turns in transit; the game start step consumes the next turn and
        // the first frame the one after.
        int roomEnd = network.hostSession.getSelf().getChannel(ProviderPacket.TURN).getExpectedSequence();
        boolean firstFrameChecked = false;

        Assertions.assertEquals(roomEnd, network.joinerSession.getSelf().getChannel(ProviderPacket.TURN).getExpectedSequence());

        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            Client hostClient = startGame(data, network, host, network.hostSession, scenario, mapData, mapPath, mapFile);
            Client joinerClient = startGame(data, network, joiner, network.joinerSession, scenario, mapData, mapPath, mapFile);
            Client[] clients = {hostClient, joinerClient};

            for (int iteration = 0; iteration < ITERATION_LIMIT && (hostClient.game.getCurrentFrame() < FRAMES ||
                                                                    joinerClient.game.getCurrentFrame() < FRAMES);
                 iteration++) {
                network.now++;

                for (Client client : clients) {
                    long frame = client.game.getCurrentFrame();

                    if (frame >= FRAMES) {
                        continue;
                    }

                    if (client == hostClient && frame == HOST_MOVE_FRAME) {
                        client.moveZerglings(HOST_MOVE_X, HOST_MOVE_Y);
                    }

                    if (client == joinerClient && frame == JOINER_MOVE_FRAME) {
                        client.moveZerglings(JOINER_MOVE_X, JOINER_MOVE_Y);
                    }

                    if (client == hostClient && frame == HOST_SECOND_MOVE_FRAME) {
                        client.moveZerglings(HOST_SECOND_MOVE_X, HOST_SECOND_MOVE_Y);
                    }

                    if (!client.live.exchangeTurn(frame)) {
                        continue;
                    }

                    if (client == hostClient && frame == 0) {
                        Assertions.assertEquals(roomEnd + ROOM_END_TO_FIRST_FRAME,
                                                network.hostSession.getSelf().getChannel(ProviderPacket.TURN).getExpectedSequence(),
                                                "frame 0 executes the room's second next turn");

                        firstFrameChecked = true;
                    }

                    client.runtime.step(FRAME_MILLISECONDS, network.now);

                    Assertions.assertNull(client.runtime.getFailure(),
                                          () -> "frame " + client.game.getCurrentFrame() + ": " + client.runtime.getFailure());
                }

                if (hostClient.game.getCurrentFrame() == joinerClient.game.getCurrentFrame()) {
                    Assertions.assertEquals(SyncChecksumUtils.calculateUnitHash(hostClient.game),
                                            SyncChecksumUtils.calculateUnitHash(joinerClient.game),
                                            "unit hash at frame " + hostClient.game.getCurrentFrame());
                }
            }

            Assertions.assertTrue(firstFrameChecked);
            Assertions.assertEquals(FRAMES, hostClient.game.getCurrentFrame());
            Assertions.assertEquals(FRAMES, joinerClient.game.getCurrentFrame());
            Assertions.assertEquals(hostClient.live.getHistory().size(), joinerClient.live.getHistory().size());
            Assertions.assertEquals(COMMANDS, hostClient.live.getHistory().size(), "three moves' select and order commands");

            // The saved replays hold every player's executed commands, the local player's own included.
            for (Client client : clients) {
                List<ReplayCommand> saved = ReplayFile.parse(client.live.writeReplay(FRAMES, 0)).getCommands();

                Assertions.assertEquals(COMMANDS, saved.size());
                Assertions.assertEquals(HOST_COMMANDS, saved.stream().filter(command -> command.getPlayerId() == HOST_PLAYER).count());
                Assertions.assertEquals(JOINER_COMMANDS, saved.stream().filter(command -> command.getPlayerId() == JOINER_PLAYER).count());
            }

            Assertions.assertEquals(0, hostClient.live.getSyncMismatches(), "the host's checks of the joiner's Syncs");
            Assertions.assertEquals(0, joinerClient.live.getSyncMismatches(), "the joiner's checks of the host's Syncs");
        }
    }
}
