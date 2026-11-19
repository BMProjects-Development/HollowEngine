package ru.hollowhorizon.hollowengine.common.scene

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.scripting.annotations.Attach
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.util.UUID
import kotlin.test.*

class SceneTest {
    @Test
    fun `scene follows an external parent matrix without allocating a new world matrix`() {
        val parent = object : SceneTransformParent {
            override val matrix = MutableMat4f()
            override var version = 0
        }
        val scene = Scene(object : SceneContext { override val side = LogicalSide.CLIENT }, parent)
        val node = scene.create("node")
        node.localTransform.translate(1f, 0f, 0f)
        val matrix = node.worldTransform
        assertEquals(1f, matrix.getTranslation().x)

        parent.matrix.translate(3f, 0f, 0f)
        parent.version++
        assertSame(matrix, node.worldTransform)
        assertEquals(4f, matrix.getTranslation().x)
        scene.close()
    }
    private fun scene(side: LogicalSide = LogicalSide.SERVER) = Scene(object : SceneContext {
        override val side = side
    })

    @Serializable
    private data class TestComponent(
        val value: String,
        @Transient val makeLifecycle: (ComponentOwner) -> ComponentLifecycle? = { null },
    ) : ComponentBehavior {
        override fun bind(owner: ComponentOwner, id: UUID) = makeLifecycle(owner)
    }

    @Attach(Scene::class)
    @Serializable
    private class RootComponent

    companion object {
        @JvmStatic
        @BeforeAll
        fun registerComponents() {
            ComponentDescriptorRegistry.register(ComponentDescriptor("test:scene_fixture".rl, TestComponent::class, TestComponent.serializer()))
            ComponentDescriptorRegistry.register(ComponentDescriptor("test:scene_root_only".rl, RootComponent::class, RootComponent.serializer()))
        }

        @JvmStatic
        @AfterAll
        fun unregisterComponents() {
            ComponentDescriptorRegistry.unregisterDescriptor("test:scene_fixture".rl)
            ComponentDescriptorRegistry.unregisterDescriptor("test:scene_root_only".rl)
        }
    }

    @Test
    fun `world matrix version follows parent changes without reallocating`() {
        val scene = scene()
        val parent = scene.create("parent")
        val child = parent.create("child")
        val matrix = child.worldTransform
        val initial = child.worldVersion
        assertEquals(initial, child.worldVersion)
        assertSame(matrix, child.worldTransform)

        parent.localTransform.translate(2f, 0f, 0f)
        assertNotEquals(initial, child.worldVersion)
        assertSame(matrix, child.worldTransform)
        assertEquals(2f, child.worldTransform.getTranslation().x)
        scene.close()
    }

    @Test
    fun `created IDs and imported paths address nested instances without ambiguity`() {
        val scene = scene()
        val character = scene.create("character")
        val left = character.importChild("left").importChild("hand")
        val right = character.importChild("right").importChild("hand")
        val sword = right.create("sword")
        val swordBone = sword.importChild("hand")
        val second = scene.create("second").importChild("right").importChild("hand")

        assertEquals(listOf(ScenePathSegment("left"), ScenePathSegment("hand")), left.address.importedPath)
        assertNotEquals(left.address, right.address)
        assertNotEquals(right.address, second.address)
        assertEquals(sword.createdId, swordBone.address.instanceId)
        listOf(character, left, right, sword, swordBone, second).forEach { assertSame(it, scene.resolve(it.address)) }
        assertNull(scene.resolve(SceneAddress(character.createdId!!, listOf("right", "sword", "hand").map { ScenePathSegment(it) })))
        val duplicate = character.importChild("right")
        assertEquals(ScenePathSegment("right", 2), duplicate.address.importedPath.single())
        assertSame(duplicate, scene.resolve(duplicate.address))
        assertFailsWith<IllegalArgumentException> { left.rename("  ") }
        assertFailsWith<IllegalArgumentException> { scene.create("other", character.createdId) }
        val oldAddress = left.address
        left.rename("renamed/hand")
        assertNull(scene.resolve(oldAddress))
        assertSame(left, scene.resolve(left.address))
        val swordAddress = swordBone.address
        sword.remove()
        assertNull(scene.resolve(swordAddress))
    }

    @Test
    fun `numbered imported siblings keep distinct addresses even when names contain numbers`() {
        val scene = scene()
        val root = scene.create("model")
        val first = root.importChild("hand")
        val second = root.importChild("hand")
        val named = root.importChild("hand (2)")
        val other = root.importChild("other").importChild("hand")
        assertEquals(ScenePathSegment("hand", 1), first.address.importedPath.single())
        assertEquals(ScenePathSegment("hand", 2), second.address.importedPath.single())
        assertNotEquals(second.address, named.address)
        assertNotEquals(second.address, other.address)
        listOf(first, second, named, other).forEach { assertSame(it, scene.resolve(it.address)) }
        assertFailsWith<IllegalArgumentException> { root.create("hand") }
        assertFailsWith<IllegalArgumentException> { ScenePathSegment("hand", 0) }
    }

    @Test
    fun `numbered paths resolve after rebuilding an instance with the same source order`() {
        val firstScene = scene()
        val original = firstScene.create("model")
        original.importChild("hand")
        val originalSecond = original.importChild("hand").importChild("finger")
        val address = originalSecond.address
        firstScene.close()

        val secondScene = scene()
        val reloaded = secondScene.create("model", address.instanceId)
        reloaded.importChild("hand")
        val reloadedSecond = reloaded.importChild("hand").importChild("finger")
        assertSame(reloadedSecond, secondScene.resolve(address))
    }

    @Test
    fun `children snapshots and lookup update through synthetic branches`() {
        val scene = scene()
        val root = scene.create("root")
        val roots = scene.children
        assertSame(roots, scene.children)
        val initial = root.children
        assertSame(initial, root.children)
        val firstHidden = root.importSynthetic("correction")
        val secondHidden = firstHidden.importSynthetic("pivot")
        val first = secondHidden.importModelChild(ScenePathSegment("hand"))
        val snapshot = root.children
        assertSame(snapshot, root.children)
        assertEquals(listOf(first), snapshot)
        assertTrue(initial.isEmpty())
        assertSame(root, first.parent)
        assertSame(first, scene.resolve(first.address))

        val second = secondHidden.importModelChild(ScenePathSegment("hand", 2))
        assertNotEquals(snapshot.size, root.children.size)
        assertSame(second, scene.resolve(second.address))
        first.rename("renamed")
        assertSame(first, scene.resolve(first.address))
        assertNull(scene.resolve(SceneAddress(root.createdId!!, listOf(ScenePathSegment("hand")))))
        second.remove()
        assertEquals(listOf(first), root.children)
        firstHidden.remove()
        assertTrue(root.children.isEmpty())
        root.remove()
        assertTrue(scene.children.isEmpty())
        assertEquals(listOf(root), roots)
    }

    @Test
    fun `moving imported nodes across synthetic transforms preserves objects and components`() {
        val scene = scene()
        val root = scene.create("model")
        val oldPivot = root.importSynthetic("old")
        oldPivot.localTransform.translate(2f, 0f, 0f)
        val hand = oldPivot.importModelChild(ScenePathSegment("hand"))
        val sword = hand.create("sword")
        val value = TestComponent("sound")
        val id = hand.attach(value)
        val address = hand.address
        assertEquals(2f, hand.worldTransform.getTranslation().x)

        val newPivot = root.importSynthetic("new")
        newPivot.localTransform.translate(5f, 0f, 0f)
        hand.moveWithinLogicalParent(newPivot)
        oldPivot.remove()

        assertSame(hand, scene.resolve(address))
        assertSame(root, hand.parent)
        assertSame(sword, hand.children.single())
        assertSame(value, hand.components[id])
        assertEquals(5f, hand.worldTransform.getTranslation().x)
        assertFailsWith<IllegalArgumentException> { hand.moveWithinLogicalParent(sword) }
        assertSame(hand, scene.resolve(address))
        scene.close()
    }

    @Test
    fun `moving into and out of disabled synthetic branches suspends and resumes components`() {
        val scene = scene()
        val root = scene.create("model")
        val active = root.importSynthetic("active")
        val disabled = root.importSynthetic("disabled")
        disabled.setEnabled(false)
        val node = active.importModelChild(ScenePathSegment("bone"))
        val events = mutableListOf<String>()
        node.attach(TestComponent("value") {
            object : ComponentLifecycle {
                override fun activate() { events += "activate" }
                override fun suspend() { events += "suspend" }
            }
        })

        node.moveWithinLogicalParent(disabled)
        assertFalse(node.isActive)
        node.moveWithinLogicalParent(active)
        assertTrue(node.isActive)
        assertEquals(listOf("activate", "suspend", "activate"), events)
        scene.close()
    }

    @Test
    fun `component snapshots are reused and invalidated on mutation and rollback`() {
        val node = scene().create("owner")
        val empty = node.components
        val first = TestComponent("first")
        val id = node.attach(first)
        val all = node.components
        assertSame(all, node.components)
        assertSame(first, node.components[id])
        val second = TestComponent("second")
        val secondId = node.attach(second)
        assertTrue(empty.isEmpty())
        assertEquals(mapOf(id to first), all)
        assertEquals(listOf(first, second), node.componentsOfType<TestComponent>())
        node.detach(id)
        assertEquals(mapOf(secondId to second), node.components)

        val failing = TestComponent("bad") { owner ->
            object : ComponentLifecycle {
                override fun attach() {
                    assertEquals(2, owner.components.size)
                    error("failed")
                }
            }
        }
        assertFailsWith<IllegalStateException> { node.attach(failing) }
        assertEquals(mapOf(secondId to second), node.components)
    }

    @Test
    fun `world transform composes parent and local matrices`() {
        val root = scene().create("root")
        val child = root.create("child")
        root.localTransform.translate(2f, 0f, 0f)
        child.localTransform.translate(0f, 3f, 0f)
        val point = MutableVec3f()
        child.worldTransform.transform(point, 1f)
        assertEquals(2f, point.x)
        assertEquals(3f, point.y)
    }

    @Test
    fun `world matrix is reused and follows changes at every level`() {
        val root = scene().create("root")
        val parent = root.create("parent")
        val child = parent.create("child")
        val other = root.create("other")
        val childMatrix = child.worldTransform
        val otherMatrix = other.worldTransform

        fun position() = MutableVec3f().also { child.worldTransform.transform(it, 1f) }

        assertSame(childMatrix, child.worldTransform)
        child.localTransform.translate(1f, 0f, 0f)
        assertEquals(1f, position().x)
        parent.localTransform.translate(0f, 2f, 0f)
        assertEquals(2f, position().y)
        root.localTransform.translate(0f, 0f, 3f)
        assertEquals(3f, position().z)
        assertSame(childMatrix, child.worldTransform)
        assertSame(otherMatrix, other.worldTransform)
        assertEquals(3f, MutableVec3f().also { other.worldTransform.transform(it, 1f) }.z)

        root.localTransform.translation.z = 4f
        root.localTransform.markDirty()
        assertEquals(4f, position().z)

        repeat(100) {
            root.localTransform.translate(1f, 0f, 0f)
            root.worldTransform
        }
        assertEquals(101f, position().x)
        assertEquals(2f, position().y)
        assertEquals(4f, position().z)
    }

    @Test
    fun `components enforce owner restrictions and unique IDs`() {
        val scene = scene()
        val node = scene.create("root")
        val sound = TestComponent("first")
        val id = node.attach(sound)
        val second = TestComponent("second")
        node.attach(second)
        assertEquals(listOf(sound, second), node.componentsOfType<TestComponent>())
        node.setComponent(id, sound.copy(value = "updated"))
        assertEquals("updated", (node.components[id] as TestComponent).value)
        assertFailsWith<IllegalArgumentException> { node.attach(sound, id) }

        val rootOnly = RootComponent()
        assertFailsWith<IllegalArgumentException> { node.attach(rootOnly) }
        val rootId = scene.attach(rootOnly)
        assertSame(rootOnly, scene.components[rootId])
        assertTrue(scene.detach(rootId))
    }

    @Test
    fun `disabling branch preserves own states and invokes ordered lifecycle exactly once`() {
        val events = mutableListOf<String>()
        fun component(value: String) = TestComponent(value) {
            object : ComponentLifecycle {
                override fun attach() { events += "attach $value" }
                override fun activate() { events += "activate $value" }
                override fun suspend() { events += "suspend $value" }
                override fun remove() { events += "remove $value" }
            }
        }
        val scene = scene()
        val root = scene.create("root")
        val child = root.create("child")
        root.attach(component("root"))
        val childId = child.attach(component("child"))
        events.clear()

        root.setEnabled(false)
        assertEquals(listOf("suspend child", "suspend root"), events)
        assertTrue(child.enabled)
        assertFalse(child.isActive)
        child.setComponentEnabled(childId, false)
        root.setEnabled(true)
        assertEquals(listOf("suspend child", "suspend root", "activate root"), events)
        assertFalse(child.componentEnabled(childId))
        child.setComponentEnabled(childId, true)
        assertTrue(child.componentEnabled(childId))

        scene.close()
        scene.close()
        root.remove()
        assertTrue(root.components.isEmpty())
        assertEquals(1, events.count { it == "remove root" })
        assertEquals(1, events.count { it == "remove child" })
        assertFailsWith<IllegalStateException> { child.setComponent(childId, component("late")) }
    }

    @Test
    fun `lifecycle callbacks see updated activity throughout the branch`() {
        val root = scene().create("root")
        val first = root.create("first")
        val second = root.create("second")
        val observations = mutableListOf<List<Boolean>>()
        val component = TestComponent("observe") {
            object : ComponentLifecycle {
                override fun activate() { observations += listOf(root.isActive, first.isActive, second.isActive) }
                override fun suspend() { observations += listOf(root.isActive, first.isActive, second.isActive) }
            }
        }
        root.attach(component)
        first.attach(component)
        second.attach(component)
        observations.clear()

        root.setEnabled(false)
        assertEquals(List(3) { listOf(false, false, false) }, observations)
        observations.clear()
        root.setEnabled(true)
        assertEquals(List(3) { listOf(true, true, true) }, observations)
    }

    @Test
    fun `cleanup continues after callback failures and cannot be reentered`() {
        val events = mutableListOf<String>()
        fun component(value: String) = TestComponent(value) { owner ->
            object : ComponentLifecycle {
                override fun remove() {
                    events += value
                    if (value == "bad") {
                        assertFailsWith<IllegalStateException> { owner.scene.create("reentrant") }
                        error("cleanup failed")
                    }
                }
            }
        }
        val scene = scene()
        val root = scene.create("root")
        root.attach(component("good"))
        root.attach(component("bad"))
        assertFailsWith<IllegalStateException> { scene.close() }
        assertEquals(listOf("bad", "good"), events)
        assertTrue(scene.isClosed)
        assertTrue(root.isRemoved)
        assertTrue(root.components.isEmpty())
        scene.close()
    }

    @Test
    fun `failed activation rolls back addition and leaves its owner usable`() {
        var removed = 0
        val failed = TestComponent("bad") {
            object : ComponentLifecycle {
                override fun activate() { error("failed") }
                override fun remove() { removed++ }
            }
        }
        val node = scene().create("root")
        assertFailsWith<IllegalStateException> { node.attach(failed) }
        assertTrue(node.components.isEmpty())
        assertEquals(1, removed)
        node.create("still usable")
    }

    @Test
    fun `activation failure disables component cleans partial runtime and allows retry`() {
        val events = mutableListOf<String>()
        var fail = false
        val component = TestComponent("data") {
            object : ComponentLifecycle {
                override fun activate() {
                    events += "activate"
                    if (fail) error("failed")
                }

                override fun suspend() { events += "suspend" }
            }
        }
        val root = scene().create("root")
        val id = root.attach(component)
        root.setEnabled(false)
        fail = true
        assertFailsWith<IllegalStateException> { root.setEnabled(true) }
        assertFalse(root.componentEnabled(id))
        fail = false
        root.setComponentEnabled(id, true)
        assertTrue(root.componentEnabled(id))
        assertEquals(listOf("activate", "suspend", "activate", "suspend", "activate"), events)
    }

    @Test
    fun `failed data change keeps updated data and suspends the component`() {
        var suspensions = 0
        val component = TestComponent("old") {
            object : ComponentLifecycle {
                override fun change(value: Any) { error("failed") }
                override fun suspend() { suspensions++ }
            }
        }
        val owner = scene().create("root")
        val id = owner.attach(component)
        val edited = component.copy(value = "new")
        assertFailsWith<IllegalStateException> { owner.setComponent(id, edited) }
        assertSame(edited, owner.components[id])
        assertFalse(owner.componentEnabled(id))
        assertEquals(1, suspensions)
    }
}
