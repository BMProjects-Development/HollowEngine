package ru.hollowhorizon.hollowengine.common.addons.project

import ru.hollowhorizon.hollowengine.common.coroutines.ServerRuntimeState
import ru.hollowhorizon.hollowengine.common.addons.ClientResources
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonManager
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonState
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient
import ru.hollowhorizon.hollowengine.HollowEngine
import java.io.File

/**
 * The `hollowengine` folder seen as the project it is: an addon kept unpacked, which the compiler runs
 * from its sources. Only the folders an addon can carry belong to it; caches, installed addons,
 * worlds and everything else the engine keeps there stay out of exports and imports alike.
 */
object HollowProject {
    val root: File get() = DirectoryManager.HOLLOW_ENGINE.toFile()

    /** Folders that make up the project, relative to [root]. */
    val contentDirectories = listOf("scripts", "assets", "data", "META-INF")

    val propertiesFile: File get() = root.resolve(ProjectProperties.PATH)

    fun properties(): ProjectProperties = ProjectProperties.read(propertiesFile)

    /**
     * An installed addon, not switched off, whose id is [id]. Its scripts would claim the namespace the
     * project wants, so the two cannot be active together.
     */
    fun enabledAddonClaiming(id: String): String? = HollowAddonManager.statuses
        .firstOrNull { it.descriptor.id == id && it.state != HollowAddonState.DISABLED }
        ?.descriptor?.id

    /** Saves [properties] and lets the scripts pick up a new namespace, dependencies or version. */
    fun saveProperties(properties: ProjectProperties) {
        val previous = properties()
        properties.write(propertiesFile)
        val scriptsAffected = previous.id != properties.id || previous.version != properties.version ||
            previous.dependsOn != properties.dependsOn
        if (scriptsAffected) ScriptRegistry.reloadSandbox()
    }

    /** Every regular file under the project folder [directory], with its path relative to [root]. */
    fun files(directory: String): Sequence<Pair<String, File>> {
        val base = root.resolve(directory)
        if (!base.isDirectory) return emptySequence()
        return base.walkTopDown()
            .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter(File::isFile)
            .map { file -> "$directory/${file.relativeTo(base).invariantSeparatorsPath}" to file }
    }

    /** Makes the running game see what is now in the folder: its scripts, assets and data. */
    fun reloadGame() {
        ScriptRegistry.reloadSandbox()
        ServerRuntimeState.servers().forEach { server ->
            server.execute {
                server.packRepository.reload()
                server.reloadResources(server.packRepository.selectedIds).exceptionally { error ->
                    HollowEngine.LOGGER.error("Failed to reload datapacks after the project changed", error)
                    null
                }
            }
        }
        if (isPhysicalClient) ClientResources.reload()
    }
}
