package org.fogofwarlabs.sc4j.scr.game.ability;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerPlayerPhase;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerTerrain;
import org.fogofwarlabs.sc4j.scr.game.resource.CarryingFlags;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitCreationContext;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Handles worker pickup, carry and drop of flags, Uraj and Khalis crystals, the Psi Emitter and other powerups.
 * <p>
 * A powerup runs {@link ScrOrderIds#POWERUP_IDLE} ({@code 97}) or {@link ScrOrderIds#UNUSED_POWERUP} ({@code 17}, the same body plus a tail that starts
 * the return timer of a flag put back at its origin). Visible, they run on the main order timer; hidden, every frame. The worker links its carried
 * powerup (with the powerup carrying flag) and the powerup links its carrier.
 */
public final class UnitPowerups {
    /**
     * The order states of {@link ScrOrderIds#UNUSED_POWERUP}: started, return timer running.
     */
    private static final int RETURNED_FLAG_STATE_START = 0;
    private static final int RETURNED_FLAG_STATE_TIMED = 1;
    /**
     * The timer {@link ScrOrderIds#UNUSED_POWERUP} starts once, kept in the remaining build time.
     */
    private static final int RETURNED_FLAG_TIMER = 0x151;

    /**
     * The image a worker shows while carrying the Uraj crystal.
     */
    private static final int CARRIED_URAJ_IMAGE = 0x3BE;
    /**
     * The image a worker shows while carrying the Khalis crystal.
     */
    private static final int CARRIED_KHALIS_IMAGE = 0x3BF;
    /**
     * Other carried powerups show the image {@code unit type + CARRIED_IMAGE_OFFSET}.
     */
    private static final int CARRIED_IMAGE_OFFSET = 0xB1;
    /**
     * The half size of the box around a powerup searched for a worker, in pixels.
     */
    private static final int PICKUP_RADIUS = 0x18;

    /**
     * The tile flags of which the tile under a dropping worker needs at least {@code 1}: every flag.
     */
    private static final int ANY_TILE_FLAG = 0xFFFFFFFF;

    /**
     * The mask of an unsigned WORD coordinate.
     */
    private static final int WORD_MASK = 0xFFFF;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitPowerups} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitPowerups(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Applies {@link ScrOrderIds#POWERUP_IDLE}, or {@link ScrOrderIds#UNUSED_POWERUP} with its tail: a hidden powerup is shown, a worker in reach picks
     * it up, and {@link ScrOrderIds#UNUSED_POWERUP} starts its return timer once.
     *
     * @param powerup          the powerup running the order
     * @param returnedFlag     whether this is {@link ScrOrderIds#UNUSED_POWERUP} ({@code 17})
     * @param instructionLimit the order and image budget
     * @throws UnsupportedOperationException if the unit creation context is unavailable
     * @throws IllegalStateException         if the powerup has no sprite
     */
    public void updateIdle(@NotNull Unit powerup, boolean returnedFlag, int instructionLimit) {
        Sprite sprite = powerup.requireSprite();

        if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) != 0) {
            UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

            if (context == null) {
                throw Exceptions.newUnsupportedOperationException("Showing a powerup requires the unit creation context.");
            }

            this.game.getUnitVisibility().show(powerup, context.isFoliageEnabled());
        }

        Unit worker = this.findCarrier(powerup);

        if (worker != null) {
            this.pickUp(powerup, worker, instructionLimit);
        }

        if (returnedFlag && powerup.getPrimaryOrderState() == RETURNED_FLAG_STATE_START) {
            powerup.setPrimaryOrderState(RETURNED_FLAG_STATE_TIMED);
            powerup.setRemainingBuildTime(RETURNED_FLAG_TIMER);
        }
    }

    /**
     * Gives a powerup to a worker: a worker carrying resources drops their images and its carrying byte (the amount stays). Under the flag victory
     * condition, a taken Flag marks the localized message as an unrepresented write. The powerup hides and the worker shows its carried image.
     *
     * @param powerup          the powerup
     * @param worker           the picking worker
     * @param instructionLimit the order and image budget
     */
    private void pickUp(@NotNull Unit powerup, @NotNull Unit worker, int instructionLimit) {
        if (worker.getCarryingFlags() != 0) {
            if (worker.getCarriedPowerup() != null) {
                return;
            }

            this.game.getIscriptExecutor().removeCarryImages(worker);
            worker.setCarryingFlags(0);
        }

        int type = powerup.getUnitId();

        if (type == ScrUnitIds.POWERUP_FLAG) {
            this.game.getCaptureTheFlag().markFlagTakenUnrepresented(powerup.getOwnerIndex());
        }

        int image = type == ScrUnitIds.POWERUP_URAJ_CRYSTAL
                    ? CARRIED_URAJ_IMAGE
                    : type == ScrUnitIds.POWERUP_KHALIS_CRYSTAL ? CARRIED_KHALIS_IMAGE : type + CARRIED_IMAGE_OFFSET;

        this.game.getUnitLifecycle().hide(powerup, true);
        this.game.getOrders().issueUntargeted(powerup, ScrOrderIds.NOTHING, instructionLimit);
        powerup.setPowerupCarrier(worker);
        this.game.getIscriptExecutor().addPowerupCarryImage(worker, image, powerup.getOwnerIndex(), instructionLimit);
        worker.setCarryingFlags(worker.getCarryingFlags() | CarryingFlags.POWERUP);
        worker.setCarriedPowerup(powerup);
    }

    /**
     * Drops a carried powerup: a worker carrying a powerup (the powerup carrying flag only) puts it down where it stands if the tile there has any
     * tile flag, is not occupied and is walkable for the powerup, otherwise at the powerup's origin; the placement search over the map rectangle and
     * the move follow. A flag put back at its origin gets {@link ScrOrderIds#UNUSED_POWERUP}, every other powerup {@link ScrOrderIds#POWERUP_IDLE}.
     * <p>
     * Carrying flags of {@code 0} or with a resource flag return without placement. The powerup/carrier links and the worker's carrying byte are
     * cleared only after placement and order issuance finish.
     *
     * @param worker           the worker
     * @param instructionLimit the order activation budget
     * @throws IllegalStateException if the worker has no sprite or carries a powerup flag without a powerup
     */
    public void drop(@NotNull Unit worker, int instructionLimit) {
        int flags = worker.getCarryingFlags();

        if (flags == 0 || (flags & CarryingFlags.RESOURCES) != 0) {
            return;
        }

        Unit powerup = worker.getCarriedPowerup();

        if (powerup == null) {
            throw Exceptions.newIllegalStateException("A worker with carrying flag 4 has no powerup.");
        }

        this.game.getIscriptExecutor().removeCarryImages(worker);

        Sprite sprite = worker.requireSprite();
        int positionX = (short) sprite.getX();
        int positionY = (short) sprite.getY();
        boolean inPlace = this.game.getMap().hasTileFlagsAtPosition(positionX, positionY, ANY_TILE_FLAG, MapTileFlags.OCCUPIED) &&
                          this.game.getUnitPositioning().isTerrainPlacementWalkable(powerup.getUnitId(),
                                                                                    positionX & WORD_MASK, positionY & WORD_MASK);

        if (!inPlace) {
            positionX = (short) powerup.getResources();
            positionY = (short) powerup.getSecondLarvaSideCount();
        }

        GameMap map = this.game.getMap();
        int[] position = {positionX & WORD_MASK, positionY & WORD_MASK};

        this.game.getUnitPositioning().find(powerup,
                                            positionX, positionY,
                                            0, 0,
                                            (short) (map.getWidth() * TerrainTileset.TILE_SIZE), (short) (map.getHeight() * TerrainTileset.TILE_SIZE),
                                            false,
                                            position);
        this.game.getUnitPositioning().move(powerup, position[UnitPositioning.X_INDEX], position[UnitPositioning.Y_INDEX]);

        int order = powerup.getUnitId() == ScrUnitIds.POWERUP_FLAG && !inPlace ? ScrOrderIds.UNUSED_POWERUP : ScrOrderIds.POWERUP_IDLE;

        this.game.getOrders().issueUntargeted(powerup, order, instructionLimit);
        powerup.setPowerupCarrier(null);
        worker.setCarryingFlags(0);
        worker.setCarriedPowerup(null);
    }

    /**
     * Releases the powerup of a carrier: unless its carrying byte is {@code 0}, the carried images go, its powerup is destroyed (hidden on death) and
     * the carrying byte and powerup link are cleared.
     * <p>
     * A carrying byte of {@code 0} returns before image removal, and a {@code null} powerup skips killing. The carrier's carrying byte and powerup
     * link are cleared only after killing finishes.
     *
     * @param carrier          the carrying worker
     * @param instructionLimit the order budget
     */
    public void release(@NotNull Unit carrier, int instructionLimit) {
        if (carrier.getCarryingFlags() == 0) {
            return;
        }

        this.game.getIscriptExecutor().removeCarryImages(carrier);

        Unit powerup = carrier.getCarriedPowerup();

        if (powerup != null) {
            powerup.setUserActionFlags(powerup.getUserActionFlags() | Unit.USER_ACTION_HIDE_ON_DEATH);
            this.game.getUnitLifecycle().kill(powerup, instructionLimit);
        }

        carrier.setCarryingFlags(0);
        carrier.setCarriedPowerup(null);
    }

    /**
     * Finds the worker that picks up a powerup: in the box of {@link #PICKUP_RADIUS} pixels around the powerup, the first completed worker that is
     * neither burrowed nor a hallucination and carries no powerup; a flag is not taken by its own owner.
     *
     * @param powerup the powerup
     * @return the picking worker, or {@code null} if none is in reach
     * @throws UnsupportedOperationException if the computer player phase (the unit rectangle query) is unavailable or the unit pool is not initialized
     * @throws IllegalStateException         if the powerup has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    private @Nullable Unit findCarrier(@NotNull Unit powerup) {
        ComputerPlayerPhase phase = this.game.getComputerPlayerPhase();

        if (phase == null) {
            throw Exceptions.newUnsupportedOperationException("Powerup pickup requires the unit rectangle query.");
        }

        ComputerTerrain query = this.game.getComputerTerrain();
        Sprite sprite = powerup.requireSprite();
        int positionX = sprite.getX();
        int positionY = sprite.getY();
        int[] candidates = query.collectRectanglePoolIndices(positionX - PICKUP_RADIUS, positionY - PICKUP_RADIUS,
                                                             positionX + PICKUP_RADIUS, positionY + PICKUP_RADIUS);

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        for (int poolIndex : candidates) {
            Unit candidate = this.game.getUnitSystem().requireUnitPool().getUnit(poolIndex);

            if (!units.hasEntry(candidate.getUnitId()) || (units.getFlags(candidate.getUnitId()) & UnitTypeFlags.WORKER) == 0) {
                continue;
            }

            int status = candidate.getStatus();

            if ((status & UnitStatusFlags.COMPLETED) == 0 ||
                (status & UnitStatusFlags.BURROWED) != 0 ||
                (status & UnitStatusFlags.HALLUCINATION) != 0 ||
                this.game.getUnitQueries().getCarriedPowerup(candidate) != null) {
                continue;
            }

            if (powerup.getUnitId() == ScrUnitIds.POWERUP_FLAG && candidate.getOwnerIndex() == powerup.getOwnerIndex()) {
                continue;
            }

            return candidate;
        }

        return null;
    }
}
