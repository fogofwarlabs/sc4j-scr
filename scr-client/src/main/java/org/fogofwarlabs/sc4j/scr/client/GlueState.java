package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditOperation;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.InputListener;
import org.fogofwarlabs.sc4j.engine.client.settings.SettingsStore;
import org.fogofwarlabs.sc4j.scr.client.audio.SoundSettings;
import org.fogofwarlabs.sc4j.scr.client.configuration.*;
import org.fogofwarlabs.sc4j.scr.client.glue.*;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.GlueLoadPopup;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.GlueSavePopup;
import org.fogofwarlabs.sc4j.scr.client.glue.score.GlueResume;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialogLayer;
import org.fogofwarlabs.sc4j.scr.client.ui.input.HotkeyBindingsUtils;
import org.fogofwarlabs.sc4j.scr.render.hd.HdRenderAssets;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFonts;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Keeps menu resources, resume state and staged start failures across client games.
 */
final class GlueState implements InputListener {
    private static final @NotNull Logger LOGGER = Logger.of(GlueState.class);

    /**
     * Developer path for text screenshots: {@code -Dsc4j.glueScreen=MAIN_MENU|CAMPAIGN|CUSTOM_GAME|REPLAY_LIST}.
     */
    private static final @NotNull String GLUE_SCREEN_PROPERTY = "sc4j.glueScreen";
    /**
     * Developer switch of {@link #GLUE_SCREEN_PROPERTY}: {@code -Dsc4j.glueExpansion=true} opens the screen as after choosing Brood War.
     */
    private static final @NotNull String GLUE_EXPANSION_PROPERTY = "sc4j.glueExpansion";

    /**
     * The network text key of the replay-load error.
     */
    private static final @NotNull String REPLAY_LOAD_ERROR = "strERROR_LOAD_MAP";

    /**
     * Selects the graphics of the whole client ({@code Main.selectGraphicsQuality}).
     */
    interface GraphicsSelection {
        /**
         * @return the selected HD quality, or {@code null} for the classic graphics
         */
        HdRenderAssets.@Nullable Quality getQuality();

        /**
         * Selects the graphics at once: the image set of an HD quality loads once and stays with the client.
         *
         * @param quality the HD quality, or {@code null} for the classic graphics
         */
        void select(HdRenderAssets.@Nullable Quality quality);
    }

    /**
     * The menu choices of the settings file, written back through the one writer {@link SettingsStore#write} when the user changes them (never when
     * writing is off): the create screen's {@code createGame} group and the options popup's values under their launch option keys. The options start
     * from the effective launch values; the display pacing ({@code graphics.vsync}, {@code graphics.fpsLimitEnabled}, {@code graphics.fpsLimit}), the
     * unit turning ({@code graphics.smoothTurning}), the shadow stacking ({@code graphics.shadowStacking}), the environment effects
     * ({@code graphics.environmentEffects}), the SD filter ({@code graphics.sdFilterMode}), the original aspect ratio
     * ({@code graphics.originalAspectRatio}), the gamma ({@code graphics.gamma}, full screen only), the game speed ({@code game.gameSpeed}, also of a
     * running single-player game), the mouse confinement ({@code input.mouseConfine}), the scroll speeds ({@code input.keyScroll},
     * {@code input.mouseScroll}), the frame and turn rate displays ({@code game.showFps}, {@code game.showTurnRate}), the game time
     * ({@code game.gameTimer}) and the sound settings ({@code audio.*} other than {@code audio.set}, held by {@link SoundSettings}, which the client
     * applies every frame) apply at once, the others at the next start (the audio set and the locale are read at start).
     */
    private static final class FileSettings implements GlueSettings {
        private static final @NotNull String GROUP = "createGame";
        private static final @NotNull String TYPE = "type";

        /**
         * The start-only setting of the sound set.
         */
        private static final @NotNull String AUDIO_SET = "audio.set";
        /**
         * The start-only setting of the text locale.
         */
        private static final @NotNull String LOCALE = "game.locale";

        private static final @NotNull String VSYNC = "graphics.vsync";
        private static final @NotNull String FRAME_RATE_LIMIT_ENABLED = "graphics.fpsLimitEnabled";
        private static final @NotNull String FRAME_RATE_LIMIT = "graphics.fpsLimit";

        private static final @NotNull String SMOOTH_TURNING = "graphics.smoothTurning";
        private static final @NotNull String SHADOW_STACKING = "graphics.shadowStacking";
        private static final @NotNull String ENVIRONMENT_EFFECTS = "graphics.environmentEffects";
        private static final @NotNull String SD_FILTER_MODE = "graphics.sdFilterMode";
        private static final @NotNull String GAMMA = "graphics.gamma";

        private static final @NotNull String GAME_SPEED = "game.gameSpeed";

        /**
         * The read-only menu setting of the custom hotkeys: {@link #getOption} reads the launch value and {@link #setOption}
         * ignores it, so it is never written.
         */
        private static final @NotNull String CUSTOM_HOTKEYS = "game.customHotkeys";
        /**
         * The read-only menu setting of the extended unit limits: {@link #getOption} reads the launch value and {@link #setOption}
         * ignores it, so it is never written.
         */
        private static final @NotNull String EXTENDED_UNIT_LIMITS = "game.extendedUnitLimits";

        private static final @NotNull String RENDERER = "graphics.renderer";
        private static final @NotNull String WINDOW_MODE = "graphics.windowMode";
        private static final @NotNull String HD_LIGHTING = "graphics.hdLighting";

        private static final @NotNull String MUSIC_VOLUME = "audio.musicVolume";
        private static final @NotNull String MUSIC_ENABLED = "audio.musicEnabled";
        private static final @NotNull String SOUND_EFFECTS_VOLUME = "audio.sfxVolume";
        private static final @NotNull String SOUND_EFFECTS_ENABLED = "audio.sfxEnabled";
        private static final @NotNull String UNIT_SPEECH = "audio.unitSpeech";
        private static final @NotNull String UNIT_ACKNOWLEDGMENTS = "audio.unitAcknowledgments";
        private static final @NotNull String BUILDING_SOUNDS = "audio.buildingSounds";
        private static final @NotNull String SOUND_IN_BACKGROUND = "audio.soundInBackground";
        private static final @NotNull String GAME_SUBTITLES = "audio.gameSubtitles";
        private static final @NotNull String CINEMATIC_SUBTITLES = "audio.cinematicSubtitles";
        private static final @NotNull String ORIGINAL_UNIT_VOICES = "audio.originalUnitVoices";
        private static final @NotNull String ORIGINAL_CAMPAIGN = "audio.originalCampaign";

        private static final @NotNull String MOUSE_CONFINE = "input.mouseConfine";
        private static final @NotNull String KEY_SCROLL = "input.keyScroll";
        private static final @NotNull String MOUSE_SCROLL = "input.mouseScroll";
        private static final @NotNull String USE_MOUSE_SENSITIVITY = "input.useMouseSensitivity";
        private static final @NotNull String MOUSE_SENSITIVITY = "input.mouseSensitivity";
        private static final @NotNull String MOUSE_SCALING = "input.mouseScaling";
        private static final @NotNull String HARDWARE_CURSOR = "input.hardwareCursor";

        private static final @NotNull String SHOW_FRAME_RATE = "game.showFps";
        private static final @NotNull String SHOW_TURN_RATE = "game.showTurnRate";
        private static final @NotNull String GAME_TIMER = "game.gameTimer";

        /**
         * The menu setting applied through the client settings writer.
         */
        private static final @NotNull String TIP_NUMBER = "game.tipNumber";
        /**
         * The menu setting applied through the client settings writer.
         */
        private static final @NotNull String SHOW_TIPS = "game.showTips";

        private static final @NotNull String COLOR_CYCLING = "game.colorCycling";
        private static final @NotNull String UNIT_PORTRAITS = "game.unitPortraits";
        private static final @NotNull String CONSOLE_SPLIT = "game.consoleSplit";

        /**
         * The latency of the running game: {@link #getOption} reads it through the latency access and {@link #setOption} stages it for the game,
         * never writing it to the settings file.
         */
        private static final @NotNull String LATENCY = "network.latency";

        private static final @NotNull String SHOW_ACTIONS_PER_MINUTE = "game.showApm";
        private static final @NotNull String ACTIONS_PER_MINUTE_ALERT = "game.apmAlert";
        private static final @NotNull String ACTIONS_PER_MINUTE_ALERT_VALUE = "game.apmAlertValue";
        private static final @NotNull String ACTIONS_PER_MINUTE_ALERT_COLOR = "game.apmAlertColor";
        private static final @NotNull String ACTIONS_PER_MINUTE_ALERT_SOUND = "game.apmAlertSound";

        /**
         * The menu setting applied through the client settings writer.
         */
        private static final @NotNull String HOTKEY_PROFILE = "input.hotkeyProfile";
        /**
         * The menu setting applied through the client settings writer.
         */
        private static final @NotNull String HOTKEYS = "input.hotkeys";

        private final @NotNull Map<String, Integer> createGame;

        private final @NotNull GraphicsSelection selection;

        private final @Nullable Path file;
        private final @NotNull GraphicsSettings graphics;
        private final @NotNull SoundSettings sound;
        private final @NotNull InterfaceSettings interfaceSettings;
        private final @NotNull ClientOptions launch;

        private final @NotNull Map<String, Object> options;

        private int gameType;

        private @NotNull Predicate<String> availability;
        private @NotNull IntSupplier latencyAccess;
        private @NotNull IntConsumer latencyStage;

        /**
         * Creates a {@link FileSettings} instance.
         *
         * @param options   the effective launch options
         * @param selection the client graphics selection
         */
        private FileSettings(@NotNull ClientOptions options, @NotNull GraphicsSelection selection) {
            this.createGame = new HashMap<>();

            this.selection = selection;

            this.file              = options.getSettingsFile();
            this.graphics          = options.getGraphicsSettings();
            this.sound             = options.getSoundSettings();
            this.interfaceSettings = options.getInterfaceSettings();
            this.launch            = options;

            this.options = new HashMap<>();

            this.gameType = options.getCreateGame().get(TYPE) instanceof Integer type ? type : NONE;

            this.availability  = key -> true;
            this.latencyAccess = () -> -1;
            this.latencyStage  = latency -> {
            };

            for (String key : new String[]{SUBTYPE, SPEED, TURN_RATE}) {
                if (options.getCreateGame().get(key) instanceof Integer value) {
                    this.createGame.put(key, value);
                }
            }

            this.options.put(HD_LIGHTING, options.isHdLighting());
            this.options.put(AUDIO_SET, options.getAudioSet().name().toLowerCase(Locale.ROOT));

            String locale = options.getLocale();

            if (locale != null) {
                this.options.put(LOCALE, locale);
            }
        }

        /**
         * Returns the current value of a client option, by its settings file key.
         *
         * @param key the key as {@code group.key}, for example {@code graphics.vsync}
         * @return the value ({@link Boolean}, {@link Integer} or {@link String}), or {@code null} when the client does not have the option
         */
        @Override
        public @Nullable Object getOption(@NotNull String key) {
            return switch (key) {
                case VSYNC -> this.graphics.getVsync().name().toLowerCase(Locale.ROOT);
                case FRAME_RATE_LIMIT_ENABLED -> this.graphics.isFrameRateLimitEnabled();
                case FRAME_RATE_LIMIT -> this.graphics.getFrameRateLimit();
                case SMOOTH_TURNING -> this.graphics.isSmoothTurning();
                case SHADOW_STACKING -> this.graphics.isShadowStacking();
                case ENVIRONMENT_EFFECTS -> this.graphics.isEnvironmentEffects();
                case SD_FILTER_MODE -> this.graphics.getSdFilterMode();
                case ClientSettings.ORIGINAL_ASPECT_RATIO_KEY -> this.graphics.isOriginalAspectRatio();
                case GAMMA -> this.graphics.getGamma();
                case WINDOW_MODE -> this.graphics.getWindowMode();
                case RENDERER -> GraphicsChoice.of(this.selection.getQuality()).getName();
                case GAME_SPEED -> this.launch.getGameSpeed();
                case MUSIC_VOLUME -> this.sound.getMusicVolume();
                case MUSIC_ENABLED -> this.sound.isMusicEnabled();
                case SOUND_EFFECTS_VOLUME -> this.sound.getSoundEffectsVolume();
                case SOUND_EFFECTS_ENABLED -> this.sound.isSoundEffectsEnabled();
                case UNIT_SPEECH -> this.sound.isUnitSpeech();
                case UNIT_ACKNOWLEDGMENTS -> this.sound.isUnitAcknowledgments();
                case BUILDING_SOUNDS -> this.sound.isBuildingSounds();
                case SOUND_IN_BACKGROUND -> this.sound.isSoundInBackground();
                case GAME_SUBTITLES -> this.sound.isGameSubtitles();
                case CINEMATIC_SUBTITLES -> this.sound.isCinematicSubtitles();
                case ORIGINAL_UNIT_VOICES -> this.sound.isOriginalUnitVoices();
                case ORIGINAL_CAMPAIGN -> this.sound.isOriginalCampaign();
                case CUSTOM_HOTKEYS -> this.launch.isCustomHotkeys();
                case EXTENDED_UNIT_LIMITS -> this.launch.isExtendedUnitLimits();
                case MOUSE_CONFINE -> !this.launch.isNoGrab();
                case KEY_SCROLL -> this.interfaceSettings.getKeyScroll();
                case MOUSE_SCROLL -> this.interfaceSettings.getMouseScroll();
                case USE_MOUSE_SENSITIVITY -> this.interfaceSettings.isMouseSensitivityEnabled();
                case MOUSE_SENSITIVITY -> this.interfaceSettings.getMouseSensitivity();
                case MOUSE_SCALING -> this.interfaceSettings.isMouseScaling();
                case HARDWARE_CURSOR -> this.interfaceSettings.isHardwareCursor();
                case SHOW_FRAME_RATE -> this.interfaceSettings.isFrameRateShown();
                case SHOW_TURN_RATE -> this.interfaceSettings.isTurnRateShown();
                case GAME_TIMER -> this.interfaceSettings.isGameTimer();
                case TIP_NUMBER -> this.interfaceSettings.getTipNumber();
                case SHOW_TIPS -> this.interfaceSettings.isTipsShown();
                case COLOR_CYCLING -> this.interfaceSettings.isColorCycling();
                case UNIT_PORTRAITS -> this.interfaceSettings.getUnitPortraits();
                case CONSOLE_SPLIT -> this.interfaceSettings.isConsoleSplit();
                case LATENCY -> {
                    int latency = this.latencyAccess.getAsInt();

                    yield latency < 0 ? null : latency;
                }
                case SHOW_ACTIONS_PER_MINUTE -> this.interfaceSettings.isActionsPerMinuteShown();
                case ACTIONS_PER_MINUTE_ALERT -> this.interfaceSettings.isActionsPerMinuteAlertEnabled();
                case ACTIONS_PER_MINUTE_ALERT_VALUE -> this.interfaceSettings.getActionsPerMinuteAlertThreshold();
                case ACTIONS_PER_MINUTE_ALERT_COLOR -> this.interfaceSettings.isActionsPerMinuteAlertColorEnabled();
                case ACTIONS_PER_MINUTE_ALERT_SOUND -> this.interfaceSettings.isActionsPerMinuteAlertSoundEnabled();
                case HOTKEY_PROFILE -> this.launch.getHotkeyProfile();
                case HOTKEYS -> HotkeyBindingsUtils.format(this.launch.getHotkeys());
                default -> this.options.get(key);
            };
        }

        /**
         * Sets and applies a client option chosen in the options popup.
         *
         * @param key   the key as {@code group.key}
         * @param value the value: {@link Boolean}, {@link Integer} or {@link String}
         */
        @Override
        public void setOption(@NotNull String key, @NotNull Object value) {
            switch (key) {
                case VSYNC -> this.graphics.setVsync(ClientValuesUtils.parseVsync(key, value));
                case FRAME_RATE_LIMIT_ENABLED -> this.graphics.setFrameRateLimitEnabled((Boolean) value);
                case FRAME_RATE_LIMIT -> this.graphics.setFrameRateLimit((Integer) value);
                case SMOOTH_TURNING -> this.graphics.setSmoothTurning((Boolean) value);
                case SHADOW_STACKING -> this.graphics.setShadowStacking((Boolean) value);
                case ENVIRONMENT_EFFECTS -> this.graphics.setEnvironmentEffects((Boolean) value);
                case SD_FILTER_MODE -> this.graphics.setSdFilterMode((Integer) value);
                case ClientSettings.ORIGINAL_ASPECT_RATIO_KEY -> this.graphics.setOriginalAspectRatio((Boolean) value);
                case GAMMA -> this.graphics.setGamma((Integer) value);
                // A changed display mode is applied on the next client frame.
                case WINDOW_MODE -> this.graphics.setWindowMode((Integer) value);
                // The options' HD graphics apply at once, as F5 does (one image set in the client context).
                case RENDERER -> this.selection.select(ClientValuesUtils.parseRenderer(key, value.toString()));
                case GAME_SPEED -> this.launch.setGameSpeed((Integer) value);
                case MUSIC_VOLUME -> this.sound.setMusicVolume((Integer) value);
                case MUSIC_ENABLED -> this.sound.setMusicEnabled((Boolean) value);
                case SOUND_EFFECTS_VOLUME -> this.sound.setSoundEffectsVolume((Integer) value);
                case SOUND_EFFECTS_ENABLED -> this.sound.setSoundEffectsEnabled((Boolean) value);
                case UNIT_SPEECH -> this.sound.setUnitSpeech((Boolean) value);
                case UNIT_ACKNOWLEDGMENTS -> this.sound.setUnitAcknowledgments((Boolean) value);
                case BUILDING_SOUNDS -> this.sound.setBuildingSounds((Boolean) value);
                case SOUND_IN_BACKGROUND -> this.sound.setSoundInBackground((Boolean) value);
                case GAME_SUBTITLES -> this.sound.setGameSubtitles((Boolean) value);
                // Read when a cinematic, a game or an establishing shot starts (the page disables them in a game).
                case CINEMATIC_SUBTITLES -> this.sound.setCinematicSubtitles((Boolean) value);
                case ORIGINAL_UNIT_VOICES -> this.sound.setOriginalUnitVoices((Boolean) value);
                case ORIGINAL_CAMPAIGN -> this.sound.setOriginalCampaign((Boolean) value);
                // MouseConfine applies at once: the cursor confinement reads it every frame.
                case MOUSE_CONFINE -> this.launch.setNoGrab(!(Boolean) value);
                case KEY_SCROLL -> this.interfaceSettings.setKeyScroll((Integer) value);
                case MOUSE_SCROLL -> this.interfaceSettings.setMouseScroll((Integer) value);
                // The pointer speed and the cursor surface follow these every frame.
                case USE_MOUSE_SENSITIVITY -> this.interfaceSettings.setMouseSensitivityEnabled((Boolean) value);
                case MOUSE_SENSITIVITY -> this.interfaceSettings.setMouseSensitivity((Integer) value);
                case MOUSE_SCALING -> this.interfaceSettings.setMouseScaling((Boolean) value);
                case HARDWARE_CURSOR -> this.interfaceSettings.setHardwareCursor((Boolean) value);
                case SHOW_FRAME_RATE -> this.interfaceSettings.setFrameRateShown((Boolean) value);
                case SHOW_TURN_RATE -> this.interfaceSettings.setTurnRateShown((Boolean) value);
                case GAME_TIMER -> this.interfaceSettings.setGameTimer((Boolean) value);
                case TIP_NUMBER -> this.interfaceSettings.setTipNumber((Integer) value);
                case SHOW_TIPS -> this.interfaceSettings.setTipsShown((Boolean) value);
                // The palette step, the portrait and the HD console layout read these every frame.
                case COLOR_CYCLING -> this.interfaceSettings.setColorCycling((Boolean) value);
                case UNIT_PORTRAITS -> this.interfaceSettings.setUnitPortraits((Integer) value);
                case CONSOLE_SPLIT -> this.interfaceSettings.setConsoleSplit((Boolean) value);
                // The latency belongs to the running game: it is staged and sent, never written to the file.
                case LATENCY -> {
                    this.latencyStage.accept((Integer) value);

                    return;
                }
                // The console APM and its alert read these every frame.
                case SHOW_ACTIONS_PER_MINUTE -> this.interfaceSettings.setActionsPerMinuteShown((Boolean) value);
                case ACTIONS_PER_MINUTE_ALERT -> this.interfaceSettings.setActionsPerMinuteAlertEnabled((Boolean) value);
                case ACTIONS_PER_MINUTE_ALERT_VALUE -> this.interfaceSettings.setActionsPerMinuteAlertThreshold((Integer) value);
                case ACTIONS_PER_MINUTE_ALERT_COLOR -> this.interfaceSettings.setActionsPerMinuteAlertColorEnabled((Boolean) value);
                case ACTIONS_PER_MINUTE_ALERT_SOUND -> this.interfaceSettings.setActionsPerMinuteAlertSoundEnabled((Boolean) value);
                // The next game builds its hotkey profile from these (Main, at the session start).
                case HOTKEY_PROFILE -> this.launch.setHotkeys((String) value, this.launch.getHotkeys());
                case HOTKEYS -> this.launch.setHotkeys(this.launch.getHotkeyProfile(), HotkeyBindingsUtils.parse((String) value));
                // Rebuild the selected image set when real-time lighting changes, as a graphics selection does.
                case HD_LIGHTING -> {
                    this.options.put(key, value);

                    if ((Boolean) value != this.launch.isHdLighting()) {
                        this.launch.setHdLighting((Boolean) value);
                        this.selection.select(this.selection.getQuality());
                    }
                }
                default -> {
                    if (!this.options.containsKey(key) && !key.equals(LOCALE)) {
                        return;
                    }

                    this.options.put(key, value);
                }
            }

            if (this.file == null) {
                return;
            }

            int separator = key.indexOf('.');

            try {
                SettingsStore.write(this.file, key.substring(0, separator), Map.of(key.substring(separator + 1), value));
            } catch (IOException | RuntimeException exception) {
                LOGGER.warning("SETTINGS NOT WRITTEN: " + this.file + ": " + exception.getMessage());
            }
        }

        /**
         * Returns whether an option can be changed now: the options page disables some controls by the client's state.
         *
         * @param key the key as {@code group.key}
         * @return {@code true} if the option's control is enabled, {@code false} otherwise
         */
        @Override
        public boolean isOptionEnabled(@NotNull String key) {
            // The Video page enables Gamma (and Monitor) only for WindowMode 2, full screen in a video mode.
            if (key.equals(GAMMA)) {
                return this.graphics.getWindowMode() == GraphicsSettings.FULLSCREEN;
            }

            return this.availability.test(key);
        }

        /**
         * @return the game type identifier last chosen on the create screen ({@code 2} Melee, {@code 3} Free For All, {@code 10} Use Map Settings), or {@link #NONE}
         */
        @Override
        public int getCreateGameType() {
            return this.gameType;
        }

        /**
         * Sets the game type chosen on the create screen.
         *
         * @param gameType the game type identifier
         */
        @Override
        public void setCreateGameType(int gameType) {
            if (gameType == this.gameType) {
                return;
            }

            this.gameType = gameType;

            if (this.file == null) {
                return;
            }

            this.writeCreateGame(this.file, TYPE, gameType);
        }

        /**
         * Reads a remembered create-screen subtype, speed or turn rate value independently of the game type.
         *
         * @param key {@link #SUBTYPE}, {@link #SPEED} or {@link #TURN_RATE}
         * @return the value, or {@link #NONE}
         */
        @Override
        public int getCreateGameValue(@NotNull String key) {
            return this.createGame.getOrDefault(key, NONE);
        }

        /**
         * Sets a create screen value other than the game type.
         *
         * @param key   {@link #SUBTYPE}, {@link #SPEED} or {@link #TURN_RATE}
         * @param value the value
         */
        @Override
        public void setCreateGameValue(@NotNull String key, int value) {
            Integer previous = this.createGame.put(key, value);

            if (previous != null && previous == value || this.file == null) {
                return;
            }

            this.writeCreateGame(this.file, key, value);
        }

        /**
         * Sets the test of the options that are enabled only in some client states.
         *
         * @param availability the test by settings key
         */
        private void setAvailability(@NotNull Predicate<String> availability) {
            this.availability = availability;
        }

        /**
         * Sets the latency access of the Network tab: the running game's latency.
         *
         * @param access the shown latency, or {@code -1} outside a game
         * @param stage  takes a chosen latency
         */
        private void setLatencyAccess(@NotNull IntSupplier access, @NotNull IntConsumer stage) {
            this.latencyAccess = access;
            this.latencyStage  = stage;
        }

        /**
         * Writes one value of the settings file's {@code createGame} group; a failed write is logged.
         *
         * @param file  the settings file
         * @param key   the key in the group
         * @param value the value
         */
        private void writeCreateGame(@NotNull Path file, @NotNull String key, int value) {
            try {
                SettingsStore.write(file, GROUP, Map.of(key, value));
            } catch (IOException | RuntimeException exception) {
                LOGGER.warning("SETTINGS NOT WRITTEN: " + file + ": " + exception.getMessage());
            }
        }
    }

    private final @NotNull ClientOptions options;
    private final @NotNull FileSettings settings;

    private @Nullable GlueInterface glue;
    private boolean unavailable;

    private @Nullable String pendingError;
    private @Nullable GlueResume resume;
    private int gluePalette;

    /**
     * Creates a {@link GlueState} instance.
     *
     * @param options  the launch options (install folders, map compatibility)
     * @param graphics the client's graphics selection, which the options' HD graphics setting applies through
     */
    GlueState(@NotNull ClientOptions options, @NotNull GraphicsSelection graphics) {
        this.options  = options;
        this.settings = new FileSettings(options, graphics);

        this.glue        = null;
        this.unavailable = false;

        this.pendingError = null;
        this.resume       = null;
        this.gluePalette  = 0;
    }

    /**
     * @return {@code true} if the menus are open, {@code false} otherwise
     */
    boolean isOpen() {
        return this.glue != null;
    }

    /**
     * @return {@code true} if opening the menus failed and is not retried, {@code false} otherwise
     */
    boolean isUnavailable() {
        return this.unavailable;
    }

    /**
     * @return {@code true} if the menus show their empty screen over a game, {@code false} otherwise
     */
    boolean isOverGame() {
        return this.glue != null && this.glue.isOverGame();
    }

    /**
     * @return the canvas of the open menus, with the interface placement of their last drawn frame, or {@code null} while they are closed
     */
    @Nullable GlueCanvas getCanvas() {
        return this.glue == null ? null : this.glue.getCanvas();
    }

    /**
     * Sets the display mode, which is applied and persisted through the shared settings writer.
     *
     * @param windowMode the selected display mode
     */
    void setWindowMode(int windowMode) {
        this.settings.setOption(FileSettings.WINDOW_MODE, windowMode);
    }

    /**
     * Selects the graphics, which are applied and persisted as the options popup's HD graphics are.
     *
     * @param choice the graphics
     */
    void selectGraphics(@NotNull GraphicsChoice choice) {
        this.settings.setOption(FileSettings.RENDERER, choice.getName());
    }

    /**
     * Selects the graphics after the shown ones in the F5 cycle, applied and persisted as {@link #selectGraphics} does.
     *
     * @return the selected graphics
     */
    @NotNull GraphicsChoice cycleGraphics() {
        GraphicsChoice next = GraphicsChoice.of(this.settings.selection.getQuality()).getNext();

        this.selectGraphics(next);

        return next;
    }

    /**
     * Sets the latency access of the options popup's Network tab: the running game's latency ({@code DefaultLatency}).
     *
     * @param access the latency the tab shows, or {@code -1} when no game runs (the tab is not listed)
     * @param stage  takes the chosen latency, which the game sends when the popup closes
     */
    void setLatencyAccess(@NotNull IntSupplier access, @NotNull IntConsumer stage) {
        this.settings.setLatencyAccess(access, stage);
    }

    /**
     * Sets the test of the options that are enabled only in some client states ({@link GlueSettings#isOptionEnabled}).
     *
     * @param availability the test by settings key
     */
    void setOptionAvailability(@NotNull Predicate<String> availability) {
        this.settings.setAvailability(availability);
    }

    /**
     * @return the current or staged menu palette identifier, or {@code 0} before any palette is set
     */
    int getGluePalette() {
        return this.glue == null ? this.gluePalette : this.glue.getGluePalette();
    }

    /**
     * Sets the staged palette identifier and applies it to open menus.
     *
     * @param palette the palette identifier
     */
    void setGluePalette(int palette) {
        this.gluePalette = palette;

        if (this.glue != null) {
            this.glue.setGluePalette(palette);
        }
    }

    /**
     * Sets the framebuffer used by the menu layer.
     *
     * @param framebuffer the framebuffer, or {@code 0} for the window
     * @param width       the framebuffer width
     * @param height      the framebuffer height
     */
    void setTargetFramebuffer(int framebuffer, int width, int height) {
        if (this.glue != null) {
            this.glue.setTargetFramebuffer(framebuffer, width, height);
        }
    }

    /**
     * @return {@code true} if a modal menu popup is open, {@code false} otherwise
     */
    boolean isPopupOpen() {
        return this.glue != null && this.glue.isPopupOpen();
    }

    /**
     * @return the open glue popups, bottom first, as modal layers of the client's dialog list (none when the glue is closed)
     */
    @NotNull List<ScrDialogLayer> getPopupLayers() {
        return this.glue == null ? List.of() : this.glue.getPopupLayers();
    }

    /**
     * Sets the screen the glue opens at next ({@link GlueResume}).
     *
     * @param next the resume point
     */
    void setResume(@NotNull GlueResume next) {
        this.resume = next;
    }

    /**
     * @return {@code true} if the menus requested program exit, {@code false} otherwise
     */
    boolean isExitRequested() {
        return this.glue != null && this.glue.isExitRequested();
    }


    /**
     * Applies an edit operation to the focused edit of the menus, if any.
     *
     * @param operation the edit operation
     */
    void applyEditOperation(@NotNull TextEditOperation operation) {
        if (this.glue != null) {
            this.glue.applyEditOperation(operation);
        }
    }

    /**
     * Opens the glue on the client's resources, at the resume screen when a game has ended.
     *
     * @param client the client resources
     * @param locale the text locale
     * @throws IOException if the glue assets cannot be read; the glue is then unavailable
     */
    void open(@NotNull ClientContext client, @NotNull String locale) throws IOException {
        InstallLayout layout = this.options.getInstallLayout();
        GlueInterface opened;

        try {
            GlueFolders folders = new GlueFolders(layout.getCharacterDirectory(), layout.getMapDirectory(), layout.getReplayDirectory(),
                                                  layout.getSaveDirectory(), layout.getRecentMapsFile());

            opened = GlueInterface.open(client, locale, folders, this.resume, this.settings);
        } catch (IOException | RuntimeException exception) {
            this.unavailable = true;

            throw exception;
        }

        this.glue   = opened;
        this.resume = null;

        opened.setMapCompatibility(this.options.isMapCompatibility());

        String screen = System.getProperty(GLUE_SCREEN_PROPERTY);

        if (screen != null) {
            if (Boolean.getBoolean(GLUE_EXPANSION_PROPERTY)) {
                opened.setExpansion(true);
            }

            opened.showScreen(GlueNavigator.Destination.valueOf(screen));
        }
    }

    /**
     * Switches the open glue to its empty screen over the game when a game takes the window.
     */
    void enterGame() {
        if (this.glue != null && !this.glue.isOverGame()) {
            this.glue.enterGame();
        }
    }

    /**
     * Shows the glue menus again after a game has ended, at the resume screen set by {@link #setResume} (the main menu when none was set).
     */
    void leaveGame() {
        GlueInterface menus = this.glue;

        if (menus == null || !menus.isOverGame()) {
            return;
        }

        GlueResume next = this.resume == null ? GlueResume.createAfterGame(null, false) : this.resume;

        this.resume = null;

        menus.leaveGame(next);
    }

    /**
     * Opens the options popup over the current screen (the main menu's Options, the game menu's Options).
     */
    void showOptions() {
        if (this.glue != null) {
            this.glue.showOptions();
        }
    }

    /**
     * Opens the game's save dialog over the game ({@link GlueInterface#showSaveGame}).
     *
     * @param target      the running game's saved games
     * @param consoleRace the console race of the game
     * @param result      receives {@code true} once Save sent the Save Game command, {@code false} on Cancel
     */
    void showSaveGame(GlueSavePopup.@NotNull Target target, int consoleRace, @NotNull Consumer<Boolean> result) {
        if (this.glue != null) {
            this.glue.showSaveGame(target, consoleRace, result);
        }
    }

    /**
     * Opens the game's load dialog over the game ({@link GlueInterface#showLoadGame}).
     *
     * @param source      the running game's saved games
     * @param consoleRace the console race of the game
     * @param result      receives {@code true} once Load sent the Load Game command, {@code false} on Cancel
     */
    void showLoadGame(GlueLoadPopup.@NotNull Source source, int consoleRace, @NotNull Consumer<Boolean> result) {
        if (this.glue != null) {
            this.glue.showLoadGame(source, consoleRace, result);
        }
    }

    /**
     * Toggles and persists music playback.
     *
     * @return {@code true} if music is now enabled, {@code false} otherwise
     */
    boolean toggleMusicEnabled() {
        boolean enabled = !this.options.getSoundSettings().isMusicEnabled();

        this.settings.setOption(FileSettings.MUSIC_ENABLED, enabled);

        return enabled;
    }

    /**
     * Toggles and persists sound-effect playback.
     *
     * @return {@code true} if effects are now enabled, {@code false} otherwise
     */
    boolean toggleSoundEffectsEnabled() {
        boolean enabled = !this.options.getSoundSettings().isSoundEffectsEnabled();

        this.settings.setOption(FileSettings.SOUND_EFFECTS_ENABLED, enabled);

        return enabled;
    }

    /**
     * Toggles and persists cursor confinement.
     *
     * @return {@code true} if the cursor is now confined, {@code false} otherwise
     */
    boolean toggleMouseConfine() {
        boolean confined = this.options.isNoGrab();

        this.settings.setOption(FileSettings.MOUSE_CONFINE, confined);

        return confined;
    }

    /**
     * Toggles and persists the frame-rate display.
     *
     * @return {@code true} if the display is enabled, {@code false} otherwise
     */
    boolean toggleShowFrameRate() {
        boolean shown = !this.options.getInterfaceSettings().isFrameRateShown();

        this.settings.setOption(FileSettings.SHOW_FRAME_RATE, shown);

        return shown;
    }

    /**
     * Toggles and persists the turn-rate display.
     *
     * @return {@code true} if the display is enabled, {@code false} otherwise
     */
    boolean toggleShowTurnRate() {
        boolean shown = !this.options.getInterfaceSettings().isTurnRateShown();

        this.settings.setOption(FileSettings.SHOW_TURN_RATE, shown);

        return shown;
    }

    /**
     * Persists changed tip-page state.
     *
     * @param tipNumber the last shown tip
     * @param showTips  whether tips open at mission start
     */
    void storeTips(int tipNumber, boolean showTips) {
        if (tipNumber != this.options.getInterfaceSettings().getTipNumber()) {
            this.settings.setOption(FileSettings.TIP_NUMBER, tipNumber);
        }

        if (showTips != this.options.getInterfaceSettings().isTipsShown()) {
            this.settings.setOption(FileSettings.SHOW_TIPS, showTips);
        }
    }

    /**
     * Opens the fatal notice over the current menu screen.
     *
     * @param networkKey the message key
     * @param detail     the optional second string
     * @param fonts      the game fonts
     */
    void showFatal(@NotNull String networkKey, @Nullable String detail, @NotNull InterfaceFonts fonts) {
        if (this.glue != null) {
            this.glue.showFatal(networkKey, detail, fonts);
        }
    }

    /**
     * Stages the replay-load error to display on the next menu frame.
     */
    void showReplayLoadError() {
        this.pendingError = REPLAY_LOAD_ERROR;
    }

    /**
     * Draws a glue frame.
     *
     * @param qualityPrefix   the glue asset set prefix
     * @param surface         the interface surface of the frame
     * @param time            the interface clock in milliseconds
     * @param pointerX        the pointer's framebuffer x
     * @param pointerY        the pointer's framebuffer y
     * @param pointerInside   whether the pointer is over the window (the drawn cursor shows)
     * @param loading         whether the client's loading runs (the title screen waits for it)
     * @param loadingCaption  what loads, for example {@code HD2}, or {@code null}
     * @param loadingProgress the loading progress {@code 0..1} while {@code loading}
     * @return the game the glue asked for in this frame, or {@code null}
     */
    @Nullable GlueGameRequest draw(@NotNull String qualityPrefix,
                                   @NotNull UiSurface surface,
                                   long time,
                                   float pointerX, float pointerY, boolean pointerInside,
                                   boolean loading, @Nullable String loadingCaption, float loadingProgress) {
        GlueInterface menus = this.glue;

        if (menus == null) {
            return null;
        }

        if (this.pendingError != null) {
            menus.showError(this.pendingError);
            this.pendingError = null;
        }

        menus.setQualityPrefix(qualityPrefix);
        menus.setClientLoading(loading);
        menus.setLoading(loadingCaption, loading ? loadingProgress : -1);
        menus.setPointer(pointerX, pointerY, pointerInside);
        menus.draw(surface, time);

        return menus.takeGameRequest();
    }

    /**
     * Handles one input event: the open glue takes it ({@link GlueInterface#handleInput}).
     *
     * @param event the event
     */
    @Override
    public void onInput(@NotNull InputEvent event) {
        if (this.glue != null) {
            this.glue.handleInput(event);
        }
    }

    /**
     * Closes the glue at the end of the run; the resume screen and a pending error are kept.
     */
    void close() {
        if (this.glue != null) {
            this.glue.close();
        }

        this.glue = null;
    }
}
