package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.video.VideoStream;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;

/**
 * Decodes a glue button video into a canvas texture, preserving its loop, hover and fade behavior.
 */
public final class GlueVideo implements AutoCloseable {
    private static final @NotNull Logger LOGGER = Logger.of(GlueVideo.class);
    private static final int WHITE = 0xFFFFFFFF;
    private static final int COLOR_WITHOUT_ALPHA = 0xFFFFFF00;
    private static final int ALPHA_MAXIMUM = 0xFF;

    private static final int FADE_MAXIMUM = 100;
    private static final int FADE_DIMMED = 30;
    private static final int FADE_STEP = 15;

    /**
     * The prefix of the frame texture keys in the canvas's dynamic textures.
     */
    private static final @NotNull String TEXTURE_KEY_PREFIX = "video@";
    /**
     * The element flag of a clip shown only while its button is hovered.
     */
    private static final @NotNull String MOUSE_OVER_FLAG = "FLC_MOUSEOVER";
    /**
     * The element flag of a clip that fades in.
     */
    private static final @NotNull String FADE_IN_FLAG = "FLC_FADEIN";
    /**
     * The element flag of a clip dimmed while its button is not hovered.
     */
    private static final @NotNull String FADED_FLAG = "FLC_FADED";
    /**
     * The element flag of a clip that starts over at its end.
     */
    private static final @NotNull String LOOP_FLAG = "FLC_LOOP";

    /**
     * The key of this video's frame texture in the canvas's dynamic textures.
     */
    private final @NotNull String textureKey;

    private final @NotNull String path;
    private final float offsetX;
    private final float offsetY;
    private final @Nullable GlueFlicLayout.Placement placement;

    private final boolean mouseOver;
    private final boolean fadeIn;
    private final boolean faded;
    private final boolean loop;

    private @Nullable VideoStream stream;
    private boolean failed;
    private int texture;
    private long nextFrameTime;
    private float width;
    private float height;
    private int fadeLevel;

    /**
     * Creates a {@link GlueVideo} instance.
     *
     * @param element   the {@code FLC} element of the button
     * @param placement the {@code rez/flc.json} rectangle of a high definition element, or {@code null} to draw the clip at the element's offset at its own size
     */
    public GlueVideo(@NotNull DialogControl element, @Nullable GlueFlicLayout.Placement placement) {
        this.textureKey = TEXTURE_KEY_PREFIX + System.identityHashCode(this);

        this.path      = ScrDialogControlUtils.getContent(element);
        this.offsetX   = element.getX();
        this.offsetY   = element.getY();
        this.placement = placement;

        this.mouseOver = ScrDialogControlUtils.hasFlag(element, MOUSE_OVER_FLAG);
        this.fadeIn    = ScrDialogControlUtils.hasFlag(element, FADE_IN_FLAG);
        this.faded     = ScrDialogControlUtils.hasFlag(element, FADED_FLAG);
        this.loop      = ScrDialogControlUtils.hasFlag(element, LOOP_FLAG);

        this.stream        = null;
        this.failed        = false;
        this.texture       = 0;
        this.nextFrameTime = 0;
        this.width         = 0;
        this.height        = 0;
        this.fadeLevel     = 0;
    }

    /**
     * @return {@code true} if the clip only shows while its button is hovered, {@code false} otherwise
     */
    public boolean isMouseOver() {
        return this.mouseOver;
    }

    /**
     * @return {@code true} if the clip starts over at its end ({@code FLC_LOOP}) instead of holding its last frame, {@code false} otherwise
     */
    public boolean isLooping() {
        return this.loop;
    }

    /**
     * Runs one {@code 10} ms tick of the fade level.
     *
     * @param hovered whether the button is hovered
     */
    public void tick(boolean hovered) {
        if (!this.fadeIn) {
            return;
        }

        int target = hovered || !this.faded ? FADE_MAXIMUM : FADE_DIMMED;

        if (this.fadeLevel < target) {
            this.fadeLevel = Math.min(this.fadeLevel + FADE_STEP, target);
        } else if (this.fadeLevel > target) {
            this.fadeLevel = Math.max(this.fadeLevel - FADE_STEP, target);
        }
    }

    /**
     * Advances the clip to a time and draws its current frame.
     *
     * @param canvas the glue canvas
     * @param left   the button left in interface pixels
     * @param top    the button top in interface pixels
     * @param now    the time in milliseconds
     */
    public void draw(@NotNull GlueCanvas canvas, float left, float top, long now) {
        if (this.failed) {
            return;
        }

        try {
            if (this.stream == null) {
                this.stream        = VideoStream.open(canvas.readVideo(this.path), false);
                this.width         = this.stream.getWidth() / (float) canvas.getAssetScale();
                this.height        = this.stream.getHeight() / (float) canvas.getAssetScale();
                this.nextFrameTime = now;
            }
        } catch (IOException | RuntimeException | LinkageError exception) {
            LOGGER.warning("GLUE VIDEO UNAVAILABLE: " + this.path + ": " + exception.getMessage());
            this.failed = true;

            return;
        }

        VideoStream opened = this.stream;

        if (this.texture == 0 || now >= this.nextFrameTime) {
            // A clip without FLC_LOOP keeps its last frame once the stream ends.
            byte[] pixels = this.loop ? opened.readNextLoopedFrame() : opened.readNextFrame();

            if (pixels != null) {
                this.texture = canvas.updateTexture(this.textureKey, opened.getWidth(), opened.getHeight(), pixels);
            }

            this.nextFrameTime = Math.max(this.nextFrameTime + opened.getFrameMilliseconds(), now);
        }

        if (this.texture == 0) {
            return;
        }

        int color = this.fadeIn || this.faded ? COLOR_WITHOUT_ALPHA | Math.round(ALPHA_MAXIMUM * this.fadeLevel / (float) FADE_MAXIMUM) : WHITE;

        if (this.placement != null) {
            canvas.drawTexture(this.texture,
                               left + this.placement.getX(), top + this.placement.getY(),
                               this.placement.getWidth(), this.placement.getHeight(),
                               color);
        } else {
            canvas.drawTexture(this.texture, left + this.offsetX, top + this.offsetY, this.width, this.height, color);
        }
    }

    /**
     * Restarts the clip timing, so that a hover clip starts at its current frame when shown again.
     *
     * @param now the time in milliseconds
     */
    public void resetTime(long now) {
        this.nextFrameTime = now;
    }

    /**
     * Releases the decoder and the texture.
     *
     * @param canvas the glue canvas that owns the texture
     */
    public void close(@NotNull GlueCanvas canvas) {
        if (this.texture != 0) {
            canvas.releaseTexture(this.textureKey);
        }

        this.texture = 0;
        this.close();
    }

    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code
     *         Exception}, implementers are <em>strongly</em> encouraged to
     *         declare concrete implementations of the {@code close} method to
     *         throw more specific exceptions, or to throw no exception at all
     *         if the close operation cannot fail.
     *
     *         <p>
     *         Cases where the close operation may fail require careful
     *         attention by implementers. It is strongly advised to relinquish
     *         the underlying resources and to internally <em>mark</em> the
     *         resource as closed, prior to throwing the exception. The {@code
     *         close} method is unlikely to be invoked more than once and so
     *         this ensures that the resources are released in a timely manner.
     *         Furthermore it reduces problems that could arise when the resource
     *         wraps, or is wrapped, by another resource.
     *
     *         <p>
     *         <em>Implementers of this interface are also strongly advised
     *         to not have the {@code close} method throw {@link
     *         InterruptedException}.</em>
     *         <p>
     *         This exception interacts with a thread's interrupted status,
     *         and runtime misbehavior is likely to occur if an {@code
     *         InterruptedException} is {@linkplain Throwable#addSuppressed
     *         suppressed}.
     *         <p>
     *         More generally, if it would cause problems for an
     *         exception to be suppressed, the {@code AutoCloseable.close}
     *         method should not throw it.
     *
     *         <p>
     *         Note that unlike the {@link Closeable#close close}
     *         method of {@link Closeable}, this {@code close} method
     *         is <em>not</em> required to be idempotent.  In other words,
     *         calling this {@code close} method more than once may have some
     *         visible side effect, unlike {@code Closeable.close} which is
     *         required to have no effect if called more than once.
     *         <p>
     *         However, implementers of this interface are strongly encouraged
     *         to make their {@code close} methods idempotent.
     * @implSpec Releases the decoder.
     */
    @Override
    public void close() {
        if (this.stream != null) {
            this.stream.close();
        }

        this.stream = null;
    }
}
