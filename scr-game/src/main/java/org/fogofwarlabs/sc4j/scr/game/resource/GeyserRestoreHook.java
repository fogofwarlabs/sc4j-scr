package org.fogofwarlabs.sc4j.scr.game.resource;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.game.system.GameSystem;
import org.fogofwarlabs.sc4j.engine.lifecycle.UnitRemainsHook;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Turns a dying Refinery, Extractor or Assimilator back into a neutral completed Vespene Geyser: the SC:R hook of the unit remains point that
 * keeps the unit. The unit keeps its slot and sprite, stays outside the dead list, and restarts with the geyser's idle order and full hit points.
 * The hook holds no state of its own; it finds the SC:R game when it is attached.
 */
public final class GeyserRestoreHook implements UnitRemainsHook, GameSystem {
    private @Nullable Game game;

    /**
     * Creates a {@link GeyserRestoreHook} instance; it calls the SC:R game it finds when it is attached.
     */
    public GeyserRestoreHook() {
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
     * Called while a unit in the world dies, after it left its owner. A hook that keeps the unit turns it into what remains of it.
     * <p>
     * A gas structure ({@link ScrUnitTypeView#isGasStructure}) leaves the completed counts, morphs into the Vespene Geyser, takes its idle order
     * with an empty target and its full hit points, passes to the neutral player and counts as a completed geyser.
     *
     * @param unit             the dying unit
     * @param instructionLimit the instruction limit of each animation program run the hook starts
     * @return {@code true} if the unit was a gas structure and is a geyser now, {@code false} for no answer
     * @throws IllegalArgumentException      if the unit is not an SC:R unit
     * @throws IllegalStateException         if the hook is not attached or the unit has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit accounting is not initialized
     */
    @Override
    public boolean keepsUnit(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit, int instructionLimit) {
        Unit scrUnit = Unit.of(unit);
        Game attached = this.requireGame();
        ScrUnitTypeView units = attached.requireGameData().getUnitTypes();

        if (!units.isGasStructure(scrUnit.getUnitId())) {
            return false;
        }

        UnitAccounting accounting = attached.getPlayerTable().requireUnitAccounting();

        if ((scrUnit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
            accounting.updateCompleted(scrUnit, units, -1, false, attached.getPlayerTable().getProductionStatistics());
        }

        scrUnit.setStatus(scrUnit.getStatus() & ~UnitStatusFlags.COMPLETED);
        attached.getUnitMorphing().morph(scrUnit, ScrUnitIds.RESOURCE_VESPENE_GEYSER, instructionLimit);
        scrUnit.setPrimaryOrder(units.getHumanIdleOrder(ScrUnitIds.RESOURCE_VESPENE_GEYSER));
        scrUnit.setOrderTargetUnit(null);
        scrUnit.setOrderTargetX(0);
        scrUnit.setOrderTargetY(0);
        scrUnit.setPrimaryOrderState(0);
        scrUnit.setOrderTargetUnitType(ScrUnitIds.NONE);
        scrUnit.setHitPoints(attached.getUnitHealth().getMaximumHitPoints(scrUnit));
        attached.getUnitOwnership().changeOwner(scrUnit, PlayerTable.NEUTRAL_PLAYER_ID, false, instructionLimit);
        scrUnit.setStatus(scrUnit.getStatus() | UnitStatusFlags.COMPLETED);
        accounting.updateCompleted(scrUnit, units, 1, true, attached.getPlayerTable().getProductionStatistics());

        return true;
    }

    private @NotNull Game requireGame() {
        Game attached = this.game;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R geyser restore hook is not attached.");
        }

        return attached;
    }
}
