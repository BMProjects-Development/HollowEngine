package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

open class Deformer(id: Long, element: Element, doc: Document, name: String) : Object(id, element, name) {
    val props = getPropertyTable(doc, "Deformer.Fbx" + element[2].parseAsString, element, element.compound, true)
}

class Cluster(id: Long, element: Element, doc: Document, name: String) : Deformer(id, element, doc, name) {
    val weights = ArrayList<Float>()
    val indices = ArrayList<Int>()

    val linkMode = element.scope["Mode"]?.get(0)?.parseAsString ?: "Normalize"
    val meshToJoint: Mat4f
    val bindToWorld: Mat4f

    var node: Model? = null

    init {
        val sc = element.scope

        if (linkMode !in setOf("Normalize", "TotalOne")) domError("unsupported cluster link mode $linkMode", element)
        val loadedIndexes = sc.getArray("Indexes")
        val loadedWeights = sc.getArray("Weights")

        val transform = getRequiredElement(sc, "Transform", element)
        val transformLink = getRequiredElement(sc, "TransformLink", element)

        this.meshToJoint = transform.readMatrix()
        this.bindToWorld = transformLink.readMatrix()

        if (loadedIndexes.isNotEmpty() != loadedWeights.isNotEmpty()) domError(
            "either Indexes or Weights are missing from Cluster",
            element
        )

        if (loadedIndexes.isNotEmpty()) {
            loadedIndexes[0].parseIntsDataArray(indices)
            loadedWeights[0].parseFloatsDataArray(weights)
        }

        if (indices.size != weights.size) domError("sizes of index and weight array don't match up", element)

        val conns = doc.getConnectionsByDestinationSequenced(id, "Model")
        for (con in conns) {
            val mod = processSimpleConnection<Model>(con, false, "Model -> Cluster", element)
            if (mod != null) {
                node = mod
                break
            }
        }
        if (node == null) domError("failed to read target Node for Cluster", element)
    }
}

class Skin(id: Long, element: Element, doc: Document, name: String) : Deformer(id, element, doc, name) {

    val skinningType = element.scope["SkinningType"]?.get(0)?.parseAsString ?: "Linear"
    val clusters = ArrayList<Cluster>()

    init {
        if (skinningType !in setOf("Linear", "Rigid")) domError("unsupported skinning type $skinningType", element)
    }

    init {
        val conns = doc.getConnectionsByDestinationSequenced(id, "Deformer")

        clusters.ensureCapacity(conns.size)
        for (con in conns) {
            val cluster = processSimpleConnection<Cluster>(con, false, "Cluster -> Skin", element)
            if (cluster != null) {
                clusters += cluster
                continue
            }
        }
    }
}

class BlendShape(id: Long, element: Element, doc: Document, name: String) : Deformer(id, element, doc, name) {

    val channels = ArrayList<BlendShapeChannel>()

    init {
        val conns = doc.getConnectionsByDestinationSequenced(id, "Deformer")

        channels.ensureCapacity(conns.size)
        for (con in conns) {
            val channel =
                processSimpleConnection<BlendShapeChannel>(con, false, "BlendShapeChannel -> BlendShape", element)
            if (channel != null) {
                channels += channel
                continue
            }
        }
    }
}

class BlendShapeChannel(id: Long, element: Element, doc: Document, name: String) : Deformer(id, element, doc, name) {

    val shapes = ArrayList<ShapeGeometry>()
    val fullWeights = ArrayList<Float>()
    var defaultWeight = 0f

    init {
        defaultWeight = element.scope["DeformPercent"]?.get(0)?.parseAsFloat ?: props("DeformPercent", 0f)
        element.scope["FullWeights"]?.parseFloatsDataArray(fullWeights)

        val conns = doc.getConnectionsByDestinationSequenced(id, "Geometry")

        shapes.ensureCapacity(conns.size)
        for (con in conns) {
            val shape = processSimpleConnection<ShapeGeometry>(con, false, "Shape -> BlendShapeChannel", element)
            if (shape != null) {
                shapes += shape
                continue
            }
        }
    }
}

class ShapeGeometry(id: Long, element: Element, doc: Document, name: String) : Geometry(id, element, name, doc) {

    val indices = ArrayList<Int>()
    val vertices = ArrayList<Vec3f>()
    val normals = ArrayList<Vec3f>()

    init {
        val sc = element.scope
        sc["Indexes"]?.parseIntsDataArray(indices)
        sc["Vertices"]?.parseVec3DataArray(vertices)
        sc["Normals"]?.parseVec3DataArray(normals)
    }
}
