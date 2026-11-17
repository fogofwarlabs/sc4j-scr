package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Checks that the Beacon.Link popups with a framed list (the channel list and the add-friend popup) keep one space between the list's frame
 * and its first row's content, measured in their frames drawn on a hidden context: SD at 640x480 in English.
 */
@RequiresInstallation
final class BeaconLinkPopupTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int SELF_KEY = 1;
    private static final int KERRIGAN_KEY = 2;
    private static final int TASSADAR_KEY = 3;
    private static final int CHANNEL_MEMBERS = 12;
    /**
     * The chat's friends tab and the tab's button (Join Channel on the channel tab, Add Friend on the friends tab), in dialog pixels.
     */
    private static final float FRIENDS_TAB_X = 445;
    private static final float FRIENDS_TAB_Y = 72;
    private static final float TAB_BUTTON_X = 500;
    private static final float TAB_BUTTON_Y = 414;
    /**
     * The popups' page.
     */
    private static final int PAGE = 11;
    /**
     * A frame pixel is red: its red channel at least this and at least this many times each of the others; a content pixel is lit: a
     * channel at least {@link #LIT_MINIMUM}, not frame red.
     */
    private static final int FRAME_RED_MINIMUM = 60;
    private static final int FRAME_RED_FACTOR = 3;
    private static final int LIT_MINIMUM = 64;
    private static final int BYTE_MASK = 0xFF;
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;
    private static final int CENTER_DIVISOR = 2;
    /**
     * The most the two lists' spaces may differ in pixels: the first ink of a row's content (an icon, a symbol) sits differently in its box.
     */
    private static final int PADDING_TOLERANCE = 3;

    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    private static boolean isFrameRed(int color) {
        int red = color >> RED_SHIFT & BYTE_MASK;
        int green = color >> GREEN_SHIFT & BYTE_MASK;
        int blue = color & BYTE_MASK;

        return red >= FRAME_RED_MINIMUM && green * FRAME_RED_FACTOR <= red && blue * FRAME_RED_FACTOR <= red;
    }

    private static boolean isLit(int color) {
        int brightest = Math.max(color >> RED_SHIFT & BYTE_MASK, Math.max(color >> GREEN_SHIFT & BYTE_MASK, color & BYTE_MASK));

        return brightest >= LIT_MINIMUM && !isFrameRed(color);
    }

    /**
     * Measures the space between a popup's list frame and the content of its first row: the frame's left and top lines found from the middle
     * of the page (inside the list, under its rows) and the first lit column and row inside them.
     *
     * @return the left and the top space in pixels
     */
    private static int @NotNull [] measurePadding(@NotNull BeaconLinkGlueScenario scenario) {
        ScrControl page = scenario.getPopupDialogs().getFirst().findControl(PAGE);

        Assertions.assertNotNull(page);

        DialogControl definition = page.getDefinition();
        BufferedImage frame = scenario.readFrame();
        int middleX = scenario.getFrameX(definition.getX() + definition.getWidth() / (float) CENTER_DIVISOR);
        int middleY = scenario.getFrameY(definition.getY() + definition.getHeight() / (float) CENTER_DIVISOR);
        int frameLeft = middleX;
        int frameTop = middleY;

        while (!isFrameRed(frame.getRGB(frameLeft, middleY))) {
            frameLeft--;
        }

        while (!isFrameRed(frame.getRGB(middleX, frameTop))) {
            frameTop--;
        }

        int contentLeft = middleX;
        int contentTop = middleY;

        for (int row = frameTop + 1; row < middleY; row++) {
            for (int column = frameLeft + 1; column < middleX; column++) {
                if (isLit(frame.getRGB(column, row))) {
                    contentLeft = Math.min(contentLeft, column);
                    contentTop  = Math.min(contentTop, row);
                }
            }
        }

        return new int[]{contentLeft - frameLeft, contentTop - frameTop};
    }

    private static @NotNull PlayerPresence createPlayer(int key, @NotNull String name) {
        return RecordingCommunitySession.createPresence(key, name, BeaconLinkGlueScenario.MOD_ID, BeaconLinkGlueScenario.MOD_VERSION, true,
                                                        PlayerStatus.AVAILABLE);
    }

    @TempDir
    @Nullable Path directory;

    private @NotNull Path requireDirectory() {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        return root;
    }

    @Test
    void channel_list_and_add_friend_popups_keep_one_space_inside_their_list_frames() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan");
        PlayerPresence tassadar = createPlayer(TASSADAR_KEY, "Tassadar");
        RecordingCommunitySession channels = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor"));
        RecordingCommunitySession friends = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor"));
        int[] channelPadding;
        int[] friendPadding;

        channels.addSummary(ChannelChat.SHARED_CHANNEL, CHANNEL_MEMBERS);
        channels.setChannelSetup(channel -> channel.addMember(kerrigan));
        friends.setChannelSetup(channel -> channel.addMember(tassadar));

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(this.requireDirectory().resolve("channels"), "enUS", WIDTH, HEIGHT,
                                                                           GlueCanvas.SD_PREFIX, channels)) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.click(TAB_BUTTON_X, TAB_BUTTON_Y);

            Assertions.assertTrue(scenario.isPopupOpen());

            channelPadding = measurePadding(scenario);
        }

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(this.requireDirectory().resolve("friends"), "enUS", WIDTH, HEIGHT,
                                                                           GlueCanvas.SD_PREFIX, friends)) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.click(FRIENDS_TAB_X, FRIENDS_TAB_Y);
            scenario.click(TAB_BUTTON_X, TAB_BUTTON_Y);

            Assertions.assertTrue(scenario.isPopupOpen());

            friendPadding = measurePadding(scenario);
        }

        String spaces = "channel list " + channelPadding[0] + "," + channelPadding[1] + ", add friend " + friendPadding[0] + "," + friendPadding[1];

        Assertions.assertTrue(Math.abs(channelPadding[0] - friendPadding[0]) <= PADDING_TOLERANCE, "Left spaces differ: " + spaces);
        Assertions.assertTrue(Math.abs(channelPadding[1] - friendPadding[1]) <= PADDING_TOLERANCE, "Top spaces differ: " + spaces);
    }
}
