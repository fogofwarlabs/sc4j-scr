package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.io.AuditedResourceUtils;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Retains display controls, local selection and panel callback state shared by game and host updates. Compatibility arrays are copied on input and
 * produced as snapshots on output; scalar fields are authoritative.
 */
public final class GameDisplayState {
    /**
     * The number of display refresh controls.
     */
    private static final int REFRESH_CONTROL_COUNT = 6;
    /**
     * The number of empty-selection panel controls.
     */
    private static final int SELECTION_CONTROL_COUNT = 10;
    /**
     * The number of replay display controls.
     */
    private static final int REPLAY_CONTROL_COUNT = 9;
    /**
     * The number of statistics panel children.
     */
    private static final int STATISTICS_CHILD_COUNT = 57;
    /**
     * The statistics root identity of a fresh replay.
     */
    private static final int INITIAL_STATISTICS_IDENTITY = 16;
    /**
     * The SHA-256 digest of the audited statistics panel definition.
     */
    private static final @NotNull String STATISTICS_DEFINITION_DIGEST = "91ab528ba760c255e970fe7b2e04c1ca311883ea6ee45c1f552492b7b9d93d09";

    /**
     * The value of a set redraw or refresh flag.
     */
    private static final int FLAG_SET = 1;
    /**
     * The replay-mode control of a replay.
     */
    private static final int REPLAY_MODE_ENABLED = 1;
    /**
     * The pause state the replay buttons receive while the paused button set is displayed.
     */
    private static final int PAUSED_STATE = 1;
    /**
     * The pause state the replay buttons receive while the playing button set is displayed.
     */
    private static final int PLAYING_STATE = 0;

    /**
     * The mask of an unsigned DWORD control.
     */
    private static final long DWORD_MASK = 0xFFFFFFFFL;
    /**
     * The mask of an unsigned WORD control.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of an unsigned BYTE control.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The multiplier that combines each retained value into the state hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * The initial flags retained on statistics children, alone or combined with additional flags.
     */
    private static final int STATISTICS_CHILD_INITIAL_FLAGS = 0x80;
    /**
     * The additional initial flag retained alone or with the initial flags on statistics children.
     */
    private static final int STATISTICS_CHILD_ADDITIONAL_INITIAL_FLAG = 0x2000;

    /**
     * The statistics child flags of a fresh replay in sibling order.
     */
    private static final int @NotNull [] FRESH_REPLAY_STATISTICS_CHILD_FLAGS = {
            0,
            STATISTICS_CHILD_ADDITIONAL_INITIAL_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS | STATISTICS_CHILD_ADDITIONAL_INITIAL_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS | STATISTICS_CHILD_ADDITIONAL_INITIAL_FLAG,
            STATISTICS_CHILD_ADDITIONAL_INITIAL_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS | StatusButtonPanel.TAB_STOP_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS | StatusButtonPanel.TAB_STOP_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS | StatusButtonPanel.TAB_STOP_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS | StatusButtonPanel.TAB_STOP_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS | StatusButtonPanel.TAB_STOP_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS,
            0,
            STATISTICS_CHILD_ADDITIONAL_INITIAL_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS | StatusButtonPanel.TAB_STOP_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS | STATISTICS_CHILD_ADDITIONAL_INITIAL_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS | STATISTICS_CHILD_ADDITIONAL_INITIAL_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS | STATISTICS_CHILD_ADDITIONAL_INITIAL_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS | STATISTICS_CHILD_ADDITIONAL_INITIAL_FLAG,
            STATISTICS_CHILD_ADDITIONAL_INITIAL_FLAG,
            STATISTICS_CHILD_ADDITIONAL_INITIAL_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_ADDITIONAL_INITIAL_FLAG,
            0,
            STATISTICS_CHILD_ADDITIONAL_INITIAL_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS | StatusButtonPanel.TAB_STOP_FLAG,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS,
            STATISTICS_CHILD_INITIAL_FLAGS
    };

    /**
     * The refresh control index of the refreshed panel identity.
     */
    private static final int CONTROL_REFRESH_PANEL_IDENTITY = 0;
    /**
     * The refresh control index of the panel redraw flag.
     */
    private static final int CONTROL_PANEL_DIRTY = 1;
    /**
     * The refresh control index of the portrait refresh flag.
     */
    private static final int CONTROL_PORTRAIT_REFRESH = 2;
    /**
     * The refresh control index of the statistics redraw flag.
     */
    private static final int CONTROL_STATISTICS_DIRTY = 3;
    /**
     * The refresh control index of the identity of the control under the pointer whose tooltip is shown.
     */
    private static final int CONTROL_TOOLTIP_CONTROL_IDENTITY = 4;
    /**
     * The refresh control index of the value of the tooltip control.
     */
    private static final int CONTROL_TOOLTIP_CONTROL_VALUE = 5;

    /**
     * The selection control index of the replay mode.
     */
    private static final int CONTROL_REPLAY_MODE = 0;
    /**
     * The selection control index of the local selection count.
     */
    private static final int CONTROL_SELECTION_COUNT = 1;
    /**
     * The selection control index of the selected identity.
     */
    private static final int CONTROL_SELECTED_IDENTITY = 2;
    /**
     * The selection control index of the current button set.
     */
    private static final int CONTROL_CURRENT_BUTTON_SET = 3;
    /**
     * The selection control index of the fallback button set.
     */
    private static final int CONTROL_FALLBACK_BUTTON_SET = 4;
    /**
     * The selection control index of the displayed button set.
     */
    private static final int CONTROL_DISPLAYED_BUTTON_SET = 5;
    /**
     * The selection control index of the hover identity.
     */
    private static final int CONTROL_HOVER_IDENTITY = 6;
    /**
     * The selection control index of the signed pointer horizontal coordinate.
     */
    private static final int CONTROL_POINTER_X = 7;
    /**
     * The selection control index of the signed pointer vertical coordinate.
     */
    private static final int CONTROL_POINTER_Y = 8;
    /**
     * The selection control index of the selection panel identity.
     */
    private static final int CONTROL_SELECTION_PANEL_IDENTITY = 9;

    /**
     * The replay control index of the pause state.
     */
    private static final int CONTROL_PAUSED = 0;
    /**
     * The replay control index of the button visibility control.
     */
    private static final int CONTROL_HIDE_BUTTONS = 1;
    /**
     * The replay control index of the network game flag.
     */
    private static final int CONTROL_NETWORK_MODE = 2;
    /**
     * The replay control index of the local player.
     */
    private static final int CONTROL_LOCAL_PLAYER = 3;
    /**
     * The replay control index of the participating player mask.
     */
    private static final int CONTROL_PARTICIPATING_PLAYER_MASK = 4;
    /**
     * The replay control index of the signed speed setting.
     */
    private static final int CONTROL_SPEED_SETTING = 5;
    /**
     * The replay control index of the replay speed multiplier.
     */
    private static final int CONTROL_REPLAY_SPEED = 6;
    /**
     * The replay control index of the command state.
     */
    private static final int CONTROL_COMMAND = 7;
    /**
     * The replay control index of the pause message state.
     */
    private static final int CONTROL_PAUSE_MESSAGE = 8;

    private boolean displayRefreshInitialized;
    private long refreshPanelIdentity;
    private long panelDirty;
    private long portraitRefresh;
    private long statisticsDirty;
    private long tooltipControlIdentity;
    private long tooltipControlValue;

    private boolean panelSelectionInitialized;
    private long replayMode;
    private long selectionCount;
    private long selectedIdentity;
    private long currentButtonSet;
    private long fallbackButtonSet;
    private long displayedButtonSet;
    private long hoverIdentity;
    private long pointerX;
    private long pointerY;
    private long selectionPanelIdentity;

    private boolean replayControlsInitialized;
    private int paused;
    private int hideButtons;
    private int networkMode;
    private int localPlayer;
    private int participatingPlayerMask;
    private int speedSetting;
    private int replaySpeed;
    private int command;
    private int pauseMessage;

    private @Nullable StatusButtonPanel statusButtonPanel;
    private int @Nullable [] replayButtonVisibilityCounters;

    private long statisticsPanelIdentity;
    private int statisticsSecondaryDirty;
    private int @Nullable [] statisticsChildFlags;

    /**
     * Creates a {@link GameDisplayState} instance without established display inputs.
     */
    public GameDisplayState() {
        this.displayRefreshInitialized = false;
        this.refreshPanelIdentity      = 0L;
        this.panelDirty                = 0L;
        this.portraitRefresh           = 0L;
        this.statisticsDirty           = 0L;
        this.tooltipControlIdentity    = 0L;
        this.tooltipControlValue       = 0L;

        this.panelSelectionInitialized = false;
        this.replayMode                = 0L;
        this.selectionCount            = 0L;
        this.selectedIdentity          = 0L;
        this.currentButtonSet          = 0L;
        this.fallbackButtonSet         = 0L;
        this.displayedButtonSet        = 0L;
        this.hoverIdentity             = 0L;
        this.pointerX                  = 0L;
        this.pointerY                  = 0L;
        this.selectionPanelIdentity    = 0L;

        this.replayControlsInitialized = false;
        this.paused                    = 0;
        this.hideButtons               = 0;
        this.networkMode               = 0;
        this.localPlayer               = 0;
        this.participatingPlayerMask   = 0;
        this.speedSetting              = 0;
        this.replaySpeed               = 0;
        this.command                   = 0;
        this.pauseMessage              = 0;

        this.statusButtonPanel              = null;
        this.replayButtonVisibilityCounters = null;

        this.statisticsPanelIdentity  = 0L;
        this.statisticsSecondaryDirty = 0;
        this.statisticsChildFlags     = null;
    }

    /**
     * @return a copy of the retained display controls, or {@code null} before initialization
     */
    public long @Nullable [] getDisplayRefreshState() {
        if (!this.displayRefreshInitialized) {
            return null;
        }

        return new long[]{this.refreshPanelIdentity,
                          this.panelDirty, this.portraitRefresh, this.statisticsDirty,
                          this.tooltipControlIdentity, this.tooltipControlValue};
    }

    /**
     * @return {@code true} if local panel selection has been initialized, {@code false} otherwise
     */
    public boolean isPanelSelectionInitialized() {
        return this.panelSelectionInitialized;
    }

    /**
     * @return a copy of the explicit selection controls, or {@code null} before initialization
     */
    public long @Nullable [] getPanelSelectionState() {
        if (!this.panelSelectionInitialized) {
            return null;
        }

        return new long[]{this.replayMode, this.selectionCount, this.selectedIdentity,
                          this.currentButtonSet, this.fallbackButtonSet, this.displayedButtonSet,
                          this.hoverIdentity, this.pointerX, this.pointerY,
                          this.selectionPanelIdentity};
    }

    /**
     * @return the retained local selection count, or {@code 0} before initialization
     */
    public long getSelectionCount() {
        return this.selectionCount;
    }

    /**
     * @return the retained selected identity, or {@code 0} before initialization
     */
    public long getSelectedIdentity() {
        return this.selectedIdentity;
    }

    /**
     * @return the retained secondary statistics redraw flag
     */
    public int getStatisticsSecondaryDirty() {
        return this.statisticsSecondaryDirty;
    }


    /**
     * Supplies retained panel identity, redraw flags, the portrait refresh flag and the tooltip control identity and value.
     *
     * @param controls the six display refresh values in declaration order
     * @throws IllegalArgumentException if {@code controls} does not hold {@code 6} values
     */
    public void initializeDisplayRefresh(long @NotNull [] controls) {
        if (controls.length != REFRESH_CONTROL_COUNT) {
            throw Exceptions.newIllegalArgumentException("Native display refresh requires six retained controls.");
        }

        this.refreshPanelIdentity      = controls[CONTROL_REFRESH_PANEL_IDENTITY];
        this.panelDirty                = controls[CONTROL_PANEL_DIRTY] & DWORD_MASK;
        this.portraitRefresh           = controls[CONTROL_PORTRAIT_REFRESH] & BYTE_MASK;
        this.statisticsDirty           = controls[CONTROL_STATISTICS_DIRTY] & BYTE_MASK;
        this.tooltipControlIdentity    = controls[CONTROL_TOOLTIP_CONTROL_IDENTITY];
        this.tooltipControlValue       = controls[CONTROL_TOOLTIP_CONTROL_VALUE];
        this.displayRefreshInitialized = true;
    }

    /**
     * Supplies retained empty-selection panel controls.
     *
     * @param panel    the panel with explicit node state
     * @param controls the ten selection values in declaration order
     * @throws IllegalArgumentException if {@code controls} does not hold {@code 10} values or its panel identity is {@code 0}
     */
    public void initializeEmptySelectionPanel(@NotNull StatusButtonPanel panel, long @NotNull [] controls) {
        if (controls.length != SELECTION_CONTROL_COUNT || controls[CONTROL_SELECTION_PANEL_IDENTITY] == 0) {
            throw Exceptions.newIllegalArgumentException("Native status-panel selection requires ten controls and a nonnull identity.");
        }

        this.replayMode                = controls[CONTROL_REPLAY_MODE] & DWORD_MASK;
        this.selectionCount            = controls[CONTROL_SELECTION_COUNT] & BYTE_MASK;
        this.selectedIdentity          = controls[CONTROL_SELECTED_IDENTITY];
        this.currentButtonSet          = controls[CONTROL_CURRENT_BUTTON_SET] & WORD_MASK;
        this.fallbackButtonSet         = controls[CONTROL_FALLBACK_BUTTON_SET] & WORD_MASK;
        this.displayedButtonSet        = controls[CONTROL_DISPLAYED_BUTTON_SET] & WORD_MASK;
        this.hoverIdentity             = controls[CONTROL_HOVER_IDENTITY];
        this.pointerX                  = (short) controls[CONTROL_POINTER_X];
        this.pointerY                  = (short) controls[CONTROL_POINTER_Y];
        this.selectionPanelIdentity    = controls[CONTROL_SELECTION_PANEL_IDENTITY];
        this.panelSelectionInitialized = true;
        this.statusButtonPanel         = panel;
    }

    /**
     * Refreshes the represented empty-selection or replay panel.
     * <p>
     * The display refresh and panel selection must be initialized, the panel present and its identity the selection panel identity. In replay mode
     * the replay controls and visibility counters must be present and no unit selected; while the panel redraw flag is clear, the current button
     * set must be empty when the fallback button set is, and otherwise empty or the paused or playing one. Outside replay mode the selection and
     * the current, fallback and displayed button sets must be empty.
     *
     * @throws UnsupportedOperationException if the display state is not represented
     */
    public void refreshDisplayPanel() {
        if (!this.displayRefreshInitialized || !this.panelSelectionInitialized || this.statusButtonPanel == null ||
            this.refreshPanelIdentity != this.selectionPanelIdentity) {
            throw Exceptions.newUnsupportedOperationException("Native display refresh requires matching explicit panel and selection state.");
        }

        if (this.replayMode != 0) {
            if (!this.replayControlsInitialized || this.replayButtonVisibilityCounters == null || this.selectedIdentity != 0) {
                throw Exceptions.newUnsupportedOperationException("Native replay panel requires explicit controls " +
                                                                  "and represented null-selection owner callbacks.");
            }

            if (this.panelDirty != 0) {
                this.currentButtonSet   = this.paused != 0 ? ButtonSetIds.REPLAY_PAUSED : ButtonSetIds.REPLAY_PLAYING;
                this.fallbackButtonSet  = ButtonSetIds.EMPTY;
                this.displayedButtonSet = ButtonSetIds.EMPTY;
            }

            if (this.currentButtonSet == ButtonSetIds.EMPTY) {
                if (this.fallbackButtonSet != ButtonSetIds.EMPTY) {
                    throw Exceptions.newUnsupportedOperationException("Native replay fallback button table is not represented.");
                }

                return;
            }

            if (this.currentButtonSet != ButtonSetIds.REPLAY_PAUSED && this.currentButtonSet != ButtonSetIds.REPLAY_PLAYING) {
                throw Exceptions.newUnsupportedOperationException("Native replay panel requires the original F8/F9 button table.");
            }

            if (this.displayedButtonSet != this.currentButtonSet) {
                this.displayedButtonSet = this.currentButtonSet;
                this.panelDirty         = FLAG_SET;
            }

            if (this.panelDirty == 0) {
                return;
            }

            this.statusButtonPanel.refreshReplayButtons(this.displayedButtonSet == ButtonSetIds.REPLAY_PAUSED ? PAUSED_STATE : PLAYING_STATE,
                                                        this.hideButtons,
                                                        this.networkMode, this.localPlayer, this.participatingPlayerMask,
                                                        this.speedSetting, this.replaySpeed,
                                                        this.command,
                                                        this.replayButtonVisibilityCounters,
                                                        (int) this.pointerX, (int) this.pointerY);

            this.hoverIdentity = 0;

            return;
        }

        if (this.selectionCount != 0 || this.selectedIdentity != 0 ||
            this.currentButtonSet != ButtonSetIds.EMPTY || this.fallbackButtonSet != ButtonSetIds.EMPTY ||
            this.displayedButtonSet != ButtonSetIds.EMPTY) {
            throw Exceptions.newUnsupportedOperationException("Native display refresh requires represented empty E4 selection.");
        }

        if (this.panelDirty == 0) {
            return;
        }

        int hit = this.statusButtonPanel.refreshEmptySelection((int) this.pointerX, (int) this.pointerY);

        this.hoverIdentity = hit == 0 ? 0 : this.selectionPanelIdentity;
    }

    /**
     * Refreshes the display panel and clears its redraw flag.
     */
    public void finishDisplayPanelFrame() {
        this.refreshDisplayPanel();

        this.panelDirty = 0;
    }

    /**
     * Refreshes the existing panel before publishing redraw flags and clearing the tooltip control identity and value.
     *
     * @throws UnsupportedOperationException if the display refresh is not initialized
     */
    public void refreshDisplayState() {
        if (!this.displayRefreshInitialized) {
            throw Exceptions.newUnsupportedOperationException("Native display refresh requires explicit panel and dirty-state inputs.");
        }

        if (this.refreshPanelIdentity != 0) {
            this.refreshDisplayPanel();
        }

        this.panelDirty             = FLAG_SET;
        this.portraitRefresh        = FLAG_SET;
        this.statisticsDirty        = FLAG_SET;
        this.tooltipControlIdentity = 0;
        this.tooltipControlValue    = 0;
    }

    /**
     * Supplies retained replay presentation inputs.
     *
     * @param controls           the replay values in declaration order
     * @param visibilityCounters the button visibility counters
     * @throws IllegalArgumentException if {@code controls} does not hold {@code 9} values or {@code visibilityCounters} does not hold {@code 10}
     */
    public void initializeReplayDisplay(int @NotNull [] controls, int @NotNull [] visibilityCounters) {
        if (controls.length != REPLAY_CONTROL_COUNT || visibilityCounters.length != StatusButtonPanel.BUTTON_SLOT_COUNT) {
            throw Exceptions.newIllegalArgumentException("Native replay display requires nine controls and ten visibility counters.");
        }

        this.paused                         = controls[CONTROL_PAUSED];
        this.hideButtons                    = controls[CONTROL_HIDE_BUTTONS];
        this.networkMode                    = controls[CONTROL_NETWORK_MODE];
        this.localPlayer                    = controls[CONTROL_LOCAL_PLAYER];
        this.participatingPlayerMask        = controls[CONTROL_PARTICIPATING_PLAYER_MASK];
        this.speedSetting                   = controls[CONTROL_SPEED_SETTING];
        this.replaySpeed                    = controls[CONTROL_REPLAY_SPEED];
        this.command                        = controls[CONTROL_COMMAND];
        this.pauseMessage                   = controls[CONTROL_PAUSE_MESSAGE];
        this.replayControlsInitialized      = true;
        this.replayButtonVisibilityCounters = visibilityCounters.clone();
    }

    /**
     * Binds an initialized replay panel and the audited empty-selection statistics startup state.
     *
     * @param panel                the initialized replay panel
     * @param statisticsDefinition the audited statistics resource
     * @param controls             the replay display controls
     * @param pointerX             the signed pointer horizontal coordinate
     * @param pointerY             the signed pointer vertical coordinate
     * @throws UnsupportedOperationException if {@code statisticsDefinition} is not the audited statistics resource
     */
    public void initializeFreshReplayPanels(@NotNull StatusButtonPanel panel,
                                            byte @NotNull [] statisticsDefinition,
                                            int @NotNull [] controls,
                                            int pointerX, int pointerY) {
        if (!AuditedResourceUtils.computeSha256(statisticsDefinition).equals(STATISTICS_DEFINITION_DIGEST)) {
            throw Exceptions.newUnsupportedOperationException("Statistics startup requires the audited statistics definition.");
        }

        this.initializeDisplayRefresh(new long[]{StatusButtonPanel.REPLAY_ROOT_IDENTITY, FLAG_SET, 0, FLAG_SET, 0, 0});
        this.initializeEmptySelectionPanel(panel,
                                           new long[]{REPLAY_MODE_ENABLED, 0, 0,
                                                      ButtonSetIds.EMPTY, ButtonSetIds.EMPTY, ButtonSetIds.EMPTY,
                                                      0, pointerX, pointerY,
                                                      StatusButtonPanel.REPLAY_ROOT_IDENTITY});
        this.initializeReplayDisplay(controls, new int[StatusButtonPanel.BUTTON_SLOT_COUNT]);
        this.initializeEmptySelectionStatistics(INITIAL_STATISTICS_IDENTITY, FRESH_REPLAY_STATISTICS_CHILD_FLAGS, 0);
    }

    /**
     * Refreshes replay buttons and labels before clearing the redraw flag.
     *
     * @param frame       the current replay frame
     * @param totalFrames the total replay frames
     * @param targetFrame the target replay frame
     * @param framePeriod the selected frame duration
     * @throws UnsupportedOperationException if the display refresh or panel selection is not initialized, or a replay panel refresh lacks its controls or panel
     */
    public void finishReplayDisplayPanelFrame(int frame, int totalFrames, int targetFrame, int framePeriod) {
        if (!this.displayRefreshInitialized || !this.panelSelectionInitialized) {
            throw Exceptions.newUnsupportedOperationException("Native replay display requires established panel state.");
        }

        if (this.refreshPanelIdentity == 0 || this.replayMode == 0) {
            return;
        }

        this.refreshDisplayPanel();

        if (!this.replayControlsInitialized || this.statusButtonPanel == null) {
            throw Exceptions.newUnsupportedOperationException("Native replay display controls are unavailable.");
        }

        if ((this.hideButtons & BYTE_MASK) == 0) {
            this.statusButtonPanel.refreshReplayLabels(frame, totalFrames, targetFrame,
                                                       framePeriod,
                                                       this.paused, this.pauseMessage,
                                                       this.speedSetting, this.replaySpeed,
                                                       (int) this.pointerX, (int) this.pointerY);
        }

        this.panelDirty = 0;
    }

    /**
     * Supplies the statistics root and post-initialization child flags.
     *
     * @param rootIdentity   the nonzero statistics root identity
     * @param childFlags     the retained child flags in sibling order
     * @param secondaryDirty the secondary redraw flag
     * @throws IllegalArgumentException if {@code rootIdentity} is {@code 0} or {@code childFlags} does not hold {@code 57} values
     */
    public void initializeEmptySelectionStatistics(long rootIdentity, int @NotNull [] childFlags, int secondaryDirty) {
        if (rootIdentity == 0 || childFlags.length != STATISTICS_CHILD_COUNT) {
            throw Exceptions.newIllegalArgumentException("Native statistics panel requires a nonnull type-zero root and57 child flags.");
        }

        this.statisticsPanelIdentity  = rootIdentity;
        this.statisticsChildFlags     = childFlags.clone();
        this.statisticsSecondaryDirty = secondaryDirty & BYTE_MASK;
    }

    /**
     * Refreshes empty-selection statistics when all retained children are hidden.
     * <p>
     * While the statistics redraw flag is set, the retained child state must be present and every child hidden.
     *
     * @throws UnsupportedOperationException if the display refresh or panel selection is not initialized, a unit is selected or a child is not represented
     */
    public void refreshEmptySelectionStatistics() {
        if (!this.displayRefreshInitialized || !this.panelSelectionInitialized || this.selectedIdentity != 0) {
            throw Exceptions.newUnsupportedOperationException("Native statistics refresh requires explicit null selection and dirty state.");
        }

        if (this.statisticsDirty == 0) {
            return;
        }

        if (this.statisticsChildFlags == null) {
            throw Exceptions.newUnsupportedOperationException("Native statistics refresh requires its retained child state.");
        }

        for (int flags : this.statisticsChildFlags) {
            if ((flags & StatusButtonPanel.VISIBLE_FLAG) != 0) {
                throw Exceptions.newUnsupportedOperationException("Native visible statistics-child callbacks remain unrepresented.");
            }
        }

        this.statisticsSecondaryDirty = 0;
        this.statisticsDirty          = 0;
    }

    /**
     * Computes a hash of the retained display state in its established contribution order.
     *
     * @param hash the preceding diagnostic hash
     * @return the hash including the retained display fields
     */
    public int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + (this.displayRefreshInitialized ? 1 : 0);

        if (this.displayRefreshInitialized) {
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.refreshPanelIdentity);
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.panelDirty);
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.portraitRefresh);
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.statisticsDirty);
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.tooltipControlIdentity);
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.tooltipControlValue);
        }

        hash = HASH_MULTIPLIER * hash + (this.panelSelectionInitialized ? 1 : 0);

        if (this.panelSelectionInitialized) {
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.replayMode);
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.selectionCount);
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.selectedIdentity);
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.currentButtonSet);
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.fallbackButtonSet);
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.displayedButtonSet);
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.hoverIdentity);
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.pointerX);
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.pointerY);
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.selectionPanelIdentity);
        }

        if (this.replayControlsInitialized) {
            hash = HASH_MULTIPLIER * hash + this.paused;
            hash = HASH_MULTIPLIER * hash + this.hideButtons;
            hash = HASH_MULTIPLIER * hash + this.networkMode;
            hash = HASH_MULTIPLIER * hash + this.localPlayer;
            hash = HASH_MULTIPLIER * hash + this.participatingPlayerMask;
            hash = HASH_MULTIPLIER * hash + this.speedSetting;
            hash = HASH_MULTIPLIER * hash + this.replaySpeed;
            hash = HASH_MULTIPLIER * hash + this.command;
            hash = HASH_MULTIPLIER * hash + this.pauseMessage;
        }

        if (this.replayButtonVisibilityCounters != null) {
            for (int value : this.replayButtonVisibilityCounters) {
                hash = HASH_MULTIPLIER * hash + value;
            }
        }

        hash = HASH_MULTIPLIER * hash + (this.statusButtonPanel == null ? 0 : 1);

        if (this.statusButtonPanel != null) {
            hash = this.statusButtonPanel.computeStateHash(hash);
        }

        hash = HASH_MULTIPLIER * hash + Long.hashCode(this.statisticsPanelIdentity);
        hash = HASH_MULTIPLIER * hash + this.statisticsSecondaryDirty;
        hash = HASH_MULTIPLIER * hash + (this.statisticsChildFlags == null ? 0 : 1);

        if (this.statisticsChildFlags != null) {
            for (int flags : this.statisticsChildFlags) {
                hash = HASH_MULTIPLIER * hash + flags;
            }
        }

        return hash;
    }
}
