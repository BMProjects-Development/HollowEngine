package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

class LazyObject(val id: Long, val element: Element, val doc: Document) {
    private var value: Object? = null
    private var constructing = false
    inline fun <reified T> get(): T? = get() as? T

    fun get(): Object? {
        value?.let { return it }
        if (constructing) domError("circular object dependency for id=$id", element)
        if (id == 0L) return Object(id, element, "Model::RootNode").also { value = it }
        val key = element.keyToken
        val rawName = element[1].parseAsString
        val separator = rawName.indexOf("\u0000\u0001")
        val name = if (separator >= 0) rawName.substring(separator + 2) + "::" + rawName.substring(0, separator)
        else rawName.trimEnd('\u0000')
        val classtag = element[2].parseAsString
        constructing = true
        try {
            value = when (key.stringContents) {
                "Geometry" -> when (classtag) {
                    "Mesh" -> MeshGeometry(id, element, name, doc)
                    "Shape" -> ShapeGeometry(id, element, doc, name)
                    else -> {
                        domWarning("Unknown Geometry classtag: '$classtag' for object '$name'", element)
                        null
                    }
                }

                "NodeAttribute" -> when (classtag) {
                    "Camera" -> Camera(id, element, doc, name)
                    "CameraSwitcher" -> CameraSwitcher(id, element, doc, name)
                    "Light" -> Light(id, element, doc, name)
                    "Null" -> Null(id, element, doc, name)
                    "LimbNode" -> LimbNode(id, element, doc, name)
                    else -> {
                        domWarning("Unknown NodeAttribute classtag: '$classtag' for object '$name'", element)
                        null
                    }
                }

                "Deformer" -> when (classtag) {
                    "Cluster" -> Cluster(id, element, doc, name)
                    "Skin" -> Skin(id, element, doc, name)
                    "BlendShape" -> BlendShape(id, element, doc, name)
                    "BlendShapeChannel" -> BlendShapeChannel(id, element, doc, name)
                    else -> {
                        domWarning("Unknown Deformer classtag: '$classtag' for object '$name'", element)
                        null
                    }
                }

                "Model" -> Model(id, element, doc, name)
                "Material" -> Material(id, element, doc, name)
                "Texture" -> Texture(id, element, doc, name)
                "LayeredTexture" -> LayeredTexture(id, element, name)
                "Video" -> Video(id, element, doc, name)
                "AnimationStack" -> AnimationStack(id, element, name, doc)
                "AnimationLayer" -> AnimationLayer(id, element, name, doc)
                "AnimationCurve" -> AnimationCurve(id, element, name, doc)
                "AnimationCurveNode" -> AnimationCurveNode(id, element, name, doc)
                else -> {
                    domWarning(
                        "Unknown object type: '${key.stringContents}' (classtag='$classtag', name='$name')", element
                    )
                    null
                }
            }
            return value
        } finally {
            constructing = false
        }
    }
}

open class Object(val id: Long, val element: Element, val name: String)

open class NodeAttribute(id: Long, element: Element, doc: Document, name: String) : Object(id, element, name) {
    val props: PropertyTable

    init {
        val sc = element.scope

        val classname = element[2].parseAsString

        val isNullOrLimb = classname == "Null" || classname == "LimbNode"
        props = getPropertyTable(doc, "NodeAttribute.Fbx$classname", element, sc, isNullOrLimb)
    }
}

class CameraSwitcher(id: Long, element: Element, doc: Document, name: String) : NodeAttribute(id, element, doc, name)

class Camera(id: Long, element: Element, doc: Document, name: String) : NodeAttribute(id, element, doc, name) {
    val position get() = props("Position", Vec3f(0f, 0f, 0f))
}

class Null(id: Long, element: Element, doc: Document, name: String) : NodeAttribute(id, element, doc, name)

class LimbNode(id: Long, element: Element, doc: Document, name: String) : NodeAttribute(id, element, doc, name)

class Light(id: Long, element: Element, doc: Document, name: String) : NodeAttribute(id, element, doc, name) {
    enum class Type { Point, Directional, Spot, Area, Volume }

    val color get() = props("Color", Vec3f(1f, 1f, 1f))
    val type get() = Type.entries[props("Type", Type.Point.ordinal)]
    val fog get() = props("Fog", 50)
    val fileName get() = props("FileName", "")
}

class Model(id: Long, element: Element, doc: Document, name: String) : Object(id, element, name) {

    val materials = ArrayList<Material>()
    val geometry = ArrayList<Geometry>()
    val attributes = ArrayList<NodeAttribute>()

    val shading = element.compound?.get("Shading")?.get(0)?.stringContents ?: "Y"
    val culling = element.compound?.get("Culling")?.get(0)?.parseAsString ?: ""
    val props = getPropertyTable(doc, "Model.FbxNode", element, element.compound)

    init {
        resolveLinks(element, doc)
    }

    enum class RotOrder { EulerXYZ, EulerXZY, EulerYZX, EulerYXZ, EulerZXY, EulerZYX, SphericXYZ }

    enum class TransformInheritance { RrSs, RSrs, Rrs }

    val rotationOffset get() = props("RotationOffset", Vec3f(0f, 0f, 0f))
    val rotationPivot get() = props("RotationPivot", Vec3f(0f, 0f, 0f))
    val scalingOffset get() = props("ScalingOffset", Vec3f(0f, 0f, 0f))
    val scalingPivot get() = props("ScalingPivot", Vec3f(0f, 0f, 0f))

    val rotationOrder
        get() = RotOrder.entries.getOrNull(props("RotationOrder", RotOrder.EulerXYZ.ordinal)) ?: domError(
            "invalid rotation order", element
        )

    val preRotation get() = props("PreRotation", Vec3f(0f, 0f, 0f))
    val postRotation get() = props("PostRotation", Vec3f(0f, 0f, 0f))

    val inheritType
        get() = TransformInheritance.entries.getOrNull(
            props(
                "InheritType", TransformInheritance.RrSs.ordinal
            )
        ) ?: domError("invalid transform inheritance", element)

    val geometricTranslation get() = props("GeometricTranslation", Vec3f(0f, 0f, 0f))
    val geometricRotation get() = props("GeometricRotation", Vec3f(0f, 0f, 0f))
    val geometricScaling get() = props("GeometricScaling", Vec3f(1f, 1f, 1f))

    val show get() = props["Show"] ?: true
    val freeze get() = props["Freeze"] ?: false
    
    fun resolveLinks(element: Element, doc: Document) {

        val arr = arrayOf("Geometry", "Material", "NodeAttribute")

        val conns = doc.getConnectionsByDestinationSequenced(id, arr)

        materials.ensureCapacity(conns.size)
        geometry.ensureCapacity(conns.size)
        attributes.ensureCapacity(conns.size)
        for (con in conns) {

            if (con.prop.isNotEmpty()) continue

            val ob = con.sourceObject
            if (ob == null) {
                domWarning("failed to read source object for incoming Model link, ignoring", element)
                continue
            }

            when (ob) {
                is Material -> materials += ob
                is Geometry -> geometry += ob
                is NodeAttribute -> attributes += ob
                else -> domWarning("invalid object linked to model", element)
            }
        }
    }
}
