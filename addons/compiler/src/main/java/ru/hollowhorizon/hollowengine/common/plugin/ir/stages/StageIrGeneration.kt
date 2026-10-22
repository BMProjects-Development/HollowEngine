package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.isAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageMessages

/**
 * Compiles stages of every script in a module: marks script classes so the runtime can find their
 * instances, compiles their actions, turns `@State` lambdas into stages, rewrites each stage body for saving and
 * resuming, and generates entries, that runtime starts stages through. Does nothing without story runtime on the
 * classpath.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class StageIrGeneration(private val context: IrPluginContext) {
    private val symbols = StageSymbols.find(context)
    private val types = StageTypes(context)

    fun generate(module: IrModuleFragment) {
        val symbols = symbols ?: return
        module.files.forEach { file ->
            file.declarations.filterIsInstance<IrClass>().filter { it.origin == IrDeclarationOrigin.SCRIPT_CLASS }
                .forEach { script ->
                    ScriptStages(
                        StageScope(context, symbols, types, StageReporter(context, file), script), file
                    ).compile()
                }
        }
    }

    private class ScriptStages(private val stages: StageScope, private val file: IrFile) {
        private val script = stages.script
        private val reporter = stages.reporter

        /** The stages made from lambdas, with how many of their arguments are captured values. */
        private var captures: Map<IrSimpleFunction, Int> = emptyMap()

        fun compile() {
            script.annotations += stages.scriptAnnotation(file.fileEntry.name)
            StageActions(stages).compile()
            val lifter = StageLambdaLifter(stages)
            script.declarations.toList().forEach { it.transform(lifter, null) }
            script.declarations += lifter.lifted.keys
            captures = lifter.lifted

            val functions = script.declarations.filterIsInstance<IrSimpleFunction>().filter(stages::isSuspendState)
            functions.groupBy(stages::stageName).filterValues { it.size > 1 }.forEach { (name, duplicates) ->
                duplicates.drop(1).forEach {
                    reporter.error(
                        it, "Two stages of this script are named '$name'; give one of them different @State(name = ...)"
                    )
                }
            }
            functions.forEach(::compile)
            checkMisplaced()
        }

        private fun compile(function: IrSimpleFunction) {
            if (!isValid(function)) return
            val body = function.body as? IrBlockBody ?: return
            val name = stages.stageName(function)
            val hash = body.dump().hashCode()
            if (!sitesArePlaced(function)) return

            val normalizer = StageNormalizer(stages, function)
            val errors = errorCount()
            val statements = normalizer.normalize(body.statements.toList())
            if (errorCount() != errors) return
            val layout = StageLayout(stages, function).apply { layout(statements) }
            layout.saved.forEach { variable ->
                val site = layout.sites.first { variable in it.live }
                stages.types.problem(variable.type)?.let { problem ->
                    if (variable in normalizer.loopCopies) {
                        reporter.error(
                            variable,
                            "A for loop that calls stages goes over a copy of its elements saved with the story, but $problem; loop over values a story can keep, such as ids, and look the rest up inside"
                        )
                        return@forEach
                    }
                    val what =
                        if (variable.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE) "A value computed before" else "'${variable.name}', used after"
                    reporter.error(
                        variable,
                        "$what the stage call '${site.name}' is saved there, but $problem; compute it after the call or make it serializable"
                    )
                }
            }
            if (errorCount() != errors) return
            StageRewriter(stages, function, layout).apply {
                rewrite(statements, name, hash)
                script.declarations += entry(name)
                captures[function]?.let {
                    script.declarations += lambdaCall(name, it)
                    script.declarations += stages.lambdaResult(function, name)
                }
            }
        }

        private fun isValid(function: IrSimpleFunction): Boolean {
            val problem = when {
                function.parent != script -> "A stage must be declared at the top level of a script"
                function.typeParameters.isNotEmpty() -> StageMessages.TYPE_PARAMETERS
                else -> null
            }
            problem?.let { reporter.error(function, it); return false }
            var valid = true
            stages.arguments(function).forEach { parameter ->
                stages.types.problem(parameter.type)?.let {
                    reporter.error(
                        parameter,
                        "The arguments of a stage are saved, but '${
                            stages.argumentName(
                                function,
                                parameter
                            )
                        }' cannot be: $it"
                    )
                    valid = false
                }
            }
            if (!function.returnType.isUnit()) stages.types.problem(function.returnType)?.let {
                reporter.error(function, "The result of a stage is saved, but $it")
                valid = false
            }
            return valid
        }

        /** Stage calls inside lambdas, `catch` or `finally` of a stage cannot be resumed and are errors. */
        private fun sitesArePlaced(function: IrSimpleFunction): Boolean {
            val errors = errorCount()
            function.body?.acceptChildrenVoid(object : IrVisitorVoid() {
                private var nested = 0
                private var handler = 0

                override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

                override fun visitFunction(declaration: IrFunction) {
                    nested++
                    super.visitFunction(declaration)
                    nested--
                }

                override fun visitClass(declaration: IrClass) {
                    nested++
                    super.visitClass(declaration)
                    nested--
                }

                override fun visitTry(aTry: IrTry) {
                    aTry.tryResult.acceptVoid(this)
                    handler++
                    aTry.catches.forEach { it.acceptVoid(this) }
                    aTry.finallyExpression?.acceptVoid(this)
                    handler--
                }

                override fun visitCall(expression: IrCall) {
                    super.visitCall(expression)
                    stages.siteKind(expression) ?: return
                    when {
                        nested > 0 -> reporter.error(
                            expression,
                            StageMessages.NESTED_CALL
                        )

                        handler > 0 -> reporter.error(
                            expression,
                            StageMessages.HANDLER_CALL
                        )
                    }
                }
            })
            return errorCount() == errors
        }

        /** Whatever is left after compiling the stages: checkpoints and `@State` where they cannot work. */
        private fun checkMisplaced() {
            script.acceptChildrenVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

                override fun visitFunction(declaration: IrFunction) {
                    val marked = declaration.annotations.any { it.isAnnotation(StageSymbols.STATE) }
                    if (marked && declaration.parent != script) reporter.error(
                        declaration, StageMessages.TOP_LEVEL
                    )
                    else if (marked && !stages.isSuspendState(declaration)) reporter.error(
                        declaration, StageMessages.SUSPEND
                    )
                    super.visitFunction(declaration)
                }

                override fun visitCall(expression: IrCall) {
                    super.visitCall(expression)
                    if (stages.symbols.isCheckpoint(expression.symbol.owner)) {
                        reporter.error(expression, StageMessages.CHECKPOINT_OUTSIDE)
                    }
                }
            })
        }

        private fun errorCount(): Int = reporter.errors
    }
}
