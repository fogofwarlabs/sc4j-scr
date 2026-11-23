package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.computer.ComputerController;
import org.fogofwarlabs.sc4j.engine.computer.ComputerPlayerSystem;
import org.fogofwarlabs.sc4j.scr.game.frame.GameFrame;
import org.fogofwarlabs.sc4j.scr.game.frame.ScrFramePhase;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs the SC:R computer players: the native computer step of the frame, the scheduler of the game's {@link ComputerPlayerPhase} with the
 * frame's player types and the elapsed trigger clock.
 * <p>
 * This is the game's engine {@link ComputerPlayerSystem}: its frame phase is the computer step of the world phases
 * ({@link ScrFramePhase#COMPUTER_PLAYERS}), which runs {@link #update} with the frame of the world step, at the native position of the step
 * in the frame. The controllers are the {@link ScrComputerController}s of the computer-player phase, one per playable slot; the scheduler
 * runs the per-player update of a slot through its controller. The computer players hear the generic places of the simulation through
 * their hooks ({@link ComputerHooks}), which do not run this step.
 */
public final class ScrComputerPlayerSystem implements ComputerPlayerSystem {
    /**
     * Returns the SC:R computer player system of a game.
     *
     * @param game the engine game
     * @return the game's computer player system
     * @throws IllegalStateException if the game's computer player system is not the SC:R one
     */
    public static @NotNull ScrComputerPlayerSystem of(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game) {
        if (!(game.getComputerPlayers() instanceof ScrComputerPlayerSystem computerPlayers)) {
            throw Exceptions.newIllegalStateException("The game has no SC:R computer player system.");
        }

        return computerPlayers;
    }


    private @Nullable Game game;

    /**
     * Creates a {@link ScrComputerPlayerSystem} instance; it serves the SC:R game it finds when it is attached.
     */
    public ScrComputerPlayerSystem() {
        this.game = null;
    }

    /**
     * Finds the controller of a player.
     * <p>
     * A playable slot whose player record is currently a computer player has the controller of the computer-player phase; before world
     * initialization installs that phase no player has one.
     *
     * @param player the player
     * @return the controller that plays the player, or {@code null} if no computer plays it
     */
    @Override
    public @Nullable ComputerController getController(org.fogofwarlabs.sc4j.engine.player.@NotNull Player player) {
        Game attached = this.requireGame();
        ComputerPlayerPhase phase = attached.getComputerPlayerPhase();
        int index = player.getIndex();

        if (phase == null || index >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return null;
        }

        Player record = attached.getPlayerTable().getPlayer(index);

        return record != null && record.getType() == PlayerTypes.COMPUTER ? phase.getController(index) : null;
    }

    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Binds the SC:R game system of the game.
     *
     * @param game the game
     */
    @Override
    public void attach(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game) {
        this.game = game.getModSystem(Game.SYSTEM_KEY);
    }

    /**
     * Checks whether the game has what the computer step needs before a frame starts: its computer player phase, which world initialization
     * installs. A frame whose phases are not all ready fails before any phase runs.
     *
     * @return {@code true} if the step can run, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    public boolean isReady() {
        return this.requireGame().getComputerPlayerPhase() != null;
    }

    /**
     * Runs the computer players of the current frame.
     * <p>
     * Runs the computer step ({@link #update}) with the game's current frame.
     *
     * @param game the game
     * @throws UnsupportedOperationException if the world is initialized without a trigger context or the computer-player phase is not initialized
     */
    @Override
    public void run(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game) {
        this.update(this.requireGame().requireFrame());
    }

    /**
     * Runs the computer step of a frame: the scheduler of the computer-player phase with the frame's player types, the elapsed trigger clock
     * and the frame's instruction limit. A game with an initialized world must have its trigger clock; before that, the elapsed clock is
     * zero.
     *
     * @param frame the frame, with this frame's inputs
     * @throws UnsupportedOperationException if the world is initialized without a trigger context or the computer-player phase is not initialized
     */
    public void update(@NotNull GameFrame frame) {
        Game attached = this.requireGame();
        TriggerContext triggers = attached.getTriggerContext();

        if (triggers == null && attached.hasInitializedWorldState()) {
            throw Exceptions.newUnsupportedOperationException("Regular game phases require the established trigger clock.");
        }

        ComputerPlayerPhase computerPhase = attached.requireComputerPlayerPhase();

        computerPhase.update(frame.getPlayerTypes(), triggers == null ? 0 : triggers.getElapsedClockTicks(), frame.getInstructionLimit());
    }

    private @NotNull Game requireGame() {
        Game attached = this.game;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R computer player system is not attached to its engine game yet.");
        }

        return attached;
    }
}
