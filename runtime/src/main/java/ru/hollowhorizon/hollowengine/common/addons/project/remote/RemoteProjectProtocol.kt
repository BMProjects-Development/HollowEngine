package ru.hollowhorizon.hollowengine.common.addons.project.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import ru.hollowhorizon.hollowengine.common.utils.bytebuf.FriendlyByteBufDecoder
import ru.hollowhorizon.hollowengine.common.utils.bytebuf.FriendlyByteBufEncoder

internal const val RemoteProjectFileLimit = 2 * 1024 * 1024
internal const val RemoteProjectChunkSize = 24 * 1024
internal const val RemoteProjectDownloadChunkSize = 512 * 1024
internal const val RemoteProjectDirectoryPageSize = 128
internal const val RemoteProjectPathLimit = 1024
internal const val RemoteProjectRequestTimeoutMillis = 15_000L
internal const val RemoteProjectRevisionLength = 64

@Serializable
internal sealed interface RemoteProjectCommand {
    @Serializable
    class Authenticate(@Serializable(with = RemoteProjectTokenSerializer::class) val token: String) : RemoteProjectCommand

    @Serializable
    data object ProjectName : RemoteProjectCommand

    @Serializable
    class ListDirectory(val path: String, val after: String? = null) : RemoteProjectCommand

    @Serializable
    class Read(val path: String) : RemoteProjectCommand

    @Serializable
    class SaveChunk(
        val path: String,
        val revision: String,
        val offset: Int,
        val size: Int,
        @Serializable(with = RemoteProjectChunkSerializer::class) val bytes: ByteArray,
    ) : RemoteProjectCommand

    @Serializable
    class Create(val path: String, val directory: Boolean) : RemoteProjectCommand

    @Serializable
    class Move(val path: String, val destination: String) : RemoteProjectCommand

    @Serializable
    class Delete(val path: String) : RemoteProjectCommand
}

@Serializable
internal sealed interface RemoteProjectReply {
    @Serializable
    class ProjectName(val value: String) : RemoteProjectReply

    @Serializable
    class Directory(val entries: List<RemoteProjectEntry>, val next: String?) : RemoteProjectReply

    @Serializable
    class FileChunk(
        val revision: String,
        val offset: Int,
        val size: Int,
        @Serializable(with = RemoteProjectDownloadChunkSerializer::class) val bytes: ByteArray,
    ) : RemoteProjectReply

    @Serializable
    class Uploading(val nextOffset: Int) : RemoteProjectReply

    @Serializable
    class Saved(val revision: String) : RemoteProjectReply

    @Serializable
    data object Done : RemoteProjectReply

    @Serializable
    class Failure(val error: RemoteProjectError) : RemoteProjectReply
}

@Serializable
internal data class RemoteProjectEntry(val name: String, val directory: Boolean)

@Serializable
internal enum class RemoteProjectError {
    ACCESS, INVALID_PATH, MISSING, EXISTS, TOO_LARGE, UNSUPPORTED, CONFLICT, BUSY, IO, DISCONNECTED, TIMEOUT
}

@Serializable
internal enum class RemoteProjectAccess { UNAVAILABLE, LOCKED, GRANTED }

@Serializable
internal data class RemoteProjectChange(val path: String, val kind: Kind, val destination: String? = null) {
    @Serializable
    enum class Kind { SAVED, CREATED, MOVED, DELETED }
}

internal object RemoteProjectChunkSerializer : KSerializer<ByteArray> by ProjectBytesSerializer(RemoteProjectChunkSize)
internal object RemoteProjectDownloadChunkSerializer :
    KSerializer<ByteArray> by ProjectBytesSerializer(RemoteProjectDownloadChunkSize)

internal class RemoteProjectFile(val revision: String, val bytes: ByteArray) {
    fun chunks(): Sequence<RemoteProjectReply.FileChunk> = sequence {
        var offset = 0
        do {
            val end = minOf(offset + RemoteProjectDownloadChunkSize, bytes.size)
            yield(RemoteProjectReply.FileChunk(revision, offset, bytes.size, bytes.copyOfRange(offset, end)))
            offset = end
        } while (offset < bytes.size)
    }
}

private class ProjectBytesSerializer(private val maxSize: Int) : KSerializer<ByteArray> {
    override val descriptor = ByteArraySerializer().descriptor

    override fun serialize(encoder: Encoder, value: ByteArray) {
        require(value.size <= maxSize)
        (encoder as FriendlyByteBufEncoder).buf.writeByteArray(value)
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        (decoder as FriendlyByteBufDecoder).input.readByteArray(maxSize)
}

internal object RemoteProjectTokenSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("RemoteProjectToken", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        (encoder as FriendlyByteBufEncoder).buf.writeUtf(value, RemoteProjectTokenLimit)
    }

    override fun deserialize(decoder: Decoder): String =
        (decoder as FriendlyByteBufDecoder).input.readUtf(RemoteProjectTokenLimit)
}
