package org.fogofwarlabs.sc4j.scr.game.visibility;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrImageTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SoundIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.combat.UnitTargeting;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Maintains the cloaked-unit list and per-player detection of cloaked and burrowed units; burrowing itself is {@link UnitBurrowing}.
 */
public final class UnitCloaking {
    /**
     * The frames between two detection updates of a visible cloaked or burrowed unit; the unit phase also spreads the visibility update countdowns
     * over this many frames.
     */
    public static final int DETECTION_UPDATE_INTERVAL = 30;

    /**
     * The widest detection range in pixels (a detector's sight range is at most {@code 11} tiles): the candidate query half-size.
     */
    private static final int MAXIMUM_DETECTION_RANGE = (SightCoverage.DESCRIPTOR_COUNT - 1) * TerrainTileset.TILE_SIZE;
    /**
     * The detection range of a grounded building in tiles.
     */
    private static final int BUILDING_DETECTION_RANGE_TILES = 7;
    /**
     * The detection range of a grounded building in pixels.
     */
    private static final int BUILDING_DETECTION_RANGE = BUILDING_DETECTION_RANGE_TILES * TerrainTileset.TILE_SIZE;
    /**
     * The detection flags of a unit under a status effect: every playable player detects it.
     */
    private static final int DETECTED_BY_PLAYABLE_PLAYERS = 0xFF;
    /**
     * The player bits of the detection flags whose owner lists lose their references to an undetected unit.
     */
    private static final int DETECTION_PLAYER_BITS = 0xFFF;


    /**
     * The fade step and delay bytes of the cloak modifier data.
     */
    private static final int CLOAK_FADE_DATA_MASK = 0xFFFF;
    /**
     * The delay byte of the cloak modifier data.
     */
    private static final int CLOAK_DELAY_MASK = 0xFF00;
    /**
     * The bit position of the delay byte in the cloak modifier data.
     */
    private static final int CLOAK_DELAY_SHIFT = 8;
    /**
     * The updates a cloak fade waits between two steps, so that it steps every fourth update.
     */
    private static final int CLOAK_FADE_DELAY = 3;
    /**
     * The number of steps of a cloak fade.
     */
    private static final int CLOAK_FADE_STEPS = 8;
    /**
     * The modifier data that starts a cloak: the fade delay and step {@code 0}.
     */
    private static final int CLOAK_FADE_START = CLOAK_FADE_DELAY << CLOAK_DELAY_SHIFT;
    /**
     * The modifier data that starts a decloak: the fade delay and the last step.
     */
    private static final int DECLOAK_FADE_START = CLOAK_FADE_START | CLOAK_FADE_STEPS;
    /**
     * The draw modifier of an image without a draw modifier.
     */
    private static final int NO_MODIFIER = 0;
    /**
     * The cloak counter of a unit that one source keeps cloaked this frame.
     */
    private static final int SINGLE_CLOAK_COUNT = 1;

    /**
     * The mask of an unsigned byte value.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * Fades an image over eight steps of four updates and sets or clears the unit cloak flags when the fade completes.
     *
     * @param image the fading image
     * @param unit  the script unit, or {@code null}
     */
    public static void updateImageCloak(@NotNull SpriteImage image, @Nullable Unit unit) {
        int modifier = image.getModifier();
        int modifierData = image.getModifierData1();
        int step = modifierData & BYTE_MASK;
        int delay = modifierData >>> CLOAK_DELAY_SHIFT & BYTE_MASK;

        if (modifier == SpriteImage.MODIFIER_CLOAKING || modifier == SpriteImage.MODIFIER_DETECTED_CLOAKING) {
            if (delay != 0) {
                image.setModifierData1(modifierData & ~CLOAK_DELAY_MASK | delay - 1 << CLOAK_DELAY_SHIFT);

                return;
            }

            step = step + 1 & BYTE_MASK;
            image.setModifierData1(modifierData & ~CLOAK_FADE_DATA_MASK | CLOAK_FADE_START | step);

            if (step >= CLOAK_FADE_STEPS) {
                // The cloaked modifier follows each cloaking modifier.
                image.setModifier(modifier + 1);
                image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);

                if (unit != null) {
                    unit.setStatus(unit.getStatus() | UnitStatusFlags.CLOAKED | UnitStatusFlags.REQUIRES_DETECTOR);
                }
            }
        } else if (modifier == SpriteImage.MODIFIER_DECLOAKING || modifier == SpriteImage.MODIFIER_DETECTED_DECLOAKING) {
            if (delay != 0) {
                image.setModifierData1(modifierData & ~CLOAK_DELAY_MASK | delay - 1 << CLOAK_DELAY_SHIFT);

                return;
            }

            if (step != 0) {
                image.setModifierData1(modifierData & ~CLOAK_FADE_DATA_MASK | CLOAK_FADE_START | step - 1);
            } else {
                image.setModifierData1(modifierData & ~CLOAK_DELAY_MASK | CLOAK_FADE_START);
                image.setModifier(NO_MODIFIER);
                image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);

                if (unit != null) {
                    unit.setStatus(unit.getStatus() & ~UnitStatusFlags.CLOAKED & ~UnitStatusFlags.REQUIRES_DETECTOR);
                }
            }
        }
    }

    /**
     * Marks a unit cloaked without the detector requirement and starts the decloak fade of its images: hidden images are shown and redrawn, and
     * cloaking or cloaked images, detected or not, take the matching decloaking modifier and the fade start.
     *
     * @param unit the unit
     * @throws IllegalStateException if the unit has no sprite
     */
    private static void decloakImages(@NotNull Unit unit) {
        unit.setStatus(unit.getStatus() & ~UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED);

        for (SpriteImage image : unit.requireSprite().getImages()) {
            if ((image.getFlags() & SpriteImage.FLAG_HIDDEN) != 0) {
                image.setFlags(image.getFlags() & ~SpriteImage.FLAG_HIDDEN | SpriteImage.FLAG_REDRAW);
            }

            int modifier = image.getModifier();

            if (modifier == SpriteImage.MODIFIER_CLOAKING || modifier == SpriteImage.MODIFIER_CLOAKED) {
                image.setModifier(SpriteImage.MODIFIER_DECLOAKING);
                image.setModifierData1(DECLOAK_FADE_START);
                image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);
            } else if (modifier == SpriteImage.MODIFIER_DETECTED_CLOAKING || modifier == SpriteImage.MODIFIER_DETECTED_CLOAKED) {
                image.setModifier(SpriteImage.MODIFIER_DETECTED_DECLOAKING);
                image.setModifierData1(DECLOAK_FADE_START);
                image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);
            }
        }
    }


    private final @NotNull Game game;

    /**
     * The cloaked-unit list head, including preplaced units linked while the map loads.
     */
    private @Nullable Unit firstCloakedUnit;

    /**
     * Creates a {@link UnitCloaking} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitCloaking(@NotNull Game game) {
        this.game = game;

        this.firstCloakedUnit = null;
    }

    /**
     * @return the head of the cloaked-unit list, or {@code null} when empty
     */
    public @Nullable Unit getFirstCloakedUnit() {
        return this.firstCloakedUnit;
    }

    /**
     * Sets the head of the cloaked-unit list.
     *
     * @param unit the new head of the cloaked-unit list, or {@code null}
     */
    public void setFirstCloakedUnit(@Nullable Unit unit) {
        this.firstCloakedUnit = unit;
    }


    /**
     * Queues the Arbiter idle order and starts cloaking nearby units.
     *
     * @param unit             the Arbiter
     * @param instructionLimit the order budget
     * @throws NullPointerException if the world was built without game data
     */
    public void updateInitializeArbiter(@NotNull Unit unit, int instructionLimit) {
        if (unit.getFirstOrder() == null) {
            this.game.getOrders().issueUntargeted(unit,
                                                  this.game.requireGameData().getUnitTypes()
                                                           .getReturnToIdleOrder(ScrUnitIds.PROTOSS_ARBITER),
                                                  instructionLimit);
        }

        if ((unit.getStatus() & UnitStatusFlags.HALLUCINATION) == 0) {
            ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.CLOAK_NEARBY_UNITS);
        }

        this.game.getOrders().completeOrder(unit, instructionLimit);
    }

    /**
     * Sustains the cloak of the owner's units within the Arbiter's air weapon range.
     * <p>
     * A unit without an air weapon uses the range of the none weapon ({@link UnitTargeting#getWeaponMaximumRange}).
     *
     * @param unit the Arbiter
     * @throws IllegalStateException         if the Arbiter has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the game has no unit finder
     */
    public void updateCloakNearbyUnits(@NotNull Unit unit) {
        int range = this.game.getUnitTargeting().getWeaponMaximumRange(unit, this.game.getUnitTargeting().getAirWeapon(unit));
        Sprite sprite = unit.requireSprite();
        // Build and clamp the box in signed WORDs; the range test compares unsigned.
        int left = (short) (sprite.getX() - range);
        int upper = (short) (sprite.getY() - range);
        int right = (short) (sprite.getX() + range);
        int lower = (short) (sprite.getY() + range);
        int mapWidth = this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE;
        int mapHeight = this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE;

        if (left < 0) {
            left = 0;
        } else if (right > mapWidth) {
            right = mapWidth;
        }

        if (upper < 0) {
            upper = 0;
        } else if (lower > mapHeight) {
            lower = mapHeight;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        UnitSpatialIndex spatialIndex = this.game.getUnitSystem().requireUnitSpatialIndex();
        int count = spatialIndex.collectPlacementCandidates(left, upper, right, lower);

        for (int index = 0; index < count; index++) {
            Unit target = spatialIndex.getPlacementCandidate(index);
            int unitType = target.getUnitId();
            int typeFlags = units.getFlags(unitType);

            if (target.getOwnerIndex() != unit.getOwnerIndex() || UnitQueries.isBuilding(units, unitType) || units.isCloakFieldExempt(unitType)) {
                continue;
            }

            if ((target.getStatus() & UnitStatusFlags.HALLUCINATION) != 0 || (typeFlags & UnitTypeFlags.POWERUP) != 0 ||
                target.getPrimaryOrder() == ScrOrderIds.WARP_IN) {
                continue;
            }

            if (!this.game.getUnitTargeting().isInRange(unit, target, range)) {
                continue;
            }

            this.setCloaked(target);
            target.setStatus(target.getStatus() | UnitStatusFlags.PASSIVELY_CLOAKED);
        }
    }

    /**
     * Cloaks a unit: a unit that is not burrowed plays the cloak sound and starts the cloak fade of its own and its subunit's images; a burrowed unit
     * that does not yet require a detector is linked and cloaked at once. Both then update the detection flags and restart the detection countdown.
     *
     * @param unit the unit to cloak
     * @throws IllegalStateException if a burrowed unit that does not require a detector yet has no sprite
     */
    public void cloakUnit(@NotNull Unit unit) {
        if ((unit.getStatus() & UnitStatusFlags.BURROWED) == 0) {
            this.game.getUnitSounds().play(SoundIds.UNIT_CLOAK, unit);
            this.cloakImages(unit);

            Unit subunit = unit.getSubunit();

            if (subunit != null) {
                this.cloakImages(subunit);
            }

            this.updateDetectedFlags(unit);
            unit.setVisibilityUpdateCountdown(DETECTION_UPDATE_INTERVAL);

            return;
        }

        if ((unit.getStatus() & UnitStatusFlags.REQUIRES_DETECTOR) == 0) {
            this.setCloaked(unit);
            unit.setStatus(unit.getStatus() | UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED);
            this.setSpriteCloakModifier(unit.requireSprite(), true, true, true, 0);
            ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.CLOAK);
            unit.setDetectionFlags(Unit.DETECTION_FLAGS_UNDETECTED);
        }

        this.updateDetectedFlags(unit);
        unit.setVisibilityUpdateCountdown(DETECTION_UPDATE_INTERVAL);
    }

    /**
     * Cloaks a preplaced unit: links it into the cloaked-unit list, applies the cloak modifier and status, sets the Cloak secondary order and marks
     * it undetected. A unit that already requires a detector is left unchanged.
     *
     * @param unit the preplaced unit
     * @throws IllegalStateException if a unit not already requiring detection has no sprite
     */
    public void cloakPreplaced(@NotNull Unit unit) {
        if ((unit.getStatus() & UnitStatusFlags.REQUIRES_DETECTOR) != 0) {
            return;
        }

        this.setCloaked(unit);
        this.setSpriteCloakModifier(unit.requireSprite(), true, true, true, 0);
        unit.setStatus(unit.getStatus() | (UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED));
        ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.CLOAK);
        unit.setDetectionFlags(Unit.DETECTION_FLAGS_UNDETECTED);
    }

    /**
     * Sustains the Cloak secondary order or clears the other cloak secondary order.
     *
     * @param unit the unit with the cloak secondary order
     */
    public void updateSecondary(@NotNull Unit unit) {
        if (unit.getSecondaryOrder() == ScrOrderIds.CLOAK) {
            this.setCloaked(unit);
        } else {
            ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.NOTHING);
        }
    }

    /**
     * Removes and decloaks units no longer kept cloaked this frame; refreshes the cloak of the remaining units.
     */
    public void updateCloakedUnits() {
        for (Unit unit = this.firstCloakedUnit; unit != null; ) {
            Unit next = unit.getNextCloakedUnit();

            if (unit.getCloakCounter() == 0) {
                this.unlink(unit);
                unit.setStatus(unit.getStatus() & ~UnitStatusFlags.PASSIVELY_CLOAKED);
                this.decloakUnit(unit);
            } else {
                if ((unit.getStatus() & UnitStatusFlags.BURROWED) == 0 && unit.getSecondaryOrder() == ScrOrderIds.CLOAK &&
                    unit.getCloakCounter() == SINGLE_CLOAK_COUNT && (unit.getStatus() & UnitStatusFlags.PASSIVELY_CLOAKED) != 0) {
                    unit.setStatus(unit.getStatus() & ~UnitStatusFlags.PASSIVELY_CLOAKED);
                }

                if ((unit.getStatus() & UnitStatusFlags.REQUIRES_DETECTOR) == 0) {
                    this.cloakUnit(unit);
                }
            }

            unit = next;
        }
    }

    /**
     * Updates the detection timer of a visible cloaked or burrowed unit: a nonzero countdown counts down, and a zero countdown updates the detection
     * flags and restarts the countdown.
     *
     * @param unit the visible cloaked or burrowed unit
     */
    public void updateDetectionTimer(@NotNull Unit unit) {
        int remaining = unit.getVisibilityUpdateCountdown();

        if (remaining != 0) {
            unit.setVisibilityUpdateCountdown(remaining - 1);

            return;
        }

        this.updateDetectedFlags(unit);
        unit.setVisibilityUpdateCountdown(DETECTION_UPDATE_INTERVAL);
    }

    /**
     * Clears the cloak counter of a dying unit that is not a building, unlinks it and clears its passive cloak. Decloaks the unit if it still
     * requires a detector.
     *
     * @param unit the dying unit
     * @throws NullPointerException if the world was built without game data
     */
    public void destroy(@NotNull Unit unit) {
        if ((this.game.requireGameData().getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.BUILDING) != 0) {
            return;
        }

        unit.setCloakCounter(0);

        if (unit.isCloakedLinked()) {
            this.unlink(unit);
        }

        unit.setStatus(unit.getStatus() & ~UnitStatusFlags.PASSIVELY_CLOAKED);

        if ((unit.getStatus() & UnitStatusFlags.REQUIRES_DETECTOR) != 0) {
            this.decloakUnit(unit);
        }
    }

    /**
     * Decloaks a unit: a unit that is not burrowed plays the decloak sound and starts the decloak fade of its own and its subunit's images; a
     * burrowed unit loses its cloak status, shows its images not drawn while cloaked and drops its cloak modifiers.
     *
     * @param unit the unit to decloak
     * @throws NullPointerException  if the world was built without game data
     * @throws IllegalStateException if a burrowed unit has no sprite
     */
    public void decloakUnit(@NotNull Unit unit) {
        if ((unit.getStatus() & UnitStatusFlags.BURROWED) == 0) {
            this.game.getUnitSounds().play(SoundIds.UNIT_DECLOAK, unit);
            decloakImages(unit);

            Unit subunit = unit.getSubunit();

            if (subunit != null) {
                decloakImages(subunit);
            }

            return;
        }

        unit.setStatus(unit.getStatus() & ~UnitStatusFlags.REQUIRES_DETECTOR & ~UnitStatusFlags.CLOAKED);

        Sprite sprite = unit.requireSprite();
        ScrImageTypeView images = this.game.requireGameData().getImageTypes();

        for (SpriteImage image : sprite.getImages()) {
            if (images.getDrawIfCloaked(image.getImageTypeId()) == 0) {
                if ((image.getFlags() & SpriteImage.FLAG_HIDDEN) != 0) {
                    image.setFlags(image.getFlags() & ~SpriteImage.FLAG_HIDDEN | SpriteImage.FLAG_REDRAW);
                }
            }

            if (image.getModifier() >= SpriteImage.MODIFIER_FIRST_CLOAK && image.getModifier() <= SpriteImage.MODIFIER_LAST_CLOAK) {
                image.setModifier(NO_MODIFIER);
                image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);
            }
        }
    }

    /**
     * Sets the cloak draw state of a rebuilt sprite: hides its images not drawn while cloaked; then a burrowed unit drops every cloak modifier, a
     * unit whose detector requirement differs from its cloak status gives every image without a modifier the cloaking or decloaking fade with the
     * retained fade data, and any other unit marks every image without a modifier or with the cloaking or decloaking modifier cloaked.
     *
     * @param sprite           the rebuilt sprite
     * @param requiresDetector whether the unit requires a detector
     * @param cloaked          whether the unit is cloaked
     * @param burrowed         whether the unit is burrowed
     * @param retainedData     the previous main image's packed fade step in the low byte and delay in the next byte
     * @throws NullPointerException if the world was built without game data
     */
    public void setSpriteCloakModifier(@NotNull Sprite sprite, boolean requiresDetector, boolean cloaked, boolean burrowed, int retainedData) {
        ScrImageTypeView images = this.game.requireGameData().getImageTypes();

        for (SpriteImage image : sprite.getImages()) {
            if (images.getDrawIfCloaked(image.getImageTypeId()) == 0) {
                image.setFlags(image.getFlags() | SpriteImage.FLAG_HIDDEN);
            }
        }

        if (burrowed) {
            for (SpriteImage image : sprite.getImages()) {
                if (image.getModifier() >= SpriteImage.MODIFIER_FIRST_CLOAK && image.getModifier() <= SpriteImage.MODIFIER_LAST_CLOAK) {
                    image.setModifier(NO_MODIFIER);
                    image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);
                }
            }
        } else if (requiresDetector != cloaked) {
            int modifier = requiresDetector ? SpriteImage.MODIFIER_CLOAKING : SpriteImage.MODIFIER_DECLOAKING;

            for (SpriteImage image : sprite.getImages()) {
                if (image.getModifier() == NO_MODIFIER) {
                    image.setModifier(modifier);
                    image.setModifierData1(image.getModifierData1() & ~CLOAK_FADE_DATA_MASK | retainedData & CLOAK_FADE_DATA_MASK);
                }
            }
        } else {
            for (SpriteImage image : sprite.getImages()) {
                int modifier = image.getModifier();

                if (modifier == NO_MODIFIER || modifier == SpriteImage.MODIFIER_CLOAKING || modifier == SpriteImage.MODIFIER_DECLOAKING) {
                    image.setModifier(SpriteImage.MODIFIER_CLOAKED);
                }
            }
        }
    }


    /**
     * Updates detection flags and removes references held by players that lose detection. A unit the local player stops detecting leaves the local
     * selection and hides its solid circle.
     *
     * @param unit the cloaked or burrowed unit
     * @throws IllegalStateException if the local player stops detecting the unit and the unit has no sprite
     */
    void updateDetectedFlags(@NotNull Unit unit) {
        int flags = this.calculateDetectedFlags(unit);

        if (unit.getDetectionFlags() == flags) {
            return;
        }

        int previous = unit.getDetectionFlags();

        if (previous == Unit.DETECTION_FLAGS_UNDETECTED) {
            previous = ~flags & BYTE_MASK;
        }

        int removed = previous & ~flags;

        unit.setDetectionFlags(flags);

        if (removed != 0) {
            this.removeUndetectedReferences(unit, removed);
        }

        // A unit the local player stops detecting leaves the local selection and loses its solid circle.
        int comparisonMask = this.game.getLocalVision().getComparisonMask();

        if (((previous ^ flags) & comparisonMask) == 0 || (flags & comparisonMask) != 0) {
            return;
        }

        Sprite sprite = unit.requireSprite();

        if ((sprite.getFlags() & Sprite.FLAG_SELECTED) != 0) {
            this.game.getInterfaceLink().getLocalSelection().removeUnit(unit);
        }

        this.game.getSelectionImages().hideSelectionCircle(sprite);
    }

    /**
     * Drops the references to a unit that players no longer detect: the mask is cut to the twelve player bits, and for each set bit, players
     * {@code 0..11} in order, every unit of that player's owner list drops its references to the unit. The detection DWORD itself may hold any bits
     * (an EUD write, or {@code 0xFFFFFFFF} from the unit initialization); bits from {@code 12} up select no owner list.
     *
     * @param unit    the unit that is no longer detected
     * @param removed the players that lost detection, one bit per player
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    void removeUndetectedReferences(@NotNull Unit unit, int removed) {
        int players = removed & DETECTION_PLAYER_BITS;

        for (int player = 0; players != 0; player++) {
            if ((players & 1 << player) == 0) {
                continue;
            }

            players &= ~(1 << player);

            UnitPool pool = this.game.getUnitSystem().requireUnitPool();

            for (int index = pool.getFirstOwnerIndex(player); index != 0; index = pool.getNextOwnerIndex(index)) {
                this.game.getUnitLifecycle().removeTargetReferences(pool.getUnit(index), unit);
            }
        }
    }

    /**
     * Keeps a unit cloaked this frame: increments its cloak counter, and links the unit at the head of the cloaked-unit list when this is its first
     * cloak source, it does not require a detector yet and it is not linked.
     *
     * @param unit the unit to keep cloaked
     */
    void setCloaked(@NotNull Unit unit) {
        unit.setCloakCounter(unit.getCloakCounter() + 1);

        if (unit.getCloakCounter() == SINGLE_CLOAK_COUNT && (unit.getStatus() & UnitStatusFlags.REQUIRES_DETECTOR) == 0 && !unit.isCloakedLinked()) {
            Unit first = this.firstCloakedUnit;

            unit.setPreviousCloakedUnit(null);
            unit.setNextCloakedUnit(first);

            if (first != null) {
                first.setPreviousCloakedUnit(unit);
            }

            this.firstCloakedUnit = unit;
            unit.setCloakedLinked(true);
        }
    }

    /**
     * Makes a unit require a detector and starts the cloak fade of its images: images without a modifier or decloaking take the cloaking modifier and
     * the fade start, images not drawn while cloaked are hidden, and the unit is detected by nobody.
     *
     * @param unit the unit
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    private void cloakImages(@NotNull Unit unit) {
        unit.setStatus(unit.getStatus() & ~UnitStatusFlags.CLOAKED | UnitStatusFlags.REQUIRES_DETECTOR);

        ScrImageTypeView images = this.game.requireGameData().getImageTypes();

        for (SpriteImage image : unit.requireSprite().getImages()) {
            int modifier = image.getModifier();

            if (modifier == NO_MODIFIER || modifier == SpriteImage.MODIFIER_DECLOAKING || modifier == SpriteImage.MODIFIER_DETECTED_DECLOAKING) {
                image.setModifier(SpriteImage.MODIFIER_CLOAKING);
                image.setModifierData1(CLOAK_FADE_START);
            }

            if (images.getDrawIfCloaked(image.getImageTypeId()) == 0) {
                image.setFlags(image.getFlags() | SpriteImage.FLAG_HIDDEN);
            }
        }

        unit.setDetectionFlags(Unit.DETECTION_FLAGS_UNDETECTED);
    }

    /**
     * Calculates the players that detect a cloaked or burrowed unit.
     *
     * @param unit the unit
     * @return the detecting players as a player mask
     * @throws IllegalStateException         if no status effect reveals the unit and it has no sprite
     * @throws UnsupportedOperationException if no status effect reveals the unit and it carries a powerup, or required state is missing
     */
    private int calculateDetectedFlags(@NotNull Unit unit) {
        if (unit.getDefensiveMatrixHealth() != 0 || unit.getLockdownTimer() != 0 || unit.getMaelstromTimer() != 0 ||
            unit.getIrradiateTimer() != 0 || unit.getSpeedDecreaseByte() != 0 || unit.getStasisTimer() != 0 ||
            unit.getPlagueTimer() != 0 || unit.getAcidSporeCount() != 0) {
            return DETECTED_BY_PLAYABLE_PLAYERS;
        }

        if (unit.hasCarriedPowerup()) {
            throw Exceptions.newUnsupportedOperationException("Detection of flag carriers requires the carried unit reference.");
        }

        SightCoverage coverage = this.game.getSightCoverage();

        if (coverage == null) {
            throw Exceptions.newUnsupportedOperationException("Detection requires shared vision masks.");
        }

        int flags = 1 << unit.getOwnerIndex() | coverage.getSharedVisionMask(unit.getOwnerIndex()) | unit.getParasiteFlags();

        for (int player = 0; player < PlayerTable.PLAYER_SLOTS; player++) {
            if ((unit.getParasiteFlags() & 1 << player) != 0) {
                flags |= coverage.getSharedVisionMask(player);
            }
        }

        Sprite sprite = unit.requireSprite();
        UnitSpatialIndex spatialIndex = this.game.getUnitSystem().requireUnitSpatialIndex();
        int count = spatialIndex.collectPlacementCandidates(sprite.getX() - MAXIMUM_DETECTION_RANGE, sprite.getY() - MAXIMUM_DETECTION_RANGE,
                                                            sprite.getX() + MAXIMUM_DETECTION_RANGE, sprite.getY() + MAXIMUM_DETECTION_RANGE);

        for (int index = 0; index < count; index++) {
            flags |= this.calculateDetectorFlags(unit, spatialIndex.getPlacementCandidate(index), coverage);
        }

        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int index = pool.getFirstRevealerIndex(); index != 0; index = pool.getNextCreatedIndex(index)) {
            flags |= this.calculateDetectorFlags(unit, pool.getUnit(index), coverage);
        }

        return flags;
    }

    /**
     * Calculates the players that detect a unit through one detector.
     *
     * @param unit     the unit
     * @param detector the candidate detector
     * @param coverage the sight coverage with the shared vision masks
     * @return the detector's owner, the players it shares vision with and its parasite flags, or {@code 0} if it does not detect the unit
     * @throws IllegalStateException if the candidate is a completed, enabled, not blinded and not hallucinated detector other than the unit, and the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    private int calculateDetectorFlags(@NotNull Unit unit, @NotNull Unit detector, @NotNull SightCoverage coverage) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if ((units.getFlags(detector.getUnitId()) & UnitTypeFlags.DETECTOR) == 0 ||
            (detector.getStatus() & UnitStatusFlags.COMPLETED) == 0 || UnitOrderEligibility.isDisabled(detector) ||
            detector.getBlindnessState() != 0) {
            return 0;
        }

        if (detector == unit || (detector.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
            return 0;
        }

        if ((unit.requireSprite().getVisibilityFlags() & 1 << detector.getOwnerIndex()) == 0) {
            return 0;
        }

        int range = UnitQueries.isGroundedBuilding(detector)
                    ? BUILDING_DETECTION_RANGE
                    : TerrainTileset.TILE_SIZE * this.game.getUnitVisibility().calculateSightRange(detector, false);

        if (!this.game.getUnitTargeting().isInRange(detector, unit, range)) {
            return 0;
        }

        return 1 << detector.getOwnerIndex() | coverage.getSharedVisionMask(detector.getOwnerIndex()) | detector.getParasiteFlags();
    }

    private void unlink(@NotNull Unit unit) {
        Unit previous = unit.getPreviousCloakedUnit();
        Unit next = unit.getNextCloakedUnit();

        if (previous != null) {
            previous.setNextCloakedUnit(next);
        } else {
            this.firstCloakedUnit = next;
        }

        if (next != null) {
            next.setPreviousCloakedUnit(previous);
        }

        unit.setPreviousCloakedUnit(null);
        unit.setNextCloakedUnit(null);
        unit.setCloakedLinked(false);
    }
}
