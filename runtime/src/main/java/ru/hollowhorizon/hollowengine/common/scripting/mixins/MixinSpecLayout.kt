package ru.hollowhorizon.hollowengine.common.scripting.mixins

import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinSpec

/**
 * The [ScriptMixinSpec] layout for the compiler addon, which must not refer to bridge classes itself: on
 * Fabric they are relocated together with the engine, and the compiler addon is not. The constants are
 * inlined where they are used; the enums repeat the bridge ones name for name, which is all the spec
 * stores.
 */
object MixinSpecLayout {
    const val VERSION: Int = ScriptMixinSpec.VERSION
    const val CLASS_SUFFIX: String = ScriptMixinSpec.CLASS_SUFFIX
    const val SPEC_ANNOTATION: String = ScriptMixinSpec.SPEC_ANNOTATION
    const val HANDLER_ANNOTATION: String = ScriptMixinSpec.HANDLER_ANNOTATION

    /** See [ScriptMixinSpec.Kind]. */
    enum class Kind { INJECT, MODIFY_RETURN_VALUE, MODIFY_EXPRESSION_VALUE, WRAP_OPERATION, WRAP_METHOD }

    /** See [ScriptMixinSpec.Point]. */
    enum class Point { HEAD, RETURN, TAIL, INVOKE, FIELD }

    /** See [ScriptMixinSpec.Shift]. */
    enum class Shift { BEFORE, AFTER }
}
