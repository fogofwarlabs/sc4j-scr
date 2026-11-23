package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Checks the order and the clearing of the sound events of a game, and the file of a sound file event.
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
    /**
     * The sound file of the sound file event, its position, and the sound of the table that takes its event slot afterwards.
     */
    private static final byte @NotNull [] SOUND_FILE = {'R', 'I', 'F', 'F'};
    private static final int FILE_POSITION_X = 40;
    private static final int FILE_POSITION_Y = 72;
    private static final int TABLE_SOUND = 7;

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

    @Test
    void a_sound_file_event_holds_its_file_until_its_slot_takes_a_sound_of_the_table() {
        SoundEvents events = new SoundEvents();

        events.addFile(SOUND_FILE, FILE_POSITION_X, FILE_POSITION_Y);

        Assertions.assertEquals(1, events.getCount());
        Assertions.assertEquals(SoundEvents.FILE_SOUND_ID, events.getSoundId(FIRST_EVENT));
        Assertions.assertSame(SOUND_FILE, events.getFile(FIRST_EVENT));
        Assertions.assertEquals(FILE_POSITION_X, events.getPositionX(FIRST_EVENT));
        Assertions.assertEquals(FILE_POSITION_Y, events.getPositionY(FIRST_EVENT));
        Assertions.assertEquals(ScrUnitIds.NONE, events.getSourceUnitType(FIRST_EVENT));

        events.clear();
        events.add(TABLE_SOUND, FILE_POSITION_X, FILE_POSITION_Y, NO_UNIT_TYPE);

        Assertions.assertEquals(TABLE_SOUND, events.getSoundId(FIRST_EVENT));
        Assertions.assertNull(events.getFile(FIRST_EVENT));
    }
}
