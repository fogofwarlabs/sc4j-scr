package org.fogofwarlabs.sc4j.scr.game.replay;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkSection;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplaySettings;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerPlayerPhase;
import org.fogofwarlabs.sc4j.scr.game.combat.ProjectilePhase;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepRecession;
import org.fogofwarlabs.sc4j.scr.game.frame.GameFrame;
import org.fogofwarlabs.sc4j.scr.game.frame.UnitPhase;
import org.fogofwarlabs.sc4j.scr.game.map.MapStringSection;
import org.fogofwarlabs.sc4j.scr.game.map.MapUnitLoader;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.ProductionStatistics;
import org.fogofwarlabs.sc4j.scr.game.player.StartLocation;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.presentation.ObserverOverlay;
import org.fogofwarlabs.sc4j.scr.game.resource.ResourceAreas;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffectPool;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.state.GameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.VictoryCondition;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.visibility.MapVisibility;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles replay storage before the ordered startup phase and display calls. Construction alone does not publish world readiness.
 */
public final class ReplayStartup {
    private static final int INITIAL_PLAYER_UPDATE_COUNTDOWN = 60;
    private static final int INITIAL_UNIT_STATUS_REFRESH_COUNTDOWN = 15;
    private static final int INITIAL_UNIT_STATUS_REFRESH_CYCLE = 6;
    /**
     * The two values of the players game option ({@link ReplaySettings#getRestrictionFlags()}) with which the Melee computer scripts start.
     */
    private static final int FIRST_MELEE_SCRIPT_PLAYERS_OPTION = 1;
    private static final int SECOND_MELEE_SCRIPT_PLAYERS_OPTION = 3;

    /**
     * Checks whether the replay header settings start the Melee computer scripts.
     *
     * @param settings the replay header settings
     * @return {@code true} if eligible players and victory, initial units or tournament options start Melee scripts, {@code false} otherwise
     */
    static boolean startsMeleeScripts(@NotNull ReplaySettings settings) {
        int players = settings.getRestrictionFlags();

        if (players != FIRST_MELEE_SCRIPT_PLAYERS_OPTION && players != SECOND_MELEE_SCRIPT_PLAYERS_OPTION) {
            return false;
        }

        return settings.getVictoryCondition() != 0 || settings.getCreateInitialUnits() != 0 || settings.getTournamentMode() != 0;
    }

    private final @NotNull ReplayInitialization initialization;
    /**
     * The trigger resource of the victory condition ({@link VictoryCondition#getTriggerFile}), or {@code null}.
     */
    private final @Nullable String victoryTriggers;
    /**
     * A team game's leader slots ({@link ReplayLoaderUtils#getTeamLeaders}), or {@code null}.
     */
    private final int @Nullable [] teamLeaders;

    private final @NotNull List<byte @NotNull []> mapTriggers;
    private final @NotNull List<byte @NotNull []> mapLocations;
    private final @NotNull List<byte @NotNull []> mapUnitProperties;

    private final @NotNull Game game;

    private final @NotNull MapVisibility visibility;
    private final ProjectilePhase.@NotNull Context projectiles;
    private final @NotNull SpriteEffectPool secondarySpriteEffects;

    /**
     * Creates a {@link ReplayStartup} instance using installed resources and actual map creation.
     *
     * @param replay           the accepted expansion Melee replay
     * @param mapFile          the embedded map in original section order
     * @param gameData         the installed resources
     * @param initialization   the established fresh offline lifecycle
     * @param viewportWidth    the viewport width in pixels
     * @param viewportHeight   the viewport height in pixels
     * @param instructionLimit the interpreter and movement budget
     * @throws IOException                   if a required installed resource cannot be read
     * @throws UnsupportedOperationException if the game type, the map triggers or the start locations are not supported, or required state is not initialized
     */
    public ReplayStartup(@NotNull ReplayFile replay,
                         @NotNull ChkFile mapFile,
                         @NotNull GameData gameData,
                         @NotNull ReplayInitialization initialization,
                         int viewportWidth, int viewportHeight,
                         int instructionLimit) throws IOException {
        this.initialization = initialization;

        ReplaySettings settings = replay.getHeader().getSettings();
        VictoryCondition victory = VictoryCondition.of(settings.getVictoryCondition());

        if (!victory.acceptsSubtypeValue(settings.getSubtypeValue())) {
            throw Exceptions.newUnsupportedOperationException("Game start fails: {0} has no trigger resource for subtype value {1}.",
                                                              victory,
                                                              settings.getSubtypeValue());
        }

        this.victoryTriggers = victory.getTriggerFile(settings.getSubtypeValue());
        this.teamLeaders     = ReplayLoaderUtils.getTeamLeaders(replay.getHeader());

        List<byte[]> mapTriggers = new ArrayList<>();
        List<byte[]> mapLocations = new ArrayList<>();
        List<byte[]> mapUnitProperties = new ArrayList<>();

        if (settings.usesMapSettings()) {
            for (ChkSection section : mapFile.getSections(ChkFile.TRIGGER_SECTION)) {
                mapTriggers.add(section.getPayload());
            }

            if (mapTriggers.isEmpty()) {
                throw Exceptions.newUnsupportedOperationException("Map-settings loading requires the TRIG section.");
            }

            for (ChkSection section : mapFile.getSections(ChkFile.LOCATION_SECTION)) {
                mapLocations.add(section.getPayload());
            }

            for (ChkSection section : mapFile.getSections(ChkFile.UNIT_PROPERTIES_SECTION)) {
                mapUnitProperties.add(section.getPayload());
            }
        }

        this.mapTriggers       = mapTriggers;
        this.mapLocations      = mapLocations;
        this.mapUnitProperties = mapUnitProperties;

        this.game = ReplayLoaderUtils.createInitializedReplayWorld(initialization,
                                                                   replay, mapFile,
                                                                   gameData,
                                                                   viewportWidth, viewportHeight,
                                                                   instructionLimit);

        MessageLines messageLines = this.game.getInterfaceLink().getMessageLines();

        try {
            messageLines.setMapStrings(mapFile.getStrings());

            ChkSection stringSection = mapFile.getStringSection();

            if (stringSection != null) {
                messageLines.setMapStringSection(new MapStringSection(stringSection.getPayload(),
                                                                      stringSection.getName().equals(ChkFile.EXTENDED_STRING_SECTION)));
            }
        } catch (RuntimeException exception) {
            messageLines.setMapStrings(null);
        }

        this.game.getGameContext().setGameHeaderSection(replay.getHeaderSection());
        messageLines.setPlayerNames(replay.getPlayerNameBytes());
        messageLines.setMultiplayerSession(this.initialization.isMultiplayerSession());
        messageLines.setReplayPlayback(!this.initialization.isLive());
        messageLines.setLocalPlayer(this.initialization.isLive() ? this.initialization.getLocalPlayer() : -1);

        int[] startPositions = new int[MapUnitLoader.START_POSITIONS_SIZE];
        int[] playerTypes = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        if (this.game.getPlayerTable().getStartLocations().size() != PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            throw Exceptions.newUnsupportedOperationException("Native resource areas require the final eight-slot start buffer.");
        }

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS; playerIndex++) {
            StartLocation start = this.game.getPlayerTable().getStartLocations().get(playerIndex);

            if (start.getOwner() != playerIndex) {
                throw Exceptions.newUnsupportedOperationException("Native start locations lost their original slot ordering.");
            }

            startPositions[playerIndex * MapUnitLoader.POSITION_COMPONENT_COUNT + MapUnitLoader.HORIZONTAL_POSITION_INDEX] = start.getX();
            startPositions[playerIndex * MapUnitLoader.POSITION_COMPONENT_COUNT + MapUnitLoader.VERTICAL_POSITION_INDEX]   = start.getY();

            playerTypes[playerIndex] = this.game.getPlayerTable().requirePlayer(playerIndex).getType();
        }

        ResourceAreas areas = new ResourceAreas(this.game, gameData.getTerrainTileset(this.game.getMap().getTileset()));

        areas.initialize(startPositions, playerTypes);
        this.game.setResourceAreas(areas);

        int regionCount = this.game.getPathSystem().requireTerrainContours().getRegions().getRegionCount();

        this.game.setComputerPlayerPhase(new ComputerPlayerPhase(this.game,
                                                                 regionCount,
                                                                 new int[]{INITIAL_PLAYER_UPDATE_COUNTDOWN,
                                                                           INITIAL_UNIT_STATUS_REFRESH_COUNTDOWN,
                                                                           INITIAL_UNIT_STATUS_REFRESH_CYCLE, 0, 0, 0, 0, 0},
                                                                 new int[regionCount * PlayerTable.PLAYABLE_PLAYER_SLOTS]));

        for (MapUnitLoader.Creation creation : this.game.getMapUnitCreations()) {
            int poolIndex = creation.getPoolIndex();

            if (poolIndex != 0 && this.game.getUnitSystem().requireUnitPool().getGeneration(poolIndex) == creation.getGeneration()) {
                this.game.requireComputerPlayerPhase().registerPreplacedUnit(this.game.getUnitSystem().requireUnitPool().getUnit(poolIndex));
            }
        }

        this.game.requireComputerPlayerPhase().initializeScripts(startsMeleeScripts(settings));

        if (victory.createsFlags()) {
            this.game.getCaptureTheFlag().createFlags(this.teamLeaders, settings.getTeamsEnabled(), instructionLimit);
        }

        this.game.setCreepRecession(new CreepRecession(0, CreepRecession.RECORD_COUNT, new int[CreepRecession.LIST_COUNT]));
        this.game.getPathSystem().requireLocalPathSearch().initializeMovementContext(false, 0);

        this.visibility  = new MapVisibility(gameData.getTerrainTileset(this.game.getMap().getTileset()),
                                             initialization.createInitialMapVisibilityControls());
        this.projectiles = new ProjectilePhase.Context(replay.getLimits().getEffectiveValues()[GameSetup.BULLET_LIMIT]);
        this.projectiles.initializeEmptyLists();
        this.secondarySpriteEffects = new SpriteEffectPool(replay.getLimits().getEffectiveValues()[GameSetup.SECONDARY_SPRITE_EFFECT_LIMIT]);

        this.game.getUnitSystem().requireUnitRegistry().populateReplayOwners(this.game, initialization.getFrameTiming().getFrameDuration());

        ObserverOverlay observer = gameData.loadObserverOverlay();

        this.game.getUnitSystem().requireUnitRegistry().bindReplaySubscriber(observer.getRecordIdentifier(),
                                                                             observer.getRecordType(),
                                                                             observer.getBindingRuntimeFlags());

        ProductionStatistics statistics = this.game.getPlayerTable().requireProductionStatistics();

        statistics.initializeSample(0);

        UnitSelection selection = initialization.createSelection(this.game.getUnitSystem().requireUnitPool());

        selection.bindStatistics(statistics);
        this.game.setSelection(selection);
        this.game.getSelectionImages().updateLocalRelations();
    }

    /**
     * @return the constructed world, still awaiting startup completion
     */
    public @NotNull Game getGame() {
        return this.game;
    }


    /**
     * Creates a {@link GameFrame} instance with the phase contexts once their UI state has been established, and attaches it.
     *
     * @param units the startup unit and panel context
     * @return the created {@link GameFrame} instance, which the game retains
     * @throws IllegalStateException if the frame contexts are already attached
     */
    public @NotNull GameFrame createFrame(UnitPhase.@NotNull Context units) {
        if (this.game.getFrame() != null) {
            throw Exceptions.newIllegalStateException("Native startup frame contexts are already attached.");
        }

        GameFrame frame = new GameFrame(units,
                                        this.initialization.createMovementContext(),
                                        this.projectiles,
                                        this.secondarySpriteEffects,
                                        this.visibility);

        this.game.setFrame(frame);

        return frame;
    }

    /**
     * Creates a {@link TriggerContext} instance with the classic triggers initialized from the established player outcomes,
     * map records and victory resources.
     *
     * @param playerOutcomes      the eight retained main-context outcome bytes
     * @param decodedSessionState the established decoded session state
     * @return the created {@link TriggerContext} instance
     * @throws IOException                   if the installed trigger resource cannot be read
     * @throws IllegalArgumentException      if the outcomes are not eight bytes
     * @throws IllegalStateException         if a playable player slot is empty
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit accounting or the player relations are not initialized
     */
    public @NotNull TriggerContext createTriggers(byte @NotNull [] playerOutcomes, int decodedSessionState) throws IOException {
        if (playerOutcomes.length != PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            throw Exceptions.newIllegalArgumentException("Replay trigger outcomes require eight players.");
        }

        TriggerContext triggers = new TriggerContext();
        Player[] triggerPlayers = new Player[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        for (int player = 0; player < triggerPlayers.length; player++) {
            triggerPlayers[player] = this.game.getPlayerTable().requirePlayer(player);
            triggerPlayers[player].setVictoryState(playerOutcomes[player]);
        }

        // The active-player walk visits the team leaders only in a network game; it decides the initially active players.
        triggers.setTeamLeaders(this.initialization.isMultiplayerSession() ? this.teamLeaders : null);
        triggers.initializeClassicPlayerRecords(triggerPlayers);

        for (byte[] locations : this.mapLocations) {
            triggers.loadLocations(locations);
        }

        for (byte[] properties : this.mapUnitProperties) {
            triggers.loadUnitProperties(properties);
        }

        for (byte[] records : this.mapTriggers) {
            triggers.loadRecords(records);
        }

        if (this.victoryTriggers != null) {
            triggers.loadAllPlayersResource(this.game.requireGameData().readInstalledFile(this.victoryTriggers));
        }

        triggers.setComputerPhase(this.game.getComputerPlayerPhase());
        triggers.initializeEvaluation(this.game.getPlayerTable().requireUnitAccounting(),
                                      this.game.getPlayerTable().requirePlayerRelations(),
                                      this.initialization.createTriggerEvaluationControls(decodedSessionState));

        return triggers;
    }
}
