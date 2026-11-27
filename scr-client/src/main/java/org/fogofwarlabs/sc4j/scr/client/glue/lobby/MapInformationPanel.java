package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueContext;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.WebPanelStyleUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.client.ui.text.StatText;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.presentation.TimerTextUtils;
import org.fogofwarlabs.sc4j.scr.render.ui.TrueTypeText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws map-browser, LAN-lobby and replay information with fitted previews, wrapped headings and summary rows.
 */
public final class MapInformationPanel {
    private static final int WHITE = 0xFFFFFFFF;
    /**
     * Halves the free space around an item, which centers the item in its box or row.
     */
    private static final int CENTER_DIVISOR = 2;
    /**
     * The sides an inset applies to: left and right.
     */
    private static final int SIDE_COUNT = 2;
    /**
     * The image box, the margin under its holder and the image border.
     */
    private static final float IMAGE_SIZE = 105;
    private static final float IMAGE_MARGIN = 5;
    private static final float IMAGE_BORDER_WIDTH = 0.5f;
    /**
     * The centered placeholder font for an unavailable preview.
     */
    private static final float IMAGE_TEXT_SIZE = 9;
    private static final int IMAGE_TEXT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY,
                                                                         IMAGE_TEXT_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    /**
     * The gap between a summary label and its value.
     */
    private static final float LABEL_PADDING = 5;
    /**
     * The summary row columns sharing the row width: the label and the value.
     */
    private static final int ROW_COLUMN_COUNT = 2;
    /**
     * The indexes of the label and the value in a summary row or a lobby block.
     */
    private static final int LABEL_INDEX = 0;
    private static final int VALUE_INDEX = 1;

    /**
     * The browser panel insets.
     */
    private static final float BROWSER_PADDING = 4;
    private static final float BROWSER_PADDING_TOP = 8;
    /**
     * The browser heading font and maximum line count.
     */
    private static final float BROWSER_HEADING_SIZE = 10;
    private static final float BROWSER_HEADING_LINE = 12;
    private static final int BROWSER_HEADING = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY,
                                                                              BROWSER_HEADING_SIZE, BROWSER_HEADING_LINE);
    private static final int BROWSER_HEADING_LINES = 2;
    /**
     * The description inset, font, line height and maximum line count.
     */
    private static final float DESCRIPTION_INSET = 2.5f;
    private static final float DESCRIPTION_SIZE = 10;
    private static final float DESCRIPTION_LINE = 12;
    private static final int DESCRIPTION = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY, DESCRIPTION_SIZE, DESCRIPTION_LINE);
    private static final int DESCRIPTION_LINES = 8;
    /**
     * The maximum description line count when no preview is drawn.
     */
    private static final int DESCRIPTION_LINES_NO_IMAGE = 12;
    /**
     * The space above the description: two description insets.
     */
    private static final float DESCRIPTION_TOP_GAP = 2 * DESCRIPTION_INSET;
    /**
     * The summary row font.
     */
    private static final float BROWSER_ROW_SIZE = 9;
    private static final int BROWSER_ROW = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY,
                                                                          BROWSER_ROW_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);

    /**
     * The lobby panel height and inset.
     */
    private static final float LOBBY_HEIGHT = 272;
    private static final float LOBBY_PADDING = 4;
    /**
     * The gap between centered lobby labels.
     */
    private static final float CENTER_MARGIN = 4;
    /**
     * The lobby text font: the labels, the summary rows and the countdown.
     */
    private static final float LOBBY_TEXT_SIZE = 8;
    private static final int LOBBY_TEXT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY,
                                                                         LOBBY_TEXT_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    /**
     * The centered lobby value font and line height.
     */
    private static final float LOBBY_VALUE_SIZE = 9;
    private static final float LOBBY_VALUE_LINE = 10;
    private static final int LOBBY_VALUE = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY, LOBBY_VALUE_SIZE, LOBBY_VALUE_LINE);
    /**
     * The gap before the lobby summary and between its rows.
     */
    private static final float SUMMARY_MARGIN = 8;
    private static final float ROW_MARGIN = 1;
    /**
     * The gap below the countdown row.
     */
    private static final float COUNTDOWN_MARGIN = 1;

    private static final @NotNull String SUGGESTED_PLAYERS = "Suggested Players: ";
    /**
     * The minimum suggested player count for a local map.
     */
    private static final int MINIMUM_PLAYERS = 1;
    private static final int PLAYER_COUNT_PREFIX_LENGTH = 3;

    /**
     * The replay player race identifiers selecting the race labels and colors.
     */
    private static final int ZERG_RACE_IDENTIFIER = 0;
    private static final int TERRAN_RACE_IDENTIFIER = 1;
    private static final int PROTOSS_RACE_IDENTIFIER = 2;
    /**
     * The player race label colors, with white reserved for a random race.
     */
    private static final int TERRAN_COLOR = 0x80D4FFFF;
    private static final int ZERG_COLOR = 0xD480FFFF;
    private static final int PROTOSS_COLOR = 0xFFF066FF;
    /**
     * The replay header font, inset, line height and maximum line count.
     */
    private static final float REPLAY_HEADER_SIZE = 10;
    private static final float REPLAY_HEADER_LINE = 12;
    private static final int REPLAY_HEADER = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY, REPLAY_HEADER_SIZE, REPLAY_HEADER_LINE);
    private static final int REPLAY_HEADER_LINES = 2;
    private static final float REPLAY_TEXT_LEFT = 3;
    private static final float REPLAY_HEADER_GAP = 5;
    /**
     * The replay summary block height.
     */
    private static final float REPLAY_INFORMATION_HEIGHT = 120;
    /**
     * The replay summary label and value fonts.
     */
    private static final float REPLAY_LABEL_SIZE = 9;
    private static final float REPLAY_LABEL_LINE = 12;
    private static final int REPLAY_LABEL = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY, REPLAY_LABEL_SIZE, REPLAY_LABEL_LINE);
    private static final float REPLAY_VALUE_SIZE = 9;
    private static final int REPLAY_VALUE = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY,
                                                                           REPLAY_VALUE_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    /**
     * The width a replay label's text box adds to the label's measured width.
     */
    private static final int REPLAY_LABEL_WIDTH_PADDING = 1;
    /**
     * The gap below a replay summary row.
     */
    private static final float REPLAY_ROW_GAP = 2;
    /**
     * The gap below the player list heading.
     */
    private static final float PLAYER_HEADER_GAP = 3;
    /**
     * The width of each player name and race column.
     */
    private static final float PLAYER_NAME_WIDTH = 100;
    /**
     * The replay file-name box dimensions.
     */
    private static final float FILE_NAME_HEIGHT = 60;
    private static final float FILE_NAME_WIDTH = 140;

    /**
     * A preview image fitted into the image box, or none.
     */
    public interface Preview {
        /**
         * @return the preview width in pixels
         */
        int getWidth();

        /**
         * @return the preview height in pixels
         */
        int getHeight();

        /**
         * @return the preview texture
         */
        int getTexture();
    }

    /**
     * Draws the selected map preview, heading, description and summary rows in the browser panel. The scenario name and description show
     * in the text language ({@link GlueContext#resolveMapText}).
     *
     * @param canvas  the glue canvas (its palette gives the color codes of the heading)
     * @param context the glue context, which gives the {@code gluAll} strings
     * @param map     the selected map's information
     * @param preview the preview image, or {@code null} when there is none
     * @param left    the control left
     * @param top     the control top
     * @param width   the control width
     * @param height  the control height
     */
    public static void draw(@NotNull GlueCanvas canvas,
                            @NotNull GlueContext context,
                            @NotNull MapInformation map,
                            @Nullable Preview preview,
                            float left, float top,
                            float width, float height) {
        GluText strings = context.getGluText();
        float innerLeft = left + BROWSER_PADDING;
        float innerWidth = width - SIDE_COUNT * BROWSER_PADDING;
        float y = top + BROWSER_PADDING_TOP;
        boolean image = drawImageBox(canvas, strings, preview, left + (width - IMAGE_SIZE) / CENTER_DIVISOR, y);

        y += IMAGE_SIZE + IMAGE_MARGIN;

        String heading = map.getName();
        String playerPrefix = "(" + map.getPlayableSlots() + ")";

        if (heading.contains(playerPrefix)) {
            heading = heading.length() > PLAYER_COUNT_PREFIX_LENGTH ? heading.substring(PLAYER_COUNT_PREFIX_LENGTH) : "";
        }

        heading = context.resolveMapText(heading);

        int headingLines = canvas.measure(BROWSER_HEADING, StatText.stripColors(heading)) > innerWidth ? BROWSER_HEADING_LINES : 1;

        WebPanelStyleUtils.drawText(canvas,
                                    BROWSER_HEADING,
                                    heading,
                                    innerLeft, y,
                                    innerWidth, BROWSER_HEADING_LINE * headingLines,
                                    GlueCanvas.FORMAT_CENTER | GlueCanvas.FORMAT_TOP,
                                    WHITE);
        y += BROWSER_HEADING_LINE * headingLines;

        String description = map.getDescription();
        String suggested = MINIMUM_PLAYERS + "-" + map.getPlayableSlots();
        int lineEnd = description.indexOf('\n');

        if (lineEnd > 0 && description.startsWith(SUGGESTED_PLAYERS)) {
            suggested = description.substring(SUGGESTED_PLAYERS.length(), lineEnd).strip();
            // The empty line after it goes too, CR LF or LF (a lone CR left over would draw as a blank first line).
            description = description.substring(lineEnd + 1).replaceFirst("^\r?\n", "");
        }

        description = context.resolveMapText(description);

        List<String[]> rows = new ArrayList<>();

        rows.add(new String[]{strings.get("infoPane_size"), formatMapSize(map.getWidth(), map.getHeight())});

        int tileset = map.getTileset();

        rows.add(new String[]{strings.get("infoPane_tileset"), strings.get("gameMapERA" + (tileset == 0 ? "" : Integer.toString(tileset)))});
        rows.add(new String[]{strings.get("infoPane_suggestedPlayers"), suggested});

        float summaryTop = top + height - BROWSER_PADDING - getRowsHeight(canvas, BROWSER_ROW, rows, 0);

        y += DESCRIPTION_TOP_GAP;

        int lines = Math.min(image ? DESCRIPTION_LINES : DESCRIPTION_LINES_NO_IMAGE, (int) ((summaryTop - y) / DESCRIPTION_LINE));

        if (lines > 0) {
            WebPanelStyleUtils.drawText(canvas,
                                        DESCRIPTION,
                                        description.replace("\r\n", "\n").replace('\r', '\n'),
                                        innerLeft + DESCRIPTION_INSET, y,
                                        innerWidth - SIDE_COUNT * DESCRIPTION_INSET, lines * DESCRIPTION_LINE,
                                        GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                        WebPanelStyleUtils.TEXT);
        }

        drawRows(canvas, BROWSER_ROW, rows, 0, innerLeft, summaryTop, innerWidth);
    }

    /**
     * Draws lobby game and map details, fitting summary rows above the optional countdown.
     *
     * @param canvas    the glue canvas
     * @param strings   the {@code gluAll} strings
     * @param preview   the map preview, or {@code null}
     * @param gameName  the game name
     * @param mapName   the map name, with its color codes
     * @param gameType  the game type's name
     * @param rows      the summary rows, each a label (without the colon) and its value
     * @param countdown the countdown line, or {@code null} while no countdown runs
     * @param left      the control left
     * @param top       the control top
     * @param width     the control width
     */
    public static void drawLobby(@NotNull GlueCanvas canvas,
                                 @NotNull GluText strings,
                                 @Nullable Preview preview,
                                 @NotNull String gameName, @NotNull String mapName, @NotNull String gameType,
                                 @NotNull List<String[]> rows,
                                 @Nullable String countdown,
                                 float left, float top,
                                 float width) {
        float innerLeft = left + LOBBY_PADDING;
        float innerWidth = width - SIDE_COUNT * LOBBY_PADDING;
        float y = top + LOBBY_PADDING;

        drawImageBox(canvas, strings, preview, left + (width - IMAGE_SIZE) / CENTER_DIVISOR, y);
        y += IMAGE_SIZE + IMAGE_MARGIN;

        String[][] blocks = {{strings.get("infoPane_gameName"), gameName}, {strings.get("infoPane_mapName"), mapName},
                             {strings.get("infoPane_gameType"), gameType}};

        for (int index = 0; index < blocks.length; index++) {
            String[] block = blocks[index];

            // The first block's margin collapses into the image holder's larger bottom margin.
            if (index > 0) {
                y += CENTER_MARGIN;
            }

            float labelLine = canvas.getLineHeight(LOBBY_TEXT, block[LABEL_INDEX]);

            WebPanelStyleUtils.drawText(canvas,
                                        LOBBY_TEXT,
                                        block[LABEL_INDEX],
                                        innerLeft, y,
                                        innerWidth, labelLine,
                                        GlueCanvas.FORMAT_CENTER | GlueCanvas.FORMAT_TOP,
                                        WebPanelStyleUtils.TEXT);
            y += labelLine;
            WebPanelStyleUtils.drawText(canvas,
                                        LOBBY_VALUE,
                                        block[VALUE_INDEX],
                                        innerLeft, y,
                                        innerWidth, LOBBY_VALUE_LINE,
                                        GlueCanvas.FORMAT_CENTER | GlueCanvas.FORMAT_TOP,
                                        WHITE);
            y += LOBBY_VALUE_LINE;
        }

        float bottom = top + LOBBY_HEIGHT - LOBBY_PADDING;

        if (countdown != null) {
            // Reserve the bottom of the panel for the countdown.
            float countdownLine = canvas.getLineHeight(LOBBY_TEXT, countdown);

            bottom -= COUNTDOWN_MARGIN + countdownLine;
            WebPanelStyleUtils.drawText(canvas,
                                        LOBBY_TEXT,
                                        countdown,
                                        innerLeft, bottom,
                                        innerWidth, countdownLine,
                                        GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                        WHITE);
        }

        // Place the summary below the heading and omit rows that would overlap the countdown.
        y += SUMMARY_MARGIN + ROW_MARGIN;

        List<String[]> fitting = new ArrayList<>();

        for (String[] row : rows) {
            fitting.add(row);

            if (y + getRowsHeight(canvas, LOBBY_TEXT, fitting, ROW_MARGIN) > bottom) {
                fitting.removeLast();

                break;
            }
        }

        drawRows(canvas, LOBBY_TEXT, fitting, ROW_MARGIN, innerLeft, y, innerWidth);
    }

    /**
     * Draws replay details, file name and player names with race labels and colors.
     *
     * @param canvas  the glue canvas
     * @param strings the {@code gluAll} strings
     * @param replay  the selected replay's information
     * @param left    the control left
     * @param top     the control top
     * @param width   the control width
     * @param height  the control height
     */
    public static void drawReplay(@NotNull GlueCanvas canvas,
                                  @NotNull GluText strings,
                                  @NotNull ReplayInformation replay,
                                  float left, float top,
                                  float width, float height) {
        float textLeft = left + BROWSER_PADDING + REPLAY_TEXT_LEFT;
        float textWidth = width - SIDE_COUNT * BROWSER_PADDING - REPLAY_TEXT_LEFT;
        float y = top + BROWSER_PADDING_TOP;
        int headerLines = canvas.measure(REPLAY_HEADER, StatText.stripColors(replay.getGameName())) > textWidth ? REPLAY_HEADER_LINES : 1;

        WebPanelStyleUtils.drawText(canvas,
                                    REPLAY_HEADER,
                                    replay.getGameName(),
                                    textLeft, y,
                                    textWidth, REPLAY_HEADER_LINE * headerLines,
                                    GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                    WHITE);
        y += REPLAY_HEADER_LINE * headerLines + REPLAY_HEADER_GAP;

        float blockTop = y;

        for (String[] row : createReplayRows(strings, replay)) {
            // Center the label and value vertically in the replay summary row.
            float labelWidth = canvas.measure(REPLAY_LABEL, row[LABEL_INDEX]);
            float valueLine = canvas.getLineHeight(REPLAY_VALUE, row[VALUE_INDEX]);
            float rowHeight = Math.max(REPLAY_LABEL_LINE, valueLine);

            WebPanelStyleUtils.drawText(canvas,
                                        REPLAY_LABEL,
                                        row[LABEL_INDEX],
                                        textLeft, y + (rowHeight - REPLAY_LABEL_LINE) / CENTER_DIVISOR,
                                        labelWidth + REPLAY_LABEL_WIDTH_PADDING, REPLAY_LABEL_LINE,
                                        GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                        WebPanelStyleUtils.TEXT);

            float valueLeft = textLeft + labelWidth + REPLAY_TEXT_LEFT;

            WebPanelStyleUtils.drawText(canvas,
                                        REPLAY_VALUE,
                                        row[VALUE_INDEX],
                                        valueLeft, y + (rowHeight - valueLine) / CENTER_DIVISOR,
                                        textLeft + textWidth - valueLeft, valueLine,
                                        GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                        WHITE);
            y += rowHeight + REPLAY_ROW_GAP;
        }

        String fileLabel = strings.get("replay_file_name");
        float fileLabelWidth = canvas.measure(REPLAY_VALUE, fileLabel);

        WebPanelStyleUtils.drawText(canvas,
                                    REPLAY_VALUE,
                                    fileLabel,
                                    textLeft, y,
                                    fileLabelWidth + REPLAY_LABEL_WIDTH_PADDING, canvas.getLineHeight(REPLAY_VALUE, fileLabel),
                                    GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                    WebPanelStyleUtils.TEXT);

        float fileLeft = textLeft + fileLabelWidth + REPLAY_TEXT_LEFT;

        WebPanelStyleUtils.drawText(canvas,
                                    REPLAY_VALUE,
                                    replay.getFileName(),
                                    fileLeft, y,
                                    Math.min(FILE_NAME_WIDTH, textLeft + textWidth - fileLeft),
                                    Math.min(FILE_NAME_HEIGHT, blockTop + REPLAY_INFORMATION_HEIGHT - y),
                                    GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                    WHITE);
        y = blockTop + REPLAY_INFORMATION_HEIGHT;

        String players = strings.get("chatPLAYERS") + ":";

        WebPanelStyleUtils.drawText(canvas,
                                    REPLAY_HEADER,
                                    players,
                                    textLeft, y,
                                    textWidth, REPLAY_HEADER_LINE,
                                    GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                    WebPanelStyleUtils.TEXT);
        y += REPLAY_HEADER_LINE + PLAYER_HEADER_GAP;

        for (ReplayInformation.Player player : replay.getPlayers()) {
            String race = switch (player.getRace()) {
                case ZERG_RACE_IDENTIFIER -> strings.get("scr.common.race.zerg");
                case TERRAN_RACE_IDENTIFIER -> strings.get("scr.common.race.terran");
                case PROTOSS_RACE_IDENTIFIER -> strings.get("scr.common.race.protoss");
                default -> strings.get("lobbyScreen_random");
            };
            int color = switch (player.getRace()) {
                case ZERG_RACE_IDENTIFIER -> ZERG_COLOR;
                case TERRAN_RACE_IDENTIFIER -> TERRAN_COLOR;
                case PROTOSS_RACE_IDENTIFIER -> PROTOSS_COLOR;
                default -> WHITE;
            };
            float rowHeight = Math.max(canvas.getLineHeight(REPLAY_VALUE, player.getName()), canvas.getLineHeight(REPLAY_VALUE, race));

            if (y + rowHeight > top + height - BROWSER_PADDING) {
                break;
            }

            WebPanelStyleUtils.drawText(canvas,
                                        REPLAY_VALUE,
                                        player.getName(),
                                        textLeft, y,
                                        PLAYER_NAME_WIDTH - REPLAY_TEXT_LEFT, rowHeight,
                                        GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                        WHITE);
            WebPanelStyleUtils.drawText(canvas,
                                        REPLAY_VALUE,
                                        race,
                                        textLeft - REPLAY_TEXT_LEFT + PLAYER_NAME_WIDTH, y,
                                        PLAYER_NAME_WIDTH, rowHeight,
                                        GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                        color);
            y += rowHeight + REPLAY_ROW_GAP;
        }
    }

    /**
     * Creates the replay summary rows: the map, the game type when the replay has one, the date and the length.
     *
     * @param strings the {@code gluAll} strings
     * @param replay  the replay's information
     * @return the rows, each a label and its value
     */
    private static @NotNull List<String[]> createReplayRows(@NotNull GluText strings, @NotNull ReplayInformation replay) {
        List<String[]> rows = new ArrayList<>();

        rows.add(new String[]{strings.get("bnMAP"), replay.getMapTitle()});

        String type = GameTemplates.getTypeName(strings, replay.getGameType());

        if (!type.isEmpty()) {
            rows.add(new String[]{strings.get("bnTYPE"), type});
        }

        LocalDate date = Instant.ofEpochSecond(replay.getCreationTime()).atZone(ZoneId.systemDefault()).toLocalDate();

        rows.add(new String[]{strings.get("replay_date"), date.toString()});

        int seconds = TimerTextUtils.calculateElapsedSeconds((int) replay.getFrames(), GameSpeed.FASTEST_DURATION);

        rows.add(new String[]{strings.get("replay_length"), TimerTextUtils.formatElapsedTime(seconds)});

        return rows;
    }

    /**
     * Formats a map size in tiles as the information panels show it.
     *
     * @param width  the map width in tiles
     * @param height the map height in tiles
     * @return the size as {@code <width>x<height>}
     */
    static @NotNull String formatMapSize(int width, int height) {
        return width + "x" + height;
    }

    /**
     * @param canvas the glue canvas
     * @param slot   the font slot of the rows
     * @param rows   the rows, each a label (without the colon) and its value
     * @param margin the margin after each row
     * @return the height of the summary rows: each row's height ({@link #getRowHeight}) and one margin after each row; the rows' margins collapse, one margin between two rows and one after the last
     */
    private static float getRowsHeight(@NotNull GlueCanvas canvas, int slot, @NotNull List<String[]> rows, float margin) {
        float height = 0;

        for (String[] row : rows) {
            height += getRowHeight(canvas, slot, row) + margin;
        }

        return height;
    }

    /**
     * @param canvas the glue canvas
     * @param slot   the font slot of the row
     * @param row    the label (without the colon) and its value
     * @return the height of a summary row: the taller of the line boxes of the label with its colon and of the value
     */
    private static float getRowHeight(@NotNull GlueCanvas canvas, int slot, String @NotNull [] row) {
        return Math.max(canvas.getLineHeight(slot, row[LABEL_INDEX] + ":"), canvas.getLineHeight(slot, row[VALUE_INDEX]));
    }

    /**
     * Draws summary rows from the first row's top: the label with its colon right aligned in the left half less the label padding, the value left
     * aligned in the right half; each item's line box centered in the row; the rows' collapsed margins between them.
     *
     * @param canvas the glue canvas
     * @param slot   the font slot of the rows
     * @param rows   the rows, each a label (without the colon) and its value
     * @param margin the margin around the rows and after each row
     * @param left   the left edge of the rows
     * @param top    the top edge of the first row
     * @param width  the width of the rows
     */
    private static void drawRows(@NotNull GlueCanvas canvas, int slot, @NotNull List<String[]> rows, float margin, float left, float top, float width) {
        float rowLeft = left + margin;
        // Divide the space between the label and value after reserving the label padding.
        float share = (width - SIDE_COUNT * margin - LABEL_PADDING) / ROW_COLUMN_COUNT;
        float y = top;

        for (String[] row : rows) {
            String label = row[LABEL_INDEX] + ":";
            float rowHeight = getRowHeight(canvas, slot, row);
            float labelLine = canvas.getLineHeight(slot, label);
            float valueLine = canvas.getLineHeight(slot, row[VALUE_INDEX]);

            WebPanelStyleUtils.drawText(canvas,
                                        slot,
                                        label,
                                        rowLeft, y + (rowHeight - labelLine) / CENTER_DIVISOR,
                                        share, labelLine,
                                        GlueCanvas.FORMAT_RIGHT | GlueCanvas.FORMAT_TOP,
                                        WebPanelStyleUtils.TEXT);
            WebPanelStyleUtils.drawText(canvas,
                                        slot,
                                        row[VALUE_INDEX],
                                        rowLeft + share + LABEL_PADDING, y + (rowHeight - valueLine) / CENTER_DIVISOR,
                                        share, valueLine,
                                        GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                        WHITE);
            y += rowHeight + margin;
        }
    }

    /**
     * Fits the preview into the image box with an inset border, or centers the unavailable-image label.
     *
     * @param canvas  the glue canvas
     * @param strings the {@code gluAll} strings
     * @param preview the preview image, or {@code null} when there is none
     * @param boxLeft the left edge of the image box
     * @param boxTop  the top edge of the image box
     * @return {@code true} if a preview was drawn, {@code false} otherwise
     */
    private static boolean drawImageBox(@NotNull GlueCanvas canvas, @NotNull GluText strings, @Nullable Preview preview, float boxLeft, float boxTop) {
        if (preview != null && preview.getWidth() > 0 && preview.getHeight() > 0) {
            float fit = Math.min(IMAGE_SIZE / (float) preview.getWidth(), IMAGE_SIZE / (float) preview.getHeight());
            float imageWidth = preview.getWidth() * fit;
            float imageHeight = preview.getHeight() * fit;
            float imageLeft = boxLeft + (IMAGE_SIZE - imageWidth) / CENTER_DIVISOR;
            float imageTop = boxTop + (IMAGE_SIZE - imageHeight) / CENTER_DIVISOR;

            canvas.drawTexture(preview.getTexture(), imageLeft, imageTop, imageWidth, imageHeight, WHITE);

            // A border thinner than a device pixel is drawn one device pixel wide.
            float border = Math.max(IMAGE_BORDER_WIDTH, 1 / canvas.getScale());

            canvas.fillRectangle(imageLeft, imageTop, imageWidth, border, WebPanelStyleUtils.BORDER);
            canvas.fillRectangle(imageLeft, imageTop + imageHeight - border, imageWidth, border, WebPanelStyleUtils.BORDER);
            canvas.fillRectangle(imageLeft, imageTop, border, imageHeight, WebPanelStyleUtils.BORDER);
            canvas.fillRectangle(imageLeft + imageWidth - border, imageTop, border, imageHeight, WebPanelStyleUtils.BORDER);

            return true;
        }

        String text = strings.get("infoPane_noMapImage");
        float line = canvas.getLineHeight(IMAGE_TEXT, text);

        WebPanelStyleUtils.drawText(canvas,
                                    IMAGE_TEXT,
                                    text,
                                    boxLeft, boxTop + (IMAGE_SIZE - line) / CENTER_DIVISOR,
                                    IMAGE_SIZE, line,
                                    GlueCanvas.FORMAT_CENTER | GlueCanvas.FORMAT_TOP,
                                    WHITE);

        return false;
    }

    private MapInformationPanel() {
    }
}
