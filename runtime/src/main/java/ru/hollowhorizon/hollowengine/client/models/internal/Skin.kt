package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f

data class SkinInfluence(val joint: Int, val weight: Float)

class Skin(
    val jointsIds: List<Int>,
    val inverseBindMatrices: Array<Mat4f>,
) {
    fun pose(): SkinPose = SkinPose(this)

    /** Use a retained [SkinPose] when computing matrices repeatedly. */
    @Deprecated("Retain a SkinPose per mesh instead")
    fun compute(globalRoot: Mat4f, jointGetter: Map<Int, RuntimeNode>): Array<Mat4f> {
        return pose().compute(globalRoot, jointGetter)
    }
}

/** Reusable joint matrices for one mesh instance. The returned array is a live view of this pose. */
class SkinPose internal constructor(private val skin: Skin) {
    private val matrices: Array<Mat4f> = Array(skin.jointsIds.size) { MutableMat4f() }
    private val inverseRoot = MutableMat4f()

    fun compute(globalRoot: Mat4f, jointGetter: Map<Int, RuntimeNode>): Array<Mat4f> {
        inverseRoot.set(globalRoot)
        inverseRoot.invert()

        for ((i, id) in skin.jointsIds.withIndex()) {
            val result = matrices[i] as? MutableMat4f ?: MutableMat4f().also { matrices[i] = it }
            result.set(inverseRoot).mul(jointGetter.getValue(id).globalMatrix).mul(skin.inverseBindMatrices[i]).transpose()
        }
        return matrices
    }
}

