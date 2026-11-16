package ru.hollowhorizon.hollowengine.common.scene

import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import java.util.UUID
import kotlin.reflect.KClass

/** Context supplied by the owning server, entity, preview, or another scene host. */
interface SceneContext {
    val side: LogicalSide
    fun <T : Any> capability(type: KClass<T>): T? = null
}

/** One imported child, numbered among siblings with the same name in source order. */
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
class Scene(val context: SceneContext) : SceneComponentOwner, AutoCloseable {
    private val roots = mutableListOf<SceneObject>()
    private var rootSnapshot: List<SceneObject>? = null
    private val created = mutableMapOf<UUID, SceneObject>()
    private val componentTypes = mutableMapOf<ResourceLocation, SceneComponentType<*>>()
    private val componentSet = SceneComponentSet(this)
    private var mutating = false
    var isClosed: Boolean = false
        private set
    val children: List<SceneObject> get() = rootSnapshot ?: buildList { addAll(roots) }.also { rootSnapshot = it }
    override val scene: Scene get() = this
    override val isActive: Boolean get() = !isClosed
    override val isRemoved: Boolean get() = isClosed
    override val components: List<SceneComponent<*>> get() = componentSet.components

    override fun <T : Any> addComponent(type: SceneComponentType<T>, data: T, id: UUID): SceneComponent<T> =
        componentSet.add(type, data, id)
    override fun component(id: UUID): SceneComponent<*>? = componentSet.component(id)
    override fun <T : Any> components(type: SceneComponentType<T>): List<SceneComponent<T>> = componentSet.components(type)
    override fun <T : Any> component(type: SceneComponentType<T>): SceneComponent<T>? = componentSet.component(type)
    override fun removeComponent(id: UUID): Boolean = componentSet.remove(id)

    fun create(name: String, id: UUID = UUID.randomUUID()): SceneObject = mutate {
        checkOpen()
        requireUniqueName(roots, name)
        createObject(null, name, id).also {
            roots.add(it)
            rootSnapshot = null
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
            val failures = SceneFailures()
            roots.toList().asReversed().forEach { it.removeInternal(failures) }
            roots.clear()
            rootSnapshot = null
            componentSet.finish(failures)
            componentTypes.clear()
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
        if (node.physicalParent == null) {
            if (roots.remove(node)) rootSnapshot = null
        } else node.physicalParent.forgetChild(node)
    }

    internal fun checkOpen() = check(!isClosed) { "Scene is closed" }

    internal fun registerType(type: SceneComponentType<*>) {
        val existing = componentTypes[type.id]
        require(existing == null || existing === type) { "Conflicting component descriptors for ${type.id}" }
        componentTypes[type.id] = type
    }

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
