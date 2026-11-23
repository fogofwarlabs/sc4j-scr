package org.fogofwarlabs.sc4j.scr.client.glue;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Stores menu choices across starts and applies option changes immediately.
 * The client persists game type, subtype, speed and option values in its settings file.
 */
public interface GlueSettings {
    /**
     * No value stored.
     */
    int NONE = -1;

    /**
     * The create screens' "Game Subtype" (the settings file's {@code createGame.subtype}).
     */
    @NotNull String SUBTYPE = "subtype";
    /**
     * The create screens' "Game Speed" (the settings file's {@code createGame.speed}).
     */
    @NotNull String SPEED = "speed";
    /**
     * The create screens' turn rate (the settings file's {@code createGame.turnRate}).
     */
    @NotNull String TURN_RATE = "turnRate";

    /**
     * Creates a {@link GlueSettings} instance held in memory for this run, without client options.
     *
     * @return the created {@link GlueSettings} instance
     */
    static @NotNull GlueSettings createInMemory() {
        return createInMemory(Map.of());
    }

    /**
     * Creates a {@link GlueSettings} instance held in memory for this run (screenshot harnesses).
     *
     * @param options the option values the settings start with, by key
     * @return the created {@link GlueSettings} instance
     */
    static @NotNull GlueSettings createInMemory(@NotNull Map<String, Object> options) {
        return new GlueSettings() {
            private final @NotNull Map<String, Object> values;
            private int gameType;

            private final @NotNull Map<String, Integer> createGame;

            {
                this.values   = new HashMap<>(options);
                this.gameType = NONE;

                this.createGame = new HashMap<>();
            }

            /**
             * @return the game type identifier last chosen on the create screen ({@code 2} Melee, {@code 3} Free For All, {@code 10} Use Map Settings), or {@link #NONE}
             */
            @Override
            public int getCreateGameType() {
                return this.gameType;
            }

            /**
             * Sets the game type chosen on the create screen.
             *
             * @param gameType the game type identifier
             */
            @Override
            public void setCreateGameType(int gameType) {
                this.gameType = gameType;
            }

            /**
             * Reads a remembered create-screen subtype, speed or turn rate value independently of the game type.
             *
             * @param key {@link #SUBTYPE}, {@link #SPEED} or {@link #TURN_RATE}
             * @return the value, or {@link #NONE}
             */
            @Override
            public int getCreateGameValue(@NotNull String key) {
                return this.createGame.getOrDefault(key, NONE);
            }

            /**
             * Sets a create screen value other than the game type.
             *
             * @param key   {@link #SUBTYPE}, {@link #SPEED} or {@link #TURN_RATE}
             * @param value the value
             */
            @Override
            public void setCreateGameValue(@NotNull String key, int value) {
                this.createGame.put(key, value);
            }

            /**
             * Returns the current value of a client option, by its settings file key.
             *
             * @param key the key as {@code group.key}, for example {@code graphics.vsync}
             * @return the value ({@link Boolean}, {@link Integer} or {@link String}), or {@code null} when no value is stored
             */
            @Override
            public @Nullable Object getOption(@NotNull String key) {
                return this.values.get(key);
            }

            /**
             * Sets and applies a client option chosen in the options popup.
             * <p>
             * The in-memory settings store the value only when its key already exists.
             *
             * @param key   the key as {@code group.key}
             * @param value the value: {@link Boolean}, {@link Integer} or {@link String}
             */
            @Override
            public void setOption(@NotNull String key, @NotNull Object value) {
                if (this.values.containsKey(key)) {
                    this.values.put(key, value);
                }
            }
        };
    }


    /**
     * @return the game type identifier last chosen on the create screen ({@code 2} Melee, {@code 3} Free For All, {@code 10} Use Map Settings), or {@link #NONE}
     */
    int getCreateGameType();

    /**
     * Sets the game type chosen on the create screen.
     *
     * @param gameType the game type identifier
     */
    void setCreateGameType(int gameType);

    /**
     * Reads a remembered create-screen subtype, speed or turn rate value independently of the game type.
     *
     * @param key {@link #SUBTYPE}, {@link #SPEED} or {@link #TURN_RATE}
     * @return the value, or {@link #NONE}
     */
    default int getCreateGameValue(@NotNull String key) {
        return NONE;
    }

    /**
     * Sets a create screen value other than the game type.
     *
     * @param key   {@link #SUBTYPE}, {@link #SPEED} or {@link #TURN_RATE}
     * @param value the value
     */
    default void setCreateGameValue(@NotNull String key, int value) {
    }

    /**
     * Returns the current value of a client option, by its settings file key.
     *
     * @param key the key as {@code group.key}, for example {@code graphics.vsync}
     * @return the value ({@link Boolean}, {@link Integer} or {@link String}), or {@code null} when the client does not have the option
     */
    @Nullable Object getOption(@NotNull String key);

    /**
     * Sets and applies a client option chosen in the options popup.
     *
     * @param key   the key as {@code group.key}
     * @param value the value: {@link Boolean}, {@link Integer} or {@link String}
     */
    void setOption(@NotNull String key, @NotNull Object value);

    /**
     * Returns whether an option can be changed now: the options page disables some controls by the client's state.
     *
     * @param key the key as {@code group.key}
     * @return {@code true} if the option's control is enabled, {@code false} otherwise
     */
    default boolean isOptionEnabled(@NotNull String key) {
        return true;
    }
}
