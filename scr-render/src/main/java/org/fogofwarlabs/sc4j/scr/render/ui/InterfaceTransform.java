package org.fogofwarlabs.sc4j.scr.render.ui;

import org.fogofwarlabs.sc4j.engine.client.host.AspectRange;
import org.jetbrains.annotations.NotNull;

/**
 * Maps interface coordinates to screen pixels with a uniform scale and an offset. The game console and menu screens use different vertical anchors.
 */
public interface InterfaceTransform {
    /**
     * The interface height in interface pixels.
     */
    int INTERFACE_HEIGHT = 480;
    /**
     * The original game width: the interface width with the original aspect ratio or the classic graphics.
     */
    int ORIGINAL_WIDTH = 640;
    /**
     * The narrowest aspect ratio of the widescreen interface ({@code 4:3}).
     */
    float MINIMUM_ASPECT_RATIO = 4.0f / 3;
    /**
     * The widest aspect ratio of the widescreen interface ({@code 16:9}).
     */
    float MAXIMUM_ASPECT_RATIO = 16.0f / 9;
    /**
     * The aspect range of the widescreen interface: {@link #INTERFACE_HEIGHT} high, {@link #MINIMUM_ASPECT_RATIO} to {@link #MAXIMUM_ASPECT_RATIO}.
     */
    @NotNull AspectRange WIDESCREEN_RANGE = new AspectRange(INTERFACE_HEIGHT, MINIMUM_ASPECT_RATIO, MAXIMUM_ASPECT_RATIO);

    /**
     * Calculates the interface width from the screen aspect ratio. Uses the original width when widescreen is disabled or the height is nonpositive;
     * otherwise clamps the aspect ratio to the supported range and truncates the scaled width to an integer ({@link AspectRange#getLogicalWidth}).
     *
     * @param widescreen whether the game may be wider than {@code 640}: remastered graphics without the original aspect ratio
     * @param width      the window width
     * @param height     the window height
     * @return the interface width {@code W}
     */
    static int getInterfaceWidth(boolean widescreen, float width, float height) {
        if (!widescreen || height <= 0) {
            return ORIGINAL_WIDTH;
        }

        return WIDESCREEN_RANGE.getLogicalWidth(width, height);
    }

    /**
     * Calculates the left edge of an original-width dialog within the interface. Truncates the interface width before integer division and returns
     * {@code 0} when it is not wider than the dialog.
     *
     * @param interfaceWidth the interface width {@code W}
     * @return the dialog's horizontal offset
     */
    static int getCenteredDialogLeft(float interfaceWidth) {
        return Math.max(0, (int) interfaceWidth - ORIGINAL_WIDTH) / 2;
    }


    /**
     * @return the screen pixels per interface pixel
     */
    float getScale();

    /**
     * @return the logical width {@code W} of the interface pixels the transform maps ({@link AspectRange#getLogicalWidth}): {@code 640}, or wider for the widescreen interface
     */
    float getLogicalWidth();


    /**
     * Converts an interface x to a screen x.
     *
     * @param x an interface x
     * @return the screen x
     */
    float toScreenX(float x);

    /**
     * Converts an interface y to a screen y.
     *
     * @param y an interface y
     * @return the screen y
     */
    float toScreenY(float y);
}
