package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitTraits;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTraitFlags;
import org.jetbrains.annotations.NotNull;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Converts the original {@code 488}-byte scalar layout to the unit's fields. Reference fields are read and written as the unit's retained
 * references.
 * <p>
 * The offsets are the native classic unit memory that EUD triggers read and write; the layout is a contract.
 */
final class UnitMemoryCheck {
    static final int SIZE = 488;
    static final int UNMAPPED_SIZE;

    // Invalid accesses use the same JDK operation and length for its exception contract.
    private static final byte @NotNull [] BOUNDS_IMAGE = new byte[SIZE];
    private static final @NotNull VarHandle WORDS = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final @NotNull VarHandle INTEGERS = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final int @NotNull [] FIELD_START = new int[SIZE];
    private static final int @NotNull [] FIELD_WIDTH = new int[SIZE];
    private static final int @NotNull [] REFERENCE_START = new int[SIZE];
    private static final int @NotNull [] UNMAPPED_INDEX = new int[SIZE];
    private static final int REFERENCE_WIDTH = Long.BYTES;
    private static final int BYTE_MASK = 0xFF;
    private static final long REFERENCE_BYTE_MASK = 0xFFL;
    private static final int @NotNull [] UNIT_TRAITS = ScrUnitTraits.createValues(ScrUnitTypeView.TraitColumn.TRAITS);

    static {
        int[] starts = {
                0x10, 0x20, 0x22, 0x30, 0x32, 0x34, 0x36, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3F, 0x40,
                0x42, 0x44, 0x48, 0x4C, 0x50, 0x54, 0x58, 0x5C, 0x60, 0x62, 0x63, 0x68, 0x69, 0x6A,
                0x6B, 0x6C, 0x70, 0x78, 0x7A, 0x88, 0x8C, 0xC8, 0xC9, 0xD4, 0xD5, 0xD7, 0xD8, 0xDA,
                0xDB, 0xDC, 0xDE, 0xE0, 0xE2, 0xE4, 0xE6, 0xE8, 0xEA, 0xEB, 0xEC, 0xEE, 0xF0, 0xF2,
                0xF4, 0xF6, 0xF8, 0xFA, 0xFC, 0xFE, 0x100, 0x102, 0x114, 0x115, 0x116, 0x118, 0x128, 0x12A,
                0x12C, 0x12E, 0x138, 0x140, 0x144, 0x145, 0x146, 0x147, 0x148, 0x190, 0x191, 0x193, 0x194, 0x196,
                0x198, 0x19A, 0x19C, 0x19E, 0x1A0, 0x1A1, 0x1A2, 0x1A3, 0x1A4, 0x1A5, 0x1A6, 0x1A7, 0x1B1, 0x1B2,
                0x1B3, 0x1B4, 0x1B6, 0x1B7, 0x1B8, 0x1B9, 0x1BA, 0x1BB, 0x1BC, 0x1BD, 0x1BE, 0x1BF, 0x1C2, 0x1D0,
                0x1D2, 0x1D4, 0x1D8, 0x1DC, 0x1E0, 0x1E4, 0x1E5, 0x1E6, 0x1E7
        };
        int[] widths = {
                4, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 2, 1, 2,
                2, 4, 4, 4, 4, 4, 4, 4, 2, 1, 1, 1, 1, 1,
                1, 2, 4, 2, 2, 4, 2, 1, 1, 1, 1, 1, 2, 1,
                1, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2,
                2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 2, 2,
                2, 2, 1, 4, 1, 1, 1, 1, 4, 1, 1, 1, 2, 2,
                2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2,
                2, 4, 4, 4, 4, 1, 1, 1, 1
        };

        Arrays.fill(REFERENCE_START, -1);

        for (int start : new int[]{0x18, 0x28, 0x80, 0xA0, 0xA8, 0xB0, 0xB8, 0xC0, 0x108, 0x158, 0x188, 0x1C8}) {
            Arrays.fill(REFERENCE_START, start, start + REFERENCE_WIDTH, start);
        }

        Arrays.fill(FIELD_START, -1);
        Arrays.fill(UNMAPPED_INDEX, -1);

        for (int fieldIndex = 0; fieldIndex < starts.length; fieldIndex++) {
            int start = starts[fieldIndex];
            int width = widths[fieldIndex];

            FIELD_WIDTH[start] = width;

            for (int byteIndex = start; byteIndex < start + width; byteIndex++) {
                FIELD_START[byteIndex] = start;
            }
        }

        int count = 0;

        for (int offset = 0; offset < SIZE; offset++) {
            if (FIELD_START[offset] < 0 && REFERENCE_START[offset] < 0) {
                UNMAPPED_INDEX[offset] = count++;
            }
        }

        UNMAPPED_SIZE = count;
    }

    /**
     * Tests the Vulture trait with the trait values of the installed content, for the captures of the type views' games.
     *
     * @param unitType the unit type
     * @return {@code true} if the type is a Vulture type, {@code false} otherwise
     */
    static boolean isVulture(int unitType) {
        return unitType >= 0 && unitType < UNIT_TRAITS.length && (UNIT_TRAITS[unitType] & UnitTraitFlags.VULTURE) != 0;
    }

    /**
     * Returns the typed field width at the supplied unit-memory offset.
     *
     * @param offset the byte offset in the unit image
     * @return the field width, or {@code 0} if no typed field starts at the offset
     */
    static int getFieldWidth(int offset) {
        return FIELD_WIDTH[offset];
    }

    /**
     * Reads a little-endian value from typed fields, retained references, and opaque bytes.
     *
     * @param unit     the unit that owns mapped domain fields
     * @param unmapped the retained opaque bytes
     * @param offset   the byte offset in the unit image
     * @param width    the number of bytes to read
     * @return the reconstructed value
     */
    static int read(@NotNull Unit unit, byte @NotNull [] unmapped, int offset, int width) {
        if (offset < 0 || offset > SIZE - width) {
            return switch (width) {
                case Byte.BYTES -> Byte.toUnsignedInt(BOUNDS_IMAGE[offset]);
                case Short.BYTES -> Short.toUnsignedInt((short) WORDS.get(BOUNDS_IMAGE, offset));
                default -> (int) INTEGERS.get(BOUNDS_IMAGE, offset);
            };
        }

        if (FIELD_WIDTH[offset] == width) {
            int value = readField(unit, offset);

            return width == Integer.BYTES ? value : value & ((1 << (width * Byte.SIZE)) - 1);
        }

        int value = 0;

        for (int byteIndex = 0; byteIndex < width; byteIndex++) {
            int address = offset + byteIndex;
            int start = FIELD_START[address];
            int reference = REFERENCE_START[address];
            int component = reference >= 0
                            ? (int) (UnitMemoryScenario.of(unit).readReference(reference) >>> ((address - reference) * Byte.SIZE))
                            : start < 0 ? unmapped[UNMAPPED_INDEX[address]] : readField(unit, start) >>> ((address - start) * Byte.SIZE);

            value |= (component & BYTE_MASK) << (byteIndex * Byte.SIZE);
        }

        return value;
    }

    /**
     * Writes a little-endian value through mapped fields and retained image components.
     *
     * @param unit     the unit that owns mapped domain fields
     * @param unmapped the retained opaque bytes
     * @param offset   the byte offset in the unit image
     * @param width    the number of bytes to write
     * @param value    the encoded value
     */
    static void write(@NotNull Unit unit, byte @NotNull [] unmapped, int offset, int width, int value) {
        if (offset < 0 || offset > SIZE - width) {
            switch (width) {
                case Byte.BYTES -> BOUNDS_IMAGE[offset] = (byte) value;
                case Short.BYTES -> WORDS.set(BOUNDS_IMAGE, offset, (short) value);
                default -> INTEGERS.set(BOUNDS_IMAGE, offset, value);
            }

            return;
        }

        if (FIELD_WIDTH[offset] == width) {
            writeField(unit, offset, width == Integer.BYTES ? value : value & ((1 << (width * Byte.SIZE)) - 1));

            return;
        }

        for (int byteIndex = 0; byteIndex < width; byteIndex++) {
            int address = offset + byteIndex;
            int start = FIELD_START[address];
            int component = (value >>> (byteIndex * Byte.SIZE)) & BYTE_MASK;
            int reference = REFERENCE_START[address];

            if (reference >= 0) {
                int shift = (address - reference) * Byte.SIZE;
                long merged = UnitMemoryScenario.of(unit).readReference(reference) & ~(REFERENCE_BYTE_MASK << shift) | (long) component << shift;

                UnitMemoryScenario.of(unit).writeReference(reference, merged);
            } else if (start < 0) {
                unmapped[UNMAPPED_INDEX[address]] = (byte) component;
            } else {
                int shift = (address - start) * Byte.SIZE;
                int merged = (readField(unit, start) & ~(BYTE_MASK << shift)) | (component << shift);

                writeField(unit, start, merged);
            }
        }
    }

    /**
     * Clears a unit-image range through the same typed and opaque write paths.
     *
     * @param unit     the unit that owns mapped domain fields
     * @param unmapped the retained opaque bytes
     * @param offset   the first byte offset to clear
     * @param length   the number of bytes to clear
     */
    static void clear(@NotNull Unit unit, byte @NotNull [] unmapped, int offset, int length) {
        int end = offset + length;

        if (offset > end || offset < 0 || end > SIZE) {
            Arrays.fill(BOUNDS_IMAGE, offset, end, (byte) 0);

            return;
        }

        for (int address = offset; address < end; ) {
            int width = FIELD_WIDTH[address];

            if (width != 0 && address + width <= end) {
                writeField(unit, address, 0);

                address += width;
            } else {
                write(unit, unmapped, address, Byte.BYTES, 0);

                address++;
            }
        }
    }

    /**
     * Tests whether the offset belongs to a mapped scalar field.
     *
     * @param offset the byte offset in the unit image
     * @return {@code true} if a scalar field covers the offset, {@code false} otherwise
     */
    static boolean isScalarByte(int offset) {
        return FIELD_START[offset] >= 0;
    }

    /**
     * Tests whether a scalar field or retained reference covers the offset.
     *
     * @param offset the byte offset in the unit image
     * @return {@code true} if the offset belongs to a mapped domain value, {@code false} otherwise
     */
    static boolean isDomainByte(int offset) {
        return FIELD_START[offset] >= 0 || REFERENCE_START[offset] >= 0;
    }

    /**
     * Creates the full unit image from mapped values and retained opaque bytes.
     *
     * @param unit     the unit that owns mapped domain fields
     * @param unmapped the retained opaque bytes
     * @return the reconstructed unit image
     */
    static byte @NotNull [] createSnapshot(@NotNull Unit unit, byte @NotNull [] unmapped) {
        byte[] result = new byte[SIZE];

        for (int offset = 0; offset < SIZE; offset++) {
            int start = FIELD_START[offset];
            int reference = REFERENCE_START[offset];

            result[offset] = reference >= 0
                             ? (byte) (UnitMemoryScenario.of(unit).readReference(reference) >>> ((offset - reference) * Byte.SIZE))
                             : start < 0
                               ? unmapped[UNMAPPED_INDEX[offset]]
                               : (byte) (readField(unit, start) >>> ((offset - start) * Byte.SIZE));
        }

        return result;
    }

    /**
     * Reads the authoritative unit field that starts at the supplied offset.
     *
     * @param unit   the unit that owns the field
     * @param offset the mapped field start offset
     * @return the field value
     * @throws IllegalArgumentException if no mapped field starts at the offset
     */
    static int readField(@NotNull Unit unit, int offset) {
        return switch (offset) {
            case 0x10 -> unit.getHitPoints();
            case 0x20 -> unit.getMovementDestinationX();
            case 0x22 -> unit.getMovementDestinationY();
            case 0x30 -> unit.getMovementWaypointX();
            case 0x32 -> unit.getMovementWaypointY();
            case 0x34 -> unit.getDirectionTargetX();
            case 0x36 -> unit.getDirectionTargetY();
            case 0x38 -> unit.getMovementFlags();
            case 0x39 -> unit.getHeading();
            case 0x3A -> unit.getTurnRate();
            case 0x3B -> unit.getTargetHeading();
            case 0x3C -> unit.getFlingyType();
            case 0x3F -> unit.getMovementControl();
            case 0x40 -> unit.getPositionX();
            case 0x42 -> unit.getPositionY();
            case 0x44 -> unit.getFixedPositionX();
            case 0x48 -> unit.getFixedPositionY();
            case 0x4C -> unit.getTopSpeed();
            case 0x50 -> unit.getCurrentSpeed();
            case 0x54 -> unit.getNextSpeed();
            case 0x58 -> unit.getVelocityX();
            case 0x5C -> unit.getVelocityY();
            case 0x60 -> unit.getAcceleration();
            case 0x62 -> unit.getMovementDirection();
            case 0x63 -> unit.getDesiredMovementDirection();
            case 0x68 -> unit.getOwnerIndex();
            case 0x69 -> unit.getPrimaryOrder();
            case 0x6A -> unit.getPrimaryOrderState();
            case 0x6B -> unit.getOrderSignal();
            case 0x6C -> unit.getOrderTargetUnitType();
            case 0x70 -> unit.getCooldownTimers();
            case 0x78 -> unit.getOrderTargetX();
            case 0x7A -> unit.getOrderTargetY();
            case 0x88 -> unit.getShieldPoints();
            case 0x8C -> unit.getUnitId();
            case 0xC9 -> unit.getPrimaryOrderCountdown();
            case 0xD5 -> unit.getVisibilityUpdateCountdown();
            case 0xDB -> unit.getMovementState();
            case 0xDC -> unit.getFirstBuildQueueType();
            case 0xDE -> unit.getSecondBuildQueueType();
            case 0xE0 -> unit.getThirdBuildQueueType();
            case 0xE2 -> unit.getFourthBuildQueueType();
            case 0xE4 -> unit.getFifthBuildQueueType();
            case 0xE6 -> unit.getEnergy();
            case 0xE8 -> unit.getBuildQueueIndex();
            case 0xEA -> unit.getSecondaryOrder();
            case 0xEB -> unit.getDamageOverlayCount();
            case 0xEC -> unit.getConstructionHitPointIncrement();
            case 0xEE -> unit.getConstructionShieldIncrement();
            case 0xF0 -> unit.getRemainingBuildTime();
            case 0x114 -> unit.getResearchType();
            case 0x115 -> unit.getUpgradeType();
            case 0x116 -> unit.getLarvaSpawnCountdown();
            case 0x128 -> unit.getResources();
            case 0x12A -> unit.getSecondLarvaSideCount();
            case 0x12C -> unit.getThirdLarvaSideCount();
            case 0x12E -> unit.getFourthLarvaSideCount();
            case 0x138 -> unit.getResourceAreaIndex();
            case 0x140 -> unit.getStatus();
            case 0x146 -> unit.getSecondaryOrderState();
            case 0x147 -> unit.getMovementDestinationCountdown();
            case 0x190 -> unit.getPathRecalculationCountdown();
            case 0x191 -> unit.getPathCollisionFlags();
            case 0x194 -> unit.getTerrainCollisionLeft();
            case 0x196 -> unit.getTerrainCollisionTop();
            case 0x198 -> unit.getTerrainCollisionRight();
            case 0x19A -> unit.getTerrainCollisionBottom();
            case 0xF4 -> unit.getLoadedUnitHandle(0);
            case 0xF6 -> unit.getLoadedUnitHandle(1);
            case 0xF8 -> unit.getLoadedUnitHandle(2);
            case 0xFA -> unit.getLoadedUnitHandle(3);
            case 0xFC -> unit.getLoadedUnitHandle(4);
            case 0xFE -> unit.getLoadedUnitHandle(5);
            case 0x100 -> unit.getLoadedUnitHandle(6);
            case 0x102 -> unit.getLoadedUnitHandle(7);
            case 0x19E -> unit.getDefensiveMatrixHealth();
            case 0x1C2 -> unit.getLoadedUnitHandleExtensionBits();
            case 0x19C -> unit.getRemainingLifetime();
            case 0x1A1 -> unit.getSpeedIncreaseByte();
            case 0x1A2 -> unit.getSpeedDecreaseByte();
            case 0xC8 -> unit.getHighlightedOrderCount();
            case 0xD7 -> unit.getUserActionFlags();
            case 0xD8 -> unit.getCurrentButtonSet();
            case 0x193 -> unit.getHealingState();
            case 0x1A0 -> unit.getDefensiveMatrixTimer();
            case 0x1A3 -> unit.getLockdownTimer();
            case 0x1A4 -> unit.getIrradiateTimer();
            case 0x1A5 -> unit.getStasisTimer();
            case 0x1A6 -> unit.getPlagueTimer();
            case 0x1A7 -> unit.getStormTimer();
            case 0x1B4 -> unit.getMaelstromTimer();
            case 0x1B6 -> unit.getAcidSporeCount();
            case 0x1B7 -> unit.getAcidSporeTimer(0);
            case 0x1B8 -> unit.getAcidSporeTimer(1);
            case 0x1B9 -> unit.getAcidSporeTimer(2);
            case 0x1BA -> unit.getAcidSporeTimer(3);
            case 0x1BB -> unit.getAcidSporeTimer(4);
            case 0x1BC -> unit.getAcidSporeTimer(5);
            case 0x1BD -> unit.getAcidSporeTimer(6);
            case 0x1BE -> unit.getAcidSporeTimer(7);
            case 0x1BF -> unit.getAcidSporeTimer(8);
            case 0x118 -> unit.getCreepTimer();
            case 0x144 -> unit.getCarryingFlags();
            case 0xD4 -> unit.getLastAttackingPlayer();
            case 0x145 -> unit.getWireframeRandomizer();
            case 0xDA -> unit.getCloakCounter();
            case 0x1B1 -> unit.getParasiteFlags();
            case 0x1B3 -> unit.getBlindnessState();
            case 0xF2 -> unit.getPreviousVisibleHitPoints();
            case 0x148 -> unit.getDetectionFlags();
            case 0x1E4 -> unit.getRepulsionFlags();
            case 0x1E5 -> unit.getRepulsionDirection();
            case 0x1E6 -> unit.getRepulsionCellX();
            case 0x1E7 -> unit.getRepulsionCellY();
            case 0x1B2 -> unit.getStatusCycleElapsed();
            case 0x1D0 -> unit.getAirStrength();
            case 0x1D2 -> unit.getGroundStrength();
            case 0x1D4 -> unit.getMinimumHorizontalEdgeIndex();
            case 0x1D8 -> unit.getMaximumHorizontalEdgeIndex();
            case 0x1DC -> unit.getMinimumVerticalEdgeIndex();
            case 0x1E0 -> unit.getMaximumVerticalEdgeIndex();
            default -> throw Exceptions.newIllegalArgumentException("Unknown captured field: {0}", offset);
        };
    }

    /**
     * Writes the authoritative unit field that starts at the supplied offset.
     *
     * @param unit   the unit that owns the field
     * @param offset the mapped field start offset
     * @param value  the field value
     * @throws IllegalArgumentException if no mapped field starts at the offset
     */
    private static void writeField(@NotNull Unit unit, int offset, int value) {
        switch (offset) {
            case 0x10 -> unit.setHitPoints(value);
            case 0x20 -> unit.setMovementDestinationX(value);
            case 0x22 -> unit.setMovementDestinationY(value);
            case 0x30 -> unit.setMovementWaypointX(value);
            case 0x32 -> unit.setMovementWaypointY(value);
            case 0x34 -> unit.setDirectionTargetX(value);
            case 0x36 -> unit.setDirectionTargetY(value);
            case 0x38 -> unit.setMovementFlags(value);
            case 0x39 -> unit.setHeading(value);
            case 0x3A -> unit.setTurnRate(value);
            case 0x3B -> unit.setTargetHeading(value);
            case 0x3C -> unit.setFlingyType(value);
            case 0x3F -> unit.setMovementControl(value);
            case 0x40 -> unit.setPositionX(value);
            case 0x42 -> unit.setPositionY(value);
            case 0x44 -> unit.setFixedPositionX(value);
            case 0x48 -> unit.setFixedPositionY(value);
            case 0x4C -> unit.setTopSpeed(value);
            case 0x50 -> unit.setCurrentSpeed(value);
            case 0x54 -> unit.setNextSpeed(value);
            case 0x58 -> unit.setVelocityX(value);
            case 0x5C -> unit.setVelocityY(value);
            case 0x60 -> unit.setAcceleration(value);
            case 0x62 -> unit.setMovementDirection(value);
            case 0x63 -> unit.setDesiredMovementDirection(value);
            case 0x68 -> unit.setOwnerIndex(value);
            case 0x69 -> unit.setPrimaryOrder(value);
            case 0x6A -> unit.setPrimaryOrderState(value);
            case 0x6B -> unit.setOrderSignal(value);
            case 0x6C -> unit.setOrderTargetUnitType(value);
            case 0x70 -> unit.setCooldownTimers(value);
            case 0x78 -> unit.setOrderTargetX(value);
            case 0x7A -> unit.setOrderTargetY(value);
            case 0x88 -> unit.setShieldPoints(value);
            case 0x8C -> {
                unit.setUnitId(value);
                unit.setSpiderMineCount(isVulture(value) ? UnitMemoryScenario.of(unit).getCapturedMineCount() : 0);
            }
            case 0xC9 -> unit.setPrimaryOrderCountdown(value);
            case 0xD5 -> unit.setVisibilityUpdateCountdown(value);
            case 0xDB -> unit.setMovementState(value);
            case 0xDC -> unit.setFirstBuildQueueType(value);
            case 0xDE -> unit.setSecondBuildQueueType(value);
            case 0xE0 -> unit.setThirdBuildQueueType(value);
            case 0xE2 -> unit.setFourthBuildQueueType(value);
            case 0xE4 -> unit.setFifthBuildQueueType(value);
            case 0xE6 -> unit.setEnergy(value);
            case 0xE8 -> unit.setBuildQueueIndex(value);
            case 0xEA -> unit.setSecondaryOrder(value);
            case 0xEB -> unit.setDamageOverlayCount(value);
            case 0xEC -> unit.setConstructionHitPointIncrement(value);
            case 0xEE -> unit.setConstructionShieldIncrement(value);
            case 0xF0 -> unit.setRemainingBuildTime(value);
            case 0x114 -> unit.setResearchType(value);
            case 0x115 -> unit.setUpgradeType(value);
            case 0x116 -> unit.setLarvaSpawnCountdown(value);
            case 0x128 -> unit.setResources(value);
            case 0x12A -> unit.setSecondLarvaSideCount(value);
            case 0x12C -> unit.setThirdLarvaSideCount(value);
            case 0x12E -> unit.setFourthLarvaSideCount(value);
            case 0x138 -> unit.setResourceAreaIndex(value);
            case 0x140 -> unit.setStatus(value);
            case 0x146 -> unit.setSecondaryOrderState(value);
            case 0x147 -> unit.setMovementDestinationCountdown(value);
            case 0x190 -> unit.setPathRecalculationCountdown(value);
            case 0x191 -> unit.setPathCollisionFlags(value);
            case 0x194 -> unit.setTerrainCollisionLeft(value);
            case 0x196 -> unit.setTerrainCollisionTop(value);
            case 0x198 -> unit.setTerrainCollisionRight(value);
            case 0x19A -> unit.setTerrainCollisionBottom(value);
            case 0xF4 -> unit.setLoadedUnitHandle(0, value);
            case 0xF6 -> unit.setLoadedUnitHandle(1, value);
            case 0xF8 -> unit.setLoadedUnitHandle(2, value);
            case 0xFA -> unit.setLoadedUnitHandle(3, value);
            case 0xFC -> unit.setLoadedUnitHandle(4, value);
            case 0xFE -> unit.setLoadedUnitHandle(5, value);
            case 0x100 -> unit.setLoadedUnitHandle(6, value);
            case 0x102 -> unit.setLoadedUnitHandle(7, value);
            case 0x19E -> unit.setDefensiveMatrixHealth(value);
            case 0x1C2 -> unit.setLoadedUnitHandleExtensionBits(value);
            case 0x19C -> unit.setRemainingLifetime(value);
            case 0x1A1 -> unit.setSpeedIncreaseByte(value);
            case 0x1A2 -> unit.setSpeedDecreaseByte(value);
            case 0xC8 -> unit.setHighlightedOrderCount(value);
            case 0xD7 -> unit.setUserActionFlags(value);
            case 0xD8 -> unit.setCurrentButtonSet(value);
            case 0x193 -> unit.setHealingState(value);
            case 0x1A0 -> unit.setDefensiveMatrixTimer(value);
            case 0x1A3 -> unit.setLockdownTimer(value);
            case 0x1A4 -> unit.setIrradiateTimer(value);
            case 0x1A5 -> unit.setStasisTimer(value);
            case 0x1A6 -> unit.setPlagueTimer(value);
            case 0x1A7 -> unit.setStormTimer(value);
            case 0x1B4 -> unit.setMaelstromTimer(value);
            case 0x1B6 -> unit.setAcidSporeCount(value);
            case 0x1B7 -> unit.setAcidSporeTimer(0, value);
            case 0x1B8 -> unit.setAcidSporeTimer(1, value);
            case 0x1B9 -> unit.setAcidSporeTimer(2, value);
            case 0x1BA -> unit.setAcidSporeTimer(3, value);
            case 0x1BB -> unit.setAcidSporeTimer(4, value);
            case 0x1BC -> unit.setAcidSporeTimer(5, value);
            case 0x1BD -> unit.setAcidSporeTimer(6, value);
            case 0x1BE -> unit.setAcidSporeTimer(7, value);
            case 0x1BF -> unit.setAcidSporeTimer(8, value);
            case 0x118 -> unit.setCreepTimer(value);
            case 0x144 -> unit.setCarryingFlags(value);
            case 0xD4 -> unit.setLastAttackingPlayer(value);
            case 0x145 -> unit.setWireframeRandomizer(value);
            case 0xDA -> unit.setCloakCounter(value);
            case 0x1B1 -> unit.setParasiteFlags(value);
            case 0x1B3 -> unit.setBlindnessState(value);
            case 0xF2 -> unit.setPreviousVisibleHitPoints(value);
            case 0x148 -> unit.setDetectionFlags(value);
            case 0x1E4 -> unit.setRepulsionFlags(value);
            case 0x1E5 -> unit.setRepulsionDirection(value);
            case 0x1E6 -> unit.setRepulsionCellX(value);
            case 0x1E7 -> unit.setRepulsionCellY(value);
            case 0x1B2 -> unit.setStatusCycleElapsed(value);
            case 0x1D0 -> unit.setAirStrength(value);
            case 0x1D2 -> unit.setGroundStrength(value);
            case 0x1D4 -> unit.setMinimumHorizontalEdgeIndex(value);
            case 0x1D8 -> unit.setMaximumHorizontalEdgeIndex(value);
            case 0x1DC -> unit.setMinimumVerticalEdgeIndex(value);
            case 0x1E0 -> unit.setMaximumVerticalEdgeIndex(value);
            default -> throw Exceptions.newIllegalArgumentException("Unknown captured field: {0}", offset);
        }
    }

    private UnitMemoryCheck() {
    }
}
