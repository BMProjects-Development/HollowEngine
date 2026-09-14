package ru.hollowhorizon.hollowengine.common.scripting.compiling

import java.io.File
import kotlin.reflect.KClass
import kotlin.script.experimental.api.ScriptEvaluationConfiguration

interface CompiledScript {
    val name: String
    val type: KClass<*>
    val implicitReceiverCount: Int

    /** Whether the script declared `@file:ClientSide`. Reading it never loads the script class. */
    val isClientSide: Boolean

    fun <T> execute(body: ScriptEvaluationConfiguration.Builder.() -> Unit = {}): Result<T>

    class WithFile(val base: CompiledScript, val file: File) : CompiledScript by base
}
