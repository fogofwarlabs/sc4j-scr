package org.fogofwarlabs.sc4j.scr.client.audio;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.audio.AudioBus;
import org.fogofwarlabs.sc4j.engine.client.audio.AudioDevice;
import org.fogofwarlabs.sc4j.engine.client.audio.AudioMixer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the client's engine {@link AudioMixer} with the SC:R mixer groups: the master bus (every sound; cinematic audio plays on it alone), the
 * music bus and the sound effects bus (game, interface, glue and trigger sounds). Percentage volumes become linear gains within a group.
 * <p>
 * The group gains follow the client's {@link SoundSettings} from the start: the audio opens at the saved sound effect gain and the music
 * created on it at the saved music volume, before any sound plays.
 */
public final class ClientAudio implements AutoCloseable {
    /**
     * The highest volume of a sound; it plays at gain {@code 1}.
     */
    public static final int MAXIMUM_VOLUME = 100;

    private static final @NotNull Logger LOGGER = Logger.of(ClientAudio.class);

    /**
     * Returns the gain a sound of a volume plays at within its group.
     *
     * @param volume the volume from {@code 0} to {@code 100}
     * @return the linear gain {@code volume / 100}
     */
    public static float getGain(int volume) {
        return Math.clamp(volume, 0, MAXIMUM_VOLUME) / (float) MAXIMUM_VOLUME;
    }

    /**
     * Opens the system's default output through the engine default device ({@link AudioDevice#openDefault}); without a usable device the client
     * plays no sound.
     *
     * @param sound the client's sound settings
     * @return the created {@link ClientAudio} instance, or {@code null} if no output device opens
     */
    public static @Nullable ClientAudio open(@NotNull SoundSettings sound) {
        try {
            return new ClientAudio(new AudioMixer(AudioDevice.openDefault()), sound);
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.warning("SOUND DISABLED: " + exception.getMessage());

            return null;
        }
    }


    private final @NotNull AudioMixer mixer;
    private final @NotNull SoundSettings sound;
    private final @NotNull AudioBus musicBus;
    private final @NotNull AudioBus effectsBus;

    /**
     * Creates a {@link ClientAudio} instance with the music and sound effect buses on a mixer, which it closes when it closes, and applies the
     * sound settings to them.
     *
     * @param mixer the mixer
     * @param sound the client's sound settings, which the group gains follow
     */
    public ClientAudio(@NotNull AudioMixer mixer, @NotNull SoundSettings sound) {
        this.mixer      = mixer;
        this.sound      = sound;
        this.musicBus   = mixer.createBus();
        this.effectsBus = mixer.createBus();

        this.applySettings();
    }

    /**
     * @return the mixer
     */
    public @NotNull AudioMixer getMixer() {
        return this.mixer;
    }

    /**
     * @return the client's sound settings, which the group gains follow
     */
    public @NotNull SoundSettings getSoundSettings() {
        return this.sound;
    }

    /**
     * @return the master bus: every sound, and alone the cinematic audio
     */
    public @NotNull AudioBus getMasterBus() {
        return this.mixer.getMasterBus();
    }

    /**
     * @return the music bus
     */
    public @NotNull AudioBus getMusicBus() {
        return this.musicBus;
    }

    /**
     * @return the sound effects bus: game, interface, glue and trigger sounds
     */
    public @NotNull AudioBus getEffectsBus() {
        return this.effectsBus;
    }


    /**
     * Applies the sound effect gain of the sound settings to the sound effects bus; the music applies the music volume itself
     * ({@link Music#applySettings}).
     */
    public void applySettings() {
        this.effectsBus.setGain(this.sound.getSoundEffectsGain());
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
     *         Note that unlike the {@link java.io.Closeable#close close}
     *         method of {@link java.io.Closeable}, this {@code close} method
     *         is <em>not</em> required to be idempotent.  In other words,
     *         calling this {@code close} method more than once may have some
     *         visible side effect, unlike {@code Closeable.close} which is
     *         required to have no effect if called more than once.
     *
     *         <p>
     *         However, implementers of this interface are strongly encouraged
     *         to make their {@code close} methods idempotent.
     * @implSpec Closes the mixer: every channel, music channel and stream, then the device.
     */
    @Override
    public void close() {
        this.mixer.close();
    }
}
