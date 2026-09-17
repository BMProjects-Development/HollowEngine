package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonBootstrapContract
import ru.hollowhorizon.hollowengine.common.scripting.source.DEFAULT_SANDBOX_NAMESPACE
import java.io.File
import java.util.Properties
import java.util.jar.JarFile

internal object HollowAddonDescriptorReader {
    private val DESCRIPTOR_PATH = AddonBootstrapContract.DESCRIPTOR_PATH
    private val addonIdPattern = Regex("[a-z0-9_.-]+")

    fun read(file: File): HollowAddonDescriptor = JarFile(file).use { jar ->
        val entry = jar.getJarEntry(DESCRIPTOR_PATH)
            ?: throw IllegalArgumentException("Missing $DESCRIPTOR_PATH")
        val properties = Properties().apply {
            jar.getInputStream(entry).use(::load)
        }
        val id = properties.required("id")
        HollowAddonDescriptor(
            id = id,
            version = properties.getProperty("version", "1.0.0").trim(),
            entrypoint = properties.getProperty("entry")?.trim()?.takeIf(String::isNotEmpty),
            dependencies = properties.list("dependsOn"),
            modDependencies = properties.list("dependsOnMods"),
            name = properties.getProperty("name", id).trim(),
            environment = properties.getProperty("environment", "common")
                .trim()
                .uppercase()
                .let(HollowAddonEnvironment::valueOf),
            requiredClasses = properties.list("requiredClasses"),
        ).also(::validate)
    }

    private fun validate(descriptor: HollowAddonDescriptor) {
        require(descriptor.id.matches(addonIdPattern)) { "Invalid addon id '${descriptor.id}'" }
        require(descriptor.id != DEFAULT_SANDBOX_NAMESPACE) {
            "Addon id '$DEFAULT_SANDBOX_NAMESPACE' is reserved for the hollowengine directory"
        }
        require(descriptor.version.isNotBlank()) { "Addon '${descriptor.id}' has an empty version" }
        require(descriptor.id !in descriptor.dependencies) { "Addon '${descriptor.id}' cannot depend on itself" }
        require(descriptor.dependencies.distinct().size == descriptor.dependencies.size) {
            "Addon '${descriptor.id}' declares duplicate dependencies"
        }
    }

    private fun Properties.required(name: String): String = getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: throw IllegalArgumentException("Missing '$name' in $DESCRIPTOR_PATH")

    private fun Properties.list(name: String): List<String> = getProperty(name, "")
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
}
