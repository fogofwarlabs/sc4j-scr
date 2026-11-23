package org.fogofwarlabs.sc4j.scr.game.visibility;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.visibility.SightDescriptors;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerRelations;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Applies the SC:R sight of units with the engine sight descriptors ({@link SightDescriptors}) on the map tile flags ({@link GameMap}, the SC:R
 * sight storage): the native admission of a reveal (an unsigned player mask up to {@code 0xFF}, twelve descriptors), the {@code 32}-pixel tiles
 * and the blocking terrain heights of the unit's ground. The shared-vision masks remain available for the game's frames; the local comparison
 * mask belongs to {@link LocalVision}.
 */
public final class SightCoverage {
    /**
     * The number of sight descriptors, one per sight range.
     */
    public static final int DESCRIPTOR_COUNT = 12;
    /**
     * The number of fields of a sight record ({@link #getRecordField}).
     */
    public static final int RECORD_FIELD_COUNT = 7;
    /**
     * The sight record field of the first predecessor record index.
     */
    public static final int RECORD_FIELD_FIRST_PREDECESSOR = 0;
    /**
     * The sight record field of the second predecessor record index.
     */
    public static final int RECORD_FIELD_SECOND_PREDECESSOR = 1;
    /**
     * The sight record field of the tile offset from the center tile.
     */
    public static final int RECORD_FIELD_TILE_OFFSET = 2;
    /**
     * The sight record field of the cached tile flags of the last ground reveal.
     */
    public static final int RECORD_FIELD_CACHED_FLAGS = 3;
    /**
     * The sight record field of the signed horizontal tile offset.
     */
    public static final int RECORD_FIELD_HORIZONTAL = 4;
    /**
     * The sight record field of the signed vertical tile offset.
     */
    public static final int RECORD_FIELD_VERTICAL = 5;
    /**
     * The sight record field of the number of predecessor records.
     */
    public static final int RECORD_FIELD_PREDECESSOR_COUNT = 6;

    /**
     * The largest player mask that reveals tiles: every playable player.
     */
    private static final int MAXIMUM_VISIBILITY_MASK = 0xFF;

    /**
     * Tests the unsigned player-mask and sight-descriptor admission of a reveal. A zero mask is admitted.
     *
     * @param visibilityMask the unsigned player mask
     * @param radiusIndex    the unsigned sight descriptor index
     * @return {@code true} if the reveal inputs are admitted, {@code false} otherwise
     */
    private static boolean canReveal(int visibilityMask, int radiusIndex) {
        return !(Integer.compareUnsigned(visibilityMask, MAXIMUM_VISIBILITY_MASK) > 0 || Integer.compareUnsigned(radiusIndex, DESCRIPTOR_COUNT) >= 0);
    }


    private final @NotNull SightDescriptors descriptors;
    private int @NotNull [] sharedVisionMasks;

    /**
     * Creates a {@link SightCoverage} instance with the twelve original sight descriptors ({@link SightDescriptors}): for each sight range a
     * midpoint-circle mask, ordered as a spiral from the center, whose records name the one or two records nearer the center that a ground reveal
     * tests first.
     *
     * @param mapWidth the established map width used by descriptor tile offsets
     * @throws IllegalArgumentException if the map width is outside {@code 1..256}
     * @throws IllegalStateException    if the traversal references a record that is not initialized
     */
    public SightCoverage(int mapWidth) {
        if (mapWidth <= 0 || mapWidth > GameMap.MAXIMUM_DIMENSION) {
            throw Exceptions.newIllegalArgumentException("Native fog requires an established map width.");
        }

        this.descriptors       = new SightDescriptors(mapWidth, DESCRIPTOR_COUNT);
        this.sharedVisionMasks = new int[PlayerTable.PLAYER_SLOTS];
    }

    /**
     * @param playerIndex the player index
     * @return the shared-vision DWORD
     */
    public int getSharedVisionMask(int playerIndex) {
        return this.sharedVisionMasks[playerIndex];
    }

    /**
     * @param radiusIndex the original zero-based sight descriptor index
     * @return the number of ordered records, including the central nine unconditional records
     */
    public int getRecordCount(int radiusIndex) {
        return this.descriptors.getRecordCount(radiusIndex);
    }

    /**
     * Returns one field of a sight record. The fields {@code 0} to {@link #RECORD_FIELD_COUNT} minus one are the predecessor indices
     * ({@link #RECORD_FIELD_FIRST_PREDECESSOR}, {@link #RECORD_FIELD_SECOND_PREDECESSOR}), the tile offset, the cached flags, the signed
     * coordinates and the predecessor count.
     *
     * @param radiusIndex the original sight descriptor index
     * @param recordIndex the ordered record index
     * @param field       the field
     * @return the represented record field
     * @throws IllegalArgumentException if the field is unknown
     */
    public int getRecordField(int radiusIndex, int recordIndex, int field) {
        SightDescriptors records = this.descriptors;

        return switch (field) {
            case RECORD_FIELD_FIRST_PREDECESSOR -> records.getFirstPredecessor(radiusIndex, recordIndex);
            case RECORD_FIELD_SECOND_PREDECESSOR -> records.getSecondPredecessor(radiusIndex, recordIndex);
            case RECORD_FIELD_TILE_OFFSET -> records.getTileOffset(radiusIndex, recordIndex);
            case RECORD_FIELD_CACHED_FLAGS -> records.getCachedFlags(radiusIndex, recordIndex);
            case RECORD_FIELD_HORIZONTAL -> records.getHorizontalOffset(radiusIndex, recordIndex);
            case RECORD_FIELD_VERTICAL -> records.getVerticalOffset(radiusIndex, recordIndex);
            case RECORD_FIELD_PREDECESSOR_COUNT -> records.getPredecessorCount(radiusIndex, recordIndex);
            default -> throw Exceptions.newIllegalArgumentException("Unknown native fog record field.");
        };
    }

    /**
     * Sets one player's shared-vision DWORD after a vision command.
     *
     * @param playerIndex the player index
     * @param mask        the new mask
     */
    public void setSharedVisionMask(int playerIndex, int mask) {
        this.sharedVisionMasks[playerIndex] = mask;
    }

    /**
     * Sets the game's shared-vision DWORDs, copying the supplied masks.
     *
     * @param sharedVisionMasks the twelve shared-vision DWORDs of the game context
     * @throws IllegalArgumentException if twelve player masks are not supplied
     */
    public void setSharedVisionMasks(int @NotNull [] sharedVisionMasks) {
        if (sharedVisionMasks.length != PlayerTable.PLAYER_SLOTS) {
            throw Exceptions.newIllegalArgumentException("Native shared vision requires twelve player masks.");
        }

        System.arraycopy(sharedVisionMasks, 0, this.sharedVisionMasks, 0, PlayerTable.PLAYER_SLOTS);
    }

    /**
     * Uses the given shared-vision DWORDs instead of its own copy.
     *
     * @param sharedVisionMasks the twelve shared-vision DWORDs of the game context ({@link PlayerRelations#shareVision})
     * @throws IllegalArgumentException if twelve player masks are not supplied
     */
    public void useSharedVisionMasks(int @NotNull [] sharedVisionMasks) {
        if (sharedVisionMasks.length != PlayerTable.PLAYER_SLOTS) {
            throw Exceptions.newIllegalArgumentException("Native shared vision requires twelve player masks.");
        }

        this.sharedVisionMasks = sharedVisionMasks;
    }


    /**
     * Reveals the tiles of a sight descriptor around a position ({@link #reveal(GameMap, int, int, boolean, int, int, int)}). An admitted ground
     * reveal reads the terrain height of the position first; a rejected mask or descriptor index and an air reveal read no terrain.
     *
     * @param game           the game owning the map and its terrain
     * @param positionX      the signed pixel X coordinate
     * @param positionY      the signed pixel Y coordinate
     * @param air            whether the original air predicate is nonzero
     * @param visibilityMask the original unsigned player mask
     * @param radiusIndex    the original sight descriptor index
     * @throws IllegalStateException if the terrain heights of a ground reveal cannot be loaded
     * @throws NullPointerException  if the world was built without game data
     */
    public void reveal(@NotNull Game game, int positionX, int positionY, boolean air, int visibilityMask, int radiusIndex) {
        if (!canReveal(visibilityMask, radiusIndex)) {
            return;
        }

        GameMap map = game.getMap();
        int terrainHeight = 0;

        if (!air) {
            try {
                terrainHeight = map.getTerrainHeight(game.requireGameData().getTerrainTileset(map.getTileset()), positionX, positionY);
            } catch (IOException exception) {
                throw Exceptions.newIllegalStateException(exception, "Ground reveal requires installed terrain heights.");
            }
        }

        this.reveal(map, positionX, positionY, air, visibilityMask, radiusIndex, terrainHeight);
    }

    /**
     * Reveals the tiles of a sight descriptor around a position: an air reveal writes every record but the last nine; a ground reveal writes the
     * central records, then each further record with a predecessor record that is not blocked by the tile heights above the unit's ground, clipped at
     * the map edges.
     *
     * @param map            the map owning the initialized visibility DWORDs
     * @param positionX      the signed pixel X coordinate
     * @param positionY      the signed pixel Y coordinate
     * @param air            whether the original air predicate is nonzero
     * @param visibilityMask the original unsigned player mask
     * @param radiusIndex    the original sight descriptor index
     * @param terrainHeight  the original terrain height result for ground units
     * @throws IllegalArgumentException if the map width differs from the descriptors' width
     */
    public void reveal(@NotNull GameMap map, int positionX, int positionY, boolean air, int visibilityMask, int radiusIndex, int terrainHeight) {
        if (!canReveal(visibilityMask, radiusIndex)) {
            return;
        }

        if (map.getWidth() != this.descriptors.getMapWidth()) {
            throw Exceptions.newIllegalArgumentException("Native fog offsets belong to a different map width.");
        }

        int column = positionX / TerrainTileset.TILE_SIZE;
        int row = positionY / TerrainTileset.TILE_SIZE;
        int blockers = visibilityMask | map.getGroundBlockers(terrainHeight);

        // Requires the expanded terrain flags.
        map.getTileFlagCount();

        this.descriptors.reveal(map, column, row, air, visibilityMask, radiusIndex, blockers);
    }
}
