package org.fogofwarlabs.sc4j.scr.client.glue.score;

import org.fogofwarlabs.sc4j.scr.client.glue.GlueGameRequest;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.glue.lobby.FileBrowserScreen;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.GlueSavePopup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Stores post-game navigation, campaign continuation, optional score data and replay-saving callbacks.
 */
public final class GlueResume {
    /**
     * The campaign mission of a continuation that is not a campaign mission.
     */
    private static final int NO_CAMPAIGN_MISSION = -1;
    /**
     * The saver of a continuation without a score screen: it writes nothing.
     */
    private static final @NotNull ReplaySaver NO_REPLAY = new ReplaySaver() {
        /**
         * Returns the folder the dialog lists and writes into.
         * <p>
         * This saver writes no files and returns the empty path.
         *
         * @return the folder the dialog lists and writes into
         */
        @Override
        public @NotNull Path getDirectory() {
            return Path.of("");
        }

        /**
         * Saves under a name: writes the replay into {@link #getDirectory()}, or sends the Save Game command whose execution writes the game.
         * <p>
         * This saver writes nothing and returns {@code false}.
         *
         * @param name the name the dialog read from its name box: the bytes cut to a saved game name buffer, without trailing spaces and without terminator
         * @return {@code true} if the save was written or sent, {@code false} otherwise
         */
        @Override
        public boolean save(byte @NotNull [] name) {
            return false;
        }
    };

    /**
     * Writes the game's replay for Save Replay of the score screen: the replay target of the {@code savegame} dialog ({@link GlueSavePopup}).
     */
    public interface ReplaySaver extends GlueSavePopup.Target {
        /**
         * Returns the folder the dialog lists and writes into.
         * <p>
         * A replay saver returns the replay folder the Save Replay dialog lists and writes into.
         *
         * @return the folder the dialog lists and writes into
         */
        @Override
        @NotNull Path getDirectory();

        /**
         * Returns the file name extension with its dot.
         * <p>
         * A replay saver returns the replay extension {@code .rep}.
         *
         * @return the file name extension with its dot
         */
        @Override
        default @NotNull String getExtension() {
            return FileBrowserScreen.REPLAY_EXTENSION;
        }

        /**
         * Returns whether the dialog saves the score screen's replay.
         * <p>
         * A replay saver always saves the score screen's replay.
         *
         * @return {@code true} if the dialog saves the score screen's replay, {@code false} otherwise
         */
        @Override
        default boolean isReplay() {
            return true;
        }

        /**
         * Saves under a name: writes the replay into {@link #getDirectory()}, or sends the Save Game command whose execution writes the game.
         * <p>
         * A replay saver writes the game's replay into {@link #getDirectory()}.
         *
         * @param name the name the dialog read from its name box: the bytes cut to a saved game name buffer, without trailing spaces and without terminator
         * @return {@code true} if the save was written or sent, {@code false} otherwise
         */
        @Override
        boolean save(byte @NotNull [] name);
    }

    /**
     * Creates a {@link GlueResume} instance for the continuation after a game.
     *
     * @param request the request that started the game, or {@code null} for a game of the launch options (the main menu follows)
     * @param victory whether the local player won
     * @return the created {@link GlueResume} instance
     */
    public static @NotNull GlueResume createAfterGame(@Nullable GlueGameRequest request, boolean victory) {
        if (request == null) {
            return new GlueResume(GlueNavigator.Destination.MAIN_MENU, "", false, NO_CAMPAIGN_MISSION, victory, null);
        }

        GlueNavigator.Destination destination = request.getReturnDestination() != null
                                                ? request.getReturnDestination()
                                                : getDefaultDestination(request.getKind());
        // A loaded campaign mission continues the campaign walk as the mission itself does.
        boolean campaign = request.getKind() == GlueGameRequest.Kind.CAMPAIGN ||
                           request.getKind() == GlueGameRequest.Kind.SAVED_GAME && request.getCampaignMission() > 0;
        int mission = campaign ? request.getCampaignMission() : NO_CAMPAIGN_MISSION;

        return new GlueResume(destination, request.getPlayerName(), request.isExpansion(), mission, victory, request);
    }

    /**
     * Creates a {@link GlueResume} instance for the continuation after a game that a fatal notice or a failed scenario
     * reload ended, without a score screen: the request's return destination (else the game list) after a network game, the main menu after
     * any other game.
     *
     * @param request the request that started the game, or {@code null} for a game of the launch options
     * @return the created {@link GlueResume} instance
     */
    public static @NotNull GlueResume createAfterFatalNotice(@Nullable GlueGameRequest request) {
        if (request == null) {
            return new GlueResume(GlueNavigator.Destination.MAIN_MENU, "", false, NO_CAMPAIGN_MISSION, false, null);
        }

        GlueNavigator.Destination returnDestination = request.getReturnDestination();
        GlueNavigator.Destination destination = request.getKind() != GlueGameRequest.Kind.NETWORK ? GlueNavigator.Destination.MAIN_MENU
                                                                                                  : returnDestination != null ? returnDestination
                                                                                                                              : GlueNavigator.Destination.GAME_LIST;

        return new GlueResume(destination, request.getPlayerName(), request.isExpansion(), NO_CAMPAIGN_MISSION, false, null);
    }

    /**
     * Returns the screen a game of a request kind returns to when the request supplies no destination.
     *
     * @param kind the request kind
     * @return the campaign screen after a single-player game, the replay list after a replay, the game list after a network game
     */
    private static GlueNavigator.@NotNull Destination getDefaultDestination(GlueGameRequest.@NotNull Kind kind) {
        return switch (kind) {
            case CAMPAIGN, MELEE, USE_MAP_SETTINGS, SAVED_GAME -> GlueNavigator.Destination.CAMPAIGN;
            case REPLAY -> GlueNavigator.Destination.REPLAY_LIST;
            case NETWORK -> GlueNavigator.Destination.GAME_LIST;
        };
    }

    private final GlueNavigator.@NotNull Destination destination;
    private final @NotNull String playerName;
    private final boolean expansion;
    private final int campaignMission;
    private final boolean victory;
    private final @Nullable GlueGameRequest request;

    private @Nullable GlueGameRequest nextScenario;
    private @Nullable String campaignScenario;
    private @Nullable ScoreScreen score;
    private @NotNull ReplaySaver replaySaver;

    /**
     * Creates a {@link GlueResume} instance.
     *
     * @param destination     the screen to show
     * @param playerName      the player name to retain
     * @param expansion       whether the continuation uses expansion assets
     * @param campaignMission the campaign mission just played, or {@code -1}
     * @param victory         whether the local player won
     * @param request         the request that started the game, or {@code null}
     */
    private GlueResume(GlueNavigator.@NotNull Destination destination,
                       @NotNull String playerName,
                       boolean expansion,
                       int campaignMission,
                       boolean victory,
                       @Nullable GlueGameRequest request) {
        this.destination     = destination;
        this.playerName      = playerName;
        this.expansion       = expansion;
        this.campaignMission = campaignMission;
        this.victory         = victory;
        this.request         = request;

        this.nextScenario     = null;
        this.campaignScenario = null;
        this.score            = null;
        this.replaySaver      = NO_REPLAY;
    }

    /**
     * @return the screen to show
     */
    public GlueNavigator.@NotNull Destination getDestination() {
        return this.destination;
    }

    /**
     * @return the retained player name
     */
    public @NotNull String getPlayerName() {
        return this.playerName;
    }

    /**
     * @return {@code true} if the continuation uses expansion assets, {@code false} otherwise
     */
    public boolean isExpansion() {
        return this.expansion;
    }

    /**
     * @return the campaign mission just played, or {@code -1}
     */
    public int getCampaignMission() {
        return this.campaignMission;
    }

    /**
     * @return {@code true} if the local player won, {@code false} otherwise
     */
    public boolean isVictory() {
        return this.victory;
    }

    /**
     * @return the custom game of the next scenario ({@link #withNextScenario}), or {@code null}
     */
    public @Nullable GlueGameRequest getNextScenario() {
        return this.nextScenario;
    }

    /**
     * @return the retained campaign scenario name, or {@code null}
     */
    public @Nullable String getCampaignScenario() {
        return this.campaignScenario;
    }

    /**
     * @return the score data shown first, or {@code null}
     */
    public @Nullable ScoreScreen getScore() {
        return this.score;
    }

    /**
     * @return what Save Replay of the score screen calls
     */
    public @NotNull ReplaySaver getReplaySaver() {
        return this.replaySaver;
    }


    /**
     * Retains a campaign scenario name or creates a custom-game continuation beside the played map.
     * Unsupported requests, empty names and maps without parent folders retain the current continuation.
     *
     * @param fileName the next scenario file name, or {@code null} when none was set
     * @return this instance for chaining
     */
    public @NotNull GlueResume withNextScenario(@Nullable String fileName) {
        GlueGameRequest played = this.request;

        if (played == null || fileName == null || fileName.isEmpty() || played.getReturnDestination() != null) {
            return this;
        }

        // Campaign requests retain the scenario name for the campaign walk.
        if (played.getKind() == GlueGameRequest.Kind.CAMPAIGN) {
            this.campaignScenario = fileName;

            return this;
        }

        if (played.getKind() != GlueGameRequest.Kind.MELEE && played.getKind() != GlueGameRequest.Kind.USE_MAP_SETTINGS) {
            return this;
        }

        Path map = played.getPath();

        if (map == null || map.getParent() == null) {
            return this;
        }

        this.nextScenario = played.createWithMap(map.resolveSibling(fileName));

        return this;
    }

    /**
     * Shows the score screen first ({@link GlueScoreScreen}).
     *
     * @param scoreScreen the score data
     * @param saver       saves the game's replay for Save Replay
     * @return this instance for chaining
     */
    public @NotNull GlueResume withScore(@NotNull ScoreScreen scoreScreen, @NotNull ReplaySaver saver) {
        this.score       = scoreScreen;
        this.replaySaver = saver;

        return this;
    }
}
