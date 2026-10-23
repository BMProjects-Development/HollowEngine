package ru.hollowhorizon.hollowengine.client.models.bbmodel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs

internal data class BBGeometryPart(val primitive: Primitive, val vertexKeys: List<String>)

internal class BBGeometry(
    private val file: BlockBenchModel,
    private val materials: List<Material>,
    private val untextured: Material,
) {
    fun convert(element: BBElement): List<BBGeometryPart> = when (element.type) {
        "cube" -> cube(element)
        "mesh" -> mesh(element)
        "group", "armature", "armature_bone", "null_object", "locator", "bounding_box" -> emptyList()
        else -> error("Unsupported Blockbench element type '${element.type}' (${element.name})")
    }

    private fun texture(face: BBFace): Int? {
        val ref = face.texture?.content ?: return null
        if (ref == "null") return null
        if (ref == "-1" || ref == "false") return -1
        val index = ref.toIntOrNull() ?: file.textures.indexOfFirst { it.uuid == ref || it.id == ref.removePrefix("#") }
        require(index in materials.indices) { "Unknown Blockbench face texture '$ref'" }
        return index
    }

    private fun cube(element: BBElement): List<BBGeometryPart> {
        val pivot = element.origin.bbVector()
        val from = requireNotNull(element.from) { "Cube ${element.uuid} has no from vector" }.bbVector() - pivot
        val to = requireNotNull(element.to) { "Cube ${element.uuid} has no to vector" }.bbVector() - pivot
        val x0 = from.x - element.inflate
        val x1 = to.x + element.inflate
        val y0 = from.y - element.inflate
        val y1 = to.y + element.inflate
        val z0 = from.z - element.inflate
        val z1 = to.z + element.inflate
        val corners = listOf(
            Vec3f(x0, y0, z0), Vec3f(x1, y0, z0), Vec3f(x1, y1, z0), Vec3f(x0, y1, z0),
            Vec3f(x0, y0, z1), Vec3f(x1, y0, z1), Vec3f(x1, y1, z1), Vec3f(x0, y1, z1),
        )
        val directions = linkedMapOf(
            "north" to listOf(1, 0, 3, 2), "south" to listOf(4, 5, 6, 7),
            "west" to listOf(0, 4, 7, 3), "east" to listOf(5, 1, 2, 6),
            "up" to listOf(7, 6, 2, 3), "down" to listOf(0, 1, 5, 4),
        )
        val box = if (element.boxUv ?: file.meta.boxUv) boxUv(element, to - from) else emptyMap()
        val builders = linkedMapOf<Int, Builder>()
        directions.forEach { (name, vertices) ->
            val face = element.faces[name] ?: return@forEach
            val texture = texture(face) ?: return@forEach
            val rect = box[name] ?: (face.uv as? JsonArray)?.map { it.jsonPrimitive.float }
                ?: error("Cube face $name of ${element.uuid} has no UV rectangle")
            require(rect.size == 4) { "Invalid cube UV rectangle: $rect" }
            val (u0, v0, u1, v1) = rect
            val uv = listOf(Vec2f(u0, v1), Vec2f(u1, v1), Vec2f(u1, v0), Vec2f(u0, v0))
            require(face.rotation % 90 == 0) { "Invalid cube UV rotation ${face.rotation}" }
            val shift = (face.rotation / 90).mod(4)
            val size = uvSize(texture)
            builders.getOrPut(texture) { Builder(material(texture)) }.face(
                vertices.map { corners[it] },
                List(4) { uv[(it + shift).mod(4)] / size },
                emptyList(),
            )
        }
        return builders.values.map(Builder::build)
    }

    private fun mesh(element: BBElement): List<BBGeometryPart> {
        val builders = linkedMapOf<Int, Builder>()
        val normals = if (element.shading == "smooth") smoothNormals(element) else emptyMap()
        element.faces.values.forEach { face ->
            val texture = texture(face) ?: return@forEach
            if (face.vertices.size < 3) return@forEach // Loose mesh edges carry no surface.
            require(face.vertices.size <= 4) { "Blockbench mesh faces must be triangles or quads" }
            val keys = sortQuad(face.vertices, element.vertices)
            val points = keys.map { requireNotNull(element.vertices[it]) { "Missing mesh vertex $it" }.bbVector() }
            val uvMap = face.uv as? JsonObject ?: error("Missing mesh UV map in ${element.uuid}")
            val size = uvSize(texture)
            val uvs = keys.map { key ->
                val uv = uvMap[key] as? JsonArray ?: error("Missing UV for vertex $key")
                require(uv.size == 2) { "Invalid mesh UV $uv" }
                Vec2f(uv[0].jsonPrimitive.float, uv[1].jsonPrimitive.float) / size
            }
            builders.getOrPut(texture) { Builder(material(texture)) }.face(points, uvs, keys,
                keys.mapNotNull { normals[it] }.takeIf { it.size == keys.size })
        }
        return builders.values.map(Builder::build)
    }

    private fun smoothNormals(element: BBElement): Map<String, Vec3f> {
        val sums = hashMapOf<String, MutableVec3f>()
        element.faces.values.filter { it.vertices.size >= 3 }.forEach { face ->
            val keys = sortQuad(face.vertices, element.vertices)
            val points = keys.map { element.vertices.getValue(it).bbVector() }
            val normal = faceNormal(points)
            keys.forEach { sums.getOrPut(it) { MutableVec3f() }.add(normal) }
        }
        return sums.mapValues { (_, normal) -> if (normal.sqrLength() > 0f) normal.normed() else Vec3f.Y_AXIS }
    }

    private fun faceNormal(points: List<Vec3f>): Vec3f {
        val normal = (points[1] - points[0]).cross(points[2] - points[0], MutableVec3f())
        return if (normal.sqrLength() > 0f) normal.normed() else Vec3f.Y_AXIS
    }

    private fun material(index: Int): Material = materials.getOrNull(index) ?: untextured

    private fun uvSize(index: Int): Vec2f {
        val texture = file.textures.getOrNull(index)
        return Vec2f(
            (texture?.uvWidth?.takeIf { it > 0 } ?: file.resolution.width).toFloat(),
            (texture?.uvHeight?.takeIf { it > 0 } ?: file.resolution.height).toFloat(),
        ).also { require(it.x > 0 && it.y > 0) { "Blockbench UV dimensions must be positive" } }
    }

    private fun boxUv(element: BBElement, size: Vec3f): Map<String, List<Float>> {
        val u = element.uvOffset[0]
        val v = element.uvOffset[1]
        val w = abs(size.x)
        val h = abs(size.y)
        val d = abs(size.z)
        val rects = mapOf(
            "east" to listOf(u, v + d, u + d, v + d + h),
            "north" to listOf(u + d, v + d, u + d + w, v + d + h),
            "west" to listOf(u + d + w, v + d, u + 2 * d + w, v + d + h),
            "south" to listOf(u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
            "up" to listOf(u + d + w, v + d, u + d, v),
            "down" to listOf(u + d + 2 * w, v, u + d + w, v + d),
        )
        if (!element.mirrorUv) return rects
        return rects.mapValues { (_, uv) -> listOf(uv[2], uv[1], uv[0], uv[3]) }.toMutableMap().apply {
            val east = getValue("east")
            put("east", getValue("west"))
            put("west", east)
        }
    }

    /** Project files can retain the editing order of a quad instead of its perimeter order. */
    private fun sortQuad(keys: List<String>, vertices: Map<String, List<Float>>): List<String> {
        if (keys.size != 4) return keys
        val p = keys.map { vertices.getValue(it).bbVector() }
        fun otherSide(a: Vec3f, b: Vec3f, top: Vec3f, check: Vec3f): Boolean {
            val line = b - a
            val normal = a + line * (((top - a) dot line) / (line dot line)) - top
            return ((check - b) dot normal) > 0f
        }
        return when {
            otherSide(p[1], p[2], p[0], p[3]) -> listOf(keys[2], keys[0], keys[1], keys[3])
            otherSide(p[0], p[1], p[2], p[3]) -> listOf(keys[0], keys[2], keys[1], keys[3])
            else -> keys
        }
    }

    private inner class Builder(private val material: Material) {
        private val positions = mutableListOf<Vec3f>()
        private val normals = mutableListOf<Vec3f>()
        private val uvs = mutableListOf<Vec2f>()
        private val indices = mutableListOf<Int>()
        private val vertexKeys = mutableListOf<String>()

        fun face(points: List<Vec3f>, uv: List<Vec2f>, keys: List<String>, vertexNormals: List<Vec3f>? = null) {
            val start = positions.size
            positions += points
            normals += vertexNormals ?: List(points.size) { faceNormal(points) }
            uvs += uv
            vertexKeys += keys
            for (i in 1 until points.lastIndex) indices += listOf(start, start + i, start + i + 1)
        }

        fun build(): BBGeometryPart {
            val points = positions.toTypedArray()
            val triangles = indices.toIntArray()
            return BBGeometryPart(
                Primitive(points, normals.toTypedArray(), uvs.toTypedArray(),
                    indices = triangles, material = material),
                vertexKeys,
            )
        }
    }
}
