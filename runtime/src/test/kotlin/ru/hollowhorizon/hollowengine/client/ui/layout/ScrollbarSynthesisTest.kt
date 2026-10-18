package ru.hollowhorizon.hollowengine.client.ui.layout

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollState
import ru.hollowhorizon.hollowengine.client.ui.style.UiModifierResolver
import ru.hollowhorizon.hollowengine.client.ui.style.compileHss
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScrollbarSynthesisTest {
    private fun layout(
        childSize: UiSize,
        scroll: Modifier = Modifier then scrollModifier(horizontal = false),
        scrollbarStyle: String = "",
    ): Pair<UiLayoutResult, BoxNode> {
        val viewport = BoxNode(
            id = "viewport",
            measurePolicy = UiMeasurePolicies.box(),
            modifiers = listOf(Modifier.size(100.px, 100.px) then scroll),
        )
        viewport.children.add(BoxNode(modifiers = listOf(Modifier.size(childSize.width, childSize.height))))
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(viewport) }
        val sheet = scrollbarStyle.takeIf { it.isNotEmpty() }?.let { compileHss("#viewport { $it }") }
        UiModifierResolver(stylesheet = sheet).resolve(root)
        return UiLayoutPipeline().compute(root, 300f, 300f, UiScrollState()) to viewport
    }

    @Test
    fun `a scrollbar node is synthesized and placed at the right edge on overflow`() {
        val (layout, viewport) = layout(UiSize(80.px, 300.px))
        val bars = layout.scrollbars[viewport]
        assertTrue(!bars.isNullOrEmpty(), "overflow should synthesize a scrollbar")

        val bar = assertNotNull(bars).single()
        val barLayout = assertNotNull(layout.nodes[bar], "scrollbar is placed")
        val thumbLayout = assertNotNull(layout.nodes[bar.thumb], "scrollbar thumb is placed")
        assertTrue(barLayout.rect.width < 10f, "vertical track is thin")
        assertTrue(barLayout.rect.height > 40f, "track spans most of the viewport")
        assertTrue(thumbLayout.rect.height < barLayout.rect.height, "thumb is shorter than the track")
        assertTrue(barLayout.rect.x > 80f, "track is at the right edge (x=${barLayout.rect.x})")
    }

    @Test
    fun `hit-testing the synthesized thumb in the gutter returns the thumb`() {
        val (layout, viewport) = layout(UiSize(80.px, 300.px))
        val thumb = layout.scrollbars.getValue(viewport).single().thumb
        val root = layout.root
        val frame = HollowUiFrame(root = root, nodes = listOf(root, viewport), layout = layout)
        val r = layout.nodes.getValue(thumb).rect
        val hit = frame.hitTest(r.x + r.width / 2f, r.y + r.height / 2f)
        assertTrue(hit?.node === thumb, "thumb in the gutter must be hittable (was ${hit?.node?.type})")
    }

    @Test
    fun `no scrollbar is synthesized when content fits`() {
        val (layout, viewport) = layout(UiSize(50.px, 50.px))
        assertTrue(layout.scrollbars[viewport].isNullOrEmpty(), "no overflow → no scrollbar")
    }

    @Test
    fun `a reserving scrollbar displaces the content instead of widening the container`() {
        val (reservedLayout, reservedViewport) = layout(UiSize(80.px, 300.px))
        val (overlayLayout, overlayViewport) = layout(
            UiSize(80.px, 300.px),
            scrollbarStyle = "scrollbar-overlay: true;",
        )

        val reserved = reservedLayout.nodes.getValue(reservedViewport)
        val overlay = overlayLayout.nodes.getValue(overlayViewport)
        assertEquals(100f, reserved.rect.width, 0.01f, "an explicit width must survive the scrollbar")
        assertEquals(overlay.rect.width, reserved.rect.width, 0.01f)
        assertTrue(
            reserved.content.width < overlay.content.width,
            "a reserving scrollbar must take its gutter out of the usable width " +
                    "(${reserved.content.width} vs ${overlay.content.width})",
        )
        assertTrue(!overlayLayout.scrollbars[overlayViewport].isNullOrEmpty(), "overlay still synthesizes a scrollbar")
    }

    @Test
    fun `an axis without a scrollbar keeps scrolling with no bar and no gutter`() {
        val (hiddenLayout, hiddenViewport) = layout(
            UiSize(80.px, 300.px),
            Modifier then scrollModifier(horizontal = false, verticalScrollbar = false),
        )
        val hidden = hiddenLayout.nodes.getValue(hiddenViewport)

        assertTrue(hiddenLayout.scrollbars[hiddenViewport].isNullOrEmpty(), "a hidden bar must not be synthesized")
        assertEquals(100f, hidden.content.width, 0.01f, "a hidden bar must not reserve a gutter")
        assertTrue(hidden.scrollRange.y > 0f, "the container still scrolls")
    }

    @Test
    fun `a stylesheet can style the bar away with zero thickness`() {
        val (layout, viewport) = layout(UiSize(80.px, 300.px), scrollbarStyle = "scrollbar: 0px;")
        val node = layout.nodes.getValue(viewport)

        assertTrue(layout.scrollbars[viewport].isNullOrEmpty(), "nothing to draw means no bar")
        assertEquals(100f, node.content.width, 0.01f, "and nothing to reserve either")
        assertTrue(node.scrollRange.y > 0f, "the container still scrolls")
    }

    @Test
    fun `the thumb of a padded container is hittable in its gutter`() {
        val viewport = BoxNode(
            id = "padded-viewport",
            measurePolicy = UiMeasurePolicies.box(),
            modifiers = listOf(Modifier.size(100.px, 100.px).padding(10.px).then(scrollModifier(horizontal = false))),
        ).also { it.children.add(BoxNode(modifiers = listOf(Modifier.size(60.px, 300.px)))) }
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(viewport) }
        UiModifierResolver().resolve(root)
        val layout = UiLayoutPipeline().compute(root, 300f, 300f, UiScrollState())
        val thumb = layout.scrollbars.getValue(viewport).single().thumb
        val frame = HollowUiFrame(root = layout.root, nodes = listOf(layout.root, viewport), layout = layout)
        val r = layout.nodes.getValue(thumb).rect

        val hit = frame.hitTest(r.x + r.width / 2f, r.y + r.height / 2f)
        assertTrue(hit?.node === thumb, "padding must not clip the thumb out of the hit test (was ${hit?.node?.type})")
    }

    @Test
    fun `pressing the track is a hit so it can page the view`() {
        val (layout, viewport) = layout(UiSize(80.px, 300.px))
        val bar = layout.scrollbars.getValue(viewport).single()
        val track = layout.nodes.getValue(bar).rect
        val thumb = layout.nodes.getValue(bar.thumb).rect
        val frame = HollowUiFrame(root = layout.root, nodes = listOf(layout.root, viewport), layout = layout)

        val y = (thumb.y + thumb.height + track.y + track.height) / 2f
        val hit = frame.hitTest(track.x + track.width / 2f, y)
        assertTrue(hit?.node === bar, "the track must be hittable (was ${hit?.node?.type})")
    }

    @Test
    fun `scrollbar stays at the outer edge when the container has padding`() {
        val viewport = BoxNode(
            id = "padded-viewport",
            measurePolicy = UiMeasurePolicies.box(),
            modifiers = listOf(
                Modifier.size(100.px, 100.px).padding(10.px).then(scrollModifier(horizontal = false)),
            ),
        ).also { it.children.add(BoxNode(modifiers = listOf(Modifier.size(80.px, 300.px)))) }
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(viewport) }
        UiModifierResolver().resolve(root)
        val layout = UiLayoutPipeline().compute(root, 300f, 300f, UiScrollState())
        val viewportRect = layout.nodes.getValue(viewport).rect
        val scrollbar = layout.scrollbars.getValue(viewport).single()
        val scrollbarRect = layout.nodes.getValue(scrollbar).rect

        assertEquals(
            viewportRect.x + viewportRect.width - 3f,
            scrollbarRect.x + scrollbarRect.width,
            0.01f,
        )
    }

    @Test
    fun `a horizontal scrollbar keeps the container height and re-centers content in what is left`() {
        fun horizontalLayout(childWidth: Float): Triple<UiLayoutResult, BoxNode, BoxNode> {
            val child = BoxNode(
                id = "content",
                modifiers = listOf(Modifier.size(childWidth.px, 10.px).align(vertical = UiAlign.CENTER)),
            )
            val viewport = BoxNode(
                id = "viewport",
                measurePolicy = UiMeasurePolicies.box(),
                modifiers = listOf(Modifier.size(100.px, 22.px).then(scrollModifier(vertical = false))),
            ).also { it.children.add(child) }
            val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(viewport) }
            UiModifierResolver().resolve(root)
            return Triple(UiLayoutPipeline().compute(root, 300f, 300f, UiScrollState()), viewport, child)
        }

        val (overflowLayout, overflowViewport, overflowChild) = horizontalLayout(180f)
        val viewport = overflowLayout.nodes.getValue(overflowViewport)
        val child = overflowLayout.nodes.getValue(overflowChild)
        val gutter = viewport.rect.height - viewport.content.height

        assertEquals(22f, viewport.rect.height, 0.01f, "the container keeps the height it declared")
        assertTrue(gutter > 0f, "the bar takes its gutter out of the container")
        assertEquals(
            viewport.content.y + (viewport.content.height - child.rect.height) / 2f,
            child.rect.y,
            0.01f,
            "centered content re-centers inside what the bar left over",
        )
        assertTrue(overflowLayout.scrollbars[overflowViewport].orEmpty().isNotEmpty())
    }

    @Test
    fun `identity of a synthesized scrollbar is stable across recomputes`() {
        val viewport = BoxNode(
            id = "v", measurePolicy = UiMeasurePolicies.box(),
            modifiers = listOf(Modifier.size(100.px, 100.px).then(scrollModifier(horizontal = false))),
        )
        viewport.children.add(BoxNode(modifiers = listOf(Modifier.size(80.px, 300.px))))
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(viewport) }
        val pipeline = UiLayoutPipeline()
        UiModifierResolver().resolve(root)
        val first = pipeline.compute(root, 300f, 300f, UiScrollState()).scrollbars.getValue(viewport).single()
        val second = pipeline.compute(root, 300f, 300f, UiScrollState()).scrollbars.getValue(viewport).single()
        assertTrue(first === second, "same pipeline reuses the cached scrollbar node")
    }
}
