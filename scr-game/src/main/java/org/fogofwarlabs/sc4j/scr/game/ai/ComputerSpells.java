package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrWeaponTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.WeaponBehaviors;
import org.fogofwarlabs.sc4j.scr.data.type.constant.WeaponTargetFlags;
import org.fogofwarlabs.sc4j.scr.game.combat.UnitTargeting;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Selects spell targets and issues spell orders for the ten supported computer-controlled caster types.
 * <p>
 * Attempts check energy and technology permission before searching. A repeated order and target ends the attempt
 * without a cast. A new spell order marks its target; an existing spell order may be retained without setting the bit.
 */
public final class ComputerSpells {
    /**
     * Truncates a value to a BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * Truncates a value to a WORD.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The fraction bits of energy and shield points.
     */
    private static final int FIXED_POINT_FRACTION_BITS = 8;
    /**
     * A splash is worthwhile only while this multiple of the allied sum stays below the enemy sum.
     */
    private static final int OWN_STRENGTH_FACTOR = 2;
    /**
     * The campaign Queen's draw that casts Parasite first.
     */
    private static final int PARASITE_SELECTION_ROLL = 1;

    /**
     * The attempt result that continues with the caster's next spell.
     */
    private static final int NEXT = 0;
    /**
     * The attempt result that ends the routine without a cast.
     */
    private static final int STOP = 1;
    /**
     * The attempt result of an issued or kept spell order.
     */
    private static final int CAST = 2;

    /**
     * The spell target bit of every spell except Parasite.
     */
    private static final int SPELL_TARGET_BIT = 1;
    /**
     * The spell target bit of Parasite.
     */
    private static final int PARASITE_TARGET_BIT = 4;

    /**
     * The search radius of a forced cast.
     */
    private static final int FORCED_RADIUS = 0x120;
    /**
     * The search radius of a forced Yamato Gun.
     */
    private static final int FORCED_YAMATO_RADIUS = 0x200;
    /**
     * The short search radius, also the radius of most searches outside campaigns.
     */
    private static final int SHORT_RADIUS = 0x400;
    /**
     * The long search radius, also the radius of most searches in campaigns.
     */
    private static final int LONG_RADIUS = 0x800;
    /**
     * The search radius of Plague and of a campaign Disruption Web.
     */
    private static final int WIDE_RADIUS = 0x1000;

    /**
     * The random source of the campaign Queen's draw for Parasite first.
     */
    private static final int QUEEN_RANDOM_SOURCE = 0x2A;

    /**
     * Orders {@code MOVE_TO_GAS + offset} up to this offset (gas harvesting) exclude a spell target.
     */
    private static final int GAS_ORDER_LAST_OFFSET = 2;
    /**
     * Orders {@code NUKE_WAIT + offset} up to this offset (the nuclear strike orders).
     */
    private static final int NUKE_ORDER_LAST_OFFSET = 6;

    /**
     * The radius of the surroundings a nuclear strike sums.
     */
    private static final int NUKE_SURROUNDINGS_RADIUS = 0xC0;
    /**
     * The hit points and shields a nuclear strike's surroundings must exceed.
     */
    private static final int NUKE_SUM_LIMIT = 799;
    /**
     * The radius of the surroundings EMP Shockwave sums.
     */
    private static final int EMP_SURROUNDINGS_RADIUS = 0xA0;
    /**
     * The shields or energy EMP Shockwave's surroundings must exceed.
     */
    private static final int EMP_SUM_LIMIT = 199;
    /**
     * The maximum hit points a Defensive Matrix target must exceed.
     */
    private static final int MATRIX_MAXIMUM_HIT_POINTS_LIMIT = 99;
    /**
     * A Defensive Matrix target has fewer hit points than this.
     */
    private static final int MATRIX_HIT_POINTS_LIMIT = 0x4C;
    /**
     * The lowest hit points and shields of a Yamato Gun target that is not a building.
     */
    private static final int YAMATO_MINIMUM_LIFE = 200;
    /**
     * The number of hit point and shield values from {@link #YAMATO_MINIMUM_LIFE} a Yamato Gun target may have.
     */
    private static final int YAMATO_LIFE_RANGE = 0xFB;
    /**
     * The lowest hit points and shields of a Restoration target.
     */
    private static final int RESTORATION_MINIMUM_LIFE = 0x3D;
    /**
     * The lowest hit points and shields of an Optical Flare target that is not a detector.
     */
    private static final int OPTICAL_FLARE_MINIMUM_LIFE = 0x51;
    /**
     * The lowest hit points and shields of a Spawn Broodlings target accepted for them alone.
     */
    private static final int BROODLINGS_MINIMUM_LIFE = 100;
    /**
     * The lowest hit points and shields of a Parasite target accepted for them alone.
     */
    private static final int PARASITE_MINIMUM_LIFE = 300;
    /**
     * The weapon behaviors, one bit per behavior below {@link #DARK_SWARM_BEHAVIOR_LIMIT}, that Dark Swarm blocks.
     */
    private static final int DARK_SWARM_BEHAVIORS = 0x97;
    private static final int DARK_SWARM_BEHAVIOR_LIMIT = 8;
    /**
     * The range within which Disruption Web takes a ground enemy.
     */
    private static final int DISRUPTION_WEB_GROUND_RANGE = 0x100;
    /**
     * A Recall target has fewer shield points than this.
     */
    private static final int RECALL_SHIELD_LIMIT = 0xB;

    /**
     * The radius of the splash sums.
     */
    private static final int SPLASH_RADIUS = 0x60;
    /**
     * The enemy sum a splash must exceed.
     */
    private static final int SPLASH_ENEMY_LIMIT = 0xF9;

    /**
     * Applies the computer spell casting routine to one unit.
     *
     * @param phase  the computer-player phase
     * @param game   the game
     * @param unit   the caster
     * @param forced {@code true} to bypass the owner's spell countdown and apply the spell-specific forced search rules
     * @return {@code true} if a spell order was issued or kept, {@code false} otherwise
     */
    public static boolean cast(@NotNull ComputerPlayerPhase phase, @NotNull Game game, @NotNull Unit unit, boolean forced) {
        if (!forced && phase.getPlayer(unit.getOwnerIndex()).getSpellCountdown() != 0) {
            return false;
        }

        ComputerSpells spells = new ComputerSpells(phase, game, unit, forced);

        return switch (ScrUnitIds.requireValid(game.requireGameData().getBaseType(unit.getUnitId()))) {
            case ScrUnitIds.TERRAN_GHOST -> spells.castGhost();
            case ScrUnitIds.TERRAN_SCIENCE_VESSEL -> spells.castScienceVessel();
            case ScrUnitIds.TERRAN_BATTLECRUISER -> spells.attempt(ScrOrderIds.FIRE_YAMATO_GUN, ScrTechIds.YAMATO_GUN, spells::findYamatoTarget, true) == CAST;
            case ScrUnitIds.TERRAN_MEDIC -> spells.castMedic();
            case ScrUnitIds.ZERG_QUEEN -> spells.castQueen();
            case ScrUnitIds.ZERG_DEFILER -> spells.castDefiler();
            case ScrUnitIds.PROTOSS_CORSAIR -> spells.attempt(ScrOrderIds.CAST_DISRUPTION_WEB, ScrTechIds.DISRUPTION_WEB, spells::findDisruptionWebTarget, true) == CAST;
            case ScrUnitIds.PROTOSS_DARK_ARCHON -> spells.castDarkArchon();
            case ScrUnitIds.PROTOSS_HIGH_TEMPLAR -> spells.castHighTemplar();
            case ScrUnitIds.PROTOSS_ARBITER -> spells.castArbiter();
            default -> false;
        };
    }

    /**
     * Returns the first ready Nuclear Silo of the Ghost's owner whose missile is free for the Ghost: the missile
     * remembers no unit, this Ghost or a silo. Shared by the Ghost's spell branch and the aiscript nukes
     * {@code nuke_location} and {@code nuke_pos}.
     *
     * @param game  the game
     * @param ghost the Ghost
     * @return the first ready Nuclear Silo of the Ghost's owner whose missile is free for it, or {@code null}
     * @throws IllegalStateException         if a ready Nuclear Silo has no missile
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    static @Nullable Unit findSilo(@NotNull Game game, @NotNull Unit ghost) {
        UnitPool pool = game.getUnitSystem().requireUnitPool();

        for (int index = pool.getFirstOwnerIndex(ghost.getOwnerIndex()); index != 0; index = pool.getNextOwnerIndex(index)) {
            Unit silo = pool.getUnit(index);

            if (game.requireGameData().getBaseType(silo) != ScrUnitIds.TERRAN_NUCLEAR_SILO || !silo.isSiloReady()) {
                continue;
            }

            Unit missile = silo.getSiloNuke();

            if (missile == null) {
                throw Exceptions.newIllegalStateException("A ready Nuclear Silo has no missile.");
            }

            Unit remembered = missile.getParentUnit();

            if (remembered == null || remembered == ghost || game.requireGameData().getBaseType(remembered) == ScrUnitIds.TERRAN_NUCLEAR_SILO) {
                return silo;
            }
        }

        return null;
    }

    /**
     * Searches the nuclear strike target of aiscript {@code nuke_location} over the whole map from the Ghost. A candidate
     * inside the rectangle that is an enemy of the Ghost (without the detection test of the spell branch) becomes the
     * fallback and ends the search when its {@code 192}-pixel surroundings sum more than {@code 799} hit points and shields.
     *
     * @param phase  the computer-player phase
     * @param game   the game
     * @param ghost  the Ghost
     * @param left   the rectangle left WORD
     * @param top    the rectangle top WORD
     * @param right  the rectangle right WORD
     * @param bottom the rectangle bottom WORD
     * @return the accepted target, else the last enemy candidate inside the rectangle, or {@code null}
     */
    static @Nullable Unit findNukeTarget(@NotNull ComputerPlayerPhase phase,
                                         @NotNull Game game,
                                         @NotNull Unit ghost,
                                         int left, int top, int right, int bottom) {
        ComputerSpells spells = new ComputerSpells(phase, game, ghost, false);
        Unit[] fallback = new Unit[1];
        Unit found = game.getComputerResources().findOnMap(ghost,
                                                           target -> {
                                                               Sprite sprite = target.requireSprite();
                                                               int positionX = (short) sprite.getX();
                                                               int positionY = (short) sprite.getY();

                                                               if (positionX < (short) left || positionX > (short) right || positionY < (short) top ||
                                                                   positionY > (short) bottom ||
                                                                   !spells.game.getComputerUnits().isEnemy(spells.caster.getOwnerIndex(), target)) {
                                                                   return false;
                                                               }

                                                               fallback[0] = target;

                                                               return spells.isNukeWorthwhile(target);
                                                           });

        return found != null ? found : fallback[0];
    }

    /**
     * Returns the energy cost of an order: the techdata energy of the order's technology shifted by eight, or zero
     * without one.
     *
     * @param game  the game
     * @param order the order
     * @return the energy cost in the fixed-point energy unit, at most {@code 0xFFFF}
     * @throws NullPointerException if the world was built without game data
     */
    static int getOrderEnergyCost(@NotNull Game game, int order) {
        int technology = game.requireGameData().getOrderTypes().getTechnology(order);

        if (technology >= ScrTechIds.NONE) {
            return 0;
        }

        return game.requireGameData().getTechTypes().getEnergyCost(technology) << FIXED_POINT_FRACTION_BITS & WORD_MASK;
    }


    private final @NotNull ComputerPlayerPhase phase;
    private final @NotNull Game game;
    private final @NotNull Unit caster;
    private final boolean forced;

    private final boolean campaign;
    private final @NotNull ScrUnitTypeView units;

    /**
     * The weapon of the splash sums.
     */
    private int splashWeapon;

    private ComputerSpells(@NotNull ComputerPlayerPhase phase, @NotNull Game game, @NotNull Unit caster, boolean forced) {
        this.phase  = phase;
        this.game   = game;
        this.caster = caster;
        this.forced = forced;

        this.campaign = phase.getPlayer(caster.getOwnerIndex()).isCampaign();
        this.units    = game.requireGameData().getUnitTypes();

        this.splashWeapon = 0;
    }

    /**
     * Returns whether the caster has the order's energy and may use the technology.
     */
    private boolean canCast(int order, int technology) {
        if (!this.game.getUnitEnergy().hasEnergy(this.caster, getOrderEnergyCost(this.game, order))) {
            return false;
        }

        return this.phase.canUseTechnology(this.caster, technology);
    }

    /**
     * Returns whether the primary order already is the spell order with this target, the stop after every search.
     */
    private boolean isRepeated(int order, @Nullable Unit target) {
        return this.caster.getPrimaryOrder() == order && this.caster.getOrderTargetUnit() == target;
    }

    /**
     * Issues a spell order and marks its target when that order is not already primary; otherwise keeps the order.
     *
     * @return {@code true} if the spell order was issued or kept, {@code false} otherwise
     */
    private boolean issueSpell(int order, @Nullable Unit target, int bit) {
        if (target == null || (target.getSpellTargetFlags() & bit) != 0) {
            return false;
        }

        if (this.caster.getPrimaryOrder() != order) {
            this.issueTargetOrder(order, target);
            target.setSpellTargetFlags(target.getSpellTargetFlags() | bit);
        }

        return true;
    }

    /**
     * Issues the interrupting order with the unit target and no target type.
     *
     * @throws IllegalStateException if the target has no sprite
     */
    private void issueTargetOrder(int order, @NotNull Unit target) {
        Sprite sprite = target.requireSprite();

        this.game.getOrders().issue(this.caster,
                                    order,
                                    target,
                                    sprite.getX(), sprite.getY(),
                                    ScrUnitIds.NONE,
                                    false,
                                    this.phase.getInstructionLimit());
    }

    /**
     * Returns whether the energy's high byte equals the maximum energy's high byte.
     */
    private boolean hasFullEnergy() {
        return (this.caster.getEnergy() >> FIXED_POINT_FRACTION_BITS & BYTE_MASK) ==
               this.game.getUnitInitializer().getMaximumEnergy(this.caster, this.units) >> FIXED_POINT_FRACTION_BITS;
    }

    /**
     * Returns the radius of the searches that use {@code 288} pixels when forced and otherwise {@code 2048} in campaigns or
     * {@code 1024}.
     */
    private int getCampaignRadius() {
        if (this.forced) {
            return FORCED_RADIUS;
        }

        return this.campaign ? LONG_RADIUS : SHORT_RADIUS;
    }

    // Acceptance tests

    /**
     * Returns whether the target is a visible, detected enemy that is not an Interceptor, Spider Mine or Scarab and not
     * harvesting gas.
     */
    private boolean isSpellEnemy(@NotNull Unit target) {
        if (!UnitTargeting.isVisible(this.caster, target) || UnitTargeting.isUndetected(this.caster, target) ||
            !this.game.getComputerUnits().isEnemy(this.caster.getOwnerIndex(), target)) {
            return false;
        }

        int type = this.game.requireGameData().getBaseType(target);

        if (type == ScrUnitIds.PROTOSS_INTERCEPTOR || type == ScrUnitIds.TERRAN_VULTURE_SPIDER_MINE ||
            type == ScrUnitIds.PROTOSS_SCARAB) {
            return false;
        }

        return (target.getPrimaryOrder() - ScrOrderIds.MOVE_TO_GAS & BYTE_MASK) > GAS_ORDER_LAST_OFFSET;
    }

    /**
     * Returns whether the {@code 192}-pixel surroundings of the target sum more than {@code 799}: the hit points and shields of enemy
     * workers and immobile units, while an enemy active detector, Sunken Colony or Lurker counting as a building sets
     * the sum to {@code 800}.
     */
    private boolean isNukeWorthwhile(@NotNull Unit target) {
        int sum = 0;

        for (Unit unit : this.collect(target, NUKE_SURROUNDINGS_RADIUS)) {
            if ((unit.getStatus() & UnitStatusFlags.INVINCIBLE) != 0 || !this.game.getComputerUnits().isEnemy(this.caster.getOwnerIndex(), unit)) {
                continue;
            }

            if (this.game.getComputerUnits().isWorker(unit) || (unit.getStatus() & UnitStatusFlags.CAN_MOVE) == 0) {
                sum += UnitQueries.getLife(this.units, unit);
            }

            if (UnitQueries.isBuilding(this.units, unit.getUnitId())) {
                int type = this.game.requireGameData().getBaseType(unit);

                if (this.game.getComputerTargets().isActiveDetector(unit) || type == ScrUnitIds.ZERG_SUNKEN_COLONY ||
                    type == ScrUnitIds.ZERG_LURKER) {
                    sum = NUKE_SUM_LIMIT + 1;
                }
            }
        }

        return sum > NUKE_SUM_LIMIT;
    }

    // Splash sums

    /**
     * Returns whether a splash is worthwhile, the tail of the splash tests: the enemy sum within {@code 96} pixels of the target
     * must exceed {@code 249} unless the forced flag is set, and twice the allied sum must stay below it.
     *
     * @param organic {@code true} to sum only organic unmaelstromed enemies, {@code false} to sum every enemy
     */
    private boolean isSplashWorthwhile(@NotNull Unit target, int weapon, boolean organic) {
        this.splashWeapon = weapon;

        int enemy = 0;
        Unit[] nearby = this.collect(target, SPLASH_RADIUS);

        for (Unit unit : nearby) {
            if (unit == this.caster || (unit.getStatus() & UnitStatusFlags.INVINCIBLE) != 0 || !this.canWeaponTarget(this.splashWeapon, unit) ||
                !this.game.getComputerUnits().isEnemy(this.caster.getOwnerIndex(), unit)) {
                continue;
            }

            if (organic) {
                if (this.isOrganic(unit) && unit.getMaelstromTimer() == 0) {
                    enemy += UnitQueries.getLife(this.units, unit);
                }
            } else {
                enemy += this.splashWeapon == ScrWeaponIds.PLAGUE ? UnitQueries.getHitPoints(unit) : UnitQueries.getLife(this.units, unit);
            }
        }

        if (!this.phase.isSpellSearchForced() && Integer.compareUnsigned(enemy, SPLASH_ENEMY_LIMIT) <= 0) {
            return false;
        }

        int own = 0;

        for (Unit unit : this.collect(target, SPLASH_RADIUS)) {
            if ((unit.getStatus() & UnitStatusFlags.INVINCIBLE) == 0 && this.canWeaponTarget(this.splashWeapon, unit) &&
                !this.game.getComputerUnits().isEnemy(this.caster.getOwnerIndex(), unit)) {
                own += UnitQueries.getLife(this.units, unit);
            }
        }

        return Integer.compareUnsigned(own * OWN_STRENGTH_FACTOR, enemy) < 0;
    }

    // Unit helpers

    /**
     * Returns whether the target's order target is owned by a playable player and is not an enemy.
     */
    private boolean isAttackingAlly(@NotNull Unit target) {
        Unit ally = target.getOrderTargetUnit();

        return ally != null && ally.getOwnerIndex() < PlayerTable.PLAYABLE_PLAYER_SLOTS &&
               !this.game.getComputerUnits().isEnemy(this.caster.getOwnerIndex(), ally);
    }

    /**
     * Returns whether the weapon's target flags accept the unit, a weapon limited to own units only an own unit.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private boolean canWeaponTarget(int weapon, @NotNull Unit target) {
        if (weapon >= ScrWeaponIds.NONE || !this.game.getUnitTargeting().canWeaponTarget(weapon, target)) {
            return false;
        }

        int flags = this.game.requireGameData().getWeaponTypes().getTargetFlags(weapon);

        return (flags & WeaponTargetFlags.OWN) == 0 || this.caster.getOwnerIndex() == target.getOwnerIndex();
    }

    /**
     * Returns the first live unit in the loaded-unit slots below the {@code units.dat} space provided.
     */
    private @Nullable Unit getFirstLoadedUnit(@NotNull Unit transport) {
        int slots = this.units.getSpaceProvided(transport.getUnitId());

        for (int slot = 0; slot < slots; slot++) {
            Unit loaded = this.game.getUnitTransport().getLoadedUnit(transport, slot);

            if (loaded != null) {
                return loaded;
            }
        }

        return null;
    }

    private boolean isHero(@NotNull Unit unit) {
        return (this.units.getFlags(unit.getUnitId()) & UnitTypeFlags.HERO) != 0;
    }

    private boolean isOrganic(@NotNull Unit unit) {
        int type = unit.getUnitId();

        return this.units.hasEntry(type) && (this.units.getFlags(type) & UnitTypeFlags.ORGANIC) != 0;
    }


    // Caster branches

    /**
     * Casts Lockdown, then a nuclear strike through a ready Nuclear Silo when the owner's nuke rate allows one.
     *
     * @throws IllegalStateException if the ready Nuclear Silo has no missile
     */
    private boolean castGhost() {
        int result = this.attempt(ScrOrderIds.CAST_LOCKDOWN, ScrTechIds.LOCKDOWN, this::findLockdownTarget, false);

        if (result != NEXT) {
            return result == CAST;
        }

        ComputerPlayer player = this.phase.getPlayer(this.caster.getOwnerIndex());
        int clock = this.game.getComputerTowns().getElapsedClockTicks();

        if (!player.isNukeReady(clock)) {
            return false;
        }

        Unit silo = findSilo(this.game, this.caster);

        if (silo == null) {
            return false;
        }

        Unit target = this.game.getComputerResources().findOnMap(this.caster, this::acceptNukeTarget);

        if (!this.issueSpell(ScrOrderIds.NUKE_PAINT, target, SPELL_TARGET_BIT)) {
            return false;
        }

        Unit missile = silo.getSiloNuke();

        if (missile == null) {
            throw Exceptions.newIllegalStateException("A ready Nuclear Silo has no missile.");
        }

        missile.setParentUnit(this.caster);
        player.setLastNukeClock(clock);

        return true;
    }

    /**
     * Casts EMP Shockwave, Defensive Matrix, then Irradiate.
     */
    private boolean castScienceVessel() {
        int result = this.attempt(ScrOrderIds.CAST_EMP_SHOCKWAVE, ScrTechIds.EMP_SHOCKWAVE, this::findEmpTarget, false);

        if (result != NEXT) {
            return result == CAST;
        }

        result = this.attempt(ScrOrderIds.CAST_DEFENSIVE_MATRIX, ScrTechIds.DEFENSIVE_MATRIX, this::findDefensiveMatrixTarget, false);

        if (result != NEXT) {
            return result == CAST;
        }

        return this.attempt(ScrOrderIds.CAST_IRRADIATE, ScrTechIds.IRRADIATE, this::findIrradiateTarget, true) == CAST;
    }

    /**
     * Casts Restoration (the owner's tracked Medic also searches the whole map for a parasited or maelstromed unit),
     * then Optical Flare at full energy.
     */
    private boolean castMedic() {
        if (this.canCast(ScrOrderIds.CAST_RESTORATION, ScrTechIds.RESTORATION)) {
            Unit target = this.findRestorationTarget();

            if (this.isRepeated(ScrOrderIds.CAST_RESTORATION, target)) {
                return false;
            }

            if (this.issueSpell(ScrOrderIds.CAST_RESTORATION, target, SPELL_TARGET_BIT)) {
                return true;
            }

            if (this.caster == this.phase.getPlayer(this.caster.getOwnerIndex()).getMedic() &&
                this.caster.getPrimaryOrder() != ScrOrderIds.CAST_RESTORATION) {
                Unit patient = this.forced ? null : this.game.getComputerResources().findOnMap(this.caster, this::acceptTrackedRestoration);

                if (patient != null && (patient.getSpellTargetFlags() & SPELL_TARGET_BIT) == 0) {
                    if (this.caster.getPrimaryOrder() == ScrOrderIds.CAST_RESTORATION) {
                        return true;
                    }

                    this.issueTargetOrder(ScrOrderIds.CAST_RESTORATION, patient);
                    patient.setSpellTargetFlags(patient.getSpellTargetFlags() | SPELL_TARGET_BIT);

                    return true;
                }
            }
        }

        if (!this.hasFullEnergy()) {
            return false;
        }

        return this.attempt(ScrOrderIds.CAST_OPTICAL_FLARE, ScrTechIds.OPTICAL_FLARE, this::findOpticalFlareTarget, true) == CAST;
    }

    /**
     * Casts Parasite first when a campaign draw is one or, outside campaigns, at full energy; otherwise Spawn Broodlings
     * (followed by a queued move to the Queen's position), then Ensnare.
     *
     * @throws IllegalStateException if Spawn Broodlings is issued and the Queen has no sprite
     */
    private boolean castQueen() {
        boolean parasiteFirst;

        if (this.campaign) {
            parasiteFirst = (this.game.getRandom().next(QUEEN_RANDOM_SOURCE) & BYTE_MASK) == PARASITE_SELECTION_ROLL;
        } else {
            parasiteFirst = this.hasFullEnergy();
        }

        if (parasiteFirst) {
            if (this.canCast(ScrOrderIds.CAST_PARASITE, ScrTechIds.PARASITE)) {
                Unit target = this.findParasiteTarget();

                if (this.isRepeated(ScrOrderIds.CAST_PARASITE, target)) {
                    return false;
                }

                return this.issueSpell(ScrOrderIds.CAST_PARASITE, target, PARASITE_TARGET_BIT);
            }

            if (this.campaign) {
                return false;
            }
        }

        if (this.canCast(ScrOrderIds.CAST_SPAWN_BROODLINGS, ScrTechIds.SPAWN_BROODLINGS)) {
            Unit target = this.findBroodlingsTarget();

            if (this.isRepeated(ScrOrderIds.CAST_SPAWN_BROODLINGS, target)) {
                return false;
            }

            if (this.issueSpell(ScrOrderIds.CAST_SPAWN_BROODLINGS, target, SPELL_TARGET_BIT)) {
                Sprite sprite = this.caster.requireSprite();

                this.game.getOrders().queueReplacingSameOrders(this.caster,
                                                               ScrOrderIds.MOVE,
                                                               null,
                                                               (short) sprite.getX(), (short) sprite.getY(),
                                                               ScrUnitIds.NONE);

                return true;
            }
        }

        if (!this.campaign && !this.hasFullEnergy()) {
            return false;
        }

        return this.attempt(ScrOrderIds.CAST_ENSNARE, ScrTechIds.ENSNARE, this::findEnsnareTarget, true) == CAST;
    }

    /**
     * Casts Plague, then Dark Swarm.
     */
    private boolean castDefiler() {
        int result = this.attempt(ScrOrderIds.CAST_PLAGUE, ScrTechIds.PLAGUE, this::findPlagueTarget, false);

        if (result != NEXT) {
            return result == CAST;
        }

        return this.attempt(ScrOrderIds.CAST_DARK_SWARM, ScrTechIds.DARK_SWARM, this::findDarkSwarmTarget, true) == CAST;
    }

    /**
     * Casts Feedback, Mind Control, then Maelstrom with the energy of both Maelstrom and Mind Control (at most the
     * maximum energy).
     */
    private boolean castDarkArchon() {
        int result = this.attempt(ScrOrderIds.CAST_FEEDBACK, ScrTechIds.FEEDBACK, this::findFeedbackTarget, false);

        if (result != NEXT) {
            return result == CAST;
        }

        result = this.attempt(ScrOrderIds.CAST_MIND_CONTROL, ScrTechIds.MIND_CONTROL, this::findMindControlTarget, false);

        if (result != NEXT) {
            return result == CAST;
        }

        int cost = getOrderEnergyCost(this.game, ScrOrderIds.CAST_MAELSTROM) + getOrderEnergyCost(this.game, ScrOrderIds.CAST_MIND_CONTROL);
        int maximum = this.game.getUnitInitializer().getMaximumEnergy(this.caster, this.units);

        if (cost > maximum) {
            cost = maximum;
        }

        if (!this.game.getUnitEnergy().hasEnergy(this.caster, cost & WORD_MASK) ||
            !this.phase.canUseTechnology(this.caster, ScrTechIds.MAELSTROM)) {
            return false;
        }

        Unit target = this.findMaelstromTarget();

        if (this.isRepeated(ScrOrderIds.CAST_MAELSTROM, target)) {
            return false;
        }

        return this.issueSpell(ScrOrderIds.CAST_MAELSTROM, target, SPELL_TARGET_BIT);
    }

    /**
     * Casts Psionic Storm, then Hallucination at full energy.
     */
    private boolean castHighTemplar() {
        int result = this.attempt(ScrOrderIds.CAST_PSIONIC_STORM, ScrTechIds.PSIONIC_STORM, this::findStormTarget, false);

        if (result != NEXT) {
            return result == CAST;
        }

        if (!this.hasFullEnergy()) {
            return false;
        }

        return this.attempt(ScrOrderIds.CAST_HALLUCINATION, ScrTechIds.HALLUCINATION, this::findHallucinationTarget, true) == CAST;
    }

    /**
     * Casts Stasis Field, then Recall.
     */
    private boolean castArbiter() {
        int result = this.attempt(ScrOrderIds.CAST_STASIS_FIELD, ScrTechIds.STASIS_FIELD, this::findStasisTarget, false);

        if (result != NEXT) {
            return result == CAST;
        }

        return this.attempt(ScrOrderIds.CAST_RECALL, ScrTechIds.RECALL, this::findRecallTarget, true) == CAST;
    }

    // Common steps

    /**
     * Runs one spell attempt: {@link #canCast}, the search, the repeated-order stop and {@link #issueSpell}.
     *
     * @param last {@code true} if a failed order ends the routine
     * @return {@link #CAST}, {@link #STOP} or {@link #NEXT}
     */
    private int attempt(int order, int technology, @NotNull Supplier<@Nullable Unit> search, boolean last) {
        if (!this.canCast(order, technology)) {
            return last ? STOP : NEXT;
        }

        Unit target = search.get();

        if (this.isRepeated(order, target)) {
            return STOP;
        }

        if (this.issueSpell(order, target, SPELL_TARGET_BIT)) {
            return CAST;
        }

        return last ? STOP : NEXT;
    }

    // Searches

    private @Nullable Unit findLockdownTarget() {
        return this.game.getComputerResources().findAround(this.caster, this.getCampaignRadius(), this::acceptLockdown);
    }

    /**
     * Searches by shields, then unless forced by energy.
     */
    private @Nullable Unit findEmpTarget() {
        int radius = this.forced ? FORCED_RADIUS : LONG_RADIUS;
        Unit target = this.game.getComputerResources().findAround(this.caster, radius, this::acceptEmpShields);

        if (target == null && !this.forced) {
            target = this.game.getComputerResources().findAround(this.caster, radius, this::acceptEmpEnergy);
        }

        return target;
    }

    private @Nullable Unit findDefensiveMatrixTarget() {
        return this.game.getComputerResources().findAround(this.caster, this.forced ? FORCED_RADIUS : LONG_RADIUS, this::acceptDefensiveMatrix);
    }

    /**
     * Searches after storing the forced flag for the acceptance test.
     */
    private @Nullable Unit findIrradiateTarget() {
        this.phase.setSpellSearchForced(this.forced);

        return this.game.getComputerResources().findAround(this.caster, this.forced ? FORCED_RADIUS : LONG_RADIUS, this::acceptIrradiate);
    }

    /**
     * Searches {@code 512} pixels when forced, otherwise the whole map.
     */
    private @Nullable Unit findYamatoTarget() {
        if (this.forced) {
            return this.game.getComputerResources().findAround(this.caster, FORCED_YAMATO_RADIUS, this::acceptYamato);
        }

        return this.game.getComputerResources().findOnMap(this.caster, this::acceptYamato);
    }

    private @Nullable Unit findRestorationTarget() {
        return this.game.getComputerResources().findAround(this.caster, SHORT_RADIUS, this::acceptRestoration);
    }

    private @Nullable Unit findOpticalFlareTarget() {
        return this.game.getComputerResources().findAround(this.caster, this.forced ? FORCED_RADIUS : SHORT_RADIUS, this::acceptOpticalFlare);
    }

    /**
     * Searches after storing the forced flag for the acceptance test.
     */
    private @Nullable Unit findBroodlingsTarget() {
        this.phase.setSpellSearchForced(this.forced);

        return this.game.getComputerResources().findAround(this.caster, this.forced ? FORCED_RADIUS : LONG_RADIUS, this::acceptBroodlings);
    }

    private @Nullable Unit findParasiteTarget() {
        return this.game.getComputerResources().findAround(this.caster, this.forced ? FORCED_RADIUS : LONG_RADIUS, this::acceptParasite);
    }

    private @Nullable Unit findEnsnareTarget() {
        return this.game.getComputerResources().findAround(this.caster, this.getCampaignRadius(), this::acceptEnsnare);
    }

    private @Nullable Unit findPlagueTarget() {
        return this.game.getComputerResources().findAround(this.caster, this.forced ? FORCED_RADIUS : WIDE_RADIUS, this::acceptPlague);
    }

    /**
     * Searches and replaces a flying result by its order target.
     */
    private @Nullable Unit findDarkSwarmTarget() {
        Unit target = this.game.getComputerResources().findAround(this.caster, this.getCampaignRadius(), this::acceptDarkSwarm);

        if (target != null && (target.getStatus() & UnitStatusFlags.FLYING) != 0) {
            return target.getOrderTargetUnit();
        }

        return target;
    }

    private @Nullable Unit findDisruptionWebTarget() {
        int radius = this.forced ? FORCED_RADIUS : this.campaign ? WIDE_RADIUS : LONG_RADIUS;

        return this.game.getComputerResources().findAround(this.caster, radius, this::acceptDisruptionWeb);
    }

    private @Nullable Unit findFeedbackTarget() {
        return this.game.getComputerResources().findAround(this.caster, this.getCampaignRadius(), this::acceptFeedback);
    }

    private @Nullable Unit findMindControlTarget() {
        return this.game.getComputerResources().findAround(this.caster, SHORT_RADIUS, this::acceptMindControl);
    }

    /**
     * Searches after storing the forced flag for the acceptance test.
     */
    private @Nullable Unit findMaelstromTarget() {
        this.phase.setSpellSearchForced(this.forced);

        return this.game.getComputerResources().findAround(this.caster, this.getCampaignRadius(), this::acceptMaelstrom);
    }

    /**
     * Searches after storing the forced flag for the acceptance test.
     */
    private @Nullable Unit findStormTarget() {
        this.phase.setSpellSearchForced(this.forced);

        return this.game.getComputerResources().findAround(this.caster, this.getCampaignRadius(), this::acceptStorm);
    }

    /**
     * Searches nothing when forced, otherwise {@code 1024} pixels.
     */
    private @Nullable Unit findHallucinationTarget() {
        if (this.forced) {
            return null;
        }

        return this.game.getComputerResources().findAround(this.caster, SHORT_RADIUS, this::acceptHallucination);
    }

    private @Nullable Unit findStasisTarget() {
        return this.game.getComputerResources().findAround(this.caster, this.getCampaignRadius(), this::acceptStasis);
    }

    /**
     * Searches the whole map when the Arbiter's region is quiet for its owner.
     */
    private @Nullable Unit findRecallTarget() {
        int region = this.game.getComputerTerrain().getUnitRegion(this.caster);

        if (!this.phase.getRegions().isQuiet(this.caster.getOwnerIndex(), region)) {
            return null;
        }

        return this.game.getComputerResources().findOnMap(this.caster, this::acceptRecall);
    }

    private boolean acceptLockdown(@NotNull Unit target) {
        if (!this.isSpellEnemy(target) || !this.canWeaponTarget(ScrWeaponIds.LOCKDOWN, target) || UnitOrderEligibility.isDisabled(target)) {
            return false;
        }

        int type = this.game.requireGameData().getBaseType(target);

        if ((type == ScrUnitIds.PROTOSS_SHUTTLE || type == ScrUnitIds.TERRAN_DROPSHIP) &&
            (target.getMovementFlags() & MovementFlags.ACCELERATING) != 0 &&
            this.game.getUnitTransport().hasLoadedUnits(target)) {
            return true;
        }

        if (type == ScrUnitIds.PROTOSS_CARRIER || type == ScrUnitIds.PROTOSS_REAVER) {
            return true;
        }

        return this.isAttackingAlly(target);
    }

    /**
     * Returns whether the target is a detected enemy whose {@code 192}-pixel surroundings sum more than {@code 799}.
     */
    private boolean acceptNukeTarget(@NotNull Unit target) {
        if (UnitTargeting.isUndetected(this.caster, target) || !this.game.getComputerUnits().isEnemy(this.caster.getOwnerIndex(), target)) {
            return false;
        }

        return this.isNukeWorthwhile(target);
    }

    /**
     * Returns whether the target is an enemy with shields whose {@code 160}-pixel surroundings hold more than {@code 199} enemy shield
     * points.
     */
    private boolean acceptEmpShields(@NotNull Unit target) {
        if (!this.isSpellEnemy(target) || this.units.getShieldEnabled(target.getUnitId()) == 0 ||
            !this.canWeaponTarget(ScrWeaponIds.EMP_SHOCKWAVE, target)) {
            return false;
        }

        this.splashWeapon = ScrWeaponIds.EMP_SHOCKWAVE;

        int sum = 0;

        for (Unit unit : this.collect(target, EMP_SURROUNDINGS_RADIUS)) {
            if ((unit.getStatus() & UnitStatusFlags.INVINCIBLE) == 0 && this.game.getComputerUnits().isEnemy(this.caster.getOwnerIndex(), unit) &&
                this.units.getShieldEnabled(unit.getUnitId()) != 0) {
                sum += unit.getShieldPoints() >> FIXED_POINT_FRACTION_BITS;
            }
        }

        return sum > EMP_SUM_LIMIT;
    }

    /**
     * Returns whether the target is an enemy spellcaster whose {@code 160}-pixel surroundings hold more than {@code 199} enemy energy.
     */
    private boolean acceptEmpEnergy(@NotNull Unit target) {
        if (!this.isSpellEnemy(target) || !UnitTypeTraitsUtils.isSpellcaster(this.units, target) ||
            !this.canWeaponTarget(ScrWeaponIds.EMP_SHOCKWAVE, target)) {
            return false;
        }

        this.splashWeapon = ScrWeaponIds.EMP_SHOCKWAVE;

        int sum = 0;

        for (Unit unit : this.collect(target, EMP_SURROUNDINGS_RADIUS)) {
            if ((unit.getStatus() & UnitStatusFlags.INVINCIBLE) == 0 && this.game.getComputerUnits().isEnemy(this.caster.getOwnerIndex(), unit) &&
                UnitTypeTraitsUtils.isSpellcaster(this.units, unit)) {
                sum += unit.getEnergy() >> FIXED_POINT_FRACTION_BITS & BYTE_MASK;
            }
        }

        return sum > EMP_SUM_LIMIT;
    }

    /**
     * Returns whether the target is an unmatrixed, detectable ally with an order target, at least {@code 100} maximum hit
     * points and fewer than {@code 76} hit points, that Defensive Matrix may target.
     */
    private boolean acceptDefensiveMatrix(@NotNull Unit target) {
        if (target.getDefensiveMatrixHealth() != 0 || this.game.getComputerUnits().isEnemy(this.caster.getOwnerIndex(), target) ||
            target.getOrderTargetUnit() == null ||
            (target.getStatus() & UnitStatusFlags.REQUIRES_DETECTOR) != 0) {
            return false;
        }

        if (this.game.getUnitQueries().getMaximumHitPoints(target) <= MATRIX_MAXIMUM_HIT_POINTS_LIMIT ||
            UnitQueries.getHitPoints(target) >= MATRIX_HIT_POINTS_LIMIT) {
            return false;
        }

        return this.game.getUnitSpells().isTechnologyTargetValid(this.caster, ScrTechIds.DEFENSIVE_MATRIX, target);
    }

    /**
     * Returns whether the target is an unirradiated organic non-building enemy (not Larva, Egg or Lurker Egg); in
     * campaigns without the forced flag only workers, Overlords and Medics.
     */
    private boolean acceptIrradiate(@NotNull Unit target) {
        if (!this.isSpellEnemy(target) || target.getIrradiateTimer() != 0 || !this.canWeaponTarget(ScrWeaponIds.IRRADIATE, target)) {
            return false;
        }

        int type = this.game.requireGameData().getBaseType(target);

        if (!this.isOrganic(target) || UnitQueries.isBuilding(this.units, target.getUnitId()) || type == ScrUnitIds.ZERG_LARVA ||
            type == ScrUnitIds.ZERG_EGG || type == ScrUnitIds.ZERG_LURKER_EGG) {
            return false;
        }

        return this.phase.isSpellSearchForced() || !this.campaign || this.game.getComputerUnits().isWorker(target) ||
               type == ScrUnitIds.ZERG_OVERLORD || type == ScrUnitIds.TERRAN_MEDIC;
    }

    /**
     * Returns whether the target is an attacking grounded building, a loaded Bunker, or a non-hero with {@code 200..450} hit
     * points and shields (never an Egg).
     */
    private boolean acceptYamato(@NotNull Unit target) {
        if (!this.isSpellEnemy(target)) {
            return false;
        }

        if (UnitQueries.isGroundedBuilding(target) && this.game.getUnitQueries().canAttack(target)) {
            return true;
        }

        int type = this.game.requireGameData().getBaseType(target);

        if (type == ScrUnitIds.TERRAN_BUNKER) {
            if (this.game.getUnitTransport().hasLoadedUnits(target)) {
                return true;
            }
        } else if (type == ScrUnitIds.ZERG_EGG) {
            return false;
        }

        int amount = UnitQueries.getLife(this.units, target);

        return Integer.compareUnsigned(amount - YAMATO_MINIMUM_LIFE, YAMATO_LIFE_RANGE) < 0 && !this.isHero(target);
    }

    /**
     * Returns whether the target is an own unit Restoration may target with at least {@code 61} hit points and shields and an
     * ensnare, plague, irradiate, lockdown, maelstrom or acid spore effect.
     */
    private boolean acceptRestoration(@NotNull Unit target) {
        if (target.getOwnerIndex() != this.caster.getOwnerIndex() || !this.canWeaponTarget(ScrWeaponIds.RESTORATION, target) ||
            UnitQueries.getLife(this.units, target) < RESTORATION_MINIMUM_LIFE) {
            return false;
        }

        return target.getSpeedDecreaseByte() != 0 || target.getPlagueTimer() != 0 || target.getIrradiateTimer() != 0 ||
               target.getLockdownTimer() != 0 || target.getMaelstromTimer() != 0 || target.getAcidSporeCount() != 0;
    }

    /**
     * Returns whether the target is an own parasited or maelstromed unit Restoration may target.
     */
    private boolean acceptTrackedRestoration(@NotNull Unit target) {
        if (target.getOwnerIndex() != this.caster.getOwnerIndex()) {
            return false;
        }

        if (target.getParasiteFlags() == 0 && target.getMaelstromTimer() == 0) {
            return false;
        }

        return this.canWeaponTarget(ScrWeaponIds.RESTORATION, target);
    }

    /**
     * Returns whether the target is an unblinded non-building enemy that is an active detector or has at least {@code 81} hit
     * points and shields.
     */
    private boolean acceptOpticalFlare(@NotNull Unit target) {
        if (!this.isSpellEnemy(target) || UnitQueries.isBuilding(this.units, target.getUnitId()) || target.getBlindnessState() != 0) {
            return false;
        }

        return this.game.getComputerTargets().isActiveDetector(target) || UnitQueries.getLife(this.units, target) >= OPTICAL_FLARE_MINIMUM_LIFE;
    }

    /**
     * Returns whether the target is a non-hero enemy Spawn Broodlings may target; without the forced flag one with fewer
     * than {@code 100} hit points and shields only when it is a worker, Siege Tank or Medic or is in a nuclear strike order.
     */
    private boolean acceptBroodlings(@NotNull Unit target) {
        if (!this.isSpellEnemy(target) || !this.canWeaponTarget(ScrWeaponIds.SPAWN_BROODLINGS, target) || this.isHero(target)) {
            return false;
        }

        if (this.phase.isSpellSearchForced() || UnitQueries.getLife(this.units, target) >= BROODLINGS_MINIMUM_LIFE ||
            this.game.getComputerUnits().isWorker(target)) {
            return true;
        }

        int type = this.game.requireGameData().getBaseType(target);

        if (type == ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE || type == ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE ||
            type == ScrUnitIds.TERRAN_MEDIC) {
            return true;
        }

        return (target.getPrimaryOrder() - ScrOrderIds.NUKE_WAIT & BYTE_MASK) <= NUKE_ORDER_LAST_OFFSET;
    }

    /**
     * Returns whether the target is an enemy not parasited by the caster's owner (in campaigns not parasited at all)
     * that is, outside campaigns, a detector, transport, spellcaster, worker or has at least {@code 300} hit points and shields.
     */
    private boolean acceptParasite(@NotNull Unit target) {
        if (!this.isSpellEnemy(target)) {
            return false;
        }

        if (this.campaign && target.getParasiteFlags() != 0) {
            return false;
        }

        if ((target.getParasiteFlags() & 1 << this.caster.getOwnerIndex()) != 0) {
            return false;
        }

        if (!this.campaign && !this.game.getComputerTargets().isActiveDetector(target) && !this.game.getUnitQueries().isTransport(target) &&
            !UnitTypeTraitsUtils.isSpellcaster(this.units, target) && !this.game.getComputerUnits().isWorker(target) &&
            UnitQueries.getLife(this.units, target) < PARASITE_MINIMUM_LIFE) {
            return false;
        }

        return this.canWeaponTarget(ScrWeaponIds.PARASITE, target);
    }

    /**
     * Returns whether the target is an unensnared movable enemy attacking an ally, with a worthwhile {@code 96}-pixel splash.
     */
    private boolean acceptEnsnare(@NotNull Unit target) {
        if (!this.isSpellEnemy(target) || !this.canWeaponTarget(ScrWeaponIds.ENSNARE, target) || target.getSpeedDecreaseByte() != 0 ||
            (target.getStatus() & UnitStatusFlags.CAN_MOVE) == 0 || !this.isAttackingAlly(target)) {
            return false;
        }

        return this.isSplashWorthwhile(target, ScrWeaponIds.ENSNARE, false);
    }

    /**
     * Returns whether the target is an unplagued non-hero enemy with a worthwhile {@code 96}-pixel splash (hit points only).
     */
    private boolean acceptPlague(@NotNull Unit target) {
        if (!this.isSpellEnemy(target) || !this.canWeaponTarget(ScrWeaponIds.PLAGUE, target) || target.getPlagueTimer() != 0 ||
            this.isHero(target)) {
            return false;
        }

        return this.isSplashWorthwhile(target, ScrWeaponIds.PLAGUE, false);
    }

    /**
     * Returns whether the target is an enemy attacking a grounded ally without a subunit whose ground weapon is melee,
     * when the enemy's own (or its turret's) ground weapon behavior is one Dark Swarm blocks and the weapon has no miss
     * chance against the ally.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private boolean acceptDarkSwarm(@NotNull Unit target) {
        if (!this.isSpellEnemy(target) || !this.isAttackingAlly(target)) {
            return false;
        }

        Unit ally = target.requireOrderTargetUnit();
        ScrWeaponTypeView weapons = this.game.requireGameData().getWeaponTypes();

        if (ally.getSubunit() != null || (ally.getStatus() & UnitStatusFlags.FLYING) != 0) {
            return false;
        }

        int allyWeapon = this.game.getUnitTargeting().getGroundWeapon(ally);

        if (allyWeapon == ScrWeaponIds.NONE || weapons.getBehavior(allyWeapon) != WeaponBehaviors.APPEAR_AT_SOURCE_UNIT) {
            return false;
        }

        Unit shooter = target.getSubunit();

        if (shooter == null || (this.units.getFlags(shooter.getUnitId()) & UnitTypeFlags.SUBUNIT) == 0) {
            shooter = target;
        }

        int weapon = this.game.getUnitTargeting().getGroundWeapon(shooter);

        if (weapon == ScrWeaponIds.NONE) {
            return false;
        }

        int behavior = weapons.getBehavior(weapon);

        return behavior < DARK_SWARM_BEHAVIOR_LIMIT && (DARK_SWARM_BEHAVIORS >> behavior & 1) != 0 &&
               this.game.getProjectilePhase().getMissChance(shooter, ally) == 0;
    }

    /**
     * Returns whether the target is an enemy that can attack and attacks an air ally (for a loaded Bunker, through its
     * first loaded unit), and is a ground unit within {@code 256} pixels or a Missile Turret, Bunker, Spore Colony or Photon
     * Cannon.
     */
    private boolean acceptDisruptionWeb(@NotNull Unit target) {
        if (!this.isSpellEnemy(target)) {
            return false;
        }

        Unit attacker = target;
        boolean check = true;

        if (this.game.requireGameData().getBaseType(target) == ScrUnitIds.TERRAN_BUNKER && this.game.getUnitTransport().hasLoadedUnits(target)) {
            attacker = this.getFirstLoadedUnit(target);

            if (attacker == null) {
                check = false;
            }
        }

        if (check) {
            Unit ally = attacker.getOrderTargetUnit();

            if (ally == null || ally.getOwnerIndex() >= PlayerTable.PLAYABLE_PLAYER_SLOTS || (ally.getStatus() & UnitStatusFlags.FLYING) == 0 ||
                this.game.getComputerUnits().isEnemy(this.caster.getOwnerIndex(), ally)) {
                return false;
            }
        }

        if ((target.getStatus() & UnitStatusFlags.CANNOT_ATTACK) != 0) {
            return false;
        }

        if ((target.getStatus() & UnitStatusFlags.FLYING) == 0 &&
            this.game.getUnitTargeting().isInRange(this.caster, target, DISRUPTION_WEB_GROUND_RANGE)) {
            return true;
        }

        return ComputerAttack.isAirDefense(this.game.requireGameData().getBaseType(target));
    }

    /**
     * Returns whether the target is a non-hero enemy spellcaster that is not a grounded building and whose hit points
     * and shields do not exceed its energy.
     */
    private boolean acceptFeedback(@NotNull Unit target) {
        if (!this.isSpellEnemy(target) || !UnitTypeTraitsUtils.isSpellcaster(this.units, target) ||
            UnitQueries.isGroundedBuilding(target) || this.isHero(target)) {
            return false;
        }

        return UnitQueries.getLife(this.units, target) <= (target.getEnergy() >> FIXED_POINT_FRACTION_BITS & BYTE_MASK);
    }

    /**
     * Returns whether the target is a non-hero enemy that is a loaded Shuttle or Dropship, one of the listed types, or
     * costs at least as many minerals and gas as a Dark Templar.
     */
    private boolean acceptMindControl(@NotNull Unit target) {
        if (!this.isSpellEnemy(target) || this.isHero(target)) {
            return false;
        }

        int type = this.game.requireGameData().getBaseType(target);

        if ((type == ScrUnitIds.PROTOSS_SHUTTLE || type == ScrUnitIds.TERRAN_DROPSHIP) &&
            this.game.getUnitTransport().hasLoadedUnits(target)) {
            return true;
        }

        return switch (ScrUnitIds.requireValid(type)) {
            case ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE, ScrUnitIds.TERRAN_SCIENCE_VESSEL, ScrUnitIds.TERRAN_BATTLECRUISER,
                 ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE, ScrUnitIds.TERRAN_MEDIC, ScrUnitIds.ZERG_ULTRALISK, ScrUnitIds.ZERG_OVERLORD,
                 ScrUnitIds.ZERG_QUEEN, ScrUnitIds.ZERG_DEFILER, ScrUnitIds.ZERG_DEVOURER, ScrUnitIds.PROTOSS_DARK_ARCHON,
                 ScrUnitIds.PROTOSS_HIGH_TEMPLAR, ScrUnitIds.PROTOSS_ARBITER, ScrUnitIds.PROTOSS_CARRIER, ScrUnitIds.PROTOSS_REAVER,
                 ScrUnitIds.ZERG_LURKER -> true;
            default -> this.units.getMineralCost(target.getUnitId()) >= this.units.getMineralCost(ScrUnitIds.PROTOSS_DARK_TEMPLAR) &&
                       this.units.getGasCost(target.getUnitId()) >= this.units.getGasCost(ScrUnitIds.PROTOSS_DARK_TEMPLAR);
        };
    }

    /**
     * Returns whether the target is an organic unmaelstromed enemy that is a moving loaded Overlord, or a non-building
     * attacking an ally (not Larva, Egg, Cocoon or Lurker Egg) with a worthwhile {@code 96}-pixel organic splash.
     */
    private boolean acceptMaelstrom(@NotNull Unit target) {
        if (!this.isSpellEnemy(target) || !this.canWeaponTarget(ScrWeaponIds.MAELSTROM, target) || !this.isOrganic(target) ||
            target.getMaelstromTimer() != 0) {
            return false;
        }

        int type = this.game.requireGameData().getBaseType(target);

        if (type == ScrUnitIds.ZERG_OVERLORD &&
            (target.getMovementFlags() & MovementFlags.ACCELERATING) != 0 &&
            this.game.getUnitTransport().hasLoadedUnits(target)) {
            return true;
        }

        if (UnitQueries.isBuilding(this.units, target.getUnitId()) || !this.isAttackingAlly(target)) {
            return false;
        }

        if (this.game.requireGameData().getUnitTypes().isLarvaOrEgg(target.getUnitId())) {
            return false;
        }

        return this.isSplashWorthwhile(target, ScrWeaponIds.MAELSTROM, true);
    }

    /**
     * Returns whether the target is an enemy with a worthwhile {@code 96}-pixel splash.
     */
    private boolean acceptStorm(@NotNull Unit target) {
        if (!this.isSpellEnemy(target) || !this.canWeaponTarget(ScrWeaponIds.PSIONIC_STORM, target)) {
            return false;
        }

        return this.isSplashWorthwhile(target, ScrWeaponIds.PSIONIC_STORM, false);
    }

    /**
     * Returns whether the target is an own Carrier, Scout, Arbiter or Archon Hallucination may target.
     */
    private boolean acceptHallucination(@NotNull Unit target) {
        if (target.getOwnerIndex() != this.caster.getOwnerIndex() ||
            !this.game.getUnitSpells().isTechnologyTargetValid(this.caster, ScrTechIds.HALLUCINATION, target)) {
            return false;
        }

        int type = this.game.requireGameData().getBaseType(target);

        return type == ScrUnitIds.PROTOSS_CARRIER || type == ScrUnitIds.PROTOSS_SCOUT || type == ScrUnitIds.PROTOSS_ARBITER ||
               type == ScrUnitIds.PROTOSS_ARCHON;
    }

    /**
     * Returns whether the target is a non-building enemy attacking an ally with a worthwhile {@code 96}-pixel splash.
     */
    private boolean acceptStasis(@NotNull Unit target) {
        if (!this.isSpellEnemy(target) || !this.canWeaponTarget(ScrWeaponIds.STASIS_FIELD, target) ||
            UnitQueries.isBuilding(this.units, target.getUnitId()) || !this.isAttackingAlly(target)) {
            return false;
        }

        return this.isSplashWorthwhile(target, ScrWeaponIds.STASIS_FIELD, false);
    }

    /**
     * Returns whether the target is an own Carrier or Reaver type with an order target and fewer than {@code 11} shield points.
     */
    private boolean acceptRecall(@NotNull Unit target) {
        if (target.getOwnerIndex() != this.caster.getOwnerIndex() || target.getOrderTargetUnit() == null) {
            return false;
        }

        int type = target.getUnitId();

        if (!this.game.requireGameData().getUnitTypes().hasHangar(type)) {
            return false;
        }

        return target.getShieldPoints() >> FIXED_POINT_FRACTION_BITS < RECALL_SHIELD_LIMIT;
    }

    /**
     * Returns the units within the radius box around the unit's position (16-bit coordinates).
     *
     * @throws IllegalStateException if the center unit has no sprite
     */
    private @NotNull Unit @NotNull [] collect(@NotNull Unit center, int radius) {
        Sprite sprite = center.requireSprite();
        int centerX = (short) sprite.getX();
        int centerY = (short) sprite.getY();

        return this.game.getComputerTerrain().collectRectangle(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
    }
}
