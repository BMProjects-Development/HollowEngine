package ru.hollowhorizon.hollowengine.fabric.internal;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import ru.hollowhorizon.hollowengine.api.ModList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.jar.JarFile;

public class FabricModList implements ModList {
    @Override
    public boolean isLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public File getFile(String modId) {
        var modContainer = FabricLoader.getInstance().getModContainer(modId).orElseThrow();
        var origin = modContainer.getOrigin();

        if (origin.getKind().equals(ModOrigin.Kind.PATH)) {
            return origin.getPaths().getFirst().toFile();
        } else if (origin.getKind().equals(ModOrigin.Kind.NESTED)) {
            try {
                return getNestedModFile(origin);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new IllegalStateException("Unsupported kind: $kind");
        }
    }

    @Override
    public List<ModInfo> getMods() {
        return FabricLoader.getInstance().getAllMods().stream()
                .map(ModContainer::getMetadata)
                .map(metadata -> new ModInfo(metadata.getId(), metadata.getName(), metadata.getVersion().getFriendlyString()))
                .toList();
    }

    private File getNestedModFile(ModOrigin origin) throws IOException {
        var parentId = origin.getParentModId();
        var parentFile = getFile(parentId);
        var subLocation = origin.getParentSubLocation();

        var parentJar = new JarFile(parentFile);
        var nestedJarEntry = parentJar.getJarEntry(subLocation);

        var parts = subLocation.split("/");
        var fileName = parts[parts.length - 1];
        var newFile = new File("hollowengine/.cache/mods/" + fileName);
        if (!newFile.getParentFile().exists()) newFile.getParentFile().mkdirs();

        if (newFile.exists()) return newFile;
        else newFile.createNewFile();

        try (var stream = new FileOutputStream(newFile)) {
            try (var reader = parentJar.getInputStream(nestedJarEntry)) {
                stream.write(reader.readAllBytes());
            }
        }

        return newFile;
    }
}
