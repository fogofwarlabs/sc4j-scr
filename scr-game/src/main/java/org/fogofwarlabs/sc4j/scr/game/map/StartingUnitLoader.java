package org.fogofwarlabs.sc4j.scr.game.map;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.content.ScrRaces;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.GameSettings;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitCreationContext;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes the normal starting-unit dispatcher.
 * <p>
 * The caller supplies the established player records, game RNG at this phase, and the sixteen start position WORDs of the game context. This does not
 * infer a seed from the replay header or complete the surrounding world loader.
 */
public final class StartingUnitLoader {
    /**
     * The initial unit mode that creates the start workers.
     */
    private static final int WORKERS_MODE = 1;
    /**
     * The initial unit mode that creates the start depot and the start workers.
     */
    private static final int DEPOT_AND_WORKERS_MODE = 2;
    /**
     * The number of start workers of a player or a team base.
     */
    private static final int START_WORKER_COUNT = 4;
    /**
     * The WORD mask that rounds a pixel coordinate down to its tile.
     */
    private static final int TILE_ALIGNED_WORD_MASK = 0xFFE0;
    /**
     * The divisor of a placement extent that gives the half extent from the depot center.
     */
    private static final int PLACEMENT_CENTER_DIVISOR = 2;
    /**
     * The divisor of a map extent that gives the map center the Overlord placement compares with.
     */
    private static final int MAP_CENTER_DIVISOR = 2;
    /**
     * The mask of one unsigned byte.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of one unsigned WORD.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * Retains one actual starting constructor request and its completed placement and random-state record.
     */
    public static final class Creation {
        private final int unitType;
        private final int owner;
        private final int requestedX;
        private final int requestedY;

        private final int poolIndex;
        private final int generation;
        private final int finalX;
        private final int finalY;

        private final int spriteX;
        private final int spriteY;

        private final int randomStateBefore;
        private final int randomStateAfter;
        private final int randomCallsBefore;
        private final int randomCallsAfter;

        /**
         * Creates a {@link Creation} instance from a constructor request and its outcome; the random state after it is read from the game.
         *
         * @param game              the game state after the constructor
         * @param unitType          the constructor unit type
         * @param owner             the constructor owner
         * @param requestedX        the requested horizontal coordinate
         * @param requestedY        the requested vertical coordinate
         * @param unit              the completed unit, or {@code null} for a failed allocation
         * @param randomStateBefore the game random state before the constructor
         * @param randomCallsBefore the game random call count before the constructor
         * @throws UnsupportedOperationException if the unit pool is not initialized
         */
        public Creation(@NotNull Game game,
                        int unitType,
                        int owner,
                        int requestedX, int requestedY,
                        @Nullable Unit unit,
                        int randomStateBefore, int randomCallsBefore) {
            this.unitType   = unitType;
            this.owner      = owner;
            this.requestedX = requestedX;
            this.requestedY = requestedY;

            this.poolIndex  = unit == null ? 0 : unit.getIndex();
            this.generation = unit == null ? 0 : game.getUnitSystem().requireUnitPool().getGeneration(this.poolIndex);
            this.finalX     = unit == null ? 0 : unit.getPositionX();
            this.finalY     = unit == null ? 0 : unit.getPositionY();

            Sprite sprite = unit == null ? null : unit.getSpriteOrNull();

            this.spriteX = sprite == null ? 0 : sprite.getX();
            this.spriteY = sprite == null ? 0 : sprite.getY();

            this.randomStateBefore = randomStateBefore;
            this.randomStateAfter  = game.getRandom().getState();
            this.randomCallsBefore = randomCallsBefore;
            this.randomCallsAfter  = game.getRandom().getTotalRandomCounts();
        }

        /**
         * @return the constructor unit type
         */
        public int getUnitType() {
            return this.unitType;
        }

        /**
         * @return the constructor owner
         */
        public int getOwner() {
            return this.owner;
        }

        /**
         * @return the requested horizontal coordinate
         */
        public int getRequestedX() {
            return this.requestedX;
        }

        /**
         * @return the requested vertical coordinate
         */
        public int getRequestedY() {
            return this.requestedY;
        }

        /**
         * @return the allocated index, or {@code 0} for failed allocation
         */
        public int getPoolIndex() {
            return this.poolIndex;
        }

        /**
         * @return the generation at allocation, or {@code 0} for failed allocation
         */
        public int getGeneration() {
            return this.generation;
        }

        /**
         * @return the completed unit horizontal WORD, valid for a nonzero pool index
         */
        public int getFinalX() {
            return this.finalX;
        }

        /**
         * @return the completed unit vertical WORD, valid for a nonzero pool index
         */
        public int getFinalY() {
            return this.finalY;
        }

        /**
         * @return the completed sprite horizontal WORD, valid for a nonzero pool index
         */
        public int getSpriteX() {
            return this.spriteX;
        }

        /**
         * @return the completed sprite vertical WORD, valid for a nonzero pool index
         */
        public int getSpriteY() {
            return this.spriteY;
        }

        /**
         * @return the game random state before this constructor
         */
        public int getRandomStateBefore() {
            return this.randomStateBefore;
        }

        /**
         * @return the game random state after completion
         */
        public int getRandomStateAfter() {
            return this.randomStateAfter;
        }

        /**
         * @return the game random call count before this constructor
         */
        public int getRandomCallsBefore() {
            return this.randomCallsBefore;
        }

        /**
         * @return the game random call count after completion
         */
        public int getRandomCallsAfter() {
            return this.randomCallsAfter;
        }
    }

    /**
     * Holds the team game's input of the team bases and of the team supply caps: the team count, each slot's race as the game start copied it to the
     * game's slot races before the team placement, and the player each force's base belongs to (the force's leader slot).
     */
    public static final class TeamStart {
        /**
         * The team count whose teams hold four slots each.
         */
        private static final int TWO_TEAMS = 2;
        /**
         * The team count whose teams hold three slots each.
         */
        private static final int THREE_TEAMS = 3;
        /**
         * The team count whose teams hold two slots each.
         */
        private static final int FOUR_TEAMS = 4;
        /**
         * The slots of a team of a two-team game.
         */
        private static final int TWO_TEAM_SIZE = 4;
        /**
         * The slots of a team of a three-team game.
         */
        private static final int THREE_TEAM_SIZE = 3;
        /**
         * The slots of a team of a four-team game.
         */
        private static final int FOUR_TEAM_SIZE = 2;

        /**
         * Returns the slots of one team: {@code 4}, {@code 3} and {@code 2} for two, three and four teams.
         *
         * @param teams the team count, the teams option
         * @return the slots of a team
         * @throws UnsupportedOperationException if the team count is outside two to four
         */
        public static int resolveTeamSize(int teams) {
            return switch (teams) {
                case TWO_TEAMS -> TWO_TEAM_SIZE;
                case THREE_TEAMS -> THREE_TEAM_SIZE;
                case FOUR_TEAMS -> FOUR_TEAM_SIZE;
                default -> throw Exceptions.newUnsupportedOperationException("Native team sizes cover two to four teams, not {0}.", teams);
            };
        }

        private final int teams;
        private final int @NotNull [] slotRaces;
        private final int @NotNull [] forceOwners;

        /**
         * Creates a {@link TeamStart} instance; the arrays are kept, not copied.
         *
         * @param teams       the team count, {@code 2} to {@code 4}
         * @param slotRaces   the eight slot races
         * @param forceOwners the owning slot of forces {@code 1} to {@code 4}, {@code -1} for none
         */
        public TeamStart(int teams, int @NotNull [] slotRaces, int @NotNull [] forceOwners) {
            this.teams       = teams;
            this.slotRaces   = slotRaces;
            this.forceOwners = forceOwners;
        }

        /**
         * @return the team count, {@code 2} to {@code 4}
         */
        public int getTeams() {
            return this.teams;
        }

        /**
         * @return the eight slot races, the array itself
         */
        public int @NotNull [] getSlotRaces() {
            return this.slotRaces;
        }

        /**
         * @param force the force, {@link PlayerTable#FIRST_FORCE} to {@link PlayerTable#FORCE_COUNT}
         * @return the owning slot of the force, {@code -1} for none or for a force past the supplied owners
         */
        public int getForceOwner(int force) {
            return force <= this.forceOwners.length ? this.forceOwners[force - PlayerTable.FIRST_FORCE] : -1;
        }

        /**
         * @return the slots of one team of this game ({@link #resolveTeamSize(int)})
         * @throws UnsupportedOperationException if the team count is outside two to four
         */
        public int getTeamSize() {
            return resolveTeamSize(this.teams);
        }

        /**
         * Counts the slots of a force with a race: the team's slots {@code (force - 1) * size} to {@code (force - 1) * size + size - 1} of the slot
         * races; a slot past the eighth reads the force's first slot instead, as the native bounds branch does.
         *
         * @param force the force {@code 1} to {@code 4}
         * @param race  the race BYTE compared with the slot races
         * @return the number of matching slots
         */
        public int countForceRace(int force, int race) {
            int size = this.getTeamSize();
            int first = size * (force - PlayerTable.FIRST_FORCE);
            int count = 0;

            for (int position = 0; position < size; position++) {
                int slot = first + position;
                int slotRace = slot < PlayerTable.PLAYABLE_PLAYER_SLOTS ? this.slotRaces[slot] : this.slotRaces[first];

                if ((slotRace & BYTE_MASK) == race) {
                    count++;
                }
            }

            return count;
        }
    }

    /**
     * Creates depots and workers in the original descending player order.
     *
     * @param creation         the unit creation inputs
     * @param game             the initialized native creation and completion contexts
     * @param settings         the established starting-unit mode and per-player overrides
     * @param startPositions   the eight horizontal/vertical WORD coordinate pairs
     * @param teamStart        the team game's input (a team game), or {@code null}
     * @param instructionLimit the checked iscript instruction bound
     * @return the constructor ledger in actual creation order, including failed allocations
     * @throws IllegalArgumentException      if the start positions are not eight WORD pairs
     * @throws IllegalStateException         if the initialization RNG scope, the accounting or the foliage grid is missing
     * @throws UnsupportedOperationException if a player race lies above Protoss, the team count of the team start is outside two to four, or a depot finds no spatial index or creep state
     */
    public static @NotNull List<Creation> create(@NotNull UnitCreationContext creation, @NotNull Game game,
                                                 @NotNull GameSettings settings,
                                                 int @NotNull [] startPositions,
                                                 @Nullable TeamStart teamStart,
                                                 int instructionLimit) {
        if (startPositions.length != MapUnitLoader.START_POSITIONS_SIZE) {
            throw Exceptions.newIllegalArgumentException("Native starting units require eight established WORD coordinate pairs.");
        }

        if (game.hasInitializedWorldState() || game.getRandom().getEnabled() == 0) {
            throw Exceptions.newIllegalStateException("Native starting units require the active initialization RNG scope.");
        }

        UnitAccounting accounting = game.getPlayerTable().getUnitAccounting();

        // The world creation (ReplayLoaderUtils, GameLoader.initializeMapObjects) sets the accounting and populates the foliage
        // grid before the starting units.
        if (accounting == null) {
            throw Exceptions.newIllegalStateException("The unit accounting is initialized with the game, before the starting units.");
        }

        if (creation.isFoliageEnabled() && (game.getFoliage() == null || !game.getFoliage().isGridPopulated())) {
            throw Exceptions.newIllegalStateException("The foliage grid is populated before the starting units.");
        }

        List<Creation> creations = new ArrayList<>();

        if (teamStart != null) {
            createTeams(creation, game, accounting, settings, startPositions, teamStart, instructionLimit, creations);

            return List.copyOf(creations);
        }

        for (int playerSlot = PlayerTable.PLAYABLE_PLAYER_SLOTS - 1; playerSlot >= 0; playerSlot--) {
            Player player = game.getPlayerTable().getPlayer(playerSlot);

            if (player == null || (player.getType() & BYTE_MASK) != PlayerTypes.COMPUTER && (player.getType() & BYTE_MASK) != PlayerTypes.HUMAN) {
                continue;
            }

            int initialUnitMode = settings.getCreateInitialUnits() & BYTE_MASK;

            if (settings.usesMapSettings() && settings.createsMeleeUnitsForPlayer(playerSlot)) {
                initialUnitMode = DEPOT_AND_WORKERS_MODE;
            }

            if (initialUnitMode != WORKERS_MODE && initialUnitMode != DEPOT_AND_WORKERS_MODE) {
                continue;
            }

            int race = player.getRace() & BYTE_MASK;

            if (race > ScrRaceIds.PROTOSS) {
                throw Exceptions.newUnsupportedOperationException("Native unresolved race uses the unit-type sentinel 228.");
            }

            int startX = startPositions[playerSlot * MapUnitLoader.POSITION_COMPONENT_COUNT + MapUnitLoader.HORIZONTAL_POSITION_INDEX] & WORD_MASK;
            int startY = startPositions[playerSlot * MapUnitLoader.POSITION_COMPONENT_COUNT + MapUnitLoader.VERTICAL_POSITION_INDEX] & WORD_MASK;

            if (initialUnitMode == DEPOT_AND_WORKERS_MODE) {
                createDepot(creation, game, accounting, playerSlot, race, startX, startY, instructionLimit, creations);
            }

            for (int workerIndex = 0; workerIndex < START_WORKER_COUNT; workerIndex++) {
                createWorker(creation, game, accounting, playerSlot, race, startX, startY, instructionLimit, creations);
            }
        }

        return List.copyOf(creations);
    }

    /**
     * Creates the team game's bases. Forces {@code 1} to {@code teams} that hold a human or a computer get one base at their owner's start location:
     * with the depot mode the depot of the race of the force's first slot ({@code (force - 1) * size}, size {@code 4}, {@code 3} or {@code 2} for
     * {@code 2}, {@code 3} or {@code 4} teams) and a Zerg Overlord; with either mode four workers, worker {@code i} of the race of slot
     * {@code i % size + first} (beyond slot {@code 7} the race of slot {@code first + 1}); a race above Protoss names no worker (type {@code 228}).
     *
     * @param creation         the unit creation inputs
     * @param game             the game state
     * @param accounting       the unit accounting
     * @param settings         the game settings
     * @param startPositions   the eight horizontal/vertical WORD coordinate pairs
     * @param teamStart        the team game's input
     * @param instructionLimit the iscript instruction bound
     * @param creations        the constructor ledger, appended to
     */
    private static void createTeams(@NotNull UnitCreationContext creation, @NotNull Game game,
                                    @NotNull UnitAccounting accounting,
                                    @NotNull GameSettings settings,
                                    int @NotNull [] startPositions,
                                    @NotNull TeamStart teamStart,
                                    int instructionLimit,
                                    @NotNull List<Creation> creations) {
        int mode = settings.getCreateInitialUnits() & BYTE_MASK;
        int size = teamStart.getTeamSize();

        for (int force = PlayerTable.FIRST_FORCE; force <= teamStart.getTeams(); force++) {
            if (!hasForcePlayer(game, force)) {
                continue;
            }

            int owner = teamStart.getForceOwner(force);

            if (owner < 0 || owner >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                continue;
            }

            int first = size * (force - PlayerTable.FIRST_FORCE);
            int startX = startPositions[owner * MapUnitLoader.POSITION_COMPONENT_COUNT + MapUnitLoader.HORIZONTAL_POSITION_INDEX] & WORD_MASK;
            int startY = startPositions[owner * MapUnitLoader.POSITION_COMPONENT_COUNT + MapUnitLoader.VERTICAL_POSITION_INDEX] & WORD_MASK;

            if (mode == DEPOT_AND_WORKERS_MODE) {
                int depotRace = teamStart.getSlotRaces()[first] & BYTE_MASK;

                createDepot(creation, game, accounting, owner, depotRace, startX, startY, instructionLimit, creations);
            }

            for (int workerIndex = 0; workerIndex < START_WORKER_COUNT; workerIndex++) {
                int slot = workerIndex % size + first;
                int raceSlot = slot < PlayerTable.PLAYABLE_PLAYER_SLOTS ? slot : first + 1;
                int race = teamStart.getSlotRaces()[raceSlot] & BYTE_MASK;

                if ((mode == WORKERS_MODE || mode == DEPOT_AND_WORKERS_MODE) && race <= ScrRaceIds.PROTOSS) {
                    createWorker(creation, game, accounting, owner, race, startX, startY, instructionLimit, creations);
                }
            }
        }
    }

    /**
     * Checks whether a playable slot of the force holds a human, a computer or a computer-preferring player.
     *
     * @param game  the game state
     * @param force the force {@code 1} to {@code 4}
     * @return {@code true} if a slot of the force is a human, a computer or a computer-preferring slot, {@code false} otherwise
     */
    private static boolean hasForcePlayer(@NotNull Game game, int force) {
        for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS - 1; slot >= 0; slot--) {
            Player player = game.getPlayerTable().getPlayer(slot);

            if (player == null) {
                continue;
            }

            int type = player.getType() & BYTE_MASK;

            if (player.getTeam() == force &&
                (type == PlayerTypes.HUMAN || type == PlayerTypes.COMPUTER || type == PlayerTypes.EITHER_PREFER_COMPUTER)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Creates the start depot of a race and a Zerg's Overlord; the units overlapping the depot's tile-aligned placement are killed first.
     *
     * @param creation         the unit creation inputs
     * @param game             the game state
     * @param accounting       the unit accounting
     * @param playerSlot       the owner slot
     * @param race             the race
     * @param startX           the start location horizontal WORD
     * @param startY           the start location vertical WORD
     * @param instructionLimit the iscript instruction bound
     * @param creations        the constructor ledger, appended to
     * @throws UnsupportedOperationException if the spatial index or the creep state is missing, or the unit pool is not initialized
     * @throws NullPointerException          if the world was built without game data
     */
    private static void createDepot(@NotNull UnitCreationContext creation, @NotNull Game game,
                                    @NotNull UnitAccounting accounting,
                                    int playerSlot,
                                    int race,
                                    int startX, int startY,
                                    int instructionLimit,
                                    @NotNull List<Creation> creations) {
        ScrUnitTypeView unitTypes = game.requireGameData().getUnitTypes();
        Player player = game.getPlayerTable().requirePlayer(playerSlot);
        int creationTag = playerSlot | playerSlot << Byte.SIZE;
        int depotType = ScrRaces.getTownHallId(race == ScrRaceIds.ZERG || race == ScrRaceIds.TERRAN ? race : ScrRaceIds.PROTOSS);
        int halfWidth = (short) unitTypes.getPlacementWidth(depotType) / PLACEMENT_CENTER_DIVISOR;
        int halfHeight = (short) unitTypes.getPlacementHeight(depotType) / PLACEMENT_CENTER_DIVISOR;
        int left = (startX - halfWidth) & TILE_ALIGNED_WORD_MASK;
        int upper = (startY - halfHeight) & TILE_ALIGNED_WORD_MASK;
        int depotX = (left + halfWidth) & WORD_MASK;
        int depotY = (upper + halfHeight) & WORD_MASK;
        UnitSpatialIndex spatialIndex = game.getUnitSystem().getUnitSpatialIndex();

        if (spatialIndex == null) {
            throw Exceptions.newUnsupportedOperationException("Native depot creation requires the original spatial rectangle query.");
        }

        // Every unit of the expanded placement query is killed hidden.
        for (int overlapping : spatialIndex.findAllInRectangle(unitTypes, left, upper, depotX + halfWidth - 1, depotY + halfHeight - 1)) {
            Unit unit = game.getUnitSystem().requireUnitPool().getUnit(overlapping);

            unit.setUserActionFlags(unit.getUserActionFlags() | Unit.USER_ACTION_HIDE_ON_DEATH);
            game.getUnitLifecycle().kill(unit, instructionLimit);
        }

        int randomStateBefore = game.getRandom().getState();
        int randomCallsBefore = game.getRandom().getTotalRandomCounts();
        Unit depot = game.getUnitStorageConstructor().create(creation,
                                                             accounting,
                                                             depotType,
                                                             depotX, depotY,
                                                             playerSlot,
                                                             creationTag,
                                                             instructionLimit);

        if (depot != null) {
            game.getUnitStorageConstructor().tryComplete(creation, depot, player.getType(), instructionLimit);
            creations.add(new Creation(game, depotType, playerSlot, depotX, depotY, depot, randomStateBefore, randomCallsBefore));

            int createdType = depot.getUnitId();

            if (createdType == ScrUnitIds.ZERG_HATCHERY || createdType == ScrUnitIds.ZERG_LAIR ||
                createdType == ScrUnitIds.ZERG_HIVE || createdType == ScrUnitIds.ZERG_CREEP_COLONY ||
                createdType == ScrUnitIds.ZERG_SPORE_COLONY || createdType == ScrUnitIds.ZERG_SUNKEN_COLONY ||
                (unitTypes.getFlags(createdType) & UnitTypeFlags.REQUIRES_CREEP) != 0) {
                CreepField creep = game.getCreepField();

                if (creep == null) {
                    throw Exceptions.newUnsupportedOperationException("Native starting depot requires initialized creep state.");
                }

                creep.fill(game, createdType, (short) depot.getPositionX(), (short) depot.getPositionY());
                game.getLarvaSpawner().create(creation, depot, player.getType(), instructionLimit, creations);
                game.getLarvaSpawner().create(creation, depot, player.getType(), instructionLimit, creations);
            }
        } else {
            creations.add(new Creation(game, depotType, playerSlot, depotX, depotY, null, randomStateBefore, randomCallsBefore));
        }

        if (race == ScrRaceIds.ZERG) {
            int sizeX = (short) (unitTypes.getDimensionLeft(ScrUnitIds.ZERG_HATCHERY) +
                                 unitTypes.getDimensionRight(ScrUnitIds.ZERG_HATCHERY) + 1);
            int sizeY = (short) (unitTypes.getDimensionUp(ScrUnitIds.ZERG_HATCHERY) +
                                 unitTypes.getDimensionDown(ScrUnitIds.ZERG_HATCHERY) + 1);
            int mapWidth = (game.getMap().getWidth() << TerrainTileset.TILE_SHIFT) & WORD_MASK;
            int mapHeight = (game.getMap().getHeight() << TerrainTileset.TILE_SHIFT) & WORD_MASK;
            int overlordX = (short) startX >= mapWidth / MAP_CENTER_DIVISOR ? (short) startX - sizeX : (short) startX + sizeX;
            int overlordY = (short) startY >= mapHeight / MAP_CENTER_DIVISOR ? (short) startY - sizeY : (short) startY + sizeY;

            randomStateBefore = game.getRandom().getState();
            randomCallsBefore = game.getRandom().getTotalRandomCounts();

            Unit overlord = game.getUnitStorageConstructor().create(creation,
                                                                    accounting,
                                                                    ScrUnitIds.ZERG_OVERLORD,
                                                                    overlordX, overlordY,
                                                                    playerSlot,
                                                                    creationTag,
                                                                    instructionLimit);

            if (overlord != null) {
                game.getUnitStorageConstructor().tryComplete(creation, overlord, player.getType(), instructionLimit);
            }

            creations.add(new Creation(game,
                                       ScrUnitIds.ZERG_OVERLORD,
                                       playerSlot,
                                       overlordX, overlordY,
                                       overlord,
                                       randomStateBefore, randomCallsBefore));
        }
    }

    /**
     * Creates a start worker of the race at the start location.
     *
     * @param creation         the unit creation inputs
     * @param game             the game state
     * @param accounting       the unit accounting
     * @param playerSlot       the owner slot
     * @param race             the race
     * @param startX           the start location horizontal WORD
     * @param startY           the start location vertical WORD
     * @param instructionLimit the iscript instruction bound
     * @param creations        the constructor ledger, appended to
     */
    private static void createWorker(@NotNull UnitCreationContext creation, @NotNull Game game,
                                     @NotNull UnitAccounting accounting,
                                     int playerSlot,
                                     int race,
                                     int startX, int startY,
                                     int instructionLimit,
                                     @NotNull List<Creation> creations) {
        Player player = game.getPlayerTable().requirePlayer(playerSlot);
        int creationTag = playerSlot | playerSlot << Byte.SIZE;
        int workerType = ScrRaces.getWorkerId(race == ScrRaceIds.ZERG || race == ScrRaceIds.TERRAN ? race : ScrRaceIds.PROTOSS);
        int randomStateBefore = game.getRandom().getState();
        int randomCallsBefore = game.getRandom().getTotalRandomCounts();
        Unit worker = game.getUnitStorageConstructor().create(creation,
                                                              accounting,
                                                              workerType,
                                                              (short) startX, (short) startY,
                                                              playerSlot,
                                                              creationTag,
                                                              instructionLimit);

        if (worker != null) {
            game.getUnitStorageConstructor().tryComplete(creation, worker, player.getType(), instructionLimit);
        }

        creations.add(new Creation(game, workerType, playerSlot, (short) startX, (short) startY, worker, randomStateBefore, randomCallsBefore));
    }

    private StartingUnitLoader() {
    }
}
