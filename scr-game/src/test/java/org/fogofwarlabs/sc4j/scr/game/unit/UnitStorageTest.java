package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitTraits;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.type.ArrayDatColumn;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.test.UnitTestUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Checks the unit memory scenario and the unit fields that share its storage.
 * <p>
 * The byte offsets are the native {@code 488}-byte classic unit memory, which EUD triggers read and write; the layout is a
 * contract, so the tests read the exported image by offset.
 */
final class UnitStorageTest {
    private static final int CLASSIC_UNIT_IMAGE_SIZE = 488;
    private static final int LAST_IMAGE_OFFSET = CLASSIC_UNIT_IMAGE_SIZE - 1;
    /**
     * The first offset whose DWORD crosses the end of the image.
     */
    private static final int FIRST_CROSSING_INTEGER_OFFSET = CLASSIC_UNIT_IMAGE_SIZE - Integer.BYTES + 1;
    private static final int LARVA_SIDE_COUNT = 4;
    private static final int BUILD_QUEUE_SLOT_COUNT = 5;
    private static final int THIRD_BUILD_QUEUE_SLOT = 2;
    private static final int FIRST_ALIASED_QUEUE_INDEX = -110;
    private static final int LAST_ALIASED_QUEUE_INDEX = 133;
    private static final int BYTE_PATTERN_MULTIPLIER = 37;
    private static final int PARTIAL_CLEAR_PATTERN_MULTIPLIER = 97;
    private static final int BOUNDS_PATTERN_MULTIPLIER = 53;
    private static final int CAPTURE_RANDOM_SEED = 0x5C94E30A;
    private static final int CAPTURE_RANDOM_MULTIPLIER = 1664525;
    private static final int CAPTURE_RANDOM_INCREMENT = 1013904223;

    private static final int HIT_POINTS_OFFSET = 0x10;
    private static final int HIT_POINTS_HIGH_BYTE_OFFSET = 0x13;
    private static final int SPRITE_REFERENCE_OFFSET = 0x18;
    private static final int MOVEMENT_TARGET_REFERENCE_OFFSET = 0x28;
    private static final int MOVEMENT_TARGET_REFERENCE_HIGH_OFFSET = 0x2C;
    private static final int MOVEMENT_TARGET_REFERENCE_LAST_BYTE_OFFSET = 0x2F;
    private static final int MOVEMENT_FLAGS_OFFSET = 0x38;
    private static final int POSITION_OFFSET = 0x40;
    private static final int POSITION_HORIZONTAL_HIGH_BYTE_OFFSET = 0x41;
    private static final int POSITION_VERTICAL_OFFSET = 0x42;
    private static final int TOP_SPEED_OFFSET = 0x4C;
    private static final int TOP_SPEED_LAST_BYTE_OFFSET = 0x4F;
    private static final int CURRENT_SPEED_SECOND_BYTE_OFFSET = 0x51;
    private static final int NEXT_SPEED_OFFSET = 0x54;
    private static final int VELOCITY_HORIZONTAL_OFFSET = 0x58;
    private static final int VELOCITY_VERTICAL_OFFSET = 0x5C;
    private static final int ORDER_SIGNAL_OFFSET = 0x6B;
    private static final int ORDER_TARGET_REFERENCE_OFFSET = 0x80;
    private static final int SUBUNIT_REFERENCE_OFFSET = 0xA0;
    private static final int FIRST_ORDER_REFERENCE_OFFSET = 0xA8;
    private static final int LAST_ORDER_REFERENCE_OFFSET = 0xB0;
    private static final int AUTOMATIC_TARGET_REFERENCE_OFFSET = 0xB8;
    private static final int PARENT_REFERENCE_OFFSET = 0xC0;
    private static final int BUILD_QUEUE_INDEX_OFFSET = 0xE8;
    private static final int BUILD_QUEUE_INDEX_HIGH_BYTE_OFFSET = 0xE9;
    private static final int SECONDARY_ORDER_OFFSET = 0xEA;
    private static final int DAMAGE_OVERLAY_COUNT_OFFSET = 0xEB;
    private static final int CARRIED_POWERUP_REFERENCE_OFFSET = 0x108;
    private static final int CARRIED_POWERUP_SECOND_DWORD_OFFSET = 0x10C;
    private static final int RESOURCES_PRECEDING_BYTE_OFFSET = 0x127;
    private static final int RESOURCES_OFFSET = 0x128;
    private static final int CURRENT_BUILD_REFERENCE_OFFSET = 0x158;
    private static final int PATH_REFERENCE_OFFSET = 0x188;
    private static final int SPEED_MODIFIERS_OFFSET = 0x1A1;
    private static final int TRAILING_CAPTURE_REFERENCE_OFFSET = 0x1C8;
    /**
     * The reference slots of the classic unit memory, which the capture keeps as unresolved addresses.
     */
    private static final int @NotNull [] REFERENCE_OFFSETS = {
            SPRITE_REFERENCE_OFFSET, MOVEMENT_TARGET_REFERENCE_OFFSET, ORDER_TARGET_REFERENCE_OFFSET, SUBUNIT_REFERENCE_OFFSET,
            FIRST_ORDER_REFERENCE_OFFSET, LAST_ORDER_REFERENCE_OFFSET, AUTOMATIC_TARGET_REFERENCE_OFFSET, PARENT_REFERENCE_OFFSET,
            CARRIED_POWERUP_REFERENCE_OFFSET, CURRENT_BUILD_REFERENCE_OFFSET, PATH_REFERENCE_OFFSET, TRAILING_CAPTURE_REFERENCE_OFFSET
    };

    private static final int UNIT_POOL_INDEX = 1;
    private static final int TARGET_POOL_INDEX = 2;
    private static final int SNAPSHOT_POOL_INDEX = 7;
    private static final int POOL_CAPACITY = 1;
    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;
    private static final int HIGH_BYTE_SHIFT = Integer.SIZE - Byte.SIZE;
    /**
     * A DWORD whose four bytes differ, and a second one for the following DWORD.
     */
    private static final int DWORD_PATTERN = 0x12345678;
    private static final int SECOND_DWORD_PATTERN = 0xABCDEF01;
    /**
     * A DWORD whose middle WORD differs from its outer bytes.
     */
    private static final int OVERLAPPING_DWORD_PATTERN = 0xFFEEDDCC;
    /**
     * A little-endian DWORD whose bytes count up from {@code 0x12}.
     */
    private static final int LITTLE_ENDIAN_DWORD = 0x78563412;
    private static final int DWORD_AFTER_OVERWIDE_WORD_WRITE = 0x78ABCD12;
    private static final int DWORD_AFTER_OVERWIDE_BYTE_WRITE = 0xFFABCD12;
    private static final int DWORD_AFTER_MIDDLE_CLEAR = 0xFF000012;

    /**
     * Resource WORDs at zero, a small count, the largest positive and smallest negative signed WORD, and all bits set.
     */
    private static final int @NotNull [] RESOURCE_WORD_SAMPLES = {0, 101, Short.MAX_VALUE, Short.MAX_VALUE + 1, WORD_MASK};
    private static final int RESOURCE_LOW_BYTE = 0x34;
    private static final int OVERWIDE_RESOURCES = 0x12345;
    private static final int QUEUED_TYPE_WORD = 0xCAFE;
    private static final int PATH_INDEX = 19;
    /**
     * Clear lengths from empty through unaligned and partial-field to the whole image.
     */
    private static final int @NotNull [] CLEAR_LENGTHS = {0, 1, 2, 3, 7, 24, 40, CLASSIC_UNIT_IMAGE_SIZE};
    private static final int REFERENCE_HIGH_BIT_BYTE = 0x80;
    private static final int SPIDER_MINE_COUNT = 3;
    private static final int OVERWIDE_SPIDER_MINE_COUNT = 0x103;
    /**
     * Horizontal {@code -1} and vertical {@code -32767} as one packed DWORD.
     */
    private static final int PACKED_POSITION = 0x8001FFFF;
    private static final int POSITION_HORIZONTAL_HIGH_BYTE = 0x12;
    private static final int PRIMARY_ORDER_WORD = 0xABCD;
    private static final int ORDER_SIGNAL = 0xEF;
    private static final int OVERWIDE_SPEED_INCREASE = 0x123;
    private static final int OVERWIDE_SPEED_DECREASE = 0x145;
    private static final int OVERWIDE_MOVEMENT_DIRECTION = 0x167;
    private static final int OVERWIDE_MOVEMENT_FLAGS = 0x189;
    private static final int TOP_SPEED = 111;
    private static final int CURRENT_SPEED = 222;
    private static final int NEXT_SPEED = 333;
    private static final int VELOCITY_X = 444;
    private static final int VELOCITY_Y = 555;
    private static final int OVERWIDE_UNIT_TYPE = 0x12345;
    private static final int OVERWIDE_OWNER = 0x23456;
    private static final int OVERWIDE_POSITION_X = 0x34567;
    private static final int OVERWIDE_POSITION_Y = 0x45678;
    private static final int NEGATIVE_HEADING = -2;
    private static final int NEGATIVE_ORDER_SIGNAL = -3;
    private static final int SMALL_TOP_SPEED = 4;
    private static final int INNER_OFFSET = 10;
    private static final int CROSSING_CLEAR_LENGTH = 3;
    private static final int OVERWIDE_WORD = 0x1ABCD;
    private static final int OVERWIDE_BYTE = 0x1FF;
    private static final int SCOUT_TOP_SPEED = 999;
    private static final int SCOUT_CURRENT_SPEED = 0x6AB;
    private static final int SCOUT_VELOCITY_X = 123;
    private static final int SCOUT_VELOCITY_Y = 456;
    private static final int ISCRIPT_MOVEMENT_SPEED = 1;
    private static final int CAPTURED_HIT_POINTS = 0x12345678;
    /**
     * The unit type of a Scout, the owner of a neutral unit and the movement flag of a starting attack.
     */
    private static final int SCOUT_TYPE = 70;
    private static final int NEUTRAL_PLAYER = 11;
    private static final int STARTING_ATTACK_FLAG = 0x8;

    private static final @NotNull VarHandle WORDS = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final @NotNull VarHandle INTEGERS = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    @Test
    void larva_side_counts_share_resource_storage_as_signed_words() {
        Unit unit = UnitTestUtils.createUnit(UNIT_POOL_INDEX);

        for (int value : RESOURCE_WORD_SAMPLES) {
            unit.setResources(value);
            unit.setSecondLarvaSideCount(value);
            unit.setThirdLarvaSideCount(value);
            unit.setFourthLarvaSideCount(value);

            for (int side = 0; side < LARVA_SIDE_COUNT; side++) {
                Assertions.assertEquals((short) value, unit.getLarvaSideCount(side));
            }
        }

        UnitMemoryScenario.of(unit).writeMemoryByte(RESOURCES_OFFSET, RESOURCE_LOW_BYTE);

        int resources = WORD_MASK & ~BYTE_MASK | RESOURCE_LOW_BYTE;

        Assertions.assertEquals(resources, unit.getResources());
        Assertions.assertEquals((short) resources, unit.getLarvaSideCount(0));

        unit.setResources(OVERWIDE_RESOURCES);

        byte[] image = UnitMemoryScenario.of(unit).exportCaptureBytes();

        Assertions.assertEquals(OVERWIDE_RESOURCES & WORD_MASK, unit.getLarvaSideCount(0));
        Assertions.assertEquals(OVERWIDE_RESOURCES & WORD_MASK, Short.toUnsignedInt((short) WORDS.get(image, RESOURCES_OFFSET)));
        Assertions.assertEquals(-1, unit.getLarvaSideCount(1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> unit.getLarvaSideCount(-1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> unit.getLarvaSideCount(LARVA_SIDE_COUNT));
    }

    @Test
    void build_queue_rejects_indices_outside_five_slots_while_memory_reads_alias_it() {
        Unit unit = UnitTestUtils.createUnit(UNIT_POOL_INDEX);
        byte[] original = new byte[CLASSIC_UNIT_IMAGE_SIZE];

        for (int offset = 0; offset < original.length; offset++) {
            original[offset] = (byte) (offset * BYTE_PATTERN_MULTIPLIER);

            UnitMemoryScenario.of(unit).writeMemoryByte(offset, original[offset]);
        }

        for (int index = 0; index < BUILD_QUEUE_SLOT_COUNT; index++) {
            Assertions.assertEquals(Short.toUnsignedInt((short) WORDS.get(original, UnitMemoryScenario.BUILD_QUEUE_OFFSET + index * Short.BYTES)),
                                    unit.getBuildQueueUnitType(index));
        }

        for (int index = FIRST_ALIASED_QUEUE_INDEX; index <= LAST_ALIASED_QUEUE_INDEX; index++) {
            Assertions.assertEquals(Short.toUnsignedInt((short) WORDS.get(original, UnitMemoryScenario.BUILD_QUEUE_OFFSET + index * Short.BYTES)),
                                    UnitMemoryScenario.of(unit).readMemoryUnsignedWord(UnitMemoryScenario.BUILD_QUEUE_OFFSET + index * Short.BYTES));
        }

        unit.setThirdBuildQueueType(QUEUED_TYPE_WORD);

        Assertions.assertEquals(QUEUED_TYPE_WORD, unit.getBuildQueueUnitType(THIRD_BUILD_QUEUE_SLOT));

        for (int index : new int[]{-1, BUILD_QUEUE_SLOT_COUNT, LAST_ALIASED_QUEUE_INDEX + 1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> unit.getBuildQueueUnitType(index));
        }
    }

    @Test
    void aligned_and_unaligned_writes_keep_every_other_byte() {
        Unit unit = UnitTestUtils.createUnit(UNIT_POOL_INDEX);
        byte[] expected = new byte[CLASSIC_UNIT_IMAGE_SIZE];
        int value = CAPTURE_RANDOM_SEED;

        for (int offset = 0; offset < expected.length; offset++) {
            value            = value * CAPTURE_RANDOM_MULTIPLIER + CAPTURE_RANDOM_INCREMENT;
            expected[offset] = (byte) value;

            UnitMemoryScenario.of(unit).writeMemoryByte(offset, value);
        }

        Assertions.assertArrayEquals(expected, UnitMemoryScenario.of(unit).exportCaptureBytes());

        for (int width : new int[]{Byte.BYTES, Short.BYTES, Integer.BYTES}) {
            for (int offset = 0; offset <= expected.length - width; offset++) {
                value = value * CAPTURE_RANDOM_MULTIPLIER + CAPTURE_RANDOM_INCREMENT;

                switch (width) {
                    case Byte.BYTES -> {
                        expected[offset] = (byte) value;

                        UnitMemoryScenario.of(unit).writeMemoryByte(offset, value);
                    }
                    case Short.BYTES -> {
                        WORDS.set(expected, offset, (short) value);
                        UnitMemoryScenario.of(unit).writeMemoryWord(offset, value);
                    }
                    case Integer.BYTES -> {
                        INTEGERS.set(expected, offset, value);
                        UnitMemoryScenario.of(unit).writeMemoryInteger(offset, value);
                    }
                }

                Assertions.assertArrayEquals(expected,
                                             UnitMemoryScenario.of(unit).exportCaptureBytes(),
                                             "write at " + offset + " with width " + width);
            }
        }

        for (int offset = 0; offset < expected.length; offset++) {
            Assertions.assertEquals(Byte.toUnsignedInt(expected[offset]), UnitMemoryScenario.of(unit).readMemoryUnsignedByte(offset));

            if (offset <= expected.length - Short.BYTES) {
                Assertions.assertEquals(Short.toUnsignedInt((short) WORDS.get(expected, offset)),
                                        UnitMemoryScenario.of(unit).readMemoryUnsignedWord(offset));
            }

            if (offset <= expected.length - Integer.BYTES) {
                Assertions.assertEquals((int) INTEGERS.get(expected, offset), UnitMemoryScenario.of(unit).readMemoryInteger(offset));
            }
        }
    }

    @Test
    void partial_clears_keep_other_bytes_and_drop_cleared_references() {
        Unit unit = UnitTestUtils.createUnit(UNIT_POOL_INDEX);
        Unit target = UnitTestUtils.createUnit(TARGET_POOL_INDEX);

        unit.setParentUnit(target);
        unit.setMovementTargetUnit(target);
        unit.setOrderTargetUnit(target);
        unit.setSubunit(target);
        unit.setPathIndex(PATH_INDEX);

        byte[] expected = new byte[CLASSIC_UNIT_IMAGE_SIZE];

        for (int offset = 0; offset <= expected.length; offset++) {
            for (int requestedLength : CLEAR_LENGTHS) {
                for (int byteIndex = 0; byteIndex < expected.length; byteIndex++) {
                    expected[byteIndex] = (byte) (byteIndex * PARTIAL_CLEAR_PATTERN_MULTIPLIER + offset);

                    UnitMemoryScenario.of(unit).writeMemoryByte(byteIndex, expected[byteIndex]);
                }

                int length = Math.min(requestedLength, expected.length - offset);

                Arrays.fill(expected, offset, offset + length, (byte) 0);
                UnitMemoryScenario.of(unit).clearMemoryBytes(offset, length);

                Assertions.assertArrayEquals(expected,
                                             UnitMemoryScenario.of(unit).exportCaptureBytes(),
                                             "clear at " + offset + " with length " + length);
            }
        }

        Assertions.assertNull(unit.getParentUnit());
        Assertions.assertNull(unit.getMovementTargetUnit());
        Assertions.assertNull(unit.getOrderTargetUnit());
        Assertions.assertNull(unit.getSubunit());
        Assertions.assertEquals(-1, unit.getPathIndex());
    }

    @Test
    void unresolved_capture_addresses_do_not_create_domain_relationships() {
        Unit unit = UnitTestUtils.createUnit(UNIT_POOL_INDEX);
        Unit target = UnitTestUtils.createUnit(TARGET_POOL_INDEX);
        UnitMemoryScenario capture = UnitMemoryScenario.of(unit);

        for (int offset : REFERENCE_OFFSETS) {
            capture.writeMemoryInteger(offset, DWORD_PATTERN);
        }

        Assertions.assertNull(unit.getSpriteOrNull());
        Assertions.assertNull(unit.getMovementTargetUnit());
        Assertions.assertNull(unit.getOrderTargetUnit());
        Assertions.assertNull(unit.getSubunit());
        Assertions.assertNull(unit.getFirstOrder());
        Assertions.assertNull(unit.getLastOrder());
        Assertions.assertFalse(unit.hasAutomaticTarget());
        Assertions.assertFalse(unit.hasCurrentBuildUnit());
        Assertions.assertFalse(unit.hasCarriedPowerup());
        Assertions.assertEquals(-1, unit.getPathIndex());

        unit.setMovementTargetUnit(target);
        unit.setOrderTargetUnit(target);
        unit.setAutomaticTargetUnit(target);
        unit.setCurrentBuildUnit(target);

        Assertions.assertSame(target, unit.getMovementTargetUnit());
        Assertions.assertTrue(unit.hasOrderTarget());
        Assertions.assertTrue(unit.hasAutomaticTarget());
        Assertions.assertTrue(unit.hasCurrentBuildUnit());
        Assertions.assertEquals(DWORD_PATTERN, capture.readMemoryInteger(MOVEMENT_TARGET_REFERENCE_OFFSET));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> capture.writeMemoryWord(LAST_IMAGE_OFFSET, 0));
        Assertions.assertSame(target, unit.getMovementTargetUnit());

        capture.writeMemoryByte(MOVEMENT_TARGET_REFERENCE_LAST_BYTE_OFFSET, REFERENCE_HIGH_BIT_BYTE);

        Assertions.assertNull(unit.getMovementTargetUnit());
        Assertions.assertEquals(REFERENCE_HIGH_BIT_BYTE << HIGH_BYTE_SHIFT, capture.readMemoryInteger(MOVEMENT_TARGET_REFERENCE_HIGH_OFFSET));
    }

    @Test
    void spider_mine_count_is_separate_from_unresolved_worker_capture_pointers() {
        Unit unit = UnitTestUtils.createUnit(UNIT_POOL_INDEX);
        UnitMemoryScenario capture = UnitMemoryScenario.of(unit);

        capture.writeMemoryInteger(CARRIED_POWERUP_REFERENCE_OFFSET, DWORD_PATTERN);
        capture.writeMemoryInteger(CARRIED_POWERUP_SECOND_DWORD_OFFSET, SECOND_DWORD_PATTERN);

        Assertions.assertFalse(unit.hasCarriedPowerup());
        Assertions.assertEquals(0, unit.getSpiderMineCount());

        capture.writeMemoryWord(UnitMemoryScenario.UNIT_TYPE_OFFSET, ScrUnitIds.TERRAN_VULTURE);

        Assertions.assertEquals(DWORD_PATTERN & BYTE_MASK, unit.getSpiderMineCount());

        unit.setSpiderMineCount(SPIDER_MINE_COUNT);

        Assertions.assertEquals(DWORD_PATTERN & ~BYTE_MASK | SPIDER_MINE_COUNT, capture.readMemoryInteger(CARRIED_POWERUP_REFERENCE_OFFSET));
        Assertions.assertEquals(SECOND_DWORD_PATTERN, capture.readMemoryInteger(CARRIED_POWERUP_SECOND_DWORD_OFFSET));
        Assertions.assertFalse(unit.hasCarriedPowerup());

        unit.setCarriedPowerup(UnitTestUtils.createUnit(TARGET_POOL_INDEX));

        Assertions.assertTrue(unit.hasCarriedPowerup());

        unit.resetRoleState();

        Assertions.assertFalse(unit.hasCarriedPowerup());
        Assertions.assertEquals(0, unit.getSpiderMineCount());
        Assertions.assertEquals(SECOND_DWORD_PATTERN, capture.readMemoryInteger(CARRIED_POWERUP_SECOND_DWORD_OFFSET));

        unit.setSpiderMineCount(OVERWIDE_SPIDER_MINE_COUNT);

        Assertions.assertEquals(OVERWIDE_SPIDER_MINE_COUNT & BYTE_MASK, capture.readMemoryUnsignedByte(CARRIED_POWERUP_REFERENCE_OFFSET));
    }

    @Test
    void memory_aliases_and_domain_fields_share_one_storage() {
        Unit unit = UnitTestUtils.createUnit(UNIT_POOL_INDEX);

        UnitMemoryScenario.of(unit).writeMemoryInteger(POSITION_OFFSET, PACKED_POSITION);

        Assertions.assertEquals((short) PACKED_POSITION, unit.getPositionX());
        Assertions.assertEquals((short) (PACKED_POSITION >>> Short.SIZE), unit.getPositionY());

        UnitMemoryScenario.of(unit).writeMemoryByte(POSITION_HORIZONTAL_HIGH_BYTE_OFFSET, POSITION_HORIZONTAL_HIGH_BYTE);

        Assertions.assertEquals(POSITION_HORIZONTAL_HIGH_BYTE << Byte.SIZE | PACKED_POSITION & BYTE_MASK, unit.getPositionX());

        UnitMemoryScenario.of(unit).writeMemoryWord(UnitMemoryScenario.PRIMARY_ORDER_OFFSET, PRIMARY_ORDER_WORD);
        UnitMemoryScenario.of(unit).writeMemoryByte(ORDER_SIGNAL_OFFSET, ORDER_SIGNAL);

        Assertions.assertEquals(ORDER_SIGNAL << Short.SIZE | PRIMARY_ORDER_WORD,
                                UnitMemoryScenario.of(unit).readMemoryInteger(UnitMemoryScenario.PRIMARY_ORDER_OFFSET));
        Assertions.assertEquals(ORDER_SIGNAL, unit.getOrderSignal());

        unit.setResources(DWORD_PATTERN);

        Assertions.assertEquals(DWORD_PATTERN & WORD_MASK, UnitMemoryScenario.of(unit).readMemoryUnsignedWord(RESOURCES_OFFSET));

        UnitMemoryScenario.of(unit).writeMemoryInteger(RESOURCES_PRECEDING_BYTE_OFFSET, OVERLAPPING_DWORD_PATTERN);

        Assertions.assertEquals(OVERLAPPING_DWORD_PATTERN >>> Byte.SIZE & WORD_MASK, unit.getResources());

        unit.setHitPoints(Integer.MIN_VALUE);

        Assertions.assertEquals(Integer.MIN_VALUE, UnitMemoryScenario.of(unit).readMemoryInteger(HIT_POINTS_OFFSET));

        UnitMemoryScenario.of(unit).writeMemoryByte(HIT_POINTS_HIGH_BYTE_OFFSET, BYTE_MASK);

        Assertions.assertEquals(BYTE_MASK << HIGH_BYTE_SHIFT, unit.getHitPoints());

        unit.setStatus(-1);
        unit.setSpeedIncreaseByte(OVERWIDE_SPEED_INCREASE);
        unit.setSpeedDecreaseByte(OVERWIDE_SPEED_DECREASE);
        unit.setMovementDirection(OVERWIDE_MOVEMENT_DIRECTION);
        unit.setMovementFlags(OVERWIDE_MOVEMENT_FLAGS);
        unit.setTopSpeed(TOP_SPEED);
        unit.setCurrentSpeed(CURRENT_SPEED);
        unit.setNextSpeed(NEXT_SPEED);
        unit.setVelocityX(VELOCITY_X);
        unit.setVelocityY(VELOCITY_Y);

        int speedModifiers = (OVERWIDE_SPEED_DECREASE & BYTE_MASK) << Byte.SIZE | OVERWIDE_SPEED_INCREASE & BYTE_MASK;

        Assertions.assertEquals(-1, UnitMemoryScenario.of(unit).readMemoryInteger(UnitMemoryScenario.STATUS_OFFSET));
        Assertions.assertEquals(speedModifiers, UnitMemoryScenario.of(unit).readMemoryUnsignedWord(SPEED_MODIFIERS_OFFSET));
        Assertions.assertEquals(OVERWIDE_MOVEMENT_DIRECTION & BYTE_MASK, unit.getMovementDirection());
        Assertions.assertEquals(OVERWIDE_MOVEMENT_FLAGS & BYTE_MASK, unit.getMovementFlags());

        UnitMemoryScenario.of(unit).writeMemoryInteger(TOP_SPEED_LAST_BYTE_OFFSET, DWORD_PATTERN);

        Assertions.assertEquals(DWORD_PATTERN >>> Byte.SIZE, unit.getCurrentSpeed());
        Assertions.assertEquals((DWORD_PATTERN & BYTE_MASK) << HIGH_BYTE_SHIFT | TOP_SPEED, unit.getTopSpeed());

        UnitMemoryScenario.of(unit).clearMemoryBytes(CURRENT_SPEED_SECOND_BYTE_OFFSET, Short.BYTES);

        Assertions.assertEquals(DWORD_PATTERN >>> Byte.SIZE & BYTE_MASK, unit.getCurrentSpeed());
    }

    @Test
    void scalar_setters_narrow_to_their_field_widths() {
        Unit unit = UnitTestUtils.createUnit(UNIT_POOL_INDEX);

        unit.setUnitId(OVERWIDE_UNIT_TYPE);
        unit.setOwnerIndex(OVERWIDE_OWNER);
        unit.setPositionX(OVERWIDE_POSITION_X);
        unit.setPositionY(OVERWIDE_POSITION_Y);

        Assertions.assertEquals(OVERWIDE_UNIT_TYPE & WORD_MASK, unit.getUnitId());
        Assertions.assertEquals(OVERWIDE_OWNER & BYTE_MASK, unit.getOwnerIndex());
        Assertions.assertEquals((short) OVERWIDE_POSITION_X, unit.getPositionX());
        Assertions.assertEquals((short) OVERWIDE_POSITION_Y, unit.getPositionY());

        unit.setResources(-1);
        unit.setHeading(NEGATIVE_HEADING);
        unit.setOrderSignal(NEGATIVE_ORDER_SIGNAL);

        Assertions.assertEquals(WORD_MASK, unit.getResources());
        Assertions.assertEquals(NEGATIVE_HEADING & BYTE_MASK, unit.getHeading());
        Assertions.assertEquals(NEGATIVE_ORDER_SIGNAL & BYTE_MASK, unit.getOrderSignal());
        Assertions.assertEquals(0, unit.getStatus());

        unit.setTopSpeed(SMALL_TOP_SPEED);

        Assertions.assertEquals(SMALL_TOP_SPEED, unit.getTopSpeed());
        Assertions.assertThrows(IllegalArgumentException.class, () -> UnitTestUtils.createUnit(0));
    }

    @Test
    void out_of_range_memory_access_fails_before_changing_any_byte() {
        Unit unit = UnitTestUtils.createUnit(UNIT_POOL_INDEX);

        for (int offset = 0; offset < CLASSIC_UNIT_IMAGE_SIZE; offset++) {
            UnitMemoryScenario.of(unit).writeMemoryByte(offset, offset * BOUNDS_PATTERN_MULTIPLIER);
        }

        byte[] expected = UnitMemoryScenario.of(unit).exportCaptureBytes();

        for (int offset : new int[]{Integer.MIN_VALUE, -1, CLASSIC_UNIT_IMAGE_SIZE, Integer.MAX_VALUE}) {
            Assertions.assertThrows(ArrayIndexOutOfBoundsException.class, () -> UnitMemoryScenario.of(unit).writeMemoryByte(offset, 0));
            Assertions.assertThrows(ArrayIndexOutOfBoundsException.class, () -> UnitMemoryScenario.of(unit).readMemoryUnsignedByte(offset));
        }

        for (int offset : new int[]{Integer.MIN_VALUE, -1, LAST_IMAGE_OFFSET, CLASSIC_UNIT_IMAGE_SIZE, Integer.MAX_VALUE}) {
            Assertions.assertThrows(IndexOutOfBoundsException.class, () -> UnitMemoryScenario.of(unit).writeMemoryWord(offset, 0));
            Assertions.assertThrows(IndexOutOfBoundsException.class, () -> UnitMemoryScenario.of(unit).readMemoryUnsignedWord(offset));
        }

        for (int offset : new int[]{Integer.MIN_VALUE, -1, FIRST_CROSSING_INTEGER_OFFSET, LAST_IMAGE_OFFSET, CLASSIC_UNIT_IMAGE_SIZE,
                                    Integer.MAX_VALUE}) {
            Assertions.assertThrows(IndexOutOfBoundsException.class, () -> UnitMemoryScenario.of(unit).writeMemoryInteger(offset, 0));
            Assertions.assertThrows(IndexOutOfBoundsException.class, () -> UnitMemoryScenario.of(unit).readMemoryInteger(offset));
        }

        Assertions.assertThrows(ArrayIndexOutOfBoundsException.class, () -> UnitMemoryScenario.of(unit).clearMemoryBytes(-1, CROSSING_CLEAR_LENGTH));
        Assertions.assertThrows(ArrayIndexOutOfBoundsException.class,
                                () -> UnitMemoryScenario.of(unit).clearMemoryBytes(LAST_IMAGE_OFFSET, Short.BYTES));
        Assertions.assertThrows(IllegalArgumentException.class, () -> UnitMemoryScenario.of(unit).clearMemoryBytes(INNER_OFFSET, -1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> UnitMemoryScenario.of(unit).clearMemoryBytes(Integer.MAX_VALUE, 1));
        Assertions.assertArrayEquals(expected, UnitMemoryScenario.of(unit).exportCaptureBytes());
    }

    @Test
    void out_of_range_memory_access_throws_the_jdk_array_exceptions() {
        Unit unit = UnitTestUtils.createUnit(UNIT_POOL_INDEX);
        byte[] original = new byte[CLASSIC_UNIT_IMAGE_SIZE];

        for (int offset : new int[]{Integer.MIN_VALUE, -1, CLASSIC_UNIT_IMAGE_SIZE, Integer.MAX_VALUE}) {
            RuntimeException expected = Assertions.assertThrows(RuntimeException.class, () -> original[offset] = 1);
            RuntimeException actual = Assertions.assertThrows(RuntimeException.class, () -> UnitMemoryScenario.of(unit).writeMemoryByte(offset, 1));

            Assertions.assertEquals(expected.getClass(), actual.getClass());
            Assertions.assertEquals(expected.getMessage(), actual.getMessage());

            expected = Assertions.assertThrows(RuntimeException.class, () -> Byte.toUnsignedInt(original[offset]));
            actual   = Assertions.assertThrows(RuntimeException.class, () -> UnitMemoryScenario.of(unit).readMemoryUnsignedByte(offset));

            Assertions.assertEquals(expected.getClass(), actual.getClass());
            Assertions.assertEquals(expected.getMessage(), actual.getMessage());
        }

        for (int offset : new int[]{Integer.MIN_VALUE, -1, LAST_IMAGE_OFFSET, CLASSIC_UNIT_IMAGE_SIZE, Integer.MAX_VALUE}) {
            RuntimeException expected = Assertions.assertThrows(RuntimeException.class, () -> WORDS.set(original, offset, (short) 1));
            RuntimeException actual = Assertions.assertThrows(RuntimeException.class, () -> UnitMemoryScenario.of(unit).writeMemoryWord(offset, 1));

            Assertions.assertEquals(expected.getClass(), actual.getClass());
            Assertions.assertEquals(expected.getMessage(), actual.getMessage());
        }

        for (int offset : new int[]{Integer.MIN_VALUE, -1, FIRST_CROSSING_INTEGER_OFFSET, CLASSIC_UNIT_IMAGE_SIZE, Integer.MAX_VALUE}) {
            RuntimeException expected = Assertions.assertThrows(RuntimeException.class, () -> INTEGERS.set(original, offset, 1));
            RuntimeException actual = Assertions.assertThrows(RuntimeException.class,
                                                              () -> UnitMemoryScenario.of(unit).writeMemoryInteger(offset, 1));

            Assertions.assertEquals(expected.getClass(), actual.getClass());
            Assertions.assertEquals(expected.getMessage(), actual.getMessage());
        }

        int[][] ranges = {
                {-1, CROSSING_CLEAR_LENGTH}, {LAST_IMAGE_OFFSET, Short.BYTES}, {INNER_OFFSET, -1}, {Integer.MAX_VALUE, 1}, {0, Integer.MIN_VALUE}
        };

        for (int[] range : ranges) {
            RuntimeException expected = Assertions.assertThrows(RuntimeException.class,
                                                                () -> Arrays.fill(original, range[0], range[0] + range[1], (byte) 0));
            RuntimeException actual = Assertions.assertThrows(RuntimeException.class,
                                                              () -> UnitMemoryScenario.of(unit).clearMemoryBytes(range[0], range[1]));

            Assertions.assertEquals(expected.getClass(), actual.getClass());
            Assertions.assertEquals(expected.getMessage(), actual.getMessage());
        }
    }

    @Test
    void little_endian_width_aliases_keep_adjacent_bytes() {
        Unit unit = UnitTestUtils.createUnit(SNAPSHOT_POOL_INDEX);

        UnitMemoryScenario.of(unit).writeMemoryInteger(BUILD_QUEUE_INDEX_OFFSET, LITTLE_ENDIAN_DWORD);

        Assertions.assertEquals(LITTLE_ENDIAN_DWORD & BYTE_MASK, UnitMemoryScenario.of(unit).readMemoryUnsignedByte(BUILD_QUEUE_INDEX_OFFSET));
        Assertions.assertEquals(LITTLE_ENDIAN_DWORD >>> Byte.SIZE & BYTE_MASK,
                                UnitMemoryScenario.of(unit).readMemoryUnsignedByte(BUILD_QUEUE_INDEX_HIGH_BYTE_OFFSET));
        Assertions.assertEquals(LITTLE_ENDIAN_DWORD >>> Short.SIZE, UnitMemoryScenario.of(unit).readMemoryUnsignedWord(SECONDARY_ORDER_OFFSET));

        UnitMemoryScenario.of(unit).writeMemoryWord(BUILD_QUEUE_INDEX_HIGH_BYTE_OFFSET, OVERWIDE_WORD);

        Assertions.assertEquals(DWORD_AFTER_OVERWIDE_WORD_WRITE, UnitMemoryScenario.of(unit).readMemoryInteger(BUILD_QUEUE_INDEX_OFFSET));

        UnitMemoryScenario.of(unit).writeMemoryByte(DAMAGE_OVERLAY_COUNT_OFFSET, OVERWIDE_BYTE);

        Assertions.assertEquals(DWORD_AFTER_OVERWIDE_BYTE_WRITE, UnitMemoryScenario.of(unit).readMemoryInteger(BUILD_QUEUE_INDEX_OFFSET));

        UnitMemoryScenario.of(unit).clearMemoryBytes(BUILD_QUEUE_INDEX_HIGH_BYTE_OFFSET, Short.BYTES);

        Assertions.assertEquals(DWORD_AFTER_MIDDLE_CLEAR, UnitMemoryScenario.of(unit).readMemoryInteger(BUILD_QUEUE_INDEX_OFFSET));
        Assertions.assertEquals(SNAPSHOT_POOL_INDEX, unit.getIndex());
        Assertions.assertNull(unit.getSpriteOrNull());
        Assertions.assertNull(unit.getSubunit());

        byte[] snapshot = UnitMemoryScenario.of(unit).exportMemoryBytes();

        snapshot[BUILD_QUEUE_INDEX_OFFSET] = 0;

        Assertions.assertEquals(LITTLE_ENDIAN_DWORD & BYTE_MASK, UnitMemoryScenario.of(unit).readMemoryUnsignedByte(BUILD_QUEUE_INDEX_OFFSET));
    }

    @Test
    void movement_fields_alias_retained_storage_and_keep_velocity_at_equal_speed() {
        Unit unit = UnitTestUtils.createUnit(UNIT_POOL_INDEX);

        UnitMemoryScenario.of(unit).writeMemoryWord(UnitMemoryScenario.UNIT_TYPE_OFFSET, ScrUnitIds.PROTOSS_SCOUT);
        UnitMemoryScenario.of(unit).writeMemoryByte(UnitMemoryScenario.OWNER_OFFSET, NEUTRAL_PLAYER);
        UnitMemoryScenario.of(unit).writeMemoryWord(POSITION_OFFSET, WORD_MASK);
        UnitMemoryScenario.of(unit).writeMemoryWord(POSITION_VERTICAL_OFFSET, Short.MIN_VALUE);
        unit.setStatus(UnitStatusFlags.SPEED_UPGRADE);
        unit.setSpeedIncreaseByte(0);
        unit.setSpeedDecreaseByte(0);
        unit.setMovementDirection(FlingyMotionUtils.DIRECTION_QUARTER_TURN);
        unit.setMovementFlags(BYTE_MASK);
        unit.setTopSpeed(SCOUT_TOP_SPEED);
        unit.setCurrentSpeed(SCOUT_CURRENT_SPEED);
        unit.setNextSpeed(0);
        unit.setVelocityX(SCOUT_VELOCITY_X);
        unit.setVelocityY(SCOUT_VELOCITY_Y);

        ScrUnitTypeView traitTypes = new ScrUnitTypeView(column -> new ArrayDatColumn(new int[0]),
                                                         ScrUnitTraits.createColumns(),
                                                         UnitsDat.REQUIREMENT_INDEX,
                                                         (column, index) -> 0);

        unit.applyIscriptMovementSpeed(unit.calculateModifiedMovementSpeed(traitTypes, ISCRIPT_MOVEMENT_SPEED));

        Assertions.assertEquals(SCOUT_TYPE, unit.getUnitId());
        Assertions.assertEquals(NEUTRAL_PLAYER, unit.getOwnerIndex());
        Assertions.assertEquals(-1, unit.getPositionX());
        Assertions.assertEquals(Short.MIN_VALUE, unit.getPositionY());
        Assertions.assertEquals(SCOUT_CURRENT_SPEED, UnitMemoryScenario.of(unit).readMemoryInteger(NEXT_SPEED_OFFSET));
        Assertions.assertEquals(SCOUT_VELOCITY_X, UnitMemoryScenario.of(unit).readMemoryInteger(VELOCITY_HORIZONTAL_OFFSET));
        Assertions.assertEquals(SCOUT_VELOCITY_Y, UnitMemoryScenario.of(unit).readMemoryInteger(VELOCITY_VERTICAL_OFFSET));

        unit.clearRepeatAttackMovementFlag();

        Assertions.assertEquals(BYTE_MASK & ~STARTING_ATTACK_FLAG, UnitMemoryScenario.of(unit).readMemoryUnsignedByte(MOVEMENT_FLAGS_OFFSET));

        unit.setTopSpeed(-1);

        Assertions.assertEquals(-1, UnitMemoryScenario.of(unit).readMemoryInteger(TOP_SPEED_OFFSET));
    }

    @Test
    void capture_target_remains_the_same_slot_through_recycling_and_clearing() {
        UnitPool pool = new UnitPool(POOL_CAPACITY);

        pool.clear();

        Unit unit = pool.getUnit(UNIT_POOL_INDEX);
        UnitMemoryScenario capture = UnitMemoryScenario.of(unit);

        pool.markLoaded(UNIT_POOL_INDEX, 0, 0, 0, 0, 0);
        pool.rebuildFreeList();
        unit.setHitPoints(CAPTURED_HIT_POINTS);
        pool.recycleFinalized(UNIT_POOL_INDEX);

        Assertions.assertFalse(pool.isAllocated(UNIT_POOL_INDEX));
        Assertions.assertSame(unit, pool.getUnit(UNIT_POOL_INDEX));
        Assertions.assertSame(capture, UnitMemoryScenario.of(pool.getUnit(UNIT_POOL_INDEX)));
        Assertions.assertEquals(CAPTURED_HIT_POINTS, capture.readMemoryInteger(HIT_POINTS_OFFSET));

        pool.clear();

        Assertions.assertSame(unit, pool.getUnit(UNIT_POOL_INDEX));
        Assertions.assertSame(capture, UnitMemoryScenario.of(pool.getUnit(UNIT_POOL_INDEX)));
        Assertions.assertEquals(0, capture.readMemoryInteger(HIT_POINTS_OFFSET));
    }
}
