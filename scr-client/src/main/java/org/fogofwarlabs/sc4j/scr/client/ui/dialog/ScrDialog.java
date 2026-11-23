package org.fogofwarlabs.sc4j.scr.client.ui.dialog;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.dialog.Control;
import org.fogofwarlabs.sc4j.engine.client.dialog.Dialog;
import org.fogofwarlabs.sc4j.engine.client.dialog.DialogListener;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;

/**
 * Runs one SC:R dialog on the engine {@link Dialog}: its controls are {@link ScrControl}s that hold the native state, and the
 * engine keeps the hovered, pressed and focused control. The native rules replace the engine's where they differ: pointer hit
 * tests select the last matching top-level control ({@link #findControlAt}: visible, enabled and pressable; {@link #findHoverAt}:
 * visible, disabled ones too), a press does not move the focus, keyboard tests select the first enabled matching control, and
 * Esc and Enter are offered to the dialog's procedure first ({@link #escape(Activator)}, {@link #enter(Activator)}). The dialog procedures
 * take the released control from {@link #release} and activate by control id, as the native procedures switch on it. Coordinates are
 * relative to the loaded dialog and its current control shifts.
 * <p>
 * A screen turns a built control into a widget ({@link ListBox}, {@link ComboBox}, {@link Slider}, {@link EditControl}, a flic
 * button) with {@link #attach}, and draws the plain controls through its procedure painter ({@link #setPainter}).
 */
public final class ScrDialog extends Dialog {
    /**
     * No control.
     */
    public static final int NONE = -1;
    /**
     * The id Esc reports to the dialog's procedure first.
     */
    public static final int ESCAPE_REQUEST = -3;
    /**
     * The id Enter reports to the dialog's procedure when no push button has the focus.
     */
    public static final int ENTER_REQUEST = -2;
    /**
     * The push button style of the game's popups (the dialog sheet's 3-slice bars {@code 112..120}, as the game menu draws them).
     */
    public static final int POPUP_BUTTON_STYLE = 4;

    private static final @NotNull String ESCAPE_FLAG = "CTRL_ESCAPE";
    private static final @NotNull String ENTER_FLAG = "CTRL_ENTER";
    /**
     * Activations are not reported through the engine listener: the procedures read the control {@link #release} returns.
     */
    private static final @NotNull DialogListener RELEASE_RESULT_LISTENER = (dialog, control) -> {
    };

    /**
     * Draws the plain controls of a dialog natively: the dialog's procedure (the glue screen's per-type draw), which holds the
     * strings, palette and time the native look needs.
     */
    public interface Painter {
        /**
         * Draws a plain control of the dialog.
         *
         * @param control the control
         * @param x       the control's left edge in interface pixels, the shift included
         * @param y       the control's top edge in interface pixels, the vertical shift included
         * @throws IOException if a sheet frame or image the control draws cannot be read
         */
        void drawControl(@NotNull ScrControl control, float x, float y) throws IOException;
    }

    /**
     * Receives the activation of a control, or of {@link #ESCAPE_REQUEST} / {@link #ENTER_REQUEST}.
     */
    public interface Activator {
        /**
         * Activates a control.
         *
         * @param control the control id
         * @return {@code true} if the dialog's procedure handled it, {@code false} otherwise
         */
        boolean activate(int control);
    }

    /**
     * @param control a control
     * @return {@code true} if the control takes presses (buttons, lists, combo boxes, scroll bars and edit controls), {@code false} otherwise
     */
    public static boolean isPressable(@NotNull DialogControl control) {
        return switch (ScrDialogControlUtils.getType(control)) {
            case FLCBTN, PUSHBUTTON, DEFPUSHBUTTON, RADIOBUTTON, CHECKBOX, LISTBOX, COMBOBOX, SCROLLBAR, EDITTEXT -> true;
            default -> false;
        };
    }

    private static int getId(@Nullable Control control) {
        return control == null ? NONE : control.getDefinition().getId();
    }


    private boolean defaultButtonFocus;
    private @Nullable Painter painter;

    /**
     * Creates a {@link ScrDialog} instance with a {@link ScrControl} for every control of the definition.
     *
     * @param definition the loaded dialog
     */
    public ScrDialog(@NotNull DialogDefinition definition) {
        super(definition, (dialog, control) -> new ScrControl(control), RELEASE_RESULT_LISTENER, false);

        this.defaultButtonFocus = false;
        this.painter            = null;
    }

    /**
     * Finds the live control of a definition.
     *
     * @param definition the control's definition, one of this dialog's
     * @return the live control, or {@code null} if the definition is not one of this dialog's
     */
    @Override
    public @Nullable ScrControl getControl(@NotNull DialogControl definition) {
        return (ScrControl) super.getControl(definition);
    }

    /**
     * Finds a control by the id its definition gives: the native dialog data and procedures name controls by id.
     *
     * @param id the id of the control's definition
     * @return the first top-level control with the id in drawing order, else the first child with it, or {@code null} if no control has it
     */
    @Override
    public @Nullable ScrControl findControl(int id) {
        return (ScrControl) super.findControl(id);
    }

    /**
     * Finds a control by id.
     *
     * @param id the id of the control's definition
     * @return the first top-level control with the id in drawing order, else the first child with it
     * @throws IllegalArgumentException if the dialog has no control with the id
     */
    public @NotNull ScrControl requireControl(int id) {
        ScrControl control = this.findControl(id);

        if (control == null) {
            throw Exceptions.newIllegalArgumentException("{0} has no control {1}.", this.getDefinition().getName(), id);
        }

        return control;
    }

    /**
     * Finds the live control of a definition: the control built from it, else the control with its id (the procedures may hold
     * the definition of another load of the same dialog file, and the native state belongs to the id).
     *
     * @param definition the control's definition
     * @return the live control
     * @throws IllegalArgumentException if the dialog has neither the definition nor a control with its id
     */
    public @NotNull ScrControl requireControl(@NotNull DialogControl definition) {
        ScrControl control = this.getControl(definition);

        if (control == null) {
            control = this.findControl(definition.getId());
        }

        if (control == null) {
            throw Exceptions.newIllegalArgumentException("{0} has no control {1}.", this.getDefinition().getName(), definition.getId());
        }

        return control;
    }

    /**
     * Puts a widget in the place of the control the dialog built for the widget's definition (or for its id, as
     * {@link #requireControl(DialogControl)} finds it), keeping that control's native state ({@link Dialog#replaceControl}).
     *
     * @param widget the widget, of a definition of this dialog and not in a dialog
     * @param <T>    the widget class
     * @return the widget, now the dialog's control of the definition
     * @throws IllegalArgumentException if the dialog has neither the definition nor a control with its id
     */
    public <T extends ScrControl> @NotNull T attach(@NotNull T widget) {
        ScrControl built = this.requireControl(widget.getDefinition());

        if (built.getDefinition() != widget.getDefinition()) {
            throw Exceptions.newIllegalArgumentException("{0} built control {1} from another load of its dialog.",
                                                         this.getDefinition().getName(), widget.getDefinition().getId());
        }

        return this.replaceControl(built, widget);
    }

    /**
     * Sets the procedure painter that draws the dialog's plain controls ({@link ScrControl#drawAt}).
     *
     * @param painter the painter, or {@code null} while the dialog's owner draws its controls itself
     */
    public void setPainter(@Nullable Painter painter) {
        this.painter = painter;
    }

    /**
     * Draws a plain control of the dialog through the procedure painter; nothing while none is set.
     *
     * @param control the control
     * @param x       the control's left edge in interface pixels, the shift included
     * @param y       the control's top edge in interface pixels, the vertical shift included
     * @throws IOException if a sheet frame or image the control draws cannot be read
     */
    void drawControl(@NotNull ScrControl control, float x, float y) throws IOException {
        Painter current = this.painter;

        if (current != null) {
            current.drawControl(control, x, y);
        }
    }

    /**
     * @return the hovered control's id, or {@link #NONE}: the native form the procedures compare with their control ids
     */
    public int getHoveredId() {
        return getId(this.getHovered());
    }

    /**
     * @return the pressed control's id, or {@link #NONE}: the native form the procedures compare with their control ids
     */
    public int getPressedId() {
        return getId(this.getPressed());
    }

    /**
     * Returns the id of the focused control, the native form the procedures compare with their control ids.
     *
     * @return the id of the control with the keyboard focus, or {@link #NONE}
     */
    public int getFocusedId() {
        return getId(this.getFocused());
    }

    /**
     * Sets whether the default push button takes the focus while no control has it (a {@code DEFPUSHBUTTON} is focused when the
     * dialog has no focused control and the dialog's bit {@code 29} is clear), as the game's popups have it; the focused button
     * draws the focus outline.
     *
     * @param defaultButtonFocus whether the default push button takes the focus
     */
    public void setDefaultButtonFocus(boolean defaultButtonFocus) {
        this.defaultButtonFocus = defaultButtonFocus;
    }

    /**
     * @param control a control of this dialog
     * @return {@code true} if the control is drawn and takes input, {@code false} otherwise
     * @throws IllegalArgumentException if the dialog has neither the definition nor a control with its id
     */
    public boolean isVisible(@NotNull DialogControl control) {
        return this.requireControl(control).isVisible();
    }

    /**
     * @param control a control of this dialog
     * @return {@code true} if the control is visible and not disabled, {@code false} otherwise
     * @throws IllegalArgumentException if the dialog has neither the definition nor a control with its id
     */
    public boolean isEnabled(@NotNull DialogControl control) {
        return this.requireControl(control).isActive();
    }

    /**
     * @param control      a control of this dialog
     * @param controlTexts resolves a content key through the control strings, empty when unknown
     * @return the control's string: the replacement, or its content resolved
     * @throws IllegalArgumentException if the dialog has neither the definition nor a control with its id
     */
    public @NotNull String getControlString(@NotNull DialogControl control, @NotNull Function<String, String> controlTexts) {
        return this.requireControl(control).getControlString(controlTexts);
    }

    /**
     * @param control      a control of this dialog
     * @param controlTexts resolves a content key through the control strings
     * @return the drawn string: without the hotkey character of a {@code WS_HOTKEY} string
     * @throws IllegalArgumentException if the dialog has neither the definition nor a control with its id
     */
    public @NotNull String getDrawnString(@NotNull DialogControl control, @NotNull Function<String, String> controlTexts) {
        return this.requireControl(control).getDrawnString(controlTexts);
    }

    /**
     * Tests whether a push button has the focus: it is enabled and it is the focused control, or the dialog's default push button while no
     * control has the focus ({@link #setDefaultButtonFocus}).
     *
     * @param control a push button of this dialog
     * @return {@code true} if the button has the focus, {@code false} otherwise
     * @throws IllegalArgumentException if the dialog has neither the definition nor a control with its id
     */
    public boolean isButtonFocused(@NotNull DialogControl control) {
        ScrControl button = this.requireControl(control);
        Control focused = this.getFocused();

        if (!button.isActive()) {
            return false;
        }

        return focused == button || this.defaultButtonFocus && focused == null && button.getType() == ScrControlType.DEFPUSHBUTTON;
    }


    /**
     * Finds the control a press at a point goes to: the last enabled pressable top-level control whose rectangle, or
     * {@code HOTBOX} child, holds it.
     *
     * @param x the dialog-relative x
     * @param y the dialog-relative y
     * @return the control, or {@code null}
     */
    @Override
    public @Nullable ScrControl findControlAt(float x, float y) {
        return this.findControlAt(x, y, true);
    }

    /**
     * Finds the control the pointer hovers at a point: the last visible pressable or hit-testable top-level control there,
     * disabled controls included.
     *
     * @param x the dialog-relative x
     * @param y the dialog-relative y
     * @return the hovered control, or {@code null}
     */
    @Override
    public @Nullable ScrControl findHoverAt(float x, float y) {
        return this.findControlAt(x, y, false);
    }

    private @Nullable ScrControl findControlAt(float x, float y, boolean enabledOnly) {
        List<Control> controls = this.getControls();

        for (int index = controls.size() - 1; index >= 0; index--) {
            ScrControl control = (ScrControl) controls.get(index);

            if (!(enabledOnly ? control.isActive() : control.isVisible()) || !control.isPressable()) {
                continue;
            }

            if (control.contains(x - control.getLeft(), y - control.getTop())) {
                return control;
            }
        }

        return null;
    }

    /**
     * Checks whether a press moves the focus to the pressed control.
     *
     * @param control the pressed control
     * @return {@code false}: the native dialogs move the focus only through their procedures
     */
    @Override
    protected boolean isFocusedOnPress(@NotNull Control control) {
        return false;
    }

    /**
     * Finds the control a key activates: the first enabled control with {@code CTRL_ESCAPE} for Esc, {@code CTRL_ENTER} for Enter,
     * or whose {@code WS_HOTKEY} string starts with the key.
     *
     * @param key          the character, or {@code escape} / {@code enter}
     * @param escape       whether the key is Esc
     * @param enter        whether the key is Enter
     * @param controlTexts resolves a content key through the control strings
     * @return the control, or {@code null}
     */
    public @Nullable DialogControl findKeyControl(char key, boolean escape, boolean enter, @NotNull Function<String, String> controlTexts) {
        for (Control each : this.getControls()) {
            ScrControl control = (ScrControl) each;
            DialogControl definition = control.getDefinition();

            if (!control.isActive()) {
                continue;
            }

            if (escape && ScrDialogControlUtils.hasFlag(definition, ESCAPE_FLAG) || enter && ScrDialogControlUtils.hasFlag(definition, ENTER_FLAG)) {
                return definition;
            }

            if (!escape && !enter && control.isHotkey(key, controlTexts)) {
                return definition;
            }
        }

        return null;
    }

    /**
     * Finds the control activated by Enter.
     *
     * @return the first enabled {@code CTRL_ENTER} control (with flag {@code 0x10}), or {@code null}
     */
    public @Nullable DialogControl findEnterControl() {
        return this.findFlaggedControl(ENTER_FLAG);
    }

    private @Nullable DialogControl findFlaggedControl(@NotNull String flag) {
        for (Control control : this.getControls()) {
            if (((ScrControl) control).isActive() && ScrDialogControlUtils.hasFlag(control.getDefinition(), flag)) {
                return control.getDefinition();
            }
        }

        return null;
    }

    /**
     * Offers the escape request to the activator first, then activates the first enabled escape control when unhandled.
     *
     * @param activator the dialog's procedure
     * @return {@code true} if something handled the key, {@code false} otherwise
     */
    public boolean escape(@NotNull Activator activator) {
        if (activator.activate(ESCAPE_REQUEST)) {
            return true;
        }

        DialogControl control = this.findFlaggedControl(ESCAPE_FLAG);

        return control != null && activator.activate(control.getId());
    }

    /**
     * Activates an enabled focused push button first, then offers the enter request and finally the first enabled enter control.
     *
     * @param activator the dialog's procedure
     * @return {@code true} if something handled the key, {@code false} otherwise
     */
    public boolean enter(@NotNull Activator activator) {
        ScrControl focus = (ScrControl) this.getFocused();

        if (focus != null && focus.isActive() && (focus.getType() == ScrControlType.PUSHBUTTON || focus.getType() == ScrControlType.DEFPUSHBUTTON)) {
            return activator.activate(focus.getDefinition().getId());
        }

        if (activator.activate(ENTER_REQUEST)) {
            return true;
        }

        DialogControl control = this.findEnterControl();

        return control != null && activator.activate(control.getId());
    }
}
