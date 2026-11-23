package org.fogofwarlabs.sc4j.scr.client.ui.command;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.command.Selection;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.LocalSelection;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Holds the interface selection, its count and active unit.
 * The replay viewer reads the recorded command selection; a local game reads the selection produced by its commands.
 * <p>
 * It is the engine {@link Selection} of the SC:R input: the selection is synchronized game state changed by selection commands, so the engine
 * translator and readers see it through the read-only interface; the slots keep their native holes, and the engine view lists the units in
 * slot order without them.
 */
public final class InterfaceSelection implements Selection {
    /**
     * The number of selection slots.
     */
    public static final int CAPACITY = UnitSelection.MAXIMUM_SELECTED;

    private final @NotNull ScrUnitTypeView unitTypes;
    private final @Nullable Unit @NotNull [] units;

    private int count;
    private int player;
    private @Nullable Unit activeUnit;
    private int selectionCount;

    /**
     * Creates an empty {@link InterfaceSelection} instance for player {@code 0}.
     *
     * @param unitTypes the unit types whose sublabel ranks the selected units
     */
    public InterfaceSelection(@NotNull ScrUnitTypeView unitTypes) {
        this.unitTypes = unitTypes;
        this.units     = new Unit[CAPACITY];

        this.count          = 0;
        this.player         = 0;
        this.activeUnit     = null;
        this.selectionCount = 0;
    }

    /**
     * @return the player whose selection is shown
     */
    public int getPlayer() {
        return this.player;
    }

    /**
     * @return the number of selected units
     */
    public int getCount() {
        return this.count;
    }

    /**
     * @return the number of selections made through {@link #select(int, Unit...)}; a change tells that the console was recomputed for a new selection
     */
    public int getSelectionCount() {
        return this.selectionCount;
    }

    /**
     * @param slot the slot from {@code 0} through {@code 11}
     * @return the selected unit in the slot, or {@code null}
     */
    public @Nullable Unit getUnit(int slot) {
        return this.units[Objects.checkIndex(slot, CAPACITY)];
    }

    /**
     * @return the active unit whose button set and status panel are shown, or {@code null} without a selection
     */
    public @Nullable Unit getActiveUnit() {
        return this.activeUnit;
    }

    /**
     * @return the number of slots, {@link #CAPACITY}
     */
    @Override
    public int getCapacity() {
        return CAPACITY;
    }

    /**
     * @return the selected units in slot order without the empty slots, a read-only list
     */
    @Override
    public @NotNull List<org.fogofwarlabs.sc4j.engine.entity.Unit> getUnits() {
        List<org.fogofwarlabs.sc4j.engine.entity.Unit> selected = new ArrayList<>(this.count);

        for (int slot = 0; slot < this.count; slot++) {
            Unit unit = this.units[slot];

            if (unit != null) {
                selected.add(unit);
            }
        }

        return Collections.unmodifiableList(selected);
    }

    /**
     * @return the number of selected units, the empty slots not counted
     */
    @Override
    public int getSize() {
        int size = 0;

        for (int slot = 0; slot < this.count; slot++) {
            if (this.units[slot] != null) {
                size++;
            }
        }

        return size;
    }

    /**
     * @return {@code true} if no slot holds a unit, {@code false} otherwise
     */
    @Override
    public boolean isEmpty() {
        return this.count == 0;
    }

    /**
     * @return {@code true} if every slot holds a unit, {@code false} otherwise
     */
    @Override
    public boolean isFull() {
        return this.getSize() >= CAPACITY;
    }

    /**
     * Tells whether a unit is selected.
     *
     * @param unit the unit
     * @return {@code true} if a slot holds the unit, {@code false} otherwise
     */
    @Override
    public boolean contains(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit) {
        for (int slot = 0; slot < this.count; slot++) {
            if (this.units[slot] == unit) {
                return true;
            }
        }

        return false;
    }


    /**
     * Sets one slot directly, as a raw write to the local selection array does: the other slots keep their units and holes are kept; the count
     * covers the slots up to the last unit, and the active unit is chosen again over all slots.
     *
     * @param player   the local player
     * @param position the slot from {@code 0} through {@code 11}
     * @param unit     the unit to store, or {@code null} for an empty slot
     */
    public void setSlot(int player, int position, @Nullable Unit unit) {
        this.player                                        = player;
        this.units[Objects.checkIndex(position, CAPACITY)] = unit;

        this.countSlots();
        this.chooseActiveUnit();
    }

    /**
     * Replaces all twelve slots with explicit units, as the start of the own local selection and the replay viewer's empty panel do.
     *
     * @param player the local player
     * @param units  up to twelve selected units
     * @throws IllegalArgumentException if more than twelve units are given
     */
    public void set(int player, @NotNull Unit @NotNull ... units) {
        if (units.length > CAPACITY) {
            throw Exceptions.newIllegalArgumentException("A selection holds at most twelve units.");
        }

        this.player = player;

        Arrays.fill(this.units, null);
        System.arraycopy(units, 0, this.units, 0, units.length);

        this.count = units.length;

        this.chooseActiveUnit();
    }

    /**
     * Sets the count to the slots up to the last unit, holes included.
     */
    private void countSlots() {
        this.count = 0;

        for (int slot = 0; slot < CAPACITY; slot++) {
            if (this.units[slot] != null) {
                this.count = slot + 1;
            }
        }
    }

    /**
     * Chooses the active unit in slot order, preferring enabled units and then strictly higher unsigned ranks.
     * Equal ranks keep the earlier slot.
     */
    private void chooseActiveUnit() {
        Unit active = null;

        for (int slot = 0; slot < CAPACITY; slot++) {
            Unit candidate = this.units[slot];

            if (candidate == null) {
                continue;
            }

            if (active == null) {
                active = candidate;

                continue;
            }

            boolean activeDisabled = UnitOrderEligibility.isDisabled(active);
            boolean candidateDisabled = UnitOrderEligibility.isDisabled(candidate);

            if (activeDisabled != candidateDisabled) {
                if (activeDisabled) {
                    active = candidate;
                }

                continue;
            }

            if (UnitQueries.getRank(this.unitTypes, candidate) > UnitQueries.getRank(this.unitTypes, active)) {
                active = candidate;
            }
        }

        this.activeUnit = active;
    }

    /**
     * Reads the synchronized selection of a player from the game state.
     *
     * @param view   the game view
     * @param player the player slot from {@code 0} through {@code 7}
     */
    public void update(@NotNull ScrGameView view, int player) {
        this.player = player;

        Arrays.fill(this.units, null);

        this.count      = 0;
        this.activeUnit = null;

        UnitSelection selection = view.getSelection();

        if (selection == null || player < 0 || player >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return;
        }

        for (int position = 0; position < CAPACITY; position++) {
            Unit unit = selection.getSelectedUnit(player, position);

            if (unit == null) {
                break;
            }

            this.units[this.count++] = unit;
        }

        this.chooseActiveUnit();
    }

    /**
     * Selects explicit units as a local selection change does before its command is sent ({@link LocalSelection#replaceSlots}): the slots before
     * the first empty slot become empty and the units fill the first slots, while the slots after the first empty slot that the units do not reach
     * keep their units. Increments the refresh count, even when the selected units did not change.
     *
     * @param player the local player
     * @param units  up to twelve selected units
     * @throws IllegalArgumentException if more than twelve units are given
     */
    public void select(int player, @NotNull Unit @NotNull ... units) {
        if (units.length > CAPACITY) {
            throw Exceptions.newIllegalArgumentException("A selection holds at most twelve units.");
        }

        int[] slots = new int[CAPACITY];
        int[] selection = new int[units.length];

        for (int slot = 0; slot < CAPACITY; slot++) {
            Unit unit = this.units[slot];

            slots[slot] = unit == null ? LocalSelection.EMPTY_SLOT : unit.getIndex();
        }

        for (int slot = 0; slot < units.length; slot++) {
            selection[slot] = units[slot].getIndex();
        }

        LocalSelection.replaceSlots(slots, selection, units.length);

        this.player = player;

        for (int slot = 0; slot < CAPACITY; slot++) {
            if (slot < units.length) {
                this.units[slot] = units[slot];
            } else if (slots[slot] == LocalSelection.EMPTY_SLOT) {
                this.units[slot] = null;
            }
        }

        this.selectionCount++;

        this.countSlots();
        this.chooseActiveUnit();
    }
}
