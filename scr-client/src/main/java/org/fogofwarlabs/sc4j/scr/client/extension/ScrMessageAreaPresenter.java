package org.fogofwarlabs.sc4j.scr.client.extension;

import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Chooses what a game's message area draws in place of the bytes of a line. It receives the writes to the message lines as their text
 * listener, and the message area asks it for each nonempty rotating line and the system line it draws.
 */
public interface ScrMessageAreaPresenter extends MessageLines.TextListener {
    /**
     * Finds the text a line shows in place of its bytes.
     *
     * @param line     the rotating line or {@link MessageLines#SYSTEM_LINE}
     * @param bytes    the line's bytes up to the first zero ({@link MessageLines#getLine}), not empty
     * @param hostTime the host clock of the drawn frame in milliseconds
     * @return the substitute, or {@code null} when the line shows its bytes
     */
    @Nullable ScrMessageSubstitute findSubstitute(int line, byte @NotNull [] bytes, int hostTime);
}
