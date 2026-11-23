package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.save.SaveReferences;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Retains the computer state of one player: its flags, the spending request heap with the reserved costs and the
 * per-update budgets, the defense lists, the maximum unit counts, the attack group and its preparation, and the script
 * countdowns.
 * <p>
 * The spending requests form a binary max-heap of {@code 63} entries ordered by priority; an entry holds a kind, a
 * unit, upgrade or technology type and the requesting town, region or guard. The costs of queued requests are reserved
 * and compared with the per-update budgets.
 */
public final class ComputerPlayer {
    /**
     * The flag of a player whose update ran.
     */
    static final int FLAG_UPDATED = 0x1;
    /**
     * The flag of a player that started a town.
     */
    static final int FLAG_TOWN_STARTED = 0x2;
    /**
     * The flag of a player whose default build is off (script opcode {@code defaultbuild_off}).
     */
    static final int FLAG_DEFAULT_BUILD_OFF = 0x4;
    /**
     * The flag of a player whose transports are off.
     */
    static final int FLAG_TRANSPORTS_OFF = 0x8;
    /**
     * The flag of a player that builds farms without timing.
     */
    static final int FLAG_FARMS_WITHOUT_TIMING = 0x10;
    /**
     * The flag of a campaign player.
     */
    static final int FLAG_CAMPAIGN = 0x20;
    /**
     * The flag of a player whose defense update ran.
     */
    static final int FLAG_DEFENSE_UPDATED = 0x40;
    /**
     * The flag of a player whose Creep Colonies are spaced by patterns instead of the defended choke.
     */
    static final int FLAG_CREEP_SPACING = 0x80;
    /**
     * The flag of a player with a patrol assignment.
     */
    static final int FLAG_PATROL_ASSIGNMENT = 0x100;
    /**
     * The flag of a player stronger than every enemy side; it suppresses defense requests.
     */
    static final int FLAG_OUTNUMBERING = 0x200;
    /**
     * The flag of a player that requests a scan.
     */
    static final int FLAG_REQUEST_SCAN = 0x400;
    /**
     * The flag of a player whose attack preparation is ready; read together with the prepared attack count.
     */
    static final int FLAG_ATTACK_READY = 0x800;

    /**
     * The entries of the spending request heap.
     */
    static final int REQUEST_CAPACITY = 63;
    /**
     * The spending request kind of no active request.
     */
    static final int REQUEST_KIND_NONE = 0;
    /**
     * The spending request kind of a region's military unit.
     */
    static final int REQUEST_KIND_REGION = 1;
    /**
     * The spending request kind of a guard refill.
     */
    static final int REQUEST_KIND_GUARD = 2;
    /**
     * The spending request kind of a town's unit or building; an add-on adds {@link UnitTypeFlags#ADDON} twice.
     */
    static final int REQUEST_KIND_TOWN = 3;
    /**
     * The spending request kind of a town's worker.
     */
    static final int REQUEST_KIND_WORKER = 4;
    /**
     * The spending request kind of an upgrade.
     */
    static final int REQUEST_KIND_UPGRADE = 5;
    /**
     * The spending request kind of a research.
     */
    static final int REQUEST_KIND_RESEARCH = 6;
    /**
     * The multiplier mapping the add-on flag into the spending request kind.
     */
    static final int ADDON_REQUEST_FLAG_MULTIPLIER = 2;
    /**
     * The spending request kind of a town's add-on.
     */
    static final int REQUEST_KIND_ADDON = REQUEST_KIND_TOWN | UnitTypeFlags.ADDON * ADDON_REQUEST_FLAG_MULTIPLIER;
    /**
     * The spending request kind of a Protoss transport owner's Observer.
     */
    static final int REQUEST_KIND_OBSERVER = 8;

    /**
     * The defense lists in script opcode order: build lists, then use lists, each for ground and air.
     */
    static final int DEFENSE_BUILD_GROUND_GROUND = 0;
    static final int DEFENSE_BUILD_AIR_GROUND = 1;
    static final int DEFENSE_BUILD_GROUND_AIR = 2;
    static final int DEFENSE_BUILD_AIR_AIR = 3;
    static final int DEFENSE_USE_GROUND_GROUND = 4;
    static final int DEFENSE_USE_AIR_GROUND = 5;
    static final int DEFENSE_USE_GROUND_AIR = 6;
    static final int DEFENSE_USE_AIR_AIR = 7;
    /**
     * The number of defense lists: build and use, for ground and air attackers and defenders.
     */
    static final int DEFENSE_LIST_COUNT = 8;
    /**
     * The unit types one defense list holds.
     */
    static final int DEFENSE_LIST_CAPACITY = 20;

    /**
     * The used entries of the attack group; the last of its entries stays the terminator.
     */
    static final int ATTACK_GROUP_LIMIT = 0x3F;

    /**
     * The size of the saved player record.
     */
    static final int SAVED_SIZE = 0x4E8;

    /**
     * The region strength scale of a new player.
     */
    private static final int DEFAULT_REGION_STRENGTH_SCALE = 100;
    /**
     * The difficulty of a new player and of a player that starts a town.
     */
    private static final int DEFAULT_DIFFICULTY = 1;
    /**
     * The most defense list entries one script opcode adds.
     */
    private static final int DEFENSE_ADD_LIMIT = 10;
    /**
     * The entries of the attack group, including the terminator.
     */
    private static final int ATTACK_GROUP_SIZE = ATTACK_GROUP_LIMIT + 1;
    /**
     * The attack group entry of a lost unit: the type {@link ScrUnitIds#NONE} plus one.
     */
    private static final int LOST_ATTACK_ENTRY = 0xE5;
    /**
     * The clock ticks after which an attack preparation expires.
     */
    private static final int ATTACK_EXPIRY = 0x78;
    /**
     * The clock ticks after which a campaign player's attack preparation expires; also the base of the attack delay.
     */
    private static final int CAMPAIGN_ATTACK_EXPIRY = 0xB4;
    /**
     * The attack delay of a zero script operand.
     */
    private static final int DEFAULT_ATTACK_DELAY = 0x14;
    /**
     * The clock ticks after the last building hit during which other unit requests wait.
     */
    private static final int BUILDING_HIT_REQUEST_DELAY = 30;
    /**
     * The indices and the size of the general command rectangle.
     */
    private static final int GENERAL_COMMAND_LEFT = 0;
    private static final int GENERAL_COMMAND_TOP = 1;
    private static final int GENERAL_COMMAND_RIGHT = 2;
    private static final int GENERAL_COMMAND_BOTTOM = 3;
    private static final int GENERAL_COMMAND_RECTANGLE_SIZE = 4;

    /**
     * Truncates a field to a BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * Truncates a field to a WORD.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The multiplier of each state-hash step.
     */
    private static final int HASH_MULTIPLIER = 31;
    /**
     * The children of a request heap entry.
     */
    private static final int REQUEST_HEAP_CHILD_COUNT = 2;

    private static final int SAVED_RESERVED_MINERALS = 0x00;
    private static final int SAVED_RESERVED_GAS = 0x04;
    private static final int SAVED_RESERVED_SUPPLY = 0x08;
    private static final int SAVED_MINERAL_BUDGET = 0x0C;
    private static final int SAVED_GAS_BUDGET = 0x10;
    private static final int SAVED_SUPPLY_BUDGET = 0x14;
    private static final int SAVED_REQUESTS = 0x18;
    private static final int SAVED_REQUEST_SIZE = 8;
    private static final int SAVED_REQUEST_KIND_OFFSET = 1;
    private static final int SAVED_REQUEST_COUNT = 0x210;
    private static final int SAVED_PLACEMENT_COUNTDOWN = 0x211;
    private static final int SAVED_NUKE_RATE = 0x212;
    private static final int SAVED_ATTACK_PREPARATION = 0x213;
    private static final int SAVED_LAST_NUKE_CLOCK = 0x214;
    private static final int SAVED_FLAGS = 0x218;
    private static final int SAVED_PANIC_ADDRESS = 0x21A;
    private static final int SAVED_STRENGTH_LIMIT = 0x21C;
    private static final int SAVED_ATTACK_REGION = 0x21E;
    private static final int SAVED_PENDING_UNIT_REQUEST = 0x220;
    private static final int SAVED_REGION_STRENGTH_SCALE = 0x222;
    private static final int SAVED_DEFENSE_COUNTDOWN = 0x223;
    private static final int SAVED_LAST_BUILDING_HIT_CLOCK = 0x224;
    private static final int SAVED_ATTACK_FRAME = 0x228;
    private static final int SAVED_STRENGTH_ATTACK_COUNTDOWN = 0x22C;
    private static final int SAVED_SPELL_COUNTDOWN = 0x22D;
    private static final int SAVED_ATTACK_SEARCH_COUNTDOWN = 0x22E;
    private static final int SAVED_DIFFICULTY = 0x22F;
    private static final int SAVED_ATTACK_TYPES = 0x230;
    private static final int SAVED_DEFENSE_LISTS = 0x2B0;
    private static final int SAVED_MAXIMUM_COUNTS = 0x3F0;
    private static final int SAVED_MEDIC = 0x4D4;
    private static final int SAVED_GENERAL_COMMAND_RECTANGLE = 0x4D8;

    private final int owner;

    private int flags;
    private int panicAddress;
    /**
     * The difficulty read by the script opcodes {@code if_dif} and {@code easy_attack}; always {@code 1} in melee.
     */
    private int difficulty;

    private int reservedMinerals;
    private int reservedGas;
    private int reservedSupply;
    private int mineralBudget;
    private int gasBudget;
    private int supplyBudget;

    private final byte @NotNull [] requestPriorities;
    private final byte @NotNull [] requestKinds;
    private final short @NotNull [] requestTypes;
    private final @Nullable ComputerTown @NotNull [] requestTowns;
    private final int @NotNull [] requestRegions;
    /**
     * The guard record target of {@link #REQUEST_KIND_GUARD} requests.
     */
    private final @Nullable ComputerUnitRecord @NotNull [] requestGuards;
    private int requestCount;
    private int pendingUnitRequest;
    private int lastBuildingHitClock;

    private final short @NotNull [] @NotNull [] defenseLists;
    private int defenseCountdown;
    private final byte @NotNull [] maximumCounts;
    private int strengthLimit;
    private int regionStrengthScale;

    private int attackPreparation;
    /**
     * The attack group: a type plus one per unit in the first {@link #ATTACK_GROUP_LIMIT} entries.
     */
    private final short @NotNull [] attackTypes;
    /**
     * The attack staging region plus one.
     */
    private int attackRegion;
    /**
     * The clock when the attack preparation started.
     */
    private int attackFrame;
    /**
     * The tracked Medic.
     */
    private @Nullable Unit medic;
    /**
     * The rectangle of the script opcode {@code set_gencmd}, read by {@code make_patrol}.
     */
    private final int @NotNull [] generalCommandRectangle;

    /**
     * The nuke interval in minutes written by the script opcode {@code nuke_rate}.
     */
    private int nukeRate;
    /**
     * The clock of the last computer nuke.
     */
    private int lastNukeClock;

    private int placementCountdown;
    private int strengthAttackCountdown;
    private int spellCountdown;
    private int attackSearchCountdown;

    /**
     * Creates a {@link ComputerPlayer} instance with the owner's current resources as budgets and the default difficulty
     * and region strength scale.
     *
     * @param owner    the player
     * @param minerals the player's minerals
     * @param gas      the player's gas
     */
    ComputerPlayer(int owner, int minerals, int gas) {
        this.owner = owner;

        this.flags        = 0;
        this.panicAddress = 0;
        this.difficulty   = DEFAULT_DIFFICULTY;

        this.reservedMinerals = 0;
        this.reservedGas      = 0;
        this.reservedSupply   = 0;
        this.mineralBudget    = minerals;
        this.gasBudget        = gas;
        this.supplyBudget     = 0;

        this.requestPriorities    = new byte[REQUEST_CAPACITY];
        this.requestKinds         = new byte[REQUEST_CAPACITY];
        this.requestTypes         = new short[REQUEST_CAPACITY];
        this.requestTowns         = new ComputerTown[REQUEST_CAPACITY];
        this.requestRegions       = new int[REQUEST_CAPACITY];
        this.requestGuards        = new ComputerUnitRecord[REQUEST_CAPACITY];
        this.requestCount         = 0;
        this.pendingUnitRequest   = 0;
        this.lastBuildingHitClock = 0;

        this.defenseLists        = new short[DEFENSE_LIST_COUNT][DEFENSE_LIST_CAPACITY];
        this.defenseCountdown    = 0;
        this.maximumCounts       = new byte[UnitsDat.UNIT_COUNT];
        this.strengthLimit       = 0;
        this.regionStrengthScale = DEFAULT_REGION_STRENGTH_SCALE;

        this.attackPreparation       = 0;
        this.attackTypes             = new short[ATTACK_GROUP_SIZE];
        this.attackRegion            = 0;
        this.attackFrame             = 0;
        this.medic                   = null;
        this.generalCommandRectangle = new int[GENERAL_COMMAND_RECTANGLE_SIZE];

        this.nukeRate      = 0;
        this.lastNukeClock = 0;

        this.placementCountdown      = 0;
        this.strengthAttackCountdown = 0;
        this.spellCountdown          = 0;
        this.attackSearchCountdown   = 0;
    }

    /**
     * @return the player
     */
    int getOwner() {
        return this.owner;
    }

    /**
     * @return the {@code FLAG_*} bits
     */
    int getFlags() {
        return this.flags;
    }

    /**
     * Sets the flags, truncated to a WORD.
     *
     * @param flags the {@code FLAG_*} bits
     */
    void setFlags(int flags) {
        this.flags = flags & WORD_MASK;
    }

    /**
     * @return {@code true} if {@link #FLAG_CAMPAIGN} is set, {@code false} otherwise
     */
    boolean isCampaign() {
        return (this.flags & FLAG_CAMPAIGN) != 0;
    }

    /**
     * @return the script address the {@link ComputerScriptOpcodes#PANIC} opcode stored
     */
    int getPanicAddress() {
        return this.panicAddress;
    }

    /**
     * Sets the script address of the {@link ComputerScriptOpcodes#PANIC} opcode, truncated to a WORD.
     *
     * @param address the script address
     */
    void setPanicAddress(int address) {
        this.panicAddress = address & WORD_MASK;
    }

    /**
     * @return the strength limit, or {@code 0} without a limit
     */
    int getStrengthLimit() {
        return this.strengthLimit;
    }

    /**
     * Sets the strength limit, truncated to a WORD.
     *
     * @param limit the script operand; {@code 0} removes the limit
     */
    void setStrengthLimit(int limit) {
        this.strengthLimit = limit & WORD_MASK;
    }

    /**
     * @return the region strength scale in percent
     */
    int getRegionStrengthScale() {
        return this.regionStrengthScale;
    }

    /**
     * Sets the region strength scale of the script opcode {@code default_min}, truncated to a BYTE.
     *
     * @param scale the scale in percent
     */
    void setRegionStrengthScale(int scale) {
        this.regionStrengthScale = scale & BYTE_MASK;
    }

    /**
     * @return the defense countdown
     */
    int getDefenseCountdown() {
        return this.defenseCountdown;
    }

    /**
     * Sets the defense countdown, truncated to a BYTE.
     *
     * @param countdown the countdown
     */
    void setDefenseCountdown(int countdown) {
        this.defenseCountdown = countdown & BYTE_MASK;
    }

    /**
     * @return {@code true} if an attack is prepared and {@link #FLAG_ATTACK_READY} is set, {@code false} otherwise
     */
    boolean isAttackPrepared() {
        return this.attackPreparation != 0 && (this.flags & FLAG_ATTACK_READY) != 0;
    }

    /**
     * @return the kind of the highest request, or {@link #REQUEST_KIND_NONE} without requests
     */
    int getActiveRequestKind() {
        return this.requestCount != 0 ? Byte.toUnsignedInt(this.requestKinds[0]) : REQUEST_KIND_NONE;
    }

    /**
     * @return the type of the highest request entry
     */
    int getActiveRequestType() {
        return Short.toUnsignedInt(this.requestTypes[0]);
    }

    /**
     * @return the town of the highest request entry
     */
    @Nullable ComputerTown getActiveRequestTown() {
        return this.requestTowns[0];
    }

    /**
     * @return the region record index of the highest request entry
     */
    int getActiveRequestRegion() {
        return this.requestRegions[0];
    }

    /**
     * @return the guard record of the highest request entry
     */
    @Nullable ComputerUnitRecord getActiveRequestGuard() {
        return this.requestGuards[0];
    }

    /**
     * Returns the guard record of the highest request entry, which a guard request ({@link #REQUEST_KIND_GUARD}) always has.
     *
     * @return the guard record of the highest request entry
     * @throws IllegalStateException if the highest request entry has no guard record
     */
    @NotNull ComputerUnitRecord requireActiveRequestGuard() {
        ComputerUnitRecord guard = this.requestGuards[0];

        if (guard == null) {
            throw Exceptions.newIllegalStateException("The active computer request of player {0} has no guard record.", this.owner);
        }

        return guard;
    }

    /**
     * @return the number of queued requests
     */
    int getRequestCount() {
        return this.requestCount;
    }

    /**
     * Sets the pending unit request to the type plus one, truncated to a WORD.
     *
     * @param unitType the requested unit type
     */
    void setPendingUnitRequest(int unitType) {
        this.pendingUnitRequest = unitType + 1 & WORD_MASK;
    }

    /**
     * Sets or clears {@link #FLAG_OUTNUMBERING}, which marks an owner stronger than every enemy side and suppresses its
     * defense requests.
     *
     * @param outnumbering whether the owner outnumbers every enemy side
     */
    void setOutnumbering(boolean outnumbering) {
        this.flags = outnumbering ? this.flags | FLAG_OUTNUMBERING : this.flags & ~FLAG_OUTNUMBERING;
    }

    /**
     * Sets or clears {@link #FLAG_CREEP_SPACING} for the {@link ComputerScriptOpcodes#CREEP} opcode: spaced Creep Colonies
     * follow placement patterns instead of the defended choke.
     *
     * @param spaced whether Creep Colonies are spaced by patterns
     */
    void setCreepSpacing(boolean spaced) {
        this.flags = spaced ? this.flags | FLAG_CREEP_SPACING : this.flags & ~FLAG_CREEP_SPACING;
    }

    /**
     * @return the minerals reserved by queued requests
     */
    int getReservedMinerals() {
        return this.reservedMinerals;
    }

    /**
     * @return the gas reserved by queued requests
     */
    int getReservedGas() {
        return this.reservedGas;
    }

    /**
     * @return the mineral budget of the current update
     */
    int getMineralBudget() {
        return this.mineralBudget;
    }

    /**
     * @return the gas budget of the current update
     */
    int getGasBudget() {
        return this.gasBudget;
    }

    /**
     * @return the supply budget of the current update
     */
    int getSupplyBudget() {
        return this.supplyBudget;
    }

    /**
     * Sets the supply budget of the current update.
     *
     * @param supply the supply budget
     */
    void setSupplyBudget(int supply) {
        this.supplyBudget = supply;
    }

    /**
     * @param unitType the unit type below {@link ScrUnitIds#NONE}
     * @return the maximum count of the {@link ComputerScriptOpcodes#DEFINE_MAX} opcode, or {@code 0} without a maximum
     */
    int getMaximumCount(int unitType) {
        return Byte.toUnsignedInt(this.maximumCounts[unitType]);
    }

    /**
     * Sets the maximum count of a unit type; a type outside the unit types is ignored.
     *
     * @param unitType the unit type
     * @param maximum  the maximum count BYTE
     */
    void setMaximumCount(int unitType, int maximum) {
        if (unitType < ScrUnitIds.NONE) {
            this.maximumCounts[unitType] = (byte) maximum;
        }
    }

    /**
     * @param list the defense list in script opcode order ({@code DEFENSE_*})
     * @return the retained list of unit types plus one, terminated by zero
     */
    short @NotNull [] getDefenseList(int list) {
        return this.defenseLists[list];
    }

    /**
     * @return the difficulty
     */
    int getDifficulty() {
        return this.difficulty;
    }

    /**
     * Sets the prepared attack count of the script opcode {@code set_attacks}, truncated to a BYTE.
     *
     * @param value the script operand
     */
    void setAttackPreparation(int value) {
        this.attackPreparation = value & BYTE_MASK;
    }

    /**
     * Sets the rectangle of the script opcode {@code set_gencmd}.
     *
     * @param left   the left coordinate
     * @param top    the top coordinate
     * @param right  the right coordinate
     * @param bottom the bottom coordinate
     */
    void setGeneralCommandRectangle(int left, int top, int right, int bottom) {
        this.generalCommandRectangle[GENERAL_COMMAND_LEFT]   = left;
        this.generalCommandRectangle[GENERAL_COMMAND_TOP]    = top;
        this.generalCommandRectangle[GENERAL_COMMAND_RIGHT]  = right;
        this.generalCommandRectangle[GENERAL_COMMAND_BOTTOM] = bottom;
    }

    /**
     * @return a copy of the general command rectangle: left, top, right and bottom
     */
    int @NotNull [] getGeneralCommandRectangle() {
        return this.generalCommandRectangle.clone();
    }

    /**
     * @return the spell countdown; a computer caster casts no spell before it reaches zero unless forced
     */
    int getSpellCountdown() {
        return this.spellCountdown;
    }

    /**
     * Sets the spell countdown after a computer caster cast a spell, truncated to a BYTE.
     *
     * @param countdown the countdown
     */
    public void setSpellCountdown(int countdown) {
        this.spellCountdown = countdown & BYTE_MASK;
    }

    /**
     * @return the remaining player-update countdown blocking a strength attack
     */
    int getStrengthAttackCountdown() {
        return this.strengthAttackCountdown;
    }

    /**
     * Sets the strength-attack countdown, truncated to a BYTE.
     *
     * @param countdown the countdown
     */
    void setStrengthAttackCountdown(int countdown) {
        this.strengthAttackCountdown = countdown & BYTE_MASK;
    }

    /**
     * @return {@code true} if a failed placement search blocks placement searches, {@code false} otherwise
     */
    boolean isPlacementBlocked() {
        return this.placementCountdown != 0;
    }

    /**
     * @return {@code true} if a search without a staging region blocks attack preparation, {@code false} otherwise
     */
    boolean isAttackSearchBlocked() {
        return this.attackSearchCountdown != 0;
    }

    /**
     * Sets the clock of the last hit on a building of the owner, delaying other unit requests.
     *
     * @param clock the clock
     */
    void setLastBuildingHitClock(int clock) {
        this.lastBuildingHitClock = clock;
    }

    /**
     * Tests whether the request delay after the last hit on a building of the owner has elapsed. Until then, guard requests and
     * town unit requests other than refineries and urgent ones wait, and a gathering attack requests its missing units without
     * urgency.
     *
     * @param clock the current clock
     * @return {@code true} if no building-hit clock is stored or {@link #BUILDING_HIT_REQUEST_DELAY} clock ticks passed (unsigned), {@code false} otherwise
     */
    boolean isBuildingHitRequestDelayElapsed(int clock) {
        return this.lastBuildingHitClock == 0 || Integer.compareUnsigned(clock - this.lastBuildingHitClock, BUILDING_HIT_REQUEST_DELAY) >= 0;
    }

    /**
     * @return the tracked Medic, or {@code null}
     */
    @Nullable Unit getMedic() {
        return this.medic;
    }

    /**
     * Sets the tracked Medic.
     *
     * @param medic the Medic, or {@code null}
     */
    void setMedic(@Nullable Unit medic) {
        this.medic = medic;
    }

    /**
     * Sets the nuke interval of the script opcode {@code nuke_rate}, truncated to a BYTE.
     *
     * @param minutes the interval in minutes
     */
    void setNukeRate(int minutes) {
        this.nukeRate = minutes & BYTE_MASK;
    }

    /**
     * Sets the clock of the last computer nuke; the script opcode {@code fake_nuke} passes the clock plus one.
     *
     * @param clock the clock
     */
    void setLastNukeClock(int clock) {
        this.lastNukeClock = clock;
    }

    /**
     * @param clock the current clock
     * @return {@code true} if the nuke interval passed since the last nuke (unsigned DWORD compare), {@code false} otherwise
     */
    boolean isNukeReady(int clock) {
        return Integer.compareUnsigned(this.nukeRate * ComputerScripts.CLOCK_TICKS_PER_MINUTE + this.lastNukeClock, clock) <= 0;
    }

    /**
     * Sets or clears {@link #FLAG_TRANSPORTS_OFF}.
     *
     * @param enabled {@code false} for the {@link ComputerScriptOpcodes#TRANSPORTS_OFF} opcode, {@code true} for {@link ComputerScriptOpcodes#CHECK_TRANSPORTS}
     */
    void setTransportsEnabled(boolean enabled) {
        this.flags = enabled ? this.flags & ~FLAG_TRANSPORTS_OFF : this.flags | FLAG_TRANSPORTS_OFF;
    }

    /**
     * Sets or clears {@link #FLAG_FARMS_WITHOUT_TIMING}.
     *
     * @param timing {@code false} for the {@link ComputerScriptOpcodes#FARMS_NOTIMING} opcode, {@code true} for {@link ComputerScriptOpcodes#FARMS_TIMING}
     */
    void setFarmTiming(boolean timing) {
        this.flags = timing ? this.flags & ~FLAG_FARMS_WITHOUT_TIMING : this.flags | FLAG_FARMS_WITHOUT_TIMING;
    }

    /**
     * @return the live attack group entries
     */
    short @NotNull [] getAttackTypes() {
        return this.attackTypes;
    }

    /**
     * @return the staging region plus one, or {@code 0}
     */
    int getAttackRegion() {
        return this.attackRegion;
    }

    /**
     * Sets the staging region and starts the preparation clock when forced or unset.
     *
     * @param region     the staging region
     * @param resetFrame whether the preparation clock restarts
     * @param clock      the current clock
     */
    void setAttackRegion(int region, boolean resetFrame, int clock) {
        if (resetFrame || this.attackFrame == 0) {
            this.attackFrame = clock;
        }

        this.attackRegion = region + 1 & WORD_MASK;
    }

    /**
     * Tests whether the attack preparation expired: {@link #ATTACK_EXPIRY} clock ticks after it started, or
     * {@link #CAMPAIGN_ATTACK_EXPIRY} with {@link #FLAG_CAMPAIGN}. An expired preparation also clears its start.
     *
     * @param clock the current clock
     * @return {@code true} if the preparation expired, {@code false} otherwise
     */
    boolean isAttackExpired(int clock) {
        if (this.attackFrame == 0) {
            return false;
        }

        int limit = (this.flags & FLAG_CAMPAIGN) != 0 ? CAMPAIGN_ATTACK_EXPIRY : ATTACK_EXPIRY;
        boolean expired = Integer.compareUnsigned(limit, clock - this.attackFrame) <= 0;

        if (expired) {
            this.attackFrame = 0;
        }

        return expired;
    }

    /**
     * Sets the preparation start so that it expires {@code delay} clock ticks ({@link #DEFAULT_ATTACK_DELAY} for zero)
     * after the {@link #CAMPAIGN_ATTACK_EXPIRY} limit; a zero delay changes only an unset start.
     *
     * @param delay the delay in clock ticks, or {@code 0}
     * @param clock the current clock
     */
    void setAttackDelay(int delay, int clock) {
        if (delay != 0 || this.attackFrame == 0) {
            this.attackFrame = (delay != 0 ? delay : DEFAULT_ATTACK_DELAY) + clock - CAMPAIGN_ATTACK_EXPIRY;
        }
    }


    /**
     * Consumes the pending unit request.
     *
     * @return the requested unit type, or {@code -1} without a pending request
     */
    int takePendingUnitRequest() {
        if (this.pendingUnitRequest == 0) {
            return -1;
        }

        int value = this.pendingUnitRequest - 1;

        this.pendingUnitRequest = 0;

        return value;
    }

    /**
     * Raises the mineral and gas budgets at once by returned resources.
     *
     * @param minerals the returned minerals
     * @param gas      the returned gas
     */
    void creditBudgets(int minerals, int gas) {
        this.mineralBudget += minerals;
        this.gasBudget += gas;
    }

    /**
     * Subtracts costs from the mineral and gas budgets.
     *
     * @param minerals the mineral cost
     * @param gas      the gas cost
     */
    void chargeBudgets(int minerals, int gas) {
        this.mineralBudget -= minerals;
        this.gasBudget -= gas;
    }

    /**
     * Marks the player as having started a town and resets the difficulty to the default.
     */
    void markTownStarted() {
        this.flags |= FLAG_TOWN_STARTED;
        this.difficulty = DEFAULT_DIFFICULTY;
    }

    /**
     * Clears {@link #FLAG_ATTACK_READY} and consumes one prepared attack.
     */
    void consumeAttackPreparation() {
        this.flags &= ~FLAG_ATTACK_READY;

        if (this.attackPreparation != 0) {
            this.attackPreparation--;
        }
    }

    /**
     * Blocks further placement searches after a failed one until the next player update.
     */
    void blockPlacement() {
        this.placementCountdown = 1;
    }

    /**
     * Blocks attack preparation after a search without a staging region until the next player update.
     */
    void blockAttackSearch() {
        this.attackSearchCountdown = 1;
    }

    /**
     * Resets the player before the budgets are recomputed in its update: counts the countdowns down, marks the update,
     * clears the reservations and the request heap and takes the current resources as budgets.
     *
     * @param minerals the current minerals
     * @param gas      the current gas
     */
    void beginUpdate(int minerals, int gas) {
        if (this.placementCountdown != 0) {
            this.placementCountdown--;
        }

        if (this.strengthAttackCountdown != 0) {
            this.strengthAttackCountdown--;
        }

        if (this.spellCountdown != 0) {
            this.spellCountdown--;
        }

        if (this.attackSearchCountdown != 0) {
            this.attackSearchCountdown--;
        }

        this.flags |= FLAG_UPDATED;
        this.reservedMinerals = 0;
        this.reservedGas      = 0;
        this.reservedSupply   = 0;
        this.requestCount     = 0;
        this.mineralBudget    = minerals;
        this.gasBudget        = gas;
    }

    /**
     * Adds a request's costs to the reserved totals.
     *
     * @param minerals the mineral cost
     * @param gas      the gas cost
     * @param supply   the supply BYTE
     */
    void reserve(int minerals, int gas, int supply) {
        this.reservedMinerals += minerals;
        this.reservedGas += gas;
        this.reservedSupply += supply & BYTE_MASK;
    }

    /**
     * Inserts a request without a guard target into the heap.
     *
     * @param priority the priority BYTE
     * @param kind     the request kind BYTE
     * @param type     the requested type WORD
     * @param town     the requesting town, or {@code null}
     * @param region   the requesting region, or {@code -1}
     */
    void pushRequest(int priority, int kind, int type, @Nullable ComputerTown town, int region) {
        this.pushRequest(priority, kind, type, town, region, null);
    }

    /**
     * Inserts a request into the heap; a full heap overwrites its last entry.
     *
     * @param priority the priority BYTE
     * @param kind     the request kind BYTE
     * @param type     the requested type WORD
     * @param town     the requesting town, or {@code null}
     * @param region   the requesting region, or {@code -1}
     * @param guard    the guard record of a {@link #REQUEST_KIND_GUARD} request, or {@code null}
     */
    void pushRequest(int priority, int kind, int type, @Nullable ComputerTown town, int region, @Nullable ComputerUnitRecord guard) {
        int slot;

        if (this.requestCount == REQUEST_CAPACITY) {
            slot = REQUEST_CAPACITY - 1;
        } else {
            slot = this.requestCount;
            this.requestCount++;
        }

        this.requestPriorities[slot] = (byte) priority;
        this.requestKinds[slot]      = (byte) kind;
        this.requestTypes[slot]      = (short) type;
        this.requestTowns[slot]      = town;
        this.requestRegions[slot]    = region;
        this.requestGuards[slot]     = guard;

        while (slot != 0) {
            int parent = (slot - 1) / REQUEST_HEAP_CHILD_COUNT;

            if (Byte.toUnsignedInt(this.requestPriorities[slot]) <= Byte.toUnsignedInt(this.requestPriorities[parent])) {
                return;
            }

            this.swapRequests(slot, parent);

            slot = parent;
        }
    }

    /**
     * Releases the highest request's reservation and removes it from the heap; on success the budgets are also charged.
     *
     * @param success whether the budgets are also charged
     * @param mineral the request's mineral cost
     * @param gas     the request's gas cost
     * @param supply  the request's supply BYTE
     * @param exempt  whether the request type bypasses the reservation update
     */
    void popRequest(boolean success, int mineral, int gas, int supply, boolean exempt) {
        if (!exempt) {
            this.reservedMinerals = Integer.compareUnsigned(this.reservedMinerals, mineral) < 0 ? 0 : this.reservedMinerals - mineral;
            this.reservedGas      = Integer.compareUnsigned(this.reservedGas, gas) < 0 ? 0 : this.reservedGas - gas;
            this.reservedSupply   = Integer.compareUnsigned(this.reservedSupply, supply & BYTE_MASK) < 0
                                    ? 0
                                    : this.reservedSupply - (supply & BYTE_MASK);

            if (success) {
                this.mineralBudget -= mineral;
                this.gasBudget -= gas;
                this.supplyBudget -= supply & BYTE_MASK;
            }
        }

        int count = (this.requestCount - 1) & BYTE_MASK;

        this.requestCount = count;

        if (count == 0) {
            return;
        }

        this.copyRequest(count, 0);

        if (count == 1) {
            return;
        }

        int parent = 0;
        int child = 1;

        while (child <= count - 1) {
            int selected = child + 1;

            if (count <= selected || Byte.toUnsignedInt(this.requestPriorities[selected]) < Byte.toUnsignedInt(this.requestPriorities[child])) {
                selected = child;
            }

            if (Byte.toUnsignedInt(this.requestPriorities[selected]) <= Byte.toUnsignedInt(this.requestPriorities[parent])) {
                return;
            }

            this.swapRequests(selected, parent);

            parent = selected;
            child  = selected * REQUEST_HEAP_CHILD_COUNT + 1;
        }
    }

    private void copyRequest(int source, int destination) {
        this.requestPriorities[destination] = this.requestPriorities[source];
        this.requestKinds[destination]      = this.requestKinds[source];
        this.requestTypes[destination]      = this.requestTypes[source];
        this.requestTowns[destination]      = this.requestTowns[source];
        this.requestRegions[destination]    = this.requestRegions[source];
        this.requestGuards[destination]     = this.requestGuards[source];
    }

    private void swapRequests(int first, int second) {
        byte priority = this.requestPriorities[first];
        byte kind = this.requestKinds[first];
        short type = this.requestTypes[first];
        ComputerTown town = this.requestTowns[first];
        int region = this.requestRegions[first];
        ComputerUnitRecord guard = this.requestGuards[first];

        this.copyRequest(second, first);

        this.requestPriorities[second] = priority;
        this.requestKinds[second]      = kind;
        this.requestTypes[second]      = type;
        this.requestTowns[second]      = town;
        this.requestRegions[second]    = region;
        this.requestGuards[second]     = guard;
    }

    /**
     * Counts the defense countdown down once per defense update and clears {@link #FLAG_DEFENSE_UPDATED}.
     */
    void finishDefenseUpdate() {
        if ((this.flags & FLAG_DEFENSE_UPDATED) == 0) {
            return;
        }

        if (this.defenseCountdown != 0) {
            this.defenseCountdown--;
        }

        this.flags &= ~FLAG_DEFENSE_UPDATED;
    }

    /**
     * Appends a unit type to a defense list for the defense list opcodes {@link ComputerScriptOpcodes#DEFENSEBUILD_GG}
     * to {@link ComputerScriptOpcodes#DEFENSEUSE_AA}, within the list capacity.
     *
     * @param list     the list in opcode order
     * @param count    the number of entries, at most {@code 10}
     * @param unitType the unit type below {@link ScrUnitIds#NONE}
     */
    void addDefenseUnits(int list, int count, int unitType) {
        if (unitType >= ScrUnitIds.NONE || count > DEFENSE_ADD_LIMIT) {
            return;
        }

        short[] entries = this.defenseLists[list];
        int index = 0;

        while (entries[index] != 0) {
            if (index >= DEFENSE_LIST_CAPACITY) {
                return;
            }

            index++;

            if (index == DEFENSE_LIST_CAPACITY) {
                return;
            }
        }

        for (int remaining = count; remaining != 0 && index < DEFENSE_LIST_CAPACITY; remaining--) {
            entries[index++] = (short) (unitType + 1);
        }
    }

    /**
     * Clears the build and use lists of one defense kind.
     *
     * @param kind the kind in opcode order ({@link #DEFENSE_BUILD_GROUND_GROUND} to {@link #DEFENSE_BUILD_AIR_AIR})
     */
    void clearDefenseUnits(int kind) {
        Arrays.fill(this.defenseLists[kind], (short) 0);
        Arrays.fill(this.defenseLists[kind + DEFENSE_USE_GROUND_GROUND], (short) 0);
    }

    /**
     * Clears the attack group and the staging region, and the preparation clock when requested; the region states are
     * not reset here.
     *
     * @param resetFrame whether the preparation clock is cleared
     */
    void clearAttack(boolean resetFrame) {
        Arrays.fill(this.attackTypes, (short) 0);

        this.attackRegion = 0;

        if (resetFrame) {
            this.attackFrame = 0;
        }
    }

    /**
     * Appends the type {@code count} times after the used entries of the attack group, within the first
     * {@link #ATTACK_GROUP_LIMIT}.
     *
     * @param count    the number of units
     * @param unitType the unit type
     */
    void addAttackUnits(int count, int unitType) {
        if (Integer.compareUnsigned(count, ATTACK_GROUP_LIMIT) >= 0 || Integer.compareUnsigned(unitType, ScrUnitIds.NONE) >= 0) {
            return;
        }

        int index = 0;

        while (this.attackTypes[index] != 0) {
            if (index > ATTACK_GROUP_LIMIT - 1) {
                return;
            }

            index++;
        }

        for (; count != 0 && index < ATTACK_GROUP_LIMIT; index++, count--) {
            this.attackTypes[index] = (short) (unitType + 1);
        }
    }

    /**
     * Marks the first attack group entry of the type as lost; a Siege Tank in Siege Mode counts as Tank Mode.
     *
     * @param unitType the lost unit's type
     */
    void releaseAttackUnit(int unitType) {
        int type = unitType == ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE ? ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE : unitType;

        for (int index = 0; index < ATTACK_GROUP_LIMIT; index++) {
            if (Short.toUnsignedInt(this.attackTypes[index]) == type + 1) {
                this.attackTypes[index] = (short) LOST_ATTACK_ENTRY;

                return;
            }
        }
    }

    /**
     * Writes the saved player fields, clearing request targets and converting the Medic to a unit reference. Each request
     * writes priority, kind and type and a zero target DWORD. The layout then stores the request count, countdowns, nuke
     * state, flags, panic address, strength limit, attack region, pending unit request, strength scale, defense countdown,
     * building hit clock, attack clock and difficulty, then the attack group, the defense lists, the maximum counts, the
     * Medic and the general command rectangle.
     *
     * @param data       the section, little-endian
     * @param offset     the record offset
     * @param references the unit references of the saved game
     */
    void writeSaveRecord(@NotNull ByteBuffer data, int offset, @NotNull SaveReferences references) {
        data.putInt(offset + SAVED_RESERVED_MINERALS, this.reservedMinerals);
        data.putInt(offset + SAVED_RESERVED_GAS, this.reservedGas);
        data.putInt(offset + SAVED_RESERVED_SUPPLY, this.reservedSupply);
        data.putInt(offset + SAVED_MINERAL_BUDGET, this.mineralBudget);
        data.putInt(offset + SAVED_GAS_BUDGET, this.gasBudget);
        data.putInt(offset + SAVED_SUPPLY_BUDGET, this.supplyBudget);

        for (int slot = 0; slot < REQUEST_CAPACITY; slot++) {
            int request = offset + SAVED_REQUESTS + slot * SAVED_REQUEST_SIZE;

            data.put(request, this.requestPriorities[slot]);
            data.put(request + SAVED_REQUEST_KIND_OFFSET, this.requestKinds[slot]);
            data.putShort(request + Short.BYTES, this.requestTypes[slot]);
            data.putInt(request + Integer.BYTES, 0);
        }

        data.put(offset + SAVED_REQUEST_COUNT, (byte) this.requestCount);
        data.put(offset + SAVED_PLACEMENT_COUNTDOWN, (byte) this.placementCountdown);
        data.put(offset + SAVED_NUKE_RATE, (byte) this.nukeRate);
        data.put(offset + SAVED_ATTACK_PREPARATION, (byte) this.attackPreparation);
        data.putInt(offset + SAVED_LAST_NUKE_CLOCK, this.lastNukeClock);
        data.putShort(offset + SAVED_FLAGS, (short) this.flags);
        data.putShort(offset + SAVED_PANIC_ADDRESS, (short) this.panicAddress);
        data.putShort(offset + SAVED_STRENGTH_LIMIT, (short) this.strengthLimit);
        data.putShort(offset + SAVED_ATTACK_REGION, (short) this.attackRegion);
        data.putShort(offset + SAVED_PENDING_UNIT_REQUEST, (short) this.pendingUnitRequest);
        data.put(offset + SAVED_REGION_STRENGTH_SCALE, (byte) this.regionStrengthScale);
        data.put(offset + SAVED_DEFENSE_COUNTDOWN, (byte) this.defenseCountdown);
        data.putInt(offset + SAVED_LAST_BUILDING_HIT_CLOCK, this.lastBuildingHitClock);
        data.putInt(offset + SAVED_ATTACK_FRAME, this.attackFrame);
        data.put(offset + SAVED_STRENGTH_ATTACK_COUNTDOWN, (byte) this.strengthAttackCountdown);
        data.put(offset + SAVED_SPELL_COUNTDOWN, (byte) this.spellCountdown);
        data.put(offset + SAVED_ATTACK_SEARCH_COUNTDOWN, (byte) this.attackSearchCountdown);
        data.put(offset + SAVED_DIFFICULTY, (byte) this.difficulty);

        for (int index = 0; index < this.attackTypes.length; index++) {
            data.putShort(offset + SAVED_ATTACK_TYPES + index * Short.BYTES, this.attackTypes[index]);
        }

        for (int list = 0; list < DEFENSE_LIST_COUNT; list++) {
            for (int index = 0; index < DEFENSE_LIST_CAPACITY; index++) {
                data.putShort(offset + SAVED_DEFENSE_LISTS + (list * DEFENSE_LIST_CAPACITY + index) * Short.BYTES, this.defenseLists[list][index]);
            }
        }

        data.put(offset + SAVED_MAXIMUM_COUNTS, this.maximumCounts);
        data.putInt(offset + SAVED_MEDIC, references.encodeUnit(this.medic));

        for (int index = 0; index < this.generalCommandRectangle.length; index++) {
            data.putInt(offset + SAVED_GENERAL_COMMAND_RECTANGLE + index * Integer.BYTES, this.generalCommandRectangle[index]);
        }
    }

    /**
     * Reads a saved player record and clears the request arrays and count instead of restoring queued requests.
     *
     * @param data       the section, little-endian
     * @param offset     the record offset
     * @param references the unit references of the restored game
     */
    void readSaveRecord(@NotNull ByteBuffer data, int offset, @NotNull SaveReferences references) {
        this.reservedMinerals = data.getInt(offset + SAVED_RESERVED_MINERALS);
        this.reservedGas      = data.getInt(offset + SAVED_RESERVED_GAS);
        this.reservedSupply   = data.getInt(offset + SAVED_RESERVED_SUPPLY);
        this.mineralBudget    = data.getInt(offset + SAVED_MINERAL_BUDGET);
        this.gasBudget        = data.getInt(offset + SAVED_GAS_BUDGET);
        this.supplyBudget     = data.getInt(offset + SAVED_SUPPLY_BUDGET);

        Arrays.fill(this.requestPriorities, (byte) 0);
        Arrays.fill(this.requestKinds, (byte) 0);
        Arrays.fill(this.requestTypes, (short) 0);
        Arrays.fill(this.requestTowns, null);
        Arrays.fill(this.requestRegions, 0);
        Arrays.fill(this.requestGuards, null);

        this.requestCount            = 0;
        this.placementCountdown      = data.get(offset + SAVED_PLACEMENT_COUNTDOWN) & BYTE_MASK;
        this.nukeRate                = data.get(offset + SAVED_NUKE_RATE) & BYTE_MASK;
        this.attackPreparation       = data.get(offset + SAVED_ATTACK_PREPARATION) & BYTE_MASK;
        this.lastNukeClock           = data.getInt(offset + SAVED_LAST_NUKE_CLOCK);
        this.flags                   = data.getShort(offset + SAVED_FLAGS) & WORD_MASK;
        this.panicAddress            = data.getShort(offset + SAVED_PANIC_ADDRESS) & WORD_MASK;
        this.strengthLimit           = data.getShort(offset + SAVED_STRENGTH_LIMIT) & WORD_MASK;
        this.attackRegion            = data.getShort(offset + SAVED_ATTACK_REGION) & WORD_MASK;
        this.pendingUnitRequest      = data.getShort(offset + SAVED_PENDING_UNIT_REQUEST) & WORD_MASK;
        this.regionStrengthScale     = data.get(offset + SAVED_REGION_STRENGTH_SCALE) & BYTE_MASK;
        this.defenseCountdown        = data.get(offset + SAVED_DEFENSE_COUNTDOWN) & BYTE_MASK;
        this.lastBuildingHitClock    = data.getInt(offset + SAVED_LAST_BUILDING_HIT_CLOCK);
        this.attackFrame             = data.getInt(offset + SAVED_ATTACK_FRAME);
        this.strengthAttackCountdown = data.get(offset + SAVED_STRENGTH_ATTACK_COUNTDOWN) & BYTE_MASK;
        this.spellCountdown          = data.get(offset + SAVED_SPELL_COUNTDOWN) & BYTE_MASK;
        this.attackSearchCountdown   = data.get(offset + SAVED_ATTACK_SEARCH_COUNTDOWN) & BYTE_MASK;
        this.difficulty              = data.get(offset + SAVED_DIFFICULTY) & BYTE_MASK;

        for (int index = 0; index < this.attackTypes.length; index++) {
            this.attackTypes[index] = data.getShort(offset + SAVED_ATTACK_TYPES + index * Short.BYTES);
        }

        for (int list = 0; list < DEFENSE_LIST_COUNT; list++) {
            for (int index = 0; index < DEFENSE_LIST_CAPACITY; index++) {
                this.defenseLists[list][index] = data.getShort(offset + SAVED_DEFENSE_LISTS + (list * DEFENSE_LIST_CAPACITY + index) * Short.BYTES);
            }
        }

        data.get(offset + SAVED_MAXIMUM_COUNTS, this.maximumCounts);

        this.medic = references.decodeUnit(data.getInt(offset + SAVED_MEDIC));

        for (int index = 0; index < this.generalCommandRectangle.length; index++) {
            this.generalCommandRectangle[index] = data.getInt(offset + SAVED_GENERAL_COMMAND_RECTANGLE + index * Integer.BYTES);
        }
    }

    /**
     * Mixes the player's state into a state hash.
     *
     * @param hash the hash so far
     * @return the updated hash
     */
    int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + this.reservedMinerals;
        hash = HASH_MULTIPLIER * hash + this.reservedGas;
        hash = HASH_MULTIPLIER * hash + this.reservedSupply;
        hash = HASH_MULTIPLIER * hash + this.mineralBudget;
        hash = HASH_MULTIPLIER * hash + this.gasBudget;
        hash = HASH_MULTIPLIER * hash + this.supplyBudget;
        hash = HASH_MULTIPLIER * hash + this.requestCount;

        for (int index = 0; index < this.requestCount; index++) {
            hash = HASH_MULTIPLIER * hash + Byte.toUnsignedInt(this.requestPriorities[index]);
            hash = HASH_MULTIPLIER * hash + Byte.toUnsignedInt(this.requestKinds[index]);
            hash = HASH_MULTIPLIER * hash + this.requestTypes[index];
            hash = HASH_MULTIPLIER * hash + (this.requestTowns[index] == null ? -1 : this.requestTowns[index].getPoolIndex());
            hash = HASH_MULTIPLIER * hash + this.requestRegions[index];
            hash = HASH_MULTIPLIER * hash + (this.requestGuards[index] == null ? -1 : this.requestGuards[index].getPoolIndex());
        }

        hash = HASH_MULTIPLIER * hash + this.placementCountdown;
        hash = HASH_MULTIPLIER * hash + this.attackPreparation;
        hash = HASH_MULTIPLIER * hash + this.flags;
        hash = HASH_MULTIPLIER * hash + this.panicAddress;
        hash = HASH_MULTIPLIER * hash + this.strengthLimit;
        hash = HASH_MULTIPLIER * hash + this.pendingUnitRequest;
        hash = HASH_MULTIPLIER * hash + this.regionStrengthScale;
        hash = HASH_MULTIPLIER * hash + this.defenseCountdown;
        hash = HASH_MULTIPLIER * hash + this.lastBuildingHitClock;
        hash = HASH_MULTIPLIER * hash + this.strengthAttackCountdown;
        hash = HASH_MULTIPLIER * hash + this.spellCountdown;
        hash = HASH_MULTIPLIER * hash + this.attackSearchCountdown;
        hash = HASH_MULTIPLIER * hash + this.difficulty;

        for (int value : this.generalCommandRectangle) {
            hash = HASH_MULTIPLIER * hash + value;
        }

        for (short entry : this.attackTypes) {
            hash = HASH_MULTIPLIER * hash + entry;
        }

        hash = HASH_MULTIPLIER * hash + this.attackRegion;
        hash = HASH_MULTIPLIER * hash + this.attackFrame;
        hash = HASH_MULTIPLIER * hash + (this.medic == null ? 0 : this.medic.getIndex());
        hash = HASH_MULTIPLIER * hash + this.nukeRate;
        hash = HASH_MULTIPLIER * hash + this.lastNukeClock;

        for (short[] list : this.defenseLists) {
            for (short entry : list) {
                hash = HASH_MULTIPLIER * hash + entry;
            }
        }

        for (byte maximum : this.maximumCounts) {
            hash = HASH_MULTIPLIER * hash + Byte.toUnsignedInt(maximum);
        }

        return hash;
    }
}
