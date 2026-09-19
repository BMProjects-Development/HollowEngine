package ru.hollowhorizon.hollowengine.common.plugin.mixins

import org.jetbrains.kotlin.config.CompilerConfigurationKey
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinSpecLayout

data class MixinSourceLocation(val file: String, val line: Int, val column: Int, val endLine: Int, val endColumn: Int)

/** An injection point that names a member of [owner]. A call when [isField] is false, a field read otherwise. */
data class MixinMemberPoint(val owner: String, val member: String, val isField: Boolean)

/**
 * One handler of a mixin script as the script spells it: names as written, nothing resolved yet.
 */
data class MixinDeclaration(
    val key: String,
    val kind: MixinSpecLayout.Kind,
    val target: String,
    val method: String,
    val point: MixinSpecLayout.Point,
    val member: MixinMemberPoint?,
    val ordinal: Int,
    val shift: MixinSpecLayout.Shift,
    val valueType: String?,
    val location: MixinSourceLocation?,
)

data class MixinError(val message: String, val location: MixinSourceLocation?)

/** What the IR pass found in one compilation, read back once the compiler is done. */
class MixinDeclarationCollector {
    val declarations = mutableListOf<MixinDeclaration>()
    val errors = mutableListOf<MixinError>()

    companion object {
        val KEY = CompilerConfigurationKey.create<MixinDeclarationCollector>("HollowEngine mixin declarations")
    }
}
