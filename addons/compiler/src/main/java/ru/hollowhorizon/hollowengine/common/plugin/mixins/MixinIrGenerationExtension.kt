package ru.hollowhorizon.hollowengine.common.plugin.mixins

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinSpecLayout.Kind
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinSpecLayout.Point
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinSpecLayout.Shift
import java.security.MessageDigest

private const val DSL = "ru.hollowhorizon.hollowengine.common.scripting.mixins"

/**
 * Reads the mixin declarations of a script and gives each body the key it is bound by.
 */
class MixinIrGenerationExtension(private val collector: MixinDeclarationCollector) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val reader = DeclarationReader(pluginContext)
        moduleFragment.transform(reader, null)
        reader.assignKeys()
    }

    private inner class DeclarationReader(private val context: IrPluginContext) : IrElementTransformerVoid() {
        private var file: IrFile? = null
        private val handlers = mutableListOf<Pair<IrCall, MixinDeclaration>>()

        override fun visitFile(declaration: IrFile): IrFile {
            file = declaration
            return super.visitFile(declaration)
        }

        override fun visitCall(expression: IrCall): IrExpression {
            if (expression.callee.isMember("MixinScript", "mixin")) readMixin(expression)
            return super.visitCall(expression)
        }

        private fun readMixin(call: IrCall) {
            val target =
                call.typeArguments.firstOrNull()?.internalName() ?: return error(call, "mixin<T> needs a class as T")
            statements(call, "block").forEach { statement ->
                val declaration = statement.asCall() ?: return@forEach
                val function = declaration.callee
                if (!function.isMemberOf("MixinTarget")) return error(
                    declaration,
                    "Only mixin declarations may appear inside mixin { }"
                )
                val method = declaration.string("method") ?: return error(
                    declaration,
                    "The method name must be a string constant"
                )
                when (function.name.asString()) {
                    "inject" -> readLowLevel(declaration, target, method, Kind.INJECT, Point.HEAD, valueType = null)
                    "modifyReturnValue" -> readLowLevel(
                        declaration,
                        target,
                        method,
                        Kind.MODIFY_RETURN_VALUE,
                        Point.RETURN,
                        declaration.valueType(0)
                    )

                    "modifyExpressionValue" -> readLowLevel(
                        declaration,
                        target,
                        method,
                        Kind.MODIFY_EXPRESSION_VALUE,
                        null,
                        declaration.valueType(0)
                    )

                    "wrapOperation" -> readLowLevel(
                        declaration,
                        target,
                        method,
                        Kind.WRAP_OPERATION,
                        null,
                        valueType = null
                    )

                    "wrapMethod" -> readLowLevel(
                        declaration,
                        target,
                        method,
                        Kind.WRAP_METHOD,
                        Point.HEAD,
                        valueType = null
                    )

                    "method" -> readMethod(declaration, target, method)
                }
            }
        }

        private fun readLowLevel(
            declaration: IrCall,
            target: String,
            method: String,
            kind: Kind,
            defaultPoint: Point?,
            valueType: String?,
        ) {
            var point = defaultPoint
            var member: MixinMemberPoint? = null
            var ordinal = -1
            var shift = Shift.BEFORE
            val bodies = mutableListOf<IrCall>()

            statements(declaration, "block").forEach { statement ->
                val call = statement.asCall() ?: return@forEach
                when (call.callee.name.asString()) {
                    "at" -> {
                        val argument = call.argument("point")
                        val entry = argument.enumEntry()
                        if (entry != null) {
                            point = Point.valueOf(entry)
                            member = null
                            ordinal = call.int("ordinal") ?: -1
                        } else {
                            val at = readAt(
                                argument as? IrCall ?: return error(
                                    call,
                                    "The point must be written in place: at(call<Owner>(\"name\"))"
                                )
                            ) ?: return
                            point = if (at.member.isField) Point.FIELD else Point.INVOKE
                            member = at.member
                            ordinal = at.ordinal
                            shift = at.shift
                        }
                    }

                    "code" -> bodies += call
                    else -> return error(call, "Only at(...) and code { } may appear here")
                }
            }

            val resolvedPoint = point ?: return error(
                declaration,
                "${declaration.callee.name} needs a call or field point: at(call<Owner>(\"name\"))"
            )
            val resolvedMember = member
            when (kind) {
                Kind.MODIFY_RETURN_VALUE -> if (resolvedPoint != Point.RETURN) return error(
                    declaration,
                    "A return value can only be modified at RETURN"
                )

                Kind.MODIFY_EXPRESSION_VALUE -> if (resolvedMember == null) return error(
                    declaration,
                    "modifyExpressionValue needs a call or field point"
                )

                Kind.WRAP_OPERATION -> if (resolvedMember == null || resolvedMember.isField) return error(
                    declaration,
                    "wrapOperation needs a call point"
                )

                else -> Unit
            }
            if (bodies.isEmpty()) return error(declaration, "The declaration has no code { }")
            bodies.forEach { body ->
                handlers += body to MixinDeclaration(
                    "",
                    kind,
                    target,
                    method,
                    resolvedPoint,
                    resolvedMember,
                    ordinal,
                    shift,
                    valueType,
                    location(declaration),
                )
            }
        }

        private fun readMethod(declaration: IrCall, target: String, method: String) {
            statements(declaration, "block").forEach { statement ->
                val call = statement.asCall() ?: return@forEach
                if (!call.callee.isMemberOf("MethodMixins")) return error(
                    call,
                    "Only method mixins may appear inside method { }"
                )

                fun handler(
                    kind: Kind,
                    point: Point,
                    member: MixinMemberPoint? = null,
                    shift: Shift = Shift.BEFORE,
                    valueType: String? = null,
                ) {
                    val ordinal = call.int("ordinal") ?: -1
                    handlers += call to MixinDeclaration(
                        "",
                        kind,
                        target,
                        method,
                        point,
                        member,
                        ordinal,
                        shift,
                        valueType,
                        location(call)
                    )
                }

                fun callPoint(): MixinMemberPoint? {
                    val owner = call.typeArguments.firstOrNull()?.internalName() ?: return null.also {
                        error(
                            call,
                            "Name the class the method belongs to: ${call.callee.name}<Owner>(\"name\")"
                        )
                    }
                    val name = call.string("method") ?: return null.also {
                        error(
                            call,
                            "The method name must be a string constant"
                        )
                    }
                    return MixinMemberPoint(owner, name, isField = false)
                }

                when (call.callee.name.asString()) {
                    "before" -> handler(Kind.INJECT, Point.HEAD)
                    "after" -> handler(Kind.INJECT, Point.RETURN)
                    "beforeCall" -> callPoint()?.let { handler(Kind.INJECT, Point.INVOKE, it) }
                    "afterCall" -> callPoint()?.let { handler(Kind.INJECT, Point.INVOKE, it, Shift.AFTER) }
                    "replaceCall" -> callPoint()?.let { handler(Kind.WRAP_OPERATION, Point.INVOKE, it) }
                    "modifyCall" -> callPoint()?.let {
                        handler(
                            Kind.MODIFY_EXPRESSION_VALUE,
                            Point.INVOKE,
                            it,
                            valueType = call.valueType(1)
                        )
                    }

                    "returns" -> handler(Kind.MODIFY_RETURN_VALUE, Point.RETURN, valueType = call.valueType(0))
                    "wrap" -> handler(Kind.WRAP_METHOD, Point.HEAD)
                }
            }
        }

        private inner class AtPoint(val member: MixinMemberPoint, val ordinal: Int, val shift: Shift)

        private fun readAt(call: IrCall): AtPoint? {
            val function = call.callee
            val isField = when {
                function.isTopLevel("call") -> false
                function.isTopLevel("field") -> true
                else -> return null.also {
                    error(
                        call,
                        "The point must be call<Owner>(\"name\") or field<Owner>(\"name\")"
                    )
                }
            }
            val owner = call.typeArguments.firstOrNull()?.internalName() ?: return null.also {
                error(
                    call,
                    "Name the class the member belongs to: ${function.name}<Owner>(...)"
                )
            }
            val name = call.string(if (isField) "name" else "method") ?: return null.also {
                error(
                    call,
                    "The member name must be a string constant"
                )
            }
            val shift = call.argument("shift").enumEntry()?.let(Shift::valueOf) ?: Shift.BEFORE
            return AtPoint(MixinMemberPoint(owner, name, isField), call.int("ordinal") ?: -1, shift)
        }

        fun assignKeys() {
            val seen = HashMap<String, Int>()
            handlers.forEach { (call, declaration) ->
                val base = "${declaration.kind.name.lowercase()}-${fingerprint(declaration)}"
                val duplicate = seen.merge(base, 1, Int::plus)!! - 1
                val key = if (duplicate == 0) base else "$base-$duplicate"
                val parameter =
                    call.callee.parameters.single { it.kind == IrParameterKind.Regular && it.name.asString() == "generatedKey" }
                call.arguments[parameter] =
                    IrConstImpl.string(call.startOffset, call.endOffset, context.irBuiltIns.stringType, key)
                collector.declarations += declaration.copy(key = key)
            }
        }

        private fun fingerprint(declaration: MixinDeclaration): String {
            val text =
                with(declaration) { "$target|$method|$point|${member?.owner}|${member?.member}|${member?.isField}|$ordinal|$shift" }
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8)
                .joinToString("") { "%02x".format(it) }
        }

        private fun statements(call: IrCall, lambda: String): List<IrStatement> {
            val function = (call.argument(lambda) as? IrFunctionExpression)?.function
                ?: return emptyList<IrStatement>().also {
                    error(
                        call,
                        "The block must be written in place as a lambda"
                    )
                }
            return (function.body as? IrBlockBody)?.statements.orEmpty()
        }

        private fun IrStatement.asCall(): IrCall? = when (this) {
            is IrCall -> this
            is IrTypeOperatorCall -> argument.asCall()
            is IrReturn -> if (value is IrGetObjectValue) null else value.asCall()
            else -> null.also {
                error(
                    this,
                    "Only mixin declarations may appear here, and their arguments must be constants"
                )
            }
        }

        private fun IrCall.valueType(index: Int): String? =
            typeArguments.getOrNull(index)?.classOrNull?.owner?.kotlinFqName?.asString()

        private fun IrCall.string(name: String): String? = (argument(name) as? IrConst)?.value as? String

        private fun IrCall.int(name: String): Int? {
            val argument = argument(name) ?: return null
            return (argument as? IrConst)?.value as? Int ?: null.also {
                error(
                    argument,
                    "'$name' must be an integer constant"
                )
            }
        }

        private fun IrExpression?.enumEntry(): String? = (this as? IrGetEnumValue)?.symbol?.owner?.name?.asString()

        private fun location(element: IrElement): MixinSourceLocation? {
            val entry = file?.fileEntry ?: return null
            if (element.startOffset < 0) return null
            return MixinSourceLocation(
                entry.name,
                entry.getLineNumber(element.startOffset) + 1,
                entry.getColumnNumber(element.startOffset) + 1,
                entry.getLineNumber(element.endOffset) + 1,
                entry.getColumnNumber(element.endOffset) + 1,
            )
        }

        private fun error(element: IrElement, message: String) {
            collector.errors += MixinError(message, location(element))
        }
    }
}

private val IrCall.callee: IrSimpleFunction get() = symbol.owner

private fun IrCall.argument(name: String): IrExpression? {
    val parameter = callee.parameters.firstOrNull { it.kind == IrParameterKind.Regular && it.name.asString() == name }
        ?: return null
    return arguments[parameter]
}

private fun IrSimpleFunction.isMemberOf(className: String): Boolean =
    parentClassOrNull?.kotlinFqName?.asString() == "$DSL.$className"

private fun IrSimpleFunction.isMember(className: String, functionName: String): Boolean =
    name.asString() == functionName && isMemberOf(className)

private fun IrSimpleFunction.isTopLevel(functionName: String): Boolean =
    name.asString() == functionName && (parent as? IrPackageFragment)?.packageFqName?.asString() == DSL

private val KOTLIN_TYPES = mapOf(
    "kotlin.Any" to "java/lang/Object",
    "kotlin.String" to "java/lang/String",
    "kotlin.CharSequence" to "java/lang/CharSequence",
    "kotlin.Number" to "java/lang/Number",
    "kotlin.Comparable" to "java/lang/Comparable",
    "kotlin.Enum" to "java/lang/Enum",
    "kotlin.Throwable" to "java/lang/Throwable",
    "kotlin.collections.Iterable" to "java/lang/Iterable",
    "kotlin.collections.MutableIterable" to "java/lang/Iterable",
    "kotlin.collections.Collection" to "java/util/Collection",
    "kotlin.collections.MutableCollection" to "java/util/Collection",
    "kotlin.collections.List" to "java/util/List",
    "kotlin.collections.MutableList" to "java/util/List",
    "kotlin.collections.Set" to "java/util/Set",
    "kotlin.collections.MutableSet" to "java/util/Set",
    "kotlin.collections.Map" to "java/util/Map",
    "kotlin.collections.MutableMap" to "java/util/Map",
)

/** The JVM internal name of the class [this] refers to, or `null` for anything but a class type. */
private fun IrType.internalName(): String? {
    val irClass = classOrNull?.owner ?: return null
    KOTLIN_TYPES[irClass.kotlinFqName.asString()]?.let { return it }
    return irClass.internalName()
}

private fun IrClass.internalName(): String = when (val parent = parent) {
    is IrClass -> parent.internalName() + "$" + name.asString()
    is IrPackageFragment -> {
        val packageName = parent.packageFqName.asString().replace('.', '/')
        if (packageName.isEmpty()) name.asString() else "$packageName/${name.asString()}"
    }

    else -> kotlinFqName.asString().replace('.', '/')
}
