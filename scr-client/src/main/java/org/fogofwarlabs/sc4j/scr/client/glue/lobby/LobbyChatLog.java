package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatEntry;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatEntryKind;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueScreen;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.EditControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.WebPanelStyleUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.WebScrollBar;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.render.ui.TrueTypeText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shows the log of a chat page: wrapped sender and message paragraphs, oldest first, with the page's scroll bar, and lays out the page's
 * input. The game lobby, the Beacon.Link channel chat, the room list and the game creation share it; the Beacon.Link pages also share the
 * input's prefix (the channel, or the whisper target) and its keys.
 */
final class LobbyChatLog {
    /**
     * {@code .textArea p span}, {@code .chatInput}: {@code Arial} (and the Hangul face) at {@code 8} px.
     */
    private static final float TEXT_SIZE = 8;
    static final int TEXT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_ARIAL_FAMILY, TEXT_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    /**
     * {@code .message_prepend} and {@code .joinLeave}; {@code .message} and the input.
     */
    static final int NAME_COLOR = 0x4CC428FF;
    static final int MESSAGE_COLOR = 0x89F85CFF;

    /**
     * The chat page ({@code .chatPanel}): the log ({@code .textAreaContainer.chatArea}) {@code 3} below the top and {@code 30} above the input form,
     * without padding (the later {@code .chatArea{padding:0}} overrides the paddings of {@code .textAreaContainer}); the form
     * ({@code margin-bottom: 4px}) holds the one-line input at the page's left.
     */
    private static final float TOP = 3;
    private static final float LOG_BOTTOM = 30;
    private static final int FORM_BOTTOM = 4;
    /**
     * {@code .textArea p}: margin-bottom {@code 2}.
     */
    private static final float PARAGRAPH_GAP = 2;
    private static final int TEXT_WIDTH_PADDING = 1;
    /**
     * The text whose line box gives the height of an input line.
     */
    private static final @NotNull String LINE_SAMPLE = "A";

    /**
     * The input of a page with a text field ({@link Page}): the screens' framed text field ({@link GlueScreen#FRAMED_FLAG}).
     */
    static final int FIELD_HEIGHT = GlueScreen.FRAMED_CONTROL_HEIGHT;
    /**
     * The gap between the log of a page with a text field and the field.
     */
    private static final float FIELD_GAP = 4;

    private static final @NotNull String VISIBLE_FLAG = "WS_VISIBLE";
    private static final @NotNull String TAB_STOP_FLAG = "WS_TABSTOP";
    private static final @NotNull String NO_SCROLL_BAR_MESSAGE = "The chat log has no scroll bar yet.";

    /**
     * A chat page whose input is the screens' framed text field: where its log starts inside the page and how far the field stands above the page's
     * bottom.
     */
    enum Page {
        /**
         * The channel chat's page ({@code ChatPanel}): the log {@code 8} in from the page's left and {@code 3} below its top margin, the
         * field at the page's bottom.
         */
        CHANNEL(8, 3, 0),
        /**
         * The chat under the room list, the game creation and its map browser ({@code GlobalChatPanel}): the log {@code 7} in and {@code 2}
         * below its top margin, the field {@code 3} above the page's bottom.
         */
        GLOBAL(7, 2, 3);

        private final float paddingLeft;
        private final float paddingTop;
        private final int fieldBottom;

        Page(float paddingLeft, float paddingTop, int fieldBottom) {
            this.paddingLeft = paddingLeft;
            this.paddingTop  = paddingTop;
            this.fieldBottom = fieldBottom;
        }
    }

    /**
     * The log's rectangle ({@code .chatArea}) in interface pixels.
     */
    static final class Bounds {
        private final float left;
        private final float top;
        private final float right;
        private final float bottom;

        private Bounds(float left, float top, float right, float bottom) {
            this.left   = left;
            this.top    = top;
            this.right  = right;
            this.bottom = bottom;
        }

        /**
         * @return the left edge
         */
        float getLeft() {
            return this.left;
        }

        /**
         * @return the top edge
         */
        float getTop() {
            return this.top;
        }

        /**
         * @return the right edge
         */
        float getRight() {
            return this.right;
        }

        /**
         * @return the bottom edge
         */
        float getBottom() {
            return this.bottom;
        }
    }

    /**
     * One paragraph of the log: the sender's prefix (empty for a line without a sender), the text and their colors.
     */
    private static final class Line {
        private final @NotNull String prefix;
        private final @NotNull String text;
        private final int prefixColor;
        private final int textColor;

        private Line(@NotNull String prefix, @NotNull String text, int prefixColor, int textColor) {
            this.prefix      = prefix;
            this.text        = text;
            this.prefixColor = prefixColor;
            this.textColor   = textColor;
        }
    }

    /**
     * Returns the log's rectangle in the chat page: from the page's left to its right edge (the log has no padding), from the log's top margin
     * below the page's top to its bottom margin above the input form, which holds one input line above the form's bottom margin.
     *
     * @param left            the page's left
     * @param top             the page's top
     * @param width           the page's width
     * @param height          the page's height
     * @param inputLineHeight the line height of the input
     * @return the log's rectangle
     */
    static @NotNull Bounds getBounds(float left, float top, float width, float height, float inputLineHeight) {
        float logBottom = top + height - FORM_BOTTOM - inputLineHeight - LOG_BOTTOM;

        return new Bounds(left, top + TOP, left + width, logBottom);
    }

    /**
     * Returns the log's text area in a chat page with a text field input ({@link #createFieldInputDefinition}): inside the page's paddings, from
     * the log's top margin down to the field, {@link #FIELD_GAP} above the field's top.
     *
     * @param kind   the kind of the chat page
     * @param left   the page's left
     * @param top    the page's top
     * @param width  the page's width
     * @param height the page's height
     * @return the log's text area
     */
    static @NotNull Bounds getBounds(@NotNull Page kind, float left, float top, float width, float height) {
        float fieldTop = top + height - kind.fieldBottom - FIELD_HEIGHT;

        return new Bounds(left + kind.paddingLeft, top + TOP + kind.paddingTop, left + width, fieldTop - FIELD_GAP);
    }

    /**
     * Creates the definition of a chat page's input: the page's one-line input at the page's left, {@code 4} above its bottom, one {@code 8} px
     * Arial line high.
     *
     * @param canvas the glue canvas
     * @param page   the chat page
     * @param id     the input's control id, one the page's dialog does not use
     * @return the created {@link DialogControl} instance
     */
    static @NotNull DialogControl createInputDefinition(@NotNull GlueCanvas canvas, @NotNull DialogControl page, int id) {
        int inputHeight = Math.round(canvas.getLineHeight(TEXT, LINE_SAMPLE));

        return ScrDialogControlUtils.createControl(ScrControlType.EDITTEXT,
                                                   id,
                                                   page.getX(), page.getY() + page.getHeight() - FORM_BOTTOM - inputHeight,
                                                   page.getWidth(), inputHeight,
                                                   Set.of(VISIBLE_FLAG, TAB_STOP_FLAG, WebPanelStyleUtils.FLAG),
                                                   "",
                                                   List.of());
    }

    /**
     * Creates the definition of a chat page's text field input: the screens' framed text field over the page's width at its bottom, above
     * it by the page's field margin.
     *
     * @param kind the kind of the chat page
     * @param page the chat page
     * @param id   the input's control id, one the page's dialog does not use
     * @return the created {@link DialogControl} instance
     */
    static @NotNull DialogControl createFieldInputDefinition(@NotNull Page kind, @NotNull DialogControl page, int id) {
        return ScrDialogControlUtils.createControl(ScrControlType.EDITTEXT,
                                                   id,
                                                   page.getX(), page.getY() + page.getHeight() - kind.fieldBottom - FIELD_HEIGHT,
                                                   page.getWidth(), FIELD_HEIGHT,
                                                   Set.of(VISIBLE_FLAG, TAB_STOP_FLAG, GlueScreen.FRAMED_FLAG),
                                                   "",
                                                   List.of());
    }

    /**
     * Creates a {@link LobbyChatLog} instance of a chat page with a text field input, whose log fills the page inside its paddings down to
     * the field ({@link #createFieldInputDefinition}, {@link #getBounds(Page, float, float, float, float)}). The log keeps the last
     * {@link ChannelChat#HISTORY} lines; the oldest go first.
     *
     * @param kind the kind of the chat page
     * @return the created {@link LobbyChatLog} instance
     */
    static @NotNull LobbyChatLog createChatPageLog(@NotNull Page kind) {
        return new LobbyChatLog(ChannelChat.HISTORY, kind);
    }

    /**
     * Returns the prefix of a Beacon.Link chat page's input: the channel the typed lines go to, or the player they are whispered to.
     *
     * @param chat the chat, or {@code null} before it opens
     * @return the prefix, empty for none
     */
    static @NotNull String getInputPrefix(@Nullable ChannelChat chat) {
        return chat == null ? "" : chat.getInputPrefix();
    }

    /**
     * Returns the color of a Beacon.Link chat page's input prefix: the whispers' color while the input whispers, the senders' color otherwise.
     *
     * @param chat the chat, or {@code null} before it opens
     * @return the packed {@code 0xRRGGBBAA} color
     */
    static int getInputPrefixColor(@Nullable ChannelChat chat) {
        boolean whispering = chat != null && chat.getWhisperTarget() != null;

        return ChannelChatScreen.getEntryColor(whispering ? ChatEntryKind.WHISPER : ChatEntryKind.SAY, true);
    }

    /**
     * Takes a typed whisper start out of a Beacon.Link chat page's input ({@link ChannelChat#takeWhisperStart}).
     *
     * @param chat  the chat, or {@code null} before it opens
     * @param input the input, or {@code null} before the screen opens
     * @return the input's new text, or {@code null} if the text stays
     */
    static @Nullable String takeWhisperStart(@Nullable ChannelChat chat, @Nullable EditControl input) {
        if (chat == null || input == null) {
            return null;
        }

        String typed = input.getEditText().getText();
        String rest = chat.takeWhisperStart(typed);

        return rest.equals(typed) ? null : rest;
    }

    /**
     * Takes a key of a Beacon.Link chat page's input while it has the focus: Enter runs the typed line or command, Backspace in the empty
     * input says lines in the channel again after a whisper.
     *
     * @param chat    the chat, or {@code null} before it opens
     * @param input   the input, or {@code null} before the screen opens
     * @param focused whether the input has the keyboard focus
     * @param key     the key
     * @return the input's new text (empty), or {@code null} if the key is not taken
     */
    static @Nullable String takeInputKey(@Nullable ChannelChat chat, @Nullable EditControl input, boolean focused, char key) {
        if (chat == null || input == null || !focused) {
            return null;
        }

        String typed = input.getEditText().getText();

        if (key == GlueScreen.KEY_ENTER && !typed.isEmpty()) {
            chat.submit(typed);

            return "";
        }

        if (key == GlueScreen.KEY_BACKSPACE && typed.isEmpty() && chat.getWhisperTarget() != null) {
            chat.setWhisperTarget(null);

            return "";
        }

        return null;
    }

    /**
     * Breaks a text into lines, the first one narrower (it follows the prefix), at spaces where possible and between
     * characters otherwise ({@code word-wrap: break-word}).
     *
     * @param canvas     the glue canvas
     * @param text       the text
     * @param firstWidth the width of the first line
     * @param width      the width of the following lines
     * @return the text with a line feed at each break
     */
    private static @NotNull String wrapAfter(@NotNull GlueCanvas canvas, @NotNull String text, float firstWidth, float width) {
        StringBuilder wrapped = new StringBuilder();
        String rest = text;
        float room = firstWidth;

        while (canvas.measure(TEXT, rest) > room && rest.length() > 1) {
            int end = rest.length();

            while (end > 1 && canvas.measure(TEXT, rest.substring(0, end)) > room) {
                end--;
            }

            int space = rest.lastIndexOf(' ', end);
            int cut = space > 0 ? space : end;

            wrapped.append(rest, 0, cut).append('\n');
            rest = rest.substring(space > 0 ? cut + 1 : cut);
            room = width;
        }

        return wrapped.append(rest).toString();
    }

    /**
     * Returns a paragraph's height: its wrapped lines at the line box of its text.
     *
     * @param canvas the glue canvas
     * @param line   the line
     * @param width  the text width
     * @return the paragraph's height
     */
    private static float getParagraphHeight(@NotNull GlueCanvas canvas, @NotNull Line line, float width) {
        float prefixWidth = line.prefix.isEmpty() ? 0 : canvas.measure(TEXT, line.prefix);
        String wrapped = wrapAfter(canvas, line.text, width - prefixWidth, width);
        int count = (int) wrapped.chars().filter(character -> character == '\n').count() + 1;

        return count * canvas.getLineHeight(TEXT, line.prefix + line.text);
    }

    private final int history;
    private final @Nullable Page page;

    private final @NotNull List<Line> lines;
    private @Nullable WebScrollBar scroll;
    /**
     * The log the chat hands its entries to while this log listens ({@link #listen}), or {@code null}.
     */
    private @Nullable Consumer<@NotNull ChatEntry> listener;

    /**
     * Creates a {@link LobbyChatLog} instance without lines and without a scroll bar, for a page whose log has no padding over a one-line
     * input ({@link #createInputDefinition}).
     *
     * @param history the most lines the log keeps; the oldest go first
     */
    LobbyChatLog(int history) {
        this(history, null);
    }

    /**
     * Creates a {@link LobbyChatLog} instance without lines and without a scroll bar.
     *
     * @param history the most lines the log keeps; the oldest go first
     * @param page    the kind of the chat page with a text field input, or {@code null} for a page with a one-line input
     */
    private LobbyChatLog(int history, @Nullable Page page) {
        this.history = history;
        this.page    = page;

        this.lines    = new ArrayList<>();
        this.scroll   = null;
        this.listener = null;
    }

    /**
     * @return the number of lines the log holds
     */
    int getLineCount() {
        return this.lines.size();
    }

    /**
     * Sets the log's scroll bar, which the screen attached to its dialog at open; it starts at the end of the log.
     *
     * @param scroll the scroll bar
     */
    void setScrollBar(@NotNull WebScrollBar scroll) {
        this.scroll = scroll;
    }


    /**
     * Adds a line in the page's sender and message colors.
     *
     * @param prefix the sender's prefix, empty for a line without a sender
     * @param text   the text
     */
    void add(@NotNull String prefix, @NotNull String text) {
        this.add(prefix, text, NAME_COLOR, MESSAGE_COLOR);
    }

    /**
     * Adds a line; the oldest line goes once the log is full.
     *
     * @param prefix      the sender's prefix, empty for a line without a sender
     * @param text        the text
     * @param prefixColor the packed {@code 0xRRGGBBAA} color of the prefix
     * @param textColor   the packed {@code 0xRRGGBBAA} color of the text
     */
    void add(@NotNull String prefix, @NotNull String text, int prefixColor, int textColor) {
        this.lines.add(new Line(prefix, text, prefixColor, textColor));

        while (this.lines.size() > this.history) {
            this.lines.removeFirst();
        }

        // A new line scrolls the log to its end; before the open there is no bar yet, and the open's bar starts at the end.
        WebScrollBar bar = this.scroll;

        if (bar != null) {
            bar.scrollToEnd();
        }
    }

    /**
     * Adds this log to the chat's logs: the chat's recent entries and then each new entry are added in their kind's colors.
     *
     * @param chat the chat
     */
    void listen(@NotNull ChannelChat chat) {
        Consumer<ChatEntry> log = entry -> this.add(entry.getPrefix(),
                                                    entry.getText(),
                                                    ChannelChatScreen.getEntryColor(entry.getKind(), true),
                                                    ChannelChatScreen.getEntryColor(entry.getKind(), false));

        this.listener = log;
        chat.addLog(log, true);
    }

    /**
     * Stops this log taking the chat's entries; the chat's other logs keep taking them.
     *
     * @param chat the chat
     */
    void stopListening(@NotNull ChannelChat chat) {
        Consumer<ChatEntry> log = this.listener;

        if (log != null) {
            chat.removeLog(log);
            this.listener = null;
        }
    }

    /**
     * Draws wrapped sender/message paragraphs with a shared baseline, clip rectangle and the scroll bar in the log of a chat page.
     *
     * @param canvas the glue canvas
     * @param left   the page's left edge in interface pixels
     * @param top    the page's top edge in interface pixels
     * @param width  the page's width
     * @param height the page's height
     * @throws IOException           if the scroll bar image cannot be read
     * @throws IllegalStateException if the log has no scroll bar yet
     */
    void draw(@NotNull GlueCanvas canvas, float left, float top, float width, float height) throws IOException {
        Page kind = this.page;
        Bounds bounds = kind == null ? getBounds(left, top, width, height, canvas.getLineHeight(TEXT, LINE_SAMPLE))
                                     : getBounds(kind, left, top, width, height);
        float logTop = bounds.getTop();
        float logBottom = bounds.getBottom();
        float logLeft = bounds.getLeft();
        float logRight = bounds.getRight();
        float logHeight = logBottom - logTop;

        // The paragraphs' heights at the full width, then with the scroll bar when they do not fit.
        float textWidth = logRight - logLeft;
        float total = this.getHeight(canvas, textWidth);

        if (total > logHeight) {
            textWidth -= WebScrollBar.WIDTH;
            total = this.getHeight(canvas, textWidth);
        }

        WebScrollBar bar = this.requireScrollBar();

        bar.setContent(total, logHeight);
        canvas.setClip(logLeft, logTop, logRight - logLeft, logHeight);

        float y = logTop - bar.getOffset();

        for (Line line : this.lines) {
            float lineHeight = canvas.getLineHeight(TEXT, line.prefix + line.text);
            float paragraph = getParagraphHeight(canvas, line, textWidth);

            if (y + paragraph > logTop && y < logBottom) {
                float prefixWidth = line.prefix.isEmpty() ? 0 : canvas.measure(TEXT, line.prefix);

                // The text continues after the prefix on the first line and wraps below it (an inline span); the two spans
                // share the first line's baseline (one line box for both).
                String wrapped = wrapAfter(canvas, line.text, textWidth - prefixWidth, textWidth);
                int firstEnd = wrapped.indexOf('\n');
                String first = firstEnd < 0 ? wrapped : wrapped.substring(0, firstEnd);
                float baseline = canvas.getBaseline(TEXT, line.prefix + first);

                if (!line.prefix.isEmpty()) {
                    WebPanelStyleUtils.drawText(canvas,
                                                TEXT,
                                                line.prefix,
                                                logLeft, y + baseline - canvas.getBaseline(TEXT, line.prefix),
                                                prefixWidth + TEXT_WIDTH_PADDING, lineHeight,
                                                GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                                line.prefixColor);
                }

                WebPanelStyleUtils.drawText(canvas,
                                            TEXT,
                                            first,
                                            logLeft + prefixWidth, y + baseline - canvas.getBaseline(TEXT, first),
                                            textWidth - prefixWidth + TEXT_WIDTH_PADDING, lineHeight,
                                            GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                            line.textColor);

                if (firstEnd >= 0) {
                    WebPanelStyleUtils.drawText(canvas,
                                                TEXT,
                                                wrapped.substring(firstEnd + 1),
                                                logLeft, y + lineHeight,
                                                textWidth + TEXT_WIDTH_PADDING, paragraph - lineHeight,
                                                GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                                line.textColor);
                }
            }

            y += paragraph + PARAGRAPH_GAP;
        }

        canvas.setClip(0, 0, GlueCanvas.NO_CLIP_SIZE, 0);
        bar.draw(canvas, logRight - WebScrollBar.WIDTH, logTop, logHeight);
    }

    /**
     * Returns the height of all paragraphs at a width.
     *
     * @param canvas the glue canvas
     * @param width  the text width
     * @return the paragraphs' heights, each with its gap
     */
    private float getHeight(@NotNull GlueCanvas canvas, float width) {
        float total = 0;

        for (Line line : this.lines) {
            total += getParagraphHeight(canvas, line, width) + PARAGRAPH_GAP;
        }

        return total;
    }

    /**
     * Presses the scroll bar.
     *
     * @param x the interface x
     * @param y the interface y
     * @return {@code true} if the press hit the scroll bar, {@code false} otherwise
     * @throws IllegalStateException if the log has no scroll bar yet
     */
    boolean press(float x, float y) {
        return this.requireScrollBar().press(x, y);
    }

    /**
     * Drags the pressed scroll bar.
     *
     * @param y the interface y
     * @throws IllegalStateException if the log has no scroll bar yet
     */
    void drag(float y) {
        this.requireScrollBar().drag(y);
    }

    /**
     * Releases the scroll bar.
     *
     * @throws IllegalStateException if the log has no scroll bar yet
     */
    void release() {
        this.requireScrollBar().release();
    }

    /**
     * Scrolls the log by a wheel notch.
     *
     * @param down {@code true} for a notch toward the user
     * @throws IllegalStateException if the log has no scroll bar yet
     */
    void scrollByWheel(boolean down) {
        this.requireScrollBar().scrollByWheel(down);
    }

    private @NotNull WebScrollBar requireScrollBar() {
        WebScrollBar bar = this.scroll;

        if (bar == null) {
            throw Exceptions.newIllegalStateException(NO_SCROLL_BAR_MESSAGE);
        }

        return bar;
    }
}
