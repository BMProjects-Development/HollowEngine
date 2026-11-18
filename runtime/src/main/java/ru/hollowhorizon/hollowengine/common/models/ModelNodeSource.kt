package ru.hollowhorizon.hollowengine.common.models

import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF

/** Shared source metadata needed by animation and physics; it contains no instance pose. */
interface ModelNodeSource {
    val index: Int
    val name: String?
    val path: String
    val hasMesh: Boolean
    val skinJoints: List<Int>
    val restTransform: TrsTransformF
}
