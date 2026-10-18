package ru.hollowhorizon.hollowengine.common.utils.expressions

import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/** Reads one named slot of a bag; the key is fixed when the expression is compiled. */
private fun interface BagGet {
    fun get(owner: Any?): Any?
}

private fun interface BagSet {
    fun set(owner: Any?, value: Any?)
}

/**
 * Compiles baked expressions to bytecode.
 */
internal object ExpressionCompiler {
    private const val CLASS_NAME = "ru/hollowhorizon/hollowengine/common/utils/expressions/CompiledExpressions"
    private const val INDEX = "index"

    private val FLOAT_EXPRESSION = Type.getInternalName(FloatExpression::class.java)
    private val COERCIONS = Type.getInternalName(Coercions::class.java)
    private val FLOAT_READER = Type.getInternalName(FloatReader::class.java)
    private val BOOL_READER = Type.getInternalName(BoolReader::class.java)
    private val OBJECT_READER = Type.getInternalName(ObjectReader::class.java)
    private val FLOAT1 = Type.getInternalName(Float1::class.java)
    private val FLOAT2 = Type.getInternalName(Float2::class.java)
    private val FLOAT3 = Type.getInternalName(Float3::class.java)
    private val GENERIC = Type.getInternalName(GenericInvoker::class.java)
    private val BAG_GET = Type.getInternalName(BagGet::class.java)
    private val BAG_SET = Type.getInternalName(BagSet::class.java)

    /**
     * Is bytecode backend covers this tree. Lists, indexing and `??` only appear in the
     * dialogue dialect, which is interpreted, so they are left to the interpreter, instead than
     * duplicating the expression language.
     */
    fun supports(ir: Ir): Boolean = when (ir) {
        is Ir.ListOf, is Ir.Index, is Ir.Coalesce, is Ir.Operation -> false
        is Ir.Const -> ir.type.isPrimitive
        is Ir.FieldRead -> ir.owner?.let(::supports) ?: true
        is Ir.MethodCall -> ir.arguments.all(::supports) && (ir.owner?.let(::supports) ?: true)
        is Ir.BagRead -> supports(ir.owner)
        is Ir.BagWrite -> supports(ir.owner) && supports(ir.value)
        is Ir.Negate -> supports(ir.operand)
        is Ir.Not -> supports(ir.operand)
        is Ir.Arithmetic -> supports(ir.left) && supports(ir.right)
        is Ir.Compare -> supports(ir.left) && supports(ir.right)
        is Ir.Logical -> supports(ir.left) && supports(ir.right)
        is Ir.ObjectEquals -> supports(ir.left) && supports(ir.right)
        is Ir.Conditional -> supports(ir.condition) && supports(ir.ifTrue) && supports(ir.ifFalse)
        is Ir.Sequence -> ir.statements.all(::supports)
        is Ir.Convert -> supports(ir.value)
    }

    /**
     * Compiles [expressions] into one class. Entries the backend does not cover come back as null.
     */
    @Suppress("UNCHECKED_CAST")
    fun compile(expressions: List<Ir?>): List<FloatExpression<Any?>?> {
        val compilable = expressions.map { ir -> ir?.takeIf { it.type.isPrimitive && supports(it) } }
        if (compilable.none { it != null }) return expressions.map { null }

        val pool = Pool()
        val writer = Writer()
        writer.visit(
            V21, ACC_PUBLIC or ACC_FINAL or ACC_SUPER, CLASS_NAME, null, "java/lang/Object",
            arrayOf(FLOAT_EXPRESSION),
        )
        writer.visitField(ACC_PRIVATE or ACC_FINAL, INDEX, "I", null, null).visitEnd()
        constructor(writer)
        dispatch(writer, compilable)

        compilable.forEachIndexed { index, ir ->
            if (ir == null) return@forEachIndexed
            val mv = writer.visitMethod(ACC_PRIVATE or ACC_STATIC, "e$index", "(Ljava/lang/Object;)F", null, null)
            mv.visitCode()
            Emitter(mv, pool).float(ir)
            mv.visitInsn(FRETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
        writer.visitEnd()

        val lookup = MethodHandles.lookup()
            .defineHiddenClassWithClassData(writer.toByteArray(), pool.build(), true)
        val create = lookup.findConstructor(
            lookup.lookupClass(),
            MethodType.methodType(Void.TYPE, Int::class.javaPrimitiveType),
        )

        return compilable.mapIndexed { index, ir ->
            ir?.let { create(index) as FloatExpression<Any?> }
        }
    }

    private fun constructor(writer: ClassWriter) {
        val mv = writer.visitMethod(ACC_PUBLIC, "<init>", "(I)V", null, null)
        mv.visitCode()
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitVarInsn(ILOAD, 1)
        mv.visitFieldInsn(PUTFIELD, CLASS_NAME, INDEX, "I")
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun dispatch(writer: ClassWriter, expressions: List<Ir?>) {
        val mv = writer.visitMethod(ACC_PUBLIC, "eval", "(Ljava/lang/Object;)F", null, null)
        val default = Label()
        val labels = Array(expressions.size) { Label() }
        mv.visitCode()
        mv.visitVarInsn(ALOAD, 0)
        mv.visitFieldInsn(GETFIELD, CLASS_NAME, INDEX, "I")
        mv.visitTableSwitchInsn(0, expressions.size - 1, default, *labels)
        expressions.forEachIndexed { index, ir ->
            mv.visitLabel(labels[index])
            if (ir == null) {
                mv.visitInsn(FCONST_0)
            } else {
                mv.visitVarInsn(ALOAD, 1)
                mv.visitMethodInsn(INVOKESTATIC, CLASS_NAME, "e$index", "(Ljava/lang/Object;)F", false)
            }
            mv.visitInsn(FRETURN)
        }
        mv.visitLabel(default)
        mv.visitInsn(FCONST_0)
        mv.visitInsn(FRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private class Writer : ClassWriter(COMPUTE_FRAMES or COMPUTE_MAXS) {
        override fun getCommonSuperClass(first: String, second: String): String = try {
            super.getCommonSuperClass(first, second)
        } catch (_: Throwable) {
            "java/lang/Object"
        }
    }

    private class Pool {
        private val values = mutableListOf<Any?>()
        private val constants = HashMap<Any, ConstantDynamic>()

        fun constant(value: Any, type: String): ConstantDynamic = constants.getOrPut(value) {
            values += value
            ConstantDynamic("_", "L$type;", CLASS_DATA, values.size - 1)
        }

        fun build(): List<Any?> = values.toList()

        private companion object {
            val CLASS_DATA = Handle(
                H_INVOKESTATIC, "java/lang/invoke/MethodHandles", "classDataAt",
                $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;I)Ljava/lang/Object;",
                false,
            )
        }
    }

    private class Emitter(private val mv: MethodVisitor, private val pool: Pool) {

        fun float(ir: Ir) {
            when (ir) {
                is Ir.Const -> mv.visitLdcInsn(Coercions.toFloat(ir.constant))

                is Ir.FieldRead -> when (val member = ir.member) {
                    is Member.FloatField -> {
                        loadConstant(member.read, FLOAT_READER)
                        owner(ir.owner)
                        mv.visitMethodInsn(INVOKEINTERFACE, FLOAT_READER, "read", "(Ljava/lang/Object;)F", true)
                    }

                    is Member.BoolField -> boolToFloat { bool(ir) }
                    is Member.ObjectField -> toFloat { obj(ir) }
                }

                is Ir.MethodCall -> floatCall(ir)
                is Ir.Negate -> {
                    float(ir.operand)
                    mv.visitInsn(FNEG)
                }

                is Ir.Arithmetic -> {
                    float(ir.left)
                    float(ir.right)
                    mv.visitInsn(
                        when (ir.op) {
                            BinaryOp.ADD -> FADD
                            BinaryOp.SUBTRACT -> FSUB
                            BinaryOp.MULTIPLY -> FMUL
                            BinaryOp.DIVIDE -> FDIV
                            else -> FREM
                        }
                    )
                }

                is Ir.Conditional -> branch({ bool(ir.condition) }, { float(ir.ifTrue) }, { float(ir.ifFalse) })

                is Ir.Sequence -> {
                    ir.statements.dropLast(1).forEach { discard(it) }
                    float(ir.statements.last())
                }

                is Ir.Convert -> when (ir.value.type) {
                    ExprType.Primitive.BOOL -> boolToFloat { bool(ir.value) }
                    else -> toFloat { obj(ir.value) }
                }

                else -> if (ir.type === ExprType.Primitive.BOOL) boolToFloat { bool(ir) } else toFloat { obj(ir) }
            }
        }

        fun bool(ir: Ir) {
            when (ir) {
                is Ir.Const -> mv.visitInsn(if (ir.constantBool() == true) ICONST_1 else ICONST_0)

                is Ir.FieldRead -> when (val member = ir.member) {
                    is Member.BoolField -> {
                        loadConstant(member.read, BOOL_READER)
                        owner(ir.owner)
                        mv.visitMethodInsn(INVOKEINTERFACE, BOOL_READER, "read", "(Ljava/lang/Object;)Z", true)
                    }

                    is Member.FloatField -> floatToBool { float(ir) }
                    is Member.ObjectField -> toBool { obj(ir) }
                }

                is Ir.Not -> {
                    bool(ir.operand)
                    mv.visitInsn(ICONST_1)
                    mv.visitInsn(IXOR)
                }

                is Ir.Compare -> {
                    float(ir.left)
                    float(ir.right)
                    val (compare, jump) = when (ir.op) {
                        BinaryOp.LT -> FCMPG to IFLT
                        BinaryOp.LTE -> FCMPG to IFLE
                        BinaryOp.GT -> FCMPL to IFGT
                        BinaryOp.GTE -> FCMPL to IFGE
                        BinaryOp.EQ -> FCMPL to IFEQ
                        else -> FCMPL to IFNE
                    }
                    mv.visitInsn(compare)
                    jumpToBoolean(jump)
                }

                is Ir.Logical -> {
                    val shortCircuit = Label()
                    val end = Label()
                    bool(ir.left)
                    mv.visitJumpInsn(if (ir.op == BinaryOp.AND) IFEQ else IFNE, shortCircuit)
                    bool(ir.right)
                    mv.visitJumpInsn(GOTO, end)
                    mv.visitLabel(shortCircuit)
                    mv.visitInsn(if (ir.op == BinaryOp.AND) ICONST_0 else ICONST_1)
                    mv.visitLabel(end)
                }

                is Ir.ObjectEquals -> {
                    obj(ir.left)
                    obj(ir.right)
                    mv.visitMethodInsn(
                        INVOKESTATIC, "java/util/Objects", "equals",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z", false,
                    )
                    if (ir.negate) {
                        mv.visitInsn(ICONST_1)
                        mv.visitInsn(IXOR)
                    }
                }

                is Ir.Conditional -> branch({ bool(ir.condition) }, { bool(ir.ifTrue) }, { bool(ir.ifFalse) })

                is Ir.Sequence -> {
                    ir.statements.dropLast(1).forEach { discard(it) }
                    bool(ir.statements.last())
                }

                is Ir.Convert -> when (ir.value.type) {
                    ExprType.Primitive.FLOAT -> floatToBool { float(ir.value) }
                    else -> toBool { obj(ir.value) }
                }

                else -> if (ir.type === ExprType.Primitive.FLOAT) floatToBool { float(ir) } else toBool { obj(ir) }
            }
        }

        fun obj(ir: Ir) {
            when (ir) {
                is Ir.Const -> when (ir.type) {
                    ExprType.Primitive.FLOAT -> boxFloat { float(ir) }
                    ExprType.Primitive.BOOL -> boxBool { bool(ir) }
                    else -> loadConstant(ir.constant ?: return mv.visitInsn(ACONST_NULL), "java/lang/Object")
                }

                is Ir.FieldRead -> when (val member = ir.member) {
                    is Member.ObjectField -> {
                        loadConstant(member.read, OBJECT_READER)
                        owner(ir.owner)
                        mv.visitMethodInsn(
                            INVOKEINTERFACE, OBJECT_READER, "read",
                            "(Ljava/lang/Object;)Ljava/lang/Object;", true,
                        )
                    }

                    is Member.FloatField -> boxFloat { float(ir) }
                    is Member.BoolField -> boxBool { bool(ir) }
                }

                is Ir.MethodCall -> if (ir.method.isAllFloat) boxFloat { float(ir) } else genericCall(ir)

                is Ir.BagRead -> {
                    val bag = ir.bag
                    val key = ir.key
                    loadConstant(BagGet { owner -> bag.read(owner, key) }, BAG_GET)
                    obj(ir.owner)
                    mv.visitMethodInsn(INVOKEINTERFACE, BAG_GET, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true)
                }

                is Ir.BagWrite -> {
                    val bag = ir.bag
                    val key = ir.key
                    val write = bag.write!!
                    loadConstant(BagSet { owner, value -> write(owner, key, value) }, BAG_SET)
                    obj(ir.owner)
                    obj(ir.value)
                    mv.visitInsn(DUP_X2)
                    mv.visitMethodInsn(
                        INVOKEINTERFACE, BAG_SET, "set",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", true,
                    )
                }

                is Ir.Negate, is Ir.Arithmetic -> boxFloat { float(ir) }
                is Ir.Not, is Ir.Compare, is Ir.Logical, is Ir.ObjectEquals -> boxBool { bool(ir) }

                is Ir.Conditional -> branch({ bool(ir.condition) }, { obj(ir.ifTrue) }, { obj(ir.ifFalse) })

                is Ir.Sequence -> {
                    ir.statements.dropLast(1).forEach { discard(it) }
                    obj(ir.statements.last())
                }

                is Ir.Convert -> when (ir.type) {
                    ExprType.Primitive.FLOAT -> boxFloat { float(ir) }
                    ExprType.Primitive.BOOL -> boxBool { bool(ir) }
                    else -> obj(ir.value)
                }

                else -> error("Cannot emit ${ir::class.simpleName} as an object")
            }
        }

        private fun floatCall(ir: Ir.MethodCall) {
            val method = ir.method
            when {
                method.float1 != null -> {
                    loadConstant(method.float1, FLOAT1)
                    float(ir.arguments[0])
                    mv.visitMethodInsn(INVOKEINTERFACE, FLOAT1, "invoke", "(F)F", true)
                }

                method.float2 != null -> {
                    loadConstant(method.float2, FLOAT2)
                    float(ir.arguments[0])
                    float(ir.arguments[1])
                    mv.visitMethodInsn(INVOKEINTERFACE, FLOAT2, "invoke", "(FF)F", true)
                }

                method.float3 != null -> {
                    loadConstant(method.float3, FLOAT3)
                    float(ir.arguments[0])
                    float(ir.arguments[1])
                    float(ir.arguments[2])
                    mv.visitMethodInsn(INVOKEINTERFACE, FLOAT3, "invoke", "(FFF)F", true)
                }

                else -> toFloat { genericCall(ir) }
            }
        }

        private fun genericCall(ir: Ir.MethodCall) {
            loadConstant(ir.method.generic, GENERIC)
            owner(ir.owner)
            mv.visitLdcInsn(ir.arguments.size)
            mv.visitTypeInsn(ANEWARRAY, "java/lang/Object")
            ir.arguments.forEachIndexed { index, argument ->
                mv.visitInsn(DUP)
                mv.visitLdcInsn(index)
                obj(argument)
                mv.visitInsn(AASTORE)
            }
            mv.visitMethodInsn(
                INVOKEINTERFACE, GENERIC, "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", true,
            )
        }

        private fun owner(ir: Ir?) {
            if (ir == null) mv.visitVarInsn(ALOAD, 0) else obj(ir)
        }

        private fun discard(ir: Ir) {
            obj(ir)
            mv.visitInsn(POP)
        }

        private fun loadConstant(value: Any, type: String) {
            mv.visitLdcInsn(pool.constant(value, type))
        }

        private inline fun branch(condition: () -> Unit, ifTrue: () -> Unit, ifFalse: () -> Unit) {
            val elseLabel = Label()
            val end = Label()
            condition()
            mv.visitJumpInsn(IFEQ, elseLabel)
            ifTrue()
            mv.visitJumpInsn(GOTO, end)
            mv.visitLabel(elseLabel)
            ifFalse()
            mv.visitLabel(end)
        }

        private fun jumpToBoolean(jump: Int) {
            val trueLabel = Label()
            val end = Label()
            mv.visitJumpInsn(jump, trueLabel)
            mv.visitInsn(ICONST_0)
            mv.visitJumpInsn(GOTO, end)
            mv.visitLabel(trueLabel)
            mv.visitInsn(ICONST_1)
            mv.visitLabel(end)
        }

        private inline fun boolToFloat(value: () -> Unit) {
            value()
            val trueLabel = Label()
            val end = Label()
            mv.visitJumpInsn(IFNE, trueLabel)
            mv.visitInsn(FCONST_0)
            mv.visitJumpInsn(GOTO, end)
            mv.visitLabel(trueLabel)
            mv.visitInsn(FCONST_1)
            mv.visitLabel(end)
        }

        private inline fun floatToBool(value: () -> Unit) {
            value()
            mv.visitInsn(FCONST_0)
            mv.visitInsn(FCMPL)
            jumpToBoolean(IFNE)
        }

        private inline fun toBool(value: () -> Unit) {
            value()
            mv.visitMethodInsn(INVOKESTATIC, COERCIONS, "toBool", "(Ljava/lang/Object;)Z", false)
        }

        private inline fun boxFloat(value: () -> Unit) {
            value()
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)
        }

        private inline fun boxBool(value: () -> Unit) {
            value()
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
        }

        private inline fun toFloat(value: () -> Unit) {
            value()
            mv.visitMethodInsn(INVOKESTATIC, COERCIONS, "toFloat", "(Ljava/lang/Object;)F", false)
        }
    }
}
