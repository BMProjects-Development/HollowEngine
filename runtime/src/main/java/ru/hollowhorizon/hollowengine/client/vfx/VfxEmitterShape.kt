package ru.hollowhorizon.hollowengine.client.vfx

import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.*
import kotlin.math.*
import kotlin.random.Random

/**
 * Where on its shape an emitter puts a newborn particle, and which way it sends it, in node space.
 */
internal class VfxEmitterShape(private val shape: VfxShape, node: VfxNodeRuntime) {
    private val radius = scalar(node, VfxProperty.SHAPE_RADIUS, shape.radius, 0f)
    private val angle = scalar(node, VfxProperty.SHAPE_ANGLE, shape.angle, 0f)
    private val thickness = scalar(node, VfxProperty.SHAPE_THICKNESS, shape.thickness, 1f)
    private val extents = VfxSamplers.vec3(
        shape.extents, node.expressions, 0f, VfxRangeMode.FRESH,
        VfxProperty.SHAPE_EXTENTS.hashCode(), node.drive(VfxProperty.SHAPE_EXTENTS),
    )

    /** The surface a model shape spawns on; until it has loaded, particles start at the origin. */
    private val modelSurface = shape.model.takeIf { shape.kind == VfxShapeKind.MODEL && it.isNotBlank() }
        ?.let(VfxModelSurfaces::request)

    private val scratch = MutableVec3f()

    /** Picks a spawn point on the shape and the way the particle leaves it. */
    fun pick(position: MutableVec3f, direction: MutableVec3f, context: VfxEvalContext, random: Random) {
        position.set(Vec3f.ZERO)
        direction.set(shape.direction)

        when (shape.kind) {
            VfxShapeKind.POINT -> Unit

            VfxShapeKind.SPHERE -> {
                randomDirection(direction, random)
                val reach = radius.eval(context) * shellDepth(thickness.eval(context), random)
                position.set(direction.x * reach, direction.y * reach, direction.z * reach)
            }

            VfxShapeKind.BOX -> {
                extents.eval(context, scratch)
                position.set(
                    (random.nextFloat() * 2f - 1f) * scratch.x,
                    (random.nextFloat() * 2f - 1f) * scratch.y,
                    (random.nextFloat() * 2f - 1f) * scratch.z,
                )
            }

            VfxShapeKind.CONE -> {
                val around = random.nextFloat() * TAU
                val spread = tan(angle.eval(context).coerceIn(0f, 89f) * DEG_TO_RAD) * random.nextFloat()
                val reach = radius.eval(context) * sqrt(random.nextFloat()) * shellDepth(thickness.eval(context), random)
                position.set(cos(around) * reach, 0f, sin(around) * reach)
                direction.set(cos(around) * spread, 1f, sin(around) * spread).norm()
            }

            VfxShapeKind.DISC -> {
                val around = random.nextFloat() * TAU
                val reach = radius.eval(context) * sqrt(random.nextFloat()) * shellDepth(thickness.eval(context), random)
                position.set(cos(around) * reach, 0f, sin(around) * reach)
                direction.set(position).norm()
                if (direction.sqrLength() < 1.0e-6f) direction.set(shape.direction)
            }

            VfxShapeKind.LINE -> {
                extents.eval(context, scratch)
                val along = random.nextFloat()
                position.set(scratch.x * along, scratch.y * along, scratch.z * along)
            }

            VfxShapeKind.MODEL -> modelSurface?.surface?.sample(random, position, direction)
        }

        when (shape.directionMode) {
            VfxDirectionMode.FIXED -> direction.set(shape.direction)
            VfxDirectionMode.RANDOM -> randomDirection(direction, random)
            VfxDirectionMode.SHAPE -> Unit
        }
        if (direction.sqrLength() < 1.0e-6f) direction.set(Vec3f.Y_AXIS) else direction.norm()
    }

    private fun randomDirection(into: MutableVec3f, random: Random) {
        val z = random.nextFloat() * 2f - 1f
        val around = random.nextFloat() * TAU
        val planar = sqrt((1f - z * z).coerceAtLeast(0f))
        into.set(cos(around) * planar, z, sin(around) * planar)
    }

    private fun shellDepth(thickness: Float, random: Random): Float =
        if (thickness <= 0f) 1f else 1f - random.nextFloat() * thickness.coerceAtMost(1f)

    private companion object {
        const val TAU = (PI * 2.0).toFloat()
        const val DEG_TO_RAD = (PI / 180.0).toFloat()

        fun scalar(node: VfxNodeRuntime, property: VfxProperty, value: VfxValue, default: Float) =
            VfxSamplers.driven(
                VfxSamplers.scalar(value, node.expressions, default, VfxRangeMode.FRESH, property.hashCode()),
                node.drive(property),
            )
    }
}
