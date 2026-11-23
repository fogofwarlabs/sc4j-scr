package org.fogofwarlabs.sc4j.scr.net.provider;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Reads the recorded LAN provider packet logs of the test resources ({@code captures/lan}).
 * <p>
 * A log holds one packet per line, {@code <milliseconds> <H|J> <hex>} (H host, J joiner). The two lobby player-information
 * messages (class {@code 1}, first byte {@code 3a}) have their BattleTag characters replaced by {@code X...#0...} and their
 * checksum recomputed; every other packet is byte-identical to the recording.
 */
public final class ProviderTestResources {
    /**
     * The test resource folder of the recorded LAN provider packet logs.
     */
    public static final @NotNull String CAPTURE_FOLDER = "captures/lan";

    private static final @NotNull String FIXTURE_DIRECTORY = "/captures/lan/";
    private static final @NotNull String LOBBY_JOIN = "lan-lobby-join.txt";
    private static final @NotNull String GAME_START = "lan-game-start.txt";
    private static final @NotNull String HOST_SENDER = "H";
    private static final int TIME_FIELD = 0;
    private static final int SENDER_FIELD = 1;
    private static final int PACKET_FIELD = 2;

    /**
     * One packet of a recorded LAN game fixture.
     */
    public static final class CapturedPacket {
        private final int time;
        private final boolean fromHost;

        private final @NotNull ProviderPacket packet;

        /**
         * Creates a {@link CapturedPacket} instance.
         *
         * @param time     the time in milliseconds since the start of the recording
         * @param fromHost whether the host sent the packet
         * @param packet   the provider packet
         */
        public CapturedPacket(int time, boolean fromHost, @NotNull ProviderPacket packet) {
            this.time     = time;
            this.fromHost = fromHost;

            this.packet = packet;
        }

        /**
         * @return the time in milliseconds since the start of the recording
         */
        public int getTime() {
            return this.time;
        }

        /**
         * @return {@code true} if the host sent the packet, {@code false} otherwise
         */
        public boolean isFromHost() {
            return this.fromHost;
        }

        /**
         * @return the provider packet
         */
        public @NotNull ProviderPacket getPacket() {
            return this.packet;
        }
    }

    /**
     * Reads the log {@code lan-lobby-join.txt}: the first {@code 20} s of the recorded lobby.
     *
     * @return the packets in recorded order
     * @throws IOException if the log cannot be read or holds an invalid packet
     */
    public static @NotNull List<CapturedPacket> readLobbyJoin() throws IOException {
        return read(LOBBY_JOIN);
    }

    /**
     * Reads the log {@code lan-game-start.txt}: the packets of {@code lan-lobby-join.txt} through {@code 21.6} s, the
     * countdown's end and the first in-game turns.
     *
     * @return the packets in recorded order
     * @throws IOException if the log cannot be read or holds an invalid packet
     */
    public static @NotNull List<CapturedPacket> readGameStart() throws IOException {
        return read(GAME_START);
    }

    /**
     * Reads a LAN provider packet log.
     *
     * @param name the log: {@code lan-lobby-join.txt} (the first {@code 20} s) or {@code lan-game-start.txt} (the same packets through {@code 21.6} s, the countdown's end and the first in-game turns)
     * @return the packets in recorded order
     * @throws IOException if the log is missing, cannot be read or holds an invalid packet
     */
    private static @NotNull List<CapturedPacket> read(@NotNull String name) throws IOException {
        List<CapturedPacket> packets = new ArrayList<>();
        InputStream resource = ProviderTestResources.class.getResourceAsStream(FIXTURE_DIRECTORY + name);

        if (resource == null) {
            throw Exceptions.newIOException("Missing LAN fixture {0}.", name);
        }

        try (InputStream input = resource; BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.US_ASCII))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String[] fields = line.split(" ");

                packets.add(new CapturedPacket(Integer.parseInt(fields[TIME_FIELD]), fields[SENDER_FIELD].equals(HOST_SENDER),
                                               ProviderPacket.parse(HexFormat.of().parseHex(fields[PACKET_FIELD]))));
            }
        }

        return packets;
    }


    /**
     * Creates a {@link ProviderTestResources} instance.
     */
    private ProviderTestResources() {
    }
}
