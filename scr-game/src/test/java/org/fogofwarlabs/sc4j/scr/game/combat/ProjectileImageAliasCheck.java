package org.fogofwarlabs.sc4j.scr.game.combat;

import org.jetbrains.annotations.NotNull;

/**
 * Computes whether a write through one page is visible through another page. It is not part of the projectile simulation.
 */
final class ProjectileImageAliasCheck {
    /**
     * The offset of the probed byte, the last byte of a page.
     */
    private static final int PROBE_OFFSET = 4095;
    /**
     * The amount the probe adds to the byte of the alias page.
     */
    private static final int PROBE_INCREMENT = 247;

    /**
     * Changes the probed byte through the alias page, tests whether the image page sees the change and restores the byte.
     *
     * @param imagePage the image page
     * @param aliasPage the page that may alias {@code imagePage}
     * @return {@code true} if the write through {@code aliasPage} changed the probed byte of {@code imagePage}, {@code false} otherwise
     */
    static boolean isImagePageAliased(byte @NotNull [] imagePage, byte @NotNull [] aliasPage) {
        byte previousValue = imagePage[PROBE_OFFSET];

        aliasPage[PROBE_OFFSET] = (byte) (aliasPage[PROBE_OFFSET] + PROBE_INCREMENT);

        boolean firstViewChanged = imagePage[PROBE_OFFSET] != previousValue;

        aliasPage[PROBE_OFFSET] = previousValue;

        return firstViewChanged;
    }


    /**
     * Creates a {@link ProjectileImageAliasCheck} instance.
     */
    private ProjectileImageAliasCheck() {
    }
}
