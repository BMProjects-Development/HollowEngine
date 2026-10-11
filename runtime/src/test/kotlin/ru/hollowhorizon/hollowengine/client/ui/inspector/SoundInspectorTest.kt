@file:OptIn(ExperimentalSerializationApi::class)

package ru.hollowhorizon.hollowengine.client.ui.inspector

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.locale.Language
import net.minecraft.network.chat.FormattedText
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.Column
import ru.hollowhorizon.hollowengine.client.ui.HollowUiFrame
import ru.hollowhorizon.hollowengine.client.ui.HollowUiSurface
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.SpanNode
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.hasGizmo
import ru.hollowhorizon.hollowengine.client.ui.px
import ru.hollowhorizon.hollowengine.client.ui.size
import ru.hollowhorizon.hollowengine.client.ui.style.compileHss
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDefault
import ru.hollowhorizon.hollowengine.common.attachments.editor.ScriptSchema
import ru.hollowhorizon.hollowengine.common.attachments.editor.toDescriptor
import ru.hollowhorizon.hollowengine.common.sounds.SoundBoneAttachmentSpec
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import ru.hollowhorizon.hollowengine.common.sounds.SoundRepeat
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundInspectorTest {
    @Test
    fun `enabling range creates a valid override and recomposes its input`() = withLanguage("ru_ru") {
        Fixture().use { editor ->
            assertNull(editor.frame().nodeByIdentifier("insp-number/sound/range"))
            val enabled = editor.click("insp-optional/sound/range")
            assertEquals(16f, editor.playback.range)
            assertNotNull(enabled.nodeByIdentifier("insp-number/sound/range"))
            val disabled = editor.click("insp-optional/sound/range")
            assertNull(editor.playback.range)
            assertNull(disabled.nodeByIdentifier("insp-number/sound/range"))
        }
    }

    @Test
    fun `selecting count starts with a valid count and switching modes removes its input`() = withLanguage("ru_ru") {
        Fixture().use { editor ->
            val count = editor.click("insp-choice/sound/repeat/${SoundRepeat.Count.serializer().descriptor.serialName}")
            assertEquals(1, assertIs<SoundRepeat.Count>(editor.playback.repeat).plays)
            assertNotNull(count.nodeByIdentifier("insp-number/sound/repeat/plays"))
            val forever = editor.click("insp-choice/sound/repeat/${SoundRepeat.Forever.serializer().descriptor.serialName}")
            assertEquals(SoundRepeat.Forever, editor.playback.repeat)
            assertNull(forever.nodeByIdentifier("insp-number/sound/repeat/plays"))
            editor.click("insp-choice/sound/repeat/${SoundRepeat.Once.serializer().descriptor.serialName}")
            assertEquals(SoundRepeat.Once, editor.playback.repeat)
        }
    }

    @Test
    fun `playback fields choices and categories have Russian and English translations`() {
        for (locale in listOf("ru_ru", "en_us")) withLanguage(locale) {
            val descriptor = SoundPlayback.serializer().descriptor
            for (index in 0 until descriptor.elementsCount) {
                val key = assertNotNull(ComponentLabels.fieldKey(null, descriptor, index))
                assertTrue(Language.getInstance().has(key), "$locale is missing $key")
                assertEquals(Language.getInstance().getOrDefault(key), ComponentLabels.fieldName(null, descriptor, index))
            }
            val categoryIndex = descriptor.getElementIndex("category")
            val category = descriptor.getElementDescriptor(categoryIndex)
            val key = ComponentLabels.fieldKey(null, descriptor, categoryIndex)
            for (index in 0 until category.elementsCount) {
                assertTrue(Language.getInstance().has("$key.${category.getElementName(index).lowercase()}"))
                assertFalse(ComponentLabels.enumValueName(category, index, key).contains("hollowengine."))
            }
            val repeat = descriptor.getElementDescriptor(descriptor.getElementIndex("repeat"))
            ComponentJson.subclassDescriptors(repeat).forEach {
                assertFalse(ComponentLabels.typeName(it).contains("hollowengine."))
            }
        }
        withLanguage("ru_ru") {
            Fixture().use { editor ->
                val text = editor.frame().nodes.filterIsInstance<SpanNode>().map { it.text }
                assertTrue("Один раз" in text)
                assertTrue("Бесконечно" in text)
                assertTrue("Радиус слышимости" in text)
                assertFalse(text.any { it.contains("SoundRepeat") || it.contains("sound_repeat") })
            }
        }
    }

    @Test
    fun `initial values survive script schema export and constructor defaults stay intact`() {
        val descriptor = SoundPlayback.serializer().descriptor
        val range = ScriptSchema.fieldOf(descriptor, descriptor.getElementIndex("range"), 0)
        val exported = listOf(range).toDescriptor("test.Playback")
        assertEquals("16.0", exported.getElementAnnotations(0).filterIsInstance<EditorDefault>().single().json)
        val initial = ComponentJson.defaultJson(descriptor)
        assertEquals(SoundPlayback(), ComponentJson.format.decodeFromJsonElement(SoundPlayback.serializer(), initial))
    }

    @Test
    fun `sound attachments contain no placement fields or placement gizmo`() {
        assertFalse(hasGizmo(SoundBoneAttachmentSpec()))
        val fields = SoundBoneAttachmentSpec.serializer().descriptor.elementNames.toSet()
        assertFalse(fields.any { it in setOf("offset", "rotation", "scale") })
    }

    private fun withLanguage(locale: String, block: () -> Unit) {
        val previous = Language.getInstance()
        val messages = ComponentJson.format.parseToJsonElement(resource("/assets/hollowengine/lang/$locale.json"))
            .jsonObject.mapValues { it.value.jsonPrimitive.content }
        Language.inject(object : Language() {
            override fun getOrDefault(key: String, fallback: String) = messages[key] ?: previous.getOrDefault(key, fallback)
            override fun has(key: String) = key in messages || previous.has(key)
            override fun isDefaultRightToLeft() = false
            override fun getVisualOrder(text: FormattedText) = previous.getVisualOrder(text)
        })
        try { block() } finally { Language.inject(previous) }
    }

    private inner class Fixture : AutoCloseable {
        var playback by mutableStateOf(SoundPlayback())
        private var time = 0L
        private val surface = HollowUiSurface(stylesheet = compileHss(
            resource("/assets/hollowengine/ui/styles/theme.hss") + "\n" +
                resource("/assets/hollowengine/ui/styles/inspector.hss"),
        ))

        init {
            surface.setContent {
                Column(modifier = Modifier.size(600.px)) {
                    AutoFields(
                        null, SoundPlayback.serializer().descriptor,
                        ComponentJson.format.encodeToJsonElement(SoundPlayback.serializer(), playback) as JsonObject,
                        "/sound",
                    ) { playback = ComponentJson.format.decodeFromJsonElement(SoundPlayback.serializer(), it) }
                }
            }
        }

        fun frame(): HollowUiFrame {
            surface.frame(640f, 2000f, -1f, -1f, time.also { time += 16_000_000L })
            return surface.frame(640f, 2000f, -1f, -1f, time.also { time += 16_000_000L })
        }

        fun click(id: String): HollowUiFrame {
            val current = frame()
            val node = assertNotNull(current.nodeByIdentifier(id), id)
            val rect = current.layout[node].rect
            val x = rect.x + rect.width / 2f
            val y = rect.y + rect.height / 2f
            assertTrue(surface.runtime.mouseClicked(x, y, 0), "Click did not reach $id ($rect)")
            surface.runtime.mouseReleased(x, y, 0)
            return frame()
        }

        override fun close() = surface.close()
    }

    private fun resource(path: String) = requireNotNull(javaClass.getResourceAsStream(path)).bufferedReader().use { it.readText() }
}
