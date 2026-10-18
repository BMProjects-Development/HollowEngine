package ru.hollowhorizon.hollowengine.client.ui.layout

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PopupOverlayLayoutTest {
    private fun overlayRoot(popup: PopupNode): BoxNode {
        val overlay = BoxNode(
            measurePolicy = PopupOverlayMeasurePolicy,
            modifiers = listOf(Modifier.size(100.percent, 100.percent)),
        ).also { it.children.add(popup) }
        return BoxNode(
            measurePolicy = UiMeasurePolicies.box(UiBoxMode.STACK),
            modifiers = listOf(Modifier.size(200.px, 200.px)),
        ).also { it.children.add(overlay) }
    }

    @Test
    fun `overlay anchors a popup below-start of its anchor bounds`() {
        val popup = PopupNode(
            anchorBounds = UiRect(40f, 30f, 20f, 10f),
            alignment = UiPopupAlignment.BelowStart,
            modifiers = listOf(Modifier.size(50.px, 24.px)),
        )
        val frame = HollowUiRuntime().frame(overlayRoot(popup), 200f, 200f, -1f, -1f, 0L)
        val rect = frame.layout[popup].rect
        assertEquals(UiRect(40f, 40f, 50f, 24f), rect)
    }

    @Test
    fun `a popup absorbs clicks on its own background`() {
        val popup = PopupNode(
            anchorBounds = UiRect(40f, 40f, 0f, 0f),
            alignment = UiPopupAlignment.BelowStart,
            modifiers = listOf(Modifier.size(60.px, 30.px).input(hoverable = true)),
        )
        val frame = HollowUiRuntime().frame(overlayRoot(popup), 200f, 200f, -1f, -1f, 0L)
        val hit = frame.hitTest(50f, 50f)
        assertSame(popup, hit?.node, "the press lands on the popup, not the content underneath")
    }
}
