package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.client.models.gltf.FloatAccessor
import ru.hollowhorizon.hollowengine.client.models.gltf.GltfAccessor
import ru.hollowhorizon.hollowengine.client.models.gltf.Vec3fAccessor
import ru.hollowhorizon.hollowengine.client.models.gltf.Vec4fAccessor
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec4f

/**
 * Sealed interface for animation channel data that supports multiple data sources.
 * This allows both GLTF and FBX loaders to provide animation data without tight coupling.
 */
sealed interface ChannelData {
    /**
     * Returns the data as Vec3f array (for translation/scale animations)
     */
    fun asVec3f(): Array<Vec3f>
    
    /**
     * Returns the data as Vec4f array (for rotation animations)
     */
    fun asVec4f(): Array<Vec4f>
    
    /**
     * Returns the data as Float list (for weight animations)
     */
    fun asFloats(): List<Float>
    
    /**
     * Returns the number of elements in this data
     */
    val count: Int
}

/**
 * GLTF accessor-based channel data - wraps existing GltfAccessor
 */
class GltfChannelData(private val accessor: GltfAccessor) : ChannelData {
    override val count: Int = accessor.count
    
    override fun asVec3f(): Array<Vec3f> = Vec3fAccessor(accessor).list
    override fun asVec4f(): Array<Vec4f> = Vec4fAccessor(accessor).list
    override fun asFloats(): List<Float> = FloatAccessor(accessor).list.toList()
}

/**
 * Raw Vec3f array channel data - used by FBX loader
 */
class Vec3fChannelData(private val data: Array<Vec3f>) : ChannelData {
    override val count: Int = data.size
    override fun asVec3f(): Array<Vec3f> = data
    override fun asVec4f(): Array<Vec4f> = throw UnsupportedOperationException("Vec3f data cannot be converted to Vec4f")
    override fun asFloats(): List<Float> = throw UnsupportedOperationException("Vec3f data cannot be converted to Floats")
}

/**
 * Raw Vec4f array channel data - used by FBX loader for rotations
 */
class Vec4fChannelData(private val data: Array<Vec4f>) : ChannelData {
    override val count: Int = data.size
    override fun asVec3f(): Array<Vec3f> = throw UnsupportedOperationException("Vec4f data cannot be converted to Vec3f")
    override fun asVec4f(): Array<Vec4f> = data
    override fun asFloats(): List<Float> = throw UnsupportedOperationException("Vec4f data cannot be converted to Floats")
}

/**
 * Raw QuatF array channel data - used by FBX loader for rotations
 */
class QuatfChannelData(private val data: Array<QuatF>) : ChannelData {
    override val count: Int = data.size
    override fun asVec3f(): Array<Vec3f> = throw UnsupportedOperationException("QuatF data cannot be converted to Vec3f")
    override fun asVec4f(): Array<Vec4f> = data.map { Vec4f(it.x, it.y, it.z, it.w) }.toTypedArray()
    override fun asFloats(): List<Float> = throw UnsupportedOperationException("QuatF data cannot be converted to Floats")
}

/**
 * Raw Float array channel data - used by FBX loader for weights
 */
class FloatChannelData(private val data: List<Float>) : ChannelData {
    override val count: Int = data.size
    override fun asVec3f(): Array<Vec3f> = throw UnsupportedOperationException("Float data cannot be converted to Vec3f")
    override fun asVec4f(): Array<Vec4f> = throw UnsupportedOperationException("Float data cannot be converted to Vec4f")
    override fun asFloats(): List<Float> = data
}

/**
 * Animation channel that supports multiple data source formats.
 */
data class Channel(
    val node: Int,
    val path: String,
    val times: List<Float>,
    val interpolation: String,
    val values: ChannelData,
) {
    /**
     * Legacy constructor for backward compatibility with GLTF loader
     */
    constructor(node: Int, path: String, times: List<Float>, interpolation: String, accessor: GltfAccessor)
            : this(node, path, times, interpolation, GltfChannelData(accessor))
}
