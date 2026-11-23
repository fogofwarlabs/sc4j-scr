package org.fogofwarlabs.sc4j.scr.game.movement;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.movement.MotionStep;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrFlingyTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.path.PathPool;
import org.fogofwarlabs.sc4j.scr.game.path.UnitPath;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitInitializer;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;

/**
 * Runs the unit movement state machine: dispatches the movement state of a unit ({@link MovementStates}) until a state ends the movement of this
 * frame, and commits prospective motion to the unit and its sprite.
 */
public final class UnitMovement {
    /**
     * The largest path recalculation countdown; the states that count waiting frames saturate at it.
     */
    static final int MAXIMUM_PATH_RECALCULATION_COUNTDOWN = 255;
    /**
     * A moving unit keeps its path recalculation countdown at most at this; a higher countdown marks a unit that waits.
     */
    static final int MOVING_PATH_COUNTDOWN = 2;
    /**
     * The random source of the path movement rolls.
     */
    static final int PATH_RANDOM_SOURCE = 51;

    /**
     * The mask of an unsigned movement BYTE field.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of an unsigned coordinate WORD.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The multiplier of the movement context state hash.
     */
    private static final int STATE_HASH_MULTIPLIER = 31;
    /**
     * The fraction bits of the prospective movement coordinates.
     */
    private static final int MOVEMENT_FRACTION_BITS = 8;

    /**
     * The path recalculation countdown a failed path search starts the retry wait with.
     */
    private static final int FAILED_PATH_COUNTDOWN = 10;
    /**
     * The path recalculation countdown at which a failed path search is retried.
     */
    private static final int RETRY_PATH_COUNTDOWN = 20;
    /**
     * The largest random path timer of a commanded iscript-controlled unit.
     */
    private static final int UI_ORDER_DELAY_MAXIMUM = 2;
    /**
     * The path timer a unit sets after it scouted its path.
     */
    private static final int SCOUT_PATH_TIMER = 30;
    /**
     * A turret within this many direction steps of its desired direction clears its ordered flag.
     */
    private static final int TURRET_FACING_TOLERANCE = 10;
    /**
     * A blocked unit slower than this on both axes does not try the half and quarter steps.
     */
    private static final int HALF_STEP_MINIMUM_VELOCITY = 32;
    /**
     * The divisor of the velocity of the half step.
     */
    private static final int HALF_STEP_DIVISOR = 2;
    /**
     * The divisor of the velocity of the quarter step.
     */
    private static final int QUARTER_STEP_DIVISOR = 4;
    /**
     * The status bits of a colliding ground unit, which checks its position.
     */
    private static final int COLLIDING_GROUND_UNIT = UnitStatusFlags.COLLISION | UnitStatusFlags.GROUND_UNIT;
    /**
     * The path collision flags kept by the state initialization: all but the ground elevation and illegal position flags.
     */
    private static final int INITIAL_PATH_FLAGS_KEPT = ~(UnitInitializer.GROUND_ELEVATION_PATH_FLAG | UnitInitializer.ILLEGAL_POSITION_PATH_FLAG) &
                                                       BYTE_MASK;

    /**
     * The path collision flags kept by the illegal position check: all but the illegal position and stopped flags.
     */
    private static final int CHECKED_PATH_FLAGS_KEPT = ~(UnitInitializer.ILLEGAL_POSITION_PATH_FLAG |
                                                         UnitInitializer.STOPPED_MOVING_TO_LEGAL_PATH_FLAG) &
                                                       BYTE_MASK;

    /**
     * Owns prospective motion (the engine {@link MotionStep} of the flingy algorithm), previous positions and reusable avoidance state.
     * Coordinates are signed WORD pixels; exact positions and speed use signed fixed-point values with eight fraction bits.
     */
    public static final class Context implements MotionStep {
        private int sourceByte;
        private int result;

        private short positionX;
        private boolean positionXInitialized;
        private short positionY;
        private boolean positionYInitialized;

        private short previousPositionX;
        private boolean previousPositionXInitialized;
        private short previousPositionY;
        private boolean previousPositionYInitialized;

        private int fixedPositionX;
        private boolean fixedPositionXInitialized;
        private int fixedPositionY;
        private boolean fixedPositionYInitialized;

        private int speed;
        private boolean speedInitialized;

        private byte startingMovement;
        private boolean startingMovementInitialized;
        private byte stoppingMovement;
        private boolean stoppingMovementInitialized;

        private byte preMovementFlags;
        private boolean preMovementFlagsInitialized;
        private byte postMovementFlags;
        private boolean postMovementFlagsInitialized;

        private final @NotNull UnitAvoidance avoidance;
        private final @NotNull UnitUnstacking unstacking;

        /**
         * Creates a {@link Context} instance with no established prospective values.
         *
         * @param sourceByte the low eight bits of the frame pulse
         * @param result     the previous movement result
         */
        public Context(int sourceByte, int result) {
            this.sourceByte = sourceByte & BYTE_MASK;
            this.result     = result;

            this.positionX            = 0;
            this.positionXInitialized = false;
            this.positionY            = 0;
            this.positionYInitialized = false;

            this.previousPositionX            = 0;
            this.previousPositionXInitialized = false;
            this.previousPositionY            = 0;
            this.previousPositionYInitialized = false;

            this.fixedPositionX            = 0;
            this.fixedPositionXInitialized = false;
            this.fixedPositionY            = 0;
            this.fixedPositionYInitialized = false;

            this.speed            = 0;
            this.speedInitialized = false;

            this.startingMovement            = 0;
            this.startingMovementInitialized = false;
            this.stoppingMovement            = 0;
            this.stoppingMovementInitialized = false;

            this.preMovementFlags             = 0;
            this.preMovementFlagsInitialized  = false;
            this.postMovementFlags            = 0;
            this.postMovementFlagsInitialized = false;

            this.avoidance  = new UnitAvoidance();
            this.unstacking = new UnitUnstacking();
        }

        /**
         * @return the unsigned frame pulse used by the next movement update
         */
        public int getSourceByte() {
            return this.sourceByte;
        }

        /**
         * Sets the frame pulse without discarding prospective motion or the previous result.
         *
         * @param sourceByte the low eight bits of the frame pulse
         */
        public void setSourceByte(int sourceByte) {
            this.sourceByte = sourceByte & BYTE_MASK;
        }

        /**
         * @return the movement result, including changes preceding a failed update
         */
        public int getResult() {
            return this.result;
        }

        /**
         * @return the prospective horizontal pixel position
         * @throws IllegalStateException if the value has not been established
         */
        int getPositionX() {
            if (!this.positionXInitialized) {
                throw Exceptions.newIllegalStateException("The movement value positionX has not been established.");
            }

            return this.positionX;
        }

        /**
         * Sets the prospective horizontal position.
         * <p>
         * The SC:R context truncates it to a signed WORD.
         *
         * @param positionX the position in pixels
         */
        @Override
        public void setPositionX(int positionX) {
            this.positionX            = (short) positionX;
            this.positionXInitialized = true;
        }

        /**
         * @return the prospective vertical pixel position
         * @throws IllegalStateException if the value has not been established
         */
        int getPositionY() {
            if (!this.positionYInitialized) {
                throw Exceptions.newIllegalStateException("The movement value positionY has not been established.");
            }

            return this.positionY;
        }

        /**
         * Sets the prospective vertical position.
         * <p>
         * The SC:R context truncates it to a signed WORD.
         *
         * @param positionY the position in pixels
         */
        @Override
        public void setPositionY(int positionY) {
            this.positionY            = (short) positionY;
            this.positionYInitialized = true;
        }

        /**
         * @return the horizontal pixel position before the last committed motion
         * @throws IllegalStateException if the value has not been established
         */
        int getPreviousPositionX() {
            if (!this.previousPositionXInitialized) {
                throw Exceptions.newIllegalStateException("The movement value previousPositionX has not been established.");
            }

            return this.previousPositionX;
        }

        void setPreviousPositionX(int value) {
            this.previousPositionX            = (short) value;
            this.previousPositionXInitialized = true;
        }

        /**
         * @return the vertical pixel position before the last committed motion
         * @throws IllegalStateException if the value has not been established
         */
        int getPreviousPositionY() {
            if (!this.previousPositionYInitialized) {
                throw Exceptions.newIllegalStateException("The movement value previousPositionY has not been established.");
            }

            return this.previousPositionY;
        }

        void setPreviousPositionY(int value) {
            this.previousPositionY            = (short) value;
            this.previousPositionYInitialized = true;
        }

        /**
         * @return the prospective horizontal exact position, fixed-point
         * @throws IllegalStateException if the value has not been established
         */
        @Override
        public int getFixedPositionX() {
            if (!this.fixedPositionXInitialized) {
                throw Exceptions.newIllegalStateException("The movement value fixedPositionX has not been established.");
            }

            return this.fixedPositionX;
        }

        /**
         * Sets the prospective horizontal exact position.
         *
         * @param fixedPositionX the fixed-point position
         */
        @Override
        public void setFixedPositionX(int fixedPositionX) {
            this.fixedPositionX            = fixedPositionX;
            this.fixedPositionXInitialized = true;
        }

        /**
         * @return the prospective vertical exact position, fixed-point
         * @throws IllegalStateException if the value has not been established
         */
        @Override
        public int getFixedPositionY() {
            if (!this.fixedPositionYInitialized) {
                throw Exceptions.newIllegalStateException("The movement value fixedPositionY has not been established.");
            }

            return this.fixedPositionY;
        }

        /**
         * Sets the prospective vertical exact position.
         *
         * @param fixedPositionY the fixed-point position
         */
        @Override
        public void setFixedPositionY(int fixedPositionY) {
            this.fixedPositionY            = fixedPositionY;
            this.fixedPositionYInitialized = true;
        }

        /**
         * @return the speed of the prospective step, fixed-point
         * @throws IllegalStateException if the value has not been established
         */
        int getSpeed() {
            if (!this.speedInitialized) {
                throw Exceptions.newIllegalStateException("The movement value speed has not been established.");
            }

            return this.speed;
        }

        /**
         * Sets the distance the step covers.
         *
         * @param speed the fixed-point distance
         */
        @Override
        public void setSpeed(int speed) {
            this.speed            = speed;
            this.speedInitialized = true;
        }

        /**
         * @return {@code 1} if the prospective step starts the walking animation, {@code 0} otherwise
         * @throws IllegalStateException if the value has not been established
         */
        int getStartingMovement() {
            if (!this.startingMovementInitialized) {
                throw Exceptions.newIllegalStateException("The movement value startingMovement has not been established.");
            }

            return this.startingMovement & BYTE_MASK;
        }

        /**
         * Sets whether the step starts the walking animation.
         *
         * @param starting whether the object starts to move
         */
        @Override
        public void setStartingMovement(boolean starting) {
            this.setStartingMovement(starting ? 1 : 0);
        }

        void setStartingMovement(int value) {
            this.startingMovement            = (byte) value;
            this.startingMovementInitialized = true;
        }

        /**
         * @return {@code 1} if the prospective step ends the walking animation, {@code 0} otherwise
         * @throws IllegalStateException if the value has not been established
         */
        int getStoppingMovement() {
            if (!this.stoppingMovementInitialized) {
                throw Exceptions.newIllegalStateException("The movement value stoppingMovement has not been established.");
            }

            return this.stoppingMovement & BYTE_MASK;
        }

        /**
         * Sets whether the step ends the walking animation.
         *
         * @param stopping whether the object stops moving
         */
        @Override
        public void setStoppingMovement(boolean stopping) {
            this.setStoppingMovement(stopping ? 1 : 0);
        }

        void setStoppingMovement(int value) {
            this.stoppingMovement            = (byte) value;
            this.stoppingMovementInitialized = true;
        }

        /**
         * @return the movement flags before the prospective step
         * @throws IllegalStateException if the value has not been established
         */
        int getPreMovementFlags() {
            if (!this.preMovementFlagsInitialized) {
                throw Exceptions.newIllegalStateException("The movement value preMovementFlags has not been established.");
            }

            return this.preMovementFlags & BYTE_MASK;
        }

        /**
         * Sets the movement flags before the step.
         *
         * @param movementFlags the flags ({@link MovementFlags})
         */
        @Override
        public void setPreMovementFlags(int movementFlags) {
            this.preMovementFlags            = (byte) movementFlags;
            this.preMovementFlagsInitialized = true;
        }

        /**
         * @return the movement flags after the prospective step, applied when the motion is committed
         * @throws IllegalStateException if the value has not been established
         */
        int getPostMovementFlags() {
            if (!this.postMovementFlagsInitialized) {
                throw Exceptions.newIllegalStateException("The movement value postMovementFlags has not been established.");
            }

            return this.postMovementFlags & BYTE_MASK;
        }

        /**
         * Sets the movement flags after the step, applied when the step is committed.
         *
         * @param movementFlags the flags ({@link MovementFlags})
         */
        @Override
        public void setPostMovementFlags(int movementFlags) {
            this.postMovementFlags            = (byte) movementFlags;
            this.postMovementFlagsInitialized = true;
        }


        /**
         * Reads the scratch next exact position as an EUD read does: {@code 0} until movement first stores it.
         *
         * @param vertical whether to read Y
         * @return the raw fixed-point scratch value
         */
        public int readScratchFixedPosition(boolean vertical) {
            if (vertical) {
                return this.fixedPositionYInitialized ? this.fixedPositionY : 0;
            }

            return this.fixedPositionXInitialized ? this.fixedPositionX : 0;
        }

        /**
         * Stores the scratch next exact position as an EUD write does (generic host copy).
         *
         * @param vertical whether to write Y
         * @param value    the raw value
         */
        public void writeScratchFixedPosition(boolean vertical, int value) {
            if (vertical) {
                this.setFixedPositionY(value);
            } else {
                this.setFixedPositionX(value);
            }
        }

        /**
         * Adds the controls, validity and motion values to a diagnostic hash, in declaration order, without a memory image.
         *
         * @param hash the previous hash
         * @return the updated hash
         */
        public int computeStateHash(int hash) {
            hash = STATE_HASH_MULTIPLIER * hash + this.sourceByte;
            hash = STATE_HASH_MULTIPLIER * hash + this.result;
            hash = STATE_HASH_MULTIPLIER * hash + (this.positionXInitialized ? 1 : 0);

            if (this.positionXInitialized) {
                hash = STATE_HASH_MULTIPLIER * hash + this.positionX;
            }

            hash = STATE_HASH_MULTIPLIER * hash + (this.positionYInitialized ? 1 : 0);

            if (this.positionYInitialized) {
                hash = STATE_HASH_MULTIPLIER * hash + this.positionY;
            }

            hash = STATE_HASH_MULTIPLIER * hash + (this.previousPositionXInitialized ? 1 : 0);

            if (this.previousPositionXInitialized) {
                hash = STATE_HASH_MULTIPLIER * hash + this.previousPositionX;
            }

            hash = STATE_HASH_MULTIPLIER * hash + (this.previousPositionYInitialized ? 1 : 0);

            if (this.previousPositionYInitialized) {
                hash = STATE_HASH_MULTIPLIER * hash + this.previousPositionY;
            }

            hash = STATE_HASH_MULTIPLIER * hash + (this.fixedPositionXInitialized ? 1 : 0);

            if (this.fixedPositionXInitialized) {
                hash = STATE_HASH_MULTIPLIER * hash + this.fixedPositionX;
            }

            hash = STATE_HASH_MULTIPLIER * hash + (this.fixedPositionYInitialized ? 1 : 0);

            if (this.fixedPositionYInitialized) {
                hash = STATE_HASH_MULTIPLIER * hash + this.fixedPositionY;
            }

            hash = STATE_HASH_MULTIPLIER * hash + (this.speedInitialized ? 1 : 0);

            if (this.speedInitialized) {
                hash = STATE_HASH_MULTIPLIER * hash + this.speed;
            }

            hash = STATE_HASH_MULTIPLIER * hash + (this.startingMovementInitialized ? 1 : 0);

            if (this.startingMovementInitialized) {
                hash = STATE_HASH_MULTIPLIER * hash + this.startingMovement;
            }

            hash = STATE_HASH_MULTIPLIER * hash + (this.stoppingMovementInitialized ? 1 : 0);

            if (this.stoppingMovementInitialized) {
                hash = STATE_HASH_MULTIPLIER * hash + this.stoppingMovement;
            }

            hash = STATE_HASH_MULTIPLIER * hash + (this.preMovementFlagsInitialized ? 1 : 0);

            if (this.preMovementFlagsInitialized) {
                hash = STATE_HASH_MULTIPLIER * hash + this.preMovementFlags;
            }

            hash = STATE_HASH_MULTIPLIER * hash + (this.postMovementFlagsInitialized ? 1 : 0);

            if (this.postMovementFlagsInitialized) {
                hash = STATE_HASH_MULTIPLIER * hash + this.postMovementFlags;
            }

            return this.avoidance.prediction.computeStateHash(hash);
        }
    }

    /**
     * Increases the path recalculation countdown of a unit by one, saturating at {@link #MAXIMUM_PATH_RECALCULATION_COUNTDOWN}.
     *
     * @param unit the unit whose countdown increases
     */
    static void increasePathRecalculationCountdown(@NotNull Unit unit) {
        unit.setPathRecalculationCountdown(Math.min(MAXIMUM_PATH_RECALCULATION_COUNTDOWN, unit.getPathRecalculationCountdown() + 1));
    }

    /**
     * @param unit   the unit whose movement destination is compared
     * @param sprite the sprite of the unit
     * @return {@code true} if the signed WORD movement destination of the unit equals the sprite position, {@code false} otherwise
     */
    static boolean isSpriteAtMovementDestination(@NotNull Unit unit, @NotNull Sprite sprite) {
        return (short) unit.getMovementDestinationX() == (short) sprite.getX() && (short) unit.getMovementDestinationY() == (short) sprite.getY();
    }

    private static int packMovementDestination(@NotNull Unit unit) {
        return unit.getMovementDestinationX() | unit.getMovementDestinationY() << Short.SIZE;
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitMovement} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitMovement(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Advances movement states, reentering the dispatcher when a state requests another transition.
     *
     * @param context         the movement context
     * @param unit            the moving unit
     * @param transitionLimit the maximum dispatcher iterations
     * @throws IllegalArgumentException      if the transition limit is not positive
     * @throws IllegalStateException         if the transition limit is reached or a NewMoveTarget state has no retained path
     * @throws UnsupportedOperationException if a state lacks its retained sprite, path or collision storage, or the state is not established
     * @throws NullPointerException          if the world was built without game data
     */
    public void update(@NotNull Context context, @NotNull Unit unit, int transitionLimit) {
        this.update(context, unit, transitionLimit, 0);
    }

    /**
     * Advances movement with an explicit instruction budget for movement animation transitions.
     *
     * @param context          the movement context
     * @param unit             the moving unit
     * @param transitionLimit  the maximum dispatcher iterations
     * @param instructionLimit the nested animation instruction budget, or {@code 0} when animation is unavailable
     * @throws IllegalArgumentException      if the transition limit is not positive
     * @throws IllegalStateException         if the transition limit is reached or a NewMoveTarget state has no retained path, or the unit has no sprite
     * @throws UnsupportedOperationException if the state is not established or lacks its retained storage, or a pool is not initialized
     * @throws NullPointerException          if the world was built without game data
     */
    public void update(@NotNull Context context, @NotNull Unit unit, int transitionLimit, int instructionLimit) {
        if (transitionLimit <= 0) {
            throw Exceptions.newIllegalArgumentException("The native movement transition limit must be positive.");
        }

        context.result = context.sourceByte != 0 ? 1 : 0;

        for (int transition = 0; transition < transitionLimit; transition++) {
            int movementState = unit.getMovementState();

            if (movementState > MovementStates.TERRAIN_SLIDE) {
                return;
            }

            switch (movementState) {
                case MovementStates.INIT -> {
                    // A hidden unit that is still moving becomes immovable and finishes its current motion before the hidden state.
                    Sprite hiddenSprite = unit.getSpriteOrNull();

                    if (hiddenSprite != null && (hiddenSprite.getFlags() & Sprite.FLAG_HIDDEN) != 0 &&
                        (unit.getStatus() & UnitStatusFlags.ISCRIPT_NO_BREAK) == 0 &&
                        !unit.isDestroyed() &&
                        (unit.getStatus() & UnitStatusFlags.IN_BUNKER) == 0 &&
                        ((unit.getMovementFlags() & MovementFlags.ACCELERATING) != 0 || !isSpriteAtMovementDestination(unit, hiddenSprite))) {
                        // Clear ground elevation and illegal position flags first so stopping does not convert a stale illegal position flag into the
                        // stopped flag.
                        unit.setPathCollisionFlags((unit.getPathCollisionFlags() & INITIAL_PATH_FLAGS_KEPT) |
                                                   (hiddenSprite.getElevationLevel() < Sprite.AIR_ELEVATION_LEVEL
                                                    ? UnitInitializer.GROUND_ELEVATION_PATH_FLAG
                                                    : 0));
                        this.game.getUnitMovementCollision().setImmovable(unit);
                        FlingyMotionUtils.update(context, unit, this.game.requireGameData().getFlingyProperties());
                        this.commitMotion(context, unit, instructionLimit);
                    }

                    this.initializeState(unit);
                }
                case MovementStates.INIT_SEQUENCE -> {
                    if ((unit.getStatus() & UnitStatusFlags.ISCRIPT_NO_BREAK) != 0) {
                        return;
                    }

                    unit.setMovementState(MovementStates.INIT);
                }
                case MovementStates.LUMP, MovementStates.FAKE_FLYER -> {
                    return;
                }
                case MovementStates.HIDDEN -> {
                    context.result = 0;

                    return;
                }
                case MovementStates.TURRET -> {
                    // Pin the move target to the sprite position without refreshing vision.
                    context.result = 0;

                    Sprite sprite = unit.getSpriteOrNull();

                    if (sprite == null) {
                        throw Exceptions.newUnsupportedOperationException("Native turret movement requires its retained sprite.");
                    }

                    this.game.getUnitPositioning().setMovementDestination(unit, (short) sprite.getX(), (short) sprite.getY());

                    int directionError = (byte) (unit.getDesiredMovementDirection() - unit.getHeading());

                    if (directionError >= -TURRET_FACING_TOLERANCE && directionError <= TURRET_FACING_TOLERANCE) {
                        unit.setMovementFlags(unit.getMovementFlags() & ~MovementFlags.ORDERED);
                    }

                    if ((unit.getStatus() & UnitStatusFlags.TURRET_FOLLOWS_BASE) != 0) {
                        FlingyMotionUtils.updateMovementFlags(context, unit);
                    } else {
                        FlingyMotionUtils.updateDirection(unit);
                        FlingyMotionUtils.updateMovementFlags(context, unit);
                        this.updateHeading(unit);
                    }

                    return;
                }
                case MovementStates.BUNKER -> {
                    context.result = 0;

                    FlingyMotionUtils.updateDirection(unit);
                    FlingyMotionUtils.updateMovementFlags(context, unit);
                    this.updateHeading(unit);

                    return;
                }
                case MovementStates.BUILDING_TURRET -> {
                    FlingyMotionUtils.updateDirection(unit);
                    FlingyMotionUtils.updateMovementFlags(context, unit);
                    this.updateHeading(unit);

                    return;
                }
                case MovementStates.FLYER -> {
                    this.updateFlyingState(context, unit, instructionLimit);

                    return;
                }
                case MovementStates.AT_REST -> {
                    Sprite sprite = unit.getSpriteOrNull();

                    if (sprite == null) {
                        throw Exceptions.newUnsupportedOperationException("Native movement state9 requires its retained sprite.");
                    }

                    boolean atTarget = isSpriteAtMovementDestination(unit, sprite);
                    int remaining = unit.getPathRecalculationCountdown();

                    if (atTarget) {
                        unit.setPathRecalculationCountdown(0);
                    } else if (remaining > MOVING_PATH_COUNTDOWN) {
                        unit.setPathRecalculationCountdown(MOVING_PATH_COUNTDOWN);
                    } else if (remaining != 0) {
                        unit.setPathRecalculationCountdown(remaining - 1);
                    }

                    int movementFlags = unit.getMovementFlags();
                    boolean calculateMotion = (movementFlags & MovementFlags.BRAKING) != 0 ||
                                              (atTarget && (movementFlags & MovementFlags.ACCELERATING) != 0);

                    if (atTarget &&
                        (unit.getDirectionTargetX() != (unit.getPositionX() & WORD_MASK) ||
                         unit.getDirectionTargetY() != (unit.getPositionY() & WORD_MASK))) {
                        int direction = FlingyMotionUtils.calculateDirection((short) unit.getDirectionTargetX() - (short) sprite.getX(),
                                                                             (short) unit.getDirectionTargetY() - (short) sprite.getY());

                        calculateMotion |= direction != unit.getHeading() || direction != unit.getTargetHeading();
                    }

                    if (calculateMotion) {
                        FlingyMotionUtils.update(context, unit, this.game.requireGameData().getFlingyProperties());

                        int horizontal = (short) context.getPositionX();
                        int vertical = (short) context.getPositionY();

                        if (this.game.getMovementCollision().findUnitCollision(unit, horizontal, vertical) != null ||
                            this.game.getMovementCollision().isBlockedByTerrain(unit, horizontal, vertical)) {
                            this.rollBackStationaryMotion(context, unit, sprite, instructionLimit);
                        } else {
                            this.commitMotion(context, unit, instructionLimit);
                        }
                    }

                    if ((unit.getStatus() & COLLIDING_GROUND_UNIT) == COLLIDING_GROUND_UNIT) {
                        unit.setMovementState(MovementStates.CHECK_ILLEGAL);

                        return;
                    }

                    atTarget = isSpriteAtMovementDestination(unit, sprite);

                    if (!atTarget && (unit.getMovementFlags() & MovementFlags.BRAKING) == 0) {
                        unit.setMovementState(MovementStates.START_PATH);
                    } else if (calculateMotion) {
                        return;
                    } else {
                        unit.setNextSpeed(0);

                        if (unit.getCurrentSpeed() != 0) {
                            unit.setCurrentSpeed(0);
                            unit.setVelocityX(0);
                            unit.setVelocityY(0);
                        }

                        unit.setDirectionTargetX(sprite.getX());
                        unit.setDirectionTargetY(sprite.getY());
                        unit.setMovementState(MovementStates.DORMANT);

                        return;
                    }
                }
                case MovementStates.DORMANT -> {
                    Sprite sprite = unit.getSpriteOrNull();

                    if (sprite == null) {
                        throw Exceptions.newUnsupportedOperationException("Native movement state10 requires its retained sprite.");
                    }

                    if ((unit.getStatus() & COLLIDING_GROUND_UNIT) != COLLIDING_GROUND_UNIT &&
                        isSpriteAtMovementDestination(unit, sprite) &&
                        unit.getDirectionTargetX() == (unit.getPositionX() & WORD_MASK) &&
                        unit.getDirectionTargetY() == (unit.getPositionY() & WORD_MASK)) {
                        return;
                    }

                    unit.setMovementState(MovementStates.AT_REST);
                }
                case MovementStates.AT_MOVE_TARGET -> {
                    if (unit.getPathIndex() >= 0) {
                        this.game.getPathSystem().requirePathPool().release(unit.getPathIndex());
                        unit.setPathIndex(-1);
                    }

                    unit.setMovementWaypointX(unit.getMovementDestinationX());
                    unit.setMovementWaypointY(unit.getMovementDestinationY());
                    unit.setMovementState((unit.getStatus() & UnitStatusFlags.GROUND_UNIT) != 0 &&
                                          (unit.getMovementFlags() & MovementFlags.BRAKING) == 0
                                          ? MovementStates.CHECK_ILLEGAL
                                          : MovementStates.AT_REST);
                }
                case MovementStates.CHECK_ILLEGAL -> {
                    unit.setStatus(unit.getStatus() & ~UnitStatusFlags.COLLISION);

                    Sprite sprite = unit.getSpriteOrNull();

                    if (sprite == null) {
                        throw Exceptions.newUnsupportedOperationException("Native movement state12 requires its retained sprite.");
                    }

                    int positionX = (short) sprite.getX();
                    int positionY = (short) sprite.getY();

                    if ((unit.getStatus() & UnitStatusFlags.GROUND_UNIT) != 0) {
                        int unitType = unit.getUnitId();
                        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
                        boolean outsideMap = positionX < (short) unitTypes.getDimensionLeft(unitType) ||
                                             positionX + (short) unitTypes.getDimensionRight(unitType) >=
                                             this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE ||
                                             positionY < (short) unitTypes.getDimensionUp(unitType) ||
                                             positionY + (short) unitTypes.getDimensionDown(unitType) >=
                                             this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE;
                        UnitSpatialIndex spatialIndex = this.game.getUnitSystem().getUnitSpatialIndex();

                        if (spatialIndex == null) {
                            throw Exceptions.newUnsupportedOperationException("Native state12 requires its initialized movement collision query.");
                        }

                        Unit blocker = outsideMap
                                       ? null
                                       : spatialIndex.findMovementBlocker(this.game.getHooks().getCollisionRules(),
                                                                          unit, unitTypes, positionX, positionY);

                        if (outsideMap || blocker != null ||
                            !this.game.getUnitPositioning().isTerrainPlacementWalkable(unitType, positionX, positionY)) {
                            if ((unit.getStatus() & (UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE | UnitStatusFlags.ISCRIPT_NO_BREAK)) != 0 ||
                                (unit.getMovementFlags() & MovementFlags.STARTING_ATTACK) != 0) {
                                unit.setStatus(unit.getStatus() | UnitStatusFlags.COLLISION);
                            } else {
                                if (context.unstacking.findEscape(this.game, unit, blocker)) {
                                    unit.setPathCollisionFlags(unit.getPathCollisionFlags() | UnitInitializer.ILLEGAL_POSITION_PATH_FLAG);
                                    unit.setMovementState(MovementStates.MOVE_TO_LEGAL);

                                    return;
                                }

                                unit.setStatus(unit.getStatus() | UnitStatusFlags.COLLISION);
                            }
                        }
                    }

                    unit.setPathCollisionFlags(unit.getPathCollisionFlags() & CHECKED_PATH_FLAGS_KEPT);

                    boolean atTarget = (short) unit.getMovementDestinationX() == positionX && (short) unit.getMovementDestinationY() == positionY;

                    unit.setMovementState(atTarget || (unit.getMovementFlags() & MovementFlags.BRAKING) != 0
                                          ? MovementStates.AT_REST
                                          : MovementStates.ANOTHER_PATH);
                }
                case MovementStates.LUMP_WANNABE -> {
                    // An immobile ground unit that is still moving, such as a Terran building that has just landed, finishes or rolls back its last
                    // motion before becoming a lump.
                    Sprite sprite = unit.getSpriteOrNull();

                    if (sprite == null) {
                        throw Exceptions.newUnsupportedOperationException("Native movement state14 requires its retained sprite.");
                    }

                    boolean atTarget = isSpriteAtMovementDestination(unit, sprite);

                    if (atTarget && (unit.getMovementFlags() & MovementFlags.ACCELERATING) == 0) {
                        unit.setMovementState(MovementStates.LUMP);

                        continue;
                    }

                    FlingyMotionUtils.update(context, unit, this.game.requireGameData().getFlingyProperties());

                    int horizontal = (short) context.getPositionX();
                    int vertical = (short) context.getPositionY();

                    if (this.game.getMovementCollision().findUnitCollision(unit, horizontal, vertical) != null ||
                        this.game.getMovementCollision().isBlockedByTerrain(unit, horizontal, vertical)) {
                        this.rollBackStationaryMotion(context, unit, sprite, instructionLimit);
                    } else {
                        this.commitMotion(context, unit, instructionLimit);
                    }

                    return;
                }
                case MovementStates.MOVE_TO_LEGAL -> {
                    if (context.unstacking.moveToLegal(context, this.game, unit, instructionLimit)) {
                        continue;
                    }

                    return;
                }
                case MovementStates.START_PATH -> {
                    if (!this.game.getPathSystem().requireUnitPath().update(this.game, unit, packMovementDestination(unit))) {
                        unit.setMovementState(MovementStates.FAILED_PATH);

                        return;
                    }

                    int nextState = MovementStates.FOLLOW_PATH;

                    if ((unit.getUserActionFlags() & Unit.USER_ACTION_COMMANDED) != 0) {
                        unit.setUserActionFlags(unit.getUserActionFlags() & ~Unit.USER_ACTION_COMMANDED);

                        if (unit.getMovementControl() == ScrFlingyTypeView.MOVEMENT_CONTROL_ISCRIPT) {
                            this.game.getPathSystem().requirePathPool().setTimer(
                                    unit.getPathIndex(),
                                    this.game.getRandom().next(PATH_RANDOM_SOURCE, 0, UI_ORDER_DELAY_MAXIMUM)
                            );

                            nextState = MovementStates.UI_ORDER_DELAY;
                        }
                    }

                    unit.setMovementState(nextState);
                }
                case MovementStates.FAILED_PATH -> {
                    unit.setPathRecalculationCountdown(FAILED_PATH_COUNTDOWN);
                    this.game.getUnitMovementCollision().stopWalkingAnimation(unit, instructionLimit);
                    unit.setMovementState(MovementStates.RETRY_PATH);

                    return;
                }
                case MovementStates.RETRY_PATH -> {
                    if (UnitPath.updateMovementGuard(this.game, unit, false)) {
                        continue;
                    }

                    if (unit.getPathRecalculationCountdown() < RETRY_PATH_COUNTDOWN) {
                        unit.setPathRecalculationCountdown(unit.getPathRecalculationCountdown() + 1);

                        return;
                    }

                    if (this.game.getPathSystem().requireUnitPath().update(this.game, unit, packMovementDestination(unit))) {
                        unit.setMovementState(MovementStates.FOLLOW_PATH);
                    } else {
                        this.game.getUnitMovementCollision().setImmovable(unit);
                        unit.setMovementState(MovementStates.AT_MOVE_TARGET);

                        return;
                    }
                }
                case MovementStates.UI_ORDER_DELAY -> {
                    if (UnitPath.updateMovementGuard(this.game, unit, true)) {
                        continue;
                    }

                    if (UnitPath.updateTimer(this.game, unit)) {
                        unit.setMovementState(MovementStates.FOLLOW_PATH);
                    } else {
                        return;
                    }
                }
                case MovementStates.TURN_AND_START -> {
                    UnitPath path = this.game.getPathSystem().requireUnitPath();
                    Unit collision = path.getConsideredCollisionUnit();

                    if (unit.getPathIndex() >= 0) {
                        collision = this.game.getUnitSystem().requireUnitPool().resolveHandle(
                                this.game.getPathSystem().requirePathPool().getLastCollisionHandle(unit.getPathIndex())
                        );

                        this.game.getPathSystem().requirePathPool().release(unit.getPathIndex());
                        unit.setPathIndex(-1);
                        path.setConsideredCollisionUnit(collision);
                    }

                    if (UnitTargetMovement.isAtMovementTarget(unit) || (unit.getMovementFlags() & MovementFlags.BRAKING) != 0) {
                        unit.setMovementState(MovementStates.AT_MOVE_TARGET);

                        continue;
                    }

                    path.setConsideredCollisionUnit(null);

                    if (path.update(this.game, unit, packMovementDestination(unit), collision, false)) {
                        unit.setMovementState(MovementStates.FACE_TARGET);

                        return;
                    }

                    unit.setMovementState(MovementStates.FAILED_PATH);
                }
                case MovementStates.FACE_TARGET -> {
                    if (UnitPath.updateMovementGuard(this.game, unit, true)) {
                        continue;
                    }

                    if (UnitTargetMovement.isAtMovementTarget(unit) || (unit.getMovementFlags() & MovementFlags.BRAKING) != 0) {
                        unit.setMovementState(MovementStates.AT_MOVE_TARGET);
                    } else if (unit.getTargetHeading() ==
                               FlingyMotionUtils.calculateDirection(unit.getMovementWaypointX() - unit.getPositionX(),
                                                                    unit.getMovementWaypointY() - unit.getPositionY())) {
                        unit.setMovementState(
                                (this.game.getPathSystem().requirePathPool().getFlags(unit.getPathIndex()) & PathPool.FLAG_NEW_MOVE_TARGET) != 0
                                ? MovementStates.NEW_MOVE_TARGET
                                : MovementStates.FOLLOW_PATH
                        );
                    } else {
                        FlingyMotionUtils.update(context, unit, this.game.requireGameData().getFlingyProperties());
                        unit.setTargetHeading(unit.getMovementDirection());
                        FlingyMotionUtils.updateFacing(unit, this.game.requireGameData().getFlingyProperties());
                        IscriptExecutor.applySpriteDirection(this.game, unit.requireSprite(), unit.getHeading());

                        return;
                    }
                }
                case MovementStates.NEW_MOVE_TARGET -> {
                    int pathIndex = unit.getPathIndex();

                    if (pathIndex < 0) {
                        throw Exceptions.newIllegalStateException("Movement NewMoveTarget requires its retained path.");
                    }

                    PathPool paths = this.game.getPathSystem().requirePathPool();

                    paths.setFlags(pathIndex, paths.getFlags(pathIndex) & ~PathPool.FLAG_NEW_MOVE_TARGET);

                    if (!UnitPath.updateMovementGuard(this.game, unit, true)) {
                        if (unit.getPathIndex() >= 0) {
                            paths.release(unit.getPathIndex());
                            unit.setPathIndex(-1);
                        }

                        unit.setMovementState(MovementStates.REPATH);
                    }
                }
                case MovementStates.ANOTHER_PATH -> {
                    UnitPath path = this.game.getPathSystem().getUnitPath();

                    if (path == null) {
                        throw Exceptions.newUnsupportedOperationException("Native movement state22 requires initialized path storage.");
                    }

                    unit.setMovementState(path.update(this.game, unit, packMovementDestination(unit))
                                          ? MovementStates.FOLLOW_PATH
                                          : MovementStates.FAILED_PATH);

                    return;
                }
                case MovementStates.REPATH -> {
                    UnitPath path = this.game.getPathSystem().requireUnitPath();
                    Unit collision = path.getConsideredCollisionUnit();

                    if (unit.getPathIndex() >= 0) {
                        collision = this.game.getUnitSystem().requireUnitPool().resolveHandle(
                                this.game.getPathSystem().requirePathPool().getLastCollisionHandle(unit.getPathIndex())
                        );

                        this.game.getPathSystem().requirePathPool().release(unit.getPathIndex());
                        unit.setPathIndex(-1);
                    }

                    path.setConsideredCollisionUnit(null);
                    unit.setMovementState(path.update(this.game, unit, packMovementDestination(unit), collision, false)
                                          ? MovementStates.FOLLOW_PATH
                                          : MovementStates.FAILED_PATH);
                }
                case MovementStates.REPATH_MOVERS -> {
                    if (unit.getPathIndex() >= 0) {
                        this.game.getPathSystem().requirePathPool().release(unit.getPathIndex());
                        unit.setPathIndex(-1);
                    }

                    int destinationX = unit.getMovementDestinationX();
                    int destinationY = unit.getMovementDestinationY();
                    Unit target = unit.getMovementTargetUnit();
                    UnitPath path = this.game.getPathSystem().requireUnitPath();

                    if (!path.update(this.game, unit, destinationX | destinationY << Short.SIZE, path.getConsideredCollisionUnit(), true) ||
                        (unit.getStatus() & UnitStatusFlags.IMMOVABLE) != 0) {
                        unit.setMovementDestinationX(destinationX);
                        unit.setMovementDestinationY(destinationY);
                        unit.setMovementTargetUnit(target);
                        unit.setMovementState(MovementStates.TURN_AND_START);

                        return;
                    }

                    unit.setMovementState(MovementStates.FACE_TARGET);
                }
                case MovementStates.FOLLOW_PATH -> {
                    if (!this.updatePathMovement(context, unit, instructionLimit)) {
                        return;
                    }
                }
                case MovementStates.SCOUT_PATH -> context.avoidance.update(this.game, unit, instructionLimit);
                case MovementStates.SCOUT_FREE -> unit.setMovementState(MovementStates.FOLLOW_PATH);
                case MovementStates.FIX_COLLISION -> {
                    if (this.game.getUnitMovementCollision().fix(unit, instructionLimit)) {
                        continue;
                    }

                    return;
                }
                case MovementStates.WAIT_FREE -> {
                    if (this.game.getUnitMovementCollision().waitFree(context, unit, instructionLimit)) {
                        continue;
                    }

                    return;
                }
                case MovementStates.GET_FREE -> {
                    increasePathRecalculationCountdown(unit);

                    if (UnitPath.updateMovementGuard(this.game, unit, true)) {
                        continue;
                    }

                    unit.setNextSpeed(this.game.getPathSystem().requirePathPool().getSavedSpeed(unit.getPathIndex()));
                    FlingyMotionUtils.setSpeed(unit, unit.getNextSpeed());
                    FlingyMotionUtils.update(context, unit, this.game.requireGameData().getFlingyProperties());

                    if (this.game.getMovementCollision().findAnyUnitCollision(unit, context.getPositionX(), context.getPositionY()) != null) {
                        if (unit.getTargetHeading() ==
                            FlingyMotionUtils.calculateDirection(unit.getMovementWaypointX() - unit.getPositionX(),
                                                                 unit.getMovementWaypointY() - unit.getPositionY())) {
                            this.game.getPathSystem().requirePathPool().release(unit.getPathIndex());
                            unit.setPathIndex(-1);
                        }
                    } else if (this.game.getMovementCollision().isBlockedByTerrain(unit, context.getPositionX(), context.getPositionY())) {
                        unit.setMovementState(MovementStates.REPATH);
                    } else {
                        this.commitMotion(context, unit, instructionLimit);
                        unit.setMovementState(MovementStates.FOLLOW_PATH);
                    }

                    return;
                }
                case MovementStates.SLIDE_PREP, MovementStates.SLIDE_FREE, MovementStates.FORCE_MOVE_FREE, MovementStates.FIX_TERRAIN,
                     MovementStates.TERRAIN_SLIDE -> {
                    if (this.game.getUnitSliding().update(context, unit, instructionLimit)) {
                        continue;
                    }

                    return;
                }
                default -> throw Exceptions.newUnsupportedOperationException(
                        "Native movement state{0} is not established: unit={1}, type={2}, phase={3}, target={4},{5}, position={6},{7}",
                        movementState,
                        unit.getIndex(),
                        unit.getUnitId(),
                        this.game.getFrame() == null ? -1 : this.game.getFrame().getPhaseIndex(),
                        unit.getMovementDestinationX(),
                        unit.getMovementDestinationY(),
                        unit.getPositionX() & WORD_MASK,
                        unit.getPositionY() & WORD_MASK
                );
            }
        }

        throw Exceptions.newIllegalStateException("The native movement transition limit was reached at state{0}", unit.getMovementState());
    }

    /**
     * Runs the FollowPath state: the movement guard, or a failed path update that selects AnotherPath, passes on to the next state; otherwise
     * one motion step is applied, a unit collision is retried at half and quarter steps before FixCollision, a terrain collision alone selects
     * FixTerrain, and a committed step selects AtMoveTarget at the destination or ScoutPath when the path timer runs out.
     *
     * @param context          the movement context
     * @param unit             the moving unit
     * @param instructionLimit the instruction budget of the committed motion
     * @return {@code true} if the next state runs in this update, {@code false} otherwise
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit path workspace, the path pool or the unit pool is not initialized
     * @throws IllegalStateException         if the unit has no sprite
     */
    private boolean updatePathMovement(@NotNull Context context, @NotNull Unit unit, int instructionLimit) {
        if (UnitPath.updateMovementGuard(this.game, unit, true)) {
            return true;
        }

        if (!this.game.getPathSystem().requireUnitPath().update(this.game, unit, packMovementDestination(unit))) {
            unit.setMovementState(MovementStates.ANOTHER_PATH);

            return true;
        }

        int savedSpeed = unit.getNextSpeed();

        FlingyMotionUtils.update(context, unit, this.game.requireGameData().getFlingyProperties());

        if (unit.getMovementControl() == ScrFlingyTypeView.MOVEMENT_CONTROL_FLINGY) {
            savedSpeed = unit.getCurrentSpeed();
        }

        int horizontal = (short) context.getPositionX();
        int vertical = (short) context.getPositionY();
        Unit collision = this.game.getMovementCollision().findUnitCollision(unit, horizontal, vertical);
        boolean terrainCollision = this.game.getMovementCollision().isBlockedByTerrain(unit, horizontal, vertical);

        if (collision != null) {
            boolean blocked = terrainCollision ||
                              unit.getVelocityX() < HALF_STEP_MINIMUM_VELOCITY && unit.getVelocityY() < HALF_STEP_MINIMUM_VELOCITY ||
                              context.getSpeed() != unit.getCurrentSpeed();

            if (!blocked) {
                int fixedHorizontal = context.getFixedPositionX();
                int fixedVertical = context.getFixedPositionY();

                context.setFixedPositionX(unit.getFixedPositionX() + unit.getVelocityX() / HALF_STEP_DIVISOR);
                context.setFixedPositionY(unit.getFixedPositionY() + unit.getVelocityY() / HALF_STEP_DIVISOR);
                context.setPositionX(context.getFixedPositionX() >> MOVEMENT_FRACTION_BITS);
                context.setPositionY(context.getFixedPositionY() >> MOVEMENT_FRACTION_BITS);

                if (this.game.getMovementCollision().findAnyUnitCollision(unit, context.getPositionX(), context.getPositionY()) != null) {
                    context.setFixedPositionX(unit.getFixedPositionX() + unit.getVelocityX() / QUARTER_STEP_DIVISOR);
                    context.setFixedPositionY(unit.getFixedPositionY() + unit.getVelocityY() / QUARTER_STEP_DIVISOR);
                    context.setPositionX(context.getFixedPositionX() >> MOVEMENT_FRACTION_BITS);
                    context.setPositionY(context.getFixedPositionY() >> MOVEMENT_FRACTION_BITS);

                    if (this.game.getMovementCollision().findAnyUnitCollision(unit, context.getPositionX(), context.getPositionY()) != null) {
                        context.setFixedPositionX(fixedHorizontal);
                        context.setFixedPositionY(fixedVertical);
                        context.setPositionX(horizontal);
                        context.setPositionY(vertical);

                        blocked = true;
                    }
                }
            }

            if (blocked) {
                this.game.getPathSystem().requirePathPool().setLastCollisionHandle(
                        unit.getPathIndex(),
                        this.game.getUnitSystem().requireUnitPool().getHandle(collision.getIndex())
                );
                this.game.getPathSystem().requirePathPool().setSavedSpeed(unit.getPathIndex(), savedSpeed);
                unit.setMovementState(MovementStates.FIX_COLLISION);

                return true;
            }

            horizontal = (short) context.getPositionX();
            vertical   = (short) context.getPositionY();
        } else if (terrainCollision) {
            this.game.getPathSystem().requirePathPool().setSavedSpeed(unit.getPathIndex(), savedSpeed);
            unit.setMovementState(MovementStates.FIX_TERRAIN);

            return true;
        }

        Sprite sprite = unit.requireSprite();

        if (horizontal != (short) sprite.getX() || vertical != (short) sprite.getY()) {
            int remaining = unit.getPathRecalculationCountdown();

            if (remaining > MOVING_PATH_COUNTDOWN) {
                unit.setPathRecalculationCountdown(MOVING_PATH_COUNTDOWN);
            } else if (remaining != 0) {
                unit.setPathRecalculationCountdown(remaining - 1);
            }
        }

        this.commitMotion(context, unit, instructionLimit);

        if (isSpriteAtMovementDestination(unit, sprite)) {
            unit.setMovementState(MovementStates.AT_MOVE_TARGET);
        } else if (UnitPath.updateTimer(this.game, unit)) {
            UnitPath.setTimer(this.game, unit, SCOUT_PATH_TIMER);
            unit.setMovementState(MovementStates.SCOUT_PATH);
        }

        return false;
    }

    /**
     * Discards a blocked turn or start from rest: the speed is cleared, the post-movement flags and heading are applied, the walking transition
     * animation runs, and the move target collapses onto the current position before the unit stops.
     *
     * @param context          the movement context with the discarded motion
     * @param unit             the blocked unit
     * @param sprite           the sprite of the unit
     * @param instructionLimit the instruction budget of the walking transition animation
     */
    private void rollBackStationaryMotion(@NotNull Context context, @NotNull Unit unit, @NotNull Sprite sprite, int instructionLimit) {
        unit.setNextSpeed(0);
        FlingyMotionUtils.setSpeed(unit, 0);
        unit.setMovementFlags(context.getPostMovementFlags());
        unit.setTargetHeading(unit.getMovementDirection());
        FlingyMotionUtils.updateFacing(unit, this.game.requireGameData().getFlingyProperties());
        IscriptExecutor.applySpriteDirection(this.game, sprite, unit.getHeading());

        if (context.getStoppingMovement() != 0 || context.getStartingMovement() != 0) {
            this.runWalkingAnimation(context, unit, sprite, instructionLimit);
        }

        int positionX = sprite.getX();
        int positionY = sprite.getY();

        if (!isSpriteAtMovementDestination(unit, sprite)) {
            unit.setMovementDestinationX(positionX);
            unit.setMovementDestinationY(positionY);
            unit.setMovementTargetUnit(null);
            unit.setMovementWaypointX(positionX);
            unit.setMovementWaypointY(positionY);
            unit.setMovementFlags(unit.getMovementFlags() & ~MovementFlags.BRAKING | MovementFlags.ORDERED);
        }

        this.game.getUnitPointMovement().stop(unit);
    }

    /**
     * Updates the unit heading from its movement direction and applies the image headings.
     *
     * @param unit the turning unit
     * @throws UnsupportedOperationException if the unit has no sprite
     */
    private void updateHeading(@NotNull Unit unit) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Native heading updates require the retained sprite.");
        }

        unit.setTargetHeading(unit.getMovementDirection());
        FlingyMotionUtils.updateFacing(unit, this.game.requireGameData().getFlingyProperties());
        IscriptExecutor.applySpriteDirection(this.game, sprite, unit.getHeading());
    }

    /**
     * Commits the prospective motion of the context to the unit: position, exact position, flags and heading, moves the sprite and the spatial index
     * entry, runs the walking transition animation, and marks the movement result when the unit changes its tile.
     *
     * @param context          the movement context with the prospective motion
     * @param unit             the moving unit
     * @param instructionLimit the instruction budget of the walking transition animation
     * @throws UnsupportedOperationException if the motion starts or stops the walking animation without a positive budget, or the sprite pool or the unit spatial index is not initialized
     * @throws IllegalStateException         if the unit has no sprite
     */
    void commitMotion(@NotNull Context context, @NotNull Unit unit, int instructionLimit) {
        int previousX = unit.getPositionX();
        int previousY = unit.getPositionY();
        int horizontal = (short) context.getPositionX();
        int vertical = (short) context.getPositionY();

        context.setPreviousPositionX(previousX);
        context.setPreviousPositionY(previousY);
        unit.setTargetHeading(unit.getMovementDirection());
        unit.setMovementFlags(context.getPostMovementFlags());
        unit.setNextSpeed(unit.getCurrentSpeed());
        unit.setPositionX(horizontal);
        unit.setPositionY(vertical);
        unit.setFixedPositionX(context.getFixedPositionX());
        unit.setFixedPositionY(context.getFixedPositionY());

        Sprite sprite = unit.requireSprite();

        this.game.getSpriteSystem().requireSpritePool().relocate(sprite, horizontal, vertical, this.game.getMap().getHeight());
        FlingyMotionUtils.updateFacing(unit, this.game.requireGameData().getFlingyProperties());
        IscriptExecutor.applySpriteDirection(this.game, sprite, unit.getHeading());

        if (context.getStoppingMovement() != 0 || context.getStartingMovement() != 0) {
            if (instructionLimit <= 0) {
                throw Exceptions.newUnsupportedOperationException("Native movement animation requires an explicit instruction budget.");
            }

            this.runWalkingAnimation(context, unit, sprite, instructionLimit);
        }

        if (horizontal != previousX || vertical != previousY) {
            this.game.getUnitSystem().requireUnitSpatialIndex().relocate(unit, horizontal - previousX, vertical - previousY);

            if (horizontal / TerrainTileset.TILE_SIZE != previousX / TerrainTileset.TILE_SIZE ||
                vertical / TerrainTileset.TILE_SIZE != previousY / TerrainTileset.TILE_SIZE) {
                context.result = 1;
            }
        }
    }

    private void runWalkingAnimation(@NotNull Context context, @NotNull Unit unit, @NotNull Sprite sprite, int instructionLimit) {
        boolean stopping = context.getStoppingMovement() != 0;

        this.game.getIscriptExecutor().runSpriteAnimation(sprite,
                                                          unit,
                                                          stopping ? IscriptExecutor.ANIMATION_WALKING_TO_IDLE : IscriptExecutor.ANIMATION_WALKING,
                                                          stopping ? 0 : IscriptExecutor.FORCE_ANIMATION,
                                                          instructionLimit);
    }

    /**
     * Classifies the unit into its first real movement state (the Init state), after clearing the terrain collision rectangle and refreshing the
     * ground elevation path flag.
     *
     * @param unit the unit to classify
     * @throws UnsupportedOperationException if the unit has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    public void initializeState(@NotNull Unit unit) {
        unit.setTerrainCollisionLeft(0);
        unit.setTerrainCollisionTop(0);
        unit.setTerrainCollisionRight(0);
        unit.setTerrainCollisionBottom(0);

        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Native initial movement classification requires its retained sprite.");
        }

        int pathCollisionFlags = (unit.getPathCollisionFlags() & INITIAL_PATH_FLAGS_KEPT) |
                                 (sprite.getElevationLevel() < Sprite.AIR_ELEVATION_LEVEL ? UnitInitializer.GROUND_ELEVATION_PATH_FLAG : 0);

        unit.setPathCollisionFlags(pathCollisionFlags);

        int status = unit.getStatus();
        boolean subunit = (this.game.requireGameData().getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0;
        int nextState;

        if (!subunit && (status & UnitStatusFlags.ISCRIPT_NO_BREAK) != 0) {
            nextState = MovementStates.INIT_SEQUENCE;
        } else if (unit.isDestroyed()) {
            nextState = MovementStates.LUMP;
        } else if ((status & UnitStatusFlags.IN_BUNKER) != 0) {
            nextState = MovementStates.BUNKER;
        } else if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) != 0) {
            // The moving-hidden-unit motion step is applied by the caller before this classification.
            nextState = MovementStates.HIDDEN;
        } else if ((status & UnitStatusFlags.BURROWED) != 0) {
            nextState = MovementStates.LUMP;
        } else if ((status & UnitStatusFlags.CAN_MOVE) != 0) {
            nextState = (pathCollisionFlags & UnitInitializer.GROUND_ELEVATION_PATH_FLAG) != 0 ? MovementStates.AT_REST : MovementStates.FLYER;
        } else if ((status & UnitStatusFlags.CAN_TURN) != 0) {
            nextState = subunit ? MovementStates.TURRET : MovementStates.BUILDING_TURRET;
        } else if ((pathCollisionFlags & UnitInitializer.GROUND_ELEVATION_PATH_FLAG) == 0) {
            nextState = MovementStates.LUMP;
        } else if ((unit.getMovementFlags() & MovementFlags.ACCELERATING) != 0 || !isSpriteAtMovementDestination(unit, sprite)) {
            nextState = MovementStates.LUMP_WANNABE;
        } else {
            nextState = MovementStates.LUMP;
        }

        unit.setMovementState(nextState);
    }

    /**
     * Runs the Flyer state: a movement destination outside the map is clamped into it and becomes an ordered waypoint of an immovable unit
     * without a movement target unit, one motion step with flying separation is applied, the position is clamped into the map, the motion is
     * committed and the unit finder cell of the flying unit is updated.
     *
     * @param context          the movement context
     * @param unit             the flying unit
     * @param instructionLimit the instruction budget of the committed motion
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit spatial index is not initialized
     */
    private void updateFlyingState(@NotNull Context context, @NotNull Unit unit, int instructionLimit) {
        int unitType = unit.getUnitId();
        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        int mapWidth = this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE;
        int mapHeight = this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE;
        int targetX = UnitPositioning.clampCoordinate(unit.getMovementDestinationX(),
                                                      unitTypes.getDimensionLeft(unitType), unitTypes.getDimensionRight(unitType),
                                                      mapWidth,
                                                      0);
        int targetY = UnitPositioning.clampCoordinate(unit.getMovementDestinationY(),
                                                      unitTypes.getDimensionUp(unitType), unitTypes.getDimensionDown(unitType),
                                                      mapHeight,
                                                      0);

        if (targetX != unit.getMovementDestinationX() || targetY != unit.getMovementDestinationY()) {
            unit.setMovementDestinationX(targetX);
            unit.setMovementDestinationY(targetY);
            unit.setMovementWaypointX(targetX);
            unit.setMovementWaypointY(targetY);
            unit.setMovementFlags(unit.getMovementFlags() & ~MovementFlags.BRAKING | MovementFlags.ORDERED);
            unit.setMovementTargetUnit(null);
            unit.setStatus(unit.getStatus() | UnitStatusFlags.IMMOVABLE);
        }

        FlingyMotionUtils.update(context, unit, this.game.requireGameData().getFlingyProperties());

        boolean displaced = this.game.getUnitSystem().requireUnitSpatialIndex().applyFlyingSeparation(context, this.game, unit);

        context.setPositionX(UnitPositioning.clampCoordinate(context.getPositionX(),
                                                             unitTypes.getDimensionLeft(unitType), unitTypes.getDimensionRight(unitType),
                                                             mapWidth,
                                                             0));
        context.setPositionY(UnitPositioning.clampCoordinate(context.getPositionY(),
                                                             unitTypes.getDimensionUp(unitType), unitTypes.getDimensionDown(unitType),
                                                             mapHeight,
                                                             0));
        this.commitMotion(context, unit, instructionLimit);
        this.game.getUnitSystem().requireUnitSpatialIndex().updateFlyingUnitCell(unit,
                                                                                 unitTypes,
                                                                                 this.game.getRandom(),
                                                                                 displaced,
                                                                                 context.getPositionX(), context.getPositionY());
    }
}
