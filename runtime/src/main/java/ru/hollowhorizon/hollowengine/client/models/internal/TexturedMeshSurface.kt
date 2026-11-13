package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec4f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec4i

/** Authored vertex data that runtime mesh modifiers need even after the original mesh has been uploaded. */
internal class TexturedMeshSurface(
    val positions: Array<Vec3f>,
    val uv: Array<Vec2f>,
    val joints: Array<Vec4i>?,
    val weights: Array<Vec4f>?,
    val additionalWeights: Array<List<SkinInfluence>>?,
)
