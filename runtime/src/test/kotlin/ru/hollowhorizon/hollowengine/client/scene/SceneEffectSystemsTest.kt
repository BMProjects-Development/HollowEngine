package ru.hollowhorizon.hollowengine.client.scene

import org.joml.Matrix4f
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.decals.DecalDraw
import ru.hollowhorizon.hollowengine.client.decals.Decals
import ru.hollowhorizon.hollowengine.client.decals.SceneDecals
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.renderModel
import ru.hollowhorizon.hollowengine.client.sounds.ManagedSound
import ru.hollowhorizon.hollowengine.client.sounds.SceneSounds
import ru.hollowhorizon.hollowengine.client.sounds.SoundEmitter
import ru.hollowhorizon.hollowengine.client.sounds.SoundEmitterState
import ru.hollowhorizon.hollowengine.client.sounds.sounds
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.decals.Decal
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.scene.Scene
import ru.hollowhorizon.hollowengine.common.scene.ScenePrefab
import ru.hollowhorizon.hollowengine.common.sounds.Sound
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SceneEffectSystemsTest {
    @Test
    fun `decal rendering reads current data from a prefab on a plain node and an imported bone`() {
        ClientSceneContext.registerSystem(Decal::class, ::SceneDecals).use {
            val standalone = ClientSceneContext()
            Scene(standalone).use { scene ->
                val empty = scene.create("empty")
                val value = Decal(width = 2f)
                val id = empty.attach(value)
                val prefab = ScenePrefab.capture(empty)
                assertEquals(2f, collect(standalone, 0f).single().spec.width)

                renderModel(NodeDefinition(0, "hand", mutableListOf(), TrsTransformF().translate(3f, 0f, 0f))).use { model ->
                    val bone = model.sceneRoot.children.single()
                    val context = model.sceneRoot.scene.context as ClientSceneContext
                    model.rig = ModelRig(bones = mapOf("hand" to RigBone(attachments = listOf(Decal(id = "rig")))))
                    assertTrue(bone.components.values.any { it is Decal })
                    assertTrue(model.nodes.single().attachments.isEmpty())
                    prefab.applyTo(bone)
                    bone.setComponent(id, value.copy(width = 4f))

                    val first = collect(context, 0f).single { it.spec.id == value.id }
                    val second = collect(context, 5f).single { it.spec.id == value.id }
                    assertEquals(4f, first.spec.width)
                    assertEquals(3f, first.placement.m30())
                    assertEquals(8f, second.placement.m30())
                    assertEquals(77, second.light)
                    assertEquals("hand/${value.id}", second.key)

                    bone.setEnabled(false)
                    assertTrue(collect(context, 0f).isEmpty())
                    bone.setEnabled(true)
                    bone.detach(id)
                    assertEquals("rig", collect(context, 0f).single().spec.id)
                }
            }
        }
    }

    @Test
    fun `sound data can change while the same scene system keeps the binding active`() {
        ClientSceneContext.registerSystem(Sound::class, ::SceneSounds).use {
            val context = ClientSceneContext()
            Scene(context).use { scene ->
                val node = scene.create("sound")
                val value = Sound()
                assertNull(context.sounds)
                val id = node.attach(value)
                val system = checkNotNull(context.sounds)
                assertTrue(system.hasActive)
                node.setComponent(id, value.copy(playback = value.playback.copy(volume = 0.25f)))
                assertSame(system, context.sounds)
                assertTrue(system.hasActive)
                node.setEnabled(false)
                assertFalse(system.hasActive)
                node.setEnabled(true)
                assertTrue(system.hasActive)
                node.detach(id)
                assertFalse(system.hasActive)
            }
        }
    }

    @Test
    fun `reconfiguring volume keeps the current playback position`() {
        val source = SoundSource.Event("minecraft:block.beacon.ambient".rl)
        val playback = SoundPlayback()
        val emitter = SoundEmitter { SoundEmitterState.Listener }
        ManagedSound("volume-test", source, playback, emitter, start = 4.5).use { sound ->
            val quiet = playback.copy(volume = 0.25f)
            assertTrue(sound.reconfigure(source, quiet, emitter))
            assertEquals(quiet, sound.settings)
            assertEquals(4.5, sound.position)
        }
    }

    private fun collect(context: ClientSceneContext, offset: Float): List<DecalDraw> =
        ArrayList<DecalDraw>().also { draws ->
            val view = Matrix4f().translation(offset, 0f, 0f)
            Decals.capturing(draws) {
                context.collect(view, 77)
                context.collect(view, 77)
            }
        }

    companion object {
        private val descriptors = listOf(
            ComponentDescriptor("test:scene_decal".rl, Decal::class, Decal.serializer()),
            ComponentDescriptor("test:audio_system".rl, Sound::class, Sound.serializer()),
        )

        @JvmStatic
        @BeforeAll
        fun register() = descriptors.forEach(ComponentDescriptorRegistry::register)

        @JvmStatic
        @AfterAll
        fun unregister() = descriptors.forEach { ComponentDescriptorRegistry.unregisterDescriptor(it.id) }
    }
}
