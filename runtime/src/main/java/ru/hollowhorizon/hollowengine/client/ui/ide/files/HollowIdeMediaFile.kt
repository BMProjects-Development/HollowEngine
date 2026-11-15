package ru.hollowhorizon.hollowengine.client.ui.ide.files

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.remote.message
import ru.hollowhorizon.hollowengine.common.addons.project.remote.RemoteProjectError
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Native media decoders need a seekable path. The temporary file belongs to the mounted preview. */
@Composable
internal fun HollowIdeMediaFile(file: HollowIdeOpenFile, content: @Composable (String) -> Unit) {
    val bytes = file.remoteSourceBytes
    if (bytes == null) {
        content(file.path)
        return
    }
    var path by remember(bytes) { mutableStateOf<Path?>(null) }
    var failed by remember(bytes) { mutableStateOf(false) }
    LaunchedEffect(bytes) {
        var backing: Path? = null
        try {
            withContext(Dispatchers.IO) {
                val temporary = Files.createTempFile("hollowengine-media-", ".${pathExtension(file.projectPath)}")
                backing = temporary
                Files.write(temporary, bytes)
            }
            path = backing
            awaitCancellation()
        } catch (failure: IOException) {
            HollowEngine.LOGGER.warn("Could not open server media {}", file.projectPath, failure)
            failed = true
        } finally {
            withContext(Dispatchers.IO + NonCancellable) {
                backing?.let { temporary ->
                    runCatching { Files.deleteIfExists(temporary) }.onFailure { temporary.toFile().deleteOnExit() }
                }
            }
        }
    }
    path?.let { content(it.toString()) }
    if (failed) Text(RemoteProjectError.IO.message())
}
