package org.fogofwarlabs.sc4j.scr.client.ui.status;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.client.ui.command.CommandInput;
import org.fogofwarlabs.sc4j.scr.client.ui.command.InterfaceSelection;
import org.fogofwarlabs.sc4j.scr.client.ui.command.RequirementEvaluator;
import org.fogofwarlabs.sc4j.scr.client.ui.text.StatText;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.client.world.ScrPresentation;
import org.fogofwarlabs.sc4j.scr.content.*;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUpgradeTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerUpgrades;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Recomputes the shown selection-panel controls from the selected units and game state.
 * Each control carries text, progress, icon and enablement values keyed by its {@code statdata} identifier.
 * Single-unit dispatch and subviews retain their lookup and control-construction order; cargo and hit-point values are published to the local status
 * cache before their view is built.
 */
public final class StatusPanel {
    private static final int LARGE_CARGO_SPACE_SHIFT = 2;
    private static final int MEDIUM_CARGO_SPACE_SHIFT = 1;

    /**
     * The empty panel view mode.
     */
    public static final int NO_MODE = 0;
    private static final int MULTI_SELECTION_MODE = 1;
    private static final int UNIT_MODE = 2;
    private static final int BUILDING_STATS_MODE = 3;
    private static final int BUILDING_MODE = 4;
    private static final int PRODUCTION_MODE = 5;
    private static final int CONSTRUCTION_MODE = 6;
    /**
     * The research progress view mode.
     */
    public static final int RESEARCH_MODE = 7;
    /**
     * The upgrade progress view mode.
     */
    public static final int UPGRADE_MODE = 8;
    private static final int ADDON_MODE = 9;
    private static final int EGG_MODE = 10;
    private static final int CARGO_MODE = 11;
    private static final int SUPPLY_MODE = 12;
    private static final int NUCLEAR_MODE = 13;
    private static final int ITEM_MODE = 14;
    private static final int RESOURCE_MODE = 15;

    private static final int FIRST_MULTI_SELECTION_CONTROL = 33;
    private static final int FIRST_STAT_ICON_CONTROL = 9;
    private static final int STAT_ICON_COUNT = 4;
    private static final int STAT_ICON_STYLE = 0xC;
    private static final int UNIT_NAME_CONTROL = -5;
    private static final int HEALTH_CONTROL = -7;
    private static final int ENERGY_LIFETIME_CONTROL = -8;
    private static final int STATUS_CONTROL = -20;
    private static final int KILLS_CONTROL = -21;
    private static final int CONSTRUCTION_PROGRESS_CONTROL = 13;
    private static final int CONSTRUCTION_LABEL_CONTROL = -30;
    private static final int EGG_ADDON_PROGRESS_CONTROL = 16;
    private static final int EGG_ADDON_LABEL_CONTROL = -40;
    private static final int QUEUE_PROGRESS_CONTROL = 7;
    private static final int QUEUE_AUXILIARY_CONTROL = 8;
    private static final int QUEUE_LABEL_CONTROL = -15;
    private static final int RESEARCH_UPGRADE_PROGRESS_CONTROL = 14;
    private static final int RESEARCH_UPGRADE_LABEL_CONTROL = -35;
    private static final int SUPPLY_USED_CONTROL = -10;
    private static final int SUPPLY_PROVIDED_CONTROL = -11;
    private static final int SUPPLY_AVAILABLE_CONTROL = -12;
    private static final int SUPPLY_CAP_CONTROL = -13;
    private static final int HEALTH_FRACTION_BITS = 8;
    private static final int PERCENT_SCALE = 100;
    private static final int SUPPLY_POINTS_PER_UNIT = 2;
    /**
     * The supply fraction bits: supply counts are kept in half units.
     */
    static final int SUPPLY_FRACTION_BITS = 1;
    private static final int MAXIMUM_HEALTH_TEXT = 9999;
    private static final int MEDIUM_HEALTH_MINIMUM_PERCENT = 34;
    private static final int MEDIUM_HEALTH_MAXIMUM_PERCENT = 66;
    private static final int HALLUCINATION_LIFETIME = 1350;
    private static final int BROODLING_LIFETIME = 1800;
    private static final int LIFETIME_TEXT_SCALE = 10;
    private static final int DISABLED_LABEL_STRING = 828;
    private static final int CONSTRUCTION_LABEL_STRING = 799;
    private static final int ADDON_LABEL_STRING = 787;
    private static final int MORPH_LABEL_STRING = 771;
    private static final int ARCHON_LABEL_STRING = 790;
    private static final int HALLUCINATION_LABEL_STRING = 832;
    private static final int ACID_SPORE_LABEL_STRING = 1300;
    private static final int BLINDNESS_LABEL_STRING = 1299;
    private static final int PARASITE_LABEL_STRING = 827;
    private static final int DETECTOR_LABEL_STRING = 831;
    private static final int GANTRITHOR_RANK_STRING = 572;
    private static final int HERO_BATTLECRUISER_RANK_STRING = 571;
    private static final int KILLS_LABEL_STRING = 764;
    private static final int TRAINING_LABEL_STRING = 774;
    private static final int NUCLEAR_LABEL_STRING = 772;
    private static final int RESEARCH_LABEL_STRING = 768;
    private static final int UPGRADE_LABEL_STRING = 765;
    private static final int SUPPLY_USED_STRING = 821;
    private static final int SUPPLY_PROVIDED_STRING = 815;
    private static final int SUPPLY_AVAILABLE_STRING = 818;
    private static final int SUPPLY_CAP_STRING = 824;
    private static final int MINERALS_STRING = 796;
    private static final int GAS_STRING = 797;
    private static final int DEPLETED_GAS_STRING = 798;
    private static final int EMPTY_QUEUE_FIRST_ICON = 6;
    private static final int SINGLE_ICON_STYLE = 2;
    private static final int FIRST_MIDDLE_QUEUE_ICON_SLOT = 1;
    private static final int MIDDLE_QUEUE_ICON_STYLE = 4;
    private static final int NARROW_CARGO_CAPACITY_LIMIT = 7;
    private static final int NARROW_LARGE_CARGO_CONTROL = 20;
    private static final int WIDE_LARGE_CARGO_CONTROL = 18;
    private static final int NARROW_MEDIUM_CARGO_CONTROL = 22;
    private static final int WIDE_MEDIUM_CARGO_CONTROL = 21;
    private static final int NARROW_SMALL_CARGO_CONTROL = 27;
    private static final int WIDE_SMALL_CARGO_CONTROL = 25;
    private static final int LARGE_CARGO_SPACE = 4;
    private static final int MEDIUM_CARGO_SPACE = 2;
    private static final int SMALL_CARGO_SPACE = 1;
    private static final int LARGE_CARGO_STYLE = 0x11;
    private static final int MEDIUM_CARGO_STYLE = 0xF;
    private static final int SMALL_CARGO_STYLE = 0xD;
    private static final int HALLUCINATION_CARGO_STYLE_OFFSET = 0x12;
    private static final int HERO_CARGO_STYLE_OFFSET = 6;
    private static final int PARASITE_CARGO_STYLE_OFFSET = 0xC;

    /**
     * The command icon of the plasma shields stat icon.
     */
    private static final int PLASMA_SHIELDS_ICON = 0x136;
    /**
     * The {@code stat_txt} string of rank {@code 0}; the Terran rank text adds the rank ({@code 0x516 + rank}).
     */
    private static final int FIRST_RANK_STRING = 0x516;

    /**
     * Text color codes of the SC fonts used by the panel.
     */
    static final char COLOR_YELLOW = 3;
    static final char COLOR_WHITE = 4;
    static final char COLOR_RED = 6;
    static final char COLOR_GREEN = 7;

    /**
     * An icon shown in a control: {@code frame} of the command icons ({@code kind} {@code 3} unit, {@code 4} technology, {@code 5} upgrade, {@code 0}
     * weapon, {@code 1} armor upgrade, {@code 2} plasma shields, {@code 6} empty queue slot).
     */
    public static final class Icon {
        /**
         * A weapon; the subject is the {@code weapons.dat} id.
         */
        public static final int WEAPON = 0;
        /**
         * The armor upgrade; the subject is the {@code upgrades.dat} id.
         */
        public static final int ARMOR = 1;
        /**
         * The plasma shields upgrade; the subject is the {@code upgrades.dat} id.
         */
        public static final int SHIELDS = 2;
        /**
         * A unit type (queue slots, hangar and silo counts); the subject is the {@code units.dat} id.
         */
        public static final int UNIT = 3;
        /**
         * A technology in research; the subject is the {@code techdata.dat} id.
         */
        public static final int TECHNOLOGY = 4;
        /**
         * An upgrade in progress; the subject is the {@code upgrades.dat} id.
         */
        public static final int UPGRADE = 5;
        /**
         * An empty queue slot; the frame is a {@code cmdbtns} digit frame.
         */
        public static final int EMPTY_QUEUE_SLOT = 6;

        private final int frame;
        private final int kind;
        private final int subject;

        /**
         * Creates a {@link Icon} instance retaining the frame, kind and represented subject.
         *
         * @param frame   the icon frame
         * @param kind    the icon kind
         * @param subject the represented type identifier
         */
        Icon(int frame, int kind, int subject) {
            this.frame   = frame;
            this.kind    = kind;
            this.subject = subject;
        }

        /**
         * @return the icon frame
         */
        public int getFrame() {
            return this.frame;
        }

        /**
         * @return the icon kind
         */
        public int getKind() {
            return this.kind;
        }

        /**
         * @return the weapon, upgrade, technology or unit the icon stands for
         */
        public int getSubject() {
            return this.subject;
        }
    }

    /**
     * The state of one shown control.
     */
    public static final class Control {
        private @NotNull String text;
        private int value;
        private boolean enabled;

        private final int identifier;

        private @Nullable Icon icon;
        private @Nullable Unit unit;
        private int style;

        /**
         * Creates a {@link Control} instance enabled with empty text and no unit or icon.
         *
         * @param identifier the control identifier
         */
        Control(int identifier) {
            this.text    = "";
            this.value   = -1;
            this.enabled = true;

            this.identifier = identifier;

            this.icon  = null;
            this.unit  = null;
            this.style = 0;
        }

        /**
         * @return the {@code statdata.ui.json} control id
         */
        public int getIdentifier() {
            return this.identifier;
        }

        /**
         * @return the text, possibly holding SC text color codes (bytes {@code 1} to {@code 8})
         */
        public @NotNull String getText() {
            return this.text;
        }

        /**
         * @return the progress percent of a progress bar, or {@code -1}
         */
        public int getValue() {
            return this.value;
        }

        /**
         * @return the icon, or {@code null}
         */
        public @Nullable Icon getIcon() {
            return this.icon;
        }

        /**
         * @return {@code true} if the control is enabled, {@code false} otherwise
         */
        public boolean isEnabled() {
            return this.enabled;
        }

        /**
         * @return the unit shown by a cargo or multi-selection slot, or {@code null}
         */
        public @Nullable Unit getUnit() {
            return this.unit;
        }

        /**
         * @return the style selected for a stat icon, production slot or cargo wireframe
         */
        public int getStyle() {
            return this.style;
        }
    }


    /**
     * Reads a player's level of an upgrade for the status interface, the stat icons, their tooltips and the observer production: any player and
     * upgrade read the level byte they address ({@link PlayerUpgrades#getLevel}).
     *
     * @param view    the game view
     * @param owner   the player
     * @param upgrade the {@code upgrades.dat} identifier
     * @return the player's level, or {@code 0} without upgrade levels
     */
    public static int getUpgradeLevel(@NotNull ScrGameView view, int owner, int upgrade) {
        PlayerUpgrades upgrades = view.getPlayerTable().getPlayerUpgrades();

        return upgrades == null ? 0 : upgrades.getLevel(owner, upgrade);
    }


    private final @NotNull Map<Integer, Control> controls;
    private final @NotNull StatText text;

    private int mode;
    private @Nullable ScrGameView view;
    private @Nullable ScrPresentation presentation;
    private boolean replay;
    private int localPlayer;

    /**
     * Creates a {@link StatusPanel} instance.
     *
     * @param text the interface strings
     */
    public StatusPanel(@NotNull StatText text) {
        this.controls = new HashMap<>();
        this.text     = text;

        this.mode         = NO_MODE;
        this.view         = null;
        this.presentation = null;
        this.replay       = false;
        this.localPlayer  = 0;
    }

    /**
     * Returns the view mode byte: {@code 0} none, {@code 1} multi-selection, {@code 2} unit, {@code 3} building with stat icons, {@code 4} plain
     * building, {@code 5} production, {@code 6} construction, {@code 7} research, {@code 8} upgrade, {@code 9} add-on, {@code 10} egg,
     * {@code 11} cargo, {@code 12} supply, {@code 13} nuke, {@code 14} item, {@code 15} resource.
     *
     * @return the view mode byte
     */
    public int getMode() {
        return this.mode;
    }

    /**
     * @param identifier the control id
     * @return the shown control, or {@code null} while it is hidden
     */
    public @Nullable Control getControl(int identifier) {
        return this.controls.get(identifier);
    }

    /**
     * @return the shown controls by id
     */
    public @NotNull Map<Integer, Control> getControls() {
        return this.controls;
    }

    /**
     * Requires the game view assigned by the current panel update.
     *
     * @return the assigned game view
     * @throws IllegalStateException if no game view has been assigned
     */
    private @NotNull ScrGameView requireView() {
        ScrGameView current = this.view;

        if (current == null) {
            throw Exceptions.newIllegalStateException("The panel has no game state.");
        }

        return current;
    }

    /**
     * Requires the presentation state assigned by the current panel update.
     *
     * @return the assigned presentation state
     * @throws IllegalStateException if no presentation state has been assigned
     */
    private @NotNull ScrPresentation requirePresentation() {
        ScrPresentation current = this.presentation;

        if (current == null) {
            throw Exceptions.newIllegalStateException("The panel has no game state.");
        }

        return current;
    }

    /**
     * Gets a shown control, creating and publishing it when absent.
     *
     * @param identifier the control identifier
     * @return the shown control
     */
    private @NotNull Control getOrCreateControl(int identifier) {
        return this.controls.computeIfAbsent(identifier, Control::new);
    }

    /**
     * Checks whether the local player owns the unit or replay viewing is active.
     *
     * @param unit the displayed unit
     * @return {@code true} if replay viewing is active or the local player owns the unit, {@code false} otherwise
     */
    private boolean isOwnedOrReplay(@NotNull Unit unit) {
        return this.replay || unit.getOwnerIndex() == this.localPlayer;
    }

    /**
     * Checks the unit completion flag.
     *
     * @param unit the displayed unit
     * @return {@code true} if the unit has its completion flag, {@code false} otherwise
     */
    private boolean isCompleted(@NotNull Unit unit) {
        return (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0;
    }

    /**
     * Checks the unit hallucination flag.
     *
     * @param unit the displayed unit
     * @return {@code true} if the unit has its hallucination flag, {@code false} otherwise
     */
    private boolean isHallucination(@NotNull Unit unit) {
        return (unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0;
    }

    /**
     * Returns the displayed type of a unit ({@link UnitQueries#getDisplayedType}) from the game unit-type table.
     *
     * @param unit the unit
     * @return the displayed unit type
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private int getDisplayedType(@NotNull Unit unit) {
        return UnitQueries.getDisplayedType(this.requireView().requireGameData().getUnitTypes(), unit);
    }

    /**
     * Returns the unit display race from the game unit-type table.
     *
     * @param type the unit type
     * @return the display race
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private int getRace(int type) {
        return UnitRacesUtils.getDisplayRace(this.requireView().requireGameData().getUnitTypes(), type);
    }

    /**
     * Finds the order-target or current-build unit for the applicable construction, repair and production orders.
     *
     * @param unit the displayed unit
     * @return the unit under construction, repair or production, or {@code null} when no such order applies
     */
    private @Nullable Unit getProductionChild(@NotNull Unit unit) {
        int order = unit.getPrimaryOrder();

        if (order == ScrOrderIds.PLACE_BUILDING || order == ScrOrderIds.CONSTRUCTING_BUILDING || order == ScrOrderIds.REPAIR ||
            UnitQueries.isPlacingProtossBuilding(unit)) {
            return unit.getOrderTargetUnit();
        }

        int secondary = unit.getSecondaryOrder();

        if (secondary == ScrOrderIds.TRAIN || secondary == ScrOrderIds.TRAIN_FIGHTER || UnitQueries.isBuildingAddon(unit)) {
            return unit.getCurrentBuildUnit();
        }

        return null;
    }

    /**
     * Computes the build progress in percent from the build time of what the unit builds ({@link UnitQueries#getBuildTime}).
     *
     * @param unit the building unit
     * @return the build progress in percent
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private int getBuildPercent(@NotNull Unit unit) {
        int time = UnitQueries.getBuildTime(this.requireView().requireGameData().getUnitTypes(), unit);

        return time == 0 ? 0 : (time - unit.getRemainingBuildTime()) * PERCENT_SCALE / time;
    }

    /**
     * @param unit the displayed unit
     * @return the ground weapon identifier, falling back to the subunit
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private int getGroundWeapon(@NotNull Unit unit) {
        if (unit.getUnitId() == ScrUnitIds.ZERG_LURKER && (unit.getStatus() & UnitStatusFlags.BURROWED) == 0) {
            return ScrWeaponIds.NONE;
        }

        int weapon = this.requireView().requireGameData().getUnitTypes().getGroundWeapon(unit.getUnitId());
        Unit subunit = unit.getSubunit();

        return weapon == ScrWeaponIds.NONE && subunit != null
               ? this.requireView().requireGameData().getUnitTypes().getGroundWeapon(subunit.getUnitId())
               : weapon;
    }

    /**
     * @param unit the displayed unit
     * @return the displayed rank text, or an empty string when no rank is shown
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private @NotNull String getRankText(@NotNull Unit unit) {
        int type = unit.getUnitId();
        ScrUnitTypeView units = this.requireView().requireGameData().getUnitTypes();
        int flags = this.requireView().requireGameData().getUnitTypes().getFlags(type);

        if ((flags & UnitTypeFlags.POWERUP) != 0 || type == ScrUnitIds.TERRAN_CIVILIAN || type == ScrUnitIds.TERRAN_VULTURE_SPIDER_MINE) {
            return "";
        }

        if (type == ScrUnitIds.HERO_GANTRITHOR) {
            return units.getMapStringIndex(ScrUnitIds.HERO_GANTRITHOR) == 0 ? this.text.getString(this.view, GANTRITHOR_RANK_STRING) : "";
        }

        if ((type == ScrUnitIds.TERRAN_BATTLECRUISER || type == ScrUnitIds.HERO_HYPERION || type == ScrUnitIds.HERO_NORAD_II) &&
            (flags & UnitTypeFlags.HERO) != 0 && units.getMapStringIndex(type) != 0) {
            return this.text.getString(this.view, HERO_BATTLECRUISER_RANK_STRING);
        }

        if (this.getRace(type) == ScrRaceIds.TERRAN) {
            int stringIdentifier = FIRST_RANK_STRING + UnitQueries.getRank(units, unit);

            return this.text.getString(this.view, stringIdentifier);
        }

        return "";
    }


    /**
     * Recomputes the panel for a selection.
     *
     * @param view         the game view
     * @param presentation the presentation state of the game, whose local interface caches the shown values
     * @param selection    the interface selection
     * @param replay       whether replay viewing is active
     * @param localPlayer  the local player
     */
    public void update(@NotNull ScrGameView view, @NotNull ScrPresentation presentation, @NotNull InterfaceSelection selection, boolean replay,
                       int localPlayer) {
        this.view         = view;
        this.presentation = presentation;
        this.replay       = replay;
        this.localPlayer  = localPlayer;

        this.controls.clear();

        Unit active = selection.getActiveUnit();

        if (active == null) {
            this.mode = NO_MODE;

            return;
        }

        if (selection.getCount() != 1) {
            this.populateMultiSelectionView(selection);

            return;
        }

        // Single-unit panels publish the active unit hit points before building their controls.
        presentation.getInterfaceLink().getLocalInterface().setStatusPanelCacheEntry(0, active.getHitPoints());

        int type = active.getUnitId();

        switch (ScrUnitIds.requireValidOrAdded(type)) {
            case ScrUnitIds.ZERG_OVERLORD, ScrUnitIds.HERO_YGGDRASILL -> this.populateOverlordView(active);
            case ScrUnitIds.TERRAN_DROPSHIP, ScrUnitIds.PROTOSS_SHUTTLE, ScrUnitIds.TERRAN_BUNKER -> this.populateTransportView(active);
            case ScrUnitIds.PROTOSS_CARRIER, ScrUnitIds.HERO_WARBRINGER, ScrUnitIds.HERO_GANTRITHOR,
                 ScrUnitIds.PROTOSS_REAVER -> this.populateHangarView(active);
            case ScrUnitIds.ZERG_EGG, ScrUnitIds.ZERG_COCOON, ScrUnitIds.ZERG_LURKER_EGG -> this.populateEggView(active);
            case ScrUnitIds.PROTOSS_DARK_ARCHON, ScrUnitIds.PROTOSS_ARCHON -> this.populateArchonView(active);
            case ScrUnitIds.TERRAN_VULTURE_SPIDER_MINE, ScrUnitIds.POWERUP_URAJ_CRYSTAL, ScrUnitIds.POWERUP_KHALIS_CRYSTAL,
                 ScrUnitIds.SPECIAL_FLOOR_MISSILE_TRAP, ScrUnitIds.SPECIAL_FLOOR_GUN_TRAP, ScrUnitIds.SPECIAL_WALL_MISSILE_TRAP,
                 ScrUnitIds.SPECIAL_WALL_FLAME_TRAP, ScrUnitIds.SPECIAL_RIGHT_WALL_MISSILE_TRAP, ScrUnitIds.SPECIAL_RIGHT_WALL_FLAME_TRAP,
                 ScrUnitIds.POWERUP_FLAG, ScrUnitIds.POWERUP_YOUNG_CHRYSALIS, ScrUnitIds.POWERUP_PSI_EMITTER, ScrUnitIds.POWERUP_DATA_DISK,
                 ScrUnitIds.POWERUP_KHAYDARIN_CRYSTAL, ScrUnitIds.POWERUP_MINERAL_CLUSTER_TYPE_1, ScrUnitIds.POWERUP_MINERAL_CLUSTER_TYPE_2,
                 ScrUnitIds.POWERUP_PROTOSS_GAS_ORB_TYPE_1, ScrUnitIds.POWERUP_PROTOSS_GAS_ORB_TYPE_2, ScrUnitIds.POWERUP_ZERG_GAS_SAC_TYPE_1,
                 ScrUnitIds.POWERUP_ZERG_GAS_SAC_TYPE_2, ScrUnitIds.POWERUP_TERRAN_GAS_TANK_TYPE_1,
                 ScrUnitIds.POWERUP_TERRAN_GAS_TANK_TYPE_2 -> this.populateItemView(active);
            case ScrUnitIds.TERRAN_GOLIATH_TURRET, ScrUnitIds.TERRAN_NUCLEAR_MISSILE, ScrUnitIds.HERO_ALAN_SCHEZAR_TURRET,
                 ScrUnitIds.SPELL_SCANNER_SWEEP, ScrUnitIds.SPECIAL_CARGO_SHIP, ScrUnitIds.SPECIAL_MERCENARY_GUNSHIP, ScrUnitIds.SPELL_DISRUPTION_WEB,
                 ScrUnitIds.UNUSED_ZERG_MARKER, ScrUnitIds.UNUSED_TERRAN_MARKER, ScrUnitIds.UNUSED_PROTOSS_MARKER, ScrUnitIds.SPECIAL_FLOOR_HATCH,
                 ScrUnitIds.SPECIAL_UPPER_LEVEL_DOOR, ScrUnitIds.SPECIAL_RIGHT_UPPER_LEVEL_DOOR, ScrUnitIds.SPECIAL_PIT_DOOR,
                 ScrUnitIds.SPECIAL_RIGHT_PIT_DOOR, ScrUnitIds.PROTOSS_SCARAB, ScrUnitIds.SPECIAL_MAP_REVEALER, ScrUnitIds.UNUSED_TERRAN1,
                 ScrUnitIds.UNUSED_ZERG2, ScrUnitIds.UNUSED_PROTOSS1 -> this.populateEmptyView(active);
            default -> {
                if (type >= ScrUnitIds.TERRAN_COMMAND_CENTER && type <= ScrUnitIds.SPELL_DARK_SWARM ||
                    type == ScrUnitIds.SPECIAL_START_LOCATION) {
                    this.populateBuildingView(active);
                } else {
                    this.populateUnitView(active);
                }
            }
        }
    }

    /**
     * Populates the general single-unit view.
     *
     * @param unit the displayed unit
     */
    private void populateUnitView(@NotNull Unit unit) {
        this.mode = UNIT_MODE;

        this.populateStatIcons(unit);
        this.populateStatusLine(unit);
        this.populateKillsLine(unit);
        this.populateHealthLine(unit);
        this.populateNameLine(unit);
    }

    /**
     * Dispatches the building controls and then adds shared status lines.
     *
     * @param unit the displayed unit
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private void populateBuildingView(@NotNull Unit unit) {
        int race = this.getRace(unit.getUnitId());
        int type = unit.getUnitId();

        if (this.requireView().requireGameData().getUnitTypes().isMineralField(type) || type == ScrUnitIds.RESOURCE_VESPENE_GEYSER) {
            this.populateResourceView(unit);
        } else if (!this.isOwnedOrReplay(unit)) {
            this.mode = BUILDING_MODE;
        } else if (!this.isCompleted(unit)) {
            this.mode = CONSTRUCTION_MODE;

            this.getOrCreateControl(CONSTRUCTION_PROGRESS_CONTROL).value = this.getBuildPercent(unit);
            this.getOrCreateControl(CONSTRUCTION_LABEL_CONTROL).text     = this.text.getString(this.view, CONSTRUCTION_LABEL_STRING + race);
        } else if (type == ScrUnitIds.TERRAN_NUCLEAR_SILO) {
            this.populateNuclearSiloView(unit);
        } else if (this.requireView().getUnitQueries().isTransport(unit)) {
            this.mode = BUILDING_MODE;
        } else if (UnitQueries.hasQueuedUnit(this.requireView().requireGameData().getUnitTypes(), unit, 0)) {
            this.populateProductionQueue(unit);
        } else if (UnitQueries.isBuildingAddon(unit) || UnitQueries.isPlacingProtossBuilding(unit)) {
            this.mode = ADDON_MODE;

            Unit child = this.getProductionChild(unit);

            this.getOrCreateControl(EGG_ADDON_PROGRESS_CONTROL).value = child == null ? 0 : this.getBuildPercent(child);

            int childType = child == null ? 0 : this.getDisplayedType(child);
            Control icon = this.getOrCreateControl(CommandInput.EGG_ADDON_CONTROL);

            icon.style = SINGLE_ICON_STYLE;
            icon.icon  = new Icon(childType, Icon.UNIT, childType);

            Control label = this.getOrCreateControl(EGG_ADDON_LABEL_CONTROL);
            int stringIdentifier = ADDON_LABEL_STRING + (child == null ? race : this.getRace(child.getUnitId()));

            label.text = this.text.getString(this.view, stringIdentifier);
        } else if (UnitQueries.isResearching(unit)) {
            this.populateResearchView(unit);
        } else if (UnitQueries.isUpgrading(unit)) {
            this.populateUpgradeView(unit);
        } else if (this.requireView().requireGameData().getUnitTypes().getSupplyProvided(type) != 0) {
            this.populateSupplyView(unit);
        } else if (this.isCompleted(unit) && this.requireView().requireGameData().getUnitTypes().isGasStructure(type)) {
            this.populateResourceView(unit);
        } else if (this.requireView().requireGameData().getUnitTypes().getAirWeapon(this.getDisplayedType(unit)) ==
                   ScrWeaponIds.NONE &&
                   this.requireView().getUnitTargeting().getGroundWeapon(unit) == ScrWeaponIds.NONE &&
                   this.requireView().requireGameData().getUnitTypes().getShieldEnabled(this.getDisplayedType(unit)) == 0) {
            this.mode = BUILDING_MODE;
        } else {
            this.mode = BUILDING_STATS_MODE;

            this.populateStatIcons(unit);
            this.controls.remove(KILLS_CONTROL);
        }

        this.populateHealthLine(unit);
        this.populateStatusLine(unit);
        this.populateNameLine(unit);
    }

    /**
     * Populates owned egg progress, queued icon and shared status lines.
     *
     * @param unit the displayed unit
     */
    private void populateEggView(@NotNull Unit unit) {
        if (!this.isOwnedOrReplay(unit) || this.isHallucination(unit)) {
            this.populateUnitView(unit);

            return;
        }

        this.mode = EGG_MODE;

        this.getOrCreateControl(EGG_ADDON_PROGRESS_CONTROL).value = this.getBuildPercent(unit);

        int queued = UnitQueries.getQueuedType(unit);
        Control icon = this.getOrCreateControl(CommandInput.EGG_ADDON_CONTROL);

        icon.style = SINGLE_ICON_STYLE;
        icon.icon  = new Icon(queued, Icon.UNIT, queued);

        Control label = this.getOrCreateControl(EGG_ADDON_LABEL_CONTROL);
        int stringIdentifier = MORPH_LABEL_STRING + this.getRace(unit.getUnitId());

        label.text = this.text.getString(this.view, stringIdentifier);

        this.populateHealthLine(unit);
        this.populateNameLine(unit);
    }

    /**
     * Populates active Archon merging progress or the general unit view.
     *
     * @param unit the displayed unit
     */
    private void populateArchonView(@NotNull Unit unit) {
        if (this.isOwnedOrReplay(unit) && unit.getPrimaryOrder() == ScrOrderIds.COMPLETING_ARCHON_SUMMON) {
            this.mode = CONSTRUCTION_MODE;

            this.getOrCreateControl(CONSTRUCTION_PROGRESS_CONTROL).value = this.getBuildPercent(unit);
            this.getOrCreateControl(CONSTRUCTION_LABEL_CONTROL).text     = this.text.getString(this.view, ARCHON_LABEL_STRING);

            this.populateHealthLine(unit);
            this.populateNameLine(unit);

            return;
        }

        this.populateUnitView(unit);
    }

    /**
     * Populates active hangar production or the general unit view.
     *
     * @param unit the displayed unit
     */
    private void populateHangarView(@NotNull Unit unit) {
        if (this.isOwnedOrReplay(unit) && UnitQueries.hasQueuedUnit(this.requireView().requireGameData().getUnitTypes(), unit, 0)) {
            this.populateProductionQueue(unit);
            this.populateHealthLine(unit);
            this.populateNameLine(unit);

            return;
        }

        this.populateUnitView(unit);
    }

    /**
     * Populates Overlord supply or loaded-unit controls.
     *
     * @param unit the displayed unit
     * @throws IllegalStateException if no game has been assigned
     */
    private void populateOverlordView(@NotNull Unit unit) {
        if (!this.isOwnedOrReplay(unit) || this.isHallucination(unit)) {
            this.populateUnitView(unit);

            return;
        }

        if (!this.requireView().getUnitTransport().hasLoadedUnits(unit)) {
            this.populateSupplyView(unit);

            if (this.mode == SUPPLY_MODE) {
                this.populateHealthLine(unit);
                this.populateStatusLine(unit);
                this.populateNameLine(unit);
            }

            return;
        }

        this.populateCargoView(unit);
    }

    /**
     * Populates eligible cargo controls or the unit or building fallback.
     *
     * @param unit the displayed unit
     * @throws IllegalStateException if no game has been assigned
     */
    private void populateTransportView(@NotNull Unit unit) {
        if (!this.isOwnedOrReplay(unit) || this.isHallucination(unit) || !this.requireView().getUnitTransport().hasLoadedUnits(unit)) {
            if (UnitQueries.isGroundedBuilding(unit)) {
                this.populateBuildingView(unit);
            } else {
                this.populateUnitView(unit);
            }

            return;
        }

        this.populateCargoView(unit);
    }

    /**
     * Populates cargo controls, health and the unit name.
     *
     * @param unit the displayed unit
     */
    private void populateCargoView(@NotNull Unit unit) {
        this.mode = CARGO_MODE;

        this.populateCargoControls(unit);
        this.populateHealthLine(unit);
        this.populateNameLine(unit);
    }

    /**
     * Populates item icons and shared status lines.
     *
     * @param unit the displayed unit
     */
    private void populateItemView(@NotNull Unit unit) {
        this.mode = ITEM_MODE;

        this.populateStatIcons(unit);
        this.populateHealthLine(unit);
        this.populateStatusLine(unit);
        this.populateNameLine(unit);
    }

    /**
     * Clears the view mode while retaining the displayed unit name.
     *
     * @param unit the displayed unit
     */
    private void populateEmptyView(@NotNull Unit unit) {
        this.mode = NO_MODE;

        this.populateNameLine(unit);
    }

    /**
     * Populates occupied selection slots in slot order.
     *
     * @param selection the interface selection
     */
    private void populateMultiSelectionView(@NotNull InterfaceSelection selection) {
        this.mode = MULTI_SELECTION_MODE;

        int next = FIRST_MULTI_SELECTION_CONTROL;

        for (int slot = 0; slot < InterfaceSelection.CAPACITY; slot++) {
            Unit unit = selection.getUnit(slot);

            if (unit == null) {
                continue;
            }

            Control control = this.getOrCreateControl(next++);

            control.unit  = unit;
            control.value = unit.getUnitId();
        }
    }

    /**
     * Formats hit points and optional shields with health-dependent colors, then updates the energy or lifetime line.
     *
     * @param unit the displayed unit
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private void populateHealthLine(@NotNull Unit unit) {
        ScrUnitTypeView units = this.requireView().requireGameData().getUnitTypes();
        int displayType = UnitQueries.getDisplayedType(units, unit);
        int hitPoints = UnitQueries.getHitPoints(unit);
        int maximumHitPoints = UnitQueries.getMaximumHitPoints(units, unit);
        int maximumShields = units.getShieldAmount(unit.getUnitId());
        Control line = this.getOrCreateControl(HEALTH_CONTROL);

        if (maximumHitPoints > MAXIMUM_HEALTH_TEXT || maximumShields > MAXIMUM_HEALTH_TEXT || (unit.getStatus() & UnitStatusFlags.INVINCIBLE) != 0) {
            line.text = "";
        } else {
            int percent = UnitQueries.getHitPointPercent(units, unit);
            boolean mediumHealth = percent >= MEDIUM_HEALTH_MINIMUM_PERCENT && percent <= MEDIUM_HEALTH_MAXIMUM_PERCENT;
            char color = UnitQueries.isHeavilyDamaged(units, unit) ? COLOR_RED : mediumHealth ? COLOR_YELLOW : COLOR_GREEN;

            if (units.getShieldEnabled(unit.getUnitId()) != 0) {
                line.text = String.format(Locale.ROOT,
                                          "%d/%d %c%d/%d",
                                          unit.getShieldPoints() >> HEALTH_FRACTION_BITS,
                                          units.getShieldAmount(displayType),
                                          color,
                                          hitPoints,
                                          maximumHitPoints);
            } else {
                line.text = String.format(Locale.ROOT, "%c%d/%d", color, hitPoints, maximumHitPoints);
            }
        }

        this.populateEnergyLine(unit);
    }

    /**
     * Shows energy or timed life for an owned unit or in replay mode, otherwise leaves the line empty.
     *
     * @param unit the displayed unit
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private void populateEnergyLine(@NotNull Unit unit) {
        boolean visible = this.replay || unit.getOwnerIndex() == this.localPlayer;
        Control line = this.getOrCreateControl(ENERGY_LIFETIME_CONTROL);

        if (UnitTypeTraitsUtils.isSpellcaster(this.requireView().requireGameData().getUnitTypes(), unit) && visible) {
            int energy = unit.getEnergy() >> HEALTH_FRACTION_BITS;
            ScrUnitTypeView units = this.requireView().requireGameData().getUnitTypes();
            int maximumEnergy = this.requireView().getUnitInitializer().getMaximumEnergy(unit, units) >> HEALTH_FRACTION_BITS;

            line.text = String.format(Locale.ROOT, "%c%d/%d", COLOR_WHITE, energy, maximumEnergy);
        } else if ((this.isHallucination(unit) || unit.getUnitId() == ScrUnitIds.ZERG_BROODLING) && visible) {
            int maximum = this.isHallucination(unit) ? HALLUCINATION_LIFETIME : BROODLING_LIFETIME;

            line.text = String.format(Locale.ROOT,
                                      "%c%d/%d",
                                      COLOR_WHITE,
                                      unit.getRemainingLifetime() / LIFETIME_TEXT_SCALE,
                                      maximum / LIFETIME_TEXT_SCALE);
        } else {
            line.text = "";
        }
    }

    /**
     * Selects the first applicable hallucination, disabled-building, acid-spore, blindness, parasite, detector or rank label.
     *
     * @param unit the displayed unit
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private void populateStatusLine(@NotNull Unit unit) {
        int race = this.getRace(unit.getUnitId());
        int unitTypeIdentifier = unit.getUnitId();
        boolean building = (this.requireView().requireGameData().getUnitTypes().getFlags(unitTypeIdentifier) & UnitTypeFlags.BUILDING) != 0;
        String status;

        if (this.isHallucination(unit) && (this.replay || unit.getOwnerIndex() == this.localPlayer)) {
            status = this.text.getString(this.view, HALLUCINATION_LABEL_STRING);
        } else if (building && UnitOrderEligibility.isDisabled(unit) && this.isCompleted(unit) && !UnitQueries.isProducing(unit) &&
                   !UnitQueries.isResearching(unit) && !UnitQueries.isUpgrading(unit)) {
            status = this.text.getString(this.view, DISABLED_LABEL_STRING + race);
        } else if (unit.getAcidSporeCount() != 0 && this.isCompleted(unit)) {
            status = String.format(Locale.ROOT, "%s (%d)", this.text.getString(this.view, ACID_SPORE_LABEL_STRING), unit.getAcidSporeCount());
        } else if (unit.getBlindnessState() != 0 && this.isCompleted(unit)) {
            status = this.text.getString(this.view, BLINDNESS_LABEL_STRING);
        } else if (unit.getParasiteFlags() != 0 && this.isCompleted(unit)) {
            status = this.text.getString(this.view, PARASITE_LABEL_STRING);
        } else {
            int detectorUnitType = unit.getUnitId();

            if ((this.requireView().requireGameData().getUnitTypes().getFlags(detectorUnitType) & UnitTypeFlags.DETECTOR) != 0 &&
                this.isCompleted(unit) && !UnitOrderEligibility.isDisabled(unit) && unit.getBlindnessState() == 0) {
                status = this.text.getString(this.view, DETECTOR_LABEL_STRING);
            } else if (!building) {
                status = this.getRankText(unit);
            } else {
                status = "";
            }
        }

        this.getOrCreateControl(STATUS_CONTROL).text = status;
    }

    /**
     * Shows kills for attack-capable units or a nonzero kill count, except for Scourges, Infested Terrans and own or replay-shown hallucinations.
     *
     * @param unit the displayed unit
     * @throws IllegalStateException if no game has been assigned
     */
    private void populateKillsLine(@NotNull Unit unit) {
        int type = unit.getUnitId();

        if (type == ScrUnitIds.ZERG_SCOURGE || type == ScrUnitIds.ZERG_INFESTED_TERRAN) {
            return;
        }

        if (this.isHallucination(unit) && (this.replay || unit.getOwnerIndex() == this.localPlayer)) {
            return;
        }

        if (this.requireView().getUnitQueries().canAttack(unit) || unit.getKillCount() != 0) {
            this.getOrCreateControl(KILLS_CONTROL).text = this.text.getString(this.view, KILLS_LABEL_STRING) + " " + unit.getKillCount();
        }
    }

    /**
     * Builds armor, shields, ground and distinct air weapon icons, followed by eligible hangar, mine or silo counts.
     *
     * @param unit the displayed unit
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private void populateStatIcons(@NotNull Unit unit) {
        if (this.isHallucination(unit) && (this.replay || unit.getOwnerIndex() == this.localPlayer)) {
            return;
        }

        ScrUnitTypeView units = this.requireView().requireGameData().getUnitTypes();
        int displayType = UnitQueries.getDisplayedType(units, unit);
        int slot = 0;
        int armorUpgrade = units.getArmorUpgrade(displayType);

        if (armorUpgrade != ScrUpgradeIds.UNUSED_60) {
            this.populateStatIcon(slot++,
                                  getUpgradeLevel(this.requireView(), unit.getOwnerIndex(), armorUpgrade),
                                  new Icon(this.requireView().requireGameData().getUpgradeTypes().getIcon(armorUpgrade),
                                           Icon.ARMOR,
                                           armorUpgrade));
        }

        if (units.getShieldEnabled(unit.getUnitId()) != 0) {
            this.populateStatIcon(slot++,
                                  getUpgradeLevel(this.requireView(), unit.getOwnerIndex(), ScrUpgradeIds.PROTOSS_PLASMA_SHIELDS),
                                  new Icon(PLASMA_SHIELDS_ICON, Icon.SHIELDS, ScrUpgradeIds.PROTOSS_PLASMA_SHIELDS));
        }

        int ground = this.getGroundWeapon(unit);

        if (ground != ScrWeaponIds.NONE) {
            int groundUpgrade = this.requireView().requireGameData().getWeaponTypes().getDamageUpgrade(ground);

            this.populateStatIcon(slot++,
                                  getUpgradeLevel(this.requireView(), unit.getOwnerIndex(), groundUpgrade),
                                  new Icon(this.requireView().requireGameData().getWeaponTypes().getIcon(ground), Icon.WEAPON, ground));
        }

        int air = this.requireView().getUnitTargeting().getUnitOrSubunitAirWeapon(unit);

        if (air != ScrWeaponIds.NONE && air != ground) {
            int airUpgrade = this.requireView().requireGameData().getWeaponTypes().getDamageUpgrade(air);

            this.populateStatIcon(slot++,
                                  getUpgradeLevel(this.requireView(), unit.getOwnerIndex(), airUpgrade),
                                  new Icon(this.requireView().requireGameData().getWeaponTypes().getIcon(air), Icon.WEAPON, air));
        }

        int type = unit.getUnitId();

        if (units.hasHangar(type)) {
            boolean carrier = units.isCarrier(type);
            int child = carrier ? ScrUnitIds.PROTOSS_INTERCEPTOR : ScrUnitIds.PROTOSS_SCARAB;
            int count = carrier ? unit.getHangarCount(false) + unit.getHangarCount(true) : unit.getHangarCount(false);

            this.populateStatIcon(slot++, count, new Icon(child, Icon.UNIT, child));
        }

        if (units.isVulture(type)) {
            RequirementEvaluator requirements = new RequirementEvaluator(this.requireView());

            if (requirements.evaluateTechnologyUse(ScrTechIds.SPIDER_MINES, unit.getOwnerIndex(), unit) == RequirementEvaluator.ENABLED) {
                int spiderMine = ScrUnitIds.TERRAN_VULTURE_SPIDER_MINE;

                this.populateStatIcon(slot++, unit.getSpiderMineCount(), new Icon(spiderMine, Icon.UNIT, spiderMine));
            }
        }

        if (displayType == ScrUnitIds.TERRAN_NUCLEAR_SILO && slot < STAT_ICON_COUNT) {
            int nuclearMissile = ScrUnitIds.TERRAN_NUCLEAR_MISSILE;

            this.populateStatIcon(slot, unit.getSiloNuke() != null ? 1 : 0, new Icon(nuclearMissile, Icon.UNIT, nuclearMissile));
        }
    }

    /**
     * Populates a stat icon when its slot is within the shown range.
     *
     * @param slot   the stat icon slot
     * @param number the shown amount
     * @param icon   the icon frame
     */
    private void populateStatIcon(int slot, int number, @NotNull Icon icon) {
        if (slot >= STAT_ICON_COUNT) {
            return;
        }

        Control control = this.getOrCreateControl(FIRST_STAT_ICON_CONTROL + slot);

        control.text    = number + " ";
        control.icon    = icon;
        control.style   = STAT_ICON_STYLE;
        control.enabled = false;
    }

    /**
     * Builds the current production progress and ordered queue slots, then chooses the race-dependent production label.
     *
     * @param unit the displayed unit
     */
    private void populateProductionQueue(@NotNull Unit unit) {
        if (!this.isOwnedOrReplay(unit)) {
            return;
        }

        this.mode = PRODUCTION_MODE;

        Unit child = this.getProductionChild(unit);

        this.getOrCreateControl(QUEUE_PROGRESS_CONTROL).value = child == null ? 0 : this.getBuildPercent(child);

        this.getOrCreateControl(QUEUE_AUXILIARY_CONTROL);

        for (int slot = 0; slot < Unit.BUILD_QUEUE_SLOTS; slot++) {
            int queued = UnitQueries.getQueueSlotType(unit, slot);
            Control control = this.getOrCreateControl(CommandInput.FIRST_QUEUE_CONTROL + slot);

            control.style = slot >= FIRST_MIDDLE_QUEUE_ICON_SLOT && slot < STAT_ICON_COUNT ? MIDDLE_QUEUE_ICON_STYLE : SINGLE_ICON_STYLE;

            if (queued != ScrUnitIds.NONE) {
                control.icon    = new Icon(this.requireView().requireGameData().getCommandIcon(queued), Icon.UNIT, queued);
                control.text    = slot == 0 ? (slot + 1) + " " : "\u0003" + (slot + 1) + "\u0001 ";
                control.enabled = true;
            } else {
                control.icon    = new Icon(slot + EMPTY_QUEUE_FIRST_ICON, Icon.EMPTY_QUEUE_SLOT, 0);
                control.text    = "";
                control.enabled = false;
            }
        }

        int race = this.getRace(unit.getUnitId());
        int displayType = this.getDisplayedType(unit);
        String label;

        if (UnitQueries.isGroundedBuilding(unit) && UnitOrderEligibility.isDisabled(unit)) {
            label = this.text.getString(this.view, DISABLED_LABEL_STRING + race);
        } else if (displayType == ScrUnitIds.PROTOSS_GATEWAY || displayType == ScrUnitIds.PROTOSS_STARGATE) {
            label = this.text.getString(this.view, MORPH_LABEL_STRING + race);
        } else {
            label = this.text.getString(this.view, TRAINING_LABEL_STRING + race);
        }

        this.getOrCreateControl(QUEUE_LABEL_CONTROL).text = label;
    }

    /**
     * Shows nuclear production while training; otherwise shows building stat icons without status or kill labels.
     *
     * @param unit the displayed unit
     */
    private void populateNuclearSiloView(@NotNull Unit unit) {
        if (UnitQueries.hasQueuedUnit(this.requireView().requireGameData().getUnitTypes(), unit, 0)) {
            this.mode = NUCLEAR_MODE;

            Unit child = this.getProductionChild(unit);

            this.getOrCreateControl(QUEUE_PROGRESS_CONTROL).value = child == null ? 0 : this.getBuildPercent(child);

            Control slot = this.getOrCreateControl(CommandInput.FIRST_QUEUE_CONTROL);
            int childType = child == null ? 0 : child.getUnitId();

            slot.style = SINGLE_ICON_STYLE;
            slot.text  = "1 ";
            slot.icon  = new Icon(childType, Icon.UNIT, childType);

            Control label = this.getOrCreateControl(QUEUE_LABEL_CONTROL);
            int stringIdentifier = NUCLEAR_LABEL_STRING + this.getRace(unit.getUnitId());

            label.text = this.text.getString(this.view, stringIdentifier);

            return;
        }

        this.mode = BUILDING_STATS_MODE;

        this.populateStatIcons(unit);
        this.controls.remove(STATUS_CONTROL);
        this.controls.remove(KILLS_CONTROL);
    }

    /**
     * Shows research progress, technology icon and the disabled or researching race label.
     *
     * @param unit the displayed unit
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private void populateResearchView(@NotNull Unit unit) {
        if (!this.isOwnedOrReplay(unit)) {
            return;
        }

        this.mode = RESEARCH_MODE;

        int technology = unit.getResearchType();
        int time = this.requireView().requireGameData().getTechTypes().getResearchTime(technology);

        this.getOrCreateControl(RESEARCH_UPGRADE_PROGRESS_CONTROL).value = time == 0
                                                                           ? 0
                                                                           : (time - unit.getUpgradeResearchTime()) * PERCENT_SCALE / time;

        Control icon = this.getOrCreateControl(CommandInput.RESEARCH_CONTROL);

        icon.style = SINGLE_ICON_STYLE;
        icon.icon  = new Icon(this.requireView().requireGameData().getTechTypes().getIcon(technology), Icon.TECHNOLOGY, technology);

        int race = this.getRace(unit.getUnitId());

        this.getOrCreateControl(RESEARCH_UPGRADE_LABEL_CONTROL).text = UnitOrderEligibility.isDisabled(unit)
                                                                       ? this.text.getString(this.view, DISABLED_LABEL_STRING + race)
                                                                       : this.text.getString(this.view, RESEARCH_LABEL_STRING + race);
    }

    /**
     * Shows level-dependent upgrade progress, upgrade icon and the disabled or upgrading race label.
     *
     * @param unit the displayed unit
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private void populateUpgradeView(@NotNull Unit unit) {
        if (!this.isOwnedOrReplay(unit)) {
            return;
        }

        this.mode = UPGRADE_MODE;

        int upgrade = unit.getUpgradeType();
        ScrUpgradeTypeView upgrades = this.requireView().requireGameData().getUpgradeTypes();
        int time = upgrades.getResearchTime(upgrade) +
                   upgrades.getResearchTimeFactor(upgrade) * getUpgradeLevel(this.requireView(), unit.getOwnerIndex(), upgrade);

        this.getOrCreateControl(RESEARCH_UPGRADE_PROGRESS_CONTROL).value = time == 0
                                                                           ? 0
                                                                           : (time - unit.getUpgradeResearchTime()) * PERCENT_SCALE / time;

        Control icon = this.getOrCreateControl(CommandInput.RESEARCH_CONTROL);

        icon.style = SINGLE_ICON_STYLE;
        icon.icon  = new Icon(upgrades.getIcon(upgrade), Icon.UPGRADE, upgrade);

        int race = this.getRace(unit.getUnitId());

        this.getOrCreateControl(RESEARCH_UPGRADE_LABEL_CONTROL).text = UnitOrderEligibility.isDisabled(unit)
                                                                       ? this.text.getString(this.view, DISABLED_LABEL_STRING + race)
                                                                       : this.text.getString(this.view, UPGRADE_LABEL_STRING + race);
    }

    /**
     * Shows rounded half-supply counters for the owner and race, then the eligible armor or shield stat icon.
     *
     * @param unit the displayed unit
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private void populateSupplyView(@NotNull Unit unit) {
        if (!this.isOwnedOrReplay(unit)) {
            this.populateUnitView(unit);

            return;
        }

        this.mode = SUPPLY_MODE;

        UnitAccounting accounting = this.requireView().getPlayerTable().getUnitAccounting();
        int race = this.getRace(unit.getUnitId());
        int supplyRace = Math.clamp(race, 0, ScrRaceIds.PLAYABLE_COUNT - 1);
        int owner = unit.getOwnerIndex();
        // Supply counters are indexed by race (Zerg 0, Terran 12, Protoss 24) plus the player.
        int counter = supplyRace * PlayerTable.PLAYER_SLOTS + owner;
        int used = accounting == null ? 0 : accounting.getSupplyUsed(counter);
        int available = accounting == null ? 0 : accounting.getSupplyAvailable(counter);
        int maximum = accounting == null ? 0 : accounting.getSupplyCap(counter);
        int provided = this.requireView().requireGameData().getUnitTypes().getSupplyProvided(this.getDisplayedType(unit));

        this.getOrCreateControl(SUPPLY_USED_CONTROL).text      = this.text.getString(this.view, SUPPLY_USED_STRING + race) + " " +
                                                                 (used + 1) / SUPPLY_POINTS_PER_UNIT;
        this.getOrCreateControl(SUPPLY_PROVIDED_CONTROL).text  = this.text.getString(this.view, SUPPLY_PROVIDED_STRING + race) + " " +
                                                                 (provided + 1) / SUPPLY_POINTS_PER_UNIT;
        this.getOrCreateControl(SUPPLY_AVAILABLE_CONTROL).text = this.text.getString(this.view, SUPPLY_AVAILABLE_STRING + race) + " " +
                                                                 (available + 1) / SUPPLY_POINTS_PER_UNIT;
        this.getOrCreateControl(SUPPLY_CAP_CONTROL).text       = this.text.getString(this.view, SUPPLY_CAP_STRING + race) + " " +
                                                                 ((maximum + 1) >>> SUPPLY_FRACTION_BITS);

        if (this.requireView().requireGameData().getUnitTypes().getShieldEnabled(unit.getUnitId()) != 0) {
            this.populateStatIcon(0,
                                  getUpgradeLevel(this.requireView(), unit.getOwnerIndex(), ScrUpgradeIds.PROTOSS_PLASMA_SHIELDS),
                                  new Icon(PLASMA_SHIELDS_ICON, Icon.SHIELDS, ScrUpgradeIds.PROTOSS_PLASMA_SHIELDS));
        } else if (unit.getUnitId() == ScrUnitIds.ZERG_OVERLORD || unit.getUnitId() == ScrUnitIds.HERO_YGGDRASILL) {
            int armorUpgrade = this.requireView().requireGameData().getUnitTypes().getArmorUpgrade(this.getDisplayedType(unit));

            this.populateStatIcon(0,
                                  getUpgradeLevel(this.requireView(), unit.getOwnerIndex(), armorUpgrade),
                                  new Icon(this.requireView().requireGameData().getUpgradeTypes().getIcon(armorUpgrade),
                                           Icon.ARMOR,
                                           armorUpgrade));
        }
    }

    /**
     * Shows a mineral, gas or depleted-resource label and an eligible shield icon.
     *
     * @param unit the displayed unit
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private void populateResourceView(@NotNull Unit unit) {
        this.mode = RESOURCE_MODE;

        this.getOrCreateControl(SUPPLY_USED_CONTROL);
        this.getOrCreateControl(SUPPLY_AVAILABLE_CONTROL);
        this.getOrCreateControl(SUPPLY_CAP_CONTROL);

        int type = unit.getUnitId();
        int amount = unit.getResources();
        String line;

        if (this.requireView().requireGameData().getUnitTypes().isMineralField(type)) {
            line = this.text.getString(this.view, MINERALS_STRING) + " " + amount;
        } else if (amount != 0) {
            line = this.text.getString(this.view, GAS_STRING) + " " + amount;
        } else {
            line = this.text.getString(this.view, DEPLETED_GAS_STRING);
        }

        this.getOrCreateControl(SUPPLY_PROVIDED_CONTROL).text = line;

        if (this.requireView().requireGameData().getUnitTypes().getShieldEnabled(type) != 0) {
            this.populateStatIcon(0,
                                  getUpgradeLevel(this.requireView(), unit.getOwnerIndex(), ScrUpgradeIds.PROTOSS_PLASMA_SHIELDS),
                                  new Icon(PLASMA_SHIELDS_ICON, Icon.SHIELDS, ScrUpgradeIds.PROTOSS_PLASMA_SHIELDS));
        }
    }

    /**
     * Publishes loaded-unit hit points, then lays out cargo controls in decreasing space-size order.
     *
     * @param unit the displayed unit
     * @throws IllegalStateException if no game has been assigned
     * @throws NullPointerException  if the game was built without game data
     */
    private void populateCargoControls(@NotNull Unit unit) {
        ScrUnitTypeView units = this.requireView().requireGameData().getUnitTypes();
        boolean small = units.getSpaceProvided(unit.getUnitId()) < NARROW_CARGO_CAPACITY_LIMIT;
        int largeCargoControl = small ? NARROW_LARGE_CARGO_CONTROL : WIDE_LARGE_CARGO_CONTROL;
        int mediumCargoControl = small ? NARROW_MEDIUM_CARGO_CONTROL : WIDE_MEDIUM_CARGO_CONTROL;
        int smallCargoControl = small ? NARROW_SMALL_CARGO_CONTROL : WIDE_SMALL_CARGO_CONTROL;

        this.requirePresentation().getInterfaceLink().cacheCargoHitPoints(this.requireView().getUnitTransport(), unit);

        for (int size : new int[]{LARGE_CARGO_SPACE, MEDIUM_CARGO_SPACE, SMALL_CARGO_SPACE}) {
            for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
                Unit loaded = this.requireView().getUnitTransport().getLoadedUnit(unit, slot);

                if (loaded == null || units.getSpaceRequired(loaded.getUnitId()) != size) {
                    continue;
                }

                int identifier = size == LARGE_CARGO_SPACE ? largeCargoControl : size == MEDIUM_CARGO_SPACE ? mediumCargoControl : smallCargoControl;
                Control control = this.getOrCreateControl(identifier);
                int base = size == LARGE_CARGO_SPACE ? LARGE_CARGO_STYLE : size == MEDIUM_CARGO_SPACE ? MEDIUM_CARGO_STYLE : SMALL_CARGO_STYLE;

                if (this.isHallucination(loaded)) {
                    control.style = base + HALLUCINATION_CARGO_STYLE_OFFSET;
                } else {
                    int unitTypeIdentifier = loaded.getUnitId();
                    int flags = this.requireView().requireGameData().getUnitTypes().getFlags(unitTypeIdentifier);

                    control.style = (flags & UnitTypeFlags.HERO) != 0
                                    ? base + HERO_CARGO_STYLE_OFFSET
                                    : loaded.getParasiteFlags() != 0 ? base + PARASITE_CARGO_STYLE_OFFSET : base;
                }

                control.unit = loaded;
                largeCargoControl += size >> LARGE_CARGO_SPACE_SHIFT;
                mediumCargoControl += size >> MEDIUM_CARGO_SPACE_SHIFT;
                smallCargoControl += size;
            }
        }
    }

    /**
     * Shows the displayed unit type name, or an empty string for a type without an entry (the no-unit marker and the ids after it that name no
     * added unit type).
     *
     * @param unit the displayed unit
     * @throws IllegalStateException if no game has been assigned
     */
    private void populateNameLine(@NotNull Unit unit) {
        int displayType = this.getDisplayedType(unit);

        this.getOrCreateControl(UNIT_NAME_CONTROL).text = this.requireView().requireGameData().getUnitTypes().hasEntry(displayType)
                                                          ? this.text.getUnitName(this.requireView(), displayType)
                                                          : "";
    }
}
