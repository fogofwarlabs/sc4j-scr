package org.fogofwarlabs.sc4j.scr.game.terrain;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.FoliageAnimation;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.FoliageDefinition;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.FoliageDistribution;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Stores the foliage identifiers, anchors and flags used during unit creation.
 * <p>
 * The grid remains cleared through map object creation and is populated before starting units. Population uses a local random generator. Rendering
 * floats and image state are unavailable; loaded render bytes are retained for saving.
 */
public final class Foliage {
    /**
     * The number of cells of the saved foliage grid.
     */
    public static final int SAVED_CELL_COUNT = GameMap.MAXIMUM_TILE_COUNT;
    /**
     * The size of a saved foliage cell.
     */
    public static final int SAVED_CELL_SIZE = 24;

    /**
     * The cell's foliage animation is active (rendering; its frame state is not represented).
     */
    private static final int FLAG_ANIMATED = 0x1;
    /**
     * The cell anchors a placed definition.
     */
    private static final int FLAG_ANCHOR = 0x2;
    /**
     * The cell is covered by a placed footprint or a resource.
     */
    private static final int FLAG_BLOCKED = 0x4;
    /**
     * The retained flag drawn with the definition's flag probability; its rendering interpretation is not represented.
     */
    private static final int FLAG_VARIANT = 0x8;
    /**
     * The cell belongs to a footprint anchored elsewhere ({@code anchors} holds the anchor cell).
     */
    private static final int FLAG_ALIAS = 0x10;
    /**
     * The pixel size of one tile, as a float factor of the definition positions.
     */
    private static final float TILE_SIZE = TerrainTileset.TILE_SIZE;
    /**
     * The number of cells of the grid allocation.
     */
    private static final int CELL_COUNT = GameMap.MAXIMUM_TILE_COUNT;

    /**
     * The largest unsigned WORD.
     */
    private static final int WORD_MAXIMUM = 0xFFFF;
    /**
     * The bits of a WORD.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The largest footprint width and height of a definition the population represents.
     */
    private static final int MAXIMUM_FOOTPRINT_EXTENT = 256;
    /**
     * The smallest footprint width and height of a definition the population represents.
     */
    private static final int MINIMUM_FOOTPRINT_EXTENT = 1;
    /**
     * The positions of the width and the height in a frame extent ({@link FoliageAnimation#readFrameExtent}).
     */
    private static final int FRAME_EXTENT_WIDTH = 0;
    private static final int FRAME_EXTENT_HEIGHT = 1;
    /**
     * The sides of each scanned row the exclusion radius extends to: left and right.
     */
    private static final int EXCLUSION_RADIUS_SIDES = 2;
    /**
     * The largest definition extent the resource footprint iteration represents.
     */
    private static final int MAXIMUM_ITERATED_EXTENT = 65536;
    /**
     * The tile variant field of a tile identifier and the tiles of a CV5 group.
     */
    private static final int TILE_VARIANT_MASK = 0xF;
    private static final int GROUP_TILES = 16;
    /**
     * The divisor of a distribution probability.
     */
    private static final float PERCENT = 100.0F;

    /**
     * The shifts of the local xorshift generator.
     */
    private static final int XORSHIFT_FIRST_LEFT = 13;
    private static final int XORSHIFT_RIGHT = 17;
    private static final int XORSHIFT_SECOND_LEFT = 5;
    /**
     * The generator state that replaces a master seed of {@code 0}, from which the xorshift generator never leaves.
     */
    private static final int ZERO_SEED_STATE = 1;
    /**
     * The range and the divisor of a uniform draw in {@code (0, 1]}.
     */
    private static final long UNIFORM_RANGE = 0xFFFFFFFFL;
    private static final double UNIFORM_DIVISOR = 4294967295.0;
    /**
     * The float magnitude from which a truncation leaves the {@code int} range.
     */
    private static final float INT_RANGE_LIMIT = 0x1.0p31F;

    /**
     * The offset of the identifier WORD of a cell in its saved record.
     */
    private static final int SAVED_IDENTIFIER = 0x00;
    /**
     * The offset of the anchor WORD of a cell in its saved record.
     */
    private static final int SAVED_ANCHOR = 0x02;
    /**
     * The offset of the flag WORD of a cell in its saved record.
     */
    private static final int SAVED_FLAGS = 0x04;
    /**
     * The offset of the renderer's state of a cell in its saved record.
     */
    private static final int SAVED_RENDER_STATE = 0x06;
    /**
     * The size of the renderer's state of a saved cell.
     */
    private static final int SAVED_RENDER_STATE_SIZE = SAVED_CELL_SIZE - SAVED_RENDER_STATE;
    /**
     * The offset of the animation timestamp DWORD of a cell in its saved render state.
     */
    private static final int SAVED_ANIMATION_TIMESTAMP = 0x14 - SAVED_RENDER_STATE;

    /**
     * The multiplier of the simulation hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    private static int truncateFloat(float value) {
        return !Float.isFinite(value) || value >= INT_RANGE_LIMIT || value < -INT_RANGE_LIMIT ? Integer.MIN_VALUE : (int) value;
    }

    private final int mapWidth;
    private final @NotNull FoliageDefinition @NotNull [] definitions;

    private short @Nullable [] terrainMegatiles;
    private @Nullable FoliageDistribution distribution;
    private int @Nullable [] frameWidths;
    private int @Nullable [] frameHeights;

    private final short @NotNull [] identifiers;
    private final short @NotNull [] anchors;
    private final short @NotNull [] flags;
    private int gridWidth;
    private int gridHeight;
    /**
     * The renderer's part of each saved cell (animation frame WORDs, scaled float timers and a DWORD), which the simulation does not keep: kept from
     * a loaded saved game so that saving it again writes the same bytes, {@code null} before.
     */
    private byte @Nullable [] savedRenderState;

    private int randomState;
    private boolean gridPopulated;
    private boolean initializationActive;

    /**
     * Creates a {@link Foliage} instance with a cleared grid after the foliage definitions load.
     *
     * @param mapWidth           the game context's unsigned WORD map width
     * @param foliageDefinitions the loaded {@code tileset/foliage.bin} records
     * @throws IllegalArgumentException if the map width does not fit a WORD
     */
    public Foliage(int mapWidth, byte @NotNull [] foliageDefinitions) {
        if (mapWidth < 0 || mapWidth > WORD_MAXIMUM) {
            throw Exceptions.newIllegalArgumentException("The native map width must fit its WORD storage.");
        }

        this.mapWidth    = mapWidth;
        this.definitions = FoliageDefinition.parseAll(foliageDefinitions);

        this.terrainMegatiles = null;
        this.distribution     = null;
        this.frameWidths      = null;
        this.frameHeights     = null;

        this.identifiers      = new short[CELL_COUNT];
        this.anchors          = new short[CELL_COUNT];
        this.flags            = new short[CELL_COUNT];
        this.gridWidth        = 0;
        this.gridHeight       = 0;
        this.savedRenderState = null;

        this.randomState          = 0;
        this.gridPopulated        = false;
        this.initializationActive = true;
    }

    /**
     * @return {@code true} if procedural identifiers, anchors and flags have been populated, {@code false} otherwise
     */
    public boolean isGridPopulated() {
        return this.gridPopulated;
    }

    /**
     * @return {@code true} if rendering floats and image state are available, {@code false} otherwise; always {@code false} because they are not represented
     */
    public boolean hasRenderingState() {
        return false;
    }

    /**
     * @return {@code true} if the represented unit-creation phase remains active, {@code false} otherwise
     */
    public boolean isInitializationActive() {
        return this.initializationActive;
    }

    /**
     * @return the grid width the saved foliage header keeps
     */
    public int getGridWidth() {
        return this.gridWidth;
    }

    /**
     * @return the grid height the saved foliage header keeps
     */
    public int getGridHeight() {
        return this.gridHeight;
    }

    /**
     * @param tileIndex the cell index
     * @return the stored unsigned definition identifier
     */
    public int getIdentifier(int tileIndex) {
        return Short.toUnsignedInt(this.identifiers[tileIndex]);
    }

    /**
     * @param tileIndex the cell index
     * @return the stored unsigned anchor index
     */
    public int getAnchor(int tileIndex) {
        return Short.toUnsignedInt(this.anchors[tileIndex]);
    }

    /**
     * @param tileIndex the index within the original {@code 65536}-cell allocation
     * @return the unsigned flag WORD of the cell
     */
    public int getFlags(int tileIndex) {
        return Short.toUnsignedInt(this.flags[tileIndex]);
    }


    /**
     * Adds the installed SD population inputs to a freshly cleared context.
     *
     * @param distribution  the tileset's FOL records
     * @param animation     the selected SD foliage ANIM file
     * @param terrainGroups the tileset's CV5 records
     * @throws IllegalStateException         if the context is not fresh
     * @throws IllegalArgumentException      if the CV5 data is incomplete or a definition references an absent animation
     * @throws UnsupportedOperationException if a definition has no integral footprint extent
     */
    public void loadPopulationData(byte @NotNull [] distribution, byte @NotNull [] animation, byte @NotNull [] terrainGroups) {
        if (!this.initializationActive || this.gridPopulated || this.distribution != null) {
            throw Exceptions.newIllegalStateException("Foliage population inputs require a fresh context.");
        }

        if (terrainGroups.length == 0 || terrainGroups.length % TerrainTileset.CV5_RECORD_SIZE != 0) {
            throw Exceptions.newIllegalArgumentException("Incomplete foliage distribution or CV5 data.");
        }

        FoliageDistribution parsedDistribution = FoliageDistribution.parse(distribution, this.definitions.length);
        FoliageAnimation frames = FoliageAnimation.parse(animation);
        int[] frameWidths = new int[this.definitions.length];
        int[] frameHeights = new int[this.definitions.length];

        for (int definitionIndex = 0; definitionIndex < this.definitions.length; definitionIndex++) {
            FoliageDefinition definition = this.definitions[definitionIndex];
            float width = definition.getFootprintWidth();
            float height = definition.getFootprintHeight();

            if (!(width >= MINIMUM_FOOTPRINT_EXTENT && width <= MAXIMUM_FOOTPRINT_EXTENT && width == (int) width &&
                  height >= MINIMUM_FOOTPRINT_EXTENT && height <= MAXIMUM_FOOTPRINT_EXTENT && height == (int) height)) {
                throw Exceptions.newUnsupportedOperationException("Population requires the established integral foliage extents.");
            }

            int animationIndex = definition.getAnimationIndex();

            if (animationIndex < 0 || animationIndex >= frames.getAnimationCount()) {
                throw Exceptions.newIllegalArgumentException("The foliage definition references an absent animation.");
            }

            int[] extent = frames.readFrameExtent(animationIndex, definition.getFrameIndex());

            frameWidths[definitionIndex]  = extent[FRAME_EXTENT_WIDTH];
            frameHeights[definitionIndex] = extent[FRAME_EXTENT_HEIGHT];
        }

        this.frameWidths      = frameWidths;
        this.frameHeights     = frameHeights;
        this.distribution     = parsedDistribution;
        this.terrainMegatiles = TerrainTileset.readMegatileIdentifiers(terrainGroups);
    }

    /**
     * Populates the simulation fields after map units and before starting units. The two INI distribution settings affect excluded rendering floats
     * only.
     *
     * @param map        the normalized terrain owned by the world
     * @param masterSeed the master seed read by the local foliage generator
     * @throws IllegalStateException    if the context is not in the prepopulation phase or its inputs are missing
     * @throws IllegalArgumentException if the map does not fit the grid or a tile references absent CV5 memory
     */
    public void populate(@NotNull GameMap map, int masterSeed) {
        int mapHeight = map.getHeight();

        if (!this.initializationActive || this.gridPopulated) {
            throw Exceptions.newIllegalStateException("Native foliage population requires the prepopulation phase.");
        }

        if (this.distribution == null || this.terrainMegatiles == null || this.frameWidths == null || this.frameHeights == null) {
            throw Exceptions.newIllegalStateException("Native foliage population inputs have not been loaded.");
        }

        if (this.mapWidth == 0 || mapHeight < 0 || mapHeight > WORD_MAXIMUM ||
            (long) this.mapWidth * mapHeight > CELL_COUNT || map.getTileCount() != this.mapWidth * mapHeight) {
            throw Exceptions.newIllegalArgumentException("The foliage map dimensions exceed the represented grid.");
        }

        int[] megatiles = new int[map.getTileCount()];

        for (int tileIndex = 0; tileIndex < map.getTileCount(); tileIndex++) {
            int tileIdentifier = map.getTileIdentifier(tileIndex);
            int terrainIndex = TerrainTileset.getGroupIndex(tileIdentifier) * GROUP_TILES + (tileIdentifier & TILE_VARIANT_MASK);

            if (tileIdentifier < 0 || tileIdentifier > WORD_MAXIMUM || terrainIndex >= this.terrainMegatiles.length) {
                throw Exceptions.newIllegalArgumentException("The foliage tile references absent CV5 memory.");
            }

            megatiles[tileIndex] = Short.toUnsignedInt(this.terrainMegatiles[terrainIndex]);
        }

        this.gridWidth   = this.mapWidth;
        this.gridHeight  = mapHeight;
        this.randomState = masterSeed == 0 ? ZERO_SEED_STATE : masterSeed;

        int definitionCount = this.definitions.length;
        FoliageDistribution distribution = this.distribution;
        int[] candidates = new int[definitionCount];

        for (int tileIndex = 0; tileIndex < megatiles.length; tileIndex++) {
            int megatile = megatiles[tileIndex];

            if (megatile >= distribution.getRecordCount() || (this.flags[tileIndex] & FLAG_ANCHOR) != 0) {
                continue;
            }

            if (!distribution.hasSelection(megatile) || !(distribution.getProbability(megatile) / PERCENT >= this.nextUniform())) {
                continue;
            }

            this.nextUniform();

            int candidateCount = 0;

            candidateSelection:
            for (int definitionIndex = 0; definitionIndex < definitionCount; definitionIndex++) {
                if (!distribution.isSelectable(megatile, definitionIndex) || this.definitions[definitionIndex].getRandomSelectionRange() == 0) {
                    continue;
                }

                int width = (int) this.definitions[definitionIndex].getFootprintWidth();
                int height = (int) this.definitions[definitionIndex].getFootprintHeight();
                int left = tileIndex % this.gridWidth;
                int upper = tileIndex / this.gridWidth;

                if (left + width > this.gridWidth || upper + height > this.gridHeight) {
                    continue;
                }

                for (int row = 0; row < height; row++) {
                    for (int column = 0; column < width; column++) {
                        int affectedIndex = tileIndex + row * this.gridWidth + column;
                        int candidateMegatile = megatiles[affectedIndex];

                        if ((this.flags[affectedIndex] & (FLAG_ANCHOR | FLAG_BLOCKED)) != 0 ||
                            candidateMegatile >= distribution.getRecordCount() ||
                            !distribution.isSelectable(candidateMegatile, definitionIndex)) {
                            continue candidateSelection;
                        }
                    }
                }

                int radius = this.definitions[definitionIndex].getExclusionRadius();
                int minimumX = Math.max(0, left - radius);
                int maximumX = minimumX + width + 1 + EXCLUSION_RADIUS_SIDES * radius;

                for (int row = Math.max(0, upper - radius); row < upper + 1; row++) {
                    for (int column = minimumX; column < Math.min(maximumX, this.gridWidth); column++) {
                        int affectedIndex = row * this.gridWidth + column;

                        if ((this.flags[affectedIndex] & FLAG_ANCHOR) != 0 &&
                            Short.toUnsignedInt(this.identifiers[affectedIndex]) == definitionIndex) {
                            continue candidateSelection;
                        }
                    }
                }

                candidates[candidateCount++] = definitionIndex;
            }

            if (candidateCount == 0) {
                continue;
            }

            int definitionIndex = candidates[this.nextBounded(candidateCount)];
            FoliageDefinition definition = this.definitions[definitionIndex];

            this.identifiers[tileIndex] = (short) definitionIndex;
            this.anchors[tileIndex]     = (short) tileIndex;

            float scale = (definition.getMaximumScale() - definition.getMinimumScale()) * this.nextUniform() + definition.getMinimumScale();

            this.flags[tileIndex] = (short) (definition.getFlagProbability() >= this.nextUniform()
                                             ? this.flags[tileIndex] | FLAG_VARIANT
                                             : this.flags[tileIndex] & (WORD_MASK & ~FLAG_VARIANT));
            this.nextBounded(definition.getRandomSelectionRange() & WORD_MASK);

            int imageWidth = truncateFloat(this.frameWidths[definitionIndex] * scale);
            int imageHeight = truncateFloat(this.frameHeights[definitionIndex] * scale);
            int minimumX = truncateFloat(definition.getMinimumHorizontalPosition() * TILE_SIZE);
            int maximumX = truncateFloat(Math.max((float) minimumX,
                                                  definition.getFootprintWidth() * TILE_SIZE * definition.getMaximumHorizontalFactor() - imageWidth));

            this.nextBounded((maximumX - minimumX + 1) & WORD_MASK);

            int minimumY = truncateFloat(definition.getMinimumVerticalPosition() * TILE_SIZE);
            int maximumY = truncateFloat(Math.max((float) minimumY,
                                                  definition.getFootprintHeight() * TILE_SIZE * definition.getMaximumVerticalFactor() - imageHeight));

            this.nextBounded((maximumY - minimumY + 1) & WORD_MASK);

            int width = (int) definition.getFootprintWidth();
            int height = (int) definition.getFootprintHeight();

            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    if (row == 0 && column == 0) {
                        continue;
                    }

                    int affectedIndex = tileIndex + row * this.gridWidth + column;

                    this.anchors[affectedIndex] = (short) tileIndex;
                    this.flags[affectedIndex]   = (short) ((this.flags[affectedIndex] | FLAG_ALIAS) & (WORD_MASK & ~FLAG_ANCHOR) | FLAG_BLOCKED);
                }
            }

            this.flags[tileIndex] |= FLAG_ANCHOR;
        }

        this.randomState   = 0;
        this.gridPopulated = true;
    }

    private int nextRandom() {
        int previous = this.randomState;
        int next = previous ^ (previous << XORSHIFT_FIRST_LEFT);

        next ^= next >>> XORSHIFT_RIGHT;
        next ^= next << XORSHIFT_SECOND_LEFT;
        this.randomState = next;

        return previous + next;
    }

    private float nextUniform() {
        return (float) ((Integer.toUnsignedLong(this.nextRandom()) % UNIFORM_RANGE + 1) / UNIFORM_DIVISOR);
    }

    private int nextBounded(int count) {
        if (count == 1) {
            return 0;
        }

        int value = this.nextRandom() & WORD_MASK;

        return count == 0 ? value : value % count;
    }


    /**
     * Applies the inactive-animation path after population. Population sets occupancy bits without enabling the rendering flag. A cell with that flag
     * cannot be updated without its animation state.
     *
     * @throws IllegalStateException         if the grid has not been populated
     * @throws UnsupportedOperationException if a rendering-activated tile requires its animation fields
     */
    public void updateInactiveAnimations() {
        if (!this.gridPopulated) {
            throw Exceptions.newIllegalStateException("Native foliage animation requires the populated grid.");
        }

        for (int tileIndex = 0; tileIndex < this.gridWidth * this.gridHeight; tileIndex++) {
            if ((this.flags[tileIndex] & FLAG_ANIMATED) != 0) {
                throw Exceptions.newUnsupportedOperationException(
                        "Native active foliage animation requires its retained frame and timestamp fields."
                );
            }
        }
    }


    /**
     * Ends the represented phase before later gameplay and rendering updates.
     */
    public void finishInitialization() {
        this.initializationActive = false;
    }

    /**
     * Marks a resource's tile footprint blocked and not animated.
     * <p>
     * Before population, identifiers of {@code 0} select definition {@code 0} and a grid width of {@code 0} repeats identical rows. After population,
     * alias cells resolve their anchor. Floating comparisons skip zero, negative and NaN definition extents. Registration remains available after
     * initialization, including for a geyser restored from a destroyed refinery.
     *
     * @param left   the original DWORD left tile coordinate
     * @param upper  the original DWORD upper tile coordinate
     * @param width  the original unsigned DWORD footprint width
     * @param height the original unsigned DWORD footprint height
     * @throws UnsupportedOperationException if the footprint or a definition extent exceeds the represented storage
     */
    public void markResourceFootprint(int left, int upper, int width, int height) {
        if (height == 0 || width == 0) {
            return;
        }

        if (height < 0 || width < 0) {
            throw Exceptions.newUnsupportedOperationException("The native unsigned footprint exceeds represented tile memory.");
        }

        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int tileIndex = this.mapWidth * (upper + row) + column + left;

                if (Integer.compareUnsigned(tileIndex, this.flags.length) >= 0) {
                    throw Exceptions.newUnsupportedOperationException("The native foliage lookup exceeds represented tile memory.");
                }

                if ((this.flags[tileIndex] & FLAG_ALIAS) != 0) {
                    tileIndex = Short.toUnsignedInt(this.anchors[tileIndex]);
                }

                int definitionIndex = Short.toUnsignedInt(this.identifiers[tileIndex]);
                float definitionWidth = this.definitions[definitionIndex].getFootprintWidth();
                float definitionHeight = this.definitions[definitionIndex].getFootprintHeight();

                if (!(definitionHeight > 0) || !(definitionWidth > 0)) {
                    continue;
                }

                if (!Float.isFinite(definitionHeight) || definitionHeight > MAXIMUM_ITERATED_EXTENT ||
                    !Float.isFinite(definitionWidth) || definitionWidth > MAXIMUM_ITERATED_EXTENT) {
                    throw Exceptions.newUnsupportedOperationException("The native foliage extent exceeds the represented iteration bound.");
                }

                int rowCount = this.gridWidth == 0 ? 1 : (int) Math.ceil(definitionHeight);

                for (int definitionRow = 0; definitionRow < rowCount; definitionRow++) {
                    for (int definitionColumn = 0; definitionColumn < definitionWidth; definitionColumn++) {
                        int affectedIndex = tileIndex + definitionRow * this.gridWidth + definitionColumn;

                        if (Integer.compareUnsigned(affectedIndex, this.flags.length) >= 0) {
                            throw Exceptions.newUnsupportedOperationException("The native foliage write exceeds represented tile memory.");
                        }

                        this.flags[affectedIndex] = (short) ((this.flags[affectedIndex] & (WORD_MASK & ~FLAG_ANIMATED)) | FLAG_BLOCKED);
                    }
                }
            }
        }
    }


    /**
     * Writes one cell into its saved {@code 24}-byte record of the foliage part: the identifier, anchor and flag WORDs, then the renderer's state
     * read from a saved game (zero for a game that was never loaded).
     *
     * @param cell   the cell index
     * @param record the record, little-endian, at offset {@code 0}
     */
    public void writeSaveCell(int cell, @NotNull ByteBuffer record) {
        record.putShort(SAVED_IDENTIFIER, this.identifiers[cell]);
        record.putShort(SAVED_ANCHOR, this.anchors[cell]);
        record.putShort(SAVED_FLAGS, this.flags[cell]);

        if (this.savedRenderState != null) {
            record.put(SAVED_RENDER_STATE, this.savedRenderState, cell * SAVED_RENDER_STATE_SIZE, SAVED_RENDER_STATE_SIZE);
        }
    }

    /**
     * Reads one cell from its saved record ({@link #writeSaveCell}).
     *
     * @param cell   the cell index
     * @param record the record, little-endian, at offset {@code 0}
     */
    public void readSaveCell(int cell, @NotNull ByteBuffer record) {
        if (this.savedRenderState == null) {
            this.savedRenderState = new byte[CELL_COUNT * SAVED_RENDER_STATE_SIZE];
        }

        this.identifiers[cell] = record.getShort(SAVED_IDENTIFIER);
        this.anchors[cell]     = record.getShort(SAVED_ANCHOR);
        this.flags[cell]       = record.getShort(SAVED_FLAGS);

        record.get(SAVED_RENDER_STATE, this.savedRenderState, cell * SAVED_RENDER_STATE_SIZE, SAVED_RENDER_STATE_SIZE);
    }

    /**
     * Restores the grid size of a saved game's foliage header; the grid counts as populated.
     *
     * @param gridWidth  the grid width
     * @param gridHeight the grid height
     */
    public void restoreSavedGrid(int gridWidth, int gridHeight) {
        this.gridWidth     = gridWidth;
        this.gridHeight    = gridHeight;
        this.gridPopulated = true;
    }

    /**
     * Clears the restored grid's animation flags and retained animation timestamps because foliage is not rendered.
     */
    public void settleSavedAnimations() {
        for (int cell = 0; cell < this.gridWidth * this.gridHeight; cell++) {
            this.flags[cell] &= (short) ~FLAG_ANIMATED;

            if (this.savedRenderState != null) {
                Arrays.fill(this.savedRenderState,
                            cell * SAVED_RENDER_STATE_SIZE + SAVED_ANIMATION_TIMESTAMP,
                            cell * SAVED_RENDER_STATE_SIZE + SAVED_ANIMATION_TIMESTAMP + Integer.BYTES,
                            (byte) 0);
            }
        }
    }


    /**
     * Mixes the definitions, the population inputs, the stored cells and the phase into a simulation hash.
     *
     * @param hash the preceding simulation hash
     * @return the hash including supplied definitions, stored flags and phase
     */
    public int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + this.mapWidth;
        hash = HASH_MULTIPLIER * hash + this.randomState;
        hash = HASH_MULTIPLIER * hash + this.gridWidth;
        hash = HASH_MULTIPLIER * hash + this.gridHeight;
        hash = HASH_MULTIPLIER * hash + (this.gridPopulated ? 1 : 0);

        for (FoliageDefinition definition : this.definitions) {
            hash = HASH_MULTIPLIER * hash + Float.floatToRawIntBits(definition.getMinimumScale());
        }

        for (FoliageDefinition definition : this.definitions) {
            hash = HASH_MULTIPLIER * hash + Float.floatToRawIntBits(definition.getMaximumScale());
        }

        for (FoliageDefinition definition : this.definitions) {
            hash = HASH_MULTIPLIER * hash + Float.floatToRawIntBits(definition.getFlagProbability());
        }

        for (FoliageDefinition definition : this.definitions) {
            hash = HASH_MULTIPLIER * hash + Float.floatToRawIntBits(definition.getMinimumHorizontalPosition());
        }

        for (FoliageDefinition definition : this.definitions) {
            hash = HASH_MULTIPLIER * hash + Float.floatToRawIntBits(definition.getMaximumHorizontalFactor());
        }

        for (FoliageDefinition definition : this.definitions) {
            hash = HASH_MULTIPLIER * hash + Float.floatToRawIntBits(definition.getMinimumVerticalPosition());
        }

        for (FoliageDefinition definition : this.definitions) {
            hash = HASH_MULTIPLIER * hash + Float.floatToRawIntBits(definition.getMaximumVerticalFactor());
        }

        for (FoliageDefinition definition : this.definitions) {
            hash = HASH_MULTIPLIER * hash + Float.floatToRawIntBits(definition.getFootprintWidth());
        }

        for (FoliageDefinition definition : this.definitions) {
            hash = HASH_MULTIPLIER * hash + Float.floatToRawIntBits(definition.getFootprintHeight());
        }

        for (FoliageDefinition definition : this.definitions) {
            hash = HASH_MULTIPLIER * hash + definition.getRandomSelectionRange();
        }

        for (FoliageDefinition definition : this.definitions) {
            hash = HASH_MULTIPLIER * hash + definition.getAnimationIndex();
        }

        for (FoliageDefinition definition : this.definitions) {
            hash = HASH_MULTIPLIER * hash + definition.getFrameIndex();
        }

        for (FoliageDefinition definition : this.definitions) {
            hash = HASH_MULTIPLIER * hash + definition.getExclusionRadius();
        }

        FoliageDistribution distribution = this.distribution;

        hash = HASH_MULTIPLIER * hash + (distribution == null ? 0 : distribution.getSelectionByteCount());
        hash = HASH_MULTIPLIER * hash + (distribution == null ? 0 : distribution.getRecordCount());
        hash = HASH_MULTIPLIER * hash + (distribution == null ? 0 : 1);

        if (this.terrainMegatiles != null) {
            for (short value : this.terrainMegatiles) {
                hash = HASH_MULTIPLIER * hash + Short.toUnsignedInt(value);
            }
        }

        if (distribution != null) {
            for (int record = 0; record < distribution.getRecordCount(); record++) {
                hash = HASH_MULTIPLIER * hash + Float.floatToRawIntBits(distribution.getProbability(record));
            }

            for (int record = 0; record < distribution.getRecordCount(); record++) {
                for (int selection = 0; selection < distribution.getSelectionByteCount(); selection++) {
                    hash = HASH_MULTIPLIER * hash + Byte.toUnsignedInt(distribution.getSelectionByte(record, selection));
                }
            }

            for (int record = 0; record < distribution.getRecordCount(); record++) {
                hash = HASH_MULTIPLIER * hash + (distribution.hasSelection(record) ? 1 : 0);
            }
        }

        if (this.frameWidths != null) {
            for (int value : this.frameWidths) {
                hash = HASH_MULTIPLIER * hash + value;
            }
        }

        if (this.frameHeights != null) {
            for (int value : this.frameHeights) {
                hash = HASH_MULTIPLIER * hash + value;
            }
        }

        for (short identifier : this.identifiers) {
            hash = HASH_MULTIPLIER * hash + Short.toUnsignedInt(identifier);
        }

        for (short anchorIndex : this.anchors) {
            hash = HASH_MULTIPLIER * hash + Short.toUnsignedInt(anchorIndex);
        }

        hash = HASH_MULTIPLIER * hash + (this.initializationActive ? 1 : 0);

        for (short cellFlags : this.flags) {
            hash = HASH_MULTIPLIER * hash + Short.toUnsignedInt(cellFlags);
        }

        return hash;
    }
}
