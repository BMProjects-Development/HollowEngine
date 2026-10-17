import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.attachments.api.RuntimeAttachment
import ru.hollowhorizon.hollowengine.common.attachments.api.RuntimeAttachments
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeAttachmentsTest {
    private class Resource(var valid: Boolean = true, val fail: Boolean = false) : RuntimeAttachment {
        var closed = 0
        override val isValid get() = valid
        override fun close() {
            closed++
            if (fail) error("Close failed")
        }
    }

    @Test
    fun `remove retain and close release owned resources once`() {
        val state = RuntimeAttachments()
        val removed = state.getOrPut("removed") { Resource() }
        val retained = state.getOrPut("retained") { Resource() }
        state.remove("removed")
        state.retain(setOf("retained"))
        assertEquals(1, removed.closed)
        assertEquals(0, retained.closed)
        state.close()
        state.close()
        assertEquals(1, retained.closed)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `component invalidation releases orphan instances and preserves valid ones`() {
        val state = RuntimeAttachments()
        val oldModel = state.getOrPut("old") { Resource() }
        val currentModel = state.getOrPut("current") { Resource() }
        oldModel.valid = false
        state.pruneInvalid()
        assertEquals(1, oldModel.closed)
        assertEquals(0, currentModel.closed)
        assertNull(state.getOrNull<Resource>("old"))
        state.close()
    }

    @Test
    fun `aliases retain ownership until the last key is removed`() {
        val state = RuntimeAttachments()
        val resource = state.getOrPut("first") { Resource() }
        state.getOrPut("second") { resource }
        state.remove("first")
        assertEquals(0, resource.closed)
        state.close()
        assertEquals(1, resource.closed)
    }

    @Test
    fun `a failing close does not abandon other resources`() {
        val state = RuntimeAttachments()
        val broken = state.getOrPut("broken") { Resource(fail = true) }
        val healthy = state.getOrPut("healthy") { Resource() }
        assertFailsWith<IllegalStateException> { state.close() }
        assertEquals(1, broken.closed)
        assertEquals(1, healthy.closed)
        assertTrue(state.isEmpty)
    }
}
