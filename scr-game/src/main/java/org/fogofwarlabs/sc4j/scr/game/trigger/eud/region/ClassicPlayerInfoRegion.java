package org.fogofwarlabs.sc4j.scr.game.trigger.eud.region;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayHeaderBuilder;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The classic player table: twelve player records over the game's player records, byte for byte. A record has the layout
 * of the game header's player records ({@link ReplayHeaderBuilder#PLAYER_RECORD_SIZE} bytes): the command index DWORD,
 * the id DWORD, the type, race and force BYTEs and the name, which starts as the game header's record and is kept here
 * (only the interface reads it).
 */
public final class ClassicPlayerInfoRegion implements ClassicRegion {
    /**
     * The classic address of the table.
     */
    public static final int BASE = 0x57EEE0;

    /**
     * The size of a player record.
     */
    private static final int RECORD_SIZE = ReplayHeaderBuilder.PLAYER_RECORD_SIZE;


    private final @NotNull Player @NotNull [] players;
    private final byte @NotNull [] names;


    /**
     * Creates a {@link ClassicPlayerInfoRegion} instance.
     *
     * @param players the twelve players
     * @param header  the game header section ({@link ReplayHeaderBuilder#HEADER_SIZE} bytes) whose records hold the names, or {@code null}
     */
    public ClassicPlayerInfoRegion(@NotNull Player @NotNull [] players, byte @Nullable [] header) {
        this.players = players;
        this.names   = new byte[PlayerTable.PLAYER_SLOTS * RECORD_SIZE];

        if (header != null) {
            for (int player = 0; player < PlayerTable.PLAYER_SLOTS; player++) {
                int record = ReplayHeaderBuilder.PLAYERS_OFFSET + player * RECORD_SIZE;

                if (record + RECORD_SIZE <= header.length) {
                    System.arraycopy(header, record + ReplayHeaderBuilder.PLAYER_NAME_OFFSET,
                                     this.names, player * RECORD_SIZE + ReplayHeaderBuilder.PLAYER_NAME_OFFSET,
                                     RECORD_SIZE - ReplayHeaderBuilder.PLAYER_NAME_OFFSET);
                }
            }
        }
    }


    /**
     * @return the classic base address
     */
    @Override
    public int getBase() {
        return BASE;
    }

    /**
     * @return the size in bytes
     */
    @Override
    public int getSize() {
        return PlayerTable.PLAYER_SLOTS * RECORD_SIZE;
    }


    /**
     * Reads the unsigned byte at an offset.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @return the unsigned byte at the offset
     */
    @Override
    public int readByte(int offset) {
        Player player = this.players[offset / RECORD_SIZE];
        int field = offset % RECORD_SIZE;

        if (field < ReplayHeaderBuilder.PLAYER_ID_OFFSET) {
            return player.getCommandPlayerIndex() >>> field * Byte.SIZE & BYTE_MASK;
        }

        if (field < ReplayHeaderBuilder.PLAYER_TYPE_OFFSET) {
            return player.getId() >>> (field - ReplayHeaderBuilder.PLAYER_ID_OFFSET) * Byte.SIZE & BYTE_MASK;
        }

        return switch (field) {
            case ReplayHeaderBuilder.PLAYER_TYPE_OFFSET -> player.getType() & BYTE_MASK;
            case ReplayHeaderBuilder.PLAYER_RACE_OFFSET -> player.getRace() & BYTE_MASK;
            case ReplayHeaderBuilder.PLAYER_TEAM_OFFSET -> player.getTeam() & BYTE_MASK;
            default -> this.names[offset] & BYTE_MASK;
        };
    }

    /**
     * Stores one byte, changing the domain state that holds it.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @param value  the byte value in the low eight bits
     */
    @Override
    public void writeByte(int offset, int value) {
        Player player = this.players[offset / RECORD_SIZE];
        int field = offset % RECORD_SIZE;

        if (field >= ReplayHeaderBuilder.PLAYER_NAME_OFFSET) {
            this.names[offset] = (byte) value;

            return;
        }

        int commandIndex = player.getCommandPlayerIndex();
        int id = player.getId();
        int type = player.getType();
        int race = player.getRace();
        int team = player.getTeam();
        int shift = (field % Integer.BYTES) * Byte.SIZE;

        if (field < ReplayHeaderBuilder.PLAYER_ID_OFFSET) {
            commandIndex = LittleEndianUtils.replaceByte(commandIndex, shift, value);
        } else if (field < ReplayHeaderBuilder.PLAYER_TYPE_OFFSET) {
            id = LittleEndianUtils.replaceByte(id, shift, value);
        } else if (field == ReplayHeaderBuilder.PLAYER_TYPE_OFFSET) {
            type = value;
        } else if (field == ReplayHeaderBuilder.PLAYER_RACE_OFFSET) {
            race = value;
        } else {
            team = value;
        }

        player.setRecord(commandIndex, id, type, race, team);
    }
}
