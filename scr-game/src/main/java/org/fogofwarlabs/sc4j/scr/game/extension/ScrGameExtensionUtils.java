package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Creates the plugins of the {@link ScrGameExtension} implementations that the jar of the running mod names. They are found once per class
 * loader, with the class loader of {@link ScrGameExtension}, so a mod jar in its own class loader finds its own implementations.
 */
public final class ScrGameExtensionUtils {
    private static final @NotNull List<@NotNull ScrGameExtension> EXTENSIONS =
            ServiceLoader.load(ScrGameExtension.class, ScrGameExtension.class.getClassLoader())
                         .stream()
                         .map(ServiceLoader.Provider::get)
                         .toList();

    /**
     * Lets every extension create its plugin of a game ({@link ScrGameExtension#createPlugin}).
     *
     * @param game the game, when its SC:R system attaches
     * @return the created plugins in extension order, empty without extensions
     */
    public static @NotNull ScrGamePlugin @NotNull [] createPlugins(@NotNull Game game) {
        List<ScrGamePlugin> plugins = new ArrayList<>(EXTENSIONS.size());

        for (ScrGameExtension extension : EXTENSIONS) {
            ScrGamePlugin plugin = extension.createPlugin(game);

            if (plugin != null) {
                plugins.add(plugin);
            }
        }

        return plugins.toArray(new ScrGamePlugin[0]);
    }

    private ScrGameExtensionUtils() {
    }
}
