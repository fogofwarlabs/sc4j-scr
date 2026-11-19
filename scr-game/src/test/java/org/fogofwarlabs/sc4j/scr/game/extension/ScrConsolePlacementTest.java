package org.fogofwarlabs.sc4j.scr.game.extension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks the console placement: dialogs keep their place until moved or hidden, the console is shown everywhere on the screen until a mask pixel
 * is set, and the change count follows the moves, the hidden dialogs and the mask changes.
 */
final class ScrConsolePlacementTest {
    private static final int WIDTH = 4;
    private static final int HEIGHT = 3;
    private static final int LEFT = 2;
    private static final int TOP = 1;

    @Test
    void dialogs_move_and_hide_and_count_their_changes() {
        ScrConsolePlacement placement = new ScrConsolePlacement(WIDTH, HEIGHT);

        Assertions.assertFalse(placement.isDialogMoved(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertThrows(IllegalStateException.class, () -> placement.getDialogLeft(ScrConsolePlacement.Dialog.MENU));

        placement.moveDialog(ScrConsolePlacement.Dialog.MENU, LEFT, TOP);
        placement.moveDialog(ScrConsolePlacement.Dialog.MENU, LEFT, TOP);

        Assertions.assertTrue(placement.isDialogMoved(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(LEFT, placement.getDialogLeft(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(TOP, placement.getDialogTop(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(1, placement.getChangeCount());

        placement.hideDialog(ScrConsolePlacement.Dialog.MENU);
        placement.hideDialog(ScrConsolePlacement.Dialog.MENU);

        Assertions.assertTrue(placement.isDialogHidden(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(2, placement.getChangeCount());

        placement.moveDialog(ScrConsolePlacement.Dialog.MENU, LEFT, TOP);

        Assertions.assertFalse(placement.isDialogHidden(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertFalse(placement.isDialogMoved(ScrConsolePlacement.Dialog.COMMAND_CARD));
        Assertions.assertEquals(3, placement.getChangeCount());
    }

    @Test
    void the_mask_shows_the_console_only_where_it_is_set() {
        ScrConsolePlacement placement = new ScrConsolePlacement(WIDTH, HEIGHT);

        Assertions.assertFalse(placement.isMasked());
        Assertions.assertTrue(placement.isShown(LEFT, TOP));
        Assertions.assertFalse(placement.isShown(WIDTH, TOP));

        placement.setMaskPixel(TOP * WIDTH + LEFT, true);
        placement.setMaskPixel(TOP * WIDTH + LEFT, true);

        Assertions.assertTrue(placement.isMasked());
        Assertions.assertTrue(placement.isShown(LEFT, TOP));
        Assertions.assertFalse(placement.isShown(LEFT - 1, TOP));
        Assertions.assertEquals(1, placement.getChangeCount());
        Assertions.assertEquals(WIDTH, placement.getSurfaces().getWidth());
    }
}
