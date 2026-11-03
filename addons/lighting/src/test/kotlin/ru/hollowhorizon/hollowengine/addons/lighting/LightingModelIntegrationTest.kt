package ru.hollowhorizon.hollowengine.addons.lighting

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.v2.Attachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RigAttachmentFactories
import ru.hollowhorizon.hollowengine.client.render.RenderPasses
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentTypes
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightingModelIntegrationTest {
    @Test fun `light data remains serializable without loading the rendering addon`() {
        val component = LightComponent(LightSpec(kind = LightKind.SPOT, beam = 0.5f, cookie = "test:mask.png"))
        assertEquals(component, Json.decodeFromString(LightComponent.serializer(), Json.encodeToString(LightComponent.serializer(), component)))
        assertFailsWith<IllegalArgumentException> { LightSpec(radius = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { LightSpec(intensity = -1f) }
        assertFailsWith<IllegalArgumentException> { LightSpec(cookieScale = 0f) }
    }

    @Test fun `models rebuild when an optional attachment factory appears and disappears`() {
        val type = RigAttachmentTypes.register(LightAttachmentSpec.TYPE)
        val model = ModelAttachment(MutableStateFlow(Model.EMPTY), null)
        model.rig = ModelRig(attachments = listOf(LightAttachmentSpec()))
        assertTrue(model.carriesEffects, "Off-screen lamps must keep being posed even before the renderer is loaded")
        assertTrue(model.modelRoot!!.attachments.isEmpty())
        var closed = false
        val registration = RigAttachmentFactories.register(LightAttachmentSpec.TYPE.id) { _, context ->
            object : Attachment(context.node) { override fun close() { closed = true } }
        }
        try {
            model.ensureReady()
            assertEquals(1, model.modelRoot!!.attachments.size)
        } finally {
            registration.close()
        }
        model.ensureReady()
        assertTrue(closed)
        assertTrue(model.modelRoot!!.attachments.isEmpty())
        model.close()
        type.close()
    }

    @Test fun `nested shadow passes restore the previous state even after a render failure`() {
        assertFalse(RenderPasses.isShadowPass)
        assertFailsWith<IllegalStateException> {
            RenderPasses.shadow {
                assertTrue(RenderPasses.isShadowPass)
                RenderPasses.shadow { assertTrue(RenderPasses.isShadowPass) }
                assertTrue(RenderPasses.isShadowPass)
                error("caster failed")
            }
        }
        assertFalse(RenderPasses.isShadowPass)
    }
}
