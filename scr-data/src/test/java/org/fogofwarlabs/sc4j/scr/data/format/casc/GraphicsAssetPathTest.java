package org.fogofwarlabs.sc4j.scr.data.format.casc;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Inspects CASC paths for graphics table and GRP asset discovery.
 */
@RequiresInstallation
final class GraphicsAssetPathTest {
    @Test
    void lists_graphics_tables_and_marine_grp_paths() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            List<String> fileNames = storage.getFileNames();

            for (String fileName : fileNames) {
                String lowerName = fileName.toLowerCase(Locale.ROOT);

                if (lowerName.endsWith(".tbl") || (lowerName.contains("marine") && lowerName.endsWith(".grp"))) {
                    System.out.println(fileName);
                }
            }
        }
    }
}
