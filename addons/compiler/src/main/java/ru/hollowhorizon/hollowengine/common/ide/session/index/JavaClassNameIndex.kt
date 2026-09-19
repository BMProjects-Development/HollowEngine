package ru.hollowhorizon.hollowengine.common.ide.session.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * A compact, immutable catalog of importable JVM classes.
 *
 * The Analysis API can resolve a class when its [ClassId] is known, but
 * does not expose an efficient way to enumerate Java class names. This index fills that narrow gap:
 * it reads only class-file headers and never loads or decompiles the classes.
 */
internal class JavaClassNameIndex(private val roots: List<VirtualFile>) {
    private val data by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::buildIndex)

    fun warmUp() {
        data
    }

    fun classes(
        nameFilter: (Name) -> Boolean,
        annotationsOnly: Boolean = false,
    ): Sequence<JavaClassName> = data.classes.asSequence().filter { javaClass ->
        (!annotationsOnly || javaClass.isAnnotation) && nameFilter(javaClass.name)
    }

    fun classesInPackage(
        packageFqName: FqName,
        nameFilter: (Name) -> Boolean,
        annotationsOnly: Boolean = false,
    ): Sequence<JavaClassName> = data.classesByPackage[packageFqName.asString()]
        .orEmpty()
        .asSequence()
        .filter { javaClass ->
            javaClass.relativeName.indexOf('.') < 0 &&
                    (!annotationsOnly || javaClass.isAnnotation) &&
                    nameFilter(javaClass.name)
        }

    fun subPackages(packageFqName: FqName, nameFilter: (Name) -> Boolean): Sequence<Name> {
        val parent = packageFqName.asString()
        val prefix = if (parent.isEmpty()) "" else "$parent."
        return data.packages.asSequence()
            .filter { packageName -> packageName.startsWith(prefix) && packageName.length > prefix.length }
            .map { packageName -> packageName.substring(prefix.length).substringBefore('.') }
            .distinct()
            .map(Name::identifier)
            .filter(nameFilter)
    }

    private fun buildIndex(): IndexData {
        val scan = scanClassFiles()
        readCache(scan.fingerprint)?.let { return it }

        val classesByFqName = linkedMapOf<String, JavaClassName>()
        scan.files.forEach { candidate ->
            createClassName(candidate.relativePath, candidate.file)?.let { javaClass ->
                classesByFqName.putIfAbsent(javaClass.fqName, javaClass)
            }
        }

        return createIndexData(classesByFqName.values.toList()).also { index ->
            writeCache(scan.fingerprint, index)
        }
    }

    private fun scanClassFiles(): ClassFileScan {
        val files = ArrayList<ClassFileCandidate>()
        roots.forEach { root ->
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
                override fun visitFileEx(file: VirtualFile): Result {
                    if (file.isDirectory || !file.name.endsWith(CLASS_EXTENSION, ignoreCase = true)) return CONTINUE

                    val relativePath = VfsUtilCore.getRelativePath(file, root, '/') ?: return CONTINUE
                    files += ClassFileCandidate(root.url, relativePath, file)
                    return CONTINUE
                }
            })
        }

        files.sortWith(compareBy(ClassFileCandidate::rootUrl, ClassFileCandidate::relativePath))
        val digest = MessageDigest.getInstance(FINGERPRINT_ALGORITHM)
        roots.sortedBy { root -> root.url }.forEach { root ->
            digest.update(root.url)
            digest.update(root.length.toString())
            digest.update(root.timeStamp.toString())
        }
        files.forEach { candidate ->
            digest.update(candidate.rootUrl)
            digest.update(candidate.relativePath)
            digest.update(candidate.file.length.toString())
            digest.update(candidate.file.timeStamp.toString())
        }
        return ClassFileScan(files, digest.digest())
    }

    private fun createClassName(relativePath: String, file: VirtualFile): JavaClassName? {
        if (relativePath.startsWith(MULTI_RELEASE_PREFIX)) return null

        val classPath = relativePath.removeSuffix(CLASS_EXTENSION)
        val packagePath = classPath.substringBeforeLast('/', missingDelimiterValue = "")
        val binaryName = classPath.substringAfterLast('/')
        if (binaryName == "module-info" || binaryName == "package-info") return null

        val relativeNames = binaryName.split('$')
        if (relativeNames.any { name -> !name.isJavaIdentifier() }) return null

        val access = readClassAccess(file) ?: return null
        if (access and ACC_PUBLIC == 0 || access and ACC_SYNTHETIC != 0) return null

        return JavaClassName.create(
            packageName = packagePath.replace('/', '.'),
            relativeName = relativeNames.joinToString("."),
            isAnnotation = access and ACC_ANNOTATION != 0,
        )
    }

    private fun readCache(fingerprint: ByteArray): IndexData? = runCatching {
        val cacheFile = cacheFile()
        if (!Files.isRegularFile(cacheFile)) return@runCatching null

        DataInputStream(BufferedInputStream(Files.newInputStream(cacheFile))).use { input ->
            if (input.readInt() != CACHE_MAGIC || input.readInt() != CACHE_VERSION) return@runCatching null
            val cachedFingerprint = ByteArray(input.readUnsignedByte())
            input.readFully(cachedFingerprint)
            if (!cachedFingerprint.contentEquals(fingerprint)) return@runCatching null

            val classCount = input.readInt()
            if (classCount !in 0..MAX_CACHED_CLASSES) return@runCatching null
            val classes = ArrayList<JavaClassName>(classCount)
            repeat(classCount) {
                classes += JavaClassName.create(
                    packageName = input.readUTF(),
                    relativeName = input.readUTF(),
                    isAnnotation = input.readBoolean(),
                )
            }
            createIndexData(classes)
        }
    }.getOrNull()

    private fun writeCache(fingerprint: ByteArray, index: IndexData) {
        runCatching {
            val cacheFile = cacheFile()
            Files.createDirectories(cacheFile.parent)
            val temporaryFile = Files.createTempFile(cacheFile.parent, "java-classes-", ".tmp")
            try {
                DataOutputStream(BufferedOutputStream(Files.newOutputStream(temporaryFile))).use { output ->
                    output.writeInt(CACHE_MAGIC)
                    output.writeInt(CACHE_VERSION)
                    output.writeByte(fingerprint.size)
                    output.write(fingerprint)
                    output.writeInt(index.classes.size)
                    index.classes.forEach { javaClass ->
                        output.writeUTF(javaClass.packageName)
                        output.writeUTF(javaClass.relativeName)
                        output.writeBoolean(javaClass.isAnnotation)
                    }
                }
                moveAtomically(temporaryFile, cacheFile)
            } finally {
                Files.deleteIfExists(temporaryFile)
            }
            Files.newDirectoryStream(cacheFile.parent, "java-classes-*").use { files ->
                files.filter { it != cacheFile }.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun cacheFile(): Path = DirectoryManager.HOLLOW_ENGINE.resolve("cache/ide/java-classes-v$CACHE_VERSION.bin")

    private fun moveAtomically(source: Path, target: Path) {
        runCatching {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readClassAccess(file: VirtualFile): Int? = runCatching {
        DataInputStream(BufferedInputStream(file.inputStream)).use { input ->
            if (input.readInt() != CLASS_MAGIC) return@runCatching null
            input.skipFully(4)

            val constantPoolCount = input.readUnsignedShort()
            var index = 1
            while (index < constantPoolCount) {
                when (input.readUnsignedByte()) {
                    CONSTANT_UTF8 -> input.skipFully(input.readUnsignedShort())
                    CONSTANT_INTEGER, CONSTANT_FLOAT,
                    CONSTANT_FIELD_REF, CONSTANT_METHOD_REF, CONSTANT_INTERFACE_METHOD_REF,
                    CONSTANT_NAME_AND_TYPE, CONSTANT_DYNAMIC, CONSTANT_INVOKE_DYNAMIC -> input.skipFully(4)
                    CONSTANT_LONG, CONSTANT_DOUBLE -> {
                        input.skipFully(8)
                        index++
                    }
                    CONSTANT_CLASS, CONSTANT_STRING, CONSTANT_METHOD_TYPE,
                    CONSTANT_MODULE, CONSTANT_PACKAGE -> input.skipFully(2)
                    CONSTANT_METHOD_HANDLE -> input.skipFully(3)
                    else -> return@runCatching null
                }
                index++
            }

            input.readUnsignedShort()
        }
    }.getOrNull()

    private fun DataInputStream.skipFully(byteCount: Int) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped <= 0) {
                readByte()
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    companion object {
        fun getInstance(project: Project): JavaClassNameIndex = project.getService(JavaClassNameIndex::class.java)

        private const val CLASS_MAGIC = -0x35014542
        private const val CACHE_MAGIC = 0x48454A43
        private const val CACHE_VERSION = 1
        private const val MAX_CACHED_CLASSES = 1_000_000
        private const val FINGERPRINT_ALGORITHM = "SHA-256"
        private const val CLASS_EXTENSION = ".class"
        private const val MULTI_RELEASE_PREFIX = "META-INF/versions/"
        private const val ACC_PUBLIC = 0x0001
        private const val ACC_SYNTHETIC = 0x1000
        private const val ACC_ANNOTATION = 0x2000

        private const val CONSTANT_UTF8 = 1
        private const val CONSTANT_INTEGER = 3
        private const val CONSTANT_FLOAT = 4
        private const val CONSTANT_LONG = 5
        private const val CONSTANT_DOUBLE = 6
        private const val CONSTANT_CLASS = 7
        private const val CONSTANT_STRING = 8
        private const val CONSTANT_FIELD_REF = 9
        private const val CONSTANT_METHOD_REF = 10
        private const val CONSTANT_INTERFACE_METHOD_REF = 11
        private const val CONSTANT_NAME_AND_TYPE = 12
        private const val CONSTANT_METHOD_HANDLE = 15
        private const val CONSTANT_METHOD_TYPE = 16
        private const val CONSTANT_DYNAMIC = 17
        private const val CONSTANT_INVOKE_DYNAMIC = 18
        private const val CONSTANT_MODULE = 19
        private const val CONSTANT_PACKAGE = 20
    }
}

internal data class JavaClassName(
    val name: Name,
    val relativeName: String,
    val fqName: String,
    val packageName: String,
    val isAnnotation: Boolean,
) {
    companion object {
        fun create(packageName: String, relativeName: String, isAnnotation: Boolean): JavaClassName {
            val fqName = if (packageName.isEmpty()) relativeName else "$packageName.$relativeName"
            return JavaClassName(
                name = Name.identifier(relativeName.substringAfterLast('.')),
                relativeName = relativeName,
                fqName = fqName,
                packageName = packageName,
                isAnnotation = isAnnotation,
            )
        }
    }
}

private data class IndexData(
    val classes: List<JavaClassName>,
    val classesByPackage: Map<String, List<JavaClassName>>,
    val packages: Set<String>,
)

private data class ClassFileCandidate(
    val rootUrl: String,
    val relativePath: String,
    val file: VirtualFile,
)

private data class ClassFileScan(
    val files: List<ClassFileCandidate>,
    val fingerprint: ByteArray,
)

private fun createIndexData(classes: List<JavaClassName>): IndexData = IndexData(
    classes = classes,
    classesByPackage = classes.groupBy(JavaClassName::packageName),
    packages = classes.asSequence().map(JavaClassName::packageName).filter(String::isNotEmpty).toSet(),
)

private fun MessageDigest.update(value: String) {
    update(value.toByteArray(StandardCharsets.UTF_8))
    update(0.toByte())
}

private fun String.isJavaIdentifier(): Boolean =
    isNotEmpty() && first().isJavaIdentifierStart() && drop(1).all(Char::isJavaIdentifierPart)
