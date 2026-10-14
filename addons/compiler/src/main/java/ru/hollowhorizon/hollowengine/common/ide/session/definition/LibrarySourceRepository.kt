package ru.hollowhorizon.hollowengine.common.ide.session.definition

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import ru.hollowhorizon.hollowengine.common.scripting.ide.DefinitionLocation
import ru.hollowhorizon.hollowengine.common.scripting.ide.withNormalizedLineSeparators
import java.io.IOException
import java.lang.System.Logger.Level
import java.nio.file.Path
import java.nio.file.InvalidPathException
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** Sources are navigation inputs, never additional binary dependencies of the compiler. */
internal class LibrarySourceRepository(
    project: Project,
    private val binaryRoots: List<Path>,
    private val attachedRoots: (() -> List<Path>)? = null,
) {
    private val matcher = SourceDeclarationMatcher(project)
    private val reportedFailures = HashSet<Pair<Path, String?>>()

    /** A restored source tab has no preceding definition request to establish its library context. */
    fun ownsSourcePath(name: String): Boolean {
        val location = name.removePrefix("library-sources/").substringBefore("!/")
        val path = try {
            Path.of(location).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            return false
        }
        return sourceRoots(null).any { root -> if (root.isDirectory()) path.startsWith(root) else path == root }
    }

    fun find(target: SourceDeclarationIdentity, classFile: VirtualFile?, packageName: String): DefinitionLocation? {
        val packagePath = packageName.replace('.', '/').let { if (it.isEmpty()) "" else "$it/" }
        val names = buildSet {
            classFile?.sourceFileName()?.let(::add)
            val outer = target.owners.firstOrNull() ?: target.name
            add("$outer.kt")
            add("$outer.java")
        }
        val directEntries = names.map { packagePath + it }
        for (root in sourceRoots(classFile)) {
            if (root.isDirectory()) {
                for (entry in directEntries) {
                    val file = root.resolve(entry).normalize()
                    if (!file.startsWith(root.normalize()) || !file.isRegularFile()) continue
                    val text = try {
                        file.readText()
                    } catch (failure: IOException) {
                        reportFailure(file, failure)
                        continue
                    }
                    val offset = matcher.offset(entry, text, target) ?: continue
                    return DefinitionLocation(file.toAbsolutePath().toString(), offset, text, readOnly = true)
                        .withNormalizedLineSeparators()
                }
            } else {
                findInArchive(root, packagePath, directEntries, target)?.let { return it }
            }
        }
        return null
    }

    private fun findInArchive(
        archive: Path, packagePath: String, directEntries: List<String>, target: SourceDeclarationIdentity,
    ): DefinitionLocation? {
        return try {
            ZipFile(archive.toFile()).use { zip ->
                val direct = directEntries.mapNotNull(zip::getEntry)
                val others = zip.entries().asSequence().filter { entry ->
                    !entry.isDirectory && (entry.name.endsWith(".kt") || entry.name.endsWith(".java")) &&
                        (entry.name.substringBeforeLast('/', "") == packagePath.removeSuffix("/") ||
                            directEntries.any { entry.name.endsWith("/$it") } || entry.name.endsWith(".kt"))
                }
                for (entry in (direct.asSequence() + others).distinctBy { it.name }) {
                    val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    if (!text.contains(target.name)) continue
                    if (entry !in direct && SourcePackage.find(text)?.groupValues?.get(1).orEmpty() != packagePath.removeSuffix("/").replace('/', '.')) continue
                    val offset = matcher.offset(entry.name, text, target.copy(packageName = packagePath.removeSuffix("/").replace('/', '.'))) ?: continue
                    return DefinitionLocation("library-sources/${archive.toAbsolutePath()}!/${entry.name}", offset, text, true)
                        .withNormalizedLineSeparators()
                }
            }
            null
        } catch (failure: IOException) {
            reportFailure(archive, failure)
            null
        }
    }

    private fun sourceRoots(classFile: VirtualFile?): Sequence<Path> = sequence {
        yieldAll(attachedRoots?.invoke() ?: attachedLibrarySources(listOf(Path.of("")) + binaryRoots, ::reportFailure))
        val binary = classFile?.path?.substringBefore("!/")?.let { runCatching { Path.of(it) }.getOrNull() }
        if (binary != null) yieldAll(adjacentLibrarySources(binary, ::reportFailure))
        for (root in binaryRoots) yieldAll(adjacentLibrarySources(root, ::reportFailure))
        val javaHome = Path.of(System.getProperty("java.home"))
        yield(javaHome.resolve("lib/src.zip"))
        javaHome.parent?.resolve("lib/src.zip")?.let { yield(it) }
    }.map { it.toAbsolutePath().normalize() }.distinct().filter { it.isDirectory() || it.isRegularFile() }

    private fun reportFailure(path: Path, failure: IOException) {
        if (reportedFailures.add(path to failure.message)) {
            SourceLogger.log(Level.WARNING, "Cannot read library sources at '$path'; continuing source lookup", failure)
        }
    }
}

private val SourcePackage = Regex("(?m)^\\s*package\\s+([\\w.]+)")
private val SourceLogger = System.getLogger(LibrarySourceRepository::class.java.name)

private fun VirtualFile.sourceFileName(): String? {
    if (extension != "class") return null
    return runCatching {
        var source: String? = null
        ClassReader(contentsToByteArray()).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitSource(sourceName: String?, debug: String?) { source = sourceName }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        source
    }.getOrNull()
}
