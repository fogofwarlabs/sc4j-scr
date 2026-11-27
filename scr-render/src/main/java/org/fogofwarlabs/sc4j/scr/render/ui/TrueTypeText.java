package org.fogofwarlabs.sc4j.scr.render.ui;

import org.fogofwarlabs.sc4j.base.binary.BigEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.engine.client.render.GlyphAtlas;
import org.fogofwarlabs.sc4j.engine.client.text.TextColorHook;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.fogofwarlabs.sc4j.scr.render.RenderAssets;
import org.fogofwarlabs.sc4j.scr.render.WorldRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.stb.STBTTBitmap;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTVertex;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads font-face chains, measures advances and draws cached distance-field or coverage glyphs. Missing glyphs use the configured fallback chain,
 * with a shared baseline for each font slot.
 */
public final class TrueTypeText implements AutoCloseable {
    /**
     * The font slot Small ({@code font8}).
     */
    public static final int SMALL = 0;
    /**
     * The font slot Medium ({@code font10}).
     */
    public static final int MEDIUM = 1;
    /**
     * The font slot Big ({@code font16}).
     */
    public static final int BIG = 2;
    /**
     * The font slot Bigger ({@code font16x}).
     */
    public static final int BIGGER = 3;
    /**
     * The web family {@code eurostile-reg} of {@link #getWebSlot}.
     */
    public static final int WEB_REGULAR_FAMILY = 0;
    /**
     * The web family {@code eurostileext-reg}.
     */
    public static final int WEB_EXTENDED_FAMILY = 1;
    /**
     * The web family {@code Arial} (the chat page; the system face, else {@code eurostile-reg}).
     */
    public static final int WEB_ARIAL_FAMILY = 2;

    /**
     * The largest render scale {@code k}.
     */
    static final int MAXIMUM_RENDER_SCALE = 4;
    /**
     * The value the smallest glyph top offset is reset to before a layout, and the result when no glyph is drawn ({@link #getMinimumTop}).
     */
    static final float NO_GLYPH_TOP = 32639.5f;

    /**
     * The font slots of the game and glue sets.
     */
    private static final int FONT_SLOT_COUNT = 4;
    /**
     * The chain entries by language: Latin, Korean, CJK, Japanese, Traditional Chinese.
     */
    private static final int LATIN = 0;
    private static final int KOREAN = 1;
    private static final int CJK = 2;
    private static final int JAPANESE = 3;
    private static final int TRADITIONAL_CHINESE = 4;
    /**
     * The locale prefixes of the Korean and Japanese fallback orders, and the Traditional Chinese locale ({@link #setLocale}).
     */
    private static final @NotNull String KOREAN_LOCALE_PREFIX = "ko";
    private static final @NotNull String JAPANESE_LOCALE_PREFIX = "ja";
    private static final @NotNull String TRADITIONAL_CHINESE_LOCALE = "zhTW";
    /**
     * The face files of the chain entries (Latin, KR, CJK, JP, TW).
     */
    private static final @NotNull String @NotNull [] FILES = {
            "font/BLIZZARD-REGULAR.TTF", "font/Kostar.ttf", "font/bl.ttf",
            "font/UDTypos58B_P_H.ttf", "font/arudjingxiheiu20_b.ttf"
    };
    /**
     * The Latin faces of the game slots.
     */
    private static final @NotNull String @NotNull [] GAME_LATIN = {
            "font/BLIZZARD-REGULAR.TTF", "font/BLIZZARD-REGULAR.TTF",
            "font/EUROSTILE-REG.TTF", "font/EUROSTILEEXT-REG.TTF"
    };
    /**
     * The point sizes of the game slots by language.
     */
    private static final int @NotNull [][] GAME_POINTS = {{8, 7, 8, 6, 7}, {10, 9, 10, 8, 8}, {16, 10, 16, 10, 10}, {16, 11, 16, 11, 12}};
    /**
     * The {@code .fnt} files of the game slots.
     */
    private static final @NotNull String @NotNull [] GAME_FNT = {"font8", "font10", "font16", "font16x"};
    /**
     * The menu font filenames and point sizes by slot and language.
     */
    private static final @NotNull String @NotNull [] GLUE_FNT = {"font10", "font14", "font16", "font16x"};
    private static final @NotNull String @NotNull [] GLUE_LATIN = {
            "font/BLIZZARD-REGULAR.TTF", "font/EUROSTILE-REG.TTF",
            "font/EUROSTILE-REG.TTF", "font/EUROSTILEEXT-REG.TTF"
    };
    private static final int @NotNull [][] GLUE_POINTS = {{10, 9, 10, 8, 8}, {14, 10, 14, 10, 10}, {16, 10, 16, 10, 10}, {16, 10, 16, 11, 12}};
    private static final @NotNull String GLUE_BIGGER_JAPANESE = "font/UDTypos510B_P_H.ttf";
    /**
     * The pixels per inch and points per inch of a point size: a point is {@code 4/3} pixels.
     */
    private static final float SCREEN_DOTS_PER_INCH = 96f;
    private static final float POINTS_PER_INCH = 72f;
    /**
     * Installed web font faces and system-font fallbacks.
     */
    private static final @NotNull String WEB_REGULAR = "webui/dist/lib/fonts/Eurostile-Reg.otf";
    private static final @NotNull String WEB_EXTENDED = "webui/dist/lib/fonts/EurostileExt-Reg.otf";
    private static final @NotNull String WEB_GLOBAL = "webui/dist/lib/fonts/Nimbus(BlizzardGlobal).ttf";
    private static final @NotNull String SYSTEM_HANGUL = "malgun.ttf";
    /**
     * The chat page's Latin face ({@code font-family: Arial, BlizzardGlobal, Malgun Gothic}), a Windows system face.
     */
    private static final @NotNull String SYSTEM_ARIAL = "arial.ttf";
    /**
     * The web faces: the three families and the Hangul face.
     */
    private static final int WEB_FACE_COUNT = 4;
    private static final int WEB_HANGUL_FACE = 3;
    /**
     * The first slot index of the web chains ({@link #getWebSlot}), after the four font slots.
     */
    private static final int FIRST_WEB_SLOT = FONT_SLOT_COUNT;
    /**
     * The web chains' fallback order: the family's face (in the Latin entry), then the Hangul face (in the Korean entry), the CSS {@code font-family}
     * order.
     */
    private static final int @NotNull [] WEB_ORDER = {LATIN, KOREAN};
    /**
     * The Unicode ranges of Hangul: syllables, Jamo, compatibility Jamo, Jamo extended A and B.
     */
    private static final int HANGUL_SYLLABLES_FIRST = 0xAC00;
    private static final int HANGUL_SYLLABLES_LAST = 0xD7AF;
    private static final int HANGUL_JAMO_FIRST = 0x1100;
    private static final int HANGUL_JAMO_LAST = 0x11FF;
    private static final int HANGUL_COMPATIBILITY_JAMO_FIRST = 0x3130;
    private static final int HANGUL_COMPATIBILITY_JAMO_LAST = 0x318F;
    private static final int HANGUL_JAMO_EXTENDED_A_FIRST = 0xA960;
    private static final int HANGUL_JAMO_EXTENDED_A_LAST = 0xA97F;
    private static final int HANGUL_JAMO_EXTENDED_B_FIRST = 0xD7B0;
    private static final int HANGUL_JAMO_EXTENDED_B_LAST = 0xD7FF;
    /**
     * The space character: it advances the pen but draws no glyph.
     */
    private static final char SPACE = ' ';

    /**
     * The SDF padding of a glyph quad in device pixels.
     */
    private static final float SDF_PADDING = 13f;
    /**
     * The on-edge value of the distance fields ({@code stbtt_GetGlyphSDF} argument {@code 180}).
     */
    private static final byte SDF_ON_EDGE = (byte) 180;
    /**
     * The distance scale of the fields ({@code stbtt_GetGlyphSDF} argument {@code 13.0}).
     */
    private static final float SDF_DISTANCE_SCALE = 13f;
    /**
     * The drop shadow weight {@code data.z} of the FONT shader.
     */
    private static final float SHADOW_POWER = 0.75f;
    /**
     * The drop shadow offset setting of the Latin entries; applied times {@code k} atlas texels.
     */
    private static final float SHADOW_OFFSET = 1f;
    /**
     * The flatness of the coverage rasterizer ({@code stbtt_GetGlyphBitmap}'s {@code 0.35} pixels).
     */
    private static final float COVERAGE_FLATNESS = 0.35f;
    /**
     * The coverage of a fully covered pixel.
     */
    private static final int FULL_COVERAGE = 0xFF;
    /**
     * The line gap of a face whose {@code hhea} line gap is {@code 0}, in interface pixels.
     */
    private static final float DEFAULT_LINE_GAP = 2;
    /**
     * The asset scale of the SD graphics.
     */
    private static final int SD_ASSET_SCALE = 1;
    /**
     * The asset scale of the HD graphics, whose render scale the SD text keeps.
     */
    private static final int HD_ASSET_SCALE = 4;
    /**
     * The positions in a glyph header box ({@link #getHeaderBox}).
     */
    private static final int BOX_Y_MIN = 0;
    private static final int BOX_Y_MAX = 1;
    private static final int BOX_UNIT_SCALE = 2;

    /**
     * The sfnt table directory: table count, records, record size, tag length and table offset field.
     */
    private static final int TABLE_COUNT_OFFSET = 4;
    private static final int TABLE_RECORDS_OFFSET = 12;
    private static final int TABLE_RECORD_SIZE = 16;
    private static final int TABLE_TAG_LENGTH = 4;
    private static final int TABLE_OFFSET_FIELD = 8;
    /**
     * The {@code hhea} fields: ascender, descender and line gap.
     */
    private static final int HHEA_ASCENDER_OFFSET = 4;
    private static final int HHEA_DESCENDER_OFFSET = 6;
    private static final int HHEA_LINE_GAP_OFFSET = 8;
    /**
     * The {@code head} field units per em, and the value of a font without {@code head}.
     */
    private static final int HEAD_UNITS_PER_EM_OFFSET = 18;
    private static final int DEFAULT_UNITS_PER_EM = 1000;
    /**
     * The {@code OS/2} fields {@code usWinAscent} and {@code usWinDescent}.
     */
    private static final int OS2_WIN_ASCENT_OFFSET = 74;
    private static final int OS2_WIN_DESCENT_OFFSET = 76;
    /**
     * The {@code kern} table: version {@code 0} header, subtable header fields and format {@code 0} pairs.
     */
    private static final int KERN_TABLE_VERSION = 0;
    private static final int KERN_PAIR_FORMAT = 0;
    private static final int KERN_TABLE_COUNT_OFFSET = 2;
    private static final int KERN_SUBTABLES_OFFSET = 4;
    private static final int KERN_SUBTABLE_HEADER_SIZE = 6;
    private static final int KERN_SUBTABLE_LENGTH_OFFSET = 2;
    private static final int KERN_SUBTABLE_COVERAGE_OFFSET = 4;
    private static final int KERN_PAIR_COUNT_OFFSET = 6;
    private static final int KERN_PAIRS_OFFSET = 14;
    private static final int KERN_PAIR_SIZE = 6;
    private static final int KERN_PAIR_RIGHT_OFFSET = 2;
    private static final int KERN_PAIR_VALUE_OFFSET = 4;

    private static final int KERNING_FORMAT_SHIFT = 8;
    private static final int KERNING_HORIZONTAL_FLAG = 1;
    private static final int KERNING_GLYPH_INDEX_SHIFT = 16;
    private static final int BYTE_MASK = 0xFF;

    /**
     * One parsed font file.
     */
    private static final class Face {
        private final @NotNull ByteBuffer data;
        private final @NotNull STBTTFontinfo fontInformation;

        private final int ascender;
        private final int descender;
        private final int lineGap;
        private final int unitsPerEm;
        /**
         * The {@code OS/2} Windows ascent and descent used for web line metrics.
         */
        private final int winAscent;
        private final int winDescent;

        private final @NotNull Map<Long, Short> kerning;

        private Face(byte @NotNull [] bytes) throws IOException {
            this.data = MemoryUtil.memAlloc(bytes.length);
            this.data.put(bytes).flip();
            this.fontInformation = STBTTFontinfo.malloc();

            // A font that cannot be read frees the parser state and the font copy.
            try {
                if (!STBTruetype.stbtt_InitFont(this.fontInformation, this.data)) {
                    throw Exceptions.newIOException("Unparsable TrueType font");
                }

                int hhea = findTableOffset(bytes, "hhea");

                if (hhea < 0) {
                    throw Exceptions.newIOException("TrueType font without hhea");
                }

                this.ascender  = (short) BigEndianUtils.readUnsigned16(bytes, hhea + HHEA_ASCENDER_OFFSET);
                this.descender = (short) BigEndianUtils.readUnsigned16(bytes, hhea + HHEA_DESCENDER_OFFSET);
                this.lineGap   = (short) BigEndianUtils.readUnsigned16(bytes, hhea + HHEA_LINE_GAP_OFFSET);

                int head = findTableOffset(bytes, "head");

                this.unitsPerEm = head < 0 ? DEFAULT_UNITS_PER_EM : BigEndianUtils.readUnsigned16(bytes, head + HEAD_UNITS_PER_EM_OFFSET);

                int os2 = findTableOffset(bytes, "OS/2");

                this.winAscent  = os2 < 0 ? this.ascender : BigEndianUtils.readUnsigned16(bytes, os2 + OS2_WIN_ASCENT_OFFSET);
                this.winDescent = os2 < 0 ? -this.descender : BigEndianUtils.readUnsigned16(bytes, os2 + OS2_WIN_DESCENT_OFFSET);

                this.kerning = readKern(bytes);
            } catch (IOException | RuntimeException exception) {
                this.close();

                throw exception;
            }
        }

        private int findGlyphIndex(int codePoint) {
            return STBTruetype.stbtt_FindGlyphIndex(this.fontInformation, codePoint);
        }

        private void close() {
            IgnoreException.execute(
                    this.fontInformation::free,
                    () -> MemoryUtil.memFree(this.data)
            );
        }
    }

    /**
     * Holds a chain face at its configured pixel height, using point sizes or bitmap maximum heights.
     */
    private static final class Entry {
        private final @NotNull Face face;
        private final float pixelHeight;

        private Entry(@NotNull Face face, float pixelHeight) {
            this.face        = face;
            this.pixelHeight = pixelHeight;
        }

        private float getUnitScale() {
            return this.pixelHeight / (this.face.ascender - this.face.descender);
        }
    }

    /**
     * A glyph entry of the face map: its atlas slot, and its quad offset from the pen and the line top and its size, in interface pixels.
     */
    private static final class Glyph {
        private final GlyphAtlas.@Nullable Slot slot;

        private final float offsetX;
        private final float offsetY;

        private final float width;
        private final float height;

        private Glyph(GlyphAtlas.@Nullable Slot slot, float offsetX, float offsetY, float width, float height) {
            this.slot = slot;

            this.offsetX = offsetX;
            this.offsetY = offsetY;

            this.width  = width;
            this.height = height;
        }
    }

    /**
     * A coverage glyph bitmap: its texture, its offset from the pen and the baseline and its size, in device pixels.
     */
    private static final class CoverageGlyph {
        private final int texture;

        private final int x;
        private final int y;

        private final int width;
        private final int height;

        private CoverageGlyph(int texture, int x, int y, int width, int height) {
            this.texture = texture;

            this.x = x;
            this.y = y;

            this.width  = width;
            this.height = height;
        }
    }

    /**
     * Returns the render scale {@code k = clamp(ceil(A / 480), 1, 4)}, where {@code A} is the height of the world render target: the game size times
     * the world render scale ({@link WorldRenderer#getRenderScale}), i.e. {@code A = 480 * renderScale} for the {@code 480}-line game view. At
     * {@code 1080} lines {@code k} is {@code 4} with the HD assets and {@code 2} with HD2. SD text keeps the {@code k} of the HD render scale, while
     * its glyphs are drawn on the {@code 1280x960} compose target ({@code WorldRenderer.beginSdInterface}).
     *
     * @param outputWidth  the output width in pixels
     * @param outputHeight the output height in pixels
     * @param scaleLimit   the asset set's scale ({@code 1} SD, {@code 2} HD2, {@code 4} HD), the render scale's cap
     * @return {@code k}
     */
    public static int calculateRenderScale(float outputWidth, float outputHeight, int scaleLimit) {
        if (scaleLimit == SD_ASSET_SCALE) {
            scaleLimit = HD_ASSET_SCALE;
        }

        int windowWidth = Math.max(1, Math.round(outputWidth));
        int windowHeight = Math.max(1, Math.round(outputHeight));
        int gameWidth = Math.max(1, (int) Math.ceil(windowWidth * InterfaceTransform.INTERFACE_HEIGHT / (float) windowHeight));
        int targetHeight = InterfaceTransform.INTERFACE_HEIGHT *
                           WorldRenderer.getRenderScale(windowWidth, windowHeight, gameWidth, InterfaceTransform.INTERFACE_HEIGHT, scaleLimit);

        return Math.clamp((int) Math.ceil(targetHeight / (float) InterfaceTransform.INTERFACE_HEIGHT), 1, MAXIMUM_RENDER_SCALE);
    }

    private static boolean isHangul(int codePoint) {
        return codePoint >= HANGUL_SYLLABLES_FIRST && codePoint <= HANGUL_SYLLABLES_LAST ||
               codePoint >= HANGUL_JAMO_FIRST && codePoint <= HANGUL_JAMO_LAST ||
               codePoint >= HANGUL_COMPATIBILITY_JAMO_FIRST && codePoint <= HANGUL_COMPATIBILITY_JAMO_LAST ||
               codePoint >= HANGUL_JAMO_EXTENDED_A_FIRST && codePoint <= HANGUL_JAMO_EXTENDED_A_LAST ||
               codePoint >= HANGUL_JAMO_EXTENDED_B_FIRST && codePoint <= HANGUL_JAMO_EXTENDED_B_LAST;
    }

    /**
     * Rasterizes a glyph's coverage over the bounds of its outline points (the glyph headers of some installed fonts, for example the Hangul
     * syllables of {@code Kostar.ttf}, state a box that misses the lowest contours).
     *
     * @param fontInformation the face
     * @param glyph           the glyph index
     * @param pixelScale      the device pixels per font unit
     * @param contrast        whether the coverage contrast of the web glyphs is applied
     * @param renderer        the interface renderer that owns the glyph texture
     * @return the coverage glyph, without a texture for a glyph without an outline
     */
    private static @NotNull CoverageGlyph rasterize(@NotNull STBTTFontinfo fontInformation, int glyph,
                                                    float pixelScale,
                                                    boolean contrast,
                                                    @NotNull InterfaceRenderer renderer) {
        STBTTVertex.Buffer shape = STBTruetype.stbtt_GetGlyphShape(fontInformation, glyph);

        if (shape == null) {
            return new CoverageGlyph(0, 0, 0, 0, 0);
        }

        try {
            int minimumX = Integer.MAX_VALUE;
            int minimumY = Integer.MAX_VALUE;
            int maximumX = Integer.MIN_VALUE;
            int maximumY = Integer.MIN_VALUE;

            for (int index = 0; index < shape.remaining(); index++) {
                STBTTVertex vertex = shape.get(index);
                boolean curve = vertex.type() >= STBTruetype.STBTT_vcurve;

                minimumX = Math.min(minimumX, Math.min(vertex.x(), curve ? vertex.cx() : vertex.x()));
                maximumX = Math.max(maximumX, Math.max(vertex.x(), curve ? vertex.cx() : vertex.x()));
                minimumY = Math.min(minimumY, Math.min(vertex.y(), curve ? vertex.cy() : vertex.y()));
                maximumY = Math.max(maximumY, Math.max(vertex.y(), curve ? vertex.cy() : vertex.y()));
            }

            if (minimumX > maximumX) {
                return new CoverageGlyph(0, 0, 0, 0, 0);
            }

            int left = (int) Math.floor(minimumX * pixelScale);
            int top = (int) Math.floor(-maximumY * pixelScale);
            int width = Math.max(1, (int) Math.ceil(maximumX * pixelScale) - left);
            int height = Math.max(1, (int) Math.ceil(-minimumY * pixelScale) - top);
            ByteBuffer pixels = MemoryUtil.memCalloc(width * height);

            try (STBTTBitmap target = STBTTBitmap.malloc()) {
                target.set(width, height, width, pixels);
                STBTruetype.stbtt_Rasterize(target, COVERAGE_FLATNESS, shape, pixelScale, pixelScale, 0, 0, left, top, true);

                byte[] rgbaPixels = new byte[width * height * OverlayPass.RGBA_BYTES];

                for (int pixel = 0; pixel < width * height; pixel++) {
                    int offset = pixel * OverlayPass.RGBA_BYTES;
                    int coverage = pixels.get(pixel) & BYTE_MASK;

                    rgbaPixels[offset]                          = InterfaceImage.FULL_CHANNEL;
                    rgbaPixels[offset + OverlayPass.RGBA_GREEN] = InterfaceImage.FULL_CHANNEL;
                    rgbaPixels[offset + OverlayPass.RGBA_BLUE]  = InterfaceImage.FULL_CHANNEL;

                    // Increase web glyph contrast with the squared coverage complement.
                    if (contrast) {
                        coverage = FULL_COVERAGE - (FULL_COVERAGE - coverage) * (FULL_COVERAGE - coverage) / FULL_COVERAGE;
                    }

                    rgbaPixels[offset + OverlayPass.RGBA_ALPHA] = (byte) coverage;
                }

                return new CoverageGlyph(renderer.createTexture(width, height, rgbaPixels), left, top, width, height);
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } finally {
            // A glyph without vertices still owns its shape allocation; the checked binding refuses an empty buffer, so it is
            // freed through the unchecked call.
            STBTruetype.nstbtt_FreeShape(fontInformation.address(), shape.address());
        }
    }

    /**
     * Returns the offset of an sfnt table.
     *
     * @param font the font file
     * @param tag  the four-character table tag
     * @return the table offset, or {@code -1} when the font has no such table
     */
    private static int findTableOffset(byte @NotNull [] font, @NotNull String tag) {
        int count = BigEndianUtils.readUnsigned16(font, TABLE_COUNT_OFFSET);

        for (int index = 0; index < count; index++) {
            int record = TABLE_RECORDS_OFFSET + index * TABLE_RECORD_SIZE;
            boolean matches = true;

            for (int character = 0; character < TABLE_TAG_LENGTH && matches; character++) {
                matches = font[record + character] == tag.charAt(character);
            }

            if (matches) {
                return (int) BigEndianUtils.readUnsigned32(font, record + TABLE_OFFSET_FIELD);
            }
        }

        return -1;
    }

    /**
     * Reads the legacy {@code kern} table: version {@code 0} subtables of format {@code 0} with horizontal kerning.
     *
     * @param font the font file
     * @return the kerning values by left and right glyph index, empty without a readable table
     */
    private static @NotNull Map<Long, Short> readKern(byte @NotNull [] font) {
        Map<Long, Short> pairs = new HashMap<>();
        int kern = findTableOffset(font, "kern");

        if (kern < 0 || BigEndianUtils.readUnsigned16(font, kern) != KERN_TABLE_VERSION) {
            return pairs;
        }

        int tables = BigEndianUtils.readUnsigned16(font, kern + KERN_TABLE_COUNT_OFFSET);
        int offset = kern + KERN_SUBTABLES_OFFSET;

        for (int index = 0; index < tables && offset + KERN_SUBTABLE_HEADER_SIZE <= font.length; index++) {
            int length = BigEndianUtils.readUnsigned16(font, offset + KERN_SUBTABLE_LENGTH_OFFSET);
            int coverage = BigEndianUtils.readUnsigned16(font, offset + KERN_SUBTABLE_COVERAGE_OFFSET);

            if ((coverage >> KERNING_FORMAT_SHIFT) == KERN_PAIR_FORMAT && (coverage & KERNING_HORIZONTAL_FLAG) != 0) {
                int count = BigEndianUtils.readUnsigned16(font, offset + KERN_PAIR_COUNT_OFFSET);

                for (int pair = 0; pair < count; pair++) {
                    int record = offset + KERN_PAIRS_OFFSET + pair * KERN_PAIR_SIZE;

                    if (record + KERN_PAIR_SIZE > font.length) {
                        break;
                    }

                    pairs.put((long) BigEndianUtils.readUnsigned16(font, record) << KERNING_GLYPH_INDEX_SHIFT |
                              BigEndianUtils.readUnsigned16(font, record + KERN_PAIR_RIGHT_OFFSET),
                              (short) BigEndianUtils.readUnsigned16(font, record + KERN_PAIR_VALUE_OFFSET));
                }
            }

            offset += length;
        }

        return pairs;
    }

    private static @Nullable Path findFont(@NotNull List<Path> directories, @NotNull String name) {
        for (Path directory : directories) {
            Path candidate = directory.resolve(name);

            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private final @NotNull InterfaceRenderer renderer;

    private final @NotNull GlyphAtlas atlas;
    private final @NotNull List<Face> faces;
    private final @NotNull List<Entry[]> chains;

    /**
     * The web faces: the three families ({@link #WEB_REGULAR_FAMILY} ..) and the Hangul face; none outside the glue set.
     */
    private final @Nullable Face @NotNull [] webFaces;
    /**
     * The web chains by family, size and line height ({@link #getWebSlot}).
     */
    private final @NotNull Map<String, Integer> webSlots;
    /**
     * The CSS line height of each web chain, by slot less {@link #FIRST_WEB_SLOT}.
     */
    private final @NotNull List<Float> webLineHeights;
    /**
     * The baseline of each web chain below its line top, by slot less {@link #FIRST_WEB_SLOT}.
     */
    private final @NotNull List<Float> webBaselines;

    private final @NotNull Map<String, Glyph> glyphs;
    /**
     * The coverage glyph bitmaps of the SD path ({@link #setFieldGlyphs}), by face, code point and render scale.
     */
    private final @NotNull SequencedMap<String, CoverageGlyph> coverageGlyphs;

    /**
     * The fallback order of the chain entries.
     */
    private int @NotNull [] order;

    /**
     * The device pixels per CSS pixel of the web pages ({@link #setWebZoom}).
     */
    private float webZoom;

    /**
     * Whether glyphs are distance fields drawn through the FONT shader (HD), or coverage bitmaps (SD).
     */
    private boolean fieldGlyphs;
    /**
     * The device pixels per interface pixel of the coverage bitmaps ({@link #setFieldGlyphs}).
     */
    private int coverageScale;

    /**
     * Creates a {@link TrueTypeText} instance with the face chains of the four font slots of the game set.
     *
     * @param storage  the installed game storage
     * @param renderer the interface renderer that owns glyph textures
     * @throws IOException if a Latin font is missing or malformed
     */
    public TrueTypeText(@NotNull CascStorage storage, @NotNull InterfaceRenderer renderer) throws IOException {
        this(storage, renderer, false, List.of());
    }

    /**
     * Creates a {@link TrueTypeText} instance with the face chains of the four font slots of the game set or the glue set.
     *
     * @param storage         the installed game storage
     * @param renderer        the interface renderer that owns glyph textures
     * @param glue            whether the glue (menu) set is loaded
     * @param fontDirectories the permitted machine-wide font directories in search order
     * @throws IOException if a Latin font is missing or malformed
     */
    public TrueTypeText(@NotNull CascStorage storage, @NotNull InterfaceRenderer renderer, boolean glue, @NotNull List<Path> fontDirectories) throws IOException {
        this.renderer = renderer;

        this.atlas  = new GlyphAtlas(renderer);
        this.faces  = new ArrayList<>();
        this.chains = new ArrayList<>();

        this.webFaces       = new Face[WEB_FACE_COUNT];
        this.webSlots       = new HashMap<>();
        this.webLineHeights = new ArrayList<>();
        this.webBaselines   = new ArrayList<>();

        this.glyphs         = new HashMap<>();
        this.coverageGlyphs = new LinkedHashMap<>();

        this.order = new int[]{LATIN, CJK};

        this.webZoom = 1;

        this.fieldGlyphs   = true;
        this.coverageScale = 1;

        // A face that cannot be loaded closes the text, which releases the faces loaded before it.
        try {
            this.loadChains(storage, glue);

            if (glue) {
                this.loadWebFaces(storage, List.copyOf(fontDirectories));
            }
        } catch (IOException | RuntimeException exception) {
            this.close();

            throw exception;
        }
    }

    /**
     * @param slot the font slot
     * @return the baseline below the line top in interface pixels; game slots share the Latin face ascender at its pixel height
     */
    public float getBaseline(int slot) {
        Entry latin = this.chains.get(slot)[LATIN];

        if (slot >= FIRST_WEB_SLOT) {
            return this.webBaselines.get(slot - FIRST_WEB_SLOT);
        }

        return latin.face.ascender * latin.getUnitScale();
    }

    /**
     * @param slot the font slot
     * @return the line height in interface pixels before the final {@code - 3}
     */
    public float getLineHeight(int slot) {
        if (slot >= FIRST_WEB_SLOT) {
            return this.webLineHeights.get(slot - FIRST_WEB_SLOT);
        }

        Entry latin = this.chains.get(slot)[LATIN];
        float gap = latin.face.lineGap == 0 ? DEFAULT_LINE_GAP : latin.face.lineGap * latin.getUnitScale();

        return latin.pixelHeight + gap;
    }

    /**
     * Returns the smallest glyph quad top of a laid-out line (the minimum of the entry {@code yoff}):
     * {@code (floor(-yMaxHeader * s) - 13 + asc0px) / k} with the glyph face's device scale {@code s = pixelHeight * k / (asc - desc)}, the SDF
     * padding {@code 13} and the Latin ascender {@code asc0px} in device pixels. The quad top lies above the ink by the padding.
     *
     * @param slot        the font slot
     * @param text        the text
     * @param renderScale the render scale
     * @return the offset in interface pixels below the line top, or {@code 32639.5} when no glyph is drawn
     */
    public float getMinimumTop(int slot, @NotNull String text, int renderScale) {
        float minimum = NO_GLYPH_TOP;

        for (int codePoint : text.codePoints().toArray()) {
            float[] box = this.getHeaderBox(slot, codePoint);

            if (box == null) {
                continue;
            }

            float scale = box[BOX_UNIT_SCALE] * renderScale;
            float top = ((float) Math.floor(-box[BOX_Y_MAX] * scale) - SDF_PADDING + this.getBaseline(slot) * renderScale) / renderScale;

            minimum = Math.min(minimum, top);
        }

        return minimum;
    }

    /**
     * Returns the lowest glyph quad bottom of a laid-out line (the bottom word the glyph emit keeps: {@code max(trunc(penY + yoff + h / k))}), which
     * is the measured text height of a one-line layout: {@code (ceil(-yMinHeader * s) + 13 + asc0px) / k}.
     *
     * @param slot        the font slot
     * @param text        the text
     * @param renderScale the render scale
     * @return the bottom in interface pixels below the line top, or {@code 0} when no glyph is drawn
     */
    public float getQuadBottom(int slot, @NotNull String text, int renderScale) {
        float maximum = 0;

        for (int codePoint : text.codePoints().toArray()) {
            float[] box = this.getHeaderBox(slot, codePoint);

            if (box == null) {
                continue;
            }

            float scale = box[BOX_UNIT_SCALE] * renderScale;
            float bottom = ((float) Math.ceil(-box[BOX_Y_MIN] * scale) + SDF_PADDING + this.getBaseline(slot) * renderScale) / renderScale;

            maximum = Math.max(maximum, bottom);
        }

        return maximum;
    }

    /**
     * Returns the ink extent of a line below its top (diagnostics): the outline tops and bottoms under the shared Latin baseline,
     * {@code (asc0px - yMax * s) / k} and {@code (asc0px - yMin * s) / k}.
     *
     * @param slot        the font slot
     * @param text        the text
     * @param renderScale the render scale
     * @return {@code {top, bottom}}, or {@code null} when no glyph is drawn
     */
    public float @Nullable [] getInkBounds(int slot, @NotNull String text, int renderScale) {
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;

        for (int codePoint : text.codePoints().toArray()) {
            float[] box = this.getHeaderBox(slot, codePoint);

            if (box == null) {
                continue;
            }

            float scale = box[BOX_UNIT_SCALE] * renderScale;
            float baseline = this.getBaseline(slot) * renderScale;

            top    = Math.min(top, (baseline - box[BOX_Y_MAX] * scale) / renderScale);
            bottom = Math.max(bottom, (baseline - box[BOX_Y_MIN] * scale) / renderScale);
        }

        return top == Float.MAX_VALUE ? null : new float[]{top, bottom};
    }

    /**
     * Returns the glyf header box of a drawn code point: {@code {yMin, yMax, unit scale}}, or {@code null} for a control code, a space or a code
     * point without a glyph.
     *
     * @param slot      the font slot
     * @param codePoint the code point
     * @return {@code {yMin, yMax, unit scale}}, or {@code null} when no glyph is drawn
     */
    private float @Nullable [] getHeaderBox(int slot, int codePoint) {
        if (codePoint < InterfaceFont.CONTROL_CODE_COUNT || codePoint == SPACE) {
            return null;
        }

        Entry entry = this.findEntry(slot, codePoint);

        if (entry == null) {
            return null;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer left = stack.mallocInt(1);
            IntBuffer bottom = stack.mallocInt(1);
            IntBuffer right = stack.mallocInt(1);
            IntBuffer top = stack.mallocInt(1);

            if (!STBTruetype.stbtt_GetCodepointBox(entry.face.fontInformation, codePoint, left, bottom, right, top)) {
                return null;
            }

            return new float[]{bottom.get(0), top.get(0), entry.getUnitScale()};
        }
    }

    /**
     * Returns the chain entry of a code point: Hangul from the KR face when it has the glyph, else the order list; the Latin face last, also when it
     * lacks the glyph.
     *
     * @param slot      the font slot
     * @param codePoint the code point
     * @return the chain entry, or {@code null} when no Hangul or ordered face has the glyph and the chain has no Latin face
     */
    private @Nullable Entry findEntry(int slot, int codePoint) {
        Entry[] chain = this.chains.get(slot);

        if (isHangul(codePoint) && chain[KOREAN] != null && chain[KOREAN].face.findGlyphIndex(codePoint) != 0) {
            return chain[KOREAN];
        }

        for (int index : slot >= FIRST_WEB_SLOT ? WEB_ORDER : this.order) {
            if (chain[index] != null && chain[index].face.findGlyphIndex(codePoint) != 0) {
                return chain[index];
            }
        }

        // A code point no face has is rasterized from face 0 anyway, so the line goes on with face 0's glyph (.notdef,
        // glyph index 0) instead of ending.
        return chain[LATIN];
    }

    /**
     * Sets the locale-specific Korean, Japanese, Traditional Chinese or default face fallback order.
     *
     * @param locale the text locale, for example {@code koKR}
     */
    public void setLocale(@NotNull String locale) {
        if (locale.startsWith(KOREAN_LOCALE_PREFIX)) {
            this.order = new int[]{KOREAN, LATIN, CJK};
        } else if (locale.startsWith(JAPANESE_LOCALE_PREFIX)) {
            this.order = new int[]{JAPANESE, LATIN, JAPANESE};
        } else if (locale.equals(TRADITIONAL_CHINESE_LOCALE)) {
            this.order = new int[]{TRADITIONAL_CHINESE, LATIN, CJK};
        } else {
            this.order = new int[]{LATIN, CJK};
        }
    }

    /**
     * Sets the zoom the web pages are drawn at, whose device pixels their font metrics are rounded to: the window's pixels per interface pixel for
     * the remastered pages, {@code 1} for the classic ones (drawn at the {@code 640x480} size and scaled up). Chains made at another zoom stay valid
     * for it; {@link #getWebSlot} makes new ones.
     *
     * @param zoom the device pixels per CSS pixel
     */
    public void setWebZoom(float zoom) {
        this.webZoom = Math.max(1, zoom);
    }

    /**
     * Sets distance-field glyphs or coverage bitmaps and clamps the coverage scale to at least {@code 1}.
     *
     * @param fieldGlyphs   {@code true} for the distance field path (HD), {@code false} for coverage bitmaps (SD)
     * @param coverageScale the device pixels per interface pixel of the coverage bitmaps
     */
    public void setFieldGlyphs(boolean fieldGlyphs, int coverageScale) {
        this.fieldGlyphs   = fieldGlyphs;
        this.coverageScale = Math.max(1, coverageScale);
    }


    /**
     * Tests whether a glyph of a line comes from the second face of a web chain.
     *
     * @param slot a web chain ({@link #getWebSlot})
     * @param text a line
     * @return {@code true} if a glyph of the line comes from the chain's second face, {@code false} otherwise
     */
    public boolean hasFallbackGlyph(int slot, @NotNull String text) {
        Entry[] chain = this.chains.get(slot);

        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);

            index += Character.charCount(codePoint);

            if (codePoint <= SPACE) {
                continue;
            }

            if (this.findEntry(slot, codePoint) != chain[LATIN]) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns or creates a cached web font slot at the requested size and line height. Rounded {@code OS/2} ascent and descent provide normal or
     * explicit-height line boxes at the current zoom.
     *
     * @param family     the family, {@link #WEB_REGULAR_FAMILY}, {@link #WEB_EXTENDED_FAMILY} or {@link #WEB_ARIAL_FAMILY}
     * @param size       the CSS font size in interface pixels
     * @param lineHeight the CSS line height in interface pixels, or {@code 0} for {@code normal}
     * @param fallback   whether a {@code normal} line's metrics hold the second face's too
     * @return the slot, or {@code -1} when the web faces are missing
     */
    public int getWebSlot(int family, float size, float lineHeight, boolean fallback) {
        Face first = this.webFaces[family];
        Face global = this.webFaces[WEB_HANGUL_FACE];

        if (first == null || global == null) {
            return -1;
        }

        float zoom = this.webZoom;
        String key = family + ":" + size + ":" + lineHeight + ":" + fallback + ":" + zoom;
        Integer known = this.webSlots.get(key);

        if (known != null) {
            return known;
        }

        Entry[] chain = new Entry[FILES.length];

        chain[LATIN]  = new Entry(first, size * (first.ascender - first.descender) / first.unitsPerEm);
        chain[KOREAN] = new Entry(global, size * (global.ascender - global.descender) / global.unitsPerEm);

        // Round face ascent, descent and half-gap to device pixels at the current page zoom.
        boolean normal = lineHeight <= 0;
        float ascent = 0;
        float descent = 0;

        for (Face face : fallback && normal ? new Face[]{first, global} : new Face[]{first}) {
            float unit = size * zoom / face.unitsPerEm;
            int gap = Math.max(0, face.ascender - face.descender + face.lineGap - face.winAscent - face.winDescent);
            float halfGap = normal ? (float) Math.round(gap * unit) / RenderAssets.HALF_DIVISOR : 0;

            ascent  = Math.max(ascent, (Math.round(face.winAscent * unit) + halfGap) / zoom);
            descent = Math.max(descent, (Math.round(face.winDescent * unit) + halfGap) / zoom);
        }

        float lineStep = normal ? ascent + descent : lineHeight;
        float baseline = normal ? ascent : (lineHeight - ascent - descent) / RenderAssets.HALF_DIVISOR + ascent;
        int slot = this.chains.size();

        this.chains.add(chain);
        this.webLineHeights.add(lineStep);
        this.webBaselines.add(baseline);
        this.webSlots.put(key, slot);

        return slot;
    }


    /**
     * Measures one line: the sum of the advances; color codes {@code 1..31} do not advance.
     *
     * @param slot        the font slot
     * @param text        the text
     * @param renderScale the render scale
     * @return the width in interface pixels
     */
    public float measure(int slot, @NotNull String text, int renderScale) {
        float width = 0;
        int[] codePoints = text.codePoints().toArray();

        for (int index = 0; index < codePoints.length; index++) {
            width += this.calculateAdvance(slot, codePoints, index, renderScale);
        }

        return width;
    }

    private float calculateAdvance(int slot, int @NotNull [] codePoints, int index, int renderScale) {
        int codePoint = codePoints[index];

        if (codePoint < InterfaceFont.CONTROL_CODE_COUNT) {
            return 0;
        }

        Entry entry = this.findEntry(slot, codePoint);

        if (entry == null) {
            return 0;
        }

        float scale = entry.getUnitScale();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer advance = stack.mallocInt(1);
            IntBuffer bearing = stack.mallocInt(1);

            STBTruetype.stbtt_GetCodepointHMetrics(entry.face.fontInformation, codePoint, advance, bearing);

            float value = advance.get(0) * scale;

            if (renderScale == SD_ASSET_SCALE) {
                return (float) Math.ceil(value);
            }

            int next = -1;

            for (int following = index + 1; following < codePoints.length; following++) {
                if (codePoints[following] >= InterfaceFont.CONTROL_CODE_COUNT) {
                    next = codePoints[following];

                    break;
                }
            }

            if (next >= 0 && entry.face.findGlyphIndex(next) != 0) {
                Short pair = entry.face.kerning.get((long) entry.face.findGlyphIndex(codePoint) << KERNING_GLYPH_INDEX_SHIFT |
                                                    entry.face.findGlyphIndex(next));

                if (pair != null) {
                    value += pair * scale;
                }
            }

            return value;
        }
    }


    /**
     * Draws one line with its top at {@code y}; color codes switch the tint and code {@code 1} restores the previous one.
     *
     * @param slot         the font slot
     * @param text         the text
     * @param x            the left screen coordinate of the pen
     * @param y            the top screen coordinate of the line
     * @param screenScale  the screen pixels per interface pixel
     * @param renderScale  the render scale
     * @param defaultColor the starting color code
     * @param shadow       whether the face has the drop shadow (drawn when {@code renderScale != 1}: {@code renderScale} atlas texels, weight {@code 0.75})
     * @return the advance in screen pixels
     */
    public float draw(int slot, @NotNull String text, float x, float y, float screenScale, int renderScale, int defaultColor, boolean shadow) {
        return this.drawGlyphs(slot, text,
                               x, y,
                               screenScale, renderScale,
                               InterfaceFont.getCodeColor(defaultColor), InterfaceFont.GAME_COLORS,
                               shadow, false);
    }

    /**
     * Draws one line with an explicit starting color and color hook (glue screens: {@link InterfaceCodeColors} over the screen's table, whose code
     * {@code 1} restores the previous color).
     *
     * @param slot         the font slot
     * @param text         the text
     * @param x            the left screen coordinate of the pen
     * @param y            the top screen coordinate of the line
     * @param screenScale  the screen pixels per interface pixel
     * @param renderScale  the render scale
     * @param color        the packed {@code 0xRRGGBBAA} starting color
     * @param colors       the color codes
     * @param shadow       whether the drop shadow is drawn
     * @param pixelSnapped whether each glyph is placed on whole screen pixels
     * @return the advance in screen pixels
     */
    public float drawColored(int slot, @NotNull String text,
                             float x, float y,
                             float screenScale, int renderScale,
                             int color, @NotNull TextColorHook colors,
                             boolean shadow, boolean pixelSnapped) {
        return this.drawGlyphs(slot, text, x, y, screenScale, renderScale, color, colors, shadow, pixelSnapped);
    }

    private float drawGlyphs(int slot, @NotNull String text,
                             float x, float y,
                             float screenScale, int renderScale,
                             int startColor, @NotNull TextColorHook colors,
                             boolean shadow, boolean pixelSnapped) {
        // Web text uses coverage glyphs at the page device scale.
        if (slot >= FIRST_WEB_SLOT) {
            return this.drawCoverageGlyphs(slot, text,
                                           x, y,
                                           screenScale, renderScale,
                                           startColor, colors,
                                           this.fieldGlyphs ? screenScale : this.coverageScale, pixelSnapped);
        }

        if (!this.fieldGlyphs) {
            return this.drawCoverageGlyphs(slot, text, x, y, screenScale, renderScale, startColor, colors, this.coverageScale, pixelSnapped);
        }

        int color = startColor;
        int previous = color;
        int[] codePoints = text.codePoints().toArray();
        // The enabled drop shadow is offset by renderScale atlas texels when renderScale differs from one.
        boolean drawShadow = shadow && renderScale != SD_ASSET_SCALE;
        float shadowOffset = drawShadow ? SHADOW_OFFSET * renderScale : 0;
        float shadowPower = drawShadow ? SHADOW_POWER : 0;
        // New glyphs enter the atlas first, and the changed texels are uploaded before any quad of this line is emitted.
        Glyph[] resolved = new Glyph[codePoints.length];

        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            Entry entry = codePoint < InterfaceFont.CONTROL_CODE_COUNT || codePoint == SPACE ? null : this.findEntry(slot, codePoint);

            if (entry != null) {
                resolved[index] = this.getDistanceFieldGlyph(slot, entry, codePoint, renderScale);
            }
        }

        this.atlas.upload();

        float pen = 0;
        float texel = 1f / GlyphAtlas.PAGE_SIZE;

        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];

            if (colors.isColorCode(codePoint)) {
                int next = colors.getColor(codePoint, startColor, color, previous);

                previous = color;
                color    = next;

                continue;
            }

            if (codePoint < InterfaceFont.CONTROL_CODE_COUNT) {
                continue;
            }

            Glyph glyph = resolved[index];

            if (glyph != null && glyph.slot != null) {
                GlyphAtlas.Slot placed = glyph.slot;
                float left = x + (pen + glyph.offsetX) * screenScale;
                float top = y + glyph.offsetY * screenScale;

                if (pixelSnapped) {
                    left = Math.round(left);
                    top  = Math.round(top);
                }

                this.renderer.drawSdfTexture(placed.getTexture(),
                                             left, top,
                                             glyph.width * screenScale, glyph.height * screenScale,
                                             placed.getX() * texel, placed.getY() * texel,
                                             (placed.getX() + placed.getWidth()) * texel, (placed.getY() + placed.getHeight()) * texel,
                                             color,
                                             shadowOffset, shadowPower);
            }

            pen += this.calculateAdvance(slot, codePoints, index, renderScale);
        }

        return pen * screenScale;
    }

    private float drawCoverageGlyphs(int slot, @NotNull String text,
                                     float x, float y,
                                     float screenScale, int renderScale,
                                     int startColor, @NotNull TextColorHook colors,
                                     float scale, boolean pixelSnapped) {
        // Bitmaps made at the screen scale are placed on whole screen pixels, so they are drawn texel for pixel.
        boolean snap = scale == screenScale;
        int color = startColor;
        int previous = color;
        int[] codePoints = text.codePoints().toArray();
        float pen = 0;
        float baseline = this.getBaseline(slot);

        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];

            if (colors.isColorCode(codePoint)) {
                int next = colors.getColor(codePoint, startColor, color, previous);

                previous = color;
                color    = next;

                continue;
            }

            if (codePoint < InterfaceFont.CONTROL_CODE_COUNT) {
                continue;
            }

            Entry entry = codePoint == SPACE ? null : this.findEntry(slot, codePoint);

            if (entry != null) {
                CoverageGlyph glyph = this.getCoverageGlyph(entry, codePoint, scale, slot >= FIRST_WEB_SLOT);

                if (glyph.texture != 0) {
                    float left = x + pen * screenScale + glyph.x / scale * screenScale;
                    float top = y + baseline * screenScale + glyph.y / scale * screenScale;

                    if (snap) {
                        left = Math.round(x + pen * screenScale) + glyph.x;
                        top  = Math.round(y + baseline * screenScale) + glyph.y;
                    } else if (pixelSnapped) {
                        left = Math.round(left);
                        top  = Math.round(top);
                    }

                    this.renderer.drawTexture(glyph.texture, left, top, glyph.width / scale * screenScale, glyph.height / scale * screenScale, color);
                }
            }

            pen += this.calculateAdvance(slot, codePoints, index, renderScale);
        }

        return pen * screenScale;
    }

    private @NotNull CoverageGlyph getCoverageGlyph(@NotNull Entry entry, int codePoint, float scale, boolean contrast) {
        String key = System.identityHashCode(entry) + ":" + codePoint + ":" + scale + ":" + contrast;
        CoverageGlyph cached = this.coverageGlyphs.get(key);

        if (cached != null) {
            return cached;
        }

        CoverageGlyph made = rasterize(entry.face.fontInformation, entry.face.findGlyphIndex(codePoint),
                                       entry.getUnitScale() * scale,
                                       contrast,
                                       this.renderer);

        this.coverageGlyphs.put(key, made);

        return made;
    }

    /**
     * Returns the glyph entry of a code point at the render scale, making its distance field on first use:
     * {@code stbtt_GetGlyphSDF(face, s, glyph, 13, 180, 13.0)} with the device scale {@code s = unitScale * k}; {@code xoff = (lsb * s - 13) / k}
     * (ceiled when {@code k == 1}), {@code yoff = (floor(-yMaxHeader * s) + asc0px - 13) / k} with the Latin face's ascender {@code asc0px} in device
     * pixels, and the quad {@code w / k} by {@code h / k}.
     *
     * @param slot        the font slot
     * @param entry       the chain entry of the code point
     * @param codePoint   the code point
     * @param renderScale the render scale
     * @return the cached glyph entry
     */
    private @NotNull Glyph getDistanceFieldGlyph(int slot, @NotNull Entry entry, int codePoint, int renderScale) {
        String key = System.identityHashCode(entry) + ":" + slot + ":" + codePoint + ":" + renderScale;
        Glyph cached = this.glyphs.get(key);

        if (cached != null) {
            return cached;
        }

        Glyph made = this.createDistanceFieldGlyph(slot, entry, codePoint, renderScale);

        this.glyphs.put(key, made);

        return made;
    }

    private @NotNull Glyph createDistanceFieldGlyph(int slot, @NotNull Entry entry, int codePoint, int renderScale) {
        STBTTFontinfo fontInformation = entry.face.fontInformation;
        int glyph = entry.face.findGlyphIndex(codePoint);
        float scale = entry.getUnitScale() * renderScale;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer fieldX = stack.mallocInt(1);
            IntBuffer fieldY = stack.mallocInt(1);
            ByteBuffer field = STBTruetype.stbtt_GetGlyphSDF(fontInformation,
                                                             scale,
                                                             glyph,
                                                             (int) SDF_PADDING,
                                                             SDF_ON_EDGE,
                                                             SDF_DISTANCE_SCALE,
                                                             width,
                                                             height,
                                                             fieldX,
                                                             fieldY);

            if (field == null) {
                return new Glyph(null, 0, 0, 0, 0);
            }

            GlyphAtlas.Slot placed;

            try {
                byte[] texels = new byte[width.get(0) * height.get(0)];

                field.get(0, texels);
                placed = this.atlas.add(texels, width.get(0), height.get(0));
            } finally {
                STBTruetype.stbtt_FreeSDF(field);
            }

            IntBuffer advance = stack.mallocInt(1);
            IntBuffer bearing = stack.mallocInt(1);

            STBTruetype.stbtt_GetGlyphHMetrics(fontInformation, glyph, advance, bearing);

            float offsetX = (bearing.get(0) * scale - SDF_PADDING) / renderScale;

            if (renderScale == SD_ASSET_SCALE) {
                offsetX = (float) Math.ceil(offsetX);
            }

            IntBuffer left = stack.mallocInt(1);
            IntBuffer bottom = stack.mallocInt(1);
            IntBuffer right = stack.mallocInt(1);
            IntBuffer top = stack.mallocInt(1);

            STBTruetype.stbtt_GetGlyphBox(fontInformation, glyph, left, bottom, right, top);

            float offsetY = ((float) Math.floor(-top.get(0) * scale) + this.getBaseline(slot) * renderScale - SDF_PADDING) / renderScale;

            return new Glyph(placed, offsetX, offsetY, (float) width.get(0) / renderScale, (float) height.get(0) / renderScale);
        }
    }


    private void loadChains(@NotNull CascStorage storage, boolean glue) throws IOException {
        Map<String, Face> loaded = new HashMap<>();
        String[] fonts = glue ? GLUE_FNT : GAME_FNT;
        int[][] points = glue ? GLUE_POINTS : GAME_POINTS;

        for (int slot = 0; slot < FONT_SLOT_COUNT; slot++) {
            Entry[] chain = new Entry[FILES.length];
            byte[] fontData = storage.readFile("font/" + fonts[slot] + ".fnt");
            int maximumHeight = fontData.length > InterfaceFont.MAXIMUM_HEIGHT_OFFSET ? fontData[InterfaceFont.MAXIMUM_HEIGHT_OFFSET] & BYTE_MASK : 0;

            for (int language = 0; language < FILES.length; language++) {
                String file = language == LATIN ? (glue ? GLUE_LATIN : GAME_LATIN)[slot]
                                                : glue && language == JAPANESE && slot == BIGGER ? GLUE_BIGGER_JAPANESE : FILES[language];
                Face face = loaded.get(file);

                if (face == null) {
                    if (!storage.hasFile(file)) {
                        if (language == LATIN) {
                            throw Exceptions.newIOException("Missing font {0}", file);
                        }

                        continue;
                    }

                    face = new Face(storage.readFile(file));
                    loaded.put(file, face);
                    this.faces.add(face);
                }

                // Use bitmap maximum heights for the selected Latin and CJK slots.
                boolean original = (glue || slot > SMALL) && (language == LATIN || language == CJK) && maximumHeight > 0;

                chain[language] = new Entry(face, original ? maximumHeight : points[slot][language] * SCREEN_DOTS_PER_INCH / POINTS_PER_INCH);
            }

            this.chains.add(chain);
        }
    }

    private void loadWebFaces(@NotNull CascStorage storage, @NotNull List<Path> fontDirectories) throws IOException {
        String[] webFiles = {WEB_REGULAR, WEB_EXTENDED};

        for (int index = 0; index < webFiles.length; index++) {
            if (!storage.hasFile(webFiles[index])) {
                continue;
            }

            this.webFaces[index] = new Face(storage.readFile(webFiles[index]));
            this.faces.add(this.webFaces[index]);
        }

        Path system = findFont(fontDirectories, SYSTEM_HANGUL);

        if (system != null) {
            this.webFaces[WEB_HANGUL_FACE] = new Face(Files.readAllBytes(system));
        } else if (storage.hasFile(WEB_GLOBAL)) {
            this.webFaces[WEB_HANGUL_FACE] = new Face(storage.readFile(WEB_GLOBAL));
        }

        if (this.webFaces[WEB_HANGUL_FACE] != null) {
            this.faces.add(this.webFaces[WEB_HANGUL_FACE]);
        }

        Path arial = findFont(fontDirectories, SYSTEM_ARIAL);

        if (arial != null) {
            this.webFaces[WEB_ARIAL_FAMILY] = new Face(Files.readAllBytes(arial));
            this.faces.add(this.webFaces[WEB_ARIAL_FAMILY]);
        } else {
            this.webFaces[WEB_ARIAL_FAMILY] = this.webFaces[WEB_REGULAR_FAMILY];
        }
    }


    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code
     *         Exception}, implementers are <em>strongly</em> encouraged to
     *         declare concrete implementations of the {@code close} method to
     *         throw more specific exceptions, or to throw no exception at all
     *         if the close operation cannot fail.
     *
     *         <p>
     *         Cases where the close operation may fail require careful
     *         attention by implementers. It is strongly advised to relinquish
     *         the underlying resources and to internally <em>mark</em> the
     *         resource as closed, prior to throwing the exception. The {@code
     *         close} method is unlikely to be invoked more than once and so
     *         this ensures that the resources are released in a timely manner.
     *         Furthermore it reduces problems that could arise when the resource
     *         wraps, or is wrapped, by another resource.
     *
     *         <p>
     *         <em>Implementers of this interface are also strongly advised
     *         to not have the {@code close} method throw {@link
     *         InterruptedException}.</em>
     *
     *         <p>
     *         This exception interacts with a thread's interrupted status,
     *         and runtime misbehavior is likely to occur if an {@code
     *         InterruptedException} is {@linkplain Throwable#addSuppressed
     *         suppressed}.
     *
     *         <p>
     *         More generally, if it would cause problems for an
     *         exception to be suppressed, the {@code AutoCloseable.close}
     *         method should not throw it.
     *
     *         <p>
     *         Note that unlike the {@link Closeable#close close}
     *         method of {@link Closeable}, this {@code close} method
     *         is <em>not</em> required to be idempotent.  In other words,
     *         calling this {@code close} method more than once may have some
     *         visible side effect, unlike {@code Closeable.close} which is
     *         required to have no effect if called more than once.
     *
     *         <p>
     *         However, implementers of this interface are strongly encouraged
     *         to make their {@code close} methods idempotent.
     * @implSpec Releases the glyph textures, the faces and the glyph atlas.
     */
    @Override
    public void close() {
        for (CoverageGlyph glyph : this.coverageGlyphs.reversed().values()) {
            if (glyph.texture != 0) {
                IgnoreException.execute(
                        () -> this.renderer.deleteTexture(glyph.texture)
                );
            }
        }

        this.coverageGlyphs.clear();
        this.glyphs.clear();

        for (int index = this.faces.size() - 1; index >= 0; index--) {
            Face face = this.faces.get(index);

            IgnoreException.execute(
                    face::close
            );
        }

        this.faces.clear();

        IgnoreException.execute(
                this.atlas::close
        );
    }
}
