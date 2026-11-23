package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.io.AuditedResourceUtils;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Retains status-panel button and replay-label state with established callback inputs.
 */
public final class StatusButtonPanel {
    /**
     * The replay play/pause button identifier accepted by {@link #evaluateReplayButtonCondition}.
     */
    public static final int PLAY_PAUSE_BUTTON = 7;
    /**
     * The replay slow-down button identifier accepted by {@link #evaluateReplayButtonCondition}.
     */
    public static final int SLOW_DOWN_BUTTON = 8;
    /**
     * The replay speed-up button identifier accepted by {@link #evaluateReplayButtonCondition}.
     */
    public static final int SPEED_UP_BUTTON = 9;
    /**
     * The number of retained global node identities.
     */
    public static final int REFERENCE_COUNT = 29;
    /**
     * The number of retained tooltip values.
     */
    public static final int TOOLTIP_VALUE_COUNT = 7;

    /**
     * The number of status buttons.
     */
    static final int BUTTON_COUNT = 9;
    /**
     * The number of button slots, including the unused slot {@code 0}.
     */
    static final int BUTTON_SLOT_COUNT = 10;
    /**
     * The default root node identity.
     */
    static final int DEFAULT_ROOT_IDENTITY = 10;
    /**
     * The replay root node identity.
     */
    static final int REPLAY_ROOT_IDENTITY = 15;
    /**
     * The node flag that makes a control visible.
     */
    static final int VISIBLE_FLAG = 0x2;
    /**
     * The retained control bit participating in tab focus.
     */
    static final int TAB_STOP_FLAG = 0x4;

    /**
     * The identity of the first status button.
     */
    private static final int FIRST_BUTTON = 1;
    /**
     * The status button identities in ascending sibling order.
     */
    private static final int @NotNull [] ASCENDING_CHILD_ORDER = {1, 2, 3, 4, 5, 6, 7, 8, 9};

    /**
     * The SHA-256 digest of the audited installed status-button resource.
     */
    private static final @NotNull String DEFAULT_DEFINITION_DIGEST = "45d884ca0a1c62c563c9a8ae9baff532508a1237189467ae9deceead451dc855";
    /**
     * The SHA-256 digest of the audited installed replay resource.
     */
    private static final @NotNull String REPLAY_DEFINITION_DIGEST = "8df31d833042d1bbd10570c034a23526e1e9bc570522698f7c1823bd1d0b8568";

    /**
     * The number of replay text labels.
     */
    private static final int TEXT_LABEL_COUNT = 4;
    /**
     * The number of replay label slots, the progress bar included.
     */
    private static final int LABEL_SLOT_COUNT = 5;
    /**
     * The node identity of the first replay label.
     */
    private static final int FIRST_LABEL_SLOT = 10;
    /**
     * The node identity of the replay progress bar.
     */
    private static final int PROGRESS_SLOT = 11;
    /**
     * The node identity of the first replay label after the progress bar.
     */
    private static final int FIRST_TEXT_LABEL_SLOT = 12;
    /**
     * The node identity of the last replay label.
     */
    private static final int LAST_LABEL_SLOT = 14;
    /**
     * The text label index of the pause label.
     */
    private static final int PAUSE_LABEL_INDEX = 3;

    /**
     * The number of root bounds values.
     */
    private static final int BOUND_COUNT = 4;
    /**
     * The root bounds index of the left edge.
     */
    private static final int BOUND_LEFT = 0;
    /**
     * The root bounds index of the top edge.
     */
    private static final int BOUND_TOP = 1;
    /**
     * The root bounds index of the right edge.
     */
    private static final int BOUND_RIGHT = 2;
    /**
     * The root bounds index of the bottom edge.
     */
    private static final int BOUND_BOTTOM = 3;
    /**
     * The left edge of the root before the horizontal placement offset.
     */
    private static final int ROOT_LEFT = 496;
    /**
     * The top edge of the root.
     */
    private static final int ROOT_TOP = 354;
    /**
     * The right edge of the root before the horizontal placement offset.
     */
    private static final int ROOT_RIGHT = 639;
    /**
     * The bottom edge of the root.
     */
    private static final int ROOT_BOTTOM = 479;

    /**
     * The flags of the root node after startup.
     */
    private static final int INITIAL_ROOT_FLAGS = 0x40000802;
    /**
     * The flags of the replay labels after the progress bar after startup.
     */
    private static final int LABEL_INITIAL_FLAGS = 0x80;
    /**
     * The node notification flag that hiding or changing a control clears.
     */
    private static final int NOTIFICATION_FLAG = 0x40000000;
    /**
     * The root flag that leaves no child focused when the focus moves on.
     */
    private static final int FOCUS_BLOCKED_FLAG = 0x20000000;
    /**
     * The node flags a control needs to take the focus.
     */
    private static final int FOCUS_CANDIDATE_FLAGS = VISIBLE_FLAG | TAB_STOP_FLAG;
    /**
     * The runtime style of a disabled button.
     */
    private static final int DISABLED_STYLE = 0x1;
    /**
     * The runtime style bit of the hovered child.
     */
    private static final int HOVER_STYLE = 0x4;
    /**
     * The hover child before the replay labels supply the retained hover state.
     */
    private static final int UNKNOWN_HOVER_CHILD = -1;

    /**
     * The icon of a button without an icon.
     */
    private static final int NO_ICON = 0xFFFF;
    /**
     * The play icon, which the play/pause button shows while paused.
     */
    private static final int PLAY_ICON = 386;
    /**
     * The pause icon, which the play/pause button shows while playing.
     */
    private static final int PAUSE_ICON = 387;
    /**
     * The icon of the slow-down button.
     */
    private static final int SLOW_DOWN_ICON = 389;
    /**
     * The icon of the speed-up button.
     */
    private static final int SPEED_UP_ICON = 388;
    /**
     * The button data identity of the play/pause button while paused.
     */
    private static final long PAUSED_BUTTON_DATA = 0xDDCC40L;
    /**
     * The button data identity of the play/pause button while playing.
     */
    private static final long PLAYING_BUTTON_DATA = 0xDDCCA0L;
    /**
     * The size in bytes of one replay button data record; the following buttons use the following records.
     */
    private static final int BUTTON_RECORD_SIZE = 32;

    /**
     * The replay button condition of an omitted button.
     */
    private static final int BUTTON_OMITTED = 0;
    /**
     * The replay button condition of an enabled button.
     */
    private static final int BUTTON_ENABLED = 1;
    /**
     * The replay button condition of a disabled button.
     */
    private static final int BUTTON_DISABLED = -1;
    /**
     * The replay mode the replay buttons are refreshed in.
     */
    private static final int REPLAY_MODE_ENABLED = 1;
    /**
     * The speed setting of the slowest game speed.
     */
    private static final int SLOWEST_SPEED = 0;
    /**
     * The smallest replay speed multiplier.
     */
    private static final int MINIMUM_REPLAY_MULTIPLIER = 1;
    /**
     * The largest replay speed multiplier.
     */
    private static final int MAXIMUM_REPLAY_MULTIPLIER = 16;
    /**
     * The command state whose replay button presentation is not represented.
     */
    private static final int UNREPRESENTED_COMMAND_STATE = 21;
    /**
     * The scale of the replay progress percentage.
     */
    private static final int PERCENT_SCALE = 100;

    /**
     * The mask of an unsigned BYTE value.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of an unsigned WORD value.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The multiplier that combines each retained value into the state hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * Creates a {@link StatusButtonPanel} instance after audited button startup callbacks.
     *
     * @param definition       the audited installed status-button resource
     * @param horizontalOffset the signed horizontal placement offset
     * @param references       the retained global node identities
     * @param tooltipValues    the retained tooltip values
     * @return the created {@link StatusButtonPanel} instance
     */
    public static @NotNull StatusButtonPanel createAfterStartup(byte @NotNull [] definition,
                                                                int horizontalOffset,
                                                                int @NotNull [] references,
                                                                long @NotNull [] tooltipValues) {
        int[] flags = new int[DEFAULT_ROOT_IDENTITY + 1];

        Arrays.fill(flags, FIRST_BUTTON, BUTTON_SLOT_COUNT, TAB_STOP_FLAG);
        flags[DEFAULT_ROOT_IDENTITY] = INITIAL_ROOT_FLAGS;

        int[] icons = new int[BUTTON_SLOT_COUNT];

        Arrays.fill(icons, FIRST_BUTTON, BUTTON_SLOT_COUNT, NO_ICON);

        StatusButtonPanel panel = new StatusButtonPanel(definition,
                                                        flags,
                                                        new int[DEFAULT_ROOT_IDENTITY + 1],
                                                        ASCENDING_CHILD_ORDER,
                                                        createRootBounds(horizontalOffset),
                                                        0,
                                                        icons,
                                                        new long[BUTTON_SLOT_COUNT],
                                                        references,
                                                        tooltipValues);

        panel.clearButtonReferences();

        return panel;
    }

    /**
     * Creates a {@link StatusButtonPanel} instance after replay startup callbacks and progress resource loading.
     *
     * @param definition        the audited installed replay resource
     * @param horizontalOffset  the signed horizontal placement offset
     * @param references        the retained global node identities
     * @param tooltipValues     the retained tooltip values
     * @param initialText       the actual localized label contents
     * @param localization      the replay localization results
     * @param progressResources the actual loaded progress resources
     * @return the created {@link StatusButtonPanel} instance
     * @throws IllegalArgumentException if {@code progressResources} or one of its resources is empty
     */
    public static @NotNull StatusButtonPanel createReplayAfterStartup(byte @NotNull [] definition,
                                                                      int horizontalOffset,
                                                                      int @NotNull [] references,
                                                                      long @NotNull [] tooltipValues,
                                                                      byte @NotNull [] @NotNull [] initialText,
                                                                      byte @NotNull [] @NotNull [] localization,
                                                                      byte @NotNull [] @NotNull [] progressResources) {
        if (progressResources.length == 0) {
            throw Exceptions.newIllegalArgumentException("Native replay startup requires established progress resource results.");
        }

        int[] flags = new int[REPLAY_ROOT_IDENTITY + 1];

        Arrays.fill(flags, FIRST_BUTTON, BUTTON_SLOT_COUNT, TAB_STOP_FLAG);
        Arrays.fill(flags, FIRST_TEXT_LABEL_SLOT, REPLAY_ROOT_IDENTITY, LABEL_INITIAL_FLAGS);
        flags[REPLAY_ROOT_IDENTITY] = INITIAL_ROOT_FLAGS;

        int[] icons = new int[REPLAY_ROOT_IDENTITY];

        Arrays.fill(icons, FIRST_BUTTON, BUTTON_SLOT_COUNT, NO_ICON);

        StatusButtonPanel panel = new StatusButtonPanel(definition,
                                                        flags,
                                                        new int[REPLAY_ROOT_IDENTITY + 1],
                                                        ASCENDING_CHILD_ORDER,
                                                        createRootBounds(horizontalOffset),
                                                        0,
                                                        icons,
                                                        new long[REPLAY_ROOT_IDENTITY],
                                                        references,
                                                        tooltipValues);

        panel.clearButtonReferences();

        ReplayStatusText labels = new ReplayStatusText(new byte[][]{new byte[0], new byte[0], new byte[0], new byte[0]}, localization);

        panel.initializeReplayLabels(labels, initialText, new int[LABEL_SLOT_COUNT], 0);

        panel.replayProgressResources = new byte[progressResources.length][];

        for (int index = 0; index < progressResources.length; index++) {
            if (progressResources[index].length == 0) {
                throw Exceptions.newIllegalArgumentException("Native replay progress resources must contain their actual loaded data.");
            }

            panel.replayProgressResources[index] = progressResources[index].clone();
            panel.replayProgressResourceHash     = HASH_MULTIPLIER * panel.replayProgressResourceHash + Arrays.hashCode(progressResources[index]);
        }

        return panel;
    }

    /**
     * Evaluates replay button visibility, enabled state and the controlling-player predicate.
     *
     * @param identifier              the replay button identifier
     * @param replayMode              the replay mode
     * @param networkGame             the network game flag
     * @param localPlayer             the local player
     * @param participatingPlayerMask the participating player mask
     * @param speedSetting            the signed speed setting
     * @param replaySpeed             the replay speed multiplier
     * @return {@code 0} if omitted, {@code 1} if enabled, or {@code -1} if disabled
     * @throws IllegalArgumentException if {@code identifier} is not a replay button identifier, {@code 7} through {@code 9}
     */
    public static int evaluateReplayButtonCondition(int identifier,
                                                    int replayMode,
                                                    int networkGame, int localPlayer, int participatingPlayerMask,
                                                    int speedSetting, int replaySpeed) {
        if (identifier < PLAY_PAUSE_BUTTON || identifier > SPEED_UP_BUTTON) {
            throw Exceptions.newIllegalArgumentException("Replay button conditions require a replay button identifier from {0} to {1}, not {2}.",
                                                         PLAY_PAUSE_BUTTON, SPEED_UP_BUTTON, identifier);
        }

        if (replayMode == 0) {
            return BUTTON_OMITTED;
        }

        if ((networkGame & BYTE_MASK) != 0) {
            int controllingPlayer = (participatingPlayerMask & BYTE_MASK) == 0
                                    ? PlayerTable.PLAYABLE_PLAYER_SLOTS
                                    : Integer.numberOfTrailingZeros(participatingPlayerMask & BYTE_MASK);

            if (controllingPlayer != localPlayer) {
                return BUTTON_OMITTED;
            }
        }

        if (identifier == SLOW_DOWN_BUTTON && speedSetting == SLOWEST_SPEED &&
            Integer.compareUnsigned(replaySpeed, MINIMUM_REPLAY_MULTIPLIER) >= 0 ||
            identifier == SPEED_UP_BUTTON && speedSetting == GameSpeed.FASTEST &&
            Integer.compareUnsigned(replaySpeed, MAXIMUM_REPLAY_MULTIPLIER) >= 0) {
            return BUTTON_DISABLED;
        }

        return BUTTON_ENABLED;
    }

    private static int @NotNull [] createRootBounds(int horizontalOffset) {
        return new int[]{(short) (ROOT_LEFT + horizontalOffset), ROOT_TOP, (short) (ROOT_RIGHT + horizontalOffset), ROOT_BOTTOM};
    }

    private final int rootIdentity;
    private int hoverChild;

    private final int @NotNull [] flags;
    private final int @NotNull [] styles;
    private final int @NotNull [] order;
    private final int @NotNull [] bounds;
    private int focusedChild;

    private final int @NotNull [] icons;
    private final long @NotNull [] buttonData;
    private final int @NotNull [] references;
    private final long @NotNull [] tooltipValues;

    private @Nullable ReplayStatusText replayLabels;
    private byte @Nullable [] @NotNull [] labelText;
    private int @Nullable [] labelVisibilityCounters;
    private byte @Nullable [] @NotNull [] replayProgressResources;
    private int replayProgressResourceHash;

    /**
     * Creates a {@link StatusButtonPanel} instance binding audited assets to explicit node and host state.
     *
     * @param definition    the audited installed status-button resource
     * @param flags         the node flags
     * @param styles        the node runtime styles
     * @param childOrder    the sibling order beginning with the first button
     * @param rootBounds    the signed root bounds
     * @param focusedChild  the focused child identity, or {@code 0}
     * @param icons         the button icons
     * @param buttonData    the button data identities
     * @param references    the retained global node identities
     * @param tooltipValues the retained tooltip values
     * @throws IllegalArgumentException      if an array has the wrong length, {@code childOrder} is not a button order or {@code focusedChild} is outside {@code 0..9}
     * @throws UnsupportedOperationException if {@code definition} is not an audited installed status-button resource
     */
    public StatusButtonPanel(byte @NotNull [] definition,
                             int @NotNull [] flags,
                             int @NotNull [] styles,
                             int @NotNull [] childOrder,
                             int @NotNull [] rootBounds,
                             int focusedChild,
                             int @NotNull [] icons,
                             long @NotNull [] buttonData,
                             int @NotNull [] references,
                             long @NotNull [] tooltipValues) {
        this.rootIdentity = switch (AuditedResourceUtils.computeSha256(definition)) {
            case DEFAULT_DEFINITION_DIGEST -> DEFAULT_ROOT_IDENTITY;
            case REPLAY_DEFINITION_DIGEST -> REPLAY_ROOT_IDENTITY;
            default -> throw Exceptions.newUnsupportedOperationException("Native status panel requires an audited installed asset.");
        };

        if (flags.length != this.rootIdentity + 1 || styles.length != this.rootIdentity + 1 ||
            childOrder.length != BUTTON_COUNT || childOrder[0] != FIRST_BUTTON ||
            rootBounds.length != BOUND_COUNT || focusedChild < 0 || focusedChild > BUTTON_COUNT ||
            icons.length != this.rootIdentity || buttonData.length != this.rootIdentity ||
            references.length != REFERENCE_COUNT || tooltipValues.length != TOOLTIP_VALUE_COUNT) {
            throw Exceptions.newIllegalArgumentException("Native status panel requires complete explicit node and host state.");
        }

        int identifiers = 0;

        for (int identifier : childOrder) {
            if (identifier < FIRST_BUTTON || identifier > BUTTON_COUNT || (identifiers & 1 << identifier) != 0) {
                throw Exceptions.newIllegalArgumentException("Native status-panel child order must contain each audited ID once.");
            }

            identifiers |= 1 << identifier;
        }

        this.hoverChild = UNKNOWN_HOVER_CHILD;

        this.flags        = flags.clone();
        this.styles       = styles.clone();
        this.order        = childOrder.clone();
        this.bounds       = rootBounds.clone();
        this.focusedChild = focusedChild;

        this.icons         = icons.clone();
        this.buttonData    = buttonData.clone();
        this.references    = references.clone();
        this.tooltipValues = tooltipValues.clone();

        this.replayLabels               = null;
        this.labelText                  = null;
        this.labelVisibilityCounters    = null;
        this.replayProgressResources    = null;
        this.replayProgressResourceHash = 0;
    }

    /**
     * @param identifier the retained node identity
     * @return the node flags
     */
    public int getFlags(int identifier) {
        return this.flags[identifier];
    }

    /**
     * @param identifier the retained node identity
     * @return the node runtime style
     */
    public int getStyle(int identifier) {
        return this.styles[identifier];
    }

    /**
     * @return the focused child identity, or {@code 0}
     */
    public int getFocusedChild() {
        return this.focusedChild;
    }

    /**
     * @param index the retained global reference index
     * @return the retained global node identity
     */
    public int getReference(int index) {
        return this.references[index];
    }

    /**
     * @param index the retained tooltip index
     * @return the retained tooltip value
     */
    public long getTooltipValue(int index) {
        return this.tooltipValues[index];
    }

    /**
     * @param identifier the child identity
     * @return the button icon
     */
    public int getIcon(int identifier) {
        return this.icons[identifier];
    }

    /**
     * @param identifier the child identity
     * @return the retained button data identity
     */
    public long getButtonData(int identifier) {
        return this.buttonData[identifier];
    }

    /**
     * @param index the label index
     * @return the retained node string
     * @throws IllegalStateException if the replay label strings are not initialized
     */
    public byte @NotNull [] getReplayLabelText(int index) {
        if (this.labelText == null) {
            throw Exceptions.newIllegalStateException("Native replay label strings have not been initialized.");
        }

        return this.labelText[index].clone();
    }


    /**
     * Refreshes replay buttons with represented focus, pointer and command state.
     * <p>
     * No child may have the focus, the pointer must be outside the panel, the buttons in ascending order and each button style the disabled one
     * or one with a retained hover state.
     *
     * @param paused                  the pause state
     * @param hideButtons             the button visibility control
     * @param networkGame             the network game flag
     * @param localPlayer             the local player
     * @param participatingPlayerMask the participating player mask
     * @param speedSetting            the signed speed setting
     * @param replaySpeed             the replay speed multiplier
     * @param commandState            the command state
     * @param visibilityCounters      the button visibility counters
     * @param pointerX                the signed pointer horizontal coordinate
     * @param pointerY                the signed pointer vertical coordinate
     * @throws UnsupportedOperationException if {@code visibilityCounters} does not hold {@code 10} values or the panel or command state is not represented
     */
    public void refreshReplayButtons(int paused,
                                     int hideButtons,
                                     int networkGame, int localPlayer, int participatingPlayerMask,
                                     int speedSetting, int replaySpeed,
                                     int commandState,
                                     int @NotNull [] visibilityCounters,
                                     int pointerX, int pointerY) {
        if (visibilityCounters.length != BUTTON_SLOT_COUNT || this.focusedChild != 0 || commandState == UNREPRESENTED_COMMAND_STATE ||
            this.isInsideRoot(pointerX, pointerY)) {
            throw Exceptions.newUnsupportedOperationException("Replay button presentation requires represented focus, pointer and command state.");
        }

        for (int identifier = FIRST_BUTTON; identifier <= BUTTON_COUNT; identifier++) {
            if (this.order[identifier - FIRST_BUTTON] != identifier) {
                throw Exceptions.newUnsupportedOperationException("Replay button presentation requires established ascending button order.");
            }

            if ((this.styles[identifier] & ~DISABLED_STYLE) != 0 || this.styles[identifier] != 0 && this.hoverChild < 0) {
                throw Exceptions.newUnsupportedOperationException("Replay button enabling requires retained hover state for nonzero styles.");
            }
        }

        for (int identifier : this.order) {
            int condition = identifier < PLAY_PAUSE_BUTTON
                            ? BUTTON_OMITTED
                            : evaluateReplayButtonCondition(identifier,
                                                            REPLAY_MODE_ENABLED,
                                                            networkGame, localPlayer, participatingPlayerMask,
                                                            speedSetting, replaySpeed);

            if (condition == BUTTON_OMITTED) {
                this.hideControl(identifier);

                this.icons[identifier]      = NO_ICON;
                this.buttonData[identifier] = 0;

                continue;
            }

            this.buttonData[identifier] = (paused != 0 ? PAUSED_BUTTON_DATA : PLAYING_BUTTON_DATA) +
                                          (identifier - PLAY_PAUSE_BUTTON) * BUTTON_RECORD_SIZE;

            int icon = identifier == PLAY_PAUSE_BUTTON
                       ? (paused != 0 ? PLAY_ICON : PAUSE_ICON)
                       : identifier == SLOW_DOWN_BUTTON ? SLOW_DOWN_ICON : SPEED_UP_ICON;

            if (this.icons[identifier] != icon) {
                this.icons[identifier] = icon;
                this.hideNotification(identifier);
            }

            if ((hideButtons & BYTE_MASK) != 0) {
                this.hideControl(identifier);
            } else if ((visibilityCounters[identifier] & WORD_MASK) == 0) {
                this.flags[identifier] |= VISIBLE_FLAG;
            }

            if (condition < BUTTON_OMITTED) {
                if ((this.styles[identifier] & DISABLED_STYLE) == 0) {
                    this.styles[identifier] = DISABLED_STYLE;
                    this.hideNotification(identifier);
                }
            } else if ((this.styles[identifier] & DISABLED_STYLE) != 0) {
                this.styles[identifier] &= ~DISABLED_STYLE;

                if (this.hoverChild != 0) {
                    this.styles[this.hoverChild] &= ~HOVER_STYLE;
                    this.hoverChild = 0;
                }
            }
        }
    }

    /**
     * Supplies retained replay label strings, visibility counters and hover identity.
     * <p>
     * The inputs are validated and copied first; the panel state changes only after every copy is complete.
     *
     * @param labels             the replay text buffers and localization results
     * @param text               the existing node strings; a {@code null} string is rejected
     * @param visibilityCounters the label visibility counters
     * @param hoverChild         the hover child identity, or {@code 0}
     * @throws IllegalArgumentException if the panel is not the replay panel, an array has the wrong length, {@code hoverChild} is no label or a label has no node string
     */
    public void initializeReplayLabels(@NotNull ReplayStatusText labels,
                                       byte @NotNull [] @Nullable [] text,
                                       int @NotNull [] visibilityCounters,
                                       int hoverChild) {
        if (this.rootIdentity != REPLAY_ROOT_IDENTITY || text.length != TEXT_LABEL_COUNT ||
            visibilityCounters.length != LABEL_SLOT_COUNT || hoverChild < 0 || hoverChild > LAST_LABEL_SLOT) {
            throw Exceptions.newIllegalArgumentException("Replay labels require the audited14-child panel and complete retained inputs.");
        }

        byte[][] retainedText = new byte[TEXT_LABEL_COUNT][];

        for (int index = 0; index < TEXT_LABEL_COUNT; index++) {
            if (text[index] == null) {
                throw Exceptions.newIllegalArgumentException("Replay label {0} has no node string.", index);
            }

            retainedText[index] = text[index].clone();
        }

        int[] retainedCounters = visibilityCounters.clone();

        this.replayLabels            = labels;
        this.labelText               = retainedText;
        this.labelVisibilityCounters = retainedCounters;
        this.hoverChild              = hoverChild;
    }

    /**
     * Refreshes replay progress and text with the pointer outside the panel and no focused child.
     *
     * @param frame        the current replay frame
     * @param totalFrames  the total replay frames
     * @param targetFrame  the target replay frame
     * @param framePeriod  the selected frame duration
     * @param paused       the pause state
     * @param pauseMessage the pause message state
     * @param speedSetting the signed speed setting
     * @param replaySpeed  the replay speed multiplier
     * @param pointerX     the signed pointer horizontal coordinate
     * @param pointerY     the signed pointer vertical coordinate
     * @throws UnsupportedOperationException if the replay labels are not initialized, a child has the focus or the pointer is inside the panel
     */
    public void refreshReplayLabels(int frame, int totalFrames, int targetFrame,
                                    int framePeriod,
                                    int paused, int pauseMessage,
                                    int speedSetting, int replaySpeed,
                                    int pointerX, int pointerY) {
        if (this.replayLabels == null || this.labelText == null || this.labelVisibilityCounters == null || this.focusedChild != 0 ||
            this.isInsideRoot(pointerX, pointerY)) {
            throw Exceptions.newUnsupportedOperationException("Native replay labels require retained inputs and represented " +
                                                              "pointer/focus callbacks.");
        }

        if ((this.labelVisibilityCounters[PROGRESS_SLOT - FIRST_LABEL_SLOT] & WORD_MASK) == 0) {
            this.flags[PROGRESS_SLOT] |= VISIBLE_FLAG;
        }

        this.icons[PROGRESS_SLOT] = totalFrames == 0 ? 0 : Integer.divideUnsigned(frame * PERCENT_SCALE, totalFrames) & WORD_MASK;

        this.replayLabels.update(frame, targetFrame, framePeriod, paused, pauseMessage, speedSetting, replaySpeed);

        for (int index = 0; index < TEXT_LABEL_COUNT; index++) {
            int identifier = index == 0 ? FIRST_LABEL_SLOT : index + PROGRESS_SLOT;

            if (index == PAUSE_LABEL_INDEX && paused == 0) {
                if ((this.flags[identifier] & VISIBLE_FLAG) != 0) {
                    this.flags[identifier] &= ~VISIBLE_FLAG;
                    this.clearReferences(identifier);
                }

                continue;
            }

            this.labelText[index] = this.replayLabels.getText(index);

            if ((this.labelVisibilityCounters[identifier - FIRST_LABEL_SLOT] & WORD_MASK) == 0) {
                this.flags[identifier] |= VISIBLE_FLAG;
            }
        }
    }

    /**
     * Refreshes zero-button empty selection, hidden children and tooltip state.
     *
     * @param pointerX the signed pointer horizontal coordinate
     * @param pointerY the signed pointer vertical coordinate
     * @return the root identity if the pointer is inside the root, or {@code 0} otherwise
     * @throws UnsupportedOperationException if the panel is the replay panel
     */
    public int refreshEmptySelection(int pointerX, int pointerY) {
        if (this.rootIdentity != DEFAULT_ROOT_IDENTITY) {
            throw Exceptions.newUnsupportedOperationException("Replay-panel hit testing requires its retained label and hover state.");
        }

        for (int identifier : this.order) {
            this.hideControl(identifier);

            this.icons[identifier]      = NO_ICON;
            this.buttonData[identifier] = 0;
        }

        boolean inside = this.isInsideRoot(pointerX, pointerY);

        if (inside || this.focusedChild != 0) {
            if (!inside) {
                this.focusedChild = 0;
            }

            Arrays.fill(this.tooltipValues, 0);
        }

        return inside ? DEFAULT_ROOT_IDENTITY : 0;
    }

    /**
     * Computes a hash of all retained panel state.
     *
     * @param hash the preceding deterministic state hash
     * @return the hash including the retained panel state
     */
    public int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + this.focusedChild;

        for (int value : this.flags) {
            hash = HASH_MULTIPLIER * hash + value;
        }

        for (int value : this.styles) {
            hash = HASH_MULTIPLIER * hash + value;
        }

        for (int value : this.order) {
            hash = HASH_MULTIPLIER * hash + value;
        }

        for (int value : this.bounds) {
            hash = HASH_MULTIPLIER * hash + value;
        }

        for (int value : this.icons) {
            hash = HASH_MULTIPLIER * hash + value;
        }

        for (long value : this.buttonData) {
            hash = HASH_MULTIPLIER * hash + Long.hashCode(value);
        }

        for (int value : this.references) {
            hash = HASH_MULTIPLIER * hash + value;
        }

        for (long value : this.tooltipValues) {
            hash = HASH_MULTIPLIER * hash + Long.hashCode(value);
        }

        hash = HASH_MULTIPLIER * hash + this.hoverChild;

        if (this.replayLabels != null) {
            hash = this.replayLabels.computeStateHash(hash);
        }

        if (this.labelText != null) {
            for (byte[] text : this.labelText) {
                for (byte value : text) {
                    hash = HASH_MULTIPLIER * hash + (value & BYTE_MASK);
                }
            }
        }

        if (this.labelVisibilityCounters != null) {
            for (int value : this.labelVisibilityCounters) {
                hash = HASH_MULTIPLIER * hash + value;
            }
        }

        if (this.replayProgressResources != null) {
            hash = HASH_MULTIPLIER * hash + this.replayProgressResourceHash;
        }

        return hash;
    }

    private boolean isInsideRoot(int pointerX, int pointerY) {
        return (short) pointerX >= (short) this.bounds[BOUND_LEFT] && (short) pointerX <= (short) this.bounds[BOUND_RIGHT] &&
               (short) pointerY >= (short) this.bounds[BOUND_TOP] && (short) pointerY <= (short) this.bounds[BOUND_BOTTOM];
    }

    private void clearButtonReferences() {
        for (int index = 0; index < this.references.length; index++) {
            if (this.references[index] >= FIRST_BUTTON && this.references[index] <= BUTTON_COUNT) {
                this.references[index] = 0;
            }
        }
    }

    private void clearReferences(int identifier) {
        for (int index = 0; index < this.references.length; index++) {
            if (this.references[index] == identifier) {
                this.references[index] = 0;
            }
        }
    }

    private void hideControl(int identifier) {
        if ((this.flags[identifier] & VISIBLE_FLAG) != 0) {
            this.flags[identifier] &= ~VISIBLE_FLAG;
            this.hideNotification(identifier);
        }
    }

    /**
     * Clears the notification and the references of a hidden or changed control. A focused control passes the focus to the next enabled, visible
     * tab stop after it in sibling order, wrapping around, or to no child when there is none or the root blocks the focus; the control is notified
     * again after a move. When the control itself is the only candidate, it keeps the focus and is not notified again.
     *
     * @param identifier the child identity
     */
    private void hideNotification(int identifier) {
        this.flags[identifier] &= ~NOTIFICATION_FLAG;

        this.clearReferences(identifier);

        if (this.focusedChild != identifier) {
            return;
        }

        int position = 0;

        while (this.order[position] != identifier) {
            position++;
        }

        for (int distance = 1; distance <= this.order.length; distance++) {
            int candidate = this.order[(position + distance) % this.order.length];

            if ((this.styles[candidate] & DISABLED_STYLE) != 0 || (this.flags[candidate] & FOCUS_CANDIDATE_FLAGS) != FOCUS_CANDIDATE_FLAGS) {
                continue;
            }

            if (candidate != identifier) {
                this.focusedChild = (this.flags[this.rootIdentity] & FOCUS_BLOCKED_FLAG) == 0 ? candidate : 0;
                this.hideNotification(identifier);
            }

            return;
        }

        this.focusedChild = 0;
    }
}
