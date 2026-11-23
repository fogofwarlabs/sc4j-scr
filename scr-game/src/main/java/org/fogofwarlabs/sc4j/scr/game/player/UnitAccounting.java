package org.fogofwarlabs.sc4j.scr.game.player;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.table.PlayerProgressTable;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnKey;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnReset;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnTable;
import org.fogofwarlabs.sc4j.engine.entity.column.IntColumn;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.TriggerUnitGroups;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTraitFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.map.StartingUnitLoader;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Retains unit, building, production and supply counters.
 * <p>
 * Each counter has one semantic owner; saved snapshots are decoded outside the game runtime. The type counts and the completed type counts are the
 * unit counts of the engine {@link PlayerProgressTable}, the kill and death tables its SC:R extension columns ({@link PlayerScores}); the other
 * counters are SC:R state of this class. The type counts, the completed type counts, the kill table and the death table lie back to back in the
 * game context, so a type count past its table (unit type {@code 228}) is a counter of the next table ({@link PlayerUnitCounts}).
 */
public final class UnitAccounting {
    /**
     * The supply race index of Terran, after Zerg ({@code 0}): the second counter range of every supply kind.
     */
    private static final int TERRAN_SUPPLY_RACE = 1;
    /**
     * The supply race index of Protoss: the third counter range of every supply kind.
     */
    private static final int PROTOSS_SUPPLY_RACE = 2;
    /**
     * The supply counter base of Zerg: counters are {@code [base + player]}.
     */
    private static final int SUPPLY_ZERG = 0;
    /**
     * The supply counter base of Terran.
     */
    private static final int SUPPLY_TERRAN = TERRAN_SUPPLY_RACE * PlayerTable.PLAYER_SLOTS;
    /**
     * The supply counter base of Protoss.
     */
    private static final int SUPPLY_PROTOSS = PROTOSS_SUPPLY_RACE * PlayerTable.PLAYER_SLOTS;
    /**
     * The races with supply counters.
     */
    private static final int SUPPLY_RACES = 3;
    /**
     * The number of supply counters: every supply race for every player slot.
     */
    private static final int SUPPLY_COUNTERS = SUPPLY_RACES * PlayerTable.PLAYER_SLOTS;
    /**
     * The half-supply cap of one race of one player: {@code 200} supply.
     */
    private static final int SUPPLY_CAP = 400;
    /**
     * The leader BYTE of a force without a leader.
     */
    private static final int NO_LEADER = 0xFF;
    /**
     * The units of one egg of a unit type with two units in one egg, which doubles the egg's supply.
     */
    private static final int TWIN_EGG_UNITS = 2;
    /**
     * The mask of an unsigned byte: the doubled supply BYTE of a twin egg and the leader slot of a force.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * The bytes of one per-player counter range.
     */
    private static final int RANGE_SIZE = PlayerTable.PLAYER_SLOTS * Integer.BYTES;
    /**
     * Game-context offset of the first per-player counter range (unit counts).
     */
    private static final int COUNTERS_OFFSET = 0x2CF4;
    /**
     * Game-context offset of the unit counts, the first per-player counter range.
     */
    private static final int UNIT_COUNTS_OFFSET = COUNTERS_OFFSET;
    /**
     * Game-context offset of the completed unit counts.
     */
    private static final int COMPLETED_UNIT_COUNTS_OFFSET = 0x2D24;
    /**
     * Game-context offset of the produced unit counts.
     */
    private static final int PRODUCED_UNIT_COUNTS_OFFSET = 0x2D54;
    /**
     * Game-context offset of the unit production scores.
     */
    private static final int UNIT_PRODUCTION_SCORES_OFFSET = 0x2DE4;
    /**
     * Game-context offset of the building counts.
     */
    private static final int BUILDING_COUNTS_OFFSET = 0x2E44;
    /**
     * Game-context offset of the completed building counts.
     */
    private static final int COMPLETED_BUILDING_COUNTS_OFFSET = 0x2E74;
    /**
     * Game-context offset of the produced building counts.
     */
    private static final int PRODUCED_BUILDING_COUNTS_OFFSET = 0x2EA4;
    /**
     * Game-context offset of the building production scores.
     */
    private static final int BUILDING_PRODUCTION_SCORES_OFFSET = 0x2F34;
    /**
     * Game-context offset of the factory counts.
     */
    private static final int FACTORY_COUNTS_OFFSET = 0x2F94;
    /**
     * Game-context offset of the completed factory counts.
     */
    private static final int COMPLETED_FACTORY_COUNTS_OFFSET = 0x2FC4;
    /**
     * Game-context offset of the Zerg provided supply, the first supply range.
     */
    private static final int SUPPLY_PROVIDED_OFFSET = 0x3054;
    /**
     * Game-context offset of the Zerg used supply, after the Zerg provided supply.
     */
    private static final int SUPPLY_USED_OFFSET = SUPPLY_PROVIDED_OFFSET + RANGE_SIZE;
    /**
     * Game-context offset of the Zerg supply caps, after the Zerg used supply.
     */
    private static final int SUPPLY_CAPS_OFFSET = SUPPLY_USED_OFFSET + RANGE_SIZE;
    /**
     * The bytes of one race's supply ranges (provided, used, caps).
     */
    private static final int SUPPLY_RACE_SIZE = (SUPPLY_CAPS_OFFSET + RANGE_SIZE) - SUPPLY_PROVIDED_OFFSET;
    /**
     * Game-context offset of the all-units type counts ({@code type * PLAYER_SLOTS + player}).
     */
    private static final int TYPE_COUNTS_OFFSET = 0x3234;
    /**
     * Game-context offset of the completed type counts.
     */
    private static final int COMPLETED_TYPE_COUNTS_OFFSET = 0x5CF4;
    /**
     * Game-context offset after the completed type counts: the kill table ({@link PlayerScores#KILL_TABLE_OFFSET}).
     */
    private static final int TYPE_COUNTS_END = PlayerScores.KILL_TABLE_OFFSET;

    /**
     * Retains the counters observed when a trigger first prepares its evaluation pass.
     */
    public static final class TriggerSnapshot {
        /**
         * The number of type count tables that the count-cache clear resets: the completed and the all-units type counts.
         */
        private static final int CLEARED_TYPE_TABLES = 2;
        /**
         * The counters of the type count tables that the count-cache clear resets: the completed type counts, then the all-units type counts.
         */
        private static final int CLEARED_TYPE_COUNTERS = CLEARED_TYPE_TABLES * PlayerUnitCounts.NATIVE_COUNT;
        /**
         * The touched count that marks every type counter as possibly nonzero.
         */
        private static final int UNKNOWN_TOUCHED_COUNT = -1;
        /**
         * The multiplier that folds each cached counter into the diagnostic hash.
         */
        private static final int HASH_MULTIPLIER = 31;
        /**
         * The cached all-units type counts, one per player and unit type.
         */
        private static final @NotNull ColumnKey<IntColumn> TYPE_COUNTS = ColumnKey.ofInt("scr.triggerTypeCounts", 0, ColumnReset.KEEP);
        /**
         * The cached completed type counts, one per player and unit type.
         */
        private static final @NotNull ColumnKey<IntColumn> COMPLETED_TYPE_COUNTS = ColumnKey.ofInt("scr.triggerCompletedTypeCounts", 0,
                                                                                                   ColumnReset.KEEP);

        private final int @NotNull [] unitCounts;
        private final int @NotNull [] completedUnitCounts;
        private final int @NotNull [] producedUnitCounts;
        private final int @NotNull [] unitProductionScores;

        private final int @NotNull [] buildingCounts;
        private final int @NotNull [] completedBuildingCounts;
        private final int @NotNull [] producedBuildingCounts;
        private final int @NotNull [] buildingProductionScores;

        private final int @NotNull [] factoryCounts;
        private final int @NotNull [] completedFactoryCounts;

        private final int @NotNull [] supplyProvided;
        private final int @NotNull [] supplyUsed;
        private final int @NotNull [] supplyCaps;

        /**
         * The cache's own type counts: a copy of the game's counts or the counts of the location refills, not the game's counts.
         */
        private final @NotNull PlayerUnitCounts typeCounts;
        private final @NotNull PlayerUnitCounts completedTypeCounts;
        /**
         * The native indices of the type counters a location refill made nonzero since the last {@link #clear}; the first {@link #touchedCount} are
         * used.
         */
        private final int @NotNull [] touchedIndices;
        /**
         * The number of used {@link #touchedIndices}, or {@link #UNKNOWN_TOUCHED_COUNT} when any type counter may be nonzero (after a copy or too
         * many indices).
         */
        private int touchedCount;

        /**
         * Creates a {@link TriggerSnapshot} instance with every counter zero, without the game-context DWORDs after its all-units type counts.
         */
        public TriggerSnapshot() {
            this(null);
        }

        /**
         * Creates a {@link TriggerSnapshot} instance with every counter zero.
         * <p>
         * As in the game context, the completed type counts are followed by the all-units type counts, which are followed by the given DWORDs; an
         * index past a table addresses the table after it ({@link PlayerUnitCounts}).
         *
         * @param followingDwords the game-context DWORDs after the all-units type counts, or {@code null} if none are represented
         */
        public TriggerSnapshot(@Nullable ContextDwordTable followingDwords) {
            this.unitCounts           = new int[PlayerTable.PLAYER_SLOTS];
            this.completedUnitCounts  = new int[PlayerTable.PLAYER_SLOTS];
            this.producedUnitCounts   = new int[PlayerTable.PLAYER_SLOTS];
            this.unitProductionScores = new int[PlayerTable.PLAYER_SLOTS];

            this.buildingCounts           = new int[PlayerTable.PLAYER_SLOTS];
            this.completedBuildingCounts  = new int[PlayerTable.PLAYER_SLOTS];
            this.producedBuildingCounts   = new int[PlayerTable.PLAYER_SLOTS];
            this.buildingProductionScores = new int[PlayerTable.PLAYER_SLOTS];

            this.factoryCounts          = new int[PlayerTable.PLAYER_SLOTS];
            this.completedFactoryCounts = new int[PlayerTable.PLAYER_SLOTS];

            this.supplyProvided = new int[SUPPLY_COUNTERS];
            this.supplyUsed     = new int[SUPPLY_COUNTERS];
            this.supplyCaps     = new int[SUPPLY_COUNTERS];

            ColumnTable typeCountColumns = new ColumnTable(List.of(TYPE_COUNTS, COMPLETED_TYPE_COUNTS), PlayerUnitCounts.NATIVE_COUNT);

            this.typeCounts          = PlayerUnitCounts.ofColumn(typeCountColumns.get(TYPE_COUNTS));
            this.completedTypeCounts = PlayerUnitCounts.ofColumn(typeCountColumns.get(COMPLETED_TYPE_COUNTS));
            this.touchedIndices      = new int[PlayerUnitCounts.NATIVE_COUNT];
            this.touchedCount        = 0;

            this.completedTypeCounts.setSuccessor(this.typeCounts);

            if (followingDwords != null) {
                this.typeCounts.setSuccessor(followingDwords);
            }
        }

        /**
         * @param player the player slot
         * @return the cached building count of the player
         */
        public int getBuildingCount(int player) {
            return this.buildingCounts[player];
        }

        /**
         * Reads the cached count the trigger count conditions select for one player: {@link TriggerUnitGroups#ANY_UNIT} (men and buildings),
         * {@link TriggerUnitGroups#MEN}, {@link TriggerUnitGroups#BUILDINGS}, {@link TriggerUnitGroups#FACTORIES} or a single unit type.
         *
         * @param player    the player slot
         * @param unitType  the unit type or trigger unit group
         * @param completed whether the completed counts are read
         * @return the cached count
         */
        public int getCommandCount(int player, int unitType, boolean completed) {
            return switch (unitType) {
                case TriggerUnitGroups.ANY_UNIT -> completed
                                                   ? this.completedUnitCounts[player] + this.completedBuildingCounts[player]
                                                   : this.unitCounts[player] + this.buildingCounts[player];
                case TriggerUnitGroups.MEN -> completed ? this.completedUnitCounts[player] : this.unitCounts[player];
                case TriggerUnitGroups.BUILDINGS -> completed ? this.completedBuildingCounts[player] : this.buildingCounts[player];
                case TriggerUnitGroups.FACTORIES -> completed ? this.completedFactoryCounts[player] : this.factoryCounts[player];
                default -> completed ? this.completedTypeCounts.get(unitType, player) : this.typeCounts.get(unitType, player);
            };
        }


        /**
         * Clears every cached range and resets the touched type counters.
         */
        public void clear() {
            Arrays.fill(this.unitCounts, 0);
            Arrays.fill(this.completedUnitCounts, 0);
            Arrays.fill(this.producedUnitCounts, 0);
            Arrays.fill(this.unitProductionScores, 0);
            Arrays.fill(this.buildingCounts, 0);
            Arrays.fill(this.completedBuildingCounts, 0);
            Arrays.fill(this.producedBuildingCounts, 0);
            Arrays.fill(this.buildingProductionScores, 0);
            Arrays.fill(this.factoryCounts, 0);
            Arrays.fill(this.completedFactoryCounts, 0);
            Arrays.fill(this.supplyProvided, 0);
            Arrays.fill(this.supplyUsed, 0);
            Arrays.fill(this.supplyCaps, 0);

            if (this.touchedCount < 0) {
                this.typeCounts.clear();
                this.completedTypeCounts.clear();
            } else {
                // Only the counters a refill made nonzero can be nonzero.
                for (int index = 0; index < this.touchedCount; index++) {
                    this.typeCounts.setByNativeIndex(this.touchedIndices[index], 0);
                    this.completedTypeCounts.setByNativeIndex(this.touchedIndices[index], 0);
                }
            }

            this.touchedCount = 0;
        }

        /**
         * Counts one unit found in a location: the type count, the factory count and the men count (also an egg type without the building group flag)
         * or the building count.
         *
         * @param unitType   the unit type
         * @param owner      the owning player
         * @param groupFlags the unit type's group flags
         * @param traits     the unit type's traits ({@link ScrUnitTypeView#getTraits(int)})
         */
        public void addLocationUnit(int unitType, int owner, int groupFlags, int traits) {
            if (this.typeCounts.get(unitType, owner) == 0) {
                this.recordTouchedType(PlayerUnitCounts.NATIVE_COUNT + unitType * PlayerTable.PLAYER_SLOTS + owner);
            }

            this.typeCounts.increment(unitType, owner);

            if ((groupFlags & UnitGroupFlags.FACTORY) != 0) {
                this.factoryCounts[owner]++;
            }

            if ((groupFlags & UnitGroupFlags.MEN) != 0 || (groupFlags & UnitGroupFlags.BUILDING) == 0 && (traits & UnitTraitFlags.EGG) != 0) {
                this.unitCounts[owner]++;
            } else if ((groupFlags & UnitGroupFlags.BUILDING) != 0) {
                this.buildingCounts[owner]++;
            }
        }

        /**
         * Adds the completed counts of a unit in a location.
         *
         * @param owner      the owning player
         * @param groupFlags the unit type's group flags
         */
        public void addCompletedLocationUnit(int owner, int groupFlags) {
            if ((groupFlags & UnitGroupFlags.FACTORY) != 0) {
                this.completedFactoryCounts[owner]++;
            }

            if ((groupFlags & UnitGroupFlags.MEN) != 0) {
                this.completedUnitCounts[owner]++;
            } else if ((groupFlags & UnitGroupFlags.BUILDING) != 0) {
                this.completedBuildingCounts[owner]++;
            }
        }

        /**
         * Adds to one completed type count of the location cache.
         *
         * @param unitType the unit type
         * @param owner    the owning player
         * @param count    the completed units to add
         */
        public void addCompletedType(int unitType, int owner, int count) {
            if (this.completedTypeCounts.get(unitType, owner) == 0) {
                this.recordTouchedType(unitType * PlayerTable.PLAYER_SLOTS + owner);
            }

            this.completedTypeCounts.add(unitType, owner, count);
        }

        /**
         * Folds every cached counter into a diagnostic hash.
         *
         * @param hash the preceding hash
         * @return the hash including every cached counter
         */
        public int computeStateHash(int hash) {
            for (int value : this.unitCounts) {
                hash = HASH_MULTIPLIER * hash + value;
            }

            for (int value : this.completedUnitCounts) {
                hash = HASH_MULTIPLIER * hash + value;
            }

            for (int value : this.producedUnitCounts) {
                hash = HASH_MULTIPLIER * hash + value;
            }

            for (int value : this.unitProductionScores) {
                hash = HASH_MULTIPLIER * hash + value;
            }

            for (int value : this.buildingCounts) {
                hash = HASH_MULTIPLIER * hash + value;
            }

            for (int value : this.completedBuildingCounts) {
                hash = HASH_MULTIPLIER * hash + value;
            }

            for (int value : this.producedBuildingCounts) {
                hash = HASH_MULTIPLIER * hash + value;
            }

            for (int value : this.buildingProductionScores) {
                hash = HASH_MULTIPLIER * hash + value;
            }

            for (int value : this.factoryCounts) {
                hash = HASH_MULTIPLIER * hash + value;
            }

            for (int value : this.completedFactoryCounts) {
                hash = HASH_MULTIPLIER * hash + value;
            }

            for (int value : this.supplyProvided) {
                hash = HASH_MULTIPLIER * hash + value;
            }

            for (int value : this.supplyUsed) {
                hash = HASH_MULTIPLIER * hash + value;
            }

            for (int value : this.supplyCaps) {
                hash = HASH_MULTIPLIER * hash + value;
            }

            hash = this.typeCounts.computeStateHash(hash);
            hash = this.completedTypeCounts.computeStateHash(hash);

            return hash;
        }

        /**
         * Records a type counter about to change from zero, by its index from the start of the completed type counts, which the all-units type counts
         * follow; {@link #clear} clears both tables at the index within its table. A counter past the all-units type counts is not recorded, since
         * the count-cache clear clears the tables only.
         *
         * @param index the counter index from the start of the completed type counts
         */
        private void recordTouchedType(int index) {
            if (this.touchedCount < 0 || index >= CLEARED_TYPE_COUNTERS) {
                return;
            }

            if (this.touchedCount == this.touchedIndices.length) {
                this.touchedCount = UNKNOWN_TOUCHED_COUNT;

                return;
            }

            this.touchedIndices[this.touchedCount++] = index % PlayerUnitCounts.NATIVE_COUNT;
        }
    }


    /**
     * Returns the index into the array {@link #findCounterRange} returns: the supply counters hold every supply race of every player, the race ranges
     * {@link #SUPPLY_RACE_SIZE} bytes apart.
     *
     * @param contextOffset an aligned game-context offset of a per-player counter range
     * @return the counter index
     */
    private static int getCounterIndex(int contextOffset) {
        int range = COUNTERS_OFFSET + (contextOffset - COUNTERS_OFFSET) / RANGE_SIZE * RANGE_SIZE;
        int player = (contextOffset - range) / Integer.BYTES;

        if (range < SUPPLY_PROVIDED_OFFSET) {
            return player;
        }

        int race = (range - SUPPLY_PROVIDED_OFFSET) / SUPPLY_RACE_SIZE;

        return race * PlayerTable.PLAYER_SLOTS + player;
    }

    private static long mixHash(long hash, int value) {
        hash ^= Integer.toUnsignedLong(value);
        hash *= Game.STATE_HASH_PRIME;
        hash ^= hash >>> Game.STATE_HASH_FOLD_SHIFT;

        return hash;
    }

    private final int @NotNull [] unitCounts;
    private final int @NotNull [] completedUnitCounts;
    private final int @NotNull [] producedUnitCounts;
    private final int @NotNull [] unitProductionScores;

    private final int @NotNull [] buildingCounts;
    private final int @NotNull [] completedBuildingCounts;
    private final int @NotNull [] producedBuildingCounts;
    private final int @NotNull [] buildingProductionScores;

    private final int @NotNull [] factoryCounts;
    private final int @NotNull [] completedFactoryCounts;

    private final int @NotNull [] supplyProvided;
    private final int @NotNull [] supplyUsed;
    private final int @NotNull [] supplyCaps;

    private final @NotNull PlayerUnitCounts typeCounts;
    private final @NotNull PlayerUnitCounts completedTypeCounts;
    private final @NotNull PlayerScores scores;

    private boolean supplyProvidedIgnored;

    /**
     * Creates a {@link UnitAccounting} instance over a progress table with zeroed per-player state; game initialization establishes the supply caps.
     *
     * @param progress the progress table of the game: type counts, completed type counts and, in {@link PlayerProgressColumns}, the death and kill tables
     */
    public UnitAccounting(@NotNull PlayerProgressTable progress) {
        this.unitCounts           = new int[PlayerTable.PLAYER_SLOTS];
        this.completedUnitCounts  = new int[PlayerTable.PLAYER_SLOTS];
        this.producedUnitCounts   = new int[PlayerTable.PLAYER_SLOTS];
        this.unitProductionScores = new int[PlayerTable.PLAYER_SLOTS];

        this.buildingCounts           = new int[PlayerTable.PLAYER_SLOTS];
        this.completedBuildingCounts  = new int[PlayerTable.PLAYER_SLOTS];
        this.producedBuildingCounts   = new int[PlayerTable.PLAYER_SLOTS];
        this.buildingProductionScores = new int[PlayerTable.PLAYER_SLOTS];

        this.factoryCounts          = new int[PlayerTable.PLAYER_SLOTS];
        this.completedFactoryCounts = new int[PlayerTable.PLAYER_SLOTS];

        this.supplyProvided = new int[SUPPLY_COUNTERS];
        this.supplyUsed     = new int[SUPPLY_COUNTERS];
        this.supplyCaps     = new int[SUPPLY_COUNTERS];

        this.typeCounts          = PlayerUnitCounts.ofUnitCounts(progress);
        this.completedTypeCounts = PlayerUnitCounts.ofCompletedUnitCounts(progress);
        this.scores              = new PlayerScores(progress);

        this.supplyProvidedIgnored = false;

        this.typeCounts.clear();
        this.completedTypeCounts.clear();

        // The type counts, the completed type counts and the kill table lie back to back in the game context.
        this.typeCounts.setSuccessor(this.completedTypeCounts);
        this.completedTypeCounts.setSuccessor(this.scores.getKillTable());
    }

    /**
     * @return the death, kill and score counters outside the state hash
     */
    public @NotNull PlayerScores getScores() {
        return this.scores;
    }

    /**
     * Returns the units produced (score screen "Units Produced"), counted by the completed-unit accounting for men other than Guardian, Devourer,
     * Lurker, Archon and Dark Archon.
     *
     * @param player the player slot
     * @return the produced unit count
     */
    public int getProducedUnitCount(int player) {
        return this.producedUnitCounts[player];
    }

    /**
     * Returns the buildings constructed (score screen "Buildings Constructed"), counted by the completed-unit accounting for buildings other than
     * Lair, Hive, Greater Spire, Spore and Sunken Colony.
     *
     * @param player the player slot
     * @return the constructed building count
     */
    public int getProducedBuildingCount(int player) {
        return this.producedBuildingCounts[player];
    }

    /**
     * @param player the player slot
     * @return the unit production score
     */
    public int getUnitProductionScore(int player) {
        return this.unitProductionScores[player];
    }

    /**
     * Sets the unit production score of a player.
     *
     * @param player the player slot
     * @param value  the new score
     */
    public void setUnitProductionScore(int player, int value) {
        this.unitProductionScores[player] = value;
    }

    /**
     * @param player the player slot
     * @return the building production score
     */
    public int getBuildingProductionScore(int player) {
        return this.buildingProductionScores[player];
    }

    /**
     * Sets the building production score of a player.
     *
     * @param player the player slot
     * @param value  the new score
     */
    public void setBuildingProductionScore(int player, int value) {
        this.buildingProductionScores[player] = value;
    }

    /**
     * @param unitType the {@code units.dat} type
     * @param player   the player slot
     * @return the completed unit count of the type
     */
    public int getCompletedTypeCount(int unitType, int player) {
        return this.completedTypeCounts.get(unitType, player);
    }

    /**
     * @param unitType the {@code units.dat} type
     * @param player   the player slot
     * @return the unit count of the type, including incomplete units
     */
    public int getTypeCount(int unitType, int player) {
        return this.typeCounts.get(unitType, player);
    }

    /**
     * @param counterIndex the race base (Zerg {@code 0}, Terran {@link PlayerTable#PLAYER_SLOTS}, Protoss {@code 2 * PLAYER_SLOTS}) plus the player
     * @return the provided half-supply DWORD
     */
    public int getSupplyProvided(int counterIndex) {
        return this.supplyProvided[counterIndex];
    }

    /**
     * @param counterIndex the race base (Zerg {@code 0}, Terran {@link PlayerTable#PLAYER_SLOTS}, Protoss {@code 2 * PLAYER_SLOTS}) plus the player
     * @return the used half-supply DWORD
     */
    public int getSupplyUsed(int counterIndex) {
        return this.supplyUsed[counterIndex];
    }

    /**
     * @param counterIndex the race base (Zerg {@code 0}, Terran {@link PlayerTable#PLAYER_SLOTS}, Protoss {@code 2 * PLAYER_SLOTS}) plus the player
     * @return the half-supply cap DWORD
     */
    public int getSupplyCap(int counterIndex) {
        return this.supplyCaps[counterIndex];
    }

    /**
     * @param counterIndex the race base (Zerg {@code 0}, Terran {@link PlayerTable#PLAYER_SLOTS}, Protoss {@code 2 * PLAYER_SLOTS}) plus the player
     * @return the available half-supply DWORD: the smaller of the provided supply and the cap, compared as unsigned values
     */
    public int getSupplyAvailable(int counterIndex) {
        int provided = this.supplyProvided[counterIndex];
        int cap = this.supplyCaps[counterIndex];

        return Integer.compareUnsigned(provided, cap) < 0 ? provided : cap;
    }

    /**
     * Changes a provided half-supply DWORD, as the engine economy view does for a supply provider it is told about.
     *
     * @param counterIndex the race base (Zerg {@code 0}, Terran {@link PlayerTable#PLAYER_SLOTS}, Protoss {@code 2 * PLAYER_SLOTS}) plus the player
     * @param change       the change, negative when a provider is lost
     */
    public void addSupplyProvided(int counterIndex, int change) {
        this.supplyProvided[counterIndex] += change;
    }

    /**
     * Changes a used half-supply DWORD, as the engine economy view does for a supply user it is told about.
     *
     * @param counterIndex the race base (Zerg {@code 0}, Terran {@link PlayerTable#PLAYER_SLOTS}, Protoss {@code 2 * PLAYER_SLOTS}) plus the player
     * @param change       the change, negative when a unit is lost
     */
    public void addSupplyUsed(int counterIndex, int change) {
        this.supplyUsed[counterIndex] += change;
    }

    /**
     * Sets whether the provided supply is ignored, as the Food for Thought cheat does: {@link #hasAvailableSupply} then checks a request against the
     * cap and the used supply only.
     *
     * @param supplyProvidedIgnored whether the cheat bit is set
     */
    public void setSupplyProvidedIgnored(boolean supplyProvidedIgnored) {
        this.supplyProvidedIgnored = supplyProvidedIgnored;
    }

    /**
     * Checks half-supply counters without reserving supply for a queued unit.
     *
     * @param owner      the owning player
     * @param raceOffset the race counter base: Zerg {@code 0}, Terran {@link PlayerTable#PLAYER_SLOTS}, Protoss {@code 2 * PLAYER_SLOTS}
     * @param required   the requested half-supply amount
     * @return {@code true} if the request is zero, or the cap and, unless it is ignored, the provided supply allow the request, {@code false} otherwise
     */
    public boolean hasAvailableSupply(int owner, int raceOffset, int required) {
        if (required == 0) {
            return true;
        }

        int counterIndex = raceOffset + owner;
        int total = this.supplyUsed[counterIndex] + required;

        return total <= this.supplyCaps[counterIndex] && (this.supplyProvidedIgnored || total <= this.supplyProvided[counterIndex]);
    }


    /**
     * Mixes the represented counters into a deterministic domain-state hash.
     *
     * @param hash the preceding hash
     * @return the hash including accounting state
     */
    public long computeStateHash(long hash) {
        for (int value : this.unitCounts) {
            hash = mixHash(hash, value);
        }

        for (int value : this.completedUnitCounts) {
            hash = mixHash(hash, value);
        }

        for (int value : this.producedUnitCounts) {
            hash = mixHash(hash, value);
        }

        for (int value : this.unitProductionScores) {
            hash = mixHash(hash, value);
        }

        for (int value : this.buildingCounts) {
            hash = mixHash(hash, value);
        }

        for (int value : this.completedBuildingCounts) {
            hash = mixHash(hash, value);
        }

        for (int value : this.producedBuildingCounts) {
            hash = mixHash(hash, value);
        }

        for (int value : this.buildingProductionScores) {
            hash = mixHash(hash, value);
        }

        for (int value : this.factoryCounts) {
            hash = mixHash(hash, value);
        }

        for (int value : this.completedFactoryCounts) {
            hash = mixHash(hash, value);
        }

        for (int value : this.supplyProvided) {
            hash = mixHash(hash, value);
        }

        for (int value : this.supplyUsed) {
            hash = mixHash(hash, value);
        }

        for (int value : this.supplyCaps) {
            hash = mixHash(hash, value);
        }

        for (int index = 0; index < PlayerUnitCounts.NATIVE_COUNT; index++) {
            hash = mixHash(hash, this.typeCounts.getByNativeIndex(index));
        }

        for (int index = 0; index < PlayerUnitCounts.NATIVE_COUNT; index++) {
            hash = mixHash(hash, this.completedTypeCounts.getByNativeIndex(index));
        }

        return hash;
    }

    /**
     * Copies every counter into a trigger snapshot; the snapshot then treats every type counter as possibly nonzero.
     *
     * @param destination the snapshot receiving the counters
     */
    public void copyTriggerSnapshot(@NotNull TriggerSnapshot destination) {
        System.arraycopy(this.unitCounts, 0, destination.unitCounts, 0, PlayerTable.PLAYER_SLOTS);
        System.arraycopy(this.completedUnitCounts, 0, destination.completedUnitCounts, 0, PlayerTable.PLAYER_SLOTS);
        System.arraycopy(this.producedUnitCounts, 0, destination.producedUnitCounts, 0, PlayerTable.PLAYER_SLOTS);
        System.arraycopy(this.unitProductionScores, 0, destination.unitProductionScores, 0, PlayerTable.PLAYER_SLOTS);
        System.arraycopy(this.buildingCounts, 0, destination.buildingCounts, 0, PlayerTable.PLAYER_SLOTS);
        System.arraycopy(this.completedBuildingCounts, 0, destination.completedBuildingCounts, 0, PlayerTable.PLAYER_SLOTS);
        System.arraycopy(this.producedBuildingCounts, 0, destination.producedBuildingCounts, 0, PlayerTable.PLAYER_SLOTS);
        System.arraycopy(this.buildingProductionScores, 0, destination.buildingProductionScores, 0, PlayerTable.PLAYER_SLOTS);
        System.arraycopy(this.factoryCounts, 0, destination.factoryCounts, 0, PlayerTable.PLAYER_SLOTS);
        System.arraycopy(this.completedFactoryCounts, 0, destination.completedFactoryCounts, 0, PlayerTable.PLAYER_SLOTS);
        System.arraycopy(this.supplyProvided, 0, destination.supplyProvided, 0, SUPPLY_COUNTERS);
        System.arraycopy(this.supplyUsed, 0, destination.supplyUsed, 0, SUPPLY_COUNTERS);
        System.arraycopy(this.supplyCaps, 0, destination.supplyCaps, 0, SUPPLY_COUNTERS);
        this.typeCounts.copyTo(destination.typeCounts);
        this.completedTypeCounts.copyTo(destination.completedTypeCounts);

        destination.touchedCount = TriggerSnapshot.UNKNOWN_TOUCHED_COUNT;
    }

    /**
     * Checks whether a game-context DWORD is an accounting counter of this storage: the per-player ranges other than the score counters of
     * {@link PlayerScores}, and the type counts.
     *
     * @param contextOffset an aligned game-context offset
     * @return {@code true} if the DWORD is an accounting counter of this storage, {@code false} otherwise
     */
    public boolean hasContextDword(int contextOffset) {
        if (contextOffset >= TYPE_COUNTS_OFFSET && contextOffset < TYPE_COUNTS_END) {
            return true;
        }

        return contextOffset >= COUNTERS_OFFSET && contextOffset < TYPE_COUNTS_OFFSET && this.findCounterRange(contextOffset) != null;
    }

    /**
     * Reads an accounting counter DWORD by its game-context offset, as an EUD read of the game-data block does.
     *
     * @param contextOffset an aligned offset accepted by {@link #hasContextDword(int)}
     * @return the counter
     * @throws NullPointerException if the offset lies in a score range
     */
    public int readContextDword(int contextOffset) {
        if (contextOffset >= TYPE_COUNTS_OFFSET) {
            PlayerUnitCounts counts = contextOffset < COMPLETED_TYPE_COUNTS_OFFSET ? this.typeCounts : this.completedTypeCounts;
            int base = contextOffset < COMPLETED_TYPE_COUNTS_OFFSET ? TYPE_COUNTS_OFFSET : COMPLETED_TYPE_COUNTS_OFFSET;

            return counts.getByNativeIndex((contextOffset - base) / Integer.BYTES);
        }

        return this.requireCounterRange(contextOffset)[getCounterIndex(contextOffset)];
    }

    /**
     * Writes an accounting counter DWORD by its game-context offset, as an EUD write of the game-data block does.
     *
     * @param contextOffset an aligned offset accepted by {@link #hasContextDword(int)}
     * @param value         the counter
     * @throws NullPointerException if the offset lies in a score range
     */
    public void writeContextDword(int contextOffset, int value) {
        if (contextOffset >= TYPE_COUNTS_OFFSET) {
            PlayerUnitCounts counts = contextOffset < COMPLETED_TYPE_COUNTS_OFFSET ? this.typeCounts : this.completedTypeCounts;
            int base = contextOffset < COMPLETED_TYPE_COUNTS_OFFSET ? TYPE_COUNTS_OFFSET : COMPLETED_TYPE_COUNTS_OFFSET;

            counts.setByNativeIndex((contextOffset - base) / Integer.BYTES, value);

            return;
        }

        this.requireCounterRange(contextOffset)[getCounterIndex(contextOffset)] = value;
    }

    /**
     * Resets the per-game counters and the supply caps.
     * <p>
     * Outside a team game every race of every player gets the cap {@code 400}. In a team game only the leader of each force gets caps: per race
     * {@code 400} for each slot of the force with that race, at least {@code 400}; every other cap stays zero.
     *
     * @param teamStart the team game's map inputs, or {@code null} outside a team game
     */
    public void initializeForGame(StartingUnitLoader.@Nullable TeamStart teamStart) {
        Arrays.fill(this.unitCounts, 0);
        Arrays.fill(this.completedUnitCounts, 0);
        Arrays.fill(this.producedUnitCounts, 0);
        Arrays.fill(this.unitProductionScores, 0);
        Arrays.fill(this.buildingCounts, 0);
        Arrays.fill(this.completedBuildingCounts, 0);
        Arrays.fill(this.producedBuildingCounts, 0);
        Arrays.fill(this.buildingProductionScores, 0);
        Arrays.fill(this.factoryCounts, 0);
        Arrays.fill(this.completedFactoryCounts, 0);
        Arrays.fill(this.supplyProvided, 0);
        Arrays.fill(this.supplyUsed, 0);
        Arrays.fill(this.supplyCaps, 0);
        this.typeCounts.clear();
        this.completedTypeCounts.clear();

        if (teamStart != null) {
            this.initializeTeamSupplyCaps(teamStart);

            return;
        }

        for (int owner = 0; owner < PlayerTable.PLAYER_SLOTS; owner++) {
            this.supplyCaps[SUPPLY_ZERG + owner]    = SUPPLY_CAP;
            this.supplyCaps[SUPPLY_TERRAN + owner]  = SUPPLY_CAP;
            this.supplyCaps[SUPPLY_PROTOSS + owner] = SUPPLY_CAP;
        }
    }

    /**
     * Removes one produced non-building and its build score from a player's totals when a canceled Zerg building restores its Drone.
     *
     * @param owner the player index
     * @param score the build score to subtract
     */
    public void revokeUnitProduction(int owner, int score) {
        this.producedUnitCounts[owner]--;
        this.unitProductionScores[owner] -= score;
    }

    /**
     * Applies the completed-unit accounting, including the production counters and scores.
     *
     * @param unit            the completed or removed unit
     * @param units           the unit type table
     * @param change          the signed DWORD increment
     * @param countProduction whether the production counters and scores of a non-neutral owner change as well
     * @param statistics      the established sample context, required only when a produced unit is recorded
     * @throws UnsupportedOperationException if the unit type lies past {@link ScrUnitIds#NONE}, the owner lies past the player slots, or a produced unit is recorded without statistics
     */
    public void updateCompleted(@NotNull Unit unit,
                                @NotNull ScrUnitTypeView units,
                                int change,
                                boolean countProduction,
                                @Nullable ProductionStatistics statistics) {
        if ((unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
            return;
        }

        int unitType = unit.getUnitId();

        if (unitType > ScrUnitIds.NONE && !units.isAddedType(unitType)) {
            throw Exceptions.newUnsupportedOperationException("Completion accounting of unit type {0} past {1} is not modeled.",
                                                              unitType, ScrUnitIds.NONE);
        }

        if ((units.getFlags(unitType) & UnitTypeFlags.SUBUNIT) != 0) {
            return;
        }

        int owner = unit.getOwnerIndex();

        if (owner >= PlayerTable.PLAYER_SLOTS) {
            throw Exceptions.newUnsupportedOperationException("Completion accounting of owner {0} past the player slots is not modeled.", owner);
        }

        this.completedTypeCounts.add(unitType, owner, change);

        int groupFlags = units.getGroupFlags(unitType);

        if ((groupFlags & UnitGroupFlags.ZERG) != 0) {
            this.supplyProvided[SUPPLY_ZERG + owner] += units.getSupplyProvided(unitType) * change;
        } else if ((groupFlags & UnitGroupFlags.TERRAN) != 0) {
            this.supplyProvided[SUPPLY_TERRAN + owner] += units.getSupplyProvided(unitType) * change;
        } else if ((groupFlags & UnitGroupFlags.PROTOSS) != 0) {
            this.supplyProvided[SUPPLY_PROTOSS + owner] += units.getSupplyProvided(unitType) * change;
        }

        if ((groupFlags & UnitGroupFlags.FACTORY) != 0) {
            this.completedFactoryCounts[owner] += change;
        }

        if ((groupFlags & UnitGroupFlags.MEN) != 0) {
            this.completedUnitCounts[owner] += change;
        } else if ((groupFlags & UnitGroupFlags.BUILDING) != 0) {
            this.completedBuildingCounts[owner] += change;
        }

        if (countProduction && owner != PlayerTable.NEUTRAL_PLAYER_ID) {
            if ((groupFlags & UnitGroupFlags.MEN) != 0) {
                int baseType = units.getBaseType(unitType);
                boolean excludedFromCount = baseType == ScrUnitIds.ZERG_GUARDIAN ||
                                            baseType == ScrUnitIds.ZERG_DEVOURER ||
                                            baseType == ScrUnitIds.PROTOSS_DARK_ARCHON ||
                                            baseType == ScrUnitIds.PROTOSS_ARCHON ||
                                            baseType == ScrUnitIds.ZERG_LURKER;

                if (!excludedFromCount) {
                    this.producedUnitCounts[owner] += change;
                }

                this.unitProductionScores[owner] += units.getBuildScore(unitType) * change;

                if (!excludedFromCount && change > 0) {
                    if (statistics == null) {
                        throw Exceptions.newUnsupportedOperationException("Completed-unit accounting requires the production statistics.");
                    }

                    statistics.record(owner, ProductionStatistics.PRODUCTION_CATEGORY, change);
                }
            } else if ((groupFlags & UnitGroupFlags.BUILDING) != 0) {
                if (unitType != ScrUnitIds.ZERG_LAIR &&
                    unitType != ScrUnitIds.ZERG_HIVE &&
                    unitType != ScrUnitIds.ZERG_GREATER_SPIRE &&
                    unitType != ScrUnitIds.ZERG_SPORE_COLONY &&
                    unitType != ScrUnitIds.ZERG_SUNKEN_COLONY) {
                    this.producedBuildingCounts[owner] += change;
                }

                this.buildingProductionScores[owner] += units.getBuildScore(unitType) * change;
            }
        }

        if (this.completedTypeCounts.get(unitType, owner) < 0) {
            this.completedTypeCounts.set(unitType, owner, 0);
        }
    }

    /**
     * Applies creation or removal accounting while preserving DWORD overflow and BYTE doubling.
     *
     * @param unit                       the created or removed unit
     * @param units                      the unit type table
     * @param change                     the signed DWORD increment
     * @param preserveNegativeTypeCounts whether a negative type count is kept instead of being reset to zero
     * @throws UnsupportedOperationException if the unit type or its queued unit type lies past {@link ScrUnitIds#NONE} or the owner lies past the player slots
     */
    public void update(@NotNull Unit unit, @NotNull ScrUnitTypeView units, int change, boolean preserveNegativeTypeCounts) {
        int status = unit.getStatus();

        if ((status & UnitStatusFlags.HALLUCINATION) != 0) {
            return;
        }

        int unitType = unit.getUnitId();

        if (unitType > ScrUnitIds.NONE && !units.isAddedType(unitType)) {
            throw Exceptions.newUnsupportedOperationException("Accounting of unit type {0} past {1} is not modeled.",
                                                              unitType, ScrUnitIds.NONE);
        }

        int specialFlags = units.getFlags(unitType);

        if ((specialFlags & UnitTypeFlags.SUBUNIT) != 0) {
            return;
        }

        int owner = unit.getOwnerIndex();

        if (owner >= PlayerTable.PLAYER_SLOTS) {
            throw Exceptions.newUnsupportedOperationException("Accounting of owner {0} past the player slots is not modeled.", owner);
        }

        this.typeCounts.add(unitType, owner, change);

        int groupFlags = units.getGroupFlags(unitType);
        boolean queuedType = units.isEgg(unitType);

        if ((groupFlags & UnitGroupFlags.ZERG) != 0) {
            int supplyType = queuedType ? UnitQueries.getQueuedType(unit) : unitType;

            if (supplyType > ScrUnitIds.NONE && !units.isAddedType(supplyType)) {
                throw Exceptions.newUnsupportedOperationException("Accounting of queued unit type {0} past {1} is not modeled.",
                                                                  supplyType, ScrUnitIds.NONE);
            }

            // An empty egg (queued type 228) reads the units.dat memory past the supply column.
            int supply = units.getSupplyRequired(supplyType);
            boolean doubled = (units.getFlags(supplyType) & UnitTypeFlags.TWO_UNITS_IN_ONE_EGG) != 0 &&
                              (queuedType || (status & UnitStatusFlags.COMPLETED) == 0);

            if (doubled) {
                supply = (supply * TWIN_EGG_UNITS) & BYTE_MASK;
            }

            this.supplyUsed[SUPPLY_ZERG + owner] += supply * change;
        } else if ((groupFlags & UnitGroupFlags.TERRAN) != 0) {
            this.supplyUsed[SUPPLY_TERRAN + owner] += units.getSupplyRequired(unitType) * change;
        } else if ((groupFlags & UnitGroupFlags.PROTOSS) != 0) {
            this.supplyUsed[SUPPLY_PROTOSS + owner] += units.getSupplyRequired(unitType) * change;
        }

        if ((groupFlags & UnitGroupFlags.FACTORY) != 0) {
            this.factoryCounts[owner] += change;
        }

        if ((groupFlags & UnitGroupFlags.MEN) != 0) {
            this.unitCounts[owner] += change;
        } else if ((groupFlags & UnitGroupFlags.BUILDING) != 0) {
            this.buildingCounts[owner] += change;
        } else if (queuedType) {
            this.unitCounts[owner] += change;
        }

        if (this.typeCounts.get(unitType, owner) < 0 && !preserveNegativeTypeCounts) {
            this.typeCounts.set(unitType, owner, 0);
        }
    }

    /**
     * Sets the team supply caps: per force the leader's cap of each race is {@code 400} times the force's slots of that race, but at least
     * {@code 400}.
     *
     * @param teamStart the team game's map inputs
     * @throws UnsupportedOperationException if a force leader lies past the player slots
     */
    private void initializeTeamSupplyCaps(StartingUnitLoader.@NotNull TeamStart teamStart) {
        for (int force = PlayerTable.FIRST_FORCE; force <= PlayerTable.FORCE_COUNT; force++) {
            int leader = teamStart.getForceOwner(force) & BYTE_MASK;

            if (leader == NO_LEADER) {
                continue;
            }

            if (leader >= PlayerTable.PLAYER_SLOTS) {
                throw Exceptions.newUnsupportedOperationException("Team supply caps of leader {0} past the player slots are not modeled.", leader);
            }

            this.supplyCaps[SUPPLY_ZERG + leader]    = Math.max(teamStart.countForceRace(force, ScrRaceIds.ZERG) * SUPPLY_CAP, SUPPLY_CAP);
            this.supplyCaps[SUPPLY_TERRAN + leader]  = Math.max(teamStart.countForceRace(force, ScrRaceIds.TERRAN) * SUPPLY_CAP, SUPPLY_CAP);
            this.supplyCaps[SUPPLY_PROTOSS + leader] = Math.max(teamStart.countForceRace(force, ScrRaceIds.PROTOSS) * SUPPLY_CAP, SUPPLY_CAP);
        }
    }

    /**
     * Finds the counter array containing a per-player offset.
     *
     * @param contextOffset the context offset
     * @return the retained range, or {@code null} for a score range
     */
    private int @Nullable [] findCounterRange(int contextOffset) {
        return switch (COUNTERS_OFFSET + (contextOffset - COUNTERS_OFFSET) / RANGE_SIZE * RANGE_SIZE) {
            case UNIT_COUNTS_OFFSET -> this.unitCounts;
            case COMPLETED_UNIT_COUNTS_OFFSET -> this.completedUnitCounts;
            case PRODUCED_UNIT_COUNTS_OFFSET -> this.producedUnitCounts;
            case UNIT_PRODUCTION_SCORES_OFFSET -> this.unitProductionScores;
            case BUILDING_COUNTS_OFFSET -> this.buildingCounts;
            case COMPLETED_BUILDING_COUNTS_OFFSET -> this.completedBuildingCounts;
            case PRODUCED_BUILDING_COUNTS_OFFSET -> this.producedBuildingCounts;
            case BUILDING_PRODUCTION_SCORES_OFFSET -> this.buildingProductionScores;
            case FACTORY_COUNTS_OFFSET -> this.factoryCounts;
            case COMPLETED_FACTORY_COUNTS_OFFSET -> this.completedFactoryCounts;
            case SUPPLY_PROVIDED_OFFSET,
                 SUPPLY_PROVIDED_OFFSET + TERRAN_SUPPLY_RACE * SUPPLY_RACE_SIZE,
                 SUPPLY_PROVIDED_OFFSET + PROTOSS_SUPPLY_RACE * SUPPLY_RACE_SIZE -> this.supplyProvided;
            case SUPPLY_USED_OFFSET,
                 SUPPLY_USED_OFFSET + TERRAN_SUPPLY_RACE * SUPPLY_RACE_SIZE,
                 SUPPLY_USED_OFFSET + PROTOSS_SUPPLY_RACE * SUPPLY_RACE_SIZE -> this.supplyUsed;
            case SUPPLY_CAPS_OFFSET,
                 SUPPLY_CAPS_OFFSET + TERRAN_SUPPLY_RACE * SUPPLY_RACE_SIZE,
                 SUPPLY_CAPS_OFFSET + PROTOSS_SUPPLY_RACE * SUPPLY_RACE_SIZE -> this.supplyCaps;
            default -> null;
        };
    }

    /**
     * Returns the counter array containing a per-player offset outside the score ranges.
     *
     * @param contextOffset the context offset
     * @return the retained range
     * @throws NullPointerException if the offset lies in a score range
     */
    private int @NotNull [] requireCounterRange(int contextOffset) {
        int[] range = this.findCounterRange(contextOffset);

        if (range == null) {
            throw Exceptions.newNullPointerException("Context offset {0} is not an accounting counter.", String.format("0x%X", contextOffset));
        }

        return range;
    }
}
