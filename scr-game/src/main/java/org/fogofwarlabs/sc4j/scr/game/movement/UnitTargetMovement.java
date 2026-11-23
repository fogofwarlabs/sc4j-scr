package org.fogofwarlabs.sc4j.scr.game.movement;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.order.OrderPool;
import org.fogofwarlabs.sc4j.scr.game.path.PathPool;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.jetbrains.annotations.NotNull;

/**
 * Applies target movement and sprite-bound range checks.
 */
public final class UnitTargetMovement {
    /**
     * @param unit the moving unit
     * @return {@code true} if the sprite has reached the exact movement destination, {@code false} otherwise
     */
    public static boolean isAtMovementTarget(@NotNull Unit unit) {
        Sprite sprite = unit.getSpriteOrNull();

        return sprite != null && sprite.getX() == unit.getMovementDestinationX() && sprite.getY() == unit.getMovementDestinationY();
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitTargetMovement} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitTargetMovement(@NotNull Game game) {
        this.game = game;
    }

    /**
     * @param unit   the source unit, measured from its main unit
     * @param target the target unit
     * @param range  the pixel range
     * @return {@code true} if the bounding boxes are within range, {@code false} otherwise
     * @throws NullPointerException  if the world was built without game data
     * @throws IllegalStateException if the main unit or the target has no sprite
     */
    public boolean isTargetInRange(@NotNull Unit unit, @NotNull Unit target, int range) {
        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();

        // Measure the range from the main unit.
        unit = this.game.getUnitTargeting().getMainUnit(unit);

        Sprite sprite = unit.getSpriteOrNull();
        Sprite targetSprite = target.getSpriteOrNull();

        if (sprite == null || targetSprite == null) {
            throw Exceptions.newIllegalStateException("Target range requires both sprites.");
        }

        int horizontal = Math.max(0,
                                  Math.max(sprite.getX() - unitTypes.getDimensionLeft(unit.getUnitId()) - targetSprite.getX() -
                                           unitTypes.getDimensionRight(target.getUnitId()) - 1,
                                           targetSprite.getX() - unitTypes.getDimensionLeft(target.getUnitId()) - sprite.getX() -
                                           unitTypes.getDimensionRight(unit.getUnitId()) - 1));
        int vertical = Math.max(0,
                                Math.max(sprite.getY() - unitTypes.getDimensionUp(unit.getUnitId()) - targetSprite.getY() -
                                         unitTypes.getDimensionDown(target.getUnitId()) - 1,
                                         targetSprite.getY() - unitTypes.getDimensionUp(target.getUnitId()) - sprite.getY() -
                                         unitTypes.getDimensionDown(unit.getUnitId()) - 1));

        return RegionPathSearch.calculateDistance(0, horizontal, 0, vertical) <= range;
    }


    /**
     * Assigns a target without changing the facing waypoint, retaining a destination on its contact border.
     * <p>
     * A pickup request keeps its own order and queue changes even when it returns {@code false}.
     *
     * @param unit   the moving unit
     * @param target the movement target
     * @return {@code true} if a computer transport pickup does not start, {@code false} otherwise
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if a retained path has no path pool
     * @throws IllegalStateException         if the target has no sprite
     */
    public boolean moveToTarget(@NotNull Unit unit, @NotNull Unit target) {
        if (unit.getMovementTargetUnit() == target) {
            Sprite targetSprite = target.requireSprite();
            int positionX = (short) targetSprite.getX();
            int positionY = (short) targetSprite.getY();

            if ((short) unit.getMovementDestinationX() == positionX && (short) unit.getMovementDestinationY() == positionY ||
                this.isOnContactBorder(unit, target)) {
                unit.setStatus(unit.getStatus() & ~UnitStatusFlags.IMMOVABLE);

                return true;
            }
        }

        if (unit.getComputerRecord() != null && unit.getPrimaryOrder() != ScrOrderIds.PICKUP4) {
            int positionY = (short) target.requireSprite().getY();
            int positionX = (short) target.requireSprite().getX();

            if (this.game.getHooks().requiresPickup(unit, positionX, positionY)) {
                return false;
            }
        }

        int pathIndex = unit.getPathIndex();

        if (pathIndex >= 0) {
            PathPool paths = this.game.getPathSystem().getPathPool();

            if (paths == null) {
                throw Exceptions.newUnsupportedOperationException("Target movement requires the retained path pool.");
            }

            paths.setFlags(pathIndex, paths.getFlags(pathIndex) | PathPool.FLAG_NEW_MOVE_TARGET);
        }

        int positionX = (short) target.requireSprite().getX();
        int positionY = (short) target.requireSprite().getY();

        if ((short) unit.getMovementDestinationX() != positionX || (short) unit.getMovementDestinationY() != positionY ||
            unit.getMovementTargetUnit() != target) {
            unit.setMovementDestinationY(positionY);

            int flags = unit.getMovementFlags() & ~MovementFlags.BRAKING | MovementFlags.ORDERED;

            unit.setMovementDestinationX(positionX);
            unit.setMovementTargetUnit(target);
            unit.setMovementFlags(flags);
            unit.setMovementWaypointX(unit.getMovementDestinationX());
            unit.setMovementWaypointY(unit.getMovementDestinationY());
        }

        unit.setMovementDestinationCountdown(UnitPositioning.DESTINATION_COUNTDOWN);
        unit.setStatus(unit.getStatus() & ~UnitStatusFlags.IMMOVABLE);

        OrderPool.Order nextOrder = unit.getFirstOrder();

        if (nextOrder != null && this.game.requireGameData().getOrderTypes().getCanBeLifted(nextOrder.getOrderId()) == 0) {
            unit.setMovementFlags(unit.getMovementFlags() | MovementFlags.PASSING_THROUGH);
        } else {
            unit.setMovementFlags(unit.getMovementFlags() & ~MovementFlags.PASSING_THROUGH);
        }

        return true;
    }

    /**
     * Assigns a target and updates the facing waypoint even when a computer pickup starts.
     * <p>
     * A {@code false} core result still updates facing and is returned unchanged.
     *
     * @param unit   the moving unit
     * @param target the movement target
     * @return {@code true} if a computer transport pickup does not start, {@code false} otherwise
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if a retained path has no path pool
     * @throws IllegalStateException         if the target has no sprite
     */
    public boolean moveToTargetReset(@NotNull Unit unit, @NotNull Unit target) {
        boolean moved = this.moveToTarget(unit, target);
        int positionY = (short) target.requireSprite().getY();
        int positionX = (short) target.requireSprite().getX();

        if ((short) unit.getDirectionTargetX() != positionX || (short) unit.getDirectionTargetY() != positionY) {
            unit.setDirectionTargetX(positionX);
            unit.setDirectionTargetY(positionY);
        }

        return moved;
    }

    /**
     * @param unit   the moving unit
     * @param target the movement target
     * @return {@code true} if the movement destination of the unit lies on the asymmetric signed-WORD contact border of the target, {@code false} otherwise
     * @throws IllegalStateException if the target has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    private boolean isOnContactBorder(@NotNull Unit unit, @NotNull Unit target) {
        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        Sprite sprite = target.requireSprite();
        int targetType = target.getUnitId();
        int followerType = unit.getUnitId();
        short left = (short) ((short) sprite.getX() - unitTypes.getDimensionLeft(targetType) - 1 - unitTypes.getDimensionRight(followerType));
        short top = (short) ((short) sprite.getY() - unitTypes.getDimensionUp(targetType) - 1 - unitTypes.getDimensionDown(followerType));
        short right = (short) ((short) sprite.getX() + unitTypes.getDimensionRight(targetType) + unitTypes.getDimensionLeft(followerType) + 1 +
                               unitTypes.getDimensionUp(followerType) + 1);
        short bottom = (short) ((short) sprite.getY() + unitTypes.getDimensionDown(targetType));
        short positionX = (short) unit.getMovementDestinationX();
        short positionY = (short) unit.getMovementDestinationY();

        if (positionX == left || positionX == right) {
            return positionY >= top && positionY <= bottom;
        }

        if (positionY == top || positionY == bottom) {
            return positionX >= left && positionX <= right;
        }

        return false;
    }
}
