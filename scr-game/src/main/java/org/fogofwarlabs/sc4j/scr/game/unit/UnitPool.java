package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.entity.column.Column;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnKey;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnTable;
import org.fogofwarlabs.sc4j.engine.entity.column.IntColumn;
import org.fogofwarlabs.sc4j.engine.entity.pool.EntityPool;
import org.fogofwarlabs.sc4j.engine.entity.pool.SlotList;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitBuildingFlight;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitHangar;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitNuclear;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.production.LarvaSpawner;
import org.fogofwarlabs.sc4j.scr.game.production.UnitAddons;
import org.fogofwarlabs.sc4j.scr.game.production.UnitResearch;
import org.fogofwarlabs.sc4j.scr.game.production.UnitTerranConstruction;
import org.fogofwarlabs.sc4j.scr.game.resource.UnitHarvesting;
import org.fogofwarlabs.sc4j.scr.game.state.GameLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Tracks bounded unit storage, successful allocation and finalized recycling.
 * <p>
 * Callers provide capacity and coordinate storage initialization, sprite and selection effects, death timing and final
 * recycling. Pool storage is independent of {@link GameLoader}'s provisional unit list.
 * <p>
 * The slots, their allocation flags, generations and the free list are an engine {@link EntityPool} with the native slot
 * policy ({@link UnitSlotPolicy}); this pool adds the native owner, creation, dead and map-revealer lists and the creation
 * metadata, and keeps the native checks of every operation. The scalar unit fields of the native union are columns of the
 * entity pool, registered by their SC:R systems ({@link LarvaSpawner#COLUMNS}, {@link UnitHarvesting#RESOURCE_COLUMNS},
 * {@link UnitResearch#RESEARCH_COLUMNS}, {@link UnitAddons#ADDON_COLUMNS}, {@link UnitBuildingFlight#LANDING_COLUMNS},
 * {@link UnitHarvesting#WORKER_COLUMNS}, {@link UnitTerranConstruction#REPAIR_COLUMNS}, {@link UnitHangar#HANGAR_COLUMNS}
 * and {@link UnitNuclear#SILO_COLUMNS}); {@link Unit} reads and writes them through its accessors.
 */
public final class UnitPool {
    /**
     * The unit capacity of the classic resource limits; a larger capacity is the extended unit limit, whose unit handles use the wider index field.
     */
    public static final int CLASSIC_UNIT_CAPACITY = 1700;

    /**
     * The number of live creation lists: the active list and the hidden list.
     */
    static final int CREATED_LIST_COUNT = 2;

    /**
     * The mask of the WORD creation metadata.
     */
    private static final int CREATION_TAG_MASK = 0xFFFF;
    /**
     * The mask of the generation BYTE.
     */
    private static final int GENERATION_BYTE_MASK = 0xFF;

    /**
     * The largest supported capacity, which keeps the pool indices inside the thirteen-bit handle index field.
     */
    private static final int MAXIMUM_CAPACITY = 8191;
    /**
     * The unit pool columns of the native union, in registration order.
     */
    private static final @NotNull List<@NotNull ColumnKey<?>> UNION_COLUMNS = Stream.of(
            LarvaSpawner.COLUMNS,
            UnitHarvesting.RESOURCE_COLUMNS,
            UnitResearch.RESEARCH_COLUMNS, UnitAddons.ADDON_COLUMNS, UnitBuildingFlight.LANDING_COLUMNS,
            UnitHarvesting.WORKER_COLUMNS, UnitTerranConstruction.REPAIR_COLUMNS,
            UnitHangar.HANGAR_COLUMNS,
            UnitNuclear.SILO_COLUMNS
    ).flatMap(List::stream).toList();

    /**
     * The positions of the unit list heads and tails in the saved order.
     */
    private static final int SAVED_FIRST_ACTIVE = 0;
    private static final int SAVED_FIRST_HIDDEN = 1;
    private static final int SAVED_FIRST_DEAD = 2;
    private static final int SAVED_FIRST_REVEALER = 3;
    private static final int SAVED_LAST_ACTIVE = 4;
    private static final int SAVED_LAST_HIDDEN = 5;
    private static final int SAVED_LAST_DEAD = 6;
    private static final int SAVED_LAST_REVEALER = 7;

    /**
     * The unit-list heads that EUD writes can replace.
     */
    public enum ListHead {
        /**
         * The first hidden unit.
         */
        FIRST_HIDDEN,
        /**
         * The last dying unit.
         */
        LAST_DEAD,
        /**
         * The first active unit.
         */
        FIRST_ACTIVE,
        /**
         * The first map revealer.
         */
        FIRST_REVEALER,
        /**
         * The first free slot.
         */
        FIRST_FREE,
        /**
         * The last free slot.
         */
        LAST_FREE
    }

    private final int capacity;
    /**
     * The players of the game by slot index, the engine owners of the native owner BYTEs ({@link #getPlayer(int)}); empty until the game
     * attaches.
     */
    private @NotNull Player @NotNull [] players;
    /**
     * The slots with their allocation flags, generations and the doubly linked free list.
     */
    private final @NotNull EntityPool<Unit> slots;
    private final @NotNull SlotList freeSlots;
    private final int @NotNull [] creationTags;
    /**
     * The unit outside the slots that stands in for a unit of a type that does not exist yet ({@link #getStandInUnit()}).
     */
    private final @NotNull Unit standInUnit;

    private final @NotNull IntColumn larvaSpawnCountdowns;
    private final @NotNull IntColumn secondLarvaSideCounts;
    private final @NotNull IntColumn thirdLarvaSideCounts;
    private final @NotNull IntColumn fourthLarvaSideCounts;
    private final @NotNull IntColumn creepTimers;

    private final @NotNull IntColumn resourceAmounts;
    private final @NotNull IntColumn resourceAreaIndices;
    private final @NotNull IntColumn resourceClaimBytes;
    private final @NotNull IntColumn resourceBeingGatheredBytes;

    private final @NotNull IntColumn researchTypes;
    private final @NotNull IntColumn upgradeTypes;
    private final @NotNull IntColumn upgradeResearchTimes;
    private final @NotNull IntColumn upgradeLevels;
    private final @NotNull IntColumn addonBuildTypes;
    private final @NotNull IntColumn landingBytes;

    private final @NotNull IntColumn targetResourceXs;
    private final @NotNull IntColumn targetResourceYs;
    private final @NotNull IntColumn gatheringResourceBytes;
    private final @NotNull IntColumn carriedResourceAmounts;
    private final @NotNull IntColumn repairTimers;

    private final @NotNull IntColumn spiderMineCounts;
    private final @NotNull IntColumn insideHangarCounts;
    private final @NotNull IntColumn outsideHangarCounts;
    private final @NotNull IntColumn hangarOutsideBytes;
    private final @NotNull IntColumn hangarSpareBytes;

    private final @NotNull IntColumn siloReadyDwords;

    private final int @NotNull [] firstOwnerIndices;
    private final int @NotNull [] previousOwnerIndices;
    private final int @NotNull [] nextOwnerIndices;

    private final int @NotNull [] nextCreatedIndices;
    private final int @NotNull [] previousCreatedIndices;
    private int firstGroundedIndex;
    private int lastGroundedIndex;
    private int firstHiddenIndex;
    private int lastHiddenIndex;
    private int firstDeadIndex;
    private int lastDeadIndex;

    private final boolean @NotNull [] revealers;
    private int firstRevealerIndex;
    private int lastRevealerIndex;

    /**
     * Creates a {@link UnitPool} instance with zeroed generations.
     * <p>
     * The initial free traversal is {@code 1, capacity, capacity - 1, ..., 2}; slot {@code 2} is the tail when it exists.
     * The supported capacity keeps indices within the thirteen-bit handle field.
     *
     * @param capacity the explicitly established runtime capacity
     * @throws IllegalArgumentException if capacity is outside the supported {@code 0..8191} range
     */
    public UnitPool(int capacity) {
        if (capacity < 0 || capacity > MAXIMUM_CAPACITY) {
            throw Exceptions.newIllegalArgumentException("Unsupported native unit pool capacity: {0}", capacity);
        }

        this.capacity     = capacity;
        this.players      = new Player[0];
        this.slots        = new EntityPool<>(capacity, index -> new Unit(this, index), UnitSlotPolicy.INSTANCE, UNION_COLUMNS);
        this.freeSlots    = this.slots.getFreeSlots();
        this.creationTags = new int[capacity + 1];
        this.standInUnit  = new Unit(this, capacity + 1);

        this.larvaSpawnCountdowns  = this.slots.getColumn(LarvaSpawner.SPAWN_COUNTDOWN_COLUMN);
        this.secondLarvaSideCounts = this.slots.getColumn(LarvaSpawner.SECOND_SIDE_COUNT_COLUMN);
        this.thirdLarvaSideCounts  = this.slots.getColumn(LarvaSpawner.THIRD_SIDE_COUNT_COLUMN);
        this.fourthLarvaSideCounts = this.slots.getColumn(LarvaSpawner.FOURTH_SIDE_COUNT_COLUMN);
        this.creepTimers           = this.slots.getColumn(LarvaSpawner.CREEP_TIMER_COLUMN);

        this.resourceAmounts            = this.slots.getColumn(UnitHarvesting.RESOURCE_AMOUNT_COLUMN);
        this.resourceAreaIndices        = this.slots.getColumn(UnitHarvesting.RESOURCE_AREA_COLUMN);
        this.resourceClaimBytes         = this.slots.getColumn(UnitHarvesting.RESOURCE_CLAIM_COLUMN);
        this.resourceBeingGatheredBytes = this.slots.getColumn(UnitHarvesting.RESOURCE_BEING_GATHERED_COLUMN);

        this.researchTypes        = this.slots.getColumn(UnitResearch.RESEARCH_TYPE_COLUMN);
        this.upgradeTypes         = this.slots.getColumn(UnitResearch.UPGRADE_TYPE_COLUMN);
        this.upgradeResearchTimes = this.slots.getColumn(UnitResearch.RESEARCH_TIME_COLUMN);
        this.upgradeLevels        = this.slots.getColumn(UnitResearch.UPGRADE_LEVEL_COLUMN);
        this.addonBuildTypes      = this.slots.getColumn(UnitAddons.ADDON_BUILD_TYPE_COLUMN);
        this.landingBytes         = this.slots.getColumn(UnitBuildingFlight.LANDING_COLUMN);

        this.targetResourceXs       = this.slots.getColumn(UnitHarvesting.TARGET_RESOURCE_X_COLUMN);
        this.targetResourceYs       = this.slots.getColumn(UnitHarvesting.TARGET_RESOURCE_Y_COLUMN);
        this.gatheringResourceBytes = this.slots.getColumn(UnitHarvesting.GATHERING_COLUMN);
        this.carriedResourceAmounts = this.slots.getColumn(UnitHarvesting.CARRIED_AMOUNT_COLUMN);
        this.repairTimers           = this.slots.getColumn(UnitTerranConstruction.REPAIR_TIMER_COLUMN);

        this.spiderMineCounts    = this.slots.getColumn(UnitHangar.SPIDER_MINE_COUNT_COLUMN);
        this.insideHangarCounts  = this.slots.getColumn(UnitHangar.INSIDE_COUNT_COLUMN);
        this.outsideHangarCounts = this.slots.getColumn(UnitHangar.OUTSIDE_COUNT_COLUMN);
        this.hangarOutsideBytes  = this.slots.getColumn(UnitHangar.OUTSIDE_COLUMN);
        this.hangarSpareBytes    = this.slots.getColumn(UnitHangar.SPARE_BYTE_COLUMN);

        this.siloReadyDwords = this.slots.getColumn(UnitNuclear.SILO_READY_COLUMN);

        this.firstOwnerIndices    = new int[PlayerTable.PLAYER_SLOTS];
        this.previousOwnerIndices = new int[capacity + 1];
        this.nextOwnerIndices     = new int[capacity + 1];

        this.nextCreatedIndices     = new int[capacity + 1];
        this.previousCreatedIndices = new int[capacity + 1];
        this.firstGroundedIndex     = 0;
        this.lastGroundedIndex      = 0;
        this.firstHiddenIndex       = 0;
        this.lastHiddenIndex        = 0;
        this.firstDeadIndex         = 0;
        this.lastDeadIndex          = 0;

        this.revealers          = new boolean[capacity + 1];
        this.firstRevealerIndex = 0;
        this.lastRevealerIndex  = 0;
    }

    /**
     * @return the configured pool capacity
     */
    public int getCapacity() {
        return this.capacity;
    }

    /**
     * Returns the engine owner of a native owner BYTE: the game's player of that slot index.
     *
     * @param ownerIndex the native owner BYTE
     * @return the player, or {@code null} if the game has no player of the index or the pool has no players yet
     */
    public @Nullable Player getPlayer(int ownerIndex) {
        return ownerIndex >= 0 && ownerIndex < this.players.length ? this.players[ownerIndex] : null;
    }

    /**
     * Sets the players of the game and the engine owner of every slot from its native owner BYTE.
     *
     * @param players the players of the game by slot index, kept
     */
    public void setPlayers(@NotNull Player @NotNull [] players) {
        this.players = players;

        for (int poolIndex = 1; poolIndex <= this.capacity; poolIndex++) {
            Unit unit = this.slots.get(poolIndex);

            unit.setOwnerIndex(unit.getOwnerIndex());
        }
    }

    /**
     * @return the number of slots not yet returned by finalized recycling
     */
    public int getAllocatedCount() {
        return this.slots.getAllocatedCount();
    }

    /**
     * @return the next one-based allocation index, or {@code 0} when the pool is exhausted
     */
    public int getFirstFreeIndex() {
        return this.freeSlots.getFirst();
    }

    /**
     * Returns the unit of this pool outside its slots, with the index after the last slot: the pool never allocates, lists or indexes it, and it
     * has no sprite. A query that needs a unit of a type that does not exist yet gives it that type ({@link UnitInitializer#prepareStandIn}).
     *
     * @return the stand-in unit, the same instance for the pool's lifetime
     */
    public @NotNull Unit getStandInUnit() {
        return this.standInUnit;
    }

    /**
     * Returns persistent native storage, including free slots with retained fields.
     *
     * @param poolIndex the one-based pool index
     * @return the retained unit storage
     * @throws IndexOutOfBoundsException if the index does not identify a pool slot
     */
    public @NotNull Unit getUnit(int poolIndex) {
        this.checkPoolIndex(poolIndex);

        return this.slots.get(poolIndex);
    }

    /**
     * @return the last free slot, or {@code 0} when the pool is exhausted
     */
    public int getLastFreeIndex() {
        return this.freeSlots.getLast();
    }

    /**
     * @param poolIndex the one-based pool index
     * @return the next free slot, or {@code 0} at the end
     * @throws IndexOutOfBoundsException if the index does not identify a pool slot
     */
    public int getNextFreeIndex(int poolIndex) {
        this.checkPoolIndex(poolIndex);

        return this.freeSlots.getNext(poolIndex);
    }

    /**
     * @param poolIndex the one-based pool index of a free slot
     * @return the previous free slot, or {@code 0} at the head
     * @throws IndexOutOfBoundsException if the index does not identify a pool slot
     */
    public int getPreviousFreeIndex(int poolIndex) {
        this.checkPoolIndex(poolIndex);

        return this.freeSlots.getPrevious(poolIndex);
    }

    /**
     * @param owner the native owner index, any value
     * @return the first owner-list index, or {@code 0} when empty or for an owner outside {@code 0..11}
     */
    public int getFirstOwnerIndex(int owner) {
        return Integer.compareUnsigned(owner, PlayerTable.PLAYER_SLOTS) < 0 ? this.firstOwnerIndices[owner] : 0;
    }

    /**
     * Sets one owner-list head as an EUD write to its host pointer does: the list links are not changed.
     *
     * @param owner     the native owner index in {@code 0..11}
     * @param poolIndex the new head, or {@code 0} for a null pointer
     * @throws ArrayIndexOutOfBoundsException if the owner is outside the owner-head storage
     */
    public void setFirstOwnerIndexRaw(int owner, int poolIndex) {
        this.firstOwnerIndices[owner] = poolIndex;
    }

    /**
     * @param poolIndex the one-based pool index
     * @return the previous owner-list index
     * @throws ArrayIndexOutOfBoundsException if the pool index is outside the owned previous-owner storage
     */
    public int getPreviousOwnerIndex(int poolIndex) {
        return this.previousOwnerIndices[poolIndex];
    }

    /**
     * @param poolIndex the one-based pool index
     * @return the next owner-list index
     * @throws ArrayIndexOutOfBoundsException if the pool index is outside the owned next-owner storage
     */
    public int getNextOwnerIndex(int poolIndex) {
        return this.nextOwnerIndices[poolIndex];
    }

    /**
     * @param grounded whether to select the grounded-building status list rather than the hidden list
     * @return the first index in the selected native creation list, or {@code 0}
     */
    public int getFirstCreatedIndex(boolean grounded) {
        return grounded ? this.firstGroundedIndex : this.firstHiddenIndex;
    }

    /**
     * @param grounded whether to select the active list initially populated by grounded units
     * @return the retained tail of the selected creation list, or {@code 0}
     */
    public int getLastCreatedIndex(boolean grounded) {
        return grounded ? this.lastGroundedIndex : this.lastHiddenIndex;
    }

    /**
     * @param poolIndex the one-based pool index
     * @return the next index in the native creation list
     * @throws ArrayIndexOutOfBoundsException if the pool index is outside the owned next-created storage
     */
    public int getNextCreatedIndex(int poolIndex) {
        return this.nextCreatedIndices[poolIndex];
    }

    /**
     * @param poolIndex the one-based pool index
     * @return the previous index in the native creation list
     * @throws ArrayIndexOutOfBoundsException if the pool index is outside the owned previous-created storage
     */
    public int getPreviousCreatedIndex(int poolIndex) {
        return this.previousCreatedIndices[poolIndex];
    }

    /**
     * Sets a list head without relinking the list, as an EUD write of the unit-list heads does.
     *
     * @param head      the list head
     * @param poolIndex the new head, {@code 0} for none
     */
    public void setListHeadRaw(@NotNull ListHead head, int poolIndex) {
        switch (head) {
            case FIRST_HIDDEN -> this.firstHiddenIndex = poolIndex;
            case LAST_DEAD -> this.lastDeadIndex = poolIndex;
            case FIRST_ACTIVE -> this.firstGroundedIndex = poolIndex;
            case FIRST_REVEALER -> this.firstRevealerIndex = poolIndex;
            case FIRST_FREE -> this.freeSlots.setFirst(poolIndex);
            case LAST_FREE -> this.freeSlots.setLast(poolIndex);
        }
    }

    /**
     * Sets a unit's previous creation-list link without relinking its neighbors, as an EUD write of the native unit pointers does.
     *
     * @param poolIndex the one-based pool index
     * @param value     the new previous creation-list index, {@code 0} for none
     * @throws ArrayIndexOutOfBoundsException if the pool index is outside the owned previous-created storage
     */
    public void setPreviousCreatedIndexRaw(int poolIndex, int value) {
        this.previousCreatedIndices[poolIndex] = value;
    }

    /**
     * Sets a unit's next creation-list link without relinking its neighbors.
     *
     * @param poolIndex the one-based pool index
     * @param value     the new next creation-list index, {@code 0} for none
     * @throws ArrayIndexOutOfBoundsException if the pool index is outside the owned next-created storage
     * @see #setPreviousCreatedIndexRaw(int, int)
     */
    public void setNextCreatedIndexRaw(int poolIndex, int value) {
        this.nextCreatedIndices[poolIndex] = value;
    }

    /**
     * Sets a free slot's previous link without relinking its neighbors.
     *
     * @param poolIndex the one-based pool index of a free slot
     * @param value     the new previous free slot, {@code 0} for none
     * @throws ArrayIndexOutOfBoundsException if the pool index is outside the owned previous-free storage
     * @see #setPreviousCreatedIndexRaw(int, int)
     */
    public void setPreviousFreeIndexRaw(int poolIndex, int value) {
        this.freeSlots.setPrevious(poolIndex, value);
    }

    /**
     * Sets a free slot's next link without relinking its neighbors.
     *
     * @param poolIndex the one-based pool index of a free slot
     * @param value     the new next free slot, {@code 0} for none
     * @throws ArrayIndexOutOfBoundsException if the pool index is outside the owned next-free storage
     * @see #setPreviousCreatedIndexRaw(int, int)
     */
    public void setNextFreeIndexRaw(int poolIndex, int value) {
        this.freeSlots.setNext(poolIndex, value);
    }

    /**
     * Sets a unit's previous owner-list link without relinking its neighbors.
     *
     * @param poolIndex the one-based pool index
     * @param value     the new previous owner-list index, {@code 0} for none
     * @throws ArrayIndexOutOfBoundsException if the pool index is outside the owned previous-owner storage
     * @see #setPreviousCreatedIndexRaw(int, int)
     */
    public void setPreviousOwnerIndexRaw(int poolIndex, int value) {
        this.previousOwnerIndices[poolIndex] = value;
    }

    /**
     * Sets a unit's next owner-list link without relinking its neighbors.
     *
     * @param poolIndex the one-based pool index
     * @param value     the new next owner-list index, {@code 0} for none
     * @throws ArrayIndexOutOfBoundsException if the pool index is outside the owned next-owner storage
     * @see #setPreviousCreatedIndexRaw(int, int)
     */
    public void setNextOwnerIndexRaw(int poolIndex, int value) {
        this.nextOwnerIndices[poolIndex] = value;
    }

    /**
     * @return the Larva spawn countdown BYTE of every slot ({@link Unit#getLarvaSpawnCountdown()})
     */
    @NotNull IntColumn getLarvaSpawnCountdowns() {
        return this.larvaSpawnCountdowns;
    }

    /**
     * @return the upper Larva side count WORD of every slot ({@link Unit#getSecondLarvaSideCount()})
     */
    @NotNull IntColumn getSecondLarvaSideCounts() {
        return this.secondLarvaSideCounts;
    }

    /**
     * @return the right Larva side count WORD of every slot ({@link Unit#getThirdLarvaSideCount()})
     */
    @NotNull IntColumn getThirdLarvaSideCounts() {
        return this.thirdLarvaSideCounts;
    }

    /**
     * @return the lower Larva side count WORD of every slot ({@link Unit#getFourthLarvaSideCount()})
     */
    @NotNull IntColumn getFourthLarvaSideCounts() {
        return this.fourthLarvaSideCounts;
    }

    /**
     * @return the creep timer BYTE of every slot ({@link Unit#getCreepTimer()})
     */
    @NotNull IntColumn getCreepTimers() {
        return this.creepTimers;
    }

    /**
     * @return the resource amount WORD of every slot ({@link Unit#getResources()})
     */
    @NotNull IntColumn getResourceAmounts() {
        return this.resourceAmounts;
    }

    /**
     * @return the resource area BYTE of every slot ({@link Unit#getResourceAreaIndex()})
     */
    @NotNull IntColumn getResourceAreaIndices() {
        return this.resourceAreaIndices;
    }

    /**
     * @return the computer claim BYTE of every slot ({@link Unit#getResourceClaimByte()})
     */
    @NotNull IntColumn getResourceClaimBytes() {
        return this.resourceClaimBytes;
    }

    /**
     * @return the being-gathered BYTE of every slot ({@link Unit#getResourceBeingGatheredByte()})
     */
    @NotNull IntColumn getResourceBeingGatheredBytes() {
        return this.resourceBeingGatheredBytes;
    }

    /**
     * @return the research type BYTE of every slot ({@link Unit#getResearchType()})
     */
    @NotNull IntColumn getResearchTypes() {
        return this.researchTypes;
    }

    /**
     * @return the upgrade type BYTE of every slot ({@link Unit#getUpgradeType()})
     */
    @NotNull IntColumn getUpgradeTypes() {
        return this.upgradeTypes;
    }

    /**
     * @return the research or upgrade time WORD of every slot ({@link Unit#getUpgradeResearchTime()})
     */
    @NotNull IntColumn getUpgradeResearchTimes() {
        return this.upgradeResearchTimes;
    }

    /**
     * @return the upgrade level BYTE of every slot ({@link Unit#getUpgradeLevel()})
     */
    @NotNull IntColumn getUpgradeLevels() {
        return this.upgradeLevels;
    }

    /**
     * @return the add-on build type of every slot ({@link Unit#getAddonBuildType()})
     */
    @NotNull IntColumn getAddonBuildTypes() {
        return this.addonBuildTypes;
    }

    /**
     * @return the landing BYTE of every slot ({@link Unit#getLandingByte()})
     */
    @NotNull IntColumn getLandingBytes() {
        return this.landingBytes;
    }

    /**
     * @return the horizontal target resource position of every slot ({@link Unit#getTargetResourceX()})
     */
    @NotNull IntColumn getTargetResourceXs() {
        return this.targetResourceXs;
    }

    /**
     * @return the vertical target resource position of every slot ({@link Unit#getTargetResourceY()})
     */
    @NotNull IntColumn getTargetResourceYs() {
        return this.targetResourceYs;
    }

    /**
     * @return the gathering BYTE of every slot ({@link Unit#getGatheringResourceByte()})
     */
    @NotNull IntColumn getGatheringResourceBytes() {
        return this.gatheringResourceBytes;
    }

    /**
     * @return the carried resource amount of every slot ({@link Unit#getCarriedResourceAmount()})
     */
    @NotNull IntColumn getCarriedResourceAmounts() {
        return this.carriedResourceAmounts;
    }

    /**
     * @return the repair timer WORD of every slot ({@link Unit#getRepairTimer()})
     */
    @NotNull IntColumn getRepairTimers() {
        return this.repairTimers;
    }

    /**
     * @return the Spider Mine count BYTE of every slot ({@link Unit#getSpiderMineCount()})
     */
    @NotNull IntColumn getSpiderMineCounts() {
        return this.spiderMineCounts;
    }

    /**
     * @return the inside hangar count BYTE of every slot ({@link Unit#getHangarCount(boolean)})
     */
    @NotNull IntColumn getInsideHangarCounts() {
        return this.insideHangarCounts;
    }

    /**
     * @return the outside hangar count BYTE of every slot ({@link Unit#getHangarCount(boolean)})
     */
    @NotNull IntColumn getOutsideHangarCounts() {
        return this.outsideHangarCounts;
    }

    /**
     * @return the outside BYTE of every slot ({@link Unit#getHangarOutsideByte()})
     */
    @NotNull IntColumn getHangarOutsideBytes() {
        return this.hangarOutsideBytes;
    }

    /**
     * @return the stored hangar BYTE of every slot ({@link Unit#getHangarSpareByte()})
     */
    @NotNull IntColumn getHangarSpareBytes() {
        return this.hangarSpareBytes;
    }

    /**
     * @return the Nuclear Silo ready DWORD of every slot ({@link Unit#getSiloReadyDword()})
     */
    @NotNull IntColumn getSiloReadyDwords() {
        return this.siloReadyDwords;
    }

    /**
     * @param poolIndex the one-based pool index
     * @return the retained WORD metadata the constructor recorded
     * @throws ArrayIndexOutOfBoundsException if the pool index is outside the owned creation-tag storage
     */
    public int getCreationTag(int poolIndex) {
        return this.creationTags[poolIndex];
    }

    /**
     * Sets the constructor's WORD metadata after its free-list removal.
     *
     * @param poolIndex the allocated one-based pool index
     * @param tag       the supplied metadata; only the low WORD is stored
     * @throws IllegalStateException     if the slot is not allocated
     * @throws IndexOutOfBoundsException if the pool index does not identify a pool slot
     */
    public void setCreationTag(int poolIndex, int tag) {
        if (!this.isAllocated(poolIndex)) {
            throw Exceptions.newIllegalStateException("Creation metadata requires allocated storage.");
        }

        this.creationTags[poolIndex] = tag & CREATION_TAG_MASK;
    }

    /**
     * Returns a slot's generation, including its retained value after recycling.
     *
     * @param poolIndex the one-based pool index
     * @return the retained unsigned generation BYTE
     * @throws IndexOutOfBoundsException if the index does not identify a pool slot
     */
    public int getGeneration(int poolIndex) {
        this.checkPoolIndex(poolIndex);

        return this.slots.getGeneration(poolIndex);
    }

    /**
     * Sets a slot's generation BYTE, as an EUD write of the classic generation byte does: the handles of the slot then resolve against the new value.
     *
     * @param poolIndex the one-based pool index
     * @param value     the BYTE in the low eight bits
     * @throws IndexOutOfBoundsException if the index does not identify a pool slot
     */
    public void setGeneration(int poolIndex, int value) {
        this.checkPoolIndex(poolIndex);

        this.slots.setGeneration(poolIndex, value & GENERATION_BYTE_MASK);
    }

    /**
     * Returns whether successful allocation has not yet been finalized for recycling.
     *
     * @param poolIndex the one-based pool index
     * @return {@code true} if the slot remains allocated, {@code false} otherwise
     * @throws IndexOutOfBoundsException if the index does not identify a pool slot
     */
    public boolean isAllocated(int poolIndex) {
        this.checkPoolIndex(poolIndex);

        return this.slots.isAllocated(poolIndex);
    }

    /**
     * Encodes a pool slot without asserting that the native handle lookup would accept it.
     * <p>
     * Encoding does not check liveness. Freed slots retain their generation until the next successful initialization.
     *
     * @param poolIndex the one-based pool index
     * @return the encoded handle DWORD
     */
    public int getHandle(int poolIndex) {
        return UnitHandle.encode(poolIndex, this.getGeneration(poolIndex), this.capacity);
    }

    /**
     * @return the first map revealer (Scanner Sweep or Map Revealer), or {@code 0}
     */
    public int getFirstRevealerIndex() {
        return this.firstRevealerIndex;
    }

    /**
     * @return the first dying unit, whose allocation is retained through its death animation
     */
    public int getFirstDeadIndex() {
        return this.firstDeadIndex;
    }

    /**
     * @return the last dying unit
     */
    public int getLastDeadIndex() {
        return this.lastDeadIndex;
    }

    /**
     * Returns the unit-list heads and tails in saved record order.
     *
     * @return the one-based first active, hidden, dying and map-revealer units, then the last active, hidden, dying and map-revealer units
     */
    public int @NotNull [] getListHeads() {
        return new int[]{
                this.firstGroundedIndex, this.firstHiddenIndex, this.firstDeadIndex, this.firstRevealerIndex,
                this.lastGroundedIndex, this.lastHiddenIndex, this.lastDeadIndex, this.lastRevealerIndex
        };
    }


    /**
     * Resolves a handle using retained sprite, death-order and full generation checks, without testing allocation commitment.
     * <p>
     * The index field is not bounded by the capacity: an index of zero or past the capacity addresses storage outside this
     * pool, which holds no unit, so such a handle resolves to no unit.
     *
     * @param handle the complete encoded handle DWORD
     * @return the retained unit, or {@code null} for a null, dead or stale handle, or for an index outside this pool
     */
    public @Nullable Unit resolveHandle(int handle) {
        if (handle == 0) {
            return null;
        }

        int poolIndex = UnitHandle.getPoolIndex(handle, this.capacity);

        if (poolIndex == 0 || poolIndex > this.capacity) {
            return null;
        }

        Unit unit = this.slots.get(poolIndex);

        if (unit.getSpriteOrNull() == null ||
            unit.isDestroyed() ||
            this.getGeneration(poolIndex) != UnitHandle.getGeneration(handle, this.capacity)) {
            return null;
        }

        return unit;
    }

    /**
     * Advances the available slot's generation at the successful inner-initialization boundary. A later construction
     * failure keeps this generation while leaving the allocation uncommitted.
     *
     * @param unit the owning pool's available free-head unit
     * @throws IllegalStateException if the unit is not this pool's available head
     */
    public void advanceGeneration(@NotNull Unit unit) {
        int poolIndex = unit.getIndex();

        if (poolIndex < 1 || poolIndex > this.capacity || this.slots.get(poolIndex) != unit ||
            poolIndex != this.freeSlots.getFirst() || this.slots.isAllocated(poolIndex)) {
            throw Exceptions.newIllegalStateException("Generation advancement requires the available unit pool head.");
        }

        this.slots.advanceGeneration(unit);
    }

    /**
     * Removes the free head after storage initialization succeeds, relinking both neighbors and clearing the slot's links.
     * <p>
     * This operation does not advance generation: the construction pipeline already advanced this pool generation before its later effects.
     *
     * @param unit the successfully initialized free-head storage
     * @throws IllegalStateException if the unit is not this pool's available head
     */
    public void commitInitializedUnit(@NotNull Unit unit) {
        int poolIndex = unit.getIndex();

        if (poolIndex < 1 || poolIndex > this.capacity || this.slots.get(poolIndex) != unit ||
            poolIndex != this.freeSlots.getFirst() || this.slots.isAllocated(poolIndex) || this.slots.getAllocatedCount() == this.capacity) {
            throw Exceptions.newIllegalStateException("The initialized unit is not the available native free head.");
        }

        this.slots.commit(unit);
    }

    /**
     * Prepends storage to the original owner list before global creation accounting.
     * <p>
     * Storage initialization performs this insertion before free-list removal; links store pool indices.
     *
     * @param unit  the storage being initialized
     * @param owner the normalized owner supplied to the native constructor
     * @throws IllegalArgumentException if the unit or owner is outside this pool
     */
    public void prependToOwnerList(@NotNull Unit unit, int owner) {
        int poolIndex = unit.getIndex();

        if (owner < 0 || owner >= PlayerTable.PLAYER_SLOTS || poolIndex < 1 || poolIndex > this.capacity || this.slots.get(poolIndex) != unit) {
            throw Exceptions.newIllegalArgumentException("The native owner registration is outside this pool.");
        }

        unit.setMinimumHorizontalEdgeIndex(-1);
        unit.setMaximumHorizontalEdgeIndex(-1);
        unit.setMinimumVerticalEdgeIndex(-1);
        unit.setMaximumVerticalEdgeIndex(-1);

        int previousFirst = this.firstOwnerIndices[owner];

        this.previousOwnerIndices[poolIndex] = 0;
        this.nextOwnerIndices[poolIndex]     = previousFirst;

        if (previousFirst != 0) {
            this.previousOwnerIndices[previousFirst] = poolIndex;
        }

        this.firstOwnerIndices[owner] = poolIndex;
    }

    /**
     * Moves a unit from its current owner's list to the front of another owner's list without changing its owner field.
     *
     * @param unit  the allocated unit, still recorded under its previous owner
     * @param owner the new owner from {@code 0} through {@code 11}
     * @throws IllegalArgumentException if the unit or owner is outside this pool
     * @throws IllegalStateException    if the unit is not linked in its owner's list
     */
    public void moveToOwnerList(@NotNull Unit unit, int owner) {
        int poolIndex = unit.getIndex();

        if (owner < 0 || owner >= PlayerTable.PLAYER_SLOTS || poolIndex < 1 || poolIndex > this.capacity || this.slots.get(poolIndex) != unit) {
            throw Exceptions.newIllegalArgumentException("The native owner transfer is outside this pool.");
        }

        int previous = this.previousOwnerIndices[poolIndex];
        int next = this.nextOwnerIndices[poolIndex];

        if (previous == 0) {
            if (this.firstOwnerIndices[unit.getOwnerIndex()] != poolIndex) {
                throw Exceptions.newIllegalStateException("The unit is not linked in its owner's list.");
            }

            this.firstOwnerIndices[unit.getOwnerIndex()] = next;
        } else {
            this.nextOwnerIndices[previous] = next;
        }

        if (next != 0) {
            this.previousOwnerIndices[next] = previous;
        }

        int previousFirst = this.firstOwnerIndices[owner];

        this.previousOwnerIndices[poolIndex] = 0;
        this.nextOwnerIndices[poolIndex]     = previousFirst;

        if (previousFirst != 0) {
            this.previousOwnerIndices[previousFirst] = poolIndex;
        }

        this.firstOwnerIndices[owner] = poolIndex;
    }

    /**
     * Inserts a successfully created primary unit into its creation list.
     * <p>
     * Insertion retains the head and inserts subsequent storage immediately after it. Subunits bypass this insertion.
     *
     * @param unit the allocated primary unit
     * @throws IllegalStateException if the unit does not identify allocated storage here
     */
    public void insertCreatedUnit(@NotNull Unit unit) {
        int poolIndex = unit.getIndex();

        if (poolIndex < 1 || poolIndex > this.capacity || this.slots.get(poolIndex) != unit || !this.slots.isAllocated(poolIndex)) {
            throw Exceptions.newIllegalStateException("Creation-list insertion requires allocated storage.");
        }

        boolean grounded = UnitQueries.isGroundedBuilding(unit);
        int first = grounded ? this.firstGroundedIndex : this.firstHiddenIndex;

        if (first == 0) {
            this.nextCreatedIndices[poolIndex]     = 0;
            this.previousCreatedIndices[poolIndex] = 0;

            if (grounded) {
                this.firstGroundedIndex = poolIndex;
                this.lastGroundedIndex  = poolIndex;
            } else {
                this.firstHiddenIndex = poolIndex;
                this.lastHiddenIndex  = poolIndex;
            }
        } else {
            int following = this.nextCreatedIndices[first];

            this.nextCreatedIndices[poolIndex]     = following;
            this.previousCreatedIndices[poolIndex] = first;
            this.nextCreatedIndices[first]         = poolIndex;

            if (following != 0) {
                this.previousCreatedIndices[following] = poolIndex;
            } else if (grounded) {
                this.lastGroundedIndex = poolIndex;
            } else {
                this.lastHiddenIndex = poolIndex;
            }
        }
    }

    /**
     * Removes a revealed unit from the hidden list and inserts it into the active list.
     *
     * @param unit the allocated hidden-list member after its reveal effects
     * @throws IllegalStateException if the unit does not identify allocated storage here
     */
    public void transferCreatedUnitToActive(@NotNull Unit unit) {
        int poolIndex = unit.getIndex();

        if (poolIndex < 1 || poolIndex > this.capacity || this.slots.get(poolIndex) != unit || !this.slots.isAllocated(poolIndex)) {
            throw Exceptions.newIllegalStateException("Reveal requires allocated unit storage.");
        }

        int previous = this.previousCreatedIndices[poolIndex];
        int next = this.nextCreatedIndices[poolIndex];

        if (this.firstHiddenIndex == poolIndex) {
            this.firstHiddenIndex = next;
        }

        if (this.lastHiddenIndex == poolIndex) {
            this.lastHiddenIndex = previous;
        }

        if (previous != 0) {
            this.nextCreatedIndices[previous] = next;
        }

        if (next != 0) {
            this.previousCreatedIndices[next] = previous;
        }

        int first = this.firstGroundedIndex;

        if (first == 0) {
            this.firstGroundedIndex                = poolIndex;
            this.lastGroundedIndex                 = poolIndex;
            this.previousCreatedIndices[poolIndex] = 0;
            this.nextCreatedIndices[poolIndex]     = 0;
        } else {
            int following = this.nextCreatedIndices[first];

            this.previousCreatedIndices[poolIndex] = first;
            this.nextCreatedIndices[poolIndex]     = following;
            this.nextCreatedIndices[first]         = poolIndex;

            if (following != 0) {
                this.previousCreatedIndices[following] = poolIndex;
            } else {
                this.lastGroundedIndex = poolIndex;
            }
        }
    }

    /**
     * Moves a completed map revealer from the hidden list to the map-revealer list.
     *
     * @param unit the allocated hidden map revealer
     * @throws IllegalStateException if the unit does not identify allocated storage here
     */
    void transferHiddenUnitToRevealers(@NotNull Unit unit) {
        int poolIndex = unit.getIndex();

        if (poolIndex < 1 || poolIndex > this.capacity || this.slots.get(poolIndex) != unit || !this.slots.isAllocated(poolIndex)) {
            throw Exceptions.newIllegalStateException("Map revealer completion requires allocated unit storage.");
        }

        int previous = this.previousCreatedIndices[poolIndex];
        int next = this.nextCreatedIndices[poolIndex];

        if (this.firstHiddenIndex == poolIndex) {
            this.firstHiddenIndex = next;
        }

        if (this.lastHiddenIndex == poolIndex) {
            this.lastHiddenIndex = previous;
        }

        if (previous != 0) {
            this.nextCreatedIndices[previous] = next;
        }

        if (next != 0) {
            this.previousCreatedIndices[next] = previous;
        }

        int first = this.firstRevealerIndex;

        if (first == 0) {
            this.firstRevealerIndex                = poolIndex;
            this.lastRevealerIndex                 = poolIndex;
            this.previousCreatedIndices[poolIndex] = 0;
            this.nextCreatedIndices[poolIndex]     = 0;
        } else {
            int following = this.nextCreatedIndices[first];

            this.previousCreatedIndices[poolIndex] = first;
            this.nextCreatedIndices[poolIndex]     = following;
            this.nextCreatedIndices[first]         = poolIndex;

            if (following != 0) {
                this.previousCreatedIndices[following] = poolIndex;
            } else {
                this.lastRevealerIndex = poolIndex;
            }
        }

        this.revealers[poolIndex] = true;
    }

    /**
     * Transfers a visible unit to the hidden list while retaining allocation and owner membership.
     *
     * @param unit the allocated visible unit
     * @throws IllegalStateException if the unit does not identify allocated storage here
     */
    void transferCreatedUnitToHidden(@NotNull Unit unit) {
        int poolIndex = unit.getIndex();

        if (poolIndex < 1 || poolIndex > this.capacity || this.slots.get(poolIndex) != unit || !this.slots.isAllocated(poolIndex)) {
            throw Exceptions.newIllegalStateException("Unit hiding requires allocated unit storage.");
        }

        int previous = this.previousCreatedIndices[poolIndex];
        int next = this.nextCreatedIndices[poolIndex];

        if (this.firstGroundedIndex == poolIndex) {
            this.firstGroundedIndex = next;
        }

        if (this.lastGroundedIndex == poolIndex) {
            this.lastGroundedIndex = previous;
        }

        if (previous != 0) {
            this.nextCreatedIndices[previous] = next;
        }

        if (next != 0) {
            this.previousCreatedIndices[next] = previous;
        }

        int first = this.firstHiddenIndex;

        if (first == 0) {
            this.firstHiddenIndex                  = poolIndex;
            this.lastHiddenIndex                   = poolIndex;
            this.previousCreatedIndices[poolIndex] = 0;
            this.nextCreatedIndices[poolIndex]     = 0;
        } else {
            int following = this.nextCreatedIndices[first];

            this.previousCreatedIndices[poolIndex] = first;
            this.nextCreatedIndices[poolIndex]     = following;
            this.nextCreatedIndices[first]         = poolIndex;

            if (following != 0) {
                this.previousCreatedIndices[following] = poolIndex;
            } else {
                this.lastHiddenIndex = poolIndex;
            }
        }
    }

    /**
     * Moves a dying unit from its creation list (map revealer, hidden or active) and its owner list to the dead list.
     *
     * @param unit   the allocated dying unit
     * @param hidden whether the unit is on the hidden list rather than the active list
     * @throws IllegalStateException if the unit does not identify allocated storage here
     */
    void transferUnitToDead(@NotNull Unit unit, boolean hidden) {
        int index = unit.getIndex();

        if (!this.isAllocated(index) || this.slots.get(index) != unit) {
            throw Exceptions.newIllegalStateException("Unit death requires allocated storage.");
        }

        int previous = this.previousCreatedIndices[index];
        int next = this.nextCreatedIndices[index];

        if (this.revealers[index]) {
            this.revealers[index] = false;

            if (this.firstRevealerIndex == index) {
                this.firstRevealerIndex = next;
            }

            if (this.lastRevealerIndex == index) {
                this.lastRevealerIndex = previous;
            }
        } else if (hidden) {
            if (this.firstHiddenIndex == index) {
                this.firstHiddenIndex = next;
            }

            if (this.lastHiddenIndex == index) {
                this.lastHiddenIndex = previous;
            }
        } else {
            if (this.firstGroundedIndex == index) {
                this.firstGroundedIndex = next;
            }

            if (this.lastGroundedIndex == index) {
                this.lastGroundedIndex = previous;
            }
        }

        if (previous != 0) {
            this.nextCreatedIndices[previous] = next;
        }

        if (next != 0) {
            this.previousCreatedIndices[next] = previous;
        }

        previous = this.previousOwnerIndices[index];
        next     = this.nextOwnerIndices[index];

        if (previous == 0) {
            this.firstOwnerIndices[unit.getOwnerIndex()] = next;
        } else {
            this.nextOwnerIndices[previous] = next;
        }

        if (next != 0) {
            this.previousOwnerIndices[next] = previous;
        }

        this.previousOwnerIndices[index] = 0;
        this.nextOwnerIndices[index]     = 0;

        int first = this.firstDeadIndex;

        if (first == 0) {
            this.firstDeadIndex                = index;
            this.lastDeadIndex                 = index;
            this.previousCreatedIndices[index] = 0;
            this.nextCreatedIndices[index]     = 0;
        } else {
            int following = this.nextCreatedIndices[first];

            this.previousCreatedIndices[index] = first;
            this.nextCreatedIndices[index]     = following;
            this.nextCreatedIndices[first]     = index;

            if (following != 0) {
                this.previousCreatedIndices[following] = index;
            } else {
                this.lastDeadIndex = index;
            }
        }
    }

    /**
     * Moves a turret, which never joins the created lists, from its owner list to the dead list.
     *
     * @param unit the allocated turret
     * @throws IllegalStateException if the unit does not identify allocated storage here
     */
    public void transferDetachedUnitToDead(@NotNull Unit unit) {
        int index = unit.getIndex();

        if (!this.isAllocated(index) || this.slots.get(index) != unit) {
            throw Exceptions.newIllegalStateException("Turret death requires allocated storage.");
        }

        this.previousCreatedIndices[index] = 0;
        this.nextCreatedIndices[index]     = 0;

        this.transferUnitToDead(unit, false);
    }

    /**
     * Removes a unit whose death animation ended from the dead list and clears its list links.
     *
     * @param unit the dead-list member
     */
    void unlinkDeadUnit(@NotNull Unit unit) {
        int index = unit.getIndex();
        int previous = this.previousCreatedIndices[index];
        int next = this.nextCreatedIndices[index];

        if (this.firstDeadIndex == index) {
            this.firstDeadIndex = next;
        }

        if (this.lastDeadIndex == index) {
            this.lastDeadIndex = previous;
        }

        if (previous != 0) {
            this.nextCreatedIndices[previous] = next;
        }

        if (next != 0) {
            this.previousCreatedIndices[next] = previous;
        }

        this.previousCreatedIndices[index] = 0;
        this.nextCreatedIndices[index]     = 0;
    }

    /**
     * Clears every slot before loading saved units. Allocation, lists, generations, the union columns and unsaved creation
     * metadata are reset.
     */
    public void clear() {
        for (int poolIndex = 1; poolIndex <= this.capacity; poolIndex++) {
            this.slots.get(poolIndex).clearStorage();
        }

        ColumnTable columns = this.slots.getColumns();

        for (int columnIndex = 0; columnIndex < columns.getCount(); columnIndex++) {
            Column column = columns.get(columns.getKey(columnIndex));

            for (int poolIndex = 1; poolIndex <= this.capacity; poolIndex++) {
                column.reset(poolIndex);
            }
        }

        this.lastRevealerIndex  = 0;
        this.firstRevealerIndex = 0;

        Arrays.fill(this.revealers, false);

        this.lastDeadIndex      = 0;
        this.firstDeadIndex     = 0;
        this.lastHiddenIndex    = 0;
        this.firstHiddenIndex   = 0;
        this.lastGroundedIndex  = 0;
        this.firstGroundedIndex = 0;

        Arrays.fill(this.previousCreatedIndices, 0);
        Arrays.fill(this.nextCreatedIndices, 0);

        Arrays.fill(this.nextOwnerIndices, 0);
        Arrays.fill(this.previousOwnerIndices, 0);
        Arrays.fill(this.firstOwnerIndices, 0);

        Arrays.fill(this.creationTags, 0);

        this.slots.clear();
    }

    /**
     * Marks a slot that a saved game's unit was read into as allocated, with the pool fields of its record.
     *
     * @param poolIndex          the one-based pool index
     * @param generation         the generation BYTE
     * @param previousIndex      the previous unit of its list, or {@code 0}
     * @param nextIndex          the next unit of its list, or {@code 0}
     * @param previousOwnerIndex the previous unit of its owner's list, or {@code 0}
     * @param nextOwnerIndex     the next unit of its owner's list, or {@code 0}
     * @throws IllegalStateException if the slot is already loaded
     */
    public void markLoaded(int poolIndex, int generation, int previousIndex, int nextIndex, int previousOwnerIndex, int nextOwnerIndex) {
        if (this.slots.isAllocated(poolIndex)) {
            throw Exceptions.newIllegalStateException("The saved unit slot {0} is loaded twice.", poolIndex);
        }

        this.slots.restoreAllocated(poolIndex);
        this.slots.setGeneration(poolIndex, generation & GENERATION_BYTE_MASK);

        this.previousCreatedIndices[poolIndex] = previousIndex;
        this.nextCreatedIndices[poolIndex]     = nextIndex;
        this.previousOwnerIndices[poolIndex]   = previousOwnerIndex;
        this.nextOwnerIndices[poolIndex]       = nextOwnerIndex;
    }

    /**
     * Restores the saved unit-list heads and tails and marks the map-revealer list, stopping at a repeated member.
     *
     * @param heads the one-based first active, hidden, dying and map-revealer units, then the last active, hidden, dying and map-revealer units
     */
    public void restoreListHeads(int @NotNull [] heads) {
        this.firstGroundedIndex = heads[SAVED_FIRST_ACTIVE];
        this.firstHiddenIndex   = heads[SAVED_FIRST_HIDDEN];
        this.firstDeadIndex     = heads[SAVED_FIRST_DEAD];
        this.firstRevealerIndex = heads[SAVED_FIRST_REVEALER];
        this.lastGroundedIndex  = heads[SAVED_LAST_ACTIVE];
        this.lastHiddenIndex    = heads[SAVED_LAST_HIDDEN];
        this.lastDeadIndex      = heads[SAVED_LAST_DEAD];
        this.lastRevealerIndex  = heads[SAVED_LAST_REVEALER];

        for (int poolIndex = this.firstRevealerIndex; poolIndex != 0 && !this.revealers[poolIndex]; poolIndex = this.nextCreatedIndices[poolIndex]) {
            this.revealers[poolIndex] = true;
        }
    }

    /**
     * Links unloaded slots into the free list in ascending slot order. The first becomes head and tail and every later
     * slot is inserted right after the head, giving {@code first, last, ..., second}.
     */
    public void rebuildFreeList() {
        for (int poolIndex = 1; poolIndex <= this.capacity; poolIndex++) {
            if (this.slots.isAllocated(poolIndex)) {
                continue;
            }

            UnitSlotPolicy.INSTANCE.release(this.freeSlots, poolIndex);
        }
    }

    /**
     * Returns an allocated slot at the finalized recycle boundary.
     * <p>
     * Callers finish death cleanup and unlinking before recycling. This operation decrements the allocation count,
     * clears allocation and returns the slot to the free list. Insertion preserves the first free slot and inserts
     * immediately after it in the next-pointer traversal, the slot becoming the tail when the head was the tail; into an
     * empty list the slot becomes head and tail with its links unchanged. Recycling does not change the generation byte.
     *
     * @param poolIndex the one-based index of the slot ready for finalized recycling
     * @throws IndexOutOfBoundsException if the index does not identify a pool slot
     * @throws IllegalStateException     if the slot is already free
     */
    public void recycleFinalized(int poolIndex) {
        this.checkPoolIndex(poolIndex);

        if (!this.slots.isAllocated(poolIndex)) {
            throw Exceptions.newIllegalStateException("Pool slot is already free: {0}", poolIndex);
        }

        this.slots.free(this.slots.get(poolIndex));
    }

    private void checkPoolIndex(int poolIndex) {
        if (poolIndex < 1 || poolIndex > this.capacity) {
            throw Exceptions.newIndexOutOfBoundsException("Pool index: {0}", poolIndex);
        }
    }
}
