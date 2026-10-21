package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.client.utils.IconHelper
import ru.hollowhorizon.hollowengine.common.scripting.source.SandboxScriptSource
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry

/**
 * A kind of script the project tree can create, with its starter text. The text may use `{{name}}` (the
 * file name without its extension), `{{id}}` (the same, made a valid resource path), `{{constant}}` (the id
 * as a Kotlin constant name), `{{namespace}}` (the sandbox namespace) and `{{path}}` (new file's project path).
 *
 * @param label shown in the "New script" menu and in the title of the name dialog.
 * @param extension appended to the typed name when it lacks it, such as `.node.kts`.
 * @param text reads the starter text; called each time a file is created.
 */
class HollowIdeScriptTemplate(
    val id: String,
    val label: String,
    val extension: String,
    private val defaultName: String,
    private val text: () -> String,
) {
    init {
        require(id.isNotBlank()) { "Script template ID cannot be blank" }
        require(extension.startsWith('.')) { "Script template extension must start with a dot: $extension" }
    }

    val suggestedFileName: String get() = defaultName + extension

    /** The icon the project tree gives files of this kind. */
    val icon: String get() = IconHelper.forFile(suggestedFileName).toString()

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
        return text()
            .replace("{{name}}", name)
            .replace("{{id}}", id)
            .replace("{{constant}}", constant)
            .replace("{{namespace}}", ScriptRegistry.sandboxNamespace)
            .replace("{{path}}", path)
    }

    companion object {
        private const val KOTLIN_SCRIPT = ".kts"
        private val INVALID_ID_CHARS = Regex("[^a-z0-9_.-]")
        private val INVALID_CONSTANT_CHARS = Regex("[^A-Z0-9_]")

        /** A template whose text is the `internal/templates/<resource>.template` file of [owner]'s jar. */
        fun fromResource(
            id: String,
            label: String,
            extension: String,
            defaultName: String,
            resource: String,
            owner: Class<*>,
        ) = HollowIdeScriptTemplate(id, label, extension, defaultName) {
            owner.classLoader.getResourceAsStream("internal/templates/$resource.template")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                .orEmpty()
        }
    }
}

private fun builtIn(id: String, label: String, extension: String, defaultName: String, resource: String) =
    HollowIdeScriptTemplate.fromResource(id, label, extension, defaultName, resource, HollowIdeScriptTemplate::class.java)

/** The engine's own templates; addons add theirs through [HollowIdeExtensionPoints.SCRIPT_TEMPLATES]. */
private val BuiltInScriptTemplates = listOf(
    builtIn("plain", "Script", ".kts", "script", "plain.kts"),
    builtIn("server_reload", "Server Reload Script", ".reload.kts", "server", "server.reload.kts"),
    builtIn("client_reload", "Client Reload Script", ".reload.kts", "client", "client.reload.kts"),
    builtIn("startup", "Startup Script", ".startup.kts", "ruby", "startup.kts"),
    builtIn("node", "Node Script", ".node.kts", "node", "node.kts"),
    builtIn("ui", "UI Script", ".ui.kts", "menu", "ui.kts"),
    builtIn("mixin", "Mixin Script", ".mixin.kts", "mixins", "mixin.kts"),
)

/** What the "New script" menu offers: the engine's templates, then those of loaded addons. */
internal fun scriptTemplates(): List<HollowIdeScriptTemplate> =
    BuiltInScriptTemplates + HollowIdeExtensionPoints.SCRIPT_TEMPLATES.extensions

/** Scripts are only picked up from the sandbox's `scripts` folder, so that is the only place to offer them. */
internal fun isInsideScripts(path: String): Boolean =
    path == SandboxScriptSource.SCRIPTS_DIRECTORY || path.startsWith(SandboxScriptSource.SCRIPTS_DIRECTORY + "/")
