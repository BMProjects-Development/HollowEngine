package ru.hollowhorizon.hollowengine.common.utils.expressions

/** Turns an [Ast] into typed [Ir], checking types and reporting what does not fit. */
internal class Resolver(
    private val declarations: Declarations<*>,
    private val options: Options,
    private val diagnostics: Diagnostics,
) {
    private val literals = declarations.literals

    fun resolve(ast: Ast): Ir? = when (ast) {
        is Ast.NumberLit -> literals?.let { Ir.Const(it.type, it.number(ast.value)) } ?: Ir.Const(FLOAT, ast.value)
        is Ast.BoolLit -> literals?.let { Ir.Const(it.type, it.bool(ast.value)) } ?: Ir.Const(BOOL, ast.value)
        is Ast.StringLit -> literals?.let { Ir.Const(it.type, it.string(ast.value)) } ?: Ir.Const(STRING, ast.value)
        is Ast.ListLit -> resolveList(ast)
        is Ast.Name -> resolveName(ast)
        is Ast.Access -> resolveAccess(ast)
        is Ast.Index -> resolveIndex(ast)
        is Ast.Call -> resolveCall(ast)
        is Ast.Unary -> resolveUnary(ast)
        is Ast.Binary -> resolveBinary(ast)
        is Ast.Conditional -> resolveConditional(ast)
        is Ast.Assign -> resolveAssign(ast)
        is Ast.Sequence -> resolveSequence(ast)
    }

    /** A list; under [literals] an item worked out as a primitive, such as `-60`, is wrapped like a literal. */
    private fun resolveList(ast: Ast.ListLit): Ir? {
        val items = ast.items.map { resolve(it) ?: return null }
        val literals = literals ?: return Ir.ListOf(items, LIST)
        val types = items.map { it.type }
        return Ir.ListOf(items, literals.type) { values ->
            literals.list(values.mapIndexed { index, value -> literals.wrap(types[index], value) })
        }
    }

    private fun resolveName(ast: Ast.Name): Ir? {
        declarations.root(ast.name)?.let { return Ir.FieldRead(null, it) }
        declarations.fieldOnReceivers(ast.name)?.let { access ->
            return Ir.FieldRead(Ir.FieldRead(null, access.receiver), access.member)
        }
        declarations.dynamicReceiver()?.let { receiver ->
            val bag = receiver.type as ExprType.Dynamic
            return Ir.BagRead(Ir.FieldRead(null, receiver), bag, ast.name)
        }
        val fallback = options.unresolvedReferences.resolve(ast.name, ast.span, diagnostics) ?: return null
        return Ir.Const(FLOAT, fallback)
    }

    private fun resolveAccess(ast: Ast.Access): Ir? {
        val target = resolve(ast.target) ?: return null

        (target.type as? ExprType.Dynamic)?.let { bag ->
            return Ir.BagRead(target, bag, ast.name)
        }

        (target as? Ir.BagRead)?.takeIf { it.bag.nested }?.let { parent ->
            return Ir.BagRead(parent.owner, parent.bag, "${parent.key}.${ast.name}")
        }

        val field = target.type.members.field(ast.name)
        if (field != null) return Ir.FieldRead(target, field)

        val fallback = options.unresolvedReferences.resolve(
            "${ast.target.describe()}.${ast.name}", ast.span, diagnostics,
        ) ?: return null
        return Ir.Const(FLOAT, fallback)
    }

    private fun resolveIndex(ast: Ast.Index): Ir? {
        val target = resolve(ast.target) ?: return null
        val index = resolve(ast.index) ?: return null

        target.type.members.operator(BinaryOp.INDEX)?.let { return Ir.Operation(BinaryOp.INDEX, target, index, it) }

        if (target.type !== LIST) {
            diagnostics.error("'${target.type.name}' cannot be indexed", ast.span)
            return null
        }
        return Ir.Index(target, asFloat(index, ast.index.span) ?: return null, ANY)
    }

    private fun resolveCall(ast: Ast.Call): Ir? {
        val arguments = ast.arguments.map { resolve(it) ?: return null }
        val argumentTypes = arguments.map { it.type }

        val owner: Ir?
        val method: Member.Method
        if (ast.target == null) {
            val access = declarations.methodOnReceivers(ast.name, argumentTypes, options.casts)
            if (access == null) {
                reportMissingMethod(ast, declarations.methodCandidates(ast.name), argumentTypes)
                return null
            }
            owner = Ir.FieldRead(null, access.receiver)
            method = access.member
        } else {
            val target = resolve(ast.target) ?: return null
            val overloads = target.type.members.method(ast.name, argumentTypes, options.casts)
            val match = overloads.match
            if (match == null) {
                val candidates = overloads.ambiguous.ifEmpty { target.type.members.methods(ast.name) }
                reportMissingMethod(ast, candidates, argumentTypes)
                return null
            }
            owner = target
            method = match
        }

        val converted = arguments.mapIndexed { index, argument ->
            val expected = method.parameters[index]
            if (argument.type === expected) argument else Optimizer.convert(argument, expected)
        }
        return Optimizer.call(method, owner, converted)
    }

    private fun reportMissingMethod(ast: Ast.Call, candidates: List<Member.Method>, arguments: List<ExprType>) {
        val signature = "${ast.name}(${arguments.joinToString { it.name }})"
        if (candidates.isEmpty()) {
            diagnostics.error("Unknown function '$signature'", ast.span)
        } else {
            diagnostics.error(
                "No overload of '$signature' fits; candidates: ${candidates.joinToString { it.signature }}",
                ast.span,
            )
        }
    }

    private fun resolveUnary(ast: Ast.Unary): Ir? {
        val operand = resolve(ast.operand) ?: return null
        return when (ast.op) {
            UnaryOp.NOT -> Optimizer.not(asBool(operand, ast.operand.span) ?: return null)
            UnaryOp.NEGATE -> Optimizer.negate(asFloat(operand, ast.operand.span) ?: return null)
        }
    }

    private fun resolveBinary(ast: Ast.Binary): Ir? {
        val left = resolve(ast.left) ?: return null
        val right = resolve(ast.right) ?: return null

        if (!(left.type.isPrimitive && right.type.isPrimitive)) {
            operatorFor(ast.op, left, right)?.let { return it }
        }

        return when (ast.op) {
            BinaryOp.AND, BinaryOp.OR -> Optimizer.logical(
                ast.op,
                asBool(left, ast.left.span) ?: return null,
                asBool(right, ast.right.span) ?: return null,
            )

            BinaryOp.EQ, BinaryOp.NEQ -> resolveEquality(ast, left, right)

            BinaryOp.LT, BinaryOp.GT, BinaryOp.LTE, BinaryOp.GTE -> Optimizer.compare(
                ast.op,
                asFloat(left, ast.left.span) ?: return null,
                asFloat(right, ast.right.span) ?: return null,
            )

            BinaryOp.COALESCE -> Ir.Coalesce(left, right)

            else -> Optimizer.arithmetic(
                ast.op,
                asFloat(left, ast.left.span) ?: return null,
                asFloat(right, ast.right.span) ?: return null,
            )
        }
    }

    private fun operatorFor(op: BinaryOp, left: Ir, right: Ir): Ir? {
        if (op == BinaryOp.AND || op == BinaryOp.OR || op == BinaryOp.COALESCE) return null
        if (op == BinaryOp.EQ || op == BinaryOp.NEQ) return null
        val operator = left.type.members.operator(op) ?: right.type.members.operator(op) ?: return null
        return Ir.Operation(op, left, right, operator)
    }

    private fun resolveEquality(ast: Ast.Binary, left: Ir, right: Ir): Ir? {
        if (left.type.isPrimitive && right.type.isPrimitive) {
            if (options.casts == Casts.EXPLICIT && left.type !== right.type) {
                diagnostics.error("Cannot compare '${left.type.name}' with '${right.type.name}'", ast.span)
                return null
            }
            return Optimizer.compare(
                ast.op,
                asFloat(left, ast.left.span) ?: return null,
                asFloat(right, ast.right.span) ?: return null,
            )
        }

        if (options.casts == Casts.EXPLICIT && left.type !== right.type) {
            diagnostics.error("Cannot compare '${left.type.name}' with '${right.type.name}'", ast.span)
            return null
        }
        return Ir.ObjectEquals(left, right, negate = ast.op == BinaryOp.NEQ)
    }

    private fun resolveConditional(ast: Ast.Conditional): Ir? {
        val condition = resolve(ast.condition)?.let { asBool(it, ast.condition.span) } ?: return null
        val ifTrue = resolve(ast.ifTrue) ?: return null
        val ifFalse = resolve(ast.ifFalse) ?: return null

        if (ifTrue.type === FLOAT && ifFalse.type === FLOAT) {
            return Optimizer.conditional(condition, ifTrue, ifFalse, FLOAT)
        }
        val type = if (ifTrue.type === ifFalse.type) ifTrue.type else ANY
        return Optimizer.conditional(condition, ifTrue, ifFalse, type)
    }

    private fun resolveAssign(ast: Ast.Assign): Ir? {
        val target = ast.target
        if (target !is Ast.Access) {
            diagnostics.error("Only members of a variable bag can be assigned", target.span)
            return null
        }
        val owner = resolve(target.target) ?: return null
        (owner as? Ir.BagRead)?.takeIf { it.bag.nested && it.bag.write != null }?.let { parent ->
            val value = resolve(ast.value) ?: return null
            return Ir.BagWrite(parent.owner, parent.bag, "${parent.key}.${target.name}", value)
        }
        val bag = owner.type as? ExprType.Dynamic
        if (bag?.write == null) {
            diagnostics.error("'${target.target.describe()}' is read-only", target.span)
            return null
        }
        val value = resolve(ast.value) ?: return null
        return Ir.BagWrite(owner, bag, target.name, value)
    }

    private fun resolveSequence(ast: Ast.Sequence): Ir? =
        Ir.Sequence(ast.statements.map { resolve(it) ?: return null })

    private fun asFloat(value: Ir, span: Span): Ir? = when {
        value.type === FLOAT -> value
        (value.type as? ExprType.Reference)?.number != null -> Ir.Convert(value, FLOAT)
        value.type === BOOL -> if (allowsCast(span, "bool", "float")) Optimizer.convert(value, FLOAT) else null
        else -> if (allowsCast(span, value.type.name, "float")) Optimizer.convert(value, FLOAT) else null
    }

    private fun asBool(value: Ir, span: Span): Ir? = when {
        value.type === BOOL -> value
        (value.type as? ExprType.Reference)?.truthy != null -> Ir.Convert(value, BOOL)
        value.type === FLOAT -> if (allowsCast(span, "float", "bool")) Optimizer.convert(value, BOOL) else null
        else -> if (allowsCast(span, value.type.name, "bool")) Optimizer.convert(value, BOOL) else null
    }

    private fun allowsCast(span: Span, from: String, to: String): Boolean {
        if (options.casts == Casts.IMPLICIT) return true
        diagnostics.error("Expected $to, got $from", span)
        return false
    }

    private fun Ast.describe(): String = when (this) {
        is Ast.Name -> name
        is Ast.Access -> "${target.describe()}.$name"
        else -> "expression"
    }

    private companion object {
        val FLOAT = ExprType.Primitive.FLOAT
        val BOOL = ExprType.Primitive.BOOL
    }
}

internal val STRING = ExprType.Reference("string", Members.EMPTY)
internal val LIST = ExprType.Reference("list", Members.EMPTY)
internal val ANY = ExprType.Reference("any", Members.EMPTY)
