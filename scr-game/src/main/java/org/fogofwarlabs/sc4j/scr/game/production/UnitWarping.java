package org.fogofwarlabs.sc4j.scr.game.production;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SoundIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitCreationContext;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.visibility.UnitCloaking;
import org.jetbrains.annotations.NotNull;

/**
 * Advances Protoss construction through its retained animation signals and completion phases.
 */
public final class UnitWarping {
    /**
     * The {@link ScrOrderIds#INCOMPLETE_WARPING} states: counting down the build time, starting the warp texture, warping in, completing.
     */
    private static final int WARPING_STATE_COUNTDOWN = 0;
    private static final int WARPING_STATE_TEXTURE = 1;
    private static final int WARPING_STATE_WARP_IN = 2;
    private static final int WARPING_STATE_COMPLETION = 3;

    /**
     * The {@link ScrOrderIds#WARP_IN} states: rebuilding the sprite, finishing the order.
     */
    private static final int WARP_IN_STATE_START = 0;
    private static final int WARP_IN_STATE_FINISH = 1;

    /**
     * The fraction bits of the fixed-point shield points.
     */
    private static final int SHIELD_FRACTION_BITS = 8;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitWarping} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitWarping(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Executes {@link ScrOrderIds#INCOMPLETE_WARPING} before disabled-state and order-countdown processing: the countdown state plays the warp end
     * sound when the build time has elapsed, the texture and warp-in states wait for the order signal, and the completion state completes the
     * building.
     *
     * @param unit             the incomplete building
     * @param instructionLimit the animation, completion and hit point budget
     * @throws IllegalStateException         if the unit has no sprite, the creation context is missing or the state is outside its state machine
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit spatial index is not initialized
     */
    public void update(@NotNull Unit unit, int instructionLimit) {
        Sprite sprite = unit.requireSprite();
        int phase = unit.getPrimaryOrderState();

        if (phase == WARPING_STATE_COUNTDOWN) {
            if (unit.getRemainingBuildTime() == 0) {
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_SPECIAL_STATE_1, 1, instructionLimit);
                this.game.getUnitSounds().play(SoundIds.PROTOSS_BUILDING_WARP_END, unit);
                unit.setPrimaryOrderState(WARPING_STATE_TEXTURE);
            }
        } else if (phase == WARPING_STATE_TEXTURE || phase == WARPING_STATE_WARP_IN) {
            if ((unit.getOrderSignal() & Unit.ORDER_SIGNAL_ACTION_DONE) != 0) {
                unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ACTION_DONE);

                int imageType = this.game.requireGameData().getSpriteTypes().getImage(sprite.getSpriteTypeId());

                this.game.getIscriptExecutor().replaceSpriteImages(unit, imageType, 0, instructionLimit);

                if (phase == WARPING_STATE_TEXTURE) {
                    this.game.getIscriptExecutor().initializeWarpTexture(unit, instructionLimit);
                } else {
                    this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_WARP_IN, 1, instructionLimit);
                }

                unit.setPrimaryOrderState(phase + 1);
            }

            return;
        } else if (phase == WARPING_STATE_COMPLETION) {
            if ((unit.getOrderSignal() & Unit.ORDER_SIGNAL_ACTION_DONE) == 0) {
                return;
            }

            unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ACTION_DONE);

            UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

            if (context == null) {
                throw Exceptions.newIllegalStateException("Warp completion requires its creation context.");
            }

            this.game.getUnitCompletion().initializeBeforePlacement(context, unit, instructionLimit);
            this.game.getUnitCompletion().completeAfterPlacement(context,
                                                                 unit,
                                                                 this.game.getPlayerTable().requirePlayer(unit.getOwnerIndex()).getType(),
                                                                 instructionLimit);
            this.game.getUnitSystem().requireUnitSpatialIndex().notifyCollision(unit, this.game.requireGameData().getUnitTypes());

            if ((unit.getStatus() & UnitStatusFlags.DISABLED) != 0) {
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_DISABLE, 1, instructionLimit);
            }
        } else {
            throw Exceptions.newIllegalStateException("Incomplete warping state is outside its state machine: {0}", phase);
        }

        if (unit.getRemainingBuildTime() != 0) {
            this.game.getUnitBuildTime().advance(unit);
        }

        this.game.getUnitHealth().setHitPoints(unit, unit.getHitPoints() + this.game.getUnitBuildTime().getHitPointIncrement(unit), instructionLimit);
        unit.setShieldPoints(Math.min(unit.getShieldPoints() + this.game.getUnitBuildTime().getShieldIncrement(unit),
                                      this.game.requireGameData().getUnitTypes().getShieldAmount(unit.getUnitId()) << SHIELD_FRACTION_BITS));
    }

    /**
     * Executes {@link ScrOrderIds#WARP_IN} before the disabled test and the order timer. The first state waits for the order signal, clears it,
     * rebuilds the sprite from its {@code sprites.dat} image in the unit's heading, runs the {@link IscriptExecutor#ANIMATION_WARP_IN} animation and
     * enters the second state. The second state waits for the signal again, clears it, cloaks a unit with the {@code units.dat} initially-cloaked
     * flag ({@link UnitCloaking#cloakUnit}, which plays the cloak sound) and finishes the order.
     *
     * @param unit             the warping unit
     * @param instructionLimit the animation and order budget
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void updateWarpIn(@NotNull Unit unit, int instructionLimit) {
        int phase = unit.getPrimaryOrderState();

        if (phase > WARP_IN_STATE_FINISH || (unit.getOrderSignal() & Unit.ORDER_SIGNAL_ACTION_DONE) == 0) {
            return;
        }

        unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ACTION_DONE);

        if (phase == WARP_IN_STATE_START) {
            Sprite sprite = unit.requireSprite();
            int imageType = this.game.requireGameData().getSpriteTypes().getImage(sprite.getSpriteTypeId());

            this.game.getIscriptExecutor().replaceSpriteImages(unit, imageType, unit.getHeading(), instructionLimit);
            this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_WARP_IN, 1, instructionLimit);
            unit.setPrimaryOrderState(WARP_IN_STATE_FINISH);

            return;
        }

        if ((this.game.requireGameData().getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.PERMANENT_CLOAK) != 0) {
            this.game.getEngineGame().getVisibility().setCloaked(unit, true);
        }

        this.game.getOrders().completeOrder(unit, instructionLimit);
    }
}
