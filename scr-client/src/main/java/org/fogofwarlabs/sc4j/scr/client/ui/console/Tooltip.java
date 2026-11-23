package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFont;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceRenderer;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * Retains the active tooltip owner and painter and draws common tooltip backgrounds and text boxes. The asset style selects a rectangular or rounded
 * border. Text wrapping and measured bounds determine the box, which is shifted inside the interface.
 */
public final class Tooltip {
    /**
     * The text color code of the common box (color code {@code 2}).
     */
    public static final int TEXT_COLOR_CODE = 2;
    /**
     * The line gap of tooltip layouts.
     */
    public static final int LINE_GAP_BYTE = 0;
    /**
     * The target RGB of the fill color byte.
     */
    public static final int FILL_TARGET = 0x000000;
    /**
     * The target RGB of the border color byte.
     */
    public static final int BORDER_TARGET = 0x004BE3;
    /**
     * The horizontal pen offset of the common tooltip text.
     */
    public static final int PEN_START_X = 2;

    /**
     * The left edge of the common tooltip measuring clip.
     */
    private static final int CLIP_LEFT = 2;
    /**
     * The top edge of the measuring clip rectangle.
     */
    private static final int CLIP_TOP = 0;
    /**
     * The bottom edge of the measuring clip rectangle.
     */
    private static final int CLIP_BOTTOM = 91;
    /**
     * The inclusive right edge of the measuring clip rectangle; lines wrap at {@code CLIP_RIGHT - CLIP_LEFT + 1} pixels.
     */
    private static final int CLIP_RIGHT = 179;
    /**
     * The fill grows the text rectangle by {@code 2} pixels on every side.
     */
    private static final int FILL_GROWTH = 2;
    /**
     * The SD outline grows the text rectangle by {@code 1} pixel.
     */
    private static final int OUTLINE_GROWTH = 1;
    private static final char LINE_FEED = 0x0A;
    /**
     * The amount removed from a box bottom greater than this value.
     */
    private static final int BOTTOM_TRIM = 2;
    /**
     * The rounded background corner radius in output pixels.
     */
    private static final float CORNER_RADIUS = 10f;
    private static final int DEFAULT_FILL = 0x000000FF;
    private static final int DEFAULT_BORDER = 0x0048CCFF;
    /**
     * The offset of an output row's center from its top.
     */
    private static final float ROW_CENTER = 0.5f;

    /**
     * Draws the shown tooltip.
     */
    public interface Painter {
        /**
         * Draws the tooltip.
         *
         * @param tooltip the tooltip, whose box drawers the painter uses
         * @param console the console layout of the frame
         * @throws IOException if an image cannot be read
         */
        void paint(@NotNull Tooltip tooltip, @NotNull ConsoleLayout console) throws IOException;
    }

    private final @NotNull InterfaceRenderer renderer;

    private @Nullable Object owner;
    private @Nullable Painter painter;

    private @Nullable InterfaceFont font;
    private int fillColor;
    private int borderColor;
    private boolean rounded;

    /**
     * Creates a {@link Tooltip} instance.
     *
     * @param renderer the interface renderer
     */
    public Tooltip(@NotNull InterfaceRenderer renderer) {
        this.renderer = renderer;

        this.owner   = null;
        this.painter = null;

        this.font        = null;
        this.fillColor   = DEFAULT_FILL;
        this.borderColor = DEFAULT_BORDER;
        this.rounded     = false;
    }

    /**
     * Sets the box style of the current asset set.
     *
     * @param fillColor   the packed fill color (palette entry nearest to {@link #FILL_TARGET})
     * @param borderColor the packed border color (palette entry nearest to {@link #BORDER_TARGET})
     * @param rounded     whether the HD rounded box is drawn instead of the SD rectangle
     */
    public void setStyle(int fillColor, int borderColor, boolean rounded) {
        this.fillColor   = fillColor;
        this.borderColor = borderColor;
        this.rounded     = rounded;
    }

    /**
     * Sets the font of the common box (font slot {@code 0}, font8).
     *
     * @param font the font, or {@code null}
     */
    public void setFont(@Nullable InterfaceFont font) {
        this.font = font;
    }

    /**
     * @return the owner of the shown tooltip, or {@code null} when none is shown
     */
    public @Nullable Object getOwner() {
        return this.owner;
    }

    /**
     * @return the font of the common box, or {@code null} before it is loaded
     */
    public @Nullable InterfaceFont getFont() {
        return this.font;
    }


    /**
     * Shows a tooltip, replacing the shown one.
     *
     * @param owner   the owning control; an equal owner marks the same tooltip
     * @param painter the painter
     */
    public void show(@NotNull Object owner, @NotNull Painter painter) {
        this.owner   = owner;
        this.painter = painter;
    }

    /**
     * Removes the tooltip.
     */
    public void clear() {
        this.owner   = null;
        this.painter = null;
    }

    /**
     * Draws the shown tooltip, if any.
     *
     * @param console the console layout of the frame
     * @throws IOException if an image cannot be read
     */
    public void draw(@NotNull ConsoleLayout console) throws IOException {
        if (this.painter != null) {
            this.painter.paint(this, console);
        }
    }

    /**
     * Draws the box background around a rectangle: SD fills the rectangle grown by {@code 2} and outlines it grown by {@code 1}; HD draws the rounded
     * box grown by {@code 2}; each draw is {@code right - left} by {@code bottom - top} of the grown rectangle.
     *
     * @param console the console layout
     * @param left    the left interface x
     * @param top     the top interface y
     * @param right   the right interface x
     * @param bottom  the bottom interface y
     */
    public void drawBackground(@NotNull ConsoleLayout console, float left, float top, float right, float bottom) {
        float scale = console.getScale();
        float x = console.toScreenX(left - FILL_GROWTH);
        float y = console.toScreenY(top - FILL_GROWTH);
        float boxWidth = (right - left + 2 * FILL_GROWTH) * scale;
        float boxHeight = (bottom - top + 2 * FILL_GROWTH) * scale;

        if (this.rounded) {
            // HD: the rounded fill covers the rectangle grown by 2 and the border lies outside it, the scale rounded up of
            // output pixels wide.
            float border = (float) Math.ceil(scale);

            this.fillRoundedRectangle(x - border, y - border,
                                      boxWidth + 2 * border, boxHeight + 2 * border,
                                      CORNER_RADIUS + border,
                                      this.borderColor);
            this.fillRoundedRectangle(x, y, boxWidth, boxHeight, CORNER_RADIUS, this.fillColor);

            return;
        }

        this.renderer.fillRectangle(x, y, boxWidth, boxHeight, this.fillColor);

        // The grown rectangle puts the outline outside the fill, matching the outer HD border.
        float outline = OUTLINE_GROWTH + 1;

        this.renderer.drawRectangle(console.toScreenX(left - outline), console.toScreenY(top - outline),
                                    (right - left + 2 * outline) * scale, (bottom - top + 2 * outline) * scale,
                                    scale,
                                    this.borderColor);
    }

    /**
     * Fills a rounded rectangle in screen pixels: one strip per output row inside the corners, then the middle.
     */
    private void fillRoundedRectangle(float x, float y, float width, float height, float radius, int color) {
        float corner = Math.min(radius, Math.min(width, height) / 2f);
        int rows = (int) Math.ceil(corner);

        for (int row = 0; row < rows; row++) {
            float distance = corner - (row + ROW_CENTER);
            float inset = corner - (float) Math.sqrt(Math.max(0f, corner * corner - distance * distance));

            this.renderer.fillRectangle(x + inset, y + row, width - 2 * inset, 1, color);
            this.renderer.fillRectangle(x + inset, y + height - row - 1, width - 2 * inset, 1, color);
        }

        this.renderer.fillRectangle(x, y + rows, width, height - 2 * rows, color);
    }

    /**
     * Draws the common text box in font slot {@code 0} with color code {@code 2}, format {@code 0x11}, line gap {@code 0} and the clip
     * {@code (2, 0, 179, 91)}: the layout is measured from the pen {@code (2, 0)} ({@link InterfaceText#measureBounds}, bounds grown by {@code 2}
     * within the clip); the rectangle is {@code left = max(x, 0)}, {@code right = left - boundsLeft + boundsRight}, {@code top = max(y, 0)},
     * {@code bottom = top - boundsTop + boundsBottom}, less {@code 2} when that exceeds {@code 2}, each pair moved left or up to stay inside the
     * interface ({@code right} and {@code bottom} at most the size minus {@code 1}); the background grows it by {@code 2} and the text is drawn from
     * the pen {@code (2, 0)} at the rectangle's origin.
     *
     * @param console the console layout
     * @param x       the anchor interface x
     * @param y       the anchor interface y
     * @param text    the text; line feeds break lines
     */
    public void drawBox(@NotNull ConsoleLayout console, float x, float y, @NotNull String text) {
        InterfaceFont boxFont = this.font;

        if (boxFont == null || text.isEmpty()) {
            return;
        }

        List<String> lines = InterfaceText.wrap(boxFont, text, CLIP_RIGHT - CLIP_LEFT);
        InterfaceText.Bounds bounds = InterfaceText.measureBounds(boxFont,
                                                                  lines,
                                                                  PEN_START_X, 0,
                                                                  LINE_GAP_BYTE,
                                                                  CLIP_LEFT, CLIP_TOP,
                                                                  CLIP_RIGHT, CLIP_BOTTOM);
        float left = Math.max(x, 0);
        float right = left + bounds.getWidth();
        float overflowX = console.getInterfaceWidth() - right - 1;

        if (overflowX < 0) {
            left += overflowX;
            right += overflowX;
        }

        float top = Math.max(y, 0);
        float bottom = top + bounds.getHeight();

        if (bottom > BOTTOM_TRIM) {
            bottom -= BOTTOM_TRIM;
        }

        float overflowY = ConsoleLayout.INTERFACE_HEIGHT - bottom - 1;

        if (overflowY < 0) {
            top += overflowY;
            bottom += overflowY;
        }

        this.drawBackground(console, left, top, right, bottom);

        // Draws within the same clip words as the measure, relative to the rectangle's origin.
        InterfaceText.drawUnwrapped(this.renderer,
                                    console,
                                    boxFont,
                                    String.join(String.valueOf(LINE_FEED), lines),
                                    left + PEN_START_X, top,
                                    left + CLIP_RIGHT,
                                    InterfaceText.LEFT | InterfaceText.TOP,
                                    TEXT_COLOR_CODE);
    }
}
