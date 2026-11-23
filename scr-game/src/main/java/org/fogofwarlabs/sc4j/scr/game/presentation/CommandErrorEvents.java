package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.scr.game.production.PlacementResult;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitRacesUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Retains the command error messages that game code raised during the current frame, for presentation.
 * <p>
 * Failed cost, supply and placement tests record sounds and status text. The consumer applies replay and local-player checks; recording a call
 * changes no game state and draws no random number.
 * <p>
 * Game code fills the queue during a loop iteration; the replay session clears it before the next iteration's commands, and the client reads it after
 * each iteration without clearing it.
 */
public final class CommandErrorEvents {
    /**
     * The race value of an event whose sound uses the local console race.
     */
    public static final int CONSOLE_RACE = -1;
    /**
     * The race value of an event whose sound has no race.
     */
    public static final int NO_RACE = -2;
    /**
     * The player value of an event shown to every player (no local-player test).
     */
    public static final int EVERY_PLAYER = -1;

    /**
     * The text id of no error: a target or order test that accepts records no text, and every test of the selected units and the targeting click
     * compares its error with this one value.
     */
    public static final int NO_ERROR_TEXT = -1;
    /**
     * {@code stat_txt} id of the Zerg "Not enough energy." text; Terran and Protoss follow.
     */
    public static final int TEXT_NOT_ENOUGH_ENERGY = 0x360;
    /**
     * {@code stat_txt} id of the invalid spell target text.
     */
    public static final int TEXT_INVALID_SPELL_TARGET = 0x36C;
    /**
     * {@code stat_txt} id of "Nuclear launch detected.".
     */
    public static final int TEXT_NUCLEAR_LAUNCH_DETECTED = 0x417;

    /**
     * {@code stat_txt} id (1-based) of "Underling limit exceeded." (Zerg); Terran and Protoss follow.
     */
    static final int TEXT_SUPPLY_LIMIT = 0x34F;
    /**
     * {@code stat_txt} id of the Zerg "Too many underlings" text; Terran and Protoss follow.
     */
    static final int TEXT_INSUFFICIENT_SUPPLY = 0x34C;
    /**
     * {@code stat_txt} id of "Not enough minerals...mine more minerals.".
     */
    static final int TEXT_INSUFFICIENT_MINERALS = 0x352;
    /**
     * {@code stat_txt} id of "Not enough Vespene gas...harvest more gas.".
     */
    static final int TEXT_INSUFFICIENT_GAS = 0x353;
    /**
     * {@code stat_txt} id of "You can't build there.".
     */
    static final int TEXT_CANNOT_BUILD_THERE = 0x358;
    /**
     * {@code stat_txt} id of the geyser-depleted text.
     */
    static final int TEXT_GAS_DEPLETED = 0x36B;

    /**
     * The text id of an event without a {@code stat_txt} text: a keyed text or the error-message sound.
     */
    private static final int NO_TEXT_IDENTIFIER = 0;
    /**
     * The pool index of an event without a source unit.
     */
    private static final int NO_SOURCE_UNIT = 0;
    /**
     * The initial event capacity.
     */
    private static final int INITIAL_CAPACITY = 10;

    /**
     * The sound of an error message.
     */
    public enum Sound {
        /**
         * The {@code SND_Z_ERROR} sound plus the race.
         */
        SUPPLY_LIMIT,
        /**
         * Race key {@code insufficientFood}, default {@code SND_Z_NEED_POWER}.
         */
        INSUFFICIENT_SUPPLY,
        /**
         * Race key {@code insufficientMinerals}, default {@code SND_Z_NEED_MINERALS}.
         */
        INSUFFICIENT_MINERALS,
        /**
         * Race key {@code insufficientGas}, default {@code SND_Z_NEED_GAS}.
         */
        INSUFFICIENT_GAS,
        /**
         * Race key {@code blockedBuildLocation}, default {@code SND_Z_CANT_BUILD_THERE}.
         */
        BLOCKED_BUILD_LOCATION,
        /**
         * Race key {@code invalidBuildLocation}, default {@code SND_Z_BUILD_INTERRUPTED}.
         */
        INVALID_BUILD_LOCATION,
        /**
         * The {@code SND_GAS_DEPLETED} sound.
         */
        GAS_DEPLETED,
        /**
         * The {@code SND_Z_ERROR} sound plus the race for a unit error.
         */
        ERROR,
        /**
         * The {@code SND_T_ERROR} sound played after writing an error to the message lines; no status-line text.
         */
        ERROR_MESSAGE,
        /**
         * No sound: the status-line text only.
         */
        NONE
    }

    /**
     * One error message.
     */
    public static final class Event {
        private final int player;
        private final int textIdentifier;
        private final @Nullable String textKey;
        private final @NotNull Sound sound;
        private final int race;
        private final int poolIndex;

        /**
         * Creates an {@link Event} instance for a command error with an id text.
         *
         * @param player         the player compared with the local player
         * @param textIdentifier the status-line string id
         * @param sound          the error sound
         * @param race           the sound race
         * @param poolIndex      the source unit pool index, or {@code 0} for none
         */
        Event(int player, int textIdentifier, @NotNull Sound sound, int race, int poolIndex) {
            this(player, textIdentifier, null, sound, race, poolIndex);
        }

        /**
         * Creates an {@link Event} instance for a command error.
         *
         * @param player         the player compared with the local player
         * @param textIdentifier the status-line string id
         * @param textKey        the localized string key, or {@code null} for an id text
         * @param sound          the error sound
         * @param race           the sound race
         * @param poolIndex      the source unit pool index, or {@code 0} for none
         */
        Event(int player, int textIdentifier, @Nullable String textKey, @NotNull Sound sound, int race, int poolIndex) {
            this.player         = player;
            this.textIdentifier = textIdentifier;
            this.textKey        = textKey;
            this.sound          = sound;
            this.race           = race;
            this.poolIndex      = poolIndex;
        }

        /**
         * @return the player the output routine compares with the local player
         */
        public int getPlayer() {
            return this.player;
        }

        /**
         * @return the 1-based {@code stat_txt} id of the status-line text, or {@code 0} for a keyed text or no text
         */
        public int getTextIdentifier() {
            return this.textIdentifier;
        }

        /**
         * @return the key of a localized Remastered string ({@code strMAXUNITS}, {@code strCANTCREATE}, {@code strEXITBLOCKED}) that replaces the {@code stat_txt} id, or {@code null} for an id text
         */
        public @Nullable String getTextKey() {
            return this.textKey;
        }

        /**
         * @return the sound
         */
        public @NotNull Sound getSound() {
            return this.sound;
        }

        /**
         * @return the race index of the sound (Zerg {@code 0}, Terran {@code 1}, Protoss {@code 2}, other {@code 4}), {@link #CONSOLE_RACE} or {@link #NO_RACE}
         */
        public int getRace() {
            return this.race;
        }

        /**
         * @return the pool index of the unit that talks and marks the alert position, or {@code 0} for none
         */
        public int getPoolIndex() {
            return this.poolIndex;
        }
    }

    private final @NotNull FrameEventBuffer<Event> events;

    /**
     * Creates a {@link CommandErrorEvents} instance with event storage.
     */
    public CommandErrorEvents() {
        this.events = FrameEventBuffer.createAppending(INITIAL_CAPACITY);
    }

    /**
     * @return the number of retained events
     */
    public int getCount() {
        return this.events.getCount();
    }

    /**
     * @param index the event position
     * @return the event
     */
    public @NotNull Event get(int index) {
        return this.events.get(index);
    }


    /**
     * Records a supply error with the race-specific supply-limit or insufficient-supply text.
     *
     * @param owner         the player
     * @param race          the race of the tested type
     * @param limitExceeded whether the maximum was exceeded
     */
    public void raiseSupply(int owner, int race, boolean limitExceeded) {
        if (limitExceeded) {
            this.events.add(new Event(owner, TEXT_SUPPLY_LIMIT + race, Sound.SUPPLY_LIMIT, race, NO_SOURCE_UNIT));
        } else {
            this.events.add(new Event(owner, TEXT_INSUFFICIENT_SUPPLY + race, Sound.INSUFFICIENT_SUPPLY, race, NO_SOURCE_UNIT));
        }
    }

    /**
     * Records a resource error with the console race; minerals are tested before gas.
     *
     * @param owner           the player
     * @param insufficientGas whether the gas was short (the minerals sufficed)
     */
    public void raiseResources(int owner, boolean insufficientGas) {
        if (insufficientGas) {
            this.events.add(new Event(owner, TEXT_INSUFFICIENT_GAS, Sound.INSUFFICIENT_GAS, CONSOLE_RACE, NO_SOURCE_UNIT));
        } else {
            this.events.add(new Event(owner, TEXT_INSUFFICIENT_MINERALS, Sound.INSUFFICIENT_MINERALS, CONSOLE_RACE, NO_SOURCE_UNIT));
        }
    }

    /**
     * Records "You can't build there." for a builder whose site failed when it arrived.
     *
     * @param game the game state
     * @param unit the builder
     * @throws NullPointerException if the world was built without game data
     */
    public void raiseBlockedBuildLocation(@NotNull Game game, @NotNull Unit unit) {
        this.events.add(new Event(unit.getOwnerIndex(),
                                  TEXT_CANNOT_BUILD_THERE,
                                  Sound.BLOCKED_BUILD_LOCATION,
                                  UnitRacesUtils.getDisplayRace(game.requireGameData().getUnitTypes(), unit.getUnitId()),
                                  unit.getIndex()));
    }

    /**
     * Records the invalid-build-location text of a placement code.
     *
     * @param game the game state
     * @param unit the unit being placed
     * @param code the placement code
     * @throws NullPointerException if the world was built without game data
     */
    public void raiseInvalidBuildLocation(@NotNull Game game, @NotNull Unit unit, int code) {
        this.events.add(new Event(unit.getOwnerIndex(),
                                  PlacementResult.getMessageId(code),
                                  Sound.INVALID_BUILD_LOCATION,
                                  UnitRacesUtils.getDisplayRace(game.requireGameData().getUnitTypes(), unit.getUnitId()),
                                  unit.getIndex()));
    }

    /**
     * Records the geyser-depleted message for the refinery's owner.
     *
     * @param refinery the depleted refinery
     */
    public void raiseGasDepleted(@NotNull Unit refinery) {
        this.events.add(new Event(refinery.getOwnerIndex(), TEXT_GAS_DEPLETED, Sound.GAS_DEPLETED, NO_RACE, NO_SOURCE_UNIT));
    }

    /**
     * Records a unit error using the unit's display race or the console race without a unit.
     *
     * @param game           the game state
     * @param unit           the unit, or {@code null}
     * @param textIdentifier the 1-based {@code stat_txt} id
     * @param player         the player compared with the local one
     * @throws NullPointerException if the world was built without game data
     */
    public void raiseUnitError(@NotNull Game game, @Nullable Unit unit, int textIdentifier, int player) {
        this.events.add(new Event(player,
                                  textIdentifier,
                                  Sound.ERROR,
                                  unit == null ? CONSOLE_RACE
                                               : UnitRacesUtils.getDisplayRace(game.requireGameData().getUnitTypes(), unit.getUnitId()),
                                  NO_SOURCE_UNIT));
    }

    /**
     * Records the caster's race-specific "Not enough energy." status text without sound.
     *
     * @param game   the game state
     * @param caster the caster
     * @throws NullPointerException if the world was built without game data
     */
    public void raiseNotEnoughEnergy(@NotNull Game game, @NotNull Unit caster) {
        int race = UnitRacesUtils.getSupplyRace(game.requireGameData().getUnitTypes(), caster.getUnitId());

        this.events.add(new Event(caster.getOwnerIndex(), TEXT_NOT_ENOUGH_ENERGY + race, Sound.NONE, NO_RACE, NO_SOURCE_UNIT));
    }

    /**
     * Records status-line text without a sound.
     *
     * @param player         the player compared with the local one
     * @param textIdentifier the 1-based {@code stat_txt} id
     */
    public void raiseStatusText(int player, int textIdentifier) {
        this.events.add(new Event(player, textIdentifier, Sound.NONE, NO_RACE, NO_SOURCE_UNIT));
    }

    /**
     * Records the pending creation or placement-search message by its localized string key.
     *
     * @param player  the player compared with the local one
     * @param textKey the string key
     */
    public void raiseStatusKey(int player, @NotNull String textKey) {
        this.events.add(new Event(player, NO_TEXT_IDENTIFIER, textKey, Sound.NONE, NO_RACE, NO_SOURCE_UNIT));
    }

    /**
     * Records the error-message sound without local-player or replay checks; message-line text was already written.
     */
    public void raiseErrorMessage() {
        this.events.add(new Event(EVERY_PLAYER, NO_TEXT_IDENTIFIER, Sound.ERROR_MESSAGE, NO_RACE, NO_SOURCE_UNIT));
    }

    /**
     * Records "Nuclear launch detected." for every player; its launch sound is a separate sound event.
     */
    public void raiseNuclearLaunchDetected() {
        this.events.add(new Event(EVERY_PLAYER, TEXT_NUCLEAR_LAUNCH_DETECTED, Sound.NONE, NO_RACE, NO_SOURCE_UNIT));
    }

    /**
     * Discards events from a position on after the interface already displayed them.
     *
     * @param count the number of events kept
     */
    public void truncate(int count) {
        this.events.truncate(count);
    }

    /**
     * Discards the events of the previous game-loop iteration.
     */
    public void clear() {
        this.events.clear();
    }
}
