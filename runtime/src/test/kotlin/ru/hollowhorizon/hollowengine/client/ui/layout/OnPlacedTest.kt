package ru.hollowhorizon.hollowengine.client.ui.layout

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.assertEquals

class OnPlacedTest {
    @Test
    fun `onPlaced reports the node bounds in root coordinates`() {
        var reported: UiRect? = null
        val child = BoxNode(
            modifiers = listOf(Modifier.size(50.px, 20.px).onPlaced { reported = it }),
        )
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(child) }
        HollowUiRuntime().frame(root, 200f, 200f, -1f, -1f, 0L)
        assertEquals(UiRect(0f, 0f, 50f, 20f), reported, "child sits at the origin with its fixed size")
    }

    @Test
    fun `onPlaced fires again only when the bounds change`() {
        var fires = 0
        val child = BoxNode(
            modifiers = listOf(Modifier.size(50.px, 20.px).onPlaced { fires++ }),
        )
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(child) }
        val runtime = HollowUiRuntime()
        runtime.frame(root, 200f, 200f, -1f, -1f, 0L)
        runtime.frame(root, 200f, 200f, -1f, -1f, 0L)
        assertEquals(1, fires, "an unchanged layout does not re-fire onPlaced")
    }
}
