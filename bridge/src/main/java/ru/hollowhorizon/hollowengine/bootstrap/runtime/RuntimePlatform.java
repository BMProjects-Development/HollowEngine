package ru.hollowhorizon.hollowengine.bootstrap.runtime;

/**
 * The mod loaders the engine runs on. This is the only list of them: a descriptor names loaders by
 * {@link #id()}, and a new loader becomes available to addons by being added here.
 */
public enum RuntimePlatform {
    FABRIC("fabric", "Fabric"),
    NEOFORGE("neoforge", "NeoForge");

    private final String id;
    private final String displayName;

    RuntimePlatform(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    /** The name the loader goes by, as in {@code NeoForge}. */
    public String displayName() {
        return displayName;
    }
}
