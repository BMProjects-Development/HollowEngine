package ru.hollowhorizon.hollowengine.common.addons

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val failedFingerprints = ConcurrentHashMap<String, String>()
    private val foreignStamps = ConcurrentHashMap<String, String>()

    fun start(initialCandidates: Collection<HollowAddonCandidate>): Job {
        initialCandidates.forEach { candidate -> tracked[candidate.sourceFile.canonicalPath] = candidate }
        return scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { scan() }
                    .onFailure { HollowEngine.LOGGER.error("Failed to scan the addon directory", it) }
                delay(SCAN_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun scan() {
        val files = directory.listFiles { file ->
            file.isFile && file.extension.equals("jar", ignoreCase = true)
        } ?: return
        val filesByPath = files.filter(::isAddon).associateBy { it.canonicalPath }

        filesByPath.forEach { (path, file) ->
            val oldCandidate = tracked[path]
            val sourceStamp = file.length().toString() + ':' + file.lastModified()
            if (
                oldCandidate != null &&
                oldCandidate.sourceLength == file.length() &&
                oldCandidate.sourceModifiedAt == file.lastModified()
            ) {
                return@forEach
            }
            if (failedFingerprints[path] == sourceStamp) return@forEach
            val staged = runCatching { artifactStore.stage(file) }
                .onFailure { error ->
                    if (failedFingerprints.put(path, sourceStamp) != sourceStamp) {
                        HollowEngine.LOGGER.error("Cannot inspect addon jar '{}'", file.name, error)
                    }
                }
                .getOrNull() ?: return@forEach

            if (oldCandidate?.fingerprint == staged.fingerprint) {
                tracked[path] = staged
                failedFingerprints.remove(path)
                return@forEach
            }

            val accepted = if (oldCandidate == null) {
                runtime.addCandidate(staged)
            } else {
                runtime.replaceCandidate(oldCandidate, staged)
            }
            if (accepted) {
                tracked[path] = staged
                failedFingerprints.remove(path)
            } else {
                failedFingerprints[path] = sourceStamp
            }
        }

        (tracked.keys - filesByPath.keys).forEach { deletedPath ->
            val deleted = tracked.remove(deletedPath) ?: return@forEach
            failedFingerprints.remove(deletedPath)
            foreignStamps.remove(deletedPath)
            runtime.removeCandidate(deleted)
            tracked.values
                .filter { it.descriptor.id == deleted.descriptor.id }
                .singleOrNull()
                ?.let { replacement -> runtime.addCandidate(replacement) }
        }
    }

    private fun isAddon(file: File): Boolean {
        val path = file.canonicalPath
        if (tracked.containsKey(path)) return true

        val stamp = file.length().toString() + ':' + file.lastModified()
        if (foreignStamps[path] == stamp) return false
        if (HollowAddonProbe.isAddonJar(file)) {
            foreignStamps.remove(path)
            return true
        }

        foreignStamps[path] = stamp
        return false
    }

    private companion object {
        const val SCAN_INTERVAL_MILLIS = 2_000L
    }
}
