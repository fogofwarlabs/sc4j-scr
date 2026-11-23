package org.fogofwarlabs.sc4j.scr.data.resource;

import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.fogofwarlabs.sc4j.scr.data.format.image.AnimFile;
import org.fogofwarlabs.sc4j.scr.data.format.image.GrpFile;
import org.fogofwarlabs.sc4j.scr.data.resource.importer.GrpSheetUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Names the frame files of an image a mod ships in its jar ({@link ScrModFiles}): the SD frames as a GRP file, and optionally the HD and HD2
 * frames as single-entry ANIM files. The files use the formats the installed data uses, so the simulation (frame count and frame rectangles), the
 * SD renderer (palette indices, team color indices {@code 8..15}) and the HD renderers (block-compressed {@code diffuse} and {@code teamcolor}
 * layers) read them with their existing readers.
 * <p>
 * An image with turns holds {@code 17} directions per frame set, north through south clockwise; the renderer mirrors directions {@code 17..31}
 * as for an installed image. A shadow is a separate image with the shadow draw function, created by the script of the main image.
 * <p>
 * The SD frames are read once and kept ({@link #loadFrames()}, {@link #loadSheet()}): the game data and the renderers of every game share them, and
 * a client loads them before its first game, so no game reads or decodes them when an image is created or first drawn.
 */
public final class ScrModGraphics {
    /**
     * Builds {@link ScrModGraphics} instances.
     */
    public static final class Builder {
        private final @NotNull ScrModFiles files;
        private final @NotNull String framesPath;

        private @Nullable String hdAnimationPath;
        private @Nullable String hd2AnimationPath;

        /**
         * Creates a {@link Builder} instance with SD frames and no HD frames.
         *
         * @param files      the files of the mod
         * @param framesPath the path of the SD GRP file
         */
        public Builder(@NotNull ScrModFiles files, @NotNull String framesPath) {
            this.files      = files;
            this.framesPath = framesPath;

            this.hdAnimationPath  = null;
            this.hd2AnimationPath = null;
        }

        /**
         * Sets the ANIM file of the HD frames (scale {@code 4}).
         *
         * @param path the path of the ANIM file, or {@code null} for none
         * @return this instance for chaining
         */
        public @NotNull Builder setHdAnimation(@Nullable String path) {
            this.hdAnimationPath = path;

            return this;
        }

        /**
         * Sets the ANIM file of the HD2 frames (scale {@code 2}).
         *
         * @param path the path of the ANIM file, or {@code null} for none
         * @return this instance for chaining
         */
        public @NotNull Builder setHd2Animation(@Nullable String path) {
            this.hd2AnimationPath = path;

            return this;
        }

        /**
         * Builds a {@link ScrModGraphics} instance with the current paths.
         *
         * @return the built {@link ScrModGraphics} instance
         */
        public @NotNull ScrModGraphics build() {
            return new ScrModGraphics(this);
        }
    }

    private final @NotNull ScrModFiles files;
    private final @NotNull String framesPath;

    private final @Nullable String hdAnimationPath;
    private final @Nullable String hd2AnimationPath;

    private @Nullable GrpFile frames;
    private @Nullable SpriteSheet sheet;

    private ScrModGraphics(@NotNull Builder builder) {
        this.files      = builder.files;
        this.framesPath = builder.framesPath;

        this.hdAnimationPath  = builder.hdAnimationPath;
        this.hd2AnimationPath = builder.hd2AnimationPath;

        this.frames = null;
        this.sheet  = null;
    }

    /**
     * @return the path of the SD GRP file in the mod's files
     */
    public @NotNull String getFramesPath() {
        return this.framesPath;
    }

    /**
     * Returns the path of the ANIM file of a quality.
     *
     * @param quality the asset quality
     * @return the path, or {@code null} if the mod gives no frames of the quality (always for {@link AssetResolver.AssetSet#SD})
     */
    public @Nullable String getAnimationPath(AssetResolver.@NotNull AssetSet quality) {
        return switch (quality) {
            case SD -> null;
            case HD2 -> this.hd2AnimationPath;
            case HD -> this.hdAnimationPath;
        };
    }


    /**
     * Returns the SD frames, read and parsed by the first call and kept.
     *
     * @return the parsed GRP file
     * @throws IOException if the file cannot be read or decoded
     */
    public synchronized @NotNull GrpFile loadFrames() throws IOException {
        GrpFile loaded = this.frames;

        if (loaded == null) {
            loaded      = GrpFile.parse(this.files.readFile(this.framesPath));
            this.frames = loaded;
        }

        return loaded;
    }

    /**
     * Returns the SD frames as a sprite sheet ({@link GrpSheetUtils#createSheet}), decoded by the first call and kept.
     *
     * @return the sprite sheet of the SD frames
     * @throws IOException if the file cannot be read or decoded
     */
    public synchronized @NotNull SpriteSheet loadSheet() throws IOException {
        SpriteSheet loaded = this.sheet;

        if (loaded == null) {
            loaded     = GrpSheetUtils.createSheet(this.loadFrames());
            this.sheet = loaded;
        }

        return loaded;
    }

    /**
     * Reads the ANIM file of a quality.
     *
     * @param quality the asset quality
     * @return the parsed ANIM file, or {@code null} if the mod gives no frames of the quality
     * @throws IOException if the file cannot be read or decoded
     */
    public @Nullable AnimFile readAnimation(AssetResolver.@NotNull AssetSet quality) throws IOException {
        String path = this.getAnimationPath(quality);

        return path == null ? null : AnimFile.parse(this.files.readFile(path));
    }
}
