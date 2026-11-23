package org.fogofwarlabs.sc4j.scr.render.ui;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Palette;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedGraphics;
import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.image.DdsFile;
import org.fogofwarlabs.sc4j.scr.data.format.image.DdsGrpFile;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrModSheetFrame;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceKeys;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.fogofwarlabs.sc4j.scr.render.RenderAssets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Decodes interface images, command icons, wireframe colors and minimap colors from installed assets. Indexed images use the map tileset palette.
 */
public final class InterfaceAssets {
    /**
     * The {@code cmdbtns.dds.grp} frame of a raised command button.
     */
    public static final int BUTTON_UP = 0;
    /**
     * The {@code cmdbtns.dds.grp} frame of a pressed command button.
     */
    public static final int BUTTON_DOWN = 1;

    /**
     * Unlocalized interface assets resolve without a locale; {@link AssetResolver} needs one for localized lookups only.
     */
    private static final @NotNull String ASSET_LOCALE = "enUS";
    private static final @NotNull String @NotNull [] TILESET_NAMES = {
            "badlands", "platform", "install", "ashworld",
            "jungle", "desert", "ice", "twilight"
    };
    /**
     * The SD console image prefixes by race: Zerg, Terran, Protoss.
     */
    private static final @NotNull String @NotNull [] CONSOLE_PREFIXES = {"z", "t", "p"};
    /**
     * The console folders by race: Zerg, Terran, Protoss.
     */
    private static final @NotNull String @NotNull [] CONSOLE_FOLDERS = {"zerg", "terran", "protoss"};
    /**
     * The size of a {@code .wpe} palette: {@code 4} bytes per entry.
     */
    private static final int PALETTE_SIZE = RenderAssets.PALETTE_ENTRIES * RenderAssets.PALETTE_ENTRY_SIZE;

    /**
     * The rows of {@code game/tblink.pcx} and the indices per row; entry {@code 1} of a row is the ping color.
     */
    private static final int BLINK_ROWS = 19;
    private static final int BLINK_ROW_SIZE = 8;
    private static final int BLINK_COLOR_ENTRY = 1;
    /**
     * The entries of {@code game/twire.pcx}.
     */
    private static final int WIREFRAME_COLORS = 24;
    /**
     * The prefix of the cache key of a mod's sheet file, which no installed path starts with.
     */
    private static final @NotNull String ADDED_SHEET_KEY = "mod:";
    /**
     * The sheet versions above this shift carry a texture scale in their low {@code 4} bits.
     */
    private static final int SHEET_SCALED_VERSION_SHIFT = 12;
    /**
     * The texture scale bits of a sheet version or a DDS scale DWORD.
     */
    private static final int SCALE_MASK = 0xF;
    /**
     * The DDS signature and header size, and the header DWORD whose low {@code 4} bits hold the texture scale.
     */
    private static final int DDS_HEADER_SIZE = 0x80;
    private static final int DDS_SCALE_OFFSET = 0x7C;
    /**
     * The VX4EX minitile reference bits below its VR4 index: the flip bit.
     */
    private static final int MINITILE_INDEX_SHIFT = 1;
    /**
     * The mask of an unsigned byte, of a palette index and of a color channel.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The squared RGB distance above every real distance ({@link #findNearestNonCyclingIndex}).
     */
    private static final long NO_DISTANCE = 0xFFFFFFFFL;
    /**
     * The color-cycling ranges per tileset ({@code first, last}); Space Platform and Installation have none.
     */
    private static final int @NotNull [][][] CYCLING_RANGES = {
            {{1, 6}, {7, 13}, {248, 254}}, {}, {}, {{1, 4}, {5, 8}, {9, 12}}, {{1, 6}, {7, 13}, {248, 254}},
            {{1, 13}, {248, 254}}, {{1, 13}, {248, 254}}, {{1, 13}, {248, 254}}
    };

    /**
     * Holds a decoded sheet frame and its texture-pixels-per-interface-pixel scale.
     */
    public static final class ScaledImage {
        private final @NotNull InterfaceImage image;
        private final int scale;

        ScaledImage(@NotNull InterfaceImage image, int scale) {
            this.image = image;
            this.scale = scale;
        }

        /**
         * @return the decoded frame
         */
        public @NotNull InterfaceImage getImage() {
            return this.image;
        }

        /**
         * @return the texture pixels per interface pixel
         */
        public int getScale() {
            return this.scale;
        }

        /**
         * @return the width in interface pixels, {@code width / scale}
         */
        public int getInterfaceWidth() {
            return this.image.getWidth() / this.scale;
        }

        /**
         * @return the height in interface pixels, {@code height / scale}
         */
        public int getInterfaceHeight() {
            return this.image.getHeight() / this.scale;
        }
    }

    /**
     * Creates an {@link InterfaceAssets} instance with the interface images of a map tileset.
     *
     * @param resources the resource provider of the installation
     * @param tileset   the map tileset index from {@code 0} through {@code 7}
     * @return the created {@link InterfaceAssets} instance
     * @throws IOException if an asset is missing or malformed
     */
    public static @NotNull InterfaceAssets load(@NotNull ScrResourceProvider resources, int tileset) throws IOException {
        if (tileset < 0 || tileset >= TILESET_NAMES.length) {
            throw Exceptions.newIOException("Unsupported tileset index: {0}", tileset);
        }

        String prefix = "tileset/" + TILESET_NAMES[tileset];
        CascStorage storage = resources.getStorage();
        byte[] palette = copyPaletteEntries(resources.loadPalette(ScrResourceKeys.ofPalette(prefix + ".wpe")));

        return new InterfaceAssets(resources, storage,
                                   tileset,
                                   palette,
                                   storage.readFile(prefix + ".cv5"), storage.readFile(prefix + ".vx4ex"), storage.readFile(prefix + ".vr4"),
                                   RenderAssets.copyIndices(resources.loadSpriteSheet(ScrResourceKeys.ofPcx("game/tminimap.pcx")),
                                                            RenderAssets.REMAP_ROWS, 1),
                                   resources.loadSpriteSheet(ScrResourceKeys.ofGrp("unit/cmdicons/cmdicons.grp")));
    }

    /**
     * Copies the colors of a palette into the {@code .wpe} entry layout: red, green and blue bytes, then an unused byte.
     *
     * @param palette the palette of {@code 256} colors
     * @return the {@code 1024} entry bytes
     * @throws IOException if the palette does not have {@code 256} colors
     */
    private static byte @NotNull [] copyPaletteEntries(@NotNull Palette palette) throws IOException {
        if (palette.getSize() != RenderAssets.PALETTE_ENTRIES) {
            throw Exceptions.newIOException("The WPE palette must have 256 four-byte entries.");
        }

        byte[] entries = new byte[PALETTE_SIZE];

        for (int index = 0; index < RenderAssets.PALETTE_ENTRIES; index++) {
            int color = palette.getColor(index);
            int offset = index * RenderAssets.PALETTE_ENTRY_SIZE;

            entries[offset]                              = (byte) (color >>> OverlayPass.RGB_RED_SHIFT);
            entries[offset + RenderAssets.PALETTE_GREEN] = (byte) (color >>> OverlayPass.RGB_GREEN_SHIFT);
            entries[offset + RenderAssets.PALETTE_BLUE]  = (byte) color;
        }

        return entries;
    }

    /**
     * Creates an {@link AssetResolver} instance for standard-definition console, cursor and command button images.
     *
     * @param storage the installed game storage
     * @return the created {@link AssetResolver} instance
     */
    private static @NotNull AssetResolver createClassicResolver(@NotNull CascStorage storage) {
        return new AssetResolver(storage, AssetResolver.AssetSet.SD, "", ASSET_LOCALE);
    }

    private final @NotNull ScrResourceProvider resources;
    private final @NotNull CascStorage storage;

    private final int tileset;

    private final byte @NotNull [] palette;
    private final byte @NotNull [] tileGroups;
    private final byte @NotNull [] megatiles;
    private final byte @NotNull [] minitiles;

    private final byte @NotNull [] minimapColors;

    private final @NotNull SpriteSheet commandIcons;

    private AssetResolver.@NotNull AssetSet assetSet;
    private @NotNull String skinFolder;
    private final @NotNull Map<String, DdsGrpFile> sheets;

    private byte @Nullable [] wireframeColorTable;
    private byte @Nullable [] blinkColors;
    private int @Nullable [] minimapRemap;

    private InterfaceAssets(@NotNull ScrResourceProvider resources, @NotNull CascStorage storage,
                            int tileset,
                            byte @NotNull [] palette,
                            byte @NotNull [] tileGroups, byte @NotNull [] megatiles, byte @NotNull [] minitiles,
                            byte @NotNull [] minimapColors,
                            @NotNull SpriteSheet commandIcons) {
        this.resources = resources;
        this.storage   = storage;

        this.tileset = tileset;

        this.palette    = palette;
        this.tileGroups = tileGroups;
        this.megatiles  = megatiles;
        this.minitiles  = minitiles;

        this.minimapColors = minimapColors;

        this.commandIcons = commandIcons;

        this.assetSet   = AssetResolver.AssetSet.SD;
        this.skinFolder = "";
        this.sheets     = new HashMap<>();

        this.wireframeColorTable = null;
        this.blinkColors         = null;
        this.minimapRemap        = null;
    }

    /**
     * @return the current asset set
     */
    public AssetResolver.@NotNull AssetSet getAssetSet() {
        return this.assetSet;
    }

    /**
     * @return the current asset set's path prefix ({@code SD/}, {@code HD2/} or empty), for cache keys
     */
    public @NotNull String getQualityPrefix() {
        return this.assetSet.getPrefix();
    }

    /**
     * @param frame the icon frame
     * @return the classic {@code cmdicons.grp} frame-header width byte without canvas margins, used to center SD command and stat icons; the SD width of an added icon
     * @throws IOException if the SD file of an added icon cannot be read
     */
    public int getClassicCommandIconWidth(int frame) throws IOException {
        ScrModSheetFrame added = this.findAddedSheetFrame(ScrAddedGraphics.COMMAND_ICON_SHEET, frame);

        return added != null ? this.getAddedSheet(added, AssetResolver.AssetSet.SD).getFrameWidth(0) : this.commandIcons.getFrame(frame).getWidth();
    }

    /**
     * @param frame the icon frame
     * @return the classic {@code cmdicons.grp} frame-header height byte without canvas margins; the SD height of an added icon
     * @throws IOException if the SD file of an added icon cannot be read
     */
    public int getClassicCommandIconHeight(int frame) throws IOException {
        ScrModSheetFrame added = this.findAddedSheetFrame(ScrAddedGraphics.COMMAND_ICON_SHEET, frame);

        return added != null ? this.getAddedSheet(added, AssetResolver.AssetSet.SD).getFrameHeight(0) : this.commandIcons.getFrame(frame).getHeight();
    }

    /**
     * @return the number of CV5 tile groups in the tileset
     */
    public int getTileGroupCount() {
        return this.tileGroups.length / TerrainTileset.CV5_RECORD_SIZE;
    }

    /**
     * Sets the asset set and clears the cached sheets when it changes.
     *
     * @param assetSet the asset set
     */
    public void setAssetSet(AssetResolver.@NotNull AssetSet assetSet) {
        if (assetSet != this.assetSet) {
            this.sheets.clear();
        }

        this.assetSet = assetSet;
    }

    /**
     * Sets an optional skin folder and clears the cached sheets.
     *
     * @param skin the skin folder name without the separator, or {@code null} for none
     */
    public void setSkin(@Nullable String skin) {
        this.skinFolder = skin == null ? "" : skin + "/";
        this.sheets.clear();
    }


    /**
     * Decodes the SD console image of a race, whose bottom edge is the bottom of the {@code 640x480} interface.
     *
     * @param race the race: {@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss
     * @return the decoded image
     * @throws IOException if the image is missing or malformed
     */
    public @NotNull InterfaceImage decodeConsole(int race) throws IOException {
        DdsFile image = DdsFile.parse(
                createClassicResolver(this.storage).read("game/" + CONSOLE_PREFIXES[Math.clamp(race, 0, CONSOLE_PREFIXES.length - 1)] + "console.DDS")
        );

        return new InterfaceImage(image.getWidth(), image.getHeight(), image.decodeRgba());
    }

    /**
     * Decodes a frame of the standard-definition race button sheet.
     *
     * @param race  the race: {@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss
     * @param frame the frame
     * @return the decoded image
     * @throws IOException if the sheet or frame is missing or malformed
     */
    public @NotNull InterfaceImage decodeButtonFrame(int race, int frame) throws IOException {
        String path = "game/consoles/" + CONSOLE_FOLDERS[Math.clamp(race, 0, CONSOLE_FOLDERS.length - 1)] + "/cmdbtns.dds.grp";
        DdsGrpFile sheet = DdsGrpFile.parse(createClassicResolver(this.storage).read(path));
        DdsFile image = sheet.getFrame(frame);

        return new InterfaceImage(image.getWidth(), image.getHeight(), image.decodeRgba());
    }

    /**
     * Decodes a frame of the SD cursor {@code SD/cursor/<name>.dds.grp}.
     *
     * @param name  the classic cursor name, for example {@code arrow}, {@code MagG} or {@code ScrollUL}
     * @param frame the frame
     * @return the decoded image
     * @throws IOException if the cursor or frame is missing or malformed
     */
    public @NotNull InterfaceImage decodeCursor(@NotNull String name, int frame) throws IOException {
        DdsGrpFile sheet = DdsGrpFile.parse(createClassicResolver(this.storage).read("cursor/" + name.toLowerCase(Locale.ROOT) + ".dds.grp"));
        DdsFile image = sheet.getFrame(frame);

        return new InterfaceImage(image.getWidth(), image.getHeight(), image.decodeRgba());
    }

    /**
     * Returns the number of frames of an SD cursor.
     *
     * @param name the classic cursor name
     * @return the frame count of {@code SD/cursor/<name>.dds.grp}
     * @throws IOException if the cursor is missing or malformed
     */
    public int getCursorFrameCount(@NotNull String name) throws IOException {
        return DdsGrpFile.parse(createClassicResolver(this.storage).read("cursor/" + name.toLowerCase(Locale.ROOT) + ".dds.grp")).getFrameCount();
    }

    /**
     * Decodes a piece of the HD console: {@code game/consoles/<race>/console_<piece>.DDS} (HD, {@code 4} texture pixels per interface pixel) or the
     * {@code HD2/} copy ({@code 2} per interface pixel).
     *
     * @param race  the race: {@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss
     * @param piece {@code left}, {@code center}, {@code right}, {@code lgap} or {@code rgap}
     * @param hd2   whether the HD2 copy is read
     * @return the decoded image
     * @throws IOException if the piece is missing or malformed
     */
    public @NotNull InterfaceImage decodeHdConsolePiece(int race, @NotNull String piece, boolean hd2) throws IOException {
        String path = "game/consoles/" + CONSOLE_FOLDERS[Math.clamp(race, 0, CONSOLE_FOLDERS.length - 1)] + "/console_" + piece + ".DDS";
        DdsFile image = DdsFile.parse(this.createResolver(hd2 ? AssetResolver.AssetSet.HD2 : AssetResolver.AssetSet.HD).read(path));

        return new InterfaceImage(image.getWidth(), image.getHeight(), image.decodeRgba());
    }


    /**
     * Tests whether any resolver candidate exists for an asset in the current set.
     *
     * @param path the path without the set prefix, for example {@code game/consoles/terran/console_TIME.DDS}
     * @return {@code true} if a candidate exists, {@code false} otherwise
     */
    public boolean hasAsset(@NotNull String path) {
        return this.createResolver(this.assetSet).resolve(path) != null;
    }

    /**
     * Tests whether a frame of a sheet is an added one: a command icon or wireframe frame of an added unit type ({@link ScrAddedGraphics}) in the
     * added graphics of the resource provider.
     *
     * @param path  the path without the set prefix
     * @param frame the frame index
     * @return {@code true} if the frame is an added one, {@code false} otherwise
     */
    public boolean isAddedSheetFrame(@NotNull String path, int frame) {
        return this.findAddedSheetFrame(path, frame) != null;
    }

    /**
     * Decodes a frame of a {@code .dds.grp} sheet of the current asset set. An added frame ({@link #isAddedSheetFrame}) is the first frame of the
     * mod's file of the set.
     *
     * @param path  the path without the set prefix, for example {@code unit/cmdicons/cmdicons.dds.grp}
     * @param frame the frame index
     * @return the frame and its scale
     * @throws IOException if the sheet or frame is missing or malformed
     */
    public @NotNull ScaledImage decodeSheetFrame(@NotNull String path, int frame) throws IOException {
        ScrModSheetFrame added = this.findAddedSheetFrame(path, frame);
        DdsGrpFile sheet = added != null ? this.getAddedSheet(added, this.assetSet) : this.getSheet(path);
        DdsFile image = sheet.getFrame(added != null ? 0 : frame);
        int version = sheet.getVersion();
        int scale = (version >>> SHEET_SCALED_VERSION_SHIFT) != 0 ? Math.max(1, version & SCALE_MASK) : 1;

        return new ScaledImage(new InterfaceImage(image.getWidth(), image.getHeight(), image.decodeRgba()), scale);
    }

    /**
     * Returns the number of frames of a {@code .dds.grp} sheet of the current asset set.
     *
     * @param path the path without the set prefix
     * @return the number of frames of the sheet
     * @throws IOException if the sheet is missing or malformed
     */
    public int getSheetFrameCount(@NotNull String path) throws IOException {
        return this.getSheet(path).getFrameCount();
    }

    private @Nullable ScrModSheetFrame findAddedSheetFrame(@NotNull String path, int frame) {
        return frame < ScrAddedGraphics.FIRST_INTERFACE_FRAME ? null : this.resources.getAddedGraphics().findSheetFrame(path, frame);
    }

    private @NotNull DdsGrpFile getAddedSheet(@NotNull ScrModSheetFrame added, AssetResolver.@NotNull AssetSet set) throws IOException {
        String cacheKey = ADDED_SHEET_KEY + added.getPath(set);
        DdsGrpFile sheet = this.sheets.get(cacheKey);

        if (sheet == null) {
            sheet = added.readSheet(set);
            this.sheets.put(cacheKey, sheet);
        }

        return sheet;
    }

    private @NotNull DdsGrpFile getSheet(@NotNull String path) throws IOException {
        String cacheKey = this.assetSet.getPrefix() + this.skinFolder + path;
        DdsGrpFile sheet = this.sheets.get(cacheKey);

        if (sheet == null) {
            sheet = DdsGrpFile.parse(this.createResolver(this.assetSet).read(path));
            this.sheets.put(cacheKey, sheet);
        }

        return sheet;
    }

    /**
     * Decodes a texture and its scale from the low nibble of the DDS header scale field. A scale of {@code 0} becomes {@code 1}.
     *
     * @param path the path without the set prefix, for example {@code game/observer/UIObserverSquareRight.DDS}
     * @return the image and its scale
     * @throws IOException if neither file exists or the texture is malformed
     */
    public @NotNull ScaledImage decodeTexture(@NotNull String path) throws IOException {
        byte[] data = this.createResolver(this.assetSet).read(path);
        DdsFile image = DdsFile.parse(data);
        int scale = data.length > DDS_HEADER_SIZE ? Math.max(1, data[DDS_SCALE_OFFSET] & SCALE_MASK) : 1;

        return new ScaledImage(new InterfaceImage(image.getWidth(), image.getHeight(), image.decodeRgba()), scale);
    }

    private @NotNull AssetResolver createResolver(AssetResolver.@NotNull AssetSet set) {
        return new AssetResolver(this.storage, set, this.skinFolder, ASSET_LOCALE);
    }


    /**
     * Returns a minimap ping color without the player-color flag: {@code palette[tblink[row * 8 + 1]]}. {@code game/tblink.pcx} ({@code 152x1})
     * holds {@code 19} rows of {@code 8} indices; row {@code 16} is red (attack alerts), {@code 17} green (alerts, transmissions, trigger pings, own
     * pings) and {@code 18} yellow (allied pings).
     *
     * @param row the {@code tblink} row
     * @return the packed opaque {@code 0xRRGGBBAA} color
     * @throws IOException if {@code game/tblink.pcx} cannot be read
     */
    public int getBlinkColor(int row) throws IOException {
        byte[] colors = this.blinkColors;

        if (colors == null) {
            colors           = RenderAssets.copyIndices(this.resources.loadSpriteSheet(ScrResourceKeys.ofPcx("game/tblink.pcx")),
                                                        BLINK_ROWS * BLINK_ROW_SIZE, 1);
            this.blinkColors = colors;
        }

        return this.getPaletteColor(colors[Math.clamp(row, 0, BLINK_ROWS - 1) * BLINK_ROW_SIZE + BLINK_COLOR_ENTRY] & BYTE_MASK);
    }

    /**
     * Returns the color of a {@code game/twire.pcx} entry in the tileset palette.
     *
     * @param index the {@code twire} entry from {@code 0} through {@code 23}
     * @return the packed {@code 0xRRGGBBAA} color
     * @throws IOException if {@code twire.pcx} is missing or malformed
     */
    public int getWireframeColor(int index) throws IOException {
        byte[] table = this.wireframeColorTable;

        if (table == null) {
            table                    = RenderAssets.copyIndices(this.resources.loadSpriteSheet(ScrResourceKeys.ofPcx("game/twire.pcx")),
                                                                WIREFRAME_COLORS, 1);
            this.wireframeColorTable = table;
        }

        return this.getPaletteColor(table[Math.clamp(index, 0, WIREFRAME_COLORS - 1)] & BYTE_MASK);
    }

    /**
     * Returns the minimap color of a player color, from {@code game/tminimap.pcx} and the palette.
     *
     * @param playerColor the player color index from {@code 0} through {@code 15}
     * @return the packed {@code 0xRRGGBBAA} color
     */
    public int getMinimapPlayerColor(int playerColor) {
        return this.getPaletteColor(this.getMinimapPlayerIndex(playerColor));
    }

    /**
     * Returns the {@code game/tminimap.pcx} palette index of a player color slot.
     *
     * @param playerColor the player color index from {@code 0} through {@code 15}
     * @return the palette index
     */
    public int getMinimapPlayerIndex(int playerColor) {
        return this.minimapColors[Math.clamp(playerColor, 0, RenderAssets.REMAP_ROWS - 1)] & BYTE_MASK;
    }

    /**
     * Returns the VX4 megatile of a tile identifier: the CV5 megatile WORD of the identifier's group and variant.
     *
     * @param tileIdentifier the tile identifier
     * @return the megatile, or {@code 0} when the group is outside the tileset
     */
    public int getMegatile(int tileIdentifier) {
        int megatileOffset = TerrainTileset.getMegatileOffset(tileIdentifier);

        if (megatileOffset + Short.BYTES > this.tileGroups.length) {
            return 0;
        }

        return LittleEndianUtils.readUnsigned16(this.tileGroups, megatileOffset);
    }

    /**
     * Returns a palette index from a megatile's selected minitile, ignoring its flip bit.
     *
     * @param megatile the megatile
     * @param minitile the minitile {@code k} from {@code 0} through {@code 15}
     * @param pixel    the pixel of the {@code 8x8} minitile from {@code 0} through {@code 63}
     * @return the palette index, or {@code 0} when the tile data is missing
     */
    public int getMinitilePixel(int megatile, int minitile, int pixel) {
        if (megatile * (long) RenderAssets.VX4EX_RECORD_SIZE + minitile * (long) Integer.BYTES + Integer.BYTES > this.megatiles.length) {
            return 0;
        }

        int reference = LittleEndianUtils.readInt32(this.megatiles, megatile * RenderAssets.VX4EX_RECORD_SIZE + minitile * Integer.BYTES);
        long offset = Integer.toUnsignedLong(reference >>> MINITILE_INDEX_SHIFT) * RenderAssets.VR4_RECORD_SIZE;

        if (offset + RenderAssets.VR4_RECORD_SIZE > this.minitiles.length) {
            return 0;
        }

        return this.minitiles[(int) offset + pixel] & BYTE_MASK;
    }

    /**
     * Tests whether a palette index belongs to one of the tileset's color-cycling ranges.
     *
     * @param index the palette index
     * @return {@code true} if the index cycles, {@code false} otherwise
     */
    public boolean isCyclingIndex(int index) {
        for (int[] range : CYCLING_RANGES[Math.clamp(this.tileset, 0, CYCLING_RANGES.length - 1)]) {
            if (index >= range[0] && index <= range[1]) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns a minimap palette index with cycling colors replaced by their nearest non-cycling entry. The lazily built table retains other indices
     * and selects the first strictly smaller squared RGB distance.
     *
     * @param index the palette index
     * @return the remapped palette index
     */
    public int getMinimapRemapIndex(int index) {
        int[] remap = this.minimapRemap;

        if (remap == null) {
            remap = new int[RenderAssets.PALETTE_ENTRIES];

            for (int entry = 0; entry < RenderAssets.PALETTE_ENTRIES; entry++) {
                remap[entry] = this.isCyclingIndex(entry) ? this.findNearestNonCyclingIndex(entry) : entry;
            }

            this.minimapRemap = remap;
        }

        return remap[index & BYTE_MASK];
    }

    private int findNearestNonCyclingIndex(int entry) {
        int best = entry;
        long bestDistance = NO_DISTANCE;
        int target = entry * RenderAssets.PALETTE_ENTRY_SIZE;

        for (int index = 1; index < RenderAssets.PALETTE_ENTRIES; index++) {
            if (this.isCyclingIndex(index)) {
                continue;
            }

            int offset = index * RenderAssets.PALETTE_ENTRY_SIZE;
            int redDifference = (this.palette[offset] & BYTE_MASK) - (this.palette[target] & BYTE_MASK);
            int greenDifference = (this.palette[offset + RenderAssets.PALETTE_GREEN] & BYTE_MASK) -
                                  (this.palette[target + RenderAssets.PALETTE_GREEN] & BYTE_MASK);
            int blueDifference = (this.palette[offset + RenderAssets.PALETTE_BLUE] & BYTE_MASK) -
                                 (this.palette[target + RenderAssets.PALETTE_BLUE] & BYTE_MASK);
            long distance = redDifference * redDifference + greenDifference * greenDifference + blueDifference * blueDifference;

            if (distance < bestDistance) {
                best         = index;
                bestDistance = distance;
            }
        }

        return best;
    }

    /**
     * Returns the nearest non-cycling palette color by squared RGB distance. Indices {@code 1} through {@code 255} are searched, retaining the first
     * strictly smaller distance.
     *
     * @param rgb the target color {@code 0xRRGGBB}
     * @return the packed {@code 0xRRGGBBAA} color, opaque
     */
    public int getNearestPaletteColor(int rgb) {
        int red = rgb >>> OverlayPass.RGB_RED_SHIFT & BYTE_MASK;
        int green = rgb >>> OverlayPass.RGB_GREEN_SHIFT & BYTE_MASK;
        int blue = rgb & BYTE_MASK;
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;

        for (int index = 1; index < RenderAssets.PALETTE_ENTRIES; index++) {
            if (this.isCyclingIndex(index)) {
                continue;
            }

            int offset = index * RenderAssets.PALETTE_ENTRY_SIZE;
            int redDifference = (this.palette[offset] & BYTE_MASK) - red;
            int greenDifference = (this.palette[offset + RenderAssets.PALETTE_GREEN] & BYTE_MASK) - green;
            int blueDifference = (this.palette[offset + RenderAssets.PALETTE_BLUE] & BYTE_MASK) - blue;
            int distance = redDifference * redDifference + greenDifference * greenDifference + blueDifference * blueDifference;

            if (distance < bestDistance) {
                best         = index;
                bestDistance = distance;
            }
        }

        return this.getPaletteColor(best);
    }

    /**
     * Returns a palette entry.
     *
     * @param index the palette index
     * @return the packed opaque {@code 0xRRGGBBAA} color
     */
    public int getPaletteColor(int index) {
        int offset = (index & BYTE_MASK) * RenderAssets.PALETTE_ENTRY_SIZE;

        return (this.palette[offset] & BYTE_MASK) << InterfaceImage.RGBA_RED_SHIFT |
               (this.palette[offset + RenderAssets.PALETTE_GREEN] & BYTE_MASK) << InterfaceImage.RGBA_GREEN_SHIFT |
               (this.palette[offset + RenderAssets.PALETTE_BLUE] & BYTE_MASK) << InterfaceImage.RGBA_BLUE_SHIFT |
               InterfaceImage.OPAQUE;
    }
}
