package org.fogofwarlabs.sc4j.scr.game.visibility;

import org.fogofwarlabs.sc4j.scr.game.frame.GameFrame;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;

/**
 * Holds the vision of the local view: the replay vision mask, the local comparison mask and whether the entire map is revealed.
 * <p>
 * The simulation compares sprite and tile visibility with the comparison mask: a sprite the comparison mask stops seeing leaves the local selection,
 * stops flashing and, for a building, leaves a fog sprite. A game view shows the players of the comparison mask, the local player's bit. A replay or
 * observer view shows the players of the replay vision mask and, while the map is revealed, every tile and every neutral sprite. A replay or observer
 * view starts with every player's vision, the replay viewer's vision dialog changes it, and a player removed from play leaves it. A restarted replay
 * keeps the reveal and the players the dialog unchecked. Every client keeps its own values, so they are not part of the synchronized game state.
 */
public final class LocalVision {
    /**
     * The mask of the eight playable players.
     */
    public static final int ALL_PLAYERS = 0xFF;

    /**
     * The bits of the checked players the vision dialog stores.
     */
    private static final int DIALOG_MASK = 0xFFFF;
    /**
     * The local slot of a game without a selection yet, which is no observer's.
     */
    private static final int NO_LOCAL_SLOT = -1;

    private final @NotNull Game game;

    private int replayMask;
    private int comparisonMask;
    private boolean revealed;

    private int hiddenPlayers;

    /**
     * Creates a {@link LocalVision} instance that shows no player's vision and does not reveal the map.
     *
     * @param game the game whose replay flag and local slot choose the rule of the view
     */
    public LocalVision(@NotNull Game game) {
        this.game = game;

        this.replayMask     = 0;
        this.comparisonMask = 0;
        this.revealed       = false;

        this.hiddenPlayers = 0;
    }

    /**
     * @return the replay vision mask, the players a replay or observer view shows
     */
    public int getReplayMask() {
        return this.replayMask;
    }

    /**
     * @return the local comparison mask the simulation compares sprite and tile visibility with
     */
    public int getComparisonMask() {
        return this.comparisonMask;
    }

    /**
     * Sets the local comparison mask: the local player's bit in a game.
     *
     * @param comparisonMask the comparison mask
     */
    public void setComparisonMask(int comparisonMask) {
        this.comparisonMask = comparisonMask;
    }

    /**
     * @return {@code true} if "Reveal Entire Map" is on, which only a replay turns on, {@code false} otherwise
     */
    public boolean isRevealed() {
        return this.revealed;
    }

    /**
     * @return {@code true} if the local slot is an observer's ({@link PlayerTable#isObserverSlot}), {@code false} otherwise
     */
    public boolean isObserver() {
        UnitSelection selection = this.game.getSelection();

        return selection != null && PlayerTable.isObserverSlot(selection.getLocalPlayerIndex());
    }

    /**
     * @return {@code true} if the world plays a replay or the local slot is an observer's ({@link PlayerTable#isReplayOrObserver}), {@code false} otherwise
     */
    public boolean isReplayOrObserver() {
        UnitSelection selection = this.game.getSelection();

        return PlayerTable.isReplayOrObserver(this.game.isReplayPlayback(), selection == null ? NO_LOCAL_SLOT : selection.getLocalPlayerIndex());
    }

    /**
     * @return the eight players whose vision the view shows: the replay vision mask in a replay or observer view, the comparison mask in a game
     */
    public int getViewMask() {
        return (this.isReplayOrObserver() ? this.replayMask : this.comparisonMask) & ALL_PLAYERS;
    }


    /**
     * Tests whether the players of the local view do not see a tile, regardless of "Reveal Entire Map": in a replay or observer view when no player
     * of the replay vision mask sees it, in a game when a player of the comparison mask does not see it.
     *
     * @param tileFlags the tile flags, whose low byte has a bit for each player that does not see the tile
     * @return {@code true} if the tile is unseen, {@code false} otherwise
     */
    public boolean isTileUnseen(int tileFlags) {
        return this.isTileUnseen(tileFlags, this.isReplayOrObserver());
    }

    private boolean isTileUnseen(int tileFlags, boolean replayOrObserver) {
        if (replayOrObserver) {
            return (~tileFlags & this.replayMask & ALL_PLAYERS) == 0;
        }

        return (tileFlags & this.comparisonMask) != 0;
    }

    /**
     * Tests whether a tile is hidden from the local view: it is unseen ({@link #isTileUnseen(int)}) and, in a replay or observer view, the map is not
     * revealed.
     *
     * @param tileFlags the tile flags, whose low byte has a bit for each player that does not see the tile
     * @return {@code true} if the tile is hidden, {@code false} otherwise
     */
    public boolean isTileHidden(int tileFlags) {
        boolean replayOrObserver = this.isReplayOrObserver();

        return this.isTileUnseen(tileFlags, replayOrObserver) && !(replayOrObserver && this.revealed);
    }

    /**
     * Starts the vision of a replay or observer view: every player's vision, and the map not revealed.
     */
    public void startReplayOrObserver() {
        this.replayMask     = ALL_PLAYERS;
        this.comparisonMask = ALL_PLAYERS;
        this.revealed       = false;
    }

    /**
     * Applies the restart of a replay to the vision of the restarted replay, which starts with every player's vision: the reveal and the players the
     * vision dialog unchecked carry over from the vision before the restart, and those players leave the replay vision mask and the comparison mask
     * again.
     *
     * @param previous the vision of the replay before the restart
     */
    public void restartReplay(@NotNull LocalVision previous) {
        this.revealed      = previous.revealed;
        this.hiddenPlayers = previous.hiddenPlayers;

        this.replayMask &= ~this.hiddenPlayers;
        this.comparisonMask &= ~this.hiddenPlayers;
    }

    /**
     * Applies OK of the vision dialog: the checked players become the replay vision mask and the comparison mask, and a player the dialog unchecked
     * stays hidden after a replay restart until the dialog checks it again. In a replay, a checked "Reveal Entire Map" reveals the map, shows every
     * player's vision and forgets the unchecked players, an unchecked one ends the reveal, and every player out of the game
     * ({@link Player#isFinished}) leaves the vision; outside a replay the reveal stays as it is. A replay or observer view then updates the sprite
     * visibility of every unit ({@link SpriteVisibility#updateUnits}).
     *
     * @param mask   the checked players, a bit for each player
     * @param reveal whether "Reveal Entire Map" is checked
     */
    public void acceptDialog(int mask, boolean reveal) {
        int checked = mask & DIALOG_MASK;
        int unchecked = this.replayMask & ~checked & ALL_PLAYERS;
        int rechecked = checked & ~this.replayMask & ALL_PLAYERS;

        this.hiddenPlayers  = (this.hiddenPlayers | unchecked) & ~rechecked;
        this.replayMask     = checked;
        this.comparisonMask = checked;

        if (this.game.isReplayPlayback()) {
            this.revealed = reveal;

            if (reveal) {
                this.replayMask     = ALL_PLAYERS;
                this.comparisonMask = ALL_PLAYERS;
                this.hiddenPlayers  = 0;
            }

            for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
                Player record = this.game.getPlayerTable().getPlayer(player);

                if (record != null && record.isFinished()) {
                    this.removePlayer(player);
                }
            }
        }

        if (this.isReplayOrObserver()) {
            GameFrame frame = this.game.getFrame();

            this.game.getSpriteVisibility().updateUnits(frame == null ? 0 : frame.getInstructionLimit());
        }
    }

    /**
     * Takes a player removed from play out of the vision of a replay or observer view; a game view keeps its vision.
     *
     * @param player the removed player
     */
    public void removePlayer(int player) {
        if (!this.isReplayOrObserver()) {
            return;
        }

        this.replayMask &= ~(1 << player);
        this.comparisonMask &= ~(1 << player);
    }
}
