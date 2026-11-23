package org.fogofwarlabs.sc4j.scr.game.save;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommand;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayHeaderBuilder;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayWriter;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.state.GameRulesVersion;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Compares save normalization and the continuation of independently loaded games.
 */
public final class SaveGameRoundTripScenario {
    /**
     * The slowest game speed option.
     */
    private static final int MINIMUM_GAME_SPEED = 0;

    /**
     * The environment variable whose comma-separated parts select the cases that run.
     */
    private static final @NotNull String CASE_FILTER_VARIABLE = "SC4J_SAVE_CASES";
    private static final @NotNull String CASE_FILTER_SEPARATOR = ",";

    /**
     * The outcome of one round trip.
     */
    static final class Result {
        private final @NotNull List<String> differingParts;
        private final boolean resavedIdentically;
        private final int firstHashMismatchFrame;
        private final int firstContinuationMismatchFrame;
        private final int savedBytes;

        /**
         * Creates a {@link Result} instance for the supplied comparison outcome.
         *
         * @param differingParts                 the saved parts in which the re-save of the loaded game differs from the save
         * @param resavedIdentically             whether repeated load and save produced identical bytes
         * @param firstHashMismatchFrame         the first hash mismatch frame
         * @param firstContinuationMismatchFrame the first continuation mismatch frame
         * @param savedBytes                     the original serialized file length
         */
        private Result(@NotNull List<String> differingParts,
                       boolean resavedIdentically,
                       int firstHashMismatchFrame,
                       int firstContinuationMismatchFrame,
                       int savedBytes) {
            this.differingParts                 = differingParts;
            this.resavedIdentically             = resavedIdentically;
            this.firstHashMismatchFrame         = firstHashMismatchFrame;
            this.firstContinuationMismatchFrame = firstContinuationMismatchFrame;
            this.savedBytes                     = savedBytes;
        }

        /**
         * @return {@code true} if the re-save of the loaded game, loaded and saved again, gave the same file, {@code false} otherwise
         */
        boolean isResavedIdentically() {
            return this.resavedIdentically;
        }

        /**
         * @return the first frame whose state hashes differ between the game loaded from the saved file and the game loaded from its re-save, or {@code -1} if none differs
         */
        int getFirstHashMismatchFrame() {
            return this.firstHashMismatchFrame;
        }


        /**
         * Describes the outcome: the resave identity, the mismatch frames and the saved parts in which the first loaded game's re-save differs from
         * the saved file.
         *
         * @return the readable summary
         */
        @NotNull String describe() {
            return "resaved identically " + this.resavedIdentically + ", first hash mismatch frame " + this.firstHashMismatchFrame + ", " +
                   this.savedBytes + " bytes; against the saved game the re-save differs in parts " + this.differingParts +
                   ", first continuation mismatch frame " + this.firstContinuationMismatchFrame;
        }
    }

    /**
     * A game loaded from a saved game.
     */
    static final class Loaded {
        private final @NotNull ReplayRuntime runtime;
        private final @Nullable LiveGame live;

        /**
         * Creates a {@link Loaded} instance.
         *
         * @param runtime the loaded game's runtime
         * @param live    its live session, or {@code null}
         */
        Loaded(@NotNull ReplayRuntime runtime, @Nullable LiveGame live) {
            this.runtime = runtime;
            this.live    = live;
        }

        /**
         * @return the loaded game's runtime
         */
        @NotNull ReplayRuntime getRuntime() {
            return this.runtime;
        }

        /**
         * @return the loaded game's live session, or {@code null} for a replay
         */
        @Nullable LiveGame getLive() {
            return this.live;
        }
    }

    /**
     * Starts a game from a saved game and reads the saved game into it.
     */
    interface Loader {
        /**
         * Loads a saved game.
         *
         * @param file the saved game
         * @return the loaded game
         * @throws IOException if the saved game cannot start a game
         */
        @NotNull Loaded load(@NotNull SaveGameFile file) throws IOException;
    }

    /**
     * Steps one frame of a live game.
     */
    interface Stepper {
        /**
         * Steps one frame.
         *
         * @param runtime the game's runtime
         * @param live    the game's live session
         */
        void stepFrame(@NotNull ReplayRuntime runtime, @Nullable LiveGame live);
    }

    /**
     * Skips the calling case unless it is selected ({@link #isSelected}).
     *
     * @param name the case name
     */
    public static void assumeSelected(@NotNull String name) {
        Assumptions.assumeTrue(isSelected(name), "Not selected by SC4J_SAVE_CASES.");
    }

    /**
     * Tells whether a case runs: all cases, or with the environment variable {@code SC4J_SAVE_CASES} set, the cases whose name contains one of its
     * comma-separated parts (a development aid for running one case).
     *
     * @param name the case name
     * @return {@code true} if the case runs, {@code false} otherwise
     */
    private static boolean isSelected(@NotNull String name) {
        String filter = System.getenv(CASE_FILTER_VARIABLE);

        if (filter == null || filter.isBlank()) {
            return true;
        }

        for (String part : filter.split(CASE_FILTER_SEPARATOR)) {
            if (!part.isBlank() && name.contains(part.trim())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Creates a {@link SaveGameDescription} instance for a custom game without players, scenario title or map path.
     *
     * @param multiplayer whether the saved game is a network game
     * @param name        the save name, encoded in UTF-8
     * @return the created {@link SaveGameDescription} instance
     */
    static @NotNull SaveGameDescription createDescription(boolean multiplayer, @NotNull String name) {
        return new SaveGameDescription(SaveGameDescription.CLIENT_VERSION,
                                       multiplayer,
                                       name.getBytes(StandardCharsets.UTF_8),
                                       List.of(),
                                       SaveGameDescription.CUSTOM_GAME_TYPE,
                                       "",
                                       "",
                                       0);
    }

    /**
     * Creates a {@link ReplayRuntime} instance started at the recorded game speed.
     *
     * @param gameData the installed game data
     * @param replay   the replay
     * @return the created {@link ReplayRuntime} instance after startup
     * @throws IOException           if the fixture resources cannot be loaded
     * @throws NullPointerException  if the replay has no map
     * @throws IllegalStateException if the replay does not start
     */
    public static @NotNull ReplayRuntime startReplay(@NotNull GameData gameData, @NotNull ReplayFile replay) throws IOException {
        byte[] mapData = replay.getMapData();

        if (mapData == null) {
            throw Exceptions.newNullPointerException();
        }

        ReplayRuntime runtime = new ReplayRuntime(replay,
                                                  ChkFile.parseTolerant(mapData),
                                                  gameData,
                                                  RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT,
                                                  RunningGameScenario.INSTRUCTION_LIMIT);

        runtime.start(0, 0);

        if (runtime.getFailure() != null) {
            throw Exceptions.newIllegalStateException(runtime.getFailure(), "The replay did not start.");
        }

        runtime.setGameSpeedOption(Math.clamp(replay.getHeader().getSpeed(), MINIMUM_GAME_SPEED, GameSpeed.FASTEST));

        return runtime;
    }

    /**
     * Creates a {@link ReplayRuntime} instance from the save and the later replay commands; the caller reads the saved parts into the started game.
     *
     * @param gameData the installed game data
     * @param file     the saved game
     * @param replay   the replay whose later commands the game runs
     * @param frame    the save frame
     * @return the created {@link ReplayRuntime} instance after startup
     * @throws IOException           if the saved game cannot start a game
     * @throws NullPointerException  if the saved game has no saved header or no limits
     * @throws IllegalStateException if the saved game does not start
     */
    static @NotNull ReplayRuntime startSavedGame(@NotNull GameData gameData,
                                                 @NotNull SaveGameFile file,
                                                 @NotNull ReplayFile replay,
                                                 int frame) throws IOException {
        SaveGameSession session = SaveGameSession.read(file);
        byte[] header = session.getSetup().getSavedHeader();

        if (header == null) {
            throw Exceptions.newNullPointerException();
        }

        byte[] mapData = session.getSetup().getMapData();
        List<ReplayCommand> commands = new ArrayList<>();

        for (ReplayCommand command : replay.getCommands()) {
            if (command.getFrame() > frame) {
                commands.add(command);
            }
        }

        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(ReplayHeaderBuilder.FRAMES_OFFSET, (int) replay.getHeader().getFrames());

        int[] limits = file.getLimits();

        if (limits == null) {
            throw Exceptions.newNullPointerException();
        }

        ReplayFile loaded = ReplayFile.create(header,
                                              commands,
                                              mapData,
                                              limits,
                                              ReplayWriter.encodeVersionBlock(GameRulesVersion.CURRENT_BUILD, 0),
                                              ReplayWriter.CURRENT_FIX_FLAGS);

        loaded.markSavedGameStart();

        ReplayRuntime runtime = new ReplayRuntime(loaded,
                                                  ChkFile.parseTolerant(mapData),
                                                  gameData,
                                                  RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT,
                                                  RunningGameScenario.INSTRUCTION_LIMIT);

        runtime.start(0, 0);

        if (runtime.getFailure() != null) {
            throw Exceptions.newIllegalStateException(runtime.getFailure(), "The saved game did not start.");
        }

        runtime.setGameSpeedOption(Math.clamp(replay.getHeader().getSpeed(), MINIMUM_GAME_SPEED, GameSpeed.FASTEST));

        return runtime;
    }

    /**
     * Steps a replay runtime to a frame.
     *
     * @param runtime the runtime
     * @param frame   the frame
     */
    public static void advanceToFrame(@NotNull ReplayRuntime runtime, int frame) {
        while (runtime.getGame().getCurrentFrame() < frame) {
            if (!stepFrame(runtime)) {
                return;
            }
        }
    }

    /**
     * Steps a replay runtime one frame.
     *
     * @param runtime the runtime
     * @return {@code true} if a frame was advanced, {@code false} otherwise
     */
    static boolean stepFrame(@NotNull ReplayRuntime runtime) {
        return runtime.step(0, runtime.getGame().getCurrentFrame() * runtime.getFrameDuration());
    }

    /**
     * Advances the runtime and its optional live session in the original callback order.
     *
     * @param runtime the game runtime
     * @param live    the live session, or {@code null}
     * @param stepper the live callback, or {@code null} for replay stepping
     * @return {@code true} if a live step ran or the replay advanced, {@code false} otherwise
     */
    private static boolean stepFrame(@NotNull ReplayRuntime runtime, @Nullable LiveGame live, @Nullable Stepper stepper) {
        if (stepper == null) {
            return stepFrame(runtime);
        }

        stepper.stepFrame(runtime, live);

        return true;
    }


    /**
     * Runs a replay to a frame, saves it and checks the loads of the file ({@link #compare}); a loaded game continues with the replay's commands
     * after the save frame.
     *
     * @param gameData       the installed game data
     * @param replay         the replay
     * @param saveFrame      the frame to save at
     * @param continueFrames the frames the games continue for the hash comparisons
     * @return the outcome
     * @throws IOException if the saved file cannot be loaded
     */
    static @NotNull Result runReplay(@NotNull GameData gameData, @NotNull ReplayFile replay, int saveFrame, int continueFrames) throws IOException {
        ReplayRuntime saved = startReplay(gameData, replay);

        advanceToFrame(saved, saveFrame);

        return compare(saved, null, file -> {
            ReplayRuntime loaded = startSavedGame(gameData, file, replay, saveFrame);

            SaveGameUtils.read(file, new SaveGameContext(loaded, null));

            return new Loaded(loaded, null);
        }, continueFrames, null);
    }

    /**
     * Saves a running game, loads the file, saves and loads that game again and compares: the saved file and the first loaded game's re-save part by
     * part and the saved and the first loaded game by state hash (reported), the re-save of the second loaded game with the first re-save (identical)
     * and the two loaded games by state hash while they continue (identical).
     *
     * @param saved          the running game
     * @param savedLive      its live session, or {@code null}
     * @param loader         loads a saved game
     * @param continueFrames the frames the games continue for the hash comparisons
     * @param stepper        steps a live game's frame, or {@code null} for replays
     * @return the outcome
     * @throws IOException if a saved file cannot be loaded
     */
    static @NotNull Result compare(@NotNull ReplayRuntime saved,
                                   @Nullable LiveGame savedLive,
                                   @NotNull Loader loader,
                                   int continueFrames,
                                   @Nullable Stepper stepper) throws IOException {
        byte[] savedFile = write(saved, savedLive);
        Loaded first = loader.load(SaveGameFile.parse(savedFile));
        byte[] resaved = write(first.getRuntime(), first.getLive());
        Loaded second = loader.load(SaveGameFile.parse(resaved));
        boolean resavedIdentically = Arrays.equals(resaved, write(second.getRuntime(), second.getLive()));
        int hashMismatchFrame = -1;
        int continuationMismatchFrame = -1;

        for (int frame = 0; frame < continueFrames; frame++) {
            if (!stepFrame(saved, savedLive, stepper) |
                !stepFrame(first.getRuntime(), first.getLive(), stepper) |
                !stepFrame(second.getRuntime(), second.getLive(), stepper)) {
                break;
            }

            long firstHash = first.getRuntime().getGame().computeStateHash();

            if (continuationMismatchFrame < 0 && saved.getGame().computeStateHash() != firstHash) {
                continuationMismatchFrame = saved.getGame().getCurrentFrame();
            }

            if (second.getRuntime().getGame().computeStateHash() != firstHash) {
                hashMismatchFrame = first.getRuntime().getGame().getCurrentFrame();

                break;
            }
        }

        return new Result(findDifferingParts(savedFile, resaved), resavedIdentically, hashMismatchFrame, continuationMismatchFrame, savedFile.length);
    }

    /**
     * Lists the saved parts in which two save files differ.
     *
     * @param expected the first save file
     * @param actual   the second save file
     * @return the names of the differing parts, in file order
     */
    static @NotNull List<String> findDifferingParts(byte @NotNull [] expected, byte @NotNull [] actual) {
        Map<String, byte[]> expectedParts = SaveGameLayoutCheck.parse(expected).getParts();
        Map<String, byte[]> actualParts = SaveGameLayoutCheck.parse(actual).getParts();
        List<String> differing = new ArrayList<>();

        for (Map.Entry<String, byte[]> part : expectedParts.entrySet()) {
            if (!Arrays.equals(part.getValue(), actualParts.get(part.getKey()))) {
                differing.add(part.getKey());
            }
        }

        return differing;
    }

    /**
     * Serializes the runtime with its optional live session.
     *
     * @param runtime the game runtime
     * @param live    the live session, or {@code null}
     * @return the serialized save file bytes
     */
    private static byte @NotNull [] write(@NotNull ReplayRuntime runtime, @Nullable LiveGame live) {
        SaveGameDescription description = createDescription(live != null && live.isNetworkGame(), "round trip");

        return SaveGameUtils.write(new SaveGameContext(runtime, live), description).toByteArray();
    }


    /**
     * Creates a {@link SaveGameRoundTripScenario} instance.
     */
    private SaveGameRoundTripScenario() {
    }
}
