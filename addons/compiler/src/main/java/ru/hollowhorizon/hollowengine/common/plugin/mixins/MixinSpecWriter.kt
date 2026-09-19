package ru.hollowhorizon.hollowengine.common.plugin.mixins

import org.objectweb.asm.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinSpecLayout
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinSpecLayout.Kind
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinSpecLayout.Point

/**
 * Resolves what a mixin script declared against the classes it compiles with, and writes the spec class
 * that bootstrap generates mixins from.
 */
class MixinSpecWriter(private val findClass: (String) -> ByteArray?) {
    class Output(val spec: ByteArray?, val errors: List<MixinError>)

    private data class Member(
        val opcode: Int,
        val owner: String,
        val name: String,
        val descriptor: String,
        val isInterface: Boolean,
    ) {
        val isField: Boolean get() = opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC
    }

    private class Handler(val declaration: MixinDeclaration, val target: Member, val point: Member?)

    private class ResolutionException(message: String) : Exception(message)

    private val nodes = HashMap<String, ClassNode?>()

    /** The spec class of [scriptClass], or the errors that prevent writing it. `null` spec means no mixins. */
    fun write(scriptClass: String, clientOnly: Boolean, declarations: List<MixinDeclaration>): Output {
        if (declarations.isEmpty()) return Output(null, emptyList())
        val errors = mutableListOf<MixinError>()
        val handlers = declarations.mapNotNull { declaration ->
            try {
                resolve(declaration)
            } catch (exception: ResolutionException) {
                errors += MixinError(exception.message.orEmpty(), declaration.location)
                null
            }
        }
        if (errors.isNotEmpty()) return Output(null, errors)
        return Output(spec(scriptClass, clientOnly, handlers), emptyList())
    }

    private fun resolve(declaration: MixinDeclaration): Handler {
        val (method, target) = targetMethod(declaration)
        val point = declaration.member?.let { pointMember(declaration, method, it) }
        val isConstructor = method.name == "<init>"

        when (declaration.kind) {
            Kind.INJECT -> if (isConstructor && declaration.point !in CONSTRUCTOR_POINTS) {
                fail("A constructor can only be injected into at RETURN or TAIL")
            }

            Kind.MODIFY_RETURN_VALUE -> checkValue(
                declaration,
                Type.getReturnType(method.desc),
                "${method.name} returns"
            )

            Kind.MODIFY_EXPRESSION_VALUE -> checkValue(declaration, valueType(point!!), "${point.name} gives")
            Kind.WRAP_OPERATION -> Unit
            Kind.WRAP_METHOD -> if (isConstructor) fail("A constructor cannot be wrapped")
        }
        return Handler(declaration, target, point)
    }

    private fun targetMethod(declaration: MixinDeclaration): Pair<MethodNode, Member> {
        val owner = declaration.target
        val node = node(owner) ?: fail("Cannot find class ${owner.dotted()}")
        if (node.access and Opcodes.ACC_INTERFACE != 0) fail("${owner.dotted()} is an interface; mixins can only go into classes")

        val (name, descriptor) = split(declaration.method)
        val candidates = node.methods.filter { it.name == name && (descriptor == null || it.desc == descriptor) }
        val matches = candidates.filterNot { it.access and Opcodes.ACC_BRIDGE != 0 }.ifEmpty { candidates }
        if (matches.isEmpty()) {
            val declaredBy =
                supertypes(owner).firstOrNull { type -> node(type)?.methods?.any { it.name == name } == true }
            fail(
                if (declaredBy != null) "${owner.simple()}.$name is inherited from ${declaredBy.dotted()}; mix into ${declaredBy.simple()} instead"
                else "${owner.dotted()} has no method '${declaration.method}'",
            )
        }
        if (matches.size > 1) {
            fail("${owner.dotted()} has ${matches.size} methods named '$name'; name one with its descriptor: " + matches.joinToString { "\"$name${it.desc}\"" })
        }

        val method = matches.single()
        val opcode = when {
            method.access and Opcodes.ACC_STATIC != 0 -> Opcodes.INVOKESTATIC
            method.access and Opcodes.ACC_PRIVATE != 0 || name == "<init>" -> Opcodes.INVOKESPECIAL
            else -> Opcodes.INVOKEVIRTUAL
        }
        return method to Member(opcode, owner, method.name, method.desc, isInterface = false)
    }

    /** One call or field read inside [method] that [point] means, found in its bytecode. */
    private fun pointMember(declaration: MixinDeclaration, method: MethodNode, point: MixinMemberPoint): Member {
        val (name, descriptor) = split(point.member)
        val occurrences = method.instructions.mapNotNull { instruction ->
            when {
                !point.isField && instruction is MethodInsnNode && instruction.name == name -> Member(
                    instruction.opcode,
                    instruction.owner,
                    instruction.name,
                    instruction.desc,
                    instruction.itf
                )

                point.isField && instruction is FieldInsnNode && instruction.name == name && (instruction.opcode == Opcodes.GETFIELD || instruction.opcode == Opcodes.GETSTATIC) -> Member(
                    instruction.opcode,
                    instruction.owner,
                    instruction.name,
                    instruction.desc,
                    isInterface = false
                )

                else -> null
            }
        }.filter { (descriptor == null || it.descriptor == descriptor) && isSubtype(it.owner, point.owner) }

        val action = if (point.isField) "reads the field" else "calls"
        val where = "${declaration.target.simple()}.${method.name}"
        if (occurrences.isEmpty()) fail("$where never $action ${point.owner.simple()}.${point.member}")
        val distinct = occurrences.distinct()
        if (distinct.size > 1) {
            fail(
                "$where $action ${point.member} on several owners or with several descriptors: " + distinct.joinToString { "${it.owner.simple()}.${it.name}${it.descriptor}" } + "; add the descriptor or name a more specific owner",
            )
        }
        if (declaration.ordinal >= occurrences.size) {
            fail("$where $action ${point.member} ${occurrences.size} time(s); ordinal ${declaration.ordinal} is out of range")
        }
        return distinct.single()
    }

    private fun valueType(member: Member): Type =
        if (member.isField) Type.getType(member.descriptor) else Type.getReturnType(member.descriptor)

    /** Catches a body typed for a different value than the one it replaces, which would fail at run time. */
    private fun checkValue(declaration: MixinDeclaration, type: Type, subject: String) {
        if (type == Type.VOID_TYPE) fail("$subject nothing, so there is no value to modify")
        val declared = declaration.valueType ?: return
        if (declared == "kotlin.Any") return
        val primitive = PRIMITIVES[type.descriptor]
        val boxed = BOXED[type.internalName]
        val matches = when {
            primitive != null -> declared == primitive
            boxed != null -> declared == boxed || declared !in PRIMITIVES.values
            else -> true
        }
        if (!matches) fail("$subject ${type.className}, so the value is ${primitive ?: boxed ?: type.className}, not $declared")
    }

    private fun spec(scriptClass: String, clientOnly: Boolean, handlers: List<Handler>): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val name = scriptClass.replace('.', '/') + MixinSpecLayout.CLASS_SUFFIX
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
            name,
            null,
            OBJECT,
            null
        )
        writer.visitAnnotation(MixinSpecLayout.SPEC_ANNOTATION, false).apply {
            visit("version", MixinSpecLayout.VERSION)
            visit("clientOnly", clientOnly)
            visitEnd()
        }

        handlers.forEachIndexed { index, handler ->
            val declaration = handler.declaration
            val method = writer.visitMethod(
                Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
                "handler$$index",
                "()V",
                null,
                null
            )
            method.visitAnnotation(MixinSpecLayout.HANDLER_ANNOTATION, false).apply {
                visit("kind", declaration.kind.name)
                visit("key", declaration.key)
                visit("point", declaration.point.name)
                visit("shift", declaration.shift.name)
                visit("ordinal", declaration.ordinal)
                visitEnd()
            }
            method.visitCode()
            method.probe(handler.target)
            handler.point?.let { method.probe(it) }
            method.visitInsn(Opcodes.RETURN)
            method.visitMaxs(0, 0)
            method.visitEnd()
        }

        writer.visitEnd()
        return writer.toByteArray()
    }

    /** Refers to [member] with an instruction that is valid bytecode, though it is never run. */
    private fun MethodVisitor.probe(member: Member) {
        if (member.isField) {
            if (member.opcode == Opcodes.GETFIELD) visitInsn(Opcodes.ACONST_NULL)
            visitFieldInsn(member.opcode, member.owner, member.name, member.descriptor)
            pop(Type.getType(member.descriptor))
            return
        }
        if (member.opcode != Opcodes.INVOKESTATIC) visitInsn(Opcodes.ACONST_NULL)
        Type.getArgumentTypes(member.descriptor).forEach { pushZero(it) }
        visitMethodInsn(member.opcode, member.owner, member.name, member.descriptor, member.isInterface)
        pop(Type.getReturnType(member.descriptor))
    }

    private fun MethodVisitor.pushZero(type: Type) = visitInsn(
        when (type.sort) {
            Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Opcodes.ICONST_0
            Type.LONG -> Opcodes.LCONST_0
            Type.FLOAT -> Opcodes.FCONST_0
            Type.DOUBLE -> Opcodes.DCONST_0
            else -> Opcodes.ACONST_NULL
        },
    )

    private fun MethodVisitor.pop(type: Type) {
        when (type.size) {
            1 -> visitInsn(Opcodes.POP)
            2 -> visitInsn(Opcodes.POP2)
        }
    }

    private fun isSubtype(type: String, expected: String): Boolean =
        expected == OBJECT || type == expected || expected in supertypes(type)

    /** Superclasses first, then interfaces, as far as the classpath can tell. */
    private fun supertypes(type: String): List<String> {
        val found = LinkedHashSet<String>()
        fun visit(name: String) {
            val node = node(name) ?: return
            listOfNotNull(node.superName).plus(node.interfaces.orEmpty()).forEach { parent ->
                if (found.add(parent)) visit(parent)
            }
        }
        visit(type)
        return found.toList()
    }

    private fun node(name: String): ClassNode? = nodes.getOrPut(name) {
        val bytes = findClass(name) ?: ClassLoader.getSystemResourceAsStream("$name.class")?.use { it.readBytes() }
        bytes?.let {
            ClassNode().also { node ->
                ClassReader(it).accept(
                    node,
                    ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG
                )
            }
        }
    }

    private fun split(member: String): Pair<String, String?> {
        val start =
            member.indexOf('(').takeIf { it >= 0 } ?: member.indexOf(':').takeIf { it >= 0 } ?: return member to null
        val descriptor = member.substring(start).removePrefix(":")
        return member.substring(0, start) to descriptor
    }

    private fun fail(message: String): Nothing = throw ResolutionException(message)

    private fun String.dotted(): String = replace('/', '.')

    private fun String.simple(): String = substringAfterLast('/')

    private companion object {
        const val OBJECT = "java/lang/Object"

        val CONSTRUCTOR_POINTS = setOf(Point.RETURN, Point.TAIL, Point.INVOKE, Point.FIELD)

        val PRIMITIVES = mapOf(
            "Z" to "kotlin.Boolean", "B" to "kotlin.Byte", "C" to "kotlin.Char", "S" to "kotlin.Short",
            "I" to "kotlin.Int", "J" to "kotlin.Long", "F" to "kotlin.Float", "D" to "kotlin.Double",
        )

        val BOXED = mapOf(
            "java/lang/Boolean" to "kotlin.Boolean", "java/lang/Byte" to "kotlin.Byte",
            "java/lang/Character" to "kotlin.Char", "java/lang/Short" to "kotlin.Short",
            "java/lang/Integer" to "kotlin.Int", "java/lang/Long" to "kotlin.Long",
            "java/lang/Float" to "kotlin.Float", "java/lang/Double" to "kotlin.Double",
        )
    }
}
