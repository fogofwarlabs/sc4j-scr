package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Extends running games of the SC:R mod with plugins: per-game state that observes the game and changes how it is shown, never the simulation.
 * A mod built from the SC:R mod's sources adds an implementation with a public no-argument constructor and names it in the resource
 * {@code META-INF/services/org.fogofwarlabs.sc4j.scr.game.extension.ScrGameExtension} of its jar; {@link ScrGameExtensionUtils} calls the named
 * implementations in the order of that file. The SC:R mod itself names none.
 */
public interface ScrGameExtension {
    /**
     * Creates the extension's plugin of a game, once, when the game's SC:R system attaches; the plugin lives as long as the game, so every new
     * game, live, replay or loaded save, starts with a new plugin.
     *
     * @param game the game, attached and not yet initialized
     * @return the created plugin, or {@code null} if the game needs none
     */
    @Nullable ScrGamePlugin createPlugin(@NotNull Game game);
}
