package ru.hollowhorizon.hollowengine.bootstrap.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where a descriptor says its addon runs. The bootstrap and the runtime read the two keys through this class
 *
 * <p>{@code platform} is either {@link #COMMON}, an addon that knows no loader, or a comma-separated list
 * of the loaders whose API the addon uses, each named by {@link RuntimePlatform#id()}.
 */
public final class AddonTargets {
    public static final String PLATFORM_KEY = "platform";
    public static final String ENVIRONMENT_KEY = "environment";
    public static final String COMMON = "common";
    public static final String CLIENT = "client";
    public static final String SERVER = "server";

    private AddonTargets() {
    }

    /** The declared value in its canonical form; a missing or blank one means {@link #COMMON}. */
    public static String normalize(String declared) {
        if (declared == null || declared.isBlank()) return COMMON;
        return declared.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The loaders a declared platform names, in the order of {@link RuntimePlatform}: none for
     * {@link #COMMON}, and null when a part of it names no loader at all.
     */
    public static List<RuntimePlatform> loadersOf(String declared) {
        String platform = normalize(declared);
        if (platform.equals(COMMON)) return List.of();

        Set<String> named = new LinkedHashSet<>();
        for (String part : platform.split(",")) {
            if (!part.isBlank()) named.add(part.trim());
        }
        List<RuntimePlatform> loaders = new ArrayList<>();
        for (RuntimePlatform loader : RuntimePlatform.values()) {
            if (named.remove(loader.id())) loaders.add(loader);
        }
        return named.isEmpty() && !loaders.isEmpty() ? List.copyOf(loaders) : null;
    }

    /**
     * The declared platform in its canonical form: {@link #COMMON}, or its loaders in the order of
     * {@link RuntimePlatform} joined by commas. A value that names something else comes back as written.
     */
    public static String normalizePlatform(String declared) {
        List<RuntimePlatform> loaders = loadersOf(declared);
        return loaders == null ? normalize(declared) : platformId(loaders);
    }

    /** The canonical value of {@code platform} for these loaders, {@link #COMMON} for none. */
    public static String platformId(List<RuntimePlatform> loaders) {
        List<String> ids = new ArrayList<>();
        for (RuntimePlatform loader : RuntimePlatform.values()) {
            if (loaders.contains(loader)) ids.add(loader.id());
        }
        return ids.isEmpty() ? COMMON : String.join(",", ids);
    }

    /** What {@code platform} may hold, for a message about a value it may not. */
    public static String platformHint() {
        List<String> ids = new ArrayList<>();
        for (RuntimePlatform loader : RuntimePlatform.values()) ids.add(loader.id());
        return COMMON + ", or loaders separated by commas out of " + String.join(", ", ids);
    }

    public static List<String> environments() {
        return List.of(COMMON, CLIENT, SERVER);
    }

    /** False for a loader other than the running one, and for a value that names no loader at all. */
    public static boolean runsOn(String declaredPlatform, RuntimePlatform running) {
        List<RuntimePlatform> loaders = loadersOf(declaredPlatform);
        return loaders != null && (loaders.isEmpty() || loaders.contains(running));
    }

    /**
     * Whether the addon is written against the API of {@code loader}. A {@link #COMMON} addon runs on
     * every loader and is written against none of them.
     */
    public static boolean namesLoader(String declaredPlatform, RuntimePlatform loader) {
        List<RuntimePlatform> loaders = loadersOf(declaredPlatform);
        return loaders != null && loaders.contains(loader);
    }

    /** False for the other physical side, and for a value that names no side at all. */
    public static boolean runsIn(String declaredEnvironment, boolean physicalClient) {
        String environment = normalize(declaredEnvironment);
        if (environment.equals(COMMON)) return true;
        return physicalClient ? environment.equals(CLIENT) : environment.equals(SERVER);
    }

    /** The variant of {@code key} that applies only on {@code platform}, such as {@code entry.fabric}. */
    public static String platformKey(String key, RuntimePlatform platform) {
        return key + '.' + platform.id();
    }
}
