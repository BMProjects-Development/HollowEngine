package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.util.bool
import ru.hollowhorizon.hollowengine.client.models.util.strncmp
import ru.hollowhorizon.hollowengine.client.models.util.trimNUL
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import java.util.*

class LazyObject(val id: Long, val element: Element, val doc: Document) {

    var object_: Object? = null
    var flags = Flags.NULL.ordinal

    enum class Flags { NULL, BEING_CONSTRUCTED, FAILED_TO_CONSTRUCT }

    infix fun Int.wo(other: Flags) = and(other.ordinal.inv())
    infix fun Int.or(f: Flags) = or(f.ordinal)

    @Suppress("UNCHECKED_CAST")
    fun <T> get(dieOnError: Boolean = false) = get(dieOnError) as? T

    fun get(dieOnError: Boolean = false): Object? {

        if (isBeingConstructed) {
            domWarning("Object is being constructed (circular dependency?), returning null for id=$id", element)
            return null
        }
        
        if (failedToConstruct) {
            domWarning("Object previously failed to construct, returning null for id=$id", element)
            return null
        }

        if (object_ != null) return object_

        /*  if this is the root object, we return a dummy since there is no root object int he fbx file - it is just
            referenced with id 0. */
        if (id == 0L) {
            object_ = Object(id, element, "Model::RootNode")
            return object_
        }

        val key = element.keyToken
        val tokens = element.tokens

        if (tokens.size < 3) domError("expected at least 3 tokens: id, name and class tag", element)

        var name = tokens[1].parseAsString

        /*  small fix for binary reading: binary fbx files don't use prefixes such as Model:: in front of their names.
            The loading code expects this at many places, though!
            so convert the binary representation (a 0x0001) to the double colon notation. */
        if (tokens[1].isBinary)
            for (i in name.indices) {
                if (name[i].code == 0x0 && name[i + 1].code == 0x1)
                    name = name.substring(i + 2) + "::" + name.substring(0, i)
            }
        else name = name.trimNUL()

        val classtag = tokens[2].parseAsString

        // prevent recursive calls
        flags = flags or Flags.BEING_CONSTRUCTED

        try {
            // this needs to be relatively fast since it happens a lot,
            // so avoid constructing strings all the time.
            val obType = key.begin
            val length = key.end - key.begin

            // For debugging
            //dumpObjectClassInfo( objtype, classtag )

            object_ = when {
                buffer.strncmp("Geometry", obType, length) -> when (classtag) {
                    "Mesh" -> MeshGeometry(id, element, name, doc)
                    "Shape" -> ShapeGeometry(id, element, doc, name)
                    else -> {
                        domWarning("Unknown Geometry classtag: '$classtag' for object '$name'", element)
                        null
                    }
                }
                buffer.strncmp("NodeAttribute", obType, length) -> when (classtag) {
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
                buffer.strncmp("Deformer", obType, length) -> when (classtag) {
                    "Cluster" -> Cluster(id, element, doc, name)
                    "Skin" -> Skin(id, element, doc, name)
                    "BlendShape" -> BlendShape(id, element, doc, name)
                    "BlendShapeChannel" -> BlendShapeChannel(id, element, doc, name)
                    else -> {
                        domWarning("Unknown Deformer classtag: '$classtag' for object '$name'", element)
                        null
                    }
                }
                buffer.strncmp("Model", obType, length) ->  // FK and IK effectors are not supported
                    Model(id, element, doc, name).takeIf { classtag != "IKEffector" && classtag != "FKEffector" }
                buffer.strncmp("Material", obType, length) -> Material(id, element, doc, name)
                buffer.strncmp("Texture", obType, length) -> Texture(id, element, doc, name)
                buffer.strncmp("LayeredTexture", obType, length) -> LayeredTexture(id, element, doc, name)
                buffer.strncmp("Video", obType, length) -> Video(id, element, doc, name)
                buffer.strncmp("AnimationStack", obType, length) -> AnimationStack(id, element, name, doc)
                buffer.strncmp("AnimationLayer", obType, length) -> AnimationLayer(id, element, name, doc)
                // note: order matters for these two
                buffer.strncmp("AnimationCurve", obType, length) -> AnimationCurve(id, element, name, doc)
                buffer.strncmp("AnimationCurveNode", obType, length) -> AnimationCurveNode(id, element, name, doc)
                else -> {
                    domWarning("Unknown object type: '${key.stringContents}' (classtag='$classtag', name='$name')", element)
                    null
                }
            }
        } catch (ex: Exception) {
            flags = flags wo Flags.BEING_CONSTRUCTED
            flags = flags or Flags.FAILED_TO_CONSTRUCT

            // note: the error message is already formatted, so raw logging is ok
            HollowEngine.LOGGER.error(ex)

            if (dieOnError) throw Exception()
        }

        if (object_ == null) {
            // Log the reason for failure to help debugging
            domWarning("failed to convert element to DOM object, class: '$classtag', name: '$name', key: '${key}'", element)
        }

        flags = flags wo Flags.BEING_CONSTRUCTED
        return object_
    }

//    inline fun <reified T> get(dieOnError: Boolean = false): T {
//        const Object * const ob = Get(dieOnError)
//        return ob ? dynamic_cast<const T*>(ob) : NULL
//    }

    val isBeingConstructed get() = flags == Flags.BEING_CONSTRUCTED.ordinal
    val failedToConstruct get() = flags == Flags.FAILED_TO_CONSTRUCT.ordinal
}


/** Base class for in-memory (DOM) representations of FBX objects */
open class Object(val id: Long, val element: Element, val name: String)


/** DOM class for generic FBX NoteAttribute blocks. NoteAttribute's just hold a property table,
 *  fixed members are added by deriving classes. */
open class NodeAttribute(id: Long, element: Element, doc: Document, name: String) : Object(id, element, name) {

    val props: PropertyTable

    init {
        val sc = element.scope

        val classname = element[2].parseAsString

        val isNullOrLimb = classname == "Null" || classname == "LimbNode"
        props = getPropertyTable(doc, "NodeAttribute.Fbx$classname", element, sc, isNullOrLimb)
    }
}


/** DOM base class for FBX camera settings attached to a node */
class CameraSwitcher(id: Long, element: Element, doc: Document, name: String) : NodeAttribute(id, element, doc, name) {

    val cameraId = element.scope["CameraId"]?.let { it[0].parseAsInt } ?: 0
    val cameraName = element.scope["CameraName"]?.let { it[0].stringContents } ?: ""
    val cameraIndexName = element.scope["CameraIndex"]?.let { it[0].stringContents } ?: ""
}


/** DOM base class for FBX cameras attached to a node */
class Camera(id: Long, element: Element, doc: Document, name: String) : NodeAttribute(id, element, doc, name) {

    val position get() = props("Position", Vec3f(0f, 0f, 0f))
    val upVector get() = props("UpVector", Vec3f(0f, 1f, 0f))
    val interestPosition get() = props("InterestPosition", Vec3f(0f, 0f, 0f))

    val aspectWidth get() = props("AspectWidth", 1f)
    val aspectHeight get() = props("AspectHeight", 1f)
    val filmWidth get() = props("FilmWidth", 1f)
    val filmHeight get() = props("FilmHeight", 1f)

    val nearPlane get() = props("NearPlane", 0.1f)
    val farPlane get() = props("FarPlane", 100f)

    val filmAspectRatio get() = props("FilmAspectRatio", 1f)
    val apertureMode get() = props("ApertureMode", 0)

    val fieldOfView get() = props("FieldOfView", 1f)
    val focalLength get() = props("FocalLength", 1f)
}

/** DOM base class for FBX null markers attached to a node */
class Null(id: Long, element: Element, doc: Document, name: String) : NodeAttribute(id, element, doc, name)

/** DOM base class for FBX limb node markers attached to a node */
class LimbNode(id: Long, element: Element, doc: Document, name: String) : NodeAttribute(id, element, doc, name)

/** DOM base class for FBX lights attached to a node */
class Light(id: Long, element: Element, doc: Document, name: String) : NodeAttribute(id, element, doc, name) {

    enum class Type { Point, Directional, Spot, Area, Volume }

    enum class Decay { None, Linear, Quadratic, Cubic }

    val color get() = props("Color", Vec3f(1f, 1f, 1f))
    val type get() = Type.entries[props("Type", Type.Point.ordinal)]
    val castLightOnObject get() = props("CastLightOnObject", defaultValue = false)
    val drawVolumetricLight get() = props("DrawVolumetricLight", defaultValue = true)
    val drawGroundProjection get() = props("DrawGroundProjection", defaultValue = true)
    val drawFrontFacingVolumetricLight get() = props("DrawFrontFacingVolumetricLight", defaultValue = false)
    val intensity get() = props("Intensity", 100f)
    val innerAngle get() = props("InnerAngle", 0f)
    val outerAngle get() = props("OuterAngle", 45f)
    val fog get() = props("Fog", 50)
    val decayType get() = Decay.values()[props("DecayType", Decay.Quadratic.ordinal)]
    val decayStart get() = props("DecayStart", 1f)
    val fileName get() = props("FileName", "")

    val enableNearAttenuation get() = props("EnableNearAttenuation", defaultValue = false)
    val nearAttenuationStart get() = props("NearAttenuationStart", 0f)
    val nearAttenuationEnd get() = props("NearAttenuationEnd", 0f)
    val enableFarAttenuation get() = props("EnableFarAttenuation", defaultValue = false)
    val farAttenuationStart get() = props("FarAttenuationStart", 0f)
    val farAttenuationEnd get() = props("FarAttenuationEnd", 0f)

    val castShadows get() = props("CastShadows", defaultValue = true)
    val shadowColor get() = props("ShadowColor", Vec3f(0f, 0f, 0f))

    val areaLightShape get() = props("AreaLightShape", 0)

    val LeftBarnDoor get() = props("LeftBarnDoor", 20f)
    val RightBarnDoor get() = props("RightBarnDoor", 20f)
    val TopBarnDoor get() = props("TopBarnDoor", 20f)
    val BottomBarnDoor get() = props("BottomBarnDoor", 20f)
    val EnableBarnDoor get() = props("EnableBarnDoor", defaultValue = true)
}

//
/** DOM base class for FBX models (even though its semantics are more "node" than "model" */
class Model(id: Long, element: Element, doc: Document, name: String) : Object(id, element, name) {

    val materials = ArrayList<Material>()
    val geometry = ArrayList<Geometry>()
    val attributes = ArrayList<NodeAttribute>()

    val shading = element.scope["Shading"]?.get(0)?.stringContents ?: "Y"
    val culling = element.scope["Culling"]?.get(0)?.parseAsString ?: ""
    val props = getPropertyTable(doc, "Model.FbxNode", element, element.scope)

    init {
        resolveLinks(element, doc)
    }

    enum class RotOrder { EulerXYZ, EulerXZY, EulerYZX, EulerYXZ, EulerZXY, EulerZYX, SphericXYZ }

    enum class TransformInheritance { RrSs, RSrs, Rrs }

    val quaternionInterpolate get() = props("QuaternionInterpolate", 0)

    val rotationOffset get() = props("RotationOffset", Vec3f(0f, 0f, 0f))
    val rotationPivot get() = props("RotationPivot", Vec3f(0f, 0f, 0f))
    val scalingOffset get() = props("ScalingOffset", Vec3f(0f, 0f, 0f))
    val scalingPivot get() = props("ScalingPivot", Vec3f(0f, 0f, 0f))
    val translationActive get() = props("TranslationActive", defaultValue = false)

    val translationMin get() = props("TranslationMin", Vec3f(0f, 0f, 0f))
    val translationMax get() = props("TranslationMax", Vec3f(0f, 0f, 0f))

    val translationMinX get() = props("TranslationMinX", defaultValue = false)
    val translationMaxX get() = props("TranslationMaxX", defaultValue = false)
    val translationMinY get() = props("TranslationMinY", defaultValue = false)
    val translationMaxY get() = props("TranslationMaxY", defaultValue = false)
    val translationMinZ get() = props("TranslationMinZ", defaultValue = false)
    val translationMaxZ get() = props("TranslationMaxZ", defaultValue = false)

    val rotationOrder get() = RotOrder.values()[props("RotationOrder", RotOrder.EulerXYZ.ordinal)]
    val rotationSpaceForLimitOnly get() = props("RotationSpaceForLimitOnlyMaxZ", defaultValue = false)
    val rotationStiffnessX get() = props("RotationStiffnessX", 0f)
    val rotationStiffnessY get() = props("RotationStiffnessY", 0f)
    val rotationStiffnessZ get() = props("RotationStiffnessZ", 0f)
    val axisLen get() = props("AxisLen", 0f)

    val preRotation get() = props("PreRotation", Vec3f(0f, 0f, 0f))
    val postRotation get() = props("PostRotation", Vec3f(0f, 0f, 0f))
    val rotationActive get() = props("RotationActive", defaultValue = false)

    val rotationMin get() = props("RotationMin", Vec3f(0f, 0f, 0f))
    val rotationMax get() = props("RotationMax", Vec3f(0f, 0f, 0f))

    val rotationMinX get() = props("RotationMinX", defaultValue = false)
    val rotationMaxX get() = props("RotationMaxX", defaultValue = false)
    val rotationMinY get() = props("RotationMinY", defaultValue = false)
    val rotationMaxY get() = props("RotationMaxY", defaultValue = false)
    val rotationMinZ get() = props("RotationMinZ", defaultValue = false)
    val rotationMaxZ get() = props("RotationMaxZ", defaultValue = false)
    val inheritType get() = TransformInheritance.entries[props("InheritType", TransformInheritance.RrSs.ordinal)]

    val scalingActive get() = props("ScalingActive", defaultValue = false)
    val scalingMin get() = props("ScalingMin", Vec3f(0f, 0f, 0f))
    val scalingMax get() = props("ScalingMax", Vec3f(1f, 1f, 1f))
    val scalingMinX get() = props("ScalingMinX", defaultValue = false)
    val scalingMaxX get() = props("ScalingMaxX", defaultValue = false)
    val scalingMinY get() = props("ScalingMinY", defaultValue = false)
    val scalingMaxY get() = props("ScalingMaxY", defaultValue = false)
    val scalingMinZ get() = props("ScalingMinZ", defaultValue = false)
    val scalingMaxZ get() = props("ScalingMaxZ", defaultValue = false)

    val geometricTranslation get() = props("GeometricTranslation", Vec3f(0f, 0f, 0f))
    val geometricRotation get() = props("GeometricRotation", Vec3f(0f, 0f, 0f))
    val geometricScaling get() = props("GeometricScaling", Vec3f(1f, 1f, 1f))

    val minDampRangeX get() = props("MinDampRangeX", 0f)
    val minDampRangeY get() = props("MinDampRangeY", 0f)
    val minDampRangeZ get() = props("MinDampRangeZ", 0f)
    val maxDampRangeX get() = props("MaxDampRangeX", 0f)
    val maxDampRangeY get() = props("MaxDampRangeY", 0f)
    val maxDampRangeZ get() = props("MaxDampRangeZ", 0f)

    val minDampStrengthX get() = props("MinDampStrengthX", 0f)
    val minDampStrengthY get() = props("MinDampStrengthY", 0f)
    val minDampStrengthZ get() = props("MinDampStrengthZ", 0f)
    val maxDampStrengthX get() = props("MaxDampStrengthX", 0f)
    val maxDampStrengthY get() = props("MaxDampStrengthY", 0f)
    val maxDampStrengthZ get() = props("MaxDampStrengthZ", 0f)

    val preferredAngleX get() = props("PreferredAngleX", 0f)
    val preferredAngleY get() = props("PreferredAngleY", 0f)
    val preferredAngleZ get() = props("PreferredAngleZ", 0f)

    val show get() = props["Show"] ?: true
    val lodBox get() = props["LODBox"] ?: false
    val freeze get() = props["Freeze"] ?: false

    /** convenience method to check if the node has a Null node marker */
    val isNull get() = attributes.any { it is Null }

    fun resolveLinks(element: Element, doc: Document) {

        val arr = arrayOf("Geometry", "Material", "NodeAttribute")

        // resolve material
        val conns = doc.getConnectionsByDestinationSequenced(id, arr)

        materials.ensureCapacity(conns.size)
        geometry.ensureCapacity(conns.size)
        attributes.ensureCapacity(conns.size)
        for (con in conns) {

            // material and geometry links should be Object-Object connections
            if (con.prop.isNotEmpty()) continue

            val ob = con.sourceObject
            if (ob == null) {
                domWarning("failed to read source object for incoming Model link, ignoring", element)
                continue
            }

            var `continue` = true
            when (ob) {
                is Material -> materials += ob
                is Geometry -> geometry += ob
                is NodeAttribute -> attributes += ob
                else -> `continue` = false
            }
            if(`continue`) continue

            domWarning("source object for model link is neither Material, NodeAttribute nor Geometry, ignoring", element)
        }
    }
}

/** DOM class for generic FBX textures */
class Texture(id: Long, element: Element, doc: Document, name: String) : Object(id, element, name) {

    val uvTrans = element.scope["ModelUVTranslation"]?.let { Vec2f(it[0].parseAsFloat, it[1].parseAsFloat) }
        ?: Vec2f(0f)
    val uvScaling = element.scope["ModelUVScaling"]?.let { Vec2f(it[0].parseAsFloat, it[1].parseAsFloat) }
        ?: Vec2f(1f)

    val type = element.scope["Type"]?.get(0)?.parseAsString ?: ""
    val relativeFileName = element.scope["RelativeFileName"]?.get(0)?.parseAsString ?: ""
    val fileName = element.scope["FileName"]?.get(0)?.parseAsString ?: ""
    val alphaSource = element.scope["Texture_Alpha_Source"]?.get(0)?.parseAsString ?: ""
    val props = getPropertyTable(doc, "Texture.FbxFileTexture", element, element.scope)

    val crop = element.scope["Cropping"].let { sc -> IntArray(4, { sc?.get(it)?.parseAsInt ?: 0 }) }

    var media: Video? = null

    init {
        // resolve video links
        val conns = doc.getConnectionsByDestinationSequenced(id)
        for (con in conns) {
            val ob = con.sourceObject
            if (ob == null) {
                domWarning("failed to read source object for texture link, ignoring", element)
                continue
            }
            media = ob as? Video
        }
    }
}

/** DOM class for layered FBX textures */
class LayeredTexture(id: Long, element: Element, doc: Document, name: String) : Object(id, element, name) {

    val textures = ArrayList<Texture>()
    val blendMode = element.scope["BlendModes"]?.let { BlendMode.entries[it[0].parseAsInt] }
        ?: BlendMode.Translucent
    val alpha = element.scope["Alphas"]?.get(0)?.parseAsFloat ?: 0f

    //Can only be called after construction of the layered texture object due to construction flag.
    fun fillTexture(doc: Document) {
        val conns = doc.getConnectionsByDestinationSequenced(id)
        for (i in 0 until conns.size) {
            val con = conns[i]

            val ob = con.sourceObject
            if (ob == null) {
                domWarning("failed to read source object for texture link, ignoring", element)
                continue
            }
            (ob as? Texture)?.let { textures += it }
        }
    }

    enum class BlendMode { Translucent, Additive, Modulate, Modulate2, Over, Normal, Dissolve, Darken, ColorBurn,
        LinearBurn, DarkerColor, Lighten, Screen, ColorDodge, LinearDodge, LighterColor, SoftLight, HardLight,
        VividLight, LinearLight, PinLight, HardMix, Difference, Exclusion, Subtract, Divide, Hue, Saturation, Color,
        Luminosity, Overlay, BlendModeCount
    }

}

/** DOM class for generic FBX videos */
class Video(id: Long, element: Element, doc: Document, name: String) : Object(id, element, name) {

    val type = element.scope["Type"]?.get(0)?.parseAsString ?: ""
    val relativeFileName = element.scope["RelativeFilename"]?.get(0)?.parseAsString ?: ""
    val fileName = element.scope["FileName"]?.get(0)?.parseAsString ?: ""

    var contentLength = 0
    var content = byteArrayOf()

    init {

        element.scope["Content"]?.let {
            //this field is ommited when the embedded texture is already loaded, let's ignore if it´s not found
            try {
                val token = it[0]
                val data = token.begin
                when {
                    !token.isBinary -> {
                        val data = token.parseAsString
                        content = Base64.getDecoder().wrap(data.byteInputStream()).readBytes()
                        contentLength = content.size
                    }
                    token.end - data < 5 -> domError("binary data array is too short, need five (5) bytes for type signature and element count", element)
                    buffer[data].toInt().toChar() != 'R' -> domWarning("video content is not raw binary data, ignoring", element)
                    else -> {
                        // read number of elements
                        val len = buffer.getInt(data + 1)

                        contentLength = len

                        content = ByteArray(len, { buffer.get(data + 5 + it) })

                        val modifiedPngSignature = byteArrayOf(-3, 80, 78, 71, 13, 10, 26, 10)
                        if(content.copyOf(8).contentEquals(modifiedPngSignature)) {
                            content[0] = -119 // BlockBench, WTF?!
                        }
                    }
                }

            } catch (runtimeError: Exception) {
                //we don´t need the content data for contents that has already been loaded
            }
        }
    }

    fun relinquishContent(): ByteArray {
        val ptr = content
        content = byteArrayOf()
        return ptr
    }

    val props = getPropertyTable(doc, "Video.FbxVideo", element, element.scope)
}

/** DOM class for generic FBX materials */
class Material(id: Long, element: Element, doc: Document, name: String) : Object(id, element, name) {

    val shading: String
    val multiLayer: Boolean
    val props: PropertyTable

    init {
        val sc = element.scope

        val shadingModel = sc["ShadingModel"]
        multiLayer = (sc["MultiLayer"]?.get(0)?.parseAsInt ?: 0).bool

        shading = if (shadingModel != null) shadingModel[0].parseAsString
        else "phong".also { domWarning("shading mode not specified, assuming phong", element) }

        val templateName = when (shading.lowercase()) {
            "phong" -> "Material.FbxSurfacePhong"
            "lambert" -> "Material.FbxSurfaceLambert"
            else -> "".also { domWarning("shading mode not recognized: $shading", element) }
        }
        props = getPropertyTable(doc, templateName, element, sc)
    }

    val textures = mutableMapOf<String, Texture>()
    val layeredTextures = mutableMapOf<String, LayeredTexture>()

    init {
        // resolve texture links
        val conns = doc.getConnectionsByDestinationSequenced(id)
        for (con in conns) {

            // texture link to properties, not objects
            if (con.prop.isEmpty()) continue

            val ob = con.sourceObject
            if (ob == null) {
                domWarning("failed to read source object for texture link, ignoring", element)
                continue
            }

            val tex = ob as? Texture
            if (tex == null) {
                val layeredTexture = ob as? LayeredTexture
                if (layeredTexture == null) {
                    domWarning("source object for texture link is not a texture or layered texture, ignoring", element)
                    continue
                }
                val prop = con.prop
                if (layeredTextures.contains(prop)) domWarning("duplicate layered texture link: $prop", element)

                layeredTextures[prop] = layeredTexture
                layeredTexture.fillTexture(doc)
            } else {
                val prop = con.prop
                if (textures.contains(prop)) domWarning("duplicate texture link: $prop", element)

                textures[prop] = tex
            }

        }
    }
}

/** Represents a FBX animation curve (i.e. a 1-dimensional set of keyframes and values therefor) */
class AnimationCurve(id: Long, element: Element, name: String, doc: Document) : Object(id, element, name) {

    /** get list of keyframe positions (time). Invariant: |GetKeys()| > 0   */
    val keys = ArrayList<Long>()
    /** list of keyframe values. Invariant: |GetKeys()| == |GetValues()| && |GetKeys()| > 0     */
    val values = ArrayList<Float>()
    val attributes = ArrayList<Float>()
    val flags = ArrayList<Int>()

    init {
        val sc = element.scope
        val keyTime = sc["KeyTime"]!!
        val keyValueFloat = sc["KeyValueFloat"]!!

        keyTime.parseLongsDataArray(keys)
        keyValueFloat.parseFloatsDataArray(values)

        if (keys.size != values.size) domError("the number of key times does not match the number of keyframe values", keyTime)

        // check if the key times are well-ordered
        for (i in 0 until keys.size - 1)
            if (keys[i] > keys[i + 1])
                domError("the keyframes are not in ascending order", keyTime)

        sc["KeyAttrDataFloat"]?.parseFloatsDataArray(attributes)
        sc["KeyAttrFlags"]?.parseIntsDataArray(flags)
    }
}

/** Represents a FBX animation curve (i.e. a mapping from single animation curves to nodes) */
class AnimationCurveNode(id: Long, element: Element, name: String, val doc: Document,
                         targetPropWhitelist: ArrayList<String> = arrayListOf()) : Object(id, element, name) {

    /** Object the curve is assigned to, this can be NULL if the target object has no DOM representation or could not
     *  be read for other reasons.*/
    var target: Object? = null
    val props: PropertyTable
    val curves = mutableMapOf<String, AnimationCurve>()

    /** Property of Target() that is being animated*/
    var prop = ""

    /* the optional white list specifies a list of property names for which the caller wants animations for.
        If the curve node does not match one of these, std::range_error will be thrown. */
    init {
        val sc = element.scope

        // find target node
        val whitelist = arrayOf("Model", "NodeAttribute")
        val conns = doc.getConnectionsBySourceSequenced(id, whitelist)

        for (con in conns) {

            // link should go for a property
            if (con.prop.isEmpty()) continue

            if (targetPropWhitelist.isNotEmpty()) {
                val s = con.prop
                var ok = false
                for (p in targetPropWhitelist) {
                    if (s == p) {
                        ok = true
                        break
                    }
                }

                if (!ok) throw Exception("AnimationCurveNode target property is not in whitelist") // TODO handle better std::range_error?
            }

            val ob = con.destinationObject
            if (ob == null) {
                domWarning("failed to read destination object for AnimationCurveNode->Model link, ignoring", element)
                continue
            }

            // XXX support constraints as DOM class
            //ai_assert(dynamic_cast<const Model*>(ob) || dynamic_cast<const NodeAttribute*>(ob));
            target = ob
            if (target == null) continue

            prop = con.prop
            break
        }

        if (target == null) domWarning("failed to resolve target Model/NodeAttribute/Constraint for AnimationCurveNode", element)

        props = getPropertyTable(doc, "AnimationCurveNode.FbxAnimCurveNode", element, sc, false)
    }

    fun curves(): MutableMap<String, AnimationCurve> {

        if (curves.isEmpty()) {
            // resolve attached animation curves
            val conns = doc.getConnectionsByDestinationSequenced(id, "AnimationCurve")

            for (con in conns) {

                // link should go for a property
                if (con.prop.isEmpty()) continue

                val ob = con.sourceObject
                if (ob == null) {
                    domWarning("failed to read source object for AnimationCurve->AnimationCurveNode link, ignoring", element)
                    continue
                }

                val anim = ob as? AnimationCurve
                if (anim == null) {
                    domWarning("source object for ->AnimationCurveNode link is not an AnimationCurve", element)
                    continue
                }

                curves[con.prop] = anim
            }
        }

        return curves
    }

    val targetAsModel get() = target as? Model

}

/** Represents a FBX animation layer (i.e. a list of node animations) */
class AnimationLayer(id: Long, element: Element, name: String, val doc: Document) : Object(id, element, name) {

    /** note: the props table here bears little importance and is usually absent */
    val props = getPropertyTable(doc, "AnimationLayer.FbxAnimLayer", element, element.scope, true)

    /* the optional white list specifies a list of property names for which the caller
    wants animations for. Curves not matching this list will not be added to the
    animation layer. */
    fun nodes(targetPropWhitelist: Array<String> = arrayOf()): ArrayList<AnimationCurveNode> {

        val nodes = ArrayList<AnimationCurveNode>()

        // resolve attached animation nodes
        val conns = doc.getConnectionsByDestinationSequenced(id, "AnimationCurveNode")
        nodes.ensureCapacity(conns.size)

        for (con in conns) {

            // link should not go to a property
            if (con.prop.isNotEmpty()) continue

            val ob = con.sourceObject
            if (ob == null) {
                domWarning("failed to read source object for AnimationCurveNode->AnimationLayer link, ignoring", element)
                continue
            }

            val anim = ob as? AnimationCurveNode
            if (anim == null) {
                domWarning("source object for ->AnimationLayer link is not an AnimationCurveNode", element)
                continue
            }

            if (targetPropWhitelist.isNotEmpty()) {
                val s = anim.prop
                var ok = false
                for (p in targetPropWhitelist) {
                    if (s == p) {
                        ok = true
                        break
                    }
                }
                if (!ok) continue
            }
            nodes += anim
        }
        return nodes // pray for NRVO
    }
}

/** Represents a FBX animation stack (i.e. a list of animation layers) */
class AnimationStack(id: Long, element: Element, name: String, doc: Document) : Object(id, element, name) {

    // note: we don't currently use any of these properties so we shouldn't bother if it is missing
    val props = getPropertyTable(doc, "AnimationStack.FbxAnimStack", element, element.scope, true)
    val layers = ArrayList<AnimationLayer>()

    init {

        // resolve attached animation layers
        val conns = doc.getConnectionsByDestinationSequenced(id, "AnimationLayer")
        layers.ensureCapacity(conns.size)

        for (con in conns) {
            // link should not go to a property
            if (con.prop.isNotEmpty()) continue

            val ob = con.sourceObject
            if (ob == null) {
                domWarning("failed to read source object for AnimationLayer->AnimationStack link, ignoring", element)
                continue
            }

            val anim = ob as? AnimationLayer
            if (anim == null) {
                domWarning("source object for ->AnimationStack link is not an AnimationLayer", element)
                continue
            }
            layers += anim
        }
    }

    val localStart get() = props("LocalStart", 0L)
    val localStop get() = props("LocalStop", 0L)
    val referenceStart get() = props("ReferenceStart", 0L)
    val referenceStop get() = props("ReferenceStop", 0L)
}


/** DOM class for deformers */
open class Deformer(id: Long, element: Element, doc: Document, name: String) : Object(id, element, name) {

    val props = getPropertyTable(doc, "Deformer.Fbx" + element[2].parseAsString, element, element.scope, true)
}

/** DOM class for skin deformer clusters (aka subdeformers) */
class Cluster(id: Long, element: Element, doc: Document, name: String) : Deformer(id, element, doc, name) {

    /** get the list of deformer weights associated with this cluster. Use #GetIndices() to get the associated vertices.
     *  Both arrays have the same size (and may also be empty). */
    val weights = ArrayList<Float>()
    /** get indices into the vertex data of the geometry associated with this cluster. Use #GetWeights() to get the
     *  associated weights. Both arrays have the same size (and may also be empty). */
    val indices = ArrayList<Int>()

    val transform: Mat4f
    val transformLink: Mat4f

    var node: Model? = null

    init {
        val sc = element.scope

        val loadedIndexes = sc.getArray("Indexes")
        val loadedWeights = sc.getArray("Weights")

        val transform = getRequiredElement(sc, "Transform", element)
        val transformLink = getRequiredElement(sc, "TransformLink", element)

        this.transform = transform.readMatrix()
        this.transformLink = transformLink.readMatrix()

        // it is actually possible that there be Deformer's with no weights
        if (loadedIndexes.isNotEmpty() != loadedWeights.isNotEmpty()) domError("either Indexes or Weights are missing from Cluster", element)

        if (loadedIndexes.isNotEmpty()) {
            loadedIndexes[0].parseIntsDataArray(indices)
            loadedWeights[0].parseFloatsDataArray(weights)
        }

        if (indices.size != weights.size) domError("sizes of index and weight array don't match up", element)

        // read assigned node
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

/** DOM class for skin deformers */
class Skin(id: Long, element: Element, doc: Document, name: String) : Deformer(id, element, doc, name) {

    val accuracy = element.scope["Link_DeformAcuracy"]?.get(0)?.parseAsFloat ?: 0f
    val clusters = ArrayList<Cluster>()

    init {
        // resolve assigned clusters
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

/** DOM class for blend shape deformers */
class BlendShape(id: Long, element: Element, doc: Document, name: String) : Deformer(id, element, doc, name) {

    val channels = ArrayList<BlendShapeChannel>()

    init {
        // resolve assigned blend shape channels
        val conns = doc.getConnectionsByDestinationSequenced(id, "Deformer")

        channels.ensureCapacity(conns.size)
        for (con in conns) {
            val channel = processSimpleConnection<BlendShapeChannel>(con, false, "BlendShapeChannel -> BlendShape", element)
            if (channel != null) {
                channels += channel
                continue
            }
        }
    }
}

/** DOM class for blend shape channels */
class BlendShapeChannel(id: Long, element: Element, doc: Document, name: String) : Deformer(id, element, doc, name) {

    val shapes = ArrayList<ShapeGeometry>()
    var defaultWeight = 0f

    init {
        // Read default weight from properties
        defaultWeight = props("DeformPercent", 0f)

        // resolve assigned shapes (geometries)
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

/** DOM class for shape geometries (morph targets) */
class ShapeGeometry(id: Long, element: Element, doc: Document, name: String) : Geometry(id, element, name, doc) {

    val vertices = ArrayList<Vec3f>()
    val normals = ArrayList<Vec3f>()

    init {
        val sc = element.scope

        // Read shape vertices (deltas)
        sc["Vertices"]?.let { it.parseVec3DataArray(vertices) }

        // Read shape normals (deltas) if present
        sc["Normals"]?.let { it.parseVec3DataArray(normals) }
    }
}

/** Represents a link between two FBX objects. */
class Connection(val insertionOrder: Long, val src: Long, val dest: Long, val prop: String, val doc: Document) : Comparable<Connection> {

    init {
        assert(doc.objects.contains(src))
        // dest may be 0 (root node)
        assert(dest != 0L || doc.objects.contains(dest))
    }

    /** note: a connection ensures that the source and dest objects exist, but not that they have DOM representations,
     *  so the return value of one of these functions can still be NULL. */
    val sourceObject get() = doc[src]!!.get()
    val destinationObject get() = doc[dest]!!.get()

    // these, however, are always guaranteed to be valid
    val lazySourceObject get() = doc[src]!!
    val lazyDestinationObject get() = doc[dest]!!

    override fun compareTo(other: Connection) = insertionOrder.compareTo(other.insertionOrder)

//    bool Compare(const Connection* c)
//    const {
//        ai_assert(NULL != c)
//
//        return InsertionOrder() < c->InsertionOrder()
//    }
}
//
//// XXX again, unique_ptr would be useful. shared_ptr is too
//// bloated since the objects have a well-defined single owner
//// during their entire lifetime (Document). FBX files have
//// up to many thousands of objects (most of which we never use),
//// so the memory overhead for them should be kept at a minimum.
//typedef std::map<uint64_t, LazyObject*> ObjectMap
//typedef std::fbx_unordered_map<std::string, std::shared_ptr<const PropertyTable> > PropertyTemplateMap
//
//typedef std::multimap<uint64_t, const Connection*> ConnectionMap

/** DOM class for global document settings, a single instance per document can
 *  be accessed via Document.Globals(). */
class FileGlobalSettings(val doc: Document, val props: PropertyTable) {

    val upAxis get() = props("UpAxis", 1)
    val upAxisSign get() = props("UpAxisSign", 1)
    val frontAxis get() = props("FrontAxis", 2)
    val frontAxisSign get() = props("FrontAxisSign", 1)
    val coordAxis get() = props("CoordAxis", 1)
    val coordAxisSign get() = props("CoordAxisSign", 1)
    val originalUpAxis get() = props("OriginalUpAxis", 1)
    val originalUpAxisSign get() = props("OriginalUpAxisSign", 1)
    val unitScaleFactor get() = props("UnitScaleFactor", 1.0)
    val originalUnitScaleFactor get() = props("OriginalUnitScaleFactor", 1.0)
    val ambientColor get() = props("AmbientColor", Vec3f(0f))
    val defaultCamera get() = props("DefaultCamera", "")

    enum class FrameRate { DEFAULT, _120, _100, _60, _50, _48, _30, _30_DROP, NTSC_DROP_FRAME, NTSC_FULL_FRAME, PAL,
        CINEMA, _1000, CINEMA_ND, CUSTOM // end-of-enum sentinel
    }

    val timeMode get() = FrameRate.entries[props("TimeMode", FrameRate.DEFAULT.ordinal)]
    val timeSpanStart get() = props("TimeSpanStart", 0L)
    val timeSpanStop get() = props("TimeSpanStop", 0L)
    val customFrameRate get() = props("CustomFrameRate", -1f)
}

/** DOM root for a FBX file */
class Document(val parser: Parser) {

    val objects = mutableMapOf<Long, LazyObject>()

    val templates = HashMap<String, PropertyTable>()
    val srcConnections = mutableMapOf<Long, ArrayList<Connection>>()
    val destConnections = mutableMapOf<Long, ArrayList<Connection>>()

    var fbxVersion = 0
    var creator = ""
    val creationTimeStamp = IntArray(7)

    private val animationStacks = ArrayList<Long>()
    val animationStacksResolved = ArrayList<AnimationStack>()

    var globals: FileGlobalSettings? = null

    init {
        readHeader()
        readPropertyTemplates()

        readGlobalSettings()

        /*  This order is important, connections need parsed objects to check whether connections are ok or not.
            Objects may not be evaluated yet, though, since this may require valid connections. */
        readObjects()
        readConnections()
    }


    operator fun get(id: Long) = objects[id]
    //
//    bool IsBinary()
//    const {
//        return parser.IsBinary()
//    }
//
//    unsigned int FBXVersion()
//    const {
//        return fbxVersion
//    }
//
//    const std::string& Creator()
//    const {
//        return creator
//    }
//
//    // elements (in this order): Year, Month, Day, Hour, Second, Millisecond
//    const unsigned int* CreationTimeStamp()
//    const {
//        return creationTimeStamp
//    }
//
//    const FileGlobalSettings& GlobalSettings()
//    const {
//        ai_assert(globals.get())
//        return * globals.get()
//    }
//
//    const PropertyTemplateMap& Templates()
//    const {
//        return templates
//    }
//
//    const ObjectMap& Objects()
//    const {
//        return objects
//    }
//
//    const ImportSettings& Settings()
//    const {
//        return settings
//    }
//
//    const ConnectionMap& ConnectionsBySource()
//    const {
//        return src_connections
//    }
//
//    const ConnectionMap& ConnectionsByDestination()
//    const {
//        return dest_connections
//    }
//
    /*  note: the implicit rule in all DOM classes is to always resolve from destination to source (since the FBX object
        hierarchy is, with very few exceptions, a DAG, this avoids cycles). In all cases that may involve back-facing
        edges in the object graph, use LazyObject::IsBeingConstructed() to check. */

    fun getConnectionsBySourceSequenced(source: Long) = getConnectionsSequenced(source, srcConnections)
    fun getConnectionsByDestinationSequenced(dest: Long) = getConnectionsSequenced(dest, destConnections)

    fun getConnectionsBySourceSequenced(source: Long, classname: String) = getConnectionsBySourceSequenced(source, arrayOf(classname))
    fun getConnectionsByDestinationSequenced(dest: Long, classname: String) = getConnectionsByDestinationSequenced(dest, arrayOf(classname))

    fun getConnectionsBySourceSequenced(source: Long, classnames: Array<String>) = getConnectionsSequenced(source, true, srcConnections, classnames)
    fun getConnectionsByDestinationSequenced(dest: Long, classnames: Array<String>) = getConnectionsSequenced(dest, false, destConnections, classnames)

    fun animationStacks(): ArrayList<AnimationStack> {

        if (animationStacksResolved.isNotEmpty() || animationStacks.isEmpty()) return animationStacksResolved

        animationStacksResolved.ensureCapacity(animationStacks.size)
        for (id in animationStacks) {
            val stack = get(id)?.get<AnimationStack>()
            if (stack == null) {
                domWarning("failed to read AnimationStack object")
                continue
            }
            animationStacksResolved += stack
        }
        return animationStacksResolved
    }

    fun getConnectionsSequenced(id: Long, conns: MutableMap<Long, ArrayList<Connection>>): ArrayList<Connection> {

        val range = conns[id] ?: arrayListOf()

        return ArrayList<Connection>(range.size).apply {// NRVO should handle this
            addAll(range)
            sort()
        }
    }

    val MAX_CLASSNAMES = 6
    fun getConnectionsSequenced(id: Long, isSrc: Boolean, conns: MutableMap<Long, ArrayList<Connection>>, classnames: Array<String>): ArrayList<Connection> {
        assert(classnames.size in 1..MAX_CLASSNAMES)

        val temp = ArrayList<Connection>()
        val range = conns[id] ?: return temp

        temp.ensureCapacity(range.size)
        for (it in range) {
            val key = (if (isSrc) it.lazyDestinationObject else it.lazySourceObject).element.keyToken

            var obType = key.begin

            for (name in classnames) {
                assert(name.isNotEmpty())
                if (key.end - key.begin == name.length && buffer.strncmp(name, obType)) {
                    obType = 0
                    break
                }
            }
            if (obType != 0) continue
            temp += it
        }
        temp.sort()
        return temp // NRVO should handle this
    }

    fun readHeader() {
        // Read ID objects from "Objects" section
        val sc = parser.root
        val eHead = sc["FBXHeaderExtension"]
        if (eHead?.compound == null) domError("no FBXHeaderExtension dictionary found")
        val sHead = eHead.compound!!
        fbxVersion = getRequiredElement(sHead, "FBXVersion", eHead)[0].parseAsInt

        // While we may have some success with newer files, we don't support the older 6.n fbx format
        if (fbxVersion < lowerSupportedVersion) domError("unsupported, old format version, supported are only FBX 2011, FBX 2012 and FBX 2013")
        if (fbxVersion > upperSupportedVersion)
            domWarning("unsupported, newer format version, supported are only FBX 2011, FBX 2012 and FBX 2013, trying to read it nevertheless")

        sHead["Creator"]?.let { creator = it[0].parseAsString }

        sHead["CreationTimeStamp"]?.let {
            it.compound?.let {
                creationTimeStamp[0] = it["Year"]!![0].parseAsInt
                creationTimeStamp[1] = it["Month"]!![0].parseAsInt
                creationTimeStamp[2] = it["Day"]!![0].parseAsInt
                creationTimeStamp[3] = it["Hour"]!![0].parseAsInt
                creationTimeStamp[4] = it["Minute"]!![0].parseAsInt
                creationTimeStamp[5] = it["Second"]!![0].parseAsInt
                creationTimeStamp[6] = it["Millisecond"]!![0].parseAsInt
            }
        }
    }

    fun readObjects() {
        // read ID objects from "Objects" section
        val sc = parser.root
        val eObjects = sc["Objects"]
        if (eObjects?.compound == null) domError("no Objects dictionary found")

        // add a dummy entry to represent the Model::RootNode object (id 0), which is only indirectly defined in the input file
        objects[0L] = LazyObject(0L, eObjects, this)

        val sObjects = eObjects.compound!!
        for (el in sObjects.elements) {
            val key = el.key
            for (e in el.value) {
                // extract ID
                val tok = e.tokens

                if (tok.isEmpty()) domError("expected ID after object key", e)

                val id = tok[0].parseAsId

                // id=0 is normally implicit
                if (id == 0L) domError("encountered object with implicitly defined id 0", e)

                if (objects.contains(id)) domWarning("encountered duplicate object id, ignoring first occurrence", e)

                objects[id] = LazyObject(id, e, this)

                // grab all animation stacks upfront since there is no listing of them
                if (key == "AnimationStack") animationStacks += id
            }
        }
    }

    fun readPropertyTemplates() {
        // read property templates from "Definitions" section
        val eDefs = parser.root["Definitions"]
        if (eDefs?.compound == null) {
            domWarning("no Definitions dictionary found")
            return
        }

        val sDefs = eDefs.compound!!
        val oTypes = sDefs.getCollection("ObjectType")
        for (el in oTypes) {
            val sc = el.compound
            if (sc == null) {
                domWarning("expected nested scope in ObjectType, ignoring", el)
                continue
            }
            val tok = el.tokens
            if (tok.isEmpty()) {
                domWarning("expected name for ObjectType element, ignoring", el)
                continue
            }
            val oName = tok[0].parseAsString
            val templs = sc.getCollection("PropertyTemplate")
            for (e in templs) {
                val s = e.compound
                if (s == null) {
                    domWarning("expected nested scope in PropertyTemplate, ignoring", e)
                    continue
                }
                val t = e.tokens
                if (t.isEmpty()) {
                    domWarning("expected name for PropertyTemplate element, ignoring", e)
                    continue
                }
                val pName = t[0].parseAsString
                s["Properties70"]?.let {
                    val props = PropertyTable(it, null)
                    templates["$oName.$pName"] = props
                }
            }
        }
    }

    fun readConnections() {
        val sc = parser.root
        // read property templates from "Definitions" section
        val eConns = sc["Connections"]
        if (eConns?.compound == null) domError("no Connections dictionary found")

        var insertionOrder = 0L
        val sConns = eConns.compound!!
        val conns = sConns.getCollection("C")
        for (el in conns) {
            val type = el[0].parseAsString

            // PP = property-property connection, ignored for now
            // (tokens: "PP", ID1, "Property1", ID2, "Property2")
            if (type == "PP") continue

            val src = el[1].parseAsId
            val dest = el[2].parseAsId

            // OO = object-object connection
            // OP = object-property connection, in which case the destination property follows the object ID
            val prop = if (type == "OP") el[3].parseAsString else ""

            if (!objects.contains(src)) {
                domWarning("source object for connection does not exist", el)
                continue
            }

            // dest may be 0 (root node) but we added a dummy object before
            if (!objects.contains(dest)) {
                domWarning("destination object for connection does not exist", el)
                continue
            }

            // add new connection
            val c = Connection(insertionOrder++, src, dest, prop, this)
            srcConnections.getOrPut(src, ::arrayListOf) += c
            destConnections.getOrPut(dest, ::arrayListOf) += c
        }
    }

    fun readGlobalSettings() {
        val sc = parser.root
        val eHead = sc["GlobalSettings"]
        if (eHead?.compound == null) {
            domWarning("no GlobalSettings dictionary found")
            globals = FileGlobalSettings(this, PropertyTable())
            return
        }

        val props = getPropertyTable(this, "", eHead, eHead.compound!!, true)

        if (props.lazyProps.isEmpty() && props.props.isEmpty())
            domError("GlobalSettings dictionary contains no property table")

        globals = FileGlobalSettings(this, props)
    }
//
//    private :
//    const ImportSettings& settings
//
//    ObjectMap objects
//    const Parser& parser
//
//    PropertyTemplateMap templates
//    ConnectionMap src_connections
//    ConnectionMap dest_connections
//
//    unsigned int fbxVersion
//    std::string creator
//    unsigned int creationTimeStamp[7]
//
//    std::vector<uint64_t> animationStacks
//    mutable std::vector<const AnimationStack*> animationStacksResolved
//
//    std::unique_ptr<FileGlobalSettings> globals

    companion object {
        const val lowerSupportedVersion = 7100
        const val upperSupportedVersion = 7400

    }
}