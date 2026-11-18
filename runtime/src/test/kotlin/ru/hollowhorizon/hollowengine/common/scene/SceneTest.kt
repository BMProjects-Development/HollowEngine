package ru.hollowhorizon.hollowengine.common.scene

import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.rl
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

    private fun type(
        multiple: Boolean = false,
        accepts: (SceneComponentOwner) -> Boolean = { true },
        sides: Set<LogicalSide> = LogicalSide.entries.toSet(),
        runtime: (SceneComponent<String>) -> SceneComponentRuntime<String>? = { null },
    ) = SceneComponentType(
        "test:sound".rl, String::class, String.serializer(), multiple = multiple,
        accepts = accepts, sides = sides, createRuntime = runtime,
    )

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
        val component = hand.addComponent(type(), "sound")
        val address = hand.address
        assertEquals(2f, hand.worldTransform.getTranslation().x)

        val newPivot = root.importSynthetic("new")
        newPivot.localTransform.translate(5f, 0f, 0f)
        hand.moveWithinLogicalParent(newPivot)
        oldPivot.remove()

        assertSame(hand, scene.resolve(address))
        assertSame(root, hand.parent)
        assertSame(sword, hand.children.single())
        assertSame(component, hand.components.single())
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
        val component = node.addComponent(type(runtime = {
            object : SceneComponentRuntime<String> {
                override fun onActivate(component: SceneComponent<String>) { events += "activate" }
                override fun onSuspend(component: SceneComponent<String>) { events += "suspend" }
            }
        }), "value")

        node.moveWithinLogicalParent(disabled)
        assertFalse(component.isActive)
        node.moveWithinLogicalParent(active)
        assertTrue(component.isActive)
        assertEquals(listOf("activate", "suspend", "activate"), events)
        scene.close()
    }

    @Test
    fun `component snapshots are reused and invalidated on mutation and rollback`() {
        val node = scene().create("owner")
        val many = type(multiple = true)
        val empty = node.components
        val first = node.addComponent(many, "first")
        val all = node.components
        val typed = node.components(many)
        assertSame(all, node.components)
        assertSame(typed, node.components(many))
        assertSame(first, node.component(many))
        val second = node.addComponent(many, "second")
        assertTrue(empty.isEmpty())
        assertEquals(listOf(first), all)
        assertEquals(listOf(first), typed)
        assertEquals(listOf(first, second), node.components(many))
        first.remove()
        assertEquals(listOf(second), node.components)

        val failing = SceneComponentType(
            "test:failing".rl, String::class, String.serializer(), createRuntime = {
                object : SceneComponentRuntime<String> {
                    override fun onAttach(component: SceneComponent<String>) {
                        assertEquals(2, component.owner.components.size)
                        error("failed")
                    }
                }
            },
        )
        assertFailsWith<IllegalStateException> { node.addComponent(failing, "bad") }
        assertEquals(listOf(second), node.components)
        assertTrue(node.components(failing).isEmpty())
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
    fun `component type restrictions IDs and typed lookup are enforced`() {
        val scene = scene()
        val node = scene.create("root")
        val many = type(
            multiple = true,
            accepts = { it is SceneObject && it.parent == null },
            sides = setOf(LogicalSide.SERVER)
        )
        val first = node.addComponent(many, "first")
        val second = node.addComponent(many, "second")
        assertSame(first, node.component(many))
        assertEquals(listOf(first, second), node.components(many))
        assertSame(second, node.component(second.id))
        first.setData("updated")
        assertEquals("updated", first.data)
        assertFailsWith<IllegalArgumentException> { node.addComponent(many, "duplicate", first.id) }
        assertFailsWith<IllegalArgumentException> { node.create("child").addComponent(many, "invalid") }
        assertFailsWith<IllegalArgumentException> {
            scene(LogicalSide.CLIENT).create("client").addComponent(many, "invalid")
        }
        assertFailsWith<IllegalArgumentException> { node.create("other").addComponent(type(), "conflicting") }

        val singleScene = scene()
        val single = singleScene.create("single")
        val once = type()
        single.addComponent(once, "one")
        assertFailsWith<IllegalArgumentException> { single.addComponent(once, "two") }
    }

    @Test
    fun `disabling branch preserves own states and invokes ordered lifecycle exactly once`() {
        val events = mutableListOf<String>()
        val type = type(multiple = true, runtime = {
            object : SceneComponentRuntime<String> {
                override fun onAttach(component: SceneComponent<String>) {
                    events += "attach ${component.data}"
                }

                override fun onActivate(component: SceneComponent<String>) {
                    events += "activate ${component.data}"
                }

                override fun onSuspend(component: SceneComponent<String>) {
                    events += "suspend ${component.data}"
                }

                override fun onRemove(component: SceneComponent<String>) {
                    events += "remove ${component.data}"
                }
            }
        })
        val scene = scene()
        val root = scene.create("root")
        val child = root.create("child")
        val rootComponent = root.addComponent(type, "root")
        val childComponent = child.addComponent(type, "child")
        events.clear()
        root.setEnabled(false)
        assertEquals(listOf("suspend child", "suspend root"), events)
        assertTrue(child.enabled)
        assertFalse(child.isActive)
        childComponent.setEnabled(false)
        root.setEnabled(true)
        assertEquals(listOf("suspend child", "suspend root", "activate root"), events)
        assertFalse(childComponent.isActive)
        childComponent.setEnabled(true)
        assertTrue(childComponent.isActive)
        scene.close()
        scene.close()
        root.remove()
        assertTrue(rootComponent.isRemoved)
        assertEquals(1, events.count { it == "remove root" })
        assertEquals(1, events.count { it == "remove child" })
        assertFailsWith<IllegalStateException> { childComponent.setData("late") }
    }

    @Test
    fun `lifecycle callbacks see updated activity throughout the branch`() {
        val root = scene().create("root")
        val first = root.create("first")
        val second = root.create("second")
        val observations = mutableListOf<List<Boolean>>()
        val type = type(multiple = true, runtime = {
            object : SceneComponentRuntime<String> {
                override fun onActivate(component: SceneComponent<String>) {
                    observations += listOf(root.isActive, first.isActive, second.isActive)
                }

                override fun onSuspend(component: SceneComponent<String>) {
                    observations += listOf(root.isActive, first.isActive, second.isActive)
                }
            }
        })
        root.addComponent(type, "root")
        first.addComponent(type, "first")
        second.addComponent(type, "second")
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
        val type = type(multiple = true, runtime = {
            object : SceneComponentRuntime<String> {
                override fun onRemove(component: SceneComponent<String>) {
                    events += component.data
                    if (component.data == "bad") {
                        assertFailsWith<IllegalStateException> { component.owner.scene.create("reentrant") }
                        error("cleanup failed")
                    }
                }
            }
        })
        val scene = scene()
        val root = scene.create("root")
        val good = root.addComponent(type, "good")
        val bad = root.addComponent(type, "bad")
        assertFailsWith<IllegalStateException> { scene.close() }
        assertEquals(listOf("bad", "good"), events)
        assertTrue(scene.isClosed)
        assertTrue(root.isRemoved)
        assertTrue(good.isRemoved)
        assertTrue(bad.isRemoved)
        scene.close()
    }

    @Test
    fun `failed activation cleans up the component and leaves its owner usable`() {
        var removed = 0
        val badType = type(runtime = {
            object : SceneComponentRuntime<String> {
                override fun onActivate(component: SceneComponent<String>) {
                    error("failed")
                }

                override fun onRemove(component: SceneComponent<String>) {
                    removed++
                }
            }
        })
        val node = scene().create("root")
        assertFailsWith<IllegalStateException> { node.addComponent(badType, "bad") }
        assertTrue(node.components.isEmpty())
        assertEquals(1, removed)
        node.create("still usable")
    }

    @Test
    fun `owner components need no transform and dependencies prevent premature removal`() {
        val scene = scene()
        val dependency = SceneComponentType("test:base".rl, String::class, String.serializer())
        val dependent = SceneComponentType(
            "test:dependent".rl, String::class, String.serializer(), requires = setOf(dependency.id),
            accepts = { it is Scene },
        )
        assertFailsWith<IllegalArgumentException> { scene.addComponent(dependent, "missing") }
        val base = scene.addComponent(dependency, "base")
        val value = scene.addComponent(dependent, "dependent")
        assertSame(value, scene.component(dependent))
        assertFailsWith<IllegalArgumentException> { base.remove() }
        value.remove()
        base.remove()
        assertTrue(scene.components.isEmpty())
    }

    @Test
    fun `activation failure disables component cleans partial runtime and allows retry`() {
        val events = mutableListOf<String>()
        var fail = false
        val type = type(runtime = {
            object : SceneComponentRuntime<String> {
                override fun onActivate(component: SceneComponent<String>) {
                    events += "activate"
                    if (fail) error("failed")
                }

                override fun onSuspend(component: SceneComponent<String>) {
                    events += "suspend"
                }
            }
        })
        val root = scene().create("root")
        val component = root.addComponent(type, "data")
        root.setEnabled(false)
        fail = true
        assertFailsWith<IllegalStateException> { root.setEnabled(true) }
        assertFalse(component.enabled)
        assertFalse(component.isActive)
        fail = false
        component.setEnabled(true)
        assertTrue(component.isActive)
        assertEquals(listOf("activate", "suspend", "activate", "suspend", "activate"), events)
    }

    @Test
    fun `failed data change preserves data and suspends the component`() {
        var suspensions = 0
        val type = type(runtime = {
            object : SceneComponentRuntime<String> {
                override fun onChange(component: SceneComponent<String>, previous: String) {
                    error("failed")
                }

                override fun onSuspend(component: SceneComponent<String>) {
                    suspensions++
                }
            }
        })
        val component = scene().create("root").addComponent(type, "old")
        assertFailsWith<IllegalStateException> { component.setData("new") }
        assertEquals("new", component.data)
        assertFalse(component.enabled)
        assertFalse(component.isActive)
        assertEquals(1, suspensions)
    }
}
