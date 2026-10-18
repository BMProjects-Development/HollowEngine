@file:JvmName("Remapping")

package ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.objectweb.asm.*
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.MethodRemapper
import org.objectweb.asm.commons.Remapper
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.metadata.KotlinMetadataRemappingClassVisitor
import ru.hollowhorizon.hollowengine.common.utils.RuntimeFlags
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.coroutines.EmptyCoroutineContext

class LambdaAwareRemapper(parent: ClassVisitor, remapper: Remapper) : ClassRemapper(Opcodes.ASM9, parent, remapper) {
    override fun createMethodRemapper(parent: MethodVisitor): MethodRemapper =
        LambdaAwareMethodRemapper(parent, remapper)
}

open class LambdaAwareMethodRemapper(
    private val parent: MethodVisitor,
    remapper: Remapper,
) : MethodRemapper(Opcodes.ASM9, parent, remapper) {
    override fun visitInvokeDynamicInsn(name: String, descriptor: String, handle: Handle, vararg args: Any) {
        val remappedName =
            if (handle.owner == "java/lang/invoke/LambdaMetafactory" && (handle.name == "metafactory" || handle.name == "altMetafactory")) {
                remapper.mapMethodName(
                    Type.getReturnType(descriptor).internalName, name, (args.first() as Type).descriptor
                )
            } else name

        parent.visitInvokeDynamicInsn(
            remappedName,
            remapper.mapMethodDesc(descriptor),
            remapper.mapValue(handle) as Handle,
            *args.map { remapper.mapValue(it) }.toTypedArray()
        )
    }
}

class MappingsRemapper(
    val mappings: Mappings,
    val from: String,
    val to: String,
    private val shouldRemapDesc: Boolean = mappings.namespaces.indexOf(from) != 0,
    private val loader: (name: String) -> ByteArray?,
) : Remapper(Opcodes.ASM9) {
    private val map = mappings.asASMMapping(from, to)
    private val baseMapper by lazy {
        MappingsRemapper(mappings, from, mappings.namespaces.first(), shouldRemapDesc = false, loader)
    }

    override fun map(internalName: String): String = map[internalName] ?: internalName
    override fun mapMethodName(owner: String, name: String, desc: String): String {
        if (name == "<init>" || name == "<clinit>") return name
        return if (desc.startsWith("(")) {
            val actualDesc = if (shouldRemapDesc) baseMapper.mapMethodDesc(desc) else desc
            walk(owner, name) { map["$it.$name$actualDesc"] }
        } else mapFieldName(owner, name, desc)
    }

    override fun mapFieldName(owner: String, name: String, desc: String?): String =
        walk(owner, name) { map["$it.$name"] }

    override fun mapRecordComponentName(owner: String, name: String, desc: String): String =
        mapFieldName(owner, name, desc)

    override fun mapSignature(signature: String?, typeSignature: Boolean): String? =
        if (signature?.isEmpty() == true) null else super.mapSignature(signature, typeSignature)

    private inline fun walk(
        owner: String,
        name: String,
        applicator: (owner: String) -> String?,
    ) = walkInheritance(loader, owner) { applicator(it) != null }?.let(applicator) ?: name

}


/**
 * Indexes a compilation classpath once and lazily reads only classes requested by inheritance lookup.
 * The owned [JarFile] handles must be closed after the compilation batch.
 */
class RemappingClasspath(classpath: List<File>) : AutoCloseable {
    private val jars =
        classpath.asSequence().filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }.map(::JarFile)
            .toList()
    private val directories = classpath.filter(File::isDirectory)
    private val entries = buildMap {
        jars.forEach { jar ->
            jar.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }
                .forEach { entry -> putIfAbsent(entry.name.dropLast(CLASS_SUFFIX.length), jar to entry) }
        }
    }
    private val cache = HashMap<String, ByteArray?>()

    @Synchronized
    fun findClass(name: String): ByteArray? {
        if (cache.containsKey(name)) return cache[name]
        val bytes = entries[name]?.let { (jar, entry) ->
            jar.getInputStream(entry).use { it.readBytes() }
        } ?: directories.firstNotNullOfOrNull { directory ->
            directory.resolve(name + CLASS_SUFFIX).takeIf(File::isFile)?.readBytes()
        }
        cache[name] = bytes
        return bytes
    }

    override fun close() {
        jars.forEach(JarFile::close)
    }

    private companion object {
        const val CLASS_SUFFIX = ".class"
    }
}

/** Reuses one mappings index and one classpath index while remapping every class in a script module. */
class ClassRemappingSession(
    mappings: Mappings,
    classpath: RemappingClasspath,
    loader: (String) -> ByteArray? = { null },
    from: String = "named",
    to: String = "intermediary",
) {
    private val remapper = MappingsRemapper(
        mappings,
        from,
        to,
        loader = { name -> loader(name) ?: classpath.findClass(name) },
    )

    fun remap(input: ByteArray): ByteArray = input.remap(remapper)
}

fun remapJars(
    mappings: Mappings,
    inputs: List<File>,
    outputDir: File,
    postfix: String = "-deobf",
    from: String = "named",
    to: String = "intermediary",
    classpath: List<File> = listOf(),
) = runBlocking {
    if (!outputDir.exists()) outputDir.mkdirs()

    val cache = hashMapOf<String, ByteArray?>()
    val jarsToUse = (classpath + inputs).map { JarFile(it) }
    val lookup = jarsToUse.flatMap { j ->
        j.entries().asSequence().filter { it.name.endsWith(".class") }
            .map { it.name.dropLast(6) to { j.getInputStream(it).readBytes() } }
    }.toMap()

    val remapper = MappingsRemapper(
        mappings,
        from,
        to,
        loader = { name -> if (name in lookup) cache.getOrPut(name) { lookup.getValue(name)() } else null })

    val semaphore = Semaphore(8)
    val context = if (RuntimeFlags.preparingMixins) EmptyCoroutineContext else Dispatchers.IO

    inputs.map { file ->
        async(context) {
            semaphore.withPermit {
                val (name, extension) = file.nameWithoutExtension to file.extension
                remapJar(remapper, file, outputDir, name, postfix, extension)
            }
        }
    }.awaitAll()
}

private fun remapJar(
    remapper: MappingsRemapper,
    file: File,
    outputDir: File,
    name: String,
    postfix: String,
    extension: String,
): File {
    JarFile(file).use { jar ->
        val output = outputDir.resolve("$name$postfix.$extension")
        JarOutputStream(output.outputStream()).use { out ->
            val entries = mutableSetOf<String>()
            val (classes, resources) = jar.entries().asSequence().partition { it.name.endsWith(".class") }

            fun write(name: String, bytes: ByteArray) {
                if (name in entries) return // Ignore duplicates
                entries += name
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
            }

            resources.filterNot { it.name.endsWith(".RSA") || it.name.endsWith(".SF") }
                .forEach { write(it.name, jar.getInputStream(it).readBytes()) }

            classes.forEach { entry ->
                val reader = ClassReader(jar.getInputStream(entry).readBytes())
                val writer = ClassWriter(reader, 0)
                val kotlinMetadataRemapper = KotlinMetadataRemappingClassVisitor(remapper, writer)
                reader.accept(LambdaAwareRemapper(kotlinMetadataRemapper, remapper), 0)
                val name = remapper.map(reader.className)
                write(if (name != reader.className) "$name.class" else entry.name, writer.toByteArray())
            }
        }
        return output
    }
}

fun ByteArray.remap(remapper: Remapper): ByteArray {
    val reader = ClassReader(this)

    val writer = ClassWriter(reader, 0)
    val kotlinMetadataRemapper = KotlinMetadataRemappingClassVisitor(remapper, writer)
    reader.accept(LambdaAwareRemapper(kotlinMetadataRemapper, remapper), 0)

    return writer.toByteArray()
}