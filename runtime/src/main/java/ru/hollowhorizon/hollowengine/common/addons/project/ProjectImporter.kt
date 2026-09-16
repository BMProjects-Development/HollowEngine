package ru.hollowhorizon.hollowengine.common.addons.project

import ru.hollowhorizon.hollowengine.common.addons.HollowAddonLayout
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonManager
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What importing [file] would do, worked out before anything is touched so the user can confirm it.
 * [conflictingAddon] is an installed addon with the project's id, which would claim the same namespace
 * and has to be switched off.
 */
class ProjectImportPlan(
    val file: File,
    val properties: ProjectProperties,
    val conflictingAddon: String?,
)

/**
 * Turns an addon exported with its sources back into the project in the `hollowengine` folder,
 * replacing the one that is there. The replaced files are zipped into `hollowengine/backups` first.
 * The game sees the new project once [HollowProject.reloadGame] is called.
 */
object ProjectImporter {
    private val backupTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun inspect(file: File): ProjectImportPlan {
        val (properties, names) = runCatching {
            JarFile(file).use { jar ->
                val descriptor = jar.getJarEntry(ProjectProperties.PATH) ?: return@use null
                jar.getInputStream(descriptor).use(ProjectProperties::read) to jar.entries().asSequence()
                    .filterNot { it.isDirectory }.map { it.name }.toList()
            }
        }.getOrNull() ?: throw ProjectException(ProjectMessage(ProjectLang.IMPORT_NOT_ADDON, file.name))

        if ("entry" in properties.extra || HollowAddonLayout.CLASSES_JAR in names) {
            throw ProjectException(ProjectMessage(ProjectLang.IMPORT_HAS_CLASSES, file.name))
        }
        val sources = names.filter { it.startsWith(HollowAddonLayout.SOURCE_PREFIX) }
            .mapTo(HashSet()) { it.removePrefix(HollowAddonLayout.SOURCE_PREFIX) }
        val compiledOnly =
            names.filter { it.startsWith(HollowAddonLayout.COMPILED_PREFIX) && it.endsWith(COMPILED_SUFFIX) }
                .map { it.removePrefix(HollowAddonLayout.COMPILED_PREFIX).removeSuffix(ScriptCache.ARTIFACT_SUFFIX) }
                .filter { it !in sources }
        if (compiledOnly.isNotEmpty()) {
            throw ProjectException(ProjectMessage(ProjectLang.IMPORT_NO_SOURCES, file.name, compiledOnly.first()))
        }
        val problems = properties.problems()
        if (problems.isNotEmpty()) throw ProjectException(problems.map(::ProjectMessage))
        if (names.any { importedPath(it, properties) != null && !isInsideProject(it) }) {
            throw ProjectException(ProjectMessage(ProjectLang.IMPORT_UNSAFE_PATH, file.name))
        }

        return ProjectImportPlan(file, properties, HollowProject.enabledAddonClaiming(properties.id))
    }

    /** Replaces project with [plan]'s. Returns backup of old one, if it had any files. */
    fun apply(plan: ProjectImportPlan): File? {
        plan.conflictingAddon?.let(HollowAddonManager::disable)
        val backup = backUp(HollowProject.properties().id)
        HollowProject.contentDirectories.forEach { HollowProject.root.resolve(it).deleteRecursively() }

        JarFile(plan.file).use { jar ->
            jar.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val path = importedPath(entry.name, plan.properties) ?: return@forEach
                val target = HollowProject.root.resolve(path)
                target.parentFile?.mkdirs()
                jar.getInputStream(entry).use { input ->
                    Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return backup
    }

    /** Where [name] from an addon jar lands in the project, or `null` when it is not part of one. */
    private fun importedPath(name: String, properties: ProjectProperties): String? = when {
        name == ProjectProperties.PATH -> name
        name.startsWith("META-INF/") -> null
        HollowProject.contentDirectories.any { name.startsWith("$it/") } -> name
        properties.icon.isNotBlank() && name == properties.icon.replace('\\', '/').trimStart('/') -> name
        else -> null
    }

    private fun isInsideProject(name: String): Boolean {
        val root = HollowProject.root.toPath().toAbsolutePath().normalize()
        return root.resolve(name).normalize().startsWith(root)
    }

    private fun backUp(id: String): File? {
        val files = HollowProject.contentDirectories.flatMap { HollowProject.files(it).toList() }
        if (files.isEmpty()) return null
        val target = HollowProject.root.resolve("backups").resolve("$id-${LocalDateTime.now().format(backupTime)}.zip")
        target.parentFile.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            files.forEach { (path, file) ->
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return target
    }

    private const val COMPILED_SUFFIX = ".kts" + ScriptCache.ARTIFACT_SUFFIX
}
