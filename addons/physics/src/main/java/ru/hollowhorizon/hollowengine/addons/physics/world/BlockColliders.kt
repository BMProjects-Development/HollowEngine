package ru.hollowhorizon.hollowengine.addons.physics.world

import com.github.stephengold.joltjni.*
import com.github.stephengold.joltjni.enumerate.EActivation
import com.github.stephengold.joltjni.enumerate.EMotionType
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.CollisionContext

/**
 * Blocks around what is being simulated, as static bodies.
 */
class BlockColliders(private val world: PhysicsWorld) : AutoCloseable {
    private val blocks = HashMap<Long, Entry>()
    private val wanted = HashSet<Long>()
    private var claimsChanged = false
    private var sinceRescan = 0f
    private var rescan = false

    /** Blocks needed to surround a single simulated object; they remain in place until the object moves or disappears. */
    inner class Patch : AutoCloseable {
        private var center: BlockPos? = null
        private var claimed: Set<Long> = emptySet()

        /**
         * Queries blocks within [radius] of [position], rebuilding the section only if it has moved
         * to a different block.
         */
        fun follow(position: BlockPos, radius: Int) {
            if (center == position) {
                wanted += claimed
                return
            }

            center = position
            claimed = buildSet {
                for (x in -radius..radius) for (y in -radius..radius) for (z in -radius..radius) {
                    add(BlockPos.asLong(position.x + x, position.y + y, position.z + z))
                }
            }
            wanted += claimed
            claimsChanged = true
        }

        override fun close() {
            claimed = emptySet()
            center = null
            claimsChanged = true
        }
    }

    fun patch(): Patch = Patch()

    /**
     * Advances timer that determines when the next world state read will occur.
     */
    fun tick(deltaTime: Float) {
        sinceRescan += deltaTime
        if (sinceRescan < RESCAN_SECONDS) return

        sinceRescan = 0f
        rescan = true
    }

    /**
     * Creates what was requested for this frame, removes what wasn't requested, and, several times per second,
     * updates areas where world itself has changed.
     */
    fun commit() {
        if (claimsChanged) {
            val gone = blocks.keys.filterNot(wanted::contains)
            gone.forEach { position -> blocks.remove(position)?.destroy() }
            claimsChanged = false
        }

        wanted.forEach { position ->
            val existing = blocks[position]
            if (existing != null && !rescan) return@forEach

            val state = world.level.getBlockState(BlockPos.of(position))
            if (existing != null) {
                if (existing.state == state) return@forEach
                existing.destroy()
            }
            blocks[position] = build(position, state)
        }

        rescan = false
        wanted.clear()
    }

    private fun build(packed: Long, state: BlockState): Entry {
        val position = BlockPos.of(packed)
        val shape = if (state.isAir) null else state.getCollisionShape(world.level, position, CollisionContext.empty())
        if (shape == null || shape.isEmpty) return Entry(state, IntArray(0))

        return Entry(state, shape.toAabbs().map { box -> createBox(position, box) }.toIntArray())
    }

    private fun createBox(position: BlockPos, box: AABB): Int {
        val halfExtents = Vec3(
            (box.xsize / 2.0).toFloat().coerceAtLeast(MIN_HALF_EXTENT),
            (box.ysize / 2.0).toFloat().coerceAtLeast(MIN_HALF_EXTENT),
            (box.zsize / 2.0).toFloat().coerceAtLeast(MIN_HALF_EXTENT),
        )
        val center = RVec3(
            position.x + box.minX + box.xsize / 2.0,
            position.y + box.minY + box.ysize / 2.0,
            position.z + box.minZ + box.zsize / 2.0,
        )

        val smallest = minOf(halfExtents.x, halfExtents.y, halfExtents.z)
        val shape = BoxShapeSettings(halfExtents, minOf(Jolt.cDefaultConvexRadius, smallest * 0.5f))

        val settings = BodyCreationSettings(
            shape,
            center,
            Quat.sIdentity(),
            EMotionType.Static,
            PhysicsWorld.LAYER_STATIC,
        )
        settings.setFriction(BLOCK_FRICTION)
        val body = world.bodies.createBody(settings)
        world.bodies.addBody(body.id, EActivation.DontActivate)
        return body.id
    }

    override fun close() {
        blocks.values.forEach(Entry::destroy)
        blocks.clear()
        wanted.clear()
    }

    /** One form of the world: block that was there and the objects created for it. */
    private inner class Entry(val state: BlockState, val bodyIds: IntArray) {
        fun destroy() {
            if (bodyIds.isEmpty()) return

            val bodyInterface = world.bodies
            bodyIds.forEach { id ->
                bodyInterface.removeBody(id)
                bodyInterface.destroyBody(id)
            }
        }
    }

    private companion object {
        const val MIN_HALF_EXTENT = 0.06f
        const val BLOCK_FRICTION = 0.6f
        const val RESCAN_SECONDS = 0.25f
    }
}
