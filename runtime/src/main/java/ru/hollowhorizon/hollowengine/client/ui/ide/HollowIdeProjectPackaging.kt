package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.api.ModList.ModInfo
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEnvironment
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonManager
import ru.hollowhorizon.hollowengine.common.addons.project.HollowModMetadata
import ru.hollowhorizon.hollowengine.common.addons.project.HollowProject
import ru.hollowhorizon.hollowengine.common.addons.project.ProjectException
import ru.hollowhorizon.hollowengine.common.addons.project.ProjectExportOptions
import ru.hollowhorizon.hollowengine.common.addons.project.ProjectExporter
import ru.hollowhorizon.hollowengine.common.addons.project.ProjectImportPlan
import ru.hollowhorizon.hollowengine.common.addons.project.ProjectImporter
import ru.hollowhorizon.hollowengine.common.addons.project.ProjectMessage
import ru.hollowhorizon.hollowengine.common.addons.project.ProjectProperties
import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironmentInitializer
import ru.hollowhorizon.hollowengine.common.scripting.source.DEFAULT_SANDBOX_NAMESPACE
import ru.hollowhorizon.hollowengine.common.utils.DesktopUtil
import ru.hollowhorizon.hollowengine.common.utils.ModList
import java.io.File

/**
 * The project panel's settings, export and import. Everything that touches the disk or waits for the
 * user in a system dialog runs off the render thread; results come back through [Minecraft.execute].
 */
internal class HollowIdeProjectPackaging(
    private val model: HollowIdeModel,
    private val setStatus: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var properties by mutableStateOf(HollowProject.properties())
        private set
    var settings by mutableStateOf<ProjectSettingsForm?>(null)
        private set
    var export by mutableStateOf<ProjectExportForm?>(null)
        private set
    var import by mutableStateOf<ProjectImportForm?>(null)
        private set

    val hasOpenDialog: Boolean get() = settings != null || export != null || import != null

    fun closeDialogs(): Boolean {
        if (!hasOpenDialog || export?.running == true || import?.running == true) return false
        settings = null
        export = null
        import = null
        return true
    }

    fun openSettings() {
        properties = HollowProject.properties()
        val installed = HollowAddonManager.statuses.map { it.descriptor.id }.distinct().sorted()
        settings = ProjectSettingsForm(properties, installed, ModList.getMods())
    }

    fun saveSettings() {
        val form = settings ?: return
        val edited = form.toProperties(properties)
        val problems = edited.problems().map { it.lang } + listOfNotNull(
            HollowProject.enabledAddonClaiming(edited.id)?.takeIf { edited.id != properties.id }
                ?.let { PROJECT_ID_TAKEN.lang(it) },
        )
        form.errors.clear()
        form.errors += problems
        if (problems.isNotEmpty()) return
        val previous = properties
        runCatching { HollowProject.saveProperties(edited) }.onSuccess {
            properties = edited
            settings = null
            setStatus(PROJECT_SAVED.lang)
            if (previous.modDependencies != edited.modDependencies || previous.dependsOn != edited.dependsOn) {
                rebuildScriptingEnvironment()
            }
        }.onFailure { error ->
            HollowEngine.LOGGER.error("Could not save the project settings", error)
            form.errors += error.message ?: error.javaClass.simpleName
        }
    }

    fun cancelSettings() {
        settings = null
    }

    private fun rebuildScriptingEnvironment() {
        val initializer = HollowAddonManager.find<ScriptingEnvironmentInitializer>() ?: return
        setStatus(MODS_REBUILDING.lang)
        scope.launch {
            val result = runCatching { initializer.rebuild() }
            onMain {
                result.onSuccess { setStatus(MODS_REBUILT.lang) }.onFailure { error ->
                    HollowEngine.LOGGER.error("Could not rebuild the scripting environment", error)
                    setStatus(MODS_REBUILD_FAILED.lang(error.message ?: error.javaClass.simpleName))
                }
            }
        }
    }

    fun openExport() {
        properties = HollowProject.properties()
        val form = ProjectExportForm(properties.id, properties.version)
        if (properties.id == DEFAULT_SANDBOX_NAMESPACE) form.errors += EXPORT_DEFAULT_ID.lang(properties.id)
        export = form
    }

    fun chooseExportFile() {
        val form = export ?: return
        val suggested = form.output
        scope.launch {
            suggested.parentFile?.mkdirs()
            val chosen = NativeFileDialogs.saveJar(EXPORT_TITLE.lang, suggested)
            if (chosen != null) onMain { form.chosenOutput = chosen }
        }
    }

    fun runExport() {
        val form = export ?: return
        if (form.running || properties.id == DEFAULT_SANDBOX_NAMESPACE) return
        form.errors.clear()
        val version = form.version.trim()
        val current = HollowProject.properties()
        val versioned = current.copy(version = version)
        if (versioned.problems().isNotEmpty()) {
            form.errors += versioned.problems().map { it.lang }
            return
        }
        if (current.version != version) HollowProject.saveProperties(versioned)
        properties = versioned
        model.saveAll()
        form.running = true
        val options = ProjectExportOptions(form.output, form.includeSources)
        scope.launch {
            val result = runCatching {
                ProjectExporter.export(options) { message ->
                    onMain { form.progress = message.text() }
                }
            }
            onMain {
                form.running = false
                form.progress = ""
                properties = HollowProject.properties()
                result.onSuccess { file ->
                    form.result = file
                    setStatus(EXPORT_DONE.lang(file.name))
                }.onFailure { error -> form.errors += error.userMessages("Project export failed") }
            }
        }
    }

    fun cancelExport() {
        if (export?.running == true) return
        export = null
    }

    fun showExported() {
        export?.result?.let(DesktopUtil::openInExplorer)
    }

    fun startImport() {
        if (import?.running == true) return
        scope.launch {
            val exports = HollowProject.root.resolve(EXPORTS_DIRECTORY).apply { mkdirs() }
            val file = NativeFileDialogs.openJar(IMPORT_TITLE.lang, exports) ?: return@launch
            val plan = runCatching { ProjectImporter.inspect(file) }
            onMain {
                import = plan.fold(
                    onSuccess = { ProjectImportForm(file, it) },
                    onFailure = { error ->
                        ProjectImportForm(file, null).apply { errors += error.userMessages("Cannot import $file") }
                    },
                )
            }
        }
    }

    fun confirmImport() {
        val form = import ?: return
        val plan = form.plan ?: return
        if (form.running) return
        form.running = true
        val projectBefore = HollowProject.properties()
        val modsBefore = projectBefore.modDependencies
        val addonsBefore = projectBefore.dependsOn
        model.discardFilesUnder(HollowProject.contentDirectories)
        scope.launch {
            val result = runCatching { ProjectImporter.apply(plan) }
            onMain {
                form.running = false
                if (result.isSuccess) HollowProject.reloadGame()
                model.refreshExternalFiles()
                properties = HollowProject.properties()
                result.onSuccess { backup ->
                    import = null
                    setStatus(
                        if (backup == null) IMPORT_DONE.lang(plan.properties.displayName)
                        else IMPORT_DONE_BACKUP.lang(plan.properties.displayName, backup.name)
                    )
                    if (properties.modDependencies != modsBefore || properties.dependsOn != addonsBefore) {
                        rebuildScriptingEnvironment()
                    }
                }.onFailure { error -> form.errors += error.userMessages("Project import failed") }
            }
        }
    }

    fun cancelImport() {
        if (import?.running == true) return
        import = null
    }

    private fun Throwable.userMessages(logMessage: String): List<String> {
        if (this is ProjectException) return messages.map { it.text() }
        HollowEngine.LOGGER.error(logMessage, this)
        return listOf(message ?: javaClass.simpleName)
    }

    private fun ProjectMessage.text(): String = key.lang(*args.toTypedArray())

    private fun onMain(block: () -> Unit) = Minecraft.getInstance().execute(block)

    private companion object {
        const val LANG = "hollowengine.gui.ide.project"
        const val PROJECT_SAVED = "$LANG.settings.saved"
        const val PROJECT_ID_TAKEN = "$LANG.problem.id_taken"
        const val MODS_REBUILDING = "$LANG.mods.rebuilding"
        const val MODS_REBUILT = "$LANG.mods.rebuilt"
        const val MODS_REBUILD_FAILED = "$LANG.mods.rebuild_failed"
        const val EXPORT_TITLE = "$LANG.export.title"
        const val EXPORT_DEFAULT_ID = "$LANG.export.default_id"
        const val EXPORT_DONE = "$LANG.export.done"
        const val IMPORT_TITLE = "$LANG.import.title"
        const val IMPORT_DONE = "$LANG.import.done"
        const val IMPORT_DONE_BACKUP = "$LANG.import.done_backup"
    }
}

/** Where exports go unless the user picks another place, inside the `hollowengine` folder. */
private const val EXPORTS_DIRECTORY = "exports"

/** What the settings dialog edits, as text the fields hold until it is saved. */
internal class ProjectSettingsForm(
    properties: ProjectProperties,
    installedAddons: List<String>,
    installedMods: List<ModInfo>,
) {
    var id by mutableStateOf(properties.id)
    var name by mutableStateOf(properties.name)
    var version by mutableStateOf(properties.version)
    var environment by mutableStateOf(properties.environment)
    var description by mutableStateOf(properties.description)
    var authors by mutableStateOf(properties.authors.joinToString(", "))
    var license by mutableStateOf(properties.license)
    var icon by mutableStateOf(properties.icon)
    val dependencies = mutableStateListOf<String>().apply { addAll(properties.dependsOn) }

    val dependencyChoices: List<String> =
        (installedAddons + properties.dependsOn).distinct().filter { it != properties.id }
    val errors = mutableStateListOf<String>()

    val modDependencies = mutableStateListOf<String>().apply { addAll(properties.modDependencies) }
    var modFilter by mutableStateOf("")

    private val modChoices: List<ModInfo> = run {
        val addonModIds = installedAddons.mapTo(HashSet(), HollowModMetadata::modId)
        val installed = installedMods
            .filter { it.id !in PlatformModIds && it.id !in addonModIds }
            .distinctBy(ModInfo::id)
        val missing = properties.modDependencies.filter { id -> installed.none { it.id == id } }
            .map { id -> ModInfo(id, id, "") }
        (missing + installed).sortedWith(
            compareBy<ModInfo> { it.id !in properties.modDependencies }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }
    private val installedModIds = installedMods.mapTo(HashSet(), ModInfo::id)

    val visibleModChoices: List<ModInfo>
        get() {
            val query = modFilter.trim()
            if (query.isEmpty()) return modChoices
            return modChoices.filter { it.id.contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true) }
        }

    fun isInstalled(mod: ModInfo): Boolean = mod.id in installedModIds

    fun toggleDependency(id: String, enabled: Boolean) {
        if (enabled && id !in dependencies) dependencies += id
        if (!enabled) dependencies -= id
    }

    fun toggleModDependency(id: String, enabled: Boolean) {
        if (enabled && id !in modDependencies) modDependencies += id
        if (!enabled) modDependencies -= id
    }

    fun toProperties(base: ProjectProperties) = base.copy(
        id = id.trim(),
        name = name.trim(),
        version = version.trim(),
        environment = environment,
        dependsOn = dependencies.toList(),
        modDependencies = modDependencies.toList(),
        description = description.trim(),
        authors = authors.split(',').map(String::trim).filter(String::isNotEmpty),
        license = license.trim(),
        icon = icon.trim().replace('\\', '/'),
    )

    companion object {
        val environments = HollowAddonEnvironment.entries

        /** Always on the classpath, so offering them as dependencies would only add noise. */
        private val PlatformModIds = setOf("minecraft", "java", "fabricloader", "neoforge", "hollowengine")
    }
}

internal class ProjectExportForm(private val id: String, version: String) {
    var version by mutableStateOf(version)

    var chosenOutput by mutableStateOf<File?>(null)
    val output: File
        get() = chosenOutput ?: HollowProject.root.resolve(EXPORTS_DIRECTORY).resolve("$id-${version.trim()}.jar")
    var includeSources by mutableStateOf(true)
    var running by mutableStateOf(false)
    var progress by mutableStateOf("")

    var result by mutableStateOf<File?>(null)
    val errors = mutableStateListOf<String>()
}

internal class ProjectImportForm(val file: File, val plan: ProjectImportPlan?) {
    var running by mutableStateOf(false)
    val errors = mutableStateListOf<String>()
}
