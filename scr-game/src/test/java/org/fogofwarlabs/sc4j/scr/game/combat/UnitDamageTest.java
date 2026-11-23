package org.fogofwarlabs.sc4j.scr.game.combat;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerRegionScenario;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerUnitRecord;
import org.fogofwarlabs.sc4j.scr.game.frame.GameFrame;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegionScenario;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Checks how {@link UnitDamage} deals weapon damage to shields and hit points, makes damaged units flee or retaliate, computes flee positions, and
 * applies Subterranean Spines to the own target, other own units and enemies.
 */
@RequiresInstallation
final class UnitDamageTest {
    /**
     * The image and order instruction budget of the created units, the primary order updates and the damage.
     */
    private static final int INSTRUCTION_LIMIT = 10000;
    /**
     * The first two flee distances of the second unit of the duel world, which flees east from the first unit, and their flee positions.
     */
    private static final int FIRST_FLEE_DISTANCE = 192;
    private static final int FIRST_FLEE_X = CombatGameScenario.SECOND_UNIT_X + FIRST_FLEE_DISTANCE;
    private static final int SECOND_FLEE_DISTANCE = 128;
    private static final int SECOND_FLEE_X = CombatGameScenario.SECOND_UNIT_X + SECOND_FLEE_DISTANCE;
    /**
     * The remaining flee distances.
     */
    private static final int THIRD_FLEE_DISTANCE = 64;
    private static final int FOURTH_FLEE_DISTANCE = 32;
    private static final int FIFTH_FLEE_DISTANCE = 16;

    /**
     * The heading of the weapon damage that hits the second unit.
     */
    private static final int HIT_HEADING = 64;
    /**
     * The fraction bits of the fixed-point hit, shield and damage points.
     */
    private static final int FIXED_POINT_FRACTION_BITS = 8;
    /**
     * The hit points and shield points of the target before the first hit.
     */
    private static final int STARTING_HIT_POINTS = 20 << FIXED_POINT_FRACTION_BITS;
    private static final int STARTING_SHIELD_POINTS = 20 << FIXED_POINT_FRACTION_BITS;
    /**
     * The particle beam damage of the hits.
     */
    private static final int PARTICLE_BEAM_DAMAGE = 5 << FIXED_POINT_FRACTION_BITS;
    /**
     * The shield points that remain after the first particle beam hit.
     */
    private static final int SHIELD_POINTS_AFTER_HIT = 15 << FIXED_POINT_FRACTION_BITS;
    /**
     * The shield points that the second particle beam hit breaks, and the hit points that remain after that hit.
     */
    private static final int BREAKING_SHIELD_POINTS = 2 << FIXED_POINT_FRACTION_BITS;
    private static final int HIT_POINTS_AFTER_SHIELD_BREAK = 17 << FIXED_POINT_FRACTION_BITS;
    /**
     * The Arclite Cannon damage of the explosive hit, and the part of it that the shields do not absorb.
     */
    private static final int ARCLITE_CANNON_DAMAGE = 30 << FIXED_POINT_FRACTION_BITS;
    private static final int EXPLOSIVE_DAMAGE_PAST_SHIELDS = 10 << FIXED_POINT_FRACTION_BITS;
    /**
     * The hit points that one particle beam hit kills.
     */
    private static final int LETHAL_HIT_POINTS = 3 << FIXED_POINT_FRACTION_BITS;
    /**
     * The particle beam damage that remains after the armor of the Zealot.
     */
    private static final int ZEALOT_ARMORED_DAMAGE = 4 << FIXED_POINT_FRACTION_BITS;
    /**
     * The minimum damage of a hit, half a point in fixed point.
     */
    private static final int HALF_POINT = 128;
    /**
     * The damage divisor of a hit whose damage is not divided.
     */
    private static final int UNDIVIDED_DAMAGE_DIVISOR = 1;
    /**
     * The divisor of explosive damage against a small target.
     */
    private static final int EXPLOSIVE_SMALL_DIVISOR = 2;
    /**
     * The weapon of damage without a weapon, the {@code weapons.dat} damage types of explosive and normal damage, and the damage of the hits
     * without a weapon.
     */
    private static final int NO_WEAPON = -1;
    private static final int EXPLOSIVE_DAMAGE_TYPE = 1;
    private static final int NORMAL_DAMAGE_TYPE = 3;
    private static final int UNARMED_DAMAGE = 10 << FIXED_POINT_FRACTION_BITS;
    /**
     * The order of a fleeing worker and the order of a killed unit.
     */
    private static final int MOVE_ORDER = 6;
    private static final int DIE_ORDER = 0;

    /**
     * The vertical position of the lurker, its own target and the enemy zergling.
     */
    private static final int UNIT_POSITION_Y = 200;
    /**
     * The vertical position of the other own zergling.
     */
    private static final int BYSTANDER_POSITION_Y = 212;
    /**
     * The horizontal positions of the lurker, of its own target and the other own zergling, and of the enemy zergling.
     */
    private static final int LURKER_POSITION_X = 200;
    private static final int TARGET_POSITION_X = 240;
    private static final int ENEMY_POSITION_X = 252;
    /**
     * The unit, sprite and image capacities of the Subterranean Spines world.
     */
    private static final int SPINES_UNIT_CAPACITY = 4;
    private static final int SPINES_SPRITE_CAPACITY = 8;
    private static final int SPINES_IMAGE_CAPACITY = 64;
    /**
     * The pool indices of the lurker, of its own target, of the other own zergling and of the enemy zergling.
     */
    private static final int LURKER_INDEX = 1;
    private static final int TARGET_INDEX = 2;
    private static final int BYSTANDER_INDEX = 3;
    private static final int ENEMY_INDEX = 4;
    /**
     * The heading of the Subterranean Spines bullet.
     */
    private static final int BULLET_HEADING = 64;
    /**
     * The bounce damage divisor of a bullet that has not bounced.
     */
    private static final int UNBOUNCED_DAMAGE_DIVISOR = 1;

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

    private static @NotNull Game createDuelGame(int firstUnitType, int secondUnitType) {
        Assertions.assertNotNull(gameData);

        return CombatGameScenario.createDuelGame(gameData, firstUnitType, secondUnitType);
    }

    /**
     * Creates a {@link Game} instance whose second unit, a Zealot, stands in the left region of the neighbor-region input and flees east from the
     * first one into the right region.
     *
     * @param sharedComponent whether the right region is in the component of the left region
     * @return the created {@link Game} instance
     */
    private static @NotNull Game createFleeGame(boolean sharedComponent) {
        Game game = createDuelGame(ScrUnitIds.PROTOSS_ZEALOT, ScrUnitIds.PROTOSS_ZEALOT);
        TerrainContours contours = game.getPathSystem().getTerrainContours();

        Assertions.assertNotNull(contours);

        TerrainRegionScenario.configureNeighborRegions(contours.getRegions(), sharedComponent);

        return game;
    }

    /**
     * Creates a {@link Game} instance whose second unit, a Zealot, stands in the left region of the region-row input and flees east from the first
     * one: the first flee position lies in the far region, the second one in the middle region. The game also has the thingy pool that the saved
     * region records of {@link ComputerRegionScenario} need.
     *
     * @return the created {@link Game} instance
     */
    private static @NotNull Game createRegionRowFleeGame() {
        Game game = createDuelGame(ScrUnitIds.PROTOSS_ZEALOT, ScrUnitIds.PROTOSS_ZEALOT);
        TerrainContours contours = game.getPathSystem().getTerrainContours();

        Assertions.assertNotNull(contours);

        TerrainRegionScenario.configureRegionRow(contours.getRegions());
        game.getSpriteSystem().initializeSpriteEffectPool(1);

        return game;
    }


    @Test
    void deal_weapon_damage_should_consume_shields_before_armor_and_hit_points() {
        Game game = createDuelGame(ScrUnitIds.PROTOSS_PROBE, ScrUnitIds.PROTOSS_PROBE);
        UnitPool unitPool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(unitPool);

        Unit attacker = unitPool.getUnit(CombatGameScenario.FIRST_UNIT_INDEX);
        Unit target = unitPool.getUnit(CombatGameScenario.SECOND_UNIT_INDEX);

        target.setHitPoints(STARTING_HIT_POINTS);
        target.setShieldPoints(STARTING_SHIELD_POINTS);
        game.getUnitDamage().dealWeaponDamage(ScrWeaponIds.PARTICLE_BEAM,
                                              PARTICLE_BEAM_DAMAGE,
                                              UNDIVIDED_DAMAGE_DIVISOR,
                                              attacker, 0,
                                              target, 0,
                                              INSTRUCTION_LIMIT);

        Assertions.assertEquals(SHIELD_POINTS_AFTER_HIT, target.getShieldPoints());
        Assertions.assertEquals(STARTING_HIT_POINTS, target.getHitPoints());
        Assertions.assertEquals(0, target.getLastAttackingPlayer());

        target.setShieldPoints(BREAKING_SHIELD_POINTS);
        game.getUnitDamage().dealWeaponDamage(ScrWeaponIds.PARTICLE_BEAM,
                                              PARTICLE_BEAM_DAMAGE,
                                              UNDIVIDED_DAMAGE_DIVISOR,
                                              attacker, 0,
                                              target, 0,
                                              INSTRUCTION_LIMIT);

        Assertions.assertEquals(0, target.getShieldPoints());
        Assertions.assertEquals(HIT_POINTS_AFTER_SHIELD_BREAK, target.getHitPoints());

        target.setShieldPoints(STARTING_SHIELD_POINTS);
        game.getUnitDamage().dealWeaponDamage(ScrWeaponIds.ARCLITE_CANNON,
                                              ARCLITE_CANNON_DAMAGE,
                                              UNDIVIDED_DAMAGE_DIVISOR,
                                              attacker, 0,
                                              target, 0,
                                              INSTRUCTION_LIMIT);

        Assertions.assertEquals(0, target.getShieldPoints());
        Assertions.assertEquals(HIT_POINTS_AFTER_SHIELD_BREAK - EXPLOSIVE_DAMAGE_PAST_SHIELDS / EXPLOSIVE_SMALL_DIVISOR, target.getHitPoints());
    }

    @Test
    void deal_weapon_damage_should_scale_damage_of_a_given_damage_type_without_a_weapon() {
        Game game = createDuelGame(ScrUnitIds.PROTOSS_PROBE, ScrUnitIds.PROTOSS_PROBE);
        UnitPool unitPool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(unitPool);

        Unit attacker = unitPool.getUnit(CombatGameScenario.FIRST_UNIT_INDEX);
        Unit target = unitPool.getUnit(CombatGameScenario.SECOND_UNIT_INDEX);

        target.setHitPoints(STARTING_HIT_POINTS);
        target.setShieldPoints(0);
        game.getUnitDamage().dealWeaponDamage(NO_WEAPON, EXPLOSIVE_DAMAGE_TYPE,
                                              UNARMED_DAMAGE,
                                              UNDIVIDED_DAMAGE_DIVISOR,
                                              attacker, 0,
                                              target, 0,
                                              INSTRUCTION_LIMIT);

        Assertions.assertEquals(STARTING_HIT_POINTS - UNARMED_DAMAGE / EXPLOSIVE_SMALL_DIVISOR, target.getHitPoints());

        target.setHitPoints(STARTING_HIT_POINTS);
        game.getUnitDamage().dealWeaponDamage(NO_WEAPON, NORMAL_DAMAGE_TYPE,
                                              UNARMED_DAMAGE,
                                              UNDIVIDED_DAMAGE_DIVISOR,
                                              attacker, 0,
                                              target, 0,
                                              INSTRUCTION_LIMIT);

        Assertions.assertEquals(STARTING_HIT_POINTS - UNARMED_DAMAGE, target.getHitPoints());
    }

    @Test
    void deal_weapon_damage_should_apply_half_point_minimum_and_kill_on_lethal_damage() {
        Game game = createDuelGame(ScrUnitIds.PROTOSS_PROBE, ScrUnitIds.PROTOSS_PROBE);
        UnitPool unitPool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(unitPool);

        Unit attacker = unitPool.getUnit(CombatGameScenario.FIRST_UNIT_INDEX);
        Unit target = unitPool.getUnit(CombatGameScenario.SECOND_UNIT_INDEX);

        target.setHitPoints(STARTING_HIT_POINTS);
        target.setShieldPoints(0);
        game.getUnitDamage().dealWeaponDamage(ScrWeaponIds.PARTICLE_BEAM,
                                              0,
                                              UNDIVIDED_DAMAGE_DIVISOR,
                                              attacker, 0,
                                              target, 0,
                                              INSTRUCTION_LIMIT);

        Assertions.assertEquals(STARTING_HIT_POINTS - HALF_POINT, target.getHitPoints());

        target.setHitPoints(LETHAL_HIT_POINTS);
        game.getUnitDamage().dealWeaponDamage(ScrWeaponIds.PARTICLE_BEAM,
                                              PARTICLE_BEAM_DAMAGE,
                                              UNDIVIDED_DAMAGE_DIVISOR,
                                              attacker, 0,
                                              target, 0,
                                              INSTRUCTION_LIMIT);

        Assertions.assertEquals(0, target.getHitPoints());
        Assertions.assertEquals(DIE_ORDER, target.getPrimaryOrder());

        game.getUnitDamage().dealWeaponDamage(ScrWeaponIds.PARTICLE_BEAM,
                                              PARTICLE_BEAM_DAMAGE,
                                              UNDIVIDED_DAMAGE_DIVISOR,
                                              attacker, 0,
                                              target, 0,
                                              INSTRUCTION_LIMIT);

        Assertions.assertEquals(0, target.getHitPoints());
    }

    @Test
    void damaged_worker_flees_and_damaged_fighter_retaliates() {
        Assertions.assertNotNull(gameData);

        Game game = createDuelGame(ScrUnitIds.PROTOSS_PROBE, ScrUnitIds.PROTOSS_PROBE);
        UnitPool unitPool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(unitPool);

        Unit attacker = unitPool.getUnit(CombatGameScenario.FIRST_UNIT_INDEX);
        Unit worker = unitPool.getUnit(CombatGameScenario.SECOND_UNIT_INDEX);

        worker.setShieldPoints(0);

        boolean fleeable = gameData.getOrders().get("Fleeable", worker.getPrimaryOrder()) != 0;

        game.getUnitDamage().dealWeaponDamage(ScrWeaponIds.PARTICLE_BEAM,
                                              PARTICLE_BEAM_DAMAGE,
                                              UNDIVIDED_DAMAGE_DIVISOR,
                                              attacker, 0,
                                              worker, HIT_HEADING,
                                              INSTRUCTION_LIMIT);

        Assertions.assertNull(worker.getAutomaticTargetUnit());

        if (fleeable) {
            Assertions.assertEquals(MOVE_ORDER, worker.getPrimaryOrder());

            int fleeDistance = worker.getOrderTargetX() - CombatGameScenario.SECOND_UNIT_X;

            Assertions.assertTrue(fleeDistance == FIRST_FLEE_DISTANCE || fleeDistance == SECOND_FLEE_DISTANCE ||
                                  fleeDistance == THIRD_FLEE_DISTANCE || fleeDistance == FOURTH_FLEE_DISTANCE || fleeDistance == FIFTH_FLEE_DISTANCE,
                                  "flee distance=" + fleeDistance);
            Assertions.assertEquals(CombatGameScenario.SECOND_UNIT_Y, worker.getOrderTargetY());
        }

        game = createDuelGame(ScrUnitIds.PROTOSS_PROBE, ScrUnitIds.PROTOSS_ZEALOT);

        unitPool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(unitPool);

        attacker = unitPool.getUnit(CombatGameScenario.FIRST_UNIT_INDEX);

        Unit zealot = unitPool.getUnit(CombatGameScenario.SECOND_UNIT_INDEX);

        zealot.setShieldPoints(0);

        int order = zealot.getPrimaryOrder();

        game.getUnitDamage().dealWeaponDamage(ScrWeaponIds.PARTICLE_BEAM,
                                              PARTICLE_BEAM_DAMAGE,
                                              UNDIVIDED_DAMAGE_DIVISOR,
                                              attacker, 0,
                                              zealot, HIT_HEADING,
                                              INSTRUCTION_LIMIT);

        Assertions.assertSame(attacker, zealot.getAutomaticTargetUnit());
        Assertions.assertEquals(order, zealot.getPrimaryOrder());
        Assertions.assertEquals(gameData.getUnits().get("Hit Points", ScrUnitIds.PROTOSS_ZEALOT) - ZEALOT_ARMORED_DAMAGE,
                                zealot.getHitPoints());
    }

    @Test
    void compute_flee_position_should_use_the_first_distance_when_its_region_is_a_listed_neighbor() {
        Game game = createFleeGame(true);
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);
        Assertions.assertArrayEquals(new int[]{FIRST_FLEE_X, CombatGameScenario.SECOND_UNIT_Y},
                                     game.getUnitDamage().computeFleePosition(pool.getUnit(CombatGameScenario.SECOND_UNIT_INDEX),
                                                                              pool.getUnit(CombatGameScenario.FIRST_UNIT_INDEX)));
    }

    @Test
    void compute_flee_position_should_keep_a_neutral_ground_unit_in_its_ground_component() {
        Game game = createFleeGame(false);
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        Unit unit = pool.getUnit(CombatGameScenario.SECOND_UNIT_INDEX);

        unit.setOwnerIndex(PlayerTable.NEUTRAL_PLAYER_ID);

        Assertions.assertArrayEquals(new int[]{CombatGameScenario.SECOND_UNIT_X, CombatGameScenario.SECOND_UNIT_Y},
                                     game.getUnitDamage().computeFleePosition(unit, pool.getUnit(CombatGameScenario.FIRST_UNIT_INDEX)));
    }

    /**
     * Uses a fleeing unit of a human player, whose region records the route test reads like those of a computer player.
     */
    @Test
    void compute_flee_position_should_skip_a_distance_reached_only_through_a_targeted_region() {
        Game game = createRegionRowFleeGame();
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);
        Assertions.assertArrayEquals(new int[]{FIRST_FLEE_X, CombatGameScenario.SECOND_UNIT_Y},
                                     game.getUnitDamage().computeFleePosition(pool.getUnit(CombatGameScenario.SECOND_UNIT_INDEX),
                                                                              pool.getUnit(CombatGameScenario.FIRST_UNIT_INDEX)));

        game = createRegionRowFleeGame();
        pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        Unit unit = pool.getUnit(CombatGameScenario.SECOND_UNIT_INDEX);
        Unit attacker = pool.getUnit(CombatGameScenario.FIRST_UNIT_INDEX);
        int middleRegion = game.getComputerTerrain().getRegion(SECOND_FLEE_X, CombatGameScenario.SECOND_UNIT_Y);

        ComputerRegionScenario.setGroundTarget(game, unit.getOwnerIndex(), middleRegion, attacker);

        Assertions.assertArrayEquals(new int[]{SECOND_FLEE_X, CombatGameScenario.SECOND_UNIT_Y},
                                     game.getUnitDamage().computeFleePosition(unit, attacker));
    }

    @Test
    void compute_flee_position_should_move_the_guard_position_of_a_guard_record_only() {
        Game game = createFleeGame(true);
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        Unit unit = pool.getUnit(CombatGameScenario.SECOND_UNIT_INDEX);
        ComputerUnitRecord guard = new ComputerUnitRecord(ComputerUnitRecord.KIND_GUARD, 0);
        ComputerUnitRecord military = new ComputerUnitRecord(ComputerUnitRecord.KIND_MILITARY, 0);

        unit.setComputerRecord(guard);
        game.getUnitDamage().computeFleePosition(unit, pool.getUnit(CombatGameScenario.FIRST_UNIT_INDEX));

        Assertions.assertEquals(FIRST_FLEE_X, guard.guardX);
        Assertions.assertEquals(CombatGameScenario.SECOND_UNIT_Y, guard.guardY);

        unit.setComputerRecord(military);
        game.getUnitDamage().computeFleePosition(unit, pool.getUnit(CombatGameScenario.FIRST_UNIT_INDEX));

        Assertions.assertEquals(0, military.guardX);
        Assertions.assertEquals(0, military.guardY);
    }

    @Test
    void lurker_spines_damage_own_target_and_enemies_but_not_other_own_units() {
        Assertions.assertNotNull(gameData);

        // Spines damage their own target and skip the other units of the owner.
        Game game = CombatGameScenario.createGame(gameData);

        CombatGameScenario.initializeWorld(game, SPINES_UNIT_CAPACITY, SPINES_SPRITE_CAPACITY, SPINES_IMAGE_CAPACITY);
        CombatGameScenario.initializeCombat(game, gameData);
        CombatGameScenario.createUnit(game, gameData, LURKER_INDEX, ScrUnitIds.ZERG_LURKER, LURKER_POSITION_X, UNIT_POSITION_Y, 0);
        CombatGameScenario.createUnit(game, gameData, TARGET_INDEX, ScrUnitIds.ZERG_ZERGLING, TARGET_POSITION_X, UNIT_POSITION_Y, 0);
        CombatGameScenario.createUnit(game, gameData, BYSTANDER_INDEX, ScrUnitIds.ZERG_ZERGLING, TARGET_POSITION_X, BYSTANDER_POSITION_Y, 0);
        CombatGameScenario.createUnit(game, gameData, ENEMY_INDEX, ScrUnitIds.ZERG_ZERGLING, ENEMY_POSITION_X, UNIT_POSITION_Y, 1);

        GameFrame activeFrame = game.getFrame();

        Assertions.assertNotNull(activeFrame);

        activeFrame.getUnits().initializeLurkerHitHistory();

        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        Unit lurker = pool.getUnit(LURKER_INDEX);
        Unit ownTarget = pool.getUnit(TARGET_INDEX);
        Unit ownBystander = pool.getUnit(BYSTANDER_INDEX);
        Unit enemy = pool.getUnit(ENEMY_INDEX);
        int targetHitPoints = ownTarget.getHitPoints();
        int bystanderHitPoints = ownBystander.getHitPoints();
        int enemyHitPoints = enemy.getHitPoints();
        Sprite sprite = ownTarget.getSpriteOrNull();

        Assertions.assertNotNull(sprite);

        game.getUnitDamage().hitBullet(ScrWeaponIds.SUBTERRANEAN_SPINES,
                                       0, lurker,
                                       ownTarget, BULLET_HEADING,
                                       0,
                                       UNBOUNCED_DAMAGE_DIVISOR,
                                       sprite,
                                       sprite.getX(), sprite.getY(),
                                       INSTRUCTION_LIMIT);

        Assertions.assertTrue(ownTarget.getHitPoints() < targetHitPoints, "the own-player bullet target is damaged");
        Assertions.assertEquals(bystanderHitPoints, ownBystander.getHitPoints(), "other own units are skipped");
        Assertions.assertTrue(enemy.getHitPoints() < enemyHitPoints, "enemies in the inner radius are damaged");
        Assertions.assertEquals(targetHitPoints - ownTarget.getHitPoints(), enemyHitPoints - enemy.getHitPoints());
    }
}
