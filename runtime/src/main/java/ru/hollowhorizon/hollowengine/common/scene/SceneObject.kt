package ru.hollowhorizon.hollowengine.common.scene

import ru.hollowhorizon.hollowengine.common.models.ModelNodeSource
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import java.util.UUID

private val EMPTY_WEIGHTS = FloatArray(0)

class SceneObject internal constructor(
    override val scene: Scene,
    physicalParent: SceneObject?,
    name: String,
    /** Null for imported nodes, which are addressed by their path from the nearest created instance. */
    val createdId: UUID?,
    occurrence: Int,
    internal val isSynthetic: Boolean = false,
) : ComponentOwner {
    internal var physicalParent: SceneObject? = physicalParent
        private set
    private var ownChildren: MutableList<SceneObject>? = null
    internal val physicalChildren: List<SceneObject> get() = ownChildren ?: emptyList()
    internal var childrenRevision: Int = 0
        private set
    private var visibleChildren: List<SceneObject>? = null
    private var childIndex: ChildIndex? = null
    private var componentState: SceneComponentStore? = null
    internal var observer: SceneObjectObserver? = null
        private set
    private val componentSet: SceneComponentStore
        get() = componentState ?: SceneComponentStore(this).also { componentState = it }
    /** Authored children; the snapshot is reused until the relevant subtree changes. */
    val children: List<SceneObject>
        get() = visibleChildren ?: buildList { collectVisibleChildren(this) }.also { visibleChildren = it }
    /** Moves between synthetic transforms preserve this logical parent. */
    val parent: SceneObject? = if (physicalParent?.isSynthetic == true) physicalParent.parent else physicalParent
    override val components: Map<UUID, Any> get() = componentState?.components ?: emptyMap()
    override fun component(id: UUID): Any? = componentState?.component(id)
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

    private var modelState: ModelState? = null
    val modelSource: ModelNodeSource? get() = modelState?.source
    val source: ModelNodeSource get() = checkNotNull(modelSource) { "Scene object '$name' has no model source" }
    val poseIndex: Int get() = source.index
    val poseName: String get() = source.name ?: "Node_$poseIndex"
    val poseParent: SceneObject? get() = physicalParent?.takeIf { it.modelState != null }
    val restTransform: TrsTransformF get() = source.restTransform
    val acceptsPose: Boolean
        get() {
            val state = modelState ?: return false
            if (!isActive) return false
            if (!isSynthetic || state.hasGeometry) return true
            return children.isEmpty() || children.any { it.isActive }
        }
    var isAnimated: Boolean
        get() = modelState?.isAnimated == true
        set(value) { checkNotNull(modelState).isAnimated = value }
    val baseMorphWeights: FloatArray get() = modelState?.baseWeights ?: EMPTY_WEIGHTS
    val morphWeights: FloatArray get() = modelState?.weights ?: EMPTY_WEIGHTS
    internal val modelVisible: Boolean get() = modelState?.visible ?: true

    internal fun setModelSource(source: ModelNodeSource, weights: FloatArray? = null) {
        checkAttached()
        require(source.restTransform !== localTransform) { "The rest transform must not alias the scene object's pose" }
        modelState = ModelState(source, weights)
    }

    internal fun includeSyntheticGeometry() {
        checkNotNull(modelState).hasGeometry = true
    }

    internal fun setModelVisibility(visible: Boolean, includeChildren: Boolean = true) {
        val state = modelState ?: return
        if (state.visible != visible) {
            state.visible = visible
            scene.markChanged()
            observer?.onActivityChanged(isActive)
        }
        if (includeChildren) ownChildren?.forEach { it.setModelVisibility(visible) }
    }

    fun resetPose() {
        val state = modelState ?: return
        if (acceptsPose) state.reset(localTransform)
    }

    private class ModelState(val source: ModelNodeSource, initialWeights: FloatArray?) {
        val baseWeights = initialWeights?.takeIf { it.isNotEmpty() }?.copyOf() ?: EMPTY_WEIGHTS
        val weights = if (baseWeights.isEmpty()) EMPTY_WEIGHTS else baseWeights.copyOf()
        var hasGeometry = false
        var visible = true
        var isAnimated = false
        private var resetVersion = -1
        private var restVersion = -1

        fun reset(current: TrsTransformF) {
            isAnimated = false
            val rest = source.restTransform
            if (current.modCount != resetVersion || rest.modCount != restVersion) {
                if (current.translation != rest.translation || current.rotation != rest.rotation || current.scale != rest.scale) {
                    current.set(rest)
                }
                resetVersion = current.modCount
                restVersion = rest.modCount
            }
            if (weights.isNotEmpty()) baseWeights.copyInto(weights)
        }
    }

    private var cachedWorldTransform: MutableMat4f? = null
    private var worldTransformDirty = true
    private var cachedWorldVersion = 0
    private var inheritedVersion = 0
    private var hasInheritedVersion = false

    /** Changes when the cached world matrix is recomputed. */
    internal val worldVersion: Int
        get() {
            worldTransform
            return cachedWorldVersion
        }

    /** Use transform methods or call markDirty() after changing its mutable vectors directly. */
    val localTransform: TrsTransformF = object : TrsTransformF() {
        override fun markDirty() {
            super.markDirty()
            worldTransformDirty = true
            scene.markChanged()
        }
    }

    /** Cached live view; copy it when the matrix must remain unchanged after later scene updates. */
    val worldTransform: Mat4f
        get() {
            val external = if (physicalParent == null) scene.transformParent else null
            if (external != null) {
                val version = external.version
                if (!hasInheritedVersion || version != inheritedVersion) {
                    inheritedVersion = version
                    hasInheritedVersion = true
                    worldTransformDirty = true
                }
            }
            val parentTransform = physicalParent?.worldTransform ?: external?.matrix
            val result = cachedWorldTransform ?: MutableMat4f().also { cachedWorldTransform = it }
            if (worldTransformDirty) {
                if (parentTransform == null) result.set(localTransform.matrixF)
                else result.set(parentTransform).mul(localTransform.matrixF)
                worldTransformDirty = false
                cachedWorldVersion++
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
        scene.markChanged()
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
            scene.markChanged()
            val failures = SceneFailures()
            updateBranchActivity()
            notifyBranchActivity(failures)
            failures.throwIfAny()
        }
    }

    override fun attach(value: Any, id: UUID, enabled: Boolean): UUID = componentSet.add(value, id, enabled)
    internal fun restoreUnknown(value: Any, id: UUID) = componentSet.restoreUnknown(value, id)
    override fun setComponent(id: UUID, value: Any) = componentSet.set(id, value)
    override fun componentEnabled(id: UUID): Boolean = componentState?.enabled(id) == true
    override fun setComponentEnabled(id: UUID, enabled: Boolean) = componentSet.setEnabled(id, enabled)
    override fun detach(id: UUID): Boolean = componentState?.remove(id) == true

    internal fun observe(value: SceneObjectObserver) {
        checkAttached()
        check(observer == null) { "Scene object already has a runtime binding" }
        observer = value
    }

    internal fun unobserve(value: SceneObjectObserver) {
        if (observer === value) observer = null
    }


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
            childrenRevision++
            scene.markChanged()
            if (children.isEmpty()) ownChildren = null
            if (!child.isSynthetic) logicalOwner()?.childIndex?.remove(child)
            invalidateVisibleChildren()
        }
    }

    internal fun importedChild(segment: ScenePathSegment): SceneObject? =
        if (ownChildren.isNullOrEmpty()) null else index().imported[segment]

    /** Moves an imported node across transparent transforms without changing its authored address. */
    internal fun moveWithinLogicalParent(newParent: SceneObject) = scene.mutate {
        checkAttached()
        newParent.checkAttached()
        require(newParent.scene === scene) { "Cannot move a node between scenes" }
        require(createdId == null && !isSynthetic) { "Only authored imported nodes can be moved" }
        val logicalParent = if (newParent.isSynthetic) newParent.parent else newParent
        require(logicalParent === parent) { "Moving a node must preserve its authored parent" }
        val previous = checkNotNull(physicalParent)
        if (previous !== newParent) {
            previous.forgetChild(this)
            physicalParent = newParent
            newParent.addChild(this)
            worldTransformDirty = true
            val failures = SceneFailures()
            updateBranchActivity()
            notifyBranchActivity(failures)
            failures.throwIfAny()
        }
    }

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
        childrenRevision++
        scene.markChanged()
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
        failures.capture { observer?.onRemoved() }
        observer = null
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
            failures.capture { observer?.onActivityChanged(true) }
            ownChildren?.forEach { it.notifyBranchActivity(failures) }
        } else {
            ownChildren?.asReversed()?.forEach { it.notifyBranchActivity(failures) }
            failures.capture { observer?.onActivityChanged(false) }
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
