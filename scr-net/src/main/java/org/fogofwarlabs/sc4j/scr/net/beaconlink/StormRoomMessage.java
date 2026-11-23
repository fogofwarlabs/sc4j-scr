package org.fogofwarlabs.sc4j.scr.net.beaconlink;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkOverlay;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkParticipant;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanRecord;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Holds one message of the room port of a Beacon.Link game. The host sends its members the game's record (the attributes the LAN discovery
 * advertises, {@link Kind#GAME}) and the room's members with the addresses the host assigned them ({@link Kind#MEMBERS}), so that every
 * member adds every other one to its overlay at the same address; every member reports to the host the members its channels are open to
 * ({@link Kind#CHANNELS}), so that the host starts only when every pair is connected. A message is one overlay datagram: a kind byte, then
 * the record's bytes, or a member count byte and per member its participant ({@value LinkParticipant#LENGTH} bytes: its key and connection
 * number), its IPv4 address (four bytes) and its port (two bytes, big-endian), or a count byte and the participants. {@link #parse} returns
 * {@code null} for a malformed datagram and never throws.
 */
final class StormRoomMessage {
    /**
     * The most members a message lists.
     */
    static final int MAXIMUM_MEMBERS = 16;
    /**
     * The longest record a message carries.
     */
    static final int MAXIMUM_RECORD_LENGTH = LinkOverlay.MAXIMUM_DATAGRAM_LENGTH - Byte.BYTES;

    private static final int UNSIGNED_BYTE_MASK = 0xFF;
    private static final int UNSIGNED_SHORT_MASK = 0xFFFF;
    private static final int IPV4_ADDRESS_SIZE = 4;
    private static final int MEMBER_LENGTH = LinkParticipant.LENGTH + IPV4_ADDRESS_SIZE + Short.BYTES;

    /**
     * The kinds of messages.
     */
    enum Kind {
        /**
         * The game's record.
         */
        GAME(1),
        /**
         * The room's members with their addresses. Code 2 is not assigned.
         */
        MEMBERS(3),
        /**
         * The members a member's channels are open to.
         */
        CHANNELS(4);

        private final int code;

        Kind(int code) {
            this.code = code;
        }

        private static @Nullable Kind of(int code) {
            for (Kind kind : values()) {
                if (kind.code == code) {
                    return kind;
                }
            }

            return null;
        }
    }

    /**
     * Encodes a game message.
     *
     * @param record the game's record
     * @return the datagram
     * @throws IllegalArgumentException if the record is longer than {@value #MAXIMUM_RECORD_LENGTH} bytes
     */
    static byte @NotNull [] encodeGame(@NotNull LanRecord record) {
        byte[] encoded = record.encode();

        if (encoded.length > MAXIMUM_RECORD_LENGTH) {
            throw Exceptions.newIllegalArgumentException("The game record has {0} bytes, more than {1}.", encoded.length, MAXIMUM_RECORD_LENGTH);
        }

        return ByteBuffer.allocate(Byte.BYTES + encoded.length).put((byte) Kind.GAME.code).put(encoded).array();
    }

    /**
     * Encodes a members message.
     *
     * @param members the members with their IPv4 addresses, at most {@value #MAXIMUM_MEMBERS}, in the order they are listed
     * @return the datagram
     * @throws IllegalArgumentException if there are more than {@value #MAXIMUM_MEMBERS} members or an address is not an IPv4 address
     */
    static byte @NotNull [] encodeMembers(@NotNull Map<@NotNull LinkParticipant, @NotNull InetSocketAddress> members) {
        if (members.size() > MAXIMUM_MEMBERS) {
            throw Exceptions.newIllegalArgumentException("A room lists at most {0} members, not {1}.", MAXIMUM_MEMBERS, members.size());
        }

        ByteBuffer buffer = ByteBuffer.allocate(Byte.BYTES + Byte.BYTES + members.size() * MEMBER_LENGTH);

        buffer.put((byte) Kind.MEMBERS.code).put((byte) members.size());

        for (Map.Entry<LinkParticipant, InetSocketAddress> member : members.entrySet()) {
            byte[] address = member.getValue().getAddress().getAddress();

            if (address.length != IPV4_ADDRESS_SIZE) {
                throw Exceptions.newIllegalArgumentException("The member address {0} is not an IPv4 address.", member.getValue());
            }

            buffer.put(member.getKey().getBytes()).put(address).putShort((short) member.getValue().getPort());
        }

        return buffer.array();
    }

    /**
     * Encodes a channels message.
     *
     * @param connected the members the sender's channels are open to, at most {@value #MAXIMUM_MEMBERS}
     * @return the datagram
     * @throws IllegalArgumentException if there are more than {@value #MAXIMUM_MEMBERS} members
     */
    static byte @NotNull [] encodeChannels(@NotNull List<@NotNull LinkParticipant> connected) {
        if (connected.size() > MAXIMUM_MEMBERS) {
            throw Exceptions.newIllegalArgumentException("A report lists at most {0} members, not {1}.", MAXIMUM_MEMBERS, connected.size());
        }

        ByteBuffer buffer = ByteBuffer.allocate(Byte.BYTES + Byte.BYTES + connected.size() * LinkParticipant.LENGTH);

        buffer.put((byte) Kind.CHANNELS.code).put((byte) connected.size());

        for (LinkParticipant member : connected) {
            buffer.put(member.getBytes());
        }

        return buffer.array();
    }

    /**
     * Reads a message.
     *
     * @param datagram the datagram
     * @return the message, or {@code null} if the datagram is not a well-formed message
     */
    static @Nullable StormRoomMessage parse(byte @NotNull [] datagram) {
        if (datagram.length < Byte.BYTES) {
            return null;
        }

        Kind kind = Kind.of(datagram[0] & UNSIGNED_BYTE_MASK);

        if (kind == Kind.GAME) {
            try {
                return new StormRoomMessage(kind, LanRecord.parse(Arrays.copyOfRange(datagram, Byte.BYTES, datagram.length)), Map.of(), List.of());
            } catch (IOException | RuntimeException exception) {
                return null;
            }
        }

        if (kind == null || datagram.length < Byte.BYTES + Byte.BYTES) {
            return null;
        }

        int count = datagram[1] & UNSIGNED_BYTE_MASK;

        if (kind == Kind.CHANNELS) {
            return parseChannels(datagram, count);
        }

        if (count > MAXIMUM_MEMBERS || datagram.length != Byte.BYTES + Byte.BYTES + count * MEMBER_LENGTH) {
            return null;
        }

        Map<LinkParticipant, InetSocketAddress> members = new LinkedHashMap<>();
        ByteBuffer input = ByteBuffer.wrap(datagram, Byte.BYTES + Byte.BYTES, count * MEMBER_LENGTH);

        for (int index = 0; index < count; index++) {
            byte[] participant = new byte[LinkParticipant.LENGTH];
            byte[] address = new byte[IPV4_ADDRESS_SIZE];

            input.get(participant).get(address);

            int port = input.getShort() & UNSIGNED_SHORT_MASK;
            LinkParticipant member = LinkParticipant.of(participant);

            try {
                if (members.put(member, new InetSocketAddress(InetAddress.getByAddress(address), port)) != null) {
                    return null;
                }
            } catch (UnknownHostException exception) {
                return null;
            }
        }

        return new StormRoomMessage(kind, null, members, List.of());
    }

    private static @Nullable StormRoomMessage parseChannels(byte @NotNull [] datagram, int count) {
        if (count > MAXIMUM_MEMBERS || datagram.length != Byte.BYTES + Byte.BYTES + count * LinkParticipant.LENGTH) {
            return null;
        }

        List<LinkParticipant> connected = new ArrayList<>(count);

        for (int index = 0; index < count; index++) {
            int offset = Byte.BYTES + Byte.BYTES + index * LinkParticipant.LENGTH;

            connected.add(LinkParticipant.of(Arrays.copyOfRange(datagram, offset, offset + LinkParticipant.LENGTH)));
        }

        return new StormRoomMessage(Kind.CHANNELS, null, Map.of(), connected);
    }


    private final @NotNull Kind kind;
    private final @Nullable LanRecord record;
    private final @NotNull Map<@NotNull LinkParticipant, @NotNull InetSocketAddress> members;
    private final @NotNull List<@NotNull LinkParticipant> connected;

    private StormRoomMessage(@NotNull Kind kind, @Nullable LanRecord record,
                             @NotNull Map<@NotNull LinkParticipant, @NotNull InetSocketAddress> members,
                             @NotNull List<@NotNull LinkParticipant> connected) {
        this.kind      = kind;
        this.record    = record;
        this.members   = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        this.connected = List.copyOf(connected);
    }

    /**
     * @return the message's kind
     */
    @NotNull Kind getKind() {
        return this.kind;
    }

    /**
     * @return the game's record, or {@code null} unless the kind is {@link Kind#GAME}
     */
    @Nullable LanRecord getRecord() {
        return this.record;
    }

    /**
     * @return the room's members with the addresses the host assigned them, in the host's order, empty unless the kind is {@link Kind#MEMBERS}
     */
    @NotNull Map<@NotNull LinkParticipant, @NotNull InetSocketAddress> getMembers() {
        return this.members;
    }

    /**
     * @return the members the sender's channels are open to, empty unless the kind is {@link Kind#CHANNELS}
     */
    @NotNull List<@NotNull LinkParticipant> getConnected() {
        return this.connected;
    }
}
