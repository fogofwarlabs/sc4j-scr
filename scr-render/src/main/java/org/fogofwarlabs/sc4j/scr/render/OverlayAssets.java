package org.fogofwarlabs.sc4j.scr.render;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Palette;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceKeys;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ImageIds;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Holds the selection-circle and health-bar color tables of a tileset palette.
 * <p>
 * The twenty circle images start at image {@code 561}: ten solid circles {@code o022..o224} followed by ten dashed circles {@code od022..od224}.
 * <p>
 * {@code game/tselect.pcx} is a row of {@code 24} entries: three groups of eight palette indices (green, yellow, red) that replace the
 * selection-circle GRP indices {@code 0..7}. {@code game/thpbar.pcx} is the row of the {@code 19} health-bar colors. Both resolve through the tileset
 * {@code .wpe} palette.
 */
public final class OverlayAssets {
    /**
     * The first selection-circle image.
     */
    public static final int FIRST_CIRCLE_IMAGE = ImageIds.SELECTION_CIRCLE_22PIXELS;
    /**
     * The number of selection-circle images, solid and dashed.
     */
    public static final int CIRCLE_IMAGE_COUNT = 20;

    /**
     * The number of palette indices of one {@code tselect} group, which replace the circle GRP indices {@code 0..7}.
     */
    private static final int SELECTION_GROUP_SIZE = 8;
    /**
     * The {@code tselect} group entry whose color draws the whole circle.
     */
    private static final int CIRCLE_COLOR_ENTRY = 3;
    /**
     * The number of selection-circle relations and {@code tselect} groups.
     */
    private static final int RELATION_COUNT = SceneRenderer.RELATION_ENEMY + 1;
    /**
     * The number of {@code tselect} entries.
     */
    private static final int SELECTION_ENTRIES = RELATION_COUNT * SELECTION_GROUP_SIZE;
    /**
     * The number of {@code thpbar} entries.
     */
    private static final int BAR_COLOR_COUNT = 19;
    /**
     * The mask of an unsigned byte.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The red, green and blue bytes of a packed {@code 0xAARRGGBB} palette color.
     */
    private static final int RGB_MASK = 0xFFFFFF;

    private static final @NotNull String @NotNull [] TILESET_NAMES = {
            "badlands", "platform", "install", "ashworld", "jungle", "desert", "ice", "twilight"
    };

    /**
     * Creates an {@link OverlayAssets} instance with the color tables of a tileset palette.
     *
     * @param resources the resource provider of the installation
     * @param tileset   the map tileset index
     * @return the created {@link OverlayAssets} instance
     * @throws IOException if the tileset index is unsupported or a table is missing or malformed
     */
    public static @NotNull OverlayAssets load(@NotNull ScrResourceProvider resources, int tileset) throws IOException {
        if (tileset < 0 || tileset >= TILESET_NAMES.length) {
            throw Exceptions.newIOException("Unsupported tileset index: {0}", tileset);
        }

        Palette palette = resources.loadPalette(ScrResourceKeys.ofPalette("tileset/" + TILESET_NAMES[tileset] + ".wpe"));
        byte[] selection = RenderAssets.copyIndices(resources.loadSpriteSheet(ScrResourceKeys.ofPcx("game/tselect.pcx")), SELECTION_ENTRIES, 1);
        byte[] bars = RenderAssets.copyIndices(resources.loadSpriteSheet(ScrResourceKeys.ofPcx("game/thpbar.pcx")), BAR_COLOR_COUNT, 1);

        return new OverlayAssets(palette, selection, bars);
    }

    private final @NotNull Palette palette;
    private final byte @NotNull [] selectionIndices;
    private final byte @NotNull [] barIndices;

    private final int @NotNull [] barColors;
    private final int @NotNull [] relationColors;

    private OverlayAssets(@NotNull Palette palette, byte @NotNull [] selectionIndices, byte @NotNull [] barIndices) {
        this.palette          = palette;
        this.selectionIndices = selectionIndices;
        this.barIndices       = barIndices;

        this.barColors      = new int[barIndices.length];
        this.relationColors = new int[RELATION_COUNT];

        for (int index = 0; index < barIndices.length; index++) {
            this.barColors[index] = this.getPaletteColor(barIndices[index] & BYTE_MASK);
        }

        for (int relation = 0; relation < RELATION_COUNT; relation++) {
            this.relationColors[relation] = this.getSelectionColor(relation, CIRCLE_COLOR_ENTRY);
        }
    }

    /**
     * @param relation the circle group
     * @return the brightest packed {@code 0xRRGGBB} color of the group, the HD circle tint
     */
    public int getRelationColor(int relation) {
        return this.relationColors[Math.clamp(relation, SceneRenderer.RELATION_OWN, SceneRenderer.RELATION_ENEMY)];
    }

    /**
     * @param index the health-bar color index from {@code 0} through {@code 18}
     * @return the packed {@code 0xRRGGBB} color
     */
    public int getBarColor(int index) {
        return this.barColors[index];
    }

    /**
     * @param paletteIndex the tileset palette index
     * @return the packed {@code 0xRRGGBB} color
     */
    int getPaletteColor(int paletteIndex) {
        return this.palette.getColor(paletteIndex & BYTE_MASK) & RGB_MASK;
    }

    /**
     * @param relation   the circle group: {@link SceneRenderer#RELATION_OWN}, {@link SceneRenderer#RELATION_NEUTRAL} or {@link SceneRenderer#RELATION_ENEMY}
     * @param entryIndex the circle GRP palette index from {@code 0} through {@code 7}
     * @return the packed {@code 0xRRGGBB} replacement color
     */
    int getSelectionColor(int relation, int entryIndex) {
        return this.getPaletteColor(this.getSelectionPaletteIndex(relation, entryIndex & (SELECTION_GROUP_SIZE - 1)));
    }

    /**
     * @param index the health-bar color index from {@code 0} through {@code 18}
     * @return the tileset palette index of that color ({@code game/thpbar.pcx})
     */
    int getBarPaletteIndex(int index) {
        return this.barIndices[index] & BYTE_MASK;
    }

    /**
     * @param relation the circle group
     * @return the tileset palette index of the group's circle color ({@link #getRelationColor})
     */
    int getRelationPaletteIndex(int relation) {
        return this.getSelectionPaletteIndex(relation, CIRCLE_COLOR_ENTRY);
    }

    /**
     * @param relation   the circle group, clamped to the {@link SceneRenderer} relations
     * @param entryIndex the entry within the group's {@code tselect} palette indices
     * @return the tileset palette index of that entry
     */
    private int getSelectionPaletteIndex(int relation, int entryIndex) {
        int group = Math.clamp(relation, SceneRenderer.RELATION_OWN, SceneRenderer.RELATION_ENEMY);

        return this.selectionIndices[group * SELECTION_GROUP_SIZE + entryIndex] & BYTE_MASK;
    }
}
