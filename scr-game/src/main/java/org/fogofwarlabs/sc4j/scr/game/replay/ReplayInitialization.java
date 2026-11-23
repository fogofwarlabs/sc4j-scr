package org.fogofwarlabs.sc4j.scr.game.replay;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayHeader;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplaySettings;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.map.StartingUnitLoader;
import org.fogofwarlabs.sc4j.scr.game.movement.UnitMovement;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.presentation.MusicRotation;
import org.fogofwarlabs.sc4j.scr.game.presentation.TimedIndicators;
import org.fogofwarlabs.sc4j.scr.game.session.setup.PlayerSlots;
import org.fogofwarlabs.sc4j.scr.game.state.RuntimeRandom;
import org.fogofwarlabs.sc4j.scr.game.state.VictoryCondition;
import org.fogofwarlabs.sc4j.scr.game.trigger.TransmissionPortraitTimer;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitCreationContext;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Retains initialization inputs for a fresh replay or live session.
 * The caller supplies any random-state effects from resource loading before initialization.
 */
public final class ReplayInitialization {
    /**
     * The music player of a game whose start-up draws picked no human: a live player's game, or a replay or observer start
     * without humans.
     */
    public static final int NO_MUSIC_PLAYER = -1;
    /**
     * The music race of a game whose music race player has no record.
     */
    public static final int NO_MUSIC_RACE = -1;

    private static final int BYTE_MASK = 0xFF;
    private static final int NO_CAMPAIGN_MISSION = 0;
    /**
     * The console race of a replay or observer start.
     */
    private static final int VIEWER_CONSOLE_RACE = ScrRaceIds.TERRAN;

    /**
     * Creates a {@link ReplayInitialization} instance with a new game generator seeded from the replay header, as the first
     * game of a process starts it.
     *
     * @param header the accepted expansion normal-game replay header
     * @return the created {@link ReplayInitialization} instance
     */
    public static @NotNull ReplayInitialization createFreshReplay(@NotNull ReplayHeader header) {
        return createFreshReplay(header, 0, new MusicRotation());
    }

    /**
     * Creates a {@link ReplayInitialization} instance with a new game generator seeded from the replay header.
     *
     * @param header             the accepted expansion normal-game replay header
     * @param speedOverrideIndex the speed override selector the recording keeps, {@code 0} for none
     * @param musicRotation      the music rotation of the client process, advanced by a non-campaign start
     * @return the created {@link ReplayInitialization} instance
     * @throws IllegalArgumentException if the speed override selector lies outside the override table
     */
    public static @NotNull ReplayInitialization createFreshReplay(@NotNull ReplayHeader header, int speedOverrideIndex,
                                                                  @NotNull MusicRotation musicRotation) {
        return new ReplayInitialization(header, new LcgRandom((int) header.getRandomSeed()), -1, 0, speedOverrideIndex, musicRotation);
    }

    /**
     * Creates a {@link ReplayInitialization} instance for a live game started by this client.
     *
     * @param header      the header built for the new game
     * @param localPlayer the human's player index, or the observer slot of an observer
     * @return the created {@link ReplayInitialization} instance
     * @throws IllegalArgumentException if the local player is neither one of the eight game players nor an observer slot
     */
    public static @NotNull ReplayInitialization createLiveGame(@NotNull ReplayHeader header, int localPlayer) {
        return createLiveGame(header, localPlayer, false);
    }

    /**
     * Creates a {@link ReplayInitialization} instance for a live game started by this client, as the first game of a process
     * starts it.
     *
     * @param header      the header built for the new game
     * @param localPlayer the human's player index, or the observer slot of an observer
     * @param networkGame whether the game is a network game
     * @return the created {@link ReplayInitialization} instance
     * @throws IllegalArgumentException if the local player is neither one of the eight game players nor an observer slot
     */
    public static @NotNull ReplayInitialization createLiveGame(@NotNull ReplayHeader header, int localPlayer, boolean networkGame) {
        return createLiveGame(header, localPlayer, networkGame, 0, new MusicRotation());
    }

    /**
     * Creates a {@link ReplayInitialization} instance for a live game started by this client. An observer's start draws as a
     * replay start does ({@link #isReplayOrObserver()}).
     *
     * @param header             the header built for the new game
     * @param localPlayer        the human's player index, or the observer slot of an observer
     * @param networkGame        whether the game is a network game
     * @param speedOverrideIndex the speed override selector of the game's name ({@link GameSpeed#parseOverrideIndex}), {@code 0} for none
     * @param musicRotation      the music rotation of the client process, advanced by a non-campaign start
     * @return the created {@link ReplayInitialization} instance
     * @throws IllegalArgumentException if the local player is neither one of the eight game players nor an observer slot, or the speed override selector lies outside the override table
     */
    public static @NotNull ReplayInitialization createLiveGame(@NotNull ReplayHeader header, int localPlayer, boolean networkGame,
                                                               int speedOverrideIndex, @NotNull MusicRotation musicRotation) {
        if (!PlayerSlots.isPlayerIdentifier(localPlayer)) {
            throw Exceptions.newIllegalArgumentException("A live local player is one of the eight game players or an observer, not {0}.",
                                                         localPlayer);
        }

        return new ReplayInitialization(header, new LcgRandom((int) header.getRandomSeed()), localPlayer, networkGame ? 1 : 0,
                                        speedOverrideIndex, musicRotation);
    }

    /**
     * @param header the replay header
     * @return the low game-type byte, excluding the subtype byte
     */
    public static int getNativeGameType(@NotNull ReplayHeader header) {
        return header.getType() & BYTE_MASK;
    }

    /**
     * Finds the first human player used by replay presentation.
     *
     * @param header the replay header
     * @return the player index, or the first non-playing slot when no player is human
     */
    public static int findNativeReplayLocalPlayer(@NotNull ReplayHeader header) {
        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS && player < header.getPlayers().size(); player++) {
            if (header.getPlayers().get(player).getType() == PlayerTypes.HUMAN) {
                return player;
            }
        }

        return PlayerTable.PLAYABLE_PLAYER_SLOTS;
    }

    /**
     * Checks the represented game modes using the map settings or victory condition.
     *
     * @param header the replay header
     * @return {@code true} if the game mode is represented, {@code false} otherwise
     */
    static boolean isSupportedGameMode(@NotNull ReplayHeader header) {
        ReplaySettings settings = header.getSettings();

        if (settings.usesMapSettings()) {
            return true;
        }

        return VictoryCondition.of(settings.getVictoryCondition()).acceptsSubtypeValue(settings.getSubtypeValue());
    }


    private final @NotNull ReplayHeader header;
    private final @NotNull LcgRandom random;

    private final boolean live;
    private final int localPlayer;
    private final int comparisonMask;
    private int triggerLocalPlayer;
    private int localSelectionSlot;

    private final @NotNull RuntimeRandom runtimeRandom;
    private final int musicPlayer;

    private final @NotNull GameSpeed frameTiming;
    private final @NotNull UnitCreationContext creationContext;

    private final int multiplayerSession;
    private int @Nullable [] teamSlotRaces;

    /**
     * Creates a {@link ReplayInitialization} instance for a fresh replay lifecycle.
     * <p>
     * The caller supplies the established first-constructor game random state. The start is the first game of a process.
     * Replay speed uses the fresh missing-profile setting.
     *
     * @param header the accepted expansion normal-game replay header
     * @param random the established enabled game random state at the first constructor
     * @throws UnsupportedOperationException if the replay setup is outside the represented scope
     * @throws IllegalArgumentException      if the game random scope is disabled or the header has fewer than eight player records
     */
    public ReplayInitialization(@NotNull ReplayHeader header, @NotNull LcgRandom random) {
        this(header, random, -1, 0, 0, new MusicRotation());
    }

    /**
     * Creates a {@link ReplayInitialization} instance and runs the C runtime start-up draws.
     * <p>
     * The C runtime generator is seeded with the header seed. A replay or observer start ({@link #isReplayOrObserver()}) then
     * draws once per type-2 player, each draw picking that human for the music when the value divides evenly by the humans
     * counted so far; a live player's start draws none. A non-campaign start then advances the music rotation, which draws
     * only at the process's first such start.
     *
     * @param header             the accepted expansion normal-game header
     * @param random             the established enabled game random state at the first constructor
     * @param liveLocalPlayer    the human's player index or the observer slot of a live game, {@code -1} for a replay
     * @param multiplayerSession {@code 1} for a network game, else {@code 0}
     * @param speedOverrideIndex the speed override selector of the frame timing, {@code 0} for none
     * @param musicRotation      the music rotation of the client process
     * @throws UnsupportedOperationException if the replay setup is outside the represented scope
     * @throws IllegalArgumentException      if the game random scope is disabled, the header has fewer than eight player records or the speed override selector lies outside the override table
     */
    private ReplayInitialization(@NotNull ReplayHeader header, @NotNull LcgRandom random, int liveLocalPlayer, int multiplayerSession,
                                 int speedOverrideIndex, @NotNull MusicRotation musicRotation) {
        if (!isSupportedGameMode(header)) {
            throw Exceptions.newUnsupportedOperationException("Game start fails for victory {0} with subtype value {1}.",
                                                              header.getSettings().getVictoryCondition(),
                                                              header.getSettings().getSubtypeValue());
        }

        if (random.getEnabled() == 0) {
            throw Exceptions.newIllegalArgumentException("The first constructor requires an enabled native random scope.");
        }

        this.header = header;
        this.random = random;

        // The shift keeps the low five bits of an observer slot: bit 0, until the observer's vision starts with every player.
        this.live               = liveLocalPlayer >= 0;
        this.localPlayer        = this.live ? liveLocalPlayer : PlayerTable.PLAYABLE_PLAYER_SLOTS;
        this.comparisonMask     = this.live ? 1 << this.localPlayer : 0;
        this.triggerLocalPlayer = this.localPlayer;
        this.localSelectionSlot = this.localPlayer;

        if (header.getPlayers().size() < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            throw Exceptions.newIllegalArgumentException("The native replay header requires eight player records.");
        }

        RuntimeRandom runtimeRandom = new RuntimeRandom((int) header.getRandomSeed());
        int musicPlayer = NO_MUSIC_PLAYER;

        if (this.isReplayOrObserver()) {
            int humans = 0;

            for (int playerIndex = 0; playerIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS; playerIndex++) {
                if (header.getPlayers().get(playerIndex).getType() == PlayerTypes.HUMAN) {
                    humans++;

                    if (runtimeRandom.draw() % humans == 0) {
                        musicPlayer = playerIndex;
                    }
                }
            }
        }

        if (header.getCampaignMission() == NO_CAMPAIGN_MISSION) {
            musicRotation.advance(runtimeRandom);
        }

        this.runtimeRandom = runtimeRandom;
        this.musicPlayer   = musicPlayer;

        this.frameTiming     = this.live
                               ? new GameSpeed(header.getSpeed(), 1, 0, speedOverrideIndex)
                               : GameSpeed.createPristineReplay(speedOverrideIndex);
        this.creationContext = new UnitCreationContext(false, true, true, false, !this.live, this.localPlayer);

        this.multiplayerSession = multiplayerSession;
        this.teamSlotRaces      = null;
    }

    /**
     * @return the supplied first-constructor game random state
     */
    public @NotNull LcgRandom getRandom() {
        return this.random;
    }

    /**
     * @return {@code true} if the game is live rather than replay playback, {@code false} otherwise
     */
    public boolean isLive() {
        return this.live;
    }

    /**
     * @return {@code true} if the game is replay playback or the local player is an observer ({@link PlayerTable#isReplayOrObserver}), {@code false} otherwise
     */
    public boolean isReplayOrObserver() {
        return PlayerTable.isReplayOrObserver(!this.live, this.localPlayer);
    }

    /**
     * @return the local identity produced by the fresh session reset
     */
    public int getLocalPlayer() {
        return this.localPlayer;
    }

    /**
     * @return the local player of trigger evaluation: {@link #getLocalPlayer()} unless {@link #selectNativeReplayLocalPlayer()} chose the replay local player
     */
    public int getTriggerLocalPlayer() {
        return this.triggerLocalPlayer;
    }

    /**
     * @return the local comparison mask while the world is created: the local player's bit in a live game, no player in a replay, whose vision starts with every player after the world is created
     */
    public int getComparisonMask() {
        return this.comparisonMask;
    }

    /**
     * @return the C runtime generator seeded with the header seed, after the start-up draws; the game created from this initialization shares it
     */
    public @NotNull RuntimeRandom getRuntimeRandom() {
        return this.runtimeRandom;
    }

    /**
     * @return the human the start-up draws of a replay or observer start picked for the game music, or {@link #NO_MUSIC_PLAYER}
     */
    public int getMusicPlayer() {
        return this.musicPlayer;
    }

    /**
     * @return the fresh missing-profile frame timing
     */
    public @NotNull GameSpeed getFrameTiming() {
        return this.frameTiming;
    }

    /**
     * @return the constructor context after the accepted replay startup stores
     */
    public @NotNull UnitCreationContext getCreationContext() {
        return this.creationContext;
    }

    /**
     * @return a copy of the live team game's slot races, or {@code null} for replay player records
     */
    public int @Nullable [] getTeamSlotRaces() {
        return this.teamSlotRaces == null ? null : this.teamSlotRaces.clone();
    }

    /**
     * Sets a live team game's slot races (lobby order, after the random races).
     *
     * @param teamSlotRaces the eight races, or {@code null}
     */
    public void setTeamSlotRaces(int @Nullable [] teamSlotRaces) {
        this.teamSlotRaces = teamSlotRaces == null ? null : teamSlotRaces.clone();
    }

    /**
     * Sets the live human's own selection slot while its local identity may be the force leader.
     *
     * @param localSelectionSlot the human's own slot, or the observer slot of an observer
     * @throws IllegalStateException    if this is replay playback
     * @throws IllegalArgumentException if the slot is neither one of the eight game players nor an observer slot
     */
    public void setLocalSelectionSlot(int localSelectionSlot) {
        if (!this.live) {
            throw Exceptions.newIllegalStateException("Replay playback keeps the replay identity as its selection slot.");
        }

        if (!PlayerSlots.isPlayerIdentifier(localSelectionSlot)) {
            throw Exceptions.newIllegalArgumentException("A local selection slot is one of the eight game players or an observer, not {0}.",
                                                         localSelectionSlot);
        }

        this.localSelectionSlot = localSelectionSlot;
    }

    /**
     * @return {@code true} if this is a multiplayer session, {@code false} otherwise
     */
    public boolean isMultiplayerSession() {
        return this.multiplayerSession != 0;
    }


    /**
     * Finds the player whose start location the game start's screen is placed on.
     *
     * @return the first human ({@link #findNativeReplayLocalPlayer}) of a replay or observer start ({@link #isReplayOrObserver()}), the local player otherwise
     */
    public int findStartScreenPlayer() {
        return this.isReplayOrObserver() ? findNativeReplayLocalPlayer(this.header) : this.localPlayer;
    }

    /**
     * Finds the race of the game music: the race of the human the start-up draws picked ({@link #getMusicPlayer()}). Without one it is the
     * console race of the start: Terran for a replay or observer start ({@link #isReplayOrObserver()}), the local player's race otherwise.
     *
     * @param playerTable the players of the game created from this initialization
     * @return the music race, or {@link #NO_MUSIC_RACE} if the chosen player has no record
     */
    public int findMusicRace(@NotNull PlayerTable playerTable) {
        if (this.musicPlayer == NO_MUSIC_PLAYER && this.isReplayOrObserver()) {
            return VIEWER_CONSOLE_RACE;
        }

        Player player = playerTable.getPlayer(this.musicPlayer == NO_MUSIC_PLAYER ? this.localPlayer : this.musicPlayer);

        return player == null ? NO_MUSIC_RACE : player.getRace();
    }

    /**
     * Makes the trigger evaluation of replay playback use the first human replay player ({@link #findNativeReplayLocalPlayer})
     * instead of player {@code 8}, as the interactive replay viewer does: the trigger local-player branches (Display Text
     * Message, Transmission, Play WAV, Center View, Minimap Ping, leaderboards, the outcome display) then present that
     * player's triggers, and a Transmission of that player arms the wall-clock portrait timer
     * ({@link TransmissionPortraitTimer}). Headless comparison runs keep player {@code 8}, so they need no host clock. Unit
     * selection and unit creation keep the replay identity {@link #getLocalPlayer()}. Call it before the replay starts.
     *
     * @throws IllegalStateException if this is a live game
     */
    public void selectNativeReplayLocalPlayer() {
        if (this.live) {
            throw Exceptions.newIllegalStateException("A live game already uses its human as local player.");
        }

        this.triggerLocalPlayer = findNativeReplayLocalPlayer(this.header);
    }

    /**
     * Creates the fresh map visibility controls without restoring local results or renderer state.
     *
     * @return the multiplayer, cheat, visibility, graphics and display controls in that order
     */
    public int @NotNull [] createInitialMapVisibilityControls() {
        return new int[]{this.multiplayerSession, 0, 0, 0, 0};
    }

    /**
     * Creates a {@link UnitMovement.Context} instance before the first frame pulse.
     *
     * @return the created {@link UnitMovement.Context} instance
     */
    public @NotNull UnitMovement.Context createMovementContext() {
        return new UnitMovement.Context(0, 0);
    }

    /**
     * Creates trigger controls for the fresh session and an established decoded state.
     *
     * @param decodedSessionState the established decoded session state
     * @return the controls expected by {@link TriggerContext#initializeEvaluation}
     */
    public int @NotNull [] createTriggerEvaluationControls(int decodedSessionState) {
        return new int[]{0, decodedSessionState, this.multiplayerSession, 0, this.triggerLocalPlayer};
    }

    /**
     * Creates a {@link TimedIndicators} instance for a session with no prior host notifications.
     *
     * @return the created {@link TimedIndicators} instance
     */
    public @NotNull TimedIndicators createFrameIndicators() {
        return new TimedIndicators(new byte[TimedIndicators.INDICATOR_COUNT], new int[TimedIndicators.INDICATOR_COUNT]);
    }

    /**
     * Creates a {@link UnitSelection} instance after fresh session startup, preserving team slot selections.
     *
     * @param unitPool the persistent unit slots after creation
     * @return the created {@link UnitSelection} instance
     */
    public @NotNull UnitSelection createSelection(@NotNull UnitPool unitPool) {
        int teamSize = 0;

        if (ReplayLoaderUtils.getTeamLeaders(this.header) != null) {
            teamSize = StartingUnitLoader.TeamStart.resolveTeamSize(this.header.getSettings().getTeamsEnabled());
        }

        return new UnitSelection(unitPool, true, teamSize, this.localSelectionSlot);
    }
}
