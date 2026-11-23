package org.fogofwarlabs.sc4j.scr.game.sprite;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Retains a sprite's position, visibility, pool identity and ordered image list.
 * <p>
 * A {@link Sprite} is one persistent slot of its {@link SpritePool}: the slot outlives the individual sprites, and fields that sprite initialization
 * does not write keep their values when the slot is reused. Most setters truncate a value to the field width (BYTE or WORD); {@link #setX},
 * {@link #setY} and {@link #setVisibilityFlags} store it unchanged.
 */
public final class Sprite {
    /**
     * The sprite flag of a sprite whose image list holds its solid selection circle (images {@code 561}-{@code 570}, {@link SelectionImages}).
     */
    public static final int FLAG_SELECTION_CIRCLE = 0x01;
    /**
     * The low bit of the team mark count of a sprite whose image list holds a dashed team-selection circle (images {@code 571}-{@code 580}).
     */
    public static final int FLAG_TEAM_SELECTION_CIRCLE_1 = 0x02;
    /**
     * The high bit of the team mark count of a sprite whose image list holds a dashed team-selection circle (images {@code 571}-{@code 580}).
     */
    public static final int FLAG_TEAM_SELECTION_CIRCLE_2 = 0x04;
    /**
     * The sprite flag of a sprite of the local selection, whose image list holds its health bar.
     */
    public static final int FLAG_SELECTED = 0x08;
    /**
     * The sprite flag of a turret sprite.
     */
    public static final int FLAG_TURRET = 0x10;
    /**
     * The sprite flag of a hidden sprite.
     */
    public static final int FLAG_HIDDEN = 0x20;
    /**
     * The sprite flag of a burrowed sprite.
     */
    public static final int FLAG_BURROWED = 0x40;
    /**
     * The sprite flag of an iscript inside a {@code nobrkcodestart} section.
     */
    public static final int FLAG_ISCRIPT_NO_BREAK = 0x80;
    /**
     * The lowest elevation level of a sprite in the air; a unit below it collides with ground units.
     */
    public static final int AIR_ELEVATION_LEVEL = 12;
    /**
     * The flash timer of a sprite flashed to draw attention to its unit (a transmission speaker, a rescued unit).
     */
    public static final int FLASH_DURATION = 45;
    /**
     * The size of a saved sprite record, the classic {@code 36}-byte sprite ({@link #writeSaveRecord}).
     */
    public static final int SAVE_RECORD_SIZE = 0x24;
    /**
     * The visibility flags of a sprite visible to every player: one bit per player.
     */
    public static final int VISIBLE_TO_ALL_PLAYERS = 0xFF;

    /**
     * The elevation level sprite initialization stores in a freshly allocated slot.
     */
    private static final int INITIAL_ELEVATION_LEVEL = 4;
    /**
     * The largest canvas width or height the BYTE fields hold; larger graphics are clamped to it.
     */
    private static final int MAXIMUM_CANVAS_DIMENSION = 0xFF;
    /**
     * The mask of a WORD field value.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of a BYTE field value.
     */
    private static final int BYTE_MASK = 0xFF;

    private static final int RECORD_SPRITE_TYPE = 0x08;
    private static final int RECORD_OWNER = 0x0A;
    private static final int RECORD_SELECTION_INDEX = 0x0B;
    private static final int RECORD_VISIBILITY = 0x0C;
    private static final int RECORD_ELEVATION = 0x0D;
    private static final int RECORD_FLAGS = 0x0E;
    private static final int RECORD_FLASH_TIMER = 0x0F;
    private static final int RECORD_RESERVED_WORD = 0x10;
    private static final int RECORD_WIDTH = 0x12;
    private static final int RECORD_HEIGHT = 0x13;
    private static final int RECORD_X = 0x14;
    private static final int RECORD_Y = 0x16;

    private final int poolIndex;
    private int spriteTypeId;
    private final @NotNull List<SpriteImage> images;
    private final @NotNull List<SpriteImage> imageView;

    private int x;
    private int y;
    private int width;
    private int height;
    private int elevationLevel;

    private int owner;
    private int visibilityFlags;
    private int flags;
    private int flashTimer;
    /**
     * The selection index the local selection circle code stores with {@link #FLAG_SELECTED}. Sprite initialization does not write it, so it survives
     * the reuse of the slot.
     */
    private int selectionIndex;
    /**
     * A WORD with no traced simulation writer: the pool stores the slot index in it, and the EUD emulation clears it.
     */
    private int reservedWord;

    private @Nullable SpriteImage mainImage;

    /**
     * Creates a {@link Sprite} instance for one zeroed pool slot.
     *
     * @param poolIndex the persistent storage index the owning pool assigns
     */
    public Sprite(int poolIndex) {
        this.poolIndex    = poolIndex;
        this.spriteTypeId = 0;
        this.images       = new ArrayList<>();
        this.imageView    = Collections.unmodifiableList(this.images);

        this.x              = 0;
        this.y              = 0;
        this.width          = 0;
        this.height         = 0;
        this.elevationLevel = 0;

        this.owner           = 0;
        this.visibilityFlags = 0;
        this.flags           = 0;
        this.flashTimer      = 0;
        this.selectionIndex  = 0;
        this.reservedWord    = 0;

        this.mainImage = null;
    }

    /**
     * @return the persistent storage index, assigned by its owning pool
     */
    public int getPoolIndex() {
        return this.poolIndex;
    }

    /**
     * @return the {@code sprites.dat} ID
     */
    public int getSpriteTypeId() {
        return this.spriteTypeId;
    }

    /**
     * @return the read-only live images in head-to-tail order, the selection images among them; image execution traverses forward and drawing traverses backward
     */
    public @NotNull List<SpriteImage> getImages() {
        return this.imageView;
    }

    /**
     * @return the main image, or {@code null} if the sprite has none
     */
    public @Nullable SpriteImage getMainImage() {
        return this.mainImage;
    }

    /**
     * Returns the main image of a sprite that must have one.
     *
     * @return the main image
     * @throws IllegalStateException if the sprite has no main image
     */
    public @NotNull SpriteImage requireMainImage() {
        SpriteImage image = this.mainImage;

        if (image == null) {
            throw Exceptions.newIllegalStateException("Sprite {0} has no main image.", this.getPoolIndex());
        }

        return image;
    }

    /**
     * @return the x coordinate in pixels
     */
    public int getX() {
        return this.x;
    }

    /**
     * @return the y coordinate in pixels
     */
    public int getY() {
        return this.y;
    }

    /**
     * @return the canvas width BYTE
     */
    public int getWidth() {
        return this.width;
    }

    /**
     * @return the canvas height BYTE
     */
    public int getHeight() {
        return this.height;
    }

    /**
     * @return the elevation level BYTE
     */
    public int getElevationLevel() {
        return this.elevationLevel;
    }

    /**
     * @return the owner BYTE
     */
    public int getOwner() {
        return this.owner;
    }

    /**
     * @return the visibility flags
     */
    public int getVisibilityFlags() {
        return this.visibilityFlags;
    }

    /**
     * @return the flags
     */
    public int getFlags() {
        return this.flags;
    }

    /**
     * @return the flash timer BYTE
     */
    public int getFlashTimer() {
        return this.flashTimer;
    }

    /**
     * @return the selection index BYTE
     */
    public int getSelectionIndex() {
        return this.selectionIndex;
    }

    /**
     * @return the reserved WORD
     */
    public int getReservedWord() {
        return this.reservedWord;
    }


    /**
     * Sets the sprite type, truncated to WORD, as burrowing and unburrowing do with the selection circle type.
     *
     * @param spriteTypeId the {@code sprites.dat} index
     */
    public void setSpriteTypeId(int spriteTypeId) {
        this.spriteTypeId = spriteTypeId & WORD_MASK;
    }

    /**
     * Sets the main image.
     *
     * @param mainImage the new main image, or {@code null}
     */
    public void setMainImage(@Nullable SpriteImage mainImage) {
        this.mainImage = mainImage;
    }

    /**
     * Sets the x coordinate.
     *
     * @param x the new x coordinate in pixels
     */
    public void setX(int x) {
        this.x = x;
    }

    /**
     * Sets the y coordinate.
     *
     * @param y the new y coordinate in pixels
     */
    public void setY(int y) {
        this.y = y;
    }

    /**
     * Sets the canvas width and height from the main image's graphics, each truncated to WORD and clamped to the largest BYTE value.
     *
     * @param width  the graphics width in pixels
     * @param height the graphics height in pixels
     */
    public void setNativeDimensions(int width, int height) {
        this.width  = Math.min(width & WORD_MASK, MAXIMUM_CANVAS_DIMENSION);
        this.height = Math.min(height & WORD_MASK, MAXIMUM_CANVAS_DIMENSION);
    }

    /**
     * Sets the elevation level, truncated to BYTE: one level below {@code 0} is {@code 255}.
     *
     * @param elevationLevel the new elevation level
     */
    public void setElevationLevel(int elevationLevel) {
        this.elevationLevel = elevationLevel & BYTE_MASK;
    }

    /**
     * Sets the owner, truncated to BYTE.
     *
     * @param owner the new owner player
     */
    public void setOwner(int owner) {
        this.owner = owner & BYTE_MASK;
    }

    /**
     * Sets the visibility flags.
     *
     * @param visibilityFlags the new visibility flags
     */
    public void setVisibilityFlags(int visibilityFlags) {
        this.visibilityFlags = visibilityFlags;
    }

    /**
     * Sets the flags, truncated to BYTE.
     *
     * @param flags the new flags
     */
    public void setFlags(int flags) {
        this.flags = flags & BYTE_MASK;
    }

    /**
     * Sets the flash timer, truncated to BYTE.
     *
     * @param flashTimer the new flash timer
     */
    public void setFlashTimer(int flashTimer) {
        this.flashTimer = flashTimer & BYTE_MASK;
    }

    /**
     * Sets the selection index, truncated to BYTE.
     *
     * @param selectionIndex the new selection index
     */
    public void setSelectionIndex(int selectionIndex) {
        this.selectionIndex = selectionIndex & BYTE_MASK;
    }

    /**
     * Sets the reserved WORD and the dimension BYTEs without clamping, as the EUD emulation stores them.
     *
     * @param reservedWord the reserved WORD
     * @param width        the width BYTE
     * @param height       the height BYTE
     */
    public void setRawDimensions(int reservedWord, int width, int height) {
        this.reservedWord = reservedWord & WORD_MASK;
        this.width        = width & BYTE_MASK;
        this.height       = height & BYTE_MASK;
    }


    /**
     * Initializes the sprite fields of a freshly allocated slot: the type, position and owner, the initial elevation level, and the visibility of a
     * sprite visible to every player or hidden from all of them. The selection index and the reserved WORD keep their values.
     *
     * @param spriteTypeId the {@code sprites.dat} index
     * @param x            the x coordinate in pixels, truncated to WORD
     * @param y            the y coordinate in pixels, truncated to WORD
     * @param owner        the owner player, truncated to BYTE
     * @param visible      {@code true} for a sprite visible to every player, {@code false} for a hidden one
     */
    public void initializeNativeStorage(int spriteTypeId, int x, int y, int owner, boolean visible) {
        this.spriteTypeId    = spriteTypeId & WORD_MASK;
        this.x               = x & WORD_MASK;
        this.y               = y & WORD_MASK;
        this.elevationLevel  = INITIAL_ELEVATION_LEVEL;
        this.visibilityFlags = visible ? VISIBLE_TO_ALL_PLAYERS : 0;
        this.flags           = visible ? 0 : FLAG_HIDDEN;
        this.flashTimer      = 0;
        this.owner           = owner & BYTE_MASK;
    }

    /**
     * Changes the owner, truncated to BYTE, and marks every image for redrawing. Nothing changes when the sprite already has the owner.
     *
     * @param owner the new owner player
     */
    public void updateOwner(int owner) {
        int spriteOwner = owner & BYTE_MASK;

        if (this.owner == spriteOwner) {
            return;
        }

        this.owner = spriteOwner;

        this.markImagesForRedraw();
    }

    /**
     * Marks every image of the sprite for redrawing.
     */
    public void markImagesForRedraw() {
        for (SpriteImage image : this.images) {
            image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);
        }
    }

    /**
     * Links an unowned image at the head of the list: above every image, drawn after them, or as the only image of an empty list.
     * <p>
     * Main-image identity is assigned separately by creation and retirement logic.
     *
     * @param image the detached image
     * @throws IllegalStateException if the image already belongs to a sprite
     */
    public void insertHeadImage(@NotNull SpriteImage image) {
        this.linkImage(0, image);
    }

    /**
     * Links an unowned image at the tail of the list: below every image, drawn before them, or as the only image of an empty list.
     *
     * @param image the detached image
     * @throws IllegalStateException if the image already belongs to a sprite
     */
    public void insertTailImage(@NotNull SpriteImage image) {
        this.linkImage(this.images.size(), image);
    }

    /**
     * Links an unowned image just above a reference image of this sprite, on the head side: it runs before the reference and is drawn after it.
     *
     * @param reference the attached reference image
     * @param image     the detached image
     * @throws IllegalStateException if the reference is not attached to this sprite or the image already belongs to a sprite
     */
    public void insertImageAbove(@NotNull SpriteImage reference, @NotNull SpriteImage image) {
        this.linkImage(this.requireImageIndex(reference), image);
    }

    /**
     * Links an unowned image just below a reference image of this sprite, on the tail side: it runs after the reference and is drawn before it.
     *
     * @param reference the attached reference image
     * @param image     the detached image
     * @throws IllegalStateException if the reference is not attached to this sprite or the image already belongs to a sprite
     */
    public void insertImageBelow(@NotNull SpriteImage reference, @NotNull SpriteImage image) {
        this.linkImage(this.requireImageIndex(reference) + 1, image);
    }

    /**
     * Finds the list position of an attached image.
     *
     * @param image the image
     * @return the position from the head
     * @throws IllegalStateException if the image is not attached to this sprite
     */
    private int requireImageIndex(@NotNull SpriteImage image) {
        if (image.getSprite() != this) {
            throw Exceptions.newIllegalStateException("The reference image is not attached to this sprite.");
        }

        return this.images.indexOf(image);
    }

    /**
     * Links an unowned image at a list position found from the head, the tail or a reference image.
     *
     * @param position the position from the head
     * @param image    the detached image
     * @throws IllegalStateException if the image already belongs to a sprite
     */
    private void linkImage(int position, @NotNull SpriteImage image) {
        if (image.getSprite() != null) {
            throw Exceptions.newIllegalStateException("The image already belongs to a sprite.");
        }

        this.images.add(position, image);
        image.setSprite(this);
    }

    /**
     * Unlinks a list member without changing the independently retained main-image reference.
     *
     * @param image the image to detach
     * @return {@code true} if the image was attached to this sprite, {@code false} otherwise
     */
    public boolean removeImage(@NotNull SpriteImage image) {
        if (!this.images.remove(image)) {
            return false;
        }

        image.setSprite(null);

        return true;
    }

    /**
     * Detaches all images while retaining the main-image reference until its caller replaces it.
     */
    public void clearImages() {
        for (SpriteImage image : this.images) {
            image.setSprite(null);
        }

        this.images.clear();
    }

    /**
     * Attaches a saved sprite's images, read from the image pool section, in list order from the head to the tail, with the primary image.
     *
     * @param images    the images from head to tail
     * @param mainImage the primary image, or {@code null}
     */
    public void restoreSavedImages(@NotNull List<SpriteImage> images, @Nullable SpriteImage mainImage) {
        this.images.clear();

        for (SpriteImage image : images) {
            image.setSprite(this);
            this.images.add(image);
        }

        this.mainImage = mainImage;
    }

    /**
     * Writes the sprite's fields into its saved record, the classic {@code 36}-byte sprite of the sprite pool section. The row links and the image
     * references belong to the pool section.
     *
     * @param record the record, little-endian, at offset {@code 0}
     */
    public void writeSaveRecord(@NotNull ByteBuffer record) {
        record.putShort(RECORD_SPRITE_TYPE, (short) this.spriteTypeId);
        record.put(RECORD_OWNER, (byte) this.owner);
        record.put(RECORD_SELECTION_INDEX, (byte) this.selectionIndex);
        record.put(RECORD_VISIBILITY, (byte) this.visibilityFlags);
        record.put(RECORD_ELEVATION, (byte) this.elevationLevel);
        record.put(RECORD_FLAGS, (byte) this.flags);
        record.put(RECORD_FLASH_TIMER, (byte) this.flashTimer);
        record.putShort(RECORD_RESERVED_WORD, (short) this.reservedWord);
        record.put(RECORD_WIDTH, (byte) this.width);
        record.put(RECORD_HEIGHT, (byte) this.height);
        record.putShort(RECORD_X, (short) this.x);
        record.putShort(RECORD_Y, (short) this.y);
    }

    /**
     * Reads the sprite's fields from its saved record ({@link #writeSaveRecord}), as the sprite pool reader restores a saved sprite into a cleared
     * slot.
     *
     * @param record the record, little-endian, at offset {@code 0}
     */
    public void readSaveRecord(@NotNull ByteBuffer record) {
        this.spriteTypeId    = record.getShort(RECORD_SPRITE_TYPE) & WORD_MASK;
        this.owner           = record.get(RECORD_OWNER) & BYTE_MASK;
        this.selectionIndex  = record.get(RECORD_SELECTION_INDEX) & BYTE_MASK;
        this.visibilityFlags = record.get(RECORD_VISIBILITY) & BYTE_MASK;
        this.elevationLevel  = record.get(RECORD_ELEVATION) & BYTE_MASK;
        this.flags           = record.get(RECORD_FLAGS) & BYTE_MASK;
        this.flashTimer      = record.get(RECORD_FLASH_TIMER) & BYTE_MASK;
        this.reservedWord    = record.getShort(RECORD_RESERVED_WORD) & WORD_MASK;
        this.width           = record.get(RECORD_WIDTH) & BYTE_MASK;
        this.height          = record.get(RECORD_HEIGHT) & BYTE_MASK;
        this.x               = record.getShort(RECORD_X) & WORD_MASK;
        this.y               = record.getShort(RECORD_Y) & WORD_MASK;
    }

    /**
     * Zeroes the slot, as a saved game's sprite pool reader clears the array.
     */
    void clearStorage() {
        this.spriteTypeId = 0;
        this.images.clear();

        this.x              = 0;
        this.y              = 0;
        this.width          = 0;
        this.height         = 0;
        this.elevationLevel = 0;

        this.owner           = 0;
        this.visibilityFlags = 0;
        this.flags           = 0;
        this.flashTimer      = 0;
        this.selectionIndex  = 0;
        this.reservedWord    = 0;

        this.mainImage = null;
    }
}
