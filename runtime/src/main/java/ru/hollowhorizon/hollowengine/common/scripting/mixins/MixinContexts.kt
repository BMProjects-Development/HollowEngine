package ru.hollowhorizon.hollowengine.common.scripting.mixins

import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinDispatch

/** Arguments of the target method, or of the wrapped call. Changing one only matters before `proceed()`. */
class MixinArgs internal constructor(internal val values: Array<Any?>) : Iterable<Any?> {
    val size: Int get() = values.size

    @Suppress("UNCHECKED_CAST")
    operator fun <A> get(index: Int): A = values[index] as A

    operator fun set(index: Int, value: Any?) {
        values[index] = value
    }

    override fun iterator(): Iterator<Any?> = values.iterator()

    override fun toString(): String = values.contentToString()
}

/** What every mixin body sees: the object the method runs on and the arguments it got. */
@MixinDsl
open class MixinContext<T> internal constructor(
    private val instance: Any?,
    val args: MixinArgs,
) {
    /** The object the target method runs on. Static methods have none. */
    @Suppress("UNCHECKED_CAST")
    val self: T
        get() = (instance ?: error("A static method has no instance")) as T
}

class InjectContext<T> internal constructor(
    instance: Any?,
    args: MixinArgs,
    private val info: CallbackInfo,
) : MixinContext<T>(instance, args) {
    val isCancelled: Boolean get() = info.isCancelled

    /** Leaves the target method right after this body. Methods that return something need [result] instead. */
    fun cancel() = info.cancel()

    /**
     * The value the method returns. Reading it makes sense at `RETURN` and `TAIL`; setting it leaves the
     * method right after this body with that value.
     */
    var result: Any?
        get() = returnable().returnValue
        set(value) = returnable().setReturnValue(value)

    @Suppress("UNCHECKED_CAST")
    private fun returnable(): CallbackInfoReturnable<Any?> =
        info as? CallbackInfoReturnable<Any?> ?: error("The target method returns nothing")
}

class ValueContext<T> internal constructor(instance: Any?, args: MixinArgs) : MixinContext<T>(instance, args)

/** Around a call inside the target method. [args] are the arguments of that call. */
class CallContext<T> internal constructor(
    instance: Any?,
    args: MixinArgs,
    private val call: ScriptMixinDispatch.Call,
) : MixinContext<T>(instance, args) {
    /** Makes the original call with the current [args]. */
    fun proceed(): Any? = call.proceed(call.receiver(), args.values)

    /** Makes the original call on [receiver] with [arguments] instead. */
    fun proceed(receiver: Any?, vararg arguments: Any?): Any? = call.proceed(receiver, arrayOf(*arguments))
}

/** Around the whole target method. */
class WrapContext<T> internal constructor(
    instance: Any?,
    args: MixinArgs,
    private val original: ScriptMixinDispatch.Original,
) : MixinContext<T>(instance, args) {
    /** Runs the original method with the current [args]. */
    fun proceed(): Any? = original.call(args.values)

    /** Runs the original method with [arguments] instead. */
    fun proceed(vararg arguments: Any?): Any? = original.call(arrayOf(*arguments))
}
