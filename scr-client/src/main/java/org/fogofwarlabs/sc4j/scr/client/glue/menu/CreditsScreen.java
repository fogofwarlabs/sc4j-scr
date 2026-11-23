package org.fogofwarlabs.sc4j.scr.client.glue.menu;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueContext;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueScreen;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.WebPanelStyleUtils;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.fogofwarlabs.sc4j.scr.render.ui.TrueTypeText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads installed credit entries, appends client credits and lays out a scrollable list with keyboard and wheel input.
 */
public final class CreditsScreen extends GlueScreen {
    private static final @NotNull Logger LOGGER = Logger.of(CreditsScreen.class);

    private static final @NotNull String DIALOG = "CreditsScreen";
    private static final @NotNull String PALETTE_FOLDER = "glue/Battlenet";
    private static final @NotNull String PAGE_SCRIPT = "webui/dist/CreditsPanel/CreditsPanel.js";
    /**
     * The start of one credits entry of the page script, {@code {name:"...",style:N}} (double or single quotes).
     */
    private static final @NotNull String ENTRY_START = "{name:";
    /**
     * The style key that follows the quoted name of an entry.
     */
    private static final @NotNull String ENTRY_STYLE = ",style:";
    /**
     * The most digits a style number has (an int).
     */
    private static final int MAXIMUM_STYLE_DIGITS = 9;
    /**
     * The character that closes an entry after its style number.
     */
    private static final char ENTRY_END = '}';
    private static final char DOUBLE_QUOTE = '"';
    private static final char SINGLE_QUOTE = '\'';
    /**
     * The character that starts an escape sequence inside a quoted name, and the length of the two-character sequence.
     */
    private static final char ESCAPE = '\\';
    private static final int ESCAPE_LENGTH = 2;
    private static final int UNICODE_ESCAPE_DIGITS = 4;
    private static final int HEXADECIMAL_RADIX = 16;

    /**
     * The credit list bounds in the {@code 640x480} interface coordinate space.
     */
    private static final float LIST_TOP = 48.96f;
    private static final float LIST_LEFT = 147.2f;
    private static final float LIST_WIDTH = 364.8f;
    private static final float LIST_HEIGHT = 398.4f;
    private static final int TEXT_COLOR = 0xB4FC74FF;

    private static final int TITLE_STYLE = 1;
    private static final int SECTION_STYLE = 2;
    private static final int ROLE_STYLE = 3;
    private static final int NAME_STYLE = 4;
    private static final int GAP_STYLE = 5;
    private static final int RULE_STYLE = 6;
    /**
     * The client's own credits, before the page's first entry and laid out by the page's styles: a section heading (style {@code 2}, as
     * {@code SPECIAL THANKS}), its name (style {@code 4}) and two gaps (style {@code 5}) that separate them from the page.
     */
    private static final @NotNull String @NotNull [] CLIENT_CREDIT_NAMES = {
            "SC4J (Simulation Core for Java) Developer", "박정환 (Park Jeonghwan)",
            "", ""
    };
    private static final int @NotNull [] CLIENT_CREDIT_STYLES = {SECTION_STYLE, NAME_STYLE, GAP_STYLE, GAP_STYLE};
    /**
     * The title size and its vertical margin in interface pixels.
     */
    private static final float TITLE_SIZE = 20;
    private static final float TITLE_MARGIN = 13.4f;
    private static final float SECTION_SIZE = 14;
    private static final float SECTION_LINE = 15;
    private static final float SECTION_BOTTOM = 10;
    private static final float ROLE_SIZE = 11;
    private static final float ROLE_LINE = 12;
    private static final float ROLE_TOP = 15;
    private static final float ROLE_BOTTOM = 4;
    private static final float NAME_SIZE = 9;
    private static final float NAME_LINE = 10;
    private static final float GAP_SIZE = 7;
    private static final float GAP_LINE = 8;
    private static final float GAP_BOTTOM = 25;
    /**
     * The rule's holder: one body line ({@code 16} px text) with the {@code 2} px rule in its middle.
     */
    private static final float RULE_HOLDER = 18;
    private static final float RULE_THICKNESS = 2;
    private static final int CENTER_DIVISOR = 2;

    private static final float KEY_INITIAL_SPEED = 2.5f;
    private static final float KEY_MAXIMUM_SPEED = 25;
    private static final float KEY_ACCELERATION = 0.5f;
    private static final float KEY_DECELERATION = 1;
    private static final float AUTO_SPEED = 0.5f;
    private static final long AUTO_SCROLL_DELAY = 2000;
    private static final int MILLISECONDS_PER_SECOND = 1000;
    private static final int FRAMES_PER_SECOND = 60;
    private static final long FRAME_MILLISECONDS = MILLISECONDS_PER_SECOND / FRAMES_PER_SECOND;
    private static final float WHEEL_STEP = 40;

    /**
     * One laid out credits entry.
     */
    private static final class Block {
        private final @NotNull List<String> lines;
        private final int fontSlot;
        private final float lineHeight;
        private final float top;
        private final boolean rule;

        private Block(@NotNull List<String> lines, int fontSlot, float lineHeight, float top, boolean rule) {
            this.lines      = lines;
            this.fontSlot   = fontSlot;
            this.lineHeight = lineHeight;
            this.top        = top;
            this.rule       = rule;
        }
    }

    /**
     * Reads quoted names and numeric styles from credits entries with a forward scan and backslash unescaping.
     *
     * @param script the script text
     * @param names  receives the names, unescaped
     * @param styles receives the styles
     * @throws NumberFormatException if a Unicode escape contains invalid hexadecimal digits
     */
    public static void parseEntries(@NotNull String script, @NotNull List<String> names, @NotNull List<Integer> styles) {
        int index = script.indexOf(ENTRY_START);

        while (index >= 0) {
            int position = index + ENTRY_START.length();
            int next = index + 1;
            char quote = position < script.length() ? script.charAt(position) : 0;

            if (quote == DOUBLE_QUOTE || quote == SINGLE_QUOTE) {
                int end = position + 1;

                while (end < script.length() && script.charAt(end) != quote) {
                    end += script.charAt(end) == ESCAPE ? ESCAPE_LENGTH : 1;
                }

                int styleStart = end + 1 + ENTRY_STYLE.length();
                int styleEnd = styleStart;

                while (styleEnd < script.length() && Character.isDigit(script.charAt(styleEnd))) {
                    styleEnd++;
                }

                if (end < script.length() && script.startsWith(ENTRY_STYLE, end + 1) && styleEnd > styleStart &&
                    styleEnd - styleStart <= MAXIMUM_STYLE_DIGITS && styleEnd < script.length() && script.charAt(styleEnd) == ENTRY_END) {
                    names.add(unescape(script.substring(position + 1, end)));
                    styles.add(Integer.parseInt(script.substring(styleStart, styleEnd)));
                    next = styleEnd + 1;
                }
            }

            index = script.indexOf(ENTRY_START, next);
        }
    }

    /**
     * Puts the client's own credits ({@link #CLIENT_CREDIT_NAMES}) before the page's entries.
     *
     * @param names  the names
     * @param styles the styles
     */
    static void prependClientCredits(@NotNull List<String> names, @NotNull List<Integer> styles) {
        for (int index = 0; index < CLIENT_CREDIT_NAMES.length; index++) {
            names.add(index, CLIENT_CREDIT_NAMES[index]);
            styles.add(index, CLIENT_CREDIT_STYLES[index]);
        }
    }

    private static @NotNull String unescape(@NotNull String text) {
        StringBuilder result = new StringBuilder(text.length());

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);

            if (character != ESCAPE || index + 1 >= text.length()) {
                result.append(character);

                continue;
            }

            char escaped = text.charAt(++index);

            switch (escaped) {
                case 'n' -> result.append('\n');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (index + UNICODE_ESCAPE_DIGITS < text.length()) {
                        result.append((char) Integer.parseInt(text.substring(index + 1, index + 1 + UNICODE_ESCAPE_DIGITS), HEXADECIMAL_RADIX));
                        index += UNICODE_ESCAPE_DIGITS;
                    }
                }
                default -> result.append(escaped);
            }
        }

        return result.toString();
    }

    private final @Nullable String music;
    private final @NotNull Runnable finished;

    private final @NotNull List<String> names;
    private final @NotNull List<Integer> styles;
    private final @NotNull List<Block> blocks;
    private float contentHeight;

    private float scrollTop;
    private float scrollValue;
    private boolean upHeld;
    private boolean downHeld;
    private boolean keyScrolling;
    private boolean autoScrolling;
    private long autoScrollAt;
    private long lastFrameTime;
    private boolean ended;

    /**
     * Creates a {@link CreditsScreen} instance.
     *
     * @param music    the glue music track the screen starts (Show Credits: track {@code 6}, {@code terran2}), or {@code null} to keep the music that plays
     * @param finished what follows the credits
     */
    public CreditsScreen(@Nullable String music, @NotNull Runnable finished) {
        super(DIALOG, PALETTE_FOLDER, null);

        this.music    = music;
        this.finished = finished;

        this.names         = new ArrayList<>();
        this.styles        = new ArrayList<>();
        this.blocks        = new ArrayList<>();
        this.contentHeight = -1;

        this.scrollTop     = 0;
        this.scrollValue   = 0;
        this.upHeld        = false;
        this.downHeld      = false;
        this.keyScrolling  = false;
        this.autoScrolling = false;
        this.autoScrollAt  = -1;
        this.lastFrameTime = -1;
        this.ended         = false;
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * The screen starts its music, if any, reads the installed credit entries and appends the client's own credits.
     *
     * @param glueContext the shared services
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        if (this.music != null) {
            glueContext.getAudio().playMusic(this.music);
        }

        try {
            parseEntries(new String(glueContext.getCanvas().getStorage().readFile(PAGE_SCRIPT), StandardCharsets.UTF_8), this.names, this.styles);
        } catch (IOException exception) {
            LOGGER.warning("CREDITS UNAVAILABLE: " + exception.getMessage());
        }

        prependClientCredits(this.names, this.styles);
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * The credits have no controls to activate.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
    }

    /**
     * Called first for every key of the screen (after an open popup): a screen without controls, such as a cinematic, takes its keys here.
     * <p>
     * Escape and Enter end the credits, and Up and Down start key scrolling. The credits consume every key.
     *
     * @param key the key
     * @return {@code true} if the key was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onKey(char key) {
        switch (key) {
            case KEY_ESCAPE, KEY_ENTER -> this.end();
            case KEY_UP -> {
                this.upHeld       = true;
                this.keyScrolling = true;
                this.stopAutoScrolling();
            }
            case KEY_DOWN -> {
                this.downHeld     = true;
                this.keyScrolling = true;
                this.stopAutoScrolling();
            }
            default -> {
            }
        }

        return true;
    }

    /**
     * Called when a navigation key ({@link #KEY_UP}, {@link #KEY_DOWN}, ...) is released, for a screen that follows held
     * keys (the credits page's scrolling).
     * <p>
     * Releasing Up or Down ends key scrolling once neither key is held.
     *
     * @param key the released key
     */
    @Override
    protected void onKeyReleased(char key) {
        if (key == KEY_UP) {
            this.upHeld = false;
        } else if (key == KEY_DOWN) {
            this.downHeld = false;
        } else {
            return;
        }

        this.keyScrolling = this.upHeld || this.downHeld;
    }

    /**
     * Called first for a wheel notch of the screen.
     * <p>
     * The credits scroll one wheel step and stop automatic scrolling, consuming every notch.
     *
     * @param x    the pointer's interface x
     * @param y    the pointer's interface y
     * @param down {@code true} for a notch toward the user
     * @return {@code true} if the notch was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onWheel(float x, float y, boolean down) {
        float distance = down ? WHEEL_STEP : -WHEEL_STEP;

        this.scrollBy(distance);
        this.stopAutoScrolling();

        return true;
    }

    /**
     * Called by {@link #update} for screen-specific timers.
     * <p>
     * The credits advance their scrolling once per elapsed frame interval.
     *
     * @param time the time in milliseconds
     */
    @Override
    protected void onUpdate(long time) {
        if (this.lastFrameTime < 0) {
            this.lastFrameTime = time;
        }

        while (time - this.lastFrameTime >= FRAME_MILLISECONDS) {
            this.lastFrameTime += FRAME_MILLISECONDS;
            this.scrollFrame(this.lastFrameTime);
        }
    }

    /**
     * Advances key acceleration, automatic-scroll timing and the bounded scroll position.
     *
     * @param time the time of the frame in milliseconds
     */
    private void scrollFrame(long time) {
        if (this.upHeld) {
            this.scrollValue = this.scrollValue > -KEY_INITIAL_SPEED
                               ? -KEY_INITIAL_SPEED
                               : Math.max(-KEY_MAXIMUM_SPEED, this.scrollValue - KEY_ACCELERATION);
        } else if (this.downHeld) {
            this.scrollValue = this.scrollValue < KEY_INITIAL_SPEED
                               ? KEY_INITIAL_SPEED
                               : Math.min(KEY_MAXIMUM_SPEED, this.scrollValue + KEY_ACCELERATION);
        } else if (this.keyScrolling || this.autoScrolling) {
            this.scrollValue = AUTO_SPEED;
        } else {
            if (this.autoScrollAt < 0) {
                this.autoScrollAt = time + AUTO_SCROLL_DELAY;
            } else if (time >= this.autoScrollAt) {
                this.autoScrolling = true;
            }

            if (this.scrollValue > AUTO_SPEED) {
                this.scrollValue -= KEY_DECELERATION;
            } else if (this.scrollValue < -AUTO_SPEED) {
                this.scrollValue += KEY_DECELERATION;
            } else {
                this.scrollValue = this.autoScrolling ? AUTO_SPEED : 0;
            }
        }

        this.scrollBy(this.scrollValue);
    }

    /**
     * Moves the scroll position by a distance, bounded by the top and the end of the laid out credits.
     *
     * @param distance the distance in interface pixels, negative upward
     */
    private void scrollBy(float distance) {
        float maximum = Math.max(0, this.contentHeight - LIST_HEIGHT);

        this.scrollTop = Math.clamp(this.scrollTop + distance, 0, maximum);
    }

    /**
     * Stops automatic scrolling and restarts its delay.
     */
    private void stopAutoScrolling() {
        this.autoScrolling = false;
        this.autoScrollAt  = -1;
    }

    /**
     * Draws the screen and its popup.
     * <p>
     * The credits draw over an opaque black fill, and the clipped credit entries follow the screen; the entries are laid out on the first draw.
     *
     * @param canvas the glue canvas
     * @throws IOException if an image cannot be read
     */
    @Override
    public void draw(@NotNull GlueCanvas canvas) throws IOException {
        canvas.fillRectangle(0, 0, canvas.getInterfaceWidth(), GlueCanvas.INTERFACE_HEIGHT, GlueCanvas.OPAQUE_BLACK);
        super.draw(canvas);

        if (this.contentHeight < 0) {
            this.layOutEntries(canvas);
        }

        float left = this.getDialogLeft() + LIST_LEFT;
        float top = this.getDialogTop() + LIST_TOP;

        canvas.setClip(left, top, LIST_WIDTH, LIST_HEIGHT);

        for (Block block : this.blocks) {
            float y = top + block.top - this.scrollTop;
            float height = block.rule ? RULE_HOLDER : block.lines.size() * block.lineHeight;

            if (y + height < top || y > top + LIST_HEIGHT) {
                continue;
            }

            if (block.rule) {
                canvas.fillRectangle(left, y + (RULE_HOLDER - RULE_THICKNESS) / CENTER_DIVISOR, LIST_WIDTH, RULE_THICKNESS, TEXT_COLOR);

                continue;
            }

            for (int line = 0; line < block.lines.size(); line++) {
                WebPanelStyleUtils.drawText(canvas,
                                            block.fontSlot,
                                            block.lines.get(line),
                                            left, y + line * block.lineHeight,
                                            LIST_WIDTH, block.lineHeight,
                                            InterfaceText.CENTER | InterfaceText.TOP,
                                            TEXT_COLOR);
            }
        }

        canvas.setClip(0, 0, GlueCanvas.NO_CLIP_SIZE, GlueCanvas.NO_CLIP_SIZE);
    }

    /**
     * Lays the entries out in the page's block flow: the lines of each entry wrapped at the list width, the vertical margins of neighboring entries
     * (and of empty ones) collapsed into the larger.
     *
     * @param canvas the glue canvas whose fonts measure the lines
     */
    private void layOutEntries(@NotNull GlueCanvas canvas) {
        float y = 0;
        float margin = 0;

        for (int index = 0; index < this.names.size(); index++) {
            int style = this.styles.get(index);
            String name = this.names.get(index);
            float topMargin;
            float bottomMargin;
            int fontSlot;
            float lineHeight;

            switch (style) {
                case TITLE_STYLE -> {
                    topMargin    = TITLE_MARGIN;
                    bottomMargin = TITLE_MARGIN;
                    fontSlot     = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY, TITLE_SIZE, 0);
                    lineHeight   = canvas.getLineHeight(fontSlot);
                }
                case SECTION_STYLE -> {
                    topMargin    = 0;
                    bottomMargin = SECTION_BOTTOM;
                    fontSlot     = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY, SECTION_SIZE, SECTION_LINE);
                    lineHeight   = SECTION_LINE;
                }
                case ROLE_STYLE -> {
                    topMargin    = ROLE_TOP;
                    bottomMargin = ROLE_BOTTOM;
                    fontSlot     = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY, ROLE_SIZE, ROLE_LINE);
                    lineHeight   = ROLE_LINE;
                }
                case NAME_STYLE -> {
                    topMargin    = 0;
                    bottomMargin = 0;
                    fontSlot     = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY, NAME_SIZE, NAME_LINE);
                    lineHeight   = NAME_LINE;
                }
                case GAP_STYLE -> {
                    topMargin    = 0;
                    bottomMargin = GAP_BOTTOM;
                    fontSlot     = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY, GAP_SIZE, GAP_LINE);
                    lineHeight   = GAP_LINE;
                }
                case RULE_STYLE -> {
                    y += margin;
                    margin = 0;
                    this.blocks.add(new Block(List.of(), 0, 0, y, true));
                    y += RULE_HOLDER;

                    continue;
                }
                default -> {
                    continue;
                }
            }

            margin = Math.max(margin, topMargin);

            if (name.isEmpty()) {
                margin = Math.max(margin, bottomMargin);

                continue;
            }

            y += margin;

            List<String> lines = InterfaceText.wrap(canvas.getFont(fontSlot), name, LIST_WIDTH);

            this.blocks.add(new Block(lines, fontSlot, lineHeight, y, false));
            y += lines.size() * lineHeight;
            margin = bottomMargin;
        }

        this.contentHeight = y + margin;
    }

    private void end() {
        if (this.ended) {
            return;
        }

        this.ended = true;
        this.finished.run();
    }
}
