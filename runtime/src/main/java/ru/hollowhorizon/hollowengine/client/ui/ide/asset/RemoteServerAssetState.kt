package ru.hollowhorizon.hollowengine.client.ui.ide.asset

import androidx.compose.runtime.*
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.network.RequestServerAssetDirectoryPacket
import ru.hollowhorizon.hollowengine.common.network.RequestServerAssetFilePacket
import ru.hollowhorizon.hollowengine.common.network.ServerAssetDirectoryPacket
import ru.hollowhorizon.hollowengine.common.network.ServerAssetFilePacket
import java.io.ByteArrayOutputStream

@Stable
internal object RemoteServerAssetState {
    private val directories = mutableStateMapOf<String, AssetDirectory>()
    private val files = mutableStateMapOf<String, AssetFile>()
    private val loadedDirectories = mutableStateMapOf<String, Boolean>()
    private val loadingDirectories = mutableStateMapOf<String, Boolean>()
    private val pendingFiles = mutableMapOf<String, PendingRemoteFile>()
    private var generation = 0

    var revision by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val loading: Boolean get() = loadingDirectories.isNotEmpty()

    fun reset() {
        generation++
        directories.clear()
        files.clear()
        loadedDirectories.clear()
        loadingDirectories.clear()
        pendingFiles.clear()
        error = null
        revision++
    }

    fun snapshot(): AssetIndex = AssetIndex(files.values.toList(), directories.values.toList())

    fun requestRoot(force: Boolean = false) {
        requestDirectory(namespace = "", path = "", force = force)
    }

    fun requestDirectory(directory: AssetDirectory, force: Boolean = false) {
        requestDirectory(directory.namespace, directory.path, force)
    }

    fun requestFile(file: AssetFile, callback: (ByteArray?, String?) -> Unit) {
        val key = file.location.toString()
        val pending = pendingFiles.getOrPut(key) { PendingRemoteFile() }
        pending.callbacks += callback
        if (pending.callbacks.size == 1) {
            RequestServerAssetFilePacket(
                file.location.namespace,
                file.location.path,
                generation = generation,
            ).send()
        }
    }

    fun accept(packet: ServerAssetDirectoryPacket) {
        if (packet.generation != generation) return
        val key = directoryRequestKey(packet.namespace, packet.path)
        if (packet.error != null) {
            loadingDirectories.remove(key)
            error = packet.error
            revision++
            return
        }
        packet.entries.forEach { entry ->
            if (entry.directory) {
                val directory = AssetDirectory(entry.namespace, entry.path)
                directories[directory.key] = directory
            } else {
                val location = ResourceLocation.tryBuild(entry.namespace, entry.path) ?: return@forEach
                files[location.toString()] = AssetFile(location, entry.sourcePackId)
            }
        }
        error = null
        revision++
        val nextOffset = packet.nextOffset
        if (nextOffset == null) {
            loadingDirectories.remove(key)
            loadedDirectories[key] = true
        } else {
            RequestServerAssetDirectoryPacket(packet.namespace, packet.path, nextOffset, generation).send()
        }
    }

    fun accept(packet: ServerAssetFilePacket) {
        if (packet.generation != generation) return
        val key = "${packet.namespace}:${packet.path}"
        val pending = pendingFiles[key] ?: return
        if (packet.error != null) {
            pendingFiles.remove(key)
            pending.callbacks.forEach { callback -> callback(null, packet.error) }
            return
        }
        pending.output.write(packet.bytes)
        val nextOffset = packet.nextOffset
        if (nextOffset != null) {
            RequestServerAssetFilePacket(
                packet.namespace,
                packet.path,
                offset = nextOffset,
                generation = generation,
            ).send()
            return
        }
        pendingFiles.remove(key)
        val bytes = pending.output.toByteArray()
        pending.callbacks.forEach { callback -> callback(bytes, null) }
    }

    private fun requestDirectory(namespace: String, path: String, force: Boolean) {
        val cleanPath = path.trim('/')
        val key = directoryRequestKey(namespace, cleanPath)
        if (!force && (loadedDirectories[key] == true || loadingDirectories[key] == true)) return
        if (force) {
            loadedDirectories.remove(key)
            removeChildren(namespace, cleanPath)
        }
        loadingDirectories[key] = true
        error = null
        RequestServerAssetDirectoryPacket(namespace, cleanPath, generation = generation).send()
    }

    private fun removeChildren(namespace: String, path: String) {
        if (namespace.isEmpty()) {
            directories.clear()
            files.clear()
            return
        }
        val prefix = path.takeIf(String::isNotEmpty)?.plus('/') ?: ""
        directories.values.filter { directory ->
            directory.namespace == namespace && directory.path.startsWith(prefix) && directory.path != path
        }.map(AssetDirectory::key).forEach(directories::remove)
        files.values.filter { file ->
            file.location.namespace == namespace && file.location.path.startsWith(prefix)
        }.map { file -> file.location.toString() }.forEach(files::remove)
    }
}

private class PendingRemoteFile {
    val output = ByteArrayOutputStream()
    val callbacks = mutableListOf<(ByteArray?, String?) -> Unit>()
}

private fun directoryRequestKey(namespace: String, path: String): String = "$namespace:${path.trim('/')}"
