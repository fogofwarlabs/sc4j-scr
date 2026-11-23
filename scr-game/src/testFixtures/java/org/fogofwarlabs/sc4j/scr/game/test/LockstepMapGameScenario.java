package org.fogofwarlabs.sc4j.scr.game.test;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.lockstep.InMemoryLockstepNetwork;
import org.fogofwarlabs.sc4j.engine.lockstep.LockstepPeer;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.NetworkPeerUtils;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.fogofwarlabs.sc4j.scr.game.session.setup.LobbyResolution;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays a Use Map Settings network game of several human players in one process: the first human slots of the map take one human each, the
 * map's computer slots a computer and its other human slots stay empty. Every human is a peer with a {@link LiveGame} and a
 * {@link ReplayRuntime} of its own, whose local player is that human, so the local code of each player runs on its own peer; every peer's setup
 * holds the map archive, as a network game's does, so the extensions of the games read the map file. The peers send their turns over an
 * in-memory lockstep network ({@link InMemoryLockstepNetwork}) and compare their Sync records as the peers of a network game do.
 * <p>
 * Each frame exchanges the turns of every peer, runs the frame on every peer in peer order and then tells each peer's game plugins that the
 * frame was played ({@link ScrGamePlugin#onFramePlayed}), as the client does after each frame. A test drives a player through its peer: the
 * commands it submits to the peer's live game and the input it gives the peer's game plugins.
 */
public final class LockstepMapGameScenario {
    /**
     * The turns each peer keeps in transit.
     */
    public static final int TURNS_IN_TRANSIT = 2;

    private static final @NotNull String PLAYER_NAME_PREFIX = "player";
    private static final @NotNull String COMPUTER_NAME = "Computer";
    /**
     * The offset of the force flag bytes in the {@code FORC} section.
     */
    private static final int FORCE_FLAGS_OFFSET = 16;
    /**
     * The rounds of turn exchanges a frame may take before a peer's missing turn fails the scenario.
     */
    private static final int MAXIMUM_EXCHANGE_ROUNDS = 64;

    /**
     * Starts a Use Map Settings game of human players on a map, one peer each, the humans in the order of the map's human slots.
     *
     * @param gameData   the installed game data, which every peer's game reads
     * @param mapPath    the map archive
     * @param humanCount the human players, at least {@code 1} and at most the map's human slots
     * @param seed       the game seed
     * @return the started scenario, its peers past the game start
     * @throws IOException if the map cannot be read or a game cannot start
     */
    public static @NotNull LockstepMapGameScenario startUseMapSettings(@NotNull GameData gameData, @NotNull Path mapPath, int humanCount,
                                                                       int seed) throws IOException {
        MpqArchive mapArchive = MpqArchive.open(mapPath);
        byte[] mapData = mapArchive.readFile(ChkFile.ARCHIVE_ENTRY);
        LobbyResolution.Table table = createTable(ChkFile.parseTolerant(mapData), humanCount);
        List<LockstepPeer> peers = new ArrayList<>();

        for (int player = 0; player < humanCount; player++) {
            peers.add(NetworkPeerUtils.getPeer(player));
        }

        InMemoryLockstepNetwork network = new InMemoryLockstepNetwork(peers);
        List<LiveGame> liveGames = new ArrayList<>();
        List<ReplayRuntime> runtimes = new ArrayList<>();

        for (int player = 0; player < humanCount; player++) {
            MeleeGameSetup setup = MeleeGameSetup.createNetwork(mapData, table.name[findSlot(table, 0)], table,
                                                                new LobbyResolution.Options(true, true, 0, true, false),
                                                                GameTemplate.USE_MAP_SETTINGS, GameSpeed.FASTEST, true);

            setup.setMapArchive(mapArchive);

            LiveGame live = new LiveGame(setup, seed, player);

            live.attachNetwork(network.getTransport(peers.get(player)), TURNS_IN_TRANSIT, false);

            ReplayRuntime runtime = LiveMapGameScenario.startLive(gameData, live, mapPath);

            live.setSyncSource(runtime.getGame(), () -> 0);
            liveGames.add(live);
            runtimes.add(runtime);
        }

        return new LockstepMapGameScenario(liveGames, runtimes);
    }

    /**
     * Creates the lobby table of a Use Map Settings game: the map's slots with their races and forces, a human in each of the first human slots,
     * the human of the {@code n}th human slot with network id {@code n} and the name {@value #PLAYER_NAME_PREFIX} and {@code n + 1}, and a
     * computer in each computer slot; a selectable race is Terran for a human and random for a computer.
     *
     * @throws IllegalArgumentException if there is no human or the map has fewer human slots than humans
     */
    private static LobbyResolution.@NotNull Table createTable(@NotNull ChkFile scenario, int humanCount) {
        byte[] owners = scenario.requireSection(ChkFile.OWNER_SECTION);
        byte[] sides = scenario.getSection(ChkFile.RACE_SECTION);
        byte[] forces = scenario.getSection(ChkFile.FORCE_SECTION);
        LobbyResolution.Table table = new LobbyResolution.Table();
        int humans = 0;

        for (int slot = 0; slot < PlayerTable.PLAYER_SLOTS; slot++) {
            int owner = slot < owners.length ? Byte.toUnsignedInt(owners[slot]) : 0;
            int side = sides != null && slot < sides.length ? Byte.toUnsignedInt(sides[slot]) : 0;
            boolean selectable = side == ScrRaceIds.USER_SELECTABLE;
            boolean playable = slot < PlayerTable.PLAYABLE_PLAYER_SLOTS;

            table.playerId[slot]     = -1;
            table.type[slot]         = owner;
            table.race[slot]         = side;
            table.team[slot]         = playable && forces != null && slot < forces.length ? Byte.toUnsignedInt(forces[slot]) + 1 : 0;
            table.name[slot]         = "";
            table.templateType[slot] = owner;
            table.templateRace[slot] = side;
            table.savedType[slot]    = owner;

            if (playable) {
                table.selectableRace[slot] = selectable;
                table.lobbyColor[slot]     = slot;
            }

            if (playable && owner == LobbyResolution.TYPE_COMPUTER) {
                table.race[slot] = selectable ? ScrRaceIds.RANDOM : side;
                table.name[slot] = COMPUTER_NAME;
            } else if (playable && owner == LobbyResolution.TYPE_OPEN && humans < humanCount) {
                table.playerId[slot] = humans;
                table.type[slot]     = MeleeGameSetup.TYPE_HUMAN;
                table.race[slot]     = selectable ? ScrRaceIds.TERRAN : side;
                table.name[slot]     = PLAYER_NAME_PREFIX + (humans + 1);
                humans++;
            }
        }

        if (humanCount < 1 || humans < humanCount) {
            throw Exceptions.newIllegalArgumentException("The map has {0} human slots for {1} humans.", humans, humanCount);
        }

        for (int force = 0; force < PlayerTable.FORCE_COUNT; force++) {
            int offset = FORCE_FLAGS_OFFSET + force;

            table.forceFlags[force] = forces != null && offset < forces.length ? Byte.toUnsignedInt(forces[offset]) : 0;
        }

        table.tileset = scenario.getTileset();

        return table;
    }

    private static int findSlot(LobbyResolution.@NotNull Table table, int playerId) {
        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            if (table.playerId[slot] == playerId) {
                return slot;
            }
        }

        throw Exceptions.newIllegalStateException("The table has no player {0}.", playerId);
    }


    private final @NotNull List<LiveGame> liveGames;
    private final @NotNull List<ReplayRuntime> runtimes;

    /**
     * Creates a {@link LockstepMapGameScenario} instance.
     *
     * @param liveGames the live game of each peer
     * @param runtimes  the runtime of each peer
     */
    private LockstepMapGameScenario(@NotNull List<LiveGame> liveGames, @NotNull List<ReplayRuntime> runtimes) {
        this.liveGames = liveGames;
        this.runtimes  = runtimes;
    }

    /**
     * @return the number of peers, one per human player
     */
    public int getPeerCount() {
        return this.liveGames.size();
    }

    /**
     * @param peer the peer, the network id of its human player
     * @return the peer's live game, which takes the commands of its local player
     */
    public @NotNull LiveGame getLiveGame(int peer) {
        return this.liveGames.get(peer);
    }

    /**
     * @param peer the peer, the network id of its human player
     * @return the peer's game
     */
    public @NotNull Game getGame(int peer) {
        return this.runtimes.get(peer).getGame();
    }

    /**
     * @return the Sync records of the other peers that did not match a peer's own, over every peer
     */
    public int getSyncMismatches() {
        int mismatches = 0;

        for (LiveGame live : this.liveGames) {
            mismatches += live.getSyncMismatches();
        }

        return mismatches;
    }

    /**
     * Runs frames ({@link #stepFrame()}).
     *
     * @param count the number of frames
     */
    public void stepFrames(int count) {
        for (int frame = 0; frame < count; frame++) {
            this.stepFrame();
        }
    }

    /**
     * Runs one frame on every peer: exchanges the turns of every peer until each may run the frame, runs it on each peer and tells each peer's
     * game plugins that it was played.
     *
     * @throws IllegalStateException if a peer still misses a turn after the exchange rounds, or a peer's game failed
     */
    public void stepFrame() {
        int count = this.liveGames.size();
        boolean[] ready = new boolean[count];
        int waiting = count;

        for (int round = 0; round < MAXIMUM_EXCHANGE_ROUNDS && waiting > 0; round++) {
            for (int peer = 0; peer < count; peer++) {
                if (!ready[peer] && this.liveGames.get(peer).exchangeTurn(this.getGame(peer).getCurrentFrame())) {
                    ready[peer] = true;
                    waiting--;
                }
            }
        }

        if (waiting > 0) {
            throw Exceptions.newIllegalStateException("{0} peers still miss a turn at frame {1}.", waiting, this.getGame(0).getCurrentFrame());
        }

        for (int peer = 0; peer < count; peer++) {
            ReplayRuntime runtime = this.runtimes.get(peer);
            Game game = runtime.getGame();
            int frame = game.getCurrentFrame();

            game.getInterfaceLink().getMessageLines().expire(frame * GameSpeed.FASTEST_DURATION);
            runtime.step(GameSpeed.FASTEST_DURATION, frame * GameSpeed.FASTEST_DURATION);
            this.liveGames.get(peer).updateOutcome(game);

            if (runtime.getFailure() != null) {
                throw Exceptions.newIllegalStateException("The game of peer {0} failed: {1}", peer, runtime.getFailure());
            }

            for (ScrGamePlugin plugin : game.getPlugins()) {
                plugin.onFramePlayed(game.getCurrentFrame());
            }
        }
    }
}
