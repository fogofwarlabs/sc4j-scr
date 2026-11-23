package org.fogofwarlabs.sc4j.scr.game.production;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * The read-only result of the building placement check: the overall code the check returns (the maximum of its codes, {@link #VALID} when placement
 * is permitted) and the per-tile codes it writes to its {@code 48}-byte slot array (row stride {@code 6}). The codes select {@code stat_txt} messages
 * ({@link #getMessageId}), which the placement preview shows.
 * <p>
 * An add-on check ({@link UnitPlacement#evaluateAddon}) also carries the result of its parent building's footprint, which the preview always
 * evaluates and the other checks only when the add-on footprint itself is valid.
 */
public final class PlacementResult {
    /**
     * Placement is permitted.
     */
    public static final int VALID = 0;
    /**
     * An undetected unit is in the way (the unit pass with the undetected check).
     */
    public static final int UNDETECTED = 1;
    /**
     * A resource depot would be within {@code 3} tiles of a mineral field or geyser.
     */
    public static final int NEAR_RESOURCES = 2;
    /**
     * The footprint leaves the map.
     */
    public static final int OFF_MAP = 3;
    /**
     * Unbuildable terrain, an occupied tile or a unit is in the way.
     */
    public static final int BLOCKED = 4;
    /**
     * A tile lacks creep.
     */
    public static final int NO_CREEP = 5;
    /**
     * The site is outside the owner's psi fields.
     */
    public static final int NO_POWER = 6;
    /**
     * The builder cannot reach the site.
     */
    public static final int UNREACHABLE = 7;
    /**
     * No Vespene Geyser lies under a refinery.
     */
    public static final int NO_GEYSER = 8;
    /**
     * A Nydus exit tile is not visible (remembered-state checks only).
     */
    public static final int NOT_VISIBLE = 9;
    /**
     * A tile is unexplored.
     */
    public static final int UNEXPLORED = 10;
    /**
     * The row stride of the per-tile code array.
     */
    public static final int ROW_STRIDE = 6;
    /**
     * The length of the per-tile code array.
     */
    public static final int TILE_CODE_COUNT = 48;
    /**
     * The WORD table of the {@code stat_txt} string of each code.
     */
    private static final int @NotNull [] MESSAGE_IDS = {0, 853, 852, 854, 856, 860, 861, 862, 859, 858, 857, 855};

    /**
     * Remembered images that the remembered-state check of the placement preview consults: the resource images keep a resource depot away on
     * every tile, and a remembered Vespene Geyser lets a gas building be placed over fogged tiles.
     */
    public interface Memory {
        /**
         * Tests whether the placement box of a remembered resource, centered on its image, lies within the distance of a tile.
         *
         * @param tileX    the tile column
         * @param tileY    the tile row
         * @param distance the distance in tiles around the placement box
         * @return {@code true} if a remembered resource's placement box lies within {@code distance} tiles of the tile, {@code false} otherwise
         */
        boolean hasResourceNear(int tileX, int tileY, int distance);

        /**
         * Tests whether the first remembered Vespene Geyser image whose placement box covers a position is centered at that position.
         *
         * @param positionX the geyser center X
         * @param positionY the geyser center Y
         * @return {@code true} if that remembered Vespene Geyser image is centered at the position, {@code false} otherwise
         */
        boolean hasGeyserAt(int positionX, int positionY);
    }

    /**
     * @param code a placement code
     * @return the {@code stat_txt} string index of the code's message, or {@code 0} for {@link #VALID} and unknown codes
     */
    public static int getMessageId(int code) {
        return code >= 0 && code < MESSAGE_IDS.length ? MESSAGE_IDS[code] : 0;
    }


    private final int code;

    private final int tileX;
    private final int tileY;
    private final int tileWidth;
    private final int tileHeight;
    private final byte @NotNull [] tileCodes;

    private final @Nullable PlacementResult parent;

    /**
     * Creates a {@link PlacementResult} instance.
     *
     * @param code       the code of this footprint alone
     * @param tileX      the left footprint tile
     * @param tileY      the top footprint tile
     * @param tileWidth  the footprint width in tiles
     * @param tileHeight the footprint height in tiles
     * @param tileCodes  the per-tile code array, copied
     * @param parent     the parent building's footprint result of an add-on check, or {@code null}
     */
    PlacementResult(int code, int tileX, int tileY, int tileWidth, int tileHeight, byte @NotNull [] tileCodes, @Nullable PlacementResult parent) {
        this.code = code;

        this.tileX      = tileX;
        this.tileY      = tileY;
        this.tileWidth  = tileWidth;
        this.tileHeight = tileHeight;
        this.tileCodes  = tileCodes.clone();

        this.parent = parent;
    }

    /**
     * @return the overall code: the add-on's code, or its parent's when the add-on footprint is valid
     */
    public int getCode() {
        if (this.code == VALID && this.parent != null) {
            return this.parent.getCode();
        }

        return this.code;
    }

    /**
     * @return the code of this footprint alone
     */
    public int getFootprintCode() {
        return this.code;
    }

    /**
     * @return {@code true} if placement is permitted, {@code false} otherwise
     */
    public boolean isValid() {
        return this.getCode() == VALID;
    }

    /**
     * @return the left footprint tile
     */
    public int getTileX() {
        return this.tileX;
    }

    /**
     * @return the top footprint tile
     */
    public int getTileY() {
        return this.tileY;
    }

    /**
     * @return the footprint width in tiles
     */
    public int getTileWidth() {
        return this.tileWidth;
    }

    /**
     * @return the footprint height in tiles
     */
    public int getTileHeight() {
        return this.tileHeight;
    }

    /**
     * @param column the column within the footprint
     * @param row    the row within the footprint
     * @return the tile's code
     * @throws IndexOutOfBoundsException if the tile is outside the code array
     */
    public int getTileCode(int column, int row) {
        if (column < 0 || column >= ROW_STRIDE || row < 0 || row * ROW_STRIDE + column >= TILE_CODE_COUNT) {
            throw Exceptions.newIndexOutOfBoundsException("Placement tile {0},{1} is outside the code array.", column, row);
        }

        return this.tileCodes[row * ROW_STRIDE + column];
    }

    /**
     * @return a copy of the per-tile code array (row stride {@link #ROW_STRIDE})
     */
    public byte @NotNull [] getTileCodes() {
        return this.tileCodes.clone();
    }

    /**
     * @return the parent building's footprint result of an add-on check, or {@code null} when the add-on footprint was invalid outside remembered state or the check was not an add-on check
     */
    public @Nullable PlacementResult getParent() {
        return this.parent;
    }


    /**
     * {@return a string representation of the object}
     * <p>
     * Satisfying this method's contract implies a non-{@code null} result must be returned.
     *
     * @apiNote In general, the {@code toString} method returns a string that "textually represents" this object. The result should be a concise but
     * informative representation that is easy for a person to read. It is recommended that all subclasses override this method. The string
     * output is not necessarily stable over time or across JVM invocations.
     * @implSpec Returns the result code, tile coordinates and dimensions, tile codes and optional parent result.
     */
    @Override
    public @NotNull String toString() {
        return "PlacementResult[code=" + this.getCode() + ", tile=" + this.tileX + "," + this.tileY + ", size=" + this.tileWidth + "x" +
               this.tileHeight + ", tiles=" + Arrays.toString(this.tileCodes) + (this.parent == null ? "" : ", parent=" + this.parent) + "]";
    }
}