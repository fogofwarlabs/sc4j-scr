package org.fogofwarlabs.sc4j.scr.game.session;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks command-buffer transmission, retained bytes, the access to the queued Sync command and the turns a command that does not fit sends
 * early.
 */
final class OutgoingTurnBufferTest {
    /**
     * The command types of the Sync, Keep Alive, Select and Stop commands.
     */
    private static final int SYNC_COMMAND_TYPE = 0x37;
    private static final int KEEP_ALIVE_COMMAND_TYPE = 0x05;
    private static final int SELECT_COMMAND_TYPE = 0x09;
    private static final int STOP_COMMAND_TYPE = 0x1A;
    /**
     * The length of the Sync command that starts every turn.
     */
    private static final int SYNC_COMMAND_LENGTH = 7;
    /**
     * The Stop command, and the Stop commands that fill a turn after its Sync command.
     */
    private static final byte @NotNull [] STOP_COMMAND = {0x1A, 0x00};
    private static final int STOPS_PER_TURN = (LiveGame.MAXIMUM_TURN_BYTES - SYNC_COMMAND_LENGTH) / STOP_COMMAND.length;
    /**
     * The base turns in transit of a network game.
     */
    private static final int NETWORK_TURNS_IN_TRANSIT = 2;

    /**
     * A turn sender whose sent turns stay in transit.
     */
    private static final class StalledTurnSender implements OutgoingTurnBuffer.TurnSender {
        private final @NotNull List<byte[]> sent;

        /**
         * Creates a {@link StalledTurnSender} instance without sent turns.
         */
        private StalledTurnSender() {
            this.sent = new ArrayList<>();
        }

        /**
         * @return the local turns sent and not yet received back
         */
        @Override
        public int getTurnsInTransit() {
            return this.sent.size();
        }

        /**
         * Sends the local player's next turn.
         *
         * @param turn the turn bytes
         */
        @Override
        public void sendTurn(byte @NotNull [] turn) {
            this.sent.add(turn);
        }
    }

    @Test
    void send_should_include_commands_written_at_the_length_and_reset_to_sync() {
        OutgoingTurnBuffer buffer = new OutgoingTurnBuffer(LiveGame.MAXIMUM_TURN_BYTES);

        Assertions.assertEquals(SYNC_COMMAND_LENGTH, buffer.getLength());

        byte[] select = {0x09, 0x01, (byte) 0x94, 0x06};

        for (int index = 0; index < select.length; index++) {
            buffer.writeByte(buffer.getLength() + index, select[index]);
        }

        buffer.setLength(buffer.getLength() + select.length);
        buffer.queue(new byte[]{0x1A, 0x00});

        byte[] turn = buffer.send();

        Assertions.assertArrayEquals(new byte[]{0x37, 0, 0, 0, 0, 0, 0, 0x09, 0x01, (byte) 0x94, 0x06, 0x1A, 0x00}, turn);
        Assertions.assertEquals(SYNC_COMMAND_LENGTH, buffer.getLength());
        Assertions.assertEquals(SELECT_COMMAND_TYPE, buffer.readByte(OutgoingTurnBuffer.SYNC_COMMAND_LENGTH));
        Assertions.assertEquals(STOP_COMMAND_TYPE, buffer.readByte(OutgoingTurnBuffer.SYNC_COMMAND_LENGTH + select.length));
    }

    @Test
    void send_should_send_keep_alive_for_an_empty_turn() {
        OutgoingTurnBuffer buffer = new OutgoingTurnBuffer(LiveGame.MAXIMUM_TURN_BYTES);

        // A map clears the length as an EUD write does.
        buffer.setLength(0);

        Assertions.assertArrayEquals(new byte[]{KEEP_ALIVE_COMMAND_TYPE}, buffer.send());
        Assertions.assertEquals(SYNC_COMMAND_TYPE, buffer.readByte(0), "The next turn's Sync command is queued over it.");
        Assertions.assertEquals(SYNC_COMMAND_LENGTH, buffer.getLength());
    }

    @Test
    void sync_command_bytes_are_readable_and_reject_changing_writes() {
        OutgoingTurnBuffer buffer = new OutgoingTurnBuffer(LiveGame.MAXIMUM_TURN_BYTES);

        // Before the game starts its Sync records, the queued Sync command carries zero record bytes.
        Assertions.assertEquals(SYNC_COMMAND_TYPE, buffer.readByte(0));
        Assertions.assertEquals(0, buffer.readByte(1));
        Assertions.assertEquals(0, buffer.readByte(4));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> buffer.writeByte(2, 1));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> buffer.writeByte(5, 1));

        buffer.writeByte(2, 0);
        buffer.writeByte(5, 0);
    }

    @Test
    void queue_should_send_the_turn_early_when_the_command_does_not_fit() {
        OutgoingTurnBuffer buffer = new OutgoingTurnBuffer(LiveGame.MAXIMUM_TURN_BYTES);
        StalledTurnSender sender = new StalledTurnSender();

        buffer.setTurnSender(sender, LiveGame.LATENCY_TURNS);

        for (int index = 0; index < STOPS_PER_TURN; index++) {
            Assertions.assertTrue(buffer.queue(STOP_COMMAND));
        }

        Assertions.assertTrue(sender.sent.isEmpty(), "The commands fit the turn.");
        Assertions.assertTrue(buffer.queue(STOP_COMMAND));
        Assertions.assertEquals(1, sender.sent.size());

        byte[] extraTurn = sender.sent.getFirst();

        Assertions.assertEquals(SYNC_COMMAND_LENGTH + STOPS_PER_TURN * STOP_COMMAND.length, extraTurn.length);
        Assertions.assertEquals(SYNC_COMMAND_TYPE, extraTurn[0]);
        Assertions.assertEquals(SYNC_COMMAND_LENGTH + STOP_COMMAND.length, buffer.getLength(), "The command starts the next turn.");
        Assertions.assertEquals(SYNC_COMMAND_TYPE, buffer.readByte(0));
        Assertions.assertEquals(STOP_COMMAND_TYPE, buffer.readByte(SYNC_COMMAND_LENGTH));
    }

    @Test
    void queue_should_drop_the_command_while_too_many_turns_are_in_transit() {
        OutgoingTurnBuffer buffer = new OutgoingTurnBuffer(LiveGame.MAXIMUM_TURN_BYTES);
        StalledTurnSender sender = new StalledTurnSender();
        int queued = 0;

        buffer.setTurnSender(sender, NETWORK_TURNS_IN_TRANSIT);

        while (buffer.queue(STOP_COMMAND)) {
            queued++;
        }

        int length = buffer.getLength();

        Assertions.assertEquals(OutgoingTurnBuffer.MAXIMUM_TURNS_IN_TRANSIT - NETWORK_TURNS_IN_TRANSIT, sender.sent.size());
        Assertions.assertEquals(sender.sent.size() * STOPS_PER_TURN + STOPS_PER_TURN, queued);
        Assertions.assertFalse(buffer.queue(STOP_COMMAND));
        Assertions.assertEquals(length, buffer.getLength(), "The dropped command leaves the turn unchanged.");
    }

    @Test
    void queue_should_fail_for_a_command_that_does_not_fit_without_a_turn_sender() {
        OutgoingTurnBuffer buffer = new OutgoingTurnBuffer(LiveGame.MAXIMUM_TURN_BYTES);

        Assertions.assertThrows(IllegalStateException.class, () -> buffer.queue(new byte[LiveGame.MAXIMUM_TURN_BYTES]));
    }
}
