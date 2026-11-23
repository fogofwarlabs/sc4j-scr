package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.engine.client.display.LetterboxFit;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.Texture;
import org.fogofwarlabs.sc4j.scr.client.ui.WebImageUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.DialogPainter;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.SheetFrameRecords;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.WebPanelStyleUtils;
import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.fogofwarlabs.sc4j.scr.render.ui.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The drawing surface of the glue screens: the interface space of {@code 480} lines ({@code 640} wide in SD, the window's aspect in HD)
 * mapped to the framebuffer, the images of the current asset set ({@code SD/}, {@code HD2/} or the bare HD path, with the
 * locale folder first), the four glue fonts and text in the colors of the current glue palette.
 * <p>
 * The glue canvas is an engine {@link Canvas} whose drawing coordinates are interface pixels: every {@link Canvas} operation
 * (textures, rectangles, outlines, lines, the clip) is scaled and offset onto the interface renderer, so the glue painters and the
 * dialog controls draw through the engine canvas API. Images of the glue's texture caches keep their texture names
 * ({@link #drawImage}, {@link #drawTexture(int, float, float, float, float, int)}): the documented fast path of the GL canvas.
 */
public final class GlueCanvas implements AutoCloseable, Canvas, InterfaceTransform, DialogPainter {
    /**
     * The interface height of every glue screen.
     */
    public static final int INTERFACE_HEIGHT = InterfaceTransform.INTERFACE_HEIGHT;
    /**
     * The SD interface width.
     */
    public static final int INTERFACE_WIDTH = InterfaceTransform.ORIGINAL_WIDTH;
    /**
     * The SD asset prefix.
     */
    public static final @NotNull String SD_PREFIX = "SD/";
    /**
     * The alternate HD asset prefix.
     */
    public static final @NotNull String HD2_PREFIX = "HD2/";
    /**
     * The default HD asset prefix.
     */
    public static final @NotNull String HD_PREFIX = "";

    /**
     * The small-text font slot.
     */
    public static final int SMALL_FONT = 0;
    /**
     * The default font slot.
     */
    public static final int NORMAL_FONT = 1;
    /**
     * The big-text font slot.
     */
    public static final int BIG_FONT = 2;
    /**
     * The bigger-text font slot.
     */
    public static final int BIGGER_FONT = 3;

    /**
     * Left-aligned text.
     */
    public static final int FORMAT_LEFT = InterfaceText.LEFT;
    /**
     * Centered text.
     */
    public static final int FORMAT_CENTER = InterfaceText.CENTER;
    /**
     * Right-aligned text.
     */
    public static final int FORMAT_RIGHT = InterfaceText.RIGHT;
    /**
     * Top-aligned text.
     */
    public static final int FORMAT_TOP = InterfaceText.TOP;
    /**
     * Vertically centered text.
     */
    public static final int FORMAT_MIDDLE = InterfaceText.MIDDLE;
    /**
     * Bottom-aligned text.
     */
    public static final int FORMAT_BOTTOM = InterfaceText.BOTTOM;
    /**
     * Centered when the text is one line, else left aligned ({@link InterfaceText#CENTER_ONE_LINE}).
     */
    public static final int FORMAT_CENTER_ONE_LINE = InterfaceText.CENTER_ONE_LINE;

    /**
     * The clip size that lifts the renderer's drawing limit.
     */
    public static final int NO_CLIP_SIZE = -1;
    /**
     * The packed {@code 0xRRGGBBAA} opaque black that fills the screen behind a glue screen and the borders around the interface.
     */
    public static final int OPAQUE_BLACK = 0x000000FF;
    /**
     * The corner size in interface pixels of the glue popup panel art ({@link GlueScreen#POPUP_PANEL_IMAGE}) drawn as a
     * framed box ({@link #drawPopupPanel}).
     */
    public static final float POPUP_PANEL_BORDER = 8;
    /**
     * The thickness in interface pixels of a one-pixel outline ({@link #drawRectangle}).
     */
    public static final float OUTLINE_THICKNESS = 1;

    private static final int WHITE = 0xFFFFFFFF;
    private static final float SCREEN_COVER = 100000;
    private static final int TINT_CHANNELS = 3;
    private static final int CHANNEL_MAXIMUM = DialogPainter.BYTE_MASK;
    private static final int BORDER_SIDES = 2;
    /**
     * The rows of a bar frame's rounded end kept at their size at its top and at its bottom when the bar is stretched to another height
     * ({@link #drawBar(String, int, float, float, float, float, int)}); the rows between are its straight sides.
     */
    private static final float BAR_CORNER = 4;
    private static final int BAR_SLICE_COUNT = 3;
    private static final int OVERLAY_FACTOR = 2;
    private static final int RECTANGLE_LEFT_INDEX = 0;
    private static final int RECTANGLE_TOP_INDEX = 1;
    private static final int RECTANGLE_WIDTH_INDEX = 2;
    private static final int RECTANGLE_HEIGHT_INDEX = 3;
    private static final int ALPHA_SHIFT = Byte.SIZE * TINT_CHANNELS;
    private static final int GLUE_TILESET = 0;
    private static final int DYNAMIC_TEXTURE_SCALE = 1;
    private static final @NotNull String NO_SKIN_FOLDER = "";
    private static final @NotNull String LEGACY_IMAGE_EXTENSION = ".pcx";
    private static final @NotNull String TEXTURE_EXTENSION = ".DDS";
    private static final @NotNull String KEY_SEPARATOR = "#";
    private static final @NotNull String OVERLAY_KEY_MARKER = "#overlay";
    /**
     * The folder of the web pages' images ({@link #getWebImage}), also the images' cache key prefix.
     */
    private static final @NotNull String WEB_IMAGE_PREFIX = "webui/dist/lib/images/";
    /**
     * The glue popup panel art: the whole {@code 360x200} panel is the frame region, a {@code 40x40} sample at {@code (20, 20)} its inside.
     */
    private static final int POPUP_PANEL_ART_WIDTH = 360;
    private static final int POPUP_PANEL_ART_HEIGHT = 200;
    private static final int POPUP_PANEL_FILL_INSET = 20;
    private static final int POPUP_PANEL_FILL_SIZE = 40;
    private static final float @NotNull [] POPUP_PANEL_REGION = {0, 0, POPUP_PANEL_ART_WIDTH, POPUP_PANEL_ART_HEIGHT};
    private static final float @NotNull [] POPUP_PANEL_FILL = {POPUP_PANEL_FILL_INSET, POPUP_PANEL_FILL_INSET,
                                                               POPUP_PANEL_FILL_SIZE, POPUP_PANEL_FILL_SIZE};

    /**
     * An uploaded image with its size in interface pixels.
     */
    public static final class Image implements DialogPainter.Frame {
        private final int texture;

        private final float width;
        private final float height;

        private final int offsetX;
        private final int offsetY;

        private final float classicWidth;
        private final float classicHeight;

        /**
         * The part of the classic frame offset that the texture already holds as transparent columns and rows (some SD
         * {@code .dds.grp} frames keep it, the other sets crop the frame), taken off every draw.
         */
        private final int bakedX;
        private final int bakedY;

        /**
         * The texture coordinates of the image: the whole texture, or a region of it ({@link #createRegion}).
         */
        private final float u0;
        private final float v0;
        private final float u1;
        private final float v1;

        private Image(int texture, float width, float height) {
            this(texture, width, height, 0, 0, width, height);
        }

        private Image(int texture, float width, float height, int offsetX, int offsetY, float classicWidth, float classicHeight) {
            this(texture, width, height, offsetX, offsetY, classicWidth, classicHeight, 0, 0, 0, 0, 1, 1);
        }

        private Image(int texture,
                      float width, float height,
                      int offsetX, int offsetY,
                      float classicWidth, float classicHeight,
                      int bakedX, int bakedY,
                      float u0, float v0,
                      float u1, float v1) {
            this.texture = texture;

            this.width  = width;
            this.height = height;

            this.offsetX = offsetX;
            this.offsetY = offsetY;

            this.classicWidth  = classicWidth;
            this.classicHeight = classicHeight;

            this.bakedX = bakedX;
            this.bakedY = bakedY;

            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
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
         * Creates a {@link Image} instance for the requested region of the shared texture.
         *
         * @param rectangle a region of the image {@code [left, top, width, height]} in interface pixels
         * @return the created {@link Image} instance sharing the texture
         */
        public @NotNull Image createRegion(float @NotNull [] rectangle) {
            return new Image(this.texture,
                             rectangle[RECTANGLE_WIDTH_INDEX], rectangle[RECTANGLE_HEIGHT_INDEX],
                             0, 0,
                             rectangle[RECTANGLE_WIDTH_INDEX], rectangle[RECTANGLE_HEIGHT_INDEX],
                             0, 0,
                             this.getTextureU(rectangle[RECTANGLE_LEFT_INDEX]), this.getTextureV(rectangle[RECTANGLE_TOP_INDEX]),
                             this.getTextureU(rectangle[RECTANGLE_LEFT_INDEX] + rectangle[RECTANGLE_WIDTH_INDEX]),
                             this.getTextureV(rectangle[RECTANGLE_TOP_INDEX] + rectangle[RECTANGLE_HEIGHT_INDEX]));
        }

        /**
         * @param x a column of the image in interface pixels
         * @return its texture coordinate
         */
        private float getTextureU(float x) {
            return this.u0 + x / this.width * (this.u1 - this.u0);
        }

        /**
         * @param y a row of the image in interface pixels
         * @return its texture coordinate
         */
        private float getTextureV(float y) {
            return this.v0 + y / this.height * (this.v1 - this.v0);
        }
    }

    /**
     * Tests a point against a rectangle.
     *
     * @param x      the interface x
     * @param y      the interface y
     * @param left   the rectangle left
     * @param top    the rectangle top
     * @param width  the rectangle width
     * @param height the rectangle height
     * @return {@code true} if the point is inside, {@code false} otherwise
     */
    public static boolean contains(float x, float y, float left, float top, float width, float height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    /**
     * Creates a {@link Image} instance sharing the cached texture, with its size divided by the scale in interface pixels.
     * A sheet frame retains its classic frame record.
     *
     * @param sprite the cached texture and its scale
     * @param record the classic frame record, or {@code null} for a full texture
     * @return the created {@link Image} instance sharing the cached texture
     */
    private static @NotNull Image createImageFromSprite(InterfaceImageCache.@NotNull Sprite sprite, int @Nullable [] record) {
        float width = sprite.getTextureWidth() / (float) sprite.getScale();
        float height = sprite.getTextureHeight() / (float) sprite.getScale();

        if (record == null) {
            return new Image(sprite.getTexture(), width, height);
        }

        // A texture whose first opaque column (row) lies at or beyond the offset holds the offset itself.
        boolean bakedX = record[SheetFrameRecords.X_INDEX] > 0 && sprite.getOpaqueLeft() / sprite.getScale() >= record[SheetFrameRecords.X_INDEX] &&
                         width >= record[SheetFrameRecords.X_INDEX] + record[SheetFrameRecords.WIDTH_INDEX];
        boolean bakedY = record[SheetFrameRecords.Y_INDEX] > 0 && sprite.getOpaqueTop() / sprite.getScale() >= record[SheetFrameRecords.Y_INDEX] &&
                         height >= record[SheetFrameRecords.Y_INDEX] + record[SheetFrameRecords.HEIGHT_INDEX];

        return new Image(sprite.getTexture(),
                         width, height,
                         record[SheetFrameRecords.X_INDEX], record[SheetFrameRecords.Y_INDEX],
                         record[SheetFrameRecords.WIDTH_INDEX], record[SheetFrameRecords.HEIGHT_INDEX],
                         bakedX ? record[SheetFrameRecords.X_INDEX] : 0, bakedY ? record[SheetFrameRecords.Y_INDEX] : 0,
                         0, 0,
                         1, 1);
    }

    private static @NotNull String normalizeImagePath(@NotNull String path) {
        String normalized = path.replace('\\', '/');

        if (normalized.toLowerCase(Locale.ROOT).endsWith(LEGACY_IMAGE_EXTENSION)) {
            normalized = normalized.substring(0, normalized.length() - LEGACY_IMAGE_EXTENSION.length()) + TEXTURE_EXTENSION;
        }

        return normalized;
    }

    private final @NotNull CascStorage storage;
    private final @NotNull InterfaceRenderer renderer;
    private final @NotNull InterfaceAssets assets;
    private final @NotNull String locale;

    private final @NotNull InterfaceFonts fonts;
    private final @NotNull InterfaceFonts gameFonts;
    private final @NotNull Map<String, Image> images;
    /**
     * The textures of the images, released when the asset set changes.
     */
    private final @NotNull InterfaceImageCache textures;
    /**
     * The textures replaced while they are shown (videos, portraits, previews), released by their owners.
     */
    private final @NotNull InterfaceImageCache dynamicTextures;
    private final @NotNull SheetFrameRecords frameRecords;
    private @Nullable GluePalette palette;
    private boolean gameFontsActive;

    private float interfaceWidth;
    private float scale;
    private float offsetX;
    private float offsetY;

    private boolean originalAspectRatio;

    /**
     * Creates a {@link GlueCanvas} instance.
     *
     * @param resources    the resource provider of the installation
     * @param renderer     the interface renderer
     * @param locale       the text locale, for example {@code enUS}
     * @param fonts        the client's glue font set (owned by the client)
     * @param gameFonts    the client's game font set, which the game's dialogs shown through the glue draw in (owned by the client)
     * @param frameRecords the client's classic frame records (owned by the client)
     * @throws IOException if the interface assets cannot be read
     */
    public GlueCanvas(@NotNull ScrResourceProvider resources,
                      @NotNull InterfaceRenderer renderer,
                      @NotNull String locale,
                      @NotNull InterfaceFonts fonts, @NotNull InterfaceFonts gameFonts,
                      @NotNull SheetFrameRecords frameRecords) throws IOException {
        this.storage  = resources.getStorage();
        this.renderer = renderer;
        this.assets   = InterfaceAssets.load(resources, GLUE_TILESET);
        this.locale   = locale;

        this.fonts           = fonts;
        this.gameFonts       = gameFonts;
        this.images          = new HashMap<>();
        this.textures        = new InterfaceImageCache(renderer);
        this.dynamicTextures = new InterfaceImageCache(renderer);
        this.frameRecords    = frameRecords;
        this.palette         = null;
        this.gameFontsActive = false;

        this.interfaceWidth = INTERFACE_WIDTH;
        this.scale          = 1;
        this.offsetX        = 0;
        this.offsetY        = 0;

        this.originalAspectRatio = false;
    }

    /**
     * @return the installed game storage
     */
    public @NotNull CascStorage getStorage() {
        return this.storage;
    }

    /**
     * @return the interface renderer
     */
    public @NotNull InterfaceRenderer getRenderer() {
        return this.renderer;
    }

    /**
     * @return the current asset set prefix
     */
    public @NotNull String getQualityPrefix() {
        return this.assets.getQualityPrefix();
    }

    /**
     * @return the current asset set
     */
    public AssetResolver.@NotNull AssetSet getAssetSet() {
        return this.assets.getAssetSet();
    }

    /**
     * @return {@code true} if the current asset set is an HD set, {@code false} otherwise
     */
    public boolean isHighDefinition() {
        return this.assets.getAssetSet().isHd();
    }

    /**
     * @return the texture pixels per interface pixel of the current asset set ({@code 1}, {@code 2} or {@code 4})
     */
    public int getAssetScale() {
        return this.assets.getAssetSet().getScale();
    }

    /**
     * @return the interface width
     */
    public float getInterfaceWidth() {
        return this.interfaceWidth;
    }

    /**
     * @return the logical width {@code W} of the interface pixels the transform maps ({@link org.fogofwarlabs.sc4j.engine.client.host.AspectRange#getLogicalWidth}): {@code 640}, or wider for the widescreen interface
     */
    @Override
    public float getLogicalWidth() {
        return this.interfaceWidth;
    }

    /**
     * @return the width of the drawing coordinates of the current drawing: the interface width in interface pixels
     */
    @Override
    public int getWidth() {
        return (int) this.interfaceWidth;
    }

    /**
     * @return the height of the drawing coordinates of the current drawing: {@link #INTERFACE_HEIGHT} interface pixels
     */
    @Override
    public int getHeight() {
        return INTERFACE_HEIGHT;
    }

    /**
     * @return the integer left offset of a centered original-width dialog
     */
    public int getDialogLeft() {
        return InterfaceTransform.getCenteredDialogLeft(this.interfaceWidth);
    }

    /**
     * @return the interface assets of the current set
     */
    public @NotNull InterfaceAssets getAssets() {
        return this.assets;
    }

    /**
     * @return the text locale, for example {@code koKR}
     */
    public @NotNull String getLocale() {
        return this.locale;
    }

    /**
     * @return the screen pixels per interface pixel
     */
    @Override
    public float getScale() {
        return this.scale;
    }

    /**
     * Returns an image of the current asset set, reading legacy {@code .pcx} dialog paths as {@code .DDS} assets.
     * The locale folder ({@code locales/<locale>/Assets/}) is tried first.
     *
     * @param path the path as a dialog names it, for example {@code glue\mainmenu\eTail.dds}
     * @return the image
     * @throws IOException if no variant exists
     */
    public @NotNull Image getImage(@NotNull String path) throws IOException {
        String normalized = normalizeImagePath(path);
        String key = this.assets.getQualityPrefix() + normalized;
        Image image = this.images.get(key);

        if (image != null) {
            return image;
        }

        InterfaceImageCache.Sprite sprite = this.textures.getSprite(key, () -> this.assets.decodeTexture(normalized));

        image = createImageFromSprite(sprite, null);
        this.images.put(key, image);

        return image;
    }

    /**
     * @param sheet the sheet path, for example {@code glue/palnl/dlg.dds.grp}
     * @param frame the frame index
     * @return the frame
     * @throws IOException if the sheet or frame is missing or malformed
     */
    @Override
    public @NotNull Image getSheetFrame(@NotNull String sheet, int frame) throws IOException {
        String normalized = sheet.replace('\\', '/');
        String key = this.assets.getQualityPrefix() + normalized + KEY_SEPARATOR + frame;
        Image image = this.images.get(key);

        if (image != null) {
            return image;
        }

        image = createImageFromSprite(this.textures.getFrame(this.assets, normalized, frame, null),
                                      this.frameRecords.getFrameRecord(normalized, frame));
        this.images.put(key, image);

        return image;
    }

    /**
     * Returns an image of SC:R's web pages ({@code webui/dist/lib/images}), which the pages draw at a CSS size
     * ({@link #drawFrame(Frame, float, float, float, float)}).
     *
     * @param name the file name, for example {@code down_arrow_yellow_32.png}
     * @return the image
     * @throws IOException if the image is missing or unreadable, or the painter has no web images
     */
    @Override
    public @NotNull Image getWebImage(@NotNull String name) throws IOException {
        String key = WEB_IMAGE_PREFIX + name;
        Image image = this.images.get(key);

        if (image != null) {
            return image;
        }

        InterfaceImageCache.Sprite sprite = this.textures.getImage(key, () -> WebImageUtils.toInterfaceImage(this.readWebImage(name)));

        image = createImageFromSprite(sprite, null);
        this.images.put(key, image);

        return image;
    }

    /**
     * Returns an image of the web pages under a layer of one color blended with {@code mix-blend-mode: overlay} over an
     * opaque black box (the hotkey page's {@code .actionTint} over an {@code .action}): each channel of the image composed
     * over black becomes {@code OVERLAY_FACTOR * backdrop * color} up to half intensity and the screen of the two above.
     *
     * @param name    the file name in {@code webui/dist/lib/images}
     * @param overlay the packed {@code 0xRRGGBBAA} color of the layer (its alpha is ignored)
     * @return the blended, opaque image
     * @throws IOException if the image is missing or unreadable
     */
    public @NotNull Image getWebImage(@NotNull String name, int overlay) throws IOException {
        String key = WEB_IMAGE_PREFIX + name + OVERLAY_KEY_MARKER + Integer.toHexString(overlay);
        Image image = this.images.get(key);

        if (image != null) {
            return image;
        }

        InterfaceImageCache.Sprite sprite = this.textures.getImage(key, () -> {
            BufferedImage decoded = this.readWebImage(name);
            byte[] rgba = new byte[decoded.getWidth() * decoded.getHeight() * OverlayPass.RGBA_BYTES];

            for (int pixel = 0; pixel < decoded.getWidth() * decoded.getHeight(); pixel++) {
                int argb = decoded.getRGB(pixel % decoded.getWidth(), pixel / decoded.getWidth());
                int alpha = argb >>> ALPHA_SHIFT;

                for (int channel = 0; channel < TINT_CHANNELS; channel++) {
                    int shift = Byte.SIZE * (TINT_CHANNELS - 1 - channel);
                    int backdrop = (argb >>> shift & CHANNEL_MAXIMUM) * alpha / CHANNEL_MAXIMUM;
                    int source = overlay >>> Byte.SIZE * (TINT_CHANNELS - channel) & CHANNEL_MAXIMUM;
                    int blended = OVERLAY_FACTOR * backdrop <= CHANNEL_MAXIMUM
                                  ? OVERLAY_FACTOR * backdrop * source / CHANNEL_MAXIMUM
                                  : CHANNEL_MAXIMUM - OVERLAY_FACTOR * (CHANNEL_MAXIMUM - backdrop) * (CHANNEL_MAXIMUM - source) / CHANNEL_MAXIMUM;

                    rgba[pixel * OverlayPass.RGBA_BYTES + channel] = (byte) blended;
                }

                rgba[pixel * OverlayPass.RGBA_BYTES + OverlayPass.RGBA_ALPHA] = (byte) CHANNEL_MAXIMUM;
            }

            return new InterfaceImage(decoded.getWidth(), decoded.getHeight(), rgba);
        });

        image = createImageFromSprite(sprite, null);
        this.images.put(key, image);

        return image;
    }

    private @NotNull BufferedImage readWebImage(@NotNull String name) throws IOException {
        BufferedImage decoded = WebImageUtils.read(this.storage.readFile(WEB_IMAGE_PREFIX + name));

        if (decoded == null) {
            throw Exceptions.newIOException("Unreadable web image {0}", name);
        }

        return decoded;
    }

    /**
     * Returns a sheet frame tinted by the color and factor, retaining its classic frame placement.
     *
     * @param path   the sheet path
     * @param frame  the frame index
     * @param color  the packed {@code 0xRRGGBBAA} color
     * @param factor the factor multiplied with each color channel of the tint
     * @return the tinted frame image
     * @throws IOException if the sheet or frame is missing or malformed
     */
    public @NotNull Image getTintedSheetFrame(@NotNull String path, int frame, int color, float factor) throws IOException {
        String normalized = path.replace('\\', '/');
        String key = this.assets.getQualityPrefix() + normalized + KEY_SEPARATOR + frame + KEY_SEPARATOR + Integer.toHexString(color) +
                     KEY_SEPARATOR + factor;
        Image image = this.images.get(key);

        if (image != null) {
            return image;
        }

        float[] tint = new float[TINT_CHANNELS];

        for (int channel = 0; channel < TINT_CHANNELS; channel++) {
            tint[channel] = factor * (color >>> (Integer.SIZE - Byte.SIZE * (channel + 1)) & CHANNEL_MAXIMUM) / CHANNEL_MAXIMUM;
        }

        image = createImageFromSprite(this.textures.getFrame(this.assets, normalized, frame, tint),
                                      this.frameRecords.getFrameRecord(normalized, frame));
        this.images.put(key, image);

        return image;
    }

    /**
     * @param slot a font slot
     * @return the font
     */
    public @NotNull InterfaceFont getFont(int slot) {
        return this.gameFontsActive ? this.gameFonts.getFont(slot) : this.fonts.getFont(slot);
    }

    /**
     * @param slot the font slot
     * @return the line height of the slot's font
     */
    @Override
    public float getLineHeight(int slot) {
        return this.getFont(slot).getLineHeight();
    }

    /**
     * Returns the height of one line of a text: the line box of a web text slot ({@link WebPanelStyleUtils#getFontSlot}), which can
     * depend on the faces the text takes; the slot's line height otherwise.
     *
     * @param slot the font slot
     * @param text the line
     * @return the height in interface pixels
     */
    @Override
    public float getLineHeight(int slot, @NotNull String text) {
        return this.getFont(slot).getLineStep(text, InterfaceText.LINE_GAP);
    }

    /**
     * Returns where the baseline of a line of text lies below the line's top: in a web text slot the baseline of the line
     * box the text takes, so that spans of one line that take different faces share it.
     *
     * @param slot the font slot
     * @param text the line
     * @return the baseline in interface pixels, {@code 0} where the painter does not know it
     */
    @Override
    public float getBaseline(int slot, @NotNull String text) {
        return this.getFont(slot).getBaseline(text);
    }

    /**
     * @param path an image path, or {@code null}
     * @return {@code true} if the storage has it in some variant, {@code false} otherwise
     */
    public boolean hasImage(@Nullable String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }

        return this.createAssetResolver().resolve(normalizeImagePath(path)) != null;
    }

    private @NotNull AssetResolver createAssetResolver() {
        return new AssetResolver(this.storage, this.assets.getAssetSet(), NO_SKIN_FOLDER, this.locale);
    }


    /**
     * Sets the asset set and fits the interface into the frame's surface: the interface ({@code 640} wide in SD and with the original aspect
     * ratio setting {@code OriginalAspectRatio}, else as wide as the framebuffer's aspect ratio allows) is centred on the surface's logical area
     * ({@link LetterboxFit}) and drawn in framebuffer pixels. The text is drawn at the framebuffer's resolution.
     *
     * @param qualityPrefix       {@link #SD_PREFIX}, {@link #HD2_PREFIX} or {@link #HD_PREFIX}
     * @param surface             the interface surface of the frame
     * @param originalAspectRatio whether the game keeps the {@code 4:3} screen
     */
    public void setViewport(@NotNull String qualityPrefix, @NotNull UiSurface surface, boolean originalAspectRatio) {
        this.originalAspectRatio = originalAspectRatio;

        if (!qualityPrefix.equals(this.assets.getQualityPrefix())) {
            this.assets.setAssetSet(AssetResolver.AssetSet.ofPrefix(qualityPrefix));
            this.releaseImages();
        }

        boolean highDefinition = !qualityPrefix.equals(SD_PREFIX);

        // Share the interface-width calculation with the in-game console.
        this.interfaceWidth = InterfaceTransform.getInterfaceWidth(highDefinition && !this.originalAspectRatio,
                                                                   surface.getFramebufferWidth(), surface.getFramebufferHeight());

        LetterboxFit fit = LetterboxFit.of(surface, this.interfaceWidth, INTERFACE_HEIGHT, LetterboxFit.CENTER, LetterboxFit.CENTER)
                                       .toFramebuffer(surface);

        this.scale   = fit.getScale();
        this.offsetX = fit.getLeft();
        this.offsetY = fit.getTop();

        this.fonts.setViewport(AssetResolver.AssetSet.ofPrefix(qualityPrefix), surface.getFramebufferWidth(), surface.getFramebufferHeight());
        this.gameFonts.setViewport(AssetResolver.AssetSet.ofPrefix(qualityPrefix), surface.getFramebufferWidth(), surface.getFramebufferHeight());
    }

    /**
     * Sets whether control text uses the game font set until the next selection.
     *
     * @param gameFontsActive whether the game's font set is used
     */
    public void setGameFontsActive(boolean gameFontsActive) {
        this.gameFontsActive = gameFontsActive;
    }

    /**
     * Sets the palette whose color codes {@link #drawCodeText} uses: the palette of the screen being drawn.
     *
     * @param palette the glue palette
     */
    public void setPalette(@NotNull GluePalette palette) {
        this.palette = palette;
    }

    /**
     * Sets an interface rectangle as the drawing limit, or lifts the limit.
     *
     * @param left   the interface left
     * @param top    the interface top
     * @param width  the interface width, or a negative value to lift the limit
     * @param height the interface height
     */
    public void setClip(float left, float top, float width, float height) {
        if (width < 0) {
            this.renderer.setClip(0, 0, NO_CLIP_SIZE, NO_CLIP_SIZE);

            return;
        }

        int screenLeft = (int) this.toScreenX(left);
        int screenTop = (int) this.toScreenY(top);

        this.renderer.setClip(screenLeft, screenTop,
                              (int) Math.ceil(this.toScreenX(left + width)) - screenLeft,
                              (int) Math.ceil(this.toScreenY(top + height)) - screenTop);
    }

    /**
     * Sets the rectangle the following drawing is limited to, or lifts the limit, in interface pixels
     * ({@link #setClip(float, float, float, float)}).
     *
     * @param x      the left coordinate
     * @param y      the top coordinate
     * @param width  the width, or a negative value to draw over the whole target again
     * @param height the height
     */
    @Override
    public void setClip(int x, int y, int width, int height) {
        this.setClip((float) x, (float) y, (float) width, (float) height);
    }


    /**
     * Converts an interface x to a screen x.
     *
     * @param x an interface x
     * @return the screen x
     */
    @Override
    public float toScreenX(float x) {
        return this.offsetX + x * this.scale;
    }

    /**
     * Converts an interface y to a screen y.
     *
     * @param y an interface y
     * @return the screen y
     */
    @Override
    public float toScreenY(float y) {
        return this.offsetY + y * this.scale;
    }

    /**
     * Converts a framebuffer column to an interface column.
     *
     * @param x a framebuffer x
     * @return the interface x
     */
    public float toInterfaceX(float x) {
        return (x - this.offsetX) / this.scale;
    }

    /**
     * Converts a framebuffer row to an interface row.
     *
     * @param y a framebuffer y
     * @return the interface y
     */
    public float toInterfaceY(float y) {
        return (y - this.offsetY) / this.scale;
    }

    /**
     * Begins drawing over the whole window, in window pixel coordinates: the interface renderer begins over the framebuffer, and the
     * glue maps its interface pixels onto it ({@link #setViewport}). Over a game the glue begins on its own target instead
     * ({@link GlueInterface}).
     *
     * @param width  the window framebuffer width in pixels
     * @param height the window framebuffer height in pixels
     */
    @Override
    public void begin(int width, int height) {
        this.renderer.begin(width, height);
    }

    /**
     * Clears the framebuffer to black.
     */
    public void clear() {
        this.renderer.fillRectangle(0, 0, SCREEN_COVER, SCREEN_COVER, OPAQUE_BLACK);
    }

    /**
     * Reads a glue video through the current localized asset resolver.
     *
     * @param path the path as a dialog names it
     * @return the file bytes
     * @throws IOException if no variant exists
     */
    public byte @NotNull [] readVideo(@NotNull String path) throws IOException {
        return this.readLocalized(normalizeImagePath(path));
    }

    /**
     * Returns the bytes of a localized asset of the current asset set through the shared resolver
     * ({@link AssetResolver#readLocalized}), such as the subtitles of a cinematic.
     *
     * @param path the path
     * @return the file bytes
     * @throws IOException if no variant exists
     */
    public byte @NotNull [] readLocalized(@NotNull String path) throws IOException {
        return this.createAssetResolver().readLocalized(path);
    }

    /**
     * Replaces the texture of a dynamic image (a video frame, a portrait frame, a map preview) through the shared
     * texture cache ({@link InterfaceImageCache#update}).
     *
     * @param key    the owner's key of the image
     * @param width  the width in pixels
     * @param height the height in pixels
     * @param rgba   the pixels
     * @return the texture name
     */
    public int updateTexture(@NotNull String key, int width, int height, byte @NotNull [] rgba) {
        return this.dynamicTextures.update(key, width, height, rgba, DYNAMIC_TEXTURE_SCALE).getTexture();
    }

    /**
     * Releases the texture of a dynamic image ({@link #updateTexture}).
     *
     * @param key the owner's key of the image
     */
    public void releaseTexture(@NotNull String key) {
        this.dynamicTextures.release(key);
    }

    /**
     * Draws an image with its top-left at an interface position.
     *
     * @param image the image
     * @param x     the interface left
     * @param y     the interface top
     */
    public void drawImage(@NotNull Image image, float x, float y) {
        this.drawImage(image, x, y, image.width, image.height);
    }

    /**
     * Draws an image stretched into an interface rectangle.
     *
     * @param image  the image
     * @param x      the interface left
     * @param y      the interface top
     * @param width  the interface width
     * @param height the interface height
     */
    public void drawImage(@NotNull Image image, float x, float y, float width, float height) {
        this.drawImage(image, x, y, width, height, WHITE);
    }

    /**
     * Draws an image stretched into an interface rectangle in a color multiplied with its texels.
     *
     * @param image  the image
     * @param x      the interface left
     * @param y      the interface top
     * @param width  the interface width
     * @param height the interface height
     * @param color  the packed {@code 0xRRGGBBAA} color
     */
    public void drawImage(@NotNull Image image, float x, float y, float width, float height, int color) {
        if (image.u0 == 0 && image.v0 == 0 && image.u1 == 1 && image.v1 == 1) {
            this.renderer.drawTexture(image.texture, this.toScreenX(x), this.toScreenY(y), width * this.scale, height * this.scale, color);
        } else if (width > 0 && height > 0) {
            this.renderer.drawTexture(image.texture,
                                      this.toScreenX(x), this.toScreenY(y),
                                      width * this.scale, height * this.scale,
                                      image.u0, image.v0,
                                      image.u1, image.v1,
                                      color);
        }
    }

    /**
     * Draws the part of an image between two interface columns, at its size.
     *
     * @param image     the image
     * @param x         the image left
     * @param y         the image top
     * @param clipLeft  the first visible column
     * @param clipRight the column after the last visible one
     */
    public void drawImageColumns(@NotNull Image image, float x, float y, float clipLeft, float clipRight) {
        float visibleLeft = Math.max(x, clipLeft);
        float visibleRight = Math.min(x + image.width, clipRight);

        if (visibleRight <= visibleLeft) {
            return;
        }

        this.renderer.drawTexture(image.texture,
                                  this.toScreenX(visibleLeft), this.toScreenY(y),
                                  (visibleRight - visibleLeft) * this.scale, image.height * this.scale,
                                  image.getTextureU(visibleLeft - x), image.v0,
                                  image.getTextureU(visibleRight - x), image.v1,
                                  WHITE);
    }

    /**
     * Draws a framed box from a region of a panel image: the region's corners at their size, its edges stretched along the box and a sample of its
     * inside stretched over the box's inside (a frame of any size from the fixed-size glue popup art).
     *
     * @param image  the panel image, for example {@code glue\palmm\pDPopup.pcx}
     * @param region the frame region in the image {@code [left, top, width, height]} (interface pixels)
     * @param fill   the inside sample in the image {@code [left, top, width, height]}
     * @param border the corner size in interface pixels
     * @param x      the box left
     * @param y      the box top
     * @param width  the box width
     * @param height the box height
     */
    public void drawFramedBox(@NotNull Image image,
                              float @NotNull [] region, float @NotNull [] fill,
                              float border,
                              float x, float y,
                              float width, float height) {
        float left = region[RECTANGLE_LEFT_INDEX];
        float top = region[RECTANGLE_TOP_INDEX];
        float right = region[RECTANGLE_LEFT_INDEX] + region[RECTANGLE_WIDTH_INDEX] - border;
        float bottom = region[RECTANGLE_TOP_INDEX] + region[RECTANGLE_HEIGHT_INDEX] - border;
        float middleWidth = region[RECTANGLE_WIDTH_INDEX] - BORDER_SIDES * border;
        float middleHeight = region[RECTANGLE_HEIGHT_INDEX] - BORDER_SIDES * border;
        Image[] pieces = {
                image.createRegion(new float[]{left, top, border, border}),
                image.createRegion(new float[]{left + border, top, middleWidth, border}),
                image.createRegion(new float[]{right, top, border, border}),

                image.createRegion(new float[]{left, top + border, border, middleHeight}),
                image.createRegion(fill),
                image.createRegion(new float[]{right, top + border, border, middleHeight}),

                image.createRegion(new float[]{left, bottom, border, border}),
                image.createRegion(new float[]{left + border, bottom, middleWidth, border}),
                image.createRegion(new float[]{right, bottom, border, border})
        };

        this.drawNineSlice(pieces,
                           new float[]{x, x + border, x + width - border, x + width},
                           new float[]{y, y + border, y + height - border, y + height},
                           WHITE);
    }

    /**
     * Draws a framed box from the glue popup panel art ({@link GlueScreen#POPUP_PANEL_IMAGE}) with
     * {@link #POPUP_PANEL_BORDER} corners ({@link #drawFramedBox}).
     *
     * @param panel  the popup panel image
     * @param x      the box left
     * @param y      the box top
     * @param width  the box width
     * @param height the box height
     */
    public void drawPopupPanel(@NotNull Image panel, float x, float y, float width, float height) {
        this.drawFramedBox(panel, POPUP_PANEL_REGION, POPUP_PANEL_FILL, POPUP_PANEL_BORDER, x, y, width, height);
    }

    /**
     * Draws the left, middle and right frames of a three-slice bar over a rectangle: at the frames' height as
     * {@link #drawBar(String, int, float, float, float, int)}; at another height each frame's top and bottom {@link #BAR_CORNER} rows keep
     * their size and the rows between stretch over the rest, the ends keep their width (half the rectangle each when it is narrower than
     * two tiles) and the middle stretches between them.
     *
     * @param sheet     the sheet path
     * @param frame     the left frame
     * @param positionX the bar left
     * @param positionY the bar top
     * @param width     the bar width
     * @param height    the bar height
     * @param tile      the tile width
     * @throws IOException if the sheet cannot be read
     */
    public void drawBar(@NotNull String sheet, int frame, float positionX, float positionY, float width, float height, int tile) throws IOException {
        Image start = this.getSheetFrame(sheet, frame);

        if (height == start.getHeight()) {
            this.drawBar(sheet, frame, positionX, positionY, width, tile);

            return;
        }

        Image middle = this.getSheetFrame(sheet, frame + 1);
        Image end = this.getSheetFrame(sheet, frame + BAR_SLICE_COUNT - 1);
        float endWidth = Math.min(tile, (float) Math.floor(width / BORDER_SIDES));
        float corner = Math.min(BAR_CORNER, start.getHeight() / BORDER_SIDES);
        float[] sourceTops = {0, corner, start.getHeight() - corner};
        float[] sourceHeights = {corner, start.getHeight() - BORDER_SIDES * corner, corner};
        Image[] columns = {start, middle, end};
        float[] sourceLefts = {0, 0, end.getWidth() - endWidth};
        float[] sourceWidths = {endWidth, middle.getWidth(), endWidth};
        Image[] pieces = new Image[BAR_SLICE_COUNT * BAR_SLICE_COUNT];

        for (int row = 0; row < BAR_SLICE_COUNT; row++) {
            for (int column = 0; column < BAR_SLICE_COUNT; column++) {
                pieces[row * BAR_SLICE_COUNT + column] = columns[column].createRegion(new float[]{sourceLefts[column], sourceTops[row],
                                                                                                  sourceWidths[column], sourceHeights[row]});
            }
        }

        this.drawNineSlice(pieces,
                           new float[]{positionX, positionX + endWidth, positionX + width - endWidth, positionX + width},
                           new float[]{positionY, positionY + corner, positionY + height - corner, positionY + height},
                           WHITE);
    }

    /**
     * Draws a rectangle outline inside the rectangle, in interface pixels; a one-pixel outline has {@link #OUTLINE_THICKNESS}.
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
        this.renderer.drawRectangle(this.toScreenX(x), this.toScreenY(y), width * this.scale, height * this.scale, thickness * this.scale, color);
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
     */
    @Override
    public void drawLine(float sourceX, float sourceY, float destinationX, float destinationY, float thickness, int color) {
        this.renderer.drawLine(this.toScreenX(sourceX), this.toScreenY(sourceY),
                               this.toScreenX(destinationX), this.toScreenY(destinationY),
                               thickness * this.scale,
                               color);
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
     */
    @Override
    public void drawTexture(@NotNull Texture texture, float x, float y, float width, float height, int color) {
        this.renderer.drawTexture(texture, this.toScreenX(x), this.toScreenY(y), width * this.scale, height * this.scale, color);
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
     */
    @Override
    public void drawTexture(@NotNull Texture texture, float x, float y, float width, float height,
                            float left, float top, float right, float bottom,
                            int color) {
        this.renderer.drawTexture(texture,
                                  this.toScreenX(x), this.toScreenY(y),
                                  width * this.scale, height * this.scale,
                                  left, top, right, bottom,
                                  color);
    }

    /**
     * Draws a texture into an interface rectangle.
     *
     * @param texture the texture name
     * @param x       the interface left
     * @param y       the interface top
     * @param width   the interface width
     * @param height  the interface height
     * @param color   the packed color multiplied with the texture
     */
    public void drawTexture(int texture, float x, float y, float width, float height, int color) {
        this.renderer.drawTexture(texture, this.toScreenX(x), this.toScreenY(y), width * this.scale, height * this.scale, color);
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
    @Override
    public void fillRectangle(float positionX, float positionY, float width, float height, int color) {
        this.renderer.fillRectangle(this.toScreenX(positionX), this.toScreenY(positionY), width * this.scale, height * this.scale, color);
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
        return this.getFont(slot).measure(text, true);
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
     * @throws IllegalStateException if no glue palette is set
     */
    @Override
    public void drawCodeText(int slot, @NotNull String text, float left, float top, float width, float height, int format, int code) {
        GluePalette palette = this.requirePalette();

        this.drawText(slot, text, left, top, width, height, format, palette.getCodeColor(code), palette);
    }

    /**
     * Draws text in a color of its own rather than a color code (the web panels' named colors, such as the lobby's
     * color picker). Painters without free colors draw it in the button code.
     *
     * @param slot   the font slot
     * @param text   the text
     * @param left   the rectangle left
     * @param top    the rectangle top
     * @param width  the rectangle width
     * @param height the rectangle height
     * @param format the text format bits
     * @param color  the packed {@code 0xRRGGBBAA} color
     * @throws IllegalStateException if no glue palette is set
     */
    @Override
    public void drawColorText(int slot, @NotNull String text, float left, float top, float width, float height, int format, int color) {
        GluePalette palette = this.requirePalette();

        this.drawText(slot, text, left, top, width, height, format, color, palette);
    }

    /**
     * Draws the nearer of two palette codes by default; painters can override this method to blend them.
     * <p>
     * This painter blends the two colors channel by channel by the weight, rounding each channel.
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
     * @throws IllegalStateException if no glue palette is set
     */
    @Override
    public void drawBlendedCodeText(int slot,
                                    @NotNull String text,
                                    float left, float top, float width, float height,
                                    int format,
                                    int from, int to,
                                    float weight) {
        GluePalette palette = this.requirePalette();
        int sourceColor = palette.getCodeColor(from);
        int destinationColor = palette.getCodeColor(to);
        int blended = 0;

        for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
            int start = sourceColor >>> shift & CHANNEL_MAXIMUM;
            int end = destinationColor >>> shift & CHANNEL_MAXIMUM;

            blended |= Math.round(start + (end - start) * weight) << shift;
        }

        this.drawText(slot, text, left, top, width, height, format, blended, palette);
    }

    private @NotNull GluePalette requirePalette() {
        GluePalette palette = this.palette;

        if (palette == null) {
            throw Exceptions.newIllegalStateException("No glue palette is set for the control text.");
        }

        return palette;
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
        Image image = (Image) frame;

        this.drawImage(image, positionX + image.offsetX - image.bakedX, positionY + image.offsetY - image.bakedY);
    }

    /**
     * Draws a frame into a rectangle, moved by the frame's classic offset as {@link #drawFrame(Frame, float, float)}.
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
     * Draws a frame into a rectangle, moved by its classic offset, in a color multiplied with the texture. The default
     * ignores the color; painters that can tint override it.
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
        Image image = (Image) frame;

        this.drawImage(image, positionX + image.offsetX - image.bakedX, positionY + image.offsetY - image.bakedY, width, height, color);
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
        // Draw the frame pixels at the position, subtracting any offset already baked into the texture.
        Image image = (Image) frame;

        this.drawImage(image, positionX - image.bakedX, positionY - image.bakedY);
    }

    /**
     * Draws a frame at its classic offset, clipped to the half-open horizontal range from {@code clipLeft} to {@code clipRight}.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left, before the offset
     * @param positionY the interface top, before the offset
     * @param clipLeft  the first visible column
     * @param clipRight the column after the last visible one
     */
    @Override
    public void drawFrameColumns(DialogPainter.@NotNull Frame frame, float positionX, float positionY, float clipLeft, float clipRight) {
        Image image = (Image) frame;

        this.drawImageColumns(image, positionX + image.offsetX - image.bakedX, positionY + image.offsetY - image.bakedY, clipLeft, clipRight);
    }

    /**
     * Draws text with a font and color table of its own (a game dialog shown through the glue, such as the fatal notice
     * box, keeps the game's fonts and {@code tfontgam} colors).
     *
     * @param font       the font
     * @param text       the text
     * @param left       the rectangle left
     * @param top        the rectangle top
     * @param width      the rectangle width
     * @param height     the rectangle height
     * @param format     the {@code FORMAT_*} bits
     * @param color      the packed starting color
     * @param codeColors the packed colors of the inline codes
     */
    public void drawText(@NotNull InterfaceFont font,
                         @NotNull String text,
                         float left, float top,
                         float width, float height,
                         int format,
                         int color,
                         int @NotNull [] codeColors) {
        InterfaceText.drawColored(this.renderer, this, font, text, left, top, width, height, format, color, codeColors, false);
    }

    /**
     * Draws text using the selected font slot and the supplied palette for inline color codes.
     *
     * @param slot    the font slot
     * @param text    the text
     * @param left    the rectangle left
     * @param top     the rectangle top
     * @param width   the rectangle width
     * @param height  the rectangle height
     * @param format  the text format bits
     * @param color   the packed starting color
     * @param palette the color table for inline codes
     */
    public void drawText(int slot,
                         @NotNull String text,
                         float left, float top,
                         float width, float height,
                         int format,
                         int color,
                         @NotNull GluePalette palette) {
        InterfaceText.drawColored(this.renderer, this,
                                  this.getFont(slot), text,
                                  left, top, width, height,
                                  format,
                                  color, palette.getCodeColors(),
                                  false);
    }

    /**
     * Covers the framebuffer outside the interface area with black: the bars of a window wider than {@code 16:9} (or of the original aspect ratio),
     * where the game's viewport ends. Art that reaches past the interface edges, such as the right-anchored {@code glue\campaign\pBotL}
     * ({@code 565} interface pixels wide in HD for a {@code 436} wide control), is cut there as by the viewport.
     *
     * @param framebufferWidth  the framebuffer width
     * @param framebufferHeight the framebuffer height
     */
    public void coverOutsideInterface(int framebufferWidth, int framebufferHeight) {
        float right = this.toScreenX(this.interfaceWidth);
        float bottom = this.toScreenY(INTERFACE_HEIGHT);

        if (this.offsetX > 0) {
            this.renderer.fillRectangle(0, 0, this.offsetX, framebufferHeight, OPAQUE_BLACK);
        }

        if (right < framebufferWidth) {
            this.renderer.fillRectangle(right, 0, framebufferWidth - right, framebufferHeight, OPAQUE_BLACK);
        }

        if (this.offsetY > 0) {
            this.renderer.fillRectangle(0, 0, framebufferWidth, this.offsetY, OPAQUE_BLACK);
        }

        if (bottom < framebufferHeight) {
            this.renderer.fillRectangle(0, bottom, framebufferWidth, framebufferHeight - bottom, OPAQUE_BLACK);
        }
    }

    /**
     * Submits the pending drawing of the interface renderer.
     */
    @Override
    public void end() {
        this.renderer.end();
    }

    private void releaseImages() {
        this.textures.releaseAll();
        this.images.clear();
    }

    /**
     * {@return a string representation of the object}
     * <p>
     * Satisfying this method's contract implies a non-{@code null}
     * result must be returned.
     *
     * @apiNote In general, the
     * {@code toString} method returns a string that
     * "textually represents" this object. The result should
     * be a concise but informative representation that is easy for a
     * person to read.
     * It is recommended that all subclasses override this method.
     * The string output is not necessarily stable over time or across
     * JVM invocations.
     * @implSpec Returns a diagnostic string containing the asset quality prefix and interface dimensions.
     */
    @Override
    public @NotNull String toString() {
        return "GlueCanvas[" + this.assets.getQualityPrefix() + ", " + this.interfaceWidth + "x" + INTERFACE_HEIGHT + "]";
    }


    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code
     * Exception}, implementers are <em>strongly</em> encouraged to
     * declare concrete implementations of the {@code close} method to
     * throw more specific exceptions, or to throw no exception at all
     * if the close operation cannot fail.
     *
     * <p>
     * Cases where the close operation may fail require careful
     * attention by implementers. It is strongly advised to relinquish
     * the underlying resources and to internally <em>mark</em> the
     * resource as closed, prior to throwing the exception. The {@code
     * close} method is unlikely to be invoked more than once and so
     * this ensures that the resources are released in a timely manner.
     * Furthermore it reduces problems that could arise when the resource
     * wraps, or is wrapped, by another resource.
     *
     * <p>
     * <em>Implementers of this interface are also strongly advised
     * to not have the {@code close} method throw {@link
     * InterruptedException}.</em>
     * <p>
     * This exception interacts with a thread's interrupted status,
     * and runtime misbehavior is likely to occur if an {@code
     * InterruptedException} is {@linkplain Throwable#addSuppressed
     * suppressed}.
     * <p>
     * More generally, if it would cause problems for an
     * exception to be suppressed, the {@code AutoCloseable.close}
     * method should not throw it.
     *
     * <p>
     * Note that unlike the {@link Closeable#close close}
     * method of {@link Closeable}, this {@code close} method
     * is <em>not</em> required to be idempotent.  In other words,
     * calling this {@code close} method more than once may have some
     * visible side effect, unlike {@code Closeable.close} which is
     * required to have no effect if called more than once.
     * <p>
     * However, implementers of this interface are strongly encouraged
     * to make their {@code close} methods idempotent.
     * @implSpec Releases the textures (the fonts belong to the client).
     */
    @Override
    public void close() {
        IgnoreException.execute(
                this.dynamicTextures::close,
                this::releaseImages
        );
    }
}
