package ru.hollowhorizon.hollowengine.client.models.internal.rendering

import org.joml.Matrix3f
import org.joml.Matrix4f
import kotlin.math.abs
import kotlin.math.cbrt

internal fun normalMatrixOf(matrix: Matrix4f): Matrix3f = normalMatrixOf(matrix, Matrix3f())

/**
 * Writes into [dest] the matrix that carries normals the way [matrix] carries positions, keeping their length
 * under a uniform scale; a degenerate [matrix] gives the identity.
 */
internal fun normalMatrixOf(matrix: Matrix4f, dest: Matrix3f): Matrix3f {
    dest.set(matrix)
    val determinant = dest.determinant()

    if (!determinant.isFinite() || abs(determinant) < 1e-8f) {
        return dest.identity()
    }

    return dest.invert().transpose().scale(cbrt(abs(determinant)))
}
