package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.resource.CarryingFlags;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.function.Predicate;

/**
 * Selects and marks resources for computer workers, finds mineral fields for every worker and runs the nearest-unit
 * searches of the computer player.
 * <p>
 * The searches walk the sorted unit edge lists of the {@link UnitSpatialIndex} outward from a start position and shrink
 * the search box to the best distance found. Whether a computer worker claimed a resource is held by
 * {@link Unit#isResourceClaimedByComputer()}.
 */
public final class ComputerResources {
    /**
     * The mineral search radius of a town with more workers and of a worker whose mineral field is being gathered.
     */
    private static final int MINERAL_SEARCH_RADIUS = 0x100;
    /**
     * The mineral search radius of a town with few workers.
     */
    private static final int FEW_WORKERS_MINERAL_SEARCH_RADIUS = 0x200;
    /**
     * The mineral search radius of a worker that looks for a mineral field without a target.
     */
    private static final int NEARBY_MINERAL_SEARCH_RADIUS = 0x180;
    /**
     * The factor of the search radius that limits the ground route distance from the worker to a mineral field.
     */
    private static final int MINERAL_ROUTE_RADIUS_FACTOR = 2;
    /**
     * The radius around an SCV within which a unit to repair is searched.
     */
    private static final int REPAIR_SEARCH_RADIUS = 0x800;

    private final @NotNull Game game;

    /**
     * Creates a {@link ComputerResources} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public ComputerResources(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Returns the VF4 ground height of the overlay tile under a pixel, or of the base tile without an overlay tile.
     *
     * @param positionX the horizontal pixel position
     * @param positionY the vertical pixel position
     * @return the ground height
     * @throws IllegalStateException if the terrain tables cannot be loaded or the map has no overlay tiles
     * @throws NullPointerException  if the world was built without game data
     */
    public int getTerrainHeight(int positionX, int positionY) {
        GameMap map = this.game.getMap();

        try {
            return map.getTerrainHeight(this.game.requireGameData().getTerrainTileset(map.getTileset()), positionX, positionY);
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Native terrain height could not load original terrain tables.");
        }
    }

    /**
     * Finds the nearest mineral field that the worker may gather and that no worker is gathering, within {@code 256} pixels
     * of the search center, or {@code 512} pixels for a computer town with few workers. With few workers, the worker of a computer player
     * also skips the fields that computer workers claimed.
     * <p>
     * The worker may gather a mineral field whose surrounding tiles are not all {@link MapTileFlags#OCCUPIED}, that it reaches
     * on the ground, that stands at the ground height of the search center on a tile its owner explored, and whose ground
     * route from the worker is at most twice the search radius long. Nothing is found while no neutral mineral field is
     * completed.
     *
     * @param worker     the worker
     * @param positionX  the search center horizontal WORD
     * @param positionY  the search center vertical WORD
     * @param fewWorkers whether the worker belongs to a computer town with at most twelve workers
     * @return the nearest such mineral field, or {@code null}
     */
    public @Nullable Unit findMineral(@NotNull Unit worker, int positionX, int positionY, boolean fewWorkers) {
        if (!this.hasCompletedMineralField()) {
            return null;
        }

        int centerX = (short) positionX;
        int centerY = (short) positionY;
        int radius = fewWorkers ? FEW_WORKERS_MINERAL_SEARCH_RADIUS : MINERAL_SEARCH_RADIUS;

        return this.findAroundPosition(centerX, centerY, radius,
                                       candidate -> {
                                           if (!this.isMineralAvailable(candidate, worker, centerX, centerY, radius)) {
                                               return false;
                                           }

                                           if (fewWorkers) {
                                               Player owner = this.game.getPlayerTable().getPlayer(worker.getOwnerIndex());

                                               if (owner != null && owner.getType() == PlayerTypes.COMPUTER &&
                                                   candidate.isResourceClaimedByComputer()) {
                                                   return false;
                                               }
                                           }

                                           return !candidate.isResourceBeingGathered();
                                       });
    }

    /**
     * Finds the nearest mineral field that the worker may gather within {@code 384} pixels of the search center, whether or
     * not a worker is gathering it. The worker may gather a mineral field as for {@link #findMineral}, with this search radius.
     *
     * @param worker    the worker
     * @param positionX the search center horizontal WORD
     * @param positionY the search center vertical WORD
     * @return the nearest such mineral field, or {@code null}
     */
    public @Nullable Unit findNearbyMineral(@NotNull Unit worker, int positionX, int positionY) {
        if (!this.hasCompletedMineralField()) {
            return null;
        }

        int centerX = (short) positionX;
        int centerY = (short) positionY;

        return this.findAroundPosition(centerX, centerY, NEARBY_MINERAL_SEARCH_RADIUS,
                                       candidate -> this.isMineralAvailable(candidate, worker, centerX, centerY, NEARBY_MINERAL_SEARCH_RADIUS));
    }

    /**
     * @return {@code true} if a neutral mineral field of any of the three types is completed, {@code false} otherwise
     * @throws IllegalStateException if the unit accounting is not initialized
     */
    private boolean hasCompletedMineralField() {
        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();

        if (accounting == null) {
            throw Exceptions.newIllegalStateException("Mineral search requires the unit accounting.");
        }

        return this.game.getComputerUnits().getCompletedTypeCount(ScrUnitIds.RESOURCE_MINERAL_FIELD, PlayerTable.NEUTRAL_PLAYER_ID) != 0 ||
               this.game.getComputerUnits().getCompletedTypeCount(ScrUnitIds.RESOURCE_MINERAL_FIELD_TYPE_2, PlayerTable.NEUTRAL_PLAYER_ID) != 0 ||
               this.game.getComputerUnits().getCompletedTypeCount(ScrUnitIds.RESOURCE_MINERAL_FIELD_TYPE_3, PlayerTable.NEUTRAL_PLAYER_ID) != 0;
    }

    /**
     * Tests whether the worker may gather a mineral field found around the search center: the mineral field is not
     * {@linkplain #isEnclosed enclosed}, the worker reaches it on the ground, it stands at the ground height of the search
     * center on a tile the worker's owner explored, and its ground route to the worker is at most twice the radius long.
     *
     * @param mineral the candidate unit
     * @param worker  the worker
     * @param centerX the search center horizontal position
     * @param centerY the search center vertical position
     * @param radius  the search radius
     * @return {@code true} if the candidate is a mineral field the worker may gather, {@code false} otherwise
     * @throws IllegalStateException if the mineral field or the worker has no sprite
     */
    private boolean isMineralAvailable(@NotNull Unit mineral, @NotNull Unit worker, int centerX, int centerY, int radius) {
        int type = mineral.getUnitId();

        if (!this.game.requireGameData().getUnitTypes().isMineralField(type)) {
            return false;
        }

        if (this.isEnclosed(mineral)) {
            return false;
        }

        Sprite mineralSprite = mineral.requireSprite();
        int mineralX = (short) mineralSprite.getX();
        int mineralY = (short) mineralSprite.getY();

        if (!this.game.getUnitPositioning().isGroundReachable(worker, mineralX, mineralY)) {
            return false;
        }

        if (this.getTerrainHeight(centerX, centerY) != this.getTerrainHeight(mineralX, mineralY)) {
            return false;
        }

        GameMap map = this.game.getMap();

        if ((map.getTileFlagsAtPosition(mineralX, mineralY) >>> (worker.getOwnerIndex() + MapTileFlags.NOT_EXPLORED_SHIFT & Integer.SIZE - 1) & 1) != 0) {
            return false;
        }

        Sprite workerSprite = worker.requireSprite();

        return this.game.getUnitPositioning().getGroundPathDistance(mineral, (short) workerSprite.getX(), (short) workerSprite.getY()) <=
               radius * MINERAL_ROUTE_RADIUS_FACTOR;
    }

    /**
     * Tests whether the ring of tiles around the unit bounds is {@link MapTileFlags#OCCUPIED}: the rows above and below the
     * bounds over the bound columns, and the columns left and right of the bounds over the bound rows, each clamped to the
     * map.
     *
     * @param unit the unit
     * @return {@code true} if every tested tile is occupied, {@code false} otherwise
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    private boolean isEnclosed(@NotNull Unit unit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = unit.getUnitId();
        Sprite sprite = unit.requireSprite();
        int boundLeft = (short) ((short) sprite.getX() - units.getDimensionLeft(type));
        int boundTop = (short) ((short) sprite.getY() - units.getDimensionUp(type));
        int boundRight = (short) ((short) sprite.getX() + units.getDimensionRight(type));
        int boundBottom = (short) ((short) sprite.getY() + units.getDimensionDown(type));
        int widthTiles = (short) (units.getDimensionRight(type) + 1 + units.getDimensionLeft(type)) / TerrainTileset.TILE_SIZE;
        int heightTiles = (short) (units.getDimensionUp(type) + 1 + units.getDimensionDown(type)) / TerrainTileset.TILE_SIZE;
        GameMap map = this.game.getMap();
        int mapWidth = map.getWidth();
        int mapHeight = map.getHeight();
        int leftTile = boundLeft / TerrainTileset.TILE_SIZE;
        int topTile = boundTop / TerrainTileset.TILE_SIZE;
        int bottomRow = Math.min(boundBottom / TerrainTileset.TILE_SIZE + 1, mapHeight - 1);
        int rightColumn = Math.min(boundRight / TerrainTileset.TILE_SIZE + 1, mapWidth - 1);
        int leftColumn = Math.max(0, leftTile - 1);
        int topRow = Math.max(0, topTile - 1);

        for (int column = leftTile, remaining = widthTiles; remaining != 0; column++, remaining--) {
            if ((map.getTileFlags(column, topRow) & MapTileFlags.OCCUPIED) == 0 ||
                (map.getTileFlags(column, bottomRow) & MapTileFlags.OCCUPIED) == 0) {
                return false;
            }
        }

        for (int row = topTile, remaining = heightTiles; remaining != 0; row++, remaining--) {
            if ((map.getTileFlags(leftColumn, row) & MapTileFlags.OCCUPIED) == 0 ||
                (map.getTileFlags(rightColumn, row) & MapTileFlags.OCCUPIED) == 0) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns carried resources, or starts gathering the primary or else the fallback resource and claims it.
     *
     * @param worker           the worker
     * @param primary          the preferred resource
     * @param fallback         the replacement resource
     * @param instructionLimit the iscript budget for issued orders
     * @throws UnsupportedOperationException if the gathered resource has no sprite
     */
    void assignWorker(@NotNull Unit worker, @Nullable Unit primary, @Nullable Unit fallback, int instructionLimit) {
        int carrying = worker.getCarryingFlags();

        if ((carrying & CarryingFlags.MINERALS) != 0) {
            this.game.getOrders().issueUntargeted(worker, ScrOrderIds.RETURN_MINERALS, instructionLimit);

            return;
        }

        if ((carrying & CarryingFlags.GAS) != 0) {
            this.game.getOrders().issueUntargeted(worker, ScrOrderIds.RETURN_GAS, instructionLimit);

            return;
        }

        Unit target = primary;

        if (target == null || this.game.requireGameData().getBaseType(target) == ScrUnitIds.RESOURCE_VESPENE_GEYSER ||
            (target.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
            target = fallback;
        }

        if (target == null || this.game.requireGameData().getBaseType(target) == ScrUnitIds.RESOURCE_VESPENE_GEYSER ||
            (target.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
            return;
        }

        Sprite sprite = target.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Computer gather orders require a resource sprite.");
        }

        this.game.getOrders().issue(worker, ScrOrderIds.HARVEST1, target, sprite.getX(), sprite.getY(), ScrUnitIds.NONE, false, instructionLimit);
        target.setResourceClaimedByComputer(true);
    }

    /**
     * Finds over the whole map, from the unit's own edges (or for a hidden unit from its position), the nearest worker of
     * the owner that is not loaded and that its town reserves.
     *
     * @param unit the unit to repair
     * @return the repairing worker, or {@code null}
     * @throws UnsupportedOperationException if the unit spatial index is missing
     * @throws IllegalStateException         if the unit has no sprite
     */
    @Nullable Unit findRepairer(@NotNull Unit unit) {
        UnitSpatialIndex index = this.game.getUnitSystem().getUnitSpatialIndex();

        if (index == null) {
            throw Exceptions.newUnsupportedOperationException("Computer repair search requires the unit spatial index.");
        }

        unit.requireSprite();

        GameMap map = this.game.getMap();

        return index.findNearest(unit,
                                 0, 0, (short) (map.getWidth() * TerrainTileset.TILE_SIZE), (short) (map.getHeight() * TerrainTileset.TILE_SIZE),
                                 candidate -> {
                                     if (candidate.getOwnerIndex() != unit.getOwnerIndex() || !this.game.getComputerUnits().isWorker(candidate) ||
                                         (candidate.getStatus() & UnitStatusFlags.LOADED) != 0) {
                                         return false;
                                     }

                                     ComputerUnitRecord record = candidate.getComputerRecord();

                                     return record != null && record.town != null && record.town.reservedWorker == candidate;
                                 });
    }

    /**
     * Finds within {@link #REPAIR_SEARCH_RADIUS} of an SCV, from its own edges, the nearest non-worker non-building unit of
     * the owner whose order target is the SCV, in the SCV's ground component, that the SCV may repair: Terran,
     * mechanical, completed and below its maximum hit points.
     *
     * @param worker the SCV
     * @return the unit to repair, or {@code null}
     * @throws UnsupportedOperationException if the unit spatial index is missing
     * @throws IllegalStateException         if the SCV has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    @Nullable Unit findRepairTarget(@NotNull Unit worker) {
        UnitSpatialIndex index = this.game.getUnitSystem().getUnitSpatialIndex();

        if (index == null) {
            throw Exceptions.newUnsupportedOperationException("Computer repair search requires the unit spatial index.");
        }

        worker.requireSprite();

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        return index.findNearest(worker, REPAIR_SEARCH_RADIUS, candidate -> {
            int type = candidate.getUnitId();

            if (candidate.getOwnerIndex() != worker.getOwnerIndex() || this.game.getComputerUnits().isWorker(candidate) ||
                UnitQueries.isBuilding(units, type) ||
                candidate.getOrderTargetUnit() != worker) {
                return false;
            }

            if (!this.game.getUnitTargeting().isReachable(candidate, worker)) {
                return false;
            }

            int groupFlags = units.getGroupFlags(type);
            boolean terran = (groupFlags & UnitGroupFlags.ZERG) == 0 && (groupFlags & UnitGroupFlags.PROTOSS) == 0 &&
                             (groupFlags & UnitGroupFlags.TERRAN) != 0;

            return terran && (units.getFlags(type) & UnitTypeFlags.MECHANICAL) != 0 &&
                   (candidate.getStatus() & UnitStatusFlags.COMPLETED) != 0 &&
                   candidate.getHitPoints() < this.game.getUnitHealth().getMaximumHitPoints(candidate);
        });
    }

    /**
     * Finds over the whole map, from the unit's position, the nearest unit of the type (any type for
     * {@link ScrUnitIds#NONE}) that is an enemy of the searching unit.
     *
     * @param unit the searching unit
     * @param type the unit type, or {@link ScrUnitIds#NONE} for any
     * @return the nearest such unit, or {@code null}
     * @throws UnsupportedOperationException if the unit spatial index is missing
     * @throws IllegalStateException         if the searching unit has no sprite
     */
    @Nullable Unit findNearestEnemyOfType(@NotNull Unit unit, int type) {
        UnitSpatialIndex index = this.game.getUnitSystem().getUnitSpatialIndex();

        if (index == null) {
            throw Exceptions.newUnsupportedOperationException("Computer enemy searches require the unit spatial index.");
        }

        Sprite sprite = unit.requireSprite();
        int positionX = (short) sprite.getX();
        int positionY = (short) sprite.getY();
        GameMap map = this.game.getMap();

        return index.findNearest(positionX, positionY,
                                 0, 0, (short) (map.getWidth() * TerrainTileset.TILE_SIZE), (short) (map.getHeight() * TerrainTileset.TILE_SIZE),
                                 candidate -> (this.game.requireGameData().getBaseType(candidate) == type || type == ScrUnitIds.NONE) &&
                                              this.game.getComputerUnits().isEnemy(unit.getOwnerIndex(), candidate));
    }

    /**
     * Finds over the whole map, from a position, the nearest disabled (unpowered) unit of the owner.
     *
     * @param owner     the player
     * @param positionX the horizontal search center
     * @param positionY the vertical search center
     * @return the unit, or {@code null}
     * @throws UnsupportedOperationException if the unit spatial index is missing
     */
    @Nullable Unit findUnpowered(int owner, int positionX, int positionY) {
        UnitSpatialIndex index = this.game.getUnitSystem().getUnitSpatialIndex();

        if (index == null) {
            throw Exceptions.newUnsupportedOperationException("Computer Pylon anchor search requires the unit spatial index.");
        }

        GameMap map = this.game.getMap();

        return index.findNearest(positionX, positionY,
                                 0, 0, (short) (map.getWidth() * TerrainTileset.TILE_SIZE), (short) (map.getHeight() * TerrainTileset.TILE_SIZE),
                                 candidate -> (candidate.getStatus() & UnitStatusFlags.DISABLED) != 0 && candidate.getOwnerIndex() == owner);
    }

    /**
     * Finds the nearest accepted unit inside the box of the unit's position plus or minus the radius (16-bit
     * coordinates), starting at its own edges or hidden sprite position and skipping the unit itself.
     *
     * @param unit   the searching unit
     * @param radius the search radius
     * @param accept the candidate test
     * @return the nearest accepted unit, or {@code null}
     * @throws UnsupportedOperationException if the unit spatial index is missing
     */
    @Nullable Unit findAround(@NotNull Unit unit, int radius, @NotNull Predicate<Unit> accept) {
        UnitSpatialIndex index = this.game.getUnitSystem().getUnitSpatialIndex();

        if (index == null) {
            throw Exceptions.newUnsupportedOperationException("Computer unit searches require the unit spatial index.");
        }

        return index.findNearest(unit, radius, accept);
    }

    /**
     * Finds the nearest accepted unit over the whole map from its own edges or hidden sprite position, skipping the unit
     * itself.
     *
     * @param unit   the searching unit
     * @param accept the candidate test
     * @return the nearest accepted unit, or {@code null}
     * @throws UnsupportedOperationException if the unit spatial index is missing
     * @throws IllegalStateException         if the searching unit has no sprite
     */
    @Nullable Unit findOnMap(@NotNull Unit unit, @NotNull Predicate<Unit> accept) {
        UnitSpatialIndex index = this.game.getUnitSystem().getUnitSpatialIndex();

        if (index == null) {
            throw Exceptions.newUnsupportedOperationException("Computer unit searches require the unit spatial index.");
        }

        unit.requireSprite();

        GameMap map = this.game.getMap();

        return index.findNearest(unit,
                                 0, 0, (short) (map.getWidth() * TerrainTileset.TILE_SIZE), (short) (map.getHeight() * TerrainTileset.TILE_SIZE),
                                 accept);
    }

    /**
     * Finds the nearest accepted unit inside the box of the center plus or minus the radius (16-bit coordinates), starting
     * at the edges of the center position. No unit is skipped before the candidate test.
     *
     * @param centerX the horizontal search center WORD
     * @param centerY the vertical search center WORD
     * @param radius  the search radius
     * @param accept  the candidate test
     * @return the nearest accepted unit, or {@code null}
     * @throws UnsupportedOperationException if the unit spatial index is missing
     */
    private @Nullable Unit findAroundPosition(int centerX, int centerY, int radius, @NotNull Predicate<Unit> accept) {
        UnitSpatialIndex index = this.game.getUnitSystem().getUnitSpatialIndex();

        if (index == null) {
            throw Exceptions.newUnsupportedOperationException("Computer resource search requires the unit spatial index.");
        }

        return index.findNearest(centerX, centerY, radius, accept);
    }
}
