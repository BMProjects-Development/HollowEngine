package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.models.ModelNodeSource

open class NodeDefinition(
    override val index: Int,
    override val name: String? = null,
    val children: MutableList<NodeDefinition>,
    val transform: TrsTransformF,
    val mesh: Mesh? = null,
    val skin: Skin? = null,
    val visible: Boolean = true,
) : ModelNodeSource {
    /** Runtime transform introduced by a loader, rather than an authored node. */
    var isSynthetic: Boolean = false
    var parent: NodeDefinition? = null
    val root: NodeDefinition by lazy { parent?.root ?: this }
    override val path: String get() = parent?.let { it.name + "/" + name } ?: name ?: "Unnamed Bone"
    override val hasMesh: Boolean get() = mesh != null
    override val skinJoints: List<Int> get() = skin?.jointsIds.orEmpty()

    val baseTransform = TrsTransformF().apply {
        translate(transform.translation)
        rotate(transform.rotation)
        scale(transform.scale)
    }
    override val restTransform: TrsTransformF get() = baseTransform


    val localMatrix get() = transform.matrixF
    val globalMatrix = MutableMat4f()

    fun allBones(): Set<NodeDefinition> = buildSet {
        add(this@NodeDefinition)
        addAll(children.flatMap { it.allBones() })
    }

    override fun toString(): String {
        return "Node $name [Mesh: $mesh, Skin: $skin]"
    }
}
