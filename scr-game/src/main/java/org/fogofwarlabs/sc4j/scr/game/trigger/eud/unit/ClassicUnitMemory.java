package org.fogofwarlabs.sc4j.scr.game.trigger.eud.unit;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.trigger.ScrTriggerSystem;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerModifiers;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves the classic 336-byte unit table to persistent unit slots, including unallocated entries.
 * The profile accepts exactly 1700 slots and supports hit points, shields, energy, resources and status flags.
 * Accesses must fit wholly inside one supported field. Unsupported addresses, widths and operations fail before
 * mutation; pointer resolution and trigger scheduling remain with the caller.
 */
public final class ClassicUnitMemory implements ScrTriggerSystem.ExtendedDeathsOperations {
    /**
     * The mask of the low WORD.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of the low BYTE.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * The classic offset of the hit points DWORD.
     */
    private static final int HIT_POINTS = 0x08;
    /**
     * The classic offset of the shield points DWORD.
     */
    private static final int SHIELD_POINTS = 0x60;
    /**
     * The classic offset of the energy WORD.
     */
    private static final int ENERGY = 0xA2;
    /**
     * The classic offset of the resource amount WORD.
     */
    private static final int RESOURCES = 0xD0;
    /**
     * The classic offset of the status flags DWORD.
     */
    private static final int STATUS = 0xDC;


    /**
     * Resolves the arithmetic address used by an extended Deaths operand.
     * <p>
     * Both inputs are encoded values, not player selectors such as Current Player or All Players. Arithmetic wraps to 32
     * bits. Resolving the address does not grant read or write support.
     *
     * @param playerOperand the encoded player/index DWORD
     * @param unitOperand   the encoded unit operand, narrowed to its unsigned WORD
     * @return the virtual 32-bit address
     */
    public static int getDeathsAddress(int playerOperand, int unitOperand) {
        return ClassicAddressSpace.DEATH_TABLE_ADDRESS + Integer.BYTES * ((unitOperand & WORD_MASK) * PlayerTable.PLAYER_SLOTS + playerOperand);
    }

    private static int validateAccess(int address, int width) {
        getWidthMask(width);

        int relative = address - ClassicUnitTableRegion.BASE;

        if (Integer.compareUnsigned(relative, ClassicUnitTableRegion.ENTRY_COUNT * ClassicUnitTableRegion.ENTRY_SIZE - width) > 0) {
            throw Exceptions.newUnsupportedOperationException("Address is outside the supported classic unit table.");
        }

        int offset = relative % ClassicUnitTableRegion.ENTRY_SIZE;
        int field = getFieldStart(offset);
        int fieldWidth = field == ENERGY || field == RESOURCES ? Short.BYTES : Integer.BYTES;

        if (offset - field > fieldWidth - width) {
            throw Exceptions.newUnsupportedOperationException("Access crosses an unsupported classic unit field boundary.");
        }

        return relative;
    }

    private static int getWidthMask(int width) {
        return switch (width) {
            case Byte.BYTES -> BYTE_MASK;
            case Short.BYTES -> WORD_MASK;
            case Integer.BYTES -> -1;
            default -> throw Exceptions.newIllegalArgumentException("Unit memory access width must be 1, 2 or 4 bytes.");
        };
    }

    private static int getFieldStart(int offset) {
        if (offset >= HIT_POINTS && offset < HIT_POINTS + Integer.BYTES) {
            return HIT_POINTS;
        }

        if (offset >= SHIELD_POINTS && offset < SHIELD_POINTS + Integer.BYTES) {
            return SHIELD_POINTS;
        }

        if (offset >= ENERGY && offset < ENERGY + Short.BYTES) {
            return ENERGY;
        }

        if (offset >= RESOURCES && offset < RESOURCES + Short.BYTES) {
            return RESOURCES;
        }

        if (offset >= STATUS && offset < STATUS + Integer.BYTES) {
            return STATUS;
        }

        throw Exceptions.newUnsupportedOperationException("Classic unit field is not represented by this address profile.");
    }

    private static int readField(@NotNull Unit unit, int field) {
        return switch (field) {
            case HIT_POINTS -> unit.getHitPoints();
            case SHIELD_POINTS -> unit.getShieldPoints();
            case ENERGY -> unit.getEnergy();
            case RESOURCES -> unit.getResources();
            case STATUS -> unit.getStatus();
            default -> throw Exceptions.newAssertionError("{0}", field);
        };
    }

    private static void writeField(@NotNull Unit unit, int field, int value) {
        switch (field) {
            case HIT_POINTS -> unit.setHitPoints(value);
            case SHIELD_POINTS -> unit.setShieldPoints(value);
            case ENERGY -> unit.setEnergy(value);
            case RESOURCES -> unit.setResources(value);
            case STATUS -> unit.setStatus(value);
            default -> throw Exceptions.newAssertionError("{0}", field);
        }
    }


    private final @NotNull UnitPool units;


    /**
     * Creates a {@link ClassicUnitMemory} instance over the supplied persistent unit slots.
     *
     * @param units the authoritative classic-sized pool, which remains owned by the game
     * @throws IllegalArgumentException if the pool does not have exactly 1700 slots
     */
    public ClassicUnitMemory(@NotNull UnitPool units) {
        if (units.getCapacity() != ClassicUnitTableRegion.ENTRY_COUNT) {
            throw Exceptions.newIllegalArgumentException("The classic unit address profile requires exactly 1700 pool slots.");
        }

        this.units = units;
    }


    /**
     * Applies the bounded Set To operation requested by the trigger scheduler.
     *
     * @param playerOperand the encoded address index after player-selection validation
     * @param unitOperand   the encoded unit operand
     * @param modifier      {@link TriggerModifiers#SET_TO} for Set To
     * @param amount        the DWORD to store
     * @throws IllegalArgumentException      if the modifier is unsupported
     * @throws UnsupportedOperationException if the address does not select a supported DWORD field
     */
    @Override
    public void setExtendedDeaths(int playerOperand, int unitOperand, int modifier, int amount) {
        this.setDeaths(playerOperand, unitOperand, modifier, amount);
    }

    /**
     * Executes a Set To extended SetDeaths/SetMemory write into a supported DWORD field.
     * <p>
     * This bounded operation accepts {@link TriggerModifiers#SET_TO} only. It does not resolve player selectors, advance an
     * action cursor or change trigger preservation flags.
     *
     * @param playerOperand the encoded address index, not an unresolved player selector
     * @param unitOperand   the encoded unit operand
     * @param modifier      {@link TriggerModifiers#SET_TO} for Set To
     * @param amount        the DWORD to store
     * @throws IllegalArgumentException      if the modifier is unsupported
     * @throws UnsupportedOperationException if the address does not select a supported DWORD field
     */
    public void setDeaths(int playerOperand, int unitOperand, int modifier, int amount) {
        if (modifier != TriggerModifiers.SET_TO) {
            throw Exceptions.newIllegalArgumentException("This address profile implements Set To only.");
        }

        this.write(getDeathsAddress(playerOperand, unitOperand), Integer.BYTES, amount);
    }


    /**
     * Reads a little-endian byte, WORD or DWORD wholly within a supported field.
     *
     * @param address the virtual 32-bit address
     * @param width   the byte width, one of {@code 1}, {@code 2} or {@code 4}
     * @return unsigned byte/WORD values, or all signed integer bits for a DWORD
     * @throws IllegalArgumentException      if the width is not supported
     * @throws UnsupportedOperationException if the interval is outside a supported field
     */
    public int read(int address, int width) {
        int relative = validateAccess(address, width);
        int offset = relative % ClassicUnitTableRegion.ENTRY_SIZE;
        int field = getFieldStart(offset);
        Unit unit = this.units.getUnit(relative / ClassicUnitTableRegion.ENTRY_SIZE + 1);

        return readField(unit, field) >>> ((offset - field) * Byte.SIZE) & getWidthMask(width);
    }

    /**
     * Reads the supported DWORD selected by a decoded extended Deaths condition.
     *
     * @param playerOperand the encoded address index after player-selection validation
     * @param unitOperand   the encoded unit operand
     * @return all bits of the authoritative game field
     * @throws UnsupportedOperationException if the address does not select a supported DWORD field
     */
    @Override
    public int readExtendedDeaths(int playerOperand, int unitOperand) {
        return this.read(getDeathsAddress(playerOperand, unitOperand), Integer.BYTES);
    }

    /**
     * Writes the low bits of a value into a supported field without changing its other bits.
     * <p>
     * Validation precedes mutation. No allocation, destruction, regeneration, display refresh or other gameplay operation
     * is implied. Resource writes affect the same WORD observed by larva-side state.
     *
     * @param address the virtual 32-bit address
     * @param width   the byte width, one of {@code 1}, {@code 2} or {@code 4}
     * @param value   the low bits to write
     * @throws IllegalArgumentException      if the width is not supported
     * @throws UnsupportedOperationException if the interval is outside a supported field
     */
    public void write(int address, int width, int value) {
        int relative = validateAccess(address, width);
        int offset = relative % ClassicUnitTableRegion.ENTRY_SIZE;
        int field = getFieldStart(offset);
        int shift = (offset - field) * Byte.SIZE;
        int mask = getWidthMask(width) << shift;
        Unit unit = this.units.getUnit(relative / ClassicUnitTableRegion.ENTRY_SIZE + 1);
        int merged = readField(unit, field) & ~mask | value << shift & mask;

        writeField(unit, field, merged);
    }

    /**
     * Replaces selected bits in a supported DWORD without maintaining a shadow memory image.
     *
     * @param address the virtual address of a supported DWORD field
     * @param value   the bits selected by the mask
     * @param mask    the bits to replace; zero leaves the value unchanged but still validates the address
     * @throws UnsupportedOperationException if the interval is outside a supported DWORD field
     */
    public void writeMaskedInteger(int address, int value, int mask) {
        int previous = this.read(address, Integer.BYTES);

        this.write(address, Integer.BYTES, previous & ~mask | value & mask);
    }

    /**
     * Tests an extended Deaths/Memory condition whose encoded operands resolve to a supported DWORD.
     * <p>
     * Player selection and trigger scheduling belong to the caller. The amount and observed value are compared as
     * unsigned DWORDs; normal death counters are outside this unit-only profile.
     *
     * @param playerOperand the encoded address index, not an unresolved player selector
     * @param unitOperand   the encoded unit operand
     * @param comparison    {@link TriggerModifiers#AT_LEAST}, {@link TriggerModifiers#AT_MOST} or {@link TriggerModifiers#EXACTLY}
     * @param amount        the comparison DWORD
     * @return {@code true} if the unsigned comparison matches, {@code false} otherwise
     * @throws IllegalArgumentException      if the comparison is unsupported
     * @throws UnsupportedOperationException if the address does not select a supported DWORD field
     */
    public boolean testDeaths(int playerOperand, int unitOperand, int comparison, int amount) {
        if (comparison != TriggerModifiers.AT_LEAST && comparison != TriggerModifiers.AT_MOST && comparison != TriggerModifiers.EXACTLY) {
            throw Exceptions.newIllegalArgumentException("Unsupported Deaths comparison: {0}", comparison);
        }

        int value = this.read(getDeathsAddress(playerOperand, unitOperand), Integer.BYTES);

        return comparison == TriggerModifiers.AT_LEAST
               ? Integer.compareUnsigned(value, amount) >= 0
               : comparison == TriggerModifiers.AT_MOST ? Integer.compareUnsigned(value, amount) <= 0 : value == amount;
    }
}
