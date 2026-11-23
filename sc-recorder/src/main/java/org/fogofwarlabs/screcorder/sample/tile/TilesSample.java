package org.fogofwarlabs.screcorder.sample.tile;

import org.fogofwarlabs.screcorder.exception.Exceptions;
import org.fogofwarlabs.screcorder.sample.Sample;
import org.jetbrains.annotations.NotNull;

/**
 * Contains an immutable copy of the map tile identifiers, in row-major order with the map width as the row length.
 */
public final class TilesSample implements Sample {
    /**
     * The largest map width or height in tiles.
     */
    public static final int MAXIMUM_DIMENSION = 256;

    private final int width;
    private final int height;
    private final short @NotNull [] tiles;

    /**
     * Creates a {@link TilesSample} instance.
     *
     * @param width  the map width in tiles
     * @param height the map height in tiles
     * @param tiles  the row-major tile identifier words, copied on construction
     * @throws IllegalArgumentException if a dimension is outside {@code 1} through {@link #MAXIMUM_DIMENSION} or {@code tiles} does not hold {@code width * height} words
     */
    public TilesSample(int width, int height, short @NotNull [] tiles) {
        if (width < 1 || width > MAXIMUM_DIMENSION || height < 1 || height > MAXIMUM_DIMENSION || tiles.length != width * height) {
            throw Exceptions.newIllegalArgumentException("Invalid map tiles: {0} x {1}, {2} words", width, height, tiles.length);
        }

        this.width  = width;
        this.height = height;
        this.tiles  = tiles.clone();
    }


    /**
     * @return the map width in tiles
     */
    public int getWidth() {
        return this.width;
    }

    /**
     * @return the map height in tiles
     */
    public int getHeight() {
        return this.height;
    }

    /**
     * @return a detached copy of the row-major tile identifier words
     */
    public short @NotNull [] getTiles() {
        return this.tiles.clone();
    }
}
