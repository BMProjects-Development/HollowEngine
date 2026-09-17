package ru.hollowhorizon.hollowengine.common.addons.project

import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.source.DEFAULT_SANDBOX_NAMESPACE
import ru.hollowhorizon.hollowengine.common.scripting.source.NAMESPACE_PATTERN
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.io.File
import java.io.InputStream
import java.util.*

/**
 * `META-INF/plugin.properties` of a project.
 */
data class ProjectProperties(
    val id: String = DEFAULT_SANDBOX_NAMESPACE,
    val name: String = "",
    val version: String = DEFAULT_VERSION,
    val environment: HollowAddonEnvironment = HollowAddonEnvironment.COMMON,
    val dependsOn: List<String> = emptyList(),
    val modDependencies: List<String> = emptyList(),
    val description: String = "",
    val authors: List<String> = emptyList(),
    val license: String = "",
    val icon: String = "",
    val extra: Map<String, String> = emptyMap(),
) {
    val displayName: String get() = name.ifBlank { id }

    fun problems(): List<String> = buildList {
        if (!id.matches(NAMESPACE_PATTERN)) add(PROBLEM_ID)
        if (id == ScriptRegistry.ENGINE_NAMESPACE) add(PROBLEM_ID_RESERVED)
        if (version.isBlank() || version.any(Char::isWhitespace)) add(PROBLEM_VERSION)
        if (id in dependsOn) add(PROBLEM_SELF_DEPENDENCY)
        if (icon.isNotBlank() && !icon.endsWith(".png", ignoreCase = true)) add(PROBLEM_ICON)
    }

    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(encode())
    }

    fun encode(): ByteArray {
        val entries = linkedMapOf(
            "id" to id,
            "name" to name,
            "version" to version,
            "environment" to environment.name.lowercase(),
            "dependsOn" to dependsOn.joinToString(","),
            "dependsOnMods" to modDependencies.joinToString(","),
            "description" to description,
            "authors" to authors.joinToString(", "),
            "license" to license,
            "icon" to icon,
        ).filterValues(String::isNotBlank) + extra.filterKeys { it !in KNOWN_KEYS }
        return entries.entries.joinToString("\n", postfix = "\n") { (key, value) ->
                "${
                    escape(
                        key,
                        true
                    )
                }=${escape(value, false)}"
            }.toByteArray(Charsets.ISO_8859_1)
    }

    companion object {
        const val DEFAULT_VERSION = "1.0.0"
        const val PATH = "META-INF/plugin.properties"

        const val PROBLEM_ID = "hollowengine.gui.ide.project.problem.id"
        const val PROBLEM_ID_RESERVED = "hollowengine.gui.ide.project.problem.id_reserved"
        const val PROBLEM_VERSION = "hollowengine.gui.ide.project.problem.version"
        const val PROBLEM_SELF_DEPENDENCY = "hollowengine.gui.ide.project.problem.self_dependency"
        const val PROBLEM_ICON = "hollowengine.gui.ide.project.problem.icon"

        private val KNOWN_KEYS = setOf(
            "id", "name", "version", "environment", "dependsOn", "dependsOnMods", "description", "authors", "license", "icon",
        )

        /** The properties in [file], or defaults when there is none. */
        fun read(file: File): ProjectProperties =
            if (file.isFile) file.inputStream().use(::read) else ProjectProperties()

        fun read(input: InputStream): ProjectProperties {
            val properties = Properties().apply { load(input) }
            fun value(key: String) = properties.getProperty(key)?.trim().orEmpty()
            fun list(key: String) = value(key).split(',').map(String::trim).filter(String::isNotEmpty)
            return ProjectProperties(
                id = value("id").ifEmpty { DEFAULT_SANDBOX_NAMESPACE },
                name = value("name"),
                version = value("version").ifEmpty { DEFAULT_VERSION },
                environment = HollowAddonEnvironment.entries.firstOrNull { it.name.equals(value("environment"), true) }
                    ?: HollowAddonEnvironment.COMMON,
                dependsOn = list("dependsOn"),
                modDependencies = list("dependsOnMods"),
                description = value("description"),
                authors = list("authors"),
                license = value("license"),
                icon = value("icon"),
                extra = properties.stringPropertyNames().filter { it !in KNOWN_KEYS }
                    .associateWith { properties.getProperty(it) },
            )
        }

        private fun escape(text: String, isKey: Boolean): String = buildString {
            text.forEachIndexed { index, character ->
                when {
                    character == '\\' -> append("\\\\")
                    character == '\n' -> append("\\n")
                    character == '\r' -> append("\\r")
                    character == '\t' -> append("\\t")
                    character == ' ' && (isKey || index == 0) -> append("\\ ")
                    character in "=:#!" && (isKey || index == 0) -> append('\\').append(character)
                    character.code !in 0x20..0x7e -> append("\\u%04x".format(character.code))
                    else -> append(character)
                }
            }
        }
    }
}
