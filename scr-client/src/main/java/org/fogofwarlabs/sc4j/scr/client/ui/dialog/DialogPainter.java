package org.fogofwarlabs.sc4j.scr.client.ui.dialog;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceImage;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Provides sheet frames, text metrics and drawing operations for shared dialog controls. Glue and console painters supply their asset and coordinate
 * systems; default methods compose bars, frames and text.
 */
public interface DialogPainter {
    /**
     * The channels per decoded color pixel.
     */
    int COLOR_CHANNELS = 4;
    /**
     * The mask for reading an unsigned color channel byte.
     */
    int BYTE_MASK = 0xFF;
    /**
     * The columns and rows of a nine-slice grid.
     */
    int SLICE_COLUMNS = 3;
    /**
     * The scale converting a percentage to a fraction.
     */
    float PERCENT_SCALE = 100f;
    /**
     * The tolerance for rounding segment-period counts at integer boundaries.
     */
    float SEGMENT_ROUNDING_EPSILON = 1e-4f;
    /**
     * The blend weight from which the default blended text takes the second color code.
     */
    float BLEND_MIDPOINT = 0.5f;

    /**
     * The default text palette code: pale blue in every glue palette.
     */
    int CODE_TEXT = 2;
    /**
     * The button, radio button and list-row palette code.
     */
    int CODE_BUTTON = 3;
    /**
     * The hovered-button, hotkey-letter and selected-row palette code.
     */
    int CODE_HIGHLIGHT = 4;
    /**
     * The disabled-control palette code.
     */
    int CODE_DISABLED = 5;
    /**
     * The highlighted-text palette code: the glue titles and the local player.
     */
    int CODE_WHITE = 6;

    /**
     * The nine-frame popup tile sheet.
     */
    @NotNull String POPUP_TILES = "dlgs/tile.dds.grp";
    /**
     * The progress image end-cap width in interface pixels.
     */
    float PROGRESS_BAR_CAP = 5;
    /**
     * The maximum color-channel difference accepted for repeating bar segments.
     */
    int SEGMENT_TOLERANCE = 24;
    /**
     * The minimum compared texel-pair count per permitted differing pair.
     */
    int SEGMENT_MISMATCH_DIVISOR = 33;
    /**
     * The piece count of a three-by-three sliced frame.
     */
    int NINE_SLICE_PIECES = 9;
    /**
     * The packed popup-frame tint with three-quarter alpha.
     */
    int POPUP_FRAME_COLOR = 0xFFFFFFBF;

    /**
     * One frame of a sheet, sized in interface pixels.
     */
    interface Frame {
        /**
         * @return the width in interface pixels
         */
        float getWidth();

        /**
         * @return the height in interface pixels
         */
        float getHeight();

        /**
         * @return the frame width of the sheet's classic {@code .grp} header, the source of the dialog metric words, or the drawn width without one
         */
        default float getClassicWidth() {
            return this.getWidth();
        }

        /**
         * @return the frame height of the sheet's classic {@code .grp} header; the drawn height when there is none
         */
        default float getClassicHeight() {
            return this.getHeight();
        }
    }

    /**
     * Returns the bar width nearest above a wanted width at which the repeated segments end at the right cap in the same place as in the art: the
     * image width plus a whole number of segment periods.
     *
     * @param imageWidth the bar image width in interface pixels
     * @param width      the wanted width
     * @param period     the segment period ({@link #detectSegmentPeriod}), or {@code 0} for any width
     * @return the fitted width
     */
    static float fitProgressBarWidth(float imageWidth, float width, float period) {
        if (period <= 0) {
            return width;
        }

        return imageWidth + (float) Math.ceil((width - imageWidth) / period - SEGMENT_ROUNDING_EPSILON) * period;
    }

    /**
     * Finds the segment period of bar art: the smallest column step at which the columns between the end caps repeat in every given image (a few
     * differing texels are allowed for the block compression).
     *
     * @param scale  the texture pixels per interface pixel
     * @param images the bar images of one set, for example {@code pbrempt} and {@code pbrfull}
     * @return the period in interface pixels, or {@code 0} when the columns do not repeat
     */
    static float detectSegmentPeriod(int scale, @NotNull InterfaceImage @NotNull ... images) {
        int capWidth = Math.round(PROGRESS_BAR_CAP * scale);
        int shortest = Integer.MAX_VALUE;

        for (InterfaceImage image : images) {
            shortest = Math.min(shortest, image.getWidth() - 2 * capWidth);
        }

        for (int period = 1; period <= shortest / 2; period++) {
            boolean repeats = true;

            for (InterfaceImage image : images) {
                if (!repeatsBy(image, capWidth, period)) {
                    repeats = false;

                    break;
                }
            }

            if (repeats) {
                return period / (float) scale;
            }
        }

        return 0;
    }

    private static boolean repeatsBy(@NotNull InterfaceImage image, int capWidth, int period) {
        byte[] pixelColors = image.getRgba();
        int width = image.getWidth();
        int compared = 0;
        int differing = 0;

        for (int positionY = 0; positionY < image.getHeight(); positionY++) {
            for (int positionX = capWidth; positionX + period < width - capWidth; positionX++) {
                int first = (positionY * width + positionX) * COLOR_CHANNELS;
                int second = (positionY * width + positionX + period) * COLOR_CHANNELS;

                compared++;

                for (int channel = 0; channel < COLOR_CHANNELS; channel++) {
                    int difference = Byte.toUnsignedInt(pixelColors[first + channel]) - Byte.toUnsignedInt(pixelColors[second + channel]);

                    if (Math.abs(difference) > SEGMENT_TOLERANCE) {
                        differing++;

                        break;
                    }
                }
            }
        }

        return compared > 0 && differing * SEGMENT_MISMATCH_DIVISOR <= compared;
    }

    /**
     * @param sheet the sheet path, for example {@code glue/palnl/dlg.dds.grp}
     * @param frame the frame index
     * @return the frame
     * @throws IOException if the sheet or frame is missing or malformed
     */
    @NotNull Frame getSheetFrame(@NotNull String sheet, int frame) throws IOException;

    /**
     * Returns an image of SC:R's web pages ({@code webui/dist/lib/images}), which the pages draw at a
     * CSS size ({@link #drawFrame(Frame, float, float, float, float)}).
     *
     * @param name the file name, for example {@code down_arrow_yellow_32.png}
     * @return the image
     * @throws IOException if the image is missing or unreadable, or the painter has no web images
     */
    default @NotNull Frame getWebImage(@NotNull String name) throws IOException {
        throw Exceptions.newIOException("No web images: {0}", name);
    }

    /**
     * Returns the classic frame width as an integer dialog metric, as the dialog setup copies it for the controls (for example frame {@code 103}
     * into the push button tile).
     *
     * @param sheet the sheet path
     * @param frame the frame index
     * @return a dialog metric word: the frame width of the sheet's classic {@code .grp} header
     * @throws IOException if the sheet cannot be read
     */
    default int getMetricWidth(@NotNull String sheet, int frame) throws IOException {
        return (int) this.getSheetFrame(sheet, frame).getClassicWidth();
    }

    /**
     * @param slot the font slot
     * @return the line height of the slot's font
     */
    float getLineHeight(int slot);

    /**
     * Returns the height of one line of a text: the line box of a web text slot ({@link WebPanelStyleUtils#getFontSlot}), which can depend on the
     * faces the text takes; the slot's line height otherwise.
     *
     * @param slot the font slot
     * @param text the line
     * @return the height in interface pixels
     */
    default float getLineHeight(int slot, @NotNull String text) {
        return this.getLineHeight(slot);
    }

    /**
     * Returns where the baseline of a line of text lies below the line's top: in a web text slot the baseline of the line box the text takes, so that
     * spans of one line that take different faces share it.
     *
     * @param slot the font slot
     * @param text the line
     * @return the baseline in interface pixels, {@code 0} where the painter does not know it
     */
    default float getBaseline(int slot, @NotNull String text) {
        return 0;
    }

    /**
     * Measures the rendered text.
     *
     * @param slot the font slot
     * @param text one line
     * @return its width in interface pixels
     */
    float measure(int slot, @NotNull String text);

    /**
     * Selects the font slot of a control.
     *
     * @param control a control
     * @return its font slot by its {@code RC_*TEXT} flags
     */
    default int getControlFontSlot(@NotNull DialogControl control) {
        return ControlPainterUtils.getFontSlot(control);
    }


    /**
     * Draws a frame at its size, at the position plus the frame's offset of the sheet's classic {@code .grp} header, as the dialog frame draw does in
     * every asset set.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left
     * @param positionY the interface top
     */
    void drawFrame(@NotNull Frame frame, float positionX, float positionY);

    /**
     * Draws a frame into a rectangle, moved by the frame's classic offset as {@link #drawFrame(Frame, float, float)}.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left
     * @param positionY the interface top
     * @param width     the interface width
     * @param height    the interface height
     */
    void drawFrame(@NotNull Frame frame, float positionX, float positionY, float width, float height);

    /**
     * Draws a frame into a rectangle, moved by its classic offset, in a color multiplied with the texture. The default ignores the color; painters
     * that can tint override it.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left
     * @param positionY the interface top
     * @param width     the interface width
     * @param height    the interface height
     * @param color     the packed {@code 0xRRGGBBAA} color
     */
    default void drawFrame(@NotNull Frame frame, float positionX, float positionY, float width, float height, int color) {
        this.drawFrame(frame, positionX, positionY, width, height);
    }

    /**
     * Draws a frame at its size exactly at a position, without the classic frame offset: the plain image draw that the list item painters use for
     * their icons (the file list painter places an {@code glue\create\iCreate} icon from the frame height alone).
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left
     * @param positionY the interface top
     */
    void drawImageFrame(@NotNull Frame frame, float positionX, float positionY);

    /**
     * Draws a frame at its classic offset, clipped to the half-open horizontal range from {@code clipLeft} to {@code clipRight}.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left, before the offset
     * @param positionY the interface top, before the offset
     * @param clipLeft  the first visible column
     * @param clipRight the column after the last visible one
     */
    void drawFrameColumns(@NotNull Frame frame, float positionX, float positionY, float clipLeft, float clipRight);

    /**
     * Draws the left, repeated middle and right frames of a three-slice bar, clipped to its width. The supplied tile width is the classic metric;
     * bars narrower than two tiles split the two end frames at the midpoint.
     *
     * @param sheet     the sheet path
     * @param frame     the left frame
     * @param positionX the bar left
     * @param positionY the bar top
     * @param width     the bar width
     * @param tile      the tile width
     * @throws IOException if the sheet cannot be read
     */
    default void drawBar(@NotNull String sheet, int frame, float positionX, float positionY, float width, int tile) throws IOException {
        this.drawBar(this.getSheetFrame(sheet, frame),
                     this.getSheetFrame(sheet, frame + 1),
                     this.getSheetFrame(sheet, frame + (SLICE_COLUMNS - 1)),
                     positionX,
                     positionY,
                     width,
                     tile);
    }

    /**
     * Draws the left, repeated middle and right frames of a three-slice bar, clipped to its width. The supplied tile width is the classic metric;
     * bars narrower than two tiles split the two end frames at the midpoint.
     *
     * @param start     the left frame
     * @param middle    the tiled frame
     * @param end       the right frame
     * @param positionX the bar left
     * @param positionY the bar top
     * @param width     the bar width
     * @param tile      the tile width
     */
    default void drawBar(@NotNull Frame start, @NotNull Frame middle, @NotNull Frame end, float positionX, float positionY, float width, int tile) {
        float right = positionX + width;

        if (width < 2 * tile) {
            float half = positionX + (int) width / 2;

            this.drawFrameColumns(start, positionX, positionY, positionX, half);
            this.drawFrameColumns(end, right - tile, positionY, half, right);

            return;
        }

        this.drawFrameColumns(start, positionX, positionY, positionX, right);

        int middles = (int) (width - tile - 1) / tile;

        for (int index = 1; index <= middles; index++) {
            this.drawFrameColumns(middle, positionX + index * tile, positionY, positionX, right - tile);
        }

        this.drawFrameColumns(end, right - tile, positionY, positionX + tile, right);
    }


    /**
     * Draws the empty progress image, followed by the full image clipped to the clamped percentage. The width overload repeats middle columns between
     * the end caps at the supplied segment period.
     *
     * @param empty     the empty bar image ({@code pbrempt})
     * @param full      the full bar image ({@code pbrfull})
     * @param positionX the interface left
     * @param positionY the interface top
     * @param percent   the value from {@code 0} through {@code 100}
     */
    default void drawProgressBar(@NotNull Frame empty, @NotNull Frame full, float positionX, float positionY, float percent) {
        this.drawImageFrame(empty, positionX, positionY);

        float fraction = Math.max(0f, Math.min(1f, percent / PERCENT_SCALE));

        if (fraction > 0) {
            this.drawFrameColumns(full, positionX, positionY, positionX, positionX + full.getWidth() * fraction);
        }
    }

    /**
     * Draws the empty progress image, followed by the full image clipped to the clamped percentage. The width overload repeats middle columns between
     * the end caps at the supplied segment period.
     *
     * @param empty     the empty bar image ({@code pbrempt})
     * @param full      the full bar image ({@code pbrfull})
     * @param positionX the interface left
     * @param positionY the interface top
     * @param percent   the value from {@code 0} through {@code 100}
     * @param width     the bar width in interface pixels
     * @param period    the segment period of the art in interface pixels ({@link #detectSegmentPeriod}), or {@code 0} to repeat the whole middle
     */
    default void drawProgressBar(@NotNull Frame empty, @NotNull Frame full,
                                 float positionX, float positionY,
                                 float percent,
                                 float width,
                                 float period) {
        this.drawCappedColumns(empty, positionX, positionY, width, positionX + width, period);

        float fraction = Math.max(0f, Math.min(1f, percent / PERCENT_SCALE));

        if (fraction > 0) {
            this.drawCappedColumns(full, positionX, positionY, width, positionX + width * fraction, period);
        }
    }

    /**
     * Draws an image stretched to a width by its columns: the left cap, the middle columns repeated by a window of whole periods, the right cap, all
     * cut at {@code clipRight}.
     */
    private void drawCappedColumns(@NotNull Frame image, float positionX, float positionY, float width, float clipRight, float period) {
        float imageWidth = image.getWidth();
        float middle = imageWidth - 2 * PROGRESS_BAR_CAP;
        float window = period > 0 && middle >= period ? (float) Math.floor(middle / period + SEGMENT_ROUNDING_EPSILON) * period : middle;
        float right = positionX + width;
        float middleEnd = right - PROGRESS_BAR_CAP;

        this.drawFrameColumns(image, positionX, positionY, positionX, Math.min(positionX + PROGRESS_BAR_CAP, clipRight));

        for (float column = positionX + PROGRESS_BAR_CAP; window > 0 && column < middleEnd; column += window) {
            this.drawFrameColumns(image, column - PROGRESS_BAR_CAP, positionY, column, Math.min(Math.min(column + window, middleEnd), clipRight));
        }

        this.drawFrameColumns(image, right - imageWidth, positionY, middleEnd, Math.min(right, clipRight));
    }

    /**
     * Rounds the dialog dimensions up to the classic tile width and draws the nine popup pieces over their grid.
     *
     * @param positionX the dialog left
     * @param positionY the dialog top
     * @param width     the dialog width
     * @param height    the dialog height
     * @throws IOException if the tile sheet cannot be read
     */
    default void drawPopupFrame(float positionX, float positionY, int width, int height) throws IOException {
        Frame corner = this.getSheetFrame(POPUP_TILES, 0);
        int tile = (int) corner.getClassicWidth();
        int roundedWidth = (width + tile - 1) / tile * tile;
        int roundedHeight = (height + tile - 1) / tile * tile;
        Frame[] pieces = new Frame[NINE_SLICE_PIECES];

        for (int piece = 0; piece < NINE_SLICE_PIECES; piece++) {
            pieces[piece] = this.getSheetFrame(POPUP_TILES, piece);
        }

        this.drawNineSlice(pieces,
                           new float[]{positionX, positionX + tile, positionX + roundedWidth, positionX + roundedWidth + tile},
                           new float[]{positionY, positionY + tile, positionY + roundedHeight - tile, positionY + roundedHeight},
                           POPUP_FRAME_COLOR);
    }

    /**
     * Draws nine pieces row by row over a grid of three columns and three rows. Rows and columns with no positive extent are skipped.
     *
     * @param pieces  the nine pieces, row by row: top left, top, top right, left, inside, right, bottom left, bottom, bottom right
     * @param columns the four column edges, left to right
     * @param rows    the four row edges, top to bottom
     * @param color   the packed {@code 0xRRGGBBAA} color
     */
    default void drawNineSlice(@NotNull Frame @NotNull [] pieces, float @NotNull [] columns, float @NotNull [] rows, int color) {
        for (int row = 0; row < SLICE_COLUMNS; row++) {
            float height = rows[row + 1] - rows[row];

            if (height <= 0) {
                continue;
            }

            for (int column = 0; column < SLICE_COLUMNS; column++) {
                float width = columns[column + 1] - columns[column];

                if (width > 0) {
                    this.drawFrame(pieces[row * SLICE_COLUMNS + column], columns[column], rows[row], width, height, color);
                }
            }
        }
    }


    /**
     * Draws text through the shared layout in a color code of the current palette.
     *
     * @param slot   the font slot
     * @param text   the text
     * @param left   the rectangle left
     * @param top    the rectangle top
     * @param width  the rectangle width
     * @param height the rectangle height
     * @param format the {@link InterfaceText} format
     * @param code   the color code, for example {@link #CODE_BUTTON}
     */
    void drawCodeText(int slot, @NotNull String text, float left, float top, float width, float height, int format, int code);

    /**
     * Draws the nearer of two palette codes by default; painters can override this method to blend them.
     *
     * @param slot   the font slot
     * @param text   the text
     * @param left   the rectangle left
     * @param top    the rectangle top
     * @param width  the rectangle width
     * @param height the rectangle height
     * @param format the {@link InterfaceText} format
     * @param from   the color code at weight {@code 0}
     * @param to     the color code at weight {@code 1}
     * @param weight the blend weight from {@code 0} through {@code 1}
     */
    default void drawBlendedCodeText(int slot,
                                     @NotNull String text,
                                     float left, float top,
                                     float width, float height,
                                     int format,
                                     int from, int to,
                                     float weight) {
        this.drawCodeText(slot, text, left, top, width, height, format, weight >= BLEND_MIDPOINT ? to : from);
    }

    /**
     * Draws text in a color of its own rather than a color code (the web panels' named colors, such as the lobby's color picker). Painters without
     * free colors draw it in the button code.
     *
     * @param slot   the font slot
     * @param text   the text
     * @param left   the rectangle left
     * @param top    the rectangle top
     * @param width  the rectangle width
     * @param height the rectangle height
     * @param format the text format bits
     * @param color  the packed {@code 0xRRGGBBAA} color
     */
    default void drawColorText(int slot, @NotNull String text, float left, float top, float width, float height, int format, int color) {
        this.drawCodeText(slot, text, left, top, width, height, format, CODE_BUTTON);
    }

    /**
     * Fills an interface rectangle with a color.
     *
     * @param positionX the interface left
     * @param positionY the interface top
     * @param width     the interface width
     * @param height    the interface height
     * @param color     the packed {@code 0xRRGGBBAA} color
     */
    void fillRectangle(float positionX, float positionY, float width, float height, int color);
}
