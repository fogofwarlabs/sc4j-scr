package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.engine.lockstep.LockstepTransport;
import org.fogofwarlabs.sc4j.engine.lockstep.PeerTurn;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplaySession;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.NetworkPeerUtils;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks the desync dump of a LAN game: each client writes one at the first Sync mismatch, the state lines of the two dumps differ
 * exactly in the changed value, and a matching game writes none.
 */
@RequiresInstallation
final class LanDesyncDumpTest {
    private static final int SEED = 0x6ABCBEE2;
    private static final int PLAYERS = 2;
    private static final int HOST_PLAYER = 0;
    private static final int JOINER_PLAYER = 1;
    private static final int TURNS_IN_TRANSIT = 3;

    private static final int FRAMES = 240;
    /**
     * The frame at which the joiner's simulation gets its own mineral count.
     */
    private static final int CHANGE_FRAME = 60;
    private static final int CHANGED_MINERALS = 8;
    private static final int ITERATION_LIMIT = 10_000;

    private static final int FRAME_MILLISECONDS = 42;
    private static final int VIEWPORT_WIDTH = 640;
    private static final int VIEWPORT_HEIGHT = 480;
    private static final int INSTRUCTION_LIMIT = 100_000;

    private static final long DUMP_WAIT_MILLISECONDS = 30_000;
    private static final long DUMP_POLL_MILLISECONDS = 50;

    private static final @NotNull String BUILD = "test build";
    private static final @NotNull String DUMP_EXTENSION = ".txt";
    private static final @NotNull String HASHED_PREFIX = "hashed ";
    private static final @NotNull String CURRENT_PREFIX = "current ";
    private static final @NotNull Pattern RECORD_FRAME = Pattern.compile("^record index \\d+ update \\d+ kind \\d+ frame (\\d+)$", Pattern.MULTILINE);
    private static final @NotNull Pattern HASHED_FRAME = Pattern.compile("^hashed frame (\\d+)$", Pattern.MULTILINE);
    private static final @NotNull Pattern MINERALS_LINE = Pattern.compile("^(hashed|current) player (\\d+) minerals (\\d+) gas (\\d+)$");

    /**
     * Carries the turns of one of two in-memory clients: a receive takes the next turn of both once both sent it.
     */
    private static final class PairedTransport implements LockstepTransport {
        private final boolean host;
        private final @NotNull List<byte @NotNull []> sent;

        private @Nullable PairedTransport other;
        private int received;

        private PairedTransport(boolean host) {
            this.host = host;
            this.sent = new ArrayList<>();

            this.other    = null;
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
         * Sends the local participant's next turn.
         *
         * @param turn the turn bytes
         */
        @Override
        public void sendTurn(byte @NotNull [] turn) {
            this.sent.add(turn);
        }

        /**
         * Takes the next turn of every participant once all of them arrived.
         *
         * @return the participants' turns in execution order, or {@code null} while a participant's turn is missing
         * @implSpec Returns the host's turn, then the joiner's.
         */
        @Override
        public @Nullable List<@NotNull PeerTurn> receiveTurns() {
            PairedTransport peer = this.other;

            Assertions.assertNotNull(peer);

            if (this.received >= this.sent.size() || this.received >= peer.sent.size()) {
                return null;
            }

            byte[] hostTurn = (this.host ? this : peer).sent.get(this.received);
            byte[] joinerTurn = (this.host ? peer : this).sent.get(this.received);

            this.received++;

            return List.of(new PeerTurn(NetworkPeerUtils.getPeer(HOST_PLAYER), hostTurn),
                           new PeerTurn(NetworkPeerUtils.getPeer(JOINER_PLAYER), joinerTurn));
        }
    }

    private static @NotNull List<@NotNull Path> listDumps(@NotNull Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(DUMP_EXTENSION)).sorted().toList();
        }
    }

    private static @NotNull List<@NotNull String> readStateLines(@NotNull String dump) {
        return dump.lines().filter(line -> line.startsWith(HASHED_PREFIX) || line.startsWith(CURRENT_PREFIX)).toList();
    }

    private static @NotNull List<@NotNull String> subtract(@NotNull List<@NotNull String> lines, @NotNull List<@NotNull String> removed) {
        List<String> left = new ArrayList<>(lines);

        for (String line : removed) {
            left.remove(line);
        }

        return left;
    }

    private static int readFrame(@NotNull Pattern pattern, @NotNull String dump) {
        Matcher matcher = pattern.matcher(dump);

        Assertions.assertTrue(matcher.find(), pattern.pattern());

        return Integer.parseInt(matcher.group(1));
    }


    @Test
    void matching_lan_game_writes_no_desync_dump(@TempDir @NotNull Path folder) throws Exception {
        List<LiveGame> games = this.play(folder, false);

        for (LiveGame live : games) {
            Assertions.assertEquals(0, live.getSyncMismatches());
        }

        Assertions.assertEquals(List.of(), listDumps(folder));
    }

    @Test
    void desync_dumps_of_both_clients_differ_only_in_the_changed_minerals(@TempDir @NotNull Path folder) throws Exception {
        List<LiveGame> games = this.play(folder, true);

        for (LiveGame live : games) {
            Assertions.assertTrue(live.getSyncMismatches() > 0);
        }

        long deadline = System.currentTimeMillis() + DUMP_WAIT_MILLISECONDS;

        // Each client writes its dump on a background thread.
        while (listDumps(folder).size() < PLAYERS && System.currentTimeMillis() < deadline) {
            Thread.sleep(DUMP_POLL_MILLISECONDS);
        }

        List<Path> dumps = listDumps(folder);

        Assertions.assertEquals(PLAYERS, dumps.size(), () -> "dumps " + dumps);

        String hostDump = Files.readString(dumps.stream().filter(file -> file.getFileName().toString().endsWith("_B" + DUMP_EXTENSION))
                                                .findFirst().orElseThrow(), StandardCharsets.UTF_8);
        String joinerDump = Files.readString(dumps.stream().filter(file -> file.getFileName().toString().endsWith("_A" + DUMP_EXTENSION))
                                                  .findFirst().orElseThrow(), StandardCharsets.UTF_8);
        int changedPlayer = games.get(JOINER_PLAYER).getLocalPlayer();

        for (String dump : List.of(hostDump, joinerDump)) {
            Assertions.assertTrue(dump.contains("\nbuild " + BUILD + "\n"));
            Assertions.assertTrue(dump.contains("\nmap " + LanTestResources.CAPTURED_MAP_FILE_NAME + "\n"));
            Assertions.assertTrue(dump.contains(" \"A\"\n") && dump.contains(" \"B\"\n"));
            Assertions.assertEquals(readFrame(RECORD_FRAME, dump), readFrame(HASHED_FRAME, dump), "the hashed state is the record's frame");
        }

        List<String> hostLines = readStateLines(hostDump);
        List<String> joinerLines = readStateLines(joinerDump);
        List<String> hostOnly = subtract(hostLines, joinerLines);
        List<String> joinerOnly = subtract(joinerLines, hostLines);

        Assertions.assertEquals(2, hostOnly.size(), () -> "host only " + hostOnly);
        Assertions.assertEquals(2, joinerOnly.size(), () -> "joiner only " + joinerOnly);

        for (int index = 0; index < hostOnly.size(); index++) {
            Matcher host = MINERALS_LINE.matcher(hostOnly.get(index));
            Matcher joiner = MINERALS_LINE.matcher(joinerOnly.get(index));

            Assertions.assertTrue(host.matches(), hostOnly.get(index));
            Assertions.assertTrue(joiner.matches(), joinerOnly.get(index));
            Assertions.assertEquals(host.group(1), joiner.group(1));
            Assertions.assertEquals(changedPlayer, Integer.parseInt(host.group(2)));
            Assertions.assertEquals(changedPlayer, Integer.parseInt(joiner.group(2)));
            Assertions.assertEquals(Integer.parseInt(host.group(3)) + CHANGED_MINERALS, Integer.parseInt(joiner.group(3)));
            Assertions.assertEquals(host.group(4), joiner.group(4));
        }
    }

    /**
     * Plays the captured game on two in-memory clients until both ran {@value #FRAMES} frames or, with a change, both saw a Sync
     * mismatch.
     *
     * @param folder the desync dump folder of both clients
     * @param change whether the joiner's simulation gets {@value #CHANGED_MINERALS} more minerals for its own player at {@value #CHANGE_FRAME}
     * @return the host's and the joiner's games
     * @throws Exception if the map or the game data cannot be read
     */
    private @NotNull List<@NotNull LiveGame> play(@NotNull Path folder, boolean change) throws Exception {
        Path mapPath = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, LanTestResources.CAPTURED_MAP_FILE_NAME);

        byte[] mapData = MpqArchive.open(mapPath).readFile("staredit\\scenario.chk");
        PairedTransport hostTransport = new PairedTransport(true);
        PairedTransport joinerTransport = new PairedTransport(false);

        hostTransport.other   = joinerTransport;
        joinerTransport.other = hostTransport;

        List<LiveGame> games = new ArrayList<>();
        List<ReplayRuntime> runtimes = new ArrayList<>();

        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            for (int player = 0; player < PLAYERS; player++) {
                LiveGame live = new LiveGame(LanTestResources.createCapturedGameSetup(mapData), SEED, player);

                live.attachNetwork(player == HOST_PLAYER ? hostTransport : joinerTransport, TURNS_IN_TRANSIT, false);
                live.setDesyncDumpTarget(folder, LanTestResources.CAPTURED_MAP_FILE_NAME, BUILD);

                ReplayRuntime runtime = ReplayRuntime.createLiveGame(live.getReplay(), ChkFile.parseTolerant(mapData),
                                                                     gameData,
                                                                     live.getLocalPlayer(),
                                                                     VIEWPORT_WIDTH, VIEWPORT_HEIGHT,
                                                                     INSTRUCTION_LIMIT);

                runtime.start(0, 0);
                live.setSyncSource(runtime.getGame(), () -> 0);

                ReplaySession session = runtime.getSession();

                Assertions.assertNotNull(session);

                live.attachReplaySession(session);
                games.add(live);
                runtimes.add(runtime);
            }

            boolean changed = false;

            for (int iteration = 0; iteration < ITERATION_LIMIT; iteration++) {
                boolean done = true;

                for (int player = 0; player < PLAYERS; player++) {
                    LiveGame live = games.get(player);
                    ReplayRuntime runtime = runtimes.get(player);
                    Game game = runtime.getGame();

                    if (game.getCurrentFrame() >= FRAMES || change && live.getSyncMismatches() > 0) {
                        continue;
                    }

                    done = false;

                    if (change && !changed && player == JOINER_PLAYER && game.getCurrentFrame() == CHANGE_FRAME) {
                        Player own = game.getPlayerTable().getPlayer(live.getLocalPlayer());

                        Assertions.assertNotNull(own);

                        own.setMinerals(own.getMinerals() + CHANGED_MINERALS);
                        changed = true;
                    }

                    if (live.exchangeTurn(game.getCurrentFrame())) {
                        runtime.step(FRAME_MILLISECONDS, 0);

                        Assertions.assertNull(runtime.getFailure());
                    }
                }

                if (done) {
                    break;
                }
            }
        }

        return games;
    }
}
