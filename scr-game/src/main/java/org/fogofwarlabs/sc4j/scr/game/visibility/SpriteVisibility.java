package org.fogofwarlabs.sc4j.scr.game.visibility;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.frame.GameFrame;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffect;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffectPool;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpritePool;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.jetbrains.annotations.NotNull;

/**
 * Applies placement-box, explored and position-tile visibility from tile DWORDs and the local comparison mask
 * ({@link LocalVision#getComparisonMask}).
 */
public final class SpriteVisibility {
    /**
     * The mask of a visibility BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of a unit type WORD.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The divisor of a footprint size that gives the distance from the sprite position to the footprint's first edge.
     */
    private static final int FOOTPRINT_CENTER_DIVISOR = 2;

    /**
     * The life a fog sprite effect starts with, which it keeps when the fog sprite copy fails.
     */
    private static final int FOG_SPRITE_LIFE = 1;

    /**
     * Applies the placement-box visibility to a unit's sprite ({@link #updateSpritePlacementBox}); a unit without a sprite reports no loss.
     *
     * @param unit           the unit whose sprite supplies decoded WORD coordinates
     * @param boxWidth       the signed placement-box width in pixels
     * @param boxHeight      the signed placement-box height in pixels
     * @param map            the established tile DWORDs
     * @param comparisonMask the visibility comparison mask
     * @return {@code true} if no player of the comparison mask sees the sprite any more after one did, or if the box lies outside the map while a player sees the sprite, {@code false} otherwise
     * @throws UnsupportedOperationException if the placement or map dimensions or the tile storage are not established
     */
    public static boolean updatePlacementBox(@NotNull Unit unit, int boxWidth, int boxHeight, @NotNull GameMap map, int comparisonMask) {
        Sprite sprite = unit.getSpriteOrNull();

        return sprite != null && updateSpritePlacementBox(sprite, boxWidth, boxHeight, map, comparisonMask);
    }

    /**
     * Applies the placement-box visibility to a sprite referenced by a unit or sprite effect: the players that see a tile of the box centered on the
     * sprite's decoded WORD coordinates, clipped at the map edges. A hidden sprite keeps its visibility, and so does a sprite whose box lies outside
     * the map.
     *
     * @param sprite         the referenced sprite
     * @param boxWidth       the signed width in pixels
     * @param boxHeight      the signed height in pixels
     * @param map            the established tile DWORDs
     * @param comparisonMask the visibility comparison mask
     * @return {@code true} if no player of the comparison mask sees the sprite any more after one did, or if the box lies outside the map while a player sees the sprite, {@code false} otherwise
     * @throws UnsupportedOperationException if the placement or map dimensions or the tile storage are not established
     */
    public static boolean updateSpritePlacementBox(@NotNull Sprite sprite, int boxWidth, int boxHeight, @NotNull GameMap map, int comparisonMask) {
        if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) != 0) {
            return false;
        }

        int mapWidth = map.getWidth();
        int mapHeight = map.getHeight();

        if (boxWidth < 0 || boxWidth > Short.MAX_VALUE || boxHeight < 0 || boxHeight > Short.MAX_VALUE ||
            mapWidth <= 0 || mapWidth > Short.MAX_VALUE || mapHeight <= 0 || mapHeight > Short.MAX_VALUE ||
            (long) mapWidth * mapHeight != map.getTileFlagCount()) {
            throw Exceptions.newUnsupportedOperationException("Visibility requires established nonnegative " +
                                                              "placement dimensions and matching tile storage.");
        }

        int firstColumn = ((short) sprite.getX() - boxWidth / FOOTPRINT_CENTER_DIVISOR) / TerrainTileset.TILE_SIZE;
        int firstRow = ((short) sprite.getY() - boxHeight / FOOTPRINT_CENTER_DIVISOR) / TerrainTileset.TILE_SIZE;
        int columns = (boxWidth + TerrainTileset.TILE_SIZE - 1) >>> TerrainTileset.TILE_SHIFT;
        int rows = (boxHeight + TerrainTileset.TILE_SIZE - 1) >>> TerrainTileset.TILE_SHIFT;
        int previousVisibility = sprite.getVisibilityFlags() & BYTE_MASK;

        if (firstColumn < 0) {
            columns += firstColumn;

            if (columns <= 0) {
                return previousVisibility != 0;
            }

            firstColumn = 0;
        }

        if (firstRow < 0) {
            rows += firstRow;

            if (rows <= 0) {
                return previousVisibility != 0;
            }

            firstRow = 0;
        }

        if (firstColumn + columns > mapWidth) {
            columns = mapWidth - firstColumn;

            if (columns <= 0) {
                return previousVisibility != 0;
            }
        }

        if (firstRow + rows > mapHeight) {
            rows = mapHeight - firstRow;

            if (rows <= 0) {
                return previousVisibility != 0;
            }
        }

        int visibility = 0;

        for (int row = firstRow + rows - 1; row >= firstRow; row--) {
            for (int column = firstColumn + columns - 1; column >= firstColumn; column--) {
                visibility |= ~map.getTileFlags(row * mapWidth + column) & BYTE_MASK;
            }
        }

        if (visibility == previousVisibility) {
            return false;
        }

        apply(sprite, visibility, comparisonMask);

        return (previousVisibility & comparisonMask) != 0 && (visibility & comparisonMask) == 0;
    }

    /**
     * Applies the explored visibility to the sprite of a sprite effect: the players that explored a tile of the sprite's box centered on its decoded
     * WORD coordinates, clipped at the map edges, or every player with full visibility. A hidden sprite keeps its visibility.
     *
     * @param sprite         the sprite of a sprite effect
     * @param map            the original tile DWORDs
     * @param comparisonMask the visibility comparison mask
     * @param fullVisibility whether both replay/full-visibility flags are nonzero
     * @return {@code true} if no player of the comparison mask sees the sprite any more after one did, {@code false} otherwise
     * @throws UnsupportedOperationException if the map dimensions or the tile storage are not established
     */
    public static boolean updateExplored(@NotNull Sprite sprite, @NotNull GameMap map, int comparisonMask, boolean fullVisibility) {
        if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) != 0) {
            return false;
        }

        int mapWidth = map.getWidth();
        int mapHeight = map.getHeight();

        if (mapWidth <= 0 || mapHeight <= 0 || (long) mapWidth * mapHeight != map.getTileFlagCount()) {
            throw Exceptions.newUnsupportedOperationException("Explored visibility requires established map dimensions and tile storage.");
        }

        int firstColumn = ((short) sprite.getX() - sprite.getWidth() / FOOTPRINT_CENTER_DIVISOR) / TerrainTileset.TILE_SIZE;
        int firstRow = ((short) sprite.getY() - sprite.getHeight() / FOOTPRINT_CENTER_DIVISOR) / TerrainTileset.TILE_SIZE;
        int columns = (sprite.getWidth() + TerrainTileset.TILE_SIZE - 1) >>> TerrainTileset.TILE_SHIFT;
        int rows = (sprite.getHeight() + TerrainTileset.TILE_SIZE - 1) >>> TerrainTileset.TILE_SHIFT;

        if (firstColumn < 0) {
            columns += firstColumn;
            firstColumn = 0;
        } else if (firstColumn + columns > mapWidth) {
            columns = mapWidth - firstColumn;
        }

        if (firstRow < 0) {
            rows += firstRow;
            firstRow = 0;
        } else if (firstRow + rows > mapHeight) {
            rows = mapHeight - firstRow;
        }

        int visibility = fullVisibility ? Sprite.VISIBLE_TO_ALL_PLAYERS : 0;

        if (!fullVisibility) {
            for (int row = (short) firstRow + (short) rows - 1; row >= (short) firstRow; row--) {
                for (int column = (short) firstColumn + (short) columns - 1; column >= (short) firstColumn; column--) {
                    visibility |= ~(map.getTileFlags(row * mapWidth + column) >>> MapTileFlags.NOT_EXPLORED_SHIFT) & BYTE_MASK;
                }
            }
        }

        int previousVisibility = sprite.getVisibilityFlags() & BYTE_MASK;

        if (visibility == previousVisibility) {
            return false;
        }

        apply(sprite, visibility, comparisonMask);

        return (previousVisibility & comparisonMask) != 0 && (visibility & comparisonMask) == 0;
    }

    /**
     * Applies the visibility of the tile under the sprite of a sprite effect or bullet: the players whose not-visible bit is clear in the tile flags
     * at the sprite's WORD position ({@link GameMap#getTileFlagsAtPosition}). A hidden sprite keeps its visibility.
     *
     * @param sprite         the sprite of a sprite effect or bullet
     * @param map            the tile DWORDs
     * @param comparisonMask the visibility comparison mask
     */
    public static void updatePositionTile(@NotNull Sprite sprite, @NotNull GameMap map, int comparisonMask) {
        if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) != 0) {
            return;
        }

        apply(sprite, ~map.getTileFlagsAtPosition(sprite.getX(), sprite.getY()) & MapTileFlags.NOT_VISIBLE, comparisonMask);
    }

    /**
     * Stores sprite visibility and marks images when the masked visible value changes to nonzero.
     *
     * @param sprite         the sprite
     * @param visibility     the new unsigned visibility BYTE
     * @param comparisonMask the visibility comparison mask
     */
    public static void apply(@NotNull Sprite sprite, int visibility, int comparisonMask) {
        visibility &= BYTE_MASK;

        int maskedVisibility = visibility & comparisonMask;

        if ((sprite.getVisibilityFlags() & comparisonMask & BYTE_MASK) != maskedVisibility && maskedVisibility != 0) {
            sprite.markImagesForRedraw();
        }

        sprite.setVisibilityFlags(visibility);
    }

    /**
     * Tests whether the rectangle of a retained fog sprite stays hidden: every tile, visited from the last row and column back to the first, is
     * hidden from the local view ({@link LocalVision#isTileHidden}).
     *
     * @param map         the tile DWORD storage
     * @param firstColumn the first column WORD
     * @param firstRow    the first row WORD
     * @param columns     the column count WORD
     * @param rows        the row count WORD
     * @param vision      the vision of the local view
     * @return {@code true} if every visited tile remains hidden, {@code false} otherwise
     * @throws UnsupportedOperationException if the rectangle leaves the represented tile storage
     */
    public static boolean isFogRectangleHidden(@NotNull GameMap map,
                                               int firstColumn, int firstRow,
                                               int columns, int rows,
                                               @NotNull LocalVision vision) {
        firstColumn = (short) firstColumn;
        firstRow    = (short) firstRow;
        columns     = (short) columns;
        rows        = (short) rows;

        for (int row = firstRow + rows - 1; row >= firstRow; row--) {
            for (int column = firstColumn + columns - 1; column >= firstColumn; column--) {
                int tileIndex = row * map.getWidth() + column;

                if (tileIndex < 0 || tileIndex >= map.getTileFlagCount()) {
                    throw Exceptions.newUnsupportedOperationException("The fog rectangle accesses outside represented tile storage.");
                }

                if (!vision.isTileHidden(map.getTileFlags(tileIndex))) {
                    return false;
                }
            }
        }

        return true;
    }

    private final @NotNull Game game;

    /**
     * Creates a {@link SpriteVisibility} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public SpriteVisibility(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Updates the visibility of a unit's sprite like {@link #update(Unit, GameMap, int, int)} without an instruction budget, so a fog sprite cannot
     * be created.
     *
     * @param unit           the unit
     * @param map            the established row-major tile DWORDs
     * @param comparisonMask the established visibility comparison mask
     */
    public void update(@NotNull Unit unit, @NotNull GameMap map, int comparisonMask) {
        this.update(unit, map, comparisonMask, 0);
    }

    /**
     * Updates the placement-box visibility of a unit's sprite ({@link #updatePlacementBox}) and gives a subunit sprite that is not hidden the same
     * visibility. A unit the comparison mask loses leaves the local selection and hides its selection circle and team marks; a grounded building or a
     * trap that is not destroyed while dying also leaves a fog sprite in a free secondary sprite effect.
     *
     * @param unit             the unit
     * @param map              the retained tile visibility DWORDs
     * @param comparisonMask   the local comparison mask
     * @param instructionLimit the instruction budget of the initial image script of a created fog sprite
     * @throws UnsupportedOperationException if visibility inheritance lacks a retained sprite or fog registration lacks its inputs
     * @throws IllegalStateException         if a unit without a sprite loses visibility
     * @throws NullPointerException          if the world was built without game data
     */
    public void update(@NotNull Unit unit, @NotNull GameMap map, int comparisonMask, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();
        int boxWidth = (short) units.getPlacementWidth(unitType);
        int boxHeight = (short) units.getPlacementHeight(unitType);
        boolean lostVisibility = updatePlacementBox(unit, boxWidth, boxHeight, map, comparisonMask);
        Sprite sprite = unit.getSpriteOrNull();
        Unit subunit = unit.getSubunit();

        if (subunit != null) {
            Sprite subunitSprite = subunit.getSpriteOrNull();

            if (subunitSprite == null || sprite == null) {
                throw Exceptions.newUnsupportedOperationException("Visibility inheritance requires both retained sprites.");
            }

            if ((subunitSprite.getFlags() & Sprite.FLAG_HIDDEN) == 0) {
                apply(subunitSprite, sprite.getVisibilityFlags(), comparisonMask);
            }
        }

        if (!lostVisibility) {
            return;
        }

        if (sprite == null) {
            throw Exceptions.newIllegalStateException("A missing sprite cannot lose native visibility.");
        }

        // The local player stops selecting a unit it no longer sees and loses its circles.
        if ((sprite.getFlags() & Sprite.FLAG_SELECTED) != 0) {
            this.game.getInterfaceLink().getLocalSelection().removeUnit(unit);
        }

        this.game.getSelectionImages().hideSelectionCircle(sprite);
        this.game.getSelectionImages().clearTeamMarks(sprite);

        if ((UnitQueries.isGroundedBuilding(unit) || units.hasBuildingVisibility(unitType)) && !unit.isDestroyed()) {
            GameFrame frame = this.game.getFrame();
            UnitPool unitPool = this.game.getUnitSystem().getUnitPool();
            SpritePool spritePool = this.game.getSpriteSystem().getSpritePool();

            if (frame == null || unitPool == null || spritePool == null || instructionLimit <= 0) {
                throw Exceptions.newUnsupportedOperationException("Fog registration requires its secondary " +
                                                                  "pool, unit metadata and explicit image budget.");
            }

            SpriteEffectPool spriteEffects = frame.getSecondarySpriteEffects();
            SpriteEffect spriteEffect = spriteEffects.allocate(false);

            if (spriteEffect == null) {
                return;
            }

            Sprite fogSprite = this.game.getIscriptExecutor().copyFogSprite(sprite, instructionLimit);

            spriteEffect.setLife(FOG_SPRITE_LIFE);

            if (fogSprite != null) {
                spriteEffect.setLife(unitType & WORD_MASK);
            }

            spriteEffects.finishInitialization(spriteEffect, fogSprite);

            if (fogSprite != null) {
                spritePool.registerFogMetadata(fogSprite, unitType, unitPool.getCreationTag(unit.getIndex()));
            }
        }
    }

    /**
     * Updates every unit of the active unit list, in list order, with the local comparison mask ({@link #update(Unit, GameMap, int, int)}). A game
     * without a unit pool has no unit to update.
     *
     * @param instructionLimit the instruction budget of the initial image script of a created fog sprite
     */
    public void updateUnits(int instructionLimit) {
        UnitPool pool = this.game.getUnitSystem().getUnitPool();

        if (pool == null) {
            return;
        }

        GameMap map = this.game.getMap();
        int comparisonMask = this.game.getLocalVision().getComparisonMask();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            this.update(pool.getUnit(poolIndex), map, comparisonMask, instructionLimit);
        }
    }
}
