package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import java.util.*

/** How a stage call stands in its statement once [StageNormalizer] is done. */
internal enum class SiteForm { STATEMENT, VARIABLE, ASSIGN, RETURN }

/**
 * A stage call or checkpoint inside a stage. Positions in the frame are `index * 2` while the call runs and
 * `index * 2 + 1` after it; [name] is what the save stores instead.
 */
internal class StageSite(
    val index: Int,
    val name: String,
    val call: IrCall,
    val kind: SiteKind,
    val form: SiteForm,
    val tail: Boolean,
) {
    /** The locals that are still needed after the call, so the frame saves them when it gets here. */
    var live: List<IrVariable> = emptyList()

    val callPosition: Int get() = index * 2
    val afterPosition: Int get() = index * 2 + 1
}

/**
 * Finds the sites of a normalized stage body and names them by where they are: the branches and loops around
 * them, then the stage called and how many calls to it precede on that level (`when#1.else/trial#1`). A
 * checkpoint is named by its own name. Then works out which locals each site has to save.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class StageLayout(private val stages: StageScope, private val function: IrSimpleFunction) {
    val sites = ArrayList<StageSite>()
    private val byCall = IdentityHashMap<IrCall, StageSite>()
    private val checkpoints = HashSet<String>()

    fun site(call: IrCall): StageSite? = byCall[call]

    /** Every local some site saves, in the order they are declared. */
    var saved: List<IrVariable> = emptyList()
        private set

    /** The key each saved local is stored under in the frame. */
    val keys = IdentityHashMap<IrVariable, String>()

    fun layout(statements: List<IrStatement>) {
        level(statements, "", HashMap(), tail = true, inTry = false)
        liveness(statements)
    }

    /** The range of positions of the sites inside [element], or null when there are none. */
    fun positions(element: IrElement): IntRange? {
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        element.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitFunction(declaration: IrFunction) = Unit

            override fun visitCall(expression: IrCall) {
                byCall[expression]?.let {
                    first = minOf(first, it.callPosition)
                    last = maxOf(last, it.afterPosition)
                }
                super.visitCall(expression)
            }
        })
        return if (first > last) null else first..last
    }

    private fun level(
        statements: List<IrStatement>,
        path: String,
        counters: HashMap<String, Int>,
        tail: Boolean,
        inTry: Boolean,
    ) {
        statements.forEachIndexed { index, statement ->
            val last = tail && index == statements.lastIndex
            statement(statement, path, counters, last, inTry)
        }
    }

    private fun statement(
        statement: IrStatement,
        path: String,
        counters: HashMap<String, Int>,
        tail: Boolean,
        inTry: Boolean,
    ) {
        siteCall(statement)?.let { (call, form) ->
            register(call, form, path, counters, tail, inTry)
            return
        }
        when (statement) {
            is IrWhen -> {
                val kind = if (statement.origin == IrStatementOrigin.IF) "if" else "when"
                val label = "$path$kind#${counters.next(kind)}"
                statement.branches.forEachIndexed { index, branch ->
                    val name = when {
                        branch is IrElseBranch || index == statement.branches.lastIndex && branch.condition.isTrue() -> "else"
                        kind == "if" -> "then"
                        else -> "b${index + 1}"
                    }
                    level(statementsOf(branch.result), "$label.$name/", HashMap(), tail, inTry)
                }
            }

            is IrWhileLoop, is IrDoWhileLoop -> {
                level(
                    statementsOf(statement.body),
                    "${path}loop#${counters.next("loop")}/",
                    HashMap(),
                    tail = false,
                    inTry = inTry
                )
            }

            is IrTry -> level(
                statementsOf(statement.tryResult),
                "${path}try#${counters.next("try")}/",
                HashMap(),
                tail = false,
                inTry = true
            )

            is IrBlock -> level(statement.statements, path, counters, tail, inTry)
        }
    }

    private fun register(
        call: IrCall,
        form: SiteForm,
        path: String,
        counters: HashMap<String, Int>,
        tail: Boolean,
        inTry: Boolean,
    ) {
        val kind = stages.siteKind(call)!!
        val name = when (kind) {
            SiteKind.CHECKPOINT -> {
                val name = (call.arguments[0] as? IrConst)?.value as? String
                if (name.isNullOrBlank()) {
                    stages.reporter.error(call, "A checkpoint needs a constant name")
                } else if (!checkpoints.add(name)) {
                    stages.reporter.error(call, "Checkpoint '$name' appears twice in '${function.name}'")
                }
                name ?: "checkpoint"
            }

            SiteKind.STAGE -> {
                val callee = stages.stageName(call.symbol.owner)
                "$path$callee#${counters.next("call:$callee")}"
            }

            SiteKind.LAMBDA -> {
                val receiver = (call.dispatchReceiver as? IrGetValue)?.symbol?.owner?.name?.asString() ?: "lambda"
                "$path$receiver#${counters.next("call:$receiver")}"
            }
        }
        val isTail = kind == SiteKind.STAGE && !inTry && when (form) {
            SiteForm.RETURN -> true
            SiteForm.STATEMENT -> tail && function.returnType.isUnit() && call.type.isUnit()
            else -> false
        } && call.arguments.drop(1).all { it != null }
        StageSite(sites.size, name, call, kind, form, isTail).also {
            sites += it
            byCall[call] = it
        }
    }

    /** Works out [StageSite.live] in evaluation order: a local is saved at a site when it is read after it. */
    private fun liveness(statements: List<IrStatement>) {
        var clock = 0
        val declared = IdentityHashMap<IrVariable, Int>()
        val reads = IdentityHashMap<IrValueDeclaration, MutableList<Int>>()
        val at = IdentityHashMap<StageSite, Int>()
        val loops = ArrayList<IntRange>()
        var depth = 0

        val visitor = object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitFunction(declaration: IrFunction) {
                depth++
                super.visitFunction(declaration)
                depth--
            }

            override fun visitVariable(declaration: IrVariable) {
                declaration.initializer?.acceptVoid(this)
                if (depth == 0) declared[declaration] = ++clock
            }

            override fun visitGetValue(expression: IrGetValue) {
                reads.getOrPut(expression.symbol.owner, ::ArrayList) += ++clock
            }

            override fun visitSetValue(expression: IrSetValue) {
                expression.value.acceptVoid(this)
                ++clock
            }

            override fun visitCall(expression: IrCall) {
                super.visitCall(expression)
                byCall[expression]?.let { at[it] = ++clock }
            }

            override fun visitLoop(loop: IrLoop) {
                val start = ++clock
                super.visitLoop(loop)
                loops += start..++clock
            }
        }
        statements.forEach { it.acceptVoid(visitor) }

        val saved = LinkedHashSet<IrVariable>()
        sites.forEach { site ->
            val time = at.getValue(site)
            site.live = declared.entries.filter { (variable, declaredAt) ->
                if (declaredAt >= time) return@filter false
                val uses = reads[variable].orEmpty()
                uses.any { it > time } || loops.any { loop -> time in loop && declaredAt < loop.first && uses.any { it in loop } }
            }.sortedBy { it.value }.map { it.key }
            saved += site.live
        }
        this.saved = saved.sortedBy { declared.getValue(it) }
        val names = HashMap<String, Int>()
        this.saved.forEach { variable ->
            val base = variable.name.asString()
            val count = names.merge(base, 1, Int::plus)!!
            keys[variable] = if (count == 1) base else "$base#$count"
        }
    }

    private fun siteCall(statement: IrStatement): Pair<IrCall, SiteForm>? {
        fun IrExpression?.site(): IrCall? = (this as? IrCall)?.takeIf { stages.siteKind(it) != null }
        return when (statement) {
            is IrCall -> statement.site()?.let { it to SiteForm.STATEMENT }
            is IrTypeOperatorCall if statement.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> statement.argument.site()
                ?.let { it to SiteForm.STATEMENT }

            is IrVariable -> statement.initializer.site()?.let { it to SiteForm.VARIABLE }
            is IrSetValue -> statement.value.site()?.let { it to SiteForm.ASSIGN }
            is IrReturn -> statement.value.site()?.takeIf { statement.returnTargetSymbol == function.symbol }
                ?.let { it to SiteForm.RETURN }

            else -> null
        }
    }

    private fun HashMap<String, Int>.next(key: String): Int = merge(key, 1, Int::plus)!!

    private fun IrExpression.isTrue() = this is IrConst && value == true
}

internal fun statementsOf(expression: IrExpression?): List<IrStatement> = when (expression) {
    null -> emptyList()
    is IrContainerExpression -> expression.statements
    else -> listOf(expression)
}
