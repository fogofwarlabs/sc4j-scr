package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SoundIds;
import org.fogofwarlabs.sc4j.scr.game.ability.TwoUnitGameScenario;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitCreationContext;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Checks that unit and position sounds play only where the local view sees them.
 */
@RequiresInstallation
final class UnitSoundsTest {
    /**
     * The sound the tests play.
     */
    private static final int SOUND = SoundIds.NYDUS_CANAL_TRANSIT;
    /**
     * The vision bit of the player who owns the units.
     */
    private static final int OWNER_BIT = 1 << TwoUnitGameScenario.PLAYER;
    /**
     * The vision bit of the other player.
     */
    private static final int OTHER_BIT = 1 << TwoUnitGameScenario.OTHER_PLAYER;
    /**
     * A position on the test map, on tile {@code (3, 2)}.
     */
    private static final int POSITION_X = 100;
    /**
     * The vertical coordinate of {@link #POSITION_X}.
     */
    private static final int POSITION_Y = 70;
    /**
     * The horizontal tile of {@link #POSITION_X}.
     */
    private static final int TILE_X = 3;
    /**
     * The vertical tile of {@link #POSITION_Y}.
     */
    private static final int TILE_Y = 2;
    /**
     * A horizontal position past the right edge of the {@code 32} tile wide test map.
     */
    private static final int OUTSIDE_POSITION_X = 32 * 32;

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
    void a_unit_sound_plays_when_the_local_player_sees_or_owns_the_unit() {
        Game game = TwoUnitGameScenario.createGame(gameData, ScrUnitIds.ZERG_DRONE, ScrUnitIds.ZERG_DRONE);
        Unit unit = getUnit(game);
        Sprite sprite = unit.requireSprite();
        SoundEvents events = game.getInterfaceLink().getSoundEvents();

        game.getLocalVision().setComparisonMask(OTHER_BIT);
        game.getUnitSystem().setUnitCreationContext(createContext(TwoUnitGameScenario.OTHER_PLAYER));
        sprite.setVisibilityFlags(OWNER_BIT);
        game.getUnitSounds().play(SOUND, unit);

        Assertions.assertEquals(0, events.getCount());

        sprite.setVisibilityFlags(OWNER_BIT | OTHER_BIT);
        game.getUnitSounds().play(SOUND, unit);

        Assertions.assertEquals(1, events.getCount());
        Assertions.assertEquals(sprite.getX(), events.getPositionX(0));
        Assertions.assertEquals(unit.getUnitId(), events.getSourceUnitType(0));

        events.clear();
        game.getLocalVision().setComparisonMask(OWNER_BIT);
        game.getUnitSystem().setUnitCreationContext(createContext(TwoUnitGameScenario.PLAYER));
        sprite.setVisibilityFlags(0);
        game.getUnitSounds().play(SOUND, unit);

        Assertions.assertEquals(1, events.getCount());
    }

    @Test
    void a_position_sound_plays_on_a_tile_the_local_player_sees_inside_the_map() {
        Game game = TwoUnitGameScenario.createGame(gameData);
        SoundEvents events = game.getInterfaceLink().getSoundEvents();

        game.getLocalVision().setComparisonMask(OTHER_BIT);
        game.getMap().setTileVisibility(TILE_X, TILE_Y, OTHER_BIT);
        game.getUnitSounds().playAt(SOUND, POSITION_X, POSITION_Y);

        Assertions.assertEquals(0, events.getCount());

        game.getMap().setTileVisibility(TILE_X, TILE_Y, OWNER_BIT);
        game.getUnitSounds().playAt(SOUND, POSITION_X, POSITION_Y);
        game.getUnitSounds().playAt(SOUND, OUTSIDE_POSITION_X, POSITION_Y);

        Assertions.assertEquals(1, events.getCount());
        Assertions.assertEquals(POSITION_X, events.getPositionX(0));
        Assertions.assertEquals(POSITION_Y, events.getPositionY(0));
        Assertions.assertEquals(ScrUnitIds.NONE, events.getSourceUnitType(0));
    }


    private static @NotNull Unit getUnit(@NotNull Game game) {
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        return pool.getUnit(TwoUnitGameScenario.CASTER_INDEX);
    }

    private static @NotNull UnitCreationContext createContext(int localPlayer) {
        return new UnitCreationContext(false, false, false, false, false, localPlayer);
    }
}
