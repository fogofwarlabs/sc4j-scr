package org.fogofwarlabs.sc4j.scr.client.ui.menu;

import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Checks the game menu pages of replays, single player and multiplayer games on installed dialogs and texts: the shown
 * buttons, the pause state, the mission outcome, the restart and exit confirmations, the help pages and direct page opening.
 */
@RequiresInstallation
final class GameMenuTest {
    private static final int SAVE_GAME = 1;
    private static final int LOAD_GAME = 2;
    private static final int OPTIONS = 3;
    private static final int HELP = 5;
    private static final int END_MISSION = 7;
    private static final int PAUSE_GAME = 8;
    private static final int RESUME_GAME = 9;
    private static final int RESTART_MISSION = 1;
    private static final int QUIT_MISSION = 2;
    private static final int EXIT_REPLAY = 2;
    private static final int EXIT_PROGRAM = 3;
    private static final int KEYSTROKE_HELP = 1;
    private static final int TIPS = 2;
    private static final int MESSAGE_LIST = 1;
    private static final int TITLE_TEXT = -1;
    private static final int OK = -2;
    private static final int CONFIRM = -2;
    private static final int CANCEL = -3;

    /**
     * A changeable game state.
     */
    private static final class State implements GameMenu.Context {
        private boolean paused;
        private boolean canPause;
        private boolean victorious;

        private State() {
            this.paused     = false;
            this.canPause   = true;
            this.victorious = false;
        }

        /**
         * @return {@code true} if the game is paused, {@code false} otherwise
         */
        @Override
        public boolean isPaused() {
            return this.paused;
        }

        /**
         * @return {@code true} if the local player may pause (a human with pauses left), {@code false} otherwise
         */
        @Override
        public boolean canLocalPlayerPause() {
            return this.canPause;
        }

        /**
         * @return {@code true} if the local player's record is a human (type {@code 2}), {@code false} otherwise
         * @implSpec Returns {@code true} for this fixture.
         */
        @Override
        public boolean isLocalPlayerHuman() {
            return true;
        }

        /**
         * @return {@code true} if the local player is the first active player of the game, {@code false} otherwise
         * @implSpec Returns {@code false} for this fixture.
         */
        @Override
        public boolean isLocalPlayerFirst() {
            return false;
        }

        /**
         * @return {@code true} if the local player is an observer, {@code false} otherwise
         * @implSpec Returns {@code false} for this fixture.
         */
        @Override
        public boolean isLocalPlayerObserver() {
            return false;
        }

        /**
         * @return {@code true} if the local player has won (game of the local player is {@code 3}), {@code false} otherwise
         */
        @Override
        public boolean isLocalPlayerVictorious() {
            return this.victorious;
        }

        /**
         * @return {@code true} if the game page permits Save Game, {@code false} otherwise
         */
        @Override
        public boolean isSaveGameShown() {
            return this.isLocalPlayerFirst();
        }
    }

    private static boolean isControlVisible(@NotNull ScrDialog dialog, int identifier) {
        DialogControl control = dialog.getDefinition().findControl(identifier);

        return control != null && dialog.isVisible(control);
    }

    private static @NotNull String getDrawnControlText(@NotNull ScrDialog dialog, int identifier, @NotNull GluText controls) {
        DialogControl control = dialog.getDefinition().findControl(identifier);

        Assertions.assertNotNull(control);

        return dialog.getDrawnString(control, controls::get).replaceAll("[\\x00-\\x1f]", "");
    }

    @Test
    void replay_menu_hides_game_buttons_and_exits_replay_through_confirmation() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            GluText controls = GluText.load(storage, "enUS", "RC3.json");
            GameMenu menu = new GameMenu(storage, GameMenu.Mode.REPLAY, controls::get);

            menu.toggle();

            ScrDialog game = menu.getDialog();

            Assertions.assertNotNull(game);

            for (int hidden : new int[]{SAVE_GAME, LOAD_GAME, PAUSE_GAME, RESUME_GAME}) {
                Assertions.assertFalse(isControlVisible(game, hidden), "the replay hides control " + hidden);
            }

            Assertions.assertTrue(isControlVisible(game, OPTIONS));
            Assertions.assertTrue(isControlVisible(game, END_MISSION));

            Assertions.assertEquals(GameMenu.Result.NONE, menu.press(END_MISSION));
            Assertions.assertEquals(GameMenu.Page.END_MISSION, menu.getPage());

            ScrDialog end = menu.getDialog();

            Assertions.assertNotNull(end);
            Assertions.assertFalse(isControlVisible(end, RESTART_MISSION), "the replay hides Restart Mission");
            Assertions.assertTrue(isControlVisible(end, EXIT_PROGRAM));
            Assertions.assertEquals(controls.get("abrtmenu-9"), end.requireControl(EXIT_REPLAY).getReplacedText());

            // The button's hotkey follows its new text: 'e' of "Exit Replay".
            Assertions.assertEquals(GameMenu.Result.NONE, menu.pressHotkey('e', controls::get));
            Assertions.assertEquals(GameMenu.Page.QUIT_REPLAY, menu.getPage());
            Assertions.assertEquals(GameMenu.Result.NONE, menu.press(CANCEL));
            Assertions.assertEquals(GameMenu.Page.END_MISSION, menu.getPage());

            menu.press(EXIT_REPLAY);

            Assertions.assertEquals(GameMenu.Result.QUIT_MISSION, menu.press(CONFIRM));
            Assertions.assertFalse(menu.isOpen());

            // Ctrl/Alt+Q opens the replay's confirmation, whose Cancel closes the menu.
            menu.openPage(GameMenu.Page.QUIT_MISSION);

            Assertions.assertEquals(GameMenu.Page.QUIT_REPLAY, menu.getPage());
            Assertions.assertEquals(GameMenu.Result.CLOSED, menu.press(CANCEL));
            Assertions.assertFalse(menu.isOpen());
        }
    }

    @Test
    void single_player_menu_shows_save_game_and_quit_mission() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            GameMenu menu = new GameMenu(storage);

            menu.toggle();

            ScrDialog game = menu.getDialog();

            Assertions.assertNotNull(game);
            Assertions.assertTrue(isControlVisible(game, SAVE_GAME), "single player shows Save Game");

            menu.press(END_MISSION);
            menu.press(EXIT_REPLAY);

            Assertions.assertEquals(GameMenu.Page.QUIT_MISSION, menu.getPage());
        }
    }

    @Test
    void single_player_restart_page_restarts_the_mission_or_goes_back() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            GameMenu menu = new GameMenu(storage);

            menu.toggle();
            menu.press(END_MISSION);

            ScrDialog end = menu.getDialog();

            Assertions.assertNotNull(end);
            Assertions.assertTrue(isControlVisible(end, RESTART_MISSION), "single player shows Restart Mission");
            Assertions.assertEquals(GameMenu.Result.NONE, menu.press(RESTART_MISSION));
            Assertions.assertEquals(GameMenu.Page.RESTART, menu.getPage());
            Assertions.assertEquals(GameMenu.Result.NONE, menu.press(CANCEL));
            Assertions.assertEquals(GameMenu.Page.END_MISSION, menu.getPage());

            menu.press(RESTART_MISSION);

            Assertions.assertEquals(GameMenu.Result.RESTART_MISSION, menu.enter());
            Assertions.assertFalse(menu.isOpen());
        }
    }

    @Test
    void game_page_follows_pause_state() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            GluText controls = GluText.load(storage, "enUS", "RC3.json");
            GameMenu menu = new GameMenu(storage, GameMenu.Mode.MULTIPLAYER, controls::get);
            State state = new State();

            menu.toggle();
            menu.refresh(state, controls::get);

            ScrDialog dialog = menu.getDialog();

            Assertions.assertNotNull(dialog);
            Assertions.assertFalse(isControlVisible(dialog, LOAD_GAME), "multiplayer hides Load Game");
            Assertions.assertFalse(isControlVisible(dialog, SAVE_GAME), "only the first player sees Save Game");
            Assertions.assertTrue(isControlVisible(dialog, PAUSE_GAME));
            Assertions.assertFalse(isControlVisible(dialog, RESUME_GAME));
            Assertions.assertEquals(GameMenu.Result.PAUSE_GAME, menu.press(PAUSE_GAME));
            Assertions.assertEquals(GameMenu.Page.GAME, menu.getPage(), "the page stays open");

            state.paused = true;

            menu.refresh(state, controls::get);

            Assertions.assertFalse(isControlVisible(dialog, PAUSE_GAME));
            Assertions.assertTrue(isControlVisible(dialog, RESUME_GAME));
            Assertions.assertEquals(GameMenu.Result.NONE, menu.press(PAUSE_GAME), "a hidden button does nothing");
            Assertions.assertEquals(GameMenu.Result.RESUME_GAME, menu.press(RESUME_GAME));

            state.paused   = false;
            state.canPause = false;

            menu.refresh(state, controls::get);

            Assertions.assertFalse(isControlVisible(dialog, PAUSE_GAME), "no pauses left");
        }
    }

    @Test
    void end_mission_page_offers_surrender_or_victory() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            GluText controls = GluText.load(storage, "enUS", "RC3.json");
            GameMenu menu = new GameMenu(storage, GameMenu.Mode.MULTIPLAYER, controls::get);
            State state = new State();

            menu.openPage(GameMenu.Page.END_MISSION);
            menu.refresh(state, controls::get);

            ScrDialog dialog = menu.getDialog();

            Assertions.assertNotNull(dialog);
            Assertions.assertFalse(isControlVisible(dialog, RESTART_MISSION), "multiplayer hides Restart Mission");
            Assertions.assertEquals("Surrender", getDrawnControlText(dialog, QUIT_MISSION, controls));

            state.victorious = true;

            menu.refresh(state, controls::get);

            Assertions.assertEquals("Victory", getDrawnControlText(dialog, QUIT_MISSION, controls));
        }
    }

    @Test
    void accelerator_pages_open_at_depth_one_and_cancel_closes_menu() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            GluText controls = GluText.load(storage, "enUS", "RC3.json");
            GameMenu menu = new GameMenu(storage, GameMenu.Mode.SINGLE_PLAYER, controls::get);

            menu.openPage(GameMenu.Page.QUIT_MISSION);

            Assertions.assertEquals(1, menu.getDepth());
            Assertions.assertEquals(GameMenu.Result.CLOSED, menu.escape(), "Cancel closes the page opened by Ctrl+Q");
            Assertions.assertFalse(menu.isOpen());

            menu.toggle();
            menu.press(END_MISSION);
            menu.press(QUIT_MISSION);

            Assertions.assertEquals(GameMenu.Page.QUIT_MISSION, menu.getPage());
            Assertions.assertEquals(GameMenu.Result.NONE, menu.escape(), "Cancel of a page reached from the menu goes back");
            Assertions.assertEquals(GameMenu.Page.END_MISSION, menu.getPage());
        }
    }

    @Test
    void message_history_opens_at_depth_one() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            GluText controls = GluText.load(storage, "enUS", "RC3.json");
            GameMenu menu = new GameMenu(storage, GameMenu.Mode.MULTIPLAYER, controls::get);

            menu.openPage(GameMenu.Page.CHAT_HISTORY);

            ScrDialog dialog = menu.getDialog();

            Assertions.assertNotNull(dialog);

            DialogControl list = dialog.getDefinition().findControl(MESSAGE_LIST);

            Assertions.assertNotNull(list);
            Assertions.assertEquals(ScrControlType.LISTBOX, ScrDialogControlUtils.getType(list));
            Assertions.assertEquals("Message History", getDrawnControlText(dialog, TITLE_TEXT, controls));
            Assertions.assertEquals(GameMenu.Result.CLOSED, menu.escape(), "OK (-2, also Esc) closes the history opened by its accelerator");
            Assertions.assertFalse(menu.isOpen());
        }
    }

    @Test
    void help_pages_open_and_go_back() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            GluText controls = GluText.load(storage, "enUS", "RC3.json");
            GameMenu menu = new GameMenu(storage, GameMenu.Mode.SINGLE_PLAYER, controls::get);

            menu.toggle();
            menu.press(HELP);

            Assertions.assertEquals(GameMenu.Page.HELP, menu.getPage());

            Assertions.assertEquals(GameMenu.Result.KEYSTROKE_HELP, menu.press(KEYSTROKE_HELP));
            Assertions.assertEquals(GameMenu.Page.KEYSTROKE_HELP, menu.getPage());
            Assertions.assertEquals(GameMenu.Result.NONE, menu.escape(), "Esc is OK (CTRL_ESCAPE) and goes back to the Help page");
            Assertions.assertEquals(GameMenu.Page.HELP, menu.getPage());

            Assertions.assertEquals(GameMenu.Result.TIPS, menu.press(TIPS));
            Assertions.assertEquals(GameMenu.Page.TIPS, menu.getPage());
            Assertions.assertEquals(GameMenu.Result.NEXT_TIP, menu.pressHotkey('n', controls::get));
            Assertions.assertEquals(GameMenu.Result.PREVIOUS_TIP, menu.pressHotkey('p', controls::get));

            ScrDialog tips = menu.getDialog();

            Assertions.assertNotNull(tips);
            Assertions.assertFalse(tips.requireControl(GameMenu.TIPS_AT_STARTUP_BOX).isChecked());
            Assertions.assertEquals(GameMenu.Result.TIPS_AT_STARTUP, menu.pressHotkey('s', controls::get));
            Assertions.assertTrue(tips.requireControl(GameMenu.TIPS_AT_STARTUP_BOX).isChecked(), "the hotkey turns the box over as a click does");
            Assertions.assertEquals(GameMenu.Result.NONE, menu.press(OK));
            Assertions.assertEquals(GameMenu.Page.HELP, menu.getPage());

            // Opened by its accelerator path (the mission start), the page is at depth 1 and OK closes the menu.
            menu.openPage(GameMenu.Page.TIPS);

            Assertions.assertEquals(GameMenu.Result.CLOSED, menu.press(OK));
            Assertions.assertNull(menu.getPage());
        }
    }
}
