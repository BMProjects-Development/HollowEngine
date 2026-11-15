package ru.hollowhorizon.hollowengine.common.scripting.ide

import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.utils.HollowJavaUtils
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves `namespace:path` strings to something the IDE can open.
 *
 * A location that maps onto a file under `hollowengine/assets` is the project's own and
 * opens for editing; anything else is served by the game's resource packs and opens
 * read-only, so a mod's stylesheet can be read without pretending it can be changed.
 *
 * Lookups are asked on every analysis pass, once per literal, so what a location resolves
 * to is cached for a moment instead of hitting the disk each time.
 */
object ResourceLocationTargets {
    private const val CacheNanos = 1_000_000_000L
    private val LocationPattern = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
    private val TextExtensions = listOf(".hss", ".json", ".kts", ".kt", ".txt", ".mcmeta", ".fsh", ".vsh", ".svg")

    private val targets = ConcurrentHashMap<String, CachedTarget>()

    /** Whether [value] is shaped like a resource location; cheap enough for every literal. */
    fun looksLikeLocation(value: String): Boolean = LocationPattern.matches(value.trim())

    /** Where [location] resolves to, or `null` when nothing provides it. */
    fun targetOf(location: String, project: ScriptAnalysisProject? = null): ResourceTarget? {
        if (project != null) {
            val path = projectTextPath(location) ?: return null
            return try {
                ResourceTarget.PROJECT.takeIf { project.readForHint(path) != null }
            } catch (_: IOException) {
                null
            }
        }
        return when (target(location)) {
            Target.PROJECT -> ResourceTarget.PROJECT
            Target.RESOURCE -> ResourceTarget.RESOURCE
            Target.MISSING -> null
        }
    }

    /** Where the IDE should open [location], or `null` when nothing provides it. */
    fun definition(location: String, project: ScriptAnalysisProject? = null): DefinitionLocation? {
        val cleaned = location.trim()
        val target = if (project == null) target(cleaned) else {
            val path = projectTextPath(cleaned) ?: return null
            if (project.read(path) != null) Target.PROJECT else Target.MISSING
        }
        return when (target) {
            Target.PROJECT -> projectPath(cleaned)?.let { DefinitionLocation(it, offset = 0) }
            Target.RESOURCE -> readGameResource(cleaned)?.let { text ->
                DefinitionLocation(
                    path = "resources/${cleaned.replace(':', '/')}",
                    offset = 0,
                    text = text,
                    readOnly = true,
                )
            }

            Target.MISSING -> null
        }
    }

    private fun target(location: String): Target {
        val cleaned = location.trim()
        if (!looksLikeLocation(cleaned)) return Target.MISSING
        val now = System.nanoTime()
        targets[cleaned]?.takeIf { now - it.checkedAtNanos <= CacheNanos }?.let { return it.target }
        val path = projectPath(cleaned)?.let(DirectoryManager.HOLLOW_ENGINE::resolve)
        val target = if (path != null && Files.isRegularFile(path)) Target.PROJECT else gameTarget(cleaned)
        targets[cleaned] = CachedTarget(target, now)
        return target
    }

    private fun gameTarget(location: String): Target {
        val parsed = parse(location) ?: return Target.MISSING
        if (!isTextResource(parsed.path)) return Target.MISSING
        val readable = runCatching { HollowJavaUtils.getResource(parsed).close() }.isSuccess
        return if (readable) Target.RESOURCE else Target.MISSING
    }

    private fun parse(location: String): ResourceLocation? =
        runCatching { ResourceLocation.parse(location.trim()) }.getOrNull()

    internal fun projectTextPath(location: String): String? = projectPath(location)?.takeIf(::isTextResource)

    private fun projectPath(location: String): String? {
        val parsed = parse(location) ?: return null
        val path = DirectoryManager.HOLLOW_ENGINE.resolve("assets").resolve(parsed.namespace).resolve(parsed.path).normalize()
        return path.takeIf { it.startsWith(DirectoryManager.HOLLOW_ENGINE.resolve("assets")) }
            ?.let { DirectoryManager.HOLLOW_ENGINE.relativize(it).toString().replace(File.separatorChar, '/') }
    }

    private fun readGameResource(location: String): String? {
        val parsed = parse(location) ?: return null
        return runCatching {
            HollowJavaUtils.getResource(parsed).use { it.bufferedReader().readText() }
        }.getOrNull()
    }

    /** Binary assets have no source to show, so only text ones become read-only tabs. */
    private fun isTextResource(path: String): Boolean = TextExtensions.any { path.endsWith(it, ignoreCase = true) }

    /** What provides a resource location, and therefore how the IDE may open it. */
    enum class ResourceTarget(val tag: String) {
        /** A file under `hollowengine/assets`, editable in place. */
        PROJECT(InlayTags.PROJECT_TARGET),

        /** A text resource served by the game, shown read-only. */
        RESOURCE(InlayTags.RESOURCE_TARGET),
    }

    private enum class Target {
        /** A file under `hollowengine/assets`, editable in place. */
        PROJECT,

        /** A text resource served by the game, shown read-only. */
        RESOURCE,
        MISSING,
    }

    private data class CachedTarget(val target: Target, val checkedAtNanos: Long)
}
