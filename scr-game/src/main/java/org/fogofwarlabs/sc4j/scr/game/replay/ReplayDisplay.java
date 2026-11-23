package org.fogofwarlabs.sc4j.scr.game.replay;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.frame.GameExecutionScope;
import org.fogofwarlabs.sc4j.scr.game.presentation.GameDisplayState;
import org.fogofwarlabs.sc4j.scr.game.presentation.PaletteAnimation;
import org.fogofwarlabs.sc4j.scr.game.presentation.ReplayOverlay;
import org.fogofwarlabs.sc4j.scr.game.presentation.TimedIndicators;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;

/**
 * Executes the represented fresh replay display path.
 * Host display invocations remain separate from simulation frame advancement.
 */
public final class ReplayDisplay {
    /**
     * The host ticks between two palette steps.
     */
    public static final int PALETTE_PERIOD = 10;

    private final @NotNull Game game;
    private final @NotNull GameDisplayState displayState;

    private final @NotNull PaletteAnimation palette;
    private final @NotNull TimedIndicators indicators;
    private final @NotNull ReplayOverlay overlay;
    private final int totalFrames;
    private final int recordedFramePeriod;

    private int lastPaletteTick;

    /**
     * Creates a {@link ReplayDisplay} instance with established storage for the fresh offline replay lifecycle.
     * The local UI selection is independent of each replay issuer's command selection.
     *
     * @param game                the game world
     * @param displayState        the retained panel and statistics state
     * @param palette             the retained palette and cycle records
     * @param indicators          the initialized host notification indicators
     * @param totalFrames         the recorded total frame DWORD
     * @param recordedFramePeriod the recorded speed table entry
     * @param lastPaletteTick     the last palette timer tick
     */
    public ReplayDisplay(@NotNull Game game,
                         @NotNull GameDisplayState displayState,
                         @NotNull PaletteAnimation palette,
                         @NotNull TimedIndicators indicators,
                         int totalFrames,
                         int recordedFramePeriod,
                         int lastPaletteTick) {
        this.game         = game;
        this.displayState = displayState;

        this.palette             = palette;
        this.indicators          = indicators;
        this.overlay             = new ReplayOverlay(0, 0, 0);
        this.totalFrames         = totalFrames;
        this.recordedFramePeriod = recordedFramePeriod;

        this.lastPaletteTick = lastPaletteTick;
    }

    /**
     * @return the retained palette after display updates
     */
    public @NotNull PaletteAnimation getPalette() {
        return this.palette;
    }


    /**
     * Runs the null-local-selection display path and the subsequent host-timed effects.
     *
     * @param hostTicks         the current host tick count
     * @param minimized         the host window's minimized state
     * @param colorCycleEnabled the retained ColorCycle setting
     * @param phaseGate         the retained phase gate
     * @param placementActive   the retained placement-preview state
     * @throws UnsupportedOperationException if the local UI selection is not the represented empty one, a placement preview is active or the game has no execution scope
     */
    public void update(int hostTicks, boolean minimized, boolean colorCycleEnabled, int phaseGate, int placementActive) {
        if (!this.displayState.isPanelSelectionInitialized() || this.displayState.getSelectionCount() != 0 ||
            this.displayState.getSelectedIdentity() != 0) {
            throw Exceptions.newUnsupportedOperationException("Native replay display requires the represented empty local UI selection.");
        }

        this.displayState.refreshEmptySelectionStatistics();
        this.displayState.finishReplayDisplayPanelFrame(this.game.getCurrentFrame(), this.totalFrames, 0, this.recordedFramePeriod);
        this.overlay.update();

        if (placementActive != 0) {
            throw Exceptions.newUnsupportedOperationException("Native placement-preview display requires its original cursor and terrain context.");
        }

        GameExecutionScope executionScope = this.game.getExecutionScope();

        if (executionScope == null) {
            throw Exceptions.newUnsupportedOperationException("Native display executionScope mismatch requires its original effect path.");
        }

        this.updatePalette(hostTicks, minimized, colorCycleEnabled, phaseGate);
        this.indicators.update(hostTicks);
    }

    /**
     * Advances color cycling at most once per {@value #PALETTE_PERIOD} host ticks while enabled and unpaused.
     *
     * @param hostTicks         the current host tick count
     * @param minimized         the host window's minimized state
     * @param colorCycleEnabled the ColorCycle setting
     * @param phaseGate         the paused state
     * @return {@code true} if the palette advanced, {@code false} otherwise
     */
    public boolean updatePalette(int hostTicks, boolean minimized, boolean colorCycleEnabled, int phaseGate) {
        if (minimized || !colorCycleEnabled || phaseGate != 0 || Integer.compareUnsigned(hostTicks - this.lastPaletteTick, PALETTE_PERIOD) < 0) {
            return false;
        }

        this.lastPaletteTick = hostTicks;
        this.palette.update();

        return true;
    }
}
