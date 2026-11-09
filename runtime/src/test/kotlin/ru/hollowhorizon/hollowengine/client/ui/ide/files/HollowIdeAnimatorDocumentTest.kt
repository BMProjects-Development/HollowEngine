package ru.hollowhorizon.hollowengine.client.ui.ide.files

import ru.hollowhorizon.hollowengine.common.models.GraphPoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HollowIdeAnimatorDocumentTest {
    private val broken = byteArrayOf(1, 2, 3)

    @Test
    fun `an unreadable animator refuses edits and keeps its bytes`() {
        val document = HollowIdeAnimatorDocument(broken)

        document.edit { it.copy(layout = mapOf("layer/a" to GraphPoint(1f, 1f))) }

        assertTrue(document.readOnly, "A decode failure must not become an editable empty animator")
        assertFalse(document.isModified)
        assertContentEquals(broken, document.encode())
    }

    @Test
    fun `reloading a repaired file makes it editable again`() {
        val document = HollowIdeAnimatorDocument(broken)

        document.reload(HollowIdeAnimatorDocument(ByteArray(0)).encode())

        assertFalse(document.readOnly)
    }
}
