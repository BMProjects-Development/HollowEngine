package ru.hollowhorizon.hollowengine.client.ui.ide

import kotlinx.coroutines.*
import ru.hollowhorizon.hollowengine.HollowEngine
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*

/** Watches parent directories so editors that save by replacing the file are observed too. */
internal suspend fun watchIdeFiles(paths: List<Path>, onChanged: () -> Unit) = withContext(Dispatchers.IO) {
    if (paths.isEmpty()) return@withContext
    try {
        while (currentCoroutineContext().isActive) {
            try {
                FileSystems.getDefault().newWatchService().use { watcher ->
                    val watched = paths.asSequence()
                        .flatMap { path ->
                            val absolute = path.toAbsolutePath().normalize()
                            val target = runCatching { absolute.toRealPath() }.getOrDefault(absolute)
                            listOf(absolute.watchTarget(), target.watchTarget())
                        }
                        .distinct().groupBy(Path::getParent)
                        .map { (directory, files) ->
                            directory.register(
                                watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY
                            ) to files.map(Path::getFileName).toSet()
                        }
                        .groupBy({ it.first }, { it.second })
                        .mapValues { (_, names) -> names.flatten().toSet() }

                    onChanged()
                    while (watched.isNotEmpty() && currentCoroutineContext().isActive) {
                        val key = runInterruptible { watcher.take() }
                        val names = watched[key].orEmpty()
                        val changed = key.pollEvents().any { it.kind() == OVERFLOW || it.context() in names }
                        if (!key.reset() || changed) break
                    }
                }
            } catch (_: NoSuchFileException) {
                onChanged()
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        HollowEngine.LOGGER.warn("Could not watch IDE files", error)
    }
}

/** A missing parent is observed through the nearest existing ancestor until it is recreated. */
private fun Path.watchTarget(): Path {
    var target = this
    while (!Files.isDirectory(target.parent)) target = target.parent
    return target
}
