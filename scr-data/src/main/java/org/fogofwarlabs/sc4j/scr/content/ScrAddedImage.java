package org.fogofwarlabs.sc4j.scr.content;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.type.ImageType;
import org.fogofwarlabs.sc4j.scr.content.table.ScrDatKind;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.ImagesDat;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrModGraphics;
import org.fogofwarlabs.sc4j.scr.data.type.ScrImageTypeView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Set;

/**
 * Describes an image a mod adds after the native {@code images.dat} entries ({@link ScrAddedGraphics}): its engine identity, one value per
 * {@code images.dat} field and its frames.
 * <p>
 * The values start as a copy of a native base image of the installed data (draw function, color shift, turns, script, overlay files); the mod then
 * changes the values it wants, typically the script ({@link ImagesDat#ISCRIPT}, a native or added script id). The overlay files (attack, damage,
 * special, landing and liftoff dust, shield) stay the base image's, so the frame layout of the image's frames should follow the base image's
 * frame sets where the script uses overlays, unless the image has its own overlay offsets of a table ({@link Builder#setOverlayOffsets}), which
 * the overlay opcodes then read instead of the base image's file. The frames are the mod's own files ({@link ScrModGraphics}); without them the
 * image shows the base image's installed frames.
 */
public final class ScrAddedImage {
    /**
     * The overlay file fields, which an added image keeps from its base image.
     */
    private static final @NotNull Set<String> OVERLAY_FIELDS = Set.of(ImagesDat.SHIELD_OVERLAY, ImagesDat.ATTACK_OVERLAY, ImagesDat.DAMAGE_OVERLAY,
                                                                      ImagesDat.SPECIAL_OVERLAY, ImagesDat.LANDING_DUST_OVERLAY,
                                                                      ImagesDat.LIFTOFF_DUST_OVERLAY);

    /**
     * Builds {@link ScrAddedImage} instances.
     */
    public static final class Builder {
        private final @NotNull ImageType type;
        private final int baseImage;

        private final @NotNull ScrAddedDatValues values;

        private @Nullable ScrModGraphics graphics;
        private final int @NotNull [] @Nullable [] @NotNull [] overlayOffsets;

        /**
         * Creates a {@link Builder} instance whose values are those of a native base image and that shows the base image's frames.
         *
         * @param type      the engine identity of the added image, not registered anywhere yet
         * @param baseImage the native {@code images.dat} entry whose values are copied
         * @param images    the installed {@code images.dat} table the values are read from
         * @throws IllegalArgumentException if the base image is not a native entry
         */
        public Builder(@NotNull ImageType type, int baseImage, @NotNull DatTable images) {
            this.type      = type;
            this.baseImage = baseImage;

            this.values = new ScrAddedDatValues(ScrDatKind.IMAGES, images, baseImage, ImagesDat.IMAGE_COUNT);

            this.graphics       = null;
            this.overlayOffsets = new int[OVERLAY_TABLE_COUNT][][];
        }

        /**
         * Sets the value of an {@code images.dat} field other than the overlay files; a script id names a native or added script.
         *
         * @param field the {@code images.dat} field name ({@link ImagesDat})
         * @param value the value, kept as given
         * @return this instance for chaining
         * @throws IllegalArgumentException if the field does not exist or is an overlay file
         */
        public @NotNull Builder setValue(@NotNull String field, int value) {
            if (OVERLAY_FIELDS.contains(field)) {
                throw Exceptions.newIllegalArgumentException("The overlay files of an added image are its base image's: {0}", field);
            }

            this.values.set(field, value);

            return this;
        }

        /**
         * Sets the frames of the image.
         *
         * @param graphics the frame files of the mod, or {@code null} for the base image's installed frames
         * @return this instance for chaining
         */
        public @NotNull Builder setGraphics(@Nullable ScrModGraphics graphics) {
            this.graphics = graphics;

            return this;
        }

        /**
         * Sets the image's own offsets of an overlay table, which the overlay opcodes of the table ({@code imgoluselo} and the others) read
         * instead of the base image's overlay file: per frame of the image's frames (the frame set's first frame plus the direction, as an
         * installed overlay file counts them), the positions of the table, each a pair of signed pixel offsets from the image position, x right
         * and y down. A horizontally flipped image negates the x offset, as for an installed file.
         *
         * @param overlayType the overlay table, {@link ScrImageTypeView#OVERLAY_ATTACK} through {@link ScrImageTypeView#OVERLAY_SHIELD}
         * @param offsets     per frame, the x and y offsets of each position in turn; every frame holds the same number of positions
         * @return this instance for chaining
         * @throws IllegalArgumentException if the table is unknown, there is no frame or position, the frames hold different numbers of values or an offset is outside a signed WORD
         */
        public @NotNull Builder setOverlayOffsets(int overlayType, int @NotNull [] @NotNull [] offsets) {
            if (overlayType < ScrImageTypeView.OVERLAY_ATTACK || overlayType > ScrImageTypeView.OVERLAY_SHIELD) {
                throw Exceptions.newIllegalArgumentException("Unsupported overlay table: {0}", overlayType);
            }

            if (offsets.length == 0 || offsets[0].length == 0 || offsets[0].length % POSITION_VALUES != 0) {
                throw Exceptions.newIllegalArgumentException("The overlay offsets need frames of position pairs.");
            }

            int[][] copy = new int[offsets.length][];

            for (int frame = 0; frame < offsets.length; frame++) {
                if (offsets[frame].length != offsets[0].length) {
                    throw Exceptions.newIllegalArgumentException("Overlay frame {0} holds {1} values, frame 0 {2}.", frame, offsets[frame].length,
                                                                 offsets[0].length);
                }

                for (int value : offsets[frame]) {
                    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                        throw Exceptions.newIllegalArgumentException("Overlay offset {0} of frame {1} is outside a signed WORD.", value, frame);
                    }
                }

                copy[frame] = offsets[frame].clone();
            }

            this.overlayOffsets[overlayType] = copy;

            return this;
        }

        /**
         * Builds a {@link ScrAddedImage} instance with the current values.
         *
         * @return the built {@link ScrAddedImage} instance
         */
        public @NotNull ScrAddedImage build() {
            return new ScrAddedImage(this);
        }
    }

    /**
     * The number of overlay tables, attack through shield.
     */
    private static final int OVERLAY_TABLE_COUNT = ScrImageTypeView.OVERLAY_SHIELD + 1;
    /**
     * The values of one overlay position: x and y.
     */
    private static final int POSITION_VALUES = 2;
    private static final int WORD_MASK = 0xFFFF;

    private final @NotNull ImageType type;
    private final int baseImage;

    private final @NotNull ScrAddedDatValues values;

    private final @Nullable ScrModGraphics graphics;
    private final int @NotNull [] @Nullable [] @NotNull [] overlayOffsets;

    private ScrAddedImage(@NotNull Builder builder) {
        this.type      = builder.type;
        this.baseImage = builder.baseImage;

        this.values = builder.values.copy();

        this.graphics       = builder.graphics;
        this.overlayOffsets = Arrays.copyOf(builder.overlayOffsets, OVERLAY_TABLE_COUNT);
    }

    /**
     * @return the engine identity of the image
     */
    public @NotNull ImageType getType() {
        return this.type;
    }

    /**
     * @return the native {@code images.dat} entry whose values the image started from and whose overlay files it uses
     */
    public int getBaseImage() {
        return this.baseImage;
    }

    /**
     * @return the frame files of the mod, or {@code null} if the image shows the base image's installed frames
     */
    public @Nullable ScrModGraphics getGraphics() {
        return this.graphics;
    }

    /**
     * @param overlayType the overlay table, {@link ScrImageTypeView#OVERLAY_ATTACK} through {@link ScrImageTypeView#OVERLAY_SHIELD}
     * @return {@code true} if the image has its own offsets of the table ({@link Builder#setOverlayOffsets}), {@code false} otherwise (it reads its base image's overlay file)
     */
    public boolean hasOverlayOffsets(int overlayType) {
        return overlayType >= 0 && overlayType < OVERLAY_TABLE_COUNT && this.overlayOffsets[overlayType] != null;
    }

    /**
     * Returns one of the image's own overlay offsets, packed as an installed overlay position is read: the x offset as a signed WORD in the low
     * half, the y offset in the high half.
     *
     * @param overlayType the overlay table that has own offsets ({@link #hasOverlayOffsets})
     * @param frame       the frame of the image's frames
     * @param position    the position of the table
     * @return the packed offset
     * @throws IllegalArgumentException if the image has no own offsets of the table, or the frame or position is outside them
     */
    public int getOverlayOffset(int overlayType, int frame, int position) {
        int[][] offsets = this.hasOverlayOffsets(overlayType) ? this.overlayOffsets[overlayType] : null;

        if (offsets == null || frame < 0 || frame >= offsets.length || position < 0 || position >= offsets[frame].length / POSITION_VALUES) {
            throw Exceptions.newIllegalArgumentException("Image {0} has no own overlay offset {1} of frame {2} in table {3}.", this.type, position,
                                                         frame, overlayType);
        }

        int x = offsets[frame][position * POSITION_VALUES];
        int y = offsets[frame][position * POSITION_VALUES + 1];

        return x & WORD_MASK | y << Short.SIZE;
    }

    /**
     * Returns the value of an {@code images.dat} field.
     *
     * @param field the {@code images.dat} field name ({@link ImagesDat})
     * @return the value
     * @throws IllegalArgumentException if the field does not exist
     */
    public int getValue(@NotNull String field) {
        return this.values.get(field);
    }
}
