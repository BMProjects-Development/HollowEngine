package ru.hollowhorizon.hollowengine.client.ui.style

import kotlin.test.*

class HssReloadTest {
    @Test
    fun `broken live edits keep the previous sheet and retry only when changed`() {
        val loader = EditedStylesheet()
        val resource = UiStylesheetReference.Resource("test:live.hss", loader)
        val original = resource.resolve()
        for (broken in listOf(".panel {", ".panel { background: #zzzzzz; }")) {
            loader.edit(broken)
            assertSame(original, resource.resolve())
            val attempts = loader.loads
            repeat(10) { assertSame(original, resource.resolve()) }
            assertEquals(attempts, loader.loads, "failed revisions must not compile again every frame")
        }
        loader.edit(".panel { width: 80px; }")
        val repaired = resource.resolve()
        assertNotSame(original, repaired)
        assertEquals(1, repaired.rules.size)
    }

    @Test
    fun `initially invalid resource recovers and deliberate empty styles replace the last sheet`() {
        val loader = EditedStylesheet().apply { edit(".panel {") }
        val resource = UiStylesheetReference.Resource("test:live.hss", loader)
        assertTrue(resource.resolve().rules.isEmpty())
        loader.edit(".panel { width: 20px; }")
        assertEquals(1, resource.resolve().rules.size)
        loader.edit("")
        assertTrue(resource.resolve().rules.isEmpty())
    }

    private class EditedStylesheet : HssResourceLoader {
        private var text = ".panel { width: 40px; }"
        private var revision = 0L
        var loads = 0
            private set

        fun edit(text: String) {
            this.text = text
            revision++
        }

        override fun version(location: String) = revision

        override fun load(location: String): CompiledHss {
            loads++
            return compileHss(text)
        }
    }
}
