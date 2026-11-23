package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Resolves original strategy identifiers using the 16-byte and 8-byte program directories.
 */
final class ComputerScriptProgram {
    /**
     * The bytes the loader adds after each resource before the size is rounded down.
     */
    private static final int BUFFER_PADDING = 0x6000;
    /**
     * The buffer size is rounded down to a multiple of this.
     */
    private static final int BUFFER_ALIGNMENT = 4;

    /**
     * The size of the directory pointer at the start of a resource and of a directory entry field.
     */
    private static final int POINTER_SIZE = Integer.BYTES;
    /**
     * The directory entry size of the main program.
     */
    private static final int MAIN_DIRECTORY_STRIDE = 16;
    /**
     * The directory entry size of the expansion program.
     */
    private static final int EXPANSION_DIRECTORY_STRIDE = 8;
    /**
     * The offset of the script address in a directory entry, after the identifier.
     */
    private static final int ENTRY_ADDRESS_OFFSET = 4;
    /**
     * The number of programs {@link #find} searches: the main program, then the expansion program.
     */
    private static final int PROGRAM_COUNT = 2;

    private static void validateDirectory(byte @NotNull [] program, int stride) {
        if (program.length < POINTER_SIZE) {
            throw Exceptions.newIllegalArgumentException("Computer script resource has no directory pointer.");
        }

        int directory = LittleEndianUtils.readInt32(program, 0);

        if (directory < POINTER_SIZE || directory > program.length - POINTER_SIZE) {
            throw Exceptions.newIllegalArgumentException("Computer script directory is outside its resource.");
        }

        for (int offset = directory; offset <= program.length - POINTER_SIZE; offset += stride) {
            if (LittleEndianUtils.readInt32(program, offset) == 0) {
                return;
            }

            if (offset > program.length - stride) {
                break;
            }
        }

        throw Exceptions.newIllegalArgumentException("Computer script directory has no terminator.");
    }


    private final byte @NotNull [] mainProgram;
    private final byte @NotNull [] expansionProgram;

    /**
     * Creates a {@link ComputerScriptProgram} instance with the two script buffers: each resource followed by zeros up to
     * its size plus {@value #BUFFER_PADDING}, rounded down to a multiple of {@value #BUFFER_ALIGNMENT}. The script reader
     * bounds by that size, and the EUD regions of the two programs are these buffers.
     *
     * @param mainProgram      the main program resource
     * @param expansionProgram the expansion program resource
     * @throws IllegalArgumentException if a directory is missing, outside its resource or not terminated
     */
    ComputerScriptProgram(byte @NotNull [] mainProgram, byte @NotNull [] expansionProgram) {
        this.mainProgram      = Arrays.copyOf(mainProgram, mainProgram.length + BUFFER_PADDING & ~(BUFFER_ALIGNMENT - 1));
        this.expansionProgram = Arrays.copyOf(expansionProgram, expansionProgram.length + BUFFER_PADDING & ~(BUFFER_ALIGNMENT - 1));

        validateDirectory(this.mainProgram, MAIN_DIRECTORY_STRIDE);
        validateDirectory(this.expansionProgram, EXPANSION_DIRECTORY_STRIDE);
    }

    /**
     * @param expansion whether the expansion program is requested
     * @return the live buffer of the program, which EUD writes change
     */
    byte @NotNull [] getBuffer(boolean expansion) {
        return expansion ? this.expansionProgram : this.mainProgram;
    }


    /**
     * Finds a strategy identifier in the main directory, then in the expansion directory.
     *
     * @param identifier the strategy identifier
     * @return the program index in the upper 32 bits and the script address in the lower 32 bits, or {@code -1} if no directory entry with a nonzero address has the identifier
     * @throws IllegalArgumentException if the entry's address is outside its resource
     */
    long find(int identifier) {
        for (int programIndex = 0; programIndex < PROGRAM_COUNT; programIndex++) {
            byte[] program = programIndex == 0 ? this.mainProgram : this.expansionProgram;
            int stride = programIndex == 0 ? MAIN_DIRECTORY_STRIDE : EXPANSION_DIRECTORY_STRIDE;
            int directory = LittleEndianUtils.readInt32(program, 0);

            for (int offset = directory; offset <= program.length - stride; offset += stride) {
                int candidate = LittleEndianUtils.readInt32(program, offset);

                if (candidate == 0) {
                    break;
                }

                if (candidate == identifier) {
                    int address = LittleEndianUtils.readInt32(program, offset + ENTRY_ADDRESS_OFFSET);

                    if (address != 0) {
                        if (address < 0 || address >= program.length) {
                            throw Exceptions.newIllegalArgumentException("Computer script entry exceeds its resource.");
                        }

                        return (long) programIndex << Integer.SIZE | Integer.toUnsignedLong(address);
                    }

                    break;
                }
            }
        }

        return -1;
    }

    /**
     * Reads one unsigned byte of a program.
     *
     * @param expansion whether the expansion program is read
     * @param address   the byte address
     * @return the unsigned byte
     * @throws IllegalStateException if the address is outside the program buffer
     */
    int readByte(boolean expansion, int address) {
        byte[] program = expansion ? this.expansionProgram : this.mainProgram;

        if (address < 0 || address >= program.length) {
            throw Exceptions.newIllegalStateException("Computer script address exceeds its resource: {0}", address);
        }

        return Byte.toUnsignedInt(program[address]);
    }

    /**
     * Reads the text of script opcode {@code debug}, from the address up to the first zero within the program buffer.
     *
     * @param expansion whether the expansion program is read
     * @param address   the first text byte
     * @return the text bytes without the terminator
     * @throws IllegalStateException if the text reaches the program end
     */
    byte @NotNull [] readText(boolean expansion, int address) {
        byte[] program = expansion ? this.expansionProgram : this.mainProgram;

        if (address < 0 || address >= program.length) {
            throw Exceptions.newIllegalStateException("Computer script text address exceeds its resource: {0}", address);
        }

        int end = address;

        while (end < program.length && program[end] != 0) {
            end++;
        }

        if (end >= program.length) {
            throw Exceptions.newIllegalStateException("Computer script text is not terminated before its resource ends: {0}", address);
        }

        return Arrays.copyOfRange(program, address, end);
    }
}
