package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrDrawList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The game screen of the local machine as the client last drew it: the interface size in interface units, the real pixels per interface unit, the
 * graphics and the measure of the interface fonts. The client supplies it for every drawn frame; until then the screen is the classic
 * {@value #CLASSIC_WIDTH} by {@value #CLASSIC_HEIGHT} screen at one pixel per unit in the classic graphics, and texts measure {@code 0}.
 */
public final class LocalDisplay {
    /**
     * The interface width of the classic screen.
     */
    public static final int CLASSIC_WIDTH = 640;
    /**
     * The interface height of every screen.
     */
    public static final int CLASSIC_HEIGHT = 480;

    /**
     * Measures texts in the interface fonts of the client.
     */
    @FunctionalInterface
    public interface TextMeasure {
        /**
         * Measures a text: the width of its widest line, the lines broken at line feeds; colour codes do not advance.
         *
         * @param text the text
         * @param font the font
         * @return the width in interface units
         */
        float measureText(@NotNull String text, ScrDrawList.@NotNull Font font);
    }

    private int interfaceWidth;
    private int interfaceHeight;
    private float scale;
    private AssetResolver.@NotNull AssetSet graphics;
    private @Nullable TextMeasure textMeasure;
    private boolean known;

    /**
     * Creates a {@link LocalDisplay} instance with the classic screen, not yet supplied.
     */
    public LocalDisplay() {
        this.interfaceWidth  = CLASSIC_WIDTH;
        this.interfaceHeight = CLASSIC_HEIGHT;
        this.scale           = 1;
        this.graphics        = AssetResolver.AssetSet.SD;
        this.textMeasure     = null;
        this.known           = false;
    }

    /**
     * @return {@code true} if the client supplied the screen, {@code false} otherwise
     */
    public boolean isKnown() {
        return this.known;
    }

    /**
     * @return the interface width in interface units: {@value #CLASSIC_WIDTH}, or wider for a widescreen interface
     */
    public int getInterfaceWidth() {
        return this.interfaceWidth;
    }

    /**
     * @return the interface height in interface units
     */
    public int getInterfaceHeight() {
        return this.interfaceHeight;
    }

    /**
     * @return the real pixels per interface unit
     */
    public float getScale() {
        return this.scale;
    }

    /**
     * @return the graphics the client draws
     */
    public AssetResolver.@NotNull AssetSet getGraphics() {
        return this.graphics;
    }

    /**
     * Sets the game screen of the frame the client draws.
     *
     * @param interfaceWidth  the interface width in interface units
     * @param interfaceHeight the interface height in interface units
     * @param scale           the real pixels per interface unit
     * @param graphics        the graphics
     * @throws IllegalArgumentException if a size or the scale is not positive
     */
    public void setScreen(int interfaceWidth, int interfaceHeight, float scale, AssetResolver.@NotNull AssetSet graphics) {
        if (interfaceWidth <= 0 || interfaceHeight <= 0 || !(scale > 0)) {
            throw Exceptions.newIllegalArgumentException("Invalid screen {0} by {1} at scale {2}", interfaceWidth, interfaceHeight, scale);
        }

        this.interfaceWidth  = interfaceWidth;
        this.interfaceHeight = interfaceHeight;
        this.scale           = scale;
        this.graphics        = graphics;
        this.known           = true;
    }

    /**
     * Sets the measure of the client's interface fonts.
     *
     * @param textMeasure the measure, or {@code null} while the client has no fonts
     */
    public void setTextMeasure(@Nullable TextMeasure textMeasure) {
        this.textMeasure = textMeasure;
    }

    /**
     * Measures a text in an interface font of the client ({@link TextMeasure#measureText}).
     *
     * @param text the text
     * @param font the font
     * @return the width in interface units, {@code 0} while the client supplied no measure
     */
    public float measureText(@NotNull String text, ScrDrawList.@NotNull Font font) {
        TextMeasure measure = this.textMeasure;

        return measure == null ? 0 : measure.measureText(text, font);
    }
}
