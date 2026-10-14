package ru.hollowhorizon.hollowengine.common.ide.session.definition

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.load.kotlin.KotlinBinaryClassCache
import org.jetbrains.kotlin.load.kotlin.KotlinClassFinder
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader.Kind
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.MetadataVersion
import org.jetbrains.kotlin.metadata.deserialization.NameResolver
import org.jetbrains.kotlin.metadata.deserialization.TypeTable
import org.jetbrains.kotlin.metadata.deserialization.contextReceiverTypes
import org.jetbrains.kotlin.metadata.deserialization.receiverType
import org.jetbrains.kotlin.metadata.deserialization.type
import org.jetbrains.kotlin.metadata.deserialization.varargElementType
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type

/** Mangled functions are absent from Kotlin light classes; metadata retains their exact JVM signatures. */
internal fun kotlinJvmFunction(
    classFile: VirtualFile,
    target: SourceDeclarationIdentity,
): Pair<VirtualFile, SourceDeclarationIdentity>? {
    if (target.kind != SourceDeclarationIdentity.Kind.FUNCTION) return null
    val binary = if (target.owners.isEmpty()) classFile else {
        classFile.parent?.findChild(target.owners.joinToString("$") + ".class") ?: return null
    }
    val result = KotlinBinaryClassCache.getKotlinBinaryClassOrClassFileContent(binary, MetadataVersion.INSTANCE)
        as? KotlinClassFinder.Result.KotlinClass ?: return null
    val header = result.kotlinJvmBinaryClass.classHeader
    val data = header.data ?: return null
    val strings = header.strings ?: return null
    val metadata = when (header.kind) {
        Kind.CLASS -> {
            val (resolver, proto) = JvmProtoBufUtil.readClassDataFrom(data, strings)
            FunctionMetadata(resolver, TypeTable(proto.typeTable), proto.functionList, proto.typeParameterList)
        }
        Kind.FILE_FACADE, Kind.MULTIFILE_CLASS_PART -> {
            val (resolver, proto) = JvmProtoBufUtil.readPackageDataFrom(data, strings)
            FunctionMetadata(resolver, TypeTable(proto.typeTable), proto.functionList, emptyList())
        }
        else -> return null
    }
    val candidates = metadata.functions.filter { metadata.resolver.getString(it.name) == target.name }.mapNotNull { proto ->
        val table = if (proto.hasTypeTable()) TypeTable(proto.typeTable) else metadata.table
        val typeNames = (metadata.typeParameters + proto.typeParameterList).associate { it.id to metadata.resolver.getString(it.name) }
        fun sourceType(type: ProtoBuf.Type): String? = metadataSourceType(type, metadata.resolver, table, typeNames)
        val contextTypes = proto.contextParameterList.map { it.type(table) }.ifEmpty { proto.contextReceiverTypes(table) }
        val types = contextTypes +
            listOfNotNull(proto.receiverType(table))
        val parameters = types.map { sourceType(it) ?: return@mapNotNull null } + proto.valueParameterList.map {
            val vararg = it.varargElementType(table)
            (sourceType(vararg ?: it.type(table)) ?: return@mapNotNull null) + if (vararg != null) "[]" else ""
        }
        val signature = JvmProtoBufUtil.getJvmMethodSignature(proto, metadata.resolver, table) ?: return@mapNotNull null
        parameters to signature
    }
    val exact = candidates.filter { sourceParametersMatch(it.first, target.parameters, allowShortNames = false) }
    val signature = if (exact.isNotEmpty()) exact.singleOrNull()?.second else {
        candidates.filter { sourceParametersMatch(it.first, target.parameters, allowShortNames = true) }.singleOrNull()?.second
    } ?: return null
    val owners = ClassReader(binary.contentsToByteArray()).className.substringAfterLast('/').split('$')
    return binary to target.copy(name = signature.name, owners = owners,
        parameters = Type.getArgumentTypes(signature.desc).map { it.className })
}

private data class FunctionMetadata(
    val resolver: NameResolver,
    val table: TypeTable,
    val functions: List<ProtoBuf.Function>,
    val typeParameters: List<ProtoBuf.TypeParameter>,
)

private fun metadataSourceType(
    type: ProtoBuf.Type,
    resolver: NameResolver,
    table: TypeTable,
    typeNames: Map<Int, String>,
): String? {
    val name = when {
        type.hasClassName() -> resolver.getQualifiedClassName(type.className).replace('/', '.')
        type.hasTypeAliasName() -> resolver.getQualifiedClassName(type.typeAliasName).replace('/', '.')
        type.hasTypeParameterName() -> resolver.getString(type.typeParameterName)
        type.hasTypeParameter() -> typeNames[type.typeParameter] ?: return null
        else -> return null
    }
    if (name.startsWith("kotlin.Function")) return name.replace("kotlin.Function", "kotlin.jvm.functions.Function")
    if (name.startsWith("kotlin.coroutines.SuspendFunction")) {
        val arity = name.removePrefix("kotlin.coroutines.SuspendFunction").toIntOrNull() ?: return null
        return "kotlin.jvm.functions.Function${arity + 1}"
    }
    if (name == "kotlin.Array") {
        val argument = type.argumentList.singleOrNull()?.type(table)
        val element = argument?.let { metadataSourceType(it, resolver, table, typeNames) } ?: "kotlin.Any"
        return "kotlin.Array<$element>"
    }
    return name + if (type.nullable) "?" else ""
}
