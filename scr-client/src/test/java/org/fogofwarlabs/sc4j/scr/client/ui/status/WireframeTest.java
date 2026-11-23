package org.fogofwarlabs.sc4j.scr.client.ui.status;

import org.fogofwarlabs.sc4j.scr.client.ClientReplayScenario;
import org.fogofwarlabs.sc4j.scr.client.ClientSession;
import org.fogofwarlabs.sc4j.scr.client.ui.command.InterfaceSelection;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitTraits;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.type.ArrayDatColumn;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.UnitTestUtils;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.ToIntFunction;

/**
 * Checks the wireframe frame selected for the status panel and the selection slots, and the palette entries of its cells.
 */
final class WireframeTest {
    private static final @NotNull String REPLAY = GameTestResources.REPLAY_TVT_BAEKDUDAEGAN;
    private static final int REPLAY_FRAME = 12000;
    private static final int PLAYER = 0;
    private static final int FIRST_POOL_INDEX = 1;
    private static final int SECOND_POOL_INDEX = 2;
    private static final int HIT_POINT_FRACTION_BITS = 8;
    private static final int MARINE_HIT_POINTS = 40;
    /**
     * 36 of 40 hit points, the eighth of the nine damage bands.
     */
    private static final int EIGHTH_BAND_HIT_POINTS = 36;
    /**
     * 45 of 40 hit points, the band after the nine damage bands.
     */
    private static final int OVER_MAXIMUM_HIT_POINTS = 45;
    /**
     * 400 of 40 hit points, a band far past the tint table.
     */
    private static final int FAR_OVER_MAXIMUM_HIT_POINTS = 400;
    private static final int LARVA_HIT_POINTS = 25;
    /**
     * Minus 5 of 25 hit points, the band before the lowest band of the fixed table.
     */
    private static final int NEGATIVE_HIT_POINTS = -5;
    private static final int ZEALOT_HIT_POINTS = 100;
    private static final int ZEALOT_SHIELDS = 60;
    /**
     * 70 of 60 shields, the band after the eight shield bands.
     */
    private static final int OVER_MAXIMUM_SHIELDS = 70;
    private static final int EVEN_RANDOMIZER = 0;
    private static final int ODD_RANDOMIZER = 1;
    private static final int RANDOM_DAMAGE_MAXIMUM_LEVEL = 9;
    private static final int HALF_DAMAGE_LEVEL = 5;
    private static final int DAMAGE_CELL_COUNT = 4;
    private static final int RED_ENTRY = 10;
    /**
     * The wireframe frames of a Lair and of a Hatchery: their unit types.
     */
    private static final int LAIR_FRAME = 132;
    private static final int HATCHERY_FRAME = 131;

    /**
     * The types whose status panel shows only the unit name; their wireframe frames hold the placeholder picture.
     */
    private static final int @NotNull [] NAME_ONLY_TYPES = {
            ScrUnitIds.TERRAN_GOLIATH_TURRET, ScrUnitIds.TERRAN_NUCLEAR_MISSILE, ScrUnitIds.HERO_ALAN_SCHEZAR_TURRET, ScrUnitIds.PROTOSS_SCARAB,
            ScrUnitIds.SPECIAL_MAP_REVEALER, ScrUnitIds.SPELL_DISRUPTION_WEB, ScrUnitIds.UNUSED_TERRAN1, ScrUnitIds.UNUSED_ZERG2, ScrUnitIds.UNUSED_PROTOSS1,
            ScrUnitIds.UNUSED_ZERG_MARKER, ScrUnitIds.UNUSED_TERRAN_MARKER, ScrUnitIds.UNUSED_PROTOSS_MARKER, ScrUnitIds.SPECIAL_FLOOR_HATCH,
            ScrUnitIds.SPECIAL_UPPER_LEVEL_DOOR, ScrUnitIds.SPECIAL_RIGHT_UPPER_LEVEL_DOOR, ScrUnitIds.SPECIAL_PIT_DOOR, ScrUnitIds.SPECIAL_RIGHT_PIT_DOOR
    };

    private static @NotNull ScrUnitTypeView createUnitTypes() {
        int[] hitPoints = new int[ScrUnitIds.NONE];
        int[] shieldEnabled = new int[ScrUnitIds.NONE];
        int[] shieldAmount = new int[ScrUnitIds.NONE];

        hitPoints[ScrUnitIds.TERRAN_MARINE]  = MARINE_HIT_POINTS << HIT_POINT_FRACTION_BITS;
        hitPoints[ScrUnitIds.ZERG_LARVA]     = LARVA_HIT_POINTS << HIT_POINT_FRACTION_BITS;
        hitPoints[ScrUnitIds.PROTOSS_ZEALOT] = ZEALOT_HIT_POINTS << HIT_POINT_FRACTION_BITS;

        shieldEnabled[ScrUnitIds.PROTOSS_ZEALOT] = 1;
        shieldAmount[ScrUnitIds.PROTOSS_ZEALOT]  = ZEALOT_SHIELDS;

        return new ScrUnitTypeView(column -> switch (column) {
            case HIT_POINTS -> new ArrayDatColumn(hitPoints);
            case SHIELD_ENABLED -> new ArrayDatColumn(shieldEnabled);
            case SHIELD_AMOUNT -> new ArrayDatColumn(shieldAmount);
            default -> new ArrayDatColumn(new int[ScrUnitIds.NONE]);
        },
                                   ScrUnitTraits.createColumns(),
                                   UnitsDat.REQUIREMENT_INDEX,
                                   (column, index) -> 0);
    }

    /**
     * @return the whole maximum hit points of a unit from the {@code units.dat} hit points of its type
     */
    private static @NotNull ToIntFunction<Unit> readMaximumHitPoints(@NotNull ScrUnitTypeView units) {
        return unit -> UnitQueries.getMaximumHitPoints(units.getHitPoints(unit.getUnitId()), unit);
    }

    private static @NotNull Unit createUnit(int poolIndex, int type) {
        Unit unit = UnitTestUtils.createUnit(poolIndex);

        unit.setUnitId(type);

        return unit;
    }

    private static int @NotNull [] getCellEntries(@NotNull Wireframe wireframe) {
        int[] entries = new int[Wireframe.CELL_COUNT];

        for (int cell = 0; cell < Wireframe.CELL_COUNT; cell++) {
            entries[cell] = wireframe.getCellEntry(cell);
        }

        return entries;
    }


    @Test
    void create_status_wireframe_should_select_types_whose_panel_shows_only_the_name() {
        ScrUnitTypeView units = createUnitTypes();

        for (int type : NAME_ONLY_TYPES) {
            InterfaceSelection selection = new InterfaceSelection(units);

            selection.set(PLAYER, createUnit(FIRST_POOL_INDEX, type));

            Wireframe wireframe = Wireframe.createStatusWireframe(selection, units, readMaximumHitPoints(units));

            Assertions.assertNotNull(wireframe, String.valueOf(type));
            Assertions.assertEquals(type, wireframe.getFrame(), String.valueOf(type));
        }
    }

    @Test
    void create_status_wireframe_should_return_null_without_a_single_selected_unit() {
        ScrUnitTypeView units = createUnitTypes();
        InterfaceSelection selection = new InterfaceSelection(units);

        Assertions.assertNull(Wireframe.createStatusWireframe(selection, units, readMaximumHitPoints(units)));

        selection.set(PLAYER, createUnit(FIRST_POOL_INDEX, ScrUnitIds.TERRAN_MARINE), createUnit(SECOND_POOL_INDEX, ScrUnitIds.TERRAN_MARINE));

        Assertions.assertNull(Wireframe.createStatusWireframe(selection, units, readMaximumHitPoints(units)));
    }

    @Test
    void status_wireframe_shows_the_queued_morph_and_slot_wireframe_shows_the_unit_type() {
        ScrUnitTypeView units = createUnitTypes();
        InterfaceSelection selection = new InterfaceSelection(units);
        Unit hatchery = createUnit(FIRST_POOL_INDEX, ScrUnitIds.ZERG_HATCHERY);

        hatchery.setBuildQueueIndex(0);
        hatchery.setFirstBuildQueueType(ScrUnitIds.ZERG_LAIR);
        selection.set(PLAYER, hatchery);

        Wireframe wireframe = Wireframe.createStatusWireframe(selection, units, readMaximumHitPoints(units));

        Assertions.assertNotNull(wireframe);
        Assertions.assertEquals(LAIR_FRAME, wireframe.getFrame());
        Assertions.assertEquals(HATCHERY_FRAME, Wireframe.createSlotWireframe(hatchery, units, readMaximumHitPoints(units)).getFrame());
    }

    @Test
    void create_slot_wireframe_should_tint_the_eighth_band_of_an_even_randomizer() {
        ScrUnitTypeView units = createUnitTypes();
        Unit marine = createUnit(FIRST_POOL_INDEX, ScrUnitIds.TERRAN_MARINE);

        marine.setHitPoints(EIGHTH_BAND_HIT_POINTS << HIT_POINT_FRACTION_BITS);
        marine.setWireframeRandomizer(EVEN_RANDOMIZER);

        Assertions.assertArrayEquals(new int[]{0, 1, 1, 1, -1, -1},
                                     getCellEntries(Wireframe.createSlotWireframe(marine, units, readMaximumHitPoints(units))));
    }

    @Test
    void create_slot_wireframe_should_read_the_following_rows_above_maximum_hit_points() {
        ScrUnitTypeView units = createUnitTypes();
        Unit marine = createUnit(FIRST_POOL_INDEX, ScrUnitIds.TERRAN_MARINE);

        marine.setHitPoints(OVER_MAXIMUM_HIT_POINTS << HIT_POINT_FRACTION_BITS);
        marine.setWireframeRandomizer(ODD_RANDOMIZER);

        Assertions.assertArrayEquals(new int[]{RED_ENTRY, RED_ENTRY, RED_ENTRY, RED_ENTRY, -1, -1},
                                     getCellEntries(Wireframe.createSlotWireframe(marine, units, readMaximumHitPoints(units))));
    }

    @Test
    void create_slot_wireframe_should_read_the_preceding_rows_below_zero_hit_points() {
        ScrUnitTypeView units = createUnitTypes();
        Unit larva = createUnit(FIRST_POOL_INDEX, ScrUnitIds.ZERG_LARVA);

        larva.setHitPoints(NEGATIVE_HIT_POINTS << HIT_POINT_FRACTION_BITS);

        Assertions.assertArrayEquals(new int[]{0x04, 0x05, 0x03, 0x04, -1, -1},
                                     getCellEntries(Wireframe.createSlotWireframe(larva, units, readMaximumHitPoints(units))));
    }

    @Test
    void create_slot_wireframe_should_read_the_fixed_rows_above_maximum_shields() {
        ScrUnitTypeView units = createUnitTypes();
        Unit zealot = createUnit(FIRST_POOL_INDEX, ScrUnitIds.PROTOSS_ZEALOT);

        zealot.setHitPoints(ZEALOT_HIT_POINTS << HIT_POINT_FRACTION_BITS);
        zealot.setShieldPoints(OVER_MAXIMUM_SHIELDS << HIT_POINT_FRACTION_BITS);

        int[] entries = getCellEntries(Wireframe.createSlotWireframe(zealot, units, readMaximumHitPoints(units)));

        Assertions.assertArrayEquals(new int[]{0x0e, 0x06}, new int[]{entries[DAMAGE_CELL_COUNT], entries[DAMAGE_CELL_COUNT + 1]});
    }

    @Test
    void create_slot_wireframe_should_use_entry_zero_outside_the_tint_table() {
        ScrUnitTypeView units = createUnitTypes();
        Unit marine = createUnit(FIRST_POOL_INDEX, ScrUnitIds.TERRAN_MARINE);

        marine.setHitPoints(FAR_OVER_MAXIMUM_HIT_POINTS << HIT_POINT_FRACTION_BITS);
        marine.setWireframeRandomizer(ODD_RANDOMIZER);

        Assertions.assertArrayEquals(new int[]{0, 0, 0, 0, -1, -1},
                                     getCellEntries(Wireframe.createSlotWireframe(marine, units, readMaximumHitPoints(units))));
    }

    @RequiresInstallation
    @Test
    void slot_wireframe_shows_a_red_part_at_half_health_or_less() throws IOException {
        try (ClientSession session = ClientReplayScenario.start(REPLAY, REPLAY_FRAME)) {
            Game game = session.getGame();
            GameData gameData = game.getGameData();

            Assertions.assertNotNull(gameData);

            DatTable units = gameData.getUnits();

            Assertions.assertNotNull(units);

            UnitPool pool = game.getUnitSystem().getUnitPool();

            Assertions.assertNotNull(pool);

            ScrUnitTypeView types = UnitsDat.createTypeTable(units);
            Unit damaged = null;

            for (int index = 1; index <= pool.getCapacity() && damaged == null; index++) {
                Unit unit = pool.isAllocated(index) ? pool.getUnit(index) : null;

                if (unit != null && unit.getUnitId() < ScrUnitIds.NONE && !Wireframe.usesFixedTable(unit.getUnitId()) &&
                    UnitQueries.getHitPoints(unit) * RANDOM_DAMAGE_MAXIMUM_LEVEL /
                    UnitQueries.getMaximumHitPoints(types.getHitPoints(unit.getUnitId()), unit) <= HALF_DAMAGE_LEVEL) {
                    damaged = unit;
                }
            }

            Assertions.assertNotNull(damaged, "The replay state has a damaged unit.");

            Wireframe wireframe = Wireframe.createSlotWireframe(damaged, types, readMaximumHitPoints(types));
            boolean anyRed = false;

            for (int cell = 0; cell < DAMAGE_CELL_COUNT; cell++) {
                anyRed |= wireframe.getCellEntry(cell) == RED_ENTRY;
            }

            Assertions.assertTrue(anyRed, "a unit at half health or less shows a red part");
        }
    }
}
