package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemoryView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The per-game state of a {@link ScrGameExtension}. A plugin reads the game and keeps presentation state of its own; it changes no simulation
 * state, so the state hash, saves, replays and network games are the same with or without it. Its methods run on the thread that runs and draws
 * the game.
 */
public interface ScrGamePlugin {
    /**
     * Hears that the game's EUD address space was created, on the first EUD access of the game's triggers; the plugin may observe writes and read
     * memory through the view from then on.
     *
     * @param memory the read-only view of the address space
     */
    default void onAddressSpaceCreated(@NotNull ClassicMemoryView memory) {
    }

    /**
     * Finds the graphics a sprite image draws instead of its installed GRP: an indexed sheet whose frames the image's frame index selects. Selection
     * circles, health bars and the selection tests keep the installed graphics.
     *
     * @param image an image of a drawn sprite
     * @return the indexed sheet the image draws, or {@code null} for its installed graphics
     */
    default @Nullable SpriteSheet findImageSheet(@NotNull SpriteImage image) {
        return null;
    }
}
