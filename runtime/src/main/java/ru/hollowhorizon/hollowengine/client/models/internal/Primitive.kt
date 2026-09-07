package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.common.utils.math.*
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.BatchingRenderer
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.MeshRenderer
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.PipelineRenderer
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.client.models.internal.utils.GeometryUtils
import ru.hollowhorizon.hollowengine.client.models.internal.v2.MeshAttachment
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
) {
    enum class StaticRenderPath {
        PIPELINE,
        BATCHING,
    }

    val hasSkinning = joints != null && jointWeights != null
    val positionsCount: Int get() = (positions?.size ?: 0) * 3
    var jointCount = 0

    var staticRenderPath: StaticRenderPath = StaticRenderPath.PIPELINE
        private set

    val useBatching get() = !hasSkinning && morphTargets.isEmpty() && staticRenderPath == StaticRenderPath.BATCHING

    val localBounds: Pair<Vec3f, Vec3f>? = computeBounds()

    private var renderer: MeshRenderer? = null

    private var isLoaded = false

    fun init() {
        if (isLoaded) return
        isLoaded = true

        if (!useBatching) {
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

        renderer = if (useBatching) {
            BatchingRenderer(this)
        } else {
            PipelineRenderer(this)
        }

        renderer?.init()

        if (!useBatching) releaseCpu()
    }

    fun setStaticRenderPath(path: StaticRenderPath) {
        val desiredPath = if (hasSkinning || morphTargets.isNotEmpty()) StaticRenderPath.PIPELINE else path

        if (staticRenderPath == desiredPath) return
        if (isLoaded) return

        staticRenderPath = desiredPath
    }

    fun setupPipeline(
        pipeline: RenderPipeline,
        instance: MeshAttachment,
    ) {
        init()
        renderer?.setupPipeline(pipeline, instance)
    }

    fun destroy() {
        renderer?.destroy()
    }

    private fun releaseCpu() {
        if (!useBatching) {
            positions = null
            normals = null
            texCoords = null
            midCoords = null
            tangents = null
            joints = null
            jointWeights = null
        }
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

    fun estimatedCubeCount(): Int {
        val vertexEstimate = (positions?.size ?: 0) / VERTICES_PER_CUBE
        val indexEstimate = (indices?.size ?: 0) / INDICES_PER_CUBE
        return max(vertexEstimate, indexEstimate)
    }

    fun minInstancedBatchSize(isTranslucent: Boolean): Int {
        val cubes = estimatedCubeCount()
        val base = when {
            cubes <= 2 -> 2
            cubes <= 6 -> 3
            cubes <= 16 -> 4
            else -> 5
        }
        return base + if (isTranslucent) 1 else 0
    }

    fun prefersInstancing(instanceCount: Int, isTranslucent: Boolean): Boolean {
        if (hasSkinning || morphTargets.isNotEmpty() || useBatching) return false
        return instanceCount >= minInstancedBatchSize(isTranslucent)
    }

    companion object {
        private const val VERTICES_PER_CUBE = 24
        private const val INDICES_PER_CUBE = 36
    }
}
