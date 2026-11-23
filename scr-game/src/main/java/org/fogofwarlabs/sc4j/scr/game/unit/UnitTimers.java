package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;

/**
 * Advances cooldowns, regeneration and lifetime for one unit in frame processing order.
 * <p>
 * Byte and WORD counters wrap at their stored widths.
 */
public final class UnitTimers {
    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;
    private static final int FIXED_POINT_FRACTION_BITS = 8;

    /**
     * The shift of the last of the four BYTE timers of the cooldown timers DWORD, and the width of each.
     */
    private static final int LAST_TIMER_SHIFT = 24;
    private static final int TIMER_BITS = 8;
    /**
     * The shields, hit points and energy regenerated per frame, with eight fraction bits.
     */
    private static final int SHIELD_REGENERATION = 7;
    private static final int HIT_POINT_REGENERATION = 4;
    private static final int ENERGY_REGENERATION = 8;
    /**
     * The frames of one status timer cycle.
     */
    private static final int STATUS_CYCLE_FRAMES = 8;
    /**
     * The energy a Dark Archon completing its summon regenerates up to, with eight fraction bits.
     */
    private static final int ARCHON_SUMMON_MAXIMUM_ENERGY = 50 << FIXED_POINT_FRACTION_BITS;
    /**
     * The damage a heavily damaged Terran building ({@link UnitQueries#isHeavilyDamaged}) takes per frame.
     */
    private static final int BURN_DAMAGE = 20;
    /**
     * The energy a cloaked Ghost type and a cloaked Wraith type pay per frame, with eight fraction bits.
     */
    private static final int GHOST_CLOAK_COST = 10;
    private static final int WRAITH_CLOAK_COST = 13;

    private static int getCloakEnergyCost(@NotNull ScrUnitTypeView units, int unitType) {
        int baseType = units.getBaseType(unitType);

        if (units.isGhost(unitType) || baseType == ScrUnitIds.HERO_INFESTED_KERRIGAN) {
            return GHOST_CLOAK_COST;
        }

        return baseType == ScrUnitIds.TERRAN_WRAITH || baseType == ScrUnitIds.HERO_TOM_KAZANSKY ? WRAITH_CLOAK_COST : 0;
    }

    private static void redrawSelectionImages(@NotNull Sprite sprite) {
        for (SpriteImage image : sprite.getImages()) {
            if (image.getModifier() == SpriteImage.MODIFIER_HEALTH_BAR) {
                image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);
            }
        }
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitTimers} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitTimers(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Advances byte cooldowns, then shield regeneration, periodic status checks and completed-unit maintenance. Shield and
     * energy values retain eight fractional bits. Byte and WORD counters wrap at their stored widths.
     *
     * @param unit the unit to update, with its sprite and pool state established
     * @throws UnsupportedOperationException if the unit has no sprite
     */
    public void update(@NotNull Unit unit) {
        this.update(unit, Integer.MAX_VALUE);
    }

    /**
     * Advances unit timers with an explicit budget for health-regeneration images.
     *
     * @param unit             the unit to update
     * @param instructionLimit the image creation instruction budget
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit has no sprite
     */
    public void update(@NotNull Unit unit, int instructionLimit) {
        int cooldownTimers = unit.getCooldownTimers();

        for (int shift = 0; shift <= LAST_TIMER_SHIFT; shift += TIMER_BITS) {
            int remaining = (cooldownTimers >>> shift) & BYTE_MASK;

            if (remaining != 0) {
                cooldownTimers = cooldownTimers & ~(BYTE_MASK << shift) | (remaining - 1) << shift;

                unit.setCooldownTimers(cooldownTimers);
            }
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Unit timer updates require the retained sprite.");
        }

        if (units.getShieldEnabled(unitType) != 0) {
            int maximumShields = units.getShieldAmount(unitType) << FIXED_POINT_FRACTION_BITS;
            int shields = unit.getShieldPoints();

            if (shields != maximumShields) {
                unit.setShieldPoints(Math.min(shields + SHIELD_REGENERATION, maximumShields));

                if ((sprite.getFlags() & Sprite.FLAG_SELECTED) != 0) {
                    redrawSelectionImages(sprite);
                }
            }
        }

        if (units.hasCooldownOrderTimer(unitType) && ((unit.getCooldownTimers() >>> Unit.GROUND_COOLDOWN_SHIFT) & BYTE_MASK) == 0) {
            unit.setPrimaryOrderCountdown(0);
        }

        unit.setHealingState(0);

        boolean completed = (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0;

        if (completed || (sprite.getFlags() & Sprite.FLAG_HIDDEN) == 0) {
            int elapsed = (unit.getStatusCycleElapsed() + 1) & BYTE_MASK;

            unit.setStatusCycleElapsed(elapsed);

            if (elapsed >= STATUS_CYCLE_FRAMES) {
                unit.setStatusCycleElapsed(0);
                this.game.getUnitSpells().updateStatusTimers(unit, instructionLimit);
            }
        }

        if (!completed) {
            return;
        }

        int abilityFlags = units.getFlags(unitType);
        int hitPoints = unit.getHitPoints();
        int maximumHitPoints = this.game.getUnitHealth().getMaximumHitPoints(unit);

        if ((abilityFlags & UnitTypeFlags.REGENERATES_HIT_POINTS) != 0 && hitPoints > 0 && hitPoints != maximumHitPoints) {
            this.game.getUnitHealth().setHitPoints(unit, hitPoints + HIT_POINT_REGENERATION, instructionLimit);
        }

        if (UnitTypeTraitsUtils.isSpellcaster(units, unit)) {
            // An actively cloaked unit pays its cloak cost instead of regenerating; a summoning Dark Archon stops at 50.
            if ((unit.getStatus() & (UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED)) != 0 &&
                (unit.getStatus() & UnitStatusFlags.PASSIVELY_CLOAKED) == 0) {
                int cost = getCloakEnergyCost(units, unitType);

                if (!this.game.getUnitEnergy().hasEnergy(unit, cost)) {
                    if (unit.getSecondaryOrder() == ScrOrderIds.CLOAK) {
                        ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.NOTHING);
                    }
                } else {
                    this.game.getUnitEnergy().spend(unit, cost);
                }
            } else {
                int maximumEnergy = this.game.getUnitInitializer().getMaximumEnergy(unit, units);

                if (units.getBaseType(unitType) == ScrUnitIds.PROTOSS_DARK_ARCHON &&
                    unit.getPrimaryOrder() == ScrOrderIds.COMPLETING_ARCHON_SUMMON &&
                    unit.getPrimaryOrderState() == 0) {
                    maximumEnergy = ARCHON_SUMMON_MAXIMUM_ENERGY;
                }

                int energy = unit.getEnergy();

                if (energy != maximumEnergy) {
                    unit.setEnergy(Math.min((energy + ENERGY_REGENERATION) & WORD_MASK, maximumEnergy));

                    if ((sprite.getFlags() & Sprite.FLAG_SELECTED) != 0) {
                        redrawSelectionImages(sprite);
                    }
                }
            }
        }

        int remaining = unit.getMovementDestinationCountdown();

        if (remaining != 0) {
            unit.setMovementDestinationCountdown(remaining - 1);
        }

        remaining = unit.getRemainingLifetime();

        if (remaining != 0) {
            unit.setRemainingLifetime(remaining - 1);

            if (remaining == 1) {
                // An expired removal timer kills the unit.
                this.game.getUnitLifecycle().kill(unit, instructionLimit);

                return;
            }
        }

        int raceFlags = units.getGroupFlags(unitType);

        if ((raceFlags & (UnitGroupFlags.ZERG | UnitGroupFlags.TERRAN | UnitGroupFlags.PROTOSS)) == UnitGroupFlags.TERRAN &&
            (UnitQueries.isGroundedBuilding(unit) || (abilityFlags & UnitTypeFlags.FLYING_BUILDING) != 0)) {
            if (this.game.getUnitQueries().isHeavilyDamaged(unit)) {
                this.game.getUnitDamage().dealUnitDamage(unit, BURN_DAMAGE, null, unit.getLastAttackingPlayer(), true, instructionLimit);
            }
        }
    }
}
