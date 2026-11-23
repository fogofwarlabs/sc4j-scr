package org.fogofwarlabs.sc4j.scr.client.audio;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.audio.*;
import org.fogofwarlabs.sc4j.engine.client.host.FrameTiming;
import org.fogofwarlabs.sc4j.engine.resource.audio.SoundClip;
import org.fogofwarlabs.sc4j.scr.client.world.ClientCamera;
import org.fogofwarlabs.sc4j.scr.client.world.GameViewport;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.client.world.ScrPresentation;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.dat.SfxdataDat;
import org.fogofwarlabs.sc4j.scr.data.type.ScrSoundTypeView;
import org.fogofwarlabs.sc4j.scr.game.presentation.SoundEvents;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;

/**
 * Drains simulation sound events through the client's engine mixer with the SC:R channel rules: eight prioritized sound effect channels (one sound
 * of a single-instance definition and one speech line per unit type at a time, no restart of a definition within {@code 80} ms, nothing at a
 * volume of {@code 10} or below) and the 128 channels of map sound files, on which a map sound without a free channel does not start. Unavailable-file
 * and device-failure state is retained: missing or undecodable sounds are skipped, and repeated device failures disable output without stopping
 * simulation.
 */
public final class ClientSoundPlayback implements AutoCloseable {
    /**
     * The number of channels for map sound files, the native map sound slots; a new map sound takes over none of them.
     */
    public static final int DATA_CHANNEL_COUNT = 128;

    private static final @NotNull Logger LOGGER = Logger.of(ClientSoundPlayback.class);

    /**
     * The game sound volume divisor while unit speech is muted.
     */
    private static final float MUTED_VOLUME_DIVISOR = 5.0f;

    /**
     * A game sound at this volume or less is not played.
     */
    private static final float SILENT_VOLUME = 10.0f;

    private static final int DEVICE_FAILURE_LIMIT = 8;

    private static final int UNKNOWN_LENGTH = -1;

    private static final int NO_SOURCE_UNIT_TYPE = -1;

    private static final int SCREEN_EDGE_VOLUME = 99;

    private static final int VOLUME_FALLOFF_PIXELS = 512;

    /**
     * The minimum volume of a sound file event: it fades to silence outside the screen.
     */
    private static final int FILE_MINIMUM_VOLUME = 0;

    /**
     * SC:R sounds keep the centre: their stereo position does not follow the screen.
     */
    private static final float NO_PANNING = 0.0f;
    private static final float CENTRE = 0.0f;

    private static final long MILLISECONDS_PER_SECOND = 1000L;

    /**
     * The number of simultaneous sound effect channels.
     */
    private static final int CHANNEL_COUNT = 8;

    /**
     * The minimum interval between starts of the same sound definition.
     */
    private static final long REPEAT_NANOSECONDS = 80_000_000L;

    /**
     * A sound at this volume or below does not start.
     */
    private static final int MINIMUM_VOLUME = 10;

    /**
     * The sfxdata property bit of unit speech: one such sound per unit type plays at a time.
     */
    private static final int PROPERTY_UNIT_SPEECH = 0x2;

    /**
     * The sfxdata property bit of a sound that plays at most once at a time.
     */
    private static final int PROPERTY_SINGLE_INSTANCE = 0x10;

    /**
     * The sfxdata property bit of a sound that a higher priority sound never replaces.
     */
    private static final int PROPERTY_NOT_PREEMPTED = 0x20;

    /**
     * The volume rule of positioned sounds: full volume on screen, fading over {@link #VOLUME_FALLOFF_PIXELS} outside it.
     */
    private static final @NotNull SoundPositioning POSITIONING = new SoundPositioning(SCREEN_EDGE_VOLUME, VOLUME_FALLOFF_PIXELS, NO_PANNING);

    /**
     * The cue of a map sound file: of equal priority, so a playing map sound is never replaced ({@link PriorityChannelPolicy}).
     */
    private static final @NotNull SoundCue DATA_CUE = SoundCue.of(0);

    /**
     * Receives every drained sound event with its resolved volume and whether its file is available; used by headless tests of the event lifecycle,
     * where no audio device may exist.
     */
    public interface Observer {
        /**
         * Called for one drained event.
         *
         * @param soundIdentifier the sfxdata sound
         * @param volume          the volume after the screen-distance rule, from {@code 0} to {@code 100}
         * @param available       {@code true} if the sound file resolved and could be read, {@code false} otherwise
         */
        void onSound(int soundIdentifier, int volume, boolean available);
    }

    /**
     * The SC:R channel rules of one installed sound: its sfxdata priority and property bits and the unit type that speaks it.
     */
    private static final class ScrSoundCue implements SoundCue {
        private final int soundIdentifier;
        private final int unitType;
        private final int properties;
        private final int priority;

        /**
         * Creates a {@link ScrSoundCue} instance.
         *
         * @param soundIdentifier the sound definition index
         * @param unitType        the source unit type, or {@code -1}
         * @param properties      the installed sound property bits
         * @param priority        the installed channel priority
         */
        private ScrSoundCue(int soundIdentifier, int unitType, int properties, int priority) {
            this.soundIdentifier = soundIdentifier;
            this.unitType        = unitType;
            this.properties      = properties;
            this.priority        = priority;
        }

        /**
         * @return the priority, higher is more important
         */
        @Override
        public int getPriority() {
            return this.priority;
        }

        /**
         * @return {@code true} if a sound of higher priority may replace this one while it plays, {@code false} otherwise
         */
        @Override
        public boolean isPreemptible() {
            return (this.properties & PROPERTY_NOT_PREEMPTED) == 0;
        }

        /**
         * Tells whether a playing sound keeps this sound from starting: a single-instance sound waits for its own definition, unit speech for
         * the speech of the same unit type.
         *
         * @param playing the cue of a playing sound
         * @return {@code true} if this sound must not start while that one plays, {@code false} otherwise
         */
        @Override
        public boolean conflictsWith(@NotNull SoundCue playing) {
            if (!(playing instanceof ScrSoundCue other)) {
                return false;
            }

            return (this.properties & PROPERTY_SINGLE_INSTANCE) != 0
                   ? other.soundIdentifier == this.soundIdentifier
                   : (this.properties & PROPERTY_UNIT_SPEECH) != 0 && this.unitType >= 0 && other.unitType == this.unitType &&
                     (other.properties & PROPERTY_UNIT_SPEECH) != 0;
        }
    }

    /**
     * Returns the decoded length of a WAVE or Ogg Vorbis file. Never throws.
     *
     * @param contents the sound file
     * @return the length in milliseconds, or {@code 0} if it cannot be decoded
     */
    public static int getFileLength(byte @NotNull [] contents) {
        try {
            return computeLength(WaveData.decode(contents));
        } catch (IOException | RuntimeException exception) {
            return 0;
        }
    }

    /**
     * Computes the playing time of decoded samples from their byte rate.
     *
     * @param wave the decoded samples
     * @return the length in milliseconds, or {@code 0} if the byte rate is {@code 0}
     */
    private static int computeLength(@NotNull WaveData wave) {
        long byteRate = (long) wave.getSampleRate() * wave.getChannels() * Math.max(1, wave.getSampleBits() / Byte.SIZE);

        return byteRate == 0 ? 0 : (int) (wave.getSamples().length * MILLISECONDS_PER_SECOND / byteRate);
    }

    /**
     * Resolves the sound definition's minimum volume against distance outside the viewport ({@link SoundPositioning#getVolume}).
     *
     * @param sounds          the sound definitions
     * @param soundIdentifier the sfxdata sound
     * @param horizontal      the horizontal map position of the sound; the position {@code (0, 0)} is a sound without position
     * @param vertical        the vertical map position of the sound
     * @param camera          the camera whose game view is the screen
     * @return the volume from {@code 0} to {@code 100}
     */
    private static int getVolume(@NotNull ScrSoundTypeView sounds, int soundIdentifier, int horizontal, int vertical, @NotNull ClientCamera camera) {
        int volume = sounds.getVolume(soundIdentifier);

        return getVolume(volume, volume, horizontal, vertical, camera);
    }

    /**
     * Resolves a minimum volume against distance outside the viewport ({@link SoundPositioning#getVolume}).
     *
     * @param volume        the volume of a sound without position
     * @param minimumVolume the volume the distance does not lower a sound below
     * @param horizontal    the horizontal map position of the sound; the position {@code (0, 0)} is a sound without position
     * @param vertical      the vertical map position of the sound
     * @param camera        the camera whose game view is the screen
     * @return the volume from {@code 0} to {@code 100}
     */
    private static int getVolume(int volume, int minimumVolume, int horizontal, int vertical, @NotNull ClientCamera camera) {
        if (horizontal == 0 && vertical == 0) {
            return volume;
        }

        // The screen is the game view of the client's viewport (GameViewport.getMapView).
        float[] view = camera.getViewport().getMapView(camera);

        return POSITIONING.getVolume(minimumVolume, horizontal, vertical,
                                     (int) view[GameViewport.MAP_VIEW_LEFT_INDEX], (int) view[GameViewport.MAP_VIEW_TOP_INDEX],
                                     (int) (view[GameViewport.MAP_VIEW_LEFT_INDEX] + view[GameViewport.MAP_VIEW_WIDTH_INDEX]),
                                     (int) (view[GameViewport.MAP_VIEW_TOP_INDEX] + view[GameViewport.MAP_VIEW_HEIGHT_INDEX]));
    }


    private final boolean @NotNull [] unavailable;
    private final int @NotNull [] lengths;

    private boolean enabled;
    private @Nullable Observer observer;

    private int consecutiveDeviceFailures;
    private boolean deviceFailureReported;

    /**
     * The client audio, {@code null} without output or after repeated device failures.
     */
    private @Nullable ClientAudio audio;
    private @Nullable SoundChannels effects;
    /**
     * The map sound channels, created by the first {@link #playData}.
     */
    private @Nullable SoundChannels data;
    /**
     * The last map sound file {@link #playData} decoded and its samples, which a start of the same file reuses so that the copies a trigger pass
     * starts begin together.
     */
    private byte @Nullable [] decodedContents;
    private @Nullable SoundClip decodedClip;

    /**
     * The uploaded samples of the installed sounds, kept until {@link #invalidate} or {@link #close()}.
     */
    private final @Nullable AudioBuffer @NotNull [] buffers;
    private final long @NotNull [] lastPlayed;

    /**
     * Creates a {@link ClientSoundPlayback} instance that plays through the client audio; its sound effect channels are created now.
     *
     * @param audio the client audio, or {@code null} to play nothing (sound events are still drained and observed)
     */
    public ClientSoundPlayback(@Nullable ClientAudio audio) {
        this.unavailable = new boolean[SfxdataDat.SOUND_COUNT];
        this.lengths     = new int[SfxdataDat.SOUND_COUNT];
        Arrays.fill(this.lengths, UNKNOWN_LENGTH);

        this.enabled  = true;
        this.observer = null;

        this.consecutiveDeviceFailures = 0;
        this.deviceFailureReported     = false;

        this.audio   = audio;
        this.effects = null;
        this.data    = null;

        this.decodedContents = null;
        this.decodedClip     = null;

        this.buffers    = new AudioBuffer[SfxdataDat.SOUND_COUNT];
        this.lastPlayed = new long[SfxdataDat.SOUND_COUNT];
        Arrays.fill(this.lastPlayed, Long.MIN_VALUE);

        if (audio != null) {
            try {
                this.effects = audio.getMixer().createChannels(audio.getEffectsBus(), CHANNEL_COUNT, new PriorityChannelPolicy());
            } catch (RuntimeException exception) {
                this.audio = null;
                LOGGER.warning("SOUND DISABLED: " + exception.getMessage());
            }
        }
    }

    /**
     * Sets whether new sound effects play while the event queue is still drained.
     *
     * @param enabled whether sound effects play
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Sets the observer of drained events ({@code null} for none).
     *
     * @param observer the observer
     */
    public void setObserver(@Nullable Observer observer) {
        this.observer = observer;
    }


    /**
     * Returns the length of a sound from its decoded WAVE or Ogg Vorbis data ({@link #getFileLength}). Never throws.
     *
     * @param data            the game data
     * @param soundIdentifier the sfxdata index
     * @return the length in milliseconds, or {@code 0} if unavailable
     */
    public int getLength(@NotNull GameData data, int soundIdentifier) {
        if (soundIdentifier < 0 || soundIdentifier >= this.unavailable.length || this.unavailable[soundIdentifier]) {
            return 0;
        }

        if (this.lengths[soundIdentifier] >= 0) {
            return this.lengths[soundIdentifier];
        }

        int length;

        try {
            length = getFileLength(data.readSound(soundIdentifier));
        } catch (IOException | RuntimeException exception) {
            length = 0;
        }

        this.lengths[soundIdentifier] = length;

        return length;
    }

    /**
     * Stops every playing sound of this playback (game, interface and map sounds). Never throws.
     */
    public void stopAll() {
        SoundChannels output = this.effects;

        if (output == null) {
            return;
        }

        try {
            output.stopAll();

            if (this.data != null) {
                this.data.stopAll();
            }
        } catch (RuntimeException exception) {
            this.reportDeviceFailure(exception);
        }
    }

    /**
     * Forgets every uploaded, unavailable and measured sound, for example after the sound files changed with the audio set. Never throws.
     */
    public void invalidate() {
        Arrays.fill(this.unavailable, false);
        Arrays.fill(this.lengths, UNKNOWN_LENGTH);

        SoundChannels output = this.effects;

        if (output == null) {
            return;
        }

        try {
            output.clear();
            this.releaseBuffers();
        } catch (RuntimeException exception) {
            this.reportDeviceFailure(exception);
        }
    }

    /**
     * Observes and plays the frame's sound events, then clears the queue and records sound timing in a {@code finally} block.
     *
     * @param gameView     the game view
     * @param presentation the presentation state of the game, whose sound events are drained
     * @param camera       the camera used to resolve sound positions
     * @throws NullPointerException if the game was built without game data
     */
    public void drain(@NotNull ScrGameView gameView, @NotNull ScrPresentation presentation, @NotNull ClientCamera camera) {
        SoundEvents events = presentation.getInterfaceLink().getSoundEvents();
        long start = System.nanoTime();

        try {
            GameData data = gameView.requireGameData();
            SoundChannels output = this.effects;

            if (this.observer != null) {
                this.observeEvents(this.observer, events, data, camera);
            }

            if (output != null && this.enabled && events.getCount() > 0) {
                this.playEvents(output, events, data, camera, presentation.getInterfaceLink().getLocalInterface().getUnitSpeechMuted() != 0);
            }
        } finally {
            events.clear();
            FrameTiming.addSound(System.nanoTime() - start);
        }
    }

    private void observeEvents(@NotNull Observer target, @NotNull SoundEvents events, @NotNull GameData data, @NotNull ClientCamera camera) {
        ScrSoundTypeView sounds = data.getSoundTypes();

        for (int index = 0; index < events.getCount(); index++) {
            if (events.getFile(index) != null) {
                continue;
            }

            int soundIdentifier = events.getSoundId(index);
            boolean available;

            try {
                available = soundIdentifier >= 0 && soundIdentifier < SfxdataDat.SOUND_COUNT && data.readSound(soundIdentifier).length > 0;
            } catch (IOException | RuntimeException exception) {
                available = false;
            }

            int volume = soundIdentifier >= 0 && soundIdentifier < SfxdataDat.SOUND_COUNT
                         ? getVolume(sounds, soundIdentifier, events.getPositionX(index), events.getPositionY(index), camera)
                         : 0;

            target.onSound(soundIdentifier, volume, available);
        }
    }

    /**
     * Plays frame sound events and skips sounds at or below the silence threshold; while unit speech is muted every game sound plays at a fifth
     * of its volume. Stops when repeated device failures closed the output.
     *
     * @param output          the open sound effect channels
     * @param events          the frame's sound events
     * @param data            the game data
     * @param camera          the camera used to resolve sound positions
     * @param unitSpeechMuted whether unit speech is muted
     */
    private void playEvents(@NotNull SoundChannels output,
                            @NotNull SoundEvents events,
                            @NotNull GameData data,
                            @NotNull ClientCamera camera,
                            boolean unitSpeechMuted) {
        ScrSoundTypeView sounds = data.getSoundTypes();

        for (int index = 0; index < events.getCount() && this.effects == output; index++) {
            int soundIdentifier = events.getSoundId(index);
            byte[] file = events.getFile(index);

            if (file != null) {
                this.playFile(file, events.getPositionX(index), events.getPositionY(index), camera, unitSpeechMuted);

                continue;
            }

            if (soundIdentifier < 0 || soundIdentifier >= this.unavailable.length || this.unavailable[soundIdentifier]) {
                continue;
            }

            try {
                this.load(data, soundIdentifier);
            } catch (IOException exception) {
                this.unavailable[soundIdentifier] = true;
                LOGGER.warning("SOUND " + soundIdentifier + " UNAVAILABLE (skipped): " + exception.getMessage());

                continue;
            } catch (RuntimeException exception) {
                this.reportDeviceFailure(exception);

                continue;
            }

            float scaled = getVolume(sounds, soundIdentifier, events.getPositionX(index), events.getPositionY(index), camera);

            if (unitSpeechMuted) {
                scaled /= MUTED_VOLUME_DIVISOR;
            }

            if (scaled <= SILENT_VOLUME) {
                continue;
            }

            int volume = (int) scaled;
            int sourceUnitType = events.getSourceUnitType(index);

            try {
                this.play(output,
                          soundIdentifier,
                          sourceUnitType == ScrUnitIds.NONE ? NO_SOURCE_UNIT_TYPE : sourceUnitType,
                          sounds.getFlags(soundIdentifier),
                          sounds.getPriority(soundIdentifier),
                          volume);
                this.consecutiveDeviceFailures = 0;
            } catch (RuntimeException exception) {
                this.reportDeviceFailure(exception);
            }
        }
    }

    /**
     * Plays a sound file event on the map sound channels ({@link #playData}) at the volume of its position: the volume of a positioned sound on
     * screen, fading to silence over {@link #VOLUME_FALLOFF_PIXELS} outside it, full volume without position; a fifth of it while unit speech is
     * muted, and nothing at the silence threshold or below.
     *
     * @param file            the WAVE or Ogg Vorbis file
     * @param horizontal      the horizontal map position of the sound
     * @param vertical        the vertical map position of the sound
     * @param camera          the camera used to resolve the position
     * @param unitSpeechMuted whether unit speech is muted
     */
    private void playFile(byte @NotNull [] file, int horizontal, int vertical, @NotNull ClientCamera camera, boolean unitSpeechMuted) {
        float scaled = getVolume(ClientAudio.MAXIMUM_VOLUME, FILE_MINIMUM_VOLUME, horizontal, vertical, camera);

        if (unitSpeechMuted) {
            scaled /= MUTED_VOLUME_DIVISOR;
        }

        if (scaled > SILENT_VOLUME) {
            this.playData(file, (int) scaled);
        }
    }

    /**
     * Plays an interface sound outside the simulation event queue and reports file or device failures.
     *
     * @param data            the game data
     * @param soundIdentifier the sound definition index
     * @param sourceUnitType  the unit type for channel rules, or {@code -1}
     * @param volume          the volume from {@code 0} to {@code 100}
     * @return {@code true} if a channel started, {@code false} otherwise
     */
    public boolean playInterface(@NotNull GameData data, int soundIdentifier, int sourceUnitType, int volume) {
        SoundChannels output = this.effects;

        if (output == null || !this.enabled ||
            soundIdentifier < 0 || soundIdentifier >= this.unavailable.length || this.unavailable[soundIdentifier]) {
            return false;
        }

        try {
            this.load(data, soundIdentifier);

            ScrSoundTypeView sounds = data.getSoundTypes();

            boolean started = this.play(output,
                                        soundIdentifier,
                                        sourceUnitType,
                                        sounds.getFlags(soundIdentifier),
                                        sounds.getPriority(soundIdentifier),
                                        volume);
            this.consecutiveDeviceFailures = 0;

            return started;
        } catch (IOException exception) {
            this.unavailable[soundIdentifier] = true;
            LOGGER.warning("SOUND " + soundIdentifier + " UNAVAILABLE (skipped): " + exception.getMessage());
        } catch (RuntimeException exception) {
            this.reportDeviceFailure(exception);
        }

        return false;
    }

    /**
     * Uploads one installed sound once; it is kept until {@link #invalidate} or {@link #close()}.
     *
     * @param data            the game data
     * @param soundIdentifier the sound definition index
     * @throws IOException           if the sound cannot be read or decoded
     * @throws IllegalStateException if the output is closed or the upload fails
     */
    private void load(@NotNull GameData data, int soundIdentifier) throws IOException {
        ClientAudio output = this.requireAudio();

        if (this.buffers[soundIdentifier] == null) {
            this.buffers[soundIdentifier] = output.getMixer().createBuffer(WaveData.decode(data.readSound(soundIdentifier)).toSoundClip());
        }
    }

    /**
     * Applies the repeat-interval and volume rules, then plays an uploaded sound under its channel rules ({@link ScrSoundCue}).
     *
     * @param output          the sound effect channels
     * @param soundIdentifier the uploaded sound definition index
     * @param unitType        the source unit type, or {@code -1}
     * @param properties      the installed sound property bits
     * @param priority        the installed channel priority
     * @param volume          the screen-distance volume from {@code 0} to {@code 100}
     * @return {@code true} if a channel started, {@code false} otherwise
     * @throws IllegalStateException if the output is closed, the sound samples are not uploaded or a device operation fails
     */
    private boolean play(@NotNull SoundChannels output, int soundIdentifier, int unitType, int properties, int priority, int volume) {
        ClientAudio current = this.requireAudio();
        AudioBuffer buffer = this.buffers[soundIdentifier];
        long timestamp = current.getMixer().getTime();

        if (buffer == null) {
            throw Exceptions.newIllegalStateException("Sound {0} is not uploaded.", soundIdentifier);
        }

        if (this.lastPlayed[soundIdentifier] != Long.MIN_VALUE && timestamp - this.lastPlayed[soundIdentifier] <= REPEAT_NANOSECONDS) {
            return false;
        }

        this.lastPlayed[soundIdentifier] = timestamp;

        if (volume <= MINIMUM_VOLUME) {
            return false;
        }

        ScrSoundCue cue = new ScrSoundCue(soundIdentifier, unitType, properties, priority);

        return output.play(cue, buffer, false, ClientAudio.getGain(volume), CENTRE) != null;
    }

    /**
     * Plays a WAVE or Ogg Vorbis file from outside the sound definitions (a map trigger sound) on a free map sound channel, if one is free; its
     * samples are released when the channel is reused or stopped. Never throws.
     *
     * @param contents the sound file
     * @param volume   the volume from {@code 0} to {@code 100}
     * @return the playing sound, or {@code null} if nothing started
     */
    public @Nullable PlayingSound playData(byte @NotNull [] contents, int volume) {
        ClientAudio output = this.audio;

        if (output == null || !this.enabled) {
            return null;
        }

        try {
            SoundClip clip = this.decodedClip;

            if (clip == null || !Arrays.equals(contents, this.decodedContents)) {
                clip = WaveData.decode(contents).toSoundClip();

                this.decodedContents = contents;
                this.decodedClip     = clip;
            }

            SoundChannels channels = this.data;

            if (channels == null) {
                channels  = output.getMixer().createChannels(output.getEffectsBus(), DATA_CHANNEL_COUNT, new PriorityChannelPolicy());
                this.data = channels;
            }

            AudioBuffer buffer = output.getMixer().createBuffer(clip);
            PlayingSound sound = channels.play(DATA_CUE, buffer, true, ClientAudio.getGain(volume), CENTRE);
            this.consecutiveDeviceFailures = 0;

            return sound;
        } catch (IOException exception) {
            LOGGER.warning("MAP SOUND UNDECODABLE (skipped): " + exception.getMessage());
        } catch (RuntimeException exception) {
            this.reportDeviceFailure(exception);
        }

        return null;
    }

    /**
     * Stops a sound of {@link #playData}; a finished or replaced sound does nothing. Never throws.
     *
     * @param sound the playing sound
     */
    public void stopData(@NotNull PlayingSound sound) {
        if (this.audio == null) {
            return;
        }

        try {
            sound.stop();
        } catch (RuntimeException exception) {
            this.reportDeviceFailure(exception);
        }
    }

    private @NotNull ClientAudio requireAudio() {
        ClientAudio current = this.audio;

        if (current == null) {
            throw Exceptions.newIllegalStateException("Sound output is closed.");
        }

        return current;
    }

    private void releaseBuffers() {
        for (int soundIdentifier = 0; soundIdentifier < this.buffers.length; soundIdentifier++) {
            AudioBuffer buffer = this.buffers[soundIdentifier];

            if (buffer != null) {
                buffer.release();
                this.buffers[soundIdentifier] = null;
            }

            this.lastPlayed[soundIdentifier] = Long.MIN_VALUE;
        }
    }

    private void reportDeviceFailure(@NotNull RuntimeException exception) {
        if (!this.deviceFailureReported) {
            this.deviceFailureReported = true;
            LOGGER.warning("SOUND DEVICE ERROR (sound dropped, reported once): " + exception.getMessage());
        }

        if (++this.consecutiveDeviceFailures >= DEVICE_FAILURE_LIMIT && this.audio != null) {
            LOGGER.warning("SOUND DISABLED after " + this.consecutiveDeviceFailures + " consecutive device errors.");

            this.close();
        }
    }


    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code
     * Exception}, implementers are <em>strongly</em> encouraged to
     * declare concrete implementations of the {@code close} method to
     * throw more specific exceptions, or to throw no exception at all
     * if the close operation cannot fail.
     *
     * <p>
     * Cases where the close operation may fail require careful
     * attention by implementers. It is strongly advised to relinquish
     * the underlying resources and to internally <em>mark</em> the
     * resource as closed, prior to throwing the exception. The {@code
     * close} method is unlikely to be invoked more than once and so
     * this ensures that the resources are released in a timely manner.
     * Furthermore it reduces problems that could arise when the resource
     * wraps, or is wrapped, by another resource.
     *
     * <p>
     * <em>Implementers of this interface are also strongly advised
     * to not have the {@code close} method throw {@link
     * InterruptedException}.</em>
     *
     * <p>
     * This exception interacts with a thread's interrupted status,
     * and runtime misbehavior is likely to occur if an {@code
     * InterruptedException} is {@linkplain Throwable#addSuppressed
     * suppressed}.
     *
     * <p>
     * More generally, if it would cause problems for an
     * exception to be suppressed, the {@code AutoCloseable.close}
     * method should not throw it.
     *
     * <p>
     * Note that unlike the {@link java.io.Closeable#close close}
     * method of {@link java.io.Closeable}, this {@code close} method
     * is <em>not</em> required to be idempotent.  In other words,
     * calling this {@code close} method more than once may have some
     * visible side effect, unlike {@code Closeable.close} which is
     * required to have no effect if called more than once.
     *
     * <p>
     * However, implementers of this interface are strongly encouraged
     * to make their {@code close} methods idempotent.
     * @implSpec Closes the map sound and sound effect channels, releases the uploaded sounds and stops using the client audio, which stays open.
     */
    @Override
    public void close() {
        if (this.audio == null) {
            return;
        }

        if (this.data != null) {
            this.data.close();
        }

        if (this.effects != null) {
            this.effects.close();
        }

        for (AudioBuffer buffer : this.buffers) {
            if (buffer != null) {
                buffer.release();
            }
        }

        Arrays.fill(this.buffers, null);

        this.data    = null;
        this.effects = null;
        this.audio   = null;

        this.decodedContents = null;
        this.decodedClip     = null;
    }
}
