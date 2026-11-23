package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitCreationContext;
import org.fogofwarlabs.sc4j.scr.game.visibility.LocalVision;
import org.jetbrains.annotations.NotNull;

/**
 * Raises the sounds the simulation plays at a unit or at a map position.
 * <p>
 * A sound plays only where the local view sees it. The consumer applies channel, volume and timing rules. Callers choose random sounds through the
 * synchronized random source; this system draws no random number.
 */
public final class UnitSounds {
    /**
     * The sound race index for a unit type without a race group flag.
     */
    public static final int RACE_NONE = 3;

    /**
     * The sound race index of a Zerg unit type.
     */
    private static final int RACE_ZERG = 0;
    /**
     * The sound race index of a Terran unit type.
     */
    private static final int RACE_TERRAN = 1;
    /**
     * The sound race index of a Protoss unit type.
     */
    private static final int RACE_PROTOSS = 2;

    /**
     * The mask of a position WORD.
     */
    private static final int WORD_MASK = 0xFFFF;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitSounds} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitSounds(@NotNull Game game) {
        this.game = game;
    }

    /**
     * @param unit the unit
     * @return the sound race index of the first StarEdit group flag in Zerg, Terran, Protoss order, or {@link #RACE_NONE}
     * @throws NullPointerException if the world was built without game data
     */
    public int getRace(@NotNull Unit unit) {
        int flags = this.game.requireGameData().getUnitTypes().getGroupFlags(unit.getUnitId());

        if ((flags & UnitGroupFlags.ZERG) != 0) {
            return RACE_ZERG;
        }

        if ((flags & UnitGroupFlags.TERRAN) != 0) {
            return RACE_TERRAN;
        }

        return (flags & UnitGroupFlags.PROTOSS) != 0 ? RACE_PROTOSS : RACE_NONE;
    }


    /**
     * Plays a sound at a unit the local view sees: in a replay or observer view a player of the replay vision mask sees its sprite, in a game the
     * local player owns it or a player of the comparison mask sees its sprite. A unit without a sprite plays at the map origin.
     *
     * @param soundId the sound table index
     * @param unit    the source unit
     */
    public void play(int soundId, @NotNull Unit unit) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite != null && !this.isSeen(unit, sprite)) {
            return;
        }

        this.game.getInterfaceLink().getSoundEvents().add(soundId,
                                                          sprite == null ? 0 : sprite.getX(), sprite == null ? 0 : sprite.getY(),
                                                          unit.getUnitId());
    }

    /**
     * Plays a sound at a map position inside the map that the local view does not hide ({@link LocalVision#isTileHidden}). Sound {@code 0} plays
     * nothing.
     *
     * @param soundId   the sound table index
     * @param positionX the horizontal position WORD in pixels
     * @param positionY the vertical position WORD in pixels
     */
    public void playAt(int soundId, int positionX, int positionY) {
        GameMap map = this.game.getMap();

        if (soundId == 0
            || (positionX & WORD_MASK) >= map.getWidth() * TerrainTileset.TILE_SIZE
            || (positionY & WORD_MASK) >= map.getHeight() * TerrainTileset.TILE_SIZE) {
            return;
        }

        if (map.hasTileFlags() && this.game.getLocalVision().isTileHidden(map.getTileFlagsAtPosition(positionX, positionY))) {
            return;
        }

        this.game.getInterfaceLink().getSoundEvents().add(soundId, positionX, positionY, ScrUnitIds.NONE);
    }

    private boolean isSeen(@NotNull Unit unit, @NotNull Sprite sprite) {
        LocalVision vision = this.game.getLocalVision();
        int visibility = sprite.getVisibilityFlags() & LocalVision.ALL_PLAYERS;

        if (vision.isReplayOrObserver()) {
            return (visibility & vision.getReplayMask()) != 0;
        }

        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

        if (context != null && unit.getOwnerIndex() == context.getLocalPlayer()) {
            return true;
        }

        return (visibility & vision.getComparisonMask()) != 0;
    }
}
