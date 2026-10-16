package ru.hollowhorizon.hollowengine.common.ide.session.definition

import com.intellij.openapi.vfs.VirtualFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.scripting.ide.DefinitionLocation
import ru.hollowhorizon.hollowengine.common.scripting.ide.SourceDocumentPath
import ru.hollowhorizon.hollowengine.common.scripting.ide.withNormalizedLineSeparators
import java.io.IOException
import java.lang.System.Logger.Level
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.*

internal class LibrarySourceRepository(
    private val matcher: SourceDeclarationMatcher,
    private val binaryRoots: List<Path>,
    private val attachedRoots: (() -> List<Path>)? = null,
) {
    private val reportedFailures = HashSet<Pair<Path, String?>>()
    private val discoveredManifests by lazy {
        (listOf(Path.of("")) + binaryRoots).mapNotNull(::developmentSourceManifest).distinct()
    }
    private var attached: AttachedSources? = null
    private val adjacent = HashMap<Path, List<Path>>()
    private val archives = HashMap<Path, ArchiveIndex>()
    private val jdkSources by lazy {
        val javaHome = Path.of(System.getProperty("java.home"))
        listOfNotNull(javaHome.resolve("lib/src.zip"), javaHome.parent?.resolve("lib/src.zip")).existing()
    }

    /** A restored source tab has no preceding definition request to establish its library context. */
    fun ownsSourcePath(name: String): Boolean {
        val location = when (val document = SourceDocumentPath.parse(name)) {
            is SourceDocumentPath.SourceFile -> document.file
            is SourceDocumentPath.ArchiveEntry -> document.archive
            else -> return false
        }
        val path = try {
            Path.of(location).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            return false
        }
        return sourceRoots(null).any { root -> if (root.isDirectory()) path.startsWith(root) else path == root }
    }

    fun find(target: SourceDeclarationIdentity, classFile: VirtualFile?, packageName: String): DefinitionLocation? {
        val packageDirectory = packageName.replace('.', '/')
        val fileNames = buildSet {
            classFile?.sourceFileName()?.let(::add)
            val outer = target.owners.firstOrNull() ?: target.name
            add("$outer.kt")
            add("$outer.java")
        }
        for (root in sourceRoots(classFile)) {
            val found = if (root.isDirectory()) findInDirectory(root, packageDirectory, fileNames, target)
            else findInArchive(root, packageDirectory, fileNames, target)
            if (found != null) return found
        }
        return null
    }

    private fun findInDirectory(
        root: Path, packageDirectory: String, fileNames: Set<String>, target: SourceDeclarationIdentity,
    ): DefinitionLocation? {
        for (name in fileNames) {
            val entry = if (packageDirectory.isEmpty()) name else "$packageDirectory/$name"
            val file = root.resolve(entry).normalize()
            if (!file.startsWith(root) || !file.isRegularFile()) continue
            val text = try {
                file.readText()
            } catch (failure: IOException) {
                reportFailure(file, failure)
                continue
            }
            val offset = matcher.offset(entry, text, target) ?: continue
            return DefinitionLocation(
                SourceDocumentPath.SourceFile(file.toString()).path,
                offset,
                text,
                readOnly = true
            ).withNormalizedLineSeparators()
        }
        return null
    }

    private fun findInArchive(
        archive: Path, packageDirectory: String, fileNames: Set<String>, target: SourceDeclarationIdentity,
    ): DefinitionLocation? {
        val index = archiveIndex(archive)
        val named = fileNames.flatMap { index.byFileName[it].orEmpty() }.sortedBy { !it.isInPackage(packageDirectory) }
        if (named.isEmpty() && index.kotlinEntries.isEmpty()) return null
        val packageName = packageDirectory.replace('/', '.')
        return try {
            ZipFile(archive.toFile()).use { zip ->
                val candidates = sequence {
                    yieldAll(named)
                    yieldAll(index.kotlinFilesIn(packageName, zip))
                }.distinct()
                for (name in candidates) {
                    val entry = zip.getEntry(name) ?: continue
                    val text = zip.readText(entry)
                    if (!text.contains(target.name)) continue
                    if (!name.isInPackage(packageDirectory) && text.declaredPackage() != packageName) continue
                    val offset = matcher.offset(name, text, target.copy(packageName = packageName)) ?: continue
                    return DefinitionLocation(
                        SourceDocumentPath.ArchiveEntry(archive.toString(), name).path,
                        offset,
                        text,
                        true
                    ).withNormalizedLineSeparators()
                }
                null
            }
        } catch (failure: IOException) {
            reportFailure(archive, failure)
            null
        }
    }

    private fun sourceRoots(classFile: VirtualFile?): Sequence<Path> = sequence {
        yieldAll(attachedRoots?.invoke()?.existing() ?: attachedSources())
        val binary = classFile?.path?.substringBefore("!/")?.let { runCatching { Path.of(it) }.getOrNull() }
        if (binary != null) yieldAll(adjacentSources(binary))
        for (root in binaryRoots) yieldAll(adjacentSources(root))
        yieldAll(jdkSources)
    }.distinct()

    /** Adding or removing an archive changes the modification time of the folder that holds it. */
    private fun attachedSources(): List<Path> {
        val folder = DirectoryManager.LIBRARY_SOURCES
        val manifests = attachedSourceManifests(discoveredManifests)
        val stamp = directoryTimes(folder) + manifests.map { it to it.modifiedOrNull() }
        attached?.takeIf { it.stamp == stamp }?.let { return it.roots }
        return attachedLibrarySources(folder, manifests, ::reportFailure).existing()
            .also { attached = AttachedSources(stamp, it) }
    }

    private fun adjacentSources(binary: Path): List<Path> = adjacent.getOrPut(binary.toAbsolutePath().normalize()) {
        adjacentLibrarySources(
            binary,
            ::reportFailure
        ).existing()
    }

    private fun archiveIndex(archive: Path): ArchiveIndex {
        val attributes = try {
            archive.readAttributes<BasicFileAttributes>()
        } catch (failure: IOException) {
            reportFailure(archive, failure)
            return ArchiveIndex.Empty
        }
        archives[archive]?.takeIf { it.size == attributes.size() && it.modified == attributes.lastModifiedTime() }
            ?.let { return it }
        val entries = try {
            ZipFile(archive.toFile()).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
                    .map { it.name }.toList()
            }
        } catch (failure: IOException) {
            reportFailure(archive, failure)
            emptyList()
        }
        return ArchiveIndex(attributes.size(), attributes.lastModifiedTime(), entries).also { archives[archive] = it }
    }

    private fun reportFailure(path: Path, failure: IOException) {
        if (reportedFailures.add(path to failure.message)) {
            SourceLogger.log(Level.WARNING, "Cannot read library sources at '$path'; continuing source lookup", failure)
        }
    }
}

private class AttachedSources(val stamp: List<Any?>, val roots: List<Path>)

private class ArchiveIndex(val size: Long, val modified: FileTime?, entries: List<String>) {
    val byFileName: Map<String, List<String>> = entries.groupBy { it.substringAfterLast('/') }
    val kotlinEntries: List<String> = entries.filter { it.endsWith(".kt") }
    private var kotlinByPackage: Map<String, List<String>>? = null

    /** Read once per archive version, and only when a lookup by file name has failed. */
    fun kotlinFilesIn(packageName: String, zip: ZipFile): List<String> {
        val byPackage = kotlinByPackage ?: kotlinEntries.groupBy { name ->
            zip.getEntry(name)?.let { zip.readText(it).declaredPackage() }.orEmpty()
        }.also { kotlinByPackage = it }
        return byPackage[packageName].orEmpty()
    }

    companion object {
        val Empty = ArchiveIndex(-1, null, emptyList())
    }
}

private fun ZipFile.readText(entry: ZipEntry): String = getInputStream(entry).bufferedReader().use { it.readText() }

private fun String.declaredPackage(): String = SourcePackage.find(this)?.groupValues?.get(1).orEmpty()

/** Also accepts archives that prefix packages with a module folder, as the JDK's `src.zip` does. */
private fun String.isInPackage(packageDirectory: String): Boolean {
    val directory = substringBeforeLast('/', "")
    return directory == packageDirectory || packageDirectory.isNotEmpty() && directory.endsWith("/$packageDirectory")
}

private fun List<Path>.existing(): List<Path> =
    map { it.toAbsolutePath().normalize() }.distinct().filter { it.isDirectory() || it.isRegularFile() }

private fun Path.modifiedOrNull(): FileTime? = try {
    getLastModifiedTime()
} catch (_: IOException) {
    null
}

private fun directoryTimes(root: Path): List<FileTime?> = buildList {
    try {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                add(attrs.lastModifiedTime())
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
    } catch (_: IOException) {
        add(null)
    }
}

private val SourcePackage = Regex("(?m)^\\s*package\\s+([\\w.]+)")
private val SourceLogger = System.getLogger(LibrarySourceRepository::class.java.name)

private fun VirtualFile.sourceFileName(): String? {
    if (extension != "class") return null
    return runCatching {
        var source: String? = null
        ClassReader(contentsToByteArray()).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitSource(sourceName: String?, debug: String?) {
                source = sourceName
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        source
    }.getOrNull()
}
