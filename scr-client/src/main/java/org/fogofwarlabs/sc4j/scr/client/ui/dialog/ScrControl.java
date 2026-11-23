package org.fogofwarlabs.sc4j.scr.client.ui.dialog;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.dialog.*;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFonts;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.function.Function;

/**
 * Holds the live state of one SC:R dialog control on the engine {@link Control}: the engine keeps whether it is shown and
 * enabled and the pointer and focus flags its {@link ScrDialog} routes by; this class adds the native state the dialog
 * procedures write (the highlight, the replacement string, text format and font slot, the horizontal and vertical shifts, the 3-slice button
 * style, the checked state on an engine {@link CheckModel}) and the native hit test (the {@code HOTBOX} child, the shifts, the pressable
 * control types).
 * <p>
 * A control starts shown when its {@code WS_VISIBLE} flag is set. The SC:R widget families are subclasses that keep their state on the
 * engine widget models and add the native metrics, input routing and look ({@link ListBox}, {@link ComboBox}, {@link Slider},
 * {@link EditControl}, the glue's flic buttons); a screen puts one in the place of the control its dialog built ({@link ScrDialog#attach}),
 * which keeps the native state above ({@link #onReplacing}).
 * <p>
 * Drawing: {@link #drawAt} draws the control natively on an SC:R {@link DialogPainter}. A widget draws itself; a plain control is
 * drawn by its dialog's procedure painter ({@link ScrDialog#setPainter}: the glue screen's per-type draw), which needs the screen's
 * strings, palette and time. The engine draw ({@link #drawControl}) reaches the same native draw on an SC:R painter. The native
 * painters (the glue screens, the console and the game menus) call {@link #drawAt} in the native order themselves.
 */
public class ScrControl extends Control {
    private static final @NotNull String VISIBLE_FLAG = "WS_VISIBLE";
    private static final @NotNull String HOTKEY_FLAG = "WS_HOTKEY";
    /**
     * The first dialog sheet frame of each push button style: the style's disabled bar; normal is {@code 3} frames on, pressed {@code 6}.
     */
    private static final int @NotNull [] BUTTON_STYLE_FRAMES = {3, 83, 0, 103, 112, 121, 130, 140};
    private static final int NO_BUTTON_STYLE = -1;

    private final @NotNull ScrControlType type;
    private final @Nullable DialogControl hotBox;

    private final @NotNull CheckModel check;

    private boolean hitTestable;
    private boolean highlighted;
    private @Nullable String replacedText;
    private @Nullable Integer textFormat;
    private @Nullable Integer fontSlot;
    private int shift;
    private int verticalShift;
    private int buttonFrame;

    /**
     * Creates a {@link ScrControl} instance, shown when the definition has the {@code WS_VISIBLE} flag.
     *
     * @param definition the control's definition
     */
    // The constructor sets the start visibility and enablement through the final engine state setters only.
    @SuppressWarnings("this-escape")
    public ScrControl(@NotNull DialogControl definition) {
        super(definition);

        this.type   = ScrDialogControlUtils.getType(definition);
        this.hotBox = ScrDialogControlUtils.findChild(definition, ScrControlType.HOTBOX);

        this.check = new CheckModel(this.type == ScrControlType.RADIOBUTTON);

        this.hitTestable   = false;
        this.highlighted   = false;
        this.replacedText  = null;
        this.textFormat    = null;
        this.fontSlot      = null;
        this.shift         = 0;
        this.verticalShift = 0;
        this.buttonFrame   = NO_BUTTON_STYLE;

        this.setVisible(ScrDialogControlUtils.hasFlag(definition, VISIBLE_FLAG));
        this.setEnabled(true);
    }

    /**
     * @return the native control type
     */
    public final @NotNull ScrControlType getType() {
        return this.type;
    }

    /**
     * Tests whether the control is visible and not disabled: the native enabled test, which ignores the parents.
     *
     * @return {@code true} if the control is visible and not disabled, {@code false} otherwise
     */
    public boolean isActive() {
        return this.isVisible() && this.isEnabled();
    }

    /**
     * @return {@code true} if a text control is highlighted (drawn in colour code {@code 6} instead of {@code 2} in the glue), {@code false} otherwise
     */
    public boolean isHighlighted() {
        return this.highlighted;
    }

    /**
     * Sets whether a text control is highlighted: drawn in colour code {@code 6} instead of code {@code 2} in the glue.
     *
     * @param highlighted whether the text is highlighted
     */
    public void setHighlighted(boolean highlighted) {
        this.highlighted = highlighted;
    }

    /**
     * @return {@code true} if the check box or radio button is checked, {@code false} otherwise
     */
    public boolean isChecked() {
        return this.check.isChecked();
    }

    /**
     * Sets the checked state of a check box or radio button; a check box also toggles it when its press is released on it.
     *
     * @param checked whether the control is checked
     */
    public void setChecked(boolean checked) {
        this.check.setChecked(checked);
    }

    /**
     * @return the program's replacement string, or {@code null} while the control shows its content
     */
    public @Nullable String getReplacedText() {
        return this.replacedText;
    }

    /**
     * Sets the replacement string of the control.
     *
     * @param text the string, or {@code null} to show the control's content again
     */
    public void setReplacedText(@Nullable String text) {
        this.replacedText = text;
    }

    /**
     * @return the replacement horizontal text format ({@link #setTextFormat}), or {@code null}
     */
    public @Nullable Integer getTextFormat() {
        return this.textFormat;
    }

    /**
     * Sets the replacement horizontal text format of a text control (a custom draw procedure that sets the format byte before).
     *
     * @param format the format
     */
    public void setTextFormat(int format) {
        this.textFormat = format;
    }

    /**
     * @return the replacement font slot ({@link #setFontSlot}), or {@code null}
     */
    public @Nullable Integer getFontSlot() {
        return this.fontSlot;
    }

    /**
     * Sets the replacement font slot of the control; {@code null} restores the font selected from its loaded flags.
     *
     * @param slot the {@link InterfaceFonts} slot, or {@code null} for the loaded flags
     */
    public void setFontSlot(@Nullable Integer slot) {
        this.fontSlot = slot;
    }

    /**
     * @return the horizontal shift of the control relative to its loaded rectangle, {@code 0} if not moved
     */
    public int getShift() {
        return this.shift;
    }

    /**
     * Sets the horizontal shift of the control relative to its loaded rectangle.
     *
     * @param shift the horizontal shift in interface pixels
     */
    public void setShift(int shift) {
        this.shift = shift;
    }

    /**
     * @return the vertical shift of the control relative to its loaded rectangle, {@code 0} if not moved
     */
    public int getVerticalShift() {
        return this.verticalShift;
    }

    /**
     * Sets the vertical shift of the control relative to its loaded rectangle.
     *
     * @param verticalShift the vertical shift in interface pixels
     */
    public void setVerticalShift(int verticalShift) {
        this.verticalShift = verticalShift;
    }

    /**
     * Sets a 3-slice style of the style table on a push button (the control gets the 3-slice bit {@code 29} and the style's first
     * frame), as the popups' initializations do for their buttons.
     *
     * @param style the style index, for example {@link ScrDialog#POPUP_BUTTON_STYLE}
     */
    public void setButtonStyle(int style) {
        this.buttonFrame = BUTTON_STYLE_FRAMES[style];
    }

    /**
     * @return {@code true} if the push button was given a 3-slice style ({@link #setButtonStyle}), {@code false} otherwise
     */
    public boolean hasButtonStyle() {
        return this.buttonFrame != NO_BUTTON_STYLE;
    }

    /**
     * @param defaultFrame the first frame of a button without a style of its own
     * @return the first dialog sheet frame of the push button's style
     */
    public int getButtonFrame(int defaultFrame) {
        return this.hasButtonStyle() ? this.buttonFrame : defaultFrame;
    }

    /**
     * Makes the control take part in pointer hit tests regardless of its control type; it must still be visible, or visible and
     * enabled, for the hit test that finds it.
     */
    public void setHitTestable() {
        this.hitTestable = true;
    }

    /**
     * @param controlTexts resolves a content key through the control strings, empty when unknown
     * @return the control's string: the replacement, or its content resolved
     */
    public @NotNull String getControlString(@NotNull Function<String, String> controlTexts) {
        if (this.replacedText != null) {
            return this.replacedText;
        }

        String content = ScrDialogControlUtils.getContent(this.getDefinition());

        if (content.isEmpty()) {
            return "";
        }

        String resolved = controlTexts.apply(content);

        return resolved.isEmpty() ? content : resolved;
    }

    /**
     * Returns the drawn string: the control string without the hotkey character of a {@code WS_HOTKEY} string, a set text too (the first
     * character of every {@code WS_HOTKEY} control string is skipped), and of a page control's hotkey string ({@link #hasHotkey}).
     *
     * @param controlTexts resolves a content key through the control strings
     * @return the drawn string
     */
    public @NotNull String getDrawnString(@NotNull Function<String, String> controlTexts) {
        String text = this.getControlString(controlTexts);

        if (this.hasHotkey(text)) {
            return InterfaceText.stripHotkey(text);
        }

        return text;
    }

    /**
     * @param key          the character
     * @param controlTexts resolves a content key through the control strings
     * @return {@code true} if the control's string has a hotkey ({@link #hasHotkey}) and starts with the key, {@code false} otherwise
     */
    public boolean isHotkey(char key, @NotNull Function<String, String> controlTexts) {
        String text = this.getControlString(controlTexts);

        return this.hasHotkey(text) && InterfaceText.isHotkey(text, key);
    }

    /**
     * Tests whether the control's string starts with its hotkey: a dialog control by its {@code WS_HOTKEY} flag, a page control
     * ({@link WebPanelStyleUtils#FLAG}) by the string itself, as the pages read every installed string ({@link InterfaceText#isHotkeyString}).
     *
     * @param text the control's string
     * @return {@code true} if the first character is the hotkey, {@code false} otherwise
     */
    private boolean hasHotkey(@NotNull String text) {
        DialogControl definition = this.getDefinition();

        if (text.isEmpty()) {
            return false;
        }

        return ScrDialogControlUtils.hasFlag(definition, HOTKEY_FLAG) ||
               ScrDialogControlUtils.hasFlag(definition, WebPanelStyleUtils.FLAG) && InterfaceText.isHotkeyString(text);
    }

    /**
     * @return {@code true} if the control takes presses (buttons, lists, combo boxes, scroll bars and edit controls, or a control set hit-testable), {@code false} otherwise
     */
    @Override
    public boolean isPressable() {
        return this.hitTestable || ScrDialog.isPressable(this.getDefinition());
    }

    /**
     * @return {@code true}: the dialog procedures may give the native focus to any control
     */
    @Override
    public boolean isFocusable() {
        return true;
    }


    /**
     * Checks whether a point is on the control: inside its {@code HOTBOX} child when it has one, else inside its rectangle, both
     * moved by the shifts.
     *
     * @param x the x relative to the control's loaded left edge
     * @param y the y relative to the control's top edge
     * @return {@code true} if the point is inside the control's hit area, {@code false} otherwise
     */
    @Override
    public boolean contains(float x, float y) {
        DialogControl definition = this.getDefinition();
        DialogControl box = this.hotBox;
        float left = this.shift;
        float top = this.verticalShift;
        float width = definition.getWidth();
        float height = definition.getHeight();

        if (box != null) {
            left += box.getX();
            top += box.getY();
            width  = box.getWidth();
            height = box.getHeight();
        }

        return x >= left && y >= top && x < left + width && y < top + height;
    }

    /**
     * Called when the press of the control is released on it: a check box toggles and a radio button becomes checked.
     *
     * @param x the x relative to the control
     * @param y the y relative to the control
     * @return {@code true}: the release activates the control
     */
    @Override
    protected boolean onReleased(float x, float y) {
        if (this.type == ScrControlType.CHECKBOX || this.type == ScrControlType.RADIOBUTTON) {
            this.check.activate();
        }

        return true;
    }

    /**
     * Called when this control has taken the place of another control of its dialog ({@link Dialog#replaceControl}), after the
     * shared live state moved over; a subclass takes over its own state from the replaced control here.
     * <p>
     * An SC:R control takes over the native state of a replaced SC:R control: the highlight, the checked state, the replacement
     * string, text format and font slot, the shifts, the button style and the hit-test flag.
     *
     * @param replaced the replaced control, no longer in the dialog
     */
    @Override
    protected void onReplacing(@NotNull Control replaced) {
        if (!(replaced instanceof ScrControl other)) {
            return;
        }

        this.check.setChecked(other.check.isChecked());

        this.hitTestable   = other.hitTestable;
        this.highlighted   = other.highlighted;
        this.replacedText  = other.replacedText;
        this.textFormat    = other.textFormat;
        this.fontSlot      = other.fontSlot;
        this.shift         = other.shift;
        this.verticalShift = other.verticalShift;
        this.buttonFrame   = other.buttonFrame;
    }

    /**
     * Draws the control itself, without its children: the native draw ({@link #drawAt}) at the control's edge moved by its shifts, on
     * the SC:R dialog painter the canvas is.
     *
     * @param canvas the canvas that draws: an SC:R {@link DialogPainter}
     * @param theme  the look of the controls
     * @param x      the control's left edge on the canvas
     * @param y      the control's top edge on the canvas
     * @throws ControlDrawException     if a sheet frame or image the control draws cannot be read
     * @throws IllegalArgumentException if the canvas is not an SC:R dialog painter
     */
    @Override
    protected final void drawControl(@NotNull Canvas canvas, @NotNull DialogTheme theme, float x, float y) {
        if (!(canvas instanceof DialogPainter painter)) {
            throw Exceptions.newIllegalArgumentException("SC:R control {0} draws on an SC:R dialog painter, not on {1}.",
                                                         this.getDefinition().getId(), canvas);
        }

        try {
            this.drawAt(painter, x + this.shift, y + this.verticalShift);
        } catch (IOException exception) {
            throw ControlDrawException.create(exception, "SC:R control {0}: {1}", this.getDefinition().getId(), exception.getMessage());
        }
    }

    /**
     * Draws the control natively at a position: a plain control through its dialog's procedure painter ({@link ScrDialog#setPainter}),
     * nothing while it is not in an SC:R dialog with one; a widget subclass draws its own look.
     *
     * @param painter the SC:R dialog painter
     * @param x       the control's left edge in interface pixels, the shift included
     * @param y       the control's top edge in interface pixels, the vertical shift included
     * @throws IOException if a sheet frame or image the control draws cannot be read
     */
    public void drawAt(@NotNull DialogPainter painter, float x, float y) throws IOException {
        if (this.getDialog() instanceof ScrDialog dialog) {
            dialog.drawControl(this, x, y);
        }
    }
}
