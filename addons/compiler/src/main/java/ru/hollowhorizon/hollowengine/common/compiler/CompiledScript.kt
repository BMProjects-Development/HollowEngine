package ru.hollowhorizon.hollowengine.common.compiler

import ru.hollowhorizon.hollowengine.common.scripting.compiling.CompiledScript
import ru.hollowhorizon.hollowengine.common.scripting.compiling.HollowEngineScriptEvaluator
import ru.hollowhorizon.hollowengine.common.scripting.compiling.ScriptResult
import ru.hollowhorizon.hollowengine.common.scripting.compiling.convert
import ru.hollowhorizon.hollowengine.common.scripting.compiling.isClientSideScript
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptEvaluationException
import kotlin.reflect.KClass
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.with
import kotlin.script.experimental.api.valueOr
import kotlin.script.experimental.api.CompiledScript as KotlinCompiledScript

data class CompiledScriptImpl(
    override val name: String,
    val script: KotlinCompiledScript,
    val evalConfiguration: ScriptEvaluationConfiguration,
) : CompiledScript {
    private val scriptClassDelegate = lazy {
        runScriptingBlocking {
            script.getClass(evalConfiguration).valueOr { failure ->
                throw ScriptEvaluationException(name, failure.reports.map { it.convert() })
            }
        }
    }

    override val type: KClass<*>
        get() = scriptClassDelegate.value

    override val implicitReceiverCount: Int
        get() = script.compilationConfiguration[ScriptCompilationConfiguration.implicitReceivers]?.size ?: 0

    override val isClientSide: Boolean
        get() = script.compilationConfiguration[ScriptCompilationConfiguration.isClientSideScript] == true

    override fun <T> execute(body: ScriptEvaluationConfiguration.Builder.() -> Unit): Result<T> =
        @Suppress("UNCHECKED_CAST")
        evaluate(body).map { result -> result.instance as T }

    override fun evaluate(body: ScriptEvaluationConfiguration.Builder.() -> Unit): Result<ScriptResult> {
        val evaluator = HollowEngineScriptEvaluator()

        val result = runScriptingBlocking {
            evaluator(script, evalConfiguration.with(body))
        }

        if (result !is ResultWithDiagnostics.Success) {
            return Result.failure(ScriptEvaluationException(name, result.reports.map { it.convert() }))
        }
        val value = result.value.returnValue
        if (value is ResultValue.Error) return Result.failure(value.error)
        return ScriptResult.of(value)?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("Script '$name' was not evaluated"))
    }
}
