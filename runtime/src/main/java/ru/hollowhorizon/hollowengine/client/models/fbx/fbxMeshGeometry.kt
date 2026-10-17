package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec4f

open class Geometry(id: Long, element: Element, name: String, doc: Document) : Object(id, element, name) {

    /** Get the Skin attached to this geometry or NULL */
    var skin: Skin? = null

    /** Get the BlendShape attached to this geometry or NULL */
    var blendShape: BlendShape? = null

    init {
        val conns = doc.getConnectionsByDestinationSequenced(id, "Deformer")
        for (con in conns) {
            val sk = processSimpleConnection<Skin>(con, false, "Skin -> Geometry", element)
            if (sk != null) {
                skin = sk
                continue
            }
            val bs = processSimpleConnection<BlendShape>(con, false, "BlendShape -> Geometry", element)
            if (bs != null) {
                blendShape = bs
                continue
            }
        }
    }
}


const val AI_MAX_NUMBER_OF_TEXTURECOORDS = 0x8
const val AI_MAX_NUMBER_OF_COLOR_SETS = 0x8

/**
 *  DOM class for FBX geometry of type "Mesh"
 */
class MeshGeometry(id: Long, element: Element, name: String, doc: Document) : Geometry(id, element, name, doc) {
    val materials = ArrayList<Int>()
    val vertices = ArrayList<Vec3f>()
    val tangents = ArrayList<Vec3f>()
    val binormals = ArrayList<Vec3f>()
    val normals = ArrayList<Vec3f>()

    val indices = ArrayList<Int>()

    val uvNames = Array(AI_MAX_NUMBER_OF_TEXTURECOORDS) { "" }
    val uvs = Array(AI_MAX_NUMBER_OF_TEXTURECOORDS) { ArrayList<Vec2f>() }
    val colors = Array(AI_MAX_NUMBER_OF_COLOR_SETS) { ArrayList<Vec4f>() }


    init {
        val sc = element.compound ?: domError("failed to read Geometry object (class: Mesh), no data scope found")

        val vertices = getRequiredElement(sc, "Vertices", element)
        val polygonVertexIndex = getRequiredElement(sc, "PolygonVertexIndex", element)

        val layer = sc.getCollection("Layer")

        vertices.parseVec3DataArray(this.vertices)

        assert(this.vertices.isNotEmpty()) { "Encountered mesh with no vertices" }

        polygonVertexIndex.parseIntsDataArray(this.indices)
        var step = -1
        indices.forEachIndexed { index, i ->
            indices[index] = if(i < 0) -i - 1 else i
            if(i < 0 && step == -1) step = index+1
        }

        layer.forEach { readLayer(it.scope) }

        if(step == 4) triangulateIndexes()
    }

    /** Get a UV coordinate slot, returns an empty array if
     *  the requested slot does not exist. */
    fun getTextureCoords(index: Int) = if (index >= AI_MAX_NUMBER_OF_TEXTURECOORDS) arrayListOf() else uvs[index]

    /** Get a UV coordinate slot, returns an empty array if the requested slot does not exist. */
    fun getTextureCoordChannelName(index: Int) = if (index >= AI_MAX_NUMBER_OF_TEXTURECOORDS) "" else uvNames[index]

    /** Get a vertex color coordinate slot, returns an empty array if the requested slot does not exist. */
    fun getVertexColors(index: Int) = if (index >= AI_MAX_NUMBER_OF_COLOR_SETS) arrayListOf() else colors[index]

    fun triangulateIndexes() {
        data class Vertex(val position: Vec3f, val normal: Vec3f, val uv: Vec2f)

        require(indices.size % 4 == 0) { "Quad index list size must be a multiple of 4" }

        val quadUvs = uvs[0]

        val outVertices = mutableListOf<Vertex>()
        val outIndices = mutableListOf<Int>()

        for (i in indices.indices step 4) {
            val i0 = indices[i]
            val i1 = indices[i + 1]
            val i2 = indices[i + 2]
            val i3 = indices[i + 3]

            val normal = normals[i / 4]

            val uv0 = quadUvs[i]
            val uv1 = quadUvs[i + 1]
            val uv2 = quadUvs[i + 2]
            val uv3 = quadUvs[i + 3]

            // Первый треугольник (i0, i1, i2)
            val base = outVertices.size
            outVertices += Vertex(vertices[i0], normal, uv0)
            outVertices += Vertex(vertices[i1], normal, uv1)
            outVertices += Vertex(vertices[i2], normal, uv2)
            outIndices += listOf(base, base + 1, base + 2)

            // Второй треугольник (i2, i3, i0)
            val base2 = outVertices.size
            outVertices += Vertex(vertices[i2], normal, uv2)
            outVertices += Vertex(vertices[i3], normal, uv3)
            outVertices += Vertex(vertices[i0], normal, uv0)
            outIndices += listOf(base2, base2 + 1, base2 + 2)
        }

        indices.clear()
        indices.addAll(outIndices)

        vertices.clear()
        vertices.addAll(outVertices.map { it.position })
        normals.clear()
        normals.addAll(outVertices.map { it.normal })
        uvs[0].clear()
        uvs[0].addAll(outVertices.map { it.uv })
    }

    fun readLayer(layer: Scope) {
        val layerElement = layer.getCollection("LayerElement")
        for (eit in layerElement)
            readLayerElement(eit.scope)
    }

    fun readLayerElement(layerElement: Scope) {
        val type = getRequiredElement(layerElement, "Type")
        val typedIndex = getRequiredElement(layerElement, "TypedIndex")

        val type_ = type[0].parseAsString
        val typedIndex_ = typedIndex[0].parseAsInt

        val top = element.scope
        val candidates = top.getCollection(type_)

        for (it in candidates) {
            val index = it[0].parseAsInt
            if (index == typedIndex_) {
                readVertexData(type_, typedIndex_, it.scope)
                return
            }
        }

        HollowEngine.LOGGER.error("failed to resolve vertex layer element: $type, index: $typedIndex")
    }

    fun readVertexData(type: String, index: Int, source: Scope) {

        val mappingInformationType = getRequiredElement(source, "MappingInformationType")[0].parseAsString

        val referenceInformationType = getRequiredElement(source, "ReferenceInformationType")[0].parseAsString

        when (type) {
            "LayerElementUV" -> {
                if (index >= AI_MAX_NUMBER_OF_TEXTURECOORDS) {
                    HollowEngine.LOGGER.error("ignoring UV layer, maximum number of UV channels exceeded: $index (limit is $AI_MAX_NUMBER_OF_TEXTURECOORDS)")
                    return
                }
                source["Name"]?.let { uvNames[index] = it[0].parseAsString }

                readVertexDataUV(uvs[index], source, mappingInformationType, referenceInformationType)
            }

            "LayerElementMaterial" -> {
                if (materials.isNotEmpty()) {
                    HollowEngine.LOGGER.error("ignoring additional material layer")
                    return
                }
                val tempMaterials = ArrayList<Int>()

                readVertexDataMaterials(tempMaterials, source, mappingInformationType, referenceInformationType)

                /*  sometimes, there will be only negative entries. Drop the material layer in such a case (I guess it
                    means a default material should be used). This is what the converter would do anyway, and it avoids
                    losing the material if there are more material layers coming of which at least one contains actual
                    data (did observe that with one test file). */
                if (tempMaterials.all { it < 0 }) {
                    HollowEngine.LOGGER.warn("ignoring dummy material layer (all entries -1)")
                    return
                }
                materials.clear()
                materials += tempMaterials
            }

            "LayerElementNormal" -> {
                if (normals.isNotEmpty()) {
                    HollowEngine.LOGGER.error("ignoring additional normal layer")
                    return
                }
                readVertexDataNormals(normals, source, mappingInformationType, referenceInformationType)
            }

            "LayerElementTangent" -> {
                if (tangents.isNotEmpty()) {
                    HollowEngine.LOGGER.error("ignoring additional tangent layer")
                    return
                }

                readVertexDataTangents(tangents, source, mappingInformationType, referenceInformationType)
            }

            "LayerElementBinormal" -> {
                if (binormals.isNotEmpty()) {
                    HollowEngine.LOGGER.error("ignoring additional binormal layer")
                    return
                }

                readVertexDataBinormals(binormals, source, mappingInformationType, referenceInformationType)
            }

            "LayerElementColor" -> {
                if (index >= AI_MAX_NUMBER_OF_COLOR_SETS) {
                    HollowEngine.LOGGER.error("ignoring vertex color layer, maximum number of color sets exceeded: $index (limit is $AI_MAX_NUMBER_OF_COLOR_SETS)")
                    return
                }

                readVertexDataColors(colors[index], source, mappingInformationType, referenceInformationType)
            }
        }
    }

    fun readVertexDataUV(
        uvOut: ArrayList<Vec2f>,
        source: Scope,
        mappingInformationType: String,
        referenceInformationType: String,
    ) =
        resolveVertexDataArray(
            uvOut, source, mappingInformationType, referenceInformationType, "UV",
            "UVIndex", vertices.size
        )

    fun readVertexDataNormals(
        normalsOut: ArrayList<Vec3f>,
        source: Scope,
        mappingInformationType: String,
        referenceInformationType: String,
    ) =
        resolveVertexDataArray(
            normalsOut, source, mappingInformationType, referenceInformationType, "Normals",
            "NormalsIndex", vertices.size
        )

    fun readVertexDataColors(
        colorsOut: ArrayList<Vec4f>,
        source: Scope,
        mappingInformationType: String,
        referenceInformationType: String,
    ) =
        resolveVertexDataArray(
            colorsOut, source, mappingInformationType, referenceInformationType, "Colors",
            "ColorIndex", vertices.size
        )

    fun readVertexDataTangents(
        tangentsOut: ArrayList<Vec3f>,
        source: Scope,
        mappingInformationType: String,
        referenceInformationType: String,
    ) {
        val any = source.elements["Tangents"]!!.isNotEmpty()
        resolveVertexDataArray(
            tangentsOut, source, mappingInformationType, referenceInformationType, if (any) "Tangents" else "Tangent",
            if (any) "TangentsIndex" else "TangentIndex", vertices.size
        )
    }

    fun readVertexDataBinormals(
        binormalsOut: ArrayList<Vec3f>,
        source: Scope,
        mappingInformationType: String,
        referenceInformationType: String,
    ) {
        val any = source.elements["Binormals"]!!.isNotEmpty()
        resolveVertexDataArray(
            binormalsOut,
            source,
            mappingInformationType,
            referenceInformationType,
            if (any) "Binormals" else "Binormal",
            if (any) "BinormalsIndex" else "BinormalIndex",
            vertices.size
        )
    }

    fun readVertexDataMaterials(
        materialsOut: ArrayList<Int>,
        source: Scope,
        mappingInformationType: String,
        referenceInformationType: String,
    ) {

        /*  materials are handled separately. First of all, they are assigned per-face and not per polyvert. Secondly,
            ReferenceInformationType=IndexToDirect has a slightly different meaning for materials. */
        getRequiredElement(source, "Materials").parseIntsDataArray(materialsOut)

        if (mappingInformationType == "AllSame") {
            // easy - same material for all faces
            if (materialsOut.isEmpty()) {
                HollowEngine.LOGGER.error("expected material index, ignoring")
                return
            } else if (materialsOut.size > 1) {
                HollowEngine.LOGGER.warn("expected only a single material index, ignoring all except the first one")
                materialsOut.clear()
            }
            for (i in vertices.indices)
                materials += materialsOut[0]
        } else
            HollowEngine.LOGGER.error("ignoring material assignments, access type not implemented: $mappingInformationType, $referenceInformationType")
    }

    /** Lengthy utility function to read and resolve a FBX vertex data array - that is, the output is in polygon vertex
     *  order. This logic is used for reading normals, UVs, colors, tangents .. */
    inline fun <reified T : Any> resolveVertexDataArray(
        dataOut: ArrayList<T>, source: Scope, mappingInformationType: String,
        referenceInformationType: String, dataElementName: String,
        indexDataElementName: String, vertexCount: Int,
    ) {
        val tempData = ArrayList<T>()
        getRequiredElement(source, dataElementName).parseVectorDataArray(tempData)
        dataOut.addAll(tempData)
    }
}