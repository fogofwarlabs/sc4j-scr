package org.fogofwarlabs.sc4j.scr.client.extension;

import org.jetbrains.annotations.Nullable;

/**
 * The text a chat log shows in place of a chat text ({@link ScrTextPresenter#presentChat}), which may come later; the chat log asks for it
 * each time it draws the text.
 */
public interface ScrShownText {
    /**
     * @return the text shown in place of the chat text once it is known, or {@code null} while the chat text is shown as it is
     */
    @Nullable String findShownText();
}
