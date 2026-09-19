package ru.hollowhorizon.hollowengine.common.scripting.mixins

import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinDispatch


@DslMarker
annotation class MixinDsl

/** Injection points that need no target. */
enum class Point {
    /** Before the first instruction. */
    HEAD,

    /** Before every `return`. */
    RETURN,

    /** Before the last `return`. */
    TAIL,
}

/** Whether a body goes before or after the call it is attached to. */
enum class Shift {
    BEFORE, AFTER,
}

/** An injection point inside the target method: a call or a field read. Built with [call] and [field]. */
class At internal constructor()

/**
 * The call of [method] on [O], or on a subclass of it. When the target method makes that call several
 * times, [ordinal] picks one, counting from zero; by default every one of them is used.
 *
 * [method] may carry a JVM descriptor, `"foo(I)V"`, when [O] has several methods of that name.
 */
@Suppress("UNUSED_PARAMETER")
fun <O> call(method: String, ordinal: Int = -1, shift: Shift = Shift.BEFORE): At = At()

/** The read of field [name] of [O]. [ordinal] works as in [call]. */
@Suppress("UNUSED_PARAMETER")
fun <O> field(name: String, ordinal: Int = -1): At = At()

/**
 * Mixins into [T].
 *
 * A method is named by its name, or by its name and JVM descriptor when [T] declares several methods of
 * that name: `"hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"`. It must be declared by [T]
 * itself; to change an inherited method, mix into the class that declares it.
 */
@MixinDsl
@Suppress("UNUSED_PARAMETER")
class MixinTarget<T : Any> internal constructor(private val script: MixinScript) {
    /** `@Inject`: runs a body at a point of [method]. */
    fun inject(method: String, block: Inject<T>.() -> Unit) = Inject<T>(script).block()

    /** `@ModifyReturnValue`: replaces what [method] returns. [R] is its return type. */
    fun <R> modifyReturnValue(method: String, block: ModifyValue<T, R>.() -> Unit) = ModifyValue<T, R>(script).block()

    /** `@ModifyExpressionValue`: replaces the result of a call or a field read inside [method]. */
    fun <R> modifyExpressionValue(method: String, block: ModifyValue<T, R>.() -> Unit) =
        ModifyValue<T, R>(script).block()

    /** `@WrapOperation`: runs a body instead of a call inside [method]; it decides whether to make the call. */
    fun wrapOperation(method: String, block: WrapOperation<T>.() -> Unit) = WrapOperation<T>(script).block()

    /** `@WrapMethod`: runs a body instead of the whole [method]; it decides whether to run the original. */
    fun wrapMethod(method: String, block: WrapMethod<T>.() -> Unit) = WrapMethod<T>(script).block()

    /** Everything that changes [method], in terms of what happens in it rather than in terms of Mixin. */
    fun method(method: String, block: MethodMixins<T>.() -> Unit) = MethodMixins<T>(script).block()
}

@MixinDsl
@Suppress("UNUSED_PARAMETER")
class Inject<T> internal constructor(private val script: MixinScript) {
    /** Where the body runs. [Point.HEAD] when not given. */
    fun at(point: Point, ordinal: Int = -1) = Unit

    fun at(point: At) = Unit

    fun code(generatedKey: String = "", block: InjectContext<T>.() -> Unit) =
        script.register(generatedKey, injectHandler(block))
}

@MixinDsl
@Suppress("UNUSED_PARAMETER")
class ModifyValue<T, R> internal constructor(private val script: MixinScript) {
    /** For [MixinTarget.modifyReturnValue] this can only be [Point.RETURN], which is the default. */
    fun at(point: Point, ordinal: Int = -1) = Unit

    fun at(point: At) = Unit

    fun code(generatedKey: String = "", block: ValueContext<T>.(original: R) -> R) =
        script.register(generatedKey, valueHandler(block))
}

@MixinDsl
@Suppress("UNUSED_PARAMETER")
class WrapOperation<T> internal constructor(private val script: MixinScript) {
    fun at(point: At) = Unit

    /** [instance] is what the call is made on, `null` for a static call. */
    fun code(generatedKey: String = "", block: CallContext<T>.(instance: Any?, args: MixinArgs) -> Any?) =
        script.register(generatedKey, callHandler(block))
}

@MixinDsl
class WrapMethod<T> internal constructor(private val script: MixinScript) {
    fun code(generatedKey: String = "", block: WrapContext<T>.() -> Any?) =
        script.register(generatedKey, wrapHandler(block))
}

/**
 * Mixins into one method, named after what they do. Each maps onto the Mixin feature that fits it best:
 * [before], [after] and the call hooks are `@Inject`, [replaceCall] is `@WrapOperation`, [modifyCall] is
 * `@ModifyExpressionValue`, [returns] is `@ModifyReturnValue` and [wrap] is `@WrapMethod`. The wrapping
 * ones compose with other mods changing the same place, where `@Redirect` or `@Overwrite` would not.
 */
@MixinDsl
@Suppress("UNUSED_PARAMETER")
class MethodMixins<T> internal constructor(private val script: MixinScript) {
    /** Runs [block] when the method starts. */
    fun before(generatedKey: String = "", block: InjectContext<T>.() -> Unit) =
        script.register(generatedKey, injectHandler(block))

    /** Runs [block] before every `return` of the method; [InjectContext.result] is the value being returned. */
    fun after(generatedKey: String = "", block: InjectContext<T>.() -> Unit) =
        script.register(generatedKey, injectHandler(block))

    /** Runs [block] right before the method calls [method] on [O]. See [call] for [method] and [ordinal]. */
    fun <O> beforeCall(
        method: String,
        ordinal: Int = -1,
        generatedKey: String = "",
        block: InjectContext<T>.() -> Unit,
    ) = script.register(generatedKey, injectHandler(block))

    /** Runs [block] right after the method calls [method] on [O]. */
    fun <O> afterCall(
        method: String,
        ordinal: Int = -1,
        generatedKey: String = "",
        block: InjectContext<T>.() -> Unit,
    ) = script.register(generatedKey, injectHandler(block))

    /**
     * Runs [block] instead of the call of [method] on [O]. `proceed()` makes the original call. [instance] is
     * what the call is made on, `null` for a static call.
     */
    fun <O> replaceCall(
        method: String,
        ordinal: Int = -1,
        generatedKey: String = "",
        block: CallContext<T>.(instance: O, args: MixinArgs) -> Any?,
    ) = script.register(generatedKey, callHandler(block))

    /** Replaces what the call of [method] on [O] returned. [R] is its return type. */
    fun <O, R> modifyCall(
        method: String,
        ordinal: Int = -1,
        generatedKey: String = "",
        block: ValueContext<T>.(original: R) -> R,
    ) = script.register(generatedKey, valueHandler(block))

    /** Replaces what the method returns. [R] is its return type. */
    fun <R> returns(generatedKey: String = "", block: ValueContext<T>.(original: R) -> R) =
        script.register(generatedKey, valueHandler(block))

    /** Runs [block] instead of the method. `proceed()` runs the original. */
    fun wrap(generatedKey: String = "", block: WrapContext<T>.() -> Any?) =
        script.register(generatedKey, wrapHandler(block))
}

private fun <T> injectHandler(block: InjectContext<T>.() -> Unit) = ScriptMixinDispatch.Handler { self, args, info ->
    InjectContext<T>(self, MixinArgs(args), info as CallbackInfo).block()
    null
}

@Suppress("UNCHECKED_CAST")
private fun <T, R> valueHandler(block: ValueContext<T>.(original: R) -> R) =
    ScriptMixinDispatch.Handler { self, args, original ->
        ValueContext<T>(self, MixinArgs(args)).block(original as R)
    }

@Suppress("UNCHECKED_CAST")
private fun <T, O> callHandler(block: CallContext<T>.(instance: O, args: MixinArgs) -> Any?) =
    ScriptMixinDispatch.Handler { self, args, call ->
        call as ScriptMixinDispatch.Call
        val arguments = MixinArgs(args)
        CallContext<T>(self, arguments, call).block(call.receiver() as O, arguments)
    }

private fun <T> wrapHandler(block: WrapContext<T>.() -> Any?) = ScriptMixinDispatch.Handler { self, args, original ->
    WrapContext<T>(self, MixinArgs(args), original as ScriptMixinDispatch.Original).block()
}
