package ru.hollowhorizon.hollowengine.common.scene

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentSyncPolicy
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.models.LegacyNodePlacement
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.common.vfx.VfxComponent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SceneComponentDataTest {
    @Serializable
    @SerialName("test:scene_data")
    data class Label(val text: String = "")

    @Serializable
    @SerialName("test:server_data")
    data class PrivateValue(val value: Int = 1)

    @Test
    fun `registered components persist and only tracked data is applied from network snapshots`() {
        ComponentDescriptorRegistry.register(ComponentDescriptor(
            "test:scene_data".rl, Label::class, Label.serializer(), syncPolicy = ComponentSyncPolicy.SYNC,
        ))
        ComponentDescriptorRegistry.register(ComponentDescriptor("test:server_data".rl, PrivateValue::class, PrivateValue.serializer()))
        val context = object : SceneContext { override val side = LogicalSide.SERVER }
        Scene(context).use { from ->
            Scene(context).use { to ->
                val original = from.create("original")
                assertFailsWith<IllegalArgumentException> { original.attach("unregistered") }
                val loaded = to.create("loaded")
                val id = original.attach(Label("before"), enabled = false)
                val privateId = original.attach(PrivateValue())
                SceneComponentData.apply(loaded, SceneComponentData.write(original), syncOnly = false)
                assertEquals(original.components, loaded.components)
                assertFalse(loaded.componentEnabled(id))

                original.setComponent(id, Label("after"))
                original.setComponentEnabled(id, true)
                val update = SceneComponentData.write(original, syncOnly = true)
                assertFalse(update.contains(privateId.toString()))
                SceneComponentData.apply(loaded, update, syncOnly = true)
                assertEquals(Label("after"), loaded.components[id])
                assertTrue(loaded.componentEnabled(id))
                SceneComponentData.apply(loaded, CompoundTag(), syncOnly = true)
                assertEquals(mapOf(privateId to PrivateValue()), loaded.components)
            }
        }
    }

    @Test
    fun `saved scene effects gain an authored child and it survives tracker updates`() {
        val type = "hollowengine:rig/vfx".rl
        val context = object : SceneContext { override val side = LogicalSide.SERVER }
        ComponentDescriptorRegistry.register(ComponentDescriptor(
            type, VfxComponent::class, VfxComponent.serializer(), syncPolicy = ComponentSyncPolicy.SYNC,
        ))
        try {
            val id = UUID.randomUUID()
            val placement = LegacyNodePlacement(offset = Vec3f(0f, 2f, 0f), scale = 2f)
            val stored = CompoundTag().apply {
                put(id.toString(), CompoundTag().apply {
                    putString("type", type.toString())
                    putBoolean("enabled", true)
                    put("data", NBTFormat.serialize(LegacyNodePlacement.serializer(), placement))
                })
            }
            Scene(context).use { first ->
                Scene(context).use { second ->
                    val from = first.create("from")
                    val to = second.create("to")
                    SceneTreeData.apply(from, stored, syncOnly = false)
                    assertTrue(from.components.isEmpty())
                    val owner = from.children.single()
                    assertEquals(placement.offset, owner.localTransform.translation)
                    assertEquals(VfxComponent(), owner.component(id))

                    SceneTreeData.apply(to, SceneTreeData.write(from, syncOnly = false), syncOnly = false)
                    val copy = to.children.single()
                    assertEquals(owner.createdId, copy.createdId)
                    assertEquals(owner.components, copy.components)
                    owner.localTransform.translate(4f, 0f, 0f)
                    SceneTreeData.apply(to, SceneTreeData.write(from, syncOnly = true), syncOnly = true)
                    assertSame(copy, to.children.single())
                    assertEquals(Vec3f(4f, 2f, 0f), copy.localTransform.translation)
                    owner.remove()
                    SceneTreeData.apply(to, SceneTreeData.write(from, syncOnly = true), syncOnly = true)
                    assertTrue(to.children.isEmpty())
                }
            }
        } finally {
            ComponentDescriptorRegistry.unregisterDescriptor(type)
        }
    }
}
