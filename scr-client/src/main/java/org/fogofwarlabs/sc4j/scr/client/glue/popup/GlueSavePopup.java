package org.fogofwarlabs.sc4j.scr.client.glue.popup;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.base.text.AsciiCaseUtils;
import org.fogofwarlabs.sc4j.base.text.TerminatedStringUtils;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueContext;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueMessagePopup;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.EditControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.InputMethod;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ListBox;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.core.FileNameUtils;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lists save targets and handles names, overwrite confirmation, deletion and save-or-cancel callbacks for game saves and replays.
 */
public final class GlueSavePopup extends GlueFramedPopup {
    /**
     * The {@code network.json} key of the save error text.
     */
    public static final @NotNull String SAVE_ERROR_KEY = "strERROR_SAVE";
    /**
     * The {@code network.json} key of the reserved file name error text.
     */
    public static final @NotNull String RESERVED_FILE_NAME_KEY = "strRESERVED_FILENAME";

    private static final @NotNull Logger LOGGER = Logger.of(GlueSavePopup.class);

    private static final @NotNull String DIALOG = "savegame";
    private static final int LIST = 1;
    private static final int NAME = 2;
    private static final int DELETE = 3;
    private static final int TITLE = 4;
    private static final int SAVE = -2;
    private static final int CANCEL = -3;
    private static final int FIRST_ROW = 0;
    /**
     * The bytes from which the name box takes no typed character: the bytes a saved game name keeps.
     */
    private static final int TYPED_NAME_BYTES = ReplayFile.SAVED_GAME_NAME_SIZE - 1;
    /**
     * The most bytes the edit control itself holds; as characters never outnumber bytes,
     * the name box's own limit ({@link #isTypable}) stops typing first.
     */
    private static final int EDIT_CONTROL_BYTES = 0xFF;
    /**
     * The byte the read name loses at its end.
     */
    private static final byte SPACE = ' ';
    /**
     * The typed space that an empty name box refuses.
     */
    private static final @NotNull String TYPED_SPACE = " ";
    /**
     * The maximum number of new game saves in one folder.
     */
    private static final int MAXIMUM_SAVED_GAMES = 0x80;
    /**
     * The file name pattern that matches any name before the extension.
     */
    private static final @NotNull String ANY_NAME_PATTERN = "*";
    private static final @NotNull String EMPTY_NAME = "";
    private static final @NotNull String NO_REJECTED_CHARACTERS = "";
    private static final @NotNull String TITLE_KEY = "saveReplayDialogTitle";
    private static final @NotNull String CONFIRM_REPLACE_KEY = "strCONFIRM_REPLACE_s";
    private static final @NotNull String CONFIRM_DELETE_KEY = "strCONFIRM_DELETE_s";
    private static final @NotNull String DELETE_ERROR_KEY = "strDELETE_ERR_s";
    private static final @NotNull String TOO_MANY_KEY = "strSAVE_TOO_MANY_ERR";
    private static final @NotNull String NAME_PLACEHOLDER = "%s";

    /**
     * What the dialog saves.
     */
    public interface Target {
        /**
         * @return the folder the dialog lists and writes into
         */
        @NotNull Path getDirectory();

        /**
         * @return the file name extension with its dot
         */
        @NotNull String getExtension();

        /**
         * @return {@code true} if the dialog saves the score screen's replay, {@code false} otherwise
         */
        boolean isReplay();

        /**
         * Saves under a name: writes the replay into {@link #getDirectory()}, or sends the Save Game command whose execution writes the game.
         *
         * @param name the name the dialog read from its name box: the bytes cut to a saved game name buffer, without trailing spaces and without terminator
         * @return {@code true} if the save was written or sent, {@code false} otherwise
         */
        boolean save(byte @NotNull [] name);
    }


    /**
     * Lists saved files by modification time, oldest first, with case-insensitive name ordering for equal times.
     *
     * @param directory the folder
     * @param extension the file name extension with its dot
     * @return the names without the extension; empty for a missing folder, retaining entries read before a listing failure
     */
    public static @NotNull List<String> listNames(@NotNull Path directory, @NotNull String extension) {
        List<Path> found = new ArrayList<>();

        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, ANY_NAME_PATTERN + extension)) {
            for (Path file : files) {
                if (Files.isRegularFile(file)) {
                    found.add(file);
                }
            }
        } catch (IOException exception) {
            LOGGER.warning("SAVE DIALOG: the folder cannot be listed: " + exception.getMessage());
        }

        found.sort(Comparator.comparing(GlueSavePopup::getModifiedTime)
                             .thenComparing(file -> file.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));

        List<String> names = new ArrayList<>(found.size());

        for (Path file : found) {
            String name = file.getFileName().toString();

            names.add(name.substring(0, name.length() - extension.length()));
        }

        return names;
    }

    private static @NotNull FileTime getModifiedTime(@NotNull Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException exception) {
            return FileTime.fromMillis(0);
        }
    }

    /**
     * Reads the name the dialog saves under from the name box text: its UTF-8 bytes cut to a saved game name buffer
     * ({@link TerminatedStringUtils#cutText(String, int)}), then without their trailing space bytes.
     *
     * @param text the name box text
     * @return the name bytes without terminator
     */
    static byte @NotNull [] readName(@NotNull String text) {
        byte[] name = TerminatedStringUtils.cutText(text, ReplayFile.SAVED_GAME_NAME_SIZE);
        int length = name.length;

        while (length > 0 && name[length - 1] == SPACE) {
            length--;
        }

        return Arrays.copyOf(name, length);
    }

    /**
     * Tells whether the name box takes a typed character: not once its text holds the bytes a saved game name keeps ({@code 27}),
     * and not a space into an empty box.
     *
     * @param text the name box text before the character
     * @param next the text the character would leave
     * @return {@code true} if the box takes the character, {@code false} otherwise
     */
    static boolean isTypable(@NotNull String text, @NotNull String next) {
        return text.getBytes(StandardCharsets.UTF_8).length < TYPED_NAME_BYTES && !(text.isEmpty() && next.equals(TYPED_SPACE));
    }

    private final @NotNull Target target;
    private final @NotNull Consumer<Boolean> result;

    private @Nullable ListBox list;
    private @NotNull List<String> names;
    /**
     * The characters the name box refuses: the localized {@link FileNameUtils#BAD_CHARACTERS_KEY} text.
     */
    private @NotNull String rejected;

    private @Nullable ScrControl saveButton;
    private @Nullable ScrControl deleteButton;
    private @Nullable EditControl nameBox;

    /**
     * Creates a {@link GlueSavePopup} instance.
     *
     * @param paletteFolder the palette folder of the screen below
     * @param consoleRace   the console race of the game below, or {@code -1} over a glue screen
     * @param target        what the dialog saves
     * @param result        receives {@code true} once the save was written or sent, {@code false} on Cancel
     */
    public GlueSavePopup(@NotNull String paletteFolder, int consoleRace, @NotNull Target target, @NotNull Consumer<Boolean> result) {
        super(DIALOG, paletteFolder, consoleRace);

        this.target = target;
        this.result = result;

        this.list     = null;
        this.names    = List.of();
        this.rejected = NO_REJECTED_CHARACTERS;

        this.saveButton   = null;
        this.deleteButton = null;
        this.nameBox      = null;
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * The popup sets the replay title and hides Delete for a replay, lists the target's saves, empties the name box and selects the first listed
     * save, whose name it copies into the name box.
     *
     * @param glueContext the shared services
     * @throws IOException if an asset cannot be read
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) throws IOException {
        this.saveButton   = this.bindControl(SAVE);
        this.deleteButton = this.bindControl(DELETE);
        this.nameBox      = null;

        super.onOpen(glueContext);

        if (this.target.isReplay()) {
            this.bindControl(TITLE).setReplacedText(glueContext.getGluText().get(TITLE_KEY));
            requireBound(this.deleteButton).setVisible(false);
        }

        this.names    = listNames(this.target.getDirectory(), this.target.getExtension());
        this.rejected = glueContext.getNetworkText().get(FileNameUtils.BAD_CHARACTERS_KEY);

        ListBox files = this.addListBox(LIST);

        files.setItems(this.names);
        this.list = files;
        this.setNameText(EMPTY_NAME);
        requireBound(this.saveButton).setEnabled(false);
        requireBound(this.deleteButton).setEnabled(false);

        if (!this.names.isEmpty()) {
            files.select(FIRST_ROW, files.getVisibleRows(glueContext.getCanvas()));
            this.onSelectionChanged(LIST);
        }
    }


    /**
     * Called after the user changed the selection of a list or combo box, or the value of a slider.
     * <p>
     * Delete is enabled while the list selects a save, and the selected save's name is copied into the name box.
     *
     * @param control the control id
     */
    @Override
    protected void onSelectionChanged(int control) {
        ListBox files = this.list;

        if (control != LIST || files == null) {
            return;
        }

        int selection = files.getSelection();

        requireBound(this.deleteButton).setEnabled(selection >= 0 && selection < this.names.size());

        if (selection < 0 || selection >= this.names.size()) {
            return;
        }

        this.setNameText(this.names.get(selection));
        this.onEditChanged(NAME);
    }

    /**
     * Called after the text of an {@code EDITTEXT} changed.
     * <p>
     * Save is enabled while the name box holds text.
     *
     * @param control the control id
     */
    @Override
    protected void onEditChanged(int control) {
        if (control == NAME) {
            requireBound(this.saveButton).setEnabled(!requireBound(this.nameBox).getEditText().getText().isEmpty());
        }
    }

    /**
     * Sets the name box's text: the box refuses the localized bad file name characters and takes typed characters by {@link #isTypable}.
     *
     * @param text the name box text
     */
    private void setNameText(@NotNull String text) {
        EditControl box = this.setEditText(NAME, text, EDIT_CONTROL_BYTES, this.rejected);

        box.getEditText().setAcceptance(next -> isTypable(box.getEditText().getText(), next));
        this.nameBox = box;
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * Cancel closes the popup and Delete asks to delete the selected save. Save reads the name, asks before it replaces a listed save, checks the
     * save count and the file name of a game save, and then saves.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
        if (control == CANCEL) {
            this.finish();
            this.result.accept(false);

            return;
        }

        if (control == DELETE) {
            this.confirmDelete();

            return;
        }

        if (control != SAVE) {
            return;
        }

        byte[] name = readName(requireBound(this.nameBox).getEditText().getText());

        if (name.length == 0) {
            return;
        }

        if (this.names.stream().anyMatch(listed -> AsciiCaseUtils.equalsIgnoreCase(listed.getBytes(StandardCharsets.UTF_8), name))) {
            String text = TerminatedStringUtils.readText(name);
            String question = this.getContext().getNetworkText().get(CONFIRM_REPLACE_KEY).replace(NAME_PLACEHOLDER, text);

            this.showPopup(new GlueMessagePopup(GlueMessagePopup.Kind.OK_CANCEL,
                                                this.getPaletteFolder(),
                                                question,
                                                null,
                                                replace -> {
                                                    if (replace) {
                                                        this.save(name);
                                                    }
                                                }));

            return;
        }

        if (!this.target.isReplay()) {
            if (this.names.size() >= MAXIMUM_SAVED_GAMES) {
                this.showError(this.getContext().getNetworkText().get(TOO_MANY_KEY));

                return;
            }

            String badCharacters = this.getContext().getNetworkText().get(FileNameUtils.BAD_CHARACTERS_KEY);

            if (!FileNameUtils.isValidFileName(name, ReplayFile.SAVED_GAME_NAME_SIZE, badCharacters)) {
                this.showError(this.getContext().getNetworkText().get(RESERVED_FILE_NAME_KEY));

                return;
            }
        }

        this.save(name);
    }

    /**
     * Saves under a name through the target, then closes the popup, or shows the save error when the target fails.
     *
     * @param name the name bytes without terminator
     */
    private void save(byte @NotNull [] name) {
        if (!this.target.save(name)) {
            this.showError(this.getContext().getNetworkText().get(SAVE_ERROR_KEY));

            return;
        }

        LOGGER.info("SAVE DIALOG: " + TerminatedStringUtils.readText(name));
        this.finish();
        this.result.accept(true);
    }

    /**
     * Confirms deletion of the selected save, then reloads the list after a successful deletion.
     */
    private void confirmDelete() {
        ListBox files = this.list;
        int selection = files == null ? ListBox.NO_SELECTION : files.getSelection();

        if (selection < 0 || selection >= this.names.size()) {
            return;
        }

        String name = this.names.get(selection);
        Path file = this.target.getDirectory().resolve(name + this.target.getExtension());
        String question = this.getContext().getNetworkText().get(CONFIRM_DELETE_KEY).replace(NAME_PLACEHOLDER, name);

        this.showPopup(new GlueMessagePopup(GlueMessagePopup.Kind.OK_CANCEL,
                                            this.getPaletteFolder(),
                                            question,
                                            null,
                                            delete -> {
                                                if (!delete) {
                                                    return;
                                                }

                                                try {
                                                    Files.delete(file);
                                                } catch (IOException exception) {
                                                    LOGGER.warning("SAVE DIALOG: " + file + " cannot be deleted: " + exception.getMessage());
                                                    this.showError(this.getContext().getNetworkText().get(DELETE_ERROR_KEY)
                                                                       .replace(NAME_PLACEHOLDER, name));

                                                    return;
                                                }

                                                this.names = listNames(this.target.getDirectory(), this.target.getExtension());

                                                ListBox current = this.list;

                                                if (current != null) {
                                                    current.setItems(this.names);
                                                }

                                                requireBound(this.deleteButton).setEnabled(false);
                                            }));
    }


    /**
     * Releases the videos and the popup.
     * <p>
     * Text input ends after the inherited resources are released.
     */
    @Override
    public void close() {
        super.close();

        // Text input ends after the dialog closes; the screen below receives none.
        InputMethod.setTextInput(false);
    }
}
