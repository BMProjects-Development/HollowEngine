package ru.hollowhorizon.hollowengine.client.decals

import org.joml.Matrix4f
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.common.decals.DecalProjection
import ru.hollowhorizon.hollowengine.common.decals.DecalSpec

/** The parts of a decal guide, each drawn its own way. */
enum class DecalGuidePart {
    /** The edges of what the decal reaches. */
    VOLUME,

    /** The face the picture is cast from, with a mark on the side that is the top of the picture. */
    SOURCE,

    /** The way the picture is cast, from the source face down to where the decal stops reaching. */
    DIRECTION,
}

/** The shape a decal reaches, as what takes the unit cube to the frame of the decal, and the guide drawn over it. */
object DecalShapes {
    /** How close to the projector a cone starts, in blocks. */
    private const val PROJECTOR_NEAR = 0.05f

    /**
     * Takes the unit cube to the decal frame, as big as [extent] says: x across the picture, y along it, z into the surface, its near face at
     * z = -1 on the place of the decal. A box reaches from there straight down its depth; a projector casts from
     * there, its cone opening with the field of view as far as its range.
     */
    fun unitCube(spec: DecalSpec, extent: DecalExtent): Matrix4f {
        val width = extent.width
        val length = extent.length
        val reach = extent.reach
        return when (spec.projection) {
            DecalProjection.BOX -> Matrix4f(
                width / 2f, 0f, 0f, 0f,
                0f, 0f, -length / 2f, 0f,
                0f, -reach / 2f, 0f, 0f,
                0f, -reach / 2f, 0f, 1f,
            )

            DecalProjection.PROJECTOR -> {
                val aspect = (width / length.coerceAtLeast(1e-3f)).coerceAtLeast(1e-3f)
                val range = reach.coerceAtLeast(PROJECTOR_NEAR * 2f)
                val fov = Math.toRadians(spec.fieldOfView.coerceIn(1f, 170f).toDouble()).toFloat()
                Matrix4f().rotateX(-Math.PI.toFloat() / 2f)
                    .mul(Matrix4f().setPerspective(fov, aspect, PROJECTOR_NEAR, range).invert())
            }
        }
    }

    /** The twelve edges of the shape [cube] takes the unit cube to, each as its two ends. */
    fun edges(cube: Matrix4f, edge: (Vector3f, Vector3f) -> Unit) {
        val corners = Array(8) { index -> cube.at(sign(index, 1), sign(index, 2), sign(index, 4)) }
        for (index in 0 until 8) for (axis in intArrayOf(1, 2, 4)) {
            if (index and axis == 0) edge(corners[index], corners[index or axis])
        }
    }

    /**
     * What shows how a decal is cast, in the space [cube] takes the unit cube to: the volume it reaches, the face it
     * is cast from with an arrowhead pointing to the top of the picture, and an arrow along the way it is cast.
     */
    fun guide(cube: Matrix4f, segment: (Vector3f, Vector3f, DecalGuidePart) -> Unit) {
        edges(cube) { start, end -> segment(start, end, DecalGuidePart.VOLUME) }

        val source = listOf(cube.at(-1f, -1f, -1f), cube.at(1f, -1f, -1f), cube.at(1f, 1f, -1f), cube.at(-1f, 1f, -1f))
        source.indices.forEach { index -> segment(source[index], source[(index + 1) % 4], DecalGuidePart.SOURCE) }
        val tip = cube.at(0f, 0.9f, -1f)
        segment(cube.at(-0.25f, 0.55f, -1f), tip, DecalGuidePart.SOURCE)
        segment(cube.at(0.25f, 0.55f, -1f), tip, DecalGuidePart.SOURCE)

        val from = cube.at(0f, 0f, -1f)
        val to = cube.at(0f, 0f, 1f)
        segment(from, to, DecalGuidePart.DIRECTION)
        val along = Vector3f(to).sub(from)
        val length = along.length()
        if (length <= 1e-4f) return
        along.div(length)
        val across = Vector3f(cube.at(1f, 0f, 1f)).sub(to).normalize()
        val head = length.coerceAtMost(HEAD_LIMIT) * HEAD_SHARE
        val back = Vector3f(to).sub(Vector3f(along).mul(head))
        segment(Vector3f(back).add(Vector3f(across).mul(head * 0.5f)), to, DecalGuidePart.DIRECTION)
        segment(Vector3f(back).sub(Vector3f(across).mul(head * 0.5f)), to, DecalGuidePart.DIRECTION)
    }

    private fun Matrix4f.at(x: Float, y: Float, z: Float): Vector3f = transformProject(Vector3f(x, y, z))

    private fun sign(index: Int, bit: Int) = if (index and bit != 0) 1f else -1f

    /** How long the arrowhead is, as a share of the arrow, and how long a long arrow gets it at most, in blocks. */
    private const val HEAD_SHARE = 0.2f
    private const val HEAD_LIMIT = 2f
}
