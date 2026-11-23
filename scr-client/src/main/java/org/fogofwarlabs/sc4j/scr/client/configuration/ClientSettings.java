package org.fogofwarlabs.sc4j.scr.client.configuration;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.engine.client.settings.SettingsGroup;
import org.fogofwarlabs.sc4j.engine.client.settings.SettingsStore;
import org.fogofwarlabs.sc4j.scr.client.ReplayPlayback;
import org.fogofwarlabs.sc4j.scr.client.audio.SoundSettings;
import org.fogofwarlabs.sc4j.scr.client.ui.input.HotkeyBindingsUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.input.HotkeyProfile;
import org.fogofwarlabs.sc4j.scr.data.format.casc.SoundPathResolver;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.render.hd.HdRenderAssets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads the client's settings file through the engine {@link SettingsStore} and applies only present values to the live option objects: the SC:R
 * keys, their validation and the unknown keys it reports. Launch options take precedence. The client writes chosen values with
 * {@link SettingsStore#write}, which keeps unknown groups and keys.
 */
public final class ClientSettings {
    /**
     * The settings key of the original aspect ratio option, which keeps the {@code 4:3} game screen and also sets the glue width.
     */
    public static final @NotNull String ORIGINAL_ASPECT_RATIO_KEY = "graphics.originalAspectRatio";

    private static final @NotNull String NO_HOTKEYS_CHOICE = "$nohotkeys";

    /**
     * Finds the settings file of an install layout.
     *
     * @param layout the install layout
     * @return the layout's settings file when it exists, or {@code null} otherwise
     */
    static @Nullable Path findDefault(@NotNull InstallLayout layout) {
        Path path = layout.getSettingsFile();

        return Files.isRegularFile(path) ? path : null;
    }

    /**
     * Creates a {@link ClientSettings} instance from the values present in a settings file.
     *
     * @param path the settings file
     * @return the created {@link ClientSettings} instance
     * @throws IOException              if the file cannot be read or is not a JSON object
     * @throws IllegalArgumentException if a known key has an invalid value
     */
    static @NotNull ClientSettings load(@NotNull Path path) throws IOException {
        SettingsStore store = SettingsStore.read(path);
        ClientSettings settings = new ClientSettings();

        for (String groupName : store.getGroupNames()) {
            SettingsGroup group = store.getGroup(groupName);

            if (group == null) {
                settings.ignored.add(groupName);

                continue;
            }

            for (String key : group.getKeys()) {
                settings.read(group, key);
            }
        }

        return settings;
    }

    /**
     * Parses a scroll-speed settings value.
     *
     * @param name  the settings key reported for invalid input, as {@code group.key}
     * @param value the supplied value
     * @return the validated scroll-speed index
     * @throws IllegalArgumentException if the value has an unsupported type or range
     */
    private static int parseScrollSpeed(@NotNull String name, @Nullable Object value) {
        if (value instanceof Long number && number >= InterfaceSettings.MINIMUM_SCROLL_SPEED && number <= InterfaceSettings.MAXIMUM_SCROLL_SPEED) {
            return number.intValue();
        }

        throw Exceptions.newIllegalArgumentException("{0} must be a scroll speed from {1} to {2}.",
                                                     name,
                                                     InterfaceSettings.MINIMUM_SCROLL_SPEED,
                                                     InterfaceSettings.MAXIMUM_SCROLL_SPEED);
    }

    /**
     * Parses a sound-volume settings value.
     *
     * @param name  the settings key reported for invalid input, as {@code group.key}
     * @param value the supplied value
     * @return the validated volume
     * @throws IllegalArgumentException if the value has an unsupported type or range
     */
    private static int parseVolume(@NotNull String name, @Nullable Object value) {
        if (value instanceof Long number && number >= 0 && number <= SoundSettings.MAXIMUM_VOLUME) {
            return number.intValue();
        }

        throw Exceptions.newIllegalArgumentException("{0} must be a volume from 0 to {1}.", name, SoundSettings.MAXIMUM_VOLUME);
    }

    private @Nullable String renderer;
    private @Nullable HdRenderAssets.Quality rendererQuality;
    private @Nullable GraphicsSettings.Vsync vsync;
    private @Nullable Boolean frameRateLimitEnabled;
    private @Nullable Boolean smoothTurning;
    private @Nullable Boolean shadowStacking;
    private @Nullable Boolean environmentEffects;
    private @Nullable Integer sdFilterMode;
    private @Nullable Integer windowMode;
    private @Nullable Boolean originalAspectRatio;
    private @Nullable Integer gamma;
    private @Nullable Integer frameRateLimit;
    private @Nullable String hdSkin;
    private boolean hdSkinSet;
    private @Nullable Boolean hdLighting;

    private @Nullable SoundPathResolver.AudioSet audioSet;
    private @Nullable Integer musicVolume;
    private @Nullable Boolean musicEnabled;

    private @Nullable Integer soundEffectsVolume;
    private @Nullable Boolean soundEffectsEnabled;

    private @Nullable Boolean unitSpeech;
    private @Nullable Boolean unitAcknowledgments;
    private @Nullable Boolean buildingSounds;
    private @Nullable Boolean soundInBackground;
    private @Nullable Boolean gameSubtitles;
    private @Nullable Boolean cinematicSubtitles;
    private @Nullable Boolean originalUnitVoices;
    private @Nullable Boolean originalCampaign;

    private @Nullable Boolean mouseConfine;
    private @Nullable Integer keyScroll;
    private @Nullable Integer mouseScroll;
    private @Nullable Boolean frameRateShown;
    private @Nullable Boolean turnRateShown;
    private @Nullable Boolean gameTimer;
    private @Nullable Integer tipNumber;
    private @Nullable Boolean tipsShown;
    private @Nullable Boolean colorCycling;
    private @Nullable Integer unitPortraits;
    private @Nullable Boolean consoleSplit;

    private @Nullable Boolean actionsPerMinuteShown;
    private @Nullable Boolean actionsPerMinuteAlertEnabled;
    private @Nullable Integer actionsPerMinuteAlertThreshold;
    private @Nullable Boolean actionsPerMinuteAlertColorEnabled;
    private @Nullable Boolean actionsPerMinuteAlertSoundEnabled;

    private @Nullable Boolean mouseSensitivityEnabled;
    private @Nullable Integer mouseSensitivity;
    private @Nullable Boolean mouseScaling;
    private @Nullable Boolean hardwareCursor;

    private @Nullable String hotkeyProfile;
    private final @NotNull Map<Integer, Integer> hotkeys;

    private @Nullable Integer speedIndex;
    private @Nullable Integer gameSpeed;
    private @Nullable String locale;
    private @Nullable Boolean extendedUnitLimits;
    private @Nullable Boolean customHotkeys;
    private final @NotNull Map<String, Object> createGame;

    private @Nullable String dataDirectory;
    private @Nullable Boolean mapCompatibility;

    private @Nullable LogLevel logLevel;
    private @Nullable Boolean logFile;

    private final @NotNull List<String> ignored;

    /**
     * Creates a {@link ClientSettings} instance.
     */
    private ClientSettings() {
        this.renderer              = null;
        this.rendererQuality       = null;
        this.vsync                 = null;
        this.frameRateLimitEnabled = null;
        this.smoothTurning         = null;
        this.shadowStacking        = null;
        this.environmentEffects    = null;
        this.sdFilterMode          = null;
        this.windowMode            = null;
        this.originalAspectRatio   = null;
        this.gamma                 = null;
        this.frameRateLimit        = null;
        this.hdSkin                = null;
        this.hdSkinSet             = false;
        this.hdLighting            = null;

        this.audioSet     = null;
        this.musicVolume  = null;
        this.musicEnabled = null;

        this.soundEffectsVolume  = null;
        this.soundEffectsEnabled = null;

        this.unitSpeech          = null;
        this.unitAcknowledgments = null;
        this.buildingSounds      = null;
        this.soundInBackground   = null;
        this.gameSubtitles       = null;
        this.cinematicSubtitles  = null;
        this.originalUnitVoices  = null;
        this.originalCampaign    = null;

        this.mouseConfine   = null;
        this.keyScroll      = null;
        this.mouseScroll    = null;
        this.frameRateShown = null;
        this.turnRateShown  = null;
        this.gameTimer      = null;
        this.tipNumber      = null;
        this.tipsShown      = null;
        this.colorCycling   = null;
        this.unitPortraits  = null;
        this.consoleSplit   = null;

        this.actionsPerMinuteShown             = null;
        this.actionsPerMinuteAlertEnabled      = null;
        this.actionsPerMinuteAlertThreshold    = null;
        this.actionsPerMinuteAlertColorEnabled = null;
        this.actionsPerMinuteAlertSoundEnabled = null;

        this.mouseSensitivityEnabled = null;
        this.mouseSensitivity        = null;
        this.mouseScaling            = null;
        this.hardwareCursor          = null;

        this.hotkeyProfile = null;
        this.hotkeys       = new HashMap<>();

        this.speedIndex         = null;
        this.gameSpeed          = null;
        this.locale             = null;
        this.extendedUnitLimits = null;
        this.customHotkeys      = null;
        this.createGame         = new LinkedHashMap<>();

        this.dataDirectory    = null;
        this.mapCompatibility = null;

        this.logLevel = null;
        this.logFile  = null;

        this.ignored = new ArrayList<>();
    }

    /**
     * @return {@code true} if {@code graphics.renderer} is present, {@code false} otherwise
     */
    boolean hasRenderer() {
        return this.renderer != null;
    }

    /**
     * @return the renderer quality of {@code graphics.renderer}, or {@code null} for classic or when absent
     */
    @Nullable HdRenderAssets.Quality getRendererQuality() {
        return this.rendererQuality;
    }

    /**
     * @return {@code true} if {@code graphics.hdSkin} is present, {@code false} otherwise
     */
    boolean hasHdSkin() {
        return this.hdSkinSet;
    }

    /**
     * @return the skin folder of {@code graphics.hdSkin} ({@code PreSale} or {@code Carbot}), or {@code null} for the default animations or when absent
     */
    @Nullable String getHdSkin() {
        return this.hdSkin;
    }

    /**
     * @return {@code true} if {@code graphics.hdLighting} turns HD lighting on, {@code false} if it turns it off, or {@code null} when absent
     */
    @Nullable Boolean getHdLighting() {
        return this.hdLighting;
    }

    /**
     * @return {@code audio.set}, or {@code null} when absent
     */
    @Nullable SoundPathResolver.AudioSet getAudioSet() {
        return this.audioSet;
    }

    /**
     * @return {@code true} if {@code input.mouseConfine} confines the cursor to the window, {@code false} if it does not, or {@code null} when absent
     */
    @Nullable Boolean getMouseConfine() {
        return this.mouseConfine;
    }

    /**
     * @return {@code input.hotkeyProfile}, or {@code null} when absent
     */
    @Nullable String getHotkeyProfile() {
        return this.hotkeyProfile;
    }

    /**
     * @return the key code points of {@code input.hotkeys} by {@code stat_txt} identifier ({@link HotkeyBindingsUtils#parse})
     */
    @NotNull Map<Integer, Integer> getHotkeys() {
        return this.hotkeys;
    }

    /**
     * @return the playback speed index of {@code game.speed}, or {@code null} when absent
     */
    @Nullable Integer getSpeedIndex() {
        return this.speedIndex;
    }

    /**
     * @return {@code game.gameSpeed} ({@code 0..6}), or {@code null} when absent
     */
    @Nullable Integer getGameSpeed() {
        return this.gameSpeed;
    }

    /**
     * @return {@code game.locale}, or {@code null} when absent
     */
    @Nullable String getLocale() {
        return this.locale;
    }

    /**
     * @return {@code true} if {@code game.extendedUnitLimits} selects extended unit limits, {@code false} if it does not, or {@code null} when absent
     */
    @Nullable Boolean getExtendedUnitLimits() {
        return this.extendedUnitLimits;
    }

    /**
     * @return {@code true} if {@code game.customHotkeys} allows player hotkey profiles, {@code false} if it does not, or {@code null} when absent
     */
    @Nullable Boolean getCustomHotkeys() {
        return this.customHotkeys;
    }

    /**
     * Returns the create screen values: {@code type}, {@code subtype}, {@code customType}, {@code speed} and {@code turnRate} as {@link Integer},
     * {@code name} as {@link String}.
     *
     * @return the create screen values of the {@code createGame} group by key
     */
    @NotNull Map<String, Object> getCreateGame() {
        return Collections.unmodifiableMap(this.createGame);
    }

    /**
     * @return {@code paths.data} as written in the file (resolve it with {@link InstallLayout#resolveDataDirectory(String)}), or {@code null} when absent
     */
    @Nullable String getDataDirectory() {
        return this.dataDirectory;
    }

    /**
     * @return {@code true} if {@code maps.compatibility} selects the relaxed map rules, {@code false} if it does not, or {@code null} when absent
     */
    @Nullable Boolean getMapCompatibility() {
        return this.mapCompatibility;
    }

    /**
     * @return the log threshold of {@code logging.level}, or {@code null} when absent
     */
    @Nullable LogLevel getLogLevel() {
        return this.logLevel;
    }

    /**
     * @return {@code true} if {@code logging.file} writes the rolling log file, {@code false} if it does not, or {@code null} when absent
     */
    @Nullable Boolean getLogFile() {
        return this.logFile;
    }

    /**
     * @return the unknown groups and keys, in file order
     */
    @NotNull List<String> getIgnored() {
        return this.ignored;
    }

    /**
     * Copies the display pacing, rendering, gamma and window values present in the file.
     *
     * @param graphics the settings to fill
     */
    void applyTo(@NotNull GraphicsSettings graphics) {
        if (this.vsync != null) {
            graphics.setVsync(this.vsync);
        }

        if (this.frameRateLimitEnabled != null) {
            graphics.setFrameRateLimitEnabled(this.frameRateLimitEnabled);
        }

        if (this.frameRateLimit != null) {
            graphics.setFrameRateLimit(this.frameRateLimit);
        }

        if (this.smoothTurning != null) {
            graphics.setSmoothTurning(this.smoothTurning);
        }

        if (this.shadowStacking != null) {
            graphics.setShadowStacking(this.shadowStacking);
        }

        if (this.environmentEffects != null) {
            graphics.setEnvironmentEffects(this.environmentEffects);
        }

        if (this.sdFilterMode != null) {
            graphics.setSdFilterMode(this.sdFilterMode);
        }

        if (this.originalAspectRatio != null) {
            graphics.setOriginalAspectRatio(this.originalAspectRatio);
        }

        if (this.gamma != null) {
            graphics.setGamma(this.gamma);
        }

        if (this.windowMode != null) {
            graphics.setWindowMode(this.windowMode);
        }
    }

    /**
     * Copies the interface values present in the file.
     *
     * @param settings the settings to fill
     */
    void applyTo(@NotNull InterfaceSettings settings) {
        if (this.keyScroll != null) {
            settings.setKeyScroll(this.keyScroll);
        }

        if (this.mouseScroll != null) {
            settings.setMouseScroll(this.mouseScroll);
        }

        if (this.frameRateShown != null) {
            settings.setFrameRateShown(this.frameRateShown);
        }

        if (this.turnRateShown != null) {
            settings.setTurnRateShown(this.turnRateShown);
        }

        if (this.gameTimer != null) {
            settings.setGameTimer(this.gameTimer);
        }

        if (this.tipNumber != null) {
            settings.setTipNumber(this.tipNumber);
        }

        if (this.tipsShown != null) {
            settings.setTipsShown(this.tipsShown);
        }

        if (this.colorCycling != null) {
            settings.setColorCycling(this.colorCycling);
        }

        if (this.unitPortraits != null) {
            settings.setUnitPortraits(this.unitPortraits);
        }

        if (this.consoleSplit != null) {
            settings.setConsoleSplit(this.consoleSplit);
        }

        if (this.actionsPerMinuteShown != null) {
            settings.setActionsPerMinuteShown(this.actionsPerMinuteShown);
        }

        if (this.actionsPerMinuteAlertEnabled != null) {
            settings.setActionsPerMinuteAlertEnabled(this.actionsPerMinuteAlertEnabled);
        }

        if (this.actionsPerMinuteAlertThreshold != null) {
            settings.setActionsPerMinuteAlertThreshold(this.actionsPerMinuteAlertThreshold);
        }

        if (this.actionsPerMinuteAlertColorEnabled != null) {
            settings.setActionsPerMinuteAlertColorEnabled(this.actionsPerMinuteAlertColorEnabled);
        }

        if (this.actionsPerMinuteAlertSoundEnabled != null) {
            settings.setActionsPerMinuteAlertSoundEnabled(this.actionsPerMinuteAlertSoundEnabled);
        }

        if (this.mouseSensitivityEnabled != null) {
            settings.setMouseSensitivityEnabled(this.mouseSensitivityEnabled);
        }

        if (this.mouseSensitivity != null) {
            settings.setMouseSensitivity(this.mouseSensitivity);
        }

        if (this.mouseScaling != null) {
            settings.setMouseScaling(this.mouseScaling);
        }

        if (this.hardwareCursor != null) {
            settings.setHardwareCursor(this.hardwareCursor);
        }
    }

    /**
     * Copies the sound values present in the file.
     *
     * @param sound the settings to fill
     */
    void applyTo(@NotNull SoundSettings sound) {
        if (this.musicVolume != null) {
            sound.setMusicVolume(this.musicVolume);
        }

        if (this.musicEnabled != null) {
            sound.setMusicEnabled(this.musicEnabled);
        }

        if (this.soundEffectsVolume != null) {
            sound.setSoundEffectsVolume(this.soundEffectsVolume);
        }

        if (this.soundEffectsEnabled != null) {
            sound.setSoundEffectsEnabled(this.soundEffectsEnabled);
        }

        if (this.unitSpeech != null) {
            sound.setUnitSpeech(this.unitSpeech);
        }

        if (this.unitAcknowledgments != null) {
            sound.setUnitAcknowledgments(this.unitAcknowledgments);
        }

        if (this.buildingSounds != null) {
            sound.setBuildingSounds(this.buildingSounds);
        }

        if (this.soundInBackground != null) {
            sound.setSoundInBackground(this.soundInBackground);
        }

        if (this.gameSubtitles != null) {
            sound.setGameSubtitles(this.gameSubtitles);
        }

        if (this.cinematicSubtitles != null) {
            sound.setCinematicSubtitles(this.cinematicSubtitles);
        }

        if (this.originalUnitVoices != null) {
            sound.setOriginalUnitVoices(this.originalUnitVoices);
        }

        if (this.originalCampaign != null) {
            sound.setOriginalCampaign(this.originalCampaign);
        }
    }

    /**
     * Reads one settings entry and records unknown entries without applying them.
     *
     * @param group the settings group
     * @param key   the settings key
     * @throws IllegalArgumentException if a recognized entry has an invalid value
     */
    private void read(@NotNull SettingsGroup group, @NotNull String key) {
        String name = group.getQualifiedName(key);
        Object value = group.getValue(key);

        switch (name) {
            case "graphics.renderer" -> {
                this.rendererQuality = ClientValuesUtils.parseRenderer(name, value);
                this.renderer        = (String) value;
            }
            case "graphics.vsync" -> this.vsync = ClientValuesUtils.parseVsync(name, value);
            case "graphics.fpsLimitEnabled" -> this.frameRateLimitEnabled = group.getBoolean(key);
            case "graphics.smoothTurning" -> this.smoothTurning = group.getBoolean(key);
            case "graphics.shadowStacking" -> this.shadowStacking = group.getBoolean(key);
            case "graphics.environmentEffects" -> this.environmentEffects = group.getBoolean(key);
            case "graphics.sdFilterMode" -> this.sdFilterMode = group.getInteger(key, 0, GraphicsSettings.MAXIMUM_SD_FILTER_MODE);
            case ORIGINAL_ASPECT_RATIO_KEY -> this.originalAspectRatio = group.getBoolean(key);
            // Display mode identifiers cover windowed, desktop fullscreen and fullscreen video modes.
            case "graphics.windowMode" -> this.windowMode = group.getInteger(key, GraphicsSettings.WINDOWED, GraphicsSettings.FULLSCREEN);
            case "graphics.gamma" -> this.gamma = group.getInteger(key, GraphicsSettings.MINIMUM_GAMMA, GraphicsSettings.MAXIMUM_GAMMA);
            case "graphics.fpsLimit" -> {
                if (!(value instanceof Long)) {
                    throw Exceptions.newIllegalArgumentException("{0} must be a number from 0 to {1}.", name,
                                                                 ClientValuesUtils.MAXIMUM_FRAME_RATE_LIMIT);
                }

                this.frameRateLimit = ClientValuesUtils.parseFrameRateLimit(name, value);
            }
            case "graphics.hdSkin" -> {
                this.hdSkin    = ClientValuesUtils.parseHdSkin(name, value);
                this.hdSkinSet = true;
            }
            case "graphics.hdLighting" -> this.hdLighting = group.getBoolean(key);
            case "audio.set" -> this.audioSet = ClientValuesUtils.parseAudioSet(name, value);
            case "audio.musicVolume" -> this.musicVolume = parseVolume(name, value);
            case "audio.musicEnabled" -> this.musicEnabled = group.getBoolean(key);
            case "audio.sfxVolume" -> this.soundEffectsVolume = parseVolume(name, value);
            case "audio.sfxEnabled" -> this.soundEffectsEnabled = group.getBoolean(key);
            case "audio.unitSpeech" -> this.unitSpeech = group.getBoolean(key);
            case "audio.unitAcknowledgments" -> this.unitAcknowledgments = group.getBoolean(key);
            case "audio.buildingSounds" -> this.buildingSounds = group.getBoolean(key);
            case "audio.soundInBackground" -> this.soundInBackground = group.getBoolean(key);
            case "audio.gameSubtitles" -> this.gameSubtitles = group.getBoolean(key);
            case "audio.cinematicSubtitles" -> this.cinematicSubtitles = group.getBoolean(key);
            case "audio.originalUnitVoices" -> this.originalUnitVoices = group.getBoolean(key);
            case "audio.originalCampaign" -> this.originalCampaign = group.getBoolean(key);
            case "input.mouseConfine" -> this.mouseConfine = group.getBoolean(key);
            case "input.useMouseSensitivity" -> this.mouseSensitivityEnabled = group.getBoolean(key);
            case "input.mouseSensitivity" -> this.mouseSensitivity = group.getInteger(key, 0, InterfaceSettings.MAXIMUM_MOUSE_SENSITIVITY);
            case "input.mouseScaling" -> this.mouseScaling = group.getBoolean(key);
            case "input.hardwareCursor" -> this.hardwareCursor = group.getBoolean(key);
            case "input.keyScroll" -> this.keyScroll = parseScrollSpeed(name, value);
            case "input.mouseScroll" -> this.mouseScroll = parseScrollSpeed(name, value);
            // Canonical profile spellings are restored after case-insensitive validation.
            case "input.hotkeyProfile" -> {
                String profile = ClientValuesUtils.parseChoice(name, value, HotkeyProfile.DEVICE, NO_HOTKEYS_CHOICE);

                this.hotkeyProfile = profile.equals(NO_HOTKEYS_CHOICE) ? HotkeyProfile.NO_HOTKEYS : HotkeyProfile.DEVICE;
            }
            case "input.hotkeys" -> {
                if (!(value instanceof String text)) {
                    throw Exceptions.newIllegalArgumentException("{0} must be SC:R's hotkey string of \"NAME=k\" lines.", name);
                }

                this.hotkeys.putAll(HotkeyBindingsUtils.parse(text));
            }
            case "game.speed" -> {
                if (!(value instanceof String text)) {
                    throw Exceptions.newIllegalArgumentException("{0} must be a playback speed such as \"x1\".", name);
                }

                this.speedIndex = ReplayPlayback.parseSpeed(text);
            }
            case "game.gameSpeed" -> {
                if (!(value instanceof Long number) || number < 0 || number > GameSpeed.FASTEST) {
                    throw Exceptions.newIllegalArgumentException("{0} must be a game speed from 0 to 6.", name);
                }

                this.gameSpeed = number.intValue();
            }
            case "game.locale" -> this.locale = ClientValuesUtils.parseLocale(name, value);
            case "game.extendedUnitLimits" -> this.extendedUnitLimits = group.getBoolean(key);
            case "game.customHotkeys" -> this.customHotkeys = group.getBoolean(key);
            case "game.showFps" -> this.frameRateShown = group.getBoolean(key);
            case "game.showTurnRate" -> this.turnRateShown = group.getBoolean(key);
            case "game.gameTimer" -> this.gameTimer = group.getBoolean(key);
            case "game.tipNumber" -> {
                if (!(value instanceof Long number) || number < 0 || number > Integer.MAX_VALUE) {
                    throw Exceptions.newIllegalArgumentException("{0} must be a whole number.", name);
                }

                this.tipNumber = number.intValue();
            }
            case "game.showTips" -> this.tipsShown = group.getBoolean(key);
            case "game.colorCycling" -> this.colorCycling = group.getBoolean(key);
            case "game.unitPortraits" -> {
                if (!(value instanceof Long number) || number < InterfaceSettings.NO_PORTRAITS || number > InterfaceSettings.ANIMATED_PORTRAITS) {
                    throw Exceptions.newIllegalArgumentException("{0} must be 0 (none), 1 (still) or 2 (animated).", name);
                }

                this.unitPortraits = number.intValue();
            }
            case "game.consoleSplit" -> this.consoleSplit = group.getBoolean(key);
            case "game.showApm" -> this.actionsPerMinuteShown = group.getBoolean(key);
            case "game.apmAlert" -> this.actionsPerMinuteAlertEnabled = group.getBoolean(key);
            case "game.apmAlertValue" -> {
                this.actionsPerMinuteAlertThreshold = group.getInteger(key, 0, InterfaceSettings.MAXIMUM_ACTIONS_PER_MINUTE_ALERT_THRESHOLD);
            }
            case "game.apmAlertColor" -> this.actionsPerMinuteAlertColorEnabled = group.getBoolean(key);
            case "game.apmAlertSound" -> this.actionsPerMinuteAlertSoundEnabled = group.getBoolean(key);
            case "createGame.type", "createGame.subtype", "createGame.customType", "createGame.speed", "createGame.turnRate" -> {
                if (!(value instanceof Long number) || number < 0 || number > Integer.MAX_VALUE) {
                    throw Exceptions.newIllegalArgumentException("{0} must be a whole number.", name);
                }

                this.createGame.put(key, number.intValue());
            }
            case "createGame.name" -> this.createGame.put(key, group.getString(key));
            case "logging.level" -> this.logLevel = ClientValuesUtils.parseLogLevel(name, value);
            case "logging.file" -> this.logFile = group.getBoolean(key);
            case "paths.data" -> {
                if (!(value instanceof String text) || text.isBlank()) {
                    throw Exceptions.newIllegalArgumentException("{0} must be a folder path such as \"data\".", name);
                }

                try {
                    Path.of(text);
                } catch (InvalidPathException exception) {
                    throw Exceptions.newIllegalArgumentException(exception, "{0} is not a valid path: {1}", name, text);
                }

                this.dataDirectory = text;
            }
            case "maps.compatibility" -> this.mapCompatibility = group.getBoolean(key);
            default -> this.ignored.add(name);
        }
    }
}
