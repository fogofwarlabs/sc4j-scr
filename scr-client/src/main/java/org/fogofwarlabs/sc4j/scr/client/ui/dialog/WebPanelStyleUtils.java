package org.fogofwarlabs.sc4j.scr.client.ui.dialog;

import org.fogofwarlabs.sc4j.scr.client.ui.text.StatText;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFonts;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.fogofwarlabs.sc4j.scr.render.ui.TrueTypeText;
import org.jetbrains.annotations.NotNull;

/**
 * Draws web-style dialog controls in interface coordinates using shared fonts, colors and geometry. Text draws its shadow before the colored line.
 * Rounded shapes use quarter-pixel scan rows so their edges scale with the viewport.
 */
public final class WebPanelStyleUtils {
    /**
     * The client flag of a dialog control that stands for a web page element (not a native dialog flag).
     */
    public static final @NotNull String FLAG = "SC4J_WEB_PANEL";
    /**
     * The pages' text color {@code #a2b1f5} (headings and labels): fixed in every graphics mode, where the glue palette's code {@code 2} is lighter
     * in the remastered palette.
     */
    public static final int TEXT = 0xA2B1F5FF;
    /**
     * The pages' border color {@code #6c0000}.
     */
    public static final int BORDER = 0x6C0000FF;
    /**
     * The border of a hovered or expanded drop-down ({@code #fc1000}).
     */
    public static final int ACTIVE_BORDER = 0xFC1000FF;
    /**
     * A disabled control's border ({@code .disabled .selectedOption{border-color:#404040}}).
     */
    public static final int DISABLED_BORDER = 0x404040FF;
    /**
     * The drop-downs' text ({@code .dropdownWidget{color:#4cc428}}), the button green.
     */
    public static final int BUTTON_TEXT = 0x4CC428FF;
    /**
     * Hovered text ({@code #b4fc74}).
     */
    public static final int HIGHLIGHT_TEXT = 0xB4FC74FF;
    /**
     * Disabled and grayed text ({@code .disabled{color:gray}}).
     */
    public static final int DISABLED_TEXT = 0x808080FF;
    /**
     * The drop-downs' background ({@code background-color:#000}).
     */
    public static final int BACKGROUND = 0x000000FF;
    /**
     * The lobby page's text ({@code .gameLobbyPanel{font-size:8px;line-height:9px}}): its drop-downs and headers.
     */
    public static final int DROPDOWN_TEXT = InterfaceFonts.getWebSlot(TrueTypeText.WEB_REGULAR_FAMILY, 8, 9);
    /**
     * The shared page button's text ({@code .Button{font-size:10px;line-height:10px}}, {@code eurostileext-reg}).
     */
    public static final int BUTTON_FONT = InterfaceFonts.getWebSlot(TrueTypeText.WEB_EXTENDED_FAMILY, 10, 10);
    /**
     * The shared page button's height: its {@code 10} px line, {@code padding: 5px} and the {@code 1} px border.
     */
    public static final float BUTTON_HEIGHT = 22;
    /**
     * The inset of a {@code .Button-group} around its buttons: {@code padding: 1.5px} and its {@code 1.5} px border.
     */
    public static final float BUTTON_GROUP_INSET = 3;
    /**
     * The space between the buttons of a {@code .Button-group} ({@code margin-right: 4px}).
     */
    public static final float BUTTON_GROUP_SPACING = 4;
    /**
     * The frames of the pages' panels and groups ({@code border: 1px solid #331f1f}).
     */
    public static final int PANEL_BORDER = 0x331F1FFF;
    /**
     * The CSS {@code line-height: normal} of {@link #getFontSlot}.
     */
    public static final float NORMAL_LINE_HEIGHT = 0;

    private static final int PANEL_FRAME_INSET = 1;
    private static final int CONTENT_INSET = 2;
    private static final int RGB_MASK = 0xFFFFFF00;
    private static final int ALPHA_MASK = 0xFF;
    private static final float BUTTON_MINIMUM_WIDTH = 120;
    private static final float BUTTON_PADDING = 5;
    private static final float BUTTON_RADIUS = 2;
    private static final float BUTTON_GROUP_BORDER = 1.5f;
    private static final float BUTTON_GROUP_RADIUS = 4;
    /**
     * The width of a slider's border ({@code 1} px).
     */
    private static final float FIELD_BORDER = 1;
    /**
     * {@code .dialogBox}: {@code border-radius: 6px}, its inner {@code #6c0000} frame {@code 1} px inside with {@code 4} px corners.
     */
    private static final float DIALOG_RADIUS = 6;
    private static final float DIALOG_INNER_RADIUS = 4;
    private static final int DOT = 0xFCFC4CFF;
    private static final int DISABLED_DOT = 0x808080FF;
    private static final float CORNER_RADIUS = 3;
    private static final float RADIO_SIZE = 10;
    private static final float RADIO_LABEL_GAP = 5;
    /**
     * The dot's solid radius and the radius its fade ends at, as fractions of the circle's size.
     */
    private static final float DOT_SOLID = 0.3f;
    private static final float DOT_EDGE = 0.5f;
    private static final int DOT_BANDS = 3;
    /**
     * A page slider ({@code .sliderHolder}): the track's fill ({@code #141834}), a stop's size ({@code .rangeLabels li}: {@code 6} px) and
     * the thumb's size ({@code 12} px); stops and thumb have a {@code 1} px ring of the field's border.
     */
    private static final int SLIDER_TRACK = 0x141834FF;
    private static final float SLIDER_STOP_SIZE = 6;
    private static final float SLIDER_THUMB_SIZE = 12;
    private static final float SLIDER_TRACK_HEIGHT = 3;
    /**
     * The row height of the shapes' scan lines in interface pixels.
     */
    private static final float ROW = 0.25f;

    /**
     * The pages' text shadow color ({@code text-shadow: 1px 1px #000}).
     */
    private static final int SHADOW = 0x000000FF;
    /**
     * The text shadow's offset right and down.
     */
    private static final float SHADOW_OFFSET = 1;
    /**
     * The mark that ends a cut text.
     */
    private static final @NotNull String CUT_MARK = "...";

    /**
     * Returns the font slot of a page's text style. The family is {@link TrueTypeText#WEB_REGULAR_FAMILY} ({@code eurostile-reg}, the pages'
     * body), {@link TrueTypeText#WEB_EXTENDED_FAMILY} ({@code eurostileext-reg}, headings) or {@link TrueTypeText#WEB_ARIAL_FAMILY} (the chat
     * page's {@code Arial}).
     *
     * @param family     the font family
     * @param size       the CSS {@code font-size}
     * @param lineHeight the CSS {@code line-height}, or {@code 0} for {@code normal}
     * @return the slot
     */
    public static int getFontSlot(int family, float size, float lineHeight) {
        return InterfaceFonts.getWebSlot(family, size, lineHeight);
    }

    /**
     * Returns the width of the shared page button ({@code .Button} of the pages' button component): its text and {@code padding: 5px} inside the
     * {@code 1} px border, at least {@code min-width: 120px} (a {@code button} element sizes its border box).
     *
     * @param textWidth the width of the label in {@link #BUTTON_FONT}
     * @return the width
     */
    public static float getButtonWidth(float textWidth) {
        return Math.max(BUTTON_MINIMUM_WIDTH, textWidth + 2 * (BUTTON_PADDING + 1));
    }


    /**
     * Draws a box with a {@code 1} px border, the corners of its rounded edges rounded by {@code 3} px, filled inside the border (a drop-down's
     * {@code .selectedOption}, its open list {@code .selectableOptionHolder}).
     *
     * @param painter     the painter
     * @param positionX   the left
     * @param positionY   the top
     * @param width       the width
     * @param height      the height
     * @param border      the packed border color
     * @param fill        the packed fill color, or {@code 0} for none
     * @param roundTop    whether the top corners are rounded
     * @param roundBottom whether the bottom corners are rounded
     * @param topEdge     whether the top border is drawn
     * @param bottomEdge  whether the bottom border is drawn
     */
    public static void drawBox(@NotNull DialogPainter painter,
                               float positionX, float positionY, float width, float height,
                               int border, int fill,
                               boolean roundTop, boolean roundBottom,
                               boolean topEdge, boolean bottomEdge) {
        drawBox(painter, positionX, positionY, width, height, CORNER_RADIUS, border, fill, roundTop, roundBottom, topEdge, bottomEdge);
    }

    /**
     * Draws a box with a {@code 1} px border and corners of a radius
     * ({@link #drawBox(DialogPainter, float, float, float, float, int, int, boolean, boolean, boolean, boolean)} with the radius of its own, such as
     * the scroll thumb's {@code 1} px).
     *
     * @param painter      the painter
     * @param positionX    the left
     * @param positionY    the top
     * @param width        the width
     * @param height       the height
     * @param cornerRadius the radius of the rounded corners
     * @param border       the packed border color
     * @param fill         the packed fill color, or {@code 0} for none
     * @param roundTop     whether the top corners are rounded
     * @param roundBottom  whether the bottom corners are rounded
     * @param topEdge      whether the top border is drawn
     * @param bottomEdge   whether the bottom border is drawn
     */
    public static void drawBox(@NotNull DialogPainter painter,
                               float positionX, float positionY, float width, float height,
                               float cornerRadius,
                               int border, int fill,
                               boolean roundTop, boolean roundBottom,
                               boolean topEdge, boolean bottomEdge) {
        float radius = Math.min(cornerRadius, Math.min(width, height) / 2);
        float topBand = roundTop ? radius : topEdge ? 1 : 0;
        float bottomBand = roundBottom ? radius : bottomEdge ? 1 : 0;
        // The straight middle: the side borders and the fill between them.
        float middle = height - topBand - bottomBand;

        if (middle > 0) {
            painter.fillRectangle(positionX, positionY + topBand, 1, middle, border);
            painter.fillRectangle(positionX + width - 1, positionY + topBand, 1, middle, border);

            if (fill != 0) {
                painter.fillRectangle(positionX + PANEL_FRAME_INSET, positionY + topBand, width - 2 * PANEL_FRAME_INSET, middle, fill);
            }
        }

        // The edge bands, one scan row at a time: the outer and the inner arc of a rounded corner, a straight edge's full row.
        for (float row = 0; row < topBand; row += ROW) {
            drawBoxRow(painter, positionX, positionY + row, width, roundTop ? radius - row - ROW / 2 : -1, radius, topEdge && row < 1, border, fill);
        }

        for (float row = 0; row < bottomBand; row += ROW) {
            drawBoxRow(painter,
                       positionX, positionY + height - row - ROW,
                       width,
                       roundBottom ? radius - row - ROW / 2 : -1,
                       radius,
                       bottomEdge && row < 1,
                       border, fill);
        }
    }

    /**
     * Draws one scan row of a box's edge band.
     *
     * @param painter   the painter
     * @param positionX the box left
     * @param positionY the row top
     * @param width     the box width
     * @param distance  the row's distance from the corner arcs' center row, or a negative value for a straight edge
     * @param radius    the corner radius
     * @param edge      whether the row lies in the top or bottom border
     * @param border    the packed border color
     * @param fill      the packed fill color, or {@code 0} for none
     */
    private static void drawBoxRow(@NotNull DialogPainter painter,
                                   float positionX, float positionY,
                                   float width,
                                   float distance,
                                   float radius,
                                   boolean edge,
                                   int border, int fill) {
        float outer = distance < 0 ? 0 : radius - (float) Math.sqrt(Math.max(0, radius * radius - distance * distance));

        if (edge) {
            painter.fillRectangle(positionX + outer, positionY, width - 2 * outer, ROW, border);

            return;
        }

        float inner = distance < 0 ? 1
                                   : distance >= radius - 1 ? radius
                                                            : Math.max(outer + 1, radius - (float) Math.sqrt((radius - 1) * (radius - 1) - distance * distance));

        painter.fillRectangle(positionX + outer, positionY, inner - outer, ROW, border);
        painter.fillRectangle(positionX + width - inner, positionY, inner - outer, ROW, border);

        if (fill != 0 && width > 2 * inner) {
            painter.fillRectangle(positionX + inner, positionY, width - 2 * inner, ROW, fill);
        }
    }


    /**
     * Draws one line box of page text with the body's shadow: the shadow in black one pixel right and down, then the text. The line's top is the top
     * of its CSS line box (the text is laid out top aligned in the slot's line box); color codes of the text color the text, not the shadow.
     *
     * @param painter the painter
     * @param slot    a web text slot ({@link #getFontSlot})
     * @param text    the text
     * @param left    the rectangle left
     * @param top     the line box top
     * @param width   the rectangle width
     * @param height  the rectangle height (the lines that fit are drawn)
     * @param format  the {@link InterfaceText} format: the horizontal alignment, with {@link InterfaceText#TOP} for a line box
     * @param color   the packed {@code 0xRRGGBBAA} text color
     */
    public static void drawText(@NotNull DialogPainter painter,
                                int slot,
                                @NotNull String text,
                                float left, float top,
                                float width, float height,
                                int format,
                                int color) {
        painter.drawColorText(slot, StatText.stripColors(text), left + SHADOW_OFFSET, top + SHADOW_OFFSET, width, height, format, SHADOW);
        painter.drawColorText(slot, text, left, top, width, height, format, color);
    }

    /**
     * Cuts a text to the longest prefix that fits a width, followed by a cut mark.
     *
     * @param painter the painter
     * @param slot    the font slot
     * @param text    the text
     * @param width   the width
     * @return the text if it fits, its cut prefix with the mark otherwise
     */
    public static @NotNull String fitText(@NotNull DialogPainter painter, int slot, @NotNull String text, float width) {
        if (painter.measure(slot, text) <= width) {
            return text;
        }

        int end = text.length();

        while (end > 0 && painter.measure(slot, text.substring(0, end) + CUT_MARK) > width) {
            end--;
        }

        return text.substring(0, end) + CUT_MARK;
    }

    /**
     * Draws a single line centered vertically in its rectangle. Web font slots center their measured line box; other slots use the shared text
     * layout middle alignment.
     *
     * @param painter the painter
     * @param slot    the font slot
     * @param text    the line
     * @param left    the rectangle left
     * @param top     the rectangle top
     * @param width   the rectangle width
     * @param height  the rectangle height
     * @param format  the horizontal {@link InterfaceText} alignment
     * @param color   the packed {@code 0xRRGGBBAA} text color
     */
    public static void drawCenteredLine(@NotNull DialogPainter painter,
                                        int slot,
                                        @NotNull String text,
                                        float left, float top,
                                        float width, float height,
                                        int format,
                                        int color) {
        if (!InterfaceFonts.isWebSlot(slot)) {
            drawText(painter, slot, text, left, top, width, height, format | InterfaceText.MIDDLE, color);

            return;
        }

        float line = painter.getLineHeight(slot, text);

        drawText(painter, slot, text, left, top + (float) Math.floor((height - line) / 2), width, line, format | InterfaceText.TOP, color);
    }


    /**
     * Draws a {@code 1} px frame with rounded corners.
     *
     * @param painter   the painter
     * @param positionX the left
     * @param positionY the top
     * @param width     the width
     * @param height    the height
     * @param color     the packed {@code 0xRRGGBBAA} border color, usually {@link #BORDER}
     */
    public static void drawRoundedFrame(@NotNull DialogPainter painter, float positionX, float positionY, float width, float height, int color) {
        float radius = Math.min(CORNER_RADIUS, Math.min(width, height) / 2);

        painter.fillRectangle(positionX + radius, positionY, width - 2 * radius, 1, color);
        painter.fillRectangle(positionX + radius, positionY + height - 1, width - 2 * radius, 1, color);
        painter.fillRectangle(positionX, positionY + radius, 1, height - 2 * radius, color);
        painter.fillRectangle(positionX + width - 1, positionY + radius, 1, height - 2 * radius, color);

        // Each corner is a quarter ring of the radius: per scan row the span between the outer and the inner arc.
        for (float row = 0; row < radius; row += ROW) {
            float distanceY = radius - row - ROW / 2;
            float outer = radius - (float) Math.sqrt(Math.max(0, radius * radius - distanceY * distanceY));
            float inner = distanceY >= radius - 1 ? radius
                                                  : radius - (float) Math.sqrt(Math.max(0, (radius - 1) * (radius - 1) - distanceY * distanceY));
            float span = Math.max(ROW, inner - outer);

            painter.fillRectangle(positionX + outer, positionY + row, span, ROW, color);
            painter.fillRectangle(positionX + width - outer - span, positionY + row, span, ROW, color);
            painter.fillRectangle(positionX + outer, positionY + height - row - ROW, span, ROW, color);
            painter.fillRectangle(positionX + width - outer - span, positionY + height - row - ROW, span, ROW, color);
        }
    }

    /**
     * Draws a radio button and its label.
     *
     * @param painter   the painter
     * @param slot      the label's font slot
     * @param text      the label
     * @param positionX the left of the circle
     * @param positionY the top of the control
     * @param width     the control width
     * @param height    the control height (the circle and the label's line box are centered in it)
     * @param checked   whether it is checked
     * @param enabled   whether it takes input
     */
    public static void drawRadioButton(@NotNull DialogPainter painter,
                                       int slot,
                                       @NotNull String text,
                                       float positionX, float positionY,
                                       float width, float height,
                                       boolean checked,
                                       boolean enabled) {
        float top = positionY + (height - ControlPainterUtils.ROW_TEXT_BOTTOM_INSET - RADIO_SIZE) / 2;
        float radius = RADIO_SIZE / 2;
        float centerX = positionX + radius;
        float centerY = top + radius;
        int border = enabled ? BORDER : DISABLED_BORDER;
        int dot = enabled ? DOT : DISABLED_DOT;

        for (float row = 0; row < RADIO_SIZE; row += ROW) {
            float distanceY = Math.abs(centerY - (top + row + ROW / 2));
            float outer = (float) Math.sqrt(Math.max(0, radius * radius - distanceY * distanceY));
            float inner = (float) Math.sqrt(Math.max(0, (radius - 1) * (radius - 1) - distanceY * distanceY));

            painter.fillRectangle(centerX - outer, top + row, outer - inner, ROW, border);
            painter.fillRectangle(centerX + inner, top + row, outer - inner, ROW, border);
        }

        if (checked) {
            fillGradientDot(painter, centerX, centerY, radius * (float) Math.sqrt(2), dot);
        }

        int code = !enabled ? DialogPainter.CODE_DISABLED : checked ? DialogPainter.CODE_HIGHLIGHT : DialogPainter.CODE_BUTTON;
        float labelLeft = positionX + RADIO_SIZE + RADIO_LABEL_GAP;

        painter.drawCodeText(slot,
                             text,
                             labelLeft, positionY,
                             positionX + width - labelLeft, height,
                             ControlPainterUtils.ROW_TEXT_FORMAT,
                             code);
    }

    /**
     * Draws a page slider's track ({@code .backRange}: a {@code 1} px line in the track's fill inside a {@code 1} px border) across a
     * width, centered vertically on a line.
     *
     * @param painter the painter
     * @param left    the track's left
     * @param centerY the line the track is centered on
     * @param width   the track's width
     * @param enabled whether the slider takes input
     */
    public static void drawSliderTrack(@NotNull DialogPainter painter, float left, float centerY, float width, boolean enabled) {
        drawBox(painter, left, centerY - SLIDER_TRACK_HEIGHT / 2, width, SLIDER_TRACK_HEIGHT, FIELD_BORDER, enabled ? BORDER : DISABLED_BORDER,
                SLIDER_TRACK, true, true, true, true);
    }

    /**
     * Draws a stop of a page slider ({@code .rangeLabels li}): a disc in the track's fill inside a ring of the field's border.
     *
     * @param painter the painter
     * @param centerX the stop's center x
     * @param centerY the stop's center y
     * @param enabled whether the slider takes input
     */
    public static void drawSliderStop(@NotNull DialogPainter painter, float centerX, float centerY, boolean enabled) {
        float radius = SLIDER_STOP_SIZE / 2;

        fillDisc(painter, centerX, centerY, radius, enabled ? BORDER : DISABLED_BORDER);
        fillDisc(painter, centerX, centerY, radius - FIELD_BORDER, SLIDER_TRACK);
    }

    /**
     * Draws a page slider's thumb ({@code .frontRange} thumb): a disc in the track's fill inside a ring of the field's border, with the
     * radio button's yellow dot in its middle (gray while disabled).
     *
     * @param painter the painter
     * @param centerX the thumb's center x
     * @param centerY the thumb's center y
     * @param enabled whether the slider takes input
     */
    public static void drawSliderThumb(@NotNull DialogPainter painter, float centerX, float centerY, boolean enabled) {
        float radius = SLIDER_THUMB_SIZE / 2;

        fillDisc(painter, centerX, centerY, radius, enabled ? BORDER : DISABLED_BORDER);
        fillDisc(painter, centerX, centerY, radius - FIELD_BORDER, SLIDER_TRACK);
        fillGradientDot(painter, centerX, centerY, radius * (float) Math.sqrt(2), enabled ? DOT : DISABLED_DOT);
    }

    /**
     * @return the size of a page slider's thumb, which its travel leaves free at both ends
     */
    public static float getSliderThumbSize() {
        return SLIDER_THUMB_SIZE;
    }

    /**
     * Draws the pages' radial dot ({@code radial-gradient(at center, color 30%, transparent 50%)}): solid to {@code 30} % of the gradient's
     * ray, fading out at {@code 50} %, as discs from the outermost, faint one to the solid core.
     */
    private static void fillGradientDot(@NotNull DialogPainter painter, float centerX, float centerY, float ray, int color) {
        for (int band = DOT_BANDS; band >= 0; band--) {
            float bandRadius = ray * (DOT_SOLID + (DOT_EDGE - DOT_SOLID) * band / DOT_BANDS);
            int alpha = band == 0
                        ? (OverlayPass.OPAQUE_ALPHA & ALPHA_MASK)
                        : (OverlayPass.OPAQUE_ALPHA & ALPHA_MASK) * (DOT_BANDS - band) / (DOT_BANDS + 1) / 2;

            fillDisc(painter, centerX, centerY, bandRadius, color & RGB_MASK | alpha);
        }
    }

    private static void fillDisc(@NotNull DialogPainter painter, float centerX, float centerY, float radius, int color) {
        for (float row = -radius; row < radius; row += ROW) {
            float distanceY = Math.abs(row + ROW / 2);
            float halfWidth = (float) Math.sqrt(Math.max(0, radius * radius - distanceY * distanceY));

            if (halfWidth > 0) {
                painter.fillRectangle(centerX - halfWidth, centerY + row, 2 * halfWidth, ROW, color);
            }
        }
    }


    /**
     * Draws the shared page button ({@code .Button}): a {@code 1} px {@code #6c0000} frame with {@code 2} px corners, inside a {@code .Button-group}
     * without its own fill ({@code rgba(0,0,0,.8)} otherwise), the label centered in {@code #4cc428}, {@code #b4fc74} while hovered and
     * gray while disabled.
     *
     * @param painter   the painter
     * @param text      the label
     * @param positionX the left
     * @param positionY the top
     * @param width     the width ({@link #getButtonWidth})
     * @param hovered   whether the pointer is over it
     * @param enabled   whether it takes clicks
     * @param filled    whether it draws its own background (not in a group)
     */
    public static void drawButton(@NotNull DialogPainter painter,
                                  @NotNull String text,
                                  float positionX, float positionY,
                                  float width,
                                  boolean hovered,
                                  boolean enabled,
                                  boolean filled) {
        drawBox(painter, positionX, positionY, width, BUTTON_HEIGHT, BUTTON_RADIUS, BORDER, filled ? BACKGROUND : 0, true, true, true, true);

        int color = !enabled ? DISABLED_TEXT : hovered ? HIGHLIGHT_TEXT : BUTTON_TEXT;

        drawText(painter,
                 BUTTON_FONT,
                 text,
                 positionX, positionY + BUTTON_PADDING + 1,
                 width, BUTTON_HEIGHT - 2 * (BUTTON_PADDING + 1),
                 InterfaceText.CENTER | InterfaceText.TOP,
                 color);
    }

    /**
     * Draws a {@code .Button-group} behind its buttons: a {@code 1.5} px {@code #331f1f} frame with {@code 4} px corners around
     * {@code padding: 1.5px}, filled black.
     *
     * @param painter   the painter
     * @param positionX the left
     * @param positionY the top
     * @param width     the width
     * @param height    the height
     */
    public static void drawButtonGroup(@NotNull DialogPainter painter, float positionX, float positionY, float width, float height) {
        drawBox(painter, positionX, positionY, width, height, BUTTON_GROUP_RADIUS, PANEL_BORDER, PANEL_BORDER, true, true, true, true);
        drawBox(painter,
                positionX + BUTTON_GROUP_BORDER, positionY + BUTTON_GROUP_BORDER, width - 2 * BUTTON_GROUP_BORDER, height - 2 * BUTTON_GROUP_BORDER,
                BUTTON_GROUP_RADIUS - BUTTON_GROUP_BORDER,
                BACKGROUND, BACKGROUND,
                true, true,
                true, true);
    }

    /**
     * Draws the frame of the pages' modal dialog ({@code .dialogBox} of the dialog component): black, a {@code 1} px {@code #331f1f} border with
     * {@code 6} px corners and the {@code #6c0000} frame {@code 1} px inside it with {@code 4} px corners.
     *
     * @param painter   the painter
     * @param positionX the left
     * @param positionY the top
     * @param width     the width
     * @param height    the height
     */
    public static void drawDialogBox(@NotNull DialogPainter painter, float positionX, float positionY, float width, float height) {
        drawBox(painter, positionX, positionY, width, height, DIALOG_RADIUS, PANEL_BORDER, BACKGROUND, true, true, true, true);
        drawBox(painter,
                positionX + CONTENT_INSET, positionY + CONTENT_INSET, width - CONTENT_INSET * 2, height - CONTENT_INSET * 2,
                DIALOG_INNER_RADIUS,
                BORDER, 0,
                true, true,
                true, true);
    }

    private WebPanelStyleUtils() {
    }
}
