package ru.hollowhorizon.hollowengine.common.scene

import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import java.util.UUID

class SceneObject internal constructor(
    override val scene: Scene,
    internal val physicalParent: SceneObject?,
    name: String,
    /** Null for imported nodes, which are addressed by their path from the nearest created instance. */
    val createdId: UUID?,
    occurrence: Int,
    internal val isSynthetic: Boolean = false,
) : SceneComponentOwner {
    private var ownChildren: MutableList<SceneObject>? = null
    private var visibleChildren: List<SceneObject>? = null
    private var childIndex: ChildIndex? = null
    private var componentState: SceneComponentSet? = null
    private val componentSet: SceneComponentSet
        get() = componentState ?: SceneComponentSet(this).also { componentState = it }
    /** Authored children; the snapshot is reused until the relevant subtree changes. */
    val children: List<SceneObject>
        get() = visibleChildren ?: buildList { collectVisibleChildren(this) }.also { visibleChildren = it }
    /** Physical parentage cannot change, so the logical parent is resolved once. */
    val parent: SceneObject? = if (physicalParent?.isSynthetic == true) physicalParent.parent else physicalParent
    override val components: List<SceneComponent<*>> get() = componentState?.components ?: emptyList()
    var name: String = name
        private set
    private var occurrence: Int = occurrence
    val importedOccurrence: Int? get() = if (createdId == null) occurrence else null
    var enabled: Boolean = true
        private set
    override var isRemoved: Boolean = false
        private set
    private var effectiveActive = physicalParent?.isActive ?: !scene.isClosed
    private var activeChanged = false
    override val isActive: Boolean get() = !isRemoved && !scene.isClosed && effectiveActive

    private var cachedWorldTransform: MutableMat4f? = null
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
            val parentTransform = physicalParent?.worldTransform
            val result = cachedWorldTransform ?: MutableMat4f().also { cachedWorldTransform = it }
            if (worldTransformDirty) {
                if (parentTransform == null) result.set(localTransform.matrixF)
                else result.set(parentTransform).mul(localTransform.matrixF)
                worldTransformDirty = false
                ownChildren?.forEach { it.worldTransformDirty = true }
            }
            return result
        }

    val address: SceneAddress
        get() {
            checkAttached()
            val segments = mutableListOf<ScenePathSegment>()
            var current = this
            while (current.createdId == null) {
                if (!current.isSynthetic) segments.add(ScenePathSegment(current.name, current.occurrence))
                current = checkNotNull(current.physicalParent) { "Imported node has no created instance" }
            }
            return SceneAddress(checkNotNull(current.createdId), segments.asReversed())
        }

    fun rename(value: String) = scene.mutate {
        checkAttached()
        if (name == value) return@mutate
        requireUniqueName(parent?.children ?: scene.children, value, this)
        val index = parent?.childIndex
        index?.remove(this)
        name = value
        occurrence = 1
        index?.add(this)
    }

    fun create(name: String, id: UUID = UUID.randomUUID()): SceneObject = scene.mutate {
        checkAttached()
        requireUniqueName(children, name)
        scene.createObject(this, name, id).also(::addChild)
    }

    fun importChild(name: String): SceneObject = scene.mutate {
        checkAttached()
        require(name.isNotBlank()) { "Scene object name cannot be blank" }
        val siblings = children
        require(siblings.none { it.createdId != null && it.name == name }) { "Duplicate sibling name: $name" }
        val occurrence = (siblings.asSequence().filter { it.createdId == null && it.name == name }
            .maxOfOrNull { it.occurrence } ?: 0) + 1
        scene.createObject(this, name, null, occurrence).also(::addChild)
    }

    fun setEnabled(value: Boolean) = scene.mutate {
        checkAttached()
        if (enabled != value) {
            enabled = value
            val failures = SceneFailures()
            updateBranchActivity()
            notifyBranchActivity(failures)
            failures.throwIfAny()
        }
    }

    override fun <T : Any> addComponent(type: SceneComponentType<T>, data: T, id: UUID): SceneComponent<T> =
        componentSet.add(type, data, id)

    override fun component(id: UUID): SceneComponent<*>? = componentState?.component(id)
    override fun <T : Any> components(type: SceneComponentType<T>): List<SceneComponent<T>> =
        componentState?.components(type) ?: emptyList()
    override fun <T : Any> component(type: SceneComponentType<T>): SceneComponent<T>? = componentState?.component(type)
    override fun removeComponent(id: UUID): Boolean = componentSet.remove(id)

    fun remove() = scene.mutate {
        if (!isRemoved) {
            val failures = SceneFailures()
            removeInternal(failures)
            failures.throwIfAny()
        }
    }

    internal fun forgetChild(child: SceneObject) {
        val children = ownChildren ?: return
        if (children.remove(child)) {
            if (children.isEmpty()) ownChildren = null
            if (!child.isSynthetic) logicalOwner()?.childIndex?.remove(child)
            invalidateVisibleChildren()
        }
    }

    internal fun importedChild(segment: ScenePathSegment): SceneObject? =
        if (ownChildren.isNullOrEmpty()) null else index().imported[segment]

    internal fun importSynthetic(name: String): SceneObject = scene.mutate {
        checkAttached()
        scene.createObject(this, name, null, synthetic = true).also(::addChild)
    }

    internal fun importModelChild(segment: ScenePathSegment): SceneObject = scene.mutate {
        checkAttached()
        val logicalParent = if (isSynthetic) checkNotNull(parent) else this
        val index = logicalParent.index()
        require(segment !in index.imported) { "Duplicate imported path segment: $segment" }
        require(segment.name !in index.createdNames) {
            "Duplicate sibling name: ${segment.name}"
        }
        scene.createObject(this, segment.name, null, segment.occurrence).also(::addChild)
    }

    private fun addChild(child: SceneObject) {
        val children = ownChildren ?: ArrayList<SceneObject>().also { ownChildren = it }
        children.add(child)
        if (!child.isSynthetic) logicalOwner()?.childIndex?.add(child)
        invalidateVisibleChildren()
    }

    private fun logicalOwner(): SceneObject? = if (isSynthetic) parent else this

    private fun index(): ChildIndex = childIndex ?: ChildIndex().also { index ->
        children.forEach(index::add)
        childIndex = index
    }

    private fun invalidateVisibleChildren() {
        var current: SceneObject? = this
        while (current != null) {
            current.visibleChildren = null
            if (!current.isSynthetic) break
            current = current.physicalParent
        }
    }

    private fun collectVisibleChildren(result: MutableList<SceneObject>) {
        ownChildren?.forEach { child ->
            if (child.isSynthetic) child.collectVisibleChildren(result) else result.add(child)
        }
    }

    internal fun removeInternal(failures: SceneFailures) {
        if (isRemoved) return
        ownChildren?.toList()?.asReversed()?.forEach { it.removeInternal(failures) }
        componentState?.finish(failures)
        isRemoved = true
        scene.forget(this)
    }

    private fun updateBranchActivity() {
        val active = !isRemoved && !scene.isClosed && enabled && (physicalParent?.isActive ?: true)
        if (effectiveActive == active) return
        effectiveActive = active
        activeChanged = true
        ownChildren?.forEach { it.updateBranchActivity() }
    }

    private fun notifyBranchActivity(failures: SceneFailures) {
        if (!activeChanged) return
        activeChanged = false
        if (effectiveActive) {
            componentState?.refresh(failures, true)
            ownChildren?.forEach { it.notifyBranchActivity(failures) }
        } else {
            ownChildren?.asReversed()?.forEach { it.notifyBranchActivity(failures) }
            componentState?.refresh(failures, false)
        }
    }

    private fun checkAttached() = check(!isRemoved && !scene.isClosed) { "Scene object has been removed" }

    private class ChildIndex {
        val imported = HashMap<ScenePathSegment, SceneObject>()
        val createdNames = HashSet<String>()

        fun add(child: SceneObject) {
            if (child.createdId == null) imported[ScenePathSegment(child.name, child.occurrence)] = child
            else createdNames.add(child.name)
        }

        fun remove(child: SceneObject) {
            if (child.createdId == null) imported.remove(ScenePathSegment(child.name, child.occurrence))
            else createdNames.remove(child.name)
        }
    }
}
