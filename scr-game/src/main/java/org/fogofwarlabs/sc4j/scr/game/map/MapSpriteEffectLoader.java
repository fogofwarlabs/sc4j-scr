package org.fogofwarlabs.sc4j.scr.game.map;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.core.GameSettings;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkSection;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkThingy;
import org.fogofwarlabs.sc4j.scr.game.ability.DoodadOrders;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffect;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameLoader;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitCreationContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads {@code THG2} records through standalone sprite creation, unit creation or filtering, in record order.
 */
public final class MapSpriteEffectLoader {
    /**
     * Retains a source record, its route and the actual allocation and random-state outcome.
     */
    public static final class Creation {
        private final @NotNull ChkThingy record;
        private final @NotNull GameLoader.InitializationRoute route;

        private final int thingyPoolIndex;
        private final int unitPoolIndex;
        private final int generation;

        private final int randomStateBefore;
        private final int randomStateAfter;
        private final int randomCallsBefore;
        private final int randomCallsAfter;

        private Creation(@NotNull Game game,
                         @NotNull ChkThingy record,
                         @NotNull GameLoader.InitializationRoute route,
                         @Nullable SpriteEffect thingy,
                         @Nullable Unit unit,
                         int randomStateBefore, int randomCallsBefore) {
            this.record = record;
            this.route  = route;

            this.thingyPoolIndex = thingy == null ? -1 : thingy.getPoolIndex();
            this.unitPoolIndex   = unit == null ? 0 : unit.getIndex();
            this.generation      = unit == null ? 0 : game.getUnitSystem().requireUnitPool().getGeneration(this.unitPoolIndex);

            this.randomStateBefore = randomStateBefore;
            this.randomStateAfter  = game.getRandom().getState();
            this.randomCallsBefore = randomCallsBefore;
            this.randomCallsAfter  = game.getRandom().getTotalRandomCounts();
        }

        /**
         * @return the immutable source record with coordinates, original owner and flags
         */
        public @NotNull ChkThingy getRecord() {
            return this.record;
        }

        /**
         * @return the absolute original CHK record offset
         */
        public int getSourceOffset() {
            return this.record.getSourceOffset();
        }

        /**
         * @return the executed constructor route, including filtered records
         */
        public @NotNull GameLoader.InitializationRoute getRoute() {
            return this.route;
        }

        /**
         * @return the effective owner BYTE passed to a constructor
         */
        public int getOwner() {
            return this.record.getCreationOwner();
        }

        /**
         * @return the allocated standalone slot, or {@code -1} when absent
         */
        public int getThingyPoolIndex() {
            return this.thingyPoolIndex;
        }

        /**
         * @return the allocated unit index, or {@code 0} when absent
         */
        public int getUnitPoolIndex() {
            return this.unitPoolIndex;
        }

        /**
         * @return the unit generation at creation, or {@code 0} when absent
         */
        public int getGeneration() {
            return this.generation;
        }

        /**
         * @return the random state before the record
         */
        public int getRandomStateBefore() {
            return this.randomStateBefore;
        }

        /**
         * @return the random state after the record
         */
        public int getRandomStateAfter() {
            return this.randomStateAfter;
        }

        /**
         * @return the game random call count before the record
         */
        public int getRandomCallsBefore() {
            return this.randomCallsBefore;
        }

        /**
         * @return the game random call count after the record
         */
        public int getRandomCallsAfter() {
            return this.randomCallsAfter;
        }
    }

    /**
     * Executes one complete {@code THG2} occurrence in record order with established creation contexts.
     * <p>
     * A failed standalone allocation does not stop the original section handler. Unit construction and its conditional post-effect retain their
     * existing unsupported boundaries explicitly.
     *
     * @param creation         the unit creation inputs
     * @param game             the initialized native state, including mandatory startup thingy creation
     * @param section          the original {@code THG2} descriptor
     * @param records          the decoded records belonging only to this occurrence
     * @param settings         the established game settings
     * @param expansionEnabled the native expansion flag
     * @param instructionLimit the iscript execution bound
     * @return immutable outcomes for every record, including filtered and failed requests
     * @throws IllegalArgumentException      if the section is not a complete {@code THG2} section or the records are not the complete records of this section occurrence
     * @throws UnsupportedOperationException if a sprite precedes the startup thingy allocation or a unit owner has no record
     */
    public static @NotNull List<Creation> load(@NotNull UnitCreationContext creation, @NotNull Game game,
                                               @NotNull ChkSection section,
                                               @NotNull List<ChkThingy> records,
                                               @NotNull GameSettings settings,
                                               boolean expansionEnabled,
                                               int instructionLimit) {
        if (!section.getName().equals(ChkFile.THINGY_SECTION) || !section.hasCompletePayload() ||
            section.getDeclaredSize() % ChkThingy.ENTRY_SIZE != 0 || records.size() != section.getDeclaredSize() / ChkThingy.ENTRY_SIZE) {
            throw Exceptions.newIllegalArgumentException("Native THG2 processing requires the complete original section records.");
        }

        for (int recordIndex = 0; recordIndex < records.size(); recordIndex++) {
            if (records.get(recordIndex).getSourceOffset() != section.getPayloadOffset() + recordIndex * ChkThingy.ENTRY_SIZE) {
                throw Exceptions.newIllegalArgumentException("The THG2 records do not belong to this ordered section occurrence.");
            }
        }

        List<Creation> creations = new ArrayList<>(records.size());

        for (ChkThingy record : records) {
            int randomStateBefore = game.getRandom().getState();
            int randomCallsBefore = game.getRandom().getTotalRandomCounts();
            SpriteEffect thingy = null;
            Unit unit = null;
            GameLoader.InitializationRoute route;

            if (record.isSprite()) {
                if (game.getSpriteSystem().getStartupSpriteEffect() == null) {
                    throw Exceptions.newUnsupportedOperationException(
                            "Native THG2 sprite creation requires the completed startup sprite effect allocation."
                    );
                }

                route  = GameLoader.InitializationRoute.THG2_SPRITE_CONSTRUCTOR;
                thingy = game.getIscriptExecutor().createSpriteEffect(record.getTypeIdentifier(),
                                                                      record.getHorizontal(), record.getVertical(),
                                                                      record.getOwner(),
                                                                      instructionLimit);
            } else if (record.requestsUnitCreation(settings.usesMapSettings())) {
                route = GameLoader.InitializationRoute.THG2_UNIT_WRAPPER;

                int owner = record.getCreationOwner();
                Player player = game.getPlayerTable().getPlayer(owner < PlayerTable.PLAYER_SLOTS ? owner : PlayerTable.NEUTRAL_PLAYER_ID);

                if (player == null) {
                    throw Exceptions.newUnsupportedOperationException("Native THG2 unit completion requires its decoded owner record.");
                }

                unit = PreplacedUnitLoaderUtils.create(creation, game,
                                                       record.getTypeIdentifier(),
                                                       record.getHorizontal(), record.getVertical(),
                                                       owner, player.getType(),
                                                       expansionEnabled,
                                                       instructionLimit);

                if (unit != null && record.hasPostCreationEffect()) {
                    applyPostCreationEffect(game, unit, instructionLimit);
                }
            } else {
                route = GameLoader.InitializationRoute.THG2_FILTERED;
            }

            creations.add(new Creation(game, record, route, thingy, unit, randomStateBefore, randomCallsBefore));
        }

        return List.copyOf(creations);
    }

    /**
     * Applies the {@code THG2} post-creation effect: disables the created doodad unit ({@link DoodadOrders#disableAtCreation}).
     *
     * @param game             the game state
     * @param unit             the created unit
     * @param instructionLimit the iscript execution bound
     */
    static void applyPostCreationEffect(@NotNull Game game, @NotNull Unit unit, int instructionLimit) {
        game.getDoodadOrders().disableAtCreation(unit, instructionLimit);
    }

    private MapSpriteEffectLoader() {
    }
}
