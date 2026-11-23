package org.fogofwarlabs.sc4j.scr.game.recording;

import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts the simulation state in the recording field layout.
 * <p>
 * The {@code *_FIELD} constants are the positions of the values in the arrays of {@link #getPlayerValues} and {@link #getUnitValues}.
 */
public final class RecorderStateUtils {
    /**
     * The unit union holds resources, town hall bounds and larva side values.
     */
    public static final int UNION_WORDS = 0;
    /**
     * The unit union holds a gather target, nydus exit or silo nuke.
     */
    public static final int UNION_POINTER = 1;
    /**
     * The unit union holds a psi field sprite or ghost nuke dot outside the unit pool.
     */
    public static final int UNION_SPRITE = 2;

    /**
     * The number of player values.
     */
    public static final int PLAYER_FIELD_COUNT = 13;
    /**
     * The player value position of the minerals.
     */
    public static final int MINERALS_FIELD = 0;
    /**
     * The player value position of the gas.
     */
    public static final int GAS_FIELD = 1;
    /**
     * The player value position of the gathered minerals.
     */
    public static final int GATHERED_MINERALS_FIELD = 2;
    /**
     * The player value position of the gathered gas.
     */
    public static final int GATHERED_GAS_FIELD = 3;
    /**
     * The player value position of the Zerg supply provided; the Terran and Protoss values follow per race.
     */
    public static final int SUPPLY_PROVIDED_FIELD = 4;
    /**
     * The player value position of the Zerg supply used; the Terran and Protoss values follow per race.
     */
    public static final int SUPPLY_USED_FIELD = 5;
    /**
     * The player value position of the Zerg supply cap; the Terran and Protoss values follow per race.
     */
    public static final int SUPPLY_CAP_FIELD = 6;
    /**
     * The number of supply values per race.
     */
    public static final int SUPPLY_FIELDS_PER_RACE = 3;

    /**
     * The unit value position of the pool slot generation.
     */
    public static final int GENERATION_FIELD = 0;
    /**
     * The unit value position of the unit type.
     */
    public static final int TYPE_FIELD = 1;
    /**
     * The unit value position of the owner.
     */
    public static final int OWNER_FIELD = 2;
    /**
     * The unit value position of the X position.
     */
    public static final int POSITION_X_FIELD = 3;
    /**
     * The unit value position of the Y position.
     */
    public static final int POSITION_Y_FIELD = 4;
    /**
     * The unit value position of the exact X position.
     */
    public static final int EXACT_X_FIELD = 5;
    /**
     * The unit value position of the exact Y position.
     */
    public static final int EXACT_Y_FIELD = 6;
    /**
     * The unit value position of the hit points.
     */
    public static final int HIT_POINTS_FIELD = 7;
    /**
     * The unit value position of the shields.
     */
    public static final int SHIELDS_FIELD = 8;
    /**
     * The unit value position of the energy.
     */
    public static final int ENERGY_FIELD = 9;
    /**
     * The unit value position of the primary order.
     */
    public static final int ORDER_FIELD = 10;
    /**
     * The unit value position of the primary order state.
     */
    public static final int ORDER_STATE_FIELD = 11;
    /**
     * The unit value position of the primary order timer.
     */
    public static final int ORDER_TIMER_FIELD = 12;
    /**
     * The unit value position of the order target unit.
     */
    public static final int ORDER_TARGET_FIELD = 13;
    /**
     * The unit value position of the order target X position.
     */
    public static final int ORDER_TARGET_X_FIELD = 14;
    /**
     * The unit value position of the order target Y position.
     */
    public static final int ORDER_TARGET_Y_FIELD = 15;
    /**
     * The unit value position of the secondary order.
     */
    public static final int SECONDARY_ORDER_FIELD = 16;
    /**
     * The unit value position of the movement state.
     */
    public static final int MOVEMENT_STATE_FIELD = 17;
    /**
     * The unit value position of the movement flags.
     */
    public static final int MOVEMENT_FLAGS_FIELD = 18;
    /**
     * The unit value position of the heading.
     */
    public static final int HEADING_FIELD = 19;
    /**
     * The unit value position of the current speed.
     */
    public static final int CURRENT_SPEED_FIELD = 20;
    /**
     * The unit value position of the status flags.
     */
    public static final int STATUS_FIELD = 21;
    /**
     * The unit value position of the resources, the first union WORD.
     */
    public static final int RESOURCES_FIELD = 22;
    /**
     * The unit value position of the carrying flags.
     */
    public static final int CARRYING_FLAGS_FIELD = 23;
    /**
     * The unit value position of the remaining build time.
     */
    public static final int REMAINING_BUILD_TIME_FIELD = 24;
    /**
     * The unit value position of the pool index of the unit the union points to.
     */
    public static final int UNION_UNIT_FIELD = 25;
    /**
     * The unit value position of the second union WORD.
     */
    public static final int UNION_WORD_2_FIELD = 26;
    /**
     * The unit value position of the third and fourth union WORDs.
     */
    public static final int UNION_WORDS_3_4_FIELD = 27;

    private static final int RESOURCE_BEING_GATHERED_WORD_BIT = 0x100;
    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;

    /**
     * Checks whether a unit pool slot holds a live unit.
     *
     * @param pool  the unit pool
     * @param index a pool index (1-based)
     * @return {@code true} if the slot holds a live unit with a sprite, {@code false} otherwise
     */
    public static boolean isLive(@NotNull UnitPool pool, int index) {
        return index <= pool.getCapacity() && pool.isAllocated(index) && pool.getUnit(index).getSpriteOrNull() != null;
    }

    /**
     * Reads the recording fields of a live unit.
     *
     * @param pool  the unit pool
     * @param index a live pool index
     * @return the unit values ({@link #GENERATION_FIELD} through {@link #UNION_WORDS_3_4_FIELD})
     */
    public static int @NotNull [] getUnitValues(@NotNull UnitPool pool, int index) {
        Unit unit = pool.getUnit(index);
        Unit target = unit.getOrderTargetUnit();
        int unionClass = getUnionClass(unit);
        Unit unionTarget = getUnionPointerTarget(unit);
        int unionUnit = unionClass == UNION_POINTER && unionTarget != null ? unionTarget.getIndex() : 0;
        boolean plain = unionClass == UNION_WORDS;
        int resources = plain ? unit.getResources() & WORD_MASK : 0;
        int secondWord = plain
                         ? (unit.getSecondLarvaSideCount() & WORD_MASK) | (unit.isResourceBeingGathered() ? RESOURCE_BEING_GATHERED_WORD_BIT : 0)
                         : 0;
        int thirdFourthWords = plain ? (unit.getThirdLarvaSideCount() & WORD_MASK) | unit.getFourthLarvaSideCount() << Short.SIZE : 0;

        return new int[]{
                pool.getGeneration(index) & BYTE_MASK, unit.getUnitId() & WORD_MASK, unit.getOwnerIndex() & BYTE_MASK,
                unit.getPositionX() & WORD_MASK, unit.getPositionY() & WORD_MASK,
                unit.getFixedPositionX(), unit.getFixedPositionY(),
                unit.getHitPoints(), unit.getShieldPoints(), unit.getEnergy() & WORD_MASK,
                unit.getPrimaryOrder() & BYTE_MASK, unit.getPrimaryOrderState() & BYTE_MASK, unit.getPrimaryOrderCountdown() & BYTE_MASK,
                target == null ? 0 : target.getIndex(), (short) unit.getOrderTargetX(), (short) unit.getOrderTargetY(),
                unit.getSecondaryOrder() & BYTE_MASK,
                unit.getMovementState() & BYTE_MASK, unit.getMovementFlags() & BYTE_MASK,
                unit.getHeading() & BYTE_MASK, unit.getCurrentSpeed(),
                unit.getStatus(), resources, unit.getCarryingFlags() & BYTE_MASK, unit.getRemainingBuildTime() & WORD_MASK,
                unionUnit, secondWord, thirdFourthWords
        };
    }

    /**
     * Finds the unit a unit's union points to.
     *
     * @param unit a unit
     * @return the worker gather target or the linked unit ({@link UnitQueries#getLinkedUnit}: silo nuke or nydus exit) held by the unit union, or {@code null} if the union holds no unit
     */
    public static @Nullable Unit getUnionPointerTarget(@NotNull Unit unit) {
        if (unit.getGatherTarget() != null) {
            return unit.getGatherTarget();
        }

        return UnitQueries.getLinkedUnit(unit);
    }

    /**
     * Classifies the contents of a unit's union.
     *
     * @param unit a unit
     * @return {@link #UNION_POINTER}, {@link #UNION_SPRITE} or {@link #UNION_WORDS}
     */
    public static int getUnionClass(@NotNull Unit unit) {
        if (getUnionPointerTarget(unit) != null) {
            return UNION_POINTER;
        }

        // Pointers outside the pool: pylon psi field sprite and ghost nuke dot.
        return unit.getPsiFieldSprite() != null || unit.getNukeDot() != null ? UNION_SPRITE : UNION_WORDS;
    }

    /**
     * Reads the recording fields of a player.
     *
     * @param game   the game state
     * @param player a player slot ({@code 0..7})
     * @return the player values: minerals, gas, gathered minerals, gathered gas and provided/used/cap supply for Zerg, Terran and Protoss
     */
    public static int @NotNull [] getPlayerValues(@NotNull Game game, int player) {
        int[] values = new int[PLAYER_FIELD_COUNT];
        Player record = game.getPlayerTable().getPlayer(player);

        if (record != null) {
            values[MINERALS_FIELD] = record.getMinerals();
            values[GAS_FIELD]      = record.getGas();

            values[GATHERED_MINERALS_FIELD] = record.getGatheredMinerals();
            values[GATHERED_GAS_FIELD]      = record.getGatheredGas();
        }

        UnitAccounting accounting = game.getPlayerTable().getUnitAccounting();

        if (accounting != null) {
            for (int race = 0; race < ScrRaceIds.PLAYABLE_COUNT; race++) {
                int raceOffset = race * SUPPLY_FIELDS_PER_RACE;
                int slot = race * PlayerTable.PLAYER_SLOTS + player;

                values[SUPPLY_PROVIDED_FIELD + raceOffset] = accounting.getSupplyProvided(slot);
                values[SUPPLY_USED_FIELD + raceOffset]     = accounting.getSupplyUsed(slot);
                values[SUPPLY_CAP_FIELD + raceOffset]      = accounting.getSupplyCap(slot);
            }
        }

        return values;
    }

    /**
     * Reads the order of a created-unit list.
     *
     * @param pool    the unit pool
     * @param visible {@code true} for the visible list, {@code false} for the hidden list
     * @return the list order as pool indices
     */
    public static @NotNull List<Integer> getUnitList(@NotNull UnitPool pool, boolean visible) {
        List<Integer> order = new ArrayList<>();

        for (int index = pool.getFirstCreatedIndex(visible);
             index != 0 && order.size() <= pool.getCapacity();
             index = pool.getNextCreatedIndex(index)) {
            order.add(index);
        }

        return order;
    }


    private RecorderStateUtils() {
    }
}
