package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.trigger.TriggerRegistry;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkSection;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerScores;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceTimers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Runs mission briefing records on the host millisecond clock. Each timer step advances at most one record; waits,
 * transmission text and portraits share the interface timer list. Session updates pump an owned list and report state
 * when the client owns the list.
 */
public final class BriefingSession {
    /**
     * The number of briefing portrait slots (controls {@code 15..18}).
     */
    public static final int SLOT_COUNT = 4;

    /**
     * The dialog control of portrait slot {@code 0}; slots {@code 1..3} follow.
     */
    private static final int FIRST_PORTRAIT_CONTROL = 15;

    /**
     * Timer owner key of the briefing dialog.
     */
    private static final int OWNER_DIALOG = 0;
    /**
     * Timer owner key of the transmission text control {@code -10}.
     */
    private static final int OWNER_TEXT = 1;
    /**
     * Timer owner key of portrait slot {@code 0}; slots {@code 1..3} follow.
     */
    private static final int OWNER_PORTRAIT_BASE = 2;

    /**
     * Dialog timer of the briefing step.
     */
    private static final int STEP_TIMER = 0xA;
    /**
     * The briefing step interval in milliseconds.
     */
    private static final int STEP_INTERVAL = 100;
    /**
     * Dialog timer of Wait and Transmission.
     */
    private static final int WAIT_TIMER = 0xB;
    /**
     * Text control timer that hides the text.
     */
    private static final int TEXT_HIDE_TIMER = 1;
    /**
     * Text control timer that scrolls the text one pixel.
     */
    private static final int TEXT_SCROLL_TIMER = 2;
    /**
     * Text control timer that starts scrolling after the delay.
     */
    private static final int TEXT_SCROLL_DELAY_TIMER = 3;
    /**
     * The time a long text stays unscrolled before scrolling starts.
     */
    private static final int TEXT_SCROLL_DELAY = 4000;
    /**
     * Portrait timer that ends the speaking animation.
     */
    private static final int PORTRAIT_SPEAKING_TIMER = 2;
    /**
     * Portrait timer of the video frames, armed when the portrait control initializes.
     */
    private static final int PORTRAIT_VIDEO_TIMER = 1;
    /**
     * The portrait video frame interval in milliseconds.
     */
    private static final int PORTRAIT_VIDEO_INTERVAL = 0x32;

    /**
     * Portrait control flag: the talking clips play.
     */
    private static final int PORTRAIT_TALKING = 0x1;
    /**
     * Portrait control flag set by a show until the first video frame reveals the control.
     */
    private static final int PORTRAIT_SHOW_PENDING = 0x2;

    /**
     * The portrait whose units.dat entry is replaced for late missions.
     */
    private static final int LATE_REPLACED_PORTRAIT = 0x26;
    /**
     * Campaign missions above this WORD use the units.dat portrait of {@link #LATE_REPLACEMENT_UNIT}.
     */
    private static final int LATE_REPLACEMENT_MISSION_ABOVE = 0x1F;
    /**
     * The unit whose units.dat portrait replaces {@link #LATE_REPLACED_PORTRAIT}.
     */
    private static final int LATE_REPLACEMENT_UNIT = ScrUnitIds.SPECIAL_CEREBRATE_DAGGOTH;
    /**
     * The portrait whose units.dat entry is replaced for expansion missions.
     */
    private static final int EXPANSION_REPLACED_PORTRAIT = 0x3C;
    /**
     * Campaign missions from this WORD use the units.dat portrait of {@link #EXPANSION_REPLACEMENT_UNIT}.
     */
    private static final int EXPANSION_REPLACEMENT_MISSION_FROM = 0x1C;
    /**
     * The unit whose units.dat portrait replaces {@link #EXPANSION_REPLACED_PORTRAIT}.
     */
    private static final int EXPANSION_REPLACEMENT_UNIT = ScrUnitIds.HERO_FENIX_DRAGOON;

    private static final int WORD_MASK = 0xFFFF;
    private static final int BYTE_MASK = 0xFF;
    /**
     * The divisor of the text box height that gives its rounded-up half, the least scroll divisor.
     */
    private static final int HALF_BOX_DIVISOR = 2;
    /**
     * The handler of every briefing action type, the briefing action table: a frozen registry indexed by the type.
     */
    private static final @NotNull TriggerRegistry<BriefingAction> ACTIONS = createActions();

    /**
     * Executes one briefing action type of a session; a briefing action is a {@link TriggerDefinition.Action} of an {@code MBRF} record.
     */
    @FunctionalInterface
    private interface BriefingAction {
        /**
         * Executes one briefing action of a running record.
         *
         * @param session the briefing session
         * @param record  the running record
         * @param action  the action
         * @param now     the host millisecond DWORD of the pump
         * @return {@code true} if the action completed, {@code false} otherwise
         */
        boolean execute(@NotNull BriefingSession session, @NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action, int now);
    }

    /**
     * The portrait animation that a slot plays.
     */
    public enum SlotAnimation {
        /**
         * The idle clips (normal frame set).
         */
        IDLE,
        /**
         * The talking clips (highlighted frame set).
         */
        TALKING
    }

    /**
     * Supplies the text geometry of the transmission text control {@code -10}, which the text scroll timing needs.
     */
    public interface TextLayout {
        /**
         * @return the control height: bottom minus top, signed WORDs
         */
        int getBoxHeight();

        /**
         * Measures the text height used by scroll timing.
         *
         * @param text the text shown
         * @return the wrapped text height in pixels, a signed WORD
         */
        int measureTextHeight(@NotNull String text);
    }

    /**
     * Receives the portrait video ticks: the 50 ms timer {@code 1} of each portrait control, whose callback advances the
     * video and picks the next clip; it fires in the order of the shared timer list.
     */
    public interface PortraitVideo {
        /**
         * Receives one video tick of a portrait slot.
         *
         * @param slot       the portrait slot
         * @param portrait   the slot's portrait index, or {@code -1} before any Show Portrait
         * @param talking    whether the slot is speaking
         * @param hostMillis the host time of the pump
         */
        void tick(int slot, int portrait, boolean talking, int hostMillis);
    }

    /**
     * A sound request of the briefing: a sound file to play or the stop of every playing sound.
     */
    public static final class SoundEvent {
        private final boolean stop;
        private final @NotNull String path;
        private final byte @NotNull [] pathBytes;
        private final boolean transmission;

        private SoundEvent(boolean stop, @NotNull String path, byte @NotNull [] pathBytes, boolean transmission) {
            this.stop         = stop;
            this.path         = path;
            this.pathBytes    = pathBytes;
            this.transmission = transmission;
        }

        /**
         * @return {@code true} if every playing sound stops instead of {@link #getPath()} playing, {@code false} otherwise
         */
        public boolean isStop() {
            return this.stop;
        }

        /**
         * @return the decoded sound path ({@link #getPathBytes()} decoded), for display; empty for a stop
         */
        public @NotNull String getPath() {
            return this.path;
        }

        /**
         * Returns a copy of the raw path bytes the action builds, which the archive lookup hashes: the map string's own (CP949) bytes, prefixed
         * with the campaign directory and a backslash in a campaign mission, at most 259 bytes.
         *
         * @return the path bytes, empty for a stop
         */
        public byte @NotNull [] getPathBytes() {
            return this.pathBytes.clone();
        }

        /**
         * Tells whether a Transmission requested the sound rather than Play WAV or a stop; the distinction is informational.
         *
         * @return {@code true} if a Transmission (speech) requested the sound, {@code false} otherwise
         */
        public boolean isTransmission() {
            return this.transmission;
        }
    }

    /**
     * The session's timers in the interface timer list: one owner key object per control (the dialog, the text
     * control, the four portrait controls), so that the session's timers keep their own identity in a list it shares with
     * other dialogs, and the callbacks reach {@link #onTimer}.
     */
    private final class SessionTimers {
        private final @NotNull InterfaceTimers list;
        /**
         * Whether {@link #update} pumps the list (own list), or its owner does (the client's list).
         */
        private final boolean pumped;
        private final @NotNull Object @NotNull [] owners;

        private SessionTimers(@NotNull InterfaceTimers list, boolean pumped) {
            this.list   = list;
            this.pumped = pumped;
            this.owners = new Object[OWNER_PORTRAIT_BASE + SLOT_COUNT];

            for (int owner = 0; owner < this.owners.length; owner++) {
                this.owners[owner] = new Object();
            }
        }

        private void arm(int owner, int id, int interval, int now) {
            this.list.arm(this.owners[owner], id, interval, now, fired -> BriefingSession.this.onTimer(owner, id, fired));
        }

        private void kill(int owner, int id) {
            this.list.kill(this.owners[owner], id);
        }

        private void killAll() {
            for (Object owner : this.owners) {
                this.list.killAll(owner);
            }
        }
    }

    /**
     * Creates a {@link BriefingSession} instance from the {@code MBRF} records of a scenario, with the ready-room dialog state; the step timer starts
     * at {@code hostMillis}.
     *
     * @param map                the scenario
     * @param units              the unit type table whose portrait column resolves Show Portrait
     * @param players            the first eight players, whose type and force decide Force owners
     * @param localPlayer        the local player DWORD
     * @param campaignMission    the campaign mission WORD, {@code 0} outside campaigns
     * @param campaignDirectory  the mission directory for the campaign mission, used by sound paths when the mission is not {@code 0}
     * @param multiplayerSession the multiplayer session BYTE
     * @param textLayout         the transmission text geometry, or {@code null} to leave text scrolling unmodeled
     * @param hostMillis         the host millisecond DWORD at the dialog initialization
     * @return the created {@link BriefingSession} instance
     * @throws UnsupportedOperationException if an {@code MBRF} section is rejected or incomplete, whose load failure path is not established
     * @throws IllegalArgumentException      if fewer than eight players are supplied
     */
    public static @NotNull BriefingSession create(@NotNull ChkFile map,
                                                  @NotNull ScrUnitTypeView units,
                                                  Player @NotNull [] players, int localPlayer,
                                                  int campaignMission, @NotNull String campaignDirectory,
                                                  boolean multiplayerSession,
                                                  @Nullable TextLayout textLayout,
                                                  int hostMillis) {
        return create(map, units, players, localPlayer, campaignMission, campaignDirectory, multiplayerSession, textLayout, hostMillis, null);
    }

    /**
     * Creates a {@link BriefingSession} instance whose timers share the client's interface timer list with other dialogs.
     * The list's owner pumps it once per interface loop; {@link #update} then only reports the state. {@link #close} removes
     * the session's timers.
     *
     * @param map                the scenario
     * @param units              the unit type table
     * @param players            the first eight players
     * @param localPlayer        the local player DWORD
     * @param campaignMission    the campaign mission WORD, {@code 0} outside campaigns
     * @param campaignDirectory  the mission directory for campaign sound paths
     * @param multiplayerSession the multiplayer session BYTE
     * @param textLayout         the transmission text geometry, or {@code null}
     * @param hostMillis         the host millisecond DWORD at the dialog initialization
     * @param timers             the client's interface timer list, or {@code null} for a list of the session's own
     * @return the created {@link BriefingSession} instance
     * @throws UnsupportedOperationException if an {@code MBRF} section is rejected or incomplete, whose load failure path is not established
     * @throws IllegalArgumentException      if fewer than eight players are supplied
     */
    public static @NotNull BriefingSession create(@NotNull ChkFile map,
                                                  @NotNull ScrUnitTypeView units,
                                                  Player @NotNull [] players, int localPlayer,
                                                  int campaignMission, @NotNull String campaignDirectory,
                                                  boolean multiplayerSession,
                                                  @Nullable TextLayout textLayout,
                                                  int hostMillis,
                                                  @Nullable InterfaceTimers timers) {
        if (players.length < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            throw Exceptions.newIllegalArgumentException("Briefing force owners require eight players.");
        }

        List<RunningTrigger> records = new ArrayList<>();

        for (ChkSection section : map.getSections(ChkFile.BRIEFING_SECTION)) {
            int size = section.getDeclaredSize();

            if (Integer.remainderUnsigned(size, TriggerRecordCodec.RECORD_SIZE) != 0) {
                throw Exceptions.newUnsupportedOperationException(
                        "The MBRF section of {0} bytes is not a multiple of the {1}-byte briefing record; its load failure is not modeled.",
                        Integer.toUnsignedString(size),
                        TriggerRecordCodec.RECORD_SIZE
                );
            }

            if (!section.hasCompletePayload()) {
                throw Exceptions.newUnsupportedOperationException("The MBRF section extends past the CHK data; reading beyond it is not modeled.");
            }

            byte[] payload = section.getPayload();

            for (int offset = 0; offset < size; offset += TriggerRecordCodec.RECORD_SIZE) {
                registerRecord(payload, offset, records, players, localPlayer);
            }
        }

        BriefingSession session = new BriefingSession(records.toArray(new RunningTrigger[0]),
                                                      map.getStrings(), map.getStringBytes(),
                                                      units,
                                                      campaignMission & WORD_MASK, campaignDirectory,
                                                      multiplayerSession,
                                                      textLayout,
                                                      timers == null ? new InterfaceTimers() : timers, timers == null);

        // The portrait controls initialize with the dialog and arm their video timers before the dialog's
        // initialization arms the step timer.
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            session.timers.arm(OWNER_PORTRAIT_BASE + slot, PORTRAIT_VIDEO_TIMER, PORTRAIT_VIDEO_INTERVAL, hostMillis);
        }

        session.timers.arm(OWNER_DIALOG, STEP_TIMER, STEP_INTERVAL, hostMillis);
        session.restart();

        return session;
    }

    /**
     * Registers one record once for every owner that selects the local player.
     *
     * @param payload     the {@code MBRF} section payload
     * @param offset      the record offset in the payload
     * @param records     the registered records
     * @param players     the first eight players
     * @param localPlayer the local player
     */
    private static void registerRecord(byte @NotNull [] payload, int offset,
                                       @NotNull List<RunningTrigger> records,
                                       Player @NotNull [] players,
                                       int localPlayer) {
        TriggerDefinition definition = null;

        for (int selector = 0; selector < TriggerPlayerSelectors.COUNT; selector++) {
            if (TriggerRecordCodec.readOwnerSelector(payload, offset, selector) == 0) {
                continue;
            }

            boolean selected;

            if (selector == localPlayer || selector == TriggerPlayerSelectors.ALL_PLAYERS) {
                selected = true;
            } else if (selector >= TriggerPlayerSelectors.FORCE_1 && selector <= TriggerPlayerSelectors.FORCE_4) {
                // The active-player walk, which has no team leaders in a map-settings game: only the local player of the force
                // registers, when active.
                selected = localPlayer >= 0 && localPlayer < PlayerTable.PLAYABLE_PLAYER_SLOTS && players[localPlayer].isActive() &&
                           (players[localPlayer].getTeam() & BYTE_MASK) == selector - TriggerPlayersUtils.FORCE_BASE;
            } else {
                selected = false;
            }

            if (!selected) {
                continue;
            }

            if (definition == null) {
                definition = TriggerRecordCodec.decodeDefinition(payload, offset);
            }

            records.add(TriggerRecordCodec.createExecution(definition, payload, offset));
        }
    }

    /**
     * Creates the registry of the briefing actions, one execution per briefing action identifier.
     *
     * @return the created, frozen {@link TriggerRegistry} instance
     */
    private static @NotNull TriggerRegistry<BriefingAction> createActions() {
        TriggerRegistry<BriefingAction> actions = new TriggerRegistry<>(BriefingActionIds.COUNT);

        actions.register(BriefingActionIds.WAIT, BriefingSession::executeWait);
        actions.register(BriefingActionIds.PLAY_WAV, BriefingSession::executePlayWav);
        actions.register(BriefingActionIds.TEXT_MESSAGE, BriefingSession::executeTextMessage);
        actions.register(BriefingActionIds.MISSION_OBJECTIVES, BriefingSession::executeMissionObjectives);
        actions.register(BriefingActionIds.SHOW_PORTRAIT, BriefingSession::executeShowPortrait);
        actions.register(BriefingActionIds.HIDE_PORTRAIT, BriefingSession::executeHidePortrait);
        actions.register(BriefingActionIds.DISPLAY_SPEAKING_PORTRAIT, BriefingSession::executeDisplaySpeakingPortrait);
        actions.register(BriefingActionIds.TRANSMISSION, BriefingSession::transmit);
        actions.register(BriefingActionIds.SKIP_TUTORIAL_ENABLED, BriefingSession::executeSkipTutorialEnabled);
        actions.freeze();

        return actions;
    }

    /**
     * Reads the portrait slot of an action: the slot is the player selector BYTE, and dialog control
     * {@code 15 + slot} is a portrait only below {@code 4} ({@code 19} is the Skip Tutorial button).
     *
     * @param action the briefing action
     * @return the portrait slot
     * @throws UnsupportedOperationException if the slot is not a portrait slot
     */
    private static int requireSlot(TriggerDefinition.@NotNull Action action) {
        int slot = action.getPlayerSelector() & BYTE_MASK;

        if (slot >= SLOT_COUNT) {
            throw Exceptions.newUnsupportedOperationException(
                    "Briefing portrait slot {0} addresses dialog control {1}, which is not a portrait control.",
                    slot,
                    slot + FIRST_PORTRAIT_CONTROL
            );
        }

        return slot;
    }


    private final @NotNull RunningTrigger @NotNull [] records;
    private final @NotNull List<String> strings;
    private final @NotNull List<byte[]> stringBytes;
    private final @NotNull ScrUnitTypeView units;
    private final int campaignMission;
    private final @NotNull String campaignDirectory;
    private final boolean multiplayerSession;
    private final @Nullable TextLayout textLayout;

    private final @NotNull SessionTimers timers;
    private int currentRecord;
    private boolean waiting;

    private final int @NotNull [] slotUnits;
    private final int @NotNull [] slotPortraits;
    private final int @NotNull [] slotFlags;
    private final boolean @NotNull [] slotShown;

    private @NotNull String text;
    private boolean textVisible;
    private int textDuration;
    private int textScrollOffset;
    private int textScrollStep;

    private int missionObjectivesIndex;
    private @NotNull String missionObjectives;
    private boolean missionObjectivesVisible;
    private boolean skipTutorialEnabled;

    private final @NotNull List<SoundEvent> soundEvents;
    private @Nullable PortraitVideo portraitVideo;

    private BriefingSession(@NotNull RunningTrigger @NotNull [] records,
                            @NotNull List<String> strings, @NotNull List<byte[]> stringBytes,
                            @NotNull ScrUnitTypeView units,
                            int campaignMission, @NotNull String campaignDirectory,
                            boolean multiplayerSession,
                            @Nullable TextLayout textLayout,
                            @NotNull InterfaceTimers timerList, boolean pumped) {
        this.records            = records;
        this.strings            = strings;
        this.stringBytes        = stringBytes;
        this.units              = units;
        this.campaignMission    = campaignMission;
        this.campaignDirectory  = campaignDirectory;
        this.multiplayerSession = multiplayerSession;
        this.textLayout         = textLayout;

        this.timers        = new SessionTimers(timerList, pumped);
        this.currentRecord = -1;
        this.waiting       = false;

        this.slotUnits     = new int[SLOT_COUNT];
        this.slotPortraits = new int[SLOT_COUNT];
        this.slotFlags     = new int[SLOT_COUNT];
        this.slotShown     = new boolean[SLOT_COUNT];

        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            this.slotUnits[slot] = -1;
        }

        this.text             = "";
        this.textVisible      = false;
        this.textDuration     = 0;
        this.textScrollOffset = 0;
        this.textScrollStep   = 0;

        this.missionObjectivesIndex   = 0;
        this.missionObjectives        = "";
        this.missionObjectivesVisible = false;
        this.skipTutorialEnabled      = false;

        this.soundEvents   = new ArrayList<>();
        this.portraitVideo = null;
    }

    /**
     * @param slot the portrait slot {@code 0..3}
     * @return the unit type of the slot's last Show Portrait, or {@code -1} before any
     */
    public int getSlotUnit(int slot) {
        return this.slotUnits[Objects.checkIndex(slot, SLOT_COUNT)];
    }

    /**
     * @param slot the portrait slot {@code 0..3}
     * @return the portrait index of the slot, {@code 0} before any Show Portrait
     */
    public int getSlotPortrait(int slot) {
        return this.slotPortraits[Objects.checkIndex(slot, SLOT_COUNT)];
    }

    /**
     * Tells whether the slot shows a portrait: set by Show Portrait, cleared by Hide Portrait, a restart or the dialog stop.
     *
     * @param slot the portrait slot {@code 0..3}
     * @return {@code true} if the slot shows a portrait, {@code false} otherwise
     */
    public boolean isSlotVisible(int slot) {
        return this.slotShown[Objects.checkIndex(slot, SLOT_COUNT)];
    }

    /**
     * @param slot the portrait slot {@code 0..3}
     * @return the animation the slot plays
     */
    public @NotNull SlotAnimation getSlotAnimation(int slot) {
        return this.isSlotSpeaking(slot) ? SlotAnimation.TALKING : SlotAnimation.IDLE;
    }

    /**
     * Tells whether the slot is speaking. A speaking slot plays the talking clips and is drawn with the highlighted frame
     * set instead of the normal one; a Display Speaking Portrait of a hidden slot sets the flag too, and a later Show
     * Portrait clears it.
     *
     * @param slot the portrait slot {@code 0..3}
     * @return {@code true} if the talking flag {@code 0x1} is set, {@code false} otherwise
     */
    public boolean isSlotSpeaking(int slot) {
        return (this.slotFlags[Objects.checkIndex(slot, SLOT_COUNT)] & PORTRAIT_TALKING) != 0;
    }

    /**
     * Returns the text that the transmission text control {@code -10} shows. A Text Message or Transmission shows it at
     * once and the hide timer removes it without a fade after {@link #getTransmissionTextDuration()} milliseconds, at the
     * first {@link #update(int)} whose clock exceeds start plus duration.
     *
     * @return the shown text, or {@code null} while the control is hidden
     */
    public @Nullable String getTransmissionText() {
        return this.textVisible ? this.text : null;
    }

    /**
     * @return the host millisecond DWORD at which the shown text appeared (hide timer start)
     * @throws IllegalStateException if no text is shown
     */
    public int getTransmissionTextStartMillis() {
        if (!this.textVisible) {
            throw Exceptions.newIllegalStateException("No briefing text is shown.");
        }

        return this.timers.list.getStart(this.timers.owners[OWNER_TEXT], TEXT_HIDE_TIMER);
    }

    /**
     * @return the display duration of the shown text in milliseconds, an unsigned DWORD: the action duration; a Transmission uses its unmodified duration here
     * @throws IllegalStateException if no text is shown
     */
    public int getTransmissionTextDuration() {
        if (!this.textVisible) {
            throw Exceptions.newIllegalStateException("No briefing text is shown.");
        }

        return this.textDuration;
    }

    /**
     * Returns the vertical scroll of the shown text in pixels. A text taller than the box scrolls one pixel per step; the
     * step is {@code duration / (height - (box + 1) / 2)}, and a text longer than {@code 4000} ms stays still for
     * {@code 4000} ms and scrolls over the rest.
     *
     * @return the scroll offset WORD
     * @throws UnsupportedOperationException if the session has no {@link TextLayout}, so the text height is unknown
     */
    public int getTransmissionScrollOffset() {
        if (this.textLayout == null) {
            throw Exceptions.newUnsupportedOperationException("The briefing text scroll requires the measured text height of a TextLayout.");
        }

        return this.textScrollOffset;
    }

    /**
     * @return the mission objectives text that control {@code -11} shows (the map string of the objectives WORD stored for the local player), or {@code null} while the control is hidden
     */
    public @Nullable String getMissionObjectives() {
        return this.missionObjectivesVisible ? this.missionObjectives : null;
    }

    /**
     * @return the objectives DWORD that Mission Objectives stored for the local player, {@code 0} before any
     */
    public int getMissionObjectivesStringIndex() {
        return this.missionObjectivesIndex;
    }

    /**
     * @return {@code true} if Skip Tutorial Enabled showed the Skip Tutorial button {@code 19} and control {@code -13}, {@code false} otherwise
     */
    public boolean isSkipTutorialEnabled() {
        return this.skipTutorialEnabled;
    }

    /**
     * @return {@code true} if a Wait or Transmission holds the briefing, {@code false} otherwise
     */
    public boolean isWaiting() {
        return this.waiting;
    }

    /**
     * @return {@code true} if no record was registered, so the dialog shows "No briefing data" (control {@code -14}), {@code false} otherwise
     */
    public boolean isEmpty() {
        return this.records.length == 0;
    }

    /**
     * @return {@code true} if the briefing has a current record and its step timer runs, {@code false} otherwise
     */
    public boolean isRunning() {
        return this.currentRecord >= 0 && this.timers.list.isArmed(this.timers.owners[OWNER_DIALOG], STEP_TIMER);
    }

    /**
     * Sets the receiver of the portrait video ticks.
     *
     * @param portraitVideo the receiver, or {@code null} to ignore the ticks
     */
    public void setPortraitVideo(@Nullable PortraitVideo portraitVideo) {
        this.portraitVideo = portraitVideo;
    }

    /**
     * Resolves a map string: empty for an index without a string.
     *
     * @param stringIndex the one-based map string index
     * @return the map string, or an empty string
     */
    private @NotNull String getMapString(int stringIndex) {
        if (stringIndex < 1 || stringIndex > this.strings.size()) {
            return "";
        }

        return this.strings.get(stringIndex - 1);
    }


    /**
     * Returns and clears the pending sound requests in request order.
     *
     * @return the sound requests since the last poll
     */
    public @NotNull List<SoundEvent> pollSoundEvents() {
        List<SoundEvent> events = List.copyOf(this.soundEvents);

        this.soundEvents.clear();

        return events;
    }

    /**
     * Pumps the briefing timers once per host update. Displays keep expiring after the triggers end, so callers keep updating while they show the
     * dialog. The triggers end with the record list or when the dialog stops.
     *
     * @param hostMillis the host millisecond DWORD
     * @return {@code true} if the briefing triggers continue ({@link #isRunning()}), {@code false} otherwise
     * @throws UnsupportedOperationException if a reached action is unsupported
     */
    public boolean update(int hostMillis) {
        if (this.timers.pumped) {
            this.timers.list.pump(hostMillis);
        }

        return this.isRunning();
    }

    /**
     * Applies the Skip Tutorial button (result {@code 19}): the dialog procedure stops the briefing (the text timers and
     * the dialog's step and wait timers are killed, every sound stops, and the text, objectives and portraits are hidden)
     * and ends the dialog; the ready room then advances the campaign. Cancel and the dialog destruction apply the same
     * stop.
     */
    public void skip() {
        this.timers.kill(OWNER_TEXT, TEXT_HIDE_TIMER);
        this.timers.kill(OWNER_TEXT, TEXT_SCROLL_TIMER);
        this.timers.kill(OWNER_TEXT, TEXT_SCROLL_DELAY_TIMER);
        this.timers.kill(OWNER_DIALOG, STEP_TIMER);
        this.timers.kill(OWNER_DIALOG, WAIT_TIMER);

        this.soundEvents.add(new SoundEvent(true, "", new byte[0], false));

        this.textVisible              = false;
        this.missionObjectivesVisible = false;

        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            this.hidePortrait(slot);
        }
    }

    /**
     * Applies the Replay button (result {@code 20}) as the restart that the dialog initialization also runs: every sound
     * stops, the portraits are hidden (their speaking timers keep running), the text is cleared and hidden with its
     * timers killed, the objectives are hidden, the wait ends and its timer is killed, the current record's current
     * action loses its started flag, its execution bit and its action index, and the first record becomes current.
     */
    public void replay() {
        this.restart();
    }

    private void restart() {
        this.soundEvents.add(new SoundEvent(true, "", new byte[0], false));

        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            this.hidePortrait(slot);
        }

        this.text        = "";
        this.textVisible = false;

        this.timers.kill(OWNER_TEXT, TEXT_HIDE_TIMER);
        this.timers.kill(OWNER_TEXT, TEXT_SCROLL_TIMER);
        this.timers.kill(OWNER_TEXT, TEXT_SCROLL_DELAY_TIMER);

        this.missionObjectivesVisible = false;
        this.waiting                  = false;

        this.timers.kill(OWNER_DIALOG, WAIT_TIMER);

        if (this.currentRecord >= 0) {
            RunningTrigger record = this.records[this.currentRecord];

            // The index byte selects the action whose started flag is cleared; the index is below 64 at rest.
            record.startedActions &= ~(1L << (record.currentActionIndex & (TriggerRecordCodec.ACTION_COUNT - 1)));
            record.executionFlags &= ~TriggerFlags.EXECUTION_ACTIONS_RUNNING;
            record.currentActionIndex = 0;
        }

        this.currentRecord = this.records.length == 0 ? -1 : 0;
    }

    private void onTimer(int owner, int id, int now) {
        if (owner == OWNER_DIALOG) {
            if (id == STEP_TIMER) {
                this.step(now);
            } else {
                // The wait ends and the timer kills itself.
                this.waiting = false;

                this.timers.kill(OWNER_DIALOG, WAIT_TIMER);
            }
        } else if (owner == OWNER_TEXT) {
            if (id == TEXT_HIDE_TIMER) {
                // Hides the control and kills timers 1, 2 and 3.
                this.textVisible = false;

                this.timers.kill(OWNER_TEXT, TEXT_HIDE_TIMER);
                this.timers.kill(OWNER_TEXT, TEXT_SCROLL_TIMER);
                this.timers.kill(OWNER_TEXT, TEXT_SCROLL_DELAY_TIMER);
            } else if (id == TEXT_SCROLL_TIMER) {
                this.textScrollOffset = (this.textScrollOffset + 1) & WORD_MASK;
            } else {
                // Scrolling starts with the stored step, and the delay timer kills itself.
                this.timers.arm(OWNER_TEXT, TEXT_SCROLL_TIMER, this.textScrollStep, now);
                this.timers.kill(OWNER_TEXT, TEXT_SCROLL_DELAY_TIMER);
            }
        } else if (id == PORTRAIT_VIDEO_TIMER) {
            int slot = owner - OWNER_PORTRAIT_BASE;

            if (this.portraitVideo != null) {
                this.portraitVideo.tick(slot, this.getSlotPortrait(slot), this.isSlotSpeaking(slot), now);
            }
        } else {
            // The speaking timer: only a shown slot stops talking; a hidden slot leaves the timer repeating.
            int slot = owner - OWNER_PORTRAIT_BASE;

            if (this.slotShown[slot]) {
                this.slotFlags[slot] &= ~PORTRAIT_TALKING;

                this.timers.kill(owner, PORTRAIT_SPEAKING_TIMER);
            }
        }
    }

    /**
     * Runs the briefing step: evaluates the current record and, when it finished, makes the next record current; the
     * list end leaves none.
     *
     * @param now the host millisecond DWORD of the pump
     */
    private void step(int now) {
        if (this.currentRecord < 0) {
            return;
        }

        if (!this.evaluate(this.records[this.currentRecord], now)) {
            return;
        }

        this.currentRecord = this.currentRecord + 1 < this.records.length ? this.currentRecord + 1 : -1;
    }

    /**
     * Evaluates one briefing record: only the type of condition {@code 0} is read, and the actions run from the stored
     * index. A failed action stops the evaluation and keeps the record current.
     *
     * @param record the running record
     * @param now    the host millisecond DWORD of the pump
     * @return {@code true} if the record finished or does not run, {@code false} otherwise
     */
    private boolean evaluate(@NotNull RunningTrigger record, int now) {
        if (record.definition.getCondition(0).getType() != TriggerConditionIds.MISSION_BRIEFING) {
            return true;
        }

        record.executionFlags |= TriggerFlags.EXECUTION_ACTIONS_RUNNING;

        while (record.currentActionIndex < TriggerRecordCodec.ACTION_COUNT) {
            TriggerDefinition.Action action = record.definition.getAction(record.currentActionIndex);

            if ((action.getFlags() & TriggerFlags.ACTION_DISABLED) == 0) {
                int actionType = action.getType();

                if (actionType == BriefingActionIds.NO_ACTION) {
                    record.currentActionIndex = TriggerRecordCodec.ACTION_COUNT;

                    break;
                }

                if (!this.execute(record, action, now)) {
                    return false;
                }
            }

            record.currentActionIndex++;
        }

        record.executionFlags &= ~TriggerFlags.EXECUTION_ACTIONS_RUNNING;
        record.currentActionIndex = 0;

        return true;
    }

    /**
     * Executes one briefing action through the briefing action table. An action that fails or waits does not complete.
     *
     * @param record the running record
     * @param action the action
     * @param now    the host millisecond DWORD of the pump
     * @return {@code true} if the action completed, {@code false} otherwise
     * @throws UnsupportedOperationException if the action type has no briefing action handler or the portrait slot is not a portrait slot
     */
    private boolean execute(@NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action, int now) {
        BriefingAction handler = ACTIONS.find(action.getType());

        if (handler == null) {
            throw Exceptions.newUnsupportedOperationException("Briefing action type {0} has no action handler.", action.getType());
        }

        return handler.execute(this, record, action, now);
    }

    /**
     * Executes a briefing Wait: a waiting briefing fails; a started wait clears its flag; otherwise the wait timer starts.
     */
    private boolean executeWait(@NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action, int now) {
        long started = 1L << record.currentActionIndex;

        if (this.waiting) {
            return false;
        }

        if ((record.startedActions & started) != 0) {
            record.startedActions &= ~started;

            return true;
        }

        this.timers.arm(OWNER_DIALOG, WAIT_TIMER, action.getDuration(), now);

        record.startedActions |= started;
        this.waiting = true;

        return false;
    }

    /**
     * Executes a briefing Play WAV: a nonzero DWORD sound string plays; no player or skip test.
     */
    private boolean executePlayWav(@NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action, int now) {
        if (action.getSoundIndex() != 0) {
            this.soundEvents.add(this.createSoundEvent(action.getSoundIndex(), false));
        }

        return true;
    }

    /**
     * Executes a briefing Text Message: the WORD string shows for the duration regardless of the subtitle option.
     */
    private boolean executeTextMessage(@NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action, int now) {
        this.showText(this.getMapString(action.getStringIndex() & WORD_MASK), action.getDuration(), now);

        return true;
    }

    /**
     * Executes a briefing Mission Objectives: the DWORD is stored; the objectives control shows the map string of its WORD.
     */
    private boolean executeMissionObjectives(@NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action, int now) {
        this.missionObjectivesIndex   = action.getStringIndex();
        this.missionObjectives        = this.getMapString(action.getStringIndex() & WORD_MASK);
        this.missionObjectivesVisible = true;

        return true;
    }

    /**
     * Executes a briefing Show Portrait: the portrait replaces the slot's clip; the flags become the show-pending flag.
     */
    private boolean executeShowPortrait(@NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action, int now) {
        int slot = requireSlot(action);
        int unitType = action.getUnitType() & WORD_MASK;
        int portrait = this.resolvePortrait(unitType);

        this.slotUnits[slot]     = unitType;
        this.slotPortraits[slot] = portrait;
        this.slotFlags[slot]     = PORTRAIT_SHOW_PENDING;
        this.slotShown[slot]     = true;

        return true;
    }

    /**
     * Executes a briefing Hide Portrait.
     */
    private boolean executeHidePortrait(@NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action, int now) {
        this.hidePortrait(requireSlot(action));

        return true;
    }

    /**
     * Executes a briefing Display Speaking Portrait.
     */
    private boolean executeDisplaySpeakingPortrait(@NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action, int now) {
        this.speak(requireSlot(action), action.getDuration(), now);

        return true;
    }

    /**
     * Executes a briefing Skip Tutorial Enabled: only outside a multiplayer session and in a campaign mission.
     */
    private boolean executeSkipTutorialEnabled(@NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action, int now) {
        if (!this.multiplayerSession && this.campaignMission != 0) {
            this.skipTutorialEnabled = true;
        }

        return true;
    }

    /**
     * Executes a briefing Transmission: the sound plays, the text shows for the unmodified duration, the portrait speaks
     * and the briefing waits for the duration that the modifier sets, adds or subtracts (clamped at {@code 0}). The
     * Transmission fails while the briefing waits, and a started Transmission completes on its next run.
     *
     * @param record the running record
     * @param action the Transmission action
     * @param now    the host millisecond DWORD of the pump
     * @return {@code true} if a started Transmission completed, {@code false} otherwise
     * @throws UnsupportedOperationException if the portrait slot is not a portrait slot
     */
    private boolean transmit(@NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action, int now) {
        long started = 1L << record.currentActionIndex;

        if (this.waiting) {
            return false;
        }

        if ((record.startedActions & started) != 0) {
            record.startedActions &= ~started;

            return true;
        }

        int duration = PlayerScores.modify(action.getDuration(), action.getModifier() & BYTE_MASK, action.getSecondaryOperand());

        int slot = requireSlot(action);

        // A nonzero DWORD sound plays the map string of its WORD.
        if (action.getSoundIndex() != 0) {
            this.soundEvents.add(this.createSoundEvent(action.getSoundIndex() & WORD_MASK, true));
        }

        // The text keeps the unmodified duration; the portrait and the wait use the modified one.
        this.showText(this.getMapString(action.getStringIndex() & WORD_MASK), action.getDuration(), now);
        this.speak(slot, duration, now);
        this.timers.arm(OWNER_DIALOG, WAIT_TIMER, duration, now);

        record.startedActions |= started;
        this.waiting = true;

        return false;
    }

    /**
     * Shows a text in the transmission text control: the hide timer starts, and a text taller than the box arms the
     * scroll timers.
     *
     * @param shownText the text
     * @param duration  the display duration in milliseconds, an unsigned DWORD
     * @param now       the host millisecond DWORD of the pump
     * @throws UnsupportedOperationException if the scroll step divides by zero
     */
    private void showText(@NotNull String shownText, int duration, int now) {
        this.text             = shownText;
        this.textScrollOffset = 0;
        this.textVisible      = true;
        this.textDuration     = duration;

        this.timers.arm(OWNER_TEXT, TEXT_HIDE_TIMER, duration, now);
        this.timers.kill(OWNER_TEXT, TEXT_SCROLL_TIMER);

        TextLayout layout = this.textLayout;

        if (layout == null) {
            return;
        }

        int boxHeight = (short) layout.getBoxHeight();
        int textHeight = (short) layout.measureTextHeight(shownText);

        if (textHeight <= boxHeight) {
            return;
        }

        int half = (boxHeight + 1) / HALF_BOX_DIVISOR;
        int divisor = textHeight - half;

        if (Integer.compareUnsigned(half, divisor) > 0) {
            divisor = half;
        }

        if (divisor == 0) {
            throw Exceptions.newUnsupportedOperationException("The briefing text scroll step divides by zero (box height {0}).", boxHeight);
        }

        if (Integer.compareUnsigned(duration, TEXT_SCROLL_DELAY) > 0) {
            this.textScrollStep = Integer.divideUnsigned(duration - TEXT_SCROLL_DELAY, divisor);

            this.timers.arm(OWNER_TEXT, TEXT_SCROLL_DELAY_TIMER, TEXT_SCROLL_DELAY, now);
        } else {
            this.textScrollStep = Integer.divideUnsigned(duration, divisor);

            this.timers.arm(OWNER_TEXT, TEXT_SCROLL_TIMER, this.textScrollStep, now);
        }
    }

    /**
     * Starts the speaking animation of a portrait slot for the duration.
     *
     * @param slot     the portrait slot
     * @param duration the speaking duration in milliseconds
     * @param now      the host millisecond DWORD of the pump
     */
    private void speak(int slot, int duration, int now) {
        this.slotFlags[slot] |= PORTRAIT_TALKING;

        this.timers.arm(OWNER_PORTRAIT_BASE + slot, PORTRAIT_SPEAKING_TIMER, duration, now);
    }

    /**
     * Hides a portrait: flags become {@code 0}; the portrait index and the speaking timer remain.
     *
     * @param slot the portrait slot
     */
    private void hidePortrait(int slot) {
        this.slotFlags[slot] = 0;
        this.slotShown[slot] = false;
    }

    /**
     * Resolves the portrait of a unit type with the campaign replacements of two portraits. Other portrait indices
     * remain unchanged.
     *
     * @param unitType the unit type
     * @return the portrait index
     * @throws UnsupportedOperationException if the portrait of the unit type lies outside the units.dat memory
     */
    private int resolvePortrait(int unitType) {
        int portrait = this.units.getPortrait(unitType);

        if (portrait == LATE_REPLACED_PORTRAIT && this.campaignMission > LATE_REPLACEMENT_MISSION_ABOVE) {
            portrait = this.units.getPortrait(LATE_REPLACEMENT_UNIT);
        } else if (portrait == EXPANSION_REPLACED_PORTRAIT && this.campaignMission >= EXPANSION_REPLACEMENT_MISSION_FROM) {
            portrait = this.units.getPortrait(EXPANSION_REPLACEMENT_UNIT);
        }

        return portrait;
    }

    /**
     * Creates a {@link SoundEvent} instance for Play WAV or Transmission from the map string's raw path bytes.
     * Campaign paths are prefixed with the mission directory. At most 259 bytes remain before the terminator.
     *
     * @param stringIndex  the map string index of the sound file
     * @param transmission {@code true} if a Transmission requests the sound, {@code false} for Play WAV
     * @return the created {@link SoundEvent} instance
     */
    private @NotNull SoundEvent createSoundEvent(int stringIndex, boolean transmission) {
        byte[] name = stringIndex < 1 || stringIndex > this.stringBytes.size() ? new byte[0] : this.stringBytes.get(stringIndex - 1);
        byte[] path = name;

        if (this.campaignMission != 0) {
            byte[] directory = (this.campaignDirectory + "\\").getBytes(StandardCharsets.US_ASCII);

            path = new byte[directory.length + name.length];

            System.arraycopy(directory, 0, path, 0, directory.length);
            System.arraycopy(name, 0, path, directory.length, name.length);
        }

        if (path.length > TriggerEvents.SOUND_PATH_LIMIT) {
            path = Arrays.copyOf(path, TriggerEvents.SOUND_PATH_LIMIT);
        }

        String decodedName = this.getMapString(stringIndex);
        String decoded = this.campaignMission == 0 ? decodedName : this.campaignDirectory + "\\" + decodedName;

        return new SoundEvent(false,
                              decoded.length() > TriggerEvents.SOUND_PATH_LIMIT ? decoded.substring(0, TriggerEvents.SOUND_PATH_LIMIT) : decoded,
                              path,
                              transmission);
    }

    /**
     * Removes the session's timers from the timer list (the dialog is destroyed).
     */
    public void close() {
        this.timers.killAll();
    }
}
