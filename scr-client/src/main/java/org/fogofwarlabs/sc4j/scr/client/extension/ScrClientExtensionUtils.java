package org.fogofwarlabs.sc4j.scr.client.extension;

import org.fogofwarlabs.sc4j.engine.mod.ModContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Creates the text presenter of the {@link ScrClientExtension} implementations that the jar of the running mod names. They are found once per
 * class loader, with the class loader of {@link ScrClientExtension}, so a mod jar in its own class loader finds its own implementations.
 */
public final class ScrClientExtensionUtils {
    private static final @NotNull List<@NotNull ScrClientExtension> EXTENSIONS = ServiceLoader.load(ScrClientExtension.class,
                                                                                                    ScrClientExtension.class.getClassLoader())
                                                                                              .stream()
                                                                                              .map(ServiceLoader.Provider::get)
                                                                                              .toList();

    /**
     * Creates the text presenter of the client: that of the first extension that creates one ({@link ScrClientExtension#createTextPresenter}).
     *
     * @param context the context of the mod
     * @return the created presenter, or {@link ScrTextPresenter#UNCHANGED} when no extension creates one
     */
    public static @NotNull ScrTextPresenter createTextPresenter(@NotNull ModContext context) {
        for (ScrClientExtension extension : EXTENSIONS) {
            ScrTextPresenter presenter = extension.createTextPresenter(context);

            if (presenter != null) {
                return presenter;
            }
        }

        return ScrTextPresenter.UNCHANGED;
    }

    private ScrClientExtensionUtils() {
    }
}
