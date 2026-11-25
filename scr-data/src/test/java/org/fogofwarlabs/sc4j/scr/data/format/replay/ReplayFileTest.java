package org.fogofwarlabs.sc4j.scr.data.format.replay;

import org.fogofwarlabs.sc4j.scr.core.test.CorpusTest;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/**
 * Tests replay decoding with synthetic containers and an optionally configured replay corpus.
 */
final class ReplayFileTest {
    private static final @NotNull String EXTENDED_REPLAY_IDENTIFIER = "seRS";
    private static final int HEADER_SIZE = 0x279;
    private static final int ENGINE_OFFSET = 0;
    private static final int PLAYER_RECORD_COUNT = 12;
    private static final int PLAYERS_OFFSET = 0xA1;
    private static final int PLAYER_RECORD_SIZE = 36;
    private static final int PLAYER_ID_OFFSET = 4;
    private static final int PLAYER_TYPE_OFFSET = 8;
    private static final int PLAYER_RACE_OFFSET = 9;
    private static final int PLAYER_TEAM_OFFSET = 10;
    private static final int PLAYER_NAME_OFFSET = 11;

    private static final @NotNull String REPLAYS_LIMIT_PROPERTY = "sc4j.replays.limit";

    private static final int DEFAULT_CORPUS_LIMIT = 5000;

    /**
     * A command type without a dispatcher case that the replay loader still splits by its one-byte length.
     */
    private static final int UNASSIGNED_COMMAND_TYPE = 0x24;

    /**
     * The name records of the player name section.
     */
    private static final int NAME_COUNT = 12;
    /**
     * A player name of two Hangul characters, which the header record's single-byte conversion writes as {@code ??}.
     */
    private static final @NotNull String HANGUL_NAME = "\uac00\ub098";
    /**
     * The header record's single-byte conversion of {@link #HANGUL_NAME}.
     */
    private static final @NotNull String CONVERTED_HANGUL_NAME = "??";
    /**
     * A name holding a color code and the first two UTF-8 bytes of a Hangul character, as the name section keeps them.
     */
    private static final byte @NotNull [] CONTROL_NAME = {'a', 0x03, 'b', (byte) 0xED, (byte) 0x95};
    private static final int WRITTEN_MAP_SIZE = 64;
    private static final int WRITTEN_PLAYER_TYPE = 2;
    private static final int WRITTEN_PLAYER_RACE = 1;
    private static final int WRITTEN_PLAYER_FORCE = 1;
    private static final int WRITTEN_BUILD = 13515;
    /**
     * The offset of the extension offset DWORD of a written replay, which follows the replay ID section.
     */
    private static final int EXTENSION_OFFSET_FIELD = 16;

    /**
     * The map width and height of the synthetic replay, in tiles.
     */
    private static final int MAP_SIZE = 64;
    private static final int MAP_WIDTH_OFFSET = 0x34;
    private static final int MAP_HEIGHT_OFFSET = 0x36;
    private static final int FIRST_SLOT = 0xFEDC1234;
    private static final int FIRST_IDENTIFIER = 0x00000102;
    private static final int SECOND_SLOT = 0x80000000;
    private static final int SECOND_IDENTIFIER = 0xFFFFFFFF;
    private static final int THIRD_SLOT = 7;
    private static final int THIRD_IDENTIFIER = 2;
    private static final byte FIRST_TYPE = 2;
    private static final byte FIRST_RACE = 1;
    private static final byte FIRST_TEAM = 3;
    private static final @NotNull String FIRST_NAME = "Player";
    private static final @NotNull String REPLAY_IDENTIFIER = "reRS";
    private static final long CHECKSUM_COMPLEMENT = 0xFFFFFFFFL;
    /**
     * The prefix of a one-chunk section: the checksum, the chunk count and the chunk length.
     */
    private static final int SECTION_PREFIX_SIZE = 3 * Integer.BYTES;
    private static final int SECTION_CHUNK_COUNT = 1;
    private static final int IDENTIFIER_SECTION_INDEX = 0;
    private static final int HEADER_SECTION_INDEX = 1;
    /**
     * The stored checksum of the {@code seRS} identifier section.
     */
    private static final int EXTENDED_IDENTIFIER_CHECKSUM = 0x93C219C2;
    /**
     * The stored checksum of the {@code reRS} identifier section.
     */
    private static final int IDENTIFIER_CHECKSUM = 0x2B7E7EA7;
    /**
     * The offset of the header section's checksum: after the one-chunk identifier section.
     */
    private static final int HEADER_CHECKSUM_OFFSET = SECTION_PREFIX_SIZE + Integer.BYTES;
    /**
     * The offset of the header section's checksum in a {@code 1.21} file, after the extension offset DWORD as well.
     */
    private static final int EXTENDED_HEADER_CHECKSUM_OFFSET = HEADER_CHECKSUM_OFFSET + Integer.BYTES;
    /**
     * The offset of the first byte of a raw legacy header section.
     */
    private static final int LEGACY_HEADER_DATA_OFFSET = HEADER_CHECKSUM_OFFSET + SECTION_PREFIX_SIZE;
    /**
     * The header byte the payload test changes.
     */
    private static final int CHANGED_HEADER_BYTE = 0x98;
    /**
     * A header section one byte short of {@link #HEADER_SIZE} and the message that reports it.
     */
    private static final int SHORT_HEADER_SIZE = 632;
    private static final @NotNull String SHORT_HEADER_MESSAGE = "The replay header section has 632 bytes, not 633.";

    private static void assertReplayInvariants(@NotNull ReplayFile replay, @NotNull Path replayFile) {
        ReplayHeader header = replay.getHeader();

        Assertions.assertTrue(header.getFrames() > 0, "Zero frames: " + replayFile);
        Assertions.assertTrue(header.getMapWidth() > 0 && header.getMapHeight() > 0, "Invalid map size: " + replayFile);
        Assertions.assertEquals(PLAYER_RECORD_COUNT, header.getPlayers().size(), "Invalid player count: " + replayFile);
        Assertions.assertFalse(replay.getCommands().isEmpty(), "No commands: " + replayFile);
    }

    private static byte @NotNull [] buildReplayData(@NotNull ReplayContainerFormat format) throws IOException {
        return buildReplayData(format, new byte[]{1, 0, 0, 0, 0});
    }

    private static byte @NotNull [] buildReplayData(@NotNull ReplayContainerFormat format, byte @NotNull [] commands) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        int firstRecord = PLAYERS_OFFSET;
        int secondRecord = firstRecord + PLAYER_RECORD_SIZE;
        int thirdRecord = secondRecord + PLAYER_RECORD_SIZE;

        header.put(ENGINE_OFFSET, (byte) 1);
        header.putShort(MAP_WIDTH_OFFSET, (short) MAP_SIZE);
        header.putShort(MAP_HEIGHT_OFFSET, (short) MAP_SIZE);
        header.putInt(firstRecord, FIRST_SLOT).putInt(firstRecord + PLAYER_ID_OFFSET, FIRST_IDENTIFIER);
        header.put(firstRecord + PLAYER_TYPE_OFFSET, FIRST_TYPE)
              .put(firstRecord + PLAYER_RACE_OFFSET, FIRST_RACE)
              .put(firstRecord + PLAYER_TEAM_OFFSET, FIRST_TEAM);
        header.position(firstRecord + PLAYER_NAME_OFFSET).put(FIRST_NAME.getBytes(StandardCharsets.US_ASCII));
        header.putInt(secondRecord, SECOND_SLOT).putInt(secondRecord + PLAYER_ID_OFFSET, SECOND_IDENTIFIER);
        header.putInt(thirdRecord, THIRD_SLOT).putInt(thirdRecord + PLAYER_ID_OFFSET, THIRD_IDENTIFIER);

        String replayIdentifierText = format == ReplayContainerFormat.MODERN_121 ? EXTENDED_REPLAY_IDENTIFIER : REPLAY_IDENTIFIER;
        byte[] replayIdentifier = replayIdentifierText.getBytes(StandardCharsets.US_ASCII);
        byte[] commandSize = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(commands.length).array();
        byte[][] sections = {replayIdentifier, header.array(), commandSize, commands};
        ByteArrayOutputStream replayData = new ByteArrayOutputStream();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            byte[] section = sections[sectionIndex];
            byte[] encodedSection = section;

            if (sectionIndex == HEADER_SECTION_INDEX && format != ReplayContainerFormat.LEGACY) {
                ByteArrayOutputStream compressedData = new ByteArrayOutputStream();

                try (DeflaterOutputStream compressor = new DeflaterOutputStream(compressedData)) {
                    compressor.write(section);
                }

                encodedSection = compressedData.toByteArray();
            }

            CRC32 checksum = new CRC32();

            checksum.update(section);

            ByteBuffer prefix = ByteBuffer.allocate(SECTION_PREFIX_SIZE).order(ByteOrder.LITTLE_ENDIAN);

            prefix.putInt((int) (checksum.getValue() ^ CHECKSUM_COMPLEMENT)).putInt(SECTION_CHUNK_COUNT).putInt(encodedSection.length);
            replayData.write(prefix.array());
            replayData.write(encodedSection);

            if (sectionIndex == IDENTIFIER_SECTION_INDEX && format == ReplayContainerFormat.MODERN_121) {
                replayData.write(new byte[Integer.BYTES]);
            }
        }

        return replayData.toByteArray();
    }

    /**
     * Writes a replay without commands whose first header record names its player {@link #HANGUL_NAME} and whose name
     * section holds the names.
     *
     * @param names the twelve names of the name section
     * @return the replay file bytes
     */
    private static byte @NotNull [] writeReplay(byte @NotNull [] @NotNull [] names) {
        byte[] header = new ReplayHeaderBuilder().setEngine(1)
                                                 .setMapSize(WRITTEN_MAP_SIZE, WRITTEN_MAP_SIZE)
                                                 .setPlayer(0, 0, 0, WRITTEN_PLAYER_TYPE, WRITTEN_PLAYER_RACE, WRITTEN_PLAYER_FORCE, HANGUL_NAME)
                                                 .build();

        return new ReplayWriter(header,
                                new byte[0],
                                new byte[]{1},
                                ReplayWriter.encodeNames(names),
                                new byte[ReplayWriter.SKIN_SIZE],
                                ReplayWriter.CURRENT_LIMITS,
                                ReplayWriter.CURRENT_FIX_FLAGS,
                                new byte[ReplayWriter.CUSTOM_COLORS_SIZE],
                                ReplayWriter.encodeVersionBlock(WRITTEN_BUILD, 0)).write();
    }

    private static @NotNull List<Path> findReplayFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(ExternalInput.REPLAYS.getPath())) {
            return walk.filter(Files::isRegularFile)
                       .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".rep"))
                       .limit(getCorpusLimit())
                       .collect(Collectors.toList());
        }
    }

    private static int getCorpusLimit() {
        String limitProperty = System.getProperty(REPLAYS_LIMIT_PROPERTY, "");

        if (limitProperty.isEmpty()) {
            return DEFAULT_CORPUS_LIMIT;
        }

        return Integer.parseInt(limitProperty);
    }


    @Test
    void parse_should_validate_checksums_of_raw_and_compressed_sections() throws IOException {
        for (ReplayContainerFormat format : ReplayContainerFormat.values()) {
            byte[] data = buildReplayData(format);
            int expectedIdentifierChecksum = format == ReplayContainerFormat.MODERN_121 ? EXTENDED_IDENTIFIER_CHECKSUM : IDENTIFIER_CHECKSUM;

            Assertions.assertEquals(expectedIdentifierChecksum, ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt());

            ReplayFile replay = ReplayFile.parse(data);

            Assertions.assertEquals(format, replay.getFormat());
            Assertions.assertEquals(MAP_SIZE, replay.getHeader().getMapWidth());
            Assertions.assertEquals(MAP_SIZE, replay.getHeader().getMapHeight());
            Assertions.assertEquals(1700, replay.getLimits().getUnitCapacity());
            Assertions.assertFalse(replay.getLimits().hasExplicitSection());
            Assertions.assertFalse(replay.getLimits().isFallback());
        }
    }

    @Test
    void parse_should_keep_full_player_identifiers_without_low_byte_aliases() throws IOException {
        for (ReplayContainerFormat format : ReplayContainerFormat.values()) {
            ReplayHeader header = ReplayFile.parse(buildReplayData(format)).getHeader();

            Assertions.assertEquals(FIRST_SLOT, header.getPlayers().get(0).getSlotId());
            Assertions.assertEquals(FIRST_IDENTIFIER, header.getPlayers().get(0).getId());
            Assertions.assertEquals(SECOND_SLOT, header.getPlayers().get(1).getSlotId());
            Assertions.assertEquals(SECOND_IDENTIFIER, header.getPlayers().get(1).getId());
            Assertions.assertEquals(THIRD_SLOT, header.getPlayers().get(2).getSlotId());
            Assertions.assertEquals(THIRD_IDENTIFIER, header.getPlayers().get(2).getId());
            Assertions.assertSame(header.getPlayers().get(2), header.findPlayer(THIRD_IDENTIFIER));
            Assertions.assertEquals(FIRST_TYPE, header.getPlayers().get(0).getType());
            Assertions.assertEquals(FIRST_RACE, header.getPlayers().get(0).getRace());
            Assertions.assertEquals(FIRST_TEAM, header.getPlayers().get(0).getTeam());
            Assertions.assertEquals(FIRST_NAME, header.getPlayers().get(0).getName());
        }
    }

    @Test
    void parse_should_reject_changed_section_checksum() throws IOException {
        for (ReplayContainerFormat format : ReplayContainerFormat.values()) {
            byte[] data = buildReplayData(format);
            int headerOffset = format == ReplayContainerFormat.MODERN_121 ? EXTENDED_HEADER_CHECKSUM_OFFSET : HEADER_CHECKSUM_OFFSET;

            data[headerOffset] ^= 1;

            IOException exception = Assertions.assertThrows(IOException.class, () -> ReplayFile.parse(data));

            Assertions.assertEquals("The replay section checksum does not match.", exception.getMessage());
        }
    }

    @Test
    void parse_should_reject_changed_section_payload() throws IOException {
        byte[] data = buildReplayData(ReplayContainerFormat.LEGACY);

        data[LEGACY_HEADER_DATA_OFFSET + CHANGED_HEADER_BYTE] ^= 1;

        IOException exception = Assertions.assertThrows(IOException.class, () -> ReplayFile.parse(data));

        Assertions.assertEquals("The replay section checksum does not match.", exception.getMessage());
    }

    @Test
    void parse_should_keep_the_command_blocks_as_the_loader_splits_them() throws IOException {
        byte[] commands = {
                5, 0, 0, 0, 9, 0, ReplayCommandIds.STOP, 0, 1, ReplayCommandIds.ALLIANCE, 1, 2, 3, 4,
                5, 0, 0, 0, 2, 2, ReplayCommandIds.KEEP_ALIVE,
                7, 0, 0, 0, 4, 1, UNASSIGNED_COMMAND_TYPE, 1, ReplayCommandIds.CANCEL_BUILD
        };
        ReplayFile replay = ReplayFile.parse(buildReplayData(ReplayContainerFormat.MODERN, commands));
        List<ReplayCommandBlock> blocks = replay.getCommandBlocks();

        Assertions.assertEquals(0, replay.getUnknownTypeCount());
        Assertions.assertEquals(5, replay.getCommands().size());
        Assertions.assertEquals(3, blocks.size());
        Assertions.assertEquals(List.of(5L, 5L, 7L), blocks.stream().map(ReplayCommandBlock::getFrame).toList());
        Assertions.assertEquals(List.of(9, 2, 4), blocks.stream().map(ReplayCommandBlock::getSize).toList());
        Assertions.assertEquals(List.of(0, 2, 3), blocks.stream().map(ReplayCommandBlock::getFirstCommandIndex).toList());
        Assertions.assertEquals(List.of(2, 1, 2), blocks.stream().map(ReplayCommandBlock::getCommandCount).toList());
        Assertions.assertEquals(UNASSIGNED_COMMAND_TYPE, replay.getCommands().get(3).getTypeId());
        Assertions.assertArrayEquals(new byte[]{1, 2, 3, 4}, replay.getCommands().get(1).getPayload());
    }

    @Test
    void parse_should_read_the_player_names_from_the_name_section() throws IOException {
        byte[][] names = new byte[NAME_COUNT][0];

        names[0] = HANGUL_NAME.getBytes(StandardCharsets.UTF_8);
        names[1] = CONTROL_NAME;

        ReplayFile replay = ReplayFile.parse(writeReplay(names));

        Assertions.assertEquals(CONVERTED_HANGUL_NAME, replay.getHeader().getPlayers().getFirst().getName());
        Assertions.assertEquals(HANGUL_NAME, replay.getPlayerNames().getFirst());
        Assertions.assertArrayEquals(CONTROL_NAME, replay.getPlayerNameBytes()[1]);
        Assertions.assertArrayEquals(new byte[0], replay.getPlayerNameBytes()[2]);
    }

    @Test
    void parse_should_name_the_players_from_the_header_records_when_the_name_section_does_not_read() throws IOException {
        byte[][] names = new byte[NAME_COUNT][0];

        names[0] = HANGUL_NAME.getBytes(StandardCharsets.UTF_8);

        byte[] file = writeReplay(names);
        // The last byte before the extensions is the name section's.
        int extensions = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).getInt(EXTENSION_OFFSET_FIELD);

        file[extensions - 1] ^= 1;

        ReplayFile replay = ReplayFile.parse(file);

        Assertions.assertEquals(CONVERTED_HANGUL_NAME, replay.getPlayerNames().getFirst());
        Assertions.assertArrayEquals(CONVERTED_HANGUL_NAME.getBytes(StandardCharsets.US_ASCII), replay.getPlayerNameBytes()[0]);
    }

    @Test
    void create_should_report_the_received_and_required_sizes_of_a_short_header_section() {
        byte[] header = new byte[SHORT_HEADER_SIZE];

        IOException exception = Assertions.assertThrows(IOException.class,
                                                        () -> ReplayFile.create(header, List.of(), new byte[0], new int[0], new byte[0], 0));

        Assertions.assertEquals(SHORT_HEADER_MESSAGE, exception.getMessage());
    }

    @CorpusTest(ExternalInput.REPLAYS)
    @Test
    void parse_should_read_every_corpus_replay() throws IOException {
        List<Path> replayFiles = findReplayFiles();

        Assertions.assertFalse(replayFiles.isEmpty(), "No replay file found.");

        int successCount = 0;
        int unknownTypeTotal = 0;
        Map<String, Integer> formatCounts = new TreeMap<>();
        Map<String, Integer> failureReasons = new TreeMap<>();

        for (Path replayFile : replayFiles) {
            try {
                ReplayFile replay = ReplayFile.parse(Files.readAllBytes(replayFile));

                assertReplayInvariants(replay, replayFile);

                formatCounts.merge(replay.getFormat().name(), 1, Integer::sum);
                unknownTypeTotal += replay.getUnknownTypeCount();
                successCount++;
            } catch (IOException | RuntimeException exception) {
                String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();

                failureReasons.merge(reason, 1, Integer::sum);
            }
        }

        System.out.println("Replays: " + replayFiles.size() + ", parsed: " + successCount + ", failed: " + (replayFiles.size() - successCount));
        System.out.println("Formats: " + formatCounts);
        System.out.println("Unknown command types skipped: " + unknownTypeTotal);

        for (Map.Entry<String, Integer> failureReason : failureReasons.entrySet()) {
            System.out.println("  " + failureReason.getValue() + "x " + failureReason.getKey());
        }

        Assertions.assertEquals(replayFiles.size(), successCount, "Some replays failed to parse.");
    }
}
