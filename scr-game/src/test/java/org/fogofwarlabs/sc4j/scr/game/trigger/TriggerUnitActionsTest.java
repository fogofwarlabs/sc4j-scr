package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitTraits;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ArrayDatColumn;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.TriggerUnitGroups;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.presentation.CommandErrorEvents;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteScenario;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMapScenario;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.test.UnitTestUtils;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

/**
 * Checks the unit creation of the trigger actions, with and without the messages of a failed creation, the search of the position a creation
 * places a unit at, and the building-flight exclusion of Move Unit for types beyond units.dat.
 */
@RequiresInstallation
final class TriggerUnitActionsTest {
    /**
     * The X position of a created Marine in the running game.
     */
    private static final int MARINE_X = 3560;
    /**
     * The Y position of a created Marine in the running game.
     */
    private static final int MARINE_Y = 2160;

    /**
     * The divisor from a placement box extent to the distance between its edge and its center.
     */
    private static final int POSITION_CENTER_DIVISOR = 2;

    /**
     * The tiles around a Hatchery site that must be inside the map and free of creep before the creation: the reach of a
     * full creep fill.
     */
    private static final int CREEP_FREE_TILES = 10;
    /**
     * The horizontal tile distance from the Hatchery center that a full creep fill reaches but the creep under the building
     * does not.
     */
    private static final int OUTSIDE_FOOTPRINT_TILES = 6;

    /**
     * The value of the building power refresh gate after a refresh request.
     */
    private static final int POWER_REFRESH_REQUESTED = 1;
    /**
     * The owner of a created add-on type without the building flag: the neutral player.
     */
    private static final int NEUTRAL_PLAYER = 11;

    /**
     * Finds the first open Hatchery site, in row order, whose surroundings are inside the map and have no creep.
     *
     * @param game the running game
     * @return the site center X and Y
     * @throws NullPointerException if the game was built without game data
     */
    private static int @NotNull [] findHatcherySite(@NotNull Game game) {
        ScrUnitTypeView units = game.requireGameData().getUnitTypes();
        GameMap map = game.getMap();
        int type = ScrUnitIds.ZERG_HATCHERY;
        int halfWidth = units.getPlacementWidth(type) / POSITION_CENTER_DIVISOR;
        int halfHeight = units.getPlacementHeight(type) / POSITION_CENTER_DIVISOR;
        int endTileX = map.getWidth() - CREEP_FREE_TILES - units.getPlacementWidth(type) / TerrainTileset.TILE_SIZE;
        int endTileY = map.getHeight() - CREEP_FREE_TILES - units.getPlacementHeight(type) / TerrainTileset.TILE_SIZE;

        for (int tileY = CREEP_FREE_TILES; tileY < endTileY; tileY++) {
            for (int tileX = CREEP_FREE_TILES; tileX < endTileX; tileX++) {
                int positionX = tileX * TerrainTileset.TILE_SIZE + halfWidth;
                int positionY = tileY * TerrainTileset.TILE_SIZE + halfHeight;

                if (game.getUnitPlacement().canPlaceTriggerBuilding(RunningGameScenario.HUMAN, type, positionX, positionY) &&
                    !hasCreepAround(map, positionX / TerrainTileset.TILE_SIZE, positionY / TerrainTileset.TILE_SIZE)) {
                    return new int[]{positionX, positionY};
                }
            }
        }

        return Assertions.fail("The running game has an open Hatchery site.");
    }

    private static boolean hasCreepAround(@NotNull GameMap map, int tileX, int tileY) {
        for (int row = tileY - CREEP_FREE_TILES; row <= tileY + CREEP_FREE_TILES; row++) {
            for (int column = tileX - CREEP_FREE_TILES; column <= tileX + CREEP_FREE_TILES; column++) {
                if (hasCreep(map, column, row)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean hasCreep(@NotNull GameMap map, int tileX, int tileY) {
        return TerrainTileset.isCreepTile(map.getTileIdentifier(tileX, tileY));
    }


    @ParameterizedTest
    @ValueSource(ints = {ScrOrderIds.BUILDING_LAND, ScrOrderIds.BUILDING_LIFTOFF})
    void accepts_move_unit_should_keep_a_type_past_units_dat_during_building_flight(int order) throws IOException {
        int[] flags = new int[UnitsDat.UNIT_COUNT];
        ScrUnitTypeView units = new ScrUnitTypeView(column -> new ArrayDatColumn(flags), ScrUnitTraits.createColumns(), UnitsDat.REQUIREMENT_INDEX,
                                                    (column, index) -> UnitTypeFlags.BUILDING);

        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            GameMap map = new GameMap(1, 1, 0, new int[1]);

            GameMapScenario.setOverlays(map, new int[1]);

            Game game = Game.create(gameData, map, new LcgRandom(0));
            Unit unit = UnitTestUtils.createUnit(1);

            unit.setUnitId(ScrUnitIds.NONE);
            unit.setPrimaryOrder(order);
            unit.setSprite(SpriteScenario.createSprite(0, 0, 0, 0));

            Assertions.assertTrue(game.getTriggerUnitActions().acceptsMoveUnit(new TriggerContext(), units, gameData.getTerrainTileset(0),
                                                                               unit, 0, TriggerUnitGroups.ANY_UNIT, 0));
        }
    }

    @Test
    void find_create_position_should_give_the_position_create_unit_places_the_unit_at() throws IOException {
        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.start(data);

            RunningGameScenario.runInCommandDispatch(runtime, () -> {
                Game game = runtime.getGame();
                TriggerUnitActions actions = game.getTriggerUnitActions();
                int[] position = new int[UnitPositioning.POSITION_COORDINATES];

                Assertions.assertNotNull(actions.createUnit(ScrUnitIds.TERRAN_MARINE, MARINE_X, MARINE_Y, RunningGameScenario.HUMAN,
                                                            RunningGameScenario.INSTRUCTION_LIMIT));
                Assertions.assertTrue(actions.findCreatePosition(ScrUnitIds.TERRAN_MARINE, MARINE_X, MARINE_Y, RunningGameScenario.HUMAN, position));

                Unit second = actions.createUnit(ScrUnitIds.TERRAN_MARINE, MARINE_X, MARINE_Y, RunningGameScenario.HUMAN,
                                                 RunningGameScenario.INSTRUCTION_LIMIT);

                Assertions.assertNotNull(second);
                Assertions.assertTrue(position[UnitPositioning.X_INDEX] != MARINE_X || position[UnitPositioning.Y_INDEX] != MARINE_Y,
                                      "the first Marine blocks the requested position");
                Assertions.assertEquals(position[UnitPositioning.X_INDEX], second.getPositionX());
                Assertions.assertEquals(position[UnitPositioning.Y_INDEX], second.getPositionY());
                Assertions.assertFalse(actions.findCreatePosition(ScrUnitIds.SPECIAL_FLOOR_GUN_TRAP, MARINE_X, MARINE_Y, RunningGameScenario.HUMAN,
                                                                  position), "a type without the unit listing flag is not created");
            });
        }
    }

    @Test
    void create_unit_without_failure_messages_should_fail_without_the_placement_warning() throws IOException {
        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.start(data);

            RunningGameScenario.runInCommandDispatch(runtime, () -> {
                Game game = runtime.getGame();
                int[] site = findHatcherySite(game);
                CommandErrorEvents errors = game.getInterfaceLink().getCommandErrorEvents();

                Assertions.assertNotNull(game.getTriggerUnitActions().createUnit(ScrUnitIds.ZERG_HATCHERY, site[0], site[1], RunningGameScenario.HUMAN,
                                                                                 RunningGameScenario.INSTRUCTION_LIMIT));

                int errorCount = errors.getCount();

                Assertions.assertNull(game.getTriggerUnitActions().createUnit(ScrUnitIds.ZERG_HATCHERY, site[0], site[1], RunningGameScenario.HUMAN,
                                                                              false, RunningGameScenario.INSTRUCTION_LIMIT));
                Assertions.assertEquals(errorCount, errors.getCount(), "a quiet failure raises no error message");

                Assertions.assertNull(game.getTriggerUnitActions().createUnit(ScrUnitIds.ZERG_HATCHERY, site[0], site[1], RunningGameScenario.HUMAN,
                                                                              RunningGameScenario.INSTRUCTION_LIMIT));
                Assertions.assertEquals(errorCount + 1, errors.getCount(), "Create Unit shows the placement warning");
            });
        }
    }

    @Test
    void create_unit_should_spread_creep_only_under_a_created_hatchery() throws IOException {
        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.start(data);

            RunningGameScenario.runInCommandDispatch(runtime, () -> {
                Game game = runtime.getGame();
                int[] site = findHatcherySite(game);
                int tileX = site[0] / TerrainTileset.TILE_SIZE;
                int tileY = site[1] / TerrainTileset.TILE_SIZE;
                Unit hatchery = game.getTriggerUnitActions().createUnit(ScrUnitIds.ZERG_HATCHERY, site[0], site[1], RunningGameScenario.HUMAN,
                                                                        RunningGameScenario.INSTRUCTION_LIMIT);

                Assertions.assertNotNull(hatchery);
                Assertions.assertTrue(hasCreep(game.getMap(), tileX, tileY));
                Assertions.assertFalse(hasCreep(game.getMap(), tileX - OUTSIDE_FOOTPRINT_TILES, tileY));
                Assertions.assertFalse(hasCreep(game.getMap(), tileX + OUTSIDE_FOOTPRINT_TILES, tileY));
            });
        }
    }

    @Test
    void create_unit_should_give_a_created_add_on_type_without_the_building_flag_to_the_neutral_player() throws IOException {
        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.start(data);

            RunningGameScenario.runInCommandDispatch(runtime, () -> {
                Game game = runtime.getGame();
                GameData gameData = game.requireGameData();
                int marine = ScrUnitIds.TERRAN_MARINE;

                gameData.getUnits().set(UnitsDat.FLAGS, marine, gameData.getUnitTypes().getFlags(marine) | UnitTypeFlags.ADDON);

                Unit created = game.getTriggerUnitActions().createUnit(marine, MARINE_X, MARINE_Y, RunningGameScenario.HUMAN,
                                                                       RunningGameScenario.INSTRUCTION_LIMIT);

                Assertions.assertNotNull(created);
                Assertions.assertEquals(NEUTRAL_PLAYER, created.getOwnerIndex());
            });
        }
    }

    @Test
    void create_unit_should_request_the_building_power_refresh_for_a_created_unit_that_is_no_building() throws IOException {
        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.start(data);

            RunningGameScenario.runInCommandDispatch(runtime, () -> {
                Game game = runtime.getGame();

                game.setBuildingPowerRefresh(0);

                Unit created = game.getTriggerUnitActions().createUnit(ScrUnitIds.TERRAN_MARINE, MARINE_X, MARINE_Y, RunningGameScenario.HUMAN,
                                                                       RunningGameScenario.INSTRUCTION_LIMIT);

                Assertions.assertNotNull(created);
                Assertions.assertEquals(POWER_REFRESH_REQUESTED, game.getBuildingPowerRefresh());
            });
        }
    }
}
