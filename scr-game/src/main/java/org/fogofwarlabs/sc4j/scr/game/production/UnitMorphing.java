package org.fogofwarlabs.sc4j.scr.game.production;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrImageTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitSpells;
import org.fogofwarlabs.sc4j.scr.game.ai.UnitStrengthTable;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.path.PathPool;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.presentation.NotificationEvents;
import org.fogofwarlabs.sc4j.scr.game.sprite.*;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Runs Larva, Hydralisk and Mutalisk egg transitions, birth completion and unit type replacement.
 */
public final class UnitMorphing {
    /**
     * The units hatched from an egg of a {@link UnitTypeFlags#TWO_UNITS_IN_ONE_EGG} type.
     */
    static final int OFFSPRING_COUNT = 2;

    private static final int FIXED_POINT_FRACTION_BITS = 8;
    private static final int OVERLAY_VERTICAL_SHIFT = Short.SIZE;

    /**
     * The order states of {@link ScrOrderIds#ZERG_UNIT_MORPH}: started, counting down the build time, hatching.
     */
    private static final int MORPH_STATE_START = 0;
    private static final int MORPH_STATE_COUNTDOWN = 1;
    private static final int MORPH_STATE_HATCHING = 2;

    /**
     * The position of the second offspring in the birth position array.
     */
    private static final int SECOND_OFFSPRING_X = UnitPositioning.POSITION_COORDINATES + UnitPositioning.X_INDEX;
    private static final int SECOND_OFFSPRING_Y = UnitPositioning.POSITION_COORDINATES + UnitPositioning.Y_INDEX;
    /**
     * The initial direction of the second Zergling and of the second Scourge.
     */
    private static final int SECOND_ZERGLING_DIRECTION = 144;
    private static final int SECOND_SCOURGE_DIRECTION = 16;
    /**
     * The pixels a hatched hovering unit and a hatched flying unit move up from the egg.
     */
    private static final int HOVERING_BIRTH_LIFT = 7;
    private static final int FLYING_BIRTH_LIFT = 42;

    /**
     * Applies the button set write: writes the unit's button set unless the unit is disabled, is not a building and the set is not the empty set
     * {@link ButtonSetIds#EMPTY}.
     *
     * @param units     the unit type table
     * @param unit      the unit
     * @param buttonSet the button set
     */
    public static void writeButtonSet(@NotNull ScrUnitTypeView units, @NotNull Unit unit, int buttonSet) {
        int type = unit.getUnitId();
        boolean building = UnitQueries.isBuilding(units, type);

        if (UnitOrderEligibility.isDisabled(unit) && !building && buttonSet != ButtonSetIds.EMPTY) {
            return;
        }

        unit.setCurrentButtonSet(buttonSet);
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitMorphing} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitMorphing(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Advances {@link ScrOrderIds#ZERG_UNIT_MORPH}: a Larva, Hydralisk or Mutalisk becomes its egg, the egg counts down the queued type's build time,
     * and the hatching egg becomes the queued type, which starts {@link ScrOrderIds#ZERG_BIRTH} or completes at once.
     *
     * @param unit             the morphing unit
     * @param instructionLimit the order, animation, morph, construction image, completion and build queue reset budget
     * @throws UnsupportedOperationException if accounting, the creation context or the unit's sprite is missing
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the unit has no sprite
     */
    public void update(@NotNull Unit unit, int instructionLimit) {
        int phase = unit.getPrimaryOrderState();

        if (phase == MORPH_STATE_COUNTDOWN) {
            int remaining = unit.getRemainingBuildTime();

            if (remaining != 0) {
                this.game.getUnitBuildTime().advance(unit);
            } else {
                this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(),
                                                                  unit,
                                                                  IscriptExecutor.ANIMATION_SPECIAL_STATE_1,
                                                                  1,
                                                                  instructionLimit);
                unit.setPrimaryOrderState(MORPH_STATE_HATCHING);
            }

            return;
        }

        if (phase == MORPH_STATE_HATCHING) {
            if ((unit.getOrderSignal() & Unit.ORDER_SIGNAL_ANIMATION_DONE) == 0) {
                return;
            }

            unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ANIMATION_DONE);
        } else if (phase != MORPH_STATE_START) {
            return;
        }

        int previousType = unit.getUnitId();
        int eggType = switch (ScrUnitIds.orNone(this.game.getBaseType(previousType))) {
            case ScrUnitIds.ZERG_LARVA -> ScrUnitIds.ZERG_EGG;
            case ScrUnitIds.ZERG_HYDRALISK -> ScrUnitIds.ZERG_LURKER_EGG;
            case ScrUnitIds.ZERG_MUTALISK -> ScrUnitIds.ZERG_COCOON;
            default -> -1;
        };

        if (phase == MORPH_STATE_START && eggType < 0) {
            return;
        }

        int requestedType = UnitQueries.getQueuedType(unit);
        int replacementType = phase == MORPH_STATE_START ? eggType : requestedType;
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();
        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

        if (accounting == null || context == null || unit.getSpriteOrNull() == null) {
            throw Exceptions.newUnsupportedOperationException("Morph initialization requires accounting, creation context and the unit sprite.");
        }

        // The supply test shows its error (display on) before the queue refund.
        if (phase == MORPH_STATE_START && !this.game.getCostTests().testSupply(this.game, unit.getOwnerIndex(), requestedType, true)) {
            this.game.getUnitLifecycle().resetBuildQueue(unit, instructionLimit);
            this.game.getOrders().issueUntargeted(unit, units.getReturnToIdleOrder(unit.getUnitId()), instructionLimit);

            return;
        }

        if (phase == MORPH_STATE_START) {
            accounting.updateCompleted(unit, units, -1, false, this.game.getPlayerTable().getProductionStatistics());
            unit.setStatus(unit.getStatus() & ~UnitStatusFlags.COMPLETED);
        }

        this.morph(unit, replacementType, instructionLimit);

        // The Larva, Hydralisk or Mutalisk that became an egg takes the egg's own button set.
        if (phase == MORPH_STATE_START) {
            writeButtonSet(units, unit, unit.getUnitId());
        }

        if (phase == MORPH_STATE_HATCHING &&
            (this.game.getBaseType(unit) == ScrUnitIds.ZERG_DRONE || this.game.getBaseType(unit) == ScrUnitIds.ZERG_OVERLORD)) {
            this.game.getComputerUnits().notifyCompleted(unit, unit, instructionLimit);
        }

        // The unit that hatched from the egg is ready.
        if (phase == MORPH_STATE_HATCHING) {
            this.game.getInterfaceLink().getNotificationEvents().raise(NotificationEvents.Kind.UNIT_READY, unit);
        }

        if (phase == MORPH_STATE_START) {
            unit.setRemainingBuildTime(units.getBuildTime(UnitQueries.getQueuedType(unit)));
            unit.setPrimaryOrderState(MORPH_STATE_COUNTDOWN);
        } else {
            UnitLifecycle.clearCurrentQueueSlot(unit);

            if (units.getConstructionImage(unit.getUnitId()) != 0) {
                this.game.getIscriptExecutor().initializeConstructionImage(unit, IscriptExecutor.CONSTRUCTION_IMAGE_MODE, instructionLimit);
                this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(),
                                                                  unit,
                                                                  IscriptExecutor.ANIMATION_SPECIAL_STATE_1,
                                                                  1,
                                                                  instructionLimit);
                this.game.getOrders().issueUntargeted(unit, ScrOrderIds.ZERG_BIRTH, instructionLimit);
            } else {
                this.completeUnit(context, unit, instructionLimit);

                if (this.game.getBaseType(previousType) == ScrUnitIds.ZERG_EGG) {
                    Unit parent = unit.getParentUnit();

                    if (parent != null) {
                        this.game.getUnitRally().issueRallyOrder(parent, unit, instructionLimit);
                    }
                }
            }
        }
    }

    /**
     * Completes a birth animation after its script signals readiness.
     *
     * @param unit             the emerging unit
     * @param instructionLimit the creation, completion, status overlay, construction image, speed and order budget
     * @throws UnsupportedOperationException if the creation context is missing or the unit accounting or the unit pool is not initialized
     * @throws IllegalStateException         if the landing-dust overlay coordinates cannot be read or the unit has no sprite, or the sprite has no main image
     * @throws NullPointerException          if the world was built without game data
     */
    public void birth(@NotNull Unit unit, int instructionLimit) {
        if ((unit.getOrderSignal() & Unit.ORDER_SIGNAL_ANIMATION_DONE) == 0) {
            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        boolean paired = (units.getFlags(unit.getUnitId()) & UnitTypeFlags.TWO_UNITS_IN_ONE_EGG) != 0;
        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

        if (context == null) {
            throw Exceptions.newUnsupportedOperationException("Birth requires initialized completion inputs.");
        }

        unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ANIMATION_DONE);

        Unit secondUnit = null;

        if (paired) {
            Sprite sprite = unit.requireSprite();
            SpriteImage image = sprite.requireMainImage();
            int[] positions = new int[OFFSPRING_COUNT * UnitPositioning.POSITION_COORDINATES];

            for (int offspringIndex = 0; offspringIndex < OFFSPRING_COUNT; offspringIndex++) {
                int offset;

                try {
                    offset = this.game.requireGameData().getImageOverlayOffset(image.getImageTypeId(),
                                                                               ScrImageTypeView.OVERLAY_LANDING_DUST,
                                                                               image.getDrawnFrameIndex(),
                                                                               offspringIndex);
                } catch (IOException exception) {
                    throw Exceptions.newIllegalStateException(exception, "Birth requires its actual landing-dust coordinate resource.");
                }

                int horizontalOffset = (short) offset;
                int verticalOffset = (short) (offset >>> OVERLAY_VERTICAL_SHIFT);

                if ((image.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0) {
                    horizontalOffset = -horizontalOffset;
                }

                int unitType = unit.getUnitId();
                int position = offspringIndex * UnitPositioning.POSITION_COORDINATES;
                int positionX = UnitPositioning.clampCoordinate((short) (sprite.getX() + horizontalOffset),
                                                                units.getDimensionLeft(unitType), units.getDimensionRight(unitType),
                                                                this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE,
                                                                0);
                int positionY = UnitPositioning.clampCoordinate((short) (sprite.getY() + verticalOffset),
                                                                units.getDimensionUp(unitType), units.getDimensionDown(unitType),
                                                                this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE,
                                                                UnitPositioning.MAP_BOTTOM_MARGIN);

                positions[position + UnitPositioning.X_INDEX] = positionX;
                positions[position + UnitPositioning.Y_INDEX] = positionY;
            }

            this.game.getUnitPositioning().move(unit, positions[UnitPositioning.X_INDEX], positions[UnitPositioning.Y_INDEX]);

            secondUnit = this.game.getUnitStorageConstructor().create(context,
                                                                      this.game.getPlayerTable().requireUnitAccounting(),
                                                                      unit.getUnitId(),
                                                                      positions[SECOND_OFFSPRING_X], positions[SECOND_OFFSPRING_Y],
                                                                      unit.getOwnerIndex(),
                                                                      this.game.getUnitSystem().requireUnitPool().getCreationTag(unit.getIndex()),
                                                                      instructionLimit);
        }

        if (paired && secondUnit == null) {
            // The creation shows the pending creation message and returns no unit; the birth then skips the second unit's computer notification and
            // goes on with the first unit alone.
            this.game.getUnitStorageConstructor().notifyCreationFailure(unit.getOwnerIndex());
        } else if (paired) {
            secondUnit.resetDirection(this.game,
                                      this.game.getBaseType(unit) == ScrUnitIds.ZERG_ZERGLING
                                      ? SECOND_ZERGLING_DIRECTION
                                      : this.game.getBaseType(unit) == ScrUnitIds.ZERG_SCOURGE ? SECOND_SCOURGE_DIRECTION : 0);
            this.completeUnit(context, secondUnit, instructionLimit);
            // The second offspring joins the local selection when the egg was selected.
            this.game.getInterfaceLink().getLocalSelection().addHatchedUnit(unit, secondUnit);
            secondUnit.setRemainingLifetime(unit.getRemainingLifetime());
            secondUnit.setDefensiveMatrixHealth(unit.getDefensiveMatrixHealth());
            secondUnit.setSpeedIncreaseByte(unit.getSpeedIncreaseByte());
            secondUnit.setStormTimer(unit.getStormTimer());
            secondUnit.setParasiteFlags(unit.getParasiteFlags());
            secondUnit.setStatusCycleElapsed(unit.getStatusCycleElapsed());
            secondUnit.setBlindnessState(unit.getBlindnessState());

            for (int timerIndex = 0; timerIndex < Unit.ACID_SPORE_SLOTS; timerIndex++) {
                secondUnit.setAcidSporeTimer(timerIndex, unit.getAcidSporeTimer(timerIndex));
            }

            // Transfer the remaining status timers and recreate their overlays.
            secondUnit.setDefensiveMatrixTimer(unit.getDefensiveMatrixTimer());
            secondUnit.setSpeedDecreaseByte(unit.getSpeedDecreaseByte());
            secondUnit.setLockdownTimer(unit.getLockdownTimer());
            secondUnit.setIrradiateTimer(unit.getIrradiateTimer());
            secondUnit.setStasisTimer(unit.getStasisTimer());
            secondUnit.setPlagueTimer(unit.getPlagueTimer());
            secondUnit.setIrradiatedBy(unit.getIrradiatedBy());
            secondUnit.setIrradiateOwner(unit.getIrradiateOwner());
            secondUnit.setMaelstromTimer(unit.getMaelstromTimer());
            secondUnit.setAcidSporeCount(unit.getAcidSporeCount());
            this.game.getUnitStatusEffects().apply(secondUnit, instructionLimit);
            this.game.getEngineGame().getMovement().recalculateSpeed(secondUnit, instructionLimit);
            this.game.getComputerUnits().notifyCompleted(unit, secondUnit, instructionLimit);
        }

        if (this.game.getBaseType(unit) != ScrUnitIds.ZERG_DRONE && this.game.getBaseType(unit) != ScrUnitIds.ZERG_OVERLORD) {
            this.game.getComputerUnits().notifyCompleted(unit, unit, instructionLimit);
        }

        this.game.getIscriptExecutor().initializeConstructionImage(unit, IscriptExecutor.COMPLETED_IMAGE_MODE, instructionLimit);
        this.completeUnit(context, unit, instructionLimit);

        Sprite sprite = unit.requireSprite();
        int positionX = sprite.getX();
        int positionY = sprite.getY();

        int previousBaseType = this.game.getBaseType(unit.getPreviousUnitType());

        if (!paired && previousBaseType != ScrUnitIds.ZERG_COCOON &&
            units.getUnknownByte(unit.getUnitId()) == UnitSpells.HOVERING_UNKNOWN_BYTE) {
            positionY -= HOVERING_BIRTH_LIFT;
        } else if (!paired && previousBaseType != ScrUnitIds.ZERG_COCOON && (unit.getStatus() & UnitStatusFlags.FLYING) != 0) {
            positionY -= FLYING_BIRTH_LIFT;
        }

        positionX = UnitPositioning.clampCoordinate(positionX,
                                                    units.getDimensionLeft(unit.getUnitId()), units.getDimensionRight(unit.getUnitId()),
                                                    this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE,
                                                    0);
        positionY = UnitPositioning.clampCoordinate(positionY,
                                                    units.getDimensionUp(unit.getUnitId()), units.getDimensionDown(unit.getUnitId()),
                                                    this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE,
                                                    UnitPositioning.MAP_BOTTOM_MARGIN);

        if (positionX != sprite.getX() || positionY != sprite.getY()) {
            this.game.getUnitPositioning().move(unit, positionX, positionY);
        }

        Unit parent = unit.getParentUnit();

        if (parent != null && previousBaseType != ScrUnitIds.ZERG_COCOON && previousBaseType != ScrUnitIds.ZERG_LURKER_EGG) {
            this.game.getUnitRally().issueRallyOrder(parent, unit, instructionLimit);

            if (secondUnit != null) {
                this.game.getUnitRally().issueRallyOrder(parent, secondUnit, instructionLimit);
            }
        }

        this.game.getOrders().completeOrder(unit, instructionLimit);
    }

    /**
     * Replaces a unit's type while retaining its identity, owner, order and relationships.
     * <p>
     * Visible units leave spatial, footprint and flying-cell storage before accounting and type reinitialization. The rebuilt sprite is clamped and
     * registered when visible. Cloak data, the selection images, the flash timer and sprite visibility survive; buildings drop cloak, an existing
     * turret takes the new type's first subunit whatever it is, and status-effect overlays are recreated.
     *
     * @param unit             the morphing unit
     * @param replacementType  the new {@code units.dat} type
     * @param instructionLimit the sprite initialization and status overlay budget
     * @throws UnsupportedOperationException if accounting, the creation context, spatial, strength, graphics or path storage is missing
     * @throws IllegalStateException         if the unit's sprite or a cloaked main image is missing
     * @throws NullPointerException          if the world was built without game data
     */
    public void morph(@NotNull Unit unit, int replacementType, int instructionLimit) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newIllegalStateException("Morph conversion requires the unit sprite.");
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();
        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();
        UnitSpatialIndex spatialIndex = this.game.getUnitSystem().getUnitSpatialIndex();
        UnitStrengthTable strengths = this.game.getUnitStrengthTable();
        SpritePool sprites = this.game.getSpriteSystem().getSpritePool();
        SpriteImagePool images = this.game.getSpriteSystem().getImagePool();

        if (accounting == null || context == null || spatialIndex == null || strengths == null || sprites == null || images == null) {
            throw Exceptions.newUnsupportedOperationException("Morph initialization requires accounting, spatial, strength and graphics storage.");
        }

        PathPool paths = this.game.getPathSystem().getPathPool();

        if (unit.getPathIndex() >= 0 && paths == null) {
            throw Exceptions.newUnsupportedOperationException("Morph conversion requires the retained path pool.");
        }

        int visibility = sprite.getVisibilityFlags();
        int positionX = (short) sprite.getX();
        int positionY = (short) sprite.getY();
        boolean requiresDetector = (unit.getStatus() & UnitStatusFlags.REQUIRES_DETECTOR) != 0;
        boolean cloaked = (unit.getStatus() & UnitStatusFlags.CLOAKED) != 0;
        int retainedCloakData = 0;

        if (requiresDetector || cloaked) {
            SpriteImage mainImage = sprite.getMainImage();

            if (mainImage == null) {
                throw Exceptions.newIllegalStateException("Cloak-preserving morph dereferences an absent main image.");
            }

            retainedCloakData = mainImage.getModifierData1();
        }

        // Leave spatial state alone while the sprite is hidden, for example a removed refinery restored to a geyser inside order Die.
        if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) == 0) {
            spatialIndex.remove(unit);

            if (UnitQueries.isGroundedBuilding(unit)) {
                this.game.getUnitPositioning().setFootprintOccupied(unit.getUnitId(), positionX, positionY, false);
            }

            if ((unit.getStatus() & UnitStatusFlags.FLYING) != 0) {
                spatialIndex.unregisterFlyingUnit(unit, units);
            }
        }

        // The selection images and the flash timer leave the old sprite; the rebuilt sprite takes them again below.
        SelectionImages selectionImages = this.game.getSelectionImages();
        int spriteFlags = sprite.getFlags();
        int selectionIndex = sprite.getSelectionIndex();
        int teamMarks = SelectionImages.getTeamMarkCount(sprite);
        int flashTimer = sprite.getFlashTimer();

        if ((spriteFlags & Sprite.FLAG_SELECTED) != 0) {
            selectionImages.deselectUnit(unit);
        }

        int circleColor = selectionImages.hideSelectionCircle(sprite);
        int teamColor = selectionImages.clearTeamMarks(sprite);

        accounting.update(unit, units, -1, context.preservesNegativeTypeCounts());

        if ((unit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
            accounting.updateCompleted(unit, units, -1, true, this.game.getPlayerTable().getProductionStatistics());
        }

        this.reinitialize(unit, replacementType, positionX, positionY, instructionLimit);

        accounting.update(unit, units, 1, context.preservesNegativeTypeCounts());

        if ((unit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
            accounting.updateCompleted(unit, units, 1, true, this.game.getPlayerTable().getProductionStatistics());
        }

        if (requiresDetector || cloaked) {
            this.game.getUnitCloaking().setSpriteCloakModifier(unit.requireSprite(),
                                                               requiresDetector,
                                                               cloaked,
                                                               (unit.getStatus() & UnitStatusFlags.BURROWED) != 0,
                                                               retainedCloakData);
        }

        Sprite rebuiltSprite = unit.requireSprite();
        UnitSelection selection = this.game.getSelection();

        if ((spriteFlags & Sprite.FLAG_SELECTED) != 0) {
            selectionImages.selectUnit(unit, selectionIndex);
        } else if ((spriteFlags & Sprite.FLAG_SELECTION_CIRCLE) != 0) {
            selectionImages.showSelectionCircle(rebuiltSprite, circleColor);
        }

        for (int mark = teamMarks; mark > 0; mark--) {
            selectionImages.addTeamMark(rebuiltSprite, teamColor, selection == null ? 0 : selection.getTeamSize());
        }

        rebuiltSprite.setFlashTimer(flashTimer);
        rebuiltSprite.setVisibilityFlags(visibility);

        if ((units.getFlags(replacementType) & UnitTypeFlags.BUILDING) != 0 && (requiresDetector || cloaked)) {
            ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.NOTHING);
            unit.setStatus(unit.getStatus() & ~(UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED));
        }

        // Move inside the new type's valid bounds before spatial registration.
        Sprite morphedSprite = unit.requireSprite();
        int boundedX = UnitPositioning.clampCoordinate((short) morphedSprite.getX(),
                                                       units.getDimensionLeft(replacementType), units.getDimensionRight(replacementType),
                                                       this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE,
                                                       0);
        int boundedY = UnitPositioning.clampCoordinate((short) morphedSprite.getY(),
                                                       units.getDimensionUp(replacementType), units.getDimensionDown(replacementType),
                                                       this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE,
                                                       UnitPositioning.MAP_BOTTOM_MARGIN);

        if (boundedX != (short) morphedSprite.getX() || boundedY != (short) morphedSprite.getY()) {
            this.game.getUnitPositioning().move(unit, boundedX, boundedY);
        }

        // The same check reads the reinitialized sprite: a recreated building sprite is not hidden.
        if ((morphedSprite.getFlags() & Sprite.FLAG_HIDDEN) == 0) {
            this.game.getUnitStorageConstructor().registerSpatially(unit, context.isFoliageEnabled());
        }

        Unit turret = unit.getSubunit();

        if (turret != null) {
            // Reinitialize the turret as the new type's first subunit while retaining cloak data.
            int turretType = units.getFirstSubunit(replacementType);
            boolean turretRequiresDetector = (turret.getStatus() & UnitStatusFlags.REQUIRES_DETECTOR) != 0;
            boolean turretCloaked = (turret.getStatus() & UnitStatusFlags.CLOAKED) != 0;
            Sprite turretSprite = turret.requireSprite();
            int turretCloakData = 0;

            if (turretRequiresDetector || turretCloaked) {
                SpriteImage turretImage = turretSprite.getMainImage();

                if (turretImage == null) {
                    throw Exceptions.newIllegalStateException("Cloak-preserving turret morph dereferences an absent main image.");
                }

                turretCloakData = turretImage.getModifierData1();
            }

            this.reinitialize(turret, turretType, (short) turretSprite.getX(), (short) turretSprite.getY(), instructionLimit);

            turretSprite = turret.requireSprite();

            turretSprite.setFlags(turretSprite.getFlags() | Sprite.FLAG_TURRET);

            if (turretRequiresDetector || turretCloaked) {
                this.game.getUnitCloaking().setSpriteCloakModifier(turretSprite,
                                                                   turretRequiresDetector,
                                                                   turretCloaked,
                                                                   (unit.getStatus() & UnitStatusFlags.BURROWED) != 0,
                                                                   turretCloakData);
            }

            this.game.getUnitStorageConstructor().attachSubunitImage(unit, turret);
            turretSprite.setVisibilityFlags(unit.requireSprite().getVisibilityFlags());
        }

        this.game.getUnitStatusEffects().apply(unit, instructionLimit);
    }

    /**
     * Rebuilds sprite and type state while retaining identity, order, targets, sprite owner and resource/link fields. The type initializer resets the
     * first role group without releasing resource claims or unlinking waiting workers; hit points scale by the maximum hit point ratio. The
     * resource/link reset ({@link Unit#resetResourceAndLinkState()}) is not applied.
     *
     * @param unit             the unit or turret
     * @param replacementType  the new {@code units.dat} type
     * @param positionX        the sprite horizontal position
     * @param positionY        the sprite vertical position
     * @param instructionLimit the sprite initialization budget
     * @throws IllegalStateException         if the sprite retirement or the type initialization fails, or the unit has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit strength tables, the sprite pool, the image pool or the path pool is not initialized
     */
    private void reinitialize(@NotNull Unit unit, int replacementType, int positionX, int positionY, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        SpritePool sprites = this.game.getSpriteSystem().requireSpritePool();
        Sprite sprite = unit.requireSprite();
        int previousSpriteOwner = sprite.getOwner();
        int previousType = unit.getUnitId();

        int previousHitPoints = unit.getHitPoints();
        int previousOrder = unit.getPrimaryOrder();
        int previousOrderState = unit.getPrimaryOrderState();
        Unit target = unit.getOrderTargetUnit();
        int targetType = unit.getOrderTargetUnitType();
        int targetX = unit.getOrderTargetX();
        int targetY = unit.getOrderTargetY();

        unit.setPreviousUnitType(previousType);
        unit.setPreviousVisibleHitPoints(Math.max(previousHitPoints >> FIXED_POINT_FRACTION_BITS, 1));
        unit.setPrimaryOrder(ScrOrderIds.DIE);
        unit.setPrimaryOrderState(Unit.DIE_STATE_DESTROYED);

        for (SpriteImage image : sprite.getImages()) {
            this.game.getSpriteSystem().requireImagePool().recycle(image);
        }

        sprite.clearImages();

        if (!sprites.recycleEmptySprite(sprite, this.game.getMap().getHeight())) {
            throw Exceptions.newIllegalStateException("Morph sprite retirement could not unlink its native row.");
        }

        unit.setSprite(null);

        if (unit.getPathIndex() >= 0) {
            this.game.getPathSystem().requirePathPool().release(unit.getPathIndex());
            unit.setPathIndex(-1);
        }

        if (!this.game.getUnitInitializer().initialize(unit, replacementType, positionX, positionY, unit.getOwnerIndex(), instructionLimit)) {
            throw Exceptions.newIllegalStateException("Morph type initialization failed.");
        }

        int maximumPreviousHitPoints = units.getHitPoints(previousType) >> FIXED_POINT_FRACTION_BITS;
        int hitPoints = maximumPreviousHitPoints == 0
                        ? 1
                        : (previousHitPoints >> FIXED_POINT_FRACTION_BITS) *
                          (units.getHitPoints(replacementType) >> FIXED_POINT_FRACTION_BITS) / maximumPreviousHitPoints;

        // A ratio that rounds down to 0 keeps 1/256 hit point.
        unit.setHitPoints(hitPoints == 0 ? 1 : hitPoints << FIXED_POINT_FRACTION_BITS);
        this.game.requireUnitStrengthTable().update(this.game, unit, units);

        int status = unit.getStatus() & ~(UnitStatusFlags.DISABLED | UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE | UnitStatusFlags.ISCRIPT_NO_BREAK);

        if (this.game.requireGameData().getOrderTypes().getCanBeInterrupted(previousOrder) == 0) {
            status |= UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE;
        }

        unit.setStatus(status);
        unit.requireSprite().updateOwner(previousSpriteOwner);
        unit.setPrimaryOrder(previousOrder);
        unit.setPrimaryOrderState(previousOrderState);
        unit.setOrderTargetUnit(target);
        unit.setOrderTargetUnitType(targetType);
        unit.setOrderTargetX(targetX);
        unit.setOrderTargetY(targetY);

        if (UnitQueries.isGroundedBuilding(unit)) {
            UnitLifecycle.clearCurrentQueueSlot(unit);
        }

        // A completed unit takes the new type's button set, an incomplete one the empty set.
        writeButtonSet(units, unit, (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0 ? replacementType : ButtonSetIds.EMPTY);
    }

    private void completeUnit(@NotNull UnitCreationContext context, @NotNull Unit unit, int instructionLimit) {
        Player player = this.game.getPlayerTable().getPlayer(unit.getOwnerIndex());

        if (player == null) {
            throw Exceptions.newUnsupportedOperationException("Morph completion requires the actual owner player.");
        }

        this.game.getUnitCompletion().initializeBeforePlacement(context, unit, instructionLimit);
        this.game.getUnitCompletion().completeAfterPlacement(context, unit, player.getType(), instructionLimit);
    }
}
