package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LatencyEstimate;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkFailure;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatLineKind;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.Whisper;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
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
 * Checks that the Beacon.Link channel chat draws the stored reference frames on a hidden context with frozen time: a channel with users of
 * this mod and of another mod with good, fair, poor and unknown latencies, said lines, an emote, a whisper and a join, SD at 640x480 in
 * English and HD at 1920x1080 in Korean; that the status drop-down's Away, chosen over the tabs behind its open list, shows this player
 * away and leaves the shown tab as it was; and that a typed whisper start makes the input whisper to its player (the input's prefix shows
 * the player, Backspace in the empty input says lines in the channel again) and a member's context menu blocks the member into the
 * account's block list file; and that a session refused for too many devices of the account shows the failure, which Ok closes.
 * <p>
 * The reference frames are stored as SHA-256 digests of the read-back pixels (the frames hold installation artwork, which the repository does
 * not keep); every drawn frame is also written to {@code build/glue-screenshots}. The digests are those of the reference machine's OpenGL
 * driver.
 */
@RequiresInstallation
final class ChannelChatScreenTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    /**
     * The key bytes of the players.
     */
    private static final int SELF_KEY = 1;
    private static final int KERRIGAN_KEY = 2;
    private static final int TASSADAR_KEY = 3;
    private static final int ZERATUL_KEY = 4;
    private static final int ARTANIS_KEY = 5;
    private static final int FENIX_KEY = 6;
    /**
     * The estimated round trip times of the players, in milliseconds: good, fair and poor.
     */
    private static final long GOOD_MILLISECONDS = 40;
    private static final long FAIR_MILLISECONDS = 150;
    private static final long POOR_MILLISECONDS = 320;

    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;
    private static final @NotNull String ENGLISH_FRAME = "beaconlink-chat-640x480-enUS";
    private static final @NotNull String KOREAN_FRAME = "beaconlink-chat-1920x1080-koKR";
    private static final @NotNull String ENGLISH_STATUS_FRAME = "beaconlink-status-640x480-enUS";
    private static final @NotNull String KOREAN_STATUS_FRAME = "beaconlink-status-1920x1080-koKR";
    private static final @NotNull String ENGLISH_WHISPER_FRAME = "beaconlink-whisper-640x480-enUS";
    private static final @NotNull String KOREAN_WHISPER_FRAME = "beaconlink-whisper-1920x1080-koKR";
    private static final @NotNull String ENGLISH_BLOCK_FRAME = "beaconlink-blockmenu-640x480-enUS";
    private static final @NotNull String KOREAN_BLOCK_FRAME = "beaconlink-blockmenu-1920x1080-koKR";
    /**
     * The frame of the status drop-down's open list, before the size and locale (written to {@code build/glue-screenshots}, not compared).
     */
    private static final @NotNull String STATUS_LIST_FRAME = "status-list-";
    /**
     * The frame of the too many devices failure, before the size and locale (written to {@code build/glue-screenshots}, not compared).
     */
    private static final @NotNull String TOO_MANY_DEVICES_FRAME = "beaconlink-too-many-devices-";
    /**
     * The status drop-down's bar, its Away item over the Friends tab, and the tab's button, in dialog pixels; a channel the channel list
     * joins by its typed name.
     */
    private static final float STATUS_X = 460;
    private static final float STATUS_Y = 42;
    private static final float AWAY_Y = 72;
    private static final float TAB_BUTTON_X = 500;
    private static final float TAB_BUTTON_Y = 414;
    private static final @NotNull String TYPED_CHANNEL = "Lounge";
    /**
     * The channel tab's third member row (this player, Kerrigan, Tassadar below the channel's label) and the third action of its context
     * menu (Block, after Whisper and Add Friend) below the press that opened it: the name's row and two action rows, then half a row.
     */
    private static final float MEMBER_ROW_X = 500;
    private static final float THIRD_MEMBER_ROW_Y = 171;
    private static final float MENU_X = MEMBER_ROW_X + 20;
    private static final float BLOCK_ACTION_Y = THIRD_MEMBER_ROW_Y + 63;
    /**
     * The digests of the reference frames, in drawing order.
     */
    private static final @NotNull Map<String, String> REFERENCE_DIGESTS = Map.of(
            ENGLISH_FRAME, "fd6c2988a24d19974f7d332910f1d64729a453f0fa58b17a160a3ff8310e01bb",
            KOREAN_FRAME, "216c6442de6177ae32f7c79b3aa5143b7e2eb765fd1546823ab38cb55912ebad",
            ENGLISH_STATUS_FRAME, "b05825b11a873c50533c2b11cf66b2f4f1d73b3bd70eeaf9eeeef575c8601ea0",
            KOREAN_STATUS_FRAME, "0b98ad04403d24c9888acda8e7489e23c480bd2d3aa5cabc0fac3c216206bcb0",
            ENGLISH_WHISPER_FRAME, "6008955761293ac7be2af1799c6fb05b58c92f1827d8ab032ca4b39df067e3fe",
            KOREAN_WHISPER_FRAME, "206c7674392081c1159fe93bfb7006d0411b45e76e2b3c46fb73df4d24e0bd54",
            ENGLISH_BLOCK_FRAME, "495838d63faed5f278e2f422c938b78bac771a685e403945ffbb6bc3cfc37614",
            KOREAN_BLOCK_FRAME, "80b8400255026b5172170bf0140c2578d5871cd864149b18294ea745e8b6c9ed"
    );

    private static @NotNull PlayerPresence createPlayer(int key, @NotNull String name, @NotNull PlayerStatus status) {
        return createPlayer(key, name, status, LatencyEstimate.UNKNOWN);
    }

    private static @NotNull PlayerPresence createPlayer(int key, @NotNull String name, @NotNull PlayerStatus status, @NotNull LatencyEstimate latency) {
        return RecordingCommunitySession.createPresence(key, name, BeaconLinkGlueScenario.MOD_ID, BeaconLinkGlueScenario.MOD_VERSION, true,
                                                        status, latency);
    }

    private static void assertReferenceDigests(@NotNull Map<String, String> digests) {
        List<String> mismatches = new ArrayList<>();

        for (Map.Entry<String, String> drawn : digests.entrySet()) {
            if (!drawn.getValue().equals(REFERENCE_DIGESTS.get(drawn.getKey()))) {
                mismatches.add(drawn.getKey() + " " + drawn.getValue());
            }
        }

        Assertions.assertEquals(List.of(), mismatches, "Frames that differ from the reference (see build/glue-screenshots).");
    }

    @TempDir
    @Nullable Path directory;

    private @NotNull String drawFrame(@NotNull String name, @NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.AVAILABLE, LatencyEstimate.of(GOOD_MILLISECONDS));
        PlayerPresence tassadar = createPlayer(TASSADAR_KEY, "Tassadar", PlayerStatus.AWAY, LatencyEstimate.of(FAIR_MILLISECONDS));
        PlayerPresence zeratul = RecordingCommunitySession.createPresence(ZERATUL_KEY, "Zeratul", "scrdemo", "0.0.9", false, PlayerStatus.IN_GAME,
                                                                          LatencyEstimate.of(POOR_MILLISECONDS));
        PlayerPresence artanis = createPlayer(ARTANIS_KEY, "Artanis", PlayerStatus.IN_ROOM);
        PlayerPresence fenix = createPlayer(FENIX_KEY, "Fenix", PlayerStatus.AVAILABLE, LatencyEstimate.of(GOOD_MILLISECONDS));
        RecordingCommunitySession community = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor", PlayerStatus.AVAILABLE));

        community.addFriend(kerrigan.getPlayer());
        community.setChannelSetup(channel -> {
            channel.addMember(kerrigan);
            channel.addMember(tassadar);
            channel.addMember(zeratul);
            channel.addMember(artanis);
            channel.receiveLine(community.getSelf(), ChatLineKind.SAY, "anyone for 2v2 on Fighting Spirit?");
            channel.receiveLine(kerrigan, ChatLineKind.SAY, "sure, make it");
            channel.receiveLine(tassadar, ChatLineKind.EMOTE, "waves");
            channel.receiveLine(zeratul, ChatLineKind.SAY, "gg last one");
        });

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(name), locale, width, height, qualityPrefix, community)) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);

            RecordingCommunitySession.Channel channel = community.findChannel(ChannelChat.findDefaultChannel("StarCraft Remastered", List.of()));

            Assertions.assertNotNull(channel);

            community.receiveWhisper(kerrigan, "join mine in 1m");
            channel.addMember(fenix);
            scenario.settle();

            return scenario.capture(name);
        }
    }

    /**
     * Opens the status drop-down, chooses Away where its list lies over the Friends tab, then opens the tab's popup and joins a typed
     * channel: only the channel tab's popup (the channel list) joins one.
     */
    private @NotNull String chooseAway(@NotNull String name, @NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        RecordingCommunitySession community = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor", PlayerStatus.AVAILABLE));

        community.setChannelSetup(channel -> channel.addMember(createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.AVAILABLE)));

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(name), locale, width, height, qualityPrefix, community)) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.click(STATUS_X, STATUS_Y);
            scenario.capture(STATUS_LIST_FRAME + width + "x" + height + "-" + locale);
            scenario.click(STATUS_X, AWAY_Y);

            Assertions.assertEquals(PlayerStatus.AWAY, community.getSelf().getStatus(), name);

            String digest = scenario.capture(name);

            scenario.click(TAB_BUTTON_X, TAB_BUTTON_Y);
            scenario.type(TYPED_CHANNEL);
            scenario.press(Key.ENTER);

            Assertions.assertNotNull(community.findChannel(TYPED_CHANNEL), name);

            return digest;
        }
    }

    /**
     * Types a whisper start and two lines (both whispered), ends the whisper with Backspace and says a line; then blocks Tassadar from the
     * context menu. Returns the digests of the whisper input and of the open menu with Block hovered.
     */
    private @NotNull List<String> whisperAndBlock(@NotNull String whisperName, @NotNull String blockName, @NotNull String locale, int width,
                                                  int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        Path install = root.resolve(whisperName);
        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.AVAILABLE);
        PlayerPresence tassadar = createPlayer(TASSADAR_KEY, "Tassadar", PlayerStatus.AVAILABLE);
        RecordingCommunitySession community = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor", PlayerStatus.AVAILABLE));

        community.setChannelSetup(channel -> {
            channel.addMember(kerrigan);
            channel.addMember(tassadar);
        });

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(install, locale, width, height, qualityPrefix, community)) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);

            RecordingCommunitySession.Channel channel = community.findChannel(ChannelChat.findDefaultChannel("StarCraft Remastered", List.of()));

            Assertions.assertNotNull(channel);

            scenario.type("/w " + kerrigan.getTaggedName() + " gl hf");

            String whisperDigest = scenario.capture(whisperName);

            scenario.press(Key.ENTER);
            scenario.type("again");
            scenario.press(Key.ENTER);
            scenario.press(Key.BACKSPACE);
            scenario.type("hello");
            scenario.press(Key.ENTER);

            Assertions.assertEquals(List.of("gl hf", "again"), community.getWhispers().stream().map(Whisper::getText).toList(), whisperName);
            Assertions.assertEquals(List.of("hello"), channel.getSaid(), whisperName);

            scenario.rightClick(MEMBER_ROW_X, THIRD_MEMBER_ROW_Y);
            scenario.hover(MENU_X, BLOCK_ACTION_Y);

            String blockDigest = scenario.capture(blockName);

            scenario.click(MENU_X, BLOCK_ACTION_Y);

            Assertions.assertEquals(List.of(tassadar.getPlayer()), community.getIgnored(), blockName);

            return List.of(whisperDigest, blockDigest);
        }
    }

    @Test
    void too_many_devices_shows_the_failure_and_ok_closes_it() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        this.showTooManyDevices(TOO_MANY_DEVICES_FRAME + SMALL_WIDTH + "x" + SMALL_HEIGHT + "-enUS", "enUS", SMALL_WIDTH, SMALL_HEIGHT,
                                GlueCanvas.SD_PREFIX);
        this.showTooManyDevices(TOO_MANY_DEVICES_FRAME + LARGE_WIDTH + "x" + LARGE_HEIGHT + "-koKR", "koKR", LARGE_WIDTH, LARGE_HEIGHT,
                                GlueCanvas.HD_PREFIX);
    }

    private void showTooManyDevices(@NotNull String name, @NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        RecordingCommunitySession community = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor", PlayerStatus.AVAILABLE));

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(name), locale, width, height, qualityPrefix, community)) {
            scenario.getBeaconLink().setFailure(LinkFailure.TOO_MANY_CONNECTIONS);
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);

            Assertions.assertTrue(scenario.isPopupOpen(), name);

            scenario.capture(name);
            scenario.press(Key.ENTER);
            scenario.settle();

            Assertions.assertFalse(scenario.isPopupOpen(), name);
        }
    }

    @Test
    void draw_should_match_reference_frames_in_english_and_korean() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Map<String, String> digests = new LinkedHashMap<>();

        digests.put(ENGLISH_FRAME, this.drawFrame(ENGLISH_FRAME, "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX));
        digests.put(KOREAN_FRAME, this.drawFrame(KOREAN_FRAME, "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX));

        assertReferenceDigests(digests);
    }

    @Test
    void status_drop_down_sets_away_without_pressing_the_tab_behind_its_list() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Map<String, String> digests = new LinkedHashMap<>();

        digests.put(ENGLISH_STATUS_FRAME, this.chooseAway(ENGLISH_STATUS_FRAME, "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX));
        digests.put(KOREAN_STATUS_FRAME, this.chooseAway(KOREAN_STATUS_FRAME, "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX));

        assertReferenceDigests(digests);
    }

    @Test
    void typed_whisper_start_and_context_menu_block_follow_the_input_prefix_and_the_block_list() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Map<String, String> digests = new LinkedHashMap<>();
        List<String> english = this.whisperAndBlock(ENGLISH_WHISPER_FRAME, ENGLISH_BLOCK_FRAME, "enUS", SMALL_WIDTH, SMALL_HEIGHT,
                                                    GlueCanvas.SD_PREFIX);
        List<String> korean = this.whisperAndBlock(KOREAN_WHISPER_FRAME, KOREAN_BLOCK_FRAME, "koKR", LARGE_WIDTH, LARGE_HEIGHT,
                                                   GlueCanvas.HD_PREFIX);

        digests.put(ENGLISH_WHISPER_FRAME, english.getFirst());
        digests.put(ENGLISH_BLOCK_FRAME, english.getLast());
        digests.put(KOREAN_WHISPER_FRAME, korean.getFirst());
        digests.put(KOREAN_BLOCK_FRAME, korean.getLast());

        assertReferenceDigests(digests);
    }
}
