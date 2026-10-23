package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f

data class SkinInfluence(val joint: Int, val weight: Float)

class Skin(
    val jointsIds: List<Int>,
    val inverseBindMatrices: Array<Mat4f>,
) {
    private val cache: Array<Mat4f> = Array(jointsIds.size) { MutableMat4f() }

    fun compute(globalRoot: Mat4f, jointGetter: Map<Int, RuntimeNode>): Array<Mat4f> {
        // Получаем и инвертируем глобальную матрицу узла модели
        val inverseRoot = MutableMat4f(globalRoot)
        inverseRoot.invert()

        // Проходим по всем суставам
        for ((i, id) in jointsIds.withIndex()) {
            val jointGlobalMatrix = MutableMat4f(jointGetter[id]!!.globalMatrix)
            val bindMatrix = MutableMat4f(inverseBindMatrices[i])
            val skinMatrix = MutableMat4f(jointGlobalMatrix).mul(bindMatrix)
            cache[i] = MutableMat4f(inverseRoot).mul(skinMatrix).transpose() // Транспозируем уже для передачи в Minecraft
        }
        return cache
    }
}

