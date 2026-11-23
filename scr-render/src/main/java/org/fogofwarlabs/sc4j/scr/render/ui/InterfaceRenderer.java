package org.fogofwarlabs.sc4j.scr.render.ui;

import org.fogofwarlabs.sc4j.engine.client.render.*;
import org.fogofwarlabs.sc4j.engine.client.text.TextStyle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * Draws RGBA images, solid rectangles and shared game-font text on an OpenGL 3.3 context: the engine canvas of the current graphics device
 * ({@link GraphicsDevice#createCanvas}), which it draws through, with the developer text in the client's interface fonts. Coordinates use a
 * top-left framebuffer origin and colors use packed {@code 0xRRGGBBAA}. Draw after the world renderer has ended its frame.
 */
public final class InterfaceRenderer implements GpuCanvas {
    private final @NotNull GpuCanvas canvas;

    private @Nullable InterfaceFonts textFonts;

    /**
     * Creates an {@link InterfaceRenderer} instance on the current OpenGL 3.3 context. Its developer text uses the client's game fonts once shared
     * ({@link #setTextFonts}).
     */
    public InterfaceRenderer() {
        this.canvas = GraphicsDevice.getCurrent().createCanvas(GlyphAtlas.PAGE_SIZE);

        this.textFonts = null;
    }

    /**
     * @return the width of the drawing coordinates of the current drawing
     */
    @Override
    public int getWidth() {
        return this.canvas.getWidth();
    }

    /**
     * @return the height of the drawing coordinates of the current drawing
     */
    @Override
    public int getHeight() {
        return this.canvas.getHeight();
    }

    /**
     * Sets the interface font slots of the screen ({@link InterfaceFonts}) that {@link #drawText} shares, so the developer text uses the same fonts,
     * locale and text mode as the in-game text. Without it the developer text is not drawn.
     *
     * @param fonts the shared fonts (owned by the caller)
     */
    public void setTextFonts(@NotNull InterfaceFonts fonts) {
        this.textFonts = fonts;
    }

    /**
     * Stops sharing a font set (its owner is closing it); {@link #drawText} draws nothing until another set is shared.
     *
     * @param fonts the shared fonts
     */
    public void releaseTextFonts(@NotNull InterfaceFonts fonts) {
        if (this.textFonts == fonts) {
            this.textFonts = null;
        }
    }


    /**
     * Creates an empty one-byte signed distance field page of a glyph atlas, sampled linearly.
     *
     * @param width  the width in texels
     * @param height the height in texels
     * @return the texture name
     */
    @Override
    public int createSdfTexture(int width, int height) {
        return this.canvas.createSdfTexture(width, height);
    }

    /**
     * Replaces a rectangle of a distance field page.
     *
     * @param texture the page texture
     * @param x       the left texel
     * @param y       the top texel
     * @param width   the width in texels
     * @param height  the height in texels
     * @param texels  the row-major texels
     */
    @Override
    public void updateSdfTexture(int texture, int x, int y, int width, int height, @NotNull ByteBuffer texels) {
        this.canvas.updateSdfTexture(texture, x, y, width, height, texels);
    }

    /**
     * Uploads an RGBA image.
     *
     * @param width      the image width
     * @param height     the image height
     * @param rgbaPixels the row-major RGBA bytes, top row first
     * @return the texture name
     * @throws IllegalArgumentException if a dimension is not positive or the pixel array does not match the dimensions
     */
    @Override
    public int createTexture(int width, int height, byte @NotNull [] rgbaPixels) {
        return this.canvas.createTexture(width, height, rgbaPixels);
    }

    /**
     * Uploads an image as a texture of this canvas.
     *
     * @param width  the image width in pixels
     * @param height the image height in pixels
     * @param colors the row-major pixels, top row first, packed {@code 0xAARRGGBB} with straight alpha as the resource model stores them
     * @return the created {@link Texture} instance, closed by the caller
     * @throws IllegalArgumentException if a dimension is not positive or the pixels do not match the dimensions
     */
    @Override
    public @NotNull Texture uploadTexture(int width, int height, int @NotNull [] colors) {
        return this.canvas.uploadTexture(width, height, colors);
    }

    /**
     * Creates an empty RGBA texture for {@link #updateTexture}.
     *
     * @param width               the texture width
     * @param height              the texture height
     * @param linearMagnification whether magnification filters linearly (minification always does)
     * @return the texture name
     * @throws IllegalArgumentException if the size is not positive
     */
    @Override
    public int createDynamicTexture(int width, int height, boolean linearMagnification) {
        return this.canvas.createDynamicTexture(width, height, linearMagnification);
    }

    /**
     * Replaces the texels of a texture created by {@link #createDynamicTexture} from packed {@code 0xRRGGBBAA} ints, uploaded without a byte
     * conversion.
     *
     * @param texture the texture name
     * @param width   the texture width
     * @param height  the texture height
     * @param pixels  the row-major texels, top row first, {@code width * height} of them
     */
    @Override
    public void updateTexture(int texture, int width, int height, int @NotNull [] pixels) {
        this.canvas.updateTexture(texture, width, height, pixels);
    }

    /**
     * Replaces rows of a texture created by {@link #createDynamicTexture} from packed {@code 0xRRGGBBAA} ints.
     *
     * @param texture the texture name
     * @param width   the texture width
     * @param top     the first row to replace
     * @param bottom  the last row to replace
     * @param pixels  the whole texture's row-major texels, top row first
     * @throws IllegalArgumentException if the texels do not cover the rows
     */
    @Override
    public void updateTextureRows(int texture, int width, int top, int bottom, int @NotNull [] pixels) {
        this.canvas.updateTextureRows(texture, width, top, bottom, pixels);
    }

    /**
     * Deletes a texture created by {@link #createTexture}.
     *
     * @param texture the texture name
     */
    @Override
    public void deleteTexture(int texture) {
        this.canvas.deleteTexture(texture);
    }

    /**
     * Begins drawing over the whole window, in window pixel coordinates.
     *
     * @param width  the window framebuffer width in pixels
     * @param height the window framebuffer height in pixels
     */
    @Override
    public void begin(int width, int height) {
        this.canvas.begin(width, height);
    }

    /**
     * Begins interface drawing into a framebuffer whose pixel grid differs from the drawing coordinates: callers keep drawing in coordinates of
     * {@code coordinateWidth} by {@code coordinateHeight} (the window), which map onto the whole viewport (for example a low resolution target that
     * is scaled up to the window afterwards).
     *
     * @param framebuffer      the framebuffer, {@code 0} for the window
     * @param viewportWidth    the framebuffer width in pixels
     * @param viewportHeight   the framebuffer height in pixels
     * @param coordinateWidth  the drawing coordinates' width
     * @param coordinateHeight the drawing coordinates' height
     * @throws IllegalStateException if the renderer is closed
     */
    @Override
    public void begin(int framebuffer, int viewportWidth, int viewportHeight, int coordinateWidth, int coordinateHeight) {
        this.canvas.begin(framebuffer, viewportWidth, viewportHeight, coordinateWidth, coordinateHeight);
    }

    /**
     * Sets the rectangle the following drawing is limited to, or lifts the limit.
     *
     * @param x      the left coordinate
     * @param y      the top coordinate
     * @param width  the width, or a negative value to draw over the whole target again
     * @param height the height
     */
    @Override
    public void setClip(int x, int y, int width, int height) {
        this.canvas.setClip(x, y, width, height);
    }

    /**
     * Draws a whole texture into a rectangle.
     *
     * @param texture the texture name
     * @param x       the left coordinate
     * @param y       the top coordinate
     * @param width   the drawn width
     * @param height  the drawn height
     * @param color   the packed color multiplied with the texture
     */
    @Override
    public void drawTexture(int texture, float x, float y, float width, float height, int color) {
        this.canvas.drawTexture(texture, x, y, width, height, color);
    }

    /**
     * Draws part of a texture into a rectangle.
     *
     * @param texture the texture name
     * @param x       the left coordinate
     * @param y       the top coordinate
     * @param width   the drawn width
     * @param height  the drawn height
     * @param left    the left texture coordinate
     * @param top     the top texture coordinate
     * @param right   the right texture coordinate
     * @param bottom  the bottom texture coordinate
     * @param color   the packed color multiplied with the texture
     */
    @Override
    public void drawTexture(int texture, float x, float y, float width, float height,
                            float left, float top, float right, float bottom,
                            int color) {
        this.canvas.drawTexture(texture, x, y, width, height, left, top, right, bottom, color);
    }

    /**
     * Draws a whole texture into a rectangle.
     *
     * @param texture the texture of this canvas
     * @param x       the left coordinate
     * @param y       the top coordinate
     * @param width   the drawn width
     * @param height  the drawn height
     * @param color   the packed colour multiplied with the texture
     */
    @Override
    public void drawTexture(@NotNull Texture texture, float x, float y, float width, float height, int color) {
        this.canvas.drawTexture(texture, x, y, width, height, color);
    }

    /**
     * Draws part of a texture into a rectangle.
     *
     * @param texture the texture of this canvas
     * @param x       the left coordinate
     * @param y       the top coordinate
     * @param width   the drawn width
     * @param height  the drawn height
     * @param left    the left texture coordinate, {@code 0} to {@code 1}
     * @param top     the top texture coordinate
     * @param right   the right texture coordinate
     * @param bottom  the bottom texture coordinate
     * @param color   the packed colour multiplied with the texture
     */
    @Override
    public void drawTexture(@NotNull Texture texture, float x, float y, float width, float height,
                            float left, float top, float right, float bottom,
                            int color) {
        this.canvas.drawTexture(texture, x, y, width, height, left, top, right, bottom, color);
    }

    /**
     * Draws part of a distance field page as a glyph through the distance-field shader.
     *
     * @param texture      the page texture
     * @param x            the left coordinate
     * @param y            the top coordinate
     * @param width        the drawn width
     * @param height       the drawn height
     * @param left         the left texture coordinate
     * @param top          the top texture coordinate
     * @param right        the right texture coordinate
     * @param bottom       the bottom texture coordinate
     * @param color        the packed glyph color
     * @param shadowOffset the drop shadow offset in texels right and down, or {@code 0}
     * @param shadowPower  the drop shadow weight, or {@code 0} for none
     */
    @Override
    public void drawSdfTexture(int texture,
                               float x, float y, float width, float height,
                               float left, float top, float right, float bottom,
                               int color,
                               float shadowOffset, float shadowPower) {
        this.canvas.drawSdfTexture(texture, x, y, width, height, left, top, right, bottom, color, shadowOffset, shadowPower);
    }

    /**
     * Fills a rectangle.
     *
     * @param x      the left coordinate
     * @param y      the top coordinate
     * @param width  the width
     * @param height the height
     * @param color  the packed colour
     */
    @Override
    public void fillRectangle(float x, float y, float width, float height, int color) {
        this.canvas.fillRectangle(x, y, width, height, color);
    }

    /**
     * Draws a rectangle outline inside the rectangle.
     *
     * @param x         the left coordinate
     * @param y         the top coordinate
     * @param width     the width
     * @param height    the height
     * @param thickness the line thickness
     * @param color     the packed colour
     */
    @Override
    public void drawRectangle(float x, float y, float width, float height, float thickness, int color) {
        this.canvas.drawRectangle(x, y, width, height, thickness, color);
    }

    /**
     * Draws a straight line of the given thickness.
     *
     * @param sourceX      the start x
     * @param sourceY      the start y
     * @param destinationX the end x
     * @param destinationY the end y
     * @param thickness    the line thickness
     * @param color        the packed colour
     */
    @Override
    public void drawLine(float sourceX, float sourceY, float destinationX, float destinationY, float thickness, int color) {
        this.canvas.drawLine(sourceX, sourceY, destinationX, destinationY, thickness, color);
    }

    /**
     * Fills a convex quadrilateral, for example the screen outline of a diamond cell or of a footprint: an axis-aligned rectangle as
     * {@link #fillRectangle}, other outlines as two triangles of the engine canvas.
     *
     * @param corners the x and y of the four corners in order around the outline, {@value QuadrilateralUtils#CORNER_VALUES} values
     * @param color   the packed colour
     * @throws IndexOutOfBoundsException if the array is shorter than {@value QuadrilateralUtils#CORNER_VALUES}
     */
    @Override
    public void fillQuadrilateral(float @NotNull [] corners, int color) {
        this.canvas.fillQuadrilateral(corners, color);
    }

    /**
     * Submits the pending drawing.
     */
    @Override
    public void end() {
        this.canvas.end();
    }


    /**
     * Measures a single line of text in the shared {@code font10} ({@link #drawText}).
     *
     * @param text   the text
     * @param height the text height in pixels
     * @return the advance width in pixels
     */
    public float measureText(@NotNull String text, float height) {
        InterfaceFonts fonts = this.textFonts;
        InterfaceFont font = fonts == null ? null : fonts.getFont(InterfaceFonts.NORMAL);

        if (font == null) {
            return 0;
        }

        float scale = height / font.getTextHeight(text);

        return font.measure(text, true) * scale;
    }

    /**
     * Draws a single line of text with its top at the given coordinate in the shared interface font: {@code font10} (the game dialog font, slot
     * {@link InterfaceFonts#NORMAL}) through {@link InterfaceFont}, whose glyphs are the {@code .fnt} bitmaps or the TrueType chain of the locale as
     * for the in-game text, scaled so that its text height is the requested height. The developer screens (debug panel, overlay labels, command
     * results) use it.
     *
     * @param text   the text
     * @param x      the left coordinate
     * @param y      the top coordinate
     * @param height the text height in pixels
     * @param color  the packed color
     * @return the pen position after the text
     */
    public float drawText(@NotNull String text, float x, float y, float height, int color) {
        InterfaceFonts fonts = this.textFonts;
        InterfaceFont font = fonts == null ? null : fonts.getFont(InterfaceFonts.NORMAL);

        if (font == null || text.isEmpty()) {
            return x;
        }

        float scale = height / font.getTextHeight(text);

        return font.draw(this, text, x, y, color, TextStyle.builder(font.getDefaultStyle()).setScale(scale).build());
    }


    /**
     * Disposes resources: releases the GPU programs, buffers and textures and the staging memory. Closing again does nothing.
     */
    @Override
    public void close() {
        this.canvas.close();
    }
}
