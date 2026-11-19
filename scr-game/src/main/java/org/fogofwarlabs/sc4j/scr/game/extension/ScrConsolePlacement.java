package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a plugin places the console of a classic screen: the top-left corners of console dialogs and of their controls moved in the classic screen,
 * the dialogs and controls not shown, the mask of the pixels where the console and its dialogs are shown, and the pictures the dialogs show over it
 * ({@link #getSurfaces()}). A dialog or control that is neither moved nor hidden keeps its place in its dialog; a control is named by its
 * identifier in the dialog's definition. Without a mask the whole console is shown. The change count grows with every move, every hidden dialog or
 * control and every mask change.
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
        MENU,
        /**
         * The text box of the chat.
         */
        TEXT_BOX
    }

    /**
     * The place of one control: moved to a corner, hidden, or both.
     */
    private static final class ControlPlace {
        private boolean moved;
        private boolean hidden;
        private int left;
        private int top;

        /**
         * Creates a {@link ControlPlace} instance that is neither moved nor hidden.
         */
        private ControlPlace() {
            this.moved  = false;
            this.hidden = false;
            this.left   = 0;
            this.top    = 0;
        }
    }

    private final int width;
    private final int height;
    private final @NotNull ScrScreenOverlay surfaces;
    private final boolean @NotNull [] moved;
    private final boolean @NotNull [] hidden;
    private final int @NotNull [] lefts;
    private final int @NotNull [] tops;
    private final boolean @NotNull [] mask;
    private final @NotNull Map<Dialog, Map<Integer, ControlPlace>> controls;

    private boolean masked;
    private long changeCount;

    /**
     * Creates a {@link ScrConsolePlacement} instance with no moved dialog or control, no mask and no surface pixel.
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
        this.controls = new EnumMap<>(Dialog.class);

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
     * @return the number of dialog and control moves, hidden dialogs and controls and mask changes so far
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
     * @return {@code true} if the dialog is hidden, {@code false} otherwise
     */
    public boolean isDialogHidden(@NotNull Dialog dialog) {
        return this.hidden[dialog.ordinal()];
    }

    /**
     * @param dialog the dialog
     * @return {@code true} if the dialog is moved, a hidden dialog included, {@code false} otherwise
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
     * Moves a control of a dialog and shows it.
     *
     * @param dialog  the dialog
     * @param control the control identifier
     * @param left    the screen column of its left edge
     * @param top     the screen row of its top edge
     */
    public void moveControl(@NotNull Dialog dialog, int control, int left, int top) {
        ControlPlace place = this.placeControl(dialog, control);

        if (place.moved && !place.hidden && place.left == left && place.top == top) {
            return;
        }

        place.moved  = true;
        place.hidden = false;
        place.left   = left;
        place.top    = top;
        this.changeCount++;
    }

    /**
     * Hides a control of a dialog: it is neither drawn nor hit.
     *
     * @param dialog  the dialog
     * @param control the control identifier
     */
    public void hideControl(@NotNull Dialog dialog, int control) {
        ControlPlace place = this.placeControl(dialog, control);

        if (place.hidden) {
            return;
        }

        place.hidden = true;
        this.changeCount++;
    }

    /**
     * @param dialog the dialog
     * @return the identifiers of the dialog's moved or hidden controls, in the order they were first placed
     */
    public int @NotNull [] getPlacedControls(@NotNull Dialog dialog) {
        return this.controls.getOrDefault(dialog, Map.of()).keySet().stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * @param dialog  the dialog
     * @param control the control identifier
     * @return {@code true} if the control is hidden, {@code false} otherwise
     */
    public boolean isControlHidden(@NotNull Dialog dialog, int control) {
        ControlPlace place = this.controls.getOrDefault(dialog, Map.of()).get(control);

        return place != null && place.hidden;
    }

    /**
     * @param dialog  the dialog
     * @param control the control identifier
     * @return {@code true} if the control is moved, a hidden control included, {@code false} otherwise
     */
    public boolean isControlMoved(@NotNull Dialog dialog, int control) {
        ControlPlace place = this.controls.getOrDefault(dialog, Map.of()).get(control);

        return place != null && place.moved;
    }

    /**
     * @param dialog  the dialog
     * @param control the identifier of a moved control
     * @return the screen column of its left edge
     * @throws IllegalStateException if the control is not moved
     */
    public int getControlLeft(@NotNull Dialog dialog, int control) {
        return this.requireMoved(dialog, control).left;
    }

    /**
     * @param dialog  the dialog
     * @param control the identifier of a moved control
     * @return the screen row of its top edge
     * @throws IllegalStateException if the control is not moved
     */
    public int getControlTop(@NotNull Dialog dialog, int control) {
        return this.requireMoved(dialog, control).top;
    }

    /**
     * @return {@code true} if the console is shown only where the mask shows it, {@code false} otherwise
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

    private @NotNull ControlPlace placeControl(@NotNull Dialog dialog, int control) {
        return this.controls.computeIfAbsent(dialog, key -> new LinkedHashMap<>()).computeIfAbsent(control, identifier -> new ControlPlace());
    }

    private @NotNull ControlPlace requireMoved(@NotNull Dialog dialog, int control) {
        ControlPlace place = this.controls.getOrDefault(dialog, Map.of()).get(control);

        if (place == null || !place.moved) {
            throw Exceptions.newIllegalStateException("The control {0} of the dialog {1} is not moved.", control, dialog);
        }

        return place;
    }
}
