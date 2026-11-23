package org.fogofwarlabs.sc4j.scr.data.type;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUpgradeIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.TriggerUnitGroups;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTraitFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;
import java.util.function.IntUnaryOperator;

/**
 * Holds the {@code units.dat} data of every unit type, indexed by its {@code int} id (the constants of {@link ScrUnitIds}).
 * <p>
 * Each accessor reads one value of a column ({@link DatColumn}) that a loader supplies. A game's columns are its own DAT memory, held by its engine
 * content: values keep the width and sign of the column, and EUD and map-setting writes to the columns are visible. An id outside a column has no
 * bound check: it reads the DAT memory around the column through the loader's {@link ColumnOverrunReader}, and an unbuilt production queue slot
 * ({@code 228}) builds a unit from those values. Reads allocate nothing.
 * <p>
 * A view of a game also reads the added unit types of its content: the ids from {@code 233} on, after the none id and the trigger group ids, read
 * their own added columns instead of the memory around the columns. Without added types every read is the native one. The per-type rules of
 * the simulation read the base type of an added type ({@link #getBaseType(int)}); its values stay its own.
 * <p>
 * Next to the DAT fields, the view reads the trait columns ({@link TraitColumn}): per-type values of the SC:R rules that no {@code units.dat}
 * field holds, which the content importer derives. A game's trait columns are extension columns of its engine content; a type outside a trait
 * column has no trait.
 */
public final class ScrUnitTypeView {
    /**
     * The first unit type with an infestation type and an add-on position (the first building).
     */
    public static final int FIRST_INFESTATION_TYPE = 106;
    /**
     * The initial energy column value of a type that starts with its default share of its maximum energy ({@link #getInitialEnergy(int)}).
     */
    public static final int DEFAULT_INITIAL_ENERGY = -1;

    /**
     * The right-click action ({@link #getRightClickAction(int)}) without a command.
     */
    public static final int RIGHT_CLICK_NONE = 0;
    /**
     * The right-click action that moves to a position and attacks or follows a target unit.
     */
    public static final int RIGHT_CLICK_MOVE_AND_ATTACK = 1;
    /**
     * The right-click action that moves without attacking; a grounded producer sets its rally point with it.
     */
    public static final int RIGHT_CLICK_MOVE_NO_ATTACK = 2;
    /**
     * The right-click action that attacks without moving (an immovable attacker).
     */
    public static final int RIGHT_CLICK_ATTACK_NO_MOVE = 3;
    /**
     * The right-click action of a worker that harvests a resource target and otherwise moves and attacks.
     */
    public static final int RIGHT_CLICK_HARVEST = 4;
    /**
     * The right-click action of a worker that harvests, repairs or constructs, and otherwise moves and attacks.
     */
    public static final int RIGHT_CLICK_HARVEST_AND_REPAIR = 5;
    /**
     * The right-click action that issues nothing.
     */
    public static final int RIGHT_CLICK_NOTHING = 6;

    /**
     * The number of build-time entries: from this unit type on, {@link #getBuildTime(int)} reads the requirement index column.
     */
    private static final int BUILD_TIME_TYPE_COUNT = ScrUnitIds.NONE;
    /**
     * The unit type id of the first added unit type, after the none id and the trigger group ids.
     */
    private static final int FIRST_ADDED_TYPE = TriggerUnitGroups.FACTORIES + 1;
    /**
     * The added column of a view without added unit types.
     */
    private static final @NotNull DatColumn NO_ADDED_TYPES = new ArrayDatColumn(new int[0]);

    /**
     * Names the columns a loader supplies to {@link ScrUnitTypeView#ScrUnitTypeView(Function, Function, Function, Function, IntUnaryOperator, String, ColumnOverrunReader)}.
     */
    public enum Column {
        /**
         * The column of {@link ScrUnitTypeView#getFlingyType(int)}.
         */
        FLINGY_TYPE,
        /**
         * The column of {@link ScrUnitTypeView#getFirstSubunit(int)}.
         */
        FIRST_SUBUNIT,
        /**
         * The column of {@link ScrUnitTypeView#getSecondSubunit(int)}.
         */
        SECOND_SUBUNIT,
        /**
         * The column of {@link ScrUnitTypeView#getInfestedType(int)}.
         */
        INFESTED_TYPE,
        /**
         * The column of {@link ScrUnitTypeView#getConstructionImage(int)}.
         */
        CONSTRUCTION_IMAGE,
        /**
         * The column of {@link ScrUnitTypeView#getInitialDirection(int)}.
         */
        INITIAL_DIRECTION,
        /**
         * The column of {@link ScrUnitTypeView#getShieldEnabled(int)}.
         */
        SHIELD_ENABLED,
        /**
         * The column of {@link ScrUnitTypeView#getShieldAmount(int)}.
         */
        SHIELD_AMOUNT,
        /**
         * The column of {@link ScrUnitTypeView#getHitPoints(int)}.
         */
        HIT_POINTS,
        /**
         * The column of {@link ScrUnitTypeView#getElevationLevel(int)}.
         */
        ELEVATION_LEVEL,
        /**
         * The column of {@link ScrUnitTypeView#getUnknownByte(int)}.
         */
        UNKNOWN_BYTE,
        /**
         * The column of {@link ScrUnitTypeView#getSublabel(int)}.
         */
        SUBLABEL,
        /**
         * The column of {@link ScrUnitTypeView#getComputerIdleOrder(int)}.
         */
        COMPUTER_IDLE_ORDER,
        /**
         * The column of {@link ScrUnitTypeView#getHumanIdleOrder(int)}.
         */
        HUMAN_IDLE_ORDER,
        /**
         * The column of {@link ScrUnitTypeView#getReturnToIdleOrder(int)}.
         */
        RETURN_TO_IDLE_ORDER,
        /**
         * The column of {@link ScrUnitTypeView#getAttackUnitOrder(int)}.
         */
        ATTACK_UNIT_ORDER,
        /**
         * The column of {@link ScrUnitTypeView#getAttackMoveOrder(int)}.
         */
        ATTACK_MOVE_ORDER,
        /**
         * The column of {@link ScrUnitTypeView#getGroundWeapon(int)}.
         */
        GROUND_WEAPON,
        /**
         * The column of {@link ScrUnitTypeView#getMaximumGroundHits(int)}.
         */
        MAXIMUM_GROUND_HITS,
        /**
         * The column of {@link ScrUnitTypeView#getAirWeapon(int)}.
         */
        AIR_WEAPON,
        /**
         * The column of {@link ScrUnitTypeView#getMaximumAirHits(int)}.
         */
        MAXIMUM_AIR_HITS,
        /**
         * The column of {@link ScrUnitTypeView#getComputerInternalFlags(int)}.
         */
        COMPUTER_INTERNAL_FLAGS,
        /**
         * The column of {@link ScrUnitTypeView#getFlags(int)}.
         */
        FLAGS,
        /**
         * The column of {@link ScrUnitTypeView#getTargetAcquisitionRange(int)}.
         */
        TARGET_ACQUISITION_RANGE,
        /**
         * The column of {@link ScrUnitTypeView#getSightRange(int)}.
         */
        SIGHT_RANGE,
        /**
         * The column of {@link ScrUnitTypeView#getArmorUpgrade(int)}.
         */
        ARMOR_UPGRADE,
        /**
         * The column of {@link ScrUnitTypeView#getSizeClass(int)}.
         */
        SIZE_CLASS,
        /**
         * The column of {@link ScrUnitTypeView#getArmor(int)}.
         */
        ARMOR,
        /**
         * The column of {@link ScrUnitTypeView#getRightClickAction(int)}.
         */
        RIGHT_CLICK_ACTION,
        /**
         * The column of {@link ScrUnitTypeView#getReadySound(int)}.
         */
        READY_SOUND,
        /**
         * The column of {@link ScrUnitTypeView#getWhatSoundStart(int)}.
         */
        WHAT_SOUND_START,
        /**
         * The column of {@link ScrUnitTypeView#getWhatSoundEnd(int)}.
         */
        WHAT_SOUND_END,
        /**
         * The column of {@link ScrUnitTypeView#getAnnoyedSoundStart(int)}.
         */
        ANNOYED_SOUND_START,
        /**
         * The column of {@link ScrUnitTypeView#getAnnoyedSoundEnd(int)}.
         */
        ANNOYED_SOUND_END,
        /**
         * The column of {@link ScrUnitTypeView#getYesSoundStart(int)}.
         */
        YES_SOUND_START,
        /**
         * The column of {@link ScrUnitTypeView#getYesSoundEnd(int)}.
         */
        YES_SOUND_END,
        /**
         * The column of {@link ScrUnitTypeView#getPlacementWidth(int)}.
         */
        PLACEMENT_WIDTH,
        /**
         * The column of {@link ScrUnitTypeView#getPlacementHeight(int)}.
         */
        PLACEMENT_HEIGHT,
        /**
         * The column of {@link ScrUnitTypeView#getAddonHorizontalOffset(int)}.
         */
        ADDON_HORIZONTAL_OFFSET,
        /**
         * The column of {@link ScrUnitTypeView#getAddonVerticalOffset(int)}.
         */
        ADDON_VERTICAL_OFFSET,
        /**
         * The column of {@link ScrUnitTypeView#getDimensionLeft(int)}.
         */
        DIMENSION_LEFT,
        /**
         * The column of {@link ScrUnitTypeView#getDimensionUp(int)}.
         */
        DIMENSION_UP,
        /**
         * The column of {@link ScrUnitTypeView#getDimensionRight(int)}.
         */
        DIMENSION_RIGHT,
        /**
         * The column of {@link ScrUnitTypeView#getDimensionDown(int)}.
         */
        DIMENSION_DOWN,
        /**
         * The column of {@link ScrUnitTypeView#getPortrait(int)}.
         */
        PORTRAIT,
        /**
         * The column of {@link ScrUnitTypeView#getMineralCost(int)}.
         */
        MINERAL_COST,
        /**
         * The column of {@link ScrUnitTypeView#getGasCost(int)}.
         */
        GAS_COST,
        /**
         * The column of {@link ScrUnitTypeView#getBuildTime(int)}.
         */
        BUILD_TIME,
        /**
         * The column of {@link ScrUnitTypeView#getRequirementIndex(int)}.
         */
        REQUIREMENT_INDEX,
        /**
         * The column of {@link ScrUnitTypeView#getGroupFlags(int)}.
         */
        GROUP_FLAGS,
        /**
         * The column of {@link ScrUnitTypeView#getSupplyProvided(int)}.
         */
        SUPPLY_PROVIDED,
        /**
         * The column of {@link ScrUnitTypeView#getSupplyRequired(int)}.
         */
        SUPPLY_REQUIRED,
        /**
         * The column of {@link ScrUnitTypeView#getSpaceRequired(int)}.
         */
        SPACE_REQUIRED,
        /**
         * The column of {@link ScrUnitTypeView#getSpaceProvided(int)}.
         */
        SPACE_PROVIDED,
        /**
         * The column of {@link ScrUnitTypeView#getBuildScore(int)}.
         */
        BUILD_SCORE,
        /**
         * The column of {@link ScrUnitTypeView#getDestroyScore(int)}.
         */
        DESTROY_SCORE,
        /**
         * The column of {@link ScrUnitTypeView#getMapStringIndex(int)}.
         */
        MAP_STRING_INDEX,
        /**
         * The column of {@link ScrUnitTypeView#getBroodWarFlag(int)}.
         */
        BROOD_WAR_FLAG,
        /**
         * The column of {@link ScrUnitTypeView#getAvailabilityFlags(int)}.
         */
        AVAILABILITY_FLAGS
    }

    /**
     * Names the trait columns a loader supplies to {@link ScrUnitTypeView#ScrUnitTypeView(Function, Function, Function, Function, IntUnaryOperator, String, ColumnOverrunReader)}.
     */
    public enum TraitColumn {
        /**
         * The column of {@link ScrUnitTypeView#getTraits(int)}.
         */
        TRAITS,
        /**
         * The column of {@link ScrUnitTypeView#getUpgradedTopSpeed(int)}.
         */
        UPGRADED_TOP_SPEED,
        /**
         * The column of {@link ScrUnitTypeView#getHangarCapacity(int)}.
         */
        HANGAR_CAPACITY,
        /**
         * The column of {@link ScrUnitTypeView#getTransportUpgrade(int)}.
         */
        TRANSPORT_UPGRADE,
        /**
         * The column of {@link ScrUnitTypeView#getInitialEnergy(int)}.
         */
        INITIAL_ENERGY,
        /**
         * The column of {@link ScrUnitTypeView#getInitialAmmunition(int)}.
         */
        INITIAL_AMMUNITION
    }

    private final @NotNull DatColumn flingyType;
    private final @NotNull DatColumn firstSubunit;
    private final @NotNull DatColumn secondSubunit;
    private final @NotNull DatColumn infestedType;
    private final @NotNull DatColumn constructionImage;
    private final @NotNull DatColumn initialDirection;

    private final @NotNull DatColumn shieldEnabled;
    private final @NotNull DatColumn shieldAmount;
    private final @NotNull DatColumn hitPoints;

    private final @NotNull DatColumn elevationLevel;
    private final @NotNull DatColumn unknownByte;
    private final @NotNull DatColumn sublabel;

    private final @NotNull DatColumn computerIdleOrder;
    private final @NotNull DatColumn humanIdleOrder;
    private final @NotNull DatColumn returnToIdleOrder;
    private final @NotNull DatColumn attackUnitOrder;
    private final @NotNull DatColumn attackMoveOrder;

    private final @NotNull DatColumn groundWeapon;
    private final @NotNull DatColumn maximumGroundHits;
    private final @NotNull DatColumn airWeapon;
    private final @NotNull DatColumn maximumAirHits;

    private final @NotNull DatColumn computerInternalFlags;
    private final @NotNull DatColumn flags;
    private final @NotNull DatColumn targetAcquisitionRange;
    private final @NotNull DatColumn sightRange;

    private final @NotNull DatColumn armorUpgrade;
    private final @NotNull DatColumn sizeClass;
    private final @NotNull DatColumn armor;

    private final @NotNull DatColumn rightClickAction;

    private final @NotNull DatColumn readySound;
    private final @NotNull DatColumn whatSoundStart;
    private final @NotNull DatColumn whatSoundEnd;
    private final @NotNull DatColumn annoyedSoundStart;
    private final @NotNull DatColumn annoyedSoundEnd;
    private final @NotNull DatColumn yesSoundStart;
    private final @NotNull DatColumn yesSoundEnd;

    private final @NotNull DatColumn placementWidth;
    private final @NotNull DatColumn placementHeight;
    private final @NotNull DatColumn addonHorizontalOffset;
    private final @NotNull DatColumn addonVerticalOffset;
    private final @NotNull DatColumn dimensionLeft;
    private final @NotNull DatColumn dimensionUp;
    private final @NotNull DatColumn dimensionRight;
    private final @NotNull DatColumn dimensionDown;

    private final @NotNull DatColumn portrait;

    private final @NotNull DatColumn mineralCost;
    private final @NotNull DatColumn gasCost;
    private final @NotNull DatColumn buildTime;
    private final @NotNull DatColumn requirementIndex;

    private final @NotNull DatColumn groupFlags;

    private final @NotNull DatColumn supplyProvided;
    private final @NotNull DatColumn supplyRequired;

    private final @NotNull DatColumn spaceRequired;
    private final @NotNull DatColumn spaceProvided;

    private final @NotNull DatColumn buildScore;
    private final @NotNull DatColumn destroyScore;

    private final @NotNull DatColumn mapStringIndex;
    private final @NotNull DatColumn broodWarFlag;
    private final @NotNull DatColumn availabilityFlags;

    private final @NotNull DatColumn traits;
    private final @NotNull DatColumn upgradedTopSpeed;
    private final @NotNull DatColumn hangarCapacity;
    private final @NotNull DatColumn transportUpgrade;
    private final @NotNull DatColumn initialEnergy;
    private final @NotNull DatColumn initialAmmunition;

    private final @NotNull RequirementIndexColumn requirementIndices;

    private final @NotNull ColumnOverrunReader<Column> overrunReader;

    private final @NotNull DatColumn @NotNull [] addedColumns;
    private final @NotNull DatColumn @NotNull [] addedTraitColumns;
    private final @NotNull IntUnaryOperator addedBaseTypes;
    private final int addedCount;

    /**
     * Creates a {@link ScrUnitTypeView} instance over the columns of a loader without added unit types: every id outside a column reads through
     * the overrun reader.
     *
     * @param columns              the column of each {@link Column}, shared and not copied
     * @param traitColumns         the column of each {@link TraitColumn}, shared and not copied
     * @param requirementIndexName the loader's name of the column, shown in requirement diagnostics
     * @param overrunReader        the reader of an element outside a column
     */
    public ScrUnitTypeView(@NotNull Function<Column, @NotNull DatColumn> columns,
                           @NotNull Function<TraitColumn, @NotNull DatColumn> traitColumns,
                           @NotNull String requirementIndexName,
                           @NotNull ColumnOverrunReader<Column> overrunReader) {
        this(columns, traitColumns, column -> NO_ADDED_TYPES, column -> NO_ADDED_TYPES, IntUnaryOperator.identity(), requirementIndexName,
             overrunReader);
    }

    /**
     * Creates a {@link ScrUnitTypeView} instance over the columns of a loader and the columns of the added unit types: element {@code n} of an
     * added column is the value of the unit type id {@link #FIRST_ADDED_TYPE}{@code  + n}, and every added column has one element per added type.
     *
     * @param columns              the column of each {@link Column}, shared and not copied
     * @param traitColumns         the column of each {@link TraitColumn}, shared and not copied
     * @param addedColumns         the added column of each {@link Column}, shared and not copied
     * @param addedTraitColumns    the added column of each {@link TraitColumn}, shared and not copied
     * @param addedBaseTypes       the native unit type id of the base type of each added type id
     * @param requirementIndexName the loader's name of the column, shown in requirement diagnostics
     * @param overrunReader        the reader of an element outside a column for every id that is not an added type
     */
    public ScrUnitTypeView(@NotNull Function<Column, @NotNull DatColumn> columns,
                           @NotNull Function<TraitColumn, @NotNull DatColumn> traitColumns,
                           @NotNull Function<Column, @NotNull DatColumn> addedColumns,
                           @NotNull Function<TraitColumn, @NotNull DatColumn> addedTraitColumns,
                           @NotNull IntUnaryOperator addedBaseTypes,
                           @NotNull String requirementIndexName,
                           @NotNull ColumnOverrunReader<Column> overrunReader) {
        this.flingyType        = columns.apply(Column.FLINGY_TYPE);
        this.firstSubunit      = columns.apply(Column.FIRST_SUBUNIT);
        this.secondSubunit     = columns.apply(Column.SECOND_SUBUNIT);
        this.infestedType      = columns.apply(Column.INFESTED_TYPE);
        this.constructionImage = columns.apply(Column.CONSTRUCTION_IMAGE);
        this.initialDirection  = columns.apply(Column.INITIAL_DIRECTION);

        this.shieldEnabled = columns.apply(Column.SHIELD_ENABLED);
        this.shieldAmount  = columns.apply(Column.SHIELD_AMOUNT);
        this.hitPoints     = columns.apply(Column.HIT_POINTS);

        this.elevationLevel = columns.apply(Column.ELEVATION_LEVEL);
        this.unknownByte    = columns.apply(Column.UNKNOWN_BYTE);
        this.sublabel       = columns.apply(Column.SUBLABEL);

        this.computerIdleOrder = columns.apply(Column.COMPUTER_IDLE_ORDER);
        this.humanIdleOrder    = columns.apply(Column.HUMAN_IDLE_ORDER);
        this.returnToIdleOrder = columns.apply(Column.RETURN_TO_IDLE_ORDER);
        this.attackUnitOrder   = columns.apply(Column.ATTACK_UNIT_ORDER);
        this.attackMoveOrder   = columns.apply(Column.ATTACK_MOVE_ORDER);

        this.groundWeapon      = columns.apply(Column.GROUND_WEAPON);
        this.maximumGroundHits = columns.apply(Column.MAXIMUM_GROUND_HITS);
        this.airWeapon         = columns.apply(Column.AIR_WEAPON);
        this.maximumAirHits    = columns.apply(Column.MAXIMUM_AIR_HITS);

        this.computerInternalFlags  = columns.apply(Column.COMPUTER_INTERNAL_FLAGS);
        this.flags                  = columns.apply(Column.FLAGS);
        this.targetAcquisitionRange = columns.apply(Column.TARGET_ACQUISITION_RANGE);
        this.sightRange             = columns.apply(Column.SIGHT_RANGE);

        this.armorUpgrade = columns.apply(Column.ARMOR_UPGRADE);
        this.sizeClass    = columns.apply(Column.SIZE_CLASS);
        this.armor        = columns.apply(Column.ARMOR);

        this.rightClickAction = columns.apply(Column.RIGHT_CLICK_ACTION);

        this.readySound        = columns.apply(Column.READY_SOUND);
        this.whatSoundStart    = columns.apply(Column.WHAT_SOUND_START);
        this.whatSoundEnd      = columns.apply(Column.WHAT_SOUND_END);
        this.annoyedSoundStart = columns.apply(Column.ANNOYED_SOUND_START);
        this.annoyedSoundEnd   = columns.apply(Column.ANNOYED_SOUND_END);
        this.yesSoundStart     = columns.apply(Column.YES_SOUND_START);
        this.yesSoundEnd       = columns.apply(Column.YES_SOUND_END);

        this.placementWidth        = columns.apply(Column.PLACEMENT_WIDTH);
        this.placementHeight       = columns.apply(Column.PLACEMENT_HEIGHT);
        this.addonHorizontalOffset = columns.apply(Column.ADDON_HORIZONTAL_OFFSET);
        this.addonVerticalOffset   = columns.apply(Column.ADDON_VERTICAL_OFFSET);
        this.dimensionLeft         = columns.apply(Column.DIMENSION_LEFT);
        this.dimensionUp           = columns.apply(Column.DIMENSION_UP);
        this.dimensionRight        = columns.apply(Column.DIMENSION_RIGHT);
        this.dimensionDown         = columns.apply(Column.DIMENSION_DOWN);

        this.portrait = columns.apply(Column.PORTRAIT);

        this.mineralCost      = columns.apply(Column.MINERAL_COST);
        this.gasCost          = columns.apply(Column.GAS_COST);
        this.buildTime        = columns.apply(Column.BUILD_TIME);
        this.requirementIndex = columns.apply(Column.REQUIREMENT_INDEX);

        this.groupFlags = columns.apply(Column.GROUP_FLAGS);

        this.supplyProvided = columns.apply(Column.SUPPLY_PROVIDED);
        this.supplyRequired = columns.apply(Column.SUPPLY_REQUIRED);

        this.spaceRequired = columns.apply(Column.SPACE_REQUIRED);
        this.spaceProvided = columns.apply(Column.SPACE_PROVIDED);

        this.buildScore   = columns.apply(Column.BUILD_SCORE);
        this.destroyScore = columns.apply(Column.DESTROY_SCORE);

        this.mapStringIndex    = columns.apply(Column.MAP_STRING_INDEX);
        this.broodWarFlag      = columns.apply(Column.BROOD_WAR_FLAG);
        this.availabilityFlags = columns.apply(Column.AVAILABILITY_FLAGS);

        this.traits            = traitColumns.apply(TraitColumn.TRAITS);
        this.upgradedTopSpeed  = traitColumns.apply(TraitColumn.UPGRADED_TOP_SPEED);
        this.hangarCapacity    = traitColumns.apply(TraitColumn.HANGAR_CAPACITY);
        this.transportUpgrade  = traitColumns.apply(TraitColumn.TRANSPORT_UPGRADE);
        this.initialEnergy     = traitColumns.apply(TraitColumn.INITIAL_ENERGY);
        this.initialAmmunition = traitColumns.apply(TraitColumn.INITIAL_AMMUNITION);

        this.requirementIndices = new RequirementIndexColumn(requirementIndexName, this.requirementIndex);

        this.overrunReader = overrunReader;

        Column[] columnKinds = Column.values();
        TraitColumn[] traitKinds = TraitColumn.values();

        this.addedColumns      = new DatColumn[columnKinds.length];
        this.addedTraitColumns = new DatColumn[traitKinds.length];

        for (Column column : columnKinds) {
            this.addedColumns[column.ordinal()] = addedColumns.apply(column);
        }

        for (TraitColumn column : traitKinds) {
            this.addedTraitColumns[column.ordinal()] = addedTraitColumns.apply(column);
        }

        this.addedBaseTypes = addedBaseTypes;
        this.addedCount     = this.addedColumns[Column.FLINGY_TYPE.ordinal()].getLength();
    }

    /**
     * @return the number of entries
     */
    public int getCount() {
        return this.flingyType.getLength();
    }

    /**
     * @return the number of added unit types, whose ids start at {@link #FIRST_ADDED_TYPE}
     */
    public int getAddedCount() {
        return this.addedCount;
    }

    /**
     * @param unitTypeIdentifier a unit type id or any other value
     * @return {@code true} if the id names an added unit type, {@code false} otherwise
     */
    public boolean isAddedType(int unitTypeIdentifier) {
        int added = unitTypeIdentifier - FIRST_ADDED_TYPE;

        return added >= 0 && added < this.addedCount;
    }

    /**
     * Tests whether the simulation reads an id as a type entry: every id below {@link ScrUnitIds#NONE} (the {@code units.dat} entries, and the
     * negative values the native code reads through the column memory) and every added unit type.
     *
     * @param unitTypeIdentifier a unit type id or any other value
     * @return {@code true} if the id is below {@link ScrUnitIds#NONE} or names an added unit type, {@code false} otherwise
     */
    public boolean hasEntry(int unitTypeIdentifier) {
        return unitTypeIdentifier < ScrUnitIds.NONE || this.isAddedType(unitTypeIdentifier);
    }

    /**
     * Returns the native unit type whose per-type rules apply to a type: the base type of an added unit type, the type itself for every other
     * id. A per-type rule (a comparison with a native type, a switch over the native types) reads this type, so an added unit behaves as a
     * unit of its base type; the values of the table stay the type's own.
     *
     * @param unitTypeIdentifier a unit type id or any other value
     * @return the native unit type of the per-type rules
     */
    public int getBaseType(int unitTypeIdentifier) {
        return this.isAddedType(unitTypeIdentifier) ? this.addedBaseTypes.applyAsInt(unitTypeIdentifier) : unitTypeIdentifier;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the flingy.dat entry that moves and draws the unit
     */
    public int getFlingyType(int unitTypeIdentifier) {
        DatColumn values = this.flingyType;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.FLINGY_TYPE, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the units.dat entry of the first subunit (turret), {@code 228} for none
     */
    public int getFirstSubunit(int unitTypeIdentifier) {
        DatColumn values = this.firstSubunit;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.FIRST_SUBUNIT, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the units.dat entry of the second subunit, {@code 228} for none
     */
    public int getSecondSubunit(int unitTypeIdentifier) {
        DatColumn values = this.secondSubunit;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.SECOND_SUBUNIT, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the units.dat entry the building becomes when infested (stored for buildings from {@link #FIRST_INFESTATION_TYPE} on)
     */
    public int getInfestedType(int unitTypeIdentifier) {
        int element = unitTypeIdentifier - FIRST_INFESTATION_TYPE;
        DatColumn values = this.infestedType;

        return element >= 0 && element < values.getLength()
               ? values.get(element)
               : this.readOutside(Column.INFESTED_TYPE, unitTypeIdentifier, element);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the images.dat entry shown while the unit is constructed
     */
    public int getConstructionImage(int unitTypeIdentifier) {
        DatColumn values = this.constructionImage;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.CONSTRUCTION_IMAGE, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the initial facing direction ({@code 32} for random)
     */
    public int getInitialDirection(int unitTypeIdentifier) {
        DatColumn values = this.initialDirection;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.INITIAL_DIRECTION, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return non-zero if the unit has shields
     */
    public int getShieldEnabled(int unitTypeIdentifier) {
        DatColumn values = this.shieldEnabled;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.SHIELD_ENABLED, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the maximum shield points (whole points)
     */
    public int getShieldAmount(int unitTypeIdentifier) {
        DatColumn values = this.shieldAmount;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.SHIELD_AMOUNT, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the maximum hit points in 1/256 points
     */
    public int getHitPoints(int unitTypeIdentifier) {
        DatColumn values = this.hitPoints;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.HIT_POINTS, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the elevation level that orders the sprite layers
     */
    public int getElevationLevel(int unitTypeIdentifier) {
        DatColumn values = this.elevationLevel;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.ELEVATION_LEVEL, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the unnamed byte after the elevation level
     */
    public int getUnknownByte(int unitTypeIdentifier) {
        DatColumn values = this.unknownByte;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.UNKNOWN_BYTE, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the stat_txt.tbl sublabel (rank) string
     */
    public int getSublabel(int unitTypeIdentifier) {
        DatColumn values = this.sublabel;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.SUBLABEL, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the idle order of a computer-controlled unit
     */
    public int getComputerIdleOrder(int unitTypeIdentifier) {
        DatColumn values = this.computerIdleOrder;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.COMPUTER_IDLE_ORDER, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the idle order of a human-controlled unit
     */
    public int getHumanIdleOrder(int unitTypeIdentifier) {
        DatColumn values = this.humanIdleOrder;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.HUMAN_IDLE_ORDER, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the order the unit returns to when an order ends
     */
    public int getReturnToIdleOrder(int unitTypeIdentifier) {
        DatColumn values = this.returnToIdleOrder;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.RETURN_TO_IDLE_ORDER, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the order used to attack a unit
     */
    public int getAttackUnitOrder(int unitTypeIdentifier) {
        DatColumn values = this.attackUnitOrder;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.ATTACK_UNIT_ORDER, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the order used to attack-move
     */
    public int getAttackMoveOrder(int unitTypeIdentifier) {
        DatColumn values = this.attackMoveOrder;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.ATTACK_MOVE_ORDER, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the weapons.dat entry of the ground weapon, {@code 130} for none
     */
    public int getGroundWeapon(int unitTypeIdentifier) {
        DatColumn values = this.groundWeapon;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.GROUND_WEAPON, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the number of ground weapon hits per attack
     */
    public int getMaximumGroundHits(int unitTypeIdentifier) {
        DatColumn values = this.maximumGroundHits;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.MAXIMUM_GROUND_HITS, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the weapons.dat entry of the air weapon, {@code 130} for none
     */
    public int getAirWeapon(int unitTypeIdentifier) {
        DatColumn values = this.airWeapon;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.AIR_WEAPON, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the number of air weapon hits per attack
     */
    public int getMaximumAirHits(int unitTypeIdentifier) {
        DatColumn values = this.maximumAirHits;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.MAXIMUM_AIR_HITS, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the computer AI flags
     */
    public int getComputerInternalFlags(int unitTypeIdentifier) {
        DatColumn values = this.computerInternalFlags;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.COMPUTER_INTERNAL_FLAGS, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the special ability flags ({@link UnitTypeFlags})
     */
    public int getFlags(int unitTypeIdentifier) {
        DatColumn values = this.flags;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.FLAGS, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the target acquisition range in tiles
     */
    public int getTargetAcquisitionRange(int unitTypeIdentifier) {
        DatColumn values = this.targetAcquisitionRange;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.TARGET_ACQUISITION_RANGE, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the sight range in tiles
     */
    public int getSightRange(int unitTypeIdentifier) {
        DatColumn values = this.sightRange;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.SIGHT_RANGE, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the upgrades.dat entry that raises the armor
     */
    public int getArmorUpgrade(int unitTypeIdentifier) {
        DatColumn values = this.armorUpgrade;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.ARMOR_UPGRADE, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the size class that scales weapon damage (independent, small, medium, large)
     */
    public int getSizeClass(int unitTypeIdentifier) {
        DatColumn values = this.sizeClass;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.SIZE_CLASS, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the base armor
     */
    public int getArmor(int unitTypeIdentifier) {
        DatColumn values = this.armor;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.ARMOR, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the right-click action, one of the {@code RIGHT_CLICK_*} values
     */
    public int getRightClickAction(int unitTypeIdentifier) {
        DatColumn values = this.rightClickAction;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.RIGHT_CLICK_ACTION, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the sfxdata.dat entry played when the unit is ready (stored for the types below {@link #FIRST_INFESTATION_TYPE})
     */
    public int getReadySound(int unitTypeIdentifier) {
        DatColumn values = this.readySound;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.READY_SOUND, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the first sfxdata.dat entry of the selection sounds
     */
    public int getWhatSoundStart(int unitTypeIdentifier) {
        DatColumn values = this.whatSoundStart;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.WHAT_SOUND_START, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the last sfxdata.dat entry of the selection sounds
     */
    public int getWhatSoundEnd(int unitTypeIdentifier) {
        DatColumn values = this.whatSoundEnd;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.WHAT_SOUND_END, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the first sfxdata.dat entry of the annoyed sounds (stored for the types below {@link #FIRST_INFESTATION_TYPE})
     */
    public int getAnnoyedSoundStart(int unitTypeIdentifier) {
        DatColumn values = this.annoyedSoundStart;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.ANNOYED_SOUND_START, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the last sfxdata.dat entry of the annoyed sounds (stored for the types below {@link #FIRST_INFESTATION_TYPE})
     */
    public int getAnnoyedSoundEnd(int unitTypeIdentifier) {
        DatColumn values = this.annoyedSoundEnd;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.ANNOYED_SOUND_END, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the first sfxdata.dat entry of the acknowledgement sounds (stored for the types below {@link #FIRST_INFESTATION_TYPE})
     */
    public int getYesSoundStart(int unitTypeIdentifier) {
        DatColumn values = this.yesSoundStart;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.YES_SOUND_START, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the last sfxdata.dat entry of the acknowledgement sounds (stored for the types below {@link #FIRST_INFESTATION_TYPE})
     */
    public int getYesSoundEnd(int unitTypeIdentifier) {
        DatColumn values = this.yesSoundEnd;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.YES_SOUND_END, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the placement box width in pixels
     */
    public int getPlacementWidth(int unitTypeIdentifier) {
        DatColumn values = this.placementWidth;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.PLACEMENT_WIDTH, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the placement box height in pixels
     */
    public int getPlacementHeight(int unitTypeIdentifier) {
        DatColumn values = this.placementHeight;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.PLACEMENT_HEIGHT, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the horizontal add-on position relative to the parent building in pixels (stored for buildings from {@link #FIRST_INFESTATION_TYPE} on)
     */
    public int getAddonHorizontalOffset(int unitTypeIdentifier) {
        int element = unitTypeIdentifier - FIRST_INFESTATION_TYPE;
        DatColumn values = this.addonHorizontalOffset;

        return element >= 0 && element < values.getLength()
               ? values.get(element)
               : this.readOutside(Column.ADDON_HORIZONTAL_OFFSET, unitTypeIdentifier, element);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the vertical add-on position relative to the parent building in pixels (stored for buildings from {@link #FIRST_INFESTATION_TYPE} on)
     */
    public int getAddonVerticalOffset(int unitTypeIdentifier) {
        int element = unitTypeIdentifier - FIRST_INFESTATION_TYPE;
        DatColumn values = this.addonVerticalOffset;

        return element >= 0 && element < values.getLength()
               ? values.get(element)
               : this.readOutside(Column.ADDON_VERTICAL_OFFSET, unitTypeIdentifier, element);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the extent left of the center in pixels (unsigned WORD)
     */
    public int getDimensionLeft(int unitTypeIdentifier) {
        DatColumn values = this.dimensionLeft;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.DIMENSION_LEFT, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the extent above the center in pixels (unsigned WORD)
     */
    public int getDimensionUp(int unitTypeIdentifier) {
        DatColumn values = this.dimensionUp;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.DIMENSION_UP, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the extent right of the center in pixels (unsigned WORD)
     */
    public int getDimensionRight(int unitTypeIdentifier) {
        DatColumn values = this.dimensionRight;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.DIMENSION_RIGHT, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the extent below the center in pixels (unsigned WORD)
     */
    public int getDimensionDown(int unitTypeIdentifier) {
        DatColumn values = this.dimensionDown;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.DIMENSION_DOWN, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the portdata.dat entry of the portrait
     */
    public int getPortrait(int unitTypeIdentifier) {
        DatColumn values = this.portrait;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.PORTRAIT, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the mineral cost
     */
    public int getMineralCost(int unitTypeIdentifier) {
        DatColumn values = this.mineralCost;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.MINERAL_COST, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the vespene gas cost
     */
    public int getGasCost(int unitTypeIdentifier) {
        DatColumn values = this.gasCost;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.GAS_COST, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * Reads the build time of a unit type. A type from {@code 228} on that is not an added type has no build-time entry and reads the requirement
     * index column at the type minus {@code 228}, which holds the live values the requirement tests write.
     *
     * @param unitTypeIdentifier the units.dat entry
     * @return the build time in frames
     */
    public int getBuildTime(int unitTypeIdentifier) {
        if (unitTypeIdentifier >= BUILD_TIME_TYPE_COUNT && !this.isAddedType(unitTypeIdentifier)) {
            return this.getRequirementIndex(unitTypeIdentifier - BUILD_TIME_TYPE_COUNT);
        }

        return this.getBuildTimeEntry(unitTypeIdentifier);
    }

    /**
     * Reads the Build Time column at a unit type without the build-time lookup of {@link #getBuildTime(int)}: a type from {@code 228} on reads past
     * the column through the overrun reader.
     *
     * @param unitTypeIdentifier the units.dat entry
     * @return the column entry
     */
    public int getBuildTimeEntry(int unitTypeIdentifier) {
        DatColumn values = this.buildTime;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.BUILD_TIME, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the position of the unit in the build requirement script, {@code 0xFFFF} until built
     */
    public int getRequirementIndex(int unitTypeIdentifier) {
        DatColumn values = this.requirementIndex;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.REQUIREMENT_INDEX, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the group flags ({@link UnitGroupFlags})
     */
    public int getGroupFlags(int unitTypeIdentifier) {
        DatColumn values = this.groupFlags;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.GROUP_FLAGS, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the supply provided in half units
     */
    public int getSupplyProvided(int unitTypeIdentifier) {
        DatColumn values = this.supplyProvided;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.SUPPLY_PROVIDED, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the supply required in half units
     */
    public int getSupplyRequired(int unitTypeIdentifier) {
        DatColumn values = this.supplyRequired;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.SUPPLY_REQUIRED, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the transport space the unit occupies
     */
    public int getSpaceRequired(int unitTypeIdentifier) {
        DatColumn values = this.spaceRequired;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.SPACE_REQUIRED, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the transport space the unit provides
     */
    public int getSpaceProvided(int unitTypeIdentifier) {
        DatColumn values = this.spaceProvided;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.SPACE_PROVIDED, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the score for producing the unit
     */
    public int getBuildScore(int unitTypeIdentifier) {
        DatColumn values = this.buildScore;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.BUILD_SCORE, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the score for destroying the unit
     */
    public int getDestroyScore(int unitTypeIdentifier) {
        DatColumn values = this.destroyScore;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.DESTROY_SCORE, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the map string index of the unit name, {@code 0} for the default name
     */
    public int getMapStringIndex(int unitTypeIdentifier) {
        DatColumn values = this.mapStringIndex;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.MAP_STRING_INDEX, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return non-zero if the unit belongs to Brood War
     */
    public int getBroodWarFlag(int unitTypeIdentifier) {
        DatColumn values = this.broodWarFlag;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.BROOD_WAR_FLAG, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return the editor availability flags
     */
    public int getAvailabilityFlags(int unitTypeIdentifier) {
        DatColumn values = this.availabilityFlags;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readOutside(Column.AVAILABILITY_FLAGS, unitTypeIdentifier, unitTypeIdentifier);
    }

    /**
     * Reads the trait mask of a unit type, the bits of {@link UnitTraitFlags}. A type outside the column has no trait.
     *
     * @param unitTypeIdentifier the units.dat entry
     * @return the trait mask, {@code 0} for a type outside the column
     */
    public int getTraits(int unitTypeIdentifier) {
        DatColumn values = this.traits;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readAddedTrait(TraitColumn.TRAITS, unitTypeIdentifier, 0);
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type is a Ghost type ({@link UnitTraitFlags#GHOST}), {@code false} otherwise
     */
    public boolean isGhost(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.GHOST) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if a Zerg building morphs into the type ({@link UnitTraitFlags#BUILDING_MORPH_RESULT}), {@code false} otherwise
     */
    public boolean isBuildingMorphType(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.BUILDING_MORPH_RESULT) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type has a hangar ({@link UnitTraitFlags#HANGAR}), {@code false} otherwise
     */
    public boolean hasHangar(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.HANGAR) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the hangar of the type holds Interceptors ({@link UnitTraitFlags#INTERCEPTOR_HANGAR}), {@code false} otherwise
     */
    public boolean isCarrier(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.INTERCEPTOR_HANGAR) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the hangar of the type holds Scarabs ({@link UnitTraitFlags#SCARAB_HANGAR}), {@code false} otherwise
     */
    public boolean isReaver(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.SCARAB_HANGAR) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type is an egg that morphs into the queued type ({@link UnitTraitFlags#EGG}), {@code false} otherwise
     */
    public boolean isEgg(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.EGG) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type is a gas structure ({@link UnitTraitFlags#GAS_STRUCTURE}), {@code false} otherwise
     */
    public boolean isGasStructure(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.GAS_STRUCTURE) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type is the Larva or an egg ({@link UnitTraitFlags#LARVA_OR_EGG}), {@code false} otherwise
     */
    public boolean isLarvaOrEgg(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.LARVA_OR_EGG) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type is a mineral field ({@link UnitTraitFlags#MINERAL_FIELD}), {@code false} otherwise
     */
    public boolean isMineralField(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.MINERAL_FIELD) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type is a rally producer ({@link UnitTraitFlags#RALLY_PRODUCER}), {@code false} otherwise
     */
    public boolean isRallyProducer(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.RALLY_PRODUCER) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type is a beacon ({@link UnitTraitFlags#BEACON}), {@code false} otherwise
     */
    public boolean isBeacon(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.BEACON) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type is a Vulture type ({@link UnitTraitFlags#VULTURE}), {@code false} otherwise
     */
    public boolean isVulture(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.VULTURE) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type is a map doodad unit ({@link UnitTraitFlags#MAP_DOODAD}), {@code false} otherwise
     */
    public boolean isMapDoodad(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.MAP_DOODAD) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type is a trap ({@link UnitTraitFlags#TRAP}), {@code false} otherwise
     */
    public boolean isTrap(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.TRAP) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type is a map revealer ({@link UnitTraitFlags#MAP_REVEALER}), {@code false} otherwise
     */
    public boolean isMapRevealer(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.MAP_REVEALER) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type is a hangar unit ({@link UnitTraitFlags#HANGAR_UNIT}), {@code false} otherwise
     */
    public boolean isHangarUnit(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.HANGAR_UNIT) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type never collides with other units ({@link UnitTraitFlags#COLLISION_FREE}), {@code false} otherwise
     */
    public boolean isCollisionFree(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.COLLISION_FREE) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the sprite visibility of the type follows the building rules ({@link UnitTraitFlags#BUILDING_VISIBILITY}), {@code false} otherwise
     */
    public boolean hasBuildingVisibility(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.BUILDING_VISIBILITY) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type has no sight of its own ({@link UnitTraitFlags#SIGHTLESS}), {@code false} otherwise
     */
    public boolean isSightless(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.SIGHTLESS) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if a cloaking field never cloaks the type ({@link UnitTraitFlags#CLOAK_FIELD_EXEMPT}), {@code false} otherwise
     */
    public boolean isCloakFieldExempt(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.CLOAK_FIELD_EXEMPT) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the order timer of the type restarts whenever its ground weapon cooldown is over ({@link UnitTraitFlags#COOLDOWN_ORDER_TIMER}), {@code false} otherwise
     */
    public boolean hasCooldownOrderTimer(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.COOLDOWN_ORDER_TIMER) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type takes no part in the flyer repulsion ({@link UnitTraitFlags#NO_AIR_REPULSION}), {@code false} otherwise
     */
    public boolean isAirRepulsionExempt(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.NO_AIR_REPULSION) != 0;
    }

    /**
     * @param unitTypeIdentifier the units.dat entry
     * @return {@code true} if the type never blocks a local path ({@link UnitTraitFlags#PATH_TRANSPARENT}), {@code false} otherwise
     */
    public boolean isPathTransparent(int unitTypeIdentifier) {
        return (this.getTraits(unitTypeIdentifier) & UnitTraitFlags.PATH_TRANSPARENT) != 0;
    }

    /**
     * Reads the energy a unit type starts with when it is initialized.
     *
     * @param unitTypeIdentifier the units.dat entry
     * @return the initial energy with eight fraction bits, or {@link #DEFAULT_INITIAL_ENERGY} for a type that starts with its default share of its maximum energy, also outside the column
     */
    public int getInitialEnergy(int unitTypeIdentifier) {
        DatColumn values = this.initialEnergy;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readAddedTrait(TraitColumn.INITIAL_ENERGY, unitTypeIdentifier, DEFAULT_INITIAL_ENERGY);
    }

    /**
     * Reads the ammunition (Spider Mines) a unit type starts with when its storage is created.
     *
     * @param unitTypeIdentifier the units.dat entry
     * @return the initial ammunition, {@code 0} for a type that starts with none or outside the column
     */
    public int getInitialAmmunition(int unitTypeIdentifier) {
        DatColumn values = this.initialAmmunition;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readAddedTrait(TraitColumn.INITIAL_AMMUNITION, unitTypeIdentifier, 0);
    }

    /**
     * Reads the top speed a unit type moves at with a net speed bonus instead of the raised speed of other types.
     *
     * @param unitTypeIdentifier the units.dat entry
     * @return the upgraded top speed with eight fraction bits, {@code 0} for a type without one or outside the column
     */
    public int getUpgradedTopSpeed(int unitTypeIdentifier) {
        DatColumn values = this.upgradedTopSpeed;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readAddedTrait(TraitColumn.UPGRADED_TOP_SPEED, unitTypeIdentifier, 0);
    }

    /**
     * Reads the hangar capacity of a unit type before its stat modifiers.
     *
     * @param unitTypeIdentifier the units.dat entry
     * @return the base hangar capacity, {@code 0} for a type without a hangar or outside the column
     */
    public int getHangarCapacity(int unitTypeIdentifier) {
        DatColumn values = this.hangarCapacity;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readAddedTrait(TraitColumn.HANGAR_CAPACITY, unitTypeIdentifier, 0);
    }

    /**
     * Reads the upgrade the owner of a unit type needs before the type carries units in its transport space.
     *
     * @param unitTypeIdentifier the units.dat entry
     * @return the upgrades.dat entry, {@link ScrUpgradeIds#NONE} for a type that needs none or outside the column
     */
    public int getTransportUpgrade(int unitTypeIdentifier) {
        DatColumn values = this.transportUpgrade;

        return unitTypeIdentifier >= 0 && unitTypeIdentifier < values.getLength()
               ? values.get(unitTypeIdentifier)
               : this.readAddedTrait(TraitColumn.TRANSPORT_UPGRADE, unitTypeIdentifier, ScrUpgradeIds.NONE);
    }

    /**
     * @return the writable {@link #getRequirementIndex(int)} column, which the requirement tests build at run time
     */
    public @NotNull RequirementIndexColumn getRequirementIndices() {
        return this.requirementIndices;
    }

    /**
     * Reads a column value of an id outside the column: the added column for an added unit type, the overrun reader for every other id.
     *
     * @param column             the column
     * @param unitTypeIdentifier the unit type id
     * @param element            the element of the id in the column, outside it
     * @return the value
     */
    private int readOutside(@NotNull Column column, int unitTypeIdentifier, int element) {
        int added = unitTypeIdentifier - FIRST_ADDED_TYPE;

        return added >= 0 && added < this.addedCount ? this.addedColumns[column.ordinal()].get(added) : this.overrunReader.read(column, element);
    }

    /**
     * Reads a trait column value of an id outside the trait column: the added trait column for an added unit type, the default for every other id.
     *
     * @param column             the trait column
     * @param unitTypeIdentifier the unit type id
     * @param defaultValue       the value of an id that is not an added type
     * @return the value
     */
    private int readAddedTrait(@NotNull TraitColumn column, int unitTypeIdentifier, int defaultValue) {
        int added = unitTypeIdentifier - FIRST_ADDED_TYPE;

        return added >= 0 && added < this.addedCount ? this.addedTraitColumns[column.ordinal()].get(added) : defaultValue;
    }
}
