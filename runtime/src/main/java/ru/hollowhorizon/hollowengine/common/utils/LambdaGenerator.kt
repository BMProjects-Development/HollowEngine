package ru.hollowhorizon.hollowengine.common.utils

import java.lang.invoke.LambdaMetafactory
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import kotlin.coroutines.Continuation

object LambdaGenerator {

    fun createSuspendRunnable(lookup: MethodHandles.Lookup, method: Method, target: Any): suspend () -> Unit {
        val methodHandle = lookup.unreflect(method)

        val samMethodType = MethodType.methodType(Any::class.java, Any::class.java)

        val factoryType = MethodType.methodType(Function1::class.java, target::class.java)

        val instantiatedMethodType = MethodType.methodType(Any::class.java, Continuation::class.java)

        val callSite = LambdaMetafactory.metafactory(
            lookup,
            "invoke",
            factoryType,
            samMethodType,
            methodHandle,
            instantiatedMethodType,
        )

        val function = callSite.target.invoke(target)
        return JavaHacks.forceCast(function)
    }
}