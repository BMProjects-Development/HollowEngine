package ru.hollowhorizon.hollowengine.client.decals

import net.minecraft.client.Minecraft
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import org.joml.Matrix4f
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.common.decals.DecalSpec

/** How big a decal is as it is drawn: its own sizes, with the picture's proportions and the reach to a surface filled in. */
class DecalExtent(val width: Float, val length: Float, val reach: Float)

/**
 * Where a decal that reaches the surface below finds it: the surface along a ray, in the space the decal is placed
 * in.
 */
interface DecalGround {
    /** How far along [direction], a unit vector, the first surface from [origin] is; null when the ray meets none. */
    fun distance(origin: Vector3f, direction: Vector3f): Float?

    /** How many units of placement space make a block along [direction] from [origin]. */
    fun unitsPerBlock(origin: Vector3f, direction: Vector3f): Float

    /** The level the camera is in, placements being camera-relative from [camera]. */
    class World(val camera: Vec3) : DecalGround {
        override fun distance(origin: Vector3f, direction: Vector3f): Float? {
            val level = Minecraft.getInstance().level ?: return null
            val from = camera.add(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble())
            val to = from.add(direction.x * SEARCH.toDouble(), direction.y * SEARCH.toDouble(), direction.z * SEARCH.toDouble())
            val hit = level.clip(ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()))
            return if (hit.type == HitResult.Type.MISS) null else hit.location.distanceTo(from).toFloat()
        }

        override fun unitsPerBlock(origin: Vector3f, direction: Vector3f) = 1f
    }

    /** A floor at height zero of the space [toFloor] takes placements to, as previews have under what they show. */
    class Floor(private val toFloor: Matrix4f = Matrix4f()) : DecalGround {
        override fun distance(origin: Vector3f, direction: Vector3f): Float? {
            val start = toFloor.transformPosition(Vector3f(origin))
            val along = toFloor.transformDirection(Vector3f(direction))
            if (start.y < 0f || along.y >= 0f) return null
            return start.y / -along.y
        }

        override fun unitsPerBlock(origin: Vector3f, direction: Vector3f) =
            1f / toFloor.transformDirection(Vector3f(direction)).length().coerceAtLeast(1e-6f)
    }

    companion object {
        /** How far a decal looks for the surface below, in blocks, and how far past it it reaches. */
        const val SEARCH = 16f
        const val PAST_SURFACE = 0.5f

        fun world(camera: Vec3) = World(camera)

        fun floor(toFloor: Matrix4f = Matrix4f()) = Floor(toFloor)
    }
}

/** Fills in what a decal leaves to its picture and to the surfaces below. */
object DecalExtents {
    /** What a decal reaches when it looks for a surface and finds none, in blocks. */
    private const val FALLBACK_REACH = 1f

    /** How many rays across the decal look for the surface it reaches, each way. */
    private const val RAYS = 3

    /**
     * The extent of [spec] placed by [placement], whose down axis it reaches along, looking for the surface on
     * [ground]; lengths are in the units of the decal frame, as its shape is.
     */
    fun of(spec: DecalSpec, placement: Matrix4f, ground: DecalGround?): DecalExtent {
        val width = spec.width
        val length = spec.length ?: (width * DecalTextures.aspect(spec))
        val reach = spec.reach ?: autoReach(spec, placement, DecalExtent(width, length, DecalGround.SEARCH), ground)
        return DecalExtent(width, length, reach)
    }

    /**
     * How deep the decal reaches to lie on every surface it is cast at: rays across its source face, straight down for a
     * box and through the cone for a projector, the deepest surface any of them meets setting it, measured down the
     * axis, and a little past it for steps.
     */
    private fun autoReach(spec: DecalSpec, placement: Matrix4f, search: DecalExtent, ground: DecalGround?): Float {
        val down = placement.transformDirection(Vector3f(0f, -1f, 0f))
        val scale = down.length()
        if (scale <= 1e-6f) return FALLBACK_REACH
        down.div(scale)
        val origin = placement.getTranslation(Vector3f())
        val blocks = ground?.unitsPerBlock(origin, down) ?: return FALLBACK_REACH / scale
        val cube = Matrix4f(placement).mul(DecalShapes.unitCube(spec, search))

        var deepest = -1f
        for (column in 0 until RAYS) for (row in 0 until RAYS) {
            val x = column * 2f / (RAYS - 1) - 1f
            val y = row * 2f / (RAYS - 1) - 1f
            val near = cube.transformProject(Vector3f(x, y, -1f))
            val direction = cube.transformProject(Vector3f(x, y, 1f)).sub(near).normalize()
            val distance = ground.distance(near, direction) ?: continue
            val hit = Vector3f(direction).mul(distance).add(near)
            deepest = maxOf(deepest, Vector3f(hit).sub(origin).dot(down))
        }
        if (deepest < 0f) return FALLBACK_REACH * blocks / scale
        return (deepest + DecalGround.PAST_SURFACE * blocks) / scale
    }
}
