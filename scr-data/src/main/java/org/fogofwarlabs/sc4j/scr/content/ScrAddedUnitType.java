package org.fogofwarlabs.sc4j.scr.content;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.scr.content.table.ScrDatKind;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTraitFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Describes a unit type that a mod adds after the native {@code units.dat} entries ({@link ScrAddedUnitTypes}): its engine identity, its name,
 * one value per {@code units.dat} field and per trait column ({@link ScrUnitTypeView.TraitColumn}), the native unit types whose
 * requirement script, button set and wireframe it uses, optionally its own requirement script ({@link ScrRequirementScript}), and optionally the
 * button a producer shows for it ({@link TrainButton}).
 * <p>
 * A type whose {@link ScrUnitTypeView.TraitColumn#TRAITS} value holds {@link UnitTraitFlags#BUILDING_MORPH_RESULT} is a building morph result:
 * a selected Zerg building whose requirement script allows it morphs into the type, as a Hatchery morphs into a Lair, and a button of the
 * {@link ProductionAction#BUILDING_MORPH} action starts the morph. The building stays a building while it morphs and completes as a unit of the
 * type, which leaves the building's creep and Larvae behind.
 * <p>
 * The interface shows the wireframe and the command icon of the wireframe type unless the type names its own added ones
 * ({@link ScrAddedGraphics#addWireframe}, {@link ScrAddedGraphics#addCommandIcon}); its own portrait is an added portrait
 * ({@link ScrAddedGraphics#addPortrait}) named by its {@link UnitsDat#PORTRAIT} value.
 * <p>
 * The values start as a copy of a native base type of the installed data, so every reference the copy holds (flingy, weapons, orders, sounds,
 * portrait, armor upgrade) names an existing installed entry; the mod then changes the values it wants. A field without an element for the base
 * type (the infestation and add-on fields, which only buildings hold) is {@code 0}. The base type must be a unit a build queue trains (below
 * {@link ScrUnitTypeView#FIRST_INFESTATION_TYPE}) without a subunit, since an added type is always trained and never has a turret.
 */
public final class ScrAddedUnitType {
    /**
     * The first command card position.
     */
    public static final int FIRST_BUTTON_POSITION = 1;
    /**
     * The last command card position.
     */
    public static final int LAST_BUTTON_POSITION = 9;
    /**
     * The wireframe or command icon frame of a type that shows its wireframe type's.
     */
    public static final int NO_INTERFACE_FRAME = -1;

    /**
     * Names the command a production button of an added type issues.
     */
    public enum ProductionAction {
        /**
         * Trains the added type in the producer's queue.
         */
        TRAIN,
        /**
         * Morphs the selected Zerg building into the added type, a building morph result.
         */
        BUILDING_MORPH
    }

    /**
     * Describes the train button of an added type on the command card of a native producer: the button set of the producer's unit type gets a
     * button at a position that trains the added type when its requirement script allows it. The button shows the icon of the added type's
     * wireframe type and its text with its hotkey; it follows the producer's native buttons of the same position, so a native button that is
     * shown there keeps the slot. The button trains the type, or morphs the producer into it ({@link ProductionAction}).
     * <p>
     * Its texts ({@link Text}) are those of a native button: the tooltip of the enabled button, and the tooltip of the disabled button, which names
     * what the requirement script asks for. The texts of the default language may have translations, chosen by the client's locale.
     */
    public static final class TrainButton {
        /**
         * The tooltip texts of a button in one language, as the {@code stat_txt} strings of a native button: the enabled text after its hotkey and
         * button kind characters (with the cost shown below it), and the disabled text, which names the requirements in the words of the installed
         * disabled strings ({@code "Hive Requires:"}, then one line per requirement indented by three spaces).
         */
        public static final class Text {
            private final @NotNull String enabledText;
            private final @Nullable String disabledText;

            /**
             * Creates a {@link Text} instance.
             *
             * @param enabledText  the tooltip of the enabled button, in the format of an installed button string after its hotkey and button kind characters
             * @param disabledText the tooltip of the disabled button, or {@code null} for none (a disabled button then shows no tooltip)
             */
            public Text(@NotNull String enabledText, @Nullable String disabledText) {
                this.enabledText  = enabledText;
                this.disabledText = disabledText;
            }

            /**
             * @return the tooltip of the enabled button
             */
            public @NotNull String getEnabledText() {
                return this.enabledText;
            }

            /**
             * @return the tooltip of the disabled button, or {@code null} for none
             */
            public @Nullable String getDisabledText() {
                return this.disabledText;
            }
        }


        private final @NotNull UnitType producerType;
        private final int position;
        private final @NotNull ProductionAction action;

        private final char hotkey;
        private final @NotNull Text text;
        private final @NotNull Map<@NotNull String, @NotNull Text> translations;

        /**
         * Creates a {@link TrainButton} instance that trains the added type, with an enabled text and no disabled text.
         *
         * @param producerType the native unit type whose button set shows the button
         * @param position     the command card position from {@link #FIRST_BUTTON_POSITION} through {@link #LAST_BUTTON_POSITION}
         * @param hotkey       the key that presses the button
         * @param text         the tooltip text, in the format of an installed button string after its hotkey and button kind characters
         * @throws IllegalArgumentException if the producer is not a native unit type or the position is outside the command card
         */
        public TrainButton(@NotNull UnitType producerType, int position, char hotkey, @NotNull String text) {
            this(producerType, position, ProductionAction.TRAIN, hotkey, text);
        }

        /**
         * Creates a {@link TrainButton} instance with an enabled text and no disabled text.
         *
         * @param producerType the native unit type whose button set shows the button
         * @param position     the command card position from {@link #FIRST_BUTTON_POSITION} through {@link #LAST_BUTTON_POSITION}
         * @param action       the command the button issues
         * @param hotkey       the key that presses the button
         * @param text         the tooltip text, in the format of an installed button string after its hotkey and button kind characters
         * @throws IllegalArgumentException if the producer is not a native unit type or the position is outside the command card
         */
        public TrainButton(@NotNull UnitType producerType, int position, @NotNull ProductionAction action, char hotkey, @NotNull String text) {
            this(producerType, position, action, hotkey, new Text(text, null), Map.of());
        }

        /**
         * Creates a {@link TrainButton} instance.
         *
         * @param producerType the native unit type whose button set shows the button
         * @param position     the command card position from {@link #FIRST_BUTTON_POSITION} through {@link #LAST_BUTTON_POSITION}
         * @param action       the command the button issues
         * @param hotkey       the key that presses the button, in every language
         * @param text         the tooltip texts in the default language, shown for a locale without a translation
         * @param translations the tooltip texts by client locale ({@code koKR}, ...)
         * @throws IllegalArgumentException if the producer is not a native unit type or the position is outside the command card
         */
        public TrainButton(@NotNull UnitType producerType, int position, @NotNull ProductionAction action, char hotkey, @NotNull Text text,
                           @NotNull Map<@NotNull String, @NotNull Text> translations) {
            ScrUnits.getNativeId(producerType);

            if (position < FIRST_BUTTON_POSITION || position > LAST_BUTTON_POSITION) {
                throw Exceptions.newIllegalArgumentException("Invalid command card position: {0}", position);
            }

            this.producerType = producerType;
            this.position     = position;
            this.action       = action;

            this.hotkey       = hotkey;
            this.text         = text;
            this.translations = Map.copyOf(translations);
        }

        /**
         * @return the native unit type whose button set shows the button
         */
        public @NotNull UnitType getProducerType() {
            return this.producerType;
        }

        /**
         * @return the command card position from {@link #FIRST_BUTTON_POSITION} through {@link #LAST_BUTTON_POSITION}
         */
        public int getPosition() {
            return this.position;
        }

        /**
         * @return the command the button issues
         */
        public @NotNull ProductionAction getAction() {
            return this.action;
        }

        /**
         * @return the key that presses the button
         */
        public char getHotkey() {
            return this.hotkey;
        }

        /**
         * @return the tooltip text of the enabled button in the default language
         */
        public @NotNull String getText() {
            return this.text.getEnabledText();
        }

        /**
         * Finds the tooltip texts of a client locale.
         *
         * @param locale the client locale ({@code enUS}, {@code koKR}, ...), or {@code null} when unknown
         * @return the translation of the locale, or the texts of the default language
         */
        public @NotNull Text getText(@Nullable String locale) {
            Text translation = locale == null ? null : this.translations.get(locale);

            return translation == null ? this.text : translation;
        }
    }

    /**
     * Builds {@link ScrAddedUnitType} instances.
     */
    public static final class Builder {
        private final @NotNull UnitType type;
        private final @NotNull String name;
        private final @NotNull UnitType baseType;

        private @NotNull UnitType requirementType;
        private @Nullable ScrRequirementScript requirementScript;
        private @NotNull UnitType buttonSetType;
        private @NotNull UnitType wireframeType;
        private @Nullable TrainButton trainButton;
        private int wireframe;
        private int commandIcon;

        private final int @NotNull [] values;
        private final int @NotNull [] traits;

        /**
         * Creates a {@link Builder} instance whose values, requirement script, button set and wireframe are those of a native base type, without a
         * train button.
         *
         * @param type     the engine identity of the added type, not registered anywhere yet
         * @param name     the name the interface shows for the type
         * @param baseType the native unit type whose values are copied
         * @param units    the installed {@code units.dat} table the values are read from
         * @throws IllegalArgumentException if the base type is not a native unit type, is a building or has a subunit
         */
        public Builder(@NotNull UnitType type, @NotNull String name, @NotNull UnitType baseType, @NotNull DatTable units) {
            int baseId = ScrUnits.getNativeId(baseType);

            if (baseId >= ScrUnitTypeView.FIRST_INFESTATION_TYPE || units.get(UnitsDat.FIRST_SUBUNIT, baseId) != ScrUnitIds.NONE) {
                throw Exceptions.newIllegalArgumentException("The base type {0} of an added unit type must be a trained unit without a subunit.",
                                                             baseType);
            }

            List<String> fields = ScrDatKind.UNITS.getFields();
            ScrUnitTypeView.TraitColumn[] traitColumns = ScrUnitTypeView.TraitColumn.values();

            this.type     = type;
            this.name     = name;
            this.baseType = baseType;

            this.requirementType   = baseType;
            this.requirementScript = null;
            this.buttonSetType     = baseType;
            this.wireframeType     = baseType;
            this.trainButton       = null;
            this.wireframe         = NO_INTERFACE_FRAME;
            this.commandIcon       = NO_INTERFACE_FRAME;

            this.values = new int[fields.size()];
            this.traits = new int[traitColumns.length];

            for (int index = 0; index < this.values.length; index++) {
                String field = fields.get(index);
                int element = baseId - ScrDatKind.UNITS.getFirstType(field);

                this.values[index] = element >= 0 && element < units.getCount(field) ? units.get(field, element) : 0;
            }

            for (ScrUnitTypeView.TraitColumn column : traitColumns) {
                this.traits[column.ordinal()] = ScrUnitTraits.createValues(column)[baseId];
            }
        }

        /**
         * Sets the value of a {@code units.dat} field; a type reference holds the native entry ({@code 228} for none), as the DAT table does.
         *
         * @param field the {@code units.dat} field name ({@link UnitsDat})
         * @param value the value, kept as given
         * @return this instance for chaining
         * @throws IllegalArgumentException if the field does not exist
         */
        public @NotNull Builder setValue(@NotNull String field, int value) {
            this.values[getFieldIndex(field)] = value;

            return this;
        }

        /**
         * Sets the value of a trait column.
         *
         * @param column the trait column
         * @param value  the value
         * @return this instance for chaining
         */
        public @NotNull Builder setTrait(ScrUnitTypeView.@NotNull TraitColumn column, int value) {
            this.traits[column.ordinal()] = value;

            return this;
        }

        /**
         * Sets the native unit type whose build requirement script the added type runs.
         *
         * @param requirementType the native unit type
         * @return this instance for chaining
         * @throws IllegalArgumentException if the type is not a native unit type
         */
        public @NotNull Builder setRequirementType(@NotNull UnitType requirementType) {
            ScrUnits.getNativeId(requirementType);

            this.requirementType = requirementType;

            return this;
        }

        /**
         * Sets the own production requirement script of the added type, which the type runs instead of the build requirement script of its
         * requirement type ({@link #setRequirementType}).
         *
         * @param requirementScript the requirement script, or {@code null} for the script of the requirement type
         * @return this instance for chaining
         */
        public @NotNull Builder setRequirementScript(@Nullable ScrRequirementScript requirementScript) {
            this.requirementScript = requirementScript;

            return this;
        }

        /**
         * Sets the native unit type whose button set a unit of the added type shows.
         *
         * @param buttonSetType the native unit type
         * @return this instance for chaining
         * @throws IllegalArgumentException if the type is not a native unit type
         */
        public @NotNull Builder setButtonSetType(@NotNull UnitType buttonSetType) {
            ScrUnits.getNativeId(buttonSetType);

            this.buttonSetType = buttonSetType;

            return this;
        }

        /**
         * Sets the native unit type whose wireframe the status panel and whose command icon the train button and the observer production list
         * show for the added type.
         *
         * @param wireframeType the native unit type
         * @return this instance for chaining
         * @throws IllegalArgumentException if the type is not a native unit type
         */
        public @NotNull Builder setWireframeType(@NotNull UnitType wireframeType) {
            ScrUnits.getNativeId(wireframeType);

            this.wireframeType = wireframeType;

            return this;
        }

        /**
         * Sets the added wireframe the status panel shows for the added type instead of the wireframe type's.
         *
         * @param wireframe the frame of an added wireframe ({@link ScrAddedGraphics#addWireframe}), or {@link #NO_INTERFACE_FRAME} for the wireframe type's
         * @return this instance for chaining
         * @throws IllegalArgumentException if the frame is neither an added frame nor {@link #NO_INTERFACE_FRAME}
         */
        public @NotNull Builder setWireframe(int wireframe) {
            this.wireframe = checkInterfaceFrame(wireframe);

            return this;
        }

        /**
         * Sets the added command icon the train button, the production queue and the observer production list show for the added type instead of
         * the wireframe type's.
         *
         * @param commandIcon the frame of an added command icon ({@link ScrAddedGraphics#addCommandIcon}), or {@link #NO_INTERFACE_FRAME} for the wireframe type's
         * @return this instance for chaining
         * @throws IllegalArgumentException if the frame is neither an added frame nor {@link #NO_INTERFACE_FRAME}
         */
        public @NotNull Builder setCommandIcon(int commandIcon) {
            this.commandIcon = checkInterfaceFrame(commandIcon);

            return this;
        }

        /**
         * Sets the button a native producer shows for the added type.
         *
         * @param trainButton the button, or {@code null} for none
         * @return this instance for chaining
         */
        public @NotNull Builder setTrainButton(@Nullable TrainButton trainButton) {
            this.trainButton = trainButton;

            return this;
        }

        /**
         * Builds a {@link ScrAddedUnitType} instance with the current values.
         *
         * @return the built {@link ScrAddedUnitType} instance
         * @throws IllegalStateException if the button is a {@link ProductionAction#BUILDING_MORPH} button and the type is no building morph result
         */
        public @NotNull ScrAddedUnitType build() {
            TrainButton button = this.trainButton;

            if (button != null && button.getAction() == ProductionAction.BUILDING_MORPH &&
                (this.traits[ScrUnitTypeView.TraitColumn.TRAITS.ordinal()] & UnitTraitFlags.BUILDING_MORPH_RESULT) == 0) {
                throw Exceptions.newIllegalStateException("The building morph button of {0} needs a building morph result.", this.type);
            }

            return new ScrAddedUnitType(this);
        }
    }

    private static int checkInterfaceFrame(int frame) {
        if (frame != NO_INTERFACE_FRAME && frame < ScrAddedGraphics.FIRST_INTERFACE_FRAME) {
            throw Exceptions.newIllegalArgumentException("Not an added interface frame: {0}", frame);
        }

        return frame;
    }

    private static int getFieldIndex(@NotNull String field) {
        int index = ScrDatKind.UNITS.getFields().indexOf(field);

        if (index < 0) {
            throw Exceptions.newIllegalArgumentException("The units.dat field does not exist: {0}", field);
        }

        return index;
    }


    private final @NotNull UnitType type;
    private final @NotNull String name;
    private final @NotNull UnitType baseType;

    private final @NotNull UnitType requirementType;
    private final @Nullable ScrRequirementScript requirementScript;
    private final @NotNull UnitType buttonSetType;
    private final @NotNull UnitType wireframeType;
    private final @Nullable TrainButton trainButton;
    private final int wireframe;
    private final int commandIcon;

    private final int @NotNull [] values;
    private final int @NotNull [] traits;

    private ScrAddedUnitType(@NotNull Builder builder) {
        this.type     = builder.type;
        this.name     = builder.name;
        this.baseType = builder.baseType;

        this.requirementType   = builder.requirementType;
        this.requirementScript = builder.requirementScript;
        this.buttonSetType     = builder.buttonSetType;
        this.wireframeType     = builder.wireframeType;
        this.trainButton       = builder.trainButton;
        this.wireframe         = builder.wireframe;
        this.commandIcon       = builder.commandIcon;

        this.values = builder.values.clone();
        this.traits = builder.traits.clone();
    }

    /**
     * @return the engine identity of the type
     */
    public @NotNull UnitType getType() {
        return this.type;
    }

    /**
     * @return the name the interface shows for the type
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the native unit type whose values the type started from
     */
    public @NotNull UnitType getBaseType() {
        return this.baseType;
    }

    /**
     * @return the native unit type whose build requirement script the type runs
     */
    public @NotNull UnitType getRequirementType() {
        return this.requirementType;
    }

    /**
     * @return the own production requirement script of the type, or {@code null} if the type runs the script of its requirement type
     */
    public @Nullable ScrRequirementScript getRequirementScript() {
        return this.requirementScript;
    }

    /**
     * @return the native unit type whose button set a unit of the type shows
     */
    public @NotNull UnitType getButtonSetType() {
        return this.buttonSetType;
    }

    /**
     * @return the native unit type whose wireframe and command icon the interface shows for the type
     */
    public @NotNull UnitType getWireframeType() {
        return this.wireframeType;
    }

    /**
     * @return the frame of the type's added wireframe, or {@link #NO_INTERFACE_FRAME} if the interface shows the wireframe type's
     */
    public int getWireframe() {
        return this.wireframe;
    }

    /**
     * @return the frame of the type's added command icon, or {@link #NO_INTERFACE_FRAME} if the interface shows the wireframe type's
     */
    public int getCommandIcon() {
        return this.commandIcon;
    }

    /**
     * @return the button a native producer shows for the type, or {@code null} if no producer shows one
     */
    public @Nullable TrainButton getTrainButton() {
        return this.trainButton;
    }

    /**
     * Returns the value of a {@code units.dat} field.
     *
     * @param field the {@code units.dat} field name ({@link UnitsDat})
     * @return the value
     * @throws IllegalArgumentException if the field does not exist
     */
    public int getValue(@NotNull String field) {
        return this.values[getFieldIndex(field)];
    }

    /**
     * @param column the trait column
     * @return the value of the trait column
     */
    public int getTrait(ScrUnitTypeView.@NotNull TraitColumn column) {
        return this.traits[column.ordinal()];
    }
}
