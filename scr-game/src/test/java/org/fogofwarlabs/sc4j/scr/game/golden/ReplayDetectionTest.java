package org.fogofwarlabs.sc4j.scr.game.golden;

import org.fogofwarlabs.sc4j.scr.core.test.CorpusTest;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.TestResourceUtils;
import org.fogofwarlabs.sc4j.scr.core.test.TestResultUtils;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.ReplaySurveyCheck;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Plays every replay of the detection folder {@code replays/detection} to its end and checks that it shows no unimplemented operation and no
 * impossible situation ({@link ReplaySurveyCheck}): a replay that is not on the known problem list {@value #KNOWN_PROBLEMS} must be {@code ok}, a
 * listed replay must keep its class.
 */
@CorpusTest(ExternalInput.INSTALLATION)
@ExtendWith(GameResourcesExtension.class)
final class ReplayDetectionTest {
    private static final @NotNull String FOLDER = "replays/detection";
    private static final @NotNull String KIND = "detection";
    private static final @NotNull String NAME_SEPARATOR = "/";
    private static final @NotNull String KNOWN_PROBLEMS = FOLDER + "/known-problems.tsv";
    private static final @NotNull String REPLAY_SUFFIX = ".rep";
    private static final @NotNull String RESULTS_FILE = "replay-detection.tsv";
    private static final @NotNull String KEY_COLUMN = "id";
    private static final @NotNull Duration CASE_TIMEOUT = Duration.ofMinutes(10);

    private static void detect(@NotNull InstallationTestResources resources,
                               @NotNull String id,
                               @NotNull Path replay,
                               @NotNull KnownProblemsAssert known,
                               @NotNull Path results) throws Exception {
        String name = KIND + NAME_SEPARATOR + id;
        ReplaySurveyCheck survey = Assertions.assertTimeoutPreemptively(CASE_TIMEOUT, () -> ReplaySurveyCheck.run(resources.getGameData(), replay),
                                                                        name + " did not finish within " + CASE_TIMEOUT.toSeconds() + " seconds.");

        TestResultUtils.appendResultLine(results, id, survey.describeReport());
        known.assertClass(id, name, survey.getClassification().getName(), survey.describe());
    }


    /**
     * Creates one test per detection replay.
     * <p>
     * The replays are {@code <source>/<id>.rep}; the id is the key of the list and the test of a replay is named {@code detection/<id>}. Every replay
     * appends its survey columns and end values to {@code <results>/replay-detection.tsv}.
     *
     * @param resources the installed game resources
     * @return one test per detection replay
     * @throws IOException if the known problem list or the detection folder cannot be read, the list names a key without a replay, or the result table cannot be written
     */
    @TestFactory
    @NotNull Stream<DynamicTest> replay_detection(@NotNull InstallationTestResources resources) throws IOException {
        KnownProblemsAssert known = KnownProblemsAssert.read(KNOWN_PROBLEMS);
        Map<String, Path> replays = TestResourceUtils.listFilesByName(TestResourceUtils.getPath(FOLDER), REPLAY_SUFFIX);
        Path results = TestResultUtils.createResultTable(RESULTS_FILE, KEY_COLUMN, ReplaySurveyCheck.REPORT_COLUMNS);

        known.assertListedKeys(replays.keySet());

        return replays.entrySet()
                      .stream()
                      .map(entry -> DynamicTest.dynamicTest(KIND + NAME_SEPARATOR + entry.getKey(), entry.getValue().toUri(),
                                                            () -> detect(resources, entry.getKey(), entry.getValue(), known, results)));
    }
}
