package org.fogofwarlabs.sc4j.scr.client.glue.score;

import org.fogofwarlabs.sc4j.scr.client.ui.console.ConsoleLayout;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommand;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceTimers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Stores end-of-game score rows, sort order and animated values for {@link GlueScoreScreen}.
 * <p>
 * Overview totals unit, structure and resource scores. Units and Structures combine production and kill scores.
 * Resources show gas, minerals and spent resources. Values count once per fill-timer firing, at most once per pump.
 */
public final class ScoreScreen {
    /**
     * The number of tabs (radio buttons {@code 3..6}).
     */
    public static final int TABS = 4;
    /**
     * The number of value columns of a tab.
     */
    public static final int COLUMNS = 3;
    /**
     * The number of row slots of the dialog.
     */
    public static final int MAXIMUM_ROWS = 8;

    /**
     * The control id of the first tab of {@code gluScore.ui.json}.
     */
    public static final int FIRST_TAB_CONTROL = 3;
    /**
     * The score screen confirmation control id.
     */
    public static final int OK_CONTROL = 7;
    /**
     * The replay save control id of the score screen.
     */
    public static final int SAVE_REPLAY_CONTROL = 61;
    /**
     * The replay playback control id of the score screen.
     */
    public static final int WATCH_REPLAY_CONTROL = 63;

    /**
     * The period of the counter fill timer in milliseconds.
     */
    static final int FILL_TICK_MILLISECONDS = 20;
    /**
     * The fill timer id.
     */
    private static final int FILL_TIMER = 0x22;
    /**
     * A counter moves by an eighth of its remaining distance ...
     */
    private static final int FILL_DIVISOR = 8;
    /**
     * ... and at least by this many scaled units.
     */
    private static final int FILL_MINIMUM_STEP = 4;
    /**
     * The value range used to choose finer counter multipliers.
     */
    private static final int FILL_SCALE_RANGE = 256;
    private static final int FILL_FINE_LIMIT = 8;
    private static final int FILL_FINE_MULTIPLIER = 8;
    private static final int FILL_FINEST_MULTIPLIER = 16;

    /**
     * The outcome bonus applied to the local human row, subtracted after defeat.
     */
    private static final long SORT_TEAM_BONUS = 0x3FFFFFFF;
    /**
     * The sort key is {@code score * 8 - position + 8}.
     */
    private static final int SORT_SCORE_FACTOR = 8;

    /**
     * The milliseconds in a minute for actions-per-minute calculations.
     */
    private static final long MILLISECONDS_PER_MINUTE = 60000;
    private static final int PLAYER_ID_COUNT = 256;
    private static final int MAXIMUM_RACE = 2;

    private static final @NotNull String SCORE_FOLDER_PREFIX = "glue/score";
    private static final @NotNull String PALETTE_FOLDER_PREFIX = "glue/pal";
    private static final @NotNull String VICTORY_SUFFIX = "v";
    private static final @NotNull String DEFEAT_SUFFIX = "d";
    private static final @NotNull String VICTORY_MUSIC_SUFFIX = "vict";
    private static final @NotNull String DEFEAT_MUSIC_SUFFIX = "defeat";

    /**
     * The player record types included in score rows.
     */
    private static final int COMPUTER = PlayerTypes.COMPUTER;
    private static final int HUMAN = PlayerTypes.HUMAN;
    private static final int LEFT_HUMAN = PlayerTypes.PLAYER_LEFT;
    private static final int REMOVED_COMPUTER = PlayerTypes.COMPUTER_LEFT;

    /**
     * The score tabs and their column-header text keys.
     */
    public enum Tab {
        /**
         * Totals unit, structure and resource scores.
         */
        OVERVIEW("scoreUNITS", "scoreINFRASTRUCTURE", "scoreRESOURCES"),
        /**
         * Shows unit production, kills and losses.
         */
        UNITS("scoreUBUILT", "scoreUKILLED", "scoreULOST"),
        /**
         * Shows structure production, razings and losses.
         */
        STRUCTURES("scoreSBUILT", "scoreSDESTROYED", "scoreSLOST"),
        /**
         * Shows gathered gas, minerals and spending.
         */
        RESOURCES("scoreRGASES", "scoreRMINERALS", "scoreRSPENT");

        private final @NotNull String @NotNull [] headers;

        Tab(@NotNull String @NotNull ... headers) {
            this.headers = headers;
        }

        /**
         * @return the {@code gluAll} keys of the three column headers
         */
        public @NotNull String @NotNull [] getHeaders() {
            return this.headers.clone();
        }
    }

    /**
     * Receives the sound events of the counting fill.
     */
    public interface FillListener {
        /**
         * Called when counting starts for the selected tab.
         *
         * @param counting whether counting audio should play
         */
        void onFillStarted(boolean counting);

        /**
         * Called when every displayed counter has reached its target.
         */
        void onFillEnded();
    }

    /**
     * One player's record.
     */
    public static final class Row {
        private final int player;
        private final @NotNull String name;
        private final @NotNull String forceName;
        private final int race;
        private final int color;
        private final boolean computer;
        /**
         * Whether the player is shown in {@link #customColor}: the game keeps its custom colors.
         */
        private final boolean customColorShown;
        /**
         * The packed {@code 0xRRGGBBAA} custom color.
         */
        private final int customColor;

        private final long @NotNull [] @NotNull [] values;

        /**
         * Creates a {@link Row} instance without a force name.
         *
         * @param player   the slot
         * @param name     the shown name
         * @param race     {@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss
         * @param color    the player color index
         * @param computer whether the player is a computer (no team bonus in the sort)
         * @param values   per tab: the three column values and the row score
         */
        public Row(int player, @NotNull String name, int race, int color, boolean computer, long @NotNull [] @NotNull [] values) {
            this(player, name, "", race, color, computer, values);
        }

        /**
         * Creates a {@link Row} instance.
         *
         * @param player    the slot
         * @param name      the shown name
         * @param forceName the force name shown under the player name, or an empty string
         * @param race      {@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss
         * @param color     the player color index
         * @param computer  whether the player is a computer (no team bonus in the sort)
         * @param values    per tab: the three column values and the row score
         */
        public Row(int player,
                   @NotNull String name,
                   @NotNull String forceName,
                   int race,
                   int color,
                   boolean computer,
                   long @NotNull [] @NotNull [] values) {
            this(player, name, forceName, race, color, false, 0, computer, values);
        }

        /**
         * Creates a {@link Row} instance.
         *
         * @param player           the slot
         * @param name             the shown name
         * @param forceName        the force name shown under the player name, or an empty string
         * @param race             {@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss
         * @param color            the player color index
         * @param customColorShown whether the player is shown in its custom color
         * @param customColor      the packed {@code 0xRRGGBBAA} custom color
         * @param computer         whether the player is a computer (no team bonus in the sort)
         * @param values           per tab: the three column values and the row score
         */
        public Row(int player,
                   @NotNull String name,
                   @NotNull String forceName,
                   int race,
                   int color,
                   boolean customColorShown, int customColor,
                   boolean computer,
                   long @NotNull [] @NotNull [] values) {
            this.player           = player;
            this.name             = name;
            this.forceName        = forceName;
            this.race             = race;
            this.color            = color;
            this.customColorShown = customColorShown;
            this.customColor      = customColor;
            this.computer         = computer;

            this.values = values;
        }

        /**
         * @return the slot
         */
        public int getPlayer() {
            return this.player;
        }

        /**
         * @return the shown name
         */
        public @NotNull String getName() {
            return this.name;
        }

        /**
         * @return the force name drawn under the name, or an empty string
         */
        public @NotNull String getForceName() {
            return this.forceName;
        }

        /**
         * @return {@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss
         */
        public int getRace() {
            return this.race;
        }

        /**
         * @return the player color index
         */
        public int getColor() {
            return this.color;
        }

        /**
         * @return {@code true} if the player is shown in {@link #getCustomColor}, {@code false} if in its color index
         */
        public boolean isCustomColorShown() {
            return this.customColorShown;
        }

        /**
         * @return the packed {@code 0xRRGGBBAA} custom color
         */
        public int getCustomColor() {
            return this.customColor;
        }

        /**
         * @return {@code true} if the player is a computer, {@code false} otherwise
         */
        public boolean isComputer() {
            return this.computer;
        }

        /**
         * @param tab    the tab
         * @param column the column {@code 0..2}, or {@code 3} for the row score
         * @return the value
         */
        public long getValue(@NotNull Tab tab, int column) {
            return this.values[tab.ordinal()][column];
        }
    }

    /**
     * A counting value of a shown row: the scaled current value moves to the scaled target.
     */
    public static final class Counter {
        private final int multiplier;
        private final long target;

        private long current;
        private boolean shown;

        /**
         * Creates a {@link Counter} instance counting from {@code 0} to a value.
         *
         * @param value the raw value to count to
         */
        private Counter(long value) {
            long quotient = value == 0 ? 0 : FILL_SCALE_RANGE / value;

            this.multiplier = quotient <= 1 ? 1 : quotient <= FILL_FINE_LIMIT ? FILL_FINE_MULTIPLIER : FILL_FINEST_MULTIPLIER;
            this.target     = value * this.multiplier;

            this.current = 0;
            this.shown   = false;
        }

        /**
         * @return the shown value: the scaled current value divided by the multiplier
         */
        public long getValue() {
            return this.current / this.multiplier;
        }

        /**
         * @return the shown value without the integer division, {@code current / multiplier} as a fraction
         */
        public double getExactValue() {
            return this.current / (double) this.multiplier;
        }

        /**
         * @return {@code true} if the text has been written by a tick, {@code false} otherwise
         */
        public boolean isShown() {
            return this.shown;
        }


        /**
         * Moves the scaled current value towards the target and marks the text as written.
         *
         * @return {@code true} if the value had not reached the target and moved, {@code false} otherwise
         */
        private boolean tick() {
            this.shown = true;

            if (this.current == this.target) {
                return false;
            }

            long step = Math.max((this.target - this.current) / FILL_DIVISOR, FILL_MINIMUM_STEP);

            this.current = Math.min(this.target, this.current + step);

            return true;
        }
    }

    /**
     * Computes each slot's actions per minute from every recorded command and the elapsed game time.
     *
     * @param commands            the recorded commands
     * @param slotOfPlayerId      the slot of each command player id ({@code 256} entries, {@code -1} for none)
     * @param elapsedMilliseconds the game time
     * @return the actions per minute of slots {@code 0..7}
     */
    public static int @NotNull [] computeActionsPerMinute(@NotNull List<ReplayCommand> commands,
                                                          int @NotNull [] slotOfPlayerId,
                                                          long elapsedMilliseconds) {
        long[] actions = new long[MAXIMUM_ROWS];

        for (ReplayCommand command : commands) {
            int playerId = command.getPlayerId();
            int slot = playerId >= 0 && playerId < PLAYER_ID_COUNT && playerId < slotOfPlayerId.length ? slotOfPlayerId[playerId] : -1;

            if (slot >= 0 && slot < MAXIMUM_ROWS) {
                actions[slot]++;
            }
        }

        int[] perMinute = new int[MAXIMUM_ROWS];

        if (elapsedMilliseconds <= 0) {
            return perMinute;
        }

        for (int slot = 0; slot < MAXIMUM_ROWS; slot++) {
            perMinute[slot] = (int) (actions[slot] * MILLISECONDS_PER_MINUTE / elapsedMilliseconds);
        }

        return perMinute;
    }

    /**
     * Collects rows for active and departed players from unit accounting and player score records.
     *
     * @param view         the game view after the outcome
     * @param names        the player names by slot, {@code null} entries for none
     * @param computerName the name of computer players in a multiplayer game
     * @param multiplayer  whether the game is a multiplayer game
     * @param forceNames   the force names shown under player names, or {@code null} when none are shown
     * @return the rows in record order
     */
    static @NotNull List<Row> collectRows(@NotNull ScrGameView view,
                                          @Nullable String @NotNull [] names,
                                          @NotNull String computerName,
                                          boolean multiplayer,
                                          @NotNull String @Nullable [] forceNames) {
        List<Row> collected = new ArrayList<>();
        UnitAccounting accounting = view.getPlayerTable().getUnitAccounting();
        PlayerScores scores = accounting == null ? null : accounting.getScores();

        if (accounting == null) {
            return collected;
        }

        PlayerColorRows colors = view.getPlayerTable().getPlayerColorRows();

        for (int player = 0; player < MAXIMUM_ROWS; player++) {
            Player record = view.getPlayerTable().getPlayer(player);

            if (record == null) {
                continue;
            }

            int type = record.getType();
            boolean computer = type == COMPUTER || type == REMOVED_COMPUTER;

            if (!computer && type != HUMAN && type != LEFT_HUMAN) {
                continue;
            }

            String slotName = names.length > player ? names[player] : null;

            if (slotName == null) {
                slotName = "";
            }

            // Omit unnamed computer slots from single-player score rows.
            if (!multiplayer && computer && slotName.isEmpty()) {
                continue;
            }

            String name = multiplayer && computer ? computerName : slotName;
            long unitScore = scores.getUnitKillScore(player) + accounting.getUnitProductionScore(player);
            long structureScore = scores.getRazingScore(player) + accounting.getBuildingProductionScore(player);
            long gas = Integer.toUnsignedLong(record.getGatheredGas());
            long minerals = Integer.toUnsignedLong(record.getGatheredMinerals());
            long spent = Math.max(0, gas + minerals - Integer.toUnsignedLong(record.getGas()) - Integer.toUnsignedLong(record.getMinerals()));
            long[][] values = {
                    {unitScore, structureScore, gas + minerals, unitScore + structureScore + gas + minerals},
                    {accounting.getProducedUnitCount(player), scores.getUnitsKilled(player), scores.getUnitsLost(player), unitScore},
                    {accounting.getProducedBuildingCount(player), scores.getBuildingsRazed(player), scores.getBuildingsLost(player), structureScore},
                    {gas, minerals, spent, gas + minerals},
                    };

            // Use the supplied force name only for valid one-based force indices.
            int force = record.getTeam();
            String forceName = forceNames != null && force >= 1 && force <= forceNames.length ? forceNames[force - 1] : "";

            // A slot's color permutation entry names the map color the score shows, where the map's COLR section gave one.
            int color = record.getColor();

            if (colors != null && color >= 0 && color < PlayerTable.PLAYABLE_PLAYER_SLOTS && colors.getMapColor(color) != 0) {
                color = colors.getMapColor(color);
            }

            boolean custom = colors != null && colors.hasCustomColor(player);

            collected.add(new Row(player, name, forceName, record.getRace(), color, custom, custom ? colors.getCustomColor(player) : 0, computer,
                                  values));
        }

        return collected;
    }

    private final @NotNull List<Row> rows;
    private final int localPlayer;
    private final boolean victory;
    private final int race;
    private final long elapsedSeconds;
    private final int @NotNull [] actionsPerMinute;
    private @NotNull Set<@NotNull String> mapForceNames;

    private @Nullable FillListener fillListener;
    private @Nullable Tab tab;
    private final @NotNull List<Row> order;
    private @NotNull Counter @NotNull [] @NotNull [] counters;
    private final long @NotNull [] columnMaxima;
    private boolean filling;
    private long fillStart;
    private @Nullable InterfaceTimers timers;

    private boolean replaySaved;
    private boolean replaySavingAllowed;
    private @Nullable Path watchedReplay;

    /**
     * Creates a {@link ScoreScreen} instance from the game counters. A single player game names computer players by their slot's name.
     *
     * @param view           the game view after the outcome
     * @param names          the player names by slot, {@code null} entries for none
     * @param computerName   the name of computer players in a multiplayer game ({@code network.json strCOMPUTER})
     * @param multiplayer    whether the game is a multiplayer game
     * @param forceNames     the force names shown under player names, or {@code null} when none are shown
     * @param localPlayer    the local player's slot
     * @param victory        whether the local player won
     * @param race           the local race: {@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss (the background folder)
     * @param elapsedSeconds the game time
     */
    public ScoreScreen(@NotNull ScrGameView view,
                       @Nullable String @NotNull [] names,
                       @NotNull String computerName,
                       boolean multiplayer,
                       @NotNull String @Nullable [] forceNames,
                       int localPlayer,
                       boolean victory,
                       int race,
                       long elapsedSeconds) {
        this(collectRows(view, names, computerName, multiplayer, forceNames), localPlayer, victory, race, elapsedSeconds);
    }

    /**
     * Creates a {@link ScoreScreen} instance from collected rows.
     *
     * @param rows           the rows in record order
     * @param localPlayer    the local player's slot
     * @param victory        whether the local player won
     * @param race           the local race: {@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss (the background folder)
     * @param elapsedSeconds the game time
     */
    public ScoreScreen(@NotNull List<Row> rows, int localPlayer, boolean victory, int race, long elapsedSeconds) {
        this.rows             = List.copyOf(rows.subList(0, Math.min(rows.size(), MAXIMUM_ROWS)));
        this.localPlayer      = localPlayer;
        this.victory          = victory;
        this.race             = Math.clamp(race, 0, MAXIMUM_RACE);
        this.elapsedSeconds   = elapsedSeconds;
        this.actionsPerMinute = new int[MAXIMUM_ROWS];
        this.mapForceNames    = Set.of();

        this.fillListener = null;
        this.tab          = null;
        this.order        = new ArrayList<>();
        this.counters     = new Counter[0][];
        this.columnMaxima = new long[COLUMNS];
        this.filling      = false;
        this.fillStart    = 0;
        this.timers       = null;

        this.replaySaved         = false;
        this.replaySavingAllowed = true;
        this.watchedReplay       = null;
    }

    /**
     * @return the rows in record order
     */
    public @NotNull List<Row> getRows() {
        return this.rows;
    }

    /**
     * @return the shown rows in their sorted order; empty before the panel locks
     */
    public @NotNull List<Row> getShownRows() {
        return this.order;
    }

    /**
     * @param shownRow the index in {@link #getShownRows()}
     * @param column   the column {@code 0..2}, or {@code 3} for the row score
     * @return the counting value
     */
    public @NotNull Counter getCounter(int shownRow, int column) {
        return this.counters[shownRow][column];
    }

    /**
     * @param column the column {@code 0..2}
     * @return the largest raw value of the column over the shown rows, the full bar length
     */
    public long getColumnMaximum(int column) {
        return this.columnMaxima[column];
    }

    /**
     * @return the local player's slot
     */
    public int getLocalPlayer() {
        return this.localPlayer;
    }

    /**
     * @return the selected tab, or {@code null} before the panel locks
     */
    public @Nullable Tab getTab() {
        return this.tab;
    }

    /**
     * @return {@code true} if the local player won, {@code false} otherwise
     */
    public boolean isVictory() {
        return this.victory;
    }

    /**
     * @return the local race {@code 0..2}
     */
    public int getRace() {
        return this.race;
    }

    /**
     * @return the score folder for the race and outcome, for example {@code glue/scoretv}
     */
    public @NotNull String getFolder() {
        return SCORE_FOLDER_PREFIX + ConsoleLayout.RACE_LETTERS.charAt(this.race) + (this.victory ? VICTORY_SUFFIX : DEFEAT_SUFFIX);
    }

    /**
     * @return the glue palette folder for the screen, for example {@code glue/paltv}
     */
    public @NotNull String getPaletteFolder() {
        return PALETTE_FOLDER_PREFIX + ConsoleLayout.RACE_LETTERS.charAt(this.race) + (this.victory ? VICTORY_SUFFIX : DEFEAT_SUFFIX);
    }

    /**
     * Returns the victory or defeat music track for the selected race.
     *
     * @return the track name, for example {@code tvict}
     */
    public @NotNull String getMusicTrack() {
        return ConsoleLayout.RACE_LETTERS.charAt(this.race) + (this.victory ? VICTORY_MUSIC_SUFFIX : DEFEAT_MUSIC_SUFFIX);
    }

    /**
     * @return the game time in seconds
     */
    public long getElapsedSeconds() {
        return this.elapsedSeconds;
    }

    /**
     * @param player the slot
     * @return the slot's actions per minute, {@code 0} when not set
     */
    public int getActionsPerMinute(int player) {
        return player >= 0 && player < MAXIMUM_ROWS ? this.actionsPerMinute[player] : 0;
    }

    /**
     * @return {@code true} if any row has a nonzero actions-per-minute value, {@code false} otherwise
     */
    public boolean hasActionsPerMinute() {
        for (Row row : this.rows) {
            if (this.getActionsPerMinute(row.getPlayer()) != 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * @param forceName a force name of a row ({@link Row#getForceName})
     * @return {@code true} if the force name is the map's name of its force, which shows in the text language, {@code false} otherwise
     */
    public boolean isMapForceName(@NotNull String forceName) {
        return this.mapForceNames.contains(forceName);
    }

    /**
     * @return {@code true} if the values are counting, {@code false} otherwise
     */
    public boolean isFilling() {
        return this.filling;
    }

    /**
     * Returns whether a control is enabled. Watch Replay needs a replay path; Save Replay needs saving to be allowed and not yet done.
     *
     * @param control a control id
     * @return {@code true} if the control is enabled, {@code false} otherwise
     */
    public boolean isEnabled(int control) {
        if (control == WATCH_REPLAY_CONTROL) {
            return this.watchedReplay != null;
        }

        return control != SAVE_REPLAY_CONTROL || !this.replaySaved && this.replaySavingAllowed;
    }

    /**
     * @return {@code true} if the replay has been saved from this screen, {@code false} otherwise
     */
    public boolean isReplaySaved() {
        return this.replaySaved;
    }

    /**
     * @return the replay Watch Replay plays, or {@code null} when the button is disabled
     */
    public @Nullable Path getWatchedReplay() {
        return this.watchedReplay;
    }


    /**
     * Sets the actions per minute of each slot ({@link #computeActionsPerMinute}).
     *
     * @param bySlot the actions per minute of slots {@code 0..7}
     */
    public void setActionsPerMinute(int @NotNull [] bySlot) {
        System.arraycopy(bySlot, 0, this.actionsPerMinute, 0, Math.min(bySlot.length, MAXIMUM_ROWS));
    }

    /**
     * Sets the force names that are the map's names of their forces ({@link #isMapForceName}).
     *
     * @param mapForceNames the map's force names
     */
    public void setMapForceNames(@NotNull Set<@NotNull String> mapForceNames) {
        this.mapForceNames = Set.copyOf(mapForceNames);
    }

    /**
     * Sets whether the replay may be saved from this score screen.
     *
     * @param replaySavingAllowed whether Save Replay can be pressed
     */
    public void setReplaySavingAllowed(boolean replaySavingAllowed) {
        this.replaySavingAllowed = replaySavingAllowed;
    }

    /**
     * Sets the replay path used by Watch Replay, enabling the button while a path is present.
     *
     * @param watchedReplay the written replay of the game, or {@code null} to disable the button
     */
    public void setWatchedReplay(@Nullable Path watchedReplay) {
        this.watchedReplay = watchedReplay;
    }

    /**
     * Sets the replay as saved, disabling further saving from this score screen.
     */
    public void setReplaySaved() {
        this.replaySaved = true;
    }

    /**
     * Sets the receiver of the fill sound events.
     *
     * @param fillListener the receiver, or {@code null}
     */
    public void setFillListener(@Nullable FillListener fillListener) {
        this.fillListener = fillListener;
    }

    /**
     * Sets the fill timer list, canceling the previous timer and arming an active fill on the new list.
     *
     * @param timers the client's interface timers, or {@code null}
     */
    public void setTimers(@Nullable InterfaceTimers timers) {
        if (this.timers != null) {
            this.timers.kill(this, FILL_TIMER);
        }

        this.timers = timers;

        if (timers != null && this.filling) {
            this.armFill(this.fillStart);
        }
    }


    /**
     * Selects a new tab, sorts rows and starts counting; selecting the active tab again has no effect.
     *
     * @param selected        the tab
     * @param nowMilliseconds the current time in milliseconds
     */
    public void selectTab(@NotNull Tab selected, long nowMilliseconds) {
        if (selected == this.tab) {
            return;
        }

        this.tab = selected;
        this.startFill(nowMilliseconds);
    }

    private void startFill(long time) {
        Tab current = this.tab;

        if (current == null) {
            return;
        }

        List<Row> sorted = new ArrayList<>(this.rows);

        // Keep the current best row unless a later row has a strictly greater position-dependent key.
        for (int position = 0; position < sorted.size(); position++) {
            int best = position;
            long bestKey = this.computeSortKey(sorted.get(position), current, position);

            for (int candidate = position + 1; candidate < sorted.size(); candidate++) {
                long key = this.computeSortKey(sorted.get(candidate), current, candidate);

                if (key > bestKey) {
                    best    = candidate;
                    bestKey = key;
                }
            }

            Row swapped = sorted.get(position);

            sorted.set(position, sorted.get(best));
            sorted.set(best, swapped);
        }

        this.order.clear();
        this.order.addAll(sorted);
        this.counters = new Counter[sorted.size()][COLUMNS + 1];
        Arrays.fill(this.columnMaxima, 0);

        for (int shownRow = 0; shownRow < sorted.size(); shownRow++) {
            Row row = sorted.get(shownRow);

            for (int column = 0; column <= COLUMNS; column++) {
                this.counters[shownRow][column] = new Counter(row.getValue(current, column));

                if (column < COLUMNS) {
                    this.columnMaxima[column] = Math.max(this.columnMaxima[column], row.getValue(current, column));
                }
            }
        }

        this.filling   = true;
        this.fillStart = time;
        this.armFill(time);

        if (this.fillListener != null) {
            long maximumTotal = 0;

            for (long maximum : this.columnMaxima) {
                maximumTotal += maximum;
            }

            this.fillListener.onFillStarted(maximumTotal > 0);
        }
    }

    private void armFill(long time) {
        InterfaceTimers current = this.timers;

        if (current != null) {
            current.arm(this, FILL_TIMER, FILL_TICK_MILLISECONDS, (int) time, clock -> this.tickFill());
        }
    }

    private long computeSortKey(@NotNull Row row, @NotNull Tab current, int position) {
        long key = row.getValue(current, COLUMNS) * SORT_SCORE_FACTOR - position + SORT_SCORE_FACTOR;

        // Apply the outcome bonus only to the local human row.
        if (row.isComputer() || row.getPlayer() != this.localPlayer) {
            return key;
        }

        return this.victory ? key + SORT_TEAM_BONUS : key - SORT_TEAM_BONUS;
    }

    private void tickFill() {
        boolean changed = false;

        for (Counter[] row : this.counters) {
            for (Counter counter : row) {
                changed |= counter.tick();
            }
        }

        if (changed) {
            return;
        }

        this.filling = false;

        if (this.timers != null) {
            this.timers.kill(this, FILL_TIMER);
        }

        if (this.fillListener != null) {
            this.fillListener.onFillEnded();
        }
    }
}
