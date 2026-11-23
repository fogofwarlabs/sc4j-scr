package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Checks the order and the clearing of the sound events of a game.
 */
@RequiresInstallation
final class SoundEventsTest {
    /**
     * The width and height of the sound event map in tiles.
     */
    private static final int SOUND_EVENT_MAP_DIMENSION = 1;
    /**
     * The tile count of the sound event map.
     */
    private static final int SOUND_EVENT_MAP_TILE_COUNT = SOUND_EVENT_MAP_DIMENSION * SOUND_EVENT_MAP_DIMENSION;
    /**
     * The tileset of the sound event map.
     */
    private static final int TILESET = 0;
    /**
     * The seed of the game random generator.
     */
    private static final int RANDOM_SEED = 1;
    /**
     * The source unit type of a sound without a unit.
     */
    private static final int NO_UNIT_TYPE = 228;
    /**
     * The count of the added sound events.
     */
    private static final int EVENT_COUNT = 100;
    /**
     * The amount by which the horizontal position of an added event exceeds its sound.
     */
    private static final int POSITION_X_DISTANCE = 1;
    /**
     * The amount by which the vertical position of an added event exceeds its sound.
     */
    private static final int POSITION_Y_DISTANCE = 2;
    /**
     * The index of the first sound event.
     */
    private static final int FIRST_EVENT = 0;

    private static @Nullable GameData gameData;

    @BeforeAll
    static void loadGameData() throws IOException {
        gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath());
    }

    @AfterAll
    static void closeGameData() {
        if (gameData != null) {
            gameData.close();
        }
    }


    @Test
    void sound_events_keep_order_until_cleared() {
        Game game = Game.create(gameData,
                                new GameMap(SOUND_EVENT_MAP_DIMENSION, SOUND_EVENT_MAP_DIMENSION, TILESET, new int[SOUND_EVENT_MAP_TILE_COUNT]),
                                new LcgRandom(RANDOM_SEED));
        SoundEvents events = game.getInterfaceLink().getSoundEvents();

        for (int index = 0; index < EVENT_COUNT; index++) {
            events.add(index, index + POSITION_X_DISTANCE, index + POSITION_Y_DISTANCE, NO_UNIT_TYPE);
        }

        Assertions.assertEquals(EVENT_COUNT, events.getCount());

        for (int index = 0; index < EVENT_COUNT; index++) {
            Assertions.assertEquals(index, events.getSoundId(index));
            Assertions.assertEquals(index + POSITION_X_DISTANCE, events.getPositionX(index));
            Assertions.assertEquals(index + POSITION_Y_DISTANCE, events.getPositionY(index));
        }

        events.clear();

        Assertions.assertEquals(0, events.getCount());
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> events.getSoundId(FIRST_EVENT));
    }
}
