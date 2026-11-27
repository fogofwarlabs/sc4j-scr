package org.fogofwarlabs.sc4j.scr.client.extension;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * A text presenter for tests that records the map texts and chat texts it is given and shows the texts it was told to show.
 */
public final class RecordingTextPresenter implements ScrTextPresenter {
    /**
     * A recorded chat text, whose shown text a test sets.
     */
    public static final class Chat implements ScrShownText {
        private final @NotNull String text;
        private final int protectedLength;
        private final @NotNull Locale target;
        private @Nullable String shownText;

        private Chat(@NotNull String text, int protectedLength, @NotNull Locale target) {
            this.text            = text;
            this.protectedLength = protectedLength;
            this.target          = target;
            this.shownText       = null;
        }

        /**
         * @return the chat text
         */
        public @NotNull String getText() {
            return this.text;
        }

        /**
         * @return the length of the start of the text that stays as it is
         */
        public int getProtectedLength() {
            return this.protectedLength;
        }

        /**
         * @return the text language the text was given with
         */
        public @NotNull Locale getTarget() {
            return this.target;
        }

        /**
         * @param shownText the text shown in place of the chat text from now on
         */
        public void show(@NotNull String shownText) {
            this.shownText = shownText;
        }

        /**
         * @return the text set by {@link #show}, or {@code null} before
         */
        @Override
        public @Nullable String findShownText() {
            return this.shownText;
        }
    }

    private final @NotNull List<@NotNull String> mapTexts;
    private final @NotNull Map<@NotNull String, @NotNull String> shownMapTexts;
    private final @NotNull List<@NotNull Chat> chats;

    /**
     * Creates a {@link RecordingTextPresenter} instance that has recorded nothing.
     */
    public RecordingTextPresenter() {
        this.mapTexts      = new ArrayList<>();
        this.shownMapTexts = new HashMap<>();
        this.chats         = new ArrayList<>();
    }

    /**
     * @return the map texts given so far, in order, repeats included
     */
    public @NotNull List<@NotNull String> getMapTexts() {
        return this.mapTexts;
    }

    /**
     * @return the chat texts given so far, in order
     */
    public @NotNull List<@NotNull Chat> getChats() {
        return this.chats;
    }

    /**
     * @param text      a map text
     * @param shownText the text the map text shows from now on
     */
    public void showMapText(@NotNull String text, @NotNull String shownText) {
        this.shownMapTexts.put(text, shownText);
    }

    /**
     * @return {@code true}
     */
    @Override
    public boolean presentsMapTexts() {
        return true;
    }

    /**
     * Records the map text and returns the text set for it by {@link #showMapText}, or the text itself.
     *
     * @param text   the map text
     * @param target the text language of the client
     * @return the shown text
     */
    @Override
    public @NotNull String presentMapText(@NotNull String text, @NotNull Locale target) {
        this.mapTexts.add(text);

        return this.shownMapTexts.getOrDefault(text, text);
    }

    /**
     * Records the chat text.
     *
     * @param text            the text
     * @param protectedLength the length of the start of the text that stays as it is
     * @param target          the text language of the client
     * @return the recorded chat text
     */
    @Override
    public @NotNull ScrShownText presentChat(@NotNull String text, int protectedLength, @NotNull Locale target) {
        Chat chat = new Chat(text, protectedLength, target);

        this.chats.add(chat);

        return chat;
    }
}
