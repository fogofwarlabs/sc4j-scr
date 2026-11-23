package org.fogofwarlabs.sc4j.scr.game.state;

import org.fogofwarlabs.sc4j.engine.collision.CollisionSystem;
import org.fogofwarlabs.sc4j.engine.game.GameDefinition;
import org.fogofwarlabs.sc4j.engine.hook.HookComposition;
import org.fogofwarlabs.sc4j.engine.hook.HookList;
import org.fogofwarlabs.sc4j.engine.hook.HookPoint;
import org.fogofwarlabs.sc4j.engine.lifecycle.UnitLifecycle;
import org.fogofwarlabs.sc4j.engine.movement.MovementSystem;
import org.fogofwarlabs.sc4j.engine.movement.TransportPickupHook;
import org.fogofwarlabs.sc4j.engine.order.DisabledOrderHook;
import org.fogofwarlabs.sc4j.engine.order.FollowTargetHook;
import org.fogofwarlabs.sc4j.engine.order.OrderSystem;
import org.fogofwarlabs.sc4j.engine.order.OrderTimerHook;
import org.fogofwarlabs.sc4j.engine.production.ProductionSystem;
import org.fogofwarlabs.sc4j.engine.production.QueueSlotClearedHook;
import org.fogofwarlabs.sc4j.engine.production.QueueSlotMovedHook;
import org.fogofwarlabs.sc4j.scr.game.ability.*;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerHooks;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepProviderHook;
import org.fogofwarlabs.sc4j.scr.game.creep.ExtractorCreepHook;
import org.fogofwarlabs.sc4j.scr.game.movement.LarvaAndGatheringBlockingHook;
import org.fogofwarlabs.sc4j.scr.game.resource.GeyserRestoreHook;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.ProducerAirPlacementHook;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitCollisionRules;
import org.fogofwarlabs.sc4j.scr.game.visibility.BurrowedTimerHook;
import org.fogofwarlabs.sc4j.scr.game.visibility.TrapCompletionHook;
import org.jetbrains.annotations.NotNull;

/**
 * Registers the SC:R hooks and calls the hook points the SC:R systems own. The SC:R systems run the generic places of the
 * simulation (movement destinations, order updates, production queues, the unit lifecycle) and call the hooks registered
 * there instead of branching on the SC:R computer players or the psi providers.
 * <p>
 * {@link #register} adds one SC:R hook per point, in native call order: the computer player hooks ({@link ComputerHooks}),
 * the trap cloaking of a completed trap ({@link TrapCompletionHook}), the Flag drop after an owner change ({@link FlagDropHook}), the psi
 * provider removal of a dying Pylon ({@link PsiProviderHook}), the Extractor creep removal and the geyser restore of a dying gas structure
 * ({@link ExtractorCreepHook}, {@link GeyserRestoreHook}), the creep provider removal of a dying Zerg building ({@link CreepProviderHook}),
 * the transport upgrade test ({@link TransportUpgradeHook}), the Larva and gathering branches of the movement blocking rule
 * ({@link LarvaAndGatheringBlockingHook}) and the producer branch of the placement blocking rule ({@link ProducerAirPlacementHook}); the
 * collision rules over the last two points are {@link #getCollisionRules}. A mod adds further hooks after them. The hooks of the unit
 * lifecycle points are called through the engine {@link UnitLifecycle}; an instance of this class resolves the lists of the other points
 * once, when the SC:R game attaches, so a call is an indexed loop without lookup.
 * <p>
 * Determinism contract: the hooks of a point run in registration order inside the simulation frame, at the caller's point; with no
 * hook a notification does nothing, a transport pickup test answers {@code false}, a transport capacity stays the space the type
 * provides, two colliding units block each other and units on the same layer block a placement.
 */
public final class ScrHooks {
    /**
     * The point a unit is loaded into a transport at, before it is hidden in the transport.
     */
    public static final @NotNull HookPoint<TransportLoadedHook> TRANSPORT_LOADED = HookPoint.of("scr.transport-loaded", TransportLoadedHook.class,
                                                                                                HookComposition.EVERY_IN_ORDER);
    /**
     * The point the order timer of a burrowed unit with a computer record elapses at.
     */
    public static final @NotNull HookPoint<BurrowedTimerHook> BURROWED_TIMER = HookPoint.of("scr.burrowed-timer", BurrowedTimerHook.class,
                                                                                            HookComposition.EVERY_IN_ORDER);
    /**
     * The point the transport capacity of a unit is decided at: each hook adjusts the capacity of the hook before it, the first the space the
     * unit type provides ({@link HookComposition#CHAINED}).
     */
    public static final @NotNull HookPoint<TransportCapacityHook> TRANSPORT_CAPACITY = HookPoint.of("scr.transport-capacity",
                                                                                                    TransportCapacityHook.class,
                                                                                                    HookComposition.CHAINED);

    /**
     * Registers the SC:R hooks on a game definition, in native call order per point.
     *
     * @param definition the open definition builder
     * @return the definition builder
     * @throws IllegalStateException if the definition is built
     */
    public static GameDefinition.@NotNull Builder register(GameDefinition.@NotNull Builder definition) {
        return definition.addHook(UnitLifecycle.UNIT_COMPLETED, context -> new TrapCompletionHook())
                         .addHook(UnitLifecycle.UNIT_REMOVED, context -> new ComputerHooks())
                         .addHook(UnitLifecycle.UNIT_ADOPTED, context -> new ComputerHooks())
                         .addHook(UnitLifecycle.UNIT_OWNER_CHANGED, context -> new FlagDropHook())
                         .addHook(UnitLifecycle.BUILDING_DESTROYED, context -> new ComputerHooks())
                         .addHook(UnitLifecycle.UNIT_DYING, context -> new PsiProviderHook())
                         .addHook(UnitLifecycle.UNIT_REMAINS, context -> new ExtractorCreepHook())
                         .addHook(UnitLifecycle.UNIT_REMAINS, context -> new GeyserRestoreHook())
                         .addHook(UnitLifecycle.TERRAIN_RELEASE, context -> new CreepProviderHook())
                         .addHook(ProductionSystem.QUEUE_SLOT_MOVED, context -> new ComputerHooks())
                         .addHook(ProductionSystem.QUEUE_SLOT_CLEARED, context -> new ComputerHooks())
                         .addHook(OrderSystem.DISABLED_ORDER, context -> new ComputerHooks())
                         .addHook(OrderSystem.ORDER_TIMER, context -> new ComputerHooks())
                         .addHook(OrderSystem.FOLLOW_TARGET, context -> new NydusFollowHook())
                         .addHook(OrderSystem.FOLLOW_TARGET, context -> new ShieldBatteryFollowHook())
                         .addHook(MovementSystem.TRANSPORT_PICKUP, context -> new ComputerHooks())
                         .addHook(TRANSPORT_LOADED, context -> new ComputerHooks())
                         .addHook(BURROWED_TIMER, context -> new ComputerHooks())
                         .addHook(TRANSPORT_CAPACITY, context -> new TransportUpgradeHook())
                         .addHook(CollisionSystem.BLOCKING, context -> new LarvaAndGatheringBlockingHook())
                         .addHook(CollisionSystem.PLACEMENT_EXEMPTION, context -> new ProducerAirPlacementHook());
    }

    private final @NotNull HookList<TransportPickupHook> transportPickupHooks;
    private final @NotNull HookList<DisabledOrderHook> disabledOrderHooks;
    private final @NotNull HookList<OrderTimerHook> orderTimerHooks;
    private final @NotNull HookList<FollowTargetHook> followTargetHooks;
    private final @NotNull HookList<QueueSlotMovedHook> queueSlotMovedHooks;
    private final @NotNull HookList<QueueSlotClearedHook> queueSlotClearedHooks;
    private final @NotNull HookList<TransportLoadedHook> transportLoadedHooks;
    private final @NotNull HookList<BurrowedTimerHook> burrowedTimerHooks;
    private final @NotNull HookList<TransportCapacityHook> transportCapacityHooks;
    private final @NotNull UnitCollisionRules collisionRules;

    /**
     * Creates a {@link ScrHooks} instance over the hooks of a game.
     *
     * @param game the engine game
     */
    ScrHooks(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game) {
        this.transportPickupHooks   = game.getHooks(MovementSystem.TRANSPORT_PICKUP);
        this.disabledOrderHooks     = game.getHooks(OrderSystem.DISABLED_ORDER);
        this.orderTimerHooks        = game.getHooks(OrderSystem.ORDER_TIMER);
        this.followTargetHooks      = game.getHooks(OrderSystem.FOLLOW_TARGET);
        this.queueSlotMovedHooks    = game.getHooks(ProductionSystem.QUEUE_SLOT_MOVED);
        this.queueSlotClearedHooks  = game.getHooks(ProductionSystem.QUEUE_SLOT_CLEARED);
        this.transportLoadedHooks   = game.getHooks(TRANSPORT_LOADED);
        this.burrowedTimerHooks     = game.getHooks(BURROWED_TIMER);
        this.transportCapacityHooks = game.getHooks(TRANSPORT_CAPACITY);
        this.collisionRules         = new UnitCollisionRules(game.getHooks(CollisionSystem.BLOCKING),
                                                             game.getHooks(CollisionSystem.PLACEMENT_EXEMPTION));
    }

    /**
     * @return the movement and placement blocking rules over the hooks of {@link CollisionSystem#BLOCKING} and {@link CollisionSystem#PLACEMENT_EXEMPTION}
     */
    public @NotNull UnitCollisionRules getCollisionRules() {
        return this.collisionRules;
    }

    /**
     * Asks the follow target hooks ({@link OrderSystem#FOLLOW_TARGET}) in registration order until one answers.
     *
     * @param unit             the following unit
     * @param target           the followed unit
     * @param instructionLimit the instruction limit of each animation program run the change of the unit's order starts
     * @return {@code true} if a hook gave the unit the order that uses its target, {@code false} otherwise
     */
    public boolean useFollowTarget(@NotNull Unit unit, @NotNull Unit target, int instructionLimit) {
        HookList<FollowTargetHook> hooks = this.followTargetHooks;

        for (int index = 0; index < hooks.size(); index++) {
            if (hooks.get(index).useTarget(unit, target, instructionLimit)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Asks the transport pickup hooks ({@link MovementSystem#TRANSPORT_PICKUP}) in registration order until one answers.
     *
     * @param unit      the unit about to move
     * @param positionX the x coordinate of the destination
     * @param positionY the y coordinate of the destination
     * @return {@code true} if a hook arranged a pickup and the unit does not move by itself, {@code false} otherwise
     */
    public boolean requiresPickup(@NotNull Unit unit, int positionX, int positionY) {
        HookList<TransportPickupHook> hooks = this.transportPickupHooks;

        for (int index = 0; index < hooks.size(); index++) {
            if (hooks.get(index).requiresPickup(unit, positionX, positionY)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Announces that a disabled or immobile unarmed unit runs its main order ({@link OrderSystem#DISABLED_ORDER}).
     *
     * @param unit the unit
     */
    public void notifyDisabledOrder(@NotNull Unit unit) {
        HookList<DisabledOrderHook> hooks = this.disabledOrderHooks;

        for (int index = 0; index < hooks.size(); index++) {
            hooks.get(index).onDisabledOrder(unit);
        }
    }

    /**
     * Announces that the timed order of a unit is about to run ({@link OrderSystem#ORDER_TIMER}).
     *
     * @param unit the unit
     */
    public void notifyOrderTimer(@NotNull Unit unit) {
        HookList<OrderTimerHook> hooks = this.orderTimerHooks;

        for (int index = 0; index < hooks.size(); index++) {
            hooks.get(index).onOrderTimer(unit);
        }
    }

    /**
     * Announces that a queue entry of a producer moves ({@link ProductionSystem#QUEUE_SLOT_MOVED}).
     *
     * @param producer   the producing unit
     * @param slot       the slot that receives the entry
     * @param sourceSlot the slot the entry leaves
     */
    public void notifyQueueSlotMoved(@NotNull Unit producer, int slot, int sourceSlot) {
        HookList<QueueSlotMovedHook> hooks = this.queueSlotMovedHooks;

        for (int index = 0; index < hooks.size(); index++) {
            hooks.get(index).onQueueSlotMoved(producer, slot, sourceSlot);
        }
    }

    /**
     * Announces that a queue slot of a producer becomes empty ({@link ProductionSystem#QUEUE_SLOT_CLEARED}).
     *
     * @param producer the producing unit
     * @param slot     the emptied slot
     */
    public void notifyQueueSlotCleared(@NotNull Unit producer, int slot) {
        HookList<QueueSlotClearedHook> hooks = this.queueSlotClearedHooks;

        for (int index = 0; index < hooks.size(); index++) {
            hooks.get(index).onQueueSlotCleared(producer, slot);
        }
    }

    /**
     * Announces that a unit is loaded into a transport ({@link #TRANSPORT_LOADED}).
     *
     * @param unit the loaded unit
     */
    public void notifyTransportLoaded(@NotNull Unit unit) {
        HookList<TransportLoadedHook> hooks = this.transportLoadedHooks;

        for (int index = 0; index < hooks.size(); index++) {
            hooks.get(index).onTransportLoaded(unit);
        }
    }

    /**
     * Announces that the order timer of a burrowed unit with a computer record elapsed ({@link #BURROWED_TIMER}).
     *
     * @param unit             the burrowed unit
     * @param instructionLimit the instruction limit of each animation program run a hook starts
     */
    public void notifyBurrowedTimer(@NotNull Unit unit, int instructionLimit) {
        HookList<BurrowedTimerHook> hooks = this.burrowedTimerHooks;

        for (int index = 0; index < hooks.size(); index++) {
            hooks.get(index).onBurrowedTimer(unit, instructionLimit);
        }
    }

    /**
     * Passes the transport capacity of a unit through the transport capacity hooks ({@link #TRANSPORT_CAPACITY}) in registration order.
     *
     * @param unit     the unit
     * @param capacity the space the unit type provides
     * @return the capacity the last hook returned, or the given capacity without a hook
     */
    public int getTransportCapacity(@NotNull Unit unit, int capacity) {
        HookList<TransportCapacityHook> hooks = this.transportCapacityHooks;

        for (int index = 0; index < hooks.size(); index++) {
            capacity = hooks.get(index).getTransportCapacity(unit, capacity);
        }

        return capacity;
    }
}
