package ru.hollowhorizon.hollowengine.runtime.remap

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.Remapper
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.MappingsRemapper
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.RemappingClasspath
import java.io.File

object PayloadRemapTableGenerator {
    class Result(val table: PayloadRemapTable, val shippedTable: PayloadRemapTable, val remapped: File)

    fun generate(
        payload: File,
        mappings: Mappings,
        classpath: List<File>,
        from: String,
        to: String,
        output: File,
        relocation: PrefixRelocation = PrefixRelocation.NONE,
        keep: Set<String> = emptySet(),
    ): Result = RemappingClasspath(classpath + payload).use { lookup ->
        val recording = Recording(from, to)
        val mappedClasses = mappings.classNames(from)
        val payloadClasses = classNames(payload) + keep

        var scriptClasses: Map<String, ByteArray> = emptyMap()
        val loader: (String) -> ByteArray? = { name -> scriptClasses[name] ?: lookup.findClass(name) }
        val delegate = MappingsRemapper(mappings, from, to, loader = loader)
        fun recorder(ownClasses: Set<String>) = RecordingRemapper(
            delegate = delegate,
            declarations = DeclarationIndex(loader),
            mappedClasses = mappedClasses,
            relocation = relocation,
            ownClasses = ownClasses,
            recording = recording,
        )

        val payloadRecorder = recorder(payloadClasses)
        remapPayload(
            payload,
            output,
            PayloadRewrite(
                classes = payloadRecorder,
                forArtifact = { classes ->
                    scriptClasses = classes
                    recorder(payloadClasses + classes.keys)
                },
            ),
        )

        Result(recording.toTable(), recording.toShippedTable(), output)
    }

    private fun Mappings.classNames(namespace: String): Set<String> {
        val index = namespaces.indexOf(namespace)
        require(index >= 0) { "Namespace $namespace does not exist!" }
        return classes.mapTo(HashSet()) { it.names[index] }
    }

    fun classNames(jar: File): Set<String> = java.util.jar.JarFile(jar).use { archive ->
        archive.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".class") }
            .mapTo(HashSet()) { it.name.removeSuffix(".class") }
    }
}

private class Recording(private val from: String, private val to: String) {
    val classes = HashMap<String, String>()
    val methods = HashMap<String, String>()
    val fields = HashMap<String, String>()
    val relocations = HashMap<String, String>()

    fun toTable() = PayloadRemapTable(from, to, classes, methods, fields)

    fun toShippedTable(): PayloadRemapTable {
        val collisions = relocations.keys.intersect(classes.keys)
        check(collisions.isEmpty()) { "Relocation overlaps mapped classes: $collisions" }
        return PayloadRemapTable(from, to, classes + relocations, methods, fields)
    }
}

private class RecordingRemapper(
    private val delegate: Remapper,
    private val declarations: DeclarationIndex,
    private val mappedClasses: Set<String>,
    private val relocation: PrefixRelocation,
    /** Classes that travel with what is being rewritten and therefore are never relocated. */
    private val ownClasses: Set<String>,
    private val recording: Recording,
) : TrackingRemapper() {
    override fun map(internalName: String): String {
        val mapped = delegate.map(internalName)
        if (mapped != internalName) recording.classes[internalName] = mapped

        if (mapped !in ownClasses) {
            val relocated = relocation.relocate(mapped)
            if (relocated != mapped) recording.relocations[internalName] = relocated
        }

        return track(internalName, mapped)
    }

    override fun mapMethodName(owner: String, name: String, descriptor: String): String {
        if (name == "<init>" || name == "<clinit>") return name
        if (!descriptor.startsWith("(")) return mapFieldName(owner, name, descriptor)
        if (ownsUninheritedMethod(owner, name, descriptor)) return name

        val mapped = delegate.mapMethodName(owner, name, descriptor)
        if (mapped != name) recording.methods["$owner.$name$descriptor"] = mapped
        return track(name, mapped)
    }

    override fun mapFieldName(owner: String, name: String, descriptor: String?): String {
        if (ownsField(owner, name)) return name

        val mapped = delegate.mapFieldName(owner, name, descriptor)
        if (mapped != name) recording.fields["$owner.$name"] = mapped
        return track(name, mapped)
    }

    override fun mapRecordComponentName(owner: String, name: String, descriptor: String): String =
        mapFieldName(owner, name, descriptor)

    override fun mapSignature(signature: String?, typeSignature: Boolean): String? =
        if (signature?.isEmpty() == true) null else super.mapSignature(signature, typeSignature)

    private fun ownsUninheritedMethod(owner: String, name: String, descriptor: String): Boolean {
        if (owner in mappedClasses) return false
        val access = declarations.methodAccess(owner, name, descriptor) ?: return false
        return access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC) != 0
    }

    private fun ownsField(owner: String, name: String): Boolean =
        owner !in mappedClasses && declarations.declaresField(owner, name)
}

private class DeclarationIndex(private val loader: (String) -> ByteArray?) {
    private class Declarations(val methods: Map<String, Int>, val fields: Set<String>)

    private val cache = HashMap<String, Declarations?>()

    fun methodAccess(owner: String, name: String, descriptor: String): Int? =
        declarations(owner)?.methods?.get(name + descriptor)

    fun declaresField(owner: String, name: String): Boolean =
        declarations(owner)?.fields?.contains(name) == true

    private fun declarations(owner: String): Declarations? = cache.getOrPut(owner) {
        val bytes = loader(owner) ?: return@getOrPut null
        val methods = HashMap<String, Int>()
        val fields = HashSet<String>()

        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                methods[name + descriptor] = access
                return null
            }

            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): FieldVisitor? {
                fields += name
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)

        Declarations(methods, fields)
    }
}
