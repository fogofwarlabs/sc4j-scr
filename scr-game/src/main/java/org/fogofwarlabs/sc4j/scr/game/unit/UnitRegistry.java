package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUpgradeIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Retains the prepopulation registry and bounded replay production records.
 * <p>
 * The registry starts with an empty owner vector and populates owners after map unit creation. Unresolved replay
 * subscriber effects remain explicitly tracked.
 */
public final class UnitRegistry {
    /**
     * The unit types below {@code 32} that the registry skips: the Goliath turret, the Siege Tank turret, the sieged
     * Siege Tank and its turret.
     */
    private static final int SKIPPED_TYPES = 0xC0000050;
    /**
     * The last unit type that {@link #SKIPPED_TYPES} covers.
     */
    private static final int SKIPPED_TYPES_LAST = 31;
    /**
     * The bus node of the replay subscriber.
     */
    private static final int REPLAY_BUS_NODE = 1;
    /**
     * The record flag that enables the bound replay subscriber.
     */
    private static final int SUBSCRIBER_ENABLED_FLAG = 0x80;
    /**
     * The mask of the decoded record identifier and type WORDs.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The multiplier of the state hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * Retains the exact numeric fields of {@code OnBuildingUnitUpdated}. Localized unit-name resolution and subscriber
     * delivery remain pending.
     */
    public static final class ProductionEvent {
        private final int handle;
        private final int owner;
        private final int unitType;
        private final int totalMilliseconds;
        private final int remainingMilliseconds;

        private ProductionEvent(int handle, int owner, int unitType, int totalMilliseconds, int remainingMilliseconds) {
            this.handle                = handle;
            this.owner                 = owner;
            this.unitType              = unitType;
            this.totalMilliseconds     = totalMilliseconds;
            this.remainingMilliseconds = remainingMilliseconds;
        }

        /**
         * @return the unit handle
         */
        public int getHandle() {
            return this.handle;
        }

        /**
         * @return the owning player
         */
        public int getOwner() {
            return this.owner;
        }

        /**
         * @return the normalized type, also supplied to localized-name resolution
         */
        public int getUnitType() {
            return this.unitType;
        }

        /**
         * @return {@code true} if the event is completed, which every retained event is, {@code false} otherwise
         */
        public boolean isDone() {
            return true;
        }

        /**
         * @return the original signed DWORD total duration
         */
        public int getTotalMilliseconds() {
            return this.totalMilliseconds;
        }

        /**
         * @return the original signed DWORD remaining duration
         */
        public int getRemainingMilliseconds() {
            return this.remainingMilliseconds;
        }
    }


    private final @NotNull UnitPool unitPool;
    private @Nullable ScrUnitTypeView units;
    private int frameDuration;

    private boolean initializationActive;
    private boolean replayOwnersInitialized;
    private final boolean @NotNull [] owners;

    private final @NotNull List<@NotNull List<@NotNull ProductionEvent>> pendingUnits;
    private final @NotNull List<@NotNull ProductionEvent> productionEvents;

    private boolean pendingSubscriberEffects;
    private boolean replaySubscriberBound;
    private int subscriberRecordFlags;
    private int subscriberRecordType;

    /**
     * Creates a {@link UnitRegistry} instance, the explicitly established empty registry used before map creation.
     *
     * @param unitPool the persistent unit storage
     */
    public UnitRegistry(@NotNull UnitPool unitPool) {
        this.unitPool      = unitPool;
        this.units         = null;
        this.frameDuration = 0;

        this.initializationActive    = true;
        this.replayOwnersInitialized = false;
        this.owners                  = new boolean[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        this.pendingUnits     = new ArrayList<>();
        this.productionEvents = new ArrayList<>();

        this.pendingSubscriberEffects = false;
        this.replaySubscriberBound    = false;
        this.subscriberRecordFlags    = 0;
        this.subscriberRecordType     = 0;

        for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
            this.pendingUnits.add(new ArrayList<>());
        }
    }

    /**
     * @return {@code true} if the established empty-owner initialization phase is active, {@code false} otherwise
     */
    public boolean isInitializationActive() {
        return this.initializationActive;
    }

    /**
     * @return {@code true} if the replay owner records have been populated, {@code false} otherwise
     */
    public boolean hasReplayOwners() {
        return this.replayOwnersInitialized;
    }

    /**
     * @param owner the player identifier
     * @return the number of pending production records
     */
    public int getPendingProductionCount(int owner) {
        return this.pendingUnits.get(owner).size();
    }

    /**
     * @return the retained completed-event count
     */
    public int getProductionEventCount() {
        return this.productionEvents.size();
    }

    /**
     * @param index the event sequence index
     * @return the retained numeric production event
     */
    public @NotNull ProductionEvent getProductionEvent(int index) {
        return this.productionEvents.get(index);
    }

    /**
     * @return {@code true} if the replay subscriber has a decoded UI record, {@code false} otherwise
     */
    public boolean isReplaySubscriberBound() {
        return this.replaySubscriberBound;
    }

    /**
     * @return the bound UI record flags with the enable flag set
     * @throws IllegalStateException if no UI record is bound
     */
    public int getSubscriberRecordFlags() {
        if (!this.replaySubscriberBound) {
            throw Exceptions.newIllegalStateException("The replay subscriber has no bound UI record.");
        }

        return this.subscriberRecordFlags;
    }

    /**
     * @return the bound UI record type that selects the dispatched events
     * @throws IllegalStateException if no UI record is bound
     */
    public int getSubscriberRecordType() {
        if (!this.replaySubscriberBound) {
            throw Exceptions.newIllegalStateException("The replay subscriber has no bound UI record.");
        }

        return this.subscriberRecordType;
    }

    /**
     * @return {@code true} if subscriber initialization, name resolution or delivery remains pending, {@code false} otherwise
     */
    public boolean hasPendingSubscriberEffects() {
        return this.pendingSubscriberEffects;
    }


    /**
     * Ends the initialization phase, after which the registry accepts units only once its owners are populated.
     */
    public void finishInitialization() {
        this.initializationActive = false;
    }

    /**
     * Registers a stored unit for an eligible replay owner.
     * <p>
     * Neither handle encoding nor the empty owner search tests slot allocation or changes unit storage.
     *
     * @param unit             the stored unit
     * @param includeCompleted whether a completed unit is registered too
     * @throws IllegalStateException    if the owners were not populated after the initialization phase ended
     * @throws IllegalArgumentException if the unit does not belong to the registry's pool
     */
    public void registerStoredUnit(@NotNull Unit unit, boolean includeCompleted) {
        if (!this.initializationActive && !this.replayOwnersInitialized) {
            throw Exceptions.newIllegalStateException("The game start populates the registry owners before the first frame.");
        }

        if ((unit.getStatus() & UnitStatusFlags.COMPLETED) != 0 && !includeCompleted) {
            return;
        }

        if (this.unitPool.getUnit(unit.getIndex()) != unit) {
            throw Exceptions.newIllegalArgumentException("The unit does not belong to the registry's unit pool.");
        }

        int handle = this.unitPool.getHandle(unit.getIndex());
        int owner = unit.getOwnerIndex();

        if (!this.replayOwnersInitialized || owner >= this.owners.length || !this.owners[owner]) {
            return;
        }

        ScrUnitTypeView units = this.units;

        if (units == null) {
            throw Exceptions.newIllegalStateException("The replay owners are populated together with the unit type table.");
        }

        int unitType = UnitQueries.getDisplayedType(units, unit);

        if (units.isEgg(unitType)) {
            unitType = UnitQueries.getQueuedType(unit);
        }

        if (unitType <= SKIPPED_TYPES_LAST && (SKIPPED_TYPES & 1 << unitType) != 0) {
            return;
        }

        this.pendingUnits.get(owner).add(new ProductionEvent(handle, owner, unitType,
                                                             UnitQueries.getBuildTime(units, unit) * this.frameDuration,
                                                             unit.getRemainingBuildTime() * this.frameDuration));
    }

    /**
     * Moves a matching pending production record to the completed event sequence.
     *
     * @param unit the completed unit
     * @throws IllegalStateException    if the owners were not populated after the initialization phase ended
     * @throws IllegalArgumentException if the unit does not belong to the registry's pool
     */
    public void completeUnit(@NotNull Unit unit) {
        if (!this.initializationActive && !this.replayOwnersInitialized) {
            throw Exceptions.newIllegalStateException("The game start populates the registry owners before the first frame.");
        }

        if (this.unitPool.getUnit(unit.getIndex()) != unit) {
            throw Exceptions.newIllegalArgumentException("The unit does not belong to the registry's unit pool.");
        }

        int handle = this.unitPool.getHandle(unit.getIndex());
        int owner = unit.getOwnerIndex();

        if (!this.replayOwnersInitialized || owner >= this.owners.length || !this.owners[owner]) {
            return;
        }

        List<ProductionEvent> pending = this.pendingUnits.get(owner);

        for (int recordIndex = 0; recordIndex < pending.size(); recordIndex++) {
            if (pending.get(recordIndex).handle != handle) {
                continue;
            }

            this.productionEvents.add(pending.remove(recordIndex));

            this.pendingSubscriberEffects = true;

            return;
        }
    }

    /**
     * Initializes replay owner records from the completed initial world before the first frame.
     * <p>
     * The registry must still be in its initialization phase and use the game's unit pool. Research, upgrades,
     * construction, repair and training must not have started.
     *
     * @param game          the game containing the initial player records and created units
     * @param frameDuration the duration of one simulation frame
     * @throws IllegalStateException if the registry is already initialized, uses a different unit pool, or research, upgrades, construction, repair or training is active
     * @throws NullPointerException  if the world was built without game data
     */
    public void populateReplayOwners(@NotNull Game game, int frameDuration) {
        if (!this.initializationActive || this.replayOwnersInitialized || game.getUnitSystem().getUnitPool() != this.unitPool) {
            throw Exceptions.newIllegalStateException("Replay owner population requires the initialization phase and the game's unit pool.");
        }

        int unitIndex = this.unitPool.getFirstCreatedIndex(true);

        while (unitIndex != 0) {
            Unit unit = this.unitPool.getUnit(unitIndex);

            if (UnitQueries.isGroundedBuilding(unit) &&
                (unit.getResearchType() != ScrTechIds.NONE || unit.getUpgradeType() != ScrUpgradeIds.NONE)) {
                throw Exceptions.newIllegalStateException("No research or upgrade runs before the first frame.");
            }

            int primary = unit.getPrimaryOrder();
            int secondary = unit.getSecondaryOrder();

            if ((primary == ScrOrderIds.PLACE_BUILDING || primary == ScrOrderIds.CONSTRUCTING_BUILDING ||
                 primary == ScrOrderIds.REPAIR ||
                 primary == ScrOrderIds.PLACE_PROTOSS_BUILDING && UnitQueries.isGroundedBuilding(unit)) &&
                unit.hasOrderTarget()) {
                throw Exceptions.newIllegalStateException("No construction or repair runs before the first frame.");
            }

            if ((secondary == ScrOrderIds.TRAIN || secondary == ScrOrderIds.TRAIN_FIGHTER ||
                 secondary == ScrOrderIds.BUILD_ADDON && UnitQueries.isGroundedBuilding(unit)) &&
                unit.hasCurrentBuildUnit()) {
                throw Exceptions.newIllegalStateException("No training runs before the first frame.");
            }

            unitIndex = this.unitPool.getNextCreatedIndex(unitIndex);
        }

        for (int owner = 0; owner < this.owners.length; owner++) {
            Player player = game.getPlayerTable().getPlayer(owner);

            this.owners[owner] = player != null && (player.getType() == PlayerTypes.COMPUTER || player.getType() == PlayerTypes.HUMAN);
        }

        this.units                    = game.requireGameData().getUnitTypes();
        this.frameDuration            = frameDuration;
        this.replayOwnersInitialized  = true;
        this.initializationActive     = false;
        this.pendingSubscriberEffects = true;
    }

    /**
     * Binds the replay bus node to its decoded BroadcastObserver UI record.
     * <p>
     * The binding registers node {@code 1}, matches the record identifier WORD and sets the record's enable flag.
     * Callers must supply decoded resource metadata; this method does not load the resource or resolve localized
     * production-event names.
     *
     * @param recordIdentifier the decoded record identifier WORD
     * @param recordType       the decoded record type WORD
     * @param recordFlags      the decoded record flags DWORD
     * @throws IllegalStateException    if the owners are not populated or the bus node is already bound
     * @throws IllegalArgumentException if the record does not match bus node {@code 1}
     */
    public void bindReplaySubscriber(int recordIdentifier, int recordType, int recordFlags) {
        if (!this.replayOwnersInitialized || this.replaySubscriberBound) {
            throw Exceptions.newIllegalStateException("Replay subscriber binding requires populated owners and an unbound bus node.");
        }

        if ((recordIdentifier & WORD_MASK) != REPLAY_BUS_NODE) {
            throw Exceptions.newIllegalArgumentException("The decoded BroadcastObserver record does not match replay bus node {0}.", REPLAY_BUS_NODE);
        }

        this.subscriberRecordFlags    = recordFlags | SUBSCRIBER_ENABLED_FLAG;
        this.subscriberRecordType     = recordType & WORD_MASK;
        this.replaySubscriberBound    = true;
        this.pendingSubscriberEffects = !this.productionEvents.isEmpty();
    }

    /**
     * Includes registry phase, subscriber state, owner flags and production records in the hash.
     *
     * @param hash the preceding simulation hash
     * @return the hash including the retained registry state
     */
    public int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * (HASH_MULTIPLIER * (HASH_MULTIPLIER * hash)) + (this.initializationActive ? 1 : 0);
        hash = HASH_MULTIPLIER * hash + (this.replayOwnersInitialized ? 1 : 0);
        hash = HASH_MULTIPLIER * hash + this.frameDuration;
        hash = HASH_MULTIPLIER * hash + (this.pendingSubscriberEffects ? 1 : 0);
        hash = HASH_MULTIPLIER * hash + (this.replaySubscriberBound ? 1 : 0);
        hash = HASH_MULTIPLIER * hash + this.subscriberRecordFlags;
        hash = HASH_MULTIPLIER * hash + this.subscriberRecordType;

        for (int owner = 0; owner < this.owners.length; owner++) {
            hash = HASH_MULTIPLIER * hash + (this.owners[owner] ? 1 : 0);
            hash = HASH_MULTIPLIER * hash + this.pendingUnits.get(owner).size();

            for (ProductionEvent pending : this.pendingUnits.get(owner)) {
                hash = HASH_MULTIPLIER * hash + pending.handle;
                hash = HASH_MULTIPLIER * hash + pending.totalMilliseconds;
                hash = HASH_MULTIPLIER * hash + pending.remainingMilliseconds;
                hash = HASH_MULTIPLIER * hash + pending.unitType;
            }
        }

        for (ProductionEvent event : this.productionEvents) {
            hash = HASH_MULTIPLIER * hash + event.handle;
            hash = HASH_MULTIPLIER * hash + event.owner;
            hash = HASH_MULTIPLIER * hash + event.unitType;
            hash = HASH_MULTIPLIER * hash + event.totalMilliseconds;
            hash = HASH_MULTIPLIER * hash + event.remainingMilliseconds;
        }

        return hash;
    }
}
