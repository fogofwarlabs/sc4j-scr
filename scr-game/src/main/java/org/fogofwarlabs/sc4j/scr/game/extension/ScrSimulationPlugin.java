package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SimulationDecision;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SimulationEvent;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceLink;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;

/**
 * The per-game state of a {@link ScrSimulationExtension}. A plugin reads and changes the game through the {@link Game} it was created for; its
 * methods run on the thread that runs the game, inside the routines that call them, in the same order on every peer and in every replay of the
 * game. A plugin keeps the simulation deterministic: it draws no numbers from the game's random generator and keeps its own
 * generator, seeded from the game's seed, for its own draws. Its state is not part of the game's state hash or saves; a plugin whose game must not
 * be saved says so ({@link #allowsSave()}).
 * <p>
 * Decisions ({@link #decide}) run inside the routine that offers them, which continues with the values the plugin leaves in the decision
 * object; a plugin changes only the decision object there. Events ({@link #onEvent}) are reported while their routine runs, so a plugin that
 * changes the game in answer to an event keeps the event and acts on it later, at the next frame start ({@link #onFrameStarted}).
 * <p>
 * Adjustments ({@link #adjustWeaponRange}, {@link #adjustMovementSpeed}, {@link #adjustAcceleration}, {@link #adjustMaximumHitPoints}) run
 * inside the routines that compute a unit's value, often several times a frame, and the interface also reads the maximum hit points for its
 * display. A plugin answers them from its own state, without changing the game or allocating; the plugins adjust in extension order, each on
 * the value the previous one returned.
 */
public interface ScrSimulationPlugin {
    /**
     * Called once per frame when the frame's trigger step runs, before the triggers, with the random generator enabled as for the triggers; the
     * trigger step also runs while the game is paused.
     */
    default void onFrameStarted() {
    }

    /**
     * Called when something happens in the simulation, from inside the routine where it happens.
     *
     * @param event the event; its kind is its class
     */
    default void onEvent(@NotNull SimulationEvent event) {
    }

    /**
     * Called at a decision point of a simulation routine, which continues with the values the plugins leave in the decision; the plugins decide
     * in extension order, each on the values the previous one left.
     *
     * @param decision the decision; its kind is its class
     */
    default void decide(@NotNull SimulationDecision decision) {
    }

    /**
     * Adjusts the maximum range of a unit's weapon after the native bonuses (the Bunker, the range upgrades and the heroes), wherever the
     * simulation reads the range: the attack range tests, the target searches and the spell ranges of weapons.
     *
     * @param unit   the attacking unit or turret
     * @param weapon the weapons.dat entry
     * @param range  the range in pixels
     * @return the range in pixels, compared unsigned
     */
    default int adjustWeaponRange(@NotNull Unit unit, int weapon, int range) {
        return range;
    }

    /**
     * Adjusts a movement speed of a unit after its speed modifiers (the speed upgrades, Stim Packs and Ensnare): the {@code flingy.dat} top speed
     * when the unit's movement fields are recalculated, and the speed each iscript {@code move} instruction gives a unit that its iscript moves,
     * which also makes the top speed sampled from its Walking animation.
     *
     * @param unit  the unit
     * @param speed the speed in {@code 1/256} pixels per frame
     * @return the speed in {@code 1/256} pixels per frame
     */
    default int adjustMovementSpeed(@NotNull Unit unit, int speed) {
        return speed;
    }

    /**
     * Adjusts the acceleration of a unit after its speed modifiers, when the unit's movement fields are recalculated.
     *
     * @param unit         the unit
     * @param acceleration the {@code flingy.dat} acceleration after the speed modifiers
     * @return the acceleration, a WORD
     */
    default int adjustAcceleration(@NotNull Unit unit, int acceleration) {
        return acceleration;
    }

    /**
     * Adjusts the maximum hit points of a unit, wherever the game reads the maximum of a unit: the hit point limit, completion, repair,
     * construction, the percentages of the triggers and the display.
     *
     * @param unit             the unit
     * @param maximumHitPoints the {@code units.dat} hit points of the unit's type in {@code 1/256} points
     * @return the maximum hit points in {@code 1/256} points
     */
    default int adjustMaximumHitPoints(@NotNull Unit unit, int maximumHitPoints) {
        return maximumHitPoints;
    }

    /**
     * Called when a mod command of a player runs, at its place in the command stream of the frame. The player is the record the command's issuer
     * resolved to, the issuing player's own record also in a team game, where the other commands of every member act for the team's leader. A
     * live game queues the mod commands of the local player through the game's mod command submitter
     * ({@link InterfaceLink#getModCommandSubmitter()}).
     *
     * @param player the issuing player's record
     * @param data   the command data, which every plugin of the game receives; the plugin does not change it
     */
    default void onModCommand(@NotNull Player player, byte @NotNull [] data) {
    }

    /**
     * Tells whether the game can be saved while this plugin runs it; a game is saved only when every plugin allows it
     * ({@link Game#isSaveAllowed()}), so a plugin whose state a saved game would lose returns {@code false}. The SC:R mod asks when it offers a
     * save, when it sends a save command and when it writes a saved game.
     *
     * @return {@code true} if the game can be saved, {@code false} otherwise
     */
    default boolean allowsSave() {
        return true;
    }
}
