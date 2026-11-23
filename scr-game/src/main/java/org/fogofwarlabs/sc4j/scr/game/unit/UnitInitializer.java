package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.modifier.Stat;
import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUpgradeIds;
import org.fogofwarlabs.sc4j.scr.data.format.iscript.IscriptFile;
import org.fogofwarlabs.sc4j.scr.data.format.iscript.IscriptHeader;
import org.fogofwarlabs.sc4j.scr.data.type.ScrFlingyTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.modifier.ScrMaximumEnergyModifier;
import org.fogofwarlabs.sc4j.scr.game.modifier.ScrSpeedModifier;
import org.fogofwarlabs.sc4j.scr.game.movement.MovementStates;
import org.fogofwarlabs.sc4j.scr.game.movement.UnitSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerUpgrades;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptModeOneInterpreter;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Initializes a unit slot for {@link UnitStorageConstructor#create}: typed motion, role, order and path state, the sprite and its script.
 * <p>
 * The caller supplies the free unit slot. Success here does not commit the allocation, complete the unit creation, place the unit, or initialize a
 * replay world.
 */
public final class UnitInitializer implements UnitEnergyLimits {
    /**
     * The path collision flag of a unit below the air elevation level.
     */
    public static final int GROUND_ELEVATION_PATH_FLAG = 1;
    /**
     * The path collision flag of a unit on an illegal position that moves to a legal one; such a unit does not fire.
     */
    public static final int ILLEGAL_POSITION_PATH_FLAG = 2;
    /**
     * The path collision flag of a unit stopped while it moves to a legal position ({@link #ILLEGAL_POSITION_PATH_FLAG});
     * the move to the legal position then gives up and leaves the unit colliding.
     */
    public static final int STOPPED_MOVING_TO_LEGAL_PATH_FLAG = 4;
    /**
     * A unit under construction starts with this fraction (one tenth) of its hit points and shields.
     */
    public static final int CONSTRUCTION_START_DIVISOR = 10;

    /**
     * The Walking frames the speed sampler averages.
     */
    private static final int WALKING_SPEED_SAMPLES = 32;
    /**
     * The last attacking player of a new unit: none, the first slot after the playable players.
     */
    private static final int NO_ATTACKING_PLAYER = PlayerTable.PLAYABLE_PLAYER_SLOTS;
    /**
     * A new spellcaster starts with this fraction (one quarter) of its maximum energy.
     */
    private static final int INITIAL_ENERGY_DIVISOR = 4;
    /**
     * The maximum energy ({@code 200}) of a caster without its energy upgrade, with eight fraction bits.
     */
    private static final int MAXIMUM_ENERGY = 0xC800;
    /**
     * The shifts that move the {@code units.dat} flags Building, Can Turn and Can Move onto their status bits.
     */
    private static final int GROUNDED_BUILDING_SHIFT = 1;
    private static final int CAN_TURN_SHIFT = 12;
    private static final int CAN_MOVE_SHIFT = 10;
    /**
     * The mask of the WORD unit type, coordinates, construction increments and pool index.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The fraction bits of the fixed-point position.
     */
    private static final int FIXED_POSITION_SHIFT = 8;
    /**
     * The fraction bits of the shield points.
     */
    private static final int FIXED_POINT_SHIFT = 8;

    /**
     * Updates the ground elevation path flag of a unit from the elevation level of its sprite: the flag is set below the
     * air elevation level and cleared otherwise; the other path collision flags stay.
     *
     * @param unit      the unit
     * @param elevation the elevation level of the unit's sprite
     */
    public static void updateGroundElevationPathFlag(@NotNull Unit unit, int elevation) {
        unit.setPathCollisionFlags(unit.getPathCollisionFlags() & ~GROUND_ELEVATION_PATH_FLAG |
                                   (elevation < Sprite.AIR_ELEVATION_LEVEL ? GROUND_ELEVATION_PATH_FLAG : 0));
    }

    /**
     * Initializes the flingy motion fields of a unit at a position: the flingy type and its speeds, the position, the movement destination and
     * waypoint (and a cleared movement target) when they differ, the direction target and the heading.
     *
     * @param unit       the unit being initialized
     * @param flingy     the flingy type table
     * @param flingyType the flingy type of the unit
     * @param positionX  the WORD x coordinate
     * @param positionY  the WORD y coordinate
     * @param heading    the initial heading
     */
    static void initializeMotionFields(@NotNull Unit unit,
                                       @NotNull ScrFlingyTypeView flingy, int flingyType,
                                       int positionX, int positionY,
                                       int heading) {
        positionX &= WORD_MASK;
        positionY &= WORD_MASK;

        unit.setFlingyType(flingyType);
        unit.setMovementFlags(0);
        unit.setNextSpeed(0);
        unit.setTopSpeed(flingy.getTopSpeed(flingyType));
        unit.setAcceleration(flingy.getAcceleration(flingyType));
        unit.setTurnRate(flingy.getTurnRate(flingyType));
        unit.setMovementControl(flingy.getMovementControl(flingyType));
        unit.setPositionX(positionX);
        unit.setPositionY(positionY);
        unit.setFixedPositionX((short) positionX << FIXED_POSITION_SHIFT);
        unit.setFixedPositionY((short) positionY << FIXED_POSITION_SHIFT);

        if (unit.getMovementDestinationX() != positionX || unit.getMovementDestinationY() != positionY) {
            unit.setMovementDestinationX(positionX);
            unit.setMovementDestinationY(positionY);
            unit.setMovementWaypointX(unit.getMovementDestinationX());
            unit.setMovementWaypointY(unit.getMovementDestinationY());
            unit.setMovementTargetUnit(null);
            unit.setMovementFlags(MovementFlags.ORDERED);
        }

        if (unit.getDirectionTargetX() != positionX || unit.getDirectionTargetY() != positionY) {
            unit.setDirectionTargetX(positionX);
            unit.setDirectionTargetY(positionY);
        }

        unit.setHeading(heading);
        unit.setTargetHeading(heading);
    }

    /**
     * Initializes the construction increments of a unit: the hit points it gains per build frame, from a tenth of its
     * hit points, and for a grounded building with shields the starting shields and the shields per build frame.
     *
     * @param unit  the unit being initialized
     * @param units the unit type table
     */
    static void initializeConstructionIncrements(@NotNull Unit unit, @NotNull ScrUnitTypeView units) {
        int unitType = unit.getUnitId();
        int buildTime = units.getBuildTime(unitType);
        int hitPoints = units.getHitPoints(unitType);
        int increment = buildTime == 0
                        ? 1
                        : Integer.divideUnsigned(buildTime - 1 + hitPoints - Integer.divideUnsigned(hitPoints, CONSTRUCTION_START_DIVISOR),
                                                 buildTime) & WORD_MASK;

        unit.setConstructionHitPointIncrement(increment == 0 ? 1 : increment);

        if (units.getShieldEnabled(unitType) != 0 && UnitQueries.isGroundedBuilding(unit)) {
            int shields = units.getShieldAmount(unitType) << FIXED_POINT_SHIFT;

            unit.setShieldPoints(Integer.divideUnsigned(shields, CONSTRUCTION_START_DIVISOR));

            increment = buildTime == 0 ? 1 : Integer.divideUnsigned(shields - unit.getShieldPoints(), buildTime) & WORD_MASK;

            unit.setConstructionShieldIncrement(increment == 0 ? 1 : increment);
        }
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitInitializer} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitInitializer(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Returns the maximum energy of a unit, in {@code 1/256} units.
     *
     * @param unit  the unit
     * @param units the unit type table
     * @return {@code 0xFA00} for heroes and upgraded casters, otherwise {@code 0xC800}
     * @throws IllegalStateException if the unit type has an energy upgrade and the player upgrade tables are not loaded
     */
    @Override
    public int getMaximumEnergy(@NotNull Unit unit, @NotNull ScrUnitTypeView units) {
        if ((units.getFlags(unit.getUnitId()) & UnitTypeFlags.HERO) != 0) {
            return ScrMaximumEnergyModifier.UPGRADED_MAXIMUM_ENERGY;
        }

        return this.game.applyModifiers(Stat.MAXIMUM_ENERGY, unit.getOwnerIndex(), unit.getUnitId(), ContentType.NO_INDEX, MAXIMUM_ENERGY);
    }


    /**
     * Initializes the motion, sprite, script and remaining unit fields of a unit slot.
     *
     * @param unit             the unit being initialized
     * @param unitType         the requested unsigned WORD unit type
     * @param positionX        the input WORD x coordinate
     * @param positionY        the input WORD y coordinate
     * @param owner            the owner byte
     * @param instructionLimit the checked interpreter instruction limit
     * @return {@code true} if the unit sprite was created and every initialization effect was applied, {@code false} otherwise
     * @throws UnsupportedOperationException if the unit type is past {@link ScrUnitIds#NONE} or the sprite pool is not initialized
     * @throws IllegalStateException         if the immediate iscript update removed the sprite or the main image, upgrades or damage overlays are missing
     * @throws NullPointerException          if the world was built without game data
     */
    public boolean initialize(@NotNull Unit unit, int unitType, int positionX, int positionY, int owner, int instructionLimit) {
        unitType &= WORD_MASK;

        GameData typeData = this.game.getGameData();

        // A type past the none id is refused before the game data is required, unless it is an added type.
        if (unitType > ScrUnitIds.NONE && (typeData == null || !typeData.getUnitTypes().isAddedType(unitType))) {
            throw Exceptions.newUnsupportedOperationException("Initialization of unit type {0} past {1} is not modeled.",
                                                              unitType, ScrUnitIds.NONE);
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        ScrFlingyTypeView flingy = this.game.requireGameData().getFlingyTypes();
        int flingyType = units.getFlingyType(unitType);

        initializeMotionFields(unit, flingy, flingyType, positionX, positionY, 0);
        unit.setHitPoints(1);

        Sprite sprite = this.game.getIscriptExecutor().createSprite(flingy.getSprite(flingyType),
                                                                    (short) positionX, (short) positionY,
                                                                    owner,
                                                                    unit,
                                                                    instructionLimit);

        unit.setSprite(sprite);

        if (sprite == null) {
            return false;
        }

        IscriptExecutor.applySpriteDirection(this.game, sprite, unit.getHeading());
        unit.setOwnerIndex(owner);
        unit.setOrderTargetUnitType(ScrUnitIds.NONE);
        unit.setCooldownTimers(0);
        unit.setOrderTargetUnit(null);
        unit.setOrderTargetX(0);
        unit.setOrderTargetY(0);
        unit.setPrimaryOrder(ScrOrderIds.FATAL);
        unit.setPrimaryOrderState(0);
        unit.setOrderSignal(0);
        unit.resetRoleState();
        unit.setSkin(0);
        unit.setCarryingFlags(0);
        unit.setVisibilityUpdateCountdown(0);
        unit.setUnitId(unitType);

        if (!this.game.getIscriptExecutor().executeSprite(sprite, unit, instructionLimit)) {
            unit.setSprite(null);
        }

        unit.setLastAttackingPlayer(NO_ATTACKING_PLAYER);

        int currentType = unit.getUnitId();

        unit.setShieldPoints(units.getShieldAmount(currentType) << FIXED_POINT_SHIFT);
        int initialEnergy = units.getInitialEnergy(unitType);

        unit.setEnergy(initialEnergy != ScrUnitTypeView.DEFAULT_INITIAL_ENERGY
                       ? initialEnergy
                       : this.getMaximumEnergy(unit, units) / INITIAL_ENERGY_DIVISOR);
        // Clear the computer spell target byte after the energy store.
        unit.setSpellTargetFlags(0);

        sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newIllegalStateException("The immediate iscript update removed the sprite of the initialized unit.");
        }

        int elevation = units.getElevationLevel(unitType);

        sprite.setElevationLevel(elevation);

        int abilities = units.getFlags(unitType);
        int status = unit.getStatus();

        status = status & ~UnitStatusFlags.GROUNDED_BUILDING | abilities << GROUNDED_BUILDING_SHIFT & UnitStatusFlags.GROUNDED_BUILDING;
        status = status & ~UnitStatusFlags.FLYING | abilities & UnitStatusFlags.FLYING;
        status = status & ~UnitStatusFlags.CAN_TURN | abilities >>> CAN_TURN_SHIFT & UnitStatusFlags.CAN_TURN;
        status = status & ~UnitStatusFlags.CAN_MOVE | units.getFlags(currentType) >>> CAN_MOVE_SHIFT & UnitStatusFlags.CAN_MOVE;
        status = status & ~UnitStatusFlags.GROUND_UNIT | ((status & UnitStatusFlags.FLYING) == 0 ? UnitStatusFlags.GROUND_UNIT : 0);

        unit.setStatus(status);
        updateGroundElevationPathFlag(unit, elevation);

        if (this.game.requireGameData().getUnitTypes().isCollisionFree(currentType)) {
            unit.setStatus(unit.getStatus() | UnitStatusFlags.NO_COLLIDE);
        }

        if ((units.getFlags(currentType) & UnitTypeFlags.BUILDING) != 0) {
            unit.setResearchType(ScrTechIds.NONE);
            unit.setUpgradeType(ScrUpgradeIds.NONE);
        }

        unit.setPathIndex(-1);
        unit.setMovementState(MovementStates.INIT);
        unit.setMovementDestinationCountdown(0);

        if ((abilities & UnitTypeFlags.INVINCIBLE) != 0) {
            unit.setStatus(unit.getStatus() | UnitStatusFlags.INVINCIBLE);
        } else if ((units.getFlags(currentType) & UnitTypeFlags.INVINCIBLE) == 0) {
            unit.setStatus(unit.getStatus() & ~UnitStatusFlags.INVINCIBLE);
        }

        this.game.getSpriteSystem().requireSpritePool().register(sprite, unit.getIndex() & WORD_MASK);

        SpriteImage mainImage = sprite.getMainImage();

        if (mainImage == null) {
            throw Exceptions.newIllegalStateException("Unit initialization requires the main image of the unit sprite.");
        }

        try {
            unit.setDamageOverlayCount(this.game.requireGameData().getImageDamageOverlayCount(mainImage.getImageTypeId()));
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Damage overlay initialization failed.");
        }

        int buildTime = units.getBuildTime(currentType);

        unit.setRemainingBuildTime(buildTime == 0 ? 1 : buildTime);
        initializeConstructionIncrements(unit, units);
        this.initializeUpgradeSpeed(unit, instructionLimit);
        this.game.getUnitSpeed().recalculate(unit, instructionLimit);

        return true;
    }

    /**
     * Recalculates the top speed of a unit whose iscript moves it: the average distance of the first {@code 32} Walking animation frames, doubled for
     * a sped-up unit and reduced by a quarter for a slowed one.
     *
     * @param unit             the unit
     * @param instructionLimit the checked interpreter instruction limit
     * @throws IllegalStateException if the main image is absent, its iscript header cannot be resolved or the iscript cannot be read
     * @throws NullPointerException  if the world was built without game data
     */
    public void recalculateWalkingSpeed(@NotNull Unit unit, int instructionLimit) {
        if (unit.getMovementControl() != ScrFlingyTypeView.MOVEMENT_CONTROL_ISCRIPT) {
            return;
        }

        Sprite sprite = unit.getSpriteOrNull();
        SpriteImage image = sprite == null ? null : sprite.getMainImage();

        if (image == null) {
            throw Exceptions.newIllegalStateException("Walking speed sampling requires the main image of the unit sprite.");
        }

        IscriptFile script = this.game.requireGameData().getIscript();
        IscriptHeader header = script.findHeader(image.getScriptIdentifier());

        if (header == null) {
            throw Exceptions.newIllegalStateException("The iscript header of the main image cannot be resolved.");
        }

        if (header.getType() < IscriptExecutor.ANIMATION_WALKING) {
            return;
        }

        try {
            int programCounter = script.getAnimationOffset(image.getScriptIdentifier(), IscriptExecutor.ANIMATION_WALKING);

            if (programCounter == 0) {
                return;
            }

            IscriptModeOneInterpreter interpreter = new IscriptModeOneInterpreter();
            int sum = 0;

            interpreter.start(programCounter, 0, 0, instructionLimit);

            for (int sampleIndex = 0; sampleIndex < WALKING_SPEED_SAMPLES; sampleIndex++) {
                int speed = interpreter.step(this.game, unit, 0);
                int modifier = unit.getSpeedModifier();

                sum += UnitSpeed.applySpeedModifier(speed, modifier);
            }

            unit.setTopSpeed(sum / WALKING_SPEED_SAMPLES);
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Walking speed sampling could not read the iscript.");
        }
    }

    /**
     * Releases the unit's path, clears its movement state and updates its ground elevation path flag from its sprite elevation
     * ({@link #updateGroundElevationPathFlag}).
     *
     * @param unit the unit
     * @throws IllegalStateException         if the unit has no sprite
     * @throws UnsupportedOperationException if the path pool is not initialized
     */
    public void resetMovementState(@NotNull Unit unit) {
        if (unit.getPathIndex() >= 0) {
            this.game.getPathSystem().requirePathPool().release(unit.getPathIndex());
            unit.setPathIndex(-1);
        }

        unit.setMovementState(MovementStates.INIT);

        int elevation = unit.requireSprite().getElevationLevel();

        updateGroundElevationPathFlag(unit, elevation);
    }

    private void initializeUpgradeSpeed(@NotNull Unit unit, int instructionLimit) {
        int unitType = unit.getUnitId();
        int baseType = this.game.getBaseType(unitType);

        if (!ScrSpeedModifier.hasSpeedRule(baseType)) {
            return;
        }

        PlayerUpgrades upgrades = this.game.getPlayerTable().getPlayerUpgrades();

        if (!ScrSpeedModifier.isFixedSpeed(baseType) && upgrades == null) {
            throw Exceptions.newIllegalStateException("The player upgrade tables are loaded before unit type {0} exists.", unitType);
        }

        boolean increased = this.game.applyModifiers(Stat.TOP_SPEED, unit.getOwnerIndex(), unitType, ContentType.NO_INDEX, 0) > 0;

        if (increased) {
            unit.setStatus(unit.getStatus() | UnitStatusFlags.SPEED_UPGRADE);
            this.game.getUnitSpeed().recalculate(unit, instructionLimit);
        }

        if (this.game.applyModifiers(Stat.WEAPON_COOLDOWN, unit.getOwnerIndex(), unitType, ContentType.NO_INDEX, 0) > 0) {
            unit.setStatus(unit.getStatus() | UnitStatusFlags.COOLDOWN_UPGRADE);
        }

        this.game.getUnitSpeed().recalculate(unit, instructionLimit);
    }
}
