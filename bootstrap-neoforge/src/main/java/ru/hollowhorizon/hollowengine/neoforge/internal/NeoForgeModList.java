package ru.hollowhorizon.hollowengine.neoforge.internal;

import cpw.mods.niofs.union.UnionFileSystem;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModFileInfo;
import ru.hollowhorizon.hollowengine.api.ModList;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;

/**
 * Reads the mod list FML discovered, which exists before mixins are prepared, unlike the one mods are
 * constructed from: scripts may have to be compiled before any mod is.
 */
public class NeoForgeModList implements ModList {
    private static final Path CACHE_DIR = FMLPaths.GAMEDIR.get()
            .resolve("hollowengine")
            .resolve(".cache")
            .resolve("mods");

    @Override
    public boolean isLoaded(String modId) {
        return FMLLoader.getLoadingModList().getModFileById(modId) != null;
    }

    @Override
    public File getFile(String modId) {
        return getModFile(modId);
    }

    @Override
    public List<ModInfo> getMods() {
        return FMLLoader.getLoadingModList().getMods().stream()
                .map(mod -> new ModInfo(mod.getModId(), mod.getDisplayName(), mod.getVersion().toString()))
                .toList();
    }

    private File getModFile(String modId) {
        IModFileInfo modFileInfo = FMLLoader.getLoadingModList().getModFileById(modId);
        if (modFileInfo == null) {
            throw new IllegalArgumentException("Mod is not loaded or has no mod file: " + modId);
        }

        Path path = modFileInfo.getFile().getFilePath();
        return toCompilerFile(modId, path);
    }

    private static File toCompilerFile(String modId, Path path) {
        Objects.requireNonNull(path, "mod path");

        Path realPath = unwrapUnionFileSystem(path);

        File direct = tryToFile(realPath);
        if (direct != null && direct.exists()) {
            return direct;
        }

        // Union File System позволяет обращаться к jarInJar, только вот Kotlin Compiler работает только с реальными файлами
        try {
            return materializeToCache(modId, realPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to materialize mod file for compiler classpath: " + modId + " -> " + realPath, e);
        }
    }

    private static Path unwrapUnionFileSystem(Path path) {
        FileSystem fs = path.getFileSystem();

        if (fs instanceof UnionFileSystem unionFs) {
            Path primary = unionFs.getPrimaryPath();
            if (primary != null && Files.exists(primary)) {
                return primary;
            }
        }

        return path;
    }

    private static File tryToFile(Path path) {
        try {
            return path.toFile();
        } catch (UnsupportedOperationException ignored) {
            return null;
        }
    }

    private static File materializeToCache(String modId, Path source) throws IOException {
        Files.createDirectories(CACHE_DIR);

        String sourceName = source.getFileName() != null ? source.getFileName().toString() : modId + ".jar";
        if (!sourceName.endsWith(".jar")) {
            sourceName = modId + ".jar";
        }

        long size = Files.isRegularFile(source) ? Files.size(source) : -1L;
        long modified = Files.exists(source) ? Files.getLastModifiedTime(source).toMillis() : -1L;

        String safeName = sanitize(modId + "-" + modified + "-" + size + "-" + sourceName);
        Path target = CACHE_DIR.resolve(safeName);

        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return target.toFile();
        }

        Path tmp = CACHE_DIR.resolve(safeName + ".tmp");

        Files.deleteIfExists(tmp);
        try (InputStream is = Files.newInputStream(source)) {
            Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
        }

        if (Files.size(tmp) <= 0) {
            Files.deleteIfExists(tmp);
            throw new IOException("Copied empty classpath jar from " + source);
        }

        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }

        return target.toFile();
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}