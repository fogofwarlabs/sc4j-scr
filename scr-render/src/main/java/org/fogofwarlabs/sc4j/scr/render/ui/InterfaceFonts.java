package org.fogofwarlabs.sc4j.scr.render.ui;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.render.WorldRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Holds the four interface font slots (small, normal, big, bigger) of the game or the glue set, loaded once with one shared TrueType face chain, and
 * applies the text-mode rule: HD output draws TrueType when {@code k = clamp(ceil(outputHeight / 480), 1, 4) > 1}; SD draws the {@code .fnt} bitmaps
 * unless the locale's text is not mapped onto them (Korean, Chinese, Japanese).
 */
public final class InterfaceFonts implements AutoCloseable {
    /**
     * Slot {@code 0}: {@code font8}, {@code RC_SMALLTEXT}.
     */
    public static final int SMALL = 0;
    /**
     * Slot {@code 1}: {@code font10}, the default dialog font.
     */
    public static final int NORMAL = 1;
    /**
     * Slot {@code 2}: {@code font16}, {@code RC_BIGTEXT}.
     */
    public static final int BIG = 2;
    /**
     * Slot {@code 3}: {@code font16x}, {@code RC_BIGGERTEXT}.
     */
    public static final int BIGGER = 3;

    /**
     * The bit of a web text slot ({@link #getWebSlot}).
     */
    private static final int WEB = 1 << 24;
    /**
     * The bit of a web slot laid out in its family's line box alone ({@link #toInputLineBox}).
     */
    private static final int WEB_INPUT = 1 << 25;
    /**
     * The web slot layout: the family above the line height above the size, each in its own bit field.
     */
    private static final int WEB_FAMILY_SHIFT = 22;
    private static final int WEB_FAMILY_MASK = 3;
    private static final int WEB_LINE_SHIFT = 11;
    private static final int WEB_FIELD = (1 << WEB_LINE_SHIFT) - 1;
    /**
     * Web sizes are kept in quarter pixels.
     */
    private static final float WEB_UNITS = 4;
    /**
     * The web pages' {@code 640x480} layout: a CSS pixel is an interface pixel of the {@code 480}-line interface.
     */
    private static final float WEB_PAGE_HEIGHT = InterfaceTransform.INTERFACE_HEIGHT;
    /**
     * The zoom of the web pages before the first viewport.
     */
    private static final float DEFAULT_WEB_ZOOM = 1;
    private static final int @NotNull [] TRUE_TYPE_SLOTS = {TrueTypeText.SMALL, TrueTypeText.MEDIUM, TrueTypeText.BIG, TrueTypeText.BIGGER};
    /**
     * The locales whose text is not mapped onto the {@code .fnt} bitmaps.
     */
    private static final @NotNull String @NotNull [] NON_LATIN_LOCALES = {"ko", "zh", "ja"};

    /**
     * Names the font sets: the game slots (section {@code Game}) and the glue slots (section {@code Glue}, with their own TrueType sizes).
     */
    public enum Set {
        /**
         * The game set: {@code font8}, {@code font10}, {@code font16}, {@code font16x}.
         */
        GAME(new String[]{"font8", "font10", "font16", "font16x"}),
        /**
         * The glue set: {@code font10}, {@code font14}, {@code font16}, {@code font16x}.
         */
        GLUE(new String[]{"font10", "font14", "font16", "font16x"});

        private final @NotNull String @NotNull [] fontNames;

        Set(@NotNull String @NotNull [] fontNames) {
            this.fontNames = fontNames;
        }
    }

    /**
     * Returns the slot of a web text style using its face family, CSS size and line height ({@link TrueTypeText#getWebSlot}). The slot can be passed
     * wherever a font slot is taken.
     *
     * @param family     the page's {@code font-family}: {@link TrueTypeText#WEB_REGULAR_FAMILY} ({@code eurostile-reg}), {@link TrueTypeText#WEB_EXTENDED_FAMILY} ({@code eurostileext-reg}) or {@link TrueTypeText#WEB_ARIAL_FAMILY} ({@code Arial}), each followed by the Hangul face
     * @param size       the CSS font size in interface pixels (the page's {@code 640}-wide stylesheet)
     * @param lineHeight the CSS line height in interface pixels, or {@code 0} for {@code line-height: normal}
     * @return the slot
     */
    public static int getWebSlot(int family, float size, float lineHeight) {
        return WEB | (family & WEB_FAMILY_MASK) << WEB_FAMILY_SHIFT |
               Math.round(lineHeight * WEB_UNITS) << WEB_LINE_SHIFT | Math.round(size * WEB_UNITS);
    }

    /**
     * Returns the slot of a web text style as a page's text field ({@code <input>}) lays it out: one line box from the family's face, the baseline
     * fixed whatever face a glyph comes from, so that Latin and Hangul text sit on one baseline and the text stays centered as the Latin line is. A
     * {@code line-height: normal} paragraph instead grows its line box for a line that falls back to the Hangul face
     * ({@link TrueTypeText#getWebSlot}). Other slots are returned unchanged.
     *
     * @param slot a font slot
     * @return the slot with the single line box
     */
    public static int toInputLineBox(int slot) {
        return isWebSlot(slot) ? slot | WEB_INPUT : slot;
    }

    /**
     * Tests whether a font slot is a web text slot.
     *
     * @param slot a font slot
     * @return {@code true} if it is a web text slot ({@link #getWebSlot}), {@code false} otherwise
     */
    public static boolean isWebSlot(int slot) {
        return (slot & WEB) != 0;
    }

    /**
     * Returns the font slot of control flags.
     *
     * @param small  whether the control has {@code RC_SMALLTEXT}
     * @param big    whether the control has {@code RC_BIGTEXT}
     * @param bigger whether the control has {@code RC_BIGGERTEXT}
     * @return the slot
     */
    public static int getSlot(boolean small, boolean big, boolean bigger) {
        return small ? SMALL : big ? BIG : bigger ? BIGGER : NORMAL;
    }

    /**
     * Loads the fonts of a set. A font that cannot be loaded releases the fonts loaded before it and the TrueType chain.
     *
     * @param resources the resource provider of the installation
     * @param renderer  the interface renderer that owns the glyph textures
     * @param set       the game or glue set
     * @param trueType  the TrueType chain of the fonts
     * @return the fonts in slot order
     * @throws IOException if a font is missing or malformed
     */
    private static @NotNull InterfaceFont @NotNull [] loadFonts(@NotNull ScrResourceProvider resources, @NotNull InterfaceRenderer renderer,
                                                                @NotNull Set set, @NotNull TrueTypeText trueType) throws IOException {
        InterfaceFont[] fonts = new InterfaceFont[set.fontNames.length];

        try {
            for (int slot = 0; slot < fonts.length; slot++) {
                fonts[slot] = InterfaceFont.load(resources, renderer, set.fontNames[slot]);
                fonts[slot].setTrueType(trueType, TRUE_TYPE_SLOTS[slot]);
            }
        } catch (IOException | RuntimeException exception) {
            closeFonts(renderer, fonts, trueType);

            throw exception;
        }

        return fonts;
    }

    /**
     * Releases fonts' atlas textures in reverse slot order, then the TrueType chain.
     *
     * @param renderer the interface renderer that owns the glyph textures
     * @param fonts    the fonts; the slots after a failed load are empty
     * @param trueType the TrueType chain of the fonts
     */
    private static void closeFonts(@NotNull InterfaceRenderer renderer, @Nullable InterfaceFont @NotNull [] fonts, @NotNull TrueTypeText trueType) {
        for (int index = fonts.length - 1; index >= 0; index--) {
            InterfaceFont font = fonts[index];

            if (font != null) {
                IgnoreException.execute(
                        () -> font.close(renderer)
                );
            }
        }

        IgnoreException.execute(
                trueType::close
        );
    }

    private final @NotNull InterfaceRenderer renderer;

    private final @NotNull TrueTypeText trueType;
    private final @NotNull InterfaceFont @NotNull [] fonts;
    /**
     * The web text fonts made so far ({@link #getWebSlot}), by slot.
     */
    private final @NotNull Map<Integer, InterfaceFont> webFonts;

    private boolean nonLatinLocale;
    private boolean trueTypeMode;
    private int renderScale;

    /**
     * The device pixels per CSS pixel of the web pages ({@link TrueTypeText#setWebZoom}).
     */
    private float webZoom;

    /**
     * Creates an {@link InterfaceFonts} instance with the four fonts and the TrueType chain of the game set.
     *
     * @param resources the resource provider of the installation
     * @param renderer  the interface renderer that owns the glyph textures
     * @param locale    the text locale, for example {@code koKR}
     * @throws IOException if a font is missing or malformed
     */
    public InterfaceFonts(@NotNull ScrResourceProvider resources, @NotNull InterfaceRenderer renderer, @NotNull String locale) throws IOException {
        this(resources, renderer, locale, Set.GAME, List.of());
    }

    /**
     * Creates an {@link InterfaceFonts} instance with the four fonts and the TrueType chain of a font set.
     *
     * @param resources       the resource provider of the installation; the TrueType chain reads its faces from the provider's storage
     * @param renderer        the interface renderer that owns the glyph textures
     * @param locale          the text locale, for example {@code koKR}
     * @param set             the game or glue set
     * @param fontDirectories the permitted machine-wide font directories in search order
     * @throws IOException if a font is missing or malformed
     */
    public InterfaceFonts(@NotNull ScrResourceProvider resources, @NotNull InterfaceRenderer renderer,
                          @NotNull String locale, @NotNull Set set, @NotNull List<Path> fontDirectories) throws IOException {
        this.renderer = renderer;

        this.trueType = new TrueTypeText(resources.getStorage(), renderer, set == Set.GLUE, fontDirectories);
        this.fonts    = loadFonts(resources, renderer, set, this.trueType);
        this.webFonts = new HashMap<>();

        this.nonLatinLocale = false;
        this.trueTypeMode   = false;
        this.renderScale    = 1;

        this.webZoom = DEFAULT_WEB_ZOOM;

        this.setLocale(locale);
    }

    /**
     * Returns the font of a slot: a web text slot's TrueType font ({@link #getWebSlot}), else the slot clamped to {@link #SMALL} .. {@link #BIGGER}.
     *
     * @param slot the font slot, {@link #SMALL} .. {@link #BIGGER}, or a web text slot
     * @return the font
     */
    public @NotNull InterfaceFont getFont(int slot) {
        if (isWebSlot(slot)) {
            return this.getWebFont(slot);
        }

        return this.fonts[Math.clamp(slot, SMALL, BIGGER)];
    }

    private @NotNull InterfaceFont getWebFont(int slot) {
        InterfaceFont cachedFont = this.webFonts.get(slot);

        if (cachedFont != null) {
            return cachedFont;
        }

        int family = slot >> WEB_FAMILY_SHIFT & WEB_FAMILY_MASK;
        float size = (slot & WEB_FIELD) / WEB_UNITS;
        float lineHeight = (slot >> WEB_LINE_SHIFT & WEB_FIELD) / WEB_UNITS;
        int chain = this.trueType.getWebSlot(family, size, lineHeight, false);

        if (chain < 0) {
            return this.fonts[SMALL];
        }

        int fallback = (slot & WEB_INPUT) != 0 ? chain : this.trueType.getWebSlot(family, size, lineHeight, true);
        InterfaceFont webFont = InterfaceFont.ofTrueType(this.trueType, chain, fallback);

        webFont.setMode(this.trueTypeMode, this.renderScale);
        this.webFonts.put(slot, webFont);

        return webFont;
    }

    /**
     * Sets the text locale (the TrueType fallback order and the SD text path).
     *
     * @param locale the text locale
     */
    public void setLocale(@NotNull String locale) {
        this.trueType.setLocale(locale);

        String language = locale.toLowerCase(Locale.ROOT);
        boolean nonLatin = false;

        for (String prefix : NON_LATIN_LOCALES) {
            nonLatin |= language.startsWith(prefix);
        }

        this.nonLatinLocale = nonLatin;
        this.applyMode();
    }


    /**
     * Sets the output and applies the text-mode rule for it: the render scale {@code k} of {@link TrueTypeText#calculateRenderScale}, then TrueType
     * text in HD when {@code k > 1} and in SD for the non-Latin locales.
     *
     * @param set          the shown asset set
     * @param outputWidth  the output width in pixels
     * @param outputHeight the output height in pixels
     */
    public void setViewport(AssetResolver.@NotNull AssetSet set, float outputWidth, float outputHeight) {
        this.renderScale  = TrueTypeText.calculateRenderScale(outputWidth, outputHeight, set.getScale());
        this.trueTypeMode = set.isHd() ? this.renderScale > 1 : this.nonLatinLocale;
        // SD text is drawn on the SD compose target (WorldRenderer.beginSdInterface).
        this.trueType.setFieldGlyphs(set.isHd(), set.isHd() ? this.renderScale : WorldRenderer.SD_COMPOSE_SCALE);

        // The web pages are zoomed with the window in HD and drawn at the SD compose size in SD; their fonts' line metrics
        // depend on it.
        float zoom = set.isHd() ? outputHeight / WEB_PAGE_HEIGHT : WorldRenderer.SD_COMPOSE_SCALE;

        if (zoom != this.webZoom) {
            this.webZoom = zoom;
            this.trueType.setWebZoom(zoom);
            this.webFonts.clear();
        }

        this.applyMode();
    }

    private void applyMode() {
        for (InterfaceFont font : this.fonts) {
            font.setMode(this.trueTypeMode, this.renderScale);
        }

        for (InterfaceFont font : this.webFonts.values()) {
            font.setMode(this.trueTypeMode, this.renderScale);
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
     * @implSpec Releases the fonts' atlas textures and the TrueType chain.
     */
    @Override
    public void close() {
        this.webFonts.clear();

        closeFonts(this.renderer, this.fonts, this.trueType);
    }
}
