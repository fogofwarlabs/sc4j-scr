package org.fogofwarlabs.sc4j.scr.client.configuration;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.settings.SettingsStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;

/**
 * Resolves client data, settings, game-content and cache paths relative to the install root or an explicitly configured data directory. An explicit
 * root takes precedence over installed jars and the development working directory.
 */
public final class InstallLayout {
    /**
     * Default data directory name.
     */
    public static final @NotNull String DATA_FOLDER = "data";

    /**
     * Map folder name.
     */
    public static final @NotNull String MAP_FOLDER = "maps";

    /**
     * Replay folder name.
     */
    public static final @NotNull String REPLAY_FOLDER = "replays";

    /**
     * The folder of the desync dumps a network game writes at its first sync mismatch.
     */
    public static final @NotNull String DESYNC_FOLDER = "desyncs";

    /**
     * The saved-game folder; each single-player identifier has its own subfolder.
     */
    public static final @NotNull String SAVE_FOLDER = "save";

    /**
     * Single player ID folder name.
     */
    public static final @NotNull String CHARACTER_FOLDER = "characters";

    /**
     * Log folder name.
     */
    public static final @NotNull String LOG_FOLDER = "logs";

    /**
     * Cache and process temporary storage folder name.
     */
    public static final @NotNull String CACHE_FOLDER = ".cache";

    /**
     * The file of the maps this client hosted last.
     */
    public static final @NotNull String RECENT_MAPS_FILE = "recent-maps.txt";

    /**
     * JVM property naming the data directory for development runs and tests.
     */
    public static final @NotNull String DATA_PROPERTY = "sc4j.data";

    /**
     * JVM property naming the root of a client run.
     */
    public static final @NotNull String ROOT_PROPERTY = "sc4j.root";

    private static final @NotNull String BUILD_INFO_FILE = ".build.info";
    private static final @NotNull String DATA_PACK_CONTENT_FOLDER = "data";

    private static final @NotNull String DEFAULT_SOURCE = "default";
    private static final @NotNull String SETTINGS_SOURCE_PREFIX = "paths.data in ";
    private static final @NotNull String PROPERTY_SOURCE_PREFIX = "-D";

    private static final @NotNull String LIBRARY_FOLDER = "lib";
    private static final @NotNull String JAR_EXTENSION = ".jar";
    private static final @NotNull String NATIVE_FOLDER = "natives";
    private static final @NotNull String TEMPORARY_FOLDER = "tmp";
    private static final @NotNull String JAVACPP_CACHE_FOLDER = "javacpp";

    private static final @NotNull String WINDOWS_DIRECTORY_VARIABLE = "WINDIR";
    private static final @NotNull String FONT_FOLDER = "Fonts";

    private static final @NotNull String NATIVE_LIBRARY_MANIFEST = "libraries.list";
    private static final @NotNull String LWJGL_LIBRARY_PATH_PROPERTY = "org.lwjgl.librarypath";
    private static final @NotNull String LWJGL_EXTRACT_PROPERTY = "org.lwjgl.system.SharedLibraryExtractPath";
    private static final @NotNull String JAVACPP_CACHE_PROPERTY = "org.bytedeco.javacpp.cachedir";
    private static final @NotNull String JAVACPP_LINK_PATH_PROPERTY = "org.bytedeco.javacpp.platform.linkpath";
    private static final @NotNull String JAVACPP_PRELOAD_PATH_PROPERTY = "org.bytedeco.javacpp.platform.preloadpath";
    private static final @NotNull String JAVACPP_PATHS_FIRST_PROPERTY = "org.bytedeco.javacpp.pathsFirst";
    private static final @NotNull String JAVACPP_CACHE_LIBRARIES_PROPERTY = "org.bytedeco.javacpp.cacheLibraries";

    /**
     * A StarCraft: Remastered data directory and the setting it came from.
     */
    public static final class DataDirectory {
        private final @NotNull Path path;
        private final @NotNull String source;

        /**
         * Creates a {@link DataDirectory} instance.
         *
         * @param path   the data directory
         * @param source the configuration source of the directory
         */
        public DataDirectory(@NotNull Path path, @NotNull String source) {
            this.path   = path;
            this.source = source;
        }

        /**
         * @return the directory
         */
        public @NotNull Path getPath() {
            return this.path;
        }

        /**
         * @return where the directory was configured
         */
        public @NotNull String getSource() {
            return this.source;
        }

        /**
         * @return {@code true} if the directory holds a data pack ({@link InstallLayout#isDataDirectory(Path)}), {@code false} otherwise
         */
        public boolean isPresent() {
            return isDataDirectory(this.path);
        }

        /**
         * Describes the missing data pack and how to configure its directory.
         *
         * @return the message shown when the data pack is missing: the path tried, its source and how to set it
         */
        public @NotNull String describeMissing() {
            return "StarCraft: Remastered data not found at " + this.path.toAbsolutePath() + " (" + this.source + "). " +
                   "Copy the game's data (the folder holding .build.info and Data) to " +
                   (this.source.equals(DEFAULT_SOURCE) ? "that folder" : "the default folder next to the executable") +
                   ", or point to it with --data <folder> or \"paths\": {\"data\": \"<folder>\"} in " + SettingsStore.FILE_NAME + ".";
        }
    }

    /**
     * Checks whether a directory holds a StarCraft: Remastered data pack.
     *
     * @param directory a candidate data directory
     * @return {@code true} if {@code .build.info} is in it (with its {@code Data} folder) or it is the {@code Data} folder of a data pack, {@code false} otherwise
     */
    public static boolean isDataDirectory(@NotNull Path directory) {
        Path parent = directory.toAbsolutePath().getParent();

        return Files.isRegularFile(directory.resolve(BUILD_INFO_FILE)) ||
               parent != null && Files.isRegularFile(parent.resolve(BUILD_INFO_FILE)) &&
               Files.isDirectory(directory.resolve(DATA_PACK_CONTENT_FOLDER));
    }

    /**
     * Creates a {@link InstallLayout} instance from the explicit root, client location or working directory.
     *
     * @return the created {@link InstallLayout} instance with the selected root
     * @throws InvalidPathException if the nonblank explicit root is not a valid path
     */
    public static @NotNull InstallLayout detect() {
        String configured = System.getProperty(ROOT_PROPERTY, "");

        if (!configured.isBlank()) {
            return of(Path.of(configured));
        }

        try {
            CodeSource source = InstallLayout.class.getProtectionDomain().getCodeSource();

            if (source != null) {
                return fromCodeLocation(Path.of(source.getLocation().toURI()));
            }
        } catch (URISyntaxException | RuntimeException exception) {
            // Falls back to the working directory.
        }

        return of(Path.of(""));
    }

    /**
     * Creates a {@link InstallLayout} instance rooted at the given directory.
     *
     * @param root the install root
     * @return the created {@link InstallLayout} instance rooted at the given directory
     */
    public static @NotNull InstallLayout of(@NotNull Path root) {
        return new InstallLayout(root);
    }

    /**
     * Creates a {@link InstallLayout} instance from the client class location: rooted at the folder holding {@code lib/} for an installed jar,
     * otherwise at the working directory.
     *
     * @param codeLocation the location the client classes were loaded from (a jar file or a class folder)
     * @return the created {@link InstallLayout} instance
     */
    static @NotNull InstallLayout fromCodeLocation(@NotNull Path codeLocation) {
        Path location = codeLocation.toAbsolutePath();
        Path parent = location.getParent();

        if (location.getFileName() != null && location.getFileName().toString().endsWith(JAR_EXTENSION) && parent != null &&
            parent.getFileName() != null && parent.getFileName().toString().equals(LIBRARY_FOLDER) && parent.getParent() != null) {
            return of(parent.getParent());
        }

        return of(Path.of(""));
    }

    private final @NotNull Path root;
    private final @NotNull List<Path> fontDirectories;

    /**
     * Creates a {@link InstallLayout} instance.
     *
     * @param root the installation root, normalized to an absolute path
     */
    private InstallLayout(@NotNull Path root) {
        this.root = root.toAbsolutePath().normalize();

        String windowsDirectory = System.getenv(WINDOWS_DIRECTORY_VARIABLE);

        this.fontDirectories = windowsDirectory == null || windowsDirectory.isBlank()
                               ? List.of()
                               : List.of(Path.of(windowsDirectory).resolve(FONT_FOLDER));
    }

    /**
     * @return the absolute install root
     */
    public @NotNull Path getRoot() {
        return this.root;
    }

    /**
     * @return the permitted machine-wide font directories in search order, or an empty list when none is configured
     */
    public @NotNull List<Path> getFontDirectories() {
        return this.fontDirectories;
    }

    /**
     * @return {@code <root>/sc4j-settings.json}
     */
    public @NotNull Path getSettingsFile() {
        return this.root.resolve(SettingsStore.FILE_NAME);
    }

    /**
     * @return {@code <root>/data}, the data directory used when none is given explicitly
     */
    public @NotNull Path getDefaultDataDirectory() {
        return this.root.resolve(DATA_FOLDER);
    }

    /**
     * @return {@code <root>/maps}, the only folder the map browser lists and the only map folder a LAN joiner searches
     */
    public @NotNull Path getMapDirectory() {
        return this.root.resolve(MAP_FOLDER);
    }

    /**
     * @return {@code <root>/replays}
     */
    public @NotNull Path getReplayDirectory() {
        return this.root.resolve(REPLAY_FOLDER);
    }

    /**
     * @return {@code <root>/desyncs}
     */
    public @NotNull Path getDesyncDirectory() {
        return this.root.resolve(DESYNC_FOLDER);
    }

    /**
     * @return {@code <root>/save}, the folder of the single player IDs' saved game folders
     */
    public @NotNull Path getSaveDirectory() {
        return this.root.resolve(SAVE_FOLDER);
    }

    /**
     * @return {@code <root>/characters}
     */
    public @NotNull Path getCharacterDirectory() {
        return this.root.resolve(CHARACTER_FOLDER);
    }

    /**
     * @return {@code <root>/recent-maps.txt}
     */
    public @NotNull Path getRecentMapsFile() {
        return this.root.resolve(RECENT_MAPS_FILE);
    }

    /**
     * @return {@code <root>/logs}
     */
    public @NotNull Path getLogDirectory() {
        return this.root.resolve(LOG_FOLDER);
    }

    /**
     * @return {@code <root>/.cache}
     */
    public @NotNull Path getCacheDirectory() {
        return this.root.resolve(CACHE_FOLDER);
    }

    /**
     * @return {@code <root>/lib/natives}, the pre-extracted native libraries
     */
    public @NotNull Path getNativeLibraryDirectory() {
        return this.root.resolve(LIBRARY_FOLDER).resolve(NATIVE_FOLDER);
    }

    /**
     * @return {@code <root>/.cache/natives}, the compatibility extraction folder
     */
    public @NotNull Path getNativeExtractDirectory() {
        return this.getCacheDirectory().resolve(NATIVE_FOLDER);
    }

    /**
     * @return {@code <root>/.cache/tmp}, the temporary directory prepared by the installed launcher
     */
    public @NotNull Path getTemporaryDirectory() {
        return this.getCacheDirectory().resolve(TEMPORARY_FOLDER);
    }

    /**
     * @return {@code <root>/.cache/javacpp}, the JavaCPP cache folder
     */
    public @NotNull Path getJavacppCacheDirectory() {
        return this.getCacheDirectory().resolve(JAVACPP_CACHE_FOLDER);
    }

    /**
     * Resolves a relative user path under the selected root and preserves an absolute user path.
     *
     * @param path the input or output path
     * @return the normalized root-relative path, or the unchanged absolute path
     */
    public @NotNull Path resolveUserPath(@NotNull Path path) {
        return path.isAbsolute() ? path : this.root.resolve(path).normalize();
    }

    /**
     * Resolves the data directory from the settings file value, else the JVM property {@value #DATA_PROPERTY}, else {@code <root>/data}. Relative
     * settings and property values resolve under the selected root. {@code --data} takes precedence over all of them and is handled by the launch
     * options.
     *
     * @param configured the settings file's {@code paths.data}, or {@code null}
     * @return the data directory and where it came from
     */
    public @NotNull DataDirectory resolveDataDirectory(@Nullable String configured) {
        if (configured != null) {
            return new DataDirectory(this.resolveUserPath(Path.of(configured)), SETTINGS_SOURCE_PREFIX + this.getSettingsFile().getFileName());
        }

        String property = System.getProperty(DATA_PROPERTY, "");

        if (!property.isBlank()) {
            return new DataDirectory(this.resolveUserPath(Path.of(property)), PROPERTY_SOURCE_PREFIX + DATA_PROPERTY);
        }

        return new DataDirectory(this.getDefaultDataDirectory(), DEFAULT_SOURCE);
    }

    /**
     * Configures image decoding and native loading before either library is initialized. The launcher must already supply {@code -Djava.io.tmpdir}
     * and {@code -XX:-UsePerfData}; changing these after JVM startup is insufficient. Development runs use the paths supplied by Gradle; installed
     * runs use the pre-extracted {@code lib/natives}.
     *
     * @throws IOException if the native directory or manifest is missing or malformed, or a required library is missing
     */
    public void applyProcessProperties() throws IOException {
        Path nativeDirectory = Path.of(System.getProperty(LWJGL_LIBRARY_PATH_PROPERTY, this.getNativeLibraryDirectory().toString()))
                                   .toAbsolutePath().normalize();

        if (!Files.isDirectory(nativeDirectory)) {
            throw Exceptions.newIOException("The pre-extracted native library directory does not exist: {0}", nativeDirectory);
        }

        Path manifest = nativeDirectory.resolve(NATIVE_LIBRARY_MANIFEST);
        List<String> libraries = Files.readAllLines(manifest);

        if (libraries.isEmpty()) {
            throw Exceptions.newIOException("The pre-extracted native library manifest is empty: {0}", manifest);
        }

        for (String library : libraries) {
            Path libraryPath;

            try {
                libraryPath = nativeDirectory.resolve(library).normalize();
            } catch (InvalidPathException exception) {
                throw Exceptions.newIOException(exception, "Invalid name in the native library manifest: {0}", manifest);
            }

            if (library.isBlank() || !nativeDirectory.equals(libraryPath.getParent()) || !library.equals(libraryPath.getFileName().toString())) {
                throw Exceptions.newIOException("Invalid name in the native library manifest: {0}", manifest);
            }

            if (!Files.isRegularFile(libraryPath)) {
                throw Exceptions.newIOException("The required pre-extracted native library does not exist: {0}", libraryPath);
            }
        }

        System.setProperty(LWJGL_LIBRARY_PATH_PROPERTY, nativeDirectory.toString());

        if (System.getProperty(LWJGL_EXTRACT_PROPERTY) == null) {
            System.setProperty(LWJGL_EXTRACT_PROPERTY, this.getNativeExtractDirectory().toString());
        }

        if (System.getProperty(JAVACPP_CACHE_PROPERTY) == null) {
            System.setProperty(JAVACPP_CACHE_PROPERTY, this.getJavacppCacheDirectory().toString());
        }

        System.setProperty(JAVACPP_LINK_PATH_PROPERTY, nativeDirectory.toString());
        System.setProperty(JAVACPP_PRELOAD_PATH_PROPERTY, nativeDirectory.toString());
        System.setProperty(JAVACPP_PATHS_FIRST_PROPERTY, "true");
        System.setProperty(JAVACPP_CACHE_LIBRARIES_PROPERTY, "false");

        ImageIO.setUseCache(false);
    }
}
