package org.fogofwarlabs.sc4j.scr.game.session;

import org.fogofwarlabs.sc4j.engine.lockstep.LockstepTransport;
import org.fogofwarlabs.sc4j.engine.lockstep.PeerTurn;
import org.fogofwarlabs.sc4j.engine.lockstep.TurnRate;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommand;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplaySession;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.LockstepMapGameScenario;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks multiplayer frame waits, turns in transit, sender identifiers, the frames that exchange turns and the drawing values their Sync records take
 * with an in-memory {@link LockstepTransport}, and a game of several human peers in one process ({@link LockstepMapGameScenario}).
 */
@RequiresInstallation
final class LiveGameNetworkTest {
    private static final int NETWORK_PLAYER_COUNT = 2;
    private static final int TURNS_IN_TRANSIT = 4;
    private static final int EXCHANGE_FRAME_LIMIT = 8;
    /**
     * A Stop, a command type without a dispatcher case and a second Stop, appended to a received turn.
     */
    private static final byte @NotNull [] TURN_TAIL = {ReplayCommandIds.STOP, 0, ReplayCommandIds.JOINED_GAME, ReplayCommandIds.STOP, 0};
    /**
     * A network game's turns per second below the provider's 24: at Fastest a turn step every 2976 / 1000 frames.
     */
    private static final int LOW_TURN_RATE = 8;
    private static final int LOOPBACK_TURNS_IN_TRANSIT = 2;
    private static final int SCHEDULED_FRAMES = 10;
    /**
     * The selection history record a player's first selection takes.
     */
    private static final int FIRST_HISTORY_RECORD = 7;
    private static final int SELECTED_WORKERS = 2;
    /**
     * The camera's top tile row in frame 0; it moves down {@link #CAMERA_ROW_STEP} rows a frame.
     */
    private static final int CAMERA_ROW_BASE = 20;
    private static final int CAMERA_ROW_STEP = 3;
    /**
     * The rows the first drawn row of a Sync record lies above the camera's top tile row.
     */
    private static final int FIRST_ROW_MARGIN = 4;
    /**
     * The Sync record of the second scheduled turn step (frame 3): records 1 and 2 are made at load, record 3 in frame 0.
     */
    private static final int SECOND_STEP_RECORD = 4;
    /**
     * The frame whose drawing is the first after the turn step of frame 0.
     */
    private static final int FIRST_DRAWING_FRAME = 1;
    private static final int SYNC_RECORD_INDEX_BYTE = 1;
    private static final int SYNC_RECORD_INDEX_SHIFT = 4;
    private static final int SYNC_FIRST_ROW_BYTE = 5;
    /**
     * The command types of the Keep Alive, Stop and Select commands.
     */
    private static final int KEEP_ALIVE_COMMAND_TYPE = 0x05;
    private static final int STOP_COMMAND_TYPE = 0x1A;
    private static final int SELECT_COMMAND_TYPE = 0x63;

    private static final @NotNull String PLAYER_NAME = "tester";
    private static final @NotNull String HOST_NAME = "host";
    private static final @NotNull String JOINER_NAME = "joiner";
    /**
     * The installed map, the seed and the frames of the game of several peers.
     */
    private static final @NotNull String BOTTLENECK_MAP = "(2)Bottleneck.scm";
    private static final int LOCKSTEP_SEED = 0x2F6A11;
    private static final int LOCKSTEP_FRAMES = 24;

    /**
     * A network of one player at {@link #LOW_TURN_RATE} turns per second whose sent turns come back as its own.
     */
    private static final class SlowLoopback implements LockstepTransport {
        private final @NotNull List<byte[]> sent;

        private int received;

        /**
         * Creates a {@link SlowLoopback} instance with an empty ordered turn list.
         */
        private SlowLoopback() {
            this.sent = new ArrayList<>();

            this.received = 0;
        }

        /**
         * @return the local turns sent and not yet received back
         */
        @Override
        public int getTurnsInTransit() {
            return this.sent.size() - this.received;
        }

        /**
         * @return the turn rate of the session and of the network it runs on, {@link TurnRate#UNSPECIFIED} for a transport that names none
         */
        @Override
        public @NotNull TurnRate getTurnRate() {
            return new TurnRate(LOW_TURN_RATE, 0, 0);
        }


        /**
         * Sends the local player's next turn.
         *
         * @param turn the turn bytes
         */
        @Override
        public void sendTurn(byte @NotNull [] turn) {
            this.sent.add(turn);
        }

        /**
         * Takes the next turn of every participant once all of them arrived.
         * <p>
         * This fixture takes the oldest local turn not yet received.
         *
         * @return the participants' turns in execution order, or {@code null} while a participant's turn is missing
         */
        @Override
        public @Nullable List<@NotNull PeerTurn> receiveTurns() {
            if (this.received >= this.sent.size()) {
                return null;
            }

            return List.of(new PeerTurn(NetworkPeerUtils.getPeer(LiveGame.HUMAN_PLAYER_ID), this.sent.get(this.received++)));
        }
    }

    /**
     * Both players' sent turns, in order.
     */
    private static final class Network {
        private final @NotNull List<List<byte[]>> sent;
        private final int @NotNull [] received;

        /**
         * Creates a {@link Network} instance retaining both players' ordered turns.
         */
        private Network() {
            this.sent     = List.of(new ArrayList<>(), new ArrayList<>());
            this.received = new int[NETWORK_PLAYER_COUNT];
        }

        /**
         * Creates a {@link LockstepTransport} instance for the requested player.
         *
         * @param player the transport player index
         * @return the created {@link LockstepTransport} instance for the requested player
         */
        private @NotNull LockstepTransport createTransport(int player) {
            return new LockstepTransport() {
                /**
                 * @return the local turns sent and not yet received back
                 */
                @Override
                public int getTurnsInTransit() {
                    return Network.this.sent.get(player).size() - Network.this.received[player];
                }


                /**
                 * Sends the local player's next turn.
                 *
                 * @param turn the turn bytes
                 */
                @Override
                public void sendTurn(byte @NotNull [] turn) {
                    Network.this.sent.get(player).add(turn);
                }

                /**
                 * Takes the next turn of every participant once all of them arrived.
                 * <p>
                 * This fixture takes both players' next turns, the joiner's first.
                 *
                 * @return the participants' turns in execution order, or {@code null} while a participant's turn is missing
                 */
                @Override
                public @Nullable List<@NotNull PeerTurn> receiveTurns() {
                    int next = Network.this.received[player];

                    if (Network.this.sent.getFirst().size() <= next || Network.this.sent.get(1).size() <= next) {
                        return null;
                    }

                    Network.this.received[player]++;

                    return List.of(new PeerTurn(NetworkPeerUtils.getPeer(1), Network.this.sent.get(1).get(next)),
                                   new PeerTurn(NetworkPeerUtils.getPeer(0), Network.this.sent.getFirst().get(next)));
                }
            };
        }
    }

    @Test
    void frames_wait_for_every_player_and_record_the_senders_commands() throws IOException {
        byte[] mapData = LiveSessionScenario.readBottleneckScenario();
        Network network = new Network();
        LiveGame host = new LiveGame(MeleeGameSetup.createUseMapSettings(mapData, HOST_NAME, ScrRaceIds.TERRAN, 0, GameSpeed.FASTEST), 1);
        LiveGame joiner = new LiveGame(MeleeGameSetup.createUseMapSettings(mapData, JOINER_NAME, ScrRaceIds.TERRAN, 0, GameSpeed.FASTEST), 1);

        host.attachNetwork(network.createTransport(0), TURNS_IN_TRANSIT, true);
        joiner.attachNetwork(network.createTransport(1), TURNS_IN_TRANSIT, false);

        // Alone, the host tops up its four turns in transit and waits.
        Assertions.assertFalse(host.exchangeTurn(0));
        Assertions.assertEquals(4, network.sent.getFirst().size());
        Assertions.assertArrayEquals(new byte[]{KEEP_ALIVE_COMMAND_TYPE},
                                     network.sent.getFirst().getFirst(),
                                     "the host sends Keep Alive instead of Sync");

        // The joiner's first exchange sends its four turns, runs the game start step (turn 0) and completes frame 0 (turn 1); the host's then
        // completes too.
        Assertions.assertTrue(joiner.exchangeTurn(0));
        Assertions.assertTrue(host.exchangeTurn(0));
        Assertions.assertEquals(6, network.sent.getFirst().size(), "one turn per completed step keeps four in transit");

        // A joiner command queued in frame 1 travels in its next turn, four turns later, and both record it as player 1's.
        Assertions.assertTrue(joiner.submit(ReplayCommandIds.STOP, new byte[]{0}));

        for (long frame = 1; frame < EXCHANGE_FRAME_LIMIT; frame++) {
            Assertions.assertTrue(joiner.exchangeTurn(frame));
            Assertions.assertTrue(host.exchangeTurn(frame));
        }

        List<ReplayCommand> hostHistory = host.getHistory();

        Assertions.assertEquals(1, hostHistory.size());
        Assertions.assertEquals(1, hostHistory.getFirst().getPlayerId());
        Assertions.assertEquals(STOP_COMMAND_TYPE, hostHistory.getFirst().getTypeId());
        // Queued before the exchange of frame 1, sent as the joiner's turn 6 (four in transit), executed in frame 5 (frame F executes turn F + 1
        // after the start step).
        Assertions.assertEquals(5, hostHistory.getFirst().getFrame());

        List<ReplayCommand> joinerHistory = joiner.getHistory();

        Assertions.assertEquals(hostHistory.size(), joinerHistory.size());

        for (int index = 0; index < hostHistory.size(); index++) {
            Assertions.assertEquals(hostHistory.get(index).getFrame(), joinerHistory.get(index).getFrame());
            Assertions.assertEquals(hostHistory.get(index).getPlayerId(), joinerHistory.get(index).getPlayerId());
            Assertions.assertEquals(hostHistory.get(index).getTypeId(), joinerHistory.get(index).getTypeId());
        }
    }

    @Test
    void network_game_below_twenty_four_turns_per_second_exchanges_turns_only_in_turn_steps() throws IOException {
        byte[] mapData = LiveSessionScenario.readBottleneckScenario();

        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            LiveGame game = new LiveGame(MeleeGameSetup.createUseMapSettings(mapData, PLAYER_NAME, ScrRaceIds.TERRAN, 0, GameSpeed.FASTEST), 1);
            SlowLoopback network = new SlowLoopback();
            List<Integer> turnFrames = new ArrayList<>();

            game.attachNetwork(network, LOOPBACK_TURNS_IN_TRANSIT, true);

            ReplayRuntime live = ReplayRuntime.createLiveGame(game.getReplay(), ChkFile.parseTolerant(mapData), gameData,
                                                              game.getLocalPlayer(),
                                                              RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT,
                                                              RunningGameScenario.INSTRUCTION_LIMIT);

            live.start(0, 0);
            game.startSession(live.getGame(), live.getFrameTiming());

            for (int iteration = 0; iteration < SCHEDULED_FRAMES; iteration++) {
                int frame = live.getGame().getCurrentFrame();
                int received = network.received;

                Assertions.assertTrue(game.exchangeTurn(frame));

                if (network.received != received) {
                    turnFrames.add(frame);
                }

                Assertions.assertTrue(live.step(GameSpeed.FASTEST_DURATION, iteration * GameSpeed.FASTEST_DURATION));
            }

            Assertions.assertEquals(SCHEDULED_FRAMES, live.getGame().getCurrentFrame(), "Every frame runs, with or without a turn.");
            Assertions.assertEquals(List.of(0, 3, 6, 9), turnFrames, "A turn step every 2976 / 1000 frames, the remainder carried.");
            Assertions.assertEquals(1 + turnFrames.size(), live.getGame().getTurnCount(), "The startup turn step and the four turn steps.");
        }
    }

    @Test
    void sync_record_below_twenty_four_turns_per_second_takes_the_first_drawing_after_the_previous_turn_step() throws IOException {
        byte[] mapData = LiveSessionScenario.readBottleneckScenario();

        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            LiveGame game = new LiveGame(MeleeGameSetup.createUseMapSettings(mapData, PLAYER_NAME, ScrRaceIds.TERRAN, 0, GameSpeed.FASTEST), 1);
            SlowLoopback network = new SlowLoopback();

            // Without Keep Alive turns, every turn sent carries the newest Sync record.
            game.attachNetwork(network, LOOPBACK_TURNS_IN_TRANSIT, false);

            ReplayRuntime live = ReplayRuntime.createLiveGame(game.getReplay(), ChkFile.parseTolerant(mapData), gameData,
                                                              game.getLocalPlayer(),
                                                              RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT,
                                                              RunningGameScenario.INSTRUCTION_LIMIT);

            live.start(0, 0);
            game.startSession(live.getGame(), live.getFrameTiming());
            game.setSyncSource(live.getGame(), () -> CAMERA_ROW_BASE + live.getGame().getCurrentFrame() * CAMERA_ROW_STEP);

            ReplaySession session = live.getSession();

            Assertions.assertNotNull(session);

            game.attachReplaySession(session);

            for (int iteration = 0; iteration < SCHEDULED_FRAMES; iteration++) {
                Assertions.assertTrue(game.exchangeTurn(live.getGame().getCurrentFrame()));
                Assertions.assertTrue(live.step(GameSpeed.FASTEST_DURATION, iteration * GameSpeed.FASTEST_DURATION));
            }

            byte[] sync = network.sent.stream()
                                      .filter(turn -> turn.length >= OutgoingTurnBuffer.SYNC_COMMAND_LENGTH &&
                                                      turn[0] == OutgoingTurnBuffer.SYNC_COMMAND)
                                      .filter(turn -> Byte.toUnsignedInt(turn[SYNC_RECORD_INDEX_BYTE]) >>> SYNC_RECORD_INDEX_SHIFT ==
                                                      SECOND_STEP_RECORD)
                                      .findFirst()
                                      .orElseThrow();

            // Frame 3's record takes the camera of frame 1's drawing, the first after the turn step of frame 0.
            Assertions.assertEquals(CAMERA_ROW_BASE + FIRST_DRAWING_FRAME * CAMERA_ROW_STEP - FIRST_ROW_MARGIN, sync[SYNC_FIRST_ROW_BYTE]);
        }
    }

    @Test
    void startup_turn_runs_the_commands_queued_before_the_game_start_in_the_startup_turn_step() throws IOException {
        byte[] mapData = LiveSessionScenario.readBottleneckScenario();

        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            LiveGame game = new LiveGame(MeleeGameSetup.createUseMapSettings(mapData, PLAYER_NAME, ScrRaceIds.TERRAN, 0, GameSpeed.FASTEST), 1);
            SlowLoopback network = new SlowLoopback();

            game.attachNetwork(network, LOOPBACK_TURNS_IN_TRANSIT, true);

            ReplayRuntime live = ReplayRuntime.createLiveGame(game.getReplay(), ChkFile.parseTolerant(mapData), gameData,
                                                              game.getLocalPlayer(),
                                                              RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT,
                                                              RunningGameScenario.INSTRUCTION_LIMIT);

            live.start(0, 0);
            game.startSession(live.getGame(), live.getFrameTiming());

            ReplaySession session = live.getSession();

            Assertions.assertNotNull(session);

            game.attachReplaySession(session);

            UnitSelection selection = live.getGame().getSelection();
            UnitPool pool = live.getGame().getUnitSystem().getUnitPool();

            Assertions.assertNotNull(selection);
            Assertions.assertNotNull(pool);

            // A selection of more than one unit is stamped into the selection history with the turn count it sees.
            ByteBuffer select = ByteBuffer.allocate(1 + SELECTED_WORKERS * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            int selected = 0;

            select.put((byte) SELECTED_WORKERS);

            for (int unit = pool.getFirstCreatedIndex(true); unit != 0 && selected < SELECTED_WORKERS; unit = pool.getNextCreatedIndex(unit)) {
                if (pool.getUnit(unit).getOwnerIndex() == game.getLocalPlayer() && pool.getUnit(unit).getUnitId() == ScrUnitIds.TERRAN_SCV) {
                    select.putInt(pool.getHandle(unit));
                    selected++;
                }
            }

            Assertions.assertEquals(SELECTED_WORKERS, selected, "The local player starts with workers.");
            Assertions.assertTrue(game.submit(ReplayCommandIds.SELECT_121, select.array()));
            Assertions.assertTrue(game.exchangeTurn(live.getGame().getCurrentFrame()));
            Assertions.assertEquals(1, game.getStartupTurnCommandCount(), "The first turn sent carries the selection and is the startup turn.");
            Assertions.assertTrue(live.step(GameSpeed.FASTEST_DURATION, GameSpeed.FASTEST_DURATION));
            Assertions.assertEquals(SELECT_COMMAND_TYPE, session.getExecutedCommands().getFirst().getTypeId());
            Assertions.assertNotEquals(0, selection.getHistoryHandle(game.getLocalPlayer(), FIRST_HISTORY_RECORD, 0), "The selection is recorded.");
            Assertions.assertEquals(0, selection.getHistoryTimestamp(game.getLocalPlayer(), FIRST_HISTORY_RECORD),
                                    "The startup turn's selection sees the turn count 0.");
            Assertions.assertEquals(2, live.getGame().getTurnCount(), "The startup turn step and the first frame's turn step.");
        }
    }

    @Test
    void exchange_turn_should_drop_the_commands_after_one_without_a_dispatcher_case() throws IOException {
        byte[] mapData = LiveSessionScenario.readBottleneckScenario();
        Network network = new Network();
        LiveGame host = new LiveGame(MeleeGameSetup.createUseMapSettings(mapData, HOST_NAME, ScrRaceIds.TERRAN, 0, GameSpeed.FASTEST), 1);
        LiveGame joiner = new LiveGame(MeleeGameSetup.createUseMapSettings(mapData, JOINER_NAME, ScrRaceIds.TERRAN, 0, GameSpeed.FASTEST), 1);

        host.attachNetwork(network.createTransport(0), TURNS_IN_TRANSIT, true);
        joiner.attachNetwork(network.createTransport(1), TURNS_IN_TRANSIT, false);

        Assertions.assertFalse(host.exchangeTurn(0));
        Assertions.assertTrue(joiner.exchangeTurn(0));
        Assertions.assertTrue(host.exchangeTurn(0));

        // The joiner's next turn ends at the command without a dispatcher case: the first Stop runs, the second is dropped.
        List<byte[]> joinerTurns = network.sent.get(1);
        int nextTurn = network.received[0];
        byte[] turn = joinerTurns.get(nextTurn);
        byte[] extendedTurn = Arrays.copyOf(turn, turn.length + TURN_TAIL.length);

        System.arraycopy(TURN_TAIL, 0, extendedTurn, turn.length, TURN_TAIL.length);
        joinerTurns.set(nextTurn, extendedTurn);

        for (long frame = 1; frame < EXCHANGE_FRAME_LIMIT; frame++) {
            Assertions.assertTrue(joiner.exchangeTurn(frame));
            Assertions.assertTrue(host.exchangeTurn(frame));
        }

        List<ReplayCommand> hostHistory = host.getHistory();

        Assertions.assertEquals(1, hostHistory.size());
        Assertions.assertEquals(1, hostHistory.getFirst().getPlayerId());
        Assertions.assertEquals(STOP_COMMAND_TYPE, hostHistory.getFirst().getTypeId());
        Assertions.assertEquals(1, joiner.getHistory().size());
    }

    @Test
    void human_peers_of_a_use_map_settings_game_play_the_same_game_each_with_its_own_local_player() throws IOException {
        Path map = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, BOTTLENECK_MAP);

        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            LockstepMapGameScenario scenario = LockstepMapGameScenario.startUseMapSettings(gameData, map, NETWORK_PLAYER_COUNT, LOCKSTEP_SEED);

            scenario.stepFrames(LOCKSTEP_FRAMES);

            Assertions.assertTrue(scenario.getLiveGame(1).submit(ReplayCommandIds.STOP, new byte[]{0}));

            scenario.stepFrames(LOCKSTEP_FRAMES);

            Assertions.assertEquals(NETWORK_PLAYER_COUNT, scenario.getPeerCount());
            Assertions.assertNotEquals(scenario.getLiveGame(0).getLocalPlayer(), scenario.getLiveGame(1).getLocalPlayer());
            Assertions.assertEquals(scenario.getGame(0).getCurrentFrame(), scenario.getGame(1).getCurrentFrame());
            Assertions.assertEquals(0, scenario.getSyncMismatches());

            UnitPool first = scenario.getGame(0).getUnitSystem().requireUnitPool();
            UnitPool second = scenario.getGame(1).getUnitSystem().requireUnitPool();

            for (int slot = 1; slot <= first.getCapacity(); slot++) {
                Assertions.assertEquals(first.isAllocated(slot), second.isAllocated(slot));

                if (first.isAllocated(slot)) {
                    Assertions.assertEquals(first.getUnit(slot).getUnitId(), second.getUnit(slot).getUnitId());
                    Assertions.assertEquals(first.getUnit(slot).getPositionX(), second.getUnit(slot).getPositionX());
                    Assertions.assertEquals(first.getUnit(slot).getPositionY(), second.getUnit(slot).getPositionY());
                }
            }

            for (int peer = 0; peer < NETWORK_PLAYER_COUNT; peer++) {
                List<ReplayCommand> history = scenario.getLiveGame(peer).getHistory();

                Assertions.assertEquals(1, history.size(), "the Stop of the second player on every peer");
                Assertions.assertEquals(1, history.getFirst().getPlayerId());
                Assertions.assertEquals(STOP_COMMAND_TYPE, history.getFirst().getTypeId());
            }
        }
    }
}
