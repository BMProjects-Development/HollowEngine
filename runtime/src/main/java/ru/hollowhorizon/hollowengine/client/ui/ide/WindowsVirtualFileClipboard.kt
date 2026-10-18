package ru.hollowhorizon.hollowengine.client.ui.ide

import com.sun.jna.Memory
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import ru.hollowhorizon.hollowengine.HollowEngine
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.*
import java.util.*

private const val FileGroupDescriptorWide = "FileGroupDescriptorW"
private const val FileContents = "FileContents"
private const val FileDescriptorSize = 592
private const val FileDescriptorNameOffset = 72
private const val FileDescriptorNameBytes = 520
private const val FileAttributeDirectory = 0x10
private const val FileDescriptorFlagSize = 0x40
private const val DescriptorHeaderSize = 4L
private const val DataAspectContent = 1
internal const val StorageGlobal = 1
private const val StorageFile = 2
private const val StorageStream = 4
private const val StreamBufferSize = 64 * 1024

internal data class WindowsVirtualFileDescriptor(
    val name: String,
    val directory: Boolean,
    val size: Long?,
)

internal data class WindowsVirtualFilePasteEntry(
    val sourceIndex: Int,
    val descriptor: WindowsVirtualFileDescriptor,
    val destination: Path,
)

/** Reads virtual shell files such as entries copied directly out of WinRAR. */
internal object WindowsVirtualFileClipboard {
    fun pasteInto(targetDir: Path): Boolean {
        if (!Platform.isWindows()) return false
        return runCatching { pasteFromOleClipboard(targetDir) }
            .onFailure { error ->
                HollowEngine.LOGGER.warn("Could not read virtual files from the Windows clipboard", error)
            }
            .getOrDefault(false)
    }

    private fun pasteFromOleClipboard(targetDir: Path): Boolean {
        return withWindowsClipboardDataObject { dataObject ->
            val descriptors = dataObject.readDescriptors()
            descriptors.isNotEmpty() && dataObject.materialize(descriptors, targetDir)
        } ?: false
    }

    private fun WindowsClipboardDataObject.readDescriptors(): List<WindowsVirtualFileDescriptor> {
        val format = ClipboardFormats.fileDescriptor
        if (format == 0) return emptyList()
        val medium = requestData(format, index = -1, acceptedStorage = StorageGlobal) ?: return emptyList()
        try {
            if (medium.tymed != StorageGlobal) return emptyList()
            val handle = medium.data ?: return emptyList()
            val size = WindowsApis.kernel32.GlobalSize(handle).toLong()
            if (size < DescriptorHeaderSize) return emptyList()
            val memory = WindowsApis.kernel32.GlobalLock(handle) ?: return emptyList()
            return try {
                parseWindowsFileGroupDescriptor(size, memory::getInt) { offset, length ->
                    memory.getByteArray(offset, length)
                }
            } finally {
                WindowsApis.kernel32.GlobalUnlock(handle)
            }
        } finally {
            releaseWindowsStorageMedium(medium)
        }
    }

    private fun WindowsClipboardDataObject.materialize(
        descriptors: List<WindowsVirtualFileDescriptor>,
        targetDir: Path,
    ): Boolean {
        val entries = planWindowsVirtualFilePaste(descriptors, targetDir)
        if (entries.isEmpty()) return false
        var pasted = false
        entries.sortedWith(compareBy({ !it.descriptor.directory }, { it.destination.nameCount }))
            .forEach { entry ->
                val destination = entry.destination
                if (entry.descriptor.directory) {
                    if (runCatching { Files.createDirectories(destination) }.isSuccess) pasted = true
                    return@forEach
                }
                if (writeFile(entry, destination)) pasted = true
            }
        return pasted
    }

    private fun WindowsClipboardDataObject.writeFile(entry: WindowsVirtualFilePasteEntry, destination: Path): Boolean {
        return runCatching {
            Files.createDirectories(destination.parent)
            val temporary = Files.createTempFile(destination.parent, ".hollowengine-paste-", ".tmp")
            try {
                val written = Files.newOutputStream(temporary).use { output ->
                    writeContents(entry, output)
                }
                if (!written) return@runCatching false
                if (entry.descriptor.size?.let { it != Files.size(temporary) } == true) return@runCatching false
                moveAtomically(temporary, destination)
                true
            } finally {
                Files.deleteIfExists(temporary)
            }
        }.onFailure { error ->
            HollowEngine.LOGGER.warn("Could not paste virtual clipboard file '{}'", entry.descriptor.name, error)
        }.getOrDefault(false)
    }

    private fun WindowsClipboardDataObject.writeContents(entry: WindowsVirtualFilePasteEntry, output: OutputStream): Boolean {
        val format = ClipboardFormats.fileContents
        if (format == 0) return false
        val acceptedStorage = StorageGlobal or StorageFile or StorageStream
        val medium = requestData(format, entry.sourceIndex, acceptedStorage) ?: return false
        try {
            val data = medium.data ?: return false
            return when (medium.tymed) {
                StorageGlobal -> writeGlobalMemory(data, entry.descriptor.size, output)
                StorageFile -> writeFileContents(data, output)
                StorageStream -> writeStream(data, output)
                else -> false
            }
        } finally {
            releaseWindowsStorageMedium(medium)
        }
    }

    private fun writeGlobalMemory(handle: Pointer, expectedSize: Long?, output: OutputStream): Boolean {
        val allocationSize = WindowsApis.kernel32.GlobalSize(handle).toLong()
        val contentSize = expectedSize ?: allocationSize
        if (contentSize > allocationSize) return false
        if (contentSize == 0L) return true
        val memory = WindowsApis.kernel32.GlobalLock(handle) ?: return false
        return try {
            var offset = 0L
            while (offset < contentSize) {
                val length = minOf(StreamBufferSize.toLong(), contentSize - offset).toInt()
                output.write(memory.getByteArray(offset, length))
                offset += length
            }
            true
        } finally {
            WindowsApis.kernel32.GlobalUnlock(handle)
        }
    }

    private fun writeFileContents(fileName: Pointer, output: OutputStream): Boolean {
        val source = runCatching { Path.of(fileName.getWideString(0)) }.getOrNull() ?: return false
        if (!Files.isRegularFile(source)) return false
        Files.newInputStream(source).use { it.copyTo(output) }
        return true
    }

    private fun writeStream(streamPointer: Pointer, output: OutputStream): Boolean {
        val stream = ClipboardStream(streamPointer)
        val buffer = Memory(StreamBufferSize.toLong())
        val bytesRead = IntByReference()
        while (true) {
            bytesRead.value = 0
            val result = stream.read(buffer, StreamBufferSize, bytesRead)
            if (result < 0) return false
            val count = bytesRead.value
            if (count !in 0..StreamBufferSize) return false
            if (count == 0) return true
            output.write(buffer.getByteArray(0, count))
        }
    }
}

internal fun parseWindowsFileGroupDescriptor(bytes: ByteArray): List<WindowsVirtualFileDescriptor> {
    return parseWindowsFileGroupDescriptor(
        bytes.size.toLong(),
        { offset -> ByteBuffer.wrap(bytes, offset.toInt(), Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int },
        { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length) },
    )
}

private fun parseWindowsFileGroupDescriptor(
    size: Long,
    readInt: (Long) -> Int,
    readBytes: (Long, Int) -> ByteArray,
): List<WindowsVirtualFileDescriptor> {
    if (size < DescriptorHeaderSize) return emptyList()
    val count = Integer.toUnsignedLong(readInt(0))
    val requiredSize = DescriptorHeaderSize + count * FileDescriptorSize
    if (count == 0L || count > Int.MAX_VALUE || requiredSize > size) return emptyList()
    return List(count.toInt()) { index ->
        val offset = DescriptorHeaderSize + index.toLong() * FileDescriptorSize
        val flags = readInt(offset)
        val attributes = readInt(offset + 36)
        val sizeHigh = readInt(offset + 64)
        val sizeLow = readInt(offset + 68)
        val nameBytes = readBytes(offset + FileDescriptorNameOffset, FileDescriptorNameBytes)
        WindowsVirtualFileDescriptor(
            name = decodeNullTerminatedUtf16(nameBytes),
            directory = attributes and FileAttributeDirectory != 0,
            size = readFileSize(flags, sizeHigh, sizeLow),
        )
    }
}

internal fun planWindowsVirtualFilePaste(
    descriptors: List<WindowsVirtualFileDescriptor>,
    targetDir: Path,
): List<WindowsVirtualFilePasteEntry> {
    val reservedRoots = mutableSetOf<String>()
    val roots = mutableMapOf<String, Path>()
    val seenSources = mutableSetOf<String>()
    return buildList {
        descriptors.forEachIndexed { index, descriptor ->
            val source = safeVirtualPath(descriptor.name) ?: return@forEachIndexed
            val sourceKey = source.toString().replace('\\', '/').lowercase(Locale.ROOT)
            if (!seenSources.add(sourceKey)) return@forEachIndexed
            val rootName = source.getName(0).toString()
            val rootKey = rootName.lowercase(Locale.ROOT)
            val root = roots.getOrPut(rootKey) {
                uniqueDestination(targetDir, rootName) { candidate ->
                    candidate.destinationKey() in reservedRoots
                }.also { reservedRoots += it.destinationKey() }
            }
            val destination = if (source.nameCount == 1) {
                root
            } else {
                root.resolve(source.subpath(1, source.nameCount)).normalize()
            }
            if (!destination.toAbsolutePath().normalize().startsWith(targetDir.toAbsolutePath().normalize())) {
                return@forEachIndexed
            }
            add(WindowsVirtualFilePasteEntry(index, descriptor, destination))
        }
    }
}

private fun safeVirtualPath(name: String): Path? {
    val normalizedSeparators = name.replace('\\', '/')
    if (normalizedSeparators.isBlank() || normalizedSeparators.startsWith('/')) return null
    if (WindowsDrivePath.matches(normalizedSeparators)) return null
    val path = try {
        Path.of(normalizedSeparators).normalize()
    } catch (_: InvalidPathException) {
        return null
    }
    val unsafe = path.isAbsolute || path.toString().isBlank() || path.nameCount == 0 ||
            path.getName(0).toString() == ".."
    if (unsafe) {
        return null
    }
    return path
}

private fun decodeNullTerminatedUtf16(bytes: ByteArray): String {
    var length = 0
    while (length + 1 < bytes.size && (bytes[length] != 0.toByte() || bytes[length + 1] != 0.toByte())) {
        length += 2
    }
    return bytes.copyOf(length).toString(Charsets.UTF_16LE)
}

private fun readFileSize(flags: Int, high: Int, low: Int): Long? {
    if (flags and FileDescriptorFlagSize == 0 || high < 0) return null
    return (high.toLong() shl 32) or Integer.toUnsignedLong(low)
}

private fun Path.destinationKey(): String =
    toAbsolutePath().normalize().toString().replace('\\', '/').lowercase(Locale.ROOT)

private fun moveAtomically(source: Path, destination: Path) {
    try {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, destination)
    }
}

private val WindowsDrivePath = Regex("^[A-Za-z]:")

@Structure.FieldOrder("cfFormat", "targetDevice", "aspect", "index", "storage")
internal class WindowsClipboardFormat : Structure() {
    @JvmField
    var cfFormat: Short = 0

    @JvmField
    var targetDevice: Pointer? = null

    @JvmField
    var aspect: Int = 0

    @JvmField
    var index: Int = -1

    @JvmField
    var storage: Int = 0
}

@Structure.FieldOrder("tymed", "data", "releaseOwner")
internal class WindowsStorageMedium : Structure() {
    @JvmField
    var tymed: Int = 0

    @JvmField
    var data: Pointer? = null

    @JvmField
    var releaseOwner: Pointer? = null
}

internal class WindowsClipboardDataObject(pointer: Pointer) : Unknown(pointer) {
    fun requestData(format: Int, index: Int, acceptedStorage: Int): WindowsStorageMedium? {
        val requested = WindowsClipboardFormat().apply {
            cfFormat = format.toShort()
            aspect = DataAspectContent
            this.index = index
            storage = acceptedStorage
            write()
        }
        val medium = WindowsStorageMedium().apply { write() }
        val result = _invokeNativeInt(3, arrayOf(pointer, requested.pointer, medium.pointer))
        if (result < 0) return null
        medium.read()
        return medium
    }
}

private class ClipboardStream(pointer: Pointer) : Unknown(pointer) {
    fun read(buffer: Pointer, capacity: Int, bytesRead: IntByReference): Int =
        _invokeNativeInt(3, arrayOf(pointer, buffer, capacity, bytesRead.pointer))
}

private object ClipboardFormats {
    val fileDescriptor: Int = User32.INSTANCE.RegisterClipboardFormat(FileGroupDescriptorWide)
    val fileContents: Int = User32.INSTANCE.RegisterClipboardFormat(FileContents)
}

internal fun <T> withWindowsClipboardDataObject(block: (WindowsClipboardDataObject) -> T): T? {
    val initialized = Ole32.INSTANCE.OleInitialize(Pointer.NULL)
    if (initialized.toInt() < 0) return null
    try {
        val dataObjectReference = PointerByReference()
        if (WindowsApis.ole32.OleGetClipboard(dataObjectReference) < 0) return null
        val dataObjectPointer = dataObjectReference.value ?: return null
        val dataObject = WindowsClipboardDataObject(dataObjectPointer)
        return try {
            block(dataObject)
        } finally {
            dataObject.Release()
        }
    } finally {
        Ole32.INSTANCE.OleUninitialize()
    }
}

internal fun releaseWindowsStorageMedium(medium: WindowsStorageMedium) {
    WindowsApis.ole32.ReleaseStgMedium(medium)
}
