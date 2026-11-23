package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.Texture;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.DialogPainter;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.SheetFrameRecords;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.fogofwarlabs.sc4j.scr.render.ui.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws console dialog frames and text through the active interface transform. Frame and decoded-image caches include the asset quality prefix;
 * release clears both after image-cache textures are released.
 */
final class ConsoleDialogPainter implements Canvas, DialogPainter {
    private static final int WHITE = 0xFFFFFFFF;
    /**
     * The clip size that lifts the drawing limit.
     */
    private static final int NO_CLIP_SIZE = -1;
    private static final @NotNull String KEY_SEPARATOR = "#";
    private static final @NotNull String TINT_SEPARATOR = ",";

    /**
     * A frame of a console sheet: its texture, its size in interface pixels and its classic record.
     */
    static final class Frame implements DialogPainter.Frame {
        private final int texture;

        private final float width;
        private final float height;

        private final int offsetX;
        private final int offsetY;

        private final float classicWidth;
        private final float classicHeight;

        private Frame(int texture, float width, float height, int @Nullable [] record) {
            this.texture = texture;

            this.width  = width;
            this.height = height;

            this.offsetX = record == null ? 0 : record[SheetFrameRecords.X_INDEX];
            this.offsetY = record == null ? 0 : record[SheetFrameRecords.Y_INDEX];

            this.classicWidth  = record == null ? width : record[SheetFrameRecords.WIDTH_INDEX];
            this.classicHeight = record == null ? height : record[SheetFrameRecords.HEIGHT_INDEX];
        }

        /**
         * @return the width in interface pixels
         */
        @Override
        public float getWidth() {
            return this.width;
        }

        /**
         * @return the height in interface pixels
         */
        @Override
        public float getHeight() {
            return this.height;
        }

        /**
         * @return the frame width of the sheet's classic {@code .grp} header, the source of the dialog metric words, or the drawn width without one
         */
        @Override
        public float getClassicWidth() {
            return this.classicWidth;
        }

        /**
         * @return the frame height of the sheet's classic {@code .grp} header; the drawn height when there is none
         */
        @Override
        public float getClassicHeight() {
            return this.classicHeight;
        }

        /**
         * @return the texture name
         */
        int getTexture() {
            return this.texture;
        }
    }


    private final @NotNull Map<String, Frame> frames;
    private final @NotNull Map<String, InterfaceAssets.ScaledImage> decoded;

    private final @NotNull InterfaceRenderer renderer;
    private final @NotNull InterfaceImageCache images;
    private final @NotNull InterfaceFonts fonts;
    private final @NotNull SheetFrameRecords records;

    private @Nullable InterfaceTransform transform;
    private @Nullable InterfaceAssets assets;

    /**
     * Creates a {@link ConsoleDialogPainter} instance.
     *
     * @param renderer the interface renderer
     * @param images   the console's image cache
     * @param fonts    the client's game fonts
     * @param records  the classic frame records
     */
    ConsoleDialogPainter(@NotNull InterfaceRenderer renderer,
                         @NotNull InterfaceImageCache images,
                         @NotNull InterfaceFonts fonts,
                         @NotNull SheetFrameRecords records) {
        this.frames  = new HashMap<>();
        this.decoded = new HashMap<>();

        this.renderer = renderer;
        this.images   = images;
        this.fonts    = fonts;
        this.records  = records;

        this.transform = null;
        this.assets    = null;
    }

    /**
     * @param sheet the sheet path, for example {@code glue/palnl/dlg.dds.grp}
     * @param frame the frame index
     * @return the frame
     * @throws IOException if the sheet or frame is missing or malformed
     */
    @Override
    public @NotNull Frame getSheetFrame(@NotNull String sheet, int frame) throws IOException {
        return this.getSheetFrame(sheet, frame, null);
    }

    /**
     * Returns a cached console sheet frame, applying optional RGB texture multipliers when creating its texture.
     *
     * @param sheet the sheet path
     * @param frame the frame index
     * @param tint  the RGB multipliers, or {@code null}
     * @return the frame
     * @throws IllegalStateException if no asset set is selected ({@link #begin}, {@link #useAssets})
     * @throws IOException           if the sheet or frame is missing or malformed
     */
    @NotNull Frame getSheetFrame(@NotNull String sheet, int frame, float @Nullable [] tint) throws IOException {
        InterfaceAssets current = this.requireAssets();
        String key = current.getQualityPrefix() + sheet + KEY_SEPARATOR + frame +
                     (tint == null ? "" : KEY_SEPARATOR + tint[0] + TINT_SEPARATOR + tint[OverlayPass.RGBA_GREEN] + TINT_SEPARATOR +
                                          tint[OverlayPass.RGBA_BLUE]);
        Frame cached = this.frames.get(key);

        if (cached != null) {
            return cached;
        }

        InterfaceAssets.ScaledImage image = this.getScaledImage(sheet, frame);
        int texture = this.images.getFrame(current, sheet, frame, tint, image).getTexture();
        Frame created = new Frame(texture,
                                  image.getImage().getWidth() / (float) image.getScale(),
                                  image.getImage().getHeight() / (float) image.getScale(),
                                  this.records.getFrameRecord(sheet, frame));

        this.frames.put(key, created);

        return created;
    }

    /**
     * Returns a plain texture of the current asset set as a frame without a classic record (console images such as the progress bar's
     * {@code pbrempt} and {@code pbrfull}).
     *
     * @param path the texture path without the set prefix
     * @return the frame, sized in interface pixels
     * @throws IllegalStateException if no asset set is selected ({@link #begin}, {@link #useAssets})
     * @throws IOException           if the texture cannot be read
     */
    @NotNull Frame getImageFrame(@NotNull String path) throws IOException {
        InterfaceAssets current = this.requireAssets();
        String key = current.getQualityPrefix() + path;
        Frame cached = this.frames.get(key);

        if (cached != null) {
            return cached;
        }

        InterfaceImageCache.Sprite sprite = this.images.getImage(current, path);
        Frame created = new Frame(sprite.getTexture(), sprite.getWidth(), sprite.getHeight(), null);

        this.frames.put(key, created);

        return created;
    }

    /**
     * Returns a decoded frame of a console sheet of the current asset set (decoded once).
     *
     * @param sheet the sheet path
     * @param frame the frame index
     * @return the decoded frame and its texture scale
     * @throws IllegalStateException if no asset set is selected ({@link #begin}, {@link #useAssets})
     * @throws IOException           if the sheet or frame is missing or malformed
     */
    InterfaceAssets.@NotNull ScaledImage getScaledImage(@NotNull String sheet, int frame) throws IOException {
        InterfaceAssets current = this.requireAssets();
        String key = current.getQualityPrefix() + sheet + KEY_SEPARATOR + frame;
        InterfaceAssets.ScaledImage image = this.decoded.get(key);

        if (image == null) {
            image = current.decodeSheetFrame(sheet, frame);

            this.decoded.put(key, image);
        }

        return image;
    }

    /**
     * @param slot the font slot
     * @return the line height of the slot's font
     */
    @Override
    public float getLineHeight(int slot) {
        return this.fonts.getFont(slot).getLineHeight();
    }

    /**
     * Selects the layout and the asset set of the frames drawn next.
     *
     * @param transform the console layout's interface transform
     * @param assets    the console's interface assets
     */
    void begin(@NotNull InterfaceTransform transform, @NotNull InterfaceAssets assets) {
        this.transform = transform;
        this.assets    = assets;
    }

    /**
     * Selects the asset set of the frames looked up next, without a layout (for sizes and textures only).
     *
     * @param assets the console's interface assets
     */
    void useAssets(@NotNull InterfaceAssets assets) {
        this.assets = assets;
    }

    /**
     * Begins drawing over the whole window, in window pixel coordinates: the interface renderer begins over the framebuffer, and the
     * painter maps the interface pixels of its layout ({@link #begin(InterfaceTransform, InterfaceAssets)}) onto it.
     *
     * @param width  the window framebuffer width in pixels
     * @param height the window framebuffer height in pixels
     */
    @Override
    public void begin(int width, int height) {
        this.renderer.begin(width, height);
    }

    /**
     * @return the width of the drawing coordinates of the current layout: its logical interface width in interface pixels
     * @throws IllegalStateException if no layout is selected ({@link #begin(InterfaceTransform, InterfaceAssets)})
     */
    @Override
    public int getWidth() {
        return (int) this.requireTransform().getLogicalWidth();
    }

    /**
     * @return the height of the drawing coordinates: {@link InterfaceTransform#INTERFACE_HEIGHT} interface pixels
     */
    @Override
    public int getHeight() {
        return InterfaceTransform.INTERFACE_HEIGHT;
    }

    /**
     * Sets the rectangle the following drawing is limited to, or lifts the limit, in interface pixels: the screen rectangle from the truncated
     * screen left and top to the rounded-up screen right and bottom.
     *
     * @param x      the left coordinate
     * @param y      the top coordinate
     * @param width  the width, or a negative value to draw over the whole target again
     * @param height the height
     * @throws IllegalStateException if no layout is selected ({@link #begin(InterfaceTransform, InterfaceAssets)})
     */
    @Override
    public void setClip(int x, int y, int width, int height) {
        if (width < 0) {
            this.renderer.setClip(0, 0, NO_CLIP_SIZE, NO_CLIP_SIZE);

            return;
        }

        InterfaceTransform current = this.requireTransform();
        int screenLeft = (int) current.toScreenX(x);
        int screenTop = (int) current.toScreenY(y);

        this.renderer.setClip(screenLeft,
                              screenTop,
                              (int) Math.ceil(current.toScreenX(x + width)) - screenLeft,
                              (int) Math.ceil(current.toScreenY(y + height)) - screenTop);
    }

    /**
     * Uploads an image as a texture of this canvas: a texture of the interface renderer, drawn in interface pixels.
     *
     * @param width  the image width in pixels
     * @param height the image height in pixels
     * @param colors the row-major pixels, top row first, packed {@code 0xAARRGGBB} with straight alpha as the resource model stores them
     * @return the created {@link Texture} instance, closed by the caller
     * @throws IllegalArgumentException if a dimension is not positive or the pixels do not match the dimensions
     */
    @Override
    public @NotNull Texture uploadTexture(int width, int height, int @NotNull [] colors) {
        return this.renderer.uploadTexture(width, height, colors);
    }

    /**
     * Draws a whole texture into a rectangle, in interface pixels.
     *
     * @param texture the texture of this canvas
     * @param x       the left coordinate
     * @param y       the top coordinate
     * @param width   the drawn width
     * @param height  the drawn height
     * @param color   the packed colour multiplied with the texture
     * @throws IllegalStateException if no layout is selected ({@link #begin(InterfaceTransform, InterfaceAssets)})
     */
    @Override
    public void drawTexture(@NotNull Texture texture, float x, float y, float width, float height, int color) {
        InterfaceTransform current = this.requireTransform();
        float scale = current.getScale();

        this.renderer.drawTexture(texture, current.toScreenX(x), current.toScreenY(y), width * scale, height * scale, color);
    }

    /**
     * Draws part of a texture into a rectangle, in interface pixels.
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
     * @throws IllegalStateException if no layout is selected ({@link #begin(InterfaceTransform, InterfaceAssets)})
     */
    @Override
    public void drawTexture(@NotNull Texture texture, float x, float y, float width, float height,
                            float left, float top, float right, float bottom,
                            int color) {
        InterfaceTransform current = this.requireTransform();
        float scale = current.getScale();

        this.renderer.drawTexture(texture,
                                  current.toScreenX(x), current.toScreenY(y),
                                  width * scale, height * scale,
                                  left, top, right, bottom,
                                  color);
    }

    /**
     * Draws a rectangle outline inside the rectangle, in interface pixels.
     *
     * @param x         the left coordinate
     * @param y         the top coordinate
     * @param width     the width
     * @param height    the height
     * @param thickness the line thickness
     * @param color     the packed colour
     * @throws IllegalStateException if no layout is selected ({@link #begin(InterfaceTransform, InterfaceAssets)})
     */
    @Override
    public void drawRectangle(float x, float y, float width, float height, float thickness, int color) {
        InterfaceTransform current = this.requireTransform();
        float scale = current.getScale();

        this.renderer.drawRectangle(current.toScreenX(x), current.toScreenY(y), width * scale, height * scale, thickness * scale, color);
    }

    /**
     * Draws a straight line of the given thickness, in interface pixels.
     *
     * @param sourceX      the start x
     * @param sourceY      the start y
     * @param destinationX the end x
     * @param destinationY the end y
     * @param thickness    the line thickness
     * @param color        the packed colour
     * @throws IllegalStateException if no layout is selected ({@link #begin(InterfaceTransform, InterfaceAssets)})
     */
    @Override
    public void drawLine(float sourceX, float sourceY, float destinationX, float destinationY, float thickness, int color) {
        InterfaceTransform current = this.requireTransform();

        this.renderer.drawLine(current.toScreenX(sourceX), current.toScreenY(sourceY),
                               current.toScreenX(destinationX), current.toScreenY(destinationY),
                               thickness * current.getScale(),
                               color);
    }

    /**
     * Ends the drawing: the interface renderer flushes its batch.
     */
    @Override
    public void end() {
        this.renderer.end();
    }

    private @NotNull InterfaceTransform requireTransform() {
        InterfaceTransform current = this.transform;

        if (current == null) {
            throw Exceptions.newIllegalStateException("The console painter has no layout.");
        }

        return current;
    }

    private @NotNull InterfaceAssets requireAssets() {
        InterfaceAssets current = this.assets;

        if (current == null) {
            throw Exceptions.newIllegalStateException("The console painter has no assets.");
        }

        return current;
    }

    /**
     * Draws a frame at its size, at the position plus the frame's offset of the sheet's classic {@code .grp} header, as the dialog frame draw does in
     * every asset set.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left
     * @param positionY the interface top
     */
    @Override
    public void drawFrame(DialogPainter.@NotNull Frame frame, float positionX, float positionY) {
        this.drawFrame(frame, positionX, positionY, WHITE);
    }

    /**
     * Draws a frame at its size at the position plus its classic offset in a color.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left
     * @param positionY the interface top
     * @param color     the packed color multiplied with the texture
     */
    void drawFrame(DialogPainter.@NotNull Frame frame, float positionX, float positionY, int color) {
        Frame sheetFrame = (Frame) frame;

        this.draw(sheetFrame, positionX + sheetFrame.offsetX, positionY + sheetFrame.offsetY, sheetFrame.width, sheetFrame.height, color);
    }

    /**
     * Draws a frame into a rectangle, moved by the frame's classic offset as {@link #drawFrame(DialogPainter.Frame, float, float)}.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left
     * @param positionY the interface top
     * @param width     the interface width
     * @param height    the interface height
     */
    @Override
    public void drawFrame(DialogPainter.@NotNull Frame frame, float positionX, float positionY, float width, float height) {
        this.drawFrame(frame, positionX, positionY, width, height, WHITE);
    }

    /**
     * Draws a frame into a rectangle, moved by its classic offset, in a color multiplied with the texture.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left
     * @param positionY the interface top
     * @param width     the interface width
     * @param height    the interface height
     * @param color     the packed {@code 0xRRGGBBAA} color
     */
    @Override
    public void drawFrame(DialogPainter.@NotNull Frame frame, float positionX, float positionY, float width, float height, int color) {
        Frame sheetFrame = (Frame) frame;

        this.draw(sheetFrame, positionX + sheetFrame.offsetX, positionY + sheetFrame.offsetY, width, height, color);
    }

    /**
     * Draws a frame at its size exactly at a position, without the classic frame offset: the plain image draw that the list item painters use for
     * their icons (the file list painter places an {@code glue\create\iCreate} icon from the frame height alone).
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left
     * @param positionY the interface top
     */
    @Override
    public void drawImageFrame(DialogPainter.@NotNull Frame frame, float positionX, float positionY) {
        this.drawImageFrame(frame, positionX, positionY, WHITE);
    }

    /**
     * Draws a frame at its size exactly at a position in a color.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left
     * @param positionY the interface top
     * @param color     the packed color multiplied with the texture
     */
    void drawImageFrame(DialogPainter.@NotNull Frame frame, float positionX, float positionY, int color) {
        Frame sheetFrame = (Frame) frame;

        this.draw(sheetFrame, positionX, positionY, sheetFrame.width, sheetFrame.height, color);
    }

    /**
     * Draws a frame exactly at a position into a rectangle, in a color.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left
     * @param positionY the interface top
     * @param width     the interface width
     * @param height    the interface height
     * @param color     the packed color multiplied with the texture
     */
    void drawImageFrame(DialogPainter.@NotNull Frame frame, float positionX, float positionY, float width, float height, int color) {
        this.draw((Frame) frame, positionX, positionY, width, height, color);
    }

    /**
     * Draws a frame at its classic offset, clipped to the half-open horizontal range from {@code clipLeft} to {@code clipRight}.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left, before the offset
     * @param positionY the interface top, before the offset
     * @param clipLeft  the first visible column
     * @param clipRight the column after the last visible one
     * @throws IllegalStateException if no layout is selected ({@link #begin}) and the frame shows a column
     */
    @Override
    public void drawFrameColumns(DialogPainter.@NotNull Frame frame, float positionX, float positionY, float clipLeft, float clipRight) {
        Frame sheetFrame = (Frame) frame;
        float left = positionX + sheetFrame.offsetX;
        float top = positionY + sheetFrame.offsetY;
        float shownLeft = Math.max(left, clipLeft);
        float shownRight = Math.min(left + sheetFrame.width, clipRight);

        if (shownRight <= shownLeft || sheetFrame.width <= 0) {
            return;
        }

        InterfaceTransform current = this.requireTransform();
        float scale = current.getScale();

        this.renderer.drawTexture(sheetFrame.texture,
                                  current.toScreenX(shownLeft), current.toScreenY(top),
                                  (shownRight - shownLeft) * scale, sheetFrame.height * scale,
                                  (shownLeft - left) / sheetFrame.width, 0,
                                  (shownRight - left) / sheetFrame.width, 1,
                                  WHITE);
    }

    private void draw(@NotNull Frame frame, float positionX, float positionY, float width, float height, int color) {
        InterfaceTransform current = this.requireTransform();
        float scale = current.getScale();

        this.renderer.drawTexture(frame.texture, current.toScreenX(positionX), current.toScreenY(positionY), width * scale, height * scale, color);
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
     * @throws IllegalStateException if no layout is selected ({@link #begin})
     */
    @Override
    public void drawCodeText(int slot, @NotNull String text, float left, float top, float width, float height, int format, int code) {
        InterfaceText.draw(this.renderer, this.requireTransform(), this.fonts.getFont(slot), text, left, top, width, height, format, code, false);
    }

    /**
     * Draws text in a color of its own rather than a color code (the web panels' named colors, such as the lobby's color picker). Painters without
     * free colors draw it in the button code.
     * <p>
     * The in-game painter starts the text in the color; its inline color codes switch to the game's code colors, as the message area draws a chat
     * line from its player color.
     *
     * @param slot   the font slot
     * @param text   the text
     * @param left   the rectangle left
     * @param top    the rectangle top
     * @param width  the rectangle width
     * @param height the rectangle height
     * @param format the text format bits
     * @param color  the packed {@code 0xRRGGBBAA} color
     * @throws IllegalStateException if no layout is selected ({@link #begin})
     */
    @Override
    public void drawColorText(int slot, @NotNull String text, float left, float top, float width, float height, int format, int color) {
        InterfaceText.drawColored(this.renderer,
                                  this.requireTransform(),
                                  this.fonts.getFont(slot),
                                  text,
                                  left, top,
                                  width, height,
                                  format,
                                  color,
                                  InterfaceText.GAME_CODE_COLORS,
                                  false);
    }

    /**
     * Fills an interface rectangle with a color.
     *
     * @param positionX the interface left
     * @param positionY the interface top
     * @param width     the interface width
     * @param height    the interface height
     * @param color     the packed {@code 0xRRGGBBAA} color
     * @throws IllegalStateException if no layout is selected ({@link #begin})
     */
    @Override
    public void fillRectangle(float positionX, float positionY, float width, float height, int color) {
        InterfaceTransform current = this.requireTransform();
        float scale = current.getScale();

        this.renderer.fillRectangle(current.toScreenX(positionX), current.toScreenY(positionY), width * scale, height * scale, color);
    }

    /**
     * Measures the rendered text.
     *
     * @param slot the font slot
     * @param text one line
     * @return its width in interface pixels
     */
    @Override
    public float measure(int slot, @NotNull String text) {
        return this.fonts.getFont(slot).measure(text, true);
    }


    /**
     * Forgets the frames, whose textures the image cache has released, and the decoded frames (the asset set or skin may change).
     */
    void release() {
        this.frames.clear();
        this.decoded.clear();
    }
}
