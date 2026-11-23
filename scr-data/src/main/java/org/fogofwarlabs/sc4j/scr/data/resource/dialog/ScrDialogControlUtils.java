package org.fogofwarlabs.sc4j.scr.data.resource.dialog;

import org.fogofwarlabs.sc4j.engine.resource.dialog.ControlKind;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.engine.resource.dialog.TextAlignment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Creates and reads the engine {@link DialogControl} of an SC:R interface control. The native parts of a control are its
 * attributes:
 * <ul>
 *     <li>{@value #TYPE_ATTRIBUTE}: the native type ({@link ScrControlType} name);</li>
 *     <li>{@value #FLAG_ATTRIBUTE_PREFIX}{@code <name>}: one attribute per flag name, such as {@code flag.WS_VISIBLE};</li>
 *     <li>{@value #FLAG_BITS_ATTRIBUTE}: the decimal OR of the flag bits of {@link #toBits}.</li>
 * </ul>
 * The native content (a string key, an image or video path) is the control's text. The engine kind and text alignment
 * follow from the native type ({@link #getKind}).
 */
public final class ScrDialogControlUtils {
    /**
     * The attribute holding the native type name.
     */
    public static final @NotNull String TYPE_ATTRIBUTE = "type";
    /**
     * The prefix of the attribute of each flag name.
     */
    public static final @NotNull String FLAG_ATTRIBUTE_PREFIX = "flag.";
    /**
     * The attribute holding the decimal flag bits.
     */
    public static final @NotNull String FLAG_BITS_ATTRIBUTE = "flagBits";

    /**
     * The value of a flag attribute.
     */
    private static final @NotNull String FLAG_VALUE = "true";

    /**
     * Maps control flag names to their bit values.
     */
    private static final @NotNull Map<String, Integer> FLAG_BITS = Map.ofEntries(Map.entry("WS_VISIBLE", 0x2),
                                                                                 Map.entry("WS_TABSTOP", 0x4),
                                                                                 Map.entry("CTRL_ESCAPE", 0x8),
                                                                                 Map.entry("CTRL_ENTER", 0x10),
                                                                                 Map.entry("WS_HOTVKEY", 0x20),
                                                                                 Map.entry("WS_HOTKEY", 0x40),
                                                                                 Map.entry("RC_SMALLTEXT", 0x80),
                                                                                 Map.entry("RC_BIGTEXT", 0x100),
                                                                                 Map.entry("RC_BIGGERTEXT", 0x200),
                                                                                 Map.entry("WS_EX_TRANSPARENT", 0x800),
                                                                                 Map.entry("CTRL_TRANSLUCENT", 0x1000),
                                                                                 Map.entry("RC_NO_TEXTKERN", 0x2000),
                                                                                 Map.entry("RC_ANCHOR_TOPRIGHT", 0x4000),
                                                                                 Map.entry("RC_ANCHOR_CENTER", 0x8000),
                                                                                 Map.entry("RC_POPUP_BACKGROUND", 0x10000),
                                                                                 Map.entry("RC_FLC_HD", 0x40),
                                                                                 Map.entry("FB_HTEXTLEFT", 0x1000000),
                                                                                 Map.entry("FB_HTEXTCENTER", 0x2000000),
                                                                                 Map.entry("FB_HTEXTRIGHT", 0x4000000),
                                                                                 Map.entry("FB_VTEXTTOP", 0x8000000),
                                                                                 Map.entry("FB_VTEXTCENTER", 0x10000000),
                                                                                 Map.entry("FB_VTEXTBOTTOM", 0x20000000),
                                                                                 Map.entry("FLC_FADEIN", 0x1),
                                                                                 Map.entry("FLC_FADED", 0x2),
                                                                                 Map.entry("FLC_LOOP", 0x4),
                                                                                 Map.entry("FLC_MOUSEOVER", 0x8));
    private static final @NotNull String CENTER_TEXT_FLAG = "FB_HTEXTCENTER";
    private static final @NotNull String RIGHT_TEXT_FLAG = "FB_HTEXTRIGHT";

    /**
     * Combines the named control flags.
     *
     * @param names flag names
     * @return the OR of their bits; unknown names and {@code WS_CHILD}, {@code WS_GROUP}, {@code WS_BORDER} and {@code SBS_HORZ} add nothing
     */
    public static int toBits(@NotNull Set<@NotNull String> names) {
        int bits = 0;

        for (String name : names) {
            bits |= FLAG_BITS.getOrDefault(name, 0);
        }

        return bits;
    }

    /**
     * Returns the engine kind of a native control type.
     *
     * @param type the native type
     * @return the kind: rectangles and dialog roots are panels, button text, hit-test, web page and unknown elements are custom controls
     */
    public static @NotNull ControlKind getKind(@NotNull ScrControlType type) {
        return switch (type) {
            case DIALOG, RECT -> ControlKind.PANEL;
            case LTEXT, CTEXT, RTEXT -> ControlKind.LABEL;
            case FLCBTN, PUSHBUTTON, DEFPUSHBUTTON -> ControlKind.BUTTON;
            case RADIOBUTTON -> ControlKind.OPTION_BUTTON;
            case CHECKBOX -> ControlKind.CHECK_BOX;
            case LISTBOX -> ControlKind.LIST_BOX;
            case COMBOBOX -> ControlKind.COMBO_BOX;
            case EDITTEXT -> ControlKind.TEXT_INPUT;
            case SCROLLBAR -> ControlKind.SCROLL_BAR;
            case FLC -> ControlKind.ANIMATION;
            case TEXTBOX, HOTBOX, WEBBROWSER, OTHER -> ControlKind.CUSTOM;
        };
    }

    /**
     * Creates the engine control of an SC:R interface control.
     *
     * @param type     the native type
     * @param id       the control id
     * @param x        the left edge
     * @param y        the top edge
     * @param width    the width, at least {@code 0}
     * @param height   the height, at least {@code 0}
     * @param flags    the flag names
     * @param content  the content: a string key, an image or video path, or an empty string
     * @param children the child elements in drawing order
     * @return the control
     * @throws IllegalArgumentException if a size is negative
     */
    public static @NotNull DialogControl createControl(@NotNull ScrControlType type, int id,
                                                       int x, int y, int width, int height,
                                                       @NotNull Set<@NotNull String> flags, @NotNull String content,
                                                       @NotNull List<@NotNull DialogControl> children) {
        Map<String, String> attributes = new LinkedHashMap<>();
        TextAlignment alignment = switch (type) {
            case CTEXT -> TextAlignment.CENTER;
            case RTEXT -> TextAlignment.RIGHT;
            case LTEXT -> TextAlignment.LEFT;
            default -> flags.contains(CENTER_TEXT_FLAG) ? TextAlignment.CENTER
                                                        : flags.contains(RIGHT_TEXT_FLAG) ? TextAlignment.RIGHT : TextAlignment.LEFT;
        };

        attributes.put(TYPE_ATTRIBUTE, type.name());

        for (String flag : flags) {
            attributes.put(FLAG_ATTRIBUTE_PREFIX + flag, FLAG_VALUE);
        }

        attributes.put(FLAG_BITS_ATTRIBUTE, Integer.toString(toBits(flags)));

        return new DialogControl(id, getKind(type), x, y, width, height, content.isEmpty() ? null : content, alignment, null, attributes, children);
    }

    /**
     * Returns the native type of a control.
     *
     * @param control the control
     * @return the native type, {@link ScrControlType#OTHER} for a control without a known type
     */
    public static @NotNull ScrControlType getType(@NotNull DialogControl control) {
        String name = control.getAttributes().get(TYPE_ATTRIBUTE);

        return name == null ? ScrControlType.OTHER : ScrControlType.of(name);
    }

    /**
     * Tells whether a control has a flag.
     *
     * @param control the control
     * @param flag    a flag name, for example {@code WS_VISIBLE}
     * @return {@code true} if the control has the flag, {@code false} otherwise
     */
    public static boolean hasFlag(@NotNull DialogControl control, @NotNull String flag) {
        return control.getAttributes().containsKey(FLAG_ATTRIBUTE_PREFIX + flag);
    }

    /**
     * Returns the flag names of a control.
     *
     * @param control the control
     * @return the flag names in the order of the file, unmodifiable
     */
    public static @NotNull Set<@NotNull String> getFlags(@NotNull DialogControl control) {
        Set<String> flags = new LinkedHashSet<>();

        for (String attribute : control.getAttributes().keySet()) {
            if (attribute.startsWith(FLAG_ATTRIBUTE_PREFIX)) {
                flags.add(attribute.substring(FLAG_ATTRIBUTE_PREFIX.length()));
            }
        }

        return Collections.unmodifiableSet(flags);
    }

    /**
     * Returns the flag bits of a control.
     *
     * @param control the control
     * @return the flag bits of the name map (the control word), {@code 0} for a control without flags
     */
    public static int getFlagBits(@NotNull DialogControl control) {
        String bits = control.getAttributes().get(FLAG_BITS_ATTRIBUTE);

        return bits == null ? 0 : Integer.parseInt(bits);
    }

    /**
     * Returns the native content of a control.
     *
     * @param control the control
     * @return the content: a string key, an image or video path, or an empty string
     */
    public static @NotNull String getContent(@NotNull DialogControl control) {
        String text = control.getText();

        return text == null ? "" : text;
    }

    /**
     * Finds a child element by native type.
     *
     * @param control the control
     * @param type    the child type
     * @return the first child of that type, or {@code null}
     */
    public static @Nullable DialogControl findChild(@NotNull DialogControl control, @NotNull ScrControlType type) {
        for (DialogControl child : control.getChildren()) {
            if (getType(child) == type) {
                return child;
            }
        }

        return null;
    }

    /**
     * Finds a top-level control of a dialog by id; child elements are not searched.
     *
     * @param definition the dialog
     * @param id         the control id
     * @return the first top-level control with that id, or {@code null}
     */
    public static @Nullable DialogControl findControl(@NotNull DialogDefinition definition, int id) {
        for (DialogControl control : definition.getControls()) {
            if (control.getId() == id) {
                return control;
            }
        }

        return null;
    }

    private ScrDialogControlUtils() {
    }
}
