package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.dat.SfxdataDat;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Retains presentation sound events until the client consumes them, so the sounds of commands and of the frame both reach it. A sound id
 * names a sound of the sound table; the ids from {@value SfxdataDat#SOUND_COUNT} up name sounds of the game plugins
 * ({@link ScrGamePlugin#findSoundFile}).
 * <p>
 * Lifecycle: game code fills the queue while commands and frames run; the game never clears it. The client drains and clears it after each played
 * loop iteration ({@code ClientSoundPlayback.drain}), or clears it unplayed when the session is not audible ({@code ClientSession}) and in the image
 * preview ({@link ImageScriptPreview}). Several simulated frames between two drains therefore accumulate their sounds.
 */
public final class SoundEvents {
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

        private Event() {
            this.soundId        = 0;
            this.positionX      = 0;
            this.positionY      = 0;
            this.sourceUnitType = 0;
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
     * @return the sound id
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
     * Records a sound for subsequent playback.
     *
     * @param soundId        the sound id
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
    }

    /**
     * Clears events while retaining allocated storage for subsequent frames.
     */
    public void clear() {
        this.events.clear();
    }
}
