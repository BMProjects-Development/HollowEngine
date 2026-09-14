import ru.hollowhorizon.hollowengine.common.scripting.reload.ScriptSides
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptSidesTests {
    @Test
    fun `client side is found wherever the annotation can be written`() {
        listOf(
            "@file:ClientSide\n\n@SubscribeEvent\nfun a(e: Event) {}",
            "@file:Import(\"common.kts\") @file:ClientSide",
            "@file : ClientSide",
            "@file:[SharedScript ClientSide]",
            "@file:[ClientSide]",
            "@file:ru.hollowhorizon.hollowengine.common.scripting.annotations.ClientSide",
        ).forEach { text -> assertTrue(ScriptSides.declaresClientSide(text), text) }
    }

    @Test
    fun `comments and look-alikes do not make a script client side`() {
        listOf(
            "// @file:ClientSide\nval a = 1",
            "/* @file:ClientSide */\nval a = 1",
            "@file:ServerSide",
            "@file:ClientSideHelpers",
            "@file:[MyClientSide]",
            "val ClientSide = 1",
        ).forEach { text -> assertFalse(ScriptSides.declaresClientSide(text), text) }
    }
}
