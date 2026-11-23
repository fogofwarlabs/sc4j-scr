package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.engine.client.game.ReplayAutosave;
import org.fogofwarlabs.sc4j.engine.client.game.SimulationWatchdog;
import org.fogofwarlabs.sc4j.scr.client.configuration.ClientOptions;
import org.fogofwarlabs.sc4j.scr.game.replay.ScrReplayFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Chooses the replay files of the client's live games and writes them through the engine {@link ReplayAutosave}: the automatic file at the end of a
 * game (the launch's explicit save file, else {@value #LAST_REPLAY_FILE} in the replay folder; a campaign mission writes none), the same file from
 * the stall watchdog's thread while a frame stalls ({@link SimulationWatchdog}). The replays are written in the SC:R mod's replay format
 * ({@link ClientSession#saveReplay}); the score screen's Save Replay copies the written file ({@link ReplayAutosave#saveCopy}).
 */
final class LiveReplayWriter {
    /**
     * The automatic replay file of the last non-campaign game, a file of the SC:R mod's replay format ({@link ScrReplayFormat}).
     */
    static final @NotNull String LAST_REPLAY_FILE = "LastReplay." + ScrReplayFormat.FILE_EXTENSION;

    private final @NotNull ClientOptions options;
    private final @NotNull ReplayAutosave autosave;

    /**
     * Creates a {@link LiveReplayWriter} instance.
     *
     * @param options the launch options: the explicit save file, the campaign and the install layout's replay folder
     */
    LiveReplayWriter(@NotNull ClientOptions options) {
        this.options  = options;
        this.autosave = new ReplayAutosave();
    }

    /**
     * @return the replay file written at the end of the last live game, or {@code null} if none was written
     */
    @Nullable Path getLastWritten() {
        return this.autosave.getLastWritten();
    }

    /**
     * @return the replay folder under the selected install root: {@code <install root>/replays}
     */
    @NotNull Path getReplayDirectory() {
        return this.options.getInstallLayout().getReplayDirectory();
    }

    /**
     * @return {@code true} if the launch setup names a built-in campaign mission, {@code false} otherwise
     */
    boolean isCampaignGame() {
        ClientOptions.Melee melee = this.options.getMelee();

        return melee != null && melee.getCampaign() != null;
    }

    /**
     * @return the automatic replay file of the running game: the launch's explicit save file, else {@value #LAST_REPLAY_FILE} in the replay folder, or {@code null} for a campaign mission
     */
    private @Nullable Path getAutomaticFile() {
        ClientOptions.Melee melee = this.options.getMelee();
        Path explicit = melee == null ? null : melee.getSave();

        if (explicit != null) {
            return explicit;
        }

        return this.isCampaignGame() ? null : this.getReplayDirectory().resolve(LAST_REPLAY_FILE);
    }


    /**
     * Marks a new session's game as not yet written.
     */
    void startSession() {
        this.autosave.startGame();
    }

    /**
     * Writes the automatic replay of a live game at its end, once per game.
     *
     * @param session the running session, or {@code null} for none
     */
    void saveAtEnd(@Nullable ClientSession session) {
        if (session == null || !session.isLive()) {
            return;
        }

        this.autosave.saveAtEnd(this.getAutomaticFile(), session::saveReplay);
    }

    /**
     * Writes the running live game's replay from the stall watchdog's thread ({@link SimulationWatchdog}): the stalled frame may never return, and a
     * window closed then would lose the game. The automatic file is written as at the end (with the commands executed so far); the end of the game
     * writes it again if the frame returns.
     *
     * @param running the running session, or {@code null} for none
     */
    void saveStalled(@Nullable ClientSession running) {
        if (running == null || !running.isLive()) {
            return;
        }

        this.autosave.saveStalled(this.getAutomaticFile(), running::saveReplay);
    }
}
