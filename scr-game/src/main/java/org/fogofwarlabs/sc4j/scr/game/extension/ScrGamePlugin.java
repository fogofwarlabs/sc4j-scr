package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
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

    /**
     * Finds the frame a sprite image draws instead of its own drawn frame, in its installed graphics or in its sheet ({@link #findImageSheet}).
     *
     * @param image an image of a drawn sprite
     * @return the frame index, or {@code -1} for the image's own frame
     */
    default int findImageFrame(@NotNull SpriteImage image) {
        return -1;
    }

    /**
     * Finds the draw function a sprite image draws with instead of its own ({@link SpriteImage#getModifier()}).
     *
     * @param image an image of a drawn sprite
     * @return the draw function, or {@code -1} for the image's own
     */
    default int findImageDrawFunction(@NotNull SpriteImage image) {
        return -1;
    }

    /**
     * Finds the picture a terrain megatile draws instead of its installed one: {@value TerrainTileset#TILE_SIZE} by
     * {@value TerrainTileset#TILE_SIZE} palette indices, rows from the top. The same array is returned while the picture is unchanged.
     *
     * @param megatile the megatile index of a drawn tile
     * @return the palette indices, or {@code null} for the installed picture
     */
    default byte @Nullable [] findTerrainTile(int megatile) {
        return null;
    }

    /**
     * Finds the picture the plugin draws over the game screen and its console.
     *
     * @return the overlay, or {@code null} for none
     */
    default @Nullable ScrScreenOverlay findScreenOverlay() {
        return null;
    }

    /**
     * @return {@code true} if the console is not drawn, {@code false} if it is
     */
    default boolean isConsoleHidden() {
        return false;
    }

    /**
     * Finds how the plugin places the console: moved dialogs, the shown console pixels and the dialog pictures.
     *
     * @return the placement, or {@code null} for the installed console
     */
    default @Nullable ScrConsolePlacement findConsolePlacement() {
        return null;
    }
}
