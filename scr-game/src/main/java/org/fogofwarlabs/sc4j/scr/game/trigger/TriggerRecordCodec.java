package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.scr.data.type.constant.TriggerUnitGroups;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Owns the 2400-byte trigger record layout of {@code TRIG}/{@code MBRF} and of the running records: sixteen 20-byte
 * conditions, sixty-four 32-byte actions, the execution flags DWORD ({@link #FLAGS_OFFSET}), the owner selector bytes
 * ({@link #OWNERS_OFFSET}) and the action cursor ({@link #ACTION_INDEX_OFFSET}), with the field offsets inside conditions
 * and actions.
 * <p>
 * It decodes and encodes records; the in-place reads of {@link RegionRecordRunnerUtils} and {@link TriggerConditions} use its
 * offsets.
 */
public final class TriggerRecordCodec {
    /**
     * The mask of the low BYTE.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * The size of one trigger record.
     */
    public static final int RECORD_SIZE = 0x960;

    /**
     * The number of conditions of a record.
     */
    static final int CONDITION_COUNT = 16;

    /**
     * The size of one condition.
     */
    static final int CONDITION_SIZE = 20;

    /**
     * The number of actions of a record.
     */
    static final int ACTION_COUNT = 64;

    /**
     * The size of one action.
     */
    static final int ACTION_SIZE = 32;

    /**
     * The offset of the first action, after the conditions.
     */
    static final int ACTIONS_OFFSET = CONDITION_COUNT * CONDITION_SIZE;

    /**
     * The offset of the execution flags DWORD, after the actions.
     */
    static final int FLAGS_OFFSET = 0x940;

    /**
     * The offset of the owner selector bytes, one per player selector.
     */
    static final int OWNERS_OFFSET = 0x944;

    /**
     * The offset of the action cursor byte, the last byte of the record.
     */
    static final int ACTION_INDEX_OFFSET = 0x95F;

    /**
     * The action byte whose bit {@link TriggerFlags#ACTION_STARTED} is the started bit ({@link RunningTrigger#startedActions}).
     */
    static final int ACTION_FLAGS_FIELD = 28;

    /*
     * Condition record fields: location DWORD, player DWORD, amount DWORD, unit WORD, comparison, type, resource type and
     * flags bytes, and the extension WORD.
     */
    static final int CONDITION_LOCATION_FIELD = 0;
    static final int CONDITION_PLAYER_FIELD = 4;
    static final int CONDITION_AMOUNT_FIELD = 8;
    static final int CONDITION_UNIT_FIELD = 12;
    static final int CONDITION_COMPARISON_FIELD = 14;
    static final int CONDITION_TYPE_FIELD = 15;
    static final int CONDITION_RESOURCE_FIELD = 16;
    static final int CONDITION_FLAGS_FIELD = 17;
    static final int CONDITION_EXTENSION_FIELD = 18;

    /*
     * Action record fields: location, string, sound, duration, player and secondary operand DWORDs, the unit WORD, type,
     * modifier and flags bytes (ACTION_FLAGS_FIELD), the extension WORD and the mask marker WORD.
     */
    static final int ACTION_LOCATION_FIELD = 0;
    static final int ACTION_STRING_FIELD = 4;
    static final int ACTION_SOUND_FIELD = 8;
    static final int ACTION_DURATION_FIELD = 12;
    static final int ACTION_PLAYER_FIELD = 16;
    static final int ACTION_SECONDARY_FIELD = 20;
    static final int ACTION_UNIT_FIELD = 24;
    static final int ACTION_TYPE_FIELD = 26;
    static final int ACTION_MODIFIER_FIELD = 27;
    static final int ACTION_EXTENSION_FIELD = 29;
    static final int ACTION_MARKER_FIELD = 30;

    /**
     * The extension WORD {@code 'S','C'} that masks an extended Deaths read or Set Deaths write by the location DWORD.
     */
    static final int MASK_MARKER = 0x4353;

    /**
     * Decodes the definition of one record.
     *
     * @param source the bytes holding the record
     * @param offset the record offset
     * @return the decoded definition
     */
    static @NotNull TriggerDefinition decodeDefinition(byte @NotNull [] source, int offset) {
        TriggerDefinition.Condition[] conditions = new TriggerDefinition.Condition[CONDITION_COUNT];
        TriggerDefinition.Action[] actions = new TriggerDefinition.Action[ACTION_COUNT];

        for (int index = 0; index < conditions.length; index++) {
            conditions[index] = decodeCondition(source, offset + index * CONDITION_SIZE);
        }

        for (int index = 0; index < actions.length; index++) {
            actions[index] = decodeAction(source, offset + ACTIONS_OFFSET + index * ACTION_SIZE);
        }

        return new TriggerDefinition(conditions, actions, Arrays.copyOfRange(source, offset + OWNERS_OFFSET, offset + ACTION_INDEX_OFFSET));
    }

    /**
     * Decodes one condition.
     *
     * @param source   the bytes holding the condition
     * @param position the condition offset
     * @return the decoded condition
     */
    static @NotNull TriggerDefinition.Condition decodeCondition(byte @NotNull [] source, int position) {
        return new TriggerDefinition.Condition(read(source, position + CONDITION_LOCATION_FIELD, Integer.BYTES),
                                               read(source, position + CONDITION_PLAYER_FIELD, Integer.BYTES),
                                               read(source, position + CONDITION_AMOUNT_FIELD, Integer.BYTES),
                                               read(source, position + CONDITION_UNIT_FIELD, Short.BYTES),
                                               read(source, position + CONDITION_COMPARISON_FIELD, Byte.BYTES),
                                               read(source, position + CONDITION_TYPE_FIELD, Byte.BYTES),
                                               read(source, position + CONDITION_RESOURCE_FIELD, Byte.BYTES),
                                               read(source, position + CONDITION_FLAGS_FIELD, Byte.BYTES),
                                               read(source, position + CONDITION_EXTENSION_FIELD, Short.BYTES));
    }

    /**
     * Decodes one action.
     *
     * @param source   the bytes holding the action
     * @param position the action offset
     * @return the decoded action
     */
    static @NotNull TriggerDefinition.Action decodeAction(byte @NotNull [] source, int position) {
        return new TriggerDefinition.Action(read(source, position + ACTION_LOCATION_FIELD, Integer.BYTES),
                                            read(source, position + ACTION_STRING_FIELD, Integer.BYTES),
                                            read(source, position + ACTION_SOUND_FIELD, Integer.BYTES),
                                            read(source, position + ACTION_DURATION_FIELD, Integer.BYTES),
                                            read(source, position + ACTION_PLAYER_FIELD, Integer.BYTES),
                                            read(source, position + ACTION_SECONDARY_FIELD, Integer.BYTES),
                                            read(source, position + ACTION_UNIT_FIELD, Short.BYTES),
                                            read(source, position + ACTION_TYPE_FIELD, Byte.BYTES),
                                            read(source, position + ACTION_MODIFIER_FIELD, Byte.BYTES),
                                            read(source, position + ACTION_FLAGS_FIELD, Byte.BYTES),
                                            read(source, position + ACTION_EXTENSION_FIELD, Short.BYTES),
                                            read(source, position + ACTION_MARKER_FIELD, Short.BYTES) == MASK_MARKER);
    }

    /**
     * Reads one owner selector byte of a record.
     *
     * @param source   the bytes holding the record
     * @param offset   the record offset
     * @param selector the player selector
     * @return the unsigned owner byte, nonzero if the record runs for the selector
     */
    static int readOwnerSelector(byte @NotNull [] source, int offset, int selector) {
        return source[offset + OWNERS_OFFSET + selector] & BYTE_MASK;
    }

    /**
     * Creates the execution of a record with the record's execution flags, action cursor and bytes.
     *
     * @param definition the record's definition
     * @param source     the bytes holding the record
     * @param offset     the record offset
     * @return the created {@link RunningTrigger} instance
     */
    static @NotNull RunningTrigger createExecution(@NotNull TriggerDefinition definition, byte @NotNull [] source, int offset) {
        RunningTrigger trigger = new RunningTrigger(definition,
                                                    read(source, offset + FLAGS_OFFSET, Integer.BYTES),
                                                    source[offset + ACTION_INDEX_OFFSET] & BYTE_MASK);

        trigger.setRecordBytes(Arrays.copyOfRange(source, offset, offset + RECORD_SIZE));

        return trigger;
    }

    /**
     * Applies the per-record EUD scan: {@link TriggerContext#EXTENDED_DEATHS_CONDITION_MODE} for a Deaths condition and
     * {@link TriggerContext#EXTENDED_SET_DEATHS_MODE} for a Set Deaths action, each with a valid comparison or modifier,
     * whose player operand is {@link PlayerTable#PLAYER_SLOTS} or above the player selectors (unsigned) or whose unit
     * operand is above the unit groups.
     *
     * @param source the record bytes
     * @param offset the record offset
     * @return the EUD mode bits the record sets
     */
    static int scanExtendedDeaths(byte @NotNull [] source, int offset) {
        int mode = 0;

        for (int index = 0; index < CONDITION_COUNT; index++) {
            int position = offset + index * CONDITION_SIZE;

            if (read(source, position + CONDITION_TYPE_FIELD, Byte.BYTES) == TriggerConditionIds.DEATHS &&
                read(source, position + CONDITION_COMPARISON_FIELD, Byte.BYTES) < TriggerModifiers.COUNT &&
                isExtendedOperand(read(source, position + CONDITION_PLAYER_FIELD, Integer.BYTES),
                                  read(source, position + CONDITION_UNIT_FIELD, Short.BYTES))) {
                mode |= TriggerContext.EXTENDED_DEATHS_CONDITION_MODE;

                break;
            }
        }

        for (int index = 0; index < ACTION_COUNT; index++) {
            int position = offset + ACTIONS_OFFSET + index * ACTION_SIZE;

            if (read(source, position + ACTION_TYPE_FIELD, Byte.BYTES) == TriggerActionIds.SET_DEATHS &&
                read(source, position + ACTION_MODIFIER_FIELD, Byte.BYTES) < TriggerModifiers.COUNT &&
                isExtendedOperand(read(source, position + ACTION_PLAYER_FIELD, Integer.BYTES),
                                  read(source, position + ACTION_UNIT_FIELD, Short.BYTES))) {
                mode |= TriggerContext.EXTENDED_SET_DEATHS_MODE;

                break;
            }
        }

        return mode;
    }

    /**
     * Tests whether the operands of a Deaths condition or Set Deaths action make it extended.
     *
     * @param player   the player operand DWORD
     * @param unitType the unit operand WORD
     * @return {@code true} if the player operand is {@link PlayerTable#PLAYER_SLOTS} or above the player selectors (unsigned) or the unit operand is above the unit groups, {@code false} otherwise
     */
    private static boolean isExtendedOperand(int player, int unitType) {
        return Integer.compareUnsigned(player, TriggerPlayerSelectors.NON_ALLIED_VICTORY_PLAYERS + 1) >= 0 ||
               unitType > TriggerUnitGroups.FACTORIES || player == PlayerTable.PLAYER_SLOTS;
    }

    /**
     * Encodes the record bytes of an execution: its definition with its execution flags, cursor and started bits.
     *
     * @param trigger the execution
     * @return the record bytes
     */
    static byte @NotNull [] exportRecord(@NotNull RunningTrigger trigger) {
        byte[] result = new byte[RECORD_SIZE];

        for (int offset = 0; offset < result.length; offset++) {
            result[offset] = (byte) readByte(trigger, offset);
        }

        return result;
    }

    /**
     * Encodes one byte of the record of an execution.
     *
     * @param trigger the execution
     * @param offset  the record offset
     * @return the unsigned byte
     */
    static int readByte(@NotNull RunningTrigger trigger, int offset) {
        if (offset < ACTIONS_OFFSET) {
            TriggerDefinition.Condition condition = trigger.definition.getCondition(offset / CONDITION_SIZE);
            int field = offset % CONDITION_SIZE;

            if (field < CONDITION_PLAYER_FIELD) {
                return getByte(condition.getLocation(), field - CONDITION_LOCATION_FIELD);
            }

            if (field < CONDITION_AMOUNT_FIELD) {
                return getByte(condition.getPlayerSelector(), field - CONDITION_PLAYER_FIELD);
            }

            if (field < CONDITION_UNIT_FIELD) {
                return getByte(condition.getAmount(), field - CONDITION_AMOUNT_FIELD);
            }

            if (field < CONDITION_COMPARISON_FIELD) {
                return getByte(condition.getUnitType(), field - CONDITION_UNIT_FIELD);
            }

            if (field < CONDITION_TYPE_FIELD) {
                return getByte(condition.getComparison(), 0);
            }

            if (field < CONDITION_RESOURCE_FIELD) {
                return getByte(condition.getType(), 0);
            }

            if (field < CONDITION_FLAGS_FIELD) {
                return getByte(condition.getResourceType(), 0);
            }

            if (field < CONDITION_EXTENSION_FIELD) {
                return getByte(condition.getFlags(), 0);
            }

            return getByte(condition.getExtension(), field - CONDITION_EXTENSION_FIELD);
        }

        if (offset < FLAGS_OFFSET) {
            int index = (offset - ACTIONS_OFFSET) / ACTION_SIZE;
            TriggerDefinition.Action action = trigger.definition.getAction(index);
            int field = (offset - ACTIONS_OFFSET) % ACTION_SIZE;

            if (field < ACTION_STRING_FIELD) {
                return getByte(action.getLocation(), field - ACTION_LOCATION_FIELD);
            }

            if (field < ACTION_SOUND_FIELD) {
                return getByte(action.getStringIndex(), field - ACTION_STRING_FIELD);
            }

            if (field < ACTION_DURATION_FIELD) {
                return getByte(action.getSoundIndex(), field - ACTION_SOUND_FIELD);
            }

            if (field < ACTION_PLAYER_FIELD) {
                return getByte(action.getDuration(), field - ACTION_DURATION_FIELD);
            }

            if (field < ACTION_SECONDARY_FIELD) {
                return getByte(action.getPlayerSelector(), field - ACTION_PLAYER_FIELD);
            }

            if (field < ACTION_UNIT_FIELD) {
                return getByte(action.getSecondaryOperand(), field - ACTION_SECONDARY_FIELD);
            }

            if (field < ACTION_TYPE_FIELD) {
                return getByte(action.getUnitType(), field - ACTION_UNIT_FIELD);
            }

            if (field < ACTION_MODIFIER_FIELD) {
                return getByte(action.getType(), 0);
            }

            if (field < ACTION_FLAGS_FIELD) {
                return getByte(action.getModifier(), 0);
            }

            if (field < ACTION_EXTENSION_FIELD) {
                // The started bit is the execution's (RunningTrigger#startedActions).
                return ((action.getFlags() & ~TriggerFlags.ACTION_STARTED) |
                        ((int) (trigger.startedActions >>> index) & TriggerFlags.ACTION_STARTED)) & BYTE_MASK;
            }

            if (field < ACTION_EXTENSION_FIELD + Short.BYTES) {
                return getByte(action.getExtension(), field - ACTION_EXTENSION_FIELD);
            }

            return 0;
        }

        if (offset < OWNERS_OFFSET) {
            return getByte(trigger.executionFlags, offset - FLAGS_OFFSET);
        }

        if (offset < ACTION_INDEX_OFFSET) {
            return trigger.definition.getOwnerSelector(offset - OWNERS_OFFSET);
        }

        return trigger.currentActionIndex & BYTE_MASK;
    }

    /**
     * Returns one little-endian byte of a value.
     *
     * @param value the value
     * @param index the byte index
     * @return the unsigned byte {@code index} of the value
     */
    private static int getByte(int value, int index) {
        return (value >>> (index * Byte.SIZE)) & BYTE_MASK;
    }

    /**
     * Reads a little-endian value of one to four bytes.
     *
     * @param source the bytes holding the value
     * @param offset the offset of the value
     * @param width  the size of the value in bytes
     * @return the value, zero-extended when narrower than four bytes
     */
    private static int read(byte @NotNull [] source, int offset, int width) {
        int value = 0;

        for (int index = 0; index < width; index++) {
            value |= (source[offset + index] & BYTE_MASK) << (index * Byte.SIZE);
        }

        return value;
    }


    private TriggerRecordCodec() {
    }
}
