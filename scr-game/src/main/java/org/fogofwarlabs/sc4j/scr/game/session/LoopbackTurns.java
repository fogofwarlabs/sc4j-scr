package org.fogofwarlabs.sc4j.scr.game.session;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;

/**
 * Carries the local outgoing turns of a game without network peers back to the local player, as the single-player turn
 * provider does for a single-player game and for replay playback.
 * <p>
 * Each turn step receives the oldest turn in transit, an empty turn while none is, then sends the outgoing turn until the
 * requested number of turns is in transit again. A received turn is therefore the outgoing turn of an earlier turn step. A turn the
 * outgoing turn sends early ({@link OutgoingTurnBuffer#queue}) joins the turns in transit.
 */
public final class LoopbackTurns implements OutgoingTurnBuffer.TurnSender {
    /**
     * The turn a turn step receives while no turn is in transit.
     */
    private static final byte @NotNull [] EMPTY_TURN = new byte[0];

    private final @NotNull OutgoingTurnBuffer outgoingTurn;

    private final @NotNull ArrayDeque<byte[]> turnsInTransit;

    /**
     * Creates a {@link LoopbackTurns} instance without turns in transit.
     *
     * @param outgoingTurn the local outgoing turn the turn steps send
     */
    public LoopbackTurns(@NotNull OutgoingTurnBuffer outgoingTurn) {
        this.outgoingTurn = outgoingTurn;

        this.turnsInTransit = new ArrayDeque<>();
    }

    /**
     * @return the local turns sent and not yet received back
     */
    @Override
    public int getTurnsInTransit() {
        return this.turnsInTransit.size();
    }


    /**
     * Sends the local player's next turn.
     *
     * @param turn the turn bytes
     */
    @Override
    public void sendTurn(byte @NotNull [] turn) {
        this.turnsInTransit.addLast(turn);
    }

    /**
     * Runs the turn exchange of one turn step: receives the oldest turn in transit, then sends the outgoing turn
     * ({@link OutgoingTurnBuffer#send()}) while fewer than {@code transitTurns} turns are in transit.
     *
     * @param transitTurns the turns kept in transit after the exchange
     * @return the received turn, empty while no turn was in transit
     * @throws UnsupportedOperationException if the outgoing turn cannot be sent
     */
    public byte @NotNull [] exchangeTurn(int transitTurns) {
        byte[] received = this.turnsInTransit.isEmpty() ? EMPTY_TURN : this.turnsInTransit.removeFirst();

        while (this.turnsInTransit.size() < transitTurns) {
            this.sendTurn(this.outgoingTurn.send());
        }

        return received;
    }
}
