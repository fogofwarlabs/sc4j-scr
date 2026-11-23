package org.fogofwarlabs.sc4j.scr.game.ai;

/**
 * The state BYTE and the flag BYTE of a computer region record ({@link ComputerRegions}).
 * <p>
 * The attack states ({@link #ATTACK}, {@link #ATTACK_ENGAGED}, {@link #ATTACK_GATHERING}, {@link #ATTACK_LAUNCHED})
 * describe region movement, engagement and gathering. The flags retain town, defense and scouting requests.
 */
final class ComputerRegionStates {
    /**
     * The default; leaving it resets the record.
     */
    static final int IDLE = 0;
    /**
     * An attack region moving to its target.
     */
    static final int ATTACK = 1;
    /**
     * An attack region with 20 military records or with a unit hit.
     */
    static final int ATTACK_ENGAGED = 2;
    /**
     * Wants a defender: the updater links a quiet neighbor in {@link #DEFENDING}.
     */
    static final int DEFENDER_WANTED = 3;
    /**
     * The town region of an allied owner, set when a depot activates.
     */
    static final int ALLY_TOWN = 4;
    /**
     * A town region: set by depot activation or by script {@code value_area}.
     */
    static final int TOWN = 5;
    /**
     * A town region with a target, or with a building hit.
     */
    static final int UNDER_ATTACK = 6;
    /**
     * Holds a linked region and moves its military there when that region needs it.
     */
    static final int DEFENDING = 7;
    /**
     * Attack preparation: the updater waits while the group is gathering.
     */
    static final int ATTACK_GATHERING = 8;
    /**
     * The attack moved on to the next target region.
     */
    static final int ATTACK_LAUNCHED = 9;
    /**
     * The value bits that every state has except {@link #IDLE}, {@link #ATTACK}, {@link #ALLY_TOWN} and {@link #TOWN}: a
     * state without any of them is one of these four.
     */
    static final int ACTIVE_STATE_BITS = 0xFA;

    /**
     * The region was made a town region.
     */
    static final int FLAG_TOWN = 0x01;
    /**
     * A town region without an own Bunker wants one: set by aiscript {@code build_bunkers}, read by the town request and
     * cleared by the Bunker build site.
     */
    static final int FLAG_BUNKER_WANTED = 0x02;
    /**
     * A scout chose the region.
     */
    static final int FLAG_SCOUTED = 0x04;
    /**
     * A town region without an own Missile Turret wants one: set by aiscript {@code build_turrets}, read by the town
     * request and cleared by the Missile Turret build site.
     */
    static final int FLAG_TURRET_WANTED = 0x10;
    /**
     * A unit of the region was hit; every state change clears it.
     */
    static final int FLAG_HIT = 0x20;
    /**
     * Set by melee depot activation: the updater skips the required-strength decay.
     */
    static final int FLAG_KEEP_REQUIRED_STRENGTH = 0x40;

    /**
     * @param state the state BYTE of a region record
     * @return {@code true} if the state is one of the attack states ({@link #ATTACK}, {@link #ATTACK_ENGAGED}, {@link #ATTACK_GATHERING} or {@link #ATTACK_LAUNCHED}), {@code false} otherwise
     */
    static boolean isAttackState(int state) {
        return state == ATTACK || state == ATTACK_ENGAGED || state == ATTACK_GATHERING || state == ATTACK_LAUNCHED;
    }


    private ComputerRegionStates() {
    }
}
