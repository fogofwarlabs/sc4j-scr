package org.fogofwarlabs.sc4j.scr.client.glue.score;

import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.glue.*;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.GlueSavePopup;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.DialogPainter;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.client.ui.text.StatText;
import org.fogofwarlabs.sc4j.scr.game.presentation.TimerTextUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * Shows end-of-game scores over the race and outcome palette.
 * <p>
 * The panel slides in after its delay, selects Overview when locked and counts the values. Tabs select score
 * categories. OK and Escape stop sounds and slide out; Watch Replay also starts the recorded game.
 * The screen plays the race victory or defeat music and offers replay actions for the completed session.
 */
public final class GlueScoreScreen extends GlueScreen {
    private static final @NotNull String DIALOG = "gluScore";

    private static final int PANEL = 1;
    private static final int TITLE = 2;
    private static final int TOTAL_SCORE = 8;
    private static final int ELAPSED_TIME = 9;
    private static final int FIRST_HEADER = 10;
    private static final int FIRST_ROW_BOX = 13;
    private static final int ROW_CONTROLS = 6;
    private static final int LAST_ROW_CONTROL = FIRST_ROW_BOX + ROW_CONTROLS * ScoreScreen.MAXIMUM_ROWS - 1;
    private static final int ACTIONS_PER_MINUTE_HEADER = 62;
    /**
     * The parts of a row: the inset, the name, the value columns from {@link #FIRST_VALUE_PART} and the score box last.
     */
    private static final int INSET_PART = 0;
    private static final int NAME_PART = 1;
    private static final int FIRST_VALUE_PART = 2;
    private static final int SCORE_PART = ROW_CONTROLS - 1;
    /**
     * The panel slides in from the left after {@code 500} ms.
     */
    private static final int @NotNull [] SWISH = {PANEL, 0};
    private static final int SWISH_DELAY_MILLISECONDS = 500;

    /**
     * The vertical offset of an unchecked tab's notch cover.
     */
    private static final int TAB_COVER_OFFSET_Y = 28;
    /**
     * The right margin of the last tab's cover with HD graphics.
     */
    private static final int LAST_TAB_COVER_RIGHT_MARGIN = 5;
    /**
     * The row portrait position relative to its name control.
     */
    private static final int ICON_OFFSET_X = 8;
    private static final int ICON_OFFSET_Y = 20;
    private static final int NAME_CLIP_RIGHT = 156;
    private static final int FORCE_NAME_OFFSET_X = 40;
    private static final int ACTIONS_PER_MINUTE_LEFT = 158;
    private static final int ACTIONS_PER_MINUTE_RIGHT = 186;
    private static final int ACTIONS_PER_MINUTE_OFFSET_Y = 4;
    /**
     * Offsets of value and score text within their controls.
     */
    private static final int VALUE_TEXT_OFFSET_Y = 2;
    private static final int SCORE_TEXT_OFFSET_X = 4;
    /**
     * {@code iScore} frames: race portraits from {@code 3}, then six frames per player color from {@code 6}.
     */
    private static final int RACE_ICON_FRAME = 3;
    private static final int BAR_SOLID_FRAME = 6;
    private static final int BAR_OUTLINE_FRAME = 9;
    private static final int FRAMES_PER_COLOR = 6;
    /**
     * The gray frame set (color {@code 6}: frames {@code 42} solid, {@code 45} outline) that the extended-color path tints.
     */
    private static final int NEUTRAL_COLOR = 6;
    /**
     * The color multiplier for tinted extended player colors.
     */
    private static final float TINT_FACTOR = 3;
    /**
     * The dialog frame providing the center tile width of score bars.
     */
    private static final int BAR_TILE_FRAME = 133;
    /**
     * The number of player colors read from the minimap color image.
     */
    private static final int MINIMAP_COLORS = 12;
    private static final int OPAQUE_WHITE = 0xFFFFFFFF;
    private static final int MAXIMUM_RACE_ICON = 2;

    /**
     * The images of the palette and score folders.
     */
    private static final @NotNull String TEXT_FONT_FILE = "/tfont.pcx";
    private static final @NotNull String MINIMAP_FILE = "/tminimap.pcx";
    private static final @NotNull String PANEL_FILE = "/pmain.DDS";
    private static final @NotNull String UNCHECKED_TAB_FILE = "/untab.DDS";
    private static final @NotNull String ROW_SHEET_FILE = "/iScore.dds.grp";
    private static final @NotNull String ROW_INSET_FILE = "/pinset.DDS";
    private static final @NotNull String SCORE_BOX_FILE = "/scorebox.DDS";

    /**
     * The {@code gluAll} text keys of the screen.
     */
    private static final @NotNull String VICTORY_TITLE_KEY = "scoreWON";
    private static final @NotNull String DEFEAT_TITLE_KEY = "scoreLOST";
    private static final @NotNull String ELAPSED_TIME_KEY = "scoreTIME_s";
    private static final @NotNull String ACTIONS_PER_MINUTE_TITLE_KEY = "scoreAPMTitle";
    private static final @NotNull String REPLAY_SAVED_KEY = "scoreReplaySavedStr";
    private static final @NotNull String TIME_PLACEHOLDER = "%s";

    /**
     * @param control a control id
     * @return {@code true} if the control is one of the score tabs, {@code false} otherwise
     */
    private static boolean isTabControl(int control) {
        return control >= ScoreScreen.FIRST_TAB_CONTROL && control < ScoreScreen.FIRST_TAB_CONTROL + ScoreScreen.TABS;
    }

    private final @NotNull ScoreScreen score;
    private final GlueResume.@NotNull ReplaySaver replaySaver;
    private final @NotNull Runnable closed;
    private final @NotNull Runnable replayWatcher;

    private final int @NotNull [] playerColors;
    private boolean tabSelected;

    private @Nullable ScrControl saveReplayButton;
    private @NotNull ScrControl @Nullable [] headers;
    private @NotNull ScrControl @Nullable [] rowControls;

    /**
     * Creates a {@link GlueScoreScreen} instance.
     *
     * @param score         the score data
     * @param replaySaver   writes the game's replay under the name Save Replay's dialog chose
     * @param closed        what follows the slide-out of OK and Esc
     * @param replayWatcher what follows the slide-out of Watch Replay: the game's replay starts
     */
    public GlueScoreScreen(@NotNull ScoreScreen score,
                           GlueResume.@NotNull ReplaySaver replaySaver,
                           @NotNull Runnable closed,
                           @NotNull Runnable replayWatcher) {
        super(DIALOG, score.getPaletteFolder(), getPaletteBackground(score.getPaletteFolder()));

        this.score         = score;
        this.replaySaver   = replaySaver;
        this.closed        = closed;
        this.replayWatcher = replayWatcher;

        this.playerColors = new int[MINIMAP_COLORS];
        this.tabSelected  = false;

        this.saveReplayButton = null;
        this.headers          = null;
        this.rowControls      = null;
    }

    /**
     * @param playerColor a player color index
     * @return the packed color of the player color, or opaque white for a color without a minimap color
     */
    private int getPlayerColor(int playerColor) {
        return playerColor >= 0 && playerColor < MINIMAP_COLORS ? this.playerColors[playerColor] : OPAQUE_WHITE;
    }


    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * Initializes the score transitions, the music, the counting sounds, the player colors and the score controls.
     *
     * @param glueContext the shared services
     * @throws IOException if an asset cannot be read
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) throws IOException {
        this.saveReplayButton = this.bindControl(ScoreScreen.SAVE_REPLAY_CONTROL);
        this.headers          = this.bindControls(FIRST_HEADER, ScoreScreen.COLUMNS);
        this.rowControls      = this.bindControls(FIRST_ROW_BOX, ROW_CONTROLS * ScoreScreen.MAXIMUM_ROWS);

        this.setSwish(SWISH, SWISH_DELAY_MILLISECONDS);
        glueContext.getAudio().playMusic(this.score.getMusicTrack());
        this.score.setTimers(glueContext.getTimers());
        this.score.setFillListener(new ScoreScreen.FillListener() {
            /**
             * Called when counting starts for the selected tab.
             * <p>
             * Stops the glue sounds and starts the counting sound when counting audio should play.
             *
             * @param counting whether counting audio should play
             */
            @Override
            public void onFillStarted(boolean counting) {
                glueContext.getAudio().stopSounds();

                if (counting) {
                    glueContext.getAudio().play(GlueAudio.Sound.SCORE_FILL);
                }
            }

            /**
             * Called when every displayed counter has reached its target.
             * <p>
             * Stops the counting sounds and plays the completed-fill sound.
             */
            @Override
            public void onFillEnded() {
                glueContext.getAudio().stopSounds();
                glueContext.getAudio().play(GlueAudio.Sound.SCORE_FILL_END);
            }
        });

        GluePalette.IndexedImage font = GluePalette.IndexedImage.parse(
                glueContext.getCanvas().getStorage().readFile(this.getPaletteFolder() + TEXT_FONT_FILE)
        );
        GluePalette.IndexedImage minimap = GluePalette.IndexedImage.parse(
                glueContext.getCanvas().getStorage().readFile(this.score.getFolder() + MINIMAP_FILE)
        );

        for (int color = 0; color < MINIMAP_COLORS; color++) {
            this.playerColors[color] = font.getColor(minimap.getPixel(color));
        }

        GluText gluText = glueContext.getGluText();

        ScrControl title = this.bindControl(TITLE);

        title.setReplacedText(gluText.get(this.score.isVictory() ? VICTORY_TITLE_KEY : DEFEAT_TITLE_KEY));
        title.setHighlighted(true);
        this.bindControl(TOTAL_SCORE).setHighlighted(true);

        String elapsedTimeFormat = gluText.get(ELAPSED_TIME_KEY);
        String elapsedTime = TimerTextUtils.formatElapsedTime((int) this.score.getElapsedSeconds());

        this.bindControl(ELAPSED_TIME).setReplacedText(elapsedTimeFormat.replace(TIME_PLACEHOLDER, elapsedTime));
        this.bindControl(ACTIONS_PER_MINUTE_HEADER).setReplacedText(this.score.hasActionsPerMinute() ? gluText.get(ACTIONS_PER_MINUTE_TITLE_KEY) : "");

        for (int column = 0; column < ScoreScreen.COLUMNS; column++) {
            requireBound(this.headers)[column].setReplacedText("");
        }

        this.bindControl(ScoreScreen.WATCH_REPLAY_CONTROL).setEnabled(this.score.isEnabled(ScoreScreen.WATCH_REPLAY_CONTROL));
        this.updateSaveButton();
        this.updateRows();
    }

    private void updateSaveButton() {
        boolean saved = this.score.isReplaySaved();

        requireBound(this.saveReplayButton).setEnabled(this.score.isEnabled(ScoreScreen.SAVE_REPLAY_CONTROL));

        if (saved) {
            // The saved-replay label also supplies the button's hotkey.
            requireBound(this.saveReplayButton).setReplacedText(this.getContext().getGluText().get(REPLAY_SAVED_KEY));
        }
    }

    /**
     * Shows controls for active rows, hides unused rows and sets column headers.
     */
    private void updateRows() {
        int rows = this.score.getShownRows().size();

        for (int row = 0; row < ScoreScreen.MAXIMUM_ROWS; row++) {
            for (int control = 0; control < ROW_CONTROLS; control++) {
                requireBound(this.rowControls)[ROW_CONTROLS * row + control].setVisible(row < rows);
            }
        }

        ScoreScreen.Tab tab = this.score.getTab();

        if (tab != null) {
            for (int column = 0; column < ScoreScreen.COLUMNS; column++) {
                requireBound(this.headers)[column].setReplacedText(this.getContext().getGluText().get(tab.getHeaders()[column]));
            }
        }
    }

    /**
     * Called by {@link #update} for screen-specific timers.
     * <p>
     * Selects Overview once the panel transition makes the screen interactive.
     *
     * @param time the time in milliseconds
     */
    @Override
    protected void onUpdate(long time) {
        if (!this.tabSelected && this.isInteractive()) {
            this.tabSelected = true;

            ScoreScreen.Tab tab = ScoreScreen.Tab.OVERVIEW;

            this.score.selectTab(tab, this.getTime());
            this.updateRows();
        }
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * Selects tabs or handles closing, replay watching and replay saving.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
        if (isTabControl(control)) {
            this.playMouseOver();

            ScoreScreen.Tab tab = ScoreScreen.Tab.values()[control - ScoreScreen.FIRST_TAB_CONTROL];

            this.score.selectTab(tab, this.getTime());
            this.updateRows();
        } else if (control == ScoreScreen.OK_CONTROL) {
            this.leaveTo(this.getContext(), this.closed);
        } else if (control == ScoreScreen.WATCH_REPLAY_CONTROL && this.score.isEnabled(control)) {
            // Complete the transition before invoking the recorded-game watcher.
            this.leaveTo(this.getContext(), this.replayWatcher);
        } else if (control == ScoreScreen.SAVE_REPLAY_CONTROL && this.score.isEnabled(control)) {
            // Open the replay-saving popup over the score screen.
            this.showPopup(new GlueSavePopup(this.getPaletteFolder(),
                                             GluePopup.NO_CONSOLE_RACE,
                                             this.replaySaver,
                                             saved -> {
                                                 if (saved) {
                                                     this.score.setReplaySaved();
                                                     this.updateSaveButton();
                                                 }
                                             }));
        }
    }

    /**
     * Called first for every key of the screen (after an open popup): a screen without controls, such as a cinematic, takes its keys here.
     * <p>
     * Escape closes an interactive score screen; other keys are not consumed here.
     *
     * @param key the key
     * @return {@code true} if the key was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onKey(char key) {
        if (key != KEY_ESCAPE || !this.isInteractive()) {
            return false;
        }

        this.leaveTo(this.getContext(), this.closed);

        return true;
    }

    /**
     * Stops the sounds and slides the screen out, then stops the music and runs what follows.
     *
     * @param glueContext the glue context
     * @param next        what follows the slide-out
     */
    private void leaveTo(@NotNull GlueContext glueContext, @NotNull Runnable next) {
        glueContext.getAudio().stopSounds();
        this.leave(() -> {
            glueContext.getAudio().stopMusic();
            next.run();
        });
    }

    /**
     * Draws one control.
     * <p>
     * The panel, the tabs and the row controls are drawn by this screen.
     *
     * @param canvas  the glue canvas
     * @param control the control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IOException if an image cannot be read
     */
    @Override
    protected void drawControl(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        int identifier = control.getId();

        if (identifier == PANEL) {
            // The panel control is anchored at its top right corner: place the panel image at the anchored left edge.
            GlueCanvas.Image main = canvas.getImage(this.score.getFolder() + PANEL_FILE);

            canvas.drawImage(main, getImageLeft(canvas, control, main, left), top);
        } else if (isTabControl(identifier)) {
            this.drawTab(canvas, control, left, top);
        } else if (identifier >= FIRST_ROW_BOX && identifier <= LAST_ROW_CONTROL) {
            this.drawRowControl(canvas, control, left, top);
        } else {
            super.drawControl(canvas, control, left, top);
        }
    }

    private void drawTab(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        int index = control.getId() - ScoreScreen.FIRST_TAB_CONTROL;

        if (ScoreScreen.Tab.values()[index] != this.score.getTab()) {
            GlueCanvas.Image uncheckedTab = canvas.getImage(this.score.getFolder() + UNCHECKED_TAB_FILE);
            boolean last = index == ScoreScreen.TABS - 1;
            float clipRight = last && canvas.isHighDefinition() ? canvas.getInterfaceWidth() - LAST_TAB_COVER_RIGHT_MARGIN : Float.POSITIVE_INFINITY;

            canvas.drawImageColumns(uncheckedTab, left, top + TAB_COVER_OFFSET_Y, Float.NEGATIVE_INFINITY, clipRight);
        }

        // Draw hovered tabs with the highlight code; hotkey letters retain their embedded color code.
        GluePalette palette = this.getPalette();
        int code = control.getId() == this.getHoveredControl() ? DialogPainter.CODE_HIGHLIGHT : DialogPainter.CODE_BUTTON;

        canvas.drawText(canvas.getControlFontSlot(control),
                        this.getDrawnString(control),
                        left, top,
                        control.getWidth(), control.getHeight(),
                        GlueCanvas.FORMAT_CENTER | GlueCanvas.FORMAT_MIDDLE,
                        palette.getCodeColor(code),
                        palette);
    }

    private void drawRowControl(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        int offset = control.getId() - FIRST_ROW_BOX;
        int shownRow = offset / ROW_CONTROLS;
        int part = offset % ROW_CONTROLS;
        List<ScoreScreen.Row> rows = this.score.getShownRows();

        if (shownRow >= rows.size()) {
            return;
        }

        ScoreScreen.Row row = rows.get(shownRow);
        GluePalette palette = this.getPalette();
        String sheet = this.score.getFolder() + ROW_SHEET_FILE;

        switch (part) {
            case INSET_PART -> canvas.drawImage(canvas.getImage(this.score.getFolder() + ROW_INSET_FILE), left, top);
            case NAME_PART -> this.drawName(canvas, palette, sheet, row, control, left, top);
            case SCORE_PART -> {
                // Draw the score box before its counted number.
                canvas.drawImage(canvas.getImage(this.score.getFolder() + SCORE_BOX_FILE), left, top);

                ScoreScreen.Counter counter = this.score.getCounter(shownRow, ScoreScreen.COLUMNS);

                if (counter.isShown()) {
                    canvas.drawText(canvas.getControlFontSlot(control),
                                    Long.toString(counter.getValue()),
                                    left + SCORE_TEXT_OFFSET_X, top + VALUE_TEXT_OFFSET_Y,
                                    control.getWidth() - SCORE_TEXT_OFFSET_X, control.getHeight(),
                                    GlueCanvas.FORMAT_CENTER | GlueCanvas.FORMAT_TOP,
                                    palette.getCodeColor(DialogPainter.CODE_TEXT),
                                    palette);
                }
            }
            default -> this.drawValue(canvas, palette, sheet, row, shownRow, part - FIRST_VALUE_PART, control, left, top);
        }
    }

    /**
     * Draws the outline, counted solid share and text of a score value.
     *
     * @param canvas   the glue canvas
     * @param palette  the screen palette
     * @param sheet    the row sheet of the score folder
     * @param row      the shown row
     * @param shownRow the index of the shown row
     * @param column   the value column
     * @param control  the value control
     * @param left     its left edge in interface pixels
     * @param top      its top edge in interface pixels
     * @throws IOException if an image cannot be read
     */
    private void drawValue(@NotNull GlueCanvas canvas,
                           @NotNull GluePalette palette,
                           @NotNull String sheet,
                           ScoreScreen.@NotNull Row row,
                           int shownRow, int column,
                           @NotNull DialogControl control,
                           float left, float top) throws IOException {
        // A game that keeps its custom colors tints the gray bars with the player's custom color.
        boolean tinted = row.isCustomColorShown();
        int colorIndex = tinted ? NEUTRAL_COLOR : row.getColor();
        int tint = tinted ? row.getCustomColor() : 0;

        ScoreScreen.Counter counter = this.score.getCounter(shownRow, column);
        int full = control.getWidth();
        long maximum = this.score.getColumnMaximum(column);
        float length = maximum == 0 ? 0 : (float) Math.min(full, full * counter.getExactValue() / maximum);

        this.drawBar(canvas, sheet, BAR_OUTLINE_FRAME + FRAMES_PER_COLOR * colorIndex, tinted, tint, left, top, full);

        if (length > 0) {
            this.drawBar(canvas, sheet, BAR_SOLID_FRAME + FRAMES_PER_COLOR * colorIndex, tinted, tint, left, top, length);
        }

        if (counter.isShown()) {
            canvas.drawText(canvas.getControlFontSlot(control),
                            Long.toString(counter.getValue()),
                            left, top + VALUE_TEXT_OFFSET_Y,
                            control.getWidth(), control.getHeight(),
                            GlueCanvas.FORMAT_CENTER | GlueCanvas.FORMAT_TOP,
                            palette.getCodeColor(DialogPainter.CODE_TEXT),
                            palette);
        }
    }

    /**
     * Draws a three-slice score bar with optional extended-player-color tinting.
     *
     * @param canvas the glue canvas
     * @param sheet  the row sheet of the score folder
     * @param frame  the first frame of the bar's three slices
     * @param tinted whether the slices are tinted with the player color
     * @param tint   the packed player color of tinted slices
     * @param x      the left edge in interface pixels
     * @param y      the top edge in interface pixels
     * @param length the bar length in interface pixels
     * @throws IOException if an image cannot be read
     */
    private void drawBar(@NotNull GlueCanvas canvas, @NotNull String sheet, int frame, boolean tinted, int tint, float x, float y, float length) throws IOException {
        int tile = canvas.getMetricWidth(DIALOG_SHEET, BAR_TILE_FRAME);

        if (!tinted) {
            canvas.drawBar(sheet, frame, x, y, length, tile);

            return;
        }

        canvas.drawBar(canvas.getTintedSheetFrame(sheet, frame, tint, TINT_FACTOR),
                       canvas.getTintedSheetFrame(sheet, frame + 1, tint, TINT_FACTOR),
                       canvas.getTintedSheetFrame(sheet, frame + (DialogPainter.SLICE_COLUMNS - 1), tint, TINT_FACTOR),
                       x, y,
                       length,
                       tile);
    }

    /**
     * Draws the race portrait, player-color outline, clipped name, force name and actions per minute.
     *
     * @param canvas  the glue canvas
     * @param palette the screen palette
     * @param sheet   the row sheet of the score folder
     * @param row     the shown row
     * @param name    the name control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IOException if an image cannot be read
     */
    private void drawName(@NotNull GlueCanvas canvas,
                          @NotNull GluePalette palette,
                          @NotNull String sheet,
                          ScoreScreen.@NotNull Row row,
                          @NotNull DialogControl name,
                          float left, float top) throws IOException {
        int iconFrame = RACE_ICON_FRAME + Math.clamp(row.getRace(), 0, MAXIMUM_RACE_ICON);
        float iconX = left + ICON_OFFSET_X;
        float iconY = top + ICON_OFFSET_Y;
        GlueCanvas.Image icon = canvas.getSheetFrame(sheet, iconFrame);

        canvas.drawFrame(icon, iconX, iconY);

        float iconWidth = icon.getWidth();
        float iconHeight = icon.getHeight();
        int outlineColor = this.getPlayerColor(row.getColor());

        canvas.drawRectangle(iconX, iconY, iconWidth, iconHeight, GlueCanvas.OUTLINE_THICKNESS, outlineColor);

        int nameCode = row.getPlayer() == this.score.getLocalPlayer() ? DialogPainter.CODE_WHITE : DialogPainter.CODE_TEXT;
        float dialogLeft = this.getDialogLeft();

        canvas.drawText(canvas.getControlFontSlot(name),
                        StatText.stripColors(row.getName()),
                        left, top,
                        dialogLeft + NAME_CLIP_RIGHT + 1 - left, name.getHeight(),
                        GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                        palette.getCodeColor(nameCode),
                        palette);

        if (!row.getForceName().isEmpty()) {
            // Place the force name to the right of the race icon.
            float forceLeft = left + FORCE_NAME_OFFSET_X + icon.getWidth();
            String forceName = this.score.isMapForceName(row.getForceName()) ? this.getContext().presentMapText(row.getForceName())
                                                                             : row.getForceName();

            canvas.drawText(GlueCanvas.SMALL_FONT,
                            forceName,
                            forceLeft, iconY,
                            left + name.getWidth() - forceLeft, top + name.getHeight() - iconY,
                            GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                            palette.getCodeColor(DialogPainter.CODE_WHITE),
                            palette);
        }

        int actionsPerMinute = this.score.getActionsPerMinute(row.getPlayer());

        if (this.score.hasActionsPerMinute() && actionsPerMinute != 0) {
            // Center the actions per minute in their column of the dialog, in the small font and the text color.
            canvas.drawText(GlueCanvas.SMALL_FONT,
                            Integer.toString(actionsPerMinute),
                            dialogLeft + ACTIONS_PER_MINUTE_LEFT, top + ACTIONS_PER_MINUTE_OFFSET_Y,
                            ACTIONS_PER_MINUTE_RIGHT - ACTIONS_PER_MINUTE_LEFT + 1, name.getHeight() - ACTIONS_PER_MINUTE_OFFSET_Y,
                            GlueCanvas.FORMAT_CENTER | GlueCanvas.FORMAT_TOP,
                            palette.getCodeColor(DialogPainter.CODE_TEXT),
                            palette);
        }
    }


    /**
     * Releases the videos and the popup.
     * <p>
     * Detaches the score fill timers before releasing the inherited screen resources.
     */
    @Override
    public void close() {
        this.score.setTimers(null);
        super.close();
    }
}
