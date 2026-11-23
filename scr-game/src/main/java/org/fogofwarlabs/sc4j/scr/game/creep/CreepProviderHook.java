package org.fogofwarlabs.sc4j.scr.game.creep;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.game.system.GameSystem;
import org.fogofwarlabs.sc4j.engine.lifecycle.TerrainReleaseHook;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Removes a dying grounded Zerg building from the creep providers ({@link CreepField#removeProvider}): the SC:R hook of the terrain release
 * point. The hook holds no state of its own; it finds the SC:R game when it is attached.
 */
public final class CreepProviderHook implements TerrainReleaseHook, GameSystem {
    private @Nullable Game game;

    /**
     * Creates a {@link CreepProviderHook} instance; it calls the SC:R game it finds when it is attached.
     */
    public CreepProviderHook() {
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
     * Called while a unit dies, where it stops changing the terrain around it.
     * <p>
     * A grounded Zerg building stops providing creep: a morphing building (Lair, Hive, Greater Spire, Sunken or Spore Colony at the queue head)
     * is marked completed first, so it releases its full creep area. When the recession records run out, the removal must be retried.
     *
     * @param unit     the dying unit
     * @param released {@code true} if the hooks before this one finished their release, {@code false} otherwise; the first hook receives {@code true}
     * @return {@code true} if every release so far is finished, {@code false} if one must be retried before the unit is released
     * @throws IllegalArgumentException      if the unit is not an SC:R unit
     * @throws IllegalStateException         if the hook is not attached
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if a grounded Zerg building dies without the creep field
     */
    @Override
    public boolean releaseTerrain(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit, boolean released) {
        Unit scrUnit = Unit.of(unit);
        Game attached = this.requireGame();
        ScrUnitTypeView units = attached.requireGameData().getUnitTypes();
        int type = scrUnit.getUnitId();

        if (!UnitQueries.isGroundedBuilding(scrUnit) || (units.getGroupFlags(type) & UnitGroupFlags.ZERG) == 0) {
            return released;
        }

        if (UnitQueries.isBuildingMorph(units, scrUnit)) {
            scrUnit.setStatus(scrUnit.getStatus() | UnitStatusFlags.COMPLETED);
        }

        CreepField creep = attached.getCreepField();

        if (creep == null) {
            throw Exceptions.newUnsupportedOperationException("Zerg building death requires the creep field.");
        }

        boolean removed = creep.removeProvider(attached, type, scrUnit.getPositionX(), scrUnit.getPositionY(),
                                               (scrUnit.getStatus() & UnitStatusFlags.COMPLETED) != 0);

        return released && removed;
    }

    private @NotNull Game requireGame() {
        Game attached = this.game;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R creep provider hook is not attached.");
        }

        return attached;
    }
}
