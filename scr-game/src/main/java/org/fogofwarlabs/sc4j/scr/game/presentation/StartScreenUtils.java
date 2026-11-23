package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.StartLocation;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalView;
import org.fogofwarlabs.sc4j.scr.game.visibility.LocalVision;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Places the local screen of the game start ({@link LocalView#placeStartScreen}).
 * <p>
 * The screen starts on a start location centered in the screen width and the play view above the console
 * ({@link LocalView#calculateCenteredScreenX}, {@link LocalView#calculateCenteredScreenY}). A replay or observer view
 * ({@link LocalVision#isReplayOrObserver}) centers it on the start location of the first human. A live player's view
 * centers it on the local player's start location with its top-left rounded down to a multiple of 8 and then to whole
 * tiles. Each axis is clamped to the map less the screen width or height.
 */
public final class StartScreenUtils {
    /**
     * The default game screen width in pixels, which sessions without a display place their start screen with.
     */
    public static final int DEFAULT_SCREEN_WIDTH = 640;
    /**
     * The default game screen height in pixels.
     */
    public static final int DEFAULT_SCREEN_HEIGHT = 480;
    /**
     * The low position bits the live centering clears.
     */
    private static final int LIVE_ALIGNMENT_MASK = 7;


    /**
     * Places the game start's screen of a player in the game's local view.
     *
     * @param game         the game state
     * @param player       the player whose start location the screen starts on: in a replay or observer view the first human, in a live player's view the local player
     * @param screenWidth  the game screen width in map pixels
     * @param screenHeight the game screen height in map pixels
     * @return {@code true} if the screen was placed, {@code false} otherwise (a replay or observer view has no human player or the live player has no start location)
     */
    public static boolean placeStartScreen(@NotNull Game game, int player, int screenWidth, int screenHeight) {
        StartLocation start = findStartLocation(game, player);
        boolean replayOrObserver = game.getLocalVision().isReplayOrObserver();

        boolean placeable = replayOrObserver ? player >= 0 && player < PlayerTable.PLAYABLE_PLAYER_SLOTS : start != null;

        if (!placeable) {
            return false;
        }

        LocalView view = game.getInterfaceLink().getLocalView();
        int maximumScreenX = Math.max(0, game.getMap().getWidth() * TerrainTileset.TILE_SIZE - screenWidth);
        int maximumScreenY = Math.max(0, game.getMap().getHeight() * TerrainTileset.TILE_SIZE - screenHeight);

        view.setScreenGeometry(screenWidth, screenHeight, maximumScreenX, maximumScreenY);

        int screenX = view.calculateCenteredScreenX(start == null ? 0 : start.getX());
        int screenY = view.calculateCenteredScreenY(start == null ? 0 : start.getY());

        if (!replayOrObserver) {
            screenX = Math.max(0, screenX & ~LIVE_ALIGNMENT_MASK) >> TerrainTileset.TILE_SHIFT << TerrainTileset.TILE_SHIFT;
            screenY = Math.max(0, screenY & ~LIVE_ALIGNMENT_MASK) >> TerrainTileset.TILE_SHIFT << TerrainTileset.TILE_SHIFT;
        }

        view.placeStartScreen(screenX, screenY);

        return true;
    }

    /**
     * Finds the start location a player owns.
     *
     * @param game   the game state
     * @param player the player
     * @return the first start location of the player, or {@code null} if the player owns none
     */
    private static @Nullable StartLocation findStartLocation(@NotNull Game game, int player) {
        for (StartLocation start : game.getPlayerTable().getStartLocations()) {
            if (start.getOwner() == player) {
                return start;
            }
        }

        return null;
    }


    private StartScreenUtils() {
    }
}
