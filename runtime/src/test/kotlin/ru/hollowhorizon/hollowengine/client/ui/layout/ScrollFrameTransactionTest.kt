package ru.hollowhorizon.hollowengine.client.ui.layout

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrollFrameTransactionTest {
    @Test
    fun `composition observes the scroll offset used by the same frame layout`() {
        val scroll = UiScrollHandle()
        var composedOffset = -1f

        HollowUiSurface().use { surface ->
            surface.setContent {
                composedOffset = scroll.offsetY
                Box(
                    id = "viewport",
                    modifier = Modifier.size(100.px, 100.px).then(scrollModifier(horizontal = false, state = scroll)),
                ) {
                    Box(modifier = Modifier.size(100.px, 300.px))
                }
            }
            surface.frame(200f, 200f, -1f, -1f, 0L)

            scroll.scrollTo(y = 50f)
            val frame = surface.frame(200f, 200f, -1f, -1f, 16_000_000L)
            val viewport = frame.nodes.single { it.id == "viewport" }

            assertEquals(50f, composedOffset)
            assertEquals(50f, frame.layout[viewport].scrollOffset.y)
        }
    }

    @Test
    fun `scroll requested during composition never desyncs composition from layout`() {
        val scroll = UiScrollHandle()
        var composedOffset = -1f
        val typed = TypingModel()

        HollowUiSurface().use { surface ->
            surface.setContent {
                composedOffset = scroll.offsetY
                // Reading `typed.count` makes this recompose every "keystroke", like the real editor.
                // Caret-follow requests the scroll from a composition SideEffect, so it must not take
                // effect until it can be applied before a composition - never same-frame after it.
                val caretTarget = typed.count * 10f
                SideEffect { if (caretTarget > 0f) scroll.scrollTo(y = caretTarget) }
                Box(
                    id = "viewport",
                    modifier = Modifier.size(100.px, 100.px).then(scrollModifier(horizontal = false, state = scroll)),
                ) {
                    Box(modifier = Modifier.size(100.px, 300.px))
                }
            }
            surface.frame(200f, 200f, -1f, -1f, 0L)

            // Every frame, whatever composition saw must equal what that frame's layout used - no jitter.
            for (tick in 1..6) {
                typed.count = tick
                val frame = surface.frame(200f, 200f, -1f, -1f, tick * 16_000_000L)
                val viewport = frame.nodes.single { it.id == "viewport" }
                assertEquals(
                    composedOffset,
                    frame.layout[viewport].scrollOffset.y,
                    "composition and layout offsets diverged on tick $tick",
                )
            }
            // And it followed the caret (clamped to the 200px scroll range).
            assertEquals(50f, composedOffset)
        }
    }

    @Test
    fun `animated scroll progresses without jumping to its target`() {
        val scroll = UiScrollHandle()

        HollowUiSurface().use { surface ->
            surface.setContent {
                Box(
                    id = "viewport",
                    modifier = Modifier.size(100.px, 100.px).then(scrollModifier(horizontal = false, state = scroll)),
                ) {
                    Box(modifier = Modifier.size(100.px, 300.px))
                }
            }
            surface.frame(200f, 200f, -1f, -1f, 0L)

            scroll.animateScrollBy(deltaY = 80f)
            val firstFrame = surface.frame(200f, 200f, -1f, -1f, 16_000_000L)
            val firstViewport = firstFrame.nodes.single { it.id == "viewport" }
            val firstOffset = firstFrame.layout[firstViewport].scrollOffset.y
            assertTrue(firstOffset in 0f..<80f)

            val finalFrame = surface.frame(200f, 200f, -1f, -1f, 240_000_000L)
            val finalViewport = finalFrame.nodes.single { it.id == "viewport" }
            assertEquals(80f, finalFrame.layout[finalViewport].scrollOffset.y)
        }
    }

    @Test
    fun `animated scroll deltas accumulate against the active target`() {
        val scroll = UiScrollHandle()

        HollowUiSurface().use { surface ->
            surface.setContent {
                Box(
                    id = "viewport",
                    modifier = Modifier.size(100.px, 100.px).then(scrollModifier(horizontal = false, state = scroll)),
                ) {
                    Box(modifier = Modifier.size(100.px, 300.px))
                }
            }
            surface.frame(200f, 200f, -1f, -1f, 0L)

            scroll.animateScrollBy(deltaY = 40f)
            surface.frame(200f, 200f, -1f, -1f, 16_000_000L)
            scroll.animateScrollBy(deltaY = 40f)
            surface.frame(200f, 200f, -1f, -1f, 32_000_000L)

            val finalFrame = surface.frame(200f, 200f, -1f, -1f, 240_000_000L)
            val finalViewport = finalFrame.nodes.single { it.id == "viewport" }
            assertEquals(80f, finalFrame.layout[finalViewport].scrollOffset.y)
        }
    }
}

private class TypingModel {
    var count by mutableStateOf(0)
}
