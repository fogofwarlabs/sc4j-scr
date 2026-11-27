package org.fogofwarlabs.sc4j.scr.data.format.casc;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Represents an opened CASC storage of a StarCraft: Remastered installation.
 * <p>
 * Reads the build information, the build configuration, the index files, the encoding file, and the root file, then exposes file extraction
 * by file name.
 */
public final class CascStorage implements AutoCloseable {
    private static final @NotNull String DATA_DIRECTORY_NAME = "Data";
    /**
     * The directory of the configuration files inside the {@code Data} directory.
     */
    private static final @NotNull String CONFIGURATION_DIRECTORY_NAME = "config";
    /**
     * The directory of the index and data files inside the {@code Data} directory.
     */
    private static final @NotNull String DATA_FILES_DIRECTORY_NAME = "data";
    /**
     * The name format of a data file, applied to its index.
     */
    private static final @NotNull String DATA_FILE_NAME_FORMAT = "data.%03d";
    private static final @NotNull String READ_ONLY_MODE = "r";
    /**
     * The build configuration key of the encoding file.
     */
    private static final @NotNull String ENCODING_CONFIGURATION_KEY = "encoding";
    /**
     * The build configuration key of the root file.
     */
    private static final @NotNull String ROOT_CONFIGURATION_KEY = "root";
    private static final int LOCAL_HEADER_SIZE = 30;

    /**
     * Parses the content of a file.
     *
     * @param <T> the parsed type
     */
    @FunctionalInterface
    public interface Parser<T> {
        /**
         * Parses the content of a file.
         *
         * @param contents the file content
         * @return the parsed value
         * @throws IOException if the content is invalid
         */
        @NotNull T parse(byte @NotNull [] contents) throws IOException;
    }

    /**
     * Creates a {@link CascStorage} instance by opening the CASC storage of the given game installation.
     *
     * @param gameRoot the game installation directory that holds {@code .build.info}, or the {@code Data} directory itself
     * @return the created {@link CascStorage} instance
     * @throws IOException if the storage cannot be opened
     */
    public static @NotNull CascStorage open(@NotNull Path gameRoot) throws IOException {
        Path dataRoot = Files.isDirectory(gameRoot.resolve(DATA_DIRECTORY_NAME)) ? gameRoot.resolve(DATA_DIRECTORY_NAME) : gameRoot;
        Path buildInfoFile = gameRoot.resolve(BuildInfo.FILE_NAME);

        if (!Files.isRegularFile(buildInfoFile)) {
            buildInfoFile = dataRoot.getParent() != null ? dataRoot.getParent().resolve(BuildInfo.FILE_NAME) : buildInfoFile;
        }

        BuildInfo buildInfo = BuildInfo.readFrom(buildInfoFile);
        BuildConfig buildConfig = BuildConfig.readFrom(dataRoot.resolve(CONFIGURATION_DIRECTORY_NAME), buildInfo.getBuildKey());
        CascIndex cascIndex = CascIndex.loadFrom(dataRoot.resolve(DATA_FILES_DIRECTORY_NAME));
        CascStorage storage = new CascStorage(dataRoot, buildInfo, cascIndex);

        try {
            // The encoding entry holds the content key and the encoding key of the encoding file.
            byte[] encodingKey = HexFormat.of().parseHex(buildConfig.getSecondHash(ENCODING_CONFIGURATION_KEY));
            EncodingFile encodingFile = EncodingFile.parse(storage.readFileByEncodingKey(encodingKey));
            byte[] rootContentKey = HexFormat.of().parseHex(buildConfig.getFirstHash(ROOT_CONFIGURATION_KEY));
            RootFile rootFile = RootFile.parse(storage.readFileByContentKey(encodingFile, rootContentKey));

            storage.encodingFile = encodingFile;
            storage.rootFile     = rootFile;
        } catch (IOException | RuntimeException exception) {
            storage.close();

            throw exception;
        }

        return storage;
    }


    private final @NotNull Path dataRoot;
    private final @NotNull BuildInfo buildInfo;

    private @Nullable CascIndex cascIndex;
    private final @NotNull Map<Integer, RandomAccessFile> dataFiles;
    private @Nullable EncodingFile encodingFile;
    private @Nullable RootFile rootFile;
    private final @NotNull Map<String, Object> parsedFiles;

    /**
     * Creates a {@link CascStorage} instance.
     *
     * @param dataRoot  the CASC {@code Data} directory
     * @param buildInfo the build information
     * @param cascIndex the loaded index files
     */
    private CascStorage(@NotNull Path dataRoot, @NotNull BuildInfo buildInfo, @NotNull CascIndex cascIndex) {
        this.dataRoot  = dataRoot;
        this.buildInfo = buildInfo;

        this.cascIndex    = cascIndex;
        this.dataFiles    = new HashMap<>();
        this.encodingFile = null;
        this.rootFile     = null;
        this.parsedFiles  = new HashMap<>();
    }

    /**
     * @return the installation directory that holds the CASC {@code Data} directory (and {@code x86_64/StarCraft.exe})
     */
    public @NotNull Path getInstallRoot() {
        Path parent = this.dataRoot.getParent();

        return parent == null ? this.dataRoot : parent;
    }

    /**
     * @return the build version string, for example {@code "1.23.10.13515"}
     */
    public @NotNull String getBuildVersion() {
        return this.buildInfo.getVersion();
    }

    /**
     * @return the file names in root file order
     * @throws IllegalStateException if the storage is closed
     */
    public @NotNull List<String> getFileNames() {
        return this.requireRootFile().getFileNames();
    }

    private @NotNull CascIndex requireCascIndex() {
        if (this.cascIndex == null) {
            throw Exceptions.newIllegalStateException("The storage is not opened.");
        }

        return this.cascIndex;
    }

    private @NotNull EncodingFile requireEncodingFile() {
        if (this.encodingFile == null) {
            throw Exceptions.newIllegalStateException("The storage is not opened.");
        }

        return this.encodingFile;
    }

    private @NotNull RootFile requireRootFile() {
        if (this.rootFile == null) {
            throw Exceptions.newIllegalStateException("The storage is not opened.");
        }

        return this.rootFile;
    }


    /**
     * Checks whether the storage holds the given file.
     *
     * @param fileName the file name; both slash directions and any letter case are accepted
     * @return {@code true} if the file exists, {@code false} otherwise
     * @throws IllegalStateException if the storage is closed
     */
    public boolean hasFile(@NotNull String fileName) {
        return this.requireRootFile().findContentKey(fileName) != null;
    }

    /**
     * Checks whether the file's data is present in the local archives. A partial installation lists every locale in its
     * root file but stores the data of the installed locales only.
     *
     * @param fileName the file name; both slash directions and any letter case are accepted
     * @return {@code true} if the file can be read locally, {@code false} otherwise
     * @throws IllegalStateException if the storage is closed
     */
    public boolean isLocallyAvailable(@NotNull String fileName) {
        byte[] contentKey = this.requireRootFile().findContentKey(fileName);

        if (contentKey == null) {
            return false;
        }

        EncodingFile.Entry entry = this.requireEncodingFile().findEntry(contentKey);

        return entry != null && this.requireCascIndex().find(entry.getEncodingKey()) != null;
    }

    /**
     * Reads the content of the given file.
     *
     * @param fileName the file name; both slash directions and any letter case are accepted
     * @return the file content
     * @throws IOException           if the file does not exist or cannot be decoded
     * @throws IllegalStateException if the storage is closed
     */
    public byte @NotNull [] readFile(@NotNull String fileName) throws IOException {
        byte[] contentKey = this.requireRootFile().findContentKey(fileName);

        if (contentKey == null) {
            throw Exceptions.newIOException("The file does not exist in the storage: {0}", fileName);
        }

        return this.readFileByContentKey(this.requireEncodingFile(), contentKey);
    }

    /**
     * Reads and parses a file once per storage: later calls with the same file name and type return the same instance.
     * <p>
     * Only for values nobody modifies, such as the {@code images.dat} table shared by the game data and the renderers.
     *
     * @param fileName the file name
     * @param type     the parsed type, part of the cache key
     * @param parser   parses the content on the first call
     * @param <T>      the parsed type
     * @return the shared parsed value
     * @throws IOException if the file cannot be read or parsed
     */
    public synchronized <T> @NotNull T readParsed(@NotNull String fileName, @NotNull Class<T> type, @NotNull Parser<T> parser) throws IOException {
        String key = type.getName() + ':' + fileName.replace('\\', '/').toLowerCase(Locale.ROOT);
        Object cached = this.parsedFiles.get(key);

        if (cached != null) {
            return type.cast(cached);
        }

        T value = parser.parse(this.readFile(fileName));

        this.parsedFiles.put(key, value);

        return value;
    }

    /**
     * Finds the content key of the given file.
     *
     * @param fileName the file name; both slash directions and any letter case are accepted
     * @return the {@code 16}-byte content key, or {@code null} if the file does not exist
     * @throws IllegalStateException if the storage is closed
     */
    public byte @Nullable [] findContentKey(@NotNull String fileName) {
        return this.requireRootFile().findContentKey(fileName);
    }


    private byte @NotNull [] readFileByContentKey(@NotNull EncodingFile encodingFile, byte @NotNull [] contentKey) throws IOException {
        EncodingFile.Entry entry = encodingFile.findEntry(contentKey);

        if (entry == null) {
            throw Exceptions.newIOException("The content key misses from the encoding file: {0}", HexFormat.of().formatHex(contentKey));
        }

        return this.readFileByEncodingKey(entry.getEncodingKey());
    }

    private byte @NotNull [] readFileByEncodingKey(byte @NotNull [] encodingKey) throws IOException {
        CascIndex.Entry entry = this.requireCascIndex().find(encodingKey);

        if (entry == null) {
            throw Exceptions.newIOException("The encoding key misses from the index files: {0}", HexFormat.of().formatHex(encodingKey));
        }

        RandomAccessFile dataFile = this.openDataFile(entry.getDataFileIndex());
        byte[] localData = new byte[(int) entry.getSize()];

        synchronized (dataFile) {
            dataFile.seek(entry.getOffset());
            dataFile.readFully(localData);
        }

        if (localData.length < LOCAL_HEADER_SIZE) {
            throw Exceptions.newIOException("The local data is too small: {0}", localData.length);
        }

        return BlteUtils.decode(localData, LOCAL_HEADER_SIZE, localData.length - LOCAL_HEADER_SIZE);
    }

    private @NotNull RandomAccessFile openDataFile(int dataFileIndex) throws IOException {
        // The HD image loader threads read concurrently; each data file handle serializes its own seek and read.
        synchronized (this.dataFiles) {
            RandomAccessFile dataFile = this.dataFiles.get(dataFileIndex);

            if (dataFile == null) {
                Path dataFilePath = this.dataRoot.resolve(DATA_FILES_DIRECTORY_NAME).resolve(String.format(DATA_FILE_NAME_FORMAT, dataFileIndex));

                dataFile = new RandomAccessFile(dataFilePath.toFile(), READ_ONLY_MODE);
                this.dataFiles.put(dataFileIndex, dataFile);
            }

            return dataFile;
        }
    }


    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code
     *         Exception}, implementers are <em>strongly</em> encouraged to
     *         declare concrete implementations of the {@code close} method to
     *         throw more specific exceptions, or to throw no exception at all
     *         if the close operation cannot fail.
     *
     *         <p>
     *         Cases where the close operation may fail require careful
     *         attention by implementers. It is strongly advised to relinquish
     *         the underlying resources and to internally <em>mark</em> the
     *         resource as closed, prior to throwing the exception. The {@code
     *         close} method is unlikely to be invoked more than once and so
     *         this ensures that the resources are released in a timely manner.
     *         Furthermore it reduces problems that could arise when the resource
     *         wraps, or is wrapped, by another resource.
     *
     *         <p>
     *         <em>Implementers of this interface are also strongly advised
     *         to not have the {@code close} method throw {@link
     *         InterruptedException}.</em>
     *
     *         <p>
     *         This exception interacts with a thread's interrupted status,
     *         and runtime misbehavior is likely to occur if an {@code
     *         InterruptedException} is {@linkplain Throwable#addSuppressed
     *         suppressed}.
     *
     *         <p>
     *         More generally, if it would cause problems for an
     *         exception to be suppressed, the {@code AutoCloseable.close}
     *         method should not throw it.
     *
     *         <p>
     *         Note that unlike the {@link java.io.Closeable#close close}
     *         method of {@link java.io.Closeable}, this {@code close} method
     *         is <em>not</em> required to be idempotent.  In other words,
     *         calling this {@code close} method more than once may have some
     *         visible side effect, unlike {@code Closeable.close} which is
     *         required to have no effect if called more than once.
     *
     *         <p>
     *         However, implementers of this interface are strongly encouraged
     *         to make their {@code close} methods idempotent.
     * @implSpec Closes the storage: releases the parsed files, the root and encoding tables, the data file handles and the index, in
     *         the reverse order of their acquisition. A closed storage reads no file, so an object that keeps a reference to it
     *         after closing does not keep its tables alive.
     */
    @Override
    public void close() {
        synchronized (this) {
            this.parsedFiles.clear();
        }

        this.rootFile     = null;
        this.encodingFile = null;

        for (RandomAccessFile dataFile : this.dataFiles.values()) {
            try {
                dataFile.close();
            } catch (IOException exception) {
                // Ignore close failures.
            }
        }

        this.dataFiles.clear();
        this.cascIndex = null;
    }
}
