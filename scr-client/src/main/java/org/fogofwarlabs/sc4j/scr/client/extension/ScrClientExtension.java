package org.fogofwarlabs.sc4j.scr.client.extension;

import org.fogofwarlabs.sc4j.engine.mod.ModContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Extends the client of the SC:R mod: how it shows map texts and the texts of other players. A mod built from the SC:R mod's sources adds an
 * implementation with a public no-argument constructor and names it in the resource
 * {@code META-INF/services/org.fogofwarlabs.sc4j.scr.client.extension.ScrClientExtension} of its jar; {@link ScrClientExtensionUtils} asks
 * the named implementations in the order of that file. The SC:R mod itself names none.
 */
public interface ScrClientExtension {
    /**
     * Creates the presenter of the client's texts, once, when the mod registers its client.
     *
     * @param context the context of the mod, with its settings and the run's translator
     * @return the created presenter, or {@code null} if the extension does not change how texts are shown
     */
    @Nullable ScrTextPresenter createTextPresenter(@NotNull ModContext context);
}
