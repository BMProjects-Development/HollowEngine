package ru.hollowhorizon.hollowengine.common.scene

import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import java.util.UUID
import kotlin.reflect.KClass

/** Context supplied by the owning server, entity, preview, or another scene host. */
interface SceneContext {
    val side: LogicalSide
    fun <T : Any> capability(type: KClass<T>): T? = null
    fun createLifecycle(owner: ComponentOwner, id: UUID, value: Any): ComponentLifecycle? =
        (value as? ComponentBehavior)?.bind(owner, id)
    fun onLifecycleActivityChanged(lifecycle: ComponentLifecycle, active: Boolean) = Unit
    fun onComponentActivity(owner: ComponentOwner, id: UUID, value: Any, activity: ComponentActivity) = Unit
}

enum class ComponentActivity { ACTIVE, SUSPENDED, REMOVED }

/** A scene may be placed under a transform supplied by its owner. */
interface SceneTransformParent {
    val matrix: Mat4f
    val version: Int
}

/** One imported child, numbered among siblings with the same name in source order. */
@Serializable
data class ScenePathSegment(val name: String, val occurrence: Int = 1) {
    init {
        require(name.isNotBlank()) { "Scene path segment cannot be blank" }
        require(occurrence > 0) { "Scene path occurrence must be positive" }
    }
}

/** Addresses are relative to a scene owner; an imported path starts at a created instance. */
data class SceneAddress(val instanceId: UUID, val importedPath: List<ScenePathSegment> = emptyList())

/**
 * An in-memory hierarchy confined to its host's thread. Closing it removes children before owner components.
 * Persistence, synchronization, and attachment to Minecraft owners are provided by later integrations.
 */
class Scene(val context: SceneContext, internal val transformParent: SceneTransformParent? = null) : ComponentOwner, AutoCloseable {
    private val roots = mutableListOf<SceneObject>()
    private var rootSnapshot: List<SceneObject>? = null
    private val created = mutableMapOf<UUID, SceneObject>()
    private val componentSet = SceneComponentStore(this)
    private var mutating = false
    internal var revision: Int = 0
        private set
    var isClosed: Boolean = false
        private set
    val children: List<SceneObject> get() = rootSnapshot ?: buildList { addAll(roots) }.also { rootSnapshot = it }
    override val scene: Scene get() = this
    override val isActive: Boolean get() = !isClosed
    override val isRemoved: Boolean get() = isClosed
    override val components: Map<UUID, Any> get() = componentSet.components
    override fun component(id: UUID): Any? = componentSet.component(id)

    override fun attach(value: Any, id: UUID, enabled: Boolean): UUID = componentSet.add(value, id, enabled)
    internal fun restoreUnknown(value: Any, id: UUID) = componentSet.restoreUnknown(value, id)
    override fun setComponent(id: UUID, value: Any) = componentSet.set(id, value)
    override fun componentEnabled(id: UUID): Boolean = componentSet.enabled(id)
    override fun setComponentEnabled(id: UUID, enabled: Boolean) = componentSet.setEnabled(id, enabled)
    override fun detach(id: UUID): Boolean = componentSet.remove(id)

    fun create(name: String, id: UUID = UUID.randomUUID()): SceneObject = mutate {
        checkOpen()
        requireUniqueName(roots, name)
        createObject(null, name, id).also {
            roots.add(it)
            rootSnapshot = null
            markChanged()
        }
    }

    fun resolve(address: SceneAddress): SceneObject? {
        var current = created[address.instanceId] ?: return null
        for (segment in address.importedPath) {
            current = current.importedChild(segment) ?: return null
        }
        return current
    }

    override fun close() = mutate {
        if (!isClosed) {
            isClosed = true
            markChanged()
            val failures = SceneFailures()
            roots.toList().asReversed().forEach { it.removeInternal(failures) }
            roots.clear()
            rootSnapshot = null
            componentSet.finish(failures)
            failures.throwIfAny()
        }
    }

    internal fun createObject(
        parent: SceneObject?, name: String, id: UUID?, occurrence: Int = 1, synthetic: Boolean = false,
    ): SceneObject {
        require(name.isNotBlank()) { "Scene object name cannot be blank" }
        require(id != null || parent != null) { "An imported node must belong to a created instance" }
        require(!synthetic || id == null) { "A synthetic node cannot have a created object ID" }
        require(occurrence > 0) { "Scene path occurrence must be positive" }
        if (id != null) require(id !in created) { "Duplicate scene object ID: $id" }
        return SceneObject(this, parent, name, id, occurrence, synthetic).also { if (id != null) created[id] = it }
    }

    internal fun forget(node: SceneObject) {
        node.createdId?.let(created::remove)
        val parent = node.physicalParent
        if (parent == null) {
            if (roots.remove(node)) {
                rootSnapshot = null
                markChanged()
            }
        } else parent.forgetChild(node)
    }

    internal fun markChanged() { revision++ }

    internal fun checkOpen() = check(!isClosed) { "Scene is closed" }


    internal inline fun <T> mutate(action: () -> T): T {
        check(!mutating) { "Scene cannot be mutated from a lifecycle callback" }
        mutating = true
        try {
            return action()
        } finally {
            mutating = false
        }
    }
}

internal fun requireUniqueName(siblings: List<SceneObject>, name: String, except: SceneObject? = null) {
    require(name.isNotBlank()) { "Scene object name cannot be blank" }
    require(siblings.none { it !== except && it.name == name }) { "Duplicate sibling name: $name" }
}
