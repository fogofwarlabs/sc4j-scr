package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.configuration.InstallLayout;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueSettings;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks the multiplayer create flags read from client settings, and that the Beacon.Link create page's controls end inside its panel art's
 * frame with its rows from the page's content top, that its folder button draws the map drop-down's bar and that a framed text field leaves
 * the corners outside its rounded frame unpainted, that its radio buttons and the map browser's rows of Korean and English names keep their text
 * on one line beside their circle or icon (GPU, hidden context).
 */
final class CreateGameScreenTest {
    private static final @NotNull String CUSTOM_HOTKEYS_OPTION = "game.customHotkeys";
    private static final @NotNull String EXTENDED_UNIT_LIMITS_OPTION = "game.extendedUnitLimits";

    /**
     * The created game's flags for disabled custom hotkeys and for original unit limits.
     */
    private static final int HOTKEYS_DISABLED_FLAG = 1;
    private static final int ORIGINAL_LIMITS_FLAG = 4;

    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int SELF_KEY = 1;
    /**
     * The create page and the map browser of the Beacon.Link create dialog, and their controls: the create page's controls (the game name, the
     * radio buttons, the password, the map drop-down and its folder button, the type and subtype drop-downs, the sliders, the title, the
     * labels and the slider rows' texts), the map browser's title and open folder.
     */
    private static final int FORM_PAGE = 22;
    private static final int GAME_NAME = 90;
    private static final int MAP_CHOICE = 91;
    private static final int FOLDER_BUTTON = 92;
    private static final int PRIVACY_PRIVATE = 101;
    private static final int PASSWORD = 102;
    private static final int FORM_TITLE = 110;
    private static final int BROWSER_TITLE = 127;
    private static final int BROWSER_FOLDER = 6;
    private static final int @NotNull [] FORM_CONTROLS = {GAME_NAME, MAP_CHOICE, FOLDER_BUTTON, 94, 95, 96, 97, 100, PRIVACY_PRIVATE, PASSWORD, 103,
                                                          16, 17, 18, FORM_TITLE, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123,
                                                          124, 125, 126};
    /**
     * The frame lines of the create page's panel art ({@code glue\create\pListMap4}) in page pixels: left, right and bottom.
     */
    private static final int FRAME_LEFT = 2;
    private static final int FRAME_RIGHT = 329;
    private static final int FRAME_BOTTOM = 249;
    /**
     * The local network game list's list starts this far under the top of its title ({@code gluJoin}: the title at {@code 60}, the list at
     * {@code 92}).
     */
    private static final int LAN_LIST_UNDER_TITLE = 32;
    /**
     * A drawn bar's column a little inside its left end, past the rounded corner, and the rows searched above and below the control.
     */
    private static final float BAR_SAMPLE_X = 3;
    private static final int BAR_SEARCH_MARGIN = 6;
    /**
     * A frame pixel is red: its red channel at least this and at least this many times each of the others.
     */
    private static final int FRAME_RED_MINIMUM = 60;
    private static final int FRAME_RED_FACTOR = 3;
    private static final int BYTE_MASK = 0xFF;
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;
    private static final int NOT_FOUND = -1;

    /**
     * The create page's radio buttons (privacy, custom hotkeys, unit limits), the size of their circle and the space before their label.
     */
    private static final int @NotNull [] RADIO_BUTTONS = {100, PRIVACY_PRIVATE, 94, 95, 96, 97};
    private static final float RADIO_SIZE = 10;
    /**
     * The map browser's list and the map files of its folder: Korean and English names, each ending in the same Latin letters.
     */
    private static final int MAP_LIST = 5;
    private static final @NotNull List<String> MIXED_MAP_NAMES = List.of("Arena.scx", "전장.scx", "Delta.scx", "사막.scx");
    /**
     * A list row: the icon column ({@code 2} in, the icon's inside) and the text from its column on, in interface pixels.
     */
    private static final float ICON_LEFT = 4;
    private static final float ICON_RIGHT = 14;
    private static final float TEXT_LEFT = 24;
    private static final float ROW_MARGIN = 3;
    /**
     * The most a mark's center and its text line may differ in frame pixels.
     */
    private static final float LINE_TOLERANCE = 1;

    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;

    private static @NotNull DialogControl getDefinition(@NotNull ScrDialog dialog, int id) {
        ScrControl control = dialog.findControl(id);

        Assertions.assertNotNull(control, "The create dialog has control " + id + ".");

        return control.getDefinition();
    }

    private static boolean isFrameRed(int color) {
        int red = color >> RED_SHIFT & BYTE_MASK;
        int green = color >> GREEN_SHIFT & BYTE_MASK;
        int blue = color & BYTE_MASK;

        return red >= FRAME_RED_MINIMUM && green * FRAME_RED_FACTOR <= red && blue * FRAME_RED_FACTOR <= red;
    }

    /**
     * @return the first and the last red row of a frame column between two rows
     */
    private static int @NotNull [] findFrameRows(@NotNull BufferedImage frame, int column, int firstRow, int lastRow) {
        int first = NOT_FOUND;
        int last = NOT_FOUND;

        for (int row = firstRow; row <= lastRow; row++) {
            if (isFrameRed(frame.getRGB(column, row))) {
                first = first == NOT_FOUND ? row : first;
                last  = row;
            }
        }

        return new int[]{first, last};
    }

    @TempDir
    @Nullable Path directory;

    /**
     * Opens the create page over Beacon.Link after the channel chat.
     */
    private @NotNull BeaconLinkGlueScenario openCreatePage(@NotNull String name, @NotNull String locale, int width, int height,
                                                           @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        RecordingCommunitySession community = new RecordingCommunitySession(
                RecordingCommunitySession.createPresence(SELF_KEY, "Raynor", BeaconLinkGlueScenario.MOD_ID, BeaconLinkGlueScenario.MOD_VERSION,
                                                         true, PlayerStatus.AVAILABLE)
        );
        BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(name), locale, width, height, qualityPrefix, community);

        scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
        scenario.show(GlueNavigator.Destination.CREATE_GAME);

        return scenario;
    }

    /**
     * Measures each radio button's circle center and its label's letters' center (frame rows) and returns the ones further apart than
     * the tolerance.
     */
    private static @NotNull List<String> findRadioOffsets(@NotNull BeaconLinkGlueScenario scenario) {
        ScrDialog dialog = scenario.getScreenDialog();
        BufferedImage frame = scenario.readFrame();
        List<String> offsets = new ArrayList<>();

        for (int id : RADIO_BUTTONS) {
            ScrControl control = dialog.findControl(id);

            Assertions.assertNotNull(control);

            DialogControl definition = control.getDefinition();
            int top = scenario.getFrameY(definition.getY() + control.getVerticalShift());
            int bottom = scenario.getFrameY(definition.getY() + control.getVerticalShift() + definition.getHeight()) - 1;
            int circleRight = scenario.getFrameX(definition.getX() + RADIO_SIZE);
            float circle = TextLineCheck.findRedCenter(frame, scenario.getFrameX(definition.getX()), top, circleRight, bottom);
            float glyph = TextLineCheck.findTextCenter(frame, circleRight + 1, top, scenario.getFrameX(definition.getX() + definition.getWidth()) - 1,
                                                       bottom);

            if (Math.abs(circle - glyph) > LINE_TOLERANCE) {
                offsets.add(id + ": circle " + circle + ", label " + glyph);
            }
        }

        return offsets;
    }

    @Test
    void read_game_flags_should_be_zero_when_settings_are_unset() {
        Assertions.assertEquals(0, CreateGameScreen.readGameFlags(GlueSettings.createInMemory()));
    }

    @Test
    void read_game_flags_should_set_flag_for_each_setting_turned_off() {
        GlueSettings settings = GlueSettings.createInMemory(Map.of(CUSTOM_HOTKEYS_OPTION, false, EXTENDED_UNIT_LIMITS_OPTION, false));

        Assertions.assertEquals(HOTKEYS_DISABLED_FLAG | ORIGINAL_LIMITS_FLAG, CreateGameScreen.readGameFlags(settings));

        settings = GlueSettings.createInMemory(Map.of(CUSTOM_HOTKEYS_OPTION, true, EXTENDED_UNIT_LIMITS_OPTION, false));

        Assertions.assertEquals(ORIGINAL_LIMITS_FLAG, CreateGameScreen.readGameFlags(settings));
    }

    @Test
    @RequiresInstallation
    void create_page_controls_end_inside_the_panel_frame_and_start_at_the_page_content_top() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        try (BeaconLinkGlueScenario scenario = this.openCreatePage("create-rows", "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX)) {
            ScrDialog dialog = scenario.getScreenDialog();
            DialogControl page = getDefinition(dialog, FORM_PAGE);
            List<String> outside = new ArrayList<>();

            for (int id : FORM_CONTROLS) {
                ScrControl control = dialog.findControl(id);

                Assertions.assertNotNull(control);

                DialogControl definition = control.getDefinition();

                // The loaded place is the row's with the subtype's row shown, the shifted place without it.
                for (int top : new int[]{definition.getY(), definition.getY() + control.getVerticalShift()}) {
                    if (definition.getX() <= page.getX() + FRAME_LEFT || definition.getX() + definition.getWidth() > page.getX() + FRAME_RIGHT ||
                        top < page.getY() || top + definition.getHeight() > page.getY() + FRAME_BOTTOM) {
                        outside.add(id + " at " + (definition.getX() - page.getX()) + "," + (top - page.getY()));
                    }
                }
            }

            Assertions.assertEquals(List.of(), outside, "Create page controls (page pixels) outside the panel art's frame.");
            Assertions.assertEquals(LAN_LIST_UNDER_TITLE, getDefinition(dialog, GAME_NAME).getY() - getDefinition(dialog, FORM_TITLE).getY());
            Assertions.assertEquals(LAN_LIST_UNDER_TITLE, getDefinition(dialog, BROWSER_FOLDER).getY() - getDefinition(dialog, BROWSER_TITLE).getY());
        }
    }

    @Test
    @RequiresInstallation
    void radio_buttons_center_their_circle_on_their_label_line() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        List<String> offsets = new ArrayList<>();

        try (BeaconLinkGlueScenario scenario = this.openCreatePage("radios-small", "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX)) {
            offsets.addAll(findRadioOffsets(scenario));
        }

        try (BeaconLinkGlueScenario scenario = this.openCreatePage("radios-large", "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX)) {
            offsets.addAll(findRadioOffsets(scenario));
        }

        Assertions.assertEquals(List.of(), offsets, "Radio buttons whose circle and label are not centered on one line (frame rows).");
    }

    @Test
    @RequiresInstallation
    void map_browser_rows_of_korean_and_english_names_keep_one_text_line_beside_their_icons() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        List<String> lines = new ArrayList<>();

        lines.addAll(this.findMapRowLines("maps-small", "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX));
        lines.addAll(this.findMapRowLines("maps-large", "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX));

        Assertions.assertEquals(List.of(), lines, "Map browser frames whose rows put the text line at different heights beside the icons.");
    }

    /**
     * Opens the map browser over a folder of the mixed map names and measures, per row, the bottom of the name's last glyph (a Latin letter
     * on the baseline) below the icon's center.
     *
     * @return the frame's offsets when they differ by more than the tolerance, empty otherwise
     */
    private @NotNull List<String> findMapRowLines(@NotNull String name, @NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        Path mapDirectory = InstallLayout.of(root.resolve(name)).getMapDirectory();

        Files.createDirectories(mapDirectory);

        for (String map : MIXED_MAP_NAMES) {
            Files.copy(GameTestResources.getPath(GameTestResources.MAP_ARCHIVE_DRAGON), mapDirectory.resolve(map));
        }

        try (BeaconLinkGlueScenario scenario = this.openCreatePage(name, locale, width, height, qualityPrefix)) {
            DialogControl button = getDefinition(scenario.getScreenDialog(), FOLDER_BUTTON);

            scenario.click(button.getX() + button.getWidth() / 2f, button.getY() + button.getHeight() / 2f);

            DialogControl list = getDefinition(scenario.getScreenDialog(), MAP_LIST);
            BufferedImage frame = scenario.readFrame();
            int iconLeft = scenario.getFrameX(list.getX() + ICON_LEFT);
            int iconRight = scenario.getFrameX(list.getX() + ICON_RIGHT);
            int textLeft = scenario.getFrameX(list.getX() + TEXT_LEFT);
            int textRight = scenario.getFrameX(list.getX() + list.getWidth()) - 1;
            int margin = scenario.getFrameY(ROW_MARGIN) - scenario.getFrameY(0);
            List<Float> offsets = new ArrayList<>();
            int row = scenario.getFrameY(list.getY());
            int listBottom = scenario.getFrameY(list.getY() + list.getHeight()) - 1;

            // Each icon is a run of rows with lit pixels in the icon column; its row's text is beside it.
            while (row <= listBottom) {
                if (TextLineCheck.findLitCenter(frame, iconLeft, row, iconRight, row) < 0) {
                    row++;

                    continue;
                }

                int iconTop = row;

                while (row + 1 <= listBottom && TextLineCheck.findLitCenter(frame, iconLeft, row + 1, iconRight, row + 1) >= 0) {
                    row++;
                }

                float iconCenter = (iconTop + row) / 2f;
                int textBottom = TextLineCheck.findLastGlyphBottom(frame, textLeft, iconTop - margin, textRight, Math.min(listBottom, row + margin));

                offsets.add(textBottom - iconCenter);
                row++;
            }

            Assertions.assertEquals(MIXED_MAP_NAMES.size(), offsets.size(), name + ": one icon per map.");

            float spread = offsets.stream().max(Float::compare).orElseThrow() - offsets.stream().min(Float::compare).orElseThrow();

            return spread > LINE_TOLERANCE ? List.of(name + " " + offsets) : List.of();
        }
    }

    @Test
    @RequiresInstallation
    void folder_button_draws_the_map_drop_down_bar_at_its_rows() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        try (BeaconLinkGlueScenario scenario = this.openCreatePage("create-folder", "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX)) {
            ScrDialog dialog = scenario.getScreenDialog();
            DialogControl choice = getDefinition(dialog, MAP_CHOICE);
            DialogControl button = getDefinition(dialog, FOLDER_BUTTON);
            BufferedImage frame = scenario.readFrame();
            int firstRow = scenario.getFrameY(choice.getY()) - BAR_SEARCH_MARGIN;
            int lastRow = scenario.getFrameY(choice.getY() + choice.getHeight()) + BAR_SEARCH_MARGIN;
            int[] barRows = findFrameRows(frame, scenario.getFrameX(choice.getX() + BAR_SAMPLE_X), firstRow, lastRow);
            int[] buttonRows = findFrameRows(frame, scenario.getFrameX(button.getX() + BAR_SAMPLE_X), firstRow, lastRow);

            Assertions.assertNotEquals(NOT_FOUND, barRows[0], "The map drop-down draws its bar.");
            Assertions.assertArrayEquals(barRows, buttonRows, "The folder button's frame rows " + buttonRows[0] + ".." + buttonRows[1] +
                                                              " are the map drop-down bar's " + barRows[0] + ".." + barRows[1] + ".");
        }
    }

    @Test
    @RequiresInstallation
    void framed_text_field_leaves_the_corners_outside_its_rounded_frame_unpainted() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        try (BeaconLinkGlueScenario scenario = this.openCreatePage("create-corners", "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX)) {
            ScrDialog dialog = scenario.getScreenDialog();
            DialogControl password = getDefinition(dialog, PASSWORD);
            DialogControl privateRadio = getDefinition(dialog, PRIVACY_PRIVATE);
            BufferedImage publicGame = scenario.readFrame();

            // A private game shows the password field over the place the public game leaves empty.
            scenario.click(privateRadio.getX() + privateRadio.getWidth() / 2f, privateRadio.getY() + privateRadio.getHeight() / 2f);

            BufferedImage privateGame = scenario.readFrame();
            ScrControl field = dialog.findControl(PASSWORD);
            int left = scenario.getFrameX(password.getX());
            int top = scenario.getFrameY(password.getY());
            int right = scenario.getFrameX(password.getX() + password.getWidth()) - 1;
            int bottom = scenario.getFrameY(password.getY() + password.getHeight()) - 1;

            Assertions.assertNotNull(field);
            Assertions.assertTrue(field.isVisible(), "The password field shows for a private game.");

            for (int[] corner : new int[][]{{left, top}, {right, top}, {left, bottom}, {right, bottom}}) {
                Assertions.assertEquals(publicGame.getRGB(corner[0], corner[1]), privateGame.getRGB(corner[0], corner[1]),
                                        "The password field paints its corner " + corner[0] + "," + corner[1] + ".");
            }
        }
    }
}
