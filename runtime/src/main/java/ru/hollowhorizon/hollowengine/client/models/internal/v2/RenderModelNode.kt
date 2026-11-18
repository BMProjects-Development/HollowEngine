package ru.hollowhorizon.hollowengine.client.models.internal.v2

import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.common.models.ModelPoseNode
import ru.hollowhorizon.hollowengine.common.scene.SceneModelPose
import ru.hollowhorizon.hollowengine.common.scene.SceneFailures
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.scene.SceneObjectObserver
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF

interface Attachment : AutoCloseable {
    val attachmentParent: Attachment?
    val transform: TrsTransformF
    val globalMatrix: Mat4f
    val globalVersion: Int

    override fun close() = Unit
    fun updateWorldPose(modelToWorld: TrsTransformF, entity: Entity) = Unit

    /** Whether [updateWorldPose] does anything: a model asks only such attachments, every frame. */
    val followsWorldPose: Boolean get() = false
    fun updateGlobalMatrix()
    fun collectCommands(pipeline: RenderPipeline) = Unit
    fun onSceneActiveChanged(active: Boolean) = Unit
}

abstract class BaseAttachment(parent: Attachment? = null, override val transform: TrsTransformF = TrsTransformF()) : Attachment {
    final override var attachmentParent: Attachment? = parent
        private set
    private val matrix by lazy { MutableMat4f() }
    override val globalMatrix: Mat4f get() = matrix

    /** Changes whenever [globalMatrix] does, so what hangs below knows it has to follow. */
    private var version = 0
    override val globalVersion: Int get() = version
    private var appliedTransform = -1
    private var appliedParent = -1
    private var matrixValid = false

    protected fun reparentAttachment(parent: Attachment?) {
        if (attachmentParent !== parent) {
            attachmentParent = parent
            matrixValid = false
        }
    }

    /** Works [globalMatrix] out again, unless neither [transform] nor the parent's matrix changed since. */
    override fun updateGlobalMatrix() {
        val parent = attachmentParent
        val parentVersion = parent?.globalVersion ?: 0
        if (matrixValid && transform.modCount == appliedTransform && parentVersion == appliedParent) return

        when {
            parent == null -> matrix.set(transform.matrixF)
            transform.isIdentity() -> matrix.set(parent.globalMatrix)
            else -> matrix.set(parent.globalMatrix).mul(transform.matrixF)
        }
        appliedTransform = transform.modCount
        appliedParent = parentVersion
        matrixValid = true
        version++
    }
}

class RenderModelNode internal constructor(
    private val scenePose: SceneModelPose,
    override val attachmentParent: Attachment,
    private val owner: ModelAttachment,
    materialResolver: (Material) -> Material,
) : Attachment, ModelPoseNode by scenePose, SceneObjectObserver {
    val definition: NodeDefinition = scenePose.source as NodeDefinition
    internal val sceneObject: SceneObject get() = scenePose.sceneObject
    override val transform: TrsTransformF get() = poseTransform
    override val globalMatrix: Mat4f get() = sceneObject.worldTransform
    override val globalVersion: Int get() = sceneObject.worldVersion
    override val poseParent: ModelPoseNode? get() = attachmentParent as? RenderModelNode
    /** Rigs and animations find the node by it, so it depends on the node alone, never on what holds it. */
    val name: String = definition.name ?: "Node_${definition.index}"
    internal val supportsMeshExtrusion: Boolean
        get() = definition.mesh?.primitives?.any { it.supportsExtrusion } == true
    var isVisible: Boolean = definition.visible && ((attachmentParent as? RenderModelNode)?.isVisible ?: true)
        get() = field && sceneObject.isActive
        set(value) {
            if (field != value) {
                field = value
                onActivityChanged(sceneObject.isActive)
            }
            children.forEach { it.isVisible = value }
        }

    val attachments = arrayListOf<Attachment>()
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        sceneObject.unobserve(this)
        val failures = SceneFailures()
        attachments.forEach { attachment -> failures.capture(attachment::close) }
        children.forEach { child -> failures.capture(child::close) }
        attachments.clear()
        cachedChildren = null
        failures.throwIfAny()
    }

    override fun onActivityChanged(active: Boolean) {
        owner.appearanceChanged()
        val failures = SceneFailures()
        attachments.forEach { attachment ->
            failures.capture { attachment.onSceneActiveChanged(active && isVisible) }
        }
        failures.throwIfAny()
    }

    override fun onRemoved() {
        owner.appearanceChanged()
        owner.sceneNodeRemoved(this)
        close()
    }

    init {
        definition.mesh?.primitives?.forEach { primitive ->
            attachments += MeshAttachment(primitive, this, materialResolver(primitive.material))
        }
    }

    private var cachedChildren: List<RenderModelNode>? = null
    private var cachedChildrenRevision = -1

    init { sceneObject.observe(this) }

    val children: List<RenderModelNode>
        get() {
            val objectNode = sceneObject
            val cached = cachedChildren
            if (cached != null && cachedChildrenRevision == objectNode.childrenRevision) return cached
            return objectNode.physicalChildren.mapNotNull { child ->
                (child.observer as? RenderModelNode)?.takeIf { it.attachmentParent === this }
            }.also {
                cachedChildren = it
                cachedChildrenRevision = objectNode.childrenRevision
            }
        }

    internal val modelPoseNodes: Map<Int, ModelPoseNode> get() = owner.scenePoseNodes

    override fun collectCommands(pipeline: RenderPipeline) {
        attachments.forEach {
            it.collectCommands(pipeline)
        }
        children.forEach {
            it.collectCommands(pipeline)
        }
    }

    fun updateHierarchyMatrices() {
        if (!acceptsPose) return
        updateGlobalMatrix()
        attachments.forEach(Attachment::updateGlobalMatrix)
        children.forEach(RenderModelNode::updateHierarchyMatrices)
    }

    override fun updateGlobalMatrix() {
        sceneObject.worldTransform
    }

}

/** Whether the transform leaves everything where it is, as most meshes on their bones do. */
private fun TrsTransformF.isIdentity(): Boolean =
    translation.x == 0f && translation.y == 0f && translation.z == 0f &&
        rotation.x == 0f && rotation.y == 0f && rotation.z == 0f && rotation.w == 1f &&
        scale.x == 1f && scale.y == 1f && scale.z == 1f

/** This node and every node under it, parents before children. */
fun RenderModelNode.walk(): List<RenderModelNode> = ArrayList<RenderModelNode>().also { collectInto(it) }

private fun RenderModelNode.collectInto(into: MutableList<RenderModelNode>) {
    into += this
    children.forEach { it.collectInto(into) }
}

/** Every node of the hierarchy, indexed the way poses address them. */
fun List<RenderModelNode>.byIndex(): Map<Int, RenderModelNode> =
    flatMap { it.walk() }.associateBy { it.definition.index }
