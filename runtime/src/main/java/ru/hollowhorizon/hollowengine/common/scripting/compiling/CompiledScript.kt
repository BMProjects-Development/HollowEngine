package ru.hollowhorizon.hollowengine.common.scripting.compiling

import java.io.File
import kotlin.reflect.KClass
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptEvaluationConfiguration

interface CompiledScript {
    val name: String
    val type: KClass<*>
    val implicitReceiverCount: Int

    /** Whether the script declared `@file:ClientSide`. Reading it never loads the script class. */
    val isClientSide: Boolean

    fun <T> execute(body: ScriptEvaluationConfiguration.Builder.() -> Unit = {}): Result<T>

    /** Runs the script like [execute], but keeps what its last expression evaluated to. */
    fun evaluate(body: ScriptEvaluationConfiguration.Builder.() -> Unit = {}): Result<ScriptResult>

    class WithFile(val base: CompiledScript, val file: File) : CompiledScript by base
}

/** A finished script run. [hasValue] is `false` when the script ended in a statement rather than an expression. */
class ScriptResult(val instance: Any?, val value: Any?, val hasValue: Boolean) {
    companion object {
        /** `null` for a run that threw; the caller reports [ResultValue.Error.error] instead. */
        fun of(value: ResultValue): ScriptResult? = when (value) {
            is ResultValue.Value -> ScriptResult(value.scriptInstance, value.value, hasValue = true)
            is ResultValue.Unit -> ScriptResult(value.scriptInstance, null, hasValue = false)
            else -> null
        }
    }
}
