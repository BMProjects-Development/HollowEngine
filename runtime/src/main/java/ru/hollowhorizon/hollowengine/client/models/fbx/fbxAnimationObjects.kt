package ru.hollowhorizon.hollowengine.client.models.fbx


class AnimationCurve(id: Long, element: Element, name: String, doc: Document) : Object(id, element, name) {
    val keys = ArrayList<Long>()
    val values = ArrayList<Float>()

    val attributes = ArrayList<Float>()
    val flags = ArrayList<Int>()
    val attributeRefs = ArrayList<Int>()

    internal val evaluator by lazy { FbxCurve(this) }

    init {
        val sc = element.scope
        val keyTime = sc["KeyTime"]!!
        val keyValueFloat = sc["KeyValueFloat"]!!

        keyTime.parseTimeDataArray(keys)
        keyValueFloat.parseFloatsDataArray(values)

        if (keys.size != values.size) domError(
            "the number of key times does not match the number of keyframe values", keyTime
        )

        for (i in 0 until keys.size - 1) if (keys[i] >= keys[i + 1]) domError(
            "the keyframes are not in ascending order", keyTime
        )

        sc["KeyAttrDataFloat"]?.parseFloatsDataArray(attributes, allowNonFinite = true)
        sc["KeyAttrFlags"]?.parseIntsDataArray(flags)
        sc["KeyAttrRefCount"]?.parseIntsDataArray(attributeRefs)
        if (attributeRefs.isNotEmpty() && (flags.size != attributeRefs.size || attributes.size != attributeRefs.size * 4)) {
            domError("animation flag and tangent counts do not match reference counts", element)
        }
        if (attributeRefs.isNotEmpty() && (attributeRefs.any { it <= 0 } || attributeRefs.sumOf { it.toLong() } != keys.size.toLong())) {
            domError("invalid animation attribute reference counts", element)
        }
    }
}

class AnimationCurveNode(
    id: Long, element: Element, name: String, val doc: Document,
    targetPropWhitelist: ArrayList<String> = arrayListOf(),
) : Object(id, element, name) {

    var target: Object? = null
    val props: PropertyTable
    val curves = mutableMapOf<String, AnimationCurve>()

    var prop = ""

    init {
        val sc = element.scope

        val whitelist = arrayOf("Model", "NodeAttribute", "Deformer")
        val conns = doc.getConnectionsBySourceSequenced(id, whitelist)

        for (con in conns) {
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

                if (!ok) domError("animation target property is not in whitelist", element)
            }

            val ob = con.destinationObject
            if (ob == null) {
                domWarning("failed to read destination object for AnimationCurveNode->Model link, ignoring", element)
                continue
            }

            target = ob

            prop = con.prop
            break
        }

        if (target == null) domWarning(
            "failed to resolve target Model/NodeAttribute/Constraint for AnimationCurveNode", element
        )

        props = getPropertyTable(doc, "AnimationCurveNode.FbxAnimCurveNode", element, sc, false)
    }

    fun curves(): MutableMap<String, AnimationCurve> {

        if (curves.isEmpty()) {
            val conns = doc.getConnectionsByDestinationSequenced(id, "AnimationCurve")

            for (con in conns) {

                if (con.prop.isEmpty()) continue

                val ob = con.sourceObject
                if (ob == null) {
                    domWarning(
                        "failed to read source object for AnimationCurve->AnimationCurveNode link, ignoring", element
                    )
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

class AnimationLayer(id: Long, element: Element, name: String, val doc: Document) : Object(id, element, name) {
    val props = getPropertyTable(doc, "AnimationLayer.FbxAnimLayer", element, element.compound, true)

    fun nodes(targetPropWhitelist: Array<String> = arrayOf()): ArrayList<AnimationCurveNode> {
        val nodes = ArrayList<AnimationCurveNode>()

        val conns = doc.getConnectionsByDestinationSequenced(id, "AnimationCurveNode")
        nodes.ensureCapacity(conns.size)

        for (con in conns) {
            if (con.prop.isNotEmpty()) continue

            val ob = con.sourceObject
            if (ob == null) {
                domWarning(
                    "failed to read source object for AnimationCurveNode->AnimationLayer link, ignoring", element
                )
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
        return nodes
    }
}

class AnimationStack(id: Long, element: Element, name: String, doc: Document) : Object(id, element, name) {
    val props = getPropertyTable(doc, "AnimationStack.FbxAnimStack", element, element.compound, true)
    val layers = ArrayList<AnimationLayer>()

    init {
        val conns = doc.getConnectionsByDestinationSequenced(id, "AnimationLayer")
        layers.ensureCapacity(conns.size)

        for (con in conns) {
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
}
