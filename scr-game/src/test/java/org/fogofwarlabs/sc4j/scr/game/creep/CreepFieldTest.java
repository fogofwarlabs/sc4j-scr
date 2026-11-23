package org.fogofwarlabs.sc4j.scr.game.creep;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteScenario;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.state.RuntimeRandom;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMapScenario;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.test.UnitTestUtils;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitMemoryScenario;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Verifies the creep fill against the native creep fill captures, the graphics cache read of a retained saved cache and the creep fill of one
 * tile.
 */
@RequiresInstallation
final class CreepFieldTest {
    private static final int FIXTURE_MAP_DIMENSION = 64;
    private static final int FIXTURE_TILE_COUNT = FIXTURE_MAP_DIMENSION * FIXTURE_MAP_DIMENSION;
    private static final int FIXTURE_BASE_TILE_IDENTIFIER = 128;
    private static final int VISIBILITY_CASE_PERIOD = 2;
    private static final int FIRST_PLAYER_VISION_FLAG = 1;
    /**
     * The player mask of the vision dialog that checks only player 0.
     */
    private static final int FIRST_PLAYER_MASK = 1;
    private static final int HIDDEN_TILE_PERIOD = 17;
    private static final int OCCUPANCY_CASE_PERIOD = 5;
    private static final int UNBUILDABLE_TILE_PERIOD = 43;
    private static final int OCCUPIED_TILE_PERIOD = 53;
    private static final int REPLAY_CASE_PERIOD = 4;
    private static final int REPLAY_CASE_COUNT = 2;
    private static final int REVEALED_CASE_PERIOD = 7;
    private static final int RANDOM_STEPS_PER_CHANGED_TILE = 2;
    /**
     * The tile group of a creep tile identifier.
     */
    private static final int CREEP_TILE_GROUP = 0x10;
    /**
     * The tile group bits of a tile identifier.
     */
    private static final int TILE_GROUP_MASK = 0x7FF0;
    private static final int UNIT_TYPE_OFFSET = 0x8C;
    private static final int ORDER_STATE_OFFSET = 0xC9;
    private static final int LARVA_TIMER_OFFSET = 0xEA;
    private static final int LARVA_TIMER_INITIAL = 102;
    private static final int LARVA_TIMER_EXPECTED = 78;
    private static final int LARVA_RETRY_TIMER_OFFSET = 0x118;
    private static final int LARVA_RETRY_TIMER_EXPECTED = 15;
    private static final int HATCHERY_ORDER_STATE = 8;
    private static final int PENDING_POSITION_X_OFFSET = 0x150;
    private static final int PENDING_POSITION_Y_OFFSET = 0x158;
    private static final int SECONDARY_ORDER_POSITION_OFFSET = 0x146;
    private static final int PENDING_POSITION_BYTE = 255;
    private static final int HATCHERY_FRAME_NUMBER = 10000;
    private static final int LIVE_PREFIX_GRAPHICS_WORD = 0x8001;
    private static final int SAVED_PREFIX_GRAPHICS_WORD = 0x7FFF;
    private static final int SAVED_TAIL_GRAPHICS_WORD = 0xFFFF;

    private static void assertFieldEquals(int caseIndex, int tileIndex, @NotNull String field, int expected, int actual) {
        if (expected != actual) {
            throw Exceptions.newAssertionError("case={0} tile={1} field={2} expected={3} actual={4}", caseIndex, tileIndex, field, expected, actual);
        }
    }

    @Test
    void matches_native_creep_fill_cases() throws IOException {
        byte[] fixture = GameTestResources.readAllBytes(GameTestResources.CAPTURE_CREEP_FILL);
        ByteBuffer input = ByteBuffer.wrap(fixture).order(ByteOrder.LITTLE_ENDIAN);

        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            int count = input.getInt();

            for (int caseIndex = 0; caseIndex < count; caseIndex++) {
                int type = input.getInt();
                int horizontal = input.getInt();
                int vertical = input.getInt();

                int randomSeed = input.getInt();
                int runtimeSeed = input.getInt();
                int invalidations = input.getInt();

                int expectedRandom = input.getInt();
                int expectedCount = input.getInt();
                int expectedRuntime = input.getInt();

                int[] tiles = new int[FIXTURE_TILE_COUNT];
                int[] flags = new int[FIXTURE_TILE_COUNT];
                int[] cache = new int[FIXTURE_TILE_COUNT];

                Arrays.fill(tiles, FIXTURE_BASE_TILE_IDENTIFIER);

                for (int tileIndex = 0; tileIndex < flags.length; tileIndex++) {
                    flags[tileIndex] = MapTileFlags.NOT_VISIBLE_OR_EXPLORED | MapTileFlags.WALKABLE;

                    if (caseIndex % VISIBILITY_CASE_PERIOD != 0 && tileIndex % HIDDEN_TILE_PERIOD == 0) {
                        flags[tileIndex] &= ~FIRST_PLAYER_VISION_FLAG;
                    }

                    if (caseIndex % OCCUPANCY_CASE_PERIOD == 0 && tileIndex % UNBUILDABLE_TILE_PERIOD == 0) {
                        flags[tileIndex] |= MapTileFlags.UNBUILDABLE;
                    }

                    if (caseIndex % OCCUPANCY_CASE_PERIOD == 1 && tileIndex % OCCUPIED_TILE_PERIOD == 0) {
                        flags[tileIndex] |= MapTileFlags.OCCUPIED;
                    }

                    cache[tileIndex] = tileIndex;
                }

                GameMap map = new GameMap(FIXTURE_MAP_DIMENSION, FIXTURE_MAP_DIMENSION, 0, tiles, flags);

                GameMapScenario.setOverlays(map, new int[FIXTURE_TILE_COUNT]);

                LcgRandom random = new LcgRandom(randomSeed);
                Game game = Game.create(data, map, random);
                RuntimeRandom runtimeRandom = game.getRuntimeRandom();
                CreepField creep = new CreepField(map, data.getTerrainTileset(0), cache);

                runtimeRandom.seed(runtimeSeed);

                // Two of every four cases are replays showing player 0's vision, revealed in every seventh case; the others are games of player 0.
                game.setReplayPlayback(caseIndex % REPLAY_CASE_PERIOD < REPLAY_CASE_COUNT);
                game.getLocalVision().acceptDialog(FIRST_PLAYER_MASK, caseIndex % REVEALED_CASE_PERIOD == 0);
                game.setCreepField(creep);
                creep.fill(game, type, horizontal, vertical);

                if (random.getState() != expectedRandom || random.getTotalRandomCounts() != expectedCount ||
                    runtimeRandom.getState() != expectedRuntime) {
                    throw Exceptions.newAssertionError("Random mismatch case={0} expected={1} actual={2}",
                                                       caseIndex, expectedCount, random.getTotalRandomCounts());
                }

                for (int tileIndex = 0; tileIndex < FIXTURE_TILE_COUNT; tileIndex++) {
                    assertFieldEquals(caseIndex, tileIndex, "base", Short.toUnsignedInt(input.getShort()), map.getTileIdentifier(tileIndex));
                }

                for (int tileIndex = 0; tileIndex < FIXTURE_TILE_COUNT; tileIndex++) {
                    assertFieldEquals(caseIndex, tileIndex, "overlay",
                                      Short.toUnsignedInt(input.getShort()), map.getOverlayTileIdentifier(tileIndex));
                }

                for (int tileIndex = 0; tileIndex < FIXTURE_TILE_COUNT; tileIndex++) {
                    assertFieldEquals(caseIndex, tileIndex, "edge", Byte.toUnsignedInt(input.get()), creep.getEdgeIdentifier(tileIndex));
                }

                for (int tileIndex = 0; tileIndex < FIXTURE_TILE_COUNT; tileIndex++) {
                    assertFieldEquals(caseIndex, tileIndex, "cache", Short.toUnsignedInt(input.getShort()), creep.getGraphicsCache(tileIndex));
                }

                for (int tileIndex = 0; tileIndex < FIXTURE_TILE_COUNT; tileIndex++) {
                    assertFieldEquals(caseIndex, tileIndex, "flags", input.getInt(), map.getTileFlags(tileIndex));
                }

                if (creep.hasPendingGraphicsInvalidation() != (invalidations != 0)) {
                    throw Exceptions.newAssertionError("Invalidation mismatch");
                }

                int changedTiles = 0;

                for (int tileIndex = 0; tileIndex < map.getTileCount(); tileIndex++) {
                    if ((map.getTileIdentifier(tileIndex) & TILE_GROUP_MASK) == CREEP_TILE_GROUP) {
                        changedTiles++;
                    }
                }

                Assertions.assertEquals(changedTiles * RANDOM_STEPS_PER_CHANGED_TILE, runtimeRandom.getDrawCount());

                creep.finishInitialization();

                Assertions.assertFalse(creep.growOnce(game, type, horizontal, vertical));
                Assertions.assertEquals(expectedRandom, random.getState());
                Assertions.assertEquals(expectedRuntime, runtimeRandom.getState());

                if (caseIndex == 0) {
                    Unit hatchery = UnitTestUtils.createUnit(1);

                    UnitMemoryScenario.of(hatchery).writeMemoryWord(UNIT_TYPE_OFFSET, ScrUnitIds.ZERG_HATCHERY);
                    UnitMemoryScenario.of(hatchery).writeMemoryByte(ORDER_STATE_OFFSET, HATCHERY_ORDER_STATE);
                    UnitMemoryScenario.of(hatchery).writeMemoryByte(LARVA_TIMER_OFFSET, LARVA_TIMER_INITIAL);
                    UnitMemoryScenario.of(hatchery).writeMemoryInteger(PENDING_POSITION_X_OFFSET, -1);
                    UnitMemoryScenario.of(hatchery).writeMemoryInteger(PENDING_POSITION_Y_OFFSET, -1);
                    UnitMemoryScenario.of(hatchery).writeMemoryByte(SECONDARY_ORDER_POSITION_OFFSET, PENDING_POSITION_BYTE);
                    hatchery.setSprite(SpriteScenario.createSprite(0, horizontal, vertical, 0));
                    hatchery.setCurrentBuildUnit(UnitTestUtils.createUnit(2));

                    game.getLarvaSpawner().updateHatchery(hatchery, HATCHERY_FRAME_NUMBER);

                    Assertions.assertEquals(LARVA_TIMER_EXPECTED, UnitMemoryScenario.of(hatchery).readMemoryUnsignedByte(LARVA_TIMER_OFFSET));
                    Assertions.assertEquals(LARVA_RETRY_TIMER_EXPECTED,
                                            UnitMemoryScenario.of(hatchery).readMemoryUnsignedByte(LARVA_RETRY_TIMER_OFFSET));
                    Assertions.assertEquals(-1, UnitMemoryScenario.of(hatchery).readMemoryInteger(PENDING_POSITION_X_OFFSET));
                    Assertions.assertFalse(hatchery.hasCurrentBuildUnit());
                    Assertions.assertEquals(0, UnitMemoryScenario.of(hatchery).readMemoryUnsignedByte(SECONDARY_ORDER_POSITION_OFFSET));
                    Assertions.assertEquals(expectedRandom, random.getState());

                    UnitMemoryScenario.of(hatchery).writeMemoryByte(ORDER_STATE_OFFSET, 0);

                    Assertions.assertThrows(UnsupportedOperationException.class,
                                            () -> game.getLarvaSpawner().updateIdleHatchery(hatchery, HATCHERY_FRAME_NUMBER));
                }

                Assertions.assertThrows(UnsupportedOperationException.class, () -> creep.fill(game, type, horizontal, vertical));
            }
        }
    }

    @Test
    void graphics_cache_should_keep_the_live_prefix_and_read_the_retained_saved_tail() throws IOException {
        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.start(data);
            GameMap map = runtime.getGame().getMap();
            CreepField creep = runtime.getGame().getCreepField();
            int tailIndex = map.getTileCount();

            Assertions.assertNotNull(creep);
            Assertions.assertTrue(tailIndex < GameMap.MAXIMUM_TILE_COUNT);

            ByteBuffer savedCache = ByteBuffer.allocate(GameMap.MAXIMUM_TILE_COUNT * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);

            savedCache.putShort(0, (short) SAVED_PREFIX_GRAPHICS_WORD);
            savedCache.putShort(tailIndex * Short.BYTES, (short) SAVED_TAIL_GRAPHICS_WORD);
            map.retainSavedArrays(new byte[0], savedCache.array(), new byte[0]);
            creep.restoreGraphicsCache(0, LIVE_PREFIX_GRAPHICS_WORD);
            savedCache.putShort(tailIndex * Short.BYTES, (short) 0);

            Assertions.assertEquals(LIVE_PREFIX_GRAPHICS_WORD, creep.getGraphicsCache(0), "The live prefix takes precedence over stale saved bytes.");
            Assertions.assertThrows(IndexOutOfBoundsException.class, () -> creep.getGraphicsCache(-1));
            Assertions.assertEquals(SAVED_TAIL_GRAPHICS_WORD, creep.getGraphicsCache(tailIndex));
            Assertions.assertEquals(LIVE_PREFIX_GRAPHICS_WORD, creep.getGraphicsCache(0));
        }
    }

    @Test
    void fill_tile_should_add_creep_once_to_a_tile_that_can_take_it() throws IOException {
        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            Game game = RunningGameScenario.start(data).getGame();
            GameMap map = game.getMap();
            CreepField creep = game.getCreepField();
            int open = -1;
            int occupied = -1;

            Assertions.assertNotNull(creep);

            for (int tileIndex = 0; tileIndex < map.getTileCount() - map.getWidth(); tileIndex++) {
                int flags = map.getTileFlags(tileIndex);
                boolean canTake = (flags & (MapTileFlags.UNBUILDABLE | MapTileFlags.PARTIALLY_WALKABLE | MapTileFlags.HAS_CREEP)) == 0 &&
                                  (map.getTileFlags(tileIndex + map.getWidth()) & MapTileFlags.UNBUILDABLE) == 0;

                if (canTake && (flags & MapTileFlags.OCCUPIED) == 0 && open < 0) {
                    open = tileIndex;
                } else if (canTake && (flags & MapTileFlags.OCCUPIED) != 0 && occupied < 0) {
                    occupied = tileIndex;
                }
            }

            Assertions.assertTrue(open >= 0 && occupied >= 0, "the map has an open and an occupied buildable tile");

            int openX = open % map.getWidth();
            int openY = open / map.getWidth();
            int terrain = map.getTerrainTileIdentifier(open);

            Assertions.assertTrue(creep.fillTile(game, openX, openY));
            Assertions.assertTrue(TerrainTileset.isCreepTile(map.getTileIdentifier(open)));
            Assertions.assertNotEquals(0, map.getTileFlags(open) & MapTileFlags.HAS_CREEP);
            Assertions.assertEquals(terrain, map.getTerrainTileIdentifier(open), "the terrain stays below the creep");
            Assertions.assertFalse(creep.fillTile(game, openX, openY), "a creep tile stays");
            Assertions.assertFalse(creep.fillTile(game, occupied % map.getWidth(), occupied / map.getWidth()), "an occupied tile stays");
            Assertions.assertEquals(0, map.getTileFlags(occupied) & MapTileFlags.HAS_CREEP);
            Assertions.assertThrows(IndexOutOfBoundsException.class, () -> creep.fillTile(game, map.getWidth(), 0));
        }
    }
}
