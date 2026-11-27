package org.fogofwarlabs.sc4j.scr.client.audio;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.audio.AudioBuffer;
import org.fogofwarlabs.sc4j.engine.client.audio.MusicChannel;
import org.fogofwarlabs.sc4j.engine.client.audio.MusicPlayer;
import org.fogofwarlabs.sc4j.engine.client.audio.WaveData;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.casc.SoundPathResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Shares one music channel of the client audio across menus and games: selects the tracks of the client's track table and follows game
 * tracks; the engine {@link MusicPlayer} plays them and applies volume, ducking and timed fades on the music bus. Missing output devices or
 * tracks are logged and skipped.
 * <p>
 * The music volume follows the sound settings of the client audio ({@link #applySettings}) from its creation on, so the first track starts at
 * the saved volume.
 */
public final class Music implements AutoCloseable {
    /**
     * No selected music track.
     */
    public static final int NONE = 0;

    private static final @NotNull Logger LOGGER = Logger.of(Music.class);

    /**
     * The gain increment per fade-in period.
     */
    private static final float FADE_IN_STEP = 0.05f;
    /**
     * The gain decrement per fade-out period.
     */
    private static final float FADE_OUT_STEP = 0.1f;

    /**
     * The fade step period in milliseconds.
     */
    private static final long FADE_PERIOD_MILLISECONDS = 20;

    /**
     * The gain factor while ducked.
     */
    private static final float DUCKED_LEVEL = 0.75f;

    /**
     * The applied music gain before the sound settings are first applied.
     */
    private static final float UNAPPLIED_VOLUME = -1.0f;

    /**
     * The music folder and the extension of the table names (the loader tries {@code .ogg} first for remastered).
     */
    private static final @NotNull String TRACK_FOLDER = "music/";
    private static final @NotNull String TRACK_EXTENSION = ".wav";

    /**
     * The campaign mission outside campaigns.
     */
    private static final int NO_CAMPAIGN_MISSION = 0;
    /**
     * The last original campaign mission; its game tracks skip the fourth track.
     */
    private static final int LAST_ORIGINAL_MISSION = 0x1f;

    /**
     * The fourth game track of each race.
     */
    private static final @NotNull Set<Integer> FOURTH_TRACKS = Set.of(4, 8, 12);

    /**
     * The first game track of each race: Zerg, Terran and Protoss.
     */
    private static final int @NotNull [] RACE_TRACKS = {1, 5, 9};
    /**
     * The first original campaign mission of each race: Zerg, Terran and Protoss.
     */
    private static final int @NotNull [] ORIGINAL_CAMPAIGN_STARTS = {12, 1, 22};
    /**
     * The first expansion campaign mission of each race: Zerg, Terran and Protoss.
     */
    private static final int @NotNull [] EXPANSION_CAMPAIGN_STARTS = {49, 40, 32};

    /**
     * The number of initial race tracks used by the campaign cycle.
     */
    private static final int CAMPAIGN_TRACK_CYCLE = 3;
    /**
     * The offset selecting the fourth expansion track at cycle position {@code 0}.
     */
    private static final int EXPANSION_FOURTH_TRACK_OFFSET = 3;

    /**
     * Track records indexed by track identifier.
     */
    private static final @NotNull List<Track> TRACKS = List.of(new Track("", TrackType.NONE, false, NONE),
                                                               new Track("zerg1", TrackType.GAME, false, 2),
                                                               new Track("zerg2", TrackType.GAME, false, 3),
                                                               new Track("zerg3", TrackType.GAME, false, 4),
                                                               new Track("zerg4", TrackType.GAME, false, 1),
                                                               new Track("terran1", TrackType.GAME, false, 6),
                                                               new Track("terran2", TrackType.GAME, false, 7),
                                                               new Track("terran3", TrackType.GAME, false, 8),
                                                               new Track("terran4", TrackType.GAME, false, 5),
                                                               new Track("protoss1", TrackType.GAME, false, 10),
                                                               new Track("protoss2", TrackType.GAME, false, 11),
                                                               new Track("protoss3", TrackType.GAME, false, 12),
                                                               new Track("protoss4", TrackType.GAME, false, 9),
                                                               new Track("zrdyroom", TrackType.LOOP, true, NONE),
                                                               new Track("trdyroom", TrackType.LOOP, true, NONE),
                                                               new Track("prdyroom", TrackType.LOOP, true, NONE),
                                                               new Track("zdefeat", TrackType.LOOP, true, NONE),
                                                               new Track("tdefeat", TrackType.LOOP, false, NONE),
                                                               new Track("pdefeat", TrackType.LOOP, false, NONE),
                                                               new Track("zvict", TrackType.LOOP, true, NONE),
                                                               new Track("tvict", TrackType.LOOP, true, NONE),
                                                               new Track("pvict", TrackType.LOOP, false, NONE),
                                                               new Track("zvict", TrackType.LOOP, true, NONE),
                                                               new Track("tvict", TrackType.LOOP, true, NONE),
                                                               new Track("pvict", TrackType.LOOP, false, NONE),
                                                               new Track("title", TrackType.LOOP, false, NONE),
                                                               new Track("terran2", TrackType.LOOP, false, NONE),
                                                               new Track("RadioFreeZerg", TrackType.GAME, false, 1));

    /**
     * A music track playback mode.
     */
    private enum TrackType {
        /**
         * The empty record {@code 0}.
         */
        NONE,
        /**
         * A repeating menu track.
         */
        LOOP,
        /**
         * A game track followed by its next track when it ends.
         */
        GAME
    }

    /**
     * One record of the track table.
     */
    private static final class Track {
        private final @NotNull String name;
        private final @NotNull TrackType type;
        private final boolean fadeIn;
        private final int next;

        /**
         * Creates a {@link Track} instance.
         *
         * @param name   the asset name
         * @param type   the playback mode
         * @param fadeIn whether startup fades in
         * @param next   the following track identifier
         */
        private Track(@NotNull String name, @NotNull TrackType type, boolean fadeIn, int next) {
            this.name   = name;
            this.type   = type;
            this.fadeIn = fadeIn;
            this.next   = next;
        }
    }

    /**
     * Returns the next game track, skipping a race's fourth track in the original campaign.
     *
     * @param track           the track that ended
     * @param campaignMission the campaign mission, {@code 0} outside campaigns
     * @return the next track, or {@link #NONE} when this is not a game track
     */
    public static int getNextTrack(int track, int campaignMission) {
        if (!isGameTrack(track)) {
            return NONE;
        }

        int next = TRACKS.get(track).next;
        boolean originalCampaign = campaignMission > NO_CAMPAIGN_MISSION && campaignMission <= LAST_ORIGINAL_MISSION;

        if (originalCampaign && FOURTH_TRACKS.contains(next)) {
            next = TRACKS.get(next).next;
        }

        return next;
    }

    /**
     * Selects the race track plus rotation outside campaigns or the unsigned mission cycle position in campaigns.
     *
     * @param race            the race index: Zerg, Terran or Protoss
     * @param campaignMission the campaign mission, {@code 0} outside campaigns
     * @param rotation        the rotation from {@code 0} to {@code 3}
     * @return the track identifier
     */
    public static int getGameTrack(int race, int campaignMission, int rotation) {
        boolean knownRace = race >= 0 && race < RACE_TRACKS.length;
        int base = knownRace ? RACE_TRACKS[race] : NONE;

        if (campaignMission == NO_CAMPAIGN_MISSION) {
            return base + rotation;
        }

        if (!knownRace) {
            return base;
        }

        boolean expansion = campaignMission > LAST_ORIGINAL_MISSION;
        int distance = campaignMission - (expansion ? EXPANSION_CAMPAIGN_STARTS : ORIGINAL_CAMPAIGN_STARTS)[race];
        int offset = Integer.remainderUnsigned(distance, CAMPAIGN_TRACK_CYCLE);

        return base + offset + (expansion && offset == 0 ? EXPANSION_FOURTH_TRACK_OFFSET : 0);
    }

    /**
     * Returns the track identifier of a table name, for the glue screens that name their track.
     *
     * @param name the track name, for example {@code title} or {@code zrdyroom}
     * @return the first track identifier of that name, or {@link #NONE}
     */
    public static int findTrack(@NotNull String name) {
        for (int trackIdentifier = NONE + 1; trackIdentifier < TRACKS.size(); trackIdentifier++) {
            if (TRACKS.get(trackIdentifier).name.equalsIgnoreCase(name)) {
                return trackIdentifier;
            }
        }

        return NONE;
    }

    private static boolean isTrack(int trackIdentifier) {
        return trackIdentifier > NONE && trackIdentifier < TRACKS.size();
    }

    private static boolean isGameTrack(int trackIdentifier) {
        return isTrack(trackIdentifier) && TRACKS.get(trackIdentifier).type == TrackType.GAME;
    }

    private static @Nullable MusicChannel openChannel(@Nullable ClientAudio audio) {
        if (audio == null) {
            return null;
        }

        try {
            return audio.getMixer().createMusicChannel(audio.getMusicBus());
        } catch (RuntimeException exception) {
            LOGGER.warning("MUSIC DISABLED: " + exception.getMessage());

            return null;
        }
    }


    private final @NotNull CascStorage storage;
    private @NotNull SoundPathResolver.AudioSet audioSet;

    private final @Nullable ClientAudio audio;
    private final @NotNull MusicPlayer player;

    /**
     * The current track.
     */
    private int track;
    /**
     * The music gain of the sound settings last applied, {@link #UNAPPLIED_VOLUME} before the first.
     */
    private float appliedVolume;

    /**
     * Creates a {@link Music} instance with its music channel on the client audio, at the music volume of the audio's sound settings.
     *
     * @param storage the installed game storage
     * @param audio   the client audio, or {@code null} to play no music
     */
    public Music(@NotNull CascStorage storage, @Nullable ClientAudio audio) {
        this.storage  = storage;
        this.audioSet = SoundPathResolver.AudioSet.REMASTERED;

        this.audio  = audio;
        this.player = new MusicPlayer(openChannel(audio), audio == null ? null : audio.getMusicBus(),
                                      DUCKED_LEVEL, FADE_IN_STEP, FADE_OUT_STEP, FADE_PERIOD_MILLISECONDS);

        this.track         = NONE;
        this.appliedVolume = UNAPPLIED_VOLUME;

        this.applySettings();
    }

    /**
     * @return the current track
     */
    public int getTrack() {
        return this.track;
    }

    /**
     * @return {@code true} if music is active, including while silent, {@code false} otherwise
     */
    public boolean isActive() {
        return this.player.isActive();
    }

    /**
     * Sets the audio set used to resolve music assets.
     *
     * @param audioSet the audio set
     */
    public void setAudioSet(@NotNull SoundPathResolver.AudioSet audioSet) {
        this.audioSet = audioSet;
    }

    /**
     * Sets the volume and audibility, resets fading and applies the gain; a volume of {@code 0} stops playback, otherwise an inactive channel
     * resumes.
     *
     * @param volume  the music gain from the settings
     * @param audible whether music is audible
     */
    public void setVolume(float volume, boolean audible) {
        this.player.setVolume(volume, audible);

        if (this.player.getVolume() == 0.0f) {
            this.stop();
        } else if (!this.player.isActive()) {
            this.resume();
        }
    }

    /**
     * Applies the music gain and audibility of the client audio's sound settings ({@link #setVolume}) when the gain changed since it was last
     * applied; without client audio nothing plays and nothing is applied.
     */
    public void applySettings() {
        ClientAudio current = this.audio;

        if (current == null) {
            return;
        }

        SoundSettings sound = current.getSoundSettings();
        float volume = sound.getMusicGain();

        if (volume == this.appliedVolume) {
            return;
        }

        this.appliedVolume = volume;
        this.setVolume(volume, sound.isMusicAudible());
    }

    /**
     * Sets whether music is ducked and immediately applies the resulting gain.
     *
     * @param ducked whether music is ducked
     */
    public void setDucked(boolean ducked) {
        this.player.setDucked(ducked);
    }


    /**
     * Starts a track when the music channel is idle, applying the track's fade-in flag.
     *
     * @param trackIdentifier the track identifier
     */
    public void start(int trackIdentifier) {
        if (this.player.isPlaying()) {
            return;
        }

        this.startTrack(trackIdentifier);
    }

    /**
     * Fades out the current music before starting the requested track.
     *
     * @param trackIdentifier the track identifier
     */
    public void switchTo(int trackIdentifier) {
        this.fadeOut();
        this.start(trackIdentifier);
    }

    /**
     * Restarts the current track when the music channel is idle.
     */
    public void resume() {
        if (this.player.isPlaying()) {
            return;
        }

        this.startTrack(this.track);
    }

    private void startTrack(int trackIdentifier) {
        if (!isTrack(trackIdentifier) || !TRACKS.get(trackIdentifier).fadeIn) {
            this.play(trackIdentifier);

            return;
        }

        this.player.startFadeIn();
        this.play(trackIdentifier);
    }

    /**
     * Selects a track, stops previous playback and loads it without ducking, repeating tracks marked for looping.
     *
     * @param trackIdentifier the track identifier
     */
    public void play(int trackIdentifier) {
        if (this.player.isActive() && trackIdentifier == this.track && this.player.isPlaying()) {
            return;
        }

        this.track = trackIdentifier;
        this.stop();

        if (!isTrack(trackIdentifier)) {
            return;
        }

        Track entry = TRACKS.get(trackIdentifier);

        this.player.clearDucking();

        ClientAudio current = this.audio;

        if (!this.player.hasChannel() || current == null) {
            return;
        }

        String path = new SoundPathResolver(this.storage, "", this.audioSet).resolveAsset(TRACK_FOLDER + entry.name + TRACK_EXTENSION);

        if (path == null) {
            LOGGER.warning("MUSIC SKIPPED: " + TRACK_FOLDER + entry.name + " is not installed.");

            return;
        }

        try {
            AudioBuffer track = current.getMixer().createBuffer(WaveData.decode(this.storage.readFile(path)).toSoundClip());

            this.player.play(track, entry.type == TrackType.LOOP);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("MUSIC SKIPPED: " + path + ": " + exception.getMessage());
        }
    }

    /**
     * Stops the music channel while retaining the current track for resume.
     */
    public void stop() {
        this.player.stop();
    }

    /**
     * Ends fade-in and lowers active music by the fade-out step at each period, then stops and restores the gain.
     */
    public void fadeOut() {
        this.player.fadeOut();
    }

    /**
     * Advances timed fade-in steps and follows completed game tracks.
     *
     * @param nowMilliseconds the interface clock in milliseconds
     * @param inGame          whether a game runs
     * @param campaignMission the campaign mission, {@code 0} outside campaigns
     */
    public void update(long nowMilliseconds, boolean inGame, int campaignMission) {
        this.player.update(nowMilliseconds);

        if (!inGame || !this.player.isActive() || !isGameTrack(this.track) || this.player.isPlaying()) {
            return;
        }

        this.play(getNextTrack(this.track, campaignMission));
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
     * @implSpec Stops the music and releases its music channel; the client audio stays open.
     */
    @Override
    public void close() {
        IgnoreException.execute(
                this.player::close
        );
    }
}
