package ru.hollowhorizon.hollowengine.client.ui.ide

import com.sun.jna.Platform
import com.sun.jna.Pointer
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFWNativeWin32
import ru.hollowhorizon.hollowengine.HollowEngine
import java.awt.Toolkit
import java.awt.datatransfer.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private data class HollowIdeClipboardPayload(
    val files: List<File>,
    val cut: Boolean,
)

/**
 * The IDE's cut/copy buffer. Windows uses native shell formats even in a headless JVM;
 * other desktops use AWT's file-list flavor. Virtual Windows files are materialized on paste.
 */
internal object HollowIdeFileClipboard : ClipboardOwner {
    private const val DropEffectNative = "Preferred DropEffect"
    private const val DropEffectMove: Byte = 2
    private const val DropEffectCopy: Byte = 1

    @Volatile
    private var internal: HollowIdeClipboardPayload? = null

    @Volatile
    private var ownedTransferable: Transferable? = null

    private val dropEffectFlavor: DataFlavor? by lazy {
        runCatching {
            val flavor = DataFlavor("application/x-java-serialized-object;class=java.io.InputStream", DropEffectNative)
            (SystemFlavorMap.getDefaultFlavorMap() as? SystemFlavorMap)?.apply {
                addUnencodedNativeForFlavor(flavor, DropEffectNative)
                addFlavorForUnencodedNative(DropEffectNative, flavor)
            }
            flavor
        }.getOrNull()
    }

    fun set(files: List<File>, cut: Boolean): Boolean {
        val payload = HollowIdeClipboardPayload(files.toList(), cut)
        if (Platform.isWindows()) {
            val window = GLFWNativeWin32.glfwGetWin32Window(Minecraft.getInstance().window.window)
            return WindowsDropFileClipboard.write(payload.files, cut, Pointer(window))
        }
        internal = payload
        val clipboard = systemClipboard() ?: return false
        val cutEffect = dropEffectFlavor.takeIf { cut }
        val primary = FileListTransferable(payload.files, cut, cutEffect)
        if (setSystemContents(clipboard, primary)) return true

        val fallback = FileListTransferable(payload.files, cut, null)
        return setSystemContents(clipboard, fallback)
    }

    fun pasteInto(targetDir: Path): Boolean {
        if (Platform.isWindows()) {
            WindowsDropFileClipboard.read()?.let { payload ->
                return if (payload.cut) moveFiles(payload.files, targetDir) else importFiles(payload.files, targetDir)
            }
            return WindowsVirtualFileClipboard.pasteInto(targetDir)
        }
        val clipboard = systemClipboard()
        val systemFiles = clipboard?.readFiles().orEmpty()
        if (systemFiles.isNotEmpty()) {
            val own = internal?.takeIf { payload -> payload.sameFilesAs(systemFiles) }
            val payload = HollowIdeClipboardPayload(systemFiles, own?.cut ?: (clipboard?.readsAsMove() == true))
            if (pasteRealFiles(payload, targetDir)) return true
        }

        return internal?.let { pasteRealFiles(it, targetDir) } == true
    }

    override fun lostOwnership(clipboard: Clipboard, contents: Transferable) {
        if (ownedTransferable !== contents) return
        ownedTransferable = null
        internal = null
    }

    private fun setSystemContents(clipboard: Clipboard, contents: Transferable): Boolean {
        return runCatching {
            clipboard.setContents(contents, this)
            ownedTransferable = contents
        }.isSuccess
    }

    private fun pasteRealFiles(payload: HollowIdeClipboardPayload, targetDir: Path): Boolean {
        var pasted = false
        val realTarget = targetDir.toRealPath()
        payload.files.forEach { sourceFile ->
            val source = sourceFile.toPath()
            if (!Files.exists(source)) return@forEach
            val destination = uniqueDestination(targetDir, source.fileName.toString())
            val copied = runCatching {
                if (realTarget.swallowedBy(source.toRealPath())) return@forEach
                if (payload.cut) {
                    moveClipboardFile(source, destination)
                } else if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    source.copyDirectory(destination)
                } else {
                    Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                }
            }.onFailure { error ->
                HollowEngine.LOGGER.warn("Could not paste clipboard file '{}' into '{}'", source, targetDir, error)
            }.isSuccess
            if (!copied) return@forEach

            pasted = true
        }
        if (payload.cut) internal = null
        return pasted
    }

    fun importFiles(files: List<File>, targetDir: Path): Boolean =
        pasteRealFiles(HollowIdeClipboardPayload(files, cut = false), targetDir)

    fun moveFiles(files: List<File>, targetDir: Path): Boolean =
        pasteRealFiles(HollowIdeClipboardPayload(files, cut = true), targetDir)

    private fun systemClipboard(): Clipboard? =
        runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()

    private fun Clipboard.readFiles(): List<File> = runCatching {
        if (!isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) return@runCatching emptyList()
        (getData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>().orEmpty()
    }.onFailure { error ->
        HollowEngine.LOGGER.warn("Could not read the AWT file list from the system clipboard", error)
    }.getOrDefault(emptyList())

    private fun Clipboard.readsAsMove(): Boolean {
        val flavor = dropEffectFlavor ?: return false
        return runCatching {
            if (!isDataFlavorAvailable(flavor)) return false
            (getData(flavor) as? InputStream)?.use { it.read() == DropEffectMove.toInt() } == true
        }.getOrDefault(false)
    }

    private fun HollowIdeClipboardPayload.sameFilesAs(other: List<File>): Boolean =
        files.map(File::getAbsolutePath) == other.map(File::getAbsolutePath)

    private class FileListTransferable(
        private val files: List<File>,
        private val cut: Boolean,
        private val dropEffectFlavor: DataFlavor?,
    ) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> =
            listOfNotNull(DataFlavor.javaFileListFlavor, dropEffectFlavor).toTypedArray()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            flavor == DataFlavor.javaFileListFlavor || flavor == dropEffectFlavor

        override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
            DataFlavor.javaFileListFlavor -> files
            dropEffectFlavor -> ByteArrayInputStream(
                byteArrayOf(if (cut) DropEffectMove else DropEffectCopy, 0, 0, 0),
            )

            else -> throw UnsupportedFlavorException(flavor)
        }
    }
}

/** Fast rename on the same filesystem; cross-volume moves delete only after a complete copy. */
private fun moveClipboardFile(source: Path, destination: Path) {
    try {
        Files.move(source, destination)
        return
    } catch (failure: IOException) {
        if (Files.getFileStore(source) == Files.getFileStore(destination.parent)) throw failure
    }
    if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) source.copyDirectory(destination)
    else Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
    source.deleteTree()
}
