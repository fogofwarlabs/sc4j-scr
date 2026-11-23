package org.fogofwarlabs.sc4j.scr.client.glue.popup;

import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueContext;
import org.fogofwarlabs.sc4j.scr.client.glue.GluePalette;
import org.fogofwarlabs.sc4j.scr.client.glue.GluePopup;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ControlPainterUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Draws save and load popups in the game frame, using game fonts and race sheets over a game or glue assets over menus.
 */
abstract class GlueFramedPopup extends GluePopup {
    /**
     * The text inset of a text box, the list rows' text indent.
     */
    private static final int FIELD_TEXT_INSET = 2;
    /**
     * The interface width the dialogs' rectangles are laid out in.
     */
    private static final int LAYOUT_WIDTH = GlueCanvas.INTERFACE_WIDTH;
    /**
     * Halves the interface width beyond {@link #LAYOUT_WIDTH}, which centers the layout in the interface.
     */
    private static final int CENTER_DIVISOR = 2;
    /**
     * The game's dialog sheets by console race (zerg, terran, protoss: {@code game\consoles\<race>\dlg.grp}).
     */
    private static final @NotNull String @NotNull [] CONSOLE_SHEETS = {
            "game/consoles/zerg/dlg.dds.grp", "game/consoles/terran/dlg.dds.grp", "game/consoles/protoss/dlg.dds.grp"
    };

    /**
     * Computes the left edge of a dialog whose rectangle is laid out in the {@link GlueCanvas#INTERFACE_WIDTH} wide
     * interface: the dialog rectangle's left edge plus the centered interface anchor.
     *
     * @param interfaceWidth the current interface width in interface pixels
     * @param dialogLeft     the dialog rectangle's left edge
     * @return the dialog's left edge in interface pixels
     */
    static float getAnchoredDialogLeft(float interfaceWidth, int dialogLeft) {
        return (interfaceWidth - LAYOUT_WIDTH) / CENTER_DIVISOR + dialogLeft;
    }

    private final int consoleRace;

    /**
     * Creates a {@link GlueFramedPopup} instance.
     *
     * @param dialogName    the dialog file name, for example {@code savegame}
     * @param paletteFolder the palette folder of the screen below
     * @param consoleRace   the console race of the game below ({@code 0} zerg, {@code 1} terran, {@code 2} protoss), or {@code -1} over a glue screen
     */
    GlueFramedPopup(@NotNull String dialogName, @NotNull String paletteFolder, int consoleRace) {
        super(dialogName, paletteFolder, null);

        this.consoleRace = consoleRace;
    }

    /**
     * @return {@code true} if the popup is shown over a game, {@code false} otherwise
     */
    protected final boolean isOverGame() {
        return this.consoleRace >= 0;
    }

    /**
     * @return {@code true} if the screen is a game dialog shown through the glue, whose text takes the game's font set, {@code false} otherwise
     */
    @Override
    protected boolean isGameDialog() {
        return this.isOverGame();
    }

    /**
     * @return the dialog sheet the screen's buttons, lists, combo boxes and sliders draw from: the console race's sheet over a game, or the glue's {@link #DIALOG_SHEET} over a glue screen
     */
    @Override
    protected @NotNull String getDialogSheet() {
        return this.isOverGame() ? CONSOLE_SHEETS[Math.min(this.consoleRace, CONSOLE_SHEETS.length - 1)] : super.getDialogSheet();
    }

    /**
     * @return the dialog's left edge in interface pixels: the dialog rectangle's left edge plus the centered interface anchor
     */
    @Override
    protected float getDialogLeft() {
        return getAnchoredDialogLeft(this.getContext().getCanvas().getInterfaceWidth(), this.getDialog().getX());
    }

    /**
     * @return the dialog's top edge in interface pixels: the dialog rectangle's top edge
     */
    @Override
    protected float getDialogTop() {
        return this.getDialog().getY();
    }

    /**
     * @return the horizontal text inset of edit controls, {@code 2} here
     */
    @Override
    protected int getEditTextInset() {
        return FIELD_TEXT_INSET;
    }


    /**
     * Loads the palette whose color codes the screen's controls draw their text in: the screen's palette folder.
     * <p>
     * Over a game, the palette takes the game's text colors.
     *
     * @param glueContext the shared services
     * @return the palette
     * @throws IOException if the palette cannot be read
     */
    @Override
    protected @NotNull GluePalette loadPalette(@NotNull GlueContext glueContext) throws IOException {
        GluePalette palette = super.loadPalette(glueContext);

        return this.isOverGame() ? palette.createWithCodeColors(InterfaceText.GAME_CODE_COLORS) : palette;
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * The default button takes the focus, and every push button takes the popup button style.
     *
     * @param glueContext the shared services
     * @throws IOException           if an asset cannot be read
     * @throws IllegalStateException if the screen is not open
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) throws IOException {
        this.requireDialogState().setDefaultButtonFocus(true);

        for (DialogControl control : this.getDialog().getControls()) {
            if (ScrDialogControlUtils.getType(control) == ScrControlType.PUSHBUTTON ||
                ScrDialogControlUtils.getType(control) == ScrControlType.DEFPUSHBUTTON) {
                this.requireDialogState().requireControl(control.getId()).setButtonStyle(ScrDialog.POPUP_BUTTON_STYLE);
            }
        }
    }

    /**
     * Draws the panel image and then the controls in child order.
     * <p>
     * The framed popup has no panel image: it first updates the dialog's focused control from the edit focus and draws the popup frame.
     *
     * @param canvas the glue canvas
     * @throws IOException           if an image cannot be read
     * @throws IllegalStateException if the screen is not open
     */
    @Override
    protected void drawControls(@NotNull GlueCanvas canvas) throws IOException {
        // A text box with the keyboard focus is the dialog's focused control: the default button then has no focus.
        int focused = ScrDialog.NONE;

        for (DialogControl control : this.getDialog().getControls()) {
            if (ScrDialogControlUtils.getType(control) == ScrControlType.EDITTEXT && this.isFocusedEdit(control)) {
                focused = control.getId();
            }
        }

        this.requireDialogState().setFocused(this.findControl(focused));
        canvas.drawPopupFrame(this.getDialogLeft(), this.getDialogTop(), this.getDialog().getWidth(), this.getDialog().getHeight());
        super.drawControls(canvas);
    }

    /**
     * Draws one control.
     * <p>
     * A text box first gets its field border, drawn as focused while the box has the keyboard focus.
     *
     * @param canvas  the glue canvas
     * @param control the control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IOException if an image cannot be read
     */
    @Override
    protected void drawControl(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        if (ScrDialogControlUtils.getType(control) == ScrControlType.EDITTEXT) {
            ControlPainterUtils.drawFieldBorder(canvas, left, top, control.getWidth(), control.getHeight(),
                                                this.isFocusedEdit(control));
        }

        super.drawControl(canvas, control, left, top);
    }
}
