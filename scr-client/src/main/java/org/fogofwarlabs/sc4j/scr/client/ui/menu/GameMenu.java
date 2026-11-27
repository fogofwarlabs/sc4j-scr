package org.fogofwarlabs.sc4j.scr.client.ui.menu;

import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogImportUtils;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Retains game-menu pages, mode-dependent dialog state and the Previous depth rule.
 * Page dialogs load in enum order. Opening a page clears its hovered and pressed controls, then advances the depth.
 * Buttons return client actions or open another page; runtime multiplayer visibility is refreshed from the supplied context.
 */
public final class GameMenu {
    private static final int OPTIONS_BUTTON = 3;
    private static final int HELP_BUTTON = 5;
    private static final int OBJECTIVES_BUTTON = 6;
    private static final int END_MISSION_BUTTON = 7;
    private static final int EXIT_PROGRAM_BUTTON = 3;

    /**
     * {@code gamemenu}: Save Game.
     */
    private static final int SAVE_GAME_BUTTON = 1;
    /**
     * {@code gamemenu}: Load Game.
     */
    private static final int LOAD_GAME_BUTTON = 2;
    /**
     * {@code gamemenu}: Social (multiplayer only).
     */
    private static final int SOCIAL_BUTTON = 4;
    /**
     * {@code gamemenu}: Pause Game.
     */
    private static final int PAUSE_BUTTON = 8;
    /**
     * {@code gamemenu}: Resume Game.
     */
    private static final int RESUME_BUTTON = 9;
    /**
     * {@code abrtmenu}: Restart Mission.
     */
    private static final int RESTART_BUTTON = 1;
    /**
     * {@code abrtmenu}: Quit Mission, or Exit Replay in the replay viewer.
     */
    private static final int QUIT_BUTTON = 2;
    /**
     * {@code abrtmenu}: the hidden text control whose string the replay viewer's button {@code 2} shows ("Exit Replay").
     */
    private static final int EXIT_REPLAY_TEXT = -13;
    /**
     * {@code helpmenu}: Keystroke Help.
     */
    private static final int KEYSTROKE_HELP_BUTTON = 1;
    /**
     * {@code helpmenu}: StarCraft Tips (with argument {@code 0}).
     */
    private static final int TIPS_BUTTON = 2;
    /**
     * {@code tips_dlg}: Next Tip (+1).
     */
    public static final int NEXT_TIP_BUTTON = 1;
    /**
     * {@code tips_dlg}: Show Tips at Startup.
     */
    public static final int TIPS_AT_STARTUP_BOX = 2;
    /**
     * {@code tips_dlg}: Previous Tip ({@code -1}).
     */
    public static final int PREVIOUS_TIP_BUTTON = 3;
    /**
     * The tip text control identifier.
     */
    public static final int TIP_TEXT = -10;
    /**
     * {@code quit2mnu}: control {@code 1}, which the page init hides.
     */
    private static final int QUIT_MISSION_HIDDEN_CONTROL = 1;
    /**
     * {@code abrtmenu}: the hidden multiplayer texts of button {@code 2}: "Surrender", "Victory", "Quit".
     */
    private static final int SURRENDER_TEXT = -10;
    private static final int VICTORY_TEXT = -11;
    private static final int QUIT_TEXT = -14;

    /**
     * Who the menu belongs to.
     */
    public enum Mode {
        /**
         * A single-player game.
         */
        SINGLE_PLAYER,
        /**
         * A multiplayer game.
         */
        MULTIPLAYER,
        /**
         * The replay viewer.
         */
        REPLAY
    }

    /**
     * The game state the multiplayer page inits read.
     */
    public interface Context {
        /**
         * @return {@code true} if the game is paused, {@code false} otherwise
         */
        boolean isPaused();

        /**
         * @return {@code true} if the local player's record is a human (type {@code 2}), {@code false} otherwise
         */
        boolean isLocalPlayerHuman();

        /**
         * @return {@code true} if the local player is the first active player of the game, {@code false} otherwise
         */
        boolean isLocalPlayerFirst();

        /**
         * @return {@code true} if the local player is an observer, {@code false} otherwise
         */
        boolean isLocalPlayerObserver();

        /**
         * @return {@code true} if the local player has won (game of the local player is {@code 3}), {@code false} otherwise
         */
        boolean isLocalPlayerVictorious();

        /**
         * @return {@code true} if the game page permits Save Game, {@code false} otherwise
         */
        boolean isSaveGameShown();

        /**
         * @return {@code true} if the local player may pause (a human with pauses left), {@code false} otherwise
         */
        boolean canLocalPlayerPause();
    }

    /**
     * What a button press asks the client to do.
     */
    public enum Result {
        /**
         * Nothing beyond the menu itself.
         */
        NONE,
        /**
         * The menu closed; the game resumes.
         */
        CLOSED,
        /**
         * Quit Mission: game end code {@code 4}.
         */
        QUIT_MISSION,
        /**
         * Restart Mission: the Restart Game command {@code 0x08} is sent; the game ends and starts again.
         */
        RESTART_MISSION,
        /**
         * Exit Program: game end code {@code 2}.
         */
        EXIT_PROGRAM,
        /**
         * Options: the menu closes and the options popup opens over the game (the same popup as the main menu's).
         */
        OPTIONS,
        /**
         * Pause Game: the Pause command {@code 0x10} is sent; the page stays.
         */
        PAUSE_GAME,
        /**
         * Resume Game: the Resume command {@code 0x11} is sent; the page stays.
         */
        RESUME_GAME,
        /**
         * The Keystroke Help page opened: its list is filled.
         */
        KEYSTROKE_HELP,
        /**
         * The StarCraft Tips page opened from the Help page: the tip after the stored one shows.
         */
        TIPS,
        /**
         * Next Tip: the following tip shows.
         */
        NEXT_TIP,
        /**
         * Previous Tip: the tip before shows.
         */
        PREVIOUS_TIP,
        /**
         * Show Tips at Startup was turned over: the page's check box holds the new choice.
         */
        TIPS_AT_STARTUP,
        /**
         * Save Game: the menu closes and the save dialog opens.
         */
        SAVE_GAME,
        /**
         * Load Game: the menu closes and the load dialog opens.
         */
        LOAD_GAME
    }

    /**
     * The menu pages.
     */
    public enum Page {
        /**
         * gamemenu.ui.json.
         */
        GAME("gamemenu"),
        /**
         * abrtmenu.ui.json.
         */
        END_MISSION("abrtmenu"),
        /**
         * restart.ui.json.
         */
        RESTART("restart"),
        /**
         * quit2mnu.ui.json.
         */
        QUIT_MISSION("quit2mnu"),
        /**
         * quitrepl.ui.json: the replay viewer's confirmation.
         */
        QUIT_REPLAY("quitrepl"),
        /**
         * quit.ui.json.
         */
        EXIT_PROGRAM("quit"),
        /**
         * helpmenu.ui.json.
         */
        HELP("helpmenu"),
        /**
         * help.ui.json: Keystroke Help (procedure); its OK ({@code -2}, also Esc) goes back to the Help page by the depth rule, else closes.
         */
        KEYSTROKE_HELP("help"),
        /**
         * tips_dlg.ui.json: StarCraft Tips (procedure); its OK ({@code -2}, also Esc) goes back to the Help page by the depth rule, else closes.
         */
        TIPS("tips_dlg"),
        /**
         * objctdlg.ui.json.
         */
        OBJECTIVES("objctdlg"),
        /**
         * chathist.ui.json: the multiplayer message history (procedure); its OK ({@code -2}, also Esc) goes back to the Help page by the depth rule,
         * else closes.
         */
        CHAT_HISTORY("chathist");

        private final @NotNull String file;

        Page(@NotNull String file) {
            this.file = file;
        }

        /**
         * @return the dialog file name without {@code .ui.json}
         */
        public @NotNull String getFile() {
            return this.file;
        }
    }

    /**
     * Creates a {@link ScrDialog} instance with mode-dependent visibility, replay labels and tip alignment.
     */
    private static @NotNull ScrDialog createDialog(@NotNull Page page,
                                                   @NotNull DialogDefinition definition,
                                                   @NotNull Mode mode,
                                                   @NotNull Function<String, String> controlTexts) {
        ScrDialog dialog = new ScrDialog(definition);

        // The pages are game popups: their default button has the focus (the return button's outline).
        dialog.setDefaultButtonFocus(true);

        boolean replay = mode == Mode.REPLAY;

        switch (page) {
            case GAME -> {
                for (int hidden : new int[]{PAUSE_BUTTON, RESUME_BUTTON, SOCIAL_BUTTON}) {
                    setVisible(dialog.findControl(hidden), false);
                }

                if (replay) {
                    setVisible(dialog.findControl(SAVE_GAME_BUTTON), false);
                    setVisible(dialog.findControl(LOAD_GAME_BUTTON), false);
                }

                // Hides Load Game in a multiplayer game too; Save, Pause and Resume follow the game (refresh).
                if (mode == Mode.MULTIPLAYER) {
                    setVisible(dialog.findControl(LOAD_GAME_BUTTON), false);
                }
            }
            case END_MISSION -> {
                if (mode == Mode.MULTIPLAYER) {
                    setVisible(dialog.findControl(RESTART_BUTTON), false);
                }

                if (replay) {
                    setVisible(dialog.findControl(RESTART_BUTTON), false);

                    DialogControl exitReplay = ScrDialogControlUtils.findControl(definition, EXIT_REPLAY_TEXT);

                    ScrControl quit = dialog.findControl(QUIT_BUTTON);

                    if (exitReplay != null && quit != null) {
                        quit.setReplacedText(dialog.getControlString(exitReplay, controlTexts));
                    }
                }
            }
            case QUIT_MISSION -> setVisible(dialog.findControl(QUIT_MISSION_HIDDEN_CONTROL), false);
            case TIPS -> {
                ScrControl tip = dialog.findControl(TIP_TEXT);

                if (tip != null) {
                    tip.setTextFormat(InterfaceText.LEFT | InterfaceText.TOP);
                }
            }
            default -> {
            }
        }

        return dialog;
    }

    /**
     * Shows or hides a control a menu page names by id; a page without the control (the game page of a dialog file without the
     * social button) changes nothing.
     *
     * @param control the control, or {@code null} if the page has none with the id
     * @param visible whether the control is drawn and takes input
     */
    private static void setVisible(@Nullable ScrControl control, boolean visible) {
        if (control != null) {
            control.setVisible(visible);
        }
    }

    private static boolean isEnabled(@NotNull ScrDialog dialog, int identifier) {
        for (DialogControl control : dialog.getDefinition().getControls()) {
            if (control.getId() == identifier && dialog.isEnabled(control)) {
                return true;
            }
        }

        return false;
    }

    private final @NotNull Mode mode;
    private final @NotNull Map<Page, ScrDialog> dialogs;
    private final @NotNull Map<Page, Page> parents;

    private @Nullable Page page;
    private int depth;

    /**
     * Creates a {@link GameMenu} instance for a single-player game.
     *
     * @param storage the installed game storage
     * @throws IOException if a dialog file is missing
     */
    public GameMenu(@NotNull CascStorage storage) throws IOException {
        this(storage, Mode.SINGLE_PLAYER, key -> "");
    }

    /**
     * Creates a {@link GameMenu} instance: loads the page layouts and applies the mode's control state.
     *
     * @param storage      the installed game storage
     * @param mode         who the menu belongs to
     * @param controlTexts resolves a content key through the control strings ({@code RC3})
     * @throws IOException if a dialog file is missing
     */
    public GameMenu(@NotNull CascStorage storage, @NotNull Mode mode, @NotNull Function<String, String> controlTexts) throws IOException {
        this.mode    = mode;
        this.dialogs = new HashMap<>();
        this.parents = new HashMap<>();

        this.page  = null;
        this.depth = 0;

        for (Page value : Page.values()) {
            this.dialogs.put(value, createDialog(value, ScrDialogImportUtils.load(storage, value.getFile()), mode, controlTexts));
        }

        this.parents.put(Page.END_MISSION, Page.GAME);
        this.parents.put(Page.HELP, Page.GAME);
        this.parents.put(Page.OBJECTIVES, Page.GAME);
        this.parents.put(Page.RESTART, Page.END_MISSION);
        this.parents.put(Page.QUIT_MISSION, Page.END_MISSION);
        this.parents.put(Page.QUIT_REPLAY, Page.END_MISSION);
        this.parents.put(Page.EXIT_PROGRAM, Page.END_MISSION);
        this.parents.put(Page.CHAT_HISTORY, Page.HELP);
        this.parents.put(Page.KEYSTROKE_HELP, Page.HELP);
        this.parents.put(Page.TIPS, Page.HELP);
    }

    /**
     * @return who the menu belongs to
     */
    public @NotNull Mode getMode() {
        return this.mode;
    }

    /**
     * @return the menu depth (byte): the pages entered since the popup opened, less the ones left
     */
    public int getDepth() {
        return this.depth;
    }

    /**
     * @return {@code true} if a page is open, {@code false} otherwise
     */
    public boolean isOpen() {
        return this.page != null;
    }

    /**
     * @return the open page, or {@code null}
     */
    public @Nullable Page getPage() {
        return this.page;
    }

    /**
     * @return the open page's dialog, or {@code null}
     */
    public @Nullable ScrDialog getDialog() {
        return this.page == null ? null : this.dialogs.get(this.page);
    }


    /**
     * Refreshes game-dependent controls of the current nonreplay page.
     * Save visibility is applied before multiplayer pause/resume or quit-label updates.
     *
     * @param context      the game state
     * @param controlTexts resolves a content key through the control strings
     */
    public void refresh(@NotNull Context context, @NotNull Function<String, String> controlTexts) {
        Page current = this.page;

        if (current == null || this.mode == Mode.REPLAY) {
            return;
        }

        ScrDialog dialog = this.dialogs.get(current);

        if (current == Page.GAME) {
            setVisible(dialog.findControl(SAVE_GAME_BUTTON), context.isSaveGameShown());
        }

        if (this.mode != Mode.MULTIPLAYER) {
            return;
        }

        if (current == Page.GAME) {
            boolean paused = context.isPaused();

            setVisible(dialog.findControl(PAUSE_BUTTON), context.canLocalPlayerPause() && !paused);
            setVisible(dialog.findControl(RESUME_BUTTON), context.isLocalPlayerHuman() && paused);
        } else if (current == Page.END_MISSION) {
            int label = context.isLocalPlayerObserver() ? QUIT_TEXT : context.isLocalPlayerVictorious() ? VICTORY_TEXT : SURRENDER_TEXT;
            DialogControl text = ScrDialogControlUtils.findControl(dialog.getDefinition(), label);
            ScrControl quit = dialog.findControl(QUIT_BUTTON);

            if (text != null && quit != null) {
                quit.setReplacedText(dialog.getControlString(text, controlTexts));
            }
        }
    }


    /**
     * Closes any current page and opens the requested page at depth one.
     * Replay mode substitutes its replay-quit confirmation for the mission-quit page.
     *
     * @param page the page
     */
    public void openPage(@NotNull Page page) {
        this.close();
        this.open(page == Page.QUIT_MISSION && this.mode == Mode.REPLAY ? Page.QUIT_REPLAY : page);
    }

    /**
     * Opens the game menu page or closes the menu when it is open.
     */
    public void toggle() {
        if (this.page != null) {
            this.close();

            return;
        }

        this.depth = 0;

        this.open(Page.GAME);
    }

    private void open(@NotNull Page page) {
        ScrDialog dialog = this.dialogs.get(page);

        dialog.setHovered(null);
        dialog.cancelPress();

        this.page = page;
        this.depth++;
    }

    /**
     * Presses the control a key activates on the open page: the first enabled control whose {@code WS_HOTKEY} string
     * starts with the key ({@link ScrDialog#findKeyControl}).
     *
     * @param key          the typed character
     * @param controlTexts resolves a content key through the control strings
     * @return what the client must do
     */
    public @NotNull Result pressHotkey(char key, @NotNull Function<String, String> controlTexts) {
        ScrDialog dialog = this.getDialog();

        if (dialog == null) {
            return Result.NONE;
        }

        DialogControl control = dialog.findKeyControl(key, false, false, controlTexts);

        if (control == null) {
            return Result.NONE;
        }

        // A hotkey activates a check box as its release does (^= 1).
        if (ScrDialogControlUtils.getType(control) == ScrControlType.CHECKBOX) {
            ScrControl box = dialog.requireControl(control);

            box.setChecked(!box.isChecked());
        }

        return this.press(control.getId());
    }

    /**
     * Activates a control of the open page, as the page functions switch on the pressed id.
     *
     * @param identifier the control id
     * @return what the client must do
     */
    public @NotNull Result press(int identifier) {
        Page current = this.page;

        if (current == null || !isEnabled(this.dialogs.get(current), identifier)) {
            return Result.NONE;
        }

        switch (current) {
            case GAME -> {
                switch (identifier) {
                    case ScrDialog.ESCAPE_REQUEST -> {
                        this.close();

                        return Result.CLOSED;
                    }
                    case SAVE_GAME_BUTTON -> {
                        return Result.SAVE_GAME;
                    }
                    case LOAD_GAME_BUTTON -> {
                        return Result.LOAD_GAME;
                    }
                    case OPTIONS_BUTTON -> {
                        return Result.OPTIONS;
                    }
                    case HELP_BUTTON -> this.open(Page.HELP);
                    case OBJECTIVES_BUTTON -> this.open(Page.OBJECTIVES);
                    case END_MISSION_BUTTON -> this.open(Page.END_MISSION);
                    case PAUSE_BUTTON -> {
                        return Result.PAUSE_GAME;
                    }
                    case RESUME_BUTTON -> {
                        return Result.RESUME_GAME;
                    }
                    default -> {
                    }
                }
            }
            case END_MISSION -> {
                switch (identifier) {
                    case ScrDialog.ESCAPE_REQUEST -> {
                        return this.previous();
                    }
                    case RESTART_BUTTON -> this.open(Page.RESTART);
                    case QUIT_BUTTON -> this.open(this.mode == Mode.REPLAY ? Page.QUIT_REPLAY : Page.QUIT_MISSION);
                    case EXIT_PROGRAM_BUTTON -> this.open(Page.EXIT_PROGRAM);
                    default -> {
                    }
                }
            }
            case QUIT_MISSION, QUIT_REPLAY -> {
                if (identifier == ScrDialog.ENTER_REQUEST) {
                    this.close();

                    return Result.QUIT_MISSION;
                }

                if (identifier == ScrDialog.ESCAPE_REQUEST) {
                    return this.previous();
                }
            }
            case EXIT_PROGRAM -> {
                if (identifier == ScrDialog.ENTER_REQUEST) {
                    this.close();

                    return Result.EXIT_PROGRAM;
                }

                if (identifier == ScrDialog.ESCAPE_REQUEST) {
                    return this.previous();
                }
            }
            case CHAT_HISTORY, KEYSTROKE_HELP -> {
                if (identifier == ScrDialog.ENTER_REQUEST) {
                    return this.previous();
                }
            }
            case HELP -> {
                switch (identifier) {
                    case ScrDialog.ESCAPE_REQUEST -> {
                        return this.previous();
                    }
                    case KEYSTROKE_HELP_BUTTON -> {
                        this.open(Page.KEYSTROKE_HELP);

                        return Result.KEYSTROKE_HELP;
                    }
                    case TIPS_BUTTON -> {
                        this.open(Page.TIPS);

                        return Result.TIPS;
                    }
                    default -> {
                    }
                }
            }
            case TIPS -> {
                switch (identifier) {
                    case ScrDialog.ENTER_REQUEST -> {
                        return this.previous();
                    }
                    case NEXT_TIP_BUTTON -> {
                        return Result.NEXT_TIP;
                    }
                    case PREVIOUS_TIP_BUTTON -> {
                        return Result.PREVIOUS_TIP;
                    }
                    case TIPS_AT_STARTUP_BOX -> {
                        return Result.TIPS_AT_STARTUP;
                    }
                    default -> {
                    }
                }
            }
            case RESTART -> {
                if (identifier == ScrDialog.ESCAPE_REQUEST) {
                    return this.previous();
                }

                if (identifier == ScrDialog.ENTER_REQUEST) {
                    this.close();

                    return Result.RESTART_MISSION;
                }
            }
            case OBJECTIVES -> {
                if (identifier == ScrDialog.ESCAPE_REQUEST || identifier == ScrDialog.ENTER_REQUEST) {
                    return this.previous();
                }
            }
        }

        return Result.NONE;
    }

    /**
     * Routes Escape through the dialog activation chain and returns the page action selected by the callback.
     *
     * @return what the client must do
     */
    public @NotNull Result escape() {
        ScrDialog dialog = this.getDialog();
        Result[] result = {Result.NONE};

        if (dialog != null) {
            dialog.escape(control -> this.activate(control, result));
        }

        return result[0];
    }

    /**
     * Routes Enter through the dialog focus and activation chain and returns the selected page action.
     *
     * @return what the client must do
     */
    public @NotNull Result enter() {
        ScrDialog dialog = this.getDialog();
        Result[] result = {Result.NONE};

        if (dialog != null) {
            dialog.enter(control -> this.activate(control, result));
        }

        return result[0];
    }

    /**
     * Answers an activation as the page procedure does: the activation is handled when the page has that enabled control.
     */
    private boolean activate(int control, @NotNull Result @NotNull [] result) {
        Page current = this.page;

        if (current == null) {
            return false;
        }

        if (control == ScrDialog.ESCAPE_REQUEST && current == Page.OBJECTIVES) {
            result[0] = this.previous();

            return true;
        }

        if (!isEnabled(this.dialogs.get(current), control)) {
            return false;
        }

        result[0] = this.press(control);

        return true;
    }

    /**
     * Goes back as "Previous (Esc)" does: the depth decreases; at {@code 0} the dialog closes, else it decreases again and the
     * parent page opens (its init adds {@code 1} back).
     */
    private @NotNull Result previous() {
        this.depth--;

        if (this.depth <= 0 || this.page == null) {
            this.close();

            return Result.CLOSED;
        }

        this.depth--;

        this.open(this.parents.getOrDefault(this.page, Page.GAME));

        return Result.NONE;
    }


    /**
     * Closes the menu (depth {@code 0}).
     */
    public void close() {
        this.page  = null;
        this.depth = 0;
    }
}
