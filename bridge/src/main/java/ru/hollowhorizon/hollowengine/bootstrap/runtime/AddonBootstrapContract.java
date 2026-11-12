package ru.hollowhorizon.hollowengine.bootstrap.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AddonBootstrapContract {
    public static final String DESCRIPTOR_PATH = "META-INF/plugin.properties";
    public static final String BOOTSTRAP_LIBRARY_PATH = "hollowengine-addon-bootstrap/";
    public static final String REGULAR_LIBRARY_PATH = "hollowengine-addon-libs/";
    public static final String LOADED_ADDON_FINGERPRINTS_PROPERTY = "hollowengine.addons.bootstrap.loaded";
    public static final String REJECTED_ADDON_FINGERPRINTS_PROPERTY = "hollowengine.addons.bootstrap.rejected";
    /** A directory of addon jars a development run loads ahead of every other source; set by the Gradle run tasks. */
    public static final String DEVELOPMENT_ADDONS_PROPERTY = "hollowengine.devAddons";
    /** Kept in {@code hollowengine/addons}: one id of a switched off addon per line. */
    public static final String DISABLED_ADDONS_FILE = ".disabled-addons";
    public static final List<String> HOST_NATIVE_LIBRARY_PREFIXES = List.of(
            "lwjgl", "jemalloc", "glfw", "openal", "opengl", "stb", "tinyfd", "shaderc", "vulkan",
            "jinput", "jna-", "jna-platform-", "netty-", "netty-tcnative", "oshi-core"
    );

    private AddonBootstrapContract() {
    }

    /** The directory named by {@link #DEVELOPMENT_ADDONS_PROPERTY}, or null outside a development run. */
    public static File developmentAddonsDirectory() {
        String path = System.getProperty(DEVELOPMENT_ADDONS_PROPERTY);
        if (path == null || path.isBlank()) return null;
        return new File(path).getAbsoluteFile();
    }

    /** Ids listed in {@link #DISABLED_ADDONS_FILE} of {@code addonsDirectory}; none when it cannot be read. */
    public static Set<String> disabledAddonIds(File addonsDirectory) {
        Set<String> ids = new LinkedHashSet<>();
        File file = new File(addonsDirectory, DISABLED_ADDONS_FILE);
        if (!file.isFile()) return ids;
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String id = line.trim();
                if (!id.isEmpty() && !id.startsWith("#")) ids.add(id);
            }
        } catch (IOException exception) {
            return ids;
        }
        return ids;
    }
}
