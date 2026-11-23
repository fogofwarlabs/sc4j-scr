package org.fogofwarlabs.sc4j.scr.render.ui;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.text.Font;
import org.fogofwarlabs.sc4j.engine.client.text.TextColorHook;
import org.fogofwarlabs.sc4j.engine.client.text.TextStyle;
import org.fogofwarlabs.sc4j.engine.resource.text.FontFace;
import org.fogofwarlabs.sc4j.engine.resource.text.FontGlyph;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceKeys;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;

/**
 * Draws bitmap game-font records with a distance-field atlas or a configured TrueType fallback chain. Text color codes select the glyph tint, with a
 * bitmap drop shadow above render scale {@code 1}.
 * <p>
 * It is the engine {@link Font} of the SC:R interface: an engine text user (a HUD block, an engine control, {@link
 * org.fogofwarlabs.sc4j.engine.client.text.TextLayout}) measures and draws FNT text through it as the native paths do. Its default
 * {@link TextStyle} has the kerning and the game's {@code tfontgam} code colors; the interface text layout ({@link InterfaceText}) draws every
 * line through the engine form with the style of the caller: the screen's code table ({@link InterfaceCodeColors}), the kerning switch of the
 * format and the screen pixels per interface pixel as the scale, the pen in screen pixels. The glyphs live in the atlas of the
 * {@link InterfaceRenderer} the font was loaded with.
 */
public final class InterfaceFont implements Font {
    /**
     * The control codes {@code 0x00..0x1F}: color, alignment and layout codes, which are not glyphs.
     */
    static final int CONTROL_CODE_COUNT = 0x20;
    /**
     * The color code that restores the previous color.
     */
    static final int RESTORE_COLOR_CODE = 0x01;
    /**
     * The offset of the maximum glyph height byte in the {@code .fnt} header.
     */
    static final int MAXIMUM_HEIGHT_OFFSET = 11;

    /**
     * The color code whose color unlisted codes use.
     */
    private static final int DEFAULT_COLOR_CODE = 2;
    /**
     * The packed color of {@link #DEFAULT_COLOR_CODE}.
     */
    private static final int DEFAULT_CODE_COLOR = 0xB8B8E8FF;
    /**
     * The colors of the text codes: the palette entry of byte {@code 1} of each {@code tfontgam} entry.
     */
    private static final int @NotNull [] CODE_COLORS = new int[CONTROL_CODE_COUNT];
    /**
     * The codes that switch the tint: the codes of {@link #CODE_COLORS} and {@link #RESTORE_COLOR_CODE}.
     */
    private static final boolean @NotNull [] COLOR_CODES = new boolean[CONTROL_CODE_COUNT];

    /**
     * The last character a {@code .fnt} can hold: the fonts map single bytes.
     */
    private static final int LAST_BITMAP_CHARACTER = 0xFF;
    /**
     * The mask of an unsigned {@code .fnt} byte.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The layout characters tab and space.
     */
    private static final char TAB = '\t';
    private static final char SPACE = ' ';
    /**
     * The packed color of the bitmap glyphs' drop shadow: black at alpha {@code 0xBF}.
     */
    private static final int BITMAP_SHADOW_COLOR = 0x000000BF;
    /**
     * The {@code tfontgam} code colors: code and packed RGB.
     */
    private static final int @NotNull [][] TFONTGAM_COLORS = {
            {2, 0xB8B8E8}, {3, 0xDCDC3C}, {4, 0xFFFFFF}, {5, 0x847474}, {6, 0xC81818}, {7, 0x10FC18}, {8, 0xF40404},
            {0x0E, 0x0C48CC}, {0x0F, 0x2CB494}, {0x10, 0x88409C}, {0x11, 0xF88C14}, {0x15, 0x703014}, {0x16, 0xCCE0D0},
            {0x17, 0xFCFC38}, {0x18, 0x088008}, {0x19, 0xFCFC7C}, {0x1B, 0xECC4B0}, {0x1C, 0x4068D4}, {0x1D, 0x74A47C},
            {0x1E, 0x9090B8}, {0x1F, 0x00E4FC},
            };
    /**
     * The color value of a glyph drawn in the code color (no fixed color).
     */
    private static final int NO_FIXED_COLOR = 0;
    /**
     * The chain slot of a font without a fallback line box.
     */
    private static final int NO_FALLBACK_SLOT = -1;

    /**
     * The game's {@code tfontgam} code colors as an engine color hook.
     */
    static final @NotNull InterfaceCodeColors GAME_COLORS;
    /**
     * The style of the engine forms without one: the game's code colors and the kerning at one screen pixel per font pixel.
     */
    private static final @NotNull TextStyle DEFAULT_STYLE;

    static {
        Arrays.fill(CODE_COLORS, DEFAULT_CODE_COLOR);

        for (int[] entry : TFONTGAM_COLORS) {
            CODE_COLORS[entry[0]] = entry[1] << Byte.SIZE | InterfaceImage.OPAQUE;
            COLOR_CODES[entry[0]] = true;
        }

        COLOR_CODES[RESTORE_COLOR_CODE] = true;

        GAME_COLORS   = new InterfaceCodeColors(CODE_COLORS);
        DEFAULT_STYLE = TextStyle.builder().setColors(GAME_COLORS).build();
    }

    /**
     * Returns the color of a text color code; unlisted codes use the color of code {@code 2}.
     *
     * @param code the code byte
     * @return the packed {@code 0xRRGGBBAA} color
     */
    public static int getCodeColor(int code) {
        return code >= 0 && code < CODE_COLORS.length ? CODE_COLORS[code] : CODE_COLORS[DEFAULT_COLOR_CODE];
    }

    /**
     * Tests whether a byte is a text color code, the cases of the glyph emit that switch the tint: {@code 0x01..0x08}, {@code 0x0E..0x11},
     * {@code 0x15..0x19} and {@code 0x1B..0x1F}. Tab, the line breaks, carriage return, the alignment codes {@code 0x12} / {@code 0x13} and
     * {@code 0x1A} do not.
     *
     * @param code the code byte
     * @return {@code true} if the byte is a color code, including code {@code 1} (restore the previous color), {@code false} otherwise
     */
    public static boolean isColorCode(int code) {
        return code >= 0 && code < CONTROL_CODE_COUNT && COLOR_CODES[code];
    }

    /**
     * Creates an {@link InterfaceFont} instance from a game font: the engine font face of the provider, whose coverage image becomes the white
     * atlas texture the glyphs are tinted from.
     *
     * @param resources the resource provider of the installation
     * @param renderer  the interface renderer that owns the atlas texture
     * @param name      the font name, {@code font8}, {@code font10}, {@code font16} or {@code font16x}
     * @return the created {@link InterfaceFont} instance
     * @throws IOException if the font is missing or malformed
     */
    public static @NotNull InterfaceFont load(@NotNull ScrResourceProvider resources, @NotNull InterfaceRenderer renderer,
                                              @NotNull String name) throws IOException {
        FontFace face = resources.loadFont(ScrResourceKeys.ofFont(name));
        int pixelCount = face.getImageWidth() * face.getImageHeight();
        byte[] rgbaPixels = new byte[pixelCount * OverlayPass.RGBA_BYTES];

        for (int pixel = 0; pixel < pixelCount; pixel++) {
            int offset = pixel * OverlayPass.RGBA_BYTES;

            rgbaPixels[offset]                          = InterfaceImage.FULL_CHANNEL;
            rgbaPixels[offset + OverlayPass.RGBA_GREEN] = InterfaceImage.FULL_CHANNEL;
            rgbaPixels[offset + OverlayPass.RGBA_BLUE]  = InterfaceImage.FULL_CHANNEL;
            rgbaPixels[offset + OverlayPass.RGBA_ALPHA] = (byte) face.getPixels().getValue(pixel);
        }

        int texture = renderer.createTexture(face.getImageWidth(), face.getImageHeight(), rgbaPixels);

        return new InterfaceFont(face, face.getLineHeight(), texture, face.getImageWidth(), face.getImageHeight());
    }

    /**
     * Creates an {@link InterfaceFont} instance that draws a TrueType chain only (a web page's text style, {@link TrueTypeText#getWebSlot}): no
     * {@code .fnt} bitmap and no atlas; its maximum height is the chain's line height less the layout gap {@link InterfaceText#LINE_GAP}, so that a
     * layout steps its lines by the chain's line height and fits one line into a rectangle of that height. The page's text shadow is drawn by the
     * caller, so the face's own drop shadow is off.
     *
     * @param trueType the face chains
     * @param slot     the chain's slot: its line box when a line takes its glyphs from the family face alone
     * @param fallback the slot of the same faces whose line box also holds the second face's metrics (a line that falls back to it)
     * @return the created {@link InterfaceFont} instance
     */
    public static @NotNull InterfaceFont ofTrueType(@NotNull TrueTypeText trueType, int slot, int fallback) {
        int maximumHeight = Math.max(0, Math.round(trueType.getLineHeight(slot)) - InterfaceText.LINE_GAP) & BYTE_MASK;
        InterfaceFont font = new InterfaceFont(null, maximumHeight, 0, 1, 1);

        font.setTrueType(trueType, slot);
        font.trueTypeFallbackSlot = fallback;
        font.trueTypeOnly         = true;

        return font;
    }

    /**
     * The glyphs of the characters {@code 0} through {@value #LAST_BITMAP_CHARACTER} of the font face, {@code null} where it has none.
     */
    private final @Nullable FontGlyph @NotNull [] glyphs;
    private final int texture;
    private final int atlasWidth;
    private final int atlasHeight;

    private final int maximumHeight;

    private @Nullable TrueTypeText trueType;
    private int trueTypeSlot;
    /**
     * The chain slot of a line that falls back to the second face ({@link #ofTrueType}), or {@code -1}.
     */
    private int trueTypeFallbackSlot;
    /**
     * Whether the font has no bitmap glyphs ({@link #ofTrueType}): TrueType in every mode, without the face's shadow.
     */
    private boolean trueTypeOnly;

    private boolean trueTypeMode;
    private int renderScale;

    private InterfaceFont(@Nullable FontFace face, int maximumHeight, int texture, int atlasWidth, int atlasHeight) {
        this.glyphs      = new FontGlyph[LAST_BITMAP_CHARACTER + 1];
        this.texture     = texture;
        this.atlasWidth  = atlasWidth;
        this.atlasHeight = atlasHeight;

        this.maximumHeight = maximumHeight;

        if (face != null) {
            for (int character = 0; character <= LAST_BITMAP_CHARACTER; character++) {
                this.glyphs[character] = face.findGlyph(character);
            }
        }

        this.trueType             = null;
        this.trueTypeSlot         = 0;
        this.trueTypeFallbackSlot = NO_FALLBACK_SLOT;
        this.trueTypeOnly         = false;

        this.trueTypeMode = false;
        this.renderScale  = 1;
    }

    /**
     * @return {@code true} if the TrueType text path is on for this font (the global flag of the text layout; {@link #setMode}), not only for characters outside the {@code .fnt}, {@code false} otherwise
     */
    public boolean isTrueTypeMode() {
        return this.trueTypeMode;
    }

    /**
     * @return the {@code .fnt} maximum glyph height
     */
    public int getMaximumHeight() {
        return this.maximumHeight;
    }

    /**
     * @return the line height in font pixels: the maximum height plus {@link InterfaceText#LINE_GAP} (the {@code .fnt} header is read in TrueType mode too, as for the message area)
     */
    @Override
    public int getLineHeight() {
        return this.maximumHeight + InterfaceText.LINE_GAP;
    }

    /**
     * @return the style of the engine forms without one: the game's {@code tfontgam} code colors and the one-pixel kerning at one screen pixel per font pixel
     */
    @Override
    public @NotNull TextStyle getDefaultStyle() {
        return DEFAULT_STYLE;
    }

    /**
     * @param text the text to be drawn (it selects the path)
     * @return the text height of a one-line layout: the line height less {@link InterfaceText#LINE_GAP}; in TrueType mode the Latin face's {@code pointSize * 4/3 + gap}
     */
    public float getTextHeight(@NotNull String text) {
        if (this.shouldUseTrueType(text)) {
            return this.trueType.getLineHeight(this.getTrueTypeSlot(text)) - InterfaceText.LINE_GAP;
        }

        return this.maximumHeight;
    }

    /**
     * @param text the text to be drawn (it selects the path and the line box)
     * @return the TrueType baseline below the line top ({@link TrueTypeText#getBaseline}); {@code 0} for bitmap text
     */
    public float getBaseline(@NotNull String text) {
        return this.shouldUseTrueType(text) ? this.trueType.getBaseline(this.getTrueTypeSlot(text)) : 0;
    }

    /**
     * Returns the smallest glyph quad top of a TrueType line, which the single-line baseline adjustment moves the line up by half of; {@code 0} for
     * bitmap text, which does not use it, and for a line that draws no glyph, which gets no adjustment.
     *
     * @param text the text
     * @return the offset in interface pixels below the line top
     */
    public float getMinimumTop(@NotNull String text) {
        if (!this.shouldUseTrueType(text)) {
            return 0;
        }

        float minimumTop = this.trueType.getMinimumTop(this.getTrueTypeSlot(text), text, this.getEffectiveRenderScale());

        return minimumTop < TrueTypeText.NO_GLYPH_TOP ? minimumTop : 0;
    }

    /**
     * Returns the measured height of a one-line layout, the bottom word the glyph emit keeps relative to the line top: TrueType
     * {@code trunc(max(yoff + h / k))} (glyph quad bottom with the SDF padding); bitmap {@code max(yoff + h) - 1} (the last ink row of the lowest
     * glyph). Spaces and color codes are not glyphs.
     *
     * @param text the text
     * @return the height in interface pixels
     */
    public int getMeasuredHeight(@NotNull String text) {
        if (this.shouldUseTrueType(text)) {
            return (int) this.trueType.getQuadBottom(this.getTrueTypeSlot(text), text, this.getEffectiveRenderScale());
        }

        int bottom = 0;

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            FontGlyph glyph = this.findGlyph(character);

            if (character <= SPACE || glyph == null) {
                continue;
            }

            bottom = Math.max(bottom, glyph.getOffsetY() + glyph.getHeight() - 1);
        }

        return bottom;
    }

    /**
     * Returns the pen step from one laid-out line to the next: in TrueType the face line height, for bitmap text the {@code .fnt} maximum height plus
     * the caller's line gap byte.
     *
     * @param text the line (it selects the path)
     * @param gap  the line gap byte
     * @return the step in interface pixels
     */
    public float getLineStep(@NotNull String text, int gap) {
        if (this.shouldUseTrueType(text)) {
            return this.trueType.getLineHeight(this.getTrueTypeSlot(text));
        }

        return this.maximumHeight + gap;
    }

    /**
     * Returns the lowest glyph bottom of one line below its pen, before truncation (TrueType {@code yoff + h / k}, the glyph quad bottom with the SDF
     * padding; bitmap {@code yoff + h - 1}).
     *
     * @param text the line
     * @return the bottom in interface pixels, {@code 0} when no glyph is drawn
     */
    public float getLineBottom(@NotNull String text) {
        if (this.shouldUseTrueType(text)) {
            return this.trueType.getQuadBottom(this.getTrueTypeSlot(text), text, this.getEffectiveRenderScale());
        }

        return this.getMeasuredHeight(text);
    }

    /**
     * Returns the ink extent of a line below its top (diagnostics): bitmap {@code min(yoff)} and {@code max(yoff + h)}; TrueType the outline extent
     * under the shared baseline.
     *
     * @param text the text
     * @return {@code {top, bottom}} in interface pixels, or {@code null} when no glyph is drawn
     */
    public float @Nullable [] getInkBounds(@NotNull String text) {
        if (this.shouldUseTrueType(text)) {
            return this.trueType.getInkBounds(this.getTrueTypeSlot(text), text, this.getEffectiveRenderScale());
        }

        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            FontGlyph glyph = this.findGlyph(character);

            if (character <= SPACE || glyph == null) {
                continue;
            }

            if (glyph.getHeight() == 0) {
                continue;
            }

            top    = Math.min(top, glyph.getOffsetY());
            bottom = Math.max(bottom, glyph.getOffsetY() + glyph.getHeight());
        }

        return top == Integer.MAX_VALUE ? null : new float[]{top, bottom};
    }

    private int getEffectiveRenderScale() {
        return this.trueTypeMode ? this.renderScale : 1;
    }

    /**
     * Returns the chain slot of a text: the fallback line box when the text takes a glyph from the second face.
     *
     * @param text the text
     * @return the chain slot
     */
    private int getTrueTypeSlot(@NotNull String text) {
        TrueTypeText faces = this.trueType;

        if (this.trueTypeFallbackSlot < 0 || faces == null || this.trueTypeFallbackSlot == this.trueTypeSlot) {
            return this.trueTypeSlot;
        }

        return faces.hasFallbackGlyph(this.trueTypeSlot, text) ? this.trueTypeFallbackSlot : this.trueTypeSlot;
    }

    /**
     * Sets the TrueType face chain of this font's slot.
     *
     * @param trueType the face chains
     * @param slot     the slot: {@link TrueTypeText#SMALL} for {@code font8} .. {@link TrueTypeText#BIGGER} for {@code font16x}
     */
    public void setTrueType(@Nullable TrueTypeText trueType, int slot) {
        this.trueType     = trueType;
        this.trueTypeSlot = slot;
    }

    /**
     * Sets the text path: TrueType in HD when {@code k > 1}, and in SD for locales whose text is not mapped onto the {@code .fnt} (Korean and CJK);
     * otherwise the {@code .fnt} bitmap glyphs.
     *
     * @param trueTypeMode whether TrueType glyphs are drawn
     * @param renderScale  {@code k = clamp(ceil(outputHeight / 480), 1, 4)}
     */
    public void setMode(boolean trueTypeMode, int renderScale) {
        this.trueTypeMode = (trueTypeMode || this.trueTypeOnly) && this.trueType != null;
        this.renderScale  = Math.clamp(renderScale, 1, TrueTypeText.MAXIMUM_RENDER_SCALE);
    }


    /**
     * @param text the text to be drawn
     * @return {@code true} if the text takes the TrueType path instead of the bitmap font, {@code false} otherwise
     */
    public boolean isTrueType(@NotNull String text) {
        return this.shouldUseTrueType(text);
    }

    private boolean canDrawBitmap(@NotNull String text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) > LAST_BITMAP_CHARACTER) {
                return false;
            }
        }

        return true;
    }

    private boolean shouldUseTrueType(@NotNull String text) {
        // A code point outside the .fnt (for example Hangul in enUS) is measured and drawn by the TrueType chain even in
        // bitmap mode; the whole line takes that path here.
        return this.trueType != null && (this.trueTypeMode || !this.canDrawBitmap(text));
    }


    /**
     * Measures one line: a space advances half the maximum width, a tab twice it, codes {@code 1..31} do not advance, a glyph advances
     * {@code xoff + width + kern}.
     *
     * @param text    the text
     * @param kerning whether the one-pixel kerning is added (not for {@code RC_NO_TEXTKERN} controls)
     * @return the width in font pixels
     */
    public float measure(@NotNull String text, boolean kerning) {
        if (this.shouldUseTrueType(text)) {
            return this.trueType.measure(this.getTrueTypeSlot(text), text, this.getEffectiveRenderScale());
        }

        int width = 0;

        for (int index = 0; index < text.length(); index++) {
            width += this.calculateAdvance(text.charAt(index), kerning);
        }

        return width;
    }

    /**
     * Measures one line in a style as the engine {@link Font} measures: {@link #measure(String, boolean)} with the style's kerning, times its scale.
     * Characters the style's color hook takes as codes do not advance (the interface codes do not advance in any case).
     *
     * @param text  the line, without line breaks
     * @param style the style: its colour codes do not advance, its kerning and scale apply
     * @return the advance of the line in canvas units
     */
    @Override
    public float measure(@NotNull CharSequence text, @NotNull TextStyle style) {
        return this.measure(removeForeignCodes(text, style.getColors()), style.isKerning()) * style.getScale();
    }

    /**
     * Removes the characters a color hook takes as codes that are not interface control codes, which the interface measures as glyphs.
     *
     * @param text   the line
     * @param colors the color hook
     * @return the line without those characters, the same text when there are none
     */
    private static @NotNull String removeForeignCodes(@NotNull CharSequence text, @NotNull TextColorHook colors) {
        String line = text.toString();

        if (colors instanceof InterfaceCodeColors) {
            return line;
        }

        StringBuilder kept = null;

        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            boolean foreign = character >= CONTROL_CODE_COUNT && colors.isColorCode(character);

            if (foreign && kept == null) {
                kept = new StringBuilder(line.substring(0, index));
            } else if (!foreign && kept != null) {
                kept.append(character);
            }
        }

        return kept == null ? line : kept.toString();
    }

    private int calculateAdvance(char character, boolean kerning) {
        FontGlyph glyph = this.findGlyph(character);

        // The face's space and tab advance by its own rule; other control codes do not advance.
        if (character == TAB || character == SPACE) {
            return glyph == null ? 0 : glyph.getAdvance();
        }

        if (character < CONTROL_CODE_COUNT || glyph == null) {
            return 0;
        }

        return glyph.getAdvance() + (kerning ? 1 : 0);
    }

    /**
     * Returns the glyph of a character in the font face.
     *
     * @param character the character
     * @return the glyph, or {@code null} when the face has none for it
     */
    private @Nullable FontGlyph findGlyph(char character) {
        return character <= LAST_BITMAP_CHARACTER ? this.glyphs[character] : null;
    }


    /**
     * Draws one line with its top at {@code y}; color codes switch the tint and code {@code 1} restores the previous one.
     *
     * @param renderer     the interface renderer between {@code begin} and {@code end}
     * @param text         the text
     * @param x            the left screen coordinate
     * @param y            the top screen coordinate
     * @param scale        the screen pixels per font pixel
     * @param defaultColor the starting color code
     * @param kerning      whether the one-pixel kerning is added
     * @return the advance in screen pixels
     */
    public float draw(@NotNull InterfaceRenderer renderer, @NotNull String text, float x, float y, float scale, int defaultColor, boolean kerning) {
        return this.drawStyled(renderer, text, x, y, scale, getCodeColor(defaultColor), GAME_COLORS, kerning, false);
    }

    /**
     * Draws one line in a style as the engine {@link Font} draws: the pen starts at {@code x} in screen pixels and moves by each advance times the
     * style's scale (the screen pixels per font pixel); the style's color hook switches the tint ({@link InterfaceCodeColors} for a screen's code
     * table, whose code {@code 1} restores the previous color), its kerning switch adds the one-pixel kerning and its pixel snapping places each
     * glyph on whole screen pixels. Bitmap text receives a drop shadow above render scale one, TrueType text the face's shadow.
     *
     * @param canvas the interface renderer the font was loaded with, between its {@code begin} and {@code end}
     * @param text   the line, without line breaks
     * @param x      the left screen coordinate of the pen
     * @param y      the top screen coordinate of the line
     * @param color  the packed {@code 0xRRGGBBAA} starting color
     * @param style  the style of the line
     * @return the pen position after the line
     * @throws IllegalArgumentException if the canvas is not an interface renderer
     */
    @Override
    public float draw(@NotNull Canvas canvas, @NotNull CharSequence text, float x, float y, int color, @NotNull TextStyle style) {
        if (!(canvas instanceof InterfaceRenderer renderer)) {
            throw Exceptions.newIllegalArgumentException("An interface font draws on its interface renderer, not on {0}.", canvas);
        }

        return x + this.drawStyled(renderer, text.toString(),
                                   x, y,
                                   style.getScale(),
                                   color, style.getColors(),
                                   style.isKerning(), style.isPixelSnapped());
    }

    private float drawStyled(@NotNull InterfaceRenderer renderer,
                             @NotNull String text,
                             float x, float y,
                             float scale,
                             int color, @NotNull TextColorHook colors,
                             boolean kerning, boolean pixelSnapped) {
        if (this.shouldUseTrueType(text)) {
            return this.trueType.drawColored(this.getTrueTypeSlot(text), text,
                                             x, y,
                                             scale, this.getEffectiveRenderScale(),
                                             color, colors,
                                             !this.trueTypeOnly, pixelSnapped);
        }

        // Bitmap text receives a drop shadow above render scale one.
        int renderScale = this.renderScale;

        if (renderScale > 1) {
            this.drawGlyphs(renderer, text, x + renderScale, y + renderScale, scale, color, colors, kerning, pixelSnapped, BITMAP_SHADOW_COLOR);
        }

        return this.drawGlyphs(renderer, text, x, y, scale, color, colors, kerning, pixelSnapped, NO_FIXED_COLOR);
    }

    private float drawGlyphs(@NotNull InterfaceRenderer renderer,
                             @NotNull String text,
                             float x, float y,
                             float scale,
                             int startColor, @NotNull TextColorHook colors,
                             boolean kerning, boolean pixelSnapped,
                             int fixedColor) {
        int color = startColor;
        int previous = color;
        float pen = x;

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);

            if (colors.isColorCode(character)) {
                int next = colors.getColor(character, startColor, color, previous);

                previous = color;
                color    = next;

                continue;
            }

            if (character < CONTROL_CODE_COUNT && character != TAB) {
                continue;
            }

            FontGlyph glyph = this.findGlyph(character);

            if (character != SPACE && character != TAB && glyph != null) {
                int glyphColor = fixedColor != NO_FIXED_COLOR ? fixedColor : color;

                int offsetX = glyph.getOffsetX();
                int offsetY = glyph.getOffsetY();
                int width = glyph.getWidth();
                int height = glyph.getHeight();
                int atlasX = glyph.getImageX();
                int atlasY = glyph.getImageY();

                if (width > 0 && height > 0) {
                    float left = pen + offsetX * scale;
                    float top = y + offsetY * scale;

                    if (pixelSnapped) {
                        left = Math.round(left);
                        top  = Math.round(top);
                    }

                    renderer.drawTexture(this.texture,
                                         left,
                                         top,
                                         width * scale,
                                         height * scale,
                                         atlasX / (float) this.atlasWidth,
                                         atlasY / (float) this.atlasHeight,
                                         (atlasX + width) / (float) this.atlasWidth,
                                         (atlasY + height) / (float) this.atlasHeight,
                                         glyphColor);
                }
            }

            pen += this.calculateAdvance(character, kerning) * scale;
        }

        return pen - x;
    }


    /**
     * Releases the atlas texture.
     *
     * @param renderer the interface renderer that created it
     */
    public void close(@NotNull InterfaceRenderer renderer) {
        if (this.texture != 0) {
            renderer.deleteTexture(this.texture);
        }
    }
}
