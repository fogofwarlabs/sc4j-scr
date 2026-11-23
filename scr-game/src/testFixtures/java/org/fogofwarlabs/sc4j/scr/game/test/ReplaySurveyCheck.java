package org.fogofwarlabs.sc4j.scr.game.test;

import org.fogofwarlabs.sc4j.scr.core.test.golden.GoldenValues;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.replay.GameSourceFile;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.CommandValidityCheck.Counter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Plays a replay to its end in replay mode and classifies it from what can be observed: the run itself and the recorded commands that the simulated
 * state does not explain ({@link CommandValidityCheck}).
 * <p>
 * A replay is {@link Classification#ERROR} when the run fails (an exception, a runtime that cannot be constructed, a stall), when its game ends
 * before the last frame as the native game ends it (an EUD failure, a Restart Game command), or when it completes fewer frames than its header. It is
 * {@link Classification#SUSPICIOUS} when one player's commands show a {@link Signal} within {@value #SPAN_WINDOWS} consecutive windows of
 * {@value CommandValidityCheck#WINDOW_FRAMES} frames, and {@link Classification#OK} otherwise.
 * <p>
 * {@link #run} plays the replay in a run of its own; {@link #of} classifies the shared run of a replay ({@link SharedReplayRunScenario}) that other
 * checks of the same replay read. Both classify the same end values and counts.
 */
public final class ReplaySurveyCheck {
    /**
     * The consecutive windows over which the events of a signal are counted.
     */
    public static final int SPAN_WINDOWS = 3;
    /**
     * The fewest dead, stale or foreign selected handles, or orders without a selection, of a span that form a signal.
     */
    public static final int MINIMUM_EVENTS = 10;
    /**
     * The smallest share in percent of such events among the selected handles or orders of a span that forms a signal.
     */
    public static final int EVENT_PERCENT = 10;
    /**
     * The fewest foreign units in selections of several units of a span that form a signal.
     */
    public static final int FOREIGN_EVENTS = 3;
    /**
     * The fewest target handles of no live unit of a span that form a signal.
     */
    public static final int MINIMUM_MISSING_TARGETS = 20;
    /**
     * The smallest share in percent of target handles of no live unit among the targets of a span that forms a signal.
     */
    public static final int MISSING_TARGET_PERCENT = 50;
    /**
     * The most selections and orders of a defeated player that form no signal.
     */
    public static final int AFTER_DEFEAT_EVENTS = 20;
    /**
     * The end values of the run that the report records after the survey columns ({@link ReplayRunScenario#putValues}).
     */
    public static final @NotNull List<String> REPORTED_END_VALUES = List.of(
            ReplayRunScenario.OBSERVABLE_HASH_KEY, ReplayRunScenario.OBSERVABLE_TRACE_KEY,
            ReplayRunScenario.RANDOM_STATE_KEY, ReplayRunScenario.RANDOM_TOTAL_KEY, ReplayRunScenario.RANDOM_TRACE_KEY
    );
    /**
     * The column names of {@link #describeReport()}, in order: the survey columns, then {@link #REPORTED_END_VALUES}.
     */
    public static final @NotNull List<String> REPORT_COLUMNS = Stream.concat(
            Stream.of("class", "signals", "first.signal.frame", "frames.header",
                      "frames.completed", "failure", "failure.frame", "end",
                      "seconds", "ms.per.1000.frames", "players", "windows"),
            REPORTED_END_VALUES.stream()
    ).toList();

    private static final @NotNull String NONE = "none";
    private static final int NO_FRAME = -1;

    private static final int PERCENT = 100;
    private static final double NANOSECONDS_PER_SECOND = 1.0e9;
    private static final double MILLISECONDS_PER_SECOND = 1.0e3;
    private static final int FRAMES_PER_COST_UNIT = 1000;

    /**
     * Names the result of a survey.
     */
    public enum Classification {
        /**
         * The replay ran to its end and showed no signal.
         */
        OK,
        /**
         * The replay ran to its end, but its commands show a signal of divergence.
         */
        SUSPICIOUS,
        /**
         * The run failed or ended early, as a failure or as the native game ends it.
         */
        ERROR;

        /**
         * @return the lowercase name used in reports and in the known problem list
         */
        public @NotNull String getName() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Names the observations that classify a replay.
     */
    public enum Signal {
        /**
         * The run failed: an exception, a runtime that cannot be constructed, or a stall.
         */
        FAILURE,
        /**
         * The game ended before its last frame at an EUD access that the native game does not serve either; the native game ends the session at the
         * same frame.
         */
        EUD_FAILURE,
        /**
         * The game ended before its last frame at a Restart Game command outside a network session; the native game ends the replay session at the
         * same frame (its scenario reload from the replay file fails).
         */
        RESTART,
        /**
         * The run completed fewer frames than the replay header.
         */
        LENGTH,
        /**
         * Selected handles of dead, stale or foreign units: at least {@value ReplaySurveyCheck#MINIMUM_EVENTS} and
         * {@value ReplaySurveyCheck#EVENT_PERCENT} percent of the selected handles of a span.
         */
        SELECTION,
        /**
         * Orders without a selection: at least {@value ReplaySurveyCheck#MINIMUM_EVENTS} and {@value ReplaySurveyCheck#EVENT_PERCENT} percent of the
         * orders of a span.
         */
        ORDER,
        /**
         * At least {@value ReplaySurveyCheck#FOREIGN_EVENTS} units of another player in selections of several units within a span.
         */
        FOREIGN,
        /**
         * Target handles of no live unit: at least {@value ReplaySurveyCheck#MINIMUM_MISSING_TARGETS} and
         * {@value ReplaySurveyCheck#MISSING_TARGET_PERCENT} percent of the targets of a span.
         */
        TARGET,
        /**
         * More than {@value ReplaySurveyCheck#AFTER_DEFEAT_EVENTS} selections and orders of a player the simulation counts as defeated.
         */
        DEFEAT;

        /**
         * @return the lowercase name used in reports
         */
        public @NotNull String getName() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }


    /**
     * Creates a {@link ReplaySurveyCheck} instance that plays a replay in a run of its own and classifies it.
     *
     * @param gameData the installed game data
     * @param replay   the replay file
     * @return the created {@link ReplaySurveyCheck} instance
     * @throws IOException if the replay cannot be read
     */
    public static @NotNull ReplaySurveyCheck run(@NotNull GameData gameData, @NotNull Path replay) throws IOException {
        long start = System.nanoTime();
        byte[] contents = Files.readAllBytes(replay);
        ReplayFile file = ReplayFile.parse(contents);
        int headerFrames = (int) Math.min(Integer.MAX_VALUE, file.getHeader().getFrames());
        CommandValidityCheck validity = new CommandValidityCheck();
        GoldenValues values = new GoldenValues();
        ReplayRuntime runtime;

        try {
            runtime = RunningGameScenario.createRuntime(file, gameData);
        } catch (RuntimeException exception) {
            values.put(ReplayRunScenario.FAILURE_KEY, ReplayRunScenario.describeConstructionFailure(exception));

            return new ReplaySurveyCheck(headerFrames, values, validity, System.nanoTime() - start);
        }

        runtime.setSourceFile(GameSourceFile.create(replay, contents));
        runtime.setCommandObserver(validity);
        runtime.start(0, 0);

        ReplayRunScenario run = new ReplayRunScenario(runtime, 0);

        run.runTo(headerFrames);
        run.putValues(values);

        return new ReplaySurveyCheck(headerFrames, values, validity, System.nanoTime() - start);
    }

    /**
     * Creates a {@link ReplaySurveyCheck} instance that classifies the shared run of a replay, advancing it to the replay's last frame first.
     *
     * @param run the shared run
     * @return the created {@link ReplaySurveyCheck} instance
     * @throws IOException if the installed resources of the run cannot be read
     */
    public static @NotNull ReplaySurveyCheck of(@NotNull SharedReplayRunScenario run) throws IOException {
        GoldenValues values = run.getEndValues();

        return new ReplaySurveyCheck(run.getLastFrame(), values, run.getEndValidity(), run.getElapsedNanoseconds());
    }

    private static int findSpanFirstFrame(@NotNull CommandValidityCheck validity, int player, int firstWindow, Counter @NotNull ... counters) {
        int first = NO_FRAME;

        for (int window = firstWindow; window < firstWindow + SPAN_WINDOWS; window++) {
            for (Counter counter : counters) {
                int frame = validity.getFirstFrame(player, window, counter);

                if (frame != NO_FRAME && (first == NO_FRAME || frame < first)) {
                    first = frame;
                }
            }
        }

        return first;
    }


    private final int headerFrames;
    private final int completedFrames;
    private final @NotNull String failure;
    private final int failureFrame;
    private final @NotNull String end;
    private final @NotNull GoldenValues endValues;
    private final @NotNull CommandValidityCheck validity;
    private final long elapsedNanoseconds;

    private final @NotNull Map<Signal, Integer> signals;

    /**
     * Creates a {@link ReplaySurveyCheck} instance that classifies the end values and the command counts of a run.
     *
     * @param headerFrames       the frame count of the replay header
     * @param endValues          the end values of the run ({@link ReplayRunScenario#putValues}); only {@code failure} if the runtime could not be constructed
     * @param validity           the command counts of the run
     * @param elapsedNanoseconds the nanoseconds the run took
     */
    private ReplaySurveyCheck(int headerFrames, @NotNull GoldenValues endValues, @NotNull CommandValidityCheck validity, long elapsedNanoseconds) {
        this.headerFrames       = headerFrames;
        this.completedFrames    = Integer.parseInt(endValues.get(ReplayRunScenario.COMPLETED_FRAMES_KEY, Integer.toString(NO_FRAME)));
        this.failure            = endValues.get(ReplayRunScenario.FAILURE_KEY, NONE);
        this.failureFrame       = Integer.parseInt(endValues.get(ReplayRunScenario.FAILURE_FRAME_KEY, Integer.toString(NO_FRAME)));
        this.end                = endValues.get(ReplayRunScenario.END_KEY, NONE);
        this.endValues          = endValues;
        this.validity           = validity;
        this.elapsedNanoseconds = elapsedNanoseconds;

        this.signals = new EnumMap<>(Signal.class);

        String endKind = endValues.get(ReplayRunScenario.END_KIND_KEY, NONE);

        if (!NONE.equals(this.failure)) {
            this.signals.put(Signal.FAILURE, this.failureFrame);
        } else if (Signal.EUD_FAILURE.getName().equals(endKind)) {
            this.signals.put(Signal.EUD_FAILURE, this.completedFrames);
        } else if (Signal.RESTART.getName().equals(endKind)) {
            this.signals.put(Signal.RESTART, this.completedFrames);
        } else if (this.completedFrames < headerFrames) {
            this.signals.put(Signal.LENGTH, this.completedFrames);
        }

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            this.findSignals(player);
        }
    }

    /**
     * @return the failure of the run, or {@code none}
     */
    public @NotNull String getFailure() {
        return this.failure;
    }

    /**
     * @return the native end state of a game that ended before its last frame ({@link ReplayRunScenario#putValues}), or {@code none}
     */
    public @NotNull String getEnd() {
        return this.end;
    }

    /**
     * @return the seconds the run took, including parsing and startup
     */
    public double getElapsedSeconds() {
        return this.elapsedNanoseconds / NANOSECONDS_PER_SECOND;
    }

    /**
     * @return the milliseconds the run took per {@code 1000} completed frames, or {@code 0} if no frame completed
     */
    public double getMillisecondsPerThousandFrames() {
        return this.completedFrames <= 0 ? 0 : this.getElapsedSeconds() * MILLISECONDS_PER_SECOND * FRAMES_PER_COST_UNIT / this.completedFrames;
    }

    /**
     * @return the classification
     */
    public @NotNull Classification getClassification() {
        if (this.signals.containsKey(Signal.FAILURE) || this.signals.containsKey(Signal.EUD_FAILURE) || this.signals.containsKey(Signal.RESTART) ||
            this.signals.containsKey(Signal.LENGTH)) {
            return Classification.ERROR;
        }

        return this.signals.isEmpty() ? Classification.OK : Classification.SUSPICIOUS;
    }

    /**
     * @return the first frame of any signal, or {@code -1} if there is none
     */
    public int getFirstSignalFrame() {
        int first = NO_FRAME;

        for (int frame : this.signals.values()) {
            if (first == NO_FRAME || frame < first) {
                first = frame;
            }
        }

        return first;
    }

    /**
     * Describes the classification for failure messages.
     *
     * @return {@code <class> (<signals>, failure: <failure>, end: <end>)}
     */
    public @NotNull String describe() {
        return this.getClassification().getName() + " (" + this.describeSignals() + ", failure: " + this.failure + ", end: " + this.end + ")";
    }

    /**
     * Describes the survey as the values of the {@link #REPORT_COLUMNS} of a result table.
     *
     * @return the values in column order
     */
    public @NotNull List<String> describeReport() {
        List<String> values = new ArrayList<>(List.of(this.getClassification().getName(), this.describeSignals(),
                                                      Integer.toString(this.getFirstSignalFrame()), Integer.toString(this.headerFrames),
                                                      Integer.toString(this.completedFrames), this.failure, Integer.toString(this.failureFrame),
                                                      this.end,
                                                      String.format(Locale.ROOT, "%.3f", this.getElapsedSeconds()),
                                                      String.format(Locale.ROOT, "%.1f", this.getMillisecondsPerThousandFrames()),
                                                      this.describePlayers(), this.describeWindows()));

        for (String key : REPORTED_END_VALUES) {
            values.add(this.endValues.get(key, NONE));
        }

        return values;
    }

    /**
     * Describes the signals in the order of their first frame.
     *
     * @return {@code <signal>@<frame>} entries separated by {@code ,}, or {@code none}
     */
    public @NotNull String describeSignals() {
        List<Map.Entry<Signal, Integer>> entries = new ArrayList<>(this.signals.entrySet());

        if (entries.isEmpty()) {
            return NONE;
        }

        entries.sort(Map.Entry.comparingByValue());

        StringBuilder description = new StringBuilder();

        for (Map.Entry<Signal, Integer> entry : entries) {
            description.append(description.isEmpty() ? "" : ",").append(entry.getKey().getName()).append('@').append(entry.getValue());
        }

        return description.toString();
    }

    /**
     * Describes the command totals of every player that sent commands.
     *
     * @return one {@code p<slot>:<counts>} group per player, the counts in {@link Counter} order separated by {@code /}
     */
    public @NotNull String describePlayers() {
        StringBuilder description = new StringBuilder();

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            if (this.validity.getTotal(player, Counter.COMMANDS) == 0) {
                continue;
            }

            description.append(description.isEmpty() ? "" : " ").append('p').append(player).append(':');

            for (Counter counter : Counter.values()) {
                description.append(counter.ordinal() == 0 ? "" : "/").append(this.validity.getTotal(player, counter));
            }
        }

        return description.toString();
    }

    /**
     * Describes the per-window counts of every player that sent commands.
     *
     * @return one {@code p<slot>=<counts>|<counts>|...} group per player with one entry per window, the counts in {@link Counter} order separated by {@code /}
     */
    public @NotNull String describeWindows() {
        StringBuilder description = new StringBuilder();

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            if (this.validity.getTotal(player, Counter.COMMANDS) == 0) {
                continue;
            }

            description.append(description.isEmpty() ? "" : " ").append('p').append(player).append('=');

            for (int window = 0; window < this.validity.getWindowCount(player); window++) {
                description.append(window == 0 ? "" : "|");

                for (Counter counter : Counter.values()) {
                    description.append(counter.ordinal() == 0 ? "" : "/").append(this.validity.getCount(player, window, counter));
                }
            }
        }

        return description.toString();
    }

    private void findSignals(int player) {
        CommandValidityCheck validity = this.validity;
        int afterDefeat = 0;

        for (int window = 0; window < validity.getWindowCount(player); window++) {
            int handles = 0;
            int badSelections = 0;
            int foreign = 0;
            int orders = 0;
            int ordersWithoutSelection = 0;
            int targets = 0;
            int missingTargets = 0;

            for (int spanWindow = window; spanWindow < window + SPAN_WINDOWS; spanWindow++) {
                handles += validity.getCount(player, spanWindow, Counter.SELECTED_HANDLES);
                badSelections += validity.getCount(player, spanWindow, Counter.SELECTED_DEAD) +
                                 validity.getCount(player, spanWindow, Counter.SELECTED_STALE) +
                                 validity.getCount(player, spanWindow, Counter.SELECTED_FOREIGN);
                foreign += validity.getCount(player, spanWindow, Counter.SELECTED_FOREIGN);
                orders += validity.getCount(player, spanWindow, Counter.ORDERS);
                ordersWithoutSelection += validity.getCount(player, spanWindow, Counter.ORDERS_WITHOUT_SELECTION);
                targets += validity.getCount(player, spanWindow, Counter.TARGETS);
                missingTargets += validity.getCount(player, spanWindow, Counter.TARGETS_MISSING);
            }

            if (badSelections >= MINIMUM_EVENTS && badSelections * PERCENT >= handles * EVENT_PERCENT) {
                this.putSignal(Signal.SELECTION,
                               findSpanFirstFrame(validity, player, window, Counter.SELECTED_DEAD, Counter.SELECTED_STALE, Counter.SELECTED_FOREIGN));
            }

            if (ordersWithoutSelection >= MINIMUM_EVENTS && ordersWithoutSelection * PERCENT >= orders * EVENT_PERCENT) {
                this.putSignal(Signal.ORDER, findSpanFirstFrame(validity, player, window, Counter.ORDERS_WITHOUT_SELECTION));
            }

            if (foreign >= FOREIGN_EVENTS) {
                this.putSignal(Signal.FOREIGN, findSpanFirstFrame(validity, player, window, Counter.SELECTED_FOREIGN));
            }

            if (missingTargets >= MINIMUM_MISSING_TARGETS && missingTargets * PERCENT >= targets * MISSING_TARGET_PERCENT) {
                this.putSignal(Signal.TARGET, findSpanFirstFrame(validity, player, window, Counter.TARGETS_MISSING));
            }

            int defeatedCommands = validity.getCount(player, window, Counter.AFTER_DEFEAT);

            afterDefeat += defeatedCommands;

            if (defeatedCommands > 0 && afterDefeat > AFTER_DEFEAT_EVENTS) {
                this.putSignal(Signal.DEFEAT, validity.getFirstFrame(player, window, Counter.AFTER_DEFEAT));
            }
        }
    }

    private void putSignal(@NotNull Signal signal, int frame) {
        Integer known = this.signals.get(signal);

        if (known == null || frame < known) {
            this.signals.put(signal, frame);
        }
    }
}
