package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.client.utils.IconHelper.Icons
import ru.hollowhorizon.hollowengine.common.scripting.source.SandboxScriptSource
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry

/**
 * The kinds of script the project tree can create, each with a starter file taken from
 * `internal/templates`. A template may use `{{name}}` (the file name without its extension), `{{id}}` (the
 * same, made a valid resource path), `{{constant}}` (the id as a Kotlin constant name), `{{namespace}}` (the
 * sandbox namespace) and `{{path}}` (new file's project path).
 */
internal enum class ScriptTemplate(
    val label: String,
    val extension: String,
    private val defaultName: String,
    private val resource: String,
    val icon: String,
) {
    Plain("Script", ".kts", "script", "plain.kts", Icons.FILE_KTS.toString()),
    ServerReload("Server Reload Script", ".reload.kts", "server", "server.reload.kts", Icons.FILE_KTS.toString()),
    ClientReload("Client Reload Script", ".reload.kts", "client", "client.reload.kts", Icons.FILE_KTS.toString()),
    Startup("Startup Script", ".startup.kts", "ruby", "startup.kts", Icons.FILE_KTS.toString()),
    Node("Node Script", ".node.kts", "node", "node.kts", Icons.FILE_KTS.toString()),
    Ui("UI Script", ".ui.kts", "menu", "ui.kts", Icons.FILE_KTS.toString()),
    Mixin("Mixin Script", ".mixin.kts", "mixins", "mixin.kts", Icons.FILE_KTS.toString()),
    Dialogue("Dialogue", ".story", "dialogue", "dialogue.story", Icons.DIALOGUE.toString());

    val suggestedFileName: String get() = defaultName + extension

    /** [input] as typed in the name dialog, completed with this template's extension when it lacks it. */
    fun fileName(input: String): String {
        val name = input.trim()
        if (name.endsWith(extension)) return name
        val base = if (extension.endsWith(KOTLIN_SCRIPT)) name.removeSuffix(KOTLIN_SCRIPT) else name
        return base.trimEnd('.') + extension
    }

    fun render(path: String): String {
        val name = path.substringAfterLast('/').removeSuffix(extension)
        val id = name.lowercase().replace(INVALID_ID_CHARS, "_").ifEmpty { "script" }
        val constant = id.uppercase().replace(INVALID_CONSTANT_CHARS, "_").let { if (it.first().isDigit()) "_$it" else it }
        val text = ScriptTemplate::class.java.classLoader
            .getResourceAsStream("internal/templates/$resource.template")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            .orEmpty()
        return text
            .replace("{{name}}", name)
            .replace("{{id}}", id)
            .replace("{{constant}}", constant)
            .replace("{{namespace}}", ScriptRegistry.sandboxNamespace)
            .replace("{{path}}", path)
    }

    private companion object {
        const val KOTLIN_SCRIPT = ".kts"
        val INVALID_ID_CHARS = Regex("[^a-z0-9_.-]")
        val INVALID_CONSTANT_CHARS = Regex("[^A-Z0-9_]")
    }
}

/** Scripts are only picked up from the sandbox's `scripts` folder, so that is the only place to offer them. */
internal fun isInsideScripts(path: String): Boolean =
    path == SandboxScriptSource.SCRIPTS_DIRECTORY || path.startsWith(SandboxScriptSource.SCRIPTS_DIRECTORY + "/")
