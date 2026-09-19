package ru.hollowhorizon.hollowengine.common.scripting.mixins

import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import java.io.File

/**
 * Compiles mixin scripts while mixins are being prepared, for scripts the launch has no current spec of.
 * Implemented by the compiler addon and created by [EarlyMixinCompilation] with the compiler jar and the
 * classpath to compile against.
 */
interface EarlyMixinCompiler : AutoCloseable {
    /** The spec of each script, `null` for a script without mixins, a failure for one that does not compile. */
    fun compile(scripts: Map<ScriptId, File>): Map<ScriptId, Result<ByteArray?>>
}
