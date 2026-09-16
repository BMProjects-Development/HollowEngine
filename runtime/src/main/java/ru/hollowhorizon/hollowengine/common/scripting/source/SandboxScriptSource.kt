package ru.hollowhorizon.hollowengine.common.scripting.source

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.addons.project.ProjectProperties
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import java.io.File

/**
 * The `hollowengine` directory treated as an unpacked addon: scripts live in `hollowengine/scripts`
 * and an optional `hollowengine/META-INF/plugin.properties` names the namespace under which they are
 * published to other namespaces. Unqualified script paths always land here regardless of that name, so
 * existing save data and command history keep working.
 */
class SandboxScriptSource(
    root: File = DirectoryManager.HOLLOW_ENGINE.toFile(),
    descriptor: SandboxDescriptor = readDescriptor(root.resolve(DESCRIPTOR_PATH)),
) : DirectoryScriptSource(
    namespace = descriptor.id,
    directory = root.resolve(SCRIPTS_DIRECTORY),
    classLoader = HollowEngine::class.java.classLoader,
    dependencies = descriptor.dependencies,
    fingerprint = descriptor.version,
) {
    init {
        directory.mkdirs()
    }

    val scriptsDirectory: File get() = directory

    data class SandboxDescriptor(val id: String, val dependencies: List<String>, val version: String)

    companion object {
        const val DESCRIPTOR_PATH = ProjectProperties.PATH
        const val SCRIPTS_DIRECTORY = "scripts"

        fun readDescriptor(file: File): SandboxDescriptor {
            val fallback = SandboxDescriptor(DEFAULT_SANDBOX_NAMESPACE, emptyList(), ProjectProperties.DEFAULT_VERSION)
            val properties = runCatching { ProjectProperties.read(file) }.onFailure {
                HollowEngine.LOGGER.error("Failed to read {}; falling back to the default namespace", file, it)
            }.getOrNull() ?: return fallback

            if (!properties.id.matches(NAMESPACE_PATTERN)) {
                HollowEngine.LOGGER.error(
                    "Invalid namespace '{}' in {}; falling back to '{}'",
                    properties.id,
                    file,
                    DEFAULT_SANDBOX_NAMESPACE,
                )
                return fallback.copy(version = properties.version)
            }
            return SandboxDescriptor(properties.id, properties.dependsOn, properties.version)
        }
    }
}

/** Namespace of `hollowengine/scripts` when it does not declare one of its own. */
const val DEFAULT_SANDBOX_NAMESPACE = "hollowengine-sandbox"

internal val NAMESPACE_PATTERN = Regex("[a-z0-9_.-]+")
