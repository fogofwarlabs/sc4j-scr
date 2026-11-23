package org.fogofwarlabs.sc4j.scr.game.order;

import org.fogofwarlabs.sc4j.engine.order.OrderContext;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;

/**
 * Carries the values of one SC:R order dispatch ({@link UnitOrders}) to the steps of an {@link ScrOrderBehavior}: the player type of the unit's
 * owner for this frame and the instruction budget of each animation script invocation.
 * <p>
 * The dispatcher keeps one context and sets its values before it runs the steps of a unit; a subunit runs with the values of its parent. A step
 * reads the context only during its call.
 */
public final class ScrOrderContext implements OrderContext {
    private final @NotNull Game game;

    private int ownerPlayerType;
    private int instructionLimit;

    /**
     * Creates a {@link ScrOrderContext} instance for one game, with player type {@code 0} and budget {@code 0} until the dispatcher sets them.
     *
     * @param game the SC:R game of the dispatcher
     */
    public ScrOrderContext(@NotNull Game game) {
        this.game = game;

        this.ownerPlayerType  = 0;
        this.instructionLimit = 0;
    }

    /**
     * @return the engine game of the SC:R game
     * @throws IllegalStateException if the SC:R game system is not attached yet
     */
    @Override
    public org.fogofwarlabs.sc4j.engine.game.@NotNull Game getGame() {
        return this.game.getEngineGame();
    }

    /**
     * @return the player type of the unit's owner for this frame
     */
    public int getOwnerPlayerType() {
        return this.ownerPlayerType;
    }

    /**
     * @return the instruction limit of each animation program run the behavior starts, which it also passes to the order system when it changes an order
     */
    @Override
    public int getInstructionLimit() {
        return this.instructionLimit;
    }

    /**
     * Sets the values of the next dispatch.
     *
     * @param ownerPlayerType  the player type of the unit's owner for this frame
     * @param instructionLimit the instruction budget of each animation script invocation
     */
    public void set(int ownerPlayerType, int instructionLimit) {
        this.ownerPlayerType  = ownerPlayerType;
        this.instructionLimit = instructionLimit;
    }
}
