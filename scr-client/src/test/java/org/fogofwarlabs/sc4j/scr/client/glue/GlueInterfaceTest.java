package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.engine.client.audio.AudioMixer;
import org.fogofwarlabs.sc4j.engine.client.audio.SilentAudioDevice;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.host.ClientHost;
import org.fogofwarlabs.sc4j.engine.client.host.HostWindow;
import org.fogofwarlabs.sc4j.engine.client.host.WindowSettings;
import org.fogofwarlabs.sc4j.scr.client.ClientContext;
import org.fogofwarlabs.sc4j.scr.client.audio.ClientAudio;
import org.fogofwarlabs.sc4j.scr.client.audio.SoundSettings;
import org.fogofwarlabs.sc4j.scr.client.configuration.InstallLayout;
import org.fogofwarlabs.sc4j.scr.client.glue.lobby.BeaconLinkGlueScenario;
import org.fogofwarlabs.sc4j.scr.client.glue.menu.MainMenuScreen;
import org.fogofwarlabs.sc4j.scr.client.glue.score.GlueResume;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.load.ScrIscriptImporter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;


/**
 * Checks that the glue shows the campaign-link error over the campaign screen it opens when a next scenario cannot be loaded, and that
 * the main menu, its popups and the custom game screen draw the stored reference frames on a hidden context with frozen time, the main menu
 * also at display scales 1.5 and 2 (the same framebuffer in fewer logical units).
 * <p>
 * The reference frames are stored as SHA-256 digests of the read-back pixels (the frames hold installation artwork, which the
 * repository does not keep); every drawn frame is also written to {@code build/glue-screenshots} for inspection. The digests are those
 * of the reference machine's OpenGL driver.
 */
@RequiresInstallation
final class GlueInterfaceTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int WINDOW_WIDTH = 640;
    private static final int WINDOW_HEIGHT = 480;

    private static final @NotNull String LOCALE = "enUS";
    private static final @NotNull String PLAYER_NAME = "player";
    private static final @NotNull String PLAYED_MAP_NAME = "chain1.scx";
    private static final @NotNull String MISSING_SCENARIO_NAME = "missing.scx";
    private static final int TERRAN = 1;
    /**
     * The popups over the campaign screen: the one error box.
     */
    private static final int POPUP_COUNT = 1;

    private static final int FRAME_WIDTH = 1280;
    private static final int FRAME_HEIGHT = 720;
    private static final int RGBA_BYTES = 4;
    private static final int GREEN_OFFSET = 1;
    private static final int BLUE_OFFSET = 2;
    private static final int OPAQUE_ARGB = 0xFF000000;
    /**
     * The frozen host time step between two drawn frames, in milliseconds.
     */
    private static final int FRAME_MILLISECONDS = 20;
    /**
     * The drawn time a screen or popup gets to settle (swish, fades, flic buttons) before its frame is compared, in milliseconds.
     */
    private static final int SETTLE_MILLISECONDS = 6000;
    private static final @NotNull String SCREENSHOT_DIRECTORY = "build/glue-screenshots";
    private static final @NotNull String SCREENSHOT_FORMAT = "png";
    private static final @NotNull String DIGEST_ALGORITHM = "SHA-256";
    private static final @NotNull String ERROR_KEY = "strERROR_LOAD_MAP";

    private static final @NotNull String MAIN_MENU_FRAME = "main-menu";
    private static final @NotNull String OPTIONS_POPUP_FRAME = "options-popup";
    private static final @NotNull String ERROR_POPUP_FRAME = "error-popup";
    private static final @NotNull String CUSTOM_GAME_FRAME = "custom-game";
    private static final @NotNull String SCALED_MAIN_MENU_FRAME = "main-menu-scale-";
    /**
     * The display scales the main menu is drawn at again: the frames equal the main menu's reference frame.
     */
    private static final float @NotNull [] DISPLAY_SCALES = {1.5f, 2f};
    /**
     * The digests of the reference frames, in drawing order.
     */
    private static final @NotNull Map<String, String> REFERENCE_DIGESTS = createReferenceDigests();

    private static @NotNull Map<String, String> createReferenceDigests() {
        Map<String, String> digests = new LinkedHashMap<>();

        digests.put(MAIN_MENU_FRAME, "bee4f66d7d1afaebd0af6d328ae56e4234e8ce837348fbc6a52107cf5345a2ea");
        digests.put(OPTIONS_POPUP_FRAME, "75cfda6841428d2945cd30b466c24f43d4c08eee67d5f043b317bed183e0b8b3");
        digests.put(ERROR_POPUP_FRAME, "77c10f315b81537b36d24b41dbda76b82194a8b04aee3ad0d417f4eed2bad475");
        digests.put(CUSTOM_GAME_FRAME, "fe4c8125a8ef978ca8d2818bd52ae0813c86de481ff6db9621044104f850541f");

        for (float scale : DISPLAY_SCALES) {
            digests.put(SCALED_MAIN_MENU_FRAME + scale, digests.get(MAIN_MENU_FRAME));
        }

        return digests;
    }

    /**
     * Draws the glue for {@link #SETTLE_MILLISECONDS} of frozen time at one pixel per logical unit.
     *
     * @param glue the glue
     * @param time the time of the first frame in milliseconds
     * @return the time after the last frame
     */
    private static long drawFrames(@NotNull GlueInterface glue, long time) {
        return drawFrames(glue, time, DisplayScale.ONE);
    }

    /**
     * Draws the glue for {@link #SETTLE_MILLISECONDS} of frozen time on the frame's framebuffer at a display scale.
     *
     * @param glue  the glue
     * @param time  the time of the first frame in milliseconds
     * @param scale the display scale
     * @return the time after the last frame
     */
    private static long drawFrames(@NotNull GlueInterface glue, long time, @NotNull DisplayScale scale) {
        UiSurface surface = UiSurface.of(FRAME_WIDTH, FRAME_HEIGHT, scale);
        long frameTime = time;

        for (int elapsed = 0; elapsed < SETTLE_MILLISECONDS; elapsed += FRAME_MILLISECONDS) {
            glue.draw(surface, frameTime);
            frameTime += FRAME_MILLISECONDS;
        }

        return frameTime;
    }

    /**
     * Reads the drawn frame back, writes it to {@link #SCREENSHOT_DIRECTORY} and digests its pixels.
     *
     * @param framebuffer the framebuffer the glue draws into
     * @param name        the frame name
     * @return the hexadecimal SHA-256 digest of the RGBA rows, bottom row first
     * @throws IOException if the frame cannot be written
     */
    private static @NotNull String captureFrame(int framebuffer, @NotNull String name) throws IOException {
        ByteBuffer pixels = MemoryUtil.memAlloc(FRAME_WIDTH * FRAME_HEIGHT * RGBA_BYTES);

        try {
            GL11C.glFinish();
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, framebuffer);
            GL11C.glReadPixels(0, 0, FRAME_WIDTH, FRAME_HEIGHT, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);

            byte[] bytes = new byte[pixels.remaining()];
            BufferedImage image = new BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_ARGB);

            pixels.get(bytes);

            for (int row = 0; row < FRAME_HEIGHT; row++) {
                for (int column = 0; column < FRAME_WIDTH; column++) {
                    int offset = (row * FRAME_WIDTH + column) * RGBA_BYTES;
                    int color = OPAQUE_ARGB | Byte.toUnsignedInt(bytes[offset]) << Short.SIZE |
                                Byte.toUnsignedInt(bytes[offset + GREEN_OFFSET]) << Byte.SIZE | Byte.toUnsignedInt(bytes[offset + BLUE_OFFSET]);

                    image.setRGB(column, FRAME_HEIGHT - row - 1, color);
                }
            }

            Path directory = Path.of(SCREENSHOT_DIRECTORY);

            Files.createDirectories(directory);
            ImageIO.write(image, SCREENSHOT_FORMAT, directory.resolve(name + "." + SCREENSHOT_FORMAT).toFile());

            return HexFormat.of().formatHex(MessageDigest.getInstance(DIGEST_ALGORITHM).digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException(exception, "No {0} digest: {1}", DIGEST_ALGORITHM, exception.getMessage());
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /**
     * Creates the hidden window of an OpenGL core profile context in the frame size.
     *
     * @param host the host
     * @return the created window
     */
    private static @NotNull HostWindow createHiddenWindow(@NotNull ClientHost host) {
        return host.createWindow(WindowSettings.builder(GlueInterfaceTest.class.getSimpleName(), WINDOW_WIDTH, WINDOW_HEIGHT).build());
    }

    /**
     * Creates client audio on a silent device: the glue plays its sounds on the host clock without output.
     *
     * @return the client audio, which the client context closes
     */
    private static @NotNull ClientAudio createSilentAudio() {
        return new ClientAudio(new AudioMixer(new SilentAudioDevice(System::nanoTime)), new SoundSettings());
    }


    @TempDir
    @Nullable Path directory;

    @Test
    void draw_should_match_reference_frames_of_main_menu_popups_and_custom_game() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Path installationPath = ExternalInput.INSTALLATION.getPath();
        Path root = this.directory;

        Assertions.assertNotNull(root);

        ClientHost host = ClientHost.initialize();
        int colorTexture = 0;
        int framebuffer = 0;
        Map<String, String> digests = new LinkedHashMap<>();

        try {
            HostWindow window = createHiddenWindow(host);

            window.makeContextCurrent();
            host.createCapabilities();

            colorTexture = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, colorTexture);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, FRAME_WIDTH, FRAME_HEIGHT, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE,
                               (ByteBuffer) null);
            framebuffer = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colorTexture, 0);
            Assertions.assertEquals(GL30C.GL_FRAMEBUFFER_COMPLETE, GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER));

            InstallLayout layout = InstallLayout.of(root);
            GlueFolders folders = new GlueFolders(layout.getCharacterDirectory(),
                                                  layout.getMapDirectory(),
                                                  layout.getReplayDirectory(),
                                                  layout.getSaveDirectory(),
                                                  layout.getRecentMapsFile());

            try (ClientContext client = ClientContext.open(layout, ScrResourceProvider.open(installationPath, ScrIscriptImporter::readLibrary), LOCALE,
                                                           createSilentAudio());
                 GlueInterface glue = GlueInterface.open(client, LOCALE, folders, null)) {
                long time = 0;

                glue.registerScreen(GlueNavigator.Destination.MAIN_MENU, context -> new MainMenuScreen(BeaconLinkGlueScenario.UNKNOWN_RELEASE));
                glue.setTargetFramebuffer(framebuffer, FRAME_WIDTH, FRAME_HEIGHT);

                glue.showScreen(GlueNavigator.Destination.MAIN_MENU);
                time = drawFrames(glue, time);
                digests.put(MAIN_MENU_FRAME, captureFrame(framebuffer, MAIN_MENU_FRAME));

                glue.showOptions();
                time = drawFrames(glue, time);
                digests.put(OPTIONS_POPUP_FRAME, captureFrame(framebuffer, OPTIONS_POPUP_FRAME));

                glue.showScreen(GlueNavigator.Destination.MAIN_MENU);
                time = drawFrames(glue, time);
                glue.showError(ERROR_KEY);
                time = drawFrames(glue, time);
                digests.put(ERROR_POPUP_FRAME, captureFrame(framebuffer, ERROR_POPUP_FRAME));

                glue.showScreen(GlueNavigator.Destination.CUSTOM_GAME);
                drawFrames(glue, time);
                digests.put(CUSTOM_GAME_FRAME, captureFrame(framebuffer, CUSTOM_GAME_FRAME));
            }

            for (float scale : DISPLAY_SCALES) {
                try (ClientContext client = ClientContext.open(layout, ScrResourceProvider.open(installationPath, ScrIscriptImporter::readLibrary),
                                                               LOCALE, createSilentAudio());
                     GlueInterface glue = GlueInterface.open(client, LOCALE, folders, null)) {
                    String name = SCALED_MAIN_MENU_FRAME + scale;

                    glue.registerScreen(GlueNavigator.Destination.MAIN_MENU, context -> new MainMenuScreen(BeaconLinkGlueScenario.UNKNOWN_RELEASE));
                    glue.setTargetFramebuffer(framebuffer, FRAME_WIDTH, FRAME_HEIGHT);
                    glue.showScreen(GlueNavigator.Destination.MAIN_MENU);
                    drawFrames(glue, 0, DisplayScale.of(scale, 1));
                    digests.put(name, captureFrame(framebuffer, name));
                }
            }
        } finally {
            int createdTexture = colorTexture;
            int createdFramebuffer = framebuffer;

            IgnoreException.execute(
                    () -> {
                        if (createdFramebuffer != 0) {
                            GL30C.glDeleteFramebuffers(createdFramebuffer);
                        }
                    },
                    () -> {
                        if (createdTexture != 0) {
                            GL11C.glDeleteTextures(createdTexture);
                        }
                    }
            );
            host.close();
        }

        List<String> mismatches = new ArrayList<>();

        for (Map.Entry<String, String> reference : REFERENCE_DIGESTS.entrySet()) {
            String digest = digests.get(reference.getKey());

            if (!reference.getValue().equals(digest)) {
                mismatches.add(reference.getKey() + " " + digest);
            }
        }

        Assertions.assertEquals(List.of(), mismatches, "Frames that differ from the reference (see " + SCREENSHOT_DIRECTORY + ").");
    }

    @Test
    void open_should_show_campaign_link_error_when_next_scenario_cannot_be_loaded() throws IOException {
        this.checkCampaignLinkError(this.createMissingScenarioResume(), null);
    }

    @Test
    void leave_game_should_show_campaign_link_error_when_next_scenario_cannot_be_loaded() throws IOException {
        this.checkCampaignLinkError(null, this.createMissingScenarioResume());
    }

    /**
     * Creates the resume after a custom game whose next scenario names a missing map next to the played one.
     *
     * @return the resume
     */
    private @NotNull GlueResume createMissingScenarioResume() {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        GlueGameRequest played = GlueGameRequest.createCustom(root.resolve(PLAYED_MAP_NAME),
                                                              PLAYER_NAME,
                                                              true,
                                                              GameTemplate.USE_MAP_SETTINGS,
                                                              TERRAN,
                                                              new int[0]);

        return GlueResume.createAfterGame(played, true).withNextScenario(MISSING_SCENARIO_NAME);
    }

    /**
     * Opens a glue at a resume, optionally leaves a game with another resume, and checks the popups over the current screen.
     *
     * @param openResume  the screen the glue opens at, or {@code null} for the title screen
     * @param leaveResume the screen a left game continues at, or {@code null} to leave no game
     * @throws IOException if an installed asset cannot be read
     */
    private void checkCampaignLinkError(@Nullable GlueResume openResume, @Nullable GlueResume leaveResume) throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Path installationPath = ExternalInput.INSTALLATION.getPath();
        Path root = this.directory;

        Assertions.assertNotNull(installationPath);
        Assertions.assertNotNull(root);

        ClientHost host = ClientHost.initialize();

        try {
            HostWindow window = createHiddenWindow(host);

            window.makeContextCurrent();
            host.createCapabilities();

            InstallLayout layout = InstallLayout.of(root);
            GlueFolders folders = new GlueFolders(layout.getCharacterDirectory(),
                                                  layout.getMapDirectory(),
                                                  layout.getReplayDirectory(),
                                                  layout.getSaveDirectory(),
                                                  layout.getRecentMapsFile());

            try (ClientContext client = ClientContext.open(layout, ScrResourceProvider.open(installationPath, ScrIscriptImporter::readLibrary), LOCALE,
                                                           createSilentAudio());
                 GlueInterface glue = GlueInterface.open(client, LOCALE, folders, openResume)) {
                if (leaveResume != null) {
                    glue.enterGame();
                    glue.leaveGame(leaveResume);
                }

                Assertions.assertTrue(glue.isPopupOpen(), "The campaign-link error is open over the campaign screen.");
                Assertions.assertEquals(POPUP_COUNT, glue.getPopupLayers().size());
            }
        } finally {
            host.close();
        }
    }
}
