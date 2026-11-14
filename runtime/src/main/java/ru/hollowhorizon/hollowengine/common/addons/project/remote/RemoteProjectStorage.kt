package ru.hollowhorizon.hollowengine.common.addons.project.remote

import ru.hollowhorizon.hollowengine.common.addons.project.HollowProject
import ru.hollowhorizon.hollowengine.common.addons.project.ProjectProperties
import ru.hollowhorizon.hollowengine.common.files.writeProjectFile
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.*

internal class RemoteProjectStorage(
    root: Path = HollowProject.root.toPath(),
    private val directories: Set<String> = HollowProject.contentDirectories.toSet(),
) {
    private val root = root.toRealPath()

    fun projectName(): String = try {
        val properties = resolve(ProjectProperties.PATH)
        ProjectProperties.read(readBytes(properties).inputStream()).displayName.take(RemoteProjectProjectNameLimit)
    } catch (_: IOException) {
        ProjectProperties().displayName
    } catch (_: IllegalArgumentException) {
        ProjectProperties().displayName
    }

    fun list(path: String, after: String?): RemoteProjectReply.Directory {
        if (after != null && !validSegment(after)) fail(RemoteProjectError.INVALID_PATH)
        val directory = if (path.isEmpty()) root else resolve(path)
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) fail(RemoteProjectError.MISSING)
        val entries = TreeMap<String, RemoteProjectEntry>()
        Files.newDirectoryStream(directory).use { children ->
            for (child in children) {
                val name = child.fileName.toString()
                if ((after != null && name <= after) || !validSegment(name) || !validLength(
                        joinProjectPath(
                            path,
                            name
                        )
                    ) || (path.isEmpty() && name !in directories) || (entries.size > RemoteProjectDirectoryPageSize && name > entries.lastKey())
                ) continue
                val attributes = try {
                    accessibleAttributes(child)
                } catch (_: NoSuchFileException) {
                    null
                } ?: continue
                if (!attributes.isDirectory && !attributes.isRegularFile) continue
                entries[name] = RemoteProjectEntry(name, attributes.isDirectory)
                if (entries.size > RemoteProjectDirectoryPageSize + 1) entries.pollLastEntry()
            }
        }
        val page = entries.values.take(RemoteProjectDirectoryPageSize)
        return RemoteProjectReply.Directory(page, page.lastOrNull()?.name?.takeIf { entries.size > page.size })
    }

    fun read(path: String): RemoteProjectFile {
        val bytes = readBytes(resolve(path))
        return RemoteProjectFile(revision(bytes), bytes)
    }

    fun save(path: String, expectedRevision: String, bytes: ByteArray): RemoteProjectReply.Saved {
        if (bytes.size > RemoteProjectFileLimit) fail(RemoteProjectError.TOO_LARGE)
        val target = resolve(path, mutable = true)
        val current = readBytes(target)
        val nextRevision = revision(bytes)
        if (current.contentEquals(bytes)) return RemoteProjectReply.Saved(nextRevision)
        if (revision(current) != expectedRevision) fail(RemoteProjectError.CONFLICT)
        writeProjectFile(target, bytes)
        return RemoteProjectReply.Saved(nextRevision)
    }

    fun create(path: String, directory: Boolean) {
        val target = resolve(path, create = true, mutable = !directory)
        if (directory) Files.createDirectory(target) else Files.createFile(target)
    }

    fun move(path: String, destination: String) {
        val source = resolve(path, mutable = true)
        val target = resolve(destination, create = true, mutable = true)
        if (path == destination || (target != source && target.startsWith(source))) fail(RemoteProjectError.INVALID_PATH)
        if (target != source && Files.exists(target, NOFOLLOW_LINKS)) fail(RemoteProjectError.EXISTS)
        Files.move(source, target)
    }

    fun delete(path: String) {
        val target = resolve(path, mutable = true)
        Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (accessibleAttributes(dir) == null) fail(RemoteProjectError.INVALID_PATH)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun resolve(path: String, create: Boolean = false, mutable: Boolean = false): Path {
        val segments = path.split('/')
        if (!validLength(path) || segments.first() !in directories || segments.any { !validSegment(it) } || (mutable && segments.size == 1)) fail(
            RemoteProjectError.INVALID_PATH
        )
        var target = root
        for ((index, segment) in segments.withIndex()) {
            target = try {
                target.resolve(segment)
            } catch (_: InvalidPathException) {
                fail(RemoteProjectError.INVALID_PATH)
            }
            val last = index == segments.lastIndex
            val attributes = try {
                accessibleAttributes(target) ?: fail(RemoteProjectError.INVALID_PATH)
            } catch (error: NoSuchFileException) {
                if (last && create) return target
                throw error
            }
            if (!last && !attributes.isDirectory) fail(RemoteProjectError.MISSING)
        }
        return target
    }

    private fun accessibleAttributes(path: Path): BasicFileAttributes? {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        return attributes.takeIf {
            !it.isSymbolicLink && !it.isOther && path.toRealPath() == path
        }
    }

    private fun readBytes(path: Path): ByteArray {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) fail(RemoteProjectError.MISSING)
        return Files.newByteChannel(path, READ, NOFOLLOW_LINKS).use { channel ->
            if (channel.size() > RemoteProjectFileLimit) fail(RemoteProjectError.TOO_LARGE)
            val bytes = Channels.newInputStream(channel).readNBytes(RemoteProjectFileLimit + 1)
            if (bytes.size > RemoteProjectFileLimit) fail(RemoteProjectError.TOO_LARGE)
            bytes
        }
    }
}

internal class RemoteProjectStorageException(val error: RemoteProjectError) : IOException()

internal fun joinProjectPath(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"
internal fun projectPathParent(path: String): String = path.substringBeforeLast('/', "")
internal fun projectPathContains(parent: String, path: String): Boolean =
    parent.isEmpty() || path == parent || path.startsWith("$parent/")

private fun fail(error: RemoteProjectError): Nothing = throw RemoteProjectStorageException(error)

private fun validLength(path: String): Boolean =
    path.length <= RemoteProjectPathLimit && path.toByteArray(Charsets.UTF_8).size <= RemoteProjectPathLimit

private fun validSegment(name: String): Boolean =
    name.isNotBlank() && !name.endsWith('.') && !name.endsWith(' ') && name.none { it.code < 32 || it in "/\\:*?\"<>|" } && !ReservedWindowsName.matches(
        name.substringBefore('.').trimEnd()
    )

private fun revision(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

private const val RemoteProjectProjectNameLimit = 80
private val ReservedWindowsName = Regex("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]", RegexOption.IGNORE_CASE)
