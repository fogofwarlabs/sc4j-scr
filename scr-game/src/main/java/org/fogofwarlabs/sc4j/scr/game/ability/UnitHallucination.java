package org.fogofwarlabs.sc4j.scr.game.ability;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.ai.UnitStrengthTable;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.production.UnitMorphing;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;

/**
 * Initializes newly constructed hallucinations and converts preplaced units into hallucinations.
 * <p>
 * Created hallucinations leave ordinary accounting and become completed at full hit points before receiving their human idle order. Preplaced
 * conversion also removes completed accounting, updates drawing and strengths, sets the secondary order and converts turrets and stored Scarabs or
 * releases Interceptors.
 */
public final class UnitHallucination {
    /**
     * The default removal timer of a hallucination.
     */
    public static final int HALLUCINATION_REMOVAL_TIMER = 1350;
    /**
     * The default removal timer of a Broodling.
     */
    public static final int BROODLING_REMOVAL_TIMER = 1800;

    /**
     * The modifier bits that keep an image unchanged: only normal and hallucination images are converted.
     */
    private static final int KEPT_MODIFIER_MASK = 0xFF & ~SpriteImage.MODIFIER_HALLUCINATION;
    /**
     * The mask of the WORD removal timer.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * Sets the WORD removal timer of a unit when it is {@code 0} or larger than the request.
     *
     * @param unit  the unit
     * @param timer the requested removal timer
     */
    public static void setRemovalTimer(@NotNull Unit unit, int timer) {
        int current = unit.getRemainingLifetime() & WORD_MASK;

        if (current == 0 || (timer & WORD_MASK) < current) {
            unit.setRemainingLifetime(timer & WORD_MASK);
        }
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitHallucination} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitHallucination(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Initializes a newly constructed unit as a completed hallucination with full hit points and its human idle order.
     *
     * @param unit                       the newly constructed unit
     * @param preserveNegativeTypeCounts the accounting compatibility flag
     * @param instructionLimit           the idle order budget
     * @throws UnsupportedOperationException if initialized unit accounting is absent
     * @throws NullPointerException          if the world was built without game data
     */
    public void initializeCreatedHallucination(@NotNull Unit unit, boolean preserveNegativeTypeCounts, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        UnitAccounting accounting = this.game.getPlayerTable().requireUnitAccounting();

        accounting.update(unit, units, -1, preserveNegativeTypeCounts);
        UnitMorphing.writeButtonSet(units, unit, ButtonSetIds.MIXED_UNITS);

        int hitPoints = units.getHitPoints(unit.getUnitId());

        unit.setStatus(unit.getStatus() | UnitStatusFlags.HALLUCINATION | UnitStatusFlags.COMPLETED);
        unit.setHitPoints(hitPoints);
        accounting.update(unit, units, 1, preserveNegativeTypeCounts);
        accounting.updateCompleted(unit, units, 1, false, this.game.getPlayerTable().getProductionStatistics());
        this.game.getOrders().issueUntargeted(unit, units.getHumanIdleOrder(unit.getUnitId()), instructionLimit);
    }

    /**
     * Converts a unit, its subunit and a Reaver's stored Scarabs into hallucinations.
     *
     * @param unit                       the unit to convert
     * @param preserveNegativeTypeCounts the accounting compatibility flag
     * @param replayObservation          whether the game is observed as a replay (every player is seen)
     * @param localPlayer                the local player
     * @param instructionLimit           the order budget of killed Interceptors
     * @throws UnsupportedOperationException if the unit type is past {@link ScrUnitIds#NONE}, required state is absent or a Reaver has fewer stored Scarabs than its hangar count
     * @throws NullPointerException          if the world was built without game data
     */
    public void convertToHallucination(@NotNull Unit unit,
                                       boolean preserveNegativeTypeCounts,
                                       boolean replayObservation,
                                       int localPlayer,
                                       int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        UnitAccounting accounting = this.game.getPlayerTable().requireUnitAccounting();

        int unitType = unit.getUnitId();

        if (unitType > ScrUnitIds.NONE && !units.isAddedType(unitType)) {
            throw Exceptions.newUnsupportedOperationException("Hallucination of unit type {0} past {1} is not modeled.",
                                                              unitType, ScrUnitIds.NONE);
        }

        int specialFlags = units.getFlags(unitType);

        accounting.update(unit, units, -1, preserveNegativeTypeCounts);
        accounting.updateCompleted(unit, units, -1, true, this.game.getPlayerTable().getProductionStatistics());

        UnitMorphing.writeButtonSet(units, unit, ButtonSetIds.MIXED_UNITS);

        unit.setStatus(unit.getStatus() | UnitStatusFlags.HALLUCINATION);
        accounting.update(unit, units, 1, preserveNegativeTypeCounts);
        accounting.updateCompleted(unit, units, 1, false, this.game.getPlayerTable().getProductionStatistics());

        if (replayObservation || unit.getOwnerIndex() == localPlayer) {
            Sprite sprite = unit.getSpriteOrNull();

            if (sprite == null) {
                throw Exceptions.newUnsupportedOperationException("Hallucination drawing requires the unit sprite.");
            }

            for (SpriteImage image : sprite.getImages()) {
                if ((image.getModifier() & KEPT_MODIFIER_MASK) != 0) {
                    continue;
                }

                // The first modifier data is cleared with the modifier.
                image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);
                image.setModifier(SpriteImage.MODIFIER_HALLUCINATION);
                image.setModifierData1(0);
            }
        }

        if ((specialFlags & UnitTypeFlags.SUBUNIT) != 0) {
            return;
        }

        setRemovalTimer(unit, HALLUCINATION_REMOVAL_TIMER);

        UnitStrengthTable strength = this.game.getUnitStrengthTable();

        if (strength == null) {
            throw Exceptions.newUnsupportedOperationException("Hallucination strength refresh requires the strength tables.");
        }

        strength.update(this.game, unit, units);

        ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.HALLUCINATION2);

        Unit subunit = unit.getSubunit();

        if (subunit != null) {
            this.convertToHallucination(subunit, preserveNegativeTypeCounts, replayObservation, localPlayer, instructionLimit);
        }

        if (this.game.requireGameData().getUnitTypes().isReaver(unitType)) {
            Unit scarab = unit.getFirstHangarUnit(false);

            for (int remaining = unit.getHangarCount(false); remaining != 0; remaining--) {
                if (scarab == null) {
                    throw Exceptions.newUnsupportedOperationException("The Reaver hangar count exceeds its stored Scarabs.");
                }

                this.convertToHallucination(scarab, preserveNegativeTypeCounts, replayObservation, localPlayer, instructionLimit);

                scarab = scarab.getNextHangarUnit();
            }
        } else if (this.game.requireGameData().getUnitTypes().isCarrier(unitType)) {
            this.game.getUnitHangar().releaseHangar(unit, instructionLimit);
        }
    }
}
