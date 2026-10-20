package ru.hollowhorizon.hollowengine.client.models.internal.utils

import ru.hollowhorizon.hollowengine.common.utils.math.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object GeometryUtils {
    fun recalculateNormals(indices: IntArray?, positions: Array<Vec3f>): Array<Vec3f> {
        val count = positions.size
        val accumNormals = Array(count) { MutableVec3f() }
        val getIndex = { i: Int -> indices?.get(i) ?: i }
        val loops = indices?.size ?: count

        val p1 = MutableVec3f()
        val p2 = MutableVec3f()
        val p3 = MutableVec3f()
        val e1 = MutableVec3f()
        val e2 = MutableVec3f()
        val nrm = MutableVec3f()

        for (i in 0 until loops step 3) {
            val i1 = getIndex(i)
            val i2 = getIndex(i + 1)
            val i3 = getIndex(i + 2)
            if (i1 >= count || i2 >= count || i3 >= count) continue

            p1.set(positions[i1]); p2.set(positions[i2]); p3.set(positions[i3])
            p2.subtract(p1, e1).norm()
            p3.subtract(p1, e2).norm()
            e1.cross(e2, nrm).norm()

            // Area weighted normal
            p2.subtract(p1, e1); p3.subtract(p1, e2)
            val area = 0.5f * e1.cross(e2, MutableVec3f()).length()
            nrm.mul(area)

            if (!nrm.x.isNaN() && !nrm.y.isNaN() && !nrm.z.isNaN()) {
                accumNormals[i1].add(nrm); accumNormals[i2].add(nrm); accumNormals[i3].add(nrm)
            }
        }

        return Array(count) { i ->
            val n = accumNormals[i]
            if (n.sqrLength() > 1e-6f) n.norm() else n.set(0f, 1f, 0f)
            Vec3f(n.x, n.y, n.z)
        }
    }

    private const val TANGENT_EPSILON = 1e-8f

    fun recalculateTangents(
        indices: IntArray?,
        positions: Array<Vec3f>,
        uvs: Array<Vec2f>,
        normals: Array<Vec3f>,
    ): Array<Vec4f> {
        require(positions.size == uvs.size) {
            "Positions and UV arrays must have equal sizes: ${positions.size} != ${uvs.size}"
        }
        require(positions.size == normals.size) {
            "Positions and normals must have equal sizes: ${positions.size} != ${normals.size}"
        }

        val vertexCount = positions.size
        if (vertexCount == 0) return emptyArray()

        val elementCount = indices?.size ?: vertexCount
        require(elementCount % 3 == 0) {
            "Triangle element count must be divisible by 3: $elementCount"
        }

        val accumulatedTangents = Array(vertexCount) { MutableVec3f() }
        val accumulatedBitangents = Array(vertexCount) { MutableVec3f() }

        fun indexAt(element: Int): Int = indices?.get(element) ?: element

        fun addScaled(
            destination: MutableVec3f,
            x: Float,
            y: Float,
            z: Float,
            scale: Float,
        ) {
            destination.x += x * scale
            destination.y += y * scale
            destination.z += z * scale
        }

        for (element in 0 until elementCount step 3) {
            val i0 = indexAt(element)
            val i1 = indexAt(element + 1)
            val i2 = indexAt(element + 2)

            if (
                i0 !in 0 until vertexCount ||
                i1 !in 0 until vertexCount ||
                i2 !in 0 until vertexCount
            ) {
                continue
            }

            val p0 = positions[i0]
            val p1 = positions[i1]
            val p2 = positions[i2]

            val uv0 = uvs[i0]
            val uv1 = uvs[i1]
            val uv2 = uvs[i2]

            val e1x = p1.x - p0.x
            val e1y = p1.y - p0.y
            val e1z = p1.z - p0.z

            val e2x = p2.x - p0.x
            val e2y = p2.y - p0.y
            val e2z = p2.z - p0.z

            val du1 = uv1.x - uv0.x
            val dv1 = uv1.y - uv0.y
            val du2 = uv2.x - uv0.x
            val dv2 = uv2.y - uv0.y

            val determinant = du1 * dv2 - du2 * dv1
            if (!determinant.isFinite() || abs(determinant) <= TANGENT_EPSILON) {
                continue
            }

            val faceX = e1y * e2z - e1z * e2y
            val faceY = e1z * e2x - e1x * e2z
            val faceZ = e1x * e2y - e1y * e2x

            val areaWeight = sqrt(
                faceX * faceX +
                        faceY * faceY +
                        faceZ * faceZ
            )

            if (!areaWeight.isFinite() || areaWeight <= TANGENT_EPSILON) {
                continue
            }

            val inverseDeterminant = 1f / determinant

            val tangentX = (dv2 * e1x - dv1 * e2x) * inverseDeterminant
            val tangentY = (dv2 * e1y - dv1 * e2y) * inverseDeterminant
            val tangentZ = (dv2 * e1z - dv1 * e2z) * inverseDeterminant

            val bitangentX = (du1 * e2x - du2 * e1x) * inverseDeterminant
            val bitangentY = (du1 * e2y - du2 * e1y) * inverseDeterminant
            val bitangentZ = (du1 * e2z - du2 * e1z) * inverseDeterminant

            if (
                !tangentX.isFinite() ||
                !tangentY.isFinite() ||
                !tangentZ.isFinite() ||
                !bitangentX.isFinite() ||
                !bitangentY.isFinite() ||
                !bitangentZ.isFinite()
            ) {
                continue
            }

            addScaled(accumulatedTangents[i0], tangentX, tangentY, tangentZ, areaWeight)
            addScaled(accumulatedTangents[i1], tangentX, tangentY, tangentZ, areaWeight)
            addScaled(accumulatedTangents[i2], tangentX, tangentY, tangentZ, areaWeight)

            addScaled(accumulatedBitangents[i0], bitangentX, bitangentY, bitangentZ, areaWeight)
            addScaled(accumulatedBitangents[i1], bitangentX, bitangentY, bitangentZ, areaWeight)
            addScaled(accumulatedBitangents[i2], bitangentX, bitangentY, bitangentZ, areaWeight)
        }

        return Array(vertexCount) { index ->
            val sourceNormal = normals[index]

            var nx = sourceNormal.x
            var ny = sourceNormal.y
            var nz = sourceNormal.z

            var normalLength = sqrt(nx * nx + ny * ny + nz * nz)
            if (!normalLength.isFinite() || normalLength <= TANGENT_EPSILON) {
                nx = 0f
                ny = 1f
                nz = 0f
                normalLength = 1f
            }

            nx /= normalLength
            ny /= normalLength
            nz /= normalLength

            val accumulatedTangent = accumulatedTangents[index]

            val normalDotTangent =
                nx * accumulatedTangent.x +
                        ny * accumulatedTangent.y +
                        nz * accumulatedTangent.z

            var tx = accumulatedTangent.x - nx * normalDotTangent
            var ty = accumulatedTangent.y - ny * normalDotTangent
            var tz = accumulatedTangent.z - nz * normalDotTangent

            var tangentLength = sqrt(tx * tx + ty * ty + tz * tz)

            if (!tangentLength.isFinite() || tangentLength <= TANGENT_EPSILON) {
                if (abs(nx) < 0.9f) {
                    tx = 0f
                    ty = -nz
                    tz = ny
                } else {
                    tx = nz
                    ty = 0f
                    tz = -nx
                }

                tangentLength = sqrt(tx * tx + ty * ty + tz * tz)
            }

            tx /= tangentLength
            ty /= tangentLength
            tz /= tangentLength

            val crossX = ny * tz - nz * ty
            val crossY = nz * tx - nx * tz
            val crossZ = nx * ty - ny * tx

            val accumulatedBitangent = accumulatedBitangents[index]
            val handednessDot =
                crossX * accumulatedBitangent.x +
                        crossY * accumulatedBitangent.y +
                        crossZ * accumulatedBitangent.z

            val handedness = if (handednessDot < 0f) -1f else 1f

            Vec4f(tx, ty, tz, handedness)
        }
    }

    /**
     * Center of the UV rectangle around each vertex: the union of the UV bounds of the triangles that use it.
     */
    fun recalculateMidCoords(indices: IntArray?, uvs: Array<Vec2f>): Array<Vec2f> {
        val count = uvs.size
        val minU = FloatArray(count) { uvs[it].x }
        val minV = FloatArray(count) { uvs[it].y }
        val maxU = FloatArray(count) { uvs[it].x }
        val maxV = FloatArray(count) { uvs[it].y }
        val getIndex = { i: Int -> indices?.get(i) ?: i }
        val loops = indices?.size ?: count

        for (i in 0 until loops - 2 step 3) {
            val i1 = getIndex(i)
            val i2 = getIndex(i + 1)
            val i3 = getIndex(i + 2)
            if (i1 >= count || i2 >= count || i3 >= count) continue

            val triangleMinU = min(uvs[i1].x, min(uvs[i2].x, uvs[i3].x))
            val triangleMinV = min(uvs[i1].y, min(uvs[i2].y, uvs[i3].y))
            val triangleMaxU = max(uvs[i1].x, max(uvs[i2].x, uvs[i3].x))
            val triangleMaxV = max(uvs[i1].y, max(uvs[i2].y, uvs[i3].y))

            for (index in intArrayOf(i1, i2, i3)) {
                minU[index] = min(minU[index], triangleMinU)
                minV[index] = min(minV[index], triangleMinV)
                maxU[index] = max(maxU[index], triangleMaxU)
                maxV[index] = max(maxV[index], triangleMaxV)
            }
        }

        return Array(count) { i -> Vec2f((minU[i] + maxU[i]) * 0.5f, (minV[i] + maxV[i]) * 0.5f) }
    }
}
