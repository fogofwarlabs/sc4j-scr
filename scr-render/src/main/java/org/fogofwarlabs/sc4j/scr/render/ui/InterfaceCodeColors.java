package org.fogofwarlabs.sc4j.scr.render.ui;

import org.fogofwarlabs.sc4j.engine.client.text.TextColorHook;
import org.jetbrains.annotations.NotNull;

/**
 * Holds a table of interface text code colors as an engine {@link TextColorHook}: the codes {@link InterfaceFont#isColorCode} accepts switch to
 * the table's color of the code (a code past the table takes the game's {@code tfontgam} color, {@link InterfaceFont#getCodeColor}) and code
 * {@code 1} switches back to the color before the last code.
 * <p>
 * The table is read where it is, not copied: the game table {@link InterfaceText#GAME_CODE_COLORS} or the {@code tfont.pcx} table of a glue
 * palette.
 */
public final class InterfaceCodeColors implements TextColorHook {
    private final int @NotNull [] codeColors;

    /**
     * Creates an {@link InterfaceCodeColors} instance over a code color table.
     *
     * @param codeColors the packed {@code 0xRRGGBBAA} colors by code, usually {@code 32} entries
     */
    public InterfaceCodeColors(int @NotNull [] codeColors) {
        this.codeColors = codeColors;
    }

    /**
     * @param codePoint the character
     * @return {@code true} if the character is an interface color code, including the restore code {@code 1}, {@code false} otherwise
     */
    @Override
    public boolean isColorCode(int codePoint) {
        return InterfaceFont.isColorCode(codePoint);
    }

    /**
     * Finds the pen color after a color code: the previous color for the restore code, otherwise the table's color of the code.
     *
     * @param codePoint     the color code, one {@link #isColorCode} accepts
     * @param lineColor     the packed {@code 0xRRGGBBAA} color the line started in, for codes that restore it
     * @param currentColor  the packed color of the pen before the code
     * @param previousColor the packed color of the pen before the code that set the current color (the line's color before the first code), for codes that switch back
     * @return the packed color of the following characters
     */
    @Override
    public int getColor(int codePoint, int lineColor, int currentColor, int previousColor) {
        if (codePoint == InterfaceFont.RESTORE_COLOR_CODE) {
            return previousColor;
        }

        return codePoint < this.codeColors.length ? this.codeColors[codePoint] : InterfaceFont.getCodeColor(codePoint);
    }
}
