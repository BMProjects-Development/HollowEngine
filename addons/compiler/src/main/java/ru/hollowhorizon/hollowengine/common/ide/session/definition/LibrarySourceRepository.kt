package ru.hollowhorizon.hollowengine.common.ide.session.definition

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.scripting.ide.DefinitionLocation
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readLines
import kotlin.io.path.readText

/** Sources are navigation inputs, never additional binary dependencies of the compiler. */
internal class LibrarySourceRepository(
    project: Project,
    private val binaryRoots: List<Path>,
    private val attachedRoots: () -> List<Path> = ::attachedSourceRoots,
) {
    private val matcher = SourceDeclarationMatcher(project)

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
                    val text = file.readText()
                    val offset = matcher.offset(entry, text, target) ?: continue
                    return DefinitionLocation(file.toAbsolutePath().toString(), offset, text, readOnly = true)
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
        return runCatching {
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
                }
            }
            null
        }.getOrNull()
    }

    private fun sourceRoots(classFile: VirtualFile?): Sequence<Path> = sequence {
        yieldAll(attachedRoots())
        val binary = classFile?.path?.substringBefore("!/")?.let { runCatching { Path.of(it) }.getOrNull() }
        if (binary != null) yieldAll(binary.adjacentSources())
        for (root in binaryRoots) yieldAll(root.adjacentSources())
        val javaHome = Path.of(System.getProperty("java.home"))
        yield(javaHome.resolve("lib/src.zip"))
        javaHome.parent?.resolve("lib/src.zip")?.let { yield(it) }
    }.map { it.toAbsolutePath().normalize() }.distinct().filter { it.isDirectory() || it.isRegularFile() }
}

private val SourcePackage = Regex("(?m)^\\s*package\\s+([\\w.]+)")

private fun Path.adjacentSources(): List<Path> {
    if (isDirectory()) {
        // Gradle outputs carry their project sources alongside build/classes/{java,kotlin}/main.
        val build = generateSequence(this) { it.parent }.firstOrNull { it.name == "build" }
        return build?.parent?.resolve("src/main")?.let { listOf(it.resolve("java"), it.resolve("kotlin")) }.orEmpty()
    }
    if (extension != "jar") return emptyList()
    return buildList {
        if (name.endsWith("-sources.jar")) add(this@adjacentSources)
        parent?.resolve("${nameWithoutExtension}-sources.jar")?.let(::add)
        // Maven/Gradle store sibling artifacts in different checksum directories of one version.
        val versionDirectory = parent?.parent ?: return@buildList
        if (versionDirectory.isDirectory()) Files.walk(versionDirectory, 2).use { paths ->
            paths.filter { it.name == "${this@adjacentSources.nameWithoutExtension}-sources.jar" }.forEach(::add)
        }
    }
}

private fun attachedSourceRoots(): List<Path> = buildList {
    val folder = DirectoryManager.LIBRARY_SOURCES
    add(folder)
    Files.walk(folder).use { paths ->
        paths.filter { it.isRegularFile() && it.extension in setOf("jar", "zip") }.sorted().forEach(::add)
    }
    System.getProperty("hollowengine.ide.sourceRoots")?.let { manifest ->
        val file = Path.of(manifest)
        if (file.isRegularFile()) addAll(file.readLines().filter(String::isNotBlank).map(Path::of))
    }
}

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
