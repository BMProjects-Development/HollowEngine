package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonTargets
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform

/**
 * What an addon declares about itself, already resolved for the running loader: [entrypoint] and the
 * lists include whatever the descriptor gives for this loader only.
 */
data class HollowAddonDescriptor(
    val id: String,
    val version: String,
    val entrypoint: String?,
    /** Addons that must be running, in a version that fits, before this one starts. */
    val addonRequirements: List<HollowAddonDependency> = emptyList(),
    /**
     * Mods the addon works with. One that is not optional must be installed for the addon to load; every
     * installed one is on the classpath of its scripts.
     */
    val modRequirements: List<HollowAddonDependency> = emptyList(),
    val name: String = id,
    val environment: HollowAddonEnvironment = HollowAddonEnvironment.COMMON,
    val requiredClasses: List<String> = emptyList(),
    val mappingNamespace: HollowAddonMappingNamespace = HollowAddonMappingNamespace.AGNOSTIC,
    val hostModId: String? = null,
    val platform: HollowAddonPlatform = HollowAddonPlatform.COMMON,
) {
    /** Ids of [addonRequirements]. */
    val dependencies: List<String> get() = addonRequirements.ids

    /** Ids of [modRequirements], the optional ones included. */
    val modDependencies: List<String> get() = modRequirements.ids
}

enum class HollowAddonEnvironment {
    COMMON,
    CLIENT,
    SERVER;

    fun supports(isClient: Boolean): Boolean = AddonTargets.runsIn(name, isClient)
}

/**
 * The mod loaders an addon is written against. A [COMMON] addon knows no loader: it uses only Minecraft
 * and the engine, and so runs on every loader from one jar. The others name the loaders whose API the
 * addon uses and run only there; [FABRIC_AND_NEOFORGE] carries code for both.
 */
enum class HollowAddonPlatform(val id: String) {
    COMMON(AddonTargets.COMMON),
    FABRIC(RuntimePlatform.FABRIC.id()),
    NEOFORGE(RuntimePlatform.NEOFORGE.id()),
    FABRIC_AND_NEOFORGE(RuntimePlatform.FABRIC.id() + "," + RuntimePlatform.NEOFORGE.id());

    /** Whether an addon made for this platform runs on [platform]. */
    fun supports(platform: RuntimePlatform): Boolean = AddonTargets.runsOn(id, platform)

    /** Whether the addon is written against the API of [platform], which a [COMMON] one never is. */
    fun names(platform: RuntimePlatform): Boolean = AddonTargets.namesLoader(id, platform)

    /** Whether the addon may use the API of a mod loader at all. */
    val usesLoaderApi: Boolean get() = this != COMMON

    companion object {
        /** The platform [declared] names, however it is spelled, or null when it names none. */
        fun of(declared: String?): HollowAddonPlatform? {
            val id = AddonTargets.normalizePlatform(declared)
            return entries.firstOrNull { it.id == id }
        }
    }
}

enum class HollowAddonMappingNamespace(val id: String) {
    AGNOSTIC("agnostic"),
    OFFICIAL("official"),
    INTERMEDIARY("intermediary"),
    NAMED("named"),
}
