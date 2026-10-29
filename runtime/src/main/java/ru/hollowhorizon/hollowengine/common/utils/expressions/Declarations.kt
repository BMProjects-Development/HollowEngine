package ru.hollowhorizon.hollowengine.common.utils.expressions

class Declarations<C> internal constructor(
    val roots: Map<String, Member.Field>,
    val receivers: List<Member.Field>,
    val types: Map<String, ExprType>,
    val literals: Literals? = null,
) {
    fun root(name: String): Member.Field? = roots[name]

    fun dynamicReceiver(): Member.Field? = receivers.firstOrNull { it.type is ExprType.Dynamic }

    fun fieldOnReceivers(name: String): Access<Member.Field>? {
        receivers.forEach { receiver ->
            receiver.type.members.field(name)?.let { return Access(receiver, it) }
        }
        return null
    }

    /** As [fieldOnReceivers], but for a call with signature. */
    fun methodOnReceivers(name: String, arguments: List<ExprType>, casts: Casts): Access<Member.Method>? {
        receivers.forEach { receiver ->
            val overloads = receiver.type.members.method(name, arguments, casts)
            overloads.match?.let { return Access(receiver, it) }
        }
        return null
    }

    /** Every overload of [name] visible on the receivers. */
    fun methodCandidates(name: String): List<Member.Method> =
        receivers.flatMap { it.type.members.methods(name) }

    /** A member together with the receiver it has to be read from. */
    class Access<T : Member>(val receiver: Member.Field, val member: T)

    operator fun plus(other: Declarations<C>): Declarations<C> = Declarations(
        roots = roots + other.roots,
        receivers = receivers + other.receivers,
        types = types + other.types,
        literals = other.literals ?: literals,
    )
}

interface Literals {
    val type: ExprType

    fun number(value: Float): Any?
    fun string(value: String): Any?
    fun bool(value: Boolean): Any?
    fun list(items: List<Any?>): Any?
}

/** A [value] of [type] as the dialect writes it: a primitive the way its literal would be, anything else as it is. */
internal fun Literals.wrap(type: ExprType, value: Any?): Any? = when (type) {
    ExprType.Primitive.BOOL -> bool(Coercions.toBool(value))
    ExprType.Primitive.FLOAT -> number(Coercions.toFloat(value))
    else -> value
}

@DslMarker
annotation class ExpressionDsl

@ExpressionDsl
class DeclarationsBuilder<C> internal constructor() {
    private val roots = LinkedHashMap<String, Member.Field>()
    private val receivers = mutableListOf<Member.Field>()
    private val types = LinkedHashMap<String, ExprType>()
    private var literals: Literals? = null

    /** A struct type: its values are never materialized, members are read straight off the owner. */
    fun <O> struct(name: String, block: MembersBuilder<O>.() -> Unit): ExprType.Struct =
        ExprType.Struct(name, MembersBuilder<O>().apply(block).build()).also { types[name] = it }

    /** A type backed by a real object; usable from the interpreter, not from the bytecode backend. */
    fun <O> reference(
        name: String,
        jvmClass: Class<*>,
        truthy: ObjectToBool? = null,
        number: ObjectToFloat? = null,
        block: MembersBuilder<O>.(ExprType.Reference) -> Unit,
    ): ExprType.Reference {
        val type = ExprType.Reference(name, Members.EMPTY, truthy, number)
        type.members = MembersBuilder<O>().apply { block(type) }.build()
        types[name] = type
        return type
    }

    /** Runtime-named values, such as Molang's `v.` and `t.`. */
    fun dynamic(
        name: String,
        valueType: ExprType = ExprType.Primitive.FLOAT,
        read: (owner: Any?, key: String) -> Any?,
        write: ((owner: Any?, key: String, value: Any?) -> Unit)? = null,
        nested: Boolean = false,
    ): ExprType.Dynamic = ExprType.Dynamic(name, valueType, read, write, nested).also { types[name] = it }

    /**
     * Property, such as `query` or `math`. A namespace of functions is just a property whose
     * type happens to hold methods instead than fields.
     */
    @Suppress("UNCHECKED_CAST")
    fun property(name: String, type: ExprType, alias: String? = null, read: (C) -> Any?) {
        val member = Member.ObjectField(name, type, alias) { read(it as C) }
        member.names.forEach { roots[it] = member }
    }

    /** Declares [rootName] as an implicit receiver, so its members resolve as identifiers. */
    fun receiver(rootName: String) {
        val member = roots[rootName] ?: error("Cannot use '$rootName' as a receiver: it is not declared")
        receivers += member
    }

    fun include(other: Declarations<C>) {
        roots += other.roots
        receivers += other.receivers
        types += other.types
        other.literals?.let { literals = it }
    }

    /** Declares how literals are represented; see [Literals]. */
    fun literals(value: Literals) {
        literals = value
    }

    internal fun build() = Declarations<C>(roots.toMap(), receivers.toList(), types.toMap(), literals)
}

@ExpressionDsl
@Suppress("UNCHECKED_CAST")
class MembersBuilder<O> internal constructor() {
    private val fields = LinkedHashMap<String, Member.Field>()
    private val methods = LinkedHashMap<String, MutableList<Member.Method>>()
    private val operators = LinkedHashMap<BinaryOp, Operator>()

    /**
     * What an operator means when one of its operands is of this type.
     */
    fun operator(op: BinaryOp, returns: ExprType, invoke: (Any?, Any?) -> Any?) {
        require(operators.put(op, Operator(returns, invoke)) == null) { "Operator '${op.symbol}' declared twice" }
    }

    fun float(name: String, alias: String? = null, read: (O) -> Float) {
        add(Member.FloatField(name, alias) { read(it as O) })
    }

    fun bool(name: String, alias: String? = null, read: (O) -> Boolean) {
        add(Member.BoolField(name, alias) { read(it as O) })
    }

    fun field(name: String, type: ExprType, alias: String? = null, read: (O) -> Any?) {
        add(Member.ObjectField(name, type, alias) { read(it as O) })
    }

    /** A one-argument float function, called without boxing from either backend. */
    fun function(name: String, alias: String? = null, body: Float1) {
        addMethod(
            Member.Method(
                name, FLOAT, listOf(FLOAT), alias,
                generic = { _, args -> body.invoke(args[0] as Float) },
                float1 = body,
            )
        )
    }

    fun function2(name: String, alias: String? = null, body: Float2) {
        addMethod(
            Member.Method(
                name, FLOAT, listOf(FLOAT, FLOAT), alias,
                generic = { _, args -> body.invoke(args[0] as Float, args[1] as Float) },
                float2 = body,
            )
        )
    }

    fun function3(name: String, alias: String? = null, body: Float3) {
        addMethod(
            Member.Method(
                name, FLOAT, listOf(FLOAT, FLOAT, FLOAT), alias,
                generic = { _, args -> body.invoke(args[0] as Float, args[1] as Float, args[2] as Float) },
                float3 = body,
            )
        )
    }

    /** Anything that is not all-float: the arguments arrive boxed and the result may be an object. */
    fun method(
        name: String,
        returns: ExprType,
        vararg parameters: ExprType,
        alias: String? = null,
        invoke: GenericInvoker,
    ) {
        addMethod(Member.Method(name, returns, parameters.toList(), alias, generic = invoke))
    }

    private fun add(member: Member.Field) {
        member.names.forEach { fields[it] = member }
    }

    private fun addMethod(member: Member.Method) {
        member.names.forEach { key ->
            val overloads = methods.getOrPut(key) { mutableListOf() }
            require(overloads.none { it.parameters == member.parameters }) {
                "Duplicate overload ${member.signature}"
            }
            overloads += member
        }
    }

    internal fun build() = Members(fields.toMap(), methods.mapValues { it.value.toList() }, operators.toMap())

    private companion object {
        val FLOAT = ExprType.Primitive.FLOAT
    }
}

fun <C> Declarations(block: DeclarationsBuilder<C>.() -> Unit): Declarations<C> =
    DeclarationsBuilder<C>().apply(block).build()
