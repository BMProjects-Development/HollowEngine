package ru.hollowhorizon.hollowengine.client.ui.ide

import kotlinx.serialization.Serializable
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.renderModel
import ru.hollowhorizon.hollowengine.client.scene.ClientComponent
import ru.hollowhorizon.hollowengine.client.scene.ClientSceneContext
import ru.hollowhorizon.hollowengine.client.scene.ComponentUpdate
import ru.hollowhorizon.hollowengine.client.scene.RenderComponent
import ru.hollowhorizon.hollowengine.client.sounds.SoundPlaybackRuntime
import ru.hollowhorizon.hollowengine.client.vfx.SceneVfx
import ru.hollowhorizon.hollowengine.client.vfx.SceneVfxBindings
import ru.hollowhorizon.hollowengine.client.vfx.VfxBoneBinding
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.Model
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.scene.Scene
import ru.hollowhorizon.hollowengine.common.sounds.Sound
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.common.vfx.VfxComponent
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect
import java.util.*
import kotlin.test.*

class WorldObjectPartTreeTest {
    @Serializable
    private data class Marker(val label: String)

    companion object {
        private val descriptors = listOf(
            ComponentDescriptor("test:scene_marker".rl, Marker::class, Marker.serializer()),
            ComponentDescriptor("test:scene_sound".rl, Sound::class, Sound.serializer()),
            ComponentDescriptor("test:scene_effect".rl, VfxComponent::class, VfxComponent.serializer()),
        )

        @JvmStatic
        @BeforeAll
        fun registerComponents() {
            descriptors.forEach(ComponentDescriptorRegistry::register)
        }

        @JvmStatic
        @AfterAll
        fun unregisterComponents() {
            descriptors.forEach { ComponentDescriptorRegistry.unregisterDescriptor(it.id) }
        }
    }

    @Test
    fun `an independently registered component participates in rendering and the hierarchy`() {
        val events = mutableListOf<String>()
        ClientSceneContext.register(Marker::class) { _, _, value ->
            object : ClientComponent, RenderComponent {
                private var frame = 0
                override fun activate() {
                    events += value.label
                }

                override fun remove() {
                    events += "removed"
                }

                override fun update(frame: ComponentUpdate) {
                    this.frame++
                }

                override fun collect(sceneToView: Matrix4fc) {
                    events += "draw $frame"
                }
            }
        }.use {
            val context = ClientSceneContext()
            Scene(context).use { scene ->
                val owner = scene.create("empty")
                val id = owner.attach(Marker("active"))
                assertTrue(context.hasDraws)
                assertEquals(id, PartsReader().read(null, null, owner.components).roots.single().path.componentId)
                context.updatePreview(playSounds = false, effectTime = null, effectEmitting = null)
                context.collect(Matrix4f())
                context.collect(Matrix4f().translate(1f, 0f, 0f))
                owner.setEnabled(false)
                context.updatePreview(playSounds = false, effectTime = null, effectEmitting = null)
                context.collect(Matrix4f())
                assertFalse(context.hasDraws)
                owner.detach(id)
                assertFalse(context.hasDraws)
                assertFalse(context.hasUpdates)
            }
        }
        assertEquals(listOf("active", "draw 1", "draw 1", "removed"), events)
    }

    @Test
    fun `effect collection uses the same playback state for several views`() {
        ClientSceneContext.register(VfxComponent::class) { node, _, value -> SceneVfx(node, value) }.use {
            val context = ClientSceneContext()
            Scene(context).use { scene ->
                val node = scene.create("effect")
                node.localTransform.translate(2f, 0f, 0f)
                node.attach(VfxComponent(ownEffect = VfxEffect()))
                context.updatePreview(playSounds = false, effectTime = 0.25f, effectEmitting = null)
                val draws = ArrayList<VfxBoneBinding>()
                SceneVfxBindings.capturing(draws) { context.collect(Matrix4f()) }
                val first = draws.single()
                assertEquals(0.25f, first.instance.time, 0.001f)
                assertEquals(2f, first.placement.m30())

                draws.clear()
                SceneVfxBindings.capturing(draws) { context.collect(Matrix4f().translate(5f, 0f, 0f)) }
                val second = draws.single()
                assertSame(first.instance, second.instance)
                assertEquals(0.25f, second.instance.time, 0.001f)
                assertEquals(7f, second.placement.m30())
            }
        }
    }

    @Test
    fun `root attachments are direct children even without a model`() {
        val sound = Sound()
        val vfx = VfxComponent()
        val parts = PartsReader().read(null, null, mapOf(UUID.randomUUID() to sound, UUID.randomUUID() to vfx))
        assertFalse(parts.loading)
        assertEquals(listOf(PartKind.COMPONENT, PartKind.COMPONENT), parts.roots.map { it.kind })
        for (row in parts.roots) {
            assertTrue(row.path.componentId != null)
            assertEquals(row.path, PartPath.decode(row.path.encode()))
            assertSame(row, parts.find(row.path))
        }
    }

    @Test
    fun `rig effects belong to scene bones and stay separate from object level rows`() {
        val sound = Sound()
        val vfx = VfxComponent()
        val rig = ModelRig(attachments = listOf(sound), bones = mapOf("speaker" to RigBone(attachments = listOf(vfx))))
        val model = Model("test:models/speaker.gltf", rig)
        ClientSceneContext.register(Sound::class, ::SoundPlaybackRuntime).use {
            ClientSceneContext.register(VfxComponent::class) { node, _, value -> SceneVfx(node, value) }.use {
                renderModel(NodeDefinition(0, "speaker", mutableListOf(), TrsTransformF())).use { instance ->
                    instance.rig = rig
                    val bone = instance.sceneRoot.children.single()
                    assertEquals(sound, instance.sceneRoot.components.values.single())
                    assertEquals(vfx, bone.components.values.single())
                    assertNull(instance.modelRoot)
                    assertTrue(instance.nodes.single().attachments.isEmpty())

                    val parts = PartsReader().read(model, instance, mapOf(UUID.randomUUID() to sound))
                    val rootSound = parts.roots.single { it.path.componentId != null }
                    val rigPart = parts.roots.single { it.kind == PartKind.MODEL }
                    assertNotEquals(rootSound.path.encode(), rigPart.children.first().path.encode())
                    val boneRow = rigPart.children.single { it.kind == PartKind.BONE }
                    assertEquals(vfx.id, boneRow.children.single().path.attachment)
                }
            }
        }
    }
}
