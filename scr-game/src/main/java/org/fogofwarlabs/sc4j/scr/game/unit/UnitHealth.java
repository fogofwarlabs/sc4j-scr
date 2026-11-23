package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.type.ScrImageTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ImageIds;
import org.fogofwarlabs.sc4j.scr.game.ai.UnitStrengthTable;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Applies shared hit-point writes, strength refresh and pooled damage overlays, and reads the maximum hit points of a unit
 * ({@link #getMaximumHitPoints}), which every read of a unit's maximum goes through.
 */
public final class UnitHealth {
    /**
     * The positions the damage overlay search cycles through.
     */
    private static final int DAMAGE_OVERLAY_POSITIONS = 22;
    /**
     * The overlay offset of an unused position, in both coordinates.
     */
    private static final int UNUSED_OVERLAY_OFFSET = 127;
    /**
     * The fewest overlay states that leave a usable position.
     */
    private static final int MINIMUM_OVERLAY_STATES = 2;
    /**
     * The random source of the damage overlay position.
     */
    private static final int OVERLAY_RANDOM_SOURCE = 8;
    /**
     * The damage overlays cover the hit points below two thirds of the maximum.
     */
    private static final int HIT_POINT_THIRDS = 3;
    /**
     * The divisor of the overlay state count that bounds the random position choice.
     */
    private static final int OVERLAY_RANDOM_RANGE_DIVISOR = 2;

    private static @Nullable SpriteImage findImage(@NotNull Sprite sprite, int minimum, int maximum) {
        for (SpriteImage image : sprite.getImages()) {
            if (image.getImageTypeId() >= minimum && image.getImageTypeId() <= maximum) {
                return image;
            }
        }

        return null;
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitHealth} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitHealth(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Returns the maximum hit points of a unit: the {@code units.dat} hit points of its type, adjusted by the simulation plugins
     * ({@link ScrSimulationPlugins#adjustMaximumHitPoints}).
     *
     * @param unit the unit
     * @return the maximum hit points with eight fractional bits
     * @throws NullPointerException if the world was built without game data
     */
    public int getMaximumHitPoints(@NotNull Unit unit) {
        int maximum = this.game.requireGameData().getUnitTypes().getHitPoints(unit.getUnitId());
        ScrSimulationPlugins plugins = this.game.getSimulationPlugins();

        return plugins != null ? plugins.adjustMaximumHitPoints(unit, maximum) : maximum;
    }

    /**
     * Sets bounded fixed-point health and refreshes completed-unit presentation and strength.
     *
     * @param unit             the receiving unit
     * @param hitPoints        the requested health with eight fractional bits
     * @param instructionLimit the image creation instruction budget
     * @throws IllegalStateException         if the unit has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the strength tables are not initialized
     */
    public void setHitPoints(@NotNull Unit unit, int hitPoints, int instructionLimit) {
        unit.setHitPoints(Math.min(hitPoints, this.getMaximumHitPoints(unit)));

        Sprite sprite = unit.requireSprite();

        if ((sprite.getFlags() & Sprite.FLAG_SELECTED) != 0) {
            int comparisonMask = this.game.getLocalVision().getComparisonMask();

            for (SpriteImage image : sprite.getImages()) {
                if ((sprite.getVisibilityFlags() & comparisonMask) != 0 && image.getModifier() == SpriteImage.MODIFIER_HEALTH_BAR) {
                    image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);
                }
            }
        }

        if ((unit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
            this.updateDamageOverlays(unit, instructionLimit);

            UnitStrengthTable strength = this.game.getUnitStrengthTable();

            if (strength == null) {
                throw Exceptions.newUnsupportedOperationException("Hit-point writes require initialized strength tables.");
            }

            strength.update(this.game, unit, this.game.requireGameData().getUnitTypes());
        }
    }


    /**
     * Updates the small and large flame image states using the damage-overlay table.
     *
     * @param unit             the damaged or repaired unit
     * @param instructionLimit the image creation instruction budget
     * @throws IllegalStateException         if the main image is absent, overlay data cannot be loaded, or the position search finds no usable position or exceeds its instruction limit
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the image pool is not initialized
     */
    public void updateDamageOverlays(@NotNull Unit unit, int instructionLimit) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null || sprite.getMainImage() == null) {
            throw Exceptions.newIllegalStateException("Damage overlays require the main image.");
        }

        SpriteImage mainImage = sprite.getMainImage();

        if (this.game.requireGameData().getImageTypes().getDamageOverlay(mainImage.getImageTypeId()) == 0) {
            return;
        }

        try {
            int states = this.game.requireGameData().getImageDamageOverlayCount(mainImage.getImageTypeId());
            int maximum = this.getMaximumHitPoints(unit);
            int twoThirds = maximum - maximum / HIT_POINT_THIRDS;
            int healthPerState = Math.max(1, twoThirds / (states + 1));
            int damageState = Math.max(0, (unit.getHitPoints() - twoThirds % healthPerState - maximum / HIT_POINT_THIRDS - 1) / healthPerState);

            while (unit.getDamageOverlayCount() < damageState) {
                SpriteImage large = findImage(sprite, ImageIds.FLAMES1_TYPE1_LARGE, ImageIds.FLAMES8_TYPE3_LARGE);

                if (large != null) {
                    int position = large.getImageTypeId() - ImageIds.FLAMES1_TYPE1_LARGE;

                    sprite.removeImage(large);
                    this.game.getSpriteSystem().requireImagePool().recycle(large);
                    this.createFlame(unit, mainImage, ImageIds.FLAMES1_TYPE1_SMALL + position, position, instructionLimit);
                } else {
                    // Only the first small flame found is destroyed.
                    SpriteImage small = findImage(sprite, ImageIds.FLAMES1_TYPE1_SMALL, ImageIds.FLAMES8_TYPE3_SMALL);

                    if (small != null) {
                        sprite.removeImage(small);
                        this.game.getSpriteSystem().requireImagePool().recycle(small);
                    }
                }

                unit.setDamageOverlayCount(unit.getDamageOverlayCount() + 1);
            }

            while (unit.getDamageOverlayCount() > damageState) {
                SpriteImage small = findImage(sprite, ImageIds.FLAMES1_TYPE1_SMALL, ImageIds.FLAMES8_TYPE3_SMALL);

                if (small != null) {
                    int position = small.getImageTypeId() - ImageIds.FLAMES1_TYPE1_SMALL;

                    sprite.removeImage(small);
                    this.game.getSpriteSystem().requireImagePool().recycle(small);
                    this.createFlame(unit, mainImage, ImageIds.FLAMES1_TYPE1_LARGE + position, position, instructionLimit);
                } else {
                    if (states < MINIMUM_OVERLAY_STATES) {
                        throw Exceptions.newIllegalStateException("Damage overlays have no usable positions.");
                    }

                    int selected = this.game.getRandom().next(OVERLAY_RANDOM_SOURCE) % (states / OVERLAY_RANDOM_RANGE_DIVISOR) + 1;
                    int visited = 0;
                    boolean created = false;

                    for (int attempt = 0; attempt < instructionLimit && !created; attempt++) {
                        int position = attempt % DAMAGE_OVERLAY_POSITIONS;
                        int offset = this.game.requireGameData().getImageOverlayOffset(mainImage.getImageTypeId(),
                                                                                       ScrImageTypeView.OVERLAY_DAMAGE,
                                                                                       mainImage.getDrawnFrameIndex(),
                                                                                       position);

                        if ((short) offset == UNUSED_OVERLAY_OFFSET && (short) (offset >>> Short.SIZE) == UNUSED_OVERLAY_OFFSET) {
                            continue;
                        }

                        visited++;

                        if (visited >= selected &&
                            findImage(sprite, ImageIds.FLAMES1_TYPE1_LARGE + position, ImageIds.FLAMES1_TYPE1_LARGE + position) == null) {
                            this.createFlame(unit, mainImage, ImageIds.FLAMES1_TYPE1_SMALL + position, position, instructionLimit);

                            created = true;
                        }
                    }

                    if (!created) {
                        throw Exceptions.newIllegalStateException("Damage-overlay position search exceeded its instruction limit.");
                    }
                }

                unit.setDamageOverlayCount(unit.getDamageOverlayCount() - 1);
            }
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Cannot load damage-overlay coordinates.");
        }
    }

    private void createFlame(@NotNull Unit unit, @NotNull SpriteImage mainImage, int imageType, int position, int instructionLimit) throws IOException {
        int offset = this.game.requireGameData().getImageOverlayOffset(mainImage.getImageTypeId(),
                                                                       ScrImageTypeView.OVERLAY_DAMAGE,
                                                                       mainImage.getDrawnFrameIndex(),
                                                                       position);
        int horizontal = (short) offset;

        if ((mainImage.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0) {
            horizontal = -horizontal;
        }

        Sprite sprite = unit.requireSprite();

        this.game.getIscriptExecutor().createImage(sprite,
                                                   sprite.getImages().getFirst(),
                                                   imageType,
                                                   horizontal, (short) (offset >>> Short.SIZE),
                                                   0,
                                                   unit,
                                                   instructionLimit);
    }
}
