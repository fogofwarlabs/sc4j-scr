package org.fogofwarlabs.sc4j.scr.net.discovery;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.base.text.AsciiCaseUtils;
import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Publishes and discovers LAN game and presence instances using multicast DNS on IPv4 interfaces.
 * Advertisements include ordered service, data and address records with byte-swapped service ports.
 * Cached records expire by their advertised lifetime; goodbye records are removed immediately.
 * A dedicated receiver and scheduled query timer share synchronized discovery state.
 */
public final class LanDiscovery implements AutoCloseable {
    /**
     * The multicast DNS service type of LAN instances.
     */
    public static final @NotNull String SERVICE = "_blizzard._udp.local";

    private static final @NotNull Logger LOGGER = Logger.of(LanDiscovery.class);

    /**
     * The multicast DNS group, its port and the multicast time to live.
     */
    private static final @NotNull String GROUP = "224.0.0.251";
    private static final int PORT = 5353;
    private static final int MULTICAST_TIME_TO_LIVE = 255;

    /**
     * The service enumeration name and the game and presence subtypes.
     */
    private static final @NotNull String SERVICES = "_services._dns-sd._udp.local";
    private static final @NotNull String GAME_SUBTYPE = "_sexpe9._sub." + SERVICE;
    private static final @NotNull String PRESENCE_SUBTYPE = "_pnm._sub." + SERVICE;

    /**
     * The port a presence registers.
     */
    private static final int PRESENCE_PORT = 1;

    /**
     * The suffix this responder appends to the computer name to form its host name.
     */
    private static final @NotNull String HOST_NAME_SUFFIX = "-sc4j.local";

    private static final long SERVICE_TIME_TO_LIVE = 4500;
    private static final long HOST_TIME_TO_LIVE = 120;
    private static final int UNIQUE = MulticastDnsMessage.CLASS_INTERNET | MulticastDnsMessage.CLASS_FLAG;
    private static final int SHARED = MulticastDnsMessage.CLASS_INTERNET;
    private static final byte @NotNull [] EMPTY_TEXT = {0};
    private static final int MAXIMUM_DATAGRAM = 9000;

    private static final int TICK_INTERVAL_MILLISECONDS = 250;
    private static final int FIRST_QUERY_INTERVAL_MILLISECONDS = 1000;
    private static final int MAXIMUM_QUERY_INTERVAL_MILLISECONDS = 30000;
    private static final int ANNOUNCE_INTERVAL_MILLISECONDS = 1000;
    private static final long MILLISECONDS_PER_SECOND = 1000;

    /**
     * The factor the browse query interval grows by after each query.
     */
    private static final int QUERY_INTERVAL_GROWTH = 2;

    /**
     * A game found on the LAN, or on another network of the client ({@code lan.NetworkProvider}).
     */
    public static final class Game {
        private final @NotNull LanGameInformation information;
        private final @NotNull InetSocketAddress address;
        private final long revision;

        /**
         * Creates a {@link Game} instance.
         *
         * @param information the game's attributes
         * @param address     the host's game socket address
         * @param revision    the revision of the game's record
         */
        public Game(@NotNull LanGameInformation information, @NotNull InetSocketAddress address, long revision) {
            this.information = information;
            this.address     = address;
            this.revision    = revision;
        }

        /**
         * @return the game's attributes
         */
        public @NotNull LanGameInformation getInformation() {
            return this.information;
        }

        /**
         * @return the host's game protocol address
         */
        public @NotNull InetSocketAddress getAddress() {
            return this.address;
        }

        /**
         * @return the record revision
         */
        public long getRevision() {
            return this.revision;
        }
    }

    /**
     * A cached record of another responder.
     */
    private static final class CachedRecord {
        private final MulticastDnsMessage.@NotNull Record record;
        private final @NotNull InetAddress source;
        private final long expiresAt;

        private CachedRecord(MulticastDnsMessage.@NotNull Record record, @NotNull InetAddress source, long expiresAt) {
            this.record    = record;
            this.source    = source;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * An instance this responder publishes.
     */
    private static final class Published {
        private final @NotNull String instanceName;
        private final @NotNull String subtype;
        private final int port;

        private @NotNull LanRecord record;

        private Published(@NotNull String instanceName, @NotNull String subtype, int port, @NotNull LanRecord record) {
            this.instanceName = instanceName;
            this.subtype      = subtype;
            this.port         = port;

            this.record = record;
        }
    }

    /**
     * Creates a {@link LanDiscovery} instance listening on every IPv4 multicast interface.
     *
     * @return the created {@link LanDiscovery} instance
     * @throws IOException if the multicast socket cannot be opened
     */
    public static @NotNull LanDiscovery open() throws IOException {
        List<NetworkInterface> interfaces = new ArrayList<>();

        for (NetworkInterface candidate : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (candidate.isUp() && !candidate.isLoopback() && candidate.supportsMulticast() && findIpv4(candidate) != null) {
                interfaces.add(candidate);
            }
        }

        MulticastSocket socket = new MulticastSocket(null);

        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));
            socket.setTimeToLive(MULTICAST_TIME_TO_LIVE);
            // Loopback allows discovery clients on this computer to receive these records.
            socket.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true);

            InetAddress group = InetAddress.getByName(GROUP);
            List<NetworkInterface> joined = new ArrayList<>();

            for (NetworkInterface networkInterface : interfaces) {
                try {
                    socket.joinGroup(new InetSocketAddress(group, PORT), networkInterface);
                    joined.add(networkInterface);
                } catch (IOException exception) {
                    LOGGER.debug("MDNS JOIN SKIPPED: " + networkInterface.getName() + ": " + exception.getMessage());
                }
            }

            String computerName = InetAddress.getLocalHost().getHostName();
            int dotIndex = computerName.indexOf('.');
            String hostName = (dotIndex < 0 ? computerName : computerName.substring(0, dotIndex)) + HOST_NAME_SUFFIX;

            return new LanDiscovery(socket, group, joined, hostName);
        } catch (IOException | RuntimeException exception) {
            socket.close();

            throw exception;
        }
    }

    private static @Nullable Inet4Address findIpv4(@NotNull NetworkInterface networkInterface) {
        for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
            if (address instanceof Inet4Address ipv4) {
                return ipv4;
            }
        }

        return null;
    }

    /**
     * Creates the full name of an instance of the service: the instance label in the text form of names, then the service type.
     *
     * @param instanceName the instance name
     * @return the full instance name
     */
    private static @NotNull String createFullInstanceName(@NotNull String instanceName) {
        return MulticastDnsMessage.escapeLabel(instanceName) + "." + SERVICE;
    }

    /**
     * Creates the key of a name in the cache and in the published instances, folded with {@link AsciiCaseUtils}.
     *
     * @param name the name
     * @return the key
     */
    private static @NotNull String createNameKey(@NotNull String name) {
        return AsciiCaseUtils.toLowerCase(name);
    }

    private static @NotNull String createRecordKey(@NotNull String name, int type) {
        return createNameKey(name) + "/" + type;
    }

    /**
     * Swaps the two bytes of a service port, between the port and its {@code SRV} wire value.
     *
     * @param port the port or the wire value
     * @return the swapped value
     */
    private static int swapPortBytes(int port) {
        return Short.toUnsignedInt(Short.reverseBytes((short) port));
    }


    private final @NotNull MulticastSocket socket;
    private final @NotNull InetAddress group;
    private final @NotNull List<NetworkInterface> interfaces;
    private final @NotNull String hostName;

    private final @NotNull Map<String, CachedRecord> cache;
    private final @NotNull Map<String, Published> published;
    private int queryInterval;
    private long nextQueryTime;

    private final @NotNull ScheduledExecutorService scheduler;
    private volatile boolean closed;

    private LanDiscovery(@NotNull MulticastSocket socket,
                         @NotNull InetAddress group,
                         @NotNull List<NetworkInterface> interfaces,
                         @NotNull String hostName) {
        this.socket     = socket;
        this.group      = group;
        this.interfaces = interfaces;
        this.hostName   = hostName;

        this.cache         = new HashMap<>();
        this.published     = new HashMap<>();
        this.queryInterval = FIRST_QUERY_INTERVAL_MILLISECONDS;
        this.nextQueryTime = 0;

        Thread receiver = new Thread(this::receiveDatagrams, "lan-discovery");

        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "lan-discovery-timer");

            thread.setDaemon(true);

            return thread;
        });
        this.closed    = false;

        receiver.setDaemon(true);
        receiver.start();
        this.scheduler.scheduleWithFixedDelay(this::onTick, 0, TICK_INTERVAL_MILLISECONDS, TimeUnit.MILLISECONDS);
    }

    /**
     * @return the host name this responder answers for
     */
    public @NotNull String getHostName() {
        return this.hostName;
    }

    /**
     * Lists the games found on the LAN, except the one this responder publishes.
     *
     * @return the games, ordered by name
     */
    public synchronized @NotNull List<Game> getGames() {
        long now = System.currentTimeMillis();
        List<Game> games = new ArrayList<>();

        this.cache.values().removeIf(cached -> cached.expiresAt <= now);

        for (CachedRecord pointer : this.cache.values()) {
            MulticastDnsMessage.Record record = pointer.record;

            if (record.getType() != MulticastDnsMessage.TYPE_PTR || !record.isNamed(GAME_SUBTYPE)) {
                continue;
            }

            String instance = record.getTarget();

            if (this.isPublished(instance)) {
                continue;
            }

            CachedRecord data = this.cache.get(createRecordKey(instance, MulticastDnsMessage.TYPE_BLIZZARD));

            if (data == null) {
                continue;
            }

            LanRecord lanRecord;

            try {
                lanRecord = LanRecord.parse(data.record.getData());
            } catch (IOException exception) {
                continue;
            }

            LanGameInformation information = LanGameInformation.of(lanRecord);

            if (information == null) {
                continue;
            }

            games.add(new Game(information, this.resolveGameAddress(instance, data.source), lanRecord.getRevision()));
        }

        games.sort((first, second) -> first.information.getName().compareToIgnoreCase(second.information.getName()));

        return games;
    }

    /**
     * Lists the players present on the LAN ({@code _pnm} instances), except this responder's own presence.
     *
     * @return the presences, ordered by toon name
     */
    public synchronized @NotNull List<LanPresenceInformation> getPresences() {
        long now = System.currentTimeMillis();
        List<LanPresenceInformation> presences = new ArrayList<>();

        this.cache.values().removeIf(cached -> cached.expiresAt <= now);

        for (CachedRecord pointer : this.cache.values()) {
            MulticastDnsMessage.Record record = pointer.record;

            if (record.getType() != MulticastDnsMessage.TYPE_PTR || !record.isNamed(PRESENCE_SUBTYPE) || this.isPublished(record.getTarget())) {
                continue;
            }

            CachedRecord data = this.cache.get(createRecordKey(record.getTarget(), MulticastDnsMessage.TYPE_BLIZZARD));

            if (data == null) {
                continue;
            }

            try {
                LanPresenceInformation presence = LanPresenceInformation.of(LanRecord.parse(data.record.getData()));

                if (presence != null) {
                    presences.add(presence);
                }
            } catch (IOException exception) {
                LOGGER.debug("MDNS PRESENCE SKIPPED: " + record.getTarget() + ": " + exception.getMessage());
            }
        }

        presences.sort((first, second) -> first.getToonName().compareToIgnoreCase(second.getToonName()));

        return presences;
    }


    /**
     * Resolves an instance's game address: the address the SRV record came from, else the address the instance's data
     * record came from; the SRV port swapped back, else {@link GameProxySocket#GAME_PORT}.
     * <p>
     * The SRV host's address record is not used: a responder with several interfaces sends one per interface, each with
     * that interface's address, and a receiver on the same computer gets all of them. The sender address of the SRV is the
     * one on the receiving network.
     *
     * @param instance the full instance name
     * @param source   the address the instance's data record came from
     * @return the game address
     */
    private @NotNull InetSocketAddress resolveGameAddress(@NotNull String instance, @NotNull InetAddress source) {
        CachedRecord service = this.cache.get(createRecordKey(instance, MulticastDnsMessage.TYPE_SRV));

        if (service == null) {
            return new InetSocketAddress(source, GameProxySocket.GAME_PORT);
        }

        return new InetSocketAddress(service.source, swapPortBytes(service.record.getPort()));
    }

    /**
     * Restarts the browse queries at their shortest interval, as a list screen does when it opens or refreshes.
     */
    public synchronized void refresh() {
        this.queryInterval = FIRST_QUERY_INTERVAL_MILLISECONDS;
        this.nextQueryTime = 0;
    }


    /**
     * Sets the player's presence instance {@code PNM:<toon>}, publishing it or updating its record.
     *
     * @param record the presence record
     */
    public void setPresence(@NotNull LanRecord record) {
        this.publish(record.getName(), PRESENCE_SUBTYPE, PRESENCE_PORT, record);
    }

    /**
     * Sets the hosted game's instance, publishing it or updating its record.
     *
     * @param record the game record ({@link LanGameInformation#toRecord})
     */
    public void setGame(@NotNull LanRecord record) {
        this.setGame(record, GameProxySocket.GAME_PORT);
    }

    /**
     * Sets the hosted game's instance with another game port in its SRV record, publishing it or updating its record.
     *
     * @param record the game record ({@link LanGameInformation#toRecord})
     * @param port   the game port
     */
    public void setGame(@NotNull LanRecord record, int port) {
        this.publish(record.getName(), GAME_SUBTYPE, port, record);
    }


    /**
     * Withdraws a published instance with goodbye records.
     *
     * @param instanceName the instance name, for example the game name
     */
    public void withdraw(@NotNull String instanceName) {
        Published removed;

        synchronized (this) {
            removed = this.published.remove(createNameKey(createFullInstanceName(instanceName)));
        }

        if (removed != null) {
            this.sendAll(MulticastDnsMessage.RESPONSE_FLAGS, List.of(), this.createPointerRecords(removed, 0), List.of(), false);
        }
    }

    /**
     * Publishes or updates an instance; an instance name longer than one label cannot be registered and is not published.
     *
     * @param instanceName the instance name
     * @param subtype      the subtype the instance is browsed under
     * @param port         the port of the instance's SRV record
     * @param record       the instance's data record
     */
    private void publish(@NotNull String instanceName, @NotNull String subtype, int port, @NotNull LanRecord record) {
        if (instanceName.getBytes(StandardCharsets.UTF_8).length > MulticastDnsMessage.MAXIMUM_LABEL_LENGTH) {
            LOGGER.warning("MDNS REGISTER FAILED: " + instanceName + " is longer than " + MulticastDnsMessage.MAXIMUM_LABEL_LENGTH + " bytes");

            return;
        }

        Published instance;
        boolean added;

        synchronized (this) {
            String lookup = createNameKey(createFullInstanceName(instanceName));

            instance = this.published.get(lookup);
            added    = instance == null;

            if (added) {
                instance = new Published(instanceName, subtype, port, record);
                this.published.put(lookup, instance);
            } else {
                instance.record = record;
            }
        }

        Published announced = instance;
        // A new instance is announced in full, an update as its changed data record; both twice, one announce interval apart.
        Runnable announce = added ? () -> this.announce(announced) : () -> this.announceData(announced);

        announce.run();
        this.scheduler.schedule(announce, ANNOUNCE_INTERVAL_MILLISECONDS, TimeUnit.MILLISECONDS);
    }


    /**
     * @param instance the full name of an instance, for example a browse pointer's target
     * @return {@code true} if this responder publishes the instance, {@code false} otherwise
     */
    private synchronized boolean isPublished(@NotNull String instance) {
        return this.published.containsKey(createNameKey(instance));
    }


    private @NotNull List<MulticastDnsMessage.Record> createPointerRecords(@NotNull Published instance, long timeToLive) {
        String name = createFullInstanceName(instance.instanceName);

        return List.of(MulticastDnsMessage.Record.createPointer(instance.subtype, SHARED, timeToLive, name),
                       MulticastDnsMessage.Record.createPointer(SERVICE, SHARED, timeToLive, name));
    }

    private @NotNull List<MulticastDnsMessage.Record> createInstanceRecords(@NotNull Published instance) {
        String name = createFullInstanceName(instance.instanceName);

        return List.of(MulticastDnsMessage.Record.createData(name, MulticastDnsMessage.TYPE_TXT, UNIQUE, SERVICE_TIME_TO_LIVE, EMPTY_TEXT),
                       MulticastDnsMessage.Record.createData(name,
                                                             MulticastDnsMessage.TYPE_BLIZZARD,
                                                             UNIQUE,
                                                             SERVICE_TIME_TO_LIVE,
                                                             instance.record.encode()),
                       MulticastDnsMessage.Record.createService(name, UNIQUE, HOST_TIME_TO_LIVE, swapPortBytes(instance.port), this.hostName));
    }

    private @NotNull MulticastDnsMessage.Record createInstanceNextSecureRecord(@NotNull Published instance) {
        return MulticastDnsMessage.Record.createNextSecure(createFullInstanceName(instance.instanceName),
                                                           UNIQUE,
                                                           SERVICE_TIME_TO_LIVE,
                                                           MulticastDnsMessage.TYPE_TXT,
                                                           MulticastDnsMessage.TYPE_SRV,
                                                           MulticastDnsMessage.TYPE_BLIZZARD);
    }

    /**
     * Announces a new instance in record order: TXT, the service enumeration PTR, the subtype and service PTRs, the LAN record, SRV; then the host's
     * address and NSEC records and the instance's NSEC.
     *
     * @param instance the new instance
     */
    private void announce(@NotNull Published instance) {
        List<MulticastDnsMessage.Record> records = this.createInstanceRecords(instance);
        List<MulticastDnsMessage.Record> answers = new ArrayList<>();

        answers.add(records.get(0));
        answers.add(MulticastDnsMessage.Record.createPointer(SERVICES, SHARED, SERVICE_TIME_TO_LIVE, SERVICE));
        answers.addAll(this.createPointerRecords(instance, SERVICE_TIME_TO_LIVE));
        answers.addAll(records.subList(1, records.size()));
        this.sendAll(MulticastDnsMessage.RESPONSE_FLAGS, List.of(), answers, List.of(this.createInstanceNextSecureRecord(instance)), false);
    }

    private void announceData(@NotNull Published instance) {
        LanRecord record;

        synchronized (this) {
            record = instance.record;
        }

        MulticastDnsMessage.Record data = MulticastDnsMessage.Record.createData(createFullInstanceName(instance.instanceName),
                                                                                MulticastDnsMessage.TYPE_BLIZZARD,
                                                                                UNIQUE,
                                                                                SERVICE_TIME_TO_LIVE,
                                                                                record.encode());

        this.sendAll(MulticastDnsMessage.RESPONSE_FLAGS, List.of(), List.of(data), List.of(this.createInstanceNextSecureRecord(instance)), false);
    }

    /**
     * Sends the browse queries of both subtypes when they are due, at an interval that grows by
     * {@link #QUERY_INTERVAL_GROWTH} up to {@link #MAXIMUM_QUERY_INTERVAL_MILLISECONDS}.
     */
    private void onTick() {
        if (this.closed) {
            return;
        }

        long now = System.currentTimeMillis();

        synchronized (this) {
            if (now < this.nextQueryTime) {
                return;
            }

            this.nextQueryTime = now + this.queryInterval;
            this.queryInterval = Math.min(this.queryInterval * QUERY_INTERVAL_GROWTH, MAXIMUM_QUERY_INTERVAL_MILLISECONDS);
        }

        List<MulticastDnsMessage.Question> questions = List.of(
                new MulticastDnsMessage.Question(GAME_SUBTYPE, MulticastDnsMessage.TYPE_PTR, SHARED),
                new MulticastDnsMessage.Question(PRESENCE_SUBTYPE, MulticastDnsMessage.TYPE_PTR, SHARED)
        );

        this.sendAll(0, questions, List.of(), List.of(), false);
    }

    /**
     * Sends a message on every interface; the host's address record carries the interface's own address.
     *
     * @param flags       the message flags
     * @param questions   the questions
     * @param answers     the answer records
     * @param additionals the additional records
     * @param hostAnswer  {@code true} to send the host's address record as an answer, {@code false} to add it to the additional records of a message that announces a service record
     */
    private void sendAll(int flags,
                         @NotNull List<MulticastDnsMessage.Question> questions,
                         @NotNull List<MulticastDnsMessage.Record> answers,
                         @NotNull List<MulticastDnsMessage.Record> additionals,
                         boolean hostAnswer) {
        boolean hostAdditional = !hostAnswer && answers.stream().anyMatch(record -> record.getType() == MulticastDnsMessage.TYPE_SRV &&
                                                                                    record.getTimeToLive() > 0);

        for (NetworkInterface networkInterface : this.interfaces) {
            Inet4Address address = findIpv4(networkInterface);

            if (address == null) {
                continue;
            }

            MulticastDnsMessage.Record host = MulticastDnsMessage.Record.createData(this.hostName,
                                                                                    MulticastDnsMessage.TYPE_A,
                                                                                    UNIQUE,
                                                                                    HOST_TIME_TO_LIVE,
                                                                                    address.getAddress());
            List<MulticastDnsMessage.Record> answerRecords = new ArrayList<>(answers);
            List<MulticastDnsMessage.Record> additionalRecords = new ArrayList<>(additionals);

            if (hostAnswer) {
                answerRecords.add(host);
            } else if (hostAdditional) {
                additionalRecords.add(0, host);
                additionalRecords.add(1, MulticastDnsMessage.Record.createNextSecure(this.hostName, UNIQUE, HOST_TIME_TO_LIVE,
                                                                                     MulticastDnsMessage.TYPE_A));
            }

            byte[] payload = MulticastDnsMessage.create(flags, questions, answerRecords, List.of(), additionalRecords).encode();

            try {
                synchronized (this.socket) {
                    this.socket.setNetworkInterface(networkInterface);
                    this.socket.send(new DatagramPacket(payload, payload.length, this.group, PORT));
                }
            } catch (IOException exception) {
                LOGGER.debug("MDNS SEND FAILED: " + networkInterface.getName() + ": " + exception.getMessage());
            }
        }
    }

    private void receiveDatagrams() {
        byte[] buffer = new byte[MAXIMUM_DATAGRAM];

        while (!this.closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            try {
                this.socket.receive(packet);
            } catch (SocketException exception) {
                if (!this.closed) {
                    LOGGER.warning("MDNS RECEIVE STOPPED: " + exception.getMessage());
                }

                return;
            } catch (IOException exception) {
                continue;
            }

            MulticastDnsMessage message;

            try {
                message = MulticastDnsMessage.parse(Arrays.copyOf(packet.getData(), packet.getLength()));
            } catch (IOException exception) {
                continue;
            }

            if (message.isResponse()) {
                this.store(message, packet.getAddress());
            } else {
                this.answer(message);
            }
        }
    }

    private synchronized void store(@NotNull MulticastDnsMessage message, @NotNull InetAddress source) {
        long now = System.currentTimeMillis();

        for (MulticastDnsMessage.Record record : message.getAllRecords()) {
            String cacheKey = record.getType() == MulticastDnsMessage.TYPE_PTR
                              ? createRecordKey(record.getName() + ">" + record.getTarget(), record.getType())
                              : createRecordKey(record.getName(), record.getType());

            if (record.getTimeToLive() == 0) {
                // A goodbye: the list drops it at once instead of keeping it one more second.
                this.cache.remove(cacheKey);

                continue;
            }

            this.cache.put(cacheKey, new CachedRecord(record, source, now + record.getTimeToLive() * MILLISECONDS_PER_SECOND));
        }
    }

    /**
     * Answers the questions this responder has records for, in one multicast response per interface, without the answers
     * and additional records the query's known answers suppress ({@link MulticastDnsResponse}).
     *
     * @param query the received query
     */
    private void answer(@NotNull MulticastDnsMessage query) {
        List<Published> instances;

        synchronized (this) {
            instances = new ArrayList<>(this.published.values());
        }

        if (instances.isEmpty()) {
            return;
        }

        MulticastDnsResponse response = new MulticastDnsResponse();
        boolean hostAnswer = false;

        for (MulticastDnsMessage.Question question : query.getQuestions()) {
            String name = question.getName();
            int type = question.getType();
            boolean anyType = type == MulticastDnsMessage.TYPE_ANY;

            if (MulticastDnsMessage.areNamesEqual(name, SERVICES) && (anyType || type == MulticastDnsMessage.TYPE_PTR)) {
                response.addAnswer(MulticastDnsMessage.Record.createPointer(SERVICES, SHARED, SERVICE_TIME_TO_LIVE, SERVICE), List.of());
            }

            for (Published instance : instances) {
                boolean browsed = MulticastDnsMessage.areNamesEqual(name, instance.subtype) || MulticastDnsMessage.areNamesEqual(name, SERVICE);

                if (browsed && (anyType || type == MulticastDnsMessage.TYPE_PTR)) {
                    String target = createFullInstanceName(instance.instanceName);
                    List<MulticastDnsMessage.Record> additionals = new ArrayList<>(this.createInstanceRecords(instance));

                    additionals.add(this.createInstanceNextSecureRecord(instance));
                    response.addAnswer(MulticastDnsMessage.Record.createPointer(name, SHARED, SERVICE_TIME_TO_LIVE, target), additionals);
                } else if (MulticastDnsMessage.areNamesEqual(name, createFullInstanceName(instance.instanceName))) {
                    for (MulticastDnsMessage.Record record : this.createInstanceRecords(instance)) {
                        if (anyType || record.getType() == type) {
                            response.addAnswer(record, List.of());
                        }
                    }
                }
            }

            if (MulticastDnsMessage.areNamesEqual(name, this.hostName) && (anyType || type == MulticastDnsMessage.TYPE_A)) {
                hostAnswer = true;
            }
        }

        response.suppressKnownAnswers(query.getAnswers());

        List<MulticastDnsMessage.Record> answers = response.getAnswers();

        if (answers.isEmpty() && !hostAnswer) {
            return;
        }

        this.sendAll(MulticastDnsMessage.RESPONSE_FLAGS, List.of(), answers, response.getAdditionals(), hostAnswer);
    }


    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code
     * Exception}, implementers are <em>strongly</em> encouraged to
     * declare concrete implementations of the {@code close} method to
     * throw more specific exceptions, or to throw no exception at all
     * if the close operation cannot fail.
     *
     * <p>
     * Cases where the close operation may fail require careful
     * attention by implementers. It is strongly advised to relinquish
     * the underlying resources and to internally <em>mark</em> the
     * resource as closed, prior to throwing the exception. The {@code
     * close} method is unlikely to be invoked more than once and so
     * this ensures that the resources are released in a timely manner.
     * Furthermore it reduces problems that could arise when the resource
     * wraps, or is wrapped, by another resource.
     *
     * <p>
     * <em>Implementers of this interface are also strongly advised
     * to not have the {@code close} method throw {@link
     * InterruptedException}.</em>
     * <p>
     * This exception interacts with a thread's interrupted status,
     * and runtime misbehavior is likely to occur if an {@code
     * InterruptedException} is {@linkplain Throwable#addSuppressed
     * suppressed}.
     * <p>
     * More generally, if it would cause problems for an
     * exception to be suppressed, the {@code AutoCloseable.close}
     * method should not throw it.
     *
     * <p>
     * Note that unlike the {@link java.io.Closeable#close close}
     * method of {@link java.io.Closeable}, this {@code close} method
     * is <em>not</em> required to be idempotent.  In other words,
     * calling this {@code close} method more than once may have some
     * visible side effect, unlike {@code Closeable.close} which is
     * required to have no effect if called more than once.
     * <p>
     * However, implementers of this interface are strongly encouraged
     * to make their {@code close} methods idempotent.
     * @implSpec Withdraws every published instance with goodbye records, stops the timer and closes the socket, which ends the
     * receiver thread.
     */
    @Override
    public void close() {
        List<Published> instances;

        synchronized (this) {
            instances = new ArrayList<>(this.published.values());
            this.published.clear();
        }

        for (Published instance : instances) {
            this.sendAll(MulticastDnsMessage.RESPONSE_FLAGS, List.of(), this.createPointerRecords(instance, 0), List.of(), false);
        }

        this.closed = true;
        this.scheduler.shutdownNow();
        this.socket.close();
    }
}
