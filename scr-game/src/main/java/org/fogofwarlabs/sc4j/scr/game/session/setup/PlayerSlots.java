package org.fogofwarlabs.sc4j.scr.game.session.setup;

import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Maps every player id to the slot of the record the player holds. The map is rebuilt from a record table after the
 * records changed ({@link #update}): a record holds the player its id names when it is a human's or an observer's
 * ({@link #isPlayerRecord}), and a later record wins over an earlier one; an open record that keeps the id of the player
 * who released it maps no one. A player who left maps no slot until the next rebuild ({@link #remove}).
 * <p>
 * The lobby keeps one map through the players' joins and moves; a live game builds its own from the records of the game
 * start, and its commands and other player lookups resolve a player id through it. The game's waiting list only tests its
 * records with {@link #isPlayerRecord}.
 * <p>
 * Every table kept per player, and the lobby's records by slot identifier, use one index ({@link #getPlayerIndex}).
 */
public final class PlayerSlots {
    /**
     * The slot of a player id that holds no record.
     */
    public static final int NO_SLOT = -1;

    /**
     * The player indexes: the eight player ids, then the four observer ids from {@link PlayerTable#FIRST_OBSERVER_SLOT}.
     */
    public static final int PLAYER_INDEXES = PlayerTable.PLAYABLE_PLAYER_SLOTS + PlayerTable.OBSERVER_SLOTS;

    /**
     * Tells whether a player holds a record: a human ({@link PlayerTypes#HUMAN}) or an observer
     * ({@link PlayerTypes#OBSERVER}). An open record may still name the player that released it.
     *
     * @param record the record
     * @return {@code true} if a player holds the record, {@code false} otherwise
     */
    public static boolean isPlayerRecord(MeleeGameSetup.@NotNull PlayerRecord record) {
        return record.getType() == PlayerTypes.HUMAN || record.getType() == PlayerTypes.OBSERVER;
    }

    /**
     * Tells whether a value names a player: a player id {@code 0..7} or an observer id {@code 0x80..0x83}. A lobby slot
     * identifier is valid by the same test.
     *
     * @param playerId the provider player id, or a lobby slot identifier
     * @return {@code true} if the value is a player or an observer id, {@code false} otherwise
     */
    public static boolean isPlayerIdentifier(int playerId) {
        return (playerId >= 0 && playerId < PlayerTable.PLAYABLE_PLAYER_SLOTS) || PlayerTable.isObserverSlot(playerId);
    }

    /**
     * Reads the index of a player id's entries: a player id takes its own value, an observer id {@code 0x80..0x83}
     * takes {@code 8..11}. A lobby slot identifier takes the same index, which is its record's: the player slots
     * {@code 0..7}, then the observer slots {@code 0x80..0x83}. The index is computed without a validity test
     * ({@link #isPlayerIdentifier}), so a value {@code 8..11} takes its own value too.
     *
     * @param playerId the provider player id, or a lobby slot identifier
     * @return the player index {@code 0..11}, or {@code -1} for a value outside them
     */
    public static int getPlayerIndex(int playerId) {
        int index = playerId >= PlayerTable.FIRST_OBSERVER_SLOT ? playerId - PlayerTable.FIRST_OBSERVER_SLOT + PlayerTable.PLAYABLE_PLAYER_SLOTS
                                                                : playerId;

        return playerId < 0 || index >= PLAYER_INDEXES ? -1 : index;
    }

    /**
     * The record index of each player's slot by player index ({@link #getPlayerIndex}), {@link #NO_SLOT} for none.
     */
    private final int @NotNull [] slots;

    /**
     * Creates a {@link PlayerSlots} instance in which no player holds a slot.
     */
    public PlayerSlots() {
        this.slots = new int[PLAYER_INDEXES];

        Arrays.fill(this.slots, NO_SLOT);
    }

    /**
     * @param playerId the provider player id
     * @return the record index of the slot the player holds, {@link #NO_SLOT} when it holds none
     */
    public int getSlot(int playerId) {
        int index = getPlayerIndex(playerId);

        return index < 0 ? NO_SLOT : this.slots[index];
    }

    /**
     * Rebuilds the slot of every player from the records that can hold a player, in record order: each record a player
     * holds ({@link #isPlayerRecord}) maps the player its id names to the record's index, over an earlier record of that
     * id. A lobby's records are the eight player slots and then the observer slots; a game's records hold no observer, so
     * only its eight player slots are scanned and the records after them are not.
     *
     * @param records     the record table
     * @param recordCount the number of records from the first that can hold a player: {@link #PLAYER_INDEXES} for a lobby's records, {@link PlayerTable#PLAYABLE_PLAYER_SLOTS} for a game's
     */
    public void update(MeleeGameSetup.@NotNull PlayerRecord @NotNull [] records, int recordCount) {
        Arrays.fill(this.slots, NO_SLOT);

        for (int recordIndex = 0; recordIndex < recordCount; recordIndex++) {
            MeleeGameSetup.PlayerRecord record = records[recordIndex];
            int index = getPlayerIndex(record.getPlayerId());

            if (isPlayerRecord(record) && index >= 0) {
                this.slots[index] = recordIndex;
            }
        }
    }

    /**
     * Removes the slot of a player who left; the records keep naming the player.
     *
     * @param playerId the provider player id
     */
    public void remove(int playerId) {
        int index = getPlayerIndex(playerId);

        if (index >= 0) {
            this.slots[index] = NO_SLOT;
        }
    }
}
