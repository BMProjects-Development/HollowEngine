package ru.hollowhorizon.hollowengine.bootstrap.runtime;

import java.io.File;
import java.util.List;

public final class AddonBootstrapContract {
    public static final String DESCRIPTOR_PATH = "META-INF/plugin.properties";
    public static final String BOOTSTRAP_LIBRARY_PATH = "hollowengine-addon-bootstrap/";
    public static final String REGULAR_LIBRARY_PATH = "hollowengine-addon-libs/";
    public static final String LOADED_ADDON_FINGERPRINTS_PROPERTY = "hollowengine.addons.bootstrap.loaded";
    public static final String REJECTED_ADDON_FINGERPRINTS_PROPERTY = "hollowengine.addons.bootstrap.rejected";
    /** A directory of addon jars a development run loads ahead of every other source; set by the Gradle run tasks. */
    public static final String DEVELOPMENT_ADDONS_PROPERTY = "hollowengine.devAddons";
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
}
