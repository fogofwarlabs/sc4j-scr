package org.fogofwarlabs.sc4j.scr.client.world;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.jetbrains.annotations.NotNull;

/**
 * Maps the inspection viewport to map pixels without modifying simulation state.
 * <p>
 * Camera movement and zoom affect only client inspection.
 */
public final class ClientCamera {
    /**
     * The time constant of the animated zoom ({@link #zoomToward}) in seconds: the remaining zoom ratio shrinks by {@code 1/e} per time constant, so
     * about {@code 95} percent of a step is done after {@code 300} ms.
     */
    static final double ZOOM_TIME_CONSTANT_SECONDS = 0.1;

    private static final @NotNull Logger LOGGER = Logger.of(ClientCamera.class);
    /**
     * The relative distance to the target zoom below which the animated zoom snaps to it and stops.
     */
    private static final double ZOOM_SETTLE_RATIO = 0.002;
    private static final int CALLER_TRACE_LIMIT = 4;
    /**
     * The divisor that takes a viewport dimension to its center.
     */
    private static final float CENTER_DIVISOR = 2.0f;

    /**
     * Formats the external caller frames retained for camera diagnostics.
     *
     * @return the formatted caller frames
     */
    private static @NotNull String formatCallerFrames() {
        return StackWalker.getInstance().walk(frames -> frames.dropWhile(frame -> !frame.getClassName().equals(ClientCamera.class.getName()))
                                                              .dropWhile(frame -> frame.getClassName().equals(ClientCamera.class.getName()))
                                                              .limit(CALLER_TRACE_LIMIT)
                                                              .map(ClientCamera::formatCallerFrame)
                                                              .toList()).toString();
    }

    /**
     * Formats one caller frame for camera diagnostics.
     *
     * @param frame the caller frame
     * @return the simple class name, method name and line number of the frame
     */
    private static @NotNull String formatCallerFrame(@NotNull StackWalker.StackFrame frame) {
        String type = frame.getClassName();

        return type.substring(type.lastIndexOf('.') + 1) + "." + frame.getMethodName() + ":" + frame.getLineNumber();
    }

    /**
     * Checks the arguments of an immediate or animated zoom.
     *
     * @param factor     the multiplicative zoom factor
     * @param horizontal the horizontal screen position
     * @param vertical   the vertical screen position
     * @throws IllegalArgumentException if the factor is not positive or any argument is not finite
     */
    private static void checkZoomArguments(float factor, float horizontal, float vertical) {
        if (!Float.isFinite(factor) || factor <= 0 || !Float.isFinite(horizontal) || !Float.isFinite(vertical)) {
            throw Exceptions.newIllegalArgumentException("Zoom arguments must be finite and the factor must be positive.");
        }
    }

    /**
     * The screen geometry: the window, the game screen columns and the play view rows ({@link #setViewport}).
     */
    private @NotNull GameViewport viewport;

    private final int mapWidth;
    private final int mapHeight;

    private float horizontalPosition;
    private float verticalPosition;
    private float zoom;

    /**
     * Whether an animated zoom runs ({@link #zoomToward}, {@link #advanceZoom}).
     */
    private boolean zoomAnimating;
    /**
     * The zoom the animation approaches.
     */
    private float targetZoom;
    /**
     * The anchor of the animated zoom: the screen position that keeps showing the anchor's map position.
     */
    private float anchorScreenX;
    private float anchorScreenY;
    private float anchorWorldX;
    private float anchorWorldY;

    /**
     * Creates a {@link ClientCamera} instance at the top-left map corner.
     *
     * @param mapWidth  the map width in pixels
     * @param mapHeight the map height in pixels
     * @throws IllegalArgumentException if either map dimension is negative
     */
    public ClientCamera(int mapWidth, int mapHeight) {
        this.viewport = new GameViewport();

        if (mapWidth < 0 || mapHeight < 0) {
            throw Exceptions.newIllegalArgumentException("Map dimensions cannot be negative.");
        }

        this.mapWidth  = mapWidth;
        this.mapHeight = mapHeight;

        this.horizontalPosition = 0;
        this.verticalPosition   = 0;
        this.zoom               = 1.0f;

        this.zoomAnimating = false;
        this.targetZoom    = 1.0f;
        this.anchorScreenX = 0;
        this.anchorScreenY = 0;
        this.anchorWorldX  = 0;
        this.anchorWorldY  = 0;
    }

    /**
     * @return the world coordinate at the left viewport edge
     */
    public float getX() {
        return this.horizontalPosition;
    }

    /**
     * @return the world coordinate at the top viewport edge
     */
    public float getY() {
        return this.verticalPosition;
    }

    /**
     * @return the number of screen pixels per world pixel
     */
    public float getZoom() {
        return this.zoom;
    }

    /**
     * @return the zoom a running animated zoom approaches, or the current zoom when none runs
     */
    public float getTargetZoom() {
        return this.zoomAnimating ? this.targetZoom : this.zoom;
    }

    /**
     * @return {@code true} if an animated zoom runs, {@code false} otherwise
     */
    public boolean isZoomAnimating() {
        return this.zoomAnimating;
    }

    /**
     * @return the viewport width in screen pixels
     */
    public int getViewportWidth() {
        return this.viewport.getWindowWidth();
    }

    /**
     * @return the viewport height in screen pixels
     */
    public int getViewportHeight() {
        return this.viewport.getWindowHeight();
    }

    /**
     * @return the native scale: the interface scale of the viewport ({@link GameViewport#getNativeZoom}), {@code 480} game rows in the window height at the native aspects
     */
    public float getNativeZoom() {
        return this.viewport.getNativeZoom();
    }

    /**
     * @return the screen geometry the camera uses
     */
    public @NotNull GameViewport getViewport() {
        return this.viewport;
    }

    /**
     * @return the largest map x of the game screen's left edge
     */
    public float getMaximumScreenX() {
        return this.viewport.getMaximumScreenX(this.zoom, this.mapWidth);
    }

    /**
     * @return the largest camera y allowed by the play view
     */
    public float getMaximumScreenY() {
        return this.viewport.getMaximumCameraY(this.zoom, this.mapHeight);
    }

    /**
     * Sets the visible map area, a window of one pixel per logical unit, and clamps the camera to its bounds.
     *
     * @param width  the viewport width in screen pixels
     * @param height the viewport height in screen pixels
     * @throws IllegalArgumentException if either viewport dimension is not positive
     */
    public void setViewportSize(int width, int height) {
        this.viewport.setSurface(UiSurface.of(width, height, DisplayScale.ONE));
        this.clampPosition();
    }

    /**
     * Sets the native scale ({@link #getNativeZoom()}), keeping the map position at the viewport center.
     */
    public void setNativeZoom() {
        if (this.viewport.getWindowHeight() > 0) {
            this.zoomAt(this.getNativeZoom() / this.zoom,
                        this.viewport.getWindowWidth() / CENTER_DIVISOR,
                        this.viewport.getWindowHeight() / CENTER_DIVISOR);
        }
    }

    /**
     * Sets the client's shared screen geometry: the camera then clamps against its game screen columns and play view rows and takes its native scale
     * ({@link GameViewport}).
     *
     * @param viewport the client's viewport
     */
    public void setViewport(@NotNull GameViewport viewport) {
        this.viewport = viewport;
        this.clampPosition();
    }


    /**
     * Moves the camera by screen-space distances.
     *
     * @param horizontal the horizontal distance, positive toward the right
     * @param vertical   the vertical distance, positive toward the bottom
     * @throws IllegalArgumentException if either distance is not finite
     */
    public void pan(float horizontal, float vertical) {
        if (!Float.isFinite(horizontal) || !Float.isFinite(vertical)) {
            throw Exceptions.newIllegalArgumentException("Camera distances must be finite.");
        }

        float fromX = this.horizontalPosition;
        float fromY = this.verticalPosition;

        this.horizontalPosition += horizontal / this.zoom;
        this.verticalPosition += vertical / this.zoom;
        this.clampPosition();

        // A pan during an animated zoom moves its anchor along, so the rest of the zoom does not undo it.
        this.anchorWorldX += this.horizontalPosition - fromX;
        this.anchorWorldY += this.verticalPosition - fromY;

        LOGGER.debug(() -> "CAMERA PAN: " + fromX + "," + fromY + " -> " + this.horizontalPosition + "," + this.verticalPosition + " from " +
                           formatCallerFrames());
    }

    /**
     * Centers the viewport on a map position, subject to map boundaries.
     *
     * @param horizontal the horizontal map position in pixels
     * @param vertical   the vertical map position in pixels
     * @throws IllegalArgumentException if either coordinate is not finite
     */
    public void centerOn(float horizontal, float vertical) {
        if (!Float.isFinite(horizontal) || !Float.isFinite(vertical)) {
            throw Exceptions.newIllegalArgumentException("Camera coordinates must be finite.");
        }

        this.horizontalPosition = horizontal - this.viewport.getWindowWidth() / (CENTER_DIVISOR * this.zoom);
        this.verticalPosition   = vertical - this.viewport.getWindowHeight() / (CENTER_DIVISOR * this.zoom);
        this.clampPosition();

        // The rest of an animated zoom keeps the new center in the middle of the viewport.
        this.anchorScreenX = this.viewport.getWindowWidth() / CENTER_DIVISOR;
        this.anchorScreenY = this.viewport.getWindowHeight() / CENTER_DIVISOR;
        this.anchorWorldX  = horizontal;
        this.anchorWorldY  = vertical;

        LOGGER.debug(() -> "CAMERA CENTER: " + horizontal + "," + vertical + " -> " + this.horizontalPosition + "," + this.verticalPosition +
                           " from " + formatCallerFrames());
    }

    /**
     * Changes zoom at once while retaining the map position under the pointer when boundaries permit. A running animated zoom ({@link #zoomToward})
     * stops.
     *
     * @param factor     the positive multiplicative zoom factor
     * @param horizontal the pointer position from the left viewport edge in screen pixels
     * @param vertical   the pointer position from the top viewport edge in screen pixels
     * @throws IllegalArgumentException if the factor is not positive or any argument is not finite
     */
    public void zoomAt(float factor, float horizontal, float vertical) {
        checkZoomArguments(factor, horizontal, vertical);

        float worldHorizontal = this.screenToWorldX(horizontal);
        float worldVertical = this.screenToWorldY(vertical);

        this.zoomAnimating      = false;
        this.zoom               = Math.clamp(this.zoom * factor,
                                             this.viewport.getMinimumZoom(this.mapWidth, this.mapHeight),
                                             GameViewport.MAXIMUM_ZOOM);
        this.horizontalPosition = worldHorizontal - horizontal / this.zoom;
        this.verticalPosition   = worldVertical - vertical / this.zoom;
        this.clampPosition();
    }

    /**
     * Starts or extends an animated zoom ({@link #advanceZoom}) about a screen position. The target zoom is multiplied by the factor, so repeated
     * wheel steps accumulate, and stays within the camera's zoom limits. The map position under the screen position at this moment stays under it for
     * the rest of the animation when boundaries permit.
     *
     * @param factor     the positive multiplicative factor of the target zoom
     * @param horizontal the anchor position from the left viewport edge in screen pixels
     * @param vertical   the anchor position from the top viewport edge in screen pixels
     * @throws IllegalArgumentException if the factor is not positive or any argument is not finite
     */
    public void zoomToward(float factor, float horizontal, float vertical) {
        checkZoomArguments(factor, horizontal, vertical);

        this.anchorScreenX = horizontal;
        this.anchorScreenY = vertical;
        this.anchorWorldX  = this.screenToWorldX(horizontal);
        this.anchorWorldY  = this.screenToWorldY(vertical);
        this.targetZoom    = Math.clamp(this.getTargetZoom() * factor,
                                        this.viewport.getMinimumZoom(this.mapWidth, this.mapHeight),
                                        GameViewport.MAXIMUM_ZOOM);
        this.zoomAnimating = this.targetZoom != this.zoom;
    }

    /**
     * Advances the animated zoom by real time. The zoom approaches its target exponentially in logarithmic space,
     * {@code zoom *= (target / zoom) ^ (1 - exp(-seconds / ZOOM_TIME_CONSTANT_SECONDS))}, so the path does not depend on the frame rate, and snaps to
     * the target once within {@code 0.2} percent of it. The camera position follows the anchor and is clamped to the map.
     *
     * @param elapsedSeconds the real time since the previous call, in seconds
     */
    public void advanceZoom(double elapsedSeconds) {
        if (!this.zoomAnimating || !(elapsedSeconds > 0)) {
            return;
        }

        // The limits can change with the viewport while the animation runs.
        float target = Math.clamp(this.targetZoom, this.viewport.getMinimumZoom(this.mapWidth, this.mapHeight), GameViewport.MAXIMUM_ZOOM);
        double progress = 1 - Math.exp(-elapsedSeconds / ZOOM_TIME_CONSTANT_SECONDS);
        double next = this.zoom * Math.pow(target / (double) this.zoom, progress);

        if (Math.abs(next / target - 1) < ZOOM_SETTLE_RATIO) {
            next               = target;
            this.zoomAnimating = false;
        }

        this.targetZoom         = target;
        this.zoom               = (float) next;
        this.horizontalPosition = this.anchorWorldX - this.anchorScreenX / this.zoom;
        this.verticalPosition   = this.anchorWorldY - this.anchorScreenY / this.zoom;
        this.clampPosition();
    }

    /**
     * Converts a horizontal viewport coordinate to a map coordinate.
     *
     * @param horizontal the viewport coordinate in screen pixels
     * @return the map coordinate in pixels
     */
    public float screenToWorldX(float horizontal) {
        return this.horizontalPosition + horizontal / this.zoom;
    }

    /**
     * Converts a vertical viewport coordinate to a map coordinate.
     *
     * @param vertical the viewport coordinate in screen pixels
     * @return the map coordinate in pixels
     */
    public float screenToWorldY(float vertical) {
        return this.verticalPosition + vertical / this.zoom;
    }

    /**
     * Returns the map column of the pixel under a horizontal viewport coordinate, as the game maps the pointer: the screen position plus the pointer
     * offset in whole map pixels. The column is the pixel that contains the point, never the next one, so the right map edge gives the last column;
     * a point past the map gives the nearest column of the map.
     *
     * @param horizontal the viewport coordinate in screen pixels
     * @return the map column from {@code 0} to the map width less one
     */
    public int getMapPixelX(float horizontal) {
        return Math.clamp((long) Math.floor(this.screenToWorldX(horizontal)), 0, Math.max(0, this.mapWidth - 1));
    }

    /**
     * Returns the map row of the pixel under a vertical viewport coordinate, as the game maps the pointer: the screen position plus the pointer
     * offset in whole map pixels. The row is the pixel that contains the point, never the next one, so the bottom map edge gives the last row; a
     * point past the map gives the nearest row of the map.
     *
     * @param vertical the viewport coordinate in screen pixels
     * @return the map row from {@code 0} to the map height less one
     */
    public int getMapPixelY(float vertical) {
        return Math.clamp((long) Math.floor(this.screenToWorldY(vertical)), 0, Math.max(0, this.mapHeight - 1));
    }

    /**
     * Converts a horizontal map coordinate to a viewport coordinate.
     *
     * @param horizontal the map coordinate in pixels
     * @return the viewport coordinate in screen pixels
     */
    public float worldToScreenX(float horizontal) {
        return (horizontal - this.horizontalPosition) * this.zoom;
    }

    /**
     * Converts a vertical map coordinate to a viewport coordinate.
     *
     * @param vertical the map coordinate in pixels
     * @return the viewport coordinate in screen pixels
     */
    public float worldToScreenY(float vertical) {
        return (vertical - this.verticalPosition) * this.zoom;
    }

    /**
     * Applies the limits of a changed viewport ({@link GameViewport#setSurface}, {@link GameViewport#setInterface}).
     */
    public void applyViewport() {
        this.clampPosition();
    }

    /**
     * Clamps the camera origin to the current game columns and play rows.
     */
    private void clampPosition() {
        float minimum = this.viewport.getMinimumCameraX(this.zoom);

        this.horizontalPosition = Math.clamp(this.horizontalPosition, minimum, this.viewport.getMaximumCameraX(this.zoom, this.mapWidth));
        this.verticalPosition   = Math.clamp(this.verticalPosition, 0, this.viewport.getMaximumCameraY(this.zoom, this.mapHeight));
    }
}
