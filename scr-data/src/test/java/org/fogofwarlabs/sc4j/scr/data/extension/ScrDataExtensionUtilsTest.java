package org.fogofwarlabs.sc4j.scr.data.extension;

import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests the scenario size limit and the map file extensions that the {@link ScrDataExtension} implementations give the map reader.
 */
final class ScrDataExtensionUtilsTest {
    private static @NotNull ScrDataExtension createExtension(int maximumScenarioSize) {
        return new ScrDataExtension() {
            @Override
            public int getMaximumScenarioSize() {
                return maximumScenarioSize;
            }
        };
    }

    private static @NotNull ScrDataExtension createExtension(@NotNull String @NotNull ... mapFileExtensions) {
        return new ScrDataExtension() {
            @Override
            public @NotNull List<@NotNull String> getExtraMapFileExtensions() {
                return List.of(mapFileExtensions);
            }
        };
    }

    @Test
    void without_an_extension_no_map_file_extension_is_added() {
        Assertions.assertEquals(List.of(), ScrDataExtensionUtils.getExtraMapFileExtensions());
        Assertions.assertEquals(List.of(), ScrDataExtensionUtils.collectExtraMapFileExtensions(List.of(new ScrDataExtension() {
        })));
    }

    @Test
    void extensions_add_map_file_extensions_in_lower_case_each_once() {
        List<ScrDataExtension> extensions = List.of(createExtension(".SCE", ".abc"), createExtension(".sce"));

        Assertions.assertEquals(List.of(".sce", ".abc"), ScrDataExtensionUtils.collectExtraMapFileExtensions(extensions));
    }

    @Test
    void collect_extra_map_file_extensions_should_reject_an_extension_without_a_dot() {
        List<ScrDataExtension> extensions = List.of(createExtension("sce"));

        Assertions.assertThrows(IllegalArgumentException.class, () -> ScrDataExtensionUtils.collectExtraMapFileExtensions(extensions));
    }

    @Test
    void without_an_extension_the_native_scenario_size_limit_applies() {
        ScrDataExtension unchanged = new ScrDataExtension() {
        };

        Assertions.assertEquals(0x10000000, MpqArchive.NATIVE_MAXIMUM_SCENARIO_SIZE);
        Assertions.assertEquals(MpqArchive.NATIVE_MAXIMUM_SCENARIO_SIZE, ScrDataExtensionUtils.getMaximumScenarioSize());
        Assertions.assertEquals(MpqArchive.NATIVE_MAXIMUM_SCENARIO_SIZE, ScrDataExtensionUtils.findMaximumScenarioSize(List.of()));
        Assertions.assertEquals(MpqArchive.NATIVE_MAXIMUM_SCENARIO_SIZE, ScrDataExtensionUtils.findMaximumScenarioSize(List.of(unchanged)));
    }

    @Test
    void an_extension_raises_the_scenario_size_limit_and_the_largest_one_applies() {
        ScrDataExtension lower = createExtension(0);
        ScrDataExtension larger = createExtension(MpqArchive.MAXIMUM_FILE_SIZE);

        Assertions.assertEquals(MpqArchive.NATIVE_MAXIMUM_SCENARIO_SIZE, ScrDataExtensionUtils.findMaximumScenarioSize(List.of(lower)));
        Assertions.assertEquals(MpqArchive.MAXIMUM_FILE_SIZE, ScrDataExtensionUtils.findMaximumScenarioSize(List.of(lower, larger)));
    }
}
