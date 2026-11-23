package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An order a unit receives, before the unit's order queue changes: a plugin rejects the order or changes the order, its target unit or its target
 * position. A target unit replaces the target position with its sprite position, as for any issued order. An order the routine does not represent
 * fails as it fails without a plugin.
 * <p>
 * An issued order replaces the unit's interruptible orders or is queued after them; a rejected issued order leaves the unit's orders unchanged. The
 * requested position is the position the order was asked for: a player command moves its target position off terrain the unit cannot stand on and
 * spreads a group around it, and keeps the command's position as the requested position; any other issued order requests its target position.
 * <p>
 * An inserted order is an order a game routine puts into the unit's queue itself, in front of or behind the queued orders, as a follow-up order;
 * the computer players give some orders this way, such as the return of a guard to its post. A rejected inserted order is not inserted, as when
 * the order pool has no free order, and the routine continues as it does then. Its requested position is its target position, and it reports no
 * {@link OrderIssuedEvent}. The queue work of an issued order and the unburrowing before an order becomes current offer no decision of their own.
 * <p>
 * The Die order and the orders of a turret, which follows its unit, offer no decision.
 */
public final class OrderDecision extends SimulationDecision {
    private final @NotNull SimulationUnit unit;
    private final int requestedPositionX;
    private final int requestedPositionY;
    private final int targetUnitType;
    private final boolean queued;
    private final boolean inserted;

    private int orderId;
    private @Nullable Unit targetUnit;
    private int positionX;
    private int positionY;
    private boolean rejected;

    /**
     * Creates an {@link OrderDecision} instance that is not rejected.
     *
     * @param unit               the receiving unit
     * @param orderId            the orders.dat entry
     * @param targetUnit         the target unit, or {@code null} for a position target
     * @param positionX          the target X coordinate
     * @param positionY          the target Y coordinate
     * @param requestedPositionX the X coordinate the order was asked for
     * @param requestedPositionY the Y coordinate the order was asked for
     * @param targetUnitType     the target unit type of a build or placement order, or {@link ScrUnitIds#NONE}
     * @param queued             whether the order is requested as a queued order
     * @param inserted           whether a game routine inserts the order into the unit's queue
     */
    public OrderDecision(@NotNull SimulationUnit unit,
                         int orderId,
                         @Nullable Unit targetUnit,
                         int positionX, int positionY,
                         int requestedPositionX, int requestedPositionY,
                         int targetUnitType,
                         boolean queued,
                         boolean inserted) {
        this.unit               = unit;
        this.requestedPositionX = requestedPositionX;
        this.requestedPositionY = requestedPositionY;
        this.targetUnitType     = targetUnitType;
        this.queued             = queued;
        this.inserted           = inserted;

        this.orderId    = orderId;
        this.targetUnit = targetUnit;
        this.positionX  = positionX;
        this.positionY  = positionY;
        this.rejected   = false;
    }

    /**
     * @return the receiving unit
     */
    public @NotNull SimulationUnit getUnit() {
        return this.unit;
    }

    /**
     * @return the X coordinate the order was asked for
     */
    public int getRequestedPositionX() {
        return this.requestedPositionX;
    }

    /**
     * @return the Y coordinate the order was asked for
     */
    public int getRequestedPositionY() {
        return this.requestedPositionY;
    }

    /**
     * @return the target unit type of a build or placement order, or {@link ScrUnitIds#NONE}
     */
    public int getTargetUnitType() {
        return this.targetUnitType;
    }

    /**
     * @return {@code true} if the order is requested as a queued order, {@code false} otherwise
     */
    public boolean isQueued() {
        return this.queued;
    }

    /**
     * @return {@code true} if a game routine inserts the order into the unit's queue, {@code false} if the order is issued
     */
    public boolean isInserted() {
        return this.inserted;
    }

    /**
     * @return the orders.dat entry
     */
    public int getOrderId() {
        return this.orderId;
    }

    /**
     * Sets the order the unit receives.
     *
     * @param orderId the orders.dat entry
     */
    public void setOrderId(int orderId) {
        this.orderId = orderId;
    }

    /**
     * @return the target unit, or {@code null} for a position target
     */
    public @Nullable Unit getTargetUnit() {
        return this.targetUnit;
    }

    /**
     * Sets the target unit of the order.
     *
     * @param targetUnit the target unit, which must have a sprite, or {@code null} for a position target
     */
    public void setTargetUnit(@Nullable Unit targetUnit) {
        this.targetUnit = targetUnit;
    }

    /**
     * @return the target X coordinate
     */
    public int getPositionX() {
        return this.positionX;
    }

    /**
     * @return the target Y coordinate
     */
    public int getPositionY() {
        return this.positionY;
    }

    /**
     * Sets the target position of the order, which a target unit replaces.
     *
     * @param positionX the target X coordinate
     * @param positionY the target Y coordinate
     */
    public void setPosition(int positionX, int positionY) {
        this.positionX = positionX;
        this.positionY = positionY;
    }

    /**
     * @return {@code true} if the order is rejected, {@code false} otherwise
     */
    public boolean isRejected() {
        return this.rejected;
    }

    /**
     * Sets whether the order is rejected.
     *
     * @param rejected whether the unit's orders stay unchanged
     */
    public void setRejected(boolean rejected) {
        this.rejected = rejected;
    }
}
