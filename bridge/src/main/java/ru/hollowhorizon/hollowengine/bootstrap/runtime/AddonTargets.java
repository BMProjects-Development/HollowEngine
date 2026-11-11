package ru.hollowhorizon.hollowengine.bootstrap.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Where a descriptor says its addon runs. The bootstrap and the runtime read the two keys through this
 * class, so that they never disagree about whether an addon belongs to the running game.
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
     * The declared platform in its canonical form. {@link #COMMON} is an addon that knows no loader; any
     * other value lists the loaders the addon is written against, in the order of {@link RuntimePlatform}
     * and joined by commas, however the descriptor spelled them. A value that names anything else comes
     * back as it was written, and is none of {@link #platforms()}.
     */
    public static String normalizePlatform(String declared) {
        String platform = normalize(declared);
        if (platform.equals(COMMON)) return COMMON;

        List<String> named = new ArrayList<>();
        for (String part : platform.split(",")) {
            if (!part.isBlank()) named.add(part.trim());
        }
        List<String> loaders = new ArrayList<>();
        for (RuntimePlatform loader : RuntimePlatform.values()) {
            if (named.removeIf(loader.id()::equals)) loaders.add(loader.id());
        }
        return named.isEmpty() && !loaders.isEmpty() ? String.join(",", loaders) : platform;
    }

    /** Every value {@code platform} can have, in canonical form. */
    public static List<String> platforms() {
        String fabric = RuntimePlatform.FABRIC.id();
        String neoforge = RuntimePlatform.NEOFORGE.id();
        return List.of(COMMON, fabric, neoforge, fabric + "," + neoforge);
    }

    public static List<String> environments() {
        return List.of(COMMON, CLIENT, SERVER);
    }

    /** False for a loader other than the running one, and for a value that names no loader at all. */
    public static boolean runsOn(String declaredPlatform, RuntimePlatform running) {
        return normalizePlatform(declaredPlatform).equals(COMMON) || namesLoader(declaredPlatform, running);
    }

    /**
     * Whether the addon is written against the API of {@code loader}. A {@link #COMMON} addon runs on
     * every loader and is written against none of them.
     */
    public static boolean namesLoader(String declaredPlatform, RuntimePlatform loader) {
        String platform = normalizePlatform(declaredPlatform);
        return platforms().contains(platform) && Arrays.asList(platform.split(",")).contains(loader.id());
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
