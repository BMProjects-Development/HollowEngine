package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec4f

open class Geometry(id: Long, element: Element, name: String, doc: Document) : Object(id, element, name) {
    private val deformers by lazy {
        doc.getConnectionsByDestinationSequenced(id, "Deformer").filter { it.prop.isEmpty() }
            .mapNotNull { it.sourceObject }
    }
    val skin: Skin?
        get() {
            val skins = deformers.filterIsInstance<Skin>()
            if (skins.size > 1) domError("multiple skins attached to one geometry", element)
            return skins.singleOrNull()
        }
    val blendShapes: List<BlendShape> get() = deformers.filterIsInstance<BlendShape>()
}

class MeshGeometry(id: Long, element: Element, name: String, doc: Document) : Geometry(id, element, name, doc) {
    val vertices = ArrayList<Vec3f>()
    val normals = ArrayList<Vec3f>()
    val tangents = ArrayList<Vec3f>()
    val binormals = ArrayList<Vec3f>()
    val indices = ArrayList<Int>()

    /** One material slot per triangle. All attributes use polygon-corner order. */
    val materials = ArrayList<Int>()
    val uvs = Array(8) { ArrayList<Vec2f>() }
    val uvNames = Array(8) { "" }
    val colors = Array(8) { ArrayList<Vec4f>() }
    val controlPoints = ArrayList<Vec3f>()
    val vertexControlPoints = ArrayList<Int>()
    private val cornerPolygons = ArrayList<Int>()
    private val polygons = ArrayList<IntRange>()
    private val trianglePolygons = ArrayList<Int>()

    init {
        val scope = element.scope
        getRequiredElement(scope, "Vertices", element).parseVec3DataArray(controlPoints)
        val encoded = ArrayList<Int>()
        getRequiredElement(scope, "PolygonVertexIndex", element).parseIntsDataArray(encoded)
        var start = 0
        for ((corner, value) in encoded.withIndex()) {
            val point = if (value < 0) value.inv() else value
            if (point !in controlPoints.indices) domError("control point $point is out of range", element)
            vertices += controlPoints[point]
            vertexControlPoints += point
            cornerPolygons += polygons.size
            if (value < 0) {
                if (corner - start < 2) domError("polygon has fewer than three corners", element)
                polygons += start..corner
                val triangles = triangulatePolygon(vertices.subList(start, corner + 1))
                indices += triangles.map { it + start }
                repeat(triangles.size / 3) { trianglePolygons += polygons.lastIndex }
                start = corner + 1
            }
        }
        if (start != encoded.size) domError("unterminated polygon", element)
        readLayers(scope)
        if (materials.isEmpty()) repeat(trianglePolygons.size) { materials += -1 }
    }

    fun getTextureCoords(index: Int) = uvs.getOrNull(index) ?: arrayListOf()

    private fun readLayers(scope: Scope) {
        val selected = linkedSetOf<Pair<String, Int>>()
        for (layer in scope.getCollection("Layer")) {
            for (entry in layer.scope.getCollection("LayerElement")) {
                selected += getRequiredElement(entry.scope, "Type")[0].parseAsString to getRequiredElement(
                    entry.scope,
                    "TypedIndex"
                )[0].parseAsInt
            }
        }
        // Some exporters omit Layer references while still supplying the attribute arrays.
        if (selected.isEmpty()) {
            scope.elements.filterKeys { it.startsWith("LayerElement") }.forEach { (type, entries) ->
                entries.forEach { selected += type to it[0].parseAsInt }
            }
        }
        for ((type, index) in selected) {
            val source = scope.getCollection(type).firstOrNull { it[0].parseAsInt == index }
                ?: domError("missing $type layer $index", element)
            when (type) {
                "LayerElementUV" -> {
                    if (index !in uvs.indices) domError("UV channel $index is out of range", source)
                    uvNames[index] = source.scope["Name"]?.get(0)?.parseAsString.orEmpty()
                    resolve(uvs[index], source, "UV", "UVIndex")
                }

                "LayerElementColor" -> {
                    if (index !in colors.indices) domError("color channel $index is out of range", source)
                    resolve(colors[index], source, "Colors", "ColorIndex")
                }

                "LayerElementNormal" -> if (normals.isEmpty()) resolve(normals, source, "Normals", "NormalsIndex")
                "LayerElementTangent" -> if (tangents.isEmpty()) {
                    val name = if (source.scope["Tangents"] != null) "Tangents" else "Tangent"
                    resolve(tangents, source, name, name + "Index")
                }

                "LayerElementBinormal" -> if (binormals.isEmpty()) {
                    val name = if (source.scope["Binormals"] != null) "Binormals" else "Binormal"
                    resolve(binormals, source, name, name + "Index")
                }

                "LayerElementMaterial" -> if (materials.isEmpty()) readMaterials(source)
            }
        }
    }

    private inline fun <reified T : Any> resolve(out: ArrayList<T>, layer: Element, name: String, indexName: String) {
        val data = ArrayList<T>()
        getRequiredElement(layer.scope, name).parseVectorDataArray(data)
        val mapping = getRequiredElement(layer.scope, "MappingInformationType")[0].parseAsString
        val reference = getRequiredElement(layer.scope, "ReferenceInformationType")[0].parseAsString
        val lookup = ArrayList<Int>()
        when (reference) {
            "IndexToDirect", "Index" -> getRequiredElement(layer.scope, indexName).parseIntsDataArray(lookup)
            "Direct" -> Unit
            else -> domError("unsupported reference type $reference", layer)
        }
        for (corner in vertices.indices) {
            val mapped = mappedIndex(mapping, corner, layer)
            val direct = if (reference == "Direct") mapped else lookup.getOrNull(mapped)
                ?: domError("$name index array is too short", layer)
            out += data.getOrNull(direct) ?: domError("$name index $direct is out of range", layer)
        }
    }

    private fun mappedIndex(mapping: String, corner: Int, layer: Element): Int = when (mapping) {
        "ByVertice", "ByVertex", "ByControlPoint" -> vertexControlPoints[corner]
        "ByPolygonVertex" -> corner
        "ByPolygon" -> cornerPolygons[corner]
        "AllSame" -> 0
        else -> domError("unsupported mapping type $mapping", layer)
    }

    private fun readMaterials(layer: Element) {
        val slots = ArrayList<Int>()
        getRequiredElement(layer.scope, "Materials").parseIntsDataArray(slots)
        val mapping = getRequiredElement(layer.scope, "MappingInformationType")[0].parseAsString
        if (mapping == "NoMappingInformation") return
        if (mapping != "AllSame" && mapping != "ByPolygon") domError("unsupported material mapping $mapping", layer)
        for (polygon in trianglePolygons) {
            materials += slots.getOrNull(if (mapping == "AllSame") 0 else polygon)
                ?: domError("material array is too short", layer)
        }
    }
}
