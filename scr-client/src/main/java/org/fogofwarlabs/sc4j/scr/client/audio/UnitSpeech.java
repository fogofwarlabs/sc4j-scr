package org.fogofwarlabs.sc4j.scr.client.audio;

import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.client.world.ScrPresentation;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.session.LocalInterfaceEffects;
import org.fogofwarlabs.sc4j.scr.game.state.RuntimeRandom;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Selects unit response sounds and talking portraits, with local response counters; a random response is drawn from the
 * game's C runtime generator ({@link ScrPresentation#getRuntimeRandom}), which the creep tile variants and the game's other C runtime
 * users share.
 */
public final class UnitSpeech {
    private static final int ZERG_SOUND_RACE = 0;
    private static final int TERRAN_SOUND_RACE = 1;
    private static final int PROTOSS_SOUND_RACE = 2;

    private static final int SELECTION_GUARD_TICKS = 10;
    private static final int NORMAL_SELECTION_CLICKS = 3;
    private static final int CRITTER_EXPLOSION_CLICKS = 10;
    private static final int ATTACK_ALERT_COOLDOWN_TICKS = 100;
    private static final int MARK_RELOAD_TICKS = 1;

    private static final int MAXIMUM_SOUND_TYPE_IDENTIFIER = 105;
    private static final int MIDDLE_HEALTH_MINIMUM_PERCENT = 34;
    private static final int MIDDLE_HEALTH_MAXIMUM_PERCENT = 66;
    private static final int UNCLASSIFIED_SOUND_RACE = 4;

    private static final int SOUND_CHOICE_RANDOM_SHIFT = 8;

    private static final int MILLISECONDS_PER_SECOND = 1000;
    private static final int DEFAULT_TALK_MILLISECONDS = 1000;

    private static final long TICK_MILLISECONDS = 100;

    /**
     * The sound table names of the responses; the race sounds start with the Zerg one, followed by Terran and Protoss.
     */
    private static final @NotNull String BUTTON_SOUND = "SND_BUTTON";
    private static final @NotNull String BUILDING_HELP_SOUND = "SND_Z_BLDG_HELP";
    private static final @NotNull String ENEMY_NEAR_SOUND = "SND_Z_ENEMY_NEAR";
    private static final @NotNull String LARGE_FIRE_SOUND = "SND_BIG_ON_FIRE";
    private static final @NotNull String SMALL_FIRE_SOUND = "SND_ON_FIRE_SMALL";
    private static final @NotNull String DISABLED_SOUND = "SND_Z_DISABLED";

    /**
     * Receives sound and talking-portrait requests.
     */
    public interface Output {
        /**
         * Requests an interface sound when enabled.
         *
         * @param sound   the sound index
         * @param unit    the speaking unit, or {@code null}
         * @param enabled whether the caller enables this response
         * @return {@code true} if the sound started, {@code false} otherwise
         */
        boolean play(int sound, @Nullable Unit unit, boolean enabled);

        /**
         * @param sound the sound index
         * @return the decoded length in milliseconds, {@code 0} if unknown
         */
        int getLength(int sound);

        /**
         * Requests a talking portrait for the unit.
         *
         * @param unit                 the speaking unit
         * @param durationMilliseconds the talk duration in milliseconds
         */
        void talk(@NotNull Unit unit, long durationMilliseconds);

        /**
         * Receives the single-player critter effect callback.
         *
         * @param unit the critter
         */
        default void explodeCritter(@NotNull Unit unit) {
        }
    }

    /**
     * Selects the first highest-rank enabled unit, or the highest-rank unit if all are disabled.
     *
     * @param view  the game view
     * @param units the ordered selection
     * @return the speaking unit, or {@code null} for an empty selection
     * @throws NullPointerException if the world was built without game data
     */
    public static @Nullable Unit findSelectionSpeaker(@NotNull ScrGameView view, @NotNull List<Unit> units) {
        ScrUnitTypeView table = view.requireGameData().getUnitTypes();
        Unit best = null;

        for (Unit unit : units) {
            if (best == null) {
                best = unit;

                continue;
            }

            boolean bestDisabled = UnitOrderEligibility.isDisabled(best);
            boolean disabled = UnitOrderEligibility.isDisabled(unit);

            if (bestDisabled && !disabled) {
                best = unit;

                continue;
            }

            if (!bestDisabled && disabled) {
                continue;
            }

            if (UnitQueries.getRank(table, unit) > UnitQueries.getRank(table, best)) {
                best = unit;
            }
        }

        return best;
    }

    /**
     * @param unitTypeIdentifier the unit type
     * @return {@code true} if the unit type is one of the supported critters, {@code false} otherwise
     */
    private static boolean isCritter(int unitTypeIdentifier) {
        return unitTypeIdentifier == ScrUnitIds.CRITTER_RHYNADON ||
               unitTypeIdentifier == ScrUnitIds.CRITTER_BENGALAAS ||
               unitTypeIdentifier >= ScrUnitIds.CRITTER_SCANTID && unitTypeIdentifier <= ScrUnitIds.CRITTER_URSADON;
    }

    private static boolean isEligibleForResponse(@NotNull ScrUnitTypeView units, @NotNull Unit unit, int localPlayer) {
        if (unit.getOwnerIndex() == localPlayer) {
            return true;
        }

        int unitTypeIdentifier = unit.getUnitId();
        boolean resource = units.isMineralField(unitTypeIdentifier) || unitTypeIdentifier == ScrUnitIds.RESOURCE_VESPENE_GEYSER;

        return unit.getOwnerIndex() == PlayerTable.NEUTRAL_PLAYER_ID && (resource || isCritter(unitTypeIdentifier));
    }


    private final @NotNull SoundTable sounds;
    private final @NotNull SoundSettings settings;

    private long lastTickMilliseconds;

    private @Nullable Unit guardUnit;
    private int guardCounter;
    private int clickCount;
    private int lastSoundIdentifier;

    private int alertCooldown;
    /**
     * The reload counter for attack-alert area marks.
     */
    private int markCounter;

    /**
     * Creates a {@link UnitSpeech} instance with default sound options.
     *
     * @param sounds the sound table
     */
    public UnitSpeech(@NotNull SoundTable sounds) {
        this(sounds, new SoundSettings());
    }

    /**
     * Creates a {@link UnitSpeech} instance.
     *
     * @param sounds   the sound table
     * @param settings the sound options read at each response
     */
    public UnitSpeech(@NotNull SoundTable sounds, @NotNull SoundSettings settings) {
        this.sounds   = sounds;
        this.settings = settings;

        this.lastTickMilliseconds = Long.MIN_VALUE;

        this.guardUnit           = null;
        this.guardCounter        = 0;
        this.clickCount          = 0;
        this.lastSoundIdentifier = 0;

        this.alertCooldown = 0;
        this.markCounter   = 0;
    }

    /**
     * @return {@code true} if the attack-alert cooldown is running, {@code false} otherwise
     */
    public boolean isAlertCoolingDown() {
        return this.alertCooldown > 0;
    }

    /**
     * @param name a sound ID of {@code sfx.json}
     * @return the sound index, or {@code 0} when unknown
     */
    public int getSoundIdentifier(@NotNull String name) {
        return this.sounds.getSoundIdentifier(name);
    }

    /**
     * Returns the sound length floored to whole seconds plus its adjustment, or the default duration if not positive.
     *
     * @param output the output that measures the decoded sound
     * @param sound  the sound index
     * @return the portrait duration in milliseconds
     */
    public long getTalkDurationMilliseconds(@NotNull Output output, int sound) {
        long duration = output.getLength(sound) / MILLISECONDS_PER_SECOND * (long) MILLISECONDS_PER_SECOND + this.sounds.getLengthAdjustment(sound);

        return duration <= 0 ? DEFAULT_TALK_MILLISECONDS : duration;
    }


    /**
     * Advances response counters and attack marks at most once per interface tick.
     *
     * @param nowMilliseconds a monotonic clock in milliseconds
     * @param effects         the current game interface effects, or {@code null}
     */
    public void tick(long nowMilliseconds, @Nullable LocalInterfaceEffects effects) {
        if (this.lastTickMilliseconds != Long.MIN_VALUE && nowMilliseconds - this.lastTickMilliseconds < TICK_MILLISECONDS) {
            return;
        }

        this.lastTickMilliseconds = nowMilliseconds;

        if (this.guardCounter > 0) {
            this.guardCounter--;
        }

        if (this.alertCooldown > 0) {
            this.alertCooldown--;
        }

        // Attack-alert marks count down every second tick.
        boolean due = this.markCounter == 0;

        this.markCounter--;

        if (!due) {
            return;
        }

        this.markCounter = MARK_RELOAD_TICKS;

        if (effects != null) {
            effects.decrementAttackAlertMarks();
        }
    }

    /**
     * Requests the selection response without the single-player critter effect.
     *
     * @param view         the game view
     * @param presentation the presentation state of the game: its mute flag and the generator the responses draw from
     * @param unit         the speaking unit, or {@code null}
     * @param localPlayer  the local player
     * @param replay       whether a replay is viewed
     * @param output       receives sound and portrait requests
     * @throws NullPointerException if the world was built without game data
     */
    public void requestSelectionResponse(@NotNull ScrGameView view, @NotNull ScrPresentation presentation, @Nullable Unit unit, int localPlayer,
                                         boolean replay, @NotNull Output output) {
        this.requestSelectionResponse(view, presentation, unit, localPlayer, replay, false, output);
    }

    /**
     * Requests the selection response and optional single-player critter effect.
     *
     * @param view         the game view
     * @param presentation the presentation state of the game: its mute flag and the generator the responses draw from
     * @param unit         the speaking unit, or {@code null}
     * @param localPlayer  the local player
     * @param replay       whether a replay is viewed
     * @param singlePlayer whether the game is single player
     * @param output       receives sound and portrait requests
     * @throws NullPointerException if the world was built without game data
     */
    public void requestSelectionResponse(@NotNull ScrGameView view, @NotNull ScrPresentation presentation,
                                         @Nullable Unit unit,
                                         int localPlayer,
                                         boolean replay, boolean singlePlayer,
                                         @NotNull Output output) {
        if (presentation.getInterfaceLink().getLocalInterface().getUnitSpeechMuted() != 0) {
            return;
        }

        int savedLastSoundIdentifier = this.lastSoundIdentifier;

        if (unit == null || replay || !isEligibleForResponse(view.requireGameData().getUnitTypes(), unit, localPlayer)) {
            this.guardUnit  = null;
            this.clickCount = 0;

            return;
        }

        if (unit == this.guardUnit && this.guardCounter != 0) {
            return;
        }

        this.guardCounter = SELECTION_GUARD_TICKS;

        if (unit != this.guardUnit) {
            this.guardUnit  = unit;
            this.clickCount = 0;
        }

        ScrUnitTypeView units = view.requireGameData().getUnitTypes();
        int unitTypeIdentifier = unit.getUnitId();
        boolean building = UnitQueries.isBuilding(units, unitTypeIdentifier);
        int sound;

        if (building && (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
            sound = this.chooseBuildingResponseSoundIdentifier(view, presentation, unit);
        } else {
            int clicks = Math.max(0, this.clickCount - NORMAL_SELECTION_CLICKS);

            sound = UnitOrderEligibility.isDisabled(unit)
                    ? this.getSoundIdentifier(BUTTON_SOUND)
                    : this.chooseSelectionResponseSoundIdentifier(presentation.getRuntimeRandom(), units, unitTypeIdentifier, clicks);

            if (singlePlayer && isCritter(unitTypeIdentifier) && clicks >= CRITTER_EXPLOSION_CLICKS) {
                output.explodeCritter(unit);
            }
        }

        if (sound == 0) {
            return;
        }

        boolean enabled = building ? this.settings.isBuildingSounds() : this.settings.isUnitAcknowledgments();
        boolean played = output.play(sound, unit, enabled);

        if (building) {
            if (!played) {
                this.lastSoundIdentifier = savedLastSoundIdentifier;
            }

            return;
        }

        if (enabled) {
            output.talk(unit, this.getTalkDurationMilliseconds(output, sound));
        }

        if (played) {
            this.clickCount++;
        } else {
            this.lastSoundIdentifier = savedLastSoundIdentifier;
        }
    }

    /**
     * Requests the active unit's order acknowledgement.
     *
     * @param view         the game view
     * @param presentation the presentation state of the game: its mute flag and the generator the responses draw from
     * @param unit         the active unit
     * @param localPlayer  the local player
     * @param replay       whether a replay is viewed
     * @param output       receives sound and portrait requests
     * @throws NullPointerException if the world was built without game data
     */
    public void requestOrderAcknowledgment(@NotNull ScrGameView view, @NotNull ScrPresentation presentation, @NotNull Unit unit, int localPlayer,
                                           boolean replay, @NotNull Output output) {
        if (presentation.getInterfaceLink().getLocalInterface().getUnitSpeechMuted() != 0) {
            return;
        }

        this.guardUnit = null;

        if (replay || unit.getOwnerIndex() != localPlayer || UnitOrderEligibility.isDisabled(unit)) {
            return;
        }

        int savedLastSoundIdentifier = this.lastSoundIdentifier;

        ScrUnitTypeView units = view.requireGameData().getUnitTypes();
        int unitTypeIdentifier = unit.getUnitId();
        int start = unitTypeIdentifier <= MAXIMUM_SOUND_TYPE_IDENTIFIER ? units.getYesSoundStart(unitTypeIdentifier) : 0;
        int sound = start != 0 ? this.chooseResponseSoundIdentifier(presentation.getRuntimeRandom(), start, units.getYesSoundEnd(unitTypeIdentifier))
                               : 0;

        if (sound != 0 && output.play(sound, unit, this.settings.isUnitAcknowledgments())) {
            output.talk(unit, this.getTalkDurationMilliseconds(output, sound));
        } else {
            this.lastSoundIdentifier = savedLastSoundIdentifier;
        }
    }

    /**
     * Starts the alert cooldown and requests the race warning and damaged unit's portrait.
     *
     * @param unit   the damaged unit
     * @param race   the console race index
     * @param output receives sound and portrait requests
     */
    public void requestAttackAlert(@NotNull Unit unit, int race, @NotNull Output output) {
        this.alertCooldown = ATTACK_ALERT_COOLDOWN_TICKS;

        boolean building = UnitQueries.isGroundedBuilding(unit);
        int sound = this.getSoundIdentifier(building ? BUILDING_HELP_SOUND : ENEMY_NEAR_SOUND) +
                    Math.clamp(race, ZERG_SOUND_RACE, PROTOSS_SOUND_RACE);

        // The portrait request does not depend on whether the sound started.
        output.play(sound, unit, this.settings.isUnitSpeech());
        output.talk(unit, this.getTalkDurationMilliseconds(output, sound));
    }

    /**
     * Selects a building response from its health, race and disabled state.
     *
     * @param view         the game view
     * @param presentation the presentation state of the game, whose generator the response draws from
     * @param unit         the selected completed building
     * @return the sound index, or {@code 0} for none
     * @throws NullPointerException if the world was built without game data
     */
    private int chooseBuildingResponseSoundIdentifier(@NotNull ScrGameView view, @NotNull ScrPresentation presentation, @NotNull Unit unit) {
        ScrUnitTypeView units = view.requireGameData().getUnitTypes();
        int unitTypeIdentifier = unit.getUnitId();
        boolean heavilyDamaged = view.getUnitQueries().isHeavilyDamaged(unit);
        int percent = view.getUnitQueries().getHitPointPercent(unit);
        int group = units.getGroupFlags(unitTypeIdentifier);

        if (heavilyDamaged && (group & UnitGroupFlags.TERRAN) != 0) {
            return this.getSoundIdentifier(LARGE_FIRE_SOUND);
        }

        if (heavilyDamaged && (group & UnitGroupFlags.PROTOSS) != 0) {
            return this.getSoundIdentifier(SMALL_FIRE_SOUND);
        }

        if (percent >= MIDDLE_HEALTH_MINIMUM_PERCENT && percent <= MIDDLE_HEALTH_MAXIMUM_PERCENT &&
            (group & (UnitGroupFlags.TERRAN | UnitGroupFlags.PROTOSS)) != 0) {
            return this.getSoundIdentifier(SMALL_FIRE_SOUND);
        }

        if (UnitOrderEligibility.isDisabled(unit)) {
            int race = (group & UnitGroupFlags.ZERG) != 0 ? ZERG_SOUND_RACE
                                                          : (group & UnitGroupFlags.PROTOSS) != 0 ? PROTOSS_SOUND_RACE
                                                                                                  : (group & UnitGroupFlags.TERRAN) != 0 ? TERRAN_SOUND_RACE : UNCLASSIFIED_SOUND_RACE;

            return this.getSoundIdentifier(DISABLED_SOUND) + race;
        }

        return this.chooseSelectionResponseSoundIdentifier(presentation.getRuntimeRandom(), units, unitTypeIdentifier, 0);
    }

    /**
     * Chooses an annoyed response or random selection response and updates repeated-click state.
     *
     * @param runtimeRandom      the game's C runtime generator
     * @param units              the unit type table
     * @param unitTypeIdentifier the selected unit type
     * @param clicks             the repeated clicks beyond the normal selection clicks
     * @return the sound index, or {@code 0} for none
     */
    private int chooseSelectionResponseSoundIdentifier(@NotNull RuntimeRandom runtimeRandom,
                                                       @NotNull ScrUnitTypeView units, int unitTypeIdentifier,
                                                       int clicks) {
        if (unitTypeIdentifier <= MAXIMUM_SOUND_TYPE_IDENTIFIER) {
            int annoyedResponseStart = units.getAnnoyedSoundStart(unitTypeIdentifier);

            if (annoyedResponseStart != 0) {
                int count = units.getAnnoyedSoundEnd(unitTypeIdentifier) - annoyedResponseStart + 1;

                if (clicks > count) {
                    this.clickCount = 0;
                } else if (clicks != 0) {
                    return annoyedResponseStart + clicks - 1;
                }
            }
        }

        if (!units.hasEntry(unitTypeIdentifier)) {
            return 0;
        }

        return this.chooseResponseSoundIdentifier(runtimeRandom,
                                                  units.getWhatSoundStart(unitTypeIdentifier), units.getWhatSoundEnd(unitTypeIdentifier));
    }

    /**
     * Chooses an inclusive sound index, avoiding the previous choice when the range has several values; only a range of
     * several values draws from the generator.
     *
     * @param runtimeRandom the game's C runtime generator
     * @param start         the first sound index
     * @param end           the last sound index
     * @return the chosen sound index
     */
    private int chooseResponseSoundIdentifier(@NotNull RuntimeRandom runtimeRandom, int start, int end) {
        if (start == end) {
            this.lastSoundIdentifier = start;

            return start;
        }

        int randomValue = runtimeRandom.draw();
        int value = start + (randomValue >> SOUND_CHOICE_RANDOM_SHIFT) % (end - start + 1);

        if (value == this.lastSoundIdentifier) {
            value++;

            if (value > end) {
                value = start;
            }
        }

        this.lastSoundIdentifier = value;

        return value;
    }
}
