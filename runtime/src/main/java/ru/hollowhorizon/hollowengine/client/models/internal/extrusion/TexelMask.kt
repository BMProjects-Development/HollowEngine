package ru.hollowhorizon.hollowengine.client.models.internal.extrusion

/** Occupancy of the base mip, in the same top-to-bottom order as the model's UVs. */
internal class TexelMask(
    val width: Int,
    val height: Int,
    private val visible: BooleanArray,
) {
    operator fun get(x: Int, y: Int): Boolean = visible[y.mod(height) * width + x.mod(width)]
}
