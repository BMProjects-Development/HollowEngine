package ru.hollowhorizon.hollowengine.bootstrap.impl.mixins;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Generated mixin classes on disk, for loaders that only read classes from their classpath.
 * <p>
 * The jar is named after its content, so a second game running from the same directory, which may hold the
 * file open, never has it rewritten underneath it. Jars of earlier launches are removed when nothing holds them.
 */
public final class ScriptMixinJars {
    private static final Path DIRECTORY = Paths.get("hollowengine", ".cache", "mixins").toAbsolutePath();
    private static final String PREFIX = "scripts-";

    private ScriptMixinJars() {
    }

    public static Path write(Map<String, byte[]> classes) throws IOException {
        Map<String, byte[]> sorted = new TreeMap<>(classes);
        Path jar = DIRECTORY.resolve(PREFIX + hash(sorted) + ".jar");
        Files.createDirectories(DIRECTORY);
        removeStale(jar);
        if (Files.isRegularFile(jar)) return jar;

        Path temporary = Files.createTempFile(DIRECTORY, PREFIX, ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary); JarOutputStream archive = new JarOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : sorted.entrySet()) {
                archive.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                archive.write(entry.getValue());
                archive.closeEntry();
            }
        }
        Files.move(temporary, jar, StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }

    private static void removeStale(Path current) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(DIRECTORY, PREFIX + "*")) {
            for (Path file : files) {
                if (file.equals(current)) continue;
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // Still open in another game running from this directory.
                }
            }
        } catch (IOException ignored) {
            // Nothing to clean up.
        }
    }

    private static String hash(Map<String, byte[]> classes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update(entry.getValue());
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
