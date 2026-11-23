package org.fogofwarlabs.sc4j.scr.render.hd;

import org.fogofwarlabs.sc4j.engine.resource.ResourceNotFoundException;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureSheet;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedGraphics;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedImage;
import org.fogofwarlabs.sc4j.scr.data.format.dat.ImagesDat;
import org.fogofwarlabs.sc4j.scr.data.format.image.ImageRelations;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrModGraphics;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceKeys;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrSkin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * The HD anims of one asset quality and skin, independent of the map tileset: the provider's {@link ScrSkin} resolves an
 * image to the anim that draws it, and the provider imports that anim as an engine {@link TextureSheet}.
 */
public final class HdAnimations {
    /**
     * Creates an {@link HdAnimations} instance of a quality and skin of the provider.
     *
     * @param resources the resource provider of the installation, kept open while the anims are read
     * @param quality   the asset quality
     * @param skin      the skin folder name, or {@code null} for the default anims
     * @return the created {@link HdAnimations} instance
     * @throws IOException if {@code images.rel} or the skin list is missing or malformed
     */
    public static @NotNull HdAnimations load(@NotNull ScrResourceProvider resources, HdRenderAssets.@NotNull Quality quality, @Nullable String skin) throws IOException {
        return new HdAnimations(resources, quality, resources.loadSkin(quality.getAssetSet(), skin));
    }


    private final @NotNull ScrResourceProvider resources;
    private final HdRenderAssets.@NotNull Quality quality;
    private final @NotNull ScrSkin skin;

    private HdAnimations(@NotNull ScrResourceProvider resources, HdRenderAssets.@NotNull Quality quality, @NotNull ScrSkin skin) {
        this.resources = resources;
        this.quality   = quality;
        this.skin      = skin;
    }

    /**
     * @return the asset quality
     */
    public HdRenderAssets.@NotNull Quality getQuality() {
        return this.quality;
    }

    /**
     * @return the skin folder name, or {@code null} for the default anims
     */
    public @Nullable String getSkin() {
        return this.skin.getName();
    }

    /**
     * @return the resource provider the anims are read from
     */
    @NotNull ScrResourceProvider getResources() {
        return this.resources;
    }

    /**
     * @return the image relations of {@code images.rel}
     */
    @NotNull ImageRelations getRelations() {
        return this.skin.getRelations();
    }

    /**
     * Returns the image whose anim an image shares: {@link #loadTextureSheet(int)} reads the same file for both, so their textures and frame
     * placement are the same. An added image without an anim of its own in the quality shares the anim of its base image.
     *
     * @param image the image identifier
     * @return the lowest image drawn by the same anim, {@code image} itself when no lower image shares it
     */
    int getSharedImage(int image) {
        ScrAddedGraphics added = image < ImagesDat.IMAGE_COUNT ? null : this.resources.getAddedGraphics();

        if (added != null && added.containsImage(image)) {
            ScrAddedImage addedImage = added.getImage(image);
            ScrModGraphics graphics = addedImage.getGraphics();

            return graphics == null || graphics.getAnimationPath(this.quality.getAssetSet()) == null ? this.getSharedImage(addedImage.getBaseImage())
                                                                                                     : image;
        }

        return this.skin.getSharedImage(image);
    }

    /**
     * @param animationImage an anim identifier (an image after {@code images.rel})
     * @return {@code true} if the skin replaces that anim, {@code false} otherwise
     */
    boolean isSkinImage(int animationImage) {
        return this.skin.isSkinImage(animationImage);
    }


    /**
     * Loads the HD texture sheet of an image.
     *
     * @param image the image identifier
     * @return the texture sheet, or {@code null} if the installation has no anim for the image
     * @throws IOException if the anim cannot be read or is not a single-entry HD anim
     */
    public @Nullable TextureSheet loadTextureSheet(int image) throws IOException {
        try {
            return this.resources.loadTextureSheet(ScrResourceKeys.ofTexture(this.quality.getAssetSet(), this.skin.getName(), image));
        } catch (ResourceNotFoundException exception) {
            return null;
        }
    }
}
