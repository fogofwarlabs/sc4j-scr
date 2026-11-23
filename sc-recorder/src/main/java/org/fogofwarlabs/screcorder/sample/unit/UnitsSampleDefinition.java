package org.fogofwarlabs.screcorder.sample.unit;

import org.fogofwarlabs.screcorder.capture.MemoryReader;
import org.fogofwarlabs.screcorder.capture.sampling.CaptureContext;
import org.fogofwarlabs.screcorder.capture.sampling.Sampler;
import org.fogofwarlabs.screcorder.capture.starcraft.StarCraftLayout;
import org.fogofwarlabs.screcorder.exception.Exceptions;
import org.fogofwarlabs.screcorder.sample.SampleDefinition;
import org.fogofwarlabs.screcorder.sample.io.SampleReader;
import org.fogofwarlabs.screcorder.sample.io.SampleWriter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Defines units payload reading, writing and bulk memory sampling.
 */
public final class UnitsSampleDefinition extends SampleDefinition<UnitsSample> {
    private static final int SAMPLE_IDENTIFIER = 2;
    private static final int ROW_BYTES = 65;
    private static final int UNIT_POOL_METADATA_BYTES = 16;

    private static final int MAXIMUM_UNSIGNED_BYTE = 0xFF;
    private static final int MAXIMUM_UNSIGNED_SHORT = 0xFFFF;
    private static final long MAXIMUM_UNSIGNED_INTEGER = 0xFFFFFFFFL;
    private static final int MAXIMUM_UNION_KIND = UnitState.UNION_SPRITE;

    private static final int TYPE_SCV = 7;
    private static final int TYPE_DRONE = 41;
    private static final int TYPE_PROBE = 64;
    private static final int TYPE_NUCLEAR_SILO = 108;
    private static final int TYPE_NYDUS_CANAL = 134;
    private static final int TYPE_GHOST = 1;
    private static final int TYPE_SARAH_KERRIGAN = 16;
    private static final int TYPE_SAMIR_DURAN = 99;
    private static final int TYPE_ALEXEI_STUKOV = 100;
    private static final int TYPE_INFESTED_DURAN = 104;
    private static final int TYPE_PYLON = 156;

    private static final @NotNull SampleReader<UnitsSample> READER = UnitsSampleDefinition::read;
    private static final @NotNull SampleWriter<UnitsSample> WRITER = UnitsSampleDefinition::write;

    /**
     * Returns how the class-dependent unit union of a unit type is decoded.
     *
     * @param type the unit type
     * @return {@link UnitState#UNION_UNIT}, {@link UnitState#UNION_SPRITE} or {@link UnitState#UNION_WORDS}
     */
    public static int getUnionKind(int type) {
        return switch (type) {
            case TYPE_SCV, TYPE_DRONE, TYPE_PROBE, TYPE_NUCLEAR_SILO, TYPE_NYDUS_CANAL -> UnitState.UNION_UNIT;
            case TYPE_GHOST, TYPE_SARAH_KERRIGAN, TYPE_SAMIR_DURAN, TYPE_ALEXEI_STUKOV, TYPE_INFESTED_DURAN, TYPE_PYLON -> UnitState.UNION_SPRITE;
            default -> UnitState.UNION_WORDS;
        };
    }

    /**
     * Decodes the raw unit union QWORD by unit class.
     *
     * @param type        the unit type
     * @param raw         the raw union QWORD
     * @param poolAddress the unit pool address
     * @param poolSlots   the unit pool slot count
     * @return the logical slot of a union unit pointer ({@link UnitState#UNRESOLVED_UNIT} when it does not address a pool slot), the presence of a sprite pointer, or the raw words
     * @throws IOException if a pool slot cannot be resolved
     */
    static long decodeUnion(int type, long raw, long poolAddress, int poolSlots) throws IOException {
        return switch (getUnionKind(type)) {
            case UnitState.UNION_UNIT -> isUnitSlot(raw, poolAddress, poolSlots) ? resolveUnitIdentifier(raw, poolAddress, poolSlots)
                                                                                 : UnitState.UNRESOLVED_UNIT;
            case UnitState.UNION_SPRITE -> raw == 0 ? 0 : 1;
            default -> raw;
        };
    }

    private static boolean isUnitSlot(long pointer, long poolAddress, int poolSlots) {
        long offset = pointer - poolAddress;

        return pointer == 0 ||
               pointer >= poolAddress && offset % StarCraftLayout.UNIT_STRIDE == 0 && offset / StarCraftLayout.UNIT_STRIDE < poolSlots;
    }

    private static int resolveUnitIdentifier(long pointer, long poolAddress, int poolSlots) throws IOException {
        if (pointer == 0) {
            return 0;
        }

        long offset = pointer - poolAddress;

        if (pointer < poolAddress || offset < 0 || offset % StarCraftLayout.UNIT_STRIDE != 0 || offset / StarCraftLayout.UNIT_STRIDE >= poolSlots) {
            throw Exceptions.newIOException("Unit target cannot be resolved to a logical pool slot");
        }

        return (int) (offset / StarCraftLayout.UNIT_STRIDE) + 1;
    }


    private static @NotNull UnitsSample read(@NotNull ByteBuffer input) throws IOException {
        input.order(ByteOrder.LITTLE_ENDIAN);

        if (input.remaining() < Integer.BYTES) {
            throw Exceptions.newIOException("Missing units row count");
        }

        int rowCount = input.getInt();

        if (rowCount < 0 || rowCount > StarCraftLayout.MAXIMUM_UNITS || (long) rowCount * ROW_BYTES != input.remaining()) {
            throw Exceptions.newIOException("Invalid units row count or payload size: {0}", Integer.toUnsignedLong(rowCount));
        }

        List<UnitState> rows = new ArrayList<>(rowCount);
        int previousIdentifier = 0;

        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            UnitState row = new UnitState(Short.toUnsignedInt(input.getShort()), Byte.toUnsignedInt(input.get()),
                                          Short.toUnsignedInt(input.getShort()), Byte.toUnsignedInt(input.get()),
                                          Short.toUnsignedInt(input.getShort()), Short.toUnsignedInt(input.getShort()),
                                          input.getInt(), input.getInt(),
                                          input.getInt(), input.getInt(), Short.toUnsignedInt(input.getShort()),
                                          Byte.toUnsignedInt(input.get()), Byte.toUnsignedInt(input.get()), Byte.toUnsignedInt(input.get()),
                                          Short.toUnsignedInt(input.getShort()), input.getInt(), input.getInt(),
                                          Byte.toUnsignedInt(input.get()),
                                          Byte.toUnsignedInt(input.get()), Byte.toUnsignedInt(input.get()), Byte.toUnsignedInt(input.get()),
                                          input.getInt(), Integer.toUnsignedLong(input.getInt()),
                                          Byte.toUnsignedInt(input.get()), input.getLong(), Byte.toUnsignedInt(input.get()),
                                          Short.toUnsignedInt(input.getShort()));

            if (row.getIdentifier() <= previousIdentifier || row.getIdentifier() > StarCraftLayout.MAXIMUM_UNITS ||
                row.getOrderTargetIdentifier() > StarCraftLayout.MAXIMUM_UNITS) {
                throw Exceptions.newIOException("Invalid or noncanonical unit identifier");
            }

            rows.add(row);
            previousIdentifier = row.getIdentifier();
        }

        return new UnitsSample(rows);
    }

    private static void write(@NotNull ByteBuffer output, @NotNull UnitsSample sample) throws IOException {
        List<UnitState> rows = getCanonicalRows(sample);
        int encodedSize = Integer.BYTES + rows.size() * ROW_BYTES;

        if (output.remaining() < encodedSize || output.isReadOnly()) {
            throw Exceptions.newIOException("Insufficient writable units buffer: {0}", encodedSize);
        }

        output.order(ByteOrder.LITTLE_ENDIAN).putInt(rows.size());

        for (UnitState row : rows) {
            output.putShort((short) row.getIdentifier());
            output.put((byte) row.getGeneration());
            output.putShort((short) row.getType());
            output.put((byte) row.getOwner());
            output.putShort((short) row.getPositionX());
            output.putShort((short) row.getPositionY());
            output.putInt(row.getExactPositionX());
            output.putInt(row.getExactPositionY());
            output.putInt(row.getHitPoints());
            output.putInt(row.getShields());
            output.putShort((short) row.getEnergy());
            output.put((byte) row.getOrder());
            output.put((byte) row.getOrderState());
            output.put((byte) row.getOrderTimer());
            output.putShort((short) row.getOrderTargetIdentifier());
            output.putInt(row.getOrderTargetX());
            output.putInt(row.getOrderTargetY());
            output.put((byte) row.getSecondaryOrder());
            output.put((byte) row.getMovementState());
            output.put((byte) row.getMovementFlags());
            output.put((byte) row.getHeading());
            output.putInt(row.getCurrentSpeed());
            output.putInt((int) row.getStatus());
            output.put((byte) row.getUnionKind());
            output.putLong(row.getUnionValue());
            output.put((byte) row.getCarryingFlags());
            output.putShort((short) row.getRemainingBuildTime());
        }
    }

    private static @NotNull List<UnitState> getCanonicalRows(@NotNull UnitsSample sample) throws IOException {
        List<UnitState> rows = sample.getUnits();

        if (rows.size() > StarCraftLayout.MAXIMUM_UNITS) {
            throw Exceptions.newIOException("Units row limit exceeded: {0}", rows.size());
        }

        boolean ordered = true;
        int previousIdentifier = 0;

        for (UnitState row : rows) {
            if (row.getIdentifier() < 1 || row.getIdentifier() > StarCraftLayout.MAXIMUM_UNITS ||
                row.getGeneration() < 0 || row.getGeneration() > MAXIMUM_UNSIGNED_BYTE ||
                row.getType() < 0 || row.getType() > MAXIMUM_UNSIGNED_SHORT ||
                row.getOwner() < 0 || row.getOwner() > MAXIMUM_UNSIGNED_BYTE ||
                row.getPositionX() < 0 || row.getPositionX() > MAXIMUM_UNSIGNED_SHORT ||
                row.getPositionY() < 0 || row.getPositionY() > MAXIMUM_UNSIGNED_SHORT ||
                row.getEnergy() < 0 || row.getEnergy() > MAXIMUM_UNSIGNED_SHORT ||
                row.getOrder() < 0 || row.getOrder() > MAXIMUM_UNSIGNED_BYTE ||
                row.getOrderState() < 0 || row.getOrderState() > MAXIMUM_UNSIGNED_BYTE ||
                row.getOrderTimer() < 0 || row.getOrderTimer() > MAXIMUM_UNSIGNED_BYTE ||
                row.getOrderTargetIdentifier() < 0 || row.getOrderTargetIdentifier() > StarCraftLayout.MAXIMUM_UNITS ||
                row.getSecondaryOrder() < 0 || row.getSecondaryOrder() > MAXIMUM_UNSIGNED_BYTE ||
                row.getMovementState() < 0 || row.getMovementState() > MAXIMUM_UNSIGNED_BYTE ||
                row.getMovementFlags() < 0 || row.getMovementFlags() > MAXIMUM_UNSIGNED_BYTE ||
                row.getHeading() < 0 || row.getHeading() > MAXIMUM_UNSIGNED_BYTE ||
                row.getStatus() < 0 || row.getStatus() > MAXIMUM_UNSIGNED_INTEGER ||
                row.getUnionKind() < 0 || row.getUnionKind() > MAXIMUM_UNION_KIND ||
                row.getUnionKind() == UnitState.UNION_UNIT && row.getUnionValue() != UnitState.UNRESOLVED_UNIT &&
                (row.getUnionValue() < 0 || row.getUnionValue() > StarCraftLayout.MAXIMUM_UNITS) ||
                row.getUnionKind() == UnitState.UNION_SPRITE && (row.getUnionValue() < 0 || row.getUnionValue() > 1) ||
                row.getCarryingFlags() < 0 || row.getCarryingFlags() > MAXIMUM_UNSIGNED_BYTE ||
                row.getRemainingBuildTime() < 0 || row.getRemainingBuildTime() > MAXIMUM_UNSIGNED_SHORT) {
                throw Exceptions.newIOException("Invalid unit value");
            }

            if (row.getIdentifier() <= previousIdentifier) {
                ordered = false;
            }

            previousIdentifier = row.getIdentifier();
        }

        if (ordered) {
            return rows;
        }

        List<UnitState> sortedRows = new ArrayList<>(rows);

        sortedRows.sort((first, second) -> Integer.compare(first.getIdentifier(), second.getIdentifier()));

        for (int rowIndex = 1; rowIndex < sortedRows.size(); rowIndex++) {
            if (sortedRows.get(rowIndex - 1).getIdentifier() == sortedRows.get(rowIndex).getIdentifier()) {
                throw Exceptions.newIOException("Duplicate unit identifier");
            }
        }

        return sortedRows;
    }


    /**
     * Creates a {@link UnitsSampleDefinition} instance.
     */
    public UnitsSampleDefinition() {
        super(SAMPLE_IDENTIFIER, "units", UnitsSample.class, Integer.BYTES + StarCraftLayout.MAXIMUM_UNITS * ROW_BYTES);
    }


    /**
     * @return the bounded payload reader
     */
    @Override
    public @NotNull SampleReader<UnitsSample> getReader() {
        return READER;
    }

    /**
     * @return the canonical payload writer
     */
    @Override
    public @NotNull SampleWriter<UnitsSample> getWriter() {
        return WRITER;
    }


    /**
     * Calculates the exact payload size before reserving reusable output storage.
     *
     * @param sample the sample to encode
     * @return the exact encoded byte count
     * @throws IOException if the sample is invalid
     */
    @Override
    public int getEncodedSize(@NotNull UnitsSample sample) throws IOException {
        return Integer.BYTES + getCanonicalRows(sample).size() * ROW_BYTES;
    }

    /**
     * Creates a {@link Sampler} instance with session-owned capture buffers.
     *
     * @param context the capture session
     * @return the created {@link Sampler} instance
     */
    @Override
    public @NotNull Sampler<UnitsSample> createSampler(@NotNull CaptureContext context) {
        byte[] metadataStorage = new byte[UNIT_POOL_METADATA_BYTES];
        ByteBuffer metadata = ByteBuffer.wrap(metadataStorage).order(ByteOrder.LITTLE_ENDIAN);
        byte[] storage = new byte[StarCraftLayout.MAXIMUM_UNITS * StarCraftLayout.UNIT_STRIDE];
        ByteBuffer memory = ByteBuffer.wrap(storage).order(ByteOrder.LITTLE_ENDIAN);

        return () -> {
            MemoryReader reader = context.getMemory();

            reader.read(context.getModuleBase() + StarCraftLayout.UNIT_POOL_POINTER, metadataStorage, 0, metadataStorage.length);
            long poolAddress = metadata.getLong(0);
            long declaredSlots = metadata.getLong(Long.BYTES);

            if (declaredSlots < 0 || declaredSlots > StarCraftLayout.MAXIMUM_UNITS || (declaredSlots != 0 && poolAddress <= 0)) {
                throw Exceptions.newIOException("Invalid unit pool metadata: {0} slots", declaredSlots);
            }

            int poolSlots = (int) declaredSlots;

            if (poolSlots == 0) {
                return new UnitsSample(List.of());
            }

            reader.read(poolAddress, storage, 0, poolSlots * StarCraftLayout.UNIT_STRIDE);

            List<UnitState> units = new ArrayList<>();

            for (int rowIndex = 0; rowIndex < poolSlots; rowIndex++) {
                int rowOffset = rowIndex * StarCraftLayout.UNIT_STRIDE;

                if (memory.getLong(rowOffset + StarCraftLayout.UNIT_ACTIVE_OFFSET) != 0) {
                    int targetIdentifier = resolveUnitIdentifier(memory.getLong(rowOffset + StarCraftLayout.UNIT_ORDER_TARGET_IDENTIFIER_OFFSET),
                                                                 poolAddress, poolSlots);
                    int type = Short.toUnsignedInt(memory.getShort(rowOffset + StarCraftLayout.UNIT_TYPE_OFFSET));

                    units.add(new UnitState(rowIndex + 1, Byte.toUnsignedInt(memory.get(rowOffset + StarCraftLayout.UNIT_GENERATION_OFFSET)),
                                            type, Byte.toUnsignedInt(memory.get(rowOffset + StarCraftLayout.UNIT_OWNER_OFFSET)),
                                            Short.toUnsignedInt(memory.getShort(rowOffset + StarCraftLayout.UNIT_POSITION_X_OFFSET)),
                                            Short.toUnsignedInt(memory.getShort(rowOffset + StarCraftLayout.UNIT_POSITION_Y_OFFSET)),
                                            memory.getInt(rowOffset + StarCraftLayout.UNIT_EXACT_POSITION_X_OFFSET),
                                            memory.getInt(rowOffset + StarCraftLayout.UNIT_EXACT_POSITION_Y_OFFSET),
                                            memory.getInt(rowOffset + StarCraftLayout.UNIT_HIT_POINTS_OFFSET),
                                            memory.getInt(rowOffset + StarCraftLayout.UNIT_SHIELDS_OFFSET),
                                            Short.toUnsignedInt(memory.getShort(rowOffset + StarCraftLayout.UNIT_ENERGY_OFFSET)),
                                            Byte.toUnsignedInt(memory.get(rowOffset + StarCraftLayout.UNIT_ORDER_OFFSET)),
                                            Byte.toUnsignedInt(memory.get(rowOffset + StarCraftLayout.UNIT_ORDER_STATE_OFFSET)),
                                            Byte.toUnsignedInt(memory.get(rowOffset + StarCraftLayout.UNIT_ORDER_TIMER_OFFSET)),
                                            targetIdentifier,
                                            memory.getShort(rowOffset + StarCraftLayout.UNIT_ORDER_TARGET_X_OFFSET),
                                            memory.getShort(rowOffset + StarCraftLayout.UNIT_ORDER_TARGET_Y_OFFSET),
                                            Byte.toUnsignedInt(memory.get(rowOffset + StarCraftLayout.UNIT_SECONDARY_ORDER_OFFSET)),
                                            Byte.toUnsignedInt(memory.get(rowOffset + StarCraftLayout.UNIT_MOVEMENT_STATE_OFFSET)),
                                            Byte.toUnsignedInt(memory.get(rowOffset + StarCraftLayout.UNIT_MOVEMENT_FLAGS_OFFSET)),
                                            Byte.toUnsignedInt(memory.get(rowOffset + StarCraftLayout.UNIT_HEADING_OFFSET)),
                                            memory.getInt(rowOffset + StarCraftLayout.UNIT_CURRENT_SPEED_OFFSET),
                                            Integer.toUnsignedLong(memory.getInt(rowOffset + StarCraftLayout.UNIT_STATUS_OFFSET)),
                                            getUnionKind(type),
                                            decodeUnion(type, memory.getLong(rowOffset + StarCraftLayout.UNIT_UNION_OFFSET), poolAddress, poolSlots),
                                            Byte.toUnsignedInt(memory.get(rowOffset + StarCraftLayout.UNIT_CARRYING_FLAGS_OFFSET)),
                                            Short.toUnsignedInt(memory.getShort(rowOffset + StarCraftLayout.UNIT_REMAINING_BUILD_TIME_OFFSET))));
                }
            }

            return new UnitsSample(units);
        };
    }
}
