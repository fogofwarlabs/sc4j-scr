package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitTraits;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatSchema;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.type.ArrayDatColumn;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.test.UnitTestUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

/**
 * Checks the queue, hit point, life, rank and cloaking technology reads that the simulation and the interface share.
 * <p>
 * The tests of the queue indexes {@code 5} and {@code 6} pin the {@code 488}-byte unit layout, because that layout is the contract:
 * a queue index past the last queue slot reads the WORD that follows the queue, the energy at index {@code 5} and the queue
 * index with the slot's generation at index {@code 6}, as EUD maps observe it.
 */
final class UnitQueriesTest {
    private static final int POOL_INDEX = 1;
    private static final int POOL_CAPACITY = 1;
    private static final int POINT_FRACTION_BITS = 8;
    /**
     * The build queue index whose WORD is the energy, the first WORD after the five queue slots.
     */
    private static final int ENERGY_QUEUE_INDEX = 5;
    /**
     * The build queue index whose WORD is the queue index itself and the slot's generation.
     */
    private static final int GENERATION_QUEUE_INDEX = 6;
    /**
     * A build queue index whose WORD lies past the {@code 488}-byte unit layout.
     */
    private static final int OUTSIDE_QUEUE_INDEX = 200;
    private static final int GENERATION = 0x13;
    private static final int LAST_QUEUE_INDEX = 4;
    private static final int THIRD_QUEUE_SLOT_OFFSET = 3;
    private static final int ZERGLING_BUILD_TIME = 420;
    private static final int LAIR_BUILD_TIME = 1500;
    private static final int HATCHERY_BUILD_TIME = 1800;
    /**
     * The first requirement index, which the build time of the empty queue type reads.
     */
    private static final int FIRST_REQUIREMENT_INDEX = 77;
    private static final int MARINE_HIT_POINTS = 40;
    private static final int CURRENT_HIT_POINTS = 25;
    private static final int NEGATIVE_UNITS_DAT_HIT_POINTS = -2;
    /**
     * {@code 13} of {@code 40} hit points, {@code 32} percent; {@code 14} of {@code 40} hit points, {@code 35} percent.
     */
    private static final int HEAVILY_DAMAGED_HIT_POINTS = 13;
    private static final int DAMAGED_HIT_POINTS = 14;
    /**
     * Hit points below zero, whose negative percentage is above every percentage when compared unsigned.
     */
    private static final int NEGATIVE_HIT_POINTS = -100;
    private static final int ZEALOT_HIT_POINTS = 100;
    private static final int ZEALOT_SHIELDS = 60;
    private static final int ZEALOT_CURRENT_SHIELDS = 30;
    /**
     * The largest unit type WORD, far past the units.dat types.
     */
    private static final int LAST_TYPE_WORD = 0xFFFF;
    /**
     * The first unit type WORD past the units.dat types.
     */
    private static final int FIRST_UNSUPPORTED_TYPE_WORD = ScrUnitIds.NONE + 1;
    /**
     * The native byte offset of the rank increase, independent of its neighbors.
     */
    private static final int RANK_INCREASE_OFFSET = 0xD2;
    /**
     * The synthetic sublabel chosen to exercise unsigned byte wrapping.
     */
    private static final int MARINE_SUBLABEL = 250;
    private static final int BYTE_MASK = 0xFF;
    /**
     * The rank increases of the rank test: a small one and one that wraps the rank byte.
     */
    private static final int RANK_INCREASE = 3;
    private static final int WRAPPING_RANK_INCREASE = 10;

    /**
     * The expected unit types: a Marine, a Firebat, a Hatchery, a Lair, a Sunken Colony and the type identifier {@code None}.
     */
    private static final int MARINE_TYPE = 0;
    private static final int FIREBAT_TYPE = 32;
    private static final int HATCHERY_TYPE = 131;
    private static final int LAIR_TYPE = 132;
    private static final int SUNKEN_COLONY_TYPE = 146;
    private static final int NO_UNIT_TYPE = 228;
    /**
     * The expected cloaking technologies: Cloaking Field, Personnel Cloaking and the technology identifier {@code None}.
     */
    private static final int CLOAKING_FIELD_TECHNOLOGY = 9;
    private static final int PERSONNEL_CLOAKING_TECHNOLOGY = 10;
    private static final int NO_TECHNOLOGY = 44;
    /**
     * The unit types with the trait columns of the installed content and empty DAT columns.
     */
    private static final @NotNull ScrUnitTypeView TRAIT_TYPES = new ScrUnitTypeView(column -> new ArrayDatColumn(new int[ScrUnitIds.NONE]),
                                                                                    ScrUnitTraits.createColumns(),
                                                                                    UnitsDat.REQUIREMENT_INDEX,
                                                                                    (column, index) -> 0);

    private static @NotNull Unit createUnit(int type, int queuedType, boolean completed) {
        Unit unit = UnitTestUtils.createUnit(POOL_INDEX);

        unit.setUnitId(type);
        unit.setStatus(completed ? UnitStatusFlags.COMPLETED : 0);
        unit.setBuildQueueIndex(0);
        unit.setFirstBuildQueueType(queuedType);

        return unit;
    }

    private static @NotNull ScrUnitTypeView createUnitTypes() {
        int[] hitPoints = new int[ScrUnitIds.NONE];
        int[] shieldEnabled = new int[ScrUnitIds.NONE];
        int[] shieldAmount = new int[ScrUnitIds.NONE];
        int[] buildTime = new int[ScrUnitIds.NONE];
        int[] requirementIndex = new int[ScrUnitIds.NONE];

        hitPoints[ScrUnitIds.TERRAN_MARINE]  = MARINE_HIT_POINTS << POINT_FRACTION_BITS;
        hitPoints[ScrUnitIds.TERRAN_FIREBAT] = NEGATIVE_UNITS_DAT_HIT_POINTS << POINT_FRACTION_BITS;
        hitPoints[ScrUnitIds.PROTOSS_ZEALOT] = ZEALOT_HIT_POINTS << POINT_FRACTION_BITS;

        shieldEnabled[ScrUnitIds.PROTOSS_ZEALOT] = 1;
        shieldAmount[ScrUnitIds.PROTOSS_ZEALOT]  = ZEALOT_SHIELDS;

        buildTime[ScrUnitIds.ZERG_ZERGLING] = ZERGLING_BUILD_TIME;
        buildTime[ScrUnitIds.ZERG_LAIR]     = LAIR_BUILD_TIME;
        buildTime[ScrUnitIds.ZERG_HATCHERY] = HATCHERY_BUILD_TIME;

        requirementIndex[0] = FIRST_REQUIREMENT_INDEX;

        return new ScrUnitTypeView(column -> switch (column) {
            case HIT_POINTS -> new ArrayDatColumn(hitPoints);
            case SHIELD_ENABLED -> new ArrayDatColumn(shieldEnabled);
            case SHIELD_AMOUNT -> new ArrayDatColumn(shieldAmount);
            case BUILD_TIME -> new ArrayDatColumn(buildTime);
            case REQUIREMENT_INDEX -> new ArrayDatColumn(requirementIndex);
            default -> new ArrayDatColumn(new int[ScrUnitIds.NONE]);
        },
                                   ScrUnitTraits.createColumns(),
                                   UnitsDat.REQUIREMENT_INDEX,
                                   (column, index) -> 0);
    }


    @Test
    void get_displayed_type_should_return_the_queued_morph_of_an_incomplete_building() {
        Assertions.assertEquals(LAIR_TYPE,
                                UnitQueries.getDisplayedType(TRAIT_TYPES, createUnit(ScrUnitIds.ZERG_HATCHERY, ScrUnitIds.ZERG_LAIR, false)));
        Assertions.assertEquals(SUNKEN_COLONY_TYPE,
                                UnitQueries.getDisplayedType(TRAIT_TYPES,
                                                             createUnit(ScrUnitIds.ZERG_CREEP_COLONY, ScrUnitIds.ZERG_SUNKEN_COLONY, false)));
    }

    @Test
    void get_displayed_type_should_return_the_unit_type_otherwise() {
        Assertions.assertEquals(HATCHERY_TYPE,
                                UnitQueries.getDisplayedType(TRAIT_TYPES, createUnit(ScrUnitIds.ZERG_HATCHERY, ScrUnitIds.ZERG_LAIR, true)));
        Assertions.assertEquals(HATCHERY_TYPE,
                                UnitQueries.getDisplayedType(TRAIT_TYPES, createUnit(ScrUnitIds.ZERG_HATCHERY, ScrUnitIds.ZERG_HATCHERY, false)));
        Assertions.assertEquals(HATCHERY_TYPE,
                                UnitQueries.getDisplayedType(TRAIT_TYPES, createUnit(ScrUnitIds.ZERG_HATCHERY, ScrUnitIds.NONE, false)));
    }

    @Test
    void get_displayed_type_should_read_the_energy_as_the_queue_entry_after_the_last_slot() {
        Unit hatchery = createUnit(ScrUnitIds.ZERG_HATCHERY, ScrUnitIds.NONE, false);

        hatchery.setBuildQueueIndex(ENERGY_QUEUE_INDEX);
        hatchery.setEnergy(ScrUnitIds.ZERG_LAIR);

        Assertions.assertEquals(LAIR_TYPE, UnitQueries.getDisplayedType(TRAIT_TYPES, hatchery));
    }

    @Test
    void get_queued_type_should_read_the_queue_index_with_the_slot_generation_as_the_seventh_entry() {
        UnitPool pool = new UnitPool(POOL_CAPACITY);
        Unit egg = pool.getUnit(POOL_INDEX);

        pool.setGeneration(POOL_INDEX, GENERATION);
        egg.setUnitId(ScrUnitIds.ZERG_EGG);
        egg.setBuildQueueIndex(GENERATION_QUEUE_INDEX);

        Assertions.assertEquals(GENERATION_QUEUE_INDEX | GENERATION << Byte.SIZE, UnitQueries.getQueuedType(egg));
    }

    @Test
    void get_queued_type_should_return_an_empty_slot_past_the_unit_layout() {
        Unit hatchery = createUnit(ScrUnitIds.ZERG_HATCHERY, ScrUnitIds.ZERG_LAIR, false);

        hatchery.setBuildQueueIndex(OUTSIDE_QUEUE_INDEX);

        Assertions.assertEquals(NO_UNIT_TYPE, UnitQueries.getQueuedType(hatchery));
    }

    @Test
    void get_queue_slot_type_should_wrap_around_the_five_queue_slots() {
        Unit barracks = createUnit(ScrUnitIds.TERRAN_BARRACKS, ScrUnitIds.NONE, true);

        barracks.setBuildQueueIndex(LAST_QUEUE_INDEX);
        barracks.setFifthBuildQueueType(ScrUnitIds.TERRAN_MARINE);
        barracks.setThirdBuildQueueType(ScrUnitIds.TERRAN_FIREBAT);

        Assertions.assertEquals(MARINE_TYPE, UnitQueries.getQueueSlotType(barracks, 0));
        Assertions.assertEquals(FIREBAT_TYPE, UnitQueries.getQueueSlotType(barracks, THIRD_QUEUE_SLOT_OFFSET));
    }

    @Test
    void get_build_time_should_read_the_queued_type_of_an_egg_and_of_a_building_morph() {
        ScrUnitTypeView units = createUnitTypes();

        Assertions.assertEquals(ZERGLING_BUILD_TIME, UnitQueries.getBuildTime(units, createUnit(ScrUnitIds.ZERG_EGG, ScrUnitIds.ZERG_ZERGLING, true)));
        Assertions.assertEquals(LAIR_BUILD_TIME, UnitQueries.getBuildTime(units, createUnit(ScrUnitIds.ZERG_HATCHERY, ScrUnitIds.ZERG_LAIR, false)));
        Assertions.assertEquals(HATCHERY_BUILD_TIME, UnitQueries.getBuildTime(units, createUnit(ScrUnitIds.ZERG_HATCHERY, ScrUnitIds.ZERG_LAIR, true)));
    }

    @Test
    void get_build_time_should_read_the_requirement_index_column_for_an_empty_queue_type() {
        ScrUnitTypeView units = createUnitTypes();

        Assertions.assertEquals(FIRST_REQUIREMENT_INDEX, UnitQueries.getBuildTime(units, createUnit(ScrUnitIds.ZERG_EGG, ScrUnitIds.NONE, true)));
    }

    @Test
    void get_maximum_hit_points_should_fall_back_to_the_current_hit_points_then_one() {
        ScrUnitTypeView units = createUnitTypes();
        Unit marine = createUnit(ScrUnitIds.TERRAN_MARINE, ScrUnitIds.NONE, true);
        Unit ghost = createUnit(ScrUnitIds.TERRAN_GHOST, ScrUnitIds.NONE, true);

        marine.setHitPoints(CURRENT_HIT_POINTS << POINT_FRACTION_BITS);
        ghost.setHitPoints(CURRENT_HIT_POINTS << POINT_FRACTION_BITS);

        Assertions.assertEquals(MARINE_HIT_POINTS, UnitQueries.getMaximumHitPoints(units.getHitPoints(marine.getUnitId()), marine));
        Assertions.assertEquals(CURRENT_HIT_POINTS, UnitQueries.getMaximumHitPoints(units.getHitPoints(ghost.getUnitId()), ghost));

        ghost.setHitPoints(0);

        Assertions.assertEquals(1, UnitQueries.getMaximumHitPoints(units.getHitPoints(ghost.getUnitId()), ghost));
    }

    @Test
    void get_maximum_hit_points_should_keep_a_negative_units_dat_value() {
        Unit firebat = createUnit(ScrUnitIds.TERRAN_FIREBAT, ScrUnitIds.NONE, true);

        firebat.setHitPoints(CURRENT_HIT_POINTS << POINT_FRACTION_BITS);

        int maximumHitPoints = createUnitTypes().getHitPoints(firebat.getUnitId());

        Assertions.assertEquals(NEGATIVE_UNITS_DAT_HIT_POINTS, UnitQueries.getMaximumHitPoints(maximumHitPoints, firebat));
    }

    @Test
    void get_hit_points_should_round_up_to_whole_points() {
        Unit marine = createUnit(ScrUnitIds.TERRAN_MARINE, ScrUnitIds.NONE, true);

        marine.setHitPoints((CURRENT_HIT_POINTS << POINT_FRACTION_BITS) + 1);

        Assertions.assertEquals(CURRENT_HIT_POINTS + 1, UnitQueries.getHitPoints(marine));

        marine.setHitPoints(NEGATIVE_HIT_POINTS << POINT_FRACTION_BITS);

        Assertions.assertEquals(NEGATIVE_HIT_POINTS, UnitQueries.getHitPoints(marine));
    }

    @Test
    void is_heavily_damaged_should_compare_the_hit_point_percentage_unsigned() {
        ScrUnitTypeView units = createUnitTypes();
        Unit marine = createUnit(ScrUnitIds.TERRAN_MARINE, ScrUnitIds.NONE, true);

        marine.setHitPoints(HEAVILY_DAMAGED_HIT_POINTS << POINT_FRACTION_BITS);

        Assertions.assertTrue(UnitQueries.isHeavilyDamaged(units.getHitPoints(marine.getUnitId()), marine));

        marine.setHitPoints(DAMAGED_HIT_POINTS << POINT_FRACTION_BITS);

        Assertions.assertFalse(UnitQueries.isHeavilyDamaged(units.getHitPoints(marine.getUnitId()), marine));

        marine.setHitPoints(NEGATIVE_HIT_POINTS << POINT_FRACTION_BITS);

        Assertions.assertTrue(UnitQueries.getHitPointPercent(units.getHitPoints(marine.getUnitId()), marine) < 0);
        Assertions.assertFalse(UnitQueries.isHeavilyDamaged(units.getHitPoints(marine.getUnitId()), marine));
    }

    @Test
    void get_life_should_add_the_whole_shields_of_a_shielded_type() {
        ScrUnitTypeView units = createUnitTypes();
        Unit zealot = createUnit(ScrUnitIds.PROTOSS_ZEALOT, ScrUnitIds.NONE, true);
        Unit marine = createUnit(ScrUnitIds.TERRAN_MARINE, ScrUnitIds.NONE, true);

        zealot.setHitPoints(CURRENT_HIT_POINTS << POINT_FRACTION_BITS);
        zealot.setShieldPoints(ZEALOT_CURRENT_SHIELDS << POINT_FRACTION_BITS);
        marine.setHitPoints(CURRENT_HIT_POINTS << POINT_FRACTION_BITS);
        marine.setShieldPoints(ZEALOT_CURRENT_SHIELDS << POINT_FRACTION_BITS);

        Assertions.assertEquals(CURRENT_HIT_POINTS + ZEALOT_CURRENT_SHIELDS, UnitQueries.getLife(units, zealot));
        Assertions.assertEquals(ZEALOT_HIT_POINTS + ZEALOT_SHIELDS,
                                UnitQueries.getMaximumLife(units, units.getHitPoints(zealot.getUnitId()), zealot));
        Assertions.assertEquals(CURRENT_HIT_POINTS, UnitQueries.getLife(units, marine));
        Assertions.assertEquals(MARINE_HIT_POINTS, UnitQueries.getMaximumLife(units, units.getHitPoints(marine.getUnitId()), marine));
    }

    @Test
    void get_rank_should_add_the_rank_increase_to_the_sublabel_as_a_byte() throws IOException {
        DatTable empty = new DatSchema().fieldU8("Sublabel", UnitsDat.UNIT_COUNT).parse(new byte[UnitsDat.UNIT_COUNT]);
        int[] sublabels = new int[UnitsDat.UNIT_COUNT];

        sublabels[ScrUnitIds.TERRAN_MARINE] = MARINE_SUBLABEL;

        DatTable units = empty.createWithColumns(Map.of("Sublabel", sublabels));
        Unit unit = UnitTestUtils.createUnit(1);

        unit.setUnitId(ScrUnitIds.TERRAN_MARINE);

        Assertions.assertEquals(MARINE_SUBLABEL, UnitQueries.getRank(UnitsDat.createTypeTable(units), unit));

        NativeUnitFields.writeByte(unit, RANK_INCREASE_OFFSET, RANK_INCREASE);

        Assertions.assertEquals(MARINE_SUBLABEL + RANK_INCREASE, UnitQueries.getRank(UnitsDat.createTypeTable(units), unit));

        NativeUnitFields.writeByte(unit, RANK_INCREASE_OFFSET, WRAPPING_RANK_INCREASE);

        Assertions.assertEquals((MARINE_SUBLABEL + WRAPPING_RANK_INCREASE) & BYTE_MASK, UnitQueries.getRank(UnitsDat.createTypeTable(units), unit),
                                "The rank increase is added as a byte.");
    }

    @Test
    void is_building_should_read_the_building_flag_of_units_dat_types_only() {
        int[] flags = new int[ScrUnitIds.NONE];

        flags[ScrUnitIds.TERRAN_SUPPLY_DEPOT] = UnitTypeFlags.BUILDING;

        ScrUnitTypeView units = new ScrUnitTypeView(column -> new ArrayDatColumn(column == ScrUnitTypeView.Column.FLAGS ? flags : new int[ScrUnitIds.NONE]),
                                                    ScrUnitTraits.createColumns(),
                                                    UnitsDat.REQUIREMENT_INDEX,
                                                    (column, index) -> UnitTypeFlags.BUILDING);

        Assertions.assertTrue(UnitQueries.isBuilding(units, ScrUnitIds.TERRAN_SUPPLY_DEPOT));
        Assertions.assertFalse(UnitQueries.isBuilding(units, ScrUnitIds.TERRAN_MARINE));
        Assertions.assertFalse(UnitQueries.isBuilding(units, ScrUnitIds.NONE));
        Assertions.assertFalse(UnitQueries.isBuilding(units, LAST_TYPE_WORD));
    }

    @Test
    void get_cloak_technology_should_return_personnel_cloaking_for_ghosts_and_infested_kerrigan() {
        int[] types = {
                ScrUnitIds.TERRAN_GHOST,
                ScrUnitIds.HERO_SARAH_KERRIGAN,
                ScrUnitIds.HERO_SAMIR_DURAN,
                ScrUnitIds.HERO_ALEXEI_STUKOV,
                ScrUnitIds.HERO_INFESTED_DURAN,
                ScrUnitIds.HERO_INFESTED_KERRIGAN
        };
        Unit unit = UnitTestUtils.createUnit(POOL_INDEX);

        for (int type : types) {
            unit.setUnitId(type);

            Assertions.assertEquals(PERSONNEL_CLOAKING_TECHNOLOGY, UnitQueries.getCloakTechnology(TRAIT_TYPES, unit), String.valueOf(type));
        }
    }

    @Test
    void get_cloak_technology_should_return_cloaking_field_for_wraiths() {
        int[] types = {ScrUnitIds.TERRAN_WRAITH, ScrUnitIds.HERO_TOM_KAZANSKY};
        Unit unit = UnitTestUtils.createUnit(POOL_INDEX);

        for (int type : types) {
            unit.setUnitId(type);

            Assertions.assertEquals(CLOAKING_FIELD_TECHNOLOGY, UnitQueries.getCloakTechnology(TRAIT_TYPES, unit), String.valueOf(type));
        }
    }

    @Test
    void get_cloak_technology_should_return_none_for_other_unit_types() {
        int[] types = {ScrUnitIds.TERRAN_MARINE, ScrUnitIds.ZERG_HYDRALISK, ScrUnitIds.PROTOSS_OBSERVER, ScrUnitIds.NONE};
        Unit unit = UnitTestUtils.createUnit(POOL_INDEX);

        for (int type : types) {
            unit.setUnitId(type);

            Assertions.assertEquals(NO_TECHNOLOGY, UnitQueries.getCloakTechnology(TRAIT_TYPES, unit), String.valueOf(type));
        }
    }

    @Test
    void get_cloak_technology_should_return_none_for_the_first_unsupported_unit_word() {
        Unit unit = UnitTestUtils.createUnit(POOL_INDEX);

        unit.setUnitId(FIRST_UNSUPPORTED_TYPE_WORD);

        Assertions.assertEquals(NO_TECHNOLOGY, UnitQueries.getCloakTechnology(TRAIT_TYPES, unit));
    }

    @Test
    void get_cloak_technology_should_return_none_for_the_maximum_unit_word() {
        Unit unit = UnitTestUtils.createUnit(POOL_INDEX);

        unit.setUnitId(LAST_TYPE_WORD);

        Assertions.assertEquals(NO_TECHNOLOGY, UnitQueries.getCloakTechnology(TRAIT_TYPES, unit));
    }
}
