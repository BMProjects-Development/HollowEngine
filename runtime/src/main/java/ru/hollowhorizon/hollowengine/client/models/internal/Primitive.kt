package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.client.models.internal.rendering.PipelineRenderer
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.StaticGeometry
import ru.hollowhorizon.hollowengine.client.models.internal.utils.GeometryUtils
import ru.hollowhorizon.hollowengine.client.models.internal.v2.MeshAttachment
import ru.hollowhorizon.hollowengine.common.utils.math.*
import kotlin.math.max
import kotlin.math.min

class Primitive(
    var positions: Array<Vec3f>? = null,
    var normals: Array<Vec3f>? = null,
    var texCoords: Array<Vec2f>? = null,
    var midCoords: Array<Vec2f>? = null,
    var tangents: Array<Vec4f>? = null,
    var joints: Array<Vec4i>? = null,
    var jointWeights: Array<Vec4f>? = null,
    val indices: IntArray? = null,
    val material: Material,
    val morphTargets: List<Map<String, FloatArray>> = listOf(),
    var weights: FloatArray = FloatArray(morphTargets.size) { 0f },
    var additionalSkinWeights: Array<List<SkinInfluence>>? = null,
    retainSurface: Boolean = true,
) {
    val hasSkinning = joints != null && jointWeights != null
    val positionsCount: Int = (positions?.size ?: 0) * 3
    var jointCount = 0
    internal val texturedSurface = if (retainSurface && positions != null && texCoords != null) {
        TexturedMeshSurface(positions!!, texCoords!!, joints, jointWeights, additionalSkinWeights)
    } else null
    internal val supportsExtrusion: Boolean get() = texturedSurface != null && positionsCount > 0

    /**
     * Whether the vertices move on their own, by skinning or morph targets. Such a primitive is drawn by itself;
     * the others of a model are merged into its [StaticGeometry].
     */
    val isDynamic: Boolean get() = hasSkinning || morphTargets.isNotEmpty()

    val localBounds: Pair<Vec3f, Vec3f>? = computeBounds()

    private var renderer: PipelineRenderer? = null

    /** Fills in what the loader left out and drawing needs: normals, centers of the UV rectangles and tangents. */
    fun prepareGeometry() {
        if (normals == null && positions != null) {
            normals = GeometryUtils.recalculateNormals(indices, positions!!)
        }
        if (midCoords == null && texCoords != null) {
            midCoords = GeometryUtils.recalculateMidCoords(indices, texCoords!!)
        }
        if (tangents == null && positions != null && texCoords != null && normals != null) {
            tangents = GeometryUtils.recalculateTangents(indices, positions!!, texCoords!!, normals!!)
        }
    }

    fun setupPipeline(
        pipeline: RenderPipeline,
        instance: MeshAttachment,
    ) {
        val renderer = standaloneRenderer()
        if (isDynamic) pipeline.addSkinnable { renderer.deform(instance) }
        pipeline.addVAORenderable { renderer.draw(this, instance) }
    }

    internal fun standaloneRenderer(): PipelineRenderer {
        renderer?.let { return it }
        prepareGeometry()
        val next = PipelineRenderer(this)
        try {
            next.init()
        } catch (error: Exception) {
            next.destroy()
            throw error
        }
        renderer = next
        releaseCpu()
        return next
    }

    fun destroy() {
        renderer?.destroy()
    }

    /** Drops the vertex data once the GPU has its own copy. */
    fun releaseCpu() {
        positions = null
        normals = null
        texCoords = null
        midCoords = null
        tangents = null
        joints = null
        jointWeights = null
        additionalSkinWeights = null
    }

    private fun computeBounds(): Pair<Vec3f, Vec3f>? {
        val pos = positions ?: return null
        if (pos.isEmpty()) return null

        var min = MutableVec3f(Float.POSITIVE_INFINITY)
        var max = MutableVec3f(Float.NEGATIVE_INFINITY)

        pos.forEach {
            min.x = min(min.x, it.x); min.y = min(min.y, it.y); min.z = min(min.z, it.z)
            max.x = max(max.x, it.x); max.y = max(max.y, it.y); max.z = max(max.z, it.z)
        }
        return Vec3f(min) to Vec3f(max)
    }
}
