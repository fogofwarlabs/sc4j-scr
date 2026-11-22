package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrDrawList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks the local display: the classic screen until the client supplies one, the supplied screen and font measure, and the refusal of an empty
 * screen.
 */
final class LocalDisplayTest {
    private static final int WIDE_WIDTH = 853;
    private static final float HD_SCALE = 2.25f;
    private static final float MEASURED_WIDTH = 17.5f;

    @Test
    void local_display_starts_as_the_classic_screen() {
        LocalDisplay display = new LocalDisplay();

        Assertions.assertFalse(display.isKnown());
        Assertions.assertEquals(LocalDisplay.CLASSIC_WIDTH, display.getInterfaceWidth());
        Assertions.assertEquals(LocalDisplay.CLASSIC_HEIGHT, display.getInterfaceHeight());
        Assertions.assertEquals(1, display.getScale());
        Assertions.assertEquals(AssetResolver.AssetSet.SD, display.getGraphics());
        Assertions.assertEquals(0, display.measureText("text", ScrDrawList.Font.MEDIUM));
    }

    @Test
    void set_screen_should_keep_the_supplied_screen_and_measure() {
        LocalDisplay display = new LocalDisplay();

        display.setScreen(WIDE_WIDTH, LocalDisplay.CLASSIC_HEIGHT, HD_SCALE, AssetResolver.AssetSet.HD);
        display.setTextMeasure((text, font) -> font == ScrDrawList.Font.LARGE ? MEASURED_WIDTH : 0);

        Assertions.assertTrue(display.isKnown());
        Assertions.assertEquals(WIDE_WIDTH, display.getInterfaceWidth());
        Assertions.assertEquals(HD_SCALE, display.getScale());
        Assertions.assertEquals(AssetResolver.AssetSet.HD, display.getGraphics());
        Assertions.assertEquals(MEASURED_WIDTH, display.measureText("text", ScrDrawList.Font.LARGE));
        Assertions.assertThrows(IllegalArgumentException.class, () -> display.setScreen(0, LocalDisplay.CLASSIC_HEIGHT, 1, AssetResolver.AssetSet.SD));
        Assertions.assertThrows(IllegalArgumentException.class, () -> display.setScreen(WIDE_WIDTH, LocalDisplay.CLASSIC_HEIGHT, 0,
                                                                                        AssetResolver.AssetSet.SD));
    }
}
