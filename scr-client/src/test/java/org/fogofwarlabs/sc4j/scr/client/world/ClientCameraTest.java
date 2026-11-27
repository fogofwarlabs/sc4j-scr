package org.fogofwarlabs.sc4j.scr.client.world;

import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests viewport transformations and boundary handling for the inspection camera.
 */
final class ClientCameraTest {
    private static final int MAP_SIZE = 4096;
    private static final int MAP_CENTER = 2048;

    private static final int VIEWPORT_WIDTH = 800;
    private static final int VIEWPORT_HEIGHT = 600;
    private static final int VIEWPORT_CENTER_X = 400;
    private static final int VIEWPORT_CENTER_Y = 300;
    private static final int POINTER_X = 235;
    private static final int POINTER_Y = 417;

    private static final float ZOOM_IN_FACTOR = 2.0f;
    private static final float ZOOM_OUT_FACTOR = 0.0001f;
    private static final float MAXIMUM_ZOOM = 4.0f;
    private static final double ZOOM_TIME_CONSTANT_SECONDS = 0.1;
    private static final double FRAME_SECONDS = 1.0 / 60;
    private static final int FAST_STEP_COUNT = 24;
    private static final int ANCHORED_STEP_COUNT = 20;
    private static final int SETTLE_STEP_COUNT = 60;
    private static final int CLAMPED_STEP_COUNT = 30;

    /**
     * The last map column and row of {@link #MAP_SIZE}.
     */
    private static final int LAST_MAP_PIXEL = MAP_SIZE - 1;
    /**
     * A pointer in the last screen column of the viewport, past its middle, and the right viewport edge itself.
     */
    private static final float LAST_COLUMN_POINTER = VIEWPORT_WIDTH - 0.4f;
    private static final float RIGHT_EDGE_POINTER = VIEWPORT_WIDTH;
    /**
     * Pointers at the start, inside and at the end of the third map pixel at twice the zoom: screen pixels 4 and 5 show map pixel 2.
     */
    private static final float PIXEL_START_POINTER = 4.0f;
    private static final float PIXEL_INSIDE_POINTER = 5.9f;
    private static final float NEXT_PIXEL_POINTER = 6.0f;
    private static final int POINTER_PIXEL = 2;

    private static final float POSITION_TOLERANCE = 0.001f;
    private static final float ANCHOR_TOLERANCE = 0.01f;
    private static final float TARGET_ZOOM_TOLERANCE = 0.0001f;
    private static final double ZOOM_TOLERANCE = 0.0005;

    @Test
    void zoom_at_should_keep_world_position_under_pointer() {
        ClientCamera camera = new ClientCamera(MAP_SIZE, MAP_SIZE);

        camera.setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        camera.centerOn(MAP_CENTER, MAP_CENTER);

        float worldHorizontal = camera.screenToWorldX(POINTER_X);
        float worldVertical = camera.screenToWorldY(POINTER_Y);

        camera.zoomAt(ZOOM_IN_FACTOR, POINTER_X, POINTER_Y);

        Assertions.assertEquals(worldHorizontal, camera.screenToWorldX(POINTER_X), POSITION_TOLERANCE);
        Assertions.assertEquals(worldVertical, camera.screenToWorldY(POINTER_Y), POSITION_TOLERANCE);
        Assertions.assertEquals(POINTER_X, camera.worldToScreenX(worldHorizontal), POSITION_TOLERANCE);
        Assertions.assertEquals(POINTER_Y, camera.worldToScreenY(worldVertical), POSITION_TOLERANCE);
    }

    @Test
    void camera_clamps_pan_after_zoom_and_resize() {
        ClientCamera camera = new ClientCamera(1024, 768);

        camera.setViewportSize(640, 480);
        camera.pan(10000, 10000);

        Assertions.assertEquals(384, camera.getX());
        Assertions.assertEquals(288, camera.getY());

        camera.zoomAt(ZOOM_IN_FACTOR, 640, 480);

        Assertions.assertEquals(704, camera.getX());
        Assertions.assertEquals(528, camera.getY());

        camera.setViewportSize(1600, 1200);

        Assertions.assertEquals(224, camera.getX());
        Assertions.assertEquals(168, camera.getY());

        camera.pan(-10000, -10000);

        Assertions.assertEquals(0, camera.getX());
        Assertions.assertEquals(0, camera.getY());
    }

    @Test
    void pan_should_convert_screen_pixels_at_current_zoom() {
        ClientCamera camera = new ClientCamera(MAP_SIZE, MAP_SIZE);

        camera.setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        camera.zoomAt(ZOOM_IN_FACTOR, 0, 0);
        camera.pan(120, 80);

        Assertions.assertEquals(60, camera.getX());
        Assertions.assertEquals(40, camera.getY());
    }

    @Test
    void camera_keeps_small_maps_at_origin_and_bounds_zoom() {
        ClientCamera camera = new ClientCamera(128, 128);

        camera.setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        camera.centerOn(64, 64);
        camera.zoomAt(ZOOM_OUT_FACTOR, VIEWPORT_CENTER_X, VIEWPORT_CENTER_Y);

        // The map is smaller than the native view (600 / 480 = 1.25 screen pixels per map pixel): zooming out stops there.
        Assertions.assertEquals(1.25f, camera.getZoom());
        Assertions.assertEquals(0, camera.getX());
        Assertions.assertEquals(0, camera.getY());

        camera.zoomAt(10000, 0, 0);

        Assertions.assertEquals(MAXIMUM_ZOOM, camera.getZoom());
        Assertions.assertEquals(0, camera.getX());
        Assertions.assertEquals(0, camera.getY());
    }

    @Test
    void zoom_at_should_stop_zooming_out_when_view_reaches_map() {
        ClientCamera camera = new ClientCamera(MAP_SIZE, 2048);
        GameViewport viewport = new GameViewport();

        viewport.setSurface(UiSurface.of(1280, 800, DisplayScale.ONE));
        // The SD console: the 640-wide game screen pillarboxed in the window.
        viewport.setInterface(false, true, false);
        camera.setViewport(viewport);
        camera.centerOn(2048, 1024);
        camera.zoomAt(ZOOM_OUT_FACTOR, 640, 400);

        // The view height (800 rows) reaches the map height first: 800 / 2048 screen pixels per map pixel.
        Assertions.assertEquals(800.0f / 2048, camera.getZoom());
        Assertions.assertEquals(0, camera.getY());
        Assertions.assertEquals(2048, camera.screenToWorldY(800), POSITION_TOLERANCE);
        Assertions.assertTrue(camera.screenToWorldX(viewport.getGameLeft()) >= 0 && camera.screenToWorldX(viewport.getGameRight()) <= MAP_SIZE);
    }

    /**
     * The animated zoom keeps the map position under the pointer fixed on every step, not only at the end.
     */
    @Test
    void animated_zoom_keeps_pointer_position_anchored_on_every_step() {
        ClientCamera camera = new ClientCamera(MAP_SIZE, MAP_SIZE);

        camera.setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        camera.centerOn(MAP_CENTER, MAP_CENTER);

        float worldHorizontal = camera.screenToWorldX(POINTER_X);
        float worldVertical = camera.screenToWorldY(POINTER_Y);

        camera.zoomToward(ZOOM_IN_FACTOR, POINTER_X, POINTER_Y);

        Assertions.assertEquals(1.0f, camera.getZoom(), "nothing moves before time passes");
        Assertions.assertEquals(2.0f, camera.getTargetZoom());

        for (int step = 0; step < ANCHORED_STEP_COUNT; step++) {
            camera.advanceZoom(FRAME_SECONDS);

            Assertions.assertEquals(worldHorizontal, camera.screenToWorldX(POINTER_X), ANCHOR_TOLERANCE);
            Assertions.assertEquals(worldVertical, camera.screenToWorldY(POINTER_Y), ANCHOR_TOLERANCE);
        }
    }

    /**
     * The approach is exponential with the time constant, so the path does not depend on the frame rate.
     */
    @Test
    void animated_zoom_converges_independently_of_frame_rate() {
        ClientCamera fast = new ClientCamera(MAP_SIZE, MAP_SIZE);
        ClientCamera slow = new ClientCamera(MAP_SIZE, MAP_SIZE);

        for (ClientCamera camera : new ClientCamera[]{fast, slow}) {
            camera.setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
            camera.centerOn(MAP_CENTER, MAP_CENTER);
            camera.zoomToward(ZOOM_IN_FACTOR, VIEWPORT_CENTER_X, VIEWPORT_CENTER_Y);
        }

        for (int step = 0; step < FAST_STEP_COUNT; step++) {
            fast.advanceZoom(ZOOM_TIME_CONSTANT_SECONDS / FAST_STEP_COUNT);
        }

        slow.advanceZoom(ZOOM_TIME_CONSTANT_SECONDS);

        // After one time constant the remaining log ratio is 1/e of the start.
        double expected = Math.pow(2.0, 1 - Math.exp(-1));

        Assertions.assertEquals(expected, fast.getZoom(), ZOOM_TOLERANCE);
        Assertions.assertEquals(expected, slow.getZoom(), ZOOM_TOLERANCE);
        Assertions.assertTrue(fast.isZoomAnimating());

        for (int step = 0; step < SETTLE_STEP_COUNT; step++) {
            fast.advanceZoom(FRAME_SECONDS);
        }

        Assertions.assertEquals(2.0f, fast.getZoom(), "the animation snaps to the target once close");
        Assertions.assertFalse(fast.isZoomAnimating());
    }

    /**
     * Wheel steps multiply the target, which stays within the zoom limits; an immediate zoom stops the animation.
     */
    @Test
    void animated_zoom_accumulates_steps_within_limits_and_stops_on_immediate_zoom() {
        ClientCamera camera = new ClientCamera(MAP_SIZE, MAP_SIZE);

        camera.setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        camera.centerOn(MAP_CENTER, MAP_CENTER);
        camera.zoomToward(1.2f, VIEWPORT_CENTER_X, VIEWPORT_CENTER_Y);
        camera.advanceZoom(0.01);
        camera.zoomToward(1.2f, VIEWPORT_CENTER_X, VIEWPORT_CENTER_Y);

        Assertions.assertEquals(1.44f, camera.getTargetZoom(), TARGET_ZOOM_TOLERANCE);

        camera.zoomToward(100, VIEWPORT_CENTER_X, VIEWPORT_CENTER_Y);

        Assertions.assertEquals(MAXIMUM_ZOOM, camera.getTargetZoom());

        camera.advanceZoom(1);

        Assertions.assertEquals(MAXIMUM_ZOOM, camera.getZoom());

        camera.zoomToward(0.5f, VIEWPORT_CENTER_X, VIEWPORT_CENTER_Y);
        camera.zoomAt(1, VIEWPORT_CENTER_X, VIEWPORT_CENTER_Y);

        Assertions.assertFalse(camera.isZoomAnimating());
        Assertions.assertEquals(camera.getZoom(), camera.getTargetZoom());
    }

    /**
     * Zooming out at the map's corner clamps the position on every step; a pan during the animation is kept.
     */
    @Test
    void animated_zoom_clamps_to_map_and_keeps_pans() {
        ClientCamera camera = new ClientCamera(1024, 768);

        camera.setViewportSize(640, 480);
        camera.zoomAt(ZOOM_IN_FACTOR, 0, 0);
        camera.zoomToward(0.5f, 600, 400);

        for (int step = 0; step < CLAMPED_STEP_COUNT; step++) {
            camera.advanceZoom(FRAME_SECONDS);

            Assertions.assertTrue(camera.getX() >= 0 && camera.getX() <= camera.getMaximumScreenX() + POSITION_TOLERANCE);
            Assertions.assertTrue(camera.getY() >= 0 && camera.getY() <= camera.getMaximumScreenY() + POSITION_TOLERANCE);
        }

        ClientCamera panned = new ClientCamera(MAP_SIZE, MAP_SIZE);

        panned.setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        panned.centerOn(MAP_CENTER, MAP_CENTER);
        panned.zoomToward(ZOOM_IN_FACTOR, VIEWPORT_CENTER_X, VIEWPORT_CENTER_Y);
        panned.advanceZoom(0.05);
        panned.pan(100, 0);

        float center = panned.screenToWorldX(VIEWPORT_CENTER_X);

        panned.advanceZoom(1);

        Assertions.assertEquals(center, panned.screenToWorldX(VIEWPORT_CENTER_X), ANCHOR_TOLERANCE);
    }

    @Test
    void camera_rejects_invalid_input_without_changing_state() {
        ClientCamera camera = new ClientCamera(1024, 1024);

        Assertions.assertThrows(IllegalArgumentException.class, () -> camera.pan(Float.NaN, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> camera.centerOn(0, Float.POSITIVE_INFINITY));
        Assertions.assertThrows(IllegalArgumentException.class, () -> camera.zoomAt(0, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> camera.zoomAt(Float.NaN, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> camera.setViewportSize(-1, VIEWPORT_HEIGHT));
        Assertions.assertEquals(0, camera.getX());
        Assertions.assertEquals(0, camera.getY());
        Assertions.assertEquals(1.0f, camera.getZoom());
    }

    @Test
    void get_map_pixel_should_give_the_last_map_column_and_row_at_the_map_edge() {
        ClientCamera camera = new ClientCamera(MAP_SIZE, MAP_SIZE);

        camera.setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        camera.pan(MAP_SIZE, MAP_SIZE);

        // The last screen column shows map pixel 4095.6, which rounding would turn into 4096.
        Assertions.assertEquals(LAST_MAP_PIXEL, camera.getMapPixelX(LAST_COLUMN_POINTER));
        Assertions.assertEquals(LAST_MAP_PIXEL, camera.getMapPixelX(RIGHT_EDGE_POINTER));
        Assertions.assertEquals(LAST_MAP_PIXEL, camera.getMapPixelY(VIEWPORT_HEIGHT));

        // Zoomed out, a screen column covers several map pixels: the pointer gives the one it is in, and the right edge the last column.
        camera.zoomAt(ZOOM_OUT_FACTOR, VIEWPORT_CENTER_X, VIEWPORT_CENTER_Y);

        Assertions.assertEquals((int) Math.floor(camera.screenToWorldX(LAST_COLUMN_POINTER)), camera.getMapPixelX(LAST_COLUMN_POINTER));
        Assertions.assertEquals(LAST_MAP_PIXEL, camera.getMapPixelX(RIGHT_EDGE_POINTER));
    }

    @Test
    void get_map_pixel_should_give_the_pixel_that_contains_the_pointer() {
        ClientCamera camera = new ClientCamera(MAP_SIZE, MAP_SIZE);

        camera.setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        camera.zoomAt(ZOOM_IN_FACTOR, 0, 0);

        Assertions.assertEquals(POINTER_PIXEL, camera.getMapPixelX(PIXEL_START_POINTER));
        Assertions.assertEquals(POINTER_PIXEL, camera.getMapPixelX(PIXEL_INSIDE_POINTER));
        Assertions.assertEquals(POINTER_PIXEL + 1, camera.getMapPixelX(NEXT_PIXEL_POINTER));
        Assertions.assertEquals(POINTER_PIXEL, camera.getMapPixelY(PIXEL_INSIDE_POINTER));
        Assertions.assertEquals(0, camera.getMapPixelX(-PIXEL_INSIDE_POINTER), "a point left of the map gives the first column");
    }
}
