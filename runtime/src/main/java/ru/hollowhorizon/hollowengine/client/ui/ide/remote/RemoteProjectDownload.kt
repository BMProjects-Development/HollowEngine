package ru.hollowhorizon.hollowengine.client.ui.ide.remote

import ru.hollowhorizon.hollowengine.common.addons.project.remote.*

internal class RemoteProjectDownload(first: RemoteProjectReply.FileChunk) {
    private val file: RemoteProjectFile
    private var offset = 0

    init {
        require(first.size in 0..RemoteProjectFileLimit && first.revision.length == RemoteProjectRevisionLength)
        file = RemoteProjectFile(first.revision, ByteArray(first.size))
    }

    fun append(chunk: RemoteProjectReply.FileChunk): RemoteProjectFile? {
        require(
            chunk.revision == file.revision &&
                    chunk.size == file.bytes.size &&
                    chunk.offset == offset &&
                    chunk.bytes.size == minOf(RemoteProjectDownloadChunkSize, chunk.size - offset)
        )
        chunk.bytes.copyInto(file.bytes, offset)
        offset += chunk.bytes.size
        return file.takeIf { offset == file.bytes.size }
    }
}
