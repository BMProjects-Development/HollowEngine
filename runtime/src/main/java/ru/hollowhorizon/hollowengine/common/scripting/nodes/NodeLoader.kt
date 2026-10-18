package ru.hollowhorizon.hollowengine.common.scripting.nodes

import ru.hollowhorizon.hollowengine.common.scripting.annotations.State
import ru.hollowhorizon.hollowengine.common.scripting.state.StateExecutor
import ru.hollowhorizon.hollowengine.common.utils.LambdaGenerator
import ru.hollowhorizon.hollowengine.common.utils.UnsafeTools
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method

fun NodeScript.prepareExecutor(executor: StateExecutor) {
    val lookup = MethodHandles.privateLookupIn(javaClass, UnsafeTools.lookup)

    javaClass.nodeStateFunctions().forEach { (name, method) ->
        executor.addState(name, LambdaGenerator.createSuspendRunnable(lookup, method, this))
    }
}

internal fun Class<*>.nodeStateFunctions(): Map<String, Method> = declaredMethods.mapNotNull { method ->
    val annotation = method.getAnnotation(State::class.java) ?: return@mapNotNull null
    (annotation.name.takeIf(String::isNotBlank) ?: method.name) to method
}.toMap()
