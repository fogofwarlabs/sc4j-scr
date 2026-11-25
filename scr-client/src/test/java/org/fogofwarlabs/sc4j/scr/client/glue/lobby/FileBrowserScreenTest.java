package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

/**
 * Tests which files {@link FileBrowserScreen} takes as maps: the native map extensions in any case, without a data extension that adds one.
 */
final class FileBrowserScreenTest {
    @Test
    void is_map_file_should_take_the_native_map_extensions_in_any_case() {
        Assertions.assertEquals(List.of(FileBrowserScreen.ORIGINAL_MAP_EXTENSION, FileBrowserScreen.EXPANSION_MAP_EXTENSION),
                                FileBrowserScreen.MAP_EXTENSIONS);
        Assertions.assertTrue(FileBrowserScreen.isMapFile(Path.of("maps", "Lost Temple.scm")));
        Assertions.assertTrue(FileBrowserScreen.isMapFile(Path.of("Python.SCX")));
        Assertions.assertFalse(FileBrowserScreen.isMapFile(Path.of("Python.sce")));
        Assertions.assertFalse(FileBrowserScreen.isMapFile(Path.of("Python.rep")));
        Assertions.assertFalse(FileBrowserScreen.isMapFile(Path.of("scx")));
    }
}
