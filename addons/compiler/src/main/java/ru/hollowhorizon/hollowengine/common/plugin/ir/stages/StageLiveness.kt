package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import java.util.*

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class StageLiveness(private val sites: Map<IrCall, StageSite>) {
    private val after = IdentityHashMap<StageSite, Set<IrValueDeclaration>>()
    private val breaks = IdentityHashMap<IrLoop, Set<IrValueDeclaration>>()
    private val continues = IdentityHashMap<IrLoop, Set<IrValueDeclaration>>()

    private var handlers: Set<IrValueDeclaration> = emptySet()

    fun analyze(statements: List<IrStatement>): Map<StageSite, Set<IrValueDeclaration>> {
        statements.foldRight(emptySet<IrValueDeclaration>()) { statement, live -> before(statement, live) }
        return after
    }

    private fun before(element: IrElement, live: Set<IrValueDeclaration>): Set<IrValueDeclaration> = when (element) {
        is IrVariable -> element.initializer?.let { before(it, live - element) } ?: (live - element)
        is IrSetValue -> before(element.value, live - element.symbol.owner)
        is IrGetValue -> live + element.symbol.owner
        is IrWhen -> element.branches.foldRight(live) { branch, rest ->
            before(branch.condition, before(branch.result, live) + rest)
        }

        is IrWhileLoop -> loop(element, live) { head ->
            val body = element.body?.let { before(it, head) } ?: head
            before(element.condition, body + live)
        }

        is IrDoWhileLoop -> {
            var body: Set<IrValueDeclaration> = emptySet()
            loop(element, live) { condition ->
                body = element.body?.let { before(it, condition) } ?: condition
                before(element.condition, body + live)
            }
            body
        }

        is IrBreak -> breaks[element.loop] ?: live
        is IrContinue -> continues[element.loop] ?: live
        is IrReturn -> before(element.value, handlers)
        is IrThrow -> before(element.value, handlers)
        is IrTry -> tryCatch(element, live)
        is IrFunction, is IrClass -> live + reads(element)
        is IrContainerExpression -> element.statements.foldRight(live) { statement, rest -> before(statement, rest) }
        else -> {
            if (element is IrCall) sites[element]?.let { site -> after[site] = live + handlers }
            children(element).foldRight(live) { child, rest -> before(child, rest) }
        }
    }

    private fun loop(
        loop: IrLoop,
        live: Set<IrValueDeclaration>,
        pass: (Set<IrValueDeclaration>) -> Set<IrValueDeclaration>,
    ): Set<IrValueDeclaration> {
        var start: Set<IrValueDeclaration> = emptySet()
        breaks[loop] = live
        while (true) {
            continues[loop] = start
            val next = pass(start)
            if (next == start) return start
            start = next
        }
    }

    private fun tryCatch(element: IrTry, live: Set<IrValueDeclaration>): Set<IrValueDeclaration> {
        val outer = handlers
        val normal = element.finallyExpression?.let { before(it, live) } ?: live
        val failed = element.finallyExpression?.let { before(it, outer) } ?: outer
        val caught = element.catches.fold(failed) { reads, catch ->
            reads + (before(
                catch.result,
                normal
            ) - catch.catchParameter)
        }
        handlers = outer + caught
        val body = try {
            before(element.tryResult, normal)
        } finally {
            handlers = outer
        }
        return body + caught
    }

    private fun children(element: IrElement): List<IrElement> {
        val children = ArrayList<IrElement>()
        element.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                children += element
            }
        })
        return children
    }

    private fun reads(element: IrElement): Set<IrValueDeclaration> {
        val reads = HashSet<IrValueDeclaration>()
        element.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitGetValue(expression: IrGetValue) {
                reads += expression.symbol.owner
            }
        })
        return reads
    }
}
