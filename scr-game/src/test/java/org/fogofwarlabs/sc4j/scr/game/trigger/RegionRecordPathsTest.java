package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.scr.data.type.constant.TriggerUnitGroups;
import org.fogofwarlabs.sc4j.scr.game.map.MapStringSection;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.test.PlayerProgressTestUtils;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicDynamicRegionsUtils;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

/**
 * Checks that in-place and decoded execution of random string-section records leave the same state, and that a Set Deaths
 * action reaches a record only through the DWORDs it writes.
 */
final class RegionRecordPathsTest {
    private static final int STRINGS_SIZE = 0x4000;

    private static final int RECORD_OFFSET = 0x400;

    private static final int RESULTS_OFFSET = 0x3000;
    private static final int RESULT_DWORD_COUNT = 16;
    private static final int RESULTS_SIZE = RESULT_DWORD_COUNT * Integer.BYTES;

    private static final int CASE_COUNT = 2000;
    private static final int RANDOM_SEED = 0x51CE84;

    /**
     * The player operand of the fixed record that the context loads.
     */
    private static final int FIXED_RECORD_PLAYER_OPERAND = 1000;
    /**
     * A player operand that addresses no region.
     */
    private static final int UNMAPPED_PLAYER_OPERAND = 0x7FFF0000;

    /**
     * The last player slot, whose death-table DWORD ends right before a record in the proof boundaries.
     */
    private static final int LAST_SLOT = PlayerTable.PLAYER_SLOTS - 1;

    /**
     * One run: a trigger context in EUD mode whose address space holds a copy of the string section and the deaths.
     */
    private static final class Run {
        private final @NotNull UnitAccounting accounting;
        private final @NotNull TriggerContext context;
        private final @NotNull MapStringSection strings;
        private final @NotNull ClassicRegion region;

        Run(byte @NotNull [] strings, int @NotNull [] deaths) {
            this.accounting = PlayerProgressTestUtils.createUnitAccounting();

            for (int player = 0; player < deaths.length && player < PlayerTable.PLAYER_SLOTS; player++) {
                this.accounting.getScores().modifyDeaths(player, player % 4, TriggerModifiers.SET_TO, deaths[player]);
            }

            Player[] players = new Player[PlayerTable.PLAYABLE_PLAYER_SLOTS];
            int[] types = new int[PlayerTable.PLAYER_SLOTS];

            for (int index = 0; index < PlayerTable.PLAYABLE_PLAYER_SLOTS; index++) {
                players[index] = new Player(index, index, index < 2 ? PlayerTypes.HUMAN : PlayerTypes.NONE, 0, 0);
                types[index]   = players[index].getType();
            }

            byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
            ByteBuffer triggerRecord = TriggerRecordScenario.createRecord(records, 0);

            triggerRecord.put(TriggerRecordCodec.ACTIONS_OFFSET + TriggerRecordCodec.ACTION_TYPE_FIELD, (byte) TriggerActionIds.SET_DEATHS);
            triggerRecord.putInt(TriggerRecordCodec.ACTIONS_OFFSET + TriggerRecordCodec.ACTION_PLAYER_FIELD, FIXED_RECORD_PLAYER_OPERAND);
            triggerRecord.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

            this.context = new TriggerContext();
            this.context.initializeClassicPlayerRecords(players);
            this.context.loadRecords(records);
            this.context.initializeEvaluation(this.accounting, new PlayerRelations(types, false, false, false), new int[]{0, 0, 0, 0, 0});

            this.strings = new MapStringSection(strings, false);

            ClassicAddressSpace space = ClassicAddressSpace.create(this.context,
                                                                   this.accounting.getScores(),
                                                                   this.accounting,
                                                                   null,
                                                                   new MessageLines());

            space.addAll(ClassicDynamicRegionsUtils.create(this.strings));
            this.context.setExtendedDeathsOperations(space);
            this.context.currentPlayer = 0;

            ClassicRegion stringRegion = space.findRegion(ClassicDynamicRegionsUtils.STRING_SECTION_BASE);

            Assertions.assertNotNull(stringRegion);

            this.region = stringRegion;

            Assertions.assertTrue(this.region.isPlainMemory());
        }

        byte @NotNull [] readBytes() {
            byte[] bytes = new byte[STRINGS_SIZE];

            this.strings.read(0, bytes, 0, STRINGS_SIZE);

            return bytes;
        }
    }

    /**
     * Runs the fixture and captures an unsupported-operation boundary.
     *
     * @param run the fixture operation
     * @return the message of the unrepresented boundary a run stops at, or {@code null}
     */
    private static @Nullable String runToBoundary(@NotNull Runnable run) {
        try {
            run.run();

            return null;
        } catch (UnsupportedOperationException boundary) {
            return boundary.getMessage();
        }
    }

    /**
     * Writes a record of random conditions and actions, and a random execution state.
     *
     * @param record the destination record
     * @param random the case generator
     */
    private static void writeRecord(@NotNull ByteBuffer record, @NotNull Random random) {
        int conditions = random.nextInt(3);

        for (int index = 0; index < conditions; index++) {
            int base = index * TriggerRecordCodec.CONDITION_SIZE;
            int kind = random.nextInt(4);
            int type = kind == 0 ? TriggerConditionIds.ALWAYS : kind == 1 ? TriggerConditionIds.SWITCH : TriggerConditionIds.DEATHS;

            record.put(base + TriggerRecordCodec.CONDITION_TYPE_FIELD, (byte) type);
            record.put(base + TriggerRecordCodec.CONDITION_COMPARISON_FIELD,
                       (byte) (type == TriggerConditionIds.SWITCH
                               ? TriggerModifiers.SWITCH_IS_SET + random.nextInt(2)
                               : new int[]{TriggerModifiers.AT_LEAST, TriggerModifiers.AT_MOST, TriggerModifiers.EXACTLY}[random.nextInt(3)]));
            record.put(base + TriggerRecordCodec.CONDITION_RESOURCE_FIELD, (byte) random.nextInt(4));
            record.putInt(base + TriggerRecordCodec.CONDITION_AMOUNT_FIELD, random.nextInt(4));

            int[] operands = createOperands(random);

            record.putInt(base + TriggerRecordCodec.CONDITION_PLAYER_FIELD, operands[0]);
            record.putShort(base + TriggerRecordCodec.CONDITION_UNIT_FIELD, (short) operands[1]);

            if (random.nextInt(6) == 0) {
                record.putInt(base + TriggerRecordCodec.CONDITION_LOCATION_FIELD, 0x00FF00FF);
                record.putShort(base + TriggerRecordCodec.CONDITION_EXTENSION_FIELD, (short) TriggerRecordCodec.MASK_MARKER);
            }

            if (random.nextInt(8) == 0) {
                record.put(base + TriggerRecordCodec.CONDITION_FLAGS_FIELD, (byte) TriggerFlags.CONDITION_DISABLED);
            }
        }

        int actions = 1 + random.nextInt(4);

        for (int index = 0; index < actions; index++) {
            int base = TriggerRecordCodec.ACTIONS_OFFSET + index * TriggerRecordCodec.ACTION_SIZE;
            int kind = random.nextInt(10);
            int type = kind == 0
                       ? TriggerActionIds.PRESERVE_TRIGGER
                       : kind == 1 ? TriggerActionIds.FIRST_WITHOUT_HANDLER + random.nextInt(100) : TriggerActionIds.SET_DEATHS;

            record.put(base + TriggerRecordCodec.ACTION_TYPE_FIELD, (byte) type);
            record.put(base + TriggerRecordCodec.ACTION_MODIFIER_FIELD, (byte) (TriggerModifiers.SET_TO + random.nextInt(3)));
            record.putInt(base + TriggerRecordCodec.ACTION_SECONDARY_FIELD, random.nextInt(8));

            int[] operands = createOperands(random);

            record.putInt(base + TriggerRecordCodec.ACTION_PLAYER_FIELD, operands[0]);
            record.putShort(base + TriggerRecordCodec.ACTION_UNIT_FIELD, (short) operands[1]);

            if (random.nextInt(6) == 0) {
                record.putInt(base + TriggerRecordCodec.ACTION_LOCATION_FIELD, 0x0000FFFF);
                record.putShort(base + TriggerRecordCodec.ACTION_MARKER_FIELD, (short) TriggerRecordCodec.MASK_MARKER);
            }

            if (random.nextInt(8) == 0) {
                record.put(base + TriggerRecordCodec.ACTION_FLAGS_FIELD, (byte) TriggerFlags.ACTION_DISABLED);
            }
        }

        int[] flags = {0, TriggerFlags.EXECUTION_PRESERVE, TriggerFlags.EXECUTION_DISABLED,
                       TriggerFlags.EXECUTION_ACTIONS_RUNNING | TriggerFlags.EXECUTION_PRESERVE,
                       TriggerFlags.EXECUTION_SETS_RESULT | TriggerFlags.EXECUTION_PRESERVE, TriggerFlags.EXECUTION_IGNORE_INTERFACE_ACTIONS};
        int executionFlags = flags[random.nextInt(flags.length)];

        record.putInt(TriggerRecordCodec.FLAGS_OFFSET, executionFlags);
        record.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        if ((executionFlags & TriggerFlags.EXECUTION_ACTIONS_RUNNING) != 0) {
            record.put(TriggerRecordCodec.ACTION_INDEX_OFFSET, (byte) random.nextInt(actions + 1));
        }
    }

    /**
     * Creates a random player operand and unit WORD: a death-table slot, the current player, a player group, a loss-counter group, a DWORD of the
     * result area or of the running record itself, or an address without a region.
     *
     * @param random the case generator
     * @return the player operand and unit WORD
     */
    private static int @NotNull [] createOperands(@NotNull Random random) {
        return switch (random.nextInt(7)) {
            case 0 -> new int[]{random.nextInt(PlayerTable.PLAYER_SLOTS), random.nextInt(4)};
            case 1 -> new int[]{TriggerPlayerSelectors.CURRENT_PLAYER, random.nextInt(4)};
            case 2 -> new int[]{TriggerPlayerSelectors.ALL_PLAYERS, random.nextInt(4)};
            case 3 -> new int[]{random.nextInt(PlayerTable.PLAYER_SLOTS), TriggerUnitGroups.ANY_UNIT + random.nextInt(4)};
            case 4 -> {
                int offset = RESULTS_OFFSET + Integer.BYTES * random.nextInt(RESULT_DWORD_COUNT);

                yield new int[]{(ClassicDynamicRegionsUtils.STRING_SECTION_BASE + offset - ClassicAddressSpace.DEATH_TABLE_ADDRESS) / Integer.BYTES,
                                0};
            }
            case 5 -> {
                int offset = RECORD_OFFSET + random.nextInt(TriggerRecordCodec.RECORD_SIZE / Integer.BYTES) * Integer.BYTES;

                yield new int[]{(ClassicDynamicRegionsUtils.STRING_SECTION_BASE + offset - ClassicAddressSpace.DEATH_TABLE_ADDRESS) / Integer.BYTES,
                                0};
            }
            default -> new int[]{UNMAPPED_PLAYER_OPERAND, 0};
        };
    }


    @Test
    void fast_and_decoded_region_record_paths_leave_same_state() {
        Random random = new Random(RANDOM_SEED);
        int changed = 0;

        for (int index = 0; index < CASE_COUNT; index++) {
            byte[] strings = new byte[STRINGS_SIZE];

            for (int offset = RESULTS_OFFSET; offset < RESULTS_OFFSET + RESULTS_SIZE; offset++) {
                strings[offset] = (byte) random.nextInt(4);
            }

            int[] deaths = new int[16];

            for (int player = 0; player < deaths.length; player++) {
                deaths[player] = random.nextInt(4);
            }

            writeRecord(TriggerRecordScenario.createRecordAt(strings, RECORD_OFFSET), random);

            Run fast = new Run(strings, deaths);
            Run decoded = new Run(strings, deaths);
            String fastBoundary = runToBoundary(() -> RegionRecordRunnerUtils.runRegionRecord(fast.context, fast.region, RECORD_OFFSET, 0, 0));
            String decodedBoundary = runToBoundary(() -> RegionRecordRunnerUtils.executeDecoded(decoded.context, decoded.region,
                                                                                                RECORD_OFFSET, 0, 0, -1));
            String label = "case " + index;

            Assertions.assertEquals(decodedBoundary, fastBoundary, label + ": unrepresented boundary");
            Assertions.assertArrayEquals(decoded.readBytes(), fast.readBytes(), label + ": string section");

            for (int player = 0; player < PlayerTable.PLAYER_SLOTS; player++) {
                for (int unitType = 0; unitType < TriggerUnitGroups.NONE; unitType++) {
                    Assertions.assertEquals(decoded.accounting.getScores().getDeaths(player, unitType),
                                            fast.accounting.getScores().getDeaths(player, unitType),
                                            label + ": deaths " + player + "/" + unitType);
                }
            }

            for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
                Assertions.assertEquals(decoded.context.getPlayerResult(player), fast.context.getPlayerResult(player), label + ": result " + player);
            }

            Assertions.assertEquals(decoded.context.isEvaluationStopped(), fast.context.isEvaluationStopped(), label + ": stop state");

            if (!Arrays.equals(strings, fast.readBytes())) {
                changed++;
            }
        }

        Assertions.assertTrue(changed > CASE_COUNT / 4, "The cases change the string section: " + changed);
    }

    @Test
    void set_deaths_reaches_record_only_through_dwords_it_writes() {
        int unitType = 5;
        int row = ClassicAddressSpace.getAddress(0, unitType);

        // The record ends right before the row: no write of the unit reaches it; one byte later the first slot does.
        Assertions.assertFalse(ExtendedDeathsUtils.mayWriteRecord(row - TriggerRecordCodec.RECORD_SIZE, 0, unitType));
        Assertions.assertTrue(ExtendedDeathsUtils.mayWriteRecord(row - TriggerRecordCodec.RECORD_SIZE + 1, 0, unitType));

        // The record starts right after slot 11: a group or the current player may name slot 11, player 0 cannot.
        int lastSlot = ClassicAddressSpace.getAddress(LAST_SLOT, unitType);

        Assertions.assertTrue(ExtendedDeathsUtils.mayWriteRecord(lastSlot + Integer.BYTES, TriggerPlayerSelectors.ALL_PLAYERS, unitType),
                              "A write at slot 11 may spill into the next aligned DWORD.");
        Assertions.assertTrue(ExtendedDeathsUtils.mayWriteRecord(lastSlot + Integer.BYTES, TriggerPlayerSelectors.CURRENT_PLAYER, unitType));
        Assertions.assertFalse(ExtendedDeathsUtils.mayWriteRecord(lastSlot + 2 * Integer.BYTES, TriggerPlayerSelectors.ALL_PLAYERS, unitType));
        Assertions.assertFalse(ExtendedDeathsUtils.mayWriteRecord(lastSlot + Integer.BYTES, 0, unitType));
        // The loss-counter unit groups never write memory.
        Assertions.assertFalse(ExtendedDeathsUtils.mayWriteRecord(row, 0, TriggerUnitGroups.FACTORIES));
        // Ranges wrap around the 32-bit address space.
        Assertions.assertTrue(ClassicAddressSpace.canWriteDeathsRange(0xFFFFFFF0, 0x20, 0x00000004, 1));
        Assertions.assertTrue(ClassicAddressSpace.canWriteDeathsRange(0x00000000, 0x20, 0xFFFFFFFE, 1), "An unaligned write spans 0xFFFFFFFC..0x3.");
        Assertions.assertFalse(ClassicAddressSpace.canWriteDeathsRange(0x00000004, 0x20, 0xFFFFFFFE, 1));
    }
}
