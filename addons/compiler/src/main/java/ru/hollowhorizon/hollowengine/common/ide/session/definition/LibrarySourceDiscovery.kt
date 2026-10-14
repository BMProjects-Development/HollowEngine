package ru.hollowhorizon.hollowengine.common.ide.session.definition

import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readLines

internal fun adjacentLibrarySources(binary: Path, onFailure: (Path, IOException) -> Unit): List<Path> {
    if (binary.isDirectory()) {
        val build = generateSequence(binary) { it.parent }.firstOrNull { it.name == "build" }
        return build?.parent?.resolve("src/main")?.let { listOf(it.resolve("java"), it.resolve("kotlin")) }.orEmpty()
    }
    if (binary.extension != "jar") return emptyList()
    return buildList {
        if (binary.name.endsWith("-sources.jar")) add(binary)
        binary.parent?.resolve("${binary.nameWithoutExtension}-sources.jar")?.let(::add)
        val checksum = binary.parent ?: return@buildList
        val version = checksum.parent ?: return@buildList
        val module = version.parent ?: return@buildList
        val group = module.parent ?: return@buildList
        if (group.parent?.name != "files-2.1" || !binary.name.startsWith("${module.name}-${version.name}")) return@buildList
        collectSourceArchives(version, maxDepth = 2, onFailure = onFailure)
            .filterTo(this) { it.name == "${binary.nameWithoutExtension}-sources.jar" }
    }
}

internal fun attachedLibrarySources(
    developmentDirectories: List<Path>,
    onFailure: (Path, IOException) -> Unit,
): List<Path> = buildList {
    val folder = DirectoryManager.LIBRARY_SOURCES
    add(folder)
    addAll(collectSourceArchives(folder, onFailure = onFailure).sorted())
    val manifests = System.getProperty("hollowengine.ide.sourceRoots")?.let { listOf(Path.of(it)) }
        ?: developmentDirectories.mapNotNull(::developmentSourceManifest).distinct()
    for (file in manifests) {
        try {
            if (file.isRegularFile()) addAll(file.readLines().filter(String::isNotBlank).map(Path::of))
        } catch (failure: IOException) {
            onFailure(file, failure)
        }
    }
}

/** Locate the generated manifest in the nearest Gradle build, without walking unrelated directories. */
internal fun developmentSourceManifest(directory: Path): Path? {
    val project = generateSequence(directory.toAbsolutePath().normalize()) { it.parent }.firstOrNull {
        it.resolve("settings.gradle.kts").isRegularFile() || it.resolve("settings.gradle").isRegularFile()
    } ?: return null
    return project.resolve("build/ide/source-roots.txt").takeIf { it.isRegularFile() }
}

/** A failed entry must not discard other attached archives or stop decompiler fallback. */
internal fun collectSourceArchives(
    root: Path,
    maxDepth: Int = Int.MAX_VALUE,
    onFailure: (Path, IOException) -> Unit,
): List<Path> = buildList {
    try {
        Files.walkFileTree(root, emptySet(), maxDepth, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && file.extension in setOf("jar", "zip")) add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                onFailure(file, exc)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) onFailure(dir, exc)
                return FileVisitResult.CONTINUE
            }
        })
    } catch (failure: IOException) {
        onFailure(root, failure)
    }
}
