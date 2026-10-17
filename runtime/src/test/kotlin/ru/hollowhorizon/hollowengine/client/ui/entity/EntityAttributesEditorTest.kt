package ru.hollowhorizon.hollowengine.client.ui.entity

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import ru.hollowhorizon.hollowengine.client.ui.HollowUiFrame
import ru.hollowhorizon.hollowengine.client.ui.HollowUiSurface
import ru.hollowhorizon.hollowengine.client.ui.style.compileHss
import ru.hollowhorizon.hollowengine.common.attachments.api.Component
import ru.hollowhorizon.hollowengine.common.attachments.editor.BuiltinVirtualComponents
import ru.hollowhorizon.hollowengine.common.attachments.editor.EntityAttributes
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntityAttributesEditorTest {
    @BeforeTest
    fun bootstrapRegistries() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        BuiltinVirtualComponents.register()
    }

    @Test
    fun `attribute rows stay compact and values remain inside narrow sidebars`() {
        for (width in listOf(180f, 240f, 320f)) {
            surface().use { surface ->
                surface.frame(width, 400f, -1f, -1f, 0L)
                val frame = surface.frame(width, 400f, -1f, -1f, 16_000_000L)
                val row = frame.nodes.single { it.id == "ee-attribute-minecraft:generic.movement_speed" }
                val field = frame.nodes.single { it.id == "insp-number/ee-attribute/minecraft:generic.movement_speed" }
                val rowRect = frame.layout[row].rect
                val fieldRect = frame.layout[field].rect
                val reset = frame.nodes.single { it.id == "ee-attribute-reset-minecraft:generic.movement_speed" }
                val resetRect = frame.layout[reset].rect
                assertEquals(26f, rowRect.height, 0.01f)
                assertEquals(22f, fieldRect.height, 0.01f)
                assertTrue(fieldRect.x >= rowRect.x && fieldRect.x + fieldRect.width <= rowRect.x + rowRect.width + 0.01f)
                assertEquals(rowRect.x + rowRect.width, resetRect.x + resetRect.width, 0.01f)
                assertTrue(frame.nodes.none { "insp-hint" in it.tags }, "explanations must live in tooltips")
                assertTrue(frame.nodes.any { it.id == "ee-attribute-help" })
            }
        }
    }

    @Test
    fun `add opens a bounded picker with attributes outside the entity defaults`() {
        surface().use { surface ->
            surface.frame(240f, 400f, -1f, -1f, 0L)
            val frame = surface.frame(240f, 400f, -1f, -1f, 16_000_000L)
            click(surface, frame, "ee-attribute-add")
            surface.frame(240f, 400f, -1f, -1f, 32_000_000L)
            val popup = surface.frame(240f, 400f, -1f, -1f, 48_000_000L)
            assertTrue(popup.nodes.any { it.id == "ee-attribute-choice-minecraft:player.block_interaction_range" })
            assertTrue(popup.nodes.none { it.id == "ee-attribute-choice-minecraft:generic.movement_speed" })
            val root = popup.nodes.single { "ee-attribute-picker" in it.tags }
            assertTrue(popup.layout[root].rect.width <= 240f)
        }
    }

    @Test
    fun `selecting an attribute submits its default and enables removal`() {
        var edited: EntityAttributes? = null
        surface { edited = it }.use { surface ->
            surface.frame(240f, 400f, -1f, -1f, 0L)
            val frame = surface.frame(240f, 400f, -1f, -1f, 16_000_000L)
            click(surface, frame, "ee-attribute-add")
            surface.frame(240f, 400f, -1f, -1f, 32_000_000L)
            val popup = surface.frame(240f, 400f, -1f, -1f, 48_000_000L)
            click(surface, popup, "ee-attribute-choice-minecraft:generic.armor")
            val result = requireNotNull(edited)
            assertEquals(0.0, result.attributes.getValue("minecraft:generic.armor"))
            assertEquals(0.0, result.defaults.getValue("minecraft:generic.armor"))
            assertTrue("minecraft:generic.armor" in result.removable)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun surface(onChange: (EntityAttributes) -> Unit = {}): HollowUiSurface {
        val source = listOf("inspector", "entity-editor").joinToString("\n") { name ->
            requireNotNull(javaClass.getResourceAsStream("/assets/hollowengine/ui/styles/$name.hss"))
                .bufferedReader().use { it.readText() }
        }
        val values = EntityAttributes(attributes = mapOf("minecraft:generic.movement_speed" to 0.23),
            defaults = mapOf("minecraft:generic.movement_speed" to 0.23))
        val serializer = EntityAttributes.serializer()
        val entry = ComponentEntry("hollowengine:editor/attributes".rl, values, serializer as KSerializer<Component>, true)
        val scope = ComponentEditorScope(entry, Json.encodeToJsonElement(serializer, values).jsonObject) {
            onChange(Json.decodeFromJsonElement(serializer, it))
        }
        return HollowUiSurface(stylesheet = compileHss(source)).also { surface ->
            surface.setContent { EntityAttributesEditor(scope) }
        }
    }

    private fun click(surface: HollowUiSurface, frame: HollowUiFrame, id: String) {
        val node = frame.nodes.single { it.id == id }
        val rect = frame.layout[node].rect
        val x = rect.x + rect.width / 2f
        val y = rect.y + rect.height / 2f
        surface.runtime.mouseClicked(x, y, 0)
        surface.runtime.mouseReleased(x, y, 0)
    }
}
