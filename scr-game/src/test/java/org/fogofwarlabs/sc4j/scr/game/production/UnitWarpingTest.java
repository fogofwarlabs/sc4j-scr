package org.fogofwarlabs.sc4j.scr.game.production;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ImageIds;
import org.fogofwarlabs.sc4j.scr.game.combat.ProjectilePhase;
import org.fogofwarlabs.sc4j.scr.game.frame.GameFrame;
import org.fogofwarlabs.sc4j.scr.game.frame.UnitPhase;
import org.fogofwarlabs.sc4j.scr.game.movement.UnitMovement;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffectPool;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameCheats;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.test.CampaignMissionScenario;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStorageConstructor;
import org.fogofwarlabs.sc4j.scr.game.visibility.MapVisibility;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Checks that a warping Protoss building counts its build time down, gains hit points and shields, and keeps its sprite through the warp states, and
 * that a unit warping in advances one warp-in state per action-done signal.
 */
@RequiresInstallation
final class UnitWarpingTest {
    /**
     * The tileset of the test maps.
     */
    private static final int TILESET = 0;
    /**
     * The owner of the warping building.
     */
    private static final int PLAYER = 0;

    private static final int MAP_DIMENSION = 32;
    private static final int MAP_TILE_COUNT = MAP_DIMENSION * MAP_DIMENSION;
    private static final int UNIT_CAPACITY = 1;
    private static final int SPRITE_CAPACITY = 4;
    private static final int IMAGE_CAPACITY = 32;
    private static final int INSTRUCTION_LIMIT = 10000;
    private static final int BUILDING_POSITION = 320;

    /**
     * The unit pool index of the warping building.
     */
    private static final int BUILDING_POOL_INDEX = 1;

    private static final int PRIMARY_REDISTRIBUTION_TIMER = 10;
    private static final int SECONDARY_REDISTRIBUTION_TIMER = 150;
    private static final int VISIBILITY_CONTROL_COUNT = 5;

    private static final int INITIAL_HIT_POINTS = 256;
    private static final int INITIAL_SHIELD_POINTS = 256;
    private static final int HIT_POINT_INCREMENT = 73;
    private static final int SHIELD_INCREMENT = 53;

    /**
     * The factor of the construction hit point and shield increments under Operation Cwal (a left shift by {@code 4}).
     */
    private static final int OPERATION_CWAL_INCREMENT_FACTOR = 16;

    private static final int REMAINING_BUILD_TIME = 7;
    private static final int DISABLED_BUILD_TIME = 2;
    private static final int PRIMARY_ORDER_COUNTDOWN = 7;

    private static final int WARP_IN_SOUND = 529;
    private static final int PYLON_TYPE = 156;

    /**
     * The order signal bit that the warp scripts set and wait for.
     */
    private static final int WARP_SIGNAL = 1;

    /**
     * The {@link ScrOrderIds#INCOMPLETE_WARPING} states: counting down, starting the warp texture, warping in, completing.
     */
    private static final int COUNTDOWN_STATE = 0;
    private static final int TEXTURE_STATE = 1;
    private static final int WARP_IN_STATE = 2;
    private static final int COMPLETION_STATE = 3;

    private static final int WARP_TEXTURE_MODIFIER = 12;
    private static final int WARP_FLASH_MODIFIER = 17;
    private static final int WARP_IN_ANIMATION = 21;
    private static final int MAXIMUM_ANIMATION_FRAMES = 256;

    /**
     * The campaign mission of the warp-in test, played as Brood War, and the turns that run before the test body starts.
     */
    private static final @NotNull String WARP_IN_MISSION = "bw-protoss-1";
    private static final int START_TURNS = 5;
    /**
     * The order of a unit warping in.
     */
    private static final int WARP_IN_ORDER = 174;

    private static @Nullable GameData gameData;

    @BeforeAll
    static void loadGameData() throws IOException {
        gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath());
    }

    @AfterAll
    static void closeGameData() {
        if (gameData != null) {
            IgnoreException.execute(
                    gameData::close
            );
        }
    }

    private static @NotNull Game createState() {
        Game game = Game.create(gameData, new GameMap(MAP_DIMENSION, MAP_DIMENSION, TILESET, new int[MAP_TILE_COUNT]), new LcgRandom(0));

        game.getUnitSystem().initializeUnitPool(UNIT_CAPACITY);
        game.getSpriteSystem().initializeSpritePool(SPRITE_CAPACITY);
        game.getSpriteSystem().initializeImagePool(IMAGE_CAPACITY);
        game.initializeExecutionScope();

        UnitPool unitPool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(unitPool);

        Unit building = unitPool.getUnit(BUILDING_POOL_INDEX);

        UnitStorageConstructor.clearBeforeInnerInitialization(building);

        Assertions.assertTrue(game.getUnitInitializer().initialize(building,
                                                                   ScrUnitIds.PROTOSS_PYLON,
                                                                   BUILDING_POSITION, BUILDING_POSITION,
                                                                   PLAYER,
                                                                   INSTRUCTION_LIMIT));

        game.getIscriptExecutor().replaceSpriteImages(building, ImageIds.WARP_ANCHOR, 0, INSTRUCTION_LIMIT);
        building.setPrimaryOrder(ScrOrderIds.INCOMPLETE_WARPING);
        building.setPrimaryOrderState(COUNTDOWN_STATE);

        return game;
    }


    @Test
    void update_should_clamp_build_time_and_apply_sixteen_increments_under_operation_cwal() throws IOException {
        Game game = createState();

        Assertions.assertNotNull(gameData);

        MapVisibility visibility = new MapVisibility(gameData.getTerrainTileset(TILESET), new int[VISIBILITY_CONTROL_COUNT]);

        visibility.setCheatFlags(GameCheats.OPERATION_CWAL);
        game.setFrame(new GameFrame(new UnitPhase.Context(PRIMARY_REDISTRIBUTION_TIMER, SECONDARY_REDISTRIBUTION_TIMER),
                                    new UnitMovement.Context(0, 0),
                                    new ProjectilePhase.Context(),
                                    new SpriteEffectPool(1),
                                    visibility));

        UnitPool unitPool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(unitPool);

        Unit building = unitPool.getUnit(BUILDING_POOL_INDEX);

        building.setRemainingBuildTime(REMAINING_BUILD_TIME);
        building.setHitPoints(INITIAL_HIT_POINTS);
        building.setShieldPoints(INITIAL_SHIELD_POINTS);
        building.setConstructionHitPointIncrement(HIT_POINT_INCREMENT);
        building.setConstructionShieldIncrement(SHIELD_INCREMENT);

        game.getUnitWarping().update(building, INSTRUCTION_LIMIT);

        Assertions.assertEquals(0, building.getRemainingBuildTime());
        Assertions.assertEquals(INITIAL_HIT_POINTS + HIT_POINT_INCREMENT * OPERATION_CWAL_INCREMENT_FACTOR, building.getHitPoints());
        Assertions.assertEquals(INITIAL_SHIELD_POINTS + SHIELD_INCREMENT * OPERATION_CWAL_INCREMENT_FACTOR, building.getShieldPoints());
        Assertions.assertEquals(COUNTDOWN_STATE, building.getPrimaryOrderState());
    }

    @Test
    void update_primary_should_advance_disabled_building_each_frame_keeping_countdown() {
        Game game = createState();
        UnitPool unitPool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(unitPool);

        Unit building = unitPool.getUnit(BUILDING_POOL_INDEX);

        building.setStatus(building.getStatus() | UnitStatusFlags.DISABLED);
        building.setPrimaryOrderCountdown(PRIMARY_ORDER_COUNTDOWN);
        building.setRemainingBuildTime(DISABLED_BUILD_TIME);
        building.setHitPoints(INITIAL_HIT_POINTS);
        building.setShieldPoints(INITIAL_SHIELD_POINTS);
        building.setConstructionHitPointIncrement(HIT_POINT_INCREMENT);
        building.setConstructionShieldIncrement(SHIELD_INCREMENT);
        building.requireSprite().setVisibilityFlags(Sprite.VISIBLE_TO_ALL_PLAYERS);
        game.getLocalVision().setComparisonMask(1 << PLAYER);

        game.getUnitOrders().updatePrimary(building, PlayerTypes.HUMAN, INSTRUCTION_LIMIT);

        Assertions.assertEquals(1, building.getRemainingBuildTime());
        Assertions.assertEquals(INITIAL_HIT_POINTS + HIT_POINT_INCREMENT, building.getHitPoints());
        Assertions.assertEquals(INITIAL_SHIELD_POINTS + SHIELD_INCREMENT, building.getShieldPoints());
        Assertions.assertEquals(PRIMARY_ORDER_COUNTDOWN, building.getPrimaryOrderCountdown());

        game.getUnitOrders().updatePrimary(building, PlayerTypes.HUMAN, INSTRUCTION_LIMIT);

        Assertions.assertEquals(0, building.getRemainingBuildTime());
        Assertions.assertEquals(COUNTDOWN_STATE, building.getPrimaryOrderState());

        game.getUnitOrders().updatePrimary(building, PlayerTypes.HUMAN, INSTRUCTION_LIMIT);

        Assertions.assertEquals(TEXTURE_STATE, building.getPrimaryOrderState());
        Assertions.assertEquals(WARP_IN_SOUND, game.getInterfaceLink().getSoundEvents().getSoundId(0));
        Assertions.assertEquals(PRIMARY_ORDER_COUNTDOWN, building.getPrimaryOrderCountdown());
    }

    @Test
    void update_should_keep_building_sprite_across_texture_and_warp_animation_states() {
        Game game = createState();
        UnitPool unitPool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(unitPool);

        Unit building = unitPool.getUnit(BUILDING_POOL_INDEX);
        Sprite sprite = building.getSpriteOrNull();

        building.setPrimaryOrderState(TEXTURE_STATE);
        building.setOrderSignal(WARP_SIGNAL);

        game.getUnitWarping().update(building, INSTRUCTION_LIMIT);

        Assertions.assertSame(sprite, building.getSpriteOrNull());
        Assertions.assertEquals(WARP_IN_STATE, building.getPrimaryOrderState());
        Assertions.assertNotNull(sprite);

        SpriteImage textureImage = sprite.getMainImage();

        Assertions.assertNotNull(textureImage);

        Assertions.assertEquals(WARP_TEXTURE_MODIFIER, textureImage.getModifier());
        Assertions.assertNotNull(gameData);

        Assertions.assertEquals(gameData.getImages().get("Iscript", textureImage.getImageTypeId()), textureImage.getScriptIdentifier());

        building.setOrderSignal(building.getOrderSignal() | WARP_SIGNAL);

        game.getUnitWarping().update(building, INSTRUCTION_LIMIT);

        Assertions.assertSame(sprite, building.getSpriteOrNull());
        Assertions.assertEquals(COMPLETION_STATE, building.getPrimaryOrderState());

        SpriteImage animationImage = sprite.getMainImage();

        Assertions.assertNotNull(animationImage);

        Assertions.assertEquals(WARP_IN_ANIMATION, animationImage.getAnimationType());
        Assertions.assertEquals(PYLON_TYPE, building.getUnitId());

        boolean hasWarpOverlay = false;

        for (SpriteImage image : sprite.getImages()) {
            hasWarpOverlay |= image.getModifier() == WARP_FLASH_MODIFIER;
        }

        Assertions.assertTrue(hasWarpOverlay);

        building.setOrderSignal(building.getOrderSignal() & ~WARP_SIGNAL);

        for (int frame = 0; frame < MAXIMUM_ANIMATION_FRAMES && (building.getOrderSignal() & WARP_SIGNAL) == 0; frame++) {
            game.getIscriptExecutor().executeSprite(sprite, building, INSTRUCTION_LIMIT);
        }

        Assertions.assertEquals(WARP_SIGNAL, building.getOrderSignal() & WARP_SIGNAL);
        Assertions.assertSame(sprite, building.getSpriteOrNull());
    }

    @Test
    void update_warp_in_should_advance_one_state_per_action_done_signal_then_finish() throws IOException {
        CampaignMissionScenario.run(ExternalInput.INSTALLATION.getPath(), WARP_IN_MISSION, true, (game, live) -> {
            Game state = live.getGame();

            CampaignMissionScenario.exchangeTurnsAndStep(game, live, START_TURNS);

            int mobileOwner = game.getLocalPlayer();

            UnitPool homePool = state.getUnitSystem().getUnitPool();

            Assertions.assertNotNull(homePool);

            Unit home = null;

            for (int index = 1; index <= homePool.getCapacity(); index++) {
                if (homePool.isAllocated(index) && homePool.getUnit(index).getOwnerIndex() == mobileOwner &&
                    (homePool.getUnit(index).getStatus() & UnitStatusFlags.CAN_MOVE) != 0) {
                    home = homePool.getUnit(index);

                    break;
                }
            }

            if (home == null) {
                Assertions.fail("No mobile unit of player " + mobileOwner);
            }

            Unit zealot = CampaignMissionScenario.createUnit(state, ScrUnitIds.PROTOSS_ZEALOT, home.getPositionX(), home.getPositionY(),
                                                             game.getLocalPlayer());

            state.getOrders().issueUntargeted(zealot, ScrOrderIds.WARP_IN, RunningGameScenario.INSTRUCTION_LIMIT);
            zealot.setOrderSignal(zealot.getOrderSignal() & ~Unit.ORDER_SIGNAL_ACTION_DONE);

            state.getUnitWarping().updateWarpIn(zealot, RunningGameScenario.INSTRUCTION_LIMIT);

            Assertions.assertEquals(0, zealot.getPrimaryOrderState(), "WarpIn state 0 waits for order signal bit 1");

            zealot.setOrderSignal(zealot.getOrderSignal() | Unit.ORDER_SIGNAL_ACTION_DONE);

            state.getUnitWarping().updateWarpIn(zealot, RunningGameScenario.INSTRUCTION_LIMIT);

            Assertions.assertEquals(1, zealot.getPrimaryOrderState());
            Assertions.assertEquals(0, zealot.getOrderSignal() & Unit.ORDER_SIGNAL_ACTION_DONE);

            zealot.setOrderSignal(zealot.getOrderSignal() | Unit.ORDER_SIGNAL_ACTION_DONE);

            state.getUnitWarping().updateWarpIn(zealot, RunningGameScenario.INSTRUCTION_LIMIT);

            Assertions.assertNotEquals(WARP_IN_ORDER, zealot.getPrimaryOrder());
            Assertions.assertEquals(0, zealot.getStatus() & UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE);
        });
    }
}
