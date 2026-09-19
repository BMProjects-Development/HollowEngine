package ru.hollowhorizon.hollowengine.common.scripting.deobf

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonManager
import ru.hollowhorizon.hollowengine.common.addons.project.HollowProject
import ru.hollowhorizon.hollowengine.common.config.HollowEngineConfig
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.remapJars
import ru.hollowhorizon.hollowengine.common.utils.ModList
import ru.hollowhorizon.hollowengine.common.utils.RuntimeFlags
import java.io.File

/**
 * Mods whose classes scripts compile against: `dependsOnMods` of the project and of every enabled addon,
 * plus the `scripting_mods` config. All of them share one classpath, so a mod requested anywhere is
 * visible to every script.
 */
object ScriptingMods {
    fun requested(): List<String> = buildList {
        if (RuntimeFlags.production) addAll(HollowEngineConfig.scriptingMods)
        addAll(HollowProject.properties().modDependencies)
        HollowAddonManager.enabled.forEach { addon -> addAll(addon.descriptor.modDependencies) }
    }.distinct()
}

/**
 * Addons the project's scripts are compiled against, named by `dependsOn` and followed through the
 * dependencies those addons declare themselves.
 */
object ScriptingAddons {
    fun classpath(): List<File> {
        val installed = HollowAddonManager.enabled.associateBy { it.descriptor.id }
        val wanted = LinkedHashSet<String>()

        fun visit(id: String) {
            if (!wanted.add(id)) return
            installed[id]?.descriptor?.dependencies?.forEach(::visit)
        }

        HollowProject.properties().dependsOn.forEach(::visit)
        return wanted.mapNotNull { id -> installed[id]?.classes?.takeIf(File::isFile) }
    }
}

class ModsEnvironment(private val modIds: List<String>) : EnvironmentSetup {
    var sources: List<File> = emptyList()
        private set

    override fun setup(mappings: Mappings, outputDir: File): List<File> {
        val files = modIds.mapNotNull(::fileOf).distinctBy { it.absoluteFile.normalize() }
        sources = files
        if (!RuntimeFlags.production || NeoForgeEnvironmentSetup.isAvailable()) return files
        val (directories, jars) = files.partition(File::isDirectory)
        return directories + remapJars(mappings, jars, outputDir, from = "intermediary", to = "named")
    }

    private fun fileOf(modId: String): File? {
        if (!ModList.isLoaded(modId)) {
            HollowEngine.LOGGER.warn("Scripts depend on mod '{}', which is not installed", modId)
            return null
        }
        return runCatching { ModList.getFile(modId) }.onFailure {
                HollowEngine.LOGGER.error(
                    "Cannot find the file of mod '{}' for scripts",
                    modId,
                    it
                )
            }.getOrNull()
    }
}
