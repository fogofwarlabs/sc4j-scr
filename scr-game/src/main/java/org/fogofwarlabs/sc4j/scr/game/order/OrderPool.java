package org.fogofwarlabs.sc4j.scr.game.order;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.type.OrderType;
import org.fogofwarlabs.sc4j.engine.entity.QueuedOrder;
import org.fogofwarlabs.sc4j.scr.content.ScrNativeIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrOrderTypeView;
import org.fogofwarlabs.sc4j.scr.game.save.SaveReferences;
import org.fogofwarlabs.sc4j.scr.game.state.GameRulesVersion;
import org.fogofwarlabs.sc4j.scr.game.state.GameSetup;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Owns the reusable orders of a game and their shared intrusive links: a free list and the order queue of each unit.
 * <p>
 * The game creates the pool with its order limit ({@link GameSetup#ORDER_LIMIT}). Allocation takes the free-list head; a freed order is inserted
 * after the head, so the free list is not in index order.
 */
public final class OrderPool {
    /**
     * The size of a saved order record, the classic {@code 20}-byte order ({@link #writeSaveRecord}).
     */
    public static final int SAVE_RECORD_SIZE = 0x14;

    /**
     * The offset of the previous-order link in a saved order record.
     */
    private static final int RECORD_PREVIOUS = 0x00;
    /**
     * The offset of the next-order link in a saved order record.
     */
    private static final int RECORD_NEXT = 0x04;
    /**
     * The offset of the order identifier BYTE in a saved order record.
     */
    private static final int RECORD_ORDER = 0x08;
    /**
     * The offset of the target unit type WORD in a saved order record.
     */
    private static final int RECORD_UNIT_TYPE = 0x0A;
    /**
     * The offset of the target X coordinate WORD in a saved order record.
     */
    private static final int RECORD_POSITION_X = 0x0C;
    /**
     * The offset of the target Y coordinate WORD in a saved order record.
     */
    private static final int RECORD_POSITION_Y = 0x0E;
    /**
     * The offset of the target unit reference in a saved order record.
     */
    private static final int RECORD_TARGET = 0x10;

    /**
     * The highlighted order count BYTE after a decrement below zero; removal clamps it to {@code 0}.
     */
    private static final int HIGHLIGHTED_COUNT_UNDERFLOW = 0xFF;

    /**
     * The fraction of the native capacity that a nearly full pool keeps free ({@link #isNearlyFull()}).
     */
    private static final double NEARLY_FULL_RESERVE_FRACTION = 0.1;

    /**
     * The mask of an unsigned BYTE: the stored order identifier and the highlighted order count.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of an unsigned WORD: the stored target unit type and coordinates.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * Holds one pooled order: the engine queued order ({@link QueuedOrder}: the links of the free list or queue, the engine order type, the
     * target point and the target unit) with the native order BYTE, the target unit type WORD, the pool slot and the queue owner.
     */
    public static final class Order extends QueuedOrder {
        private final int poolIndex;
        private boolean allocated;

        private @Nullable Unit owner;
        private int orderId;
        private int unitType;

        private Order(int poolIndex) {
            this.poolIndex = poolIndex;
            this.allocated = false;

            this.owner    = null;
            this.orderId  = 0;
            this.unitType = 0;
        }

        /**
         * @return the zero-based slot of the order in the pool
         */
        public int getPoolIndex() {
            return this.poolIndex;
        }

        /**
         * @return {@code true} if this slot is allocated, {@code false} otherwise
         */
        public boolean isAllocated() {
            return this.allocated;
        }

        /**
         * @return the queue owner, or {@code null}
         */
        public @Nullable Unit getOwner() {
            return this.owner;
        }

        /**
         * @return the unsigned BYTE order identifier
         */
        public int getOrderId() {
            return this.orderId;
        }

        /**
         * @return the unsigned WORD target unit type
         */
        public int getUnitType() {
            return this.unitType;
        }

        /**
         * @return the preceding member of the free list or queue, or {@code null}
         */
        @Override
        public @Nullable Order getPrevious() {
            return (Order) super.getPrevious();
        }

        /**
         * @return the following member of the free list or queue, or {@code null}
         */
        @Override
        public @Nullable Order getNext() {
            return (Order) super.getNext();
        }

        /**
         * @return the target unit, or {@code null} for a point target
         */
        @Override
        public @Nullable Unit getTargetUnit() {
            return (Unit) super.getTargetUnit();
        }

        /**
         * Sets the horizontal map position of the target, narrowed to its native WORD.
         *
         * @param targetX the position, truncated to sixteen bits
         */
        @Override
        public void setTargetX(int targetX) {
            super.setTargetX(targetX & WORD_MASK);
        }

        /**
         * Sets the vertical map position of the target, narrowed to its native WORD.
         *
         * @param targetY the position, truncated to sixteen bits
         */
        @Override
        public void setTargetY(int targetY) {
            super.setTargetY(targetY & WORD_MASK);
        }

        /**
         * Sets the order type and the target of the order, the target point narrowed to its native WORDs.
         *
         * @param type       the order type, or {@code null} if the order names no order type of the content
         * @param targetX    the horizontal map position of the target, truncated to sixteen bits
         * @param targetY    the vertical map position of the target, truncated to sixteen bits
         * @param targetUnit the target unit, or {@code null} for a point target
         */
        @Override
        public void set(@Nullable OrderType type, int targetX, int targetY, org.fogofwarlabs.sc4j.engine.entity.@Nullable Unit targetUnit) {
            super.set(type, targetX & WORD_MASK, targetY & WORD_MASK, targetUnit);
        }

        /**
         * Sets the native order BYTE and the engine order type of the entry ({@code null} outside {@code orders.dat}).
         *
         * @param orderId the order identifier, truncated to a BYTE
         */
        private void setOrderId(int orderId) {
            this.orderId = orderId & BYTE_MASK;

            this.setType(ScrNativeIds.orderOf(this.orderId));
        }
    }

    /**
     * The capacity the pool was created with: the initial order limit, unchanged by {@link #expandFreeList(int)}.
     */
    private final int nativeCapacity;

    private @NotNull Order @NotNull [] orders;
    private @Nullable Order firstFreeOrder;
    private @Nullable Order lastFreeOrder;
    private int allocatedCount;

    /**
     * Creates a {@link OrderPool} instance with zeroed orders, all free: each order after the first is inserted after the free-list head, so the free
     * list runs {@code 0, capacity - 1, ..., 1}.
     *
     * @param capacity the order limit, positive
     * @throws IllegalArgumentException if the capacity is not positive
     */
    public OrderPool(int capacity) {
        if (capacity <= 0) {
            throw Exceptions.newIllegalArgumentException("Native order capacity must be positive.");
        }

        this.nativeCapacity = capacity;

        this.orders         = new Order[capacity];
        this.firstFreeOrder = null;
        this.lastFreeOrder  = null;
        this.allocatedCount = 0;

        for (int poolIndex = 0; poolIndex < capacity; poolIndex++) {
            Order order = new Order(poolIndex);

            this.orders[poolIndex] = order;

            if (this.firstFreeOrder == null) {
                this.firstFreeOrder = order;
                this.lastFreeOrder  = order;
            } else {
                this.insertAfterFreeHead(order);
            }
        }
    }

    /**
     * @return the current number of order slots, including entries a free-list commit added
     */
    public int getCapacity() {
        return this.orders.length;
    }

    /**
     * @return the order limit the pool was created with; entries a free-list commit added do not raise it
     */
    public int getNativeCapacity() {
        return this.nativeCapacity;
    }

    /**
     * @return the number of allocated orders
     */
    public int getAllocatedCount() {
        return this.allocatedCount;
    }

    /**
     * Returns the native capacity ({@link #getNativeCapacity()}) minus the allocated orders ({@link #getAllocatedCount()}). The result is negative
     * when more orders are allocated than the native capacity, which the entries added by a free-list commit allow.
     *
     * @return the free order count
     */
    public int getFreeCount() {
        return this.nativeCapacity - this.allocatedCount;
    }

    /**
     * @return the free head, or {@code null}
     */
    public @Nullable Order getFirstFreeOrder() {
        return this.firstFreeOrder;
    }

    /**
     * @return the free tail, or {@code null}
     */
    public @Nullable Order getLastFreeOrder() {
        return this.lastFreeOrder;
    }

    /**
     * @param poolIndex the zero-based slot
     * @return the order in that slot
     */
    public @NotNull Order getOrder(int poolIndex) {
        return this.orders[poolIndex];
    }


    /**
     * Replaces the free list with {@code count + 1} fresh zeroed entries appended to the pool, as the EUD emulation's free-list commit does when a
     * map writes the classic free-list head: both list heads become the first entry of a newly sized array, then each further entry is inserted after
     * the head, giving {@code new[0], new[count], ..., new[1]}. The entries free before leave the free list; allocated entries return to the new list
     * when freed.
     *
     * @param count the length of the classic chain the map built
     * @return the pool index of {@code new[0]}
     */
    public int expandFreeList(int count) {
        int first = this.orders.length;

        this.orders = Arrays.copyOf(this.orders, first + count + 1);

        for (int poolIndex = first; poolIndex < this.orders.length; poolIndex++) {
            this.orders[poolIndex] = new Order(poolIndex);
        }

        Order head = this.orders[first];

        this.firstFreeOrder = head;
        this.lastFreeOrder  = head;

        for (int entry = 1; entry <= count; entry++) {
            Order order = this.orders[first + entry];

            if (this.lastFreeOrder == head) {
                this.lastFreeOrder = order;
            }

            Order next = head.getNext();

            order.setPrevious(head);
            order.setNext(next);

            if (next != null) {
                next.setPrevious(order);
            }

            head.setNext(order);
        }

        return first;
    }

    /**
     * Checks whether the pool is nearly full: the allocated orders reach the native capacity less its reserve, a tenth of the native capacity
     * truncated toward zero.
     *
     * @return {@code true} if at least {@code capacity - (int) (capacity * 0.1)} orders are allocated, {@code false} otherwise
     */
    public boolean isNearlyFull() {
        return this.allocatedCount >= this.nativeCapacity - (int) (this.nativeCapacity * NEARLY_FULL_RESERVE_FRACTION);
    }

    /**
     * Checks whether a unit can still queue the Die order: it holds fewer than {@link GameRulesVersion#HIGHLIGHTED_ORDER_CAPACITY} highlighted queued
     * orders and the pool has a free order ({@link #getFreeCount()} is not zero).
     *
     * @param unit the unit
     * @return {@code true} if the unit can queue the Die order, {@code false} otherwise
     */
    public boolean canQueueDieOrder(@NotNull Unit unit) {
        return unit.getHighlightedOrderCount() < GameRulesVersion.HIGHLIGHTED_ORDER_CAPACITY && this.getFreeCount() != 0;
    }

    /**
     * Allocates the free-list head and copies the target fields of the order into it, masked to their stored widths.
     *
     * @param orderId    the BYTE order identifier
     * @param unitType   the WORD target unit type
     * @param positionX  the WORD target X coordinate
     * @param positionY  the WORD target Y coordinate
     * @param targetUnit the target unit, or {@code null}
     * @return the allocated order, or {@code null} when the pool is exhausted
     */
    public @Nullable Order allocate(int orderId, int unitType, int positionX, int positionY, @Nullable Unit targetUnit) {
        Order order = this.firstFreeOrder;

        if (order == null) {
            return null;
        }

        Order previous = order.getPrevious();
        Order next = order.getNext();

        order.setOrderId(orderId);
        order.unitType = unitType & WORD_MASK;
        order.setTargetX(positionX);
        order.setTargetY(positionY);
        order.setTargetUnit(targetUnit);

        this.firstFreeOrder = next;

        if (this.lastFreeOrder == order) {
            this.lastFreeOrder = previous;
        }

        if (previous != null) {
            previous.setNext(next);
        }

        if (next != null) {
            next.setPrevious(previous);
        }

        order.setPrevious(null);
        order.setNext(null);
        order.allocated = true;
        this.allocatedCount++;

        return order;
    }

    /**
     * Appends an allocated order to a unit's queue, or inserts it after a queued predecessor, and counts it as highlighted when its
     * {@code orders.dat} highlight names a command icon.
     *
     * @param unit       the owning unit
     * @param order      the detached allocated order
     * @param after      the predecessor, or {@code null} to append
     * @param orderTypes the {@code orders.dat} columns
     * @throws IllegalStateException if the order is not allocated in this pool or already queued, or the predecessor belongs to another queue
     */
    public void enqueue(@NotNull Unit unit, @NotNull Order order, @Nullable Order after, @NotNull ScrOrderTypeView orderTypes) {
        this.requireAllocated(order);

        if (order.owner != null) {
            throw Exceptions.newIllegalStateException("The native order is already queued.");
        }

        if (after != null && after.owner != unit) {
            throw Exceptions.newIllegalStateException("The native order predecessor belongs to another queue.");
        }

        if (orderTypes.getHighlight(order.orderId) != ScrOrderTypeView.NO_HIGHLIGHT) {
            unit.setHighlightedOrderCount(unit.getHighlightedOrderCount() + 1);
        }

        Order predecessor = after == null ? unit.getLastOrder() : after;

        if (predecessor == null) {
            unit.setFirstOrder(order);
            unit.setLastOrder(order);

            order.setPrevious(null);
            order.setNext(null);
        } else {
            Order next = predecessor.getNext();

            order.setPrevious(predecessor);
            order.setNext(next);

            if (next == null) {
                unit.setLastOrder(order);
            } else {
                next.setPrevious(order);
            }

            predecessor.setNext(order);
        }

        order.owner = unit;
    }

    /**
     * Inserts an allocated order at the queue head, preserving reciprocal links.
     *
     * @param unit       the owning unit
     * @param order      the detached allocated order
     * @param orderTypes the {@code orders.dat} columns
     * @throws IllegalStateException if the order is not allocated in this pool or already queued
     */
    public void enqueueFront(@NotNull Unit unit, @NotNull Order order, @NotNull ScrOrderTypeView orderTypes) {
        this.requireAllocated(order);

        if (order.owner != null) {
            throw Exceptions.newIllegalStateException("The native order is already queued.");
        }

        if (orderTypes.getHighlight(order.orderId) != ScrOrderTypeView.NO_HIGHLIGHT) {
            unit.setHighlightedOrderCount(unit.getHighlightedOrderCount() + 1);
        }

        Order next = unit.getFirstOrder();

        order.setPrevious(null);
        order.setNext(next);

        if (next == null) {
            unit.setLastOrder(order);
        } else {
            next.setPrevious(order);
        }

        unit.setFirstOrder(order);

        order.owner = unit;
    }

    /**
     * Unlinks one queue member and returns it to the free list after the head; the highlighted order count clamps an underflow to {@code 0}.
     *
     * @param unit       the owning unit
     * @param order      the queued order
     * @param orderTypes the {@code orders.dat} columns
     * @throws IllegalStateException if the order is not allocated in this pool or does not belong to the unit
     */
    public void remove(@NotNull Unit unit, @NotNull Order order, @NotNull ScrOrderTypeView orderTypes) {
        this.requireAllocated(order);

        if (order.owner != unit) {
            throw Exceptions.newIllegalStateException("The native order does not belong to the unit.");
        }

        int highlightedCount = unit.getHighlightedOrderCount();

        if (orderTypes.getHighlight(order.orderId) != ScrOrderTypeView.NO_HIGHLIGHT) {
            highlightedCount = (highlightedCount - 1) & BYTE_MASK;
        }

        unit.setHighlightedOrderCount(highlightedCount == HIGHLIGHTED_COUNT_UNDERFLOW ? 0 : highlightedCount);

        Order previous = order.getPrevious();
        Order next = order.getNext();

        if (unit.getFirstOrder() == order) {
            unit.setFirstOrder(next);
        }

        if (unit.getLastOrder() == order) {
            unit.setLastOrder(previous);
        }

        if (previous != null) {
            previous.setNext(next);
        }

        if (next != null) {
            next.setPrevious(previous);
        }

        order.owner = null;
        this.allocatedCount--;
        order.allocated = false;

        if (this.firstFreeOrder == null) {
            order.setPrevious(null);
            order.setNext(null);
            this.firstFreeOrder = order;
            this.lastFreeOrder  = order;
        } else {
            this.insertAfterFreeHead(order);
        }
    }

    /**
     * Writes an order into its saved record, the classic {@code 20}-byte order of the order section, which keeps every slot, allocated or free, with
     * its stale fields and links.
     *
     * @param poolIndex  the zero-based slot
     * @param record     the record, little-endian, at offset {@code 0}
     * @param references the reference conversions of the game
     */
    public void writeSaveRecord(int poolIndex, @NotNull ByteBuffer record, @NotNull SaveReferences references) {
        Order order = this.orders[poolIndex];

        record.putInt(RECORD_PREVIOUS, references.encodeOrder(order.getPrevious()));
        record.putInt(RECORD_NEXT, references.encodeOrder(order.getNext()));
        record.put(RECORD_ORDER, (byte) order.orderId);
        record.putShort(RECORD_UNIT_TYPE, (short) order.unitType);
        record.putShort(RECORD_POSITION_X, (short) order.getTargetX());
        record.putShort(RECORD_POSITION_Y, (short) order.getTargetY());
        record.putInt(RECORD_TARGET, references.encodeUnit(order.getTargetUnit()));
    }

    /**
     * Reads an order from its saved record ({@link #writeSaveRecord}).
     *
     * @param poolIndex  the zero-based slot
     * @param record     the record, little-endian, at offset {@code 0}
     * @param references the reference conversions of the game
     */
    public void readSaveRecord(int poolIndex, @NotNull ByteBuffer record, @NotNull SaveReferences references) {
        Order order = this.orders[poolIndex];

        order.setPrevious(references.decodeOrder(record.getInt(RECORD_PREVIOUS)));
        order.setNext(references.decodeOrder(record.getInt(RECORD_NEXT)));
        order.setOrderId(Byte.toUnsignedInt(record.get(RECORD_ORDER)));
        order.unitType = Short.toUnsignedInt(record.getShort(RECORD_UNIT_TYPE));
        order.setTargetX(Short.toUnsignedInt(record.getShort(RECORD_POSITION_X)));
        order.setTargetY(Short.toUnsignedInt(record.getShort(RECORD_POSITION_Y)));
        order.setTargetUnit(references.decodeUnit(record.getInt(RECORD_TARGET)));
    }

    /**
     * Restores the free-list ends, allocation flags and queue ownership of a saved game: an order is allocated unless the free list holds it, and an
     * allocated order belongs to the unit whose queue holds it.
     *
     * @param firstFreeOrder the first free order, or {@code null}
     * @param lastFreeOrder  the last free order, or {@code null}
     * @param units          the unit pool, whose units' queues were read
     */
    public void restoreSavedLists(@Nullable Order firstFreeOrder, @Nullable Order lastFreeOrder, @NotNull UnitPool units) {
        this.firstFreeOrder = firstFreeOrder;
        this.lastFreeOrder  = lastFreeOrder;

        for (Order order : this.orders) {
            order.allocated = true;
            order.owner     = null;
        }

        for (Order order = firstFreeOrder; order != null && order.allocated; order = order.getNext()) {
            order.allocated = false;
        }

        this.allocatedCount = 0;

        for (Order order : this.orders) {
            if (order.allocated) {
                this.allocatedCount++;
            }
        }

        for (int poolIndex = 1; poolIndex <= units.getCapacity(); poolIndex++) {
            Unit unit = units.getUnit(poolIndex);

            if (!units.isAllocated(poolIndex)) {
                continue;
            }

            for (Order order = unit.getFirstOrder(); order != null && order.owner == null; order = order.getNext()) {
                order.owner = unit;
            }
        }
    }

    /**
     * Inserts a free order after the head of the nonempty free list; it becomes the free tail when the head was the only member.
     */
    private void insertAfterFreeHead(@NotNull Order order) {
        Order head = this.firstFreeOrder;

        if (head == null) {
            throw Exceptions.newIllegalStateException("The native order free list is empty.");
        }

        Order next = head.getNext();

        order.setPrevious(head);
        order.setNext(next);

        if (next == null) {
            this.lastFreeOrder = order;
        } else {
            next.setPrevious(order);
        }

        head.setNext(order);
    }

    private void requireAllocated(@NotNull Order order) {
        if (order.poolIndex >= this.orders.length || this.orders[order.poolIndex] != order || !order.allocated) {
            throw Exceptions.newIllegalStateException("The native order is not allocated in this pool.");
        }
    }
}
