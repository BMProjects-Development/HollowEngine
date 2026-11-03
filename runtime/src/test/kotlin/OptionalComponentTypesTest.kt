import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.common.attachments.api.ComponentStore
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.UnknownComponent
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySerialization
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySnapshot
import ru.hollowhorizon.hollowengine.common.models.ModelAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentType
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentTypes
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.models.UnknownRigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OptionalComponentTypesTest {
    private val componentId = "test:optional_rig_component".rl
    private var attachmentRegistration: ExtensionHandle? = null

    @AfterEach
    fun cleanup() {
        attachmentRegistration?.close()
        ComponentDescriptorRegistry.unregisterDescriptor(componentId)
    }

    @Test
    fun `live components become unknown on unload and restore on registration`() {
        registerComponent()
        val store = ComponentStore()
        val original = OptionalRigComponent()
        store.put(original)

        ComponentDescriptorRegistry.unregisterDescriptor(componentId)
        assertIs<UnknownComponent>(store.readOnly[componentId])

        registerComponent()
        assertEquals(original, store.asMutableMap()[componentId])
    }

    @Test
    fun `custom serializers remain available for live instances after unregistering`() {
        val descriptor = ComponentDescriptor(componentId, CustomRigComponent::class, CustomRigSerializer)
        ComponentDescriptorRegistry.register(descriptor)
        val store = ComponentStore()
        val original = CustomRigComponent(ModelRig.EMPTY)
        store.put(original)

        ComponentDescriptorRegistry.unregisterDescriptor(componentId)
        assertIs<UnknownComponent>(store.readOnly[componentId])

        ComponentDescriptorRegistry.register(descriptor)
        assertEquals(original, store.readOnly[componentId])
    }

    @Test
    fun `nested attachments survive absent schemas on disk and on the network`() {
        registerComponent()
        registerAttachment()
        val original = EntitySnapshot(components = listOf(OptionalRigComponent(nestedRig())))
        val saved = EntitySerialization.serializeToNbt(original)
        attachmentRegistration!!.close()

        // An existing snapshot can also outlive the addon which created it.
        assertEquals(saved, EntitySerialization.serializeToNbt(original))
        val withoutAddon = EntitySerialization.deserializeFromNbt(saved)
        val nested = (withoutAddon.components.single() as OptionalRigComponent).rig
            .bones.getValue("root").attachments.single() as ModelAttachmentSpec
        val unknown = assertIs<UnknownRigAttachmentSpec>(nested.rig.attachments.single())
        assertEquals("effect", unknown.id)
        assertEquals(saved, EntitySerialization.serializeToNbt(withoutAddon))

        val wire = EntitySerialization.serializeToByteBuf(withoutAddon)
        try {
            val received = EntitySerialization.deserializeFromByteBuf(wire)
            assertEquals(withoutAddon, received)
            registerAttachment()
            assertEquals(original, EntitySerialization.deserializeFromNbt(EntitySerialization.serializeToNbt(received)))
        } finally {
            wire.release()
        }
    }

    @Test
    fun `live components rebind their nested attachments when schemas change`() {
        registerComponent()
        registerAttachment()
        val store = ComponentStore()
        val original = OptionalRigComponent(ModelRig(attachments = listOf(OptionalAttachment())))
        store.put(original)
        attachmentRegistration!!.close()

        val unknown = store.readOnly[componentId] as OptionalRigComponent
        assertIs<UnknownRigAttachmentSpec>(unknown.rig.attachments.single())

        registerAttachment()
        assertEquals(original, store.readOnly[componentId])
    }

    private fun nestedRig() = ModelRig(bones = mapOf(
        "root" to RigBone(attachments = listOf(ModelAttachmentSpec(
            rig = ModelRig(attachments = listOf(OptionalAttachment())),
        ))),
    ))

    private fun registerComponent() {
        ComponentDescriptorRegistry.register(ComponentDescriptor(
            componentId, OptionalRigComponent::class, OptionalRigComponent.serializer(),
        ))
    }

    private fun registerAttachment() {
        attachmentRegistration = RigAttachmentTypes.register(RigAttachmentType(
            "test:optional_attachment", OptionalAttachment::class, OptionalAttachment.serializer(), "test.attachment",
        ))
    }
}

@Serializable
@SerialName("test:optional_rig_component")
private data class OptionalRigComponent(val rig: ModelRig = ModelRig.EMPTY)

@Serializable
@SerialName("test:optional_attachment")
private data class OptionalAttachment(override val id: String = "effect", val strength: Float = 0.75f) : RigAttachmentSpec() {
    override fun withId(id: String) = copy(id = id)
}

private data class CustomRigComponent(val rig: ModelRig)

private object CustomRigSerializer : KSerializer<CustomRigComponent> {
    private val delegate = OptionalRigComponent.serializer()
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: CustomRigComponent) =
        delegate.serialize(encoder, OptionalRigComponent(value.rig))
    override fun deserialize(decoder: Decoder) = CustomRigComponent(delegate.deserialize(decoder).rig)
}
