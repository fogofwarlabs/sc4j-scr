package org.fogofwarlabs.sc4j.scr.render.hd;

import org.fogofwarlabs.sc4j.engine.content.type.ImageType;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedGraphics;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedImage;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.ImagesDat;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrModFiles;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrModGraphics;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Tests which anim the images of {@link HdAnimations} share: an added image without an anim of its own in the quality shares its base image's,
 * so the HD image set loads one texture for them all, and an added image with its own anim keeps its own.
 */
@RequiresInstallation
final class HdAnimationsTest {
    /**
     * The base image of the added images: the Terran Marine.
     */
    private static final int BASE_IMAGE = 239;
    private static final int FIRST_SCRIPT_ID = 1000;

    @Test
    void get_shared_image_should_share_the_base_anim_of_an_added_image_without_its_own() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            DatTable images = ImagesDat.createSchema().parse(storage.readFile("arr/images.dat"));
            ScrAddedGraphics graphics = new ScrAddedGraphics(FIRST_SCRIPT_ID);
            ScrModFiles files = path -> new byte[0];
            int plain = graphics.addImage(new ScrAddedImage.Builder(new ImageType("plain"), BASE_IMAGE, images).build());
            int framesOnly = graphics.addImage(new ScrAddedImage.Builder(new ImageType("frames only"), BASE_IMAGE, images)
                                                       .setGraphics(new ScrModGraphics.Builder(files, "frames.grp").build())
                                                       .build());
            int animated = graphics.addImage(new ScrAddedImage.Builder(new ImageType("animated"), BASE_IMAGE, images)
                                                     .setGraphics(new ScrModGraphics.Builder(files, "frames.grp").setHd2Animation("frames-hd2.anim")
                                                                                                                 .build())
                                                     .build());
            HdAnimations hd2 = HdAnimations.load(ScrResourceProvider.borrow(storage, graphics), HdRenderAssets.Quality.HD2, null);
            HdAnimations hd = HdAnimations.load(ScrResourceProvider.borrow(storage, graphics), HdRenderAssets.Quality.HD, null);
            int base = hd2.getSharedImage(BASE_IMAGE);

            Assertions.assertEquals(base, hd2.getSharedImage(plain));
            Assertions.assertEquals(base, hd2.getSharedImage(framesOnly));
            Assertions.assertEquals(animated, hd2.getSharedImage(animated), "an added image with an anim of the quality keeps its own");
            Assertions.assertEquals(hd.getSharedImage(BASE_IMAGE), hd.getSharedImage(animated), "the HD quality has no anim of its own");
        }
    }
}
