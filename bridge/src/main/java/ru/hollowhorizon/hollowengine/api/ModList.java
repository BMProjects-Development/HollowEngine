package ru.hollowhorizon.hollowengine.api;

import java.io.File;
import java.util.List;

public interface ModList {
    boolean isLoaded(String modId);

    File getFile(String modId);

    /** Every loaded mod, the platform itself and Minecraft included. */
    List<ModInfo> getMods();

    record ModInfo(String id, String name, String version) {
    }
}
