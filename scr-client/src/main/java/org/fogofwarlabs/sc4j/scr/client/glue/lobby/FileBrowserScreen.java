package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueContext;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueScreen;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.DialogPainter;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ListBox;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.data.extension.ScrDataExtensionUtils;
import org.fogofwarlabs.sc4j.scr.game.replay.ScrReplayFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Lists local folders, maps and replays with their information panels.
 * <p>
 * Folders precede files; each group sorts names ignoring case. The root omits its parent row. A name query keeps only the files whose names
 * contain it, ignoring case. OK opens a folder or chooses a file, and a double press acts as OK. Cancel returns to the campaign screen. Cloud
 * folders are unavailable.
 */
public abstract class FileBrowserScreen extends GlueScreen {
    /**
     * The lower-case file extension of an original map archive, with the dot.
     */
    public static final @NotNull String ORIGINAL_MAP_EXTENSION = ".scm";
    /**
     * The lower-case file extension of an expansion map archive, with the dot.
     */
    public static final @NotNull String EXPANSION_MAP_EXTENSION = ".scx";
    /**
     * The replay file extension, lower case with the dot: the SC:R mod's replay format ({@link ScrReplayFormat}).
     */
    public static final @NotNull String REPLAY_EXTENSION = "." + ScrReplayFormat.FILE_EXTENSION;
    /**
     * The Ok button.
     */
    protected static final int OK = 12;
    /**
     * The Cancel button.
     */
    protected static final int CANCEL = 13;
    /**
     * The file list ({@code LISTBOX}).
     */
    protected static final int LIST = 5;
    /**
     * The open folder's label, drawn after the open folder icon.
     */
    protected static final int FOLDER = 6;
    /**
     * The map file extensions, lower case with the dot: the native ones, then those the data extensions add
     * ({@link ScrDataExtensionUtils#getExtraMapFileExtensions}).
     */
    static final @NotNull List<String> MAP_EXTENSIONS = createMapExtensions();

    private static final @NotNull Logger LOGGER = Logger.of(FileBrowserScreen.class);

    /**
     * The map and replay information panel ({@code WEBBROWSER}, {@code MapBrowserInfoPanel}).
     */
    private static final int INFORMATION_PANEL = 41;
    private static final @NotNull String UP_ONE_LEVEL = "gameUpOneLevel";
    /**
     * The icons of {@code glue\create\iCreate.grp}: folder, file; the next frame is selected.
     */
    private static final @NotNull String ICON_SHEET = "glue/create/icreate.dds.grp";
    private static final int FOLDER_ICON = 0;
    private static final int FILE_ICON = 3;
    /**
     * The open folder drawn before the folder label.
     */
    private static final int OPEN_FOLDER_ICON = 2;
    /**
     * The label text starts this far right of the open folder icon.
     */
    private static final int LABEL_GAP = 2;
    /**
     * The slide-in list for the file browser: panel and direction ({@code 0} left, {@code 2} right, {@code 3} down), no delay.
     */
    private static final int @NotNull [] SWISH = {1, 0, 2, 2, 3, 3, 4, 2, 19, 0, 42, 0};
    private static final int SWISH_DELAY_MILLISECONDS = 0;
    private static final int FIRST_ROW = 0;

    /**
     * One row of the list.
     */
    private static final class Entry {
        private final @NotNull Path path;
        private final boolean folder;

        private Entry(@NotNull Path path, boolean folder) {
            this.path   = path;
            this.folder = folder;
        }
    }

    private static @NotNull List<String> createMapExtensions() {
        List<String> extensions = new ArrayList<>(List.of(ORIGINAL_MAP_EXTENSION, EXPANSION_MAP_EXTENSION));

        for (String extension : ScrDataExtensionUtils.getExtraMapFileExtensions()) {
            if (!extensions.contains(extension)) {
                extensions.add(extension);
            }
        }

        return List.copyOf(extensions);
    }

    /**
     * Checks whether a file is a map archive by its file name extension, ignoring case: {@value #ORIGINAL_MAP_EXTENSION},
     * {@value #EXPANSION_MAP_EXTENSION} or an extension a data extension adds ({@link ScrDataExtensionUtils#getExtraMapFileExtensions}).
     *
     * @param file the file
     * @return {@code true} if the file name ends with a map file extension, {@code false} otherwise
     */
    public static boolean isMapFile(@NotNull Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);

        for (String extension : MAP_EXTENSIONS) {
            if (name.endsWith(extension)) {
                return true;
            }
        }

        return false;
    }

    private static @Nullable ReplayInformation readReplay(@NotNull Path file) {
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(REPLAY_EXTENSION)) {
            return null;
        }

        try {
            return ReplayInformation.read(file);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("REPLAY UNREADABLE: " + file + ": " + exception.getMessage());

            return null;
        }
    }

    private static @Nullable MapInformation readMap(@NotNull Path file, boolean mapCompatibility) {
        if (!isMapFile(file)) {
            return null;
        }

        try {
            return MapInformation.read(file, mapCompatibility);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("MAP UNREADABLE: " + file + ": " + exception.getMessage());

            return null;
        }
    }

    private final @NotNull List<String> extensions;

    private @NotNull String nameQuery;
    private @Nullable Path root;
    private @Nullable Path folder;
    private final @NotNull List<Entry> entries;
    private @Nullable ListBox list;

    private @Nullable MapInformation selectedMap;
    private @Nullable ReplayInformation selectedReplay;
    private final @NotNull MapPreviewTexture preview;

    private @Nullable ScrControl folderLabel;
    private @Nullable ScrControl okButton;

    /**
     * Creates a {@link FileBrowserScreen} instance.
     *
     * @param extensions the listed file extensions, lower case with the dot
     */
    protected FileBrowserScreen(@NotNull List<String> extensions) {
        this("gluCustm", extensions);
    }

    /**
     * Creates a {@link FileBrowserScreen} instance on a dialog with the shared file-browser layout.
     *
     * @param dialogName the dialog name
     * @param extensions the listed file extensions, lower case with the dot
     */
    protected FileBrowserScreen(@NotNull String dialogName, @NotNull List<String> extensions) {
        super(dialogName, SHARED_PALETTE_FOLDER, getPaletteBackground(SHARED_PALETTE_FOLDER));

        this.extensions = List.copyOf(extensions);

        this.nameQuery = "";
        this.root      = null;
        this.folder    = null;
        this.entries   = new ArrayList<>();
        this.list      = null;

        this.selectedMap    = null;
        this.selectedReplay = null;
        this.preview        = new MapPreviewTexture();

        this.folderLabel = null;
        this.okButton    = null;
    }

    /**
     * @return the information of the selected map file, or {@code null} for a folder, another file, no selection or a map the map list rejects
     */
    protected final @Nullable MapInformation getSelectedMap() {
        return this.selectedMap;
    }

    /**
     * @return the listed files of the open folder in list order (the root's other files included), without its folders
     */
    protected final @NotNull List<Path> getListedFiles() {
        List<Path> files = new ArrayList<>();

        for (Entry entry : this.entries) {
            if (!entry.folder) {
                files.add(entry.path);
            }
        }

        return files;
    }

    /**
     * Sets the name query and lists the open folder again: only the files whose names contain the query, ignoring case.
     *
     * @param query the query, or an empty string for every file
     */
    protected final void setNameQuery(@NotNull String query) {
        this.nameQuery = query.strip().toLowerCase(Locale.ROOT);
        this.fillEntries();
    }

    /**
     * Selects a file: in the open folder's list, or else in its own folder below the root, which opens.
     *
     * @param file the file
     */
    protected final void showFile(@NotNull Path file) {
        Path parent = file.getParent();

        if (this.selectFile(file) || parent == null || this.root == null || !parent.startsWith(this.root)) {
            return;
        }

        this.folder = parent;
        this.fillEntries();
        this.selectFile(file);
    }

    /**
     * @param glueContext the shared services
     * @return the root folder of the list
     */
    protected abstract @NotNull Path getRoot(@NotNull GlueContext glueContext);

    /**
     * Called when Ok is chosen on a file.
     *
     * @param file the file
     */
    protected abstract void onFileChosen(@NotNull Path file);

    /**
     * @param glueContext the shared services
     * @return the file to show selected when the screen opens (its folder is opened), or {@code null} for the root
     */
    protected @Nullable Path getRememberedFile(@NotNull GlueContext glueContext) {
        return null;
    }

    /**
     * Returns files of other folders that the root folder lists after its own files.
     *
     * @param glueContext the shared services
     * @return the files, empty by default
     */
    protected @NotNull List<Path> getRootFiles(@NotNull GlueContext glueContext) {
        return List.of();
    }

    /**
     * Called when the selected file changes.
     *
     * @param file the selected file, or {@code null} for a folder or no selection
     */
    protected void onFileSelected(@Nullable Path file) {
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * Opens the root folder, or the folder of the remembered file, and selects the remembered file.
     *
     * @param glueContext the shared services
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        this.folderLabel = this.bindControl(FOLDER);
        this.okButton    = this.bindControl(OK);

        ListBox names = this.addListBox(LIST);

        this.list = names;
        names.setIconSheet(ICON_SHEET);
        this.setSwish(SWISH, SWISH_DELAY_MILLISECONDS);

        Path rootFolder = this.getRoot(glueContext);

        this.root   = rootFolder;
        this.folder = rootFolder;

        Path remembered = this.getRememberedFile(glueContext);

        if (remembered != null && remembered.getParent() != null && remembered.startsWith(rootFolder) && Files.isRegularFile(remembered)) {
            this.folder = remembered.getParent();
        }

        this.fillEntries();

        if (remembered != null) {
            this.selectFile(remembered);
        }
    }

    private @NotNull ListBox requireList() {
        if (this.list == null) {
            throw Exceptions.newIllegalStateException("The file list is not open.");
        }

        return this.list;
    }

    private void fillEntries() {
        ListBox names = this.requireList();

        this.entries.clear();

        Path current = this.folder;

        if (current == null) {
            return;
        }

        List<Path> folders = new ArrayList<>();
        List<Path> files = new ArrayList<>();

        if (Files.isDirectory(current)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(current)) {
                for (Path child : children) {
                    if (Files.isDirectory(child)) {
                        folders.add(child);
                    } else if (this.isListed(child) && this.matchesQuery(child)) {
                        files.add(child);
                    }
                }
            } catch (IOException exception) {
                LOGGER.warning("GLUE FOLDER UNREADABLE: " + current + ": " + exception.getMessage());
            }
        }

        Comparator<Path> byName = Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT));

        folders.sort(byName);
        files.sort(byName);
        names.setItems(List.of());

        if (!current.equals(this.root) && current.getParent() != null) {
            this.entries.add(new Entry(current.getParent(), true));
            names.addIconItem("[" + this.getContext().getGluText().get(UP_ONE_LEVEL) + "]", FOLDER_ICON);
        }

        for (Path child : folders) {
            this.entries.add(new Entry(child, true));
            names.addIconItem("[" + child.getFileName() + "]", FOLDER_ICON);
        }

        for (Path child : files) {
            this.entries.add(new Entry(child, false));
            names.addIconItem(child.getFileName().toString(), FILE_ICON);
        }

        if (current.equals(this.root)) {
            for (Path extra : this.getRootFiles(this.getContext())) {
                if (!this.matchesQuery(extra)) {
                    continue;
                }

                this.entries.add(new Entry(extra, false));
                names.addIconItem(String.valueOf(extra.getFileName()), FILE_ICON);
            }
        }

        requireBound(this.folderLabel).setReplacedText(current.getFileName() == null ? "" : current.getFileName().toString());
        names.select(this.entries.isEmpty() ? ListBox.NO_SELECTION : FIRST_ROW, names.getVisibleRows(this.getContext().getCanvas()));
        this.updateSelection();
    }

    private boolean selectFile(@NotNull Path file) {
        ListBox names = this.requireList();

        for (int index = 0; index < this.entries.size(); index++) {
            if (!this.entries.get(index).folder && this.entries.get(index).path.equals(file)) {
                names.select(index, names.getVisibleRows(this.getContext().getCanvas()));
                this.updateSelection();

                return true;
            }
        }

        return false;
    }

    private boolean matchesQuery(@NotNull Path file) {
        return this.nameQuery.isEmpty() || String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT).contains(this.nameQuery);
    }

    private boolean isListed(@NotNull Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);

        for (String extension : this.extensions) {
            if (name.endsWith(extension)) {
                return true;
            }
        }

        return false;
    }

    private @Nullable Entry getSelectedEntry() {
        int selection = this.requireList().getSelection();

        return selection == ListBox.NO_SELECTION ? null : this.entries.get(selection);
    }

    /**
     * Called after the user changed the selection of a list or combo box, or the value of a slider.
     * <p>
     * Reads the information of the entry the file list selects.
     *
     * @param control the control id
     */
    @Override
    protected void onSelectionChanged(int control) {
        if (control == LIST) {
            this.updateSelection();
        }
    }

    private void updateSelection() {
        Entry entry = this.getSelectedEntry();

        this.selectedMap    = entry == null || entry.folder ? null : readMap(entry.path, this.getContext().isMapCompatibility());
        this.selectedReplay = entry == null || entry.folder ? null : readReplay(entry.path);
        this.preview.replace(this.getContext(), this.selectedMap);
        requireBound(this.okButton).setEnabled(entry != null);
        this.onFileSelected(entry == null || entry.folder ? null : entry.path);
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * Ok opens the selected folder or chooses the selected file ({@link #onFileChosen}); Cancel calls {@link #onCancel};
     * another control goes to {@link #onOtherControl}.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
        switch (control) {
            case OK -> {
                Entry entry = this.getSelectedEntry();

                if (entry == null) {
                    return;
                }

                if (entry.folder) {
                    this.folder = entry.path;
                    this.fillEntries();
                } else {
                    this.onFileChosen(entry.path);
                }
            }
            case CANCEL -> this.onCancel();
            default -> this.onOtherControl(control);
        }
    }

    /**
     * Draws one control.
     * <p>
     * Draws the selected map's or replay's information panel and the folder label with its open-folder icon.
     *
     * @param canvas  the glue canvas
     * @param control the control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IOException if an image cannot be read
     */
    @Override
    protected void drawControl(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        if (control.getId() == INFORMATION_PANEL) {
            MapInformation map = this.selectedMap;
            ReplayInformation replay = this.selectedReplay;

            if (map != null) {
                MapInformationPanel.draw(canvas,
                                         this.getContext().getGluText(),
                                         map,
                                         this.preview,
                                         left, top,
                                         control.getWidth(), control.getHeight());
            } else if (replay != null) {
                MapInformationPanel.drawReplay(canvas, this.getContext().getGluText(), replay, left, top, control.getWidth(), control.getHeight());
            }

            return;
        }

        if (control.getId() != FOLDER) {
            super.drawControl(canvas, control, left, top);

            return;
        }

        ListBox.drawIconLabel(canvas,
                              canvas.getControlFontSlot(control),
                              canvas.getSheetFrame(ICON_SHEET, OPEN_FOLDER_ICON),
                              left, top,
                              control.getHeight(),
                              LABEL_GAP,
                              left + control.getWidth(),
                              this.getDrawnString(control),
                              DialogPainter.CODE_TEXT);
    }

    /**
     * Called when Cancel is chosen: returns to the campaign screen (the single player screens).
     */
    protected void onCancel() {
        this.leave(() -> this.getContext().getNavigator().show(GlueNavigator.Destination.CAMPAIGN));
    }

    /**
     * Called for a control other than Ok and Cancel.
     *
     * @param control the control id
     */
    protected void onOtherControl(int control) {
    }


    /**
     * Releases the videos and the popup.
     * <p>
     * Releases the map preview first.
     */
    @Override
    public void close() {
        this.preview.release(this.getContext().getCanvas());
        super.close();
    }
}
