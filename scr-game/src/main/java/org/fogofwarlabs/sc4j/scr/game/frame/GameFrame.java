package org.fogofwarlabs.sc4j.scr.game.frame;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.combat.ProjectilePhase;
import org.fogofwarlabs.sc4j.scr.game.movement.UnitMovement;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffect;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffectPool;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.visibility.MapVisibility;
import org.jetbrains.annotations.NotNull;

/**
 * Retains the phase contexts and runs one game frame: the game's {@link FramePhaseList} in its order, inside the frame's execution scope and random
 * scope. During a frame it holds the frame's inputs for the phases. It keeps a reference to the caller's viewport array, not a copy: the phases read
 * the caller's values, and the reference stays until the next frame replaces it.
 */
public final class GameFrame {
    /**
     * The index of the horizontal viewport tile in the viewport array ({@link #getViewport()}).
     */
    static final int VIEWPORT_HORIZONTAL = 0;
    /**
     * The index of the vertical viewport tile in the viewport array.
     */
    static final int VIEWPORT_VERTICAL = 1;
    /**
     * The index of the viewport width in pixels in the viewport array.
     */
    static final int VIEWPORT_WIDTH = 2;
    /**
     * The index of the viewport height in pixels in the viewport array.
     */
    static final int VIEWPORT_HEIGHT = 3;

    /**
     * The number of viewport values: horizontal and vertical tile, width and height in pixels.
     */
    private static final int VIEWPORT_LENGTH = 4;
    /**
     * The multiplier of the diagnostic state hash.
     */
    private static final int HASH_MULTIPLIER = 31;
    /**
     * The random enable flag of the frame's random scope.
     */
    private static final int RANDOM_ENABLED = 1;
    /**
     * The random enable flag outside the frame's random scope.
     */
    private static final int RANDOM_DISABLED = 0;

    private final UnitPhase.@NotNull Context units;
    private final UnitMovement.@NotNull Context movement;
    private final ProjectilePhase.@NotNull Context projectiles;
    private final @NotNull SpriteEffectPool secondarySpriteEffects;
    private final @NotNull MapVisibility visibility;

    private final int @NotNull [] playerTypes;
    private final int @NotNull [] ownerPlayerTypes;

    private int phaseIndex;

    private int elapsedHostTicks;
    private boolean buildingRefreshEnabled;
    private boolean statusRefreshEnabled;
    private int @NotNull [] viewport;
    private int instructionLimit;
    private int buildingPowerRefresh;

    /**
     * Creates a {@link GameFrame} instance with initialized phase contexts and shared visibility controls.
     *
     * @param units                  the retained unit phase timers, lists and direction cache
     * @param movement               the retained movement pulse, result and scratch
     * @param projectiles            the initialized bullet/flingy context and image alias relation
     * @param secondarySpriteEffects the separately initialized sprite effect pool, sized by replay limit {@code 6}
     * @param visibility             the map visibility and rendering controls
     */
    public GameFrame(UnitPhase.@NotNull Context units,
                     UnitMovement.@NotNull Context movement,
                     ProjectilePhase.@NotNull Context projectiles,
                     @NotNull SpriteEffectPool secondarySpriteEffects,
                     @NotNull MapVisibility visibility) {
        this.units                  = units;
        this.movement               = movement;
        this.projectiles            = projectiles;
        this.secondarySpriteEffects = secondarySpriteEffects;
        this.visibility             = visibility;

        this.playerTypes      = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.ownerPlayerTypes = new int[PlayerTable.PLAYER_SLOTS];

        this.phaseIndex = 0;

        this.elapsedHostTicks       = 0;
        this.buildingRefreshEnabled = false;
        this.statusRefreshEnabled   = false;
        this.viewport               = new int[VIEWPORT_LENGTH];
        this.instructionLimit       = 0;
        this.buildingPowerRefresh   = 0;
    }

    /**
     * @return the retained unit phase context
     */
    public UnitPhase.@NotNull Context getUnits() {
        return this.units;
    }

    /**
     * @return the retained movement pulse, result and scratch
     */
    public UnitMovement.@NotNull Context getMovement() {
        return this.movement;
    }

    /**
     * @return the retained projectile pool and traversal context
     */
    public ProjectilePhase.@NotNull Context getProjectiles() {
        return this.projectiles;
    }

    /**
     * @return the retained secondary sprite effect pool, sized by replay limit {@code 6}
     */
    public @NotNull SpriteEffectPool getSecondarySpriteEffects() {
        return this.secondarySpriteEffects;
    }

    /**
     * @return the shared visibility and cheat controls
     */
    public @NotNull MapVisibility getVisibility() {
        return this.visibility;
    }

    /**
     * @return the player types of the playable slots for the current frame
     */
    public int @NotNull [] getPlayerTypes() {
        return this.playerTypes;
    }

    /**
     * @return the player types of every slot for the current frame, the owner player types of the unit phase
     */
    public int @NotNull [] getOwnerPlayerTypes() {
        return this.ownerPlayerTypes;
    }

    /**
     * @return the number of completed phase invocations
     */
    public int getPhaseIndex() {
        return this.phaseIndex;
    }

    /**
     * @return the measured host tick difference of the current frame
     */
    public int getElapsedHostTicks() {
        return this.elapsedHostTicks;
    }

    /**
     * @return {@code true} if the caller's building refresh is enabled for the current frame, {@code false} otherwise
     */
    public boolean isBuildingRefreshEnabled() {
        return this.buildingRefreshEnabled;
    }

    /**
     * @return {@code true} if the caller's status refresh is enabled for the current frame, {@code false} otherwise
     */
    public boolean isStatusRefreshEnabled() {
        return this.statusRefreshEnabled;
    }

    /**
     * @return the viewport of the current frame: horizontal and vertical tile, width and height in pixels; the caller's array of the last {@link #update}, not a copy
     */
    public int @NotNull [] getViewport() {
        return this.viewport;
    }

    /**
     * @return the instruction budget of each movement and animation script invocation in the current frame
     */
    public int getInstructionLimit() {
        return this.instructionLimit;
    }

    /**
     * @return the game's building power refresh gate, read when the current frame started
     */
    public int getBuildingPowerRefresh() {
        return this.buildingPowerRefresh;
    }


    /**
     * Runs one game frame with explicit caller controls and host elapsed ticks: prepares the controls ({@link #prepare}) and runs the frame
     * ({@link #run}).
     *
     * @param game                   the initialized game world
     * @param elapsedHostTicks       the measured DWORD difference used by the unit phase
     * @param buildingRefreshEnabled whether the caller's building refresh is enabled
     * @param statusRefreshEnabled   whether the caller's status refresh is enabled
     * @param viewport               the horizontal/vertical tile WORDs and width/height pixel DWORDs, kept without a copy
     * @param instructionLimit       the per-invocation movement and script budget
     * @throws UnsupportedOperationException if a phase is not ready, required frame state is missing or the viewport does not hold {@code 4} values
     */
    public void update(@NotNull Game game,
                       int elapsedHostTicks,
                       boolean buildingRefreshEnabled, boolean statusRefreshEnabled,
                       int @NotNull [] viewport,
                       int instructionLimit) {
        this.prepare(elapsedHostTicks, buildingRefreshEnabled, statusRefreshEnabled, viewport, instructionLimit);
        this.run(game);
    }

    /**
     * Sets the caller controls and host elapsed ticks of the next frames that {@link #run} runs, as the world step of the engine frame
     * ({@link FrameSequence.Step#WORLD}) does.
     *
     * @param elapsedHostTicks       the measured DWORD difference used by the unit phase
     * @param buildingRefreshEnabled whether the caller's building refresh is enabled
     * @param statusRefreshEnabled   whether the caller's status refresh is enabled
     * @param viewport               the horizontal/vertical tile WORDs and width/height pixel DWORDs; the frame keeps this array (no copy) for its phases
     * @param instructionLimit       the per-invocation movement and script budget
     */
    public void prepare(int elapsedHostTicks,
                        boolean buildingRefreshEnabled, boolean statusRefreshEnabled,
                        int @NotNull [] viewport,
                        int instructionLimit) {
        this.elapsedHostTicks       = elapsedHostTicks;
        this.buildingRefreshEnabled = buildingRefreshEnabled;
        this.statusRefreshEnabled   = statusRefreshEnabled;
        this.viewport               = viewport;
        this.instructionLimit       = instructionLimit;
    }

    /**
     * Runs one game frame with the prepared caller controls: the phases of the game's {@link FramePhaseList}, in order, inside the frame phase
     * of the execution scope with the random generator enabled, which they leave disabled. A phase that throws still ends the frame phase of
     * the execution scope, and the phase index does not advance.
     *
     * @param game the initialized game world
     * @throws UnsupportedOperationException if a phase is not ready, required frame state is missing or the viewport does not hold {@code 4} values
     */
    public void run(@NotNull Game game) {
        FramePhaseList phases = game.getFramePhases();
        GameExecutionScope executionScope = game.getExecutionScope();
        Integer buildingPowerRefresh = game.getBuildingPowerRefresh();

        if (!phases.isReady(game) || executionScope == null || buildingPowerRefresh == null || this.viewport.length != VIEWPORT_LENGTH) {
            throw Exceptions.newUnsupportedOperationException("Native frame execution requires initialized phase contexts and caller controls.");
        }

        for (int playerIndex = 0; playerIndex < this.ownerPlayerTypes.length; playerIndex++) {
            Player player = game.getPlayerTable().getPlayer(playerIndex);

            if (player == null) {
                throw Exceptions.newUnsupportedOperationException("Native frame execution requires every original player record.");
            }

            this.ownerPlayerTypes[playerIndex] = player.getType();

            if (playerIndex < this.playerTypes.length) {
                this.playerTypes[playerIndex] = player.getType();
            }
        }

        this.buildingPowerRefresh = buildingPowerRefresh;

        executionScope.beginFramePhase(this.phaseIndex);

        try {
            game.getRandom().setEnabled(RANDOM_ENABLED);
            phases.update(game, this);
            game.getRandom().setEnabled(RANDOM_DISABLED);
            this.phaseIndex++;
        } finally {
            executionScope.endFramePhase();
        }
    }

    /**
     * Mixes the retained phase state and the bounded secondary sprite effect pool into a diagnostic hash.
     *
     * @param hash the preceding diagnostic hash
     * @return the hash including retained phase state and the bounded empty secondary pool
     */
    public int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + this.phaseIndex;
        hash = this.units.computeStateHash(hash);
        hash = this.projectiles.computeStateHash(hash);
        hash = this.visibility.computeStateHash(hash);
        hash = this.movement.computeStateHash(hash);
        hash = HASH_MULTIPLIER * hash + this.secondarySpriteEffects.getCapacity();
        hash = HASH_MULTIPLIER * hash + this.secondarySpriteEffects.getFirstFreeIndex();
        hash = HASH_MULTIPLIER * hash + this.secondarySpriteEffects.getLastFreeIndex();
        hash = HASH_MULTIPLIER * hash + this.secondarySpriteEffects.getFirstActiveIndex();
        hash = HASH_MULTIPLIER * hash + this.secondarySpriteEffects.getLastActiveIndex();
        hash = HASH_MULTIPLIER * hash + this.secondarySpriteEffects.getAllocatedCount();

        for (int poolIndex = 0; poolIndex < this.secondarySpriteEffects.getCapacity(); poolIndex++) {
            hash = HASH_MULTIPLIER * hash + this.secondarySpriteEffects.getPreviousIndex(poolIndex);
            hash = HASH_MULTIPLIER * hash + this.secondarySpriteEffects.getNextIndex(poolIndex);
            hash = HASH_MULTIPLIER * hash + (this.secondarySpriteEffects.isAllocated(poolIndex) ? 1 : 0);
            hash = HASH_MULTIPLIER * hash + (this.secondarySpriteEffects.isActive(poolIndex) ? 1 : 0);

            SpriteEffect effect = this.secondarySpriteEffects.getSpriteEffect(poolIndex);
            Sprite sprite = effect.getSpriteOrNull();

            hash = HASH_MULTIPLIER * hash + effect.getLife();
            hash = HASH_MULTIPLIER * hash + (sprite == null ? -1 : sprite.getPoolIndex());
        }

        return hash;
    }
}
