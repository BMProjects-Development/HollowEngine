package ru.hollowhorizon.hollowengine.client.models.bedrock

import ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations.Interpolator
import ru.hollowhorizon.hollowengine.common.utils.math.Easing
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.nbt.TreeMap

class BedrockInterpolator<T>(
    val keys: TreeMap<Float, Keyframe>,
    val type: ConverterType<T>,
    val valueScale: Float = 1f
) : Interpolator<T> {
    override val duration: Float = keys.lastKey() ?: 0f

    override fun compute(time: Float, context: BedrockContext): T {
        if (keys.isEmpty()) return type.create(0f, 0f, 0f)

        if (keys.containsKey(time)) {
            val k = keys[time]!!
            val vec = k.pre.eval(context)
            return type.convert(vec)
        }

        val firstKey = keys.firstKey() ?: return type.create(0f, 0f, 0f)
        if (time < firstKey) {
            val vec = keys[firstKey]!!.pre.eval(context)
            return type.convert(vec)
        }
        val lastKey = keys.lastKey() ?: return type.create(0f, 0f, 0f)
        if (time > lastKey) {
            val vec = keys[lastKey]!!.post.eval(context)
            return type.convert(vec)
        }

        val prevEntry = keys.floorEntry(time) ?: return type.create(0f, 0f, 0f)
        val nextEntry = keys.ceilingEntry(time) ?: return type.create(0f, 0f, 0f)

        val t0 = prevEntry.key
        val t1 = nextEntry.key
        val k0 = prevEntry.value
        val k1 = nextEntry.value

        val delta = t1 - t0
        val alpha = if (delta < 1e-5f) 0f else (time - t0) / delta

        val startVal = k0.post.eval(context)
        val endVal = k1.pre.eval(context)

        return when (k0.smooth) {
            "step" -> type.convert(startVal)
            "linear" -> {
                val res = startVal.mix(endVal, alpha) * valueScale
                type.convert(res)
            }

            "catmullrom" -> {
                val p0Entry = keys.floorEntry(t0 - 0.0001f) ?: prevEntry
                val p3Entry = keys.ceilingEntry(t1 + 0.0001f) ?: nextEntry

                val p0 = p0Entry.value.post.eval(context)
                val p1 = startVal
                val p2 = endVal
                val p3 = p3Entry.value.pre.eval(context)

                val res = Easing.catmullRom(alpha, p0, p1, p2, p3) * valueScale
                type.convert(res)
            }

            else -> error("Unsupported interpolation: ${k0.smooth}")
        }
    }

    interface ConverterType<T> {
        fun convert(vec: Vec3f): T
        fun create(x: Float, y: Float, z: Float): T
    }

    object Vec3Converter : ConverterType<Vec3f> {
        override fun convert(vec: Vec3f) = vec
        override fun create(x: Float, y: Float, z: Float) = Vec3f(x, y, z)
    }

    object PositionConverter : ConverterType<Vec3f> {
        override fun convert(vec: Vec3f) = BedrockCoordinates.position(vec)
        override fun create(x: Float, y: Float, z: Float) = convert(Vec3f(x, y, z))
    }

    object QuatConverter : ConverterType<QuatF> {
        override fun convert(vec: Vec3f): QuatF = BedrockCoordinates.rotation(vec)

        override fun create(x: Float, y: Float, z: Float) = convert(Vec3f(x, y, z))
    }
}
