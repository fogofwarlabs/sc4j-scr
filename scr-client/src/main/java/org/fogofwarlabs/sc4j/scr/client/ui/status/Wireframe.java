package org.fogofwarlabs.sc4j.scr.client.ui.status;

import org.fogofwarlabs.sc4j.scr.client.ui.command.InterfaceSelection;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.ToIntFunction;

/**
 * Selects the wireframe of a unit: the frame of the wireframe sheets and the {@code twire.pcx} entry of each of its six cells.
 * <p>
 * The status panel shows the wireframe of the active unit of every single selection, whatever view the panel builds for the unit, at the frame of its
 * displayed type ({@link UnitQueries#getDisplayedType}). A multi-selection or cargo slot shows its unit at the frame of the unit type. A frame
 * without a unit picture holds a placeholder picture, which the cells tint like any other frame.
 * <p>
 * Cells {@code 0} to {@code 3} are the damage parts. The frames that use the fixed table take its row of the hit-point band; the other frames take
 * the row of the randomizer table, odd or even by the unit's wireframe randomizer, with the parts permuted by the randomizer bits. Cells {@code 4}
 * and {@code 5} are the shield outline by the shield band of the displayed type; a unit without shields has a negative entry there.
 * <p>
 * The four tables are one block, and a band has no bound: hit points or shields above the maximum read the rows that follow the table, and negative
 * ones the rows before it. A read outside the block takes entry {@code 0}.
 */
public final class Wireframe {
    /**
     * The color of cells {@code 4} and {@code 5} of a unit without shields in SD, opaque black. HD does not draw those cells without shields.
     */
    public static final int NO_SHIELD_COLOR = 0x000000FF;
    /**
     * The number of cells of a wireframe frame.
     */
    public static final int CELL_COUNT = 6;

    private static final int ZERG_LARVA_TYPE = 35;
    private static final int HERO_YGGDRASILL_TYPE = 57;
    private static final int ZERG_COCOON_TYPE = 59;
    private static final int ZERG_DEVOURER_TYPE = 62;
    private static final int PROTOSS_DARK_ARCHON_TYPE = 63;
    private static final int PROTOSS_ARCHON_TYPE = 68;
    private static final int HERO_TASSADAR_ZERATUL_ARCHON_TYPE = 76;
    private static final int CRITTER_RHYNADON_TYPE = 89;
    private static final int ZERG_LURKER_EGG_TYPE = 97;
    private static final int ZERG_LURKER_TYPE = 103;
    private static final int ZERG_INFESTED_COMMAND_CENTER_TYPE = 130;
    private static final int SPECIAL_CEREBRATE_DAGGOTH_TYPE = 152;
    private static final int SPECIAL_KHAYDARIN_CRYSTAL_FORM_TYPE = 173;
    private static final int RESOURCE_MINERAL_FIELD_TYPE = 176;
    private static final int RESOURCE_MINERAL_FIELD_TYPE_3_TYPE = 178;
    private static final int UNUSED_KHAYDARIN_CRYSTAL_FORMATION_TYPE = 187;
    private static final int RESOURCE_VESPENE_GEYSER_TYPE = 188;
    private static final int SPECIAL_ZERG_BEACON_TYPE = 194;
    private static final int SPECIAL_ZERG_FLAG_BEACON_TYPE = 197;
    private static final int SPECIAL_OVERMIND_COCOON_TYPE = 201;
    private static final int POWERUP_YOUNG_CHRYSALIS_TYPE = 216;
    private static final int POWERUP_MINERAL_CLUSTER_TYPE_1_TYPE = 220;
    private static final int POWERUP_TERRAN_GAS_TANK_TYPE_1_TYPE = 226;
    private static final int POWERUP_TERRAN_GAS_TANK_TYPE_2_TYPE = 227;

    /**
     * The tint block: ten rows of the odd randomizer table, ten rows of the even randomizer table, eight shield rows and six rows of the fixed table,
     * each table from its lowest band. Every element is a {@code twire.pcx} entry.
     */
    private static final int @NotNull [] TINT_TABLE = {
            0x0a, 0x0a, 0x0a, 0x0a, 0x0a, 0x0a, 0x0a, 0x00, 0x0a, 0x0a, 0x00, 0x00, 0x0a, 0x0a, 0x00, 0x01, 0x0a, 0x0a, 0x01, 0x01,
            0x0a, 0x00, 0x01, 0x01, 0x0a, 0x01, 0x01, 0x01, 0x00, 0x01, 0x01, 0x01, 0x00, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
            0x0a, 0x0a, 0x0a, 0x0a, 0x0a, 0x0a, 0x0a, 0x00, 0x0a, 0x0a, 0x00, 0x00, 0x0a, 0x00, 0x00, 0x00, 0x0a, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x01, 0x01, 0x00, 0x01, 0x01, 0x01, 0x00, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
            0x02, 0x02, 0x09, 0x02, 0x08, 0x09, 0x07, 0x08, 0x06, 0x07, 0x05, 0x06, 0x04, 0x05, 0x03, 0x04,
            0x0e, 0x06, 0x07, 0x08, 0x13, 0x0b, 0x0d, 0x06, 0x13, 0x0b, 0x0a, 0x10,
            0x01, 0x12, 0x14, 0x10, 0x01, 0x12, 0x14, 0x0c, 0x00, 0x01, 0x12, 0x0a
    };
    /**
     * The index of the first row of the odd randomizer table in {@link #TINT_TABLE}.
     */
    private static final int ODD_RANDOM_ROWS = 0;
    /**
     * The index of the first row of the even randomizer table in {@link #TINT_TABLE}.
     */
    private static final int EVEN_RANDOM_ROWS = 40;
    /**
     * The index of the first shield row in {@link #TINT_TABLE}.
     */
    private static final int SHIELD_ROWS = 80;
    /**
     * The index of the first row of the fixed table in {@link #TINT_TABLE}.
     */
    private static final int FIXED_ROWS = 96;
    /**
     * The entry of a read outside {@link #TINT_TABLE}.
     */
    private static final int OUTSIDE_TABLE_ENTRY = 0;
    /**
     * The row of full hit points in the randomizer tables: the row is the hit points times this over the maximum hit points.
     */
    private static final int RANDOM_FULL_ROW = 9;
    /**
     * The row of full hit points in the fixed table: the row is the hit points times this over the maximum hit points.
     */
    private static final int FIXED_FULL_ROW = 5;
    /**
     * The row of full shields: the row is the shields times this over the maximum shields.
     */
    private static final int SHIELD_FULL_ROW = 7;
    private static final int NO_SHIELD_ENTRY = -1;
    private static final int DAMAGE_CELL_COUNT = 4;
    private static final int SHIELD_CELL_COUNT = 2;
    private static final int HIT_POINT_FRACTION_BITS = 8;


    /**
     * Creates a {@link Wireframe} instance for the status panel: the active unit of a single selection at the frame of its displayed type.
     *
     * @param selection        the interface selection
     * @param units            the {@code units.dat} table
     * @param maximumHitPoints the whole maximum hit points of a unit ({@link UnitQueries#getMaximumHitPoints(Unit)})
     * @return the created {@link Wireframe} instance, or {@code null} if the selection does not hold exactly one unit
     */
    public static @Nullable Wireframe createStatusWireframe(@NotNull InterfaceSelection selection, @NotNull ScrUnitTypeView units,
                                                            @NotNull ToIntFunction<Unit> maximumHitPoints) {
        Unit active = selection.getActiveUnit();

        if (active == null || selection.getCount() != 1) {
            return null;
        }

        int frame = UnitQueries.getDisplayedType(units, active);

        return new Wireframe(frame, computeCellEntries(active, units, maximumHitPoints.applyAsInt(active), frame));
    }

    /**
     * Creates a {@link Wireframe} instance for a multi-selection or cargo slot: the unit at the frame of its unit type.
     *
     * @param unit             the unit in the slot
     * @param units            the {@code units.dat} table
     * @param maximumHitPoints the whole maximum hit points of a unit ({@link UnitQueries#getMaximumHitPoints(Unit)})
     * @return the created {@link Wireframe} instance
     */
    public static @NotNull Wireframe createSlotWireframe(@NotNull Unit unit, @NotNull ScrUnitTypeView units,
                                                         @NotNull ToIntFunction<Unit> maximumHitPoints) {
        return new Wireframe(unit.getUnitId(), computeCellEntries(unit, units, maximumHitPoints.applyAsInt(unit), unit.getUnitId()));
    }

    /**
     * Tests membership in the frame ranges whose damage parts use the fixed table.
     *
     * @param frame the wireframe frame
     * @return {@code true} if the frame uses the fixed table, {@code false} otherwise
     */
    static boolean usesFixedTable(int frame) {
        return frame >= ZERG_LARVA_TYPE && frame <= HERO_YGGDRASILL_TYPE || frame == ZERG_COCOON_TYPE || frame == ZERG_DEVOURER_TYPE ||
               frame == PROTOSS_DARK_ARCHON_TYPE || frame == PROTOSS_ARCHON_TYPE || frame == HERO_TASSADAR_ZERATUL_ARCHON_TYPE ||
               frame >= CRITTER_RHYNADON_TYPE && frame <= ZERG_LURKER_EGG_TYPE ||
               frame == ZERG_LURKER_TYPE || frame >= ZERG_INFESTED_COMMAND_CENTER_TYPE && frame <= SPECIAL_CEREBRATE_DAGGOTH_TYPE ||
               frame == SPECIAL_KHAYDARIN_CRYSTAL_FORM_TYPE ||
               frame >= RESOURCE_MINERAL_FIELD_TYPE && frame <= RESOURCE_MINERAL_FIELD_TYPE_3_TYPE ||
               frame == UNUSED_KHAYDARIN_CRYSTAL_FORMATION_TYPE ||
               frame == RESOURCE_VESPENE_GEYSER_TYPE ||
               frame == SPECIAL_ZERG_BEACON_TYPE || frame == SPECIAL_ZERG_FLAG_BEACON_TYPE || frame == SPECIAL_OVERMIND_COCOON_TYPE ||
               frame == POWERUP_YOUNG_CHRYSALIS_TYPE || frame == POWERUP_MINERAL_CLUSTER_TYPE_1_TYPE ||
               frame == POWERUP_TERRAN_GAS_TANK_TYPE_1_TYPE || frame == POWERUP_TERRAN_GAS_TANK_TYPE_2_TYPE;
    }

    /**
     * Computes the {@code twire.pcx} entry of each cell of a unit drawn at a frame.
     *
     * @param unit             the unit
     * @param units            the {@code units.dat} table
     * @param maximumHitPoints the unit's whole maximum hit points
     * @param frame            the wireframe frame
     * @return six entries, negative for the shield cells of a unit without shields
     */
    private static int @NotNull [] computeCellEntries(@NotNull Unit unit, @NotNull ScrUnitTypeView units, int maximumHitPoints, int frame) {
        int type = unit.getUnitId();
        int randomizer = unit.getWireframeRandomizer();
        int hitPoints = UnitQueries.getHitPoints(unit);
        int[] entries = new int[CELL_COUNT];
        int[] permutation = {0, 1, 2, 3};

        for (int bit = 0; bit < Byte.SIZE; bit++) {
            if ((randomizer >> bit & 1) == 0) {
                continue;
            }

            int cell = bit < DAMAGE_CELL_COUNT ? bit : 0;
            int swap = permutation[cell];

            permutation[cell]                           = permutation[(cell + 1) % DAMAGE_CELL_COUNT];
            permutation[(cell + 1) % DAMAGE_CELL_COUNT] = swap;
        }

        int randomRows = (randomizer & 1) != 0 ? ODD_RANDOM_ROWS : EVEN_RANDOM_ROWS;
        int randomRow = hitPoints * RANDOM_FULL_ROW / maximumHitPoints;
        int fixedRow = hitPoints * FIXED_FULL_ROW / maximumHitPoints;
        boolean gasTank = frame == POWERUP_TERRAN_GAS_TANK_TYPE_1_TYPE || frame == POWERUP_TERRAN_GAS_TANK_TYPE_2_TYPE;

        for (int cell = 0; cell < DAMAGE_CELL_COUNT; cell++) {
            boolean fixed = usesFixedTable(frame) && !(gasTank && cell == 0);

            entries[cell] = fixed
                            ? readTintEntry(FIXED_ROWS + cell + (long) fixedRow * DAMAGE_CELL_COUNT)
                            : readTintEntry(randomRows + permutation[cell] + (long) randomRow * DAMAGE_CELL_COUNT);
        }

        int maximumShields = units.getShieldAmount(UnitQueries.getDisplayedType(units, unit));

        if (units.getShieldEnabled(type) != 0 && maximumShields != 0) {
            int shieldRow = (unit.getShieldPoints() >> HIT_POINT_FRACTION_BITS) * SHIELD_FULL_ROW / maximumShields;

            for (int cell = 0; cell < SHIELD_CELL_COUNT; cell++) {
                entries[DAMAGE_CELL_COUNT + cell] = readTintEntry(SHIELD_ROWS + cell + (long) shieldRow * SHIELD_CELL_COUNT);
            }
        } else {
            entries[DAMAGE_CELL_COUNT]     = NO_SHIELD_ENTRY;
            entries[DAMAGE_CELL_COUNT + 1] = NO_SHIELD_ENTRY;
        }

        return entries;
    }

    /**
     * Reads an element of the tint block at an index that may lie outside it.
     *
     * @param index the element index
     * @return the {@code twire.pcx} entry, {@link #OUTSIDE_TABLE_ENTRY} outside the block
     */
    private static int readTintEntry(long index) {
        return index >= 0 && index < TINT_TABLE.length ? TINT_TABLE[(int) index] : OUTSIDE_TABLE_ENTRY;
    }


    private final int frame;
    private final int @NotNull [] cellEntries;

    private Wireframe(int frame, int @NotNull [] cellEntries) {
        this.frame       = frame;
        this.cellEntries = cellEntries;
    }

    /**
     * @return the frame of the wireframe sheets: the displayed type for the status panel, the unit type for a slot
     */
    public int getFrame() {
        return this.frame;
    }

    /**
     * Returns the {@code twire.pcx} entry of a cell.
     *
     * @param cell the cell, from {@code 0} to {@link #CELL_COUNT} minus one
     * @return the {@code twire.pcx} entry, or a negative value for a shield cell of a unit without shields
     */
    public int getCellEntry(int cell) {
        return this.cellEntries[cell];
    }
}
