package org.fogofwarlabs.sc4j.scr.game.session.setup;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Holds who plays each slot of a network game restored from a saved game: per saved slot 0..7 the network id and name of
 * the player that the new lobby put there.
 */
public final class SavedGamePlayers {
    /**
     * The network id of a saved slot no player of the new game took.
     */
    public static final int ABSENT = -1;

    private final int @NotNull [] playerIds;
    private final @NotNull String @NotNull [] names;

    /**
     * Creates a {@link SavedGamePlayers} instance in which no saved slot has a player yet.
     */
    public SavedGamePlayers() {
        this.playerIds = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.names     = new String[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        Arrays.fill(this.playerIds, ABSENT);
        Arrays.fill(this.names, "");
    }

    /**
     * @param slot the saved slot 0..7
     * @return the network id of the slot's player in the new game, or {@link #ABSENT}
     */
    public int getPlayerId(int slot) {
        return this.playerIds[slot];
    }

    /**
     * @param slot the saved slot 0..7
     * @return the name of the slot's player in the new game, empty for {@link #ABSENT}
     */
    public @NotNull String getName(int slot) {
        return this.names[slot];
    }

    /**
     * Sets the player of a saved slot.
     *
     * @param slot     the saved slot 0..7
     * @param playerId the player's network id
     * @param name     the player's name
     * @throws IllegalArgumentException if the slot is out of range or already has another player
     */
    public void setPlayer(int slot, int playerId, @NotNull String name) {
        if (slot < 0 || slot >= this.playerIds.length) {
            throw Exceptions.newIllegalArgumentException("Invalid saved slot: {0}", slot);
        }

        if (this.playerIds[slot] != ABSENT && this.playerIds[slot] != playerId) {
            throw Exceptions.newIllegalArgumentException("The saved slot {0} already has player {1}.", slot, this.playerIds[slot]);
        }

        this.playerIds[slot] = playerId;
        this.names[slot]     = name;
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
     * @implSpec Returns the occupied saved slots and their network player ids.
     */
    @Override
    public @NotNull String toString() {
        StringBuilder text = new StringBuilder("SavedGamePlayers[");

        for (int slot = 0; slot < this.playerIds.length; slot++) {
            if (this.playerIds[slot] != ABSENT) {
                text.append(" slot ").append(slot).append(" id ").append(this.playerIds[slot]);
            }
        }

        return text.append(" ]").toString();
    }
}
