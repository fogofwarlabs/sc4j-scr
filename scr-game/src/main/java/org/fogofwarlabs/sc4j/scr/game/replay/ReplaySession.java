package org.fogofwarlabs.sc4j.scr.game.replay;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.command.CommandSink;
import org.fogofwarlabs.sc4j.engine.controller.ControllerCommandSource;
import org.fogofwarlabs.sc4j.engine.controller.ControllerSource;
import org.fogofwarlabs.sc4j.engine.controller.PlayerController;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommand;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandUtils;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.game.frame.GameExecutionScope;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.LoopbackTurns;
import org.fogofwarlabs.sc4j.scr.game.session.OutgoingTurnBuffer;
import org.fogofwarlabs.sc4j.scr.game.session.SyncRing;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameEnd;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Steps a replay against a simulation state.
 * Commands due at the current frame are delivered in order before the established game caller advances the frame.
 * Command processing has its own RNG enable scope.
 * <p>
 * Replay playback runs each frame's command blocks through the {@link ReplayCommandBuffer}, which can run other commands
 * than the recorded ones for a frame whose blocks overflow the buffer and that runs after its loading call. A live game
 * runs its recorded commands.
 * <p>
 * Each step is one game loop of the turn gate ({@link GameSpeed#takeFrameBudget()}): the session start stores the
 * turn-wait budget of the game start, and a step whose loop runs a turn step runs the live game's commands of its frame,
 * counts the turn step ({@link Game#incrementTurnCount()}) and then adds the timeout back
 * ({@link GameSpeed#addTurnTimeout()}), before the commands-executed call and the frame. A step without a turn step runs
 * no live command and counts no turn step; replay playback runs its frame's blocks in every step. Outside a multiplayer
 * session every step runs a turn step, a paused frame's step too.
 * <p>
 * The first step runs the startup turn step before its own turn step: the commands of a network game's startup turn
 * ({@link #setStartupTurnCommandCount}), which see the turn count {@code 0}, then the count of the startup turn step.
 * <p>
 * Replay playback runs the turn steps of a single-player game. The session start supplies the single-player turn limits
 * and the local outgoing turn with the Sync command queued ({@link OutgoingTurnBuffer}). Each turn step, the startup
 * turn step included, exchanges the turns of the local player ({@link LoopbackTurns}) after the replay commands and
 * before it counts the turn step: it receives the turn sent {@link LiveGame#LATENCY_TURNS} plus the latency setting turn
 * steps earlier, sends the outgoing turn, which queues the Sync command of the next turn with the newest Sync record
 * ({@link SyncRing}: the session start makes record 1, each turn step one more once it counts), and runs the received turn's
 * leading Keep Alive, Restart Game, Pause, Resume, Sync, Latency, Replay Speed and Replay Seek commands
 * ({@link ReplayCommandUtils#walkReplayTurn}); its other commands do not run. Restart Game, Pause, Resume and Replay Seek
 * run through the command handler as the recorded ones do. EUD maps read the buffer and its turn limits and queue
 * commands into it as in a live game, and the viewer's controls queue Replay Speed and Replay Seek commands
 * ({@link #decreaseSpeed()}, {@link #increaseSpeed()}, {@link #playOrPause()}, {@link #seek(int)}). While the viewer's
 * playback is paused, its game loops run the turn steps without a frame ({@link #stepPaused()}).
 * <p>
 * A game loop whose commands or turn step end the game ({@link Game#end(GameEnd)}) runs no frame, and the session runs no further
 * game loop ({@link #getEnd()}).
 * <p>
 * The recorded command stream is an engine {@link PlayerController} ({@link #getController()}): each run of due commands
 * applies the session's command source ({@link #getCommandSource()}), which collects them from the stream into one
 * command dispatch, every record as a {@link RecordedCommandType} command whose player is the engine player of the record
 * its issuer resolves to (the neutral player for an issuer without one); the command handler runs each record as it is.
 * The command source is the session's own over the stream alone, or the one of the client's engine game session, whose
 * game loop this session is: the engine frame runs no command source, so every record is handed over once.
 */
public final class ReplaySession {
    /**
     * The block frame when no block is loaded.
     */
    private static final int NO_BLOCK = -1;
    /**
     * The offset of the Replay Speed command's paused byte (the speed configuration's mode) in its payload.
     */
    private static final int REPLAY_SPEED_MODE_OFFSET = 0;
    /**
     * The offset of the Replay Speed command's speed setting DWORD in its payload.
     */
    private static final int REPLAY_SPEED_SETTING_OFFSET = REPLAY_SPEED_MODE_OFFSET + 1;
    /**
     * The offset of the Replay Speed command's factor DWORD in its payload.
     */
    private static final int REPLAY_SPEED_FACTOR_OFFSET = REPLAY_SPEED_SETTING_OFFSET + Integer.BYTES;
    /**
     * The factor above which Slow Down at {@link GameSpeed#FASTEST} and Speed Up at speed {@code 0} halve the factor.
     */
    private static final int UNSCALED_FACTOR = 1;
    /**
     * The largest factor Speed Up doubles to at {@link GameSpeed#FASTEST}.
     */
    private static final int MAXIMUM_FACTOR = 16;

    /**
     * Hands over the due records of the recorded stream, from the next due command up to the end of the current run, each as a
     * {@link RecordedCommandType} command; a record counts as run once the sink returns, so a failed record stays pending.
     */
    private final class RecordedStream implements PlayerController {
        /**
         * The source a session set, or {@code null} for the default of the game.
         */
        private @Nullable ControllerSource source;
        /**
         * The due command index the current run stops at.
         */
        private int end;

        private RecordedStream() {
            this.source = null;
            this.end    = 0;
        }

        /**
         * @return where the commands of this controller come from: the source a session set, else {@link ControllerSource#REPLAY} in replay playback and {@link ControllerSource#HUMAN} in a live game
         */
        @Override
        public @NotNull ControllerSource getSource() {
            ControllerSource set = this.source;

            if (set != null) {
                return set;
            }

            return ReplaySession.this.game.isReplayPlayback() ? ControllerSource.REPLAY : ControllerSource.HUMAN;
        }

        /**
         * Hands over the commands due at the frame about to run, in their order.
         * <p>
         * The due records of the current run, each for the engine player of its issuer's record, in one command dispatch that the
         * first record opens; the session sets the run's end (the startup turn step runs a part of the frame's records first) and
         * closes the dispatch after the run.
         *
         * @param game the game
         * @param sink the sink of the commands
         */
        @Override
        public void collectCommands(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game, @NotNull CommandSink sink) {
            ReplaySession session = ReplaySession.this;

            while (session.dueIndex < this.end) {
                ReplayCommand command = session.dueCommands.get(session.dueIndex);

                session.dispatch.open();
                sink.submit(session.findIssuer(command), session.recordedType, command);
                session.dueIndex++;
                session.executedCount++;
                session.commandIndex = Math.min(session.commandIndex + 1, session.dueEnd);
            }
        }
    }

    /**
     * Runs the commands of the recorded stream in one command dispatch: the first command opens it (random generator enabled,
     * the execution scope's command phase), {@link #close()} closes it after the run, also after a failed command.
     */
    private final class RecordedDispatch {
        private boolean open;

        private RecordedDispatch() {
            this.open = false;
        }

        /**
         * Opens the dispatch before the first command of a run; a later command of the run finds it open.
         */
        private void open() {
            if (!this.open) {
                ReplaySession.this.beginCommandDispatch();

                this.open = true;
            }
        }

        /**
         * Closes the dispatch a command of the run opened.
         */
        private void close() {
            if (this.open) {
                this.open = false;

                ReplaySession.this.endCommandDispatch();
            }
        }
    }

    private final @NotNull Game game;
    private final @NotNull ReplayFile replay;
    private final @NotNull ReplayCommandHandler commandHandler;
    private final @NotNull List<ReplayCommand> commands;
    private final @NotNull ReplayFrameRunner frameRunner;
    /**
     * The game's speed configuration, whose turn-wait budget schedules the turn steps.
     */
    private final @NotNull GameSpeed timing;
    private final @NotNull ReplayCommandBuffer commandBuffer;
    /**
     * The commands of the current frame in dispatch order, of which those from {@link #dueIndex} on have not run.
     */
    private final @NotNull List<ReplayCommand> dueCommands;
    /**
     * The commands the loaded frame that has not run will run, read from the replay command buffer once its loading call
     * ended ({@link #getPendingCommand()}); empty until read.
     */
    private final @NotNull List<ReplayCommand> loadedCommands;
    /**
     * The command type of the recorded commands the stream hands over, the stream and the command dispatch they run in.
     */
    private final @NotNull RecordedCommandType recordedType;
    private final @NotNull RecordedStream stream;
    private final @NotNull RecordedDispatch dispatch;
    /**
     * The command source each run of due commands applies, whose controllers include the recorded stream.
     */
    private @NotNull ControllerCommandSource commandSource;

    private int commandIndex;
    /**
     * The index of the next due command to run.
     */
    private int dueIndex;
    /**
     * The command index that {@link #commandIndex} takes once the due commands ran.
     */
    private int dueEnd;
    /**
     * The number of due commands the last step ran, the last of them before {@link #dueIndex}.
     */
    private int executedCount;
    /**
     * The loaded command block frame, or minus one when no block is loaded.
     */
    private int blockFrame;
    /**
     * The end index of the loaded command block in {@link #commands}.
     */
    private int blockEnd;
    /**
     * Whether the loaded block has not run yet.
     */
    private boolean blockPending;
    /**
     * The last completed frame; another thread (a client's stall watchdog) reads it while the game thread steps.
     */
    private volatile int lastCompletedFrame;
    private @Nullable Runnable commandsExecutedListener;

    /**
     * Whether the first step has not run the startup turn step yet.
     */
    private boolean startupTurnPending;
    /**
     * Reads the number of commands of a network game's startup turn, or {@code null} when the startup turn runs none.
     */
    private @Nullable IntSupplier startupTurnCommandCount;

    /**
     * The local player's sent turns on their way back during replay playback, or {@code null} in a live game, whose
     * session exchanges the turns.
     */
    private final @Nullable LoopbackTurns loopbackTurns;
    /**
     * The network id the received turns of replay playback run as: the value of the replay's local player, the first
     * human ({@link ReplayInitialization#findNativeReplayLocalPlayer}), which the replay load also stores as the local
     * network id.
     */
    private final int loopbackPlayerId;
    /**
     * The Sync records of replay playback, whose newest record the queued Sync command carries, or {@code null} in a live
     * game, whose session makes its own.
     */
    private final @Nullable SyncRing syncRing;
    /**
     * The latency setting of the last Latency command a received turn ran: the extra turns kept in transit.
     */
    private int latency;
    /**
     * The viewer's playback that the Replay Speed commands of a received turn pause and continue, or {@code null} without a
     * viewer.
     */
    private ReplayCommandExecutor.@Nullable PlaybackControl playbackControl;

    /**
     * Creates a {@link ReplaySession} instance with an established game caller.
     *
     * @param game           the initialized simulation state
     * @param replay         the parsed replay
     * @param commandHandler the command execution handler
     * @param frameRunner    the established regular game caller
     * @throws IllegalArgumentException if the game caller belongs to a different simulation state
     */
    public ReplaySession(@NotNull Game game,
                         @NotNull ReplayFile replay,
                         @NotNull ReplayCommandHandler commandHandler,
                         @NotNull ReplayFrameRunner frameRunner) {
        if (frameRunner.getGame() != game) {
            throw Exceptions.newIllegalArgumentException("The native replay caller belongs to a different simulation state.");
        }

        this.game           = game;
        this.replay         = replay;
        this.commandHandler = commandHandler;
        this.commands       = replay.getCommands();
        this.frameRunner    = frameRunner;
        this.timing         = frameRunner.getFrameTiming();
        this.commandBuffer  = new ReplayCommandBuffer(this.commands, replay.getCommandBlocks());
        this.dueCommands    = new ArrayList<>();
        this.loadedCommands = new ArrayList<>();
        this.recordedType   = new RecordedCommandType(game, commandHandler);
        this.stream         = new RecordedStream();
        this.dispatch       = new RecordedDispatch();
        this.commandSource  = new ControllerCommandSource(List.of(this.stream));

        this.commandIndex             = 0;
        this.dueIndex                 = 0;
        this.dueEnd                   = 0;
        this.executedCount            = 0;
        this.blockFrame               = NO_BLOCK;
        this.blockEnd                 = 0;
        this.blockPending             = false;
        this.lastCompletedFrame       = game.getCurrentFrame();
        this.commandsExecutedListener = null;

        this.startupTurnPending      = true;
        this.startupTurnCommandCount = null;

        // A live game's session supplies its own outgoing turn and turn limits; replay playback starts single-player ones.
        OutgoingTurnBuffer outgoingTurn = game.isReplayPlayback() ? new OutgoingTurnBuffer(LiveGame.MAXIMUM_TURN_BYTES) : null;
        LoopbackTurns loopbackTurns = outgoingTurn == null ? null : new LoopbackTurns(outgoingTurn);

        this.loopbackTurns    = loopbackTurns;
        this.loopbackPlayerId = ReplayInitialization.findNativeReplayLocalPlayer(replay.getHeader());
        this.syncRing         = outgoingTurn == null ? null : new SyncRing(this.loopbackPlayerId, false);
        this.latency          = 0;
        this.playbackControl  = null;

        // The session start is the game start, which stores the turn-wait budget.
        this.timing.resetTurnBudget();

        if (outgoingTurn != null && loopbackTurns != null) {
            outgoingTurn.setTurnSender(loopbackTurns, LiveGame.LATENCY_TURNS);

            this.game.getInterfaceLink().setOutgoingTurn(outgoingTurn);
            this.game.getInterfaceLink().setSessionTurnLimits(LiveGame.LATENCY_TURNS, LiveGame.MAXIMUM_TURN_BYTES);

            // The game start makes Sync record 1 and queues its Sync command.
            this.updateSyncRecord();
            outgoingTurn.startSync(this.syncRing);
        }
    }

    /**
     * @return the simulation state
     */
    public @NotNull Game getGame() {
        return this.game;
    }

    /**
     * @return the next unconsumed command index in the replay's command list
     */
    public int getNextCommandIndex() {
        return this.commandIndex;
    }

    /**
     * Returns the commands the last step ran, in order: in replay playback the commands the replay command buffer ran
     * ({@link ReplayCommandBuffer#run}), in a live game the recorded commands.
     *
     * @return a read-only view of the commands, valid until the next step
     */
    public @NotNull List<ReplayCommand> getExecutedCommands() {
        if (this.executedCount == 0) {
            return List.of();
        }

        return Collections.unmodifiableList(this.dueCommands.subList(this.dueIndex - this.executedCount, this.dueIndex));
    }

    /**
     * Returns the command the next step runs first, as far as it is known: a due command that has not run, the first command
     * a loaded frame runs from the replay command buffer ({@link ReplayCommandBuffer#readCommands}), or else the next recorded
     * command.
     *
     * @return the command, or {@code null} if no command is left
     */
    public @Nullable ReplayCommand getPendingCommand() {
        if (this.dueIndex < this.dueCommands.size()) {
            return this.dueCommands.get(this.dueIndex);
        }

        int nextCommandIndex = this.commandIndex;

        if (this.blockPending) {
            if (this.loadedCommands.isEmpty()) {
                this.commandBuffer.readCommands(this.blockFrame, this.loadedCommands);
            }

            if (!this.loadedCommands.isEmpty()) {
                return this.loadedCommands.getFirst();
            }

            nextCommandIndex = this.blockEnd;
        }

        return nextCommandIndex < this.commands.size() ? this.commands.get(nextCommandIndex) : null;
    }

    /**
     * @return the last frame whose complete game caller returned successfully, initially the startup frame
     */
    public int getLastCompletedFrame() {
        return this.lastCompletedFrame;
    }

    /**
     * @return {@code true} if all replay frames have been processed, {@code false} otherwise
     */
    public boolean isFinished() {
        return this.lastCompletedFrame >= this.replay.getHeader().getFrames();
    }

    /**
     * @return {@code true} if the game ended before its last frame ({@link #getEnd()}), {@code false} otherwise
     */
    public boolean isEnded() {
        return this.getEnd() != null;
    }

    /**
     * Returns why the game ended before its last frame: a command ended it ({@link Game#getEnd()}), or a trigger accessed
     * an EUD address the game does not serve ({@link TriggerContext#hasEudFailure()}). The last completed frame is the
     * frame of the end.
     *
     * @return the end, or {@code null} while the game runs
     */
    public @Nullable GameEnd getEnd() {
        GameEnd end = this.game.getEnd();
        TriggerContext triggers = this.game.getTriggerContext();

        if (end != null) {
            return end;
        }

        return triggers != null && triggers.hasEudFailure() ? GameEnd.EUD_FAILURE : null;
    }

    /**
     * @return the recorded command stream as the engine controller of its commands: the due records, collected by each run of due commands into one command dispatch
     */
    public @NotNull PlayerController getController() {
        return this.stream;
    }

    /**
     * @return the command source each run of due commands applies: the session's own over the recorded stream, or the one set ({@link #setCommandSource})
     */
    public @NotNull ControllerCommandSource getCommandSource() {
        return this.commandSource;
    }

    /**
     * Sets the command source each run of due commands applies: the command source of the client's engine game session, whose
     * game loop this session is, so that the recorded stream reaches the game only through it. Its controllers include the
     * recorded stream ({@link #getController()}).
     *
     * @param commandSource the command source
     * @throws IllegalArgumentException if the source does not ask the recorded stream
     */
    public void setCommandSource(@NotNull ControllerCommandSource commandSource) {
        if (!commandSource.getControllers().contains(this.stream)) {
            throw Exceptions.newIllegalArgumentException("The command source does not ask the recorded stream of this session.");
        }

        this.commandSource = commandSource;
    }

    /**
     * Sets where the commands of the recorded stream come from ({@link PlayerController#getSource()}): by default
     * {@link ControllerSource#REPLAY} in replay playback and {@link ControllerSource#HUMAN} in a live game; a network game's
     * session sets {@link ControllerSource#NETWORK}.
     *
     * @param source the source, or {@code null} for the default
     */
    public void setControllerSource(@Nullable ControllerSource source) {
        this.stream.source = source;
    }

    /**
     * Sets the call made after each frame's commands execute and before the frame advances.
     *
     * @param commandsExecutedListener the call, or {@code null} for none
     */
    public void setCommandsExecutedListener(@Nullable Runnable commandsExecutedListener) {
        this.commandsExecutedListener = commandsExecutedListener;
    }

    /**
     * Sets the source of the number of commands a network game's startup turn recorded: the first step runs that many of
     * its frame's live commands as the startup turn step, before it counts the startup turn step, and the rest as its own
     * turn step. The source is read once, by the first step.
     *
     * @param startupTurnCommandCount the source, or {@code null} for a startup turn without commands
     */
    public void setStartupTurnCommandCount(@Nullable IntSupplier startupTurnCommandCount) {
        this.startupTurnCommandCount = startupTurnCommandCount;
    }

    /**
     * Sets the viewer's playback that the Replay Speed commands of a received turn pause and continue. Without one (a
     * headless run) those commands only change the speed configuration.
     *
     * @param playbackControl the viewer's playback, or {@code null} for none
     */
    public void setPlaybackControl(ReplayCommandExecutor.@Nullable PlaybackControl playbackControl) {
        this.playbackControl = playbackControl;
    }


    /**
     * Takes the game loop's turn-wait budget, runs the startup turn step in the first step, delivers the due commands in
     * order (a live game's only in a turn step, which then counts the turn step and adds the timeout back; a turn step of
     * replay playback first exchanges the local player's turns), then advances the game using measured host ticks.
     * A failed callback leaves that command pending and closes the command random scope. A failed game phase prevents
     * retry through the same caller.
     *
     * @param elapsedHostTicks the measured DWORD difference for the unit phase
     * @return {@code true} if a frame was advanced, {@code false} otherwise (the replay is finished or the game ended)
     */
    public boolean step(int elapsedHostTicks) {
        return this.runGameLoop(elapsedHostTicks, true);
    }

    /**
     * Runs a game loop of replay playback while the viewer's playback is paused: as {@link #step(int)}, with the replay
     * commands of the current frame and the turn step, but without a frame. A finished replay's paused loops go on, so its
     * viewer's controls still run.
     *
     * @return {@code true} if the loop ran, {@code false} otherwise (the game ended)
     * @throws IllegalStateException if the session runs a live game
     */
    public boolean stepPaused() {
        if (this.loopbackTurns == null) {
            throw Exceptions.newIllegalStateException("Only replay playback runs game loops without a frame.");
        }

        return this.runGameLoop(0, false);
    }

    /**
     * Queues the Replay Speed command of the viewer's Slow Down: at {@link GameSpeed#FASTEST} a factor above one is
     * halved, otherwise a speed setting above {@code 0} is lowered, and at speed {@code 0} the factor doubles (only a
     * factor of {@code 0}, which stays). The command carries the playback's paused state.
     *
     * @throws IllegalStateException if the session runs a live game
     */
    public void decreaseSpeed() {
        int speed = this.timing.getSpeedIndex();
        int factor = this.timing.getFactor();

        if (speed == GameSpeed.FASTEST && Integer.compareUnsigned(factor, UNSCALED_FACTOR) > 0) {
            factor >>>= 1;
        } else if (speed > 0) {
            speed--;
        } else if (Integer.compareUnsigned(factor, UNSCALED_FACTOR) < 0) {
            factor += factor;
        }

        this.queueReplaySpeed(speed, factor, this.timing.getMode());
    }

    /**
     * Queues the Replay Speed command of the viewer's Speed Up: at speed {@code 0} a factor above one is halved,
     * otherwise a speed setting below {@link GameSpeed#FASTEST} is raised, and at {@link GameSpeed#FASTEST} the factor
     * doubles while it is below {@value #MAXIMUM_FACTOR}. The command carries the playback's paused state.
     *
     * @throws IllegalStateException if the session runs a live game
     */
    public void increaseSpeed() {
        int speed = this.timing.getSpeedIndex();
        int factor = this.timing.getFactor();

        if (speed == 0 && Integer.compareUnsigned(factor, UNSCALED_FACTOR) > 0) {
            factor >>>= 1;
        } else if (speed < GameSpeed.FASTEST) {
            speed++;
        } else if (Integer.compareUnsigned(factor, MAXIMUM_FACTOR) < 0) {
            factor += factor;
        }

        this.queueReplaySpeed(speed, factor, this.timing.getMode());
    }

    /**
     * Queues the Replay Speed command of the viewer's Play/Pause: the current speed and factor with the paused state
     * toggled.
     *
     * @throws IllegalStateException if the session runs a live game
     */
    public void playOrPause() {
        this.queueReplaySpeed(this.timing.getSpeedIndex(), this.timing.getFactor(), this.timing.getMode() == 0 ? 1 : 0);
    }

    /**
     * Configures the replay's speed as the replay start and a Replay Speed command do: the speed setting, the factor and the
     * mode rebuild the speed configuration ({@link GameSpeed#configure}), and the speed setting becomes the game speed
     * option that trigger Waits count down with ({@link ReplayFrameRunner#setGameSpeedOption}).
     *
     * @param speed  the speed setting
     * @param factor the DWORD factor
     * @param mode   the mode byte, not {@code 0} while the playback is paused
     * @throws IllegalArgumentException if the speed setting is outside {@code 0..6}
     */
    public void configureSpeed(int speed, int factor, int mode) {
        this.timing.configure(speed, factor, mode);
        this.frameRunner.setGameSpeedOption(speed);
    }

    /**
     * Queues the Replay Seek command of the viewer's seek bar.
     *
     * @param frame the frame to seek to
     * @throws IllegalStateException if the session runs a live game
     */
    public void seek(int frame) {
        byte[] command = new byte[ReplayCommandUtils.getFixedLength(ReplayCommandIds.REPLAY_SEEK)];

        ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN).put((byte) ReplayCommandIds.REPLAY_SEEK).putInt(frame);

        this.queueLocalCommand(command);
    }

    /**
     * Runs one game loop: takes the turn-wait budget, runs the startup turn step in the first loop, delivers the due
     * commands, runs the loop's turn step and then the frame, if one runs and the game did not end.
     *
     * @param elapsedHostTicks the measured DWORD difference for the unit phase
     * @param frameRuns        whether the loop runs a frame
     * @return {@code true} if the loop ran, {@code false} otherwise (the game ended before or in the loop or a frame would run past the finished replay)
     */
    private boolean runGameLoop(int elapsedHostTicks, boolean frameRuns) {
        this.executedCount = 0;

        if ((frameRuns && this.isFinished()) || this.isEnded()) {
            return false;
        }

        // Sounds of the previous iteration were drained (or discarded); this iteration's commands and frame raise new ones.
        this.game.getInterfaceLink().getSoundEvents().clear();
        // Command errors of the previous iteration were drained; this iteration's commands and frame raise new ones.
        this.game.getInterfaceLink().getCommandErrorEvents().clear();
        this.game.getInterfaceLink().getNotificationEvents().clear();
        this.frameRunner.prepareCommands();

        int currentFrame = this.game.getCurrentFrame();
        boolean turnStep = this.timing.takeFrameBudget();

        if (this.game.isReplayPlayback()) {
            this.selectReplayBlock(currentFrame);
        } else if (turnStep) {
            this.selectLiveCommands(currentFrame);
        } else {
            this.dueCommands.clear();
            this.dueIndex = 0;
            this.dueEnd   = this.commandIndex;
        }

        if (this.startupTurnPending) {
            IntSupplier startupCommandCount = this.startupTurnCommandCount;
            int startupEnd = startupCommandCount == null ? this.dueIndex : this.dueIndex + startupCommandCount.getAsInt();

            this.startupTurnPending = false;

            this.runDueCommands(Math.min(startupEnd, this.dueCommands.size()));
            this.exchangeLoopbackTurn();
            this.game.incrementTurnCount();
            this.updateSyncRecord();
        }

        this.runDueCommands(this.dueCommands.size());

        this.commandIndex = this.dueEnd;

        if (turnStep) {
            this.exchangeLoopbackTurn();
            this.game.incrementTurnCount();
            this.updateSyncRecord();
            this.timing.addTurnTimeout();
        }

        Runnable executed = this.commandsExecutedListener;

        if (executed != null) {
            executed.run();
        }

        if (this.game.getEnd() != null) {
            return false;
        }

        if (!frameRuns) {
            return true;
        }

        this.frameRunner.advance(elapsedHostTicks);
        this.lastCompletedFrame = this.game.getCurrentFrame();

        return true;
    }

    /**
     * Runs the due commands before an end index in order, in one command dispatch with the random generator enabled: the
     * command source ({@link #getCommandSource()}) asks the recorded stream ({@link RecordedStream}), which opens the
     * dispatch ({@link RecordedDispatch}) and hands each over to be executed. A failed callback leaves that command pending
     * and closes the dispatch.
     *
     * @param end the due command index the run stops at
     */
    private void runDueCommands(int end) {
        this.stream.end = end;

        try {
            this.commandSource.applyCommands(this.game.getEngineGame());
        } finally {
            this.dispatch.close();
        }
    }

    /**
     * Finds the engine player of a recorded command's issuer: the player of the record it resolves to, or the neutral player
     * for an issuer without a record (whose command the native dispatcher runs as a no-op or a barrier).
     *
     * @param command the recorded command
     * @return the engine player the stream submits the command as
     */
    private @NotNull Player findIssuer(@NotNull ReplayCommand command) {
        org.fogofwarlabs.sc4j.engine.game.Game engineGame = this.game.getEngineGame();
        int issuer = command.getPlayerId();

        if (issuer >= 0 && issuer <= ReplayCommandIssuer.LAST_ISSUER) {
            ReplayCommandIssuer resolved = ReplayCommandIssuer.resolve(this.game, issuer);

            if (resolved.getStatus() == ReplayCommandIssuer.Status.RESOLVED) {
                return engineGame.getPlayer(resolved.getRecordIndex());
            }
        }

        return engineGame.getPlayer(PlayerTable.NEUTRAL_PLAYER_ID);
    }

    /**
     * Opens a command dispatch: enables the random generator and the execution scope's command phase.
     */
    private void beginCommandDispatch() {
        GameExecutionScope scope = this.game.getExecutionScope();

        this.game.getRandom().setEnabled(1);

        if (scope != null) {
            scope.beginCommandDispatch();
        }
    }

    /**
     * Closes a command dispatch opened by {@link #beginCommandDispatch()}.
     */
    private void endCommandDispatch() {
        GameExecutionScope scope = this.game.getExecutionScope();

        this.game.getRandom().setEnabled(0);

        if (scope != null) {
            scope.endCommandDispatch();
        }
    }

    /**
     * Exchanges the local player's turns in a turn step of replay playback ({@link LoopbackTurns#exchangeTurn}) and runs
     * the received turn's commands that replay playback runs ({@link ReplayCommandUtils#walkReplayTurn}); a live game's
     * session exchanges its turns itself.
     */
    private void exchangeLoopbackTurn() {
        LoopbackTurns loopback = this.loopbackTurns;

        if (loopback == null) {
            return;
        }

        byte[] turn = loopback.exchangeTurn(LiveGame.LATENCY_TURNS + this.latency);

        ReplayCommandUtils.walkReplayTurn(turn, 0, turn.length, this::runLoopbackCommand);
    }

    /**
     * Makes the Sync record of a turn step of replay playback after the turn step counts ({@link SyncRing#update}); a live
     * game's session makes its own records.
     */
    private void updateSyncRecord() {
        SyncRing ring = this.syncRing;

        if (ring == null) {
            return;
        }

        ring.sampleDrawing(this.game);
        ring.update(this.game);
    }

    /**
     * Runs one command of a received turn of replay playback: Restart Game, Pause, Resume and Replay Seek run through the
     * command handler as the local player's. Keep Alive and Sync only count the turn's marker, which replay playback does
     * not read.
     *
     * @param turn   the received turn
     * @param offset the offset of the command's type byte
     * @param length the command length, type byte included
     */
    private void runLoopbackCommand(byte @NotNull [] turn, int offset, int length) {
        int typeId = Byte.toUnsignedInt(turn[offset]);
        byte[] payload = Arrays.copyOfRange(turn, offset + 1, offset + length);
        ByteBuffer fields = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        switch (typeId) {
            case ReplayCommandIds.RESTART_GAME, ReplayCommandIds.PAUSE, ReplayCommandIds.RESUME, ReplayCommandIds.REPLAY_SEEK -> {
                ReplayCommand command = new ReplayCommand(this.game.getCurrentFrame(), this.loopbackPlayerId, typeId, payload);

                this.beginCommandDispatch();

                try {
                    this.commandHandler.execute(this.game, command);
                } finally {
                    this.endCommandDispatch();
                }
            }
            case ReplayCommandIds.LATENCY -> this.latency = LiveGame.readLatency(Byte.toUnsignedInt(payload[0]));
            case ReplayCommandIds.REPLAY_SPEED -> this.applyReplaySpeed(fields.getInt(REPLAY_SPEED_SETTING_OFFSET),
                                                                        fields.getInt(REPLAY_SPEED_FACTOR_OFFSET),
                                                                        Byte.toUnsignedInt(fields.get(REPLAY_SPEED_MODE_OFFSET)));
            default -> {
            }
        }
    }

    /**
     * Applies a Replay Speed command: configures the speed ({@link #configureSpeed}) and pauses or continues the viewer's
     * playback by the mode.
     *
     * @param speed  the speed setting
     * @param factor the DWORD factor
     * @param mode   the mode byte, not {@code 0} while the playback is paused
     */
    private void applyReplaySpeed(int speed, int factor, int mode) {
        ReplayCommandExecutor.PlaybackControl playback = this.playbackControl;

        this.configureSpeed(speed, factor, mode);

        if (playback != null) {
            playback.setPlaybackPaused(mode != 0);
        }
    }

    /**
     * Queues a Replay Speed command of the viewer's controls.
     *
     * @param speed  the speed setting
     * @param factor the DWORD factor
     * @param mode   the mode byte
     * @throws IllegalStateException if the session runs a live game
     */
    private void queueReplaySpeed(int speed, int factor, int mode) {
        byte[] command = new byte[ReplayCommandUtils.getFixedLength(ReplayCommandIds.REPLAY_SPEED)];

        ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN)
                  .put((byte) ReplayCommandIds.REPLAY_SPEED)
                  .put((byte) mode)
                  .putInt(speed)
                  .putInt(factor);

        this.queueLocalCommand(command);
    }

    /**
     * Queues a command of the viewer's controls into the local outgoing turn.
     *
     * @param command the command bytes, type byte first
     * @throws IllegalStateException if the session runs a live game
     */
    private void queueLocalCommand(byte @NotNull [] command) {
        if (this.loopbackTurns == null) {
            throw Exceptions.newIllegalStateException("Only replay playback queues the replay viewer's commands.");
        }

        this.game.getInterfaceLink().getOutgoingTurn().queue(command);
    }

    /**
     * Selects the commands of a replay frame: once the signed frame counter passed the loaded frame, the buffer loads the
     * next frame's blocks (a loaded frame that never ran is dropped), and a loaded frame whose frame the counter has
     * reached runs. A grown buffer copy lasts until the end of this call, so a frame that runs in a later call than its
     * loading one runs from the buffer image.
     *
     * @param currentFrame the frame counter
     */
    private void selectReplayBlock(int currentFrame) {
        if (this.blockFrame > currentFrame) {
            return;
        }

        try {
            if (this.blockFrame < currentFrame) {
                if (this.blockPending) {
                    this.commandIndex = this.blockEnd;
                    this.dueEnd       = this.blockEnd;
                }

                this.blockFrame   = this.commandBuffer.load();
                this.blockPending = this.blockFrame != NO_BLOCK;
                this.loadedCommands.clear();

                if (!this.blockPending) {
                    return;
                }

                this.blockEnd = this.commandIndex + this.commandBuffer.getCount();

                if (this.blockFrame > currentFrame) {
                    return;
                }
            }

            if (!this.blockPending) {
                return;
            }

            this.blockPending = false;
            this.dueCommands.clear();
            this.commandBuffer.run(this.blockFrame, this.dueCommands);
            this.dueIndex = 0;
            this.dueEnd   = this.blockEnd;
        } finally {
            this.commandBuffer.release();
        }
    }

    /**
     * Selects the commands a live game recorded for this frame: every command not yet run, each recorded at the frame
     * counter of its turn ({@link LiveGame#exchangeTurn}).
     *
     * @param currentFrame the frame counter
     * @throws IllegalStateException if a command was recorded for another frame
     */
    private void selectLiveCommands(int currentFrame) {
        int end = this.commandIndex;

        while (end < this.commands.size() && (int) this.commands.get(end).getFrame() == currentFrame) {
            end++;
        }

        if (end < this.commands.size() && (int) this.commands.get(end).getFrame() < currentFrame) {
            throw Exceptions.newIllegalStateException("A live command was recorded for frame {0} before frame {1}.",
                                                      this.commands.get(end).getFrame(),
                                                      currentFrame);
        }

        this.dueCommands.clear();
        this.dueCommands.addAll(this.commands.subList(this.commandIndex, end));
        this.dueIndex = 0;
        this.dueEnd   = end;
    }
}
