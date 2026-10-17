package ru.hollowhorizon.hollowengine.bootstrap.impl;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class EmbeddedRuntimeJar {
    private static final String RUNTIME_JAR = "META-INF/hollowengine/runtime/HollowEngineRuntime.jar";
    private static final String RUNTIME_SHA = "META-INF/hollowengine/runtime/HollowEngineRuntime.sha256";

    private EmbeddedRuntimeJar() {
    }

    static @Nullable File extract(Class<?> anchor, File cacheDirectory) throws IOException {
        return extract(anchor.getClassLoader(), cacheDirectory);
    }

    static @Nullable File extract(ClassLoader classLoader, File cacheDirectory) throws IOException {
        String checksum;
        try (InputStream shaStream = classLoader.getResourceAsStream(RUNTIME_SHA)) {
            if (shaStream == null) return null;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(shaStream, StandardCharsets.UTF_8))) {
                checksum = reader.readLine();
            }
        }

        if (checksum == null || checksum.isBlank()) return null;

        Path runtimeDir = cacheDirectory.toPath().resolve("runtime");
        Files.createDirectories(runtimeDir);
        Path target = runtimeDir.resolve("HollowEngineRuntime-" + checksum + ".jar");
        if (matchesChecksum(target, checksum)) return target.toFile();

        try (InputStream jarStream = classLoader.getResourceAsStream(RUNTIME_JAR)) {
            if (jarStream == null) return null;
            Path temporary = Files.createTempFile(runtimeDir, "HollowEngineRuntime-", ".tmp");
            try {
                Files.copy(jarStream, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (!matchesChecksum(temporary, checksum)) {
                    throw new IOException("Embedded runtime checksum does not match " + checksum);
                }
                try {
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException exception) {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException exception) {
                    // Another client may have installed and opened the same runtime while we copied it.
                    if (!matchesChecksum(target, checksum)) throw exception;
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        return target.toFile();
    }

    private static boolean matchesChecksum(Path path, String checksum) throws IOException {
        if (!Files.isRegularFile(path)) return false;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(checksum);
    }
}
