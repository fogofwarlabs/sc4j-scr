package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

/**
 * How a plugin places the console of a classic screen: the top-left corners of console dialogs moved in the classic screen, the dialogs not shown,
 * the mask of the pixels where the console is shown, and the pictures the dialogs show over it ({@link #getSurfaces()}). A dialog that is neither
 * moved nor hidden keeps its place; without a mask the whole console is shown. The change count grows with every move, every hidden dialog and
 * every mask change.
 */
public final class ScrConsolePlacement {
    /**
     * A console dialog.
     */
    public enum Dialog {
        /**
         * The command card.
         */
        COMMAND_CARD,
        /**
         * The selection panel.
         */
        SELECTION,
        /**
         * The resource display.
         */
        RESOURCES,
        /**
         * The portrait.
         */
        PORTRAIT,
        /**
         * The minimap and its buttons.
         */
        MINIMAP,
        /**
         * The menu button.
         */
        MENU
    }

    private final int width;
    private final int height;
    private final boolean @NotNull [] moved;
    private final boolean @NotNull [] hidden;
    private final int @NotNull [] lefts;
    private final int @NotNull [] tops;
    private final boolean @NotNull [] mask;
    private final @NotNull ScrScreenOverlay surfaces;

    private boolean masked;
    private long changeCount;

    /**
     * Creates a {@link ScrConsolePlacement} instance with no moved dialog, no mask and no surface pixel.
     *
     * @param width  the screen width in pixels
     * @param height the screen height in pixels
     * @throws IllegalArgumentException if a size is not positive
     */
    public ScrConsolePlacement(int width, int height) {
        this.width    = width;
        this.height   = height;
        this.surfaces = new ScrScreenOverlay(width, height);
        this.moved    = new boolean[Dialog.values().length];
        this.hidden   = new boolean[Dialog.values().length];
        this.lefts    = new int[Dialog.values().length];
        this.tops     = new int[Dialog.values().length];
        this.mask     = new boolean[width * height];

        this.masked      = false;
        this.changeCount = 0;
    }

    /**
     * @return the screen width in pixels
     */
    public int getWidth() {
        return this.width;
    }

    /**
     * @return the screen height in pixels
     */
    public int getHeight() {
        return this.height;
    }

    /**
     * @return the number of dialog moves and mask changes so far
     */
    public long getChangeCount() {
        return this.changeCount;
    }

    /**
     * Moves a dialog and shows it.
     *
     * @param dialog the dialog
     * @param left   the screen column of its left edge
     * @param top    the screen row of its top edge
     */
    public void moveDialog(@NotNull Dialog dialog, int left, int top) {
        int index = dialog.ordinal();

        if (this.moved[index] && !this.hidden[index] && this.lefts[index] == left && this.tops[index] == top) {
            return;
        }

        this.moved[index]  = true;
        this.hidden[index] = false;
        this.lefts[index]  = left;
        this.tops[index]   = top;
        this.changeCount++;
    }

    /**
     * Hides a dialog: it is neither drawn nor hit.
     *
     * @param dialog the dialog
     */
    public void hideDialog(@NotNull Dialog dialog) {
        int index = dialog.ordinal();

        if (this.hidden[index]) {
            return;
        }

        this.hidden[index] = true;
        this.changeCount++;
    }

    /**
     * @param dialog the dialog
     * @return whether the dialog is hidden
     */
    public boolean isDialogHidden(@NotNull Dialog dialog) {
        return this.hidden[dialog.ordinal()];
    }

    /**
     * @param dialog the dialog
     * @return whether the dialog is moved; a hidden dialog may be moved too
     */
    public boolean isDialogMoved(@NotNull Dialog dialog) {
        return this.moved[dialog.ordinal()];
    }

    /**
     * @param dialog a moved dialog
     * @return the screen column of its left edge
     * @throws IllegalStateException if the dialog is not moved
     */
    public int getDialogLeft(@NotNull Dialog dialog) {
        this.requireMoved(dialog);

        return this.lefts[dialog.ordinal()];
    }

    /**
     * @param dialog a moved dialog
     * @return the screen row of its top edge
     * @throws IllegalStateException if the dialog is not moved
     */
    public int getDialogTop(@NotNull Dialog dialog) {
        this.requireMoved(dialog);

        return this.tops[dialog.ordinal()];
    }

    /**
     * @return whether the console is shown only where the mask shows it
     */
    public boolean isMasked() {
        return this.masked;
    }

    /**
     * Sets whether the console is shown at a pixel and makes the console masked.
     *
     * @param offset the pixel offset, {@code row * width + column}
     * @param shown  whether the console is shown there
     * @throws IndexOutOfBoundsException if the offset is outside the screen
     */
    public void setMaskPixel(int offset, boolean shown) {
        if (this.masked && this.mask[offset] == shown) {
            return;
        }

        this.masked       = true;
        this.mask[offset] = shown;
        this.changeCount++;
    }

    /**
     * @param column the screen column
     * @param row    the screen row
     * @return {@code true} if the console is shown at the pixel: inside the screen, and the console is not masked or the mask shows it
     */
    public boolean isShown(int column, int row) {
        if (column < 0 || row < 0 || column >= this.width || row >= this.height) {
            return false;
        }

        return !this.masked || this.mask[row * this.width + column];
    }

    /**
     * @return the pictures the dialogs show over the console, drawn where the console is shown
     */
    public @NotNull ScrScreenOverlay getSurfaces() {
        return this.surfaces;
    }

    private void requireMoved(@NotNull Dialog dialog) {
        if (!this.moved[dialog.ordinal()]) {
            throw Exceptions.newIllegalStateException("The dialog {0} is not moved.", dialog);
        }
    }
}
