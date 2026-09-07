package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF

abstract class Attachment(val parent: Attachment? = null) {
    val transform = TrsTransformF()
    val globalMatrix = MutableMat4f()

    open fun updateGlobalMatrix() {
        val localM = transform.matrixF
        if (parent != null) {
            globalMatrix.set(parent.globalMatrix)
            globalMatrix.mul(localM)
        } else {
            globalMatrix.set(localM)
        }
    }

    open fun collectCommands(pipeline: RenderPipeline) {
    }
}

open class RuntimeNode(
    val definition: NodeDefinition,
    parent: Attachment?,
    private val materialResolver: (Material) -> Material = { it },
) : Attachment(parent) {
    val name: String = definition.name ?: parent?.let { "Node_${definition.index}" } ?: "Root"
    var isVisible = true
        set(value) {
            field = value
            children.forEach { it.isVisible = value }
        }

    init {
        transform.set(definition.baseTransform)
    }

    val attachments = arrayListOf<Attachment>()

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

    val children = definition.children.map {
        RuntimeNode(it, this, materialResolver)
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

    fun resetPose() {
        transform.set(definition.baseTransform)
        baseMorphWeights.copyInto(morphWeights)
    }

    fun updateHierarchyMatrices() {
        updateGlobalMatrix()
        attachments.forEach(Attachment::updateGlobalMatrix)
        children.forEach(RuntimeNode::updateHierarchyMatrices)
    }
}

fun RuntimeNode.walk(): List<RuntimeNode> = buildList {
    add(this@walk)
    children.forEach {
        addAll(it.walk())
    }
}

val RuntimeNode.root: RuntimeNode
    get() {
        var current: RuntimeNode = this
        while (current.parent is RuntimeNode) {
            current = current.parent
        }
        return current
    }
