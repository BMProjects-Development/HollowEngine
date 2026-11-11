package ru.hollowhorizon.hollowengine.common.addons

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class HollowAddonActivationStoreTests {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `disabled addon ids survive restart`() {
        val store = HollowAddonActivationStore(temporaryDirectory.toFile())

        store.save(linkedSetOf("video", "debug-command"))

        assertEquals(setOf("debug-command", "video"), store.load())
    }
}
