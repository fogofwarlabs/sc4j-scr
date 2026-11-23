package org.fogofwarlabs.sc4j.scr.game.combat;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.combat.CombatSystem;
import org.fogofwarlabs.sc4j.engine.combat.WeaponSlot;
import org.fogofwarlabs.sc4j.engine.content.type.WeaponType;
import org.fogofwarlabs.sc4j.scr.content.ScrNativeIds;
import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Serves the SC:R targeting rules ({@link UnitTargeting}) as the engine {@link CombatSystem}: enemies, attack gates, weapon range, the weapon
 * of a target kind, the pursuit of an attack target ({@link UnitAttack#tryFollowTarget}) and damage after armor and shields
 * ({@link UnitDamage#dealUnitDamage}). The view holds no state and is no state hash contributor.
 * <p>
 * A weapon hit (armor, shields, the hit count and the facing of the attacker) stays inside the SC:R weapon rules of {@link UnitDamage}.
 */
public final class ScrCombatSystem implements CombatSystem {
    private @Nullable Game game;

    /**
     * Creates a {@link ScrCombatSystem} instance; it serves the SC:R game it finds when it is attached.
     */
    public ScrCombatSystem() {
        this.game = null;
    }

    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Binds the SC:R game system of the game.
     *
     * @param game the game
     */
    @Override
    public void attach(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game) {
        this.game = game.getModSystem(Game.SYSTEM_KEY);
    }

    /**
     * Checks whether a unit treats another unit as an enemy.
     * <p>
     * The test is {@link UnitTargeting#isEnemy(Unit, Unit)}: the owner of the unit is not allied to the owner of the other unit, the sprite owner
     * for a neutral unit.
     *
     * @param unit      the unit
     * @param otherUnit the other unit
     * @return {@code true} if the owners are enemies, {@code false} otherwise
     * @throws IllegalArgumentException      if a unit is not an SC:R unit
     * @throws IllegalStateException         if the system is not attached, or a neutral unit has no sprite
     * @throws UnsupportedOperationException if the alliances are unavailable
     */
    @Override
    public boolean isEnemy(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit, org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit otherUnit) {
        return this.requireGame().getUnitTargeting().isEnemy(Unit.of(unit), Unit.of(otherUnit));
    }

    /**
     * Checks whether a unit can attack a target at all: it has a weapon for the target and the target can be attacked.
     * <p>
     * The test is {@link UnitTargeting#canAttackTarget}.
     *
     * @param unit       the attacking unit
     * @param targetUnit the target unit, or {@code null} for none
     * @return {@code true} if the unit can attack the target, {@code false} otherwise or without target
     * @throws IllegalArgumentException if a unit is not an SC:R unit
     * @throws IllegalStateException    if the system is not attached, or the target has no sprite
     */
    @Override
    public boolean canAttack(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit, org.fogofwarlabs.sc4j.engine.entity.@Nullable Unit targetUnit) {
        return this.requireGame().getUnitTargeting().canAttackTarget(Unit.of(unit), targetUnit == null ? null : Unit.of(targetUnit));
    }

    /**
     * Checks whether a target is within the range of the weapon a unit would use against it.
     * <p>
     * The weapon is {@link UnitTargeting#getTargetWeapon} and the range its maximum range ({@link UnitTargeting#getWeaponMaximumRange}); a unit
     * without that weapon has no range.
     *
     * @param unit       the attacking unit
     * @param targetUnit the target unit
     * @return {@code true} if the target is in range, {@code false} otherwise
     * @throws IllegalArgumentException if a unit is not an SC:R unit
     * @throws IllegalStateException    if the system is not attached, or a turret has no parent unit
     * @throws NullPointerException     if the world was built without game data
     */
    @Override
    public boolean isInRange(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit, org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit targetUnit) {
        UnitTargeting targeting = this.requireGame().getUnitTargeting();
        Unit attacker = Unit.of(unit);
        Unit target = Unit.of(targetUnit);
        int weapon = targeting.getTargetWeapon(attacker, target);

        return weapon != ScrWeaponIds.NONE && targeting.isInRange(attacker, target, targeting.getWeaponMaximumRange(attacker, weapon));
    }

    /**
     * Returns the weapon of a unit for a kind of target.
     * <p>
     * The weapon is {@link UnitTargeting#getGroundWeapon} or {@link UnitTargeting#getAirWeapon} of the unit itself.
     *
     * @param unit the unit
     * @param slot the kind of target
     * @return the weapon, or {@code null} if the unit has no such weapon
     * @throws IllegalArgumentException if the unit is not an SC:R unit
     * @throws IllegalStateException    if the system is not attached
     * @throws NullPointerException     if the world was built without game data
     */
    @Override
    public @Nullable WeaponType getWeapon(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit, @NotNull WeaponSlot slot) {
        UnitTargeting targeting = this.requireGame().getUnitTargeting();
        Unit scrUnit = Unit.of(unit);

        return ScrNativeIds.weaponOf(slot == WeaponSlot.GROUND ? targeting.getGroundWeapon(scrUnit) : targeting.getAirWeapon(scrUnit));
    }

    /**
     * Keeps a unit after the target of its attack: it moves towards the target until the target is in range.
     * <p>
     * The SC:R pursuit is {@link UnitAttack#tryFollowTarget}: nothing happens while the movement destination timer runs; an unseen target out of
     * range ends the order when the unit reports a reached destination; a computer transport pickup takes the unit over.
     *
     * @param unit             the attacking unit
     * @param targetUnit       the target unit
     * @param instructionLimit the instruction limit of each animation program run the pursuit starts
     * @return {@code true} if the pursuit goes on, {@code false} if it ended: the unit arrived without reaching the target, or another system took the unit over
     * @throws IllegalArgumentException if a unit is not an SC:R unit
     * @throws IllegalStateException    if the system is not attached
     */
    @Override
    public boolean pursueTarget(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit, org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit targetUnit,
                                int instructionLimit) {
        return this.requireGame().getUnitAttack().tryFollowTarget(Unit.of(unit), Unit.of(targetUnit), instructionLimit);
    }

    /**
     * Deals damage to a unit that armor and shields no longer reduce; a unit whose hit points run out dies.
     * <p>
     * The SC:R damage is {@link UnitDamage#dealUnitDamage} with eight fractional bits; the attacker's position is revealed to the target's owner.
     *
     * @param targetUnit       the damaged unit
     * @param amount           the damage, in the implementation's fixed-point unit
     * @param attackerUnit     the attacking unit, or {@code null} without attacker
     * @param instructionLimit the instruction limit of each animation program run the damage starts
     * @throws IllegalArgumentException if a unit is not an SC:R unit
     * @throws IllegalStateException    if the system is not attached
     * @throws NullPointerException     if the world was built without game data
     */
    @Override
    public void dealDamage(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit targetUnit, int amount,
                           org.fogofwarlabs.sc4j.engine.entity.@Nullable Unit attackerUnit,
                           int instructionLimit) {
        Unit attacker = attackerUnit == null ? null : Unit.of(attackerUnit);

        this.requireGame().getUnitDamage().dealUnitDamage(Unit.of(targetUnit), amount, attacker, true, instructionLimit);
    }

    /**
     * Deals the damage of one hit of a weapon to a unit: the weapon's damage after the attacker's modifiers, reduced by the unit's shields and
     * armor by the rules of the implementation, then {@link #dealDamage}. A projectile system calls it when a projectile arrives.
     * <p>
     * The SC:R hit is the full-damage weapon hit of the attacker ({@link UnitDamage#dealWeaponHit}), as a melee hit deals it. The SC:R bullets
     * hit through their own phase and do not call this.
     *
     * @param weapon           the weapon
     * @param attackerUnit     the attacking unit, or {@code null} if it no longer lives
     * @param targetUnit       the hit unit
     * @param instructionLimit the instruction limit of each animation program run the hit starts
     * @throws IllegalArgumentException if a unit is not an SC:R unit, or there is no attacker: an SC:R hit needs the attacking player
     * @throws IllegalStateException    if the system is not attached
     * @throws NullPointerException     if the world was built without game data
     */
    @Override
    public void dealWeaponDamage(@NotNull WeaponType weapon, org.fogofwarlabs.sc4j.engine.entity.@Nullable Unit attackerUnit,
                                 org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit targetUnit, int instructionLimit) {
        if (attackerUnit == null) {
            throw Exceptions.newIllegalArgumentException("An SC:R weapon hit of {0} needs its attacking unit.", weapon);
        }

        this.requireGame().getUnitDamage().dealWeaponHit(ScrNativeIds.toNative(weapon), Unit.of(attackerUnit), Unit.of(targetUnit), instructionLimit);
    }

    private @NotNull Game requireGame() {
        Game attached = this.game;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R combat system is not attached.");
        }

        return attached;
    }
}
