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
 * The mod loaders an addon is written against: any set of [RuntimePlatform]. With none it is [COMMON]:
 * it uses only Minecraft and the engine, and so runs on every loader from one jar. Otherwise it uses the
 * API of exactly [loaders] and runs only there, carrying the code of each one in a part of its own.
 */
class HollowAddonPlatform private constructor(loaders: Collection<RuntimePlatform>) {
    /** The loaders in the order of [RuntimePlatform]; empty for [COMMON]. */
    val loaders: List<RuntimePlatform> = RuntimePlatform.entries.filter(loaders::contains)

    /** The value of `platform` that names this platform, as in `common` or `fabric,neoforge`. */
    val id: String = AddonTargets.platformId(this.loaders)

    /** Whether the addon may use the API of a mod loader at all. */
    val usesLoaderApi: Boolean get() = loaders.isNotEmpty()

    /** The loaders the addon runs on: those it names, or every one for [COMMON]. */
    val targets: List<RuntimePlatform> get() = loaders.ifEmpty { RuntimePlatform.entries }

    /** Whether an addon made for this platform runs on [platform]. */
    fun supports(platform: RuntimePlatform): Boolean = platform in targets

    /** Whether the addon is written against the API of [platform], which a [COMMON] one never is. */
    fun names(platform: RuntimePlatform): Boolean = platform in loaders

    /** This platform with [platform] added to or taken out of its loaders. */
    fun with(platform: RuntimePlatform, included: Boolean): HollowAddonPlatform =
        of(if (included) loaders + platform else loaders - platform)

    override fun equals(other: Any?): Boolean = other is HollowAddonPlatform && other.loaders == loaders

    override fun hashCode(): Int = loaders.hashCode()

    override fun toString(): String = id

    companion object {
        /** An addon that knows no mod loader. */
        @JvmField
        val COMMON = HollowAddonPlatform(emptyList())

        fun of(loaders: Collection<RuntimePlatform>): HollowAddonPlatform =
            if (loaders.isEmpty()) COMMON else HollowAddonPlatform(loaders)

        fun of(vararg loaders: RuntimePlatform): HollowAddonPlatform = of(loaders.toList())

        /** The platform [declared] names, however it is spelled, or null when a part of it names no loader. */
        fun of(declared: String?): HollowAddonPlatform? = AddonTargets.loadersOf(declared)?.let { of(it) }
    }
}

enum class HollowAddonMappingNamespace(val id: String) {
    AGNOSTIC("agnostic"),
    OFFICIAL("official"),
    INTERMEDIARY("intermediary"),
    NAMED("named"),
}
