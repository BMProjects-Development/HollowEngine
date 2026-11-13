package ru.hollowhorizon.hollowengine.client.models.internal.extrusion

import ru.hollowhorizon.hollowengine.client.models.gltf.GltfMesh
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.SkinInfluence
import ru.hollowhorizon.hollowengine.client.models.internal.TexturedMeshSurface
import ru.hollowhorizon.hollowengine.client.models.internal.extrusion.ExtrusionMesh.Face
import ru.hollowhorizon.hollowengine.client.models.internal.extrusion.ExtrusionMesh.Vertex
import ru.hollowhorizon.hollowengine.common.utils.math.*

/** Remaps each new vertex to the source triangle for skin weights and morph deltas. */
internal class ExtrusionVertices(
    private val source: Primitive,
    private val surface: TexturedMeshSurface,
    private val extendsOutward: Boolean,
) {
    private class Sample(val face: Face, val weights: Vec3f)

    private val samples = ArrayList<Sample>()
    private val positions = ArrayList<Vec3f>()
    private val normals = ArrayList<Vec3f>()
    private val uvs = ArrayList<Vec2f>()
    private val indices = ArrayList<Int>()

    fun cap(polygon: List<Vertex>, uv: Vec2f, outer: Boolean) {
        val first = positions.size
        val normal = polygon.first().face.normal * if (outer) 1f else -1f
        polygon.forEach { add(it, it.position(outer), uv, normal) }
        for (index in 1 until polygon.lastIndex) {
            indices += first
            indices += first + if (outer) index else index + 1
            indices += first + if (outer) index + 1 else index
        }
    }

    fun wall(start: Vertex, end: Vertex, uv: Vec2f) {
        val first = positions.size
        val endOuter = end.position(outer = true)
        val startOuter = start.position(outer = true)
        val startInner = start.position(outer = false)
        val endInner = end.position(outer = false)
        val normal = (startInner - endOuter).cross(endInner - startOuter, MutableVec3f()).norm()
        add(end, endOuter, uv, normal)
        add(start, startOuter, uv, normal)
        add(start, startInner, uv, normal)
        add(end, endInner, uv, normal)
        quadIndices(first)
    }

    fun quad(positions: List<Vec3f>, uv: Vec2f, normal: Vec3f, sampleAt: (Vec3f) -> Pair<Face, Vec3f>) {
        val first = this.positions.size
        positions.forEach { position ->
            val (face, weights) = sampleAt(position)
            add(face, weights, position, uv, normal)
        }
        quadIndices(first)
    }

    private fun quadIndices(first: Int) {
        indices.addAll(listOf(first, first + 1, first + 2, first, first + 2, first + 3))
    }

    /** For an inward extrusion the original surface is the outside of the shell. */
    private fun Vertex.position(outer: Boolean): Vec3f = if (outer == extendsOutward) displaced else base

    private fun add(vertex: Vertex, position: Vec3f, uv: Vec2f, normal: Vec3f) {
        add(vertex.face, vertex.weights, position, uv, normal)
    }

    private fun add(face: Face, weights: Vec3f, position: Vec3f, uv: Vec2f, normal: Vec3f) {
        samples += Sample(face, weights)
        positions += position
        normals += normal
        uvs += uv
    }

    fun build(): Primitive {
        val skin = if (source.hasSkinning) samples.map(::influences) else null
        return Primitive(
            positions = positions.toTypedArray(),
            normals = normals.toTypedArray(),
            texCoords = uvs.toTypedArray(),
            indices = indices.toIntArray(),
            material = source.material,
            joints = skin?.map { weights ->
                Vec4i(weights.joint(0), weights.joint(1), weights.joint(2), weights.joint(3))
            }?.toTypedArray(),
            jointWeights = skin?.map { weights ->
                Vec4f(weights.weight(0), weights.weight(1), weights.weight(2), weights.weight(3))
            }?.toTypedArray(),
            additionalSkinWeights = skin?.takeIf { it.any { weights -> weights.size > 4 } }?.map { it.drop(4) }
                ?.toTypedArray(),
            morphTargets = source.morphTargets.map { target ->
                val deltas = target[GltfMesh.Primitive.ATTRIBUTE_POSITION] ?: return@map emptyMap()
                val remapped = FloatArray(samples.size * 3)
                samples.forEachIndexed { index, vertex ->
                    val delta = vertex.face.interpolate(vertex.weights) { at ->
                        val offset = at * 3
                        Vec3f(deltas[offset], deltas[offset + 1], deltas[offset + 2])
                    }
                    remapped[index * 3] = delta.x
                    remapped[index * 3 + 1] = delta.y
                    remapped[index * 3 + 2] = delta.z
                }
                mapOf(GltfMesh.Primitive.ATTRIBUTE_POSITION to remapped)
            },
            retainSurface = false,
        ).also { it.jointCount = source.jointCount }
    }

    private fun influences(vertex: Sample): List<SkinInfluence> {
        val joints = checkNotNull(surface.joints)
        val weights = checkNotNull(surface.weights)
        val blended = LinkedHashMap<Int, Float>()
        fun add(joint: Int, weight: Float) {
            if (weight > 0f) blended.merge(joint, weight, Float::plus)
        }

        val (a, b, c) = vertex.weights
        vertex.face.corners.forEachIndexed { corner, index ->
            val factor = when (corner) {
                0 -> a
                1 -> b
                else -> c
            }
            val ids = joints[index]
            val strengths = weights[index]
            add(ids.x, strengths.x * factor)
            add(ids.y, strengths.y * factor)
            add(ids.z, strengths.z * factor)
            add(ids.w, strengths.w * factor)
            surface.additionalWeights?.get(index)?.forEach { add(it.joint, it.weight * factor) }
        }
        return blended.entries.sortedByDescending { it.value }.map { SkinInfluence(it.key, it.value) }
    }

    private fun List<SkinInfluence>.joint(index: Int): Int = getOrNull(index)?.joint ?: 0
    private fun List<SkinInfluence>.weight(index: Int): Float = getOrNull(index)?.weight ?: 0f
}
