package org.fogofwarlabs.sc4j.scr.game.sprite.script.instruction.unit;

import org.fogofwarlabs.sc4j.engine.animation.AnimationContext;
import org.fogofwarlabs.sc4j.engine.animation.AnimationStep;
import org.fogofwarlabs.sc4j.scr.data.format.iscript.IscriptInstruction;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptModeOneInterpreter;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.instruction.ScrImageTarget;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.instruction.ScrInstruction;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.instruction.ScrModeOneStep;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.instruction.ScrPreviewSupport;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;

/**
 * Runs {@code move}: applies the operand as the iscript movement speed of the unit.
 */
public final class ScrMove extends ScrInstruction implements ScrModeOneStep {
    private final int speed;

    /**
     * Creates a {@link ScrMove} instance.
     *
     * @param decoded the decoded {@code move} instruction
     */
    public ScrMove(@NotNull IscriptInstruction decoded) {
        super(decoded, ScrPreviewSupport.WITH_UNIT);

        this.speed = decoded.getOperands()[0];
    }


    /**
     * Runs this instruction for the animated object of the context.
     * <p>
     * Fails with an {@link IllegalStateException} without a unit context.
     *
     * @param context the running animation of one object
     * @return how the program continues
     * @throws UnsupportedOperationException if a bounded update meets the instruction and does not run it
     */
    @Override
    public @NotNull AnimationStep execute(@NotNull AnimationContext context) {
        ScrImageTarget target = getTarget(context);

        this.checkPreviewSupport(context, target);

        Unit unit = this.requireUnit(target);
        int speed = target.getGame().getUnitSpeed().calculateModifiedSpeed(unit, this.speed << IscriptExecutor.MOVEMENT_FRACTION_BITS);

        unit.applyIscriptMovementSpeed(speed);

        return AnimationStep.CONTINUE;
    }

    /**
     * Runs this instruction in mode one on the scratch state of the interpreter, whose program counter is the offset of the
     * instruction.
     * <p>
     * Yields the operand as the modified movement speed of the unit and goes on.
     *
     * @param interpreter  the mode-one interpreter
     * @param game         the game, whose random generator the instruction may draw
     * @param unit         the unit whose speed modifiers the instruction may apply
     * @param nextPosition the offset after the instruction
     */
    @Override
    public void stepModeOne(@NotNull IscriptModeOneInterpreter interpreter, @NotNull Game game, @NotNull Unit unit, int nextPosition) {
        interpreter.setSpeed(game.getUnitSpeed().calculateModifiedSpeed(unit, this.speed << IscriptExecutor.MOVEMENT_FRACTION_BITS));
        interpreter.setProgramPosition(nextPosition);
    }
}
