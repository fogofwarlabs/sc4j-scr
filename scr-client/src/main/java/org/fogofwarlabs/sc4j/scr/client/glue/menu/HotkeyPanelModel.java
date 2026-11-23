package org.fogofwarlabs.sc4j.scr.client.glue.menu;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Stores hotkey actions, race and unit groups, command cards, display positions and icon overrides.
 */
final class HotkeyPanelModel {
    /**
     * The action cards' grid: three columns and three rows of one-based cells.
     */
    static final int GRID_SIZE = 3;

    private static final int RALLY_POINT = 672;
    private static final int RALLY_POINT_COLUMN = 2;
    private static final int RALLY_POINT_ROW = 1;
    private static final int HATCHERY = 131;
    private static final int LAIR = 132;
    private static final int HIVE = 133;
    private static final int BUILD_STRUCTURE = 678;
    private static final int BUILD_ADVANCED_STRUCTURE = 679;
    private static final int PROBE = 64;
    private static final int PROBE_BUILD_STRUCTURE_ICON = 272;
    private static final int PROBE_BUILD_ADVANCED_STRUCTURE_ICON = 273;
    private static final @NotNull String ICON_PREFIX = "cmdicons.";
    private static final @NotNull String ICON_SUFFIX = ".png";
    /**
     * The positions of an {@link #ACTION_TABLE} entry's values.
     */
    private static final int ACTION_IDENTIFIER_INDEX = 0;
    private static final int ACTION_COLUMN_INDEX = 1;
    private static final int ACTION_ROW_INDEX = 2;
    private static final int ACTION_ICON_INDEX = 3;

    /**
     * The page's {@code Actions}: id, column, row, icon number.
     */
    private static final int @NotNull [] @NotNull [] ACTION_TABLE = {
            {324, 2, 1, 237}, {325, 1, 1, 240}, {326, 1, 1, 241}, {327, 2, 1, 243}, {328, 3, 1, 245}, {330, 2, 1, 242}, {331, 1, 1, 251},
            {332, 1, 1, 252}, {333, 2, 1, 252}, {334, 1, 3, 237}, {335, 2, 3, 240}, {336, 1, 3, 243}, {337, 1, 1, 250}, {338, 1, 3, 245},
            {339, 1, 3, 246}, {340, 1, 3, 247}, {341, 2, 3, 241}, {342, 3, 3, 242}, {343, 1, 3, 251}, {344, 1, 3, 252}, {345, 1, 3, 253},
            {364, 3, 1, 259}, {366, 1, 1, 40}, {369, 1, 1, 265}, {370, 2, 1, 267}, {371, 2, 1, 302}, {372, 3, 3, 259}, {373, 3, 3, 260},
            {374, 3, 2, 313}, {375, 2, 3, 40}, {376, 1, 3, 270}, {377, 1, 3, 271}, {378, 2, 3, 265}, {379, 3, 2, 302}, {381, 3, 3, 267},
            {395, 1, 1, 275}, {396, 2, 1, 277}, {397, 1, 1, 280}, {398, 2, 1, 278}, {400, 1, 3, 275}, {401, 2, 3, 277}, {402, 1, 3, 280},
            {403, 2, 3, 278}, {404, 3, 3, 68}, {456, 2, 1, 292}, {457, 1, 2, 293}, {458, 2, 2, 291}, {459, 3, 1, 297}, {460, 2, 1, 298},
            {461, 2, 1, 303}, {462, 2, 1, 304}, {463, 1, 1, 288}, {464, 1, 1, 289}, {465, 2, 1, 290}, {466, 1, 1, 299}, {467, 2, 1, 300},
            {468, 1, 1, 301}, {469, 1, 1, 305}, {470, 1, 1, 306}, {471, 3, 1, 310}, {472, 1, 1, 238}, {473, 1, 1, 287}, {475, 3, 1, 248},
            {476, 1, 2, 249}, {477, 2, 2, 256}, {478, 2, 1, 284}, {479, 2, 1, 285}, {480, 1, 2, 261}, {481, 2, 2, 262}, {482, 3, 2, 296},
            {483, 1, 1, 263}, {484, 2, 1, 264}, {485, 1, 1, 266}, {486, 2, 1, 268}, {487, 3, 1, 294}, {488, 3, 1, 295}, {489, 3, 1, 281},
            {490, 1, 1, 307}, {491, 1, 1, 314}, {492, 2, 1, 315}, {493, 3, 1, 316}, {494, 2, 1, 317}, {495, 1, 1, 276}, {496, 3, 1, 318},
            {497, 1, 1, 319}, {498, 2, 1, 320}, {499, 3, 1, 321}, {500, 3, 1, 322}, {575, 2, 1, 37}, {576, 1, 2, 53}, {577, 2, 3, 48},
            {578, 1, 1, 41}, {579, 3, 1, 57}, {580, 2, 2, 55}, {581, 1, 3, 56}, {582, 1, 3, 45}, {583, 3, 3, 52}, {584, 3, 2, 47}, {585, 1, 1, 50},
            {586, 1, 1, 33}, {587, 3, 1, 1}, {588, 2, 1, 32}, {589, 1, 1, 2}, {590, 3, 1, 3}, {591, 2, 1, 5}, {592, 1, 1, 7}, {593, 1, 1, 21},
            {594, 3, 1, 22}, {595, 2, 1, 11}, {596, 1, 2, 12}, {597, 1, 1, 14}, {598, 3, 1, 84}, {599, 1, 1, 64}, {600, 1, 1, 65}, {601, 2, 1, 66},
            {602, 3, 1, 67}, {603, 1, 1, 69}, {604, 1, 1, 80}, {605, 3, 1, 86}, {606, 2, 1, 72}, {607, 1, 3, 73}, {608, 2, 1, 83}, {609, 1, 3, 85},
            {613, 1, 1, 131}, {614, 2, 1, 143}, {615, 3, 1, 149}, {616, 1, 2, 142}, {617, 2, 2, 139}, {618, 1, 3, 135}, {619, 3, 1, 134},
            {620, 1, 1, 141}, {621, 2, 1, 138}, {622, 1, 2, 140}, {623, 2, 2, 136}, {624, 1, 3, 132}, {625, 1, 3, 133}, {626, 1, 3, 137},
            {627, 1, 3, 144}, {628, 2, 3, 146}, {629, 1, 1, 134}, {630, 1, 1, 154}, {631, 2, 1, 156}, {632, 3, 1, 157}, {633, 1, 2, 160},
            {634, 2, 2, 166}, {635, 3, 2, 162}, {636, 1, 3, 164}, {637, 2, 3, 172}, {638, 1, 1, 155}, {639, 1, 3, 159}, {640, 3, 1, 163},
            {641, 3, 2, 165}, {642, 2, 1, 167}, {643, 2, 2, 169}, {644, 2, 3, 170}, {645, 1, 2, 171}, {646, 1, 1, 106}, {647, 2, 1, 109},
            {648, 3, 1, 110}, {649, 1, 2, 111}, {650, 2, 2, 122}, {651, 3, 2, 124}, {652, 1, 3, 112}, {653, 2, 3, 125}, {654, 1, 1, 113},
            {655, 2, 1, 114}, {656, 3, 1, 116}, {657, 1, 2, 123}, {658, 1, 3, 107}, {659, 2, 3, 108}, {660, 1, 3, 115}, {661, 1, 3, 117},
            {662, 2, 3, 118}, {663, 1, 3, 120}, {664, 1, 1, 228}, {665, 2, 1, 229}, {666, 3, 1, 230}, {667, 1, 2, 254}, {668, 2, 2, 255},
            {670, 3, 3, 283}, {671, 3, 3, 282}, {672, 3, 2, 286}, {673, 1, 1, 308}, {674, 1, 1, 35}, {675, 2, 2, 231}, {676, 3, 2, 233},
            {677, 1, 2, 232}, {678, 1, 3, 234}, {679, 2, 3, 235}, {680, 1, 3, 257}, {681, 2, 3, 258}, {683, 2, 3, 309}, {684, 3, 3, 312},
            {685, 3, 3, 311}, {1245, 1, 2, 366}, {1246, 2, 2, 373}, {1247, 1, 3, 365}, {1248, 2, 3, 366}, {1249, 3, 3, 373}, {1253, 1, 2, 91},
            {1260, 1, 2, 368}, {1261, 1, 2, 274}, {1263, 2, 2, 381}, {1264, 3, 3, 63}, {1265, 1, 3, 368}, {1266, 2, 3, 274}, {1267, 1, 3, 372},
            {1268, 3, 3, 381}, {1281, 3, 2, 384}, {1282, 1, 2, 380}, {1283, 1, 1, 378}, {1284, 2, 1, 379}, {1285, 2, 2, 383}, {1286, 3, 2, 385},
            {1288, 2, 3, 62}, {1289, 1, 3, 103}, {1290, 1, 2, 34}, {1291, 2, 2, 58}, {1292, 1, 2, 60}, {1293, 1, 2, 61}
    };
    /**
     * The actions by id, in the order of the page's {@code Actions} (ascending ids).
     */
    private static final @NotNull Map<Integer, Action> ACTIONS = createActions();
    /**
     * The page's {@code Units}, race by race in page order.
     */
    private static final @NotNull List<Unit> UNITS = List.of(
            createUnit(Race.TERRAN,
                       7,
                       Group.GROUND,
                       "SND_SCV_READY",
                       createCard(CardLabel.GENERAL, 664, 665, 666, 677, 675, 676, 678, 679),
                       createCard(CardLabel.BASIC_STRUCTURE, 646, 647, 648, 649, 650, 651, 652, 653),
                       createCard(CardLabel.ADVANCED_STRUCTURE, 654, 655, 656, 657)),
            createUnit(Race.TERRAN, 0, Group.GROUND, "SND_MARINE_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668, 334)),
            createUnit(Race.TERRAN, 32, Group.GROUND, "SND_FIREBAT_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668, 334)),
            createUnit(Race.TERRAN,
                       1,
                       Group.GROUND,
                       "SND_GHOST_READY",
                       createCard(CardLabel.UNCLOAKED, 664, 665, 666, 667, 344, 335, 685, 668),
                       createCard(CardLabel.CLOAKED, 664, 665, 666, 667, 345, 335, 685, 668)),
            createUnit(Race.TERRAN, 34, Group.GROUND, "SND_MEDIC_READY", createCard(CardLabel.GENERAL, 664, 665, 667, 668, 1247, 1248, 1249)),
            createUnit(Race.TERRAN, 2, Group.GROUND, "SND_VULTURE_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668, 336)),
            createUnit(Race.TERRAN,
                       5,
                       Group.GROUND,
                       "SND_TANK_READY",
                       createCard(CardLabel.TANK_MODE, 664, 665, 666, 667, 668, 338),
                       createCard(CardLabel.SIEGE_MODE, 665, 666, 339)),
            createUnit(Race.TERRAN, 3, Group.GROUND, "SND_GOLIATH_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668)),
            createUnit(Race.TERRAN,
                       8,
                       Group.AIR,
                       "SND_PHOENIX_READY",
                       createCard(CardLabel.UNCLOAKED, 664, 665, 666, 667, 668, 344),
                       createCard(CardLabel.CLOAKED, 664, 665, 666, 667, 668, 345)),
            createUnit(Race.TERRAN, 11, Group.AIR, "SND_DROPSHIP_READY", createCard(CardLabel.GENERAL, 664, 665, 667, 668, 683, 684)),
            createUnit(Race.TERRAN, 12, Group.AIR, "SND_BATTLE_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668, 343)),
            createUnit(Race.TERRAN, 9, Group.AIR, "SND_VESSEL_READY", createCard(CardLabel.GENERAL, 664, 665, 667, 668, 340, 341, 342)),
            createUnit(Race.TERRAN, 58, Group.AIR, "SND_FRIGATE_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668)),
            createUnit(Race.TERRAN,
                       106,
                       Group.BASIC_BUILDING,
                       null,
                       createCard(CardLabel.GENERAL, 592, 658, 659, 671, 672),
                       createCard(CardLabel.LIFTED, 664, 665, 670, 658, 659)),
            createUnit(Race.TERRAN, 107, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 337)),
            createUnit(Race.TERRAN, 108, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 597)),
            createUnit(Race.TERRAN,
                       122,
                       Group.BASIC_BUILDING,
                       null,
                       createCard(CardLabel.GENERAL, 456, 463, 671),
                       createCard(CardLabel.LIFTED, 664, 665, 670)),
            createUnit(Race.TERRAN,
                       111,
                       Group.BASIC_BUILDING,
                       null,
                       createCard(CardLabel.GENERAL, 586, 587, 588, 1290, 671, 672),
                       createCard(CardLabel.LIFTED, 664, 665, 670)),
            createUnit(Race.TERRAN, 124, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 665, 666)),
            createUnit(Race.TERRAN, 112, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 472, 324, 1245, 1246, 1281)),
            createUnit(Race.TERRAN, 125, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 683, 684)),
            createUnit(Race.TERRAN,
                       113,
                       Group.ADVANCED_BUILDING,
                       null,
                       createCard(CardLabel.GENERAL, 589, 590, 591, 671, 672, 663),
                       createCard(CardLabel.LIFTED, 664, 665, 670, 663)),
            createUnit(Race.TERRAN, 120, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 473, 327, 328, 1282)),
            createUnit(Race.TERRAN,
                       114,
                       Group.ADVANCED_BUILDING,
                       null,
                       createCard(CardLabel.GENERAL, 593, 595, 594, 596, 1291, 671, 672, 660),
                       createCard(CardLabel.LIFTED, 664, 665, 670, 660)),
            createUnit(Race.TERRAN, 123, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 457, 464, 458, 465)),
            createUnit(Race.TERRAN, 115, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 332, 478)),
            createUnit(Race.TERRAN,
                       116,
                       Group.ADVANCED_BUILDING,
                       null,
                       createCard(CardLabel.GENERAL, 326, 330, 475, 661, 662, 671),
                       createCard(CardLabel.LIFTED, 664, 665, 670, 661, 662)),
            createUnit(Race.TERRAN, 118, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 331, 479)),
            createUnit(Race.TERRAN, 117, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 325, 333, 476, 477)),
            createUnit(Race.PROTOSS,
                       64,
                       Group.GROUND,
                       "SND_PROBE_READY",
                       createCard(CardLabel.GENERAL, 664, 665, 666, 675, 676, 678, 679),
                       createCard(CardLabel.BASIC_STRUCTURE, 630, 631, 632, 633, 634, 635, 636, 637),
                       createCard(CardLabel.ADVANCED_STRUCTURE, 638, 639, 640, 641, 642, 643, 644, 645)),
            createUnit(Race.PROTOSS, 65, Group.GROUND, "SND_ZEALOT_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668)),
            createUnit(Race.PROTOSS, 66, Group.GROUND, "SND_DRAGOON_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668)),
            createUnit(Race.PROTOSS, 67, Group.GROUND, "SND_TEMPLAR_READY", createCard(CardLabel.GENERAL, 664, 665, 667, 668, 400, 401, 404)),
            createUnit(Race.PROTOSS, 61, Group.GROUND, "SND_DARK_TEMPLAR_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668, 1264)),
            createUnit(Race.PROTOSS, 83, Group.GROUND, "SND_TRILOBYTE_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668, 609)),
            createUnit(Race.PROTOSS, 68, Group.GROUND, "SND_ARCHON_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668)),
            createUnit(Race.PROTOSS, 63, Group.GROUND, "SND_DARCHON_READY", createCard(CardLabel.GENERAL, 664, 665, 667, 668, 1267, 1266, 1268)),
            createUnit(Race.PROTOSS, 69, Group.AIR, "SND_SHUTTLE_READY", createCard(CardLabel.GENERAL, 664, 665, 667, 668, 683, 684)),
            createUnit(Race.PROTOSS, 84, Group.AIR, "SND_WITNESS_READY", createCard(CardLabel.GENERAL, 664, 665, 667, 668)),
            createUnit(Race.PROTOSS, 70, Group.AIR, "SND_SCOUT_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668)),
            createUnit(Race.PROTOSS, 60, Group.AIR, "SND_CORSAIR_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668, 1265)),
            createUnit(Race.PROTOSS, 72, Group.AIR, "SND_CARRIER_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668, 607)),
            createUnit(Race.PROTOSS, 71, Group.AIR, "SND_ARBITER_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668, 402, 403)),
            createUnit(Race.PROTOSS, 154, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 599, 672)),
            createUnit(Race.PROTOSS, 160, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 600, 601, 602, 1293, 672)),
            createUnit(Race.PROTOSS, 166, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 469, 461, 471)),
            createUnit(Race.PROTOSS, 172, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 673)),
            createUnit(Race.PROTOSS, 164, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 470, 462, 489)),
            createUnit(Race.PROTOSS, 162, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 665, 666)),
            createUnit(Race.PROTOSS, 155, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 603, 608, 598, 672)),
            createUnit(Race.PROTOSS, 171, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 491, 492, 493)),
            createUnit(Race.PROTOSS, 159, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 495, 494)),
            createUnit(Race.PROTOSS, 167, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 604, 606, 605, 1292, 672)),
            createUnit(Race.PROTOSS, 169, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 497, 498, 499, 1260, 1285)),
            createUnit(Race.PROTOSS, 170, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 397, 398, 500)),
            createUnit(Race.PROTOSS, 163, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 490)),
            createUnit(Race.PROTOSS, 165, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 395, 396, 496, 1261, 1263, 1286)),
            createUnit(Race.ZERG, 35, Group.GROUND, "SND_LARVA_WHAT", createCard(CardLabel.GENERAL, 578, 575, 579, 576, 580, 584, 582, 577, 583)),
            createUnit(Race.ZERG,
                       41,
                       Group.GROUND,
                       "SND_DRONE_READY",
                       createCard(CardLabel.GENERAL, 664, 665, 666, 675, 676, 680, 681, 372),
                       createCard(CardLabel.BASIC_STRUCTURE, 613, 614, 615, 616, 617, 618),
                       createCard(CardLabel.ADVANCED_STRUCTURE, 620, 621, 619, 622, 623),
                       createCard(CardLabel.BURROWED, 373)),
            createUnit(Race.ZERG,
                       37,
                       Group.GROUND,
                       "SND_ZERGLING_READY",
                       createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668, 372),
                       createCard(CardLabel.BURROWED, 373)),
            createUnit(Race.ZERG,
                       38,
                       Group.GROUND,
                       "SND_HYDRA_READY",
                       createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668, 372, 1289),
                       createCard(CardLabel.BURROWED, 373)),
            createUnit(Race.ZERG,
                       103,
                       Group.GROUND,
                       "SND_LURKER_READY",
                       createCard(CardLabel.GENERAL, 664, 665, 667, 668, 372),
                       createCard(CardLabel.BURROWED, 665, 666, 373)),
            createUnit(Race.ZERG,
                       46,
                       Group.GROUND,
                       "SND_DEFILER_READY",
                       createCard(CardLabel.GENERAL, 664, 665, 667, 668, 372, 376, 378, 379),
                       createCard(CardLabel.BURROWED, 373)),
            createUnit(Race.ZERG, 39, Group.GROUND, "SND_ULTRA_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668)),
            createUnit(Race.ZERG, 40, Group.GROUND, "SND_BROODLING_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668)),
            createUnit(Race.ZERG,
                       50,
                       Group.GROUND,
                       "SND_BUGGUY_READY",
                       createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668, 372),
                       createCard(CardLabel.BURROWED, 373)),
            createUnit(Race.ZERG, 42, Group.AIR, "SND_OVERLORD_READY", createCard(CardLabel.GENERAL, 664, 665, 667, 668, 683, 684)),
            createUnit(Race.ZERG, 43, Group.AIR, "SND_MUTALID_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668, 581, 1288)),
            createUnit(Race.ZERG, 47, Group.AIR, "SND_AVENGER_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668)),
            createUnit(Race.ZERG, 45, Group.AIR, "SND_QUEEN_READY", createCard(CardLabel.GENERAL, 664, 665, 667, 668, 377, 374, 375, 381)),
            createUnit(Race.ZERG, 44, Group.AIR, "SND_GUARDIAN_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668)),
            createUnit(Race.ZERG, 62, Group.AIR, "SND_DEVOURER_READY", createCard(CardLabel.GENERAL, 664, 665, 666, 667, 668)),
            createUnit(Race.ZERG, 131, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 674, 672, 364, 624)),
            createUnit(Race.ZERG, 139, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 466, 467, 459)),
            createUnit(Race.ZERG, 143, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 627, 628)),
            createUnit(Race.ZERG, 142, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 483, 484)),
            createUnit(Race.ZERG, 144, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 665, 666)),
            createUnit(Race.ZERG, 146, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 665, 666)),
            createUnit(Race.ZERG, 135, Group.BASIC_BUILDING, null, createCard(CardLabel.GENERAL, 485, 486, 1253)),
            createUnit(Race.ZERG, 132, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 674, 672, 364, 480, 481, 482, 625)),
            createUnit(Race.ZERG, 141, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 468, 460, 626)),
            createUnit(Race.ZERG, 138, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 366, 370, 487)),
            createUnit(Race.ZERG, 133, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 674, 672, 364, 480, 481, 482)),
            createUnit(Race.ZERG, 137, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 468, 460)),
            createUnit(Race.ZERG, 134, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 629)),
            createUnit(Race.ZERG, 136, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 369, 371, 488)),
            createUnit(Race.ZERG, 140, Group.ADVANCED_BUILDING, null, createCard(CardLabel.GENERAL, 1283, 1284)),
            createUnit(Race.ZERG,
                       130,
                       Group.ADVANCED_BUILDING,
                       null,
                       createCard(CardLabel.GENERAL, 585, 672, 671),
                       createCard(CardLabel.LIFTED, 664, 665, 670))
    );

    /**
     * The race tabs, in the page's order (and the order of its {@code Units} keys).
     */
    enum Race {
        /**
         * The Terran race tab.
         */
        TERRAN("lobbyScreen_terran"),
        /**
         * The Protoss race tab.
         */
        PROTOSS("lobbyScreen_protoss"),
        /**
         * The Zerg race tab.
         */
        ZERG("lobbyScreen_zerg");

        private final @NotNull String label;

        Race(@NotNull String label) {
            this.label = label;
        }

        /**
         * @return the tab's string id
         */
        @NotNull String getLabel() {
            return this.label;
        }
    }

    /**
     * The unit groups of a race (a unit's {@code type}), in the page's order.
     */
    enum Group {
        /**
         * The ground-unit group.
         */
        GROUND("bnet119_hotkeysPanel_groudUnits_label"),
        /**
         * The air-unit group.
         */
        AIR("bnet119_hotkeysPanel_airUnits_label"),
        /**
         * The basic-building group.
         */
        BASIC_BUILDING("bnet119_hotkeysPanel_basicBuildings_label"),
        /**
         * The advanced-building group.
         */
        ADVANCED_BUILDING("bnet119_hotkeysPanel_advancedBuildings_label");

        private final @NotNull String label;

        Group(@NotNull String label) {
            this.label = label;
        }

        /**
         * @return the group title's string id
         */
        @NotNull String getLabel() {
            return this.label;
        }
    }

    /**
     * The titles of the action cards.
     */
    enum CardLabel {
        /**
         * The general command card.
         */
        GENERAL("bnet119_hotkeysPanel_general_actionCard"),
        /**
         * The basic-structure command card.
         */
        BASIC_STRUCTURE("bnet119_hotkeysPanel_basicStructure_actionCard"),
        /**
         * The advanced-structure command card.
         */
        ADVANCED_STRUCTURE("bnet119_hotkeysPanel_advancedStructure_actionCard"),
        /**
         * The tank-mode command card.
         */
        TANK_MODE("bnet119_hotkeysPanel_tankMode_actionCard"),
        /**
         * The siege-mode command card.
         */
        SIEGE_MODE("bnet119_hotkeysPanel_siegeMode_actionCard"),
        /**
         * The uncloaked command card.
         */
        UNCLOAKED("bnet119_hotkeysPanel_uncloaked_actionCard"),
        /**
         * The cloaked command card.
         */
        CLOAKED("bnet119_hotkeysPanel_cloaked_actionCard"),
        /**
         * The lifted-building command card.
         */
        LIFTED("bnet119_hotkeysPanel_lifted_actionCard"),
        /**
         * The burrowed command card.
         */
        BURROWED("bnet119_hotkeysPanel_burrowed_actionCard");

        private final @NotNull String label;

        CardLabel(@NotNull String label) {
            this.label = label;
        }

        /**
         * @return the card title's string id
         */
        @NotNull String getLabel() {
            return this.label;
        }
    }

    /**
     * A command-card action identifier, grid position and icon.
     */
    static final class Action {
        private final int identifier;
        private final int column;
        private final int row;
        private final int icon;

        private Action(int identifier, int column, int row, int icon) {
            this.identifier = identifier;
            this.column     = column;
            this.row        = row;
            this.icon       = icon;
        }

        /**
         * @return the one-based {@code stat_txt} button id
         */
        int getIdentifier() {
            return this.identifier;
        }

        /**
         * @param unit the unit whose card shows the action
         * @return the one-based card column
         */
        int getColumn(@NotNull Unit unit) {
            return this.isHatcheryRallyPoint(unit) ? RALLY_POINT_COLUMN : this.column;
        }

        /**
         * @param unit the unit whose card shows the action
         * @return the one-based card row
         */
        int getRow(@NotNull Unit unit) {
            return this.isHatcheryRallyPoint(unit) ? RALLY_POINT_ROW : this.row;
        }

        /**
         * @param unit the selected unit
         * @return the command-icon file name
         */
        @NotNull String getIcon(@NotNull Unit unit) {
            int frame = this.icon;

            if (unit.unitType == PROBE && this.identifier == BUILD_STRUCTURE) {
                frame = PROBE_BUILD_STRUCTURE_ICON;
            } else if (unit.unitType == PROBE && this.identifier == BUILD_ADVANCED_STRUCTURE) {
                frame = PROBE_BUILD_ADVANCED_STRUCTURE_ICON;
            }

            return ICON_PREFIX + frame + ICON_SUFFIX;
        }

        /**
         * @param unit the unit whose card shows the action
         * @return {@code true} if the action is the rally point on a Hatchery, Lair or Hive card, which moves it to another cell, {@code false} otherwise
         */
        private boolean isHatcheryRallyPoint(@NotNull Unit unit) {
            return this.identifier == RALLY_POINT && (unit.unitType == HATCHERY || unit.unitType == LAIR || unit.unitType == HIVE);
        }
    }

    /**
     * One action card of a unit: a title and the actions it holds.
     */
    static final class Card {
        private final @NotNull CardLabel label;
        private final @NotNull List<Action> actions;

        private Card(@NotNull CardLabel label, @NotNull List<Action> actions) {
            this.label   = label;
            this.actions = actions;
        }

        /**
         * @return the title
         */
        @NotNull CardLabel getLabel() {
            return this.label;
        }

        /**
         * @return the actions in the page's order
         */
        @NotNull List<Action> getActions() {
            return this.actions;
        }
    }

    /**
     * A unit's hotkey grouping, ready sound and command cards.
     */
    static final class Unit {
        private final @NotNull Race race;
        private final int unitType;
        private final @NotNull Group group;

        private final @Nullable String sound;
        private final @NotNull List<Card> cards;

        private Unit(@NotNull Race race, int unitType, @NotNull Group group, @Nullable String sound, @NotNull List<Card> cards) {
            this.race     = race;
            this.unitType = unitType;
            this.group    = group;

            this.sound = sound;
            this.cards = cards;
        }

        /**
         * @return the race
         */
        @NotNull Race getRace() {
            return this.race;
        }

        /**
         * @return the {@code units.dat} type: the {@code n} of the page's {@code FIRST_UNIT_STRING_n} and of its icon
         */
        int getUnitType() {
            return this.unitType;
        }

        /**
         * @return the group
         */
        @NotNull Group getGroup() {
            return this.group;
        }

        /**
         * @return the sound id the page plays when the unit is chosen (the unit's ready sound), or {@code null} for a building
         */
        @Nullable String getSound() {
            return this.sound;
        }

        /**
         * @return the command-icon file name
         */
        @NotNull String getIcon() {
            return ICON_PREFIX + this.unitType + ICON_SUFFIX;
        }

        /**
         * @return the action cards
         */
        @NotNull List<Card> getCards() {
            return this.cards;
        }
    }

    /**
     * @return the page's actions by id, in ascending id order
     */
    static @NotNull Map<Integer, Action> getActions() {
        return ACTIONS;
    }

    /**
     * @return the page's units, race by race in page order
     */
    static @NotNull List<Unit> getUnits() {
        return UNITS;
    }

    /**
     * Counts the units whose cards hold an action, as the page's rebind panel does for its "units share this command" line.
     *
     * @param action the action
     * @return the number of units of every race with a card that holds the action
     */
    static int countUnits(@NotNull Action action) {
        int count = 0;

        for (Unit unit : UNITS) {
            for (Card card : unit.cards) {
                if (card.actions.contains(action)) {
                    count++;

                    break;
                }
            }
        }

        return count;
    }

    private static @NotNull Map<Integer, Action> createActions() {
        Map<Integer, Action> actions = new LinkedHashMap<>();

        for (int[] entry : ACTION_TABLE) {
            actions.put(entry[ACTION_IDENTIFIER_INDEX],
                        new Action(entry[ACTION_IDENTIFIER_INDEX], entry[ACTION_COLUMN_INDEX], entry[ACTION_ROW_INDEX], entry[ACTION_ICON_INDEX]));
        }

        return Collections.unmodifiableMap(actions);
    }

    private static @NotNull Card createCard(@NotNull CardLabel label, int @NotNull ... identifiers) {
        List<Action> actions = new ArrayList<>(identifiers.length);

        for (int identifier : identifiers) {
            Action action = ACTIONS.get(identifier);

            if (action == null) {
                throw Exceptions.newIllegalStateException("The hotkey page has no action {0}.", identifier);
            }

            actions.add(action);
        }

        return new Card(label, List.copyOf(actions));
    }

    private static @NotNull Unit createUnit(@NotNull Race race,
                                            int unitType,
                                            @NotNull Group group,
                                            @Nullable String sound,
                                            @NotNull Card @NotNull ... cards) {
        return new Unit(race, unitType, group, sound, List.of(cards));
    }

    private HotkeyPanelModel() {
    }
}
