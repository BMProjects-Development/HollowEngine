package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.bedrock.MolangDefaults
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.StaticGeometry
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * A model as it came out of a loader: the node hierarchy, its materials and its animation clips.
 *
 * One instance is shared by every entity showing this model; what differs per entity lives in the
 * runtime nodes of a [ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment].
 */
data class Model(
    val scene: Int,
    val scenes: List<Scene>,
    val materials: Set<Material>,
    val animations: List<AnimationClip> = emptyList(),
    val constraints: List<ModelPoseConstraint> = emptyList(),
    val textureContents: List<ModelTexture> = emptyList(),
    val molangDefaults: MolangDefaults = MolangDefaults.EMPTY,
) {
    /** Clips by name, which is how a layer names the animation it plays. */
    val animationsByName: Map<String, AnimationClip> by lazy { animations.associateBy { it.name } }

    /** Every node of every scene, flattened once. */
    val nodes: List<NodeDefinition> by lazy { walkNodes().toList() }

    /**
     * How much room each bone's geometry takes up, in the bone's own space; see [BoneGeometry].
     *
     * Measured here rather than on demand: a primitive hands its vertices to the driver the first time
     * it is drawn, and nothing can measure them afterwards.
     */
    val boneBounds: Map<Int, Pair<Vec3f, Vec3f>> = BoneGeometry.of(this)

    private var geometry: StaticGeometry? = null
    private var geometryBuilt = false

    /**
     * The unskinned primitives merged for drawing, null when there are none. Built the first time something draws
     * the model, on the render thread, and shared by all its instances.
     */
    val staticGeometry: StaticGeometry?
        get() {
            if (!geometryBuilt) {
                geometry = StaticGeometry.of(this)
                geometryBuilt = true
            }
            return geometry
        }

    fun walkNodes(): Sequence<NodeDefinition> {
        return sequence {
            suspend fun SequenceScope<NodeDefinition>.walk(node: NodeDefinition) {
                yield(node)
                node.children.forEach { walk(it) }
            }
            scenes.flatMap { it.nodes }.forEach { walk(it) }
        }
    }

    fun findNodeByIndex(index: Int): NodeDefinition? {
        return walkNodes().find { it.index == index }
    }

    fun node(id: Int) = findNodeByIndex(id) ?: error("Node $id not found")

    fun findNodeByName(name: String): NodeDefinition? {
        return walkNodes().find { it.name == name }
    }

    /** Releases the GPU buffers of the model; called by the manager when a model is replaced. */
    fun destroy() {
        textureContents.forEach(ModelTexture::close)
        walkNodes().mapNotNull { it.mesh }.flatMap { it.primitives }.forEach(Primitive::destroy)
        geometry?.destroy()
        geometry = null
    }

    companion object {
        val EMPTY = Model(0, listOf(), setOf(), listOf())
    }
}
