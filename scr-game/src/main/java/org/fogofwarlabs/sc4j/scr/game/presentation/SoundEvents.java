package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Retains presentation sound events until the client consumes them, so the sounds of commands and of the frame both reach it. An event plays a
 * sound of the sound table or a sound file ({@link #addFile}) at a map position.
 * <p>
 * Lifecycle: game code fills the queue while commands and frames run; the game never clears it. The client drains and clears it after each played
 * loop iteration ({@code ClientSoundPlayback.drain}), or clears it unplayed when the session is not audible ({@code ClientSession}) and in the image
 * preview ({@link ImageScriptPreview}). Several simulated frames between two drains therefore accumulate their sounds.
 */
public final class SoundEvents {
    /**
     * The sound table identifier of a sound file event.
     */
    public static final int FILE_SOUND_ID = -1;

    /**
     * The initial number of reusable event slots.
     */
    private static final int INITIAL_CAPACITY = 16;

    /**
     * One sound event.
     */
    private static final class Event {
        private int soundId;
        private int positionX;
        private int positionY;
        private int sourceUnitType;
        private byte @Nullable [] file;

        private Event() {
            this.soundId        = 0;
            this.positionX      = 0;
            this.positionY      = 0;
            this.sourceUnitType = 0;
            this.file           = null;
        }
    }

    private final @NotNull FrameEventBuffer<Event> events;

    /**
     * Creates a {@link SoundEvents} instance with reusable event storage.
     */
    public SoundEvents() {
        this.events = FrameEventBuffer.createReusing(Event::new, INITIAL_CAPACITY);
    }

    /**
     * @return the retained event count
     */
    public int getCount() {
        return this.events.getCount();
    }

    /**
     * @param index the event position
     * @return the sound table identifier, or {@link #FILE_SOUND_ID} for a sound file
     */
    public int getSoundId(int index) {
        return this.events.get(index).soundId;
    }

    /**
     * @param index the event position
     * @return the source horizontal coordinate
     */
    public int getPositionX(int index) {
        return this.events.get(index).positionX;
    }

    /**
     * @param index the event position
     * @return the source vertical coordinate
     */
    public int getPositionY(int index) {
        return this.events.get(index).positionY;
    }

    /**
     * @param index the event position
     * @return the source unit type, or {@link ScrUnitIds#NONE} without a unit source
     */
    public int getSourceUnitType(int index) {
        return this.events.get(index).sourceUnitType;
    }

    /**
     * @param index the event position
     * @return the sound file of an event of {@link #addFile}, or {@code null} for a sound of the sound table
     */
    public byte @Nullable [] getFile(int index) {
        return this.events.get(index).file;
    }


    /**
     * Records a sound for subsequent playback.
     *
     * @param soundId        the sound table identifier
     * @param positionX      the source horizontal coordinate
     * @param positionY      the source vertical coordinate
     * @param sourceUnitType the optional source type, or {@link ScrUnitIds#NONE} for none
     */
    public void add(int soundId, int positionX, int positionY, int sourceUnitType) {
        Event event = this.events.next();

        event.soundId        = soundId;
        event.positionX      = positionX;
        event.positionY      = positionY;
        event.sourceUnitType = sourceUnitType;
        event.file           = null;
    }

    /**
     * Records a sound file for subsequent playback at a map position, without a source unit.
     *
     * @param file      the WAVE or Ogg Vorbis file, not copied
     * @param positionX the source horizontal coordinate
     * @param positionY the source vertical coordinate
     */
    public void addFile(byte @NotNull [] file, int positionX, int positionY) {
        Event event = this.events.next();

        event.soundId        = FILE_SOUND_ID;
        event.positionX      = positionX;
        event.positionY      = positionY;
        event.sourceUnitType = ScrUnitIds.NONE;
        event.file           = file;
    }

    /**
     * Clears events while retaining allocated storage for subsequent frames.
     */
    public void clear() {
        this.events.clear();
    }
}
