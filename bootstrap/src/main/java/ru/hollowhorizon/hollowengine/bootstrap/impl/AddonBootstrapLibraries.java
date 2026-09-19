package ru.hollowhorizon.hollowengine.bootstrap.impl;

import org.apache.logging.log4j.Logger;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonBootstrapContract;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonVersions;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class AddonBootstrapLibraries {
    private static final Set<String> NATIVE_EXTENSIONS = Set.of(".dll", ".so", ".dylib", ".jnilib");

    private AddonBootstrapLibraries() {
    }

    /**
     * [directories] are searched in order, and that order decides ties: the engine's own addon folder
     * comes before the loader's mods folder.
     */
    static Result discover(List<File> directories, File cacheDirectory, Logger logger) throws IOException {
        List<File> sortedAddons = selectAddons(directories, logger);
        if (sortedAddons.isEmpty()) {
            removeStaleExtractions(cacheDirectory, Set.of(), logger);
            System.clearProperty(AddonBootstrapContract.LOADED_ADDON_FINGERPRINTS_PROPERTY);
            System.clearProperty(AddonBootstrapContract.REJECTED_ADDON_FINGERPRINTS_PROPERTY);
            return new Result(List.of());
        }

        Map<String, ExtractedLibrary> librariesByName = new LinkedHashMap<>();
        Set<String> loadedAddonFingerprints = new LinkedHashSet<>();
        Set<String> rejectedAddonFingerprints = new LinkedHashSet<>();
        Set<String> addonFingerprints = new HashSet<>();

        for (File addonFile : sortedAddons) {
            String addonFingerprint = sha256(addonFile.toPath());
            addonFingerprints.add(addonFingerprint);
            Map<String, ExtractedLibrary> addonLibraries = new LinkedHashMap<>();
            try {
                boolean containsBootstrapLibraries = inspectAddon(
                        addonFile,
                        addonFingerprint,
                        cacheDirectory,
                        addonLibraries
                );
                Map<String, ExtractedLibrary> combinedLibraries = new LinkedHashMap<>(librariesByName);
                mergeLibraries(combinedLibraries, addonLibraries);
                validateLibraryContents(combinedLibraries.values());
                librariesByName.clear();
                librariesByName.putAll(combinedLibraries);
                if (containsBootstrapLibraries) loadedAddonFingerprints.add(addonFingerprint);
            } catch (IOException exception) {
                rejectedAddonFingerprints.add(addonFingerprint);
                logger.error("Rejected unsafe bootstrap libraries from addon {}", addonFile.getName(), exception);
            }
        }

        removeStaleExtractions(cacheDirectory, addonFingerprints, logger);
        System.setProperty(
                AddonBootstrapContract.LOADED_ADDON_FINGERPRINTS_PROPERTY,
                String.join(",", loadedAddonFingerprints)
        );
        System.setProperty(
                AddonBootstrapContract.REJECTED_ADDON_FINGERPRINTS_PROPERTY,
                String.join(",", rejectedAddonFingerprints)
        );
        if (!librariesByName.isEmpty()) {
            logger.info(
                    "Prepared {} bootstrap libraries for {} addons before runtime initialization",
                    librariesByName.size(),
                    loadedAddonFingerprints.size()
            );
        }
        return new Result(librariesByName.values().stream().map(ExtractedLibrary::file).map(File::toURI)
                .map(uri -> {
                    try {
                        return uri.toURL();
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                }).toList());
    }

    private static List<File> selectAddons(List<File> directories, Logger logger) {
        Map<String, AddonFile> selected = new LinkedHashMap<>();

        for (int priority = 0; priority < directories.size(); priority++) {
            File directory = directories.get(priority);
            File[] files = directory.listFiles(file ->
                    file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (files == null) continue;

            List<File> sorted = new ArrayList<>(List.of(files));
            sorted.sort(Comparator.comparing(File::getName));
            for (File file : sorted) {
                AddonFile addon = readAddon(file, priority);
                if (addon == null) continue;

                AddonFile previous = selected.get(addon.id());
                if (previous == null) {
                    selected.put(addon.id(), addon);
                    continue;
                }

                AddonFile winner = preferred(previous, addon);
                selected.put(addon.id(), winner);
                AddonFile ignored = winner == previous ? addon : previous;
                logger.warn(
                        "Addon '{}' found twice: using {} ({}), ignoring {} ({})",
                        addon.id(),
                        winner.file().getName(),
                        winner.version(),
                        ignored.file().getName(),
                        ignored.version()
                );
            }
        }

        List<File> addons = new ArrayList<>();
        selected.values().stream()
                .sorted(Comparator.comparing(addon -> addon.file().getName()))
                .forEach(addon -> addons.add(addon.file()));
        return addons;
    }

    private static AddonFile preferred(AddonFile left, AddonFile right) {
        int byVersion = AddonVersions.compare(left.version(), right.version());
        if (byVersion != 0) return byVersion > 0 ? left : right;
        return left.priority() <= right.priority() ? left : right;
    }

    private static AddonFile readAddon(File file, int priority) {
        try (JarFile jar = new JarFile(file, false)) {
            JarEntry descriptor = jar.getJarEntry(AddonBootstrapContract.DESCRIPTOR_PATH);
            if (descriptor == null) return null;

            Properties properties = new Properties();
            try (InputStream input = jar.getInputStream(descriptor)) {
                properties.load(input);
            }
            String id = properties.getProperty("id", "").trim();
            if (id.isEmpty()) return null;
            return new AddonFile(file, id, properties.getProperty("version", "1.0.0").trim(), priority);
        } catch (IOException exception) {
            return null;
        }
    }

    private record AddonFile(File file, String id, String version, int priority) {
    }

    /**
     * Libraries are extracted per addon jar, so each update of an addon leaves the previous extraction behind.
     * Nothing is loaded from them yet at this point, so everything but the current addons' goes.
     */
    private static void removeStaleExtractions(File cacheDirectory, Set<String> addonFingerprints, Logger logger) {
        File[] extractions = cacheDirectory.toPath().resolve("addon-bootstrap").toFile().listFiles(File::isDirectory);
        if (extractions == null) return;
        for (File extraction : extractions) {
            if (addonFingerprints.contains(extraction.getName())) continue;
            try (var files = Files.walk(extraction.toPath())) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException exception) {
                logger.debug("Could not remove stale bootstrap libraries {}", extraction, exception);
            }
            if (!extraction.exists()) logger.info("Removed stale bootstrap libraries of a replaced addon: {}", extraction.getName());
        }
    }

    private static boolean inspectAddon(
            File addonFile,
            String addonFingerprint,
            File cacheDirectory,
            Map<String, ExtractedLibrary> librariesByName
    ) throws IOException {
        boolean found = false;
        try (JarFile addon = new JarFile(addonFile)) {
            Enumeration<JarEntry> entries = addon.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".jar")) continue;
                String fileName = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                if (entry.getName().startsWith(AddonBootstrapContract.REGULAR_LIBRARY_PATH)) {
                    rejectHostNativeLibrary(addonFile, fileName);
                    continue;
                }
                if (!entry.getName().startsWith(AddonBootstrapContract.BOOTSTRAP_LIBRARY_PATH)) continue;
                rejectHostNativeLibrary(addonFile, fileName);
                found = true;

                Path outputDirectory = cacheDirectory.toPath().resolve("addon-bootstrap").resolve(addonFingerprint);
                Files.createDirectories(outputDirectory);
                Path output = outputDirectory.resolve(fileName).normalize();
                if (!output.getParent().equals(outputDirectory.normalize())) {
                    throw new IOException("Illegal bootstrap library path in " + addonFile.getName() + ": " + entry.getName());
                }
                if (!Files.isRegularFile(output) || Files.size(output) != entry.getSize()) {
                    try (InputStream input = addon.getInputStream(entry)) {
                        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                String fingerprint = entry.getSize() + ":" + entry.getCrc();
                ExtractedLibrary previous = librariesByName.putIfAbsent(
                        fileName,
                        new ExtractedLibrary(output.toFile(), fingerprint, addonFile.getName())
                );
                if (previous != null && !previous.fingerprint().equals(fingerprint)) {
                    throw new IOException(
                            "Conflicting bootstrap library '" + fileName + "' is bundled by " +
                                    previous.addonName() + " and " + addonFile.getName()
                    );
                }
            }
        }
        return found;
    }

    private static void mergeLibraries(
            Map<String, ExtractedLibrary> target,
            Map<String, ExtractedLibrary> additions
    ) throws IOException {
        for (Map.Entry<String, ExtractedLibrary> addition : additions.entrySet()) {
            ExtractedLibrary previous = target.putIfAbsent(addition.getKey(), addition.getValue());
            if (previous != null && !previous.fingerprint().equals(addition.getValue().fingerprint())) {
                throw new IOException(
                        "Conflicting bootstrap library '" + addition.getKey() + "' is bundled by " +
                                previous.addonName() + " and " + addition.getValue().addonName()
                );
            }
        }
    }

    private static void validateLibraryContents(Iterable<ExtractedLibrary> libraries) throws IOException {
        Map<String, ContentOwner> ownedClasses = new HashMap<>();
        Map<String, ContentOwner> ownedNatives = new HashMap<>();
        for (ExtractedLibrary library : libraries) {
            try (JarFile jar = new JarFile(library.file())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (name.endsWith(".class") && !isModuleDescriptor(name)) {
                        registerContent(name, entry, library, ownedClasses, "class");
                    } else if (isNativeLibrary(name)) {
                        registerContent(name, entry, library, ownedNatives, "native resource");
                    }
                }
            }
        }
    }

    private static void registerContent(
            String name,
            JarEntry entry,
            ExtractedLibrary library,
            Map<String, ContentOwner> owners,
            String contentType
    ) throws IOException {
        String fingerprint = entry.getSize() + ":" + entry.getCrc();
        ContentOwner previous = owners.putIfAbsent(name, new ContentOwner(library.file().getName(), fingerprint));
        if (previous != null && !previous.fingerprint().equals(fingerprint)) {
            throw new IOException(
                    "Conflicting " + contentType + " '" + name + "' exists in " +
                            previous.libraryName() + " and " + library.file().getName()
            );
        }
    }

    private static void rejectHostNativeLibrary(File addonFile, String fileName) throws IOException {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (AddonBootstrapContract.HOST_NATIVE_LIBRARY_PREFIXES.stream().anyMatch(normalized::startsWith)) {
            throw new IOException(
                    "Addon " + addonFile.getName() + " bundles host native library '" + fileName +
                            "'. Minecraft's copy must be used to avoid JVM native conflicts."
            );
        }
    }

    private static boolean isNativeLibrary(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return NATIVE_EXTENSIONS.stream().anyMatch(normalized::endsWith);
    }

    private static boolean isModuleDescriptor(String name) {
        return name.equals("module-info.class") || name.endsWith("/module-info.class");
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return sha256(input);
        }
    }

    private static String sha256(InputStream input) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    record Result(List<URL> urls) {
    }

    private record ExtractedLibrary(File file, String fingerprint, String addonName) {
    }

    private record ContentOwner(String libraryName, String fingerprint) {
    }
}
