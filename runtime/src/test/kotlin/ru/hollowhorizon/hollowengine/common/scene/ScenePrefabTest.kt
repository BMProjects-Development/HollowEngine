package ru.hollowhorizon.hollowengine.common.scene

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.models.ModelNodeSource
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScenePrefabTest {
    @Serializable
    private data class Label(val text: String)

    @Serializable
    private data class Behavior(val text: String) : ComponentBehavior {
        override fun bind(owner: ComponentOwner, id: UUID): ComponentLifecycle {
            val context = owner.scene.context as Context
            return object : ComponentLifecycle {
                override fun attach() {
                    check(!context.rejectBehavior) { "Rejected behavior" }
                    context.events.add("attach")
                }
                override fun activate() { context.events.add("activate") }
                override fun suspend() { context.events.add("suspend") }
                override fun change(value: Any) { context.events.add((value as Behavior).text) }
                override fun remove() { context.events.add("remove") }
            }
        }
    }

    private class Context(val rejectBehavior: Boolean = false) : SceneContext {
        override val side = LogicalSide.SERVER
        val events = ArrayList<String>()
    }

    companion object {
        private val descriptors = listOf(
            ComponentDescriptor("test:prefab_label".rl, Label::class, Label.serializer()),
            ComponentDescriptor("test:prefab_behavior".rl, Behavior::class, Behavior.serializer()),
        )

        @BeforeAll
        @JvmStatic
        fun register() {
            descriptors.forEach(ComponentDescriptorRegistry::register)
        }

        @AfterAll
        @JvmStatic
        fun unregister() {
            descriptors.forEach { ComponentDescriptorRegistry.unregisterDescriptor(it.id) }
        }
    }

    @Test
    fun `a saved prefab merges by binding id and preserves behavior on a bone`() {
        Scene(Context()).use { source ->
            val original = source.create("template")
            val first = original.attach(Label("first"))
            val second = original.attach(Label("second"))
            val behavior = original.attach(Behavior("initial"))
            val marker = original.create("marker").also { it.localTransform.translate(2f, 0f, 0f) }

            val initial = roundTrip(original)
            original.setComponent(first, Label("edited"))
            original.setComponent(behavior, Behavior("changed"))
            val edited = roundTrip(original)

            val context = Context()
            Scene(context).use { scene ->
                val owner = scene.create("owner")
                val target = owner.importChild("bone").also { it.localTransform.translate(7f, 0f, 0f) }
                val other = target.attach(Label("keep"))
                initial.applyTo(target)
                val appliedMarker = target.children.single()
                assertNotEquals(marker.createdId, appliedMarker.createdId)
                assertEquals(9f, appliedMarker.worldTransform.getTranslation().x)

                edited.applyTo(target)
                assertSame(appliedMarker, target.children.single())
                assertEquals(Label("edited"), target.component(first))
                assertEquals(Label("second"), target.component(second))
                assertEquals(Label("keep"), target.component(other))
                assertEquals(Behavior("changed"), target.component(behavior))
                target.setEnabled(false)
                target.setEnabled(true)
                target.detach(behavior)
                assertEquals(listOf("attach", "activate", "changed", "suspend", "activate", "suspend", "remove"), context.events)

                val empty = scene.create("empty")
                initial.applyTo(empty)
                assertEquals(Label("first"), empty.component(first))
                assertNotEquals(appliedMarker.createdId, empty.children.single().createdId)
            }
        }
    }

    @Test
    fun `prefab overrides address the numbered imported node and keep its rest pose`() {
        Scene(Context()).use { scene ->
            val template = scene.create("template")
            template.importChild("hand")
            val authored = template.importChild("hand")
            authored.setImportedRestTransform(TrsTransformF().translate(3f, 0f, 0f))
            val label = authored.attach(Label("held object"))
            val prefab = roundTrip(template)

            val target = scene.create("target")
            val first = target.importChild("hand")
            val second = target.importChild("hand")
            val asset = object : ModelNodeSource {
                override val index = 0
                override val name = "hand"
                override val path = "hand"
                override val hasMesh = false
                override val skinJoints = emptyList<Int>()
                override val restTransform = TrsTransformF()
            }
            second.setModelSource(asset)
            prefab.applyTo(target)
            second.localTransform.translate(5f, 0f, 0f)
            second.resetPose()

            assertTrue(first.components.isEmpty())
            assertEquals(Label("held object"), second.component(label))
            assertEquals(3f, second.localTransform.translation.x)
            assertEquals(0f, asset.restTransform.translation.x)

            val incomplete = scene.create("incomplete")
            incomplete.importChild("hand")
            assertFailsWith<IllegalArgumentException> { prefab.applyTo(incomplete) }
            assertTrue(incomplete.children.single().components.isEmpty())
        }
    }

    @Test
    fun `prefab application removes partial additions on a lifecycle failure`() {
        Scene(Context()).use { source ->
            val root = source.create("template")
            root.attach(Behavior("test"))
            root.create("child")
            val prefab = roundTrip(root)

            Scene(Context(rejectBehavior = true)).use { scene ->
                val target = scene.create("target")
                val unaffected = target.attach(Label("keep"), enabled = false)
                assertFailsWith<IllegalStateException> { prefab.applyTo(target) }
                assertTrue(target.children.isEmpty())
                assertEquals(mapOf(unaffected to Label("keep")), target.components)
                assertFalse(target.componentEnabled(unaffected))
            }
        }
    }

    private fun roundTrip(owner: SceneObject): ScenePrefab {
        val bytes = ByteArrayOutputStream().also { ScenePrefab.capture(owner).write(it) }.toByteArray()
        return ScenePrefab.read(bytes.inputStream())
    }
}
