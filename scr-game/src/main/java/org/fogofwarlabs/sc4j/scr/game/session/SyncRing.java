package org.fogofwarlabs.sc4j.scr.game.session;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Retains the Sync records of a game and their diagnostic hash inputs in a fixed ring: in a multiplayer session each record
 * keeps the state it hashed ({@link SyncState}), for the mismatch report and the desync dump.
 * <p>
 * Each record combines the current state hash with drawing samples; the outgoing turn sends the newest record.
 * Drawing values are sampled before the frame, and its state hash is computed after commands execute. The first drawn row,
 * the visibility block and its fold are those of the first drawing after the previous turn step ({@link #updateDrawing}):
 * below 24 turns per second the drawings of frames without a turn step leave them.
 * <p>
 * Only a multiplayer session alternates the unit and resource kinds and takes drawing values. Outside one (single player
 * and replay playback) every record has kind {@code 0}, whose hash is the record index, and its drawing values stay clear.
 */
public final class SyncRing {
    /**
     * The records in the ring.
     */
    public static final int RECORDS = 16;

    /**
     * The record kind outside a multiplayer session, whose hash is the record index.
     */
    private static final int KIND_NONE = 0;
    private static final int KIND_UNITS = 1;
    private static final int KIND_RESOURCES = 2;
    private static final int KIND_COUNT = 2;
    private static final int FIRST_ROW_MARGIN = 4;
    /**
     * The records made at load, before the first drawing.
     */
    private static final int LOAD_RECORDS = 3;

    private static final int REPORTED_UNITS = 48;

    private static final int KIND_INDEX_BYTE = 1;
    private static final int RECORD_INDEX_SHIFT = 4;
    private static final int KIND_MASK = 0xF;
    private static final int HASH_BYTE = 2;
    private static final int VISIBILITY_FOLD_BYTE = 4;
    private static final int FIRST_ROW_BYTE = 5;
    private static final int MINIMAP_COUNT_BYTE = 6;

    private static final int BYTE_MASK = 0xFF;

    private final int localSlot;
    private final boolean multiplayer;

    private final int @NotNull [] words;
    private final int @NotNull [] kinds;
    private final int @NotNull [] folds;
    private final int @NotNull [] rows;
    private final int @NotNull [] counts;
    private final int @NotNull [] parities;
    private final int @NotNull [] @NotNull [] visibility;

    /**
     * Diagnostics: the frame each record was made in, its kind hash and tile row hash, and in a multiplayer session the state it
     * hashed ({@link #describe}, {@link #createDump}).
     */
    private final long @NotNull [] frames;
    private final int @NotNull [] kindHashes;
    private final int @NotNull [] rowHashes;
    private final @NotNull SyncState @NotNull [] states;

    private boolean @NotNull [] checkedSprites;
    private int @NotNull [] sampledVisibility;

    /**
     * Whether a turn step ran since the last drawing values were taken, so that the next drawing takes them.
     */
    private boolean drawingDue;
    private int drawnRow;
    private int drawnFold;
    private int @NotNull [] drawnVisibility;

    private int updates;
    private boolean sampled;
    private int sampledFold;
    private int sampledRow;
    private int sampledCount;
    private int sampledParity;
    private int previousCount;
    private int previousParity;

    /**
     * Creates an empty {@link SyncRing} instance.
     *
     * @param localSlot   the local player's slot
     * @param multiplayer whether the game runs in a multiplayer session, whose records alternate the unit and resource kinds and take drawing values
     */
    public SyncRing(int localSlot, boolean multiplayer) {
        this.localSlot   = localSlot;
        this.multiplayer = multiplayer;

        this.words      = new int[RECORDS];
        this.kinds      = new int[RECORDS];
        this.folds      = new int[RECORDS];
        this.rows       = new int[RECORDS];
        this.counts     = new int[RECORDS];
        this.parities   = new int[RECORDS];
        this.visibility = new int[RECORDS][];

        this.frames     = new long[RECORDS];
        this.kindHashes = new int[RECORDS];
        this.rowHashes  = new int[RECORDS];
        this.states     = new SyncState[RECORDS];

        this.checkedSprites    = new boolean[0];
        this.sampledVisibility = new int[SyncChecksumUtils.SPRITE_ROWS];

        for (int index = 0; index < RECORDS; index++) {
            this.visibility[index] = new int[SyncChecksumUtils.SPRITE_ROWS];
            this.states[index]     = new SyncState();
        }

        this.drawingDue      = false;
        this.drawnRow        = 0;
        this.drawnFold       = 0;
        this.drawnVisibility = new int[SyncChecksumUtils.SPRITE_ROWS];

        this.updates        = 0;
        this.sampled        = false;
        this.sampledFold    = 0;
        this.sampledRow     = 0;
        this.sampledCount   = 0;
        this.sampledParity  = 0;
        this.previousCount  = 0;
        this.previousParity = 0;
    }

    /**
     * @return the number of records made
     */
    public int getUpdates() {
        return this.updates;
    }

    /**
     * @return {@code true} if the drawing values of the next record were sampled and the record is not yet made, {@code false} otherwise
     */
    public boolean isSampled() {
        return this.sampled;
    }


    /**
     * Takes the drawing values of a frame's drawing when it is the first after a turn step, from the state the frame starts
     * from: the first drawn row, the visibility block and its fold. A drawing that follows no new turn step keeps the values
     * of the previous one.
     *
     * @param game      the game state
     * @param cameraRow the camera's top tile row
     */
    public void updateDrawing(@NotNull Game game, int cameraRow) {
        if (!this.drawingDue) {
            return;
        }

        if (this.checkedSprites.length == 0) {
            this.checkedSprites = SyncChecksumUtils.createCheckedSpriteTypes(game);
        }

        int firstRow = Math.max(0, cameraRow - FIRST_ROW_MARGIN);
        int[] block = SyncChecksumUtils.calculateVisibilityRows(game, this.checkedSprites);

        this.drawingDue      = false;
        this.drawnRow        = firstRow & BYTE_MASK;
        this.drawnFold       = SyncChecksumUtils.calculateVisibilityFold(block, firstRow, 1 << this.localSlot);
        this.drawnVisibility = block;
    }

    /**
     * Takes the drawing's values for the next record in a turn step: the first drawn row, the visibility block and its fold
     * of the first drawing after the previous turn step ({@link #updateDrawing}), and the parity and minimap count of the
     * drawing before the frame's. The records made at load find them clear. The turn step makes the next drawing take new
     * drawing values. Outside a multiplayer session the turn step takes none: the drawing values stay clear.
     *
     * @param game the game state
     * @throws IllegalStateException if the values sampled for the next record were not used yet
     */
    public void sampleDrawing(@NotNull Game game) {
        if (this.sampled) {
            throw Exceptions.newIllegalStateException("The Sync record of the previous frame was not made; its commands-executed call is missing.");
        }

        if (!this.multiplayer) {
            this.sampled = true;

            return;
        }

        boolean drawn = this.updates + 1 > LOAD_RECORDS;

        this.sampledRow        = drawn ? this.drawnRow : 0;
        this.sampledFold       = drawn ? this.drawnFold : 0;
        this.sampledVisibility = drawn ? this.drawnVisibility : new int[SyncChecksumUtils.SPRITE_ROWS];
        this.sampledCount      = drawn ? this.previousCount : 0;
        this.sampledParity     = drawn ? this.previousParity : 0;

        this.previousCount  = SyncChecksumUtils.countMinimapUnits(game, this.localSlot) & BYTE_MASK;
        this.previousParity = SyncChecksumUtils.calculateUnitVisibilityParity(game);
        this.sampled        = true;
        this.drawingDue     = true;
    }

    /**
     * Makes the next record: the WORD of its kind from the current state and the drawing values of the last
     * {@link #sampleDrawing}. Outside a multiplayer session the kind is {@code 0} and its hash the record index.
     *
     * @param game the game state
     * @throws IllegalStateException if the drawing values were not sampled for this record
     */
    public void update(@NotNull Game game) {
        if (!this.sampled) {
            throw Exceptions.newIllegalStateException("The Sync record's drawing values were not sampled.");
        }

        this.sampled = false;
        this.updates++;

        int index = this.updates % RECORDS;
        int kind = KIND_NONE;

        if (this.multiplayer) {
            kind = this.updates % KIND_COUNT == 1 ? KIND_RESOURCES : KIND_UNITS;
        }

        int hash = switch (kind) {
            case KIND_UNITS -> SyncChecksumUtils.calculateUnitHash(game);
            case KIND_RESOURCES -> SyncChecksumUtils.calculateResourceHash(game);
            default -> index;
        };

        int rowHash = SyncChecksumUtils.calculateTileRowHash(game.getMap(), this.updates - 1);
        int slotMasks = SyncChecksumUtils.calculateSlotMasks(game.getLocalVision().getComparisonMask(), this.localSlot);

        this.words[index]      = SyncChecksumUtils.calculateRecordWord(hash, rowHash, slotMasks);
        this.kinds[index]      = kind;
        this.rows[index]       = this.sampledRow;
        this.folds[index]      = this.sampledFold;
        this.counts[index]     = this.sampledCount;
        this.parities[index]   = this.sampledParity;
        this.visibility[index] = this.sampledVisibility;
        this.frames[index]     = game.getCurrentFrame();
        this.kindHashes[index] = hash;
        this.rowHashes[index]  = rowHash;

        // Only a multiplayer session checks records, so only it keeps their state.
        if (kind != KIND_NONE) {
            this.states[index].capture(game, this.updates - 1, this.sampledVisibility);
        }
    }

    /**
     * Describes the record a Sync command names, for a mismatch report: its kind, the frame it was made in and its hash
     * inputs (kind 1: up to {@value #REPORTED_UNITS} units as {@code index:owner:type s e x,y hp}; kind 2: minerals/gas of
     * players 0..11, the generator state and the frame; kind 0 keeps none).
     *
     * @param sync the seven command bytes
     * @return the description
     */
    public @NotNull String describe(byte @NotNull [] sync) {
        int index = (sync[KIND_INDEX_BYTE] & BYTE_MASK) >>> RECORD_INDEX_SHIFT;
        SyncState state = this.states[index];

        StringBuilder text = new StringBuilder(String.format("record %d kind %d made in frame %d (sent kind %d)", index, this.kinds[index],
                                                             this.frames[index], sync[KIND_INDEX_BYTE] & KIND_MASK));

        if (!state.isCaptured()) {
            return text.toString();
        }

        if (this.kinds[index] == KIND_UNITS) {
            state.describeUnits(text, REPORTED_UNITS);
        } else {
            state.describeResources(text);
        }

        return text.toString();
    }

    /**
     * Creates the desync dump of the record a Sync command names: the record's values, a copy of the state it hashed and a full
     * capture of the current state. The copies are taken now, so the dump can be formatted on another thread.
     *
     * @param sync the seven command bytes
     * @param game the game state
     * @return the dump
     * @throws NullPointerException if the world was built without game data
     */
    @NotNull DesyncDump createDump(byte @NotNull [] sync, @NotNull Game game) {
        int index = (sync[KIND_INDEX_BYTE] & BYTE_MASK) >>> RECORD_INDEX_SHIFT;
        int comparisonMask = game.getLocalVision().getComparisonMask();
        StringBuilder record = new StringBuilder();

        SyncState.appendLine(record, "record index %d update %d kind %d frame %d", index, this.findUpdate(index), this.kinds[index],
                             this.frames[index]);
        SyncState.appendLine(record, "record word 0x%04X kind hash 0x%08X tile row hash 0x%08X", this.words[index], this.kindHashes[index],
                             this.rowHashes[index]);
        SyncState.appendLine(record, "record drawing first row %d fold 0x%02X minimap count %d parity 0x%02X", this.rows[index],
                             this.folds[index], this.counts[index], this.parities[index]);
        SyncState.appendLine(record, "record local slot %d comparison mask 0x%08X slot masks 0x%08X", this.localSlot, comparisonMask,
                             SyncChecksumUtils.calculateSlotMasks(comparisonMask, this.localSlot));

        if (this.checkedSprites.length == 0) {
            this.checkedSprites = SyncChecksumUtils.createCheckedSpriteTypes(game);
        }

        SyncState current = new SyncState();

        current.captureCurrent(game, this.checkedSprites);

        return new DesyncDump(record.toString(), this.states[index].copy(), current);
    }

    /**
     * Creates the Sync command of the newest record.
     *
     * @return the seven command bytes
     */
    public byte @NotNull [] createCommand() {
        int index = this.updates % RECORDS;
        int word = this.words[index];

        return new byte[]{ReplayCommandIds.SYNC, (byte) (this.kinds[index] | index << RECORD_INDEX_SHIFT), (byte) word,
                          (byte) (word >>> Byte.SIZE), (byte) this.folds[index], (byte) this.rows[index], (byte) this.counts[index]};
    }

    /**
     * Checks a received Sync command against the named record using the sender's slot: minimap count parity,
     * visibility fold from the sent first row, state hash WORD and record kind.
     *
     * @param sync       the seven command bytes
     * @param senderSlot the sender's slot
     * @return {@code null} if the command matches, otherwise the failed checks with the local record's values
     */
    public @Nullable String check(byte @NotNull [] sync, int senderSlot) {
        int index = (sync[KIND_INDEX_BYTE] & BYTE_MASK) >>> RECORD_INDEX_SHIFT;
        int kind = sync[KIND_INDEX_BYTE] & KIND_MASK;
        int word = LittleEndianUtils.readUnsigned16(sync, HASH_BYTE);
        int fold = sync[VISIBILITY_FOLD_BYTE] & BYTE_MASK;
        int firstRow = sync[FIRST_ROW_BYTE] & BYTE_MASK;
        int count = sync[MINIMAP_COUNT_BYTE] & BYTE_MASK;

        StringBuilder failed = new StringBuilder();

        if (((this.parities[index] >>> senderSlot ^ count) & 1) != 0) {
            failed.append(String.format(" parity 0x%02X (count bit %d)", this.parities[index], count & 1));
        }

        int expectedFold = SyncChecksumUtils.calculateVisibilityFold(this.visibility[index], firstRow, 1 << senderSlot);

        if (expectedFold != fold) {
            failed.append(String.format(" fold 0x%02X", expectedFold));
        }

        if (this.words[index] != word) {
            failed.append(String.format(" word 0x%04X", this.words[index]));
        }

        if (this.kinds[index] != kind) {
            failed.append(" kind ").append(this.kinds[index]);
        }

        return failed.isEmpty() ? null : String.format("record %d (update %d):%s", index, this.findUpdate(index), failed);
    }

    /**
     * Finds the update that made the record at an index: the newest one that maps to it.
     *
     * @param index the record index
     * @return the update number
     */
    private int findUpdate(int index) {
        return this.updates - ((this.updates - index) % RECORDS + RECORDS) % RECORDS;
    }
}
