package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.engine.state.StateHash;
import org.fogofwarlabs.sc4j.scr.content.ScrNativeIds;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerUnitRecord;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.order.OrderPool;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffect;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Holds one game unit: its identity, position and vitals, status, movement, orders, production, resources, transport
 * and hangar state, status effects and its links to other game objects.
 * <p>
 * The SC:R unit is the engine {@link org.fogofwarlabs.sc4j.engine.entity.Unit} of its slot, so the engine systems and hooks take it as
 * an engine unit. The position is the engine position. The native owner BYTE and unit type WORD stay the stores that EUD and the native
 * tables address ({@link #getOwnerIndex()}, {@link #getUnitId()}); each of their setters also sets the engine owner and type, the
 * game's player of the owner BYTE ({@link UnitPool#getPlayer(int)}) and the SC:R identity of the unit type WORD, so the engine fields
 * always name the native values.
 * <p>
 * A {@link Unit} is one persistent slot of its {@link UnitPool}: the slot outlives the individual units, and a partial initialization keeps the
 * fields it does not touch. Width-specific setters retain the low BYTE or WORD; coordinates are signed and speeds use fixed-point integer arithmetic.
 * Allocation, generation and recycling belong to the owning {@link UnitPool}, an engine entity pool whose slots are the
 * units: the slot index is the entity index ({@link #getIndex()}) and the generation is kept by the pool.
 */
public final class Unit extends org.fogofwarlabs.sc4j.engine.entity.Unit {
    /**
     * The number of build queue slots; the queue index wraps around at this count.
     */
    public static final int BUILD_QUEUE_SLOTS = 5;
    /**
     * The number of loaded-unit handle slots of a transport or Bunker.
     */
    public static final int LOADED_UNIT_SLOTS = 8;
    /**
     * The number of Acid Spore timer slots.
     */
    public static final int ACID_SPORE_SLOTS = 9;
    /**
     * The number of hangar lists of a Carrier or Reaver: inside and outside ({@link #getFirstHangarUnit(boolean)}).
     */
    public static final int HANGAR_LIST_COUNT = 2;
    /**
     * The user action flag a finished order sets when it activates the next queued order; the end of a no-break script
     * section activates the queue again while it is set.
     */
    public static final int USER_ACTION_ORDER_DONE = 0x1;
    /**
     * The user action flag of a unit that received a player command; the movement clears it when it starts the next path
     * (delaying an iscript-controlled unit by a random number of frames) or makes the unit immovable.
     */
    public static final int USER_ACTION_COMMANDED = 0x2;
    /**
     * The user action flag of a unit killed without a visible death: the death routine hides it first.
     */
    public static final int USER_ACTION_HIDE_ON_DEATH = 0x4;
    /**
     * The main order timer, the low byte of the cooldown timers ({@link #getCooldownTimers()}).
     */
    public static final int ORDER_TIMER_MASK = 0xFF;
    /**
     * Adding this to the hit points, which have eight fraction bits, before the shift by {@code 8} rounds them up to whole points.
     */
    public static final int HIT_POINT_ROUNDING = 0xFF;
    /**
     * The position of the ground weapon cooldown byte in the cooldown timers ({@link #getCooldownTimers()}).
     */
    public static final int GROUND_COOLDOWN_SHIFT = 8;
    /**
     * The ground weapon cooldown byte of the cooldown timers.
     */
    public static final int GROUND_COOLDOWN_MASK = 0xFF << GROUND_COOLDOWN_SHIFT;
    /**
     * The position of the air weapon cooldown byte in the cooldown timers.
     */
    public static final int AIR_COOLDOWN_SHIFT = 16;
    /**
     * The air weapon cooldown byte of the cooldown timers.
     */
    public static final int AIR_COOLDOWN_MASK = 0xFF << AIR_COOLDOWN_SHIFT;
    /**
     * The position of the spell cooldown byte in the cooldown timers.
     */
    public static final int SPELL_COOLDOWN_SHIFT = 24;
    /**
     * The spell cooldown byte of the cooldown timers.
     */
    public static final int SPELL_COOLDOWN_MASK = 0xFF << SPELL_COOLDOWN_SHIFT;
    /**
     * The order signal bit ({@link #getOrderSignal()}) a sprite script sets when the scripted action of the order ends: a
     * Scarab or Spider Mine explosion, a door, trap or warp animation, a turret attack.
     */
    public static final int ORDER_SIGNAL_ACTION_DONE = 0x1;
    /**
     * The order signal bit a sprite script sets when a spell cast or a missile launch ends.
     */
    public static final int ORDER_SIGNAL_CAST_DONE = 0x2;
    /**
     * The order signal bit a sprite script sets when a cloak, burrow, morph or merge animation ends.
     */
    public static final int ORDER_SIGNAL_ANIMATION_DONE = 0x4;
    /**
     * The order signal bit a sprite script sets when the lift-off or landing animation of a building ends.
     */
    public static final int ORDER_SIGNAL_LIFT_DONE = 0x10;
    /**
     * The number of extension bits of each loaded-unit handle slot ({@link #getLoadedUnitHandleExtensionBits()}).
     */
    public static final int LOADED_HANDLE_EXTENSION_BITS = 2;
    /**
     * The extension bits of one loaded-unit handle slot, before the shift to its slot; they become handle bits {@code 16} and {@code 17}.
     */
    public static final int LOADED_HANDLE_EXTENSION_MASK = 0x3;
    /**
     * The detection flags of a cloaked unit that no player has detected yet: only the high marker bit is set.
     */
    public static final int DETECTION_FLAGS_UNDETECTED = 0x80000000;
    /**
     * The primary order state ({@link #getPrimaryOrderState()}) of a unit whose Die order has run: the unit is dead,
     * plays its death animation or waits for removal, and commands, selections and searches skip it.
     */
    public static final int DIE_STATE_DESTROYED = 1;

    /**
     * The first build queue slot ({@link #getBuildQueueUnitType(int)}).
     */
    public static final int FIRST_QUEUE_SLOT = 0;
    /**
     * The second build queue slot.
     */
    public static final int SECOND_QUEUE_SLOT = 1;
    /**
     * The third build queue slot.
     */
    public static final int THIRD_QUEUE_SLOT = 2;
    /**
     * The fourth build queue slot.
     */
    public static final int FOURTH_QUEUE_SLOT = 3;
    /**
     * The fifth build queue slot.
     */
    public static final int FIFTH_QUEUE_SLOT = 4;

    /**
     * The larva placement side whose count shares the resource amount WORD.
     */
    public static final int LARVA_SIDE_LEFT = 0;
    /**
     * The upper larva placement side.
     */
    public static final int LARVA_SIDE_UPPER = 1;
    /**
     * The right larva placement side.
     */
    public static final int LARVA_SIDE_RIGHT = 2;
    /**
     * The lower larva placement side.
     */
    public static final int LARVA_SIDE_LOWER = 3;
    /**
     * The bit position of the rank increase byte in {@link #classicEventBytes}.
     */
    private static final int RANK_INCREASE_SHIFT = 16;
    /**
     * The masks of a BYTE and a WORD field.
     */
    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The multiplier of the state hash.
     */
    private static final int STATE_HASH_MULTIPLIER = 31;
    /**
     * The high byte of a WORD.
     */
    private static final int HIGH_BYTE_MASK = 0xFF00;
    /**
     * The lowest speed of any other unit with a speed modifier.
     */
    private static final int MINIMUM_UPGRADED_SPEED = 0x355;

    /**
     * Returns the SC:R unit an engine system or hook was given: the SC:R mod pools its units itself, so every engine unit of an SC:R game is one.
     *
     * @param unit the engine unit
     * @return the same unit as an SC:R unit
     * @throws IllegalArgumentException if the unit is not an SC:R unit
     */
    public static @NotNull Unit of(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit) {
        if (!(unit instanceof Unit scrUnit)) {
            throw Exceptions.newIllegalArgumentException("Not an SC:R unit: {0}", unit);
        }

        return scrUnit;
    }


    private final @NotNull UnitPool pool;
    private int unitId;
    private int owner;
    private int previousUnitType;
    private @Nullable Sprite sprite;
    private @Nullable Unit subunit;
    private @Nullable Unit parentUnit;

    private int shieldPoints;
    private int energy;
    private int previousVisibleHitPoints;
    private int damageOverlayCount;
    private int remainingLifetime;
    private int killCount;
    private int lastAttackingPlayer;
    private int wireframeRandomizer;

    private int status;
    private int userActionFlags;
    private int highlightedOrderCount;
    private int currentButtonSet;
    private int statusCycleElapsed;
    private int visibilityUpdateCountdown;
    private int detectionFlags;
    private int cloakCounter;
    private int parasiteFlags;
    private int blindnessState;

    private int flingyType;
    private int movementState;
    private int speedIncreaseByte;
    private int speedDecreaseByte;

    private @Nullable Unit movementTargetUnit;
    private int movementDestinationCountdown;
    private int pathIndex;
    private int pathRecalculationCountdown;
    private int pathCollisionFlags;
    private int terrainCollisionLeft;
    private int terrainCollisionTop;
    private int terrainCollisionRight;
    private int terrainCollisionBottom;

    private int repulsionFlags;
    private int repulsionDirection;
    private int repulsionCellX;
    private int repulsionCellY;

    private int primaryOrder;
    private int primaryOrderState;
    private int primaryOrderCountdown;
    private int secondaryOrder;
    private int secondaryOrderState;
    /**
     * The secondary-order position DWORD, cleared by the semantic order setter and retained by raw access.
     */
    private int secondaryOrderPosition;
    /**
     * The four classic event bytes; the third one is the rank increase ({@link #getRankIncrease()}).
     */
    private int classicEventBytes;
    /**
     * A byte that no routine reads ({@link #getClassicUnusedByte()}).
     */
    private int classicUnusedByte;
    /**
     * The under-attack area mark ({@link #getAlertMark()}).
     */
    private int alertMark;
    private int orderSignal;
    private int orderTargetUnitType;
    private int rallyPositionX;
    private int rallyPositionY;
    private @Nullable Unit rallyTargetUnit;
    private @Nullable ComputerUnitRecord computerRecord;
    /**
     * The skin WORD saved in the {@code SKIN} trailer section. The game has no skin selections, so a created unit has
     * {@code 0}; a loaded unit keeps its saved skin.
     */
    private int skin;
    /**
     * The local event bytes of the classic unit, which the interface of the saving machine sets and the simulation never
     * reads: a loaded unit keeps the saved bytes, which the next save writes again.
     */
    private int localEventBytes;

    private int cooldownTimers;
    private @Nullable Unit automaticTargetUnit;
    private int airStrength;
    private int groundStrength;
    private int nextHitNearTargetIndex;
    /**
     * The computer spell bits set on a target by a computer caster and cleared by the target's own order update.
     */
    private int spellTargetFlags;

    private int buildQueueIndex;
    private int firstBuildQueueType;
    private int secondBuildQueueType;
    private int thirdBuildQueueType;
    private int fourthBuildQueueType;
    private int fifthBuildQueueType;
    private @Nullable Unit currentBuildUnit;
    private int remainingBuildTime;
    private int constructionHitPointIncrement;
    private int constructionShieldIncrement;
    private @Nullable Unit addon;
    private int healingState;


    private @Nullable Unit firstGatheringWorker;
    private @Nullable Unit lastGatheringWorker;
    private @Nullable Unit previousGatheringWorker;
    private @Nullable Unit nextGatheringWorker;
    private @Nullable Unit targetResourceUnit;
    private @Nullable Unit gatherTarget;
    private int carryingFlags;
    private @Nullable Unit carriedPowerup;
    private @Nullable Unit powerupCarrier;

    private final short @NotNull [] loadedUnitHandles;
    private short loadedUnitHandleExtensionBits;
    private @Nullable Unit hangarParent;
    private @Nullable Unit firstInsideHangarUnit;
    private @Nullable Unit firstOutsideHangarUnit;
    private @Nullable Unit previousHangarUnit;
    private @Nullable Unit nextHangarUnit;
    /**
     * The WORD after the unit type that only the save conversions and EUD touch; only the pool initialization clears it,
     * so it survives the slot's later units.
     */
    private int spareWord;

    private short defensiveMatrixHealth;
    private int defensiveMatrixTimer;
    private int lockdownTimer;
    private int irradiateTimer;
    private @Nullable Unit irradiatedBy;
    private int irradiateOwner;
    private int stasisTimer;
    private int plagueTimer;
    private int stormTimer;
    private int maelstromTimer;
    private int acidSporeCount;
    private final byte @NotNull [] acidSporeTimers;

    private @Nullable Unit previousCloakedUnit;
    private @Nullable Unit nextCloakedUnit;
    private boolean cloakedLinked;

    private @Nullable Unit previousPsiProvider;
    private @Nullable Unit nextPsiProvider;
    private @Nullable Sprite psiFieldSprite;
    private @Nullable Unit siloNuke;
    private @Nullable SpriteEffect nukeDot;
    private @Nullable Unit nydusExit;

    /**
     * Creates a {@link Unit} instance for one persistent slot of its pool.
     *
     * @param pool  the owning pool, which creates every slot
     * @param index the positive slot index assigned by the owning pool
     * @throws IllegalArgumentException if the slot index is less than {@code 1}
     */
    Unit(@NotNull UnitPool pool, int index) {
        if (index < 1) {
            throw Exceptions.newIllegalArgumentException("Unit pool index must be positive: {0}", index);
        }

        super(index);

        this.pool = pool;
        this.setUnitId(0);
        this.setOwnerIndex(0);
        this.previousUnitType = 0;
        this.sprite           = null;
        this.subunit          = null;
        this.parentUnit       = null;

        this.shieldPoints             = 0;
        this.energy                   = 0;
        this.previousVisibleHitPoints = 0;
        this.damageOverlayCount       = 0;
        this.remainingLifetime        = 0;
        this.killCount                = 0;
        this.lastAttackingPlayer      = 0;
        this.wireframeRandomizer      = 0;

        this.status                    = 0;
        this.userActionFlags           = 0;
        this.highlightedOrderCount     = 0;
        this.currentButtonSet          = 0;
        this.statusCycleElapsed        = 0;
        this.visibilityUpdateCountdown = 0;
        this.detectionFlags            = 0;
        this.cloakCounter              = 0;
        this.parasiteFlags             = 0;
        this.blindnessState            = 0;

        this.flingyType        = 0;
        this.movementState     = 0;
        this.speedIncreaseByte = 0;
        this.speedDecreaseByte = 0;

        this.movementTargetUnit           = null;
        this.movementDestinationCountdown = 0;
        this.pathIndex                    = -1;
        this.pathRecalculationCountdown   = 0;
        this.pathCollisionFlags           = 0;
        this.terrainCollisionLeft         = 0;
        this.terrainCollisionTop          = 0;
        this.terrainCollisionRight        = 0;
        this.terrainCollisionBottom       = 0;

        this.repulsionFlags     = 0;
        this.repulsionDirection = 0;
        this.repulsionCellX     = 0;
        this.repulsionCellY     = 0;

        this.primaryOrder           = 0;
        this.primaryOrderState      = 0;
        this.primaryOrderCountdown  = 0;
        this.secondaryOrder         = 0;
        this.secondaryOrderState    = 0;
        this.secondaryOrderPosition = 0;
        this.classicEventBytes      = 0;
        this.classicUnusedByte      = 0;
        this.alertMark              = 0;
        this.orderSignal            = 0;
        this.orderTargetUnitType    = 0;
        this.rallyPositionX         = 0;
        this.rallyPositionY         = 0;
        this.rallyTargetUnit        = null;
        this.computerRecord         = null;
        this.skin                   = 0;
        this.localEventBytes        = 0;

        this.setOrderType(ScrNativeIds.orderOf(this.primaryOrder));

        this.cooldownTimers         = 0;
        this.automaticTargetUnit    = null;
        this.airStrength            = 0;
        this.groundStrength         = 0;
        this.nextHitNearTargetIndex = 0;
        this.spellTargetFlags       = 0;

        this.buildQueueIndex               = 0;
        this.firstBuildQueueType           = 0;
        this.secondBuildQueueType          = 0;
        this.thirdBuildQueueType           = 0;
        this.fourthBuildQueueType          = 0;
        this.fifthBuildQueueType           = 0;
        this.currentBuildUnit              = null;
        this.remainingBuildTime            = 0;
        this.constructionHitPointIncrement = 0;
        this.constructionShieldIncrement   = 0;
        this.addon                         = null;
        this.healingState                  = 0;

        this.firstGatheringWorker    = null;
        this.lastGatheringWorker     = null;
        this.previousGatheringWorker = null;
        this.nextGatheringWorker     = null;
        this.targetResourceUnit      = null;
        this.gatherTarget            = null;
        this.carryingFlags           = 0;
        this.carriedPowerup          = null;
        this.powerupCarrier          = null;

        this.loadedUnitHandles             = new short[LOADED_UNIT_SLOTS];
        this.loadedUnitHandleExtensionBits = 0;
        this.hangarParent                  = null;
        this.firstInsideHangarUnit         = null;
        this.firstOutsideHangarUnit        = null;
        this.previousHangarUnit            = null;
        this.nextHangarUnit                = null;
        this.spareWord                     = 0;

        this.defensiveMatrixHealth = 0;
        this.defensiveMatrixTimer  = 0;
        this.lockdownTimer         = 0;
        this.irradiateTimer        = 0;
        this.irradiatedBy          = null;
        this.irradiateOwner        = 0;
        this.stasisTimer           = 0;
        this.plagueTimer           = 0;
        this.stormTimer            = 0;
        this.maelstromTimer        = 0;
        this.acidSporeCount        = 0;
        this.acidSporeTimers       = new byte[ACID_SPORE_SLOTS];

        this.previousCloakedUnit = null;
        this.nextCloakedUnit     = null;
        this.cloakedLinked       = false;

        this.previousPsiProvider = null;
        this.nextPsiProvider     = null;
        this.psiFieldSprite      = null;
        this.siloNuke            = null;
        this.nukeDot             = null;
        this.nydusExit           = null;
    }

    /**
     * @return the slot's generation BYTE, which the owning {@link UnitPool} advances and replaces
     */
    int getGeneration() {
        return this.pool.getGeneration(this.getIndex());
    }

    /**
     * Sets the slot's generation BYTE.
     *
     * @param value the generation, truncated to a BYTE
     */
    void setGeneration(int value) {
        this.pool.setGeneration(this.getIndex(), value);
    }

    /**
     * @return the native unit type WORD, the units.dat entry of the unit type or any value EUD wrote
     */
    public int getUnitId() {
        return this.unitId;
    }

    /**
     * Sets the native unit type WORD and the engine unit type: the SC:R identity of the entry, or {@code null} for a value outside
     * units.dat.
     *
     * @param value the unit type, truncated to a WORD
     */
    public void setUnitId(int value) {
        this.unitId = value & WORD_MASK;

        this.setType(ScrNativeIds.unitOf(this.unitId));
    }

    /**
     * @return the native owner BYTE: the player slot of the owner, or any value EUD wrote
     */
    public int getOwnerIndex() {
        return this.owner;
    }

    /**
     * Sets the native owner BYTE and the engine owner: the game's player of the slot, or {@code null} for a BYTE the game has no player
     * for ({@link UnitPool#getPlayer(int)}).
     *
     * @param value the owner player slot, truncated to a BYTE
     */
    public void setOwnerIndex(int value) {
        this.owner = value & BYTE_MASK;

        this.setOwner(this.pool.getPlayer(this.owner));
    }

    /**
     * @return the unit type before the last morph or transformation
     */
    public int getPreviousUnitType() {
        return this.previousUnitType;
    }

    /**
     * Sets the unit type before the last morph or transformation.
     *
     * @param value the previous unit type ID
     */
    public void setPreviousUnitType(int value) {
        this.previousUnitType = value;
    }

    /**
     * @return the unit's sprite, or {@code null} if it has none
     */
    public @Nullable Sprite getSpriteOrNull() {
        return this.sprite;
    }

    /**
     * Returns the sprite of a unit that must have one.
     *
     * @return the unit's sprite
     * @throws IllegalStateException if the unit has no sprite
     */
    public @NotNull Sprite requireSprite() {
        Sprite sprite = this.sprite;

        if (sprite == null) {
            throw Exceptions.newIllegalStateException("Unit {0} has no sprite.", this.getIndex());
        }

        return sprite;
    }

    /**
     * Sets the unit's sprite.
     *
     * @param sprite the sprite, or {@code null}
     */
    public void setSprite(@Nullable Sprite sprite) {
        this.sprite = sprite;
    }

    /**
     * @return the subunit (for example a turret), or {@code null}
     */
    public @Nullable Unit getSubunit() {
        return this.subunit;
    }

    /**
     * Sets the subunit.
     *
     * @param subunit the subunit, or {@code null}
     */
    public void setSubunit(@Nullable Unit subunit) {
        this.subunit = subunit;
    }

    /**
     * @return the parent unit of a subunit, or {@code null}
     */
    public @Nullable Unit getParentUnit() {
        return this.parentUnit;
    }

    /**
     * Returns the parent unit of a unit that must have one, such as the Bunker of a unit inside it.
     *
     * @return the parent unit
     * @throws IllegalStateException if the unit has no parent unit
     */
    public @NotNull Unit requireParentUnit() {
        Unit parentUnit = this.parentUnit;

        if (parentUnit == null) {
            throw Exceptions.newIllegalStateException("Unit {0} has no parent unit.", this.getIndex());
        }

        return parentUnit;
    }

    /**
     * Sets the parent unit of a subunit.
     *
     * @param parentUnit the parent unit, or {@code null}
     */
    public void setParentUnit(@Nullable Unit parentUnit) {
        this.parentUnit = parentUnit;
    }


    /**
     * Sets the x coordinate of the engine position.
     *
     * @param value the x coordinate in pixels, truncated to a signed WORD
     */
    public void setPositionX(int value) {
        this.setPosition((short) value, this.getPositionY());
    }

    /**
     * Sets the y coordinate of the engine position.
     *
     * @param value the y coordinate in pixels, truncated to a signed WORD
     */
    public void setPositionY(int value) {
        this.setPosition(this.getPositionX(), (short) value);
    }

    /**
     * Sets the movement control, narrowed to its native BYTE.
     *
     * @param movementControl the movement control, truncated to eight bits
     */
    @Override
    public void setMovementControl(int movementControl) {
        super.setMovementControl(movementControl & BYTE_MASK);
    }

    /**
     * Sets the movement flags, narrowed to its native BYTE.
     *
     * @param movementFlags the movement flags, truncated to eight bits
     */
    @Override
    public void setMovementFlags(int movementFlags) {
        super.setMovementFlags(movementFlags & BYTE_MASK);
    }

    /**
     * Sets the direction the object faces, narrowed to its native BYTE.
     *
     * @param heading the direction the object faces, truncated to eight bits
     */
    @Override
    public void setHeading(int heading) {
        super.setHeading(heading & BYTE_MASK);
    }

    /**
     * Sets the direction the object turns to, narrowed to its native BYTE.
     *
     * @param targetHeading the direction the object turns to, truncated to eight bits
     */
    @Override
    public void setTargetHeading(int targetHeading) {
        super.setTargetHeading(targetHeading & BYTE_MASK);
    }

    /**
     * Sets the turn rate, narrowed to its native BYTE.
     *
     * @param turnRate the turn rate, truncated to eight bits
     */
    @Override
    public void setTurnRate(int turnRate) {
        super.setTurnRate(turnRate & BYTE_MASK);
    }

    /**
     * Sets the direction the object moves in, narrowed to its native BYTE.
     *
     * @param movementDirection the direction the object moves in, truncated to eight bits
     */
    @Override
    public void setMovementDirection(int movementDirection) {
        super.setMovementDirection(movementDirection & BYTE_MASK);
    }

    /**
     * Sets the direction toward the current waypoint, narrowed to its native BYTE.
     *
     * @param desiredMovementDirection the direction toward the current waypoint, truncated to eight bits
     */
    @Override
    public void setDesiredMovementDirection(int desiredMovementDirection) {
        super.setDesiredMovementDirection(desiredMovementDirection & BYTE_MASK);
    }

    /**
     * Sets the acceleration per frame, narrowed to its native WORD.
     *
     * @param acceleration the acceleration per frame, truncated to sixteen bits
     */
    @Override
    public void setAcceleration(int acceleration) {
        super.setAcceleration(acceleration & WORD_MASK);
    }

    /**
     * Sets the horizontal map position of the movement destination, narrowed to its native WORD.
     *
     * @param movementDestinationX the horizontal map position of the movement destination, truncated to sixteen bits
     */
    @Override
    public void setMovementDestinationX(int movementDestinationX) {
        super.setMovementDestinationX(movementDestinationX & WORD_MASK);
    }

    /**
     * Sets the vertical map position of the movement destination, narrowed to its native WORD.
     *
     * @param movementDestinationY the vertical map position of the movement destination, truncated to sixteen bits
     */
    @Override
    public void setMovementDestinationY(int movementDestinationY) {
        super.setMovementDestinationY(movementDestinationY & WORD_MASK);
    }

    /**
     * Sets the horizontal map position of the current waypoint, narrowed to its native WORD.
     *
     * @param movementWaypointX the horizontal map position of the current waypoint, truncated to sixteen bits
     */
    @Override
    public void setMovementWaypointX(int movementWaypointX) {
        super.setMovementWaypointX(movementWaypointX & WORD_MASK);
    }

    /**
     * Sets the vertical map position of the current waypoint, narrowed to its native WORD.
     *
     * @param movementWaypointY the vertical map position of the current waypoint, truncated to sixteen bits
     */
    @Override
    public void setMovementWaypointY(int movementWaypointY) {
        super.setMovementWaypointY(movementWaypointY & WORD_MASK);
    }

    /**
     * Sets the horizontal map position the object turns to when it stands on its waypoint, narrowed to its native WORD.
     *
     * @param directionTargetX the horizontal map position the object turns to when it stands on its waypoint, truncated to sixteen bits
     */
    @Override
    public void setDirectionTargetX(int directionTargetX) {
        super.setDirectionTargetX(directionTargetX & WORD_MASK);
    }

    /**
     * Sets the vertical map position the object turns to when it stands on its waypoint, narrowed to its native WORD.
     *
     * @param directionTargetY the vertical map position the object turns to when it stands on its waypoint, truncated to sixteen bits
     */
    @Override
    public void setDirectionTargetY(int directionTargetY) {
        super.setDirectionTargetY(directionTargetY & WORD_MASK);
    }

    /**
     * Sets the horizontal map position of the order target, narrowed to its native WORD.
     *
     * @param orderTargetX the horizontal map position of the order target, truncated to sixteen bits
     */
    @Override
    public void setOrderTargetX(int orderTargetX) {
        super.setOrderTargetX(orderTargetX & WORD_MASK);
    }

    /**
     * Sets the vertical map position of the order target, narrowed to its native WORD.
     *
     * @param orderTargetY the vertical map position of the order target, truncated to sixteen bits
     */
    @Override
    public void setOrderTargetY(int orderTargetY) {
        super.setOrderTargetY(orderTargetY & WORD_MASK);
    }

    /**
     * @return the shield points DWORD in units of {@code 1/256}, the points of the shield damage layer ({@link org.fogofwarlabs.sc4j.scr.game.combat.ScrShieldLayer})
     */
    public int getShieldPoints() {
        return this.shieldPoints;
    }

    /**
     * Sets the shield points DWORD, keeping all {@code 32} bits.
     *
     * @param shieldPoints the shield points in units of {@code 1/256}
     */
    public void setShieldPoints(int shieldPoints) {
        this.shieldPoints = shieldPoints;
    }

    /**
     * @return the unsigned 16-bit energy value in units of {@code 1/256}, from {@code 0} through {@code 65535}
     */
    public int getEnergy() {
        return this.energy;
    }

    /**
     * Sets the low {@code 16} bits as energy in units of {@code 1/256} without clamping to the unit's maximum.
     * <p>
     * This assignment does not redraw images, spend energy or run ability effects.
     *
     * @param value the energy value narrowed to an unsigned 16-bit word
     */
    public void setEnergy(int value) {
        this.energy = value & WORD_MASK;
    }

    /**
     * Returns the previous hit points WORD: {@code 1} from the storage initializer, the previous hit points (at least {@code 1}) once the unit
     * morphs, which a canceled Zerg building restores to its Drone.
     *
     * @return the previous hit points
     */
    public int getPreviousVisibleHitPoints() {
        return this.previousVisibleHitPoints;
    }

    /**
     * Sets the previous hit points.
     *
     * @param value the previous hit points, truncated to a WORD
     */
    public void setPreviousVisibleHitPoints(int value) {
        this.previousVisibleHitPoints = value & WORD_MASK;
    }

    /**
     * @return the number of damage overlays shown on the unit
     */
    public int getDamageOverlayCount() {
        return this.damageOverlayCount;
    }

    /**
     * Sets the number of damage overlays shown on the unit.
     *
     * @param value the overlay count, truncated to a BYTE
     */
    public void setDamageOverlayCount(int value) {
        this.damageOverlayCount = value & BYTE_MASK;
    }

    /**
     * @return the unsigned 16-bit removal timer, {@code 0} when the unit is not timed
     */
    public int getRemainingLifetime() {
        return this.remainingLifetime;
    }

    /**
     * Sets the removal timer.
     *
     * @param value the remaining frames, truncated to a WORD
     */
    public void setRemainingLifetime(int value) {
        this.remainingLifetime = value & WORD_MASK;
    }

    /**
     * @return the retained kill count integer; excluded from the state hash
     */
    public int getKillCount() {
        return this.killCount;
    }

    /**
     * Sets the kill count.
     *
     * @param value the kill count integer, retained without narrowing
     */
    public void setKillCount(int value) {
        this.killCount = value;
    }

    /**
     * @return the player that last attacked the unit
     */
    public int getLastAttackingPlayer() {
        return this.lastAttackingPlayer;
    }

    /**
     * Sets the player that last attacked the unit.
     *
     * @param value the player ID, truncated to a BYTE
     */
    public void setLastAttackingPlayer(int value) {
        this.lastAttackingPlayer = value & BYTE_MASK;
    }

    /**
     * @return the wireframe randomizer that permutes the damage colors of the wireframe parts
     */
    public int getWireframeRandomizer() {
        return this.wireframeRandomizer;
    }

    /**
     * Sets the wireframe randomizer.
     *
     * @param value the randomizer, truncated to a BYTE
     */
    public void setWireframeRandomizer(int value) {
        this.wireframeRandomizer = value & BYTE_MASK;
    }


    /**
     * @return the unit status flags ({@link UnitStatusFlags}), distinct from the CHK flags
     */
    public int getStatus() {
        return this.status;
    }

    /**
     * Sets all {@code 32} status flag bits without validating their combination.
     * <p>
     * This assignment does not update movement, visibility or accounting, and does not retire the unit.
     *
     * @param value the complete status flag word
     */
    public void setStatus(int value) {
        this.status = value;
    }

    /**
     * @return the user action flags (for example {@link #USER_ACTION_HIDE_ON_DEATH})
     */
    public int getUserActionFlags() {
        return this.userActionFlags;
    }

    /**
     * Sets the user action flags.
     *
     * @param value the flags, truncated to a BYTE
     */
    public void setUserActionFlags(int value) {
        this.userActionFlags = value & BYTE_MASK;
    }

    /**
     * @return the number of queued orders shown with a highlighted target
     */
    public int getHighlightedOrderCount() {
        return this.highlightedOrderCount;
    }

    /**
     * Sets the number of queued orders shown with a highlighted target.
     *
     * @param value the count, truncated to a BYTE
     */
    public void setHighlightedOrderCount(int value) {
        this.highlightedOrderCount = value & BYTE_MASK;
    }

    /**
     * @return the unsigned button-set identifier, including the synthetic construction sets {@code 231} and {@code 232}
     */
    public int getCurrentButtonSet() {
        return this.currentButtonSet;
    }

    /**
     * Sets the current command-panel button set without changing the unit type.
     *
     * @param value the identifier truncated to its original unsigned WORD
     */
    public void setCurrentButtonSet(int value) {
        this.currentButtonSet = value & WORD_MASK;
    }

    /**
     * @return the frames elapsed in the current status timer cycle
     */
    public int getStatusCycleElapsed() {
        return this.statusCycleElapsed;
    }

    /**
     * Sets the frames elapsed in the current status timer cycle.
     *
     * @param value the elapsed frames, truncated to a BYTE
     */
    public void setStatusCycleElapsed(int value) {
        this.statusCycleElapsed = value & BYTE_MASK;
    }

    /**
     * @return the frames until the next visibility update of the unit
     */
    public int getVisibilityUpdateCountdown() {
        return this.visibilityUpdateCountdown;
    }

    /**
     * Sets the frames until the next visibility update of the unit.
     *
     * @param value the countdown, truncated to a BYTE
     */
    public void setVisibilityUpdateCountdown(int value) {
        this.visibilityUpdateCountdown = value & BYTE_MASK;
    }

    /**
     * @return the players that detect this cloaked unit; the minimap draws a cloaked unit only for them
     */
    public int getDetectionFlags() {
        return this.detectionFlags;
    }

    /**
     * Sets the players that detect this cloaked unit.
     *
     * @param value the player bit set
     */
    public void setDetectionFlags(int value) {
        this.detectionFlags = value;
    }

    /**
     * @return the cloaking counter
     */
    public int getCloakCounter() {
        return this.cloakCounter;
    }

    /**
     * Sets the cloaking counter.
     *
     * @param value the counter, truncated to a BYTE
     */
    public void setCloakCounter(int value) {
        this.cloakCounter = value & BYTE_MASK;
    }

    /**
     * @return the players whose Parasite is on the unit
     */
    public int getParasiteFlags() {
        return this.parasiteFlags;
    }

    /**
     * Sets the players whose Parasite is on the unit.
     *
     * @param value the player bit set, truncated to a BYTE
     */
    public void setParasiteFlags(int value) {
        this.parasiteFlags = value & BYTE_MASK;
    }

    /**
     * @return the blindness state, shown as "Blind"
     */
    public int getBlindnessState() {
        return this.blindnessState;
    }

    /**
     * Sets the blindness state.
     *
     * @param value the state, truncated to a BYTE
     */
    public void setBlindnessState(int value) {
        this.blindnessState = value & BYTE_MASK;
    }


    /**
     * @return the flingy type that drives the unit's movement
     */
    public int getFlingyType() {
        return this.flingyType;
    }

    /**
     * Sets the flingy type, and the engine motion profile of the entry ({@link #getMotionProfile()}, {@code null} outside {@code flingy.dat}).
     *
     * @param value the flingy type ID, truncated to a WORD
     */
    public void setFlingyType(int value) {
        this.flingyType = value & WORD_MASK;

        this.setMotionProfile(ScrNativeIds.flingyOf(this.flingyType));
    }

    /**
     * @return the movement state byte
     */
    public int getMovementState() {
        return this.movementState;
    }

    /**
     * Sets the movement state.
     *
     * @param value the movement state, truncated to a BYTE
     */
    public void setMovementState(int value) {
        this.movementState = value & BYTE_MASK;
    }

    /**
     * @return the unsigned speed increase byte (nonzero under a speed bonus)
     */
    public int getSpeedIncreaseByte() {
        return this.speedIncreaseByte;
    }

    /**
     * Sets the speed increase byte.
     *
     * @param value the byte, truncated to a BYTE
     */
    public void setSpeedIncreaseByte(int value) {
        this.speedIncreaseByte = value & BYTE_MASK;
    }

    /**
     * @return the unsigned speed decrease byte (nonzero under a slowing effect)
     */
    public int getSpeedDecreaseByte() {
        return this.speedDecreaseByte;
    }

    /**
     * Sets the speed decrease byte.
     *
     * @param value the byte, truncated to a BYTE
     */
    public void setSpeedDecreaseByte(int value) {
        this.speedDecreaseByte = value & BYTE_MASK;
    }


    /**
     * @return the unit the movement follows, or {@code null}
     */
    public @Nullable Unit getMovementTargetUnit() {
        return this.movementTargetUnit;
    }

    /**
     * Sets the unit the movement follows.
     *
     * @param movementTargetUnit the unit, or {@code null}
     */
    public void setMovementTargetUnit(@Nullable Unit movementTargetUnit) {
        this.movementTargetUnit = movementTargetUnit;
    }

    /**
     * @return the frames until the movement destination is refreshed
     */
    public int getMovementDestinationCountdown() {
        return this.movementDestinationCountdown;
    }

    /**
     * Sets the frames until the movement destination is refreshed.
     *
     * @param value the countdown, truncated to a BYTE
     */
    public void setMovementDestinationCountdown(int value) {
        this.movementDestinationCountdown = value & BYTE_MASK;
    }

    /**
     * @return the zero-based persistent path index, or {@code -1} for no path
     */
    public int getPathIndex() {
        return this.pathIndex;
    }

    /**
     * Sets the path of the unit.
     *
     * @param pathIndex the zero-based path index, or {@code -1}
     */
    public void setPathIndex(int pathIndex) {
        this.pathIndex = pathIndex;
    }

    /**
     * @return the frames until the path is recalculated
     */
    public int getPathRecalculationCountdown() {
        return this.pathRecalculationCountdown;
    }

    /**
     * Sets the frames until the path is recalculated.
     *
     * @param value the countdown, truncated to a BYTE
     */
    public void setPathRecalculationCountdown(int value) {
        this.pathRecalculationCountdown = value & BYTE_MASK;
    }

    /**
     * @return the path collision flags byte
     */
    public int getPathCollisionFlags() {
        return this.pathCollisionFlags;
    }

    /**
     * Sets the path collision flags.
     *
     * @param value the flags, truncated to a BYTE
     */
    public void setPathCollisionFlags(int value) {
        this.pathCollisionFlags = value & BYTE_MASK;
    }

    /**
     * @return the left edge of the terrain collision box
     */
    public int getTerrainCollisionLeft() {
        return this.terrainCollisionLeft;
    }

    /**
     * Sets the left edge of the terrain collision box.
     *
     * @param value the edge, truncated to a WORD
     */
    public void setTerrainCollisionLeft(int value) {
        this.terrainCollisionLeft = value & WORD_MASK;
    }

    /**
     * @return the top edge of the terrain collision box
     */
    public int getTerrainCollisionTop() {
        return this.terrainCollisionTop;
    }

    /**
     * Sets the top edge of the terrain collision box.
     *
     * @param value the edge, truncated to a WORD
     */
    public void setTerrainCollisionTop(int value) {
        this.terrainCollisionTop = value & WORD_MASK;
    }

    /**
     * @return the right edge of the terrain collision box
     */
    public int getTerrainCollisionRight() {
        return this.terrainCollisionRight;
    }

    /**
     * Sets the right edge of the terrain collision box.
     *
     * @param value the edge, truncated to a WORD
     */
    public void setTerrainCollisionRight(int value) {
        this.terrainCollisionRight = value & WORD_MASK;
    }

    /**
     * @return the bottom edge of the terrain collision box
     */
    public int getTerrainCollisionBottom() {
        return this.terrainCollisionBottom;
    }

    /**
     * Sets the bottom edge of the terrain collision box.
     *
     * @param value the edge, truncated to a WORD
     */
    public void setTerrainCollisionBottom(int value) {
        this.terrainCollisionBottom = value & WORD_MASK;
    }


    /**
     * @return the flyer repulsion flags: the countdown to the next turn (bits {@code 0-2}), bit {@code 3} and the adjustment index (bits {@code 4-7})
     */
    public int getRepulsionFlags() {
        return this.repulsionFlags;
    }

    /**
     * Sets the flyer repulsion flags.
     *
     * @param value the flags, truncated to a BYTE
     */
    public void setRepulsionFlags(int value) {
        this.repulsionFlags = value & BYTE_MASK;
    }

    /**
     * @return the flyer repulsion direction, an eight-bit direction
     */
    public int getRepulsionDirection() {
        return this.repulsionDirection;
    }

    /**
     * Sets the flyer repulsion direction.
     *
     * @param value the direction, truncated to a BYTE
     */
    public void setRepulsionDirection(int value) {
        this.repulsionDirection = value & BYTE_MASK;
    }

    /**
     * @return the horizontal cell of the flyer repulsion grid the unit is counted in
     */
    public int getRepulsionCellX() {
        return this.repulsionCellX;
    }

    /**
     * Sets the horizontal cell of the flyer repulsion grid.
     *
     * @param value the cell, truncated to a BYTE
     */
    public void setRepulsionCellX(int value) {
        this.repulsionCellX = value & BYTE_MASK;
    }

    /**
     * @return the vertical cell of the flyer repulsion grid the unit is counted in
     */
    public int getRepulsionCellY() {
        return this.repulsionCellY;
    }

    /**
     * Sets the vertical cell of the flyer repulsion grid.
     *
     * @param value the cell, truncated to a BYTE
     */
    public void setRepulsionCellY(int value) {
        this.repulsionCellY = value & BYTE_MASK;
    }


    /**
     * @return the {@code orders.dat} identifier of the primary order
     */
    public int getPrimaryOrder() {
        return this.primaryOrder;
    }

    /**
     * Sets the primary order, and the engine order type of the entry ({@link #getOrderType()}, {@code null} outside {@code orders.dat}).
     *
     * @param value the {@code orders.dat} identifier, truncated to a BYTE
     */
    public void setPrimaryOrder(int value) {
        this.primaryOrder = value & BYTE_MASK;

        this.setOrderType(ScrNativeIds.orderOf(this.primaryOrder));
    }

    /**
     * @return the state byte of the primary order
     */
    public int getPrimaryOrderState() {
        return this.primaryOrderState;
    }

    /**
     * Sets the state of the primary order.
     *
     * @param value the state, truncated to a BYTE
     */
    public void setPrimaryOrderState(int value) {
        this.primaryOrderState = value & BYTE_MASK;
    }

    /**
     * @return the frames until the primary order runs again
     */
    public int getPrimaryOrderCountdown() {
        return this.primaryOrderCountdown;
    }

    /**
     * Sets the frames until the primary order runs again.
     *
     * @param value the countdown, truncated to a BYTE
     */
    public void setPrimaryOrderCountdown(int value) {
        this.primaryOrderCountdown = value & BYTE_MASK;
    }

    /**
     * @return the {@code orders.dat} identifier of the secondary order
     */
    public int getSecondaryOrder() {
        return this.secondaryOrder;
    }

    /**
     * Sets the secondary order and clears the secondary-order position DWORD.
     *
     * @param value the order id in the low eight bits
     */
    public void setSecondaryOrder(int value) {
        this.secondaryOrder         = value & BYTE_MASK;
        this.secondaryOrderPosition = 0;
    }

    /**
     * Sets only the secondary-order BYTE, as a raw memory write does.
     *
     * @param value the order id in the low eight bits
     */
    public void setSecondaryOrderByte(int value) {
        this.secondaryOrder = value & BYTE_MASK;
    }

    /**
     * @return the state byte of the secondary order
     */
    public int getSecondaryOrderState() {
        return this.secondaryOrderState;
    }

    /**
     * Sets the state of the secondary order.
     *
     * @param value the state, truncated to a BYTE
     */
    public void setSecondaryOrderState(int value) {
        this.secondaryOrderState = value & BYTE_MASK;
    }

    /**
     * @return the secondary-order position DWORD, cleared by {@link #setSecondaryOrder(int)}
     */
    public int getSecondaryOrderPosition() {
        return this.secondaryOrderPosition;
    }

    /**
     * Sets the secondary-order position DWORD.
     *
     * @param value the DWORD
     */
    public void setSecondaryOrderPosition(int value) {
        this.secondaryOrderPosition = value;
    }

    /**
     * Returns the four classic event bytes. The unit initializer clears them, so they hold {@code 0} unless an EUD write stored a value; the
     * third byte is the rank increase ({@link #getRankIncrease()}).
     *
     * @return the classic event bytes
     */
    public int getClassicEventBytes() {
        return this.classicEventBytes;
    }

    /**
     * Sets the four classic event bytes.
     *
     * @param value the bytes as a little-endian DWORD
     */
    public void setClassicEventBytes(int value) {
        this.classicEventBytes = value;
    }

    /**
     * Returns the rank increase, which the rank adds to the {@code units.dat} Sublabel.
     * <p>
     * Only the unit initializer (clears it), the EUD write and the classic-layout unit load write the byte; no game rule,
     * trigger action or map section sets it.
     *
     * @return the unsigned rank increase byte
     */
    public int getRankIncrease() {
        return this.classicEventBytes >>> RANK_INCREASE_SHIFT & BYTE_MASK;
    }

    /**
     * @return the retained classic-layout unused byte
     */
    public int getClassicUnusedByte() {
        return this.classicUnusedByte;
    }

    /**
     * Sets the classic unused byte.
     *
     * @param value the byte, truncated to a BYTE
     */
    public void setClassicUnusedByte(int value) {
        this.classicUnusedByte = value & BYTE_MASK;
    }

    /**
     * Returns the under-attack area mark: the local attack alert sets it to {@code 100} on every unit near an alerted
     * one and suppresses a new alert of a marked unit; the interface tick decrements it on every unit each {@code 100} ms tick.
     * The unit initializer clears it. It is local interface state kept in the unit.
     *
     * @return the mark, {@code 0} when unmarked
     */
    public int getAlertMark() {
        return this.alertMark;
    }

    /**
     * Sets the under-attack area mark.
     *
     * @param value the mark in the low eight bits
     */
    public void setAlertMark(int value) {
        this.alertMark = value & BYTE_MASK;
    }

    /**
     * @return the order signal flags
     */
    public int getOrderSignal() {
        return this.orderSignal;
    }

    /**
     * Sets the order signal flags.
     *
     * @param orderSignal the new order signal flags
     */
    public void setOrderSignal(int orderSignal) {
        this.orderSignal = orderSignal & BYTE_MASK;
    }

    /**
     * @return the unit type of the order target (for example the building to construct), or {@code 228} for none
     */
    public int getOrderTargetUnitType() {
        return this.orderTargetUnitType;
    }

    /**
     * Sets the unit type of the order target.
     *
     * @param value the unit type ID, truncated to a WORD
     */
    public void setOrderTargetUnitType(int value) {
        this.orderTargetUnitType = value & WORD_MASK;
    }

    /**
     * @return the target unit of the current order, or {@code null} for a point target
     */
    @Override
    public @Nullable Unit getOrderTargetUnit() {
        return (Unit) super.getOrderTargetUnit();
    }

    /**
     * Returns the target unit of a current order that must have one, such as a spell its caster reached.
     *
     * @return the target unit of the current order
     * @throws IllegalStateException if the current order has a point target
     */
    public @NotNull Unit requireOrderTargetUnit() {
        Unit target = this.getOrderTargetUnit();

        if (target == null) {
            throw Exceptions.newIllegalStateException("Unit {0} has no order target unit.", this.getIndex());
        }

        return target;
    }

    /**
     * @return the first order of the queue, the one that follows the current order, or {@code null} if the queue is empty
     */
    @Override
    public OrderPool.@Nullable Order getFirstOrder() {
        return (OrderPool.Order) super.getFirstOrder();
    }

    /**
     * @return the last order of the queue, or {@code null} if the queue is empty
     */
    @Override
    public OrderPool.@Nullable Order getLastOrder() {
        return (OrderPool.Order) super.getLastOrder();
    }

    /**
     * @return the horizontal rally position, {@code 0} when unset
     */
    public int getRallyPositionX() {
        return this.rallyPositionX;
    }

    /**
     * Sets the horizontal rally position.
     *
     * @param value the position, truncated to a WORD
     */
    public void setRallyPositionX(int value) {
        this.rallyPositionX = value & WORD_MASK;
    }

    /**
     * @return the vertical rally position, {@code 0} when unset
     */
    public int getRallyPositionY() {
        return this.rallyPositionY;
    }

    /**
     * Sets the vertical rally position.
     *
     * @param value the position, truncated to a WORD
     */
    public void setRallyPositionY(int value) {
        this.rallyPositionY = value & WORD_MASK;
    }

    /**
     * @return the rally target unit, or {@code null}
     */
    public @Nullable Unit getRallyTargetUnit() {
        return this.rallyTargetUnit;
    }

    /**
     * Sets the rally target unit.
     *
     * @param value the rally target unit, or {@code null}
     */
    public void setRallyTargetUnit(@Nullable Unit value) {
        this.rallyTargetUnit = value;
    }

    /**
     * @return the computer player's record of this unit, or {@code null} if no computer player tracks it
     */
    public @Nullable ComputerUnitRecord getComputerRecord() {
        return this.computerRecord;
    }

    /**
     * Returns the computer player's record of a unit that must have one, such as a guard away from its post.
     *
     * @return the computer player's record of this unit
     * @throws IllegalStateException if no computer player tracks the unit
     */
    public @NotNull ComputerUnitRecord requireComputerRecord() {
        ComputerUnitRecord record = this.computerRecord;

        if (record == null) {
            throw Exceptions.newIllegalStateException("Unit {0} has no computer record.", this.getIndex());
        }

        return record;
    }

    /**
     * Sets the computer player's record of this unit.
     *
     * @param record the record, or {@code null}
     */
    public void setComputerRecord(@Nullable ComputerUnitRecord record) {
        this.computerRecord = record;
    }

    /**
     * @return the saved skin WORD
     */
    public int getSkin() {
        return this.skin;
    }

    /**
     * Sets the saved skin WORD.
     *
     * @param skin the skin, truncated to a WORD
     */
    public void setSkin(int skin) {
        this.skin = skin & WORD_MASK;
    }

    /**
     * @return the local event bytes of the classic unit as a little-endian WORD
     */
    public int getLocalEventBytes() {
        return this.localEventBytes;
    }

    /**
     * Sets the local event bytes of the classic unit.
     *
     * @param localEventBytes the bytes as a little-endian WORD
     */
    public void setLocalEventBytes(int localEventBytes) {
        this.localEventBytes = localEventBytes & WORD_MASK;
    }


    /**
     * @return the four timer bytes: the main order timer ({@link #ORDER_TIMER_MASK}), the ground and air weapon cooldowns and the spell cooldown
     */
    public int getCooldownTimers() {
        return this.cooldownTimers;
    }

    /**
     * Sets the four timer bytes.
     *
     * @param value the timer bytes as a little-endian DWORD
     */
    public void setCooldownTimers(int value) {
        this.cooldownTimers = value;
    }

    /**
     * @return the main order timer, the low byte of the cooldown timers
     */
    public int getOrderTimer() {
        return this.getCooldownTimers() & ORDER_TIMER_MASK;
    }

    /**
     * Sets the main order timer, the low byte of the cooldown timers, and keeps the other timer bytes.
     *
     * @param value the timer, truncated to a BYTE
     */
    public void setOrderTimer(int value) {
        this.setCooldownTimers(this.getCooldownTimers() & ~ORDER_TIMER_MASK | value & ORDER_TIMER_MASK);
    }

    /**
     * @return {@code true} if an automatic target is assigned, {@code false} otherwise
     */
    public boolean hasAutomaticTarget() {
        return this.automaticTargetUnit != null;
    }

    /**
     * @return the automatic attack target, or {@code null}
     */
    public @Nullable Unit getAutomaticTargetUnit() {
        return this.automaticTargetUnit;
    }

    /**
     * Sets the automatic attack target.
     *
     * @param target the automatic target, or {@code null} to clear it
     */
    public void setAutomaticTargetUnit(@Nullable Unit target) {
        this.automaticTargetUnit = target;
    }

    /**
     * @return the air strength of the unit for the computer players
     */
    public int getAirStrength() {
        return this.airStrength;
    }

    /**
     * Sets the air strength.
     *
     * @param value the strength, truncated to a WORD
     */
    public void setAirStrength(int value) {
        this.airStrength = value & WORD_MASK;
    }

    /**
     * @return the ground strength of the unit for the computer players
     */
    public int getGroundStrength() {
        return this.groundStrength;
    }

    /**
     * Sets the ground strength.
     *
     * @param value the strength, truncated to a WORD
     */
    public void setGroundStrength(int value) {
        this.groundStrength = value & WORD_MASK;
    }

    /**
     * @return the next offset index used by this unit's attack-position bullets
     */
    public int getNextHitNearTargetIndex() {
        return this.nextHitNearTargetIndex;
    }

    /**
     * Sets the next attack-position bullet offset index.
     *
     * @param index the index
     */
    public void setNextHitNearTargetIndex(int index) {
        this.nextHitNearTargetIndex = index;
    }

    /**
     * @return the computer spell target bits
     */
    public int getSpellTargetFlags() {
        return this.spellTargetFlags;
    }

    /**
     * Sets the computer spell target bits.
     *
     * @param value the bits, truncated to a BYTE
     */
    public void setSpellTargetFlags(int value) {
        this.spellTargetFlags = value & BYTE_MASK;
    }


    /**
     * @return the queue slot of the current production entry
     */
    public int getBuildQueueIndex() {
        return this.buildQueueIndex;
    }

    /**
     * Sets the queue slot of the current production entry.
     *
     * @param value the slot, truncated to a BYTE
     */
    public void setBuildQueueIndex(int value) {
        this.buildQueueIndex = value & BYTE_MASK;
    }

    /**
     * @param index the queue slot from {@code 0} through {@code 4}
     * @return the queued {@code units.dat} type, or {@code 228} for an empty slot
     * @throws IllegalArgumentException if the index is outside {@code 0..4}
     */
    public int getBuildQueueUnitType(int index) {
        return switch (index) {
            case FIRST_QUEUE_SLOT -> this.firstBuildQueueType;
            case SECOND_QUEUE_SLOT -> this.secondBuildQueueType;
            case THIRD_QUEUE_SLOT -> this.thirdBuildQueueType;
            case FOURTH_QUEUE_SLOT -> this.fourthBuildQueueType;
            case FIFTH_QUEUE_SLOT -> this.fifthBuildQueueType;
            default -> throw Exceptions.newIllegalArgumentException("Build queue index must be between zero and four: {0}", index);
        };
    }

    /**
     * Sets the unit type of one build queue slot.
     *
     * @param index    the queue slot from {@code 0} through {@code 4}
     * @param unitType the unit type ID, truncated to a WORD
     * @throws IllegalArgumentException if the index is outside {@code 0..4}
     */
    public void setBuildQueueUnitType(int index, int unitType) {
        switch (index) {
            case FIRST_QUEUE_SLOT -> this.setFirstBuildQueueType(unitType);
            case SECOND_QUEUE_SLOT -> this.setSecondBuildQueueType(unitType);
            case THIRD_QUEUE_SLOT -> this.setThirdBuildQueueType(unitType);
            case FOURTH_QUEUE_SLOT -> this.setFourthBuildQueueType(unitType);
            case FIFTH_QUEUE_SLOT -> this.setFifthBuildQueueType(unitType);
            default -> throw Exceptions.newIllegalArgumentException("Build queue index must be between zero and four: {0}", index);
        }
    }

    /**
     * @return the unit type of the first build queue slot
     */
    public int getFirstBuildQueueType() {
        return this.firstBuildQueueType;
    }

    /**
     * Sets the unit type of the first build queue slot.
     *
     * @param value the unit type ID, truncated to a WORD
     */
    public void setFirstBuildQueueType(int value) {
        this.firstBuildQueueType = value & WORD_MASK;
    }

    /**
     * @return the unit type of the second build queue slot
     */
    public int getSecondBuildQueueType() {
        return this.secondBuildQueueType;
    }

    /**
     * Sets the unit type of the second build queue slot.
     *
     * @param value the unit type ID, truncated to a WORD
     */
    public void setSecondBuildQueueType(int value) {
        this.secondBuildQueueType = value & WORD_MASK;
    }

    /**
     * @return the unit type of the third build queue slot
     */
    public int getThirdBuildQueueType() {
        return this.thirdBuildQueueType;
    }

    /**
     * Sets the unit type of the third build queue slot.
     *
     * @param value the unit type ID, truncated to a WORD
     */
    public void setThirdBuildQueueType(int value) {
        this.thirdBuildQueueType = value & WORD_MASK;
    }

    /**
     * @return the unit type of the fourth build queue slot
     */
    public int getFourthBuildQueueType() {
        return this.fourthBuildQueueType;
    }

    /**
     * Sets the unit type of the fourth build queue slot.
     *
     * @param value the unit type ID, truncated to a WORD
     */
    public void setFourthBuildQueueType(int value) {
        this.fourthBuildQueueType = value & WORD_MASK;
    }

    /**
     * @return the unit type of the fifth build queue slot
     */
    public int getFifthBuildQueueType() {
        return this.fifthBuildQueueType;
    }

    /**
     * Sets the unit type of the fifth build queue slot.
     *
     * @param value the unit type ID, truncated to a WORD
     */
    public void setFifthBuildQueueType(int value) {
        this.fifthBuildQueueType = value & WORD_MASK;
    }

    /**
     * @return {@code true} if a current build target is assigned, {@code false} otherwise
     */
    public boolean hasCurrentBuildUnit() {
        return this.currentBuildUnit != null;
    }

    /**
     * @return the unit this unit is constructing or training, or {@code null}
     */
    public @Nullable Unit getCurrentBuildUnit() {
        return this.currentBuildUnit;
    }

    /**
     * Sets the unit currently being built.
     *
     * @param target the current build target, or {@code null}
     */
    public void setCurrentBuildUnit(@Nullable Unit target) {
        this.currentBuildUnit = target;
    }

    /**
     * @return the remaining construction or training time in frames
     */
    public int getRemainingBuildTime() {
        return this.remainingBuildTime;
    }

    /**
     * Sets the remaining construction or training time.
     *
     * @param value the frames, truncated to a WORD
     */
    public void setRemainingBuildTime(int value) {
        this.remainingBuildTime = value & WORD_MASK;
    }

    /**
     * @return the hit points added per construction frame
     */
    public int getConstructionHitPointIncrement() {
        return this.constructionHitPointIncrement;
    }

    /**
     * Sets the hit points added per construction frame.
     *
     * @param value the increment, truncated to a WORD
     */
    public void setConstructionHitPointIncrement(int value) {
        this.constructionHitPointIncrement = value & WORD_MASK;
    }

    /**
     * @return the shield points added per construction frame
     */
    public int getConstructionShieldIncrement() {
        return this.constructionShieldIncrement;
    }

    /**
     * Sets the shield points added per construction frame.
     *
     * @param value the increment, truncated to a WORD
     */
    public void setConstructionShieldIncrement(int value) {
        this.constructionShieldIncrement = value & WORD_MASK;
    }

    /**
     * @return the technology being researched, or {@code 44} when none
     */
    public int getResearchType() {
        return this.pool.getResearchTypes().get(this.getIndex());
    }

    /**
     * Sets the technology being researched.
     *
     * @param value the technology ID, truncated to a BYTE
     */
    public void setResearchType(int value) {
        this.pool.getResearchTypes().set(this.getIndex(), value & BYTE_MASK);
    }

    /**
     * @return the upgrade being researched, or {@code 61} when none
     */
    public int getUpgradeType() {
        return this.pool.getUpgradeTypes().get(this.getIndex());
    }

    /**
     * Sets the upgrade being researched.
     *
     * @param value the upgrade ID, truncated to a BYTE
     */
    public void setUpgradeType(int value) {
        this.pool.getUpgradeTypes().set(this.getIndex(), value & BYTE_MASK);
    }

    /**
     * @return the remaining research or upgrade time in frames
     */
    public int getUpgradeResearchTime() {
        return this.pool.getUpgradeResearchTimes().get(this.getIndex());
    }

    /**
     * Sets the remaining research or upgrade time.
     *
     * @param value the frames, truncated to a WORD
     */
    public void setUpgradeResearchTime(int value) {
        this.pool.getUpgradeResearchTimes().set(this.getIndex(), value & WORD_MASK);
    }

    /**
     * @return the level of the upgrade being researched
     */
    public int getUpgradeLevel() {
        return this.pool.getUpgradeLevels().get(this.getIndex());
    }

    /**
     * Sets the level of the upgrade being researched.
     *
     * @param value the level, truncated to a BYTE
     */
    public void setUpgradeLevel(int value) {
        this.pool.getUpgradeLevels().set(this.getIndex(), value & BYTE_MASK);
    }

    /**
     * @return the attached Terran add-on of this building, or {@code null}
     */
    public @Nullable Unit getAddon() {
        return this.addon;
    }

    /**
     * Sets the attached Terran add-on of this building.
     *
     * @param addon the attached add-on, or {@code null}
     */
    public void setAddon(@Nullable Unit addon) {
        this.addon = addon;
    }

    /**
     * @return the add-on type requested by PlaceAddon, or {@code 228} for none
     */
    public int getAddonBuildType() {
        return this.pool.getAddonBuildTypes().get(this.getIndex());
    }

    /**
     * Sets the add-on type requested by PlaceAddon.
     *
     * @param addonBuildType the requested add-on type, or {@code 228} for none
     */
    public void setAddonBuildType(int addonBuildType) {
        this.pool.getAddonBuildTypes().set(this.getIndex(), addonBuildType);
    }

    /**
     * @return {@code true} if this flying building has reserved its landing footprint, {@code false} otherwise
     */
    public boolean isLanding() {
        return this.getLandingByte() != 0;
    }

    /**
     * Sets whether this flying building has reserved its landing footprint.
     *
     * @param landing whether the landing footprint is reserved
     */
    public void setLanding(boolean landing) {
        this.setLandingByte(landing ? 1 : 0);
    }

    /**
     * @return the raw landing byte of a building
     */
    public int getLandingByte() {
        return this.pool.getLandingBytes().get(this.getIndex());
    }

    /**
     * Sets the raw landing byte of a building.
     *
     * @param value the byte in the low eight bits
     */
    public void setLandingByte(int value) {
        this.pool.getLandingBytes().set(this.getIndex(), value & BYTE_MASK);
    }

    /**
     * @return the worker's unsigned WORD of remaining frames before the next repair charge
     */
    public int getRepairTimer() {
        return this.pool.getRepairTimers().get(this.getIndex());
    }

    /**
     * Sets the worker's remaining repair frames before the next repair charge.
     *
     * @param repairTimer the remaining repair frames, truncated to a WORD
     */
    public void setRepairTimer(int repairTimer) {
        this.pool.getRepairTimers().set(this.getIndex(), repairTimer & WORD_MASK);
    }

    /**
     * @return the Medic healing state of the unit, nonzero while it is being healed
     */
    public int getHealingState() {
        return this.healingState;
    }

    /**
     * Sets the Medic healing state.
     *
     * @param value the state, truncated to a BYTE
     */
    public void setHealingState(int value) {
        this.healingState = value & BYTE_MASK;
    }


    /**
     * @return the frames until the next Larva spawns
     */
    public int getLarvaSpawnCountdown() {
        return this.pool.getLarvaSpawnCountdowns().get(this.getIndex());
    }

    /**
     * Sets the frames until the next Larva spawns.
     *
     * @param value the countdown, truncated to a BYTE
     */
    public void setLarvaSpawnCountdown(int value) {
        this.pool.getLarvaSpawnCountdowns().set(this.getIndex(), value & BYTE_MASK);
    }

    /**
     * Reads the signed side count used by Larva placement without duplicating its shared storage.
     * <p>
     * Side {@code 0} uses the same WORD as {@link #getResources()}.
     *
     * @param side the side index: left {@code 0}, upper {@code 1}, right {@code 2}, lower {@code 3}
     * @return the retained signed 16-bit count, including negative values
     * @throws IllegalArgumentException if the side is outside {@code 0..3}
     */
    public int getLarvaSideCount(int side) {
        int count = switch (side) {
            case LARVA_SIDE_LEFT -> this.getResources();
            case LARVA_SIDE_UPPER -> this.getSecondLarvaSideCount();
            case LARVA_SIDE_RIGHT -> this.getThirdLarvaSideCount();
            case LARVA_SIDE_LOWER -> this.getFourthLarvaSideCount();
            default -> throw Exceptions.newIllegalArgumentException("{0}", "Larva placement requires a side index from 0 through 3.");
        };

        return (short) count;
    }

    /**
     * @return the upper Larva side count WORD
     */
    public int getSecondLarvaSideCount() {
        return this.pool.getSecondLarvaSideCounts().get(this.getIndex());
    }

    /**
     * Sets the upper Larva side count.
     *
     * @param value the count, truncated to a WORD
     */
    public void setSecondLarvaSideCount(int value) {
        this.pool.getSecondLarvaSideCounts().set(this.getIndex(), value & WORD_MASK);
    }

    /**
     * @return the right Larva side count WORD
     */
    public int getThirdLarvaSideCount() {
        return this.pool.getThirdLarvaSideCounts().get(this.getIndex());
    }

    /**
     * Sets the right Larva side count.
     *
     * @param value the count, truncated to a WORD
     */
    public void setThirdLarvaSideCount(int value) {
        this.pool.getThirdLarvaSideCounts().set(this.getIndex(), value & WORD_MASK);
    }

    /**
     * @return the lower Larva side count WORD
     */
    public int getFourthLarvaSideCount() {
        return this.pool.getFourthLarvaSideCounts().get(this.getIndex());
    }

    /**
     * Sets the lower Larva side count.
     *
     * @param value the count, truncated to a WORD
     */
    public void setFourthLarvaSideCount(int value) {
        this.pool.getFourthLarvaSideCounts().set(this.getIndex(), value & WORD_MASK);
    }

    /**
     * @return the resource animation, which shares the low byte of the second Larva side count WORD
     */
    public int getResourceAnimation() {
        return this.getSecondLarvaSideCount() & BYTE_MASK;
    }

    /**
     * Sets the resource animation in the low byte of the second Larva side count WORD, keeping its high byte.
     *
     * @param value the animation, truncated to a BYTE
     */
    public void setResourceAnimation(int value) {
        this.setSecondLarvaSideCount((this.getSecondLarvaSideCount() & HIGH_BYTE_MASK) | (value & BYTE_MASK));
    }

    /**
     * @return the frames until the next creep spread step
     */
    public int getCreepTimer() {
        return this.pool.getCreepTimers().get(this.getIndex());
    }

    /**
     * Sets the frames until the next creep spread step.
     *
     * @param value the timer, truncated to a BYTE
     */
    public void setCreepTimer(int value) {
        this.pool.getCreepTimers().set(this.getIndex(), value & BYTE_MASK);
    }


    /**
     * @return the resource amount
     */
    public int getResources() {
        return this.pool.getResourceAmounts().get(this.getIndex());
    }

    /**
     * Sets the resource amount.
     *
     * @param resources the new resource amount
     */
    public void setResources(int resources) {
        this.pool.getResourceAmounts().set(this.getIndex(), resources & WORD_MASK);
    }

    /**
     * @return the resource area of the resource
     */
    public int getResourceAreaIndex() {
        return this.pool.getResourceAreaIndices().get(this.getIndex());
    }

    /**
     * Sets the resource area of the resource.
     *
     * @param value the resource area index, truncated to a BYTE
     */
    public void setResourceAreaIndex(int value) {
        this.pool.getResourceAreaIndices().set(this.getIndex(), value & BYTE_MASK);
    }

    /**
     * @return {@code true} if a computer town has claimed this resource, {@code false} otherwise
     */
    public boolean isResourceClaimedByComputer() {
        return this.getResourceClaimByte() != 0;
    }

    /**
     * Sets whether a computer town has claimed this resource.
     *
     * @param claimed whether the resource is claimed
     */
    public void setResourceClaimedByComputer(boolean claimed) {
        this.setResourceClaimByte(claimed ? 1 : 0);
    }

    /**
     * @return the raw resource byte that marks a computer town's claim
     */
    public int getResourceClaimByte() {
        return this.pool.getResourceClaimBytes().get(this.getIndex());
    }

    /**
     * Sets the raw resource byte that marks a computer town's claim.
     *
     * @param value the byte in the low eight bits
     */
    public void setResourceClaimByte(int value) {
        this.pool.getResourceClaimBytes().set(this.getIndex(), value & BYTE_MASK);
    }

    /**
     * @return {@code true} if a worker gathers from this resource, {@code false} otherwise
     */
    public boolean isResourceBeingGathered() {
        return this.getResourceBeingGatheredByte() != 0;
    }

    /**
     * Sets whether a worker gathers from this resource.
     *
     * @param value whether the resource is being gathered
     */
    public void setResourceBeingGathered(boolean value) {
        this.setResourceBeingGatheredByte(value ? 1 : 0);
    }

    /**
     * @return the raw resource byte that is nonzero while a worker gathers
     */
    public int getResourceBeingGatheredByte() {
        return this.pool.getResourceBeingGatheredBytes().get(this.getIndex());
    }

    /**
     * Sets the raw resource byte that is nonzero while a worker gathers.
     *
     * @param value the byte in the low eight bits
     */
    public void setResourceBeingGatheredByte(int value) {
        this.pool.getResourceBeingGatheredBytes().set(this.getIndex(), value & BYTE_MASK);
    }

    /**
     * @return the first worker of the resource's gathering queue, or {@code null}
     */
    public @Nullable Unit getFirstGatheringWorker() {
        return this.firstGatheringWorker;
    }

    /**
     * Sets the first worker of the resource's gathering queue.
     *
     * @param worker the worker, or {@code null}
     */
    public void setFirstGatheringWorker(@Nullable Unit worker) {
        this.firstGatheringWorker = worker;
    }

    /**
     * @return the last worker of the resource's gathering queue, or {@code null}
     */
    public @Nullable Unit getLastGatheringWorker() {
        return this.lastGatheringWorker;
    }

    /**
     * Sets the last worker of the resource's gathering queue.
     *
     * @param worker the worker, or {@code null}
     */
    public void setLastGatheringWorker(@Nullable Unit worker) {
        this.lastGatheringWorker = worker;
    }

    /**
     * @return the previous worker in the gathering queue, or {@code null}
     */
    public @Nullable Unit getPreviousGatheringWorker() {
        return this.previousGatheringWorker;
    }

    /**
     * Sets the previous worker in the gathering queue.
     *
     * @param worker the worker, or {@code null}
     */
    public void setPreviousGatheringWorker(@Nullable Unit worker) {
        this.previousGatheringWorker = worker;
    }

    /**
     * @return the next worker in the gathering queue, or {@code null}
     */
    public @Nullable Unit getNextGatheringWorker() {
        return this.nextGatheringWorker;
    }

    /**
     * Sets the next worker in the gathering queue.
     *
     * @param worker the worker, or {@code null}
     */
    public void setNextGatheringWorker(@Nullable Unit worker) {
        this.nextGatheringWorker = worker;
    }

    /**
     * @return the horizontal position of the worker's target resource
     */
    public int getTargetResourceX() {
        return this.pool.getTargetResourceXs().get(this.getIndex());
    }

    /**
     * @return the vertical position of the worker's target resource
     */
    public int getTargetResourceY() {
        return this.pool.getTargetResourceYs().get(this.getIndex());
    }

    /**
     * @return the worker's target resource, or {@code null}
     */
    public @Nullable Unit getTargetResourceUnit() {
        return this.targetResourceUnit;
    }

    /**
     * Sets the worker's target resource and its position.
     *
     * @param target    the target resource, or {@code null}
     * @param positionX the horizontal position
     * @param positionY the vertical position
     */
    public void setTargetResource(@Nullable Unit target, int positionX, int positionY) {
        this.targetResourceUnit = target;
        this.pool.getTargetResourceXs().set(this.getIndex(), positionX);
        this.pool.getTargetResourceYs().set(this.getIndex(), positionY);
    }

    /**
     * @return the resource the worker gathers from, or {@code null}
     */
    public @Nullable Unit getGatherTarget() {
        return this.gatherTarget;
    }

    /**
     * Sets the resource the worker gathers from.
     *
     * @param target the resource, or {@code null}
     */
    public void setGatherTarget(@Nullable Unit target) {
        this.gatherTarget = target;
    }

    /**
     * @return {@code true} if the worker gathers, {@code false} otherwise
     */
    public boolean isGatheringResource() {
        return this.getGatheringResourceByte() != 0;
    }

    /**
     * Sets whether the worker gathers.
     *
     * @param value whether the worker gathers
     */
    public void setGatheringResource(boolean value) {
        this.setGatheringResourceByte(value ? 1 : 0);
    }

    /**
     * @return the raw worker byte that is nonzero while the worker gathers
     */
    public int getGatheringResourceByte() {
        return this.pool.getGatheringResourceBytes().get(this.getIndex());
    }

    /**
     * Sets the raw worker byte that is nonzero while the worker gathers.
     *
     * @param value the byte in the low eight bits
     */
    public void setGatheringResourceByte(int value) {
        this.pool.getGatheringResourceBytes().set(this.getIndex(), value & BYTE_MASK);
    }

    /**
     * @return the resource amount the worker carries
     */
    public int getCarriedResourceAmount() {
        return this.pool.getCarriedResourceAmounts().get(this.getIndex());
    }

    /**
     * Sets the resource amount the worker carries.
     *
     * @param amount the amount
     */
    public void setCarriedResourceAmount(int amount) {
        this.pool.getCarriedResourceAmounts().set(this.getIndex(), amount);
    }

    /**
     * @return the carried resource flags: {@code 1} gas, {@code 2} minerals
     */
    public int getCarryingFlags() {
        return this.carryingFlags;
    }

    /**
     * Sets the carried resource flags.
     *
     * @param value the flags, truncated to a BYTE
     */
    public void setCarryingFlags(int value) {
        this.carryingFlags = value & BYTE_MASK;
    }

    /**
     * @return {@code true} if the worker carries a powerup, {@code false} otherwise
     */
    public boolean hasCarriedPowerup() {
        return this.carriedPowerup != null;
    }

    /**
     * @return the powerup the worker carries, or {@code null}
     */
    public @Nullable Unit getCarriedPowerup() {
        return this.carriedPowerup;
    }

    /**
     * Sets the powerup the worker carries.
     *
     * @param powerup the powerup, or {@code null}
     */
    public void setCarriedPowerup(@Nullable Unit powerup) {
        this.carriedPowerup = powerup;
    }

    /**
     * @return the worker carrying this powerup, or {@code null}
     */
    public @Nullable Unit getPowerupCarrier() {
        return this.powerupCarrier;
    }

    /**
     * Sets the worker carrying this powerup.
     *
     * @param carrier the worker, or {@code null}
     */
    public void setPowerupCarrier(@Nullable Unit carrier) {
        this.powerupCarrier = carrier;
    }


    /**
     * @param position the transport slot from {@code 0} through {@code 7}
     * @return the unsigned base handle WORD, before capacity-dependent extension bits
     * @throws ArrayIndexOutOfBoundsException if the position is outside {@code 0..7}
     */
    public int getLoadedUnitHandle(int position) {
        return this.loadedUnitHandles[position] & WORD_MASK;
    }

    /**
     * Sets the base handle of a transport slot.
     *
     * @param position the transport slot from {@code 0} through {@code 7}
     * @param handle   the base handle narrowed to one WORD
     * @throws ArrayIndexOutOfBoundsException if the position is outside {@code 0..7}
     */
    public void setLoadedUnitHandle(int position, int handle) {
        this.loadedUnitHandles[position] = (short) handle;
    }

    /**
     * @return the capacity-dependent extension bits of the loaded unit handles, two bits per slot
     */
    public int getLoadedUnitHandleExtensionBits() {
        return this.loadedUnitHandleExtensionBits & WORD_MASK;
    }

    /**
     * Sets the extension bits of the loaded unit handles.
     *
     * @param value the bits, truncated to a WORD
     */
    public void setLoadedUnitHandleExtensionBits(int value) {
        this.loadedUnitHandleExtensionBits = (short) value;
    }

    /**
     * @return the remaining Spider Mine count
     */
    public int getSpiderMineCount() {
        return this.pool.getSpiderMineCounts().get(this.getIndex());
    }

    /**
     * Sets the remaining Spider Mine count.
     *
     * @param count the count, truncated to a BYTE
     */
    public void setSpiderMineCount(int count) {
        this.pool.getSpiderMineCounts().set(this.getIndex(), count & BYTE_MASK);
    }

    /**
     * @return the Carrier or Reaver of this Interceptor or Scarab, or {@code null}
     */
    public @Nullable Unit getHangarParent() {
        return this.hangarParent;
    }

    /**
     * Sets the Carrier or Reaver of this Interceptor or Scarab.
     *
     * @param parent the parent, or {@code null}
     */
    public void setHangarParent(@Nullable Unit parent) {
        this.hangarParent = parent;
    }

    /**
     * @param outside whether to read the list of units outside the hangar
     * @return the first Interceptor or Scarab inside or outside the hangar, or {@code null}
     */
    public @Nullable Unit getFirstHangarUnit(boolean outside) {
        return outside ? this.firstOutsideHangarUnit : this.firstInsideHangarUnit;
    }

    /**
     * Sets the first Interceptor or Scarab inside or outside the hangar.
     *
     * @param outside whether to set the list of units outside the hangar
     * @param unit    the first unit, or {@code null}
     */
    public void setFirstHangarUnit(boolean outside, @Nullable Unit unit) {
        if (outside) {
            this.firstOutsideHangarUnit = unit;
        } else {
            this.firstInsideHangarUnit = unit;
        }
    }

    /**
     * @param outside whether to count units outside the hangar
     * @return the number of Interceptors or Scarabs inside or outside
     */
    public int getHangarCount(boolean outside) {
        return outside ? this.pool.getOutsideHangarCounts().get(this.getIndex()) : this.pool.getInsideHangarCounts().get(this.getIndex());
    }

    /**
     * Sets the number of Interceptors or Scarabs inside or outside the hangar.
     *
     * @param outside whether to set the count of units outside the hangar
     * @param count   the count, truncated to a BYTE
     */
    public void setHangarCount(boolean outside, int count) {
        if (outside) {
            this.pool.getOutsideHangarCounts().set(this.getIndex(), count & BYTE_MASK);
        } else {
            this.pool.getInsideHangarCounts().set(this.getIndex(), count & BYTE_MASK);
        }
    }

    /**
     * @return the previous unit in the hangar list, or {@code null}
     */
    public @Nullable Unit getPreviousHangarUnit() {
        return this.previousHangarUnit;
    }

    /**
     * Sets the previous unit in the hangar list.
     *
     * @param unit the unit, or {@code null}
     */
    public void setPreviousHangarUnit(@Nullable Unit unit) {
        this.previousHangarUnit = unit;
    }

    /**
     * @return the next unit in the hangar list, or {@code null}
     */
    public @Nullable Unit getNextHangarUnit() {
        return this.nextHangarUnit;
    }

    /**
     * Sets the next unit in the hangar list.
     *
     * @param unit the unit, or {@code null}
     */
    public void setNextHangarUnit(@Nullable Unit unit) {
        this.nextHangarUnit = unit;
    }

    /**
     * @return {@code true} if this Interceptor or Scarab is outside its hangar, {@code false} otherwise
     */
    public boolean isHangarOutside() {
        return this.getHangarOutsideByte() != 0;
    }

    /**
     * Sets whether this Interceptor or Scarab is outside its hangar.
     *
     * @param outside whether the unit is outside
     */
    public void setHangarOutside(boolean outside) {
        this.setHangarOutsideByte(outside ? 1 : 0);
    }

    /**
     * @return the raw outside byte of an Interceptor or Scarab
     */
    public int getHangarOutsideByte() {
        return this.pool.getHangarOutsideBytes().get(this.getIndex());
    }

    /**
     * Sets the raw outside byte of an Interceptor or Scarab.
     *
     * @param value the byte in the low eight bits
     */
    public void setHangarOutsideByte(int value) {
        this.pool.getHangarOutsideBytes().set(this.getIndex(), value & BYTE_MASK);
    }

    /**
     * @return the stored spare byte of a Carrier or Reaver
     */
    public int getHangarSpareByte() {
        return this.pool.getHangarSpareBytes().get(this.getIndex());
    }

    /**
     * Sets the stored spare byte of a Carrier or Reaver.
     *
     * @param value the byte in the low eight bits
     */
    public void setHangarSpareByte(int value) {
        this.pool.getHangarSpareBytes().set(this.getIndex(), value & BYTE_MASK);
    }

    /**
     * @return the stored spare WORD after the unit type
     */
    public int getSpareWord() {
        return this.spareWord;
    }

    /**
     * Sets the stored spare WORD after the unit type.
     *
     * @param value the WORD in the low sixteen bits
     */
    public void setSpareWord(int value) {
        this.spareWord = value & WORD_MASK;
    }


    /**
     * @return the unsigned Defensive Matrix hit points WORD
     */
    public int getDefensiveMatrixHealth() {
        return this.defensiveMatrixHealth & WORD_MASK;
    }

    /**
     * Sets the Defensive Matrix hit points.
     *
     * @param value the hit points, truncated to a WORD
     */
    public void setDefensiveMatrixHealth(int value) {
        this.defensiveMatrixHealth = (short) value;
    }

    /**
     * @return the remaining Defensive Matrix time
     */
    public int getDefensiveMatrixTimer() {
        return this.defensiveMatrixTimer;
    }

    /**
     * Sets the remaining Defensive Matrix time.
     *
     * @param value the timer, truncated to a BYTE
     */
    public void setDefensiveMatrixTimer(int value) {
        this.defensiveMatrixTimer = value & BYTE_MASK;
    }

    /**
     * @return the remaining Lockdown time
     */
    public int getLockdownTimer() {
        return this.lockdownTimer;
    }

    /**
     * Sets the remaining Lockdown time.
     *
     * @param value the timer, truncated to a BYTE
     */
    public void setLockdownTimer(int value) {
        this.lockdownTimer = value & BYTE_MASK;
    }

    /**
     * @return the remaining Irradiate time
     */
    public int getIrradiateTimer() {
        return this.irradiateTimer;
    }

    /**
     * Sets the remaining Irradiate time.
     *
     * @param value the timer, truncated to a BYTE
     */
    public void setIrradiateTimer(int value) {
        this.irradiateTimer = value & BYTE_MASK;
    }

    /**
     * @return the unit that irradiated this unit, or {@code null}
     */
    public @Nullable Unit getIrradiatedBy() {
        return this.irradiatedBy;
    }

    /**
     * Sets the unit that irradiated this unit.
     *
     * @param unit the caster, or {@code null}
     */
    public void setIrradiatedBy(@Nullable Unit unit) {
        this.irradiatedBy = unit;
    }

    /**
     * @return the player credited with irradiation damage
     */
    public int getIrradiateOwner() {
        return this.irradiateOwner;
    }

    /**
     * Sets the player credited with irradiation damage.
     *
     * @param owner the player ID
     */
    public void setIrradiateOwner(int owner) {
        this.irradiateOwner = owner;
    }

    /**
     * @return the remaining Stasis Field time
     */
    public int getStasisTimer() {
        return this.stasisTimer;
    }

    /**
     * Sets the remaining Stasis Field time.
     *
     * @param value the timer, truncated to a BYTE
     */
    public void setStasisTimer(int value) {
        this.stasisTimer = value & BYTE_MASK;
    }

    /**
     * @return the remaining Plague time
     */
    public int getPlagueTimer() {
        return this.plagueTimer;
    }

    /**
     * Sets the remaining Plague time.
     *
     * @param value the timer, truncated to a BYTE
     */
    public void setPlagueTimer(int value) {
        this.plagueTimer = value & BYTE_MASK;
    }

    /**
     * @return the remaining Psionic Storm time
     */
    public int getStormTimer() {
        return this.stormTimer;
    }

    /**
     * Sets the remaining Psionic Storm time.
     *
     * @param value the timer, truncated to a BYTE
     */
    public void setStormTimer(int value) {
        this.stormTimer = value & BYTE_MASK;
    }

    /**
     * @return the remaining Maelstrom time
     */
    public int getMaelstromTimer() {
        return this.maelstromTimer;
    }

    /**
     * Sets the remaining Maelstrom time.
     *
     * @param value the timer, truncated to a BYTE
     */
    public void setMaelstromTimer(int value) {
        this.maelstromTimer = value & BYTE_MASK;
    }

    /**
     * @return the Acid Spore count, shown as "Acid Spores (n)"
     */
    public int getAcidSporeCount() {
        return this.acidSporeCount;
    }

    /**
     * Sets the Acid Spore count.
     *
     * @param value the count, truncated to a BYTE
     */
    public void setAcidSporeCount(int value) {
        this.acidSporeCount = value & BYTE_MASK;
    }

    /**
     * @param index the Acid Spore slot from {@code 0} through {@code 8}
     * @return the unsigned timer byte
     * @throws ArrayIndexOutOfBoundsException if the index is outside {@code 0..8}
     */
    public int getAcidSporeTimer(int index) {
        return this.acidSporeTimers[index] & BYTE_MASK;
    }

    /**
     * Sets the timer of an Acid Spore slot.
     *
     * @param index the Acid Spore slot from {@code 0} through {@code 8}
     * @param value the timer narrowed to one byte
     * @throws ArrayIndexOutOfBoundsException if the index is outside {@code 0..8}
     */
    public void setAcidSporeTimer(int index, int value) {
        this.acidSporeTimers[index] = (byte) value;
    }


    /**
     * @return the previous unit in the cloaked unit list, or {@code null}
     */
    public @Nullable Unit getPreviousCloakedUnit() {
        return this.previousCloakedUnit;
    }

    /**
     * Sets the previous unit in the cloaked unit list.
     *
     * @param unit the unit, or {@code null}
     */
    public void setPreviousCloakedUnit(@Nullable Unit unit) {
        this.previousCloakedUnit = unit;
    }

    /**
     * @return the next unit in the cloaked unit list, or {@code null}
     */
    public @Nullable Unit getNextCloakedUnit() {
        return this.nextCloakedUnit;
    }

    /**
     * Sets the next unit in the cloaked unit list.
     *
     * @param unit the unit, or {@code null}
     */
    public void setNextCloakedUnit(@Nullable Unit unit) {
        this.nextCloakedUnit = unit;
    }

    /**
     * @return {@code true} if the unit is in the cloaked unit list, {@code false} otherwise
     */
    public boolean isCloakedLinked() {
        return this.cloakedLinked;
    }

    /**
     * Sets whether the unit is in the cloaked unit list.
     *
     * @param linked whether the unit is linked
     */
    public void setCloakedLinked(boolean linked) {
        this.cloakedLinked = linked;
    }

    /**
     * @return the previous unit in the psi provider list, or {@code null}
     */
    public @Nullable Unit getPreviousPsiProvider() {
        return this.previousPsiProvider;
    }

    /**
     * Sets the previous unit in the psi provider list.
     *
     * @param value the unit, or {@code null}
     */
    public void setPreviousPsiProvider(@Nullable Unit value) {
        this.previousPsiProvider = value;
    }

    /**
     * @return the next unit in the psi provider list, or {@code null}
     */
    public @Nullable Unit getNextPsiProvider() {
        return this.nextPsiProvider;
    }

    /**
     * Sets the next unit in the psi provider list.
     *
     * @param value the unit, or {@code null}
     */
    public void setNextPsiProvider(@Nullable Unit value) {
        this.nextPsiProvider = value;
    }

    /**
     * @return the pylon's psi field sprite (kept hidden by the simulation), or {@code null} if the unit provides none
     */
    public @Nullable Sprite getPsiFieldSprite() {
        return this.psiFieldSprite;
    }

    /**
     * Sets the pylon's psi field sprite.
     *
     * @param value the sprite, or {@code null}
     */
    public void setPsiFieldSprite(@Nullable Sprite value) {
        this.psiFieldSprite = value;
    }

    /**
     * @return the Nuclear Missile stored in this Nuclear Silo, or {@code null}
     */
    public @Nullable Unit getSiloNuke() {
        return this.siloNuke;
    }

    /**
     * Sets the Nuclear Missile stored in this Nuclear Silo.
     *
     * @param nuke the missile, or {@code null}
     */
    public void setSiloNuke(@Nullable Unit nuke) {
        this.siloNuke = nuke;
    }

    /**
     * @return {@code true} if this Nuclear Silo's missile is completed and ready to launch, {@code false} otherwise
     */
    public boolean isSiloReady() {
        return this.getSiloReadyDword() != 0;
    }

    /**
     * Sets whether this Nuclear Silo's missile is ready to launch.
     *
     * @param ready whether the missile is ready
     */
    public void setSiloReady(boolean ready) {
        this.setSiloReadyDword(ready ? 1 : 0);
    }

    /**
     * @return the raw ready DWORD of a Nuclear Silo
     */
    public int getSiloReadyDword() {
        return this.pool.getSiloReadyDwords().get(this.getIndex());
    }

    /**
     * Sets the raw ready DWORD of a Nuclear Silo.
     *
     * @param value the DWORD
     */
    public void setSiloReadyDword(int value) {
        this.pool.getSiloReadyDwords().set(this.getIndex(), value);
    }

    /**
     * @return the nuclear target dot of this Ghost, or {@code null}
     */
    public @Nullable SpriteEffect getNukeDot() {
        return this.nukeDot;
    }

    /**
     * Sets the nuclear target dot of this Ghost.
     *
     * @param dot the target dot, or {@code null}
     */
    public void setNukeDot(@Nullable SpriteEffect dot) {
        this.nukeDot = dot;
    }

    /**
     * @return the other end of this Nydus Canal, or {@code null}
     */
    public @Nullable Unit getNydusExit() {
        return this.nydusExit;
    }

    /**
     * Sets the other end of this Nydus Canal.
     *
     * @param exit the exit, or {@code null}
     */
    public void setNydusExit(@Nullable Unit exit) {
        this.nydusExit = exit;
    }


    /**
     * Clears the starting-attack movement flag ({@link MovementFlags#STARTING_ATTACK}), as the normal-mode {@code gotorepeatattk} branch does.
     */
    public void clearRepeatAttackMovementFlag() {
        this.setMovementFlags(this.getMovementFlags() & ~MovementFlags.STARTING_ATTACK);
    }

    /**
     * Sets the ready-to-attack status flag ({@link UnitStatusFlags#READY_TO_ATTACK}) of the unit and of its subunit.
     */
    public void markReadyToAttack() {
        this.setStatus(this.getStatus() | UnitStatusFlags.READY_TO_ATTACK);

        Unit subunit = this.getSubunit();

        if (subunit != null) {
            subunit.setStatus(subunit.getStatus() | UnitStatusFlags.READY_TO_ATTACK);
        }
    }

    /**
     * @return {@code true} if the unit's Die order has run ({@link ScrOrderIds#DIE} in the state {@link #DIE_STATE_DESTROYED}), {@code false} otherwise
     */
    public boolean isDestroyed() {
        return this.getPrimaryOrder() == ScrOrderIds.DIE && this.getPrimaryOrderState() == DIE_STATE_DESTROYED;
    }

    /**
     * Returns the net speed modifier of the unit: one each for a speed increase and for the speed upgrade, minus one for a speed decrease.
     *
     * @return the net speed modifier, from {@code -1} through {@code 2}
     */
    public int getSpeedModifier() {
        return (this.getSpeedIncreaseByte() != 0 ? 1 : 0) + ((this.getStatus() & UnitStatusFlags.SPEED_UPGRADE) != 0 ? 1 : 0) -
               (this.getSpeedDecreaseByte() != 0 ? 1 : 0);
    }

    /**
     * Calculates the speed after the unit's speed modifiers ({@link #getSpeedModifier}) without changing unit state.
     * <p>
     * A net speed bonus sets a type with an upgraded top speed ({@link ScrUnitTypeView#getUpgradedTopSpeed}) to it and raises any other speed by
     * half, to at least the minimum upgraded speed; a net slowing halves the speed.
     *
     * @param units the unit type table
     * @param speed the input speed DWORD in units with eight fraction bits
     * @return the modified DWORD speed, preserving unsigned shifts and overflow
     */
    public int calculateModifiedMovementSpeed(@NotNull ScrUnitTypeView units, int speed) {
        int modifier = this.getSpeedModifier();

        if (modifier > 0) {
            int upgradedSpeed = units.getUpgradedTopSpeed(this.getUnitId() & WORD_MASK);

            if (upgradedSpeed != 0) {
                speed = upgradedSpeed;
            } else {
                speed += speed >>> 1;

                if (Integer.compareUnsigned(speed, MINIMUM_UPGRADED_SPEED) < 0) {
                    speed = MINIMUM_UPGRADED_SPEED;
                }
            }
        } else if (modifier < 0) {
            speed >>>= 1;
        }

        return speed;
    }

    /**
     * Applies the movement speed modifiers and stores the next speed; a changed speed also updates the current speed and
     * the velocity along the movement direction.
     *
     * @param units the unit type table
     * @param speed the input DWORD speed in units with eight fraction bits
     */
    public void applyIscriptMovementSpeed(@NotNull ScrUnitTypeView units, int speed) {
        speed = this.calculateModifiedMovementSpeed(units, speed);

        this.setNextSpeed(speed);

        if (this.getCurrentSpeed() != speed) {
            long velocity = FlingyMotionUtils.calculateDirectionVector(this.getMovementDirection(), speed);

            this.setCurrentSpeed(speed);
            this.setVelocityX((int) velocity);
            this.setVelocityY((int) (velocity >>> FlingyMotionUtils.DIRECTION_VECTOR_VERTICAL_SHIFT));
        }
    }

    /**
     * Turns the unit to the given direction at once: sets the heading, target heading and movement direction, rescales
     * the velocity, points the direction target at the sprite position and turns the sprite.
     *
     * @param game      the game supplying the special image offsets
     * @param direction the direction DWORD, stored and indexed as an unsigned byte
     * @throws UnsupportedOperationException if the unit has no sprite
     */
    public void resetDirection(@NotNull Game game, int direction) {
        int directionByte = direction & BYTE_MASK;

        this.setTargetHeading(directionByte);
        this.setHeading(directionByte);
        this.setMovementDirection(directionByte);

        long velocity = FlingyMotionUtils.calculateDirectionVector(directionByte, this.getCurrentSpeed());

        this.setVelocityX((int) velocity);
        this.setVelocityY((int) (velocity >>> FlingyMotionUtils.DIRECTION_VECTOR_VERTICAL_SHIFT));

        if (this.sprite == null) {
            throw Exceptions.newUnsupportedOperationException("{0}", "Native direction reset requires the unit's sprite.");
        }

        int vertical = (short) this.sprite.getY();
        int horizontal = (short) this.sprite.getX();

        if ((short) this.getDirectionTargetX() != horizontal || (short) this.getDirectionTargetY() != vertical) {
            this.setDirectionTargetX(horizontal);
            this.setDirectionTargetY(vertical);
        }

        IscriptExecutor.applySpriteDirection(game, this.sprite, direction);
    }

    /**
     * Tests whether the sprite position and destination share a terrain component.
     *
     * @param regions   the established terrain regions
     * @param positionX the destination horizontal coordinate
     * @param positionY the destination vertical coordinate
     * @return {@code true} if the positions share a component, {@code false} otherwise
     * @throws UnsupportedOperationException if the unit has no sprite
     */
    public boolean sharesTerrainComponent(@NotNull TerrainRegions regions, int positionX, int positionY) {
        Sprite sprite = this.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("{0}", "Terrain component lookup requires a sprite.");
        }

        return regions.sharesComponent((short) sprite.getX(), (short) sprite.getY(), positionX, positionY);
    }

    /**
     * Resets the role state that only a new unit clears and a morph keeps: the resource amount with its Larva side and resource animation aliases,
     * the resource area, the computer claim, the gathering byte and worker queue of a resource, the gather target and queue links of a worker, the
     * powerup carrier, the Nuclear Silo missile, the nuke dot and the Nydus exit.
     */
    public void resetResourceAndLinkState() {
        this.firstGatheringWorker    = null;
        this.lastGatheringWorker     = null;
        this.previousGatheringWorker = null;
        this.nextGatheringWorker     = null;
        this.gatherTarget            = null;
        this.powerupCarrier          = null;
        this.siloNuke                = null;
        this.nukeDot                 = null;
        this.nydusExit               = null;

        this.setSiloReadyDword(0);
        this.setResources(0);
        this.setResourceAreaIndex(0);
        this.setResourceClaimByte(0);
        this.setResourceBeingGatheredByte(0);
        this.setSecondLarvaSideCount(0);
        this.setThirdLarvaSideCount(0);
        this.setFourthLarvaSideCount(0);
    }

    /**
     * Mixes this part of the simulation state into the hash: the SC:R unit hash of {@link #computeStateHash(long)},
     * started from {@code 0}.
     *
     * @param hash the hash being computed
     */
    @Override
    public void contributeStateHash(@NotNull StateHash hash) {
        hash.mixLong(this.computeStateHash(0L));
    }

    /**
     * Mixes the selected simulation fields and relationships into a domain-state hash without allocating.
     *
     * @param hash the preceding state hash
     * @return the hash including this unit's semantic state
     */
    public long computeStateHash(long hash) {
        hash = STATE_HASH_MULTIPLIER * hash + (this.previousPsiProvider == null ? 0 : this.previousPsiProvider.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.nextPsiProvider == null ? 0 : this.nextPsiProvider.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.psiFieldSprite == null ? 0 : this.psiFieldSprite.getPoolIndex() + 1);
        hash = STATE_HASH_MULTIPLIER * hash + this.unitId;
        hash = STATE_HASH_MULTIPLIER * hash + this.owner;
        hash = STATE_HASH_MULTIPLIER * hash + this.getPositionX();
        hash = STATE_HASH_MULTIPLIER * hash + this.getPositionY();
        hash = STATE_HASH_MULTIPLIER * hash + this.getHitPoints();
        hash = STATE_HASH_MULTIPLIER * hash + this.getShieldPoints();
        hash = STATE_HASH_MULTIPLIER * hash + this.getResources();
        hash = STATE_HASH_MULTIPLIER * hash + this.getTargetResourceX();
        hash = STATE_HASH_MULTIPLIER * hash + this.getTargetResourceY();
        hash = STATE_HASH_MULTIPLIER * hash + (this.targetResourceUnit == null ? 0 : this.targetResourceUnit.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + this.getResourceBeingGatheredByte();
        hash = STATE_HASH_MULTIPLIER * hash + (this.gatherTarget == null ? 0 : this.gatherTarget.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + this.getGatheringResourceByte();
        hash = STATE_HASH_MULTIPLIER * hash + this.getCarriedResourceAmount();
        hash = STATE_HASH_MULTIPLIER * hash + (this.firstGatheringWorker == null ? 0 : this.firstGatheringWorker.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.lastGatheringWorker == null ? 0 : this.lastGatheringWorker.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.previousGatheringWorker == null ? 0 : this.previousGatheringWorker.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.nextGatheringWorker == null ? 0 : this.nextGatheringWorker.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + this.getHeading();
        hash = STATE_HASH_MULTIPLIER * hash + this.getNextSpeed();
        hash = STATE_HASH_MULTIPLIER * hash + this.orderSignal;
        hash = STATE_HASH_MULTIPLIER * hash + this.status;
        hash = STATE_HASH_MULTIPLIER * hash + this.speedIncreaseByte;
        hash = STATE_HASH_MULTIPLIER * hash + this.speedDecreaseByte;
        hash = STATE_HASH_MULTIPLIER * hash + this.getMovementDirection();
        hash = STATE_HASH_MULTIPLIER * hash + this.getDesiredMovementDirection();
        hash = STATE_HASH_MULTIPLIER * hash + this.getMovementFlags();
        hash = STATE_HASH_MULTIPLIER * hash + this.getTopSpeed();
        hash = STATE_HASH_MULTIPLIER * hash + this.getCurrentSpeed();
        hash = STATE_HASH_MULTIPLIER * hash + this.getVelocityX();
        hash = STATE_HASH_MULTIPLIER * hash + this.getVelocityY();
        hash = STATE_HASH_MULTIPLIER * hash + this.getIndex();
        hash = STATE_HASH_MULTIPLIER * hash + this.getMovementDestinationX();
        hash = STATE_HASH_MULTIPLIER * hash + this.getMovementDestinationY();
        hash = STATE_HASH_MULTIPLIER * hash + this.getMovementWaypointX();
        hash = STATE_HASH_MULTIPLIER * hash + this.getMovementWaypointY();
        hash = STATE_HASH_MULTIPLIER * hash + this.getDirectionTargetX();
        hash = STATE_HASH_MULTIPLIER * hash + this.getDirectionTargetY();
        hash = STATE_HASH_MULTIPLIER * hash + this.getTurnRate();
        hash = STATE_HASH_MULTIPLIER * hash + this.getTargetHeading();
        hash = STATE_HASH_MULTIPLIER * hash + this.flingyType;
        hash = STATE_HASH_MULTIPLIER * hash + this.getMovementControl();
        hash = STATE_HASH_MULTIPLIER * hash + this.getFixedPositionX();
        hash = STATE_HASH_MULTIPLIER * hash + this.getFixedPositionY();
        hash = STATE_HASH_MULTIPLIER * hash + this.getAcceleration();
        hash = STATE_HASH_MULTIPLIER * hash + this.primaryOrder;
        hash = STATE_HASH_MULTIPLIER * hash + this.primaryOrderState;
        hash = STATE_HASH_MULTIPLIER * hash + this.orderTargetUnitType;
        hash = STATE_HASH_MULTIPLIER * hash + this.cooldownTimers;
        hash = STATE_HASH_MULTIPLIER * hash + this.getOrderTargetX();
        hash = STATE_HASH_MULTIPLIER * hash + this.getOrderTargetY();
        hash = STATE_HASH_MULTIPLIER * hash + this.primaryOrderCountdown;
        hash = STATE_HASH_MULTIPLIER * hash + this.visibilityUpdateCountdown;
        hash = STATE_HASH_MULTIPLIER * hash + this.movementState;
        hash = STATE_HASH_MULTIPLIER * hash + this.firstBuildQueueType;
        hash = STATE_HASH_MULTIPLIER * hash + this.secondBuildQueueType;
        hash = STATE_HASH_MULTIPLIER * hash + this.thirdBuildQueueType;
        hash = STATE_HASH_MULTIPLIER * hash + this.fourthBuildQueueType;
        hash = STATE_HASH_MULTIPLIER * hash + this.fifthBuildQueueType;
        hash = STATE_HASH_MULTIPLIER * hash + this.energy;
        hash = STATE_HASH_MULTIPLIER * hash + this.buildQueueIndex;
        hash = STATE_HASH_MULTIPLIER * hash + this.secondaryOrder;
        hash = STATE_HASH_MULTIPLIER * hash + this.damageOverlayCount;
        hash = STATE_HASH_MULTIPLIER * hash + this.constructionHitPointIncrement;
        hash = STATE_HASH_MULTIPLIER * hash + this.constructionShieldIncrement;
        hash = STATE_HASH_MULTIPLIER * hash + this.remainingBuildTime;
        hash = STATE_HASH_MULTIPLIER * hash + this.getResearchType();
        hash = STATE_HASH_MULTIPLIER * hash + this.getUpgradeType();
        hash = STATE_HASH_MULTIPLIER * hash + this.getLarvaSpawnCountdown();
        hash = STATE_HASH_MULTIPLIER * hash + this.getSecondLarvaSideCount();
        hash = STATE_HASH_MULTIPLIER * hash + this.getThirdLarvaSideCount();
        hash = STATE_HASH_MULTIPLIER * hash + this.getFourthLarvaSideCount();
        hash = STATE_HASH_MULTIPLIER * hash + this.secondaryOrderState;

        if (this.secondaryOrderPosition != 0) {
            hash = STATE_HASH_MULTIPLIER * hash + this.secondaryOrderPosition;
        }

        if (this.classicEventBytes != 0) {
            hash = STATE_HASH_MULTIPLIER * hash + this.classicEventBytes;
        }

        hash = STATE_HASH_MULTIPLIER * hash + this.movementDestinationCountdown;
        hash = STATE_HASH_MULTIPLIER * hash + this.pathRecalculationCountdown;
        hash = STATE_HASH_MULTIPLIER * hash + this.pathCollisionFlags;
        hash = STATE_HASH_MULTIPLIER * hash + this.terrainCollisionLeft;
        hash = STATE_HASH_MULTIPLIER * hash + this.terrainCollisionTop;
        hash = STATE_HASH_MULTIPLIER * hash + this.terrainCollisionRight;
        hash = STATE_HASH_MULTIPLIER * hash + this.terrainCollisionBottom;
        hash = STATE_HASH_MULTIPLIER * hash + this.remainingLifetime;
        hash = STATE_HASH_MULTIPLIER * hash + this.defensiveMatrixHealth;
        hash = STATE_HASH_MULTIPLIER * hash + this.getResourceAreaIndex();
        hash = STATE_HASH_MULTIPLIER * hash + this.loadedUnitHandleExtensionBits;
        hash = STATE_HASH_MULTIPLIER * hash + this.statusCycleElapsed;
        hash = STATE_HASH_MULTIPLIER * hash + this.getCreepTimer();
        hash = STATE_HASH_MULTIPLIER * hash + this.carryingFlags;
        hash = STATE_HASH_MULTIPLIER * hash + this.lastAttackingPlayer;
        hash = STATE_HASH_MULTIPLIER * hash + this.wireframeRandomizer;
        hash = STATE_HASH_MULTIPLIER * hash + this.cloakCounter;
        hash = STATE_HASH_MULTIPLIER * hash + this.parasiteFlags;
        hash = STATE_HASH_MULTIPLIER * hash + this.blindnessState;
        hash = STATE_HASH_MULTIPLIER * hash + this.previousVisibleHitPoints;
        hash = STATE_HASH_MULTIPLIER * hash + this.previousUnitType;
        hash = STATE_HASH_MULTIPLIER * hash + (this.rallyTargetUnit == null ? 0 : this.rallyTargetUnit.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + this.rallyPositionX;
        hash = STATE_HASH_MULTIPLIER * hash + this.rallyPositionY;
        hash = STATE_HASH_MULTIPLIER * hash + this.detectionFlags;
        hash = STATE_HASH_MULTIPLIER * hash + this.getRepulsionFlags();
        hash = STATE_HASH_MULTIPLIER * hash + this.getRepulsionDirection();
        hash = STATE_HASH_MULTIPLIER * hash + this.getRepulsionCellX();
        hash = STATE_HASH_MULTIPLIER * hash + this.getRepulsionCellY();
        hash = STATE_HASH_MULTIPLIER * hash + this.currentButtonSet;
        hash = STATE_HASH_MULTIPLIER * hash + this.highlightedOrderCount;
        hash = STATE_HASH_MULTIPLIER * hash + this.userActionFlags;
        hash = STATE_HASH_MULTIPLIER * hash + this.spellTargetFlags;
        hash = STATE_HASH_MULTIPLIER * hash + this.healingState;
        hash = STATE_HASH_MULTIPLIER * hash + this.defensiveMatrixTimer;
        hash = STATE_HASH_MULTIPLIER * hash + this.lockdownTimer;
        hash = STATE_HASH_MULTIPLIER * hash + this.irradiateTimer;
        hash = STATE_HASH_MULTIPLIER * hash + this.stasisTimer;
        hash = STATE_HASH_MULTIPLIER * hash + this.plagueTimer;
        hash = STATE_HASH_MULTIPLIER * hash + this.stormTimer;
        hash = STATE_HASH_MULTIPLIER * hash + (this.irradiatedBy == null ? 0 : this.irradiatedBy.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + this.irradiateOwner;
        hash = STATE_HASH_MULTIPLIER * hash + this.nextHitNearTargetIndex;
        hash = STATE_HASH_MULTIPLIER * hash + (this.siloNuke == null ? 0 : this.siloNuke.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + this.getSiloReadyDword();
        hash = STATE_HASH_MULTIPLIER * hash + (this.nydusExit == null ? 0 : this.nydusExit.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.nukeDot == null ? 0 : this.nukeDot.getPoolIndex() + 1);
        hash = STATE_HASH_MULTIPLIER * hash + this.maelstromTimer;
        hash = STATE_HASH_MULTIPLIER * hash + this.acidSporeCount;
        hash = STATE_HASH_MULTIPLIER * hash + this.airStrength;
        hash = STATE_HASH_MULTIPLIER * hash + this.groundStrength;
        hash = STATE_HASH_MULTIPLIER * hash + this.getMinimumHorizontalEdgeIndex();
        hash = STATE_HASH_MULTIPLIER * hash + this.getMaximumHorizontalEdgeIndex();
        hash = STATE_HASH_MULTIPLIER * hash + this.getMinimumVerticalEdgeIndex();
        hash = STATE_HASH_MULTIPLIER * hash + this.getMaximumVerticalEdgeIndex();
        hash = STATE_HASH_MULTIPLIER * hash + this.pathIndex;
        hash = STATE_HASH_MULTIPLIER * hash + this.getSpiderMineCount();
        hash = STATE_HASH_MULTIPLIER * hash + (this.firstInsideHangarUnit == null ? 0 : this.firstInsideHangarUnit.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.firstOutsideHangarUnit == null ? 0 : this.firstOutsideHangarUnit.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + this.pool.getInsideHangarCounts().get(this.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + this.pool.getOutsideHangarCounts().get(this.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.hangarParent == null ? 0 : this.hangarParent.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.nextHangarUnit == null ? 0 : this.nextHangarUnit.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + this.getHangarOutsideByte();
        hash = STATE_HASH_MULTIPLIER * hash + (this.nextCloakedUnit == null ? 0 : this.nextCloakedUnit.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.cloakedLinked ? 1 : 0);

        for (int value : this.loadedUnitHandles) {
            hash = STATE_HASH_MULTIPLIER * hash + value;
        }

        for (int value : this.acidSporeTimers) {
            hash = STATE_HASH_MULTIPLIER * hash + value;
        }

        hash = STATE_HASH_MULTIPLIER * hash + (this.sprite == null ? -1 : this.sprite.getPoolIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.subunit == null ? 0 : this.subunit.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.getOrderTargetUnit() == null ? 0 : this.getOrderTargetUnit().getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.parentUnit == null ? 0 : this.parentUnit.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.movementTargetUnit == null ? 0 : this.movementTargetUnit.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.getFirstOrder() == null ? -1 : this.getFirstOrder().getPoolIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.getLastOrder() == null ? -1 : this.getLastOrder().getPoolIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.automaticTargetUnit == null ? 0 : this.automaticTargetUnit.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.currentBuildUnit == null ? 0 : this.currentBuildUnit.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.carriedPowerup == null ? 0 : this.carriedPowerup.getIndex());
        hash = STATE_HASH_MULTIPLIER * hash + (this.powerupCarrier == null ? 0 : this.powerupCarrier.getIndex());

        if (this.computerRecord != null) {
            hash = this.computerRecord.computeStateHash(hash);
        }

        return hash;
    }

    /**
     * Zeroes the fields of the slot, as a saved game's unit reader clears the whole unit array before it restores the saved
     * units; a cleared slot has no path ({@code -1}). The union columns of the slot are zeroed by {@link UnitPool#clear()}.
     */
    void clearStorage() {
        this.nydusExit           = null;
        this.nukeDot             = null;
        this.siloNuke            = null;
        this.psiFieldSprite      = null;
        this.nextPsiProvider     = null;
        this.previousPsiProvider = null;

        this.cloakedLinked       = false;
        this.nextCloakedUnit     = null;
        this.previousCloakedUnit = null;

        Arrays.fill(this.acidSporeTimers, (byte) 0);

        this.acidSporeCount        = 0;
        this.maelstromTimer        = 0;
        this.stormTimer            = 0;
        this.plagueTimer           = 0;
        this.stasisTimer           = 0;
        this.irradiateOwner        = 0;
        this.irradiatedBy          = null;
        this.irradiateTimer        = 0;
        this.lockdownTimer         = 0;
        this.defensiveMatrixTimer  = 0;
        this.defensiveMatrixHealth = 0;

        this.spareWord                     = 0;
        this.nextHangarUnit                = null;
        this.previousHangarUnit            = null;
        this.firstOutsideHangarUnit        = null;
        this.firstInsideHangarUnit         = null;
        this.hangarParent                  = null;
        this.loadedUnitHandleExtensionBits = 0;

        Arrays.fill(this.loadedUnitHandles, (short) 0);

        this.powerupCarrier          = null;
        this.carriedPowerup          = null;
        this.carryingFlags           = 0;
        this.gatherTarget            = null;
        this.targetResourceUnit      = null;
        this.nextGatheringWorker     = null;
        this.previousGatheringWorker = null;
        this.lastGatheringWorker     = null;
        this.firstGatheringWorker    = null;

        this.healingState                  = 0;
        this.addon                         = null;
        this.constructionShieldIncrement   = 0;
        this.constructionHitPointIncrement = 0;
        this.remainingBuildTime            = 0;
        this.currentBuildUnit              = null;
        this.fifthBuildQueueType           = 0;
        this.fourthBuildQueueType          = 0;
        this.thirdBuildQueueType           = 0;
        this.secondBuildQueueType          = 0;
        this.firstBuildQueueType           = 0;
        this.buildQueueIndex               = 0;

        this.spellTargetFlags       = 0;
        this.nextHitNearTargetIndex = 0;
        this.groundStrength         = 0;
        this.airStrength            = 0;
        this.automaticTargetUnit    = null;
        this.cooldownTimers         = 0;

        this.localEventBytes = 0;
        this.skin            = 0;
        this.computerRecord  = null;
        this.rallyTargetUnit = null;
        this.rallyPositionY  = 0;
        this.rallyPositionX  = 0;
        this.resetOrders();
        this.orderTargetUnitType    = 0;
        this.orderSignal            = 0;
        this.alertMark              = 0;
        this.classicUnusedByte      = 0;
        this.classicEventBytes      = 0;
        this.secondaryOrderPosition = 0;
        this.secondaryOrderState    = 0;
        this.secondaryOrder         = 0;
        this.primaryOrderCountdown  = 0;
        this.primaryOrderState      = 0;
        this.setPrimaryOrder(0);

        this.setMaximumVerticalEdgeIndex(0);
        this.setMinimumVerticalEdgeIndex(0);
        this.setMaximumHorizontalEdgeIndex(0);
        this.setMinimumHorizontalEdgeIndex(0);
        this.setRepulsionCellY(0);
        this.setRepulsionCellX(0);
        this.setRepulsionDirection(0);
        this.setRepulsionFlags(0);

        this.terrainCollisionBottom       = 0;
        this.terrainCollisionRight        = 0;
        this.terrainCollisionTop          = 0;
        this.terrainCollisionLeft         = 0;
        this.pathCollisionFlags           = 0;
        this.pathRecalculationCountdown   = 0;
        this.pathIndex                    = -1;
        this.movementDestinationCountdown = 0;
        this.movementTargetUnit           = null;

        this.speedDecreaseByte = 0;
        this.speedIncreaseByte = 0;
        this.movementState     = 0;
        this.flingyType        = 0;
        this.resetMotion();

        this.blindnessState            = 0;
        this.parasiteFlags             = 0;
        this.cloakCounter              = 0;
        this.detectionFlags            = 0;
        this.visibilityUpdateCountdown = 0;
        this.statusCycleElapsed        = 0;
        this.currentButtonSet          = 0;
        this.highlightedOrderCount     = 0;
        this.userActionFlags           = 0;
        this.status                    = 0;

        this.wireframeRandomizer      = 0;
        this.lastAttackingPlayer      = 0;
        this.killCount                = 0;
        this.remainingLifetime        = 0;
        this.damageOverlayCount       = 0;
        this.previousVisibleHitPoints = 0;
        this.energy                   = 0;
        this.setShieldPoints(0);
        this.setHitPoints(0);
        this.setPosition(0, 0);

        this.parentUnit       = null;
        this.subunit          = null;
        this.sprite           = null;
        this.previousUnitType = 0;
        this.setOwnerIndex(0);
        this.setUnitId(0);
    }

    /**
     * Resets the worker, Vulture, hangar and building role state before assigning the unit type, for a new unit and for a
     * morph alike. The resource and link state stays ({@link #resetResourceAndLinkState()}).
     */
    void resetRoleState() {
        this.targetResourceUnit     = null;
        this.firstInsideHangarUnit  = null;
        this.firstOutsideHangarUnit = null;
        this.hangarParent           = null;
        this.previousHangarUnit     = null;
        this.nextHangarUnit         = null;
        this.carriedPowerup         = null;
        this.addon                  = null;

        this.pool.getTargetResourceXs().set(this.getIndex(), 0);
        this.pool.getTargetResourceYs().set(this.getIndex(), 0);
        this.setGatheringResourceByte(0);
        this.setCarriedResourceAmount(0);
        this.setSpiderMineCount(0);
        this.pool.getInsideHangarCounts().set(this.getIndex(), 0);
        this.pool.getOutsideHangarCounts().set(this.getIndex(), 0);
        this.setHangarOutsideByte(0);
        this.setHangarSpareByte(0);
        this.setRepairTimer(0);
        this.setResearchType(0);
        this.setUpgradeType(0);
        this.setUpgradeResearchTime(0);
        this.setUpgradeLevel(0);
        this.setAddonBuildType(0);
        this.setLandingByte(0);
        this.setLarvaSpawnCountdown(0);
        this.setCreepTimer(0);
    }

    /**
     * Resets lifetime, Defensive Matrix health and the eight contiguous status durations before inner initialization.
     * <p>
     * The irradiation source is cleared here; Acid Spore and Maelstrom belong to the following initialization group.
     */
    void resetLifetimeAndStatusDurations() {
        this.remainingLifetime     = 0;
        this.defensiveMatrixHealth = 0;
        this.defensiveMatrixTimer  = 0;
        this.speedIncreaseByte     = 0;
        this.speedDecreaseByte     = 0;
        this.lockdownTimer         = 0;
        this.irradiateTimer        = 0;
        this.stasisTimer           = 0;
        this.plagueTimer           = 0;
        this.stormTimer            = 0;
        this.irradiatedBy          = null;
        this.irradiateOwner        = 0;
    }

    /**
     * Resets lingering status effects and transport-handle extension bits, retaining base handles.
     */
    void resetStatusEffectsAndLoadedUnitExtensions() {
        this.parasiteFlags      = 0;
        this.statusCycleElapsed = 0;
        this.blindnessState     = 0;
        this.maelstromTimer     = 0;
        this.acidSporeCount     = 0;

        Arrays.fill(this.acidSporeTimers, (byte) 0);

        this.loadedUnitHandleExtensionBits = 0;
    }
}
