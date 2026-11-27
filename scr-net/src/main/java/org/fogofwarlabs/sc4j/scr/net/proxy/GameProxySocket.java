package org.fogofwarlabs.sc4j.scr.net.proxy;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.net.DatagramLink;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderPacket;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderTransportMessage;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Sends provider messages through game proxy datagrams and delivers received datagrams when the caller polls.
 * Per-destination state selects initial probe datagrams; received probes that pass the receive gate are answered.
 * <p>
 * The datagrams travel on a link addressed by IPv4 socket address: on the local network a UDP socket ({@link GameProxyUdpLink}), whose
 * receiver thread reads the socket while polling performs decoding and provider callbacks; over Beacon.Link the overlay port of the SC:R
 * Storm session, whose addresses stand for the participants. The datagrams are the same on every link.
 */
public final class GameProxySocket implements ProviderSession.Transport, AutoCloseable {
    /**
     * The game port.
     */
    public static final int GAME_PORT = 6112;

    private static final @NotNull Logger LOGGER = Logger.of(GameProxySocket.class);

    /**
     * The probe count of a destination after which datagrams are no longer probes; the count starts at {@code 0}.
     */
    private static final int PROBE_LIMIT = 3;
    private static final long PROBE_WINDOW_MILLISECONDS = 5000;
    private static final int MAXIMUM_DATAGRAM = 2048;
    private static final int IPV4_ADDRESS_SIZE = 4;
    private static final long MICROSECONDS_PER_SECOND = 1_000_000L;
    private static final int NANOSECONDS_PER_MICROSECOND = 1000;

    /**
     * The probe state of one destination.
     */
    private static final class ProbeState {
        private int count;
        private long lastProbeTime;

        private ProbeState() {
            this.count         = 0;
            this.lastProbeTime = 0;
        }
    }

    /**
     * Creates a {@link GameProxySocket} instance bound to a UDP port on every interface.
     *
     * @param port the local port, {@link #GAME_PORT} for a game
     * @return the created {@link GameProxySocket} instance
     * @throws SocketException if the port cannot be bound
     */
    public static @NotNull GameProxySocket open(int port) throws SocketException {
        GameProxyUdpLink link = GameProxyUdpLink.open(port);

        return open(link, link.getLocalPort());
    }

    /**
     * Creates a {@link GameProxySocket} instance on a link; the socket owns the link and closes it.
     *
     * @param link      the link of the datagrams
     * @param localPort the port the link receives at, as a game advertises it
     * @return the created {@link GameProxySocket} instance
     */
    public static @NotNull GameProxySocket open(@NotNull DatagramLink<@NotNull InetSocketAddress> link, int localPort) {
        long key;

        do {
            key = new SecureRandom().nextLong();
        } while (key == 0);

        return new GameProxySocket(link, localPort, key);
    }

    /**
     * Converts an IPv4 address to the native DWORD layout (first dotted octet in the low byte).
     *
     * @param address the address
     * @return the DWORD, {@code 0} for a non-IPv4 address
     */
    public static int toNativeAddress(@NotNull InetAddress address) {
        byte[] bytes = address.getAddress();

        if (bytes.length != IPV4_ADDRESS_SIZE) {
            return 0;
        }

        return LittleEndianUtils.readInt32(bytes, 0);
    }

    /**
     * Converts a native DWORD IPv4 address (first dotted octet in the low byte) and a port to a socket address.
     *
     * @param address the DWORD address, in the layout {@link #toNativeAddress(InetAddress)} returns
     * @param port    the port
     * @return the socket address
     * @throws UnknownHostException if the address bytes do not form an IPv4 address
     */
    private static @NotNull InetSocketAddress toSocketAddress(int address, int port) throws UnknownHostException {
        byte[] bytes = new byte[IPV4_ADDRESS_SIZE];

        LittleEndianUtils.writeInt32(bytes, 0, address);

        return new InetSocketAddress(InetAddress.getByAddress(bytes), port);
    }

    private static long getCurrentMicroseconds() {
        Instant now = Instant.now();

        return now.getEpochSecond() * MICROSECONDS_PER_SECOND + now.getNano() / NANOSECONDS_PER_MICROSECOND;
    }


    private final @NotNull DatagramLink<@NotNull InetSocketAddress> link;
    private final int localPort;
    private final long key;

    private final @NotNull Map<InetSocketAddress, ProbeState> probes;
    private final @NotNull ByteBuffer receiveBuffer;
    private final @NotNull GameProxyProviderReceiver gate;

    private GameProxySocket(@NotNull DatagramLink<@NotNull InetSocketAddress> link, int localPort, long key) {
        this.link      = link;
        this.localPort = localPort;
        this.key       = key;

        this.probes        = new HashMap<>();
        this.receiveBuffer = ByteBuffer.allocate(MAXIMUM_DATAGRAM);
        this.gate          = new GameProxyProviderReceiver(() -> key);
    }

    /**
     * @return the local port the link receives at
     */
    public int getLocalPort() {
        return this.localPort;
    }


    /**
     * Sends one provider packet.
     *
     * @param address the destination
     * @param packet  the packet
     * @implSpec Wraps the packet in a datagram, with probe fields while the destination's probe state permits them. The address information
     *         of a probe names only the destination: this socket keeps no relay connection, so the sender's address and port are left out.
     */
    @Override
    public void send(@NotNull InetSocketAddress address, @NotNull ProviderPacket packet) {
        ProviderTransportMessage message = ProviderTransportMessage.create(packet);
        int destinationAddress = toNativeAddress(address.getAddress());
        long hash = GameProxyProviderReceiver.isPrivateAddress(destinationAddress)
                    ? GameProxyDatagram.calculatePayloadHash(message.getEncodedBytes()) ^ this.key
                    : 0;
        long now = System.currentTimeMillis();
        ProbeState state = this.probes.computeIfAbsent(address, ignored -> new ProbeState());
        GameProxyDatagram datagram;

        if (now - state.lastProbeTime > PROBE_WINDOW_MILLISECONDS) {
            state.count = 0;
        }

        if (state.count <= PROBE_LIMIT) {
            state.count++;
            state.lastProbeTime = now;

            byte[] addressInformation = GameProxyDatagram.encodeAddressInformation(null, null, null, destinationAddress, address.getPort());

            datagram = GameProxyDatagram.createData(message, hash, getCurrentMicroseconds(), addressInformation);
        } else {
            datagram = GameProxyDatagram.createData(message, hash, 0, new byte[0]);
        }

        this.transmit(address, datagram.getEncodedBytes());
    }

    /**
     * Delivers every datagram received since the last call: answers the probes that pass the receive gate and hands the
     * provider packets to {@code consumer} (for {@link ProviderSession#receive(InetSocketAddress, byte[])}) with their
     * mapped source endpoint.
     *
     * @param consumer the provider packet consumer
     */
    public void poll(@NotNull BiConsumer<InetSocketAddress, byte[]> consumer) {
        ByteBuffer buffer = this.receiveBuffer;

        while (true) {
            InetSocketAddress source;

            buffer.clear();

            try {
                source = this.link.receive(buffer);
            } catch (IOException exception) {
                LOGGER.debug("GAME SOCKET RECEIVE FAILED: " + exception.getMessage());

                return;
            }

            if (source == null) {
                return;
            }

            GameProxyDatagram datagram;

            try {
                datagram = GameProxyDatagram.parse(Arrays.copyOf(buffer.array(), buffer.position()));
            } catch (IOException exception) {
                LOGGER.debug("GAME DATAGRAM DROPPED: " + exception.getMessage());

                continue;
            }

            try {
                this.gate.receive(datagram,
                                  toNativeAddress(source.getAddress()), source.getPort(),
                                  (address, port, answer) -> this.transmit(toSocketAddress(address, port), answer.getEncodedBytes()),
                                  (address, port, packet) -> consumer.accept(toSocketAddress(address, port), packet.getEncodedBytes()));
            } catch (UnsupportedOperationException | IOException exception) {
                LOGGER.debug("GAME DATAGRAM DROPPED: " + exception.getMessage());
            }
        }
    }

    private void transmit(@NotNull InetSocketAddress address, byte @NotNull [] payload) {
        try {
            this.link.send(ByteBuffer.wrap(payload), address);
        } catch (IOException exception) {
            LOGGER.debug("GAME DATAGRAM SEND FAILED: " + address + ": " + exception.getMessage());
        }
    }


    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code
     *         Exception}, implementers are <em>strongly</em> encouraged to
     *         declare concrete implementations of the {@code close} method to
     *         throw more specific exceptions, or to throw no exception at all
     *         if the close operation cannot fail.
     *
     *         <p>
     *         Cases where the close operation may fail require careful
     *         attention by implementers. It is strongly advised to relinquish
     *         the underlying resources and to internally <em>mark</em> the
     *         resource as closed, prior to throwing the exception. The {@code
     *         close} method is unlikely to be invoked more than once and so
     *         this ensures that the resources are released in a timely manner.
     *         Furthermore it reduces problems that could arise when the resource
     *         wraps, or is wrapped, by another resource.
     *
     *         <p>
     *         <em>Implementers of this interface are also strongly advised
     *         to not have the {@code close} method throw {@link
     *         InterruptedException}.</em>
     *         <p>
     *         This exception interacts with a thread's interrupted status,
     *         and runtime misbehavior is likely to occur if an {@code
     *         InterruptedException} is {@linkplain Throwable#addSuppressed
     *         suppressed}.
     *         <p>
     *         More generally, if it would cause problems for an
     *         exception to be suppressed, the {@code AutoCloseable.close}
     *         method should not throw it.
     *
     *         <p>
     *         Note that unlike the {@link java.io.Closeable#close close}
     *         method of {@link java.io.Closeable}, this {@code close} method
     *         is <em>not</em> required to be idempotent.  In other words,
     *         calling this {@code close} method more than once may have some
     *         visible side effect, unlike {@code Closeable.close} which is
     *         required to have no effect if called more than once.
     *         <p>
     *         However, implementers of this interface are strongly encouraged
     *         to make their {@code close} methods idempotent.
     * @implSpec Closes the link.
     */
    @Override
    public void close() {
        try {
            this.link.close();
        } catch (IOException exception) {
            LOGGER.debug("GAME SOCKET CLOSE FAILED: " + exception.getMessage());
        }
    }
}
