package ru.hollowhorizon.hollowengine.common.compiler

import ru.hollowhorizon.hollowengine.common.scripting.compiling.CompiledScript
import ru.hollowhorizon.hollowengine.common.scripting.compiling.HollowEngineScriptEvaluator
import ru.hollowhorizon.hollowengine.common.scripting.compiling.isClientSideScript
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptEvaluationException
import kotlin.reflect.KClass
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.with

data class CompiledScriptImpl(
    override val name: String,
    val script: kotlin.script.experimental.api.CompiledScript,
    val evalConfiguration: ScriptEvaluationConfiguration,
) : CompiledScript {
    private val scriptClassDelegate = lazy {
        runScriptingBlocking {
            (script.getClass(evalConfiguration) as ResultWithDiagnostics.Success).value
        }
    }

    override val type: KClass<*>
        get() = scriptClassDelegate.value

    override val implicitReceiverCount: Int
        get() = script.compilationConfiguration[ScriptCompilationConfiguration.implicitReceivers]?.size ?: 0

    override val isClientSide: Boolean
        get() = script.compilationConfiguration[ScriptCompilationConfiguration.isClientSideScript] == true

    override fun <T> execute(body: ScriptEvaluationConfiguration.Builder.() -> Unit): Result<T> {
        val evaluator = HollowEngineScriptEvaluator()

        val result = runScriptingBlocking {
            evaluator(script, evalConfiguration.with(body))
        }

        return if (result is ResultWithDiagnostics.Success) {
            val value = result.value.returnValue
            if (value is ResultValue.Error) {
                Result.failure(value.error)
            } else {
                Result.success(result.value.returnValue.scriptInstance as T)
            }
        } else {
            Result.failure(ScriptEvaluationException(name, result.reports.map { it.convert() }))
        }
    }
}
