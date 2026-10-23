package ru.hollowhorizon.hollowengine.client.models.internal.v2

import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF

abstract class Attachment(val parent: Attachment? = null) : AutoCloseable {
    override fun close() = Unit
    open fun updateWorldPose(modelToWorld: TrsTransformF, entity: Entity) = Unit

    /** Whether [updateWorldPose] does anything: a model asks only such attachments, every frame. */
    open val followsWorldPose: Boolean get() = false
    val transform = TrsTransformF()
    val globalMatrix = MutableMat4f()

    /** Changes whenever [globalMatrix] does, so what hangs below knows it has to follow. */
    var globalVersion = 0
        private set
    private var appliedTransform = -1
    private var appliedParent = -1

    /** Works [globalMatrix] out again, unless neither [transform] nor the parent's matrix changed since. */
    open fun updateGlobalMatrix() {
        val parentVersion = parent?.globalVersion ?: 0
        if (transform.modCount == appliedTransform && parentVersion == appliedParent) return

        when {
            parent == null -> globalMatrix.set(transform.matrixF)
            transform.isIdentity() -> globalMatrix.set(parent.globalMatrix)
            else -> globalMatrix.set(parent.globalMatrix).mul(transform.matrixF)
        }
        appliedTransform = transform.modCount
        appliedParent = parentVersion
        globalVersion++
    }

    open fun collectCommands(pipeline: RenderPipeline) {
    }
}

open class RuntimeNode(
    val definition: NodeDefinition,
    parent: Attachment?,
    private val materialResolver: (Material) -> Material = { it },
) : Attachment(parent) {
    /** Rigs and animations find the node by it, so it depends on the node alone, never on what holds it. */
    val name: String = definition.name ?: "Node_${definition.index}"
    /** Set by a sampled channel and reset at the start of each pose. */
    var isAnimated: Boolean = false
    var isVisible: Boolean = definition.visible && ((parent as? RuntimeNode)?.isVisible ?: true)
        set(value) {
            if (field != value) owningModel()?.appearanceChanged()
            field = value
            children.forEach { it.isVisible = value }
        }

    private fun owningModel(): ModelAttachment? =
        generateSequence(parent) { it.parent }.firstNotNullOfOrNull { it as? ModelAttachment }

    init {
        transform.set(definition.baseTransform)
    }

    val attachments = arrayListOf<Attachment>()

    override fun close() {
        attachments.forEach(Attachment::close)
        children.forEach(RuntimeNode::close)
    }

    override fun updateWorldPose(modelToWorld: TrsTransformF, entity: Entity) {
        attachments.forEach { it.updateWorldPose(modelToWorld, entity) }
        children.forEach { it.updateWorldPose(modelToWorld, entity) }
    }

    val baseMorphWeights: FloatArray = definition.mesh
        ?.primitives
        ?.firstOrNull()
        ?.weights
        ?.copyOf()
        ?: floatArrayOf()

    val morphWeights: FloatArray = baseMorphWeights.copyOf()

    init {
        definition.mesh?.primitives?.forEach { primitive ->
            attachments += MeshAttachment(primitive, this, materialResolver(primitive.material))
        }
    }

    private val ownChildren: MutableList<RuntimeNode> = definition.children.mapTo(ArrayList()) {
        RuntimeNode(it, this, materialResolver)
    }

    val children: List<RuntimeNode> get() = ownChildren

    /**
     * Builds a node under this one that the model itself does not have, as a bone a rig adds. The model's own
     * definitions are shared by every instance and stay as they are.
     */
    fun adopt(definition: NodeDefinition): RuntimeNode {
        definition.parent = this.definition
        return RuntimeNode(definition, this, materialResolver).also(ownChildren::add)
    }

    val jointGetter by lazy {
        definition.skin!!.jointsIds.associateWith { id -> root.walk().first { it.definition.index == id } }
    }

    override fun collectCommands(pipeline: RenderPipeline) {
        super.collectCommands(pipeline)
        attachments.forEach {
            it.collectCommands(pipeline)
        }
        children.forEach {
            it.collectCommands(pipeline)
        }
    }

    fun child(name: String): RuntimeNode = children.single { it.name == name }

    private var restTransform = -1
    private var restBase = -1

    /** Puts the node back at rest; one nothing moved since the last time is left as it is. */
    fun resetPose() {
        isAnimated = false
        val base = definition.baseTransform
        if (transform.modCount != restTransform || base.modCount != restBase) {
            transform.set(base)
            restTransform = transform.modCount
            restBase = base.modCount
        }
        if (morphWeights.isNotEmpty()) baseMorphWeights.copyInto(morphWeights)
    }

    fun updateHierarchyMatrices() {
        updateGlobalMatrix()
        attachments.forEach(Attachment::updateGlobalMatrix)
        children.forEach(RuntimeNode::updateHierarchyMatrices)
    }
}

/** Whether the transform leaves everything where it is, as most meshes on their bones do. */
private fun TrsTransformF.isIdentity(): Boolean =
    translation.x == 0f && translation.y == 0f && translation.z == 0f &&
        rotation.x == 0f && rotation.y == 0f && rotation.z == 0f && rotation.w == 1f &&
        scale.x == 1f && scale.y == 1f && scale.z == 1f

/** This node and every node under it, parents before children. */
fun RuntimeNode.walk(): List<RuntimeNode> = ArrayList<RuntimeNode>().also { collectInto(it) }

private fun RuntimeNode.collectInto(into: MutableList<RuntimeNode>) {
    into += this
    children.forEach { it.collectInto(into) }
}

val RuntimeNode.root: RuntimeNode
    get() {
        var current: RuntimeNode = this
        while (current.parent is RuntimeNode) {
            current = current.parent
        }
        return current
    }
