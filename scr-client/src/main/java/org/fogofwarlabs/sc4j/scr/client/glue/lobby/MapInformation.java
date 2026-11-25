package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkUnit;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkValidatorUtils;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads scenario metadata, slot counts and preview units from a validated map archive.
 */
public final class MapInformation {
    private static final int PROPERTIES_SIZE = 4;
    private static final int NAME_STRING_OFFSET = 0;
    private static final int DESCRIPTION_STRING_OFFSET = 2;
    private static final int START_LOCATION = 214;
    private static final int FIRST_MINERAL_FIELD = 176;
    private static final int LAST_MINERAL_FIELD = 178;
    private static final int VESPENE_GEYSER = 188;
    private static final int FIRST_PLAYABLE_TYPE = PlayerTypes.RESCUE_PASSIVE;
    private static final int LAST_PLAYABLE_TYPE = PlayerTypes.NEUTRAL;
    private static final int COMPUTER = PlayerTypes.EITHER_PREFER_COMPUTER;
    private static final int HUMAN = PlayerTypes.EITHER_PREFER_HUMAN;
    private static final int SLOT_COUNT = PlayerTable.PLAYABLE_PLAYER_SLOTS;
    private static final int TILESET_MASK = 7;

    /**
     * Creates a {@link MapInformation} instance from a map file's scenario.
     *
     * @param file             the map archive
     * @param mapCompatibility whether the relaxed {@code 1.16}-era map rules apply
     * @return the created {@link MapInformation} instance
     * @throws IOException if the archive cannot be read or the map list rejects the map
     */
    public static @NotNull MapInformation read(@NotNull Path file, boolean mapCompatibility) throws IOException {
        ChkFile map = ChkFile.parseTolerant(MpqArchive.open(file, mapCompatibility).readFile(ChkFile.ARCHIVE_ENTRY));

        ChkValidatorUtils.validateMapInformation(map, mapCompatibility);

        byte[] properties = map.getSection(ChkFile.SCENARIO_PROPERTIES_SECTION);
        List<String> strings = map.getStrings();
        String name;
        String description;

        if (properties == null || properties.length < PROPERTIES_SIZE) {
            name        = "";
            description = "";
        } else {
            name        = findString(strings, readStringIdentifier(properties, NAME_STRING_OFFSET));
            description = findString(strings, readStringIdentifier(properties, DESCRIPTION_STRING_OFFSET));
        }

        byte[] owners = map.getSection(ChkFile.OWNER_SECTION);
        int playableSlotCount = 0;
        int startSlotCount = 0;

        for (int slot = 0; owners != null && slot < owners.length && slot < SLOT_COUNT; slot++) {
            int ownerType = Byte.toUnsignedInt(owners[slot]);

            if (ownerType >= FIRST_PLAYABLE_TYPE && ownerType <= LAST_PLAYABLE_TYPE) {
                playableSlotCount++;
            }

            if (ownerType == COMPUTER || ownerType == HUMAN) {
                startSlotCount++;
            }
        }

        List<ChkUnit> previewUnits = new ArrayList<>();

        for (ChkUnit unit : map.getUnits()) {
            int unitTypeIdentifier = unit.getUnitId();

            if (unitTypeIdentifier == START_LOCATION ||
                unitTypeIdentifier >= FIRST_MINERAL_FIELD && unitTypeIdentifier <= LAST_MINERAL_FIELD ||
                unitTypeIdentifier == VESPENE_GEYSER) {
                previewUnits.add(unit);
            }
        }

        return new MapInformation(map, name, description, playableSlotCount, startSlotCount, previewUnits);
    }

    /**
     * Reads a little-endian string id from the scenario properties section.
     *
     * @param properties the scenario properties section
     * @param offset     the offset of the little-endian string id
     * @return the unsigned string id
     */
    private static int readStringIdentifier(byte @NotNull [] properties, int offset) {
        return Byte.toUnsignedInt(properties[offset]) | Byte.toUnsignedInt(properties[offset + 1]) << Byte.SIZE;
    }

    /**
     * Finds a map string by its one-based id.
     *
     * @param strings    the map strings
     * @param identifier the one-based string id
     * @return the string, or an empty string for an id outside the strings
     */
    private static @NotNull String findString(@NotNull List<String> strings, int identifier) {
        return identifier >= 1 && identifier <= strings.size() ? strings.get(identifier - 1) : "";
    }

    private final @NotNull ChkFile map;

    private final @NotNull String name;
    private final @NotNull String description;

    private final int playableSlots;
    private final int startSlots;

    private final @NotNull List<ChkUnit> previewUnits;

    private MapInformation(@NotNull ChkFile map,
                           @NotNull String name, @NotNull String description,
                           int playableSlots, int startSlots,
                           @NotNull List<ChkUnit> previewUnits) {
        this.map = map;

        this.name        = name;
        this.description = description;

        this.playableSlots = playableSlots;
        this.startSlots    = startSlots;

        this.previewUnits = List.copyOf(previewUnits);
    }

    /**
     * @return the parsed map
     */
    public @NotNull ChkFile getMap() {
        return this.map;
    }

    /**
     * @return the scenario name ({@code SPRP} name string), with its color codes
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the scenario description ({@code SPRP} description string)
     */
    public @NotNull String getDescription() {
        return this.description;
    }

    /**
     * @return the map width in tiles
     */
    public int getWidth() {
        return this.map.getWidth();
    }

    /**
     * @return the map height in tiles
     */
    public int getHeight() {
        return this.map.getHeight();
    }

    /**
     * @return the tileset ({@code ERA} masked with {@code 7})
     */
    public int getTileset() {
        return this.map.getTileset() & TILESET_MASK;
    }

    /**
     * @return the playable owner slots counted from the map
     */
    public int getPlayableSlots() {
        return this.playableSlots;
    }

    /**
     * @return the start slots: players {@code 0..7} whose {@code OWNR} type is human ({@code 6}) or computer ({@code 5})
     */
    public int getStartSlots() {
        return this.startSlots;
    }

    /**
     * @return the start locations, mineral fields and geysers the preview shows
     */
    public @NotNull List<ChkUnit> getPreviewUnits() {
        return this.previewUnits;
    }
}
