package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.scr.net.discovery.LanDiscovery;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanRecord;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * Holds the local network of LAN games: the LAN discovery ({@link LanDiscovery}) lists, publishes and withdraws the games and presences, the
 * game socket is a UDP socket ({@link GameProxySocket#open(int)}), and the host of a listed game is reachable at once at its advertised
 * address.
 */
public final class LocalNetworkProvider implements NetworkProvider {
    /**
     * Opens the LAN discovery of a local network provider.
     *
     * @return the created {@link LocalNetworkProvider} instance
     * @throws IOException if the discovery cannot be opened
     */
    public static @NotNull LocalNetworkProvider open() throws IOException {
        return new LocalNetworkProvider(LanDiscovery.open());
    }


    private final @NotNull LanDiscovery discovery;

    private LanDiscovery.@Nullable Game connectedGame;

    private LocalNetworkProvider(@NotNull LanDiscovery discovery) {
        this.discovery = discovery;

        this.connectedGame = null;
    }

    /**
     * Lists the games found on the network, except the one this client hosts.
     *
     * @return the games
     */
    @Override
    public @NotNull List<LanDiscovery.Game> getGames() {
        return this.discovery.getGames();
    }

    /**
     * @return the game whose host {@link #connect} made reachable, with the host's own game attributes, or {@code null} while the connection is pending, has failed or was never started
     */
    @Override
    public LanDiscovery.@Nullable Game getConnectedGame() {
        return this.connectedGame;
    }

    /**
     * @return why the connection {@link #connect} started failed ({@link ProviderSession.JoinState#REJECTED} or {@link ProviderSession.JoinState#TIMED_OUT}), or {@code null} while it is pending or reached the host
     * @implSpec Returns {@code null}: the host of a LAN game is reachable at once.
     */
    @Override
    public ProviderSession.@Nullable JoinState getConnectionFailure() {
        return null;
    }


    /**
     * @return {@code true} if the hosted game may start as far as the network goes (every pair of its players can reach each other) or this client hosts no game, {@code false} otherwise
     * @implSpec Returns {@code true}: on the local network every player reaches every other one.
     */
    @Override
    public boolean isReadyToStart() {
        return true;
    }

    /**
     * @return why the network cannot list, host or join games, as a text to show, or {@code null} while it can or is still trying
     * @implSpec Returns {@code null}: a failure of the local network shows when its sockets open.
     */
    @Override
    public @Nullable String getFailureText() {
        return null;
    }


    /**
     * Sets the player's presence record, publishing it or updating it, on a network that shows presences.
     *
     * @param record the presence record
     */
    @Override
    public void setPresence(@NotNull LanRecord record) {
        this.discovery.setPresence(record);
    }

    /**
     * Sets the hosted game's record, publishing the game or updating it.
     *
     * @param record the game record
     * @param port   the game socket's local port ({@link GameProxySocket#getLocalPort()})
     */
    @Override
    public void setGame(@NotNull LanRecord record, int port) {
        this.discovery.setGame(record, port);
    }

    /**
     * Withdraws the hosted game of the given name.
     *
     * @param name the game name
     */
    @Override
    public void withdraw(@NotNull String name) {
        this.discovery.withdraw(name);
    }

    /**
     * Opens the socket of a hosted or joined game on this network; closing the socket ends the game's use of the network.
     *
     * @param port the local UDP port on the local network, {@link GameProxySocket#GAME_PORT} for a game
     * @return the opened socket
     * @throws IOException if the socket cannot be opened
     */
    @Override
    public @NotNull GameProxySocket openGameSocket(int port) throws IOException {
        return GameProxySocket.open(port);
    }

    /**
     * Starts making the host of a listed game reachable for the game socket; {@link #getConnectedGame()} returns the game once it is.
     *
     * @param game the game, as {@link #getGames()} listed it
     * @implSpec The game is connected at once: its advertised address is the host's game socket.
     */
    @Override
    public void connect(LanDiscovery.@NotNull Game game) {
        this.connectedGame = game;
    }

    /**
     * Carries out the network's due work without waiting.
     *
     * @implSpec Does nothing: the discovery runs on its own threads.
     */
    @Override
    public void update() {
    }

    /**
     * Closes the network: it withdraws what it published and closes its sockets.
     */
    @Override
    public void close() {
        this.discovery.close();
    }
}
