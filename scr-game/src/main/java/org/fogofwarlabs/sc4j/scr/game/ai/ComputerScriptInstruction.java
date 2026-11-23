package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.engine.computer.ComputerInstruction;
import org.jetbrains.annotations.NotNull;

/**
 * Executes one opcode of a computer AI script record: the aiscript instruction of an opcode in the engine instruction registry of
 * {@link ComputerScripts}, which runs the instructions of a record until one stops it (a wait, a release or an error path).
 */
@FunctionalInterface
interface ComputerScriptInstruction extends ComputerInstruction<ScrComputerScriptContext> {
    /**
     * Executes the instruction for the running script of the context.
     * <p>
     * Executes the opcode of the context ({@link #execute(ComputerPlayerPhase, int, int, int, int, boolean, int)}).
     *
     * @param context the context of the running script
     * @return {@code true} if the script continues with its next instruction, {@code false} if it stops for this frame (a wait, an end or a retry later)
     */
    @Override
    default boolean execute(@NotNull ScrComputerScriptContext context) {
        return this.execute(context.getPhase(),
                            context.getRecord(),
                            context.getOpcode(), context.getStart(),
                            context.getOwner(),
                            context.isExpansion(),
                            context.getInstructionLimit());
    }

    /**
     * Executes the opcode at the record's program counter, which already points past the opcode BYTE.
     *
     * @param phase            the owning computer phase
     * @param index            the script record
     * @param opcode           the opcode
     * @param start            the program counter of the opcode BYTE, for a retry
     * @param owner            the record's owner
     * @param expansion        whether the record runs the expansion program
     * @param instructionLimit the iscript budget for issued orders
     * @return {@code true} if the record continues with the next opcode, {@code false} otherwise (it stops for this frame)
     */
    boolean execute(@NotNull ComputerPlayerPhase phase, int index, int opcode, int start, int owner, boolean expansion, int instructionLimit);
}
