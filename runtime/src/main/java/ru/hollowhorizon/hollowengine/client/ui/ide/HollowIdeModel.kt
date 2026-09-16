package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.docking.DockItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeItem
import ru.hollowhorizon.hollowengine.client.utils.IconHelper
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager.fromReadablePath
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager.toReadablePath
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.*

private const val AutoSaveDelayMillis = 900L

internal class HollowIdeModel(
    private val fileTypes: HollowIdeFileTypeRegistry,
) {
    val tree = HollowIdeFileTree()
    val files = mutableStateMapOf<String, HollowIdeOpenFile>()
    var onFileRemoved: ((String) -> Unit)? = null
    val selectedTreePaths = mutableStateListOf<String>()
    var selectedTreePath by mutableStateOf("")
        private set
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pasteMutex = Mutex()
    private val pendingSaves = mutableMapOf<String, Job>()

    fun visibleTreeItems(filter: String, rootLabel: String): List<UiTreeItem<HollowIdeFileNode>> {
        return tree.visible(filter).map { node ->
            UiTreeItem(
                id = node.path.ifEmpty { "root" }.replace('/', '-'),
                label = if (node.path.isEmpty()) rootLabel else node.name,
                depth = node.depth,
                payload = node,
                icon = IconHelper.forPath(node.path, node.isDirectory, node.expanded).toString(),
                hasChildren = node.isDirectory,
                expanded = node.expanded,
                selected = node.path in selectedTreePaths,
            )
        }
    }

    fun select(node: HollowIdeFileNode, additive: Boolean = false) {
        selectPath(node.path, additive)
    }

    fun focusSelection(node: HollowIdeFileNode) {
        selectedTreePath = node.path
        if (node.path !in selectedTreePaths) {
            selectedTreePaths.clear()
            selectedTreePaths += node.path
        }
    }

    fun selectPath(path: String, additive: Boolean = false) {
        selectedTreePath = path
        if (additive) {
            if (path in selectedTreePaths) selectedTreePaths.remove(path) else selectedTreePaths += path
        } else {
            selectedTreePaths.clear()
            selectedTreePaths += path
        }
    }

    fun toggle(node: HollowIdeFileNode, additive: Boolean = false) {
        select(node, additive)
        tree.toggle(node.path)
    }

    fun revealPath(path: String) {
        tree.expandTo(path)
        selectPath(path)
    }

    fun open(node: HollowIdeFileNode, additive: Boolean = false): HollowIdeOpenResult {
        select(node, additive)
        if (node.isDirectory) {
            tree.toggle(node.path)
            return HollowIdeOpenResult.Directory
        }
        return openFile(node.path)
    }

    fun openFile(path: String): HollowIdeOpenResult {
        selectedTreePath = path
        val file = path.fromReadablePath()
        if (!file.isFile) return HollowIdeOpenResult.Unsupported
        if (file.isExtractedScript()) {
            val created = path !in files
            return HollowIdeOpenResult.File(openReadOnly(path, file.readText()), created)
        }
        files[path]?.let { opened ->
            if (!opened.dirty && opened.type.requiresContent) opened.refresh(file.readBytes())
            return HollowIdeOpenResult.File(opened, created = false)
        }
        val opened = runCatching {
            fileTypes.open(path, file::readBytes)?.also { opened ->
                opened.attachSaveHandler { save(path) }
            }
        }.getOrNull() ?: return HollowIdeOpenResult.Unsupported
        files[path] = opened
        return HollowIdeOpenResult.File(opened, created = true)
    }

    fun openReadOnly(path: String, text: String): HollowIdeOpenFile {
        files[path]?.let { opened ->
            if (opened.textOrNull != null) {
                opened.refreshReadOnly(text)
                return opened
            }
            files.remove(path)
            opened.close()
        }
        val textType = requireNotNull(fileTypes.find(BuiltinTextFileTypeId)) {
            "The built-in text file type is not registered"
        }
        return HollowIdeOpenFile(path, textType, HollowIdeTextDocument(text, readOnly = true)).also { opened ->
            opened.attachSaveHandler { save(path) }
            files[path] = opened
        }
    }

    fun openVirtual(path: String, typeId: String, bytes: ByteArray): HollowIdeOpenResult {
        files[path]?.let { opened ->
            if (!opened.dirty) opened.refresh(bytes)
            return HollowIdeOpenResult.File(opened, created = false)
        }
        val type = fileTypes.find(typeId) ?: return HollowIdeOpenResult.Unsupported
        val opened = runCatching { type.open(path, bytes) }.getOrNull() ?: return HollowIdeOpenResult.Unsupported
        if (!opened.readOnly) {
            opened.close()
            return HollowIdeOpenResult.Unsupported
        }
        files[path] = opened
        return HollowIdeOpenResult.File(opened, created = true)
    }

    fun updateText(path: String, text: String) {
        val file = files[path]?.takeUnless { it.readOnly } ?: return
        file.update(text)
        scheduleSave(path)
    }

    fun save(path: String): Boolean {
        val file = files[path]?.takeUnless { it.readOnly } ?: return false
        pendingSaves.remove(path)?.cancel()
        writeIdeFile(path.fromReadablePath().toPath(), file.encode())
        file.markSaved()
        tree.refresh()
        return true
    }

    fun selectedOr(path: String): List<String> {
        return if (path in selectedTreePaths) selectedTreePaths.toList() else listOf(path)
    }

    /**
     * Writes [bytes] to [path] unless something is already there.
     */
    fun createIfMissing(path: String, bytes: ByteArray): Boolean {
        val file = path.fromReadablePath()
        if (file.isFile) return true

        return runCatching {
            file.parentFile?.mkdirs()
            writeIdeFile(file.toPath(), bytes)
            tree.refresh()
        }.isSuccess
    }

    fun createFile(parentPath: String, name: String, content: String = ""): HollowIdeFileOperationResult {
        val cleanName = name.trim().replace('\\', '/').trim('/')
        if (cleanName.isBlank()) return HollowIdeFileOperationResult.InvalidName
        val parent = targetDirectory(parentPath)
        val target =
            parent.resolve(cleanName).toPath().normalizeInsideRoot() ?: return HollowIdeFileOperationResult.InvalidName
        if (Files.exists(target)) return HollowIdeFileOperationResult.AlreadyExists
        Files.createDirectories(target.parent)
        writeIdeFile(target, content.toByteArray(Charsets.UTF_8))
        tree.refresh()
        selectPath(target.toReadablePathInsideRoot())
        return HollowIdeFileOperationResult.Success
    }

    /** Replaces a project file with externally supplied bytes and closes a stale editor tab for it. */
    fun replaceFile(path: String, bytes: ByteArray): HollowIdeFileOperationResult {
        val target = path.toPathInsideRoot() ?: return HollowIdeFileOperationResult.InvalidName
        if (Files.isDirectory(target)) return HollowIdeFileOperationResult.InvalidName
        return runCatching {
            pendingSaves.remove(path)?.cancel()
            files.remove(path)?.close()
            onFileRemoved?.invoke(path)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
            tree.refresh()
            revealPath(path)
            HollowIdeFileOperationResult.Success
        }.onFailure { error ->
            HollowEngine.LOGGER.warn("Could not replace project file '{}'", path, error)
        }.getOrDefault(HollowIdeFileOperationResult.NotFound)
    }

    /** Creates (if missing) and opens a starter `sounds.json` inside [dirPath], for the sounds editor. */
    fun createSoundsFile(dirPath: String): HollowIdeOpenResult {
        val directory = targetDirectory(dirPath)
        val target = File(directory, "sounds.json")
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            target.writeText("{\n}\n")
        }
        tree.refresh()
        val readablePath = if (dirPath.isBlank()) "sounds.json" else "$dirPath/sounds.json"
        selectPath(readablePath)
        return openFile(readablePath)
    }

    fun createFolder(parentPath: String, name: String): HollowIdeFileOperationResult {
        val cleanName = name.trim().replace('\\', '/').trim('/')
        if (cleanName.isBlank()) return HollowIdeFileOperationResult.InvalidName
        val parent = targetDirectory(parentPath)
        val target =
            parent.resolve(cleanName).toPath().normalizeInsideRoot() ?: return HollowIdeFileOperationResult.InvalidName
        if (Files.exists(target)) return HollowIdeFileOperationResult.AlreadyExists
        Files.createDirectories(target)
        tree.refresh()
        selectPath(target.toReadablePathInsideRoot())
        return HollowIdeFileOperationResult.Success
    }

    fun rename(path: String, newName: String): HollowIdeFileOperationResult {
        val cleanName = newName.trim()
        if (cleanName.isBlank() || cleanName.any { it == '/' || it == '\\' }) return HollowIdeFileOperationResult.InvalidName
        val source = path.toPathInsideRoot() ?: return HollowIdeFileOperationResult.NotFound
        if (!Files.exists(source)) return HollowIdeFileOperationResult.NotFound
        val target =
            source.parent.resolve(cleanName).normalizeInsideRoot() ?: return HollowIdeFileOperationResult.InvalidName
        if (Files.exists(target)) return HollowIdeFileOperationResult.AlreadyExists
        val opened = files[path]
        if (opened?.dirty == true && !save(path)) return HollowIdeFileOperationResult.NotFound
        Files.move(source, target)
        val targetPath = target.toReadablePathInsideRoot()
        files.remove(path)
        onFileRemoved?.invoke(path)
        if (opened != null) {
            opened.close()
            runCatching {
                fileTypes.open(targetPath) { Files.readAllBytes(target) }?.also { reopened ->
                    reopened.attachSaveHandler { save(targetPath) }
                }
            }.getOrNull()?.let { files[targetPath] = it }
        }
        tree.refresh()
        selectPath(targetPath)
        return HollowIdeFileOperationResult.Success
    }

    fun delete(paths: List<String>): HollowIdeFileOperationResult {
        val targets = paths.mapNotNull { it.toPathInsideRoot() }.filter { Files.exists(it) }
            .filterNot { it == DirectoryManager.HOLLOW_ENGINE }.withoutNestedChildren()
        if (targets.isEmpty()) return HollowIdeFileOperationResult.NotFound
        targets.forEach { path -> path.deleteTree() }
        val removedReadable = targets.map { it.toReadablePathInsideRoot() }
        files.keys.filter { openPath -> removedReadable.any { openPath == it || openPath.startsWith("$it/") } }.toList()
            .forEach { path ->
                files.remove(path)?.close()
                onFileRemoved?.invoke(path)
            }
        tree.refresh()
        selectedTreePaths.clear()
        selectedTreePath = ""
        return HollowIdeFileOperationResult.Success
    }

    fun copyToClipboard(paths: List<String>, cut: Boolean = false): HollowIdeFileOperationResult {
        val files = exportFiles(paths)
        if (files.isEmpty()) return HollowIdeFileOperationResult.NotFound
        return if (HollowIdeFileClipboard.set(files, cut)) HollowIdeFileOperationResult.Success
        else HollowIdeFileOperationResult.NotFound
    }

    /** Flush edited documents before another application can move their files away. */
    fun exportFiles(paths: List<String>): List<File> {
        files.values.filter { file -> file.dirty && paths.any { file.path == it || file.path.startsWith("$it/") } }
            .forEach { save(it.path) }
        return paths.mapNotNull { it.toPathInsideRoot() }.withoutNestedChildren().map { it.toFile() }
            .filter { it.exists() }
    }

    /**
     * Closes every editor under [directories] without saving, for files about to be replaced from
     * outside; a pending autosave would otherwise write the old text back afterwards.
     */
    fun discardFilesUnder(directories: List<String>) {
        files.keys.filter { path -> directories.any { path == it || path.startsWith("$it/") } }.forEach { path ->
            pendingSaves.remove(path)?.cancel()
            files.remove(path)?.close()
            onFileRemoved?.invoke(path)
        }
    }

    /** Reconcile once on return from another application, without polling the filesystem per frame. */
    fun refreshExternalFiles() {
        tree.refresh()
        files.values.toList().filter { !it.readOnly && !it.path.fromReadablePath().exists() }.forEach { file ->
            pendingSaves.remove(file.path)?.cancel()
            if (!file.dirty) {
                files.remove(file.path)?.close()
                onFileRemoved?.invoke(file.path)
            }
        }
        selectedTreePaths.removeAll { !it.fromReadablePath().exists() }
        if (selectedTreePath !in selectedTreePaths) selectedTreePath = selectedTreePaths.lastOrNull().orEmpty()
    }

    /** Moves [paths] into the folder [targetPath] names (or the folder holding it, for a file). */
    fun moveInto(paths: List<String>, targetPath: String): HollowIdeFileOperationResult {
        val targetDir = targetDirectory(targetPath).toPath().normalizeInsideRoot()
            ?: return HollowIdeFileOperationResult.InvalidName
        val sources = paths.mapNotNull { it.toPathInsideRoot() }.filter { Files.exists(it) }
        if (sources.isEmpty()) return HollowIdeFileOperationResult.NotFound
        Files.createDirectories(targetDir)

        var moved = 0
        sources.forEach { source ->
            if (source.parent == targetDir || targetDir.swallowedBy(source)) return@forEach
            val destination = uniqueDestination(targetDir, source.fileName.toString())
            val openPath = source.toReadablePathInsideRoot()
            runCatching { Files.move(source, destination) }.onSuccess {
                moved++
                files.remove(openPath)?.close()
                onFileRemoved?.invoke(openPath)
            }
        }
        if (moved == 0) return HollowIdeFileOperationResult.InvalidName
        tree.refresh()
        selectPath(targetDir.toReadablePathInsideRoot())
        return HollowIdeFileOperationResult.Success
    }

    fun pasteIntoAsync(
        targetPath: String,
        onComplete: (HollowIdeFileOperationResult) -> Unit,
    ) = transferIntoAsync(targetPath, HollowIdeFileClipboard::pasteInto, onComplete)

    fun importFilesAsync(
        files: List<File>,
        targetPath: String,
        onComplete: (HollowIdeFileOperationResult) -> Unit,
    ) = transferIntoAsync(targetPath, { HollowIdeFileClipboard.importFiles(files, it) }, onComplete)

    private fun transferIntoAsync(
        targetPath: String,
        transfer: (Path) -> Boolean,
        onComplete: (HollowIdeFileOperationResult) -> Unit,
    ) {
        val targetDir = targetDirectory(targetPath).toPath().normalizeInsideRoot()
        if (targetDir == null) {
            onComplete(HollowIdeFileOperationResult.InvalidName)
            return
        }
        ioScope.launch {
            val result = pasteMutex.withLock {
                runCatching {
                    Files.createDirectories(targetDir)
                    if (transfer(targetDir)) {
                        HollowIdeFileOperationResult.Success
                    } else {
                        HollowIdeFileOperationResult.NotFound
                    }
                }.onFailure { error ->
                    HollowEngine.LOGGER.warn("Could not paste clipboard contents into '{}'", targetDir, error)
                }.getOrDefault(HollowIdeFileOperationResult.NotFound)
            }
            Minecraft.getInstance().execute {
                if (result == HollowIdeFileOperationResult.Success) tree.refresh()
                onComplete(result)
            }
        }
    }

    fun saveAll(): Int {
        var count = 0
        files.values.forEach { file ->
            if (file.dirty && save(file.path)) count++
        }
        return count
    }

    private fun scheduleSave(path: String) {
        val file = files[path]?.takeIf { it.dirty } ?: return
        val text = file.textOrNull ?: return
        pendingSaves.remove(path)?.cancel()
        pendingSaves[path] = ioScope.launch {
            delay(AutoSaveDelayMillis)
            runCatching {
                writeIdeFile(path.fromReadablePath().toPath(), text.toByteArray(Charsets.UTF_8))
            }.onSuccess {
                Minecraft.getInstance().execute {
                    pendingSaves.remove(path)
                    files[path]?.markSavedIfText(text)
                    tree.refresh()
                }
            }
        }
    }

}

internal sealed interface HollowIdeOpenResult {
    data object Directory : HollowIdeOpenResult
    data object Unsupported : HollowIdeOpenResult
    data class File(val file: HollowIdeOpenFile, val created: Boolean) : HollowIdeOpenResult
}

internal enum class HollowIdeFileOperationResult {
    Success, InvalidName, AlreadyExists, NotFound,
}

class HollowIdeOpenFile internal constructor(
    val path: String,
    val type: HollowIdeFileType,
    val document: HollowIdeFileDocument,
) {
    var dirty by mutableStateOf(false)
        private set
    private var saveHandler: (() -> Boolean)? = null

    val readOnly: Boolean get() = document.readOnly
    val virtual: Boolean get() = path.startsWith("resource://")
    val text: String get() = requireNotNull(textOrNull) { "File '$path' is not a text document" }
    internal val textOrNull: String? get() = (document as? HollowIdeTextDocument)?.text
    val id: String = fileDockItemId(path)
    val title: String get() = path.substringAfterLast('/').ifEmpty { path }

    fun dockItem(): DockItem {
        return DockItem(id, title, IconHelper.forPath(path).toString(), dirty = dirty && !readOnly)
    }

    fun update(next: String) {
        val textDocument = document as? HollowIdeTextDocument ?: return
        if (textDocument.update(next)) dirty = true
    }

    fun markDirty() {
        if (!readOnly) dirty = true
    }

    fun updateDirty(modified: Boolean) {
        if (!readOnly) dirty = modified
    }

    fun save(): Boolean = saveHandler?.invoke() == true

    internal fun attachSaveHandler(handler: () -> Boolean) {
        saveHandler = handler
    }

    fun markSaved() {
        if (readOnly) return
        document.markSaved()
        dirty = false
    }

    fun markSavedIfText(savedText: String) {
        if (readOnly) return
        if (text == savedText) dirty = false
    }

    fun refresh(bytes: ByteArray) {
        if (dirty) return
        document.reload(bytes)
    }

    fun refreshReadOnly(next: String) {
        val textDocument = document as? HollowIdeTextDocument ?: return
        if (!readOnly) return
        textDocument.refresh(next)
        dirty = false
    }

    fun encode(): ByteArray = document.encode()

    fun close() = document.close()
}

internal class HollowIdeFileTree {
    private val root = HollowIdeFileNode("HollowEngine", "", depth = 0, isDirectory = true).apply {
        expanded = true
    }
    private var searchRoot: HollowIdeFileNode? = null

    init {
        refresh()
    }

    fun refresh() {
        root.refresh(root.expandedPaths())
        searchRoot = null
    }

    fun toggle(path: String) {
        val node = root.resolve(path) ?: return
        if (!node.isDirectory) return
        node.expanded = !node.expanded
        if (node.expanded) node.refresh()
    }

    /** Opens every folder on the way to [path] so the node is actually visible in the tree. */
    fun expandTo(path: String) {
        var node = root
        var prefix = ""
        for (segment in path.split('/')) {
            if (segment.isEmpty()) continue
            prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
            node = node.children.firstOrNull { it.path == prefix } ?: return
            if (node.isDirectory && !node.expanded) {
                node.expanded = true
                node.refresh()
            }
        }
    }

    fun visible(filter: String): List<HollowIdeFileNode> {
        val cleanFilter = filter.trim()
        if (cleanFilter.isEmpty()) return root.visible("")
        val searchable = searchRoot ?: root.searchSnapshot().also { searchRoot = it }
        return searchable.visible(cleanFilter)
    }
}

internal class HollowIdeFileNode(
    val name: String,
    val path: String,
    val depth: Int,
    val isDirectory: Boolean,
) {
    val dropDirectoryPath: String = if (isDirectory) path else path.substringBeforeLast('/', "")
    val children = mutableStateListOf<HollowIdeFileNode>()
    var expanded by mutableStateOf(false)

    fun refresh(expandedPaths: Set<String> = expandedPaths()) {
        if (!isDirectory) return
        val files = path.fromReadablePath().listFiles().orEmpty()
        children.clear()
        children += files.sortedWith(compareBy<File> { it.isFile }.thenBy { it.name.lowercase() }).map { file ->
                val childPath = if (path.isEmpty()) file.name else "$path/${file.name}"
                HollowIdeFileNode(file.name, childPath, depth + 1, file.isDirectory).apply {
                    expanded = childPath in expandedPaths
                    if (expanded) refresh(expandedPaths)
                }
            }
    }

    fun expandedPaths(): Set<String> = buildSet {
        if (expanded) add(path)
        children.forEach { addAll(it.expandedPaths()) }
    }

    fun find(targetPath: String): HollowIdeFileNode? {
        if (path == targetPath) return this
        return children.firstNotNullOfOrNull { it.find(targetPath) }
    }

    fun resolve(targetPath: String): HollowIdeFileNode? {
        if (targetPath.isEmpty()) return this
        var node = this
        var currentPath = ""
        for (segment in targetPath.split('/').filter(String::isNotEmpty)) {
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
            if (node.isDirectory && node.children.none { it.path == currentPath }) node.refresh()
            node = node.children.firstOrNull { it.path == currentPath } ?: return null
        }
        return node
    }

    fun searchSnapshot(): HollowIdeFileNode = HollowIdeFileNode(name, path, depth, isDirectory).also { snapshot ->
        snapshot.expanded = expanded
        snapshot.refreshRecursively()
    }

    private fun refreshRecursively() {
        refresh()
        children.asSequence().filter(HollowIdeFileNode::isDirectory)
            .filterNot { Files.isSymbolicLink(it.path.fromReadablePath().toPath()) }
            .forEach(HollowIdeFileNode::refreshRecursively)
    }

    fun visible(filter: String): List<HollowIdeFileNode> {
        if (filter.isNotEmpty() && !matches(filter)) return emptyList()
        val result = mutableListOf(this)
        if (expanded || filter.isNotEmpty()) {
            children.forEach { result += it.visible(filter) }
        }
        return result
    }

    private fun matches(filter: String): Boolean {
        return path.contains(filter, ignoreCase = true) || children.any { it.matches(filter) }
    }
}

internal fun fileDockItemId(path: String): String {
    return "ide-file-" + Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray())
}

private fun targetDirectory(path: String): File {
    val file = path.fromReadablePath()
    return if (file.isDirectory || path.isBlank()) file else file.parentFile
}

/** Whether a file is a copy the engine unpacked out of an addon rather than something authored here. */
private fun File.isExtractedScript(): Boolean {
    val path = toPath().toAbsolutePath().normalize()
    return sequenceOf(
        DirectoryManager.SCRIPT_SOURCE_CACHE,
        DirectoryManager.SCRIPT_BUNDLE_CACHE
    ).any { root -> path.startsWith(root.toPath().toAbsolutePath().normalize()) }
}

private fun String.toPathInsideRoot(): Path? {
    return fromReadablePath().toPath().normalizeInsideRoot()
}

private fun Path.normalizeInsideRoot(): Path? {
    val root = DirectoryManager.HOLLOW_ENGINE.toAbsolutePath().normalize()
    val normalized = toAbsolutePath().normalize()
    return normalized.takeIf { it.isSameOrNestedIn(root) }
}

private fun List<Path>.withoutNestedChildren(): List<Path> {
    val sorted = map { it.toAbsolutePath().normalize() }.distinct().sortedBy { it.nameCount }
    val result = mutableListOf<Path>()
    for (path in sorted) {
        if (result.none { parent -> path != parent && path.isSameOrNestedIn(parent) }) result.add(path)
    }
    return result
}

internal fun Path.deleteTree() {
    if (!Files.exists(this, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(this, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            try {
                Files.deleteIfExists(file)
            } catch (e: Exception) {
                HollowEngine.LOGGER.info("Error while deleting {}: ", file.toReadablePath(), e)
            }
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exception: IOException?): FileVisitResult {
            try {
                Files.deleteIfExists(dir)
            } catch (e: Exception) {
                HollowEngine.LOGGER.info("Error while deleting {}: ", dir.toReadablePath(), e)
            }
            return FileVisitResult.CONTINUE
        }
    })
}

internal fun Path.swallowedBy(source: Path): Boolean =
    toAbsolutePath().normalize().isSameOrNestedIn(source.toAbsolutePath().normalize())

private fun Path.isSameOrNestedIn(parent: Path): Boolean =
    toAbsolutePath().normalize().startsWith(parent.toAbsolutePath().normalize())

private fun Path.toReadablePathInsideRoot(): String {
    val root = DirectoryManager.HOLLOW_ENGINE.toAbsolutePath().normalize()
    val normalized = toAbsolutePath().normalize()
    return root.relativize(normalized).toString().replace('\\', '/')
}

internal fun uniqueDestination(
    targetDir: Path,
    fileName: String,
    isReserved: (Path) -> Boolean = { false },
): Path {
    var candidate = targetDir.resolve(fileName)
    if (!Files.exists(candidate) && !isReserved(candidate)) return candidate
    val extensionStart = fileName.lastIndexOf('.').takeIf { it > 0 }
    val baseName = extensionStart?.let { fileName.substring(0, it) } ?: fileName
    val extension = extensionStart?.let { fileName.substring(it) }.orEmpty()
    var index = 1
    while (Files.exists(candidate) || isReserved(candidate)) {
        candidate = targetDir.resolve("$baseName copy${if (index == 1) "" else " $index"}$extension")
        index++
    }
    return candidate
}

internal fun Path.copyDirectory(target: Path) {
    Files.walk(this).use { stream ->
        stream.forEach { source ->
            val destination = target.resolve(relativize(source).toString())
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(destination)
            } else {
                Files.createDirectories(destination.parent)
                Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
            }
        }
    }
}
