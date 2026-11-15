package ru.hollowhorizon.hollowengine.common.addons

import kotlinx.coroutines.*
import ru.hollowhorizon.hollowengine.HollowEngine
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class HollowAddonWatcher(
    private val directory: File,
    private val artifactStore: HollowAddonArtifactStore,
    private val runtime: HollowAddonRuntime,
    private val scope: CoroutineScope,
) {
    private val tracked = ConcurrentHashMap<String, HollowAddonCandidate>()
    private val unreadableStamps = ConcurrentHashMap<String, String>()
    private val foreignStamps = ConcurrentHashMap<String, String>()

    /** Stamps seen on the previous scan: a jar is read once it has stopped changing, not while it is copied. */
    private val settlingStamps = ConcurrentHashMap<String, String>()

    /** [unreadable] are the jars of this directory the startup has already failed to read and reported. */
    fun start(initialCandidates: Collection<HollowAddonCandidate>, unreadable: Collection<File> = emptyList()): Job {
        initialCandidates.forEach { candidate -> tracked[candidate.key] = candidate }
        unreadable.forEach { file -> unreadableStamps[file.canonicalPath] = stampOf(file) }
        return scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { scan() }
                    .onFailure { HollowEngine.LOGGER.error("Failed to scan the addon directory", it) }
                delay(SCAN_INTERVAL_MILLIS)
            }
        }
    }

    internal suspend fun scan() {
        // A directory that is gone has lost all of its addons, the same as an empty one.
        val files = directory.listFiles { file ->
            file.isFile && file.extension.equals("jar", ignoreCase = true)
        }.orEmpty()
        val filesByPath = files.filter(::isAddon).associateBy { it.canonicalPath }

        filesByPath.forEach { (path, file) ->
            val oldCandidate = tracked[path]
            val stamp = stampOf(file)
            if (
                oldCandidate != null &&
                oldCandidate.sourceLength == file.length() &&
                oldCandidate.sourceModifiedAt == file.lastModified()
            ) {
                settlingStamps.remove(path)
                return@forEach
            }
            if (unreadableStamps[path] == stamp) return@forEach
            if (settlingStamps.put(path, stamp) != stamp) return@forEach
            settlingStamps.remove(path)

            val staged = runCatching { artifactStore.stage(file) }
                .onFailure { error ->
                    unreadableStamps[path] = stamp
                    HollowEngine.LOGGER.error("Cannot inspect addon jar '{}'", file.name, error)
                    runtime.reportUnreadable(file, error)
                }
                .getOrNull() ?: return@forEach

            unreadableStamps.remove(path)
            tracked[path] = staged
            if (oldCandidate?.fingerprint == staged.fingerprint) runtime.reportUnreadable(file, null)
            else runtime.install(staged)
        }

        (tracked.keys - filesByPath.keys).forEach { deletedPath ->
            val deleted = tracked.remove(deletedPath) ?: return@forEach
            runtime.remove(deleted)
        }
        (unreadableStamps.keys - filesByPath.keys).forEach { deletedPath ->
            unreadableStamps.remove(deletedPath)
            runtime.reportUnreadable(File(deletedPath), null)
        }
        settlingStamps.keys.retainAll(filesByPath.keys)
        foreignStamps.keys.retainAll(files.mapTo(HashSet()) { it.canonicalPath })
    }

    private fun isAddon(file: File): Boolean {
        val path = file.canonicalPath
        if (tracked.containsKey(path) || unreadableStamps.containsKey(path)) return true

        val stamp = stampOf(file)
        if (foreignStamps[path] == stamp) return false
        if (HollowAddonProbe.isAddonJar(file)) {
            foreignStamps.remove(path)
            return true
        }

        foreignStamps[path] = stamp
        return false
    }

    private fun stampOf(file: File): String = file.length().toString() + ':' + file.lastModified()

    private companion object {
        const val SCAN_INTERVAL_MILLIS = 2_000L
    }
}
