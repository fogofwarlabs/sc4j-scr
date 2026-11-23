package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the developer text lines of the client frame (the replay load failure, the drop prompt, the loading caption, status lines) and
 * draws them in interface-font text after the world frame, then forgets them: a part of the SC:R HUD frame with its own owner.
 */
final class DeveloperTextLayer {
    /**
     * The pixel height of a developer text line per text scale unit (cap height {@code 7} of {@code 12} units).
     */
    private static final float TEXT_UNITS = 9f;

    /**
     * A queued developer text line.
     */
    private static final class Line {
        private final @NotNull String text;
        private final float x;
        private final float y;
        private final float height;
        private final int color;

        /**
         * Creates a {@link Line} instance.
         *
         * @param text   the displayed text
         * @param x      the left screen coordinate
         * @param y      the top screen coordinate
         * @param height the text height
         * @param color  the packed color
         */
        private Line(@NotNull String text, float x, float y, float height, int color) {
            this.text   = text;
            this.x      = x;
            this.y      = y;
            this.height = height;
            this.color  = color;
        }
    }

    private final @NotNull List<Line> lines;

    /**
     * Creates an empty {@link DeveloperTextLayer} instance.
     */
    DeveloperTextLayer() {
        this.lines = new ArrayList<>();
    }


    /**
     * Queues interface-font text to draw after the world frame.
     *
     * @param text  the displayed text
     * @param x     the left screen coordinate
     * @param y     the top screen coordinate
     * @param size  the scale multiplied by {@value #TEXT_UNITS} to obtain the text height
     * @param color the packed color
     */
    void queue(@NotNull String text, float x, float y, float size, int color) {
        this.lines.add(new Line(text, x, y, size * TEXT_UNITS, color));
    }

    /**
     * Draws the queued lines in one interface pass and forgets them; without an interface renderer they are dropped.
     *
     * @param renderer the interface renderer, or {@code null} before the client resources exist
     * @param width    the framebuffer width
     * @param height   the framebuffer height
     */
    void draw(@Nullable InterfaceRenderer renderer, int width, int height) {
        if (this.lines.isEmpty()) {
            return;
        }

        if (renderer != null) {
            renderer.begin(width, height);

            for (Line line : this.lines) {
                renderer.drawText(line.text, line.x, line.y, line.height, line.color);
            }

            renderer.end();
        }

        this.lines.clear();
    }
}
