package org.fogofwarlabs.sc4j.scr.client.extension;

import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * Chooses how the client shows map texts and the texts of other players, such as in another language. The client keeps the texts it shows
 * this way as they are; only what it draws changes. Every method runs on the client's user interface thread.
 */
public interface ScrTextPresenter {
    /**
     * The presenter that shows every text as it is.
     */
    @NotNull ScrTextPresenter UNCHANGED = new ScrTextPresenter() {
    };

    /**
     * Tells whether the presenter changes how map texts are shown, so the client looks for the texts a map changed only when it does.
     *
     * @return {@code true} if {@link #presentMapText} may return another text, {@code false} otherwise
     */
    default boolean presentsMapTexts() {
        return false;
    }

    /**
     * Returns the text a map text shows, such as a scenario name, a briefing, a mission objective, a unit name or a string the map changed.
     *
     * @param text   the map text
     * @param target the text language of the client
     * @return the shown text
     */
    default @NotNull String presentMapText(@NotNull String text, @NotNull Locale target) {
        return text;
    }

    /**
     * Returns how a chat text of another player in a chat log is shown.
     *
     * @param text            the text
     * @param protectedLength the length of the start of the text that stays as it is, such as the name an emote starts with, or {@code 0}
     * @param target          the text language of the client
     * @return the shown text, or {@code null} when the text is shown as it is
     */
    default @Nullable ScrShownText presentChat(@NotNull String text, int protectedLength, @NotNull Locale target) {
        return null;
    }

    /**
     * Creates the presenter of a game's message area, which the client installs as the text listener of its message lines.
     *
     * @param lines           the message lines
     * @param layout          the message area's layout
     * @param target          the text language of the client
     * @param otherHumanTexts tells whether the chat texts of a sender slot are those of another human player
     * @return the created presenter, or {@code null} when the message area shows its lines as they are
     */
    default @Nullable ScrMessageAreaPresenter createMessageArea(@NotNull MessageLines lines, MessageLines.@NotNull TextLayout layout,
                                                                @NotNull Locale target, @NotNull IntPredicate otherHumanTexts) {
        return null;
    }
}
