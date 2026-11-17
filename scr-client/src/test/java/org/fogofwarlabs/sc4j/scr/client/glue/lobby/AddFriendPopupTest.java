package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
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
 * Checks that the chat's friends tab (opened at its tab: an online friend selected, a friend of another mod in a game and an offline
 * friend) and the add-friend popup (opened by the tab's Add Friend button: the suggested channel members, one pressed into the name input)
 * draw the stored reference frames on a hidden context with frozen time, and that Enter adds the pressed member and closes the popup; then
 * that the right button opens the first friend's context menu (Whisper, Remove Friend; Remove Friend hovered), drawn as a frame too, whose
 * Remove Friend removes the friend: SD at 640x480 in English and HD at 1920x1080 in Korean.
 * <p>
 * The reference frames are stored as SHA-256 digests of the read-back pixels (the frames hold installation artwork, which the repository does
 * not keep); every drawn frame is also written to {@code build/glue-screenshots}. The digests are those of the reference machine's OpenGL
 * driver.
 */
@RequiresInstallation
final class AddFriendPopupTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int SELF_KEY = 1;
    private static final int KERRIGAN_KEY = 2;
    private static final int TASSADAR_KEY = 3;
    private static final int ZERATUL_KEY = 4;
    private static final int ARTANIS_KEY = 5;
    /**
     * The chat's friends tab, the friends tab's first friend (below the Online group's label), the tab's button and the popup's first
     * suggested member (below the group's label), in dialog pixels.
     */
    private static final float FRIENDS_TAB_X = 445;
    private static final float FRIENDS_TAB_Y = 72;
    private static final float TAB_ROW_X = 500;
    private static final float FIRST_FRIEND_ROW_Y = 123;
    private static final float TAB_BUTTON_X = 500;
    private static final float TAB_BUTTON_Y = 414;
    private static final float POPUP_ROW_X = 250;
    private static final float FIRST_SUGGESTED_ROW_Y = 115;
    /**
     * The context menu's second action (Remove Friend) below the press that opened it: the name's row and one action row, then half a row.
     */
    private static final float MENU_X = TAB_ROW_X + 20;
    private static final float REMOVE_ACTION_Y = FIRST_FRIEND_ROW_Y + 45;

    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;
    private static final @NotNull String ENGLISH_TAB_FRAME = "beaconlink-friends-640x480-enUS";
    private static final @NotNull String KOREAN_TAB_FRAME = "beaconlink-friends-1920x1080-koKR";
    private static final @NotNull String ENGLISH_POPUP_FRAME = "beaconlink-addfriend-640x480-enUS";
    private static final @NotNull String KOREAN_POPUP_FRAME = "beaconlink-addfriend-1920x1080-koKR";
    private static final @NotNull String ENGLISH_MENU_FRAME = "beaconlink-friendmenu-640x480-enUS";
    private static final @NotNull String KOREAN_MENU_FRAME = "beaconlink-friendmenu-1920x1080-koKR";
    /**
     * The digests of the reference frames, in drawing order.
     */
    private static final @NotNull Map<String, String> REFERENCE_DIGESTS = Map.of(
            ENGLISH_TAB_FRAME, "3a8ee47a08eba5ed5db54753d3d445adff8244296208b2a0186170ce7615fd92",
            ENGLISH_POPUP_FRAME, "1cfcbf011bc6ebcad2e25b21ba684d639b2aff5bb5b6f0600974a20eec24e71b",
            KOREAN_TAB_FRAME, "0bc07d95e266a0c205023f4a5f9ceae0d0006822a50fdd05d9c6d7d247aa3546",
            KOREAN_POPUP_FRAME, "559aa650c0be7c1b6d807209473289bc615d09c2f80ab212cff550a36466580e",
            ENGLISH_MENU_FRAME, "19dae793e8a2a63c47c023fd7317a4e80ba6f98d802c2574ca162856f67a2a71",
            KOREAN_MENU_FRAME, "02ed077c09836e0582072ca376ea1b9f731c0afbc6f81d13791c934b45a045eb"
    );

    private static @NotNull PlayerPresence createPlayer(int key, @NotNull String name, @NotNull PlayerStatus status) {
        return RecordingCommunitySession.createPresence(key, name, BeaconLinkGlueScenario.MOD_ID, BeaconLinkGlueScenario.MOD_VERSION, true,
                                                        status);
    }

    @TempDir
    @Nullable Path directory;

    private void drawFrames(@NotNull Map<String, String> digests, @NotNull String tabFrame, @NotNull String popupFrame, @NotNull String menuFrame,
                            @NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.AVAILABLE);
        PlayerPresence tassadar = createPlayer(TASSADAR_KEY, "Tassadar", PlayerStatus.AWAY);
        PlayerPresence zeratul = RecordingCommunitySession.createPresence(ZERATUL_KEY, "Zeratul", "scrdemo", "0.0.9", false, PlayerStatus.IN_GAME);
        PlayerPresence artanis = createPlayer(ARTANIS_KEY, "Artanis", PlayerStatus.AVAILABLE);
        RecordingCommunitySession community = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor", PlayerStatus.AVAILABLE));

        community.addFriend(kerrigan.getPlayer());
        community.addFriend(zeratul.getPlayer());
        community.addFriend(artanis.getPlayer());
        community.setChannelSetup(channel -> {
            channel.addMember(kerrigan);
            channel.addMember(tassadar);
            channel.addMember(zeratul);
        });

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(tabFrame), locale, width, height, qualityPrefix, community)) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.click(FRIENDS_TAB_X, FRIENDS_TAB_Y);
            scenario.click(TAB_ROW_X, FIRST_FRIEND_ROW_Y);
            digests.put(tabFrame, scenario.capture(tabFrame));
            scenario.click(TAB_BUTTON_X, TAB_BUTTON_Y);
            scenario.click(POPUP_ROW_X, FIRST_SUGGESTED_ROW_Y);

            Assertions.assertTrue(scenario.isPopupOpen());

            digests.put(popupFrame, scenario.capture(popupFrame));
            scenario.press(Key.ENTER);

            Assertions.assertEquals(List.of(kerrigan.getPlayer(), zeratul.getPlayer(), artanis.getPlayer(), tassadar.getPlayer()), community.getFriends());
            Assertions.assertFalse(scenario.isPopupOpen());

            scenario.rightClick(TAB_ROW_X, FIRST_FRIEND_ROW_Y);
            scenario.hover(MENU_X, REMOVE_ACTION_Y);
            digests.put(menuFrame, scenario.capture(menuFrame));
            scenario.click(MENU_X, REMOVE_ACTION_Y);

            Assertions.assertEquals(List.of(zeratul.getPlayer(), artanis.getPlayer(), tassadar.getPlayer()), community.getFriends());
        }
    }

    @Test
    void draw_should_match_reference_frames_in_english_and_korean() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Map<String, String> digests = new LinkedHashMap<>();

        this.drawFrames(digests, ENGLISH_TAB_FRAME, ENGLISH_POPUP_FRAME, ENGLISH_MENU_FRAME, "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX);
        this.drawFrames(digests, KOREAN_TAB_FRAME, KOREAN_POPUP_FRAME, KOREAN_MENU_FRAME, "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX);

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
