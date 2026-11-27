package org.fogofwarlabs.sc4j.scr.client.glue.campaign;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.video.VideoClipDecoder;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.glue.*;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.DialogPainter;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.client.ui.portrait.PortraitPlayer;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.casc.SoundPathResolver;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.dat.PortdataDat;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.session.setup.CampaignMissions;
import org.fogofwarlabs.sc4j.scr.game.trigger.BriefingSession;
import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.fogofwarlabs.sc4j.scr.net.lan.LanLobby;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFont;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Runs campaign or custom-map briefings with text, sound and portrait controls before starting the game.
 * <p>
 * Network rooms wait for every player to become ready; local rooms also support replaying the briefing and skipping
 * a tutorial. Portrait frames advance on the briefing timer and use the selected asset set.
 */
public final class ReadyRoomScreen extends GlueScreen {
    private static final @NotNull Logger LOGGER = Logger.of(ReadyRoomScreen.class);

    private static final int START = 13;
    private static final int CANCEL = 14;
    private static final int SKIP_TUTORIAL = 19;
    private static final int REPLAY = 20;
    /**
     * The button panel under Replay ({@code glue\Ready<R>\But<Race>.pcx}).
     */
    private static final int BUTTON_PANEL = 5;
    private static final int FIRST_PORTRAIT = 15;
    private static final int TRANSMISSION_TEXT = -10;
    private static final int OBJECTIVES = -11;
    private static final int TUTORIAL_PANEL = -13;
    private static final int NO_BRIEFING = -14;
    /**
     * The waiting text shown after Start in a network game.
     */
    private static final int WAITING = -12;
    /**
     * The opening panel identifiers paired with their slide directions.
     */
    private static final int @NotNull [] SWISH = {5, 3, 6, 0, 7, 3, 9, 0, 10, 0, 11, 2, 12, 2};
    private static final int SWISH_DELAY_MILLISECONDS = 80;
    /**
     * The transmission text scrolls until its last half box of lines is in the box: the box height is divided by this to get that part.
     */
    private static final int SCROLL_END_BOX_DIVISOR = 2;

    /**
     * The ready room races: the dialog, palette, music and portrait frames of each.
     */
    private static final int RACE_ZERG = 0;
    private static final int RACE_TERRAN = 1;
    private static final int RACE_PROTOSS = 2;
    private static final @NotNull String ZERG_DIALOG = "GluRdyZ";
    private static final @NotNull String TERRAN_DIALOG = "gluRdyT";
    private static final @NotNull String PROTOSS_DIALOG = "gluRdyP";
    private static final @NotNull String ZERG_PALETTE_FOLDER = "glue/palrz";
    private static final @NotNull String TERRAN_PALETTE_FOLDER = "glue/palrt";
    private static final @NotNull String PROTOSS_PALETTE_FOLDER = "glue/palrp";
    private static final @NotNull String ZERG_MUSIC = "zrdyroom";
    private static final @NotNull String TERRAN_MUSIC = "trdyroom";
    private static final @NotNull String PROTOSS_MUSIC = "prdyroom";
    private static final @NotNull String ZERG_FRAME_PREFIX = "glue/readyz/z";
    private static final @NotNull String TERRAN_FRAME_PREFIX = "glue/readyt/t";
    private static final @NotNull String PROTOSS_FRAME_PREFIX = "glue/readyp/p";
    /**
     * The portrait frame images: {@code <prefix>frame<slot>.pcx}, {@code <prefix>frameh<slot>.pcx} while the slot speaks.
     */
    private static final @NotNull String SILENT_FRAME = "frame";
    private static final @NotNull String SPEAKING_FRAME = "frameh";
    private static final @NotNull String FRAME_EXTENSION = ".pcx";

    /**
     * The portrait clip box width and height in interface pixels.
     */
    private static final int PORTRAIT_WIDTH = 120;
    private static final int PORTRAIT_HEIGHT = 112;
    /**
     * The portrait dimensions used to center its box within the inclusive control rectangle.
     */
    private static final int PORTRAIT_INSET_WIDTH = 0x77;
    private static final int PORTRAIT_INSET_HEIGHT = 0x6f;
    /**
     * The talking animation lasts while the slot speaks; the briefing ends it.
     */
    private static final int TALKING_TIME_DIVISOR = 4;
    private static final long TALKING_UNTIL_STOPPED = Long.MAX_VALUE / TALKING_TIME_DIVISOR;
    private static final int CENTER_DIVISOR = 2;
    private static final int OPAQUE_WHITE = 0xFFFFFFFF;
    /**
     * The portrait folder inside the asset set's prefix.
     */
    private static final @NotNull String PORTRAIT_FOLDER = "portrait/";
    private static final @NotNull String PORTRAIT_TEXTURE_KEY_PREFIX = "portrait@";
    private static final @NotNull String KEY_SEPARATOR = "#";

    /**
     * The OWNR slot types ({@code chk}): {@code 5} computer, {@code 6} human.
     */
    private static final int OWNER_COMPUTER = 5;
    private static final int OWNER_HUMAN = 6;
    /**
     * The SIDE race of a slot whose player chooses the race.
     */
    private static final int SELECTABLE_RACE = 5;
    private static final @NotNull String SCENARIO_EXTENSION = ".chk";
    private static final int PLAYER_SLOTS = PlayerTable.PLAYABLE_PLAYER_SLOTS;
    private static final @NotNull String UNITS_FILE = "arr/units.dat";
    /**
     * A custom map briefs as campaign mission {@code 0}, whose sound paths are the map strings themselves.
     */
    private static final int CUSTOM_MAP_MISSION = 0;
    private static final @NotNull String CUSTOM_MAP_DIRECTORY = "";

    /**
     * Creates a {@link ReadyRoomScreen} instance from the custom map and the local human or network lobby race.
     *
     * @param request          the custom game the room's Start button starts
     * @param mapCompatibility whether the map opens with the relaxed {@code 1.16}-era rules
     * @return the created {@link ReadyRoomScreen} instance
     * @throws IOException if the request has no map or the map cannot be read
     */
    public static @NotNull ReadyRoomScreen createForCustomGame(@NotNull GlueGameRequest request, boolean mapCompatibility) throws IOException {
        Path path = request.getPath();

        if (path == null) {
            throw Exceptions.newIOException("The custom game has no map.");
        }

        MpqArchive archive = null;
        byte[] scenario;

        if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(SCENARIO_EXTENSION)) {
            scenario = Files.readAllBytes(path);
        } else {
            archive  = MpqArchive.open(path, mapCompatibility);
            scenario = archive.readFile(ChkFile.ARCHIVE_ENTRY);
        }

        ChkFile map = ChkFile.parseTolerant(scenario);
        Player[] players = new Player[PLAYER_SLOTS];
        int localPlayer = readPlayers(map, players);
        int race = players[localPlayer].getRace();

        if (race == SELECTABLE_RACE) {
            race = request.getRace();
        }

        LanClient lanClient = request.getLanClient();
        LanLobby lobby = lanClient == null ? null : lanClient.getLobby();
        ProviderSession session = lanClient == null ? null : lanClient.getSession();

        if (lobby != null && session != null) {
            // A network game: the local player's lobby record.
            int localSlot = lobby.getPlayerSlot(session.getLocalIdentifier());

            if (localSlot >= 0 && localSlot < PLAYER_SLOTS) {
                race = lobby.getRecord(localSlot).getRace();
            }
        }

        int room = race == RACE_ZERG ? RACE_ZERG : race == RACE_PROTOSS ? RACE_PROTOSS : RACE_TERRAN;

        return new ReadyRoomScreen(room, null, request, map, archive);
    }

    /**
     * Builds the eight players of the map's slots (OWNR types, SIDE races, FORC forces) for the briefing's force owners.
     *
     * @param map     the parsed scenario
     * @param players the initially nullable player slots, all populated on normal completion
     * @return the local player: the first human slot, or slot {@code 0} when none is human
     */
    static int readPlayers(@NotNull ChkFile map, @Nullable Player @NotNull [] players) {
        byte[] owners = map.getSection(ChkFile.OWNER_SECTION);
        byte[] sides = map.getSection(ChkFile.RACE_SECTION);
        byte[] forces = map.getSection(ChkFile.FORCE_SECTION);
        int localPlayer = -1;

        for (int slot = 0; slot < PLAYER_SLOTS; slot++) {
            int owner = owners != null && slot < owners.length ? Byte.toUnsignedInt(owners[slot]) : 0;
            int race = sides != null && slot < sides.length ? Byte.toUnsignedInt(sides[slot]) : 0;
            int force = forces != null && slot < forces.length ? Byte.toUnsignedInt(forces[slot]) : 0;
            int type = owner == OWNER_HUMAN ? PlayerTypes.HUMAN : owner == OWNER_COMPUTER ? PlayerTypes.COMPUTER : owner;

            if (owner == OWNER_HUMAN && localPlayer < 0) {
                localPlayer = slot;
            }

            // The briefing compares the team with Force 1..4 (owners 18..21): the team is the FORC index plus one.
            players[slot] = new Player(slot, slot, type, race, force + 1);
        }

        return Math.max(localPlayer, 0);
    }

    /**
     * Selects the asset of a ready room race.
     *
     * @param race    the ready room race
     * @param zerg    the Zerg asset
     * @param terran  the Terran asset
     * @param protoss the asset of any other race
     * @return the asset of the race
     */
    private static @NotNull String selectByRace(int race, @NotNull String zerg, @NotNull String terran, @NotNull String protoss) {
        return race == RACE_ZERG ? zerg : race == RACE_TERRAN ? terran : protoss;
    }

    /**
     * Measures the height of a text wrapped at the width of the transmission text control.
     *
     * @param font    the control's font
     * @param control the transmission text control
     * @param text    the text
     * @return the wrapped text height in pixels
     */
    private static int measureWrappedHeight(@NotNull InterfaceFont font, @NotNull DialogControl control, @NotNull String text) {
        List<String> lines = InterfaceText.wrap(font, text, control.getWidth());

        return (int) InterfaceText.getLayoutHeight(font, lines, InterfaceText.LEFT | InterfaceText.TOP);
    }

    private final int race;
    private final CampaignEpisodes.@Nullable Entry entry;
    private final @Nullable GlueGameRequest customGame;
    private final @Nullable ChkFile customMap;
    private final @Nullable MpqArchive mapArchive;

    private @Nullable BriefingSession briefing;
    private @Nullable String campaignDirectory;
    private boolean briefingStarted;
    private boolean stopped;

    /**
     * The transmission text as shown when the briefing measured it for its scroll, and its height.
     */
    private @Nullable String measuredText;
    private int measuredHeight;
    /**
     * The transmission text as last drawn, and its height.
     */
    private @Nullable String drawnText;
    private int drawnHeight;

    private final @Nullable PortraitPlayer @NotNull [] portraits;
    private final boolean @NotNull [] speaking;
    private final PortraitPlayer.@Nullable Frame @NotNull [] portraitFrames;
    private final int @NotNull [] portraitTextures;
    private final @Nullable String @NotNull [] portraitTextureKeys;

    private @Nullable ScrControl skipTutorialButton;
    private @Nullable ScrControl waitingLabel;
    private @Nullable ScrControl noBriefingLabel;
    private @Nullable ScrControl transmissionText;
    private @Nullable ScrControl objectivesText;
    private @Nullable ScrControl tutorialPanel;
    private @Nullable ScrControl startButton;
    private @NotNull ScrControl @Nullable [] portraitControls;

    /**
     * Creates a {@link ReadyRoomScreen} instance.
     *
     * @param entry the mission entry of the campaign walk
     */
    public ReadyRoomScreen(CampaignEpisodes.@NotNull Entry entry) {
        this(entry.getRace(), entry, null, null, null);
    }

    /**
     * Creates a {@link ReadyRoomScreen} instance for a campaign entry or a custom map.
     *
     * @param race       the ready room race
     * @param entry      the mission entry of the campaign walk, or {@code null} for a custom game
     * @param customGame the custom game the Start button starts, or {@code null} for a campaign entry
     * @param customMap  the parsed custom map, or {@code null} for a campaign entry
     * @param mapArchive the open custom map archive, or {@code null} for a campaign entry or a bare scenario
     */
    private ReadyRoomScreen(int race, CampaignEpisodes.@Nullable Entry entry,
                            @Nullable GlueGameRequest customGame, @Nullable ChkFile customMap, @Nullable MpqArchive mapArchive) {
        super(selectByRace(race, ZERG_DIALOG, TERRAN_DIALOG, PROTOSS_DIALOG),
              selectByRace(race, ZERG_PALETTE_FOLDER, TERRAN_PALETTE_FOLDER, PROTOSS_PALETTE_FOLDER),
              getPaletteBackground(selectByRace(race, ZERG_PALETTE_FOLDER, TERRAN_PALETTE_FOLDER, PROTOSS_PALETTE_FOLDER)));

        this.race       = race;
        this.entry      = entry;
        this.customGame = customGame;
        this.customMap  = customMap;
        this.mapArchive = mapArchive;

        this.briefing          = null;
        this.campaignDirectory = null;
        this.briefingStarted   = false;
        this.stopped           = false;

        this.measuredText   = null;
        this.measuredHeight = 0;
        this.drawnText      = null;
        this.drawnHeight    = 0;

        this.portraits           = new PortraitPlayer[BriefingSession.SLOT_COUNT];
        this.speaking            = new boolean[BriefingSession.SLOT_COUNT];
        this.portraitFrames      = new PortraitPlayer.Frame[BriefingSession.SLOT_COUNT];
        this.portraitTextures    = new int[BriefingSession.SLOT_COUNT];
        this.portraitTextureKeys = new String[BriefingSession.SLOT_COUNT];

        this.skipTutorialButton = null;
        this.waitingLabel       = null;
        this.noBriefingLabel    = null;
        this.transmissionText   = null;
        this.objectivesText     = null;
        this.tutorialPanel      = null;
        this.startButton        = null;
        this.portraitControls   = null;
    }

    /**
     * @return the mission entry, or {@code null} for a custom game
     */
    public CampaignEpisodes.@Nullable Entry getEntry() {
        return this.entry;
    }

    /**
     * @return the LAN client of a network game's room, {@code null} otherwise
     */
    private @Nullable LanClient getNetworkClient() {
        GlueGameRequest request = this.customGame;

        return request == null || request.getKind() != GlueGameRequest.Kind.NETWORK ? null : request.getLanClient();
    }

    /**
     * @param slot the portrait slot
     * @return the canvas texture key of the slot's portrait frames
     */
    private @NotNull String getPortraitTextureKey(int slot) {
        return PORTRAIT_TEXTURE_KEY_PREFIX + System.identityHashCode(this) + KEY_SEPARATOR + slot;
    }


    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     *
     * @param glueContext the shared services
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        this.skipTutorialButton = this.bindControl(SKIP_TUTORIAL);
        this.waitingLabel       = this.bindControl(WAITING);
        this.noBriefingLabel    = this.bindControl(NO_BRIEFING);
        this.transmissionText   = this.bindControl(TRANSMISSION_TEXT);
        this.objectivesText     = this.bindControl(OBJECTIVES);
        this.tutorialPanel      = this.bindControl(TUTORIAL_PANEL);
        this.startButton        = this.bindControl(START);
        this.portraitControls   = this.bindControls(FIRST_PORTRAIT, BriefingSession.SLOT_COUNT);

        this.setSwish(SWISH, SWISH_DELAY_MILLISECONDS);
        requireBound(this.skipTutorialButton).setVisible(false);
        requireBound(this.waitingLabel).setVisible(false);

        if (this.getNetworkClient() != null) {
            // Hide Replay and its framing panel in a network room.
            this.bindControl(BUTTON_PANEL).setVisible(false);
            this.bindControl(REPLAY).setVisible(false);
        }

        glueContext.getAudio().playMusic(selectByRace(this.race, ZERG_MUSIC, TERRAN_MUSIC, PROTOSS_MUSIC));
        this.showBriefingState();
    }

    /**
     * Starts the briefing on its first update using that frame's host clock.
     *
     * @param time the host time in milliseconds
     */
    private void startBriefing(long time) {
        this.briefingStarted = true;

        try {
            this.briefing = this.createBriefing(this.getContext(), time);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("BRIEFING UNAVAILABLE: " + (this.entry != null ? "mission " + this.entry.getMission() : "custom map") + ": " +
                           exception.getMessage());
            this.briefing = null;
        }
    }

    private @NotNull BriefingSession createBriefing(@NotNull GlueContext glueContext, long time) throws IOException {
        GlueCanvas canvas = glueContext.getCanvas();
        CascStorage storage = canvas.getStorage();
        CampaignEpisodes.Entry mission = this.entry;
        ChkFile map;
        int missionIndex;

        if (mission != null) {
            CampaignMissions missions = CampaignMissions.load(storage);

            missionIndex           = mission.getMission();
            this.campaignDirectory = missions.getDirectory(missionIndex);
            map                    = ChkFile.parseTolerant(missions.readScenario(storage, missionIndex, canvas.getLocale()));
        } else {
            missionIndex           = CUSTOM_MAP_MISSION;
            this.campaignDirectory = CUSTOM_MAP_DIRECTORY;

            ChkFile requiredMap = this.customMap;

            if (requiredMap == null) {
                throw Exceptions.newNullPointerException();
            }

            map = requiredMap;
        }

        ScrUnitTypeView units = UnitsDat.createTypeTable(UnitsDat.createSchema().parse(storage.readFile(UNITS_FILE)));
        Player[] players = new Player[PLAYER_SLOTS];
        int localPlayer = readPlayers(map, players);
        BriefingSession.TextLayout layout = this.createTextLayout(canvas);

        // The briefing's timers live in the client's one timer list, which the glue pumps once per frame.
        BriefingSession session = BriefingSession.create(map,
                                                         units,
                                                         players, localPlayer,
                                                         missionIndex, this.campaignDirectory,
                                                         false,
                                                         layout,
                                                         (int) time,
                                                         glueContext.getTimers());

        session.setPortraitVideo(this::tickPortrait);

        return session;
    }

    private BriefingSession.@Nullable TextLayout createTextLayout(@NotNull GlueCanvas canvas) {
        DialogControl textControl = ScrDialogControlUtils.findControl(this.getDialog(), TRANSMISSION_TEXT);

        if (textControl == null) {
            return null;
        }

        InterfaceFont font = canvas.getFont(canvas.getControlFontSlot(textControl));

        return new BriefingSession.TextLayout() {
            /**
             * @return the control height: bottom minus top, signed WORDs
             */
            @Override
            public int getBoxHeight() {
                // Bottom minus top of the control rectangle, whose bottom is its last line.
                return textControl.getHeight() - 1;
            }

            /**
             * Measures the text height used by scroll timing.
             * <p>
             * The text wraps at the transmission text control's width. The text is measured as it shows, as a map text of the text
             * presenter.
             *
             * @param text the text shown
             * @return the wrapped text height in pixels, a signed WORD
             */
            @Override
            public int measureTextHeight(@NotNull String text) {
                String shown = ReadyRoomScreen.this.getContext().presentMapText(text);
                int height = measureWrappedHeight(font, textControl, shown);

                ReadyRoomScreen.this.measuredText   = shown;
                ReadyRoomScreen.this.measuredHeight = height;

                return height;
            }
        };
    }

    /**
     * Called by {@link #update} for screen-specific timers.
     *
     * @param time the time in milliseconds
     * @throws NullPointerException if a network game starts without a custom game request
     */
    @Override
    protected void onUpdate(long time) {
        if (!this.briefingStarted) {
            this.startBriefing(time);
        }

        LanClient lanClient = this.getNetworkClient();

        if (lanClient != null && lanClient.updateBriefing()) {
            // Start the game once every player is ready.
            this.stopBriefing();

            GlueContext glueContext = this.getContext();
            GlueGameRequest request = this.customGame;

            if (request == null) {
                throw Exceptions.newNullPointerException();
            }

            this.leave(() -> {
                glueContext.getAudio().stopMusic();
                glueContext.getNavigator().start(request);
            });

            return;
        }

        BriefingSession session = this.briefing;

        if (session == null || this.stopped) {
            return;
        }

        try {
            session.update((int) time);
        } catch (UnsupportedOperationException exception) {
            LOGGER.warning("BRIEFING STOPPED: " + exception.getMessage());
            this.stopBriefing();

            return;
        }

        this.playSounds(session);
        this.showBriefingState();
    }

    private void playSounds(@NotNull BriefingSession session) {
        GlueContext glueContext = this.getContext();

        for (BriefingSession.SoundEvent event : session.pollSoundEvents()) {
            if (event.isStop()) {
                glueContext.getAudio().stopSounds();

                continue;
            }

            byte[] contents = this.readSound(event.getPathBytes(), event.getPath());

            if (contents != null) {
                glueContext.getAudio().playData(contents);
            }
        }
    }

    /**
     * Reads a briefing sound using its raw path bytes, with the open custom map archive as fallback.
     *
     * @param path  the raw sound path bytes
     * @param shown the sound path shown in warnings
     * @return the sound file contents, or {@code null} when the sound is unavailable
     */
    private byte @Nullable [] readSound(byte @NotNull [] path, @NotNull String shown) {
        GlueCanvas canvas = this.getContext().getCanvas();
        SoundPathResolver.AudioSet set = canvas.isHighDefinition() ? SoundPathResolver.AudioSet.REMASTERED : SoundPathResolver.AudioSet.CLASSIC;

        try {
            byte[] contents = new SoundPathResolver(canvas.getStorage(), canvas.getLocale(), set).readAsset(path, this.mapArchive);

            if (contents == null) {
                LOGGER.warning("BRIEFING SOUND UNAVAILABLE: " + shown);
            }

            return contents;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("BRIEFING SOUND UNAVAILABLE: " + shown + ": " + exception.getMessage());

            return null;
        }
    }

    private void showBriefingState() {
        BriefingSession session = this.briefing;
        boolean live = session != null && !this.stopped;

        requireBound(this.noBriefingLabel).setVisible(this.briefingStarted && (session == null || session.isEmpty()));

        String text = live ? session.getTransmissionText() : null;

        requireBound(this.transmissionText).setVisible(text != null);
        requireBound(this.transmissionText).setReplacedText(text);

        String objectives = live ? session.getMissionObjectives() : null;

        requireBound(this.objectivesText).setVisible(objectives != null);
        requireBound(this.objectivesText).setReplacedText(objectives == null ? null : this.getContext().presentMapText(objectives));

        boolean skip = live && session.isSkipTutorialEnabled();

        requireBound(this.skipTutorialButton).setVisible(skip);
        requireBound(this.tutorialPanel).setVisible(skip);

        for (int slot = 0; slot < BriefingSession.SLOT_COUNT; slot++) {
            requireBound(this.portraitControls)[slot].setVisible(live && session.isSlotVisible(slot));
        }
    }

    /**
     * Draws one control.
     * <p>
     * The briefing portraits and the scrolling transmission text are drawn by this screen.
     *
     * @param canvas  the glue canvas
     * @param control the control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IOException if an image cannot be read
     */
    @Override
    protected void drawControl(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        int slot = control.getId() - FIRST_PORTRAIT;

        if (slot >= 0 && slot < BriefingSession.SLOT_COUNT) {
            this.drawPortrait(canvas, control, slot, left, top);

            return;
        }

        if (control.getId() == TRANSMISSION_TEXT) {
            BriefingSession session = this.briefing;
            String text = session == null ? null : session.getTransmissionText();

            if (text == null) {
                return;
            }

            String shown = this.getContext().presentMapText(text);
            int offset = this.findTransmissionScrollOffset(session, canvas.getFont(canvas.getControlFontSlot(control)), control, shown);

            canvas.setClip(left, top, control.getWidth(), control.getHeight());
            canvas.drawText(canvas.getControlFontSlot(control),
                            shown,
                            left, top - offset,
                            control.getWidth(), control.getHeight() + offset,
                            GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                            this.getPalette().getCodeColor(DialogPainter.CODE_TEXT),
                            this.getPalette());
            canvas.setClip(0, 0, GlueCanvas.NO_CLIP_SIZE, 0);

            return;
        }

        super.drawControl(canvas, control, left, top);
    }

    /**
     * Returns the scroll of the drawn transmission text. A text drawn otherwise than the briefing measured it, such as a shown text that
     * changed after the text was measured, scrolls the same share of its own scroll range.
     *
     * @param session the briefing
     * @param font    the font of the transmission text control
     * @param control the transmission text control
     * @param shown   the drawn text
     * @return the scroll offset in pixels
     */
    private int findTransmissionScrollOffset(@NotNull BriefingSession session, @NotNull InterfaceFont font, @NotNull DialogControl control,
                                             @NotNull String shown) {
        int offset = session.getTransmissionScrollOffset();

        if (shown.equals(this.measuredText)) {
            return offset;
        }

        if (!shown.equals(this.drawnText)) {
            this.drawnText   = shown;
            this.drawnHeight = measureWrappedHeight(font, control, shown);
        }

        int scrollEnd = control.getHeight() / SCROLL_END_BOX_DIVISOR;
        int measuredRange = this.measuredHeight - scrollEnd;
        int drawnRange = this.drawnHeight - scrollEnd;

        return measuredRange > 0 && drawnRange > 0 ? (int) ((long) offset * drawnRange / measuredRange) : 0;
    }

    /**
     * Advances a portrait clip on the briefing timer, applying the speaking state and retaining the current frame for drawing.
     *
     * @param slot                 the portrait slot
     * @param portrait             the slot's portrait index
     * @param talking              whether the slot is speaking
     * @param hostTimeMilliseconds the host time in milliseconds
     */
    private void tickPortrait(int slot, int portrait, boolean talking, int hostTimeMilliseconds) {
        PortraitPlayer player = this.getPortraitPlayer(slot);

        // A portrait index beyond the 110 portrait directories (units.dat 65535 for a unit type without one) opens no clip.
        if (player == null || portrait < 0 || portrait >= PortdataDat.PORTRAIT_COUNT) {
            return;
        }

        GlueCanvas canvas = this.getContext().getCanvas();

        // The portrait folder of the asset set: SD/portrait/, HD2/portrait/ or portrait/.
        player.setPrefix(canvas.getAssetSet().getPrefix() + PORTRAIT_FOLDER);

        try {
            player.show(player, portrait, hostTimeMilliseconds);

            if (talking != this.speaking[slot]) {
                if (talking) {
                    player.talk(hostTimeMilliseconds, TALKING_UNTIL_STOPPED);
                } else {
                    player.stopTalking();
                }

                this.speaking[slot] = talking;
            }

            this.portraitFrames[slot] = player.update(portrait, hostTimeMilliseconds);
        } catch (IOException exception) {
            LOGGER.warning("BRIEFING PORTRAIT UNAVAILABLE: " + portrait + ": " + exception.getMessage());
            this.portraitFrames[slot] = null;
        }
    }

    private void drawPortrait(@NotNull GlueCanvas canvas, @NotNull DialogControl control, int slot, float left, float top) throws IOException {
        BriefingSession session = this.briefing;

        if (session == null) {
            return;
        }

        PortraitPlayer.Frame frame = this.portraitFrames[slot];

        if (frame != null) {
            String key = frame.getPath() + KEY_SEPARATOR + frame.getIndex();
            int width = frame.getClip().getWidth();
            int height = frame.getClip().getHeight();

            if (!key.equals(this.portraitTextureKeys[slot])) {
                this.portraitTextures[slot]    = canvas.updateTexture(this.getPortraitTextureKey(slot),
                                                                      width, height,
                                                                      frame.getClip().getFrame(frame.getIndex()));
                this.portraitTextureKeys[slot] = key;
            }

            float boxWidth = PORTRAIT_WIDTH;
            float boxHeight = PORTRAIT_HEIGHT;
            float aspect = height / (float) width;
            float x = 0;
            float y = 0;

            if (aspect >= boxHeight / boxWidth) {
                int fitted = (int) (boxHeight / aspect);

                x        = (int) ((boxWidth - fitted) / CENTER_DIVISOR);
                boxWidth = fitted;
            } else {
                int fitted = (int) (boxWidth * aspect);

                y         = (int) ((boxHeight - fitted) / CENTER_DIVISOR);
                boxHeight = fitted;
            }

            int insetX = (control.getWidth() - 1 - PORTRAIT_INSET_WIDTH) / CENTER_DIVISOR;
            int insetY = (control.getHeight() - 1 - PORTRAIT_INSET_HEIGHT) / CENTER_DIVISOR;

            canvas.drawTexture(this.portraitTextures[slot], left + insetX + x, top + insetY + y, boxWidth, boxHeight, OPAQUE_WHITE);
        }

        String framePrefix = selectByRace(this.race, ZERG_FRAME_PREFIX, TERRAN_FRAME_PREFIX, PROTOSS_FRAME_PREFIX);
        String framePath = framePrefix + (session.isSlotSpeaking(slot) ? SPEAKING_FRAME : SILENT_FRAME) + (slot + 1) + FRAME_EXTENSION;

        if (canvas.hasImage(framePath)) {
            canvas.drawImage(canvas.getImage(framePath), left, top);
        }
    }

    private @Nullable PortraitPlayer getPortraitPlayer(int slot) {
        if (this.portraits[slot] == null) {
            try {
                this.portraits[slot] = new PortraitPlayer(this.getContext().getCanvas().getStorage(),
                                                          this.getContext().getPortraitTables(),
                                                          VideoClipDecoder.getDefault());
            } catch (IOException | RuntimeException | LinkageError exception) {
                LOGGER.warning("BRIEFING PORTRAITS UNAVAILABLE: " + exception.getMessage());

                return null;
            }
        }

        return this.portraits[slot];
    }

    private void stopBriefing() {
        BriefingSession session = this.briefing;

        if (session != null && !this.stopped) {
            session.skip();
            this.playSounds(session);
        }

        this.stopped = true;
        this.showBriefingState();
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     *
     * @param control the control id
     * @throws NullPointerException if Start is activated in a room without a campaign entry or a custom game request
     */
    @Override
    protected void onActivate(int control) {
        GlueContext glueContext = this.getContext();

        switch (control) {
            case START -> {
                LanClient lanClient = this.getNetworkClient();

                if (lanClient != null) {
                    // Disable Start and show the waiting text before sending the ready command.
                    requireBound(this.startButton).setEnabled(false);
                    requireBound(this.waitingLabel).setVisible(true);
                    lanClient.readyBriefing();

                    return;
                }

                this.stopBriefing();

                CampaignEpisodes.Entry mission = this.entry;
                GlueGameRequest request;

                if (mission == null) {
                    GlueGameRequest customRequest = this.customGame;

                    if (customRequest == null) {
                        throw Exceptions.newNullPointerException();
                    }

                    request = customRequest;
                } else {
                    request = GlueGameRequest.createCampaign(glueContext.getPlayerName(), glueContext.isExpansion(), mission.getMission());
                }

                this.leave(() -> {
                    glueContext.getAudio().stopMusic();
                    glueContext.getNavigator().start(request);
                });
            }
            case CANCEL -> {
                this.stopBriefing();

                LanClient lanClient = this.getNetworkClient();

                if (lanClient != null) {
                    // Leave the network game before returning to its game list.
                    lanClient.leave();
                    this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.GAME_LIST));

                    return;
                }

                this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.CAMPAIGN));
            }
            case SKIP_TUTORIAL -> {
                // Skipping a tutorial advances the campaign as a victory.
                this.stopBriefing();

                CampaignEpisodes.Entry mission = this.entry;

                if (mission == null) {
                    // A custom game has no next scenario before starting, so return to campaign selection.
                    this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.CAMPAIGN));

                    return;
                }

                CampaignEpisodes.Entry next = mission.createNextEntry();
                CampaignEpisodes.Entry target = next == null ? mission : next;
                CharacterFile character = glueContext.getCharacter();

                try {
                    CampaignScreen.advanceProgress(character, glueContext.isExpansion(), target);
                } catch (IOException exception) {
                    CampaignScreen.logSaveFailure(LOGGER, character, exception);
                }

                this.leave(() -> glueContext.getNavigator().playCampaign(target));
            }
            case REPLAY -> {
                BriefingSession session = this.briefing;

                if (session != null) {
                    this.stopped = false;
                    session.replay();
                    this.playSounds(session);
                    this.showBriefingState();
                }
            }
            default -> {
            }
        }
    }

    /**
     * Releases the videos and the popup.
     * <p>
     * Also releases the briefing and the portrait textures before the inherited screen resources.
     */
    @Override
    public void close() {
        if (this.briefing != null) {
            this.briefing.close();
        }

        for (int slot = 0; slot < BriefingSession.SLOT_COUNT; slot++) {
            if (this.portraitTextures[slot] != 0) {
                this.getContext().getCanvas().releaseTexture(this.getPortraitTextureKey(slot));
            }

            this.portraitTextures[slot] = 0;
        }

        super.close();
    }
}
