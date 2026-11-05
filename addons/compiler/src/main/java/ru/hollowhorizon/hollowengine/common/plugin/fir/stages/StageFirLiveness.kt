package ru.hollowhorizon.hollowengine.common.plugin.fir.stages

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirLocalPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.SpecialNames
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageDiagnostics
import java.util.*

@OptIn(SymbolInternals::class)
internal class StageFirLiveness private constructor(private val session: FirSession) {
    private val sites = IdentityHashMap<FirFunctionCall, Set<FirPropertySymbol>>()
    private val breaks = IdentityHashMap<FirLoop, Set<FirPropertySymbol>>()
    private val continues = IdentityHashMap<FirLoop, Set<FirPropertySymbol>>()
    private var handlers: Set<FirPropertySymbol> = emptySet()

    fun isSite(call: FirFunctionCall): Boolean {
        val symbol = call.calleeReference.toResolvedCallableSymbol() ?: return false
        if (symbol.callableId?.packageName == StageFirIds.PACKAGE && symbol.name.asString() == "checkpoint") return true
        if (symbol.hasAnnotation(StageFirIds.STATE, session)) return true
        if (symbol.name.asString() == "invoke" && call.dispatchReceiver?.resolvedType?.isStageLambda(session) == true) return true
        val function = symbol.fir as? FirFunction ?: return false
        val action =
            symbol.hasAnnotation(StageFirIds.ACTION, session) || symbol.hasAnnotation(StageFirIds.ACTION_ALIAS, session)
        return action && (function as? FirMemberDeclaration)?.isSuspend == true && (function.valueParameters.map { it.returnTypeRef } + listOfNotNull(
            function.receiverParameter?.typeRef
        )).none { it.coneType.classId == StageFirIds.CHECKPOINT_SCOPE }
    }

    private fun before(element: FirElement, live: Set<FirPropertySymbol>): Set<FirPropertySymbol> = when (element) {
        is FirProperty -> {
            val rest = live - element.symbol
            val delegated = element.delegate?.let { before(it, rest) } ?: rest
            element.initializer?.let { before(it, delegated) } ?: delegated
        }

        is FirVariableAssignment -> {
            val target = (element.lValue as? FirQualifiedAccessExpression)?.calleeReference?.toResolvedCallableSymbol()
            before(element.rValue, if (target is FirPropertySymbol) live - target else live)
        }

        is FirPropertyAccessExpression -> {
            val read = element.calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol
            val receiver = element.explicitReceiver?.let { before(it, live) } ?: live
            if (read != null) receiver + read else receiver
        }

        is FirWhenExpression -> {
            val branches = element.branches.foldRight(live) { branch, rest ->
                before(branch.condition, before(branch.result, live) + rest)
            }
            element.subjectVariable?.let { before(it, branches) } ?: branches
        }

        is FirWhileLoop -> loop(element, live) { head -> before(element.condition, before(element.block, head) + live) }
        is FirDoWhileLoop -> {
            var body: Set<FirPropertySymbol> = emptySet()
            loop(element, live) { condition ->
                body = before(element.block, condition)
                before(element.condition, body + live)
            }
            body
        }

        is FirBreakExpression -> breaks[element.target.labeledElement] ?: live
        is FirContinueExpression -> continues[element.target.labeledElement] ?: live
        is FirReturnExpression -> before(element.result, handlers)
        is FirThrowExpression -> before(element.exception, handlers)
        is FirTryExpression -> tryCatch(element, live)
        is FirBooleanOperatorExpression -> before(element.leftOperand, before(element.rightOperand, live) + live)
        is FirElvisExpression -> before(element.lhs, before(element.rhs, live) + live)
        is FirSafeCallExpression -> before(element.receiver, before(element.selector, live) + live)
        is FirFunction, is FirRegularClass, is FirAnonymousObject -> live + reads(element)
        is FirBlock -> element.statements.foldRight(live) { statement, rest -> before(statement, rest) }
        else -> {
            if (element is FirFunctionCall && isSite(element)) sites[element] = live + handlers
            children(element).foldRight(live) { child, rest -> before(child, rest) }
        }
    }

    private fun loop(
        loop: FirLoop,
        live: Set<FirPropertySymbol>,
        pass: (Set<FirPropertySymbol>) -> Set<FirPropertySymbol>,
    ): Set<FirPropertySymbol> {
        var start: Set<FirPropertySymbol> = emptySet()
        breaks[loop] = live
        while (true) {
            continues[loop] = start
            val next = pass(start)
            if (next == start) return start
            start = next
        }
    }

    private fun tryCatch(element: FirTryExpression, live: Set<FirPropertySymbol>): Set<FirPropertySymbol> {
        val outer = handlers
        val normal = element.finallyBlock?.let { before(it, live) } ?: live
        val failed = element.finallyBlock?.let { before(it, outer) } ?: outer
        val caught = element.catches.fold(failed) { reads, catch ->
            reads + (before(
                catch.block,
                normal
            ) - catch.parameter.symbol)
        }
        handlers = outer + caught
        val body = try {
            before(element.tryBlock, normal)
        } finally {
            handlers = outer
        }
        return body + caught
    }

    private fun children(element: FirElement): List<FirElement> {
        val children = ArrayList<FirElement>()
        element.acceptChildren(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (children.none { it === element }) children += element
            }
        })
        return children
    }

    private fun reads(element: FirElement): Set<FirPropertySymbol> {
        val reads = HashSet<FirPropertySymbol>()
        element.acceptChildren(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element is FirPropertyAccessExpression) {
                    (element.calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol)?.let(reads::add)
                }
                element.acceptChildren(this)
            }
        })
        return reads
    }

    private fun locals(function: FirFunction): List<FirProperty> {
        val locals = ArrayList<FirProperty>()
        function.body?.acceptChildren(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element is FirFunction || element is FirRegularClass || element is FirAnonymousObject) return
                if (element is FirProperty && element.symbol is FirLocalPropertySymbol) locals += element
                element.acceptChildren(this)
            }
        })
        return locals
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun report(function: FirFunction) {
        val body = function.body ?: return
        before(body, emptySet())
        val reported = HashSet<FirPropertySymbol>()
        locals(function).forEach { local ->
            val site =
                sites.entries.filter { local.symbol in it.value }.minByOrNull { it.key.source?.startOffset ?: 0 }?.key
                    ?: return@forEach
            val problem = problem(local) ?: return@forEach
            if (reported.add(local.symbol)) reporter.reportOn(
                local.source,
                StageDiagnostics.STAGE_ERROR,
                message(local, site, problem)
            )
        }
        reportOperands(body)
    }

    private fun problem(local: FirProperty): String? {
        if (local.name != SpecialNames.ITERATOR) {
            val type = local.delegate?.resolvedType ?: local.returnTypeRef.coneType
            return type.stageProblem(session)
        }
        val iterable = (local.initializer as? FirFunctionCall)?.explicitReceiver?.resolvedType ?: return null
        if (iterable.inherits(INT_PROGRESSION)) return null
        if (iterable.inherits(SEQUENCE)) return LOOP_SEQUENCE
        val element =
            local.initializer?.resolvedType?.typeArguments?.firstOrNull() as? ConeKotlinTypeProjection ?: return null
        return element.type.stageProblem(session)?.let { "the copy of its elements is saved with the story, but $it" }
    }

    private fun message(local: FirProperty, site: FirFunctionCall, problem: String): String {
        val where = callName(site)
        if (local.name == SpecialNames.ITERATOR) {
            return if (problem == LOOP_SEQUENCE) "A for loop that calls $where $problem"
            else "A for loop that calls $where goes over a copy of its elements: $problem; loop over values a story can keep, such as ids, and look the rest up inside"
        }
        val name = if (local.name.isSpecial) "A value computed here" else "'${local.name}'"
        return "$name is used after $where, so the story saves it there, but $problem; compute it after the call or make it serializable"
    }

    private fun callName(call: FirFunctionCall): String {
        val symbol = call.calleeReference.toResolvedCallableSymbol()
        val name = symbol?.name?.asString() ?: "a stage"
        if (symbol?.callableId?.packageName == StageFirIds.PACKAGE && name == "checkpoint") {
            val checkpoint = (call.argumentList.arguments.firstOrNull() as? FirLiteralExpression)?.value as? String
            return "checkpoint '${checkpoint ?: "?"}'"
        }
        return "the call '$name'"
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportOperands(body: FirElement) {
        body.acceptChildren(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element is FirFunction || element is FirRegularClass || element is FirAnonymousObject) return
                if (element is FirFunctionCall || element is FirStringConcatenationCall) operands(element as FirCall)
                element.acceptChildren(this)
            }
        })
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun operands(call: FirCall) {
        val arguments =
            call.argumentList.arguments.flatMap { (it as? FirVarargArgumentsExpression)?.arguments ?: listOf(it) }
        val operands = listOfNotNull((call as? FirQualifiedAccessExpression)?.explicitReceiver) + arguments
        val last = operands.indexOfLast(::hasSite)
        val site = operands.getOrNull(last)?.let(::firstSite) ?: return
        operands.take(last).filterNot(::isTrivial).forEach { operand ->
            operand.resolvedType.stageProblem(session)?.let { problem ->
                reporter.reportOn(
                    operand.source, StageDiagnostics.STAGE_ERROR,
                    "A value computed before ${callName(site)} is saved there, but $problem; compute it after the call or make it serializable",
                )
            }
        }
    }

    private fun firstSite(element: FirElement): FirFunctionCall? {
        var found: FirFunctionCall? = null
        fun visit(element: FirElement) {
            if (found != null || element is FirFunction || element is FirRegularClass || element is FirAnonymousObject) return
            if (element is FirFunctionCall && isSite(element)) {
                found = element
                return
            }
            element.acceptChildren(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) = visit(element)
            })
        }
        visit(element)
        return found
    }

    private fun hasSite(element: FirElement) = firstSite(element) != null

    private fun isTrivial(operand: FirExpression): Boolean = when (operand) {
        is FirLiteralExpression, is FirResolvedQualifier, is FirAnonymousFunctionExpression, is FirGetClassCall -> true
        is FirPropertyAccessExpression -> operand.explicitReceiver == null && when (val symbol =
            operand.calleeReference.toResolvedCallableSymbol()) {
            is FirEnumEntrySymbol, is FirValueParameterSymbol -> true
            is FirLocalPropertySymbol -> !symbol.isVar
            else -> false
        }

        else -> false
    }

    private fun ConeKotlinType.inherits(id: ClassId, visited: MutableSet<ClassId> = HashSet()): Boolean {
        val symbol = toRegularClassSymbol(session) ?: return false
        if (symbol.classId == id) return true
        if (!visited.add(symbol.classId)) return false
        return symbol.resolvedSuperTypes.any { it.inherits(id, visited) }
    }

    companion object {
        private val INT_PROGRESSION = ClassId.topLevel(FqName("kotlin.ranges.IntProgression"))
        private val SEQUENCE = ClassId.topLevel(FqName("kotlin.sequences.Sequence"))
        private const val LOOP_SEQUENCE = "cannot go over a sequence: it may never end; call toList() first"

        context(context: CheckerContext, reporter: DiagnosticReporter)
        fun check(function: FirFunction) = StageFirLiveness(context.session).report(function)
    }
}
