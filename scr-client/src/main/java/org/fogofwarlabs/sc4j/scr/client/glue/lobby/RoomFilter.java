package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.FoundRoom;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LatencyClass;
import org.fogofwarlabs.sc4j.scr.net.beaconlink.ScrRoomData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the filter of the SC:R Beacon.Link room list, as the install's custom games page filters its games: the engine's room filter
 * ({@link org.fogofwarlabs.sc4j.engine.client.community.RoomFilter}: search text, worst latency, largest player count, closed rooms) with
 * the SC:R game type, and the host's name and the game type's name as further texts the search text is found in.
 * <p>
 * {@link #select} filters the found rooms and orders them as the engine's filter does: the joinable rooms by latency, best first, then by
 * name, followed by the closed rooms (full, started or of another version) in the same order.
 */
public final class RoomFilter {
    /**
     * The game type value that passes every game type.
     */
    static final int ANY_GAME_TYPE = -1;
    /**
     * The smallest and largest of the largest player counts to show; the largest passes every room.
     */
    static final int MINIMUM_PLAYERS = 2;
    static final int MAXIMUM_PLAYERS = 8;

    /**
     * A found room with what the list shows of it.
     */
    static final class Entry {
        private final @NotNull FoundRoom room;
        private final @Nullable ScrRoomData data;

        private Entry(@NotNull FoundRoom room) {
            this.room = room;
            this.data = ScrRoomData.decode(room.getSettings().getModData());
        }

        /**
         * @return the found room
         */
        @NotNull FoundRoom getRoom() {
            return this.room;
        }

        /**
         * @return the SC:R data of the room, or {@code null} if the room carries none
         */
        @Nullable ScrRoomData getData() {
            return this.data;
        }

        /**
         * @return {@code true} if this run can join the room, {@code false} for a full or started room or one of another version or content of the mod
         */
        boolean isJoinable() {
            return this.room.isJoinable();
        }
    }

    /**
     * Names what the search text is found in.
     */
    interface Names {
        /**
         * @param gameType the game type identifier
         * @return the game type's shown name
         */
        @NotNull String getGameTypeName(int gameType);

        /**
         * @param room the room
         * @return the shown name of the room's host, or an empty string if it is not known
         */
        @NotNull String getHostName(@NotNull FoundRoom room);
    }

    private final @NotNull org.fogofwarlabs.sc4j.engine.client.community.RoomFilter general;
    private int gameType;

    /**
     * Creates a {@link RoomFilter} instance that passes every room.
     */
    public RoomFilter() {
        this.general  = new org.fogofwarlabs.sc4j.engine.client.community.RoomFilter();
        this.gameType = ANY_GAME_TYPE;
    }

    /**
     * @return the search text
     */
    @NotNull String getQuery() {
        return this.general.getQuery();
    }

    /**
     * Sets the search text.
     *
     * @param query the text, found in a room's name, game type, map name or host name regardless of case; empty passes every room
     */
    void setQuery(@NotNull String query) {
        this.general.setQuery(query);
    }

    /**
     * @return the game type to show, or {@link #ANY_GAME_TYPE}
     */
    int getGameType() {
        return this.gameType;
    }

    /**
     * Sets the game type to show.
     *
     * @param gameType the game type identifier, or {@link #ANY_GAME_TYPE}
     */
    void setGameType(int gameType) {
        this.gameType = gameType;
    }

    /**
     * @return the worst latency to show, or {@code null} for every latency
     */
    @Nullable LatencyClass getWorstLatency() {
        return this.general.getWorstLatency();
    }

    /**
     * Sets the worst latency to show.
     *
     * @param worstLatency the worst latency class to show, or {@code null} for every latency
     */
    void setWorstLatency(@Nullable LatencyClass worstLatency) {
        this.general.setWorstLatency(worstLatency);
    }

    /**
     * @return the largest player count to show, {@value #MINIMUM_PLAYERS} to {@value #MAXIMUM_PLAYERS}
     */
    int getMaximumPlayers() {
        int capacity = this.general.getMaximumCapacity();

        return capacity == org.fogofwarlabs.sc4j.engine.client.community.RoomFilter.ANY_CAPACITY ? MAXIMUM_PLAYERS : capacity;
    }

    /**
     * Sets the largest player count to show.
     *
     * @param maximumPlayers the count, {@value #MINIMUM_PLAYERS} to {@value #MAXIMUM_PLAYERS}; the largest passes every room
     * @throws IllegalArgumentException if the count is out of range
     */
    void setMaximumPlayers(int maximumPlayers) {
        if (maximumPlayers < MINIMUM_PLAYERS || maximumPlayers > MAXIMUM_PLAYERS) {
            throw Exceptions.newIllegalArgumentException("The player count {0} is outside {1}..{2}.", maximumPlayers, MINIMUM_PLAYERS, MAXIMUM_PLAYERS);
        }

        this.general.setMaximumCapacity(maximumPlayers == MAXIMUM_PLAYERS ? org.fogofwarlabs.sc4j.engine.client.community.RoomFilter.ANY_CAPACITY
                                                                          : maximumPlayers);
    }

    /**
     * @return {@code true} if the closed rooms (full, started or of another version of the mod) are listed, {@code false} otherwise
     */
    boolean isClosedRoomsShown() {
        return this.general.isClosedRoomsShown();
    }

    /**
     * Sets whether the closed rooms (full, started or of another version of the mod) are listed.
     *
     * @param closedRoomsShown whether they are listed
     */
    void setClosedRoomsShown(boolean closedRoomsShown) {
        this.general.setClosedRoomsShown(closedRoomsShown);
    }

    /**
     * Sets every choice to pass every room; the search text stays.
     */
    void reset() {
        this.general.reset();
        this.gameType = ANY_GAME_TYPE;
    }

    /**
     * Copies another filter's choices and search text.
     *
     * @param other the filter to copy
     */
    void copy(@NotNull RoomFilter other) {
        this.general.copy(other.general);
        this.gameType = other.gameType;
    }


    /**
     * Filters and orders found rooms.
     *
     * @param rooms the found rooms, joinable and of other versions
     * @param names the shown names of game types and hosts
     * @return the rooms that pass the filter: the joinable ones first, each part by latency (best first, unknown last) and then by name
     */
    @NotNull List<@NotNull Entry> select(@NotNull List<@NotNull FoundRoom> rooms, @NotNull Names names) {
        List<FoundRoom> selected = this.general.select(rooms, new org.fogofwarlabs.sc4j.engine.client.community.RoomFilter.ModFilter() {
            /**
             * @param room a found room
             * @return {@code true} if the mod's own choices pass the room, {@code false} otherwise
             */
            @Override
            public boolean accepts(@NotNull FoundRoom room) {
                ScrRoomData data = ScrRoomData.decode(room.getSettings().getModData());

                return RoomFilter.this.gameType == ANY_GAME_TYPE || data != null && data.getGameType() == RoomFilter.this.gameType;
            }

            /**
             * Returns the room's texts the search text is also found in besides the room and map names, for example the host's name or the
             * game type.
             *
             * @param room a found room
             * @return the texts, empty for none
             */
            @Override
            public @NotNull List<@NotNull String> getSearchTexts(@NotNull FoundRoom room) {
                ScrRoomData data = ScrRoomData.decode(room.getSettings().getModData());

                return data == null ? List.of(names.getHostName(room)) : List.of(names.getGameTypeName(data.getGameType()), names.getHostName(room));
            }
        });
        List<Entry> entries = new ArrayList<>(selected.size());

        for (FoundRoom room : selected) {
            entries.add(new Entry(room));
        }

        return entries;
    }
}
