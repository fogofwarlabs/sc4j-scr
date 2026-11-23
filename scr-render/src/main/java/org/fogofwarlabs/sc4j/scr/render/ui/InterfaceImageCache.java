package org.fogofwarlabs.sc4j.scr.render.ui;

import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Caches uploaded interface textures by key and releases them when screens or asset sets change. Sheet frames apply a clamped RGB tint; dynamic
 * images replace their textures through {@link #update}.
 */
public final class InterfaceImageCache implements AutoCloseable {
    /**
     * The color channels of an RGBA pixel that a tint multiplies (red, green and blue).
     */
    private static final int COLOR_CHANNELS = 3;
    /**
     * The position of the red multiplier in a tint and of the red byte in an RGBA pixel.
     */
    private static final int RED_CHANNEL = 0;
    /**
     * The largest value of a color channel byte.
     */
    private static final int MAXIMUM_CHANNEL = 0xFF;
    /**
     * The mask that reads a color channel byte as an unsigned value.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * Holds a texture with its size in interface pixels.
     */
    public static final class Sprite {
        private final int texture;

        private final int width;
        private final int height;

        private final int textureWidth;
        private final int textureHeight;
        private final int scale;

        private final int opaqueLeft;
        private final int opaqueTop;

        Sprite(int texture, int textureWidth, int textureHeight, int scale) {
            this(texture, textureWidth, textureHeight, scale, 0, 0);
        }

        Sprite(int texture, int textureWidth, int textureHeight, int scale, int opaqueLeft, int opaqueTop) {
            this.texture = texture;

            this.width  = textureWidth / scale;
            this.height = textureHeight / scale;

            this.textureWidth  = textureWidth;
            this.textureHeight = textureHeight;
            this.scale         = scale;

            this.opaqueLeft = opaqueLeft;
            this.opaqueTop  = opaqueTop;
        }

        /**
         * @return the texture name
         */
        public int getTexture() {
            return this.texture;
        }

        /**
         * @return the width in interface pixels, {@code textureWidth / scale}
         */
        public int getWidth() {
            return this.width;
        }

        /**
         * @return the height in interface pixels
         */
        public int getHeight() {
            return this.height;
        }

        /**
         * @return the texture width in texture pixels
         */
        public int getTextureWidth() {
            return this.textureWidth;
        }

        /**
         * @return the texture height in texture pixels
         */
        public int getTextureHeight() {
            return this.textureHeight;
        }

        /**
         * @return the texture pixels per interface pixel
         */
        public int getScale() {
            return this.scale;
        }

        /**
         * Some SD {@code .dds.grp} frames (for example {@code glue\create\iCreate}) keep the classic frame offset as transparent columns, where the
         * other sets crop the frame.
         *
         * @return the first column holding a pixel that is not fully transparent, in texture pixels ({@code 0} for images other than sheet frames)
         */
        public int getOpaqueLeft() {
            return this.opaqueLeft;
        }

        /**
         * @return the first row holding a pixel that is not fully transparent, in texture pixels ({@link #getOpaqueLeft})
         */
        public int getOpaqueTop() {
            return this.opaqueTop;
        }
    }

    /**
     * Decodes an image on a cache miss.
     */
    public interface Source {
        /**
         * Decodes the image.
         *
         * @return the image and its texture scale
         * @throws IOException if the image cannot be read
         */
        InterfaceAssets.@NotNull ScaledImage decode() throws IOException;
    }

    /**
     * Decodes a plain image (texture scale {@code 1}) on a cache miss.
     */
    public interface ImageSource {
        /**
         * Decodes the image.
         *
         * @return the image
         * @throws IOException if the image cannot be read
         */
        @NotNull InterfaceImage decode() throws IOException;
    }

    private final @NotNull InterfaceRenderer renderer;

    private final @NotNull Map<String, Sprite> sprites;

    /**
     * Creates an {@link InterfaceImageCache} instance without cached images.
     *
     * @param renderer the renderer that owns the textures
     */
    public InterfaceImageCache(@NotNull InterfaceRenderer renderer) {
        this.renderer = renderer;

        this.sprites = new HashMap<>();
    }

    /**
     * Returns the cached sprite of a key without decoding it.
     *
     * @param key the cache key
     * @return the cached sprite, or {@code null}
     */
    public @Nullable Sprite getCachedSprite(@NotNull String key) {
        return this.sprites.get(key);
    }

    /**
     * Counts the cached sprites whose key starts with a prefix.
     *
     * @param prefix a key prefix
     * @return the number of cached sprites whose key starts with it
     */
    public int getSpriteCount(@NotNull String prefix) {
        int count = 0;

        for (String key : this.sprites.keySet()) {
            if (key.startsWith(prefix)) {
                count++;
            }
        }

        return count;
    }


    /**
     * Returns the sprite of a key, decoding and uploading it on a miss.
     *
     * @param key    the cache key (include the asset set prefix)
     * @param source the decoder
     * @return the sprite
     * @throws IOException if the image cannot be read
     */
    public @NotNull Sprite getSprite(@NotNull String key, @NotNull Source source) throws IOException {
        Sprite sprite = this.sprites.get(key);

        if (sprite == null) {
            InterfaceAssets.ScaledImage image = source.decode();

            sprite = this.uploadSprite(image.getImage().getWidth(), image.getImage().getHeight(), image.getImage().getRgba(), image.getScale());
            this.sprites.put(key, sprite);
        }

        return sprite;
    }

    /**
     * Returns the sprite of a plain image (texture scale {@code 1}), decoding and uploading it on a miss.
     *
     * @param key    the cache key
     * @param source the decoder
     * @return the sprite
     * @throws IOException if the image cannot be read
     */
    public @NotNull Sprite getImage(@NotNull String key, @NotNull ImageSource source) throws IOException {
        Sprite sprite = this.sprites.get(key);

        if (sprite == null) {
            InterfaceImage image = source.decode();

            sprite = this.uploadSprite(image.getWidth(), image.getHeight(), image.getRgba(), 1);
            this.sprites.put(key, sprite);
        }

        return sprite;
    }

    /**
     * Returns a plain DDS texture of an asset set ({@link InterfaceAssets#decodeTexture}).
     *
     * @param assets the interface assets of the current set
     * @param path   the path without the set prefix
     * @return the sprite
     * @throws IOException if the texture cannot be read
     */
    public @NotNull Sprite getImage(@NotNull InterfaceAssets assets, @NotNull String path) throws IOException {
        return this.getSprite(assets.getQualityPrefix() + path, () -> assets.decodeTexture(path));
    }

    /**
     * Returns a sheet frame of an asset set, optionally tinted.
     *
     * @param assets the interface assets of the current set
     * @param sheet  the sheet path without the set prefix
     * @param frame  the frame
     * @param tint   the RGB multipliers, or {@code null}
     * @return the sprite
     * @throws IOException if the sheet cannot be read
     */
    public @NotNull Sprite getFrame(@NotNull InterfaceAssets assets, @NotNull String sheet, int frame, float @Nullable [] tint) throws IOException {
        return this.getFrame(assets, sheet, frame, tint, null);
    }

    /**
     * Returns a sheet frame of an asset set, optionally tinted, uploading an already decoded frame when it is not cached.
     *
     * @param assets  the interface assets of the current set
     * @param sheet   the sheet path without the set prefix
     * @param frame   the frame
     * @param tint    the RGB multipliers, or {@code null}
     * @param decoded the decoded frame, or {@code null} to decode it here
     * @return the sprite
     * @throws IOException if the sheet cannot be read
     */
    public @NotNull Sprite getFrame(@NotNull InterfaceAssets assets,
                                    @NotNull String sheet, int frame,
                                    float @Nullable [] tint,
                                    InterfaceAssets.@Nullable ScaledImage decoded) throws IOException {
        String key = assets.getQualityPrefix() + sheet + "#" + frame +
                     (tint == null ? "" : "#" + tint[RED_CHANNEL] + "," + tint[OverlayPass.RGBA_GREEN] + "," + tint[OverlayPass.RGBA_BLUE]);
        Sprite sprite = this.sprites.get(key);

        if (sprite != null) {
            return sprite;
        }

        InterfaceAssets.ScaledImage image = decoded != null ? decoded : assets.decodeSheetFrame(sheet, frame);
        byte[] rgba = image.getImage().getRgba();

        if (tint != null) {
            rgba = rgba.clone();

            for (int pixel = 0; pixel < rgba.length / OverlayPass.RGBA_BYTES; pixel++) {
                for (int channel = 0; channel < COLOR_CHANNELS; channel++) {
                    int offset = pixel * OverlayPass.RGBA_BYTES + channel;

                    rgba[offset] = (byte) Math.min(MAXIMUM_CHANNEL, Math.round((rgba[offset] & BYTE_MASK) * tint[channel]));
                }
            }
        }

        int width = image.getImage().getWidth();
        int height = image.getImage().getHeight();
        int opaqueLeft = width;
        int opaqueTop = height;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (rgba[(y * width + x) * OverlayPass.RGBA_BYTES + OverlayPass.RGBA_ALPHA] != 0) {
                    opaqueLeft = Math.min(opaqueLeft, x);
                    opaqueTop  = Math.min(opaqueTop, y);
                }
            }
        }

        if (opaqueLeft == width) {
            opaqueLeft = 0;
            opaqueTop  = 0;
        }

        sprite = new Sprite(this.renderer.createTexture(width, height, rgba), width, height, Math.max(1, image.getScale()), opaqueLeft, opaqueTop);
        this.sprites.put(key, sprite);

        return sprite;
    }

    /**
     * Replaces the texture of a dynamic image.
     *
     * @param key    the cache key
     * @param width  the image width
     * @param height the image height
     * @param rgba   the RGBA bytes
     * @param scale  the texture pixels per interface pixel
     * @return the new sprite
     */
    public @NotNull Sprite update(@NotNull String key, int width, int height, byte @NotNull [] rgba, int scale) {
        this.release(key);

        Sprite sprite = this.uploadSprite(width, height, rgba, scale);

        this.sprites.put(key, sprite);

        return sprite;
    }


    private @NotNull Sprite uploadSprite(int width, int height, byte @NotNull [] rgba, int scale) {
        int texture = this.renderer.createTexture(width, height, rgba);

        return new Sprite(texture, width, height, Math.max(1, scale));
    }


    /**
     * Releases one sprite.
     *
     * @param key the cache key
     */
    public void release(@NotNull String key) {
        Sprite sprite = this.sprites.remove(key);

        if (sprite != null) {
            this.renderer.deleteTexture(sprite.texture);
        }
    }

    /**
     * Releases the sprites whose key starts with a prefix.
     *
     * @param prefix the key prefix
     */
    public void releaseMatching(@NotNull String prefix) {
        for (Iterator<Map.Entry<String, Sprite>> iterator = this.sprites.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<String, Sprite> entry = iterator.next();

            if (entry.getKey().startsWith(prefix)) {
                this.renderer.deleteTexture(entry.getValue().texture);
                iterator.remove();
            }
        }
    }

    /**
     * Releases every sprite.
     */
    public void releaseAll() {
        for (Sprite sprite : this.sprites.values()) {
            this.renderer.deleteTexture(sprite.texture);
        }

        this.sprites.clear();
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
     *
     * <p>
     * This exception interacts with a thread's interrupted status,
     * and runtime misbehavior is likely to occur if an {@code
     * InterruptedException} is {@linkplain Throwable#addSuppressed
     * suppressed}.
     *
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
     *
     * <p>
     * However, implementers of this interface are strongly encouraged
     * to make their {@code close} methods idempotent.
     * @implSpec Releases every sprite ({@link #releaseAll}).
     */
    @Override
    public void close() {
        this.releaseAll();
    }
}
