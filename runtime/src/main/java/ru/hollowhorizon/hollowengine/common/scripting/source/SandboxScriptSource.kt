package ru.hollowhorizon.hollowengine.common.scripting.source

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import java.io.File
import java.util.Properties

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
    fingerprint = "sandbox",
) {
    init {
        directory.mkdirs()
    }

    val scriptsDirectory: File get() = directory

    data class SandboxDescriptor(val id: String, val dependencies: List<String>)

    companion object {
        const val DESCRIPTOR_PATH = "META-INF/plugin.properties"
        const val SCRIPTS_DIRECTORY = "scripts"

        fun readDescriptor(file: File): SandboxDescriptor {
            val fallback = SandboxDescriptor(DEFAULT_SANDBOX_NAMESPACE, emptyList())
            if (!file.isFile) return fallback
            val properties = runCatching {
                Properties().apply { file.inputStream().use(::load) }
            }.onFailure {
                HollowEngine.LOGGER.error("Failed to read {}; falling back to the default namespace", file, it)
            }.getOrNull() ?: return fallback

            val id = properties.getProperty("id")?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_SANDBOX_NAMESPACE
            if (!id.matches(NAMESPACE_PATTERN)) {
                HollowEngine.LOGGER.error(
                    "Invalid namespace '{}' in {}; falling back to '{}'",
                    id,
                    file,
                    DEFAULT_SANDBOX_NAMESPACE,
                )
                return fallback
            }
            val dependencies = properties.getProperty("dependsOn", "")
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
            return SandboxDescriptor(id, dependencies)
        }
    }
}

/** Namespace of `hollowengine/scripts` when it does not declare one of its own. */
const val DEFAULT_SANDBOX_NAMESPACE = "hollowengine-sandbox"

internal val NAMESPACE_PATTERN = Regex("[a-z0-9_.-]+")
