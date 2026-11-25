package org.fogofwarlabs.sc4j.scr;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.app.FirstSceneFactory;
import org.fogofwarlabs.sc4j.engine.game.GameDefinition;
import org.fogofwarlabs.sc4j.engine.mod.*;
import org.fogofwarlabs.sc4j.engine.replay.ReplayFormatFactory;
import org.fogofwarlabs.sc4j.engine.resource.ResourceProvider;
import org.fogofwarlabs.sc4j.engine.save.SaveFormatFactory;
import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.ModManifestReader;
import org.fogofwarlabs.sc4j.manifest.settings.ChoiceSetting;
import org.fogofwarlabs.sc4j.manifest.settings.DirectorySetting;
import org.fogofwarlabs.sc4j.manifest.settings.value.SettingValues;
import org.fogofwarlabs.sc4j.manifest.settings.value.SettingValuesException;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.game.save.SaveGameKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tests the SC:R mod's manifest and its installation check: only the supported StarCraft: Remastered build runs.
 */
final class ScrModTest {
    private static final @NotNull String BUILD_INFO_HEADER = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|Version!STRING:0\n";
    private static final @NotNull String EXECUTABLE = "x86_64/StarCraft.exe";
    private static final @NotNull String MACOS_EXECUTABLE = "x86_64/StarCraft.app/Contents/MacOS/StarCraft";
    private static final byte @NotNull [] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G'};

    /**
     * Collects what the mod registers.
     */
    private static final class RecordedRegistration implements ModRegistration {
        private @Nullable ModApplication application;
        private final @NotNull List<byte @NotNull []> identityData;
        private @Nullable ResourceProvider resourceProvider;
        private final GameDefinition.@NotNull Builder simulation;
        private final @NotNull List<@NotNull ReplayFormatFactory> replayFormats;
        private final @NotNull List<@NotNull SaveFormatFactory> saveFormats;

        private RecordedRegistration() {
            this.application      = null;
            this.identityData     = new ArrayList<>();
            this.resourceProvider = null;
            this.simulation       = new GameDefinition.Builder();
            this.replayFormats    = new ArrayList<>();
            this.saveFormats      = new ArrayList<>();
        }

        @Override
        public void registerApplication(@NotNull ModApplication application) {
            this.application = application;
        }

        @Override
        public void registerFirstScene(@NotNull FirstSceneFactory factory) {
            Assertions.fail("The SC:R mod runs its own application and registers no first scene.");
        }

        @Override
        public void registerHeadlessRunner(@NotNull HeadlessRunner runner) {
            Assertions.fail("The SC:R mod runs its own application and registers no headless runner.");
        }

        @Override
        public void addIdentityData(byte @NotNull [] data) {
            this.identityData.add(data.clone());
        }

        @Override
        public void registerResourceProvider(@NotNull ResourceProvider provider) {
            this.resourceProvider = provider;
        }

        @Override
        public void registerReplayFormat(@NotNull ReplayFormatFactory factory) {
            this.replayFormats.add(factory);
        }

        @Override
        public void registerSaveFormat(@NotNull SaveFormatFactory factory) {
            this.saveFormats.add(factory);
        }

        @Override
        public GameDefinition.@NotNull Builder getSimulation() {
            return this.simulation;
        }
    }

    /**
     * Gives the mod a manifest, validated settings and a storage folder, as the runtime does.
     */
    private static final class TestContext implements ModContext, ModSettings, ModStorage {
        private final @NotNull ModManifest manifest;
        private final @NotNull SettingValues values;
        private final @NotNull Path storage;

        private TestContext(@NotNull ModManifest manifest, @NotNull SettingValues values, @NotNull Path storage) {
            this.manifest = manifest;
            this.values   = values;
            this.storage  = storage;
        }

        @Override
        public @NotNull ModManifest getManifest() {
            return this.manifest;
        }

        @Override
        public @NotNull ModSettings getSettings() {
            return this;
        }

        @Override
        public @NotNull ModStorage getStorage() {
            return this;
        }

        @Override
        public @NotNull Path getInstallDirectory() {
            return this.storage.getParent();
        }

        @Override
        public @NotNull List<@NotNull String> getArguments() {
            return List.of();
        }

        @Override
        public @NotNull Logger getLogger() {
            return Logger.of(ScrModTest.class);
        }

        @Override
        public boolean hasValue(@NotNull String key) {
            return this.values.hasValue(key);
        }

        @Override
        public @NotNull Path getDirectory(@NotNull String key) {
            return this.values.getDirectory(key);
        }

        @Override
        public @NotNull Path getFile(@NotNull String key) {
            return this.values.getFile(key);
        }

        @Override
        public @NotNull String getChoice(@NotNull String key) {
            return this.values.getChoice(key);
        }

        @Override
        public boolean getBoolean(@NotNull String key) {
            return this.values.getBoolean(key);
        }

        @Override
        public int getInteger(@NotNull String key) {
            return this.values.getInteger(key);
        }

        @Override
        public @NotNull String getString(@NotNull String key) {
            return this.values.getString(key);
        }

        @Override
        public @NotNull Path getRoot() {
            return this.storage;
        }

        @Override
        public @NotNull Path resolve(@NotNull String relativePath) {
            return this.storage.resolve(relativePath).normalize();
        }
    }

    @Test
    void manifest_names_this_entry_and_declares_the_installation_and_locale_settings() throws IOException, ManifestException {
        ModManifest manifest = readManifest();
        DirectorySetting folder = (DirectorySetting) manifest.getSettings().findDefinition(ScrMod.GAME_FOLDER_SETTING);
        ChoiceSetting locale = (ChoiceSetting) manifest.getSettings().findDefinition(ScrMod.LOCALE_SETTING);

        Assertions.assertEquals("scr", manifest.getId());
        Assertions.assertEquals(ScrMod.class.getName(), manifest.getEntry());
        Assertions.assertTrue(manifest.checkEngine(EngineVersion.CURRENT).isValid());
        Assertions.assertNotNull(folder);
        Assertions.assertEquals("starcraft-remastered", folder.getSharedName());
        Assertions.assertEquals(List.of("StarCraft"), folder.getUninstallEntries());
        Assertions.assertEquals(List.of("StarCraft"), folder.getProgramFolders());
        Assertions.assertTrue(folder.isRequired());
        Assertions.assertEquals(List.of(".build.info"), folder.getContainedPaths());
        Assertions.assertEquals(List.of(EXECUTABLE, MACOS_EXECUTABLE), folder.getAnyContainedPaths());
        Assertions.assertNotNull(folder.getMessage());
        Assertions.assertNotNull(locale);
        Assertions.assertTrue(locale.isSystemDefault());
        Assertions.assertTrue(locale.getOptions().containsAll(List.of("enUS", "koKR", "deDE", "zhTW")), () -> locale.getOptions().toString());
        Assertions.assertEquals("koKR", locale.findOption(Locale.KOREA));

        for (String image : manifest.getImages().getEntries()) {
            try (InputStream input = ScrModTest.class.getClassLoader().getResourceAsStream(image)) {
                Assertions.assertNotNull(input, image);
                Assertions.assertArrayEquals(PNG_SIGNATURE, input.readNBytes(PNG_SIGNATURE.length), image);
            }
        }
    }

    @Test
    void initialize_should_refuse_unsupported_and_unreadable_installations(@TempDir @NotNull Path directory) throws Exception {
        Path older = createInstallation(directory.resolve("older"), "1.23.9.10756");
        Path unreadable = createInstallation(directory.resolve("unreadable"), "1.23.10.13515");

        Files.writeString(unreadable.resolve(".build.info"), "Branch!STRING:0\n");

        ModException olderFailure = Assertions.assertThrows(ModException.class, () -> new ScrMod().initialize(createContext(older, directory)));
        ModException unreadableFailure = Assertions.assertThrows(ModException.class, () -> new ScrMod().initialize(createContext(unreadable, directory)));

        Assertions.assertTrue(olderFailure.getMessage().contains("1.23.9.10756 in " + older + " is not supported"), olderFailure.getMessage());
        Assertions.assertTrue(unreadableFailure.getMessage().contains("has no readable .build.info"), unreadableFailure.getMessage());
    }

    @Test
    void register_should_add_the_build_to_the_identity_and_register_the_client_and_resources(@TempDir @NotNull Path directory) throws Exception {
        Path installation = createInstallation(directory.resolve("StarCraft"), ScrMod.SUPPORTED_BUILD);
        ScrMod mod = new ScrMod();
        RecordedRegistration registration = new RecordedRegistration();

        Assertions.assertThrows(ModException.class, () -> mod.register(registration));

        mod.initialize(createContext(installation, directory));
        mod.register(registration);

        Assertions.assertNotNull(registration.application);
        Assertions.assertFalse(registration.application.isHeadlessCapable());
        Assertions.assertEquals(1, registration.identityData.size());
        Assertions.assertEquals(ScrMod.SUPPORTED_BUILD, new String(registration.identityData.getFirst(), StandardCharsets.UTF_8));
        Assertions.assertInstanceOf(ScrResourceProvider.class, registration.resourceProvider);
        Assertions.assertInstanceOf(LcgRandom.class, registration.simulation.build().getRandomFactory().create(1L));
        Assertions.assertEquals(1, registration.replayFormats.size());
        Assertions.assertEquals(SaveGameKind.values().length, registration.saveFormats.size());

        mod.close();
    }

    private static @NotNull ModManifest readManifest() throws IOException, ManifestException {
        try (InputStream input = ScrModTest.class.getClassLoader().getResourceAsStream(ModManifestReader.FILE_NAME)) {
            Assertions.assertNotNull(input);

            return ModManifestReader.parse(input.readAllBytes());
        }
    }

    private static @NotNull TestContext createContext(@NotNull Path installation, @NotNull Path directory) throws IOException, ManifestException,
                                                                                                                  SettingValuesException {
        ModManifest manifest = readManifest();
        SettingValues values = SettingValues.resolve(manifest.getSettings(), Map.of(ScrMod.GAME_FOLDER_SETTING, installation.toString()));

        return new TestContext(manifest, values, directory.resolve("userdata").resolve("mods").resolve("scr"));
    }

    private static @NotNull Path createInstallation(@NotNull Path installation, @NotNull String version) throws IOException {
        Files.createDirectories(installation.resolve(EXECUTABLE).getParent());
        Files.writeString(installation.resolve(EXECUTABLE), "");
        Files.writeString(installation.resolve(".build.info"), BUILD_INFO_HEADER + "kr|1|864772b9ff94f6d372aa4ee90ee2f8ab|" + version + "\n");

        return installation;
    }
}
