package org.fogofwarlabs.sc4j.scr.game.production;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.requirement.RequirementAction;
import org.fogofwarlabs.sc4j.engine.content.requirement.RequirementResult;
import org.fogofwarlabs.sc4j.engine.content.requirement.RequirementSystem;
import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.scr.content.*;
import org.fogofwarlabs.sc4j.scr.data.type.RequirementIndexColumn;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.RequirementOpcodes;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTechnology;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerUnitAvailability;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerUpgrades;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameCheats;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.state.GameRulesVersion;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitTypeTraitsUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Interprets order, technology-use, research, upgrade and unit production requirements.
 * <p>
 * Scripts contain identifier entries terminated by {@code 0xFFFF}; the same word ends the script. Alternatives within a group are joined by
 * {@code 0xFF01}. Live DAT index columns locate the requirement words, including upgrade sections.
 * <p>
 * Tests return {@code 0} when accepted, a failure code otherwise, and may include {@link #DISABLED} for a visible disabled button. Production may
 * fail with {@link #FAILED_WITHOUT_CODE}. Game and interface callers read the result through {@link #isAccepted}, {@link #isDisabled} and
 * {@link #getFailureCode}.
 * <p>
 * This is the SC:R {@link RequirementSystem} of a game ({@link RequirementSystem#KEY}): the engine creates it with the game and it attaches to
 * the SC:R game system. {@link #check} runs the same native tests and returns their result as a shared {@link ScrRequirementResult}.
 */
public final class ScrRequirementSystem implements RequirementSystem {
    /**
     * The production result of a failure without a failure code: the add-on words {@code 0xFF04} and {@code 0xFF0C} after an {@code 0xFF02} type that
     * is not the producer's.
     */
    public static final int FAILED_WITHOUT_CODE = 0x100;
    /**
     * The result flag of a failure whose button remains visible and disabled.
     */
    public static final int DISABLED = 0x10000;

    /**
     * The index of the order script in {@link #copyScripts()}.
     */
    public static final int ORDERS = 0;
    /**
     * The index of the technology-use script in {@link #copyScripts()}.
     */
    public static final int TECHNOLOGY_USE = 1;
    /**
     * The index of the research script in {@link #copyScripts()}.
     */
    public static final int RESEARCH = 2;
    /**
     * The index of the upgrade script in {@link #copyScripts()}.
     */
    public static final int UPGRADES = 3;
    /**
     * The index of the unit production script in {@link #copyScripts()}.
     */
    public static final int BUILD = 4;

    /**
     * The identifier limits of the production, order, technology and upgrade tests.
     */
    private static final int BUILD_LIMIT = 0xE4;
    private static final int ORDER_LIMIT = 0xBD;
    private static final int TECHNOLOGY_LIMIT = 0x2C;
    private static final int UPGRADE_LIMIT = 0x3D;

    /**
     * The position of the first entry of a script (the word before it is not part of any entry).
     */
    private static final int FIRST_ENTRY_POSITION = 1;
    /**
     * The value of a DAT index column entry the index builder has not written yet.
     */
    private static final int UNINDEXED = 0xFFFF;
    /**
     * The production queue slot after the current one that a full queue occupies, counted from the current slot.
     */
    private static final int LAST_QUEUE_SLOT_OFFSET = Unit.BUILD_QUEUE_SLOTS - 1;
    /**
     * The upgrade levels whose requirements are the sections {@link RequirementOpcodes#LEVEL_ONE_SECTION} and
     * {@link RequirementOpcodes#LEVEL_TWO_SECTION}.
     */
    private static final int LEVEL_ONE = 1;
    private static final int LEVEL_TWO = 2;

    /**
     * The failure codes of the ownership, availability, room and add-on tests.
     */
    private static final int FAILURE_NOT_OWNER = 1;
    private static final int FAILURE_UNAVAILABLE = 2;
    private static final int FAILURE_FULL = 3;
    private static final int FAILURE_ADDON = 4;
    /**
     * The failure code of a busy unit, also used for the burrow state words.
     */
    private static final int FAILURE_BUSY = 5;
    /**
     * The failure codes of a landed unit that must be lifted and of a lifted one that must be landed.
     */
    private static final int FAILURE_NOT_LIFTED = 6;
    private static final int FAILURE_NOT_LANDED = 7;
    /**
     * The failure code of an unsatisfied requirement.
     */
    private static final int FAILURE_REQUIREMENT = 8;
    /**
     * The failure code of a technology the production requires but the owner has not researched.
     */
    private static final int FAILURE_NOT_RESEARCHED = 9;
    /**
     * The failure codes of a disabled and of a burrowed unit.
     */
    private static final int FAILURE_UNIT_DISABLED = 0xA;
    private static final int FAILURE_BURROWED = 0xB;
    /**
     * The failure codes of a research or upgrade in progress, a researched technology and the maximum upgrade level.
     */
    private static final int FAILURE_IN_PROGRESS = 0xC;
    private static final int FAILURE_RESEARCHED = 0xD;
    private static final int FAILURE_MAXIMUM_LEVEL = 0xE;
    /**
     * The failure codes of an order, unit type, technology and upgrade identifier outside the test's limit.
     */
    private static final int FAILURE_INVALID_ORDER = 0xF;
    private static final int FAILURE_INVALID_UNIT_TYPE = 0x10;
    private static final int FAILURE_INVALID_TECHNOLOGY = 0x11;
    private static final int FAILURE_INVALID_UPGRADE = 0x12;
    /**
     * The failure codes of a unit of the wrong kind and of an incomplete unit.
     */
    private static final int FAILURE_WRONG_KIND = 0x13;
    private static final int FAILURE_INCOMPLETE = 0x14;
    /**
     * The failure code of {@link RequirementOpcodes#DISABLED}.
     */
    private static final int FAILURE_DISABLED_WORD = 0x15;
    /**
     * The failure code of a hallucination without {@link RequirementOpcodes#HALLUCINATION_ALLOWED}.
     */
    private static final int FAILURE_HALLUCINATION = 0x16;
    /**
     * The failure code of an identifier without a script entry, and of {@link RequirementOpcodes#UNAVAILABLE}.
     */
    private static final int FAILURE_NO_SCRIPT = 0x17;
    /**
     * The failure code of a unit (or producer) that is not of an {@link RequirementOpcodes#CURRENT_UNIT} type.
     */
    private static final int FAILURE_WRONG_UNIT = 0x19;
    /**
     * The failure code of {@link RequirementOpcodes#EXPANSION} without the expansion.
     */
    private static final int FAILURE_EXPANSION = 0x1A;

    /**
     * The order requirement words.
     */
    private static final int @NotNull [] ORDER_SCRIPT = {
            0x0, 0x0, 0xFF1E, 0xFFFF, 0x1, 0xFF1E, 0xFFFF, 0x2, 0xFF12, 0xFF1E, 0xFFFF, 0x3, 0xFF12, 0xFF1E, 0xFFFF, 0x4,
            0xFF1A, 0xFF1E, 0xFFFF, 0x5, 0xFF02, 0x7D, 0xFFFF, 0x6, 0xFF14, 0xFF1E, 0xFFFF, 0x40, 0xFF02, 0x49, 0xFF1E, 0xFFFF,
            0x41, 0xFF02, 0x55, 0xFFFF, 0x14, 0xFF02, 0xD, 0xFFFF, 0x32, 0xFF02, 0x48, 0xFF01, 0xFF02, 0x52, 0xFF1E, 0xFFFF,
            0x3A, 0xFF02, 0x53, 0xFF01, 0xFF02, 0x51, 0xFF1E, 0xFFFF, 0x33, 0xFF02, 0x48, 0xFF01, 0xFF02, 0x52, 0xFF01, 0xFF02,
            0x53, 0xFF01, 0xFF02, 0x51, 0xFF1E, 0xFFFF, 0x34, 0xFF02, 0x48, 0xFF01, 0xFF02, 0x52, 0xFF1E, 0xFFFF, 0x7, 0xFF02,
            0x53, 0xFF01, 0xFF02, 0x51, 0xFF1E, 0xFFFF, 0x39, 0xFF02, 0x48, 0xFF01, 0xFF02, 0x52, 0xFF1E, 0xFFFF, 0x3E, 0xFF02,
            0x53, 0xFF01, 0xFF02, 0x51, 0xFF1E, 0xFFFF, 0x35, 0xFF02, 0x48, 0xFF01, 0xFF02, 0x52, 0xFF1E, 0xFFFF, 0x3B, 0xFF02,
            0x53, 0xFF01, 0xFF02, 0x51, 0xFF1E, 0xFFFF, 0x36, 0xFF02, 0x48, 0xFF01, 0xFF02, 0x52, 0xFF1E, 0xFFFF, 0x3C, 0xFF02,
            0x53, 0xFF01, 0xFF02, 0x51, 0xFF1E, 0xFFFF, 0x37, 0xFF02, 0x48, 0xFF01, 0xFF02, 0x52, 0xFF01, 0xFF02, 0x53, 0xFF01,
            0xFF02, 0x51, 0xFF1E, 0xFFFF, 0x38, 0xFF02, 0x48, 0xFF01, 0xFF02, 0x52, 0xFF1E, 0xFFFF, 0x3D, 0xFF02, 0x53, 0xFF01,
            0xFF02, 0x51, 0xFF1E, 0xFFFF, 0x8, 0xFF12, 0xFF1E, 0xFFFF, 0x9, 0xFF12, 0xFF1E, 0xFFFF, 0xA, 0xFF14, 0xFF1E, 0xFFFF,
            0xB, 0xFF12, 0xFF1E, 0xFFFF, 0xC, 0xFF12, 0xFF1E, 0xFFFF, 0xD, 0xFF14, 0xFF1E, 0xFFFF, 0xE, 0xFF14, 0xFF1E, 0xFFFF,
            0x9D, 0xFF14, 0xFF1E, 0xFFFF, 0x15, 0xFF14, 0xFF1E, 0xFFFF, 0x12, 0xFF02, 0x7C, 0xFF01, 0xFF02, 0x90, 0xFF01, 0xFF02,
            0x92, 0xFF01, 0xFF02, 0xA2, 0xFFFF, 0x13, 0xFF02, 0x7C, 0xFF01, 0xFF02, 0x90, 0xFF01, 0xFF02, 0x92, 0xFF01, 0xFF02,
            0xA2, 0xFF01, 0xFF02, 0xD1, 0xFF01, 0xFF02, 0xD2, 0xFF01, 0xFF02, 0xD3, 0xFF01, 0xFF02, 0xD4, 0xFF01, 0xFF02, 0xD5,
            0xFFFF, 0x16, 0xFF1A, 0xFF1E, 0xFFFF, 0x17, 0xFF1E, 0xFFFF, 0x19, 0xFF02, 0x29, 0xFFFF, 0x1A, 0xFF02, 0x29, 0xFFFF,
            0x46, 0xFF02, 0x29, 0xFFFF, 0x1E, 0xFF02, 0x7, 0xFFFF, 0x1F, 0xFF02, 0x40, 0xFFFF, 0x20, 0xFF02, 0x40, 0xFFFF,
            0x24, 0xFFFF, 0x27, 0xFF13, 0xFFFF, 0x28, 0xFF13, 0xFFFF, 0xF, 0xFF02, 0x6A, 0xFFFF, 0x2E, 0xFF02, 0x86, 0xFF0D,
            0xFFFF, 0x6C, 0xFF02, 0x2D, 0xFF01, 0xFF02, 0x31, 0xFFFF, 0x1B, 0xFF02, 0x2D, 0xFF01, 0xFF02, 0x31, 0xFFFF, 0x1C,
            0xFF02, 0x2D, 0xFF01, 0xFF02, 0x31, 0xFFFF, 0x1D, 0xFF02, 0x2D, 0xFF01, 0xFF02, 0x31, 0xFFFF, 0x21, 0xFF02, 0x7,
            0xFFFF, 0x22, 0xFF02, 0x7, 0xFFFF, 0x23, 0xFF02, 0x7, 0xFFFF, 0x29, 0xFF02, 0x24, 0xFF01, 0xFF02, 0x3B, 0xFFFF,
            0x31, 0xFF14, 0xFF1E, 0xFFFF, 0x45, 0xFF16, 0xFFFF, 0x47, 0xFF17, 0xFF06, 0xFFFF, 0x48, 0xFF17, 0xFF05, 0xFFFF, 0x4A,
            0xFF17, 0xFFFF, 0x9B, 0xFF02, 0xC5, 0xFF01, 0xFF02, 0xC6, 0xFF01, 0xFF02, 0xC7, 0xFFFF, 0x4D, 0xFF02, 0x23, 0xFF1E,
            0xFFFF, 0x4E, 0xFF02, 0x83, 0xFF01, 0xFF02, 0x84, 0xFF01, 0xFF02, 0x85, 0xFFFF, 0x4F, 0xFF16, 0xFFFF, 0x50, 0xFF16,
            0xFFFF, 0x51, 0xFF16, 0xFFFF, 0x52, 0xFF16, 0xFFFF, 0x54, 0xFF16, 0xFFFF, 0x55, 0xFF16, 0xFFFF, 0x56, 0xFF16, 0xFFFF,
            0x5A, 0xFF16, 0xFFFF, 0x5C, 0xFF1E, 0xFFFF, 0x6F, 0xFF18, 0xFFFF, 0x70, 0xFF18, 0xFFFF, 0x5E, 0xFF18, 0xFFFF, 0x5F,
            0xFF02, 0x7D, 0xFFFF, 0x5D, 0xFF18, 0xFF01, 0xFF02, 0x2A, 0xFFFF, 0x61, 0xFF19, 0xFFFF, 0x64, 0xFF1E, 0xFFFF, 0x65,
            0xFF02, 0x8F, 0xFF01, 0xFF02, 0x83, 0xFF01, 0xFF02, 0x84, 0xFF01, 0xFF02, 0x85, 0xFFFF, 0x67, 0xFF02, 0x90, 0xFF01,
            0xFF02, 0x92, 0xFFFF, 0x66, 0xFFFF, 0x68, 0xFF02, 0x2B, 0xFFFF, 0x6B, 0xFF1D, 0xFF1E, 0xFFFF, 0x98, 0xFF14, 0xFF1E,
            0xFFFF, 0x43, 0xFF02, 0xAC, 0xFFFF, 0x8C, 0xFF02, 0x21, 0xFFFF, 0x7B, 0xFF02, 0xE, 0xFFFF, 0x7D, 0xFF02, 0xE,
            0xFFFF, 0x7E, 0xFF02, 0x1, 0xFFFF, 0x7F, 0xFF02, 0xE, 0xFFFF, 0x80, 0xFF02, 0xE, 0xFFFF, 0x81, 0xFF02, 0xE,
            0xFFFF, 0x82, 0xFF02, 0x47, 0xFF01, 0xFF02, 0x56, 0xFF1E, 0xFFFF, 0x83, 0xFF02, 0x47, 0xFF01, 0xFF02, 0x56, 0xFFFF,
            0x85, 0xFF1E, 0xFFFF, 0x86, 0xFF02, 0x32, 0xFF1E, 0xFFFF, 0x87, 0xFF02, 0x32, 0xFF1E, 0xFFFF, 0x88, 0xFF02, 0x32,
            0xFF01, 0xFF02, 0x2F, 0xFF1E, 0xFFFF, 0xA5, 0xFF02, 0x55, 0xFFFF, 0xA6, 0xFF02, 0x59, 0xFF01, 0xFF02, 0x5A, 0xFF01,
            0xFF02, 0x5F, 0xFF01, 0xFF02, 0x5D, 0xFF01, 0xFF02, 0x5E, 0xFF01, 0xFF02, 0x60, 0xFF1E, 0xFFFF, 0xA7, 0xFF02, 0xD1,
            0xFF01, 0xFF02, 0xD2, 0xFF01, 0xFF02, 0xD3, 0xFF01, 0xFF02, 0xD4, 0xFF01, 0xFF02, 0xD5, 0xFFFF, 0xA8, 0xFF02, 0xCD,
            0xFF01, 0xFF02, 0xCE, 0xFF01, 0xFF02, 0xCF, 0xFF01, 0xFF02, 0xD0, 0xFFFF, 0xA9, 0xFF02, 0xCD, 0xFF01, 0xFF02, 0xCE,
            0xFF01, 0xFF02, 0xCF, 0xFF01, 0xFF02, 0xD0, 0xFFFF, 0xAA, 0xFF02, 0xCB, 0xFF01, 0xFF02, 0xD1, 0xFF01, 0xFF02, 0xD2,
            0xFF01, 0xFF02, 0xD3, 0xFF01, 0xFF02, 0xD4, 0xFF01, 0xFF02, 0xD5, 0xFFFF, 0xAB, 0xFF02, 0xCB, 0xFF01, 0xFF02, 0xD1,
            0xFF01, 0xFF02, 0xD2, 0xFF01, 0xFF02, 0xD3, 0xFF01, 0xFF02, 0xD4, 0xFF01, 0xFF02, 0xD5, 0xFFFF, 0xAF, 0xFF02, 0x22,
            0xFFFF, 0xB0, 0xFF02, 0x22, 0xFFFF, 0xB1, 0xFF02, 0x22, 0xFFFF, 0xB2, 0xFF02, 0x22, 0xFFFF, 0xB6, 0xFF02, 0x3F,
            0xFFFF, 0xFFFF
    };
    /**
     * The technology-use requirement words.
     */
    private static final int @NotNull [] TECHNOLOGY_SCRIPT = {
            0x0, 0x0, 0xFF1C, 0xFF01, 0xFF0F, 0xFF02, 0x0, 0xFF01, 0xFF02, 0x20, 0xFF01, 0xFF02, 0x14, 0xFF01, 0xFF02, 0xA,
            0xFFFF, 0x1, 0xFF1C, 0xFF01, 0xFF0F, 0xFF02, 0x1, 0xFF01, 0xFF02, 0x63, 0xFF01, 0xFF02, 0x68, 0xFF01, 0xFF02, 0x64,
            0xFF01, 0xFF02, 0x10, 0xFFFF, 0x3, 0xFF1C, 0xFF01, 0xFF0F, 0xFF1B, 0xFF02, 0x2, 0xFF01, 0xFF02, 0x13, 0xFFFF, 0x4,
            0xFF02, 0x6B, 0xFFFF, 0x5, 0xFF1C, 0xFF01, 0xFF0F, 0xFF02, 0x5, 0xFF01, 0xFF02, 0x1E, 0xFF01, 0xFF02, 0x17, 0xFF01,
            0xFF02, 0x19, 0xFFFF, 0x2, 0xFF1C, 0xFF01, 0xFF0F, 0xFF02, 0x9, 0xFF01, 0xFF02, 0x16, 0xFFFF, 0x6, 0xFF02, 0x9,
            0xFF01, 0xFF02, 0x16, 0xFFFF, 0x7, 0xFF1C, 0xFF01, 0xFF0F, 0xFF02, 0x9, 0xFF01, 0xFF02, 0x16, 0xFFFF, 0x8, 0xFF1C,
            0xFF01, 0xFF0F, 0xFF02, 0xC, 0xFF01, 0xFF02, 0x1C, 0xFF01, 0xFF02, 0x1D, 0xFF01, 0xFF02, 0x66, 0xFFFF, 0x9, 0xFF1C,
            0xFF01, 0xFF0F, 0xFF02, 0x8, 0xFF01, 0xFF02, 0x15, 0xFFFF, 0xA, 0xFF1C, 0xFF01, 0xFF0F, 0xFF02, 0x1, 0xFF01, 0xFF02,
            0x63, 0xFF01, 0xFF02, 0x68, 0xFF01, 0xFF02, 0x64, 0xFF01, 0xFF02, 0x10, 0xFF01, 0xFF02, 0x33, 0xFFFF, 0x18, 0xFF0F,
            0xFF02, 0x22, 0xFFFF, 0x22, 0xFF02, 0x22, 0xFFFF, 0xB, 0xFF1C, 0xFF01, 0xFF0F, 0xFF02, 0x67, 0xFF01, 0xFF02, 0x29,
            0xFF01, 0xFF02, 0x32, 0xFF01, 0xFF02, 0x2E, 0xFF01, 0xFF02, 0x25, 0xFF01, 0xFF02, 0x26, 0xFF01, 0xFF02, 0x34, 0xFF01,
            0xFF02, 0x36, 0xFF01, 0xFF02, 0x35, 0xFFFF, 0xC, 0xFF02, 0x2D, 0xFF01, 0xFF02, 0x31, 0xFFFF, 0xD, 0xFF1C, 0xFF01,
            0xFF0F, 0xFF02, 0x31, 0xFF01, 0xFF02, 0x2D, 0xFFFF, 0x12, 0xFF02, 0x31, 0xFF01, 0xFF02, 0x2D, 0xFFFF, 0xE, 0xFF02,
            0x34, 0xFF01, 0xFF02, 0x2E, 0xFF11, 0xFFFF, 0xF, 0xFF1C, 0xFF01, 0xFF0F, 0xFF02, 0x34, 0xFF01, 0xFF02, 0x2E, 0xFF11,
            0xFFFF, 0x10, 0xFF1C, 0xFF01, 0xFF0F, 0xFF02, 0x2E, 0xFF01, 0xFF02, 0x34, 0xFF01, 0xFF02, 0x68, 0xFF01, 0xFF02, 0x33,
            0xFF11, 0xFFFF, 0x11, 0xFF1C, 0xFF01, 0xFF0F, 0xFF02, 0x2D, 0xFF01, 0xFF02, 0x31, 0xFF01, 0xFF02, 0x33, 0xFF11, 0xFFFF,
            0x13, 0xFF1C, 0xFF01, 0xFF0F, 0xFF02, 0x43, 0xFF01, 0xFF02, 0x4F, 0xFF01, 0xFF02, 0x33, 0xFFFF, 0x14, 0xFF1C, 0xFF01,
            0xFF0F, 0xFF02, 0x43, 0xFF01, 0xFF02, 0x4F, 0xFFFF, 0x17, 0xFF02, 0x43, 0xFFFF, 0x15, 0xFF1C, 0xFF01, 0xFF0F, 0xFF02,
            0x56, 0xFF01, 0xFF02, 0x47, 0xFFFF, 0x16, 0xFF1C, 0xFF01, 0xFF0F, 0xFF02, 0x56, 0xFF01, 0xFF02, 0x47, 0xFFFF, 0x19,
            0xFF1C, 0xFF01, 0xFF0F, 0xFF02, 0x3C, 0xFF01, 0xFF02, 0x62, 0xFFFF, 0x1C, 0xFF24, 0xFF02, 0x3D, 0xFFFF, 0x1B, 0xFF0F,
            0xFF02, 0x3F, 0xFFFF, 0x1D, 0xFF02, 0x3F, 0xFFFF, 0x1E, 0xFF02, 0x22, 0xFF0F, 0xFFFF, 0x1F, 0xFF0F, 0xFF02, 0x3F,
            0xFFFF, 0x20, 0xFF24, 0xFF0F, 0xFF02, 0x26, 0xFFFF, 0xFFFF
    };
    /**
     * The research requirement words.
     */
    private static final int @NotNull [] RESEARCH_SCRIPT = {
            0x0, 0x0, 0xFF02, 0x70, 0xFF07, 0xFF05, 0xFFFF, 0x1, 0xFF02, 0x75, 0xFF07, 0xFF05, 0xFFFF, 0x3, 0xFF02, 0x78,
            0xFF07, 0xFF05, 0xFFFF, 0x5, 0xFF02, 0x78, 0xFF07, 0xFF05, 0xFFFF, 0x2, 0xFF02, 0x74, 0xFF07, 0xFF05, 0xFFFF, 0x7,
            0xFF02, 0x74, 0xFF07, 0xFF05, 0xFFFF, 0x8, 0xFF02, 0x76, 0xFF07, 0xFF05, 0xFFFF, 0x9, 0xFF02, 0x73, 0xFF07, 0xFF05,
            0xFFFF, 0xA, 0xFF02, 0x75, 0xFF07, 0xFF05, 0xFFFF, 0x18, 0xFF24, 0xFF02, 0x70, 0xFF07, 0xFF05, 0xFFFF, 0x1E, 0xFF24,
            0xFF02, 0x70, 0xFF07, 0xFF05, 0xFFFF, 0xB, 0xFF02, 0x83, 0xFF01, 0xFF02, 0x84, 0xFF01, 0xFF02, 0x85, 0xFF07, 0xFFFF,
            0xD, 0xFF02, 0x8A, 0xFF07, 0xFFFF, 0x12, 0xFF02, 0x8A, 0xFF07, 0xFFFF, 0xF, 0xFF02, 0x88, 0xFF07, 0xFFFF, 0x10,
            0xFF02, 0x88, 0xFF07, 0xFFFF, 0x11, 0xFF02, 0x8A, 0xFF07, 0xFFFF, 0x20, 0xFF24, 0xFF02, 0x87, 0x84, 0xFF01, 0x85,
            0xFF07, 0xFFFF, 0x13, 0xFF02, 0xA5, 0xFF07, 0xFFFF, 0x14, 0xFF02, 0xA5, 0xFF07, 0xFFFF, 0x15, 0xFF02, 0xAA, 0xFF07,
            0xFFFF, 0x16, 0xFF02, 0xAA, 0xFF07, 0xFFFF, 0x19, 0xFF24, 0xFF02, 0xA9, 0xFF07, 0xFFFF, 0x1B, 0xFF24, 0xFF02, 0xA5,
            0xFF07, 0xFFFF, 0x1D, 0xFF24, 0xFF02, 0xA5, 0xFF07, 0xFFFF, 0x1F, 0xFF24, 0xFF02, 0xA5, 0xFF07, 0xFFFF, 0xFFFF
    };
    /**
     * The upgrade requirement words.
     */
    private static final int @NotNull [] UPGRADE_SCRIPT = {
            0x0, 0x0, 0xFF02, 0x7A, 0xFF05, 0xFF07, 0xFF1F, 0xFFFF, 0xFF20, 0x74, 0xFFFF, 0xFF21, 0x74, 0xFFFF, 0x1, 0xFF02,
            0x7B, 0xFF05, 0xFF07, 0xFF1F, 0xFFFF, 0xFF20, 0x74, 0xFFFF, 0xFF21, 0x74, 0xFFFF, 0x2, 0xFF02, 0x7B, 0xFF05, 0xFF07,
            0xFF1F, 0xFFFF, 0xFF20, 0x74, 0xFFFF, 0xFF21, 0x74, 0xFFFF, 0x3, 0xFF02, 0x8B, 0xFF07, 0xFF1F, 0xFFFF, 0xFF20, 0x84,
            0xFF01, 0xFF03, 0x85, 0xFFFF, 0xFF21, 0x85, 0xFFFF, 0x4, 0xFF02, 0x8D, 0xFF01, 0xFF02, 0x89, 0xFF07, 0xFF1F, 0xFFFF,
            0xFF20, 0x84, 0xFF01, 0xFF03, 0x85, 0xFFFF, 0xFF21, 0x85, 0xFFFF, 0x5, 0xFF02, 0xA6, 0xFF07, 0xFF1F, 0xFFFF, 0xFF20,
            0xA5, 0xFFFF, 0xFF21, 0xA5, 0xFFFF, 0x6, 0xFF02, 0xA4, 0xFF07, 0xFF1F, 0xFFFF, 0xFF20, 0xA9, 0xFFFF, 0xFF21, 0xA9,
            0xFFFF, 0x7, 0xFF02, 0x7A, 0xFF05, 0xFF07, 0xFF1F, 0xFFFF, 0xFF20, 0x74, 0xFFFF, 0xFF21, 0x74, 0xFFFF, 0x8, 0xFF02,
            0x7B, 0xFF05, 0xFF07, 0xFF1F, 0xFFFF, 0xFF20, 0x74, 0xFFFF, 0xFF21, 0x74, 0xFFFF, 0x9, 0xFF02, 0x7B, 0xFF05, 0xFF07,
            0xFF1F, 0xFFFF, 0xFF20, 0x74, 0xFFFF, 0xFF21, 0x74, 0xFFFF, 0xA, 0xFF02, 0x8B, 0xFF07, 0xFF1F, 0xFFFF, 0xFF20, 0x84,
            0xFF01, 0xFF03, 0x85, 0xFFFF, 0xFF21, 0x85, 0xFFFF, 0xB, 0xFF02, 0x8B, 0xFF07, 0xFF1F, 0xFFFF, 0xFF20, 0x84, 0xFF01,
            0xFF03, 0x85, 0xFFFF, 0xFF21, 0x85, 0xFFFF, 0xC, 0xFF02, 0x8D, 0xFF01, 0xFF02, 0x89, 0xFF07, 0xFF1F, 0xFFFF, 0xFF20,
            0x84, 0xFF01, 0xFF03, 0x85, 0xFFFF, 0xFF21, 0x85, 0xFFFF, 0xD, 0xFF02, 0xA6, 0xFF07, 0xFF1F, 0xFFFF, 0xFF20, 0xA5,
            0xFFFF, 0xFF21, 0xA5, 0xFFFF, 0xE, 0xFF02, 0xA4, 0xFF07, 0xFF1F, 0xFFFF, 0xFF20, 0xA9, 0xFFFF, 0xFF21, 0xA9, 0xFFFF,
            0xF, 0xFF02, 0xA6, 0xFF07, 0xFF1F, 0xFFFF, 0xFF20, 0xA4, 0xFFFF, 0xFF21, 0xA4, 0xFFFF, 0x10, 0xFF02, 0x70, 0xFF05,
            0xFF07, 0xFFFF, 0x11, 0xFF02, 0x78, 0xFF05, 0xFF07, 0xFFFF, 0x13, 0xFF02, 0x74, 0xFF05, 0xFF07, 0xFFFF, 0x14, 0xFF02,
            0x75, 0xFF05, 0xFF07, 0xFFFF, 0x15, 0xFF02, 0x75, 0xFF05, 0xFF07, 0xFFFF, 0x16, 0xFF02, 0x73, 0xFF05, 0xFF07, 0xFFFF,
            0x17, 0xFF02, 0x76, 0xFF05, 0xFF07, 0xFFFF, 0x18, 0xFF02, 0x84, 0xFF01, 0xFF02, 0x85, 0xFF07, 0xFFFF, 0x19, 0xFF02,
            0x84, 0xFF01, 0xFF02, 0x85, 0xFF07, 0xFFFF, 0x1A, 0xFF02, 0x84, 0xFF01, 0xFF02, 0x85, 0xFF07, 0xFFFF, 0x1B, 0xFF02,
            0x8E, 0xFF07, 0xFFFF, 0x1C, 0xFF02, 0x8E, 0xFF07, 0x85, 0xFFFF, 0x1D, 0xFF02, 0x87, 0xFF07, 0xFFFF, 0x1E, 0xFF02,
            0x87, 0xFF07, 0xFFFF, 0x1F, 0xFF02, 0x8A, 0xFF07, 0xFFFF, 0x20, 0xFF02, 0x88, 0xFF07, 0xFFFF, 0x21, 0xFF02, 0xA4,
            0xFF07, 0xFFFF, 0x22, 0xFF02, 0xA3, 0xFF07, 0xFFFF, 0x23, 0xFF02, 0xAB, 0xFF07, 0xFFFF, 0x24, 0xFF02, 0xAB, 0xFF07,
            0xFFFF, 0x25, 0xFF02, 0xAB, 0xFF07, 0xFFFF, 0x26, 0xFF02, 0x9F, 0xFF07, 0xFFFF, 0x27, 0xFF02, 0x9F, 0xFF07, 0xFFFF,
            0x28, 0xFF02, 0xA5, 0xFF07, 0xFFFF, 0x29, 0xFF02, 0xA9, 0xFF07, 0xFFFF, 0x2A, 0xFF02, 0xA9, 0xFF07, 0xFFFF, 0x2B,
            0xFF02, 0xA9, 0xFF07, 0xFFFF, 0x2C, 0xFF02, 0xAA, 0xFF07, 0xFFFF, 0x2F, 0xFF24, 0xFF02, 0xA9, 0xFF07, 0xFFFF, 0x33,
            0xFF24, 0xFF02, 0x70, 0xFF05, 0xFF07, 0xFFFF, 0x31, 0xFF24, 0xFF02, 0xA5, 0xFF07, 0xFFFF, 0x34, 0xFF24, 0xFF02, 0x8C,
            0xFF07, 0xFFFF, 0x35, 0xFF24, 0xFF02, 0x8C, 0xFF07, 0xFFFF, 0x36, 0xFF24, 0xFF02, 0x78, 0xFF07, 0x7B, 0xFFFF, 0x3C,
            0xFF23, 0xFFFF, 0xFFFF
    };
    /**
     * The unit production requirement words.
     */
    private static final int @NotNull [] BUILD_SCRIPT = {
            0x0, 0x0, 0xFF02, 0x6F, 0xFF08, 0xFF05, 0xFFFF, 0x1, 0xFF02, 0x6F, 0xFF08, 0xFF05, 0x75, 0x70, 0xFFFF, 0x2,
            0xFF02, 0x71, 0xFF05, 0xFF08, 0xFFFF, 0x3, 0xFF02, 0x71, 0xFF05, 0xFF08, 0x7B, 0xFFFF, 0x5, 0xFF02, 0x71, 0xFF05,
            0xFF08, 0xFF04, 0x78, 0xFFFF, 0x7, 0xFF02, 0x6A, 0xFF08, 0xFF05, 0xFFFF, 0x8, 0xFF02, 0x72, 0xFF08, 0xFF05, 0xFFFF,
            0x9, 0xFF02, 0x72, 0xFF08, 0xFF05, 0xFF04, 0x73, 0x74, 0xFFFF, 0xB, 0xFF02, 0x72, 0xFF08, 0xFF05, 0xFF04, 0x73,
            0xFFFF, 0xC, 0xFF02, 0x72, 0xFF08, 0xFF05, 0xFF04, 0x73, 0x76, 0xFFFF, 0x20, 0xFF02, 0x6F, 0xFF08, 0xFF05, 0x70,
            0xFFFF, 0xE, 0xFF02, 0x6C, 0xFF10, 0xFF07, 0xFFFF, 0x22, 0xFF24, 0xFF02, 0x6F, 0xFF08, 0xFF05, 0x70, 0xFFFF, 0x3A,
            0xFF24, 0xFF02, 0x72, 0xFF08, 0xFF05, 0xFF04, 0x73, 0x7B, 0xFFFF, 0x25, 0xFF02, 0x23, 0x8E, 0xFFFF, 0x26, 0xFF02,
            0x23, 0x87, 0xFFFF, 0x27, 0xFF02, 0x23, 0x8C, 0xFFFF, 0x29, 0xFF02, 0x23, 0xFFFF, 0x2A, 0xFF02, 0x23, 0xFFFF,
            0x2B, 0xFF02, 0x23, 0x8D, 0xFF01, 0xFF03, 0x89, 0xFFFF, 0x2C, 0xFF02, 0x2B, 0x89, 0xFFFF, 0x2D, 0xFF02, 0x23,
            0x8A, 0xFFFF, 0x2E, 0xFF02, 0x23, 0x88, 0xFFFF, 0x2F, 0xFF02, 0x23, 0x8D, 0xFF01, 0xFF03, 0x89, 0xFFFF, 0x32,
            0xFF02, 0x82, 0xFF05, 0xFFFF, 0x3E, 0xFF24, 0xFF02, 0x2B, 0x89, 0xFFFF, 0x67, 0xFF24, 0xFF02, 0x26, 0xFF25, 0x20,
            0xFFFF, 0x40, 0xFF02, 0x9A, 0xFF08, 0xFFFF, 0x41, 0xFF02, 0xA0, 0xFFFF, 0x42, 0xFF02, 0xA0, 0xA4, 0xFFFF, 0x43,
            0xFF02, 0xA0, 0xA5, 0xFFFF, 0x45, 0xFF02, 0x9B, 0xFFFF, 0x46, 0xFF02, 0xA7, 0xFFFF, 0x47, 0xFF02, 0xA7, 0xAA,
            0xFFFF, 0x48, 0xFF02, 0xA7, 0xA9, 0xFFFF, 0x49, 0xFF02, 0x48, 0xFF01, 0xFF02, 0x52, 0xFF0E, 0xFFFF, 0x53, 0xFF02,
            0x9B, 0xAB, 0xFFFF, 0x55, 0xFF02, 0x53, 0xFF01, 0xFF02, 0x51, 0xFF0E, 0xFFFF, 0x54, 0xFF02, 0x9B, 0x9F, 0xFFFF,
            0x3C, 0xFF24, 0xFF02, 0xA7, 0xFFFF, 0x3D, 0xFF24, 0xFF02, 0xA0, 0xA5, 0xFFFF, 0x6A, 0xFF02, 0x7, 0xFF0B, 0xFFFF,
            0x6B, 0xFF02, 0x6A, 0xFF0C, 0xFF07, 0x70, 0xFFFF, 0x6C, 0xFF02, 0x6A, 0xFF0C, 0xFF07, 0x75, 0xFFFF, 0x6D, 0xFF02,
            0x7, 0xFF0B, 0xFFFF, 0x6E, 0xFF02, 0x7, 0xFF0B, 0xFFFF, 0x6F, 0xFF02, 0x7, 0xFF0B, 0x6A, 0xFFFF, 0x70, 0xFF02,
            0x7, 0xFF0B, 0x6F, 0xFFFF, 0x71, 0xFF02, 0x7, 0xFF0B, 0x6F, 0xFFFF, 0x72, 0xFF02, 0x7, 0xFF0B, 0x71, 0xFFFF,
            0x73, 0xFF02, 0x72, 0xFF0C, 0xFF07, 0xFFFF, 0x74, 0xFF02, 0x7, 0xFF0B, 0x72, 0xFFFF, 0x75, 0xFF02, 0x74, 0xFF0C,
            0xFF07, 0xFFFF, 0x76, 0xFF02, 0x74, 0xFF0C, 0xFF07, 0xFFFF, 0x78, 0xFF02, 0x71, 0xFF0C, 0xFF07, 0xFFFF, 0x7A, 0xFF02,
            0x7, 0xFF0B, 0x6A, 0xFFFF, 0x7B, 0xFF02, 0x7, 0xFF0B, 0x71, 0xFFFF, 0x7C, 0xFF02, 0x7, 0xFF0B, 0x7A, 0xFFFF,
            0x7D, 0xFF02, 0x7, 0xFF0B, 0x6F, 0xFFFF, 0x83, 0xFF02, 0x29, 0xFF11, 0xFFFF, 0x84, 0xFF02, 0x83, 0x8E, 0xFF07,
            0xFFFF, 0x85, 0xFF02, 0x84, 0x8A, 0xFF07, 0xFFFF, 0x8E, 0xFF02, 0x29, 0xFF11, 0x83, 0xFF01, 0xFF03, 0x84, 0xFF01,
            0xFF03, 0x85, 0xFFFF, 0x87, 0xFF02, 0x29, 0xFF11, 0x8E, 0xFFFF, 0x88, 0xFF02, 0x29, 0xFF11, 0x85, 0xFFFF, 0x89,
            0xFF02, 0x8D, 0xFF07, 0x85, 0xFFFF, 0x8A, 0xFF02, 0x29, 0xFF11, 0x84, 0xFF01, 0xFF03, 0x85, 0xFFFF, 0x8B, 0xFF02,
            0x29, 0xFF11, 0x83, 0xFF01, 0xFF03, 0x84, 0xFF01, 0xFF03, 0x85, 0xFFFF, 0x8C, 0xFF02, 0x29, 0xFF11, 0x85, 0xFFFF,
            0x8D, 0xFF02, 0x29, 0xFF11, 0x84, 0xFF01, 0xFF03, 0x85, 0xFFFF, 0x86, 0xFF02, 0x29, 0xFF11, 0x85, 0xFFFF, 0x8F,
            0xFF02, 0x29, 0xFF11, 0xFFFF, 0x90, 0xFF02, 0x8F, 0x8B, 0xFFFF, 0x92, 0xFF02, 0x8F, 0x8E, 0xFFFF, 0x95, 0xFF02,
            0x29, 0xFFFF, 0x9A, 0xFF02, 0x40, 0xFFFF, 0x9B, 0xFF02, 0x40, 0xA4, 0xFFFF, 0x9C, 0xFF02, 0x40, 0xFFFF, 0x9D,
            0xFF02, 0x40, 0xFFFF, 0x9F, 0xFF02, 0x40, 0x9B, 0xFFFF, 0xA0, 0xFF02, 0x40, 0x9A, 0xFFFF, 0xA2, 0xFF02, 0x40,
            0xA6, 0xFFFF, 0xA3, 0xFF02, 0x40, 0xA4, 0xFFFF, 0xA4, 0xFF02, 0x40, 0xA0, 0xFFFF, 0xA5, 0xFF02, 0x40, 0xA3,
            0xFFFF, 0xA6, 0xFF02, 0x40, 0x9A, 0xFFFF, 0xA7, 0xFF02, 0x40, 0xA4, 0xFFFF, 0xA9, 0xFF02, 0x40, 0xA7, 0xFFFF,
            0xAA, 0xFF02, 0x40, 0xA7, 0xA5, 0xFFFF, 0xAB, 0xFF02, 0x40, 0x9B, 0xFFFF, 0xAC, 0xFF02, 0x40, 0xA0, 0xFFFF,
            0xFFFF
    };

    /**
     * The first requirement word of each technology, upgrade and unit type in the installed scripts.
     */
    private static final int @NotNull [] RESEARCH_STARTS = buildStarts(RESEARCH_SCRIPT, TECHNOLOGY_LIMIT);
    private static final int @NotNull [] UPGRADE_STARTS = buildStarts(UPGRADE_SCRIPT, UPGRADE_LIMIT);
    private static final int @NotNull [] BUILD_STARTS = buildStarts(BUILD_SCRIPT, BUILD_LIMIT);

    /**
     * Copies {@code 5} writable WORD scripts for one game. EUD writes change the words read by the tests; the game's live DAT index columns locate
     * each entry.
     *
     * @return the order, technology-use, research, upgrade and production scripts, a WORD per element
     */
    public static int @NotNull [] @NotNull [] copyScripts() {
        return new int[][]{ORDER_SCRIPT.clone(), TECHNOLOGY_SCRIPT.clone(), RESEARCH_SCRIPT.clone(), UPGRADE_SCRIPT.clone(), BUILD_SCRIPT.clone()};
    }

    /**
     * Builds the installed unit production index before the first trigger pass.
     * <p>
     * The {@code units.dat} requirement index column is already filled with the installed positions at the first frame, while the {@code orders.dat},
     * {@code techdata.dat} and {@code upgrades.dat} columns are still all {@code 0xFFFF}: those are built by the first test of each kind, after an
     * EUD map may already have rewritten the scripts, so their index follows the rewritten layout. A column entry an EUD write sets back to
     * {@code 0xFFFF} is rebuilt from the live script.
     *
     * @param gameData the installed game data
     */
    public static void buildInstalledIndices(@NotNull GameData gameData) {
        readIndex(gameData.getUnitTypes().getRequirementIndices(), BUILD_SCRIPT, 0, BUILD_LIMIT);
    }

    /**
     * @param result a test result
     * @return {@code true} if the requirement holds, {@code false} otherwise
     */
    public static boolean isAccepted(int result) {
        return result == 0;
    }

    /**
     * @param result a test result
     * @return {@code true} if the failure is shown as a disabled button, {@code false} otherwise
     */
    public static boolean isDisabled(int result) {
        return (result & DISABLED) != 0;
    }

    /**
     * @param result a test result
     * @return the failure code after the test: the failure code, or {@code 0} when accepted or failed without a code
     */
    public static int getFailureCode(int result) {
        int code = result & ~DISABLED;

        return code == FAILED_WITHOUT_CODE ? 0 : code;
    }

    /**
     * @param upgrade the upgrade
     * @return the first requirement word of the upgrade in the installed script, or {@code 0} without a script
     * @throws ArrayIndexOutOfBoundsException if the upgrade is outside the installed upgrade-script start array
     */
    public static int getUpgradeScriptStart(int upgrade) {
        return UPGRADE_STARTS[upgrade];
    }

    /**
     * @param position the word position
     * @return the word of the installed upgrade script at the position
     * @throws ArrayIndexOutOfBoundsException if the position is outside the installed upgrade-script word array
     */
    public static int getUpgradeScriptWord(int position) {
        return UPGRADE_SCRIPT[position];
    }

    /**
     * @param technology the technology
     * @return the first requirement word of the technology in the installed script, or {@code 0} without a research script
     * @throws ArrayIndexOutOfBoundsException if the technology is outside the installed research-script start array
     */
    public static int getResearchScriptStart(int technology) {
        return RESEARCH_STARTS[technology];
    }

    /**
     * @param position the word position
     * @return the word of the installed research script at the position
     * @throws ArrayIndexOutOfBoundsException if the position is outside the installed research-script word array
     */
    public static int getResearchScriptWord(int position) {
        return RESEARCH_SCRIPT[position];
    }

    /**
     * @param type the unit type
     * @return the first requirement word of the unit type in the installed script, or {@code 0} without a production script
     * @throws ArrayIndexOutOfBoundsException if the type is outside the installed build-script start array
     */
    public static int getBuildScriptStart(int type) {
        return BUILD_STARTS[type];
    }

    /**
     * @param position the word position
     * @return the word of the installed production script at the position
     * @throws ArrayIndexOutOfBoundsException if the position is outside the installed build-script word array
     */
    public static int getBuildScriptWord(int position) {
        return BUILD_SCRIPT[position];
    }

    /**
     * Returns the Spider Mine count the Spider Mines word reads: the unit byte the unit union holds as the Spider Mine count of a Vulture. Its only
     * script use is the Spider Mines entry ({@code 0xFF1B} before the Vulture types), so for any other type the result is a failure either way
     * ({@code 8} here, {@code 0x19} from the type words); other types answer {@code 0}.
     *
     * @param units the unit type table
     * @param unit  the tested unit
     * @return the Spider Mine count of a Vulture, otherwise {@code 0}
     */
    static int getSpiderMineCount(@NotNull ScrUnitTypeView units, @NotNull Unit unit) {
        int type = unit.getUnitId();

        if (units.isVulture(type)) {
            return unit.getSpiderMineCount();
        }

        return 0;
    }

    /**
     * Applies an index builder of the tests to an installed script: maps every identifier below the limit to the position after it.
     *
     * @param script the installed script
     * @param limit  the test's identifier limit
     * @return the first requirement word of each identifier, {@code 0} without an entry
     */
    private static int @NotNull [] buildStarts(int @NotNull [] script, int limit) {
        int[] starts = new int[limit];
        int position = FIRST_ENTRY_POSITION;

        while (script[position] != RequirementOpcodes.END) {
            int identifier = script[position];

            if (identifier < limit) {
                starts[identifier] = position + 1;
            }

            while (script[position] != RequirementOpcodes.END) {
                position++;
            }

            position++;
        }

        return starts;
    }

    /**
     * Reads a requirement index from the game's DAT column, building the whole column first when the entry is {@code 0xFFFF}: as the index builders
     * at the start of the tests, each identifier of the live script gets the position after it, then every entry still {@code 0xFFFF} below the limit
     * becomes {@code 0}. A map that rewrites a script before the first test is therefore indexed by its own layout.
     *
     * @param column     the game's DAT index column
     * @param script     the live script
     * @param identifier the tested identifier
     * @param limit      the test's identifier limit
     * @return the first requirement word of the identifier, {@code 0} without an entry
     * @throws UnsupportedOperationException if the script holds an identifier past the column
     */
    private static int readIndex(@NotNull RequirementIndexColumn column, int @NotNull [] script, int identifier, int limit) {
        if (column.get(identifier) == UNINDEXED) {
            int count = column.getCount();
            int position = FIRST_ENTRY_POSITION;

            while (script[position] != RequirementOpcodes.END) {
                int entry = script[position];
                // Only the upgrade builder skips identifiers from its limit: the level sections 0xFF20/0xFF21.
                boolean skipped = limit == UPGRADE_LIMIT && entry >= limit;

                if (!skipped && entry >= count) {
                    throw Exceptions.newUnsupportedOperationException(
                            "The requirement index builder writes identifier {0} past the DAT column {1} (native memory after it).",
                            entry,
                            column.getName()
                    );
                }

                if (!skipped) {
                    column.set(entry, position + 1);
                }

                while (script[position] != RequirementOpcodes.END) {
                    position++;
                }

                position++;
            }

            for (int index = 0; index < limit; index++) {
                if (column.get(index) == UNINDEXED) {
                    column.set(index, 0);
                }
            }
        }

        return column.get(identifier);
    }


    /**
     * Returns the SC:R requirement system of an engine game.
     *
     * @param game the engine game of the SC:R definition
     * @return the requirement system
     * @throws IllegalStateException if the game's requirement system is not the SC:R one
     */
    public static @NotNull ScrRequirementSystem of(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game) {
        if (!(game.getModSystem(RequirementSystem.KEY) instanceof ScrRequirementSystem requirements)) {
            throw Exceptions.newIllegalStateException("The game has no SC:R requirement system.");
        }

        return requirements;
    }


    /**
     * The SC:R game system, attached after creation.
     */
    private @Nullable Game game;

    /**
     * The game's requirement scripts ({@link #copyScripts()}), which EUD writes change.
     */
    private final int @NotNull [] @NotNull [] scripts;
    /**
     * The own production requirement scripts of the added unit types ({@link ScrAddedUnitType#getRequirementScript()}), copied by the first
     * production test of an added type: an element per added type, {@code null} for a type that runs the script of its requirement type.
     */
    private int @Nullable [] @Nullable [] addedScripts;

    /**
     * Creates a {@link ScrRequirementSystem} instance for one game; it attaches to the SC:R game system ({@link #attach}).
     */
    public ScrRequirementSystem() {
        this.game = null;

        this.scripts      = copyScripts();
        this.addedScripts = null;
    }

    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Binds the SC:R game system, whose units, players and tables the tests read.
     *
     * @param game the game
     */
    @Override
    public void attach(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game) {
        this.game = game.getModSystem(Game.SYSTEM_KEY);
    }

    /**
     * @return the game's {@code 5} requirement scripts, indexed as {@link #ORDERS} .. {@link #BUILD}, live: EUD writes change them
     */
    public int @NotNull [] @NotNull [] getScripts() {
        return this.scripts;
    }


    /**
     * Checks whether a player may do an action with a type.
     * <p>
     * Determinism contract: a check reads game state only and allocates nothing; it is called from the simulation in a fixed order.
     *
     * @param action the action
     * @param type   the type, of the content kind the action names
     * @param player the player
     * @param actor  the SC:R unit that would act
     * @return the result; {@link RequirementResult#isMet()} if the player may do it
     * @throws IllegalArgumentException if the actor is not an SC:R unit
     * @implSpec Every SC:R test needs its actor, an SC:R unit, whose type the native tests read themselves. The action runs
     *         {@link #checkBuild}, {@link #checkUpgrade}, {@link #checkResearch}, {@link #checkTechnologyUse} or {@link #checkOrder} with
     *         the native entry of the type ({@link ScrNativeIds#toNative}) and the player slot index, and returns the shared
     *         {@link ScrRequirementResult} of its native result.
     */
    @Override
    public @NotNull RequirementResult check(@NotNull RequirementAction action, @NotNull ContentType type, @NotNull Player player,
                                            org.fogofwarlabs.sc4j.engine.entity.@Nullable Unit actor) {
        if (!(actor instanceof Unit scrActor)) {
            throw Exceptions.newIllegalArgumentException("The SC:R requirement tests need an SC:R actor unit: {0}", actor);
        }

        int nativeType = ScrNativeIds.toNative(type);
        int owner = player.getIndex();
        int result = switch (action) {
            case BUILD_UNIT -> this.checkBuild(scrActor, nativeType, owner);
            case RESEARCH_UPGRADE -> this.checkUpgrade(scrActor, nativeType, owner);
            case RESEARCH_TECH -> this.checkResearch(scrActor, nativeType, owner);
            case USE_TECH -> this.checkTechnologyUse(scrActor, nativeType, owner);
            case ISSUE_ORDER -> this.checkOrder(scrActor, nativeType, owner);
        };

        return ScrRequirementResult.of(result);
    }

    /**
     * Applies the order test.
     *
     * @param unit  the unit to receive the order
     * @param order the order
     * @param owner the commanding player
     * @return {@code 0} when the unit may receive the order, otherwise the failure code
     * @throws NullPointerException if the world was built without game data
     */
    public int checkOrder(@NotNull Unit unit, int order, int owner) {
        if (order < 0 || order >= ORDER_LIMIT) {
            return FAILURE_INVALID_ORDER;
        }

        int start = readIndex(this.requireGame().requireGameData().getOrderTypes().getRequirementIndices(), this.scripts[ORDERS], order, ORDER_LIMIT);

        if (owner != unit.getOwnerIndex()) {
            return FAILURE_NOT_OWNER;
        }

        int status = unit.getStatus();

        // The rally orders are accepted from incomplete units.
        if (order != ScrOrderIds.RALLY_POINT_UNIT && order != ScrOrderIds.RALLY_POINT_TILE && (status & UnitStatusFlags.COMPLETED) == 0) {
            return FAILURE_INCOMPLETE;
        }

        if (UnitOrderEligibility.isDisabled(unit)) {
            return FAILURE_UNIT_DISABLED;
        }

        int type = unit.getUnitId();
        int baseType = this.requireGame().getBaseType(type);

        if ((status & UnitStatusFlags.BURROWED) != 0 && baseType != ScrUnitIds.ZERG_LURKER && order != ScrOrderIds.UNBURROWING) {
            return FAILURE_BURROWED;
        }

        int primary = unit.getPrimaryOrder();

        if (baseType == ScrUnitIds.TERRAN_SCV && primary == ScrOrderIds.CONSTRUCTING_BUILDING && order != ScrOrderIds.STOP) {
            return FAILURE_REQUIREMENT;
        }

        if (this.requireGame().requireGameData().getUnitTypes().isGhost(type) && primary == ScrOrderIds.NUKE_TRACK) {
            return FAILURE_REQUIREMENT;
        }

        if ((baseType == ScrUnitIds.PROTOSS_ARCHON || baseType == ScrUnitIds.PROTOSS_DARK_ARCHON) &&
            primary == ScrOrderIds.COMPLETING_ARCHON_SUMMON) {
            return FAILURE_REQUIREMENT;
        }

        if (start == 0) {
            return FAILURE_NO_SCRIPT;
        }

        return this.interpret(order, owner, unit, this.scripts[ORDERS], start);
    }

    /**
     * Applies the technology-use test.
     *
     * @param unit       the unit to use the technology
     * @param technology the technology
     * @param owner      the commanding player
     * @return {@code 0} when the unit may use the technology, otherwise the failure code
     * @throws UnsupportedOperationException if the player technology state is missing
     * @throws NullPointerException          if the world was built without game data
     */
    public int checkTechnologyUse(@NotNull Unit unit, int technology, int owner) {
        if (technology < 0 || technology >= TECHNOLOGY_LIMIT) {
            return FAILURE_INVALID_TECHNOLOGY;
        }

        int start = readIndex(this.requireGame().requireGameData().getTechTypes().getUseRequirementIndices(),
                              this.scripts[TECHNOLOGY_USE],
                              technology,
                              TECHNOLOGY_LIMIT);

        if (owner != unit.getOwnerIndex()) {
            return FAILURE_NOT_OWNER;
        }

        int status = unit.getStatus();

        if ((status & UnitStatusFlags.COMPLETED) == 0) {
            return FAILURE_INCOMPLETE;
        }

        if (UnitOrderEligibility.isDisabled(unit)) {
            return FAILURE_UNIT_DISABLED;
        }

        if ((status & UnitStatusFlags.HALLUCINATION) != 0) {
            return FAILURE_HALLUCINATION;
        }

        if (!this.requireGame().getPlayerTable().requirePlayerTechnology().isAvailable(owner, technology) &&
            (this.requireGame().getCheatFlags() & GameCheats.MEDIEVAL_MAN) == 0) {
            return FAILURE_UNAVAILABLE;
        }

        if (start == 0) {
            return FAILURE_NO_SCRIPT;
        }

        return this.interpret(technology, owner, unit, this.scripts[TECHNOLOGY_USE], start);
    }

    /**
     * Applies the research test.
     *
     * @param unit       the researching building
     * @param technology the technology
     * @param owner      the commanding player
     * @return {@code 0} when the unit may start the research, otherwise the failure code
     * @throws UnsupportedOperationException if the player technology state is missing
     * @throws NullPointerException          if the world was built without game data
     */
    public int checkResearch(@NotNull Unit unit, int technology, int owner) {
        if (technology < 0 || technology >= TECHNOLOGY_LIMIT) {
            return FAILURE_INVALID_TECHNOLOGY;
        }

        int start = readIndex(this.requireGame().requireGameData().getTechTypes().getResearchRequirementIndices(),
                              this.scripts[RESEARCH],
                              technology,
                              TECHNOLOGY_LIMIT);

        if (owner != unit.getOwnerIndex()) {
            return FAILURE_NOT_OWNER;
        }

        if ((unit.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
            return FAILURE_INCOMPLETE;
        }

        if (UnitOrderEligibility.isDisabled(unit)) {
            return FAILURE_UNIT_DISABLED;
        }

        PlayerTechnology technologies = this.requireGame().getPlayerTable().requirePlayerTechnology();
        boolean cheat = (this.requireGame().getCheatFlags() & GameCheats.MEDIEVAL_MAN) != 0;

        if (!technologies.isAvailable(owner, technology) && !cheat) {
            return FAILURE_UNAVAILABLE;
        }

        if (technologies.isResearching(owner, technology)) {
            return FAILURE_IN_PROGRESS;
        }

        if (technologies.isResearched(owner, technology) || cheat) {
            return FAILURE_RESEARCHED;
        }

        if (start == 0) {
            return FAILURE_NO_SCRIPT;
        }

        return this.interpret(technology, owner, unit, this.scripts[RESEARCH], start);
    }

    /**
     * Applies the upgrade test.
     *
     * @param unit    the upgrading building
     * @param upgrade the upgrade
     * @param owner   the commanding player
     * @return {@code 0} when the unit may start the next level, otherwise the failure code
     * @throws UnsupportedOperationException if the player upgrade state is missing
     * @throws NullPointerException          if the world was built without game data
     */
    public int checkUpgrade(@NotNull Unit unit, int upgrade, int owner) {
        if (upgrade < 0 || upgrade >= UPGRADE_LIMIT) {
            return FAILURE_INVALID_UPGRADE;
        }

        int start = readIndex(this.requireGame().requireGameData().getUpgradeTypes().getRequirementIndices(),
                              this.scripts[UPGRADES],
                              upgrade,
                              UPGRADE_LIMIT);

        if (owner != unit.getOwnerIndex()) {
            return FAILURE_NOT_OWNER;
        }

        if ((unit.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
            return FAILURE_INCOMPLETE;
        }

        if (UnitOrderEligibility.isDisabled(unit)) {
            return FAILURE_UNIT_DISABLED;
        }

        PlayerUpgrades upgrades = this.requireGame().getPlayerTable().requirePlayerUpgrades();

        if (upgrades.getLevel(owner, upgrade) >= upgrades.getMaximumLevel(owner, upgrade)) {
            return FAILURE_MAXIMUM_LEVEL;
        }

        if (upgrades.isUpgrading(owner, upgrade)) {
            return FAILURE_IN_PROGRESS;
        }

        if (start == 0) {
            return FAILURE_NO_SCRIPT;
        }

        return this.interpret(upgrade, owner, unit, this.scripts[UPGRADES], start);
    }

    /**
     * Applies the unit production test with its interpreter.
     * <p>
     * Unlike the shared interpreter, a plain unit type word counts {@code 1} extra unit under the production cheat, {@code 0xFF02} clears the group's
     * nothing-owned flag for a producer mismatch (in a replay only from build {@code 0x18C6}, {@link GameRulesVersion#appliesProducerMismatch}),
     * {@code 0xFF11} rejects every burrowed producer, and the words {@code 0xFF04} (the add-on), {@code 0xFF0B} (an SCV not constructing),
     * {@code 0xFF0C} (no add-on), {@code 0xFF0E} (hangar room), {@code 0xFF10} (an empty Nuclear Silo) and {@code 0xFF25} (a researched technology)
     * exist only here.
     * <p>
     * An added unit type ({@link GameData#getAddedUnitTypes()}) passes the same tests with its own requirement script
     * ({@link ScrAddedUnitType#getRequirementScript()}), or without one with the build script of its requirement type.
     *
     * @param producer the producing unit
     * @param type     the requested unit type
     * @param owner    the requesting player
     * @return {@code 0} when the unit may be produced, otherwise the failure code or {@link #FAILED_WITHOUT_CODE}
     * @throws UnsupportedOperationException if unit accounting or the player technology or upgrade state is missing, or a script word is unsupported
     * @throws NullPointerException          if the world was built without game data
     */
    public int checkBuild(@NotNull Unit producer, int type, int owner) {
        GameData gameData = this.requireGame().requireGameData();
        boolean added = gameData.getUnitTypes().isAddedType(type);

        if (!added && (type < 0 || type >= BUILD_LIMIT)) {
            return FAILURE_INVALID_UNIT_TYPE;
        }

        if (owner != producer.getOwnerIndex()) {
            return FAILURE_NOT_OWNER;
        }

        int status = producer.getStatus();

        if ((status & UnitStatusFlags.COMPLETED) == 0) {
            return FAILURE_INCOMPLETE;
        }

        if (UnitOrderEligibility.isDisabled(producer)) {
            return FAILURE_UNIT_DISABLED;
        }

        // The per-player availability of the map settings.
        PlayerUnitAvailability availability = this.requireGame().getPlayerTable().getPlayerUnitAvailability();

        if (availability != null && !availability.isAllowed(owner, type)) {
            return FAILURE_UNAVAILABLE;
        }

        // The last queue slot holds a unit type that is not a building.
        if (UnitTypeTraitsUtils.isQueueUnitType(gameData.getUnitTypes(),
                                                producer.getBuildQueueUnitType((producer.getBuildQueueIndex() + LAST_QUEUE_SLOT_OFFSET) %
                                                                               Unit.BUILD_QUEUE_SLOTS))) {
            return FAILURE_FULL;
        }

        if ((status & UnitStatusFlags.HALLUCINATION) != 0) {
            return FAILURE_HALLUCINATION;
        }

        int[] script = added ? this.getAddedScript(gameData, type) : null;
        int position = 0;

        // An added unit type without its own script runs the build script of its requirement type.
        if (script == null) {
            int scriptType = added ? ScrUnits.getNativeId(gameData.getAddedUnitTypes().get(type).getRequirementType()) : type;

            script   = this.scripts[BUILD];
            position = readIndex(gameData.getUnitTypes().getRequirementIndices(), script, scriptType, BUILD_LIMIT);

            if (position == 0) {
                return FAILURE_NO_SCRIPT;
            }
        }

        UnitAccounting accounting = this.requireGame().getPlayerTable().getUnitAccounting();

        if (accounting == null) {
            throw Exceptions.newUnsupportedOperationException("Requirement scripts need initialized unit accounting.");
        }

        int cheats = this.requireGame().getCheatFlags();
        boolean wrongProducer = false;
        boolean firstAlternative = true;
        int word = script[position];

        if (word == RequirementOpcodes.END) {
            return 0;
        }

        while (true) {
            int satisfied = 0;
            boolean nothingOwned = true;

            while (true) {
                switch (word) {
                    case RequirementOpcodes.CURRENT_UNIT -> {
                        int required = script[++position];

                        if (accounting.getCompletedTypeCount(required, owner) != 0) {
                            nothingOwned = false;
                        }

                        if (required == this.requireGame().getBaseType(producer)) {
                            wrongProducer    = false;
                            firstAlternative = false;
                            satisfied++;
                        } else if (firstAlternative) {
                            wrongProducer = true;

                            if (this.requireGame().getRulesVersion().appliesProducerMismatch()) {
                                nothingOwned = false;
                            }
                        }
                    }
                    case RequirementOpcodes.MUST_HAVE -> {
                        int required = script[++position];

                        satisfied += accounting.getCompletedTypeCount(required, owner) + accounting.getTypeCount(required, owner);
                    }
                    case RequirementOpcodes.ADDON -> {
                        // The add-on must be the next word's type, unless the production cheat is on.
                        if ((cheats & GameCheats.MODIFY_THE_PHASE_VARIANCE) == 0) {
                            Unit addon = producer.getAddon();

                            if (addon == null || addon.getUnitId() != script[position + 1]) {
                                return DISABLED | (wrongProducer ? FAILED_WITHOUT_CODE : FAILURE_ADDON);
                            }
                        }

                        position++;
                        satisfied++;
                    }
                    case RequirementOpcodes.LANDED -> {
                        if (!UnitQueries.isGroundedBuilding(producer)) {
                            return FAILURE_NOT_LANDED;
                        }

                        satisfied++;
                    }
                    case RequirementOpcodes.NOT_BUSY -> {
                        if (UnitQueries.isProducing(producer) || producer.getResearchType() != ScrTechIds.NONE ||
                            producer.getUpgradeType() != ScrUpgradeIds.NONE) {
                            return FAILURE_BUSY;
                        }

                        satisfied++;
                    }
                    case RequirementOpcodes.NOT_BUILDING_ADDON -> {
                        if (UnitQueries.isBuildingAddon(producer) || UnitQueries.isPlacingProtossBuilding(producer)) {
                            return FAILURE_BUSY;
                        }

                        satisfied++;
                    }
                    case RequirementOpcodes.NOT_RESEARCHING -> {
                        if (producer.getResearchType() != ScrTechIds.NONE) {
                            return FAILURE_BUSY;
                        }

                        satisfied++;
                    }
                    case RequirementOpcodes.NOT_UPGRADING -> {
                        if (producer.getUpgradeType() != ScrUpgradeIds.NONE) {
                            return FAILURE_BUSY;
                        }

                        satisfied++;
                    }
                    case RequirementOpcodes.NOT_CONSTRUCTING -> {
                        if (this.requireGame().getBaseType(producer) == ScrUnitIds.TERRAN_SCV &&
                            producer.getPrimaryOrder() == ScrOrderIds.CONSTRUCTING_BUILDING) {
                            return FAILURE_BUSY;
                        }

                        satisfied++;
                    }
                    case RequirementOpcodes.NO_ADDON -> {
                        if (producer.getAddon() != null) {
                            return wrongProducer ? FAILED_WITHOUT_CODE : FAILURE_ADDON;
                        }

                        satisfied++;
                    }
                    case RequirementOpcodes.HANGAR_ROOM -> {
                        // The hangar capacity above the stored and queued count.
                        Game game = this.requireGame();
                        int capacity = game.getUnitQueries().getHangarCapacity(producer);

                        if (capacity <= UnitQueries.getHangarUse(game.requireGameData().getUnitTypes(), producer)) {
                            return FAILURE_FULL;
                        }

                        satisfied++;
                    }
                    case RequirementOpcodes.SILO_EMPTY -> {
                        if (UnitQueries.getLinkedUnit(producer) != null) {
                            return FAILURE_FULL;
                        }

                        satisfied++;
                    }
                    case RequirementOpcodes.NOT_BURROWED -> {
                        if ((status & UnitStatusFlags.BURROWED) != 0) {
                            return FAILURE_BUSY;
                        }

                        satisfied++;
                    }
                    case RequirementOpcodes.DISABLED -> {
                        return DISABLED | FAILURE_DISABLED_WORD;
                    }
                    case RequirementOpcodes.UNAVAILABLE -> {
                        return FAILURE_NO_SCRIPT;
                    }
                    case RequirementOpcodes.EXPANSION -> {
                        if (!this.requireGame().getPlayerTable().requirePlayerUpgrades().isExpansionEnabled()) {
                            return FAILURE_EXPANSION;
                        }

                        satisfied++;
                    }
                    case RequirementOpcodes.TECHNOLOGY_RESEARCHED -> {
                        int technology = script[++position];

                        if (!this.requireGame().getPlayerTable().requirePlayerTechnology().isResearched(owner, technology) &&
                            (cheats & GameCheats.MEDIEVAL_MAN) == 0) {
                            return DISABLED | FAILURE_NOT_RESEARCHED;
                        }

                        satisfied++;
                    }
                    case RequirementOpcodes.BURROWED -> {
                        if ((status & UnitStatusFlags.BURROWED) == 0) {
                            return DISABLED | FAILURE_BUSY;
                        }

                        satisfied++;
                    }
                    default -> {
                        if (word >= ScrUnitIds.NONE) {
                            throw Exceptions.newUnsupportedOperationException(
                                    "Requirement word 0x{0} has no case in the build requirement interpreter.",
                                    Integer.toHexString(word)
                            );
                        }

                        satisfied += accounting.getCompletedTypeCount(word, owner) + ((cheats & GameCheats.MODIFY_THE_PHASE_VARIANCE) != 0 ? 1 : 0);
                    }
                }

                word = script[++position];

                if (word != RequirementOpcodes.OR) {
                    break;
                }

                word = script[++position];
            }

            if (satisfied == 0 && nothingOwned) {
                return DISABLED | FAILURE_REQUIREMENT;
            }

            if (word == RequirementOpcodes.END) {
                return wrongProducer ? DISABLED | FAILURE_WRONG_UNIT : 0;
            }
        }
    }

    /**
     * Returns the own production requirement script of an added unit type, copying the scripts of every added type on the first call.
     *
     * @param gameData the game data whose added unit types the game runs
     * @param type     the added unit type
     * @return the requirement words, or {@code null} if the type runs the build script of its requirement type
     */
    private int @Nullable [] getAddedScript(@NotNull GameData gameData, int type) {
        int[][] copies = this.addedScripts;

        if (copies == null) {
            ScrAddedUnitTypes addedTypes = gameData.getAddedUnitTypes();

            copies = new int[addedTypes.getCount()][];

            for (int index = 0; index < copies.length; index++) {
                ScrRequirementScript script = addedTypes.getTypes().get(index).getRequirementScript();

                copies[index] = script == null ? null : script.copyWords();
            }

            this.addedScripts = copies;
        }

        return copies[type - ScrAddedUnitTypes.FIRST_ID];
    }

    /**
     * Applies the interpreter shared by the order, technology-use, research and upgrade tests.
     * <p>
     * The locals track group state: {@code wrongProducer}/{@code firstAlternative} track an {@code 0xFF02} unit type that is not the unit's own (code
     * {@code 0x19}), {@code hallucinationAllowed} is set by {@code 0xFF1E}, and the booleans {@code nothingOwned}, {@code afterUnitType} and
     * {@code beforeAlternative} are the flags that decide code {@code 8} for a group without a satisfied alternative: an {@code 0xFF02} type the
     * owner has completed clears it, a plain unit type word, {@code 0xFF1E} and {@code 0xFF24} restore the value saved at the last {@code 0xFF01}.
     *
     * @param identifier the tested order, technology or upgrade
     * @param owner      the commanding player
     * @param unit       the tested unit
     * @param script     the live script
     * @param position   the first requirement word
     * @return {@code 0} when the requirement holds, otherwise the failure code
     * @throws UnsupportedOperationException if unit accounting or the player technology or upgrade state is missing, or a script word is unsupported
     * @throws NullPointerException          if the world was built without game data
     */
    private int interpret(int identifier, int owner, @NotNull Unit unit, int @NotNull [] script, int position) {
        UnitAccounting accounting = this.requireGame().getPlayerTable().getUnitAccounting();

        if (accounting == null) {
            throw Exceptions.newUnsupportedOperationException("Requirement scripts need initialized unit accounting.");
        }

        boolean wrongProducer = false;
        boolean hallucinationAllowed = false;
        boolean firstAlternative = true;
        int word = script[position];

        while (word != RequirementOpcodes.END) {
            int satisfied = 0;
            boolean afterUnitType = true;
            boolean beforeAlternative = true;
            boolean nothingOwned;

            while (true) {
                nothingOwned = afterUnitType;

                boolean counted = true;

                switch (word) {
                    case RequirementOpcodes.CURRENT_UNIT -> {
                        int required = script[++position];

                        nothingOwned  = accounting.getCompletedTypeCount(required, owner) == 0 && beforeAlternative;
                        afterUnitType = nothingOwned;

                        if (required == this.requireGame().getBaseType(unit)) {
                            wrongProducer    = false;
                            firstAlternative = false;
                            satisfied++;
                        } else if (firstAlternative) {
                            wrongProducer = true;
                        }

                        counted = false;
                    }
                    case RequirementOpcodes.MUST_HAVE -> {
                        int required = script[++position];

                        satisfied += accounting.getTypeCount(required, owner) + accounting.getCompletedTypeCount(required, owner);
                        counted = false;
                    }
                    case RequirementOpcodes.LANDED -> {
                        if (!UnitQueries.isGroundedBuilding(unit)) {
                            return FAILURE_NOT_LANDED;
                        }
                    }
                    case RequirementOpcodes.LIFTED -> {
                        if (UnitQueries.isGroundedBuilding(unit)) {
                            return FAILURE_NOT_LIFTED;
                        }
                    }
                    case RequirementOpcodes.NOT_BUSY -> {
                        // Busy checks include production, research and upgrades in that order.
                        if (UnitQueries.isProducing(unit) || unit.getResearchType() != ScrTechIds.NONE ||
                            unit.getUpgradeType() != ScrUpgradeIds.NONE) {
                            return FAILURE_BUSY;
                        }
                    }
                    case RequirementOpcodes.NOT_BUILDING_ADDON -> {
                        if (UnitQueries.isBuildingAddon(unit) || UnitQueries.isPlacingProtossBuilding(unit)) {
                            return FAILURE_BUSY;
                        }
                    }
                    case RequirementOpcodes.NOT_RESEARCHING -> {
                        if (unit.getResearchType() != ScrTechIds.NONE) {
                            return FAILURE_BUSY;
                        }
                    }
                    case RequirementOpcodes.NOT_UPGRADING -> {
                        if (unit.getUpgradeType() != ScrUpgradeIds.NONE) {
                            return FAILURE_BUSY;
                        }
                    }
                    case RequirementOpcodes.NO_LINKED_UNIT -> {
                        // A Nuclear Silo missile or Nydus Canal exit occupies the linked unit slot.
                        if (UnitQueries.getLinkedUnit(unit) != null) {
                            return FAILURE_REQUIREMENT;
                        }
                    }
                    case RequirementOpcodes.RESEARCHED -> {
                        // Researched, a Lurker for Burrowing, or the requirement cheat counts; otherwise the word neither fails nor counts.
                        boolean researched = identifier == ScrTechIds.BURROWING && this.requireGame().getBaseType(unit) == ScrUnitIds.ZERG_LURKER ||
                                             this.requireGame().getPlayerTable().requirePlayerTechnology().isResearched(owner, identifier);

                        if (!researched && (this.requireGame().getCheatFlags() & GameCheats.MEDIEVAL_MAN) == 0) {
                            counted = false;
                        }
                    }
                    case RequirementOpcodes.NOT_BURROWED -> {
                        // A burrowed unit fails only without a computer record.
                        if ((unit.getStatus() & UnitStatusFlags.BURROWED) != 0 && unit.getComputerRecord() == null) {
                            return FAILURE_BUSY;
                        }
                    }
                    case RequirementOpcodes.NOT_LANDED_BUILDING -> {
                        if (UnitQueries.isGroundedBuilding(unit)) {
                            return FAILURE_WRONG_KIND;
                        }
                    }
                    case RequirementOpcodes.LANDED_BUILDING -> {
                        if (!UnitQueries.isGroundedBuilding(unit)) {
                            return FAILURE_WRONG_KIND;
                        }
                    }
                    case RequirementOpcodes.CAN_MOVE -> {
                        if (!this.requireGame().getUnitQueries().canMove(unit)) {
                            return FAILURE_WRONG_KIND;
                        }
                    }
                    case RequirementOpcodes.CAN_ATTACK -> {
                        if (!this.requireGame().getUnitQueries().canAttack(unit)) {
                            return FAILURE_WRONG_KIND;
                        }
                    }
                    case RequirementOpcodes.WORKER -> {
                        int unitType = unit.getUnitId();

                        if (!this.requireGame().requireGameData().getUnitTypes().hasEntry(unitType) ||
                            (this.requireGame().requireGameData().getUnitTypes().getFlags(unitType) & UnitTypeFlags.WORKER) == 0) {
                            return FAILURE_WRONG_KIND;
                        }
                    }
                    case RequirementOpcodes.FLYING_BUILDING -> {
                        int unitType = unit.getUnitId();

                        if (!this.requireGame().requireGameData().getUnitTypes().hasEntry(unitType) ||
                            (this.requireGame().requireGameData().getUnitTypes().getFlags(unitType) & UnitTypeFlags.FLYING_BUILDING) == 0) {
                            return FAILURE_WRONG_KIND;
                        }
                    }
                    case RequirementOpcodes.TRANSPORT -> {
                        // A transport counts; otherwise the word neither fails nor counts.
                        if (!this.requireGame().getUnitQueries().isTransport(unit)) {
                            counted = false;
                        }
                    }
                    case RequirementOpcodes.POWERUP -> {
                        int unitType = unit.getUnitId();

                        if (!this.requireGame().requireGameData().getUnitTypes().hasEntry(unitType) ||
                            (this.requireGame().requireGameData().getUnitTypes().getFlags(unitType) & UnitTypeFlags.POWERUP) == 0) {
                            return FAILURE_WRONG_KIND;
                        }
                    }
                    case RequirementOpcodes.SUBUNIT -> {
                        int unitType = unit.getUnitId();

                        if (!this.requireGame().requireGameData().getUnitTypes().hasEntry(unitType) ||
                            (this.requireGame().requireGameData().getUnitTypes().getFlags(unitType) & UnitTypeFlags.SUBUNIT) == 0) {
                            return FAILURE_WRONG_KIND;
                        }
                    }
                    case RequirementOpcodes.SPIDER_MINES -> {
                        if (getSpiderMineCount(this.requireGame().requireGameData().getUnitTypes(), unit) == 0) {
                            return FAILURE_REQUIREMENT;
                        }
                    }
                    case RequirementOpcodes.HERO -> {
                        // A hero counts; otherwise the word neither fails nor counts.
                        int unitType = unit.getUnitId();

                        if (!this.requireGame().requireGameData().getUnitTypes().hasEntry(unitType) ||
                            (this.requireGame().requireGameData().getUnitTypes().getFlags(unitType) & UnitTypeFlags.HERO) == 0) {
                            counted = false;
                        }
                    }
                    case RequirementOpcodes.RIGHT_CLICK -> {
                        if (this.requireGame().getUnitQueries().getRightClickClass(unit) == 0) {
                            return FAILURE_REQUIREMENT;
                        }
                    }
                    case RequirementOpcodes.HALLUCINATION_ALLOWED -> {
                        hallucinationAllowed = true;
                        nothingOwned         = beforeAlternative;
                    }
                    case RequirementOpcodes.UPGRADE_LEVEL -> {
                        // The upgrade level selects the section 0xFF20 (level 1) or 0xFF21 (level 2); level 0 stays on this word.
                        int level = this.requireGame().getPlayerTable().requirePlayerUpgrades().getLevel(owner, identifier);
                        int marker = level == LEVEL_ONE
                                     ? RequirementOpcodes.LEVEL_ONE_SECTION
                                     : level == LEVEL_TWO ? RequirementOpcodes.LEVEL_TWO_SECTION : RequirementOpcodes.UPGRADE_LEVEL;

                        while (script[position] != marker) {
                            position++;
                        }
                    }
                    case RequirementOpcodes.DISABLED -> {
                        return DISABLED | FAILURE_DISABLED_WORD;
                    }
                    case RequirementOpcodes.UNAVAILABLE -> {
                        return FAILURE_NO_SCRIPT;
                    }
                    case RequirementOpcodes.EXPANSION -> {
                        if (!this.requireGame().getPlayerTable().requirePlayerUpgrades().isExpansionEnabled()) {
                            return FAILURE_EXPANSION;
                        }

                        nothingOwned = beforeAlternative;
                    }
                    case RequirementOpcodes.BURROWED -> {
                        if ((unit.getStatus() & UnitStatusFlags.BURROWED) == 0) {
                            return FAILURE_BUSY;
                        }
                    }
                    default -> {
                        // The default case counts the completed units of the type word. No script holds another opcode here: 0xFF20/0xFF21 are only
                        // jumped over by 0xFF1F.
                        if (word >= ScrUnitIds.NONE) {
                            throw Exceptions.newUnsupportedOperationException(
                                    "Requirement word 0x{0} has no case in the shared requirement interpreter.",
                                    Integer.toHexString(word)
                            );
                        }

                        satisfied += accounting.getCompletedTypeCount(word, owner);
                        counted      = false;
                        nothingOwned = beforeAlternative;
                    }
                }

                if (counted) {
                    satisfied++;
                }

                word = script[++position];

                if (word != RequirementOpcodes.OR) {
                    break;
                }

                word              = script[++position];
                beforeAlternative = nothingOwned;
            }

            if (wrongProducer) {
                return FAILURE_WRONG_UNIT;
            }

            if (satisfied == 0 && nothingOwned) {
                return DISABLED | FAILURE_REQUIREMENT;
            }
        }

        if ((unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0 && !hallucinationAllowed) {
            return FAILURE_HALLUCINATION;
        }

        return 0;
    }

    private @NotNull Game requireGame() {
        Game attached = this.game;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R requirement system is not attached to its game yet.");
        }

        return attached;
    }
}
