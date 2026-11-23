package org.fogofwarlabs.sc4j.scr.game.ability;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerPlayerPhase;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerTerrain;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerUnits;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitCreationContext;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;

/**
 * Applies the {@link ScrOrderIds#RESCUE_PASSIVE} order ({@code 161}) of rescuable players.
 * <p>
 * Scans nearby human-owned units and transfers the rescued unit, or its resource depot owner's living units, to the first rescuer found.
 */
public final class UnitRescue {
    /**
     * The main order timer set before each scan, so the scan runs every frame.
     */
    private static final int RESCUE_SCAN_TIMER = 1;
    /**
     * The half size of the box around the unit searched for a rescuer, in pixels.
     */
    private static final int RESCUE_RADIUS = 0x40;
    /**
     * The Zerg rescue sound; the Terran and Protoss sounds follow it in race order.
     */
    private static final int RESCUE_SOUND = 32;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitRescue} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitRescue(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Applies {@link ScrOrderIds#RESCUE_PASSIVE}: while the main order timer is {@code 0} it is set to {@code 1} and the units in the box within
     * {@link #RESCUE_RADIUS} pixels of the unit are visited; the first whose owner is a human rescues: a completed resource depot hands every living
     * unit of its owner over, otherwise only this unit, each through the owner change (score counted), and then receives
     * {@link ScrOrderIds#BUNKER_GUARD} inside a Bunker or its {@code units.dat} Human AI Idle order. Then the rescue sound ({@link #playRescueSound})
     * plays; the interface refresh is presentation.
     *
     * @param unit             the unit executing {@link ScrOrderIds#RESCUE_PASSIVE}
     * @param instructionLimit the ownership and order budget
     * @throws UnsupportedOperationException if the computer player phase (the unit rectangle query) is unavailable or the unit pool is not initialized
     * @throws NullPointerException          if the world was built without game data
     */
    public void update(@NotNull Unit unit, int instructionLimit) {
        if (unit.getOrderTimer() != 0) {
            return;
        }

        unit.setOrderTimer(RESCUE_SCAN_TIMER);

        ComputerPlayerPhase phase = this.game.getComputerPlayerPhase();

        if (phase == null) {
            throw Exceptions.newUnsupportedOperationException("RescuePassive requires the unit rectangle query.");
        }

        ComputerTerrain query = this.game.getComputerTerrain();
        int positionX = unit.getPositionX();
        int positionY = unit.getPositionY();
        int[] candidates = query.collectRectanglePoolIndices(positionX - RESCUE_RADIUS, positionY - RESCUE_RADIUS,
                                                             positionX + RESCUE_RADIUS, positionY + RESCUE_RADIUS);

        for (int poolIndex : candidates) {
            UnitPool pool = this.game.getUnitSystem().requireUnitPool();
            Unit rescuer = pool.getUnit(poolIndex);
            Player owner = this.game.getPlayerTable().getPlayer(rescuer.getOwnerIndex());

            if (owner == null || owner.getType() != PlayerTypes.HUMAN) {
                continue;
            }

            int newOwner = rescuer.getOwnerIndex();

            if (this.game.getUnitResourceSearch().isActiveDepot(unit)) {
                int index = pool.getFirstOwnerIndex(unit.getOwnerIndex());

                while (index != 0) {
                    int next = pool.getNextOwnerIndex(index);
                    Unit member = pool.getUnit(index);

                    if (!ComputerUnits.isDeadOrHidden(member)) {
                        this.game.getUnitOwnership().setOwner(member, newOwner, true, instructionLimit);

                        int order = (member.getStatus() & UnitStatusFlags.IN_BUNKER) != 0
                                    ? ScrOrderIds.BUNKER_GUARD
                                    : this.game.requireGameData().getUnitTypes().getHumanIdleOrder(member.getUnitId());

                        this.game.getOrders().issueUntargeted(member, order, instructionLimit);
                    }

                    index = next;
                }
            } else {
                this.game.getUnitOwnership().setOwner(unit, newOwner, true, instructionLimit);

                int order = (unit.getStatus() & UnitStatusFlags.IN_BUNKER) != 0
                            ? ScrOrderIds.BUNKER_GUARD
                            : this.game.requireGameData().getUnitTypes().getHumanIdleOrder(unit.getUnitId());

                this.game.getOrders().issueUntargeted(unit, order, instructionLimit);
            }

            this.playRescueSound(unit);

            return;
        }
    }

    /**
     * Plays the rescue feedback: outside replays, for a unit of the local player (replay observation has local player {@code 8}, so no unit matches),
     * the sprite flashes and the rescue sound of the local player's race plays at the unit.
     *
     * @param unit the rescued unit that executed the order
     */
    private void playRescueSound(@NotNull Unit unit) {
        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

        if (context == null || unit.getOwnerIndex() != context.getLocalPlayer()) {
            return;
        }

        Player local = this.game.getPlayerTable().getPlayer(context.getLocalPlayer());
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite != null) {
            sprite.setFlashTimer(Sprite.FLASH_DURATION);
        }

        if (local != null) {
            this.game.getUnitSounds().play(RESCUE_SOUND + local.getRace(), unit);
        }
    }
}
