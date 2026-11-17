package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueScreen;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks that the Beacon.Link channel list, opened by the channel tab's Join Channel button, draws the stored reference frames on a hidden context with
 * frozen time (the known channels with their member counts and a pressed channel in the name input), that its name input and its Ok and Cancel
 * are the screens' framed text field and buttons, and that Enter then joins the pressed channel: SD at 640x480 in English and HD at 1920x1080
 * in Korean.
 * <p>
 * The reference frames are stored as SHA-256 digests of the read-back pixels (the frames hold installation artwork, which the repository does
 * not keep); every drawn frame is also written to {@code build/glue-screenshots}. The digests are those of the reference machine's OpenGL
 * driver.
 */
@RequiresInstallation
final class ChannelListPopupTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int SELF_KEY = 1;
    private static final int KERRIGAN_KEY = 2;
    /**
     * The known channels and their member counts.
     */
    private static final @NotNull String FIRST_CHANNEL = "StarCraft Remastered-1";
    private static final @NotNull String SECOND_CHANNEL = "StarCraft Remastered-2";
    private static final int SHARED_MEMBERS = 58;
    private static final int FIRST_MEMBERS = 23;
    private static final int SECOND_MEMBERS = 4;
    private static final int KOREAN_MEMBERS = 12;
    /**
     * The channel tab's Join Channel button and the list's fourth row ({@link #SECOND_CHANNEL}), in dialog pixels.
     */
    private static final float CHANNEL_BUTTON_X = 500;
    private static final float CHANNEL_BUTTON_Y = 414;
    private static final float ROW_X = 250;
    private static final float FOURTH_ROW_Y = 163;
    /**
     * The popup's Ok and Cancel, and with its name input the controls that are the screens' framed ones.
     */
    private static final int OK_BUTTON = 92;
    private static final int CANCEL_BUTTON = 93;
    private static final int @NotNull [] FRAMED_CONTROLS = {BeaconLinkPopup.INPUT, OK_BUTTON, CANCEL_BUTTON};

    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;
    private static final @NotNull String ENGLISH_FRAME = "beaconlink-channels-640x480-enUS";
    private static final @NotNull String KOREAN_FRAME = "beaconlink-channels-1920x1080-koKR";
    /**
     * The digests of the reference frames, in drawing order.
     */
    private static final @NotNull Map<String, String> REFERENCE_DIGESTS = Map.of(
            ENGLISH_FRAME, "30302f00364e11503b246c4f36e5ac94fc05b3ab82f3f000a788735fdc97d2bf",
            KOREAN_FRAME, "c16e9b12b386716c0443ad16fd61db70aa6a739daacc7d3e28d1a1bb6842f22a"
    );

    private static @NotNull PlayerPresence createPlayer(int key, @NotNull String name) {
        return RecordingCommunitySession.createPresence(key, name, BeaconLinkGlueScenario.MOD_ID, BeaconLinkGlueScenario.MOD_VERSION, true,
                                                        PlayerStatus.AVAILABLE);
    }

    @TempDir
    @Nullable Path directory;

    private @NotNull String drawFrame(@NotNull String name, @NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan");
        RecordingCommunitySession community = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor"));

        community.addSummary(ChannelChat.SHARED_CHANNEL, SHARED_MEMBERS);
        community.addSummary(FIRST_CHANNEL, FIRST_MEMBERS);
        community.addSummary("Korean", KOREAN_MEMBERS);
        community.addSummary(SECOND_CHANNEL, SECOND_MEMBERS);
        community.setChannelSetup(channel -> channel.addMember(kerrigan));

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(name), locale, width, height, qualityPrefix, community)) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.click(CHANNEL_BUTTON_X, CHANNEL_BUTTON_Y);
            scenario.click(ROW_X, FOURTH_ROW_Y);

            Assertions.assertTrue(scenario.isPopupOpen());

            ScrDialog popup = scenario.getPopupDialogs().getFirst();

            for (int control : FRAMED_CONTROLS) {
                ScrControl found = popup.findControl(control);

                Assertions.assertNotNull(found);
                Assertions.assertTrue(ScrDialogControlUtils.hasFlag(found.getDefinition(), GlueScreen.FRAMED_FLAG),
                                      "The popup's control " + control + " is the screens' framed control.");
            }

            String digest = scenario.capture(name);

            scenario.press(Key.ENTER);

            Assertions.assertFalse(scenario.isPopupOpen());
            Assertions.assertNotNull(community.findChannel(SECOND_CHANNEL), "Enter joins the pressed channel.");
            Assertions.assertNull(community.findChannel(FIRST_CHANNEL), "Joining a channel leaves the previous one.");

            return digest;
        }
    }

    @Test
    void draw_should_match_reference_frames_in_english_and_korean() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Map<String, String> digests = new LinkedHashMap<>();

        digests.put(ENGLISH_FRAME, this.drawFrame(ENGLISH_FRAME, "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX));
        digests.put(KOREAN_FRAME, this.drawFrame(KOREAN_FRAME, "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX));

        List<String> mismatches = new ArrayList<>();

        for (Map.Entry<String, String> reference : REFERENCE_DIGESTS.entrySet()) {
            String digest = digests.get(reference.getKey());

            if (!reference.getValue().equals(digest)) {
                mismatches.add(reference.getKey() + " " + digest);
            }
        }

        Assertions.assertEquals(List.of(), mismatches, "Frames that differ from the reference (see build/glue-screenshots).");
    }
}
