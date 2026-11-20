package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatLineKind;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.configuration.InstallLayout;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.fogofwarlabs.sc4j.scr.net.beaconlink.ScrRoomData;
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
import java.util.*;

/**
 * Checks that the Beacon.Link room list and the game creation over Beacon.Link draw the stored reference frames on a hidden context with
 * frozen time: three joinable rooms with good, fair and unknown latencies (the first private), a full room and one room of another version,
 * with the channel chat under them (more lines than it shows), SD at 640x480 in English (also with the filter settings open, and with the
 * password popup a private room's join opens) and HD at 1920x1080 in Korean; the game creation over a map folder of three maps and a
 * sub-folder with one recently hosted map, its create page with a private game and its map browser, in both; and the local network's game
 * creation over the same folder with a lower turn rate chosen on its turn rate slider, in both; and that the room list's search row starts
 * under its title as the local network game list's list does, that a full room list keeps whole rows above the
 * panel art's frame, that the filter's check box and its label stand on one line, and that a double press on a room while the list slides
 * away starts no join.
 * <p>
 * The reference frames are stored as SHA-256 digests of the read-back pixels (the frames hold installation artwork, which the repository does
 * not keep); every drawn frame is also written to {@code build/glue-screenshots}. The digests are those of the reference machine's OpenGL
 * driver.
 */
@RequiresInstallation
final class RoomListScreenTest {
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
     * The estimated round trip times to the hosts, in milliseconds: good, fair and poor.
     */
    private static final long GOOD_MILLISECONDS = 40;
    private static final long FAIR_MILLISECONDS = 150;
    private static final long POOR_MILLISECONDS = 320;
    private static final int FASTEST_SPEED = 6;
    private static final int MAP_SIZE = 128;
    private static final int SMALL_MAP_SIZE = 96;
    private static final int TOP_VS_BOTTOM_SUBTYPE = 2;
    /**
     * The filter button's center in dialog pixels: right of the search input, left of Create.
     */
    private static final float FILTER_X = 245;
    private static final float FILTER_Y = 107;
    /**
     * The game creation's folder button's center in dialog pixels: right of the map drop-down.
     */
    private static final float FOLDER_BUTTON_X = 359;
    private static final float FOLDER_BUTTON_Y = 168;
    /**
     * The game creation's Private radio button in dialog pixels: the second column of the privacy row.
     */
    private static final float PRIVATE_X = 285;
    private static final float PRIVATE_Y = 126;
    /**
     * A point of the local network's turn rate slider in dialog pixels, on the track left of the thumb, which starts at the highest rate.
     */
    private static final float LAN_TURN_RATE_X = 200;
    private static final float LAN_TURN_RATE_Y = 318;
    /**
     * The room list's Join button's center in dialog pixels (the dialog's Ok).
     */
    private static final float JOIN_X = 516;
    private static final float JOIN_Y = 393;
    private static final byte @NotNull [] JOIN_KEY = new byte[32];
    /**
     * The room list's title and search field, and how far the local network game list's list starts under the top of its title
     * ({@code gluJoin}: the title at {@code 60}, the list at {@code 92}).
     */
    private static final int TITLE = 103;
    private static final int SEARCH = 90;
    /**
     * The room list's Cancel (the dialog's control {@code 10}), and the middle of the first room row below the search field's bottom: the
     * page's inset ({@code 4}), the header ({@code 12}) and half a row ({@code 17}).
     */
    private static final int CANCEL = 10;
    private static final float FIRST_ROW_BELOW_SEARCH = 24;
    private static final float ROW_INSET = 20;
    private static final float CENTER_DIVISOR = 2;
    private static final int LAN_LIST_UNDER_TITLE = 32;
    /**
     * A full room list (more rooms than the list shows) and, at 640x480 where frame pixels are dialog pixels, the band of the list page from
     * the panel art's frame line ({@code glue\create\pListMap4}) down to the page's bottom, across the rows' columns.
     */
    private static final int FULL_ROOM_COUNT = 20;
    /**
     * The room filter's check box (show closed games), the size of its box and the most its box's and its label's centers may differ in frame
     * pixels.
     */
    private static final int VERSIONS_CHECK = 98;
    private static final float CHECK_SIZE = 10;
    private static final float LINE_TOLERANCE = 1;
    private static final int FIRST_EXTRA_HOST_KEY = 10;
    private static final int BAND_LEFT = 50;
    private static final int BAND_RIGHT = 368;
    private static final int BAND_TOP = 314;
    private static final int BAND_BOTTOM = 325;
    /**
     * The maps of the game creation's map folder and its sub-folder.
     */
    private static final @NotNull List<String> MAPS = List.of(GameTestResources.MAP_ARCHIVE_NEW_AVALON, GameTestResources.MAP_ARCHIVE_DRAGON,
                                                              GameTestResources.MAP_EUD_COMPUTER);
    private static final @NotNull String MAP_SUBFOLDER = "Download";
    /**
     * The channel's earlier lines, more than the chat under the rooms shows, so its log scrolls to the end above the input.
     */
    private static final @NotNull List<String> EARLIER_LINES = List.of("hi all", "anyone on Fighting Spirit?", "1v1 me", "gg wp", "rematch?",
                                                                       "sure", "lag again", "fixed now", "host left", "brb");

    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;
    private static final @NotNull String ENGLISH_FRAME = "beaconlink-rooms-640x480-enUS";
    private static final @NotNull String KOREAN_FRAME = "beaconlink-rooms-1920x1080-koKR";
    private static final @NotNull String ENGLISH_FILTER_FRAME = "beaconlink-filter-640x480-enUS";
    private static final @NotNull String KOREAN_FILTER_FRAME = "beaconlink-filter-1920x1080-koKR";
    private static final @NotNull String ENGLISH_PASSWORD_FRAME = "beaconlink-password-640x480-enUS";
    private static final @NotNull String KOREAN_PASSWORD_FRAME = "beaconlink-password-1920x1080-koKR";
    private static final @NotNull String ENGLISH_CREATE_FRAME = "beaconlink-create-640x480-enUS";
    private static final @NotNull String KOREAN_CREATE_FRAME = "beaconlink-create-1920x1080-koKR";
    private static final @NotNull String ENGLISH_MAPS_FRAME = "beaconlink-maps-640x480-enUS";
    private static final @NotNull String KOREAN_MAPS_FRAME = "beaconlink-maps-1920x1080-koKR";
    private static final @NotNull String ENGLISH_LAN_CREATE_FRAME = "lan-create-640x480-enUS";
    private static final @NotNull String KOREAN_LAN_CREATE_FRAME = "lan-create-1920x1080-koKR";
    /**
     * The digests of the reference frames, in drawing order.
     */
    private static final @NotNull Map<String, String> REFERENCE_DIGESTS = Map.ofEntries(
            Map.entry(ENGLISH_FRAME, "7a63775a65e08cf394cdaf9f81e87a00679d9b97c569598ea64fbea4552d114a"),
            Map.entry(KOREAN_FRAME, "25671b30238580a94f384988267edab0d3be9e8995f5ccbfce00dbbfc58bbac4"),
            Map.entry(ENGLISH_FILTER_FRAME, "d6ada3b8ea30941c0ac423286dc6a8aff24c2db44f306c1fb0c96955d0be9215"),
            Map.entry(KOREAN_FILTER_FRAME, "730584fc8b038f1468a89a264e64dd5043eeb9de5986053c738aa6f4ffdb6db1"),
            Map.entry(ENGLISH_PASSWORD_FRAME, "41c89793481840f882e2dad8bf7d1040cf7290ab6ab0f1debd25dd60ef8fc52d"),
            Map.entry(KOREAN_PASSWORD_FRAME, "f8543f4b6eb5e964948e29027042f9f27092deddd26f3bb149793f2c6e8bb245"),
            Map.entry(ENGLISH_CREATE_FRAME, "1540b80aa81f214217c8eedd26c39b204c4d7640418c29abf89043a8af3b3181"),
            Map.entry(KOREAN_CREATE_FRAME, "f60d46492ea99dd457a47f3fd734bb32bde2c208877b22dd8d5f21e10a9619f5"),
            Map.entry(ENGLISH_MAPS_FRAME, "241457698af22b4c271d9670703ecb4f156a3a817f7c3bf19bb7b30eec0bb4d4"),
            Map.entry(KOREAN_MAPS_FRAME, "407d4c490a3f384a1c00c0f4fc9de240ae789f2a082cb378159be1c71a32565b"),
            Map.entry(ENGLISH_LAN_CREATE_FRAME, "205640c89e068d4be8cfd99116269b83709d9e9a00ead7609d064db8c465d0b1"),
            Map.entry(KOREAN_LAN_CREATE_FRAME, "05d3a842c93d2753c6e46c26bc08e8c19dd089a6b41faeaed6bef1c7acf8bcef")
    );

    private static @NotNull PlayerPresence createPlayer(int key, @NotNull String name, @NotNull PlayerStatus status) {
        return RecordingCommunitySession.createPresence(key, name, BeaconLinkGlueScenario.MOD_ID, BeaconLinkGlueScenario.MOD_VERSION, true,
                                                        status, LatencyEstimate.UNKNOWN);
    }

    private static @NotNull LinkParticipant createHost(int key) {
        byte[] bytes = new byte[LinkPlayer.KEY_LENGTH];

        Arrays.fill(bytes, (byte) key);

        return LinkParticipant.of(LinkPlayer.of(bytes), key);
    }

    private static @NotNull FoundRoom createRoom(@NotNull LinkParticipant host, @NotNull String name, @NotNull String map, int players,
                                                 int capacity, @NotNull ScrRoomData data, @Nullable JoinRefusal refusal,
                                                 @NotNull LatencyEstimate latency) {
        return createRoom(host, name, map, players, capacity, data, null, refusal, latency);
    }

    private static @NotNull FoundRoom createRoom(@NotNull LinkParticipant host, @NotNull String name, @NotNull String map, int players,
                                                 int capacity, @NotNull ScrRoomData data, byte @Nullable [] joinKey,
                                                 @Nullable JoinRefusal refusal, @NotNull LatencyEstimate latency) {
        String version = refusal == null ? BeaconLinkGlueScenario.MOD_VERSION : "0.0.9";

        return new FoundRoom(host, 1, new RoomSettings(name, map, players, capacity, data.encode()), BeaconLinkGlueScenario.MOD_ID, version,
                             joinKey, refusal, latency);
    }

    @TempDir
    @Nullable Path directory;

    private @NotNull RecordingCommunitySession createCommunity(@NotNull PlayerPresence kerrigan) {
        PlayerPresence tassadar = createPlayer(TASSADAR_KEY, "Tassadar", PlayerStatus.IN_ROOM);
        RecordingCommunitySession community = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor", PlayerStatus.AVAILABLE));

        community.setChannelSetup(channel -> {
            channel.addMember(kerrigan);
            channel.addMember(tassadar);

            for (int index = 0; index < EARLIER_LINES.size(); index++) {
                channel.receiveLine(index % 2 == 0 ? tassadar : kerrigan, ChatLineKind.SAY, EARLIER_LINES.get(index));
            }

            channel.receiveLine(kerrigan, ChatLineKind.SAY, "made it, come in");
            channel.receiveLine(tassadar, ChatLineKind.SAY, "joining");
        });

        return community;
    }

    private @NotNull List<@NotNull FoundRoom> createRooms(@NotNull PlayerPresence kerrigan) {
        return List.of(createRoom(LinkParticipant.of(kerrigan.getPlayer(), 1L), "Kerrigan's game", "Fighting Spirit", 2, 8,
                                  new ScrRoomData(GameTemplate.TYPE_MELEE, 1, FASTEST_SPEED, MAP_SIZE, MAP_SIZE), JOIN_KEY, null,
                                  LatencyEstimate.of(GOOD_MILLISECONDS)),
                       createRoom(createHost(TASSADAR_KEY), "2v2 team game", "Circuit Breakers", 3, 4,
                                  new ScrRoomData(GameTemplate.TYPE_TOP_VS_BOTTOM, TOP_VS_BOTTOM_SUBTYPE, FASTEST_SPEED, MAP_SIZE, MAP_SIZE), null,
                                  LatencyEstimate.of(FAIR_MILLISECONDS)),
                       createRoom(createHost(ZERATUL_KEY), "Map test", "Ice Fortress", 6, 6,
                                  new ScrRoomData(GameTemplate.TYPE_USE_MAP_SETTINGS, 1, FASTEST_SPEED, SMALL_MAP_SIZE, SMALL_MAP_SIZE), null,
                                  LatencyEstimate.of(POOR_MILLISECONDS)),
                       createRoom(createHost(ARTANIS_KEY), "Free for all", "Python", 5, 8,
                                  new ScrRoomData(GameTemplate.TYPE_FREE_FOR_ALL, 1, FASTEST_SPEED, MAP_SIZE, MAP_SIZE), null,
                                  LatencyEstimate.UNKNOWN),
                       createRoom(createHost(FENIX_KEY), "Old patch game", "Lost Temple", 2, 4,
                                  new ScrRoomData(GameTemplate.TYPE_MELEE, 1, FASTEST_SPEED, MAP_SIZE, MAP_SIZE), JoinRefusal.MOD_VERSION,
                                  LatencyEstimate.of(GOOD_MILLISECONDS)));
    }

    private @NotNull String drawFrame(@NotNull String name, @NotNull String locale, int width, int height, @NotNull String qualityPrefix,
                                      boolean filterOpened) throws IOException {
        return this.drawFrame(name, locale, width, height, qualityPrefix, filterOpened, false);
    }

    /**
     * Draws the room list or the game creation after the channel chat, with the filter settings open or after Join on the selected room
     * (the first, a private room, whose join opens the password popup).
     */
    private @NotNull String drawFrame(@NotNull String name, @NotNull String locale, int width, int height, @NotNull String qualityPrefix,
                                      boolean filterOpened, boolean joined) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.IN_ROOM);
        RecordingCommunitySession community = this.createCommunity(kerrigan);

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(name), locale, width, height, qualityPrefix, community,
                                                                           this.createRooms(kerrigan))) {
            // The chat opens the network and joins the channel the room list shows under the rooms.
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.show(GlueNavigator.Destination.GAME_LIST);

            if (filterOpened) {
                scenario.click(FILTER_X, FILTER_Y);
                scenario.settle();
            }

            if (joined) {
                scenario.click(JOIN_X, JOIN_Y);
                scenario.settle();
            }

            return scenario.capture(name);
        }
    }

    /**
     * Fills the map folder of a frame's install root with the maps and the sub-folder.
     *
     * @return the install root
     */
    private @NotNull Path createMapFolder(@NotNull String name) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        Path installRoot = root.resolve(name);
        Path mapDirectory = InstallLayout.of(installRoot).getMapDirectory();

        Files.createDirectories(mapDirectory.resolve(MAP_SUBFOLDER));

        for (String map : MAPS) {
            Path source = GameTestResources.getPath(map);

            Files.copy(source, mapDirectory.resolve(source.getFileName()));
        }

        return installRoot;
    }

    /**
     * Draws the game creation's create page, then opens its map browser and draws it.
     */
    private void drawCreateFrames(@NotNull Map<String, String> digests, @NotNull String createName, @NotNull String mapsName, @NotNull String locale,
                                  int width, int height, @NotNull String qualityPrefix) throws IOException {
        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.IN_ROOM);
        RecordingCommunitySession community = this.createCommunity(kerrigan);
        Path installRoot = this.createMapFolder(createName);
        InstallLayout layout = InstallLayout.of(installRoot);

        Files.writeString(layout.getRecentMapsFile(), layout.getMapDirectory().resolve(Path.of(MAPS.get(1)).getFileName()).toAbsolutePath() + "\n");

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(installRoot, locale, width, height, qualityPrefix, community,
                                                                           this.createRooms(kerrigan))) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.show(GlueNavigator.Destination.CREATE_GAME);
            scenario.click(PRIVATE_X, PRIVATE_Y);
            scenario.settle();
            digests.put(createName, scenario.capture(createName));
            scenario.click(FOLDER_BUTTON_X, FOLDER_BUTTON_Y);
            digests.put(mapsName, scenario.capture(mapsName));
        }
    }

    /**
     * Draws the local network's game creation over the same map folder after a press on the turn rate slider's track: its map list shares the
     * file rows of the Beacon.Link map browser.
     */
    private @NotNull String drawLanCreateFrame(@NotNull String name, @NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.IN_ROOM);
        Path installRoot = this.createMapFolder(name);

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(installRoot, locale, width, height, qualityPrefix,
                                                                           this.createCommunity(kerrigan))) {
            scenario.show(GlueNavigator.Destination.CREATE_GAME);
            scenario.click(LAN_TURN_RATE_X, LAN_TURN_RATE_Y);

            return scenario.capture(name);
        }
    }

    /**
     * Draws the room list over the given rooms at 640x480 and reads the frame back.
     */
    private @NotNull BufferedImage readRoomList(@NotNull String name, @NotNull List<@NotNull FoundRoom> rooms) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.IN_ROOM);

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(name), "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX,
                                                                           this.createCommunity(kerrigan), rooms)) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.show(GlueNavigator.Destination.GAME_LIST);

            return scenario.readFrame();
        }
    }

    /**
     * Opens the room filter and measures its check box's center and its label's letters' center (frame rows).
     *
     * @return the check box's offset when it is more than the tolerance, empty otherwise
     */
    private @NotNull List<String> findCheckBoxOffset(@NotNull String name, @NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.IN_ROOM);

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(name), locale, width, height, qualityPrefix,
                                                                           this.createCommunity(kerrigan))) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.show(GlueNavigator.Destination.GAME_LIST);
            scenario.click(FILTER_X, FILTER_Y);

            ScrControl check = scenario.getScreenDialog().findControl(VERSIONS_CHECK);

            Assertions.assertNotNull(check);

            DialogControl definition = check.getDefinition();
            BufferedImage frame = scenario.readFrame();
            int top = scenario.getFrameY(definition.getY());
            int bottom = scenario.getFrameY(definition.getY() + definition.getHeight()) - 1;
            int boxRight = scenario.getFrameX(definition.getX() + CHECK_SIZE);
            float box = TextLineCheck.findRedCenter(frame, scenario.getFrameX(definition.getX()), top, boxRight, bottom);
            float glyph = TextLineCheck.findTextCenter(frame, boxRight + 1, top, scenario.getFrameX(definition.getX() + definition.getWidth()) - 1,
                                                       bottom);

            return Math.abs(box - glyph) > LINE_TOLERANCE ? List.of(name + ": box " + box + ", label " + glyph) : List.of();
        }
    }

    @Test
    void filter_check_box_centers_its_box_on_its_label_line() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        List<String> offsets = new ArrayList<>();

        offsets.addAll(this.findCheckBoxOffset("check-small", "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX));
        offsets.addAll(this.findCheckBoxOffset("check-large", "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX));

        Assertions.assertEquals(List.of(), offsets, "Check boxes whose box and label are not centered on one line (frame rows).");
    }

    @Test
    void full_room_list_keeps_its_rows_above_the_panel_frame() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        List<FoundRoom> rooms = new ArrayList<>();

        for (int index = 0; index < FULL_ROOM_COUNT; index++) {
            rooms.add(createRoom(createHost(FIRST_EXTRA_HOST_KEY + index), "Room " + index, "Python", 2, 8,
                                 new ScrRoomData(GameTemplate.TYPE_MELEE, 1, FASTEST_SPEED, MAP_SIZE, MAP_SIZE), null, LatencyEstimate.UNKNOWN));
        }

        BufferedImage empty = this.readRoomList("rows-empty", List.of());
        BufferedImage full = this.readRoomList("rows-full", rooms);
        List<String> drawn = new ArrayList<>();

        for (int y = BAND_TOP; y <= BAND_BOTTOM; y++) {
            for (int x = BAND_LEFT; x <= BAND_RIGHT; x++) {
                if (empty.getRGB(x, y) != full.getRGB(x, y)) {
                    drawn.add(x + "," + y);
                }
            }
        }

        Assertions.assertEquals(List.of(), drawn, "Room rows drawn on or below the panel frame's line.");
    }

    @Test
    void search_row_starts_under_the_title_as_the_local_network_game_list_starts_its_list() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Path root = this.directory;

        Assertions.assertNotNull(root);

        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.IN_ROOM);

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve("search-row"), "enUS", SMALL_WIDTH, SMALL_HEIGHT,
                                                                           GlueCanvas.SD_PREFIX, this.createCommunity(kerrigan))) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.show(GlueNavigator.Destination.GAME_LIST);

            ScrControl title = scenario.getScreenDialog().findControl(TITLE);
            ScrControl search = scenario.getScreenDialog().findControl(SEARCH);

            Assertions.assertNotNull(title);
            Assertions.assertNotNull(search);
            Assertions.assertEquals(LAN_LIST_UNDER_TITLE, search.getDefinition().getY() - title.getDefinition().getY());
        }
    }

    @Test
    void double_press_on_a_room_while_the_list_slides_away_starts_no_join() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Path root = this.directory;

        Assertions.assertNotNull(root);

        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.IN_ROOM);
        FoundRoom room = createRoom(createHost(TASSADAR_KEY), "2v2 team game", "Circuit Breakers", 3, 4,
                                    new ScrRoomData(GameTemplate.TYPE_MELEE, 1, FASTEST_SPEED, MAP_SIZE, MAP_SIZE), null,
                                    LatencyEstimate.of(FAIR_MILLISECONDS));

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve("slide-join"), "enUS", SMALL_WIDTH, SMALL_HEIGHT,
                                                                           GlueCanvas.SD_PREFIX, this.createCommunity(kerrigan), List.of(room))) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.show(GlueNavigator.Destination.GAME_LIST);

            ScrDialog dialog = scenario.getScreenDialog();
            ScrControl cancel = dialog.findControl(CANCEL);
            ScrControl search = dialog.findControl(SEARCH);

            Assertions.assertNotNull(cancel);
            Assertions.assertNotNull(search);

            DialogControl cancelDefinition = cancel.getDefinition();
            DialogControl searchDefinition = search.getDefinition();

            // Cancel slides the list away; a join of the session's recorded network would throw, as it carries no game.
            scenario.tap(cancelDefinition.getX() + cancelDefinition.getWidth() / CENTER_DIVISOR,
                         cancelDefinition.getY() + cancelDefinition.getHeight() / CENTER_DIVISOR);
            scenario.doubleTap(searchDefinition.getX() + ROW_INSET, searchDefinition.getY() + searchDefinition.getHeight() + FIRST_ROW_BELOW_SEARCH);
            scenario.settle();

            Assertions.assertNotSame(dialog, scenario.getScreenDialog(), "the list slid away to the channel chat");
        }
    }

    @Test
    void draw_should_match_reference_frames_in_english_and_korean() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Map<String, String> digests = new LinkedHashMap<>();

        digests.put(ENGLISH_FRAME, this.drawFrame(ENGLISH_FRAME, "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX, false));
        digests.put(KOREAN_FRAME, this.drawFrame(KOREAN_FRAME, "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX, false));
        digests.put(ENGLISH_FILTER_FRAME, this.drawFrame(ENGLISH_FILTER_FRAME, "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX, true));
        digests.put(KOREAN_FILTER_FRAME, this.drawFrame(KOREAN_FILTER_FRAME, "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX, true));
        digests.put(ENGLISH_PASSWORD_FRAME, this.drawFrame(ENGLISH_PASSWORD_FRAME, "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX,
                                                           false, true));
        digests.put(KOREAN_PASSWORD_FRAME, this.drawFrame(KOREAN_PASSWORD_FRAME, "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX,
                                                          false, true));
        this.drawCreateFrames(digests, ENGLISH_CREATE_FRAME, ENGLISH_MAPS_FRAME, "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX);
        this.drawCreateFrames(digests, KOREAN_CREATE_FRAME, KOREAN_MAPS_FRAME, "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX);
        digests.put(ENGLISH_LAN_CREATE_FRAME, this.drawLanCreateFrame(ENGLISH_LAN_CREATE_FRAME, "enUS", SMALL_WIDTH, SMALL_HEIGHT,
                                                                      GlueCanvas.SD_PREFIX));
        digests.put(KOREAN_LAN_CREATE_FRAME, this.drawLanCreateFrame(KOREAN_LAN_CREATE_FRAME, "koKR", LARGE_WIDTH, LARGE_HEIGHT,
                                                                     GlueCanvas.HD_PREFIX));

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
