package org.fogofwarlabs.sc4j.scr.client.ui.text;

import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * Resolves force names for lobby headers, scores, messaging and chat.
 * Team games use the localized team name; other games prefer the map name and then the localized force name.
 */
public final class ForceNamesUtils {
    /**
     * {@code FORC}: eight player force bytes, then the four force name string WORDs.
     */
    private static final int NAME_OFFSET = 8;

    /**
     * Reads the map name of a force.
     *
     * @param map   the scenario
     * @param force the force, {@code 1..4}
     * @return the map's string for the force, or {@code null} when the map names none (WORD {@code 0}, no {@code FORC})
     */
    public static @Nullable String getMapForceName(@NotNull ChkFile map, int force) {
        byte[] forces = map.getSection(ChkFile.FORCE_SECTION);
        int offset = NAME_OFFSET + (force - 1) * Short.BYTES;

        if (forces == null || force < 1 || force > PlayerTable.FORCE_COUNT || offset + Short.BYTES > forces.length) {
            return null;
        }

        int index = Byte.toUnsignedInt(forces[offset]) | Byte.toUnsignedInt(forces[offset + 1]) << Byte.SIZE;

        if (index == 0) {
            return null;
        }

        List<String> strings = map.getStrings();

        return index <= strings.size() ? strings.get(index - 1) : "";
    }

    /**
     * Resolves a force's name.
     *
     * @param network  the {@code network.json} text of a key in the interface locale; the callback returns non-null text for every supplied key
     * @param teamGame whether the game is a team game
     * @param mapName  the map's name of the force ({@link #getMapForceName}, or a lobby's received name), {@code null} or empty for none
     * @param force    the force, {@code 1..4}
     * @return the name
     */
    public static @NotNull String resolveForceName(@NotNull Function<String, @NotNull String> network,
                                                   boolean teamGame,
                                                   @Nullable String mapName, int force) {
        if (teamGame) {
            return network.apply("strTEAM" + force);
        }

        if (mapName != null && !mapName.isEmpty()) {
            return mapName;
        }

        return network.apply("strFORCE" + force);
    }


    private ForceNamesUtils() {
    }
}
