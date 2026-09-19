package ru.hollowhorizon.hollowengine.common.files

import ru.hollowhorizon.hollowengine.HollowEngine
import java.io.File

/**
 * Removes cache, that no longer needs.
 */
object CacheCleanup {
    /** Deletes every child of [directory] whose name is not in [live]. */
    fun retain(directory: File, live: Set<String>) {
        report(directory, directory.listFiles().orEmpty().filter { it.name !in live }.total(::delete))
    }

    fun retainNested(directory: File, live: Set<String>) {
        val freed = directory.listFiles(File::isDirectory).orEmpty().toList().total { child ->
            child.listFiles().orEmpty().filter { it.name !in live }.total(::delete)
                .also { if (child.list()?.isEmpty() == true) child.delete() }
        }
        report(directory, freed)
    }

    /** Deletes [file] with everything inside, as far as possible. */
    fun delete(file: File): Freed {
        var freed = Freed()
        file.walkBottomUp().forEach { entry ->
            val isFile = entry.isFile
            val size = if (isFile) entry.length() else 0L
            if (entry.delete() && isFile) freed += Freed(1, size)
        }
        if (file.exists()) HollowEngine.LOGGER.debug(
            "Could not remove the stale cache entry '{}'; it is still in use",
            file
        )
        return freed
    }

    /** Logs what a sweep of [directory] freed, if anything. */
    fun report(directory: File, freed: Freed) {
        if (freed.files == 0) return
        HollowEngine.LOGGER.info(
            "Removed {} stale files ({} MB) from {}",
            freed.files,
            "%.1f".format(freed.bytes / (1024.0 * 1024.0)),
            directory,
        )
    }

    data class Freed(val files: Int = 0, val bytes: Long = 0) {
        operator fun plus(other: Freed) = Freed(files + other.files, bytes + other.bytes)
    }

    private inline fun <T> List<T>.total(selector: (T) -> Freed): Freed =
        fold(Freed()) { sum, item -> sum + selector(item) }
}
