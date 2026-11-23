package org.fogofwarlabs.sc4j.scr.game.frame;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUpgradeIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.movement.UnitMovement;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.presentation.GameDisplayState;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.visibility.UnitCloaking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Runs the unit phase of a game frame: order redistribution, restriction flags, dead units, movement, sight, sprite visibility and detection, orders,
 * building power, the Lurker hit history, hidden and cloaked units and the map revealers, in the phase traversal order.
 */
public final class UnitPhase {
    /**
     * The primary order redistribution timer the pool initializer supplies ({@link Context#Context}).
     */
    public static final int INITIAL_PRIMARY_REDISTRIBUTION_TIMER = 10;
    /**
     * The secondary order redistribution timer the pool initializer supplies ({@link Context#Context}).
     */
    public static final int INITIAL_SECONDARY_REDISTRIBUTION_TIMER = 150;

    /**
     * The frames between two redistributions of the primary order countdowns.
     */
    private static final int PRIMARY_REDISTRIBUTION_INTERVAL = 150;
    /**
     * The frames between two redistributions of the visibility update countdowns.
     */
    private static final int SECONDARY_REDISTRIBUTION_INTERVAL = 300;
    /**
     * The number of primary order countdown values the redistribution deals out in turn ({@code 0..7}).
     */
    private static final int PRIMARY_ORDER_COUNTDOWN_SPREAD = 8;
    /**
     * The number of Lurker hit windows; the window index wraps around at this count.
     */
    private static final int LURKER_HIT_WINDOWS = 32;
    /**
     * The number of source/target pairs one Lurker hit window holds.
     */
    private static final int LURKER_HIT_WINDOW_SIZE = 16;
    /**
     * The first unit owner that indexes the four player-type entries after the player slots ({@code 128..131} map to {@code 12..15}); the frame holds
     * no such entries, so a unit with such an owner fails.
     */
    private static final int FIRST_EXTENDED_OWNER = 128;
    /**
     * The last unit owner that indexes an entry after the player slots.
     */
    private static final int LAST_EXTENDED_OWNER = 131;
    /**
     * The host tick difference above which native runs its host-timing diagnostics.
     */
    private static final int HOST_TIMING_DIAGNOSTIC_TICKS = 7500;
    /**
     * The host tick difference from which native skips the host-timing diagnostics.
     */
    private static final int HOST_TIMING_DIAGNOSTIC_LIMIT = 0x0FFFFFFF;
    /**
     * The mask of the player-type BYTE and of the heading BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The number of fractional bits of a fixed-point position.
     */
    private static final int FIXED_POINT_FRACTION_BITS = 8;
    /**
     * The multiplier of the diagnostic state hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * Retains unit scheduling, recent lurker hit history and shared display-state ownership.
     */
    public static final class Context {
        private int primaryRedistributionTimer;
        private int secondaryRedistributionTimer;
        private int interceptorStatusProcessed;

        private @Nullable Unit @Nullable [] lurkerHitSources;
        private @Nullable Unit @Nullable [] lurkerHitTargets;
        private int lurkerHitWindowIndex;
        private int lurkerHitCount;

        private final @NotNull GameDisplayState displayState;

        /**
         * Creates a {@link Context} instance with the retained order-redistribution timers. The pool initializer supplies
         * {@link UnitPhase#INITIAL_PRIMARY_REDISTRIBUTION_TIMER} and {@link UnitPhase#INITIAL_SECONDARY_REDISTRIBUTION_TIMER}.
         *
         * @param primaryRedistributionTimer   the primary order redistribution DWORD
         * @param secondaryRedistributionTimer the secondary order redistribution DWORD
         */
        public Context(int primaryRedistributionTimer, int secondaryRedistributionTimer) {
            this.primaryRedistributionTimer   = primaryRedistributionTimer;
            this.secondaryRedistributionTimer = secondaryRedistributionTimer;
            this.interceptorStatusProcessed   = 0;

            this.lurkerHitSources     = null;
            this.lurkerHitTargets     = null;
            this.lurkerHitWindowIndex = 0;
            this.lurkerHitCount       = 0;

            this.displayState = new GameDisplayState();
        }

        /**
         * @return the retained primary-order redistribution DWORD
         */
        public int getPrimaryRedistributionTimer() {
            return this.primaryRedistributionTimer;
        }

        /**
         * @return the retained secondary-order redistribution DWORD
         */
        public int getSecondaryRedistributionTimer() {
            return this.secondaryRedistributionTimer;
        }

        /**
         * @return the current hit-history window from {@code 0} through {@code 31}
         */
        public int getLurkerHitWindowIndex() {
            return this.lurkerHitWindowIndex;
        }

        /**
         * @return the number of entries retained in the current window, at most {@code 16}
         */
        public int getLurkerHitCount() {
            return this.lurkerHitCount;
        }

        /**
         * @return the single display-state owner shared with host display updates
         */
        public @NotNull GameDisplayState getDisplayState() {
            return this.displayState;
        }


        /**
         * Initializes {@code 32} windows of {@code 16} source/target pairs. Unit references retain pool-slot identity across reuse, matching the
         * original pointer comparison.
         */
        public void initializeLurkerHitHistory() {
            this.lurkerHitSources     = new Unit[LURKER_HIT_WINDOWS * LURKER_HIT_WINDOW_SIZE];
            this.lurkerHitTargets     = new Unit[LURKER_HIT_WINDOWS * LURKER_HIT_WINDOW_SIZE];
            this.lurkerHitWindowIndex = 0;
            this.lurkerHitCount       = 0;
        }

        /**
         * Checks whether any retained window holds a source/target pair.
         *
         * @param source the Lurker
         * @param target the candidate
         * @return {@code true} if any retained window holds the pair, {@code false} otherwise
         * @throws UnsupportedOperationException if the hit history is not initialized
         */
        public boolean hasLurkerHit(@NotNull Unit source, @NotNull Unit target) {
            if (this.lurkerHitSources == null) {
                throw Exceptions.newUnsupportedOperationException("Lurker hit history must be initialized before Subterranean Spines hit.");
            }

            for (int index = 0; index < this.lurkerHitSources.length; index++) {
                if (this.lurkerHitSources[index] == source && this.lurkerHitTargets[index] == target) {
                    return true;
                }
            }

            return false;
        }

        /**
         * Records a pair in the current window unless it already holds {@code 16} pairs.
         *
         * @param source the Lurker
         * @param target the hit unit
         * @throws UnsupportedOperationException if the hit history is not initialized
         */
        public void addLurkerHit(@NotNull Unit source, @NotNull Unit target) {
            if (this.lurkerHitSources == null) {
                throw Exceptions.newUnsupportedOperationException("Lurker hit history must be initialized before Subterranean Spines hit.");
            }

            if (this.lurkerHitCount == LURKER_HIT_WINDOW_SIZE) {
                return;
            }

            int index = this.lurkerHitWindowIndex * LURKER_HIT_WINDOW_SIZE + this.lurkerHitCount;

            this.lurkerHitSources[index] = source;
            this.lurkerHitTargets[index] = target;
            this.lurkerHitCount++;
        }

        /**
         * Mixes the scheduling, display state and retained source/target identities into a diagnostic hash.
         *
         * @param hash the preceding diagnostic hash
         * @return the hash including scheduling, display state and retained source/target identities
         */
        public int computeStateHash(int hash) {
            hash = HASH_MULTIPLIER * hash + this.primaryRedistributionTimer;
            hash = HASH_MULTIPLIER * hash + this.secondaryRedistributionTimer;
            hash = HASH_MULTIPLIER * hash + this.interceptorStatusProcessed;
            hash = HASH_MULTIPLIER * hash + this.lurkerHitWindowIndex;
            hash = HASH_MULTIPLIER * hash + this.lurkerHitCount;
            hash = this.displayState.computeStateHash(hash);
            hash = HASH_MULTIPLIER * hash + (this.lurkerHitSources == null ? 0 : 1);

            if (this.lurkerHitSources != null) {
                for (int index = 0; index < this.lurkerHitSources.length; index++) {
                    Unit source = this.lurkerHitSources[index];
                    Unit target = this.lurkerHitTargets[index];

                    hash = HASH_MULTIPLIER * hash + (source == null ? 0 : source.getIndex());
                    hash = HASH_MULTIPLIER * hash + (target == null ? 0 : target.getIndex());
                }
            }

            return hash;
        }
    }

    /**
     * Advances the Lurker hit history to its next window and clears that window: the window index wraps around after the last window and the window's
     * count restarts.
     *
     * @param context the owner of the {@code 32} retained hit windows
     * @throws UnsupportedOperationException if the hit history is not initialized
     */
    public static void advanceLurkerHitHistory(@NotNull Context context) {
        context.lurkerHitWindowIndex++;

        if (context.lurkerHitWindowIndex == LURKER_HIT_WINDOWS) {
            context.lurkerHitWindowIndex = 0;
        }

        int first = context.lurkerHitWindowIndex * LURKER_HIT_WINDOW_SIZE;

        if (context.lurkerHitSources == null || first < 0 || first > context.lurkerHitSources.length - LURKER_HIT_WINDOW_SIZE) {
            throw Exceptions.newUnsupportedOperationException("Lurker hit history must be initialized before advancing its window.");
        }

        Arrays.fill(context.lurkerHitSources, first, first + LURKER_HIT_WINDOW_SIZE, null);
        Arrays.fill(context.lurkerHitTargets, first, first + LURKER_HIT_WINDOW_SIZE, null);
        context.lurkerHitCount = 0;
    }

    /**
     * Returns the index of a unit owner in the owner player types: an owner from {@link #FIRST_EXTENDED_OWNER} through {@link #LAST_EXTENDED_OWNER}
     * indexes an entry after the player slots, every other owner its own entry.
     *
     * @param owner the unit owner
     * @return the index in the owner player types, which may lie past the entries the frame holds
     */
    private static int getOwnerTypeIndex(int owner) {
        return owner >= FIRST_EXTENDED_OWNER && owner <= LAST_EXTENDED_OWNER ? owner - FIRST_EXTENDED_OWNER + PlayerTable.PLAYER_SLOTS : owner;
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitPhase} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitPhase(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Updates the unit lists, visibility, orders, building power and hit history in phase order.
     *
     * @param context                the initialized auxiliary lists, lurker hit history and retained timers
     * @param movementContext        the shared movement control fields supplied by the outer frame
     * @param ownerPlayerTypes       the original normalized player-type BYTE values
     * @param buildingRefreshEnabled whether the caller's building refresh is enabled
     * @param statusRefreshEnabled   whether the caller's status refresh is enabled
     * @param buildingPowerRefresh   whether the native building power refresh gate DWORD is nonzero
     * @param elapsedHostTicks       the exact DWORD host tick difference of the frame
     * @param instructionLimit       the per-invocation script and movement transition bound
     * @throws UnsupportedOperationException if a caller refresh is enabled, required state is missing or the host tick difference lies in the host-timing diagnostic range
     */
    public void update(@NotNull Context context,
                       UnitMovement.@NotNull Context movementContext,
                       int @NotNull [] ownerPlayerTypes,
                       boolean buildingRefreshEnabled, boolean statusRefreshEnabled, boolean buildingPowerRefresh,
                       int elapsedHostTicks,
                       int instructionLimit) {
        context.interceptorStatusProcessed = 0;

        if (buildingRefreshEnabled || statusRefreshEnabled) {
            throw Exceptions.newUnsupportedOperationException("The building and status refresh of the local selection is not represented.");
        }

        this.redistributeOrders(context);
        this.clearRestrictionFlags();

        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();

        if (accounting == null) {
            throw Exceptions.newUnsupportedOperationException("Native neutral-unit phase requires initialized accounting.");
        }

        if (accounting.getCompletedTypeCount(ScrUnitIds.SPELL_DISRUPTION_WEB, PlayerTable.NEUTRAL_PLAYER_ID) != 0) {
            this.game.getDisruptionWeb().apply();
        }

        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstDeadIndex(); poolIndex != 0; ) {
            int following = pool.getNextCreatedIndex(poolIndex);

            this.game.getUnitLifecycle().updateDead(pool.getUnit(poolIndex), movementContext.getSourceByte() != 0, instructionLimit);
            poolIndex = following;
        }

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            this.updateMovement(movementContext, pool.getUnit(poolIndex), instructionLimit);
        }

        if (movementContext.getSourceByte() != 0) {
            // Refreshes map-revealer vision when the movement source requests a tile update.
            for (int poolIndex = pool.getFirstRevealerIndex(); poolIndex != 0; ) {
                int following = pool.getNextCreatedIndex(poolIndex);

                this.game.getUnitVisibility().updateFog(pool.getUnit(poolIndex));
                poolIndex = following;
            }
        }

        int comparisonMask = this.game.getLocalVision().getComparisonMask();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            this.game.getSpriteVisibility().update(unit, this.game.getMap(), comparisonMask, instructionLimit);

            if ((unit.getStatus() & (UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED)) != 0) {
                unit.setCloakCounter(0);
                this.game.getUnitCloaking().updateDetectionTimer(unit);
            }
        }

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; ) {
            int following = pool.getNextCreatedIndex(poolIndex);
            Unit unit = pool.getUnit(poolIndex);
            int ownerIndex = getOwnerTypeIndex(unit.getOwnerIndex());

            if (ownerIndex >= ownerPlayerTypes.length) {
                throw Exceptions.newUnsupportedOperationException("Native unit phase requires the retained owner-player type.");
            }

            this.game.getUnitOrders().update(unit, ownerPlayerTypes[ownerIndex] & BYTE_MASK, instructionLimit);
            poolIndex = following;
        }

        if (Integer.compareUnsigned(elapsedHostTicks, HOST_TIMING_DIAGNOSTIC_TICKS) > 0 &&
            Integer.compareUnsigned(elapsedHostTicks, HOST_TIMING_DIAGNOSTIC_LIMIT) < 0) {
            throw Exceptions.newUnsupportedOperationException("The unit phase's host-timing diagnostics are not represented.");
        }

        // Reads the building power gate after visible-unit orders so power changes from this loop are applied in this frame.
        if (buildingPowerRefresh || this.game.requireBuildingPowerRefresh() != 0) {
            this.refreshBuildingPower(context, ownerPlayerTypes, instructionLimit);
        }

        advanceLurkerHitHistory(context);

        for (int poolIndex = pool.getFirstCreatedIndex(false); poolIndex != 0; ) {
            int following = pool.getNextCreatedIndex(poolIndex);
            Unit unit = pool.getUnit(poolIndex);

            // Clears the sustaining cloak counter of hidden cloaked units.
            if ((unit.getStatus() & (UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED)) != 0) {
                unit.setCloakCounter(0);
            }

            int ownerIndex = getOwnerTypeIndex(unit.getOwnerIndex());

            if (ownerIndex >= ownerPlayerTypes.length) {
                throw Exceptions.newUnsupportedOperationException("Native hidden-unit phase requires the retained owner-player type.");
            }

            this.game.getUnitOrders().updateHidden(movementContext, unit, ownerPlayerTypes[ownerIndex] & BYTE_MASK, instructionLimit);
            poolIndex = following;
        }

        this.game.getUnitCloaking().updateCloakedUnits();

        // Updates map-revealer orders after hidden and cloaked units.
        for (int poolIndex = pool.getFirstRevealerIndex(); poolIndex != 0; ) {
            int following = pool.getNextCreatedIndex(poolIndex);
            Unit unit = pool.getUnit(poolIndex);
            int ownerIndex = getOwnerTypeIndex(unit.getOwnerIndex());

            if (ownerIndex >= ownerPlayerTypes.length) {
                throw Exceptions.newUnsupportedOperationException("Native map-revealer phase requires the retained owner-player type.");
            }

            this.game.getUnitOrders().update(unit, ownerPlayerTypes[ownerIndex] & BYTE_MASK, instructionLimit);
            poolIndex = following;
        }
    }

    /**
     * Refreshes the building power ({@link #refreshBuildingPower(Context, int[], int)}) with an unlimited instruction budget.
     *
     * @param context          the explicitly supplied display controls
     * @param ownerPlayerTypes the normalized player-type BYTE values
     * @throws UnsupportedOperationException if required state is missing or the display refresh has no matching represented panel and selection state
     */
    public void refreshBuildingPower(@NotNull Context context, int @NotNull [] ownerPlayerTypes) {
        this.refreshBuildingPower(context, ownerPlayerTypes, Integer.MAX_VALUE);
    }

    /**
     * Refreshes provider coverage and associated power animations within the phase instruction budget: a grounded Protoss building of an owner that
     * is not rescue-passive is enabled when it requires no psionic power or is powered, and disabled otherwise; a completed building whose state
     * changes plays its enable or disable animation. Then the gate is cleared and the display state refreshed.
     *
     * @param context          the unit phase and display controls
     * @param ownerPlayerTypes the normalized owner types
     * @param instructionLimit the animation instruction budget
     * @throws UnsupportedOperationException if required state is missing or the display refresh has no matching represented panel and selection state
     * @throws IllegalStateException         if a completed building enabled again has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    public void refreshBuildingPower(@NotNull Context context, int @NotNull [] ownerPlayerTypes, int instructionLimit) {
        if (this.game.requireBuildingPowerRefresh() == 0) {
            return;
        }

        UnitPool pool = this.game.getUnitSystem().getUnitPool();

        if (pool == null) {
            throw Exceptions.newUnsupportedOperationException("Native building-power refresh requires its active unit list.");
        }

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);
            int status = unit.getStatus();

            if (!UnitQueries.isGroundedBuilding(unit)) {
                continue;
            }

            int raceFlags = this.game.requireGameData().getUnitTypes().getGroupFlags(unit.getUnitId());

            if ((raceFlags & UnitGroupFlags.ZERG) != 0 || (raceFlags & UnitGroupFlags.PROTOSS) == 0) {
                continue;
            }

            int owner = unit.getOwnerIndex();
            int ownerIndex = getOwnerTypeIndex(owner);

            if (ownerIndex >= ownerPlayerTypes.length) {
                throw Exceptions.newUnsupportedOperationException("Native building-power refresh requires the owner-player type.");
            }

            if ((ownerPlayerTypes[ownerIndex] & BYTE_MASK) == PlayerTypes.RESCUE_PASSIVE) {
                continue;
            }

            boolean requiresPower = (this.game.requireGameData().getUnitTypes().getFlags(unit.getUnitId()) &
                                     UnitTypeFlags.REQUIRES_PSIONIC_MATRIX) != 0;
            Sprite sprite = unit.getSpriteOrNull();

            if (requiresPower && sprite == null) {
                throw Exceptions.newUnsupportedOperationException("Psionic coverage requires the retained building sprite.");
            }

            if (!requiresPower || this.game.getPsi().isPowered(owner, sprite.getX(), sprite.getY())) {
                unit.setStatus(status & ~UnitStatusFlags.DISABLED);

                if ((status & UnitStatusFlags.COMPLETED) != 0 && (status & UnitStatusFlags.DISABLED) != 0) {
                    Sprite enabledSprite = unit.requireSprite();

                    this.game.getIscriptExecutor().runSpriteAnimation(enabledSprite,
                                                                      unit,
                                                                      IscriptExecutor.ANIMATION_ENABLE,
                                                                      IscriptExecutor.FORCE_ANIMATION,
                                                                      instructionLimit);

                    Unit target = unit.getOrderTargetUnit();

                    if (unit.getUpgradeType() != ScrUpgradeIds.NONE ||
                        (unit.getSecondaryOrder() == ScrOrderIds.PLACE_PROTOSS_BUILDING && target != null &&
                         (target.getStatus() & UnitStatusFlags.COMPLETED) == 0)) {
                        this.game.getIscriptExecutor().runSpriteAnimation(enabledSprite,
                                                                          unit,
                                                                          IscriptExecutor.ANIMATION_IS_WORKING,
                                                                          IscriptExecutor.FORCE_ANIMATION,
                                                                          instructionLimit);
                    }
                }
            } else {
                unit.setStatus(status | UnitStatusFlags.DISABLED);
                this.game.getIscriptExecutor().runToIdle(unit, instructionLimit);

                if ((status & UnitStatusFlags.COMPLETED) != 0 && (status & UnitStatusFlags.DISABLED) == 0) {
                    this.game.getIscriptExecutor().runSpriteAnimation(sprite,
                                                                      unit,
                                                                      IscriptExecutor.ANIMATION_DISABLE,
                                                                      IscriptExecutor.FORCE_ANIMATION,
                                                                      instructionLimit);
                }
            }
        }

        this.game.setBuildingPowerRefresh(0);
        context.displayState.refreshDisplayState();
    }

    /**
     * Applies the active-list countdown redistribution: every {@link #PRIMARY_REDISTRIBUTION_INTERVAL} frames the units of the active list get the
     * primary order countdowns {@code 0..7} in turn, and every {@link #SECONDARY_REDISTRIBUTION_INTERVAL} frames the visibility update countdowns
     * {@code 0..29}.
     *
     * @param context the retained native redistribution timers
     * @throws UnsupportedOperationException if the active unit list is missing
     */
    public void redistributeOrders(@NotNull Context context) {
        UnitPool pool = this.game.getUnitSystem().getUnitPool();

        if (pool == null) {
            throw Exceptions.newUnsupportedOperationException("Native order redistribution requires its active unit list.");
        }

        context.primaryRedistributionTimer--;

        if (context.primaryRedistributionTimer == 0) {
            int countdown = 0;

            for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
                pool.getUnit(poolIndex).setPrimaryOrderCountdown(countdown);
                countdown = countdown == PRIMARY_ORDER_COUNTDOWN_SPREAD - 1 ? 0 : countdown + 1;
            }

            context.primaryRedistributionTimer = PRIMARY_REDISTRIBUTION_INTERVAL;
        }

        context.secondaryRedistributionTimer--;

        if (context.secondaryRedistributionTimer == 0) {
            int countdown = 0;

            for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
                pool.getUnit(poolIndex).setVisibilityUpdateCountdown(countdown);
                countdown = countdown == UnitCloaking.DETECTION_UPDATE_INTERVAL - 1 ? 0 : countdown + 1;
            }

            context.secondaryRedistributionTimer = SECONDARY_REDISTRIBUTION_INTERVAL;
        }
    }

    /**
     * Clears the {@link UnitStatusFlags#CANNOT_ATTACK} status in the active-list pass: a flying unit without the status is skipped; every other unit
     * loses it, and so do the units loaded in a transport ({@link UnitQueries#isTransport}) or the subunit of any other unit.
     *
     * @throws UnsupportedOperationException if the active unit list is missing
     */
    public void clearRestrictionFlags() {
        UnitPool pool = this.game.getUnitSystem().getUnitPool();

        if (pool == null) {
            throw Exceptions.newUnsupportedOperationException("Native unit restriction updates require the active unit list.");
        }

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);
            int status = unit.getStatus();

            if ((status & UnitStatusFlags.FLYING) != 0 && (status & UnitStatusFlags.CANNOT_ATTACK) == 0) {
                continue;
            }

            unit.setStatus(status & ~UnitStatusFlags.CANNOT_ATTACK);

            if (this.game.getUnitQueries().isTransport(unit)) {
                for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
                    Unit loadedUnit = this.game.getUnitTransport().getLoadedUnit(unit, slot);

                    if (loadedUnit != null) {
                        loadedUnit.setStatus(loadedUnit.getStatus() & ~UnitStatusFlags.CANNOT_ATTACK);
                    }
                }
            } else {
                Unit subunit = unit.getSubunit();

                if (subunit != null) {
                    subunit.setStatus(subunit.getStatus() & ~UnitStatusFlags.CANNOT_ATTACK);
                }
            }
        }
    }

    /**
     * Applies the movement and sight sequence of one unit: its movement, its sight when the movement reports a change, and the movement of the turret
     * of a completed unit that is not itself a subunit.
     *
     * @param context         the shared native movement control fields
     * @param unit            the retained native unit
     * @param transitionLimit the movement dispatcher budget
     * @throws NullPointerException if the world was built without game data
     */
    public void updateMovement(UnitMovement.@NotNull Context context, @NotNull Unit unit, int transitionLimit) {
        int previousTargetHeading = unit.getTargetHeading();

        this.game.getUnitMovement().update(context, unit, transitionLimit, transitionLimit);

        if (context.getResult() != 0) {
            this.game.getUnitVisibility().updateFog(unit);
        }

        Unit subunit = unit.getSubunit();

        if ((unit.getStatus() & UnitStatusFlags.COMPLETED) != 0 && subunit != null &&
            (this.game.requireGameData().getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.SUBUNIT) == 0) {
            this.updateSubunitMovement(context, unit, subunit, previousTargetHeading, transitionLimit);
        }
    }

    /**
     * Applies the turret continuation of the unit movement: the turret turns with its base, follows its exact position and special overlay point,
     * switches between Walking and WalkingToIdle with the base's movement, and then runs its own movement.
     *
     * @param context               the shared native movement control fields
     * @param unit                  the base unit
     * @param subunit               the turret of the base unit
     * @param previousTargetHeading the base unit's target heading before its movement
     * @param transitionLimit       the movement dispatcher budget
     * @throws IllegalStateException         if the turret has no sprite
     * @throws UnsupportedOperationException if the sprite pool is not initialized
     */
    private void updateSubunitMovement(UnitMovement.@NotNull Context context,
                                       @NotNull Unit unit, @NotNull Unit subunit,
                                       int previousTargetHeading,
                                       int transitionLimit) {
        int turn = (unit.getTargetHeading() - previousTargetHeading) & BYTE_MASK;

        if (subunit.getOrderTargetUnit() != null) {
            subunit.setStatus(subunit.getStatus() & ~UnitStatusFlags.TURRET_FOLLOWS_BASE);
        } else if (subunit.getHeading() == unit.getHeading()) {
            subunit.setStatus(subunit.getStatus() | UnitStatusFlags.TURRET_FOLLOWS_BASE);
        }

        if ((subunit.getStatus() & UnitStatusFlags.TURRET_FOLLOWS_BASE) != 0) {
            subunit.resetDirection(this.game, unit.getHeading());
        } else {
            subunit.setTargetHeading(subunit.getTargetHeading() + turn);
            subunit.setHeading(subunit.getTargetHeading());
        }

        if (subunit.getUnitId() == ScrUnitIds.TERRAN_GOLIATH_TURRET || subunit.getUnitId() == ScrUnitIds.HERO_ALAN_SCHEZAR_TURRET) {
            // A Goliath turret stays within an eighth turn of its base.
            int difference = (byte) (unit.getHeading() - subunit.getHeading());

            if (difference < -FlingyMotionUtils.DIRECTION_EIGHTH_TURN) {
                subunit.setHeading(unit.getHeading() + FlingyMotionUtils.DIRECTION_EIGHTH_TURN);
            } else if (difference > FlingyMotionUtils.DIRECTION_EIGHTH_TURN) {
                subunit.setHeading(unit.getHeading() - FlingyMotionUtils.DIRECTION_EIGHTH_TURN);
            }
        }

        subunit.setFixedPositionX(unit.getFixedPositionX());
        subunit.setFixedPositionY(unit.getFixedPositionY());
        subunit.setPositionX(unit.getFixedPositionX() >> FIXED_POINT_FRACTION_BITS);
        subunit.setPositionY(unit.getFixedPositionY() >> FIXED_POINT_FRACTION_BITS);

        Sprite sprite = subunit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newIllegalStateException("Native turret movement dereferences an absent subunit sprite.");
        }

        this.game.getSpriteSystem().requireSpritePool().relocate(sprite,
                                                                 (short) (unit.getFixedPositionX() >> FIXED_POINT_FRACTION_BITS),
                                                                 (short) (unit.getFixedPositionY() >> FIXED_POINT_FRACTION_BITS),
                                                                 this.game.getMap().getHeight());
        this.game.getUnitStorageConstructor().attachSubunitImage(unit, subunit);

        boolean moving = (unit.getMovementFlags() & MovementFlags.ACCELERATING) != 0;
        boolean walking = (subunit.getStatus() & UnitStatusFlags.TURRET_WALKING) != 0;

        if (moving != walking) {
            subunit.setStatus(moving ? subunit.getStatus() | UnitStatusFlags.TURRET_WALKING : subunit.getStatus() & ~UnitStatusFlags.TURRET_WALKING);

            if ((moving || (unit.getStatus() & UnitStatusFlags.CAN_MOVE) != 0) && (subunit.getMovementFlags() & MovementFlags.STARTING_ATTACK) == 0) {
                this.game.getIscriptExecutor().runSpriteAnimation(sprite,
                                                                  subunit,
                                                                  moving ? IscriptExecutor.ANIMATION_WALKING
                                                                         : IscriptExecutor.ANIMATION_WALKING_TO_IDLE,
                                                                  IscriptExecutor.FORCE_ANIMATION,
                                                                  transitionLimit);
            }
        }

        this.updateMovement(context, subunit, transitionLimit);
    }
}
