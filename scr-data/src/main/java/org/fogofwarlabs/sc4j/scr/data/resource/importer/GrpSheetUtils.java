package org.fogofwarlabs.sc4j.scr.data.resource.importer;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteFrame;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.scr.data.format.image.GrpFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports a GRP file as an engine {@link SpriteSheet}: the GRP canvas is the frame box with its anchor at the centre
 * ({@code width / 2}, {@code height / 2}), each GRP frame is one {@link SpriteFrame} of its encoded rectangle, and the
 * decoded palette indices are one {@link org.fogofwarlabs.sc4j.engine.resource.graphics.PixelFormat#INDEXED_8} layer.
 * <p>
 * Palette index {@code 0} is transparent: the RLE runs of every installed GRP cover exactly its nonzero pixels. Frames
 * that share their line data in the GRP (the low {@code 31} bits of the frame data offset, with the same rectangle size)
 * share their pixels in the sheet, so they start at the same pixel offset; a renderer recognizes a repeated frame by it.
 * The sheet has no team colour mask and no shadow: SC:R draws them with remapping palettes and draw functions.
 */
public final class GrpSheetUtils {
    /**
     * The {@code 31} low bits of a GRP frame data offset, which tell whether two frames share their line data.
     */
    private static final int FRAME_DATA_OFFSET_MASK = 0x7FFFFFFF;
    /**
     * The divisor that halves the canvas size to its centre.
     */
    private static final int HALF_DIVISOR = 2;
    /**
     * The bits of a rectangle size in the frame sharing key, above the data offset.
     */
    private static final int SIZE_BITS = 16;

    /**
     * Creates a sprite sheet of every frame of a GRP file.
     *
     * @param file the GRP file
     * @return the sprite sheet
     * @throws IOException if the canvas is empty or a frame cannot be decoded
     */
    public static @NotNull SpriteSheet createSheet(@NotNull GrpFile file) throws IOException {
        if (file.getWidth() < 1 || file.getHeight() < 1) {
            throw Exceptions.newIOException("The GRP canvas is empty: {0}x{1}", file.getWidth(), file.getHeight());
        }

        int frameCount = file.getFrameCount();
        int[] pixelOffsets = new int[frameCount];
        boolean[] stored = new boolean[frameCount];
        Map<Long, Integer> sharedOffsets = new HashMap<>();
        int pixelCount = 0;

        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            int width = file.getFrameWidth(frameIndex);
            int height = file.getFrameHeight(frameIndex);
            long sharingKey = (long) (file.getFrameDataOffset(frameIndex) & FRAME_DATA_OFFSET_MASK) << SIZE_BITS << SIZE_BITS |
                              (long) width << SIZE_BITS | height;
            Integer sharedOffset = sharedOffsets.get(sharingKey);

            if (sharedOffset != null) {
                pixelOffsets[frameIndex] = sharedOffset;

                continue;
            }

            pixelOffsets[frameIndex] = pixelCount;
            stored[frameIndex]       = true;

            sharedOffsets.put(sharingKey, pixelCount);
            pixelCount += width * height;
        }

        byte[] pixels = new byte[pixelCount];
        List<SpriteFrame> frames = new ArrayList<>(frameCount);

        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            int left = file.getFrameXOffset(frameIndex);
            int top = file.getFrameYOffset(frameIndex);
            int width = file.getFrameWidth(frameIndex);
            int height = file.getFrameHeight(frameIndex);

            if (stored[frameIndex]) {
                byte[] canvas = file.decodeFrame(frameIndex).getPixels();

                for (int row = 0; row < height; row++) {
                    System.arraycopy(canvas, (top + row) * file.getWidth() + left, pixels, pixelOffsets[frameIndex] + row * width, width);
                }
            }

            frames.add(new SpriteFrame(left, top, width, height, pixelOffsets[frameIndex]));
        }

        try {
            return new SpriteSheet(file.getWidth(), file.getHeight(), file.getWidth() / HALF_DIVISOR, file.getHeight() / HALF_DIVISOR, frames,
                                   PixelLayer.ofIndexed(pixels), null, null);
        } catch (IllegalArgumentException exception) {
            throw Exceptions.newIOException(exception, "The GRP frames do not fit its canvas: {0}", exception.getMessage());
        }
    }

    /**
     * Tells whether a sheet frame repeats the frame before it, as the GRP frames that share their line data do.
     *
     * @param sheet      a sheet of {@link #createSheet}
     * @param frameIndex the frame index
     * @return {@code true} if the frame is not the first and starts at the previous frame's pixel offset with the same rectangle size, {@code false} otherwise
     */
    public static boolean isPreviousFrameRepeated(@NotNull SpriteSheet sheet, int frameIndex) {
        if (frameIndex <= 0) {
            return false;
        }

        SpriteFrame previous = sheet.getFrame(frameIndex - 1);
        SpriteFrame frame = sheet.getFrame(frameIndex);

        return previous.getPixelOffset() == frame.getPixelOffset() && previous.getWidth() == frame.getWidth() &&
               previous.getHeight() == frame.getHeight();
    }

    private GrpSheetUtils() {
    }
}
