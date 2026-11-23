package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the immutable file definition of a trigger record, shared by independent player executions.
 * <p>
 * Operand interpretation depends on condition or action type; names describe their common use rather than validating
 * every opcode. Unknown extensions retain their exact file bits; unused action padding is not game state.
 */
public final class TriggerDefinition {
    /**
     * The mask of the low BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The multiplier that combines the fields of {@link #computeStateHash(int)}.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * Retains the condition operands. Comparison, resource type and unit type are interpreted only by the supported
     * condition branch; the extension remains an opaque unsigned file WORD.
     */
    static final class Condition {
        private final int location;
        private final int playerSelector;
        private final int amount;
        private final int unitType;
        private final int comparison;
        private final int type;
        private final int resourceType;
        private final int flags;
        private final int extension;

        Condition(int location, int playerSelector, int amount, int unitType, int comparison, int type, int resourceType, int flags, int extension) {
            this.location       = location;
            this.playerSelector = playerSelector;
            this.amount         = amount;
            this.unitType       = unitType;
            this.comparison     = comparison;
            this.type           = type;
            this.resourceType   = resourceType;
            this.flags          = flags;
            this.extension      = extension;
        }

        int getLocation() {
            return this.location;
        }

        int getPlayerSelector() {
            return this.playerSelector;
        }

        int getAmount() {
            return this.amount;
        }

        int getUnitType() {
            return this.unitType;
        }

        int getComparison() {
            return this.comparison;
        }

        int getType() {
            return this.type;
        }

        int getResourceType() {
            return this.resourceType;
        }

        int getFlags() {
            return this.flags;
        }

        int getExtension() {
            return this.extension;
        }

        /**
         * @return {@code true} if the extension WORD holds the marker {@code 'S','C'} that makes an extended Deaths read masked by the location DWORD, {@code false} otherwise
         */
        boolean isMasked() {
            return this.extension == TriggerRecordCodec.MASK_MARKER;
        }
    }

    /**
     * Retains action operands without enabling execution. The secondary operand can designate a count or another player
     * depending on action type. Location, duration, unit type and modifier are also type-dependent; the extension retains
     * its encoded file value.
     */
    public static final class Action {
        private final int location;
        private final int stringIndex;
        private final int soundIndex;
        private final int duration;
        private final int playerSelector;
        private final int secondaryOperand;
        private final int unitType;
        private final int type;
        private final int modifier;
        private final int flags;
        private final int extension;
        private final boolean masked;

        Action(int location,
               int stringIndex,
               int soundIndex,
               int duration,
               int playerSelector,
               int secondaryOperand,
               int unitType,
               int type,
               int modifier,
               int flags,
               int extension,
               boolean masked) {
            this.location         = location;
            this.stringIndex      = stringIndex;
            this.soundIndex       = soundIndex;
            this.duration         = duration;
            this.playerSelector   = playerSelector;
            this.secondaryOperand = secondaryOperand;
            this.unitType         = unitType;
            this.type             = type;
            this.modifier         = modifier;
            this.flags            = flags;
            this.extension        = extension;
            this.masked           = masked;
        }

        /**
         * @return the location field (the location number, or the mask of a masked Set Deaths)
         */
        public int getLocation() {
            return this.location;
        }

        /**
         * @return the map string index
         */
        public int getStringIndex() {
            return this.stringIndex;
        }

        /**
         * @return the sound string index
         */
        public int getSoundIndex() {
            return this.soundIndex;
        }

        /**
         * @return the duration field in milliseconds
         */
        public int getDuration() {
            return this.duration;
        }

        /**
         * @return the player selector field
         */
        public int getPlayerSelector() {
            return this.playerSelector;
        }

        /**
         * @return the secondary operand field (amount, second player or unit count)
         */
        public int getSecondaryOperand() {
            return this.secondaryOperand;
        }

        /**
         * @return the unit type field
         */
        public int getUnitType() {
            return this.unitType;
        }

        /**
         * @return the action type
         */
        public int getType() {
            return this.type;
        }

        /**
         * @return the modifier field (number modifier, state or order)
         */
        public int getModifier() {
            return this.modifier;
        }

        /**
         * @return the action flags
         */
        public int getFlags() {
            return this.flags;
        }

        /**
         * @return the extension WORD after the flags
         */
        public int getExtension() {
            return this.extension;
        }

        /**
         * @return {@code true} if the mask marker WORD holds the marker {@code 'S','C'} that makes an extended Set Deaths write masked by the location DWORD, {@code false} otherwise
         */
        public boolean isMasked() {
            return this.masked;
        }
    }

    private final @NotNull Condition @NotNull [] conditions;
    private final @NotNull Action @NotNull [] actions;
    private final byte @NotNull [] ownerSelectors;

    /**
     * Creates a {@link TriggerDefinition} instance.
     *
     * @param conditions     the conditions of the record
     * @param actions        the actions of the record
     * @param ownerSelectors the owner selector bytes, one per player selector
     */
    TriggerDefinition(@NotNull Condition @NotNull [] conditions, @NotNull Action @NotNull [] actions, byte @NotNull [] ownerSelectors) {
        this.conditions     = conditions;
        this.actions        = actions;
        this.ownerSelectors = ownerSelectors;
    }

    /**
     * @param index the condition index
     * @return the condition
     */
    @NotNull Condition getCondition(int index) {
        return this.conditions[index];
    }

    /**
     * @param index the action index
     * @return the action
     */
    @NotNull Action getAction(int index) {
        return this.actions[index];
    }

    /**
     * @param index the player selector
     * @return the unsigned owner selector byte
     */
    int getOwnerSelector(int index) {
        return this.ownerSelectors[index] & BYTE_MASK;
    }


    /**
     * Adds every field of the definition to a diagnostic hash.
     *
     * @param hash the preceding hash
     * @return the hash including this definition
     */
    int computeStateHash(int hash) {
        for (Condition condition : this.conditions) {
            hash = HASH_MULTIPLIER * hash + condition.location;
            hash = HASH_MULTIPLIER * hash + condition.playerSelector;
            hash = HASH_MULTIPLIER * hash + condition.amount;
            hash = HASH_MULTIPLIER * hash + condition.unitType;
            hash = HASH_MULTIPLIER * hash + condition.comparison;
            hash = HASH_MULTIPLIER * hash + condition.type;
            hash = HASH_MULTIPLIER * hash + condition.resourceType;
            hash = HASH_MULTIPLIER * hash + condition.flags;
            hash = HASH_MULTIPLIER * hash + condition.extension;
        }

        for (Action action : this.actions) {
            hash = HASH_MULTIPLIER * hash + action.location;
            hash = HASH_MULTIPLIER * hash + action.stringIndex;
            hash = HASH_MULTIPLIER * hash + action.soundIndex;
            hash = HASH_MULTIPLIER * hash + action.duration;
            hash = HASH_MULTIPLIER * hash + action.playerSelector;
            hash = HASH_MULTIPLIER * hash + action.secondaryOperand;
            hash = HASH_MULTIPLIER * hash + action.unitType;
            hash = HASH_MULTIPLIER * hash + action.type;
            hash = HASH_MULTIPLIER * hash + action.modifier;
            hash = HASH_MULTIPLIER * hash + action.flags;
            hash = HASH_MULTIPLIER * hash + action.extension;
        }

        for (byte ownerSelector : this.ownerSelectors) {
            hash = HASH_MULTIPLIER * hash + (ownerSelector & BYTE_MASK);
        }

        return hash;
    }

    /**
     * Creates a {@link TriggerDefinition} instance with replacement conditions, actions or owner selectors.
     *
     * @param conditions     the replacement conditions, or {@code null} to keep these
     * @param actions        the replacement actions, or {@code null} to keep these
     * @param ownerSelectors the replacement owner selector bytes, or {@code null} to keep these
     * @return the created {@link TriggerDefinition} instance
     */
    @NotNull TriggerDefinition createReplacement(@NotNull Condition @Nullable [] conditions,
                                                 @NotNull Action @Nullable [] actions,
                                                 byte @Nullable [] ownerSelectors) {
        return new TriggerDefinition(conditions != null ? conditions : this.conditions,
                                     actions != null ? actions : this.actions,
                                     ownerSelectors != null ? ownerSelectors : this.ownerSelectors);
    }

    /**
     * Copies the conditions, to replace some of them in a new definition.
     *
     * @return a copy of the conditions
     */
    @NotNull Condition @NotNull [] copyConditions() {
        return this.conditions.clone();
    }

    /**
     * Copies the actions, to replace some of them in a new definition.
     *
     * @return a copy of the actions
     */
    @NotNull Action @NotNull [] copyActions() {
        return this.actions.clone();
    }

    /**
     * Creates a {@link TriggerDefinition} instance with one condition replaced.
     *
     * @param index     the condition index
     * @param condition the replacement condition
     * @return the created {@link TriggerDefinition} instance
     */
    @NotNull TriggerDefinition withCondition(int index, @NotNull Condition condition) {
        Condition[] replaced = this.conditions.clone();

        replaced[index] = condition;

        return new TriggerDefinition(replaced, this.actions, this.ownerSelectors);
    }

    /**
     * Creates a {@link TriggerDefinition} instance with one action replaced.
     *
     * @param index  the action index
     * @param action the replacement action
     * @return the created {@link TriggerDefinition} instance
     */
    @NotNull TriggerDefinition withAction(int index, @NotNull Action action) {
        Action[] replaced = this.actions.clone();

        replaced[index] = action;

        return new TriggerDefinition(this.conditions, replaced, this.ownerSelectors);
    }

    /**
     * Creates a {@link TriggerDefinition} instance with one owner selector byte replaced.
     *
     * @param index the player selector
     * @param value the owner byte in the low eight bits
     * @return the created {@link TriggerDefinition} instance
     */
    @NotNull TriggerDefinition withOwnerSelector(int index, int value) {
        byte[] replaced = this.ownerSelectors.clone();

        replaced[index] = (byte) value;

        return new TriggerDefinition(this.conditions, this.actions, replaced);
    }
}
