package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.audio.*;
import org.fogofwarlabs.sc4j.scr.client.audio.ClientAudio;
import org.fogofwarlabs.sc4j.scr.client.audio.Music;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays glue sound effects, music and cinematic audio through the client audio: glue sounds on four channels of the effects bus, on which the
 * oldest sound gives way, and cinematic audio as streams on the master bus. Data playback failures are logged.
 */
public final class GlueAudio implements AutoCloseable {
    private static final @NotNull Logger LOGGER = Logger.of(GlueAudio.class);
    /**
     * The number of glue sound channels.
     */
    private static final int CHANNEL_COUNT = 4;
    /**
     * The volume of every glue sound.
     */
    private static final int FULL_VOLUME = ClientAudio.MAXIMUM_VOLUME;
    /**
     * Glue sounds take the oldest channel and have no channel rules; they keep the centre.
     */
    private static final @NotNull SoundCue GLUE_CUE = SoundCue.of(0);
    private static final float CENTRE = 0.0f;
    /**
     * The gain of a silent sound effect group.
     */
    private static final float SILENT = 0.0f;
    /**
     * The folder of the glue sounds.
     */
    private static final @NotNull String SOUND_FOLDER = "sound/glue/";
    /**
     * The folder of the glue sounds in the SD asset set, read when {@link #SOUND_FOLDER} lacks a sound.
     */
    private static final @NotNull String SD_SOUND_FOLDER = "SD/sound/glue/";

    /**
     * The glue sounds of the generic control code.
     */
    public enum Sound {
        /**
         * The pointer enters a control.
         */
        MOUSE_OVER("mouseover.wav"),
        /**
         * A control is pressed.
         */
        MOUSE_DOWN("mousedown2.wav"),
        /**
         * A screen starts entering.
         */
        SWISH_IN("swishin.wav"),
        /**
         * A screen finishes entering.
         */
        SWISH_LOCK("swishlock.wav"),
        /**
         * A screen starts leaving.
         */
        SWISH_OUT("swishout.wav"),
        /**
         * Score values are counting.
         */
        SCORE_FILL("scorefill.wav"),
        /**
         * Score values finish counting.
         */
        SCORE_FILL_END("scorefillend.wav"),
        /**
         * The lobby start countdown advances.
         */
        CHAT_COUNTDOWN("countdown.wav"),
        /**
         * A player joins the lobby while the window is in the background.
         */
        BATTLE_NET_CLICK("bnetclick.wav");

        private final @NotNull String fileName;

        Sound(@NotNull String fileName) {
            this.fileName = fileName;
        }

        /**
         * @return the file name under {@code sound/glue}
         */
        public @NotNull String getFileName() {
            return this.fileName;
        }
    }

    private final @NotNull CascStorage storage;
    private final @NotNull Music music;

    private final @Nullable ClientAudio audio;
    /**
     * The glue sound channels, created by the first glue sound.
     */
    private @Nullable SoundChannels channels;
    private final @NotNull List<PlayingSound> sounds;
    /**
     * The open streams, closed with the glue audio.
     */
    private final @NotNull List<AudioStream> streams;

    /**
     * Creates a {@link GlueAudio} instance on the client audio.
     *
     * @param storage the installed game storage
     * @param music   the client's music
     * @param audio   the client audio, or {@code null} to play no glue sound
     */
    public GlueAudio(@NotNull CascStorage storage, @NotNull Music music, @Nullable ClientAudio audio) {
        this.storage = storage;
        this.music   = music;

        this.audio    = audio;
        this.channels = null;
        this.sounds   = new ArrayList<>();
        this.streams  = new ArrayList<>();
    }

    /**
     * Plays a glue sound at full volume when the effects bus is enabled and audible.
     *
     * @param sound the sound
     */
    public void play(@NotNull Sound sound) {
        PlayingSound playing = this.playFile(SOUND_FOLDER + sound.getFileName(), SD_SOUND_FOLDER + sound.getFileName());

        if (playing != null) {
            this.sounds.add(playing);
        }
    }

    private @Nullable PlayingSound playFile(@NotNull String path, @NotNull String fallback) {
        ClientAudio output = this.findEffectsAudio();

        if (output == null) {
            return null;
        }

        for (String candidate : new String[]{path, fallback}) {
            if (!this.storage.hasFile(candidate)) {
                continue;
            }

            try {
                return this.playContents(output, this.storage.readFile(candidate));
            } catch (IOException | RuntimeException exception) {
                LOGGER.warning("GLUE SOUND SKIPPED: " + candidate + ": " + exception.getMessage());

                return null;
            }
        }

        return null;
    }

    /**
     * Plays WAVE or Ogg Vorbis data at full volume on the effects bus. {@link #stopSounds} stops it with the other glue sounds.
     *
     * @param contents the WAVE or Ogg Vorbis file
     */
    public void playData(byte @NotNull [] contents) {
        ClientAudio output = this.findEffectsAudio();

        if (output == null) {
            return;
        }

        try {
            PlayingSound playing = this.playContents(output, contents);

            if (playing != null) {
                this.sounds.add(playing);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("GLUE SOUND SKIPPED: " + exception.getMessage());
        }
    }

    /**
     * Decodes a sound file and plays it on the glue sound channels, which own its samples; the oldest glue sound gives way when all four play.
     *
     * @param output   the client audio
     * @param contents the WAVE or Ogg Vorbis file
     * @return the playing sound, or {@code null} if it did not start
     * @throws IOException           if decoding fails
     * @throws IllegalStateException if a device operation fails
     */
    private @Nullable PlayingSound playContents(@NotNull ClientAudio output, byte @NotNull [] contents) throws IOException {
        WaveData wave = WaveData.decode(contents);
        SoundChannels current = this.channels;

        if (current == null) {
            current       = output.getMixer().createChannels(output.getEffectsBus(), CHANNEL_COUNT, new OldestChannelPolicy());
            this.channels = current;
        }

        return current.play(GLUE_CUE, output.getMixer().createBuffer(wave.toSoundClip()), true, ClientAudio.getGain(FULL_VOLUME), CENTRE);
    }

    /**
     * Stops all playing glue sounds.
     */
    public void stopSounds() {
        for (PlayingSound sound : this.sounds) {
            sound.stop();
        }

        this.sounds.clear();
    }

    /**
     * Finds the client audio for a glue sound effect.
     *
     * @return the client audio, or {@code null} without audio or while the effects bus is silent
     */
    private @Nullable ClientAudio findEffectsAudio() {
        ClientAudio output = this.audio;

        return output == null || output.getEffectsBus().getGain() == SILENT ? null : output;
    }


    /**
     * Switches to a repeating glue music track unless it is already active.
     *
     * @param track the track name, for example {@code title}
     */
    public void playMusic(@NotNull String track) {
        int trackIdentifier = Music.findTrack(track);

        if (trackIdentifier == this.music.getTrack() && this.music.isActive()) {
            return;
        }

        this.music.switchTo(trackIdentifier);
    }

    /**
     * Fades out the current music.
     */
    public void stopMusic() {
        this.music.fadeOut();
    }


    /**
     * Opens a stream on the master bus, for the audio track of a cinematic.
     *
     * @return the stream, or {@code null} without audio
     * @throws IllegalStateException if the stream's voice cannot be created
     */
    public @Nullable AudioStream openStream() {
        ClientAudio output = this.audio;

        if (output == null) {
            return null;
        }

        AudioStream stream = output.getMixer().createStream(output.getMasterBus());

        this.streams.add(stream);

        return stream;
    }

    /**
     * Queues 16-bit PCM on a stream at full volume; empty samples are ignored.
     *
     * @param stream     the stream of {@link #openStream}
     * @param samples    little-endian signed 16-bit samples, interleaved
     * @param channels   {@code 1} or {@code 2}
     * @param sampleRate the sample rate in hertz
     * @throws IllegalStateException if a device operation fails
     */
    public void queueStream(@NotNull AudioStream stream, byte @NotNull [] samples, int channels, int sampleRate) {
        if (samples.length == 0) {
            return;
        }

        stream.queue(new WaveData(channels, sampleRate, Short.SIZE, samples).toSoundClip(), ClientAudio.getGain(FULL_VOLUME));
    }

    /**
     * Stops and releases a stream.
     *
     * @param stream the stream of {@link #openStream}
     */
    public void closeStream(@NotNull AudioStream stream) {
        stream.close();
        this.streams.remove(stream);
    }

    private void closeChannels() {
        for (AudioStream stream : this.streams) {
            stream.close();
        }

        this.streams.clear();

        if (this.channels != null) {
            this.channels.close();
        }

        this.channels = null;
    }


    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code
     *         Exception}, implementers are <em>strongly</em> encouraged to
     *         declare concrete implementations of the {@code close} method to
     *         throw more specific exceptions, or to throw no exception at all
     *         if the close operation cannot fail.
     *
     *         <p>
     *         Cases where the close operation may fail require careful
     *         attention by implementers. It is strongly advised to relinquish
     *         the underlying resources and to internally <em>mark</em> the
     *         resource as closed, prior to throwing the exception. The {@code
     *         close} method is unlikely to be invoked more than once and so
     *         this ensures that the resources are released in a timely manner.
     *         Furthermore it reduces problems that could arise when the resource
     *         wraps, or is wrapped, by another resource.
     *
     *         <p>
     *         <em>Implementers of this interface are also strongly advised
     *         to not have the {@code close} method throw {@link
     *         InterruptedException}.</em>
     *
     *         <p>
     *         This exception interacts with a thread's interrupted status,
     *         and runtime misbehavior is likely to occur if an {@code
     *         InterruptedException} is {@linkplain Throwable#addSuppressed
     *         suppressed}.
     *
     *         <p>
     *         More generally, if it would cause problems for an
     *         exception to be suppressed, the {@code AutoCloseable.close}
     *         method should not throw it.
     *
     *         <p>
     *         Note that unlike the {@link Closeable#close close}
     *         method of {@link Closeable}, this {@code close} method
     *         is <em>not</em> required to be idempotent.  In other words,
     *         calling this {@code close} method more than once may have some
     *         visible side effect, unlike {@code Closeable.close} which is
     *         required to have no effect if called more than once.
     *
     *         <p>
     *         However, implementers of this interface are strongly encouraged
     *         to make their {@code close} methods idempotent.
     * @implSpec Fades the music, stops glue sounds and closes the glue sound channels and open streams; the client retains ownership of the music and
     *         the client audio.
     */
    @Override
    public void close() {
        IgnoreException.execute(
                this::stopMusic,
                this::stopSounds,
                this::closeChannels
        );
    }
}
