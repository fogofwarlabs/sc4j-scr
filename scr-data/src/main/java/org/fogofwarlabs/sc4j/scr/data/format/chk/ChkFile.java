package org.fogofwarlabs.sc4j.scr.data.format.chk;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.base.text.TerminatedStringUtils;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.format.text.LegacyTextUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Represents a parsed CHK ({@code scenario.chk}) file.
 * <p>
 * The CHK file uses {@code name[4] + raw length DWORD LE + data}; its signed length DWORD controls section traversal. This
 * class keeps every encountered header and provides typed accessors for the sections the game reads.
 */
public final class ChkFile {
    /**
     * The {@code VER } value of original maps.
     */
    public static final int VERSION_ORIGINAL = 59;
    /**
     * The {@code VER } value of hybrid maps.
     */
    public static final int VERSION_HYBRID = 63;
    /**
     * The {@code VER } value of remastered original maps.
     */
    public static final int VERSION_REMASTERED_ORIGINAL = 64;
    /**
     * The {@code VER } value of Brood War maps.
     */
    public static final int VERSION_BROOD_WAR = 205;
    /**
     * The {@code VER } value of remastered Brood War maps.
     */
    public static final int VERSION_REMASTERED_BROOD_WAR = 206;
    /**
     * The archive entry of the CHK file in a map archive: {@code staredit\scenario.chk}.
     */
    public static final @NotNull String ARCHIVE_ENTRY = "staredit\\scenario.chk";
    /**
     * The {@code VER } section tag: the map version.
     */
    public static final @NotNull String VERSION_SECTION = "VER ";
    /**
     * The {@code DIM } section tag: the map width and height.
     */
    public static final @NotNull String DIMENSIONS_SECTION = "DIM ";
    /**
     * The {@code ERA } section tag: the tileset.
     */
    public static final @NotNull String TILESET_SECTION = "ERA ";
    /**
     * The {@code OWNR} section tag: the player slot owners.
     */
    public static final @NotNull String OWNER_SECTION = "OWNR";
    /**
     * The {@code SIDE} section tag: the player slot races.
     */
    public static final @NotNull String RACE_SECTION = "SIDE";
    /**
     * The {@code SPRP} section tag: the scenario name and description strings.
     */
    public static final @NotNull String SCENARIO_PROPERTIES_SECTION = "SPRP";
    /**
     * The {@code FORC} section tag: the forces.
     */
    public static final @NotNull String FORCE_SECTION = "FORC";
    /**
     * The {@code VCOD} section tag: the verification code.
     */
    public static final @NotNull String VERIFICATION_CODE_SECTION = "VCOD";
    /**
     * The {@code TRIG} section tag: the triggers.
     */
    public static final @NotNull String TRIGGER_SECTION = "TRIG";
    /**
     * The {@code MBRF} section tag: the mission briefing triggers.
     */
    public static final @NotNull String BRIEFING_SECTION = "MBRF";
    /**
     * The {@code CRGB} section tag: the remastered player colors.
     */
    public static final @NotNull String PLAYER_COLORS_SECTION = "CRGB";
    /**
     * The {@code COLR} section tag: the player colors of expansion maps.
     */
    public static final @NotNull String COLORS_SECTION = "COLR";
    /**
     * The {@code MTXM} section tag: the terrain tile WORDs.
     */
    public static final @NotNull String TERRAIN_SECTION = "MTXM";
    /**
     * The {@code UNIT} section tag: the preplaced units.
     */
    public static final @NotNull String UNIT_SECTION = "UNIT";
    /**
     * The {@code THG2} section tag: the preplaced sprites and doodads.
     */
    public static final @NotNull String THINGY_SECTION = "THG2";
    /**
     * The {@code MASK} section tag: the fog-of-war mask.
     */
    public static final @NotNull String MASK_SECTION = "MASK";
    /**
     * The {@code UNIS} section tag: the unit settings.
     */
    public static final @NotNull String UNIT_SETTINGS_SECTION = "UNIS";
    /**
     * The {@code UPGS} section tag: the upgrade settings.
     */
    public static final @NotNull String UPGRADE_SETTINGS_SECTION = "UPGS";
    /**
     * The {@code TECS} section tag: the technology settings.
     */
    public static final @NotNull String TECHNOLOGY_SETTINGS_SECTION = "TECS";
    /**
     * The {@code PUNI} section tag: the unit availability per player.
     */
    public static final @NotNull String UNIT_AVAILABILITY_SECTION = "PUNI";
    /**
     * The {@code UPGR} section tag: the upgrade levels per player.
     */
    public static final @NotNull String UPGRADE_LEVELS_SECTION = "UPGR";
    /**
     * The {@code PTEC} section tag: the technology availability per player.
     */
    public static final @NotNull String TECHNOLOGY_AVAILABILITY_SECTION = "PTEC";
    /**
     * The {@code UNIx} section tag: the expansion unit settings.
     */
    public static final @NotNull String EXPANSION_UNIT_SETTINGS_SECTION = "UNIx";
    /**
     * The {@code UPGx} section tag: the expansion upgrade settings.
     */
    public static final @NotNull String EXPANSION_UPGRADE_SETTINGS_SECTION = "UPGx";
    /**
     * The {@code TECx} section tag: the expansion technology settings.
     */
    public static final @NotNull String EXPANSION_TECHNOLOGY_SETTINGS_SECTION = "TECx";
    /**
     * The {@code PUPx} section tag: the expansion upgrade levels per player.
     */
    public static final @NotNull String EXPANSION_UPGRADE_LEVELS_SECTION = "PUPx";
    /**
     * The {@code PTEx} section tag: the expansion technology availability per player.
     */
    public static final @NotNull String EXPANSION_TECHNOLOGY_AVAILABILITY_SECTION = "PTEx";
    /**
     * The {@code UPRP} section tag: the unit properties of trigger-created units.
     */
    public static final @NotNull String UNIT_PROPERTIES_SECTION = "UPRP";
    /**
     * The {@code MRGN} section tag: the locations.
     */
    public static final @NotNull String LOCATION_SECTION = "MRGN";
    /**
     * The {@code STR } section tag.
     */
    public static final @NotNull String STRING_SECTION = "STR ";
    /**
     * The {@code STRx} section tag.
     */
    public static final @NotNull String EXTENDED_STRING_SECTION = "STRx";
    /**
     * The size of a section header: the four tag bytes and the signed length DWORD.
     */
    static final int SECTION_HEADER_SIZE = 8;
    /**
     * The size of the {@code VER } and {@code ERA } payloads.
     */
    static final int WORD_SECTION_SIZE = 2;
    /**
     * The size of the {@code DIM } payload: width and height WORDs.
     */
    static final int DIMENSIONS_SIZE = 4;
    /**
     * The offset of the height WORD inside the {@code DIM } payload.
     */
    static final int DIMENSION_HEIGHT_OFFSET = 2;
    /**
     * The largest map width or height in tiles.
     */
    static final int MAXIMUM_DIMENSION = 256;
    /**
     * The mask of the tileset bits of the {@code ERA } WORD.
     */
    static final int TILESET_MASK = 0x7;
    /**
     * The size of the original terrain buffer, the largest accepted {@code MTXM} payload.
     */
    static final int MAXIMUM_TERRAIN_SIZE = 0x20000;
    /**
     * The size of the string count and of one offset entry of {@code STR }.
     */
    static final int STRING_ENTRY_SIZE = 2;
    /**
     * The size of the string count and of one offset entry of {@code STRx}.
     */
    static final int EXTENDED_STRING_ENTRY_SIZE = 4;

    private static final int BYTE_MASK = 0xFF;
    private static final @NotNull Logger LOGGER = Logger.of(ChkFile.class);

    private static final int SECTION_NAME_SIZE = 4;
    private static final int FIRST_PRINTABLE_NAME_CHARACTER = 0x20;
    private static final int LAST_PRINTABLE_NAME_CHARACTER = 0x7E;
    private static final int TILE_WORD_SIZE = 2;
    /**
     * The CV5 group that replaces an invalid group; the installed table must hold it.
     */
    private static final int FALLBACK_TILE_GROUP = 2;
    /**
     * The low byte of the tile word that replaces a tile of an invalid group.
     */
    private static final byte INVALID_GROUP_TILE = 0x20;

    private static final int UNIT_X_OFFSET = 4;
    private static final int UNIT_Y_OFFSET = 6;
    private static final int UNIT_TYPE_OFFSET = 8;
    private static final int UNIT_RELATION_TYPE_OFFSET = 10;
    private static final int UNIT_VALID_PROPERTIES_OFFSET = 12;
    private static final int UNIT_VALID_ELEMENTS_OFFSET = 14;
    private static final int UNIT_OWNER_OFFSET = 16;
    private static final int UNIT_HIT_POINTS_OFFSET = 17;
    private static final int UNIT_SHIELD_POINTS_OFFSET = 18;
    private static final int UNIT_ENERGY_OFFSET = 19;
    private static final int UNIT_RESOURCE_AMOUNT_OFFSET = 20;
    private static final int UNIT_HANGAR_COUNT_OFFSET = 24;
    private static final int UNIT_STATE_FLAGS_OFFSET = 26;
    private static final int UNIT_LINKED_SERIAL_OFFSET = 32;

    private static final int THINGY_X_OFFSET = 2;
    private static final int THINGY_Y_OFFSET = 4;
    private static final int THINGY_OWNER_OFFSET = 6;
    private static final int THINGY_FLAGS_OFFSET = 8;

    /**
     * Creates a {@link ChkFile} instance from a replay-embedded CHK buffer with bounded signed navigation.
     * <p>
     * Headers are indexed before the next header bounds are tested; duplicate tags retain discovery order. Negative lengths can
     * jump backward. Traversal stops at a revisited header or before-buffer address. Repeated terrain payloads overwrite
     * prefixes, while {@code UNIT} and {@code THG2} accessors visit every occurrence. Other typed accessors select the final
     * occurrence.
     *
     * @param data the CHK chunk buffer
     * @return the created {@link ChkFile} instance
     * @throws IOException if an {@code MTXM} payload exceeds the supported terrain bounds
     */
    public static @NotNull ChkFile parseTolerant(byte @NotNull [] data) throws IOException {
        return parse(data, true);
    }

    /**
     * Creates a {@link ChkFile} instance from the given CHK content.
     *
     * @param data the CHK content
     * @return the created {@link ChkFile} instance
     * @throws IOException if the content is not a valid CHK file
     */
    public static @NotNull ChkFile parse(byte @NotNull [] data) throws IOException {
        return parse(data, false);
    }

    private static @NotNull ChkFile parse(byte @NotNull [] data, boolean tolerant) throws IOException {
        Map<String, ChkSection> sections = new LinkedHashMap<>();
        List<String> sectionNames = new ArrayList<>();
        List<ChkSection> sectionEntries = new ArrayList<>();
        List<byte[]> terrainPayloads = new ArrayList<>();
        byte[] terrainData = null;
        BitSet visitedOffsets = new BitSet();
        int offset = 0;

        while (offset <= data.length - SECTION_HEADER_SIZE) {
            if (visitedOffsets.get(offset)) {
                // Only the tolerant parse accepts the negative sizes that a cycle needs.
                int cycleOffset = offset;

                LOGGER.debug(() -> "CHK compatibility: the section chain cycles at offset " + cycleOffset +
                                   " (endless in the original); the traversal stops.");

                break;
            }

            visitedOffsets.set(offset);

            boolean validSectionName = true;

            if (!tolerant) {
                for (int nameIndex = 0; nameIndex < SECTION_NAME_SIZE; nameIndex++) {
                    byte nameCharacter = data[offset + nameIndex];

                    if (nameCharacter < FIRST_PRINTABLE_NAME_CHARACTER || nameCharacter > LAST_PRINTABLE_NAME_CHARACTER) {
                        validSectionName = false;

                        break;
                    }
                }
            }

            // Preserve all four tag bytes, including non-ASCII values.
            String sectionName = validSectionName ? new String(data, offset, SECTION_NAME_SIZE, StandardCharsets.ISO_8859_1) : null;

            if (sectionName == null) {
                throw Exceptions.newIOException("The CHK file holds an invalid section name at offset {0}", offset);
            }

            int sectionSize = LittleEndianUtils.readInt32(data, offset + SECTION_NAME_SIZE);
            int sectionDataOffset = offset + SECTION_HEADER_SIZE;
            long nextHeaderOffset = sectionDataOffset + (long) sectionSize;
            boolean completePayload = sectionSize >= 0 && nextHeaderOffset <= data.length;

            if (!tolerant && !completePayload) {
                throw Exceptions.newIOException("The CHK section exceeds the file size: {0}", sectionName);
            }

            if (sectionName.equals(TERRAIN_SECTION) && Integer.compareUnsigned(sectionSize, MAXIMUM_TERRAIN_SIZE) > 0) {
                throw Exceptions.newIOException("The MTXM section exceeds the original terrain buffer size.");
            }

            byte[] sectionData = completePayload ? Arrays.copyOfRange(data, sectionDataOffset, (int) nextHeaderOffset) : null;

            if (sectionName.equals(TERRAIN_SECTION)) {
                if (sectionData == null) {
                    throw Exceptions.newIOException("The MTXM payload exceeds the supplied CHK buffer.");
                }

                terrainPayloads.add(sectionData);

                if (terrainData == null) {
                    terrainData = sectionData.clone();
                } else {
                    if (sectionSize > terrainData.length) {
                        terrainData = Arrays.copyOf(terrainData, sectionSize);
                    }

                    System.arraycopy(sectionData, 0, terrainData, 0, sectionSize);
                }
            }

            if (!sections.containsKey(sectionName)) {
                sectionNames.add(sectionName);
            }

            ChkSection section = new ChkSection(sectionName, offset, sectionSize, sectionData);

            sections.put(sectionName, section);
            sectionEntries.add(section);

            if (tolerant && (nextHeaderOffset + SECTION_HEADER_SIZE < 0 || nextHeaderOffset + SECTION_HEADER_SIZE > data.length)) {
                break;
            }

            if (nextHeaderOffset < 0) {
                long headerOffset = nextHeaderOffset;

                LOGGER.debug(() -> "CHK compatibility: the next header starts at " + headerOffset +
                                   " before the buffer (heap memory in the original); the traversal stops.");

                break;
            }

            offset = (int) nextHeaderOffset;
        }

        if (!tolerant && offset != data.length) {
            throw Exceptions.newIOException("The CHK file holds trailing garbage: {0} bytes", data.length - offset);
        }

        return new ChkFile(sections, sectionNames, sectionEntries, terrainData, terrainPayloads);
    }

    /**
     * @param version the {@code VER } value
     * @return {@code true} if the version is a Brood War map version ({@link #VERSION_BROOD_WAR} or {@link #VERSION_REMASTERED_BROOD_WAR}), {@code false} otherwise
     */
    public static boolean isExpansionVersion(int version) {
        return version == VERSION_BROOD_WAR || version == VERSION_REMASTERED_BROOD_WAR;
    }

    /**
     * @param version the {@code VER } value
     * @return {@code true} if the version is a Remastered map version ({@link #VERSION_REMASTERED_ORIGINAL} or {@link #VERSION_REMASTERED_BROOD_WAR}), {@code false} otherwise
     */
    static boolean isRemasteredVersion(int version) {
        return version == VERSION_REMASTERED_ORIGINAL || version == VERSION_REMASTERED_BROOD_WAR;
    }


    private final @NotNull Map<String, ChkSection> sections;
    private final @NotNull List<String> sectionNames;
    private final @NotNull List<ChkSection> sectionEntries;
    private final byte @Nullable [] terrainData;
    private final @NotNull List<byte[]> terrainPayloads;

    /**
     * Creates a {@link ChkFile} instance.
     *
     * @param sections        the sections keyed by name
     * @param sectionNames    the section names in file order
     * @param sectionEntries  every indexed header in discovery order
     * @param terrainData     the combined terrain prefix, or {@code null} if absent
     * @param terrainPayloads the terrain payloads in dispatch order
     */
    private ChkFile(@NotNull Map<String, ChkSection> sections, @NotNull List<String> sectionNames, @NotNull List<ChkSection> sectionEntries,
                    byte @Nullable [] terrainData, @NotNull List<byte[]> terrainPayloads) {
        this.sections        = sections;
        this.sectionNames    = sectionNames;
        this.sectionEntries  = sectionEntries;
        this.terrainData     = terrainData;
        this.terrainPayloads = terrainPayloads;
    }

    /**
     * Returns the raw payload of the final occurrence of the given section.
     *
     * @param sectionName the section name, for example {@code "VER "}
     * @return the section payload, or {@code null} if the section does not exist
     * @throws IllegalStateException if the final indexed payload cannot be read from the supplied buffer
     */
    public byte @Nullable [] getSection(@NotNull String sectionName) {
        ChkSection section = this.sections.get(sectionName);

        return section == null ? null : section.getPayload();
    }

    /**
     * @return the section names in file order
     */
    public @NotNull List<String> getSectionNames() {
        return Collections.unmodifiableList(this.sectionNames);
    }

    /**
     * @return all indexed headers, including duplicate and incomplete sections, in discovery order
     */
    public @NotNull List<ChkSection> getSections() {
        return Collections.unmodifiableList(this.sectionEntries);
    }

    /**
     * Returns every matching header in per-tag discovery order.
     *
     * @param sectionName the four-character raw tag
     * @return matching headers in discovery order
     */
    public @NotNull List<ChkSection> getSections(@NotNull String sectionName) {
        List<ChkSection> matches = new ArrayList<>();

        for (ChkSection section : this.sectionEntries) {
            if (section.getName().equals(sectionName)) {
                matches.add(section);
            }
        }

        return Collections.unmodifiableList(matches);
    }

    /**
     * Returns the low three tileset bits from the map's {@code ERA } section.
     *
     * @return the tileset index: {@code 0} badlands, {@code 1} space platform, {@code 2} installation, {@code 3} ashworld, {@code 4} jungle, {@code 5} desert, {@code 6} ice, {@code 7} twilight
     * @throws IllegalStateException if the map has no {@code ERA } section
     */
    public int getTileset() {
        return LittleEndianUtils.readUnsigned16(this.requireSection(TILESET_SECTION), 0) & TILESET_MASK;
    }

    /**
     * Returns the width of the map in tiles from the {@code DIM } section.
     *
     * @return the width in tiles
     * @throws IllegalStateException if the map has no {@code DIM } section
     */
    public int getWidth() {
        return LittleEndianUtils.readUnsigned16(this.requireSection(DIMENSIONS_SECTION), 0);
    }

    /**
     * Returns the height of the map in tiles from the {@code DIM } section.
     *
     * @return the height in tiles
     * @throws IllegalStateException if the map has no {@code DIM } section
     */
    public int getHeight() {
        return LittleEndianUtils.readUnsigned16(this.requireSection(DIMENSIONS_SECTION), DIMENSION_HEIGHT_OFFSET);
    }

    /**
     * Returns complete tile words from the combined {@code MTXM} prefix copies.
     * <p>
     * A short final payload preserves earlier trailing bytes, including the upper byte after an odd-length copy.
     *
     * @return the supplied tile words in row-major order
     * @throws IllegalStateException if the map has no {@code MTXM} section
     */
    public int @NotNull [] getTileIds() {
        byte[] tileData = this.terrainData == null ? this.requireSection(TERRAIN_SECTION) : this.terrainData;
        int[] tileIds = new int[tileData.length / TILE_WORD_SIZE];

        for (int tileIndex = 0; tileIndex < tileIds.length; tileIndex++) {
            tileIds[tileIndex] = LittleEndianUtils.readUnsigned16(tileData, tileIndex * TILE_WORD_SIZE);
        }

        return tileIds;
    }

    /**
     * Applies each terrain prefix copy followed by CV5 group normalization.
     * <p>
     * Invalid groups become {@code 0x20} before the next payload is copied, including an odd-byte overwrite.
     * Processing starts with the first payload supplying every map cell.
     *
     * @param tileGroupCount the number of installed CV5 records, including fallback group {@code 2}
     * @return the normalized map tile words in row-major order
     * @throws IllegalArgumentException if fallback group {@code 2} is unavailable or dimensions exceed the supported bounds
     * @throws IllegalStateException    if the map has no {@code DIM } section or no payload fully supplies the map
     */
    public int @NotNull [] getTileIds(int tileGroupCount) {
        if (tileGroupCount <= FALLBACK_TILE_GROUP) {
            throw Exceptions.newIllegalArgumentException("Terrain normalization requires CV5 fallback group 2.");
        }

        byte[] dimensions = this.requireSection(DIMENSIONS_SECTION);

        if (dimensions.length != DIMENSIONS_SIZE || this.getWidth() > MAXIMUM_DIMENSION || this.getHeight() > MAXIMUM_DIMENSION) {
            throw Exceptions.newIllegalArgumentException("The DIM payload must contain two dimensions no greater than 256.");
        }

        int tileByteCount = this.getWidth() * this.getHeight() * TILE_WORD_SIZE;

        if (this.terrainData == null || this.terrainData.length < tileByteCount) {
            throw Exceptions.newIllegalStateException("MTXM payloads leave native terrain bytes unobserved.");
        }

        byte[] normalizedData = new byte[this.terrainData.length];
        boolean fullySupplied = false;

        for (byte[] payload : this.terrainPayloads) {
            fullySupplied |= payload.length >= tileByteCount;

            if (!fullySupplied) {
                continue;
            }

            System.arraycopy(payload, 0, normalizedData, 0, payload.length);

            for (int tileOffset = 0; tileOffset < tileByteCount; tileOffset += TILE_WORD_SIZE) {
                int tileIdentifier = LittleEndianUtils.readUnsigned16(normalizedData, tileOffset);

                if (TerrainTileset.getGroupIndex(tileIdentifier) >= tileGroupCount) {
                    normalizedData[tileOffset]     = INVALID_GROUP_TILE;
                    normalizedData[tileOffset + 1] = 0;
                }
            }
        }

        int[] tileIdentifiers = new int[tileByteCount / TILE_WORD_SIZE];

        for (int tileIndex = 0; tileIndex < tileIdentifiers.length; tileIndex++) {
            tileIdentifiers[tileIndex] = LittleEndianUtils.readUnsigned16(normalizedData, tileIndex * TILE_WORD_SIZE);
        }

        return tileIdentifiers;
    }

    /**
     * Returns records from every {@code UNIT} section in discovery order.
     *
     * @return the preplaced units
     * @throws IllegalStateException if the map has no {@code UNIT} section, or a payload is incomplete or is not divisible by the {@code 36}-byte record size
     */
    public @NotNull List<ChkUnit> getUnits() {
        if (!this.hasSection(UNIT_SECTION)) {
            throw Exceptions.newIllegalStateException("The CHK file misses the required section: UNIT");
        }

        List<ChkUnit> units = new ArrayList<>();

        for (ChkSection section : this.getSections(UNIT_SECTION)) {
            byte[] unitData = section.getPayload();

            if (unitData.length % ChkUnit.ENTRY_SIZE != 0) {
                throw Exceptions.newIllegalStateException("The UNIT payload at offset {0} is not divisible by 36.", section.getPayloadOffset());
            }

            for (int offset = 0; offset < unitData.length; offset += ChkUnit.ENTRY_SIZE) {
                long serial = LittleEndianUtils.readUnsigned32(unitData, offset);
                int x = LittleEndianUtils.readUnsigned16(unitData, offset + UNIT_X_OFFSET);
                int y = LittleEndianUtils.readUnsigned16(unitData, offset + UNIT_Y_OFFSET);
                int unitId = LittleEndianUtils.readUnsigned16(unitData, offset + UNIT_TYPE_OFFSET);
                int relationType = LittleEndianUtils.readUnsigned16(unitData, offset + UNIT_RELATION_TYPE_OFFSET);
                int validPropertiesFlags = LittleEndianUtils.readUnsigned16(unitData, offset + UNIT_VALID_PROPERTIES_OFFSET);
                int validElementsFlags = LittleEndianUtils.readUnsigned16(unitData, offset + UNIT_VALID_ELEMENTS_OFFSET);
                int owner = unitData[offset + UNIT_OWNER_OFFSET] & BYTE_MASK;
                int hitPointsPercent = unitData[offset + UNIT_HIT_POINTS_OFFSET] & BYTE_MASK;
                int shieldPointsPercent = unitData[offset + UNIT_SHIELD_POINTS_OFFSET] & BYTE_MASK;
                int energyPercent = unitData[offset + UNIT_ENERGY_OFFSET] & BYTE_MASK;
                long resourceAmount = LittleEndianUtils.readUnsigned32(unitData, offset + UNIT_RESOURCE_AMOUNT_OFFSET);
                int hangarCount = LittleEndianUtils.readUnsigned16(unitData, offset + UNIT_HANGAR_COUNT_OFFSET);
                int stateFlags = LittleEndianUtils.readUnsigned16(unitData, offset + UNIT_STATE_FLAGS_OFFSET);
                long linkedSerial = LittleEndianUtils.readUnsigned32(unitData, offset + UNIT_LINKED_SERIAL_OFFSET);

                units.add(new ChkUnit(serial,
                                      x, y,
                                      unitId,
                                      relationType,
                                      validPropertiesFlags, validElementsFlags,
                                      owner,
                                      hitPointsPercent, shieldPointsPercent, energyPercent,
                                      resourceAmount,
                                      hangarCount,
                                      stateFlags,
                                      linkedSerial));
            }
        }

        return units;
    }

    /**
     * Returns records from every {@code THG2} section in discovery order without constructing units or sprites.
     *
     * @return the {@code THG2} records, or an empty list if the optional tag is absent
     * @throws IllegalStateException if a payload is incomplete or is not divisible by the ten-byte record size
     */
    public @NotNull List<ChkThingy> getThingies() {
        List<ChkThingy> thingies = new ArrayList<>();

        for (ChkSection section : this.getSections(THINGY_SECTION)) {
            byte[] thingyData = section.getPayload();

            if (thingyData.length % ChkThingy.ENTRY_SIZE != 0) {
                throw Exceptions.newIllegalStateException("The THG2 payload at offset {0} is not divisible by 10.", section.getPayloadOffset());
            }

            for (int offset = 0; offset < thingyData.length; offset += ChkThingy.ENTRY_SIZE) {
                thingies.add(new ChkThingy(section.getPayloadOffset() + offset,
                                           LittleEndianUtils.readUnsigned16(thingyData, offset),
                                           LittleEndianUtils.readUnsigned16(thingyData, offset + THINGY_X_OFFSET),
                                           LittleEndianUtils.readUnsigned16(thingyData, offset + THINGY_Y_OFFSET),
                                           thingyData[offset + THINGY_OWNER_OFFSET] & BYTE_MASK,
                                           LittleEndianUtils.readUnsigned16(thingyData, offset + THINGY_FLAGS_OFFSET)));
            }
        }

        return Collections.unmodifiableList(thingies);
    }

    /**
     * Returns the map string table selected by this map version.
     * <p>
     * The section lists dispatch every {@code STR } section and, for versions {@code 64} and {@code 206} only, every
     * {@code STRx} section after them. Both share one handler: a section whose unsigned size is below the offset-table
     * header ({@code 2} bytes, or {@code 4} for {@code STRx}) is ignored, any other replaces the table, so the last accepted section wins
     * and {@code STRx} overrides {@code STR }. The lookup returns no string for an index at or past the count, an offset
     * entry past the section, or an offset at or past the section size; those strings are empty here, and the list stops
     * at the last offset entry inside the section.
     *
     * @return the strings in table order, or an empty list when no string section is loaded
     */
    public @NotNull List<String> getStrings() {
        List<byte[]> rawStrings = this.getStringBytes();
        List<String> strings = new ArrayList<>(rawStrings.size());

        for (byte[] rawString : rawStrings) {
            strings.add(LegacyTextUtils.decode(rawString, 0, rawString.length));
        }

        return strings;
    }

    /**
     * Returns the raw bytes of the map strings of {@link #getStrings()}, without the terminator and without decoding.
     * <p>
     * File names from map strings (Play WAV, transmissions) must be hashed from these bytes.
     *
     * @return the raw strings in table order, or an empty list when no string section is loaded
     */
    public @NotNull List<byte[]> getStringBytes() {
        ChkSection stringSection = this.getStringSection();

        if (stringSection == null) {
            return Collections.emptyList();
        }

        byte[] stringData = stringSection.getPayload();
        boolean extended = stringSection.getName().equals(EXTENDED_STRING_SECTION);
        int entrySize = extended ? EXTENDED_STRING_ENTRY_SIZE : STRING_ENTRY_SIZE;
        long stringCount = extended ? LittleEndianUtils.readUnsigned32(stringData, 0) : LittleEndianUtils.readUnsigned16(stringData, 0);
        long presentCount = Math.min(stringCount, (stringData.length - entrySize) / entrySize);
        List<byte[]> strings = new ArrayList<>((int) presentCount);

        for (int stringIndex = 0; stringIndex < presentCount; stringIndex++) {
            int entryOffset = entrySize + stringIndex * entrySize;
            long stringOffset = extended ? LittleEndianUtils.readUnsigned32(stringData, entryOffset)
                                         : LittleEndianUtils.readUnsigned16(stringData, entryOffset);

            if (stringOffset >= stringData.length) {
                strings.add(new byte[0]);

                continue;
            }

            int stringEnd = (int) stringOffset + TerminatedStringUtils.getTextLength(stringData, (int) stringOffset);

            strings.add(Arrays.copyOfRange(stringData, (int) stringOffset, stringEnd));
        }

        return strings;
    }

    /**
     * Returns the selected string section: the last {@code STR } section, or for versions {@code 64} and
     * {@code 206} the last {@code STRx} after them, whose unsigned size reaches the offset-table header; see
     * {@link #getStrings()}.
     *
     * @return the section, or {@code null} when no string section is loaded
     */
    public @Nullable ChkSection getStringSection() {
        List<ChkSection> candidates = new ArrayList<>(this.getSections(STRING_SECTION));
        int version = -1;

        for (ChkSection section : this.getSections(VERSION_SECTION)) {
            if (section.hasCompletePayload() && section.getDeclaredSize() >= WORD_SECTION_SIZE) {
                version = LittleEndianUtils.readUnsigned16(section.getPayload(), 0);
            }
        }

        if (isRemasteredVersion(version)) {
            candidates.addAll(this.getSections(EXTENDED_STRING_SECTION));
        }

        ChkSection stringSection = null;

        for (ChkSection candidate : candidates) {
            boolean extended = candidate.getName().equals(EXTENDED_STRING_SECTION);
            int headerSize = extended ? EXTENDED_STRING_ENTRY_SIZE : STRING_ENTRY_SIZE;

            if (Integer.compareUnsigned(candidate.getDeclaredSize(), headerSize) >= 0) {
                stringSection = candidate;
            }
        }

        return stringSection;
    }

    /**
     * Checks whether the CHK file holds the given section.
     *
     * @param sectionName the section name, for example {@code "VER "}
     * @return {@code true} if the section exists, {@code false} otherwise
     */
    public boolean hasSection(@NotNull String sectionName) {
        return this.sections.containsKey(sectionName);
    }


    /**
     * Returns the raw payload of the given section.
     *
     * @param sectionName the section name, for example {@code "VER "}
     * @return the section payload
     * @throws IllegalStateException if the section does not exist
     */
    public byte @NotNull [] requireSection(@NotNull String sectionName) {
        ChkSection section = this.sections.get(sectionName);

        if (section == null) {
            throw Exceptions.newIllegalStateException("The CHK file misses the required section: {0}", sectionName);
        }

        return section.getPayload();
    }
}
