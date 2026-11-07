package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.mutableStateOf
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.assertEquals

class NumberFieldSyncTest {
    private fun UiNode.spans(): List<String> =
        (if (this is SpanNode) listOf(text) else emptyList()) + children.flatMap { it.spans() }

    /** What was laid out for the spans, which is what is drawn. */
    private fun UiNode.laid(): List<String> =
        (if (this is SpanNode) listOf(lineLayout?.lines.orEmpty().joinToString("") { it.text }) else emptyList()) +
            children.flatMap { it.laid() }

    @Test
    fun `a value changed from outside is what the field shows`() {
        val value = mutableStateOf(1.0)
        HollowUiSurface().use { surface ->
            val root = surface.setContent {
                NumberField(value.value, id = "n", modifier = Modifier.size(100.px, 20.px)) { value.value = it }
            }
            surface.frame(200f, 100f, -1f, -1f, 0L)
            assertEquals(listOf("1"), root.spans())
            assertEquals(listOf("1"), root.laid())
            value.value = 5.0
            surface.frame(200f, 100f, -1f, -1f, 16_000_000L)
            assertEquals(listOf("5"), root.spans())
            assertEquals(listOf("5"), root.laid())
        }
    }
}
