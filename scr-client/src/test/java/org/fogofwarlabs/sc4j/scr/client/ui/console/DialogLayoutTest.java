package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * Checks a placed dialog layout: the dialog moves with the controls that keep their place in it, a control placed on its own lies at its place,
 * and the dialog's runtime state built from the placed definition hits the controls where the layout draws them.
 */
final class DialogLayoutTest {
    private static final int FIRST = 1;
    private static final int SECOND = 2;
    private static final int DIALOG_LEFT = 100;
    private static final int DIALOG_TOP = 200;
    private static final int DIALOG_SIZE = 80;
    private static final int CONTROL_SIZE = 20;
    private static final int FIRST_LEFT = 5;
    private static final int SECOND_LEFT = 40;
    private static final int CONTROL_TOP = 10;
    private static final int MOVED_LEFT = 300;
    private static final int MOVED_TOP = 50;
    private static final int PLACED_FIRST_LEFT = 20;
    private static final int PLACED_FIRST_TOP = 30;

    private static @NotNull DialogControl createButton(int identifier, int left) {
        return ScrDialogControlUtils.createControl(ScrControlType.PUSHBUTTON, identifier, left, CONTROL_TOP, CONTROL_SIZE, CONTROL_SIZE,
                                                   Set.of("WS_VISIBLE"), "", List.of());
    }

    private static @NotNull DialogLayout createLayout() {
        return DialogLayout.of(new DialogDefinition("test", DIALOG_LEFT, DIALOG_TOP, DIALOG_SIZE, DIALOG_SIZE,
                                                    List.of(createButton(FIRST, FIRST_LEFT), createButton(SECOND, SECOND_LEFT))));
    }

    @Test
    void a_placed_control_lies_at_its_place_and_the_others_move_with_the_dialog() {
        DialogLayout placed = createLayout().createPlacedLayout(MOVED_LEFT, MOVED_TOP,
                                                                identifier -> identifier == FIRST ? new int[]{PLACED_FIRST_LEFT, PLACED_FIRST_TOP}
                                                                                                  : null);

        Assertions.assertEquals(MOVED_LEFT, placed.getX());
        Assertions.assertEquals(MOVED_TOP, placed.getY());
        Assertions.assertEquals(DIALOG_SIZE, placed.getWidth());
        Assertions.assertEquals(PLACED_FIRST_LEFT, placed.getControl(FIRST).getX());
        Assertions.assertEquals(PLACED_FIRST_TOP, placed.getControl(FIRST).getY());
        Assertions.assertEquals(MOVED_LEFT + SECOND_LEFT, placed.getControl(SECOND).getX());
        Assertions.assertEquals(MOVED_TOP + CONTROL_TOP, placed.getControl(SECOND).getY());
        Assertions.assertEquals(CONTROL_SIZE, placed.getControl(FIRST).getWidth());
    }

    @Test
    void the_runtime_state_of_a_placed_dialog_hits_the_controls_at_their_places() {
        DialogLayout placed = createLayout().createPlacedLayout(MOVED_LEFT, MOVED_TOP,
                                                                identifier -> identifier == FIRST ? new int[]{PLACED_FIRST_LEFT, PLACED_FIRST_TOP}
                                                                                                  : null);
        ScrDialog dialog = new ScrDialog(placed.getDefinition());
        ScrControl first = dialog.findControlAt(PLACED_FIRST_LEFT + 1 - placed.getX(), PLACED_FIRST_TOP + 1 - placed.getY());
        ScrControl second = dialog.findControlAt(MOVED_LEFT + SECOND_LEFT + 1 - placed.getX(), MOVED_TOP + CONTROL_TOP + 1 - placed.getY());

        Assertions.assertNotNull(first);
        Assertions.assertEquals(FIRST, first.getDefinition().getId());
        Assertions.assertNotNull(second);
        Assertions.assertEquals(SECOND, second.getDefinition().getId());
        Assertions.assertNull(dialog.findControlAt(FIRST_LEFT + 1, CONTROL_TOP + 1), "the first control left its place in the dialog");
    }
}
