package org.fogofwarlabs.sc4j.scr.net.beaconlink;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.RoomSettings;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanGameInformation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * Holds the SC:R mod's data about a Beacon.Link room, which the room advertisement carries unread ({@link RoomSettings#getModData()}): the
 * game type and its subtype, the game speed and the map size, which the game list shows and filters by before a join.
 * <p>
 * Encoded, big endian: the format ({@value #FORMAT}), the game type and the subtype (two bytes each), the speed (one byte), and the map width
 * and height in tiles (two bytes each). Data of another format or length decodes to nothing.
 */
public final class ScrRoomData {
    /**
     * The format of the encoded data.
     */
    public static final int FORMAT = 1;
    /**
     * The length of the encoded data.
     */
    public static final int ENCODED_LENGTH = 10;

    private static final int UNSIGNED_BYTE_MAXIMUM = 0xff;
    private static final int UNSIGNED_SHORT_MAXIMUM = 0xffff;

    /**
     * Creates a {@link ScrRoomData} instance from a game's advertised attributes.
     *
     * @param information the game's attributes
     * @return the created {@link ScrRoomData} instance
     * @throws IllegalArgumentException if an attribute is out of its encoded range
     */
    public static @NotNull ScrRoomData of(@NotNull LanGameInformation information) {
        return new ScrRoomData(information.getGameType(), information.getSubtype(), information.getGameSpeed(),
                               information.getMapWidth(), information.getMapHeight());
    }

    /**
     * Decodes a room's mod data.
     *
     * @param data the room's mod data
     * @return the decoded data, or {@code null} if the data is of another format or length
     */
    public static @Nullable ScrRoomData decode(byte @NotNull [] data) {
        if (data.length != ENCODED_LENGTH) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);

        if (Byte.toUnsignedInt(buffer.get()) != FORMAT) {
            return null;
        }

        int gameType = Short.toUnsignedInt(buffer.getShort());
        int subtype = Short.toUnsignedInt(buffer.getShort());
        int speed = Byte.toUnsignedInt(buffer.get());
        int mapWidth = Short.toUnsignedInt(buffer.getShort());
        int mapHeight = Short.toUnsignedInt(buffer.getShort());

        return new ScrRoomData(gameType, subtype, speed, mapWidth, mapHeight);
    }

    private static int checkRange(@NotNull String name, int value, int maximum) {
        if (value < 0 || value > maximum) {
            throw Exceptions.newIllegalArgumentException("The {0} {1} is outside 0..{2}.", name, value, maximum);
        }

        return value;
    }


    private final int gameType;
    private final int subtype;
    private final int speed;

    private final int mapWidth;
    private final int mapHeight;

    /**
     * Creates a {@link ScrRoomData} instance.
     *
     * @param gameType  the game type identifier
     * @param subtype   the game type's subtype
     * @param speed     the game speed, {@code 0..6}
     * @param mapWidth  the map width in tiles
     * @param mapHeight the map height in tiles
     * @throws IllegalArgumentException if a value is out of its encoded range
     */
    public ScrRoomData(int gameType, int subtype, int speed, int mapWidth, int mapHeight) {
        this.gameType = checkRange("game type", gameType, UNSIGNED_SHORT_MAXIMUM);
        this.subtype  = checkRange("subtype", subtype, UNSIGNED_SHORT_MAXIMUM);
        this.speed    = checkRange("speed", speed, UNSIGNED_BYTE_MAXIMUM);

        this.mapWidth  = checkRange("map width", mapWidth, UNSIGNED_SHORT_MAXIMUM);
        this.mapHeight = checkRange("map height", mapHeight, UNSIGNED_SHORT_MAXIMUM);
    }

    /**
     * @return the game type identifier
     */
    public int getGameType() {
        return this.gameType;
    }

    /**
     * @return the game type's subtype
     */
    public int getSubtype() {
        return this.subtype;
    }

    /**
     * @return the game speed, {@code 0..6}
     */
    public int getSpeed() {
        return this.speed;
    }

    /**
     * @return the map width in tiles
     */
    public int getMapWidth() {
        return this.mapWidth;
    }

    /**
     * @return the map height in tiles
     */
    public int getMapHeight() {
        return this.mapHeight;
    }


    /**
     * Encodes the data for a room advertisement.
     *
     * @return the encoded data, {@value #ENCODED_LENGTH} bytes
     */
    public byte @NotNull [] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(ENCODED_LENGTH);

        buffer.put((byte) FORMAT);
        buffer.putShort((short) this.gameType);
        buffer.putShort((short) this.subtype);
        buffer.put((byte) this.speed);
        buffer.putShort((short) this.mapWidth);
        buffer.putShort((short) this.mapHeight);

        return buffer.array();
    }

    /**
     * {@return a string representation of the object}
     * <p>
     * Satisfying this method's contract implies a non-{@code null}
     * result must be returned.
     *
     * @apiNote In general, the
     *         {@code toString} method returns a string that
     *         "textually represents" this object. The result should
     *         be a concise but informative representation that is easy for a
     *         person to read.
     *         It is recommended that all subclasses override this method.
     *         The string output is not necessarily stable over time or across
     *         JVM invocations.
     * @implSpec Returns the type, subtype, speed and map size, for logs.
     */
    @Override
    public @NotNull String toString() {
        return "type " + this.gameType + "/" + this.subtype + ", speed " + this.speed + ", map " + this.mapWidth + "x" + this.mapHeight;
    }
}
