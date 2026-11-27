package org.fogofwarlabs.sc4j.scr.game.test;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.TestResultUtils;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads the installed SC:R resources of {@link ExternalInput#INSTALLATION} once and shares them, together with the shared replay runs
 * ({@link SharedReplayRunScenario}), between the tests of one test JVM.
 * <p>
 * {@link GameResourcesExtension} injects one instance per test JVM and closes it when the test run ends.
 */
public final class InstallationTestResources implements AutoCloseable, CloseableResource {
    private static final @NotNull String SHARED_RUNS_FILE = "shared-replay-runs.txt";

    private @Nullable Path gamePath;
    private @Nullable GameData gameData;

    private final @NotNull Map<Path, SharedReplayRunScenario> replayRuns;
    private @Nullable SharedReplayRunScenario liveReplayRun;

    /**
     * Creates a {@link InstallationTestResources} instance that loads nothing until first use.
     */
    public InstallationTestResources() {
        this.gamePath = null;
        this.gameData = null;

        this.replayRuns    = new HashMap<>();
        this.liveReplayRun = null;
    }

    /**
     * @return the SC:R installation
     * @throws IllegalStateException if the installation is not configured or names no directory
     */
    public @NotNull Path getGamePath() {
        if (this.gamePath == null) {
            this.gamePath = ExternalInput.INSTALLATION.getPath();
        }

        return this.gamePath;
    }

    /**
     * @return the loaded game resources
     * @throws IOException if the installation cannot be read
     */
    public @NotNull GameData getGameData() throws IOException {
        if (this.gameData == null) {
            this.gameData = GameData.loadFrom(this.getGamePath());
        }

        return this.gameData;
    }

    /**
     * Returns the shared run of a replay, created on first use. Only one shared run keeps its live game at a time: using another replay's run
     * releases the live game of the previous one, whose end values stay available.
     *
     * @param replay the replay file
     * @return the shared run
     * @throws IOException if the installation or the replay cannot be read
     */
    public @NotNull SharedReplayRunScenario getReplayRun(@NotNull Path replay) throws IOException {
        Path key = replay.toAbsolutePath().normalize();
        SharedReplayRunScenario run = this.replayRuns.get(key);

        if (run == null) {
            run = new SharedReplayRunScenario(this.getGameData(), key);
            this.replayRuns.put(key, run);
        }

        if (this.liveReplayRun != null && this.liveReplayRun != run) {
            this.liveReplayRun.release();
        }

        this.liveReplayRun = run;

        return run;
    }


    /**
     * Closes this resource, relinquishing any underlying resources. This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code Exception}, implementers are <em>strongly</em> encouraged to declare concrete
     *         implementations of the {@code close} method to throw more specific exceptions, or to throw no exception at all if the close operation cannot
     *         fail.
     *
     *         <p>
     *         Cases where the close operation may fail require careful attention by implementers. It is strongly advised to relinquish the underlying
     *         resources and to internally <em>mark</em> the resource as closed, prior to throwing the exception. The {@code close} method is unlikely to be
     *         invoked more than once and so this ensures that the resources are released in a timely manner. Furthermore it reduces problems that could arise
     *         when the resource wraps, or is wrapped, by another resource.
     *
     *         <p>
     *         <em>Implementers of this interface are also strongly advised to not have the {@code close} method throw {@link InterruptedException}.</em>
     *         <p>
     *         This exception interacts with a thread's interrupted status, and runtime misbehavior is likely to occur if an {@code InterruptedException} is
     *         {@linkplain Throwable#addSuppressed suppressed}.
     *         <p>
     *         More generally, if it would cause problems for an exception to be suppressed, the {@code AutoCloseable.close} method should not throw it.
     *
     *         <p>
     *         Note that unlike the {@link java.io.Closeable#close close} method of {@link java.io.Closeable}, this {@code close} method is <em>not</em>
     *         required to be idempotent. In other words, calling this {@code close} method more than once may have some visible side effect, unlike
     *         {@code Closeable.close} which is required to have no effect if called more than once.
     *         <p>
     *         However, implementers of this interface are strongly encouraged to make their {@code close} methods idempotent.
     * @implSpec Releases the shared replay runs, then closes the loaded game data and clears its cache.
     */
    @Override
    public void close() {
        GameData loaded = this.gameData;
        StringBuilder starts = new StringBuilder();

        for (Map.Entry<Path, SharedReplayRunScenario> entry : this.replayRuns.entrySet()) {
            starts.append(entry.getKey().getFileName()).append('\t').append(entry.getValue().getStartCount()).append('\n');
            entry.getValue().release();
        }

        // The start counts show whether the tests of this JVM simulated each shared replay once.
        if (!this.replayRuns.isEmpty()) {
            IgnoreException.execute(
                    () -> Files.writeString(TestResultUtils.getResultPath(SHARED_RUNS_FILE), starts, StandardCharsets.UTF_8)
            );
        }

        this.liveReplayRun = null;
        this.replayRuns.clear();
        this.gameData = null;

        if (loaded != null) {
            IgnoreException.execute(
                    loaded::close
            );
        }
    }
}
