package org.fogofwarlabs.screcorder;

import org.fogofwarlabs.screcorder.capture.CaptureOptions;
import org.fogofwarlabs.screcorder.capture.CaptureSchedule;
import org.fogofwarlabs.screcorder.capture.Recorder;
import org.fogofwarlabs.screcorder.capture.starcraft.StarCraftLayout;
import org.fogofwarlabs.screcorder.capture.windows.WindowsProcess;
import org.fogofwarlabs.screcorder.exception.Exceptions;
import org.fogofwarlabs.screcorder.format.recording.Frame;
import org.fogofwarlabs.screcorder.format.recording.RecordReader;
import org.fogofwarlabs.screcorder.format.recording.metadata.RecordMetadata;
import org.fogofwarlabs.screcorder.format.recording.metadata.SamplingMethod;
import org.fogofwarlabs.screcorder.format.recording.observation.CaptureConsistency;
import org.fogofwarlabs.screcorder.format.recording.observation.StorageMode;
import org.fogofwarlabs.screcorder.format.recording.summary.FrameRange;
import org.fogofwarlabs.screcorder.format.recording.summary.RecordSummary;
import org.fogofwarlabs.screcorder.format.recording.summary.Termination;
import org.fogofwarlabs.screcorder.format.replay.ReplayHeaderUtils;
import org.fogofwarlabs.screcorder.sample.SampleDefinition;
import org.fogofwarlabs.screcorder.sample.SampleDefinitions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Runs the independent recorder and inspects its recording files.
 */
public final class RecorderCli {
    private static final @NotNull String PRODUCER_VERSION = "sc-recorder-java-0.1.0";
    private static final @NotNull String GAME_EXECUTABLE_NAME = "StarCraft.exe";

    private static final @NotNull List<String> OPTIONS = List.of(
            "--replay", "--executable", "--out", "--process-id", "--mode", "--sample", "--storage", "--poll-us",
            "--timeout-seconds", "--mock-base", "--file", "--playback-speed", "--preset", "--coverage", "--dense-frames",
            "--stride", "--frames", "--warm-up", "--sound"
    );
    private static final @NotNull List<String> PLAYBACK_SPEEDS = List.of("X1", "X2", "X4", "X8", "MAX");
    private static final @NotNull List<String> SOUND_SETTINGS = List.of("off", "on");
    private static final @NotNull String FRAME_RANGE_SEPARATOR = "..";
    private static final int HEXADECIMAL_PREFIX_LENGTH = 2;
    private static final int HEXADECIMAL_RADIX = 16;

    private static final long DEFAULT_TIMEOUT_SECONDS = 7200;
    private static final long NANOSECONDS_PER_MICROSECOND = 1_000;
    private static final long MAXIMUM_POLLING_MICROSECONDS = 1_000_000;

    private static final long TEST_DENSE_FRAMES = 2400;
    private static final long TEST_STRIDE = 24;
    private static final @NotNull String TEST_PLAYBACK_SPEED = "X4";

    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_FAILURE = 1;
    private static final int EXIT_ABNORMAL_TERMINATION = 2;
    private static final int EXIT_PARTIAL = 3;

    private static final int SHUTDOWN_WAIT_SECONDS = 15;
    private static final int PROCESS_EXIT_WAIT_SECONDS = 5;
    private static final int PROCESS_EXIT_POLL_MILLISECONDS = 10;

    private static final int HASH_BUFFER_SIZE = 64 * 1024;
    private static final int SESSION_DIGEST_PREFIX_LENGTH = 12;
    private static final int JSON_CONTROL_CHARACTER_LIMIT = 0x20;

    /**
     * Runs a command and exits with a nonzero status on failure.
     *
     * @param arguments the command arguments
     */
    public static void main(@NotNull String @NotNull [] arguments) {
        int status;

        try {
            status = run(arguments);
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            System.err.println(failure.getMessage());
            status = EXIT_FAILURE;
        }

        if (status != EXIT_SUCCESS) {
            System.exit(status);
        }
    }

    /**
     * Runs one command without terminating the calling virtual machine.
     *
     * @param arguments the command arguments
     * @return {@code 0} on successful completion, or a capture quality/termination status
     * @throws IOException              if files, the target or the launcher fail
     * @throws IllegalArgumentException if the command, an option or an option value is unknown or malformed
     */
    public static int run(@NotNull String @NotNull [] arguments) throws IOException {
        if (arguments.length == 0 || arguments[0].equals("--help") || arguments[0].equals("help")) {
            System.out.println("""
                               sc-recorder record --replay FILE.rep --executable StarCraft.exe [--out DIR]
                               sc-recorder record --replay FILE.rep --process-id NUMBER [--out DIR]
                               sc-recorder record [--process-id NUMBER] [--mode replay|game] [--out DIR]
                                  [--preset test] [--sample NAME]... [--storage values|digest]
                                  [--coverage strict|tolerant] [--dense-frames N] [--stride K] [--frames A..B]
                                  [--playback-speed X1|X2|X4|X8|MAX] [--sound off|on] [--poll-us NUMBER] [--warm-up NUMBER]
                                  [--timeout-seconds NUMBER] [--mock-base ADDRESS]
                               sc-recorder validate FILE.screc
                               sc-recorder inspect FILE.screc
                               sc-recorder list
                               sc-recorder show NAME
                               sc-recorder check --file StarCraft.exe
                               
                               --preset test selects the comparison samples, digest storage, tolerant coverage,
                               --dense-frames 2400, --stride 24 and --playback-speed X4; explicit options override it.
                               A launched game runs without sound (--sound off) unless --sound on is given; an attached game keeps its own setting.
                               The default polls the frame counter in a busy loop (--poll-us 0).
                               validate exits 0 for a valid complete file, 3 for a valid partial file, 1 otherwise.
                               """);

            return EXIT_SUCCESS;
        }

        String command = arguments[0];

        if (command.equals("show") && arguments.length == 2) {
            SampleDefinition<?> definition = SampleDefinitions.findByName(arguments[1]);

            if (definition == null) {
                throw Exceptions.newIllegalArgumentException("Unknown sample: {0}", arguments[1]);
            }

            System.out.println(definition.getName() + " (identifier " + definition.getIdentifier() + ")");
            System.out.println("Data: " + definition.getSampleClass().getName());

            return EXIT_SUCCESS;
        }

        if (command.equals("list") && arguments.length == 1) {
            for (SampleDefinition<?> definition : SampleDefinitions.getAll()) {
                System.out.println(definition.getName() + ": " + definition.getSampleClass().getSimpleName());
            }

            return EXIT_SUCCESS;
        }

        if (command.equals("inspect") && arguments.length == 2) {
            inspect(Path.of(arguments[1]));

            return EXIT_SUCCESS;
        }

        if (command.equals("validate") && arguments.length == 2) {
            return validate(Path.of(arguments[1]));
        }

        if (!command.equals("record") && !command.equals("check")) {
            throw Exceptions.newIllegalArgumentException("Unknown or malformed command: {0}", command);
        }

        Map<String, String> options = new LinkedHashMap<>();
        List<SampleDefinition<?>> definitions = new ArrayList<>();

        for (int index = 1; index < arguments.length; index++) {
            String option = arguments[index];

            if (!OPTIONS.contains(option) || ++index == arguments.length) {
                throw Exceptions.newIllegalArgumentException("Unknown option or missing value: {0}", option);
            }

            String value = arguments[index];

            if (option.equals("--sample")) {
                SampleDefinition<?> definition = SampleDefinitions.findByName(value);

                if (definition == null || definitions.contains(definition)) {
                    throw Exceptions.newIllegalArgumentException("Unknown or duplicate sample: {0}", value);
                }

                definitions.add(definition);
            } else if (options.putIfAbsent(option, value) != null) {
                throw Exceptions.newIllegalArgumentException("Duplicate option: {0}", option);
            }
        }

        if (command.equals("check")) {
            String file = options.get("--file");

            if (file == null) {
                throw Exceptions.newIllegalArgumentException("check requires --file");
            }

            WindowsProcess.checkExecutable(Path.of(file));
            System.out.println("Supported: " + file);

            return EXIT_SUCCESS;
        }

        String mode = options.getOrDefault("--mode", "replay");
        String preset = options.get("--preset");

        if (preset != null && !preset.equals("test")) {
            throw Exceptions.newIllegalArgumentException("Unknown preset: {0}", preset);
        }

        boolean testPreset = preset != null;

        if (definitions.isEmpty() && testPreset) {
            definitions.addAll(SampleDefinitions.getComparisonDefinitions());
        }

        if (definitions.isEmpty()) {
            throw Exceptions.newIllegalArgumentException("{0} requires at least one --sample NAME or --preset test", command);
        }

        String storageText = options.getOrDefault("--storage", testPreset ? "digest" : "values");
        StorageMode storageMode = switch (storageText) {
            case "values" -> StorageMode.VALUES;
            case "digest" -> StorageMode.DIGEST;
            default -> throw Exceptions.newIllegalArgumentException("Unknown storage mode: {0}", storageText);
        };

        long pollingMicroseconds = parseNumber(options.getOrDefault("--poll-us", "0"));
        long timeoutSeconds = parseNumber(options.getOrDefault("--timeout-seconds", Long.toString(DEFAULT_TIMEOUT_SECONDS)));
        String coverage = options.getOrDefault("--coverage", testPreset ? "tolerant" : "strict");
        String playbackSpeed = options.getOrDefault("--playback-speed", testPreset ? TEST_PLAYBACK_SPEED : "");
        String sound = options.getOrDefault("--sound", "off");

        if (!mode.equals("replay") && !mode.equals("game") || pollingMicroseconds < 0 || pollingMicroseconds > MAXIMUM_POLLING_MICROSECONDS ||
            timeoutSeconds <= 0 || timeoutSeconds > Recorder.MAXIMUM_TIMEOUT_SECONDS) {
            throw Exceptions.newIllegalArgumentException("Invalid recording mode or timing limits");
        }

        if (!coverage.equals("strict") && !coverage.equals("tolerant")) {
            throw Exceptions.newIllegalArgumentException("Unknown coverage: {0}", coverage);
        }

        if (!playbackSpeed.isEmpty() && !PLAYBACK_SPEEDS.contains(playbackSpeed)) {
            throw Exceptions.newIllegalArgumentException("Unknown playback speed: {0}", playbackSpeed);
        }

        if (!SOUND_SETTINGS.contains(sound)) {
            throw Exceptions.newIllegalArgumentException("Unknown sound setting: {0}", sound);
        }

        CaptureSchedule schedule = parseSchedule(options, testPreset);
        int warmUpPasses = (int) parseNumber(options.getOrDefault("--warm-up", Integer.toString(CaptureOptions.getDefaultWarmUpPasses())));
        CaptureOptions captureOptions = new CaptureOptions(pollingMicroseconds * NANOSECONDS_PER_MICROSECOND,
                                                           schedule,
                                                           coverage.equals("tolerant"),
                                                           warmUpPasses);

        String replayText = options.get("--replay");
        String mockText = options.get("--mock-base");

        if (replayText != null && (!mode.equals("replay") || mockText != null)) {
            throw Exceptions.newIllegalArgumentException("Replay pairing requires replay mode and cannot use a mock process");
        }

        Path executable = options.containsKey("--executable") ? Path.of(options.get("--executable")).toRealPath() : null;
        Process ownedProcess = null;
        ProcessBuilder launcher = null;
        Path pairedReplay = null;
        String replayHash = "";
        byte[] replayHeader = null;

        if (replayText != null) {
            if (executable == null && !options.containsKey("--process-id")) {
                throw Exceptions.newIllegalArgumentException("--replay requires --executable or --process-id");
            }

            if (!options.containsKey("--process-id")) {
                WindowsProcess.checkExecutable(executable);

                try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
                    if (processes.anyMatch(RecorderCli::isGameProcess)) {
                        throw Exceptions.newIOException("A StarCraft process is already running; replay launch requires a new owned process");
                    }
                }
            }

            Path replay = Path.of(replayText).toRealPath();

            replayHeader = ReplayHeaderUtils.readHeader(replay);
            replayHash   = hashFile(replay);

            Path directory = Path.of(options.getOrDefault("--out", "recordings")).toAbsolutePath()
                                 .resolve("session-" + Instant.now().toEpochMilli() + "-" + replayHash.substring(0, SESSION_DIGEST_PREFIX_LENGTH));

            Files.createDirectories(directory);
            pairedReplay = directory.resolve("input.rep");
            Files.copy(replay, pairedReplay);

            if (!replayHash.equals(hashFile(pairedReplay)) || !Arrays.equals(replayHeader, ReplayHeaderUtils.readHeader(pairedReplay))) {
                throw Exceptions.newIOException("Replay changed while its session copy was being created");
            }

            options.put("--out", directory.toString());

            if (!options.containsKey("--process-id")) {
                List<String> gameArguments = new ArrayList<>(List.of(executable.toString(), "--launch"));

                if (sound.equals("off")) {
                    gameArguments.add("--noSound");
                }

                gameArguments.addAll(List.of("-uid", "s1", "-displayMode", "0", "--playReplay=" + pairedReplay.getFileName()));

                if (!playbackSpeed.isEmpty()) {
                    gameArguments.add("--playbackSpeed=" + playbackSpeed);
                }

                launcher = new ProcessBuilder(gameArguments)
                        .directory(directory.toFile()).redirectErrorStream(true)
                        .redirectOutput(directory.resolve("game-launch.log").toFile());
            }
        }

        AtomicBoolean canceled = new AtomicBoolean(false);
        CountDownLatch completed = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> {
            canceled.set(true);

            try {
                completed.await(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
        }, "recorder-shutdown");
        Path directory = Path.of(options.getOrDefault("--out", "recordings")).toAbsolutePath();

        Files.createDirectories(directory);

        String recordingName = "sc-record-" + Instant.now().toEpochMilli();
        Path partialRecording = directory.resolve(recordingName + ".partial");
        Path recording = partialRecording;
        Map<String, String> statistics = Map.of("reason", "error");

        Runtime.getRuntime().addShutdownHook(shutdown);

        try {
            if (launcher != null) {
                ownedProcess = launcher.start();
            }

            long processIdentifier = ownedProcess == null ? findProcess(options.get("--process-id")) : ownedProcess.pid();
            Path image = Path.of(ProcessHandle.of(processIdentifier).flatMap(process -> process.info().command())
                                              .orElseThrow(() -> Exceptions.newIOException("Cannot find target executable path")));

            if (mockText == null) {
                WindowsProcess.checkExecutable(image);
            } else if (!image.getFileName().toString().equalsIgnoreCase("scr-recorder-mock.exe") || !options.containsKey("--process-id")) {
                throw Exceptions.newIllegalArgumentException("--mock-base requires an explicitly selected scr-recorder-mock.exe process");
            }

            try (WindowsProcess target = WindowsProcess.open(processIdentifier)) {
                long moduleBase = mockText == null ? target.readModuleBase() : parseModuleBase(mockText);

                target.checkModule(moduleBase);

                Recorder recorder = new Recorder(target, moduleBase, mode.equals("replay"), definitions, captureOptions, timeoutSeconds, canceled);
                int requirements = RecordMetadata.REQUIRE_AVAILABLE_SAMPLES | RecordMetadata.REQUIRE_MARKER_STABLE;

                if (!captureOptions.isGapTolerant()) {
                    requirements |= RecordMetadata.REQUIRE_NO_GAPS;

                    if (mode.equals("replay")) {
                        requirements |= RecordMetadata.REQUIRE_EXPECTED_BOUNDARIES;
                    }
                }

                RecordMetadata.Builder metadata = new RecordMetadata.Builder()
                        .setProducerVersion(PRODUCER_VERSION)
                        .setExecutableDigest(HexFormat.of().parseHex(hashFile(image)))
                        .setSamplingMethod(SamplingMethod.EXTERNAL_MARKER_POLLING)
                        .setPollingIntervalNanoseconds(captureOptions.getPollingIntervalNanoseconds())
                        .setCoverageRequirements(requirements);

                if (!replayHash.isEmpty()) {
                    metadata.setRequestedReplayDigest(HexFormat.of().parseHex(replayHash));
                }

                System.out.println("Recording process " + processIdentifier + " to " + partialRecording);

                byte[] expectedHeader = replayHeader;
                RecordSummary summary = runOnCaptureThread(() -> recorder.record(partialRecording, metadata, storageMode, expectedHeader));

                statistics = new LinkedHashMap<>();
                statistics.put("termination", summary.getTermination().name());
                statistics.put("process_id", Long.toString(processIdentifier));
                statistics.put("frames", Long.toString(summary.getFrameCount()));
                statistics.put("first", Long.toString(summary.getFirstFrame()));
                statistics.put("last", Long.toString(summary.getLastFrame()));
                statistics.put("missed", Long.toString(summary.getMissingFrameCount()));
                statistics.put("unavailable_samples", Long.toString(summary.getUnavailableSampleCount()));
                statistics.put("torn", Long.toString(summary.getTornObservationCount()));
                statistics.put("lost_while_sampling", Long.toString(recorder.getLostFrameCount()));
                statistics.put("dropped_inconsistent", Long.toString(recorder.getDroppedFrameCount()));
                statistics.put("quality", summary.isRequirementsSatisfied() ? "clean-observed-coverage" : "partial-or-inconsistent");

                if (isComplete(summary)) {
                    Path completeRecording = directory.resolve(recordingName + ".screc");

                    Files.move(partialRecording, completeRecording);
                    recording = completeRecording;
                }

                System.out.println(statistics);

                if (summary.getTermination() != Termination.NORMAL) {
                    return EXIT_ABNORMAL_TERMINATION;
                }

                return summary.isRequirementsSatisfied() ? EXIT_SUCCESS : EXIT_PARTIAL;
            }
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            statistics = new LinkedHashMap<>();
            statistics.put("reason", "error");
            statistics.put("quality", "partial-or-inconsistent");
            statistics.put("error_message", String.valueOf(failure.getMessage()));

            throw failure;
        } finally {
            try {
                if (ownedProcess != null) {
                    closeOwnedGame(ownedProcess, executable);
                }

                if (Files.exists(recording) || pairedReplay != null) {
                    Map<String, String> attributes = new LinkedHashMap<>();

                    attributes.put("recording", Files.exists(recording) ? recording.getFileName().toString() : "");
                    attributes.put("recording_sha256", Files.exists(recording) ? hashFile(recording) : "");
                    attributes.put("replay", pairedReplay == null ? "" : pairedReplay.getFileName().toString());
                    attributes.put("replay_sha256", replayHash);
                    attributes.put("build", StarCraftLayout.BUILD);
                    attributes.put("format", "SCRECBIN 0.1");
                    attributes.put("frame_phase", "observed-counter-with-rng-bracket");
                    attributes.put("playback_speed", playbackSpeed.isEmpty() ? "game-default" : playbackSpeed);
                    attributes.put("sound", launcher == null ? "not-launched" : sound);
                    attributes.put("coverage", coverage);
                    attributes.put("schedule", schedule.toString());
                    attributes.put("polling_us", Long.toString(pollingMicroseconds));
                    attributes.put("recorder", PRODUCER_VERSION);
                    attributes.put("samples", definitions.stream().map(SampleDefinition::getName).toList().toString());
                    attributes.put("storage", storageText);

                    if (ownedProcess != null && !ownedProcess.isAlive()) {
                        attributes.put("process_exit_code", Integer.toString(ownedProcess.exitValue()));
                    }

                    attributes.putAll(statistics);
                    writeMetadata(recording.resolveSibling(recording.getFileName() + ".json"), attributes);
                }
            } finally {
                completed.countDown();

                if (!canceled.get()) {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                }
            }
        }
    }

    /**
     * Validates a recording file completely: header, metadata, every frame chunk (CRC32C, sequence, identifiers and
     * exact payload consumption), the {@code END} summary and physical end of file.
     *
     * @param path the recording file
     * @return {@code 0} if the file is valid and satisfies its coverage requirements, {@code 3} if it is valid but partial, {@code 1} if it is invalid
     */
    public static int validate(@NotNull Path path) {
        try (RecordReader reader = RecordReader.open(path)) {
            while (reader.readNextFrame() != null) {
                // Every frame is decoded and checked.
            }

            RecordSummary summary = reader.getSummary();

            if (summary == null) {
                System.out.println("Invalid: no validated termination summary: " + path);

                return EXIT_FAILURE;
            }

            boolean complete = isComplete(summary);

            System.out.println((complete ? "Valid, complete: " : "Valid, partial: ") + path);

            return complete ? EXIT_SUCCESS : EXIT_PARTIAL;
        } catch (IOException | RuntimeException failure) {
            System.out.println("Invalid: " + failure.getMessage() + ": " + path);

            return EXIT_FAILURE;
        }
    }

    private static void inspect(@NotNull Path path) throws IOException {
        try (RecordReader reader = RecordReader.open(path)) {
            RecordMetadata metadata = reader.getRecord().getMetadata();
            long frameCount = 0;
            long stableCount = 0;
            Frame frame;

            while ((frame = reader.readNextFrame()) != null) {
                frameCount++;

                if (frame.getConsistency() == CaptureConsistency.MARKER_STABLE) {
                    stableCount++;
                }
            }

            RecordSummary summary = reader.getSummary();

            if (summary == null) {
                throw Exceptions.newIOException("Recording has no validated termination summary");
            }

            byte[] replayDigest = metadata.getRequestedReplayDigest();

            System.out.println("Producer: " + metadata.getProducerVersion());
            System.out.println("Requested replay SHA-256: " + (replayDigest == null ? "none" : HexFormat.of().formatHex(replayDigest)));
            System.out.println("Loaded replay evidence: " + metadata.getLoadedReplayEvidence());
            System.out.println("Sampling: " + metadata.getSamplingMethod() + ", polling interval " +
                               metadata.getPollingIntervalNanoseconds() + " ns, coverage requirements " + metadata.getCoverageRequirements());
            System.out.println("Expected frames: " + metadata.getExpectedFirstFrame() + ".." + metadata.getExpectedLastFrame());
            System.out.println("Samples: " + reader.getRecord().getDefinitions().stream().map(SampleDefinition::getName).toList());
            System.out.println("Frames: " + frameCount + " (" + stableCount + " marker-stable), first " + summary.getFirstFrame() +
                               ", last " + summary.getLastFrame());
            System.out.println("Termination: " + summary.getTermination() + ", requirements satisfied: " + summary.isRequirementsSatisfied());
            System.out.println("Missing frames: " + summary.getMissingFrameCount() + " in " + summary.getMissingRanges().size() +
                               " ranges, unavailable samples: " + summary.getUnavailableSampleCount() +
                               ", torn observations: " + summary.getTornObservationCount());

            long longestMissingRange = 0;

            for (FrameRange range : summary.getMissingRanges()) {
                longestMissingRange = Math.max(longestMissingRange, range.getFrameCount());
            }

            System.out.println("Longest missing range: " + longestMissingRange);
        }
    }

    private static boolean isComplete(@NotNull RecordSummary summary) {
        return summary.getTermination() == Termination.NORMAL && summary.isRequirementsSatisfied();
    }

    private static long parseNumber(@NotNull String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException failure) {
            throw Exceptions.newIllegalArgumentException(failure, "Invalid number: {0}", text);
        }
    }

    private static long parseModuleBase(@NotNull String text) {
        return text.startsWith("0x") || text.startsWith("0X") ? Long.parseUnsignedLong(text.substring(HEXADECIMAL_PREFIX_LENGTH), HEXADECIMAL_RADIX)
                                                              : Long.parseLong(text);
    }

    private static @NotNull CaptureSchedule parseSchedule(@NotNull Map<String, String> options, boolean testPreset) {
        long firstFrame = 0;
        long lastFrame = Long.MAX_VALUE;
        String window = options.get("--frames");

        if (window != null) {
            int separator = window.indexOf(FRAME_RANGE_SEPARATOR);

            if (separator <= 0 || separator + FRAME_RANGE_SEPARATOR.length() == window.length()) {
                throw Exceptions.newIllegalArgumentException("Frame window must be A..B: {0}", window);
            }

            firstFrame = parseNumber(window.substring(0, separator));
            lastFrame  = parseNumber(window.substring(separator + FRAME_RANGE_SEPARATOR.length()));
        }

        long densePrefix = parseNumber(options.getOrDefault("--dense-frames", Long.toString(testPreset ? TEST_DENSE_FRAMES : 0)));
        long stride = parseNumber(options.getOrDefault("--stride", Long.toString(testPreset ? TEST_STRIDE : 1)));

        return new CaptureSchedule(firstFrame, lastFrame, densePrefix, stride);
    }

    private static boolean isGameProcess(@NotNull ProcessHandle process) {
        return process.info().command()
                      .map(command -> Path.of(command).getFileName().toString().equalsIgnoreCase(GAME_EXECUTABLE_NAME))
                      .orElse(false);
    }

    private static long findProcess(@Nullable String selected) throws IOException {
        if (selected != null) {
            long identifier = Long.parseLong(selected);

            if (!ProcessHandle.of(identifier).map(ProcessHandle::isAlive).orElse(false)) {
                throw Exceptions.newIOException("Target process is not running: {0}", selected);
            }

            return identifier;
        }

        List<ProcessHandle> candidates;

        try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
            candidates = processes.filter(RecorderCli::isGameProcess).toList();
        }

        if (candidates.size() != 1) {
            throw Exceptions.newIOException("Expected one StarCraft.exe process; found {0}. Select --process-id", candidates.size());
        }

        return candidates.getFirst().pid();
    }

    private static @NotNull String hashFile(@NotNull Path path) throws IOException {
        MessageDigest digest;

        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw Exceptions.newIllegalStateException(failure, "SHA-256 is unavailable");
        }

        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[HASH_BUFFER_SIZE];
            int count;

            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static @NotNull RecordSummary runOnCaptureThread(@NotNull Callable<RecordSummary> capture) throws IOException {
        FutureTask<RecordSummary> task = new FutureTask<>(capture);
        Thread thread = new Thread(task, "recorder-capture");

        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();

        try {
            return task.get();
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();

            throw Exceptions.newIOException(interruption, "Interrupted while waiting for the capture thread");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();

            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }

            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }

            throw Exceptions.newIOException(cause, "Capture thread failed");
        }
    }

    private static void closeOwnedGame(@NotNull Process game, @NotNull Path executable) throws IOException {
        String browserPath = executable.getParent().resolve("cef").resolve("SceneCefBrowser.exe").toString();
        List<ProcessHandle> browsers;

        try (Stream<ProcessHandle> descendants = game.descendants()) {
            browsers = descendants.filter(process -> process.info().command().map(command -> command.equalsIgnoreCase(browserPath)).orElse(false))
                                  .toList();
        }

        try {
            if (game.isAlive()) {
                game.destroy();

                if (!game.waitFor(PROCESS_EXIT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    game.destroyForcibly();

                    if (!game.waitFor(PROCESS_EXIT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw Exceptions.newIOException("Owned StarCraft process did not terminate");
                    }
                }
            }

            for (ProcessHandle browser : browsers) {
                if (browser.isAlive()) {
                    browser.destroyForcibly();
                }
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_EXIT_WAIT_SECONDS);

            while (browsers.stream().anyMatch(ProcessHandle::isAlive)) {
                if (System.nanoTime() >= deadline) {
                    throw Exceptions.newIOException("Owned StarCraft browser process did not terminate");
                }

                TimeUnit.MILLISECONDS.sleep(PROCESS_EXIT_POLL_MILLISECONDS);
            }
        } catch (InterruptedException interruption) {
            game.destroyForcibly();
            browsers.forEach(ProcessHandle::destroyForcibly);
            Thread.currentThread().interrupt();

            throw Exceptions.newIOException(interruption, "Interrupted while closing the owned game");
        }
    }

    private static void writeMetadata(@NotNull Path path, @NotNull Map<String, String> attributes) throws IOException {
        StringBuilder text = new StringBuilder("{\n");
        int index = 0;

        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            if (index++ != 0) {
                text.append(",\n");
            }

            text.append("  ").append(quoteJson(attribute.getKey())).append(": ").append(quoteJson(attribute.getValue()));
        }

        Files.writeString(path, text.append("\n}\n"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static @NotNull String quoteJson(@NotNull String value) {
        StringBuilder result = new StringBuilder("\"");

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);

            if (character == '"' || character == '\\') {
                result.append('\\').append(character);
            } else if (character < JSON_CONTROL_CHARACTER_LIMIT) {
                result.append("\\u").append(HexFormat.of().toHexDigits(character));
            } else {
                result.append(character);
            }
        }

        return result.append('"').toString();
    }


    private RecorderCli() {
    }
}
