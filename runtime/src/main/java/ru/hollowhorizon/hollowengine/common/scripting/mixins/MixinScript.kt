package ru.hollowhorizon.hollowengine.common.scripting.mixins

import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinDispatch

/**
 * Base class of `.mixin.kts` scripts.
 *
 * A mixin script is read twice. The compiler reads its declarations and writes down which mixins to
 * generate; the bootstrap generates and applies them before the game loads its first class.
 */
abstract class MixinScript(
    /** Qualified id of this script, which the keys of its handlers are prefixed with. */
    val scriptId: String,
) {
    private val registered = LinkedHashMap<String, ScriptMixinDispatch.Handler>()

    /** Bodies this script registered, by the key the compiler gave them. */
    val handlers: Map<String, ScriptMixinDispatch.Handler> get() = registered

    /** Declares mixins into [T]. [T] is only read by the compiler; this call never loads it. */
    fun <T : Any> mixin(block: MixinTarget<T>.() -> Unit) {
        MixinTarget<T>(this).block()
    }

    internal fun register(generatedKey: String, handler: ScriptMixinDispatch.Handler) {
        check(generatedKey.isNotEmpty()) {
            "'$scriptId' was compiled without mixin support, so its mixins have no keys: " +
                "the compiler addon is older than the engine, update it"
        }
        registered[generatedKey] = handler
    }
}
