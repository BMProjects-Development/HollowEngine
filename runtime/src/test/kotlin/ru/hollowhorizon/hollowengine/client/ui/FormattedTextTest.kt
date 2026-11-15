package ru.hollowhorizon.hollowengine.client.ui

import ru.hollowhorizon.hollowengine.client.ui.style.textEffects
import ru.hollowhorizon.hollowengine.client.ui.style.fontSize
import ru.hollowhorizon.hollowengine.client.ui.text.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FormattedTextTest {
    @Test
    fun `nested scales change span font size and preserve text effects`() {
        HollowUiSurface().use { surface ->
            surface.setContent {
                FormattedText("a<scale=1.5>b<scale=2><b>c</b></scale></scale>", modifier = Modifier.fontSize(10f))
            }
            val spans = surface.frame(500f, 100f, -1f, -1f, 0L).nodes.filterIsInstance<SpanNode>()
            assertEquals(listOf(10f, 15f, 30f), spans.map { it.resolvedSnapshot.fontSize })
            assertIs<Bold>(spans[2].resolvedSnapshot.textEffects.single())
        }
    }
    @Test
    fun `formatted text reveals unicode characters without exposing markup`() {
        HollowUiSurface().use { surface ->
            surface.setContent {
                FormattedText(
                    value = "A<b>Б😀</b>C",
                    visibleCharacters = 3,
                    pendingTags = listOf("pending"),
                )
            }

            val frame = surface.frame(500f, 100f, -1f, -1f, 0L)
            val spans = frame.nodes.filterIsInstance<SpanNode>()
            assertEquals(listOf("A", "Б😀", "C"), spans.map { it.text })
            assertIs<Bold>(spans[1].resolvedSnapshot.textEffects.single())
            assertEquals(setOf("pending"), spans[2].tags)
        }
    }
}
