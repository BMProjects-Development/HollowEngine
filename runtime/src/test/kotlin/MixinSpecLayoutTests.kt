import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinSpec
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinSpecLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class MixinSpecLayoutTests {
    /** The compiler writes these names and the bootstrap parses them back, each from its own copy. */
    @Test
    fun `the compiler writes the names the bootstrap reads`() {
        assertEquals(ScriptMixinSpec.Kind.entries.map { it.name }, MixinSpecLayout.Kind.entries.map { it.name })
        assertEquals(ScriptMixinSpec.Point.entries.map { it.name }, MixinSpecLayout.Point.entries.map { it.name })
        assertEquals(ScriptMixinSpec.Shift.entries.map { it.name }, MixinSpecLayout.Shift.entries.map { it.name })
    }
}
