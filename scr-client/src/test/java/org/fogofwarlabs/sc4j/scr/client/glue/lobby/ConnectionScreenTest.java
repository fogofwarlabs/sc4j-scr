package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LatencyEstimate;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.glue.OwnText;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks that the connection choice enters Beacon.Link with the run's account: Beacon.Link and Ok open the channel chat at once with a
 * session named after the account, while the top right names the account. SD at 640x480 in English and HD at 1920x1080 in Korean. The
 * lower left panel holds LAN's
 * protocol pane and is hidden for Beacon.Link, which has no pane; Beacon.Link shows its plain name and a requirements line as the
 * installed ones.
 * <p>
 * Switching between them slides the lower left panel out and back in with its pane.
 * <p>
 * The reference frames are stored as SHA-256 digests of the read-back pixels (the frames hold installation artwork, which the repository does
 * not keep); every drawn frame is also written to {@code build/glue-screenshots}. The digests are those of the reference machine's OpenGL
 * driver.
 */
@RequiresInstallation
final class ConnectionScreenTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int SELF_KEY = 1;

    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;
    private static final @NotNull String ACCOUNT_FRAME = "beaconlink-entry-";
    private static final @NotNull String LAN_PANE_FRAME = "connection-lan-";
    private static final @NotNull String BEACON_LINK_PANE_FRAME = "connection-beaconlink-";
    private static final @NotNull String SLIDE_OUT_FRAME = "connection-slide-out-";
    private static final @NotNull String SLIDE_IN_FRAME = "connection-slide-in-";
    /**
     * The drawn times after a selection at which the slide frames are taken, in milliseconds.
     */
    private static final int @NotNull [] SLIDE_FRAME_TIMES = {60, 120, 180, 240, 360, 480};
    /**
     * The panel of the selected provider's pane and LAN's protocol pane in it.
     */
    private static final int PANE_PANEL = 11;
    private static final int PROTOCOL_PANEL = 17;
    /**
     * A drawn border line covers more than half of the panel's width.
     */
    private static final int BORDER_LINE_DIVISOR = 2;
    /**
     * The installed requirements line of LAN, whose label ({@code Requirements:}) every provider's line starts with.
     */
    private static final @NotNull String LAN_REQUIREMENTS_KEY = "conUDP_d";
    /**
     * The installed title of the online service, shown with Beacon.Link's plain name.
     */
    private static final @NotNull String BEACON_LINK_TITLE_KEY = "spi_battlenet_title";
    private static final @NotNull String BEACON_LINK_NAME = "Beacon.Link";
    private static final char LABEL_END = ':';
    /**
     * The locales of the mod's own texts.
     */
    private static final @NotNull List<String> TEXT_LOCALES = List.of("enUS", "koKR");
    /**
     * The provider list's first row, Beacon.Link, in dialog pixels.
     */
    private static final float BEACON_LINK_ROW_X = 150;
    private static final float BEACON_LINK_ROW_Y = 100;
    /**
     * The provider list's second row, LAN, in dialog pixels.
     */
    private static final float LAN_ROW_Y = 120;
    /**
     * The digests of the reference frames.
     */
    private static final @NotNull Map<String, String> REFERENCE_DIGESTS = Map.of(
            ACCOUNT_FRAME + "640x480-enUS", "faed1456eb85e9d10d0b12bee1539cf2a272278ec7d567324ce1d280bbbf9212",
            ACCOUNT_FRAME + "1920x1080-koKR", "d4c9bb31b18030337aa12e8859e7c7ea787bdf9f8aafddcf05178a5680a146e9",
            LAN_PANE_FRAME + "640x480-enUS", "01d9c1111141865885e8039027e1fe167a74e3f040133c3bb95c6b8b16fb8a9d",
            LAN_PANE_FRAME + "1920x1080-koKR", "acef69ab1d8142a7d6f67d8a469fa124636469ea90d46600d1243d9748fb27cf",
            BEACON_LINK_PANE_FRAME + "640x480-enUS", "faed1456eb85e9d10d0b12bee1539cf2a272278ec7d567324ce1d280bbbf9212",
            BEACON_LINK_PANE_FRAME + "1920x1080-koKR", "d4c9bb31b18030337aa12e8859e7c7ea787bdf9f8aafddcf05178a5680a146e9"
    );

    private static void assertReferenceDigests(@NotNull Map<String, String> digests) {
        List<String> mismatches = new ArrayList<>();

        for (Map.Entry<String, String> drawn : digests.entrySet()) {
            if (!drawn.getValue().equals(REFERENCE_DIGESTS.get(drawn.getKey()))) {
                mismatches.add(drawn.getKey() + " " + drawn.getValue());
            }
        }

        Assertions.assertEquals(List.of(), mismatches, "Frames that differ from the reference (see build/glue-screenshots).");
    }

    /**
     * Counts the frame-red pixels (a panel's border) of each frame row over a dialog rectangle.
     *
     * @return the counts, the rectangle's top row first
     */
    private static int @NotNull [] countRedRows(@NotNull BeaconLinkGlueScenario scenario, @NotNull BufferedImage frame,
                                                @NotNull DialogControl control) {
        int top = scenario.getFrameY(control.getY());
        int[] counts = new int[scenario.getFrameY(control.getY() + control.getHeight()) - top];

        for (int row = 0; row < counts.length; row++) {
            for (int column = scenario.getFrameX(control.getX()); column < scenario.getFrameX(control.getX() + control.getWidth()); column++) {
                if (TextLineCheck.isRed(frame.getRGB(column, top + row))) {
                    counts[row]++;
                }
            }
        }

        return counts;
    }

    @TempDir
    @Nullable Path directory;

    /**
     * Opens the connection choice on LAN and selects Beacon.Link: the lower left panel and its protocol pane show for LAN only.
     */
    private void selectProviders(@NotNull String size, @NotNull String locale, int width, int height, @NotNull String qualityPrefix,
                                 @NotNull Map<String, String> digests) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        RecordingCommunitySession community = new RecordingCommunitySession(
                RecordingCommunitySession.createPresence(SELF_KEY, BeaconLinkGlueScenario.ACCOUNT_NAME, BeaconLinkGlueScenario.MOD_ID,
                                                         BeaconLinkGlueScenario.MOD_VERSION, true, PlayerStatus.AVAILABLE, LatencyEstimate.UNKNOWN)
        );

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(LAN_PANE_FRAME + size), locale, width, height, qualityPrefix,
                                                                           community)) {
            scenario.show(GlueNavigator.Destination.CONNECTION);

            ScrDialog dialog = scenario.getScreenDialog();
            DialogControl panel = dialog.requireControl(PANE_PANEL).getDefinition();
            DialogControl protocol = dialog.requireControl(PROTOCOL_PANEL).getDefinition();

            digests.put(LAN_PANE_FRAME + size, scenario.capture(LAN_PANE_FRAME + size));
            Assertions.assertTrue(dialog.isVisible(panel), size);
            Assertions.assertTrue(dialog.isVisible(protocol), size);

            // The panel's longest red row is its border line.
            int[] lanRows = countRedRows(scenario, scenario.readFrame(), panel);
            int border = 0;

            for (int row = 1; row < lanRows.length; row++) {
                border = lanRows[row] > lanRows[border] ? row : border;
            }

            int lineWidth = scenario.getFrameX(panel.getX() + panel.getWidth()) - scenario.getFrameX(panel.getX());

            Assertions.assertTrue(lanRows[border] * BORDER_LINE_DIVISOR > lineWidth, size);

            scenario.click(BEACON_LINK_ROW_X, BEACON_LINK_ROW_Y);

            digests.put(BEACON_LINK_PANE_FRAME + size, scenario.capture(BEACON_LINK_PANE_FRAME + size));
            Assertions.assertFalse(dialog.isVisible(panel), size);
            Assertions.assertFalse(dialog.isVisible(protocol), size);
            Assertions.assertTrue(countRedRows(scenario, scenario.readFrame(), panel)[border] * BORDER_LINE_DIVISOR <= lineWidth, size);
        }
    }

    /**
     * Opens the connection choice, selects Beacon.Link, captures the screen and presses Ok.
     *
     * @return the frame's digest
     */
    private @NotNull String enterBeaconLink(@NotNull String name, @NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        RecordingCommunitySession community = new RecordingCommunitySession(
                RecordingCommunitySession.createPresence(SELF_KEY, BeaconLinkGlueScenario.ACCOUNT_NAME, BeaconLinkGlueScenario.MOD_ID,
                                                         BeaconLinkGlueScenario.MOD_VERSION, true, PlayerStatus.AVAILABLE, LatencyEstimate.UNKNOWN)
        );

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(name), locale, width, height, qualityPrefix, community)) {
            scenario.show(GlueNavigator.Destination.CONNECTION);
            scenario.click(BEACON_LINK_ROW_X, BEACON_LINK_ROW_Y);

            String digest = scenario.capture(name);

            scenario.press(Key.ENTER);

            Assertions.assertEquals(List.of(BeaconLinkGlueScenario.ACCOUNT_NAME), scenario.getBeaconLink().getDisplayNames(), name);
            Assertions.assertFalse(scenario.isPopupOpen(), name);

            return digest;
        }
    }

    /**
     * Opens the connection choice on LAN, selects Beacon.Link and LAN again and takes frames of both slides.
     */
    private void slidePane(@NotNull String size, @NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        RecordingCommunitySession community = new RecordingCommunitySession(
                RecordingCommunitySession.createPresence(SELF_KEY, BeaconLinkGlueScenario.ACCOUNT_NAME, BeaconLinkGlueScenario.MOD_ID,
                                                         BeaconLinkGlueScenario.MOD_VERSION, true, PlayerStatus.AVAILABLE, LatencyEstimate.UNKNOWN)
        );

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(SLIDE_OUT_FRAME + size), locale, width, height,
                                                                           qualityPrefix, community)) {
            scenario.show(GlueNavigator.Destination.CONNECTION);

            ScrDialog dialog = scenario.getScreenDialog();
            DialogControl panel = dialog.requireControl(PANE_PANEL).getDefinition();

            // The pointer rests where the last selection leaves it.
            scenario.hover(BEACON_LINK_ROW_X, LAN_ROW_Y);

            String lan = scenario.capture(LAN_PANE_FRAME + size + "-hovered");

            // The panel slides out while it stays shown, then hides.
            List<String> outFrames = this.captureSlide(scenario, BEACON_LINK_ROW_Y, SLIDE_OUT_FRAME + size, dialog, panel);

            Assertions.assertNotEquals(lan, outFrames.getFirst(), size);
            scenario.settle();
            Assertions.assertFalse(dialog.isVisible(panel), size);

            String beaconLink = scenario.capture(BEACON_LINK_PANE_FRAME + size);

            Assertions.assertNotEquals(beaconLink, outFrames.getFirst(), size);

            // The panel shows at once and slides in, then stands where it stood.
            List<String> inFrames = this.captureSlide(scenario, LAN_ROW_Y, SLIDE_IN_FRAME + size, dialog, panel);

            Assertions.assertNotEquals(beaconLink, inFrames.getFirst(), size);
            Assertions.assertNotEquals(lan, inFrames.getFirst(), size);
            scenario.settle();
            Assertions.assertEquals(lan, scenario.capture(LAN_PANE_FRAME + size + "-again"), size);
        }
    }

    /**
     * Selects a provider row and takes a frame at each of {@link #SLIDE_FRAME_TIMES}; the panel is shown at the first one.
     *
     * @return the frames' digests
     */
    private @NotNull List<String> captureSlide(@NotNull BeaconLinkGlueScenario scenario, float rowY, @NotNull String name, @NotNull ScrDialog dialog,
                                              @NotNull DialogControl panel) throws IOException {
        List<String> digests = new ArrayList<>();
        int drawn = 0;

        scenario.tap(BEACON_LINK_ROW_X, rowY);

        for (int time : SLIDE_FRAME_TIMES) {
            scenario.draw(time - drawn);
            drawn = time;
            digests.add(scenario.capture(name + time));

            if (digests.size() == 1) {
                Assertions.assertTrue(dialog.isVisible(panel), name);
            }
        }

        return digests;
    }

    @Test
    void beacon_link_shows_its_plain_name_and_a_requirements_line_as_the_installed_ones() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            for (String locale : TEXT_LOCALES) {
                GluText text = GluText.load(storage, locale);
                String installed = text.get(LAN_REQUIREMENTS_KEY);
                String label = installed.substring(0, installed.indexOf(LABEL_END) + 1);

                Assertions.assertEquals(BEACON_LINK_NAME, text.get(BEACON_LINK_TITLE_KEY), locale);
                Assertions.assertTrue(OwnText.CONNECTION_REQUIREMENTS.get(locale).startsWith(label + " "), locale);
            }
        }
    }

    @Test
    void lan_shows_its_protocol_panel_and_beacon_link_shows_no_empty_panel() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Map<String, String> digests = new LinkedHashMap<>();

        this.selectProviders(SMALL_WIDTH + "x" + SMALL_HEIGHT + "-enUS", "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX, digests);
        this.selectProviders(LARGE_WIDTH + "x" + LARGE_HEIGHT + "-koKR", "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX, digests);

        assertReferenceDigests(digests);
    }

    @Test
    void the_lower_left_panel_slides_out_for_beacon_link_and_back_in_for_lan() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        this.slidePane(SMALL_WIDTH + "x" + SMALL_HEIGHT + "-enUS", "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX);
        this.slidePane(LARGE_WIDTH + "x" + LARGE_HEIGHT + "-koKR", "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX);
    }

    @Test
    void ok_enters_beacon_link_with_the_account_name() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Map<String, String> digests = new LinkedHashMap<>();
        String small = SMALL_WIDTH + "x" + SMALL_HEIGHT + "-enUS";
        String large = LARGE_WIDTH + "x" + LARGE_HEIGHT + "-koKR";

        digests.put(ACCOUNT_FRAME + small, this.enterBeaconLink(ACCOUNT_FRAME + small, "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX));
        digests.put(ACCOUNT_FRAME + large, this.enterBeaconLink(ACCOUNT_FRAME + large, "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX));

        assertReferenceDigests(digests);
    }
}
