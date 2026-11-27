package org.fogofwarlabs.sc4j.scr.client.extension;

import org.jetbrains.annotations.NotNull;

/**
 * The text a message line shows in place of its bytes ({@link ScrMessageAreaPresenter#findSubstitute}), with the style and color it starts
 * with.
 */
public final class ScrMessageSubstitute {
    private final @NotNull String text;
    private final int style;
    private final int color;

    /**
     * Creates a {@link ScrMessageSubstitute} instance.
     *
     * @param text  the text, empty for a line that shows nothing
     * @param style the style code the text starts with
     * @param color the packed {@code 0xRRGGBBAA} starting color, or {@code 0} when the text starts with its style code
     */
    public ScrMessageSubstitute(@NotNull String text, int style, int color) {
        this.text  = text;
        this.style = style;
        this.color = color;
    }

    /**
     * @return the text, empty for a line that shows nothing
     */
    public @NotNull String getText() {
        return this.text;
    }

    /**
     * @return the style code the text starts with
     */
    public int getStyle() {
        return this.style;
    }

    /**
     * @return the packed {@code 0xRRGGBBAA} starting color, or {@code 0} when the text starts with its style code
     */
    public int getColor() {
        return this.color;
    }
}
