package ru.hollowhorizon.hollowengine.common.scene

import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import java.util.UUID

class SceneObject internal constructor(
    override val scene: Scene,
    val parent: SceneObject?,
    name: String,
    /** Null for imported nodes, which are addressed by their path from the nearest created instance. */
    val createdId: UUID?,
) : SceneComponentOwner {
    private val ownChildren = mutableListOf<SceneObject>()
    private val componentSet = SceneComponentSet(this)
    val children: List<SceneObject> get() = ownChildren.toList()
    override val components: List<SceneComponent<*>> get() = componentSet.components
    var name: String = name
        private set
    var enabled: Boolean = true
        private set
    override var isRemoved: Boolean = false
        private set
    override val isActive: Boolean get() = !isRemoved && !scene.isClosed && enabled && (parent?.isActive ?: true)

    private val cachedWorldTransform = MutableMat4f()
    private var worldTransformDirty = true

    /** Use transform methods or call markDirty() after changing its mutable vectors directly. */
    val localTransform: TrsTransformF = object : TrsTransformF() {
        override fun markDirty() {
            super.markDirty()
            worldTransformDirty = true
        }
    }

    /** Cached live view; copy it when the matrix must remain unchanged after later scene updates. */
    val worldTransform: Mat4f
        get() {
            val parentTransform = parent?.worldTransform
            if (worldTransformDirty) {
                if (parentTransform == null) cachedWorldTransform.set(localTransform.matrixF)
                else cachedWorldTransform.set(parentTransform).mul(localTransform.matrixF)
                worldTransformDirty = false
                ownChildren.forEach { it.worldTransformDirty = true }
            }
            return cachedWorldTransform
        }

    val address: SceneAddress
        get() {
            checkAttached()
            val segments = mutableListOf<String>()
            var current = this
            while (current.createdId == null) {
                segments.add(current.name)
                current = checkNotNull(current.parent) { "Imported node has no created instance" }
            }
            return SceneAddress(checkNotNull(current.createdId), segments.asReversed())
        }

    fun rename(value: String) = scene.mutate {
        checkAttached()
        requireUniqueName(parent?.ownChildren ?: scene.children, value, this)
        name = value
    }

    fun create(name: String, id: UUID = UUID.randomUUID()): SceneObject = scene.mutate {
        checkAttached()
        requireUniqueName(ownChildren, name)
        scene.createObject(this, name, id).also(ownChildren::add)
    }

    fun importChild(name: String): SceneObject = scene.mutate {
        checkAttached()
        requireUniqueName(ownChildren, name)
        scene.createObject(this, name, null).also(ownChildren::add)
    }

    fun setEnabled(value: Boolean) = scene.mutate {
        checkAttached()
        if (enabled != value) {
            enabled = value
            val failures = SceneFailures()
            refreshBranch(failures)
            failures.throwIfAny()
        }
    }

    override fun <T : Any> addComponent(type: SceneComponentType<T>, data: T, id: UUID): SceneComponent<T> =
        componentSet.add(type, data, id)

    override fun component(id: UUID): SceneComponent<*>? = componentSet.component(id)
    override fun <T : Any> components(type: SceneComponentType<T>): List<SceneComponent<T>> = componentSet.components(type)
    override fun <T : Any> component(type: SceneComponentType<T>): SceneComponent<T>? = components(type).firstOrNull()
    override fun removeComponent(id: UUID): Boolean = componentSet.remove(id)

    fun remove() = scene.mutate {
        if (!isRemoved) {
            val failures = SceneFailures()
            removeInternal(failures)
            failures.throwIfAny()
        }
    }

    internal fun forgetChild(child: SceneObject) { ownChildren.remove(child) }

    internal fun removeInternal(failures: SceneFailures) {
        if (isRemoved) return
        ownChildren.toList().asReversed().forEach { it.removeInternal(failures) }
        componentSet.finish(failures)
        isRemoved = true
        scene.forget(this)
    }

    private fun refreshBranch(failures: SceneFailures) {
        if (isActive) {
            componentSet.refresh(failures, true)
            ownChildren.forEach { it.refreshBranch(failures) }
        } else {
            ownChildren.asReversed().forEach { it.refreshBranch(failures) }
            componentSet.refresh(failures, false)
        }
    }

    private fun checkAttached() = check(!isRemoved && !scene.isClosed) { "Scene object has been removed" }
}
