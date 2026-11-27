package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.scr.client.ui.text.StatText;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.client.world.ScrPresentation;
import org.fogofwarlabs.sc4j.scr.core.GameSettings;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Refreshes the trigger leaderboard when its timer or visibility toggle marks an update due. Rows follow the trigger context order. Value mode
 * resolves a map or default label; Greed mode shows current player resources. The first valid row is shown independently of the all-player toggle.
 */
public final class Leaderboard {
    /**
     * The number of rows.
     */
    public static final int ROWS = PlayerTable.PLAYABLE_PLAYER_SLOTS;
    /**
     * The timer period in milliseconds.
     */
    public static final int TICK_MILLISECONDS = 1000;

    /**
     * The color boxes {@code -10..-17}.
     */
    public static final int FIRST_BOX = -10;
    /**
     * The value texts {@code -20..-27}.
     */
    public static final int FIRST_VALUE = -20;
    /**
     * The label.
     */
    public static final int LABEL = -28;
    /**
     * The first Greed mineral text control.
     */
    public static final int FIRST_MINERALS = -30;
    /**
     * The first Greed gas text control.
     */
    public static final int FIRST_GAS = -40;
    /**
     * The Greed mineral icon control.
     */
    public static final int MINERALS_ICON = -48;
    /**
     * The Greed gas icon control.
     */
    public static final int GAS_ICON = -49;

    /**
     * The row player of a hidden row, and the trigger context's player index of an empty row.
     */
    private static final int NO_PLAYER = -1;
    /**
     * The leaderboard type of a game without a leaderboard.
     */
    private static final int NO_LEADERBOARD = 0;
    private static final int GREED_TYPE = 40;
    /**
     * Normal and goal leaderboard labels use their respective {@code stat_txt} identifier bases.
     */
    private static final int FIRST_LEADERBOARD = 0x11;
    private static final int LAST_LEADERBOARD = 0x15;
    private static final int FIRST_GOAL = 0x21;
    private static final int LAST_GOAL = 0x25;
    private static final int LEADERBOARD_LABEL_BASE = 0x330;
    private static final int GOAL_LABEL_BASE = 0x325;
    /**
     * The victory conditions of the game settings: the map's default, resources and slaughter.
     */
    private static final int VICTORY_MAP_DEFAULT = 0;
    private static final int VICTORY_RESOURCES = 3;
    private static final int VICTORY_SLAUGHTER = 6;

    /**
     * The shown kind of rows.
     */
    public enum Mode {
        /**
         * Nothing shown.
         */
        NONE,
        /**
         * One value per row and the label.
         */
        VALUES,
        /**
         * Minerals and gas per row.
         */
        GREED
    }

    /**
     * Tests whether the game configuration includes a leaderboard.
     *
     * @param view   the game view
     * @param replay whether displaying a replay
     * @return {@code true} if the console creates a leaderboard dialog, {@code false} otherwise
     */
    public static boolean isCreated(@NotNull ScrGameView view, boolean replay) {
        TriggerContext context = view.getTriggerContext();
        GameSettings settings = view.getGameSettings();
        boolean multiplayer = context != null && context.getMultiplayerSession() != 0;

        if (!multiplayer || replay || settings == null) {
            return true;
        }

        int victory = settings.getVictoryCondition();
        boolean useMapSettings = victory == VICTORY_MAP_DEFAULT && settings.getCreateInitialUnits() == 0 && settings.getTournamentMode() == 0;

        return victory == VICTORY_RESOURCES || victory == VICTORY_SLAUGHTER || useMapSettings;
    }

    private @NotNull Mode mode;
    private boolean allPlayersShown;
    private final int @NotNull [] rowPlayers;
    private final @NotNull String @NotNull [] values;
    private final @NotNull String @NotNull [] minerals;
    private final @NotNull String @NotNull [] gas;
    private @Nullable String label;
    private boolean mapLabel;
    private boolean due;

    /**
     * Creates a {@link Leaderboard} instance with its controls hidden.
     */
    public Leaderboard() {
        this.mode            = Mode.NONE;
        this.allPlayersShown = true;
        this.rowPlayers      = new int[ROWS];
        this.values          = new String[ROWS];
        this.minerals        = new String[ROWS];
        this.gas             = new String[ROWS];
        this.label           = null;
        this.mapLabel        = false;
        this.due             = false;

        Arrays.fill(this.rowPlayers, NO_PLAYER);
        Arrays.fill(this.values, "");
        Arrays.fill(this.minerals, "");
        Arrays.fill(this.gas, "");
    }

    /**
     * @return the shown kind of rows
     */
    public @NotNull Mode getMode() {
        return this.mode;
    }

    /**
     * @param row the row {@code 0..7}
     * @return the player whose color box and texts the row shows, or {@code -1} when the row is hidden
     */
    public int getRowPlayer(int row) {
        return this.rowPlayers[row];
    }

    /**
     * @param row the row {@code 0..7}
     * @return the value text of the row: the leaderboard value as an unsigned decimal
     */
    public @NotNull String getValue(int row) {
        return this.values[row];
    }

    /**
     * @param row the row {@code 0..7}
     * @return the Greed minerals text of the row: the mineral count as a signed decimal
     */
    public @NotNull String getMinerals(int row) {
        return this.minerals[row];
    }

    /**
     * @param row the row {@code 0..7}
     * @return the Greed gas text of the row: the gas count as a signed decimal
     */
    public @NotNull String getGas(int row) {
        return this.gas[row];
    }

    /**
     * @return the label (control {@code -28}), or {@code null} when it is hidden: shown in {@link Mode#VALUES} when the first row has a value text
     */
    public @Nullable String getLabel() {
        return this.label;
    }

    /**
     * @return {@code true} if the label is the map's string, {@code false} if it is the leaderboard type's default or hidden
     */
    public boolean isMapLabel() {
        return this.mapLabel;
    }

    /**
     * @return {@code true} if the rows after the first are shown, {@code false} otherwise
     */
    public boolean isAllPlayersShown() {
        return this.allPlayersShown;
    }

    /**
     * Toggles the rows after the first and marks them for refresh by the next {@link #update}.
     */
    public void toggleAllPlayersShown() {
        this.allPlayersShown = !this.allPlayersShown;
        this.due             = true;
    }

    /**
     * Marks the rows for refresh by the next {@link #update}.
     */
    public void tick() {
        this.due = true;
    }

    /**
     * Refreshes pending leaderboard rows from the game.
     *
     * @param view         the game view
     * @param presentation the presentation state of the game, whose message lines hold the map strings
     * @param text         the {@code stat_txt} strings for the default labels
     */
    public void update(@NotNull ScrGameView view, @NotNull ScrPresentation presentation, @NotNull StatText text) {
        if (!this.due) {
            return;
        }

        this.due = false;

        TriggerContext context = view.getTriggerContext();
        int type = context == null ? NO_LEADERBOARD : context.getLeaderboardType();

        if (type == NO_LEADERBOARD) {
            if (this.mode != Mode.NONE) {
                this.clear();
            }

            return;
        }

        if (type == GREED_TYPE) {
            this.showGreed(view, context);
        } else {
            this.showValues(view, presentation, context, type, text);
        }
    }

    private void clear() {
        this.mode     = Mode.NONE;
        this.label    = null;
        this.mapLabel = false;

        Arrays.fill(this.rowPlayers, NO_PLAYER);
    }

    /**
     * Updates each shown value row, then resolves its label.
     */
    private void showValues(@NotNull ScrGameView view, @NotNull ScrPresentation presentation, @NotNull TriggerContext context, int type,
                            @NotNull StatText text) {
        this.mode = Mode.VALUES;

        for (int row = 0; row < ROWS; row++) {
            int player = context.getLeaderboardPlayerIndex(row);

            if ((row == 0 || this.allPlayersShown) && player != NO_PLAYER) {
                this.rowPlayers[row] = player;
                this.values[row]     = Integer.toUnsignedString(context.getLeaderboardValue(row));
            } else {
                this.rowPlayers[row] = NO_PLAYER;
                this.values[row]     = "";
            }
        }

        // The label is the map string, else the leaderboard type's default; it is hidden without a first value.
        String mapLabel = presentation.getInterfaceLink().getMessageLines().getMapString(context.getLeaderboardString());
        String shown = mapLabel == null ? "" : mapLabel;

        if (shown.isEmpty() && type >= FIRST_LEADERBOARD && type <= LAST_LEADERBOARD) {
            shown = text.getString(view, type + LEADERBOARD_LABEL_BASE);
        } else if (shown.isEmpty() && type >= FIRST_GOAL && type <= LAST_GOAL) {
            shown = text.getString(view, type + GOAL_LABEL_BASE);
        }

        this.label    = this.values[0].isEmpty() ? null : shown;
        this.mapLabel = this.label != null && mapLabel != null && !mapLabel.isEmpty();
    }

    /**
     * Updates minerals and gas for each shown row without a label.
     */
    private void showGreed(@NotNull ScrGameView view, @NotNull TriggerContext context) {
        this.mode     = Mode.GREED;
        this.label    = null;
        this.mapLabel = false;

        for (int row = 0; row < ROWS; row++) {
            int player = context.getLeaderboardPlayerIndex(row);
            Player record = player >= 0 && player < PlayerTable.PLAYER_SLOTS ? view.getPlayerTable().getPlayer(player) : null;

            if ((row == 0 || this.allPlayersShown) && player != NO_PLAYER && record != null) {
                this.rowPlayers[row] = player;
                this.minerals[row]   = Integer.toString(record.getMinerals());
                this.gas[row]        = Integer.toString(record.getGas());
            } else {
                this.rowPlayers[row] = NO_PLAYER;
            }
        }
    }
}
