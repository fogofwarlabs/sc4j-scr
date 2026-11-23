package org.fogofwarlabs.sc4j.scr.data.resource.importer;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.CompressedTexture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.CompressionFormat;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureFrame;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureSheet;
import org.fogofwarlabs.sc4j.scr.data.format.image.AnimFile;
import org.fogofwarlabs.sc4j.scr.data.format.image.DdsFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports a single-entry HD anim ({@code anim/main_NNN.anim}) as an engine {@link TextureSheet}: the entry's canvas is the
 * frame box, each frame keeps its rectangle, atlas position and flags, and each DDS layer of the entry becomes a
 * {@link CompressedTexture} under the anim's layer name ({@code diffuse}, {@code teamcolor}, {@code bright}, ...), its
 * blocks copied as they are. Frame geometry is in scale-{@value #UNIT_SCALE} pixels; an HD2 anim (scale {@code 2}) holds its
 * textures at half that resolution, so a texel covers two units.
 */
public final class AnimTextureUtils {
    /**
     * The pixel scale of the frame geometry of an HD anim.
     */
    public static final int UNIT_SCALE = 4;

    /**
     * Creates the texture sheet of the one entry of an HD anim.
     *
     * @param file the HD anim
     * @return the texture sheet
     * @throws IOException if the anim is not a single-entry anim of a scale dividing {@value #UNIT_SCALE}, or a layer is not a block-compressed DDS texture
     */
    public static @NotNull TextureSheet createSheet(@NotNull AnimFile file) throws IOException {
        if (file.getEntryCount() != 1 || file.getEntry(0).isReference() || file.getScale() < 1 || UNIT_SCALE % file.getScale() != 0) {
            throw Exceptions.newIOException("Not a single-entry HD anim of scale 1, 2 or 4: {0} entries, scale {1}",
                                            file.getEntryCount(), file.getScale());
        }

        AnimFile.Entry entry = file.getEntry(0);
        List<TextureFrame> frames = new ArrayList<>(entry.getFrameCount());
        Map<String, CompressedTexture> layers = new LinkedHashMap<>();

        for (int frame = 0; frame < entry.getFrameCount(); frame++) {
            frames.add(new TextureFrame(entry.getFrameXOffset(frame), entry.getFrameYOffset(frame),
                                        entry.getFrameWidth(frame), entry.getFrameHeight(frame),
                                        entry.getFrameX(frame), entry.getFrameY(frame),
                                        entry.getFrameFlags(frame)));
        }

        for (int layer = 0; layer < file.getLayerCount(); layer++) {
            DdsFile texture = entry.getLayerTexture(layer);

            if (texture != null && !layers.containsKey(file.getLayerName(layer))) {
                layers.put(file.getLayerName(layer), createTexture(texture, file.getLayerName(layer)));
            }
        }

        try {
            return new TextureSheet(entry.getWidth(), entry.getHeight(), UNIT_SCALE / file.getScale(), frames, layers);
        } catch (IllegalArgumentException exception) {
            throw Exceptions.newIOException(exception, "Invalid HD anim frames: {0}", exception.getMessage());
        }
    }

    /**
     * Returns the engine compression of a DDS format.
     *
     * @param format the DDS format
     * @return the block compression, or {@code null} for an uncompressed format
     */
    public static @Nullable CompressionFormat toCompressionFormat(DdsFile.@NotNull Format format) {
        return switch (format) {
            case DXT1 -> CompressionFormat.BC1;
            case DXT3 -> CompressionFormat.BC2;
            case DXT5 -> CompressionFormat.BC3;
            case RGB32, RGB24 -> null;
        };
    }

    /**
     * Returns the DDS format of an engine compression, for decoding the blocks on the CPU ({@link DdsFile#decodeBlocks}).
     *
     * @param format the block compression
     * @return the DDS format, or {@code null} for {@link CompressionFormat#BC7}, which DDS files of SC:R do not hold
     */
    public static DdsFile.@Nullable Format toDdsFormat(@NotNull CompressionFormat format) {
        return switch (format) {
            case BC1 -> DdsFile.Format.DXT1;
            case BC2 -> DdsFile.Format.DXT3;
            case BC3 -> DdsFile.Format.DXT5;
            case BC7 -> null;
        };
    }

    private static @NotNull CompressedTexture createTexture(@NotNull DdsFile texture, @NotNull String layerName) throws IOException {
        CompressionFormat format = toCompressionFormat(texture.getFormat());

        if (format == null) {
            throw Exceptions.newIOException("The HD anim layer {0} is not block-compressed: {1}", layerName, texture.getFormat());
        }

        List<byte[]> levels = new ArrayList<>(texture.getMipmapCount());

        for (int level = 0; level < texture.getMipmapCount(); level++) {
            byte[] blocks = new byte[texture.getLevelSize(level)];

            texture.copyLevel(level, ByteBuffer.wrap(blocks));
            levels.add(blocks);
        }

        try {
            return new CompressedTexture(format, texture.getWidth(), texture.getHeight(), levels);
        } catch (IllegalArgumentException exception) {
            throw Exceptions.newIOException(exception, "Invalid HD anim layer {0}: {1}", layerName, exception.getMessage());
        }
    }

    private AnimTextureUtils() {
    }
}
