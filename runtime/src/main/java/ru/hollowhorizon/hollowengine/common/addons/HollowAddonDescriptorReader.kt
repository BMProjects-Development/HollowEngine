package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonBootstrapContract
import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonTargets
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform
import ru.hollowhorizon.hollowengine.common.scripting.source.DEFAULT_SANDBOX_NAMESPACE
import java.io.File
import java.io.InputStream
import java.util.*
import java.util.jar.JarFile

internal object HollowAddonDescriptorReader {
    private val DESCRIPTOR_PATH = AddonBootstrapContract.DESCRIPTOR_PATH
    private val addonIdPattern = Regex("[a-z0-9_.-]+")

    /** Keys that may be given for one loader only, as `entry.fabric` or `dependsOnMods.neoforge`. */
    private val PLATFORM_KEYS = listOf("entry", "dependsOn", "dependsOnMods", "requiredClasses")

    fun read(
        file: File,
        descriptorPath: String = DESCRIPTOR_PATH,
        platform: RuntimePlatform = HollowAddonRuntimeEnvironment.platform,
    ): HollowAddonDescriptor = JarFile(file).use { jar ->
        val entry = jar.getJarEntry(descriptorPath)
            ?: throw IllegalArgumentException("Missing $descriptorPath")
        jar.getInputStream(entry).use { input -> read(input, platform, descriptorPath) }
    }

    fun read(
        input: InputStream,
        platform: RuntimePlatform,
        descriptorPath: String = DESCRIPTOR_PATH,
    ): HollowAddonDescriptor {
        val properties = Properties().apply { load(input) }
        val id = properties.getProperty("id")?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Missing 'id' in $descriptorPath")
        val declaredPlatform = AddonTargets.normalizePlatform(properties.getProperty(AddonTargets.PLATFORM_KEY))
        val addonPlatform = HollowAddonPlatform.of(declaredPlatform) ?: throw IllegalArgumentException(
            "Addon '$id' has unknown platform '$declaredPlatform', use ${AddonTargets.platformHint()}"
        )
        properties.requireKnownPlatformKeys(id, addonPlatform)
        return HollowAddonDescriptor(
            id = id,
            version = properties.getProperty("version", "1.0.0").trim(),
            entrypoint = properties.text(AddonTargets.platformKey("entry", platform)) ?: properties.text("entry"),
            addonRequirements = properties.dependencies(id, "dependsOn", platform),
            modRequirements = properties.dependencies(id, "dependsOnMods", platform),
            name = properties.getProperty("name", id).trim(),
            environment = properties.choice(id, AddonTargets.ENVIRONMENT_KEY, HollowAddonEnvironment.entries) {
                it.name.lowercase()
            },
            requiredClasses = properties.list("requiredClasses", platform),
            hostModId = properties.text("hostModId"),
            platform = addonPlatform,
        ).also(::validate)
    }

    private fun validate(descriptor: HollowAddonDescriptor) {
        require(descriptor.id.matches(addonIdPattern)) { "Invalid addon id '${descriptor.id}'" }
        require(descriptor.id != DEFAULT_SANDBOX_NAMESPACE) {
            "Addon id '$DEFAULT_SANDBOX_NAMESPACE' is reserved for the hollowengine directory"
        }
        require(descriptor.version.isNotBlank()) { "Addon '${descriptor.id}' has an empty version" }
        require(descriptor.id !in descriptor.dependencies) { "Addon '${descriptor.id}' cannot depend on itself" }
        descriptor.addonRequirements.firstOrNull(HollowAddonDependency::optional)?.let { dependency ->
            throw IllegalArgumentException(
                "Addon '${descriptor.id}' has '$dependency' in dependsOn: only dependsOnMods can be optional"
            )
        }
        require(descriptor.hostModId == null || descriptor.hostModId.matches(addonIdPattern)) {
            "Addon '${descriptor.id}' has an invalid hostModId"
        }
    }

    /**
     * A key written for a loader that does not exist, or for one the addon is not written against, would
     * be ignored without a word, and the addon would lose its entrypoint or a dependency there.
     */
    private fun Properties.requireKnownPlatformKeys(id: String, addonPlatform: HollowAddonPlatform) {
        stringPropertyNames().forEach { key ->
            val base = PLATFORM_KEYS.firstOrNull { key.startsWith("$it.") } ?: return@forEach
            val suffix = key.removePrefix("$base.")
            val target = RuntimePlatform.entries.firstOrNull { it.id() == suffix }
            requireNotNull(target) {
                "Addon '$id' has '$key': unknown loader '$suffix', use ${RuntimePlatform.entries.joinToString(" or ") { it.id() }}"
            }
            require(addonPlatform.usesLoaderApi) {
                "Addon '$id' has '$key', but a common addon knows no loader; " +
                    "name the loaders, as in platform=${AddonTargets.platformId(RuntimePlatform.entries)}"
            }
            require(addonPlatform.names(target)) {
                "Addon '$id' has '$key', but its platform is ${addonPlatform.id}"
            }
        }
    }

    private fun <T> Properties.choice(id: String, key: String, values: List<T>, name: (T) -> String): T {
        val declared = AddonTargets.normalize(getProperty(key))
        return values.firstOrNull { name(it) == declared } ?: throw IllegalArgumentException(
            "Addon '$id' has unknown $key '$declared', use ${values.joinToString(", ", transform = name)}"
        )
    }

    private fun Properties.text(name: String): String? = getProperty(name)?.trim()?.takeIf(String::isNotEmpty)

    /** Values of [name] together with those given for [platform] only. */
    private fun Properties.list(name: String, platform: RuntimePlatform): List<String> =
        (values(name) + values(AddonTargets.platformKey(name, platform))).distinct()

    /**
     * Dependencies of [name] together with those given for [platform] only. What a loader says about an
     * id replaces what is said for every loader, so one of them can ask for another version.
     */
    private fun Properties.dependencies(id: String, name: String, platform: RuntimePlatform): List<HollowAddonDependency> {
        val byId = LinkedHashMap<String, HollowAddonDependency>()
        listOf(name, AddonTargets.platformKey(name, platform)).forEach { key ->
            values(key).forEach { spec ->
                val dependency = runCatching { HollowAddonDependency.parse(spec) }.getOrElse { error ->
                    throw IllegalArgumentException("Addon '$id' has a malformed $key: ${error.message}")
                }
                byId[dependency.id] = dependency
            }
        }
        return byId.values.toList()
    }

    private fun Properties.values(name: String): List<String> = getProperty(name, "")
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
}
